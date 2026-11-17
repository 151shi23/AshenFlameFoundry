package com.mineways.astrbot;

import android.content.Context;

import org.json.JSONObject;

/**
 * AstrBot 的<b>唯一云控入口</b>：所有可调项集中在一个 {@code astrbot_cfg} JSON 里，
 * 模块其它代码不再各自去读零散的 flag。
 *
 * <p>兼容：JSON 里缺的字段回退到旧的单项键（astrbot_ver / astrbot_port_* / …），
 * 老云控配置不改也能继续用；新配置只写一个键即可整包覆盖。</p>
 *
 * <pre>
 * FLAG key=astrbot_cfg to={"enabled":1,"ver":"4.29.3","bridge":"napcat","snow":1,"novnc":0,
 *   "seed":1,"plugins":1,"scan_wait":900,
 *   "ports":{"webui":6185,"ws":6199,"napcat":6099,"snowluma":5099,"novnc":6081},
 *   "urls":{"ubuntu":"","bookworm":"","qq_deb":"","napcat":"","snowluma":"",
 *           "termux_mirror":"https://packages.termux.dev/apt/termux-main","apt_mirror":""},
 *   "cmds":{"entry":"","qq_bin":"/opt/QQ/qq","napcat":"","snow":""},
 *   "napcat_token":""}
 * </pre>
 */
public final class AstrBotCloud {

    public static final String DEFAULT_VERSION = "4.29.3";
    public static final String DEFAULT_TERMUX = "https://packages.termux.dev/apt/termux-main";

    public static final class Cfg {
        public boolean enabled = true;
        public String ver = DEFAULT_VERSION;
        public String bridge = AstrBotEnv.BRIDGE_NAPCAT;
        public boolean snow = true;
        public boolean novnc = false;
        public boolean seed = true;
        public boolean plugins = true;
        public int scanWait = 900;
        public int portWebui = 6185;
        public int portWs = 6199;
        public int portNapcat = 6099;
        public int portSnowluma = 5099;
        public int portNovnc = 6081;
        public String ubuntuUrl = "";
        public String bookwormUrl = "";
        public String qqDebUrl = "";
        public String napcatUrl = "";
        public String snowLumaUrl = "";
        public String termuxMirror = DEFAULT_TERMUX;
        public String aptMirror = "";
        public String entryCmd = "";
        public String qqBin = "/opt/QQ/qq";
        public String napcatCmd = "";
        public String snowCmd = "";
        public String napcatToken = "";
        // 紧急运维 / 调控
        public String maint = "";
        public boolean noDeploy = false;
        public boolean noStart = false;
        public boolean health = true;
        public int healthSec = 60;
        public String stopNonce = "";
        public String resetNonce = "";
        public String restartNonce = "";
        // 部署前环境门槛（云控可调）
        public int minFreeMb = 3072;
        public boolean requireArm64 = true;
    }

    private static volatile Cfg cached;
    private static volatile long cachedAt;

    private AstrBotCloud() {
    }

    public static Cfg get(Context c) {
        final long now = System.currentTimeMillis();
        Cfg cfg = cached;
        if (cfg != null && now - cachedAt < 3000) return cfg;
        cfg = load(c);
        cached = cfg;
        cachedAt = now;
        return cfg;
    }

