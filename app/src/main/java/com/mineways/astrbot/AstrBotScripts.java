package com.mineways.astrbot;

import android.content.Context;

import com.mineways.blender.RootShell;

import java.io.File;
import java.util.UUID;

/**
 * 三端配置模板与启动脚本（内容与实测文档 AstrBot全接入文档 一一对应）。
 *
 * <p>字段名差异是实坑，别互相抄：NapCat 是 enable / messagePostFormat / token；
 * SnowLuma 是 enabled / messageFormat / accessToken。所有 WS 都必须 array 段格式。</p>
 */
public final class AstrBotScripts {

    private AstrBotScripts() {
    }

    // ---------------------------------------------------------------- AstrBot 配置

    /** 平台适配器条目（aiocqhttp 反向 WS 服务端）。 */
    public static String platformEntry(Context c) {
        return "{"
                + "\"id\":\"qq-bridge\","
                + "\"type\":\"aiocqhttp\","
                + "\"enable\":true,"
                + "\"ws_reverse_host\":\"127.0.0.1\","
                + "\"ws_reverse_port\":" + AstrBotEnv.portWs(c) + ","
                + "\"ws_reverse_token\":\"\""
                + "}";
    }

    // ---------------------------------------------------------------- NapCat

    public static String napcatOneBot(Context c) {
        return "{\n"
                + "  \"network\": {\n"
                + "    \"websocketClients\": [\n"
                + "      {\n"
                + "        \"name\": \"astrbot\",\n"
                + "        \"enable\": true,\n"
                + "        \"url\": \"ws://127.0.0.1:" + AstrBotEnv.portWs(c) + "/ws\",\n"
                + "        \"messagePostFormat\": \"array\",\n"
                + "        \"reportSelfMessage\": false,\n"
                + "        \"token\": \"\",\n"
                + "        \"debug\": false,\n"
                + "        \"heartInterval\": 30000,\n"
                + "        \"reconnectInterval\": 5000\n"
                + "      }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"musicSignUrl\": \"\",\n"
                + "  \"enableLocalFile2Url\": false,\n"
                + "  \"parseMultMsg\": true\n"
                + "}\n";
    }

    public static String napcatWebui(String token) {
        return "{\n  \"token\": \"" + token + "\",\n  \"loginRate\": 3\n}\n";
    }

    // ---------------------------------------------------------------- SnowLuma

    public static String snowOneBot(Context c) {
        return "{\n"
                + "  \"networks\": {\n"
                + "    \"wsClients\": [\n"
                + "      {\n"
                + "        \"name\": \"astrbot\",\n"
                + "        \"enabled\": true,\n"
                + "        \"url\": \"ws://127.0.0.1:" + AstrBotEnv.portWs(c) + "/ws\",\n"
                + "        \"role\": \"Universal\",\n"
                + "        \"accessToken\": \"\",\n"
                + "        \"messageFormat\": \"array\",\n"
                + "        \"reconnectIntervalMs\": 3000\n"
                + "      }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"musicSignUrl\": \"\"\n"
                + "}\n";
    }

    public static String snowRuntime(Context c) {
        return "{\n"
                + "  \"webuiPort\": " + AstrBotEnv.portSnowluma(c) + ",\n"
                + "  \"hookAutoLoad\": true,\n"
                + "  \"webuiHost\": \"127.0.0.1\"\n"
                + "}\n";
    }

    public static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------------------------------------------------------- proot 基础

    /** proot 一条命令的前缀：Termux 的 proot 自带库都在 proot/lib 里。 */
    public static String proot(Context c, String rootfs) {
        return "LD_LIBRARY_PATH=" + q(AstrBotEnv.prootLibs(c)) + " PROOT_TMP_DIR=" + q(AstrBotEnv.dlDir(c).getAbsolutePath())
                + " " + q(AstrBotEnv.prootBin(c))
                + " --link2symlink -0 -r " + q(rootfs)
                + " -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random"
                + " -b " + q(AstrBotEnv.shareDir(c).getAbsolutePath()) + ":/mnt/share"
                + " -w /root";
    }

    /** 容器内执行一段 sh 脚本（带基本的 PATH / HOME）。 */
    public static String inRootfs(Context c, String rootfs, String script) {
        return proot(c, rootfs) + " /usr/bin/env -i HOME=/root TERM=xterm-256color"
                + " PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                + " LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive"
                + " /bin/sh -c " + q(script);
    }

    // ---------------------------------------------------------------- 启动 / 停止

    /**
     * 首启要带 ASTRBOT_RESET_DASHBOARD_PASSWORD=1，让日志打印初始面板密码 —— 抓到后 WebView 自动填。
     */
    private static String astrbotLaunch(Context c, boolean resetPassword) {
        final String custom = AstrBotEnv.entryCmd(c);
        final String entry = custom.isEmpty()
                ? "if [ -x /opt/astrbot/venv/bin/astrbot ]; then exec /opt/astrbot/venv/bin/astrbot; "
                + "else exec /opt/astrbot/venv/bin/python -m astrbot; fi"
                : "exec " + custom;
        return "cd /opt/astrbot/home && "
                + (resetPassword ? "export ASTRBOT_RESET_DASHBOARD_PASSWORD=1; " : "")
                + entry;
    }

