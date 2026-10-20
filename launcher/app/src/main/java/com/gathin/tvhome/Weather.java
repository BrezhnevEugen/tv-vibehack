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
        /** Today's sunrise and sunset (epoch ms), or 0. */
        long sunrise, sunset;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
    }

    /** Last good result, so the screensaver shows something at once while it refreshes. */
    static volatile Data last;

    private Weather() {
    }

    static void fetch(Context ctx, Consumer<Data> onResult) {
        final Context app = ctx.getApplicationContext();
        final String city = Prefs.city(ctx);
        final String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,is_day,precipitation,precipitation_probability&forecast_hours=25"
                        + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,precipitation_sum,sunrise,sunset"
                        + "&forecast_days=10&wind_speed_unit=ms&timezone=auto",
                Prefs.lat(ctx), Prefs.lon(ctx));
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
            } catch (Exception e) {
                android.util.Log.w("TvHomeWeather", "open-meteo failed: " + e);
            } finally {
                if (c != null) c.disconnect();
            }
            if (data == null) {   // Open-Meteo down or unreachable: MET Norway (also free, no key)
                try {
                    data = metNo(city, Prefs.lat(app), Prefs.lon(app));
                } catch (Exception e) {
                    android.util.Log.w("TvHomeWeather", "met.no failed: " + e);
                }
            }
            if (data != null) {
                last = data;
                if (data.sunrise > 0 && data.sunset > 0) Prefs.setSun(app, data.sunrise, data.sunset);
            }
            final Data result = data != null ? data : last;
            main.post(() -> onResult.accept(result));
        }, "weather").start();
    }

    /** Same Data from api.met.no (Norwegian Meteorological Institute): hourly series, aggregated into days here. */
    private static Data metNo(String city, String lat, String lon) throws Exception {
        String json = Net.text(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f",
                Double.parseDouble(lat), Double.parseDouble(lon)),
                java.util.Collections.singletonMap("User-Agent", "tvhome/1.0 github.com/BrezhnevEugen/tv-vibehack"));
        JSONArray ts = new JSONObject(json).getJSONObject("properties").getJSONArray("timeseries");
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        java.text.SimpleDateFormat hh = new java.text.SimpleDateFormat("HH", Locale.US);
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.text.SimpleDateFormat dow = new java.text.SimpleDateFormat("EE", new Locale("ru"));
        Data d = new Data();
        d.city = city;
        long now = System.currentTimeMillis();
        java.util.LinkedHashMap<String, Day> days = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> dayCodeHour = new java.util.HashMap<>();
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.getJSONObject(i);
            long when = iso.parse(t.getString("time")).getTime();
            JSONObject data = t.getJSONObject("data");
            JSONObject inst = data.getJSONObject("instant").getJSONObject("details");
            int temp = round(inst.getDouble("air_temperature"));
            JSONObject n1 = data.optJSONObject("next_1_hours"), n6 = data.optJSONObject("next_6_hours");
            JSONObject next = n1 != null ? n1 : n6;
            String sym = next != null ? next.getJSONObject("summary").optString("symbol_code", "cloudy") : "cloudy";
            double mm = next != null ? next.optJSONObject("details") != null ? next.getJSONObject("details").optDouble("precipitation_amount", 0) : 0 : 0;
            int prob = next != null && next.optJSONObject("details") != null
                    ? (int) Math.round(next.getJSONObject("details").optDouble("probability_of_precipitation", 0)) : 0;
            if (when + 3_600_000L < now) continue;
            if (d.hours.isEmpty()) {
                d.temp = temp;
                d.feels = round(inst.optDouble("apparent_air_temperature", inst.getDouble("air_temperature")));
                d.humidity = (int) Math.round(inst.optDouble("relative_humidity", 0));
                d.wind = round(inst.optDouble("wind_speed", 0));
                d.code = wmo(sym);
                d.day = !sym.endsWith("_night");
            }
            if (d.hours.size() < 24 && n1 != null) {
                Hour h = new Hour();
                h.label = d.hours.isEmpty() ? "Сейчас" : hh.format(new java.util.Date(when));
                h.temp = temp;
                h.code = wmo(sym);
                h.day = !sym.endsWith("_night");
                h.precipMm = mm;
                h.precipProb = prob;
                d.hours.add(h);
            }
            String key = day.format(new java.util.Date(when));
            Day x = days.get(key);
            if (x == null) {
                if (days.size() >= 10) continue;
                x = new Day();
                x.min = temp;
                x.max = temp;
                x.code = wmo(sym);
                days.put(key, x);
            }
            x.min = Math.min(x.min, temp);
            x.max = Math.max(x.max, temp);
            x.precipMm += mm;
            x.precip = Math.max(x.precip, prob);
            int hour = Integer.parseInt(hh.format(new java.util.Date(when)));
            Integer best = dayCodeHour.get(key);   // the day's symbol: the one closest to 13:00
            if (best == null || Math.abs(hour - 13) < Math.abs(best - 13)) {
                dayCodeHour.put(key, hour);
                x.code = wmo(sym);
            }
        }
        int i = 0;
        for (java.util.Map.Entry<String, Day> e : days.entrySet()) {
            Day x = e.getValue();
            if (i == 0) x.label = "Сегодня";
            else {
                String s = dow.format(day.parse(e.getKey()));
                x.label = s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1).replace(".", "");
            }
            d.days.add(x);
            i++;
        }
        if (!d.days.isEmpty()) {
            d.min = d.days.get(0).min;
            d.max = d.days.get(0).max;
        }
        return d;
    }

    /** MET Norway symbol → WMO code, so the rest of the code (icons, sky, text) stays the same. */
    private static int wmo(String sym) {
        String s = sym.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
        if (s.equals("clearsky")) return 0;
        if (s.equals("fair")) return 1;
        if (s.equals("partlycloudy")) return 2;
        if (s.equals("cloudy")) return 3;
        if (s.equals("fog")) return 45;
        if (s.contains("thunder")) return 95;
        if (s.contains("snow") || s.contains("sleet")) return s.contains("showers") ? 85 : 73;
        if (s.contains("rainshowers")) return 80;
        if (s.contains("lightrain")) return 61;
        if (s.contains("heavyrain")) return 65;
        if (s.contains("rain")) return 63;
        return 3;
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
        // Local times ("2026-10-20T06:58") in the city's time zone, which is the TV's.
        SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        try {
            d.sunrise = local.parse(dl.getJSONArray("sunrise").getString(0)).getTime();
            d.sunset = local.parse(dl.getJSONArray("sunset").getString(0)).getTime();
        } catch (Exception ignored) {   // polar day or night: no sunrise or sunset
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
