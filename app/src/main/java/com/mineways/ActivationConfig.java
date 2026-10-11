package com.mineways;

/**
 * 激活码相关的对外地址集中在这里：改页面只动这一个文件。
 *
 * <p>签到页面 = 社区站点首页（在社区网页登录后签到，每天可领 1 个激活码）。
 */
public final class ActivationConfig {

    /** 社区网页·签到激活码页（v2 文档 6.1：每天签到领 1 个码，管理员批量码也在这里领）。 */
    public static final String CHECKIN_PAGE = page("/checkin-codes");

    /** 一天能领几个码（用于文案）。 */
    public static final int CODES_PER_DAY = 1;

    /** 拼站点地址，容忍配置里结尾多写的斜杠。 */
    private static String page(String path) {
        String base = BuildConfig.AFF_BASE_URL == null ? "" : BuildConfig.AFF_BASE_URL.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private ActivationConfig() {
    }
}
