package com.mineways.prism;

import com.mineways.cloud.FeatureCatalog;
import com.mineways.cloud.FeatureGate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 棱镜玩机 · 功能表（按品牌裁剪）。
 *
 * <p>这张表就是「自动适配界面」的数据源：每个功能带 vendor 位掩码，
 * {@link PrismProfile.Vendor} 决定哪些行会被渲染。命令本身在
 * {@link PrismShell} / {@code PrismActivity} 里按 id 实现。
 *
 * <p>通用性标注沿用逆向文档的三级口径：
 * <b>全品牌</b>（AOSP 命令/键）、<b>半通用</b>（命令通用、参数按品牌换）、
 * <b>品牌专属</b>（私有键，其他 ROM 直接无效，只在本品牌界面出现）。
 * 风险：SAFE=可逆；CAUTION=改系统状态；DANGER=冻结系统组件。
 */
public final class PrismFeatures {

    private PrismFeatures() {
    }

    public static final int SEC_DISPLAY = 0;
    public static final int SEC_APP = 1;
    public static final int SEC_SYSTEM = 2;
    /** 特色玩机：本 App 自己的差异化功能（待机分组/Doze/深色/分辨率/屏幕体检/网络诊断…）。 */
    public static final int SEC_FEATURE = 3;
    public static final int SEC_SHORTCUT = 4;

    public static final int SAFE = 0;
    public static final int CAUTION = 1;
    public static final int DANGER = 2;

    private static final int ALL = 0xF;

    public static final class Feature {
        public final String id;
        public final String title;
        public final String desc;
        /** 角标：全品牌 / vivo / ColorOS 15+ 之类。 */
        public final String tag;
        public final int section;
        public final int risk;
        public final int mask;

        Feature(String id, String title, String desc, String tag, int section, int risk, int mask) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.tag = tag;
            this.section = section;
            this.risk = risk;
            this.mask = mask;
        }

