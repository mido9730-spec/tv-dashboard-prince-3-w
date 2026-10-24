package com.example.tvdash;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements RemoteServer.Host {

    private static final int PORT = 8080;

    // Tile sizes for Small / Medium / Large (dp)
    private static final int[] TILE_H = {120, 150, 190};
    private static final int[] ICON = {64, 84, 112};
    private static final int[] TEXT = {14, 16, 18};

    private final List<AppStore.Entry> apps = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private ImageView wallView;
    private View dimView;
    private LinearLayout root;
    private GridView grid;
    private TextView clock, date, titleView, hint;
    private BaseAdapter adapter;
    private RemoteServer server;
    private boolean serverOk;
    private AppStore.Settings cfg;
    private int curText = Color.WHITE;
    private int slideIdx = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable slide = new Runnable() {
        @Override public void run() {
            List<String> w = AppStore.walls(MainActivity.this);
            if (w.size() > 1) {
                slideIdx = (slideIdx + 1) % w.size();
                showWall(w.get(slideIdx), true);
            }
            ui.postDelayed(this, Math.max(5, cfg.slide) * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = AppStore.settings(this);
        buildUi();
        applySettings();

        server = new RemoteServer(getApplicationContext(), this);
        try {
            server.start(PORT);
            serverOk = true;
        } catch (IOException e) {
            serverOk = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        updateHint();
        applySettings();
        ui.post(tick);
        grid.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(slide);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }

    // ---- RemoteServer.Host (called from server threads) ----

    @Override
    public void onKey(final String key) {
        ui.post(new Runnable() {
            @Override public void run() {
                int code;
                switch (key) {
                    case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
                    case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
                    case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
                    case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
                    case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
                    default: return;
                }
                if (!grid.hasFocus()) grid.requestFocus();
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
    }

    @Override
    public void onAppsChanged() {
        ui.post(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    @Override
    public void onSettingsChanged() {
        ui.post(new Runnable() {
            @Override public void run() { applySettings(); }
        });
    }

    // ---- UI ----

    private void refresh() {
        apps.clear();
        apps.addAll(AppStore.load(this, false));
        adapter.notifyDataSetChanged();
    }

    private void updateClock() {
        Date now = new Date();
        String pattern = cfg.clock24 ? "HH:mm" : "h:mm a";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
    }

    private void updateHint() {
        String ip = localIp();
        if (!serverOk) {
            hint.setText("Phone remote could not start (port " + PORT + " is busy).");
        } else if (ip == null) {
            hint.setText("Connect the TV to Wi-Fi/Ethernet to use the phone remote.");
        } else {
            hint.setText("Phone remote:  http://" + ip + ":" + PORT + "     PIN: " + AppStore.pin(this));
        }
    }

    private Themes.Theme resolveTheme(AppStore.Settings s) {
        if ("custom".equals(s.theme)) {
            return Themes.custom(s.cTop, s.cBottom, s.cAccent, s.cText);
        }
        return Themes.byId(s.theme);
    }

    /** Applies theme, picture, columns, tile size, clock options and title. */
    private void applySettings() {
        cfg = AppStore.settings(this);
        Themes.Theme t = resolveTheme(cfg);
        List<String> walls = AppStore.walls(this);

        boolean slideshow = "*".equals(cfg.wall) && !walls.isEmpty();
        boolean single = !cfg.wall.isEmpty() && !"*".equals(cfg.wall) && walls.contains(cfg.wall);
        boolean hasWall = slideshow || single;

        ui.removeCallbacks(slide);
        int text = t.text;
        int dimText = t.dim;

        if (hasWall) {
            // Light text over a darkened picture so it stays readable
            text = Color.WHITE;
            dimText = 0xFFD0D5DD;
            root.setBackground(null);
            wallView.setVisibility(View.VISIBLE);
            dimView.setVisibility(View.VISIBLE);
            dimView.setBackgroundColor(Color.argb(cfg.dim * 255 / 100, 0, 0, 0));
            if (slideshow) {
                slideIdx = Math.min(slideIdx, walls.size() - 1);
                showWall(walls.get(slideIdx), false);
                if (walls.size() > 1) ui.postDelayed(slide, Math.max(5, cfg.slide) * 1000L);
            } else {
                showWall(cfg.wall, false);
            }
        } else {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[]{t.top, t.bottom});
            root.setBackground(bg);
            wallView.setImageDrawable(null);
            wallView.setVisibility(View.GONE);
            dimView.setVisibility(View.GONE);
        }

        curText = text;
        clock.setTextColor(text);
        date.setTextColor(dimText);
        hint.setTextColor(dimText);
        titleView.setTextColor(t.accent);
        titleView.setText(cfg.title);

        int vis = cfg.showClock ? View.VISIBLE : View.GONE;
        clock.setVisibility(vis);
        date.setVisibility(vis);

        grid.setNumColumns(cfg.cols);
        grid.setSelector(focusDrawable(t.accent));
        updateClock();
        adapter.notifyDataSetChanged();
    }

    private Bitmap loadWall(String id) {
        try {
            File f = AppStore.wallFile(this, id);
            if (f == null || !f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void showWall(String id, boolean fade) {
        final Bitmap bmp = loadWall(id);
        if (bmp == null) return;
        if (!fade) {
            wallView.setAlpha(1f);
            wallView.setImageBitmap(bmp);
            return;
        }
        wallView.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            @Override public void run() {
                wallView.setImageBitmap(bmp);
                wallView.animate().alpha(1f).setDuration(500).start();
            }
        }).start();
    }

    private void buildUi() {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF0E1116);

        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallView.setVisibility(View.GONE);
        frame.addView(wallView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dimView = new View(this);
        dimView.setVisibility(View.GONE);
        frame.addView(dimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(24));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);

        clock = new TextView(this);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.DEFAULT_BOLD);

        date = new TextView(this);
        date.setTextSize(22);
        date.setPadding(dp(24), 0, 0, dp(14));

        titleView = new TextView(this);
        titleView.setTextSize(28);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        titleView.setPadding(0, 0, 0, dp(14));

        header.addView(clock);
        header.addView(date);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(16));
        grid.setClipToPadding(false);
        grid.setPadding(dp(6), dp(24), dp(6), dp(6));
        grid.setDrawSelectorOnTop(false);
        grid.setFocusable(true);
        grid.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(grid, gp);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                AppStore.Entry e = apps.get(i);
                int s = Math.max(0, Math.min(2, cfg.size));

                LinearLayout tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(12), dp(14), dp(12), dp(12));
                tile.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H[s])));

                ImageView icon = new ImageView(MainActivity.this);
                icon.setImageDrawable(e.icon);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(icon, new LinearLayout.LayoutParams(dp(ICON[s]), dp(ICON[s])));

                TextView label = new TextView(MainActivity.this);
                label.setText(e.label);
                label.setTextColor(curText);
                label.setTextSize(TEXT[s]);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setPadding(0, dp(10), 0, 0);
                tile.addView(label);
                return tile;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                AppStore.launch(MainActivity.this, apps.get(pos).pkg);
            }
        });

        hint = new TextView(this);
        hint.setTextSize(18);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        setContentView(frame);
    }

    private GradientDrawable focusDrawable(int accent) {
        GradientDrawable d = new GradientDrawable();
        d.setColor((accent & 0x00FFFFFF) | 0x33000000);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(3), accent);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String localIp() {
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
