package com.mineways.guardlink;

import com.zeus.guardlink.GuardLinkCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 守护的「可云控特征集」（纯 Java，零 Android 依赖，PC 可单测）。
 *
 * <p>一条特征 = 一个 id + 静态/动态 + 分值 + 匹配式 + 标题/说明。内置特征写在
 * {@link #builtin()}，云端可以通过现有云控协议的 {@code FLAG} 指令增删改：
 *
 * <pre>
 * FLAG key=gsg_scrcpy   to=s|50|entry:scrcpy_server|内置 scrcpy 远控服务|它能把屏幕镜像到远端
 * FLAG key=gsg_dy_combo to=d|20|a11y_enabled+notify_enabled|无障碍+通知监听同时开启|验证码与读屏同时失守
 * FLAG key=gsd_st_scrcpy to=1     # 删除/停用内置特征（任何非空值都算）
 * FLAG key=gsg_custom   to=drop   # 删除云特征
 * </pre>
 *
 * <p><b>静态特征</b>匹配安装包结构（{@link GuardApkAudit.Meta}）：{@code entry:/asset:/permission:/
 * component:/pkg:/pkgre:/signer:/manifest_gt:/dex_hidden/dex_count_gt:/res_count_gt} 以及
 * {@code a11y_service/notify_service/vpn_service/admin_receiver/media_projection/request_delete/overlay/sms/
 * contacts/record/camera/manage_storage/install_packages/boot} 这些布尔面。
 *
 * <p><b>动态特征</b>匹配运行期画像（{@link GuardLinkCore.SuspectEngine.AppProfile}）：{@code a11y/a11y_enabled/
 * notify/notify_enabled/vpn/admin/default_sms/projection/delete_pkg/overlay/install_pkg/boot_fg/hidden/behavior/
 * sms/traffic_rank_le:N}，多个条件用 {@code +} 表示「全部满足」。
 *
 * <p>取值上限：单条分值 0..100；云特征最多 128 条；id 只允许 {@code [a-z0-9_]}（要能当 FLAG 键的后缀）。
 * 坏行整条忽略并计数，不影响内置检测 —— 云控写错一条，不能把整台手机的防护搞停。
 */
public final class GuardFeatureSet {

    public static final String PREFIX_ADD = "gsg_";
    public static final String PREFIX_DEL = "gsd_";
    public static final int MAX_CLOUD_FEATURES = 128;
    public static final int MAX_WEIGHT = 100;

    /** 内置静态特征的 id（守卫层按这些 id 取分/取文案，云端同 id 覆盖）。 */
    public static final String ST_SCRCPY = "st_scrcpy";
    public static final String ST_SPAKE2 = "st_spake2";
    public static final String ST_HIDDEN_DEX = "st_hidden_dex";
    public static final String ST_ZIP = "st_zip";
    public static final String ST_MANIFEST_BOMB = "st_manifest_bomb";
    public static final String ST_MANIFEST_LARGE = "st_manifest_large";
    public static final String ST_MANIFEST_UNPARSABLE = "st_manifest_unparsable";
    public static final String ST_DEX_COUNT = "st_dex_count";
    public static final String ST_RES_HUGE = "st_res_huge";
    public static final String ST_RES_MANY = "st_res_many";
    public static final String ST_A11Y = "st_a11y";
    public static final String ST_NOTIFY = "st_notify";
    public static final String ST_VPN = "st_vpn";
    public static final String ST_ADMIN = "st_admin";
    public static final String ST_COMBO_MANY = "st_combo_many";
    public static final String ST_COMBO_SOME = "st_combo_some";
    public static final String ST_DELETE_PROJECTION = "st_delete_projection";
    public static final String ST_SIGNER = "st_signer";

    /** 内置动态特征的 id（只用于云控停用；分值仍归 GuardLinkCore 的 W_*）。 */
    public static final String DY_INSTALL = "dy_install";
    public static final String DY_A11Y = "dy_a11y";
    public static final String DY_A11Y_ENABLED = "dy_a11y_enabled";
    public static final String DY_OVERLAY = "dy_overlay";
    public static final String DY_WRITE_SETTINGS = "dy_write_settings";
    public static final String DY_ADMIN = "dy_admin";
    public static final String DY_BOOT_FG = "dy_boot_fg";
    public static final String DY_SMS = "dy_sms";
    public static final String DY_DELETE = "dy_delete";
    public static final String DY_PROJECTION = "dy_projection";
    public static final String DY_NOTIFY = "dy_notify";
    public static final String DY_NOTIFY_ENABLED = "dy_notify_enabled";
    public static final String DY_VPN = "dy_vpn";
    public static final String DY_DEFAULT_SMS = "dy_default_sms";
    public static final String DY_HIDDEN = "dy_hidden";

    /** 一条特征。{@code builtin=true} 表示逻辑在守卫层写死，云端只能改分值/文案/停用。 */
    public static final class Feature {
        public final String id;
        public final boolean dynamic;
        public final int weight;
        public final String match;
        public final String title;
        public final String detail;
        public final boolean builtin;

        public Feature(String id, boolean dynamic, int weight, String match,
                       String title, String detail, boolean builtin) {
            this.id = id;
            this.dynamic = dynamic;
            this.weight = weight;
            this.match = match == null ? "" : match;
            this.title = title == null ? id : title;
            this.detail = detail == null ? "" : detail;
            this.builtin = builtin;
        }
    }

    private final Map<String, Feature> builtins = new LinkedHashMap<>();
    private final Map<String, Feature> cloud = new LinkedHashMap<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private int badLines;

    private GuardFeatureSet() {
    }

    /** 只有内置特征：没有云控配置时（或云控整份被拒）用这一份，行为与出厂完全一致。 */
    public static GuardFeatureSet builtin() {
        GuardFeatureSet s = new GuardFeatureSet();
        s.installBuiltins();
        return s;
    }

    private void installBuiltins() {
        // 分值/文案与 GuardApkAudit 出厂规则一一对应；云端同 id 覆盖。
        putBuiltin(new Feature(ST_SCRCPY, false, 50, "entry:scrcpy_server",
                "内置 scrcpy 远控服务",
                "安装包里带着 scrcpy server。它配合无线调试可以把屏幕实时镜像到远端并注入键鼠，"
                        + "正常视频/天气类应用没有任何理由内置它。", true));
        putBuiltin(new Feature(ST_SPAKE2, false, 40, "entry:spake2",
                "内置 ADB 无线调试配对库（SPAKE2）",
                "SPAKE2 是 ADB 无线调试配对专用算法。应用自己实现配对，等于准备自己拿 ADB shell 权限。", true));
        putBuiltin(new Feature(ST_HIDDEN_DEX, false, 40, "dex_hidden",
                "隐藏 DEX 载荷",
                "有 {v} 个 DEX 文件的路径伪装成 classes.dex / 资源文件等其它条目，是加壳/反分析的典型手法；"
                        + "真实代码在运行期才解密加载。", true));
        putBuiltin(new Feature(ST_ZIP, false, 30, "zip_unreadable",
                "压缩包无法正常解析",
                "APK 中央目录异常，常规检查工具读不全。这本身可能是反分析构造。", true));
        putBuiltin(new Feature(ST_MANIFEST_BOMB, false, 40, "manifest_gt:2000000",
                "清单体积异常（清单炸弹）",
                "AndroidManifest.xml 解压后 {v}。正常应用通常只有几百 KB 以内，超大清单用于撑爆解析器、干扰扫描。", true));
        putBuiltin(new Feature(ST_MANIFEST_LARGE, false, 15, "manifest_gt:500000",
                "清单体积偏大", "AndroidManifest.xml 解压后 {v}，高于常见水平。", true));
        putBuiltin(new Feature(ST_MANIFEST_UNPARSABLE, false, 25, "manifest_unparsable",
                "清单无法解析",
                "系统包管理器读不出这个 APK 的清单（可能被刻意构造）。装前无法确认它到底申请了什么。", true));
        putBuiltin(new Feature(ST_DEX_COUNT, false, 10, "dex_count_gt:7",
                "DEX 数量异常", "共 {v} 个 DEX 文件，显著多于常规应用；常见于加壳或多阶段载荷。", true));
        putBuiltin(new Feature(ST_RES_HUGE, false, 15, "res_count_gt:500",
                "资源条目异常膨胀", "资源目录条目 {v} 个，疑似用大量随机资源名干扰静态分析。", true));
        putBuiltin(new Feature(ST_RES_MANY, false, 10, "res_count_gt:150",
                "资源条目偏多", "资源目录条目 {v} 个。", true));
        putBuiltin(new Feature(ST_A11Y, false, 25, "a11y_service",
                "声明无障碍服务",
                "清单里挂了 BIND_ACCESSIBILITY_SERVICE。它能读屏、模拟点击，是远控与自动操作的入口；"
                        + "装前就该确认这个应用是否真的需要它。", true));
        putBuiltin(new Feature(ST_NOTIFY, false, 20, "notify_service",
                "声明通知监听服务", "能读取全部通知（含短信验证码、银行动账、社交消息）。", true));
        putBuiltin(new Feature(ST_VPN, false, 20, "vpn_service",
                "声明 VPN 服务", "能把整机流量经自己转发，具备中间人式劫持能力。", true));
        putBuiltin(new Feature(ST_ADMIN, false, 20, "admin_receiver",
                "声明设备管理员", "可锁屏、清数据、改密码、阻止卸载，是勒索与反卸载的常用入口。", true));
        putBuiltin(new Feature(ST_COMBO_MANY, false, 30, "combo_ge:4",
                "危险权限组合（{v} 项）",
                "同时申请投屏/删包/悬浮窗/存储/短信/通讯录/录音/相机/装包/开机自启中的 {v} 项。"
                        + "正常应用很少需要这样一套采集面。", true));
        putBuiltin(new Feature(ST_COMBO_SOME, false, 15, "combo_ge:2",
                "危险权限组合（{v} 项）", "同时申请 {v} 项高危能力，需结合应用用途判断。", true));
        putBuiltin(new Feature(ST_DELETE_PROJECTION, false, 15, "delete_and_projection",
                "删包 + 投屏", "既能抓屏又能卸载其它应用：既能看屏幕，也能清掉取证/竞品应用。", true));
        putBuiltin(new Feature(ST_SIGNER, false, 15, "junk_signer",
                "签名证书可疑", "证书主体是随机十六进制串（{v}），常见于自动化重打包与批量伪造。", true));
    }

    private void putBuiltin(Feature f) {
        builtins.put(f.id, f);
    }

    /**
     * 从云控 FLAG 表解析。键为 {@code gsg_<id>}（增/改）或 {@code gsd_<id>}（删/停用）。
     *
     * <p>失败的行只计数，不带任何效果；内置特征永远在，除非显式 {@code gsd_} 停用。
     */
    public static GuardFeatureSet fromFlags(Map<String, String> flags) {
        GuardFeatureSet s = builtin();
        if (flags == null) {
            return s;
        }
        for (Map.Entry<String, String> e : flags.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            String val = e.getValue() == null ? "" : e.getValue().trim();
            if (key.startsWith(PREFIX_DEL)) {
                String id = key.substring(PREFIX_DEL.length());
                if (validId(id) && !isOff(val)) {
                    s.deleted.add(id);
                }
            }
        }
        for (Map.Entry<String, String> e : flags.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            String val = e.getValue() == null ? "" : e.getValue().trim();
            if (!key.startsWith(PREFIX_ADD)) {
                continue;
            }
            String id = key.substring(PREFIX_ADD.length());
            if (!validId(id)) {
                s.badLines++;
                continue;
            }
            if (isDrop(val)) {
                s.deleted.add(id);
                continue;
            }
            Feature f = parse(id, val);
            if (f == null) {
                s.badLines++;
                continue;
            }
            if (s.cloud.size() >= MAX_CLOUD_FEATURES && !s.cloud.containsKey(id)) {
                s.badLines++;
                continue;
            }
            s.cloud.put(id, f);
        }
        return s;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_]{1,27}");
    }

    private static boolean isOff(String v) {
        String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || "0".equals(t) || "false".equals(t) || "off".equals(t)
                || "no".equals(t) || "n".equals(t);
    }

    private static boolean isDrop(String v) {
        return v != null && v.trim().toLowerCase(Locale.ROOT).startsWith("drop");
    }

    /** {@code kind|weight|match|title|detail}；title/detail 可省。 */
    private static Feature parse(String id, String v) {
        String[] p = v.split("\\|", -1);
        if (p.length < 3) {
            return null;
        }
        String kind = p[0].trim().toLowerCase(Locale.ROOT);
        boolean dynamic;
        if ("s".equals(kind) || "static".equals(kind) || "静态".equals(kind)) {
            dynamic = false;
        } else if ("d".equals(kind) || "dynamic".equals(kind) || "动态".equals(kind)) {
            dynamic = true;
        } else {
            return null;
        }
        int w;
        try {
            w = Integer.parseInt(p[1].trim());
        } catch (Throwable t) {
            return null;
        }
        if (w < 0 || w > MAX_WEIGHT) {
            return null;
        }
        String match = p[2].trim();
        if (match.isEmpty() || match.length() > 200) {
            return null;
        }
        String title = p.length > 3 && !p[3].trim().isEmpty() ? p[3].trim() : id;
        if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        StringBuilder detail = new StringBuilder();
        for (int i = 4; i < p.length; i++) {
            if (detail.length() > 0) {
                detail.append('|');
            }
            detail.append(p[i]);
        }
        String d = detail.toString().trim();
        if (d.length() > 300) {
            d = d.substring(0, 300);
        }
        return new Feature(id, dynamic, w, match, title, d, false);
    }

    // ---------------------------------------------------------------- 查询

    public boolean enabled(String id) {
        return id != null && !deleted.contains(id);
    }

    public boolean suppressed(String id) {
        return deleted.contains(id);
    }

    public boolean isBuiltin(String id) {
        return builtins.containsKey(id);
    }

    public int weight(String id, int def) {
        Feature f = effective(id);
        return f == null ? def : f.weight;
    }

    public String title(String id, String def) {
        Feature f = effective(id);
        return f == null || f.title == null ? def : f.title;
    }

    public String detail(String id, String def) {
        Feature f = effective(id);
        return f == null || f.detail == null || f.detail.isEmpty() ? def : f.detail;
    }

    private Feature effective(String id) {
        Feature c = cloud.get(id);
        return c != null ? c : builtins.get(id);
    }

    /** 云端新增的静态特征（不含对内置 id 的覆盖，覆盖只改元数据）。 */
    public List<Feature> customStatic() {
        List<Feature> out = new ArrayList<>();
        for (Feature f : cloud.values()) {
            if (!f.dynamic && !builtins.containsKey(f.id) && enabled(f.id)) {
                out.add(f);
            }
        }
        return out;
    }

    /** 云端新增的动态特征（内置动态 id 只支持停用，覆盖不重复计分）。 */
    public List<Feature> customDynamic() {
        List<Feature> out = new ArrayList<>();
        for (Feature f : cloud.values()) {
            if (f.dynamic && !builtins.containsKey(f.id) && enabled(f.id)) {
                out.add(f);
            }
        }
        return out;
    }

    public int cloudCount() {
        return cloud.size();
    }

    public int deletedCount() {
        return deleted.size();
    }

    public int badLineCount() {
        return badLines;
    }

    public String summary() {
        int s = 0;
        int d = 0;
        for (Feature f : customStatic()) {
            s++;
        }
        for (Feature f : customDynamic()) {
            d++;
        }
        return "云端特征 " + cloudCount() + " 条（新增静态 " + s + " / 动态 " + d
                + "，停用 " + deletedCount() + "，坏行 " + badLines + "）";
    }

    // ---------------------------------------------------------------- 静态匹配

    /** 匹配一条静态特征；{@code value} 用来替换文案里的 {@code {v}}。 */
    public static boolean matchStatic(Feature f, GuardApkAudit.Meta m) {
        return m != null && f != null && matchStatic(f.match, m);
    }

    public static boolean matchStatic(String match, GuardApkAudit.Meta m) {
        if (match == null || m == null) {
            return false;
        }
        String s = match.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("entry:") || s.startsWith("asset:")) {
            String needle = s.substring(s.indexOf(':') + 1);
            if (needle.isEmpty()) {
                return false;
            }
            for (String e : m.entries) {
                if (e != null && e.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
        if (s.startsWith("permission:")) {
            String perm = s.substring("permission:".length());
            for (String p : m.permissions) {
                if (p != null && p.equalsIgnoreCase(perm)) {
                    return true;
                }
            }
            return false;
        }
        if (s.startsWith("component:")) {
            String needle = s.substring("component:".length());
            for (String c : m.components) {
                if (c != null && c.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
        if (s.startsWith("pkgre:")) {
            try {
                return m.pkg != null && java.util.regex.Pattern.compile(s.substring("pkgre:".length()),
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(m.pkg).find();
            } catch (Throwable t) {
                return false;
            }
        }
        if (s.startsWith("pkg:")) {
            return m.pkg != null && m.pkg.equalsIgnoreCase(s.substring("pkg:".length()));
        }
        if (s.startsWith("signer:")) {
            String needle = s.substring("signer:".length());
            return m.signerCn != null && m.signerCn.toLowerCase(Locale.ROOT).contains(needle);
        }
        if (s.startsWith("manifest_gt:")) {
            long n = num(s.substring("manifest_gt:".length()), -1);
            return n > 0 && m.manifestBytes > n;
        }
        if (s.startsWith("dex_count_gt:")) {
            long n = num(s.substring("dex_count_gt:".length()), -1);
            return n >= 0 && m.dexEntryCount > n;
        }
        if (s.startsWith("res_count_gt:")) {
            long n = num(s.substring("res_count_gt:".length()), -1);
            return n >= 0 && m.resEntryCount > n;
        }
        return bool(m, s);
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean bool(GuardApkAudit.Meta m, String s) {
        switch (s) {
            case "dex_hidden":
                return m.hiddenDexCount > 0;
            case "zip_unreadable":
                return m.zipUnreadable;
            case "manifest_unparsable":
                return m.manifestUnparsable;
            case "a11y_service":
                return m.a11yService;
            case "notify_service":
                return m.notificationListenerService;
            case "vpn_service":
                return m.vpnService;
            case "admin_receiver":
                return m.deviceAdminReceiver;
            case "media_projection":
                return m.mediaProjection;
            case "request_delete":
                return m.requestDelete;
            case "overlay":
                return m.overlay;
            case "boot":
                return m.boot;
            case "sms":
                return m.sms;
            case "contacts":
                return m.contacts;
            case "record":
                return m.recordAudio;
            case "camera":
                return m.camera;
            case "manage_storage":
                return m.manageStorage;
            case "install_packages":
                return m.installPackages;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 动态匹配

    public static boolean matchDynamic(Feature f, GuardLinkCore.SuspectEngine.AppProfile p) {
        return p != null && f != null && matchDynamic(f.match, p);
    }

    /** 逗号 {@code +} 并联：每个条件都成立才算命中。 */
    public static boolean matchDynamic(String match, GuardLinkCore.SuspectEngine.AppProfile p) {
        if (match == null || p == null) {
            return false;
        }
        String s = match.trim().toLowerCase(Locale.ROOT);
        for (String one : s.split("\\+")) {
            String t = one.trim();
            if (t.startsWith("dyn:")) {
                t = t.substring(4);
            }
            if (!t.isEmpty() && !dynamicToken(t, p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean dynamicToken(String t, GuardLinkCore.SuspectEngine.AppProfile p) {
        if (t.startsWith("traffic_rank_le:")) {
            int n = (int) num(t.substring("traffic_rank_le:".length()), -1);
            return p.trafficRank > 0 && p.trafficRank <= n;
        }
        switch (t) {
            case "a11y":
                return p.declaredAccessibilityService;
            case "a11y_enabled":
                return p.enabledAccessibilityService;
            case "notify":
                return p.notificationListener;
            case "notify_enabled":
                return p.notificationListenerEnabled;
            case "vpn":
                return p.vpnService;
            case "admin":
                return p.deviceAdmin;
            case "default_sms":
                return p.defaultSmsApp;
            case "projection":
                return p.mediaProjection;
            case "delete_pkg":
                return p.requestDeletePackages;
            case "overlay":
                return p.overlay;
            case "install_pkg":
                return p.requestInstallPackages;
            case "boot_fg":
                return p.bootCompletedWithForeground;
            case "hidden":
                return p.noLauncherEntry && (p.requestInstallPackages || p.declaredAccessibilityService
                        || p.notificationListener || p.overlay || p.vpnService
                        || p.requestDeletePackages);
            case "behavior":
                return p.behaviorHit;
            case "sms":
                return p.sms;
            default:
                return false;
        }
    }

    /**
     * 把云端动态特征与「停用内置动态特征」注入画像。分值累加到 {@code cloudScore}，
     * 停用 id 写进 {@code suppressed}，由内核在算分时跳过对应内置项。
     */
    public void applyDynamic(GuardLinkCore.SuspectEngine.AppProfile p) {
        if (p == null) {
            return;
        }
        p.suppressed.addAll(deleted);
        for (Feature f : customDynamic()) {
            if (matchDynamic(f, p)) {
                p.cloudScore += f.weight;
                p.cloudDetails.add(f.title + "（云端特征）");
            }
        }
    }
}
