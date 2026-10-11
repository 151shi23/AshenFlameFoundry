package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * 专业版 · 论坛账号授权（Mode B 手动授权码）。
 *
 * <p>与大众版的「按次激活码」是两条线：
 * <ul>
 *   <li>大众版限制功能：{@link ActivationDialog} 每次使用扣一次机会；</li>
 *   <li><b>专业版</b>：论坛账号登录一次 → 本地长期有效（默认 30 天，到期重新登录）→
 *       叠加 {@link #WHITELIST} 名单校验，只认那几个人。</li>
 * </ul>
 *
 * <p>所有网络调用都带限流保护：只重试 429 / 5xx / 网络错误，指数退避 + ±30% 抖动，
 * 并遵守服务端的 <code>Retry-After</code>。
 */
public final class ProAuth {

    // ============================================================ ★ 名单（授权用户）
    /**
     * 白名单：论坛 <b>username / 用户 id / 邮箱</b> 三者命中任意一个即视为授权。
     * 例：{@code new String[]{"张三", "12345", "someone@qq.com"}}。
     * <p>名单为空时的行为由 {@link #EMPTY_MEANS_ALLOW} 决定（开发期放行，上线前收紧）。
     */
    public static final String[] WHITELIST = new String[]{
            "356136076@qq.com",
            "3688434319@qq.com",
            "2722442008@qq.com",
            "1501621157@qq.com",
            "2024907263@qq.com",
            "1871915299@qq.com",
            "2719787785@qq.com",
            "2469300594@qq.com",
    };

    /** 名单为空时是否放行：已填名单 → false（不在名单里的谁都进不去）。 */
    public static final boolean EMPTY_MEANS_ALLOW = false;

    // ============================================================ 本地凭证
    private static final String PREFS = "pro_auth";
    private static final String K_TOKEN = "access_token";
    private static final String K_ID = "uid";
    private static final String K_NAME = "username";
    private static final String K_AVATAR = "avatar";
    private static final String K_AT = "saved_at";
    private static final String K_EMAIL = "email";
    private static final String K_SOURCE = "login_source";

    /** 本地凭证有效期（天）：到期后需要重新登录一次。 */
    public static final long VALID_DAYS = 30;

    public static final class Record {
        public String token = "";
        public long id;
        public String username = "";
        public String email = "";
        public String avatar = "";
        public long savedAt;
        /**
         * 登录来源：{@code oauth}（论坛授权码）/ {@code password}（账号密码）/ {@code code}（邮件验证码）。
         * 空串 = 老版本留下的记录（来源未知，按最保守的方式对待）。
         */
        public String source = "";

        public boolean hasToken() {
            return token != null && token.length() > 0;
        }

        public boolean expired() {
            if (savedAt <= 0) {
                return true;
            }
            return System.currentTimeMillis() - savedAt > VALID_DAYS * 86400000L;
        }
    }

    private ProAuth() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Record load(Context c) {
        Record r = new Record();
        SharedPreferences p = sp(c);
        r.token = p.getString(K_TOKEN, "");
        r.id = p.getLong(K_ID, 0L);
        r.username = p.getString(K_NAME, "");
        r.email = p.getString(K_EMAIL, "");
        r.avatar = p.getString(K_AVATAR, "");
        r.savedAt = p.getLong(K_AT, 0L);
        r.source = p.getString(K_SOURCE, "");
        return r;
    }

    public static void save(Context c, Record r) {
        sp(c).edit()
                .putString(K_TOKEN, r.token)
                .putLong(K_ID, r.id)
                .putString(K_NAME, r.username)
                .putString(K_EMAIL, r.email)
                .putString(K_AVATAR, r.avatar)
                .putString(K_SOURCE, r.source == null ? "" : r.source)
                .putLong(K_AT, r.savedAt > 0 ? r.savedAt : System.currentTimeMillis())
                .apply();
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    // ============================================================ 判定

    /** 名单命中？（username / id / email 任一匹配，忽略大小写与首尾空格） */
    public static boolean whitelistHit(Record r) {
        if (WHITELIST == null || WHITELIST.length == 0) {
            return EMPTY_MEANS_ALLOW;
        }
        for (String w : WHITELIST) {
            if (w == null || w.trim().length() == 0) {
                continue;
            }
            final String k = w.trim().toLowerCase();
            if (r.username != null && r.username.trim().toLowerCase().equals(k)) {
                return true;
            }
            if (r.email != null && r.email.trim().toLowerCase().equals(k)) {
                return true;
            }
            if (r.id > 0 && String.valueOf(r.id).equals(k)) {
                return true;
            }
        }
        return false;
    }

    /** 当前是否已授权（本地凭证有效 + 未过期 + 名单命中）。 */
    public static boolean isAuthorized(Context c) {
        Record r = load(c);
        return r.hasToken() && !r.expired() && whitelistHit(r);
    }

    // ============================================================ 登录（Mode B）

    public static final class Result {
        public boolean ok;
        public String message = "";
        public Record record = new Record();
    }

    /**
     * 用 6 位授权码登录论坛（后台线程调用）。
     *
     * <p>流程：submit（把码挂到你的账号上）→ 用户若还没确认会明确提示 → token 换票 → userinfo 取身份。
     */
    public static Result login(String rawCode) {
        final Result out = new Result();
        if (!OAuthClient.configured()) {
            out.message = "论坛授权未配置（local.properties 缺少 AFF_OAUTH_APP_KEY / AFF_OAUTH_APP_SECRET）";
            return out;
        }
        if (!OAuthClient.looksLikeCode(rawCode)) {
            out.message = "授权码格式不对：4~32 位字母/数字（区分大小写，别带空格）";
            return out;
        }

        // 第一步：submit（失败不致命：有的部署里码生成即已确认）
        OAuthClient.Token sub = retry(() -> OAuthClient.submitCode(rawCode));
        if (sub != null && !sub.ok && !sub.networkError
                && sub.httpStatus != 0 && sub.httpStatus != 400 && sub.httpStatus != 409) {
            // 只有"接口确实报错"才拦下；400/409（已确认/状态不符）继续往下走
            out.message = OAuthClient.friendlyError(sub.code, sub.message, sub.httpStatus, sub.rawBody);
            return out;
        }

        // 第二步：换 token
        OAuthClient.Token tk = retry(() -> OAuthClient.exchangeToken(rawCode));
        if (tk == null || !tk.ok) {
            if (tk == null) {
                out.message = "网络异常，请稍后重试";
            } else {
                out.message = OAuthClient.friendlyError(tk.code, tk.message, tk.httpStatus, tk.rawBody);
            }
            return out;
        }

        // 第三步：取身份
        OAuthClient.User u = OAuthClient.userInfo(tk.accessToken);
        Record r = new Record();
        r.token = tk.accessToken;
        r.id = u.id;
        r.username = u.username;
        r.avatar = u.avatar;
        r.savedAt = System.currentTimeMillis();
        r.source = "oauth";
        out.record = r;

        if (!whitelistHit(r)) {
            out.message = "这个论坛账号不在授权名单里"
                    + (r.username.length() > 0 ? "（当前账号：" + r.username + "）" : "");
            if (r.email.length() == 0 && whitelistHasEmail()) {
                out.message += "\n提示：名单是按邮箱配的，而授权码登录拿不到邮箱 —— "
                        + "请改用「账号密码登录」（邮箱 + 密码）。";
            }
            return out;
        }
        out.ok = true;
        return out;
    }

    /** 名单里是否配了邮箱（决定要不要提示"改用账密登录"）。 */
    private static boolean whitelistHasEmail() {
        if (WHITELIST == null) {
            return false;
        }
        for (String w : WHITELIST) {
            if (w != null && w.indexOf('@') > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动时后台复验一次（不阻塞界面）：token 失效（userinfo 拉不到用户名）就清掉本地凭证，
     * 下次进专业版会重新要求登录。网络异常时**不动**本地凭证（避免断网被踢）。
     */
    public static void reverifyInBackground(final Context c) {
        final Record r = load(c);
        if (!r.hasToken()) {
            return;
        }
        // 只有论坛授权码（OAuth）登录才谈得上用 /api/oauth/userinfo 复验。
        // 账密 / 验证码登录拿到的是论坛自己的 Bearer，这个接口不认 -> 会拉不到用户名，
        // 老逻辑据此判"token 失效"并把刚登录成功的凭证清掉（表现：登录成功，切个页面回来就变未登录，
        // 白名单功能也跟着掉）。这类记录只做名单判定，不做接口复验 —— 真失效了用户重新登录即可。
        if (!"oauth".equals(r.source)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                OAuthClient.User u = OAuthClient.userInfo(r.token);
                if (u.username != null && u.username.length() > 0) {
                    if (!whitelistHit(userToRecord(r.token, u))) {
                        clear(c);   // 不在名单里了（被移出/换号）→ 收回
                    }
                    return;
                }
                // 拉不到身份：只有网络是通的（说明是 401 之类的真失效）才清
                if (networkAlive()) {
                    clear(c);
                }
            }
        }, "pro-reverify").start();
    }

    private static Record userToRecord(String token, OAuthClient.User u) {
        Record r = new Record();
        r.token = token;
        r.id = u.id;
        r.username = u.username;
        r.avatar = u.avatar;
        r.savedAt = System.currentTimeMillis();
        r.source = "oauth";
        return r;
    }

    private static boolean networkAlive() {
        return OAuthClient.ping();   // 只读探针，不会给服务端塞假授权码
    }

    // ============================================================ 验证码登录（没设置密码的账号）

    /**
     * 发送登录验证码：POST <code>auth/send-code {email, type:"login"}</code>。
     *
     * <p>给"只注册过、没设置过密码"的账号用（也顺手解决了小白忘记密码的情况）。
     * 重发冷却 60 秒由界面控制，这里只做一次调用。</p>
     */
    public static Result sendCode(String rawEmail) {
        final Result out = new Result();
        final String email = OAuthClient.normEmail(rawEmail);
        if (email.length() == 0) {
            out.message = "请填写论坛邮箱";
            return out;
        }
        OAuthClient.LoginUser r = OAuthClient.sendLoginCode(email);
        out.ok = r.ok;
        out.message = r.ok ? "验证码已发送，请查收邮箱（也看看垃圾箱）"
                : (r.message.length() > 0 ? r.message
                : OAuthClient.friendlyError(r.codeName, r.message, r.httpStatus, r.rawBody));
        return out;
    }

    /**
     * 验证码登录：邮箱 + 邮件里的验证码（POST <code>auth/verify {email, code}</code>）。
     *
     * <p>白名单命中判定与账密登录一致；邮箱取"用户填的 + 服务端返回的"二者之一，
     * 保证按邮箱配置的名单也能命中。</p>
     */
    public static Result loginByCode(String rawEmail, String rawCode) {
        final Result out = new Result();
        final String email = OAuthClient.normEmail(rawEmail);
        final String code = rawCode == null ? "" : rawCode.trim();
        if (email.length() == 0 || code.length() == 0) {
            out.message = "请填写邮箱和邮件里的验证码";
            return out;
        }
        final OAuthClient.LoginUser u = retryLogin(() -> OAuthClient.verifyLoginCode(email, code));
        if (u == null) {
            out.message = "网络异常，请稍后重试";
            return out;
        }
        if (!u.ok) {
            out.message = u.message.length() > 0 ? u.message
                    : OAuthClient.friendlyError(u.codeName, u.message, u.httpStatus, u.rawBody);
            return out;
        }
        final Record r = new Record();
        r.token = u.token;
        r.id = u.id;
        r.username = u.username;
        r.email = u.email.length() > 0 ? u.email : email;   // 验证码本身就证明了邮箱归属
        r.avatar = u.avatar;
        r.savedAt = System.currentTimeMillis();
        r.source = "code";
        out.record = r;
        if (!whitelistHit(r)) {
            out.message = "这个论坛账号不在授权名单里"
                    + (r.username.length() > 0 ? "（当前账号：" + r.username + "）" : "");
            return out;
        }
        out.ok = true;
        return out;
    }

    // ============================================================ 限流保护

    /** 可重试的调用。 */
    private interface Call {
        OAuthClient.Token run();
    }

    /**
     * 最多 3 次：只对 429 / 5xx / 网络错误重试；退避 500ms → 1200ms → 2500ms，
     * 每次 ±30% 抖动；若服务端给了 Retry-After 就优先听它的。
     */
    private static OAuthClient.Token retry(Call call) {
        final long[] base = {500L, 1200L, 2500L};
        final Random rnd = new Random();
        OAuthClient.Token last = null;
        for (int i = 0; i < 3; i++) {
            try {
                last = call.run();
            } catch (Throwable t) {
                last = null;
            }
            if (last != null && (last.ok || !retryable(last))) {
                return last;
            }
            if (i == 2) {
                break;
            }
            long wait = base[i];
            if (last != null && last.retryAfterSec > 0) {
                wait = last.retryAfterSec * 1000L;
            } else {
                wait = wait + (long) (wait * 0.3 * (rnd.nextDouble() * 2 - 1));   // ±30% 抖动
            }
            try {
                Thread.sleep(Math.max(200L, Math.min(wait, 15000L)));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** 登录类调用（账密/验证码）的统一重试：策略与 {@link #retry} 相同。 */
    private interface LoginCall {
        OAuthClient.LoginUser run();
    }

    private static OAuthClient.LoginUser retryLogin(LoginCall call) {
        final long[] waits = {600L, 1500L, 3000L};
        final Random rnd = new Random();
        OAuthClient.LoginUser last = null;
        for (int i = 0; i < 3; i++) {
            try {
                last = call.run();
            } catch (Throwable t) {
                last = null;
            }
            if (last != null && (last.ok || !retryableLogin(last))) {
                return last;
            }
            if (i == 2) {
                break;
            }
            long wait = (last != null && last.retryAfterSec > 0) ? last.retryAfterSec * 1000L
                    : waits[i] + (long) (waits[i] * 0.3 * (rnd.nextDouble() * 2 - 1));
            try {
                Thread.sleep(Math.max(200L, Math.min(wait, 15000L)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private static boolean retryableLogin(OAuthClient.LoginUser u) {
        if (u.networkError) {
            return true;
        }
        final int s = u.httpStatus;
        // 404 在 Coze 网关上是"限流/抖动"的表现（同一 URL 一会 200 一会 404），所以也要重试
        return s == 429 || s == 404 || s == 500 || s == 502 || s == 503 || s == 504 || s == 0;
    }

    private static boolean retryable(OAuthClient.Token t) {
        if (t.networkError) {
            return true;
        }
        final int s = t.httpStatus;
        // 404 在 Coze 网关上是"限流/抖动"的表现（同一 URL 一会 200 一会 404），所以也要重试
        return s == 429 || s == 404 || s == 500 || s == 502 || s == 503 || s == 504 || s == 0;
    }

    /**
     * 账密登录（论坛账号邮箱 + 密码）：POST {base}/api/auth/login-password。
     * 身份直接来自响应，无需再调 userinfo；同样带限流重试 + 名单校验。
     */
    public static Result loginPassword(String email, String password) {
        final Result out = new Result();
        if (email == null || email.trim().length() == 0 || password == null || password.length() == 0) {
            out.message = "请填写论坛邮箱和密码";
            return out;
        }
        final OAuthClient.LoginUser u = retryLogin(() -> OAuthClient.loginPassword(email, password));
        if (u == null) {
            out.message = "网络异常，请稍后重试";
            return out;
        }
        if (!u.ok) {
            out.message = u.message.length() > 0
                    ? u.message
                    : OAuthClient.friendlyError(u.codeName, u.message, u.httpStatus, u.rawBody);
            return out;
        }
        final Record r = new Record();
        r.token = u.token;
        r.id = u.id;
        r.username = u.username;
        // 服务端没回邮箱时用输入值兜底：白名单是按邮箱配的话，缺了邮箱就等于"不在名单里"
        r.email = u.email.length() > 0 ? u.email : email.trim();
        r.avatar = u.avatar;
        r.savedAt = System.currentTimeMillis();
        r.source = "password";
        out.record = r;
        if (!whitelistHit(r)) {
            out.message = "这个论坛账号不在授权名单里"
                    + (r.username.length() > 0 ? "（当前账号：" + r.username + "）" : "");
            return out;
        }
        out.ok = true;
        return out;
    }
}
