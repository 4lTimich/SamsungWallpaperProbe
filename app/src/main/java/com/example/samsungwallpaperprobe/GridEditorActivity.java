package com.example.samsungwallpaperprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class GridEditorActivity extends Activity implements GlassGridView.CellTapListener {

    private static final int PICK_SCREENSHOT = 2001;

    private SharedPreferences prefs;
    private GlassGridView gridView;
    private TextView pageValue;
    private TextView colsValue;
    private TextView rowsValue;
    private TextView xValue;
    private TextView yValue;
    private TextView widthValue;
    private TextView heightValue;
    private TextView gapXValue;
    private TextView gapYValue;
    private TextView opacityValue;
    private int editorPage = 0;

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        String display() {
            return label + "\n" + packageName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        Prefs.ensureV06GridDefaults(prefs, sw, sh);
        int pages = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int detected = LauncherScrollBus.authoritativePage;
        if (LauncherScrollBus.serviceConnected && detected >= 0 && detected < pages) {
            editorPage = detected;
        } else {
            editorPage = clamp(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, pages - 1);
        }
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(36));
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Редактор сетки стекла v0.8", 24f, Color.BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView hint = text(
                "Каждая страница хранит СВОИ ячейки. Сверху всегда крупно указано, какую страницу ты сейчас редактируешь. Кнопка ниже подхватывает последнюю страницу, подтверждённую службой One UI, чтобы стекло случайно не записалось на страницу 1 вместо 2.",
                15f, Color.DKGRAY, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint, matchWrap());

        pageValue = text("", 22f, Color.BLACK, true);
        pageValue.setGravity(Gravity.CENTER);
        root.addView(labelledStepper("РЕДАКТИРУЕТСЯ СТРАНИЦА", pageValue,
                v -> changePage(-1), v -> changePage(1)), matchWrap());

        Button useDetectedPage = new Button(this);
        useDetectedPage.setText("ВЗЯТЬ ТЕКУЩУЮ СТРАНИЦУ ИЗ ONE UI");
        useDetectedPage.setOnClickListener(v -> useAuthoritativePage());
        LinearLayout.LayoutParams detectedParams = matchWrap();
        detectedParams.setMargins(0, dp(4), 0, dp(10));
        root.addView(useDetectedPage, detectedParams);

        Button movePrevPage = new Button(this);
        movePrevPage.setText("ПЕРЕНЕСТИ ЯЧЕЙКИ С ПРЕДЫДУЩЕЙ СТРАНИЦЫ СЮДА");
        movePrevPage.setOnClickListener(v -> movePreviousPageAssignments());
        root.addView(movePrevPage, matchWrap());

        LinearLayout dims = new LinearLayout(this);
        dims.setOrientation(LinearLayout.HORIZONTAL);
        dims.setGravity(Gravity.CENTER);
        dims.setWeightSum(2f);

        colsValue = text("", 20f, Color.BLACK, true);
        colsValue.setGravity(Gravity.CENTER);
        LinearLayout cols = labelledStepper("Колонки", colsValue,
                v -> changeCols(-1), v -> changeCols(1));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dims.addView(cols, half);

        rowsValue = text("", 20f, Color.BLACK, true);
        rowsValue.setGravity(Gravity.CENTER);
        LinearLayout rows = labelledStepper("Ряды", rowsValue,
                v -> changeRows(-1), v -> changeRows(1));
        dims.addView(rows, half);
        root.addView(dims, matchWrap());

        LinearLayout shotButtons = new LinearLayout(this);
        shotButtons.setOrientation(LinearLayout.HORIZONTAL);
        shotButtons.setGravity(Gravity.CENTER);

        Button chooseShot = new Button(this);
        chooseShot.setText("ЗАГРУЗИТЬ СКРИНШОТ");
        chooseShot.setOnClickListener(v -> pickScreenshot());
        Button clearShot = new Button(this);
        clearShot.setText("УБРАТЬ");
        clearShot.setOnClickListener(v -> {
            prefs.edit().remove(Prefs.KEY_EDITOR_SCREENSHOT_URI).apply();
            gridView.reloadScreenshot();
        });
        shotButtons.addView(chooseShot, new LinearLayout.LayoutParams(0, dp(54), 1f));
        shotButtons.addView(clearShot, new LinearLayout.LayoutParams(dp(108), dp(54)));
        LinearLayout.LayoutParams shotParams = matchWrap();
        shotParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(shotButtons, shotParams);

        gridView = new GlassGridView(this);
        gridView.setCellTapListener(this);
        gridView.setEditorPage(editorPage);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(610));
        root.addView(gridView, previewParams);

        TextView precision = text("Точная калибровка", 19f, Color.BLACK, true);
        precision.setGravity(Gravity.CENTER);
        precision.setPadding(0, dp(14), 0, dp(6));
        root.addView(precision, matchWrap());

        TextView geomHint = text(
                "X/Y — центр первой ячейки. Ширина/высота — размер самого стекла. Зазор X/Y — пустое расстояние от края одной ячейки до края следующей.",
                13.5f, Color.DKGRAY, false);
        geomHint.setGravity(Gravity.CENTER);
        geomHint.setPadding(0, 0, 0, dp(8));
        root.addView(geomHint, matchWrap());

        xValue = text("", 16f, Color.BLACK, true);
        yValue = text("", 16f, Color.BLACK, true);
        widthValue = text("", 16f, Color.BLACK, true);
        heightValue = text("", 16f, Color.BLACK, true);
        gapXValue = text("", 16f, Color.BLACK, true);
        gapYValue = text("", 16f, Color.BLACK, true);
        opacityValue = text("", 16f, Color.BLACK, true);

        root.addView(fineRow("Сетка X", xValue,
                v -> nudge(Prefs.KEY_GRID_X0, -1, true),
                v -> nudge(Prefs.KEY_GRID_X0, 1, true)), matchWrap());
        root.addView(fineRow("Сетка Y", yValue,
                v -> nudge(Prefs.KEY_GRID_Y0, -1, false),
                v -> nudge(Prefs.KEY_GRID_Y0, 1, false)), matchWrap());
        root.addView(fineRow("Ширина ячейки", widthValue,
                v -> nudge(Prefs.KEY_CELL_WIDTH, -1, true),
                v -> nudge(Prefs.KEY_CELL_WIDTH, 1, true)), matchWrap());
        root.addView(fineRow("Высота ячейки", heightValue,
                v -> nudge(Prefs.KEY_CELL_HEIGHT, -1, false),
                v -> nudge(Prefs.KEY_CELL_HEIGHT, 1, false)), matchWrap());
        root.addView(fineRow("Зазор X", gapXValue,
                v -> nudge(Prefs.KEY_GAP_X, -1, true),
                v -> nudge(Prefs.KEY_GAP_X, 1, true)), matchWrap());
        root.addView(fineRow("Зазор Y", gapYValue,
                v -> nudge(Prefs.KEY_GAP_Y, -1, false),
                v -> nudge(Prefs.KEY_GAP_Y, 1, false)), matchWrap());
        root.addView(fineRow("Стекло", opacityValue,
                v -> changeOpacity(-0.05f),
                v -> changeOpacity(0.05f)), matchWrap());

        Button reset = new Button(this);
        reset.setText("СБРОСИТЬ ГЕОМЕТРИЮ К 4×6");
        reset.setOnClickListener(v -> resetGridDefaults());
        LinearLayout.LayoutParams resetParams = matchWrap();
        resetParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(reset, resetParams);

        Button save = new Button(this);
        save.setText("СОХРАНИТЬ И ВЕРНУТЬСЯ");
        save.setOnClickListener(v -> {
            bumpGeneration();
            finish();
        });
        root.addView(save, matchWrap());

        TextView bottom = text(
                "Для твоего скриншота: сначала попаданием первой ячейки выставь Сетка X/Y, потом ширину/высоту, и только после этого зазоры X/Y — они определяют накопление ошибки к 4-й колонке и нижним рядам.",
                13.5f, Color.DKGRAY, false);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(12), 0, 0);
        root.addView(bottom, matchWrap());

        setContentView(scroll);
        refreshValues();
    }

    @Override
    public void onCellTapped(int row, int col) {
        showAppPicker(editorPage, row, col);
    }

    private void showAppPicker(int page, int row, int col) {
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcherIntent, 0);

        final List<AppEntry> all = new ArrayList<>();
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            CharSequence labelCs = info.loadLabel(pm);
            String label = labelCs == null ? info.activityInfo.packageName : labelCs.toString();
            all.add(new AppEntry(label, info.activityInfo.packageName));
        }
        Collections.sort(all, Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        box.setPadding(p, p, p, p);

        EditText search = new EditText(this);
        search.setHint("Поиск приложения");
        search.setSingleLine(true);
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(430)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ячейка " + (row + 1) + ", " + (col + 1))
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Очистить", null)
                .create();

        final List<AppEntry> shown = new ArrayList<>(all);

        Runnable rebuild = () -> {
            List<String> display = new ArrayList<>();
            for (AppEntry e : shown) display.add(e.display());
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, display);
            list.setAdapter(adapter);
        };
        rebuild.run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (AppEntry e : all) {
                    if (q.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(q)
                            || e.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                        shown.add(e);
                    }
                }
                rebuild.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shown.size()) {
                AppEntry selected = shown.get(position);
                prefs.edit().putString(Prefs.cellKey(page, row, col), selected.packageName).apply();
                gridView.invalidateAssignments();
                dialog.dismiss();
            }
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                prefs.edit().remove(Prefs.cellKey(page, row, col)).apply();
                gridView.invalidateAssignments();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void pickScreenshot() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_SCREENSHOT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SCREENSHOT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                prefs.edit().putString(Prefs.KEY_EDITOR_SCREENSHOT_URI, uri.toString()).apply();
                gridView.reloadScreenshot();
            }
        }
    }

    private void useAuthoritativePage() {
        int pages = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int detected = LauncherScrollBus.authoritativePage;
        if (LauncherScrollBus.serviceConnected && detected >= 0 && detected < pages) {
            editorPage = detected;
            gridView.setEditorPage(editorPage);
            refreshValues();
            Toast.makeText(this, "Редактируется страница " + (editorPage + 1), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "One UI пока не подтвердил текущую страницу. Вернись домой, листни страницу и открой редактор снова.", Toast.LENGTH_LONG).show();
        }
    }

    private void movePreviousPageAssignments() {
        if (editorPage <= 0) {
            Toast.makeText(this, "У первой страницы нет предыдущей", Toast.LENGTH_SHORT).show();
            return;
        }
        final int sourcePage = editorPage - 1;
        new AlertDialog.Builder(this)
                .setTitle("Перенести страницу " + (sourcePage + 1) + " → " + (editorPage + 1) + "?")
                .setMessage("Назначенные приложения будут перенесены в те же ячейки. Уже занятые ячейки текущей страницы не перезаписываются.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Перенести", (d, which) -> {
                    int cols = prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS);
                    int rows = prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS);
                    SharedPreferences.Editor edit = prefs.edit();
                    int moved = 0;
                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < cols; col++) {
                            String srcKey = Prefs.cellKey(sourcePage, row, col);
                            String dstKey = Prefs.cellKey(editorPage, row, col);
                            String pkg = prefs.getString(srcKey, null);
                            String dst = prefs.getString(dstKey, null);
                            if (pkg != null && !pkg.isEmpty() && (dst == null || dst.isEmpty())) {
                                edit.putString(dstKey, pkg);
                                edit.remove(srcKey);
                                moved++;
                            }
                        }
                    }
                    edit.apply();
                    gridView.invalidateAssignments();
                    Toast.makeText(this, "Перенесено ячеек: " + moved, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void changePage(int delta) {
        int pages = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        editorPage = clamp(editorPage + delta, 0, pages - 1);
        gridView.setEditorPage(editorPage);
        refreshValues();
    }

    private void changeCols(int delta) {
        int cols = clamp(prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS) + delta, 2, 7);
        prefs.edit().putInt(Prefs.KEY_GRID_COLS, cols).apply();
        gridView.invalidateAssignments();
        refreshValues();
    }

    private void changeRows(int delta) {
        int rows = clamp(prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS) + delta, 2, 9);
        prefs.edit().putInt(Prefs.KEY_GRID_ROWS, rows).apply();
        gridView.invalidateAssignments();
        refreshValues();
    }

    private void nudge(String key, int pixels, boolean horizontal) {
        int basePixels = horizontal
                ? getResources().getDisplayMetrics().widthPixels
                : getResources().getDisplayMetrics().heightPixels;
        float fallback;
        float min;
        float max;

        if (Prefs.KEY_GRID_X0.equals(key)) {
            fallback = Prefs.DEFAULT_X0; min = 0f; max = 1f;
        } else if (Prefs.KEY_GRID_Y0.equals(key)) {
            fallback = Prefs.DEFAULT_Y0; min = 0f; max = 1f;
        } else if (Prefs.KEY_CELL_WIDTH.equals(key)) {
            fallback = Prefs.DEFAULT_CELL_WIDTH; min = 0.03f; max = 0.40f;
        } else if (Prefs.KEY_CELL_HEIGHT.equals(key)) {
            fallback = Prefs.DEFAULT_CELL_HEIGHT; min = 0.015f; max = 0.25f;
        } else if (Prefs.KEY_GAP_X.equals(key)) {
            fallback = Prefs.DEFAULT_GAP_X; min = 0f; max = 0.40f;
        } else if (Prefs.KEY_GAP_Y.equals(key)) {
            fallback = Prefs.DEFAULT_GAP_Y; min = 0f; max = 0.30f;
        } else {
            return;
        }

        float current = prefs.getFloat(key, fallback);
        float next = current + pixels / (float) Math.max(1, basePixels);
        prefs.edit().putFloat(key, clamp(next, min, max)).apply();
        gridView.invalidate();
        refreshValues();
    }

    private void changeOpacity(float delta) {
        float current = prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY);
        prefs.edit().putFloat(Prefs.KEY_GLASS_OPACITY, clamp(current + delta, 0.05f, 0.95f)).apply();
        gridView.invalidate();
        refreshValues();
    }

    private void resetGridDefaults() {
        prefs.edit()
                .putInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS)
                .putInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS)
                .putFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0)
                .putFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0)
                .putFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH)
                .putFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT)
                .putFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X)
                .putFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y)
                .putFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY)
                .apply();
        gridView.invalidateAssignments();
        refreshValues();
    }

    private void bumpGeneration() {
        int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, 0);
        prefs.edit().putInt(Prefs.KEY_CONFIG_GENERATION, generation + 1).apply();
    }

    private void refreshValues() {
        int pages = prefs.getInt(Prefs.KEY_PAGE_COUNT, 4);
        int cols = prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS);
        int rows = prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS);
        float x0 = prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0);
        float y0 = prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0);
        float width = prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH);
        float height = prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT);
        float gapX = prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X);
        float gapY = prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y);
        float opacity = prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        pageValue.setText("СТРАНИЦА " + (editorPage + 1) + " / " + pages);
        colsValue.setText(String.valueOf(cols));
        rowsValue.setText(String.valueOf(rows));
        xValue.setText(Math.round(x0 * sw) + " px");
        yValue.setText(Math.round(y0 * sh) + " px");
        widthValue.setText(Math.round(width * sw) + " px");
        heightValue.setText(Math.round(height * sh) + " px");
        gapXValue.setText(Math.round(gapX * sw) + " px");
        gapYValue.setText(Math.round(gapY * sh) + " px");
        opacityValue.setText(Math.round(opacity * 100f) + "%");
    }

    private LinearLayout labelledStepper(String label, TextView value,
                                         View.OnClickListener minusListener,
                                         View.OnClickListener plusListener) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);

        TextView l = text(label, 15f, Color.DKGRAY, true);
        l.setGravity(Gravity.CENTER);
        outer.addView(l, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button minus = new Button(this);
        minus.setText("−");
        minus.setOnClickListener(minusListener);
        Button plus = new Button(this);
        plus.setText("+");
        plus.setOnClickListener(plusListener);
        row.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(52)));
        row.addView(value, new LinearLayout.LayoutParams(dp(104), dp(52)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(52)));
        outer.addView(row, matchWrap());
        return outer;
    }

    private LinearLayout fineRow(String label, TextView value,
                                 View.OnClickListener minusListener,
                                 View.OnClickListener plusListener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = text(label, 15f, Color.BLACK, false);
        row.addView(l, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button minus = new Button(this);
        minus.setText("−1");
        minus.setOnClickListener(minusListener);
        row.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(48)));

        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(82), dp(48)));

        Button plus = new Button(this);
        plus.setText("+1");
        plus.setOnClickListener(plusListener);
        row.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(48)));
        return row;
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

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
