package com.mineways;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 论坛 OAuth 2.0 · Mode B（手动授权码，适用于移动客户端）。
 *
 * <pre>
 *   论坛设置 → 授权应用 → 生成 6 位授权码（一次性，5 分钟有效）
 *        ↓ 用户抄进 App
 *   POST /api/oauth/token   {grant_type, code, appKey, appSecret, redirectUri}
 *        ↓ access_token（Bearer）
 *   GET  /api/oauth/userinfo  → { id, username, avatar, joinedAt }
 * </pre>
 *
 * 凭证（appKey / appSecret）来自未提交的 local.properties → BuildConfig，不进 git。
 */
public final class OAuthClient {

    private static final String BASE = BuildConfig.AFF_BASE_URL;
    private static final String APP_KEY = BuildConfig.AFF_OAUTH_APP_KEY;
    private static final String APP_SECRET = BuildConfig.AFF_OAUTH_APP_SECRET;
    private static final String REDIRECT = BuildConfig.AFF_OAUTH_REDIRECT;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    /** 统一 UA：后端（Coze 网关）只认这套"软件客户端"标识，Java 默认 UA 会被打成 404。 */
    private static final String UA = "okhttp/4.12.0";

    private OAuthClient() {
    }

    public static final class Token {
        public boolean ok;
        public String accessToken = "";
        public String scope = "";
        public long bindingId;
        public String code = "";
        public String message = "";
        public boolean networkError;
        /** 诊断用：HTTP 状态码 / 服务端原始响应（截断）。 */
        public int httpStatus;
        public String rawBody = "";
        /** 服务端 Retry-After（秒）；0 = 没给。限流退避优先听它的。 */
        public int retryAfterSec;
    }

    public static final class User {
        public long id;
        public String username = "";
        public String avatar = "";
        public String joinedAt = "";
        /** 有些部署会在 userinfo 里一并返回邮箱；没有就是空串（名单匹配时按空处理）。 */
        public String email = "";
    }

    /** 是否已配置 appKey / appSecret。 */
    public static boolean configured() {
        return BASE.length() > 0 && APP_KEY.length() > 0 && APP_SECRET.length() > 0;
    }

    /** 打开论坛去生成授权码（用户登录论坛后，在「设置 → 授权应用」里生成 6 位码）。 */
    public static String authorizePage() {
        return BASE + "/settings/oauth";
    }

    /**
     * 网络活性探针：GET /api/oauth/app-info（只读、无副作用）。
     * 用途：判断"拉不到身份"到底是 token 失效，还是单纯没网 —— 决定要不要清本地凭证。
     */
    public static boolean ping() {
        if (!configured()) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "/api/oauth/app-info?appKey=" + APP_KEY).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", UA);
            final int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String homePage() {
        return BASE;
    }

