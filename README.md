# Samsung Wallpaper Probe v0.1

Tiny Android live-wallpaper test for Samsung One UI.

## What it tests

Whether One UI Home sends horizontal wallpaper offsets while swiping between home-screen pages.

If it works, the wallpaper will:
- move a large white circle;
- move an iridescent background field;
- change the `xOffset` number;
- briefly show `ONE UI SENDS MOVEMENT ✓`.

## Build

The included GitHub Actions workflow builds a debug APK automatically after the files are pushed to GitHub.
The produced artifact is named `SamsungWallpaperProbe-v0.1-APK`.
