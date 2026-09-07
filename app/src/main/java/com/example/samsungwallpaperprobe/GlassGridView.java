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
        int sw = context.getResources().getDisplayMetrics().widthPixels;
        int sh = context.getResources().getDisplayMetrics().heightPixels;
        Prefs.ensureV06GridDefaults(prefs, sw, sh);
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
        float cellW = prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH);
        float cellH = prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT);
        float gapX = prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X);
        float gapY = prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y);
        float opacity = prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY);

        float stepX = cellW + gapX;
        float stepY = cellH + gapY;
        float screenW = content.width();
        float screenH = content.height();
        float pxW = cellW * screenW;
        float pxH = cellH * screenH;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float cx = content.left + (x0 + col * stepX) * screenW;
                float cy = content.top + (y0 + row * stepY) * screenH;

                String pkg = prefs.getString(Prefs.cellKey(editorPage, row, col), null);
                boolean assigned = pkg != null && !pkg.isEmpty();

                if (assigned) {
                    drawGlass(canvas, cx, cy, pxW, pxH, opacity);
                    // With a screenshot loaded, drawing the app icon again makes calibration
                    // look doubled and confusing. Only draw the app icon in the blank preview.
                    if (screenshot == null) {
                        drawApp(canvas, pkg, cx, cy, Math.min(pxW, pxH) * 0.58f);
                    }
                }

                float radius = Math.min(pxW, pxH) * 0.24f;
                RectF cell = new RectF(cx - pxW * 0.5f, cy - pxH * 0.5f,
                        cx + pxW * 0.5f, cy + pxH * 0.5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, screenW * 0.0026f));
                paint.setColor(assigned
                        ? Color.argb(240, 255, 255, 255)
                        : Color.argb(120, 255, 255, 255));
                canvas.drawRoundRect(cell, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);

                // Small coordinate badge inside the cell instead of text under the icon.
                String coord = (row + 1) + "," + (col + 1);
                float textSize = Math.max(10f, screenW * 0.020f);
                paint.setTextSize(textSize);
                paint.setFakeBoldText(true);
                paint.setTextAlign(Paint.Align.LEFT);
                float badgePad = textSize * 0.35f;
                float tw = paint.measureText(coord);
                float bx = cell.left + badgePad;
                float by = cell.top + textSize * 1.15f;
                paint.setColor(Color.argb(155, 0, 0, 0));
                canvas.drawRoundRect(new RectF(
                        bx - badgePad * 0.5f,
                        cell.top + badgePad * 0.35f,
                        bx + tw + badgePad * 0.75f,
                        by + badgePad * 0.45f),
                        badgePad, badgePad, paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(coord, bx, by, paint);
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

    private void drawGlass(Canvas canvas, float cx, float cy, float width, float height, float opacity) {
        float radius = Math.min(width, height) * 0.25f;
        RectF rect = new RectF(cx - width * 0.5f, cy - height * 0.5f,
                cx + width * 0.5f, cy + height * 0.5f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (65 * opacity), 0, 0, 0));
        canvas.drawRoundRect(new RectF(rect.left + width * 0.03f, rect.top + height * 0.045f,
                rect.right + width * 0.03f, rect.bottom + height * 0.045f), radius, radius, paint);

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

        float min = Math.min(width, height);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, min * 0.045f));
        paint.setColor(Color.argb((int) (205 * opacity), 255, 255, 255));
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStrokeWidth(Math.max(1f, min * 0.018f));
        paint.setColor(Color.argb((int) (110 * opacity), 42, 48, 58));
        RectF inner = new RectF(rect.left + min * 0.055f, rect.top + min * 0.055f,
                rect.right - min * 0.055f, rect.bottom - min * 0.055f);
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

    @SuppressWarnings("unused")
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
            return packageName;
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
        float cellW = prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH);
        float cellH = prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT);
        float stepX = cellW + prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X);
        float stepY = cellH + prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y);

        float nx = (event.getX() - content.left) / content.width();
        float ny = (event.getY() - content.top) / content.height();
        float halfX = cellW * 0.58f;
        float halfY = cellH * 0.58f;

        int bestRow = -1;
        int bestCol = -1;
        float bestDist = Float.MAX_VALUE;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float cx = x0 + col * stepX;
                float cy = y0 + row * stepY;
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
