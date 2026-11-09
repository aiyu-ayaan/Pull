# Pull logo: Comet

A ball diving down-right with three speed trails behind it. It stands for speed: Pull's job is to finish
downloads fast.

## Files

| File | Use |
|---|---|
| `comet.py` | Geometry source. Prints the master SVG: `python3 comet.py "#386A20"`. |
| `pull-symbol.svg` | Master, brand green, for light backgrounds. |
| `pull-symbol-dark-bg.svg` | Light green for dark backgrounds. |
| `pull-symbol-black.svg` / `pull-symbol-white.svg` | One-colour versions. |
| `play-store-icon.svg` / `play-store-icon-512.png` | Play Store listing icon (full-bleed square; Play applies the mask). |
| `pull-symbol-1024.png` | Raster master. |

The Android resources use the same geometry inside a group rotated 45 degrees:

- `app/src/main/res/drawable/ic_launcher_foreground.xml` (adaptive and themed monochrome icon)
- `app/src/main/res/drawable/ic_stat_pull.xml` (notifications)
- `app/src/main/res/drawable/splash_static.xml` (splash before Android 12)
- `app/src/main/res/drawable/splash_mark.xml` (animated splash, Android 12+)

If you change the geometry, change `comet.py` and the four drawables together.

## Colour

| Role | Light | Dark |
|---|---|---|
| Mark | `#386A20` | `#9DD67D` |
| Icon background | `#386A20` | `#386A20` |
| Icon foreground | `#B8F397` | `#B8F397` |

These come from the app's tonal scheme (seed `#4C8C2B`).

## Splash animation (960 ms)

| Time | Element | Motion |
|---|---|---|
| 0–200 ms | — | Waits out the launch transition. |
| 200–680 ms | Head | Shoots in along the diagonal from 40 % size, overshoots, settles. Fades in over the first 200 ms. |
| 420–820 ms | Middle trail | Stretches out behind the head to 130 %, springs back to 100 %. |
| 490–890 ms | Upper trail | Same, 70 ms later. |
| 560–960 ms | Lower trail | Same, 140 ms later. |

`windowSplashScreenAnimationDuration` in `values-v31/themes.xml` and `SPLASH_MS` in `MainActivity` must match the
960 ms total.

## Rules

- Clear space: one head radius (44 units on the 256 canvas) on every side.
- Minimum size: 16 px. Below 24 px the outer trails get thin, so prefer 24 px and up.
- Always keep the 45-degree angle. Never mirror or recolour the trails separately from the head.
- No outlines, shadows or gradients.

Trademark clearance has not been checked. Run a search before registering the mark.
