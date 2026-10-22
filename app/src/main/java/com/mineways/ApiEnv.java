package com.mineways;

import com.mineways.cloud.CloudCatalog;

/**
 * API 环境（正式 / 开发）的运行时选择。
 *
 * <p>为什么不在编译期写死：AI 上游在正式环境还没就绪，开发环境是好的。产品要求
 * 「云控切换正式/开发环境」，所以每次请求都现读云控：
 *
 * <pre>
 * FLAG key=api_env to=dev            # 默认环境（不填 = 正式）
 * FLAG key=api_env_whitelist to=dev  # 白名单用户单独走的环境（优先，见 AiAccess）
 * FLAG key=api_base to=https://...   # 直接指定域名（优先级最高，便于灰度/临时切机）
 * </pre>
 *
 * <p>读取失败、没网、配置里没写 → 一律回正式环境，绝不让界面因为一条配置挂掉。
 * 注意：切换只影响 {@link AiClient}；激活码 / OAuth 仍走各自编译期的正式地址
 * （那两条链路牵扯登录态，不在这次改动范围里）。
 */
public final class ApiEnv {

    /** 正式环境（与 local.properties 的 AFF_BASE_URL 同一支，这里只做兜底）。 */
    public static final String PROD = "https://wg5p835kmb.coze.site";

    /** 开发环境（Coze dev 部署，5000 端口映射）。 */
    public static final String DEV =
            "https://44e50670-dbb8-4f00-b357-0ee078860477-5000.dev.coze.site";

    private ApiEnv() {
    }

    /** 当前是否开发环境（由 {@link AiAccess} 按人群算好：白名单可单独走 dev）。 */
    public static boolean isDev() {
        return AiAccess.isDev();
    }

    /** 当前 API 根地址（不带结尾斜杠）。 */
    public static String base() {
        String custom = flag("api_base", "");
        if (custom.startsWith("http://") || custom.startsWith("https://")) {
            return trimSlash(custom);
        }
        if (isDev()) {
            return DEV;
        }
        String prod = BuildConfig.AFF_BASE_URL;
        return (prod != null && prod.length() > 0) ? trimSlash(prod) : PROD;
    }

    /** 给界面显示用的一句话。 */
    public static String label() {
        return isDev() ? "开发环境" : "正式环境";
    }

    private static String flag(String key, String def) {
        try {
            String v = CloudCatalog.gate().flag(key, def);
            return v == null ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }

    private static String trimSlash(String s) {
        String v = s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
