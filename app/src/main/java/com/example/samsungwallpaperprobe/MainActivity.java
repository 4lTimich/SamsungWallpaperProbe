package com.example.samsungwallpaperprobe;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Samsung Wallpaper Probe v0.2");
        title.setTextColor(Color.BLACK);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);

        TextView body = new TextView(this);
        body.setText("Вторая проверка. Примени обои, затем: (1) листай страницы рабочего стола, (2) наклоняй телефон. Нас интересуют строки TOUCH и GYRO. Если TOUCH меняется во время свайпа — сможем привязать эффект прямо к движению пальца.");
        body.setTextColor(Color.DKGRAY);
        body.setTextSize(16f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(18), 0, dp(24));

        Button button = new Button(this);
        button.setText("ВЫБРАТЬ ТЕСТОВЫЕ ОБОИ");
        button.setOnClickListener(v -> openWallpaperPicker());

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
