package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class GlassGridView extends View {

    public interface CellTapListener {
        void onCellTapped(int row, int col);
    }

    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final PackageManager pm;
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private CellTapListener listener;
    private Bitmap screenshot;
    private int editorPage = 0;

    public GlassGridView(Context context) {
        super(context);
        prefs = context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
        pm = context.getPackageManager();
        setBackgroundColor(Color.rgb(24, 28, 38));
        reloadScreenshot();
    }

    public void setCellTapListener(CellTapListener listener) {
        this.listener = listener;
    }

    public void setEditorPage(int page) {
        editorPage = Math.max(0, page);
        invalidate();
    }

    public void invalidateAssignments() {
        iconCache.clear();
        labelCache.clear();
        invalidate();
    }

    public void reloadScreenshot() {
        screenshot = null;
        String uriText = prefs.getString(Prefs.KEY_EDITOR_SCREENSHOT_URI, null);
        if (uriText != null && !uriText.isEmpty()) {
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uriText))) {
                screenshot = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
                screenshot = null;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        RectF content = getContentRect();
        if (screenshot != null) {
            canvas.drawColor(Color.BLACK);
            paint.setAlpha(255);
            canvas.drawBitmap(screenshot, null, content, paint);
        } else {
            paint.setShader(new LinearGradient(
                    content.left, content.top, content.right, content.bottom,
                    new int[]{Color.rgb(8, 28, 54), Color.rgb(56, 34, 116), Color.rgb(91, 24, 79)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(content, paint);
            paint.setShader(null);
        }

        int cols = prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS);
        int rows = prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS);
        float x0 = prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0);
        float y0 = prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0);
        float sx = prefs.getFloat(Prefs.KEY_GRID_STEP_X, Prefs.DEFAULT_STEP_X);
        float sy = prefs.getFloat(Prefs.KEY_GRID_STEP_Y, Prefs.DEFAULT_STEP_Y);
        float glassSize = prefs.getFloat(Prefs.KEY_GLASS_SIZE, Prefs.DEFAULT_GLASS_SIZE);
        float opacity = prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY);

        float screenW = content.width();
        float screenH = content.height();
        float size = glassSize * screenW;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float cx = content.left + (x0 + col * sx) * screenW;
                float cy = content.top + (y0 + row * sy) * screenH;

                String pkg = prefs.getString(Prefs.cellKey(editorPage, row, col), null);
                boolean assigned = pkg != null && !pkg.isEmpty();

                if (assigned) {
                    drawGlass(canvas, cx, cy, size, opacity);
                    drawApp(canvas, pkg, cx, cy, size * 0.58f);
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, screenW * 0.003f));
                paint.setColor(assigned
                        ? Color.argb(235, 255, 255, 255)
                        : Color.argb(120, 255, 255, 255));
                RectF cell = new RectF(cx - size * 0.54f, cy - size * 0.54f,
                        cx + size * 0.54f, cy + size * 0.54f);
                canvas.drawRoundRect(cell, size * 0.18f, size * 0.18f, paint);
                paint.setStyle(Paint.Style.FILL);

                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.max(14f, screenW * 0.030f));
                paint.setFakeBoldText(true);
                paint.setColor(Color.argb(230, 255, 255, 255));
                String label = assigned ? shortLabel(pkg) : (row + 1) + "," + (col + 1);
                canvas.drawText(label, cx, cy + size * 0.76f, paint);
                paint.setFakeBoldText(false);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, screenW * 0.004f));
        paint.setColor(Color.argb(180, 255, 255, 255));
        canvas.drawRect(content, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private RectF getContentRect() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return new RectF(0, 0, 1, 1);

        float aspect = screenshot != null
                ? screenshot.getWidth() / (float) screenshot.getHeight()
                : 709f / 1536f;
        float viewAspect = w / h;
        if (viewAspect > aspect) {
            float targetW = h * aspect;
            float left = (w - targetW) * 0.5f;
            return new RectF(left, 0, left + targetW, h);
        } else {
            float targetH = w / aspect;
            float top = (h - targetH) * 0.5f;
            return new RectF(0, top, w, top + targetH);
        }
    }

    private void drawGlass(Canvas canvas, float cx, float cy, float size, float opacity) {
        float half = size * 0.5f;
        float radius = size * 0.25f;
        RectF rect = new RectF(cx - half, cy - half, cx + half, cy + half);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (65 * opacity), 0, 0, 0));
        canvas.drawRoundRect(new RectF(rect.left + size * 0.03f, rect.top + size * 0.045f,
                rect.right + size * 0.03f, rect.bottom + size * 0.045f), radius, radius, paint);

        paint.setShader(new LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                new int[]{
                        Color.argb((int) (92 * opacity), 255, 255, 255),
                        Color.argb((int) (28 * opacity), 235, 242, 248),
                        Color.argb((int) (18 * opacity), 120, 130, 145),
                        Color.argb((int) (56 * opacity), 255, 255, 255)
                },
                new float[]{0f, 0.34f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, size * 0.045f));
        paint.setColor(Color.argb((int) (205 * opacity), 255, 255, 255));
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStrokeWidth(Math.max(1f, size * 0.018f));
        paint.setColor(Color.argb((int) (110 * opacity), 42, 48, 58));
        RectF inner = new RectF(rect.left + size * 0.055f, rect.top + size * 0.055f,
                rect.right - size * 0.055f, rect.bottom - size * 0.055f);
        canvas.drawRoundRect(inner, radius * 0.78f, radius * 0.78f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawApp(Canvas canvas, String packageName, float cx, float cy, float size) {
        Bitmap bitmap = iconCache.get(packageName);
        if (bitmap == null && !iconCache.containsKey(packageName)) {
            try {
                Drawable d = pm.getApplicationIcon(packageName);
                bitmap = drawableToBitmap(d, 160, 160);
            } catch (Exception ignored) {
                bitmap = null;
            }
            iconCache.put(packageName, bitmap);
        }
        if (bitmap != null) {
            RectF dst = new RectF(cx - size * 0.5f, cy - size * 0.5f,
                    cx + size * 0.5f, cy + size * 0.5f);
            paint.setAlpha(235);
            canvas.drawBitmap(bitmap, null, dst, paint);
            paint.setAlpha(255);
        }
    }

    private String shortLabel(String packageName) {
        String cached = labelCache.get(packageName);
        if (cached != null) return cached;
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            String label = pm.getApplicationLabel(info).toString();
            if (label.length() > 11) label = label.substring(0, 10) + "…";
            labelCache.put(packageName, label);
            return label;
        } catch (Exception ignored) {
            String label = packageName;
            int dot = label.lastIndexOf('.');
            if (dot >= 0 && dot < label.length() - 1) label = label.substring(dot + 1);
            if (label.length() > 11) label = label.substring(0, 10) + "…";
            labelCache.put(packageName, label);
            return label;
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;

        RectF content = getContentRect();
        if (!content.contains(event.getX(), event.getY())) return true;

        int cols = prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS);
        int rows = prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS);
        float x0 = prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0);
        float y0 = prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0);
        float sx = prefs.getFloat(Prefs.KEY_GRID_STEP_X, Prefs.DEFAULT_STEP_X);
        float sy = prefs.getFloat(Prefs.KEY_GRID_STEP_Y, Prefs.DEFAULT_STEP_Y);
        float glassSize = prefs.getFloat(Prefs.KEY_GLASS_SIZE, Prefs.DEFAULT_GLASS_SIZE);

        float nx = (event.getX() - content.left) / content.width();
        float ny = (event.getY() - content.top) / content.height();
        float halfX = Math.max(glassSize * 0.62f, sx * 0.42f);
        float halfY = Math.max((glassSize * content.width() / content.height()) * 0.62f, sy * 0.42f);

        int bestRow = -1;
        int bestCol = -1;
        float bestDist = Float.MAX_VALUE;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float cx = x0 + col * sx;
                float cy = y0 + row * sy;
                if (Math.abs(nx - cx) <= halfX && Math.abs(ny - cy) <= halfY) {
                    float dx = nx - cx;
                    float dy = ny - cy;
                    float dist = dx * dx + dy * dy;
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestRow = row;
                        bestCol = col;
                    }
                }
            }
        }

        if (bestRow >= 0 && listener != null) {
            listener.onCellTapped(bestRow, bestCol);
        }
        return true;
    }
}
