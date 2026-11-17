package com.mineways.astrbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * AstrBot 一键部署的运行环境参数：目录、端口、云控可覆盖的版本与下载地址。
 *
 * <p>运行目录必须落在 /data/local/tmp（应用私有目录是 noexec，proot / QQ 都跑不起来），
 * 由 Shizuku 建目录并 chmod 777，Java 侧才能往里写文件 —— 与 Blender 那套同一个约束。</p>
 */
public final class AstrBotEnv {

    /** AstrBot 首发基线版本；实际以云控 astrbot_cfg.ver 为准。 */
    public static final String DEFAULT_VERSION = AstrBotCloud.DEFAULT_VERSION;

    public static final String BRIDGE_NAPCAT = "napcat";
    public static final String BRIDGE_SNOWLUMA = "snowluma";

    private static final String PREF = "astrbot";

    private AstrBotEnv() {
    }

    // ---------------------------------------------------------------- 云控参数（统一走 AstrBotCloud）

    private static AstrBotCloud.Cfg cfg(Context c) {
        return AstrBotCloud.get(c);
    }

    /** 总开关：云的 enabled=0 时卡片不显示、页面禁用全部操作。 */
    public static boolean enabled(Context c) {
        return cfg(c).enabled;
    }

    public static int portWebui(Context c) {
        return cfg(c).portWebui;
    }

    public static int portWs(Context c) {
        return cfg(c).portWs;
    }

    public static int portNapcat(Context c) {
        return cfg(c).portNapcat;
    }

    public static int portSnowluma(Context c) {
        return cfg(c).portSnowluma;
    }

    public static int portNovnc(Context c) {
        return cfg(c).portNovnc;
    }

    /** 默认桥（首次安装时的选项）：napcat / snowluma。 */
    public static String defaultBridge(Context c) {
        return cfg(c).bridge;
    }

    /** 是否套用本机配置模板。 */
    public static boolean seedEnabled(Context c) {
        return cfg(c).seed;
    }

    /** 是否安装内置插件。 */
    public static boolean pluginsEnabled(Context c) {
        return cfg(c).plugins;
    }

    /** 扫码等待秒数。 */
    public static long scanWaitMs(Context c) {
        return Math.max(60, cfg(c).scanWait) * 1000L;
    }

    public static String version(Context c) {
        return cfg(c).ver;
    }

    /** ubuntu24（AstrBot 容器）rootfs 压缩包；空 = 运行时按 proot-distro 最新 release 解析。 */
    public static String ubuntuUrl(Context c) {
        return cfg(c).ubuntuUrl;
    }

    /** bookworm（QQ 桥容器）rootfs 压缩包；空 = 运行时解析。 */
    public static String bookwormUrl(Context c) {
        return cfg(c).bookwormUrl;
    }

    /** Linux QQ arm64 .deb 地址；空 = 优先桥项目自带，其次本机导入。 */
    public static String qqDebUrl(Context c) {
        return cfg(c).qqDebUrl;
    }

    public static String napcatUrl(Context c) {
        return cfg(c).napcatUrl;
    }

    public static String snowLumaUrl(Context c) {
        return cfg(c).snowLumaUrl;
    }

    /** Termux 软件源根地址（proot 二进制与 libtalloc 从这里的 Packages 索引解析）。 */
    public static String termuxMirror(Context c) {
        return cfg(c).termuxMirror;
    }

    /** apt 镜像（Debian/Ubuntu 容器内）；空 = 用官方源。 */
    public static String aptMirror(Context c) {
        return cfg(c).aptMirror;
    }

    /** 是否开放 SnowLuma 桥。 */
    public static boolean snowEnabled(Context c) {
        return cfg(c).snow;
    }

    /** 是否装 noVNC 备用桌面（默认关，体积大）。 */
    public static boolean novncEnabled(Context c) {
        return cfg(c).novnc;
    }

    /** 容器内 AstrBot 启动命令；空 = 自动（venv/bin/astrbot → python -m astrbot）。 */
    public static String entryCmd(Context c) {
        return cfg(c).entryCmd;
    }

    /** 容器内 QQ 可执行文件路径（不同 deb 版本可能不同）。 */
    public static String qqBin(Context c) {
        return cfg(c).qqBin;
    }

    /** 桥的完整启动命令覆盖（容器内 sh）；空 = 用内置模板。 */
    public static String napcatCmd(Context c) {
        return cfg(c).napcatCmd;
    }

    public static String snowCmd(Context c) {
        return cfg(c).snowCmd;
    }

    /** NapCat WebUI 固定 token；空 = 本地随机生成并落盘。 */
    public static String napcatToken(Context c) {
        return cfg(c).napcatToken;
    }

    // ---------------------------------------------------------------- 紧急运维（云控 ops）

    /** 维护公告文本：非空时在页面顶部展示。 */
    public static String maint(Context c) {
        return cfg(c).maint;
    }

    public static boolean noDeploy(Context c) {
        return cfg(c).noDeploy;
    }

    public static boolean noStart(Context c) {
        return cfg(c).noStart;
    }

    public static boolean healthEnabled(Context c) {
        return cfg(c).health;
    }

    public static int healthSec(Context c) {
        return cfg(c).healthSec;
    }

