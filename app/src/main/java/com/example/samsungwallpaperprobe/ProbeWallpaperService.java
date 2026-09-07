package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Locale;

public class ProbeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new VirtualPageEngine();
    }

    private final class VirtualPageEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);

        private SharedPreferences prefs;
        private boolean visible = false;

        private int pageCount = 4;
        private int currentPage = 0;
        private int targetPage = 0;
        private int lastConfigGeneration = -1;
        private float swipeSensitivity = 1.80f;
        private boolean showDebug = false;

        // Background material position can be more sensitive than the real page.
        private float virtualPosition = 0f;
        private float visualVelocity = 0f;
        private float motionEnergy = 0f;

        // Glass grid remains 1:1 with One UI page motion.
        private float glassPosition = 0f;
        private float glassVelocity = 0f;

        private long lastFrameNs = 0L;
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

        // v0.6 performance: low-resolution material buffer + cached glass tile.
        private Bitmap backgroundBitmap;
        private Canvas backgroundCanvas;
        private int backgroundW = 0;
        private int backgroundH = 0;
        private int frameSequence = 0;

        private Bitmap glassBitmap;
        private int glassBitmapW = 0;
        private int glassBitmapH = 0;
        private float glassBitmapOpacity = -1f;

        // Cached grid geometry and assigned cells. No SharedPreferences scanning each frame.
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
        private boolean gridDirty = true;
        private boolean glassBitmapDirty = true;

        // FPS diagnostics.
        private long fpsWindowNs = 0L;
        private int fpsFrames = 0;
        private float measuredFps = 0f;
        private boolean lastFrameHardware = false;

        private final class Cell {
            final int row;
            final int col;
            Cell(int row, int col) { this.row = row; this.col = col; }
        }

        private final Runnable drawRunner = new Runnable() {
            @Override
            public void run() {
                drawFrame();
            }
        };

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
                    if (Prefs.KEY_CELL_WIDTH.equals(key)
                            || Prefs.KEY_CELL_HEIGHT.equals(key)
                            || Prefs.KEY_GLASS_OPACITY.equals(key)) {
                        glassBitmapDirty = true;
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
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
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
            if (prefs == null) return;

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
                visualVelocity = 0f;
                glassVelocity = 0f;
                dragging = false;
            } else {
                currentPage = clampInt(currentPage, 0, pageCount - 1);
                targetPage = clampInt(targetPage, 0, pageCount - 1);
                virtualPosition = clamp(virtualPosition, -0.22f, pageCount - 1f + 0.22f);
                glassPosition = clamp(glassPosition, -0.18f, pageCount - 1f + 0.18f);
            }

            lastConfigGeneration = generation;
            gridDirty = true;
        }

        private void saveStablePage() {
            if (prefs == null || isPreview()) return;
            prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, currentPage).apply();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (visible) {
                loadPersistentState(false);
                lastFrameNs = 0L;
                drawFrame();
            } else {
                saveStablePage();
                handler.removeCallbacks(drawRunner);
                lastFrameNs = 0L;
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            backgroundW = 0;
            backgroundH = 0;
            glassBitmapDirty = true;
            gridDirty = true;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            saveStablePage();
            visible = false;
            handler.removeCallbacks(drawRunner);
            lastFrameNs = 0L;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            saveStablePage();
            handler.removeCallbacks(drawRunner);
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            if (backgroundBitmap != null) backgroundBitmap.recycle();
            if (glassBitmap != null) glassBitmap.recycle();
            super.onDestroy();
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            final long now = SystemClock.uptimeMillis();
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
                    lastDecision = "DRAGGING";
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
                    updateDraggedPositions();
                    break;

                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        touchDx = touchX - downX;
                        touchDy = touchY - downY;
                        finishGesture(false);
                    }
                    break;

                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        touchDx = touchX - downX;
                        touchDy = touchY - downY;
                        finishGesture(true);
                    }
                    break;
            }
            // Immediate render on every input event; scheduled frames continue between events.
            drawFrame();
        }

        private void updateDraggedPositions() {
            int w = getSurfaceHolder().getSurfaceFrame().width();
            if (w <= 0) return;

            float rawPageDelta = -touchDx / w;

            float backgroundRaw = gestureBasePage + rawPageDelta * swipeSensitivity;
            virtualPosition = applyEdgeRubber(backgroundRaw, 0.28f);

            // Exactly 1:1 with the finger / One UI page movement.
            float glassRaw = gestureBasePage + rawPageDelta;
            glassPosition = applyEdgeRubber(glassRaw, 0.20f);
        }

        private float applyEdgeRubber(float raw, float strength) {
            if (raw < 0f) {
                return -rubberBand(-raw, strength);
            } else if (raw > pageCount - 1f) {
                return (pageCount - 1f) + rubberBand(raw - (pageCount - 1f), strength);
            }
            return raw;
        }

        private float rubberBand(float beyond, float strength) {
            return beyond * strength / (1f + beyond * 1.55f);
        }

        private void finishGesture(boolean fromCancel) {
            dragging = false;
            int w = Math.max(1, getSurfaceHolder().getSurfaceFrame().width());

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
            boolean changed = proposedPage != gestureBasePage;

            currentPage = proposedPage;
            targetPage = proposedPage;

            visualVelocity = clamp(velocityPages * 0.24f, -2.15f, 2.15f);
            glassVelocity = clamp((-touchVelocityX / w) * 0.18f, -1.40f, 1.40f);

            if (changed) {
                lastDecision = String.format(Locale.US, "%s -> PAGE %d SAVED",
                        fromCancel ? "FLING" : "SWIPE", currentPage + 1);
            } else if (direction != 0) {
                lastDecision = "EDGE: PAGE " + (currentPage + 1);
            } else {
                lastDecision = "RETURN PAGE " + (currentPage + 1);
            }

            saveStablePage();
        }

        private void advancePhysics(float dt, int width) {
            if (!dragging) {
                float bgDisplacement = targetPage - virtualPosition;
                float bgAccel = bgDisplacement * 42f - visualVelocity * 10.8f;
                visualVelocity += bgAccel * dt;
                virtualPosition += visualVelocity * dt;
                virtualPosition = clamp(virtualPosition, -0.20f, pageCount - 1f + 0.20f);

                float glassDisplacement = targetPage - glassPosition;
                // Faster glass spring than v0.5 so it does not visibly lag behind icons.
                float glassAccel = glassDisplacement * 68f - glassVelocity * 15.8f;
                glassVelocity += glassAccel * dt;
                glassPosition += glassVelocity * dt;
                glassPosition = clamp(glassPosition, -0.12f, pageCount - 1f + 0.12f);

                if (Math.abs(targetPage - virtualPosition) < 0.0014f && Math.abs(visualVelocity) < 0.008f) {
                    virtualPosition = targetPage;
                    visualVelocity = 0f;
                }
                if (Math.abs(targetPage - glassPosition) < 0.0008f && Math.abs(glassVelocity) < 0.005f) {
                    glassPosition = targetPage;
                    glassVelocity = 0f;
                }
            }

            float pageTouchSpeed = width > 0 ? Math.abs(touchVelocityX) / width : 0f;
            float desiredEnergy = dragging
                    ? clamp(pageTouchSpeed * 0.42f, 0f, 1f)
                    : clamp(Math.abs(visualVelocity) * 0.55f, 0f, 1f);
            float response = desiredEnergy > motionEnergy ? 12f : 4.3f;
            motionEnergy += (desiredEnergy - motionEnergy) * clamp(dt * response, 0f, 1f);
        }

        private void drawFrame() {
            handler.removeCallbacks(drawRunner);
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            boolean hardware = false;

            try {
                try {
                    canvas = holder.lockHardwareCanvas();
                    hardware = canvas != null;
                } catch (Throwable ignored) {
                    canvas = null;
                }
                if (canvas == null) {
                    canvas = holder.lockCanvas();
                    hardware = false;
                }
                if (canvas == null) return;

                int w = canvas.getWidth();
                int h = canvas.getHeight();
                long nowNs = System.nanoTime();

                float dt = 1f / 60f;
                if (lastFrameNs != 0L) {
                    dt = clamp((nowNs - lastFrameNs) / 1_000_000_000f, 0.001f, 0.05f);
                }
                lastFrameNs = nowNs;

                int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, lastConfigGeneration);
                if (generation != lastConfigGeneration) loadPersistentState(false);
                if (gridDirty) rebuildGridCache(w, h);

                advancePhysics(dt, w);
                frameSequence++;

                // Material updates at 30fps, while cached glass can track at 60fps.
                // This cuts the expensive gradient work roughly in half without making
                // the icon-alignment layer feel slow.
                boolean renderMaterial = backgroundBitmap == null || (frameSequence & 1) == 0;
                drawIridescentBackground(canvas, w, h, renderMaterial);
                drawGlassPages(canvas, w, h);

                lastFrameHardware = hardware;
                updateFps(nowNs);
                if (showDebug) drawDiagnostics(canvas, w, h);

            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }

            if (visible) {
                boolean active = dragging
                        || Math.abs(visualVelocity) > 0.01f
                        || Math.abs(glassVelocity) > 0.01f
                        || motionEnergy > 0.02f;
                handler.postDelayed(drawRunner, active ? 16L : 33L);
            }
        }

        private void updateFps(long nowNs) {
            if (fpsWindowNs == 0L) fpsWindowNs = nowNs;
            fpsFrames++;
            long elapsed = nowNs - fpsWindowNs;
            if (elapsed >= 1_000_000_000L) {
                measuredFps = fpsFrames * 1_000_000_000f / elapsed;
                fpsFrames = 0;
                fpsWindowNs = nowNs;
            }
        }

        private void ensureBackgroundBuffer(int w, int h) {
            // Smooth gradients do not need full phone resolution. Scaling a half-ish
            // resolution material buffer is much cheaper on Canvas and remains smooth.
            int bw = Math.max(220, Math.round(w * 0.45f));
            int bh = Math.max(420, Math.round(h * 0.45f));
            if (backgroundBitmap != null && bw == backgroundW && bh == backgroundH) return;

            if (backgroundBitmap != null) backgroundBitmap.recycle();
            backgroundBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            backgroundCanvas = new Canvas(backgroundBitmap);
            backgroundW = bw;
            backgroundH = bh;
        }

        private void drawIridescentBackground(Canvas target, int w, int h, boolean rerender) {
            ensureBackgroundBuffer(w, h);
            if (rerender) renderMaterial(backgroundCanvas, backgroundW, backgroundH);
            bitmapPaint.setAlpha(255);
            target.drawBitmap(backgroundBitmap, null, new RectF(0, 0, w, h), bitmapPaint);
        }

        private void renderMaterial(Canvas canvas, int w, int h) {
            float time = SystemClock.elapsedRealtime() / 1000f;
            float pos = virtualPosition;
            float direction = dragging ? Math.signum(-touchVelocityX) : Math.signum(visualVelocity);
            float split = w * (0.018f + motionEnergy * 0.060f) * direction;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    0f, 0f, w, h,
                    new int[]{Color.rgb(2, 4, 12), Color.rgb(5, 13, 30), Color.rgb(24, 6, 31), Color.rgb(5, 6, 12)},
                    new float[]{0f, 0.34f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float pagePhase = pos * 0.62f;
            float breathe = (float) Math.sin(time * 0.23f);

            drawGlow(canvas,
                    w * (0.12f + 0.22f * (float) Math.sin(pagePhase + time * 0.08f)) - split,
                    h * (0.28f + 0.08f * (float) Math.cos(time * 0.17f + pagePhase)),
                    Math.max(w, h) * 0.68f, Color.rgb(0, 225, 255), 176);

            drawGlow(canvas,
                    w * (0.78f + 0.15f * (float) Math.cos(pagePhase * 1.11f - time * 0.06f)) + split,
                    h * (0.38f + 0.10f * (float) Math.sin(time * 0.14f - pagePhase)),
                    Math.max(w, h) * 0.61f, Color.rgb(86, 34, 255), 172);

            drawGlow(canvas,
                    w * (0.28f + 0.24f * (float) Math.cos(pagePhase * 0.91f + 1.7f)) + split * 0.70f,
                    h * (0.76f + 0.05f * breathe),
                    Math.max(w, h) * 0.58f, Color.rgb(255, 0, 181), 150);

            drawGlow(canvas,
                    w * (0.92f - 0.22f * (float) Math.sin(pagePhase * 0.77f + 0.8f)) - split * 0.45f,
                    h * (0.82f - 0.05f * (float) Math.cos(time * 0.19f)),
                    Math.max(w, h) * 0.50f, Color.rgb(255, 118, 24), 122);

            float lightTravel = (pos * 0.29f + time * 0.018f) * w;
            paint.setShader(new LinearGradient(
                    -w * 0.55f - lightTravel, h * 0.06f,
                    w * 1.55f - lightTravel, h * 0.93f,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb(16, 120, 230, 255),
                            Color.argb((int) (38 + motionEnergy * 40), 255, 255, 255),
                            Color.argb(18, 255, 95, 230),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.40f, 0.50f, 0.60f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setShader(new RadialGradient(
                    w * 0.50f, h * 0.46f, Math.max(w, h) * 0.82f,
                    new int[]{Color.TRANSPARENT, Color.argb(108, 0, 0, 0)},
                    new float[]{0.48f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        private void drawGlow(Canvas canvas, float x, float y, float radius, int rgb, int alpha) {
            int r = Color.red(rgb);
            int g = Color.green(rgb);
            int b = Color.blue(rgb);
            paint.setShader(new RadialGradient(
                    x, y, radius,
                    new int[]{
                            Color.argb(alpha, r, g, b),
                            Color.argb((int) (alpha * 0.52f), r, g, b),
                            Color.argb(0, r, g, b)
                    },
                    new float[]{0f, 0.36f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, paint);
            paint.setShader(null);
        }

        @SuppressWarnings("unchecked")
        private void rebuildGridCache(int w, int h) {
            Prefs.ensureV06GridDefaults(prefs, w, h);
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
            for (int page = 0; page < pageCount; page++) {
                ArrayList<Cell> cells = new ArrayList<>();
                for (int row = 0; row < gridRows; row++) {
                    for (int col = 0; col < gridCols; col++) {
                        String pkg = prefs.getString(Prefs.cellKey(page, row, col), null);
                        if (pkg != null && !pkg.isEmpty()) cells.add(new Cell(row, col));
                    }
                }
                cellsByPage[page] = cells;
            }

            gridDirty = false;
            glassBitmapDirty = true;
        }

        private void ensureGlassBitmap(int screenW, int screenH) {
            int pxW = Math.max(12, Math.round(cellWidth * screenW));
            int pxH = Math.max(12, Math.round(cellHeight * screenH));
            if (!glassBitmapDirty && glassBitmap != null
                    && pxW == glassBitmapW && pxH == glassBitmapH
                    && Math.abs(glassBitmapOpacity - glassOpacity) < 0.001f) return;

            if (glassBitmap != null) glassBitmap.recycle();
            glassBitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(glassBitmap);
            renderGlassTile(c, pxW, pxH, glassOpacity);
            glassBitmapW = pxW;
            glassBitmapH = pxH;
            glassBitmapOpacity = glassOpacity;
            glassBitmapDirty = false;
        }

        private void renderGlassTile(Canvas canvas, int w, int h, float opacity) {
            float min = Math.min(w, h);
            float pad = Math.max(2f, min * 0.04f);
            RectF rect = new RectF(pad, pad, w - pad, h - pad);
            float radius = min * 0.24f;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{
                            Color.argb((int) (88 * opacity), 255, 255, 255),
                            Color.argb((int) (24 * opacity), 242, 246, 250),
                            Color.argb((int) (14 * opacity), 114, 124, 140),
                            Color.argb((int) (54 * opacity), 255, 255, 255)
                    },
                    new float[]{0f, 0.32f, 0.76f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, min * 0.045f));
            paint.setColor(Color.argb((int) (205 * opacity), 255, 255, 255));
            canvas.drawRoundRect(rect, radius, radius, paint);

            paint.setStrokeWidth(Math.max(1f, min * 0.018f));
            paint.setColor(Color.argb((int) (110 * opacity), 42, 48, 58));
            RectF inner = new RectF(rect.left + min * 0.055f, rect.top + min * 0.055f,
                    rect.right - min * 0.055f, rect.bottom - min * 0.055f);
            canvas.drawRoundRect(inner, radius * 0.78f, radius * 0.78f, paint);

            paint.setStrokeWidth(Math.max(1f, min * 0.020f));
            paint.setColor(Color.argb((int) (118 * opacity), 255, 255, 255));
            RectF highlight = new RectF(rect.left + min * 0.10f, rect.top + min * 0.08f,
                    rect.right - min * 0.10f, rect.bottom - min * 0.18f);
            canvas.drawArc(highlight, 200f, 140f, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawGlassPages(Canvas canvas, int w, int h) {
            if (cellsByPage == null) return;
            ensureGlassBitmap(w, h);

            float stepX = cellWidth + gapX;
            float stepY = cellHeight + gapY;

            // Only pages that can physically intersect the screen are drawn.
            int p0 = clampInt((int) Math.floor(glassPosition), 0, pageCount - 1);
            int p1 = clampInt((int) Math.ceil(glassPosition), 0, pageCount - 1);
            drawOneGlassPage(canvas, w, h, p0, stepX, stepY);
            if (p1 != p0) drawOneGlassPage(canvas, w, h, p1, stepX, stepY);
        }

        private void drawOneGlassPage(Canvas canvas, int w, int h, int page, float stepX, float stepY) {
            if (page < 0 || page >= pageCount || cellsByPage[page] == null) return;
            float pageTranslateX = (page - glassPosition) * w;

            for (Cell cell : cellsByPage[page]) {
                float cx = (gridX0 + cell.col * stepX) * w + pageTranslateX;
                float cy = (gridY0 + cell.row * stepY) * h;

                // Skip fully offscreen plates.
                if (cx < -glassBitmapW || cx > w + glassBitmapW
                        || cy < -glassBitmapH || cy > h + glassBitmapH) continue;

                canvas.drawBitmap(glassBitmap,
                        cx - glassBitmapW * 0.5f,
                        cy - glassBitmapH * 0.5f,
                        bitmapPaint);
            }
        }

        private void drawDiagnostics(Canvas canvas, int w, int h) {
            float margin = w * 0.045f;
            float panelTop = h * 0.055f;
            float panelBottom = h * 0.36f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(220, 0, 0, 0));
            canvas.drawRoundRect(margin, panelTop, w - margin, panelBottom, 30f, 30f, paint);

            float left = margin * 1.45f;
            float y = panelTop + h * 0.044f;
            float line = h * 0.035f;

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(27f, w * 0.044f));
            canvas.drawText("Iridescent + Glass v0.6", left, y, paint);
            paint.setFakeBoldText(false);

            y += line * 1.25f;
            paint.setTextSize(Math.max(17f, w * 0.027f));
            paint.setColor(Color.rgb(135, 255, 177));
            canvas.drawText(String.format(Locale.US,
                    "FPS %.1f  %s  BG %dx%d",
                    measuredFps, lastFrameHardware ? "HW" : "SW", backgroundW, backgroundH), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(180, 220, 255));
            canvas.drawText(String.format(Locale.US,
                    "BG %.3f  GLASS %.3f  PAGE %d/%d",
                    virtualPosition, glassPosition, currentPage + 1, pageCount), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(230, 230, 230));
            canvas.drawText(String.format(Locale.US,
                    "TOUCH dx %.0f  vx %.0f  SENS %.2fx",
                    touchDx, touchVelocityX, swipeSensitivity), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(230, 230, 230));
            canvas.drawText(String.format(Locale.US,
                    "CELL %dx%d  GAP %dx%d",
                    Math.round(cellWidth * w), Math.round(cellHeight * h),
                    Math.round(gapX * w), Math.round(gapY * h)), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(255, 225, 145));
            canvas.drawText(lastDecision, left, y, paint);
            paint.setFakeBoldText(false);
        }

        private int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
