package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Home's own settings, on the TV: the home screen (ours or the TV's), extra apps, the look, the weather city, the dock,
 * the remote's app buttons and the trending tile. Opened from the top bar's Settings button, and from the TV's app
 * list (so it is still there after switching back to the TV's own home screen).
 */
public class SettingsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private float density;
    private ColorStateList ink, inkDim;

    private static final class App {
        final String pkg, label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        ink = getColorStateList(R.drawable.pill_text);
        inkDim = getColorStateList(R.drawable.pill_text_dim);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(220), dp(40), dp(220), dp(40));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(0);
    }

    // ---- the list --------------------------------------------------------------------------------

    /** (Re)builds every row; focus goes back to row number `focus`. */
    private void build(int focus) {
        list.removeAllViews();
        TextView title = text(32, 300, 0xFFFFFFFF);
        title.setText("Настройки Home");
        list.addView(title);

        section("Главный экран");
        boolean ours = !Prefs.MODE_ORIGINAL.equals(Prefs.mode(this));
        row("Главный экран", ours ? "Home" : "Телевизора", this::chooseMode);
        row("Отключить лишние приложения", getResources().getStringArray(R.array.debloat_apps).length + " в списке",
                () -> confirm("Отключить приложения TCL и Google из списка? Ничего не удаляется, «Вернуть» включит их обратно.",
                        () -> runMode(l -> HomeMode.debloat(this, true, l))));
        row("Вернуть отключённые приложения", "", () -> runMode(l -> HomeMode.debloat(this, false, l)));

        section("Оформление");
        String theme = Prefs.theme(this);
        row("Тема", Prefs.THEME_DAY.equals(theme) ? "Светлая" : Prefs.THEME_NIGHT.equals(theme) ? "Тёмная" : "Авто (по закату)",
                this::chooseTheme);

        section("Погода");
        row("Город", Prefs.city(this), this::chooseCity);

        section("Нижний ряд");
        List<String> dock = Prefs.dockApps(this);
        for (int i = 0; i < Math.max(1, getResources().getInteger(R.integer.columns)); i++) {
            final int slot = i;
            row("Место " + (i + 1), i < dock.size() ? labelOf(dock.get(i)) : "—", () -> chooseDockApp(slot));
        }

        section("Кнопки пульта");
        for (Map.Entry<Integer, String> e : Prefs.keyRemaps(this).entrySet()) {
            final int key = e.getKey();
            row(keyName(key), e.getValue().isEmpty() ? "Как у телевизора" : labelOf(e.getValue()), () -> chooseKeyApp(key));
        }

        section("В тренде (TMDB)");
        boolean hasKey = !Prefs.tmdbKey(this).isEmpty();
        row("Плитка «В тренде»", !hasKey ? "нужен ключ" : Prefs.picksOn(this) ? "Показывать" : "Скрыта", () -> {
            if (!hasKey) {
                chooseTmdbKey();
                return;
            }
            Prefs.setPicksOn(this, !Prefs.picksOn(this));
            build(currentFocus());
        });
        row("Ключ TMDB", hasKey ? "задан" : "нет", this::chooseTmdbKey);

        section("Телевизор");
        row("Системные настройки", "", () -> open(new Intent(Settings.ACTION_SETTINGS)));

        View target = rowAt(focus);
        if (target != null) target.requestFocus();
    }

    private void section(String name) {
        TextView t = text(13, 600, 0x8CFFFFFF);
        t.setText(name.toUpperCase());
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(18), dp(26), 0, dp(8));
        list.addView(t);
    }

    private void row(String title, String value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.settings_row_bg);
        r.setPadding(dp(18), 0, dp(18), 0);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag("row");
        r.setOnClickListener(v -> action.run());
        TextView t = text(17, 500, 0);
        t.setTextColor(ink);
        t.setText(title);
        t.setDuplicateParentStateEnabled(true);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(15, 400, 0);
        v.setTextColor(inkDim);
        v.setText(value);
        v.setSingleLine(true);
        v.setDuplicateParentStateEnabled(true);
        r.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(4);
        list.addView(r, lp);
    }

    private TextView text(int sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        if (color != 0) t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private View rowAt(int index) {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if ("row".equals(c.getTag()) && n++ == index) return c;
        }
        return null;
    }

    private int currentFocus() {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (!"row".equals(c.getTag())) continue;
            if (c.isFocused()) return n;
            n++;
        }
        return 0;
    }

    // ---- home screen and extra apps --------------------------------------------------------------

    private interface ModeCall {
        void start(HomeMode.Listener l);
    }

    private void chooseMode() {
        String[] names = {"Home (этот)", "Главный экран телевизора"};
        boolean ours = !Prefs.MODE_ORIGINAL.equals(Prefs.mode(this));
        new AlertDialog.Builder(this).setTitle("Главный экран")
                .setSingleChoiceItems(names, ours ? 0 : 1, (d, which) -> {
                    d.dismiss();
                    if ((which == 0) == ours) return;
                    runMode(l -> HomeMode.switchTo(this, which == 0 ? Prefs.MODE_OURS : Prefs.MODE_ORIGINAL, l));
                }).show();
    }

    /** Runs a HomeMode job behind a small dialog that explains the TV's debugging prompt and shows the result. */
    private void runMode(ModeCall call) {
        final int focus = currentFocus();
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Подождите…").setMessage("Подключаюсь к телевизору")
                .setCancelable(false).show();
        call.start(new HomeMode.Listener() {
            @Override
            public void progress(String text) {
                if (d.isShowing()) d.setMessage(text);
            }

            @Override
            public void done(boolean ok, String text) {
                if (d.isShowing()) d.dismiss();
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(SettingsActivity.this).setTitle(ok ? "Готово" : "Ошибка").setMessage(text)
                        .setPositiveButton("OK", null).show();
                build(focus);
            }
        });
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton("Да", (d, w) -> yes.run())
                .setNegativeButton("Отмена", null).show();
    }

    // ---- look ------------------------------------------------------------------------------------

    private void chooseTheme() {
        String[] values = {Prefs.THEME_AUTO, Prefs.THEME_DAY, Prefs.THEME_NIGHT};
        String[] names = {"Авто: светлая днём, тёмная после заката", "Всегда светлая", "Всегда тёмная"};
        int now = Math.max(0, java.util.Arrays.asList(values).indexOf(Prefs.theme(this)));
        final int focus = currentFocus();
        new AlertDialog.Builder(this).setTitle("Тема").setSingleChoiceItems(names, now, (d, which) -> {
            d.dismiss();
            Prefs.setTheme(this, values[which]);
            build(focus);
        }).show();
    }

    // ---- weather city ----------------------------------------------------------------------------

    private void chooseCity() {
        ask("Город", Prefs.city(this), InputType.TYPE_CLASS_TEXT, name -> {
            if (name.isEmpty()) return;
            final int focus = currentFocus();
            new Thread(() -> {
                List<String[]> found = new ArrayList<>();   // {label, name, lat, lon}
                try {
                    JSONObject r = new JSONObject(Net.text("https://geocoding-api.open-meteo.com/v1/search?count=10&language=ru&name="
                            + Uri.encode(name), null));
                    JSONArray a = r.optJSONArray("results");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        StringBuilder label = new StringBuilder(o.optString("name"));
                        for (String k : new String[]{"admin1", "country"}) {
                            if (!o.optString(k).isEmpty()) label.append(", ").append(o.optString(k));
                        }
                        found.add(new String[]{label.toString(), o.optString("name"),
                                String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))});
                    }
                } catch (Exception ignored) {
                }
                main.post(() -> {
                    if (isFinishing()) return;
                    if (found.isEmpty()) {
                        Toast.makeText(this, "Не нашёл «" + name + "»", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = found.get(i)[0];
                    new AlertDialog.Builder(this).setTitle("Какой город?").setItems(labels, (d, which) -> {
                        String[] c = found.get(which);
                        Prefs.setCity(this, c[1], c[2], c[3]);
                        MainActivity.lastWeatherFetch = 0;   // Home fetches the new city's weather on its next resume
                        build(focus);
                    }).show();
                });
            }, "geocode").start();
        });
    }

    // ---- dock and remote buttons -----------------------------------------------------------------

    private void chooseDockApp(int slot) {
        List<App> apps = apps();
        String[] names = new String[apps.size() + 1];
        names[0] = "Пусто";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
        final int focus = currentFocus();
        new AlertDialog.Builder(this).setTitle("Место " + (slot + 1)).setItems(names, (d, which) -> {
            List<String> dock = new ArrayList<>(Prefs.dockApps(this));
            while (dock.size() <= slot) dock.add("");
            String pkg = which == 0 ? "" : apps.get(which - 1).pkg;
            int already = pkg.isEmpty() ? -1 : dock.indexOf(pkg);
            if (already >= 0) dock.set(already, dock.get(slot));   // already in the row: the two swap places
            dock.set(slot, pkg);
            while (!dock.isEmpty() && dock.get(dock.size() - 1).isEmpty()) dock.remove(dock.size() - 1);
            Prefs.setDockApps(this, dock);
            build(focus);
        }).show();
    }

    private void chooseKeyApp(int key) {
        List<App> apps = apps();
        String[] names = new String[apps.size() + 1];
        names[0] = "Как у телевизора";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
        final int focus = currentFocus();
        new AlertDialog.Builder(this).setTitle(keyName(key)).setItems(names, (d, which) -> {
            Prefs.setKeyApp(this, key, which == 0 ? "" : apps.get(which - 1).pkg);
            build(focus);
        }).show();
    }

    private static String keyName(int key) {
        if (key == 4062) return "Кнопка Netflix";
        if (key == 4065) return "Кнопка YouTube";
        if (key == 4066) return "Кнопка Prime Video";
        return "Кнопка " + key;
    }

    /** The TV apps, by name. */
    private List<App> apps() {
        PackageManager pm = getPackageManager();
        List<App> out = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            out.add(new App(pkg, String.valueOf(ri.loadLabel(pm))));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private String labelOf(String pkg) {
        if (pkg.isEmpty()) return "—";
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg + " (нет)";
        }
    }

    // ---- trending --------------------------------------------------------------------------------

    private void chooseTmdbKey() {
        ask("Ключ TMDB (themoviedb.org → Настройки → API)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                key -> {
                    final int focus = currentFocus();
                    Prefs.setTmdbKey(this, key);
                    Prefs.setPicksOn(this, true);
                    build(focus);
                });
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private interface Answer {
        void accept(String text);
    }

    private void ask(String title, String start, int inputType, Answer answer) {
        EditText field = new EditText(this);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setText(start);
        field.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> answer.accept(field.getText().toString().trim()))
                .setNegativeButton("Отмена", null).show();
        field.requestFocus();
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
