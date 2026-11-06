package tv.clean.phone.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** The system WebView swap: manifest renaming, signing, the rewritten APK, and the TV-side steps against a pretend TV. */
public final class WebViewSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ---------------------------------------------------------------- a compiled manifest, built by hand

    private static void p16(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
    }

    private static void p32(ByteArrayOutputStream o, long v) {
        o.write((int) v);
        o.write((int) (v >> 8));
        o.write((int) (v >> 16));
        o.write((int) (v >> 24));
    }

    static byte[] axml(boolean utf8, String pkg, long versionCode, String... extra) {
        List<String> s = new ArrayList<>(Arrays.asList("manifest", "package", "versionCode", pkg));
        s.addAll(Arrays.asList(extra));
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offs = new int[s.size()];
        for (int i = 0; i < s.size(); i++) {
            offs[i] = strings.size();
            String t = s.get(i);
            if (utf8) {
                byte[] raw = t.getBytes(StandardCharsets.UTF_8);
                strings.write(t.length());
                strings.write(raw.length);
                strings.write(raw, 0, raw.length);
                strings.write(0);
            } else {
                p16(strings, t.length());
                byte[] raw = t.getBytes(StandardCharsets.UTF_16LE);
                strings.write(raw, 0, raw.length);
                p16(strings, 0);
            }
        }
        while (strings.size() % 4 != 0) strings.write(0);
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        int stringsStart = 28 + 4 * s.size();
        p16(pool, 1);
        p16(pool, 28);
        p32(pool, stringsStart + strings.size());
        p32(pool, s.size());
        p32(pool, 0);
        p32(pool, (utf8 ? 0x100 : 0) | 1);
        p32(pool, stringsStart);
        p32(pool, 0);
        for (int o : offs) p32(pool, o);
        pool.write(strings.toByteArray(), 0, strings.size());

        ByteArrayOutputStream el = new ByteArrayOutputStream();
        p16(el, 0x102);
        p16(el, 16);
        p32(el, 16 + 20 + 40);
        p32(el, 1);
        p32(el, -1);
        p32(el, -1);
        p32(el, 0);
        p16(el, 20);
        p16(el, 20);
        p16(el, 2);
        p16(el, 0);
        p16(el, 0);
        p16(el, 0);
        // package="..." (string)
        p32(el, -1);
        p32(el, 1);
        p32(el, 3);
        p16(el, 8);
        el.write(0);
        el.write(3);
        p32(el, 3);
        // versionCode (int)
        p32(el, -1);
        p32(el, 2);
        p32(el, -1);
        p16(el, 8);
        el.write(0);
        el.write(0x10);
        p32(el, versionCode);
        ByteArrayOutputStream end = new ByteArrayOutputStream();
        p16(end, 0x103);
        p16(end, 16);
        p32(end, 24);
        p32(end, 1);
        p32(end, -1);
        p32(end, -1);
        p32(end, 0);

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        p16(all, 3);
        p16(all, 8);
        p32(all, 8 + pool.size() + el.size() + end.size());
        all.write(pool.toByteArray(), 0, pool.size());
        all.write(el.toByteArray(), 0, el.size());
        all.write(end.toByteArray(), 0, end.size());
        return all.toByteArray();
    }

    static File apk(File dir, String name, byte[] manifest, boolean storedLibs) throws IOException {
        File f = new File(dir, name);
        Random rnd = new Random(7);
        try (ZipOutputStream z = new ZipOutputStream(new java.io.FileOutputStream(f))) {
            put(z, "AndroidManifest.xml", manifest, false);
            put(z, "classes.dex", random(rnd, 30000), false);
            put(z, "assets/stored.pak", random(rnd, 5000), true);
            put(z, "lib/armeabi-v7a/libwebviewchromium.so", random(rnd, 70000), storedLibs);
            put(z, "lib/arm64-v8a/libwebviewchromium.so", random(rnd, 80000), storedLibs);
            put(z, "META-INF/OLD.SF", "old signature".getBytes(StandardCharsets.UTF_8), false);
            put(z, "META-INF/OLD.RSA", "old signature".getBytes(StandardCharsets.UTF_8), false);
        }
        return f;
    }

    static byte[] random(Random r, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (r.nextInt(8) == 0 ? r.nextInt() : 'a');
        return b;
    }

    static void put(ZipOutputStream z, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            CRC32 c = new CRC32();
            c.update(data, 0, data.length);
            e.setCrc(c.getValue());
        }
        z.putNextEntry(e);
        z.write(data, 0, data.length);
        z.closeEntry();
    }

    static byte[] read(ZipFile z, String name) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (InputStream in = z.getInputStream(z.getEntry(name))) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        return o.toByteArray();
    }

    /** Where each entry's data starts in the file, read from the ZIP's own directory. */
    static Map<String, Long> dataOffsets(File f) throws IOException {
        Map<String, Long> out = new HashMap<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) r.length()];
            r.readFully(all);
            int eocd = all.length - 22;
            while (eocd > 0 && !(all[eocd] == 0x50 && all[eocd + 1] == 0x4b && all[eocd + 2] == 5 && all[eocd + 3] == 6)) eocd--;
            int count = (all[eocd + 10] & 0xff) | ((all[eocd + 11] & 0xff) << 8);
            int cd = le32(all, eocd + 16);
            for (int i = 0; i < count; i++) {
                int nameLen = (all[cd + 28] & 0xff) | ((all[cd + 29] & 0xff) << 8);
                int extraLen = (all[cd + 30] & 0xff) | ((all[cd + 31] & 0xff) << 8);
                int comment = (all[cd + 32] & 0xff) | ((all[cd + 33] & 0xff) << 8);
                int local = le32(all, cd + 42);
                String name = new String(all, cd + 46, nameLen, StandardCharsets.UTF_8);
                int ln = (all[local + 26] & 0xff) | ((all[local + 27] & 0xff) << 8);
                int le = (all[local + 28] & 0xff) | ((all[local + 29] & 0xff) << 8);
                out.put(name, (long) local + 30 + ln + le);
                cd += 46 + nameLen + extraLen + comment;
            }
        }
        return out;
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    // ---------------------------------------------------------------- a pretend TV for the swap

    /** Understands just the commands WebViewSwap sends. Files are only sizes. */
    static final class WebTv implements TvShell {
        final Map<String, Long> files = new HashMap<>();
        final Set<String> dirs = new HashSet<>();
        final String sys = "/system/app/webview/webview.apk";
        long origSize = 1000;
        long installedVersion = 275610000L;
        long newVersion;
        long newSize;
        boolean acceptNew = true;
        boolean rootOk = true;
        boolean systemWritable = true;
        boolean rebootHappened;
        int downPolls;
        long systemFreeKb = 900000;
        final List<String> history = new ArrayList<>();

        WebTv() {
            files.put(sys, origSize);
            dirs.add("/system/app/webview/oat");
        }

        boolean isNew() {
            return files.get(sys) != null && files.get(sys) == newSize;
        }

        @Override
        public boolean ensureRoot() {
            return rootOk;
        }

        @Override
        public void push(byte[] data, String path, int mode) {
            files.put(path, (long) data.length);
        }

        @Override
        public void pushFile(File local, String path, int mode, Progress p) {
            files.put(path, local.length());
            newSize = local.length();
            if (p != null) p.onProgress(local.length(), local.length());
        }

        @Override
        public String run(String command, int timeoutMs) throws IOException {
            history.add(command);
            if (downPolls > 0) {
                downPolls--;
                throw new IOException("connection refused");
            }
            if (command.equals("reboot")) {
                rebootHappened = true;
                downPolls = 2;
                if (isNew()) installedVersion = newVersion;
                else installedVersion = 275610000L;
                throw new IOException("closed");
            }
            StringBuilder out = new StringBuilder();
            if (command.startsWith("pm path")) {
                if (isNew() && !acceptNew && rebootHappened) return "";
                return "package:" + sys + "\n";
            }
            if (command.startsWith("dumpsys package")) return "    versionCode=" + installedVersion + " minSdk=24\n";
            if (command.startsWith("getprop ro.product.cpu.abilist")) return "armeabi-v7a,armeabi\n";
            if (command.startsWith("getprop ro.product.cpu.abi")) return "armeabi-v7a\n";
            if (command.startsWith("getprop sys.boot_completed")) return "1\n";
            if (command.startsWith("df")) return "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 1000000 100000 " + (command.contains("/data") ? 5000000 : systemFreeKb) + " 10% /x\n";
            if (command.startsWith("stat -c %s")) {
                Long n = files.get(command.split(" ")[3]);
                return n == null ? "" : n + "\n";
            }
            if (command.startsWith("[ -f ")) return files.containsKey(command.split(" ")[2]) ? "yes\n" : "no\n";
            if (command.startsWith("[ -d ")) return dirs.contains(command.split(" ")[2]) ? "yes\n" : "no\n";
            if (command.startsWith("mount")) return "";
            if (command.startsWith("touch")) return systemWritable ? "ok\n" : "touch: Read-only file system\n";
            for (String part : command.split(";")) {
                part = part.trim();
                String[] t = part.replace("2>&1", "").replace("2>/dev/null", "").trim().split("\\s+");
                if (t[0].equals("rm")) {
                    for (int i = 1; i < t.length; i++) {
                        if (t[i].startsWith("-")) continue;
                        files.remove(t[i]);
                        dirs.remove(t[i]);
                    }
                } else if (t[0].equals("cp") && t.length >= 3 && !t[1].equals("-r")) {
                    Long n = files.get(t[1]);
                    if (n == null) {
                        out.append("cp: no such file\n");
                    } else if (t[2].startsWith("/system") && !t[2].equals(sys) && n / 1024 > systemFreeKb) {
                        out.append("cp: No space left on device\n");
                        files.put(t[2], n / 2);
                    } else {
                        files.put(t[2], n);
                    }
                } else if (t[0].equals("mv") && t.length >= 3) {
                    Long n = files.remove(t[1]);
                    if (n != null) files.put(t[2], n);
                }
            }
            return out.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        WebViewSwap.pauseMs = 0;
        File dir = java.nio.file.Files.createTempDirectory("wvtest").toFile();

        // --- the manifest, both string table kinds
        for (boolean utf8 : new boolean[] {false, true}) {
            String kind = utf8 ? "UTF-8" : "UTF-16";
            byte[] m = axml(utf8, "com.google.android.webview", 604519401L, "com.android.webview.WebViewLibrary",
                "com.google.android.webview.IconProvider", "org.chromium.Thing");
            Axml.Info info = Axml.read(m);
            check(info.packageName.equals("com.google.android.webview") && info.versionCode == 604519401L, kind + ": package and version code read");
            byte[] r = Axml.renamePackage(m, "com.google.android.webview", "com.android.webview");
            Axml.Info after = Axml.read(r);
            check(after.packageName.equals("com.android.webview") && after.versionCode == 604519401L, kind + ": package renamed, version code kept");
            check(after.strings.contains("com.android.webview.IconProvider") && after.strings.contains("org.chromium.Thing")
                && after.strings.contains("com.android.webview.WebViewLibrary"), kind + ": names under the package follow, class names stay");
            check(((r[4] & 0xff) | ((r[5] & 0xff) << 8) | ((r[6] & 0xff) << 16) | ((r[7] & 0xff) << 24)) == r.length, kind + ": file size field matches");
            byte[] back = Axml.renamePackage(r, "com.android.webview", "com.google.android.webview");
            check(Axml.read(back).packageName.equals("com.google.android.webview"), kind + ": renaming back works (longer string)");
        }

        // --- the rewritten APK
        byte[] manifest = axml(false, "com.google.android.webview", 604519401L, "com.android.webview.WebViewLibrary",
            "com.google.android.webview.IconProvider");
        File src = apk(dir, "src.apk", manifest, false);
        File out = new File(dir, "out.apk");
        ApkRewriter.Result res = ApkRewriter.rewrite(src, out, WebViewSwap.TARGET_PKG, new HashSet<>(Arrays.asList("armeabi-v7a")), l -> { });
        try (ZipFile z = new ZipFile(out)) {
            Axml.Info i = Axml.read(read(z, "AndroidManifest.xml"));
            check(i.packageName.equals("com.android.webview") && i.versionCode == 604519401L, "rewritten APK: manifest renamed");
            check(z.getEntry("lib/arm64-v8a/libwebviewchromium.so") == null && z.getEntry("lib/armeabi-v7a/libwebviewchromium.so") != null
                && res.skippedAbiEntries == 1, "rewritten APK: only the TV's CPU type kept");
            check(z.getEntry("META-INF/OLD.SF") == null && z.getEntry("META-INF/CERT.RSA") != null, "rewritten APK: old signature dropped, new one added");
            ZipEntry so = z.getEntry("lib/armeabi-v7a/libwebviewchromium.so");
            check(so.getMethod() == ZipEntry.STORED, "rewritten APK: library stored uncompressed");
            try (ZipFile o = new ZipFile(src)) {
                check(Arrays.equals(read(z, so.getName()), read(o, so.getName())) && Arrays.equals(read(z, "classes.dex"), read(o, "classes.dex"))
                    && Arrays.equals(read(z, "assets/stored.pak"), read(o, "assets/stored.pak")), "rewritten APK: file contents are identical");
            }
        }
        Map<String, Long> offsets = dataOffsets(out);
        check(offsets.get("lib/armeabi-v7a/libwebviewchromium.so") % 4096 == 0, "rewritten APK: library starts on a 4096 boundary");
        check(offsets.get("assets/stored.pak") % 4 == 0, "rewritten APK: other stored files are 4-aligned");
        boolean signed = true;
        try (JarFile jar = new JarFile(out, true)) {
            int n = 0;
            for (java.util.Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                try (InputStream in = jar.getInputStream(je)) {
                    byte[] b = new byte[8192];
                    while (in.read(b) > 0) { /* reading verifies the digest */ }
                }
                if (!je.getName().startsWith("META-INF/") && je.getCodeSigners() == null) signed = false;
                n++;
            }
            check(n > 5 && signed, "rewritten APK: the signature verifies for every file (" + n + " entries)");
        } catch (java.lang.SecurityException e) {
            check(false, "signature check failed: " + e.getMessage());
        }
        // a stored-libraries source and a source that already has the right name
        File src2 = apk(dir, "src2.apk", axml(true, "com.android.webview", 113L, "com.android.webview.WebViewLibrary"), true);
        File out2 = new File(dir, "out2.apk");
        ApkRewriter.rewrite(src2, out2, WebViewSwap.TARGET_PKG, new HashSet<>(Arrays.asList("x86")), l -> { });
        try (ZipFile z = new ZipFile(out2)) {
            check(z.getEntry("lib/arm64-v8a/libwebviewchromium.so") != null && Axml.read(read(z, "AndroidManifest.xml")).packageName.equals("com.android.webview"),
                "no matching CPU type in the list: everything is kept; a correctly named APK stays as is");
        }

        // --- inspect
        check(WebViewSwap.inspect(src).versionCode == 604519401L, "inspect reads a WebView APK");
        try {
            WebViewSwap.inspect(apk(dir, "other.apk", axml(false, "org.some.app", 1, "x"), false));
            check(false, "a non-WebView APK is refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("not a WebView"), "a non-WebView APK is refused: " + e.getMessage().substring(0, 40));
        }
        File junk = new File(dir, "junk.apk");
        java.nio.file.Files.write(junk.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
        try {
            WebViewSwap.inspect(junk);
            check(false, "a file that is not an APK is refused");
        } catch (CleanException e) {
            check(true, "a file that is not an APK is refused");
        }

        // --- helpers
        check("/system/app/webview/webview.apk".equals(WebViewSwap.systemPath("package:/data/app/x/base.apk\npackage:/system/app/webview/webview.apk\n"))
            && WebViewSwap.systemPath("package:/data/app/x/base.apk\n") == null, "system copy found among several paths");
        check(WebViewSwap.freeKb("/system: 1006M total, 700M used, 306M available (block size 4096)\n") == 306 * 1024, "df of old Androids");
        check(WebViewSwap.freeKb("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/x 1000 400 600 40% /system\n") == 600, "df of newer Androids");
        check(WebViewSwap.abis("armeabi-v7a,armeabi", "armeabi-v7a", "armeabi").size() == 2, "CPU types listed once");

        // --- the whole swap on a pretend TV
        WebTv tv = new WebTv();
        tv.newVersion = 604519401L;
        File work = new File(dir, "work.apk");
        List<String> log = new ArrayList<>();
        String done = WebViewSwap.swap(tv, new TvInfo(), src, "webview.apk", work, log::add);
        check(done.contains("new WebView") && tv.isNew() && tv.rebootHappened, "swap: the new file is in place and the TV restarted (" + done + ")");
        check(tv.files.get(WebViewSwap.BACKUP) == tv.origSize, "swap: the original was saved first");
        check(!tv.dirs.contains("/system/app/webview/oat") && !tv.files.containsKey(WebViewSwap.STAGE) && !work.exists(), "swap: old compiled copy, staging file and phone copy cleaned up");
        check(tv.history.stream().anyMatch(c -> c.startsWith("mount -o remount,ro")), "swap: system partition set back to read-only");

        // a second swap must not overwrite the saved original
        tv.files.put(WebViewSwap.BACKUP, 4242L);
        WebViewSwap.swap(tv, new TvInfo(), src, "webview.apk", work, l -> { });
        check(tv.files.get(WebViewSwap.BACKUP) == 4242L, "swap again: the saved original is kept as it was");

        // not enough room beside the old file: write over it
        WebTv small = new WebTv();
        small.newVersion = 604519401L;
        small.systemFreeKb = 10;
        WebViewSwap.swap(small, new TvInfo(), src, "webview.apk", work, l -> { });
        check(small.isNew(), "swap with a full system partition: written over the old file");

        // the TV refuses the new WebView after the restart: the original comes back by itself
        WebTv bad = new WebTv();
        bad.newVersion = 604519401L;
        bad.acceptNew = false;
        try {
            WebViewSwap.swap(bad, new TvInfo(), src, "webview.apk", work, l -> { });
            check(false, "a TV that rejects the new WebView is reported");
        } catch (CleanException e) {
            check(bad.files.get(bad.sys) == bad.origSize && e.getMessage().contains("original is back"), "TV rejects it: the original is put back automatically");
        }

        // no root, read-only system, older version, bad file: nothing is changed
        WebTv noroot = new WebTv();
        noroot.rootOk = false;
        try {
            WebViewSwap.swap(noroot, new TvInfo(), src, "webview.apk", work, l -> { });
            check(false, "no root is reported");
        } catch (CleanException e) {
            check(noroot.files.get(noroot.sys) == noroot.origSize && !noroot.files.containsKey(WebViewSwap.BACKUP), "no root: clear message, nothing touched");
        }
        WebTv ro = new WebTv();
        ro.systemWritable = false;
        try {
            WebViewSwap.swap(ro, new TvInfo(), src, "webview.apk", work, l -> { });
            check(false, "a read-only system is reported");
        } catch (CleanException e) {
            check(ro.files.get(ro.sys) == ro.origSize, "read-only system: nothing touched");
        }
        WebTv newer = new WebTv();
        newer.installedVersion = 900000000L;
        try {
            WebViewSwap.swap(newer, new TvInfo(), src, "webview.apk", work, l -> { });
            check(false, "an older WebView than the TV's is refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("newer") && newer.files.get(newer.sys) == newer.origSize, "an older WebView than the TV's is refused");
        }

        // --- restore
        WebViewSwap.restore(tv, l -> { });
        check(tv.files.get(tv.sys) == 4242L && tv.rebootHappened, "restore: the saved original is back in place");
        WebTv none = new WebTv();
        try {
            WebViewSwap.restore(none, l -> { });
            check(false, "restore without a saved original is reported");
        } catch (CleanException e) {
            check(e.getMessage().contains("no saved original"), "restore without a saved original: clear message");
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
