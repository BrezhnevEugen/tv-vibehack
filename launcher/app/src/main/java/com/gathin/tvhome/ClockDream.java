package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Screensaver in the style of the iPhone Weather app: a sky gradient for the current conditions,
 * the city, a big temperature and today's range on the left, hourly and daily cards on the right,
 * and the clock in the corner. Weather (config.xml, weather_*) refreshes every 30 minutes while it runs.
 * Until the first forecast arrives (or offline) it is the clock on black with a slow ambient glow.
 * Everything drifts a little each minute so nothing burns in.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    private static final long WEATHER_MS = 30 * 60_000L;
    /** Deep, low-brightness glow colours for the offline fallback. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View root;
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private float density;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            drift();
            if (Weather.last == null) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(ClockDream.this, ClockDream.this::showWeather);
            handler.postDelayed(this, WEATHER_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        density = getResources().getDisplayMetrics().density;

        root = findViewById(R.id.dreamRoot);
        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.3f, 0.3f);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        showWeather(Weather.last);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(1200).start();
        handler.postDelayed(tick, MOVE_MS);
        handler.post(weatherTick);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(weatherTick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(weatherTick);
        super.onDetachedFromWindow();
    }

    /** Small random offset (up to 24dp) so static parts never sit on the same pixels for long. */
    private void drift() {
        float r = 24 * density;
        content.animate()
                .translationX((random.nextFloat() * 2 - 1) * r)
                .translationY((random.nextFloat() * 2 - 1) * r)
                .setDuration(4000).start();
    }

    // ---- weather ---------------------------------------------------------------------------------

    private void showWeather(Weather.Data d) {
        if (d == null || root == null) return;
        new WeatherPanel(this, root).show(d);
    }

    // ---- offline fallback glow -------------------------------------------------------------------

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
