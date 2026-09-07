# Iridescent Wallpaper Lab v0.16 — boundsInScreen probe

This is a diagnostic branch built from v0.15. Grid geometry, app-to-cell assignments and existing glass motion are intentionally left unchanged.

## Experiment
The accessibility service now has `canRetrieveWindowContent=true` and, only when the active root belongs to `com.sec.android.app.launcher`, searches for one chosen launcher icon (default: `Pinterest`). It reads that node's `AccessibilityNodeInfo.getBoundsInScreen()` about 42 times/second.

The app still has no INTERNET permission. If the active root is not One UI Home, the service does not traverse it.

## Test
1. Install v0.16 over the previous build.
2. IMPORTANT: toggle the accessibility service OFF and ON again because its capability changed from event-only to window-content access. The service is now named `One UI Bounds Probe`.
3. In the app leave the marker label as `Pinterest` (or enter another visible app label) and tap `СОХРАНИТЬ ИКОНКУ-МАРКЕР`.
4. Turn debug ON and return to the home page containing that icon.
5. In the debug panel watch `BOUNDS ... X=` and `changes=` while slowly dragging the page left/right without lifting your finger.
6. A high-opacity debug glass pad is drawn at the exact reported node bounds while debug is enabled.

### Success
If `X` changes continuously and the debug pad stays locked to the real icon, the next version can derive page offset directly from the icon bounds and delete most of the virtual-offset physics.

### Failure
If `X` stays constant, changes only after the page settles, or the node disappears during the swipe, Samsung is not publishing useful live bounds. Then the next test should record timestamped touch/accessibility scroll traces instead.
