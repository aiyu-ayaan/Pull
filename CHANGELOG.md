# Changelog

## [0.1.1-alpha.5](https://github.com/aiyu-ayaan/Pull/compare/v0.1.1-alpha.4...v0.1.1-alpha.5) (2026-11-10)

### Bug fixes

* Split servers that answer 200 to bytes=0-, never restart on a dropped link
* System bar icons follow the in-app theme
* Stop reading WebView.url off the main thread

### Other changes

* Release browser crash, resume and engine split fixes

## [0.1.1-alpha.4](https://github.com/aiyu-ayaan/Pull/compare/v0.1.1-alpha.3...v0.1.1-alpha.4) (2026-11-09)

### Features

* Expand blocklist to 260k+ domains with heuristics and cosmetic CSS
* Add ad blocker, popup blocker, tabs, and documentation
* Start on blank home page and auto-select address text on focus
* Add app filters, animated multi-selection, and file deletion dialog
* Auto-update yt-dlp and preview thumbnails in cards
* Add initial documentation for Pull download manager

### Bug fixes

* Remove DuckDuckGo search default and update test

### Other changes

* Update TODO list with completed features across phases

## [0.1.1-alpha.3](https://github.com/aiyu-ayaan/Pull/compare/v0.1.1-alpha.2...v0.1.1-alpha.3) (2026-11-09)

### Other changes

* Fix yt-dlp and torrents in release builds (R8 keep rules)

## [0.1.1-alpha.2](https://github.com/aiyu-ayaan/Pull/compare/v0.1.1-alpha.1...v0.1.1-alpha.2) (2026-11-09)

### Other changes

* Fix release crash at launch from R8 renaming nav route enum

## [0.1.1-alpha.1](https://github.com/aiyu-ayaan/Pull/compare/v0.1.0...v0.1.1-alpha.1) (2026-11-09)

### Features

* Logo sources and splash timing
* Share target is a bottom sheet over the calling app
* Implement download task with segmented downloading and resume support
* Warn before downloading the same thing twice
* Seal-style download sheet and format preferences
* Quick download share target
* Category pages and torrent settings
* Tabbed details with trackers, peers, pieces and per-torrent options
* Torrent and video aware details screen
* Torrent and yt-dlp downloads (WIP), 16 KB aligned natives
* Rewire tab pill, Findex push drawer, browser as its own task, wavy notification
* Download manager, foreground service and M3 Expressive UI
* Segmented download engine with resume and link refresh

### Other changes

* Self-update, About page, per-ABI signed release pipeline
* Add README
* Add GPL-3.0 license
* Drop debug status log
* Task list in resume prompt
* Resume prompt for the next session
* FDM teardown, Pull design docs, TODO and CLAUDE.md

# Changelog
