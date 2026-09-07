package com.example.samsungwallpaperprobe;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.provider.Settings;
import java.util.List;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView pagesValue;
    private TextView currentValue;
    private TextView sensitivityValue;
    private TextView startPhaseValue;
    private Button debugButton;
    private TextView realOffsetStatus;
    private EditText boundsAnchorEdit;
    private TextView boundsProbeStatus;

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
        refreshRealOffsetStatus();
        if (pagesValue != null) refreshValues();
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        if (!prefs.contains(Prefs.KEY_PAGE_COUNT)) e.putInt(Prefs.KEY_PAGE_COUNT, 4);
        if (!prefs.contains(Prefs.KEY_SAVED_PAGE)) e.putInt(Prefs.KEY_SAVED_PAGE, 0);
        if (!prefs.contains(Prefs.KEY_SWIPE_SENSITIVITY)) e.putFloat(Prefs.KEY_SWIPE_SENSITIVITY, 1.80f);
        if (!prefs.contains(Prefs.KEY_SHOW_DEBUG)) e.putBoolean(Prefs.KEY_SHOW_DEBUG, false);
        if (!prefs.contains(Prefs.KEY_GLASS_START_PHASE_PX)) e.putFloat(Prefs.KEY_GLASS_START_PHASE_PX, Prefs.DEFAULT_GLASS_START_PHASE_PX);
        if (!prefs.contains(Prefs.KEY_BOUNDS_ANCHOR_LABEL)) e.putString(Prefs.KEY_BOUNDS_ANCHOR_LABEL, Prefs.DEFAULT_BOUNDS_ANCHOR_LABEL);
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

        TextView title = text("Iridescent Wallpaper Lab v0.16", 25f, Color.BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView body = text(
                "v0.16 — отдельный эксперимент с boundsInScreen. Сетку и текущую физику стекла здесь не меняю. Служба пытается найти одну настоящую иконку One UI и читать её реальные координаты на экране во время свайпа.",
                16f, Color.DKGRAY, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(14), 0, dp(20));
        root.addView(body, matchWrap());

        root.addView(text("Количество страниц", 17f, Color.BLACK, true), matchWrap());
        pagesValue = text("", 28f, Color.BLACK, true);
        pagesValue.setGravity(Gravity.CENTER);
        root.addView(makeIntegerStepper(pagesValue, true), matchWrap());

        TextView currentLabel = text("Текущая страница", 17f, Color.BLACK, true);
        currentLabel.setPadding(0, dp(14), 0, 0);
        root.addView(currentLabel, matchWrap());
        currentValue = text("", 28f, Color.BLACK, true);
        currentValue.setGravity(Gravity.CENTER);
        root.addView(makeIntegerStepper(currentValue, false), matchWrap());

        TextView sensLabel = text("Чувствительность движения фона", 17f, Color.BLACK, true);
        sensLabel.setPadding(0, dp(18), 0, 0);
        root.addView(sensLabel, matchWrap());
        sensitivityValue = text("", 27f, Color.BLACK, true);
        sensitivityValue.setGravity(Gravity.CENTER);
        root.addView(makeSensitivityStepper(), matchWrap());

        TextView phaseLabel = text("Компенсация старта стекла", 17f, Color.BLACK, true);
        phaseLabel.setPadding(0, dp(16), 0, 0);
        root.addView(phaseLabel, matchWrap());
        startPhaseValue = text("", 27f, Color.BLACK, true);
        startPhaseValue.setGravity(Gravity.CENTER);
        root.addView(makeStartPhaseStepper(), matchWrap());
        TextView phaseHint = text("Если стекло на старте уезжает вперёд — увеличь на 1 px. Если отстаёт — уменьши. Это не меняет сетку и не влияет на движение после старта.", 13.5f, Color.DKGRAY, false);
        phaseHint.setGravity(Gravity.CENTER);
        phaseHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(phaseHint, matchWrap());

        realOffsetStatus = text("", 15f, Color.DKGRAY, true);
        realOffsetStatus.setGravity(Gravity.CENTER);
        realOffsetStatus.setPadding(0, dp(18), 0, dp(6));
        root.addView(realOffsetStatus, matchWrap());

        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("ВКЛЮЧИТЬ РЕАЛЬНЫЙ OFFSET ONE UI");
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibilityButton, matchWrap());

        TextView accessibilityHint = text(
                "В системных настройках включи службу ‘One UI Bounds Probe’. В v0.16 службе нужен доступ к содержимому окна One UI Home, чтобы получить boundsInScreen выбранной иконки. Код отбрасывает root, если активное окно НЕ com.sec.android.app.launcher, дерево других приложений не обходится, INTERNET-разрешения у приложения нет. После обновления службу лучше выключить и включить заново.",
                13.5f, Color.DKGRAY, false);
        accessibilityHint.setGravity(Gravity.CENTER);
        accessibilityHint.setPadding(0, dp(6), 0, dp(8));
        root.addView(accessibilityHint, matchWrap());

        TextView boundsTitle = text("Эксперимент: boundsInScreen", 18f, Color.BLACK, true);
        boundsTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams boundsTitleParams = matchWrap();
        boundsTitleParams.setMargins(0, dp(16), 0, dp(4));
        root.addView(boundsTitle, boundsTitleParams);

        TextView boundsHint = text(
                "По умолчанию ищем Pinterest. Если One UI обновляет координаты accessibility-node во время свайпа, в отладке BOUNDS X должен плавно меняться вместе с настоящей иконкой. Тогда виртуальный offset нам больше не нужен.",
                13.5f, Color.DKGRAY, false);
        boundsHint.setGravity(Gravity.CENTER);
        root.addView(boundsHint, matchWrap());

        boundsAnchorEdit = new EditText(this);
        boundsAnchorEdit.setSingleLine(true);
        boundsAnchorEdit.setText(prefs.getString(Prefs.KEY_BOUNDS_ANCHOR_LABEL, Prefs.DEFAULT_BOUNDS_ANCHOR_LABEL));
        boundsAnchorEdit.setHint("Название иконки, например Pinterest");
        boundsAnchorEdit.setTextSize(17f);
        root.addView(boundsAnchorEdit, matchWrap());

        Button saveBoundsAnchor = new Button(this);
        saveBoundsAnchor.setText("СОХРАНИТЬ ИКОНКУ-МАРКЕР");
        saveBoundsAnchor.setOnClickListener(v -> {
            String label = boundsAnchorEdit.getText() == null ? "" : boundsAnchorEdit.getText().toString().trim();
            if (label.isEmpty()) label = Prefs.DEFAULT_BOUNDS_ANCHOR_LABEL;
            prefs.edit().putString(Prefs.KEY_BOUNDS_ANCHOR_LABEL, label).apply();
            boundsAnchorEdit.setText(label);
            refreshBoundsProbeStatus();
        });
        root.addView(saveBoundsAnchor, matchWrap());

        boundsProbeStatus = text("", 14f, Color.DKGRAY, true);
        boundsProbeStatus.setGravity(Gravity.CENTER);
        boundsProbeStatus.setPadding(0, dp(6), 0, dp(4));
        root.addView(boundsProbeStatus, matchWrap());

        Button gridEditor = new Button(this);
        gridEditor.setText("РЕДАКТОР СЕТКИ И СТЕКЛА");
        gridEditor.setOnClickListener(v -> startActivity(new Intent(this, GridEditorActivity.class)));
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.setMargins(0, dp(20), 0, dp(8));
        root.addView(gridEditor, gridParams);

        debugButton = new Button(this);
        debugButton.setOnClickListener(v -> toggleDebug());
        root.addView(debugButton, matchWrap());

        Button applyPosition = new Button(this);
        applyPosition.setText("СОХРАНИТЬ СТРАНИЦУ И НАСТРОЙКИ");
        applyPosition.setOnClickListener(v -> bumpGeneration());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(applyPosition, buttonParams);

        Button wallpaper = new Button(this);
        wallpaper.setText("ВЫБРАТЬ / ОБНОВИТЬ ЖИВЫЕ ОБОИ");
        wallpaper.setOnClickListener(v -> {
            bumpGeneration();
            openWallpaperPicker();
        });
        root.addView(wallpaper, matchWrap());

        TextView note = text(
                "Важно: это диагностическая версия. Сетка, назначения приложений и существующая физика стекла не менялись. Включи ОТЛАДКУ и смотри строку BOUNDS. Если число X меняется во время свайпа — следующий билд переведём на реальные координаты иконки.",
                14f, Color.DKGRAY, false);
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
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(110), dp(56));

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

    private LinearLayout makeSensitivityStepper() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = new Button(this);
        minus.setText("−");
        Button plus = new Button(this);
        plus.setText("+");

        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(84), dp(56));
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(110), dp(56));

        minus.setOnClickListener(v -> changeSensitivity(-0.10f));
        plus.setOnClickListener(v -> changeSensitivity(0.10f));

        row.addView(minus, side);
        row.addView(sensitivityValue, center);
        row.addView(plus, side);
        return row;
    }

    private LinearLayout makeStartPhaseStepper() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = new Button(this);
        minus.setText("−");
        Button plus = new Button(this);
        plus.setText("+");

        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(84), dp(56));
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(110), dp(56));

        minus.setOnClickListener(v -> changeStartPhase(-1f));
        plus.setOnClickListener(v -> changeStartPhase(1f));

        row.addView(minus, side);
        row.addView(startPhaseValue, center);
        row.addView(plus, side);
        return row;
    }

    private void changeStartPhase(float delta) {
        float current = prefs.getFloat(Prefs.KEY_GLASS_START_PHASE_PX, Prefs.DEFAULT_GLASS_START_PHASE_PX);
        float next = clamp(current + delta, 0f, 12f);
        prefs.edit().putFloat(Prefs.KEY_GLASS_START_PHASE_PX, next).apply();
        refreshValues();
    }

    private void changePageCount(int delta) {
        int oldCount = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int count = clamp(oldCount + delta, 2, 9);
        int current = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, count - 1);
        prefs.edit()
                .putInt(Prefs.KEY_PAGE_COUNT, count)
                .putInt(Prefs.KEY_SAVED_PAGE, current)
                .apply();
        refreshValues();
    }

    private void changeCurrentPage(int delta) {
        int count = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int current = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0) + delta, 0, count - 1);
        prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, current).apply();
        refreshValues();
    }

    private void changeSensitivity(float delta) {
        float current = prefs.getFloat(Prefs.KEY_SWIPE_SENSITIVITY, 1.80f);
        float next = clamp(current + delta, 0.70f, 2.60f);
        next = Math.round(next * 10f) / 10f;
        prefs.edit().putFloat(Prefs.KEY_SWIPE_SENSITIVITY, next).apply();
        refreshValues();
    }

    private void toggleDebug() {
        boolean current = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);
        prefs.edit().putBoolean(Prefs.KEY_SHOW_DEBUG, !current).apply();
        bumpGeneration();
    }

    private void bumpGeneration() {
        int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, 0);
        prefs.edit().putInt(Prefs.KEY_CONFIG_GENERATION, generation + 1).apply();
        refreshValues();
    }

    private void refreshValues() {
        int count = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int page = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, count - 1);
        float sensitivity = prefs.getFloat(Prefs.KEY_SWIPE_SENSITIVITY, 1.80f);
        float startPhase = prefs.getFloat(Prefs.KEY_GLASS_START_PHASE_PX, Prefs.DEFAULT_GLASS_START_PHASE_PX);
        boolean debug = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);

        pagesValue.setText(String.valueOf(count));
        currentValue.setText((page + 1) + " / " + count);
        sensitivityValue.setText(String.format(Locale.US, "%.2f×", sensitivity));
        if (startPhaseValue != null) startPhaseValue.setText(String.format(Locale.US, "%.0f px", startPhase));
        if (debugButton != null) {
            debugButton.setText(debug ? "ОТЛАДКА: ВКЛ" : "ОТЛАДКА: ВЫКЛ");
        }
        refreshRealOffsetStatus();
        refreshBoundsProbeStatus();
    }

    private void refreshBoundsProbeStatus() {
        if (boundsProbeStatus == null) return;
        String anchor = prefs.getString(Prefs.KEY_BOUNDS_ANCHOR_LABEL, Prefs.DEFAULT_BOUNDS_ANCHOR_LABEL);
        if (LauncherScrollBus.boundsFound) {
            long age = Math.max(0L, android.os.SystemClock.uptimeMillis() - LauncherScrollBus.boundsUptimeMs);
            boundsProbeStatus.setText(String.format(Locale.US, "FOUND %s: x=%d y=%d  changes=%d  age=%dms",
                    anchor, LauncherScrollBus.boundsCenterX, LauncherScrollBus.boundsCenterY,
                    LauncherScrollBus.boundsChanges, age));
            boundsProbeStatus.setTextColor(Color.rgb(20, 125, 65));
        } else {
            boundsProbeStatus.setText("Пока не вижу bounds для: " + anchor);
            boundsProbeStatus.setTextColor(Color.rgb(145, 75, 20));
        }
    }

    private void refreshRealOffsetStatus() {
        if (realOffsetStatus == null) return;
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean enabled = false;
        if (am != null) {
            List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                        && LauncherAccessibilityService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) {
                    enabled = true; break;
                }
            }
        }
        realOffsetStatus.setText(enabled
                ? "REAL OFFSET: служба включена ✓"
                : "REAL OFFSET: служба пока выключена");
        realOffsetStatus.setTextColor(enabled ? Color.rgb(20, 125, 65) : Color.rgb(145, 75, 20));
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
