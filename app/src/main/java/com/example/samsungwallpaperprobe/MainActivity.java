package com.example.samsungwallpaperprobe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView pagesValue;
    private TextView currentValue;
    private Button debugButton;
    private Button outlinesButton;
    private TextView trackerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE);
        Prefs.ensureV06GridDefaults(prefs,
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        ensureDefaults();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        if (!prefs.contains(Prefs.KEY_PAGE_COUNT)) e.putInt(Prefs.KEY_PAGE_COUNT, 4);
        if (!prefs.contains(Prefs.KEY_SAVED_PAGE)) e.putInt(Prefs.KEY_SAVED_PAGE, 0);
        if (!prefs.contains(Prefs.KEY_SHOW_DEBUG)) e.putBoolean(Prefs.KEY_SHOW_DEBUG, false);
        if (!prefs.contains(Prefs.KEY_SHOW_ICON_OUTLINES)) e.putBoolean(Prefs.KEY_SHOW_ICON_OUTLINES, true);
        if (!prefs.contains(Prefs.KEY_GRID_COLS)) e.putInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS);
        if (!prefs.contains(Prefs.KEY_GRID_ROWS)) e.putInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS);
        if (!prefs.contains(Prefs.KEY_GRID_X0)) e.putFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0);
        if (!prefs.contains(Prefs.KEY_GRID_Y0)) e.putFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0);
        if (!prefs.contains(Prefs.KEY_CELL_WIDTH)) e.putFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH);
        if (!prefs.contains(Prefs.KEY_CELL_HEIGHT)) e.putFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT);
        if (!prefs.contains(Prefs.KEY_GAP_X)) e.putFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X);
        if (!prefs.contains(Prefs.KEY_GAP_Y)) e.putFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y);
        if (!prefs.contains(Prefs.KEY_GLASS_OPACITY)) e.putFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY);
        e.apply();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, dp(40));
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Iridescent Dual Bounds Engine v0.20", 25f, Color.BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView body = text(
                "v0.20 отслеживает сразу две реальные иконки One UI: левую нижнюю и правую верхнюю. При движении берётся тот реальный bounds, который меньше запаздывает в текущем направлении, а кэш остальных иконок по-прежнему обновляется редко. " +
                "Сетка оставлена для старого стекла, но bounds-трекер от неё больше не зависит. Высокочастотные touch-события используются только как интерполяция между редкими реальными bounds — они не решают, какая страница выбрана.",
                16f, Color.DKGRAY, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(14), 0, dp(18));
        root.addView(body, matchWrap());

        trackerStatus = text("", 14.5f, Color.DKGRAY, true);
        trackerStatus.setGravity(Gravity.CENTER);
        trackerStatus.setPadding(0, dp(4), 0, dp(10));
        root.addView(trackerStatus, matchWrap());

        Button accessibility = new Button(this);
        accessibility.setText("ОТКРЫТЬ СЛУЖБУ ONE UI BOUNDS");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrap());

        Button rescan = new Button(this);
        rescan.setText("ПЕРЕСНЯТЬ ТЕКУЩУЮ СТРАНИЦУ ПРИ ВЫХОДЕ НА HOME");
        rescan.setOnClickListener(v -> {
            LauncherScrollBus.requestRescan();
            trackerStatus.setText("Пересъёмка текущей страницы запрошена. Вернись на рабочий стол — старый кэш этой страницы будет заменён, а не добавлен поверх.");
        });
        root.addView(rescan, matchWrap());

        TextView pagesLabel = text("Количество страниц", 17f, Color.BLACK, true);
        pagesLabel.setPadding(0, dp(18), 0, 0);
        root.addView(pagesLabel, matchWrap());
        pagesValue = text("", 28f, Color.BLACK, true);
        pagesValue.setGravity(Gravity.CENTER);
        root.addView(makeIntegerStepper(pagesValue, true), matchWrap());

        TextView currentLabel = text("Текущая сохранённая страница", 17f, Color.BLACK, true);
        currentLabel.setPadding(0, dp(14), 0, 0);
        root.addView(currentLabel, matchWrap());
        currentValue = text("", 28f, Color.BLACK, true);
        currentValue.setGravity(Gravity.CENTER);
        root.addView(makeIntegerStepper(currentValue, false), matchWrap());

        Button gridEditor = new Button(this);
        gridEditor.setText("РЕДАКТОР СЕТКИ И СТЕКЛА");
        gridEditor.setOnClickListener(v -> startActivity(new Intent(this, GridEditorActivity.class)));
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.setMargins(0, dp(18), 0, dp(8));
        root.addView(gridEditor, gridParams);

        debugButton = new Button(this);
        debugButton.setOnClickListener(v -> toggleDebug());
        root.addView(debugButton, matchWrap());

        outlinesButton = new Button(this);
        outlinesButton.setOnClickListener(v -> toggleOutlines());
        root.addView(outlinesButton, matchWrap());

        Button wallpaper = new Button(this);
        wallpaper.setText("ВЫБРАТЬ / ОБНОВИТЬ ЖИВЫЕ ОБОИ");
        wallpaper.setOnClickListener(v -> {
            bumpGeneration();
            openWallpaperPicker();
        });
        root.addView(wallpaper, matchWrap());

        TextView note = text(
                "Белая обводка всех найденных приложений работает независимо от режима отладки и независимо от сетки. Полный accessibility-tree сканируется при выходе на Home и при редкой смене anchor. В движении опрашивается только одна иконка. В отладке теперь отдельно видно POLL Hz и CHANGE Hz: если POLL ≈120, а CHANGE ≈20, значит ступенчатость идёт от частоты обновления bounds Samsung, а не от GPU.",
                13.5f, Color.DKGRAY, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());

        setContentView(scroll);
        refreshValues();
    }

    private LinearLayout makeIntegerStepper(TextView valueView, boolean pagesStepper) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = new Button(this);
        minus.setText("−");
        Button plus = new Button(this);
        plus.setText("+");

        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(84), dp(56));
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(120), dp(56));

        minus.setOnClickListener(v -> {
            if (pagesStepper) changePageCount(-1); else changeCurrentPage(-1);
        });
        plus.setOnClickListener(v -> {
            if (pagesStepper) changePageCount(1); else changeCurrentPage(1);
        });

        row.addView(minus, side);
        row.addView(valueView, center);
        row.addView(plus, side);
        return row;
    }

    private void changePageCount(int delta) {
        int oldCount = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int count = clamp(oldCount + delta, 2, 9);
        int current = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, count - 1);
        prefs.edit()
                .putInt(Prefs.KEY_PAGE_COUNT, count)
                .putInt(Prefs.KEY_SAVED_PAGE, current)
                .apply();
        LauncherScrollBus.requestRescan();
        bumpGeneration();
    }

    private void changeCurrentPage(int delta) {
        int count = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int current = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0) + delta, 0, count - 1);
        prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, current).apply();
        LauncherScrollBus.authoritativePage = current;
        LauncherScrollBus.requestRescan();
        bumpGeneration();
    }

    private void toggleDebug() {
        boolean current = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);
        prefs.edit().putBoolean(Prefs.KEY_SHOW_DEBUG, !current).apply();
        refreshValues();
    }

    private void toggleOutlines() {
        boolean current = prefs.getBoolean(Prefs.KEY_SHOW_ICON_OUTLINES, true);
        prefs.edit().putBoolean(Prefs.KEY_SHOW_ICON_OUTLINES, !current).apply();
        refreshValues();
    }

    private void bumpGeneration() {
        int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, 0);
        prefs.edit().putInt(Prefs.KEY_CONFIG_GENERATION, generation + 1).apply();
        refreshValues();
    }

    private void refreshValues() {
        if (pagesValue == null) return;
        int count = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int page = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, count - 1);
        if (LauncherScrollBus.authoritativePage >= 0 && LauncherScrollBus.authoritativePage < count) {
            page = LauncherScrollBus.authoritativePage;
        }
        pagesValue.setText(String.valueOf(count));
        currentValue.setText((page + 1) + " / " + count);
        boolean debug = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);
        debugButton.setText(debug ? "ОТЛАДКА: ВКЛ" : "ОТЛАДКА: ВЫКЛ");
        boolean outlines = prefs.getBoolean(Prefs.KEY_SHOW_ICON_OUTLINES, true);
        if (outlinesButton != null) outlinesButton.setText(outlines ? "ОБВОДКА ВСЕХ ИКОНОК: ВКЛ" : "ОБВОДКА ВСЕХ ИКОНОК: ВЫКЛ");

        boolean enabled = isAccessibilityEnabled();
        if (!enabled) {
            trackerStatus.setText("Bounds-служба выключена. Включи её в специальных возможностях.");
            trackerStatus.setTextColor(Color.rgb(165, 55, 45));
        } else if (LauncherScrollBus.positionValid) {
            long age = Math.max(0L, android.os.SystemClock.uptimeMillis() - LauncherScrollBus.positionUptimeMs);
            trackerStatus.setText(String.format(Locale.US,
                    "BOUND OK: %s · page %d · poll %.1f Hz · real changes %.1f Hz · age %d ms · icons %d",
                    LauncherScrollBus.anchorLabel,
                    LauncherScrollBus.authoritativePage < 0 ? 0 : LauncherScrollBus.authoritativePage + 1,
                    LauncherScrollBus.trackerPollHz,
                    LauncherScrollBus.boundsChangeHz,
                    age,
                    LauncherScrollBus.iconSnapshot.length));
            trackerStatus.setTextColor(Color.rgb(20, 125, 65));
        } else {
            trackerStatus.setText("Служба включена. Вернись на Home — движок просканирует иконки и сам выберет anchor.");
            trackerStatus.setTextColor(Color.DKGRAY);
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String expected = getPackageName() + "/" + LauncherAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            String id = info.getResolveInfo().serviceInfo.packageName + "/" + info.getResolveInfo().serviceInfo.name;
            if (expected.equals(id)) return true;
        }
        return false;
    }

    private void openWallpaperPicker() {
        ComponentName component = new ComponentName(this, ProbeWallpaperService.class);
        Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        direct.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(direct);
        } catch (Exception ignored) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
