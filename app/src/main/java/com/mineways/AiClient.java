package com.mineways;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 软件端 AI 对话客户端（对应《软件端 AI 对话接口（App 接入）》）。
 *
 * <p><b>签名与 {@link ActivationClient} 完全同一套</b>，不另写一份协议：
 * <pre>
 *   body_hash = SHA256_HEX(body 原始字节)
 *   canonical = timestamp + "\n" + nonce + "\n" + body_hash
 *   X-Signature = HMAC_SHA256_HEX(APP_SECRET, canonical)   // 小写 hex
 * </pre>
 * 请求头 {@code User-Agent（合规）/ X-Client-Type: software / X-App-Id / X-Timestamp / X-Nonce /
 * X-Signature} 六个全必填 —— 缺 {@code X-Client-Type} 或缺合规 UA 会被社区网关当爬虫
 * （403 {@code BOT_DETECTED}），跟签名对不对没关系；{@code HttpURLConnection} 默认 UA 带
 * {@code Java/} 字样，必须显式覆盖。
 *
 * <p><b>请求体只序列化一次</b>：同一份 {@code json.getBytes("UTF-8")} 既拿去算 sha256，
 * 也原样写进连接 —— 文档明确要求「签名与发送用同一字符串，不得重新序列化」。
 *
 * <p><b>SSE 按行读</b>：用 {@link BufferedReader#readLine()} 而不是自己切 chunk，
 * 一个 TCP chunk 里的半行 / 多行都会被它拼好；只认 {@code data:} 前缀，
 * {@code [DONE]} 结束，其余 JSON 取 {@code choices[0].delta.content}。
 *
 * <p>回调统一切回主线程（Compose 的状态只能在主线程改），
 * 网络与解析全部在一条 daemon 线程上，调用方不需要自己开线程。
 */
public final class AiClient {

    private static final String APP_ID = BuildConfig.AFF_APP_ID;
    private static final String APP_SECRET = BuildConfig.AFF_APP_SECRET;
    private static final String CLIENT_TYPE = "software";

    /**
     * 软件端专用通道（文档《软件端 AI API 接入》§一）：与网页版 AI 完全隔离，
     * 没有论坛工具注入，人设由 App 下发。
     *
     * <p>2026-10-18 实测：正式域 {@code /api/app/ai/chat} 当时还 404、{@code /api/ai/chat} 通；
     * 开发域两个都在。所以**主走文档规定的软件端通道，404 再退回网页路由**并记住 ——
     * 服务端补上部署后客户端不用改代码。
     */
    private static final String PATH_DOC = "/api/app/ai/chat";
    private static final String PATH_LEGACY = "/api/ai/chat";
    private static volatile String preferredPath = PATH_DOC;

    /**
     * 网关反爬硬要求（文档 §2.2）：UA 必须 ≥10 字符且不含 {@code java/}、{@code httpclient}
     * 这些关键词 —— {@code HttpURLConnection} 的默认 UA 就是 {@code Java/17.x}，不设必然 403
     * {@code BOT_DETECTED}，跟签名没关系。
     */
    private static final String USER_AGENT = "MinewaysMobile/" + BuildConfig.VERSION_NAME
            + " (Android)";

    /**
     * 本轮要发的 system：客户端出厂人设 + 当前开放的工具有关协议（{@link AiTools#prompt()}），
     * 再按云控算 —— {@code ai_system} 整段替换（留空 = 干脆不发）、
     * {@code ai_system_extra} 在末尾追加规则。
     *
     * <p>文档 §4.4：不传 system 时模型会自称「Agnes-3.0-flash，由 Sapiens AI 开发」
     * （供应商出厂设定，不是平台注入），传了可 100% 覆盖。
     */
    static String systemForRound() {
        return AiTools.systemForThisRound();
    }

    private static final int CONNECT_TIMEOUT = 15000;

    /** 文档 §7.4：长回复 read 超时给 ≥120 秒；这是「两次读到数据之间」的上限，不是总时长。 */
    private static final int READ_TIMEOUT = 120000;

    /** 文档 §4.1：messages 最多 30 条、单条 content 超 8000 字符服务端会截断。 */
    private static final int MAX_MESSAGES = 30;
    private static final int MAX_CONTENT = 8000;

    private static final String NONCE_POOL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiClient() {
    }

    /** 一条对话消息。role 只允许 user / assistant。 */
    public static final class Msg {
        public final String role;
        public final String content;

        public Msg(String role, String content) {
            this.role = role;
            this.content = content == null ? "" : content;
        }
    }

    /** 回调全部在主线程。 */
    public interface Callback {
        /** 增量片段（可能一次一个词，也可能一次一句）。 */
        void onDelta(String piece);

        /** 正常结束。full 是完整回答（拿不到增量时也以它为准）。 */
        void onDone(String full, String model, int totalTokens);

        /**
         * 失败。httpStatus 为 0 表示本地/网络异常；
         * retryAfterSec 仅在 429 时有值。
         */
        void onError(String message, String code, int httpStatus, int retryAfterSec);

        /**
         * 工具事件里的图片地址（{@code generate_image} 的 {@code result.imageUrl}）。
         * 默认空实现，避免以后加事件时破坏已有调用方。
         */
        default void onImage(String url) {
        }
    }

    /** 一次请求的句柄，关面板 / 切页时 cancel 掉，避免连接吊着。 */
    public static final class Session {
        volatile boolean cancelled;
        volatile HttpURLConnection conn;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 发起一次流式对话。立即返回句柄，结果通过 {@link Callback} 回主线程。
     *
     * <p>{@code system} 人设在这里面自动带上（云控 {@code ai_system} 可改，空串 = 不发），
     * 调用方只管把历史 + 本轮消息按顺序送上来。
     *
     * @param messages 历史 + 本轮用户消息（顺序即对话顺序），超过 30 条这里自己截最近 30 条
     */
    public static Session chat(List<Msg> messages, Callback cb) {
        final Session session = new Session();
        Thread t = new Thread(() -> run(messages, cb, session), "mw-ai-chat");
        t.setDaemon(true);
        t.start();
        return session;
    }

    // ---------------------------------------------------------------- 执行

    private static void run(List<Msg> messages, Callback cb, Session s) {
        String base = ApiEnv.base();
        if (base == null || base.length() == 0
                || APP_ID == null || APP_ID.length() == 0
                || APP_SECRET == null || APP_SECRET.length() == 0) {
            post(() -> cb.onError("客户端凭证未配置（缺少 AFF_APP_ID / AFF_APP_SECRET）",
                    "NOT_CONFIGURED", 0, 0));
            return;
        }
        HttpURLConnection conn = null;
        try {
            String json = buildBody(messages);
            byte[] body = json.getBytes("UTF-8");

            String path = preferredPath;
            conn = open(path, body, s);
            int status = conn.getResponseCode();
            // 哪个 404 就换另一个，并记住（正式/开发两个路由的部署情况不一样）
            if (status == 404) {
                String other = PATH_DOC.equals(path) ? PATH_LEGACY : PATH_DOC;
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
                preferredPath = other;
                path = other;
                conn = open(path, body, s);
                status = conn.getResponseCode();
            }
            // 文档 §6.3/§7.8：502 是上游抖动，等 2.5 秒重试一次就够；签名类错误重试没意义
            if (status == 502 && !s.cancelled) {
                try {
                    conn.disconnect();
                    Thread.sleep(2500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                }
                if (!s.cancelled) {
                    conn = open(path, body, s);
                    status = conn.getResponseCode();
                }
            }
            if (status < 200 || status >= 300) {
                String text = readAll(conn.getErrorStream());
                failFromBody(text, status, cb);
                return;
            }

            String ctype = conn.getContentType();
            if (ctype != null && ctype.toLowerCase().contains("application/json")) {
                // 服务端没按流式回（例如被网关兜底成整包 JSON）——按非流式解析
                parseWhole(readAll(conn.getInputStream()), cb);
                return;
            }
            int streamed = stream(conn.getInputStream(), cb, s);
            if (streamed == 0 && !s.cancelled) {
                // 「200 但一个字都没有」是实测存在的形态（开发域 /api/app/ai/chat 就返回空包）。
                // 先原路由重试一次，再换另一条路由试一次；换通了就把偏好改过去，别让人干等。
                String orig = path;
                String alt = PATH_DOC.equals(orig) ? PATH_LEGACY : PATH_DOC;
                for (String p2 : new String[]{orig, alt}) {
                    try {
                        Thread.sleep(700L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    if (s.cancelled) {
                        return;
                    }
                    conn = open(p2, body, s);
                    int st2 = conn.getResponseCode();
                    if (st2 < 200 || st2 >= 300) {
                        failFromBody(readAll(conn.getErrorStream()), st2, cb);
                        return;
                    }
                    int again = stream(conn.getInputStream(), cb, s);
                    if (again != 0) {
                        if (again > 0 && !p2.equals(orig)) {
                            preferredPath = p2;
                        }
                        return;
                    }
                }
                post(() -> cb.onError("连上了但服务端一个字都没回（" + baseHost() + " 的 "
                        + preferredPath + " 是空响应），换环境再试", "EMPTY_STREAM", 200, 0));
            }
        } catch (Throwable t) {
            if (!s.cancelled) {
                String m = "网络异常：" + t.getClass().getSimpleName();
                post(() -> cb.onError(m, "NETWORK_ERROR", 0, 0));
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 打开一次连接并写完 body。每次调用都重新签名（nonce 一次一用）。 */
    private static HttpURLConnection open(String path, byte[] body, Session s) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomNonce(24);
        String bodyHash = sha256Hex(body);
        String canonical = ts + "\n" + nonce + "\n" + bodyHash;
        String signature = hmacSha256Hex(APP_SECRET, canonical);

        HttpURLConnection conn = (HttpURLConnection) new URL(ApiEnv.base() + path).openConnection();
        s.conn = conn;
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("X-Client-Type", CLIENT_TYPE);
        conn.setRequestProperty("X-App-Id", APP_ID);
        conn.setRequestProperty("X-Timestamp", ts);
        conn.setRequestProperty("X-Nonce", nonce);
        conn.setRequestProperty("X-Signature", signature);

        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        os.close();
        return conn;
    }

    /** 请求体：只序列化这一次，返回值直接 getBytes 用于签名与发送。 */
    private static String buildBody(List<Msg> messages) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray arr = new JSONArray();
        String sys = systemForRound();
        if (sys.length() > 0) {
            // 文档 §4.4：人设由软件端下发，不传就露出供应商出厂的「Agnes / Sapiens AI」自称
            body.put("system", sys.length() > 4000 ? sys.substring(0, 4000) : sys);
        }
        List<Msg> recent = messages.size() > MAX_MESSAGES
                ? messages.subList(messages.size() - MAX_MESSAGES, messages.size()) : messages;
        for (Msg m : recent) {
            String content = m.content == null ? "" : m.content;
            if (content.length() > MAX_CONTENT) {
                content = content.substring(0, MAX_CONTENT);
            }
            if (content.length() == 0) {
                continue;   // 文档 §4.1：content 必须非空，空条目会被判 INVALID_MESSAGES
            }
            JSONObject o = new JSONObject();
            o.put("role", "assistant".equals(m.role) ? "assistant" : "user");
            o.put("content", content);
            arr.put(o);
        }
        body.put("messages", arr);
        // 采样与长度上限也归云控管（ai_temp / ai_max_tokens），越界值在 AiPolicy 里先钳到接口允许的区间
        body.put("temperature", AiPolicy.temperature(AiTools.FLAGS));
        body.put("max_tokens", AiPolicy.maxTokens(AiTools.FLAGS));
        body.put("stream", true);
        // 文档 §7.7：App 不许指定模型，请求里也就不带 model 字段
        return body.toString();
    }

    // ---------------------------------------------------------------- SSE

    /**
     * 读 SSE。
     *
     * @return 1 = 有内容并已回调 onDone；0 = 流正常结束但一个字都没有（空流）；
     *         -1 = 已回调 onError / 已取消（调用方不要再兜底）
     */
    private static int stream(InputStream in, Callback cb, Session s) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder full = new StringBuilder();
        String model = "";
        int totalTokens = 0;
        String line;
        while (!s.cancelled && (line = br.readLine()) != null) {
            String t = line.trim();
            if (t.length() == 0 || !t.startsWith("data:")) {
                continue;   // 空行是事件分隔，注释行（: ping）忽略
            }
            String payload = t.substring(5).trim();
            if ("[DONE]".equals(payload)) {
                break;
            }
            JSONObject o;
            try {
                o = new JSONObject(payload);
            } catch (Throwable bad) {
                continue;   // 半截 JSON / 心跳：跳过，不打断整条流
            }
            String m = o.optString("model", "");
            if (m.length() > 0) {
                model = m;
            }
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) {
                totalTokens = usage.optInt("total_tokens", totalTokens);
            }

            // 工具事件：{"name":"generate_image","result":{"success":true,"imageUrl":"…"}}
            JSONObject result = o.optJSONObject("result");
            if (result != null) {
                String img = result.optString("imageUrl", result.optString("url", ""));
                if (img.length() > 0) {
                    final String u = img;
                    post(() -> cb.onImage(u));
                }
            }
            String piece = "";
            JSONArray choices = o.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                // 文档格式：OpenAI 兼容
                JSONObject c0 = choices.optJSONObject(0);
                JSONObject delta = c0 == null ? null : c0.optJSONObject("delta");
                piece = delta == null ? "" : delta.optString("content", "");
            } else {
                // 线上实际格式：{"message":"...","type":"status|content|error|done"}
                String type = o.optString("type", "");
                if ("done".equals(type)) {
                    break;
                }
                if ("error".equals(type)) {
                    String msg = o.optString("message", "AI 服务暂时不可用");
                    post(() -> cb.onError(msg, "AI_UPSTREAM_ERROR", 200, 0));
                    return -1;
                }
                if ("status".equals(type)) {
                    continue;
                }
                // 实测内容事件是 {"content":"…","type":"content"}；message 留给状态/错误
                String c = o.optString("content", "");
                piece = c.length() > 0 ? c : o.optString("message", "");
            }
            if (piece.length() > 0) {
                full.append(piece);
                final String p = piece;
                post(() -> cb.onDelta(p));
            }
        }
        if (s.cancelled) {
            return -1;
        }
        if (full.length() == 0) {
            return 0;
        }
        final String f = full.toString();
        final String mm = model;
        final int tt = totalTokens;
        post(() -> cb.onDone(f, mm, tt));
        return 1;
    }

    /** 非流式兜底：文档格式 {success,data:{content,...}} 或线上 {message,type}。 */
    private static void parseWhole(String text, Callback cb) {
        try {
            JSONObject o = new JSONObject(text);
            JSONObject data = o.optJSONObject("data");
            // 线上实际格式：{"message":"...","type":"content|error"}
            if (data == null && o.has("type") && !o.has("success")) {
                String type = o.optString("type", "");
                String msg = "error".equals(type)
                        ? o.optString("message", "")
                        : o.optString("content", o.optString("message", ""));
                if ("error".equals(type)) {
                    final String em = msg.length() > 0 ? msg : "AI 服务暂时不可用";
                    post(() -> cb.onError(em, "AI_UPSTREAM_ERROR", 200, 0));
                } else if (msg.length() > 0) {
                    final String c = msg;
                    post(() -> cb.onDone(c, "", 0));
                } else {
                    post(() -> cb.onError("服务端返回格式异常", "BAD_RESPONSE", 200, 0));
                }
                return;
            }
            if (!o.optBoolean("success", false)) {
                failFromObject(o, 200, cb);
                return;
            }
            if (data == null) {
                post(() -> cb.onError("服务端返回格式异常", "BAD_RESPONSE", 200, 0));
                return;
            }
            String content = data.optString("content", "");
            String model = data.optString("model", "");
            int tokens = 0;
            JSONObject usage = data.optJSONObject("usage");
            if (usage != null) {
                tokens = usage.optInt("total_tokens", 0);
            }
            final String c = content;
            final String m = model;
            final int t = tokens;
            post(() -> cb.onDone(c, m, t));
        } catch (Throwable e) {
            post(() -> cb.onError("响应解析失败", "BAD_RESPONSE", 200, 0));
        }
    }

    // ---------------------------------------------------------------- 错误

    private static void failFromBody(String text, int status, Callback cb) {
        if (text != null && text.length() > 0) {
            try {
                failFromObject(new JSONObject(text), status, cb);
                return;
            } catch (Throwable ignored) {
            }
        }
        final String m = defaultMessage(status, "");
        post(() -> cb.onError(m, status == 429 ? "RATE_LIMITED" : "HTTP_" + status, status, 0));
    }

    private static void failFromObject(JSONObject o, int status, Callback cb) {
        String code = o.optString("code", o.optString("error", ""));
        String message = o.optString("message", "");
        int retryAfter = o.optInt("retry_after", 0);
        if (message.length() == 0) {
            message = defaultMessage(status, code);
        }
        final String c = code;
        final String m = message;
        final int ra = retryAfter;
        post(() -> cb.onError(m, c, status, ra));
    }

    /** 文案按文档 §6 的错误码总表给，别一律「请求失败」——用户看不见日志，界面就是唯一出口。 */
    private static String defaultMessage(int status, String code) {
        if ("BOT_DETECTED".equals(code) || status == 403 && code.length() == 0) {
            return "被网关当成爬虫拦下了（客户端 User-Agent 不合规）";
        }
        if ("MISSING_SIGNATURE".equals(code)) {
            return "签名头没带全（客户端 bug，请反馈）";
        }
        if ("INVALID_APP_ID".equals(code)) {
            return "应用 ID 无效（客户端凭证没配置好）";
        }
        if ("APP_DISABLED".equals(code)) {
            return "这个应用被服务端停用了，联系管理方";
        }
        if ("TIMESTAMP_EXPIRED".equals(code)) {
            return "手机时间不准（与服务端差超过 5 分钟），去系统设置里打开自动时间";
        }
        if ("INVALID_NONCE".equals(code) || "REPLAY_DETECTED".equals(code)) {
            return "请求随机串不合规或被当成重放，直接再问一次";
        }
        if ("SIGNATURE_MISMATCH".equals(code)) {
            return "签名对不上（多半是设备时间跳了或客户端版本过旧，重启 App 再试）";
        }
        if ("INVALID_BODY".equals(code) || "INVALID_MESSAGES".equals(code)) {
            return "这条消息发不出去（内容为空或格式不对）";
        }
        if (status == 429 || "RATE_LIMITED".equals(code)) {
            return "请求太频繁，稍等一会儿再试";
        }
        if (status == 401) {
            return "签名校验失败（客户端凭证或设备时间不对）";
        }
        if (status == 502 || "AI_UPSTREAM_ERROR".equals(code)) {
            return "AI 上游繁忙或临时下线，已自动重试过一次，再问一次试试";
        }
        if (status >= 500) {
            return "服务端开小差了，请稍后重试";
        }
        return "请求失败（HTTP " + status + "）";
    }

    // ---------------------------------------------------------------- 原语

    private static void post(Runnable r) {
        MAIN.post(r);
    }

    /** 错误文案里带上域名与环境名：空响应到底是正式还是开发环境出的，一眼能分辨。 */
    private static String baseHost() {
        String b = ApiEnv.base() == null ? "" : ApiEnv.base();
        int i = b.indexOf("://");
        if (i >= 0) {
            b = b.substring(i + 3);
        }
        int j = b.indexOf('/');
        if (j >= 0) {
            b = b.substring(0, j);
        }
        return b + "·" + ApiEnv.label();
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String randomNonce(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NONCE_POOL.charAt(RANDOM.nextInt(NONCE_POOL.length())));
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(data));
    }

    private static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        return toHex(mac.doFinal(data.getBytes("UTF-8")));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) {
                sb.append('0');
            }
            sb.append(h);
        }
        return sb.toString();
    }
}
