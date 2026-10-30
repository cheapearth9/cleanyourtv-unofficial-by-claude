package tv.clean.phone.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain-text report for when the launcher will not start: device, memory, launcher state, a watched start with
 * its crash log, and who answers Home. Every part runs on its own and is shown as soon as it is done, so one slow or
 * failed command (a 1 GB box can take a minute over a big dump) never hides the rest. The crash log comes first
 * among the TV-reading parts because it is the one that matters.
 */
public final class Diagnostics {
    private Diagnostics() {}

    private interface Part {
        void run(StringBuilder out) throws IOException;
    }

    public static String collect(final TvShell sh, final TvInfo tv, final Log progress) {
        final StringBuilder report = new StringBuilder();

        section(report, progress, "Device", new Part() {
            @Override
            public void run(StringBuilder r) {
                r.append(tv.title()).append(" | ").append(tv.androidLabel()).append('\n');
                r.append("device=").append(tv.device).append(" product=").append(tv.product).append(" leanback=").append(tv.leanback).append('\n');
                r.append("fingerprint=").append(tv.fingerprint).append('\n');
            }
        });

        section(report, progress, "Memory", new Part() {
            @Override
            public void run(StringBuilder r) throws IOException {
                r.append(sh.run("head -n 4 /proc/meminfo 2>&1", 20000).trim()).append('\n');
            }
        });

        section(report, progress, "Launcher package", new Part() {
            @Override
            public void run(StringBuilder r) throws IOException {
                String dump = Cleaner.filtered(sh, "dumpsys package " + Guards.OUR_LAUNCHER,
                    "versionName|versionCode|targetSdk|minSdk|codePath|enabled=|flags=|Unable to find", 60000);
                int n = 0;
                for (String l : dump.split("\n")) {
                    if (!l.trim().isEmpty() && n++ < 14) r.append(l.trim()).append('\n');
                }
                if (n == 0) r.append("(no package record: the launcher is not installed)\n");
            }
        });

        section(report, progress, "Start attempt and crash log", new Part() {
            @Override
            public void run(StringBuilder r) throws IOException {
                sh.run("am force-stop " + Guards.OUR_LAUNCHER + "; true", 20000);
                LaunchCheck c = LaunchCheck.run(sh, "am start -n " + Guards.OUR_LAUNCHER + "/.MainActivity", false);
                r.append("result: ").append(c.up ? "RUNNING and stayed on screen" : "FAILED: " + c.failureReason()).append('\n');
                r.append("on screen at the end: ").append(c.resumed).append('\n');
                int n = 0;
                for (String l : c.crashLines) {
                    if (n++ >= 60) break;
                    r.append(l).append('\n');
                }
                String mine = Cleaner.filtered(sh, "logcat -d -v brief '*:W'", "gathin|tvhome", 30000);
                List<String> tail = new ArrayList<>();
                for (String l : mine.split("\n")) if (!l.trim().isEmpty()) tail.add(l.trim());
                if (!tail.isEmpty()) {
                    r.append("-- log lines naming the launcher (last 30) --\n");
                    for (int i = Math.max(0, tail.size() - 30); i < tail.size(); i++) r.append(tail.get(i)).append('\n');
                }
            }
        });

        section(report, progress, "Home", new Part() {
            @Override
            public void run(StringBuilder r) throws IOException {
                r.append("Home process: ").append(Cleaner.homeComponent(sh, tv)).append('\n');
                ResolverTable rt = Cleaner.resolverTable(sh);
                r.append("launchers that answer Home: ").append(rt.launcherPackages()).append('\n');
                r.append("filters read: ").append(rt.filters().size()).append('\n');
            }
        });
        return report.toString();
    }

    private static void section(StringBuilder report, Log progress, String name, Part part) {
        StringBuilder mine = new StringBuilder();
        mine.append("== ").append(name).append(" ==\n");
        try {
            part.run(mine);
        } catch (IOException e) {
            mine.append("(this part failed: ").append(e.getMessage()).append(")\n");
        } catch (RuntimeException e) {
            mine.append("(this part failed: ").append(e).append(")\n");
        }
        mine.append('\n');
        report.append(mine);
        if (progress != null) progress.log(mine.toString().trim());
    }
}
