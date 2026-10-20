package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * "В тренде": what is trending this week on TMDB (films and series, in Russian), refreshed at most once an hour.
 * The Home tile shows one pick at a time, a new one every hour, over its own backdrop photo.
 * After Free My TV's Recs, minus the "only from your apps" filter: TMDB barely knows Russian services.
 */
final class Picks {

    static final class Pick {
        int id;
        boolean tv;
        String title, overview, year, backdrop;
        double rating;
    }

    static final String API = "https://api.themoviedb.org/3";
    static final String IMAGES = "https://image.tmdb.org/t/p/";

    /** Backdrop of current(), for the tile and the Home background; null until loaded. */
    static volatile Bitmap art;
    private static volatile int artFor = -1;
    private static volatile boolean busy;

    private Picks() {
    }

    /** The saved list (may be empty). */
    static List<Pick> all(Context c) {
        List<Pick> out = new ArrayList<>();
        String json = Prefs.picksJson(c);
        if (json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pick p = new Pick();
                p.id = o.getInt("id");
                p.tv = "tv".equals(o.optString("media_type"));
                p.title = o.optString(p.tv ? "name" : "title");
                p.overview = o.optString("overview");
                String date = o.optString(p.tv ? "first_air_date" : "release_date");
                p.year = date.length() >= 4 ? date.substring(0, 4) : "";
                p.backdrop = o.optString("backdrop_path");
                p.rating = o.optDouble("vote_average", 0);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** This hour's pick, or null. */
    static Pick current(Context c) {
        List<Pick> list = all(c);
        if (list.isEmpty()) return null;
        return list.get((int) ((System.currentTimeMillis() / 3_600_000L) % list.size()));
    }

    /**
     * Fetches the list when it is older than an hour and the current pick's photo when it is missing; then calls
     * onChange on the main thread. Does nothing when the tile is off.
     */
    static void refresh(Context ctx, Runnable onChange) {
        final Context app = ctx.getApplicationContext();
        if (!Prefs.picksOn(app) || busy) return;
        boolean stale = System.currentTimeMillis() - Prefs.picksTime(app) > 3_600_000L || Prefs.picksJson(app).isEmpty();
        Pick now = current(app);
        if (!stale && now != null && artFor == now.id && art != null) return;
        busy = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                if (stale) {
                    JSONArray results = new JSONObject(get(app, "/trending/all/week?language=ru-RU")).getJSONArray("results");
                    JSONArray keep = new JSONArray();
                    for (int i = 0; i < results.length() && keep.length() < 9; i++) {
                        JSONObject o = results.getJSONObject(i);
                        String type = o.optString("media_type");
                        if (!("movie".equals(type) || "tv".equals(type)) || o.optString("backdrop_path").isEmpty()) continue;
                        keep.put(o);
                    }
                    if (keep.length() > 0) {
                        Prefs.setPicks(app, keep.toString());
                        changed = true;
                    }
                }
                Pick p = current(app);
                if (p != null && (artFor != p.id || art == null)) {
                    art = Net.image(IMAGES + "w1280" + p.backdrop, 1280);
                    artFor = p.id;
                    changed = true;
                }
            } catch (Exception ignored) {   // offline, bad key or TMDB unreachable: keep what we have
            } finally {
                busy = false;
            }
            if (changed) new Handler(Looper.getMainLooper()).post(onChange);
        }, "picks").start();
    }

    /** The YouTube id of the pick's trailer (Russian first, then English), or null. Blocking. */
    static String trailer(Context c, Pick p) {
        for (String lang : new String[]{"ru-RU", "en-US"}) {
            try {
                JSONArray v = new JSONObject(get(c, "/" + (p.tv ? "tv" : "movie") + "/" + p.id + "/videos?language=" + lang))
                        .getJSONArray("results");
                String teaser = null;
                for (int i = 0; i < v.length(); i++) {
                    JSONObject o = v.getJSONObject(i);
                    if (!"YouTube".equals(o.optString("site"))) continue;
                    if ("Trailer".equals(o.optString("type"))) return o.optString("key");
                    if (teaser == null && "Teaser".equals(o.optString("type"))) teaser = o.optString("key");
                }
                if (teaser != null) return teaser;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** TMDB accepts the short API key as a parameter and the long Read Access Token as a Bearer header. */
    private static String get(Context c, String path) throws IOException {
        String key = Prefs.tmdbKey(c);
        if (key.length() > 40) {
            return Net.text(API + path, Collections.singletonMap("Authorization", "Bearer " + key));
        }
        return Net.text(API + path + (path.contains("?") ? "&" : "?") + "api_key=" + android.net.Uri.encode(key), (Map<String, String>) null);
    }
}
