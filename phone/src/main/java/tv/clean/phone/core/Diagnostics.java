package tv.clean.phone.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A plain-text report for when the launcher will not start: device, Home, launcher state and its crash log. */
public final class Diagnostics {
    private Diagnostics() {}

    public static String collect(TvShell sh, TvInfo tv) throws IOException {
        StringBuilder r = new StringBuilder();
        r.append("== Device ==\n");
        r.append(tv.title()).append(" | ").append(tv.androidLabel()).append('\n');
        r.append("device=").append(tv.device).append(" product=").append(tv.product).append(" leanback=").append(tv.leanback).append('\n');
        r.append("fingerprint=").append(tv.fingerprint).append('\n');

        r.append("\n== Home ==\n");
        r.append("resolves to: ").append(Cleaner.homeComponent(sh, tv)).append('\n');
        try {
            String raw = "";
            for (String cmd : Cleaner.RESOLVER_COMMANDS) {
                String o = sh.run(cmd, 240000);
                ResolverTable t = ResolverTable.parse(o);
                r.append("[").append(cmd).append("] ").append(o.length()).append(" chars, filters parsed ").append(t.filters().size())
                 .append(", launchers ").append(t.launcherPackages()).append('\n');
                if (raw.isEmpty() || (t.filters().size() > ResolverTable.parse(raw).filters().size())) raw = o;
                if (!t.components("android.intent.action.MAIN", "android.intent.category.HOME", null).isEmpty()) { raw = o; break; }
            }
            r.append("first lines of the table:\n");
            int at = Math.max(0, raw.indexOf("Activity Resolver Table"));
            String[] head = raw.substring(at).split("\n");
            for (int i = 0; i < Math.min(14, head.length); i++) r.append("  ").append(head[i]).append('\n');
            ResolverTable rt = ResolverTable.parse(raw);
            r.append("resolver table found: ").append(raw.contains("Activity Resolver Table")).append(", filters parsed: ").append(rt.filters().size()).append('\n');
            r.append("launcher packages parsed: ").append(rt.launcherPackages()).append('\n');
            int homeLines = 0;
            String[] lines = raw.split("\n");
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("category.HOME")) {
                    homeLines++;
                    if (sample.size() < 12) sample.add(lines[Math.max(0, i - 3)].trim() + "  ..  " + lines[i].trim());
                }
            }
            r.append("raw lines mentioning category.HOME: ").append(homeLines).append('\n');
            for (String s : sample) r.append("  ").append(s).append('\n');
        } catch (IOException e) {
            r.append("could not read the resolver table: ").append(e.getMessage()).append('\n');
        }

        r.append("\n== Launcher package ==\n");
        String dump = sh.run("dumpsys package " + Guards.OUR_LAUNCHER, 60000);
        Pattern keep = Pattern.compile("versionName|versionCode|targetSdk|minSdk|codePath|installed|enabled=|flags=|Unable to find");
        int n = 0;
        for (String l : dump.split("\n")) {
            if (keep.matcher(l).find() && n++ < 14) r.append(l.trim()).append('\n');
        }

        r.append("\n== Start attempt and crash log ==\n");
        String out = sh.run("logcat -c 2>&1; am start -W -n " + Guards.OUR_LAUNCHER + "/.MainActivity 2>&1; sleep 5; logcat -d -t 4000 2>&1", 60000);
        List<String> kept = new ArrayList<>();
        String[] lines = out.split("\n");
        int tail = 0;
        for (String l : lines) {
            boolean head = l.contains("FATAL EXCEPTION") || l.contains("AndroidRuntime") || l.contains("Process: " + Guards.OUR_LAUNCHER)
                || l.contains("gathin") || l.contains("tvhome") || l.startsWith("Status:") || l.startsWith("Error") || l.contains("ANR in")
                || l.contains("Caused by") || l.contains("OutOfMemory") || l.contains("Force finishing");
            if (head) {
                kept.add(l.trim());
                tail = l.contains("FATAL EXCEPTION") ? 18 : 0;
            } else if (tail > 0) {
                kept.add(l.trim());
                tail--;
            }
        }
        int from = Math.max(0, kept.size() - 90);
        for (int i = from; i < kept.size(); i++) r.append(kept.get(i)).append('\n');
        if (kept.isEmpty()) r.append("(nothing from the launcher in the log; first lines of output:)\n").append(out.length() > 600 ? out.substring(0, 600) : out).append('\n');
        return r.toString();
    }
}
