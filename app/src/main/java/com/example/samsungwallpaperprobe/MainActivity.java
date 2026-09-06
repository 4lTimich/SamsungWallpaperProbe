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
import android.widget.TextView;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView pagesValue;
    private TextView currentValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ProbeWallpaperService.PREFS, MODE_PRIVATE);

        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        TextView title = text("Virtual Page Engine v0.3", 25f, Color.BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView body = text(
                "Эта версия сама ведёт номер страницы и сохраняет его. После открытия приложения, блокировки/разблокировки или перезапуска процесса позиция не должна сбрасываться.\n\n" +
                "Перед тестом укажи число обычных страниц One UI (не считай Google Discover/Samsung Free слева) и страницу, на которой ты находился перед открытием этого приложения.",
                16f, Color.DKGRAY, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(16), 0, dp(20));
        root.addView(body, matchWrap());

        root.addView(text("Количество страниц", 17f, Color.BLACK, true), matchWrap());
        pagesValue = text("", 28f, Color.BLACK, true);
        pagesValue.setGravity(Gravity.CENTER);
        root.addView(makeStepper(pagesValue, true), matchWrap());

        TextView currentLabel = text("Текущая страница", 17f, Color.BLACK, true);
        currentLabel.setPadding(0, dp(16), 0, 0);
        root.addView(currentLabel, matchWrap());
        currentValue = text("", 28f, Color.BLACK, true);
        currentValue.setGravity(Gravity.CENTER);
        root.addView(makeStepper(currentValue, false), matchWrap());

        Button applyPosition = new Button(this);
        applyPosition.setText("СОХРАНИТЬ ЭТУ ПОЗИЦИЮ");
        applyPosition.setOnClickListener(v -> bumpGeneration());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(18), 0, dp(8));
        root.addView(applyPosition, buttonParams);

        Button wallpaper = new Button(this);
        wallpaper.setText("ВЫБРАТЬ / ОБНОВИТЬ ЖИВЫЕ ОБОИ");
        wallpaper.setOnClickListener(v -> {
            bumpGeneration();
            openWallpaperPicker();
        });
        root.addView(wallpaper, matchWrap());

        TextView note = text(
                "После применения: листни 1–2 страницы, запомни SAVED PAGE. Затем открой любое приложение и вернись домой; потом заблокируй и разблокируй телефон. SAVED PAGE и положение маркера должны остаться прежними.",
                14f, Color.DKGRAY, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());

        setContentView(root);
        refreshValues();
    }

    private LinearLayout makeStepper(TextView valueView, boolean pagesStepper) {
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

    private void bumpGeneration() {
        int generation = prefs.getInt(ProbeWallpaperService.KEY_CONFIG_GENERATION, 0);
        prefs.edit().putInt(ProbeWallpaperService.KEY_CONFIG_GENERATION, generation + 1).apply();
        refreshValues();
    }

    private void refreshValues() {
        int count = prefs.getInt(ProbeWallpaperService.KEY_PAGE_COUNT, 4);
        int page = clamp(prefs.getInt(ProbeWallpaperService.KEY_SAVED_PAGE, 0), 0, count - 1);
        pagesValue.setText(String.valueOf(count));
        currentValue.setText((page + 1) + " / " + count);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
