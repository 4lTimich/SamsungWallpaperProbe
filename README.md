# Iridescent Wallpaper v0.17 — Bounds Engine 120

This version replaces the old virtual page-offset stack with direct icon-bounds tracking.

## Architecture

- `WallpaperService` no longer receives touch events.
- No touch-slop emulation, predicted fling, page spring, quintic snap, A11Y page debounce, or virtual offset is used for animation.
- When One UI Home becomes visible, the accessibility service performs one tree scan and matches only apps assigned in the glass-grid editor.
- It caches their relative grid layout and chooses one visible configured app as an anchor.
- During motion, only that one cached `AccessibilityNodeInfo` is refreshed at ~125 Hz.
- Continuous page position is derived from the anchor's real `getBoundsInScreen()` X coordinate:

  `position = anchorPage - (currentX - restingX) / screenWidth`

- When the anchor leaves the page, a rare new tree scan chooses an icon on the incoming page by continuity.
- The OpenGL wallpaper loop targets 120 FPS. Actual measured FPS can still be capped by the device / One UI wallpaper surface refresh rate.
- All configured glass pages are rendered continuously, so pads do not disappear just because a logical page changed.

## Cached-relative-layout model

The user's suggestion is implemented: the app does **not** query every icon at frame rate. On each Home entry it snapshots the configured visible apps, measures a global X/Y bias between the editor grid and One UI's real icon bounds, then tracks one anchor only. The snapshot is refreshed on Home entry, grid changes, or anchor reacquisition.

## Grid

GridEditorActivity and existing geometry/preferences are intentionally unchanged from the v0.8-compatible branch. Existing app-to-cell assignments are preserved.

## Test

1. Install over the previous build.
2. In Android accessibility settings, make sure **One UI Bounds Engine** is enabled. If Android disabled it after the service metadata changed, toggle it off/on once.
3. Turn debug on.
4. Return to Home and wait a moment for `BOUNDS REAL`.
5. Test slow drag, reverse drag without release, incomplete swipe spring-back, and a full page transition.
6. Debug should show renderer FPS, tracker Hz, anchor app/page, current X/rest X/dX, snapshot size, scan count and reacquisition count.
