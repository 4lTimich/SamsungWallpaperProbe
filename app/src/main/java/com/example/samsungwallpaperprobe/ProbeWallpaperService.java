package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.util.Locale;

public class ProbeWallpaperService extends WallpaperService {

    public static final String PREFS = "virtual_page_engine";
    public static final String KEY_PAGE_COUNT = "page_count";
    public static final String KEY_SAVED_PAGE = "saved_page";
    public static final String KEY_CONFIG_GENERATION = "config_generation";
    public static final String KEY_SWIPE_SENSITIVITY = "swipe_sensitivity";
    public static final String KEY_SHOW_DEBUG = "show_debug";

    @Override
    public Engine onCreateEngine() {
        return new VirtualPageEngine();
    }

    private final class VirtualPageEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        private SharedPreferences prefs;
        private boolean visible = false;

        // Config / persistent state. Pages are zero-based internally.
        private int pageCount = 4;
        private int currentPage = 0;
        private int targetPage = 0;
        private int lastConfigGeneration = -1;
        private float swipeSensitivity = 1.65f;
        private boolean showDebug = false;

        // Continuous visual position. 0 = page 1, 1 = page 2, etc.
        private float virtualPosition = 0f;
        private float visualVelocity = 0f; // pages/second
        private float motionEnergy = 0f;   // 0..1, drives chromatic split / light stretch
        private long lastFrameNs = 0L;

