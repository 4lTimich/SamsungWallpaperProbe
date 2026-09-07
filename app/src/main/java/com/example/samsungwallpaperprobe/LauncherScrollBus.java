package com.example.samsungwallpaperprobe;

/**
 * v0.21 bridge: immutable page-local layout cache + real bounds position.
 *
 * Important invariant: cached icon coordinates are written ONLY for a settled page.
 * A swipe never mutates page ownership or resting X/Y. Any tracked icon can therefore
 * reconstruct one common absolute page position:
 *
 *   position = icon.pageIndex + (icon.restCenterX - currentCenterX) / screenWidth
 *
 * This removes the old worldX/basisPos rebasing that could copy outgoing-page icons onto
 * the incoming page during a swipe.
 */
public final class LauncherScrollBus {
    private LauncherScrollBus() {}

    public static volatile boolean serviceConnected = false;
    public static volatile boolean wallpaperVisible = false;
    public static volatile boolean homeActive = false;

    public static volatile long homeEpoch = 0L;
    public static volatile long rescanGeneration = 0L;

    // Continuous real page position. 0 = page 1, 1 = page 2, etc.
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

    // Kept only for diagnostics / the future swipe-recorder branch. v0.21 position does not use it.
    public static volatile boolean touchActive = false;
    public static volatile float touchX = 0f;
    public static volatile long touchUptimeMs = 0L;
    public static volatile long touchSequence = 0L;
    public static volatile float touchVelocityX = 0f;

    // Active real anchor metadata for debug marker.
    public static volatile String anchorLabel = "";
    public static volatile int anchorLeft = 0;
    public static volatile int anchorTop = 0;
    public static volatile int anchorRight = 0;
    public static volatile int anchorBottom = 0;
    public static volatile long anchorReacquires = 0L;

    // Two-anchor diagnostics.
    public static volatile boolean anchorLeftBottomValid = false;
    public static volatile String anchorLeftBottomLabel = "";
    public static volatile int anchorLeftBottomX = 0;
    public static volatile float anchorLeftBottomPosition = 0f;
    public static volatile boolean anchorRightTopValid = false;
    public static volatile String anchorRightTopLabel = "";
    public static volatile int anchorRightTopX = 0;
    public static volatile float anchorRightTopPosition = 0f;
    public static volatile float anchorDisagreementPx = 0f;
    public static volatile String activeAnchorRole = "NONE";

    // Immutable snapshot replacement for lock-free renderer reads.
    public static volatile IconBox[] iconSnapshot = new IconBox[0];
    public static volatile long iconSnapshotVersion = 0L;
    public static volatile long layoutScans = 0L;
    public static volatile long lastLayoutScanMs = 0L;
    public static volatile int cachedPageCount = 0;

    public static volatile int authoritativePage = -1;
    public static volatile long authoritativePageUptimeMs = 0L;
    public static volatile String trackerState = "WAITING";

    public static final class IconBox {
        // For moving icons this is the center X while THEIR OWN page is fully settled.
        // For dock icons this is just the normal screen-space center X.
        public final float localCenterX;
        public final float centerY;
        public final float width;
        public final float height;
        public final String label;
        public final boolean movesWithPages;
        public final int pageIndex; // -1 for dock

        public IconBox(float localCenterX, float centerY, float width, float height,
                       String label, boolean movesWithPages, int pageIndex) {
            this.localCenterX = localCenterX;
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
