package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * An iOS-style on/off switch: a pill with a round knob that slides right (on, blue) or left (off, gray). Drawn directly on a
 * canvas with three paints and no layout or bitmap, so it costs next to nothing; it animates only for the moment it changes.
 */
final class SwitchView extends View {
    interface OnChange {
        void onChange(boolean checked);
    }

    private static final int BLUE = 0xFF0A84FF;
    private static final int GRAY = 0xFF787880;
    private static final long SLIDE_MS = 160;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final boolean animate;
    private boolean checked;
    /** 0 = knob at the left (off) ... 1 = knob at the right (on). */
    private float position;
    private ValueAnimator slide;
    private OnChange listener;

    SwitchView(Context context, boolean animate) {
        super(context);
        this.animate = animate;
        density = context.getResources().getDisplayMetrics().density;
        knob.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setColor(0xFFFFFFFF);
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> toggle());
    }

    void setOnChange(OnChange listener) {
        this.listener = listener;
    }

    boolean isChecked() {
        return checked;
    }

    /** User action: flips the switch and tells the listener. */
    void toggle() {
        setChecked(!checked, true);
    }

    /** Sets the state (animated when the screen is on); {@code notify} says whether the listener hears of it. */
    void setChecked(boolean on, boolean notify) {
        if (on == checked) return;
        checked = on;
        if (slide != null) slide.cancel();
        float target = on ? 1f : 0f;
        if (animate && isAttachedToWindow()) {
            slide = ValueAnimator.ofFloat(position, target);
            slide.setDuration(SLIDE_MS);
            slide.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            slide.start();
        } else {
            position = target;
            invalidate();
        }
        if (notify && listener != null) listener.onChange(on);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(52 * density), Math.round(32 * density));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slide != null) slide.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = 2 * density;
        float radius = (h - 2 * inset) / 2f;
        track.setColor(blend(GRAY, BLUE, position));
        rect.set(inset, inset, w - inset, h - inset);
        canvas.drawRoundRect(rect, radius, radius, track);
        float knobRadius = radius - 3 * density;
        float left = inset + radius;
        float right = w - inset - radius;
        canvas.drawCircle(left + (right - left) * position, h / 2f, knobRadius, knob);
        if (isFocused()) {
            rect.set(1, 1, w - 1, h - 1);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, ring);
        }
        if (!isEnabled()) {
            // dim while an answer is awaited
            track.setColor(0x66000000);
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, radius, radius, track);
        }
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
