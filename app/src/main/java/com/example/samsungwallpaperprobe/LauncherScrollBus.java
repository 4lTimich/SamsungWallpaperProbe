package com.example.samsungwallpaperprobe;

/**
 * v0.18 bridge between the One UI accessibility tracker and the wallpaper renderer.
 *
 * The tracker caches a snapshot of ALL launcher icon-like nodes when Home becomes visible.
 * During motion it refreshes only one real AccessibilityNodeInfo (the anchor).  The wallpaper
 * can therefore move the cached layout as a rigid body without polling every app at frame rate.
 */
public final class LauncherScrollBus {
    private LauncherScrollBus() {}

    public static volatile boolean serviceConnected = false;
    public static volatile boolean wallpaperVisible = false;
    public static volatile boolean homeActive = false;

    // Home-entry/layout rescan handshake.
    public static volatile long homeEpoch = 0L;
    public static volatile long rescanGeneration = 0L;

    // Continuous real page position from one anchor icon. 0 = page 1, 1 = page 2, etc.
    public static volatile boolean positionValid = false;
    public static volatile float position = 0f;
    public static volatile float velocityPagesPerSec = 0f;
    public static volatile long positionUptimeMs = 0L;
    public static volatile long positionSequence = 0L;

    // More useful than polling frequency: how often Samsung actually changes bounds.
    public static volatile float trackerPollHz = 0f;
    public static volatile float boundsChangeHz = 0f;
    public static volatile long boundsSamples = 0L;
    public static volatile long boundsChanges = 0L;

    // Last two genuinely changed position samples. Used by the renderer for post-touch prediction.
    public static volatile float previousChangedPosition = 0f;
    public static volatile long previousChangedUptimeMs = 0L;
    public static volatile float lastChangedPosition = 0f;
    public static volatile long lastChangedUptimeMs = 0L;

    // High-rate touch bridge. Touch NEVER decides a page; it only fills time between real bounds
    // samples while the finger is down.
    public static volatile boolean touchActive = false;
    public static volatile float touchX = 0f;
    public static volatile long touchUptimeMs = 0L;
    public static volatile long touchSequence = 0L;
    public static volatile float touchXAtLastBoundsChange = 0f;
    public static volatile boolean touchWasActiveAtLastBoundsChange = false;

    // Cached anchor metadata.
    public static volatile String anchorLabel = "";
    public static volatile int anchorLeft = 0;
    public static volatile int anchorTop = 0;
    public static volatile int anchorRight = 0;
    public static volatile int anchorBottom = 0;
    public static volatile int anchorCenterX = 0;
    public static volatile int anchorCenterY = 0;
    public static volatile float anchorWorldX = 0f;
    public static volatile long anchorReacquires = 0L;

    // Cached launcher icon layout. Immutable array replacement = lock-free reader side.
    public static volatile IconBox[] iconSnapshot = new IconBox[0];
    public static volatile long iconSnapshotVersion = 0L;
    public static volatile long layoutScans = 0L;
    public static volatile long lastLayoutScanMs = 0L;

    // Stable page inferred only when the real anchor is stationary near an integer page.
    public static volatile int authoritativePage = -1;
    public static volatile long authoritativePageUptimeMs = 0L;

    // Human-readable state for diagnostics.
    public static volatile String trackerState = "WAITING";

    public static final class IconBox {
        public final float worldCenterX;
        public final float centerY;
        public final float width;
        public final float height;
        public final String label;
        public final boolean movesWithPages;

        public IconBox(float worldCenterX, float centerY, float width, float height, String label, boolean movesWithPages) {
            this.worldCenterX = worldCenterX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.label = label == null ? "" : label;
            this.movesWithPages = movesWithPages;
        }
    }

    public static void onWallpaperVisible() {
        wallpaperVisible = true;
        homeEpoch++;
        rescanGeneration++;
    }

    public static void onWallpaperHidden() {
        wallpaperVisible = false;
        touchActive = false;
    }

    public static void requestRescan() {
        rescanGeneration++;
    }

    public static void clearTracking(String state) {
        positionValid = false;
        trackerState = state;
        anchorLabel = "";
    }
}
