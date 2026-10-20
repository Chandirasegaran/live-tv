# Live TV — Build Plan

App id `com.segar.livetv`. Two parts: an Android TV app and a small Rust server.

## Target device
Redmi Smart TV 32 (2021): 1 GB RAM, quad-core A35 / Mali G31, 1366×768, Android TV 11 (API 30), PatchWall.
Every decision below favours low RAM and fast focus movement on this hardware.

## Part A — Android TV app (`app/`)

**Rules:** follow [design-language.md](design-language.md) (TV Detox look and footprint). Plain Java, no `res/`, no AndroidX, no Kotlin.
The one exception is Media3 ExoPlayer (core and HLS only), with R8 shrinking turned on. Target APK size is about 1 MB.

| File | Job |
|---|---|
| `MainActivity` | Header (date and time, Search, Grid/List switch); category pills (Favourites, All, News, …); channel grid (16:9, 6 per row) or list (number, logo, name, now playing); recycles views with platform `GridView`/`ListView` |
| `PlayerActivity` | Fullscreen ExoPlayer; info bar on tune (number, name, now/next, time; hides after about 4 s); CH+/CH− and D-pad up/down; OK opens a 640-wide channel panel; Menu toggles favourite; Back goes home |
| `Ui` | Design-doc starter kit: `px()`, text helper, glass tile, focus animation, warm background |
| `LineIcon` | Icons drawn from int arrays (search, grid, list, star, play, …) |
| `M3u` / `Channel` | Streaming M3U parser (`tvg-id`, logo, group, `EXTVLCOPT` referrer and user agent) |
| `Repo` | Playlist straight from iptv-org (`languages/tam.m3u` first, then `countries/in.m3u`, merged and de-duplicated in the app; Tamil channels get a "Tamil" category), cached on the TV, with a built-in snapshot for first launch and offline |
| `Epg` | Fetches compact now/next JSON from the server; per-channel schedule on demand |
| `ImageLoader` | `HttpURLConnection`, logos shrunk to tile size, `LruCache`, 3 worker threads |
| `Prefs` | Favourites, last channel (resumed on launch), view mode |
| `Strings` | English labels (Java string table) |

Manifest: `LEANBACK_LAUNCHER`, `INTERNET`, `usesCleartextTraffic` (94 streams use plain http), landscape.

## Part B — Rust server (`server/`)

axum, tokio, reqwest (rustls) and serde. Multi-stage Dockerfile producing a static musl binary on a minimal base image (about 15 MB). Data kept in memory plus a JSON snapshot on a volume.

| Endpoint | Returns |
|---|---|
| `GET /epg/now.json` | `{ tvgId: [[start, end, title], [next…]] }` for all channels (small, gzip) |
| `GET /epg/{tvgId}.json` | Today and tomorrow schedule for one channel |
| `GET /health` | Status, last refresh times, counts |

- **EPG only.** Playlists come straight from iptv-org (see [PLAYLISTS.md](https://github.com/iptv-org/iptv/blob/master/PLAYLISTS.md)); the server reads `tam.m3u` and `in.m3u` only to learn which channels to scrape.
- **Guide job (daily):** pure-Rust scrapers for **Tata Play, DishTV and Airtel Xstream**, ported from the iptv-org/epg site configs. Channels are mapped to each site using iptv-org `guides.json`. That gives about 498 of 761 channels a guide.
- Config through environment variables (port, intervals, data directory).
- **Host:** Oracle Cloud, Hyderabad (so Indian geo-blocked streams check correctly). Public URL `https://tvepg.chandirasegaran.dpdns.org` (TLS handled by the user's reverse proxy; the container serves plain HTTP). Oracle may be ARM (Ampere), so the image must build for both `linux/amd64` and `linux/arm64`.

### Contract (app ↔ server)
Base URL: `https://tvepg.chandirasegaran.dpdns.org`. Times are Unix seconds (UTC). Channel key = `tvg-id` with any `@feed` suffix removed (e.g. `SunTV.in@SD` → `SunTV.in`).

- `GET /epg/now.json`
  ```json
  {"updated": 1760000000, "channels": {"SunTV.in": [[1760000000, 1760003600, "Now title"], [1760003600, 1760007200, "Next title"]]}}
  ```
  Each channel lists the current programme and up to 1 next; channels with no data are left out.
- `GET /epg/{key}.json` (key URL-encoded)
  ```json
  {"id": "SunTV.in", "programmes": [[start, end, "Title", "Description or empty"]]}
  ```
  Covers now through tomorrow end of day. Returns 404 if there's no guide.
- All JSON is gzip-compressed when the client sends `Accept-Encoding: gzip`, with `Cache-Control: max-age=300`.

## Build order
1. Rust server: EPG scrapers, endpoints, Dockerfile.
2. App: parser, repo, grid and list screens, logo loading.
3. App: player, channel up/down, info bar.
4. App: favourites, search.
5. App: now/next display and schedule panel from the server.
6. Test on a TV emulator first (720p, API 30, low RAM); the Redmi TV over ADB Wi-Fi comes later. Check APK size every build.

Parts A and B only meet at the server address and the JSON format above, so they can be built in parallel.
