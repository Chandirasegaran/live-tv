//! Guide job: map our channels to Tata Play / DishTV / Airtel Xstream ids via
//! iptv-org guides.json, scrape today + tomorrow (IST), keep compact JSON.
//! The channel list comes from the same iptv-org playlists the app plays.

pub mod airtel;
pub mod dishtv;
pub mod guides;
pub mod tataplay;

use crate::state::AppState;
use crate::timeutil::{now, IstDate, DAY};
use async_trait::async_trait;
use futures::StreamExt;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// `[start, end, title, description]`, Unix seconds UTC. Serialises as a JSON array.
pub type Prog = (i64, i64, String, String);

const MAX_DESC: usize = 400;
/// Site ids tried per channel per site (e.g. SD then HD feed).
const MAX_IDS_PER_SITE: usize = 2;
/// Give up on a site after this many failed requests in a row.
const BREAKER: usize = 25;
/// Pause for COOLDOWN after every COOLDOWN_EVERY failures in a row.
const COOLDOWN_EVERY: usize = 5;
const COOLDOWN: Duration = Duration::from_secs(60);
/// Log progress every this many channels per site.
const PROGRESS_EVERY: usize = 50;
/// Retry delay after a run that got nothing.
const RETRY: Duration = Duration::from_secs(3600);

