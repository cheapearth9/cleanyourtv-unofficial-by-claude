package tv.clean.phone.core;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Replaces the TV's built-in WebView (com.android.webview, an old Chrome 52 on Android 7) with a newer one, such as
 * Google's, which the firmware would never choose by itself because it only accepts the package name com.android.webview.
 * So the phone renames the new WebView to that name, signs it, and writes it over the system file (the TV must allow
 * "adb root"). The original file is first saved on the TV (/data/local), so it can always be put back, automatically when
 * the TV does not come back with the new one, or by the "Put back the original WebView" button.
 */
public final class WebViewSwap {
    private WebViewSwap() {}

    public static final String TARGET_PKG = "com.android.webview";
    static final String BACKUP = "/data/local/cyt-webview-original.apk";
    static final String STAGE = "/data/local/tmp/cyt-webview.apk";
    private static final String LIBRARY_META = "com.android.webview.WebViewLibrary";

    /** Tests set this to 0 so the waits for the TV's restart cost nothing. */
    static long pauseMs = -1;

    private static void pause(long ms) throws InterruptedException {
        Thread.sleep(pauseMs >= 0 ? pauseMs : ms);
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    /** The system copy's path from "pm path" output (it may list an update in /data first). */
    static String systemPath(String pmPath) {
        for (String line : pmPath.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String p = line.substring("package:".length()).trim();
            if (p.startsWith("/system/") || p.startsWith("/vendor/") || p.startsWith("/oem/") || p.startsWith("/product/")) return p;
        }
        return null;
    }

    /** Free space in KB from "df" output of old (toolbox: "... 306M available") or new (toybox table) Androids; -1 if unknown. */
    static long freeKb(String df) {
        for (String line : df.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(" available");
            if (at > 0 && lower.contains("total")) {
                String before = line.substring(0, at).trim();
                int space = before.lastIndexOf(' ');
                return parseSize(space < 0 ? before : before.substring(space + 1));
            }
        }
        long found = -1;
        for (String line : df.split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t.length >= 4 && t[1].matches("\\d+") && t[3].matches("\\d+")) found = Long.parseLong(t[3]);
        }
        return found;
    }

    private static long parseSize(String s) {
        try {
            char unit = s.charAt(s.length() - 1);
            if (Character.isDigit(unit)) return Long.parseLong(s) / 1024;
            double n = Double.parseDouble(s.substring(0, s.length() - 1));
            switch (Character.toUpperCase(unit)) {
                case 'K': return (long) n;
                case 'M': return (long) (n * 1024);
                case 'G': return (long) (n * 1024 * 1024);
                default: return -1;
            }
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The CPU types the TV runs, from its comma separated abilist. */
    static Set<String> abis(String abilist, String abi, String abi2) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : (abilist + "," + abi + "," + abi2).split(",")) {
            s = s.trim();
            if (!s.isEmpty() && !s.equals("?")) out.add(s);
        }
        return out;
    }

