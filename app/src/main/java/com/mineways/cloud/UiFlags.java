package com.mineways.cloud;

import com.mineways.DeviceId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 界面控件 / 功能页 / 说明文字的云控开关。
 *
 * <p>这是「界面本身」的总闸，和 {@link FeatureGate} 不重叠：
 * <ul>
 *   <li>{@code FEAT hide/disable/label/desc}：按功能条目控制（某一张卡片）；</li>
 *   <li>{@code ui_show_*} / {@code ui_hide}：按控件控制（导航、顶栏、搜索、AI、快捷、分类、介绍、页脚…）；</li>
 *   <li>{@code ui_hide_pages} / {@code ui_only_pages}：按功能页控制（整个工具页连入口一起不出现）；</li>
 *   <li>{@code ui_hide_desc} / {@code ui_desc_*}：说明文字的开关与覆盖文案。</li>
 * </ul>
 *
 * <p>所有键默认「显示 / 不改文案」，读不到或写坏都不会把界面弄没。列表类键统一用
 * 逗号/分号/空白分隔（与 AiPolicy、GlassRules 同一套写法）。
 *
 * <pre>
 * FLAG ui_show_nav=0            底部导航栏
 * FLAG ui_show_topbar=0         玻璃顶栏（含标题与三点按钮）
 * FLAG ui_show_search=0         搜索框
 * FLAG ui_show_ai=0             AI 入口与面板
 * FLAG ui_show_quick=0          快捷访问
 * FLAG ui_show_cats=0           分类栏
 * FLAG ui_show_intro=0          页签说明条
 * FLAG ui_show_footer=0         列表页脚
 * FLAG ui_show_story=0          故事墙横幅
 * FLAG ui_show_video=0          视频展示栏
 * FLAG ui_show_empty=0          空状态面板
 * FLAG ui_show_pageglass=0      原生工具页悬浮玻璃顶栏
 * FLAG ui_hide=nav,search,ai   上面的快捷写法（等价于逐个 ui_show_*=0）
 * FLAG ui_hide_desc=1           隐藏所有说明文字（卡片副标题/页签统计/分组副标题/关于页介绍）
 * FLAG ui_desc_intro=文字        首页说明条覆盖文案
 * FLAG ui_desc_footer=文字       首页页脚覆盖文案
 * FLAG ui_desc_about=文字        关于页介绍覆盖文案
 * FLAG ui_desc_tool_prism=文字   单个工具卡片副标题覆盖（键 = pageKey，见 HomeIndex.Row.iconKey）
 * FLAG ui_hide_pages=prism,mocap 整个功能页隐藏（入口不出现，深链也会被拦）
 * FLAG ui_only_pages=prism,mocap 白名单：只显示这些功能页（空 = 不启用）
 *
 * // 机型 / 已安装包名（同样支持 * 通配；包名精确匹配，也支持 com.tencent.*）
 * FLAG ui_hide_models=K70*,2210*        命中机型 → 隐藏所有 UI 与功能页
 * FLAG ui_hide_pkgs=com.omarea.vtools   装了这些包 → 隐藏所有 UI 与功能页
 * FLAG ui_only_models=K70*,K60*         只在这些机型显示（白名单）
 * FLAG ui_only_pkgs=com.miui.home       只在这些包存在时显示（白名单）
 * FLAG ui_hide_story_models=K70*        只隐藏故事墙（任意 ui_show_* 同名可加 _models/_pkgs）
 * FLAG ui_hide_ai_pkgs=com.tencent.mm   装了微信就隐藏 AI 入口
 * FLAG ui_hide_pages_models=2210*       这些机型隐藏所有功能页
 * FLAG ui_hide_page_prism_pkgs=org.lsposed.manager   装了 LSPosed 就隐藏棱镜页
 * </pre>
 */
public final class UiFlags {

    private UiFlags() {
    }

    /** 控件名（ui_show_<surface> / ui_hide 列表用）。 */
    public static final String NAV = "nav";
    public static final String TOPBAR = "topbar";
    public static final String SEARCH = "search";
    public static final String AI = "ai";
    public static final String QUICK = "quick";
    public static final String CATS = "cats";
    public static final String INTRO = "intro";
    public static final String FOOTER = "footer";
    public static final String STORY = "story";
    public static final String VIDEO = "video";
    public static final String EMPTY = "empty";
    public static final String PAGEGLASS = "pageglass";

    /**
     * 控件是否显示。
     *
     * <p>判定顺序：机型/包名总闸 → ui_show_* → ui_hide 列表 → 该控件自己的机型/包名规则。
     * 命中机型或已安装包名时隐藏；{@code ui_only_models} / {@code ui_only_pkgs} 是白名单，
     * 配了之后不命中的机器整层隐藏。
     */
    public static boolean show(String surface) {
        String s = key(surface);
        if (s.isEmpty()) return true;
        if (globalHidden()) return false;
        if (!CloudFlags.bool("ui_show_" + s, true)) return false;
        if (names("ui_hide").contains(s)) return false;
        if (modelHit("ui_hide_" + s + "_models")) return false;
        if (pkgHit("ui_hide_" + s + "_pkgs")) return false;
        return true;
    }

