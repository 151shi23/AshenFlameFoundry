package com.mineways.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只监听 127.0.0.1 的极简 HTTP 代理（CONNECT 隧道 + 明文转发）。
 *
 * <p>为什么自己写：LittleProxy 会拖进 Netty（包体 + 兼容性），而我们只需要做一件事 ——
 * 把 {@code CONNECT host:port} 里的 host 用自己的解析器（{@link HttpDns}）换成 IP，
 * 然后原样把字节泵过去。HTTPS 不解密、不装证书，WebView 的 TLS/证书校验完全不受影响。
 *
 * <p>纯 Java，无 Android 依赖：电脑上可以直接起一个实例，用 {@code curl -x} 验证。
 *
 * <p>流量：WebView 的 {@code ProxyController} 把请求指到这里，数据仍由本机直接连目标 IP，
 * 不经过任何中转服务器。
 */
public final class LocalProxy {

    /** 一条连接的结果，给诊断用（只记 host，不记路径/参数）。 */
    public interface Stats {
        void onRequest(String host, String ip, String source, int connectMs, boolean ok);
    }

    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_CONN = 96;
    private static final int CONNECT_TIMEOUT_MS = 4500;
    private static final int RESPONSE_IDLE_MS = 45_000;

    private final ServerSocket server;
    private final Stats stats;
    private final AtomicInteger active = new AtomicInteger();
    private final Thread acceptor;
    private volatile boolean running = true;

    private LocalProxy(ServerSocket server, Stats stats) {
        this.server = server;
        this.stats = stats;
        this.acceptor = new Thread(this::acceptLoop, "mw-proxy-accept");
        this.acceptor.setDaemon(true);
    }

