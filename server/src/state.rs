//! Shared in-memory state plus JSON snapshots in DATA_DIR.

use crate::config::Config;
use crate::epg::EpgState;
use axum::body::Bytes;
use serde::{de::DeserializeOwned, Serialize};
use std::path::Path;
use std::sync::{Mutex, RwLock};

pub struct AppState {
    pub cfg: Config,
    pub epg: RwLock<EpgState>,
    /// `/epg/now.json` body, cached per minute.
    pub now_cache: Mutex<Option<(i64, Bytes)>>,
    pub started_at: i64,
}

impl AppState {
    pub fn new(cfg: Config) -> Self {
        let epg: EpgState = load(&cfg.data_dir, "epg.json").unwrap_or_default();
        tracing::info!(guide_channels = epg.channels.len(), "loaded snapshots");
        AppState {
            cfg,
            epg: RwLock::new(epg),
            now_cache: Mutex::new(None),
            started_at: crate::timeutil::now(),
        }
    }

    pub fn save_epg(&self) {
        let e = self.epg.read().unwrap();
        save(&self.cfg.data_dir, "epg.json", &*e);
    }
}

pub fn load<T: DeserializeOwned>(dir: &Path, name: &str) -> Option<T> {
    let path = dir.join(name);
    let data = std::fs::read(&path).ok()?;
    match serde_json::from_slice(&data) {
        Ok(v) => Some(v),
        Err(e) => {
            tracing::warn!(file = %path.display(), error = %e, "ignoring unreadable snapshot");
            None
        }
    }
}

/// Writes atomically (temp file + rename) so a crash never leaves a torn snapshot.
pub fn save<T: Serialize + ?Sized>(dir: &Path, name: &str, value: &T) {
    let path = dir.join(name);
    let tmp = dir.join(format!("{name}.tmp"));
    let result = (|| -> std::io::Result<()> {
        std::fs::create_dir_all(dir)?;
        let data = serde_json::to_vec(value).map_err(std::io::Error::other)?;
        std::fs::write(&tmp, data)?;
        std::fs::rename(&tmp, &path)
    })();
    if let Err(e) = result {
        tracing::error!(file = %path.display(), error = %e, "failed to write snapshot");
    }
}
