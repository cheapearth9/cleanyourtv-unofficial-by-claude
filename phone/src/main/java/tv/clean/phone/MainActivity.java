package tv.clean.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.clean.phone.adb.AdbConnection;
import tv.clean.phone.adb.AdbKey;
import tv.clean.phone.core.ApkInstaller;
import tv.clean.phone.core.AppManager;
import tv.clean.phone.core.Cleaner;
import tv.clean.phone.core.CleanException;
import tv.clean.phone.core.Db;
import tv.clean.phone.core.Guards;
import tv.clean.phone.core.Log;
import tv.clean.phone.core.Planner;
import tv.clean.phone.core.Record;
import tv.clean.phone.core.SystemBars;
import tv.clean.phone.core.ResolverTable;
import tv.clean.phone.core.TvInfo;

/** One activity, four screens: connect, review, run, and restore (a dialog). All TV work runs on one worker thread. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF171C24;
    private static final int TEXT = 0xFFE8ECF1;
    private static final int DIM = 0xFF8B95A3;
    private static final int ACCENT = 0xFF4FC3A1;
    private static final int DANGER = 0xFFE5675B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Db db;
    private String dbError;
    private AdbTvShell shell;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String keyFingerprint = "";
    private TvInfo info;
    private Planner.Plan plan;
    private String homeComponent;
    private final List<String> launcherChoices = new ArrayList<>();

    // review-screen state
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private CheckBox installLauncherBox;
    private CheckBox layoutBox;
    private CheckBox inputKeyBox;
    private CheckBox homeKeyBox;
    private Spinner stockSpinner;
    private CheckBox alsoDisableBox;
    private TextView countView;
    private CheckBox barsBox;
    private boolean barsHiddenNow;
    private boolean barsSupported;
    private LinearLayout details;
    private ProgressBar progress;

    // apps screen
    private static final int PICK_APK = 41;
    private List<AppManager.App> apps = new ArrayList<>();
    private boolean showSystemApps;
    private boolean showRemovedApps;
    private String appQuery = "";
    private LinearLayout appList;
    private TextView appCount;
    private boolean inApps;
    private Runnable afterRun;

    private TextView logView;
    private ScrollView logScroll;
    private Button runButton;
    private TextView statusView;
    private String lastHost = "";

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        showConnect();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = getAssets().open("db/models.txt")) {
                    db = Db.parse(in);
                } catch (IOException e) {
                    dbError = e.toString();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        final AdbTvShell c = shell;
        releaseConnectionHolds();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (c != null) c.close();
            }
        });
        worker.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (inApps) {
            backToReview();
        } else if (shell != null && info != null && runButton != null) {
            disconnect();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ screen 1: connect

    private void showConnect() {
        inApps = false;
        LinearLayout root = page();
        root.addView(title("CleanYourTV"));
        root.addView(pill("Not connected", DIM));
        root.addView(dim("Unofficial phone version of the open-source cleanyourtv project. Removes ad and tracker apps from "
            + "an Android TV over Wi-Fi and gives it a clean home screen. Nothing is uninstalled; one tap restores it."));

        LinearLayout steps = card();
        steps.addView(body("On the TV (Android 7 or newer):"));
        steps.addView(dim("1. Settings > Device Preferences > About: press OK on \"Build\" seven times.\n"
            + "2. Back, then Developer options: turn on USB debugging. If there is a Network / ADB over network toggle, turn that on too.\n"
            + "3. Keep the TV on and on the same Wi-Fi as this phone."));
        root.addView(steps);

        final EditText host = new EditText(this);
        host.setHint("TV address, e.g. 192.168.1.50");
        host.setText(lastHost);
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setTextColor(TEXT);
        host.setHintTextColor(DIM);
        root.addView(host, matchWrap());

        final LinearLayout found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        final Button scan = button("Find TVs on this Wi-Fi", false);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan.setEnabled(false);
                scan.setText("Scanning...");
                found.removeAllViews();
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<String> hosts = LanScanner.findAdbHosts(5555);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                scan.setEnabled(true);
                                scan.setText("Find TVs on this Wi-Fi");
                                if (hosts.isEmpty()) {
                                    found.addView(dim("Nothing answered on port 5555. Check USB debugging / ADB over network on the TV, "
                                        + "or type its IP address (TV Settings > Network)."));
                                }
                                for (final String h : hosts) {
                                    Button hb = button("Use " + h, false);
                                    hb.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View x) {
                                            host.setText(h);
                                        }
                                    });
                                    found.addView(hb, matchWrap());
                                }
                            }
                        });
                    }
                });
            }
        });
        root.addView(scan, matchWrap());
        root.addView(found, matchWrap());

        statusView = dim("");
        final Button connect = button("Connect", true);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String h = host.getText().toString().trim();
                if (h.isEmpty()) {
                    statusView.setText("Type the TV's IP address first.");
                    return;
                }
                lastHost = h;
                connect.setEnabled(false);
                connectTo(h, connect);
            }
        });
        root.addView(connect, matchWrap());
        root.addView(statusView);
        setContentView(scroll(root));
    }

    private void connectTo(final String hostPort, final Button connect) {
        final String h;
        final int port;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(':') == colon) {
            h = hostPort.substring(0, colon);
            int p;
            try {
                p = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                p = 5555;
            }
            port = p;
        } else {
            h = hostPort;
            port = 5555;
        }
        setStatus("Connecting to " + h + ":" + port + "...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (db == null) throw new IOException("TV database not loaded: " + dbError);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            holdConnectionAlive();
                        }
                    });
                    final AdbKey key = AdbKey.loadOrCreate(getFilesDir());
                    keyFingerprint = key.fingerprint();
                    AdbTvShell s = new AdbTvShell(h, port, key, new AdbConnection.Status() {
                        @Override
                        public void onStatus(String m) {
                            setStatus(m + "\n\nThe TV shows this fingerprint:\n" + keyFingerprint);
                        }
                    }, new AdbTvShell.Notes() {
                        @Override
                        public void note(String m) {
                            logger.log(m);
                        }
                    });
                    s.open(120000);
                    shell = s;
                    setStatus("Connected. Reading the TV...");
                    info = TvInfo.probe(shell);
                    plan = Planner.plan(db, info);
                    homeComponent = Cleaner.homeComponent(shell, info);
                    loadLauncherChoices();
                    readBars();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showReview();
                        }
                    });
                } catch (final Exception e) {
                    if (shell != null) shell.close();
                    shell = null;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            releaseConnectionHolds();
                            connect.setEnabled(true);
                            setStatus(explainConnectError(e, h, port));
                        }
                    });
                }
            }
        });
    }

    /** Keeps Wi-Fi and the CPU awake for the session and pins traffic to Wi-Fi (a phone that prefers mobile data would cut the link). */
    private void holdConnectionAlive() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cleanyourtv");
                wifiLock.setReferenceCounted(false);
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cleanyourtv:adb");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        cm.bindProcessToNetwork(n);
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private void releaseConnectionHolds() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) cm.bindProcessToNetwork(null);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Whether the TV's on-screen buttons and notification bar are hidden by this app right now. */
    private void readBars() {
        barsSupported = SystemBars.supported(info);
        barsHiddenNow = false;
        if (!barsSupported) return;
        try {
            barsHiddenNow = SystemBars.isHidden(SystemBars.current(shell));
        } catch (IOException ignored) {
            // shown as "not hidden"; applying it later reports the real problem
        }
    }

    private void loadLauncherChoices() {
        launcherChoices.clear();
        try {
            ResolverTable rt = Cleaner.resolverTable(shell);
            Set<String> l = rt.launcherPackages();
            l.remove(Guards.OUR_LAUNCHER);
            launcherChoices.addAll(l);
        } catch (IOException ignored) {
            // the spinner then offers only the detected Home owner
        }
        String owner = homeComponent == null ? null : Guards.pkgOf(homeComponent);
        if (owner != null && !owner.equals("android") && !owner.equals(Guards.OUR_LAUNCHER) && !launcherChoices.contains(owner)
            && Guards.isValidName(owner)) {
            launcherChoices.add(0, owner);
        }
    }

    private String explainConnectError(Exception e, String host, int port) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.ConnectException || m.contains("refused")) {
            return host + ":" + port + " refused the connection. USB debugging (and ADB over network, if the TV has that toggle) must be on. "
                + "Some TVs open the port only while the Developer options screen has been opened once after boot.";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "No answer from " + host + ". If the TV shows \"Allow USB debugging?\", press OK (tick Always allow) and connect again.";
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.NoRouteToHostException) {
            return "Cannot reach " + host + ": check the address and that the phone is on the same Wi-Fi.";
        }
        if (m.contains("bad magic")) {
            return host + ":" + port + " is not an ADB port. Wireless debugging ports (Android 11+) are not supported: use port 5555.";
        }
        return m;
    }

    // ------------------------------------------------------------------ screen 2: review

    private void showReview() {
        inApps = false;
        boxes.clear();
        LinearLayout root = page();
        root.addView(pill("Connected", ACCENT));
        root.addView(title(info.title().isEmpty() ? "Your TV" : info.title()));
        root.addView(dim(info.androidLabel() + (info.leanback ? "" : "  -  not reporting Android TV features")));
        if ("0".equals(info.adbSecure)) {
            root.addView(warn("This TV reports ro.adb.secure=0: anyone on your network can get a shell on it. Turn debugging off when you are done."));
        }

        // ---- the headline: what is about to happen, in one card
        LinearLayout head = card();
        countView = new TextView(this);
        countView.setTextColor(TEXT);
        countView.setTextSize(22);
        countView.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(countView);
        head.addView(dim("Apps are switched off, never deleted. A restore point is saved on the TV and on this phone first, "
            + "and the TV puts the old launcher back by itself if this phone loses contact during the hand-over."));
        if (plan.best != null) {
            head.addView(dim("Matched: " + plan.best.tv + (plan.best.notes.isEmpty() ? "" : " - " + plan.best.notes)));
        } else if (!plan.matches.isEmpty()) {
            head.addView(dim("Close match: " + plan.matches.get(0).model.tv + ". Using the cross-TV scan."));
        } else {
            head.addView(dim("This model is not among the " + db.models.size()
                + " TVs in the database, so the list comes from what many other TVs disable, limited to apps installed here."));
        }
        root.addView(head);

        barsBox = null;
        if (barsSupported) {
            LinearLayout bc = card();
            barsBox = check("Hide the on-screen buttons and the notification bar", barsHiddenNow);
            barsBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    updateCount();
                }
            });
            bc.addView(barsBox);
            bc.addView(dim("For TVs that show a Home / Back button bar and a status bar on screen. Hidden after every restart too. "
                + "Your remote's Home and Back keys keep working. Untick it and press Make it clean to bring the bars back as they were."));
            root.addView(bc);
        }
        runButton = button("Make it clean", true);
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndRun();
            }
        });
        root.addView(runButton, matchWrap());
        final Button customise = button("Customise", false);
        root.addView(customise, matchWrap());

        // ---- details (hidden until Customise)
        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        customise.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = details.getVisibility() == View.GONE;
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                customise.setText(open ? "Hide the list" : "Customise");
            }
        });

        details.addView(section("Clean home screen"));
        LinearLayout lc = card();
        boolean canLauncher = info.sdk >= 24;
        installLauncherBox = check("Install the clean launcher", canLauncher && hasLauncherAsset());
        installLauncherBox.setEnabled(canLauncher);
        installLauncherBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                updateCount();
            }
        });
        lc.addView(installLauncherBox);
        if (!canLauncher) {
            lc.addView(dim("This TV runs Android API " + info.sdk + "; the launcher needs Android 7.0+. Clean-up still works."));
        } else {
            lc.addView(dim("Replace this Home app with the new launcher. It is started and watched first; your old one is only switched off once the new one runs:"));
            stockSpinner = new Spinner(this);
            List<String> labels = new ArrayList<>();
            labels.add("Keep the current Home app (launcher is installed but Home stays as is)");
            labels.addAll(launcherChoices);
            ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels);
            stockSpinner.setAdapter(ad);
            String owner = homeComponent == null ? "" : Guards.pkgOf(homeComponent);
            int sel = launcherChoices.indexOf(owner);
            if (sel < 0 && launcherChoices.size() == 1) sel = 0;
            stockSpinner.setSelection(sel >= 0 ? sel + 1 : 0);
            lc.addView(stockSpinner, matchWrap());
            if (launcherChoices.size() > 1) {
                alsoDisableBox = check("Also switch off the other launchers, so Home never asks which one to use (" + launcherChoices.size() + " found)", false);
                lc.addView(alsoDisableBox);
            }
            if (plan.best != null && plan.best.hasConfig) {
                layoutBox = check("Apply the dock layout tested on " + plan.best.tv, true);
                lc.addView(layoutBox);
            }
            inputKeyBox = check("Remote's Input button opens the input switcher (accessibility helper)", false);
            homeKeyBox = check("Remote sends the All-apps key instead of Home (some Xiaomi sticks)", false);
            lc.addView(inputKeyBox);
            lc.addView(homeKeyBox);
            if (!hasLauncherAsset()) lc.addView(warn("This build of the app does not contain the launcher APK (build it with the full project)."));
        }
        details.addView(lc);

        details.addView(section("Clean up (apps are disabled, not deleted)"));
        String[] tierNames = {"From this TV's own list", "Disabled on many TVs", "Optional: seen on a few TVs, or features some people use"};
        int shown = 0;
        for (int tier = 0; tier < 3; tier++) {
            List<Planner.Item> items = new ArrayList<>();
            for (Planner.Item it : plan.items) if (it.tier == tier) items.add(it);
            if (items.isEmpty()) continue;
            details.addView(body(tierNames[tier] + " (" + items.size() + ")"));
            LinearLayout tc = card();
            for (Planner.Item it : items) {
                CheckBox cb = check(it.pkg, it.checked);
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        updateCount();
                    }
                });
                tc.addView(cb);
                tc.addView(dim("    " + it.why));
                boxes.put(it.pkg, cb);
                shown++;
            }
            details.addView(tc);
        }
        if (shown == 0) details.addView(dim("Nothing to offer: no known ad or bloat apps are enabled on this TV."));
        details.addView(dim("Protected automatically: Android core, HDMI/tuner drivers, Settings, the Wi-Fi setup app when it owns your network, and launchers."));
        root.addView(details);
        updateCount();

        // ---- the rest
        Button appsBtn = button("Apps: uninstall, or install an APK", false);
        appsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadApps();
            }
        });
        root.addView(appsBtn, matchWrap());
        Button diag = button("Diagnostics (launcher crash log)", false);
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostics();
            }
        });
        root.addView(diag, matchWrap());
        Button restore = button("Undo / restore this TV", false);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRestore();
            }
        });
        root.addView(restore, matchWrap());
        Button bye = button("Not this TV? Enter the address", false);
        bye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect();
            }
        });
        root.addView(bye, matchWrap());
        setContentView(scroll(root));
    }

    private void updateCount() {
        if (countView == null) return;
        int n = 0;
        for (CheckBox cb : boxes.values()) if (cb.isChecked()) n++;
        boolean launcher = installLauncherBox != null && installLauncherBox.isChecked();
        boolean barsChange = barsBox != null && barsBox.isChecked() != barsHiddenNow;
        String t = n + (n == 1 ? " thing" : " things") + " to switch off";
        if (launcher) t += " + a clean launcher";
        if (barsChange) t += barsBox.isChecked() ? " + hide the bars" : " + bring the bars back";
        countView.setText(t);
        if (runButton != null) runButton.setEnabled(n > 0 || launcher || barsChange);
    }

    private boolean hasLauncherAsset() {
        try (InputStream in = getAssets().open("launcher.apk")) {
            return in.available() > 1000;
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void confirmAndRun() {
        final List<String> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> e : boxes.entrySet()) if (e.getValue().isChecked()) selected.add(e.getKey());
        final boolean launcher = installLauncherBox != null && installLauncherBox.isChecked();
        final String stock = launcher && stockSpinner != null && stockSpinner.getSelectedItemPosition() > 0
            ? launcherChoices.get(stockSpinner.getSelectedItemPosition() - 1) : "";
        final boolean barsChange = barsBox != null && barsBox.isChecked() != barsHiddenNow;
        final boolean hideBars = barsBox != null && barsBox.isChecked();
        if (selected.isEmpty() && !launcher && !barsChange) {
            toast("Nothing selected.");
            return;
        }
        final List<String> alsoDisable = new ArrayList<>();
        if (launcher && !stock.isEmpty() && alsoDisableBox != null && alsoDisableBox.isChecked()) {
            for (String l : launcherChoices) if (!l.equals(stock)) alsoDisable.add(l);
        }
        StringBuilder msg = new StringBuilder();
        if (barsChange) msg.append(hideBars ? "Hide the on-screen buttons and the notification bar.\n" : "Bring the on-screen buttons and the notification bar back.\n");
        if (!selected.isEmpty()) msg.append("Disable ").append(selected.size()).append(" apps (reversible).\n");
        if (launcher) {
            msg.append("Install the clean launcher.\n");
            if (!stock.isEmpty()) msg.append("Then switch Home from ").append(stock).append(" to it.\n");
            if (!alsoDisable.isEmpty()) msg.append("Also switch off: ").append(alsoDisable).append(".\n");
        }
        msg.append("\nKeep the TV on and the phone connected until it finishes. An undo record is saved on the TV first.");
        new AlertDialog.Builder(this)
            .setTitle("Ready?")
            .setMessage(msg.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runApply(selected, launcher, stock, alsoDisable, barsChange, hideBars);
                }
            }).show();
    }

    // ------------------------------------------------------------------ screen 3: run

    private void showRun(String heading) {
        inApps = false;
        afterRun = null;
        LinearLayout root = page();
        root.addView(title(heading));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, matchWrap());
        root.addView(dim("Keep this screen open. If the connection drops, the app reconnects by itself."));
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextColor(TEXT);
        logView.setText("");
        logView.setTextIsSelectable(true);
        LinearLayout c = card();
        c.addView(logView);
        root.addView(c);
        logScroll = scroll(root);
        setContentView(logScroll);
    }

    private final Log logger = new Log() {
        @Override
        public void log(final String line) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (logView != null) {
                        logView.append(line + "\n");
                        logScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                logScroll.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                }
            });
        }
    };

    private final Cleaner.RecordSink sink = new Cleaner.RecordSink() {
        @Override
        public void save(Record record) throws IOException {
            File dir = new File(getFilesDir(), "records");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = (info.title() + "_" + lastHost).replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(record.serialize().getBytes(StandardCharsets.UTF_8));
            }
        }
    };

    private void runApply(final List<String> selected, final boolean launcher, final String stock, final List<String> alsoDisable,
                          final boolean barsChange, final boolean hideBars) {
        showRun("Working on " + info.title());
        final byte[] apk = launcher ? readAsset("launcher.apk") : null;
        final byte[] cfg = launcher && layoutBox != null && layoutBox.isChecked() && plan.best != null
            ? readAsset("db/cfg/" + plan.best.dir + ".json") : null;
        final boolean inputKey = inputKeyBox != null && inputKeyBox.isChecked();
        final boolean homeKey = homeKeyBox != null && homeKeyBox.isChecked();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder summary = new StringBuilder();
                boolean failed = false;
                try {
                    if (barsChange) {
                        logger.log("== Screen bars ==");
                        try {
                            Cleaner.applyBars(shell, info, hideBars, logger, sink);
                            barsHiddenNow = hideBars;
                            summary.append(hideBars ? "Hid the on-screen buttons and the notification bar.\n" : "Brought the on-screen buttons and the notification bar back.\n");
                        } catch (CleanException e) {
                            logger.log("Could not change the bars: " + e.getMessage());
                            summary.append("Bars unchanged: ").append(e.getMessage()).append('\n');
                        }
                    }
                    if (!selected.isEmpty()) {
                        logger.log("== Clean-up ==");
                        Cleaner.DebloatResult r = Cleaner.debloat(shell, info, selected, logger, sink);
                        summary.append("Disabled ").append(r.disabled.size()).append(" apps.");
                        if (!r.kept.isEmpty()) {
                            logger.log("Kept for safety:");
                            for (String k : r.kept) logger.log("  " + k);
                            summary.append(" Kept ").append(r.kept.size()).append(" for safety.");
                        }
                        if (!r.stillEnabled.isEmpty()) {
                            summary.append(" ").append(r.stillEnabled.size()).append(" refused by this firmware.");
                        }
                        info = TvInfo.probe(shell);
                    }
                    if (launcher) {
                        logger.log("== Launcher ==");
                        Cleaner.LauncherOptions o = new Cleaner.LauncherOptions();
                        o.apk = apk;
                        o.stock = stock;
                        o.alsoDisable = alsoDisable;
                        o.config = cfg;
                        o.inputKeyService = inputKey;
                        o.homeKeyService = homeKey;
                        Cleaner.LauncherResult lr = Cleaner.installLauncher(shell, info, o, logger, sink);
                        summary.append(lr.isHome ? "\nThe new launcher is now Home. Press Home on the remote." : "\n" + lr.note);
                        if (lr.isHome && !lr.note.isEmpty()) summary.append("\n").append(lr.note);
                    }
                    logger.log("");
                    logger.log("Done. When you are finished, turn USB debugging off on the TV (and reboot it) so port 5555 closes.");
                } catch (CleanException e) {
                    failed = true;
                    logger.log("");
                    logger.log("STOPPED: " + e.getMessage());
                    summary.append("Stopped: ").append(e.getMessage());
                } catch (IOException e) {
                    failed = true;
                    logger.log("");
                    logger.log("Lost contact with the TV: " + e.getMessage());
                    logger.log("The app already tried to reconnect. If the launcher hand-over was in progress, the TV puts the old launcher "
                        + "back by itself within a few minutes. Connect again and open Undo / restore to see what state it is in.");
                    summary.append("Lost contact with the TV: ").append(e.getMessage());
                }
                finish(failed, summary.toString());
            }
        });
    }

    private void runDiagnostics() {
        showRun("Diagnostics for " + info.title());
        logger.log("Starting the launcher on the TV once and watching it (about 15 seconds). Each part appears as soon as it is done.");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final String text = tv.clean.phone.core.Diagnostics.collect(shell, info, logger);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        LinearLayout root = (LinearLayout) logScroll.getChildAt(0);
                        Button copy = button("Copy report", false);
                        copy.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("cleanyourtv diagnostics", text));
                                toast("Copied. Paste it into your message.");
                            }
                        });
                        root.addView(copy, matchWrap());
                    }
                });
                finish(false, "Report ready: copy it or take a photo.");
            }
        });
    }

    private void finish(final boolean failed, final String summary) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout root = (LinearLayout) logScroll.getChildAt(0);
                if (progress != null) progress.setVisibility(View.GONE);
                TextView t = body(summary);
                t.setTextColor(failed ? DANGER : ACCENT);
                root.addView(t);
                Button back = button(failed ? "Back" : "Finish", true);
                back.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (afterRun != null) afterRun.run();
                        else backToReview();
                    }
                });
                root.addView(back, matchWrap());
            }
        });
    }

    private void backToReview() {
        setStatus("");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    info = TvInfo.probe(shell);
                    plan = Planner.plan(db, info);
                    homeComponent = Cleaner.homeComponent(shell, info);
                    loadLauncherChoices();
                    readBars();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showReview();
                        }
                    });
                } catch (IOException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            shell = null;
                            showConnect();
                            setStatus("The TV is no longer reachable (it may be restarting). Connect again.");
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ apps

    private interface Task {
        /** Returns null on success, else a message for the person. */
        String run() throws Exception;
    }

    private void loadApps() {
        showRun("Apps on " + info.title());
        logger.log("Reading the list of apps...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    apps = AppManager.list(shell);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showApps();
                        }
                    });
                } catch (IOException e) {
                    afterRun = new Runnable() {
                        @Override
                        public void run() {
                            backToReview();
                        }
                    };
                    finish(true, "Could not read the apps: " + e.getMessage());
                }
            }
        });
    }

    private void showApps() {
        inApps = true;
        LinearLayout root = page();
        root.addView(title("Apps"));
        root.addView(dim(info.title() + ". Tap Uninstall to remove an app from the TV. System apps are only removed for this TV's user "
            + "and can be brought back from the Removed list."));

        Button install = button("Install an APK from this phone", true);
        install.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(pick, PICK_APK);
                } catch (RuntimeException e) {
                    toast("No file picker found on this phone.");
                }
            }
        });
        root.addView(install, matchWrap());

        final EditText search = new EditText(this);
        search.setHint("Search by name");
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(DIM);
        search.setText(appQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                appQuery = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                renderApps();
            }
        });
        root.addView(search, matchWrap());

        CheckBox sys = check("Show system apps", showSystemApps);
        sys.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                showSystemApps = on;
                renderApps();
            }
        });
        root.addView(sys);
        boolean anyRemoved = false;
        for (AppManager.App a : apps) if (a.removed) anyRemoved = true;
        if (anyRemoved) {
            CheckBox rem = check("Show removed system apps (to bring back)", showRemovedApps);
            rem.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    showRemovedApps = on;
                    renderApps();
                }
            });
            root.addView(rem);
        }
        appCount = dim("");
        root.addView(appCount);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList);
        Button back = button("Back", false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backToReview();
            }
        });
        root.addView(back, matchWrap());
        setContentView(scroll(root));
        renderApps();
    }

    private void renderApps() {
        if (appList == null) return;
        appList.removeAllViews();
        int shown = 0;
        int total = 0;
        for (final AppManager.App a : apps) {
            if (a.removed ? !showRemovedApps : (a.system && !showSystemApps)) continue;
            if (!appQuery.isEmpty() && !a.pkg.toLowerCase(java.util.Locale.ROOT).contains(appQuery)) continue;
            total++;
            if (shown >= 300) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = body(a.pkg);
            name.setTextSize(14);
            text.addView(name);
            text.addView(dim(a.kind()));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button act = button(a.removed ? "Bring back" : a.system || a.disabled ? "Options" : "Uninstall", false);
            act.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    appAction(a);
                }
            });
            row.addView(act, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            appList.addView(row);
        }
        appCount.setText(total + (total == 1 ? " app" : " apps") + (total > shown ? " (showing the first " + shown + ": search to narrow)" : "")
            + (showSystemApps ? "" : "  -  system apps hidden"));
    }

    private void appAction(final AppManager.App a) {
        if (a.removed) {
            appTask("Bringing back " + a.pkg, new Task() {
                @Override
                public String run() throws Exception {
                    String err = AppManager.reinstall(shell, a);
                    if (err == null) logger.log(a.pkg + " is back.");
                    return err;
                }
            });
            return;
        }
        Set<String> launchers = new java.util.LinkedHashSet<>(launcherChoices);
        String homePkg = homeComponent == null ? null : Guards.pkgOf(homeComponent);
        final String blocked = AppManager.blockedReason(a, launchers, homePkg);
        if (!a.system && !a.disabled) {
            if (blocked != null) {
                new AlertDialog.Builder(this).setTitle("Cannot uninstall").setMessage(a.pkg + ": " + blocked + ".").setPositiveButton("OK", null).show();
                return;
            }
            confirmUninstall(a, "Uninstall " + a.pkg + "?", withWarning(a, "The app and its data are deleted from the TV."));
            return;
        }
        // system or disabled: offer the reversible choices
        final List<String> labels = new ArrayList<>();
        if (a.disabled) labels.add("Enable");
        if (!a.disabled && blocked == null) labels.add("Disable (reversible, recorded for Undo)");
        if (a.updatedSystem()) labels.add("Remove updates (back to the built-in version)");
        if (blocked == null) labels.add("Uninstall for this TV (can be brought back)");
        if (labels.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Protected").setMessage(a.pkg + ": " + blocked + ".").setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(a.pkg)
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    String l = labels.get(which);
                    if (l.startsWith("Enable")) {
                        appTask("Enabling " + a.pkg, new Task() {
                            @Override
                            public String run() throws Exception {
                                return AppManager.enable(shell, a);
                            }
                        });
                    } else if (l.startsWith("Remove updates")) {
                        new AlertDialog.Builder(MainActivity.this).setTitle("Remove updates of " + a.pkg + "?")
                            .setMessage(withWarning(a, "The built-in version comes back. Its data is kept."))
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove updates", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w) {
                                    appTask("Removing updates of " + a.pkg, new Task() {
                                        @Override
                                        public String run() throws Exception {
                                            return AppManager.removeUpdates(shell, a);
                                        }
                                    });
                                }
                            }).show();
                    } else if (l.startsWith("Disable")) {
                        appTask("Disabling " + a.pkg, new Task() {
                            @Override
                            public String run() throws Exception {
                                TvInfo fresh = TvInfo.probe(shell);
                                Cleaner.DebloatResult r = Cleaner.debloat(shell, fresh, java.util.Collections.singletonList(a.pkg), logger, sink);
                                if (r.disabled.contains(a.pkg)) return null;
                                return r.kept.isEmpty() ? "the TV refused to disable it" : r.kept.get(0);
                            }
                        });
                    } else {
                        confirmUninstall(a, "Uninstall " + a.pkg + " for this TV?",
                            withWarning(a, "It is removed for this TV's user only. The app stays in the system and can be brought back from the Removed list."));
                    }
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private String withWarning(AppManager.App a, String text) {
        String w = AppManager.warning(a);
        return w == null ? text : "WARNING: " + w + "\n\n" + text;
    }

    private void confirmUninstall(final AppManager.App a, String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Uninstall", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    appTask("Uninstalling " + a.pkg, new Task() {
                        @Override
                        public String run() throws Exception {
                            String err = AppManager.uninstall(shell, a);
                            if (err == null) logger.log(a.pkg + " is gone.");
                            return err;
                        }
                    });
                }
            }).show();
    }

    /** Runs one app task on the worker with the progress screen, then returns to the apps list. */
    private void appTask(String heading, final Task task) {
        showRun(heading);
        afterRun = new Runnable() {
            @Override
            public void run() {
                loadApps();
            }
        };
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String err;
                try {
                    err = task.run();
                } catch (CleanException e) {
                    err = e.getMessage();
                } catch (IOException e) {
                    err = "Lost contact with the TV: " + e.getMessage();
                } catch (Exception e) {
                    err = String.valueOf(e);
                }
                finish(err != null, err != null ? "Failed: " + err : "Done.");
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null && shell != null) {
            installFromUri(data.getData());
        }
    }

    private void installFromUri(final Uri uri) {
        showRun("Install an APK");
        afterRun = new Runnable() {
            @Override
            public void run() {
                loadApps();
            }
        };
        worker.execute(new Runnable() {
            @Override
            public void run() {
                File copy = new File(getCacheDir(), "install.apk");
                String err = null;
                try {
                    String name = "the app";
                    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (i >= 0 && c.getString(i) != null) name = c.getString(i);
                        }
                    } catch (RuntimeException ignored) {
                        // keep the generic name
                    }
                    logger.log("Reading " + name + " from this phone...");
                    try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(copy)) {
                        if (in == null) throw new IOException("the file picker returned nothing");
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    ApkInstaller.install(shell, info, copy, name, logger);
                } catch (CleanException e) {
                    err = e.getMessage();
                } catch (IOException e) {
                    err = "Lost contact with the TV or could not read the file: " + e.getMessage();
                } catch (RuntimeException e) {
                    err = String.valueOf(e);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                }
                finish(err != null, err != null ? "Failed: " + err : "Installed.");
            }
        });
    }

    // ------------------------------------------------------------------ restore

    private void startRestore() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Record rec = Cleaner.readTvRecord(shell);
                    final Set<String> disabledNow = TvInfo.listPackages(shell, false);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            askRestore(rec, disabledNow);
                        }
                    });
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("Could not read the TV: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void askRestore(final Record rec, final Set<String> disabledNow) {
        final CheckBox remove = check("Also remove the clean launcher", true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        String msg;
        if (rec != null) {
            msg = "Undo record from " + rec.savedAt + ":\n" + rec.disabled.size() + " apps will be switched back on"
                + (rec.stock.isEmpty() ? "" : ", and " + rec.stock + " will be Home again") + ".";
            if (rec.launcherInstalled) box.addView(remove);
        } else {
            msg = "No undo record on this TV.\n\nThe TV has " + disabledNow.size() + " disabled apps (some were disabled at the factory). "
                + "Switching all of them on is safe but may bring back apps you never saw.";
        }
        TextView t = new TextView(this);
        t.setText(msg);
        box.addView(t, 0);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Restore this TV").setView(box)
            .setNegativeButton("Cancel", null);
        if (rec != null) {
            b.setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runRestore(rec, remove.isChecked(), null);
                }
            });
        } else if (!disabledNow.isEmpty()) {
            b.setPositiveButton("Enable all", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runRestore(null, false, new ArrayList<>(disabledNow));
                }
            });
        }
        b.show();
    }

    private void runRestore(final Record rec, final boolean removeLauncher, final List<String> enableAll) {
        showRun("Restoring " + info.title());
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean failed = false;
                String summary;
                try {
                    if (rec != null) {
                        Cleaner.RestoreResult r = Cleaner.restore(shell, info, rec, removeLauncher, logger);
                        summary = "Re-enabled " + r.enabled.size() + " apps." + (r.failed.isEmpty() ? "" : " " + r.failed.size() + " could not be re-enabled.");
                        failed = !r.failed.isEmpty();
                    } else {
                        int n = Cleaner.enableAll(shell, enableAll, logger);
                        summary = "Enabled " + n + " apps.";
                    }
                    logger.log("Restarting is not required. If Home looks wrong, press Home once.");
                } catch (CleanException e) {
                    failed = true;
                    summary = e.getMessage();
                } catch (IOException e) {
                    failed = true;
                    summary = "Lost contact with the TV: " + e.getMessage();
                }
                finish(failed, summary);
            }
        });
    }

    private void disconnect() {
        final AdbTvShell c = shell;
        shell = null;
        info = null;
        runButton = null;
        countView = null;
        alsoDisableBox = null;
        releaseConnectionHolds();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (c != null) c.close();
            }
        });
        showConnect();
    }

    // ------------------------------------------------------------------ view helpers

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) statusView.setText(s);
            }
        });
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(24), dp(16), dp(32));
        return l;
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        s.addView(child);
        return s;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        l.setBackground(g);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView pill(String text, int color) {
        TextView t = new TextView(this);
        t.setText("\u25CF  " + text);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView section(String s) {
        TextView t = title(s);
        t.setTextSize(18);
        t.setTextColor(ACCENT);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    private TextView dim(String s) {
        TextView t = body(s);
        t.setTextColor(DIM);
        t.setTextSize(13);
        return t;
    }

    private TextView warn(String s) {
        TextView t = body(s);
        t.setTextColor(DANGER);
        t.setTextSize(13);
        return t;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TEXT);
        c.setTextSize(14);
        c.setChecked(on);
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(primary ? BG : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setGravity(Gravity.CENTER);
        return b;
    }
}
