package tv.clean.phone.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Starts the launcher (or presses Home) and then WATCHES it for about ten seconds: is it on screen, and did it crash.
 * "am start -W" cannot tell: it reports success for an app that dies a moment later, which is exactly how a launcher
 * that crashed on an Android 7 box looked "started" in an earlier version.
 */
public final class LaunchCheck {
    private static final String OUR = Guards.OUR_LAUNCHER;
    private static final String Q = Pattern.quote(OUR);
    /** Lines that only a crash of OUR process produces (not "AndroidRuntime START", which every `am` call logs). */
    private static final Pattern OURS = Pattern.compile("Process: " + Q + "|>>> " + Q + " <<<|Force finishing activity " + Q + "|ANR in " + Q);
    private static final Pattern BRIEF_PREFIX = Pattern.compile("^[VDIWEF]/[^(]*\\(\\s*\\d+\\):\\s?");

    public boolean crashed;
    /** Our launcher is on screen and stayed there. */
    public boolean up;
    /** Android is asking which Home app to use (several launchers answer Home). */
    public boolean chooser;
    /** Package on screen at the end; null if the firmware's dump could not be read. */
    public String resumed;
    /** am's own error line, e.g. "Error: Activity class ... does not exist". */
    public String startError = "";
    public final List<String> crashLines = new ArrayList<>();
    /** The root cause line of a crash ("java.lang.NoClassDefFoundError: ..."), or "". */
    public String cause = "";

    /** One-line reason this check failed, with the crash cause when there is one. */
    public String failureReason() {
        if (crashed) return "it crashed" + (cause.isEmpty() ? "" : " (" + cause + ")");
        if (!startError.isEmpty()) return "Android refused to start it (" + startError + ")";
        if (resumed == null) return "it never showed up on screen";
        return resumed.equals(OUR) ? "it did not stay on screen" : resumed + " was on screen instead";
    }

    /**
     * @param startCommand a shell command that starts something (null: only watch what is happening)
     * @param acceptChooser the Android "which Home app?" chooser counts as an answer (used when pressing Home)
     */
    public static LaunchCheck run(TvShell sh, String startCommand, boolean acceptChooser) throws IOException {
        LaunchCheck r = new LaunchCheck();
        sh.run("logcat -c 2>&1; true", 15000);
        if (startCommand != null) {
            String o = sh.run(startCommand + " 2>&1", 45000);
            for (String l : o.split("\n")) {
                String t = l.trim();
                if (t.startsWith("Error") || t.contains("Exception")) {
                    r.startError = t;
                    return r;
                }
            }
        }
        int upRounds = 0;
        for (int round = 1; round <= 6; round++) {
            String logs = sh.run("sleep 3; logcat -d -v brief AndroidRuntime:E DEBUG:I ActivityManager:W '*:S' 2>&1 | tail -n 250; true", 45000);
            r.readCrash(logs);
            if (r.crashed) break;
            r.resumed = Cleaner.resumedPackage(sh);
            boolean ours = OUR.equals(r.resumed) || (r.resumed == null && processAlive(sh));
            if (ours) {
                if (++upRounds >= 3) {          // on screen for three looks in a row, about nine seconds
                    r.up = true;
                    break;
                }
            } else if (acceptChooser && "android".equals(r.resumed)) {
                r.chooser = true;
                break;
            } else {
                upRounds = 0;
            }
        }
        return r;
    }

    private static boolean processAlive(TvShell sh) throws IOException {
        // [c]om... so the grep's own command line does not match itself
        String out = Cleaner.filtered(sh, "ps", "[c]om\\.gathin\\.tvhome", 20000);
        return out.contains(OUR);
    }

    void readCrash(String logs) {
        String[] lines = logs.split("\n");
        int hit = -1;
        for (int i = 0; i < lines.length; i++) {
            if (OURS.matcher(lines[i]).find()) {
                hit = i;
                break;
            }
        }
        if (hit < 0) return;
        crashed = true;
        int from = hit;
        for (int k = hit; k >= Math.max(0, hit - 3); k--) {
            if (lines[k].contains("FATAL EXCEPTION")) {
                from = k;
                break;
            }
        }
        int to = Math.min(lines.length, from + 70);
        for (int k = from; k < to; k++) {
            String t = BRIEF_PREFIX.matcher(lines[k].trim()).replaceFirst("").trim();
            if (!t.isEmpty()) crashLines.add(t);
        }
        for (String l : crashLines) {
            if (l.startsWith("Caused by:")) cause = l.substring("Caused by:".length()).trim();
        }
        if (cause.isEmpty()) {
            for (int k = 0; k < crashLines.size(); k++) {
                if (crashLines.get(k).startsWith("Process:") && k + 1 < crashLines.size()) {
                    cause = crashLines.get(k + 1);
                    break;
                }
            }
        }
        if (cause.length() > 200) cause = cause.substring(0, 200);
    }
}
