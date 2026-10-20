package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Weather for the city in config.xml from Open-Meteo (free, no API key). One request per call. */
final class Weather {

    static final class Hour {
        String label;
        int code;
        boolean day;
        int temp;
        int precipProb;
        double precipMm;
    }

    static final class Day {
        String label;
        int code;
        int min, max, precip;
        double precipMm;
    }

    static final class Data {
        String city;
        int temp, feels, humidity, wind, code, min, max;
        boolean day;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
    }

    /** Last good result, so the screensaver shows something at once while it refreshes. */
    static volatile Data last;

    private Weather() {
    }

    static void fetch(Context ctx, Consumer<Data> onResult) {
        final String city = ctx.getString(R.string.weather_city);
        final String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,is_day,precipitation,precipitation_probability&forecast_hours=25"
                        + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,precipitation_sum"
                        + "&forecast_days=10&wind_speed_unit=ms&timezone=auto",
                ctx.getString(R.string.weather_lat), ctx.getString(R.string.weather_lon));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Data data = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 262144) out.write(buf, 0, n);
                    data = parse(city, new JSONObject(out.toString("UTF-8")));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            if (data != null) last = data;
            final Data result = data != null ? data : last;
            main.post(() -> onResult.accept(result));
        }, "weather").start();
    }

    private static Data parse(String city, JSONObject root) throws Exception {
        Data d = new Data();
        d.city = city;
        JSONObject cur = root.getJSONObject("current");
        d.temp = round(cur.getDouble("temperature_2m"));
        d.feels = round(cur.getDouble("apparent_temperature"));
        d.humidity = cur.getInt("relative_humidity_2m");
        d.wind = round(cur.getDouble("wind_speed_10m"));
        d.code = cur.getInt("weather_code");
        d.day = cur.optInt("is_day", 1) == 1;

        JSONObject h = root.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time"), htemp = h.getJSONArray("temperature_2m"),
                hcode = h.getJSONArray("weather_code"), hday = h.getJSONArray("is_day");
        String now = cur.getString("time").substring(0, 13);   // "2026-10-20T20"
        int start = 0;
        for (int i = 0; i < ht.length(); i++) {
            if (ht.getString(i).substring(0, 13).compareTo(now) >= 0) {
                start = i;
                break;
            }
        }
        for (int i = start; i < ht.length() && d.hours.size() < 24; i++) {
            Hour x = new Hour();
            x.label = d.hours.isEmpty() ? "Сейчас" : ht.getString(i).substring(11, 13);
            x.temp = d.hours.isEmpty() ? d.temp : round(htemp.getDouble(i));
            x.code = d.hours.isEmpty() ? d.code : hcode.getInt(i);
            x.day = hday.getInt(i) == 1;
            x.precipMm = h.getJSONArray("precipitation").optDouble(i, 0);
            x.precipProb = h.getJSONArray("precipitation_probability").optInt(i, 0);
            d.hours.add(x);
        }

        JSONObject dl = root.getJSONObject("daily");
        JSONArray dt = dl.getJSONArray("time");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat dow = new SimpleDateFormat("EE", new Locale("ru"));
        for (int i = 0; i < dt.length(); i++) {
            Day x = new Day();
            if (i == 0) {
                x.label = "Сегодня";
            } else {
                Calendar cal = Calendar.getInstance();
                cal.setTime(in.parse(dt.getString(i)));
                String s = dow.format(cal.getTime());
                x.label = s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1).replace(".", "");
            }
            x.code = dl.getJSONArray("weather_code").getInt(i);
            x.min = round(dl.getJSONArray("temperature_2m_min").getDouble(i));
            x.max = round(dl.getJSONArray("temperature_2m_max").getDouble(i));
            x.precip = dl.getJSONArray("precipitation_probability_max").optInt(i, 0);
            x.precipMm = dl.getJSONArray("precipitation_sum").optDouble(i, 0);
            d.days.add(x);
        }
        if (!d.days.isEmpty()) {
            d.min = d.days.get(0).min;
            d.max = d.days.get(0).max;
        }
        return d;
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    static String deg(int t) {
        return t + "°";
    }

    /** WMO weather interpretation codes, in Russian, capitalised like the iPhone Weather app. */
    static String describe(int code) {
        if (code == 0) return "Ясно";
        if (code <= 2) return "Переменная облачность";
        if (code == 3) return "Пасмурно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Морось";
        if (code >= 61 && code <= 67) return "Дождь";
        if (code >= 71 && code <= 77) return "Снег";
        if (code >= 80 && code <= 82) return "Ливень";
        if (code == 85 || code == 86) return "Снегопад";
        if (code >= 95) return "Гроза";
        return "";
    }

    /** Weather glyph (emoji: colour icons without shipping image assets). */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "🌨️";
        if (code >= 80 && code <= 82) return "🌧️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    /** Sky gradient (top, bottom) for the conditions, like the iPhone Weather backgrounds. */
    static int[] sky(int code, boolean day) {
        if (!day) return new int[]{0xFF0B1026, 0xFF1C2541};
        if (code == 0 || code <= 2) return new int[]{0xFF2E6FD8, 0xFF6FA8F0};
        if (code >= 95) return new int[]{0xFF2B2F3A, 0xFF4A4F5C};
        if (code >= 51) return new int[]{0xFF3E4A5C, 0xFF6B7788};
        return new int[]{0xFF4F5D73, 0xFF8291A6};   // overcast, fog
    }
}
