package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The "AirPlay settings" panel: the two receiver settings people change most, as switches, and a row that opens the
 * receiver's own screen for everything else. Built from plain views when it opens and gone when it closes.
 */
final class AirPlaySettingsDialog {
    private AirPlaySettingsDialog() {}

    /** {@code onClose} runs when the panel closes (the main switch re-reads the settings then). */
    static void show(Activity a, Runnable onClose) {
        float density = a.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean allowed = AirPlayControl.allowed(a);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * density);
        panel.setPadding(pad, pad, pad, Math.round(12 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2181C22);
        bg.setCornerRadius(18 * density);
        panel.setBackground(bg);

        TextView title = new TextView(a);
        title.setText(R.string.airplay_settings);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(0, 0, 0, Math.round(12 * density));
        panel.addView(title);

        if (!allowed) {
            TextView note = new TextView(a);
            note.setText(R.string.airplay_needs_phone);
            note.setTextColor(0xFFB0B4BA);
            note.setTextSize(14);
            note.setPadding(0, 0, 0, Math.round(10 * density));
            panel.addView(note);
        } else {
            SwitchView boot = addSwitchRow(a, panel, density, R.string.airplay_boot, AirPlayControl.KEY_BOOT);
            SwitchView background = addSwitchRow(a, panel, density, R.string.airplay_background, AirPlayControl.KEY_BACKGROUND);
            AirPlayControl.list(a, (ok, json) -> {
                if (!ok) return;
                boot.setChecked(AirPlayControl.flag(json, AirPlayControl.KEY_BOOT, true), false);
                background.setChecked(AirPlayControl.flag(json, AirPlayControl.KEY_BACKGROUND, true), false);
            });
        }

        TextView open = row(a, density);
        open.setText(R.string.airplay_open_app);
        open.setOnClickListener(v -> {
            android.content.Intent i = AirPlayControl.openIntent(a);
            dialog.dismiss();
            if (i != null) {
                try {
                    a.startActivity(i);
                } catch (RuntimeException e) {
                    Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
        });
        panel.addView(open, rowParams(density));

        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> {
            if (onClose != null) onClose.run();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.round(460 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static SwitchView addSwitchRow(Activity a, LinearLayout panel, float density, int label, String key) {
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setFocusable(true);
        line.setClickable(true);
        line.setBackground(focusBackground(density));
        int h = Math.round(14 * density);
        line.setPadding(h, 0, h, 0);

        TextView text = new TextView(a);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(17);
        line.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchView sw = new SwitchView(a, true);
        sw.setFocusable(false);
        sw.setClickable(false);
        line.addView(sw);
        sw.setOnChange(on -> {
            line.setEnabled(false);
            AirPlayControl.set(a, key, on, (ok, data) -> {
                line.setEnabled(true);
                if (!ok) {
                    sw.setChecked(!on, false);
                    Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
        });
        line.setOnClickListener(v -> sw.toggle());
        panel.addView(line, rowParams(density));
        return sw;
    }

    private static TextView row(Activity a, float density) {
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBackground(density));
        int h = Math.round(14 * density);
        t.setPadding(h, 0, h, 0);
        return t;
    }

    private static LinearLayout.LayoutParams rowParams(float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(54 * density));
        lp.topMargin = Math.round(2 * density);
        return lp;
    }

    /** Transparent, with a soft light fill while the row has focus (the remote's selection). */
    private static StateListDrawable focusBackground(float density) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(12 * density);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }
}
