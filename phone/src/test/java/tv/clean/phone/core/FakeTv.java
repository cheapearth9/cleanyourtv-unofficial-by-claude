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

    // ---- behaviours for the harder tests
    boolean crashOnStart;        // our launcher dies right after `am start -n`
    boolean crashOnHome;         // our launcher starts fine but dies when it becomes Home
    String otherHome;            // another enabled launcher: Android then shows the chooser on a Home press
    boolean noGrep;              // a shell without grep
    boolean failInputCheck;      // the TV stops answering the batched dumpsys package calls
    String installResult;
    String policyControl;        // settings global policy_control (null = unset)
    boolean watchdogArmed;
    int armCount, disarmCount;
    boolean crashLogged;
    final Set<String> systemPkgs = new LinkedHashSet<>();
    final Set<String> removedPkgs = new LinkedHashSet<>();

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
        } else if (noGrep && c.contains("| grep -E")) {
            out.append("/system/bin/sh: grep: not found\n");
        } else if (c.startsWith("dumpsys package -f resolvers") || c.startsWith("dumpsys package resolvers")) {
            out.append(resolverTable());
        } else if (c.startsWith("dumpsys wifi")) {
            out.append("WifiConfiguration{ cname=").append(wifiOwner).append(" }\n");
        } else if (c.startsWith("for p in") && c.contains("dumpsys package $p")) {
            if (failInputCheck) throw new java.net.SocketTimeoutException("The TV said nothing for 90 s");
            Matcher m = Pattern.compile("^for p in (.*?); do").matcher(c);
            m.find();
            for (String p : m.group(1).trim().split(" ")) {
                out.append("@@PKG ").append(p).append('\n');
                out.append("Packages:\n  Package [").append(p).append("] (1):\n    userId=10056\n    requested permissions:\n      android.permission.INTERNET\n");
                if (p.equals(hwDriver)) out.append("      android.permission.TV_INPUT_HARDWARE\n");
                out.append("    install permissions:\n      android.permission.INTERNET: granted=true\n");
            }
        } else if (c.startsWith("cat " + Cleaner.RECORD_PATH)) {
            byte[] b = files.get(Cleaner.RECORD_PATH);
            if (b != null) out.append(new String(b, "UTF-8"));
        } else if (c.startsWith("rm -f " + Cleaner.RECORD_PATH)) {
            files.remove(Cleaner.RECORD_PATH);
        } else if (c.startsWith("echo @@ALL")) {
            out.append("@@ALL\n");
            for (String p : installed) out.append("package:").append(systemPkgs.contains(p) ? "/system/app/" + p + "/base.apk" : "/data/app/" + p + "-1/base.apk").append('=').append(p).append('\n');
            out.append("@@SYS\n");
            for (String p : installed) if (systemPkgs.contains(p)) out.append("package:").append(p).append('\n');
            out.append("@@DIS\n");
            for (String p : disabled) out.append("package:").append(p).append('\n');
            out.append("@@UNI\n");
            for (String p : installed) out.append("package:").append(p).append('\n');
            for (String p : removedPkgs) out.append("package:").append(p).append('\n');
        } else if (c.startsWith("pm install-existing")) {
            String p = c.replaceAll(" 2>&1.*", "").replaceAll(".* ", "");
            if (removedPkgs.remove(p)) { installed.add(p); out.append("Package ").append(p).append(" installed for user: 0\n"); }
            else out.append("Error: package not found\n");
        } else if (c.contains("cyt-install.apk")) {
            if (c.startsWith("rm -f")) { files.remove("/data/local/tmp/cyt-install.apk"); }
            else if (!files.containsKey("/data/local/tmp/cyt-install.apk")) out.append("Failure [INSTALL_FAILED_INVALID_URI]\n");
            else out.append(installResult != null ? installResult : "Success\n");
        } else if (c.contains("pm install -r")) {
            if (c.contains("pm uninstall")) ownInstalled = false;
            if (!files.containsKey("/data/local/tmp/tvhome.apk")) out.append("Failure [INSTALL_FAILED_INVALID_URI]");
            else { ownInstalled = true; installed.add("com.gathin.tvhome"); out.append("pkg: /data/local/tmp/tvhome.apk\nSuccess\n"); }
            files.remove("/data/local/tmp/tvhome.apk");
        } else if (c.startsWith("s=$(settings get secure enabled_accessibility_services)")) {
            // force-stop and restore services: nothing to model
        } else if (c.startsWith("head -n 4 /proc/meminfo")) {
            out.append("MemTotal:        1011456 kB\nMemFree:           41200 kB\n");
        } else if (c.startsWith("am force-stop")) {
            resumed = null;
        } else if (c.startsWith("dumpsys package com.gathin.tvhome")) {
            if (ownInstalled) out.append("    versionName=1.0\n    targetSdk=33\n");
        } else if (c.startsWith("logcat -d -v brief '*:W'")) {
            if (crashLogged) out.append("E/AndroidRuntime( 3105): Process: com.gathin.tvhome, PID: 3105\n");
        } else if (c.startsWith("settings get global policy_control")) {
            out.append(policyControl == null ? "null" : policyControl).append('\n');
        } else if (c.startsWith("settings put global policy_control")) {
            Matcher m = Pattern.compile("policy_control '?([^' ]*)'?").matcher(c);
            if (m.find()) policyControl = m.group(1).equals("null") ? null : m.group(1);
        } else if (c.startsWith("settings delete global policy_control")) {
            policyControl = null;
            out.append("Deleted 1 rows\n");
        } else if (c.startsWith("logcat -c")) {
            crashLogged = false;
        } else if (c.startsWith("sleep 3; logcat -d")) {
            if (crashLogged) out.append(crashText());
            out.append("I/ActivityManager(  612): Start proc 3105:com.some.other/u0a9 for service\n");
        } else if (c.startsWith("ps ")) {
            if (ownInstalled && resumed != null && resumed.equals("com.gathin.tvhome")) out.append("u0_a58    3105  612   com.gathin.tvhome\n");
        } else if (c.startsWith("rm -f " + Watchdog.DIR + "/cyt-watchdog.ok")) {
            watchdogArmed = true;
            armCount++;
            out.append("CYT_ARMED\n");
        } else if (c.startsWith("echo ok > " + Watchdog.DIR + "/cyt-watchdog.ok")) {
            watchdogArmed = false;
            disarmCount++;
        } else if (c.startsWith("cmd package compile") || c.startsWith("pm grant")) {
            // slow and optional
        } else if (c.startsWith("am start -n com.gathin.tvhome")) {
            if (refuseOwnStart) out.append("Starting: Intent\nError type 3\nError: Activity class does not exist.\n");
            else if (crashOnStart) {
                crashLogged = true;
                resumed = stock != null && enabled(stock) ? stock : null;
            } else {
                out.append("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n");
                resumed = "com.gathin.tvhome";
            }
        } else if (c.startsWith("am start -a android.intent.action.MAIN")) {
            java.util.List<String> cands = new ArrayList<>();
            if (stock != null && enabled(stock)) cands.add(stock);
            if (ownInstalled && enabled("com.gathin.tvhome")) cands.add("com.gathin.tvhome");
            if (otherHome != null && enabled(otherHome)) cands.add(otherHome);
            if (cands.size() > 1) resumed = "android";
            else if (cands.isEmpty()) resumed = null;
            else {
                resumed = cands.get(0);
                if (resumed.equals("com.gathin.tvhome")) {
                    ownIsHome = true;
                    if (crashOnHome) { crashLogged = true; resumed = null; }
                }
            }
        } else if (c.startsWith("dumpsys activity activities")) {
            if (resumed != null) out.append("  mResumedActivity: ActivityRecord{3a1b2c u0 ").append(resumed).append("/.MainActivity t12}\n");
        } else if (c.startsWith("dumpsys activity")) {
            String h = homePkg();
            if (h != null) out.append("  mHomeProcess: ProcessRecord{52f4a5c8 2314:").append(h).append("/u0a56}\n");
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
            String p = c.replaceAll(" 2>&1.*", "").replaceAll(".* ", "");
            if (c.startsWith("pm uninstall -k") && installed.contains(p)) removedPkgs.add(p);
            installed.remove(p);
            if (p.equals("com.gathin.tvhome")) { ownInstalled = false; ownIsHome = false; }
            out.append(installed.contains(p) || (!c.startsWith("pm uninstall -k") && false) ? "Failure\n" : "Success\n");
        } else if (c.startsWith("mkdir -p") || c.startsWith("cmd appops")) {
            // ok
        } else if (c.startsWith("cmd package set-home-activity")) {
            ownIsHome = c.contains("com.gathin.tvhome");
        } else {
            out.append("fake tv: unhandled command: ").append(c).append('\n');
        }
        return out.toString();
    }

    private String crashText() {
        return "I/AndroidRuntime( 2999): >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<\n"
            + "E/AndroidRuntime( 2870): FATAL EXCEPTION: main\n"
            + "E/AndroidRuntime( 2870): Process: com.some.other, PID: 2870\n"
            + "E/AndroidRuntime( 2870): java.lang.IllegalStateException: not ours\n"
            + "E/AndroidRuntime( 3105): FATAL EXCEPTION: main\n"
            + "E/AndroidRuntime( 3105): Process: com.gathin.tvhome, PID: 3105\n"
            + "E/AndroidRuntime( 3105): java.lang.RuntimeException: Unable to start activity ComponentInfo{com.gathin.tvhome/com.gathin.tvhome.MainActivity}: java.lang.NoClassDefFoundError: Failed resolution of: Ljava/time/LocalDate;\n"
            + "E/AndroidRuntime( 3105):     at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2416)\n"
            + "E/AndroidRuntime( 3105): Caused by: java.lang.NoClassDefFoundError: Failed resolution of: Ljava/time/LocalDate;\n"
            + "E/AndroidRuntime( 3105):     at com.gathin.tvhome.MainActivity.onCreate(MainActivity.java:88)\n";
    }

    private String resolverTable() {
        StringBuilder t = new StringBuilder("Activity Resolver Table:\n  Non-Data Actions:\n      android.intent.action.MAIN:\n");
        int n = 0;
        List<String> launchers = new ArrayList<>(Arrays.asList(stock, ownInstalled ? "com.gathin.tvhome" : null, otherHome));
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
