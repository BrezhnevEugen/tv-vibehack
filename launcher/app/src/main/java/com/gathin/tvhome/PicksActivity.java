package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * "В тренде": the week's trending films and series (Picks), one at a time over its backdrop photo.
 * ◀ ▶ moves between them, OK plays the trailer in YouTube, Back returns Home.
 */
public class PicksActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Picks.Pick> picks;
    private int index;
    private int shown = -1;
    private ImageView photo;
    private TextView caption, title, meta, overview, hint;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        picks = Picks.all(this);
        if (picks.isEmpty()) {
            Toast.makeText(this, "Подборка ещё загружается", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Picks.Pick now = Picks.current(this);
        for (int i = 0; i < picks.size(); i++) if (now != null && picks.get(i).id == now.id) index = i;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackgroundResource(R.drawable.backdrop_scrim);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(64);
        text.setPadding(pad, 0, pad, dp(56));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        root.addView(text, lp);
        caption = label(text, 13, 0xB3FFFFFF, 600);
        caption.setLetterSpacing(0.22f);
        title = label(text, 44, 0xFFFFFFFF, 300);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = label(text, 15, 0xCCFFFFFF, 500);
        overview = label(text, 16, 0xE6FFFFFF, 400);
        overview.setMaxLines(5);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        hint = label(text, 13, 0x99FFFFFF, 500);
        ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = dp(6);
        ((LinearLayout.LayoutParams) meta.getLayoutParams()).topMargin = dp(8);
        ((LinearLayout.LayoutParams) overview.getLayoutParams()).topMargin = dp(16);
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).topMargin = dp(22);
        TextView credit = new TextView(this);
        credit.setText("Данные: TMDB");
        credit.setTextColor(0x66FFFFFF);
        credit.setTextSize(11);
        root.addView(credit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
        ((FrameLayout.LayoutParams) credit.getLayoutParams()).setMargins(0, 0, dp(28), dp(20));
        setContentView(root);
        show();
    }

    private TextView label(LinearLayout parent, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setShadowLayer(8, 0, 0, 0x66000000);
        parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private void show() {
        Picks.Pick p = picks.get(index);
        caption.setText(String.format(Locale.ROOT, "В ТРЕНДЕ НА ЭТОЙ НЕДЕЛЕ · %d / %d", index + 1, picks.size()));
        title.setText(p.title);
        StringBuilder m = new StringBuilder(p.tv ? "Сериал" : "Фильм");
        if (!p.year.isEmpty()) m.append(" · ").append(p.year);
        if (p.rating > 0) m.append(String.format(Locale.ROOT, " · ★ %.1f", p.rating));
        meta.setText(m);
        overview.setText(p.overview.isEmpty() ? "Описания на русском пока нет." : p.overview);
        overview.setVisibility(View.VISIBLE);
        hint.setText("OK — трейлер на YouTube   ◀ ▶ — другие");
        final int want = index;
        if (shown != want) photo.animate().alpha(0.25f).setDuration(150).start();
        new Thread(() -> {
            Bitmap b = null;
            try {
                b = Net.image(Picks.IMAGES + "w1280" + p.backdrop, 1280);
            } catch (Exception ignored) {
            }
            final Bitmap img = b;
            main.post(() -> {
                if (isFinishing() || index != want) return;
                shown = want;
                if (img != null) photo.setImageBitmap(img);
                photo.animate().alpha(1f).setDuration(350).start();
            });
        }, "pick-photo").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                index = (index + 1) % picks.size();
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                index = (index - 1 + picks.size()) % picks.size();
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) playTrailer(picks.get(index));
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void playTrailer(Picks.Pick p) {
        hint.setText("Ищу трейлер…");
        new Thread(() -> {
            String key = Picks.trailer(this, p);
            main.post(() -> {
                if (isFinishing()) return;
                hint.setText("OK — трейлер на YouTube   ◀ ▶ — другие");
                if (key == null) {
                    Toast.makeText(this, "Трейлер не найден", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(new Intent(i).setPackage("com.google.android.youtube.tv"));
                } catch (Exception e) {
                    try {
                        startActivity(i);
                    } catch (Exception e2) {
                        Toast.makeText(this, "Не открывается YouTube", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "pick-trailer").start();
    }
}
