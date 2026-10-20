package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.function.Consumer;

/** Current weather for the city in config.xml from Open-Meteo (free, no API key). One request per call. */
final class Weather {

    /** Last good result, so the screensaver shows something at once while it refreshes. */
    static volatile String last;

    private Weather() {
    }

    static void fetch(Context ctx, Consumer<String> onResult) {
        final String city = ctx.getString(R.string.weather_city);
        final String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,weather_code&timezone=auto",
                ctx.getString(R.string.weather_lat), ctx.getString(R.string.weather_lon));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text = null;
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
                    while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    long t = Math.round(cur.getDouble("temperature_2m"));
                    String temp = (t > 0 ? "+" : "") + t + "°";
                    text = city + "  ·  " + temp + "  ·  " + describe(cur.getInt("weather_code"));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            if (text != null) last = text;
            final String result = text != null ? text : last;
            main.post(() -> onResult.accept(result));
        }, "weather").start();
    }

    /** WMO weather interpretation codes, in Russian. */
    private static String describe(int code) {
        if (code == 0) return "ясно";
        if (code <= 2) return "переменная облачность";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 57) return "морось";
        if (code >= 61 && code <= 67) return "дождь";
        if (code >= 71 && code <= 77) return "снег";
        if (code >= 80 && code <= 82) return "ливень";
        if (code == 85 || code == 86) return "снегопад";
        if (code >= 95) return "гроза";
        return "";
    }
}
