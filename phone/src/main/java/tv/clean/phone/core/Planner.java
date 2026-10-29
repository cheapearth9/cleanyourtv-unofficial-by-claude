package tv.clean.phone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what to offer for disabling on this TV. Three tiers:
 *  A  the package is in the upstream list of a TV that matches this one (pre-ticked)
 *  B  packages many TVs' lists disable, or that same-brand TVs disable (pre-ticked, except features people use)
 *  C  seen on a few TVs, or a feature people use (offered, not ticked)
 * Only packages that are installed and enabled here are offered; the TV-side safety checks run later.
 */
public final class Planner {
    public static final int TIER_A = 0;
    public static final int TIER_B = 1;
    public static final int TIER_C = 2;

    public static final class Item {
        public final String pkg;
        public int tier;
        public String why;
        public boolean checked;

        Item(String pkg, int tier, String why, boolean checked) {
            this.pkg = pkg;
            this.tier = tier;
            this.why = why;
            this.checked = checked;
        }
    }

    public static final class Plan {
        public final List<Item> items = new ArrayList<>();
        public final List<Db.Match> matches = new ArrayList<>();
        public Db.Model best; // strong match, or null
    }

    private static final int CROSS_TV_MIN = 10;
    private static final int BRAND_MIN = 3;
    private static final int CROSS_TV_SOME = 2;

    public static Plan plan(Db db, TvInfo tv) {
        Plan plan = new Plan();
        plan.matches.addAll(db.match(tv));
        Db.Match top = plan.matches.isEmpty() ? null : plan.matches.get(0);
        if (top != null && top.strong()) plan.best = top.model;
        Map<String, Item> items = new LinkedHashMap<>();

        if (plan.best != null) {
            for (String p : plan.best.pkgs) {
                if (tv.enabled.contains(p) && !Guards.neverSuggest(p)) {
                    items.put(p, new Item(p, TIER_A, "on the list for " + shortName(plan.best), true));
                }
            }
        }
        // near matches (same family): offered, not ticked
        for (Db.Match m : plan.matches) {
            if (m.model == plan.best) continue;
            for (String p : m.model.pkgs) {
                if (tv.enabled.contains(p) && !Guards.neverSuggest(p) && !items.containsKey(p)) {
                    items.put(p, new Item(p, TIER_C, "on the list for similar " + shortName(m.model), false));
                }
            }
            if (items.size() > 400) break;
        }
        for (String p : tv.enabled) {
            if (Guards.neverSuggest(p)) continue;
            int n = db.frequency(p);
            int b = db.brandFrequency(p, tv);
            Item existing = items.get(p);
            if (existing != null && existing.tier == TIER_A) {
                continue;
            }
            boolean soft = Guards.isSoft(p);
            if (n >= CROSS_TV_MIN || b >= BRAND_MIN) {
                String why = b >= BRAND_MIN ? "disabled on " + b + " " + brandLabel(tv) + " TV lists"
                                            : "disabled on " + n + " of " + db.models.size() + " TV lists";
                items.put(p, new Item(p, soft ? TIER_C : TIER_B, why, !soft));
            } else if (n >= CROSS_TV_SOME && existing == null) {
                items.put(p, new Item(p, TIER_C, "disabled on " + n + " TV lists", false));
            }
        }
        plan.items.addAll(items.values());
        plan.items.sort((x, y) -> x.tier != y.tier ? x.tier - y.tier : x.pkg.compareTo(y.pkg));
        return plan;
    }

    private static String brandLabel(TvInfo tv) {
        String b = tv.brand.isEmpty() ? tv.manufacturer : tv.brand;
        return b.isEmpty() ? "same-brand" : b;
    }

    static String shortName(Db.Model m) {
        String s = m.tv;
        int paren = s.indexOf(" (");
        return paren > 0 ? s.substring(0, paren) : s;
    }
}
