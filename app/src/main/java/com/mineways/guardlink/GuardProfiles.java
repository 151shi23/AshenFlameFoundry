package com.mineways.guardlink;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;

import com.zeus.guardlink.GuardLinkCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 四步溯源筛查的数据采集面 + 证据包落盘。
 *
 * <p>「抓包」在这里是<b>降级实现</b>，也是诚实的：真 pcap 要 root 的 tcpdump（{@code CAP_NET_RAW}），
 * Shizuku 的 shell 身份同样没有。所以取证用 TrafficStats 按 uid 差分 + {@code /proc/net/tcp} 连接表
 * + {@code dumpsys usagestats} 事件流，审计价值等价、零额外权限。
 *
 * <p>相对 demo 的两处改动：证据文件一律 <b>UTF-8</b> 写（demo 用平台默认编码，中文行在
 * 别的工具里打开就是花的）；流量基线改成<b>只在触发时向前差分</b>并给每个 uid 留一份快照，
 * 不再每 5 秒遍历全量已装应用打点（那样既费电又会在省电策略下被掐）。
 */
public final class GuardProfiles {

    private final Context ctx;
    private final Map<String, long[]> baseline = new HashMap<>();
    private volatile long baselineAt;

    public GuardProfiles(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 基线快照（守护启动时打一次；触发时再打一次做向前差分）。 */
    public void snapshotTrafficBaseline() {
        Map<String, long[]> next = new HashMap<>();
        List<PackageInfo> installed = installed(GET_META);
        for (PackageInfo pi : installed) {
            if (pi.applicationInfo == null) {
                continue;
            }
            int uid = pi.applicationInfo.uid;
            next.put(pi.packageName, new long[]{TrafficStats.getUidRxBytes(uid),
                    TrafficStats.getUidTxBytes(uid)});
        }
        baseline.clear();
        baseline.putAll(next);
        baselineAt = System.currentTimeMillis();
    }

    private static final int GET_META = PackageManager.GET_META_DATA;
    private static final int GET_PERM = PackageManager.GET_PERMISSIONS
            | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS;

    private List<PackageInfo> installed(int flags) {
        try {
            List<PackageInfo> l = ctx.getPackageManager().getInstalledPackages(flags);
            return l == null ? new ArrayList<PackageInfo>() : l;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 供溯源界面展示画像明细：包 → 事发窗口流量增量。 */
    public Map<String, Long> trafficDeltas(GuardLinkCore.NoHumanDetector.TriggerResult t) {
        Map<String, Long> delta = new HashMap<>();
        for (PackageInfo pi : installed(GET_META)) {
            if (pi.applicationInfo == null) {
                continue;
            }
            long[] base = baseline.get(pi.packageName) == null ? new long[]{0, 0}
                    : baseline.get(pi.packageName);
            int uid = pi.applicationInfo.uid;
            long d = Math.max(0, TrafficStats.getUidRxBytes(uid) - base[0])
                    + Math.max(0, TrafficStats.getUidTxBytes(uid) - base[1]);
            if (d > 0) {
                delta.put(pi.packageName, d);
            }
        }
        return delta;
    }

    /** Core 的 Step1..Step4 要的画像列表（除流量、行为以外全部来自系统 API，伪造不了）。 */
    public List<GuardLinkCore.SuspectEngine.AppProfile> collect(
            GuardLinkCore.NoHumanDetector.TriggerResult t) {

        Map<String, Long> delta = trafficDeltas(t);
        List<Map.Entry<String, Long>> rank = new ArrayList<>(delta.entrySet());
        rank.sort(new java.util.Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        Map<String, Integer> trafficRank = new HashMap<>();
        for (int i = 0; i < rank.size(); i++) {
            trafficRank.put(rank.get(i).getKey(), i + 1);
        }

        String enabledA11y = GuardProbe.enabledAccessibility(ctx);
        String enabledListeners = GuardProbe.enabledNotificationListeners(ctx);
        String defaultSms = GuardProbe.defaultSmsPackage(ctx);
        java.util.Set<String> launcher = GuardProbe.launcherPackages(ctx);
        List<android.content.ComponentName> admins = activeAdmins();
        List<GuardLinkCore.SuspectEngine.AppProfile> out = new ArrayList<>();
        GuardTrust.ensureLoaded(ctx);   // 建档前把豁免名单装配好，空名单等于谁都不豁免
        // 云控特征集：一次装配，整轮复用（避免每个包都去解析一遍 FLAG 表）
        GuardFeatureSet features = GuardCloudFeatures.load();

        for (PackageInfo pi : installed(GET_PERM)) {
            if (pi.applicationInfo == null) {
                continue;
            }
            if (ctx.getPackageName().equals(pi.packageName)) {
                // 自己（含守护的常驻无障碍服务）天然声明无障碍/前台服务/悬浮窗，不能拿它当嫌疑人
                continue;
            }
            if ((pi.applicationInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                // 系统包（含被厂商商店更新过的）不进推举榜：一是它们本来就动不得（不可卸载），
                // 二是控制中心 / 侧边栏 / 系统弹窗这些「没人碰也会自己动」是它们的正常行为。
                // 伪装成系统包名的那些走另一条路：FLAG_SYSTEM 是系统给的，伪造不了，
                // 名字像而标志不是 → 照旧建档，还额外吃 w_fake_system 那条分。
                continue;
            }
            if (GuardTrust.keepOutOfRank(pi.packageName)) {
                continue;   // 豁免名单里「不进榜」及以上的：常见 App 不该被推举成嫌疑对象
            }
            GuardLinkCore.SuspectEngine.AppProfile p = new GuardLinkCore.SuspectEngine.AppProfile();
            p.pkg = pi.packageName;
            // 伪装系统包名那条分靠它区分真假：FLAG_SYSTEM 是系统给的事实，伪造不出来。
            // 被商店更新过的系统包两个标志都会挂；走到这里的都过不了上面那道 FLAG_SYSTEM 闸，
            // 这里再填一遍是防御性的 —— 万一将来闸门挪动，isRealSystem 也不能回到「永远 false」。
            p.isRealSystem = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (pi.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean boot = false;
            boolean fg = false;
            if (pi.requestedPermissions != null) {
                for (String perm : pi.requestedPermissions) {
                    if (perm == null) {
                        continue;
                    }
                    switch (perm) {
                        case "android.permission.REQUEST_INSTALL_PACKAGES":
                            p.requestInstallPackages = true;
                            break;
                        case "android.permission.SYSTEM_ALERT_WINDOW":
                            p.overlay = true;
                            break;
                        case "android.permission.WRITE_SETTINGS":
                            p.writeSettings = true;
                            break;
                        case "android.permission.RECEIVE_BOOT_COMPLETED":
                            boot = true;
                            break;
                        case "android.permission.FOREGROUND_SERVICE":
                            fg = true;
                            break;
                        case "android.permission.READ_SMS":
                        case "android.permission.RECEIVE_SMS":
                            p.sms = true;
                            break;
                        case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                            p.declaredAccessibilityService = true;
                            break;
                        case "android.permission.REQUEST_DELETE_PACKAGES":
                            // 反卸载自保 / 清掉取证工具 —— 真实 RAT 权限面（§8.3.158 样本）的标配
                            p.requestDeletePackages = true;
                            break;
                        case "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION":
                            // 投屏采集能力：屏幕内容外传的合法入口，恶意样本最爱借道
                            p.mediaProjection = true;
                            break;
                        case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                            // 少数 ROM/旧包会把它写成 uses-permission；正规写法在 service 上（下面补）
                            p.notificationListener = true;
                            break;
                        case "android.permission.BIND_VPN_SERVICE":
                            p.vpnService = true;
                            break;
                        default:
                            break;
                    }
                }
            }
            p.bootCompletedWithForeground = boot && fg;
            // 绑定型权限（无障碍/通知监听/VPN/设备管理员）是写在组件上的，不在 uses-permission 里。
            // 旧版只查 requestedPermissions，会把「真声明了无障碍服务」的包漏成 0 分。
            if (pi.services != null) {
                for (android.content.pm.ServiceInfo si : pi.services) {
                    if (si.permission == null) {
                        continue;
                    }
                    switch (si.permission) {
                        case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                            p.declaredAccessibilityService = true;
                            break;
                        case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                            p.notificationListener = true;
                            break;
                        case "android.permission.BIND_VPN_SERVICE":
                            p.vpnService = true;
                            break;
                        default:
                            break;
                    }
                }
            }
            if (pi.receivers != null) {
                for (android.content.pm.ActivityInfo ri : pi.receivers) {
                    if (ri.permission != null
                            && "android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)) {
                        p.deviceAdmin = true;   // 声明了设备管理员接收器（激活状态另由 DPM 补充）
                    }
                }
            }
            // Step2：已启用的无障碍服务归属（Settings 里是 "pkg/cls:pkg/cls" 拼接）
            if (enabledA11y != null && p.declaredAccessibilityService) {
                for (String flat : enabledA11y.split(":")) {
                    if (flat.startsWith(p.pkg + "/")) {
                        p.enabledAccessibilityService = true;
                        break;
                    }
                }
            }
            // 通知监听：启用串形状与无障碍一致，直接按同一个前缀口径判
            if (enabledListeners != null && p.notificationListener) {
                for (String flat : enabledListeners.split(":")) {
                    if (flat.startsWith(p.pkg + "/")) {
                        p.notificationListenerEnabled = true;
                        break;
                    }
                }
            }
            p.defaultSmsApp = defaultSms != null && defaultSms.equals(p.pkg);
            // 有桌面入口的包集合查不到（空集）时不判隐藏，避免一次查询失败把所有包都加 25 分
            p.noLauncherEntry = !launcher.isEmpty() && !launcher.contains(p.pkg);
            if (admins != null) {
                for (android.content.ComponentName cn : admins) {
                    if (p.pkg.equals(cn.getPackageName())) {
                        p.deviceAdmin = true;
                        break;
                    }
                }
            }
            Long d = delta.get(p.pkg);
            p.trafficDelta = d == null ? 0L : d;
            Integer r = trafficRank.get(p.pkg);
            p.trafficRank = r == null ? 0 : r;
            // t == null 是「界面手动重跑一次推举」：此刻没有触发上下文，第四步行为实锤不给分，
            // 其余三项（权限面 / 无障碍 / 流量）照打 —— 手动查看不应凭空造出一条作案证据。
            if (t != null && t.activePackages != null && t.activePackages.contains(p.pkg)) {
                p.behaviorHit = true;
                p.behaviorDetails.add("无人操作窗口内界面自行变化/自启动（触发原因: " + t.reason + "）");
            }
            // 云端新增动态特征叠加到画像；云端停用的内置动态 id 一并写进 suppressed
            features.applyDynamic(p);
            out.add(p);
        }
        return out;
    }

    private List<android.content.ComponentName> activeAdmins() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm == null ? null : dpm.getActiveAdmins();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 证据包落盘到 {@code Android/data/<包>/files/guardlink/evidence-<时间>/}，
     * 返回目录；界面给「复制摘要」和完整路径，方便拿去找人看。
     */
    public File writeEvidence(GuardLinkCore.NoHumanDetector.TriggerResult t) {
        File dir = new File(evidenceRoot(ctx), "evidence-" + t.pendingConfirmedAt);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(t.pendingConfirmedAt));
        write(dir, "meta.txt", "触发时刻: " + stamp + "（" + t.pendingConfirmedAt + "）\n"
                + "判定原因: " + t.reason + "\n"
                + "距上次人类触摸: " + t.idleMs + " ms\n"
                + "窗口内活动包: " + (t.activePackages == null ? "[]" : t.activePackages.toString()) + "\n"
                + "基线采样于: " + baselineAt + "\n");

        GuardShell.Result net = GuardShell.netTables();
        write(dir, "net_table.txt", "shell cat /proc/net/tcp(+6):\n" + net.detail + "\n");

        GuardShell.Result usage = GuardShell.usageEvents();
        write(dir, "usage_events.txt", usage.detail + "\n");

        StringBuilder sb = new StringBuilder();
        for (GuardLinkCore.SuspectEngine.AppProfile p : collect(t)) {
            sb.append(p.pkg).append(" trafficDelta=").append(p.trafficDelta)
                    .append(" rank=").append(p.trafficRank)
                    .append(" a11yEnabled=").append(p.enabledAccessibilityService)
                    .append(" behaviorHit=").append(p.behaviorHit)
                    .append('\n');
        }
        write(dir, "traffic_snapshot.txt", sb.toString());
        return dir;
    }

    public static File evidenceRoot(Context ctx) {
        File base = ctx.getExternalFilesDir("guardlink");
        if (base == null) {
            base = new File(ctx.getFilesDir(), "guardlink");
        }
        return base;
    }

    private static void write(File dir, String name, String content) {
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(new File(dir, name)), "UTF-8");
            w.write(content == null ? "" : content);
            w.flush();
        } catch (Throwable ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
