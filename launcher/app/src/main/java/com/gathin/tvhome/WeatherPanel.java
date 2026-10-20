package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Fills the iPhone-Weather-style layout (res/layout/dream_clock.xml) with a forecast. Used by the screensaver and the Weather tile. */
final class WeatherPanel {

    private final Context ctx;
    private final View root;
    private final float density;

    WeatherPanel(Context ctx, View root) {
        this.ctx = ctx;
        this.root = root;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    void show(Weather.Data d) {
        show(d, false);
    }

    /** detailed: 10 days with precipitation amounts and a 24-hour precipitation chart instead of the hourly row. */
    void show(Weather.Data d, boolean detailed) {
        int[] sky = Weather.sky(d.code, d.day);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, sky);
        root.setBackground(bg);
        root.findViewById(R.id.dreamAmbient).setVisibility(View.GONE);

        ((TextView) root.findViewById(R.id.wxCity)).setText(d.city);
        ((TextView) root.findViewById(R.id.wxTemp)).setText(Weather.deg(d.temp));
        ((TextView) root.findViewById(R.id.wxCond)).setText(Weather.describe(d.code));
        ((TextView) root.findViewById(R.id.wxRange)).setText(
                "Макс.: " + Weather.deg(d.max) + ", мин.: " + Weather.deg(d.min));
        ((TextView) root.findViewById(R.id.wxExtra)).setText(
                "Ощущается как " + Weather.deg(d.feels) + "  ·  ветер " + d.wind + " м/с  ·  влажность " + d.humidity + "%");

        ((TextView) root.findViewById(R.id.wxHoursCaption)).setText(detailed ? "ОСАДКИ НА 24 ЧАСА" : "ПОЧАСОВОЙ ПРОГНОЗ");
        ((TextView) root.findViewById(R.id.wxDaysCaption)).setText(
                detailed ? "ПРОГНОЗ НА " + d.days.size() + " ДНЕЙ" : "ПРОГНОЗ НА 6 ДНЕЙ");
        LinearLayout hours = root.findViewById(R.id.wxHours);
        hours.removeAllViews();
        if (detailed) {
            precipChart(hours, d);
        } else for (Weather.Hour h : d.hours.subList(0, Math.min(7, d.hours.size()))) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(text(h.label, 0xFFFFFFFF, h.label.length() > 2 ? 600 : 500, 13));
            TextView ic = text(Weather.icon(h.code, h.day), 0xFFFFFFFF, 400, 20);
            ic.setPadding(0, dp(4), 0, dp(4));
            cell.addView(ic);
            cell.addView(text(Weather.deg(h.temp), 0xFFFFFFFF, 500, 15));
            hours.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout days = root.findViewById(R.id.wxDays);
        days.removeAllViews();
        java.util.List<Weather.Day> shown = detailed ? d.days : d.days.subList(0, Math.min(6, d.days.size()));
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Weather.Day x : shown) {
            lo = Math.min(lo, x.min);
            hi = Math.max(hi, x.max);
        }
        for (int i = 0; i < shown.size(); i++) {
            Weather.Day x = shown.get(i);
            View line = new View(ctx);
            line.setBackgroundColor(0x26FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            int m = detailed ? 2 : 5;
            lp.setMargins(0, dp(m), 0, dp(m));
            days.addView(line, lp);
            days.addView(dayRow(x, lo, hi, i == 0 ? d.temp : Integer.MIN_VALUE, detailed));
        }

        root.findViewById(R.id.wxNow).setVisibility(View.VISIBLE);
        root.findViewById(R.id.wxCards).setVisibility(View.VISIBLE);
    }

    /** "Пн  🌧️ 40%  10°  ━━━━  19°", with the bar spanning the week's range like on the iPhone. */
    private View dayRow(Weather.Day x, int lo, int hi, int now, boolean detailed) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int ts = detailed ? 14 : 15;
        TextView name = text(x.label, 0xFFFFFFFF, 500, ts);
        row.addView(name, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout icon = new LinearLayout(ctx);
        icon.setOrientation(LinearLayout.VERTICAL);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        icon.addView(text(Weather.icon(x.code, true), 0xFFFFFFFF, 400, detailed ? 14 : 17));
        if (!detailed && x.precip >= 30) icon.addView(text(x.precip + "%", 0xFF8CD3FF, 600, 10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(detailed ? 36 : 52), LinearLayout.LayoutParams.WRAP_CONTENT));
        if (detailed) {   // "💧 70% · 3,2 мм"
            String mm = x.precipMm >= 0.1 ? String.format(java.util.Locale.forLanguageTag("ru"), "%.1f мм", x.precipMm) : "—";
            TextView pr = text(x.precip + "%  ·  " + mm, x.precip >= 30 ? 0xFF8CD3FF : 0x80FFFFFF, 500, 13);
            row.addView(pr, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        TextView min = text(Weather.deg(x.min), 0x99FFFFFF, 500, ts);
        min.setGravity(Gravity.END);
        row.addView(min, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout track = new FrameLayout(ctx);
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
            View fill = new View(ctx);
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
                View dot = new View(ctx);
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

        TextView max = text(Weather.deg(x.max), 0xFFFFFFFF, 500, ts);
        row.addView(max, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 24 bars of hourly precipitation (height = mm, opacity = probability), labels every 3 hours. */
    private void precipChart(LinearLayout into, Weather.Data d) {
        double peak = 1.0;   // at least 1 mm full height, so drizzle stays small
        for (Weather.Hour h : d.hours) peak = Math.max(peak, h.precipMm);
        int barMax = dp(34);
        double total = 0;
        for (int i = 0; i < d.hours.size(); i++) {
            Weather.Hour h = d.hours.get(i);
            total += h.precipMm;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            TextView pct = text(h.precipProb >= 30 && i % 3 == 0 ? h.precipProb + "%" : " ", 0xFF8CD3FF, 600, 9);
            col.addView(pct);
            FrameLayout slot = new FrameLayout(ctx);
            View bar = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(2));
            int alpha = 0x40 + (int) (0xBF * Math.min(100, h.precipProb) / 100.0);
            g.setColor((alpha << 24) | 0x5AC8FA);
            bar.setBackground(g);
            int bh = h.precipMm < 0.05 ? dp(2) : Math.max(dp(3), (int) (barMax * h.precipMm / peak));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(9), bh, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            slot.addView(bar, blp);
            col.addView(slot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barMax));
            String lbl = i % 3 == 0 ? (i == 0 ? d.hours.get(1).label.equals("00") ? "23" : String.format("%02d", (Integer.parseInt(d.hours.get(1).label) + 23) % 24) : h.label) : " ";
            col.addView(text(lbl, 0xB3FFFFFF, 500, 10));
            into.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        TextView caption = root.findViewById(R.id.wxHoursCaption);
        caption.setText(total >= 0.1
                ? String.format(java.util.Locale.forLanguageTag("ru"), "ОСАДКИ НА 24 ЧАСА  ·  %.1f ММ", total)
                : "ОСАДКИ НА 24 ЧАСА  ·  БЕЗ ОСАДКОВ");
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
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(ctx.getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setShadowLayer(6, 0, 0, 0x33000000);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

}
