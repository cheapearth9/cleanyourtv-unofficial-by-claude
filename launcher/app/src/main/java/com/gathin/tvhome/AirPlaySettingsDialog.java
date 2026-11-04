package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "AirPlay settings": every setting of the AirPlay receiver's own Settings screen (same sections, same order, same texts: the
 * labels are read from the receiver app itself), plus its log at the bottom. Nothing here knows how the receiver stores a
 * setting: it reads them with one broadcast, writes one at a time with another, and the receiver checks every value.
 * Built from plain views when it opens and released when it closes.
 */
final class AirPlaySettingsDialog {
    private static final int[] ADAPTIVE_PERCENTILES = {80, 85, 90, 95, 99};
    private static final String[] FPS_PRESETS = {"24", "30", "60", "120"};

    private final Activity a;
    private final float d;
    private final boolean animate;
    private final Dialog dialog;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final Map<String, String> values = new LinkedHashMap<>();
    private String focusKey;
    private boolean closed;

    private AirPlaySettingsDialog(Activity activity, boolean animate, Runnable onClose) {
        this.a = activity;
        this.animate = animate;
        d = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        panel.setPadding(pad, pad, pad, px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2181C22);
        bg.setCornerRadius(px(18));
        panel.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(R.string.airplay_settings);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(px(6), 0, 0, px(8));
        panel.addView(title);

        scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(panel);
        dialog.setOnDismissListener(x -> {
            closed = true;
            values.clear();
            list.removeAllViews();
            if (onClose != null) onClose.run();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics m = activity.getResources().getDisplayMetrics();
            window.setLayout(Math.min(px(560), Math.round(m.widthPixels * 0.94f)), Math.round(m.heightPixels * 0.9f));
        }
    }

    /** {@code onClose} runs when the panel closes (the main switch re-reads its state then). */
    static void show(Activity a, boolean animate, Runnable onClose) {
        AirPlaySettingsDialog s = new AirPlaySettingsDialog(a, animate, onClose);
        s.dialog.show();
        s.load();
    }

    // ---- data ---------------------------------------------------------------------------------------

    private void load() {
        if (!AirPlayControl.allowed(a)) {
            rebuild();
            return;
        }
        AirPlayControl.list(a, (ok, json) -> {
            if (closed) return;
            values.clear();
            if (ok) {
                try {
                    JSONObject all = new JSONObject(json);
                    for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                        String key = it.next();
                        values.put(key, all.getJSONObject(key).getString("value"));
                    }
                } catch (Exception ignored) {
                    values.clear();
                }
            }
            rebuild();
        });
    }

    private String v(String key, String fallback) {
        String x = values.get(key);
        return x == null ? fallback : x;
    }

    private boolean on(String key, boolean fallback) {
        return Boolean.parseBoolean(v(key, String.valueOf(fallback)));
    }

    /** Writes one setting, then reads everything again (a change can switch other rows on or off). */
    private void change(String key, String value) {
        focusKey = key;
        AirPlayControl.set(a, key, value, (ok, data) -> {
            if (closed) return;
            if (!ok) Toast.makeText(a, data == null || data.isEmpty() ? a.getString(R.string.not_available) : data, Toast.LENGTH_LONG).show();
            load();
        });
    }

    // ---- layout -------------------------------------------------------------------------------------

    private void rebuild() {
        int scrollY = scroll.getScrollY();
        list.removeAllViews();
        if (!AirPlayControl.allowed(a) || values.isEmpty()) {
            TextView note = new TextView(a);
            note.setText(AirPlayControl.allowed(a) ? R.string.not_available : R.string.airplay_needs_phone);
            note.setTextColor(0xFFB0B4BA);
            note.setTextSize(15);
            note.setPadding(px(8), px(4), px(8), px(12));
            list.addView(note);
        } else {
            addSettings();
        }
        // The log: last, at the bottom of the menu.
        addTextRow("logs", a.getString(R.string.airplay_logs), null, null, v -> showLogs());
        restoreFocus(scrollY);
    }

