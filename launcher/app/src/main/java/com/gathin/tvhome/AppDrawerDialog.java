package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bottom "Apps" button's drawer: every launchable app in a plain full-screen grid. It is made of one GridView with
 * recycled cells and a tiny icon cache, loads the list off the main thread when opened, and lets go of everything on close.
 */
final class AppDrawerDialog {
    private static final int ICON_CACHE = 30;

    private final Activity activity;
    private final Dialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(ICON_CACHE);
    private final float density;
    private volatile boolean closed;
    private Adapter adapter;
    private GridView gridView;

    private static final class Entry {
        final String label;
        final String pkg;
        final Intent launch;

        Entry(String label, String pkg, Intent launch) {
            this.label = label;
            this.pkg = pkg;
            this.launch = launch;
        }
    }

    AppDrawerDialog(Activity activity, Runnable onClose) {
        this.activity = activity;
        density = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xEE0B0E12);
        int pad = Math.round(32 * density);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, Math.round(24 * density), pad, 0);

        TextView title = new TextView(activity);
        title.setText(R.string.app_drawer_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, Math.round(12 * density));
        column.addView(title);

        GridView grid = new GridView(activity);
        gridView = grid;
        grid.setColumnWidth(Math.round(132 * density));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Math.round(8 * density));
        grid.setHorizontalSpacing(Math.round(8 * density));
        grid.setClipToPadding(false);
        grid.setSelector(highlight());
        grid.setDrawSelectorOnTop(true);
        grid.setPadding(0, Math.round(4 * density), 0, pad);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(position));
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            closed = true;
            entries.clear();
            icons.evictAll();
            adapter = null;
            gridView = null;
            if (onClose != null) onClose.run();
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    void show() {
        dialog.show();
        new Thread(this::load, "app-drawer").start();
    }

    void close() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void load() {
        PackageManager pm = activity.getPackageManager();
        List<Entry> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String self = activity.getPackageName();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                ActivityInfo ai = info.activityInfo;
                if (ai == null || self.equals(ai.packageName) || !seen.add(ai.packageName)) continue;
                // The AirPlay receiver is run from the AirPlay pill and its settings; it is put on Home only from the 9-dots library.
                if (AirPlayControl.PKG.equals(ai.packageName) || AirPlayControl.LEGACY_PKG.equals(ai.packageName)) continue;
                CharSequence label = info.loadLabel(pm);
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                found.add(new Entry(label == null ? ai.packageName : label.toString(), ai.packageName, launch));
            }
        }
        Collator collator = Collator.getInstance();
        Collections.sort(found, (a, b) -> {
            int c = collator.compare(a.label, b.label);
            return c != 0 ? c : a.pkg.compareTo(b.pkg);
        });
        main.post(() -> {
            if (closed || adapter == null) return;
            entries.clear();
            entries.addAll(found);
            adapter.notifyDataSetChanged();
            if (gridView != null) gridView.requestFocus();   // selects the first app, so the frame shows at once
        });
    }

    private void launch(int position) {
        if (position < 0 || position >= entries.size()) return;
        Intent intent = new Intent(entries.get(position).launch);
        close();
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private Drawable icon(Entry e) {
        Drawable d = icons.get(e.pkg);
        if (d == null) {
            try {
                d = activity.getPackageManager().getActivityIcon(e.launch);
            } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
                d = activity.getPackageManager().getDefaultActivityIcon();
            }
            icons.put(e.pkg, d);
        }
        return d;
    }

    /** The white frame around the selected app: a GridView draws this itself (its cells never take focus on their own). */
    private Drawable highlight() {
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x33FFFFFF);
        box.setStroke(Math.round(2 * density), 0xFFFFFFFF);
        box.setCornerRadius(14 * density);
        return box;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout cell;
            ImageView image;
            TextView label;
            if (convert == null) {
                cell = new LinearLayout(activity);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                int p = Math.round(10 * density);
                cell.setPadding(p, p, p, p);
                image = new ImageView(activity);
                image.setId(android.R.id.icon);
                int s = Math.round(56 * density);
                cell.addView(image, new LinearLayout.LayoutParams(s, s));
                label = new TextView(activity);
                label.setId(android.R.id.text1);
                label.setTextColor(Color.WHITE);
                label.setTextSize(13);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(0, Math.round(6 * density), 0, 0);
                cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                cell = (LinearLayout) convert;
                image = (ImageView) cell.findViewById(android.R.id.icon);
                label = (TextView) cell.findViewById(android.R.id.text1);
            }
            Entry e = entries.get(position);
            image.setImageDrawable(icon(e));
            label.setText(e.label);
            return cell;
        }
    }
}
