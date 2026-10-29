package tv.clean.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import tv.clean.phone.core.Cleaner;
import tv.clean.phone.core.CleanException;
import tv.clean.phone.core.Db;
import tv.clean.phone.core.Guards;
import tv.clean.phone.core.Log;
import tv.clean.phone.core.Planner;
import tv.clean.phone.core.Record;
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
    private AdbConnection conn;
    private AdbTvShell shell;
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
        final AdbConnection c = conn;
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
        if (conn != null && info != null && runButton != null) {
            disconnect();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ screen 1: connect

    private void showConnect() {
        LinearLayout root = page();
        root.addView(title("CleanYourTV Phone"));
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
                    AdbKey key = AdbKey.loadOrCreate(getFilesDir());
                    AdbConnection c = AdbConnection.connect(h, port, key, 120000, new AdbConnection.Status() {
                        @Override
                        public void onStatus(String m) {
                            setStatus(m);
                        }
                    });
                    conn = c;
                    shell = new AdbTvShell(c);
                    setStatus("Connected. Reading the TV...");
                    info = TvInfo.probe(shell);
                    plan = Planner.plan(db, info);
                    homeComponent = Cleaner.homeComponent(shell, info);
                    loadLauncherChoices();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showReview();
                        }
                    });
                } catch (final Exception e) {
                    if (conn != null) conn.close();
                    conn = null;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            connect.setEnabled(true);
                            setStatus(explainConnectError(e, h, port));
                        }
                    });
                }
            }
        });
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
        boxes.clear();
        LinearLayout root = page();
        root.addView(title(info.title().isEmpty() ? "Your TV" : info.title()));
        root.addView(dim(info.androidLabel() + (info.leanback ? "" : "  -  not reporting Android TV features")));
        if ("0".equals(info.adbSecure)) {
            root.addView(warn("This TV reports ro.adb.secure=0: anyone on your network can get a shell on it. Turn debugging off when you are done."));
        }
        if (plan.best != null) {
            LinearLayout c = card();
            c.addView(body("Matched: " + plan.best.tv));
            if (!plan.best.notes.isEmpty()) c.addView(dim(plan.best.notes));
            root.addView(c);
        } else if (!plan.matches.isEmpty()) {
            root.addView(dim("Close matches: " + plan.matches.get(0).model.tv + ". Using the cross-TV scan below."));
        } else {
            root.addView(dim("This model is not in the database of " + db.models.size()
                + " TVs, so the list below comes from what many other TVs disable, limited to apps installed here."));
        }

        // ---- launcher
        root.addView(section("Clean home screen"));
        LinearLayout lc = card();
        boolean canLauncher = info.sdk >= 24;
        installLauncherBox = check("Install the clean launcher", canLauncher);
        installLauncherBox.setEnabled(canLauncher);
        lc.addView(installLauncherBox);
        if (!canLauncher) {
            lc.addView(dim("This TV runs Android API " + info.sdk + "; the launcher needs Android 7.0+. Clean-up still works."));
        } else {
            lc.addView(dim("Replace this Home app with the new launcher (it is started first; your old one is only disabled once the new one works):"));
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
        root.addView(lc);

        // ---- debloat
        root.addView(section("Clean up (apps are disabled, not deleted)"));
        String[] tierNames = {"From this TV's own list", "Disabled on many TVs", "Optional: seen on a few TVs, or features some people use"};
        int shown = 0;
        for (int tier = 0; tier < 3; tier++) {
            List<Planner.Item> items = new ArrayList<>();
            for (Planner.Item it : plan.items) if (it.tier == tier) items.add(it);
            if (items.isEmpty()) continue;
            root.addView(body(tierNames[tier] + " (" + items.size() + ")"));
            LinearLayout tc = card();
            for (Planner.Item it : items) {
                CheckBox cb = check(it.pkg, it.checked);
                tc.addView(cb);
                tc.addView(dim("    " + it.why));
                boxes.put(it.pkg, cb);
                shown++;
            }
            root.addView(tc);
        }
        if (shown == 0) root.addView(dim("Nothing to offer: no known ad or bloat apps are enabled on this TV."));
        root.addView(dim("Protected automatically: Android core, HDMI/tuner drivers, Settings, the Wi-Fi setup app when it owns your network, and launchers."));

        // ---- actions
        runButton = button("Start", true);
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndRun();
            }
        });
        root.addView(runButton, matchWrap());
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
        Button bye = button("Disconnect", false);
        bye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect();
            }
        });
        root.addView(bye, matchWrap());
        setContentView(scroll(root));
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
        if (selected.isEmpty() && !launcher) {
            toast("Nothing selected.");
            return;
        }
        StringBuilder msg = new StringBuilder();
        if (!selected.isEmpty()) msg.append("Disable ").append(selected.size()).append(" apps (reversible).\n");
        if (launcher) {
            msg.append("Install the clean launcher.\n");
            if (!stock.isEmpty()) msg.append("Then switch Home from ").append(stock).append(" to it.\n");
        }
        msg.append("\nKeep the TV on and the phone connected until it finishes. An undo record is saved on the TV first.");
        new AlertDialog.Builder(this)
            .setTitle("Ready?")
            .setMessage(msg.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runApply(selected, launcher, stock);
                }
            }).show();
    }

    // ------------------------------------------------------------------ screen 3: run

    private void showRun(String heading) {
        LinearLayout root = page();
        root.addView(title(heading));
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

    private void runApply(final List<String> selected, final boolean launcher, final String stock) {
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
                    logger.log("The TV may be restarting. Reconnect and open Undo / restore to see what state it is in.");
                    summary.append("Lost contact with the TV.");
                }
                finish(failed, summary.toString());
            }
        });
    }

    private void runDiagnostics() {
        showRun("Diagnostics for " + info.title());
        logger.log("Starting the launcher on the TV once and reading its log (about 15 seconds)...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String report;
                boolean failed = false;
                try {
                    report = tv.clean.phone.core.Diagnostics.collect(shell, info);
                } catch (IOException e) {
                    report = "Lost contact with the TV: " + e.getMessage();
                    failed = true;
                }
                final String text = report;
                logger.log(text);
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
                finish(failed, failed ? "Could not finish." : "Report ready: copy it or take a photo.");
            }
        });
    }

    private void finish(final boolean failed, final String summary) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout root = (LinearLayout) logScroll.getChildAt(0);
                TextView t = body(summary);
                t.setTextColor(failed ? DANGER : ACCENT);
                root.addView(t);
                Button back = button(failed ? "Back" : "Finish", true);
                back.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        backToReview();
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
                            conn = null;
                            showConnect();
                            setStatus("The TV is no longer reachable (it may be restarting). Connect again.");
                        }
                    });
                }
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
        final AdbConnection c = conn;
        conn = null;
        info = null;
        runButton = null;
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
