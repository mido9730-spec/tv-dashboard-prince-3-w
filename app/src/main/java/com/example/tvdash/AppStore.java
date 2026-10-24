package com.example.tvdash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds installed apps, remembers hidden apps, style settings and pictures, launches apps, keeps the phone PIN. */
final class AppStore {

    static final class Entry {
        String label;
        String pkg;
        Drawable icon;
        boolean hidden;
    }

    /** Look-and-feel options chosen from the phone. */
    static final class Settings {
        String theme = "midnight";
        int cols = 5;
        int size = 1; // 0 small, 1 medium, 2 large
        boolean clock24 = true;
        boolean showClock = true;
        String title = "";
        String wall = "";   // "" = no picture, "*" = slideshow, otherwise a picture id
        int dim = 40;       // darkening over the picture, percent
        int slide = 30;     // seconds per picture in slideshow
        String cTop = "#0E1116";
        String cBottom = "#1F2937";
        String cAccent = "#4F8CFF";
        String cText = "#FFFFFF";
    }

    private static final int MAX_WALLS = 30;

    private AppStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvdash", Context.MODE_PRIVATE);
    }

    /** 4-digit PIN shown on the TV; the phone must send it with every request. */
    static String pin(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("pin", null);
        if (s == null) {
            s = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("pin", s).apply();
        }
        return s;
    }

    // ---- settings ----

    static Settings settings(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.theme = p.getString("theme", s.theme);
        s.cols = p.getInt("cols", s.cols);
        s.size = p.getInt("size", s.size);
        s.clock24 = p.getBoolean("clock24", s.clock24);
        s.showClock = p.getBoolean("showClock", s.showClock);
        s.title = p.getString("title", s.title);
        s.wall = p.getString("wall", s.wall);
        s.dim = p.getInt("dim", s.dim);
        s.slide = p.getInt("slide", s.slide);
        s.cTop = p.getString("cTop", s.cTop);
        s.cBottom = p.getString("cBottom", s.cBottom);
        s.cAccent = p.getString("cAccent", s.cAccent);
        s.cText = p.getString("cText", s.cText);
        return s;
    }

    /** Validates and saves one setting. Returns false if the value is not allowed. */
    static boolean setSetting(Context c, String key, String v) {
        if (key == null || v == null) return false;
        SharedPreferences.Editor e = prefs(c).edit();
        try {
            switch (key) {
                case "theme": {
                    if (!Themes.exists(v)) return false;
                    e.putString("theme", v);
                    break;
                }
                case "cols": {
                    int n = Integer.parseInt(v);
                    if (n < 2 || n > 8) return false;
                    e.putInt("cols", n);
                    break;
                }
                case "size": {
                    int n = Integer.parseInt(v);
                    if (n < 0 || n > 2) return false;
                    e.putInt("size", n);
                    break;
                }
                case "dim": {
                    int n = Integer.parseInt(v);
                    if (n < 0 || n > 85) return false;
                    e.putInt("dim", n);
                    break;
                }
                case "slide": {
                    int n = Integer.parseInt(v);
                    if (n < 5 || n > 600) return false;
                    e.putInt("slide", n);
                    break;
                }
                case "clock24": {
                    e.putBoolean("clock24", "1".equals(v));
                    break;
                }
                case "showClock": {
                    e.putBoolean("showClock", "1".equals(v));
                    break;
                }
                case "title": {
                    String t = v.trim();
                    if (t.length() > 40) t = t.substring(0, 40);
                    e.putString("title", t);
                    break;
                }
                case "wall": {
                    if (!v.isEmpty() && !v.equals("*") && !validWallId(v)) return false;
                    e.putString("wall", v);
                    break;
                }
                case "cTop":
                case "cBottom":
                case "cAccent":
                case "cText": {
                    if (!v.matches("#[0-9A-Fa-f]{6}")) return false;
                    e.putString(key, v.toUpperCase(Locale.US));
                    break;
                }
                default:
                    return false;
            }
        } catch (NumberFormatException x) {
            return false;
        }
        e.apply();
        return true;
    }

    // ---- pictures ----

    static File wallDir(Context c) {
        File d = new File(c.getFilesDir(), "walls");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean validWallId(String id) {
        return id != null && id.matches("w[0-9]{6,20}");
    }

    static File wallFile(Context c, String id) {
        return validWallId(id) ? new File(wallDir(c), id + ".jpg") : null;
    }

    /** Picture ids, oldest first. */
    static List<String> walls(Context c) {
        File[] files = wallDir(c).listFiles();
        List<String> ids = new ArrayList<>();
        if (files == null) return ids;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".jpg")) {
                String id = n.substring(0, n.length() - 4);
                if (validWallId(id)) ids.add(id);
            }
        }
        return ids;
    }

    static boolean canAddWall(Context c) {
        return walls(c).size() < MAX_WALLS;
    }

    static String newWallId(Context c) {
        long t = System.currentTimeMillis();
        while (new File(wallDir(c), "w" + t + ".jpg").exists()) t++;
        return "w" + t;
    }

    static void deleteWall(Context c, String id) {
        File f = wallFile(c, id);
        if (f != null && f.exists()) f.delete();
        if (id != null && id.equals(settings(c).wall)) setSetting(c, "wall", "");
    }

    // ---- apps ----

    private static Set<String> hiddenSet(Context c) {
        return new HashSet<>(prefs(c).getStringSet("hidden", new HashSet<String>()));
    }

    static void setHidden(Context c, String pkg, boolean hide) {
        Set<String> h = hiddenSet(c);
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs(c).edit().putStringSet("hidden", h).apply();
    }

    /** All launchable apps (TV apps first found, then normal apps), sorted by name. */
    static List<Entry> load(Context c, boolean includeHidden) {
        PackageManager pm = c.getPackageManager();
        Set<String> hidden = hiddenSet(c);
        Map<String, Entry> map = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.hidden = hidden.contains(pkg);
                if (e.hidden && !includeHidden) continue;
                e.icon = ri.loadIcon(pm);
                map.put(pkg, e);
            }
        }
        List<Entry> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    static boolean launch(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
