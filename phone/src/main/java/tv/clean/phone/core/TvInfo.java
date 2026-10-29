package tv.clean.phone.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** What the TV says about itself, plus which packages it has enabled. */
public final class TvInfo {
    public String model = "";
    public String brand = "";
    public String manufacturer = "";
    public String device = "";
    public String product = "";
    public String release = "";
    public String fingerprint = "";
    public String adbSecure = "";
    public int sdk;
    public boolean leanback;
    public Set<String> enabled = new LinkedHashSet<>();

    public String title() {
        String m = model.isEmpty() ? product : model;
        String b = brand.isEmpty() ? manufacturer : brand;
        return (b + " " + m).trim();
    }

    public String androidLabel() {
        return "Android " + (release.isEmpty() ? "?" : release) + " (API " + sdk + ")";
    }

    public static TvInfo probe(TvShell sh) throws IOException {
        String out = sh.run(
            "for k in ro.product.model ro.product.brand ro.product.manufacturer ro.product.device ro.product.name "
            + "ro.build.version.sdk ro.build.version.release ro.build.fingerprint ro.adb.secure; do echo \"$k=$(getprop $k)\"; done; "
            + "echo \"leanback=$(pm list features 2>/dev/null | grep -c leanback)\"", 20000);
        Map<String, String> kv = new HashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        TvInfo t = new TvInfo();
        t.model = kv.getOrDefault("ro.product.model", "");
        t.brand = kv.getOrDefault("ro.product.brand", "");
        t.manufacturer = kv.getOrDefault("ro.product.manufacturer", "");
        t.device = kv.getOrDefault("ro.product.device", "");
        t.product = kv.getOrDefault("ro.product.name", "");
        t.release = kv.getOrDefault("ro.build.version.release", "");
        t.fingerprint = kv.getOrDefault("ro.build.fingerprint", "");
        t.adbSecure = kv.getOrDefault("ro.adb.secure", "?");
        try {
            t.sdk = Integer.parseInt(kv.getOrDefault("ro.build.version.sdk", "0"));
        } catch (NumberFormatException e) {
            t.sdk = 0;
        }
        t.leanback = !"0".equals(kv.getOrDefault("leanback", "0"));
        t.enabled = listPackages(sh, true);
        return t;
    }

    /** {@code pm list packages} for user 0: enabled ones, or disabled ones when {@code enabled} is false. */
    public static Set<String> listPackages(TvShell sh, boolean enabled) throws IOException {
        String flag = enabled ? "-e" : "-d";
        String out = sh.run("pm list packages " + flag + " --user 0", 40000);
        Set<String> r = parsePackages(out);
        if (r.isEmpty() && !enabled) {
            return r; // genuinely nothing disabled, or the flag is unknown: callers treat empty as "none"
        }
        if (r.isEmpty()) r = parsePackages(sh.run("pm list packages " + flag, 40000));
        return r;
    }

    static Set<String> parsePackages(String out) {
        Set<String> r = new LinkedHashSet<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                String name = line.substring(8).trim();
                int sp = name.indexOf(' ');
                if (sp > 0) name = name.substring(0, sp);
                if (Guards.isValidName(name)) r.add(name);
            }
        }
        return r;
    }
}
