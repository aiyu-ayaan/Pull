# 01 — FDM Android teardown

Target: `fdm_armv8.apk`, Free Download Manager for Android **6.35.2.7075**, arm64-v8a.
SHA-256: `0fd5cc97088cb88a2227e399f37ed2bf8b0b6b94f668470611768856a98bc64c`

## Method and limits

- APK unzipped; `AndroidManifest.xml`, `classes.dex` and native `.so` files inspected with `strings`
  (string tables, C++ mangled symbols, embedded QML source). The target was never executed.
- `classes.dex` was decompiled with JADX 1.5.1 (2340 classes, 2 methods failed). The Java layer
  (`org.freedownloadmanager.fdm`, 2952 lines) was read in full for the service, browser and JNI surface.
  See §7.
- The native engine (C++) was not disassembled. Sections 2 and 6 come from symbol names and log strings.
- Labels used below:
  - **Observed** — the string or symbol is present in the binary.
  - **Inferred** — a reasonable reading of observed names; not confirmed by code or runtime.
  - **Unknown** — not determined.
- No FDM code is copied into Pull. Only architecture ideas are reused.

## 1. Overall shape

**Observed**

- FDM Android is a **Qt 6 / QML** app. It ships `libQt6Core`, `libQt6Quick`, `libQt6Qml`,
  QuickControls2 styles (Material, Fusion, Universal, Imagine, FluentWinUI3), and the QML UI source is
  embedded in `libfdm_arm64-v8a.so`.
- The Java layer is thin: `org.qtproject.qt.android.*` bindings plus about 30 classes in
  `org.freedownloadmanager.fdm`.
- The engine is native C++ built from `src/vmscl/inet/qt/download/...` (paths from Jenkins assert strings).

| Native lib | Size | Role (from symbols) |
|---|---|---|
| `libfdm` | 19.6 MB | App core, QML UI, settings, SQLite (`db.sqlite`, `SqlEasyTableManager`) |
| `libdownloadswww` | 1.8 MB | HTTP/HTTPS/FTP/SFTP engine on **libcurl 8.7.1** (`CurlDownloader`, `WwwDownload`, `WwwDownloadSection`) |
| `libdownloadsbt` | 10.3 MB | BitTorrent on **libtorrent-rasterbar** + Boost.Asio |
| `libdownloadsm3u` | 1.3 MB | HLS (`M3UStreamDownload`, `M3UMasterPlaylistDownloadResourceVersionSelector`, `M3UMediaPlaylistReloader`) |
| `libdownloadsfragments` | small | `FragmentsDownload` (multi-fragment media, e.g. DASH/split A+V) |
| `libdownloadsbatch` | small | `BatchDownload` (batch/URL-pattern downloads) |
| `libffmpeg` | 22 MB | Muxing/merging media (HLS to MP4, A+V merge) |
| `libvmsclshared` | 1.7 MB | Shared framework (settings, IPC) |
| `libcrypto_3`, `libssl_3` | | OpenSSL 3 for curl/libtorrent |

**Inferred:** APK size (60 MB) is mostly Qt + FFmpeg + libtorrent. A native Kotlin/Compose app can be
far smaller and start faster.

## 2. HTTP segmented download engine (`libdownloadswww`)

### Observed classes and methods

`WwwDownload` (one per download):
`createNewSection`, `startExtraSection`, `startExtraSectionIfPossible`, `mayCreateSection`,
`findBiggestBytesLeftSection`, `findLazySection`, `checkConnectionsLimit`, `runningSectionCount`,
`compactFinishedSections`, `serializeSections`, `sortedSections`, `supportsWriteOffset`,
`setWritersThread`, `onSectionFilePreallocating`, `onSectionFilePreallocated`, `checkIfResourceChanged`,
`mergeIntoThisDownload`, `onGlobalConnectionsChanged`, `onSectionTimedOut`.

