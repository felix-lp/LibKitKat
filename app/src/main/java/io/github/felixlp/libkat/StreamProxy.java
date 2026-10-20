package io.github.felixlp.libkat;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Локальный HTTP-прокси для видео: плеер (встроенный или VLC) ходит на http://127.0.0.1,
 * а мы качаем через OkHttp — с современным TLS, нужным Referer и SOCKS5 при необходимости.
 * Range пробрасывается, так что перемотка работает.
 */
public class StreamProxy {
    private static final String TAG = "LibKat.Stream";
    private static final String[] PASS_HEADERS = {
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"
    };

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;

    public synchronized String url(String upstream, String referer, String ext) throws IOException {
        start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/stream" + ext
                + "?u=" + URLEncoder.encode(upstream, "UTF-8")
                + "&r=" + URLEncoder.encode(referer == null ? "" : referer, "UTF-8");
    }

    private void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final ServerSocket s = server;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket client = s.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(client);
                            }
                        });
                    } catch (IOException e) {
                        Log.w(TAG, "accept", e);
                    }
                }
            }
        }, "stream-proxy");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket client) {
        Response resp = null;
        try {
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            HttpUrl local = HttpUrl.parse("http://127.0.0.1" + parts[1]);
            String upstream = local == null ? null : local.queryParameter("u");
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            if (upstream == null || HttpUrl.parse(upstream) == null) {
                out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
                return;
            }
            boolean head = "HEAD".equals(parts[0]);
            Request.Builder rb = new Request.Builder().url(upstream);
            String referer = local.queryParameter("r");
            if (referer != null && referer.length() > 0) rb.header("Referer", referer);
            String range = headers.get("range");
            if (range != null) rb.header("Range", range);
            if (head) rb.head();
            resp = App.get().mediaClient().newCall(rb.build()).execute();

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(resp.code()).append(' ')
                    .append(resp.message().length() > 0 ? resp.message() : "OK").append("\r\n");
            for (String h : PASS_HEADERS) {
                String v = resp.header(h);
                if (v != null) sb.append(h).append(": ").append(v).append("\r\n");
            }
            if (resp.header("Accept-Ranges") == null) sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            if (!head && resp.body() != null) {
                InputStream body = resp.body().byteStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = body.read(buf)) > 0) out.write(buf, 0, r);
            }
            out.flush();
        } catch (IOException e) {
            // Плеер закрывает соединение при каждой перемотке — это норма
        } finally {
            if (resp != null) resp.close();
            try {
                client.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 16384) throw new IOException("Слишком длинная строка запроса");
        }
        if (c < 0 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }
}
