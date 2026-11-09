# 02 — Pull architecture

## Stack

| Concern | Choice | Why |
|---|---|---|
| Build | AGP 9.4.0, Kotlin 2.4.10, Gradle version catalog | Same as `../Rewire`, `../Findex`, `../domus` |
| Package | `com.aiyu.pull` | Matches `com.aiyu.*` of the sibling apps |
| Language | Kotlin 2.4 | Coroutines, no JNI layer for the core |
| UI | Jetpack Compose + Material 3 Expressive | See `06-ui-material3-expressive.md` |
| HTTP | OkHttp 5 (HTTP/1.1 for segments, HTTP/2 allowed for probes) | Mature, connection pool control, Okio buffers |
| Optional HTTP/3 | Cronet (Play Services or embedded), behind a flag, later | QUIC for CDNs that support it |
| Persistence | Room 2.8.4 (SQLite) | Downloads, segments, browser history, bookmarks |
| Settings | DataStore (Preferences) | Typed, async |
| DI | Hilt 2.60.1 + `androidx.hilt` 1.4.0 | Same as sibling apps |
| Navigation | Navigation Compose 2.9.6 | Same as sibling apps |
| Startup perf | `:baselineprofile` module | Same as Rewire/Findex |
| Background | Foreground service (`specialUse`) | Long downloads; see `04-foreground-service.md` |
| Browser | Android System WebView + `androidx.webkit` | Chromium engine, no bundled engine; see `05-browser-and-adblock.md` |
| Ad block | adblock-rust (Brave, MPL-2.0) via JNI, or Kotlin matcher | See `05` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 | Same as Findex; 26 for notification channels and `FileChannel` |

## Modules

```
:app                 Activity, navigation, DI graph
:core:model          Download, Segment, enums (pure Kotlin)
:core:database       Room DB, DAOs
:core:datastore      Settings
:core:network        OkHttp clients, DNS, cookie bridge
:engine              Segmented download engine (pure Kotlin + OkHttp; JVM unit-testable)
:service             DownloadService (FGS), notifications, wake/wifi locks
:feature:downloads   Downloads list, details, add-download sheet
:feature:browser     WebView browser, tabs, download sniffing
:feature:adblock     Filter list loading, matcher, cosmetic CSS
:feature:settings    Settings screens
:baselineprofile     Baseline profile generator (like Rewire/Findex)
:benchmark           Engine throughput harness
```

Start smaller if this is too many modules for v1: Findex uses `:app :core :ui :baselineprofile`.
Pull can begin with `:app :engine :baselineprofile` and split later. `:engine` stays separate from
the start because it must be testable on the JVM.

`:engine` has no Android UI dependencies. It can be tested on the JVM against a local server.

## Layers and data flow

```
UI (Compose) --observes--> Repository (Flow<List<Download>>) <-- Room
     |                                ^
     | intents (add/pause/resume)     | progress batches (every 500 ms)
     v                                |
DownloadService (FGS) ---owns---> DownloadManager (scheduler, global limits)
                                        |
                                        v
                                  DownloadTask (one per download)
                                        |
                          +-------------+-------------+
                          v             v             v
                     SegmentWorker  SegmentWorker  SegmentWorker  (coroutines, 1 TCP connection each)
                          \             |             /
                           +---- FileChannel.write(buf, position) ----+
```

- `DownloadManager` enforces `maxDownloads`, `maxConnections` (global) and `maxConnectionsPerHost`.
- `DownloadTask` owns the segment table and the split/steal logic (`03-download-engine.md`).
- Progress lives in memory as `AtomicLong` per segment. It is flushed to Room in batches
  (one transaction about every 1 s) and on pause, error or completion.
- The UI never reads per-byte state. It gets a `StateFlow` sampled at about 4 Hz.

## Database schema (Room)

```kotlin
@Entity data class DownloadEntity(
  @PrimaryKey val id: Long,
  val url: String,
  val finalUrl: String?,
  val fileName: String,
  val destinationUri: String,      // file:// or content:// (MediaStore/SAF)
  val mimeType: String?,
  val totalBytes: Long,            // -1 = unknown
  val downloadedBytes: Long,
  val status: Status,              // QUEUED, CONNECTING, RUNNING, PAUSED, COMPLETED, FAILED
  val etag: String?, val lastModified: String?,
  val acceptRanges: Boolean,
  val headersJson: String,         // referer, user-agent, cookies, POST body ref
  val maxConnections: Int,
  val speedLimitBps: Long,         // 0 = unlimited
  val createdAt: Long, val finishedAt: Long?,
  val error: String?,
)

@Entity(primaryKeys = ["downloadId", "index"]) data class SegmentEntity(
  val downloadId: Long, val index: Int,
  val start: Long, val end: Long,  // inclusive end
  val current: Long,               // next byte to write
)
```

Segments that finish are merged (like FDM `compactFinishedSections`) so the table stays small.

## Threading

- `Dispatchers.IO` limited view (`limitedParallelism(maxConnections + 4)`) for socket reads and disk writes.
- One coroutine per segment. Blocking reads use Okio `source.read(buffer, 256 KiB)`.
- No main-thread work in the engine. The service binds to the manager and posts notifications at most once per second.

## Error handling

- Per-segment retry with exponential backoff and jitter (1 s, 2 s, 4 s ... max 60 s; 10 attempts default).
- HTTP 403/410 on resume: refresh the link (re-run the browser request with saved cookies/headers), or mark as "link expired".
- `ETag`/`Last-Modified`/size changed on resume: do not mix bytes. Restart from zero after asking the user.
- Server ignores `Range` (returns 200 with full body): drop to a single connection, no resume.
- Network change: pause all segments, wait for `ConnectivityManager` callback, resume.
