package io.github.felixlp.libkat;

import android.graphics.Color;

final class Colors {
    private Colors() {
    }

    /** Color.parseColor не понимает короткую запись "#fff", а сайт её использует. */
    static int parse(String s, int fallback) {
        if (s == null) return fallback;
        String c = s.trim();
        if (c.length() == 4 && c.charAt(0) == '#') {
            c = "#" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
        }
        try {
            return Color.parseColor(c);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
