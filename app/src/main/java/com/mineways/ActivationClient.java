package com.mineways;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 激活码后端客户端（对应《激活码系统 — 安卓 App 接入文档》）。
 *
 * <p>签名（所有 /api/app/activation/* 通用）：
 * <pre>
 *   body_hash = SHA256_HEX(body UTF-8)
 *   canonical = timestamp + "\n" + nonce + "\n" + body_hash
 *   signature = HMAC_SHA256_HEX(APP_SECRET, canonical)      // 小写 hex
 * </pre>
 * 请求头：X-Client-Type / X-App-Id / X-Timestamp / X-Nonce / X-Signature（5 个全必填）。
 *
 * <p>v2 接入差异（签名协议与 v1 相同，改的是这几处）：
 * <ul>
 *   <li>必须带 {@code X-Client-Type: software}：社区网关靠这个头把软件端流量摘出去，
 *       缺了会走反爬风控（{@code BOT_DETECTED}）与额外限流；</li>
 *   <li>新增激活码状态 {@code revoked}（管理员作废）→ verify 返回 {@code CODE_REVOKED}，
 *       后台可恢复该码；</li>
 *   <li>{@code check} 的可用性看 {@code valid} 与 {@code redeemable} 两个字段，不能只看
 *       {@code success}（码不存在时服务端照样返回 {@code success:true, valid:false}）；</li>
 *   <li>本地预校验按文档给的 5-5-5 正则，并顺手把用户粘进来的大小写/缺横杠修成规范格式。</li>
 * </ul>
 *
 * <p>核销时序：verify（验证并锁定）→ 执行功能 → confirm（成功扣次）/ release（失败不扣）。
 * 凭证来自 BuildConfig（由未提交的 local.properties 注入，不进 git）。
 */
public final class ActivationClient {

    private static final String BASE_URL = BuildConfig.AFF_BASE_URL;
    private static final String APP_ID = BuildConfig.AFF_APP_ID;
    private static final String APP_SECRET = BuildConfig.AFF_APP_SECRET;

    /** v2 必填：社区网关靠它识别合法软件端流量（缺失会被反爬/限流拦截）。 */
    private static final String CLIENT_TYPE = "software";

    /** 激活码规范格式：5-5-5 大写，字母表去掉易混淆的 I/O/0/1。 */
    private static final java.util.regex.Pattern CODE_RE = java.util.regex.Pattern.compile(
            "^[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}$");

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;
    private static final String NONCE_POOL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ActivationClient() {
    }

    /** 统一结果。 */
    public static final class Result {
        public boolean ok;           // 业务是否成功
        public String code = "";     // 服务端错误码：INVALID_FORMAT / CODE_NOT_FOUND / ALREADY_REDEEMED / LOCK_IN_PROGRESS ...
        public String message = "";  // 服务端中文说明
        public String token = "";    // verify 成功时的 operation_token
        public String status = "";   // check 的状态：active / locked / redeemed / revoked / NOT_FOUND
        public boolean valid;        // check：码是否存在且格式合法
        public boolean redeemable;   // check：是否还能用（未核销、未作废、未锁定）
        public long lockRemainingMs; // LOCK_IN_PROGRESS 时剩余锁定时长
        public boolean idempotent;   // confirm 重复调用（服务端幂等，不二次扣码）
        public boolean networkError; // 网络/解析失败（与业务失败区分）
        /**
         * 服务端返回的原始 JSON。资源清单/下载直链这类接口把整包结构原样转给页面，
         * 不再逐字段建模 —— 字段是服务端文档里的，加字段不用动这里。
         */
        public String raw = "";

        public boolean isConfigured() {
            return !networkError || (message != null && message.length() > 0);
        }
    }

    /**
     * 只读校验，不锁定。
     *
     * <p>注意判定口径：这个接口即使码不存在也返回 {@code success:true}，能不能用要看
     * {@code valid && redeemable}；这里把 {@code status} 归一成第 4 节错误码表，
     * 上层只管看 {@code ok} 和 {@code code}。v2 新增的 {@code revoked} 也在这里露出。
     */
    public static Result check(String code) {
        Result r = post("/api/app/activation/check", codePayload(code));
        if (r.networkError) {
            return r;
        }
        if (r.ok && !(r.valid && r.redeemable)) {
            r.ok = false;
            r.code = statusToCode(r);
        }
        return r;
    }

    /** 验证并锁定（两阶段第一阶段）。必须拿到 operation_token 才算成功。 */
    public static Result verify(String code) {
        Result r = post("/api/app/activation/verify", codePayload(code));
        if (r.networkError) {
            return r;
        }
        if (r.ok && r.token.length() == 0) {
            r.ok = false;
            r.code = "NO_TOKEN";
            r.message = "服务端没有返回核销令牌，请稍后重试";
        }
        return r;
    }

