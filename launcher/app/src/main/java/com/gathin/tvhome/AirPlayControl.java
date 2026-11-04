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
 * Talks to the AirPlay receiver (tv.clean.airplay, the cleanyourtv fork of jqssun/android-airplay-server, see /airplay) from
 * the launcher. The fork has a control door for exactly this, so nothing ever has to open on screen: one broadcast starts the
 * server, one stops it, others read or change its settings, ask whether it runs, and fetch its log.
 *
 * The door is protected by the receiver's own permission {@code tv.clean.airplay.permission.CONTROL}, a development
 * permission that only {@code pm grant} can hand out; the phone app does that when it installs either app.
 */
final class AirPlayControl {
    private AirPlayControl() {}

    static final String PKG = "tv.clean.airplay";
    /** The receiver this one replaces (the original app, signed by someone else, so it can never be updated into the fork). */
    static final String LEGACY_PKG = "io.github.jqssun.airplay";
    static final String KEY_BOOT = "boot_auto_start";

    static final String PERMISSION = PKG + ".permission.CONTROL";
    private static final String ACTION_PREFIX = "io.github.jqssun.airplay.";
    private static final String SETTINGS_RECEIVER = "io.github.jqssun.airplay.SettingsReceiver";
    private static final String BOOT_RECEIVER = "io.github.jqssun.airplay.BootReceiver";
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    /** Result code handed to the callback when nobody answered (receiver missing, or this launcher lacks the permission). */
    private static final int NO_ANSWER = -100;

    private static final String UI_PREFS = "airplay_ui";
    private static final String UI_ON = "on";

    interface Done {
        void done(boolean ok, String data);
    }

    /** Whether this launcher may use the receiver's control door (the phone app grants it). */
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

    private static void command(Context c, Intent intent, Done done) {
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

    /** Sets one setting; {@code value} is its text form ("true", "7000", "auto"...). The receiver checks it. */
    static void set(Context c, String key, String value, Done done) {
        command(c, new Intent(ACTION_PREFIX + "SET_SETTING").putExtra("key", key).putExtra("value", value), done);
    }

    static void set(Context c, String key, boolean value, Done done) {
        set(c, key, value ? "true" : "false", done);
    }

    /** Reads the receiver's settings: {@code done(true, json)} with {"key": {"value": ..., "default": ...}, ...}. */
    static void list(Context c, Done done) {
        command(c, new Intent(ACTION_PREFIX + "LIST_SETTINGS"), done);
    }

    /** The receiver's log, newest lines last. */
    static void logs(Context c, Done done) {
        command(c, new Intent(ACTION_PREFIX + "LOGS"), done);
    }

    static void clearLogs(Context c, Done done) {
        command(c, new Intent(ACTION_PREFIX + "CLEAR_LOGS"), done);
    }

    /** A setting's text from a {@link #list} answer, or {@code fallback}. */
    static String value(String json, String key, String fallback) {
        try {
            JSONObject entry = new JSONObject(json).optJSONObject(key);
            return entry == null || !entry.has("value") ? fallback : entry.getString("value");
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A boolean setting from a {@link #list} answer (the receiver's own default when it is missing). */
    static boolean flag(String json, String key, boolean fallback) {
        return Boolean.parseBoolean(value(json, key, String.valueOf(fallback)));
    }

    /** The main switch's state: {@code done(true, "true"/"false")}, true while the server really runs. */
    static void readEnabled(Context c, Done done) {
        command(c, new Intent(ACTION_PREFIX + "STATUS"), (ok, json) -> {
            if (!ok) {
                done.done(false, json);
                return;
            }
            boolean running = false;
            try {
                running = new JSONObject(json).optBoolean("running", false);
            } catch (Exception ignored) {
                // an answer that is not JSON counts as "not running"
            }
            done.done(true, String.valueOf(running));
        });
    }

    /**
     * Turns the server on or off, for good: on = "start with the TV" on and the server started now; off = "start with the
     * TV" off and the server stopped now. No screen opens. Without the permission only "on" can work (the start-up broadcast,
     * which the receiver honours while its own "start at boot" setting is on, as it is by default).
     */
    static void apply(Context c, boolean on, Done done) {
        if (!allowed(c)) {
            if (!on) {
                done.done(false, null);
                return;
            }
            try {
                c.sendBroadcast(new Intent(QUICKBOOT).setComponent(new ComponentName(PKG, BOOT_RECEIVER)));
            } catch (RuntimeException ignored) {
                // the receiver is gone
            }
            done.done(true, null);
            return;
        }
        set(c, KEY_BOOT, on, (ok, data) -> {
            if (!ok) {
                done.done(false, data);
                return;
            }
            command(c, new Intent(ACTION_PREFIX + (on ? "START" : "STOP")), done);
        });
    }

    /**
     * A string of the receiver (its own English texts: the settings are labelled exactly as in the original app), or null.
     * Looked up by name in the receiver's resources, so a text the receiver renames or drops never breaks the launcher.
     */
    static String text(Context c, String name) {
        try {
            Context other = c.createPackageContext(PKG, 0);
            int id = other.getResources().getIdentifier(name, "string", PKG);
            return id == 0 ? null : other.getString(id);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** A string array of the receiver (see {@link #text}), or null. */
    static String[] array(Context c, String name) {
        try {
            Context other = c.createPackageContext(PKG, 0);
            int id = other.getResources().getIdentifier(name, "array", PKG);
            return id == 0 ? null : other.getResources().getStringArray(id);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
