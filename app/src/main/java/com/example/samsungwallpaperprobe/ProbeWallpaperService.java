package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * v0.9: GPU wallpaper renderer with smooth hybrid One UI tracking.
 *
 * While the finger is down, glass follows raw touch 1:1 because that is the smoothest
 * signal Android gives a live wallpaper. Accessibility data is used as an authority
 * and post-release target, but sparse A11Y samples are never copied straight to the
 * visible glass position; they are interpolated by a 60 fps critically-damped spring.
 */
public class ProbeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new GpuEngine();
    }

    private final class GpuEngine extends Engine {
        private final Object stateLock = new Object();
        private SharedPreferences prefs;
        private RenderThread renderThread;

        private volatile boolean visible = false;
        private volatile int surfaceW = 1;
        private volatile int surfaceH = 1;

        // Persistent page state.
        private int pageCount = 4;
        private int currentPage = 0;
        private int targetPage = 0;
        private int lastConfigGeneration = -1;
        private float swipeSensitivity = 1.80f;
        private boolean showDebug = false;

        // Visual positions and their smooth targets.
        private float virtualPosition = 0f;
        private float glassPosition = 0f;
        private float virtualTargetPosition = 0f;
        private float glassTargetPosition = 0f;
        private float visualVelocity = 0f;
        private float glassVelocity = 0f;
        private float motionEnergy = 0f;

        // Touch fallback state.
        private boolean dragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float touchX = 0f;
        private float touchY = 0f;
        private float touchDx = 0f;
        private float touchDy = 0f;
        private float touchVelocityX = 0f;
        private float prevMoveX = 0f;
        private long prevMoveMs = 0L;
        private int gestureBasePage = 0;
        private String lastDecision = "READY";

        // Accessibility real-offset state.
        private long lastBusSequence = -1L;
        private boolean realOffsetLocked = false;
        private float realPosition = 0f;
        private long lastRealEventMs = 0L;
        private boolean realSettledForEvent = true;
        private String offsetSource = "VIRTUAL TOUCH";
        private int a11yScrollX = -1;
        private int a11yMaxScrollX = -1;
        private int a11yDeltaX = 0;
        private int a11yFrom = -1;
        private int a11yTo = -1;
        private int a11yCount = -1;
        private String a11yClass = "";
        private String a11ySummary = "";

        // v0.8 authoritative accessibility state. When the service is connected,
        // the touch heuristic is NEVER allowed to commit a page change. Touch is
        // only a temporary preview until One UI confirms the final page.
        private boolean awaitingA11yDecision = false;
        private long a11yReleaseMs = 0L;
        private long lastA11yMotionMs = 0L;
        private boolean a11yContinuousThisGesture = false;
        private int a11yDeltaSign = 0;
        private float glassVisibility = 1f;

        // Grid cache.
        private int gridCols = Prefs.DEFAULT_COLS;
        private int gridRows = Prefs.DEFAULT_ROWS;
        private float gridX0 = Prefs.DEFAULT_X0;
        private float gridY0 = Prefs.DEFAULT_Y0;
        private float cellWidth = Prefs.DEFAULT_CELL_WIDTH;
        private float cellHeight = Prefs.DEFAULT_CELL_HEIGHT;
        private float gapX = Prefs.DEFAULT_GAP_X;
        private float gapY = Prefs.DEFAULT_GAP_Y;
        private float glassOpacity = Prefs.DEFAULT_GLASS_OPACITY;
        private ArrayList<Cell>[] cellsByPage;
        private volatile boolean gridDirty = true;

        private final class Cell {
            final int row, col;
            Cell(int row, int col) { this.row = row; this.col = col; }
        }

        private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
                (sharedPreferences, key) -> {
                    if (key == null) return;
                    if (key.startsWith("cell_")
                            || Prefs.KEY_GRID_COLS.equals(key)
                            || Prefs.KEY_GRID_ROWS.equals(key)
                            || Prefs.KEY_GRID_X0.equals(key)
                            || Prefs.KEY_GRID_Y0.equals(key)
                            || Prefs.KEY_CELL_WIDTH.equals(key)
                            || Prefs.KEY_CELL_HEIGHT.equals(key)
                            || Prefs.KEY_GAP_X.equals(key)
                            || Prefs.KEY_GAP_Y.equals(key)
                            || Prefs.KEY_GLASS_OPACITY.equals(key)
                            || Prefs.KEY_PAGE_COUNT.equals(key)) {
                        gridDirty = true;
                    }
                    if (Prefs.KEY_SHOW_DEBUG.equals(key)) {
                        showDebug = sharedPreferences.getBoolean(Prefs.KEY_SHOW_DEBUG, showDebug);
                    }
                    if (Prefs.KEY_SWIPE_SENSITIVITY.equals(key)) {
                        swipeSensitivity = clamp(sharedPreferences.getFloat(
                                Prefs.KEY_SWIPE_SENSITIVITY, swipeSensitivity), 0.70f, 2.60f);
                    }
                };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(false);
            prefs = getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
            Prefs.ensureV06GridDefaults(prefs,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            loadPersistentState(true);
        }

        private void loadPersistentState(boolean forcePosition) {
            synchronized (stateLock) {
                pageCount = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
                swipeSensitivity = clamp(prefs.getFloat(Prefs.KEY_SWIPE_SENSITIVITY, 1.80f), 0.70f, 2.60f);
                showDebug = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);
                int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, 0);
                int saved = clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, pageCount - 1);
                if (forcePosition || generation != lastConfigGeneration) {
                    currentPage = saved;
                    targetPage = saved;
                    virtualPosition = saved;
                    glassPosition = saved;
                    virtualTargetPosition = saved;
                    glassTargetPosition = saved;
                    realPosition = saved;
                    visualVelocity = 0f;
                    glassVelocity = 0f;
                    dragging = false;
                }
                lastConfigGeneration = generation;
                gridDirty = true;
            }
        }

        private void saveStablePage() {
            if (prefs == null || isPreview()) return;
            prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, currentPage).apply();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (renderThread == null) {
                renderThread = new RenderThread(holder);
                renderThread.start();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceW = Math.max(1, width);
            surfaceH = Math.max(1, height);
            gridDirty = true;
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, lastConfigGeneration);
                if (generation != lastConfigGeneration) loadPersistentState(false);
                if (renderThread != null) renderThread.wakeUp();
            } else {
                saveStablePage();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            saveStablePage();
            if (renderThread != null) {
                renderThread.requestStop();
                try { renderThread.join(1500L); } catch (InterruptedException ignored) {}
                renderThread = null;
            }
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            saveStablePage();
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            if (renderThread != null) {
                renderThread.requestStop();
                renderThread = null;
            }
            super.onDestroy();
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            final long now = SystemClock.uptimeMillis();
            synchronized (stateLock) {
                touchX = event.getX();
                touchY = event.getY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = true;
                        gestureBasePage = currentPage;
                        targetPage = currentPage;
                        downX = touchX;
                        downY = touchY;
                        touchDx = 0f;
                        touchDy = 0f;
                        touchVelocityX = 0f;
                        prevMoveX = touchX;
                        prevMoveMs = now;
                        visualVelocity = 0f;
                        glassVelocity = 0f;
                        awaitingA11yDecision = false;
                        a11yContinuousThisGesture = false;
                        a11yDeltaSign = 0;
                        if (LauncherScrollBus.serviceConnected) {
                            realPosition = currentPage;
                            lastDecision = "A11Y AUTHORITY + TOUCH PREVIEW";
                            offsetSource = "A11Y TOUCH";
                        } else {
                            lastDecision = "VIRTUAL TOUCH";
                            offsetSource = "VIRTUAL TOUCH";
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) break;
                        touchDx = touchX - downX;
                        touchDy = touchY - downY;
                        long dtMs = now - prevMoveMs;
                        if (dtMs > 0) {
                            float instantVx = (touchX - prevMoveX) * 1000f / dtMs;
                            touchVelocityX = touchVelocityX * 0.54f + instantVx * 0.46f;
                        }
                        prevMoveX = touchX;
                        prevMoveMs = now;
                        if (LauncherScrollBus.serviceConnected) {
                            // v0.9: while the finger is physically down, TOUCH always wins
                            // visually. Accessibility samples on One UI are too sparse/jagged
                            // for direct rendering; they are still collected in the background
                            // and become authoritative after release.
                            updateAuthoritativeTouchPreview();
                        } else {
                            updateFallbackDraggedPositions();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) break;
                        touchDx = touchX - downX;
                        touchDy = touchY - downY;
                        dragging = false;
                        if (LauncherScrollBus.serviceConnected) {
                            // Critical v0.8 rule: accessibility ON = NO virtual page guess.
                            // We wait for One UI. If no authoritative event arrives, we return
                            // to the already-known current page rather than inventing a transition.
                            awaitingA11yDecision = true;
                            a11yReleaseMs = now;
                            targetPage = currentPage;
                            // Hold exactly where the finger released. A11Y will move the
                            // TARGET from here; the visible glass is eased at render rate.
                            glassTargetPosition = glassPosition;
                            virtualTargetPosition = virtualPosition;
                            glassVelocity = 0f;
                            visualVelocity = 0f;
                            lastDecision = "WAIT ONE UI — NO PAGE GUESS";
                            offsetSource = "A11Y WAIT";
                        } else {
                            finishFallbackGesture(event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                        }
                        break;
                }
            }
            if (renderThread != null) renderThread.wakeUp();
        }

        private void updateAuthoritativeTouchPreview() {
            int w = Math.max(1, surfaceW);
            float rawPageDelta = -touchDx / w;
            float glass = applyEdgeRubber(gestureBasePage + rawPageDelta, 0.20f);
            float bg = applyEdgeRubber(gestureBasePage + rawPageDelta * swipeSensitivity, 0.28f);
            // Direct assignment is intentional: MotionEvent is the highest-rate signal we
            // have while dragging and follows reversals immediately. No A11Y jitter here.
            glassPosition = glass;
            glassTargetPosition = glass;
            virtualPosition = bg;
            virtualTargetPosition = bg;
            glassVelocity = 0f;
            visualVelocity = 0f;
            offsetSource = "TOUCH 1:1 + A11Y AUTH";
            // Page state intentionally unchanged. Accessibility alone can commit it.
        }

        private void updateFallbackDraggedPositions() {
            int w = Math.max(1, surfaceW);
            float rawPageDelta = -touchDx / w;
            virtualPosition = applyEdgeRubber(gestureBasePage + rawPageDelta * swipeSensitivity, 0.28f);
            glassPosition = applyEdgeRubber(gestureBasePage + rawPageDelta, 0.20f);
            virtualTargetPosition = virtualPosition;
            glassTargetPosition = glassPosition;
            offsetSource = "VIRTUAL TOUCH";
        }

        private void finishFallbackGesture(boolean fromCancel) {
            int w = Math.max(1, surfaceW);
            float distancePages = (-touchDx / w) * Math.min(swipeSensitivity, 1.65f);
            float velocityPages = (-touchVelocityX / w) * Math.min(swipeSensitivity, 1.50f);
            boolean horizontal = Math.abs(touchDx) > Math.abs(touchDy) * 0.70f;
            boolean strongDistance = Math.abs(distancePages) >= 0.21f;
            boolean strongVelocity = Math.abs(velocityPages) >= 0.58f;
            int direction = 0;
            if (horizontal && (strongDistance || strongVelocity)) {
                float signal = Math.abs(distancePages) >= 0.065f ? distancePages : velocityPages;
                direction = signal > 0f ? 1 : -1;
            }
            int proposedPage = clampInt(gestureBasePage + direction, 0, pageCount - 1);
            currentPage = proposedPage;
            targetPage = proposedPage;
            virtualTargetPosition = proposedPage;
            glassTargetPosition = proposedPage;
            visualVelocity = clamp(velocityPages * 0.20f, -1.80f, 1.80f);
            glassVelocity = clamp((-touchVelocityX / w) * 0.12f, -1.10f, 1.10f);
            lastDecision = direction == 0 ? "RETURN PAGE " + (currentPage + 1)
                    : String.format(Locale.US, "%s -> PAGE %d", fromCancel ? "FLING" : "SWIPE", currentPage + 1);
            saveStablePage();
        }

        private float applyEdgeRubber(float raw, float strength) {
            if (raw < 0f) return -rubberBand(-raw, strength);
            if (raw > pageCount - 1f) return (pageCount - 1f) + rubberBand(raw - (pageCount - 1f), strength);
            return raw;
        }

        private float rubberBand(float beyond, float strength) {
            return beyond * strength / (1f + beyond * 1.55f);
        }

        private void pollAccessibilityRealOffset(long nowMs) {
            long seq = LauncherScrollBus.sequence;
            if (seq == lastBusSequence) {
                handleA11yQuietPeriod(nowMs);
                return;
            }
            lastBusSequence = seq;
            a11yScrollX = LauncherScrollBus.scrollX;
            a11yMaxScrollX = LauncherScrollBus.maxScrollX;
            a11yDeltaX = LauncherScrollBus.deltaX;
            a11yFrom = LauncherScrollBus.fromIndex;
            a11yTo = LauncherScrollBus.toIndex;
            a11yCount = LauncherScrollBus.itemCount;
            a11yClass = LauncherScrollBus.className;
            a11ySummary = LauncherScrollBus.summary;

            if (!LauncherScrollBus.serviceConnected) {
                realOffsetLocked = false;
                awaitingA11yDecision = false;
                a11yContinuousThisGesture = false;
                offsetSource = "VIRTUAL TOUCH";
                return;
            }

            final boolean isScrollEvent = LauncherScrollBus.eventType
                    == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED;
            final float w = Math.max(1f, surfaceW);

            // Best case: One UI exposes an absolute horizontal scroll position.
            // v0.9 never copies the sparse sample straight into glassPosition. During
            // touch we render touch 1:1; after release this becomes a smooth target.
            if (isScrollEvent && a11yMaxScrollX > 0 && a11yScrollX >= 0 && pageCount > 1) {
                float stepPx = a11yMaxScrollX / (float) (pageCount - 1);
                boolean plausible = stepPx >= w * 0.45f && stepPx <= w * 1.85f
                        && a11yScrollX <= a11yMaxScrollX + w * 0.25f;
                if (plausible) {
                    float p = clamp(a11yScrollX / stepPx, 0f, pageCount - 1f);
                    synchronized (stateLock) {
                        realPosition = p;
                        if (!dragging) {
                            glassTargetPosition = p;
                            float base = currentPage;
                            virtualTargetPosition = applyEdgeRubber(
                                    base + (p - base) * swipeSensitivity, 0.24f);
                            offsetSource = "A11Y REAL → SMOOTH";
                        }
                        realOffsetLocked = true;
                        a11yContinuousThisGesture = true;
                        lastA11yMotionMs = nowMs;
                        lastRealEventMs = nowMs;
                        realSettledForEvent = false;
                        lastDecision = dragging ? "TOUCH 1:1 / A11Y OBSERVED" : "ONE UI ABSOLUTE TARGET";
                    }
                    handleA11yQuietPeriod(nowMs);
                    return;
                }
            }

            // Second-best case: One UI exposes only scrollDeltaX. Integrate it into an
            // observed target, then interpolate at render rate. This removes the 5–15 Hz
            // staircase that was visible in v0.8 when samples were rendered directly.
            if (isScrollEvent && a11yDeltaX != 0 && Math.abs(a11yDeltaX) <= w * 1.50f) {
                synchronized (stateLock) {
                    if (a11yDeltaSign == 0) {
                        float expected = Math.abs(touchVelocityX) > 30f ? -touchVelocityX : -touchDx;
                        if (Math.abs(expected) > 8f) {
                            a11yDeltaSign = ((expected > 0f) == (a11yDeltaX > 0)) ? 1 : -1;
                        } else {
                            a11yDeltaSign = 1;
                        }
                    }
                    if (!a11yContinuousThisGesture) {
                        realPosition = dragging ? gestureBasePage : glassPosition;
                    }
                    realPosition = applyEdgeRubber(
                            realPosition + (a11yDeltaX / w) * a11yDeltaSign, 0.20f);
                    if (!dragging) {
                        glassTargetPosition = realPosition;
                        float base = currentPage;
                        virtualTargetPosition = applyEdgeRubber(
                                base + (realPosition - base) * swipeSensitivity, 0.24f);
                        offsetSource = "A11Y DELTA → SMOOTH";
                    }
                    realOffsetLocked = true;
                    a11yContinuousThisGesture = true;
                    lastA11yMotionMs = nowMs;
                    lastRealEventMs = nowMs;
                    realSettledForEvent = false;
                    lastDecision = dragging ? "TOUCH 1:1 / A11Y DELTA OBS" : "ONE UI DELTA TARGET";
                }
            }

            // Authoritative final page. Only accept it from an actual scroll event.
            // Unlike v0.7, this is the ONLY thing (besides a truly settled continuous
            // scroll coordinate) that can commit a new page while accessibility is on.
            boolean postReleaseEvent = !awaitingA11yDecision
                    || LauncherScrollBus.eventUptimeMs >= a11yReleaseMs - 30L;
            if (isScrollEvent && !dragging && postReleaseEvent
                    && a11yCount == pageCount && a11yTo >= 0 && a11yTo < pageCount) {
                synchronized (stateLock) {
                    commitAuthoritativePage(a11yTo, "ONE UI PAGE " + (a11yTo + 1));
                }
            }

            handleA11yQuietPeriod(nowMs);
        }

        private void commitAuthoritativePage(int page, String reason) {
            int settled = clampInt(page, 0, pageCount - 1);
            currentPage = settled;
            targetPage = settled;
            realPosition = settled;
            // v0.9: page confirmation moves only the TARGET. Never snap the glass.
            // The 60 fps spring closes the remaining distance smoothly.
            glassTargetPosition = settled;
            virtualTargetPosition = settled;
            awaitingA11yDecision = false;
            realSettledForEvent = true;
            realOffsetLocked = true;
            a11yContinuousThisGesture = false;
            offsetSource = "A11Y PAGE → EASE";
            lastDecision = reason + " ✓";
            LauncherScrollBus.authoritativePage = settled;
            LauncherScrollBus.authoritativePageUptimeMs = SystemClock.uptimeMillis();
            saveStablePage();
        }

        private void handleA11yQuietPeriod(long nowMs) {
            if (!LauncherScrollBus.serviceConnected) return;

            // If continuous One UI scroll data has actually come to rest almost exactly
            // on a page, it is safe to commit that page even without a separate index event.
            if (a11yContinuousThisGesture && !dragging
                    && nowMs - lastA11yMotionMs > 150L) {
                int nearest = clampInt(Math.round(realPosition), 0, pageCount - 1);
                if (Math.abs(realPosition - nearest) < 0.10f) {
                    synchronized (stateLock) {
                        commitAuthoritativePage(nearest, "A11Y MOTION SETTLED " + (nearest + 1));
                    }
                    a11yContinuousThisGesture = false;
                    return;
                }
            }

            // No continuous scroll and no final page event: never guess. The partial swipe
            // is treated as cancelled and our glass returns to the already-known page.
            if (awaitingA11yDecision && nowMs - a11yReleaseMs > 430L
                    && nowMs - lastA11yMotionMs > 170L) {
                synchronized (stateLock) {
                    targetPage = currentPage;
                    realPosition = currentPage;
                    glassTargetPosition = currentPage;
                    virtualTargetPosition = currentPage;
                    awaitingA11yDecision = false;
                    offsetSource = "A11Y HOLD → EASE BACK";
                    lastDecision = "NO ONE UI PAGE CHANGE — RETURN";
                }
            }
        }

        private void advanceFallbackPhysics(float dt) {
            synchronized (stateLock) {
                if (!dragging) {
                    // Every sparse A11Y sample changes a target; the visible values are
                    // integrated every render frame. Damping is slightly over-critical so
                    // there is no oscillation/stair-step, but the response remains quick.
                    float bgD = virtualTargetPosition - virtualPosition;
                    visualVelocity += (bgD * 78f - visualVelocity * 18.5f) * dt;
                    virtualPosition += visualVelocity * dt;

                    float glassD = glassTargetPosition - glassPosition;
                    glassVelocity += (glassD * 118f - glassVelocity * 22.5f) * dt;
                    glassPosition += glassVelocity * dt;

                    if (Math.abs(bgD) < 0.0008f && Math.abs(visualVelocity) < 0.006f) {
                        virtualPosition = virtualTargetPosition;
                        visualVelocity = 0f;
                    }
                    if (Math.abs(glassD) < 0.0007f && Math.abs(glassVelocity) < 0.006f) {
                        glassPosition = glassTargetPosition;
                        glassVelocity = 0f;
                    }
                }

                float touchSpeed = Math.abs(touchVelocityX) / Math.max(1f, surfaceW);
                float desired = dragging ? clamp(touchSpeed * 0.42f, 0f, 1f)
                        : clamp(Math.abs(visualVelocity) * 0.55f, 0f, 1f);
                float response = desired > motionEnergy ? 12f : 4.3f;
                motionEnergy += (desired - motionEnergy) * clamp(dt * response, 0f, 1f);

                // v0.8 hid glass while waiting, which looked like another stutter. Keep it
                // fully visible and let the smooth target correction do the work instead.
                glassVisibility += (1f - glassVisibility) * clamp(dt * 14f, 0f, 1f);
            }
        }

        @SuppressWarnings("unchecked")
        private void rebuildGridCache() {
            int w = Math.max(1, surfaceW), h = Math.max(1, surfaceH);
            Prefs.ensureV06GridDefaults(prefs, w, h);
            synchronized (stateLock) {
                pageCount = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, pageCount), 2, 9);
                gridCols = clampInt(prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS), 2, 7);
                gridRows = clampInt(prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS), 2, 9);
                gridX0 = clamp(prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0), -0.20f, 1.20f);
                gridY0 = clamp(prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0), -0.20f, 1.20f);
                cellWidth = clamp(prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH), 0.03f, 0.40f);
                cellHeight = clamp(prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT), 0.015f, 0.25f);
                gapX = clamp(prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X), 0f, 0.40f);
                gapY = clamp(prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y), 0f, 0.30f);
                glassOpacity = clamp(prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY), 0.05f, 0.95f);
                cellsByPage = (ArrayList<Cell>[]) new ArrayList[pageCount];
                for (int p = 0; p < pageCount; p++) {
                    ArrayList<Cell> cells = new ArrayList<>();
                    for (int r = 0; r < gridRows; r++) {
                        for (int c = 0; c < gridCols; c++) {
                            String pkg = prefs.getString(Prefs.cellKey(p, r, c), null);
                            if (pkg != null && !pkg.isEmpty()) cells.add(new Cell(r, c));
                        }
                    }
                    cellsByPage[p] = cells;
                }
                gridDirty = false;
            }
        }

        private final class RenderThread extends Thread {
            private final SurfaceHolder holder;
            private volatile boolean running = true;
            private final Object waitLock = new Object();

            private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
            private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
            private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

            private int bgProgram, glassProgram, texProgram;
            private int quadBuffer;
            private FloatBuffer quad;
            private int debugTexture = 0;
            private Bitmap debugBitmap;
            private Canvas debugCanvas;
            private Paint debugPaint;
            private long lastDebugUploadMs = 0L;

            private long fpsStartNs = 0L;
            private int fpsFrames = 0;
            private float measuredFps = 0f;

            RenderThread(SurfaceHolder holder) {
                super("IridescentGL");
                this.holder = holder;
            }

            void requestStop() {
                running = false;
                wakeUp();
            }

            void wakeUp() {
                synchronized (waitLock) { waitLock.notifyAll(); }
            }

            @Override
            public void run() {
                if (!initEgl()) return;
                initGl();
                long lastNs = System.nanoTime();
                long nextFrameNs = lastNs;

                while (running) {
                    if (!visible) {
                        synchronized (waitLock) {
                            try { waitLock.wait(80L); } catch (InterruptedException ignored) {}
                        }
                        lastNs = System.nanoTime();
                        nextFrameNs = lastNs;
                        continue;
                    }

                    long nowNs = System.nanoTime();
                    float dt = clamp((nowNs - lastNs) / 1_000_000_000f, 0.001f, 0.05f);
                    lastNs = nowNs;

                    int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, lastConfigGeneration);
                    if (generation != lastConfigGeneration) loadPersistentState(false);
                    if (gridDirty) rebuildGridCache();

                    pollAccessibilityRealOffset(SystemClock.uptimeMillis());
                    advanceFallbackPhysics(dt);
                    render(nowNs);
                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) break;
                    updateFps(nowNs);

                    nextFrameNs += 16_666_667L;
                    long sleepNs = nextFrameNs - System.nanoTime();
                    if (sleepNs > 1_000_000L) {
                        try {
                            long ms = sleepNs / 1_000_000L;
                            int ns = (int) (sleepNs % 1_000_000L);
                            Thread.sleep(ms, ns);
                        } catch (InterruptedException ignored) {}
                    } else if (sleepNs < -50_000_000L) {
                        nextFrameNs = System.nanoTime();
                    }
                }
                releaseGl();
            }

            private boolean initEgl() {
                EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;
                int[] version = new int[2];
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

                int[] attrib = {
                        EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                if (!EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, num, 0) || num[0] == 0) return false;

                int[] ctxAttrib = {0x3098, 2, EGL14.EGL_NONE}; // EGL_CONTEXT_CLIENT_VERSION
                eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
                if (eglContext == EGL14.EGL_NO_CONTEXT) return false;

                int[] surfAttrib = {EGL14.EGL_NONE};
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], holder.getSurface(), surfAttrib, 0);
                if (eglSurface == EGL14.EGL_NO_SURFACE) return false;
                return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            }

            private void initGl() {
                float[] verts = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
                quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                quad.put(verts).position(0);

                bgProgram = buildProgram(VS_BG, FS_BG);
                glassProgram = buildProgram(VS_GLASS, FS_GLASS);
                texProgram = buildProgram(VS_TEX, FS_TEX);

                int[] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                debugTexture = tex[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                debugBitmap = Bitmap.createBitmap(900, 330, Bitmap.Config.ARGB_8888);
                debugCanvas = new Canvas(debugBitmap);
                debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            }

            private void render(long nowNs) {
                int w = Math.max(1, surfaceW), h = Math.max(1, surfaceH);
                GLES20.glViewport(0, 0, w, h);
                GLES20.glClearColor(0.01f, 0.01f, 0.03f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                float bgPos, glassPos, energy, opacity, visibility, x0, y0, cw, ch, gx, gy;
                int pages;
                ArrayList<Cell>[] pageCells;
                boolean debug;
                synchronized (stateLock) {
                    bgPos = virtualPosition;
                    glassPos = glassPosition;
                    energy = motionEnergy;
                    opacity = glassOpacity;
                    visibility = glassVisibility;
                    x0 = gridX0; y0 = gridY0; cw = cellWidth; ch = cellHeight; gx = gapX; gy = gapY;
                    pages = pageCount;
                    pageCells = cellsByPage;
                    debug = showDebug;
                }

                drawBackground(w, h, nowNs / 1_000_000_000f, bgPos, energy);
                if (pageCells != null) drawGlass(w, h, glassPos, pages, pageCells, x0, y0, cw, ch, gx, gy, opacity * visibility);
                if (debug) drawDebug(w, h);
            }

            private void drawBackground(int w, int h, float time, float pos, float energy) {
                GLES20.glUseProgram(bgProgram);
                bindQuad(bgProgram, "aPos");
                GLES20.glUniform2f(GLES20.glGetUniformLocation(bgProgram, "uRes"), w, h);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uTime"), time);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uPos"), pos);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uEnergy"), energy);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            private void drawGlass(int w, int h, float pos, int pages, ArrayList<Cell>[] pageCells,
                                   float x0, float y0, float cw, float ch, float gx, float gy, float opacity) {
                GLES20.glUseProgram(glassProgram);
                bindQuad(glassProgram, "aPos");
                int uScreen = GLES20.glGetUniformLocation(glassProgram, "uScreen");
                int uCenter = GLES20.glGetUniformLocation(glassProgram, "uCenter");
                int uSize = GLES20.glGetUniformLocation(glassProgram, "uSize");
                int uOpacity = GLES20.glGetUniformLocation(glassProgram, "uOpacity");
                GLES20.glUniform2f(uScreen, w, h);
                GLES20.glUniform2f(uSize, cw * w, ch * h);
                GLES20.glUniform1f(uOpacity, opacity);

                int p0 = clampInt((int) Math.floor(pos), 0, pages - 1);
                int p1 = clampInt((int) Math.ceil(pos), 0, pages - 1);
                drawGlassPage(w, h, pos, p0, pageCells[p0], x0, y0, cw + gx, ch + gy, uCenter);
                if (p1 != p0) drawGlassPage(w, h, pos, p1, pageCells[p1], x0, y0, cw + gx, ch + gy, uCenter);
            }

            private void drawGlassPage(int w, int h, float pos, int page, ArrayList<Cell> cells,
                                       float x0, float y0, float stepX, float stepY, int uCenter) {
                if (cells == null) return;
                float pageShift = (page - pos) * w;
                for (Cell cell : cells) {
                    float cx = (x0 + cell.col * stepX) * w + pageShift;
                    float cy = (y0 + cell.row * stepY) * h;
                    if (cx < -w * 0.25f || cx > w * 1.25f || cy < -h * 0.15f || cy > h * 1.15f) continue;
                    GLES20.glUniform2f(uCenter, cx, cy);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }
            }

            private void drawDebug(int w, int h) {
                long now = SystemClock.uptimeMillis();
                if (now - lastDebugUploadMs > 220L) {
                    lastDebugUploadMs = now;
                    updateDebugBitmap();
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, debugBitmap, 0);
                }
                GLES20.glUseProgram(texProgram);
                bindQuad(texProgram, "aPos");
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uScreen"), w, h);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uCenter"), w * 0.50f, h * 0.145f);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uSize"), w * 0.94f, h * 0.25f);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(texProgram, "uTex"), 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            private void updateDebugBitmap() {
                debugBitmap.eraseColor(Color.TRANSPARENT);
                debugPaint.setStyle(Paint.Style.FILL);
                debugPaint.setColor(Color.argb(220, 0, 0, 0));
                debugCanvas.drawRoundRect(new RectF(8, 8, 892, 322), 38, 38, debugPaint);
                debugPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                debugPaint.setTextSize(54f);
                debugPaint.setColor(Color.WHITE);
                debugCanvas.drawText("GPU Smooth Hybrid v0.9", 38, 68, debugPaint);
                debugPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                debugPaint.setTextSize(36f);

                String line1, line2, line3, line4, line5;
                synchronized (stateLock) {
                    line1 = String.format(Locale.US, "FPS %.1f   SOURCE %s", measuredFps, offsetSource);
                    line2 = String.format(Locale.US, "BG %.3f→%.3f  GLASS %.3f→%.3f  P %d/%d", virtualPosition, virtualTargetPosition, glassPosition, glassTargetPosition, currentPage + 1, pageCount);
                    line3 = String.format(Locale.US, "A11Y x=%d max=%d dx=%d", a11yScrollX, a11yMaxScrollX, a11yDeltaX);
                    line4 = String.format(Locale.US, "idx %d>%d count=%d  %s", a11yFrom, a11yTo, a11yCount, trim(a11yClass, 26));
                    line5 = trim(lastDecision + (a11ySummary.isEmpty() ? "" : " | " + a11ySummary), 72);
                }
                debugPaint.setColor(Color.rgb(145, 255, 180)); debugCanvas.drawText(line1, 38, 118, debugPaint);
                debugPaint.setColor(Color.rgb(185, 220, 255)); debugCanvas.drawText(line2, 38, 164, debugPaint);
                debugPaint.setColor(Color.WHITE); debugCanvas.drawText(line3, 38, 210, debugPaint);
                debugCanvas.drawText(line4, 38, 256, debugPaint);
                debugPaint.setColor(Color.rgb(255, 226, 150)); debugCanvas.drawText(line5, 38, 302, debugPaint);
            }

            private void bindQuad(int program, String attr) {
                int loc = GLES20.glGetAttribLocation(program, attr);
                quad.position(0);
                GLES20.glEnableVertexAttribArray(loc);
                GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad);
            }

            private int buildProgram(String vs, String fs) {
                int v = compile(GLES20.GL_VERTEX_SHADER, vs);
                int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
                int p = GLES20.glCreateProgram();
                GLES20.glAttachShader(p, v);
                GLES20.glAttachShader(p, f);
                GLES20.glLinkProgram(p);
                int[] ok = new int[1];
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) throw new RuntimeException("GL link: " + GLES20.glGetProgramInfoLog(p));
                GLES20.glDeleteShader(v); GLES20.glDeleteShader(f);
                return p;
            }

            private int compile(int type, String src) {
                int s = GLES20.glCreateShader(type);
                GLES20.glShaderSource(s, src);
                GLES20.glCompileShader(s);
                int[] ok = new int[1];
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
                if (ok[0] == 0) throw new RuntimeException("GL shader: " + GLES20.glGetShaderInfoLog(s));
                return s;
            }

            private void updateFps(long nowNs) {
                if (fpsStartNs == 0L) fpsStartNs = nowNs;
                fpsFrames++;
                long elapsed = nowNs - fpsStartNs;
                if (elapsed >= 1_000_000_000L) {
                    measuredFps = fpsFrames * 1_000_000_000f / elapsed;
                    fpsFrames = 0;
                    fpsStartNs = nowNs;
                }
            }

            private void releaseGl() {
                if (debugBitmap != null) debugBitmap.recycle();
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                }
            }
        }

        private String trim(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max);
        }

        private int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
        private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

        private static final String VS_BG =
                "attribute vec2 aPos; varying vec2 vUv; void main(){ vUv=aPos*0.5+0.5; gl_Position=vec4(aPos,0.0,1.0); }";

        private static final String FS_BG =
                "precision mediump float; varying vec2 vUv; uniform vec2 uRes; uniform float uTime; uniform float uPos; uniform float uEnergy;" +
                "float g(vec2 p, vec2 c, float r){ float d=length(p-c); return 1.0-smoothstep(0.0,r,d); }" +
                "void main(){" +
                " vec2 p=vUv; float asp=uRes.x/uRes.y; vec2 q=vec2((p.x-0.5)*asp,p.y-0.5);" +
                " vec3 col=mix(vec3(0.008,0.014,0.04),vec3(0.035,0.01,0.06),p.y);" +
                " float ph=uPos*0.62; float t=uTime;" +
                " vec2 c1=vec2((-0.24+0.20*sin(ph+t*0.08))*asp,-0.20+0.06*cos(t*0.17+ph));" +
                " vec2 c2=vec2(( 0.27+0.16*cos(ph*1.11-t*0.06))*asp,-0.06+0.08*sin(t*0.14-ph));" +
                " vec2 c3=vec2((-0.15+0.21*cos(ph*0.91+1.7))*asp, 0.27+0.04*sin(t*0.23));" +
                " vec2 c4=vec2(( 0.32-0.18*sin(ph*0.77+0.8))*asp, 0.33-0.04*cos(t*0.19));" +
                " float split=uEnergy*0.055*asp; c1.x-=split; c2.x+=split; c3.x+=split*0.7; c4.x-=split*0.45;" +
                " col+=vec3(0.00,0.72,1.00)*g(q,c1,0.72);" +
                " col+=vec3(0.32,0.05,0.95)*g(q,c2,0.64);" +
                " col+=vec3(0.95,0.00,0.62)*g(q,c3,0.58);" +
                " col+=vec3(1.00,0.28,0.02)*g(q,c4,0.48);" +
                " float band=1.0-smoothstep(0.02+uEnergy*0.02,0.12+uEnergy*0.05,abs(fract((p.x+p.y*0.42)+uPos*0.10+t*0.012)-0.50));" +
                " col+=band*vec3(0.16,0.18,0.22);" +
                " float vig=smoothstep(0.34,0.78,length(q)); col*=mix(1.0,0.45,vig);" +
                " gl_FragColor=vec4(col,1.0); }";

        private static final String VS_GLASS =
                "attribute vec2 aPos; varying vec2 vLocal; uniform vec2 uScreen; uniform vec2 uCenter; uniform vec2 uSize;" +
                "void main(){ vLocal=aPos; vec2 px=uCenter+aPos*uSize*0.5; vec2 clip=vec2(px.x/uScreen.x*2.0-1.0,1.0-px.y/uScreen.y*2.0); gl_Position=vec4(clip,0.0,1.0); }";

        private static final String FS_GLASS =
                "precision mediump float; varying vec2 vLocal; uniform float uOpacity;" +
                "float sdRoundBox(vec2 p, vec2 b, float r){ vec2 q=abs(p)-b+r; return min(max(q.x,q.y),0.0)+length(max(q,0.0))-r; }" +
                "void main(){ float d=sdRoundBox(vLocal,vec2(1.0),0.32); float inside=1.0-smoothstep(-0.02,0.02,d);" +
                " float edge=1.0-smoothstep(0.00,0.085,abs(d)); float inner=1.0-smoothstep(0.10,0.20,abs(d+0.10));" +
                " float shine=smoothstep(-0.35,0.75,-vLocal.y+vLocal.x*0.18)*0.16;" +
                " vec3 col=vec3(0.92,0.95,1.0)+shine; float a=inside*(0.045+uOpacity*0.10)+edge*(0.30+uOpacity*0.42)+inner*0.05;" +
                " gl_FragColor=vec4(col,clamp(a,0.0,0.82)*inside); }";

        private static final String VS_TEX =
                "attribute vec2 aPos; varying vec2 vUv; uniform vec2 uScreen; uniform vec2 uCenter; uniform vec2 uSize;" +
                "void main(){ vUv=aPos*0.5+0.5; vec2 px=uCenter+aPos*uSize*0.5; vec2 clip=vec2(px.x/uScreen.x*2.0-1.0,1.0-px.y/uScreen.y*2.0); gl_Position=vec4(clip,0.0,1.0); }";

        private static final String FS_TEX =
                "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor=texture2D(uTex,vUv); }";
    }
}
