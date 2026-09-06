# Samsung Wallpaper Probe v0.3 — Virtual Page Engine

This build no longer depends on Samsung/One UI wallpaper offsets.
It observes the touch gesture, predicts whether One UI changed page, keeps its own page index, and stores that page in SharedPreferences.

## Persistence rule
The logical page is saved immediately after a committed swipe and again whenever the wallpaper becomes invisible/destroyed. Opening an app, locking/unlocking the phone, or Android recreating the wallpaper process should therefore restore the same virtual page instead of returning to page 1.

## Before testing
Open the app and set:
- number of normal One UI home pages (do not count Google Discover / Samsung Free on the far left)
- the page you were on immediately before opening the app

Tap **SAVE THIS POSITION**, then return home.

## What to test
1. Swipe to another page and wait for the spring to settle.
2. Check `SAVED PAGE`.
3. Open any app, then return home.
4. Lock and unlock the phone.
5. `SAVED PAGE`, `VIRTUAL`, and the large white marker should remain on the same page.

Known limitation: because One UI does not expose its real page offset, the engine predicts page changes from swipe distance/velocity. Edge swipes are handled using the configured page count, but unusual launcher gestures can still cause desynchronization.
