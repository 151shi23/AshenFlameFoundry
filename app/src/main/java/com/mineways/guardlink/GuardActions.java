package com.mineways.guardlink;

import android.content.Context;

import com.zeus.guardlink.GuardLinkCore;

import java.io.File;
import java.util.List;

/**
 * Core 的 {@code GuardActions} 在 Android 上的实现：特权动作全部经 {@link GuardShell}
 * （内部复用 App 既有的 {@code RootShell}）与 {@link GuardA11y}，取证走 {@link GuardProfiles}。
 *
 * <p>每个动作都做两件事：把闸门判一遍（{@link GuardFlags}，云控 &gt; 本机 &gt; 出厂），
 * 再把执行结果写进事件流 —— 用户必须能看见「为什么没断网」「为什么只掐了它一个」，
 * 而不是只收到一句结论。
 *
 * <p>v3.2 起断网有两条路：{@link #cutAppNet} 精确掐嫌疑应用（iptables owner match，其余应用无感），
 * 返回 false 才由内核降级 {@link #cutNetwork} 全局断。两条都被闸门拒掉时，内核仍会把
 * {@code networkCut} 置 true，所以纠正逻辑在 {@link GuardRuntime} 的心跳里。
 */
public final class GuardActions implements GuardLinkCore.GuardActions {

    private final Context ctx;
    private final GuardProfiles profiles;

    GuardActions(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.profiles = new GuardProfiles(this.ctx);
    }

    GuardProfiles profiles() {
        return profiles;
    }

    @Override
    public void cutNetwork() {
        GuardShell.Result r = GuardShell.cutNetwork(ctx);
        GuardRuntime.appendLog(r.ok ? "🌐 " + r.detail : "🌐 断网未成功：" + r.detail);
    }

    @Override
    public void restoreNetwork() {
        GuardShell.Result r = GuardShell.restoreNetwork(ctx);
        GuardRuntime.appendLog(r.ok ? "🌐 " + r.detail : "🌐 恢复网络未成功：" + r.detail);
    }

    @Override
    public boolean cutAppNet(String pkg) {
        GuardShell.Result r = GuardShell.cutAppNet(ctx, pkg);
        GuardRuntime.appendLog((r.ok ? "🎯 " : "🎯 精确断网未成功：") + r.detail);
        if (r.ok) {
            GuardRuntime.noteAppNetCut(pkg);
        }
        return r.ok;
    }

    @Override
    public void restoreAppNet(String pkg) {
        GuardShell.Result r = GuardShell.restoreAppNet(ctx, pkg);
        GuardRuntime.appendLog((r.ok ? "🎯 " : "🎯 恢复联网未确认：") + r.detail);
        GuardRuntime.noteAppNetCut(null);
    }

    @Override
    public void suspendUntrustedA11y() {
        GuardRuntime.appendLog("🧱 " + GuardA11y.suspend(ctx));
    }

    @Override
    public void restoreA11y() {
        GuardRuntime.appendLog("🧱 " + GuardA11y.restore(ctx));
    }

    @Override
    public void collectEvidence(GuardLinkCore.NoHumanDetector.TriggerResult t) {
        File dir = profiles.writeEvidence(t);
        GuardRuntime.appendLog(dir == null
                ? "📦 证据包写入失败（外部目录不可用），只保留界面内事件记录"
                : "📦 证据包已写入 " + dir.getAbsolutePath());
    }

    @Override
    public List<GuardLinkCore.SuspectEngine.AppProfile> collectProfiles(
            GuardLinkCore.NoHumanDetector.TriggerResult t) {
        return profiles.collect(t);
    }

    @Override
    public boolean uninstall(String pkg) {
        GuardShell.Result r = GuardShell.uninstall(ctx, pkg);
        GuardRuntime.appendLog(r.ok ? "🧨 " + r.detail : "🧨 卸载未执行：" + r.detail);
        return r.ok;
    }

    @Override
    public void kickHome() {
        GuardRuntime.kickHome();
    }

    @Override
    public void forceStop(String pkg) {
        GuardShell.Result r = GuardShell.forceStop(ctx, pkg);
        GuardRuntime.appendLog(r.ok ? "🧊 " + r.detail : "🧊 未冻结：" + r.detail);
    }
}
