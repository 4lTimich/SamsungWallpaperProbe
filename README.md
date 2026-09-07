# Iridescent Wallpaper v0.12

Focused regression-fix build.

## Grid
The grid editor/view code is restored to the v0.8 implementation. Existing v0.11 installs have incorrect geometry persisted, so v0.12 performs one one-time geometry restore to the exact v0.8 defaults. App-to-cell assignments and the editor screenshot are preserved. After that restore, the grid is not auto-migrated again.

## Motion fixes
1. Glass no longer vanishes at page boundaries/overshoot. The renderer keeps one extra neighboring page alive on each side instead of drawing only floor/ceil pages.
2. Removed the 3–4 px startup teleport. Crossing touch slop begins from a continuous zero displacement; the skipped slop distance is blended back over ~90 ms. The fixed +8 ms latency lead from v0.11 is removed; only measured event lag is compensated, and that compensation is ramped in.

The v0.11 Samsung/Launcher3-style page snap and A11Y final-page verification remain otherwise unchanged. Debug mode remains available.
