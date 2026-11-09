# 06 — UI: Material 3 Expressive

## Reference apps (same author, same conventions)

Pull follows the UI code of the sibling projects in `../Rewire`, `../Findex` and `../domus`.
Copy the pattern, not the brand colors.

| What | Reference file | Use in Pull |
|---|---|---|
| Theme root | `Rewire/app/src/main/java/com/aiyu/rewire/ui/theme/Theme.kt` | `PullTheme(themeMode, dynamicColor)` wrapping `MaterialExpressiveTheme(motionScheme = MotionScheme.expressive())`; dynamic color off by default, on API 31+ when enabled |
| Shapes | `Rewire/.../ui/theme/Shape.kt` | "Increased" corner scale: 8 / 12 / 20 / 28 / 36 dp |
| Motion tokens | `Rewire/.../ui/theme/Motion.kt` | `PullMotion` constants + `rememberReducedMotion()` (reads `ANIMATOR_DURATION_SCALE`) |
| Spacing | `Findex/ui/src/main/java/com/aiyu/findex/ui/theme/Dimens.kt` | `Dimens` 8 dp grid with 4 dp half step; `minTouch = 48.dp`, `contentMaxWidth = 840.dp` |
| Type-tinted icons | `Findex/.../ui/theme/CategoryTones.kt`, `components/ShapeIcon.kt`, `components/FileVisuals.kt` | File-type tone + `MaterialShapes` icon container for each download row |
| Adaptive width | `Findex/.../ui/components/Adaptive.kt` | `isExpandedWidth()`, `readableGutter()`, `ProvideContentWidth` |
| List-detail | `Rewire/.../ui/components/ListDetail.kt` | Downloads list + details pane on expanded widths |
| Predictive back | `Rewire/.../ui/components/PredictiveBack.kt` | Detail screen and browser tab switcher |
| Hero shape | `Rewire/.../ui/components/MorphingShape.kt` | Empty states and the speed header |
| Haptics | `domus/apps/android/ui-shared/src/main/java/com/aiyu/domus/ui/theme/Haptics.kt` | `Modifier.clickHaptics()` once at the app root |
| Undo snackbar | `Rewire/.../ui/UndoSnackbarHost.kt` | Delete download with undo |
| Design docs | `Findex/development/docs/DESIGN_SYSTEM.md`, `Rewire/development/docs/DESIGN.md` | Same section layout for Pull's design-system doc |

Components already used across those apps (so known to work on the pinned BOM): `LargeFlexibleTopAppBar`,
`MediumFlexibleTopAppBar`, `LoadingIndicator`, `LinearWavyProgressIndicator`, `ToggleButton`,
`ButtonGroupDefaults`, `MaterialShapes`, `ListItem`, `FilterChip`, `ModalBottomSheet`,
`SingleChoiceSegmentedButtonRow`, `ExtendedFloatingActionButton`.

## Setup

- Same versions as the reference apps: Compose BOM `2026.08.00`, `material3` `1.5.0-alpha26`
  (pinned explicitly over the BOM). Expressive components need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- `androidx.compose.material3.adaptive` for list-detail layouts on tablets and foldables.
- Theme root: `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive(), shapes, typography)`.
- Dynamic color on Android 12+ (`dynamicLightColorScheme` / `dynamicDarkColorScheme`), with a static
  Pull brand scheme fallback generated from one seed color in Material Theme Builder.
- Edge-to-edge (`enableEdgeToEdge()`), insets through `Scaffold` padding.
- Predictive back on all screens.

Check exact component names against the BOM in use; several Expressive APIs are still experimental
and names have changed between alphas.

## Navigation

| Window width | Pattern |
|---|---|
| Compact (< 600 dp) | `NavigationBar` (short): Downloads, Browser, Settings |
| Medium (600–839 dp) | `NavigationRail` |
| Expanded (840 dp+) | Rail + list-detail: download list on the left, details on the right |

Use `NavigationSuiteScaffold` to switch automatically.

## Screens

### Downloads (home)

- `LargeFlexibleTopAppBar` (or `LargeTopAppBar`) with a global speed readout: "24.1 MB/s · 3 active".
- Filter chips: All, Active, Completed, Failed, by type (Video, Audio, Docs, Archives, APK).
- List items (expressive list style), each with:
  - file-type icon in a shaped container (use the Expressive shape set, e.g. cookie/clover shapes per type)
  - name, size, speed, ETA
  - `LinearWavyProgressIndicator` while running; flat `LinearProgressIndicator` when paused
  - trailing play/pause `IconToggleButton` that morphs shape on press
- Swipe to delete (with undo snackbar), long-press for multi-select.
- `FloatingActionButtonMenu` (FAB menu): Add URL, Paste from clipboard, Scan QR, Open browser.
- Empty state with `LoadingIndicator`-style shape morph as decoration (not as fake loading).

### Download details

- Header: file name, `Display` size progress, speed graph (last 60 s).
- **Segment map**: one horizontal bar split into segment colors, showing live work stealing. This is
  the visual signature of Pull's speed.
- Connections list: per-segment range, speed, retries.
- `ButtonGroup`: Pause / Resume, Open, Share, Delete.
- Info: URL (copy), final URL, ETag, headers, destination.

### Add download (modal bottom sheet)

- URL field (auto-filled from clipboard or browser), file name, destination, connections slider (1–32),
  "Start now" / "Add to queue" `SplitButton`.
- Shows size and range support as soon as the probe returns.

### Browser

- Bottom `FlexibleBottomAppBar`/`HorizontalFloatingToolbar` with back, forward, tabs count, shield, menu.
- Address bar on top or bottom (setting).
- Shield badge with blocked count. Media-found chip floats above the toolbar.
- Tab switcher in a grid, with incognito section.

### Settings

- Groups: Downloads (connections, simultaneous, min split, speed limit, destination), Network
  (Wi-Fi only, metered, proxy), Browser (search engine, desktop mode, clear data), Ad block (lists,
  update now, allowlist), Appearance (theme, dynamic color, contrast), About.
- Speed modes like FDM's Traffic Usage Modes: `Snail | Low | Medium | High | Unlimited` as a
  connected `ButtonGroup` at the top of Downloads settings.

## Motion

- Use `MaterialTheme.motionScheme` springs for component state changes (FAB menu, toggles, list item expand).
- Item add/remove: `Modifier.animateItem()` in `LazyColumn`.
- Speed numbers: animate with `animateFloatAsState` and spring, not per-byte recomposition.
- Respect "Remove animations" accessibility setting.

## Performance rules for the UI

- Progress state sampled at 4 Hz max. One `StateFlow<List<DownloadUi>>` with stable `@Immutable` items.
- Keys on `LazyColumn` items = download id.
- No recomposition of the whole list on one item's progress: read progress inside the item via a
  per-id `State`.
- Baseline profile for cold start and list scroll.

## Accessibility

- Touch targets 48 dp. Content descriptions for icon buttons. Progress exposed through semantics
  (`progressBarRangeInfo`).
- Contrast: support the 3 system contrast levels.
- Speed and ETA text readable by TalkBack ("24 megabytes per second, 2 minutes left").
