package com.example.samsungwallpaperprobe;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Locale;

public class ProbeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ProbeEngine();
    }

    private final class ProbeEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean visible = false;
        private float xOffset = 0.5f;
        private float yOffset = 0.5f;
        private float xOffsetStep = 0f;
        private int xPixelOffset = 0;
        private long eventCount = 0;
        private long lastOffsetEventMs = 0;

        private final Runnable drawRunner = new Runnable() {
            @Override
            public void run() {
                drawFrame();
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (visible) {
                drawFrame();
            } else {
                handler.removeCallbacks(drawRunner);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            handler.removeCallbacks(drawRunner);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacks(drawRunner);
        }

        @Override
        public void onOffsetsChanged(
                float newXOffset,
                float newYOffset,
                float newXOffsetStep,
                float newYOffsetStep,
                int newXPixelOffset,
                int newYPixelOffset) {

            boolean changed = Math.abs(newXOffset - xOffset) > 0.0001f
                    || newXPixelOffset != xPixelOffset;

            xOffset = newXOffset;
            yOffset = newYOffset;
            xOffsetStep = newXOffsetStep;
            xPixelOffset = newXPixelOffset;

            if (changed) {
                eventCount++;
                lastOffsetEventMs = SystemClock.uptimeMillis();
            }

            drawFrame();
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

                canvas.drawColor(Color.rgb(5, 5, 8));

                // Moving iridescent field. If One UI sends page offsets, this shifts visibly.
                float travel = w * 0.8f;
                float shift = (xOffset - 0.5f) * travel;

                paint.setShader(new LinearGradient(
                        -w * 0.45f + shift,
                        h * 0.15f,
                        w * 1.45f + shift,
                        h * 0.85f,
                        new int[]{
                                Color.rgb(0, 220, 255),
                                Color.rgb(70, 30, 255),
                                Color.rgb(255, 0, 190),
                                Color.rgb(255, 120, 0),
                                Color.rgb(0, 235, 210)
                        },
                        null,
                        Shader.TileMode.MIRROR));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(null);

                // Dark transparent overlay so text is always readable.
                paint.setColor(Color.argb(85, 0, 0, 0));
                canvas.drawRect(0, 0, w, h, paint);

                // Grid: also shifts with offset.
                paint.setStrokeWidth(Math.max(2f, w / 540f));
                paint.setColor(Color.argb(100, 255, 255, 255));
                float grid = Math.max(90f, w / 5f);
                float gridShift = shift % grid;
                for (float x = -grid + gridShift; x < w + grid; x += grid) {
                    canvas.drawLine(x, 0, x, h, paint);
                }

                // Big moving circle: easiest visual proof that xOffset changes.
                float radius = Math.min(w, h) * 0.115f;
                float cx = w * (0.15f + 0.70f * clamp01(xOffset));
                float cy = h * 0.55f;

                paint.setColor(Color.argb(220, 0, 0, 0));
                canvas.drawCircle(cx, cy, radius * 1.10f, paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setColor(Color.BLACK);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(radius * 0.34f);
                canvas.drawText("OFFSET", cx, cy + radius * 0.12f, paint);

                // Readout panel.
                float margin = w * 0.055f;
                float top = h * 0.10f;
                paint.setColor(Color.argb(205, 0, 0, 0));
                canvas.drawRoundRect(
                        margin,
                        top,
                        w - margin,
                        top + h * 0.25f,
                        32f,
                        32f,
                        paint);

                paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(Color.WHITE);
                paint.setTextSize(Math.max(42f, w * 0.072f));
                paint.setFakeBoldText(true);
                canvas.drawText(String.format(Locale.US, "xOffset  %.3f", xOffset),
                        margin * 1.45f,
                        top + h * 0.090f,
                        paint);

                paint.setFakeBoldText(false);
                paint.setTextSize(Math.max(24f, w * 0.038f));
                paint.setColor(Color.rgb(225, 225, 225));
                canvas.drawText(String.format(Locale.US,
                                "step %.3f   pixel %d   events %d",
                                xOffsetStep,
                                xPixelOffset,
                                eventCount),
                        margin * 1.45f,
                        top + h * 0.145f,
                        paint);

                boolean recentlyChanged = SystemClock.uptimeMillis() - lastOffsetEventMs < 900;
                paint.setColor(recentlyChanged
                        ? Color.rgb(130, 255, 170)
                        : Color.rgb(255, 230, 145));
                paint.setFakeBoldText(true);
                canvas.drawText(recentlyChanged
                                ? "ONE UI SENDS MOVEMENT ✓"
                                : "SWIPE BETWEEN HOME PAGES",
                        margin * 1.45f,
                        top + h * 0.205f,
                        paint);
                paint.setFakeBoldText(false);

            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            if (visible) {
                handler.postDelayed(drawRunner, 33L);
            }
        }

        private float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
