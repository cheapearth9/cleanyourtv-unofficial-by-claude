package tv.clean.phone.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The actions: debloat, launcher hand-over, restore. A port of upstream tools/disable.sh, launcher/build-and-install.sh,
 * tools/theme.sh and tools/restore.sh, with the same guards, rewritten to work on Android 7 (no `cmd package`
 * sub-commands, no sed -E, no content write) by reading dumpsys output on the phone instead of on the TV.
 */
public final class Cleaner {
    private Cleaner() {}

    public static final String RECORD_PATH = "/data/local/tmp/cleanyourtv-record.txt";
    private static final String OUR = Guards.OUR_LAUNCHER;
    private static final String HOME_ACTION = "android.intent.action.MAIN";
    private static final String HOME_CAT = "android.intent.category.HOME";
    private static final Pattern COMPONENT = Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");

    /** Where the phone keeps its own copy of a record. */
    public interface RecordSink {
        void save(Record record) throws IOException;
    }

    // ------------------------------------------------------------------ reading the TV

    public static ResolverTable resolverTable(TvShell sh) throws IOException {
        String out = sh.run("dumpsys package resolvers", 120000);
        if (!out.contains("Activity Resolver Table")) {
            out = sh.run("dumpsys package", 240000);
        }
        return ResolverTable.parse(out);
    }

