package io.github.felixlp.libkat.core;

/** Настройки SOCKS5-прокси. */
public class ProxyConfig {
    public static final int OFF = 0;
    /** Через прокси идут API и вход; картинки и видео — напрямую. */
    public static final int API = 1;
    /** Через прокси идёт всё. */
    public static final int ALL = 2;

    public final int mode;
    public final String host;
    public final int port;
    public final String user;
    public final String password;

    public ProxyConfig(int mode, String host, int port, String user, String password) {
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    public boolean enabled() {
        return mode != OFF && !Text.isEmpty(host) && port > 0 && port < 65536;
    }

    public boolean hasAuth() {
        return !Text.isEmpty(user);
    }

    public boolean forApi() {
        return enabled() && mode >= API;
    }

    public boolean forMedia() {
        return enabled() && mode >= ALL;
    }
}