    /**
     * 授权码规范化：只去掉空白（含全角空格），**不做大小写转换**。
     * 论坛的码可能区分大小写，之前强制转大写会把正确的码改坏。
     */
    public static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u3000') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 授权码格式预检（宽松：4~32 位字母/数字，允许连字符下划线；大小写都收）。 */
    public static boolean looksLikeCode(String code) {
        String c = normalizeCode(code);
        if (c.length() < 4 || c.length() > 32) {
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

    /**
     * 授权码换 token（POST /api/oauth/token）。
     *
     * <p><b>只按用户输入的原文请求一次</b>：服务端对每个码是一次性的，
     * 自动换大小写重试反而可能把码打废。想换写法时由界面上的「用大写再试一次」显式触发。</p>
     */
    public static Token exchangeToken(String rawCode) {
        return requestToken(normalizeCode(rawCode));
    }

    /** 显式用大写再试一次（界面按钮触发，非自动）。 */
    public static Token exchangeTokenUpper(String rawCode) {
        return requestToken(normalizeCode(rawCode).toUpperCase());
    }

    /**
     * 第 1 步：提交授权码（POST /api/oauth/submit {code, appKey, appSecret}）—— 服务端状态 unused → pending。
     *
     * <p>论坛的三步状态机：</p>
     * <pre>
     *   设置页生成码（unused）
     *     → App 调 /api/oauth/submit（pending，出现在「待确认请求」里）
     *     → 用户点确认 /api/user/oauth/confirm/{code}（confirmed）
     *     → App 调 /api/oauth/token 换 token
     * </pre>
     *
     * <p>如果服务端后来把设置页生成的码直接标记为 confirmed，这一步会失败，
     * 属于正常情况 —— 直接走兑换即可，所以调用方忽略失败结果。</p>
     */
    public static Token submitCode(String rawCode) {
        Token t = new Token();
        if (!configured()) {
            t.networkError = true;
            t.code = "NOT_CONFIGURED";
            t.message = "OAuth 未配置（local.properties 缺少 AFF_OAUTH_APP_KEY / AFF_OAUTH_APP_SECRET）";
            return t;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("code", normalizeCode(rawCode));
            body.put("appKey", APP_KEY);
            body.put("appSecret", APP_SECRET);
            Http http = post(BASE + "/api/oauth/submit", body.toString());
            t.httpStatus = http.status;
            t.retryAfterSec = parseRetryAfter(http.retryAfter);
            t.rawBody = http.body.length() > 300 ? http.body.substring(0, 300) : http.body;
            JSONObject o = new JSONObject(http.body);
            t.code = o.optString("code", o.optString("error", ""));
            t.message = o.optString("message", o.optString("error_description", o.optString("error", "")));
            t.ok = o.optBoolean("success", false)
                    || (t.code.length() == 0 && t.message.length() == 0);
        } catch (Throwable e) {
            t.networkError = true;
            t.message = "网络异常（" + e.getClass().getSimpleName() + "）";
        }
        return t;
    }

    private static boolean isCodeProblem(String code) {
        return "invalid_grant".equals(code) || "CODE_INVALID".equals(code)
                || "CODE_EXPIRED".equals(code) || "CODE_NOT_FOUND".equals(code);
    }

    /** 单次请求：用一种写法的授权码换 token。 */
    private static Token requestToken(String code) {
        Token t = new Token();
        if (!configured()) {
            t.networkError = true;
            t.code = "NOT_CONFIGURED";
            t.message = "OAuth 未配置（local.properties 缺少 AFF_OAUTH_APP_KEY / AFF_OAUTH_APP_SECRET）";
            return t;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("grant_type", "authorization_code");
            body.put("code", code == null ? "" : code);
            body.put("appKey", APP_KEY);
            body.put("appSecret", APP_SECRET);
            if (REDIRECT.length() > 0) {
                body.put("redirectUri", REDIRECT);
            }
            Http http = post(BASE + "/api/oauth/token", body.toString());
            t.httpStatus = http.status;
            t.retryAfterSec = parseRetryAfter(http.retryAfter);
            t.rawBody = http.body.length() > 300 ? http.body.substring(0, 300) : http.body;
            JSONObject o = new JSONObject(http.body);
            t.ok = o.optString("access_token", "").length() > 0;
            t.accessToken = o.optString("access_token", "");
            t.scope = o.optString("scope", "");
            t.bindingId = o.optLong("binding_id", 0L);
            t.code = o.optString("code", o.optString("error", ""));
            t.message = o.optString("message", o.optString("error_description", ""));
        } catch (Throwable e) {
            t.networkError = true;
            t.message = "网络异常（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "）";
        }
        return t;
    }

    /** 拉取用户资料（GET /api/oauth/userinfo）。 */
    public static User userInfo(String accessToken) {
        User u = new User();
        if (accessToken == null || accessToken.length() == 0) {
            return u;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/oauth/userinfo").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent", UA);
            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            conn.disconnect();
            JSONObject o = new JSONObject(text);
            JSONObject user = o.optJSONObject("user");
            if (user != null) {
                u.id = user.optLong("id", 0L);
                u.username = user.optString("username", "");
                u.avatar = user.optString("avatar", "");
                u.joinedAt = user.optString("joinedAt", "");
                u.email = user.optString("email", "");
            }
        } catch (Throwable ignored) {
        }
        return u;
    }

    /** 错误码 → 中文（把服务端原话一并带上，方便判断到底哪一步不对）。 */
    public static String friendlyError(String code, String serverMessage) {
        return friendlyError(code, serverMessage, 0, "");
    }

    /** 带 HTTP 状态与原始响应的版本（诊断用）。 */
    public static String friendlyError(String code, String serverMessage, int httpStatus, String rawBody) {
        String c = code == null ? "" : code;
        String extra = "";
        if (serverMessage != null && serverMessage.length() > 0 && !serverMessage.equals(c)) {
            extra = "（论坛说：" + serverMessage + "）";
        }
        String base;
        if (serverMessage != null && serverMessage.contains("尚未被确认")) {
            // 论坛的码生成后处于「待确认」状态，没确认过的码不能兑换
            base = "这个授权码还没在论坛确认 —— 打开论坛「授权应用 → 待确认请求」点『确认』后再登录；"
                    + "或直接用 App 内一键授权（会自动确认）" + extra;
        } else if ("invalid_grant".equals(c) || "CODE_INVALID".equals(c) || "CODE_EXPIRED".equals(c)
                || "CODE_NOT_FOUND".equals(c)) {
            base = "授权码无效或已过期 —— 只能用一次、5 分钟内有效" + extra;
        } else if ("invalid_client".equals(c) || "APP_NOT_FOUND".equals(c) || "INVALID_CLIENT".equals(c)) {
            base = "应用凭证不对（API Key / appSecret）" + extra;
        } else if ("invalid_request".equals(c)) {
            base = "请求缺少必要参数" + extra;
        } else if ("unsupported_grant_type".equals(c)) {
            base = "服务端不支持这种登录方式（接口版本不匹配）" + extra;
        } else if (serverMessage != null
                && (serverMessage.contains("密码") || serverMessage.toLowerCase().contains("password"))) {
            // 论坛里只注册过、或从没设过密码的账号，用密码登录必然失败 —— 直接把人引到验证码登录
            base = serverMessage + "\n\n如果这个账号没有设置过密码（只注册过 / 用第三方登录的），"
                    + "请改用「验证码登录」：填邮箱 → 收邮件验证码 → 登录。";
        } else if ("NOT_CONFIGURED".equals(c)) {
            base = serverMessage != null && serverMessage.length() > 0 ? serverMessage : "OAuth 未配置";
        } else if (serverMessage != null && serverMessage.length() > 0) {
            base = serverMessage;
        } else {
            base = "登录失败，请稍后重试";
        }
        StringBuilder sb = new StringBuilder(base);
        if (httpStatus > 0 || (rawBody != null && rawBody.length() > 0)) {
            sb.append("\n\n〔诊断〕HTTP ").append(httpStatus);
            if (rawBody != null && rawBody.length() > 0) {
                sb.append("\n").append(rawBody);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 账密登录（论坛账号）

    public static final class LoginUser {
        public boolean ok;
        public String token = "";
        public long id;
        public String username = "";
        public String email = "";
        public String avatar = "";
        public String codeName = "";
        public String message = "";
        public int httpStatus;
        public String rawBody = "";
        public int retryAfterSec;
        public boolean networkError;

        public User toUser() {
            User u = new User();
            u.id = id;
            u.username = username;
            u.email = email;
            u.avatar = avatar;
            return u;
        }
    }

    /**
     * 论坛账号密码登录：<code>POST {base}/api/auth/login-password {email, password}</code>。
     *
     * <p>响应里直接带 user 主体（id / username / avatar / email），**不需要**再调 userinfo；
     * 返回的 token 是论坛自己的 Bearer，和 OAuth 的 <code>tk_</code> 是两套，别混用。</p>
     *
     * <p>头策略：默认最小头；若被网关打成 404，再带一次论坛 App 头重试。</p>
     */
    public static LoginUser loginPassword(String email, String password) {
        final LoginUser out = new LoginUser();
        try {
            JSONObject body = new JSONObject();
            body.put("email", normEmail(email));
            body.put("password", password == null ? "" : password);
            fillLogin(out, postAuth("/api/auth/login-password", body.toString()));
            if (out.email.length() == 0) {
                // 服务端没回邮箱时用输入值兜底：白名单是按邮箱配的，缺了会被判"不在名单里"
                out.email = normEmail(email);
            }
        } catch (Throwable e) {
            out.networkError = true;
            out.message = "网络异常（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "）";
        }
        return out;
    }

    /**
     * 发送登录验证码：<code>POST {base}/api/auth/send-code {email, type:"login"}</code>。
     *
     * <p>给"没设置过密码"的账号用（也有人是忘了密码）。重发冷却由界面控制（60 秒）。</p>
     */
    public static LoginUser sendLoginCode(String email) {
        final LoginUser out = new LoginUser();
        try {
            JSONObject body = new JSONObject();
            body.put("email", normEmail(email));
            body.put("type", "login");
            Http http = postAuth("/api/auth/send-code", body.toString());
            out.httpStatus = http.status;
            out.retryAfterSec = parseRetryAfter(http.retryAfter);
            out.rawBody = http.body.length() > 300 ? http.body.substring(0, 300) : http.body;
            JSONObject o = new JSONObject(http.body);
            out.codeName = o.optString("error", o.optString("code", ""));
            // 实测：这个接口成功时只回 {"message":"验证码已发送"}；失败时把中文放在 error 里
            out.message = o.optString("message",
                    o.optString("error_description", o.optString("error", "")));
            out.ok = out.codeName.length() == 0
                    && (o.optBoolean("success", false) || (http.status >= 200 && http.status < 300));
            if (!out.ok && out.message.length() == 0) {
                out.message = "验证码发送失败（HTTP " + http.status + "）";
            }
        } catch (Throwable e) {
            out.networkError = true;
            out.message = "网络异常（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "）";
        }
        return out;
    }

    /**
     * 验证码登录：<code>POST {base}/api/auth/verify {email, code}</code> → 与账密登录同样的响应结构。
     *
     * <p>这条路线顺带解决了"授权码登录拿不到邮箱"的老问题：邮箱是用户自己填的、
     * 又用邮件里的验证码证明了归属，所以白名单按邮箱配也能命中。</p>
     */
    public static LoginUser verifyLoginCode(String email, String code) {
        final LoginUser out = new LoginUser();
        try {
            JSONObject body = new JSONObject();
            body.put("email", normEmail(email));
            body.put("code", code == null ? "" : code.trim());
            fillLogin(out, postAuth("/api/auth/verify", body.toString()));
            if (out.ok && out.email.length() == 0) {
                out.email = normEmail(email);
            }
        } catch (Throwable e) {
            out.networkError = true;
            out.message = "网络异常（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "）";
        }
        return out;
    }

    /** 邮箱规范化：去空白（含全角空格）与零宽字符。 */
    public static String normEmail(String email) {
        if (email == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < email.length(); i++) {
            char ch = email.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
                    || ch == '\u3000' || ch == '\u200b') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 登录类接口统一发法：默认最小头，被网关打成 404 时再补一次论坛 App 头。 */
    private static Http postAuth(String path, String body) throws Exception {
        Http http = post(BASE + path, body, false);
        if (http.status == 404) {
            http = post(BASE + path, body, true);
        }
        return http;
    }

    /** 登录响应 → LoginUser（token + user 主体）。账密登录与验证码登录共用。 */
    private static void fillLogin(LoginUser out, Http http) {
        out.httpStatus = http.status;
        out.retryAfterSec = parseRetryAfter(http.retryAfter);
        out.rawBody = http.body.length() > 300 ? http.body.substring(0, 300) : http.body;
        try {
            JSONObject o = new JSONObject(http.body);
            out.token = o.optString("token", o.optString("accessToken", ""));
            out.ok = out.token.length() > 0;
            out.codeName = o.optString("error", o.optString("code", ""));
            // 实测：业务失败时论坛把中文说明放在 error 里（如 {"error":"验证码无效或已过期"}）
            out.message = o.optString("message",
                    o.optString("error_description", o.optString("error", "")));
            JSONObject u = o.optJSONObject("user");
            if (u != null) {
                out.id = u.optLong("id", 0L);
                out.username = u.optString("username", "");
                out.email = u.optString("email", "");
                out.avatar = u.optString("avatar", "");
            }
            if (!out.ok && out.message.length() == 0 && out.codeName.length() == 0) {
                out.message = "服务端没返回 token（HTTP " + http.status + "）";
            }
        } catch (Throwable ignored) {
            out.ok = false;
            if (out.message.length() == 0) {
                out.message = "服务端返回无法解析（HTTP " + http.status + "）";
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    /** HTTP 结果（状态码 + 原始响应体），失败时要能看到服务端原话。 */
    private static final class Http {
        int status;
        String body = "";
        String retryAfter = "";
    }

    /** Retry-After：数字秒；HTTP 日期形式不认识就返回 0（退避走默认值）。 */
    private static int parseRetryAfter(String v) {
        if (v == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(120, Integer.parseInt(v.trim())));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Http post(String url, String jsonBody) throws Exception {
        return post(url, jsonBody, false);
    }

    /**
     * @param appHeaders true = 额外带论坛 App 的那套头（X-Client-Platform/Version、Accept-Language）。
     *                   <p>实测：带上这些头时，Coze 网关会把请求打成 404（可疑请求）；
     *                   所以默认走"最小头"，只有路由挑剔（404）时才补一次 App 头。</p>
     */
    private static Http post(String url, String jsonBody, boolean appHeaders) throws Exception {
        Http http = new Http();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", UA);
        if (appHeaders) {
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("X-Client-Platform", "android");
            conn.setRequestProperty("X-Client-Version", "5.00");
        }
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.flush();
        os.close();
        http.status = conn.getResponseCode();
        http.retryAfter = conn.getHeaderField("Retry-After");
        InputStream in = (http.status >= 200 && http.status < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        http.body = readAll(in);
        conn.disconnect();
        return http;
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
}