#[async_trait]
pub trait Scraper: Send + Sync {
    /// Site name as used in guides.json.
    fn site(&self) -> &'static str;
    /// Parallel requests to this site.
    fn concurrency(&self) -> usize {
        2
    }
    /// Pause after each request, per worker.
    fn pause(&self) -> Duration {
        Duration::from_millis(300)
    }
    /// Only scrape channels the other sites left without a guide. For slow,
    /// rate-limited sites (Tata Play allows 30 requests a minute per IP).
    fn gap_filler(&self) -> bool {
        false
    }
    /// One-off setup per run (e.g. auth token).
    async fn prepare(&self, _client: &Client) -> Result<(), String> {
        Ok(())
    }
    async fn fetch_day(
        &self,
        client: &Client,
        site_id: &str,
        day: IstDate,
    ) -> Result<Vec<Prog>, String>;
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SiteStats {
    pub site: String,
    /// Channels we asked this site for.
    pub tried: usize,
    /// Channels that got a guide from this site.
    pub ok: usize,
    pub empty: usize,
    pub errors: usize,
    pub programmes: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fatal: Option<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct EpgState {
    /// Last time the published guide changed.
    pub updated: i64,
    /// Last time a full run (all sites) finished; drives the schedule.
    #[serde(default)]
    pub completed: i64,
    /// Channel keys found in the playlists.
    #[serde(default)]
    pub wanted: usize,
    /// Sites still scraping in the current run.
    #[serde(default)]
    pub running: Vec<String>,
    pub channels: HashMap<String, Vec<Prog>>,
    /// Which site each channel's guide came from.
    #[serde(default)]
    pub source: HashMap<String, String>,
    #[serde(default)]
    pub sites: Vec<SiteStats>,
    #[serde(default)]
    pub mapped: usize,
}

/// Earlier sites win when several have the same channel.
pub fn scrapers() -> Vec<Box<dyn Scraper>> {
    vec![
        Box::new(dishtv::DishTv::default()),
        Box::new(airtel::Airtel),
        Box::new(tataplay::TataPlay),
    ]
}

pub async fn run_forever(state: Arc<AppState>) {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .gzip(true)
        .build()
        .expect("http client");
    let interval = state.cfg.epg_interval;
    let last = state.epg.read().unwrap().completed;
    let mut due_in = Duration::from_secs((last + interval.as_secs() as i64 - now()).max(0) as u64);
    loop {
        if !due_in.is_zero() {
            tracing::info!(in_secs = due_in.as_secs(), "next guide refresh scheduled");
            tokio::time::sleep(due_in).await;
        }
        due_in = if refresh(&state, &client).await {
            interval
        } else {
            interval.min(RETRY)
        };
    }
}

/// Channel keys (tvg-id without @feed) -> feeds, from the iptv-org playlists the app uses.
/// Falls back to the last saved list if a playlist can't be downloaded.
async fn load_wanted(state: &AppState, client: &Client) -> Option<BTreeMap<String, Vec<String>>> {
    let mut wanted: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for src in &state.cfg.sources {
        let r = client
            .get(src)
            .header(reqwest::header::USER_AGENT, crate::BOT_UA)
            .timeout(Duration::from_secs(60))
            .send()
            .await;
        match body(r).await {
            Ok(text) => {
                let list = crate::m3u::parse(&text);
                tracing::info!(source = %src, channels = list.len(), "fetched playlist");
                for ch in list.iter().filter(|c| !c.tvg_id.is_empty()) {
                    let feeds = wanted.entry(ch.key().to_string()).or_default();
                    if let Some(f) = ch.feed() {
                        feeds.push(f.to_string());
                    }
                }
            }
            Err(e) => {
                tracing::warn!(source = %src, error = %e, "playlist download failed; using saved channel list");
                return crate::state::load(&state.cfg.data_dir, "channels.json");
            }
        }
    }
    crate::state::save(&state.cfg.data_dir, "channels.json", &wanted);
    Some(wanted)
}

struct SiteResult {
    priority: usize,
    site: &'static str,
    stats: SiteStats,
    got: HashMap<String, Vec<Prog>>,
}

/// Scrapes the quick sites (DishTV, Airtel) in parallel, publishing after each,
/// then lets gap fillers (Tata Play, 30 requests a minute) cover only the
/// channels still missing. Returns false when nothing was scraped, so the job
/// retries sooner.
pub async fn refresh(state: &Arc<AppState>, client: &Client) -> bool {
    let started = std::time::Instant::now();
    let Some(wanted) = load_wanted(state, client).await else {
        tracing::error!("no channel list available; skipping guide run");
        return false;
    };
    let scrapers: Vec<_> = scrapers()
        .into_iter()
        .filter(|s| {
            state
                .cfg
                .epg_sites
                .as_ref()
                .is_none_or(|only| only.iter().any(|o| o == s.site()))
        })
        .collect();
    let sites: Vec<&str> = scrapers.iter().map(|s| s.site()).collect();
    let map = match guides::load_map(client, &state.cfg.data_dir, &sites, &wanted).await {
        Ok(m) => m,
        Err(e) => {
            tracing::error!(error = %e, "no channel→site mapping available; skipping guide run");
            return false;
        }
    };
    tracing::info!(
        wanted = wanted.len(),
        mapped = map.len(),
        "channels mapped to guide sites"
    );
    {
        let mut e = state.epg.write().unwrap();
        e.mapped = map.len();
        e.wanted = wanted.len();
        e.running = sites.iter().map(|s| s.to_string()).collect();
    }

    // Last run's data, kept for channels this run misses while it still covers the future.
    let (old, old_source) = {
        let e = state.epg.read().unwrap();
        (e.channels.clone(), e.source.clone())
    };
    let today = IstDate::today();
    let days = [today, today.next()];
    let done: std::sync::Mutex<Vec<SiteResult>> = std::sync::Mutex::new(Vec::new());

    let ctx = RunCtx { state, client, days, done: &done, old: &old, old_source: &old_source };
    let (fillers, quick): (Vec<_>, Vec<_>) =
        scrapers.iter().enumerate().partition(|(_, s)| s.gap_filler());
    futures::future::join_all(quick.iter().map(|(i, s)| ctx.run(*i, s.as_ref(), map.clone()))).await;
    for (i, s) in fillers {
        let covered: HashSet<String> = done
            .lock()
            .unwrap()
            .iter()
            .flat_map(|r| r.got.keys().cloned())
            .collect();
        let gaps: guides::SiteMap = map
            .iter()
            .filter(|(k, _)| !covered.contains(*k))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        tracing::info!(site = s.site(), covered = covered.len(), gaps = gaps.len(), "filling gaps");
        ctx.run(i, s.as_ref(), gaps).await;
    }

    let done = done.into_inner().unwrap();
    let fresh: usize = done.iter().map(|r| r.got.len()).sum();
    {
        let mut e = state.epg.write().unwrap();
        e.completed = now();
        e.running.clear();
        tracing::info!(
            total = e.channels.len(),
            secs = started.elapsed().as_secs(),
            "guide refresh done"
        );
    }
    state.save_epg();
    fresh > 0
}

/// What every site run in one refresh shares.
struct RunCtx<'a> {
    state: &'a Arc<AppState>,
    client: &'a Client,
    days: [IstDate; 2],
    done: &'a std::sync::Mutex<Vec<SiteResult>>,
    old: &'a HashMap<String, Vec<Prog>>,
    old_source: &'a HashMap<String, String>,
}

impl RunCtx<'_> {
    /// Scrapes one site and publishes the combined guide straight away.
    async fn run(&self, priority: usize, scraper: &dyn Scraper, map: guides::SiteMap) {
        let (stats, got) = scrape_site(scraper, self.client, &map, self.days).await;
        let mut done = self.done.lock().unwrap();
        done.push(SiteResult {
            priority,
            site: scraper.site(),
            stats,
            got,
        });
        publish(self.state, &done, self.old, self.old_source);
    }
}

