package com.mineways.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给本地代理用的解析器（HTTPDNS）。
 *
 * <p>WebView 走系统解析器，我们插不进去；代理拿到 {@code CONNECT host:port} 之后由自己解析，
 * 于是"用谁的 DNS、用哪个 IP"就都归我们管了。
 *
 * <p>解析顺序：阿里 DoH（域名）→ 阿里 223.5.5.5（明文、IP 直连）→ DNSPod 119.29.29.29 → 系统解析。
 * 前两个都是国内公共 DNS，不需要任何账号/SDK；明文那两条是为系统解析器坏掉时兜底，
 * 真正的防投毒靠 TLS 证书（代理只负责连到 IP）。
 *
 * <p>只查 A 记录（IPv4）：国内不少网络 IPv6 半通不通，先避开这个坑。
 * 纯 Java，无 Android 依赖，电脑上可以直接跑同一个类做验证。
 */
public final class HttpDns {

    /** 解析结果。 */
    public static final class Result {
        public final List<InetAddress> ips;
        public final String source;

        Result(List<InetAddress> ips, String source) {
            this.ips = ips;
            this.source = source;
        }

        public boolean ok() {
            return ips != null && !ips.isEmpty();
        }
    }

    private static final class Entry {
        final List<InetAddress> ips;
        final String source;
        final long at;

        Entry(List<InetAddress> ips, String source, long at) {
            this.ips = ips;
            this.source = source;
            this.at = at;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 5 * 60_000L;
    /** 上游全挂时，旧结果最多再用一小时，总比直接失败强。 */
    private static final long STALE_MS = 60 * 60_000L;

    private static final Pattern RECORD = Pattern.compile("\\{[^{}]*}");
    private static final Pattern TYPE_A = Pattern.compile("\"type\"\\s*:\\s*1(?![0-9])");
    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CNAME_DATA = Pattern.compile("\"type\"\\s*:\\s*5[^{}]*\"data\"\\s*:\\s*\"([^\"]+)\"");

    private HttpDns() {
    }

    public static Result resolve(String host) {
        if (host == null || host.isEmpty()) return new Result(Collections.emptyList(), "empty");
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        byte[] literal = parseIpv4(h);
        if (literal != null) {
            try {
                return new Result(Collections.singletonList(InetAddress.getByAddress(literal)), "literal");
            } catch (Throwable ignored) {
            }
        }

        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(h);
        if (cached != null && now - cached.at < TTL_MS) return new Result(cached.ips, cached.source);

        Result r = aliDoh(h);
        if (r == null) r = aliPlain(h);
        if (r == null) r = dnspod(h);
        if (r == null) r = system(h);

        if (r != null && r.ok()) {
            CACHE.put(h, new Entry(r.ips, r.source, now));
            return r;
        }
        if (cached != null && now - cached.at < STALE_MS) {
            return new Result(cached.ips, cached.source + "(stale)");
        }
        return new Result(Collections.emptyList(), "fail");
    }

    /** 清空缓存（诊断页/网络切换时用）。 */
    public static void clear() {
        CACHE.clear();
    }

    // ------------------------------------------------------------ 上游

    private static Result aliDoh(String host) {
        return aliJson("https://dns.alidns.com/resolve?name=" + enc(host) + "&type=A", "ali-doh");
    }

    private static Result aliPlain(String host) {
        // 用 IP 直连，系统 DNS 坏了也能问
        return aliJson("http://223.5.5.5/resolve?name=" + enc(host) + "&type=A", "ali-223");
    }

    private static Result aliJson(String url, String source) {
        try {
            String text = get(url, 2600, 2600, "application/dns-json");
            if (text == null || !text.contains("\"Answer\"")) return null;
            List<InetAddress> ips = new ArrayList<>();
            Matcher m = RECORD.matcher(text);
            while (m.find()) {
                String rec = m.group();
                if (!TYPE_A.matcher(rec).find()) continue;
                Matcher d = DATA.matcher(rec);
                if (!d.find()) continue;
                InetAddress ip = ipv4(d.group(1));
                if (ip != null) ips.add(ip);
            }
            if (ips.isEmpty()) {
                // 只有 CNAME 时（部分域名会这样）把 CNAME 也当线索，交给系统再解一次
                Matcher c = CNAME_DATA.matcher(text);
                if (c.find()) return null;
                return null;
            }
            return new Result(ips, source);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Result dnspod(String host) {
        try {
            String text = get("http://119.29.29.29/d?dn=" + enc(host), 2200, 2200, null);
            if (text == null) return null;
            List<InetAddress> ips = new ArrayList<>();
            for (String part : text.trim().split("[;,\\s]+")) {
                InetAddress ip = ipv4(part);
                if (ip != null) ips.add(ip);
            }
            return ips.isEmpty() ? null : new Result(ips, "dnspod");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Result system(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            List<InetAddress> ips = new ArrayList<>();
            for (InetAddress a : all) {
                if (a instanceof java.net.Inet4Address) ips.add(a);
            }
            if (ips.isEmpty()) Collections.addAll(ips, all);
            return ips.isEmpty() ? null : new Result(ips, "system");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String get(String url, int connectMs, int readMs, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "MinewaysMobile/dns");
            if (accept != null) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 8 * 1024)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 64 * 1024) break;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    // ------------------------------------------------------------ IP 工具

    /** 只认点分十进制，避免 InetAddress.getByName 又去做一次 DNS。 */
    public static byte[] parseIpv4(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(p[i]);
            } catch (Throwable t) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    private static InetAddress ipv4(String s) {
        byte[] b = parseIpv4(s);
        if (b == null) return null;
        try {
            return InetAddress.getByAddress(b);
        } catch (Throwable t) {
            return null;
        }
    }
}
