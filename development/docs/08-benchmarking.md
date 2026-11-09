# 08 — Benchmarking: proving "faster than FDM"

No speed claim ships without these numbers.

## Setup

- Same phone for both apps, same storage target (internal shared Downloads), same network, screen on,
  charger connected, battery optimization off for both.
- FDM version: 6.35.2.7075 (the analyzed APK). Set FDM connections to its maximum and Pull to the same
  number for the "equal connections" run, then each app on its own defaults.
- Run each case 5 times, alternate apps, report median and p90.

## Test servers

1. **Local lab**: nginx on a LAN machine serving 100 MB, 1 GB, 4 GB files.
   Use `tc netem` on the server to add latency and loss:
   - `delay 20ms` (good Wi-Fi)
   - `delay 80ms loss 0.5%` (mobile)
   - `delay 200ms loss 1%` (bad network)
   - per-connection rate cap with nginx `limit_rate 2m` (CDN-style throttling)
2. **Real CDNs**: public test files (for example a Linux ISO mirror, Cloudflare, a GitHub release asset).
   Record time of day; repeat on another day.

## Metrics

| Metric | How |
|---|---|
| Total time | add tap to file complete (both apps show it) |
| Time to full speed | first time aggregate speed reaches 90% of the run's peak |
| Average throughput | size / total time |
| Tail time | time from 95% to 100% |
| CPU % and battery | `adb shell dumpsys batterystats`, `top` sampling |
| Correctness | SHA-256 of the result vs the source |

Pull logs per-segment timelines to a file in debug builds for the segment map and tail analysis.

## Pass criteria for v1

- Median total time ≤ FDM on every case, and at least 10% lower on the 80 ms and throttled cases.
- Tail time at least 30% lower (end-game mode).
- Zero hash mismatches across all runs, including runs with forced pause/resume and airplane-mode toggles.

## Engine micro-benchmarks (JVM, `:engine`)

- MockWebServer / local Ktor server with range support and a configurable per-connection rate.
- Cases: split correctness under random pauses; CAS split race (stress test with 32 workers);
  resume after kill at random offsets; server ignoring `Range`; server changing ETag mid-download.
