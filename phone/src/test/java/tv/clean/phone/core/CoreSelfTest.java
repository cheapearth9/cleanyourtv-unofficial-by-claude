package tv.clean.phone.core;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** java tv.clean.phone.core.CoreSelfTest <path to assets/db/models.txt> */
public final class CoreSelfTest {
    static int failures;

    /** A pretend Android 7.1 box with a stock launcher, Settings and the new launcher's room to install. */
    static FakeTv fresh(int sdk) {
        FakeTv t = new FakeTv(sdk);
        t.stock = "com.google.android.leanbacklauncher";
        t.installed.addAll(Arrays.asList(t.stock, "com.android.tv.settings"));
        return t;
    }

    static void runSh(java.io.File script, java.io.File bin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", script.getPath());
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

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

        // ---- the launcher that crashes right after `am start` (the "Launcher started." lie of an earlier version)
        FakeTv crash = fresh(25);
        crash.crashOnStart = true;
        try {
            Cleaner.installLauncher(crash, TvInfo.probe(crash), lo, log, null);
            check(false, "a launcher that crashes on start is reported");
        } catch (CleanException e) {
            check(e.getMessage().contains("NoClassDefFoundError") && e.getMessage().contains("was left enabled"), "crash on start: reported with its cause (" + e.getMessage().substring(0, Math.min(90, e.getMessage().length())) + "...)");
            check(crash.enabled(crash.stock) && crash.history.stream().noneMatch(c -> c.contains("pm disable-user")), "crash on start: stock launcher never touched");
            check(crash.armCount == 0, "crash on start: no safety timer needed yet");
            check(logs.stream().anyMatch(l -> l.contains("crash log from the TV")) && logs.stream().anyMatch(l -> l.contains("Caused by: java.lang.NoClassDefFoundError")), "crash log lines reach the run log");
            check(logs.stream().noneMatch(l -> l.contains("not ours")), "another app's crash in the same log is ignored");
        }

        // ---- a launcher that starts but dies when it becomes Home: roll back, timer off
        FakeTv late = fresh(25);
        late.crashOnHome = true;
        try {
            Cleaner.installLauncher(late, TvInfo.probe(late), lo, log, null);
            check(false, "a launcher that crashes as Home is reported");
        } catch (CleanException e) {
            check(late.enabled(late.stock) && e.getMessage().contains("switched back on"), "crash as Home: stock launcher switched back on");
            check(late.armCount == 1 && !late.watchdogArmed && late.disarmCount >= 1, "crash as Home: safety timer armed, then stopped");
            int arm = -1, disable = -1;
            for (int i = 0; i < late.history.size(); i++) {
                String c = late.history.get(i);
                if (arm < 0 && c.startsWith("rm -f " + Watchdog.DIR + "/cyt-watchdog.ok")) arm = i;
                if (disable < 0 && c.contains("pm disable-user")) disable = i;
            }
            check(arm >= 0 && disable > arm, "the safety timer is armed BEFORE the stock launcher is switched off");
        }

        // ---- two launchers answer Home: Android 7 shows its chooser
        FakeTv two = fresh(25);
        two.otherHome = "com.vendor.home";
        two.installed.add("com.vendor.home");
        Cleaner.LauncherResult tr = Cleaner.installLauncher(two, TvInfo.probe(two), lo, log, null);
        check(tr.isHome && tr.note.contains("Always"), "chooser on Home: not treated as a failure, the person is told to choose Always");
        FakeTv three = fresh(25);
        three.otherHome = "com.vendor.home";
        three.installed.add("com.vendor.home");
        Cleaner.LauncherOptions both = new Cleaner.LauncherOptions();
        both.apk = lo.apk;
        both.stock = three.stock;
        both.alsoDisable = new ArrayList<>(Arrays.asList("com.vendor.home", "com.android.tv.settings", "foo; reboot"));
        Record[] rec3 = new Record[1];
        Cleaner.LauncherResult r3 = Cleaner.installLauncher(three, TvInfo.probe(three), both, log, r -> rec3[0] = r);
        check(r3.isHome && r3.note.isEmpty() && three.disabled.contains("com.vendor.home"), "also switching off the other launcher: Home goes straight to the new one");
        check(!three.disabled.contains("com.android.tv.settings") && three.history.stream().noneMatch(c -> c.contains("reboot")), "core packages and odd names in alsoDisable are refused");
        check(rec3[0] != null && rec3[0].disabled.contains("com.vendor.home"), "the other launcher is in the undo record");

        // ---- shell without grep, and a TV that stops answering the input check
        FakeTv nogrep = fresh(24);
        nogrep.noGrep = true;
        nogrep.hwDriver = "com.mediatek.tvinput";
        nogrep.installed.addAll(Arrays.asList("com.mediatek.tvinput", "com.google.android.feedback"));
        Cleaner.DebloatResult ng = Cleaner.debloat(nogrep, TvInfo.probe(nogrep), Arrays.asList("com.mediatek.tvinput", "com.google.android.feedback"), log, null);
        check(nogrep.disabled.contains("com.google.android.feedback") && !nogrep.disabled.contains("com.mediatek.tvinput"), "no grep on the TV: falls back to the full dump, same protection");
        FakeTv slow = fresh(24);
        slow.failInputCheck = true;
        slow.installed.addAll(Arrays.asList("com.google.android.feedback"));
        Cleaner.DebloatResult sl = Cleaner.debloat(slow, TvInfo.probe(slow), Arrays.asList("com.google.android.feedback"), log, null);
        check(slow.disabled.isEmpty() && sl.kept.stream().anyMatch(k -> k.contains("did not answer")), "input check timed out: nothing is disabled on a guess");

        // ---- the crash parser ignores everything that is not our process
        LaunchCheck lc = new LaunchCheck();
        lc.readCrash("I/AndroidRuntime( 2999): >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<\nE/AndroidRuntime( 1): FATAL EXCEPTION: main\nE/AndroidRuntime( 1): Process: com.other, PID: 1\n");
        check(!lc.crashed, "another app's crash and `am`'s own START line are not our crash");

        // ---- diagnostics: crash first, and one failing part does not hide the others
        FakeTv dg = fresh(25);
        dg.installed.add("com.gathin.tvhome");
        dg.ownInstalled = true;
        dg.crashOnStart = true;
        List<String> streamed = new ArrayList<>();
        String report = Diagnostics.collect(dg, TvInfo.probe(dg), streamed::add);
        check(report.contains("FAILED: it crashed") && report.contains("NoClassDefFoundError") && report.contains("MemTotal"), "diagnostics: crash cause and memory in the report");
        check(streamed.size() == 5 && streamed.get(3).contains("crash log"), "diagnostics: five parts, streamed one by one, crash log before Home");
        dg.failInputCheck = false;
        TvShell flaky = new TvShell() {
            @Override
            public String run(String c, int t) throws java.io.IOException {
                if (c.startsWith("dumpsys package -f") || c.startsWith("dumpsys package resolvers")) throw new java.net.SocketTimeoutException("The TV said nothing for 120 s");
                return dg.run(c, t);
            }

            @Override
            public void push(byte[] d, String p2, int m) throws java.io.IOException {
                dg.push(d, p2, m);
            }
        };
        String partial = Diagnostics.collect(flaky, TvInfo.probe(dg), null);
        check(partial.contains("this part failed: The TV said nothing") && partial.contains("== Start attempt and crash log ==") && partial.contains("== Device =="), "diagnostics: a timeout in one part leaves the other parts intact");

        // ---- the Apps screen: list, protect, uninstall, bring back
        FakeTv ap = fresh(25);
        ap.installed.addAll(Arrays.asList("com.netflix.ninja", "com.vendor.bloat", "com.google.android.gms", "com.gathin.tvhome", "com.vendor.home"));
        ap.systemPkgs.addAll(Arrays.asList(ap.stock, "com.android.tv.settings", "com.vendor.bloat", "com.google.android.gms", "com.vendor.home"));
        ap.disabled.add("com.vendor.home");
        List<AppManager.App> apps = AppManager.list(ap);
        AppManager.App netflix = null, bloat = null, gms = null, settings = null, home = null;
        for (AppManager.App a : apps) {
            if (a.pkg.equals("com.netflix.ninja")) netflix = a;
            if (a.pkg.equals("com.vendor.bloat")) bloat = a;
            if (a.pkg.equals("com.google.android.gms")) gms = a;
            if (a.pkg.equals("com.android.tv.settings")) settings = a;
            if (a.pkg.equals("com.vendor.home")) home = a;
        }
        check(netflix != null && !netflix.system && bloat != null && bloat.system && home != null && home.disabled && home.system, "apps list: user vs system vs disabled (" + apps.size() + " apps)");
        java.util.Set<String> launcherSet = new java.util.LinkedHashSet<>(Arrays.asList(ap.stock));
        check(AppManager.blockedReason(netflix, launcherSet, ap.stock) == null && AppManager.blockedReason(bloat, launcherSet, ap.stock) == null, "an ordinary user app and a vendor system app may be removed");
        check(AppManager.blockedReason(settings, launcherSet, ap.stock) != null && AppManager.blockedReason(gms, launcherSet, ap.stock) != null, "core software and the Play-services family are protected");
        AppManager.App stockApp = null;
        for (AppManager.App a : apps) if (a.pkg.equals(ap.stock)) stockApp = a;
        check(AppManager.blockedReason(stockApp, launcherSet, ap.stock).contains("launcher"), "the Home launcher cannot be uninstalled from the list");
        check(AppManager.blockedReason(apps.stream().filter(a -> a.pkg.equals("com.gathin.tvhome")).findFirst().get(), launcherSet, null).contains("clean launcher"), "our own launcher points to Undo / restore");
        check(AppManager.uninstall(ap, netflix) == null && !ap.installed.contains("com.netflix.ninja") && ap.removedPkgs.isEmpty(), "user app: plain pm uninstall");
        check(AppManager.uninstall(ap, bloat) == null && ap.removedPkgs.contains("com.vendor.bloat") && ap.history.stream().anyMatch(c -> c.startsWith("pm uninstall -k --user 0 com.vendor.bloat")), "system app: removed for this user only, so it can come back");
        List<AppManager.App> after = AppManager.list(ap);
        AppManager.App gone = after.stream().filter(a -> a.pkg.equals("com.vendor.bloat")).findFirst().get();
        check(gone.removed && gone.kind().startsWith("Removed"), "a removed system app is listed as removed");
        check(AppManager.reinstall(ap, gone) == null && ap.installed.contains("com.vendor.bloat"), "removed system app is brought back");
        check(AppManager.uninstall(ap, new AppManager.App("bad; reboot") ) != null && ap.history.stream().noneMatch(c -> c.contains("reboot")), "an invalid package name never reaches the shell");
        check(AppManager.enable(ap, home) == null && ap.enabled("com.vendor.home"), "a disabled app is enabled again");

        // ---- Google Play: allowed, with a warning (it is often replaced by uninstalling and installing a newer APK)
        FakeTv play = fresh(25);
        play.installed.add("com.android.vending");
        play.systemPkgs.add("com.android.vending");
        AppManager.App vend = AppManager.list(play).stream().filter(a -> a.pkg.equals("com.android.vending")).findFirst().get();
        check(AppManager.blockedReason(vend, new java.util.LinkedHashSet<>(), null) == null && AppManager.warning(vend) != null && AppManager.warning(vend).contains("Google Play"), "Google Play may be uninstalled, with a warning");
        check(AppManager.blockedReason(AppManager.list(play).stream().filter(a -> a.pkg.equals("com.android.tv.settings")).findFirst().get(), new java.util.LinkedHashSet<>(), null) != null, "Settings is still protected");
        check(AppManager.uninstall(play, vend) == null && play.removedPkgs.contains("com.android.vending"), "Google Play uninstalls for this TV");
        AppManager.App upd = new AppManager.App("com.android.vending");
        upd.system = true;
        upd.path = "/data/app/com.android.vending-1/base.apk";
        check(upd.updatedSystem() && upd.kind().contains("updated") && !vend.updatedSystem(), "an updated system app is recognised by its APK path");

        // ---- install an APK from the phone
        java.io.File apkFile = java.io.File.createTempFile("good", ".apk");
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(apkFile))) {
            zo.putNextEntry(new java.util.zip.ZipEntry("AndroidManifest.xml"));
            zo.write(new byte[200]);
            zo.closeEntry();
        }
        java.io.File bundle = java.io.File.createTempFile("bundle", ".xapk");
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(bundle))) {
            zo.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zo.write(new byte[10]);
            zo.closeEntry();
        }
        List<String> inst = new ArrayList<>();
        ApkInstaller.install(ap, TvInfo.probe(ap), apkFile, "good.apk", inst::add);
        check(inst.stream().anyMatch(l -> l.equals("Installed.")) && inst.stream().anyMatch(l -> l.contains("100%")) && ap.history.stream().anyMatch(c -> c.startsWith("pm install -r -g /data/local/tmp/cyt-install.apk")), "APK install: sent with progress, installed");
        check(!ap.files.containsKey("/data/local/tmp/cyt-install.apk"), "APK install: temporary file removed from the TV");
        try {
            ApkInstaller.install(ap, TvInfo.probe(ap), bundle, "game.xapk", inst::add);
            check(false, "a bundle is refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("not a plain APK"), "an .xapk bundle is refused with an explanation");
        }
        ap.installResult = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match]\n";
        try {
            ApkInstaller.install(ap, TvInfo.probe(ap), apkFile, "good.apk", inst::add);
            check(false, "incompatible signature reported");
        } catch (CleanException e) {
            check(e.getMessage().contains("signed by someone else"), "signature clash: tells the person to uninstall the old copy first");
        }

        // ---- hide / show the on-screen bars
        FakeTv bars = fresh(25);
        TvInfo bi = TvInfo.probe(bars);
        Record[] brec = new Record[1];
        check(Cleaner.applyBars(bars, bi, true, log, r -> brec[0] = r) && SystemBars.HIDE_VALUE.equals(bars.policyControl), "bars: hidden with the immersive policy");
        check(brec[0] != null && "null".equals(brec[0].barsBefore) && bars.files.get(Cleaner.RECORD_PATH) != null, "bars: 'it was unset' is in the undo record");
        check(!Cleaner.applyBars(bars, bi, true, log, null), "bars: hiding twice changes nothing");
        check(Cleaner.applyBars(bars, bi, false, log, r -> brec[0] = r) && bars.policyControl == null && !bars.files.containsKey(Cleaner.RECORD_PATH), "bars: shown again = unset as before, empty record removed");

        FakeTv bars2 = fresh(25);
        bars2.policyControl = "immersive.status=*";
        Cleaner.applyBars(bars2, TvInfo.probe(bars2), true, log, null);
        check(Record.parse(new String(bars2.files.get(Cleaner.RECORD_PATH), "UTF-8")).barsBefore.equals("immersive.status=*"), "bars: the earlier setting is saved in the record");
        Cleaner.applyBars(bars2, TvInfo.probe(bars2), false, log, null);
        check("immersive.status=*".equals(bars2.policyControl), "bars: shown again = the earlier setting is back, whatever it was");

        FakeTv bars3 = fresh(25);
        Cleaner.applyBars(bars3, TvInfo.probe(bars3), true, log, null);
        bars3.policyControl = "immersive.navigation=*";      // something else changed it meanwhile
        check(!Cleaner.applyBars(bars3, TvInfo.probe(bars3), false, log, null) && "immersive.navigation=*".equals(bars3.policyControl), "bars: a value somebody else set is left alone");

        FakeTv bars4 = fresh(25);
        bars4.policyControl = "x; reboot";
        try {
            Cleaner.applyBars(bars4, TvInfo.probe(bars4), true, log, null);
            check(false, "unusual value refused");
        } catch (CleanException e) {
            check(bars4.history.stream().noneMatch(c -> c.contains("reboot") && c.startsWith("settings put")), "bars: an unusual existing value is not overwritten or echoed into a shell");
        }
        try {
            Cleaner.applyBars(fresh(34), TvInfo.probe(fresh(34)), true, log, null);
            check(false, "Android 14 refused");
        } catch (CleanException e) {
            check(e.getMessage().contains("ignores"), "bars: Android 11+ says it cannot");
        }
        check(Record.parse("cleanyourtv-record 1\nbars_before=a; reboot\n").barsBefore == null && Record.parse("cleanyourtv-record 1\nbars_before=immersive.full=*\n").barsBefore.equals("immersive.full=*"), "bars: a tampered record value is dropped");
        FakeTv bars5 = fresh(25);
        Cleaner.applyBars(bars5, TvInfo.probe(bars5), true, log, null);
        Cleaner.restore(bars5, TvInfo.probe(bars5), Cleaner.readTvRecord(bars5), false, log);
        check(bars5.policyControl == null, "restore also brings the bars back");

        // ---- the safety timer script, run for real on this machine's /bin/sh with fake pm/cmd/am
        java.io.File dir = java.nio.file.Files.createTempDirectory("wd").toFile();
        java.io.File bin = new java.io.File(dir, "bin");
        bin.mkdirs();
        java.io.File calls = new java.io.File(dir, "calls.txt");
        for (String tool : new String[] {"pm", "cmd", "am"}) {
            java.io.File f = new java.io.File(bin, tool);
            java.nio.file.Files.write(f.toPath(), ("#!/bin/sh\necho \"" + tool + " $*\" >> " + calls.getPath() + "\n").getBytes("UTF-8"));
            f.setExecutable(true);
        }
        String script = Watchdog.script(dir.getPath(), Arrays.asList("com.stock.launcher", "com.vendor.home"), "com.stock.launcher/.Main", 26, 2);
        java.io.File sf = new java.io.File(dir, "cyt-watchdog.sh");
        java.nio.file.Files.write(sf.toPath(), script.getBytes("UTF-8"));
        // confirmed in time: nothing is touched
        java.nio.file.Files.write(new java.io.File(dir, "cyt-watchdog.ok").toPath(), "ok\n".getBytes("UTF-8"));
        runSh(sf, bin);
        check(!calls.exists() && !sf.exists(), "safety timer: confirmed in time -> does nothing and removes itself");
        // never confirmed: puts everything back
        java.nio.file.Files.write(sf.toPath(), script.getBytes("UTF-8"));
        runSh(sf, bin);
        String made = calls.exists() ? new String(java.nio.file.Files.readAllBytes(calls.toPath()), "UTF-8") : "";
        check(made.contains("pm enable --user 0 com.stock.launcher") && made.contains("pm enable --user 0 com.vendor.home")
            && made.contains("cmd package set-home-activity --user 0 com.stock.launcher/.Main") && made.contains("am start -a android.intent.action.MAIN -c android.intent.category.HOME"),
            "safety timer: never confirmed -> old launchers back on, Home pressed");
        boolean refused = false;
        try {
            Watchdog.script(dir.getPath(), Arrays.asList("a.b; reboot"), null, 24, 60);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "safety timer: a package name that is not a package name is refused");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
