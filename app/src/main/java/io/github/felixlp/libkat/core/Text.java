package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Мелкие строковые утилиты (String.join и прочее появились только в Java 8 / API 26). */
public final class Text {
    private Text() {
    }

    public static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    /** optString возвращает "null" для JSON null — здесь это просто null. */
    public static String opt(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, null);
        return isEmpty(s) ? null : s;
    }

    public static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (isEmpty(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    /** Описание приходит строкой или документом ProseMirror ({type: doc, content: [...]}) — достаём текст. */
    public static String fromDoc(Object node) {
        StringBuilder sb = new StringBuilder();
        walk(node, sb);
        return sb.toString();
    }

    private static void walk(Object node, StringBuilder sb) {
        if (node instanceof String) {
            sb.append((String) node);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) walk(arr.opt(i), sb);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String type = o.optString("type");
            if ("text".equals(type)) {
                sb.append(o.optString("text"));
            } else if ("hardBreak".equals(type)) {
                sb.append('\n');
            } else {
                walk(o.opt("content"), sb);
                if ("paragraph".equals(type)) sb.append('\n');
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** java.util.Base64 есть только с API 26, android.util.Base64 — не на JVM. */
    public static String base64(byte[] data, boolean urlSafeNoPad) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? B64[((b1 & 0xf) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 0x3f] : '=');
        }
        String s = sb.toString();
        if (!urlSafeNoPad) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        return s.substring(0, end).replace('+', '-').replace('/', '_');
    }
}
