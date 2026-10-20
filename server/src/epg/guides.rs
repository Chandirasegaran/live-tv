//! Channel → site_id mapping from iptv-org's guides.json (~25 MB).
//! Rows are filtered while deserialising so only the few we need stay in memory.
//! The filtered map is cached in DATA_DIR in case the download fails later.

use serde::de::{DeserializeSeed, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::path::Path;
use std::time::Duration;

pub const GUIDES_URL: &str = "https://iptv-org.github.io/api/guides.json";

/// key -> site -> site ids (best feed first)
pub type SiteMap = BTreeMap<String, HashMap<String, Vec<String>>>;

#[derive(Debug, Deserialize)]
pub struct Row {
    pub channel: Option<String>,
    pub feed: Option<String>,
    pub site: String,
    pub site_id: String,
}

struct Filter<'a> {
    sites: &'a [&'a str],
}

impl<'de> DeserializeSeed<'de> for Filter<'_> {
    type Value = Vec<Row>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_seq(self)
    }
}

impl<'de> Visitor<'de> for Filter<'_> {
    type Value = Vec<Row>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an array of guides")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
        let mut out = Vec::new();
        while let Some(row) = seq.next_element::<Row>()? {
            if row.channel.is_some() && self.sites.contains(&row.site.as_str()) {
                out.push(row);
            }
        }
        Ok(out)
    }
}

pub fn parse_rows(json: &[u8], sites: &[&str]) -> Result<Vec<Row>, String> {
    let mut de = serde_json::Deserializer::from_slice(json);
    Filter { sites }
        .deserialize(&mut de)
        .map_err(|e| e.to_string())
}

/// Builds the map for the keys we carry. Site ids whose feed matches one of our
/// feeds come first, then SD, then the rest.
pub fn build_map(rows: Vec<Row>, wanted: &BTreeMap<String, Vec<String>>) -> SiteMap {
    let mut map: SiteMap = BTreeMap::new();
    let mut ranked: HashMap<(String, String), Vec<(u8, String)>> = HashMap::new();
    for r in rows {
        let Some(key) = r.channel else { continue };
        let Some(our_feeds) = wanted.get(&key) else {
            continue;
        };
        if r.site_id.trim().is_empty() || r.site_id == "#" {
            continue;
        }
        let feed = r.feed.unwrap_or_default();
        let rank = if our_feeds.iter().any(|f| f == &feed) {
            0
        } else if feed == "SD" {
            1
        } else {
            2
        };
        ranked
            .entry((key, r.site))
            .or_default()
            .push((rank, r.site_id));
    }
    for ((key, site), mut ids) in ranked {
        ids.sort();
        ids.dedup_by(|a, b| a.1 == b.1);
        map.entry(key)
            .or_default()
            .insert(site, ids.into_iter().map(|x| x.1).collect());
    }
    map
}

pub async fn load_map(
    client: &reqwest::Client,
    data_dir: &Path,
    sites: &[&str],
    wanted: &BTreeMap<String, Vec<String>>,
) -> Result<SiteMap, String> {
    let fetched = async {
        let r = client
            .get(GUIDES_URL)
            .header(reqwest::header::USER_AGENT, crate::BOT_UA)
            .timeout(Duration::from_secs(120))
            .send()
            .await;
        let bytes = super::body(r).await?;
        parse_rows(bytes.as_bytes(), sites)
    }
    .await;
    match fetched {
        Ok(rows) => {
            tracing::info!(rows = rows.len(), "guides.json rows for our sites");
            let map = build_map(rows, wanted);
            crate::state::save(data_dir, "guides_map.json", &map);
            Ok(map)
        }
        Err(e) => {
            tracing::warn!(error = %e, "guides.json download failed; using cached mapping");
            let cached: SiteMap = crate::state::load(data_dir, "guides_map.json").ok_or(e)?;
            Ok(cached
                .into_iter()
                .filter(|(k, _)| wanted.contains_key(k))
                .collect())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filters_and_ranks() {
        let json = br##"[
          {"channel":"Channel9.il","feed":"SD","site":"9tv.co.il","site_id":"#","site_name":"x","lang":"ru","sources":[]},
          {"channel":"SunTV.in","feed":"HD","site":"tataplay.com","site_id":"521","site_name":"Sun TV HD","lang":"en","sources":[]},
          {"channel":"SunTV.in","feed":"SD","site":"tataplay.com","site_id":"165","site_name":"Sun TV","lang":"en","sources":[]},
          {"channel":null,"feed":null,"site":"tataplay.com","site_id":"974","site_name":"1st Gujarat","lang":"en","sources":[]},
          {"channel":"SunTV.in","feed":"SD","site":"dishtv.in","site_id":"117852","site_name":"Sun TV","lang":"en","sources":[]},
          {"channel":"KTV.in","feed":"HD","site":"airtelxstream.in","site_id":"LIVETV_KTV_HD","site_name":"KTV HD","lang":"en","sources":[]},
          {"channel":"Other.in","feed":"SD","site":"dishtv.in","site_id":"1","site_name":"x","lang":"en","sources":[]}
        ]"##;
        let rows = parse_rows(json, &["tataplay.com", "dishtv.in", "airtelxstream.in"]).unwrap();
        assert_eq!(rows.len(), 5);
        let mut wanted = BTreeMap::new();
        wanted.insert("SunTV.in".to_string(), vec!["SD".to_string()]);
        wanted.insert("KTV.in".to_string(), vec!["SD".to_string()]);
        let m = build_map(rows, &wanted);
        assert_eq!(m.len(), 2);
        assert_eq!(m["SunTV.in"]["tataplay.com"], vec!["165", "521"]);
        assert_eq!(m["SunTV.in"]["dishtv.in"], vec!["117852"]);
        assert_eq!(m["KTV.in"]["airtelxstream.in"], vec!["LIVETV_KTV_HD"]);
    }
}
