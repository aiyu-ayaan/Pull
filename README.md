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

<p align="center"><img src="development/brand/logo/splash-preview.gif" width="240" alt="Pull splash animation: the comet's head shoots in, then its three trails stretch out behind it"></p>
<p align="center"><sub>The splash animation, recorded on an API 37 emulator. The comet is the logo: speed, with trails.</sub></p>

> Pull is in alpha. There are no benchmark numbers yet, so this README makes no speed claim
> against other download managers. The method for measuring one is in [`08-benchmarking.md`](development/docs/08-benchmarking.md).

## What it does

### HTTP and HTTPS downloads
- **Segmented engine.** The first request doubles as segment 0, then the remainder splits into parallel segments. A
  worker that finishes early takes half of the biggest remaining section.
- **Resume.** Pause, lose the connection or kill the app: Pull resumes from the saved segment table. It checks the
  ETag, `Last-Modified` and size first, and refreshes expired links.
- **HTTP/1.1 per segment.** HTTP/2 would put every segment on one TCP connection and defeat the point.
- Retry with backoff, per-download and global connection limits, a speed limit and a queue.

### Torrents and magnet links
Magnet links and `.torrent` files through libtorrent4j. The details screen has tabs for trackers, peers, pieces and
per-torrent options.

### Video sites
Links to supported video sites go through yt-dlp, with a format picker and saved format preferences.

### Browser
A built-in WebView browser in its own task that hands downloads to the engine. An ad blocker is planned but is not
built yet.

### Add a download
Paste a URL, open a link with Pull, or **share** to it. The share target is a bottom sheet over the app you shared
from. Pull warns before you download the same thing twice.

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
