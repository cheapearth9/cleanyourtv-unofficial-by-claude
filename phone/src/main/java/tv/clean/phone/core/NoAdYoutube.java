package tv.clean.phone.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Install a no ad YouTube": this phone downloads one of two ad-free YouTube apps and installs it on the TV.
 * <ul>
 * <li><b>SmartTube</b> (yuliskov/SmartTube): its latest stable release, the file for the TV's processor;</li>
 * <li><b>VacuumTube</b>: this project owner's Android TV build (cheapearth9/vacuumtube-forked-by-claude), a fixed address.</li>
 * </ul>
 * Nothing is bundled in this app, and the TV never needs internet access to GitHub. The app is an ordinary app afterwards.
 */
public final class NoAdYoutube {
    private NoAdYoutube() {}

    public enum Choice {
        SMARTTUBE("SmartTube", "org.smarttube.stable"),
        VACUUMTUBE("VacuumTube", "io.github.cheapearth9.vacuumtube");

        public final String label;
        public final String pkg;

        Choice(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    /** VacuumTube's APK: a rolling release of the owner's repository, always under this name. */
    public static final String VACUUMTUBE_URL =
        "https://github.com/cheapearth9/vacuumtube-forked-by-claude/releases/download/android-latest/VacuumTube-AndroidTV.apk";
    /** SmartTube's latest (stable) release, with the list of its APK files. */
    public static final String SMARTTUBE_API = "https://api.github.com/repos/yuliskov/SmartTube/releases/latest";
    /** A real APK is megabytes; anything smaller is an error page or a cut-off download. */
    static final long MIN_BYTES = 500_000;

    /** Gets the APK onto this phone. Real code downloads it; tests hand over a file. */
    public interface Fetch {
        File fetch(Choice choice, String abis, Log log) throws IOException;
    }

    public static boolean installed(TvShell sh, Choice c) throws IOException {
        return sh.run("pm list packages " + c.pkg + " 2>&1", 40000).contains("package:" + c.pkg);
    }

    /** The TV's processor types, best first, as one comma list ("arm64-v8a,armeabi-v7a,armeabi"). */
    static String abis(TvShell sh) throws IOException {
        String out = sh.run("getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi", 20000);
        StringBuilder b = new StringBuilder();
        for (String part : out.split("[,\\s]+")) {
            if (part.matches("[A-Za-z0-9_-]+")) b.append(b.length() > 0 ? "," : "").append(part);
        }
        return b.toString();
    }

    /**
     * Installs the chosen app, or updates it when it is already on the TV (the newest build is always downloaded and installed
     * over the old one, keeping its data). A copy signed with another key can not be updated in place: it is uninstalled and
     * the new one installed (the app's own data is lost; it only happens once). Returns a line for the summary.
     */
    public static String install(TvShell sh, TvInfo tv, Choice c, Fetch fetch, Log log) throws IOException, CleanException {
        boolean was = installed(sh, c);
        String abis = abis(sh);
        log.log((was ? "Updating " : "Downloading ") + c.label + "...");
        File apk;
        try {
            apk = fetch.fetch(c, abis, log);
        } catch (IOException e) {
            throw new CleanException("Could not download " + c.label + ": " + e.getMessage()
                + ". This phone needs internet access (not only the TV's Wi-Fi) for this step.");
        }
        try {
            try {
                ApkInstaller.install(sh, tv, apk, c.label, log);
            } catch (CleanException e) {
                if (!was || !e.getMessage().contains("signed by someone else")) throw e;
                log.log("The " + c.label + " on the TV was signed with a different key; replacing it (its data is reset)...");
                sh.run("pm uninstall " + c.pkg + " 2>&1", 120000);
                ApkInstaller.install(sh, tv, apk, c.label, log);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
        if (!installed(sh, c)) throw new CleanException("The TV said " + c.label + " was installed, but it is not on the TV.");
        return (was ? "Updated " : "Installed ") + c.label + ": find it in the app list on the TV.";
    }

    /** Which of SmartTube's release files fits this TV: the exact processor type, else the universal one; null if none. */
    static String pickSmartTube(String releaseJson, String abis) {
        List<String> urls = new ArrayList<>();
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(releaseJson);
        while (m.find()) urls.add(m.group(1));
        for (String abi : abis.split(",")) {
            String suffix;
            if (abi.startsWith("arm64")) suffix = "_arm64-v8a.apk";
            else if (abi.startsWith("armeabi")) suffix = "_armeabi-v7a.apk";
            else if (abi.startsWith("x86")) suffix = "_x86.apk";
            else continue;
            for (String u : urls) if (u.endsWith(suffix)) return u;
        }
        for (String u : urls) if (u.endsWith("_universal.apk")) return u;
        return null;
    }

    /** The real download: VacuumTube from its fixed address, SmartTube from its latest release. Checks the file is that app. */
    public static File download(Choice c, String abis, File dest, Log log) throws IOException {
        String address = VACUUMTUBE_URL;
        if (c == Choice.SMARTTUBE) {
            String json = text(SMARTTUBE_API);
            address = pickSmartTube(json, abis);
            if (address == null) throw new IOException("no APK for this TV was found in SmartTube's latest release");
            log.log("  " + address.substring(address.lastIndexOf('/') + 1));
        }
        return downloadTo(address, dest, c.pkg, log);
    }

    static String text(String address) throws IOException {
        HttpURLConnection c = open(address);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("the address answered " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && o.size() < 4_000_000) o.write(buf, 0, n);
                return o.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static File downloadTo(String address, File dest, String pkg, Log log) throws IOException {
        HttpURLConnection c = open(address);
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
        if (dest.length() < MIN_BYTES || !ApkInstaller.looksLikeApk(dest) || !AirPlay.hasPackage(dest, pkg)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new IOException("the downloaded file is not the expected app");
        }
        return dest;
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(address).toURL().openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "cleanyourtv-phone");
        c.setRequestProperty("Accept", "*/*");
        return c;
    }
}
