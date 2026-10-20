package io.github.felixlp.libkat.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

import javax.net.SocketFactory;

import okhttp3.Dns;

/**
 * SOCKS5 (RFC 1928) с логином/паролем (RFC 1929).
 * В Android java.net умеет только SOCKS4, поэтому рукопожатие сделано вручную.
 * Имена хостов резолвит сам прокси — это обходит и DNS-блокировки.
 */
public final class Socks5SocketFactory extends SocketFactory {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] PLACEHOLDER = {0, 0, 0, 0};

    private final ProxyConfig cfg;

    public Socks5SocketFactory(ProxyConfig cfg) {
        this.cfg = cfg;
    }

    /** DNS-заглушка: адрес 0.0.0.0 с сохранённым именем, которое потом уйдёт прокси. */
    public static final Dns REMOTE_DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
            return Collections.singletonList(InetAddress.getByAddress(hostname, PLACEHOLDER));
        }
    };

    @Override
    public Socket createSocket() {
        return new Socks5Socket(cfg);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(InetSocketAddress.createUnresolved(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address, port);
    }

    static final class Socks5Socket extends Socket {
        private final ProxyConfig cfg;

        Socks5Socket(ProxyConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) throw new IOException("Неподдерживаемый адрес: " + endpoint);
            InetSocketAddress target = (InetSocketAddress) endpoint;
            try {
                super.connect(new InetSocketAddress(cfg.host, cfg.port), timeout);
            } catch (IOException e) {
                throw new IOException("Прокси " + cfg.host + ":" + cfg.port + " недоступен: " + e.getMessage(), e);
            }
            int oldTimeout = getSoTimeout();
            setSoTimeout(timeout > 0 ? timeout : 30000);
            try {
                handshake(target);
            } catch (IOException e) {
                close();
                throw e;
            }
            setSoTimeout(oldTimeout);
        }

        private void handshake(InetSocketAddress target) throws IOException {
            OutputStream out = getOutputStream();
            DataInputStream in = new DataInputStream(getInputStream());

            if (cfg.hasAuth()) {
                out.write(new byte[]{5, 2, 0, 2});
            } else {
                out.write(new byte[]{5, 1, 0});
            }
            out.flush();
            int ver = in.readUnsignedByte();
            int method = in.readUnsignedByte();
            if (ver != 5) throw new IOException("Это не SOCKS5-прокси (версия " + ver + ")");
            if (method == 2) {
                byte[] u = cfg.user.getBytes(UTF8);
                byte[] p = (cfg.password == null ? "" : cfg.password).getBytes(UTF8);
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(1);
                auth.write(u.length);
                auth.write(u);
                auth.write(p.length);
                auth.write(p);
                out.write(auth.toByteArray());
                out.flush();
                in.readUnsignedByte();
                if (in.readUnsignedByte() != 0) throw new IOException("Прокси отклонил логин/пароль");
            } else if (method == 0xff) {
                throw new IOException(cfg.hasAuth()
                        ? "Прокси не принимает этот способ входа"
                        : "Прокси требует логин и пароль");
            } else if (method != 0) {
                throw new IOException("Прокси выбрал неизвестный способ входа: " + method);
            }

            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0});
            String host = hostOf(target);
            if (host != null) {
                byte[] h = host.getBytes(UTF8);
                req.write(3);
                req.write(h.length);
                req.write(h);
            } else {
                byte[] addr = target.getAddress().getAddress();
                req.write(addr.length == 4 ? 1 : 4);
                req.write(addr);
            }
            req.write((target.getPort() >> 8) & 0xff);
            req.write(target.getPort() & 0xff);
            out.write(req.toByteArray());
            out.flush();

            in.readUnsignedByte();
            int rep = in.readUnsignedByte();
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();
            if (rep != 0) throw new IOException("Прокси не смог подключиться к " + target.getPort() + ": " + reply(rep));
            int skip;
            if (atyp == 1) skip = 4;
            else if (atyp == 4) skip = 16;
            else if (atyp == 3) skip = in.readUnsignedByte();
            else throw new IOException("Странный ответ прокси (atyp " + atyp + ")");
            in.readFully(new byte[skip + 2]);
        }

        /** Имя хоста для удалённого резолва или null, если есть настоящий IP. */
        private static String hostOf(InetSocketAddress target) {
            InetAddress a = target.getAddress();
            if (a == null) return target.getHostName();
            byte[] raw = a.getAddress();
            if (raw.length == 4 && raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0) {
                return a.getHostName();
            }
            return null;
        }

        private static String reply(int rep) {
            switch (rep) {
                case 1: return "общая ошибка";
                case 2: return "запрещено правилами прокси";
                case 3: return "сеть недоступна";
                case 4: return "хост недоступен";
                case 5: return "соединение отклонено";
                case 6: return "истёк TTL";
                case 7: return "команда не поддерживается";
                case 8: return "тип адреса не поддерживается";
                default: return "код " + rep;
            }
        }
    }
}