        public boolean available(PrismProfile.Vendor v) {
            return (mask & bit(v)) != 0;
        }
    }

    private static int bit(PrismProfile.Vendor v) {
        switch (v) {
            case XIAOMI: return 1;
            case VIVO: return 2;
            case OPPO: return 4;
            default: return 8;
        }
    }

    private static int mask(PrismProfile.Vendor... vs) {
        int m = 0;
        for (PrismProfile.Vendor v : vs) m |= bit(v);
        return m;
    }

    private static final List<Feature> ALL_FEATURES = new ArrayList<Feature>();
    /** id → 功能，云控按 id 反查登记键时用。 */
    private static final Map<String, Feature> BY_ID = new LinkedHashMap<String, Feature>();
    /** id → 门面里的真实登记键（重名时会加「棱镜·」前缀）。 */
    private static final Map<String, String> CLOUD_KEYS = new LinkedHashMap<String, String>();
    /** 云端功能门面里的分类名，{@code CAT drop 棱镜玩机} 可整类下架。 */
    public static final String CLOUD_CATEGORY = "棱镜玩机";
    private static boolean cloudRegistered;

    static {
        // ---------------- 显示与性能 ----------------
        add("no_lock", "不锁屏", "把 screen_off_timeout 拉到极大值；关闭时恢复原值。", "全品牌", SEC_DISPLAY, SAFE, ALL);
        add("anim_scale", "系统动画速度", "animator / transition / window 三个原生倍率键，0=关、0.5=快、1=标准、2=慢。", "全品牌", SEC_DISPLAY, SAFE, ALL);
        add("refresh_rate", "全局高刷", "ColorOS 显示调度覆盖键 oplus_customize_screen_refresh_rate；改前备份四个 original_* 键。", "ColorOS", SEC_DISPLAY, CAUTION, mask(PrismProfile.Vendor.OPPO));
        add("ultra_dark", "极暗模式", "ultra_dark_mode_enabled：亮度压到系统滑条下限以下。", "ColorOS", SEC_DISPLAY, SAFE, mask(PrismProfile.Vendor.OPPO));
        add("fingerprint", "指纹动画", "枚举 SystemUI 的 fingerprint anim overlay，运行时用 RRO 切换。", "ColorOS", SEC_DISPLAY, CAUTION, mask(PrismProfile.Vendor.OPPO));
        add("frame_interp", "全局插帧", "gamecube_frame_interpolation：vivo 游戏魔盒插帧引擎，可选 120/144Hz。重启后需重设。", "vivo", SEC_DISPLAY, SAFE, mask(PrismProfile.Vendor.VIVO));
        add("direct_charge", "直驱供电", "settings_battery_charge_director：旁路电池直接给主板供电，边充边玩发热更低。", "vivo", SEC_DISPLAY, CAUTION, mask(PrismProfile.Vendor.VIVO));
        add("charging_aod", "充电 AOD", "power_engine_and_plugged_state_and_charging_aod：充电时的息屏显示。", "vivo", SEC_DISPLAY, SAFE, mask(PrismProfile.Vendor.VIVO));
        add("device_name", "手机改名", "vivo_user_define_device_name：改蓝牙/WiFi 直连显示名，无需联网验证。", "vivo", SEC_DISPLAY, SAFE, mask(PrismProfile.Vendor.VIVO));
        add("charge_anim", "彩色充电动画", "flash_charge_animation_style=10；关闭时恢复备份值。", "vivo", SEC_DISPLAY, SAFE, mask(PrismProfile.Vendor.VIVO));
        add("demo_mode", "状态栏演示模式", "AOSP DemoMode：隐藏状态栏图标 / 显示秒钟；进入后记得退出。", "全品牌", SEC_DISPLAY, SAFE, ALL);

        // ---------------- 应用管理 ----------------
        add("app_actions", "应用管理", "冻结 / 解冻 / 强制停止 / 清数据 / 预装卸载；冻结和卸载带核心包保护名单。", "全品牌", SEC_APP, CAUTION, ALL);
        add("app_network", "分应用断网", "cmd connectivity set-package-networking-enabled：netd 按包断/通网。", "ColorOS", SEC_APP, CAUTION, mask(PrismProfile.Vendor.OPPO));
        add("install_popup", "装机防弹窗", "冻结 com.oplus.appdetail（OPPO 安装器），装 APK 时不再弹商店推荐。", "ColorOS · 高风险", SEC_APP, DANGER, mask(PrismProfile.Vendor.OPPO));
        add("game_space", "游戏助手开发者选项", "直达 oplus 游戏空间的插帧/画质增强配置页。", "ColorOS", SEC_APP, SAFE, mask(PrismProfile.Vendor.OPPO));
        add("scene", "Scene 性能工具箱", "检测 com.omarea.vtools；已安装直接拉起，未安装给安装入口（不自研帧率监控）。", "全品牌", SEC_APP, SAFE, ALL);
        add("ad_guide", "广告关闭向导", "按品牌列出系统广告入口，跳转并给出逐步关闭说明；不拦截、不改系统。", "全品牌", SEC_APP, SAFE, ALL);

        // ---------------- 系统与更新 ----------------
        add("block_update", "屏蔽系统更新", "冻结本品牌 OTA 包（vivo 另清更新提醒）；恢复时重新 enable。", "半通用", SEC_SYSTEM, CAUTION, ALL);
        add("clear_update_notice", "清除更新提醒", "只把已出现的系统更新弹窗清掉（bbk_update_notice=-1），不冻结 OTA。", "vivo", SEC_SYSTEM, SAFE, mask(PrismProfile.Vendor.VIVO));
        add("chain3", "网络分链", "cmd connectivity set-chain3-enabled：ColorOS 加速/分流通道。", "ColorOS", SEC_SYSTEM, CAUTION, mask(PrismProfile.Vendor.OPPO));
        add("five_g", "5G 快捷开关", "尝试小米 5G 键族（smart_5g_enabled / user_5g_enabled）；写后校验，不支持会明确提示。", "澎湃OS", SEC_SYSTEM, SAFE, mask(PrismProfile.Vendor.XIAOMI));
        add("battery_magic", "电量魔法师", "dumpsys battery set level 伪造显示电量；reset 恢复真实读数。", "全品牌", SEC_SYSTEM, CAUTION, ALL);
        add("hide_notifications", "隐藏系统通知", "NotificationListenerService + snoozeNotification 3 年；恢复只需 100ms 重新弹出（可逆）。", "全品牌", SEC_SYSTEM, SAFE, ALL);
        add("grant_wss", "授予免 Root 写设置", "用 Shizuku 给自己 pm grant WRITE_SECURE_SETTINGS；之后掉线也能改设置键。", "全品牌", SEC_SYSTEM, SAFE, ALL);
        add("restore_all", "一键恢复全部修改", "把本 App 改过的键全部写回 original_* 备份。", "全品牌", SEC_SYSTEM, SAFE, ALL);

        // ---------------- 特色玩机 ----------------
        add("standby_bucket", "应用待机分组", "am set-standby-bucket：active / working_set / frequent / rare / restricted，控制系统冻结该应用的深度。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("appops_bg", "后台运行管控", "AppOps RUN_ANY_IN_BACKGROUND：ignore 后应用一退到后台就被系统掐停（比冻结温和）。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("force_doze", "强制深度睡眠", "dumpsys deviceidle force-idle：立刻进 Doze（屏幕熄灭后生效）；unforce 退出。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("perf_mode", "固定性能模式", "cmd power set-fixed-performance-mode-enabled：锁最高频，游戏更稳，但发热/耗电明显上升。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("force_dark", "强制深色模式", "cmd uimode night yes/no/auto：直接切系统夜间模式，不动开发者选项。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("display_size", "分辨率 / 密度", "wm size / wm density：降分辨率省电、提密度看更多内容；改错可能黑屏，带 10 秒自动回滚。", "全品牌", SEC_FEATURE, DANGER, ALL);
        add("screen_test", "屏幕体检", "纯色 / 灰阶 / 触控网格：查坏点、漏光、断触，全屏无广告。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("battery_health", "电池体检", "读取 dumpsys battery：温度、电压、健康度、充电状态；只读不改。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("net_diag", "网络诊断", "ping / DNS / TCP 443 三连测，输出原始耗时，判断是断网还是 DNS 被污染。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("component_viewer", "组件查看器", "列出 Activity / Service / Receiver 与 exported 状态，方便找广告入口。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("privacy_audit", "隐私体检", "无障碍 / 通知监听 / 设备管理员 / 悬浮窗四类高危授权的持有者；第三方标红，一键跳转撤销。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("app_perms", "权限审计", "解析 dumpsys 的运行时权限清单，谁在偷偷用相机/麦克风/定位一眼看清，可逐条 pm revoke。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("bg_cleaner", "后台清道夫", "am kill-all + pm trim-caches：结束退后台进程并回收缓存，清理前后可用内存对比。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("battery_cycle", "电池循环与损耗", "读 cycle_count / charge_full / charge_full_design，估算电池健康度；只读不改。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("refresh_lock", "刷新率锁定", "AOSP min/peak_refresh_rate：把屏幕钉在 60/90/120Hz，省电或满帧；带备份、回读与一键恢复。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("wifi_adb", "无线调试开关", "global adb_wifi_enabled 开关 + 当前 adb tcp 端口；Android 11+ 免数据线连电脑。", "全品牌", SEC_FEATURE, CAUTION, ALL);
        add("hw_profile", "硬件档案", "SoC / CPU 核心 / 内存 / 存储 / 屏幕分辨率与密度 / 刷新率，一页看完。", "全品牌", SEC_FEATURE, SAFE, ALL);
        add("lock_screen", "一键锁屏", "input keyevent 26 立刻息屏，单手也能锁（需要 Shizuku）。", "全品牌", SEC_FEATURE, SAFE, ALL);

        // ---------------- 快捷入口 ----------------
        add("settings_jumps", "系统设置直达", "通知管理 / 省电 / 使用情况 / 应用内存 / 开发者选项 / 移动网络 / 设备控制器等隐藏页。", "全品牌", SEC_SHORTCUT, SAFE, ALL);
        add("wallpaper_store", "壁纸商店直达", "按品牌打开主题/壁纸商店；没有专属商店时退回系统壁纸设置。", "半通用", SEC_SHORTCUT, SAFE, ALL);
        add("dial_codes", "拨号暗码", "预填工程暗码到拨号盘（4636 / 7594 / 64663 / 232331 等），部分 ROM 会屏蔽。", "半通用", SEC_SHORTCUT, SAFE, ALL);
        add("shizuku_help", "通道与授权说明", "Shizuku 激活、adb 授权写设置、无线调试的完整步骤。", "全品牌", SEC_SHORTCUT, SAFE, ALL);
    }

    private static void add(String id, String title, String desc, String tag,
                            int section, int risk, int mask) {
        Feature f = new Feature(id, title, desc, tag, section, risk, mask);
        ALL_FEATURES.add(f);
        BY_ID.put(id, f);
    }

    /**
     * 把整张表登记进云端功能门面（{@link FeatureGate}）。
     *
     * <p>登记之后云端就能用与首页条目完全一致的写法控制本页，不必再记 {@code prism_<id>} 这种私有键：
     * <pre>
     * FEAT id=不锁屏 hide override=builtin                       入口直接不渲染
     * FEAT id=不锁屏 disable notice=维护中 override=builtin        入口保留但置灰，点按显示 notice
     * FEAT id=不锁屏 label=常亮屏 desc=… override=builtin          改标题/说明
     * FEAT id=不锁屏 pin order=1 override=builtin                 置顶到「常用」并在分组内排第一
     * CAT  id=棱镜玩机 drop                                       整页功能下架
     * </pre>
     * 键用中文标题（与首页一致），同时把功能 id 登记成别名，写 {@code id=no_lock} 也能命中。
     *
     * <p>必须在 {@code CloudCatalog.init} 之前调用，否则解析器面对空清单会把每条 id= 当成笔误。
     */
    public static synchronized void registerCloud() {
        if (cloudRegistered) return;
        cloudRegistered = true;
        try {
            FeatureGate.registerCategory(CLOUD_CATEGORY);
            List<String> taken = new ArrayList<String>(FeatureGate.keySpace().keys());
            for (Feature f : ALL_FEATURES) {
                String title = f.title;
                String key = FeatureCatalog.norm(title);
                if (taken.contains(key)) {
                    // 与首页/工具条目重名：加前缀另起一个键，绝不覆盖别人的登记。
                    title = "棱镜·" + f.title;
                    key = FeatureCatalog.norm(title);
                }
                FeatureGate.register(title, FeatureGate.KIND_FREE, CLOUD_CATEGORY,
                        sectionName(f.section), f.tag, "", f.id, tight(f.title));
                taken.add(key);
                CLOUD_KEYS.put(f.id, key);
            }
        } catch (Throwable ignored) {
            // 云控坏了不能把玩机页一起带走：登记失败就退回纯 FLAG 控制
        }
    }

    /** 功能 id 在门面里的登记键；未知 id 原样返回。 */
    public static String cloudKey(String id) {
        String k = CLOUD_KEYS.get(id);
        if (k != null) return k;
        Feature f = BY_ID.get(id);
        return f == null ? id : FeatureCatalog.norm(f.title);
    }

    public static Feature byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    private static String tight(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\u3000", "");
    }

    public static List<Feature> all() {
        return Collections.unmodifiableList(ALL_FEATURES);
    }

    public static List<Feature> forVendor(PrismProfile.Vendor v) {
        List<Feature> out = new ArrayList<Feature>();
        for (Feature f : ALL_FEATURES) if (f.available(v)) out.add(f);
        return out;
    }

    /** 云控按键 id 关功能时要知道它属于哪一档；未知 id 当 SAFE。 */
    public static int riskOf(String id) {
        for (Feature f : ALL_FEATURES) if (f.id.equals(id)) return f.risk;
        return SAFE;
    }

    public static boolean exists(String id) {
        for (Feature f : ALL_FEATURES) if (f.id.equals(id)) return true;
        return false;
    }

    public static String sectionName(int section) {
        switch (section) {
            case SEC_DISPLAY: return "显示与性能";
            case SEC_APP: return "应用管理";
            case SEC_SYSTEM: return "系统与更新";
            case SEC_FEATURE: return "特色玩机";
            case SEC_SHORTCUT: return "快捷入口";
            default: return section < 0 ? "常用" : "其他";
        }
    }

    /** 本品牌 OTA 包名（屏蔽更新用）。 */
    public static String[] otaPackages(PrismProfile.Vendor v) {
        switch (v) {
            case XIAOMI:
                return new String[]{"com.android.updater"};
            case VIVO:
                return new String[]{"com.vivo.ota", "com.iqoo.ota"};
            case OPPO:
                return new String[]{"com.oplus.ota", "com.oneplus.oppoota", "com.realme.ota"};
            default:
                return new String[]{"com.android.updater"};
        }
    }

    /** 更新提醒的 settings 键（只有 vivo 有实证键）。 */
    public static String updateNoticeKey(PrismProfile.Vendor v) {
        return v == PrismProfile.Vendor.VIVO ? "bbk_update_notice" : null;
    }

    // ---------------------------------------------------------------- 广告向导

    public static final class Guide {
        public final String label;
        public final String pkg;
        /** 可空；空则用启动 Intent / monkey 打开。 */
        public final String component;
        public final String steps;

        Guide(String label, String pkg, String component, String steps) {
            this.label = label;
            this.pkg = pkg;
            this.component = component;
            this.steps = steps;
        }
    }

    public static List<Guide> adGuides(PrismProfile.Vendor v) {
        List<Guide> l = new ArrayList<Guide>();
        switch (v) {
            case OPPO:
                l.add(new Guide("系统设置", "com.android.settings",
                        "com.android.settings/com.android.settings.Settings",
                        "密码与安全 → 系统安全 → 关闭「安全应用推荐」\n隐私 → 广告与隐私 → 关闭广告跟踪\n增强服务 → 关闭商业服务 / 乐滑锁屏 / 开机精选"));
                l.add(new Guide("负一屏", "com.android.launcher", null,
                        "左滑进负一屏 → 右上角头像 → 设置 → 自定义布局\n关闭「推荐服务」「热点资讯」"));
                l.add(new Guide("全局搜索", "com.heytap.quicksearchbox", null,
                        "搜索首页 → 卡片管理 → 全部关闭\n关闭搜索框下方「提示词 / 热榜」"));
                l.add(new Guide("浏览器", "com.heytap.browser", null,
                        "我的 → 设置 → 主页设置 → 页面布局选「简洁」\n关闭「热点资讯」「个性化推荐」"));
                l.add(new Guide("信息", "com.android.mms", null,
                        "设置 → 关闭「智能信息服务」「WAP PUSH」\n关闭「服务号 / 推荐消息」"));
                l.add(new Guide("天气", "com.coloros.weather2", null,
                        "右上角菜单 → 设置 → 关闭「热门推荐」「个性化推荐」"));
                l.add(new Guide("日历", "com.coloros.calendar", null,
                        "我的 → 设置 → 关闭「内容推广」「个性化资讯」"));
                l.add(new Guide("手机管家", "com.oplus.phonemanager", null,
                        "设置 → 关闭「推送」「推荐内容」相关项"));
                break;
            case VIVO:
                l.add(new Guide("系统设置", "com.android.settings", null,
                        "设置 → 安全 → 关闭「安全应用推荐 / 广告」\n设置 → 隐私 → 关闭「广告与隐私」个性化推荐"));
                l.add(new Guide("应用商店", "com.bbk.appstore", null,
                        "我的 → 设置 → 关闭「推荐」「热门内容」「消息提醒」"));
                l.add(new Guide("浏览器", "com.vivo.browser", null,
                        "我的 → 设置 → 主页设置 → 简洁\n关闭「热点资讯」「个性化推荐」"));
                l.add(new Guide("信息", "com.android.mms", null,
                        "设置 → 关闭「智能信息服务」「服务号」"));
                l.add(new Guide("日历 / 天气", "com.bbk.calendar", null,
                        "各自 我的 → 设置 → 关闭「内容推荐 / 热点」"));
                l.add(new Guide("负一屏", "com.bbk.launcher2", null,
                        "左滑 → 头像 → 设置 → 关闭「推荐服务」「热点资讯」"));
                break;
            case XIAOMI:
                l.add(new Guide("系统设置", "com.android.settings", null,
                        "设置 → 隐私保护 → 特殊权限 → 关闭「个性化广告」\n设置 → 密码与安全 → 系统安全 → 关闭「安全应用推荐」"));
                l.add(new Guide("应用商店", "com.xiaomi.market", null,
                        "我的 → 设置 → 关闭「推荐」「热门内容」「消息提醒」"));
                l.add(new Guide("音乐 / 视频", "com.miui.player", null,
                        "我的 → 设置 → 关闭「推荐」「热榜」「消息推送」\n视频同路径"));
                l.add(new Guide("主题壁纸", "com.android.thememanager", null,
                        "我的 → 设置 → 关闭「个性化推荐」"));
                l.add(new Guide("安全中心", "com.miui.securitycenter", null,
                        "设置 → 关闭「推荐内容 / 热点资讯」"));
                l.add(new Guide("浏览器", "com.android.browser", null,
                        "我的 → 设置 → 主页 → 简洁；关闭「热点资讯」"));
                break;
            default:
                l.add(new Guide("系统设置", "com.android.settings", null,
                        "设置 → 隐私 → 广告 → 关闭广告个性化\n应用商店/浏览器各自设置里关闭「推荐内容」"));
                break;
        }
        return l;
    }

    // ---------------------------------------------------------------- 设置直达页

    public static final class Jump {
        public final String label;
        /** 可空：空则用下面的 action 走 Intent。 */
        public final String component;
        /** 可空：Settings.ACTION_* 这类系统 Action，无需 shell。 */
        public final String action;

        Jump(String label, String component) {
            this(label, component, null);
        }

        Jump(String label, String component, String action) {
            this.label = label;
            this.component = component;
            this.action = action;
        }
    }

    public static List<Jump> settingsJumps(PrismProfile.Vendor v) {
        List<Jump> l = new ArrayList<Jump>();
        l.add(new Jump("通知管理", "com.android.settings/.Settings$ConfigureNotificationSettingsActivity"));
        l.add(new Jump("省电模式", "com.android.settings/.Settings$BatterySaverSettingsActivity"));
        l.add(new Jump("使用情况统计", "com.android.settings/.Settings$UsageStatsActivity"));
        l.add(new Jump("应用内存", "com.android.settings/.Settings$AppMemoryUsageActivity"));
        l.add(new Jump("移动网络（视频彩铃）", "com.android.settings/.Settings$MobileNetworkActivity"));
        l.add(new Jump("开发者选项", "com.android.settings/.Settings$DevelopmentSettingsDashboardActivity"));
        l.add(new Jump("系统设置主页", "com.android.settings/.Settings"));
        l.add(new Jump("显示 / 护眼设置", "com.android.settings/.Settings$DisplaySettingsActivity"));
        // Android 11+ 原生「设备控制」：米家等 Home App 注册的设备会出现在这里
        l.add(new Jump("设备控制器", null, "android.settings.DEVICE_CONTROL_SETTINGS"));
        // 无线调试是 Shizuku / ADB 通道的前置开关
        l.add(new Jump("无线调试（开发者选项）", null, "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        if (v == PrismProfile.Vendor.VIVO) {
            l.add(new Jump("vivo 子设置", "com.vivo.settings/.VivoSubSettings"));
        }
        return l;
    }

    /** 壁纸/主题商店候选包（按顺序取第一个安装的）。 */
    public static String[] wallpaperPackages(PrismProfile.Vendor v) {
        switch (v) {
            case XIAOMI:
                return new String[]{"com.android.thememanager", "com.miui.thememanager"};
            case VIVO:
                return new String[]{"com.vivo.wallpaper", "com.bbk.theme", "com.bbk.appstore"};
            case OPPO:
                return new String[]{"com.heytap.themestore", "com.nearme.themestore", "com.oplus.themestore"};
            default:
                return new String[0];
        }
    }

    public static final class DialCode {
        public final String label;
        public final String code;

        DialCode(String label, String code) {
            this.label = label;
            this.code = code;
        }
    }

    public static List<DialCode> dialCodes() {
        List<DialCode> l = new ArrayList<DialCode>();
        l.add(new DialCode("手机信息 / 网络类型（通用）", "*#*#4636#*#*"));
        l.add(new DialCode("关机菜单（通用）", "*#*#7594#*#*"));
        l.add(new DialCode("小米硬件检测", "*#*#64663#*#*"));
        l.add(new DialCode("OPPO 工程模式", "*#*#232331#*#*"));
        l.add(new DialCode("vivo 工程测试", "*#*#6484#*#*"));
        l.add(new DialCode("GTalk 服务信息", "*#*#8255#*#*"));
        return l;
    }

    /** 冻结/卸载/清数据的核心包保护名单（防变砖）。 */
    public static final String[] PROTECTED = {
            "com.android.systemui", "com.android.phone", "com.android.dialer",
            "com.android.mms", "com.android.settings", "com.android.launcher",
            "com.android.launcher3", "com.google.android.gsf", "com.google.android.gms",
            "android", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.home", "com.miui.securitycenter", "com.bbk.launcher2",
            "com.oppo.launcher", "com.coloros.launcher", "com.heytap.launcher",
            "com.oplus.appdetail", "com.vivo.ota", "com.oplus.ota", "com.android.updater"
    };

    public static boolean protectedPackage(String pkg) {
        for (String p : PROTECTED) {
            if (p.equalsIgnoreCase(pkg)) return true;
        }
        return false;
    }

    public static boolean isOwnPackage(String pkg, String self) {
        return pkg != null && pkg.equalsIgnoreCase(self);
    }
}
