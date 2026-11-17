package com.mineways.share;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 本机设置的自动发现目录。
 *
 * <p>不维护"工具 → 键"的手工表：直接枚举应用自己的 {@code shared_prefs} 目录，
 * 因此任何工具（含以后新增的）留下的设置都自动出现在分享列表里，工具代码无需配合。
 * 只做三件事：枚举、按规则排除、按 pref 名给一个人类可读的分组标题。
 */
public final class PrefCatalog {

    /** 分组：一个 pref 文件 = 一组。 */
    public static final class Group {
        public final String prefs;
        public final String title;
        public final TreeMap<String, Object[]> keys = new TreeMap<>();
        public int secretCount;

        Group(String prefs, String title) {
            this.prefs = prefs;
            this.title = title;
        }

        public int count() {
            return keys.size();
        }
    }

    /** 永远不参与分享的 pref（账号 / 云控 / 安全 / 本功能自身）。 */
    private static final Set<String> HARD = new HashSet<>(Arrays.asList(
            "share_box", "share_presets",
            "aff_activation", "aff_oauth", "aff_story", "aff_notice",
            "pro_auth", "pro_login",
            "mw_social",
            "cloud_catalog", "cloud_ask", "cloud_gate", "update_gate_cache",
            "guardlink", "guardlink_params", "guardlink_state",
            "guardlink_switch", "guardlink_a11y",
            "mineways_adapt",
            "astrbot",
            "mim_prefs"
    ));

    /** 键前缀排除：运行时状态 / 原始值备份 / GPU 探测。 */
    private static final String[] BAD_PREFIX = {"original_", "gpu_", "webview", "cached_", "tmp_"};

    /**
     * 按键排除：这类值只在本机成立，分享出去反而会让对方导出失败。
     * 例如材质包是本机路径、模组是一串 content:// URI、首次引导标记属于运行状态。
     */
    private static final Map<String, Set<String>> SKIP_KEYS = new LinkedHashMap<>();

    static {
        SKIP_KEYS.put("export_options", new HashSet<>(Arrays.asList(
                "matpack_path", "matpack_name", "matpack_on",
                "mod_uris", "blocksize_fixed_v1", "first_run_intro_shown")));
    }

    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(key|token|secret|passwd|password|pwd|cookie|session|uin|email|phone|credential).*");

    /** pref → 分组标题（仅影响展示；映射不到也不影响分享）。 */
    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("export_options", "存档导出（OBJ / 材质 / 比例）/ AI 助手 / 图片转模型");
        TITLES.put("phrase_box", "串词工具箱");
        TITLES.put("mw_music", "音乐播放器");
        TITLES.put("mocap_ui", "动作捕捉");
        TITLES.put("prism_toolbox", "棱镜玩机");
        TITLES.put("prism_notifications", "棱镜玩机（通知）");
        TITLES.put("blender_env", "Blender 渲染");
        TITLES.put("web_i18n", "网页工具（站点偏好）");
        TITLES.put("opencut_pack", "视频剪辑");
        TITLES.put("glass_policy", "界面外观");
    }

    private PrefCatalog() {
    }

    public static List<Group> list(Context c) {
        List<Group> out = new ArrayList<>();
        File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".xml")) continue;
            String name = n.substring(0, n.length() - 4);
            if (HARD.contains(name)) continue;
            SharedPreferences sp;
            try {
                sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
            } catch (Exception e) {
                continue;
            }
            Map<String, ?> all;
            try {
                all = sp.getAll();
            } catch (Exception e) {
                continue;
            }
            if (all == null || all.isEmpty()) continue;
            Group g = new Group(name, TITLES.containsKey(name) ? TITLES.get(name) : name);
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isEmpty()) continue;
                if (badKey(key)) continue;
                Object[] v = ShareCodec.of(e.getValue());
                if (v == null) continue;
                if (isSecret(key) || skipped(name, key)) {
                    g.secretCount++;
                    continue;
                }
                g.keys.put(key, v);
            }
            if (!g.keys.isEmpty()) out.add(g);
        }
        Collections.sort(out, (a, b) -> a.title.compareTo(b.title));
        return out;
    }

    /** 取指定 pref 的当前值（导入预览 / 备份用）。 */
    public static TreeMap<String, Object[]> read(Context c, String prefs, Set<String> onlyKeys) {
        TreeMap<String, Object[]> out = new TreeMap<>();
        SharedPreferences sp = c.getSharedPreferences(prefs, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (onlyKeys != null && !onlyKeys.contains(e.getKey())) continue;
            Object[] v = ShareCodec.of(e.getValue());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    public static boolean excluded(String prefs, String key) {
        if (prefs == null || key == null) return true;
        if (HARD.contains(prefs)) return true;
        return badKey(key) || isSecret(key) || skipped(prefs, key);
    }

    private static boolean skipped(String prefs, String key) {
        Set<String> s = SKIP_KEYS.get(prefs);
        return s != null && s.contains(key);
    }

    public static boolean isSecret(String key) {
        return key != null && SECRET.matcher(key).matches();
    }

    private static boolean badKey(String key) {
        for (String p : BAD_PREFIX) if (key.startsWith(p)) return true;
        return false;
    }
}
