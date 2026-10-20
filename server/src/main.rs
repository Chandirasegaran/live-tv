mod config;
mod epg;
mod m3u;
mod state;
mod timeutil;

use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use serde_json::json;
use state::AppState;
use std::sync::Arc;
use tower_http::compression::CompressionLayer;
use tracing_subscriber::EnvFilter;

/// User agent for our own (polite) requests to iptv-org.
pub const BOT_UA: &str = concat!(
    "LiveTV-server/",
    env!("CARGO_PKG_VERSION"),
    " (+https://tvepg.chandirasegaran.dpdns.org)"
);

type Shared = Arc<AppState>;

fn main() {
    // `livetv-server healthcheck` for Docker HEALTHCHECK (the image has no shell or curl).
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(healthcheck());
    }
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stdout()))
        .init();
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(serve());
}

async fn serve() {
    let cfg = config::Config::from_env();
    tracing::info!(?cfg, "starting");
    let state: Shared = Arc::new(AppState::new(cfg.clone()));

    tokio::spawn(epg::run_forever(state.clone()));

    let app = Router::new()
        .route("/epg/now.json", get(epg_now))
        .route("/epg/{file}", get(epg_channel))
        .route("/health", get(health))
        .layer(CompressionLayer::new().gzip(true))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", cfg.port))
        .await
        .expect("bind port");
    tracing::info!(port = cfg.port, "listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let ctrl_c = tokio::signal::ctrl_c();
            #[cfg(unix)]
            {
                let mut term =
                    tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                        .unwrap();
                tokio::select! { _ = ctrl_c => {}, _ = term.recv() => {} }
            }
            #[cfg(not(unix))]
            let _ = ctrl_c.await;
        })
        .await
        .expect("server");
}

fn json_response(status: StatusCode, body: Bytes) -> Response {
    let mut r = (status, body).into_response();
    let h = r.headers_mut();
    h.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    h.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=300"),
    );
    r
}

async fn epg_now(State(s): State<Shared>) -> Response {
    let t = timeutil::now();
    let minute = t / 60;
    if let Some((m, b)) = s.now_cache.lock().unwrap().as_ref() {
        if *m == minute {
            return json_response(StatusCode::OK, b.clone());
        }
    }
    let body = {
        let e = s.epg.read().unwrap();
        let channels: serde_json::Map<String, serde_json::Value> = e
            .channels
            .iter()
            .filter_map(|(k, v)| Some((k.clone(), json!(epg::now_next(v, t)?))))
            .collect();
        Bytes::from(
            serde_json::to_vec(&json!({ "updated": e.updated, "channels": channels })).unwrap(),
        )
    };
    *s.now_cache.lock().unwrap() = Some((minute, body.clone()));
    json_response(StatusCode::OK, body)
}

async fn epg_channel(State(s): State<Shared>, Path(file): Path<String>) -> Response {
    let Some(key) = file.strip_suffix(".json") else {
        return (StatusCode::NOT_FOUND, "not found\n").into_response();
    };
    let t = timeutil::now();
    let e = s.epg.read().unwrap();
    let progs = e
        .channels
        .get(key)
        .map(|v| epg::window(v, t))
        .unwrap_or_default();
    if progs.is_empty() {
        let body = serde_json::to_vec(&json!({ "id": key, "error": "no guide" })).unwrap();
        return json_response(StatusCode::NOT_FOUND, Bytes::from(body));
    }
    let body = serde_json::to_vec(&json!({ "id": key, "programmes": progs })).unwrap();
    json_response(StatusCode::OK, Bytes::from(body))
}

async fn health(State(s): State<Shared>) -> Response {
    let body = {
        let e = s.epg.read().unwrap();
        let t = timeutil::now();
        json!({
            "status": if e.channels.is_empty() { "starting" } else { "ok" },
            "now": t,
            "uptime": t - s.started_at,
            "epg": {
                "updated": e.updated,
                "completed": e.completed,
                "running": e.running,
                "channels": e.channels.len(),
                "wanted": e.wanted,
                "mapped": e.mapped,
                "sites": e.sites,
            },
        })
    };
    let mut r = json_response(
        StatusCode::OK,
        Bytes::from(serde_json::to_vec(&body).unwrap()),
    );
    r.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    r
}

/// Minimal blocking GET /health against localhost. Exit 0 when it answers 200.
fn healthcheck() -> i32 {
    use std::io::{Read, Write};
    let port = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse::<u16>().ok())
        .unwrap_or(5001);
    let run = || -> std::io::Result<bool> {
        let mut c = std::net::TcpStream::connect(("127.0.0.1", port))?;
        c.set_read_timeout(Some(std::time::Duration::from_secs(5)))?;
        c.write_all(b"GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n")?;
        let mut buf = [0u8; 16];
        let n = c.read(&mut buf)?;
        Ok(buf[..n].starts_with(b"HTTP/1.1 200") || buf[..n].starts_with(b"HTTP/1.0 200"))
    };
    match run() {
        Ok(true) => 0,
        _ => 1,
    }
}
