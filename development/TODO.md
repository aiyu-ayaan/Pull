# Pull — TODO

Design docs: [docs/README.md](docs/README.md). Phases follow `docs/07-features-and-scope.md`.

## Phase 0 — Project setup

- [ ] Create Gradle project `com.aiyu.pull` with the same version catalog as `../Findex`
      (AGP 9.4.0, Kotlin 2.4.10, Compose BOM 2026.08.00, material3 1.5.0-alpha26, Hilt 2.60.1, Room 2.8.4, Navigation 2.9.6)
- [ ] Modules: `:app`, `:engine` (pure Kotlin + OkHttp), `:baselineprofile`
- [ ] Copy UI foundations from reference apps (see `docs/06` table): `Theme.kt`, `Shape.kt`, `Motion.kt`, `Dimens.kt`,
      `Adaptive.kt`, `ListDetail.kt`, `PredictiveBack.kt`, `Haptics.kt`, `UndoSnackbarHost.kt`; rename to Pull
- [ ] Pick Pull seed color; generate light/dark schemes
- [ ] `AGENTS.md` / `CLAUDE.md`, `CHANGELOG.md`, `run.sh` like sibling apps
- [ ] CI: build, unit tests, lint

## Phase 1 — Engine core (`:engine`) — top priority

- [ ] Models: `DownloadRequest`, `SegmentState`, `DownloadState`, `Destination`
- [ ] OkHttp clients: probe client (HTTP/2 allowed), segment client (`protocols = [HTTP_1_1]`), shared dispatcher limits
- [ ] Probe: `GET Range: bytes=0-`; parse 206/200, `Content-Range`, `Content-Length`, `ETag`, `Last-Modified`,
      `Content-Disposition`, final URL; keep the stream as segment 0
- [ ] Range check fallback when the server returns 200 with a length
- [ ] Immediate ramp-up: split remainder into N and start all at once
- [ ] Segment worker: Okio read 256 KiB, trim to `end`, `FileChannel.write(buf, pos)`, update `AtomicLong current`
- [ ] Work stealing: split biggest remaining at midpoint (64 KiB aligned), CAS on `end`
- [ ] Min split size check (1 MiB default)
- [ ] Parallel `Os.posix_fallocate`; ENOSPC pauses with error
- [ ] Retry with exponential backoff + jitter per segment
- [ ] Pause returns segment table; resume from table with ETag/Last-Modified/size check
- [ ] Server ignores Range: single connection, no resume
- [ ] Global + per-host connection limits (`ConnectionSlots`)
- [ ] Token-bucket speed limit (per download + global)
- [ ] Speed/ETA: EWMA, sampled `StateFlow` at 4 Hz
- [ ] JVM tests with MockWebServer: correctness hash, random pause/resume, 32-worker split race, server without ranges,
      ETag change on resume

## Phase 2 — Persistence and service

- [ ] Room: `DownloadEntity`, `SegmentEntity`, DAOs, migrations folder
- [ ] Batch progress flush every ~1 s in one transaction; flush on pause/error/finish
- [ ] Merge finished segments
- [ ] `DownloadManager`: queue, `maxDownloads`, auto-start next
- [ ] `DownloadService`: `specialUse` FGS on API 34+, `dataSync` on API 29–33, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
- [ ] `startForeground` within 5 s; stop when idle
- [ ] `onTimeout` handling for the `dataSync` path (API 35+)
- [ ] Partial wake lock + Wi-Fi lock only while bytes move
- [ ] Notifications: summary with speed, Pause all/Resume all actions, completed/failed channel; 1 Hz max
- [ ] `POST_NOTIFICATIONS` request at first download
- [ ] Network callback: pause on loss, resume on return; Wi-Fi-only and metered rules
- [ ] Process-death recovery: RUNNING → PAUSED on start, optional auto-resume
- [ ] Boot receiver (opt-in "resume after reboot")
- [ ] Battery optimization exemption prompt for long downloads (not on first launch)
- [ ] Destinations: MediaStore Downloads with `IS_PENDING`, app folder, SAF tree

## Phase 3 — Downloads UI (M3 Expressive)

- [ ] `PullTheme` with `MaterialExpressiveTheme` + `MotionScheme.expressive()`
- [ ] `NavigationSuiteScaffold`: Downloads, Browser, Settings
- [ ] Downloads list: `LargeFlexibleTopAppBar` with global speed, filter chips, file-type `ShapeIcon`,
      `LinearWavyProgressIndicator`, morphing pause/resume toggle, swipe delete + undo, multi-select
