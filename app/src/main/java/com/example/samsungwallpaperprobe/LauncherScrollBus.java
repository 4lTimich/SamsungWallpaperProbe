package com.example.samsungwallpaperprobe;

/**
 * v0.17 bridge between the One UI accessibility tracker and the wallpaper renderer.
 *
 * There is deliberately no virtual-scroll model here. The accessibility service tracks
 * one real launcher icon and publishes the continuous page position derived from that
 * icon's getBoundsInScreen() coordinate. The wallpaper only consumes that position.
 */
public final class LauncherScrollBus {
    private LauncherScrollBus() {}

    public static volatile boolean serviceConnected = false;
    public static volatile boolean wallpaperVisible = false;
    public static volatile boolean homeActive = false;

    // Home-entry/layout rescan handshake. Wallpaper increments this when it becomes visible.
    public static volatile long homeEpoch = 0L;
    public static volatile long rescanGeneration = 0L;

    // Continuous real position from bounds tracking. 0 = page 1, 1 = page 2, etc.
    public static volatile boolean positionValid = false;
    public static volatile float position = 0f;
    public static volatile float velocityPagesPerSec = 0f;
    public static volatile long positionUptimeMs = 0L;
    public static volatile long positionSequence = 0L;

    // Cached anchor metadata.
    public static volatile String anchorPackage = "";
    public static volatile String anchorLabel = "";
    public static volatile int anchorPage = -1;
    public static volatile int anchorRow = -1;
    public static volatile int anchorCol = -1;
    public static volatile int anchorLeft = 0;
    public static volatile int anchorTop = 0;
    public static volatile int anchorRight = 0;
    public static volatile int anchorBottom = 0;
    public static volatile int anchorCenterX = 0;
    public static volatile int anchorCenterY = 0;
    public static volatile float anchorRestX = 0f;
    public static volatile float anchorRestY = 0f;
    public static volatile long boundsSamples = 0L;
    public static volatile long boundsChanges = 0L;
    public static volatile long anchorReacquires = 0L;

    // Snapshot/cache diagnostics. A full tree scan is intentionally rare.
    public static volatile int configuredSlots = 0;
    public static volatile int visibleSlotsInSnapshot = 0;
    public static volatile long layoutScans = 0L;
    public static volatile long lastLayoutScanMs = 0L;
    public static volatile float layoutBiasX = 0f;
    public static volatile float layoutBiasY = 0f;

    // Effective high-rate sample frequency.
    public static volatile float trackerHz = 0f;

    // Stable page inferred from whichever assigned icon is currently at rest.
    public static volatile int authoritativePage = -1;
    public static volatile long authoritativePageUptimeMs = 0L;

    // Human-readable state for debug UI.
    public static volatile String trackerState = "WAITING";

    public static void onWallpaperVisible() {
        wallpaperVisible = true;
        homeEpoch++;
        rescanGeneration++;
    }

    public static void onWallpaperHidden() {
        wallpaperVisible = false;
    }

    public static void requestRescan() {
        rescanGeneration++;
    }

    public static void clearTracking(String state) {
        positionValid = false;
        trackerState = state;
        anchorPackage = "";
        anchorLabel = "";
        anchorPage = -1;
        anchorRow = -1;
        anchorCol = -1;
    }
}
