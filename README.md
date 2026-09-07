# Iridescent Wallpaper v0.18 — cached icon layout + bounds resampling

This build keeps the old grid/editor but the bounds tracker no longer depends on it.

## What changed

- On each return to One UI Home, Accessibility scans the launcher tree once and caches all app-like icon bounds it can see.
- During motion only ONE cached AccessibilityNodeInfo is refreshed as the anchor.
- All cached icons are stored in world coordinates relative to the real page position.
- A white rounded outline is drawn around every cached icon even when normal debug is OFF. This can be toggled independently in the app.
- The debug panel now reports both POLL Hz and CHANGE Hz. POLL is how often we call refresh(); CHANGE is how often Samsung actually publishes a different bounds coordinate.
- Wallpaper touch events are re-enabled only as a temporal bridge between real bounds samples while the finger is down. Touch never chooses a page and no virtual fling/spring model was reintroduced.
- After finger release, the renderer makes only a very short, bounded velocity extrapolation (<=42 ms, <=0.085 page) between real bounds samples to reduce 15–30 Hz stair-stepping.
- Existing glass grid, grid editor, app assignments and geometry are preserved and not migrated/reset.

## Why

v0.17 could poll the anchor around 120 Hz while the actual bounds coordinate changed only around ~20 Hz on some One UI builds. Rendering 120 GPU frames from a 20 Hz position source still looks like 20 FPS. v0.18 separates these rates in diagnostics and resamples the real bounds source instead of increasing tree scans.

## Test

1. Re-enable **One UI Bounds Engine** in Accessibility after installing the APK.
2. Return to Home. White outlines should appear around detected launcher icons even with debug OFF.
3. Swipe slowly, reverse direction, and complete a page transition.
4. Turn debug ON and compare `POLL` vs `CHANGE`.
5. Note whether the outlines follow the real icons smoothly under the finger and during Samsung's post-release animation.
