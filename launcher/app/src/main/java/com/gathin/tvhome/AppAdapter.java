package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Set for HDMI tiles: opens the input instead of an app. */
        final Intent intent;
        final String key;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.intent = null;
            this.key = component.flattenToShortString();
        }

        Entry(String label, String key, Drawable banner, Drawable icon, Intent intent) {
            this.label = label;
            this.component = null;
            this.pkg = "";
            this.banner = banner;
            this.icon = icon;
            this.intent = intent;
            this.key = key;
        }

        boolean isInput() {
            return intent != null;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        // HDMI tiles go right after the dock, like the input cards on the stock Google TV home.
        List<Entry> inputs = hdmiEntries(fresh);
        inputs.add(weatherEntry());
        fresh.addAll(Math.min(dockSize, fresh.size()), inputs);
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** "Погода" tile: a sky-coloured card with the current temperature, opening the full forecast. */
    private Entry weatherEntry() {
        Weather.Data d = Weather.last;
        int w = 360, h = 202;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int[] sky = d != null ? Weather.sky(d.code, d.day) : new int[]{0xFF2E6FD8, 0xFF6FA8F0};
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, sky[0], sky[1], android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(ctx.getResources().getFont(R.font.outfit));
        p.setTextSize(30);
        c.drawText(ctx.getString(R.string.weather_city), 24, 46, p);
        p.setTextSize(84);
        c.drawText(d != null ? Weather.deg(d.temp) : "—", 22, 140, p);
        p.setTextSize(24);
        p.setColor(0xD9FFFFFF);
        c.drawText(d != null ? Weather.describe(d.code) : "Погода", 24, 180, p);
        if (d != null) {
            p.setTextSize(64);
            p.setTextAlign(android.graphics.Paint.Align.RIGHT);
            c.drawText(Weather.icon(d.code, d.day), w - 22, 130, p);
        }
        Drawable banner = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
        Intent i = new Intent(ctx, WeatherActivity.class);
        return new Entry("Погода", "weather", banner, null, i);
    }

    private List<Entry> hdmiEntries(List<Entry> apps) {
        List<Entry> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>(tim.getTvInputList());
        Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx))));
        Drawable icon = ctx.getDrawable(R.drawable.ic_input);
        // A device found over HDMI-CEC (e.g. "Apple TV") is a child input of its port: show the port once,
        // named after the device.
        java.util.Map<String, String> deviceOnPort = new java.util.HashMap<>();
        for (TvInputInfo info : list) {
            if (info.getParentId() == null) continue;
            CharSequence l = info.loadLabel(ctx);
            if (l != null) deviceOnPort.put(info.getParentId(), l.toString().replaceFirst("(?i)^HDMI\\s*", "").trim());
        }
        for (TvInputInfo info : list) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            CharSequence l = info.loadLabel(ctx);
            String label = l == null ? info.getId() : l.toString();
            if (!label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
            String device = deviceOnPort.get(info.getId());
            // Only ports with something on them: a device seen over CEC, or a live signal.
            boolean live = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            if (device == null && !live) continue;
            Drawable art = null;
            if (device != null && !device.isEmpty()) {
                // A box whose name matches an installed app (Apple TV) borrows that app's banner.
                for (Entry a : apps) {
                    if (a.banner != null && a.label.equalsIgnoreCase(device)) art = a.banner;
                }
                label = device + " " + ctx.getString(R.string.hdmi_box);
            }
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    TvContract.buildChannelUriForPassthroughInput(info.getId()), "vnd.android.cursor.item/channel");
            out.add(new Entry(label, "input:" + info.getId(), art, icon, i));
        }
        return out;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.card.setBackgroundResource(e.isInput() && e.banner == null ? R.drawable.tile_hdmi_bg : R.drawable.tile_bg);
        if (e.isInput() && e.banner == null) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (e.isInput()) return false;
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        if (e.isInput()) {
            try {
                ctx.startActivity(new Intent(e.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex) {
                Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key.hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** The focused tile's artwork for the backdrop, or null. */
    Drawable artAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
