package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.cloud.LoginPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 这台机器上「现在拿着哪几张登录牌」的唯一视图。
 *
 * <p>三档身份各存各的，但可以<b>同时持有</b>：论坛登录走原有的 {@link ProAuth}（账密 / 邮箱验证码）
 * 与 {@link OAuthStore}（论坛授权码），Google / 微软存在本类的 {@code mw_social} 里。
 * 之所以不迁移论坛那两份：老用户已经登录着的凭证不能因为这次改动就掉线。
 *
 * <p>本类<b>不做任何放行判定</b> —— 判定在 {@link LoginPolicy}（纯 JVM、可断言），
 * 名单在云控配置里，读法在 {@link LoginAccess}。这里只回答「谁登录了、账号键是什么」。
 */
public final class Identities {

    /** 一份登录身份。{@code scope} 用 {@link LoginPolicy} 的档名。 */
    public static final class Account {
        public String scope = "";
        public String uid = "";
        public String name = "";
        public String email = "";
        public String avatar = "";
        public String token = "";
        public long savedAt;
        /** 有效期（天）。0 = 永不过期（论坛授权码那类由服务端复验，本地不定时）。 */
        public int validDays = VALID_DAYS;

        public boolean live() {
            if (token == null || token.length() == 0) {
                return false;
            }
            if (validDays <= 0) {
                return true;
            }
            if (savedAt <= 0) {
                return false;
            }
            return System.currentTimeMillis() - savedAt < validDays * 86400000L;
        }

        /** 给人看的名字：没名字退回邮箱，再没有退回档名。 */
        public String display() {
            if (name != null && name.trim().length() > 0) {
                return name.trim();
            }
            if (email != null && email.trim().length() > 0) {
                return email.trim();
            }
            return LoginPolicy.scopeCn(scope);
        }

        /** 名单匹配用的键（小写去空白）：uid / email / 用户名 任一命中即算命中。 */
        public String[] keys() {
            List<String> out = new ArrayList<>();
            add(out, uid);
            add(out, email);
            add(out, name);
            return out.toArray(new String[0]);
        }

        private static void add(List<String> out, String v) {
            if (v == null) {
                return;
            }
            String t = v.trim().toLowerCase(Locale.ROOT);
            if (t.length() > 0 && !out.contains(t)) {
                out.add(t);
            }
        }
    }

    /** 本地凭证有效期（天）：到期重新登录一次。与 {@link ProAuth#VALID_DAYS} 同值。 */
    public static final int VALID_DAYS = 30;

    private static final String PREF = "mw_social";
    private static final String F_SUFFIX = "_token";
    private static final String F_UID = "_uid";
    private static final String F_NAME = "_name";
    private static final String F_EMAIL = "_email";
    private static final String F_AVATAR = "_avatar";
    private static final String F_AT = "_saved_at";

