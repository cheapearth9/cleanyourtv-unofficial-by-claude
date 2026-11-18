package tv.clean.phone.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Installs an app that comes as several APKs in one zip: .apkm (APKMirror), .xapk (APKPure), .apks (bundletool, SAI) or any zip of
 * APKs. It reads the manifest of every APK inside to tell the main APK from its splits, keeps only the splits this TV can
 * use (its CPU type, the closest screen density, its language), sends them to the TV and installs them together with
 * "pm install-multiple". Expansion files (.obb, in .xapk) are copied to the TV's obb folder afterwards.
 */
public final class BundleInstaller {
    private BundleInstaller() {}

    static final String DIR = "/data/local/tmp/cyt-bundle";

    /** One APK inside a bundle. */
    static final class Part {
        String entry = "";
        String pkg = "";
        String split = "";
        String configFor = "";
        long versionCode;
        long size;

        String file() {
            return entry.substring(entry.lastIndexOf('/') + 1);
        }

        String dir() {
            int i = entry.lastIndexOf('/');
            return i < 0 ? "" : entry.substring(0, i);
        }
    }

    /** True for a zip that holds APKs but is not an APK itself (a plain APK has AndroidManifest.xml at its root). */
    public static boolean isBundle(File f) {
        try (ZipFile z = new ZipFile(f)) {
            if (z.getEntry("AndroidManifest.xml") != null) return false;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                if (isApk(e.nextElement())) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static boolean isApk(ZipEntry e) {
        return !e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    // ------------------------------------------------------------------ reading the bundle

    private static Axml.Info manifestOf(ZipFile z, ZipEntry e) throws IOException {
        try (ZipInputStream in = new ZipInputStream(z.getInputStream(e))) {
            for (ZipEntry inner; (inner = in.getNextEntry()) != null; ) {
                if (!inner.getName().equals("AndroidManifest.xml")) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    bo.write(b, 0, n);
                    if (bo.size() > 16 * 1024 * 1024) throw new IOException("a manifest that big is not a manifest");
                }
                return Axml.read(bo.toByteArray());
            }
        }
        throw new IOException(e.getName() + " has no AndroidManifest.xml");
    }

    /** Every APK in the zip with what its manifest says, grouped by package (a normal bundle has one package). */
    static Map<String, List<Part>> scan(ZipFile z) throws CleanException {
        Map<String, List<Part>> out = new LinkedHashMap<>();
        for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (!isApk(e)) continue;
            Part p = new Part();
            p.entry = e.getName();
            p.size = e.getSize();
            try {
                Axml.Info info = manifestOf(z, e);
                p.pkg = info.packageName;
                p.split = info.splitName;
                p.configFor = info.configForSplit;
                p.versionCode = info.versionCode;
            } catch (IOException | RuntimeException ex) {
                throw new CleanException(e.getName() + " inside the bundle is not a readable APK (" + ex.getMessage() + ").");
            }
            if (p.pkg.isEmpty()) throw new CleanException(e.getName() + " inside the bundle has no package name.");
            out.computeIfAbsent(p.pkg, k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ choosing what the TV needs

    private static final String[] ABI_NAMES = {"arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"};

    /** "arm64-v8a" or "arm64_v8a" to the form used in split names. */
    private static String abiToken(String abi) {
        return abi.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String abiOf(String token) {
        for (String a : ABI_NAMES) if (token.equals(a)) return a;
        return null;
    }

    private static int densityOf(String token) {
        switch (token) {
            case "ldpi": return 120;
            case "mdpi": return 160;
            case "tvdpi": return 213;
            case "hdpi": return 240;
            case "xhdpi": return 320;
            case "xxhdpi": return 480;
            case "xxxhdpi": return 640;
            default: return -1;
        }
    }

    private static boolean looksLikeLanguage(String token) {
        return token.matches("[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?") || token.startsWith("b+");
    }

    /** The part of a split's name after "config.", or null when it is not a config split. */
    private static String configToken(Part p) {
        int at = p.split.lastIndexOf("config.");
        return at < 0 ? null : p.split.substring(at + 7);
    }

    private static Part pickBase(List<Part> bases, List<Part> splits, List<String> abis) {
        if (bases.size() == 1) return bases.get(0);
        if (!splits.isEmpty()) {
            // the main APK that sits beside the splits (a .apks file also holds standalone APKs elsewhere)
            String dir = splits.get(0).dir();
            Part found = null;
            for (Part b : bases) {
                if (b.dir().equals(dir)) {
                    if (found != null) {
                        found = null;
                        break;
                    }
                    found = b;
                }
            }
            if (found != null) return found;
            for (Part b : bases) if (b.file().toLowerCase(Locale.ROOT).contains("base")) return b;
        }
        for (Part b : bases) if (b.entry.toLowerCase(Locale.ROOT).contains("universal")) return b;
        for (String abi : abis) {
            for (Part b : bases) if (b.entry.toLowerCase(Locale.ROOT).replace('-', '_').contains(abiToken(abi))) return b;
        }
        Part best = bases.get(0);
        for (Part b : bases) if (b.versionCode > best.versionCode) best = b;
        return best;
    }

    /**
     * From all APKs of one package: the main APK and the splits this TV can use.
     *
     * @param abis     the TV's CPU types in order of preference ("armeabi-v7a", ...)
     * @param density  the TV's screen density in dpi (0 if unknown)
     * @param langs    language codes to keep besides English ("vi", ...)
     */
    static List<Part> choose(List<Part> parts, List<String> abis, int density, Set<String> langs) throws CleanException {
        List<Part> bases = new ArrayList<>();
        List<Part> splits = new ArrayList<>();
        for (Part p : parts) (p.split.isEmpty() ? bases : splits).add(p);
        if (bases.isEmpty()) throw new CleanException("This bundle has no main APK (only split parts).");
        Part base = pickBase(bases, splits, abis);
        List<Part> out = new ArrayList<>();
        out.add(base);

        // parts that belong to one target (the main APK or one feature split) are chosen among each other
        Map<String, List<Part>> targets = new LinkedHashMap<>();
        for (Part p : splits) targets.computeIfAbsent(p.configFor, k -> new ArrayList<>()).add(p);
        int want = density > 0 ? density : 320;
        for (List<Part> group : targets.values()) {
            List<Part> abiParts = new ArrayList<>();
            List<Part> densityParts = new ArrayList<>();
            for (Part p : group) {
                String token = configToken(p);
                if (token == null) {
                    out.add(p); // a feature split: keep
                } else if (abiOf(token) != null) {
                    abiParts.add(p);
                } else if (densityOf(token) > 0) {
                    densityParts.add(p);
                } else if (looksLikeLanguage(token)) {
                    String lang = token.toLowerCase(Locale.ROOT).replaceAll("[_-].*", "");
                    if (lang.equals("en") || langs.contains(lang)) out.add(p);
                } else {
                    out.add(p); // nodpi, anydpi, texture formats...
                }
            }
            if (!abiParts.isEmpty()) {
                Part pick = null;
                for (String abi : abis) {
                    for (Part p : abiParts) {
                        if (configToken(p).equals(abiToken(abi))) {
                            pick = p;
                            break;
                        }
                    }
                    if (pick != null) break;
                }
                if (pick == null) {
                    throw new CleanException("This app has no files for this TV's processor (" + (abis.isEmpty() ? "unknown" : abis.get(0))
                        + "); the bundle only has " + abiParts.size() + " other type(s).");
                }
                out.add(pick);
            }
            if (!densityParts.isEmpty()) {
                Part pick = null;
                int bestDiff = Integer.MAX_VALUE;
                for (Part p : densityParts) {
                    int d = densityOf(configToken(p));
                    int diff = Math.abs(d - want);
                    if (diff < bestDiff || (diff == bestDiff && pick != null && d > densityOf(configToken(pick)))) {
                        bestDiff = diff;
                        pick = p;
                    }
                }
                out.add(pick);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ on the TV

    /** The TV's CPU types, screen density and language, in one command. */
    static final class Facts {
        List<String> abis = new ArrayList<>();
        int density;
        Set<String> langs = new java.util.HashSet<>();
    }

    static Facts facts(TvShell sh) throws IOException {
        String out = sh.run("echo abis=$(getprop ro.product.cpu.abilist); echo abi=$(getprop ro.product.cpu.abi); "
            + "echo abi2=$(getprop ro.product.cpu.abi2); echo wm=$(wm density 2>/dev/null | head -n 1); "
            + "echo dpi=$(getprop ro.sf.lcd_density); echo lang=$(getprop persist.sys.locale)$(getprop persist.sys.language); "
            + "echo lang2=$(getprop ro.product.locale)", 20000);
        Map<String, String> kv = new java.util.HashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        Facts f = new Facts();
        f.abis.addAll(WebViewSwap.abis(kv.getOrDefault("abis", ""), kv.getOrDefault("abi", ""), kv.getOrDefault("abi2", "")));
        String wm = kv.getOrDefault("wm", "").replaceAll("[^0-9]+$", "");
        String digits = wm.replaceAll(".*[^0-9]", "");
        if (digits.isEmpty()) digits = kv.getOrDefault("dpi", "").replaceAll("[^0-9]", "");
        try {
            f.density = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            f.density = 0;
        }
        for (String l : new String[] {kv.getOrDefault("lang", ""), kv.getOrDefault("lang2", "")}) {
            String code = l.trim().toLowerCase(Locale.ROOT).replaceAll("[_-].*", "");
            if (code.matches("[a-z]{2,3}")) f.langs.add(code);
        }
        return f;
    }

    private static void extract(ZipFile z, ZipEntry e, File to) throws IOException {
        try (InputStream in = z.getInputStream(e); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private static void send(TvShell sh, ZipFile z, String entry, File tmp, String remote, final Log log, final String label) throws IOException, CleanException {
        ZipEntry e = z.getEntry(entry);
        if (e == null) throw new CleanException("The bundle changed while reading it (" + entry + ").");
        extract(z, e, tmp);
        try {
            final int[] last = {-1};
            final boolean big = tmp.length() > 20L * 1024 * 1024;
            sh.pushFile(tmp, remote, 0644, (sent, total) -> {
                if (!big) return;
                int tenth = total <= 0 ? 10 : (int) (sent * 10 / total);
                if (tenth != last[0]) {
                    last[0] = tenth;
                    log.log("    " + label + " " + tenth * 10 + "%");
                }
            });
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Installs a bundle (see the class comment).
     *
     * @param work a folder on this phone for the parts while they are sent (they are deleted one by one)
     * @return a line for the summary
     */
    public static String install(TvShell sh, TvInfo tv, File bundle, String displayName, File work, Log log) throws IOException, CleanException {
        try (ZipFile z = new ZipFile(bundle)) {
            Map<String, List<Part>> packages = scan(z);
            if (packages.isEmpty()) throw new CleanException(displayName + " has no APK inside.");
            Facts facts = facts(sh);
            log.log(displayName + " holds " + packages.size() + " app" + (packages.size() == 1 ? "" : "s") + ". This TV: "
                + (facts.abis.isEmpty() ? "unknown CPU" : facts.abis.get(0)) + ", " + (facts.density > 0 ? facts.density + " dpi" : "unknown density") + ".");
            StringBuilder summary = new StringBuilder();
            String firstPkg = null;
            for (Map.Entry<String, List<Part>> g : packages.entrySet()) {
                List<Part> chosen = choose(g.getValue(), facts.abis, facts.density, facts.langs);
                if (firstPkg == null) firstPkg = g.getKey();
                log.log(g.getKey() + ": installing " + chosen.size() + " of " + g.getValue().size() + " parts"
                    + (chosen.size() < g.getValue().size() ? " (the rest do not fit this TV)" : "") + ".");
                installGroup(sh, tv, z, chosen, work, log);
                if (summary.length() > 0) summary.append(", ");
                summary.append(g.getKey());
            }
            copyObb(sh, z, packages.size() == 1 ? firstPkg : null, work, log);
            return "Installed " + summary + ".";
        } catch (java.util.zip.ZipException e) {
            throw new CleanException(displayName + " is not a valid zip file: " + e.getMessage());
        }
    }

    private static void installGroup(TvShell sh, TvInfo tv, ZipFile z, List<Part> chosen, File work, Log log) throws IOException, CleanException {
        sh.run("rm -rf " + DIR + "; mkdir -p " + DIR, 30000);
        try {
            StringBuilder files = new StringBuilder();
            for (int i = 0; i < chosen.size(); i++) {
                Part p = chosen.get(i);
                log.log("Sending part " + (i + 1) + " of " + chosen.size() + ": " + p.file() + " (" + (p.size / 1024) + " KB)...");
                String remote = DIR + "/part" + i + ".apk";
                send(sh, z, p.entry, new File(work, "bundle-part.tmp"), remote, log, p.file());
                files.append(' ').append(remote);
            }
            log.log("Installing...");
            String out = sh.run("pm install-multiple -r -g" + files + " 2>&1", 600000);
            if (out.contains("Unknown option") || out.contains("Error: Unknown")) {
                out = sh.run("pm install-multiple -r" + files + " 2>&1", 600000);
            }
            if (!out.contains("Success")) throw new CleanException(ApkInstaller.explain(out, tv));
        } finally {
            try {
                sh.run("rm -rf " + DIR + "; true", 30000);
            } catch (IOException ignored) {
                // a leftover folder in /data/local/tmp is harmless
            }
        }
    }

    /** .obb expansion files (some .xapk have them) go where the app looks for them. A failure here only warns: the app is installed. */
    private static void copyObb(TvShell sh, ZipFile z, String onlyPackage, File work, Log log) {
        for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".obb")) continue;
            String file = name.substring(name.lastIndexOf('/') + 1);
            String pkg = onlyPackage;
            int at = name.indexOf("Android/obb/");
            if (at >= 0) {
                String rest = name.substring(at + "Android/obb/".length());
                int slash = rest.indexOf('/');
                if (slash > 0) pkg = rest.substring(0, slash);
            }
            if (pkg == null || !Guards.isValidName(pkg) || !file.matches("[A-Za-z0-9._-]+")) {
                log.log("Skipped " + name + " (cannot tell which app it belongs to).");
                continue;
            }
            try {
                String dir = "/sdcard/Android/obb/" + pkg;
                sh.run("mkdir -p " + dir, 20000);
                log.log("Copying the expansion file " + file + " (" + (e.getSize() / 1048576) + " MB)...");
                send(sh, z, name, new File(work, "bundle-obb.tmp"), dir + "/" + file, log, file);
            } catch (IOException | CleanException ex) {
                log.log("Could not copy " + file + ": " + ex.getMessage() + " (the app is installed; it may ask to download its data).");
            }
        }
    }
}
