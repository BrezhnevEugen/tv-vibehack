package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live TV from public streams: Russian federal channels and world news (config.xml, iptv_*).
 * Up/Down switch channels while watching, OK/Left open the list, Back hides the list, then exits.
 */
@OptIn(markerClass = UnstableApi.class)
public class ChannelsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Object> rows = new ArrayList<>();   // String = header, Channel = channel, Action = menu item
    private List<Iptv.Channel> favourites;

    /** A menu row: "Каналы по странам ›", a country, "‹ Назад". */
    private static final class Action {
        final String label;
        final Runnable run;

        Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }
    private final List<Iptv.Channel> channels = new ArrayList<>();
    private ExoPlayer player;
    private ListView list;
    private View panel;
    private TextView badge, status;
    private int current = -1;
    /** The list stays open on entry (Russian channels) until a channel is picked. */
    private boolean pinned = true;
    private SharedPreferences prefs;
    private final Runnable hideBadge = () -> badge.setVisibility(View.GONE);
    private final Runnable hideList = this::hideList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channels);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);
        list = findViewById(R.id.list);
        panel = findViewById(R.id.listPanel);
        badge = findViewById(R.id.badge);
        status = findViewById(R.id.status);
        status.setText("Проверяю каналы…");

        player = new ExoPlayer.Builder(this).build();
        ((PlayerView) findViewById(R.id.player)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("Канал сейчас недоступен");
                if (current >= 0) {
                    Iptv.Channel dead = channels.get(current);
                    dead.state = -1;
                    if (favourites != null && favourites.contains(dead)) Iptv.save(ChannelsActivity.this, favourites);
                    rows.remove(dead);   // gone from the list, like after a check
                    adapter.notifyDataSetChanged();
                    play(current + 1);   // and straight on to the next channel
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setText("");
                    if (current >= 0 && channels.get(current).state == 0) {
                        channels.get(current).state = 1;
                        if (favourites != null && favourites.contains(channels.get(current))) Iptv.save(ChannelsActivity.this, favourites);
                    }
                }
            }
        });

        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object row = rows.get(pos);
            if (row instanceof Action) {
                ((Action) row).run.run();
            } else if (row instanceof Iptv.Channel && ((Iptv.Channel) row).available()) {
                pinned = false;
                play(channels.indexOf(row));
                hideList();
            }
        });

        // Instant open from the last saved list; the network is only touched on first run or on the check button.
        List<Iptv.Channel> saved = Iptv.cache != null ? Iptv.cache : Iptv.restore(this);
        if (saved != null) {
            setChannels(saved);
        } else {
            status.setText("Загрузка каналов…");
            Iptv.load(this, false, cs -> {
                if (isFinishing()) return;
                if (cs != null) setChannels(cs);
                else status.setText("Не удалось загрузить список каналов");
            });
        }
    }

    /** "Проверить каналы": tries every stream, hides the dead ones, remembers the result. */
    private void checkChannels() {
        status.setText("Проверяю каналы… это займёт до минуты");
        rows.add(rows.size(), "Проверка…");
        adapter.notifyDataSetChanged();
        Iptv.load(this, true, cs -> {
            if (isFinishing()) return;
            status.setText("");
            if (cs != null) {
                Iptv.Channel playing = current >= 0 && current < channels.size() ? channels.get(current) : null;
                setChannels(cs);
                if (playing != null) badge.setText(playing.name);
            }
        });
    }

    private void setChannels(List<Iptv.Channel> cs) {
        favourites = cs;
        channels.clear();
        channels.addAll(cs);
        rows.clear();
        String group = null;
        for (Iptv.Channel c : cs) {
            if (!c.available()) continue;   // only channels that actually answer
            if (!c.group.equals(group)) {
                group = c.group;
                rows.add(group);
            }
            rows.add(c);
        }
        rows.add("Ещё");
        rows.add(new Action("Каналы по странам  ›", this::showCountries));
        rows.add(new Action("Каналы по темам  ›", this::showTopics));
        rows.add(new Action("🔄  Проверить каналы", this::checkChannels));
        adapter.notifyDataSetChanged();
        String last = prefs.getString("last", null);
        int start = -1;
        for (int i = 0; i < channels.size(); i++) {
            Iptv.Channel c = channels.get(i);
            if (c.available() && (start < 0 || c.name.equals(last))) start = i;
        }
        if (start < 0) {
            status.setText("Ни один канал сейчас не отвечает");
            showList();
            return;
        }
        play(start);
        showList();
        list.setSelection(0);   // top of the list: the Russian federal channels
    }

    private void play(int index) {
        play(index, 1);
    }

    /** Back to the curated list, keeping what plays. */
    private void showFavourites() {
        countryName = null;
        Iptv.Channel playing = current >= 0 ? channels.get(current) : null;
        channels.clear();
        channels.addAll(favourites);
        rows.clear();
        String group = null;
        for (Iptv.Channel c : favourites) {
            if (!c.available()) continue;
            if (!c.group.equals(group)) {
                group = c.group;
                rows.add(group);
            }
            rows.add(c);
        }
        rows.add("Ещё");
        rows.add(new Action("Каналы по странам  ›", this::showCountries));
        rows.add(new Action("Каналы по темам  ›", this::showTopics));
        rows.add(new Action("🔄  Проверить каналы", this::checkChannels));
        current = playing != null ? channels.indexOf(playing) : -1;
        adapter.notifyDataSetChanged();
        list.setSelection(1);
    }

    private void showCountries() {
        countryName = null;
        rows.clear();
        rows.add(new Action("‹  Избранные каналы", this::showFavourites));
        rows.add("Страны");
        for (String line : getResources().getStringArray(R.array.iptv_countries)) {
            String[] kv = line.split("\\|", 2);
            if (kv.length == 2) rows.add(new Action(kv[1], () -> showCountry(kv[0], kv[1])));
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
    }

    private void showTopics() {
        countryName = null;
        rows.clear();
        rows.add(new Action("‹  Избранные каналы", this::showFavourites));
        rows.add("Темы");
        for (String line : getResources().getStringArray(R.array.iptv_topics)) {
            String[] kv = line.split("\\|", 2);
            if (kv.length == 2) rows.add(new Action(kv[1], () -> showCountry(kv[0], kv[1], true)));
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
    }

    private void showCountry(String code, String name) {
        showCountry(code, name, false);
    }

    /** A country's (or, with topic, a theme's) channels. */
    private void showCountry(String code, String name, boolean topic) {
        backAction = topic ? new Action("‹  Темы", this::showTopics) : new Action("‹  Страны", this::showCountries);
        filter = topic ? null : "General";   // a country opens on its broadcast channels
        rows.clear();
        rows.add(backAction);
        rows.add(name + " · загрузка…");
        adapter.notifyDataSetChanged();
        java.util.function.Consumer<List<Iptv.Channel>> onList = cs -> {
            if (isFinishing()) return;
            rows.clear();
            rows.add(backAction);
            if (cs == null || cs.isEmpty()) {
                rows.add(name + " · нет каналов");
                adapter.notifyDataSetChanged();
                return;
            }
            Iptv.Channel playing = current >= 0 && current < channels.size() ? channels.get(current) : null;
            channels.clear();
            channels.addAll(cs);
            current = -1;
            countryName = name;
            refreshCountry();
            list.setSelection(2);
            if (playing != null) badge.setText(playing.name);
        };
        Runnable onUpdate = () -> {
            if (countryName != null && countryName.equals(name)) refreshCountry();
        };
        if (topic) Iptv.loadTopic(code, name, onList, onUpdate);
        else Iptv.loadCountry(code, name, onList, onUpdate);
    }

    private String countryName;
    private Action backAction;
    /** Theme filter in a country list: null = all themes, grouped under headers. */
    private String filter;

    private void nextFilter() {
        java.util.TreeSet<String> cats = new java.util.TreeSet<>();
        for (Iptv.Channel c : channels) if (c.state != -1) cats.add(c.category);
        List<String> order = new ArrayList<>(cats);
        if (order.remove("General")) order.add(0, "General");
        if (order.isEmpty()) return;
        if (filter == null) filter = order.get(0);
        else {
            int i = order.indexOf(filter);
            filter = i < 0 || i + 1 >= order.size() ? null : order.get(i + 1);
        }
        refreshCountry();
        list.setSelection(1);
    }

    /** Country list without the channels that turned out dead, so only watchable ones remain. */
    private void refreshCountry() {
        Object sel = list.getSelectedItem();
        rows.clear();
        rows.add(backAction);
        int checked = 0, live = 0;
        for (Iptv.Channel c : channels) {
            if (c.state != 0) checked++;
            if (c.state == 1) live++;
        }
        rows.add(countryName + (checked < channels.size()
                ? " · проверено " + checked + " из " + channels.size()
                : " · работают " + live));
        if ("General".equals(filter)) {   // no broadcast channels in this list: show everything
            boolean any = false;
            for (Iptv.Channel c : channels) if (c.state != -1 && "General".equals(c.category)) any = true;
            if (!any) filter = null;
        }
        rows.add(new Action("Тема: " + (filter == null ? "все" : Iptv.themeRu(filter)) + "   (OK — следующая)", this::nextFilter));
        List<Iptv.Channel> shown = new ArrayList<>();
        for (Iptv.Channel c : channels) {
            if (c.state == -1) continue;
            if (filter != null && !filter.equals(c.category)) continue;
            shown.add(c);
        }
        if (filter == null) {   // all themes: grouped by theme, then by name
            shown.sort((a, b) -> {
                boolean ga = "General".equals(a.category), gb = "General".equals(b.category);
                if (ga != gb) return ga ? -1 : 1;   // broadcast channels first
                int t = Iptv.themeRu(a.category).compareTo(Iptv.themeRu(b.category));
                return t != 0 ? t : a.name.compareToIgnoreCase(b.name);
            });
            String head = null;
            for (Iptv.Channel c : shown) {
                String h = Iptv.themeRu(c.category);
                if (!h.equals(head)) {
                    rows.add(h);
                    head = h;
                }
                rows.add(c);
            }
        } else {
            rows.addAll(shown);
        }
        adapter.notifyDataSetChanged();
        int at = sel != null ? rows.indexOf(sel) : -1;
        if (at >= 0) list.setSelection(at);
    }

    /** Plays the channel at index, skipping unavailable ones in the given direction. */
    private void play(int index, int dir) {
        if (channels.isEmpty()) return;
        int n = channels.size(), i = ((index % n) + n) % n;
        for (int k = 0; k < n && !channels.get(i).available(); k++) i = ((i + dir) % n + n) % n;
        if (!channels.get(i).available()) return;
        current = i;
        Iptv.Channel c = channels.get(current);
        prefs.edit().putString("last", c.name).apply();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(c.userAgent != null ? c.userAgent : "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        if (c.referer != null) {
            Map<String, String> h = new HashMap<>();
            h.put("Referer", c.referer);
            http.setDefaultRequestProperties(h);
        }
        // HLS (.m3u8) or DASH (.mpd, e.g. Первый канал), picked from the URL.
        player.setMediaSource(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http)
                .createMediaSource(MediaItem.fromUri(c.url)));
        player.prepare();
        player.setPlayWhenReady(true);
        status.setText("");
        badge.setText(c.name);
        badge.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBadge);
        handler.postDelayed(hideBadge, 3000);
        int row = rows.indexOf(c);
        if (row >= 0) list.setSelection(row);
    }

    private void showList() {
        panel.setVisibility(View.VISIBLE);
        int row = current >= 0 ? rows.indexOf(channels.get(current)) : 1;
        list.requestFocus();
        list.setSelection(Math.max(0, row));
        handler.removeCallbacks(hideList);
        handler.postDelayed(hideList, 8000);
    }

    private void hideList() {
        if (current >= 0 && !pinned) panel.setVisibility(View.GONE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel.getVisibility() == View.VISIBLE && e.getAction() == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(hideList);
            handler.postDelayed(hideList, 8000);   // any key keeps the list open a bit longer
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean listShown = panel.getVisibility() == View.VISIBLE;
        if (!listShown) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    play(current - 1, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    play(current + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MENU:
                    showList();
                    return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK && current >= 0 && !pinned) {
            hideList();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.pause();
        finish();   // reopening the tile always starts on the open Russian list; nothing plays in the background
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (current >= 0) player.play();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        player.release();
        super.onDestroy();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean isEnabled(int i) {
            return !(rows.get(i) instanceof String);
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            TextView t = v instanceof TextView ? (TextView) v : new TextView(ChannelsActivity.this);
            Object row = rows.get(i);
            float d = getResources().getDisplayMetrics().density;
            t.setTypeface(getResources().getFont(R.font.outfit));
            if (row instanceof Action) {
                t.setText(((Action) row).label);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setTextColor(0xFF8CD3FF);
                t.setFontVariationSettings("'wght' 500");
                t.setPadding((int) (24 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
            } else if (row instanceof String) {
                t.setText(((String) row).toUpperCase());
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setTextColor(0x99FFFFFF);
                t.setFontVariationSettings("'wght' 600");
                t.setPadding((int) (24 * d), (int) (14 * d), 0, (int) (4 * d));
            } else {
                Iptv.Channel c = (Iptv.Channel) row;
                t.setText(c.available() ? c.name : c.name + "  · нет сигнала");
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setTextColor(!c.available() ? 0x59FFFFFF : channels.indexOf(c) == current ? 0xFF8CD3FF : 0xFFFFFFFF);
                t.setFontVariationSettings("'wght' 400");
                t.setPadding((int) (24 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
            }
            t.setTypeface(t.getTypeface(), Typeface.NORMAL);
            return t;
        }
    };
}
