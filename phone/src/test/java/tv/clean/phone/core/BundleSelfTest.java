package tv.clean.phone.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Installing .apkm / .xapk / .apks bundles: which parts are chosen for a TV, and the steps sent to it. */
public final class BundleSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

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

    /** A compiled manifest with package, versionCode and optionally split / configForSplit. */
    static byte[] manifest(String pkg, long versionCode, String split, String configFor) {
        List<String> s = new ArrayList<>(Arrays.asList("manifest", "package", "versionCode", "split", "configForSplit", pkg));
        int splitIdx = -1;
        int configIdx = -1;
        if (!split.isEmpty()) {
            splitIdx = s.size();
            s.add(split);
        }
        if (!configFor.isEmpty()) {
            configIdx = s.size();
            s.add(configFor);
        }
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offs = new int[s.size()];
        for (int i = 0; i < s.size(); i++) {
            offs[i] = strings.size();
            p16(strings, s.get(i).length());
            byte[] raw = s.get(i).getBytes(StandardCharsets.UTF_16LE);
            strings.write(raw, 0, raw.length);
            p16(strings, 0);
        }
        while (strings.size() % 4 != 0) strings.write(0);
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        int stringsStart = 28 + 4 * s.size();
        p16(pool, 1);
        p16(pool, 28);
        p32(pool, stringsStart + strings.size());
        p32(pool, s.size());
        p32(pool, 0);
        p32(pool, 0);
        p32(pool, stringsStart);
        p32(pool, 0);
        for (int o : offs) p32(pool, o);
        pool.write(strings.toByteArray(), 0, strings.size());

        List<int[]> attrs = new ArrayList<>(); // name index, string index or -1, int value
        attrs.add(new int[] {1, 5, 0});
        attrs.add(new int[] {2, -1, (int) versionCode});
        if (splitIdx >= 0) attrs.add(new int[] {3, splitIdx, 0});
        if (configIdx >= 0) attrs.add(new int[] {4, configIdx, 0});
        ByteArrayOutputStream el = new ByteArrayOutputStream();
        p16(el, 0x102);
        p16(el, 16);
        p32(el, 16 + 20 + 20 * attrs.size());
        p32(el, 1);
        p32(el, -1);
        p32(el, -1);
        p32(el, 0);
        p16(el, 20);
        p16(el, 20);
        p16(el, attrs.size());
        p16(el, 0);
        p16(el, 0);
        p16(el, 0);
        for (int[] a : attrs) {
            p32(el, -1);
            p32(el, a[0]);
            p32(el, a[1]);
            p16(el, 8);
            el.write(0);
            el.write(a[1] >= 0 ? 3 : 0x10);
            p32(el, a[1] >= 0 ? a[1] : a[2]);
        }
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

    /** An APK (a zip with a manifest and some filler). */
    static byte[] apk(String pkg, long vc, String split, String configFor) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            put(z, "AndroidManifest.xml", manifest(pkg, vc, split, configFor));
            byte[] filler = new byte[3000];
            new Random(split.hashCode()).nextBytes(filler);
            put(z, "classes.dex", filler);
        }
        return bo.toByteArray();
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data, 0, data.length);
        z.closeEntry();
    }

    static File zip(File dir, String name, Object... nameAndBytes) throws IOException {
        File f = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (int i = 0; i < nameAndBytes.length; i += 2) put(z, (String) nameAndBytes[i], (byte[]) nameAndBytes[i + 1]);
        }
        return f;
    }

    /** A pretend TV: answers the facts command, remembers pushed files, installs when every named file is there. */
    static final class BundleTv implements TvShell {
        final Map<String, Long> files = new HashMap<>();
        final List<String> history = new ArrayList<>();
        String abilist = "armeabi-v7a,armeabi";
        String density = "Physical density: 320";
        String lang = "vi";
        String installOut = null;
        String installed = "";

        @Override
        public void push(byte[] data, String path, int mode) {
            files.put(path, (long) data.length);
        }

        @Override
        public void pushFile(File local, String path, int mode, Progress p) {
            files.put(path, local.length());
            if (p != null) p.onProgress(local.length(), local.length());
        }

        @Override
        public String run(String c, int timeoutMs) {
            history.add(c);
            if (c.startsWith("echo abis=")) {
                return "abis=" + abilist + "\nabi=" + abilist.split(",")[0] + "\nabi2=\nwm=" + density + "\ndpi=320\nlang=" + lang + "\nlang2=en-US\n";
            }
            if (c.startsWith("rm -rf " + BundleInstaller.DIR)) {
                files.keySet().removeIf(k -> k.startsWith(BundleInstaller.DIR));
                if (c.contains("mkdir")) files.put(BundleInstaller.DIR + "/", 0L);
                return "";
            }
            if (c.startsWith("pm install-multiple")) {
                if (installOut != null) return installOut;
                for (String t : c.split(" ")) {
                    if (t.startsWith(BundleInstaller.DIR + "/part") && !files.containsKey(t)) return "Failure [INSTALL_FAILED_INVALID_URI]\n";
                }
                installed = c;
                return "Success\n";
            }
            return "";
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("bundletest").toFile();
        File work = new File(dir, "work");
        work.mkdir();

        // --- an .apkm: base + CPU + density + language splits
        String pkg = "com.example.app";
        File apkm = zip(dir, "app.apkm",
            "info.json", "{}".getBytes(StandardCharsets.UTF_8),
            "base.apk", apk(pkg, 5, "", ""),
            "split_config.arm64_v8a.apk", apk(pkg, 5, "config.arm64_v8a", ""),
            "split_config.armeabi_v7a.apk", apk(pkg, 5, "config.armeabi_v7a", ""),
            "split_config.xxhdpi.apk", apk(pkg, 5, "config.xxhdpi", ""),
            "split_config.xhdpi.apk", apk(pkg, 5, "config.xhdpi", ""),
            "split_config.en.apk", apk(pkg, 5, "config.en", ""),
            "split_config.vi.apk", apk(pkg, 5, "config.vi", ""),
            "split_config.fr.apk", apk(pkg, 5, "config.fr", ""),
            "split_dynamic.apk", apk(pkg, 5, "dynamic", ""));
        check(BundleInstaller.isBundle(apkm), "an .apkm is recognised as a bundle");
        File plain = zip(dir, "plain.apk", "AndroidManifest.xml", manifest(pkg, 1, "", ""), "classes.dex", new byte[10]);
        File notZip = new File(dir, "x.bin");
        java.nio.file.Files.write(notZip.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        check(!BundleInstaller.isBundle(plain) && !BundleInstaller.isBundle(notZip), "a plain APK and a non-zip are not bundles");

        BundleTv tv = new BundleTv();
        List<String> log = new ArrayList<>();
        String done = BundleInstaller.install(tv, new TvInfo(), apkm, "app.apkm", work, log::add);
        check(done.contains(pkg), "apkm: installed (" + done + ")");
        String cmd = tv.installed;
        int parts = cmd.split("/part").length - 1;
        check(parts == 6, "apkm: base + feature split + armeabi-v7a + xhdpi + en + vi = 6 parts sent, the rest left out (" + parts + ")");
        check(log.stream().anyMatch(l -> l.contains("6 of 9")), "apkm: the log says 6 of 9 parts fit");
        check(cmd.startsWith("pm install-multiple -r -g "), "apkm: one pm install-multiple call with permissions granted");
        check(tv.files.keySet().stream().noneMatch(k -> k.startsWith(BundleInstaller.DIR)) && work.list().length == 0,
            "apkm: temporary files on the TV and on the phone are cleaned up");

        // --- chosen parts, directly
        Map<String, List<BundleInstaller.Part>> grouped;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apkm)) {
            grouped = BundleInstaller.scan(z);
        }
        List<BundleInstaller.Part> all = grouped.get(pkg);
        check(grouped.size() == 1 && all.size() == 9, "scan: nine APKs, one package");
        List<BundleInstaller.Part> big = BundleInstaller.choose(all, Arrays.asList("arm64-v8a", "armeabi-v7a"), 480, new HashSet<>(Arrays.asList("fr")));
        Set<String> names = new HashSet<>();
        for (BundleInstaller.Part p : big) names.add(p.file());
        check(names.contains("split_config.arm64_v8a.apk") && !names.contains("split_config.armeabi_v7a.apk") && names.contains("split_config.xxhdpi.apk")
            && !names.contains("split_config.xhdpi.apk") && names.contains("split_config.fr.apk") && !names.contains("split_config.vi.apk"),
            "a 64-bit 480 dpi French TV: its own CPU type, density and language (" + names + ")");
        List<BundleInstaller.Part> near = BundleInstaller.choose(all, Arrays.asList("armeabi-v7a"), 213, new HashSet<>());
        Set<String> n2 = new HashSet<>();
        for (BundleInstaller.Part p : near) n2.add(p.file());
        check(n2.contains("split_config.xhdpi.apk") && n2.contains("split_dynamic.apk") && n2.contains("split_config.en.apk"),
            "a 213 dpi TV gets the closest density the bundle has, feature splits and English are always kept");

        // --- a TV the bundle has no CPU type for
        BundleTv x86 = new BundleTv();
        x86.abilist = "mips";
        try {
            BundleInstaller.install(x86, new TvInfo(), apkm, "app.apkm", work, l -> { });
            check(false, "no matching CPU type is reported");
        } catch (CleanException e) {
            check(e.getMessage().contains("processor") && x86.installed.isEmpty(), "no CPU type for this TV: clear message, nothing installed");
        }

        // --- an .xapk: one APK and an .obb
        File xapk = zip(dir, "game.xapk",
            "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
            "com.game.big.apk", apk("com.game.big", 9, "", ""),
            "Android/obb/com.game.big/main.9.com.game.big.obb", new byte[5000]);
        BundleTv t2 = new BundleTv();
        String d2 = BundleInstaller.install(t2, new TvInfo(), xapk, "game.xapk", work, l -> { });
        check(d2.contains("com.game.big") && t2.files.containsKey("/sdcard/Android/obb/com.game.big/main.9.com.game.big.obb"), "xapk: app installed and its .obb copied to the obb folder");
        check(t2.history.stream().anyMatch(c -> c.equals("mkdir -p /sdcard/Android/obb/com.game.big")), "xapk: the obb folder is created");

        // --- an .apks (bundletool): main APK beside its splits, standalone APKs elsewhere
        File apks = zip(dir, "app.apks",
            "toc.pb", new byte[8],
            "standalones/standalone-armeabi_v7a.apk", apk(pkg, 5, "", ""),
            "splits/base-master.apk", apk(pkg, 5, "", ""),
            "splits/base-armeabi_v7a.apk", apk(pkg, 5, "config.armeabi_v7a", ""),
            "splits/base-xhdpi.apk", apk(pkg, 5, "config.xhdpi", ""));
        BundleTv t3 = new BundleTv();
        BundleInstaller.install(t3, new TvInfo(), apks, "app.apks", work, l -> { });
        check(t3.installed.split("/part").length - 1 == 3, "apks: base-master and its two splits are installed, the standalone APK is ignored");

        // --- a zip with a universal APK among per-CPU ones
        File uni = zip(dir, "multi.zip", "a-arm64.apk", apk(pkg, 5, "", ""), "universal.apk", apk(pkg, 5, "", ""));
        BundleTv t4 = new BundleTv();
        BundleInstaller.install(t4, new TvInfo(), uni, "multi.zip", work, l -> { });
        check(t4.installed.split("/part").length - 1 == 1, "a zip of several complete APKs: one is installed");

        // --- two different apps in one zip: both are installed
        File two = zip(dir, "two.zip", "one.apk", apk("com.one", 1, "", ""), "two.apk", apk("com.two", 1, "", ""));
        BundleTv t5 = new BundleTv();
        String d5 = BundleInstaller.install(t5, new TvInfo(), two, "two.zip", work, l -> { });
        check(d5.contains("com.one") && d5.contains("com.two") && t5.history.stream().filter(c -> c.startsWith("pm install-multiple")).count() == 2, "a zip of two apps: each is installed");

        // --- the TV refuses: the reason is explained, the TV is cleaned up
        BundleTv bad = new BundleTv();
        bad.installOut = "Failure [INSTALL_FAILED_MISSING_SPLIT: Missing split for com.example.app]\n";
        try {
            BundleInstaller.install(bad, new TvInfo(), apkm, "app.apkm", work, l -> { });
            check(false, "a refused install is reported");
        } catch (CleanException e) {
            check(e.getMessage().contains("missing") && bad.files.keySet().stream().noneMatch(k -> k.startsWith(BundleInstaller.DIR)), "refused by the TV: explained, parts removed from the TV");
        }

        // --- through the normal APK installer: a bundle is handed over, a junk file is refused
        File copy = new File(work, "install.apk");
        java.nio.file.Files.copy(apkm.toPath(), copy.toPath());
        BundleTv t6 = new BundleTv();
        ApkInstaller.install(t6, new TvInfo(), copy, "app.apkm", l -> { });
        check(t6.installed.startsWith("pm install-multiple"), "the APK installer hands a bundle to the bundle installer");
        try {
            ApkInstaller.install(new BundleTv(), new TvInfo(), notZip, "x.bin", l -> { });
            check(false, "a junk file is refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("not an APK"), "a file that is neither APK nor bundle is refused");
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
