package com.mineways.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 棱镜玩机 · 本机系统画像。
 *
 * <p>「自动选对应工具界面」靠两层判断：
 * <ol>
 *   <li><b>品牌层</b>：{@code Build.MANUFACTURER / BRAND / DISPLAY} + 各家 ROM 的
 *       system property（澎湃 / OriginOS / ColorOS）。这决定加载哪张功能表；</li>
 *   <li><b>功能层</b>：具体开关能不能用，最终仍由 {@code settings get} 探键决定
 *       （见 {@link PrismShell}），品牌判断只负责「先展示什么」，不假装功能一定可用。</li>
 * </ol>
 *
 * <p>用户可以手动切换适配系统（ROM 移植 / 国际版 / 判断错误时），选择存 SharedPreferences。
 */
public final class PrismProfile {

    public enum Vendor {
        XIAOMI, VIVO, OPPO, GENERIC
    }

    private static final String PREFS = "prism_toolbox";
    private static final String K_OVERRIDE = "vendor_override";

    public final Vendor vendor;
    /** 系统名：澎湃OS / OriginOS / ColorOS / Android。 */
    public final String systemName;
    /** ROM 版本（可能为空）。 */
    public final String version;
    /** 机型展示名。 */
    public final String device;
    /** true=按品牌自动识别；false=手动或云端指定。 */
    public final boolean auto;
    /** 来源：0=本机自动识别，1=用户手动指定，2=云端下发 prism_vendor。 */
    public final int source;

    private PrismProfile(Vendor vendor, String systemName, String version, String device, int source) {
        this.vendor = vendor;
        this.systemName = systemName;
        this.version = version;
        this.device = device;
        this.source = source;
        this.auto = source == 0;
    }

    // ---------------------------------------------------------------- 识别

    public static PrismProfile detect(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String override = sp.getString(K_OVERRIDE, "");
        DeviceInfo dev = deviceInfo();

        // 1) 云控优先：运营可整批指定“这台机器按哪套界面跑”（适配 ROM 移植/国际版）
        Vendor forced = vendorFromKey(PrismFlags.vendor(ctx));
        if (forced != null) {
            return new PrismProfile(forced, nameOf(forced), versionOf(forced), dev.name, 2);
        }
        // 2) 用户在页面上手动切换
        if (!TextUtils.isEmpty(override)) {
            Vendor v = parseVendor(override);
            return new PrismProfile(v, nameOf(v), versionOf(v), dev.name, 1);
        }
        // 3) 本机品牌 + ROM 属性
        Vendor v = vendorOf(dev.manufacturer, dev.brand, dev.display);
        return new PrismProfile(v, nameOf(v), versionOf(v), dev.name, 0);
    }

    /** 识别链路自己出错时的兜底：只显示通用项，绝不因为读属性失败而白屏。 */
    public static PrismProfile fallback() {
        String name = (nz(Build.MANUFACTURER) + " " + nz(Build.MODEL)).trim();
        return new PrismProfile(Vendor.GENERIC, "Android",
                Build.VERSION.RELEASE, name, 0);
    }

