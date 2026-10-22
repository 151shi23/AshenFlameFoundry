package com.mineways;

import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本机型号的<b>两个身份</b>，以及"名单里任意一条命中任意一个身份即生效"的匹配。
 *
 * <p>为什么要两个：
 * <ul>
 *   <li>{@link #internal()} —— 系统给App的 {@code Build.BRAND + Build.MODEL}，
 *       形如 {@code Redmi 24016PADG}。写配置的人看不见它，但它是唯一稳定的机器可读标识；</li>
 *   <li>{@link #marketing()} —— 用户在「设置 → 我的设备」里<b>看到的那个名字</b>
 *       （{@code K90 Ultra} / {@code Redmi K70}）。云控名单、公告、群里反馈的都是这个。</li>
 * </ul>
 * 只用 {@code Build.MODEL} 会漏：小米的 MODEL 常常是 {@code 24016PADG} 这种内部代号，
 * 用户说"K70 卡"，规则写 {@code K70*} 就永远命中不上。所以两边都收进 {@link #names()}，
 * <b>任一条命中任一身份即生效</b>。
 *
 * <p>市场名安卓没有公开 API，只能读系统属性（{@code ro.product.marketname} 等）。
 * 读不到就退回 {@code Build.MODEL}，并且把<b>来源</b>记在 {@link #marketingSource()} 里 ——
 * 这样界面上能显示"这台机器识别成什么、从哪读来的"，不对一眼就能看出来，不用猜。
 */
public final class DeviceId {

    /** 依次尝试的属性名：通用在前，小米/红米系在后。 */
    private static final String[] KEYS = {
            "ro.product.marketname",
            "ro.product.system.marketname",
            "ro.product.vendor.marketname",
            "ro.product.device.marketname",
            "ro.product.odm.marketname",
            "ro.config.marketing_name",
            "ro.product.model",
    };

    private static volatile List<String> names;
    private static volatile String marketing;
    private static volatile String marketingFrom;

    private DeviceId() {
    }

    public static String brand() {
        return clean(Build.BRAND);
    }

    public static String model() {
        return clean(Build.MODEL);
    }

    /** 官方内部身份：品牌 + 型号。 */
    public static String internal() {
        String b = brand();
        String m = model();
        if (b.length() == 0) {
            return m;
        }
        if (m.length() == 0 || m.toLowerCase(Locale.ROOT).startsWith(b.toLowerCase(Locale.ROOT))) {
            return m;
        }
        return b + " " + m;
    }

    /** 设置里对用户显示的名字；读不到退回型号。 */
    public static String marketing() {
        if (marketing == null) {
            resolve();
        }
        return marketing;
    }

    /** 市场名是从哪个属性读来的（{@code fallback-model} = 没读到，用的型号）。 */
    public static String marketingSource() {
        if (marketing == null) {
            resolve();
        }
        return marketingFrom == null ? "" : marketingFrom;
    }

    private static synchronized void resolve() {
        if (marketing != null) {
            return;
        }
        String got = null;
        String from = null;
        for (String k : KEYS) {
            String v = prop(k);
            if (v != null && v.trim().length() > 0 && !looksLikeCode(v)) {
                got = clean(v);
                from = k;
                break;
            }
        }
        if (got == null) {
            got = internal();
            from = "fallback-internal";
        }
        // 品牌没写在市场名里时补上，方便「Redmi K70」这种整条也能命中
        String b = brand();
        if (b.length() > 0 && got.toLowerCase(Locale.ROOT).indexOf(b.toLowerCase(Locale.ROOT)) < 0) {
            marketing = b + " " + got;
        } else {
            marketing = got;
        }
        marketingFrom = from;
    }

    /** 纯代号（{@code 24016PADG}、{@code M2012K11C}）不适合当对用户显示的名字。 */
    private static boolean looksLikeCode(String v) {
        String s = v.trim();
        if (s.length() == 0) {
            return true;
        }
        // 全是大写字母与数字、且没有空格 → 判定为内部代号
        boolean alnumUpper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || (c >= 'A' && c <= 'Z') || c == '-')) {
                alnumUpper = false;
                break;
            }
        }
        return alnumUpper && s.indexOf(' ') < 0;
    }

    /** 反射读系统属性；拿不到（隐藏 API 被限制、方法不存在）就回 null，绝不抛。 */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 所有可以用来匹配的身份：内部、市场、型号、品牌、代号，去重后小写。 */
    public static List<String> names() {
        List<String> cached = names;
        if (cached != null) {
            return cached;
        }
        Set<String> out = new LinkedHashSet<>();
        add(out, internal());
        add(out, marketing());
        add(out, model());
        add(out, brand());
        add(out, clean(Build.DEVICE));
        add(out, clean(Build.PRODUCT));
        add(out, clean(Build.HARDWARE));
        // 「品牌 市场名」的组合也收进来：设置里显示的就是这种整串
        String b = brand();
        String m = marketing();
        if (b.length() > 0 && m.length() > 0) {
            add(out, b + " " + m);
        }
        cached = new ArrayList<>(out);
        names = cached;
        return cached;
    }

    private static void add(Set<String> set, String s) {
        if (s != null && s.trim().length() > 0) {
            set.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** 一条规则名单（逗号/分号/空格分隔，支持 {@code *} 通配）是否命中本机任一身份。 */
    public static boolean hitAny(String csvPatterns) {
        return match(csvPatterns, names()) != null;
    }

    /**
     * 命中的话返回<b>那一条规则原文</b>（界面上要说清"因为你这台是 K70，命中了 K70* 这条"）；
     * 没命中返回 null。空名单不算命中。
     */
    public static String match(String csvPatterns, List<String> identities) {
        String raw = csvPatterns == null ? "" : csvPatterns.trim();
        if (raw.length() == 0) {
            return null;
        }
        for (String p : raw.split("[,，;；\n]+")) {
            String pat = p.trim().toLowerCase(Locale.ROOT);
            if (pat.length() == 0) {
                continue;
            }
            for (String name : identities) {
                if (one(pat, name) || one(pat.replace(" ", ""), name.replace(" ", ""))) {
                    return p.trim();
                }
            }
        }
        return null;
    }

    /** 单条模式匹配：带 {@code *} 走通配（find 语义，能命中带品牌前缀的整名），不带就"等于或包含"。 */
    private static boolean one(String pat, String name) {
        if (pat.indexOf('*') >= 0) {
            try {
                return java.util.regex.Pattern.compile(pat.replace(".", "\\.").replace("*", ".*"))
                        .matcher(name).find();
            } catch (Throwable t) {
                return name.contains(pat.replace("*", ""));
            }
        }
        return name.equals(pat) || name.contains(pat);
    }

    /** 给界面/诊断用的一行：两个身份 + 市场名来源。 */
    public static String debugLine() {
        return "内部身份：" + internal() + "　显示名：" + marketing()
                + "（来源 " + marketingSource() + "）";
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
