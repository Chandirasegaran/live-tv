use std::path::PathBuf;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Config {
    pub port: u16,
    pub data_dir: PathBuf,
    pub epg_interval: Duration,
    /// Playlists whose tvg-ids decide which channels get a guide.
    pub sources: Vec<String>,
    /// Restrict guide scraping to these sites (EPG_SITES=tataplay.com,dishtv.in); None = all.
    pub epg_sites: Option<Vec<String>>,
}

fn env_or<T: std::str::FromStr>(name: &str, default: T) -> T {
    match std::env::var(name) {
        Ok(v) if !v.trim().is_empty() => v.trim().parse().unwrap_or_else(|_| {
            tracing::warn!("{name}={v:?} is invalid, using default");
            default
        }),
        _ => default,
    }
}

fn list(name: &str) -> Option<Vec<String>> {
    let v: Vec<String> = std::env::var(name)
        .ok()?
        .split(',')
        .map(|u| u.trim().to_string())
        .filter(|u| !u.is_empty())
        .collect();
    (!v.is_empty()).then_some(v)
}

fn hours(h: f64) -> Duration {
    Duration::from_secs_f64((h.max(0.05)) * 3600.0)
}

impl Config {
    pub fn from_env() -> Self {
        let sources = list("PLAYLIST_SOURCES").unwrap_or_else(|| {
            vec![
                // Order matters: Tamil first.
                "https://iptv-org.github.io/iptv/languages/tam.m3u".to_string(),
                "https://iptv-org.github.io/iptv/countries/in.m3u".to_string(),
            ]
        });
        Config {
            port: env_or("PORT", 5001),
            data_dir: PathBuf::from(env_or("DATA_DIR", "./data".to_string())),
            epg_interval: hours(env_or("EPG_INTERVAL_H", 24.0)),
            sources,
            epg_sites: list("EPG_SITES"),
        }
    }
}
