# 07 — Feature scope

## v1 (initial release)

Core goal: download fast with segmented chunking in a foreground service.

- HTTP/HTTPS segmented engine (`03-download-engine.md`): probe-as-segment-0, immediate ramp-up,
  work stealing, lazy-segment split, end-game, parallel pre-allocation, resume, retry.
- Foreground service with `specialUse` (`04-foreground-service.md`), notifications, wake/Wi-Fi locks.
- Download queue, simultaneous limit, per-download and global connection limits, speed modes.
- Add from: URL field, clipboard, share intent (`text/plain` URLs), "Open with" for http/https links.
- Downloads list and details with segment map; Material 3 Expressive UI (`06`).
- Built-in browser on System WebView with tabs, history, bookmarks, download capture, cookies/referer pass-through.
- Ad blocker: network + cosmetic, EasyList/EasyPrivacy, per-site allowlist.
- Settings (DataStore). Light/dark/dynamic theme.

## v1.x

- HLS/DASH media download from the browser's media sniffer, with remux to MP4.
- POST-download and blob capture.
- Mirrors, multi-IP spreading, adaptive connection count.
- Scheduler (start/stop at a time), Wi-Fi-only, metered rules.
- QR scan to add a URL.
- Batch downloads (`file[1-100].jpg`), import a URL list.
- Tablet/foldable list-detail layout.

## v2

- BitTorrent and magnet links (libtorrent4j), as an optional module.
- HTTP/3 via Cronet engine option.
- GeckoView engine option with uBlock Origin.
- Remote control from desktop (FDM has a remote-access IPC; Pull would use a local web UI with a pairing code).
- Backup/restore of downloads list and settings.

## Out of scope

- Downloading from sites whose terms forbid it, DRM-protected streams. Pull will not include DRM circumvention.
- Bundling a custom Chromium build.
- Telemetry by default. If analytics is ever added, it is opt-in. (FDM ships Firebase Analytics and `AD_ID`.)
