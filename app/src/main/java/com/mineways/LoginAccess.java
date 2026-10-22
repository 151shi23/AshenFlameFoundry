package com.mineways;

import android.content.Context;

import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureGate;
import com.mineways.cloud.LoginPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录门禁在安卓侧的接线：把 {@link Identities}（谁登录了）、{@link FeatureGate}（云控名单与 gate）
 * 和 {@link ProAuth}（包内出厂名单）拼成 {@link LoginPolicy.Env}，再给出界面能直接显示的结果。
 *
 * <p><b>判定规则一条都不写在这里</b> —— 全在 {@link LoginPolicy}，那层是纯 JVM 的、有 103 条断言。
 * 这里只做取数：现读云控（云控改完不用重装），现读本地凭证（刚登录完立刻生效）。
 */
public final class LoginAccess {

    private LoginAccess() {
    }

    /** 一个功能的判定结果，界面上每一句都要有出处。 */
    public static final class Verdict {
        public boolean allowed;
        /** 云控写的 gate 原文（空 = 这一项没配门禁）。 */
        public String gate = "";
        /** 需要什么：{@code 白名单·论坛登录} / {@code 登录·任一种登录}。 */
        public String need = "";
        /** 拦下来时给人看的话，缺哪张牌、还是牌对了名单没有，说清楚。 */
        public String reason = "";
        /** 卡片角标（未解锁时才用）。 */
        public String badge = "";
        /** 是谁改了它（状态面板与「问 AI」要能报出那条指令）。 */
        public String source = "";

        public boolean gated() {
            return gate != null && gate.length() > 0;
        }
    }

    /** 现读的判定环境。 */
    public static LoginPolicy.Env env(final Context c) {
        return new LoginPolicy.Env() {
            @Override
            public List<String> activeScopes() {
                return Identities.activeScopes(c);
            }

            @Override
            public boolean listed(String scope) {
                return hitsList(c, scope);
            }

            @Override
            public String flag(String key, String def) {
                return CloudCatalog.gate().flag(key, def);
            }
        };
    }

    /**
     * 这一档的名单里有没有对应身份的账号。
     *
     * <p>三层来源，按「谁说了都算但踢人最大」的顺序叠：
     * ① 云端显式 {@code WL drop}（不写 list= 的那份）是全局踢人，命中就直接否；
     * ② 包内出厂名单只兜论坛档（Google / 微软的名单只能云控下发）；
     * ③ 云端分档名单：共享名单四档通吃，各档名单只管自己那一档。
     */
    public static boolean hitsList(Context c, String scope) {
        if (c == null) {
            return false;
        }
        FeatureGate g = CloudCatalog.gate();
        if (LoginPolicy.ANY.equals(scope)) {
            for (String s : LoginPolicy.SCOPES) {
                if (hitsList(c, s)) {
                    return true;
                }
            }
            return false;
        }
        Identities.Account a = Identities.of(c, scope);
        if (!a.live()) {
            return false;
        }
        if (g.active() && g.wlKickedAnywhere(a.keys())) {
            return false;
        }
        if (LoginPolicy.FORUM.equals(scope) && ProAuth.builtinWhitelistHit(ProAuth.load(c))) {
            return true;
        }
        return g.active() && (g.wlShared(a.keys()) || g.wlInList(scope, a.keys()));
    }

    /**
     * 这个功能现在能不能用。
     *
     * @param gateSpec 云控的 gate 原文（{@code FeatureGate.gateOf(key)}）
     * @param kind     该功能的档位（{@link FeatureGate#KIND_FREE} / {@code KIND_LIMIT} / {@code KIND_PAID}）；
     *                 没有 gate 时只有 {@code KIND_PAID} 才退回旧的白名单判定，
     *                 免费功能绝不能因为「这条没配门禁」被误判成要登录
     */
    public static Verdict check(Context c, String key, String gateSpec, int kind) {
        Verdict v = new Verdict();
        v.gate = gateSpec == null ? "" : gateSpec;
        LoginPolicy.Gate gate = LoginPolicy.parse(v.gate);
        if (gate == null || gate.bad.length() > 0) {
            // 没配门禁（或那条写错到没落地）：只有旧的限制档才沿用白名单判定
            boolean legacyOk = kind != FeatureGate.KIND_PAID || legacyWhitelist(c);
            v.allowed = legacyOk;
            v.need = legacyOk ? "" : "白名单";
            v.badge = legacyOk ? "" : "白名单";
            v.reason = legacyOk ? "" : "这个功能只对白名单账号开放 —— 到「关于」页登录一下。";
            v.source = gateSource(c, key);
            return v;
        }
        LoginPolicy.Env env = env(c);
        v.need = LoginPolicy.requireText(gate);
        v.allowed = LoginPolicy.allowed(env, gate);
        if (!v.allowed) {
            v.reason = LoginPolicy.denyReason(env, gate);
            v.badge = badge(env, gate);
        }
        v.source = gateSource(c, key);
        return v;
    }

    /** 角标：短，但要说清缺的是哪张牌。 */
    private static String badge(LoginPolicy.Env env, LoginPolicy.Gate gate) {
        List<String> active = env.activeScopes();
        if (active == null || active.isEmpty()) {
            return LoginPolicy.WL.equals(gate.family) ? "白名单" : "需登录";
        }
        for (String r : gate.scopes) {
            if (LoginPolicy.satisfies(env, r, active)) {
                return "白名单";                      // 牌有了，缺的是名单
            }
        }
        return "需" + LoginPolicy.scopeCn(gate.scopes.get(0));
    }

    /** 旧的「白名单功能」判定（内置名单 ∪ 云端增 − 云端删）。 */
    public static boolean legacyWhitelist(Context c) {
        return ProAuth.isAuthorized(c);
    }

    /**
     * 这台机器现在算不算「白名单用户」：任一登录身份命中了名单（共享名单或自己那一档）。
     * 用在那些<b>不与具体功能绑定</b>的地方 —— 页签副标题、以及「白名单账号用限制功能不用再扣激活码」。
     */
    public static boolean whitelistUser(Context c) {
        if (c == null) {
            return false;
        }
        return hitsList(c, LoginPolicy.ANY) || legacyWhitelist(c);
    }

    /** 是谁给这个功能配的 gate —— 状态面板与 AI 都要能报出处。 */
    private static String gateSource(Context c, String key) {
        if (c == null || key == null) {
            return "";
        }
        FeatureGate raw = CloudCatalog.gate();
        if (!raw.active()) {
            return "";
        }
        FeatureGate.State s = raw.stateOf(key, "", FeatureGate.KIND_FREE);
        return s == null || s.source == null ? "" : s.source;
    }

    /** 关于页那一行的结论部分：几档登录、命中哪些名单、论坛是不是被降权。 */
    public static String debugLine(Context c) {
        return LoginPolicy.debugLine(env(c));
    }

    /** 云控现在一共给多少个功能配了门禁。 */
    public static int gatedCount() {
        FeatureGate g = CloudCatalog.gate();
        return g.active() ? g.gatedCount() : 0;
    }

    /** 云控现在配了门禁的功能（{@code 键 = gate 原文}，状态面板与 AI 都要能报出处）。 */
    public static List<String> gatedKeys() {
        List<String> out = new ArrayList<>();
        FeatureGate g = CloudCatalog.gate();
        if (!g.active()) {
            return out;
        }
        gatedCount();                      // 先让门面把 gates 表算出来（gatedCount 内部会 ensureResolved）
        for (java.util.Map.Entry<String, String> e : g.raw().gates.entrySet()) {
            out.add(e.getKey() + " = " + e.getValue());
        }
        return out;
    }
}
