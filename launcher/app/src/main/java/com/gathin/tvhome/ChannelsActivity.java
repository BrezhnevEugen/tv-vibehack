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
    private final List<Object> rows = new ArrayList<>();   // String = group header, Channel = channel
    private final List<Iptv.Channel> channels = new ArrayList<>();
    private ExoPlayer player;
    private ListView list;
    private View panel;
    private TextView badge, status;
    private int current = -1;
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
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setText("");
            }
        });

        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object row = rows.get(pos);
            if (row instanceof Iptv.Channel && ((Iptv.Channel) row).available()) {
                play(channels.indexOf(row));
                hideList();
            }
        });

        if (Iptv.cache != null) setChannels(Iptv.cache);
        Iptv.load(this, cs -> {
            if (!isFinishing() && cs != null && channels.isEmpty()) setChannels(cs);
            else if (cs == null && channels.isEmpty()) status.setText("Не удалось загрузить список каналов");
        });
    }

    private void setChannels(List<Iptv.Channel> cs) {
        channels.clear();
        channels.addAll(cs);
        rows.clear();
        String group = null;
        for (Iptv.Channel c : cs) {
            if (!c.group.equals(group)) {
                group = c.group;
                rows.add(group);
            }
            rows.add(c);
        }
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
    }

    private void play(int index) {
        play(index, 1);
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
        player.setMediaSource(new HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(c.url)));
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
        if (current >= 0) panel.setVisibility(View.GONE);
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
        } else if (keyCode == KeyEvent.KEYCODE_BACK && current >= 0) {
            hideList();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.pause();
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
            return rows.get(i) instanceof Iptv.Channel;
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
            if (row instanceof String) {
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
