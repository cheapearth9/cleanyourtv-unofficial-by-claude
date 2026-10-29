package tv.clean.phone.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A pretend Android TV: answers the commands Cleaner sends, in the formats Android 7 / 14 print them. */
final class FakeTv implements TvShell {
    final int sdk;
    final Set<String> installed = new LinkedHashSet<>();
    final Set<String> disabled = new LinkedHashSet<>();
    String stock;                 // the stock launcher package
    String wifiOwner = "android.uid.system";
    String hwDriver;              // a package that asks for TV_INPUT_HARDWARE
    String channelApp;            // answers VIEW vnd.android.cursor.item/channel
    String settingsApp = "com.android.tv.settings";
    boolean ownInstalled;
    boolean refuseOwnStart;
    String resumed;
    final Map<String, byte[]> files = new TreeMap<>();
    final List<String> history = new ArrayList<>();

    FakeTv(int sdk) {
        this.sdk = sdk;
    }

    boolean enabled(String p) {
        return installed.contains(p) && !disabled.contains(p);
    }

    String homePkg() {
        if (ownInstalled && enabled("com.gathin.tvhome") && (stock == null || !enabled(stock) || ownIsHome)) return "com.gathin.tvhome";
        return stock != null && enabled(stock) ? stock : null;
    }

    boolean ownIsHome;

    @Override
    public synchronized String run(String c, int timeoutMs) throws IOException {
        history.add(c);
        StringBuilder out = new StringBuilder();
        if (c.startsWith("for k in ro.product.model")) {
            out.append("ro.product.model=MXQ Pro 4K\nro.product.brand=generic\nro.product.manufacturer=Shenzhen\nro.product.device=rk3229\n")
               .append("ro.product.name=rk322x\nro.build.version.sdk=").append(sdk).append("\nro.build.version.release=")
               .append(sdk == 24 ? "7.0" : "7.1.2").append("\nro.build.fingerprint=x\nro.adb.secure=1\nleanback=1\n");
        } else if (c.startsWith("pm list packages -e")) {
            for (String p : installed) if (enabled(p)) out.append("package:").append(p).append('\n');
        } else if (c.startsWith("pm list packages -d")) {
            for (String p : disabled) out.append("package:").append(p).append('\n');
        } else if (c.startsWith("dumpsys package resolvers")) {
            out.append(resolverTable());
        } else if (c.equals("dumpsys wifi")) {
            out.append("WifiConfiguration{ cname=").append(wifiOwner).append(" }\n");
        } else if (c.startsWith("for p in") && c.contains("dumpsys package $p")) {
            Matcher m = Pattern.compile("^for p in (.*?); do").matcher(c);
            m.find();
            for (String p : m.group(1).trim().split(" ")) {
                out.append("@@PKG ").append(p).append('\n');
                out.append("Packages:\n  Package [").append(p).append("] (1):\n    requested permissions:\n      android.permission.INTERNET\n");
                if (p.equals(hwDriver)) out.append("      android.permission.TV_INPUT_HARDWARE\n");
                out.append("    install permissions:\n      android.permission.INTERNET: granted=true\n");
            }
        } else if (c.startsWith("cat " + Cleaner.RECORD_PATH)) {
            byte[] b = files.get(Cleaner.RECORD_PATH);
            if (b != null) out.append(new String(b, "UTF-8"));
        } else if (c.startsWith("rm -f " + Cleaner.RECORD_PATH)) {
            files.remove(Cleaner.RECORD_PATH);
        } else if (c.contains("pm install -r")) {
            if (c.contains("pm uninstall")) ownInstalled = false;
            if (!files.containsKey("/data/local/tmp/tvhome.apk")) out.append("Failure [INSTALL_FAILED_INVALID_URI]");
            else { ownInstalled = true; installed.add("com.gathin.tvhome"); out.append("pkg: /data/local/tmp/tvhome.apk\nSuccess\n"); }
            files.remove("/data/local/tmp/tvhome.apk");
        } else if (c.startsWith("s=$(settings get secure enabled_accessibility_services)")) {
            // force-stop and restore services: nothing to model
        } else if (c.startsWith("am start -W")) {
            if (refuseOwnStart) out.append("Starting: Intent\nError type 3\nError: Activity class does not exist.\n");
            else {
                out.append("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\nStatus: ok\nActivity: com.gathin.tvhome/.MainActivity\n");
                resumed = "com.gathin.tvhome";
                if (c.contains("category.HOME")) ownIsHome = true;
            }
        } else if (c.startsWith("am start -a android.intent.action.MAIN")) {
            resumed = homePkg();
        } else if (c.startsWith("dumpsys activity activities")) {
            if (resumed != null) out.append("  mResumedActivity: ActivityRecord{3a1b2c u0 ").append(resumed).append("/.MainActivity t12}\n");
        } else if (c.startsWith("dumpsys activity")) {
            String h = homePkg();
            if (h != null) out.append("  mHomeProcess: ProcessRecord{52f4a5c8 2f4b:").append(h).append("/u0a56}\n");
        } else if (c.startsWith("cmd package resolve-activity")) {
            if (sdk >= 26 && homePkg() != null) out.append("priority=0 preferredOrder=0\n").append(homePkg()).append("/.MainActivity\n");
            else out.append("Unknown command: resolve-activity\n");
        } else if (c.contains("pm disable-user") || c.contains("pm enable")) {
            Matcher m = Pattern.compile("pm (disable-user|enable) --user 0 ([A-Za-z0-9_.]+)").matcher(c);
            while (m.find()) {
                String p = m.group(2);
                if (!installed.contains(p)) { out.append("Error: package not found ").append(p).append('\n'); continue; }
                if (m.group(1).equals("enable")) disabled.remove(p); else disabled.add(p);
                if (p.equals("com.gathin.tvhome") && ownIsHome && disabled.contains(p)) ownIsHome = false;
                out.append("Package ").append(p).append(" new state: ").append(m.group(1).equals("enable") ? "enabled" : "disabled-user").append('\n');
            }
            if (c.contains("pm disable-user") && stock != null && disabled.contains(stock)) { ownIsHome = ownInstalled; }
        } else if (c.startsWith("pm uninstall")) {
            ownInstalled = false; installed.remove("com.gathin.tvhome"); ownIsHome = false; out.append("Success\n");
        } else if (c.startsWith("pm grant") || c.startsWith("mkdir -p") || c.startsWith("cmd appops")) {
            // ok
        } else if (c.startsWith("cmd package set-home-activity")) {
            ownIsHome = c.contains("com.gathin.tvhome");
        } else {
            out.append("fake tv: unhandled command: ").append(c).append('\n');
        }
        return out.toString();
    }

