# Live TV guide server

A small Rust service that gives the Live TV Android TV app its programme guide (EPG), scraped from DishTV, Airtel Xstream and Tata Play. The app gets its channel playlists straight from iptv-org; this server only provides the guide.

Public URL: `https://tvepg.chandirasegaran.dpdns.org`. TLS is handled by Caddy; the container speaks plain HTTP on port 5001. It uses about 15 MB of RAM.

## Endpoints

| Path | Returns |
|---|---|
| `GET /epg/now.json` | `{"updated": 1760000000, "channels": {"SunTV.in": [[start, end, "Now"], [start, end, "Next"]]}}`. Only channels with a programme on air right now are listed. |
| `GET /epg/{key}.json` | `{"id": "SunTV.in", "programmes": [[start, end, "Title", "Description"]]}` from now until the end of tomorrow (IST). Returns 404 when there's no guide. `key` is the tvg-id without `@feed`, URL-encoded. |
| `GET /health` | Status, refresh times, counts, and how each guide site did in the last run. |

Times are Unix seconds (UTC). JSON is gzip-compressed for clients that send `Accept-Encoding: gzip`, and every response carries `Cache-Control: max-age=300`.

## How it works

Runs once at start-up if the saved guide is stale, then every `EPG_INTERVAL_H`.

1. Reads iptv-org's `languages/tam.m3u` and `countries/in.m3u` (the playlists the app plays) to learn which channels need a guide.
2. Maps those channels to each site's ids with iptv-org's `guides.json` (about 474 of 761 channels have a guide somewhere).
3. Scrapes today and tomorrow (IST):
   - **DishTV and Airtel** run first, in parallel. Each covers about 350–370 channels in about 2 minutes.
   - **Tata Play** then fills only the channels still missing. Its API allows 30 requests a minute per IP, so it's used as a gap filler rather than for everything.
4. The guide is published after each site finishes, so it's usable within about 2 minutes of start-up. A full run takes about 6 minutes (measured: 464 channels).
5. Channels a run misses keep the previous run's data while it still covers the future.

Each site is its own `Scraper` implementation, so a broken site only loses its own channels. Every request has a timeout; a site is skipped for the run after 25 failures in a row, with a 60 s pause after every 5.

The guide (`epg.json`), the site mapping (`guides_map.json`) and the channel list (`channels.json`) are saved in `DATA_DIR`, so a restart serves straight away.

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `5001` | HTTP port |
| `DATA_DIR` | `./data` (`/data` in Docker) | Snapshot directory |
| `EPG_INTERVAL_H` | `24` | Hours between guide scrapes |
| `PLAYLIST_SOURCES` | tam.m3u,in.m3u | Comma-separated playlists whose channels get a guide |
| `EPG_SITES` | all | Limit scraping, e.g. `dishtv.in,airtelxstream.in` |
| `RUST_LOG` | `info` | Log level (`debug` shows each failed guide request) |

## Run locally

```bash
cargo test
```

```bash
cargo run --release
```

Then try it:

```bash
curl -s localhost:5001/health
```

```bash
curl -s --compressed localhost:5001/epg/now.json | head -c 400
```

## Publish and deploy

Build for both architectures and push (arm64 is emulated with QEMU, so it's slower):

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t chandirasegaran/livetv-epg:latest --push .
```

On the server, use `deploy/docker-compose.yml` from the project root (it pulls that image and binds `127.0.0.1:5001`):

```bash
docker compose pull && docker compose up -d
```

Caddy:

```
tvepg.chandirasegaran.dpdns.org {
    reverse_proxy localhost:5001
}
```

The `docker-compose.yml` in this folder builds from source instead, for local testing.

The image is `FROM scratch` with a single static musl binary, running as user `nobody` (65534). TLS roots are compiled in (webpki-roots), so no CA bundle is needed. `HEALTHCHECK` uses the binary itself (`/livetv-server healthcheck`).

## Troubleshooting

- `curl localhost:5001/health` shows per-site results (`tried`, `ok`, `empty`, `errors`, `fatal`).
- **Tata Play** sits behind Akamai, which needs browser-style `sec-*` headers or it returns 403. Its gateway allows 30 requests per minute per IP and returns 429 beyond that; the scraper sends 1 request every 2.2 s and, on a 429, waits for `RateLimit-Reset`. Anything else on the same IP (another grabber) shares that quota.
- **DishTV** returns IST wall-clock times labelled `Z`. This matches the upstream iptv-org config, and the parser treats them as IST.
- To force a fresh guide scrape, stop the container, delete `epg.json` from the volume, and start it again.

## Layout

```
src/main.rs          routes, startup, healthcheck subcommand
src/config.rs        env config
src/m3u.rs           M3U parser (channel keys for the guide job)
src/state.rs         shared state and JSON snapshots
src/timeutil.rs      IST dates, ISO parsing (no tz database needed)
src/epg/mod.rs       Scraper trait, guide job (quick sites, then gap fillers), now/next
src/epg/guides.rs    guides.json → site id mapping (streamed filter)
src/epg/dishtv.rs    DishTV scraper (+ tests)
src/epg/airtel.rs    Airtel Xstream scraper (+ tests)
src/epg/tataplay.rs  Tata Play scraper, gap filler (+ tests)
tests/fixtures/      saved sample responses (iptv-org fixtures + live samples, 2026-10-20)
```