`WwwDownloadSection`, `CurlDownloader` (`curlMultiPerform`, `curlWriteCallback`, `truncateSection`,
`tryAgain`, `applyProxy`, `applyNus` = network usage settings, `checkDataExcess`, `processPendingData`).

`DownloadMirrorsUsageManager` (`pickMirror`, `sortMirrors`, `nextMirrorUrl`,
`nextMirrorConnectionCount`, `containsMirrorWithoutErrors`).

### Observed log strings

```
creating a new section...
biggest section resume offset:
failed to create a new section: can't find the biggest section
failed to create a new section: too small
failed to create a new section: file size is unknown
failed to create a new section: preallocating (current progress is: ...
failed to starting extra section. Reason: query mode
starting extra section if possible...
The max connection limit is reached
forcibly testing resume support
HTTP server doesn't seem to support byte ranges. Cannot resume.
compacted parent section is already finished
the whole parent section is already finished
trying next mirror
Could not set TCP_NODELAY
```

### Inferred algorithm (dynamic segmentation, IDM-style)

1. Start one connection in **query mode**. Until the response headers arrive (size, `Accept-Ranges`),
   no extra section may start ("Reason: query mode").
2. If size is known and ranges work: **pre-allocate** the file. While pre-allocating, new sections are
   refused ("preallocating (current progress is...)").
3. Add sections **one at a time** (`startExtraSectionIfPossible`). Each new section takes the
   **biggest remaining section** (`findBiggestBytesLeftSection`) and splits it, starting at its
   midpoint ("biggest section resume offset"). The parent is truncated (`truncateSection`).
4. Stop splitting when the remainder is "too small" or the connection limit is reached
   (per download, per server, global: `MaxConnections`, `MaxConnectionsPerServer`, `MaxDownloads`).
5. `findLazySection`: a slow section is detected and its work can be taken over.
6. Finished sections are merged (`compactFinishedSections`) and the section list is persisted
   (`serializeSections`) for resume.
7. Disk writes go through a separate **writers thread** (`setWritersThread`), using positional writes
   (`supportsWriteOffset`).
8. `checkIfResourceChanged` compares the resource on resume (ETag/Last-Modified/size) to avoid corruption.

### Settings (observed keys)

`MaxConnections`, `MaxConnectionsPerServer`, `MaxConnectionsPerServerLimit`, `MaxDownloads`,
`MaxDownloadSpeed`, and **Traffic Usage Modes** `Snail | Low | Medium | High`.
UI slider range for connections: `from: 0 to: 100` (observed in QML; exact mapping unknown).

**Unknown:** default numeric values, minimum section size, lazy-section threshold, buffer sizes.

### HTTP protocol support

- **Observed:** curl HTTP/2 strings (`MAX_CONCURRENT_STREAMS`, `Error in the HTTP2 framing layer`,
  `Multiplexed connection found`) and HTTP/3 strings (`Using HTTP/3`, `QUIC connection error`).
- **Unknown:** whether HTTP/3 is enabled at runtime. These curl strings may exist without the
  feature being active.

## 3. Foreground service

**Observed** in the manifest:

