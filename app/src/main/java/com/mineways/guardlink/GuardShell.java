package com.mineways.guardlink;

import android.content.Context;

import com.mineways.AppLog;
import com.mineways.blender.RootShell;

import java.util.ArrayList;
import java.util.List;

/**
 * 守护用到的提权命令层。
 *
 * <p>上游 demo 自带一份 {@code ShizukuRunner}（反射 {@code Shizuku.newProcess} + 自己 drain 流）。
 * 本 App 早有同一件事的正式实现 {@link RootShell}（Blender / AstrBot 都在用），所以这里
 * 只是薄封装 —— <b>不留两套提权通道</b>，否则 Shizuku 版本一升级就得两边改。
 *
 * <p>另外补了两件 demo 没做的事：
 * <ul>
 *   <li><b>包名白字符校验</b>：所有进 shell 的包名都必须匹配 {@code [A-Za-z0-9._-]+}。
 *       demo 是把包名直接拼进 {@code "am force-stop " + pkg}，包名来自系统 API 看着可信，
 *       但一旦哪天改成解析外部输入（比如证据文件里的字符串）就是现成的命令注入；
 *       守护这种自带提权的能力不该留这种口子。</li>
 *   <li><b>每条特权命令都进 AppLog</b>：断网/冻结/卸载是否真的执行过、返回是什么，
 *       出事时必须能在日志里查到，而不是只有一句 toast。</li>
 * </ul>
 */
public final class GuardShell {

    private static final String TAG = "GuardLink";

    private GuardShell() {
    }