        // Gesture state.
        private boolean dragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float touchX = 0f;
        private float touchY = 0f;
        private float touchDx = 0f;
        private float touchDy = 0f;
        private float touchVelocityX = 0f; // px/sec
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

            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            loadPersistentState(true);
        }

        private void loadPersistentState(boolean forcePosition) {
            if (prefs == null) return;

            pageCount = clampInt(prefs.getInt(KEY_PAGE_COUNT, 4), 2, 9);
            swipeSensitivity = clamp(prefs.getFloat(KEY_SWIPE_SENSITIVITY, 1.65f), 0.70f, 2.60f);
            showDebug = prefs.getBoolean(KEY_SHOW_DEBUG, false);

            int generation = prefs.getInt(KEY_CONFIG_GENERATION, 0);
            int saved = clampInt(prefs.getInt(KEY_SAVED_PAGE, 0), 0, pageCount - 1);

            // A generation change means the user explicitly changed the current page
            // from the app. A process recreation also restores the last saved page.
            if (forcePosition || generation != lastConfigGeneration) {
                currentPage = saved;
                targetPage = saved;
                virtualPosition = saved;
                visualVelocity = 0f;
                dragging = false;
            } else {
                currentPage = clampInt(currentPage, 0, pageCount - 1);
                targetPage = clampInt(targetPage, 0, pageCount - 1);
                virtualPosition = clamp(virtualPosition, -0.20f, pageCount - 1f + 0.20f);
            }

            lastConfigGeneration = generation;
        }

        private void saveStablePage() {
            if (prefs == null || isPreview()) return;
            prefs.edit().putInt(KEY_SAVED_PAGE, currentPage).apply();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (visible) {
                // Preserve virtual page across unlock / app open-close. Reload visual
                // settings every time, but only reset the page after an explicit save.
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

                    updateDraggedPosition();
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
                        // One UI often CANCELs when it takes over the page animation.
                        finishGesture(true);
                    }
                    break;
            }

            drawFrame();
        }

        private void updateDraggedPosition() {
            SurfaceHolder holder = getSurfaceHolder();
            int w = holder.getSurfaceFrame().width();
            if (w <= 0) return;

            // v0.4: deliberately more sensitive. The wallpaper's visual offset can
            // travel farther than the finger so the material reacts immediately.
            // Finger left => launcher goes to the page on the right => page number grows.
            float pageDelta = (-touchDx / w) * swipeSensitivity;
            float raw = gestureBasePage + pageDelta;

            // Rubber-band at first/last page instead of wandering into nonexistent pages.
            if (raw < 0f) {
                raw = -rubberBand(-raw);
            } else if (raw > pageCount - 1f) {
                raw = (pageCount - 1f) + rubberBand(raw - (pageCount - 1f));
            }

            virtualPosition = raw;
        }

        private float rubberBand(float beyond) {
            // Keep edge response visible but increasingly resistant.
            return beyond * 0.28f / (1f + beyond * 1.55f);
        }

        private void finishGesture(boolean fromCancel) {
            dragging = false;

            SurfaceHolder holder = getSurfaceHolder();
            int w = Math.max(1, holder.getSurfaceFrame().width());

            // Apply some of the same sensitivity to our page decision. This makes short,
            // intentional One UI flicks more likely to be recognized than in v0.3.
            float distancePages = (-touchDx / w) * Math.min(swipeSensitivity, 1.65f);
            float velocityPages = (-touchVelocityX / w) * Math.min(swipeSensitivity, 1.50f);
            boolean horizontal = Math.abs(touchDx) > Math.abs(touchDy) * 0.70f;

            boolean strongDistance = Math.abs(distancePages) >= 0.21f;
            boolean strongVelocity = Math.abs(velocityPages) >= 0.58f;

            int direction = 0;
            if (horizontal && (strongDistance || strongVelocity)) {
                float decisionSignal = Math.abs(distancePages) >= 0.065f ? distancePages : velocityPages;
                direction = decisionSignal > 0f ? 1 : -1;
            }

            int proposedPage = clampInt(gestureBasePage + direction, 0, pageCount - 1);
            boolean changed = proposedPage != gestureBasePage;

            currentPage = proposedPage;
            targetPage = proposedPage;

            // Carry the fling into the shader. This is not just position: it also
            // increases chromatic separation for a brief moment.
            visualVelocity = clamp(velocityPages * 0.24f, -2.15f, 2.15f);

            if (changed) {
                lastDecision = String.format(Locale.US,
                        "%s -> PAGE %d SAVED", fromCancel ? "FLING" : "SWIPE", currentPage + 1);
            } else if (direction != 0) {
                lastDecision = "EDGE: PAGE " + (currentPage + 1);
            } else {
                lastDecision = "RETURN PAGE " + (currentPage + 1);
            }

            saveStablePage();
        }

        private void advancePhysics(float dt, int width) {
            if (!dragging) {
                // Spring toward the persistent virtual page.
                float displacement = targetPage - virtualPosition;
                float acceleration = displacement * 42f - visualVelocity * 10.8f;
                visualVelocity += acceleration * dt;
                virtualPosition += visualVelocity * dt;

                virtualPosition = clamp(virtualPosition, -0.18f, (pageCount - 1f) + 0.18f);

                if (Math.abs(targetPage - virtualPosition) < 0.0014f
                        && Math.abs(visualVelocity) < 0.008f) {
                    virtualPosition = targetPage;
                    visualVelocity = 0f;
                }
            }

            float pageTouchSpeed = width > 0 ? Math.abs(touchVelocityX) / width : 0f;
            float desiredEnergy;
            if (dragging) {
                desiredEnergy = clamp(pageTouchSpeed * 0.42f, 0f, 1f);
            } else {
                desiredEnergy = clamp(Math.abs(visualVelocity) * 0.55f, 0f, 1f);
            }

            // Fast attack, slower release = material feels like it has mass.
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

                final int w = canvas.getWidth();
                final int h = canvas.getHeight();
                final long nowNs = System.nanoTime();

                float dt = 1f / 60f;
                if (lastFrameNs != 0L) {
                    dt = clamp((nowNs - lastFrameNs) / 1_000_000_000f, 0.001f, 0.05f);
                }
                lastFrameNs = nowNs;

                advancePhysics(dt, w);
                drawIridescentBackground(canvas, w, h);

                if (showDebug) {
                    drawDiagnostics(canvas, w, h);
                }

            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            if (visible) {
                handler.postDelayed(drawRunner, 16L);
            }
        }

        private void drawIridescentBackground(Canvas canvas, int w, int h) {
            float time = SystemClock.elapsedRealtime() / 1000f;
            float pos = virtualPosition;
            float direction = dragging ? Math.signum(-touchVelocityX) : Math.signum(visualVelocity);
            float split = w * (0.018f + motionEnergy * 0.060f) * direction;

            // Dark glass base. Keeping the centre dark makes translucent icon shells read
            // as chrome/glass instead of a flat rainbow sticker.
            paint.setShader(new LinearGradient(
                    0f, 0f, w, h,
                    new int[]{
                            Color.rgb(2, 4, 12),
                            Color.rgb(5, 13, 30),
                            Color.rgb(24, 6, 31),
                            Color.rgb(5, 6, 12)
                    },
                    new float[]{0f, 0.34f, 0.72f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // The main spectral fields are tied to persistent virtualPosition.
            float pagePhase = pos * 0.62f;
            float breathe = (float) Math.sin(time * 0.23f);

            float cyanX = w * (0.12f + 0.22f * (float) Math.sin(pagePhase + time * 0.08f)) - split;
            float cyanY = h * (0.28f + 0.08f * (float) Math.cos(time * 0.17f + pagePhase));
            drawGlow(canvas, cyanX, cyanY, Math.max(w, h) * 0.68f,
                    Color.rgb(0, 225, 255), 176);

            float violetX = w * (0.78f + 0.15f * (float) Math.cos(pagePhase * 1.11f - time * 0.06f)) + split;
            float violetY = h * (0.38f + 0.10f * (float) Math.sin(time * 0.14f - pagePhase));
            drawGlow(canvas, violetX, violetY, Math.max(w, h) * 0.61f,
                    Color.rgb(86, 34, 255), 172);

            float magentaX = w * (0.28f + 0.24f * (float) Math.cos(pagePhase * 0.91f + 1.7f)) + split * 0.70f;
            float magentaY = h * (0.76f + 0.05f * breathe);
            drawGlow(canvas, magentaX, magentaY, Math.max(w, h) * 0.58f,
                    Color.rgb(255, 0, 181), 150);

            float orangeX = w * (0.92f - 0.22f * (float) Math.sin(pagePhase * 0.77f + 0.8f)) - split * 0.45f;
            float orangeY = h * (0.82f - 0.05f * (float) Math.cos(time * 0.19f));
            drawGlow(canvas, orangeX, orangeY, Math.max(w, h) * 0.50f,
                    Color.rgb(255, 118, 24), 122);

            // Thin pearlescent highlight. It moves farther during swipes so transparent
            // icon interiors visibly catch a different piece of the wallpaper.
            float lightTravel = (pos * 0.29f + time * 0.018f) * w;
            float bandWidth = w * (0.18f + motionEnergy * 0.10f);
            paint.setShader(new LinearGradient(
                    -w * 0.55f - lightTravel,
                    h * 0.06f,
                    w * 1.55f - lightTravel,
                    h * 0.93f,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb(16, 120, 230, 255),
                            Color.argb((int) (38 + motionEnergy * 40), 255, 255, 255),
                            Color.argb(18, 255, 95, 230),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.40f, 0.50f, 0.60f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(-bandWidth, 0, w + bandWidth, h, paint);
            paint.setShader(null);

            // Slight vignette leaves room for icon labels and makes the centre glow feel
            // like light under glass rather than a flat bitmap.
            paint.setShader(new RadialGradient(
                    w * 0.50f,
                    h * 0.46f,
                    Math.max(w, h) * 0.82f,
                    new int[]{Color.TRANSPARENT, Color.argb(112, 0, 0, 0)},
                    new float[]{0.48f, 1f},
                    Shader.TileMode.CLAMP));
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
                    new float[]{0f, 0.36f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, paint);
            paint.setShader(null);
        }

        private void drawDiagnostics(Canvas canvas, int w, int h) {
            float margin = w * 0.045f;
            float panelTop = h * 0.055f;
            float panelBottom = h * 0.33f;
            paint.setColor(Color.argb(218, 0, 0, 0));
            canvas.drawRoundRect(margin, panelTop, w - margin, panelBottom, 30f, 30f, paint);

            float left = margin * 1.45f;
            float y = panelTop + h * 0.048f;
            float line = h * 0.038f;

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(28f, w * 0.046f));
            canvas.drawText("Iridescent Engine v0.4", left, y, paint);
            paint.setFakeBoldText(false);

            y += line * 1.25f;
            paint.setTextSize(Math.max(19f, w * 0.029f));
            paint.setColor(Color.rgb(135, 255, 177));
            canvas.drawText(String.format(Locale.US,
                    "OFFSET %.3f   TARGET %d   PAGE %d/%d",
                    virtualPosition, targetPage + 1, currentPage + 1, pageCount), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(180, 220, 255));
            canvas.drawText(String.format(Locale.US,
                    "SENS %.2fx   ENERGY %.2f", swipeSensitivity, motionEnergy), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(230, 230, 230));
            canvas.drawText(String.format(Locale.US,
                    "TOUCH dx %.0f   vx %.0f", touchDx, touchVelocityX), left, y, paint);

            y += line;
            paint.setColor(Color.rgb(255, 225, 145));
            canvas.drawText(lastDecision, left, y, paint);

            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
