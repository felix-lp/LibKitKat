package io.github.felixlp.libkat.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Вход в аккаунт *LIB без WebView: тот же OAuth (authorization code + PKCE), что делает сайт,
 * только форма auth.lib.social заполняется программно, а капча ALTCHA решается перебором.
 */
public class Auth {
    public static final String AUTH_HOST = "https://auth.lib.social";
    static final String CLIENT_ID = "1";
    static final String REDIRECT = "https://mangalib.org/ru/front/auth/oauth/callback";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern CSRF = Pattern.compile("name=\"_token\" value=\"([^\"]+)\"");
    private static final Pattern FIELD = Pattern.compile("name=\"(_token|state|client_id|auth_token)\" value=\"([^\"]*)\"");
    private static final Pattern FORM_ERROR = Pattern.compile("class=\"form-input__error\"[^>]*>([^<]+)<");

    public interface Store {
        Token load();

        void save(Token token);
    }

    public interface Progress {
        void step(String message);
    }

    private final OkHttpClient client;
    private final String apiDomain;
    private final Store store;
    private Token token;
    private boolean loaded;

    public Auth(OkHttpClient client, String apiDomain, Store store) {
        this.client = client;
        this.apiDomain = apiDomain;
        this.store = store;
    }

    public synchronized Token token() {
        if (!loaded) {
            token = store.load();
            loaded = true;
        }
        return token;
    }

    public boolean loggedIn() {
        return token() != null;
    }

    public synchronized void logout() {
        token = null;
        loaded = true;
        store.save(null);
    }

    private synchronized void set(Token t) {
        token = t;
        loaded = true;
        store.save(t);
    }

