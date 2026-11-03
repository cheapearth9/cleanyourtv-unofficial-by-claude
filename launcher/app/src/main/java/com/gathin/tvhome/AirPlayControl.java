package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Talks to the AirPlay receiver app (io.github.jqssun.airplay) from the launcher. The receiver has no "stop" command for
 * other apps, so this uses what it does offer:
 * <ul>
 * <li>its settings broadcast (protected by android.permission.DUMP, which the phone app grants to this launcher with
 *     {@code pm grant}) to switch its start-up settings;</li>
 * <li>a start-up broadcast it handles (QUICKBOOT_POWERON), which starts its server in the background;</li>
 * <li>to stop a running server: with "run in background" off, the receiver stops its own server when its screen is
 *     left, so the receiver is opened for a moment and the launcher is brought back.</li>
 * </ul>
 */
final class AirPlayControl {
    private AirPlayControl() {}

    static final String PKG = "io.github.jqssun.airplay";
    static final String KEY_BOOT = "boot_auto_start";
    static final String KEY_AUTO = "auto_start";
    static final String KEY_BACKGROUND = "run_in_background";

    private static final String PERMISSION = "android.permission.DUMP";
    private static final String ACTION_SET = PKG + ".SET_SETTING";
    private static final String ACTION_LIST = PKG + ".LIST_SETTINGS";
    private static final String SETTINGS_RECEIVER = PKG + ".SettingsReceiver";
    private static final String BOOT_RECEIVER = PKG + ".BootReceiver";
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    /** Result code handed to the callback when nobody answered (receiver missing, or this launcher lacks the permission). */
    private static final int NO_ANSWER = -100;
    /** How long the receiver stays in front when it is opened only to make it stop (it must reach the screen, then leave it). */
    private static final long STOP_FLASH_MS = 1200;

    private static final String UI_PREFS = "airplay_ui";
    private static final String UI_ON = "on";

    interface Done {
        void done(boolean ok, String data);
    }

    /** Whether this launcher may use the receiver's settings broadcast (the phone app grants it). */
    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The last state this launcher set or read; the receiver's own default is on. */
    static boolean remembered(Context c) {
        return c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(UI_ON, true);
    }

    static void remember(Context c, boolean on) {
        c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putBoolean(UI_ON, on).apply();
    }

    private static void ordered(Context c, Intent intent, Done done) {
        intent.setComponent(new ComponentName(PKG, SETTINGS_RECEIVER));
        BroadcastReceiver result = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                done.done(getResultCode() == Activity.RESULT_OK, getResultData());
            }
        };
        try {
            c.sendOrderedBroadcast(intent, null, result, new Handler(Looper.getMainLooper()), NO_ANSWER, null, null);
        } catch (RuntimeException e) {
            done.done(false, String.valueOf(e.getMessage()));
        }
    }

    static void set(Context c, String key, boolean value, Done done) {
        ordered(c, new Intent(ACTION_SET).putExtra("key", key).putExtra("value", value ? "true" : "false"), done);
    }

    /** Reads the receiver's settings: {@code done(true, json)} with {"key": {"value": ..., "default": ...}, ...}. */
    static void list(Context c, Done done) {
        ordered(c, new Intent(ACTION_LIST), done);
    }

    /** A boolean setting from a {@link #list} answer (the receiver's own default when it is missing). */
    static boolean flag(String json, String key, boolean fallback) {
        try {
            JSONObject entry = new JSONObject(json).optJSONObject(key);
            return entry == null ? fallback : entry.optBoolean("value", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The main switch: on when the receiver starts with the TV and by itself. */
    static void readEnabled(Context c, Done done) {
        list(c, (ok, json) -> done.done(ok, ok ? String.valueOf(flag(json, KEY_BOOT, true) && flag(json, KEY_AUTO, true)) : json));
    }

    private static void setAll(Context c, String[] keys, int i, boolean value, Done done) {
        if (i == keys.length) {
            done.done(true, null);
            return;
        }
        set(c, keys[i], value, (ok, data) -> {
            if (!ok) done.done(false, data);
            else setAll(c, keys, i + 1, value, done);
        });
    }

    /**
     * Turns the server on or off. On: its settings are switched on and it is started now. Off: they are switched off and a
     * running server is stopped. Without the permission only "on" can work (the receiver's defaults are on already).
     */
    static void apply(Activity a, boolean on, Done done) {
        String[] keys = {KEY_BOOT, KEY_AUTO, KEY_BACKGROUND};
        if (!allowed(a)) {
            if (!on) {
                done.done(false, null);
                return;
            }
            start(a);
            done.done(true, null);
            return;
        }
        setAll(a, keys, 0, on, (ok, data) -> {
            if (!ok) {
                done.done(false, data);
                return;
            }
            if (on) start(a);
            else stopRunning(a);
            done.done(true, null);
        });
    }

    private static void start(Context c) {
        try {
            c.sendBroadcast(new Intent(QUICKBOOT).setComponent(new ComponentName(PKG, BOOT_RECEIVER)));
        } catch (RuntimeException ignored) {
            // the receiver is gone; the caller already got its answer
        }
    }

    /** Opens the receiver for a moment and comes back: leaving its screen makes it stop its server (run in background is off). */
    private static void stopRunning(Activity a) {
        Intent open = openIntent(a);
        if (open == null) return;
        try {
            a.startActivity(open);
        } catch (RuntimeException e) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                a.startActivity(new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (RuntimeException ignored) {
                // the launcher is already gone; Home does the same
            }
        }, STOP_FLASH_MS);
    }

    /** The intent that opens the receiver's own screen, or null when it is not installed or is disabled. */
    static Intent openIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(PKG);
        if (i == null) i = pm.getLaunchIntentForPackage(PKG);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
