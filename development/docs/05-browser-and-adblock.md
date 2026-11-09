# 05 — Built-in browser and ad blocker

## Engine choice

| Option | Size cost | Ad block quality | Effort | Verdict |
|---|---|---|---|---|
| **Android System WebView** (Chromium, updated by Play) | 0 MB | Good: network via `shouldInterceptRequest`, cosmetic via injected CSS | Low | **Use this.** FDM uses it too |
| GeckoView (Firefox engine) | +50–70 MB | Best: can run uBlock Origin as a WebExtension | Medium | Possible later "engine" option |
| Bundled Chromium (custom build) | +100 MB, weeks of builds | Full control | Very high | No |

"Full browser like Chromium" is met by System WebView: it is the Chromium engine. Pull adds tabs,
history, bookmarks, incognito, desktop mode and download capture around it.

## Browser features

- Tabs (list + grid switcher), incognito tabs (separate cookie handling; clear on close).
- Address bar with search suggestions; search engine choice (default: DuckDuckGo).
- History, bookmarks (import from FDM-style HTML bookmarks file later), downloads shortcut.
- Desktop mode (`WebSettings.userAgentString` override + wide viewport).
- Find in page, share, open in external app, reader mode (later).
- Pull-to-refresh, back/forward with predictive back.
- `WebViewCompat` / `androidx.webkit` features: `addDocumentStartJavaScript`, `ALGORITHMIC_DARKENING`
  for dark mode, `setSafeBrowsingEnabled`.
- Safe Browsing on by default.

## Download capture

1. **`DownloadListener.onDownloadStart(url, userAgent, contentDisposition, mimeType, contentLength)`**
   → show the "Add download" bottom sheet with name, size, and connections. Copy:
   - cookies: `CookieManager.getInstance().getCookie(url)`
   - `User-Agent`, `Referer` (current page URL)
2. **POST downloads** (forms that return a file): inject a document-start script that hooks
   `form.submit` and `fetch`/`XMLHttpRequest` POSTs, and sends the body to a
   `WebMessageListener` (`WebViewCompat.addWebMessageListener`, safer than `addJavascriptInterface`).
   FDM hooks form submit and XHR only, not `fetch` (`01-fdm-teardown.md` §7).
   Keep request info (headers, POST body) per tab in a bounded LRU (about 200 entries), not FDM's
   10-entry window, so the download reuses the exact request.
3. **`blob:` URLs**: the engine cannot fetch them. Inject JS that reads the blob with `FileReader` in
   chunks and streams it over the message channel, or save it directly.
4. **Media sniffing**: in `shouldInterceptRequest`, record URLs with `.m3u8`, `.mpd`, `video/*`,
   `audio/*` content. Show a floating "N media found" chip on the page. Tapping it lists streams with
   quality (HLS master playlist variants).
5. **Long-press** on a link or image: "Download with Pull".
6. **Intercept by extension** setting: links to `.zip .apk .mp4 .pdf ...` go to Pull instead of
   loading in the WebView (`shouldOverrideUrlLoading`).

Only capture from the user's own browsing in Pull. Do not respect pages that ask to "download with
another tool" through JS; treat page content as untrusted.

## Ad blocker

### Lists

- Default: EasyList, EasyPrivacy, and uBlock filters (check each list's license before bundling;
  EasyList is GPLv3/CC BY-SA, so download at runtime rather than bundle if licensing is unclear).
- Optional: regional lists, Peter Lowe's list, annoyance lists.
- Update every 4 days on unmetered networks (WorkManager periodic job).
- Ship a small built-in snapshot so blocking works on first launch offline.

### Matcher

Pull implements **Option B: Kotlin high-performance matcher** with a bundled 3,680+ domain snapshot
(`app/src/main/assets/adblock_domains.txt`) sourced from Peter Lowe's adservers list plus top popup networks,
click-jackers, and tracker networks.
- **Fast hierarchical suffix matching**: `isAdDomain(host)` checks hierarchical suffixes (`sub.ads.example.com` → `ads.example.com` → `example.com`) against an in-memory `HashSet<String>` loaded in < 4 ms.
- **Allocation-free URL parsing**: extracts host and path segments via zero-allocation string slicing, running in < 2 µs per check without Android framework dependencies (fully testable on JVM).
- **Popup & redirect shield**:
  - `onCreateWindow`: checks `isUserGesture`, verifies hit-test target URLs, and throttles rapid-fire popup bursts (< 800 ms).
  - `shouldOverrideUrlLoading`: detects ad and tracker destinations; cancels unwanted navigation and immediately closes unverified popup candidate tabs.
  - Injected JavaScript popup shield wraps `window.open` to suppress burst script loops.

### Network blocking

```kotlin
override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
    if (!adblockEnabledFor(currentHost)) return null
    val type = guessResourceType(req)            // from Accept header + extension
    val result = engine.check(req.url.toString(), currentPageUrl, type)
    return if (result.blocked) EMPTY_RESPONSE(type) else null
}
```

- Runs on a WebView background thread. The check must be fast (target < 50 µs median). No allocation-heavy work.
- Return an empty `200` with a matching MIME type (empty JS/CSS/1x1 GIF) so pages do not break.
- Keep blocked-count per tab for the shield badge.

### Cosmetic filtering

- On each navigation, get hide selectors for the host from the engine.
- Inject `<style>selectors { display: none !important; }</style>` with `addDocumentStartJavaScript`
  so elements are hidden before first paint.
- Generic cosmetic filters: limit to a size budget per page to avoid slow style recalculation.

### Controls

- Shield icon in the address bar: blocked count, toggle for this site (allowlist), "report broken site" (local note).
- Global toggle and list management in settings.

### Limits (document for users)

- `shouldInterceptRequest` does not see requests from service workers in all cases; use
  `ServiceWorkerController` client to cover them.
- WebView cannot block requests inside some media pipelines. In-video ads served from the same
  domain as content cannot be blocked by network rules.
