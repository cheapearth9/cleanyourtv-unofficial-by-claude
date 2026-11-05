package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The on/off switch drawn inside the AirPlay pill (a text pill with this as its end icon): a rounded track that is blue with
 * the white knob on the right when on, gray with the knob on the left when off. One drawable, three paints, no layout.
 */
final class SwitchDrawable extends Drawable {
    private static final int BLUE = 0xFF0A84FF;
    private static final int GRAY = 0xFF787880;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int width;
    private final int height;
    private final boolean animate;
    private boolean checked;
    private float position;
    private ValueAnimator slide;
    private boolean dim;

    SwitchDrawable(float density, boolean animate) {
        width = Math.round(44 * density);
        height = Math.round(26 * density);
        this.animate = animate;
        knob.setColor(0xFFFFFFFF);
        setBounds(0, 0, width, height);
    }

    boolean isChecked() {
        return checked;
    }

    /** Dimmed while the receiver's answer is awaited. */
    void setBusy(boolean busy) {
        dim = busy;
        invalidateSelf();
    }

    void setChecked(boolean on) {
        if (on == checked) return;
        checked = on;
        if (slide != null) slide.cancel();
        float target = on ? 1f : 0f;
        if (animate && getCallback() != null) {
            slide = ValueAnimator.ofFloat(position, target);
            slide.setDuration(160);
            slide.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            slide.start();
        } else {
            position = target;
            invalidateSelf();
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        int from = GRAY;
        int to = BLUE;
        track.setColor(blend(from, to, position));
        track.setAlpha(dim ? 110 : 255);
        rect.set(b.left, b.top, b.left + w, b.top + h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track);
        float inset = h * 0.12f;
        float radius = h / 2f - inset;
        float left = b.left + inset + radius;
        float right = b.left + w - inset - radius;
        knob.setAlpha(dim ? 160 : 255);
        canvas.drawCircle(left + (right - left) * position, b.top + h / 2f, radius, knob);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
