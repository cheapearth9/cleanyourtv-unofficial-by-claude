package tv.clean.phone.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lists what is installed on the TV and removes, re-adds or enables single apps (the "Apps" screen). */
public final class AppManager {
    private AppManager() {}

    public static final class App {
        public final String pkg;
        public String path = "";
        public boolean system;
        public boolean disabled;
        /** A system app that was uninstalled for this user: still on the TV's system partition, can be brought back. */
        public boolean removed;

        App(String pkg) {
            this.pkg = pkg;
        }

        /** A system app whose current APK is an update in /data: removing the update puts the built-in version back. */
        public boolean updatedSystem() {
            return system && !removed && path.startsWith("/data/");
        }

        public String kind() {
            if (removed) return "Removed system app";
            return (system ? (updatedSystem() ? "System app (updated)" : "System app") : "App") + (disabled ? " - disabled" : "");
        }
    }

    /** Everything on the TV, in one round trip. */
    public static List<App> list(TvShell sh) throws IOException {
        String out = sh.run("echo @@ALL; pm list packages -f --user 0; echo @@SYS; pm list packages -s --user 0; "
            + "echo @@DIS; pm list packages -d --user 0; echo @@UNI; pm list packages -u --user 0", 90000);
        return parse(out);
    }

    static List<App> parse(String out) {
        Map<String, App> all = new LinkedHashMap<>();
        Set<String> sys = new LinkedHashSet<>();
        Set<String> dis = new LinkedHashSet<>();
        Set<String> uni = new LinkedHashSet<>();
        String section = "";
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("@@")) {
                section = line;
                continue;
            }
            if (!line.startsWith("package:")) continue;
            String body = line.substring(8).trim();
            if (section.equals("@@ALL")) {
                int eq = body.lastIndexOf('=');
                String pkg = eq > 0 ? body.substring(eq + 1).trim() : body;
                if (!Guards.isValidName(pkg)) continue;
                App a = new App(pkg);
                if (eq > 0) a.path = body.substring(0, eq);
                all.put(pkg, a);
            } else {
                String pkg = body;
                int sp = pkg.indexOf(' ');
                if (sp > 0) pkg = pkg.substring(0, sp);
                if (!Guards.isValidName(pkg)) continue;
                if (section.equals("@@SYS")) sys.add(pkg);
                else if (section.equals("@@DIS")) dis.add(pkg);
                else if (section.equals("@@UNI")) uni.add(pkg);
            }
        }
        for (App a : all.values()) {
            a.system = sys.contains(a.pkg) || a.path.startsWith("/system/") || a.path.startsWith("/vendor/") || a.path.startsWith("/product/");
            a.disabled = dis.contains(a.pkg);
        }
        List<App> r = new ArrayList<>(all.values());
        for (String p : uni) {
            if (all.containsKey(p)) continue;
            App a = new App(p);
            a.system = true;
            a.removed = true;
            r.add(a);
        }
        Collections.sort(r, new Comparator<App>() {
            @Override
            public int compare(App x, App y) {
                return x.pkg.compareToIgnoreCase(y.pkg);
            }
        });
        return r;
    }

    public static final String PLAY_STORE = "com.android.vending";

    /** A caution to show before removing an app that is allowed but needs care, or null. */
    public static String warning(App a) {
        if (PLAY_STORE.equals(a.pkg)) {
            return "Google Play is the app store: without it the TV cannot install or update apps from the store, and apps that need Google "
                + "services may stop working. Use this to replace it with a newer Google Play APK (Install an APK, right after), "
                + "not to leave the TV without one.";
        }
        return null;
    }

    /** Why this app must not be removed from the list, or null when it may be. */
    public static String blockedReason(App a, Set<String> launchers, String homePackage) {
        if (!Guards.isValidName(a.pkg)) return "not a valid package name";
        if (a.pkg.equals(Guards.OUR_LAUNCHER)) return "this is the clean launcher: use Undo / restore to remove it";
        if (Guards.isCore(a.pkg)) return "core Android/TV software: removing it can stop the TV from starting";
        if (launchers.contains(a.pkg) || a.pkg.equals(homePackage)) return "a launcher: switch Home to another launcher first";
        if (a.system && !PLAY_STORE.equals(a.pkg) && Guards.neverSuggest(a.pkg)) return "the Play Store, accounts, keyboard, remote or similar: other apps depend on it";
        return null;
    }

    /** Removes the app. System apps are removed for this user only ({@code pm uninstall -k --user 0}) and can be brought back. Returns null on success, else the TV's message. */
    public static String uninstall(TvShell sh, App a) throws IOException {
        if (!Guards.isValidName(a.pkg)) return "not a valid package name";
        String cmd = a.system ? "pm uninstall -k --user 0 " + a.pkg : "pm uninstall " + a.pkg;
        String out = sh.run(cmd + " 2>&1", 120000);
        if (out.contains("Success")) return null;
        return explain(out);
    }

    /** Removes the update of a system app (back to the built-in version): plain {@code pm uninstall}. Null on success. */
    public static String removeUpdates(TvShell sh, App a) throws IOException {
        if (!Guards.isValidName(a.pkg)) return "not a valid package name";
        String out = sh.run("pm uninstall " + a.pkg + " 2>&1", 120000);
        return out.contains("Success") ? null : explain(out);
    }

    /**
     * Brings back a system app that was removed for this user. Android 7.1 on some boxes has no {@code pm install-existing};
     * then the app's own built-in APK (from its codePath) is installed over itself, which switches it on for the user again.
     */
    public static String reinstall(TvShell sh, App a) throws IOException {
        if (!Guards.isValidName(a.pkg)) return "not a valid package name";
        String out = sh.run("pm install-existing --user 0 " + a.pkg + " 2>&1", 60000);
        if (bad(out) && !out.contains("unknown command")) out = sh.run("pm install-existing " + a.pkg + " 2>&1", 60000);
        if (!bad(out)) return null;
        // fallback: the built-in APK
        String dump = Cleaner.filtered(sh, "dumpsys package " + a.pkg, "codePath=", 60000);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("codePath=(/[A-Za-z0-9_./-]+)").matcher(dump);
        if (!m.find()) return explain(out);
        String dir = m.group(1);
        if (!(dir.startsWith("/system/") || dir.startsWith("/vendor/") || dir.startsWith("/product/"))) return explain(out);
        String apk = dir.endsWith(".apk") ? dir : null;
        if (apk == null) {
            for (String l : sh.run("ls " + dir + " 2>/dev/null", 20000).split("\n")) {
                String t = l.trim();
                if (t.endsWith(".apk") && t.matches("[A-Za-z0-9_.-]+")) {
                    apk = dir + "/" + t;
                    break;
                }
            }
        }
        if (apk == null) return explain(out);
        String res = sh.run("pm install -r -d " + apk + " 2>&1", 180000);
        return res.contains("Success") ? null : explain(res);
    }

    public static String enable(TvShell sh, App a) throws IOException {
        if (!Guards.isValidName(a.pkg)) return "not a valid package name";
        String out = sh.run("pm enable --user 0 " + a.pkg + " 2>&1", 30000);
        return out.contains("enabled") && !out.contains("Error") ? null : explain(out);
    }

    private static boolean bad(String out) {
        return out.contains("Error") || out.contains("Exception") || out.contains("Unknown") || out.contains("Failure") || !out.contains("installed");
    }

    private static String explain(String out) {
        if (out.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER")) return "the TV's device policy forbids removing it";
        if (out.contains("DELETE_FAILED_INTERNAL_ERROR")) return "the TV could not remove it (internal error): it may be protected by the firmware";
        if (out.contains("DELETE_FAILED_USER_RESTRICTED")) return "the TV's user restrictions forbid removing it";
        String last = Cleaner.lastLine(out);
        return last.isEmpty() ? "no answer from the TV" : last;
    }
}