/// Combines finished sites (in `scrapers()` order per channel) plus carried-over data.
fn publish(
    state: &AppState,
    done: &[SiteResult],
    old: &HashMap<String, Vec<Prog>>,
    old_source: &HashMap<String, String>,
) {
    let mut order: Vec<&SiteResult> = done.iter().collect();
    order.sort_by_key(|r| r.priority);
    let mut channels: HashMap<String, Vec<Prog>> = HashMap::new();
    let mut source: HashMap<String, String> = HashMap::new();
    for r in &order {
        for (k, v) in &r.got {
            if !channels.contains_key(k) {
                channels.insert(k.clone(), v.clone());
                source.insert(k.clone(), r.site.to_string());
            }
        }
    }
    let t = now();
    let mut carried = 0;
    for (k, v) in old {
        if !channels.contains_key(k) && v.iter().any(|p| p.1 > t) {
            channels.insert(k.clone(), v.clone());
            if let Some(s) = old_source.get(k) {
                source.insert(k.clone(), s.clone());
            }
            carried += 1;
        }
    }
    {
        let mut e = state.epg.write().unwrap();
        tracing::info!(
            sites_done = done.len(),
            channels = channels.len(),
            carried,
            "guide published"
        );
        e.channels = channels;
        e.source = source;
        e.sites = order.iter().map(|r| r.stats.clone()).collect();
        e.running.retain(|s| !done.iter().any(|r| r.site == s));
        e.updated = t;
    }
    *state.now_cache.lock().unwrap() = None;
    state.save_epg();
}

async fn scrape_site(
    scraper: &dyn Scraper,
    client: &Client,
    map: &guides::SiteMap,
    days: [IstDate; 2],
) -> (SiteStats, HashMap<String, Vec<Prog>>) {
    let site = scraper.site();
    let jobs: Vec<(String, Vec<String>)> = map
        .iter()
        .filter_map(|(k, by_site)| by_site.get(site).map(|ids| (k.clone(), ids.clone())))
        .collect();
    let total = jobs.len();
    let mut stats = SiteStats {
        site: site.to_string(),
        tried: total,
        ..Default::default()
    };
    let mut got = HashMap::new();
    if jobs.is_empty() {
        return (stats, got);
    }
    tracing::info!(site, channels = total, "site scrape starting");
    if let Err(e) = scraper.prepare(client).await {
        tracing::error!(site, error = %e, "site setup failed; skipping site");
        stats.fatal = Some(e);
        return (stats, got);
    }
    let streak = AtomicUsize::new(0);
    let errors = AtomicUsize::new(0);
    let finished = AtomicUsize::new(0);
    let ok = AtomicUsize::new(0);
    let site_started = std::time::Instant::now();
    let results: Vec<(String, Option<Vec<Prog>>)> = futures::stream::iter(jobs)
        .map(|(key, ids)| {
            let (streak, errors, finished, ok) = (&streak, &errors, &finished, &ok);
            async move {
                let mut out = Some(Vec::new());
                'ids: for id in ids.iter().take(MAX_IDS_PER_SITE) {
                    let mut progs = Vec::new();
                    for (i, day) in days.iter().enumerate() {
                        if streak.load(Ordering::Relaxed) >= BREAKER {
                            out = None;
                            break 'ids;
                        }
                        let r = fetch_retry(scraper, client, id, *day).await;
                        tokio::time::sleep(scraper.pause()).await;
                        match r {
                            Ok(p) => {
                                streak.store(0, Ordering::Relaxed);
                                if p.is_empty() && i == 0 {
                                    break; // nothing today: try next id
                                }
                                progs.extend(p);
                            }
                            Err(e) => {
                                let n = streak.fetch_add(1, Ordering::Relaxed) + 1;
                                if errors.fetch_add(1, Ordering::Relaxed) < 5 {
                                    tracing::warn!(site, id, error = %e, "fetch failed");
                                } else {
                                    tracing::debug!(site, id, error = %e, "fetch failed");
                                }
                                if n % COOLDOWN_EVERY == 0 && n < BREAKER {
                                    tracing::warn!(site, errors_in_a_row = n, "cooling down");
                                    tokio::time::sleep(COOLDOWN).await;
                                }
                                if i == 0 {
                                    break;
                                }
                            }
                        }
                    }
                    if !progs.is_empty() {
                        ok.fetch_add(1, Ordering::Relaxed);
                        out = Some(progs);
                        break;
                    }
                }
                let n = finished.fetch_add(1, Ordering::Relaxed) + 1;
                if n % PROGRESS_EVERY == 0 && n < total {
                    tracing::info!(
                        site,
                        done = n,
                        total,
                        ok = ok.load(Ordering::Relaxed),
                        errors = errors.load(Ordering::Relaxed),
                        secs = site_started.elapsed().as_secs(),
                        "site progress"
                    );
                }
                (key, out)
            }
        })
        .buffer_unordered(scraper.concurrency())
        .collect()
        .await;
    if streak.load(Ordering::Relaxed) >= BREAKER {
        stats.fatal = Some(format!(
            "{BREAKER} failures in a row; site skipped for this run"
        ));
    }
    stats.errors = errors.load(Ordering::Relaxed);
    for (key, progs) in results {
        match progs {
            Some(p) if !p.is_empty() => {
                let p = normalize(p);
                stats.ok += 1;
                stats.programmes += p.len();
                got.insert(key, p);
            }
            Some(_) => stats.empty += 1,
            None => {}
        }
    }
    tracing::info!(
        site,
        tried = stats.tried,
        ok = stats.ok,
        empty = stats.empty,
        request_errors = stats.errors,
        programmes = stats.programmes,
        secs = site_started.elapsed().as_secs(),
        fatal = stats.fatal.as_deref().unwrap_or("-"),
        "site scrape done"
    );
    (stats, got)
}

