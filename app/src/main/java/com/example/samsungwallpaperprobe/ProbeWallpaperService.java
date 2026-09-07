package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
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

import java.util.Locale;

public class ProbeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new VirtualPageEngine();
    }

    private final class VirtualPageEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        private SharedPreferences prefs;
        private boolean visible = false;

        private int pageCount = 4;
        private int currentPage = 0;
        private int targetPage = 0;
        private int lastConfigGeneration = -1;
        private float swipeSensitivity = 1.80f;
        private boolean showDebug = false;

        // Background material position can be intentionally more sensitive than the real page.
        private float virtualPosition = 0f;
        private float visualVelocity = 0f;
        private float motionEnergy = 0f;

        // Glass grid uses a separate 1:1 page position so pads stay under One UI icons.
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

        private final Runnable drawRunner = new Runnable() {
            @Override
            public void run() {
                drawFrame();
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(false);
            prefs = getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
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
                        touchVelocityX = touchVelocityX * 0.58f + instantVx * 0.42f;
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
            drawFrame();
        }

        private void updateDraggedPositions() {
            int w = getSurfaceHolder().getSurfaceFrame().width();
            if (w <= 0) return;

            float rawPageDelta = -touchDx / w;

            // Material reacts stronger than the page itself.
            float backgroundRaw = gestureBasePage + rawPageDelta * swipeSensitivity;
            virtualPosition = applyEdgeRubber(backgroundRaw, 0.28f);

            // Glass must follow One UI 1:1 so it stays underneath the moving icon grid.
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
                float glassAccel = glassDisplacement * 48f - glassVelocity * 12.5f;
                glassVelocity += glassAccel * dt;
                glassPosition += glassVelocity * dt;
                glassPosition = clamp(glassPosition, -0.14f, pageCount - 1f + 0.14f);

                if (Math.abs(targetPage - virtualPosition) < 0.0014f && Math.abs(visualVelocity) < 0.008f) {
                    virtualPosition = targetPage;
                    visualVelocity = 0f;
                }
                if (Math.abs(targetPage - glassPosition) < 0.0010f && Math.abs(glassVelocity) < 0.006f) {
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

            try {
                canvas = holder.lockCanvas();
                if (canvas == null) return;

                int w = canvas.getWidth();
                int h = canvas.getHeight();
                long nowNs = System.nanoTime();

                float dt = 1f / 60f;
                if (lastFrameNs != 0L) {
                    dt = clamp((nowNs - lastFrameNs) / 1_000_000_000f, 0.001f, 0.05f);
                }
                lastFrameNs = nowNs;

                // Reload visual/grid options continuously enough that returning from settings updates immediately.
                swipeSensitivity = clamp(prefs.getFloat(Prefs.KEY_SWIPE_SENSITIVITY, swipeSensitivity), 0.70f, 2.60f);
                showDebug = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, showDebug);

                advancePhysics(dt, w);
                drawIridescentBackground(canvas, w, h);
                drawGlassPages(canvas, w, h);

                if (showDebug) drawDiagnostics(canvas, w, h);

            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }

            if (visible) handler.postDelayed(drawRunner, 16L);
        }

        private void drawIridescentBackground(Canvas canvas, int w, int h) {
            float time = SystemClock.elapsedRealtime() / 1000f;
            float pos = virtualPosition;
            float direction = dragging ? Math.signum(-touchVelocityX) : Math.signum(visualVelocity);
            float split = w * (0.018f + motionEnergy * 0.060f) * direction;

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

        private void drawGlassPages(Canvas canvas, int w, int h) {
            int cols = clampInt(prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS), 2, 7);
            int rows = clampInt(prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS), 2, 9);
            float x0 = clamp(prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0), 0.01f, 0.95f);
            float y0 = clamp(prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0), 0.01f, 0.95f);
            float stepX = clamp(prefs.getFloat(Prefs.KEY_GRID_STEP_X, Prefs.DEFAULT_STEP_X), 0.02f, 0.80f);
            float stepY = clamp(prefs.getFloat(Prefs.KEY_GRID_STEP_Y, Prefs.DEFAULT_STEP_Y), 0.02f, 0.80f);
            float size = clamp(prefs.getFloat(Prefs.KEY_GLASS_SIZE, Prefs.DEFAULT_GLASS_SIZE), 0.06f, 0.28f) * w;
            float opacity = clamp(prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY), 0.05f, 0.95f);

            // Draw current/neighboring pages. Translation follows glassPosition, not the more sensitive background position.
            int firstPage = clampInt((int) Math.floor(glassPosition) - 1, 0, pageCount - 1);
            int lastPage = clampInt((int) Math.ceil(glassPosition) + 1, 0, pageCount - 1);

            for (int page = firstPage; page <= lastPage; page++) {
                float pageTranslateX = (page - glassPosition) * w;
                if (Math.abs(pageTranslateX) > w * 1.35f) continue;

                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        String pkg = prefs.getString(Prefs.cellKey(page, row, col), null);
                        if (pkg == null || pkg.isEmpty()) continue;

                        float cx = (x0 + col * stepX) * w + pageTranslateX;
                        float cy = (y0 + row * stepY) * h;
                        drawGlassPlate(canvas, cx, cy, size, opacity);
                    }
                }
            }
        }

        private void drawGlassPlate(Canvas canvas, float cx, float cy, float size, float opacity) {
            float half = size * 0.5f;
            float radius = size * 0.25f;
            RectF rect = new RectF(cx - half, cy - half, cx + half, cy + half);

            // Soft shadow / depth.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (70 * opacity), 0, 0, 0));
            RectF shadow = new RectF(rect.left + size * 0.025f, rect.top + size * 0.040f,
                    rect.right + size * 0.025f, rect.bottom + size * 0.040f);
            canvas.drawRoundRect(shadow, radius, radius, paint);

            // Neutral glass fill: grayscale only, no iridescent color baked into the glass itself.
            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{
                            Color.argb((int) (92 * opacity), 255, 255, 255),
                            Color.argb((int) (26 * opacity), 242, 246, 250),
                            Color.argb((int) (14 * opacity), 114, 124, 140),
                            Color.argb((int) (58 * opacity), 255, 255, 255)
                    },
                    new float[]{0f, 0.32f, 0.76f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            // Bright silver rim.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.045f));
            paint.setColor(Color.argb((int) (210 * opacity), 255, 255, 255));
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Inner darker ring to create glass thickness.
            paint.setStrokeWidth(Math.max(1f, size * 0.018f));
            paint.setColor(Color.argb((int) (118 * opacity), 42, 48, 58));
            RectF inner = new RectF(rect.left + size * 0.055f, rect.top + size * 0.055f,
                    rect.right - size * 0.055f, rect.bottom - size * 0.055f);
            canvas.drawRoundRect(inner, radius * 0.78f, radius * 0.78f, paint);

            // Static specular top edge, deliberately neutral.
            paint.setStrokeWidth(Math.max(1f, size * 0.020f));
            paint.setColor(Color.argb((int) (122 * opacity), 255, 255, 255));
            RectF highlight = new RectF(rect.left + size * 0.10f, rect.top + size * 0.08f,
                    rect.right - size * 0.10f, rect.bottom - size * 0.20f);
            canvas.drawArc(highlight, 200f, 140f, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawDiagnostics(Canvas canvas, int w, int h) {
            float margin = w * 0.045f;
            float panelTop = h * 0.055f;
            float panelBottom = h * 0.34f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(220, 0, 0, 0));
            canvas.drawRoundRect(margin, panelTop, w - margin, panelBottom, 30f, 30f, paint);

            float left = margin * 1.45f;
            float y = panelTop + h * 0.046f;
            float line = h * 0.038f;

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(28f, w * 0.046f));
            canvas.drawText("Iridescent + Glass v0.5", left, y, paint);
            paint.setFakeBoldText(false);

            y += line * 1.25f;
            paint.setTextSize(Math.max(18f, w * 0.028f));
            paint.setColor(Color.rgb(135, 255, 177));
            canvas.drawText(String.format(Locale.US,
                    "BG %.3f  GLASS %.3f  PAGE %d/%d",
                    virtualPosition, glassPosition, currentPage + 1, pageCount), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(180, 220, 255));
            canvas.drawText(String.format(Locale.US,
                    "SENS %.2fx  ENERGY %.2f", swipeSensitivity, motionEnergy), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(230, 230, 230));
            canvas.drawText(String.format(Locale.US,
                    "TOUCH dx %.0f  vx %.0f", touchDx, touchVelocityX), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(255, 225, 145));
            canvas.drawText(lastDecision, left, y, paint);
        }

        private int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
