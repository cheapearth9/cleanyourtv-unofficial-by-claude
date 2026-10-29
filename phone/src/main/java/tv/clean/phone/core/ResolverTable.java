package tv.clean.phone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Activity Resolver Table" out of {@code dumpsys package}. Works on every Android version
 * (Android 7 has no {@code cmd package query-activities}), so "who answers HOME / Settings / HDMI inputs"
 * is answered the same way everywhere. Disabled packages are not in the table: it is "who answers now".
 */
public final class ResolverTable {
    public static final class Filter {
        public final String pkg;
        public final String cls;
        public final Set<String> actions = new LinkedHashSet<>();
        public final Set<String> categories = new LinkedHashSet<>();
        public final Set<String> types = new LinkedHashSet<>();

        Filter(String pkg, String cls) {
            this.pkg = pkg;
            this.cls = cls;
        }

        public String component() {
            return pkg + "/" + cls;
        }
    }

    private static final Pattern FILTER_LINE = Pattern.compile("^\\s+[0-9a-f]{3,8} ([A-Za-z0-9_.]+)/(\\S+) filter ([0-9a-f]+)");
    private static final Pattern ATTR = Pattern.compile("^\\s+(Action|Category|Type|Scheme): \"([^\"]*)\"");
    private static final Pattern SECTION = Pattern.compile("^[A-Z][A-Za-z ]*(Resolver Table|Preferred Activities|Registered ContentProviders|ContentProvider Authorities):?\\s*$");

    private final List<Filter> filters = new ArrayList<>();

    public static ResolverTable parse(String dump) {
        ResolverTable t = new ResolverTable();
        boolean inActivities = false;
        Map<String, Filter> byKey = new LinkedHashMap<>();
        Filter current = null;
        for (String raw : dump.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!line.startsWith(" ") && !line.isEmpty()) {
                if (line.startsWith("Activity Resolver Table")) {
                    inActivities = true;
                    current = null;
                    continue;
                }
                if (SECTION.matcher(line).matches() || line.endsWith("Resolver Table:")) {
                    inActivities = false;
                    current = null;
                    continue;
                }
            }
            if (!inActivities) continue;
            Matcher fm = FILTER_LINE.matcher(line);
            if (fm.find()) {
                String key = fm.group(1) + "/" + fm.group(2) + "#" + fm.group(3);
                current = byKey.get(key);
                if (current == null) {
                    current = new Filter(fm.group(1), fm.group(2));
                    byKey.put(key, current);
                    t.filters.add(current);
                }
                continue;
            }
            if (current != null) {
                Matcher am = ATTR.matcher(line);
                if (am.find()) {
                    switch (am.group(1)) {
                        case "Action": current.actions.add(am.group(2)); break;
                        case "Category": current.categories.add(am.group(2)); break;
                        case "Type": current.types.add(am.group(2)); break;
                        default: break;
                    }
                }
            }
        }
        return t;
    }

    public List<Filter> filters() {
        return filters;
    }

    /** Components whose filter has the action, the category (if non-null) and the MIME type (if non-null). */
    public Set<String> components(String action, String category, String type) {
        Set<String> out = new LinkedHashSet<>();
        for (Filter f : filters) {
            if (!f.actions.contains(action)) continue;
            if (category != null && !f.categories.contains(category)) continue;
            if (type != null && !f.types.contains(type)) continue;
            out.add(f.component());
        }
        return out;
    }

    public Set<String> packages(String action, String category, String type) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : components(action, category, type)) out.add(Guards.pkgOf(c));
        return out;
    }

    /** Packages that are real launchers: answer HOME, minus placeholders, factory test and setup apps. */
    public Set<String> launcherPackages() {
        Set<String> out = new LinkedHashSet<>();
        for (String c : components("android.intent.action.MAIN", "android.intent.category.HOME", null)) {
            if (c.endsWith("FallbackHome")) continue;
            String p = Guards.pkgOf(c);
            if (p.toLowerCase().matches(".*(factorytest|factory_test|factorymode).*")) continue;
            if (p.equals(Guards.SETUPWRAITH) || p.equals("com.android.tv.settings") || p.equals("com.android.settings")
                || p.equals("com.android.provision")) continue;
            out.add(p);
        }
        return out;
    }
}
