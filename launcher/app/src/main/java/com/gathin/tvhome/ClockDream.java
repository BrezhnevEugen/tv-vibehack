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
        int[] sky = Weather.sky(d.code, d.day);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, sky);
        root.setBackground(bg);
        findViewById(R.id.dreamAmbient).setVisibility(View.GONE);

        ((TextView) findViewById(R.id.wxCity)).setText(d.city);
        ((TextView) findViewById(R.id.wxTemp)).setText(Weather.deg(d.temp));
        ((TextView) findViewById(R.id.wxCond)).setText(Weather.describe(d.code));
        ((TextView) findViewById(R.id.wxRange)).setText(
                "Макс.: " + Weather.deg(d.max) + ", мин.: " + Weather.deg(d.min));
        ((TextView) findViewById(R.id.wxExtra)).setText(
                "Ощущается как " + Weather.deg(d.feels) + "  ·  ветер " + d.wind + " м/с  ·  влажность " + d.humidity + "%");

        LinearLayout hours = findViewById(R.id.wxHours);
        hours.removeAllViews();
        for (Weather.Hour h : d.hours) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(text(h.label, 0xFFFFFFFF, h.label.length() > 2 ? 600 : 500, 13));
            TextView ic = text(Weather.icon(h.code, h.day), 0xFFFFFFFF, 400, 20);
            ic.setPadding(0, dp(4), 0, dp(4));
            cell.addView(ic);
            cell.addView(text(Weather.deg(h.temp), 0xFFFFFFFF, 500, 15));
            hours.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout days = findViewById(R.id.wxDays);
        days.removeAllViews();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Weather.Day x : d.days) {
            lo = Math.min(lo, x.min);
            hi = Math.max(hi, x.max);
        }
        for (int i = 0; i < d.days.size(); i++) {
            Weather.Day x = d.days.get(i);
            View line = new View(this);
            line.setBackgroundColor(0x26FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.setMargins(0, dp(5), 0, dp(5));
            days.addView(line, lp);
            days.addView(dayRow(x, lo, hi, i == 0 ? d.temp : Integer.MIN_VALUE));
        }

        findViewById(R.id.wxNow).setVisibility(View.VISIBLE);
        findViewById(R.id.wxCards).setVisibility(View.VISIBLE);
    }

    /** "Пн  🌧️ 40%  10°  ━━━━  19°", with the bar spanning the week's range like on the iPhone. */
    private View dayRow(Weather.Day x, int lo, int hi, int now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = text(x.label, 0xFFFFFFFF, 500, 15);
        row.addView(name, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout icon = new LinearLayout(this);
        icon.setOrientation(LinearLayout.VERTICAL);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        icon.addView(text(Weather.icon(x.code, true), 0xFFFFFFFF, 400, 17));
        if (x.precip >= 30) icon.addView(text(x.precip + "%", 0xFF8CD3FF, 600, 10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView min = text(Weather.deg(x.min), 0x99FFFFFF, 500, 15);
        min.setGravity(Gravity.END);
        row.addView(min, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x33000000);
        trackBg.setCornerRadius(dp(3));
        track.setBackground(trackBg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(6), 1f);
        tlp.setMargins(dp(16), 0, dp(16), 0);
        row.addView(track, tlp);
        final int span = Math.max(1, hi - lo);
        track.post(() -> {
            int w = track.getWidth();
            View fill = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{tempColor(x.min), tempColor(x.max)});
            g.setCornerRadius(dp(3));
            fill.setBackground(g);
            int left = w * (x.min - lo) / span;
            int right = w * (x.max - lo) / span;
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(Math.max(dp(6), right - left), dp(6));
            flp.leftMargin = left;
            track.addView(fill, flp);
            if (now != Integer.MIN_VALUE) {   // today: white dot at the current temperature
                View dot = new View(this);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(0xFFFFFFFF);
                dg.setStroke(dp(2), 0x66000000);
                dot.setBackground(dg);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(10), dp(10));
                dlp.leftMargin = Math.max(0, Math.min(w - dp(10), w * (now - lo) / span - dp(5)));
                dlp.topMargin = -dp(2);
                track.setClipChildren(false);
                track.addView(dot, dlp);
            }
        });

        TextView max = text(Weather.deg(x.max), 0xFFFFFFFF, 500, 15);
        row.addView(max, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Cold blue → green → yellow → orange, like the iPhone temperature bars. */
    private static int tempColor(int t) {
        if (t <= 0) return 0xFF5AC8FA;
        if (t <= 10) return 0xFF64D2C8;
        if (t <= 18) return 0xFFA8E063;
        if (t <= 25) return 0xFFFFD60A;
        return 0xFFFF9F0A;
    }

    private TextView text(String s, int color, int weight, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setShadowLayer(6, 0, 0, 0x33000000);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
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
