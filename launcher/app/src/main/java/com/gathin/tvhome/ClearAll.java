package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/**
 * The "Clear all" pill: asks Android to end the background processes of every app that has a launcher entry, the same
 * thing the system does on its own under memory pressure. An app that is showing, playing or running a foreground
 * service (a video, a music player, a mirroring session) is not a background process and is left alone, and so are
 * this launcher, the Home apps and the keyboard in use.
 */
final class ClearAll {
    private ClearAll() {}

    /** Result of a run, delivered on the main thread. */
    interface Done {
        void onDone(int apps, long freeBytes, long freedBytes);
    }

    /** Packages whose background processes are cleared. Runs on any thread. */
    static Set<String> targets(PackageManager pm, String self, String inputMethod) {
        Set<String> skip = new HashSet<>();
        skip.add(self);
        skip.add("android");
        if (inputMethod != null && inputMethod.indexOf('/') > 0) skip.add(inputMethod.substring(0, inputMethod.indexOf('/')));
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            if (ri.activityInfo != null) skip.add(ri.activityInfo.packageName);
        }
        Set<String> out = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (ri.activityInfo != null && !skip.contains(ri.activityInfo.packageName)) out.add(ri.activityInfo.packageName);
            }
        }
        return out;
    }

    /** Clears off the main thread (a call per app) and reports on the main thread. */
    static void run(Context context, Done done) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            int count = 0;
            long before = 0;
            long after = 0;
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                before = info.availMem;
                String ime = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                for (String pkg : targets(app.getPackageManager(), app.getPackageName(), ime)) {
                    try {
                        am.killBackgroundProcesses(pkg);
                        count++;
                    } catch (RuntimeException ignored) {
                        // one app refusing must not stop the rest
                    }
                }
                try {
                    Thread.sleep(900);   // the kills finish in the background; read the memory after they have
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                am.getMemoryInfo(info);
                after = info.availMem;
            }
            final int apps = count;
            final long free = after;
            final long freed = Math.max(0, after - before);
            main.post(() -> done.onDone(apps, free, freed));
        }, "clear-all").start();
    }
}
