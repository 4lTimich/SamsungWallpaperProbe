# Iridescent Wallpaper v0.9 — Smooth Hybrid

v0.9 fixes the main regression in v0.8: accessibility scroll events from One UI are sparse and were being copied too directly into the visible glass position, which made motion look stepped/jagged even when GPU FPS was high.

## Motion model

When the accessibility service is ON:

- While the finger is down, glass follows raw wallpaper touch **1:1**. This is the smoothest signal and immediately follows reversals.
- Accessibility scrollX/scrollDeltaX is still observed, but it does **not** overwrite the visible glass while dragging.
- After release, A11Y data changes only a **target position**.
- The visible glass interpolates toward that target every GPU frame with an over-damped spring.
- A confirmed One UI page also changes only the target; no snap is performed.
- If One UI reports no page change, the target becomes the already-known page and glass eases back smoothly.
- Glass is no longer faded while waiting for a One UI decision.

The accessibility service remains authoritative for committing the logical page. The old virtual page heuristic is used only when the service is disabled.

## Default grid preset

The default/reset grid is now copied from the user's calibrated 709×1536 screenshot:

- 4 columns × 6 rows
- Grid X: 164 px
- Grid Y: 256 px
- Cell width: 175 px
- Cell height: 176 px
- Gap X: 75 px
- Gap Y: 117 px
- Glass opacity: 36%

The reset button in the grid editor returns to these exact normalized values.

## Debug

Debug remains available. The second line now shows current → target positions for both background and glass, making it easier to see whether visible jitter comes from input samples or the smoother.
