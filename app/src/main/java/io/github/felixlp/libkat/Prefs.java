package io.github.felixlp.libkat;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import io.github.felixlp.libkat.core.Auth;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.ProxyConfig;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Token;

/** Настройки (те же, что редактирует SettingsActivity) и хранилище токена. */
public class Prefs implements Auth.Store {
    public static final String API_DOMAIN = "api_domain";
    public static final String IMAGE_SERVER = "img_server";
    public static final String PROXY_MODE = "proxy_mode";
    public static final String PROXY_HOST = "proxy_host";
    public static final String PROXY_PORT = "proxy_port";
    public static final String PROXY_USER = "proxy_user";
    public static final String PROXY_PASS = "proxy_pass";
    public static final String PLAYER = "player";
    public static final String QUALITY = "video_quality";
    public static final String TITLE_LANG = "title_lang";
    public static final String COVERS = "covers";
    public static final String READER_MODE = "reader_mode";
    public static final String INLINE_COMMENTS = "comments_inline";
    private static final String SITE = "site";
    private static final String TOKEN = "token";

    public final SharedPreferences sp;

    Prefs(Context context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public String apiDomain() {
        return sp.getString(API_DOMAIN, LibApi.DEFAULT_DOMAIN);
    }

    public String imageServer() {
        return sp.getString(IMAGE_SERVER, "compress");
    }

    public ProxyConfig proxy() {
        return new ProxyConfig(
                parseInt(sp.getString(PROXY_MODE, "0"), 0),
                sp.getString(PROXY_HOST, "").trim(),
                parseInt(sp.getString(PROXY_PORT, "1080"), 0),
                sp.getString(PROXY_USER, "").trim(),
                sp.getString(PROXY_PASS, ""));
    }

    public boolean externalPlayer() {
        return "external".equals(sp.getString(PLAYER, "internal"));
    }

    public int quality() {
        return parseInt(sp.getString(QUALITY, "720"), 720);
    }

    /** Полные обложки в сетке или миниатюры 100×143. */
    public boolean fullCovers() {
        return !"thumb".equals(sp.getString(COVERS, "full"));
    }

    /** auto, webtoon, ltr или rtl. */
    public String readerMode() {
        return sp.getString(READER_MODE, "auto");
    }

    /** Комментарии прямо под главой в читалке (иначе — только по кнопке). */
    public boolean inlineComments() {
        return sp.getBoolean(INLINE_COMMENTS, true);
    }

    public boolean rusTitles() {
        return !"eng".equals(sp.getString(TITLE_LANG, "rus"));
    }

    public Site site() {
        return Site.byId(sp.getInt(SITE, Site.MANGALIB.id));
    }

    public void setSite(Site site) {
        sp.edit().putInt(SITE, site.id).apply();
    }

    @Override
    public Token load() {
        return Token.fromJson(sp.getString(TOKEN, null));
    }

    @Override
    public void save(Token token) {
        sp.edit().putString(TOKEN, token == null ? null : token.toJson()).apply();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