    /** 说明文字总开关：ui_hide_desc=1 或机型/包名总闸命中时，全部副标题/统计/介绍都不显示。 */
    public static boolean descVisible() {
        return !globalHidden() && !CloudFlags.bool("ui_hide_desc", false);
    }

    /**
     * 说明文字：ui_hide_desc 优先；否则 ui_desc_&lt;key&gt; 有值就用它，没有就回退默认。
     * key 只保留小写字母/数字/下划线，避免中文键写不进 FLAG 语法。
     */
    public static String desc(String key, String def) {
        if (!descVisible()) return "";
        String k = key(key);
        if (k.isEmpty()) return def;
        String v = CloudFlags.str("ui_desc_" + k, "").trim();
        return v.isEmpty() ? def : v;
    }

    /**
     * 功能页是否被隐藏（ui_only_pages 白名单优先，其次 ui_hide_pages 黑名单）。
     * 同时认 pageKey（图标键/id）与中文标题 —— 运营写 {@code prism} 或 {@code 棱镜玩机} 都行。
     */
    public static boolean pageHidden(String pageKey) {
        if (pageKey == null) return false;
        String raw = pageKey.trim().toLowerCase(Locale.ROOT);
        String norm = key(pageKey);
        if (raw.isEmpty() && norm.isEmpty()) return false;
        if (globalHidden()) return true;
        List<String> only = rawNames("ui_only_pages");
        if (!only.isEmpty() && !only.contains(raw) && !only.contains(norm)) return true;
        List<String> hide = rawNames("ui_hide_pages");
        if (hide.contains(raw) || hide.contains(norm)) return true;
        // 功能页自己的机型/包名规则：ui_hide_page_<pageKey>_models / _pkgs
        if (!norm.isEmpty()) {
            if (modelHit("ui_hide_page_" + norm + "_models")) return true;
            if (pkgHit("ui_hide_page_" + norm + "_pkgs")) return true;
        }
        // 所有功能页共用：ui_hide_pages_models / ui_hide_pages_pkgs
        return modelHit("ui_hide_pages_models") || pkgHit("ui_hide_pages_pkgs");
    }

    /** 机型/包名总闸：ui_hide_models / ui_hide_pkgs 命中，或白名单不命中。 */
    private static boolean globalHidden() {
        if (modelHit("ui_hide_models")) return true;
        if (pkgHit("ui_hide_pkgs")) return true;
        String onlyModel = CloudFlags.str("ui_only_models", "").trim();
        if (!onlyModel.isEmpty() && !DeviceId.hitAny(onlyModel)) return true;
        String onlyPkg = CloudFlags.str("ui_only_pkgs", "").trim();
        if (!onlyPkg.isEmpty() && !PkgProbe.hasAny(onlyPkg)) return true;
        return false;
    }

    private static boolean modelHit(String flagKey) {
        return DeviceId.hitAny(CloudFlags.str(flagKey, ""));
    }

    private static boolean pkgHit(String flagKey) {
        return PkgProbe.hasAny(CloudFlags.str(flagKey, ""));
    }

    /** 列表原值（允许中文标题），只做 trim + 小写 + 去重。 */
    public static List<String> rawNames(String flagKey) {
        List<String> out = new ArrayList<String>();
        String raw = CloudFlags.str(flagKey, "");
        if (raw.isEmpty()) return out;
        Set<String> seen = new HashSet<String>();
        for (String p : raw.split("[,，;；\\s]+")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && seen.add(t)) out.add(t);
        }
        return out;
    }

    /** 逗号/分号/空白分隔的列表（统一小写；去空项）。 */
    public static List<String> names(String flagKey) {
        List<String> out = new ArrayList<String>();
        String raw = CloudFlags.str(flagKey, "");
        if (raw.isEmpty()) return out;
        Set<String> seen = new HashSet<String>();
        for (String p : raw.split("[,，;；\\s]+")) {
            String t = key(p);
            if (!t.isEmpty() && seen.add(t)) out.add(t);
        }
        return out;
    }

    /** 规范化：小写、只留 a-z0-9_，其余变下划线。 */
    public static String key(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                b.append(c);
            } else if (b.length() > 0 && b.charAt(b.length() - 1) != '_') {
                b.append('_');
            }
        }
        while (b.length() > 0 && b.charAt(b.length() - 1) == '_') b.setLength(b.length() - 1);
        return b.toString();
    }
}