    private void restoreFocus(int scrollY) {
        View target = null;
        if (focusKey != null) target = list.findViewWithTag(focusKey);
        if (target == null && list.getChildCount() > 0) target = list.getChildAt(0).isFocusable() ? list.getChildAt(0) : null;
        final View focus = target;
        scroll.post(() -> {
            if (closed) return;
            scroll.scrollTo(0, scrollY);
            if (focus != null) focus.requestFocus();
        });
    }

    private void addSettings() {
        section("section_server");
        text("server_name", "setting_server_name", InputType.TYPE_CLASS_TEXT);
        text("server_port", "setting_server_port", InputType.TYPE_CLASS_NUMBER);
        sw("boot_auto_start", "setting_boot_auto_start", true);
        sw("run_in_background", "setting_run_in_background", true);

        section("section_connection");
        String usb = v("usb_only", "false");
        choice("usb_only", "setting_discovery", usb.equals("true") ? "setting_discovery_usb_desc" : "setting_discovery_all_desc",
                new String[]{"false", "true"}, new String[]{"chip_all_networks", "chip_usb_only"});
        sw("require_pin", "setting_require_pin", false);
        sw("allow_new_conn", "setting_allow_new_conn", true);
        sw("launch_on_connect", "setting_launch_on_connect", true);

        section("section_display");
        sw("auto_fullscreen", "setting_auto_fullscreen", true);
        resolution();
        maxFps();
        sw("overscanned", "setting_overscanned", false);

        section("section_decode");
        tri("h265", "setting_h265");
        sw("force_sw_alac", "setting_sw_alac", true);
        sw("force_sw_aac", "setting_sw_aac", false);
        sw("sync_client_volume", "setting_sync_client_volume", true);

        section("section_developer");
        sw("developer_options", "setting_developer_options", false);
        if (!on("developer_options", false)) return;
        sw("auto_start", "setting_auto_start", true);
        sw("keep_screen_on", "setting_keep_screen_on", true);
        sw("idle_preview", "setting_idle_preview", false);
        sw("advertise_video", "setting_advertise_video", true);
        sw("advertise_mirroring", "setting_advertise_mirroring", true);
        sw("advertise_audio", "setting_advertise_audio", true);
        sw("alac_enabled", "setting_alac", false);
        sw("aac_enabled", "setting_aac", true);
        sw("allow-frame-drop", "setting_key_allow_frame_drop", true);
        sw("enforce_sdr", "setting_enforce_sdr", true);
        sw("priority", "setting_realtime_decoder_priority", true);
        tri("operating-rate", "setting_operating_rate");
        sw("low_latency", "setting_low_latency", false);
        sw("scheduled_output_buffer_release", "setting_scheduled_output_buffer_release", false);

        int delay = parse(v("audio_latency_ms", "-1"), -1);
        addSwitchRow("audio_latency_ms", label("setting_audio_delay"), desc("setting_audio_delay_desc"), delay >= 0,
                checked -> change("audio_latency_ms", checked ? "250" : "-1"));
        if (delay >= 0) {
            stepper("audio_latency_ms.step", label("setting_audio_delay"), delay + " ms",
                    () -> change("audio_latency_ms", String.valueOf(Math.max(0, delay - 50))),
                    () -> change("audio_latency_ms", String.valueOf(Math.min(1000, delay + 50))));
        }

        boolean auto = on("audio_auto_buffer", true);
        sw("audio_auto_buffer", "setting_audio_auto_buffer", true);
        if (auto) {
            int step = Math.max(0, Math.min(ADAPTIVE_PERCENTILES.length - 1, parse(v("audio_adaptive_step", "3"), 3)));
            String[] names = AirPlayControl.array(a, "audio_adaptive_step_names");
            String shown = ADAPTIVE_PERCENTILES[step] + "%" + (names != null && step < names.length ? " (" + names[step] + ")" : "");
            stepper("audio_adaptive_step", label("setting_audio_auto_buffer"), shown,
                    () -> change("audio_adaptive_step", String.valueOf(Math.max(0, step - 1))),
                    () -> change("audio_adaptive_step", String.valueOf(Math.min(ADAPTIVE_PERCENTILES.length - 1, step + 1))));
        } else {
            text("audio_cushion_ms", "setting_audio_cushion_ms", InputType.TYPE_CLASS_NUMBER);
        }
        text("oboe_buffer_frames", "setting_oboe_buffer_frames", InputType.TYPE_CLASS_NUMBER);
        sw("debug_enabled", "setting_debug_overlay", false);
        sw("benchmark_log", "setting_benchmark_log", false);
    }

