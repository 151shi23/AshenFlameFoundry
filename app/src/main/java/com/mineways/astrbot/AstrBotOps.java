package com.mineways.astrbot;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.blender.RootShell;

import java.io.File;

/**
 * 紧急运维与诊断：云端通过 astrbot_cfg.ops 下发。
 *
 * <p>一次性动作（stop / reset / restart）用 nonce 语义：只有值变化才执行一次，
 * 执行后把 nonce 落盘，避免每次刷新页面都重复停/删。所有动作都要求 Shizuku 在线。</p>
 */
public final class AstrBotOps {

    private static final String PREF = "astrbot";

    private AstrBotOps() {
    }

    /** 应用云端紧急指令；返回本次实际执行的动作描述（无动作返回空串）。 */
    public static String applyEmergency(Context app) {
        if (!RootShell.isReady()) return "";
        final SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        final StringBuilder done = new StringBuilder();

        final String stop = AstrBotEnv.stopNonce(app);
        if (changed(sp, "ops_stop", stop)) {
            RootShell.sh(AstrBotScripts.stopAll(app));
            mark(sp, "ops_stop", stop);
            done.append("已执行紧急停止；");
        }
        final String reset = AstrBotEnv.resetNonce(app);
        if (changed(sp, "ops_reset", reset)) {
            AstrBotInstaller.uninstall(app);
            mark(sp, "ops_reset", reset);
            done.append("已执行重置（容器与配置已清空）；");
        }
        final String restart = AstrBotEnv.restartNonce(app);
        if (changed(sp, "ops_restart", restart)) {
            if (AstrBotEnv.deployed(app)) {
                AstrBotOrchestrator.stop(app);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                final AstrBotOrchestrator.Status s = AstrBotOrchestrator.start(app, null);
                done.append("已执行远程重启（").append(s.note).append("）；");
            } else {
                done.append("远程重启跳过：尚未部署；");
            }
            mark(sp, "ops_restart", restart);
        }
        return done.toString();
    }

    private static boolean changed(SharedPreferences sp, String key, String value) {
        if (value == null || value.isEmpty()) return false;
        return !value.equals(sp.getString(key, ""));
    }

    private static void mark(SharedPreferences sp, String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    /** 一键诊断：环境/文件/端口/日志，给用户复制回传。 */
    public static String diagnostics(Context app) {
        final StringBuilder sb = new StringBuilder(8192);
        sb.append("=== AstrBot 诊断 ===\n");
        sb.append("版本: ").append(AstrBotEnv.version(app))
                .append(" · 云控开关: ").append(AstrBotEnv.enabled(app) ? "开" : "关")
                .append(" · 已部署: ").append(AstrBotEnv.deployed(app) ? "是" : "否")
                .append("（装过: ").append(AstrBotEnv.installedVersion(app)).append("）\n");
        sb.append("桥: ").append(AstrBotEnv.bridge(app))
                .append(" · 模板: ").append(AstrBotEnv.seedEnabled(app) ? "开" : "关")
                .append(" · 插件: ").append(AstrBotEnv.pluginsEnabled(app) ? "开" : "关")
                .append(" · 维护公告: ").append(AstrBotEnv.maint(app)).append("\n");
        sb.append("Shizuku: ").append(RootShell.isReady() ? "已授权" : "未授权")
                .append(" / root: ").append(RootShell.isRoot() ? "是" : "否").append("\n");
        sb.append("Linux QQ: ").append(AstrBotInstaller.isQqInstalled(app) ? "已安装" : "未安装").append("\n");

        sb.append("端口: webui ").append(portState(AstrBotEnv.portWebui(app)))
                .append(" / ws ").append(portState(AstrBotEnv.portWs(app)))
                .append(" / napcat ").append(portState(AstrBotEnv.portNapcat(app)))
                .append(" / snowluma ").append(portState(AstrBotEnv.portSnowluma(app))).append("\n");

        sb.append("文件: proot ").append(ok(new File(AstrBotEnv.prootBin(app))))
                .append(" · ubuntu/sh ").append(ok(new File(AstrBotEnv.ubuntu(app), "bin/sh")))
                .append(" · bookworm/sh ").append(ok(new File(AstrBotEnv.bookworm(app), "bin/sh")))
                .append(" · astrbot-venv ").append(ok(new File(AstrBotEnv.ubuntu(app), "opt/astrbot/venv/bin/python")))
                .append(" · cmd_config ").append(ok(new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/cmd_config.json")))
                .append("\n");
        final File plugins = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/plugins");
        final String[] kids = plugins.list();
        sb.append("插件目录: ").append(kids == null ? "缺失" : (kids.length + " 个")).append("\n");
        sb.append("QQ 目录: ").append(ok(new File(AstrBotEnv.bookworm(app), "opt/QQ/qq"))).append("\n");

        sb.append("\n--- AstrBot 日志（尾） ---\n");
        sb.append(tail(AstrBotOrchestrator.Logs.astrbotTail(app, 6000), 6000)).append('\n');
        sb.append("--- 桥日志（尾） ---\n");
        sb.append(tail(AstrBotOrchestrator.Logs.bridgeTail(app, 3000), 3000)).append('\n');
        return sb.toString();
    }

    private static String portState(int port) {
        return AstrBotOrchestrator.portOpen(port) ? "开" : "关";
    }

    private static String ok(File f) {
        return f != null && f.isFile() ? "✓" : "✗";
    }

    private static String tail(String s, int max) {
        if (s == null || s.isEmpty()) return "(空)";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }
}
