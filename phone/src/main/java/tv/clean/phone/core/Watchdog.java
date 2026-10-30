package tv.clean.phone.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A safety timer that lives on the TV. Before the old launcher is switched off, a small shell script is started on the
 * TV that counts down; if the phone has not called {@link #disarm} by then (Wi-Fi dropped, phone died, the new launcher
 * crashed after the check), the script switches the old launcher back on and presses Home. So a half-finished
 * hand-over can never leave a TV without a working Home screen. Same idea as upstream's rollback watchdog.
 */
public final class Watchdog {
    private Watchdog() {}

    public static final String DIR = "/data/local/tmp";
    private static final Pattern COMPONENT = Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");

    static String scriptPath(String dir) {
        return dir + "/cyt-watchdog.sh";
    }

    static String pidPath(String dir) {
        return dir + "/cyt-watchdog.pid";
    }

    static String confirmPath(String dir) {
        return dir + "/cyt-watchdog.ok";
    }

    /**
     * The script. {@code packages} are re-enabled on timeout; {@code homeComponent} (may be null) is made the Home
     * activity again on Android 8+, where that is possible. Every value is validated: nothing odd reaches a shell.
     */
    public static String script(String dir, List<String> packages, String homeComponent, int sdk, int seconds) {
        if (seconds < 2 || seconds > 900) throw new IllegalArgumentException("bad timeout " + seconds);
        if (packages.isEmpty()) throw new IllegalArgumentException("nothing to restore");
        for (String p : packages) if (!Guards.isValidName(p)) throw new IllegalArgumentException("bad package " + p);
        if (homeComponent != null && !COMPONENT.matcher(homeComponent).matches()) homeComponent = null;
        if (!dir.matches("^/[A-Za-z0-9_./-]+$")) throw new IllegalArgumentException("bad directory " + dir);

        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("# cleanyourtv phone: switches the old launcher back on unless the phone confirms in time\n");
        s.append("echo $$ > ").append(pidPath(dir)).append('\n');
        s.append("i=0\n");
        s.append("while [ $i -lt ").append(seconds).append(" ]; do\n");
        s.append("  if [ -f ").append(confirmPath(dir)).append(" ]; then\n");
        s.append("    rm -f ").append(confirmPath(dir)).append(' ').append(pidPath(dir)).append(' ').append(scriptPath(dir)).append('\n');
        s.append("    exit 0\n");
        s.append("  fi\n");
        s.append("  sleep 2\n");
        s.append("  i=$((i+2))\n");
        s.append("done\n");
        for (String p : packages) s.append("pm enable --user 0 ").append(p).append(" >/dev/null 2>&1\n");
        if (sdk >= 26 && homeComponent != null) {
            s.append("cmd package set-home-activity --user 0 ").append(homeComponent).append(" >/dev/null 2>&1\n");
        }
        s.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1\n");
        s.append("rm -f ").append(pidPath(dir)).append(' ').append(scriptPath(dir)).append('\n');
        return s.toString();
    }

    /** Starts the timer on the TV. Returns false when it could not be confirmed running (the caller goes on, with a warning). */
    public static boolean arm(TvShell sh, List<String> packages, String homeComponent, int sdk, int seconds, Log log) {
        try {
            Set<String> unique = new LinkedHashSet<>(packages);
            String body = script(DIR, new ArrayList<>(unique), homeComponent, sdk, seconds);
            sh.push(body.getBytes(StandardCharsets.UTF_8), scriptPath(DIR), 0755);
            String script = scriptPath(DIR);
            String out = sh.run("rm -f " + confirmPath(DIR) + " " + pidPath(DIR) + "; "
                + "if command -v setsid >/dev/null 2>&1; then setsid sh " + script + " </dev/null >/dev/null 2>&1 & "
                + "else ( trap '' HUP; sh " + script + " </dev/null >/dev/null 2>&1 & ); fi; "
                + "sleep 1; if [ -f " + pidPath(DIR) + " ] && kill -0 $(cat " + pidPath(DIR) + ") 2>/dev/null; then echo CYT_ARMED; fi", 30000);
            if (out.contains("CYT_ARMED")) {
                log.log("Safety timer started on the TV: if this phone loses contact, the old launcher comes back by itself in "
                    + (seconds / 60) + " min.");
                return true;
            }
            log.log("Could not start the safety timer on this TV; going on without it.");
        } catch (IOException | RuntimeException e) {
            log.log("Could not start the safety timer (" + e.getMessage() + "); going on without it.");
        }
        return false;
    }

    /** Tells the timer all is well and stops it. Never throws: a lost message only means the timer later does a harmless re-enable. */
    public static void disarm(TvShell sh, Log log) {
        try {
            sh.run("echo ok > " + confirmPath(DIR) + "; kill $(cat " + pidPath(DIR) + ") 2>/dev/null; "
                + "rm -f " + confirmPath(DIR) + " " + pidPath(DIR) + " " + scriptPath(DIR) + "; true", 20000);
        } catch (IOException e) {
            log.log("Could not stop the safety timer (" + e.getMessage() + "); it only re-enables the old launcher, which is harmless.");
        }
    }
}
