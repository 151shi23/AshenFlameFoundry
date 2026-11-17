package com.mineways.music;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 音源给的是「跳转端点」（{@code ?type=url}），真实音频在几跳之外，而这一跳常常跳到明文 http。
 *
 * <p>直接把端址交给 MediaPlayer 是不行的：App 的 network_security_config 全局禁明文，被策略拦下来
 * framework 只回一个 {@code 1/-2147483648}（UNKNOWN_ERROR），用户看到的就是「播放失败」四个字，
 * 谁也猜不到是协议问题。所以这里先用普通连接把跳转跟到底，拿到最终地址后：
 * 明文就换 https 试一次（实测 {@code fsios.tx.kugou.com} 同一串路径 https 直接 200），
 * 并把整条链路写成一句话交给界面显示。
 *
 * <p>只用 java.net，不碰 android.*，所以在桌面 JDK 里也能真跑一遍（{@code MusicResolveMain} 那条路）。
 */
public final class MusicResolve {

    /** 跟进几跳。音源基本一跳，留点余量防环。 */
    private static final int MAX_HOPS = 5;

    public static final class Result {
        public final String url;
        public final String note;

        Result(String url, String note) {
            this.url = url;
            this.note = note;
        }
    }

    private MusicResolve() {
    }

    /**
     * @param endpoint 音源给的 {@code type=url} 端址（可能 302）
     * @param ua       与 MediaPlayer 完全一致的 UA —— CDN 若按 UA 发token，两次请求必须同口径
     */
    public static Result audioUrl(String endpoint, String ua) throws Exception {
        // 酷狗：Meting 实例的 type=url 现在回空 HTML（实测 200 / 0 字节），
        // 但酷狗自己的 mobile 接口还能给出直链（免费曲有效，VIP 曲会明确说没有地址）。
        String kugouHash = kugouHash(endpoint);
        if (kugouHash != null) {
            return kugouAudio(kugouHash, ua);
        }
        String current = endpoint;
        StringBuilder chain = new StringBuilder();
        String finalUrl = null;
        String type = "";
        int code = 0;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = open(current, ua, true);
            int c = conn.getResponseCode();
            String loc = c >= 300 && c < 400 ? conn.getHeaderField("Location") : null;
            String ct = conn.getContentType();
            conn.disconnect();
            if (c >= 400) {
                // 有些站不认 Range（416/403），退回不带 Range 再问一次，别把好好的源判死
                conn = open(current, ua, false);
                c = conn.getResponseCode();
                loc = c >= 300 && c < 400 ? conn.getHeaderField("Location") : null;
                ct = conn.getContentType();
                conn.disconnect();
            }
            chain.append(hop == 0 ? "" : " → ").append(c);
            if (loc == null || loc.length() == 0) {
                code = c;
                finalUrl = current;
                type = ct == null ? "" : ct;
                break;
            }
            current = join(current, loc);
        }
        if (finalUrl == null) {
            throw new Exception("音源跳转超过 " + MAX_HOPS + " 跳（" + chain + "）");
        }
        if (code < 200 || code >= 400) {
            throw new Exception("音源取地址失败 HTTP " + code + "（" + chain + "）");
        }
        // 「200 但回的是 JSON/HTML」是最坑的一种：MediaPlayer 拿到只会吐 1/-2147483648。
        // 典型场景是这条 id 在源上取不到地址，接口直接回一个 [] 或错误页
        if (isTextish(type)) {
            throw new Exception("音源没给出音频地址（HTTP " + code + " 回的是 " + type
                    + "，这条可能是别人家的 vip / 下架曲）");
        }
        if (!isPlainHttp(finalUrl)) {
            return new Result(finalUrl, "直连 " + host(finalUrl) + "（" + chain + " " + type + "）");
        }
        // 明文：先换 https 验一次。换不动就照原样交回去 —— 那时被安全策略拦，
        // 也比在这里偷偷放开明文强
        String secure = httpsOf(finalUrl);
        int sc = status(secure, ua);
        if (sc >= 200 && sc < 400) {
            return new Result(secure, "音源跳到明文，已改走 https：" + host(secure) + "（"
                    + chain + " → " + sc + "）");
        }
        return new Result(finalUrl, "音源只有明文地址，https 试不了（HTTP " + sc + "）：" + host(finalUrl));
    }

    /** 从 Meting 端址里认出酷狗曲目（server=kugou&type=url&id=&lt;hash&gt;）。 */
    static String kugouHash(String endpoint) {
        if (endpoint == null) return null;
        String e = endpoint.toLowerCase(Locale.ROOT);
        if (!e.contains("server=kugou") || !e.contains("type=url")) return null;
        int id = e.indexOf("id=");
        if (id < 0) return null;
        int end = e.indexOf('&', id);
        String h = endpoint.substring(id + 3, end < 0 ? endpoint.length() : end);
        return h.matches("[0-9a-zA-Z]{16,40}") ? h : null;
    }

    /**
     * 酷狗自己的 mobile 接口。免费曲会给 {@code url}；会员/下架曲回空字符串 ——
     * 这时给一句人能看懂的话，而不是笼统的「播放失败」。
     */
    private static Result kugouAudio(String hash, String ua) throws Exception {
        String api = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", ua);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("酷狗取地址失败 HTTP " + code);
            String json = new String(readAll(conn.getInputStream(), 512 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8);
            String url = field(json, "url");
            if (url.isEmpty()) {
                throw new Exception("酷狗：这首歌没有可播地址（多半是会员/版权限制）");
            }
            url = url.replace("\\/", "/");
            if (isPlainHttp(url)) {
                String secure = httpsOf(url);
                int sc = status(secure, ua);
                if (sc >= 200 && sc < 400) {
                    return new Result(secure, "酷狗直连（已把明文换成 https）");
                }
            }
            return new Result(url, "酷狗直连 " + host(url));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static byte[] readAll(java.io.InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) break;
        }
        return out.toByteArray();
    }

    /** 音频的 Content-Type 五花八门（audio/mpeg、octet-stream…），只把明显的「不是音频」挑出来。 */
    public static boolean isTextish(String contentType) {        if (contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.contains("json") || t.contains("html") || t.startsWith("text/") || t.contains("xml");
    }

    /**
     * JDK 的异常信息给用户看等于没说（{@code api.qijieya.cn} / {@code Connect timed out}），
     * 这里翻成一句能读懂的话；翻不出来就原样带出，绝不静默。
     */
    public static String why(Throwable t) {
        if (t == null) {
            return "未知原因";
        }
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (t instanceof java.net.UnknownHostException) {
            return "域名解析不了：" + m + "（这台网络到不了该音源，换一个）";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "连接超时：" + m;
        }
        if (t instanceof java.io.FileNotFoundException) {
            return "音源说这个地址不存在（404）：" + m;
        }
        String cls = t.getClass().getSimpleName();
        return m.length() == 0 ? cls : cls + "：" + m;
    }

    public static boolean isPlainHttp(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /** 只换协议，路径与签名 token 一个字都不动（实测酷狗同一串路径 https 直接 200）。 */
    public static String httpsOf(String url) {
        return isPlainHttp(url) ? "https://" + url.substring("http://".length()) : url;
    }

    private static HttpURLConnection open(String url, String ua, boolean ranged) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        // 自己跟就看不到 Location 了，也就没法判断是不是明文；这里一律手动跟
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", ua);
        if (ranged) {
            conn.setRequestProperty("Range", "bytes=0-1");
        }
        return conn;
    }

    /** 只问状态码。带 Range 被拒的站（416/403）退回去不带 Range 再问一次。 */
    private static int status(String url, String ua) {
        try {
            HttpURLConnection conn = open(url, ua, true);
            int c = conn.getResponseCode();
            conn.disconnect();
            if (c >= 400) {
                conn = open(url, ua, false);
                int c2 = conn.getResponseCode();
                conn.disconnect();
                return c2;
            }
            return c;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Location 可能是绝对址，也可能只是 {@code /xxx.mp3} 这种相对址。 */
    public static String join(String base, String loc) {
        String l = loc.trim();
        if (l.startsWith("//")) {
            return base.regionMatches(true, 0, "https:", 0, 6) ? "https:" + l : "http:" + l;
        }
        if (l.regionMatches(true, 0, "http://", 0, 7) || l.regionMatches(true, 0, "https://", 0, 8)) {
            return l;
        }
        int slash = base.indexOf("//");
        int pathStart = slash < 0 ? -1 : base.indexOf('/', slash + 2);
        String origin = pathStart < 0 ? base : base.substring(0, pathStart);
        return l.startsWith("/") ? origin + l : origin + "/" + l;
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return url;
        }
    }
}
