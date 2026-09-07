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
    public static final String KEY_GRID_PRESET_REV = "grid_preset_rev";

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

    // v0.9 defaults copied from the user's calibrated 709x1536 screenshot:
    // X=164 px, Y=256 px, cell=175x176 px, gaps=75x117 px.
    public static final int DEFAULT_COLS = 4;
    public static final int DEFAULT_ROWS = 6;
    public static final float DEFAULT_X0 = 164f / 709f;
    public static final float DEFAULT_Y0 = 256f / 1536f;
    public static final float DEFAULT_CELL_WIDTH = 175f / 709f;
    public static final float DEFAULT_CELL_HEIGHT = 176f / 1536f;
    public static final float DEFAULT_GAP_X = 75f / 709f;
    public static final float DEFAULT_GAP_Y = 117f / 1536f;
    public static final float DEFAULT_GLASS_OPACITY = 0.36f;
    public static final int GRID_PRESET_REV = 11;

    // Legacy defaults.
    public static final float DEFAULT_STEP_X = 0.229f;
    public static final float DEFAULT_STEP_Y = 0.128f;
    public static final float DEFAULT_GLASS_SIZE = 0.151f;

    public static void ensureV06GridDefaults(SharedPreferences prefs, int screenW, int screenH) {
        // v0.11: the user supplied the calibrated 709x1536 geometry explicitly.
        // Older builds could keep migrated v0.5/v0.6 values in SharedPreferences, so
        // merely changing DEFAULT_* did not actually change an existing install.
        // Force the requested geometry ONCE on upgrade, without touching cell/app assignments.
        if (prefs.getInt(KEY_GRID_PRESET_REV, 0) < GRID_PRESET_REV) {
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
                    .putInt(KEY_GRID_PRESET_REV, GRID_PRESET_REV)
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
