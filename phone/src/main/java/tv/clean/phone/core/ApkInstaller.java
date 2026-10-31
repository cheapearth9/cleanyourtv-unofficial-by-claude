package tv.clean.phone.core;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;

/** Installs an APK that lives on the phone onto the TV: push to /data/local/tmp, pm install, clean up. */
public final class ApkInstaller {
    private ApkInstaller() {}

    static final String REMOTE = "/data/local/tmp/cyt-install.apk";

    /** A plain APK is a zip with AndroidManifest.xml at its root; bundles (.xapk, .apks, .apkm) are zips without it. */
    public static boolean looksLikeApk(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return z.getEntry("AndroidManifest.xml") != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static void install(TvShell sh, TvInfo tv, File apk, String displayName, final Log log) throws IOException, CleanException {
        if (!looksLikeApk(apk)) {
            throw new CleanException(displayName + " is not a plain APK. Bundles (.xapk, .apks, .apkm) and other files cannot be installed this way: "
                + "get the single .apk version of the app.");
        }
        log.log("Sending " + displayName + " (" + (apk.length() / 1024) + " KB) to the TV...");
        final int[] lastTenth = {-1};
        sh.pushFile(apk, REMOTE, 0644, new TvShell.Progress() {
            @Override
            public void onProgress(long sent, long total) {
                int tenth = total <= 0 ? 10 : (int) (sent * 10 / total);
                if (tenth != lastTenth[0]) {
                    lastTenth[0] = tenth;
                    log.log("  " + (tenth * 10) + "%");
                }
            }
        });
        log.log("Installing...");
        String out = sh.run("pm install -r -g " + REMOTE + " 2>&1", 300000);
        if (out.contains("Unknown option") || out.contains("Error: Unknown")) {
            out = sh.run("pm install -r " + REMOTE + " 2>&1", 300000);
        }
        try {
            sh.run("rm -f " + REMOTE + "; true", 20000);
        } catch (IOException ignored) {
            // the file in /data/local/tmp is harmless
        }
        if (!out.contains("Success")) throw new CleanException(explain(out, tv));
        log.log("Installed.");
    }

    static String explain(String out, TvInfo tv) {
        if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || out.contains("signatures do not match")) {
            return "The TV has a copy of this app signed by someone else. Uninstall it first (Apps list); its data will be lost.";
        }
        if (out.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) return "A newer version of this app is already installed.";
        if (out.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) return "This app has no build for this TV's processor (for example a 64-bit-only app on a 32-bit box).";
        if (out.contains("INSTALL_FAILED_OLDER_SDK")) return "This app needs a newer Android than this TV runs (API " + tv.sdk + ").";
        if (out.contains("INSTALL_FAILED_DUPLICATE_PERMISSION") || out.contains("INSTALL_FAILED_CONFLICTING_PROVIDER")) {
            return "Another app on the TV clashes with this one (" + Cleaner.lastLine(out) + ").";
        }
        if (out.contains("INSTALL_PARSE_FAILED")) return "The TV could not read this file as an app (" + Cleaner.lastLine(out) + ").";
        return Cleaner.explainInstallFailure(out, tv).replace(" The stock launcher was left untouched.", "");
    }
}
