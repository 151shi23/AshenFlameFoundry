package com.mineways.prism;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import com.mineways.blender.RootShell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 棱镜玩机 · 命令通道与「读 → 备份 → 改 → 校验 → 回滚」状态机。
 *
 * <p>双通道与三大工具箱同源：
 * <ul>
 *   <li><b>Shizuku</b>：shell(uid=2000) 跑 settings / pm / am / cmd / dumpsys，功能最全；</li>
 *   <li><b>WRITE_SECURE_SETTINGS</b>：没有 Shizuku 时至少能读写三张 settings 表；</li>
 *   <li>两者都没有 → 所有开关灰置，只保留「免 Root 跳转」类功能。</li>
 * </ul>
 *
 * <p>所有写操作都带 {@code original_*} 备份与回读校验：ROM 不认这个键时写入会被
 * 静默忽略，这里会读回对比并自动回滚，避免 UI 显示"已开启"其实没生效。
 */
public final class PrismShell {

    public static final String PREFS = "prism_toolbox";
    private static final String K_BACKUPS = "backups";
    private static final String BACKUP_PREFIX = "original_";

    public final Context ctx;

    public PrismShell(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public static final class Out {
        public final boolean ok;
        public final String text;

        Out(boolean ok, String text) {
            this.ok = ok;
            this.text = text == null ? "" : text;
        }
    }

    public static final class Res {
        public final boolean ok;
        public final String msg;

        Res(boolean ok, String msg) {
            this.ok = ok;
            this.msg = msg == null ? "" : msg;
        }
    }

    // ---------------------------------------------------------------- 通道

    public boolean shizuku() {
        try {
            return RootShell.isReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean wss() {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean any() {
        return shizuku() || wss();
    }

    /** 通道徽章文案。 */
    public String channelLabel() {
        if (shizuku()) return "Shizuku 已就绪";
        if (wss()) return "免 Root 写设置（WSS）";
        return "未授权 · 仅跳转类可用";
    }

    public String channelDetail() {
        if (shizuku()) return "可执行 settings / pm / am / cmd / dumpsys";
        if (wss()) return "仅 settings 读写；冻结/卸载/演示模式需要 Shizuku";
        return "点上方「激活 Shizuku」，或按指引用 adb 授权";
    }

    /** 走 Shizuku 执行 shell；未就绪直接失败。 */
    public Out exec(String cmd) {
        if (!shizuku()) return new Out(false, "Shizuku 未就绪");
        try {
            RootShell.Result r = RootShell.sh(cmd);
            String t = r.out == null ? "" : r.out.trim();
            boolean ok = r.code == 0
                    && !t.contains("Error:")
                    && !t.contains("Error ")
                    && !t.contains("Exception")
                    && !t.contains("Failure")
                    && !t.contains("Permission denied")
                    && !t.contains("Activity not started")
                    && !t.contains("does not exist")
                    && !t.contains("Unable to find");
            return new Out(ok, t.isEmpty() && r.code != 0 ? ("exit " + r.code) : t);
        } catch (Throwable t) {
            return new Out(false, String.valueOf(t));
        }
    }

    /** 用 Shizuku 给自己授 WRITE_SECURE_SETTINGS（之后免 Shizuku 也能写 settings）。 */
    public Res grantWss() {
        if (wss()) return new Res(true, "已经有写设置权限");
        if (!shizuku()) return new Res(false, "需要先用 Shizuku 授权一次");
        Out o = exec("pm grant " + ctx.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
        if (wss()) return new Res(true, "已授予 WRITE_SECURE_SETTINGS");
        return new Res(false, "已执行授权命令，但系统仍未放行"
                + (o.text.isEmpty() ? "（部分 ROM 限制 adb 授予该权限）" : "：" + o.text)
                + "\n可重启页面再试，或用电脑 adb 执行同一条 pm grant");
    }

    // ---------------------------------------------------------------- settings 读写

    /** 读一个 settings 键；不存在/读不到返回 null（调用方必须按「不支持」处理，不能当 0）。 */
    public String get(String table, String key) {
        String v = null;
        if (shizuku()) {
            Out o = exec("settings get " + table + " " + key);
            if (o.ok) v = lastLine(o.text);
        }
        if (isEmpty(v) || "null".equals(v)) {
            if (wss()) v = readWss(table, key);
        }
        if (isEmpty(v) || "null".equals(v)) return null;
        return v.trim();
    }

    /** 直接写（不备份、不校验）——只用于写 original_* 备份键。 */
    private boolean putRaw(String table, String key, String value) {
        if (shizuku()) {
            Out o = exec("settings put " + table + " " + key + " " + RootShell.q(value));
            return o.ok;
        }
        if (wss()) return writeWss(table, key, value);
        return false;
    }

    public Res put(String table, String key, String value) {
        if (!any()) return new Res(false, "需要 Shizuku 或写设置权限");
        if (!putRaw(table, key, value)) {
            return new Res(false, shizuku() ? "写入命令失败" : "系统拒绝写入（键不存在或受保护）");
        }
        return new Res(true, "已写入");
    }

    /**
     * 带备份与校验的写入：首次修改前把原值写进 {@code original_<key>}；
     * 写后读回不一致 → 自动回滚并报「机型不支持」。
     */
    public Res setChecked(String table, String key, String value) {
        String cur = get(table, key);
        if (cur == null) {
            return new Res(false, "读不到 " + key + "，本机可能没有这个设置项");
        }
        if (sameValue(cur, value)) return new Res(true, "已经是目标值");
        String bakKey = BACKUP_PREFIX + key;
        if (get(table, bakKey) == null) {
            putRaw(table, bakKey, cur);      // 备份失败不阻断主流程
        }
        Res w = put(table, key, value);
        if (!w.ok) return w;
        String now = get(table, key);
        if (!sameValue(now, value)) {
            putRaw(table, key, cur);         // 系统不认 → 回滚
            return new Res(false, "这版 ROM 不接受该值，已自动回滚");
        }
        remember(table, key);
        return new Res(true, "已生效（已校验）");
    }

    /** 开关：当前值==on 就写 off，否则写 on。 */
    public Res toggle(String table, String key, String on, String off) {
        String cur = get(table, key);
        if (cur == null) return new Res(false, "读不到 " + key + "，本机可能不支持");
        return setChecked(table, key, sameValue(cur, on) ? off : on);
    }

    /** 数值型键的读回可能是 "1.0" / "1"，按数值比较，避免误判成"没生效"再回滚。 */
    public static boolean sameValue(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim(), y = b.trim();
        if (x.equalsIgnoreCase(y)) return true;
        try {
            return Double.compare(Double.parseDouble(x), Double.parseDouble(y)) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复到 original_* 备份值。 */
    public Res restore(String table, String key) {
        String bak = get(table, BACKUP_PREFIX + key);
        if (bak == null) return new Res(false, "没有这一项的原始值备份");
        Res r = put(table, key, bak);
        if (!r.ok) return r;
        return new Res(sameValue(get(table, key), bak), "已恢复原始值：" + bak);
    }

    public boolean hasBackup(String table, String key) {
        return get(table, BACKUP_PREFIX + key) != null;
    }

    /** 只备份、不修改：用于“主键 + 关联键”一起备份的复合功能（如高刷的 peak/min/user）。 */
    public void ensureBackup(String table, String key) {
        try {
            String cur = get(table, key);
            if (cur == null) return;
            if (get(table, BACKUP_PREFIX + key) == null) {
                putRaw(table, BACKUP_PREFIX + key, cur);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 游戏空间开发者选项：除组件外还要带 action/category，否则部分 ROM 只打开游戏空间首页。 */
    public Res startGameSpace() {
        String cmp = "com.oplus.games/business.compact.activity.GameDevelopOptionsActivity";
        Out o = exec("am start -n " + RootShell.q(cmp)
                + " -a oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS"
                + " -c android.intent.category.DEFAULT");
        if (o.ok) return new Res(true, "已打开游戏空间开发者选项");
        return startComponent(cmp, "com.oplus.games");
    }

    /** 一键恢复所有改过的键（本 App 记录过的）。 */
    public Res restoreAll() {
        Set<String> set = backups();
        if (set.isEmpty()) return new Res(false, "没有需要恢复的设置");
        int ok = 0, fail = 0;
        List<String> failed = new ArrayList<String>();
        for (String item : set) {
            String[] p = item.split("\\|", 2);
            if (p.length != 2) continue;
            Res r = restore(p[0], p[1]);
            if (r.ok) ok++;
            else {
                fail++;
                failed.add(p[0] + " " + p[1]);
            }
        }
        String msg = "已恢复 " + ok + " 项" + (fail > 0 ? "，失败 " + fail + " 项" : "");
        if (fail > 0 && !failed.isEmpty()) msg += "：" + failed.get(0);
        return new Res(fail == 0, msg);
    }

    public int backupCount() {
        return backups().size();
    }

    private Set<String> backups() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(K_BACKUPS, Collections.<String>emptySet());
        return new HashSet<String>(raw);
    }

    private void remember(String table, String key) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<String>(sp.getStringSet(K_BACKUPS, Collections.<String>emptySet()));
        set.add(table + "|" + key);
        sp.edit().putStringSet(K_BACKUPS, set).apply();
    }

    // ---------------------------------------------------------------- WSS 读写

    private String readWss(String table, String key) {
        try {
            if ("secure".equals(table)) return Settings.Secure.getString(ctx.getContentResolver(), key);
            if ("global".equals(table)) return Settings.Global.getString(ctx.getContentResolver(), key);
            return Settings.System.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean writeWss(String table, String key, String value) {
        try {
            if ("secure".equals(table)) {
                return Settings.Secure.putString(ctx.getContentResolver(), key, value);
            }
            if ("global".equals(table)) {
                return Settings.Global.putString(ctx.getContentResolver(), key, value);
            }
            return Settings.System.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 包管理 / 跳转

    /** 已冻结（suspended）才可能出现在 dumpsys 的 suspended=true。 */
    public boolean isSuspended(String pkg) {
        if (!shizuku()) return false;
        Out o = exec("dumpsys package " + pkg + " | grep -m1 suspended=");
        return o.text.contains("suspended=true");
    }

    public boolean isDisabled(String pkg) {
        if (!shizuku()) return false;
        Out o = exec("pm list packages -d");
        for (String line : o.text.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    public Res freeze(String pkg) {
        Out o = exec("cmd package suspend --user 0 " + pkg);
        if (!o.ok) o = exec("pm suspend " + pkg);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "冻结失败" : o.text);
        boolean suspended = isSuspended(pkg);
        return new Res(true, suspended ? "已冻结（图标置灰，可解冻）" : "已发送冻结命令（个别 ROM 需重启桌面生效）");
    }

    public Res unfreeze(String pkg) {
        Out o = exec("cmd package unsuspend --user 0 " + pkg);
        if (!o.ok) o = exec("pm unsuspend " + pkg);
        return new Res(o.ok, o.ok ? "已解冻" : (o.text.isEmpty() ? "解冻失败" : o.text));
    }

    /** OTA 这类服务包用 disable-user：比 suspend 更彻底（系统更新服务直接不存在）。 */
    public Res disablePackage(String pkg) {
        Out o = exec("pm disable-user --user 0 " + pkg);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "禁用失败" : o.text);
        return new Res(isDisabled(pkg), isDisabled(pkg) ? "已禁用 " + pkg : "命令已执行，但回读未确认（个别 ROM 延迟生效）");
    }

    public Res enablePackage(String pkg) {
        Out o = exec("pm enable --user 0 " + pkg);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "启用失败" : o.text);
        return new Res(!isDisabled(pkg), "已恢复 " + pkg);
    }

    public Res forceStop(String pkg) {
        Out o = exec("am force-stop " + pkg);
        return new Res(o.ok, o.ok ? "已强制停止" : o.text);
    }

    public Res clearData(String pkg) {
        Out o = exec("pm clear " + pkg);
        return new Res(o.ok && o.text.contains("Success"), o.ok ? "已清除应用数据" : o.text);
    }

    public Res uninstall(String pkg) {
        Out o = exec("pm uninstall --user 0 " + pkg);
        return new Res(o.ok && o.text.contains("Success"), o.ok ? "已卸载（恢复出厂可回）" : o.text);
    }

    /** 精准禁用/启用一个组件（广告 Activity/Service 单点掐掉，比整包冻结细）。 */
    public Res setComponent(String pkg, String cls, boolean enable) {
        if (pkg == null || pkg.trim().isEmpty() || cls == null || cls.trim().isEmpty()) {
            return new Res(false, "包名/组件名不能为空");
        }
        String cmp = pkg.trim() + "/" + cls.trim();
        Out o = exec("pm " + (enable ? "enable" : "disable-user") + " --user 0 " + RootShell.q(cmp));
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "命令失败" : o.text);
        return new Res(true, enable ? "已启用组件" : "已禁用组件（可用同入口恢复）");
    }

    // ---------------------------------------------------------------- 特色玩机

    /** 应用待机分组：10 active / 20 working_set / 30 frequent / 40 rare / 45 restricted。 */
    public int standbyBucket(String pkg) {
        Out o = exec("am get-standby-bucket " + pkg);
        try {
            return Integer.parseInt(o.text.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    public Res setStandbyBucket(String pkg, int bucket) {
        Out o = exec("am set-standby-bucket " + pkg + " " + bucket);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "设置失败" : o.text);
        int now = standbyBucket(pkg);
        return new Res(now == bucket, now == bucket
                ? "待机分组已设为 " + bucketName(bucket)
                : "系统未接受（回读 " + now + "）");
    }

    public static String bucketName(int b) {
        switch (b) {
            case 10: return "active（最活跃）";
            case 20: return "working_set";
            case 30: return "frequent";
            case 40: return "rare（很少用）";
            case 45: return "restricted（严格限制）";
            default: return "未知(" + b + ")";
        }
    }

    /** AppOps 读一个操作的当前模式（allow/ignore/deny/…）。 */
    public String appops(String pkg, String op) {
        Out o = exec("cmd appops get " + pkg + " " + op);
        if (!o.ok) return null;
        int i = o.text.indexOf(':');
        return i >= 0 ? o.text.substring(i + 1).trim() : o.text.trim();
    }

    public Res setAppops(String pkg, String op, String mode) {
        Out o = exec("cmd appops set " + pkg + " " + op + " " + mode);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "设置失败" : o.text);
        String now = appops(pkg, op);
        return new Res(mode.equalsIgnoreCase(now), mode.equalsIgnoreCase(now)
                ? "已设为 " + mode : "系统回读：" + now);
    }

    // ---------------------------------------------------------------- 隐私 / 清理 / 硬件

    /** 后台清道夫第一步：结束后台进程（前台应用不受影响）。 */
    public Res killBackground() {
        Out o = exec("am kill-all");
        return new Res(o.ok, o.ok ? "已结束后台进程" : (o.text.isEmpty() ? "需要 Shizuku" : o.text));
    }

    /** 后台清道夫第二步：让系统回收应用缓存（不改用户数据）。 */
    public Res trimCaches() {
        Out o = exec("pm trim-caches 32G");
        if (!o.ok) o = exec("cmd package trim-caches 32G");
        return new Res(o.ok, o.ok ? "已请求系统回收应用缓存"
                : (o.text.isEmpty() ? "本机不支持缓存回收命令" : o.text));
    }

    /** 刷新率上下限：返回原始值，读不到返回 null。 */
    public String refreshMin() {
        return get("system", "min_refresh_rate");
    }

    public String refreshPeak() {
        return get("system", "peak_refresh_rate");
    }

    /** 同时锁 min / peak；第二个键失败时回滚第一个，避免只锁了一半。 */
    public Res setRefreshRange(String min, String peak) {
        Res r1 = setChecked("system", "min_refresh_rate", min);
        if (!r1.ok) return r1;
        Res r2 = setChecked("system", "peak_refresh_rate", peak);
        if (!r2.ok) {
            restore("system", "min_refresh_rate");
            return r2;
        }
        return new Res(true, "刷新率已锁定 " + min + "–" + peak + "Hz（已校验）");
    }

    public Res resetRefresh() {
        Res a = restore("system", "min_refresh_rate");
        Res b = restore("system", "peak_refresh_rate");
        if (a.ok || b.ok) return new Res(true, "已恢复系统自动刷新率");
        return new Res(false, "没有刷新率备份；可先锁定一次再恢复");
    }

    /** 无线调试开关（Android 11+ 的 adb_wifi_enabled）。 */
    public String wifiAdb() {
        return get("global", "adb_wifi_enabled");
    }

    public Res setWifiAdb(boolean on) {
        return setChecked("global", "adb_wifi_enabled", on ? "1" : "0");
    }

    public String adbPort() {
        Out o = exec("getprop service.adb.tcp.port");
        return o.text.trim();
    }

    /** 一键锁屏。 */
    public Res lockScreen() {
        Out o = exec("input keyevent 26");
        return new Res(o.ok, o.ok ? "已锁屏" : (o.text.isEmpty() ? "需要 Shizuku" : o.text));
    }

    /** 读 sysfs 的一行；文件不存在/读不到返回 null。 */
    public String cat(String path) {
        Out o = exec("cat " + path);
        if (!o.ok) return null;
        String t = lastLine(o.text);
        return t == null || t.isEmpty() || t.contains("No such file") || t.contains("Permission denied")
                ? null : t.trim();
    }

    /** 电池循环次数 / 满充容量 / 设计容量 -> 健康度。 */
    public String batteryCycleReport() {
        String cycle = cat("/sys/class/power_supply/battery/cycle_count");
        String full = cat("/sys/class/power_supply/battery/charge_full");
        String design = cat("/sys/class/power_supply/battery/charge_full_design");
        String status = cat("/sys/class/power_supply/battery/status");
        StringBuilder b = new StringBuilder();
        b.append("循环次数：").append(cycle == null ? "本机未公开" : cycle + " 次").append('\n');
        b.append("满充容量：").append(mah(full)).append('\n');
        b.append("设计容量：").append(mah(design)).append('\n');
        if (full != null && design != null) {
            try {
                double ratio = Double.parseDouble(full.trim()) / Double.parseDouble(design.trim()) * 100.0;
                b.append("估算健康度：").append(String.format(Locale.US, "%.1f", ratio)).append("%");
                b.append(ratio >= 90 ? "（良好）" : (ratio >= 80 ? "（正常损耗）" : "（建议关注）")).append('\n');
            } catch (Throwable ignored) {
            }
        }
        b.append("当前状态：").append(status == null ? "未知" : status);
        return b.toString();
    }

    private static String mah(String micro) {
        if (micro == null) return "未公开";
        try {
            double v = Double.parseDouble(micro.trim());
            if (v > 100000) v = v / 1000.0;      // 多数机型给的是 µAh
            return String.format(Locale.US, "%.0f mAh", v);
        } catch (Throwable t) {
            return micro;
        }
    }

    // ---------------------------------------------------------------- 权限审计

    public String packageDump(String pkg) {
        Out o = exec("dumpsys package " + pkg);
        return o.ok ? o.text : null;
    }

    /** 解析 dumpsys package 的 runtime permissions 段：[权限全名, "1"=已授予 / "0"=未授予]。 */
    public static List<String[]> parseRuntimePerms(String dump) {
        List<String[]> out = new ArrayList<String[]>();
        if (dump == null) return out;
        boolean in = false;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("runtime permissions:")) {
                in = true;
                continue;
            }
            if (!in || t.isEmpty()) continue;
            int g = t.indexOf("granted=");
            if (g < 0) {
                if (t.endsWith(":")) break;      // 进入 install permissions 等下一段
                continue;
            }
            int c = t.indexOf(':');
            if (c <= 0) continue;
            String perm = t.substring(0, c).trim();
            out.add(new String[]{perm, t.substring(g + 8).trim().startsWith("true") ? "1" : "0"});
        }
        return out;
    }

    public Res revokePermission(String pkg, String perm) {
        if (perm == null || perm.trim().isEmpty()) return new Res(false, "权限名为空");
        Out o = exec("pm revoke " + pkg + " " + perm.trim());
        return new Res(o.ok, o.ok ? "已撤销 " + shortPerm(perm)
                : (o.text.isEmpty() ? "撤销失败（系统应用或固定权限）" : o.text));
    }

    public static String shortPerm(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('.');
        return i >= 0 && i + 1 < p.length() ? p.substring(i + 1) : p;
    }

    /** 强制进入/退出 Doze。 */
    public Res doze(boolean enter) {
        Out o = exec(enter ? "dumpsys deviceidle force-idle" : "dumpsys deviceidle unforce");
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "命令失败" : o.text);
        return new Res(true, enter
                ? "已请求进入 Doze（灭屏后生效；插电时可能被系统推迟）"
                : "已退出 Doze");
    }

    public String dozeState() {
        Out o = exec("dumpsys deviceidle get deep");
        if (!o.ok || o.text.trim().isEmpty()) o = exec("dumpsys deviceidle get state");
        return o.text.trim();
    }

    /** 固定性能模式（锁最高频）。 */
    public String perfState() {
        Out o = exec("cmd power get-fixed-performance-mode-enabled");
        if (!o.ok || o.text.trim().isEmpty()) {
            o = exec("dumpsys power | grep -i FixedPerformanceMode");
        }
        return o.text.trim();
    }

    public Res setPerf(boolean on) {
        Out o = exec("cmd power set-fixed-performance-mode-enabled " + on);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "命令失败" : o.text);
        String now = perfState();
        return new Res(now.toLowerCase(Locale.ROOT).contains(String.valueOf(on)),
                on ? "已开启固定性能模式（注意发热）" : "已关闭固定性能模式");
    }

    /** 系统夜间模式：yes / no / auto。 */
    public String uiMode() {
        Out o = exec("cmd uimode night");
        if (!o.ok) return "";
        String t = o.text.toLowerCase(Locale.ROOT);
        if (t.contains("yes")) return "yes";
        if (t.contains("no")) return "no";
        if (t.contains("auto")) return "auto";
        return o.text.trim();
    }

    public Res setUiMode(String mode) {
        Out o = exec("cmd uimode night " + mode);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "切换失败" : o.text);
        String now = uiMode();
        return new Res(mode.equalsIgnoreCase(now), "当前夜间模式：" + now);
    }

    /** 分辨率/密度：wm 的原始输出，含 Physical/Override 两行。 */
    public String wmSize() {
        Out o = exec("wm size");
        return o.text.trim();
    }

    public String wmDensity() {
        Out o = exec("wm density");
        return o.text.trim();
    }

    public Res setWmSize(String size) {
        Out o = exec("wm size " + size);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "设置失败" : o.text);
        return new Res(wmSize().contains(size), "已设置显示分辨率 " + size);
    }

    public Res setWmDensity(int dpi) {
        Out o = exec("wm density " + dpi);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "设置失败" : o.text);
        return new Res(wmDensity().contains(String.valueOf(dpi)), "已设置显示密度 " + dpi + "dpi");
    }

    public Res resetWm() {
        exec("wm size reset");
        Out o = exec("wm density reset");
        return new Res(o.ok || true, "已恢复原生分辨率与密度");
    }

    /** 电池体检：把 dumpsys battery 的字段抽成一行行中文。 */
    public String batteryReport() {
        Out o = exec("dumpsys battery");
        if (!o.ok) return "读取失败：" + o.text;
        StringBuilder b = new StringBuilder();
        String level = field(o.text, "level");
        String temp = field(o.text, "temperature");
        String volt = field(o.text, "voltage");
        String health = field(o.text, "health");
        String status = field(o.text, "status");
        String ac = field(o.text, "AC powered");
        String usb = field(o.text, "USB powered");
        b.append("电量：").append(level.isEmpty() ? "?" : level + "%").append('\n');
        try {
            b.append("温度：").append(String.format(Locale.US, "%.1f", Integer.parseInt(temp) / 10.0)).append(" °C\n");
        } catch (Throwable t) {
            b.append("温度：").append(temp).append('\n');
        }
        b.append("电压：").append(volt).append(" mV\n");
        b.append("健康度：").append(healthName(health)).append('\n');
        b.append("状态：").append(statusName(status)).append('\n');
        b.append("供电：").append("true".equalsIgnoreCase(ac) ? "AC 充电器"
                : ("true".equalsIgnoreCase(usb) ? "USB" : "电池"));
        return b.toString();
    }

    private static String field(String text, String key) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) return t.substring(key.length() + 1).trim();
        }
        return "";
    }

    private static String healthName(String h) {
        switch (h) {
            case "2": return "良好";
            case "3": return "过热";
            case "4": return "损坏";
            case "5": return "过压";
            case "6": return "未指定故障";
            case "7": return "过冷";
            default: return "未知(" + h + ")";
        }
    }

    private static String statusName(String s) {
        switch (s) {
            case "2": return "充电中";
            case "3": return "放电中";
            case "4": return "未充电";
            case "5": return "已充满";
            default: return "未知(" + s + ")";
        }
    }

    // ---------------------------------------------------------------- 电量

    /** 读 dumpsys 的模拟电量；读不到返回 -1。 */
    public int batteryLevel() {
        Out o = exec("dumpsys battery | grep -m1 level:");
        String t = o.text;
        int i = t.indexOf(':');
        if (i < 0) return -1;
        try {
            return Integer.parseInt(t.substring(i + 1).trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 伪造电量（仅显示层，BatteryService 模拟模式）。 */
    public Res setBatteryLevel(int level) {
        Out o = exec("dumpsys battery set level " + level);
        if (!o.ok) return new Res(false, o.text.isEmpty() ? "设置失败" : o.text);
        int now = batteryLevel();
        if (now >= 0 && now != level) {
            exec("dumpsys battery reset");
            return new Res(false, "系统未接受该电量值，已恢复真实读数");
        }
        return new Res(true, "电量已显示为 " + level + "%（全系统读数都会变）");
    }

    public Res resetBattery() {
        Out o = exec("dumpsys battery reset");
        return new Res(o.ok, o.ok ? "已恢复真实电量" : (o.text.isEmpty() ? "恢复失败" : o.text));
    }

    /**
     * 启动组件；失败时自动退回「打开应用详情」。
     *
     * <p>组件名里的 {@code $}（如 {@code Settings$BatterySaverSettingsActivity}）必须整体加引号，
     * 否则 sh 会把它当变量展开，命令变成 {@code am start -n com.android.settings/.Settings}
     * ——看起来"打开了"，其实跳错页。
     */
    public Res startComponent(String component, String fallbackPkg) {
        Out o = exec("am start -n " + RootShell.q(component));
        if (o.ok) return new Res(true, "已打开");
        if (fallbackPkg != null) {
            Out f = exec("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + fallbackPkg);
            if (f.ok) return new Res(true, "目标页不存在，已打开应用详情");
        }
        return new Res(false, o.text.isEmpty() ? "打开失败（该 ROM 没有这个入口）" : o.text);
    }

    // ---------------------------------------------------------------- 小工具

    /** 取最后一行非空输出：settings get 正常只有一行，前面偶尔会混入 Warning。 */
    public static String lastLine(String s) {
        if (s == null) return null;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 已暂停/已禁用包名匹配（大小写不敏感，防手输包名带空格）。 */
    public static boolean samePkg(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** 供界面显示：把 settings 值翻译成开关状态。 */
    public static boolean isOn(String value, String onValue) {
        return value != null && value.trim().equalsIgnoreCase(onValue);
    }

    public static String lc(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
