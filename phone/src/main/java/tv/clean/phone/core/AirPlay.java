package tv.clean.phone.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The AirPlay switch. On: this phone downloads the AirPlay receiver (this project's fork, in /airplay, of jqssun/android-airplay-server,
 * GPL-3.0; built by this repository's own workflow and published on its releases page, never bundled in this app), installs it
 * on the TV, lets the launcher control it, and turns on the launcher's "AirPlay" button (a global setting the launcher reads).
 * Off: the button is switched off again; the receiver app is left installed (it is an ordinary app and can be removed in
 * the Apps list).
 */
public final class AirPlay {
    private AirPlay() {}

    public static final String PACKAGE = "tv.clean.airplay";
    /** The original receiver this one is forked from: another signature, so it can not be updated into the fork, and it would
     *  fight the fork for the AirPlay port, so it is removed when the fork goes in. */
    public static final String LEGACY_PACKAGE = "io.github.jqssun.airplay";
    /** The permission the fork protects its control door with; the launcher is given it with "pm grant". */
    public static final String CONTROL_PERMISSION = PACKAGE + ".permission.CONTROL";
    /** The APK the build workflow in this repository publishes (a rolling release, replaced on every build of /airplay). */
    public static final String URL = "https://github.com/cheapearth9/cleanyourtv-unofficial-by-claude/releases/download/airplay-latest/airplay.apk";
    /** The global setting the launcher reads: 1 shows the AirPlay button. */
    public static final String SETTING = "cyt_airplay";
    /** A real APK is megabytes; anything smaller is an error page or a cut-off download. */
    static final long MIN_BYTES = 200_000;

    /** Gets the APK onto this phone. Real code downloads it; tests hand over a file. */
    public interface Fetch {
        File fetch(Log log) throws IOException;
    }

    public static boolean enabled(TvShell sh) throws IOException {
        return sh.run("settings get global " + SETTING + " 2>&1", 20000).trim().equals("1");
    }

    public static boolean installed(TvShell sh) throws IOException {
        return sh.run("pm list packages " + PACKAGE + " 2>&1", 40000).contains("package:" + PACKAGE);
    }

    /**
     * Turns the AirPlay button on or off. {@code restartLauncher}: restart our launcher afterwards so it shows the change
     * (not needed when the launcher is about to be installed or restarted anyway). Returns a line for the summary.
     */
    public static String apply(TvShell sh, TvInfo tv, boolean on, Fetch fetch, boolean restartLauncher, Log log)
            throws IOException, CleanException {
        if (!on) {
            sh.run("settings put global " + SETTING + " 0 2>&1", 20000);
            if (enabled(sh)) throw new CleanException("The TV did not accept the setting.");
            log.log("AirPlay button switched off.");
            if (restartLauncher) restartLauncher(sh);
            return "AirPlay button switched off (the receiver app stays installed).";
        }
        if (tv.sdk < 23) throw new CleanException("The AirPlay receiver needs Android 6 or newer; this TV runs API " + tv.sdk + ".");
        removeLegacy(sh, log);
        String line;
        if (installed(sh)) {
            log.log("The AirPlay receiver is already installed on the TV.");
            line = "AirPlay button switched on (the receiver was already installed).";
        } else {
            log.log("Downloading the AirPlay receiver (open source, GPL-3.0) from its GitHub releases page...");
            File apk;
            try {
                apk = fetch.fetch(log);
            } catch (IOException e) {
                throw new CleanException("Could not download the AirPlay receiver: " + e.getMessage()
                    + ". This phone needs internet access (not only the TV's Wi-Fi) for this step.");
            }
            try {
                ApkInstaller.install(sh, tv, apk, "AirPlay receiver", log);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
            if (!installed(sh)) throw new CleanException("The TV said the AirPlay receiver was installed, but it is not on the TV.");
            line = "Installed the AirPlay receiver and switched the AirPlay button on.";
        }
        sh.run("settings put global " + SETTING + " 1 2>&1", 20000);
        if (!enabled(sh)) throw new CleanException("The AirPlay receiver is installed, but the TV did not accept the launcher setting.");
        grantSwitchPermission(sh);
        if (restartLauncher) restartLauncher(sh);
        return line;
    }

    /**
     * The launcher's AirPlay on/off switch and settings go through the receiver's control door, which it protects with its own
     * development permission, so only "pm grant" can give it; without it the switch can still turn AirPlay on, not off.
     */
    private static void grantSwitchPermission(TvShell sh) throws IOException {
        String own = Guards.OUR_LAUNCHER;
        if (!sh.run("pm list packages " + own + " 2>&1", 40000).contains("package:" + own)) return;
        sh.run("pm grant " + own + " " + CONTROL_PERMISSION + " 2>/dev/null; true", 30000);
    }

    /** Removes the original (jqssun) receiver if it is there, so the two never fight for the AirPlay port. */
    private static void removeLegacy(TvShell sh, Log log) throws IOException {
        if (!sh.run("pm list packages " + LEGACY_PACKAGE + " 2>&1", 40000).contains("package:" + LEGACY_PACKAGE)) return;
        log.log("Removing the original AirPlay receiver (" + LEGACY_PACKAGE + "): this project's own build replaces it.");
        sh.run("pm uninstall " + LEGACY_PACKAGE + " 2>&1", 60000);
    }

    /** The launcher builds its top bar when it starts: restart it (only if ours is installed) so a changed button shows. */
    private static void restartLauncher(TvShell sh) throws IOException {
        String own = Guards.OUR_LAUNCHER;
        if (!sh.run("pm list packages " + own + " 2>&1", 40000).contains("package:" + own)) return;
        sh.run("am force-stop " + own + "; sleep 1; input keyevent 3; true", 30000);
    }

    /** Downloads {@link #URL} (or another https address, for tests) to {@code dest}. Checks that the result is the receiver's APK. */
    public static File download(String address, File dest, Log log) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(address).toURL().openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "cleanyourtv-phone");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("the download address answered " + code);
            long total = c.getContentLengthLong();
            long got = 0;
            int lastTenth = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int tenth = total <= 0 ? -1 : (int) (got * 10 / total);
                    if (tenth != lastTenth) {
                        lastTenth = tenth;
                        if (tenth >= 0) log.log("  downloaded " + (tenth * 10) + "%");
                    }
                }
            }
            if (total > 0 && got != total) throw new IOException("the download was cut off (" + got + " of " + total + " bytes)");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (dest.length() < MIN_BYTES || !ApkInstaller.looksLikeApk(dest) || !isReceiver(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new IOException("the downloaded file is not the AirPlay receiver APK");
        }
        return dest;
    }

    /** The manifest names the receiver's package (as UTF-8 or UTF-16 text in the binary manifest). */
    static boolean isReceiver(File apk) {
        return hasPackage(apk, PACKAGE);
    }

    /** The manifest names this package (as UTF-8 or UTF-16 text in the binary manifest). */
    static boolean hasPackage(File apk, String pkg) {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return false;
            byte[] m;
            try (InputStream in = z.getInputStream(e)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                m = o.toByteArray();
            }
            return indexOf(m, pkg.getBytes(StandardCharsets.UTF_8)) >= 0
                || indexOf(m, pkg.getBytes(StandardCharsets.UTF_16LE)) >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
