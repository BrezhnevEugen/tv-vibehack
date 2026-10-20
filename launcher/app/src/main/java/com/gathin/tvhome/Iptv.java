package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Channel list for the TV tile: public iptv-org playlists filtered to the channels in config.xml. */
final class Iptv {

    static final class Channel {
        final String name, group;
        /** Theme from the playlist (iptv-org group-title, first one), e.g. "News". */
        String category = "";
        /** Stream to play: the first candidate that answered (or, before probing, the best guess). */
        String url, userAgent, referer;
        /** 0 not checked yet, 1 answers, -1 no candidate answered. */
        volatile int state;

        Channel(String name, String group) {
            this.name = name;
            this.group = group;
        }

        boolean available() {
            return url != null && state != -1;
        }
    }

    /** One playlist entry before filtering. */
    private static final class Entry {
        String id, variant, url, ua, ref, name, cat;
        boolean geo, hd;
    }

    static volatile List<Channel> cache;

    private Iptv() {
    }

    static void load(Context ctx, Consumer<List<Channel>> onResult) {
        final String[] playlists = ctx.getResources().getStringArray(R.array.iptv_playlists);
        final String[] russia = ctx.getResources().getStringArray(R.array.iptv_russia);
        final String[] world = ctx.getResources().getStringArray(R.array.iptv_world);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<Entry> all = new ArrayList<>();
            for (String p : playlists) {
                try {
                    all.addAll(parse(p));
                } catch (Exception ignored) {
                }
            }
            List<Channel> out = new ArrayList<>();
            synchronized (probes) {
                probes.clear();
                pick(all, russia, "Россия", out);
                pick(all, world, "Мир", out);
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(8);
                for (Runnable r : probes) ex.submit(r);
                ex.shutdown();
                try {
                    ex.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                probes.clear();
            }
            if (!out.isEmpty()) cache = out;
            final List<Channel> result = out.isEmpty() ? cache : out;
            main.post(() -> onResult.accept(result));
        }, "iptv").start();
    }

    /** For each wanted "id[@variant]|Name", the best entry: not geo-blocked, HD, first in the list. */
    private static void pick(List<Entry> all, String[] wanted, String group, List<Channel> out) {
        for (String w : wanted) {
            String[] kv = w.split("\\|", 2);
            if (kv.length != 2) continue;
            String id = kv[0], variant = null;
            int at = id.indexOf('@');
            if (at >= 0) {
                variant = id.substring(at + 1);
                id = id.substring(0, at);
            }
            List<Entry> cands = new ArrayList<>();
            for (Entry e : all) {
                if (!e.id.equalsIgnoreCase(id)) continue;
                if (variant != null && !variant.equalsIgnoreCase(e.variant)) continue;
                cands.add(e);
            }
            if (cands.isEmpty()) continue;
            cands.sort((a, b) -> Integer.compare(score(b), score(a)));
            Channel ch = new Channel(kv[1], group);
            out.add(ch);
            probes.add(() -> {
                probe(ch, cands);
            });
        }
    }

    private static final List<Runnable> probes = new ArrayList<>();

    private static void probe(Channel ch, List<Entry> cands) {
        for (Entry e : cands) {
            if (reachable(e)) {
                ch.url = e.url;
                ch.userAgent = e.ua;
                ch.referer = e.ref;
                ch.state = 1;
                return;
            }
        }
        ch.state = -1;
    }

    private static java.util.concurrent.ExecutorService countryProbe;

    /** iptv-org theme names in Russian. */
    static String themeRu(String c) {
        switch (c) {
            case "News": return "Новости";
            case "Music": return "Музыка";
            case "Movies": return "Кино";
            case "Series": return "Сериалы";
            case "Sports": return "Спорт";
            case "Kids": return "Детские";
            case "Animation": return "Мультфильмы";
            case "Documentary": return "Документальные";
            case "Entertainment": return "Развлечения";
            case "General": return "Общие";
            case "Lifestyle": return "Стиль и мода";
            case "Religious": return "Религия";
            case "Business": return "Бизнес";
            case "Culture": return "Культура";
            case "Education": return "Образование";
            case "Science": return "Наука";
            case "Travel": return "Путешествия";
            case "Cooking": return "Кулинария";
            case "Comedy": return "Юмор";
            case "Family": return "Семейные";
            case "Auto": return "Авто";
            case "Classic": return "Классика";
            case "Relax": return "Релакс";
            case "Outdoor": return "Природа";
            case "Shop": return "Магазины";
            case "Legislative": return "Парламент";
            case "Weather": return "Погода";
            case "": case "Undefined": return "Без темы";
            default: return c;
        }
    }

    /**
     * All channels of one country (iptv-org countries/<code>.m3u), sorted by name. The list comes back at once
     * with best-guess streams; each channel is then checked in the background and onUpdate fires as results land.
     */
    static void loadCountry(String code, String country, Consumer<List<Channel>> onList, Runnable onUpdate) {
        loadPlaylist("https://iptv-org.github.io/iptv/countries/" + code + ".m3u", country, onList, onUpdate);
    }

    /** Same for a theme playlist (iptv-org categories/<name>.m3u). */
    static void loadTopic(String category, String title, Consumer<List<Channel>> onList, Runnable onUpdate) {
        loadPlaylist("https://iptv-org.github.io/iptv/categories/" + category + ".m3u", title, onList, onUpdate);
    }

    private static void loadPlaylist(String url, String country, Consumer<List<Channel>> onList, Runnable onUpdate) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (countryProbe != null) countryProbe.shutdownNow();
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        countryProbe = pool;
        new Thread(() -> {
            List<Entry> all;
            try {
                all = parse(url);
            } catch (Exception e) {
                main.post(() -> onList.accept(null));
                return;
            }
            Map<String, List<Entry>> byId = new java.util.LinkedHashMap<>();
            for (Entry e : all) {
                String key = e.id.isEmpty() ? e.name : e.id;
                byId.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
            List<Channel> out = new ArrayList<>();
            for (List<Entry> cands : byId.values()) {
                cands.sort((a, b) -> Integer.compare(score(b), score(a)));
                Entry best = cands.get(0);
                Channel ch = new Channel(best.name, country);
                ch.url = best.url;
                ch.userAgent = best.ua;
                ch.referer = best.ref;
                ch.category = best.cat == null ? "" : best.cat;
                out.add(ch);
                pool.submit(() -> {
                    probe(ch, cands);
                    main.post(onUpdate);
                });
                if (out.size() >= 400) break;
            }
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            pool.shutdown();
            main.post(() -> onList.accept(out));
        }, "iptv-country").start();
    }

    private static int score(Entry e) {
        return (e.geo ? 0 : 2) + (e.hd ? 1 : 0);
    }

    /** Does the playlist URL answer with an HLS playlist within a few seconds? Many public streams die or are blocked. */
    private static boolean reachable(Entry e) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(e.url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", e.ua != null ? e.ua : "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            if (e.ref != null) c.setRequestProperty("Referer", e.ref);
            if (c.getResponseCode() != 200) return false;
            byte[] buf = new byte[256];
            int n = c.getInputStream().read(buf);
            return n > 0 && new String(buf, 0, n, "UTF-8").contains("#EXTM3U");
        } catch (Exception ex) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<Entry> parse(String url) throws Exception {
        List<Entry> out = new ArrayList<>();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            Entry cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    cur = new Entry();
                    Map<String, String> attrs = attrs(line);
                    String tvg = attrs.getOrDefault("tvg-id", "");
                    int at = tvg.indexOf('@');
                    String base = at >= 0 ? tvg.substring(0, at) : tvg;
                    cur.variant = at >= 0 ? tvg.substring(at + 1) : "";
                    int dot = base.lastIndexOf('.');
                    cur.id = dot > 0 ? base.substring(0, dot) : base;
                    cur.ua = attrs.get("http-user-agent");
                    String g = attrs.getOrDefault("group-title", "");
                    cur.cat = g.contains(";") ? g.substring(0, g.indexOf(';')) : g;
                    cur.ref = attrs.get("http-referrer");
                    cur.geo = line.contains("[Geo-blocked]");
                    cur.hd = cur.variant.toUpperCase().contains("HD") || line.contains("(1080p)");
                    String title = line.substring(line.lastIndexOf(',') + 1);
                    cur.name = title.replaceAll("\\s*\\([0-9]+[pi]\\)", "").replaceAll("\\s*\\[[^]]*]", "").trim();
                } else if (line.startsWith("#EXTVLCOPT:") && cur != null) {
                    String opt = line.substring(11);
                    if (opt.startsWith("http-user-agent=")) cur.ua = opt.substring(16);
                    else if (opt.startsWith("http-referrer=")) cur.ref = opt.substring(14);
                } else if (!line.isEmpty() && !line.startsWith("#") && cur != null) {
                    cur.url = line;
                    if (!cur.id.isEmpty() || !cur.name.isEmpty()) out.add(cur);
                    cur = null;
                }
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (true) {
            int eq = line.indexOf("=\"", i);
            if (eq < 0) break;
            int start = line.lastIndexOf(' ', eq) + 1;
            int end = line.indexOf('"', eq + 2);
            if (end < 0) break;
            m.put(line.substring(start, eq), line.substring(eq + 2, end));
            i = end + 1;
        }
        return m;
    }
}