    /** {@code check} 的 status → 统一错误码（第 4 节错误码表）。 */
    private static String statusToCode(Result r) {
        String s = r.status == null ? "" : r.status.trim().toLowerCase();
        if (s.startsWith("revoked")) {
            return "CODE_REVOKED";
        }
        if (s.startsWith("redeemed")) {
            return "ALREADY_REDEEMED";
        }
        if (s.startsWith("locked")) {
            return "LOCK_IN_PROGRESS";
        }
        if (s.startsWith("not")) {   // NOT_FOUND / not_found
            return "CODE_NOT_FOUND";
        }
        if (r.code.length() > 0) {
            return r.code;
        }
        return r.valid ? "ALREADY_REDEEMED" : "CODE_NOT_FOUND";
    }

    /** 功能执行成功后调用：真正扣掉一次。 */
    public static Result confirm(String token) {
        return post("/api/app/activation/confirm", tokenPayload(token, ""));
    }

    /** 功能执行失败/取消时调用：释放，不扣次。 */
    /**
     * 软件资源清单（字体等）。签名通道与激活码完全一致；整包 JSON 放在
     * {@link Result#raw} 里，由上层原样转给 WebView，字段增删不用动这里。
     */
    public static Result resourcesList(String category) {
        String body = (category == null || category.length() == 0)
                ? "{}"
                : "{\"category\":\"" + jsonEscape(category) + "\"}";
        return post("/api/app/resources/list", body);
    }

    /**
     * 取某个资源的下载直链（服务端发 24 小时预签名 URL，客户端直连对象存储下载）。
     * 带上 app_version_code：服务端下载日志按它统计，排查"某个版本下不动"时才有的对。
     */
    public static Result resourceDownload(String slug) {
        return post("/api/app/resources/download",
                "{\"slug\":\"" + jsonEscape(slug) + "\",\"app_version_code\":"
                        + BuildConfig.VERSION_CODE + "}");
    }

    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static Result release(String token, String reason) {
        return post("/api/app/activation/release", tokenPayload(token, reason == null ? "cancelled" : reason));
    }