    private String resolverTable() {
        StringBuilder t = new StringBuilder("Activity Resolver Table:\n  Non-Data Actions:\n      android.intent.action.MAIN:\n");
        int n = 0;
        List<String> launchers = new ArrayList<>(Arrays.asList(stock, ownInstalled ? "com.gathin.tvhome" : null));
        for (String p : launchers) {
            if (p == null || !enabled(p)) continue;
            t.append(String.format("        %07x %s/.MainActivity filter %07x\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n          Category: \"android.intent.category.DEFAULT\"\n", 0x3e8d3b6 + n, p, 0x1b0c0e1 + n));
            n++;
        }
        t.append(String.format("        %07x com.android.tv.settings/.FallbackHome filter 99a\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n", 0x1111));
        if (settingsApp != null && enabled(settingsApp)) {
            t.append("      android.settings.SETTINGS:\n        ab12cd ").append(settingsApp).append("/.MainSettings filter 77f\n          Action: \"android.settings.SETTINGS\"\n");
        }
        t.append("  Full MIME Types:\n      vnd.android.cursor.item/channel:\n");
        if (channelApp != null && enabled(channelApp)) {
            t.append("        cd34ef ").append(channelApp).append("/.ChannelView filter 88a\n          Action: \"android.intent.action.VIEW\"\n          Category: \"android.intent.category.DEFAULT\"\n          Type: \"vnd.android.cursor.item/channel\"\n");
        }
        t.append("\nReceiver Resolver Table:\n  Non-Data Actions:\n      android.intent.action.BOOT_COMPLETED:\n        ef56ab com.ad.bloat/.BootReceiver filter 99b\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n");
        return t.toString();
    }

    @Override
    public synchronized void push(byte[] data, String remotePath, int mode) throws IOException {
        files.put(remotePath, data);
    }
}