/// One retry on network/5xx errors. Every attempt has a hard cap on top of the
/// client's 30 s request timeout (Tata Play may also wait out a 429 once).
async fn fetch_retry(
    s: &dyn Scraper,
    client: &Client,
    id: &str,
    day: IstDate,
) -> Result<Vec<Prog>, String> {
    let once = || async {
        tokio::time::timeout(Duration::from_secs(180), s.fetch_day(client, id, day))
            .await
            .unwrap_or_else(|_| Err("hard timeout".into()))
    };
    match once().await {
        Ok(p) => Ok(p),
        Err(e) if e.starts_with("HTTP 4") || e.starts_with("json") => Err(e),
        Err(_) => {
            tokio::time::sleep(Duration::from_secs(2)).await;
            once().await
        }
    }
}

/// Sort, drop broken/duplicate rows, trim descriptions.
pub fn normalize(mut p: Vec<Prog>) -> Vec<Prog> {
    p.retain(|x| x.1 > x.0 && !x.2.trim().is_empty());
    p.sort_by_key(|x| (x.0, x.1));
    let mut seen = HashSet::new();
    p.retain(|x| seen.insert(x.0));
    for x in p.iter_mut() {
        x.2 = x.2.trim().to_string();
        let d = x.3.trim();
        x.3 = if d.chars().count() > MAX_DESC {
            let mut s: String = d.chars().take(MAX_DESC - 1).collect();
            s.push('…');
            s
        } else {
            d.to_string()
        };
    }
    p
}

/// Current programme plus the one after it.
pub fn now_next(progs: &[Prog], t: i64) -> Option<Vec<(i64, i64, &str)>> {
    let i = progs.iter().position(|p| p.0 <= t && t < p.1)?;
    let mut out = vec![(progs[i].0, progs[i].1, progs[i].2.as_str())];
    if let Some(n) = progs[i + 1..].iter().find(|p| p.0 >= progs[i].1 - 60) {
        out.push((n.0, n.1, n.2.as_str()));
    }
    Some(out)
}

/// Programmes from now through the end of tomorrow (IST).
pub fn window(progs: &[Prog], t: i64) -> Vec<&Prog> {
    let end = IstDate::of(t).start_utc() + 2 * DAY;
    progs.iter().filter(|p| p.1 > t && p.0 < end).collect()
}

/// Shared helper for scrapers: read a response as text, mapping non-2xx to an error.
pub async fn body(resp: reqwest::Result<reqwest::Response>) -> Result<String, String> {
    let r = resp.map_err(|e| {
        if e.is_timeout() {
            "timeout".to_string()
        } else {
            e.to_string()
        }
    })?;
    let st = r.status();
    if !st.is_success() {
        return Err(format!("HTTP {}", st.as_u16()));
    }
    r.text().await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: i64, e: i64, t: &str) -> Prog {
        (s, e, t.to_string(), String::new())
    }

    #[test]
    fn now_next_and_window() {
        let v = normalize(vec![
            p(200, 300, "B"),
            p(100, 200, "A"),
            p(100, 200, "A dup"),
            p(300, 400, "C"),
            p(5, 5, "bad"),
        ]);
        assert_eq!(v.len(), 3);
        let nn = now_next(&v, 150).unwrap();
        assert_eq!(nn, vec![(100, 200, "A"), (200, 300, "B")]);
        assert!(now_next(&v, 1000).is_none());
        assert_eq!(window(&v, 250).len(), 2);
    }
}