    /**
     * 把用户粘进来的码修成规范格式：去掉空白与杂字符 → 转大写 →
     * 正好 15 位合法字符时重新按 5-5-5 断开。修不动就原样返回，交给服务端判。
     */
    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < code.length(); i++) {
            char ch = Character.toUpperCase(code.charAt(i));
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
            }
        }
        if (sb.length() == 15) {
            String flat = sb.toString();
            String grouped = flat.substring(0, 5) + "-" + flat.substring(5, 10) + "-" + flat.substring(10);
            if (CODE_RE.matcher(grouped).matches()) {
                return grouped;
            }
        }
        return code.trim();
    }

    /** 本地可做的格式预检，省一次网络往返（v2 文档第 5 节第 1 条）。 */
    public static boolean looksLikeCode(String code) {
        if (code == null) {
            return false;
        }
        if (CODE_RE.matcher(normalize(code)).matches()) {
            return true;   // 规范格式，直接放行
        }
        // 非规范格式（老码 / 以后换规则）：只要字符集看着像码就交给服务端判，
        // 免得一条本地正则把人挡在门外
        String c = code.trim();
        if (c.length() < 6 || c.length() > 64) {
            return false;
        }
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 带上下文的提示：网络异常、锁定剩余时间这类要拿响应字段才能说清的情况走这里。 */
    public static String friendlyError(Result r) {
        if (r == null) {
            return "激活失败，请稍后重试";
        }
        if (r.networkError) {
            return "网络异常，无法连接激活服务器，请检查网络后重试";
        }
        if ("LOCK_IN_PROGRESS".equals(r.code) && r.lockRemainingMs > 0) {
            long min = Math.max(1, Math.round(r.lockRemainingMs / 60000.0));
            return "这个激活码正在被使用中，约 " + min + " 分钟后可再试";
        }
        return friendlyError(r.code, r.message);
    }

    /** 错误码 -> 中文提示（覆盖 v2 文档第 4 节业务码与第 4.5 节签名层错误码）。 */
    public static String friendlyError(String code, String serverMessage) {
        if (code == null) {
            code = "";
        }
        if ("INVALID_FORMAT".equals(code)) {
            return "激活码格式不正确，请检查是否复制完整（形如 ABCDE-FGHJK-LMNPQ）";
        }
        if ("CODE_NOT_FOUND".equals(code)) {
            return "这个激活码不存在，可能来自其它社区";
        }
        if ("ALREADY_REDEEMED".equals(code)) {
            return "这个激活码已经被用过了（一个码只能用一次）";
        }
        if ("CODE_REVOKED".equals(code)) {
            return "这个激活码已被管理员作废，请联系管理员（后台可以恢复）";
        }
        if ("LOCK_IN_PROGRESS".equals(code)) {
            return "这个激活码正在被使用中，请稍后再试";
        }
        if ("NO_TOKEN".equals(code)) {
            return "服务端没有返回核销令牌，请稍后重试";
        }
        if ("ALREADY_RELEASED".equals(code)) {
            return "上次操作已回滚，激活码仍然可用，请重新验证";
        }
        if ("TIMESTAMP_EXPIRED".equals(code)) {
            return "手机时间和服务器差了太多，请把系统时间设为「自动校准」后重试";
        }
        if ("RATE_LIMITED".equals(code)) {
            return "请求太频繁，请稍后再试";
        }
        if ("TOO_MANY_FAILURES".equals(code)) {
            return "连续失败次数过多，IP 被临时封禁，请 15 分钟后再试";
        }
        if ("BOT_DETECTED".equals(code)) {
            return "请求被社区网关风控拦截，请升级到最新版 App 后重试";
        }
        if ("MISSING_SIGNATURE".equals(code) || "SIGNATURE_MISMATCH".equals(code)
                || "INVALID_NONCE".equals(code) || "REPLAY_DETECTED".equals(code)
                || "INVALID_APP_ID".equals(code) || "APP_DISABLED".equals(code)
                || "APP_NOT_FOUND".equals(code)) {
            return "客户端凭证校验未通过（" + code + "），请升级 App 或联系管理员";
        }
        if (serverMessage != null && serverMessage.length() > 0) {
            return serverMessage;
        }
        return "激活失败，请稍后重试";
    }

    private static String codePayload(String code) {
        String c = code == null ? "" : code.trim();
        try {
            JSONObject o = new JSONObject();
            o.put("code", c);
            return o.toString();
        } catch (Throwable t) {
            return "{\"code\":\"" + c + "\"}";
        }
    }

    private static String tokenPayload(String token, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("operation_token", token == null ? "" : token);
            o.put("reason", reason == null ? "" : reason);
            return o.toString();
        } catch (Throwable t) {
            return "{\"operation_token\":\"" + token + "\",\"reason\":\"" + reason + "\"}";
        }
    }

    // ---------------------------------------------------------------- 网络

    private static Result post(String path, String jsonBody) {
        Result r = new Result();
        if (BASE_URL == null || BASE_URL.length() == 0
                || APP_ID == null || APP_ID.length() == 0
                || APP_SECRET == null || APP_SECRET.length() == 0) {
            r.networkError = true;
            r.code = "NOT_CONFIGURED";
            r.message = "客户端凭证未配置（local.properties 缺少 AFF_APP_ID / AFF_APP_SECRET）";
            return r;
        }
        HttpURLConnection conn = null;
        try {
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = randomNonce(24);
            byte[] body = jsonBody.getBytes("UTF-8");
            String bodyHash = sha256Hex(body);
            String canonical = ts + "\n" + nonce + "\n" + bodyHash;
            String signature = hmacSha256Hex(APP_SECRET, canonical);

            conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Client-Type", CLIENT_TYPE);   // v2 必填，缺了会被网关风控
            conn.setRequestProperty("X-App-Id", APP_ID);
            conn.setRequestProperty("X-Timestamp", ts);
            conn.setRequestProperty("X-Nonce", nonce);
            conn.setRequestProperty("X-Signature", signature);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            r.raw = text;
            parse(text, r);
            if (status == 401 || status == 403 || status == 429) {
                r.ok = false;
                r.networkError = false;
                if (r.message == null || r.message.length() == 0) {
                    r.message = status == 429
                            ? "请求太频繁，请稍后再试"
                            : "签名校验失败（客户端凭证或时间不对）";
                }
            }
            return r;
        } catch (Throwable t) {
            r.networkError = true;
            r.message = "网络异常：" + t.getClass().getSimpleName();
            return r;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void parse(String text, Result r) {
        if (text == null) {
            return;
        }
        String t = text.trim();
        if (t.length() == 0) {
            return;
        }
        try {
            JSONObject o = new JSONObject(t);
            r.ok = o.optBoolean("success", false);
            r.code = o.optString("code", "");
            r.message = o.optString("message", "");
            r.token = o.optString("operation_token", o.optString("token", ""));
            r.status = o.optString("status", "");
            r.valid = o.optBoolean("valid", r.ok);
            r.redeemable = o.optBoolean("redeemable", r.ok);
            r.lockRemainingMs = o.optLong("lock_remaining_ms", o.optLong("lock_remaining", 0L));
            r.idempotent = o.optBoolean("idempotent", false);
            if (!r.ok && r.code.length() == 0 && o.has("error")) {
                r.code = o.optString("error", "");
            }
            // 某些实现把 token 放在 data 里
            if (r.token.length() == 0 && o.optJSONObject("data") != null) {
                r.token = o.optJSONObject("data").optString("operation_token", "");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 签名原语

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