    /** The component that answers Home now ("pkg/.Activity"), "android/..." for the chooser, or null if unknown. */
    public static String homeComponent(TvShell sh, TvInfo tv) throws IOException {
        if (tv.sdk >= 26) {
            String out = sh.run("cmd package resolve-activity --brief -a " + HOME_ACTION + " -c " + HOME_CAT, 20000);
            String last = lastLine(out);
            if (COMPONENT.matcher(last).matches()) return last;
        }
        String dump = sh.run("dumpsys activity", 60000);
        Matcher m = Pattern.compile("mHomeProcess:.*[: ]([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(dump);
        if (m.find()) return m.group(1) + "/" + m.group(2);
        Matcher m2 = Pattern.compile("mHomeProcess:.*[: ]([A-Za-z0-9_.]+)/").matcher(dump);
        if (m2.find() && Guards.isValidName(m2.group(1))) return m2.group(1) + "/";
        return null;
    }

    static String lastLine(String out) {
        String last = "";
        for (String l : out.split("\n")) {
            l = l.trim();
            if (!l.isEmpty()) last = l;
        }
        return last;
    }

    static String resumedPackage(TvShell sh) throws IOException {
        String dump = sh.run("dumpsys activity activities", 60000);
        Matcher m = Pattern.compile("(?:m|top)?ResumedActivity: ActivityRecord\\{[0-9a-f]+ u\\d+ ([A-Za-z0-9_.]+)/").matcher(dump);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ debloat

    public static final class DebloatResult {
        public final List<String> disabled = new ArrayList<>();
        public final List<String> stillEnabled = new ArrayList<>();
        public final List<String> kept = new ArrayList<>();
        public boolean verified;
    }

    /** Disables the packages (never uninstalls) after the safety checks. Records what it did first. */
    public static DebloatResult debloat(TvShell sh, TvInfo tv, List<String> selected, Log log, RecordSink sink)
            throws IOException, CleanException {
        DebloatResult res = new DebloatResult();
        Set<String> pkgs = new LinkedHashSet<>();
        for (String p : selected) {
            if (!Guards.isValidName(p)) {
                res.kept.add(p + " (not a package name)");
            } else if (Guards.isCore(p)) {
                res.kept.add(p + " (core Android/TV middleware: without it the TV may not boot)");
            } else if (p.equals(OUR)) {
                res.kept.add(p + " (the new launcher itself)");
            } else if (!tv.enabled.contains(p)) {
                // not on this TV, or already off: nothing to do and nothing to record
            } else {
                pkgs.add(p);
            }
        }
        if (pkgs.isEmpty()) {
            log.log("Nothing to disable.");
            res.verified = true;
            return res;
        }

        ResolverTable rt = null;
        try {
            log.log("Checking which apps answer Home, Settings and the TV inputs...");
            rt = resolverTable(sh);
        } catch (IOException e) {
            log.log("Could not read the TV's intent table (" + e.getMessage() + "); using the stock-launcher list instead.");
        }

        // Home guard: launchers are handled by the launcher step, never by the list.
        Set<String> launchers = rt != null ? rt.launcherPackages() : new LinkedHashSet<>(Guards.KNOWN_STOCK_LAUNCHERS);
        for (String l : launchers) {
            if (pkgs.remove(l)) res.kept.add(l + " (a launcher: swapped by the launcher step, not by the list)");
        }

        // The setup wizard owns the saved Wi-Fi on some sets: disabling it deletes the network.
        if (pkgs.contains(Guards.SETUPWRAITH)) {
            String wifi = "";
            try {
                wifi = sh.run("dumpsys wifi", 40000);
            } catch (IOException ignored) {
                // treated as unreadable below
            }
            Set<String> owners = new LinkedHashSet<>();
            Matcher m = Pattern.compile("cname=\\S*").matcher(wifi);
            while (m.find()) owners.add(m.group());
            boolean owned = false;
            for (String o : owners) if (o.contains("setupwraith")) owned = true;
            if (owners.isEmpty() || owned) {
                pkgs.remove(Guards.SETUPWRAITH);
                res.kept.add(Guards.SETUPWRAITH + (owners.isEmpty()
                    ? " (could not check which app owns the saved Wi-Fi)"
                    : " (the saved Wi-Fi network belongs to it; forget and re-join the network in TV settings first)"));
            }
        }

        // HDMI/AV/tuner driver packages: disabling one leaves "No Signal" until a full restore + cold boot.
        List<String> checkInputs = new ArrayList<>(pkgs);
        for (int i = 0; i < checkInputs.size(); i += 12) {
            List<String> chunk = checkInputs.subList(i, Math.min(i + 12, checkInputs.size()));
            StringBuilder cmd = new StringBuilder("for p in");
            for (String p : chunk) cmd.append(' ').append(p);
            cmd.append("; do echo \"@@PKG $p\"; dumpsys package $p 2>/dev/null; done");
            try {
                for (String[] pr : parseInputCheck(sh.run(cmd.toString(), 90000))) {
                    if (pr[1].equals("HW")) {
                        pkgs.remove(pr[0]);
                        res.kept.add(pr[0] + " (drives the HDMI/AV/tuner inputs: disabling it can leave them black)");
                    } else if (pr[1].equals("NOCHK")) {
                        pkgs.remove(pr[0]);
                        res.kept.add(pr[0] + " (this firmware's dump cannot be read for the input-driver check)");
                    }
                }
            } catch (IOException e) {
                log.log("Could not run the HDMI input check on this batch: " + e.getMessage());
            }
        }

        // Whatever answers channel links or Settings has to stay, or the launcher's input tiles / Settings do nothing.
        if (rt != null) {
            Set<String> inputApps = rt.packages("android.intent.action.VIEW", null, "vnd.android.cursor.item/channel");
            Set<String> settingsApps = rt.packages("android.settings.SETTINGS", null, null);
            if (settingsApps.isEmpty()) {
                log.log("Could not tell which apps open Settings and the inputs: after this, open an HDMI input and Settings before putting the remote down.");
            } else {
                for (String p : new ArrayList<>(pkgs)) {
                    if (inputApps.contains(p)) {
                        pkgs.remove(p);
                        res.kept.add(p + " (opens the TV's HDMI/AV inputs and channels)");
                    } else if (settingsApps.contains(p)) {
                        pkgs.remove(p);
                        res.kept.add(p + " (opens the TV's Settings)");
                    }
                }
            }
        }

        if (pkgs.isEmpty()) {
            log.log("Every selected package was protected by a safety check; nothing was changed.");
            res.verified = true;
            return res;
        }

        // The undo record goes to the TV and the phone before the first change.
        Record rec = readTvRecord(sh);
        if (rec == null) rec = new Record();
        if (rec.savedAt.isEmpty()) rec.savedAt = now();
        for (String p : pkgs) if (!rec.disabled.contains(p)) rec.disabled.add(p);
        saveRecord(sh, rec, sink);
        log.log("Saved an undo record (" + rec.disabled.size() + " packages).");

        List<String> todo = new ArrayList<>(pkgs);
        for (int i = 0; i < todo.size(); i += 10) {
            StringBuilder cmd = new StringBuilder();
            for (String p : todo.subList(i, Math.min(i + 10, todo.size()))) {
                cmd.append("pm disable-user --user 0 ").append(p).append(" 2>&1; ");
            }
            cmd.append("true");
            String out = sh.run(cmd.toString(), 90000);
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Error") || t.contains("Exception") || t.contains("Permission Denial")) log.log("  TV said: " + t);
            }
            log.log("Disabled " + Math.min(i + 10, todo.size()) + " of " + todo.size() + "...");
        }

        Set<String> enabledNow = TvInfo.listPackages(sh, true);
        if (enabledNow.isEmpty()) {
            log.log("Could not read the TV back to confirm; compare 'pm list packages -d' by hand if something looks off.");
        } else {
            res.verified = true;
            for (String p : todo) {
                if (enabledNow.contains(p)) res.stillEnabled.add(p);
                else res.disabled.add(p);
            }
        }
        if (!res.stillEnabled.isEmpty()) {
            log.log(res.stillEnabled.size() + " packages are still enabled: this firmware refused pm disable-user for them.");
        }
        return res;
    }

    /** Test hook: pairs of {package, "HW"|"NOCHK"|"OK"} from the batched dumpsys output. */
    static List<String[]> parseInputCheck(String out) {
        List<String[]> r = new ArrayList<>();
        String[] sections = out.split("@@PKG ");
        for (int i = 1; i < sections.length; i++) {
            String s = sections[i];
            int nl = s.indexOf('\n');
            String pkg = (nl < 0 ? s : s.substring(0, nl)).trim();
            String body = nl < 0 ? "" : s.substring(nl + 1);
            String status;
            if (body.contains("requested permissions:")) {
                status = blockHas(body, "requested permissions:", "TV_INPUT_HARDWARE") ? "HW" : "OK";
            } else if (body.contains("grantedPermissions:")) {
                status = blockHas(body, "grantedPermissions:", "TV_INPUT_HARDWARE") ? "HW" : "OK";
            } else if (body.contains("Service Resolver Table:")) {
                status = body.contains("android.media.tv.TvInputService") ? "HW" : "OK";
            } else if (body.contains("Unable to find")) {
                status = "OK";
            } else {
                status = "NOCHK";
            }
            r.add(new String[] {pkg, status});
        }
        return r;
    }

    private static boolean blockHas(String body, String start, String needle) {
        int at = body.indexOf(start);
        String rest = body.substring(at);
        Matcher end = Pattern.compile("(install|runtime) permissions:|User [0-9]+:").matcher(rest);
        String block = end.find() ? rest.substring(0, end.start()) : rest;
        return block.contains(needle);
    }

    // ------------------------------------------------------------------ records

    public static Record readTvRecord(TvShell sh) throws IOException {
        String text = sh.run("cat " + RECORD_PATH + " 2>/dev/null", 20000);
        return Record.parse(text);
    }

    static void saveRecord(TvShell sh, Record rec, RecordSink sink) throws IOException {
        byte[] bytes = rec.serialize().getBytes(StandardCharsets.UTF_8);
        IOException tvFailure = null;
        try {
            sh.push(bytes, RECORD_PATH, 0644);
        } catch (IOException e) {
            tvFailure = e;
        }
        if (sink != null) sink.save(rec);
        if (tvFailure != null && sink == null) throw tvFailure;
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------------ launcher

    public static final class LauncherOptions {
        public byte[] apk;
        /** Stock launcher to disable once the new one runs; null or empty leaves every launcher as it is. */
        public String stock;
        public boolean inputKeyService;
        public boolean homeKeyService;
        /** Recommended dock layout (upstream config.json) to apply, or null. */
        public byte[] config;
    }

    /** What the person is told about the launcher step. */
    public static final class LauncherResult {
        public boolean isHome;
        public String note = "";
    }

    public static LauncherResult installLauncher(TvShell sh, TvInfo tv, LauncherOptions o, Log log, RecordSink sink)
            throws IOException, CleanException {
        if (tv.sdk < 24) {
            throw new CleanException("This TV runs Android API " + tv.sdk + "; the launcher needs Android 7.0 (API 24) or newer. "
                + "The clean-up half still works on it.");
        }
        if (o.apk == null || o.apk.length < 1000) {
            throw new CleanException("The launcher APK is not bundled in this build of the app.");
        }
        String stock = o.stock == null ? "" : o.stock;
        if (!stock.isEmpty() && (!Guards.isValidName(stock) || Guards.isCore(stock))) {
            throw new CleanException("Refusing to disable " + stock + ".");
        }

        String homeBefore = homeComponent(sh, tv);
        log.log("Home is currently: " + (homeBefore == null ? "unknown" : homeBefore));

        log.log("Pushing the launcher (" + (o.apk.length / 1024) + " KB)...");
        sh.push(o.apk, "/data/local/tmp/tvhome.apk", 0644);
        String out = sh.run("pm install -r /data/local/tmp/tvhome.apk 2>&1; rm -f /data/local/tmp/tvhome.apk", 180000);
        if (!out.contains("Success")) {
            if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || out.contains("signatures do not match")) {
                log.log("A launcher signed with a different key is installed; replacing it...");
                sh.push(o.apk, "/data/local/tmp/tvhome.apk", 0644);
                out = sh.run("pm uninstall " + OUR + " 2>&1; pm install -r /data/local/tmp/tvhome.apk 2>&1; rm -f /data/local/tmp/tvhome.apk", 180000);
            }
            if (!out.contains("Success")) throw new CleanException(explainInstallFailure(out, tv));
        }
        log.log("Launcher installed.");

        // Keep key helpers that were on: a force stop takes them off the list.
        sh.run("s=$(settings get secure enabled_accessibility_services); a=$(settings get secure accessibility_enabled); "
            + "am force-stop " + OUR + "; case \"$s\" in *" + OUR + "*) settings put secure enabled_accessibility_services \"\"; "
            + "settings put secure enabled_accessibility_services \"$s\"; settings put secure accessibility_enabled \"$a\" ;; esac", 30000);

        boolean takeHome = !stock.isEmpty();
        String start = takeHome
            ? "am start -W -a " + HOME_ACTION + " -c " + HOME_CAT + " -n " + OUR + "/.MainActivity 2>&1"
            : "am start -W -n " + OUR + "/.MainActivity 2>&1";
        String started = sh.run(start, 60000);
        boolean errorLine = false;
        for (String l : started.split("\n")) if (l.trim().toLowerCase(Locale.ROOT).startsWith("error")) errorLine = true;
        boolean statusOk = started.contains("Status: ok");
        boolean statusBad = started.contains("Status:") && !statusOk;
        if (errorLine || statusBad || (!statusOk && !OUR.equals(resumedPackage(sh)))) {
            throw new CleanException("The new launcher did not start (" + lastLine(started) + "). "
                + (takeHome ? stock + " was left enabled." : "Nothing else was changed."));
        }
        log.log("Launcher started.");

        sh.run("pm grant " + OUR + " android.permission.ACCESS_FINE_LOCATION 2>/dev/null; "
            + "cmd package compile -m speed -f " + OUR + " 2>/dev/null; true", 180000);

        if (o.config != null) {
            pushConfig(sh, o.config);
            log.log("Applied the recommended dock layout.");
        }

        LauncherResult res = new LauncherResult();
        Record rec = readTvRecord(sh);
        if (rec == null) rec = new Record();
        if (rec.savedAt.isEmpty()) rec.savedAt = now();
        rec.launcherInstalled = true;

        if (takeHome) {
            if (rec.stock.isEmpty()) rec.stock = stock;
            if (tv.sdk >= 26 && rec.home.isEmpty() && homeBefore != null && homeBefore.startsWith(stock + "/")) rec.home = homeBefore;
            saveRecord(sh, rec, sink);
            log.log("Handing Home to the new launcher (disabling " + stock + ")...");
            sh.run("pm disable-user --user 0 " + stock + " 2>&1", 40000);
            if (tv.sdk >= 26) {
                sh.run("cmd package set-home-activity --user 0 " + OUR + "/.MainActivity 2>&1", 30000);
            }
            sh.run("am start -a " + HOME_ACTION + " -c " + HOME_CAT + " 2>&1", 30000);
            res.isHome = verifyHome(sh, tv, log, res);
            if (!res.isHome) {
                rollbackHome(sh, tv, stock, rec.home, log);
                throw new CleanException("Home did not move to the new launcher, so " + stock + " was switched back on. "
                    + "On Google TV this usually means the setup wizard (" + Guards.SETUPWRAITH + ") also answers Home: "
                    + "tick it in the clean-up list (re-join Wi-Fi on the TV first if it was protected) and run again.");
            }
        } else {
            saveRecord(sh, rec, sink);
            res.note = "The launcher is installed and running, but " + (homeBefore == null ? "the current" : homeBefore) + " still owns the Home button.";
        }

        if (o.inputKeyService) enableService(sh, "InputKeyService");
        if (o.homeKeyService) enableService(sh, "HomeKeyService");
        return res;
    }

    private static boolean verifyHome(TvShell sh, TvInfo tv, Log log, LauncherResult res) throws IOException {
        if (tv.sdk >= 26) {
            String home = homeComponent(sh, tv);
            log.log("Home now resolves to " + home);
            return home != null && home.startsWith(OUR + "/");
        }
        // Android 7: no resolve-activity. Ask what can answer Home now, and what is on screen.
        Set<String> launchers = resolverTable(sh).launcherPackages();
        String resumed = resumedPackage(sh);
        log.log("Launchers that answer Home: " + launchers + "; on screen: " + resumed);
        if (!launchers.contains(OUR)) return false;
        if (launchers.size() > 1) {
            Set<String> others = new LinkedHashSet<>(launchers);
            others.remove(OUR);
            res.note = "Another launcher also answers Home (" + others + "): if Android asks which to use on the first Home press, pick the new one and choose Always.";
        }
        return OUR.equals(resumed) || launchers.size() == 1;
    }

    private static void rollbackHome(TvShell sh, TvInfo tv, String stock, String homeComponent, Log log) {
        try {
            sh.run("pm enable --user 0 " + stock + " 2>&1", 30000);
            if (tv.sdk >= 26) {
                String comp = homeComponent != null && COMPONENT.matcher(homeComponent).matches() ? homeComponent
                    : "$(cmd package resolve-activity --brief -a " + HOME_ACTION + " -c " + HOME_CAT + " " + stock + " | tail -n 1)";
                sh.run("cmd package set-home-activity --user 0 " + comp + " 2>&1", 30000);
            }
            log.log(stock + " is enabled again.");
        } catch (IOException e) {
            log.log("Could not switch " + stock + " back on automatically: run  pm enable --user 0 " + stock + "  over ADB. (" + e.getMessage() + ")");
        }
    }

    static String explainInstallFailure(String out, TvInfo tv) {
        String line = lastLine(out);
        if (out.contains("INSTALL_FAILED_OLDER_SDK")) {
            return "The TV (API " + tv.sdk + ") is older than the launcher supports. Nothing was changed.";
        }
        if (out.contains("INSUFFICIENT_STORAGE") || out.contains("not enough space")) {
            return "The TV is out of storage space. Free some space (Settings > Storage) and try again.";
        }
        if (out.contains("USER_RESTRICTED") || out.contains("INSTALL_FAILED_VERIFICATION")) {
            return "The TV blocked the install (" + line + "). Turn off 'Verify apps over USB' / allow unknown sources in Developer options.";
        }
        return "pm install failed on the TV: " + line + ". The stock launcher was left untouched.";
    }

    private static void enableService(TvShell sh, String name) throws IOException {
        String svc = OUR + "/." + name;
        sh.run("cmd appops set " + OUR + " AUTO_START allow 2>/dev/null; cmd appops set " + OUR + " APP_AUTO_START allow 2>/dev/null; "
            + "appops write-settings 2>/dev/null; s=$(settings get secure enabled_accessibility_services); "
            + "case \"$s\" in *" + name + "*) settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; "
            + "settings put secure enabled_accessibility_services \"$s\" ;; null|'') settings put secure enabled_accessibility_services " + svc
            + " ;; *) settings put secure enabled_accessibility_services \"$s:" + svc + "\" ;; esac; settings put secure accessibility_enabled 1", 40000);
    }

    /** Puts the dock layout where the launcher reads it, then restarts the launcher (as upstream tools/theme.sh). */
    static void pushConfig(TvShell sh, byte[] json) throws IOException {
        String dir = "/sdcard/Android/data/" + OUR + "/files";
        String tmp = "/data/local/tmp/cyt-theme-config.json";
        sh.push(json, tmp, 0644);
        sh.run("mkdir -p " + dir + " 2>/dev/null; rm -f " + dir + "/config.json 2>/dev/null; "
            + "{ o=$(content write --uri content://" + OUR + ".theme/config.json < " + tmp + " 2>&1) && case \"$o\" in *'Unsupported operation'*) false ;; esac; } "
            + "|| cp " + tmp + " " + dir + "/config.json 2>/dev/null; rm -f " + tmp, 40000);
        sh.run("am force-stop " + OUR + "; am start -W -n " + OUR + "/.MainActivity 2>&1", 40000);
    }

    // ------------------------------------------------------------------ restore

    public static final class RestoreResult {
        public final List<String> enabled = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        public boolean launcherRemoved;
    }

    /** Puts a TV back: stock launcher on and Home again, every recorded package enabled, optionally the launcher removed. */
    public static RestoreResult restore(TvShell sh, TvInfo tv, Record rec, boolean removeLauncher, Log log)
            throws IOException, CleanException {
        RestoreResult res = new RestoreResult();
        if (!rec.stock.isEmpty()) {
            log.log("Enabling the stock launcher " + rec.stock + "...");
            sh.run("pm enable --user 0 " + rec.stock + " 2>&1", 30000);
            if (tv.sdk >= 26) {
                String comp = !rec.home.isEmpty() ? rec.home
                    : "$(cmd package resolve-activity --brief -a " + HOME_ACTION + " -c " + HOME_CAT + " " + rec.stock + " | tail -n 1)";
                sh.run("cmd package set-home-activity --user 0 " + comp + " 2>&1", 30000);
            }
        }
        List<String> pkgs = rec.disabled;
        for (int i = 0; i < pkgs.size(); i += 10) {
            StringBuilder cmd = new StringBuilder();
            for (String p : pkgs.subList(i, Math.min(i + 10, pkgs.size()))) {
                cmd.append("pm enable --user 0 ").append(p).append(" 2>&1; ");
            }
            cmd.append("true");
            sh.run(cmd.toString(), 90000);
            log.log("Re-enabled " + Math.min(i + 10, pkgs.size()) + " of " + pkgs.size() + "...");
        }
        if (removeLauncher && rec.launcherInstalled) {
            if (rec.stock.isEmpty() && homeIsOurs(sh, tv)) {
                log.log("Keeping the launcher: it owns Home and no stock launcher was recorded.");
            } else {
                String out = sh.run("pm uninstall " + OUR + " 2>&1", 60000);
                res.launcherRemoved = out.contains("Success");
                log.log(res.launcherRemoved ? "Launcher removed." : "Could not remove the launcher: " + lastLine(out));
            }
        }
        Set<String> enabledNow = TvInfo.listPackages(sh, true);
        for (String p : pkgs) {
            if (enabledNow.isEmpty() || enabledNow.contains(p)) res.enabled.add(p);
            else res.failed.add(p);
        }
        sh.run("rm -f " + RECORD_PATH, 15000);
        return res;
    }

    private static boolean homeIsOurs(TvShell sh, TvInfo tv) throws IOException {
        String h = homeComponent(sh, tv);
        return h != null && h.startsWith(OUR + "/");
    }

    /** Last resort when no record exists: enable these (the packages `pm list packages -d` names). */
    public static int enableAll(TvShell sh, List<String> pkgs, Log log) throws IOException {
        int n = 0;
        for (int i = 0; i < pkgs.size(); i += 10) {
            StringBuilder cmd = new StringBuilder();
            for (String p : pkgs.subList(i, Math.min(i + 10, pkgs.size()))) {
                if (!Guards.isValidName(p)) continue;
                cmd.append("pm enable --user 0 ").append(p).append(" 2>&1; ");
                n++;
            }
            cmd.append("true");
            sh.run(cmd.toString(), 90000);
            log.log("Enabled " + Math.min(i + 10, pkgs.size()) + " of " + pkgs.size() + "...");
        }
        return n;
    }
}
