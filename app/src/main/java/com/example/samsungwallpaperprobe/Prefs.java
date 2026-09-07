package com.example.samsungwallpaperprobe;

public final class Prefs {
    private Prefs() {}

    public static final String PREFS = "virtual_page_engine";

    public static final String KEY_PAGE_COUNT = "page_count";
    public static final String KEY_SAVED_PAGE = "saved_page";
    public static final String KEY_CONFIG_GENERATION = "config_generation";
    public static final String KEY_SWIPE_SENSITIVITY = "swipe_sensitivity";
    public static final String KEY_SHOW_DEBUG = "show_debug";

    public static final String KEY_GRID_COLS = "grid_cols";
    public static final String KEY_GRID_ROWS = "grid_rows";
    public static final String KEY_GRID_X0 = "grid_x0";
    public static final String KEY_GRID_Y0 = "grid_y0";
    public static final String KEY_GRID_STEP_X = "grid_step_x";
    public static final String KEY_GRID_STEP_Y = "grid_step_y";
    public static final String KEY_GLASS_SIZE = "glass_size";
    public static final String KEY_GLASS_OPACITY = "glass_opacity";
    public static final String KEY_EDITOR_SCREENSHOT_URI = "editor_screenshot_uri";

    // Defaults tuned to the user's 709x1536 / 4-column One UI screenshot.
    public static final int DEFAULT_COLS = 4;
    public static final int DEFAULT_ROWS = 6;
    public static final float DEFAULT_X0 = 0.154f;
    public static final float DEFAULT_Y0 = 0.109f;
    public static final float DEFAULT_STEP_X = 0.229f;
    public static final float DEFAULT_STEP_Y = 0.128f;
    public static final float DEFAULT_GLASS_SIZE = 0.151f; // fraction of screen width
    public static final float DEFAULT_GLASS_OPACITY = 0.36f;

    public static String cellKey(int page, int row, int col) {
        return "cell_" + page + "_" + row + "_" + col;
    }
}
