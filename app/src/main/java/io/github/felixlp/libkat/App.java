package io.github.felixlp.libkat;

import android.app.Application;

import java.util.List;

import io.github.felixlp.libkat.core.Auth;
import io.github.felixlp.libkat.core.Chapter;
import io.github.felixlp.libkat.core.HttpClients;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.ProxyConfig;
import io.github.felixlp.libkat.core.Title;
import okhttp3.OkHttpClient;

public class App extends Application {
    private static App instance;

    public Prefs prefs;
    public History history;
    public ImageLoader images;
    public StreamProxy stream;

    private HttpClients.Tls tls;
    private OkHttpClient apiClient;
    private OkHttpClient mediaClient;
    private Auth auth;
    private LibApi api;

    /** Открытый тайтл и его главы — чтобы не тянуть список заново при переходе в читалку. */
    public Title currentTitle;
    public List<Chapter> currentChapters;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tls = Tls.init(this);
        prefs = new Prefs(this);
        history = new History(this);
        rebuild();
        images = new ImageLoader(this);
        stream = new StreamProxy();
    }

    /** Пересобрать сетевые клиенты после смены прокси или домена API. */
    public synchronized void rebuild() {
        OkHttpClient direct = HttpClients.builder(tls).build();
        ProxyConfig proxy = prefs.proxy();
        OkHttpClient proxied = proxy.enabled() ? HttpClients.viaSocks(direct, proxy) : direct;
        apiClient = proxy.forApi() ? proxied : direct;
        mediaClient = proxy.forMedia() ? proxied : direct;
        auth = new Auth(apiClient, prefs.apiDomain(), prefs);
        api = new LibApi(apiClient, prefs.apiDomain(), auth);
    }

    public synchronized LibApi api() {
        return api;
    }

    public synchronized Auth auth() {
        return auth;
    }

    public synchronized OkHttpClient apiClient() {
        return apiClient;
    }

    public synchronized OkHttpClient mediaClient() {
        return mediaClient;
    }
}
