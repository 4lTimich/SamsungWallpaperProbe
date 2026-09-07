# Iridescent Wallpaper v0.14

Targeted motion-only revision. Grid/editor data and cell assignments are intentionally untouched.

## What changed

- Restored the slower Samsung/Launcher3-style quintic return timing used before v0.13. Slow cancelled drags can take up to ~750 ms instead of being forced into the 190–390 ms window.
- Startup tracking now uses the v0.12 90 ms touch-slop catch-up, but with **all velocity/input-lag lead removed**. This is intended to remove the initial few-pixel overshoot while reaching 1:1 tracking quickly.
- No grid geometry, editor, app-cell assignment, glass rendering, or page-continuity code was changed in this revision.

## What to test

1. Start a very slow horizontal drag and watch the first 100–150 ms: glass should not jump ahead.
2. Pull only a short distance and release slowly: glass should return with the slower One UI-like easing rather than snapping back.
3. Continue a normal drag after the first ~100 ms: glass should settle into 1:1 motion with the icon.
