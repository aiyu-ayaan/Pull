<p align="center"><img src="development/brand/logo/pull-symbol.svg" width="120" alt="Pull logo"></p>
<h1 align="center">Pull</h1>
<p align="center"><b>A download manager that splits every file into parallel segments.</b></p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <img alt="Status" src="https://img.shields.io/badge/status-work%20in%20progress-orange">
  <img alt="Telemetry" src="https://img.shields.io/badge/telemetry-none-0f766e">
</p>

Pull is an Android download manager built around one idea: a single connection rarely uses your whole line, so split
the file into segments, download them in parallel and keep re-splitting the slowest part until the file is done.
It also downloads torrents and video-site links, and has its own browser.


<p align="center"><a href="development/media/pull-release-0.1.1-alpha.4.mp4"><img src="development/media/pull-release-0.1.1-alpha.4.gif" width="270" alt="Pull 0.1.1-alpha.4 release video: splash, segmented downloads, the browser Shield, torrents and video sites"></a></p>
<p align="center"><sub>Release video for 0.1.1-alpha.4 (25 s, 1440x2560). <a href="development/media/pull-release-0.1.1-alpha.4.mp4">Watch the full MP4 with sound</a>.</sub></p>

> Pull is in alpha. There are no benchmark numbers yet, so this README makes no speed claim
> against other download managers. The method for measuring one is in [`08-benchmarking.md`](development/docs/08-benchmarking.md).

## What it does

### Segmented HTTP & HTTPS engine (`:engine`)
- **Probe-as-segment-0 & dynamic work stealing.** The first request doubles as segment 0, then the remainder splits into parallel segments. When a worker finishes early, it dynamically steals half of the largest remaining segment.
- **Lazy-segment & end-game tail racing.** Detects stalled or slow workers so faster segments can take over, and issues duplicate end-game tail requests on the final blocks so a single straggling socket never holds up completion.
- **HTTP/1.1 per segment & adaptive connections.** Uses dedicated HTTP/1.1 clients (HTTP/2 would multiplex every segment over a single TCP connection) with configurable or **Auto** adaptive connection counts that scale with bandwidth saturation.
- **Resilience & multi-source failover.** Recovers from transient `403`, `429`, and `503` server refusals with backoff, spreads segment connections across multiple resolved A/AAAA records via `RoundRobinDns`, and automatically fails over to backup mirror URLs.
- **Safe resume & integrity verification.** Resumes cleanly from the saved segment table after pausing, losing connectivity, or restarting the app—checking `ETag`, `Last-Modified`, and file size first, refreshing expired links, and verifying completed files against **MD5** or **SHA-256** checksums.
- **Queue & traffic control.** Per-download and global connection limits, live token-bucket speed limiting, and automatic queue advancement.

### BitTorrent & magnet engine
Full torrent and magnet support powered by `libtorrent4j`, with a rich details screen covering files, trackers, peers, pieces, and options:
- **Sequential & preview modes.** Toggle sequential piece downloading or prioritize the **first and last pieces** (`first_and_last`) so media files can be previewed immediately while downloading.
- **Per-file priorities.** Inspect the multi-file tree and set individual files to **Skip**, **Low**, **Normal**, or **High** priority before or during a download.
- **Seeding & ratio enforcement.** Continue seeding after completion with a configurable share-ratio limit that automatically pauses or completes the torrent once reached.
- **Tracker & peer management.** Manage default and per-torrent custom trackers (add, remove, force reannounce), inspect live peer flags and progress, and ban misbehaving peer IPs via `ip_filter`.
- **Create & export torrents.** Build new `.torrent` files from local files or folders with `TorrentCreator` (custom piece size, trackers, comment, private flag) and export existing torrents as `.torrent` files or `magnet:` URIs.

### Video sites
Links to supported video sites go through `yt-dlp` (with bundled FFmpeg and `aria2c`), complete with a format picker and saved format preferences.

### Built-in browser & Ad Shield
A multi-tab WebView browser running in its own task with built-in media sniffing, download interception, and a three-layer content shield:
- **Tabs, incognito & start page.** Independent tabs with separate navigation history, a tab switcher grid, incognito mode (isolated storage and no history recording), a customizable start page with quick-launch shortcuts, and Room-backed **History** and **Bookmarks**.
- **Search & desktop mode.** Configurable search engine selector with live address-bar autocomplete suggestions, plus a per-tab **Desktop site** user-agent toggle.
- **Smart download capture.** Long-press any link or image to **Download with Pull**, automatically intercept file links by extension, capture `blob:` and `POST` form downloads via an injected JS bridge, and detect streaming or embedded media (`m3u8`, `mpd`, `mp4`, `webm`, `mkv`, `mp3`, `m4a`) via the live **"N media" Sniffer** chip.
- **Ad, popup & cosmetic Shield.** Intercepts ad and tracker requests in `shouldInterceptRequest`, blocks unprompted `window.open` popups and spam redirects, and injects CSS cosmetic rules to collapse empty ad slots and click-jack overlays—all controllable via the per-page **Shield panel** with blocked counters and a **per-site allowlist**.

