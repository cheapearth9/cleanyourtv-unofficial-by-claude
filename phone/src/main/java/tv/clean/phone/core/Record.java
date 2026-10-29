package tv.clean.phone.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What was changed on a TV, so it can be put back. Saved on the TV itself (so any phone can undo it) and on the
 * phone. Everything here was enabled before this app touched it: already-disabled packages are never listed.
 */
public final class Record {
    public String stock = "";       // stock launcher package this app disabled ("" if none)
    public String home = "";        // Home component before the change, e.g. com.google.android.tvlauncher/.MainActivity
    public boolean launcherInstalled;
    public final List<String> disabled = new ArrayList<>();
    public String savedAt = "";

    public String serialize() {
        StringBuilder sb = new StringBuilder("cleanyourtv-record 1\n");
        sb.append("saved=").append(savedAt).append('\n');
        sb.append("stock=").append(stock).append('\n');
        sb.append("home=").append(home).append('\n');
        sb.append("launcher=").append(launcherInstalled ? "1" : "0").append('\n');
        for (String p : disabled) sb.append("disabled=").append(p).append('\n');
        return sb.toString();
    }

    /** Returns null when the text is not a record. Lines with bad package names are dropped. */
    public static Record parse(String text) {
        if (text == null || !text.startsWith("cleanyourtv-record")) return null;
        Record r = new Record();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "saved": r.savedAt = v; break;
                case "stock": if (v.isEmpty() || Guards.isValidName(v)) r.stock = v; break;
                case "home": if (v.isEmpty() || v.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) r.home = v; break;
                case "launcher": r.launcherInstalled = v.equals("1"); break;
                case "disabled": if (Guards.isValidName(v) && !r.disabled.contains(v)) r.disabled.add(v); break;
                default: break;
            }
        }
        return r;
    }
}
