package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings changed on the TV (SettingsActivity). Each one starts from res/values/config.xml and the TV's choice wins,
 * so a rebuild with a new config.xml only changes what was never touched on the TV.
 */
final class Prefs {

    static final String THEME_AUTO = "auto", THEME_DAY = "day", THEME_NIGHT = "night";
    static final String MODE_OURS = "ours", MODE_ORIGINAL = "original";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    // ---- weather city ----------------------------------------------------------------------------

    static String city(Context c) {
        return sp(c).getString("city", c.getString(R.string.weather_city));
    }

    static String lat(Context c) {
        return sp(c).getString("lat", c.getString(R.string.weather_lat));
    }

    static String lon(Context c) {
        return sp(c).getString("lon", c.getString(R.string.weather_lon));
    }

    static void setCity(Context c, String name, String lat, String lon) {
        sp(c).edit().putString("city", name).putString("lat", lat).putString("lon", lon)
                .remove("sunrise").remove("sunset").apply();
        Weather.last = null;
    }

    // ---- dock ------------------------------------------------------------------------------------

    static List<String> dockApps(Context c) {
        String saved = sp(c).getString("dock", null);
        if (saved == null) return Arrays.asList(c.getResources().getStringArray(R.array.dock_apps));
        return saved.isEmpty() ? new ArrayList<>() : Arrays.asList(saved.split(","));
    }

    static void setDockApps(Context c, List<String> pkgs) {
        sp(c).edit().putString("dock", String.join(",", pkgs)).apply();
    }

    // ---- remote buttons --------------------------------------------------------------------------

    /** Key code → package ("" = the button does what the TV does by itself), in config.xml order. */
    static Map<Integer, String> keyRemaps(Context c) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String line : c.getResources().getStringArray(R.array.key_remaps)) {
            String[] kv = line.split("=", 2);
            if (kv.length != 2) continue;
            try {
                int key = Integer.parseInt(kv[0].trim());
                out.put(key, sp(c).getString("key" + key, kv[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void setKeyApp(Context c, int keyCode, String pkg) {
        sp(c).edit().putString("key" + keyCode, pkg).apply();
    }

    // ---- look ------------------------------------------------------------------------------------

    static String theme(Context c) {
        return sp(c).getString("theme", c.getString(R.string.theme));
    }

    static void setTheme(Context c, String theme) {
        sp(c).edit().putString("theme", theme).apply();
    }

    /** Today's sunrise and sunset (epoch ms) from the last forecast, or 0. */
    static long sunrise(Context c) {
        return sp(c).getLong("sunrise", 0);
    }

    static long sunset(Context c) {
        return sp(c).getLong("sunset", 0);
    }

    static void setSun(Context c, long sunrise, long sunset) {
        sp(c).edit().putLong("sunrise", sunrise).putLong("sunset", sunset).apply();
    }

    // ---- trending picks (TMDB) -------------------------------------------------------------------

    static String tmdbKey(Context c) {
        return sp(c).getString("tmdbKey", c.getString(R.string.tmdb_key)).trim();
    }

    static void setTmdbKey(Context c, String key) {
        sp(c).edit().putString("tmdbKey", key.trim()).remove("picksJson").apply();
    }

    static boolean picksOn(Context c) {
        return sp(c).getBoolean("picks", true) && !tmdbKey(c).isEmpty();
    }

    static void setPicksOn(Context c, boolean on) {
        sp(c).edit().putBoolean("picks", on).apply();
    }

    static String picksJson(Context c) {
        return sp(c).getString("picksJson", "");
    }

    static long picksTime(Context c) {
        return sp(c).getLong("picksTime", 0);
    }

    static void setPicks(Context c, String json) {
        sp(c).edit().putString("picksJson", json).putLong("picksTime", System.currentTimeMillis()).apply();
    }

    // ---- home screen mode (HomeMode) -------------------------------------------------------------

    /** Ours unless "Original" was picked on the TV: then nothing here turns our services back on. */
    static String mode(Context c) {
        return sp(c).getString("mode", MODE_OURS);
    }

    static void setMode(Context c, String mode) {
        sp(c).edit().putString("mode", mode).apply();
    }

    /** The TV's own screensaver before ours replaced it, for "Original". */
    static String originalDream(Context c) {
        return sp(c).getString("originalDream", "");
    }

    static void setOriginalDream(Context c, String component) {
        sp(c).edit().putString("originalDream", component).apply();
    }
}
