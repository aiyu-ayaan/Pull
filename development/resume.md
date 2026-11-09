# Resume prompt

Paste the block below into a new session to continue Pull from commit `8f2ed47`.

```
Continue Pull (Android download manager) in /media/aiyu/550E85595197DBEC/development/Pull. Read CLAUDE.md, development/resume.md and `git log --oneline` (last work commit 8f2ed47 "torrent and yt-dlp downloads (WIP)"). Use caveman style, commit at stages, never add yourself as co-author, test everything on the running emulator (emulator-5554, foldable, display id 4619827259835644672 for screencap).

Context:
- Stack: Kotlin 2.4.10, AGP 9.4.0, Compose M3 Expressive 1.5.0-alpha26, Hilt, Room v2 (auto-migration 1→2), libtorrent4j 2.1.0-39, youtubedl-android 0.18.1 (GPL-3.0 → Pull is GPL-3.0).
- Repo disk is full: build output goes to /media/aiyu/U_U/LinuxFiles/pull-build via `pull.buildRoot` in local.properties (git-ignored). APK: /media/aiyu/U_U/LinuxFiles/pull-build/app/outputs/apk/debug/app-debug.apk.
- Test server: `cd build/qa/srv && python3 -I server.py 8765` (emulator reaches it at http://10.0.2.2:8765). Routes: /file/<name>?size=&rate= (Range + ETag + per-connection throttle), /go/<name> (302 to an expiring signed link), /expire, /page (test HTML page), /t/test.torrent (web-seed torrent, 32 MB tor.bin via /ws/tor.bin). Check hashes with `python3 -I build/qa/srv/expected.py <size> build/qa/srv`. Screenshots: `build/qa/shot.sh <name>` → build/qa/<name>.png (coordinates ×2.5 for taps). build/qa is git-ignored and may need recreating.
- Architecture: engine/ = pure-JVM segmented HTTP engine (DownloadTask). app/download/DownloadManager runs Transfer implementations: HttpTransfer, TorrentTransfer (app/torrent/TorrentEngine.kt), VideoTransfer (app/video/VideoEngine.kt). The smart add popup is ui/add/AddDownloadSheet.kt + AddViewModel (Analysis states: file / torrent / video / web page). Torrent file picker: ui/add/TorrentFilesSheet.kt. Nav: Active (live home) / Downloaded tabs in a Rewire-style pill; a Findex PushDrawer holds actions only; the browser is BrowserActivity in its own task.

Next steps, in order:
1. Verify the torrent download completes: hash of /sdcard/Download/Pull/tor.bin vs expected 33554432. Then remove the debug `PullTorrent` Log line in TorrentEngine.kt.
2. DetailsScreen (ui/details/DetailsScreen.kt) for Kind.TORRENT: state text (metadata / checking / downloading N peers); stats tiles (down rate, ETA, peers/seeds, upload rate, uploaded, time); a piece map from LiveStats.torrent.pieces; a Files card with a "Select files" button that opens TorrentFilesSheet with live per-file progress and calls vm.setTorrentFiles(id, set, count) (when not running, use vm.torrentFiles(e)); info rows: magnet copy/share, info hash. Hide the HTTP connections/resume tiles for torrent and video. For Kind.VIDEO: format label, page URL, rate/ETA tiles.
3. Seal-like yt-dlp Update screen (Settings → yt-dlp): current version (VideoEngine.version()), channel Stable/Nightly/Master (settings.ytdlpChannel), Update now with progress (VideoEngine.update), auto-update toggle (check at most every 24 h on app start using ytdlpLastCheck).
4. Custom command templates screen (Settings → Command templates): list/add/edit/delete CommandTemplate stored via SettingsRepository.templates/saveTemplates; already picked in the add popup.
5. Torrent settings section: sequential by default (torrentSequential), DHT/LSD/uTP toggles, upload limit; apply them to the SettingsPack in TorrentEngine.ensureStarted.
6. Test video on the emulator: add a YouTube link → quality chips (Best default) → download → file in Download/Pull. Test a magnet (a public legal torrent).
7. Release ABI splits (the APK is ~270 MB with all ABIs). Add a GPL-3.0 LICENSE. Write development/docs/09-ttorrent-teardown.md (observed tTorrent v2.0.0 features only: add/create torrent, magnet, file selection, sequential, trackers edit, DHT/LSD/PeX/uTP/UPnP/NAT-PMP, encryption, port, proxy, IP filter, speed/seeding ratio+time limits, battery/charging/Wi-Fi rules, scheduling, RSS feeds, web UI, uses RUN_USER_INITIATED_JOBS; no code/assets copied, do not record its embedded API key). Update development/TODO.md checkboxes and CLAUDE.md (new modules, GPL, build dir note). Commit each stage.
```

## State at handoff (2026-11-09)

Tested on the emulator and working:

- Segmented HTTP engine: 9/9 JVM tests pass, file hashes match.
- Resume after a crash or kill.
- Expired-link refresh: automatic re-resolve, plus "Paste new link".
- Custom wavy notification.
- Rewire-style pill and Findex-style drawer.
- Browser runs in its own task.
- 16 KB page-size check passes.

In progress:

- Torrent downloads run from the web seed; the final file hash is not checked yet.
- Video downloads (yt-dlp) are wired but have never been run.
- The details screen is not torrent- or video-aware yet.

## Task list at handoff

- [x] **Torrent: engine, DB migration, task abstraction.** Covers the libtorrent4j session, Room v2 (`kind`, torrent and video columns), the `Transfer` abstraction in `DownloadManager`, web seeds read from `url-list`, and the web-seed test on the emulator.
- [ ] **Torrent: add popup, intents, details, settings.**
  - Done: magnet and `.torrent` VIEW intents, drawer "Open torrent file", torrent mode in the add popup, `TorrentFilesSheet` (folder tree, type chips, select all), live reselection plumbing (`setTorrentFiles`).
  - Left: torrent details UI (next step 2), torrent settings (next step 5), magnet test.
- [ ] **Video: yt-dlp via youtubedl-android.**
  - Done: `VideoEngine` / `VideoTransfer`, video mode in the add popup (Best quality default, per-resolution, audio only, custom command chips), web page → "Find videos".
  - Left: Seal-like yt-dlp Update page (next step 3), Command templates screen (next step 4), video details, emulator test with a real video (next step 6), GPL-3.0 LICENSE.
- [ ] **Docs: tTorrent teardown, TODO, CLAUDE.md update, commits.** `development/docs/09-ttorrent-teardown.md`, TODO.md checkboxes, CLAUDE.md notes (modules, GPL, `pull.buildRoot`), release ABI splits (next step 7).