    /** What the manifest of this file says; throws when it is not a WebView APK. */
    public static Axml.Info inspect(File apk) throws CleanException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry("AndroidManifest.xml");
            if (e == null) throw new CleanException("This file is not an APK (no AndroidManifest.xml). Bundles (.xapk, .apks, .apkm) do not work.");
            byte[] b = new byte[(int) e.getSize()];
            try (java.io.InputStream in = zip.getInputStream(e)) {
                int off = 0;
                while (off < b.length) {
                    int r = in.read(b, off, b.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            Axml.Info info = Axml.read(b);
            if (!info.strings.contains(LIBRARY_META)) {
                throw new CleanException("This is not a WebView APK (" + (info.packageName.isEmpty() ? "unknown app" : info.packageName)
                    + "). Use the WebView from apkmirror.com, for example 'Android System WebView' for Android 7.");
            }
            return info;
        } catch (java.util.zip.ZipException e) {
            throw new CleanException("This file is not a valid APK: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new CleanException("Could not read this file: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ on the TV

    private static long size(TvShell sh, String path) throws IOException {
        String out = sh.run("stat -c %s " + path + " 2>/dev/null || wc -c < " + path + " 2>/dev/null", 20000).trim();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.matches("\\d+")) return Long.parseLong(line);
        }
        return -1;
    }

    private static boolean exists(TvShell sh, String path) throws IOException {
        return sh.run("[ -f " + path + " ] && echo yes || echo no", 15000).contains("yes");
    }

    private static void remountSystem(TvShell sh, boolean writable) throws IOException {
        String mode = writable ? "rw" : "ro";
        if (writable) {
            try {
                sh.remountRw(); // what "adb remount" does; the plain mount command below fails on some boxes
            } catch (IOException ignored) {
                // the mount command is the fallback
            }
        }
        sh.run("mount -o remount," + mode + " /system 2>&1 || mount -o " + mode + ",remount /system 2>&1", 30000);
    }

    private static void requireWritable(TvShell sh, String dir) throws IOException, CleanException {
        remountSystem(sh, true);
        String t = sh.run("touch " + dir + "/.cyt-test 2>&1 && echo ok; rm -f " + dir + "/.cyt-test", 20000);
        if (!t.contains("ok")) {
            throw new CleanException("The TV will not let the system partition be written (" + t.trim() + "). Nothing was changed.");
        }
    }

    /** Puts {@code from} (a file on the TV) in place of the system file; the size is checked. */
    private static void place(TvShell sh, String from, String target, long expect, Log log) throws IOException, CleanException {
        String tmp = target + ".cyt-new";
        sh.run("rm -f " + tmp, 20000);
        String cp = sh.run("cp " + from + " " + tmp + " 2>&1", 300000);
        long got = size(sh, tmp);
        if (got != expect) {
            sh.run("rm -f " + tmp, 20000);
            log.log("Not enough room beside the old file (" + cp.trim() + "); writing over it instead.");
            sh.run("cp " + from + " " + target + " 2>&1", 300000);
            got = size(sh, target);
            if (got != expect) throw new CleanException("Writing the system file failed (got " + got + " bytes of " + expect + ").");
        } else {
            sh.run("chmod 644 " + tmp + "; chown 0:0 " + tmp + " 2>/dev/null; mv " + tmp + " " + target + " 2>&1", 60000);
            if (size(sh, target) != expect) throw new CleanException("Replacing the system file failed.");
        }
        sh.run("chmod 644 " + target + "; restorecon " + target + " 2>/dev/null; sync", 60000);
    }

    /** Reboots and waits until the TV answers again; false if it never went down or never came back. */
    private static boolean reboot(TvShell sh, Log log) {
        log.log("Restarting the TV...");
        try {
            sh.run("reboot", 15000);
        } catch (IOException ignored) {
            // the connection ends with the restart
        }
        long end = System.currentTimeMillis() + 6 * 60 * 1000L;
        boolean wentDown = false;
        while (System.currentTimeMillis() < end) {
            try {
                pause(wentDown ? 6000 : 2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                String up = sh.run("getprop sys.boot_completed", 10000).trim();
                if (wentDown && up.equals("1")) return true;
            } catch (IOException e) {
                wentDown = true;
            }
            if (!wentDown && System.currentTimeMillis() > end - 5 * 60 * 1000L) {
                // 60 s and still answering: the restart did not happen
                log.log("The TV did not restart by itself. Unplug it and plug it in again.");
                // keep waiting: the person may do that
                end = System.currentTimeMillis() + 5 * 60 * 1000L;
                wentDown = false;
                try {
                    pause(20000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean healthy(TvShell sh, long versionCode) throws IOException {
        String path = sh.run("pm path " + TARGET_PKG + " 2>&1", 30000);
        if (systemPath(path) == null) return false;
        if (versionCode < 0) return true;
        String dump = sh.run("dumpsys package " + TARGET_PKG + " 2>&1 | grep -m1 versionCode", 30000);
        return dump.contains("versionCode=" + versionCode);
    }

    private static void rootOrFail(TvShell sh) throws IOException, CleanException {
        if (!sh.ensureRoot()) {
            throw new CleanException("This needs root on the TV and this TV does not give it to ADB ('adb root' is refused). "
                + "Nothing was changed.");
        }
    }

    /**
     * Installs the WebView in {@code source} as the TV's system WebView.
     *
     * @param work a file on this phone to build the converted APK in (deleted afterwards)
     * @return a summary line
     */
    public static String swap(TvShell sh, TvInfo tv, File source, String displayName, File work, Log log) throws IOException, CleanException {
        Axml.Info info = inspect(source);
        log.log("Found " + displayName + ": " + info.packageName + ", version code " + info.versionCode + ".");
        rootOrFail(sh);

        String path = systemPath(sh.run("pm path " + TARGET_PKG + " 2>&1", 30000));
        if (path == null) throw new CleanException("The TV has no built-in " + TARGET_PKG + " to replace. Nothing was changed.");
        String dir = path.substring(0, path.lastIndexOf('/'));
        log.log("The TV's WebView is " + path + ".");

        long installed = -1;
        String dump = sh.run("dumpsys package " + TARGET_PKG + " 2>&1 | grep -m1 versionCode", 30000);
        int at = dump.indexOf("versionCode=");
        if (at >= 0) {
            String digits = dump.substring(at + 12).split("[^0-9]", 2)[0];
            if (!digits.isEmpty()) installed = Long.parseLong(digits);
        }
        if (installed >= 0 && info.versionCode >= 0 && info.versionCode < installed) {
            throw new CleanException("The TV's WebView (version code " + installed + ") is newer than this file (" + info.versionCode
                + "). Nothing was changed.");
        }

        Set<String> abis = abis(sh.run("getprop ro.product.cpu.abilist", 10000), sh.run("getprop ro.product.cpu.abi", 10000),
            sh.run("getprop ro.product.cpu.abi2", 10000));
        log.log("Converting it for this TV (" + abis + "), signing it... this takes a minute.");
        ApkRewriter.Result r = ApkRewriter.rewrite(source, work, TARGET_PKG, abis, log);
        long expect = r.bytes;
        log.log("Converted: " + (expect / 1024) + " KB, " + r.entries + " files, " + r.skippedAbiEntries + " files for other CPUs left out.");
        try {
            long freeData = Math.max(freeKb(sh.run("df /data 2>&1", 20000)), freeKb(sh.run("df -k /data 2>&1", 20000)));
            if (freeData >= 0 && freeData < (expect / 1024) * 2 + 20000) {
                throw new CleanException("The TV needs about " + ((expect / 1024) * 2 / 1024 + 20) + " MB free in its data storage for this (it has "
                    + (freeData / 1024) + " MB). Free some space first. Nothing was changed.");
            }

            requireWritable(sh, dir);
            // 1. the safety copy: never overwritten once it exists, because it holds the real original
            if (!exists(sh, BACKUP)) {
                long orig = size(sh, path);
                sh.run("cp " + path + " " + BACKUP + " 2>&1", 300000);
                if (orig <= 0 || size(sh, BACKUP) != orig) {
                    sh.run("rm -f " + BACKUP, 20000);
                    throw new CleanException("Could not save a copy of the original WebView, so nothing was changed.");
                }
                log.log("Saved the original WebView on the TV (" + BACKUP + ").");
            } else {
                log.log("The original WebView is already saved on the TV.");
            }

            log.log("Sending the new WebView to the TV (" + (expect / 1048576) + " MB)...");
            final int[] last = {-1};
            sh.pushFile(work, STAGE, 0644, (sent, total) -> {
                int tenth = total <= 0 ? 10 : (int) (sent * 10 / total);
                if (tenth != last[0]) {
                    last[0] = tenth;
                    log.log("  " + tenth * 10 + "%");
                }
            });
            if (size(sh, STAGE) != expect) throw new CleanException("The file did not arrive complete. Nothing was changed.");

            log.log("Writing it into the system...");
            boolean touched = true;
            try {
                sh.run("rm -rf " + dir + "/oat", 30000);
                if (sh.run("[ -d " + dir + "/lib ] && echo yes || echo no", 15000).contains("yes")) {
                    sh.run("rm -rf /data/local/cyt-webview-lib; cp -r " + dir + "/lib /data/local/cyt-webview-lib 2>&1; rm -rf " + dir + "/lib", 120000);
                }
                place(sh, STAGE, path, expect, log);
                sh.run("rm -f " + STAGE, 20000);
                remountSystem(sh, false);
            } catch (CleanException | IOException e) {
                log.log("That failed (" + e.getMessage() + "); putting the original back.");
                restoreFiles(sh, path, dir, log);
                throw e;
            }
            if (!touched) return "";

            if (!reboot(sh, log)) {
                throw new CleanException("The TV did not come back after the restart. If it shows the boot logo for a long time, "
                    + "unplug it for a minute and try again. If it never starts, the original WebView is saved in " + BACKUP
                    + " on the TV: connect with ADB from a computer and copy it back to " + path + ".");
            }
            log.log("The TV is back. Checking...");
            rootOrFail(sh);
            if (!healthy(sh, info.versionCode)) {
                log.log("The TV did not take the new WebView; putting the original back.");
                restoreFiles(sh, path, dir, log);
                reboot(sh, log);
                throw new CleanException("The TV would not accept the new WebView, so the original is back in place.");
            }
            return "The TV now runs the new WebView (version code " + info.versionCode + "). Apps pick it up the next time they open.";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            work.delete();
        }
    }

    private static void restoreFiles(TvShell sh, String path, String dir, Log log) {
        try {
            sh.ensureRoot();
            remountSystem(sh, true);
            long expect = size(sh, BACKUP);
            if (expect <= 0) {
                log.log("There is no saved original to put back.");
                return;
            }
            sh.run("rm -rf " + dir + "/oat", 30000);
            place(sh, BACKUP, path, expect, log);
            if (sh.run("[ -d /data/local/cyt-webview-lib ] && echo yes || echo no", 15000).contains("yes")) {
                sh.run("rm -rf " + dir + "/lib; cp -r /data/local/cyt-webview-lib " + dir + "/lib 2>&1; "
                    + "rm -rf /data/local/cyt-webview-lib", 120000);
            }
            remountSystem(sh, false);
        } catch (IOException | CleanException e) {
            log.log("Could not put the original back: " + e.getMessage());
        }
    }

    /** Puts the saved original WebView back and restarts the TV. */
    public static String restore(TvShell sh, Log log) throws IOException, CleanException {
        rootOrFail(sh);
        if (!exists(sh, BACKUP)) {
            throw new CleanException("There is no saved original WebView on this TV (it is saved the first time the new one is installed).");
        }
        String path = systemPath(sh.run("pm path " + TARGET_PKG + " 2>&1", 30000));
        if (path == null) path = "/system/app/webview/webview.apk";
        String dir = path.substring(0, path.lastIndexOf('/'));
        requireWritable(sh, dir);
        log.log("Putting the original WebView back...");
        long expect = size(sh, BACKUP);
        sh.run("rm -rf " + dir + "/oat", 30000);
        place(sh, BACKUP, path, expect, log);
        if (sh.run("[ -d /data/local/cyt-webview-lib ] && echo yes || echo no", 15000).contains("yes")) {
            sh.run("rm -rf " + dir + "/lib; cp -r /data/local/cyt-webview-lib " + dir + "/lib 2>&1; rm -rf /data/local/cyt-webview-lib", 120000);
        }
        remountSystem(sh, false);
        if (!reboot(sh, log)) {
            throw new CleanException("The original is back in place, but the TV did not answer after the restart. Unplug it for a minute.");
        }
        rootOrFail(sh);
        return healthy(sh, -1) ? "The original WebView is back." : "The original file is back, but the TV does not list its WebView yet. Restart it once more.";
    }
}