    /** 一次性动作 nonce：每次下发新值才会执行一次（防重复停/重置）。 */
    public static String stopNonce(Context c) {
        return cfg(c).stopNonce;
    }

    public static String resetNonce(Context c) {
        return cfg(c).resetNonce;
    }

    public static String restartNonce(Context c) {
        return cfg(c).restartNonce;
    }

    /** 部署前最少剩余空间（MB，云控 astrbot_cfg.min_free_mb）。 */
    public static int minFreeMb(Context c) {
        return cfg(c).minFreeMb;
    }

    /** 是否只允许 arm64（proot 二进制只有 aarch64 版）。 */
    public static boolean requireArm64(Context c) {
        return cfg(c).requireArm64;
    }

    // ---------------------------------------------------------------- 目录

    public static File root(Context c) {
        return new File("/data/local/tmp/mineways_astrbot");
    }

    public static File sub(Context c, String name) {
        return new File(root(c), name);
    }

    public static File ubuntu(Context c) {
        return sub(c, "ubuntu");
    }

    public static File bookworm(Context c) {
        return sub(c, "bookworm");
    }

    public static File prootDir(Context c) {
        return sub(c, "proot");
    }

    public static File dlDir(Context c) {
        return sub(c, "dl");
    }

    public static File logDir(Context c) {
        return sub(c, "logs");
    }

    public static File shareDir(Context c) {
        return sub(c, "share");
    }

    public static String prootBin(Context c) {
        return new File(prootDir(c), "usr/bin/proot").getAbsolutePath();
    }

    public static String prootLibs(Context c) {
        return prootDir(c).getAbsolutePath() + "/usr/lib";
    }

    public static File log(Context c, String name) {
        return new File(logDir(c), name);
    }

    public static File astrbotLog(Context c) {
        return log(c, "astrbot.log");
    }

    public static File napcatLog(Context c) {
        return log(c, "napcat.log");
    }

    public static File snowLog(Context c) {
        return log(c, "snowluma.log");
    }

    // ---------------------------------------------------------------- 状态

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean deployed(Context c) {
        return prefs(c).getBoolean("deployed", false);
    }

    public static void markDeployed(Context c, String version) {
        prefs(c).edit().putBoolean("deployed", true)
                .putString("installed_version", version).apply();
    }

    public static void markUndeployed(Context c) {
        prefs(c).edit().putBoolean("deployed", false)
                .remove("installed_version").apply();
    }

    public static String installedVersion(Context c) {
        return prefs(c).getString("installed_version", "");
    }

    public static String bridge(Context c) {
        return prefs(c).getString("bridge", defaultBridge(c));
    }

    public static void setBridge(Context c, String b) {
        prefs(c).edit().putString("bridge", BRIDGE_SNOWLUMA.equals(b) ? BRIDGE_SNOWLUMA : BRIDGE_NAPCAT).apply();
    }

    public static String qqDebPath(Context c) {
        return prefs(c).getString("qq_deb", "");
    }

    public static void setQqDebPath(Context c, String path) {
        prefs(c).edit().putString("qq_deb", path == null ? "" : path).apply();
    }

    public static String botUin(Context c) {
        return prefs(c).getString("bot_uin", "");
    }

    public static void setBotUin(Context c, String uin) {
        prefs(c).edit().putString("bot_uin", uin == null ? "" : uin).apply();
    }

    /** 部署向导给 LLM Key 用的简单持久化（真正的 provider 由 AstrBot 配置 API 写入）。 */
    public static String llmProvider(Context c) {
        return prefs(c).getString("llm_provider", "openai");
    }

    public static String llmBase(Context c) {
        return prefs(c).getString("llm_base", "");
    }

    public static String llmKey(Context c) {
        return prefs(c).getString("llm_key", "");
    }

    public static String llmModel(Context c) {
        return prefs(c).getString("llm_model", "");
    }

    public static void setLlm(Context c, String provider, String base, String key, String model) {
        prefs(c).edit().putString("llm_provider", provider)
                .putString("llm_base", base == null ? "" : base.trim())
                .putString("llm_key", key == null ? "" : key.trim())
                .putString("llm_model", model == null ? "" : model.trim()).apply();
    }

    /** SnowLuma 首启日志里的初始账号（webui.json 存 hash，不能伪造，只能抓+改）。 */
    public static String snowUser(Context c) {
        return prefs(c).getString("snow_user", "");
    }

    public static String snowPass(Context c) {
        return prefs(c).getString("snow_pass", "");
    }

    public static void setSnowCred(Context c, String user, String pass) {
        prefs(c).edit().putString("snow_user", user == null ? "" : user)
                .putString("snow_pass", pass == null ? "" : pass).apply();
    }

    /** AstrBot 面板账号：首启日志里抓到的初始凭据，WebView 登录页自动填。 */
    public static String astrbotUser(Context c) {
        return prefs(c).getString("astrbot_user", "astrbot");
    }

    public static String astrbotPass(Context c) {
        return prefs(c).getString("astrbot_pass", "");
    }

    public static void setAstrbotCred(Context c, String user, String pass) {
        prefs(c).edit().putString("astrbot_user", user == null || user.isEmpty() ? "astrbot" : user)
                .putString("astrbot_pass", pass == null ? "" : pass).apply();
    }
}
