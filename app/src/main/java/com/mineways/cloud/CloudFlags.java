package com.mineways.cloud;

import java.util.Locale;

/**
 * 全 App 唯一的云控旗标解析层。
 *
 * <p>语义统一（所有功能一致，不再各自写一份）：
 * <ul>
 *   <li>{@code str}：去首尾空白；读不到返回默认值（空串原样保留）</li>
 *   <li>{@code bool}：1/true/on/yes 为真；0/false/off/no 为假；其它或空串取默认</li>
 *   <li>{@code num}：整数并夹在 [min,max]；解析失败取默认</li>
 * </ul>
 * 任何异常都回退默认值，云控坏了不影响启动。</p>
 */
public final class CloudFlags {

    private CloudFlags() {
    }

    // ---------------------------------------------------------------- 字符串

    public static String str(String key, String def) {
        try {
            return norm(CloudCatalog.gate().flag(key, def), def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static String str(FeatureGate g, String key, String def) {
        try {
            return norm(g == null ? null : g.flag(key, def), def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String norm(String v, String def) {
        return v == null ? def : v.trim();
    }

    // ---------------------------------------------------------------- 布尔

    public static boolean bool(String key, boolean def) {
        return parseBool(str(key, def ? "1" : "0"), def);
    }

    public static boolean bool(FeatureGate g, String key, boolean def) {
        return parseBool(str(g, key, def ? "1" : "0"), def);
    }

    public static boolean parseBool(String v, boolean def) {
        if (v == null) return def;
        final String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        if ("1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s)) return true;
        if ("0".equals(s) || "false".equals(s) || "off".equals(s) || "no".equals(s)) return false;
        return def;
    }

    // ---------------------------------------------------------------- 整数

    public static int num(String key, int def, int min, int max) {
        return parseNum(str(key, String.valueOf(def)), def, min, max);
    }

    public static int num(FeatureGate g, String key, int def, int min, int max) {
        return parseNum(str(g, key, String.valueOf(def)), def, min, max);
    }

    public static int parseNum(String v, int def, int min, int max) {
        int n = def;
        if (v != null && !v.trim().isEmpty()) {
            try {
                n = Integer.parseInt(v.trim());
            } catch (Throwable ignored) {
                n = def;
            }
        }
        return Math.max(min, Math.min(max, n));
    }
}
