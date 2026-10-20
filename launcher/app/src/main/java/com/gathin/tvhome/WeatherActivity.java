package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen weather from the Weather tile: the screensaver's layout, refreshed on open.
 * OK cycles: normal → detailed (10 days, precipitation) → precipitation map (config.xml, weather_map_url,
 * opened in a WebView like a browser would). Back closes the map, then the screen.
 */
public class WeatherActivity extends Activity {

    private WeatherPanel panel;
    private TextView hint;
    private int mode;   // 0 normal, 1 detailed, 2 map
    private WebView map;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        View root = findViewById(R.id.dreamRoot);
        panel = new WeatherPanel(this, root);
        hint = findViewById(R.id.wxHint);
        render();
        Weather.fetch(this, d -> {
            if (d != null && !isFinishing()) render();
        });
    }

    private void render() {
        if (Weather.last == null) return;
        panel.show(Weather.last, mode == 1);
        hint.setText(mode == 0 ? "OK — ПОДРОБНО: 10 ДНЕЙ И ОСАДКИ" : "OK — КАРТА ОСАДКОВ");
        hint.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (mode == 2) return super.onKeyDown(keyCode, event);   // the map handles its own keys
            mode++;
            if (mode == 2) showMap();
            else render();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && mode == 2) {
            closeMap();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showMap() {
        map = new WebView(this);
        WebSettings ws = map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        map.setWebViewClient(new WebViewClient());   // stay inside the view
        map.setBackgroundColor(0xFF000000);
        ((FrameLayout) findViewById(R.id.dreamRoot)).addView(map,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        map.loadUrl(getString(R.string.weather_map_url));
        map.requestFocus();
    }

    private void closeMap() {
        if (map != null) {
            ((ViewGroup) map.getParent()).removeView(map);
            map.destroy();
            map = null;
        }
        mode = 0;
        render();
    }

    @Override
    protected void onDestroy() {
        if (map != null) map.destroy();
        super.onDestroy();
    }
}
