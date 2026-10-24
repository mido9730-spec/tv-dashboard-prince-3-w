package com.example.tvdash;

import android.graphics.Color;

import java.util.Locale;

/** Colour themes the phone can pick from, plus a user-defined "custom" theme. */
final class Themes {

    static final class Theme {
        final String id, name;
        final int top, bottom, accent, text, dim;

        Theme(String id, String name, int top, int bottom, int accent, int text, int dim) {
            this.id = id;
            this.name = name;
            this.top = top;
            this.bottom = bottom;
            this.accent = accent;
            this.text = text;
            this.dim = dim;
        }
    }

    static final Theme[] ALL = {
        new Theme("midnight", "Midnight",   0xFF0E1116, 0xFF0E1116, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF9AA4B2),
        new Theme("ocean",    "Ocean",      0xFF081B33, 0xFF0F5C7A, 0xFF7DD3FC, 0xFFFFFFFF, 0xFFA5C8DA),
        new Theme("forest",   "Forest",     0xFF0B1F14, 0xFF1F5B3A, 0xFF86EFAC, 0xFFFFFFFF, 0xFFA7C9B2),
        new Theme("sunset",   "Sunset",     0xFF2A0F2E, 0xFFB4452F, 0xFFFCD34D, 0xFFFFFFFF, 0xFFF0C6B0),
        new Theme("royal",    "Royal",      0xFF160B2E, 0xFF4C2A85, 0xFFC4B5FD, 0xFFFFFFFF, 0xFFC1B5E0),
        new Theme("black",    "Pure black", 0xFF000000, 0xFF000000, 0xFF4F8CFF, 0xFFFFFFFF, 0xFF8A8F98),
        new Theme("light",    "Light",      0xFFF3F4F6, 0xFFDDE3EA, 0xFF2563EB, 0xFF111827, 0xFF4B5563)
    };

    private Themes() {}

    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    /** True for the presets and for "custom". */
    static boolean exists(String id) {
        if ("custom".equals(id)) return true;
        for (Theme t : ALL) if (t.id.equals(id)) return true;
        return false;
    }

    /** Builds the user's own theme from four "#RRGGBB" colours. */
    static Theme custom(String top, String bottom, String accent, String text) {
        int tx = parse(text, 0xFFFFFFFF);
        int dim = (tx & 0x00FFFFFF) | 0xB3000000;
        return new Theme("custom", "Custom",
                parse(top, 0xFF0E1116), parse(bottom, 0xFF0E1116),
                parse(accent, 0xFF4F8CFF), tx, dim);
    }

    private static int parse(String s, int def) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }

    static String hex(int c) {
        return String.format(Locale.US, "#%06X", c & 0xFFFFFF);
    }
}
