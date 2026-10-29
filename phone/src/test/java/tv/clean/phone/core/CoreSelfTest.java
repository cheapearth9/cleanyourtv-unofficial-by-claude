package tv.clean.phone.core;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** java tv.clean.phone.core.CoreSelfTest <path to assets/db/models.txt> */
public final class CoreSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Db db;
        try (FileInputStream in = new FileInputStream(args[0])) {
            db = Db.parse(in);
        }
        check(db.models.size() > 300, "database loaded: " + db.models.size() + " TV lists");

        // --- guards
        check(Guards.isCore("com.android.systemui") && Guards.isCore("com.android.providers.media") && Guards.isCore("android"), "core packages recognised");
        check(!Guards.isCore("com.android.providers.calendar") && !Guards.isCore("com.tcl.bi"), "calendar provider and vendor apps are not core");
        check(!Guards.isValidName("foo; reboot") && !Guards.isValidName("a b.c") && Guards.isValidName("com.tcl.bi"), "package names are validated before they reach a shell");

        // --- resolver table parsing (Android 7 format, same filter listed under two indexes)
        String dump = "Activity Resolver Table:\n  Non-Data Actions:\n      android.intent.action.MAIN:\n"
            + "        3e8d3b6 com.x.launcher/.Main filter 1b0c0e1\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n"
            + "  Schemes:\n      http:\n        3e8d3b6 com.x.launcher/.Main filter 1b0c0e1\n          Action: \"android.intent.action.MAIN\"\n"
            + "        aa00001 com.x.launcher2/.FallbackHome filter 2\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n"
            + "Service Resolver Table:\n  Non-Data Actions:\n      x:\n        bb com.y.svc/.S filter 3\n          Action: \"android.intent.action.MAIN\"\n          Category: \"android.intent.category.HOME\"\n";
        ResolverTable rt = ResolverTable.parse(dump);
        check(rt.filters().size() == 2, "same filter under two indexes is one filter, other sections ignored (" + rt.filters().size() + ")");
        check(rt.launcherPackages().equals(new java.util.LinkedHashSet<>(Arrays.asList("com.x.launcher"))), "FallbackHome is not a launcher: " + rt.launcherPackages());

        // --- input-driver check, three dumpsys shapes
        List<String[]> pr = Cleaner.parseInputCheck(
            "@@PKG a.b.hw\n    requested permissions:\n      android.permission.TV_INPUT_HARDWARE\n    install permissions:\n"
            + "@@PKG a.b.ok\n    requested permissions:\n      android.permission.INTERNET\n    install permissions:\n      android.permission.TV_INPUT_HARDWARE: granted=true\n"
            + "@@PKG a.b.lolli\n    grantedPermissions:\n      android.permission.TV_INPUT_HARDWARE\n  User 0: x\n"
            + "@@PKG a.b.svc\nService Resolver Table:\n  android.media.tv.TvInputService:\n"
            + "@@PKG a.b.none\nUnable to find package: a.b.none\n@@PKG a.b.odd\nnothing useful\n");
        StringBuilder got = new StringBuilder();
        for (String[] r : pr) got.append(r[0]).append('=').append(r[1]).append(' ');
        check(got.toString().trim().equals("a.b.hw=HW a.b.ok=OK a.b.lolli=HW a.b.svc=HW a.b.none=OK a.b.odd=NOCHK"), "input-driver check: " + got);

        // --- matching
        TvInfo aiwa = new TvInfo();
        aiwa.model = "AI PONT SA"; aiwa.device = "osaka"; aiwa.brand = "AIWA";
        List<Db.Match> m = db.match(aiwa);
        check(!m.isEmpty() && m.get(0).model.dir.equals("aiwa-ai-pont-sa") && m.get(0).strong(), "AIWA AI PONT SA matches its own list (top: " + (m.isEmpty() ? "-" : m.get(0).model.dir + " score " + m.get(0).score) + ")");
        TvInfo box = new TvInfo();
        box.model = "MXQ Pro 4K"; box.device = "rk3229"; box.brand = "generic"; box.product = "rk322x";
        check(db.match(box).stream().noneMatch(Db.Match::strong), "an unlisted generic box gets no strong match");

        // --- full flow on a pretend Android 7.0 box that is in no list
        FakeTv tv = new FakeTv(24);
        tv.stock = "com.google.android.leanbacklauncher";
        tv.hwDriver = "com.mediatek.tvinput";
        tv.channelApp = "com.vendor.livetv";
        String[] pkgs = {"com.google.android.leanbacklauncher", "com.android.tv.settings", "com.android.systemui", "com.google.android.feedback",
            "com.google.android.partnersetup", "com.google.android.tvrecommendations", "com.google.android.katniss", "com.mediatek.tvinput",
            "com.vendor.livetv", "com.google.android.gms", "com.some.userapp", "com.google.android.videos", "com.google.android.tungsten.setupwraith"};
        tv.installed.addAll(Arrays.asList(pkgs));
        TvInfo info = TvInfo.probe(tv);
        check(info.sdk == 24 && info.model.equals("MXQ Pro 4K") && info.enabled.size() == pkgs.length, "probe reads model/SDK/packages");
        Planner.Plan plan = Planner.plan(db, info);
        java.util.Map<String, Planner.Item> byPkg = new java.util.HashMap<>();
        for (Planner.Item i : plan.items) byPkg.put(i.pkg, i);
        check(byPkg.containsKey("com.google.android.feedback") && byPkg.get("com.google.android.feedback").checked, "cross-TV ad/telemetry package is pre-ticked");
        check(byPkg.containsKey("com.google.android.katniss") && !byPkg.get("com.google.android.katniss").checked, "voice search is offered but not pre-ticked");
        check(!byPkg.containsKey("com.google.android.gms") && !byPkg.containsKey("com.android.systemui") && !byPkg.containsKey("com.some.userapp"), "gms, systemui and unknown user apps are never offered");

        List<String> all = new ArrayList<>(Arrays.asList(pkgs));   // worst case: the person ticks everything
        List<String> logs = new ArrayList<>();
        Log log = logs::add;
        Record[] saved = new Record[1];
        Cleaner.DebloatResult dr = Cleaner.debloat(tv, info, all, log, r -> saved[0] = r);
        String keptText = String.join(" | ", dr.kept);
        check(!tv.disabled.contains("com.google.android.leanbacklauncher"), "stock launcher is not disabled by the list");
        check(!tv.disabled.contains("com.android.tv.settings") && !tv.disabled.contains("com.android.systemui"), "settings and systemui survive");
        check(!tv.disabled.contains("com.mediatek.tvinput"), "TV_INPUT_HARDWARE driver survives");
        check(!tv.disabled.contains("com.vendor.livetv"), "the app that answers channel links survives");
        check(!tv.disabled.contains("com.google.android.tungsten.setupwraith") || !tv.wifiOwner.contains("setupwraith"), "setupwraith handled by the Wi-Fi rule");
        check(tv.disabled.contains("com.google.android.feedback") && tv.disabled.contains("com.some.userapp"), "everything else that was ticked is disabled");
        check(dr.verified && dr.stillEnabled.isEmpty(), "read-back confirms the changes");
        check(saved[0] != null && saved[0].disabled.containsAll(dr.disabled) && !saved[0].disabled.contains("com.mediatek.tvinput"), "undo record lists exactly what was disabled");
        check(tv.files.containsKey(Cleaner.RECORD_PATH), "undo record is also on the TV");
        System.out.println("      kept: " + keptText);

        // launcher hand-over on API 24 (no cmd package set-home-activity there)
        Cleaner.LauncherOptions lo = new Cleaner.LauncherOptions();
        lo.apk = new byte[400_000];
        lo.stock = "com.google.android.leanbacklauncher";
        info = TvInfo.probe(tv);
        Cleaner.LauncherResult lr = Cleaner.installLauncher(tv, info, lo, log, r -> saved[0] = r);
        check(lr.isHome && tv.homePkg().equals("com.gathin.tvhome"), "API 24: new launcher owns Home after the hand-over");
        check(tv.disabled.contains("com.google.android.leanbacklauncher"), "stock launcher disabled only after the new one started");
        check(saved[0].launcherInstalled && saved[0].stock.equals("com.google.android.leanbacklauncher"), "record knows the stock launcher");
        check(tv.history.stream().noneMatch(c -> c.contains("set-home-activity")), "no Android 8-only command was sent to an API 24 TV");
        check(tv.history.stream().noneMatch(c -> c.contains("query-activities") || c.contains("sed -n -E") || c.contains("grep -q")), "no command that Android 7's shell lacks");

        // restore
        Record rec = Cleaner.readTvRecord(tv);
        Cleaner.RestoreResult rr = Cleaner.restore(tv, TvInfo.probe(tv), rec, true, log);
        check(tv.disabled.isEmpty() && rr.failed.isEmpty(), "restore re-enables every package");
        check(rr.launcherRemoved && !tv.ownInstalled && tv.homePkg().equals("com.google.android.leanbacklauncher"), "restore removes our launcher; stock launcher is Home again");
        check(!tv.files.containsKey(Cleaner.RECORD_PATH), "record is cleaned up");

        // the same hand-over on an Android 14 TV uses set-home-activity
        FakeTv modern = new FakeTv(34);
        modern.stock = "com.google.android.apps.tv.launcherx";
        modern.installed.addAll(Arrays.asList(modern.stock, "com.android.tv.settings"));
        Cleaner.LauncherOptions mo = new Cleaner.LauncherOptions();
        mo.apk = new byte[400_000];
        mo.stock = modern.stock;
        Cleaner.LauncherResult mr = Cleaner.installLauncher(modern, TvInfo.probe(modern), mo, log, null);
        check(mr.isHome && modern.history.stream().anyMatch(c -> c.startsWith("cmd package set-home-activity")), "API 34: hand-over sets the Home activity and verifies it");

        // launcher that will not start must leave the stock launcher alone
        FakeTv bad = new FakeTv(25);
        bad.stock = "com.google.android.leanbacklauncher";
        bad.installed.addAll(Arrays.asList(bad.stock, "com.android.tv.settings"));
        bad.refuseOwnStart = true;
        try {
            Cleaner.installLauncher(bad, TvInfo.probe(bad), lo, log, null);
            check(false, "a launcher that cannot start is reported");
        } catch (CleanException e) {
            check(bad.enabled(bad.stock), "launcher fails to start -> stock launcher untouched (" + e.getMessage().substring(0, 40) + "...)");
        }

        // TV too old for the launcher
        FakeTv old = new FakeTv(22);
        try {
            Cleaner.installLauncher(old, TvInfo.probe(old), lo, log, null);
            check(false, "API 22 refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("API 22"), "API 22 TV: launcher refused with a clear message");
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