    private Identities() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Google / 微软那两档的存储读法（论坛档不在这里，见 {@link #of}）。 */
    public static Account social(Context c, String scope) {
        return read(c, scope);
    }

    public static void saveSocial(Context c, Account a) {
        if (c == null || a == null || a.scope == null) {
            return;
        }
        if (a.savedAt <= 0) {
            a.savedAt = System.currentTimeMillis();
        }
        SharedPreferences.Editor e = sp(c).edit();
        String p = prefix(a.scope);
        e.putString(p + F_SUFFIX, a.token == null ? "" : a.token)
                .putString(p + F_UID, a.uid == null ? "" : a.uid)
                .putString(p + F_NAME, a.name == null ? "" : a.name)
                .putString(p + F_EMAIL, a.email == null ? "" : a.email)
                .putString(p + F_AVATAR, a.avatar == null ? "" : a.avatar)
                .putLong(p + F_AT, a.savedAt)
                .apply();
    }

    public static void dropSocial(Context c, String scope) {
        if (c == null || scope == null) {
            return;
        }
        String p = prefix(scope);
        sp(c).edit()
                .remove(p + F_SUFFIX).remove(p + F_UID).remove(p + F_NAME)
                .remove(p + F_EMAIL).remove(p + F_AVATAR).remove(p + F_AT)
                .apply();
    }

    private static String prefix(String scope) {
        return scope.trim().toLowerCase(Locale.ROOT);
    }

    private static Account read(Context c, String scope) {
        Account a = new Account();
        a.scope = scope;
        if (c == null) {
            return a;
        }
        SharedPreferences p = sp(c);
        String k = prefix(scope);
        a.token = p.getString(k + F_SUFFIX, "");
        a.uid = p.getString(k + F_UID, "");
        a.name = p.getString(k + F_NAME, "");
        a.email = p.getString(k + F_EMAIL, "");
        a.avatar = p.getString(k + F_AVATAR, "");
        a.savedAt = p.getLong(k + F_AT, 0L);
        return a;
    }

    /**
     * 某一档的当前身份。论坛档由两份旧凭证合成（{@link ProAuth} 的白名单登录优先，
     * 缺头像/用户名时用 {@link OAuthStore} 的社区身份补上），Google / 微软读本类的存储。
     */
    public static Account of(Context c, String scope) {
        if (!LoginPolicy.FORUM.equals(scope)) {
            return read(c, scope);
        }
        return forum(c);
    }

    /** 论坛身份：账密 / 验证码 / 授权码 三种登录方式都是这一档。 */
    public static Account forum(Context c) {
        Account a = new Account();
        a.scope = LoginPolicy.FORUM;
        if (c == null) {
            return a;
        }
        ProAuth.Record pr = ProAuth.load(c);
        OAuthStore.Account acc = OAuthStore.load(c);
        boolean prLive = pr.hasToken() && !pr.expired();
        if (prLive) {
            a.token = pr.token;
            a.uid = pr.id > 0 ? String.valueOf(pr.id) : "";
            a.name = pr.username == null ? "" : pr.username;
            a.email = pr.email == null ? "" : pr.email;
            a.avatar = pr.avatar == null ? "" : pr.avatar;
            a.savedAt = pr.savedAt;
        } else if (acc.loggedIn()) {
            a.token = acc.token;
            a.uid = acc.id > 0 ? String.valueOf(acc.id) : "";
            a.name = acc.username == null ? "" : acc.username;
            a.email = "";
            a.avatar = acc.avatar == null ? "" : acc.avatar;
            a.savedAt = 0L;
            a.validDays = 0;      // 授权码那份由服务端复验（ProAuth.revalidate），本地不定时
        }
        if (a.avatar.length() == 0 && acc.loggedIn()) {
            a.avatar = acc.avatar;
        }
        if (a.name.length() == 0 && acc.loggedIn()) {
            a.name = acc.username;
        }
        return a;
    }

    /** 现在有效着的登录牌（按 论坛 → Google → 微软 的固定顺序）。 */
    public static List<String> activeScopes(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : LoginPolicy.SCOPES) {
            if (of(c, s).live()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 全部身份（含没登录的空壳），界面按这个顺序排版。 */
    public static List<Account> all(Context c) {
        List<Account> out = new ArrayList<>();
        for (String s : LoginPolicy.SCOPES) {
            out.add(of(c, s));
        }
        return out;
    }

    public static boolean hasAny(Context c) {
        return !activeScopes(c).isEmpty();
    }

    /** 关于页那一行：三档各是什么状态，一眼看得全。 */
    public static String summary(Context c) {
        List<String> parts = new ArrayList<>();
        for (Account a : all(c)) {
            parts.add(LoginPolicy.scopeCn(a.scope) + "：" + (a.live() ? a.display() : "未登录"));
        }
        return String.join(" · ", parts);
    }

    /** 某一档退出。论坛档把两份旧凭证一起清掉（用户点「退出」不该还剩一半登录态）。 */
    public static void logout(Context c, String scope) {
        if (c == null || scope == null) {
            return;
        }
        if (LoginPolicy.FORUM.equals(scope)) {
            ProAuth.clear(c);
            OAuthStore.logout(c);
        } else {
            dropSocial(c, scope);
        }
    }

    public static void logoutAll(Context c) {
        for (String s : LoginPolicy.SCOPES) {
            logout(c, s);
        }
    }
}
