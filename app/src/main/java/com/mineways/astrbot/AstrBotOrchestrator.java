package com.mineways.astrbot;

import android.content.Context;

import com.mineways.blender.RootShell;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 启动 / 停止 / 健康检查 / 管理员回填。顺序按文档：AstrBot 先起，桥后起；停止反过来。 */
public final class AstrBotOrchestrator {

    public enum State {
        NOT_DEPLOYED, STOPPED, STARTING, RUNNING, BRIDGE_DOWN, ERROR
    }

    public static final class Status {
        public State state = State.NOT_DEPLOYED;
        public boolean astrbotUp;
        public boolean wsUp;
        public boolean bridgeUp;
        public boolean bridgeConnected;
        public String note = "";
        public String botUin = "";
    }

    public interface Callback {
        void onStatus(String msg);
    }

    private AstrBotOrchestrator() {
    }

    // ---------------------------------------------------------------- 状态

    public static Status status(Context app) {
        final Status s = new Status();
        s.botUin = AstrBotEnv.botUin(app);
        if (!AstrBotEnv.deployed(app)) {
            s.state = State.NOT_DEPLOYED;
            s.note = "尚未部署";
            return s;
        }
        s.wsUp = portOpen(AstrBotEnv.portWs(app));
        s.astrbotUp = httpOk("http://127.0.0.1:" + AstrBotEnv.portWebui(app) + "/");
        final boolean napcat = AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(app));
        s.bridgeUp = portOpen(napcat ? AstrBotEnv.portNapcat(app) : AstrBotEnv.portSnowluma(app));
        if (s.astrbotUp && s.wsUp && s.bridgeUp) {
            s.bridgeConnected = Logs.bridgeLooksConnected(app);
            s.state = s.bridgeConnected ? State.RUNNING : State.BRIDGE_DOWN;
            s.note = s.bridgeConnected ? "运行中" : "桥已起，未连上 AstrBot";
        } else if (s.astrbotUp || s.wsUp || s.bridgeUp) {
            s.state = State.BRIDGE_DOWN;
            s.note = "部分组件在跑（AstrBot " + (s.astrbotUp ? "✓" : "✗")
                    + " /WS " + (s.wsUp ? "✓" : "✗")
                    + " /桥 " + (s.bridgeUp ? "✓" : "✗") + "）";
        } else {
            s.state = State.STOPPED;
            s.note = "已停止";
        }
        return s;
    }

    // ---------------------------------------------------------------- 启停

    /** 阻塞式启动；调用方放后台线程。返回最终状态。 */
    public static Status start(Context app, Callback cb) {
        final Status s = new Status();
        if (!AstrBotEnv.enabled(app)) {
            s.state = State.STOPPED;
            s.note = "云控未开放（astrbot_cfg.enabled=0）";
            return s;
        }
        if (AstrBotEnv.noStart(app)) {
            s.state = State.STOPPED;
            s.note = "云控已临时禁止启动（维护中）";
            return s;
        }
        if (!AstrBotEnv.deployed(app)) {
            s.state = State.NOT_DEPLOYED;
            s.note = "请先部署";
            return s;
        }
        if (!RootShell.isReady()) {
            s.state = State.ERROR;
            s.note = "Shizuku 未授权";
            return s;
        }
        try {
            say(cb, "清理旧进程…");
            RootShell.sh(AstrBotScripts.stopAll(app));
            sleep(800);

            say(cb, "启动 AstrBot（反向 WS " + AstrBotEnv.portWs(app)
                    + " / WebUI " + AstrBotEnv.portWebui(app) + "）…");
            RootShell.sh(AstrBotScripts.startAstrBot(app));
            if (!waitPort(AstrBotEnv.portWs(app), 180_000L, cb, "反向 WS")) {
                return fail(app, s, "AstrBot 180 秒内没有监听 " + AstrBotEnv.portWs(app) + " 端口。");
            }
            waitHttp("http://127.0.0.1:" + AstrBotEnv.portWebui(app) + "/", 90_000L, cb, "WebUI");

            // 每次启动前把三端配置重写一遍（幂等）：NapCat token / SnowLuma 反连 / 已知账号的 onebot11
            try {
                AstrBotInstaller.writeConfigs(app);
            } catch (Throwable ignored) {
            }
            final boolean napcat = AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(app));
            if (!AstrBotInstaller.isQqInstalled(app)) {
                s.state = State.BRIDGE_DOWN;
                s.note = "AstrBot 已起；还没装 Linux QQ（回页面点「安装 QQ」导入 arm64 安装包）。";
                return s;
            }
            if (napcat) {
                say(cb, "启动 NapCat 桥（Xvfb + Linux QQ）…");
                RootShell.sh(AstrBotScripts.startNapCat(app, napcatEntry(app)));
                if (!waitPort(AstrBotEnv.portNapcat(app), 240_000L, cb, "NapCat WebUI")) {
                    s.state = State.BRIDGE_DOWN;
                    s.note = "NapCat 没起来；首次启动要先扫码登录 QQ。日志里看原因。";
                    return s;
                }
            } else {
                say(cb, "启动 SnowLuma 桥…");
                RootShell.sh(AstrBotScripts.startSnowLuma(app));
                waitPort(AstrBotEnv.portSnowluma(app), 180_000L, cb, "SnowLuma WebUI");
                Logs.captureSnowCred(app);
            }
            if (AstrBotEnv.novncEnabled(app)) {
                say(cb, "启动 noVNC 备用桌面…");
                RootShell.sh(AstrBotScripts.startNoVnc(app));
            }

            say(cb, "等待扫码登录并连上 AstrBot（最多 " + (AstrBotEnv.scanWaitMs(app) / 60000)
                    + " 分钟；扫完会自动配置并重启桥）…");
            boolean restarted = false;
            String uinSeen = AstrBotEnv.botUin(app);
            final long deadline = System.currentTimeMillis() + AstrBotEnv.scanWaitMs(app);
            while (System.currentTimeMillis() < deadline) {
                if (Logs.bridgeLooksConnected(app)) break;
                if (!napcat) Logs.captureSnowCred(app);
                String uin = Logs.findUin(app);
                if (uin.isEmpty() && napcat) uin = AstrBotInstaller.findNapcatUin(app);
                if (!uin.isEmpty() && !uin.equals(uinSeen)) {
                    uinSeen = uin;
                    AstrBotInstaller.writeAdmins(app, uin);
                    AstrBotInstaller.writeNapcatOneBot(app, uin);
                    AstrBotEnv.setBotUin(app, uin);
                    if (!restarted && napcat) {
                        // NapCat 的反连配置按 QQ 号命名，登录后写文件再重启一次即可自动连上
                        say(cb, "已登录 QQ " + uin + "，自动重启 NapCat 让反连配置生效…");
                        RootShell.sh(AstrBotScripts.stopBridge(app));
                        sleep(1500);
                        RootShell.sh(AstrBotScripts.startNapCat(app, napcatEntry(app)));
                        restarted = true;
                    }
                }
                sleep(3000);
            }
            return status(app);
        } catch (Throwable t) {
            return fail(app, s, "启动失败：" + t);
        }
    }

    /** NapCat 的入口目录：安装时可能带一层壳，这里在容器里找一次并记住。 */
    private static String napcatEntry(Context app) {
        String cached = app.getSharedPreferences("astrbot", Context.MODE_PRIVATE)
                .getString("napcat_entry", "");
        if (!cached.isEmpty()) return cached;
        RootShell.Result r = RootShell.sh("find " + RootShell.q(new File(AstrBotEnv.bookworm(app), "opt/napcat")
                .getAbsolutePath()) + " -maxdepth 3 \\( -name napcat.mjs -o -name napcat.js -o -name index.js \\) "
                + "2>/dev/null | head -1");
        String path = r.out == null ? "" : r.out.trim();
        if (path.isEmpty()) return "/opt/napcat";
        // 容器内路径（去掉宿主前缀）
        int i = path.indexOf("/opt/napcat");
        String inner = i >= 0 ? path.substring(i) : "/opt/napcat";
        String dir = inner.substring(0, inner.lastIndexOf('/'));
        app.getSharedPreferences("astrbot", Context.MODE_PRIVATE).edit()
                .putString("napcat_entry", dir).apply();
        return dir;
    }

    public static void stop(Context app) {
        RootShell.sh(AstrBotScripts.stopAll(app));
    }

    private static Status fail(Context app, Status s, String why) {
        s.state = State.ERROR;
        s.note = why;
        com.mineways.AppLog.e("astrbot", why + "\n" + Logs.astrbotTail(app, 4000), null);
        return s;
    }

    private static void say(Callback cb, String msg) {
        if (cb != null) cb.onStatus(msg);
    }

    // ---------------------------------------------------------------- 端口

    static boolean portOpen(int port) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean httpOk(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(900);
            c.setReadTimeout(1500);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return code > 0 && code < 500;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean waitPort(int port, long timeout, Callback cb, String label) {
        final long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) {
                say(cb, label + " 已就绪（:" + port + "）");
                return true;
            }
            sleep(1500);
        }
        return false;
    }

    private static boolean waitHttp(String url, long timeout, Callback cb, String label) {
        final long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (httpOk(url)) {
                say(cb, label + " 已就绪");
                return true;
            }
            sleep(1500);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 日志

    public static final class Logs {

        private Logs() {
        }

        public static String astrbotTail(Context app, int max) {
            return RootShell.cat(AstrBotEnv.astrbotLog(app).getAbsolutePath(), max);
        }

        public static String bridgeTail(Context app, int max) {
            final boolean napcat = AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(app));
            return RootShell.cat((napcat ? AstrBotEnv.napcatLog(app) : AstrBotEnv.snowLog(app))
                    .getAbsolutePath(), max);
        }

        /** 桥→AstrBot 是否已建立：看 AstrBot 日志里 aiocqhttp 的连接记录。 */
        static boolean bridgeLooksConnected(Context app) {
            String log = astrbotTail(app, 120_000);
            if (log.isEmpty()) return false;
            String lower = log.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("aiocqhttp")
                    && (lower.contains("connected") || lower.contains("self_id")
                    || lower.contains("websocket") || lower.contains("bot 已连接"));
        }

        /** SnowLuma 首启日志里的初始账号：抓出来存着，WebView 登录页自动填。 */
        public static void captureSnowCred(Context app) {
            if (AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(app))) return;
            if (!AstrBotEnv.snowUser(app).isEmpty()) return;
            String log = RootShell.cat(AstrBotEnv.snowLog(app).getAbsolutePath(), 60_000);
            Matcher m = Pattern.compile("(?i)user=(\\S+)\\s+password=(\\S+)").matcher(log);
            String u = "", p = "";
            while (m.find()) {
                u = m.group(1);
                p = m.group(2);
            }
            if (!u.isEmpty()) AstrBotEnv.setSnowCred(app, u, p);
        }

        /** 从日志里找 bot 的 QQ 号（连接事件带 self_id）。 */
        static String findUin(Context app) {
            String log = astrbotTail(app, 120_000);
            Matcher m = Pattern.compile("(?i)(?:self_?id|uin|qq)[\"'\\s:=]+(\\d{5,12})").matcher(log);
            String last = "";
            while (m.find()) last = m.group(1);
            if (!last.isEmpty()) return last;
            String bridge = bridgeTail(app, 120_000);
            m = Pattern.compile("(?:登录成功|login success|uin[\"'\\s:=]+)(\\d{5,12})", Pattern.CASE_INSENSITIVE)
                    .matcher(bridge);
            while (m.find()) last = m.group(1);
            return last;
        }
    }
}
