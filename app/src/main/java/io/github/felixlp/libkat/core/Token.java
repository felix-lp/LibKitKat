package io.github.felixlp.libkat.core;

import org.json.JSONException;
import org.json.JSONObject;

/** OAuth-токен аккаунта *LIB (один на все сайты). */
public class Token {
    public String tokenType = "Bearer";
    public String accessToken;
    public String refreshToken;
    /** Время истечения, мс с эпохи. */
    public long expiresAt;
    public int userId;
    public String username;

    public String header() {
        return tokenType + " " + accessToken;
    }

    /** Обновляем заранее, за сутки до истечения. */
    public boolean nearExpiry() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt - 24L * 3600 * 1000;
    }

    static Token fromOAuth(JSONObject o) {
        Token t = new Token();
        t.tokenType = o.optString("token_type", "Bearer");
        t.accessToken = o.optString("access_token");
        t.refreshToken = Text.opt(o, "refresh_token");
        long expiresIn = o.optLong("expires_in", 0);
        t.expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0;
        return t;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("token_type", tokenType);
            o.put("access_token", accessToken);
            o.put("refresh_token", refreshToken);
            o.put("expires_at", expiresAt);
            o.put("user_id", userId);
            o.put("username", username);
            return o.toString();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static Token fromJson(String s) {
        if (Text.isEmpty(s)) return null;
        try {
            JSONObject o = new JSONObject(s);
            Token t = new Token();
            t.tokenType = o.optString("token_type", "Bearer");
            t.accessToken = o.optString("access_token");
            t.refreshToken = Text.opt(o, "refresh_token");
            t.expiresAt = o.optLong("expires_at");
            t.userId = o.optInt("user_id");
            t.username = Text.opt(o, "username");
            return Text.isEmpty(t.accessToken) ? null : t;
        } catch (JSONException e) {
            return null;
        }
    }
}
