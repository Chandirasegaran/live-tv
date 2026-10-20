# Live TV

A small, fast Android TV app for watching free Indian and Tamil live TV channels, made for low-end TVs (built and tested for a Redmi Smart TV 32: 1 GB RAM, Android TV 11).

- **Channel grid and list:** 16:9 logo tiles or numbered rows, with categories (Favourites, Tamil, News, Movies, Kids, …).
- **Remote-first player:** channel up/down, number entry, an info bar with what's on now and next, a channel list (OK), the day's guide (→), and audio-track switching (←) on channels with several languages.
- **Favourites and search.**
- **TV guide (EPG)** for about 460 channels, from a small self-hosted server (see [`server/`](server/)).
- **Plays MP2 audio everywhere:** many satellite-sourced sports channels use MP2 (MPEG-1 Layer II) audio, which a lot of TVs can't decode. A small built-in decoder takes over when the TV has none.
- **Settings:** video quality (highest by default), aspect ratio (16:9 by default, Original or Zoom), start-up behaviour, up-button direction, info bar time, refresh channels or guide, and **Check for updates** from this repository's GitHub Releases.
- **Light:** about 680 KB. No AndroidX, Kotlin or Compose; plain Java views drawn in code, plus Media3 ExoPlayer for playback.

## Thanks to iptv-org

All channels come from the community-maintained **[iptv-org/iptv](https://github.com/iptv-org/iptv)** project. Thank you to everyone who collects and maintains those links. This app uses:

- [`languages/tam.m3u`](https://iptv-org.github.io/iptv/languages/tam.m3u) (Tamil) and [`countries/in.m3u`](https://iptv-org.github.io/iptv/countries/in.m3u) (India), listed in [PLAYLISTS.md](https://github.com/iptv-org/iptv/blob/master/PLAYLISTS.md).
- The [iptv-org API](https://github.com/iptv-org/api) `guides.json`, which maps channels to guide sources.
- The site configs in [iptv-org/epg](https://github.com/iptv-org/epg), which the guide server's Rust scrapers are ported from.

The built-in MP2 audio decoder is ported from [pl_mpeg](https://github.com/phoboslab/pl_mpeg) by Dominic Szablewski (MIT), itself based on [kjmp2](https://keyj.emphy.de/kjmp2/) by Martin J. Fiedler.

Live TV only plays links published by iptv-org. It doesn't host, store or re-stream any video. Whether a channel plays depends on its source; free streams come and go. If a channel is broken, it's best reported to [iptv-org](https://github.com/iptv-org/iptv/issues).

## Install

1. On the TV, allow installing apps from unknown sources.
2. Download `app-release.apk` from the [latest release](https://github.com/Chandirasegaran/live-tv/releases/latest) and install it (for example with a USB stick and a file manager, or `adb install`).

Later versions can be installed from inside the app: **Settings › Check for updates**.

## Remote

| Where | Key | Does |
|---|---|---|
| Home | D-pad, OK | Move, open a channel |
| Home | Menu or hold OK | Add or remove a favourite |
| Player | ▲ ▼ / CH+ CH− / digits | Change channel |
| Player | OK | Channel list |
| Player | → | Today's guide for this channel |
| Player | ← | Audio language, when the channel has more than one (else the info bar) |
| Player | Menu | Favourite on/off |
| Player | Back | Back to the grid |

## Build

Requirements: Android SDK (API 36), JDK 17 or newer (Android Studio's bundled JBR works).

```bash
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the local debug key, so keep building from the same machine (or switch to a release keystore) or in-app updates won't install over each other.

To refresh the built-in channel snapshot used on first launch and offline:

```bash
python tools/snapshot.py app/src/main/assets/channels.m3u
```

## Releasing an update

1. Bump `versionCode` and `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts).
2. Build the release APK.
3. Create a GitHub release tagged `v<versionName>` (for example `v1.1`) and attach the APK.

**Check for updates** compares the latest release's tag with the installed version and installs the attached `.apk`.

## Guide server

The TV guide comes from a small Rust service (about 15 MB of RAM) that scrapes DishTV, Airtel Xstream and Tata Play. It's published as the Docker image `chandirasegaran/livetv-epg`. See [`server/README.md`](server/README.md), and [`deploy/docker-compose.yml`](deploy/docker-compose.yml) for running it behind Caddy.

## Project layout

```
app/       Android TV app (Java, views in code)
server/    Rust guide (EPG) server + Dockerfile
deploy/    docker-compose.yml for the server host
tools/     snapshot.py: builds the offline channel list
docs/      plan and design-language reference
```
