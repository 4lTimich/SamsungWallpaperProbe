# Iridescent Wallpaper v0.8 — Accessibility authority + delta tracking

This build addresses four v0.7 issues.

## 1. No virtual page commit while accessibility is enabled
When `One UI Offset Probe` is connected, raw touch is only a temporary visual preview. It can no longer decide that a page changed. The page is committed only by:
- a plausible absolute One UI `scrollX/maxScrollX`, or
- integrated One UI `scrollDeltaX` settling very close to a page, or
- an authoritative One UI page-index scroll event.

If the user drags forward, returns the finger, and releases without One UI confirming a new page, the wallpaper returns to the already-known page instead of inventing a transition.

## 2. Better continuous movement
v0.8 now uses `AccessibilityEvent.getScrollDeltaX()` when absolute `scrollX` is unavailable. This is the launcher's own scrolling delta, so reversals and post-release movement can track much closer than the old finger-distance heuristic.

If One UI exposes neither useful absolute scroll nor useful deltas, v0.8 enters a safe hybrid mode: touch previews the drag, then glass briefly fades during the uncertain post-release animation and reappears on the page confirmed by One UI. This avoids visibly sliding glass to the wrong place.

## 3. Page-specific grid editing made explicit
The grid editor opens on the last page confirmed by One UI when available. It now has a prominent `РЕДАКТИРУЕТСЯ СТРАНИЦА` control and a button `ВЗЯТЬ ТЕКУЩУЮ СТРАНИЦУ ИЗ ONE UI`.
Assignments remain stored separately per page.

## 4. Debug overlay orientation fixed
The OpenGL debug texture no longer applies the extra vertical flip from v0.7.

## Debug sources
- `A11Y REAL` — absolute One UI scroll position is available.
- `A11Y DELTA` — One UI scroll deltas are being integrated.
- `A11Y PAGE` — final page came from One UI.
- `A11Y WAIT` — waiting for One UI; no virtual guess is allowed.
- `A11Y HOLD` — no page change was confirmed; return to the known page.
- `VIRTUAL TOUCH` — accessibility service is off, so legacy fallback is active.

## Build
The GitHub Actions artifact is `IridescentWallpaper-v0.8-APK`.
