package tv.clean.phone.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The per-TV lists collected by the upstream project (345 TVs, 70+ brands), read from assets/db/models.txt. */
public final class Db {
    public static final class Model {
        public String dir = "";
        public String tv = "";
        public String brand = "";
        public String os = "";
        public String stock = "";
        public String notes = "";
        public boolean hasConfig;
        public final List<String> pkgs = new ArrayList<>();
        String haystack = "";
    }

    public static final class Match {
        public final Model model;
        public final int score;

        Match(Model model, int score) {
            this.model = model;
            this.score = score;
        }

        public boolean strong() {
            return score >= STRONG;
        }
    }

    public static final int STRONG = 8;
    private static final int WEAK = 5;
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
        "androidtv", "googletv", "smarttv", "tvbox", "generic", "unknown", "android", "atv", "mediatek", "amlogic",
        "rockchip", "realtek", "mstar", "hisilicon", "broadcom", "androidbox", "streamer", "dongle", "project"));

    public final List<Model> models = new ArrayList<>();
    private final Map<String, Integer> freq = new HashMap<>();
    private final Map<String, Map<String, Integer>> brandFreq = new HashMap<>();

    public static Db parse(InputStream in) throws IOException {
        Db db = new Db();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Model cur = null;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.startsWith("#M\t")) {
                String[] f = line.split("\t", -1);
                cur = new Model();
                cur.dir = f[1];
                cur.tv = f.length > 2 ? f[2] : "";
                cur.brand = f.length > 3 ? f[3] : "";
                cur.os = f.length > 4 ? f[4] : "";
                cur.stock = f.length > 5 ? f[5] : "";
                cur.hasConfig = f.length > 6 && f[6].equals("1");
                cur.haystack = norm(cur.tv + " " + cur.dir);
                db.models.add(cur);
            } else if (line.startsWith("#N\t")) {
                if (cur != null) cur.notes = line.substring(3);
            } else if (cur != null && !line.startsWith("#") && Guards.isValidName(line)) {
                cur.pkgs.add(line);
            }
        }
        for (Model m : db.models) {
            String b = norm(m.brand);
            for (String p : new HashSet<>(m.pkgs)) {
                db.freq.merge(p, 1, Integer::sum);
                if (!b.isEmpty()) db.brandFreq.computeIfAbsent(p, k -> new HashMap<>()).merge(b, 1, Integer::sum);
            }
        }
        return db;
    }

    /** Number of TV lists that disable this package. */
    public int frequency(String pkg) {
        Integer n = freq.get(pkg);
        return n == null ? 0 : n;
    }

    /** Number of lists from TVs of this brand that disable the package. */
    public int brandFrequency(String pkg, TvInfo tv) {
        Map<String, Integer> m = brandFreq.get(pkg);
        if (m == null) return 0;
        int best = 0;
        for (String key : new String[] {tv.brand, tv.manufacturer}) {
            String b = norm(key);
            if (b.length() < 3) continue;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey().contains(b) || b.contains(e.getKey())) best = Math.max(best, e.getValue());
            }
        }
        return best;
    }

    public Model byDir(String dir) {
        for (Model m : models) if (m.dir.equals(dir)) return m;
        return null;
    }

    /** TV lists that look like this TV, best first. */
    public List<Match> match(TvInfo tv) {
        List<String> tokens = new ArrayList<>();
        for (String raw : new String[] {tv.model, tv.device, tv.product}) {
            String t = norm(raw);
            if (t.length() >= 4 && !GENERIC.contains(t) && !tokens.contains(t)) tokens.add(t);
        }
        List<Match> out = new ArrayList<>();
        String brandN = norm(tv.brand.isEmpty() ? tv.manufacturer : tv.brand);
        for (Model m : models) {
            int score = 0;
            for (String t : tokens) {
                if (m.haystack.contains(t)) score += t.length();
            }
            if (score > 0 && brandN.length() >= 3 && m.haystack.contains(brandN)) score += 2;
            if (score >= WEAK) out.add(new Match(m, score));
        }
        Collections.sort(out, (a, b) -> b.score - a.score);
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
