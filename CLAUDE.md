# Pull

Pull is an Android download manager meant to finish downloads faster than Free Download Manager (FDM).
It has a segmented HTTP engine, a foreground download service, a built-in WebView browser with an ad
blocker, and a Material 3 Expressive UI.

Status: design phase. No app code exists yet. The plan is in `development/docs/` and `development/TODO.md`.

## Where things are

- `development/docs/README.md` — index of the design docs.
- `development/docs/01-fdm-teardown.md` — reverse-engineering notes on FDM 6.35.2.7075, each claim labeled
  observed / inferred / unknown.
- `development/docs/03-download-engine.md` — the segmented download algorithm. This is the core of the project.
- `development/docs/08-benchmarking.md` — how "faster than FDM" is measured.
- `development/TODO.md` — phased task list. Update checkboxes as work lands.
- `fdm_armv8.apk` — the analyzed FDM build. Local only, git-ignored. Do not commit it.

## Target stack

Kotlin 2.4.10, AGP 9.4.0, Compose BOM 2026.08.00, material3 1.5.0-alpha26, Hilt 2.60.1, Room 2.8.4,
Navigation Compose 2.9.6, OkHttp. Package `com.aiyu.pull`. minSdk 26, targetSdk 36, compileSdk 37.
These match the sibling projects `../Rewire`, `../Findex` and `../domus`.

## Rules

- UI follows the sibling projects. Copy their theme, shape, motion, spacing, adaptive and haptics
  patterns (file map in `development/docs/06-ui-material3-expressive.md`). Use Material 3 Expressive.
- Segment workers must use HTTP/1.1 clients. HTTP/2 would put all segments on one TCP connection.
- The download service uses `foregroundServiceType="specialUse"` on API 34+ (Android 15 limits `dataSync`
  to 6 hours). See `development/docs/04-foreground-service.md`.
- `:engine` must stay free of Android UI code so it can be tested on the JVM.
- No speed claim about Pull versus FDM goes into docs or release notes without benchmark numbers.
- No FDM code or assets are copied into Pull. Reuse architecture ideas only.
- Never proceed past SSL errors in the browser. Do not add telemetry by default.

## Working notes

- The FDM teardown used `strings`, symbol names and JADX output. The native C++ engine was not
  disassembled, so FDM's exact split thresholds are unknown.
- Decompiled FDM sources are not stored in the repo. To regenerate: unzip the APK and run
  `jadx -d out --no-res classes.dex`.
