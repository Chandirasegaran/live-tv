package com.segar.livetv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Outline icons stored as int arrays on a 24-unit grid written in tenths (0–240).
 * Ops: 0 moveTo x y · 1 lineTo x y · 2 close · 3 circle cx cy r · 4 arc cx cy r start sweep.
 */
final class LineIcon extends Drawable {
    static final int[] PLAY = {0, 60, 45, 1, 60, 195, 1, 190, 120, 2};
    static final int[] SEARCH = {3, 105, 105, 65, 0, 152, 152, 1, 205, 205};
    static final int[] GRID = {
            0, 40, 40, 1, 105, 40, 1, 105, 105, 1, 40, 105, 2,
            0, 135, 40, 1, 200, 40, 1, 200, 105, 1, 135, 105, 2,
            0, 40, 135, 1, 105, 135, 1, 105, 200, 1, 40, 200, 2,
            0, 135, 135, 1, 200, 135, 1, 200, 200, 1, 135, 200, 2};
    static final int[] LIST = {
            3, 42, 60, 6, 0, 85, 60, 1, 205, 60,
            3, 42, 120, 6, 0, 85, 120, 1, 205, 120,
            3, 42, 180, 6, 0, 85, 180, 1, 205, 180};
    static final int[] SETTINGS = {
            0, 40, 80, 1, 118, 80, 0, 162, 80, 1, 200, 80, 3, 140, 80, 22,
            0, 40, 160, 1, 68, 160, 0, 112, 160, 1, 200, 160, 3, 90, 160, 22};
    static final int[] STAR = {
            0, 120, 30, 1, 144, 93, 1, 210, 96, 1, 158, 137, 1, 176, 202,
            1, 120, 165, 1, 64, 202, 1, 82, 137, 1, 30, 96, 1, 96, 93, 2};

    private final int[] ops;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    LineIcon(int[] ops) {
        this(ops, 0xEBFFFFFF);
    }

    LineIcon(int[] ops, int color) {
        this.ops = ops;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = Math.min(b.width(), b.height()) / 240f;
        float ox = b.left + (b.width() - 240 * s) / 2f;
        float oy = b.top + (b.height() - 240 * s) / 2f;
        paint.setStrokeWidth(16 * s);
        path.reset();
        for (int i = 0; i < ops.length; ) {
            switch (ops[i++]) {
                case 0: path.moveTo(ox + ops[i++] * s, oy + ops[i++] * s); break;
                case 1: path.lineTo(ox + ops[i++] * s, oy + ops[i++] * s); break;
                case 2: path.close(); break;
                case 3: path.addCircle(ox + ops[i++] * s, oy + ops[i++] * s, ops[i++] * s, Path.Direction.CW); break;
                case 4: {
                    float cx = ox + ops[i++] * s, cy = oy + ops[i++] * s, r = ops[i++] * s;
                    path.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), ops[i++], ops[i++]);
                    break;
                }
                default: return;
            }
        }
    }

    @Override public void draw(Canvas c) { c.drawPath(path, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
