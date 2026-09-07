# Iridescent Wallpaper v0.10 — debounced One UI page authority

v0.10 fixes two behaviors reported in v0.9:

1. A tiny, slow partial drag must not change the logical page just because One UI emits one early accessibility `toIndex` event.
2. The first frame after finger release must not visibly pause while waiting for accessibility confirmation.

## What changed

- Glass still follows the finger 1:1 while touching.
- On release, the renderer immediately chooses a **visual-only** provisional target so animation keeps moving; it does **not** save that page.
- Short + slow releases predict a return to the current page.
- Strong releases may visually continue toward the neighbour, but One UI accessibility remains the only authority that can persist a new page while the service is enabled.
- Accessibility page-index events are now debounced. One early `toIndex` packet is only a candidate.
- A page change is committed only when supported by settled continuous scroll data, or by a strong release plus a stable/aged candidate.
- A weak release that receives a contradictory next-page index is rejected and eased back.
- Page-change spring constants were retuned to reduce the small hitch/jolt when the target changes.
- Debug overlay shows `cand=<page> x<hits>` so false early page events are visible.

Grid defaults remain the user's calibrated 4×6 values from v0.9:
X 164 px, Y 256 px, cell 175×176 px, gap 75×117 px, glass 36% on a 709×1536 reference screen.
