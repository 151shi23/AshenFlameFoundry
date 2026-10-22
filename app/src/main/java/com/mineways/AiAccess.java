package com.mineways;

import android.content.Context;
import android.os.Build;

import com.mineways.cloud.CloudCatalog;

import java.util.Locale;

/**
 * AI 功能的放行与环境选择（白名单 / 特定机型 / 特定系统）。
 *
 * <p>产品要求：AI 不是全员开放，按三类人群放行，且不同人群可以走不同环境：
 * <pre>
 * FLAG key=ai_whitelist to=1              # 白名单用户可用（默认 1，写 0 关闭）
 * FLAG key=ai_models to=2210132C,PJD*     # 特定机型（逗号/空格分隔，支持 * 通配）
 * FLAG key=ai_sdk_min to=33               # 特定系统：最低 SDK
 * FLAG key=ai_sdk_max to=99               # 特定系统：最高 SDK
 * FLAG key=ai_all to=1                    # 全员开放（慎用）
 * FLAG key=ai_off to=1                    # 硬闸：新旧包都关（可选）
 * FLAG key=api_env to=prod                # 默认环境
 * FLAG key=api_env_whitelist to=dev       # 白名单用户单独走的环境（优先）
 * </pre>
 *
 * <p><b>兼容旧包</b>：旧包只认 {@code ui_ai}。所以发「仅白名单」时配
 * {@code ui_ai=0}（旧包保持关闭）+ {@code ai_whitelist=1}（新包按白名单放行）；
 * 新包一旦检测到任一 {@code ai_*} 规则，就不再拿 {@code ui_ai} 当总闸。
 *
 * <p>放行规则是 **OR**：白名单命中、机型命中、系统命中，任意一个就开。
 * `ui_ai=0` 仍是总闸，一票否决。
 *
 * <p>环境按人群分：白名单用户优先读 {@code api_env_whitelist}（比如 dev），
 * 其他命中机型/系统的用户读 {@code api_env}（比如 prod）。结果缓存在
 * {@link #env()}，{@link AiClient} 每次请求现取，切环境不用出包。
 */
public final class AiAccess {

    private static volatile String effectiveEnv = "prod";

    // 白名单要读 SharedPreferences，别在每帧组合里重复读；3 秒内的结果直接复用
    private static volatile long whitelistAt = 0L;
    private static volatile boolean whitelistVal = false;

    private AiAccess() {
    }

    /** 重新计算当前人群应使用的环境。登录/退出、回前台、配置刷新后调用。 */
    public static void refresh(Context c) {
        try {
            String env = flag("api_env", "prod");
            if (isWhitelisted(c)) {
                String w = flag("api_env_whitelist", "");
                if (w.length() > 0) {
                    env = w;
                }
            }
            effectiveEnv = env.length() == 0 ? "prod" : env;
        } catch (Throwable t) {
            effectiveEnv = "prod";
        }
    }

    /** 当前生效的环境名（prod / dev / staging）。 */
    public static String env() {
        return effectiveEnv;
    }

    public static boolean isDev() {
        String e = effectiveEnv;
        return "dev".equalsIgnoreCase(e) || "staging".equalsIgnoreCase(e)
                || "test".equalsIgnoreCase(e);
    }

    /** 是否白名单用户（任一种登录 + 名单命中）。带 3 秒缓存。 */
    public static boolean isWhitelisted(Context c) {
        long now = System.currentTimeMillis();
        if (now - whitelistAt < 3000L) {
            return whitelistVal;
        }
        boolean v = false;
        try {
            v = LoginAccess.whitelistUser(c);
        } catch (Throwable ignored) {
        }
        whitelistVal = v;
        whitelistAt = now;
        return v;
    }

    /** 当前用户/设备是否允许使用 AI。 */
    public static boolean allowed(Context c) {
        if ("1".equals(flag("ai_off", ""))) {
            return false;                       // 可选硬闸（新旧包都认）
        }
        // 新规则是否配置过：配了任一 ai_* 就按新规则走，忽略 ui_ai（那是给旧包的总闸）
        boolean newGate = flag("ai_whitelist", "").length() > 0
                || flag("ai_all", "").length() > 0
                || flag("ai_models", "").length() > 0
                || flag("ai_sdk_min", "").length() > 0
                || flag("ai_sdk_max", "").length() > 0;
        if (newGate) {
            if ("1".equals(flag("ai_all", ""))) {
                return true;                    // 全员开放
            }
            if (!"0".equals(flag("ai_whitelist", "")) && isWhitelisted(c)) {
                return true;                    // 白名单
            }
            if (DeviceId.hitAny(flag("ai_models", ""))) {
                return true;                    // 特定机型：内部型号或设置里显示的名字，任一命中即放行
            }
            return sdkHit(flag("ai_sdk_min", ""), flag("ai_sdk_max", "")); // 特定系统
        }
        // 没配新规则：沿用旧语义（ui_ai=0 关闭，其余开放），旧包行为不变
        return !"0".equals(flag("ui_ai", ""));
    }

    /** 界面一次拿完：刷新环境 + 判断放行（避免两次读配置）。 */
    public static boolean refreshAndAllowed(Context c) {
        refresh(c);
        return allowed(c);
    }

    // ---------------------------------------------------------------- 匹配

    /*
     * 机型匹配不再在这里各写一份：那份只认 Build.MODEL，用户写 `ai_models=K70*` 命中不了
     * （小米的 MODEL 是 24016PADG 这种代号）。现在统一走 DeviceId —— 内部型号与设置里
     * 显示的名字，任意一个命中名单里任意一条即生效。
     */

    /** 系统版本匹配：只填一端就按开区间处理；两端都没填 = 不按系统放行。 */
    static boolean sdkHit(String minS, String maxS) {
        boolean hasMin = minS != null && minS.trim().length() > 0;
        boolean hasMax = maxS != null && maxS.trim().length() > 0;
        if (!hasMin && !hasMax) {
            return false;
        }
        int sdk = Build.VERSION.SDK_INT;
        int min = hasMin ? parseInt(minS, 0) : 0;
        int max = hasMax ? parseInt(maxS, Integer.MAX_VALUE) : Integer.MAX_VALUE;
        return sdk >= min && sdk <= max;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 读一条云控旗标（{@link AiClient} 也用它取 {@code ai_system} 人设）。 */
    static String flag(String key, String def) {
        try {
            String v = CloudCatalog.gate().flag(key, def);
            return v == null ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }
}
