package com.gathin.tvhome;

import android.content.Context;

import java.util.Calendar;

/**
 * Light by day, dark by night, following the real sunrise and sunset of the weather city (Open-Meteo), the way a
 * phone does. Settings can pin it to day or night instead (Prefs.theme).
 */
final class DayNight {

    private DayNight() {
    }

    static boolean isDay(Context c) {
        String theme = Prefs.theme(c);
        if (Prefs.THEME_DAY.equals(theme)) return true;
        if (Prefs.THEME_NIGHT.equals(theme)) return false;
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        // The saved times can be a day or two old (no network): their time of day is still close enough.
        int rise = minuteOfDay(Prefs.sunrise(c), 7 * 60);
        int set = minuteOfDay(Prefs.sunset(c), 19 * 60);
        return minute >= rise && minute < set;
    }

    private static int minuteOfDay(long epochMs, int fallback) {
        if (epochMs <= 0) return fallback;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }
}