    /** 云控字符串 → 厂商；auto/空/未知返回 null（=不干预）。 */
    private static Vendor vendorFromKey(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || "auto".equals(k)) return null;
        if ("xiaomi".equals(k) || "mi".equals(k) || "hyperos".equals(k)) return Vendor.XIAOMI;
        if ("vivo".equals(k) || "iqoo".equals(k) || "originos".equals(k)) return Vendor.VIVO;
        if ("oppo".equals(k) || "oneplus".equals(k) || "realme".equals(k) || "coloros".equals(k)) {
            return Vendor.OPPO;
        }
        if ("generic".equals(k) || "android".equals(k)) return Vendor.GENERIC;
        return null;
    }

    /** 手动指定（空串 = 恢复自动）。 */
    public static void setOverride(Context ctx, String vendor) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_OVERRIDE, vendor == null ? "" : vendor).apply();
    }

    public static void clearOverride(Context ctx) {
        setOverride(ctx, "");
    }

    public static Vendor parseVendor(String s) {
        if (s == null) return Vendor.GENERIC;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("xiaomi")) return Vendor.XIAOMI;
        if (t.startsWith("vivo")) return Vendor.VIVO;
        if (t.startsWith("oppo")) return Vendor.OPPO;
        return Vendor.GENERIC;
    }

    private static Vendor vendorOf(String man, String brand, String display) {
        String m = nz(man).toLowerCase(Locale.ROOT);
        String b = nz(brand).toLowerCase(Locale.ROOT);
        String d = nz(display).toLowerCase(Locale.ROOT);
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
                || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
                || d.contains("hyperos") || d.contains("miui")
                || !prop("ro.mi.os.version.name").isEmpty()
                || !prop("ro.miui.ui.version.name").isEmpty()) {
            return Vendor.XIAOMI;
        }
        if (m.contains("vivo") || m.contains("iqoo")
                || b.contains("vivo") || b.contains("iqoo")
                || !prop("ro.vivo.os.name").isEmpty()
                || !prop("ro.vivo.os.version").isEmpty()) {
            return Vendor.VIVO;
        }
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")
                || b.contains("oppo") || b.contains("oneplus") || b.contains("realme")
                || !prop("ro.build.version.oplusrom").isEmpty()
                || !prop("ro.build.version.opporom").isEmpty()
                || !prop("ro.oplus.version").isEmpty()) {
            return Vendor.OPPO;
        }
        return Vendor.GENERIC;
    }

    /** 本机型号：优先市场名（DeviceId 已有完整逻辑），失败退回 Build.MODEL。 */
    private static String deviceName() {
        try {
            String s = com.mineways.DeviceId.marketing();
            if (!TextUtils.isEmpty(s)) return s;
        } catch (Throwable ignored) {
        }
        return nz(Build.MANUFACTURER) + " " + nz(Build.MODEL);
    }

    private static DeviceInfo deviceInfo() {
        DeviceInfo d = new DeviceInfo();
        d.manufacturer = nz(Build.MANUFACTURER);
        d.brand = nz(Build.BRAND);
        d.display = nz(Build.DISPLAY);
        d.name = deviceName().trim();
        return d;
    }

    private static final class DeviceInfo {
        String manufacturer, brand, display, name;
    }

    private static String nameOf(Vendor v) {
        switch (v) {
            case XIAOMI:
                if (!prop("ro.mi.os.version.name").isEmpty()) return "澎湃OS";
                if (!prop("ro.miui.ui.version.name").isEmpty()) return "MIUI";
                return "澎湃OS";
            case VIVO: {
                String n = prop("ro.vivo.os.name");
                if (n.toLowerCase(Locale.ROOT).contains("origin")) return "OriginOS";
                if (!n.isEmpty()) return n;
                return "OriginOS";
            }
            case OPPO:
                return "ColorOS";
            default:
                return "Android";
        }
    }

    private static String versionOf(Vendor v) {
        switch (v) {
            case XIAOMI: {
                String h = firstProp("ro.mi.os.version.name", "ro.mi.os.version.incremental");
                if (!h.isEmpty()) return h;
                return prop("ro.miui.ui.version.name");
            }
            case VIVO: {
                String origin = firstProp("ro.vivo.os.version", "ro.vivo.os.build.display.id");
                if (!origin.isEmpty()) return origin;
                return prop("ro.vivo.os.name");
            }
            case OPPO: {
                String color = firstProp("ro.build.version.oplusrom", "ro.build.version.opporom",
                        "ro.oplus.version", "ro.build.version.realme");
                if (color.isEmpty()) return "";
                return color.toUpperCase(Locale.ROOT).startsWith("V") ? color : "V" + color;
            }
            default:
                return Build.VERSION.RELEASE;
        }
    }

    // ---------------------------------------------------------------- 展示

    public String vendorKey() {
        switch (vendor) {
            case XIAOMI: return "xiaomi";
            case VIVO: return "vivo";
            case OPPO: return "oppo";
            default: return "generic";
        }
    }

    public String title() {
        StringBuilder b = new StringBuilder(device);
        if (version != null && !version.isEmpty()) b.append(" · ").append(systemName).append(' ').append(version);
        else b.append(" · ").append(systemName);
        return b.toString();
    }

    public String subtitle() {
        if (source == 2) return "云端指定适配系统";
        if (source == 1) return "手动指定适配系统";
        return "自动识别：品牌 + ROM 属性";
    }

    /** 品牌色（用作标题旁的小色条，不参与 MwViews 的文字重绘）。 */
    public int accent() {
        switch (vendor) {
            case XIAOMI: return 0xFFFF6A2B;
            case VIVO: return 0xFF1E6FFF;
            case OPPO: return 0xFF00A86B;
            default: return 0xFFD9603A;
        }
    }

    public String brandLabel() {
        switch (vendor) {
            case XIAOMI: return "小米 / 红米 · 澎湃OS";
            case VIVO: return "vivo / iQOO · OriginOS";
            case OPPO: return "OPPO / 一加 / 真我 · ColorOS";
            default: return "通用 Android";
        }
    }

    // ---------------------------------------------------------------- props

    private static final Map<String, String> PROP_CACHE = new HashMap<String, String>();

    /** 读 system property：getprop 子进程（避免隐藏 API 限制），进程内缓存。 */
    public static String prop(String key) {
        synchronized (PROP_CACHE) {
            String cached = PROP_CACHE.get(key);
            if (cached != null) return cached;
        }
        String val = "";
        Process p = null;
        try {
            try {
                p = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            } catch (Throwable t) {
                p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(line.trim());
            }
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            val = sb.toString().trim();
        } catch (Throwable ignored) {
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (PROP_CACHE) {
            PROP_CACHE.put(key, val);
        }
        return val;
    }

    private static String firstProp(String... keys) {
        for (String k : keys) {
            String v = prop(k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