- `MyService` (with inner `NotificationActionService`), permissions `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.
- Special-use justification string:
  > "This service is used to perform downloads created by the user which could take more than 6 hours
  > to complete, so dataSync is not suitable anymore."
- `AutoStartBroadcastReceiver` (boot), `MyAlarmReceiver` (scheduler), `BatteryStatusReceiver`,
  `NetworkStateMonitor`, `AppNetworkStateMonitor`, `NetworkRequestsKeeper`.

**Key lesson:** Android 15 limits `dataSync` foreground services to 6 hours per 24 hours. FDM moved
to `specialUse`. Pull must handle this from day one (see `04-foreground-service.md`).

## 4. Built-in browser

**Observed** Java classes: `WebViewActivity`, `WebViewActivity$MyWebViewClient`, `BibWebViewClient`,
`BibDownloadListener`, `BidPostDataMonitor`, `PersistentCookieStore`, `SerializableHttpCookie`,
`BookmarkActivity`, `BookmarkTools`, `SendToBrowserActivity`.
Observed API names: `setDownloadListener`, `onDownloadStart`, `shouldInterceptRequest`,
`shouldOverrideUrlLoading`, `addJavascriptInterface`, `evaluateJavascript`, `getCookie`,
`onBeforeSendPostData`, `containsPostDataForUrl`, `extractPostDataForUrl`,
JS bridge name `postDataMonitor_D9A5FBEB963C4D06BFA19E4864D6E1FF`.

**Inferred:**

- The browser is the **Android System WebView** (Chromium-based), not a bundled Chromium.
- Downloads are caught with `DownloadListener.onDownloadStart`. Cookies are copied from
  `CookieManager` so the engine can download behind logins.
- Injected JS reports form **POST bodies** to Java so POST-triggered downloads can be replayed.

**Observed absence:** no ad-block strings (`easylist`, `adblock`, filter lists) in dex or `libfdm`.
FDM Android has **no ad blocker**. This is a gap Pull fills.

## 5. Other features (observed)

- Torrents and magnet links (`magnet` scheme, `application/x-bittorrent`, `.torrent` path patterns).
- QR code scanner (`QRCodeScannerActivity`, zxing / `me.dm7.barcodescanner`).
- Share-to-download intents (`SendActivity`, `...intent.action.SEND`).
- `DownloadFileProvider` for opening finished files.
- Storage handling (`AndroidStoragesManager`, `SharedStoragePaths`, media mount receiver).
- Firebase Analytics and `AD_ID` permission (telemetry).
- Remote-control IPC (`VhIpcRemoteDownloadsInfos3KeeperServer`) — **inferred** to be for FDM's
  remote access feature.

## 6. Where Pull can beat FDM (hypotheses to benchmark)

| FDM behavior | Evidence | Pull approach |
|---|---|---|
| Sections are added one at a time after the query finishes | Observed `startExtraSectionIfPossible`, "query mode" | Open all N connections at once when headers arrive (`03-download-engine.md` §3) |
| No new sections while pre-allocating | Observed log string | `fallocate` runs in parallel; segments never wait for it |
| Separate HEAD/query step | Inferred from "query mode" | First request is a real `GET Range: bytes=0-`, so it becomes segment 0 |
| One writers thread | Observed `setWritersThread` | Positional `FileChannel.write(buf, pos)` from each segment, no shared lock |
| Qt event loop + JNI bridge | Observed | Native Kotlin coroutines + OkHttp, no UI-toolkit layer |
| No ad blocker | Observed absence | Built-in network + cosmetic blocking |
| 60 MB APK | Observed | Target < 20 MB without torrent/FFmpeg |

These are hypotheses until `08-benchmarking.md` measurements confirm them.

## 7. Java layer (decompiled, observed)

### Processes

The manifest declares separate processes: `:service`, `:webbrowser`, `:phoenix` (ProcessPhoenix restart
helper) and `:custom_activity`. The engine and the browser run in different processes.
Which component goes in which process was not mapped (**inferred**: `MyService` in `:service`,
`WebViewActivity` in `:webbrowser`). The native `VhIpc*` classes are the likely cross-process channel.

### `MyService` (extends Qt `QtService`)

- The whole Qt/C++ engine runs inside the service. `onCreate` calls
  `startForeground(1, notification, -1)` on API 29+. `-1` is `FOREGROUND_SERVICE_TYPE_MANIFEST`:
  all types declared in the manifest.
- `onStartCommand` returns `2` (`START_NOT_STICKY`). The service is not restarted by the system after
  it is killed; restart goes through `MyAlarmReceiver`/`AutoStartBroadcastReceiver`.
- **One `PARTIAL_WAKE_LOCK` with no timeout**, held from `onCreate` until `onDestroy`, even when no
  bytes move. No Wi-Fi lock.
- **One notification only** (id 1, low priority, `setForegroundServiceBehavior(IMMEDIATE)`):
  aggregate progress and speed text built in native code. Actions: **PAUSE ALL** (shown only if some
  running download supports resume) and **SNAIL MODE** toggle. No per-download notifications.
- `BatteryStatusReceiver` sends charging state and level to native code. The level is computed as
  `int level / int scale`, so it is always `0` or `1` (integer division bug).
- Scheduler uses `AlarmManager.set(RTC_WAKEUP, ...)`, which is inexact.

### Browser (`WebViewActivity`, `BibWebViewClient`, `BibDownloadListener`)

- Plain `WebView` with a classic `EditText` address bar and `PopupMenu`. Settings set only
  `setJavaScriptEnabled(true)`. No tabs, no incognito, no ad blocking, no desktop mode.
- Java `CookieHandler` uses `PersistentCookieStore` with `ACCEPT_ALL`.
- `shouldInterceptRequest` runs for **every** request and calls native `onBibRequest(url, pageUrl)`
  over JNI. Native code uses this for media sniffing: the "Download media" menu item is enabled by
  `bibHasMedia(pageUrl)` and starts `bibDownloadMedia(pageUrl)`.
- `NetworkRequestsKeeper` keeps the **last 10 requests** (URL, request headers, POST body). When
  `onDownloadStart` fires, the matching request is taken so the download reuses its exact headers.
  On a busy page the request can be pushed out of the 10-entry window before the download starts.
- POST capture: `assets/www/interceptheader.html` is injected with `evaluateJavascript` in
  `onPageStarted`. It wraps `HTMLFormElement.prototype.submit`, listens to `submit` events, and wraps
  `XMLHttpRequest.open/send`. It sends bodies to the `postDataMonitor_*` `addJavascriptInterface`
  object. **`fetch()` is not hooked**, so POST downloads started with `fetch` are missed.
- A second mode (`InjectJs`, not the default) re-fetches every page with `HttpURLConnection` and
  injects the script after `<head>`. The default mode is `EvaluateJs`.
- `onReceivedSslError` shows a dialog that lets the user **continue past certificate errors**.
- `BibDownloadListener` has bugs visible in the decompiled code: it calls `isEmpty()` on a `null`
  cookie string when Java cookies exist (NullPointerException), and labels the replay request
  `GET` when a POST body is present and `POST` when it is not.
- Downloads from the browser call native `onNewDownload(url, requestHeaders, postData,
  responseHeaders, cookies, pageUrl)`, then switch to the main activity.

### JNI surface (`MyJavaNatives`)

`onNewDownload`, `onNewDownloadFromBytes` (bytes from the page, e.g. data/blob content),
`onBibRequest`, `bibHasMedia`, `bibDownloadMedia`, `shouldOverrideUrlLoading` (native decides if a link
is a download), `pauseAllDownloads`, `enableSnailMode`, `isSnailModeEnabled`,
`hasRunningNonFinishedDownloadsWithResumeSupport`, `downloadProgressText`, `onNetworkChanged`,
`onBatteryStatus`, `onStoragesChanged`, `onActivityStopped`, `qtTranslate`.

### What Pull takes from this

| FDM | Pull |
|---|---|
| Wake lock held for the service's whole life | Hold only while bytes move, with timeout (`04` §Locks) |
| No Wi-Fi lock | Wi-Fi lock while downloading on Wi-Fi |
| One aggregate notification | Summary + optional per-download notifications, pause/resume per item |
| Last-10-requests window | Keep request info keyed by URL per tab for the page's lifetime (bounded LRU, e.g. 200) |
| Form + XHR POST hooks only | Also hook `fetch`; use `addDocumentStartJavaScript` + `WebMessageListener` |
| JNI call on every subresource | Kotlin media matcher on the WebView thread, no JNI |
| SSL errors can be bypassed | Never proceed on SSL errors |
| Snail mode in notification | Keep it: speed-mode action in the summary notification |