- [ ] FAB menu: Add URL, Paste, Scan QR (later), Browser
- [ ] Add-download bottom sheet: URL, name, destination, connections slider, split button Start / Queue; live probe info
- [ ] Details: speed graph (60 s), segment map, connections list, `ButtonGroup` actions, info
- [ ] List-detail on expanded width; predictive back
- [ ] Share-intent and `http/https` "Open with" entry
- [ ] Settings: connections, simultaneous, min split, speed modes (Snail/Low/Medium/High/Unlimited), destination,
      network rules, theme, dynamic color
- [ ] Baseline profile for start and list scroll
- [ ] Accessibility pass: 48 dp targets, content descriptions, progress semantics, TalkBack speed text

## Phase 4 — Browser

- [ ] WebView screen with `androidx.webkit`; Safe Browsing on; algorithmic darkening in dark theme
- [x] Tabs (+ incognito), tab switcher grid
- [ ] Address bar + search engine setting + suggestions
- [ ] History and bookmarks (Room)
- [ ] Desktop mode toggle
- [ ] `DownloadListener` → add-download sheet with cookies (`CookieManager`), UA, Referer
- [ ] Intercept-by-extension setting in `shouldOverrideUrlLoading`
- [ ] Long-press link/image → "Download with Pull"
- [x] Bottom toolbar: back, forward, reload, home, tabs count, shield badge

## Phase 5 — Ad blocker & popup blocker

- [x] Decide matcher: Kotlin matcher with domain hash set + hierarchical suffix traversal (< 2 µs latency)
- [x] Built-in snapshot for offline first run (3,680+ domains in `assets/adblock_domains.txt`)
- [ ] Filter list periodic update (WorkManager, unmetered, every 4 days)
- [x] Network blocking in `shouldInterceptRequest` (empty typed responses and 1×1 transparent PNGs)
- [x] Cosmetic CSS injection to hide ad frames and transparent click-jack overlays
- [x] Popup & redirect blocker: stops unprompted `window.open`, cancels spam redirects, throttles rapid bursts
- [x] Shield badge with per-tab count + modal bottom sheet toggles for ad blocker, popup blocker, and cosmetic filters

## Phase 6 — Benchmark vs FDM (gate for "faster" claim)

- [ ] Lab server: nginx + `tc netem` profiles (20 ms; 80 ms + 0.5% loss; 200 ms + 1% loss; `limit_rate 2m`)
- [ ] Test files 100 MB / 1 GB / 4 GB with SHA-256
- [ ] Debug segment timeline logging in Pull
- [ ] Run Pull vs FDM 6.35.2.7075, 5 runs each, alternating; median + p90
- [ ] Record total time, time to full speed, tail time, CPU, battery
- [ ] Tune defaults (connections, min split, buffer size) from results
- [ ] Publish results table in `docs/08-benchmarking.md`

## Phase 7 — v1.x

- [ ] End-game duplicate tail requests
- [ ] Lazy-segment detection (25% of median for 6 s)
- [ ] Adaptive connection count ("Auto")
- [ ] Multi-IP DNS spreading
- [ ] Mirrors
- [ ] POST-download capture (document-start script + `WebMessageListener`)
- [ ] `blob:` capture
- [ ] Media sniffer: m3u8/mpd/video detection, "N media found" chip
- [ ] HLS download with parallel segments + remux to MP4
- [ ] Scheduler, QR scan, batch URL patterns, import URL list
- [ ] `Notification.ProgressStyle` on API 36+
- [ ] Evaluate User-Initiated Data Transfer jobs vs `specialUse` FGS

## Phase 8 — v2

- [ ] BitTorrent/magnet (libtorrent4j) as optional module
- [ ] Cronet engine option (HTTP/3)
- [ ] GeckoView engine option with uBlock Origin
- [ ] Remote control via local web UI + pairing code
- [ ] Backup/restore

## Open questions

- [ ] Play Store or sideload/GitHub only? (`specialUse` declaration review risk on Play)
- [ ] Ad blocker matcher: accept a Rust/JNI build step?
- [x] Decompile FDM Java layer with JADX (done; findings in `docs/01-fdm-teardown.md` §7)
- [ ] Optional: disassemble `libdownloadswww` (Ghidra) to read the real split/lazy-section thresholds
- [ ] FDM default values (connections, min section size) are unknown; measure in FDM settings UI on device
