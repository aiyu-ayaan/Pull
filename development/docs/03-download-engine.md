# 03 — Download engine: segmented, chunked, fast

Goal: finish the same file faster than FDM 6.35 on the same device and network.
Every speed claim must be backed by `08-benchmarking.md`.

## 1. Why multiple connections are faster

- TCP throughput per connection is limited by `window / RTT` and by loss recovery. Several connections
  fill the pipe sooner and recover from loss independently.
- Many servers and CDNs throttle **per connection**. N connections can get up to N times the rate.
- Segments must use **separate TCP connections**. Over HTTP/2, OkHttp multiplexes all requests to
  one host on a single connection, so "8 segments" would still be one TCP flow.
  **Rule: segment workers use an OkHttp client with `protocols = [HTTP_1_1]`.**
  HTTP/2 is allowed only for the probe and single-connection downloads.

## 2. Terms

- **Segment**: `[start, end]` byte range owned by one worker; `current` is the next byte to write.
- **Remaining** of a segment: `end - current + 1`.
- **Min split size**: smallest remainder worth splitting. Default 1 MiB.

## 3. Lifecycle

### 3.1 Probe (no separate HEAD)

FDM appears to run a separate query step before adding sections (`01-fdm-teardown.md` §2).
Pull sends the real download request first:

```
GET /file  Range: bytes=0-
```

- `206` + `Content-Range: bytes 0-X/TOTAL` → ranges supported, size = TOTAL.
  This connection **keeps streaming** as segment 0. No bytes or RTT are wasted.
- `200` with `Content-Length` → ranges unknown. Keep streaming. Check range support with one extra
  `Range: bytes=<mid>-` request. If it returns 206, split; otherwise stay single.
- `200` without a length (chunked) → single connection, no resume.

Save `ETag`, `Last-Modified`, final URL after redirects, and `Content-Disposition` file name.

### 3.2 Immediate ramp-up

When total size is known and ranges work, compute the target count:

```
n = clamp(total / minSplit, 1, maxConnectionsPerDownload)
n = min(n, freeSlots(global), freeSlots(host))
```

Split the remainder of segment 0 into `n` equal parts and **start them all at once**.
FDM adds one section at a time (`startExtraSectionIfPossible`), which delays full speed by several RTTs.

### 3.3 Pre-allocation in parallel

- `Os.posix_fallocate(fd, 0, total)` on a background coroutine.
- Workers do not wait for it. `FileChannel.write(buf, pos)` past the current end is valid; the
  file grows sparse until allocation completes.
- If `fallocate` fails with `ENOSPC`: pause the download and report "not enough space" before
  downloading more bytes.
- FDM refuses new sections while pre-allocating (observed log). Pull never blocks on it.

### 3.4 Work stealing (dynamic segmentation)

When a worker finishes its segment and a slot is free:

1. Pick the segment with the largest remaining bytes.
2. If remaining < `2 * minSplit`, do not split.
3. Otherwise split at `mid = current + remaining / 2` (aligned down to 64 KiB).
   The victim's `end` becomes `mid - 1` (atomic CAS on `end`). The new segment is `[mid, oldEnd]`.
4. The new worker opens a connection with `Range: bytes=mid-oldEnd`.

The victim reads `end` before each write and never writes past it (it trims the buffer). The CAS makes
the split safe without a lock.

### 3.5 Slow-segment detection and end-game

- Every 2 s, compute each segment's rate (EWMA, alpha 0.3).
- A segment slower than 25% of the median for 6 s is **lazy** (FDM also has `findLazySection`).
  Split it so a fresh connection takes the second half. If its remainder is too small, restart it on a
  new connection.
- **End-game:** when less than about 2 s of data remains at the current rate, and a slot is free,
  start a **duplicate** request for the tail of the slowest segment. The first worker to reach a byte
  writes it; the other stops at that offset. This removes the long tail at 99%.

### 3.6 Adaptive connection count (optional, setting "Auto")

Start with 4 connections. Every 3 s, add 2 while aggregate speed grows by more than 10%.
Stop at the limit or when gains stop. Remove connections on HTTP 429/503 or connection resets.
Fixed mode uses the user setting (default 8, max 32).

### 3.7 Multi-IP spreading

DNS often returns several A/AAAA records. Pull's custom OkHttp `Dns` rotates the address list per
connection, so segments spread over CDN edge nodes. Fall back to normal order if a node fails.

### 3.8 Mirrors

Optional list of mirror URLs with the same content (checked by size + ETag or first 64 KiB hash).
Segments are spread across mirrors, as in FDM's `DownloadMirrorsUsageManager`.

## 4. Disk I/O

- Destination: MediaStore `Downloads` (API 29+) with `IS_PENDING=1`, or an app-specific folder,
  or a SAF tree the user picked. Open with `openFileDescriptor(uri, "rw")` and get a `FileChannel`.
- Writes: each worker calls `channel.write(byteBuffer, position)` (pwrite). No shared lock and no writer thread.
- Buffers: pooled direct `ByteBuffer`s, 256 KiB each (benchmark 64 KiB / 256 KiB / 1 MiB).
- `fsync`: only on pause, completion, and every 32 MiB, not per write.
- Progress persistence: segment `current` values flushed to Room about every 1 s in one transaction.
  On a crash, at most 1 s of data is downloaded again, never corrupted, because `current` is saved
  only after the bytes are written.

## 5. Network details

- Socket read buffer: let the kernel autotune; do not set `SO_RCVBUF` (setting it disables autotuning).
- `TCP_NODELAY` on (OkHttp default).
- Timeouts: connect 15 s, read 30 s per segment. A read timeout triggers retry or lazy handling.
- `Accept-Encoding: identity` for segmented downloads. Ranges on compressed bodies break offsets.
- Headers forwarded from the browser: `User-Agent`, `Referer`, `Cookie`, `Origin`, auth, and the POST
  body for POST downloads.
- Proxy: system proxy by default. HTTP/SOCKS proxy in settings.
- Speed limit: token bucket per download plus a global bucket. Workers request tokens before each read.

## 6. Defaults

| Setting | Default | Range |
|---|---|---|
| Connections per download | 8 | 1–32 |
| Connections per host (global) | 16 | 1–64 |
| Global connections | 48 | 1–128 |
| Simultaneous downloads | 3 | 1–10 |
| Min split size | 1 MiB | 256 KiB–16 MiB |
| Read buffer | 256 KiB | 64 KiB–1 MiB |
| Retries per segment | 10 | 0–unlimited |

Defaults are starting points. Tune them from benchmark results.

## 7. Engine API sketch

```kotlin
interface DownloadEngine {
    fun start(request: DownloadRequest): DownloadHandle
}

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String>,
    val method: String = "GET",
    val body: ByteArray? = null,
    val destination: Destination,
    val maxConnections: Int,
    val resume: List<SegmentState>? = null,
)

interface DownloadHandle {
    val state: StateFlow<DownloadState>   // sampled; not per byte
    suspend fun pause(): List<SegmentState>
    fun cancel()
}
```

## 8. Later formats (FDM parity)

| Format | FDM module | Pull plan |
|---|---|---|
| HLS `.m3u8` | `libdownloadsm3u` | Parse playlist, pick variant, download segments in parallel, remux with Media3 `Transformer` or FFmpegKit replacement |
| DASH / split A+V | `libdownloadsfragments` | Same, merge streams |
| Batch / URL patterns | `libdownloadsbatch` | `file[001-100].jpg` expansion |
| BitTorrent | `libdownloadsbt` (libtorrent) | libtorrent4j, separate optional module |
| FTP / SFTP | curl | Low priority |
