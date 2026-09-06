package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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

    @Override
    public Engine onCreateEngine() {
        return new VirtualPageEngine();
    }

    private final class VirtualPageEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private SharedPreferences prefs;
        private boolean visible = false;

        // Config / persistent state. Pages are zero-based internally.
        private int pageCount = 4;
        private int currentPage = 0;
        private int targetPage = 0;
        private int lastConfigGeneration = -1;

        // Continuous visual position. 0 = page 1, 1 = page 2, etc.
        private float virtualPosition = 0f;
        private float visualVelocity = 0f; // pages/second
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
        private long gestureCount = 0;
        private String lastDecision = "WAITING FOR SWIPE";

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
                virtualPosition = clamp(virtualPosition, 0f, pageCount - 1f);
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
                // Do NOT reset position on unlock or after closing an app.
                // We only reload if settings were explicitly changed while hidden.
                loadPersistentState(false);
                lastFrameNs = 0L;
                drawFrame();
            } else {
                // Persist the page before the wallpaper goes invisible, so process death,
                // lock/unlock and opening apps cannot reset our virtual offset.
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
                    gestureCount++;
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
                        touchVelocityX = touchVelocityX * 0.62f + instantVx * 0.38f;
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
                        // CANCEL can happen when the launcher takes over the page animation.
                        // Treat it like release if the gesture is clearly horizontal/strong.
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

            // Finger left => launcher moves toward the page on the right => page number grows.
            float pageDelta = -touchDx / w;
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
            return beyond * 0.22f / (1f + beyond * 1.7f);
        }

        private void finishGesture(boolean fromCancel) {
            dragging = false;

            SurfaceHolder holder = getSurfaceHolder();
            int w = Math.max(1, holder.getSurfaceFrame().width());

            float distancePages = -touchDx / w;
            float velocityPages = -touchVelocityX / w;
            boolean horizontal = Math.abs(touchDx) > Math.abs(touchDy) * 0.72f;

            // Samsung uses both distance and fling velocity. We approximate the same decision.
            boolean strongDistance = Math.abs(distancePages) >= 0.24f;
            boolean strongVelocity = Math.abs(velocityPages) >= 0.72f;

            int direction = 0;
            if (horizontal && (strongDistance || strongVelocity)) {
                float decisionSignal = Math.abs(distancePages) >= 0.08f ? distancePages : velocityPages;
                direction = decisionSignal > 0f ? 1 : -1;
            }

            int proposedPage = clampInt(gestureBasePage + direction, 0, pageCount - 1);
            boolean changed = proposedPage != gestureBasePage;

            currentPage = proposedPage;
            targetPage = proposedPage;

            // Preserve some fling energy for a small overshoot, then spring to the new page.
            visualVelocity = clamp(velocityPages * 0.20f, -1.8f, 1.8f);

            if (changed) {
                lastDecision = String.format(Locale.US,
                        "%s -> PAGE %d SAVED", fromCancel ? "CANCEL/FLING" : "SWIPE", currentPage + 1);
            } else if (direction != 0) {
                lastDecision = "EDGE: STAY ON PAGE " + (currentPage + 1);
            } else {
                lastDecision = "SHORT SWIPE: RETURN PAGE " + (currentPage + 1);
            }

            // Save immediately at release. If the process is killed while the spring is
            // still visually settling, it will restore the correct logical page.
            saveStablePage();
        }

        private void advanceSpring(float dt) {
            if (dragging) return;

            // Critically-ish damped spring. Units are pages and seconds.
            float displacement = targetPage - virtualPosition;
            float acceleration = displacement * 38f - visualVelocity * 10.5f;
            visualVelocity += acceleration * dt;
            virtualPosition += visualVelocity * dt;

            // A tiny overshoot is allowed, but never let the visual state escape far.
            virtualPosition = clamp(virtualPosition, -0.16f, (pageCount - 1f) + 0.16f);

            if (Math.abs(targetPage - virtualPosition) < 0.0015f
                    && Math.abs(visualVelocity) < 0.008f) {
                virtualPosition = targetPage;
                visualVelocity = 0f;
            }
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

                advanceSpring(dt);

                canvas.drawColor(Color.rgb(5, 5, 8));

                // The background is intentionally very obvious: its phase is based on our
                // PERSISTENT virtual page position, not on raw touch dx.
                float shift = virtualPosition * w * 0.34f;
                paint.setShader(new LinearGradient(
                        -w * 0.70f - shift,
                        h * 0.08f,
                        w * 1.65f - shift,
                        h * 0.92f,
                        new int[]{
                                Color.rgb(0, 225, 255),
                                Color.rgb(62, 25, 255),
                                Color.rgb(255, 0, 185),
                                Color.rgb(255, 126, 0),
                                Color.rgb(0, 235, 205)
                        },
                        null,
                        Shader.TileMode.MIRROR));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(null);

                paint.setColor(Color.argb(92, 0, 0, 0));
                canvas.drawRect(0, 0, w, h, paint);

                // Large marker whose location is also tied to persistent virtualPosition.
                float frac = pageCount <= 1 ? 0f : virtualPosition / (pageCount - 1f);
                frac = clamp(frac, -0.08f, 1.08f);
                float markerX = w * (0.15f + frac * 0.70f);
                float markerY = h * 0.58f;
                float markerR = Math.min(w, h) * 0.050f;
                paint.setColor(Color.argb(185, 0, 0, 0));
                canvas.drawCircle(markerX, markerY, markerR * 1.25f, paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(markerX, markerY, markerR, paint);

                // Page rail.
                float railY = h * 0.71f;
                float railLeft = w * 0.16f;
                float railRight = w * 0.84f;
                paint.setStrokeWidth(Math.max(5f, w / 150f));
                paint.setColor(Color.argb(120, 255, 255, 255));
                canvas.drawLine(railLeft, railY, railRight, railY, paint);

                for (int i = 0; i < pageCount; i++) {
                    float t = pageCount == 1 ? 0.5f : i / (float) (pageCount - 1);
                    float x = railLeft + (railRight - railLeft) * t;
                    float r = i == targetPage ? w * 0.021f : w * 0.012f;
                    paint.setColor(i == targetPage ? Color.WHITE : Color.argb(170, 255, 255, 255));
                    canvas.drawCircle(x, railY, r, paint);
                }

                // Diagnostic panel.
                float margin = w * 0.045f;
                float panelTop = h * 0.055f;
                float panelBottom = h * 0.39f;
                paint.setColor(Color.argb(220, 0, 0, 0));
                canvas.drawRoundRect(margin, panelTop, w - margin, panelBottom, 30f, 30f, paint);

                float left = margin * 1.45f;
                float y = panelTop + h * 0.052f;
                float line = h * 0.043f;

                paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                paint.setTextSize(Math.max(30f, w * 0.052f));
                canvas.drawText("Virtual Page Engine v0.3", left, y, paint);
                paint.setFakeBoldText(false);

                y += line * 1.25f;
                paint.setTextSize(Math.max(21f, w * 0.033f));

                paint.setColor(Color.rgb(130, 255, 170));
                canvas.drawText(String.format(Locale.US,
                        "VIRTUAL  %.3f   TARGET %d", virtualPosition, targetPage + 1), left, y, paint);

                y += line;
                paint.setColor(Color.rgb(180, 220, 255));
                canvas.drawText(String.format(Locale.US,
                        "SAVED    PAGE %d / %d", currentPage + 1, pageCount), left, y, paint);

                y += line;
                paint.setColor(Color.rgb(230, 230, 230));
                canvas.drawText(String.format(Locale.US,
                        "TOUCH    dx %.0f   vx %.0f", touchDx, touchVelocityX), left, y, paint);

                y += line;
                paint.setColor(Color.rgb(255, 225, 145));
                canvas.drawText(lastDecision, left, y, paint);

                y += line * 1.12f;
                paint.setFakeBoldText(true);
                paint.setColor(Color.rgb(130, 255, 170));
                canvas.drawText("PERSISTENCE ON ✓", left, y, paint);
                paint.setFakeBoldText(false);

                // Instruction at bottom.
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.max(19f, w * 0.030f));
                paint.setColor(Color.WHITE);
                canvas.drawText("Swipe pages → wait → open app/lock phone → return", w * 0.5f, h * 0.82f, paint);
                canvas.drawText("VIRTUAL and SAVED must stay on the same page", w * 0.5f, h * 0.86f, paint);

            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            if (visible) {
                handler.postDelayed(drawRunner, 16L);
            }
        }

        private int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
