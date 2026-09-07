# Iridescent Wallpaper v0.15

Targeted motion fix. Grid/editor code is intentionally unchanged from the stable v0.12/v0.8 branch.

Changes:
- reverted drag/release base to the known-good v0.12 branch;
- glass startup uses a small configurable pixel phase correction (default 4 px), no velocity lead and no catch-up acceleration;
- after that tiny deadzone, glass follows touch exactly 1:1 in both directions;
- modern One UI release prediction is used only for visual continuation; accessibility still verifies the final page;
- all non-empty pages are rendered during transitions so glass cannot vanish at page boundaries;
- slower v0.12-style quintic return timing is retained;
- debug remains available.

If the glass is still ahead at swipe start, raise “Компенсация старта стекла” by 1 px. If it lags, lower it by 1 px.
