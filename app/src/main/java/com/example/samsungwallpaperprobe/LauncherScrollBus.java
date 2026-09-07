package com.example.samsungwallpaperprobe;

/**
 * v0.20 bridge between the One UI accessibility tracker and the wallpaper renderer.
 *
 * Two real launcher icons are tracked at the same time:
 *  - left/bottom anchor: survives longer while moving toward the previous page;
 *  - right/top anchor: survives longer while moving toward the next page.
 *
 * The accessibility service publishes one unified real page position selected from the fresher
 * directional anchor. Touch remains only a short temporal bridge between real bounds updates.
 */
public final class LauncherScrollBus {
    private LauncherScrollBus() {}

    public static volatile boolean serviceConnected = false;
    public static volatile boolean wallpaperVisible = false;
    public static volatile boolean homeActive = false;

    public static volatile long homeEpoch = 0L;
    public static volatile long rescanGeneration = 0L;

    // Unified continuous real page position. 0 = page 1, 1 = page 2, etc.
    public static volatile boolean positionValid = false;
    public static volatile float position = 0f;
    public static volatile float velocityPagesPerSec = 0f;
    public static volatile long positionUptimeMs = 0L;
    public static volatile long positionSequence = 0L;

    public static volatile float trackerPollHz = 0f;
    public static volatile float boundsChangeHz = 0f;
    public static volatile long boundsSamples = 0L;
    public static volatile long boundsChanges = 0L;

    public static volatile float previousChangedPosition = 0f;
    public static volatile long previousChangedUptimeMs = 0L;
    public static volatile float lastChangedPosition = 0f;
    public static volatile long lastChangedUptimeMs = 0L;

    // Touch bridge. It never decides the page or simulates a fling.
    public static volatile boolean touchActive = false;
    public static volatile float touchX = 0f;
    public static volatile long touchUptimeMs = 0L;
    public static volatile long touchSequence = 0L;
    public static volatile float touchXAtLastBoundsChange = 0f;
    public static volatile boolean touchWasActiveAtLastBoundsChange = false;
    public static volatile float touchVelocityX = 0f; // px/sec; finger left is negative

    // Legacy/public unified anchor metadata used by the renderer/debug overlay.
    public static volatile String anchorLabel = "";
    public static volatile int anchorLeft = 0;
    public static volatile int anchorTop = 0;
    public static volatile int anchorRight = 0;
    public static volatile int anchorBottom = 0;
    public static volatile int anchorCenterX = 0;
    public static volatile int anchorCenterY = 0;
    public static volatile float anchorWorldX = 0f;
    public static volatile long anchorReacquires = 0L;

    // Dual-anchor diagnostics.
    public static volatile boolean anchorLeftBottomValid = false;
    public static volatile String anchorLeftBottomLabel = "";
    public static volatile int anchorLeftBottomX = 0;
    public static volatile int anchorLeftBottomY = 0;
    public static volatile float anchorLeftBottomPosition = 0f;
    public static volatile long anchorLeftBottomChangedMs = 0L;

    public static volatile boolean anchorRightTopValid = false;
    public static volatile String anchorRightTopLabel = "";
    public static volatile int anchorRightTopX = 0;
    public static volatile int anchorRightTopY = 0;
    public static volatile float anchorRightTopPosition = 0f;
    public static volatile long anchorRightTopChangedMs = 0L;

    public static volatile String activeAnchorRole = "NONE";
    public static volatile float anchorDisagreementPx = 0f;

    // Cached launcher icon layout. Immutable array replacement = lock-free reader side.
    public static volatile IconBox[] iconSnapshot = new IconBox[0];
    public static volatile long iconSnapshotVersion = 0L;
    public static volatile long layoutScans = 0L;
    public static volatile long lastLayoutScanMs = 0L;

    public static volatile int authoritativePage = -1;
    public static volatile long authoritativePageUptimeMs = 0L;

    public static volatile String trackerState = "WAITING";

    public static final class IconBox {
        public final float worldCenterX;
        public final float centerY;
        public final float width;
        public final float height;
        public final String label;
        public final boolean movesWithPages;
        public final int pageIndex;

        public IconBox(float worldCenterX, float centerY, float width, float height, String label, boolean movesWithPages, int pageIndex) {
            this.worldCenterX = worldCenterX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.label = label == null ? "" : label;
            this.movesWithPages = movesWithPages;
            this.pageIndex = pageIndex;
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
        touchVelocityX = 0f;
    }

    public static void requestRescan() {
        rescanGeneration++;
    }

    public static void clearTracking(String state) {
        positionValid = false;
        trackerState = state;
        anchorLabel = "";
        anchorLeftBottomValid = false;
        anchorRightTopValid = false;
        activeAnchorRole = "NONE";
    }
}
