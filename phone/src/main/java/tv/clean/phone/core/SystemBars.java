package tv.clean.phone.core;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Hides the on-screen button bar (Home / Back / Recents) and the notification bar on boxes that show them, using the
 * system "immersive" policy: {@code settings put global policy_control immersive.full=*}. It is a normal Global
 * setting, so it survives restarts. What the setting held before is kept in the undo record and put back on request.
 * Android 11 and newer ignore this setting.
 */
public final class SystemBars {
    private SystemBars() {}

    /** immersive.full hides both bars everywhere; preconfirms skips the "Viewing full screen" prompt. */
    public static final String HIDE_VALUE = "immersive.full=*:immersive.preconfirms=*";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.=*:,\\-]{0,200}$");

    /** The settings value only ever contains these characters; anything else never reaches a shell. */
    public static boolean isSafeValue(String v) {
        return v != null && SAFE.matcher(v).matches();
    }

    /** policy_control now: "" when unset, null when it could not be read. */
    public static String current(TvShell sh) throws IOException {
        String out = Cleaner.lastLine(sh.run("settings get global policy_control 2>&1", 20000));
        if (out.contains("Exception") || out.contains("Error") || out.contains("not found")) return null;
        return out.equals("null") ? "" : out;
    }

    public static boolean isHidden(String current) {
        return HIDE_VALUE.equals(current);
    }

    /** Whether this Android honours the setting at all. */
    public static boolean supported(TvInfo tv) {
        return tv.sdk > 0 && tv.sdk < 30;
    }

    /**
     * Hides ({@code hide}) or shows the bars. Updates {@code rec.barsBefore}. Returns true when the TV was changed.
     * Showing only undoes what this app set: a value somebody else put there is left alone.
     */
    public static boolean apply(TvShell sh, TvInfo tv, boolean hide, Record rec, Log log) throws IOException, CleanException {
        if (!supported(tv)) {
            throw new CleanException("Android " + tv.release + " (API " + tv.sdk + ") ignores the setting that hides the bars.");
        }
        String cur = current(sh);
        if (cur == null) throw new CleanException("Could not read the TV's screen-bar setting.");
        if (hide) {
            if (isHidden(cur)) {
                log.log("The bars are already hidden.");
                return false;
            }
            if (!isSafeValue(cur)) {
                throw new CleanException("Something else manages the TV's screen bars (an unusual value is set); left alone.");
            }
            if (rec.barsBefore == null) rec.barsBefore = cur.isEmpty() ? "null" : cur;
            sh.run("settings put global policy_control '" + HIDE_VALUE + "' 2>&1", 20000);
            if (!isHidden(current(sh))) {
                rec.barsBefore = null;
                throw new CleanException("The TV did not accept the setting that hides the bars.");
            }
            log.log("Hid the on-screen buttons and the notification bar (also after a restart).");
            return true;
        }
        if (!isHidden(cur)) {
            if (rec.barsBefore != null) log.log("The bar setting was changed by something else since; left as it is.");
            rec.barsBefore = null;
            return false;
        }
        String before = rec.barsBefore;
        if (before == null || before.equals("null") || !isSafeValue(before)) {
            sh.run("settings delete global policy_control 2>&1", 20000);
            if (!"".equals(current(sh))) sh.run("settings put global policy_control null 2>&1", 20000);
        } else {
            sh.run("settings put global policy_control '" + before + "' 2>&1", 20000);
        }
        rec.barsBefore = null;
        log.log("Brought the on-screen buttons and the notification bar back.");
        return true;
    }
}
