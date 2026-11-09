package com.mineways.cloud;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 已安装包名探针（云控条件用）。
 *
 * <p>两套用法：
 * <ul>
 *   <li>{@link UiFlags} 的 {@code ui_hide_*_pkgs} 直接问 {@link #hasAny(String)}；</li>
 *   <li>规则表达式里的 {@code packages} / {@code pkg_count} 变量（{@code if="'com.xxx' 在 packages"}）。</li>
 * </ul>
 *
 * <p>进程内缓存一次；{@link #invalidate()} 供安装/卸载后刷新。读不到包列表时
 * 返回空表而不是抛异常 —— 云控条件按“没命中”处理，宁可少隐藏也不误隐藏。
 */
public final class PkgProbe {

    private static volatile Context app;
    private static volatile List<String> cache;
    private static volatile String csv;

    private PkgProbe() {
    }

    public static void init(Context c) {
        if (c == null) return;
        app = c.getApplicationContext();
        cache = null;
        csv = null;
    }

    public static void invalidate() {
        cache = null;
        csv = null;
    }

    public static List<String> installed() {
        List<String> local = cache;
        if (local != null) return local;
        Context c = app;
        if (c == null) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        try {
            List<PackageInfo> list = c.getPackageManager()
                    .getInstalledPackages(0);
            for (PackageInfo pi : list) {
                if (pi != null && pi.packageName != null && !pi.packageName.isEmpty()) {
                    out.add(pi.packageName.toLowerCase(Locale.ROOT));
                }
            }
            Collections.sort(out);
        } catch (Throwable ignored) {
        }
        cache = out;
        return out;
    }

    /** 逗号/分号/空白分隔的包名（支持 {@code com.tencent.*} 通配）里，命中任意一个就 true。 */
    public static boolean hasAny(String patterns) {
        return matchAny(patterns, installed());
    }

    /** 纯匹配（可注入包列表，JVM 可测）。 */
    public static boolean matchAny(String patterns, List<String> installed) {
        String raw = patterns == null ? "" : patterns.trim();
        if (raw.isEmpty() || installed == null || installed.isEmpty()) return false;
        for (String p : raw.split("[,，;；\\n]+")) {
            String pat = p.trim().toLowerCase(Locale.ROOT);
            if (pat.isEmpty()) continue;
            for (String pkg : installed) {
                if (pkg != null && one(pat, pkg.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static boolean one(String pat, String pkg) {
        if (pat.indexOf('*') >= 0) {
            try {
                return java.util.regex.Pattern.compile(pat.replace(".", "\\.").replace("*", ".*"))
                        .matcher(pkg).find();
            } catch (Throwable t) {
                return pkg.contains(pat.replace("*", ""));
            }
        }
        return pkg.equals(pat);
    }

    /** 条件变量 {@code packages}：逗号分隔的包名（进程内缓存）。 */
    public static String csv() {
        String local = csv;
        if (local != null) return local;
        StringBuilder b = new StringBuilder();
        for (String pkg : installed()) {
            if (b.length() > 0) b.append(',');
            b.append(pkg);
        }
        csv = b.toString();
        return csv;
    }

    public static int count() {
        return installed().size();
    }

    /** 供条件求值：确保 app 已注入后返回 csv。 */
    public static String csv(Context c) {
        if (app == null) init(c);
        return csv();
    }
}
