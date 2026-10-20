package io.github.felixlp.libkat.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Мини SOCKS5-сервер с логином/паролем. Наружу он выходит через HTTP CONNECT-прокси окружения
 * (если задан https.proxyHost), иначе напрямую.
 */
public class Socks5Test {
    private ServerSocket server;
    private final List<String> requestedHosts = new CopyOnWriteArrayList<>();

    @Before
    public void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket c = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                serve(c);
                            }
                        }).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    private void serve(Socket c) {
        try {
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            in.readUnsignedByte();
            int n = in.readUnsignedByte();
            boolean userPass = false;
            for (int i = 0; i < n; i++) userPass |= in.readUnsignedByte() == 2;
            if (!userPass) {
                out.write(new byte[]{5, (byte) 0xff});
                c.close();
                return;
            }
            out.write(new byte[]{5, 2});
            in.readUnsignedByte();
            byte[] u = new byte[in.readUnsignedByte()];
            in.readFully(u);
            byte[] p = new byte[in.readUnsignedByte()];
            in.readFully(p);
            boolean ok = "kat".equals(new String(u, "UTF-8")) && "secret".equals(new String(p, "UTF-8"));
            out.write(new byte[]{1, (byte) (ok ? 0 : 1)});
            if (!ok) {
                c.close();
                return;
            }
            in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();
            String host;
            if (atyp == 3) {
                byte[] h = new byte[in.readUnsignedByte()];
                in.readFully(h);
                host = new String(h, "UTF-8");
            } else {
                byte[] a = new byte[atyp == 1 ? 4 : 16];
                in.readFully(a);
                host = InetAddress.getByAddress(a).getHostAddress();
            }
            int port = in.readUnsignedShort();
            requestedHosts.add(host + ":" + port);
            Socket up = upstream(host, port);
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            pipe(up.getInputStream(), out);
            pipe(in, up.getOutputStream());
        } catch (IOException e) {
            try {
                c.close();
            } catch (IOException ignored) {
                // всё равно
            }
        }
    }

    private static Socket upstream(String host, int port) throws IOException {
        String ph = System.getProperty("https.proxyHost");
        if (ph == null || ph.isEmpty()) return new Socket(host, port);
        Socket s = new Socket(ph, Integer.parseInt(System.getProperty("https.proxyPort", "3128")));
        s.getOutputStream().write(("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\n\r\n").getBytes("US-ASCII"));
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("CONNECT оборвался");
            head.append((char) b);
        }
        if (!head.toString().startsWith("HTTP/1.1 200") && !head.toString().startsWith("HTTP/1.0 200")) {
            throw new IOException("CONNECT: " + head);
        }
        return s;
    }

    private static void pipe(final InputStream from, final OutputStream to) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[16384];
                try {
                    int r;
                    while ((r = from.read(buf)) > 0) {
                        to.write(buf, 0, r);
                        to.flush();
                    }
                } catch (IOException ignored) {
                    // соединение закрыто
                } finally {
                    try {
                        to.close();
                    } catch (IOException ignored) {
                        // уже
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private OkHttpClient client(String user, String pass) {
        ProxyConfig cfg = new ProxyConfig(ProxyConfig.ALL, "127.0.0.1", server.getLocalPort(), user, pass);
        return HttpClients.viaSocks(HttpClients.builder(null).build(), cfg);
    }

    @Test
    public void apiThroughSocksWithRemoteDns() throws Exception {
        assumeTrue("live-тесты выключены", Boolean.getBoolean("live"));
        LibApi api = new LibApi(client("kat", "secret"), LibApi.DEFAULT_DOMAIN, null);
        LibApi.Paged<Title> p = api.catalog(Site.MANGALIB, null, null, 0, 1);
        assertFalse(p.items.isEmpty());
        // Прокси получил имя хоста, а не IP — значит DNS резолвится на стороне прокси
        assertTrue(requestedHosts.toString(), requestedHosts.contains("api.cdnlibs.org:443"));
    }

    @Test
    public void wrongPasswordIsReported() {
        LibApi api = new LibApi(client("kat", "wrong"), LibApi.DEFAULT_DOMAIN, null);
        try {
            api.catalog(Site.MANGALIB, null, null, 0, 1);
            fail("должна быть ошибка");
        } catch (IOException e) {
            assertTrue(e.getMessage(), String.valueOf(e.getMessage()).contains("логин/пароль"));
        }
    }

    @Test
    public void missingCredentialsAreReported() {
        LibApi api = new LibApi(client("", ""), LibApi.DEFAULT_DOMAIN, null);
        try {
            api.catalog(Site.MANGALIB, null, null, 0, 1);
            fail("должна быть ошибка");
        } catch (IOException e) {
            assertTrue(e.getMessage(), String.valueOf(e.getMessage()).contains("требует логин"));
        }
        assertEquals(0, requestedHosts.size());
    }
}