    public Token login(String login, String password, Progress progress) throws IOException {
        OkHttpClient c = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(new MemoryCookieJar())
                .build();
        String verifier = randomUrlSafe(64);
        String challenge = Text.base64(sha256(verifier.getBytes(Charset.forName("US-ASCII"))), true);
        String state = Text.hex(randomBytes(20));
        HttpUrl authorize = HttpUrl.parse(AUTH_HOST + "/auth/oauth/authorize").newBuilder()
                .addQueryParameter("scope", "")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", REDIRECT)
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("prompt", "consent")
                .addQueryParameter("iframe", "false")
                .build();

        progress.step("Открываю страницу входа…");
        Hop hop = follow(c, new Request.Builder().url(authorize).header("Referer", Site.MANGALIB.referer()).build());
        String csrf = find(CSRF, hop.body);
        if (csrf == null) throw new ApiException(0, "Не нашёл форму входа на " + hop.url);

        progress.step("Решаю капчу…");
        String altcha;
        try (Response r = c.newCall(new Request.Builder().url(AUTH_HOST + "/altcha/challenge")
                .header("Referer", AUTH_HOST + "/auth/login").build()).execute()) {
            altcha = Altcha.solve(new JSONObject(r.body().string()));
        } catch (JSONException e) {
            throw new ApiException(0, "Капча: " + e.getMessage());
        }

        progress.step("Вхожу…");
        RequestBody form = new FormBody.Builder()
                .add("_token", csrf)
                .add("login", login)
                .add("password", password)
                .add("altcha", altcha)
                .build();
        String location;
        try (Response r = c.newCall(new Request.Builder().url(AUTH_HOST + "/auth/login").post(form)
                .header("Referer", AUTH_HOST + "/auth/login").header("Origin", AUTH_HOST).build()).execute()) {
            location = r.isRedirect() ? r.header("Location") : null;
        }
        if (location == null || !location.contains("/oauth/authorize")) {
            throw new ApiException(401, loginError(c));
        }

        hop = follow(c, new Request.Builder().url(https(HttpUrl.parse(AUTH_HOST).resolve(location))).build());
        if (hop.callback == null) {
            // Страница «Продолжить как …» — подтверждаем доступ, как кнопка на сайте
            progress.step("Подтверждаю доступ…");
            String consentForm = hop.body.split("name=\"_method\"")[0];
            FormBody.Builder fb = new FormBody.Builder();
            Matcher m = FIELD.matcher(consentForm);
            int fields = 0;
            while (m.find()) {
                fb.add(m.group(1), m.group(2));
                fields++;
            }
            if (fields < 3) throw new ApiException(0, "Не понял страницу подтверждения входа");
            try (Response r = c.newCall(new Request.Builder().url(AUTH_HOST + "/auth/oauth/authorize").post(fb.build())
                    .header("Referer", hop.url.toString()).header("Origin", AUTH_HOST).build()).execute()) {
                String loc = r.header("Location");
                hop.callback = loc != null && loc.startsWith(REDIRECT) ? HttpUrl.parse(loc) : null;
            }
        }
        if (hop.callback == null) throw new ApiException(0, "Сервер не вернул код авторизации");
        String code = hop.callback.queryParameter("code");
        if (code == null) {
            throw new ApiException(401, "Вход отклонён: " + hop.callback.queryParameter("error"));
        }
        if (!state.equals(hop.callback.queryParameter("state"))) throw new ApiException(0, "Не совпал state OAuth");

        progress.step("Получаю токен…");
        JSONObject body = new JSONObject();
        try {
            body.put("grant_type", "authorization_code");
            body.put("client_id", CLIENT_ID);
            body.put("redirect_uri", REDIRECT);
            body.put("code_verifier", verifier);
            body.put("code", code);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Token t = exchange(body);
        fillUser(t);
        set(t);
        return t;
    }

    /** Обновить токен по refresh_token. false — обновить не удалось, нужен повторный вход. */
    public boolean refresh() throws IOException {
        Token old = token();
        if (old == null || Text.isEmpty(old.refreshToken)) return false;
        JSONObject body = new JSONObject();
        try {
            body.put("grant_type", "refresh_token");
            body.put("client_id", CLIENT_ID);
            body.put("refresh_token", old.refreshToken);
            body.put("scope", "");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Token t;
        try {
            t = exchange(body);
        } catch (ApiException e) {
            if (e.code >= 400 && e.code < 500) return false;
            throw e;
        }
        t.userId = old.userId;
        t.username = old.username;
        synchronized (this) {
            // Другой поток мог успеть обновить токен раньше
            if (token == old) set(t);
        }
        return true;
    }

    private Token exchange(JSONObject body) throws IOException {
        Request req = new Request.Builder().url(apiDomain + "/api/auth/oauth/token")
                .post(RequestBody.create(JSON, body.toString()))
                .header("Accept", "application/json")
                .header("Site-Id", String.valueOf(Site.MANGALIB.id))
                .header("Referer", Site.MANGALIB.referer())
                .header("Origin", Site.MANGALIB.web)
                .build();
        try (Response r = client.newCall(req).execute()) {
            String s = r.body().string();
            if (!r.isSuccessful()) throw new ApiException(r.code(), "Токен не выдан (HTTP " + r.code() + ")");
            Token t = Token.fromOAuth(new JSONObject(s));
            if (Text.isEmpty(t.accessToken)) throw new ApiException(0, "Пустой токен");
            return t;
        } catch (JSONException e) {
            throw new ApiException(0, "Странный ответ при выдаче токена");
        }
    }

    private void fillUser(Token t) {
        Request req = new Request.Builder().url(apiDomain + "/api/auth/me")
                .header("Accept", "application/json")
                .header("Site-Id", String.valueOf(Site.MANGALIB.id))
                .header("Referer", Site.MANGALIB.referer())
                .header("Authorization", t.header())
                .build();
        try (Response r = client.newCall(req).execute()) {
            if (!r.isSuccessful()) return;
            JSONObject data = new JSONObject(r.body().string()).optJSONObject("data");
            if (data != null) {
                t.userId = data.optInt("id");
                t.username = Text.opt(data, "username");
            }
        } catch (IOException | JSONException ignored) {
            // Имя пользователя — только для красоты
        }
    }

    private String loginError(OkHttpClient c) {
        try (Response r = c.newCall(new Request.Builder().url(AUTH_HOST + "/auth/login").build()).execute()) {
            List<String> errors = new ArrayList<>();
            Matcher m = FORM_ERROR.matcher(r.body().string());
            while (m.find()) {
                String e = m.group(1).trim();
                if (e.length() > 0) errors.add(e);
            }
            if (!errors.isEmpty()) return Text.join("\n", errors);
        } catch (IOException ignored) {
            // Ниже общее сообщение
        }
        return "Неверный логин или пароль";
    }

    private static final class Hop {
        HttpUrl url;
        String body;
        HttpUrl callback;
    }

    /** Идёт по редиректам вручную и останавливается на callback сайта: туда приходит code. */
    private static Hop follow(OkHttpClient c, Request req) throws IOException {
        Hop hop = new Hop();
        for (int i = 0; i < 10; i++) {
            try (Response r = c.newCall(req).execute()) {
                if (r.isRedirect()) {
                    HttpUrl next = r.request().url().resolve(r.header("Location"));
                    if (next == null) throw new ApiException(0, "Битый редирект");
                    if (next.toString().startsWith(REDIRECT)) {
                        hop.url = next;
                        hop.callback = next;
                        hop.body = "";
                        return hop;
                    }
                    req = new Request.Builder().url(https(next)).build();
                    continue;
                }
                if (!r.isSuccessful()) throw new ApiException(r.code(), "auth.lib.social ответил HTTP " + r.code());
                hop.url = r.request().url();
                hop.body = r.body().string();
                return hop;
            }
        }
        throw new ApiException(0, "Слишком много редиректов при входе");
    }

    /** Сервер входа редиректит на http:// (а потом сам на https://) — открытый HTTP клиентом запрещён. */
    private static HttpUrl https(HttpUrl url) {
        if (url == null) return null;
        return url.isHttps() ? url : url.newBuilder().scheme("https").port(443).build();
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String randomUrlSafe(int bytes) {
        return Text.base64(randomBytes(bytes), true);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Куки нужны только на время входа (сессия Laravel + XSRF). */
    static final class MemoryCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> list) {
            for (Cookie n : list) {
                Iterator<Cookie> it = cookies.iterator();
                while (it.hasNext()) {
                    Cookie o = it.next();
                    if (o.name().equals(n.name()) && o.domain().equals(n.domain()) && o.path().equals(n.path())) it.remove();
                }
                cookies.add(n);
            }
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Cookie c : cookies) {
                if (c.expiresAt() > now && c.matches(url)) out.add(c);
            }
            return out;
        }
    }
}
