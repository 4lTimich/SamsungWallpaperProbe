package com.example.samsungwallpaperprobe;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView pagesValue;
    private TextView currentValue;
    private TextView sensitivityValue;
    private Button debugButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ProbeWallpaperService.PREFS, MODE_PRIVATE);

        if (!prefs.contains(ProbeWallpaperService.KEY_SWIPE_SENSITIVITY)) {
            prefs.edit().putFloat(ProbeWallpaperService.KEY_SWIPE_SENSITIVITY, 1.65f).apply();
        }

        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, dp(36));
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Iridescent Engine v0.4", 25f, Color.BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView body = text(
                "Теперь это уже не только тест. Фон сохраняет виртуальную страницу, сильнее реагирует на палец и рисует живые iridescent-поля под будущими полупрозрачными иконками.\n\n" +
                "Сначала выставь страницы. Чувствительность можно менять без пересборки APK.",
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

        TextView sensLabel = text("Чувствительность движения", 17f, Color.BLACK, true);
        sensLabel.setPadding(0, dp(18), 0, 0);
        root.addView(sensLabel, matchWrap());
        sensitivityValue = text("", 27f, Color.BLACK, true);
        sensitivityValue.setGravity(Gravity.CENTER);
        root.addView(makeSensitivityStepper(), matchWrap());

        TextView sensHint = text(
                "1.00× = примерно как палец. 1.65× = текущий рекомендуемый вариант. Если хочешь сильнее — попробуй 1.80–2.00×.",
                13.5f, Color.DKGRAY, false);
        sensHint.setGravity(Gravity.CENTER);
        sensHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(sensHint, matchWrap());

        debugButton = new Button(this);
        debugButton.setOnClickListener(v -> toggleDebug());
        root.addView(debugButton, matchWrap());

        Button applyPosition = new Button(this);
        applyPosition.setText("СОХРАНИТЬ СТРАНИЦУ И НАСТРОЙКИ");
        applyPosition.setOnClickListener(v -> bumpGeneration());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(applyPosition, buttonParams);

        Button wallpaper = new Button(this);
        wallpaper.setText("ВЫБРАТЬ / ОБНОВИТЬ ЖИВЫЕ ОБОИ");
        wallpaper.setOnClickListener(v -> {
            bumpGeneration();
            openWallpaperPicker();
        });
        root.addView(wallpaper, matchWrap());

        TextView note = text(
                "Практический тест иконок: следующим шагом мы ставим через Theme Park одну PNG-иконку с прозрачными участками. Если Samsung сохраняет alpha, живой цвет этих обоев будет виден прямо внутри её стекла — это и будет основа всего пака.",
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

    private void changePageCount(int delta) {
        int oldCount = prefs.getInt(ProbeWallpaperService.KEY_PAGE_COUNT, 4);
        int count = clamp(oldCount + delta, 2, 9);
        int current = clamp(prefs.getInt(ProbeWallpaperService.KEY_SAVED_PAGE, 0), 0, count - 1);
        prefs.edit()
                .putInt(ProbeWallpaperService.KEY_PAGE_COUNT, count)
                .putInt(ProbeWallpaperService.KEY_SAVED_PAGE, current)
                .apply();
        refreshValues();
    }

    private void changeCurrentPage(int delta) {
        int count = prefs.getInt(ProbeWallpaperService.KEY_PAGE_COUNT, 4);
        int current = clamp(prefs.getInt(ProbeWallpaperService.KEY_SAVED_PAGE, 0) + delta, 0, count - 1);
        prefs.edit().putInt(ProbeWallpaperService.KEY_SAVED_PAGE, current).apply();
        refreshValues();
    }

    private void changeSensitivity(float delta) {
        float current = prefs.getFloat(ProbeWallpaperService.KEY_SWIPE_SENSITIVITY, 1.65f);
        float next = clamp(current + delta, 0.70f, 2.60f);
        next = Math.round(next * 10f) / 10f;
        prefs.edit().putFloat(ProbeWallpaperService.KEY_SWIPE_SENSITIVITY, next).apply();
        refreshValues();
    }

    private void toggleDebug() {
        boolean current = prefs.getBoolean(ProbeWallpaperService.KEY_SHOW_DEBUG, false);
        prefs.edit().putBoolean(ProbeWallpaperService.KEY_SHOW_DEBUG, !current).apply();
        refreshValues();
    }

    private void bumpGeneration() {
        int generation = prefs.getInt(ProbeWallpaperService.KEY_CONFIG_GENERATION, 0);
        prefs.edit().putInt(ProbeWallpaperService.KEY_CONFIG_GENERATION, generation + 1).apply();
        refreshValues();
    }

    private void refreshValues() {
        int count = prefs.getInt(ProbeWallpaperService.KEY_PAGE_COUNT, 4);
        int page = clamp(prefs.getInt(ProbeWallpaperService.KEY_SAVED_PAGE, 0), 0, count - 1);
        float sensitivity = prefs.getFloat(ProbeWallpaperService.KEY_SWIPE_SENSITIVITY, 1.65f);
        boolean debug = prefs.getBoolean(ProbeWallpaperService.KEY_SHOW_DEBUG, false);

        pagesValue.setText(String.valueOf(count));
        currentValue.setText((page + 1) + " / " + count);
        sensitivityValue.setText(String.format(Locale.US, "%.2f×", sensitivity));
        if (debugButton != null) {
            debugButton.setText(debug ? "ДИАГНОСТИКА: ВКЛ" : "ДИАГНОСТИКА: ВЫКЛ");
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
