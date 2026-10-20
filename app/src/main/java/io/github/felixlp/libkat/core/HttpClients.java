package io.github.felixlp.libkat.core;

import java.io.IOException;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public final class HttpClients {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    private HttpClients() {
    }

    /** TLS-настройки платформы. На Android — Conscrypt и свои корневые сертификаты, в тестах — null (JDK). */
    public static final class Tls {
        public final SSLSocketFactory factory;
        public final X509TrustManager trustManager;

        public Tls(SSLSocketFactory factory, X509TrustManager trustManager) {
            this.factory = factory;
            this.trustManager = trustManager;
        }
    }

    public static OkHttpClient.Builder builder(Tls tls) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // HTTP/2 через рефлексию ALPN на старом Android — лишний риск, HTTP/1.1 хватает
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request r = chain.request();
                        if (r.header("User-Agent") == null) {
                            r = r.newBuilder().header("User-Agent", USER_AGENT).build();
                        }
                        return chain.proceed(r);
                    }
                });
        if (tls != null) b.sslSocketFactory(tls.factory, tls.trustManager);
        return b;
    }

    /** Тот же клиент, но через SOCKS5 с удалённым DNS. */
    public static OkHttpClient viaSocks(OkHttpClient base, ProxyConfig cfg) {
        return base.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(new Socks5SocketFactory(cfg))
                .dns(Socks5SocketFactory.REMOTE_DNS)
                .build();
    }
}