    /** AstrBot 启动：先等 6199 监听，桥后面再起（文档的顺序）。 */
    public static String startAstrBot(Context c) {
        final String log = AstrBotEnv.astrbotLog(c).getAbsolutePath();
        final String pid = new File(AstrBotEnv.dlDir(c), "astrbot.pid").getAbsolutePath();
        return "mkdir -p " + q(AstrBotEnv.logDir(c).getAbsolutePath()) + " " + q(AstrBotEnv.shareDir(c).getAbsolutePath()) + "; "
                + "nohup " + inRootfs(c, AstrBotEnv.ubuntu(c).getAbsolutePath(), astrbotLaunch(c, false))
                + " > " + q(log) + " 2>&1 & echo $! > " + q(pid) + "; cat " + q(pid);
    }

    /** AstrBot 首启（生成 data/cmd_config.json 用），与正式启动同一条命令。 */
    public static String startAstrBotFirstRun(Context c) {
        final String log = AstrBotEnv.astrbotLog(c).getAbsolutePath();
        final String pid = new File(AstrBotEnv.dlDir(c), "astrbot.pid").getAbsolutePath();
        return "mkdir -p " + q(AstrBotEnv.logDir(c).getAbsolutePath()) + " " + q(AstrBotEnv.shareDir(c).getAbsolutePath()) + "; "
                + "nohup " + inRootfs(c, AstrBotEnv.ubuntu(c).getAbsolutePath(), astrbotLaunch(c, true))
                + " > " + q(log) + " 2>&1 & echo $! > " + q(pid) + "; cat " + q(pid);
    }

    /**
     * NapCat 桥：Xvfb 起显示 :95 → proot 里跑 QQ（入口 JS 已替换）→ NapCat 连 6199。
     * QQ 与 NapCat 都是 loopback 内部通信，不开公网端口。
     */
    public static String startNapCat(Context c, String entry) {
        final String root = AstrBotEnv.bookworm(c).getAbsolutePath();
        final String log = AstrBotEnv.napcatLog(c).getAbsolutePath();
        final String pid = new File(AstrBotEnv.dlDir(c), "napcat.pid").getAbsolutePath();
        final String custom = AstrBotEnv.napcatCmd(c);
        final String qq = AstrBotEnv.qqBin(c);
        final String inner = custom.isEmpty()
                ? "export DISPLAY=:95; "
                + "cd /opt/QQ && (setsid " + qq + " --no-sandbox >/var/log/qq.log 2>&1 &) ; sleep 1; "
                + "cd " + (entry == null || entry.isEmpty() ? "/opt/napcat" : entry) + "; "
                + "exec " + napcatEntryCommand(entry)
                : custom;
        return "mkdir -p " + q(AstrBotEnv.logDir(c).getAbsolutePath()) + "; "
                + "(command -v Xvfb >/dev/null 2>&1 && (Xvfb :95 -screen 0 1280x720x24 >/dev/null 2>&1 &)) ; "
                + "nohup " + inRootfs(c, root, inner) + " > " + q(log) + " 2>&1 & echo $! > " + q(pid) + "; cat " + q(pid);
    }

    /** NapCat.Shell 的入口按包内实际文件名二选一。 */
    public static String napcatEntryCommand(String dir) {
        String d = (dir == null || dir.isEmpty()) ? "/opt/napcat" : dir;
        return "if [ -f " + d + "/napcat.mjs ]; then exec node " + d + "/napcat.mjs; "
                + "elif [ -f " + d + "/napcat.js ]; then exec node " + d + "/napcat.js; "
                + "elif [ -f " + d + "/index.js ]; then exec node " + d + "/index.js; "
                + "else echo 'NAP CAT ENTRY NOT FOUND in " + d + "'; fi";
    }

    public static String startSnowLuma(Context c) {
        final String root = AstrBotEnv.bookworm(c).getAbsolutePath();
        final String log = AstrBotEnv.snowLog(c).getAbsolutePath();
        final String pid = new File(AstrBotEnv.dlDir(c), "snowluma.pid").getAbsolutePath();
        final String custom = AstrBotEnv.snowCmd(c);
        final String qq = AstrBotEnv.qqBin(c);
        final String inner = custom.isEmpty()
                ? "export DISPLAY=:95; "
                + "cd /opt/QQ && (setsid " + qq + " --no-sandbox >/var/log/qq.log 2>&1 &) ; sleep 1; "
                + "cd /opt/snowluma && exec node ./index.mjs"
                : custom;
        return "mkdir -p " + q(AstrBotEnv.logDir(c).getAbsolutePath()) + "; "
                + "(command -v Xvfb >/dev/null 2>&1 && (Xvfb :95 -screen 0 1280x720x24 >/dev/null 2>&1 &)) ; "
                + "nohup " + inRootfs(c, root, inner) + " > " + q(log) + " 2>&1 & echo $! > " + q(pid) + "; cat " + q(pid);
    }