    /** Shizuku 是否已授权可用（没授权时守护只能检测与提示，不能断网/冻结/卸载）。 */
    public static boolean ready() {
        try {
            return RootShell.isReady();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手机上装没装 Shizuku 本体（用来决定引导文案）。 */
    public static boolean installed() {
        try {
            return RootShell.isInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拉起 Shizuku 授权请求；返回 false 表示连请求都发不出去。 */
    public static boolean requestPermission() {
        try {
            return RootShell.requestPermission();
        } catch (Throwable t) {
            AppLog.e(TAG, "请求 Shizuku 授权失败", t);
            return false;
        }
    }

    /**
     * 跑一条 shell（以 shell uid 身份，等价 adb shell）。永不抛：失败以 ok=false + 原因回来，
     * 调用方（功能页/警报通知）负责把原因显示出来。
     */
    public static Result sh(String script) {
        try {
            RootShell.Result r = RootShell.sh(script);
            String out = r == null || r.out == null ? "" : r.out.trim();
            AppLog.i(TAG, "sh(" + brief(script) + ") → code=" + (r == null ? -1 : r.code)
                    + " out=" + brief(out));
            return new Result(r != null && r.ok(), out);
        } catch (Throwable t) {
            AppLog.e(TAG, "sh 执行异常: " + brief(script), t);
            return new Result(false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 特权动作的执行结果：成功与否 + 一句话说明（说明要能显示给用户看）。 */
    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * 合法的 Android 包名才准进 shell。空、含空格、含任何 shell 元字符一律拒。
     */
    public static boolean safePkg(String pkg) {
        if (pkg == null || pkg.length() == 0 || pkg.length() > 256) {
            return false;
        }
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return pkg.indexOf('.') > 0;      // 至少一段，避免 "abc" 这种半截值
    }

    // ---------------------------------------------------------------- 三类特权动作

    /**
     * 切断 WiFi 与数据流量（全局降级路径）。
     *
     * <p>下发命令<b>之前</b>先把两个开关的现值读好存盘：命令一旦下去就再也问不出「断之前是什么样子」，
     * 而恢复时必须照着那个样子还原 —— 不然就是替你把本来关着的流量打开（他报的正是这条）。
     * 闸门没放行就直接不做，并把「为什么没做」返回出去。
     */
    public static Result cutNetwork(Context ctx) {
        if (!GuardFlags.killNetwork(ctx)) {
            return skipped("断网能力已关闭（guard_kill_net=0，云控或本机下发）");
        }
        Boolean wifi = radio(ctx, "wifi_on");
        Boolean data = radio(ctx, "mobile_data");
        GuardNetState.Plan p = GuardNetState.cut(wifi, data);
        GuardNetState.saveCut(ctx, System.currentTimeMillis(), wifi, data);
        return run(p.cmds, p.note, "断网");
    }

    /**
     * 恢复网络：只打开「断之前本来就是开着」的那一样。
     *
     * <p>这里<b>不看</b> {@code guard_kill_net}：那个开关管的是「准不准断网」，
     * 而恢复是断网的退路。要是把它也 gated，云端一改成 0，已经断掉的网络就永远解不开
     * （心跳会把 {@code networkCut} 按「没断过」纠正掉，恢复动作连被调到的机会都没有）。
     */
    public static Result restoreNetwork(Context ctx) {
        GuardNetState.Plan p = GuardNetState.restore(GuardNetState.hasCut(ctx),
                GuardNetState.wifiWas(ctx), GuardNetState.dataWas(ctx));
        if (p.isEmpty()) {
            return new Result(true, p.note);
        }
        Result r = run(p.cmds, p.note, "恢复网络");
        if (r.ok) {
            GuardNetState.clear(ctx);
        }
        return r;
    }

    /** 逐条下发并汇总；有一条不成就把每条的原因都带回去（不许只说「失败了」）。 */
    private static Result run(List<String> cmds, String note, String what) {
        List<String> bad = new ArrayList<>();
        for (String cmd : cmds) {
            Result r = sh(cmd);
            if (!r.ok) {
                bad.add(cmd + " → " + r.detail);
            }
        }
        if (bad.isEmpty()) {
            return new Result(true, note);
        }
        StringBuilder sb = new StringBuilder(what).append("未完全成功：");
        for (int i = 0; i < bad.size(); i++) {
            sb.append(i > 0 ? "；" : "").append(bad.get(i));
        }
        sb.append("（").append(note).append("）");
        return new Result(false, sb.toString());
    }

    /**
     * 用与 {@code svc} 同一条通道（Shizuku shell）读开关现值。
     *
     * <p>读不到就返回 {@code null}，调用方按「不知道」处理：断的时候照断（保护优先），
     * 恢复的时候<b>不碰</b>那一趟。没插卡、ROM 裁了这个 setting，都走这条路。
     */
    private static Boolean radio(Context ctx, String key) {
        Result r = sh("settings get global " + key);
        if (!r.ok) {
            return null;
        }
        return GuardNetState.parseRadio(r.detail);
    }

    // ---------------------------------------------------------------- per-app 精确断网

    /**
     * 只掐嫌疑应用自己的联网：iptables 的 owner match 按 uid 在 OUTPUT 链 DROP/REJECT，
     * 其余应用联网完全无感 —— 这是「先保护、少打扰」的关键一条。
     *
     * <p>返回 {@code ok=false} 有四种原因，都会原样显示到界面，绝不静默吞掉：
     * 闸门关闭 / 包名不合规 / 查不到 uid / 规则写不进去（内核没这个模块或不允许）。
     * 上层拿到 false 就按设计降级全局断网。
     */
    public static Result cutAppNet(Context ctx, String pkg) {
        if (!GuardFlags.perAppCut(ctx)) {
            return skipped("精确断网已关闭（guard_per_app=0），改用全局断网");
        }
        if (!GuardFlags.killNetwork(ctx)) {
            return skipped("断网能力已关闭（guard_kill_net=0）");
        }
        if (!safePkg(pkg)) {
            return skipped("包名不合规则，已拒绝执行：" + brief(pkg));
        }
        String refusal = GuardTrust.irreversibleRefusal(pkg, isSystemApp(ctx, pkg), "精确断网");
        if (refusal != null) {
            return skipped(refusal);
        }
        int uid = uidOf(ctx, pkg);
        if (uid < 0) {
            return skipped("查不到 " + pkg + " 的 uid，精确断网无法执行");
        }
        Result v4 = sh("iptables -I OUTPUT -m owner --uid-owner " + uid + " -j REJECT");
        Result v6 = sh("ip6tables -I OUTPUT -m owner --uid-owner " + uid + " -j REJECT");
        return v4.ok && v6.ok
                ? new Result(true, "已精确切断 " + pkg + "（uid " + uid + "）的全部联网，其余应用不受影响")
                : new Result(false, "规则写入失败（v4=" + v4.detail + " v6=" + v6.detail + "），需降级全局断网");
    }

    /** 解除 per-app 规则。删不干净不报错给用户 —— 卸载后整机重启也会自然清空。 */
    public static Result restoreAppNet(Context ctx, String pkg) {
        if (!safePkg(pkg)) {
            return skipped("包名不合规则，已拒绝执行：" + brief(pkg));
        }
        int uid = uidOf(ctx, pkg);
        if (uid < 0) {
            return skipped("查不到 " + pkg + " 的 uid，规则只能等重启自然清空");
        }
        Result v4 = sh("iptables -D OUTPUT -m owner --uid-owner " + uid + " -j REJECT");
        Result v6 = sh("ip6tables -D OUTPUT -m owner --uid-owner " + uid + " -j REJECT");
        return v4.ok && v6.ok
                ? new Result(true, "已恢复 " + pkg + "（uid " + uid + "）的联网")
                : new Result(false, "规则删除未确认（v4=" + v4.detail + " v6=" + v6.detail + "）");
    }

    /**
     * 是不是系统应用（{@code FLAG_SYSTEM}）。这是系统给的事实，包名伪装不出来。
     *
     * <p>查不到（没装 / 已卸载）按<b>不是</b>系统应用处理：那种包名下面每条命令本来就会失败，
     * 失败原因由命令自己报，比在这里猜一个「是系统应用」更好查。
     */
    public static boolean isSystemApp(Context ctx, String pkg) {
        if (!safePkg(pkg)) {
            return false;
        }
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ai != null && (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 包名 → uid。查不到（未安装 / 被卸载）返回 -1。 */
    public static int uidOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            AppLog.i(TAG, "查 uid 失败：" + brief(pkg) + " · " + t.getClass().getSimpleName());
            return -1;
        }
    }

    // ---------------------------------------------------------------- 无障碍设置写回

    /**
     * 用 shell 身份整体写 {@code enabled_accessibility_services}（无障碍防火墙的掐/还原都靠这条）。
     * 传 null 或空串表示「全清」—— 上游实现里空列表就是清空的语义，这里保持一样。
     */
    public static Result writeA11yServices(Context ctx, String flatList) {
        String value = flatList == null ? "" : flatList;
        Result one = sh("settings put secure enabled_accessibility_services " + RootShell.q(value));
        Result two = sh("settings put secure accessibility_enabled 1");
        return one.ok && two.ok
                ? new Result(true, "无障碍列表已写入（" + countFlats(value) + " 条）")
                : new Result(false, "写入失败：services=" + one.detail + " enabled=" + two.detail);
    }

    private static int countFlats(String flatList) {
        if (flatList == null || flatList.length() == 0) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < flatList.length(); i++) {
            if (flatList.charAt(i) == ':') {
                n++;
            }
        }
        return n;
    }

    /** 冻结嫌疑进程：底层调用（借道微信发消息等）随进程死亡一起失效。 */
    public static Result forceStop(Context ctx, String pkg) {
        if (!GuardFlags.allowFreeze(ctx)) {
            return skipped("冻结能力已关闭（guard_freeze=0，云控或本机下发）");
        }
        if (!safePkg(pkg)) {
            return skipped("包名不合规则，已拒绝执行：" + brief(pkg));
        }
        String refusal = GuardTrust.irreversibleRefusal(pkg, isSystemApp(ctx, pkg), "冻结");
        if (refusal != null) {
            return skipped(refusal);
        }
        Result r = sh("am force-stop " + pkg);
        return r.ok ? new Result(true, "已冻结 " + pkg)
                : new Result(false, "冻结 " + pkg + " 失败：" + r.detail);
    }

    /**
     * 卸载第三方应用。出厂默认关（要云控或本机打开 guard_uninstall）；
     * 开着也要 UI 层真人按压 + 二次确认后才走到这里。
     */
    public static Result uninstall(Context ctx, String pkg) {
        if (!GuardFlags.allowUninstall(ctx)) {
            return skipped("卸载能力未开启（guard_uninstall=0），出厂默认就是关的");
        }
        if (!safePkg(pkg)) {
            return skipped("包名不合规则，已拒绝执行：" + brief(pkg));
        }
        String refusal = GuardTrust.irreversibleRefusal(pkg, isSystemApp(ctx, pkg), "卸载");
        if (refusal != null) {
            return skipped(refusal);
        }
        Result r = sh("pm uninstall --user 0 " + RootShell.q(pkg));
        boolean ok = r.ok && (r.detail.contains("Success") || r.detail.length() == 0);
        return ok ? new Result(true, "已卸载（--user 0）" + pkg)
                : new Result(false, "卸载 " + pkg + " 失败：" + r.detail);
    }

    /** 读 TCP 连接表（真 pcap 需要 root 的 tcpdump，Shizuku shell 没有 CAP_NET_RAW；这里取等价审计面）。 */
    public static Result netTables() {
        return sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || echo SHELL_LIMITED");
    }

    /** 前台切换事件流（取证用，截尾部避免刷爆文件）。 */
    public static Result usageEvents() {
        return sh("dumpsys usagestats | tail -n 160");
    }

    /** 反查某包的安装来源（复活监测用）。 */
    public static Result installerOf(Context ctx, String pkg) {
        if (!safePkg(pkg)) {
            return skipped("包名不合规则，已拒绝查询：" + brief(pkg));
        }
        return sh("dumpsys package " + pkg + " | grep -iE 'installerPackageName|install origin'");
    }

    /** 无障碍被省电策略掐掉时，用 shell 身份把自身服务重新开启（只动自己这一条，不碰别人的）。 */
    public static Result enableOwnAccessibility(Context ctx, String flatValue) {
        if (flatValue == null || flatValue.length() == 0) {
            return skipped("没有要写入的无障碍组件名");
        }
        Result one = sh("settings put secure enabled_accessibility_services "
                + RootShell.q(flatValue));
        Result two = sh("settings put secure accessibility_enabled 1");
        if (!one.ok || !two.ok) {
            return new Result(false, "写入失败：services=" + one.detail + " enabled=" + two.detail);
        }
        // 命令返回 0 不等于系统认了：ROM 拦改无障碍设置时串照样写得进去但不生效，
        // 只有回读那串里真有本包名，才算恢复了信号源。
        if (!GuardProbe.accessibilityEnabled(ctx)) {
            return new Result(false, "命令成功但系统没认（这台 ROM 拦了改无障碍设置）："
                    + "现在启用列表是「" + GuardProbe.enabledAccessibility(ctx) + "」");
        }
        return new Result(true, "已通过 Shizuku 重新开启守护无障碍，并已回读确认");
    }

    /** 首次授权后自取 USAGE_STATS appop（没有它，媒体豁免与行为终筛都退化成保守不豁免）。 */
    public static Result grantUsageStats(Context ctx) {
        String pkg = ctx.getPackageName();
        if (!safePkg(pkg)) {
            return skipped("包名不合规则");
        }
        return sh("appops set " + pkg + " PACKAGE_USAGE_STATS allow");
    }

    private static Result skipped(String why) {
        AppLog.i(TAG, "跳过特权动作：" + why);
        return new Result(false, why);
    }

    /** 日志里不要把整条命令和输出铺满屏，截一下。 */
    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }
}
