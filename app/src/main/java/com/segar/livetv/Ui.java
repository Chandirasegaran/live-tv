package com.segar.livetv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

/** Design-language starter kit (docs/design-language.md §11). All numbers are 1080p units. */
final class Ui {
    // Text and icons: white at set opacities (§3)
    static final int TEXT_PRIMARY = 0xFFFFFFFF;
    static final int TEXT_LIST = 0xE6FFFFFF;
    static final int TEXT_TILE = 0xE0FFFFFF;
    static final int TEXT_MENU = 0xD9FFFFFF;
    static final int TEXT_SECONDARY = 0xA8FFFFFF;

    // Surfaces
    static final int GLASS = 0x13FFFFFF;
    static final int GLASS_STROKE = 0x17FFFFFF;
    static final int GLASS_FOCUS = 0x38FFFFFF;
    static final int GLASS_ACTIVE = 0x24FFFFFF;
    static final int MENU_SURFACE = 0xFF231D1F;
    static final int PANEL_SURFACE = 0xF7161113;
    static final int SCRIM = 0x80000000;

    // Layout (§5)
    static final int MARGIN = 96;
    static final int GAP = 36;
    static final int RADIUS = 22;
    static final int PER_ROW = 6;
    static final int TILE_W = (1920 - 2 * MARGIN - (PER_ROW - 1) * GAP) / PER_ROW; // 258
    static final int TILE_H = TILE_W * 9 / 16;                                    // 145
    static final int ROW_STEP = 224;

    static float scale = 1f;
    static Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface semibold = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static void init(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        scale = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        // Xiaomi TVs ship MiLan Pro; use it when present (§4).
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        for (String name : new String[] {"MiLanProVF.ttf", "MiLanPro-Regular.ttf"}) {
            File f = new File("/system/fonts/" + name);
            if (f.exists()) {
                try {
                    Typeface base = Typeface.createFromFile(f);
                    regular = Typeface.create(base, 400, false);
                    semibold = Typeface.create(base, 600, false);
                } catch (RuntimeException ignored) {
                    // Keep Roboto.
                }
                break;
            }
        }
    }

    static int px(int v) {
        return Math.round(v * scale);
    }

    static TextView text(Context c, int size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    static FrameLayout.LayoutParams centered(int w, int h) {
        return new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
    }

    static GradientDrawable glass(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(radius));
        bg.setColor(GLASS);
        bg.setStroke(Math.max(1, px(2)), GLASS_STROKE);
        return bg;
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(radius));
        bg.setColor(color);
        return bg;
    }

    /** List selector that only shows while the list has focus (§1: focus is the only highlight). */
    static StateListDrawable listSelector() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, rounded(GLASS_FOCUS, RADIUS));
        d.addState(new int[0], new ColorDrawable(0));
        return d;
    }

    /** Standard focus behaviour (§8): scale 1.1, lift 26, glass 7.5% → 22%, 140 ms. */
    static void focus(View v, GradientDrawable bg, boolean has, int restColor) {
        if (bg != null) bg.setColor(has ? GLASS_FOCUS : restColor);
        v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                .translationZ(has ? px(26) : 0).setDuration(140).withLayer().start();
    }

    private static BitmapDrawable background;

    /**
     * Warm near-black background with amber and plum glows, set once on the window.
     * Painted in software at quarter resolution and scaled up: radial GradientDrawables
     * render as solid white on some GLES2 GPUs, and a smooth gradient loses nothing when upscaled.
     */
    static void background(Activity a) {
        if (background == null) {
            int w = 480, h = 270; // 1920×1080 / 4
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h,
                    new int[] {0xFF15100F, 0xFF0A0809, 0xFF050405}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            glow(c, p, 0x36FF8048, 0.08f * w, -0.12f * h, 1250 / 4f);
            glow(c, p, 0x2EAA3C8C, 1.0f * w, 1.1f * h, 1000 / 4f);
            background = new BitmapDrawable(a.getResources(), bmp);
            background.setFilterBitmap(true);
            background.setDither(true);
        }
        a.getWindow().setBackgroundDrawable(background);
    }

    private static void glow(Canvas c, Paint p, int color, float cx, float cy, float r) {
        p.setShader(new RadialGradient(cx, cy, r, color, color & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
    }
}