### Batching, scheduling & backup
- **Batch downloads.** Expand numbered URL patterns like `https://example.com/file_[001-100].zip` with live preview or import `.txt` URL lists in one step.
- **Scheduled download window.** Restrict automatic downloading to an off-peak time window (configurable **Start hour** and **End hour**), plus Wi-Fi-only, charging-only, and battery-guard constraints.
- **JSON backup & restore.** Export and import app settings, browser bookmarks, and start-page shortcuts as portable JSON files.

### Add a download
Paste a URL, expand a batch pattern, open a link with Pull, or **share** to it from any app via the modal bottom sheet. Pull checks for duplicates before queuing the same file twice.

### Foreground service
Downloads run in a foreground service with `specialUse` on Android 14 and up, because Android 15 limits
`dataSync` services to six hours. Notifications show progress and have a pause-all action.

### Look and feel
Material 3 Expressive with dynamic color, light and dark themes, and shape-morphing components. The design follows the
sibling apps [Rewire](https://github.com/aiyu-ayaan/Rewire), Findex and Domus.

### Privacy
No accounts and no telemetry. The browser never continues past an SSL error.

## Project layout

```
app/        Compose UI, Hilt, Room, the foreground service, the browser, torrents and video
engine/     The segmented HTTP engine: pure Kotlin and OkHttp, with no Android UI code, so it is tested on the JVM
development/
├── docs/   Design docs (start with docs/README.md)
├── brand/  Logo, geometry source and the splash recording
└── TODO.md Phased task list
```

Design docs: [overview](development/docs/README.md) ·
[architecture](development/docs/02-architecture.md) ·
[download engine](development/docs/03-download-engine.md) ·
[foreground service](development/docs/04-foreground-service.md) ·
[browser](development/docs/05-browser-and-adblock.md) ·
[UI](development/docs/06-ui-material3-expressive.md) ·
[scope](development/docs/07-features-and-scope.md) ·
[benchmarking](development/docs/08-benchmarking.md)

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.4 |
| UI | Jetpack Compose, Material 3 Expressive, dynamic color |
| DI, async | Hilt, Coroutines and Flow |
| Persistence | Room and DataStore |
| HTTP | OkHttp, with HTTP/1.1 clients for segments |
| Torrents | libtorrent4j |
| Video | yt-dlp, FFmpeg and aria2c through youtubedl-android |

SDK: `minSdk 26`, `targetSdk 36`, `compileSdk 37`. Versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Install

Download the APK for your phone from [Releases](https://github.com/aiyu-ayaan/Pull/releases). Each release has one APK
per CPU type, so you download only the native code your phone runs:

| APK | For |
| --- | --- |
| `Pull-<version>-arm64-v8a.apk` | Almost every phone made since 2017 |
| `Pull-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `Pull-<version>-x86_64.apk`, `Pull-<version>-x86.apk` | Emulators and Chromebooks |

After that, Pull updates itself. **Settings → Updates** checks GitHub, downloads the APK for your CPU and hands it to
Android's installer. You can pick the Stable, Beta or Alpha channel there. The check reads only the public release
list and sends no data about you.

### Releases

A commit on `master` whose subject starts with `!alpha`, `!beta`, `!stable`, `!fix`, `!feat` or `!major` opens a
release PR. The PR bumps `VERSION` and writes `CHANGELOG.md`. Merging it builds the signed APKs and publishes the
GitHub Release. The rules are in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Build and run

You need Android Studio (current stable) or JDK 17+ with the Android SDK, and a device or emulator on API 26+.

```bash
# Build and install the debug build
./gradlew :app:installDebug

# Engine tests on the JVM
./gradlew :engine:test
```

The debug build installs as `com.aiyu.pull.debug`.

## The logo

The mark is a comet: a ball diving down and to the right with three trails behind it. The geometry lives in
[`comet.py`](development/brand/logo/comet.py), and [`development/brand/logo/README.md`](development/brand/logo/README.md)
has the colours, the splash timings and the usage rules.

## License

Pull is released under the [GNU General Public License v3.0](LICENSE).

It has to be GPL-3.0: the video downloader uses [youtubedl-android](https://github.com/JunkFood02/youtubedl-android),
which is GPL-3.0 and bundles FFmpeg and aria2c, so the combined app must be GPL-3.0 too. Source for any build you
distribute must be available under the same license.

| Component | License |
|---|---|
| yt-dlp | Unlicense |
| youtubedl-android, FFmpeg build, aria2c | GPL-3.0 (aria2 itself is GPL-2.0-or-later) |
| libtorrent4j and libtorrent | MIT and BSD-3-Clause |
| OkHttp, Room, Hilt, Compose, Kotlin | Apache-2.0 |

Pull reuses architecture ideas from other download managers and no code or assets from Free Download Manager.
The table is a summary, so check each project for its exact terms before you release.