    private static Cfg load(Context c) {
        final Cfg d = new Cfg();
        JSONObject j = null;
        try {
            final String raw = legacy("astrbot_cfg", "");
            if (!raw.isEmpty()) j = new JSONObject(raw);
        } catch (Throwable ignored) {
        }

        d.enabled = bool(j, "enabled", "astrbot_on", true);
        d.ver = str(j, "ver", "astrbot_ver", DEFAULT_VERSION);
        String bridge = str(j, "bridge", "astrbot_bridge", AstrBotEnv.BRIDGE_NAPCAT);
        d.bridge = AstrBotEnv.BRIDGE_SNOWLUMA.equals(bridge)
                ? AstrBotEnv.BRIDGE_SNOWLUMA : AstrBotEnv.BRIDGE_NAPCAT;
        d.snow = bool(j, "snow", "astrbot_snow", true);
        d.novnc = bool(j, "novnc", "astrbot_novnc", false);
        d.seed = bool(j, "seed", "astrbot_seed", true);
        d.plugins = bool(j, "plugins", "astrbot_plugins", true);
        d.scanWait = Math.max(60, num(j, "scan_wait", "astrbot_scan_wait", 900));

        JSONObject ports = obj(j, "ports");
        d.portWebui = port(num(ports, "webui", "astrbot_port_webui", 6185), 6185);
        d.portWs = port(num(ports, "ws", "astrbot_port_ws", 6199), 6199);
        d.portNapcat = port(num(ports, "napcat", "astrbot_port_napcat", 6099), 6099);
        d.portSnowluma = port(num(ports, "snowluma", "astrbot_port_snowluma", 5099), 5099);
        d.portNovnc = port(num(ports, "novnc", "astrbot_port_novnc", 6081), 6081);

        JSONObject urls = obj(j, "urls");
        d.ubuntuUrl = str(urls, "ubuntu", "astrbot_ubuntu_url", "");
        d.bookwormUrl = str(urls, "bookworm", "astrbot_bookworm_url", "");
        d.qqDebUrl = str(urls, "qq_deb", "astrbot_qq_deb_url", "");
        d.napcatUrl = str(urls, "napcat", "astrbot_napcat_url", "");
        d.snowLumaUrl = str(urls, "snowluma", "astrbot_snowluma_url", "");
        d.termuxMirror = str(urls, "termux_mirror", "astrbot_termux_mirror", DEFAULT_TERMUX);
        d.aptMirror = str(urls, "apt_mirror", "astrbot_apt_mirror", "");

        JSONObject cmds = obj(j, "cmds");
        d.entryCmd = str(cmds, "entry", "astrbot_entry_cmd", "");
        d.qqBin = str(cmds, "qq_bin", "astrbot_qq_bin", "/opt/QQ/qq");
        d.napcatCmd = str(cmds, "napcat", "astrbot_napcat_cmd", "");
        d.snowCmd = str(cmds, "snow", "astrbot_snow_cmd", "");
        d.napcatToken = str(j, "napcat_token", "astrbot_napcat_token", "");

        JSONObject ops = obj(j, "ops");
        d.maint = str(ops, "maint", "astrbot_maint", "");
        d.noDeploy = bool(ops, "no_deploy", "astrbot_no_deploy", false);
        d.noStart = bool(ops, "no_start", "astrbot_no_start", false);
        d.health = bool(ops, "health", "astrbot_health", true);
        d.healthSec = Math.max(30, num(ops, "health_sec", "astrbot_health_sec", 60));
        d.stopNonce = str(ops, "stop", "astrbot_stop", "");
        d.resetNonce = str(ops, "reset", "astrbot_reset", "");
        d.restartNonce = str(ops, "restart", "astrbot_restart", "");
        d.minFreeMb = Math.max(512, num(j, "min_free_mb", "astrbot_min_free_mb", 3072));
        d.requireArm64 = bool(j, "require_arm64", "astrbot_require_arm64", true);
        return d;
    }

    // ---------------------------------------------------------------- 取值工具

    private static String legacy(String key, String def) {
        return com.mineways.cloud.CloudFlags.str(key, def);
    }

    /** 端口越界（0 / >65535）直接回退默认，避免脚本生成非法参数。 */
    private static int port(int v, int def) {
        return v >= 1024 && v <= 65535 ? v : def;
    }

    private static JSONObject obj(JSONObject j, String name) {
        return j == null ? null : j.optJSONObject(name);
    }

    private static boolean bool(JSONObject j, String key, String legacyKey, boolean def) {
        if (j != null && j.has(key)) {
            final Object o = j.opt(key);
            if (o instanceof Boolean) return (Boolean) o;
            if (o instanceof Number) return ((Number) o).intValue() != 0;
            final String s = String.valueOf(o).trim();
            if ("0".equals(s) || "false".equalsIgnoreCase(s)) return false;
            if ("1".equals(s) || "true".equalsIgnoreCase(s)) return true;
            return def;
        }
        final String s = legacy(legacyKey, def ? "1" : "0");
        return !("0".equals(s) || "false".equalsIgnoreCase(s));
    }

    private static int num(JSONObject j, String key, String legacyKey, int def) {
        if (j != null && j.has(key)) {
            final Object o = j.opt(key);
            if (o instanceof Number) return ((Number) o).intValue();
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (Throwable t) {
                return def;
            }
        }
        try {
            return Integer.parseInt(legacy(legacyKey, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }

    private static String str(JSONObject j, String key, String legacyKey, String def) {
        if (j != null && j.has(key)) {
            final String v = j.optString(key, def);
            if (v == null || v.trim().isEmpty() || "null".equals(v.trim())) return def;
            return v.trim();
        }
        return legacy(legacyKey, def);
    }
}
