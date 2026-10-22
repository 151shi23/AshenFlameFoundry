package com.mineways;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.LoginPolicy;
import com.mineways.SocialClients.Cfg;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Google / 微软 的一键授权登录（客户端直连，不引任何 SDK）。
 *
 * <p>为什么用<b>系统浏览器</b>而不是内嵌 WebView：Google 对 WebView 的 UA 直接回
 * {@code 403 disallowed_useragent}，这是它家的硬规则，不是我们能绕的事。
 * 所以主路径是 {@code ACTION_VIEW} 打开浏览器，回跳由清单里那条 {@code mineways://login}
 * 的 intent-filter 交给 {@link SocialRedirectActivity}；一台浏览器都拉不起来的设备
 * （有些定制容器就是没有）才退回 {@link SocialWebActivity}，并在界面上如实说明是内嵌页。
 *
 * <p>整条流程的每一步都往回调里塞人话：<b>哪一步、哪个来源、HTTP 多少、返回体里那句 error</b>。
 * 这类只在真机上成立的功能，看不见错误就等于没做。
 */
public final class SocialLogin {

    public interface Callback {
        /** {@code scope} 是哪一档，{@code ok} 成功与否，{@code message} 成功时是账号名、失败时是原因。 */
        void onDone(boolean ok, String scope, String message);
    }

    public static final String EXTRA_SCOPE = "mw_social_scope";

    /** 一次流程的活状态。5 分钟内没回跳就作废（授权页挂着不管 = 下次重新起一条）。 */
    private static final long TTL_MS = 5 * 60 * 1000L;

    private static volatile String sScope;
    private static volatile String sState;
    private static volatile String sVerifier;
    private static volatile String sUrl;
    private static volatile long sAt;
    private static volatile Callback sCb;
    /** 存应用级 Context（不是 Activity）：换票是后台线程在做的那一步，回跳时界面可能已经重建了。 */
    private static volatile Context appCtx;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SocialLogin() {
    }

    /** 云控现读的配置（Client ID 没下发时 {@link Cfg#enabled()} 就是 false）。 */
    public static Cfg cfg(String scope) {
        return SocialClients.of(new SocialClients.Flag() {
            public String get(String key, String def) {
                return CloudCatalog.gate().flag(key, def);
            }
        }, scope);
    }

    public static boolean available(String scope) {
        return cfg(scope).enabled();
    }

    /** 开始一次登录。失败（含「暂未开放」）都通过 {@code cb} 回调，不抛异常、不弹窗。 */
    public static void start(Activity a, String scope, Callback cb) {
        sCb = cb;
        Cfg c = cfg(scope);
        if (!c.enabled()) {
            report(false, scope, c.whyNot());
            return;
        }
        if (a == null) {
            report(false, scope, "没有可用的界面，登录没开始");
            return;
        }
        appCtx = a.getApplicationContext();
        sScope = scope;
        sState = Pkce.state();
        sVerifier = Pkce.verifier();
        sAt = System.currentTimeMillis();
        String chal = Pkce.challenge(sVerifier);
        if (chal.length() == 0) {
            report(false, scope, "PKCE 算不出来（这台机器的 SHA-256 不可用），登录没开始");
            return;
        }
        sUrl = SocialClients.authorizeUrl(c, sState, chal);

        if (openBrowser(a, sUrl)) {
            report(true, scope, "已在浏览器打开授权页，授权完会自动回来");
            return;
        }
        Intent i = new Intent(a, SocialWebActivity.class);
        i.putExtra(EXTRA_SCOPE, scope);
        try {
            a.startActivity(i);
            report(true, scope, "这台设备没有可用的浏览器，已改用应用内授权页");
        } catch (Throwable t) {
            report(false, scope, "授权页打不开：" + t.getClass().getSimpleName());
        }
    }

    private static boolean openBrowser(Activity a, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 收到回跳（浏览器的 deep link 或内嵌页拦到的跳转都走这里）。
     * {@code from} 只用于把错误说清楚：是哪一条路送回来的。
     */
    public static void handleRedirect(String url, String from) {
        String scope = sScope;
        if (scope == null) {
            report(false, "", "没有正在进行的登录（流程已经结束或进程被杀）· " + from);
            return;
        }
        if (System.currentTimeMillis() - sAt > TTL_MS) {
            clear();
            report(false, scope, "这次授权已经超时作废，重新点一次登录");
            return;
        }
        String[] r = SocialClients.parseCallback(url, sState);
        if (r[0].startsWith("ERR:")) {
            report(false, scope, r[0].substring(4) + "〔回跳原文〕" + brief(url));
            return;
        }
        exchange(scope, r[0], sVerifier, from);
    }

    /** 内嵌页那条路：地址栏跳出回调前缀时直接结束这次流程。 */
    public static boolean isCallback(String url) {
        if (url == null) {
            return false;
        }
        String p = callbackPrefix(pendingScope());
        return p.length() > 0 && url.startsWith(p);
    }

    /**
     * 回调前缀 = 云控那条 {@code login_redirect} 的「协议 + 主机」部分，路径丢掉。
     *
     * <p>为什么不能写死：Google 的客户端类型会把回调登记成 {@code mineways:/oauth2redirect}
     * 或 {@code com.googleusercontent.apps.xxx:/oauth2redirect} 这类形态，微软那边也可能要求
     * 换成 {@code msauth.com.mineways://auth}。前缀跟着云控走，你在后台改了回调
     * 就只改配置，不用我重新出包。
     */
    public static String callbackPrefix(String scope) {
        String r = cfg(scope == null ? "" : scope).redirect;
        if (r.length() == 0) {
            r = SocialClients.DEFAULT_REDIRECT;
        }
        int s = r.indexOf(':');
        if (s < 0) {
            return r;
        }
        int i = s + 1;
        if (r.startsWith("//", i)) {
            i += 2;
        }
        int end = r.indexOf('/', i);
        return end < 0 ? r : r.substring(0, end);
    }

    /** 用 code 换 token，再拉一次 userinfo，最后把身份存进 {@link Identities}。 */
    private static void exchange(final String scope, final String code, final String verifier,
            final String from) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Cfg c = cfg(scope);
                Resp tok = post(c.token, c.tokenForm(code, c.redirect, verifier));
                if (!tok.ok) {
                    fail(scope, "换 token 失败（HTTP " + tok.status + "）：" + tok.errorText()
                            + "〔来源 " + from + "〕");
                    return;
                }
                String accessToken = firstStr(tok.json, "access_token", "accessToken");
                if (accessToken.length() == 0) {
                    fail(scope, "响应里没有 access_token：" + brief(tok.body));
                    return;
                }
                if (c.userinfo.length() == 0) {
                    // 没有资料端点时至少登录成功（身份只有 token），但必须把这件事说出来
                    save(scope, "", "", "", "", accessToken);
                    ok(scope, "已登录（这个端点没配资料接口，名字取不到）");
                    return;
                }
                Resp ui = getBearer(c.userinfo, accessToken);
                if (!ui.ok) {
                    fail(scope, "token 换到了，但取资料失败（HTTP " + ui.status + "）：" + ui.errorText());
                    return;
                }
                String uid = firstStr(ui.json, "sub", "id", "oid");
                String name = firstStr(ui.json, "name", "displayName", "given_name");
                String email = firstStr(ui.json, "email", "mail", "preferred_username", "upn");
                String avatar = firstStr(ui.json, "picture", "avatar");
                if (name.length() == 0 && email.length() > 0) {
                    int at = email.indexOf('@');
                    name = at > 0 ? email.substring(0, at) : email;
                }
                if (uid.length() == 0 && name.length() == 0 && email.length() == 0) {
                    fail(scope, "资料接口回的是空身份〔" + brief(ui.body) + "〕—— "
                            + "多半是 scope 少了 openid/email/profile，或应用没开这些权限");
                    return;
                }
                save(scope, uid, name, email, avatar, accessToken);
                ok(scope, name.length() > 0 ? name : email);
            }
        }, "mw-social-" + scope).start();
    }

    private static void save(String scope, String uid, String name, String email, String avatar,
            String token) {
        Identities.Account a = new Identities.Account();
        a.scope = scope;
        a.uid = uid;
        a.name = name;
        a.email = email;
        a.avatar = avatar;
        a.token = token;
        a.savedAt = System.currentTimeMillis();
        Context c = appCtx;
        if (c != null) {
            Identities.saveSocial(c, a);
        }
    }

    // ============================================================ 传输

    private static final class Resp {
        boolean ok;
        int status;
        String body = "";
        JSONObject json;
        String io = "";

        /** 服务端给的 error 描述优先，其次 HTTP 状态，最后才是 IO 异常名。 */
        String errorText() {
            if (json != null) {
                String e = firstStr(json, "error_description", "error", "message");
                if (e.length() > 0) {
                    return e;
                }
            }
            if (body.length() > 0) {
                return brief(body);
            }
            return io.length() > 0 ? io : "没有返回内容";
        }
    }

    private static Resp post(String url, String form) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent());
            byte[] payload = form.getBytes(UTF8);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            close(os);
            r.status = conn.getResponseCode();
            r.body = read(r.status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            r.ok = r.status >= 200 && r.status < 300;
            r.json = parse(r.body);
        } catch (Throwable t) {
            r.io = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    private static Resp getBearer(String url, String token) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", userAgent());
            r.status = conn.getResponseCode();
            r.body = read(r.status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            r.ok = r.status >= 200 && r.status < 300;
            r.json = parse(r.body);
        } catch (Throwable t) {
            r.io = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    /** 换票与取资料是原生连接，UA 只需要诚实标识自己（授权页的 UA 由浏览器/WebView 出）。 */
    static String userAgent() {
        return "MinewaysMobile/" + BuildConfig.VERSION_NAME + " (Android; oauth-client)";
    }

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstStr(JSONObject o, String... keys) {
        if (o == null) {
            return "";
        }
        for (String k : keys) {
            try {
                Object v = o.opt(k);
                if (v == null || v == JSONObject.NULL) {
                    continue;
                }
                if (v instanceof JSONObject) {
                    continue;
                }
                String s = String.valueOf(v).trim();
                if (s.length() > 0 && !"null".equals(s)) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) {        // 身份接口不该回大字块，超了就是被劫持/门户页
                    break;
                }
            }
            return new String(out.toByteArray(), UTF8);
        } catch (Throwable t) {
            return "";
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 220 ? t : t.substring(0, 220) + "…";
    }

    // ============================================================ 回调

    private static void ok(String scope, String who) {
        clear();
        report(true, scope, who);
    }

    private static void fail(String scope, String why) {
        clear();
        report(false, scope, why);
    }

    private static void report(final boolean ok, final String scope, final String msg) {
        final Callback c = sCb;
        if (c == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                c.onDone(ok, scope, msg);
            }
        });
    }

    private static void clear() {
        sScope = null;
        sState = null;
        sVerifier = null;
        sUrl = null;
    }

    /** 内嵌页要加载的地址（浏览器那条路用不上）。 */
    public static String pendingUrl() {
        return sUrl == null ? "" : sUrl;
    }

    public static String pendingScope() {
        return sScope == null ? "" : sScope;
    }

    /** 这一档现在是什么状态（登录面板那一行要用）。 */
    public static String statusOf(Context c, String scope) {
        if (LoginPolicy.FORUM.equals(scope)) {
            return Identities.forum(c).live() ? "已登录" : "可登录";
        }
        Cfg cfg = cfg(scope);
        if (!cfg.enabled()) {
            return cfg.whyNot();
        }
        return Identities.of(c, scope).live() ? "已登录" : "可一键授权";
    }

    /** 面板上每档的显示名。 */
    public static String label(String scope) {
        return LoginPolicy.scopeCn(scope);
    }

    /** 把某档的登录态显示成人话（已登录：xxx）。 */
    public static String describe(Context c, String scope) {
        Identities.Account a = Identities.of(c, scope);
        return a.live() ? ("已登录：" + a.display()) : "未登录";
    }

    public static String scopeOfIntent(Intent i) {
        if (i == null) {
            return "";
        }
        String s = i.getStringExtra(EXTRA_SCOPE);
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
