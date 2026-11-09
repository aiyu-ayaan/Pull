# 04 — Foreground service and background execution

## Constraints (Android 14–16)

- Android 14+ requires a declared `foregroundServiceType` and the matching permission.
- Android 15 limits `dataSync` (and `mediaProcessing`) foreground services to **6 hours in 24 hours**.
  After that, `Service.onTimeout()` is called and the service must stop.
- FDM 6.35 uses `specialUse` for this reason (manifest justification quoted in `01-fdm-teardown.md` §3).
- Android 12+ blocks starting a foreground service from the background, with exceptions.
  Starting from a user action in the activity, a notification action, or `BOOT_COMPLETED` is allowed.
- Android 13+ needs the `POST_NOTIFICATIONS` runtime permission for the progress notification.

## Decision

Use one `DownloadService` with `foregroundServiceType="specialUse"` on API 34+,
like FDM. Keep the `dataSync` type as a fallback on API 29–33, where `specialUse` does not exist.

Alternative to evaluate later: **User-Initiated Data Transfer jobs** (`JobInfo.Builder.setUserInitiated(true)`,
API 34+, `RUN_USER_INITIATED_JOBS` permission). Google recommends this for user-started transfers.
It needs a network constraint and a notification. It is not limited to 6 hours, but the system
may stop it under some conditions. Track this in TODO; do not build it first.

**Play Store risk:** `specialUse` needs a justification in the Play Console declaration.
A download manager that runs user-started downloads longer than 6 hours is a valid case, and FDM ships it.
Review is still possible.

## Manifest

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

<service
    android:name=".service.DownloadService"
    android:exported="false"
    android:foregroundServiceType="specialUse|dataSync">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-initiated file downloads that can run longer than 6 hours; dataSync is time-limited."/>
</service>
```

Call `startForeground(id, notification, type)` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+,
and `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 29–33.
On API 35+, implement `onTimeout(startId, fgsType)` for the `dataSync` path: persist state and stop.

## Lifecycle

1. User adds a download, so the activity calls `startForegroundService()`. The service calls
   `startForeground()` within 5 s, before any network work.
2. Service owns `DownloadManager`. The UI binds for live state; Room is the source of truth.
3. When no download is running or queued: `stopForeground(STOP_FOREGROUND_REMOVE)` then `stopSelf()`.
4. Process death: on next start, rows in `RUNNING` become `PAUSED`; resume if auto-resume is on.
5. Boot: `BOOT_COMPLETED` receiver restarts the service only if queued downloads exist and the
   user enabled "resume after reboot".

## Locks

- `PowerManager.PARTIAL_WAKE_LOCK` held only while bytes are moving, with a timeout, refreshed
  every 10 min. Release it on pause and when idle.
- `WifiManager.WifiLock` with `WIFI_MODE_FULL_HIGH_PERF` on API < 29, and `WIFI_MODE_FULL_LOW_LATENCY`
  as an option on API 29+. Held only while downloading on Wi-Fi.
- Ask the user once to exempt Pull from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
  when a download is long. Explain why. Do not ask on first launch.

## Notifications

- Channel `downloads_progress` (low importance, silent) for the ongoing summary.
- Channel `downloads_done` (default importance) for completed and failed downloads.
- Summary notification: "3 downloading · 24.1 MB/s", determinate progress.
  Actions: Pause all, Resume all.
- Optional per-download child notifications in a group.
- Update rate: at most once per second (the system drops faster updates).
- On API 36+, consider `Notification.ProgressStyle` (Android 16 progress-centric notifications)
  for the single active download.

## Network policy

- `ConnectivityManager.registerDefaultNetworkCallback`: pause on loss, resume on return.
- "Wi-Fi only" and "Not on metered networks" settings per download and globally.
- Bind sockets to the current network so a Wi-Fi to mobile switch does not leave dead sockets.
