package com.mineways;

import com.mineways.cloud.LoginPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Google / 微软 的 OAuth 端点与参数装配（纯逻辑，不碰安卓类，所以能用 javac 断言）。
 *
 * <p>Client ID 一律<b>云控下发</b>（{@code login_google_client_id} / {@code login_ms_client_id}）：
 * 这两个值是你在开发者后台申请出来的东西，写进包里就等于每次改都要重新出包；下发之后
 * 「换一枚 ID、下架某个登录、改回调地址」都不用装新版。没下发时这一档直接
 * {@link Cfg#enabled()}＝false，界面上显示「暂未开放」，不发请求、不崩。
 *
 * <p>端点也留了覆盖口（{@code login_google_authorize} 这些），因为国内直连
 * {@code accounts.google.com} 不一定通 —— 真要走中转是服务端的事，客户端只需要能把地址换掉。
 */
public final class SocialClients {

    /** 读云控开关。安卓侧传 {@code CloudCatalog.gate()::flag}，断言里传假表。 */
    public interface Flag {
        String get(String key, String def);
    }

    /** 一个供应商的配置。 */
    public static final class Cfg {
        public final String scope;
        public String clientId = "";
        public String authorize = "";
        public String token = "";
        public String userinfo = "";
        public String redirect = "";
        public String scopes = "";
        /** 额外表单（微软要带 {@code scope}，Google 不用；这里是原文追加的 {@code k=v}）。 */
        public String extra = "";
        public String off = "";
        public String name = "";

        Cfg(String scope) {
            this.scope = scope;
        }

        /** 能不能用：Client ID 有、且云控没把它关掉。 */
        public boolean enabled() {
            return clientId.length() > 0 && !"0".equals(off) && authorize.length() > 0
                    && token.length() > 0;
        }

        /** 不能用时给用户看的那句（要说清是「还没申请」还是「云控关了」）。 */
        public String whyNot() {
            if ("0".equals(off)) {
                return name + "登录已被云控下架";
            }
            if (clientId.length() == 0) {
                return name + "登录暂未开放：还没下发 Client ID";
            }
            return name + "登录配置不完整";
        }

        /** 拿 code 换 token 的表单体（含 PKCE 的 verifier）。 */
        public String tokenForm(String code, String redirect, String verifier) {
            StringBuilder sb = new StringBuilder();
            kv(sb, "client_id", clientId);
            kv(sb, "grant_type", "authorization_code");
            kv(sb, "code", code);
            kv(sb, "redirect_uri", redirect);
            kv(sb, "code_verifier", verifier);
            if (extra.length() > 0) {
                sb.append('&').append(extra);
            }
            return sb.toString();
        }
    }

    /** 出厂端点。Google 用 v2 授权端点；微软用 common 租户（消费者+企业账号都能登）。 */
    private static final String GOOGLE_AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String GOOGLE_SCOPES = "openid email profile";
    private static final String MS_HOST = "https://login.microsoftonline.com/";
    private static final String MS_USERINFO = "https://graph.microsoft.com/oidc/userinfo";
    private static final String MS_SCOPES = "openid profile email offline_access";

    /** 回调用的自定义 scheme：不需要域名验证，也不会被别的 App 抢注（我们只在自己的 WebView/浏览器里用）。 */
    public static final String DEFAULT_REDIRECT = "mineways://login";

    private SocialClients() {
    }

    /** 取某一档的配置（{@code scope} 用 {@link com.mineways.cloud.LoginPolicy} 的档名）。 */
    public static Cfg of(Flag f, String scope) {
        Cfg c = new Cfg(scope);
        String p = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (LoginPolicy.GOOGLE.equals(p)) {
            c.name = "Google";
            c.clientId = trim(f.get("login_google_client_id", ""));
            c.authorize = trim(f.get("login_google_authorize", GOOGLE_AUTHORIZE));
            c.token = trim(f.get("login_google_token", GOOGLE_TOKEN));
            c.userinfo = trim(f.get("login_google_userinfo", GOOGLE_USERINFO));
            c.scopes = trim(f.get("login_google_scopes", GOOGLE_SCOPES));
            c.off = trim(f.get("login_google", "1"));
        } else if (LoginPolicy.MS.equals(p)) {
            String tenant = orDefault(trim(f.get("login_ms_tenant", "common")), "common");
            c.name = "微软";
            c.clientId = trim(f.get("login_ms_client_id", ""));
            c.authorize = trim(f.get("login_ms_authorize",
                    MS_HOST + tenant + "/oauth2/v2.0/authorize"));
            c.token = trim(f.get("login_ms_token", MS_HOST + tenant + "/oauth2/v2.0/token"));
            c.userinfo = trim(f.get("login_ms_userinfo", MS_USERINFO));
            c.scopes = trim(f.get("login_ms_scopes", MS_SCOPES));
            c.off = trim(f.get("login_ms", "1"));
        } else {
            c.name = p;
            c.off = "0";
            return c;
        }
        // 回调按供应商各配一条：Google 的 Android 客户端把回调固定成
        // com.googleusercontent.apps.<客户端ID>:/oauth2redirect，微软那边用的是自定义 scheme
        // mineways://login。只留一条 login_redirect 会让两家互相顶掉，所以先读带档名的，
        // 再退回公用的，最后退回出厂默认。
        c.redirect = orDefault(trim(f.get("login_" + p + "_redirect", "")),
                orDefault(trim(f.get("login_redirect", "")), DEFAULT_REDIRECT));
        // 覆盖过的端点也要跟着租户走：只改了 authorize 而没改 token 时，从 authorize 反推 token
        if (LoginPolicy.MS.equals(p) && c.token.length() == 0 && c.authorize.length() > 0) {
            c.token = c.authorize.replace("authorize", "token");
        }
        return c;
    }

    /**
     * 授权页地址。带 PKCE（S256）与 state，{@code prompt=select_account} 让 Google 每次都能换账号，
     * 不然它会静默用上一个 Google 会话登录，用户想换号就换不掉。
     */
    public static String authorizeUrl(Cfg c, String state, String challenge) {
        StringBuilder sb = new StringBuilder(c.authorize);
        sb.append(c.authorize.indexOf('?') < 0 ? '?' : '&');
        q(sb, "response_type", "code");
        q(sb, "client_id", c.clientId);
        q(sb, "redirect_uri", c.redirect);
        q(sb, "scope", c.scopes);
        q(sb, "state", state);
        q(sb, "code_challenge", challenge);
        q(sb, "code_challenge_method", "S256");
        if (LoginPolicy.GOOGLE.equals(c.scope)) {
            q(sb, "prompt", "select_account");
        }
        String extra = c.extra;
        if (extra.length() > 0) {
            sb.append('&').append(extra);
        }
        return sb.toString();
    }

    /**
     * 解析回跳 URL。返回 {@code [code, state]}，出错时第一个元素以 {@code ERR:} 开头带原因。
     *
     * <p>浏览器/ WebView 的回跳形态五花八门：{@code mineways://login?code=…&state=…}、
     * {@code https://host/#code=…}（微软某些租户带 fragment）、还有供应商直接带 {@code error=} 的
     * （用户拒了授权、或者配置不匹配）。这里一次处理完，上层只看到「拿到 code」或「一句人话」。
     */
    public static String[] parseCallback(String url, String expectState) {
        if (url == null || url.trim().length() == 0) {
            return new String[]{"ERR:回跳是空的"};
        }
        // 先把 fragment 整段摘出来，再取 query —— 否则会剩下一截 "…#code=…" 把 state 的值污染掉
        String head = url;
        String frag = "";
        int hash = head.indexOf('#');
        if (hash >= 0) {
            frag = head.substring(hash + 1);
            head = head.substring(0, hash);
        }
        int mark = head.indexOf('?');
        String query = mark >= 0 ? head.substring(mark + 1) : "";
        if (frag.length() > 0) {
            query = query.length() == 0 ? frag : query + "&" + frag;
        }
        List<String> codes = new ArrayList<>();
        String state = "";
        String err = "";
        String desc = "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq);
            String v = decode(pair.substring(eq + 1));
            if ("code".equals(k)) {
                codes.add(v);
            } else if ("state".equals(k)) {
                state = v;
            } else if ("error".equals(k) || "error_description".equals(k)) {
                if ("error".equals(k)) {
                    err = v;
                } else {
                    desc = v;
                }
            }
        }
        if (err.length() > 0) {
            return new String[]{"ERR:" + err + (desc.length() == 0 ? "" : " · " + desc)};
        }
        if (codes.isEmpty() || codes.get(0).length() == 0) {
            return new String[]{"ERR:回跳里没有 code（可能授权被取消）"};
        }
        if (codes.size() > 1) {
            return new String[]{"ERR:回跳里有 " + codes.size() + " 个 code，不猜"};
        }
        if (expectState != null && expectState.length() > 0 && !expectState.equals(state)) {
            return new String[]{"ERR:state 对不上，这次回跳不认（防 CSRF）"};
        }
        return new String[]{codes.get(0), state};
    }

    /** access_token 在 JSON 里的可能字段名（两家都叫 access_token，留个兜底）。 */
    public static final String[] TOKEN_KEYS = {"access_token", "accessToken", "id_token"};

    private static void q(StringBuilder sb, String k, String v) {
        if (v == null || v.length() == 0) {
            return;
        }
        if (sb.charAt(sb.length() - 1) != '?' && sb.charAt(sb.length() - 1) != '&') {
            sb.append('&');
        }
        sb.append(k).append('=').append(Pkce.enc(v));
    }

    private static void kv(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(k).append('=').append(Pkce.enc(v == null ? "" : v));
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orDefault(String v, String d) {
        return v.length() == 0 ? d : v;
    }
}
