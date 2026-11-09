# Pull — design docs

Pull is an Android download manager built to finish downloads faster than Free Download Manager (FDM),
with a built-in Chromium-based browser and ad blocker, using Material 3 Expressive.

| Doc | Content |
|---|---|
| [01-fdm-teardown.md](01-fdm-teardown.md) | What FDM 6.35 Android contains and how its engine works (evidence with confidence labels) |
| [02-architecture.md](02-architecture.md) | Stack, modules, data flow, Room schema, threading, errors |
| [03-download-engine.md](03-download-engine.md) | Segmented download algorithm and why it should beat FDM |
| [04-foreground-service.md](04-foreground-service.md) | `specialUse` FGS, Android 15 6-hour `dataSync` limit, locks, notifications |
| [05-browser-and-adblock.md](05-browser-and-adblock.md) | WebView browser, download capture, ad block engine |
| [06-ui-material3-expressive.md](06-ui-material3-expressive.md) | Screens, components, motion, adaptive layout |
| [07-features-and-scope.md](07-features-and-scope.md) | v1 / v1.x / v2 scope |
| [08-benchmarking.md](08-benchmarking.md) | How "faster than FDM" is measured |

Task list: [../TODO.md](../TODO.md)

## Summary of the FDM findings

- FDM Android is a Qt 6/QML app. Its engine is native C++: libcurl for HTTP, libtorrent for torrents,
  FFmpeg for media. The Java layer is a thin shell.
- HTTP downloads use dynamic segmentation: split the biggest remaining section in half, up to a
  connection limit, with lazy-section detection and pre-allocation.
- It uses a `specialUse` foreground service because Android 15 caps `dataSync` at 6 hours.
- The browser is the Android System WebView with download, cookie, and POST-body capture.
  It has no ad blocker.

## Where Pull is designed to win

1. The first GET is also segment 0 (no separate query step).
2. All connections open at once when headers arrive, instead of one at a time.
3. Pre-allocation never blocks downloading.
4. End-game duplicate requests remove the slow tail.
5. HTTP/1.1 forced for segments, so each segment is a real TCP connection.
6. Native Kotlin, smaller APK, no Qt runtime.
7. Built-in ad blocker.

Items 1–5 are hypotheses. `08-benchmarking.md` defines how to confirm them.