    /** noVNC 备选桌面：Xvfb:95 → x11vnc:5900 → websockify:6081（浏览器远程桌面）。 */
    public static String startNoVnc(Context c) {
        final String root = AstrBotEnv.bookworm(c).getAbsolutePath();
        final String pid = new File(AstrBotEnv.dlDir(c), "novnc.pid").getAbsolutePath();
        final String inner = "export DISPLAY=:95; "
                + "x11vnc -display :95 -forever -shared -rfbport 5900 -nopw >/var/log/x11vnc.log 2>&1 & "
                + "exec websockify --web=/usr/share/novnc " + AstrBotEnv.portNovnc(c) + " localhost:5900";
        return "nohup " + inRootfs(c, root, inner) + " >/dev/null 2>&1 & echo $! > " + q(pid) + "; cat " + q(pid);
    }

    /** 全停：桥先停、AstrBot 后停（文档规定，避免桥反复重连）。 */
    public static String stopAll(Context c) {
        final String pidDir = AstrBotEnv.dlDir(c).getAbsolutePath();
        return "for f in napcat snowluma astrbot; do "
                + "p=" + q(pidDir) + "/$f.pid; "
                + "if [ -f \"$p\" ]; then kill $(cat \"$p\") 2>/dev/null; rm -f \"$p\"; fi; done; "
                + "pkill -f 'proot.*mineways_astrbot' 2>/dev/null; "
                + "pkill -f 'Xvfb :95' 2>/dev/null; true";
    }

    public static String stopAstrBot(Context c) {
        final String p = new File(AstrBotEnv.dlDir(c), "astrbot.pid").getAbsolutePath();
        return "if [ -f " + q(p) + " ]; then kill $(cat " + q(p) + ") 2>/dev/null; rm -f " + q(p) + "; fi; true";
    }

    public static String stopBridge(Context c) {
        final String d = AstrBotEnv.dlDir(c).getAbsolutePath();
        return "for f in napcat snowluma; do p=" + q(d) + "/$f.pid; "
                + "if [ -f \"$p\" ]; then kill $(cat \"$p\") 2>/dev/null; rm -f \"$p\"; fi; done; "
                + "pkill -f 'proot.*bookworm' 2>/dev/null; true";
    }

    // ---------------------------------------------------------------- 安装脚本（容器内）

    /** ubuntu24 容器：python + venv + AstrBot（PC 最新版，PyPI 同源）。 */
    public static String installAstrBot(Context c) {
        final String ver = AstrBotEnv.version(c);
        return "set -e\n"
                + "apt-get update -y\n"
                + "apt-get install -y --no-install-recommends python3 python3-venv python3-pip ca-certificates curl git tzdata\n"
                + "mkdir -p /opt/astrbot/home\n"
                + "python3 -m venv /opt/astrbot/venv\n"
                + "/opt/astrbot/venv/bin/pip install -U pip setuptools wheel\n"
                + "/opt/astrbot/venv/bin/pip install AstrBot==" + ver + "\n"
                + "echo ASTRBOT_INSTALL_OK " + ver + "\n";
    }

    /** bookworm 容器：Xvfb + Node（NapCat / SnowLuma 都靠它）+ QQ 运行依赖。 */
    public static String installBridgeDeps(Context c) {
        return "set -e\n"
                + "apt-get update -y\n"
                + "apt-get install -y --no-install-recommends xvfb x11-utils libnss3 libasound2 "
                + "libgbm1 libxss1 libgtk-3-0 libnotify4 libxtst6 libxdamage1 libxrandr2 libatk-bridge2.0-0 "
                + "libcups2 libdrm2 libxcomposite1 libxkbcommon0 libpango-1.0-0 libcairo2 ca-certificates curl unzip "
                + "nodejs npm\n"
                + "node -v && npm -v\n"
                + "echo BRIDGE_DEPS_OK\n";
    }

    /** SnowLuma 的依赖安装（release 若已带 node_modules 会自然跳过大部分）。 */
    public static String installSnowDeps(Context c) {
        return "set -e\n"
                + "cd /opt/snowluma && if [ -f package.json ]; then npm install --omit=dev --no-audit --no-fund; fi\n"
                + "echo SNOW_DEPS_OK\n";
    }

    /** noVNC 备选通道（6081），只在云控开启时装。 */
    public static String installNoVnc(Context c) {
        return "set -e\n"
                + "apt-get install -y --no-install-recommends x11vnc novnc websockify\n"
                + "echo NOVNC_OK\n";
    }

    /** 把容器里的 AstrBot 数据目录链接到共享目录，升级/重装不丢配置。 */
    public static String wireShare(Context c) {
        return "mkdir -p /opt/astrbot/home && ln -sfn /mnt/share /opt/astrbot/home/shared; true";
    }

    static String q(String s) {
        return RootShell.q(s);
    }
}
