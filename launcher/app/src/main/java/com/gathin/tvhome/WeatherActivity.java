package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

/**
 * Full-screen weather from the Weather tile: the screensaver's layout, refreshed on open.
 * OK switches between the normal view and the detailed one (10 days, precipitation). Back closes it.
 */
public class WeatherActivity extends Activity {

    private WeatherPanel panel;
    private TextView hint;
    private boolean detailed;

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
        panel.show(Weather.last, detailed);
        hint.setText(detailed ? "OK — КРАТКО" : "OK — ПОДРОБНО: 10 ДНЕЙ И ОСАДКИ");
        hint.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            detailed = !detailed;
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
