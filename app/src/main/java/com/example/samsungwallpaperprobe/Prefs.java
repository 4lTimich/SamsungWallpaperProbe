package com.example.samsungwallpaperprobe;

import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    public static final String PREFS = "virtual_page_engine";

    public static final String KEY_PAGE_COUNT = "page_count";
    public static final String KEY_SAVED_PAGE = "saved_page";
    public static final String KEY_CONFIG_GENERATION = "config_generation";
    public static final String KEY_SWIPE_SENSITIVITY = "swipe_sensitivity";
    public static final String KEY_SHOW_DEBUG = "show_debug";

    // v0.12 only: v0.11 overwrote the user's geometry with a bad preset.
    // Restore the exact v0.8 geometry once, then leave the grid alone forever.
    public static final String KEY_V08_GRID_RESTORE_DONE = "v08_grid_restore_done";

    public static final String KEY_GRID_COLS = "grid_cols";
    public static final String KEY_GRID_ROWS = "grid_rows";
    public static final String KEY_GRID_X0 = "grid_x0";
    public static final String KEY_GRID_Y0 = "grid_y0";

    // v0.6 geometry: independent cell width/height and true edge-to-edge gaps.
    public static final String KEY_CELL_WIDTH = "cell_width";
    public static final String KEY_CELL_HEIGHT = "cell_height";
    public static final String KEY_GAP_X = "gap_x";
    public static final String KEY_GAP_Y = "gap_y";

    public static final String KEY_GLASS_OPACITY = "glass_opacity";
    public static final String KEY_EDITOR_SCREENSHOT_URI = "editor_screenshot_uri";

    // Legacy v0.5 keys kept only for one-time migration.
    public static final String KEY_GRID_STEP_X = "grid_step_x";
    public static final String KEY_GRID_STEP_Y = "grid_step_y";
    public static final String KEY_GLASS_SIZE = "glass_size";

    // Defaults tuned to the user's 709x1536 / 4-column One UI screenshot.
    public static final int DEFAULT_COLS = 4;
    public static final int DEFAULT_ROWS = 6;
    public static final float DEFAULT_X0 = 0.154f; // center of first cell, normalized by screen width
    public static final float DEFAULT_Y0 = 0.109f; // center of first cell, normalized by screen height

    // Same visual geometry as v0.5, expressed as width/height + edge gaps.
    public static final float DEFAULT_CELL_WIDTH = 0.151f;
    public static final float DEFAULT_CELL_HEIGHT = 0.06972f; // 0.151 * 709 / 1536
    public static final float DEFAULT_GAP_X = 0.078f;         // 0.229 - 0.151
    public static final float DEFAULT_GAP_Y = 0.05828f;      // 0.128 - 0.06972
    public static final float DEFAULT_GLASS_OPACITY = 0.36f;

    // Legacy defaults.
    public static final float DEFAULT_STEP_X = 0.229f;
    public static final float DEFAULT_STEP_Y = 0.128f;
    public static final float DEFAULT_GLASS_SIZE = 0.151f;

    public static void ensureV06GridDefaults(SharedPreferences prefs, int screenW, int screenH) {
        // Existing v0.11 installs already have the incorrect geometry persisted, so merely
        // restoring the old constants is not enough. Reset geometry ONCE to v0.8 values.
        // Cell/app assignments and screenshot URI are intentionally preserved.
        if (!prefs.getBoolean(KEY_V08_GRID_RESTORE_DONE, false)) {
            prefs.edit()
                    .putInt(KEY_GRID_COLS, DEFAULT_COLS)
                    .putInt(KEY_GRID_ROWS, DEFAULT_ROWS)
                    .putFloat(KEY_GRID_X0, DEFAULT_X0)
                    .putFloat(KEY_GRID_Y0, DEFAULT_Y0)
                    .putFloat(KEY_CELL_WIDTH, DEFAULT_CELL_WIDTH)
                    .putFloat(KEY_CELL_HEIGHT, DEFAULT_CELL_HEIGHT)
                    .putFloat(KEY_GAP_X, DEFAULT_GAP_X)
                    .putFloat(KEY_GAP_Y, DEFAULT_GAP_Y)
                    .putFloat(KEY_GLASS_OPACITY, DEFAULT_GLASS_OPACITY)
                    .putBoolean(KEY_V08_GRID_RESTORE_DONE, true)
                    .apply();
            return;
        }

        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;

        if (!prefs.contains(KEY_CELL_WIDTH) || !prefs.contains(KEY_CELL_HEIGHT)
                || !prefs.contains(KEY_GAP_X) || !prefs.contains(KEY_GAP_Y)) {

            float oldSize = prefs.getFloat(KEY_GLASS_SIZE, DEFAULT_GLASS_SIZE);
            float oldStepX = prefs.getFloat(KEY_GRID_STEP_X, DEFAULT_STEP_X);
            float oldStepY = prefs.getFloat(KEY_GRID_STEP_Y, DEFAULT_STEP_Y);

            float width = oldSize;
            float height = oldSize * Math.max(1, screenW) / (float) Math.max(1, screenH);
            float gapX = Math.max(0.002f, oldStepX - width);
            float gapY = Math.max(0.002f, oldStepY - height);

            if (!prefs.contains(KEY_CELL_WIDTH)) e.putFloat(KEY_CELL_WIDTH, width);
            if (!prefs.contains(KEY_CELL_HEIGHT)) e.putFloat(KEY_CELL_HEIGHT, height);
            if (!prefs.contains(KEY_GAP_X)) e.putFloat(KEY_GAP_X, gapX);
            if (!prefs.contains(KEY_GAP_Y)) e.putFloat(KEY_GAP_Y, gapY);
            changed = true;
        }

        if (changed) e.apply();
    }

    public static float stepX(SharedPreferences prefs) {
        return prefs.getFloat(KEY_CELL_WIDTH, DEFAULT_CELL_WIDTH)
                + prefs.getFloat(KEY_GAP_X, DEFAULT_GAP_X);
    }

    public static float stepY(SharedPreferences prefs) {
        return prefs.getFloat(KEY_CELL_HEIGHT, DEFAULT_CELL_HEIGHT)
                + prefs.getFloat(KEY_GAP_Y, DEFAULT_GAP_Y);
    }

    public static String cellKey(int page, int row, int col) {
        return "cell_" + page + "_" + row + "_" + col;
    }
}
