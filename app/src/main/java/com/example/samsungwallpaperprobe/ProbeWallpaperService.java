package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
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
        return new ProbeEngine();
    }

    private final class ProbeEngine extends Engine implements SensorEventListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean visible = false;

        // Launcher offset probe
        private float xOffset = 0.5f;
        private float xOffsetStep = 0f;
        private int xPixelOffset = 0;
        private long offsetEventCount = 0;
        private long lastOffsetChangeMs = 0;

        // Raw touch probe
        private long touchEventCount = 0;
        private long lastTouchMs = 0;
        private String touchAction = "WAITING";
        private float touchX = 0f;
        private float touchY = 0f;
        private float touchDownX = 0f;
        private float touchDownY = 0f;
        private float touchDx = 0f;
        private float touchDy = 0f;
        private float touchVelocityX = 0f;
        private long prevMoveMs = 0;
        private float prevMoveX = 0f;

        // Sensor probe
        private SensorManager sensorManager;
        private Sensor rotationSensor;
        private long sensorEventCount = 0;
        private long lastSensorMs = 0;
        private float tiltX = 0f;
        private float tiltY = 0f;

        // Other wallpaper callbacks
        private float zoom = 0f;
        private long zoomEventCount = 0;
        private String lastCommand = "none";
        private long commandEventCount = 0;

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
            setTouchEventsEnabled(true);

            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (rotationSensor == null) {
                    rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (visible) {
                startSensors();
                drawFrame();
            } else {
                stopSensors();
                handler.removeCallbacks(drawRunner);
            }
        }

        private void startSensors() {
            if (sensorManager != null && rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        }

        private void stopSensors() {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
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
            stopSensors();
            handler.removeCallbacks(drawRunner);
        }

        @Override
        public void onDestroy() {
            stopSensors();
            handler.removeCallbacks(drawRunner);
            super.onDestroy();
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
            xOffsetStep = newXOffsetStep;
            xPixelOffset = newXPixelOffset;
            offsetEventCount++;

            if (changed) {
                lastOffsetChangeMs = SystemClock.uptimeMillis();
            }
            drawFrame();
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            long now = SystemClock.uptimeMillis();
            touchEventCount++;
            lastTouchMs = now;
            touchX = event.getX();
            touchY = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchAction = "DOWN";
                    touchDownX = touchX;
                    touchDownY = touchY;
                    touchDx = 0f;
                    touchDy = 0f;
                    touchVelocityX = 0f;
                    prevMoveX = touchX;
                    prevMoveMs = now;
                    break;
                case MotionEvent.ACTION_MOVE:
                    touchAction = "MOVE";
                    touchDx = touchX - touchDownX;
                    touchDy = touchY - touchDownY;
                    long dt = now - prevMoveMs;
                    if (dt > 0) {
                        float instantVx = (touchX - prevMoveX) * 1000f / dt;
                        touchVelocityX = touchVelocityX * 0.65f + instantVx * 0.35f;
                    }
                    prevMoveX = touchX;
                    prevMoveMs = now;
                    break;
                case MotionEvent.ACTION_UP:
                    touchAction = "UP";
                    touchDx = touchX - touchDownX;
                    touchDy = touchY - touchDownY;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    touchAction = "CANCEL";
                    break;
                default:
                    touchAction = "OTHER";
            }
            drawFrame();
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR
                    || event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                float[] rotation = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                SensorManager.getOrientation(rotation, orientation);

                // roll and pitch mapped into roughly -1..1 for a convenient visual probe
                tiltX = clamp(orientation[2] / 0.75f, -1f, 1f);
                tiltY = clamp(-orientation[1] / 0.75f, -1f, 1f);
                sensorEventCount++;
                lastSensorMs = SystemClock.uptimeMillis();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Nothing needed for this probe.
        }

        @Override
        public void onZoomChanged(float newZoom) {
            super.onZoomChanged(newZoom);
            zoom = newZoom;
            zoomEventCount++;
            drawFrame();
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            lastCommand = action == null ? "null" : action;
            commandEventCount++;
            drawFrame();
            return super.onCommand(action, x, y, z, extras, resultRequested);
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
                final long now = SystemClock.uptimeMillis();

                canvas.drawColor(Color.rgb(5, 5, 8));

                // If touch is delivered, the field follows the horizontal swipe directly.
                float touchNorm = w > 0 ? clamp(touchDx / w, -1f, 1f) : 0f;
                float shift = touchNorm * w * 0.55f + tiltX * w * 0.12f;

                paint.setShader(new LinearGradient(
                        -w * 0.55f + shift,
                        h * (0.10f + 0.06f * tiltY),
                        w * 1.55f + shift,
                        h * (0.90f + 0.06f * tiltY),
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

                paint.setColor(Color.argb(95, 0, 0, 0));
                canvas.drawRect(0, 0, w, h, paint);

                // Sensor dot: should move when the phone is tilted.
                float dotR = Math.min(w, h) * 0.045f;
                float dotX = w * 0.5f + tiltX * w * 0.30f;
                float dotY = h * 0.58f + tiltY * h * 0.17f;
                paint.setColor(Color.argb(210, 0, 0, 0));
                canvas.drawCircle(dotX, dotY, dotR * 1.25f, paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(dotX, dotY, dotR, paint);

                // Touch trail / displacement line.
                boolean touchRecent = now - lastTouchMs < 1200;
                if (touchRecent) {
                    paint.setStrokeWidth(Math.max(8f, w / 90f));
                    paint.setColor(Color.argb(220, 255, 255, 255));
                    canvas.drawLine(touchDownX, h * 0.78f, touchX, h * 0.78f, paint);
                    canvas.drawCircle(touchX, h * 0.78f, Math.max(14f, w / 45f), paint);
                }

                float margin = w * 0.045f;
                float panelTop = h * 0.055f;
                float panelBottom = h * 0.45f;
                paint.setColor(Color.argb(215, 0, 0, 0));
                canvas.drawRoundRect(margin, panelTop, w - margin, panelBottom, 30f, 30f, paint);

                float left = margin * 1.45f;
                float y = panelTop + h * 0.055f;
                float line = h * 0.043f;

                paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                paint.setTextSize(Math.max(32f, w * 0.055f));
                canvas.drawText("Samsung Probe v0.2", left, y, paint);
                paint.setFakeBoldText(false);

                y += line * 1.25f;
                paint.setTextSize(Math.max(22f, w * 0.034f));

                boolean offsetRecent = now - lastOffsetChangeMs < 1200;
                paint.setColor(offsetRecent ? Color.rgb(130, 255, 170) : Color.rgb(255, 210, 130));
                canvas.drawText(String.format(Locale.US,
                        "OFFSET  x %.3f  step %.3f  px %d  calls %d",
                        xOffset, xOffsetStep, xPixelOffset, offsetEventCount), left, y, paint);

                y += line;
                paint.setColor(touchRecent ? Color.rgb(130, 255, 170) : Color.rgb(255, 210, 130));
                canvas.drawText(String.format(Locale.US,
                        "TOUCH   %s  dx %.0f  vx %.0f  calls %d",
                        touchAction, touchDx, touchVelocityX, touchEventCount), left, y, paint);

                y += line;
                boolean sensorRecent = now - lastSensorMs < 500;
                paint.setColor(sensorRecent ? Color.rgb(130, 255, 170) : Color.rgb(255, 210, 130));
                canvas.drawText(String.format(Locale.US,
                        "GYRO    x %.2f  y %.2f  calls %d",
                        tiltX, tiltY, sensorEventCount), left, y, paint);

                y += line;
                paint.setColor(Color.rgb(220, 220, 220));
                canvas.drawText(String.format(Locale.US,
                        "ZOOM    %.3f  calls %d", zoom, zoomEventCount), left, y, paint);

                y += line;
                String shortCommand = lastCommand;
                if (shortCommand.length() > 27) {
                    shortCommand = shortCommand.substring(shortCommand.length() - 27);
                }
                canvas.drawText(String.format(Locale.US,
                        "COMMAND %s  calls %d", shortCommand, commandEventCount), left, y, paint);

                y += line * 1.2f;
                paint.setFakeBoldText(true);
                if (touchRecent) {
                    paint.setColor(Color.rgb(130, 255, 170));
                    canvas.drawText("SWIPE INPUT FOUND ✓", left, y, paint);
                } else if (sensorRecent) {
                    paint.setColor(Color.rgb(160, 215, 255));
                    canvas.drawText("TILT INPUT FOUND ✓ — NOW SWIPE", left, y, paint);
                } else {
                    paint.setColor(Color.rgb(255, 230, 145));
                    canvas.drawText("SWIPE HOME PAGES + TILT PHONE", left, y, paint);
                }
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

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