    // ---- rows ---------------------------------------------------------------------------------------

    private String label(String name) {
        String t = AirPlayControl.text(a, name);
        return t == null ? name : t;
    }

    private String desc(String name) {
        return AirPlayControl.text(a, name);
    }

    private void section(String name) {
        TextView t = new TextView(a);
        t.setText(label(name));
        t.setTextColor(0xFF0A84FF);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(px(8), px(14), px(8), px(4));
        list.addView(t);
    }

    private void sw(String key, String labelName, boolean def) {
        addSwitchRow(key, label(labelName), desc(labelName + "_desc"), on(key, def), checked -> {
            change(key, String.valueOf(checked));
            // Android 10+ shows a new window over other apps only with this permission: send the user to grant it.
            if (checked && key.equals("launch_on_connect") && Build.VERSION.SDK_INT >= 29) openOverlaySettings();
        });
    }

    private void openOverlaySettings() {
        try {
            a.startActivity(new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + AirPlayControl.PKG))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // no such settings page on this TV
        }
    }

    private void tri(String key, String labelName) {
        choice(key, labelName, labelName + "_desc", new String[]{"auto", "on", "off"}, new String[]{"chip_auto", "chip_on", "chip_off"});
    }

    private void resolution() {
        String cur = v("resolution", "auto");
        String[] presets = {"auto", "portrait", "landscape"};
        String[] names = {"chip_auto", "chip_device_portrait", "chip_device_landscape"};
        int index = -1;
        for (int i = 0; i < presets.length; i++) if (presets[i].equals(cur)) index = i;
        String shown = index >= 0 ? label(names[index]) : cur;
        int at = index >= 0 ? index : presets.length;   // a custom WxH size sits after the presets
        boolean preset = index >= 0;
        addChoiceRow("resolution", label("setting_resolution"), desc("setting_resolution_desc"), shown, step -> {
            int slots = presets.length + 1;
            int next = (((at + (step == 0 ? 1 : step)) % slots) + slots) % slots;
            if (next == presets.length) askText("resolution", label("setting_resolution"), preset ? "" : cur, InputType.TYPE_CLASS_TEXT);
            else change("resolution", presets[next]);
        });
    }

    private void maxFps() {
        String cur = v("max_fps", "60");
        int index = -1;
        for (int i = 0; i < FPS_PRESETS.length; i++) if (FPS_PRESETS[i].equals(cur)) index = i;
        int at = index >= 0 ? index : FPS_PRESETS.length;   // any other number sits after the presets
        addChoiceRow("max_fps", label("setting_max_fps"), desc("setting_max_fps_desc"), cur, step -> {
            int slots = FPS_PRESETS.length + 1;
            int next = (((at + (step == 0 ? 1 : step)) % slots) + slots) % slots;
            if (next == FPS_PRESETS.length) askText("max_fps", label("setting_max_fps"), cur, InputType.TYPE_CLASS_NUMBER);
            else change("max_fps", FPS_PRESETS[next]);
        });
    }

    /** A row that steps through fixed values with Left/Right (OK = next, wrapping around). */
    private void choice(String key, String labelName, String descName, String[] options, String[] names) {
        String cur = v(key, options[0]);
        int at = 0;
        for (int i = 0; i < options.length; i++) if (options[i].equals(cur)) at = i;
        int index = at;
        addChoiceRow(key, label(labelName), desc(descName), label(names[at]), step -> {
            int next = index + step;
            if (step == 0) next = index + 1;
            change(key, options[((next % options.length) + options.length) % options.length]);
        });
    }

    private void text(String key, String labelName, int inputType) {
        addTextRow(key, label(labelName), desc(labelName + "_desc"), v(key, ""), v -> askText(key, label(labelName), values.get(key), inputType));
    }

    private void askText(String key, String title, String current, int inputType) {
        EditText input = new EditText(a);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (x, which) -> change(key, input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface Check {
        void changed(boolean checked);
    }

    private interface Step {
        void step(int direction);
    }

    private LinearLayout baseRow(String key, String title, String description) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(key);
        row.setBackground(focusBackground());
        row.setPadding(px(10), px(8), px(10), px(8));
        row.setMinimumHeight(px(52));
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        column.addView(t);
        if (description != null && !description.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(description);
            s.setTextColor(0xFF9AA0A6);
            s.setTextSize(12);
            column.addView(s);
        }
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(2);
        list.addView(row, lp);
        row.setOnFocusChangeListener((x, focused) -> {
            if (focused) focusKey = key;
        });
        return row;
    }

    private void addSwitchRow(String key, String title, String description, boolean checked, Check listener) {
        LinearLayout row = baseRow(key, title, description);
        SwitchView sw = new SwitchView(a, animate);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setChecked(checked, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(52), px(32));
        lp.setMarginStart(px(12));
        row.addView(sw, lp);
        row.setOnClickListener(x -> {
            x.setEnabled(false);
            sw.setChecked(!sw.isChecked(), false);
            listener.changed(sw.isChecked());
        });
    }

    private void addChoiceRow(String key, String title, String description, String shown, Step stepper) {
        LinearLayout row = baseRow(key, title, description);
        TextView value = valueText("‹  " + shown + "  ›");
        row.addView(value);
        row.setOnClickListener(x -> stepper.step(0));
        row.setOnKeyListener(sideKeys(stepper));
    }

    private void addTextRow(String key, String title, String description, String shown, View.OnClickListener click) {
        LinearLayout row = baseRow(key, title, description);
        if (shown != null) row.addView(valueText(shown));
        row.setOnClickListener(click);
    }

    private void stepper(String key, String title, String shown, Runnable less, Runnable more) {
        LinearLayout row = baseRow(key, title, null);
        row.addView(valueText("‹  " + shown + "  ›"));
        row.setOnClickListener(x -> more.run());
        row.setOnKeyListener(sideKeys(direction -> {
            if (direction < 0) less.run();
            else more.run();
        }));
    }

    private View.OnKeyListener sideKeys(Step stepper) {
        return (x, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                stepper.step(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepper.step(1);
                return true;
            }
            return false;
        };
    }

    private TextView valueText(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFFE8EAED);
        t.setTextSize(15);
        t.setPadding(px(12), 0, 0, 0);
        return t;
    }

    private StateListDrawable focusBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(px(12));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private int px(int dp) {
        return Math.round(dp * d);
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- the log ------------------------------------------------------------------------------------

    private void showLogs() {
        Dialog logs = new Dialog(a);
        logs.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(20), px(16), px(20), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101318);
        bg.setCornerRadius(px(18));
        panel.setBackground(bg);

        TextView title = new TextView(a);
        title.setText(R.string.airplay_logs);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(px(4), 0, 0, px(8));
        panel.addView(title);

        ScrollView body = new ScrollView(a);
        body.setFocusable(true);
        body.setBackground(focusBackground());
        TextView text = new TextView(a);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextColor(0xFFD0D4D9);
        text.setPadding(px(8), px(6), px(8), px(6));
        text.setText(R.string.airplay_logs_empty);
        body.addView(text);
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView clear = button(R.string.airplay_logs_clear);
        TextView close = button(android.R.string.ok);
        buttons.addView(clear);
        buttons.addView(close);
        panel.addView(buttons);

        boolean[] gone = {false};
        clear.setOnClickListener(x -> AirPlayControl.clearLogs(a, (ok, data) -> {
            if (!gone[0]) text.setText(R.string.airplay_logs_empty);
        }));
        close.setOnClickListener(x -> logs.dismiss());
        logs.setOnDismissListener(x -> gone[0] = true);
        logs.setContentView(panel);
        Window window = logs.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics m = a.getResources().getDisplayMetrics();
            window.setLayout(Math.min(px(720), Math.round(m.widthPixels * 0.94f)), Math.round(m.heightPixels * 0.9f));
        }
        logs.show();
        close.requestFocus();
        AirPlayControl.logs(a, (ok, data) -> {
            if (gone[0] || !ok || data == null || data.isEmpty()) return;
            text.setText(data);
            body.post(() -> body.fullScroll(View.FOCUS_DOWN));
        });
    }

    private TextView button(int label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBackground());
        t.setPadding(px(18), px(10), px(18), px(10));
        return t;
    }
}
