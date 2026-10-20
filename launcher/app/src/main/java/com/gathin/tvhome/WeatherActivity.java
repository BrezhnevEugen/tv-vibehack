package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** Full-screen weather from the Weather tile: the screensaver's layout, refreshed on open. Back closes it. */
public class WeatherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        View root = findViewById(R.id.dreamRoot);
        WeatherPanel panel = new WeatherPanel(this, root);
        if (Weather.last != null) panel.show(Weather.last);
        Weather.fetch(this, d -> {
            if (d != null && !isFinishing()) panel.show(d);
        });
    }
}
