package io.github.felixlp.libkat.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * ALTCHA — капча на proof-of-work: найти n, для которого SHA-256(salt + n) == challenge.
 * На сайте её решает JS-виджет, здесь — тот же перебор (до maxnumber, обычно 100 000).
 */
public final class Altcha {
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private Altcha() {
    }

    /** Возвращает значение поля формы "altcha" (base64 JSON с найденным числом). */
    public static String solve(JSONObject challenge) throws JSONException {
        String algorithm = challenge.getString("algorithm");
        if (!"SHA-256".equalsIgnoreCase(algorithm)) throw new JSONException("Неизвестный алгоритм капчи: " + algorithm);
        String salt = challenge.getString("salt");
        String target = challenge.getString("challenge");
        int max = challenge.optInt("maxnumber", 1000000);
        int n = find(salt, Text.unhex(target), max);
        if (n < 0) throw new JSONException("Не удалось решить капчу");
        JSONObject payload = new JSONObject();
        payload.put("algorithm", algorithm);
        payload.put("challenge", target);
        payload.put("number", n);
        payload.put("salt", salt);
        payload.put("signature", challenge.getString("signature"));
        return Text.base64(payload.toString().getBytes(Charset.forName("UTF-8")), false);
    }

    static int find(String salt, byte[] target, int max) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] prefix = salt.getBytes(ASCII);
        for (int n = 0; n <= max; n++) {
            md.update(prefix);
            md.update(Integer.toString(n).getBytes(ASCII));
            if (Arrays.equals(md.digest(), target)) return n;
        }
        return -1;
    }
}
