package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;

import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Below the date: current weather (config.xml, weather_*), refreshed every 30 minutes while it runs.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    private static final long WEATHER_MS = 30 * 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private android.widget.TextView weather;

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(ClockDream.this, ClockDream.this::showWeather);
            handler.postDelayed(this, WEATHER_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        weather = findViewById(R.id.dreamWeather);
        showWeather(Weather.last);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        handler.post(weatherTick);
    }

    private void showWeather(String text) {
        if (weather == null || text == null) return;
        weather.setText(text);
        weather.setVisibility(View.VISIBLE);
        // The block just got wider: move it back on screen if it now sticks out.
        content.post(() -> {
            View parent = (View) content.getParent();
            if (content.getX() + content.getWidth() > parent.getWidth()
                    || content.getY() + content.getHeight() > parent.getHeight()) {
                placeClock();
            }
        });
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

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

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