    public static LocalProxy start(Stats stats) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        LocalProxy p = new LocalProxy(s, stats);
        p.acceptor.start();
        return p;
    }

    public int port() {
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (Throwable ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                if (active.incrementAndGet() > MAX_CONN) {
                    active.decrementAndGet();
                    close(client);
                    continue;
                }
                final Socket c = client;
                Thread t = new Thread(() -> {
                    try {
                        handle(c);
                    } catch (Throwable ignored) {
                    } finally {
                        close(c);
                        active.decrementAndGet();
                    }
                }, "mw-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (!running) return;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(client.getInputStream(), 16 * 1024);
        OutputStream out = client.getOutputStream();

        byte[] head = readHead(in);
        if (head == null) return;
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        if (lines.length == 0) return;

        String[] first = lines[0].split(" ");
        if (first.length < 2) return;
        String method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];

        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c > 0) {
                headers.add(new String[]{line.substring(0, c).trim(), line.substring(c + 1).trim()});
            }
        }

        if ("CONNECT".equals(method)) {
            tunnel(client, in, out, target);
        } else {
            forward(client, in, out, method, target, headers);
        }
    }

    // ------------------------------------------------------------ CONNECT

    private void tunnel(Socket client, InputStream cin, OutputStream cout, String hostPort)
            throws IOException {
        String host = hostPort;
        int port = 443;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end > 0) {
                host = hostPort.substring(1, end);
                int c = hostPort.indexOf(':', end);
                if (c > 0) port = parseInt(hostPort.substring(c + 1), 443);
            }
        } else {
            int c = hostPort.lastIndexOf(':');
            if (c > 0) {
                host = hostPort.substring(0, c);
                port = parseInt(hostPort.substring(c + 1), 443);
            }
        }

        long t0 = System.currentTimeMillis();
        HttpDns.Result r = HttpDns.resolve(host);
        Socket remote = null;
        String usedIp = "";
        if (r.ok()) {
            for (InetAddress a : r.ips) {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    remote = s;
                    usedIp = a.getHostAddress();
                    break;
                } catch (Throwable t) {
                    close(s);
                }
            }
        }
        int ms = (int) (System.currentTimeMillis() - t0);
        if (remote == null) {
            report(host, usedIp, r.source, ms, false);
            fail(cout, 502, "无法连接 " + host + "（" + r.source + "）");
            return;
        }
        report(host, usedIp, r.source, ms, true);

        cout.write("HTTP/1.1 200 Connection established\r\nProxy-Agent: Mineways\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        cout.flush();
        pump(client, cin, cout, remote);
    }

    // ------------------------------------------------------------ 明文转发

    private void forward(Socket client, InputStream cin, OutputStream cout,
                         String method, String target, List<String[]> headers) throws IOException {
        String host;
        String path;
        int port = 80;
        boolean absolute = target.startsWith("http://") || target.startsWith("https://");
        try {
            if (absolute) {
                URI u = new URI(target);
                if ("https".equalsIgnoreCase(u.getScheme())) {
                    fail(cout, 400, "HTTPS 请用 CONNECT");
                    return;
                }
                host = u.getHost();
                port = u.getPort() > 0 ? u.getPort() : 80;
                path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
                if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            } else {
                host = header(headers, "Host");
                path = target;
                if (host != null && host.contains(":")) {
                    int c = host.lastIndexOf(':');
                    port = parseInt(host.substring(c + 1), 80);
                    host = host.substring(0, c);
                }
            }
        } catch (Throwable t) {
            fail(cout, 400, "请求地址不对");
            return;
        }
        if (host == null || host.isEmpty()) {
            fail(cout, 400, "缺少 Host");
            return;
        }

        long t0 = System.currentTimeMillis();
        HttpDns.Result r = HttpDns.resolve(host);
        Socket remote = null;
        String usedIp = "";
        if (r.ok()) {
            for (InetAddress a : r.ips) {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);
                    remote = s;
                    usedIp = a.getHostAddress();
                    break;
                } catch (Throwable t) {
                    close(s);
                }
            }
        }
        int ms = (int) (System.currentTimeMillis() - t0);
        if (remote == null) {
            report(host, usedIp, r.source, ms, false);
            fail(cout, 502, "无法连接 " + host + "（" + r.source + "）");
            return;
        }
        report(host, usedIp, r.source, ms, true);

        String upgrade = header(headers, "Upgrade");
        boolean isUpgrade = upgrade != null && !upgrade.isEmpty();

        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean sawHost = false;
        for (String[] h : headers) {
            String name = h[0].toLowerCase(Locale.ROOT);
            if (name.equals("proxy-connection") || name.equals("proxy-authorization")
                    || name.equals("connection")) {
                continue;
            }
            if (name.equals("host")) sawHost = true;
            req.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        if (!sawHost) req.append("Host: ").append(host).append("\r\n");
        req.append(isUpgrade ? "Connection: Upgrade\r\n" : "Connection: close\r\n");
        req.append("\r\n");

        OutputStream rin = remote.getOutputStream();
        rin.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        rin.flush();

        String te = header(headers, "Transfer-Encoding");
        long len = parseLong(header(headers, "Content-Length"), -1);
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            copyChunked(cin, rin);
        } else if (len > 0) {
            copyN(cin, rin, len);
        }
        rin.flush();

        if (isUpgrade) {
            pump(client, cin, cout, remote);
            return;
        }

        try {
            remote.setSoTimeout(RESPONSE_IDLE_MS);
        } catch (Throwable ignored) {
        }
        InputStream resp = remote.getInputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = resp.read(buf)) > 0) {
            cout.write(buf, 0, n);
            cout.flush();
        }
        close(remote);
    }

    // ------------------------------------------------------------ 管道

    private static void pump(Socket client, InputStream cin, OutputStream cout, Socket remote) {
        Thread up = new Thread(() -> {
            try {
                copy(cin, remote.getOutputStream());
            } catch (Throwable ignored) {
            } finally {
                close(remote);
                close(client);
            }
        }, "mw-proxy-up");
        up.setDaemon(true);
        up.start();
        try {
            copy(remote.getInputStream(), cout);
        } catch (Throwable ignored) {
        } finally {
            close(remote);
            close(client);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) return;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static void copyChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null) return;
            out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            int semi = line.indexOf(';');
            String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
            long n;
            try {
                n = Long.parseLong(hex, 16);
            } catch (Throwable t) {
                return;
            }
            if (n == 0) {
                String t;
                while ((t = readLine(in)) != null) {
                    out.write((t + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    if (t.isEmpty()) break;
                }
                out.flush();
                return;
            }
            copyN(in, out, n);
            readLine(in); // 块尾 CRLF
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int last = 0;
        int b;
        while ((b = in.read()) >= 0) {
            out.write(b);
            last = (last << 8) | (b & 0xff);
            if (last == 0x0d0a0d0a) return out.toByteArray();
            if (out.size() > MAX_HEADER_BYTES) throw new IOException("header too large");
        }
        return out.size() == 0 ? null : out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
            if (out.size() > 4096) break;
        }
        if (b < 0 && out.size() == 0) return null;
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------ 工具

    private static String header(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) return h[1];
        }
        return null;
    }

    private static void fail(OutputStream out, int code, String msg) {
        try {
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " ProxyError\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private void report(String host, String ip, String source, int ms, boolean ok) {
        if (stats == null) return;
        try {
            stats.onRequest(host, ip, source, ms, ok);
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void close(Object o) {
        try {
            if (o instanceof Socket) ((Socket) o).close();
            else if (o instanceof ServerSocket) ((ServerSocket) o).close();
        } catch (Throwable ignored) {
        }
    }
}
