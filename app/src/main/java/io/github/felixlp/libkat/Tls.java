package io.github.felixlp.libkat;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.github.felixlp.libkat.core.HttpClients;

/**
 * TLS для Android 4.4: системный стек не знает TLS 1.3, части шифров и новых корневых сертификатов
 * (ISRG Root X1 и др.). Ставим Conscrypt и доверяем бандлу Mozilla + системному хранилищу.
 */
final class Tls {
    private static final String TAG = "LibKat.Tls";
    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private Tls() {
    }

    static HttpClients.Tls init(Context context) {
        Provider conscrypt = null;
        try {
            conscrypt = Conscrypt.newProvider();
            Security.insertProviderAt(conscrypt, 1);
        } catch (Throwable e) {
            Log.w(TAG, "Conscrypt недоступен, остаётся системный TLS", e);
        }
        try {
            X509TrustManager tm = trustManager(context);
            SSLContext ctx = conscrypt != null ? SSLContext.getInstance("TLS", conscrypt) : SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return new HttpClients.Tls(new ModernSocketFactory(ctx.getSocketFactory()), tm);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось настроить TLS", e);
            return null;
        }
    }

    private static X509TrustManager trustManager(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String pem = readAsset(context, "cacert.pem");
        int n = 0;
        int pos = 0;
        while ((pos = pem.indexOf(BEGIN, pos)) >= 0) {
            int end = pem.indexOf(END, pos);
            if (end < 0) break;
            String b64 = pem.substring(pos + BEGIN.length(), end);
            pos = end + END.length();
            try {
                Certificate c = cf.generateCertificate(new ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT)));
                ks.setCertificateEntry("mozilla-" + n++, c);
            } catch (Exception e) {
                // Старый парсер может не понять отдельные сертификаты — пропускаем их
            }
        }
        try {
            KeyStore sys = KeyStore.getInstance("AndroidCAStore");
            sys.load(null, null);
            Enumeration<String> aliases = sys.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                Certificate c = sys.getCertificate(a);
                if (c != null) ks.setCertificateEntry("sys-" + a, c);
            }
        } catch (Exception e) {
            Log.w(TAG, "Системное хранилище сертификатов недоступно", e);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager t : tmf.getTrustManagers()) {
            if (t instanceof X509TrustManager) return (X509TrustManager) t;
        }
        throw new IllegalStateException("Нет X509TrustManager");
    }

    private static String readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString("US-ASCII");
        }
    }

    /** Включает только TLS 1.2/1.3 и явно задаёт SNI (OkHttp 3.12 на Android не всегда делает это для Conscrypt). */
    static final class ModernSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        ModernSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public Socket createSocket() throws IOException {
            return patch(delegate.createSocket(), null);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port), host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port), null);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort), null);
        }

        private static Socket patch(Socket s, String host) {
            if (!(s instanceof SSLSocket)) return s;
            SSLSocket ssl = (SSLSocket) s;
            List<String> protocols = new ArrayList<>();
            for (String p : ssl.getSupportedProtocols()) {
                if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) protocols.add(p);
            }
            if (!protocols.isEmpty()) ssl.setEnabledProtocols(protocols.toArray(new String[0]));
            if (host != null && Conscrypt.isConscrypt(ssl)) {
                Conscrypt.setHostname(ssl, host);
                Conscrypt.setUseSessionTickets(ssl, true);
            }
            return ssl;
        }
    }
}
