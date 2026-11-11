package com.zeus.guardlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GuardLink 核心算法层（纯 Java，零 Android 依赖，PC 可编译实测）
 *
 * 职责：
 *   1. NoHumanDetector  —— "设备一定不是用户本人操作"判定状态机（触发缓冲 + 触摸撤防）
 *   2. SuspectEngine    —— 四步溯源筛查：包名初筛 → 无障碍二筛 → 流量三筛 → 行为终筛，证据加权推举
 *   3. GuardController  —— 编排：命中 → 断网断流量 → 采集证据包 → 推举嫌疑 → 用户确认后卸载 → 恢复网络
 *
 * 与 Android 的解耦：所有特权动作通过 GuardActions 接口注入。
 * Android 侧实现见 GuardLinkAndroid.java（Shizuku exec / svc / pm uninstall / TrafficStats）。
 * 仿真侧实现见本文件 MockActions。
 *
 * 实测：javac -d out GuardLinkCore.java && java -cp out com.zeus.guardlink.GuardLinkCore
 */
public final class GuardLinkCore {

    private GuardLinkCore() {}

    // =========================================================================
    // 事件模型：无障碍服务/广播/TrafficStats 采集到的原始信号归一化后喂给状态机
    // =========================================================================
    public static final class InteractionEvent {
        public enum Type {
            HUMAN_TOUCH,      // TYPE_TOUCH_INTERACTION_START / TYPE_VIEW_CLICKED 等 —— 有人在碰屏幕
            UI_ACTIVITY,      // TYPE_WINDOW_STATE_CHANGED / 应用自弹窗、自启动界面 —— 界面自己在动
            SCREEN_ON,        // 熄屏→亮屏
            SCREEN_OFF,
            USER_PRESENT,     // 解锁
            APP_RESUMED,      // 前台应用切换（UsageStats ACTIVITY_RESUMED）
            TRAFFIC_DELTA     // 某包流量增量事件（携带 byteDelta）
        }
        public final long ts;          // 事件时间戳（任意单调时钟，demo 用 ms）
        public final Type type;
        public final String pkg;       // 事件归属包名（UI_ACTIVITY/APP_RESUMED 必填）
        public final long byteDelta;   // 仅 TRAFFIC_DELTA
        public final boolean confirmed; // 高置信信号（UsageStats 由系统服务记录，第三方无法伪造）；
                                        // 无障碍事件为低置信（包名可被部分手法混淆），仅作观察

        public InteractionEvent(long ts, Type type, String pkg, long byteDelta, boolean confirmed) {
            this.ts = ts; this.type = type; this.pkg = pkg; this.byteDelta = byteDelta; this.confirmed = confirmed;
        }
        public InteractionEvent(long ts, Type type, String pkg, long byteDelta) { this(ts, type, pkg, byteDelta, false); }
        public InteractionEvent(long ts, Type type, String pkg) { this(ts, type, pkg, 0, false); }
    }

    // =========================================================================
    // 非人类操控判定状态机
    //
    // 核心思想：无障碍服务只收"触摸交互时序"（TYPE_TOUCH_INTERACTION_*，不含屏幕内容，
    // 隐私友好），界面活动事件只看"谁在动"，不看内容。
    //
    // 判定规则（窗口 W = 6s）：
    //   C1 冷启动型：SCREEN_ON / USER_PRESENT 到达，且触发前 W 内零 HUMAN_TOUCH
    //   C2 静默运行型：UI_ACTIVITY 到达，且触发前 W 内零 HUMAN_TOUCH
    //
    // 确定性保障（"一定不是本人"的四重防线）：
    //   ① 触发缓冲：命中后先进 PENDING（2.5s），期间收到 HUMAN_TOUCH → 撤防（防阅读停顿误报）
    //   ② 媒体豁免：音频会话活跃期间（看视频/听歌），媒体主人 app 自身的界面活动豁免；
    //      但其他包顶进前台照常触发 —— "视频看一半被劫持"正是必抓场景
    //   ③ 持续作案实锤：缓冲期内恶意 app 再弹界面 → 立即实锤，跳过等待
    //   ④ 白名单：自身包、SystemUI、输入法 的常规事件不作为 UI_ACTIVITY 证据（严格 equals，
    //      防 "com.android.systemui.tools" 之类 prefix 伪装）
    // =========================================================================
    public static final class NoHumanDetector {

        // =====================================================================
        // 【本 App 的最小补丁 —— 全文只有这一类改动】
        //
        //   上游 v3.2 里 21 个判定/推举参数是 `static final` 常量，界面没法「直观设置」。
        //   本 App 需要把每个参数在界面上看得见、改得动，所以下面把这些字段改成
        //   `public static volatile`，出厂值就是原常量字面值，一个数字都没动。
        //   除「去掉 final、加 volatile、加注释」之外，本文件与上游 v3.2 逐字节一致；
        //   运行期由 com.mineways.guardlink.GuardParams 按「本机持久化 + 云控下发」写入，
        //   取值范围与恢复出厂也归它管。改动点清单见 PROJECT_NOTES §8.3.146。
        //   拿新 zip 整包覆盖后，需要按那份清单重放本补丁。
        // =====================================================================
        public static volatile long HUMAN_IDLE_WINDOW_MS   = 6_000;   // W：人类触摸空闲窗口
        public static volatile long PENDING_CONFIRM_MS     = 2_500;   // 触发缓冲时长
        public static volatile int  PENDING_ACTIVITY_BURST = 2;       // 缓冲期内再收到 N 次 UI_ACTIVITY 即实锤
        public static volatile long WAKE_ACTIVITY_WINDOW_MS = 10_000; // 亮屏/解锁后窗口活动计入 C1 的时间窗
        /**
         * 真人驻留期（§8.3.165）：一次真人触摸/解锁后这么久内，都视为「人就在手机前」。
         *
         * <p>核心判据是「非真人操控」——真人正刷着抖音/看 B 站/回微信的时候，恶意软件
         * 当着人的面远程操控既不合理也立刻会被看见。这段驻留期内界面怎么变都不进缓冲期、
         * 不自动判炸、不断网；只有人离开（超过驻留期没碰手机）才恢复自动值守。
         */
        public static volatile long HUMAN_PRESENCE_MS      = 300_000; // 5 分钟

        /** 白名单：系统组件的自动行为（通知栏、键盘弹出）不算"作案"证据 */
        private static final Set<String> WHITELIST = new LinkedHashSet<>(Arrays.asList(
                "com.zeus.guardlink",            // 本守护应用自身
                "com.android.systemui",          // 系统界面
                "com.android.inputmethod",       // 输入法族（前缀匹配）
                "com.google.android.inputmethod"
        ));

        public enum State { IDLE, PENDING, FIRED }

        /** 触发结果：原因 + 全量证据 */
        public static final class TriggerResult {
            public final String reason;               // "C1 冷启动型" / "C2 静默运行型" / "缓冲期内持续作案"
            public final long idleMs;                 // 触发时刻距最近一次人类触摸
            public final List<String> activePackages; // 触发窗口内活动的非白名单包名
            public final long pendingConfirmedAt;     // 实锤确认时刻
            TriggerResult(String reason, long idleMs, List<String> pkgs, long at) {
                this.reason = reason; this.idleMs = idleMs; this.activePackages = pkgs; this.pendingConfirmedAt = at;
            }
        }

        public interface Listener { void onNoHuman(TriggerResult result); }

        private final Listener listener;
        private State state = State.IDLE;
        private long lastHumanTouchAt = Long.MIN_VALUE;
        private long pendingFrom = 0;
        private String pendingReason = "";
        private final List<String> pendingActivityPkgs = new ArrayList<>();
        private int pendingActivityCount = 0;

        // ---- 媒体豁免（看长视频/听音乐场景） ----
        // 由 Android 层喂：AudioManager.isMusicActive() false→true 翻转瞬间，
        // 用 UsageStats 抓"当时的前台包"记为 mediaOwner（音频主人归属，第三方难伪造）。
        private volatile boolean mediaActive = false;
        private volatile String mediaOwnerPkg = null;
        // 系统音频服务里正在播放的包集合：小窗/后台播放时前台归属会错，按这个集合直接豁免
        private volatile Set<String> mediaPackages = java.util.Collections.emptySet();
        /** 最近一次「人就在手机前」的时刻（真人触摸 / 解锁 / 媒体在放，见 HUMAN_PRESENCE_MS）。 */
        private volatile long lastHumanPresenceAt = Long.MIN_VALUE;

        public NoHumanDetector(Listener listener) { this.listener = listener; }

        /** 音频会话活跃状态 + 音频主人包名（开始播放瞬间的前台包快照；拿不到传 null 则不豁免） */
        public void setMediaActive(boolean active, String ownerPkg) {
            this.mediaActive = active;
            this.mediaOwnerPkg = active ? ownerPkg : null;
            if (!active) this.mediaPackages = java.util.Collections.emptySet();
            if (active && state == State.PENDING) {
                // 正在缓冲期时视频开始播放：人在看视频，不是远程操控，撤防并留痕
                mediaSuppressed++;
                reset();
            }
        }

        /** 音频会话活跃状态 + 音频主人 + 系统音频服务里正在播放的包集合（小窗/后台播放用）。 */
        public void setMediaActive(boolean active, String ownerPkg, Set<String> playingPkgs) {
            setMediaActive(active, ownerPkg);
            if (active && playingPkgs != null) {
                this.mediaPackages = playingPkgs;
            }
        }

        public boolean isMediaActive() { return mediaActive; }

        /**
         * 真人现在在不在手机前。触摸/解锁由状态机自己记；媒体播放由 Android 层持续
         * {@link #markHumanPresence(long)} 续期（视频停了也还有 HUMAN_PRESENCE_MS 的缓冲）。
         */
        public boolean isHumanPresent(long ts) {
            return lastHumanPresenceAt != Long.MIN_VALUE
                    && (ts - lastHumanPresenceAt) < HUMAN_PRESENCE_MS;
        }

        /** Android 层续期「人在手机前」：媒体播放期间每拍调用一次；停止后仍保留整段驻留期。 */
        public void markHumanPresence(long ts) {
            this.lastHumanPresenceAt = ts;
        }

        /** 媒体播放期间被拦下的自动判炸次数（只做日志/观测，不参与判定）。 */
        public volatile int mediaSuppressed;
        /** 真人在场期间被拦下的自动判炸次数（只做日志/观测，不参与判定）。 */
        public volatile int humanSuppressed;

        public State getState() { return state; }

        public void feed(InteractionEvent e) {
            switch (e.type) {
                case HUMAN_TOUCH:
                    lastHumanTouchAt = e.ts;
                    lastHumanPresenceAt = e.ts;              // 真人碰了屏幕 = 人就在手机前
                    if (state == State.PENDING) {            // ① 触摸撤防：是活人在操作，撤销判定
                        reset();
                    }
                    break;

                case SCREEN_ON:
                case USER_PRESENT: {
                    // C1 改双条件：亮屏/解锁本身不触发（抬手亮屏/双击亮屏无触摸属正常用户行为），
                    // 只记录时刻；亮屏后 W 内零触摸 + 窗口自行活动才复合触发（见 UI_ACTIVITY 分支）
                    lastWakeSignalAt = e.ts;
                    // 解锁一定是人做的（§8.3.165）：真人刚解锁，后面几秒的界面变化不判无人操控
                    if (e.type == InteractionEvent.Type.USER_PRESENT) {
                        lastHumanPresenceAt = e.ts;
                        if (state == State.PENDING) reset();
                    }
                    break;
                }

                case SCREEN_OFF:
                    // 熄屏 = 人不在手机前：清掉驻留期，让「自己亮屏 + 自启动」的 C1 仍然可判
                    lastHumanPresenceAt = Long.MIN_VALUE;
                    if (state == State.PENDING) reset();
                    break;

                case UI_ACTIVITY:
                case APP_RESUMED: {
                    if (isWhitelisted(e.pkg)) return;
                    // ② 真人在场 / 媒体在放：核心判据是「非真人操控」——人在刷视频、看文章、
                    //    开小窗的时候，界面自己在动是正常的，一律不进缓冲期、不判炸、不断网。
                    //    只有人离开（超过 HUMAN_PRESENCE_MS 没碰手机）才恢复自动值守。
                    if (mediaActive || isHumanPresent(e.ts)) {
                        if (state == State.PENDING) {
                            if (mediaActive) mediaSuppressed++; else humanSuppressed++;
                            reset();
                        }
                        return;
                    }
                    long idle = idleSinceHumanTouch(e.ts);
                    if (state == State.IDLE) {
                        if (idle >= HUMAN_IDLE_WINDOW_MS) {   // 界面自己在动，但 6s 内没人碰过
                            // C1 复合型：亮屏/解锁后 10s 内自启动；C2 静默型：其余
                            boolean afterWake = sinceWake(e.ts) <= WAKE_ACTIVITY_WINDOW_MS;
                            String reason = afterWake
                                    ? "C1 冷启动型（无人触摸下屏幕自行点亮且应用自启动：" + e.pkg + "）"
                                    : "C2 静默运行型（无人操作下界面自行变化：" + e.pkg + "）";
                            enterPending(reason, idle, e.ts);
                        }
                    } else if (state == State.PENDING) {
                        pendingActivityCount++;               // ③ 缓冲期内还在动 → 持续作案实锤
                        if (!pendingActivityPkgs.contains(e.pkg)) pendingActivityPkgs.add(e.pkg);
                        // 高置信事件（UsageStats 系统记录，伪造不了）单次即可实锤；
                        // 低置信事件需 ≥2 次（防包名混淆的单一假窗口）
                        if (pendingActivityCount >= PENDING_ACTIVITY_BURST || e.confirmed) {
                            fire("缓冲期内持续作案（" + e.pkg + " 再次自行活动）", idleSinceHumanTouch(e.ts), e.ts);
                        }
                    }
                    break;
                }

                default:
                    // SCREEN_OFF / TRAFFIC_DELTA 不参与判定，仅记录
                    break;
            }
        }

        private void enterPending(String reason, long idleMs, long ts) {
            state = State.PENDING;
            pendingFrom = ts;
            pendingReason = reason;
            pendingActivityCount = 0;
            pendingActivityPkgs.clear();
        }

        /** 缓冲期到点仍无触摸 → 确认触发。由外部时钟在 ts >= pendingFrom + PENDING_CONFIRM_MS 时调用 */
        public void onPendingTimeout(long ts) {
            if (state != State.PENDING) return;
            // §8.3.165：到点这一拍如果真人已在手机前 / 媒体在放，直接撤防，不再走判炸
            if (mediaActive || isHumanPresent(ts)) {
                if (mediaActive) mediaSuppressed++; else humanSuppressed++;
                reset();
                return;
            }
            if (ts - pendingFrom >= PENDING_CONFIRM_MS) {
                fire(pendingReason, idleSinceHumanTouch(ts), ts);
            }
        }

        /** 距上次真人触摸多久；从没见过触摸时给 Long.MAX_VALUE（避免 Long.MIN_VALUE 相减溢出）。 */
        private long idleSinceHumanTouch(long ts) {
            return lastHumanTouchAt == Long.MIN_VALUE ? Long.MAX_VALUE : ts - lastHumanTouchAt;
        }

        /** 距上次亮屏/解锁多久；从没见过唤醒事件时给 Long.MAX_VALUE（同样避免溢出）。 */
        private long sinceWake(long ts) {
            return lastWakeSignalAt == Long.MIN_VALUE ? Long.MAX_VALUE : ts - lastWakeSignalAt;
        }

        private void fire(String reason, long idleMs, long ts) {
            // §8.3.165：最后一道闸 —— 看视频/听歌/真人刚碰过手机时，「无人操控」与「人在看」
            // 无法区分；缓冲期内再多界面变化也不自动判炸、不自动断网。
            // 只牺牲「人在场」这一小段时间的自动处置，换掉「刷抖音/小窗看 B 站被突然断网」。
            if (mediaActive || isHumanPresent(ts)) {
                if (mediaActive) mediaSuppressed++; else humanSuppressed++;
                reset();
                return;
            }
            state = State.FIRED;
            listener.onNoHuman(new TriggerResult(reason, idleMs, new ArrayList<>(pendingActivityPkgs), ts));
        }

        /** 处置完毕后复位，继续值守 */
        public void reset() { state = State.IDLE; pendingActivityPkgs.clear(); pendingActivityCount = 0; }

        /** Android 层可扩展白名单（launcher/IME/输入法等，onServiceConnected 时注入）——
         *  桌面时钟/天气 widget 自动刷新不作为 UI_ACTIVITY 证据（消除放置桌面时的周期误报） */
        public static final Set<String> EXTRA_WHITELIST = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private long lastWakeSignalAt = Long.MIN_VALUE;   // 最近亮屏/解锁时刻（C1 双条件用）

        /** 白名单严格 equals —— 防 "com.android.systemui.tools" 型 prefix 伪装（伪装包由 SuspectEngine 加分标记） */
        private static boolean isWhitelisted(String pkg) {
            if (pkg == null) return true;
            return WHITELIST.contains(pkg) || EXTRA_WHITELIST.contains(pkg);
        }
    }

    // =========================================================================
    // 应用画像 + 四步溯源筛查
    //
    // Step1 权限面初筛（全量已安装应用）
    // Step2 无障碍二筛（启用中的无障碍服务归属 = 当前具备模拟点击操控能力）
    // Step3 流量三筛（事发窗口内流量增量排行 + 连接表命中）
    // Step4 行为终筛（无人操作窗口内的界面活动/自启动归属 = 最直接实锤）
    // 合成：证据加权求和，总分排序，>=60 分推举为主要嫌疑
    // =========================================================================
    public static final class SuspectEngine {

        // ---- 权重表 ----（补丁：出厂值不变，改成可覆盖字段，见 NoHumanDetector 上方说明）
        public static volatile int W_REQUEST_INSTALL      = 30;  // 静默装包能力
        public static volatile int W_DECLARED_ACCESSIBILITY = 20; // 声明了无障碍服务
        public static volatile int W_ENABLED_ACCESSIBILITY = 40;  // 无障碍已启用（当前可操控设备）
        public static volatile int W_OVERLAY              = 15;  // 悬浮窗
        public static volatile int W_WRITE_SETTINGS       = 10;
        public static volatile int W_DEVICE_ADMIN         = 15;  // 设备管理员
        public static volatile int W_BOOT_FG              = 10;  // 开机自启 + 前台服务组合
        public static volatile int W_SMS                  = 10;  // 短信权限
        public static volatile int W_IMPERSONATE_SYSTEM   = 25;  // 伪装系统应用（系统范儿包名但非 FLAG_SYSTEM）
        public static volatile int W_TRAFFIC_TOP          = 20;  // 事发窗口流量增量前三
        public static volatile int W_TRAFFIC_ANY          = 5;
        public static volatile int W_BEHAVIOR_HIT         = 50;  // 无人窗口内界面活动归属
        // 补丁 22/23（§8.3.158，对抗样本 app.cedar.weather.app 的 TTP，样式与上方参数补丁一致）：
        //   静默删包 = 反卸载自保 + 能清掉竞品/取证应用；投屏 = MediaProjection 屏幕窃取能力。
        //   两条都出现在真实 RAT 权限面里，而旧表对它们一分不计 —— 评分盲区。
        public static volatile int W_REQUEST_DELETE       = 25;  // REQUEST_DELETE_PACKAGES（静默删包/反卸载）
        public static volatile int W_MEDIA_PROJECTION     = 25;  // FOREGROUND_SERVICE_MEDIA_PROJECTION（投屏采集）
        // 补丁 24..28（§8.3.159，真实 RAT 的第二组高价值入口）：
        //   通知监听 = 读验证码/银行推送；VPN = 流量转发与中间人；默认短信 = 直接拿验证码；
        //   无桌面入口却持危险能力 = 隐藏型应用。四条都带组合条件，单条权限不单独定罪。
        public static volatile int W_NOTIFICATION_DECLARED = 20; // 声明通知监听服务
        public static volatile int W_NOTIFICATION_ENABLED  = 25; // 通知监听已启用（正在读通知）
        public static volatile int W_VPN_SERVICE           = 20; // 声明 VPNService
        public static volatile int W_DEFAULT_SMS           = 30; // 当前是默认短信应用
        public static volatile int W_HIDDEN_APP            = 25; // 无桌面入口 + 至少一项危险能力

        public static volatile int PRIMARY_SUSPECT_THRESHOLD = 60;

        /** 应用画像（Android 侧由 PackageManager/DevicePolicyManager/TrafficStats 填充） */
        public static final class AppProfile {
            public String pkg;
            public boolean requestInstallPackages;
            public boolean declaredAccessibilityService;  // manifest 声明 BIND_ACCESSIBILITY_SERVICE
            public boolean enabledAccessibilityService;   // 已在系统设置中启用
            public boolean overlay;
            public boolean writeSettings;
            public boolean deviceAdmin;
            public boolean bootCompletedWithForeground;
            public boolean sms;
            public boolean requestDeletePackages;          // REQUEST_DELETE_PACKAGES（补丁 22）
            public boolean mediaProjection;                // FOREGROUND_SERVICE_MEDIA_PROJECTION（补丁 23）
            public boolean notificationListener;           // 声明 BIND_NOTIFICATION_LISTENER_SERVICE（补丁 24）
            public boolean notificationListenerEnabled;    // 通知监听已在系统里启用（补丁 25）
            public boolean vpnService;                     // 声明 BIND_VPN_SERVICE（补丁 26）
            public boolean defaultSmsApp;                  // 当前默认短信应用（补丁 27）
            public boolean noLauncherEntry;                // 无桌面入口（补丁 28，组合条件）
            // §8.3.160 云控特征：云端新增的动态特征累加分 / 文案，以及云端要停用的内置项 id。
            // id 用字符串（"dy_install" 等），避免内核反向依赖 com.mineways.guardlink 的常量类。
            public int cloudScore;
            public final List<String> cloudDetails = new ArrayList<>();
            public final Set<String> suppressed = new LinkedHashSet<>();
            public boolean isRealSystem;                  // FLAG_SYSTEM 真系统包（Android 层填，反伪装检测用）
            public long   trafficDelta;                   // 事发窗口流量增量（rx+tx, bytes）
            public int    trafficRank;                    // 增量排行（1 起，0 = 无流量）
            public boolean behaviorHit;                   // 无人窗口内有界面活动/自启动行为
            public List<String> behaviorDetails = new ArrayList<>();
        }

        /** 嫌疑人：分数 + 分步证据链 */
        public static final class Suspect {
            public final AppProfile profile;
            public int score;
            public final List<String> evidence = new ArrayList<>();
            public int stepHitMask;   // bit0..3 = Step1..4 命中
            /** 是否有「实锤」证据：行为命中 / 无障碍已启用 / 伪装系统包 / 隐藏入口组合。 */
            public boolean strongEvidence;
            Suspect(AppProfile p) { this.profile = p; }
            public boolean isPrimary() {
                // 权限面只是能力，不等于正在作恶：正常 App 天生就占着装包、悬浮窗、投屏、通知监听，
                // 单靠能力累加会把酷狗、夸克、系统组件这类正常软件推举成主要嫌疑。
                // 所以过线之外，还必须有一条能证明「它正在动」的实锤，才允许点名。
                return score >= PRIMARY_SUSPECT_THRESHOLD && strongEvidence;
            }
            public String report() {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("[%3d 分]%s %s%n", score, isPrimary() ? "◀主要嫌疑" : "", profile.pkg));
                for (String ev : evidence) sb.append("      └ ").append(ev).append('\n');
                return sb.toString();
            }
        }

        public static final class Verdict {
            public final List<Suspect> ranked;
            public final Suspect primary;   // 推举出的主要嫌疑（可为 null）
            Verdict(List<Suspect> ranked) {
                this.ranked = ranked;
                // 不能只看第一名：排在前面的可能是一台权限面很肥但没有实锤的正常软件，
                // 真正的作案者排在后面。取「第一个满足主要嫌疑条件」的。
                Suspect found = null;
                for (Suspect s : ranked) {
                    if (s.isPrimary()) {
                        found = s;
                        break;
                    }
                }
                this.primary = found;
            }
        }

        /** 云端是否停用了这条内置动态特征（id 见 com.mineways.guardlink.GuardFeatureSet.DY_*）。 */
        private static boolean on(AppProfile p, String id) {
            return p == null || p.suppressed == null || !p.suppressed.contains(id);
        }

        /** 四步筛查 + 证据加权合成 */
        public static Verdict investigate(List<AppProfile> profiles) {
            List<Suspect> out = new ArrayList<>();
            for (AppProfile p : profiles) {
                int score = 0, mask = 0;

                // ---- Step1 权限面初筛 ----
                Suspect s = new Suspect(p);
                out.add(s);
                StringBuilder s1 = new StringBuilder();
                if (on(p, "dy_install") && p.requestInstallPackages)       { score += W_REQUEST_INSTALL; mask |= 1; s1.append("可请求安装应用 "); }
                if (on(p, "dy_a11y") && p.declaredAccessibilityService) { score += W_DECLARED_ACCESSIBILITY; mask |= 1; s1.append("声明无障碍 "); }
                if (on(p, "dy_overlay") && p.overlay)                      { score += W_OVERLAY; mask |= 1; s1.append("悬浮窗 "); }
                if (on(p, "dy_write_settings") && p.writeSettings)                { score += W_WRITE_SETTINGS; mask |= 1; s1.append("改系统设置 "); }
                if (on(p, "dy_admin") && p.deviceAdmin)                  { score += W_DEVICE_ADMIN; mask |= 1; s1.append("设备管理员 "); }
                if (on(p, "dy_boot_fg") && p.bootCompletedWithForeground)  { score += W_BOOT_FG; mask |= 1; s1.append("开机自启+前台服务 "); }
                if (on(p, "dy_sms") && p.sms)                          { score += W_SMS; mask |= 1; s1.append("短信权限 "); }
                if (on(p, "dy_delete") && p.requestDeletePackages)        { score += W_REQUEST_DELETE; mask |= 1; s1.append("可请求卸载应用 "); }
                if (on(p, "dy_projection") && p.mediaProjection)              { score += W_MEDIA_PROJECTION; mask |= 1; s1.append("投屏服务 "); }
                if (on(p, "dy_notify") && p.notificationListener)         { score += W_NOTIFICATION_DECLARED; mask |= 1; s1.append("通知监听 "); }
                if (on(p, "dy_notify_enabled") && p.notificationListenerEnabled)  { score += W_NOTIFICATION_ENABLED; mask |= 1; s1.append("通知监听已启用 "); }
                if (on(p, "dy_vpn") && p.vpnService)                   { score += W_VPN_SERVICE; mask |= 1; s1.append("VPN 服务 "); }
                if (on(p, "dy_default_sms") && p.defaultSmsApp)                { score += W_DEFAULT_SMS; mask |= 1; s1.append("默认短信 "); }
                // 隐藏型应用：没有桌面图标本身不是罪，但同时持危险能力就是另一回事了。
                // 只认「无入口 + 至少一项」，避免把插件/主题/配套服务这类正常无入口包误伤。
                if (on(p, "dy_hidden") && p.noLauncherEntry && (p.requestInstallPackages
                        || p.declaredAccessibilityService || p.notificationListener || p.overlay
                        || p.vpnService || p.requestDeletePackages)) {
                    score += W_HIDDEN_APP; mask |= 1; s1.append("隐藏桌面入口 ");
                    s.strongEvidence = true;
                }
                // 云控新增的动态特征（匹配在当前画像上算出的分）与对应文案
                if (p.cloudScore > 0) {
                    score += p.cloudScore; mask |= 1;
                }
                if (!p.cloudDetails.isEmpty()) {
                    mask |= 1;
                    s1.append("云端特征×").append(p.cloudDetails.size()).append(' ');
                    s.evidence.add("云控特征： " + String.join("；", p.cloudDetails));
                }
                // 反隐藏：起"系统范儿"包名但不是真系统包 → 伪装系统应用加分
                if (!p.isRealSystem && (p.pkg.startsWith("com.android.")
                        || p.pkg.startsWith("com.google.android."))) {
                    score += W_IMPERSONATE_SYSTEM; mask |= 1; s1.append("伪装系统应用(").append(p.pkg).append(") ");
                    s.strongEvidence = true;
                }
                if (s1.length() > 0) s.evidence.add("Step1 权限面： " + s1.toString().trim());

                // ---- Step2 无障碍二筛 ----
                if (p.enabledAccessibilityService) {
                    score += W_ENABLED_ACCESSIBILITY; mask |= 2;
                    s.strongEvidence = true;
                    s.evidence.add("Step2 无障碍： 无障碍服务已启用（具备模拟点击操控能力）");
                }

                // ---- Step3 流量三筛 ----
                if (p.trafficRank > 0 && p.trafficDelta > 0) {
                    if (p.trafficRank <= 3) { score += W_TRAFFIC_TOP; mask |= 4; }
                    else score += W_TRAFFIC_ANY;
                    s.evidence.add(String.format("Step3 流量： 事发窗口增量 %d bytes（第 %d 名）", p.trafficDelta, p.trafficRank));
                }

                // ---- Step4 行为终筛 ----
                if (p.behaviorHit) {
                    score += W_BEHAVIOR_HIT; mask |= 8;
                    s.strongEvidence = true;
                    s.evidence.add("Step4 行为： 无人操作窗口内的界面活动/自启动 —— " + String.join("；", p.behaviorDetails));
                }

                s.score = score; s.stepHitMask = mask;
            }
            out.sort((a, b) -> b.score - a.score);
            return new Verdict(out);
        }
    }

    // =========================================================================
    // 特权动作接口：PC 仿真用 MockActions，Android 用 GuardLinkAndroid.ShizukuActions
    // =========================================================================
    public interface GuardActions {
        /** 断网断流量：Android 实现 = Shizuku exec "svc wifi disable" + "svc data disable" */
        void cutNetwork();

        /** 恢复网络 */
        void restoreNetwork();

        /** 采集证据包（流量差分/TCP 连接表/事件流落盘） */
        void collectEvidence(NoHumanDetector.TriggerResult t);

        /** 四步筛查数据源（Android 实现：PackageManager 遍历 + 无障碍设置解析 + TrafficStats 差分） */
        List<SuspectEngine.AppProfile> collectProfiles(NoHumanDetector.TriggerResult t);

        /** 卸载：Android 实现 = Shizuku exec "pm uninstall --user 0 <pkg>"，返回是否成功 */
        boolean uninstall(String pkg);

        /** 踢回系统主界面：Android 实现 = 无障碍 performGlobalAction(GLOBAL_ACTION_HOME)，不依赖 Shizuku */
        void kickHome();

        /** 冻结进程：Android 实现 = Shizuku exec "am force-stop <pkg>" —— 涉嫌操控的包直接冻结，
         *  底层调用（IPC/操纵微信发消息等）随进程死亡全部失效 */
        void forceStop(String pkg);

        /** 静默掐掉不可信（非大厂/知名/系统自带）的无障碍服务——防 dispatchGesture 假触摸骗确认窗。
         *  必须无感执行：不弹通知、不响铃。实现需先快照被掐者，解除时 restoreA11y() 原样还原 */
        void suspendUntrustedA11y();

        /** 原样还原被掐的无障碍服务（快照合并回写） */
        void restoreA11y();

        /** 按应用精确断网（iptables owner match）：Android 实现 = Shizuku iptables/ip6tables DROP 该 uid。
         *  返回 false = 设备不支持（规则写入失败），调用方降级全局断网 */
        boolean cutAppNet(String pkg);

        /** 解除按应用断网（卸载后清理规则） */
        void restoreAppNet(String pkg);
    }

    // =========================================================================
    // 人工确认鉴定：区分"真人手指"与"程序点击"（用户要求：无障碍点击与远程控制点击不算）
    //
    // 技术依据：
    //   · 无障碍 performAction(ACTION_CLICK) 只产生 TYPE_VIEW_CLICKED，不产生 MotionEvent
    //     → 确认按钮必须走 onTouch 流程（DOWN→UP），无 DOWN/UP 时序 = 模拟点击，直接无效
    //   · input tap / adb 注入会给窗口发 MotionEvent，但注入是瞬时单点（DOWN+UP 同帧 <30ms）
    //     → 真人手指按压 ≥40ms 硬校验；Android 层另有 dumpsys input 注入 grep 作旁证
    // =========================================================================
    public static final class HumanVerifier {
        // 补丁：出厂值不变，改成可覆盖字段（见 NoHumanDetector 上方说明）
        public static volatile long MIN_TOUCH_DURATION_MS = 40;    // 真人按压下限（input tap 同帧点被拒）
        public static volatile long TOUCH_AGE_WINDOW_MS   = 1500;  // VIEW_CLICKED 必须紧随触摸序列

        /** @return null=真人点击有效；否则返回拒绝原因 */
        public static String verify(long viewClickedTs, Long touchDownTs, Long touchUpTs) {
            if (touchDownTs == null || touchUpTs == null)
                return "无真实触摸序列（无障碍模拟点击 / 远程 performAction 不算）";
            long duration = touchUpTs - touchDownTs;
            if (duration < MIN_TOUCH_DURATION_MS)
                return "按压时长 " + duration + "ms 过短（疑似 input tap 注入瞬时点击）";
            if (viewClickedTs - touchUpTs > TOUCH_AGE_WINDOW_MS)
                return "点击与触摸序列时间脱节";
            return null;
        }
    }

    // =========================================================================
    // Lockdown 强保护模式：FIRED 起效（无论最终是否误报），人工确认撤销后才解除
    //   · 隐私软件（微信/QQ/支付宝）进前台 → KICK_HOME 立即踢回主界面
    //   · 安装/卸载确认框（packageinstaller）出现 → BLOCK_INSTALLER 拦截
    // =========================================================================
    public static final class LockdownController {
        public enum Action { NONE, KICK_HOME, BLOCK_INSTALLER, FREEZE_SUSPECT }

        /** 隐私软件名单（demo 内置三项，接入时可按需扩充银行类） */
        public static final List<String> PRIVACY_APPS = Arrays.asList(
                "com.tencent.mm",               // 微信
                "com.tencent.mobileqq",         // QQ
                "com.eg.android.AlipayGphone"); // 支付宝

        /** 系统安装/卸载确认框宿主：恶意软件装包/卸守护必须经过它们 */
        public static final List<String> INSTALLER_PACKAGES = Arrays.asList(
                "com.android.packageinstaller",
                "com.google.android.packageinstaller");

        /** 保护期豁免名单：launcher/守护自身/输入法等（Android 层装配时注入，防冻结桌面） */
        public static final Set<String> EXTRA_WHITELIST = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private boolean active = false;
        public boolean isActive() { return active; }
        public void engage() { active = true; }
        public void disarm() { active = false; }

        /** 每次前台变化时评估处置动作 */
        public Action evaluate(String foregroundPkg) {
            if (!active || foregroundPkg == null) return Action.NONE;
            if (INSTALLER_PACKAGES.contains(foregroundPkg)) return Action.BLOCK_INSTALLER;
            if (PRIVACY_APPS.contains(foregroundPkg))       return Action.KICK_HOME;
            if (EXTRA_WHITELIST.contains(foregroundPkg))    return Action.NONE;
            // 保护期陌生前台包（FIRED 后同伙新拉起）→ 冻结：force-stop 可恢复，不算不可逆动作
            return Action.FREEZE_SUSPECT;
        }
    }

    // =========================================================================
    // A11yPolicy：无障碍防火墙的信任判定（用户要求：只掐非大厂/知名/系统自带）
    //
    // 背景：持有无障碍权限的 app 可用 dispatchGesture 注入"时长可控的真人手势"，
    //   骗过触摸撤防与人工确认窗——所以 FIRED 保护窗口期间静默掐掉不可信无障碍，
    //   解除后原样还原（快照合并回写，用户自己开的其他服务不受影响）。
    //
    // 信任标准（满足其一即放过）：
    //   · 真系统应用（FLAG_SYSTEM，含更新过的系统包）—— TalkBack、各家自带服务
    //   · 大厂/知名包名前缀（反向域名）—— 微信/QQ/支付宝/输入法/浏览器/银行类
    //     以及 Google、小米/华为/OPPO/vivo/荣耀/三星等 OEM 自家包
    // 其余（小厂/无名/新装包）→ 掐。全程静默：不弹通知、不响铃、不留系统提示。
    // =========================================================================
    public static final class A11yPolicy {

        /** 大厂/知名包名前缀白名单（反向域名，覆盖国内主流 + OEM + 国际大厂） */
        public static final String[] TRUSTED_PKG_PREFIXES = {
                // 社交/支付（用户点名放过）
                "com.tencent.", "com.alipay.", "com.eg.android.AlipayGphone",
                // 电商/生活
                "com.taobao.", "com.tmall.", "com.xunmeng.", "com.jingdong.", "com.jd.",
                "com.netease.", "com.ss.android.", "com.smile.gifmaker", "com.zhihu.",
                "com.bilibili.", "com.meituan.", "com.sankuai.", "com.dianping.",
                // 搜索/浏览器/AI
                "com.baidu.", "com.google.", "com.android.chrome", "com.microsoft.",
                "com.UCMobile.", "com.kmxs.", "com.xingin.",
                // 输入法（无障碍常被输入法用于键盘增强/语音）
                "com.iflytek.", "com.sohu.inputmethod", "com.cootek.smartinputv5",
                // 银行/金融/运营商（安全要求高，宁可放过）
                "com.chinamworld.", "com.icbc.", "com.cmbchina.", "com.unionpay.",
                "com.unicom.", "com.cmcc.", "com.chinatelecom.", "com.chinamobile.",
                // OEM 自家（系统级信任）
                "com.xiaomi.", "com.miui.", "com.huawei.", "com.hihonor.", "com.oplus.",
                "com.oppo.", "com.coloros.", "com.vivo.", "com.bbk.", "com.meizu.",
                "com.samsung.", "com.heytap.", "com.nearme.",
                // 国际大厂
                "com.amazon.", "com.facebook.", "com.instagram.", "com.whatsapp.",
                "com.twitter", "com.linkedin.", "com.paypal.", "com.telegram.",
                "org.telegram.", "com.discord.", "com.skype.", "com.apple."
        };

        /** @param isRealSystem Android 层查好 FLAG_SYSTEM 再喂进来；null 视为未知 → 按前缀判 */
        public static boolean isTrusted(String pkg, Boolean isRealSystem) {
            if (pkg == null) return false;
            if (isRealSystem != null && isRealSystem) return true;
            for (String p : TRUSTED_PKG_PREFIXES) {
                // 目录语义：前缀统一折算成"包名或其子包"匹配（com.icbc. 既放行 com.icbc 也放行 com.icbc.pay）
                String base = p.endsWith(".") ? p.substring(0, p.length() - 1) : p;
                if (pkg.equals(base) || pkg.startsWith(base + ".")) return true;
            }
            return false;
        }
    }

    // =========================================================================
    // 编排器：疑似操控 → Lockdown+静默掐不可信无障碍 → 弹窗 → 断网 → 卸载
    // =========================================================================
    public static final class GuardController implements NoHumanDetector.Listener {

        public interface Ui {
            void onLog(String line);
            default void onSuspectVerdict(SuspectEngine.Verdict v) {}
            default void onHealthChanged(int healthFlags) {}
            default void onHumanConfirmRequired(long deadlineTs) {}   // FIRED 后进入主界面 → 弹人工确认
        }

        // 补丁：出厂值不变，改成可覆盖字段（见 NoHumanDetector 上方说明）
        public static volatile long HUMAN_CONFIRM_WINDOW_MS = 10_000;  // 人工确认倒计时（用户指定 10s）

        // 健康位（bit0 无障碍 / bit1 Shizuku / bit2 UsageStats 授权）—— 任一掉线守护降级，UI 层报警+自动修复
        public static final int HEALTH_ACCESSIBILITY = 1, HEALTH_SHIZUKU = 2, HEALTH_USAGE_STATS = 4;
        public static final int HEALTH_ALL = HEALTH_ACCESSIBILITY | HEALTH_SHIZUKU | HEALTH_USAGE_STATS;

        private final GuardActions actions;
        private final Ui ui;
        public final NoHumanDetector detector = new NoHumanDetector(this);
        private SuspectEngine.Verdict lastVerdict;
        private int lastHealth = -1;

        public volatile boolean networkCut = false;      // 网络对账用：守护期间被恶意恢复 → 再断+警报
        public volatile String lastUninstalledPkg = null; // 复活监测用：最近卸载的嫌疑包（PACKAGE_ADDED 比对）

        public final LockdownController lockdown = new LockdownController(); // FIRED 起效的强保护
        private volatile long confirmDeadline = Long.MAX_VALUE;   // 人工确认截止时刻
        private volatile boolean confirmHandled = false;          // 已处理（确认/撤销/超时）
        public volatile boolean confirmedCrack = false;           // 10s 无真人确认 → 确认恶意操控

        // ---- 触发退避（防"狼来了"：高频假触发让用户疲于确认最后关掉守护） ----
        public static volatile int SILENT_AFTER_FIRES = 3;   // 补丁：出厂值不变，可覆盖
        private volatile int fireCount = 0;
        private volatile boolean silentMode = false;              // 静默模式：保护照常，弹窗/kickHome 抑制

        public int getFireCount() { return fireCount; }
        public boolean isSilentMode() { return silentMode; }
        /** MainActivity 打开时调用：用户已知悉，退出静默模式 */
        public void exitSilentMode() { silentMode = false; fireCount = 0; }
        /** StateKeeper 恢复用：重启后保留静默模式（弹窗抑制延续） */
        public void setSilentForRestore() { silentMode = true; }
        /** StateKeeper 恢复用：重启后累计触发数不清零 */
        public void restoreFireCount(int n) { fireCount = n; }

        public GuardController(GuardActions actions, Ui ui) {
            this.actions = actions;
            this.ui = ui;
        }

        /** Android 层周期自检后喂入；与上次不同才回调（防刷屏）；首帧只建立基线不报警 */
        public void setHealth(int flags) {
            if (flags != lastHealth) {
                boolean firstFrame = (lastHealth == -1);
                lastHealth = flags;
                if (!firstFrame) ui.onHealthChanged(flags);
            }
        }
        public int getHealth() { return lastHealth; }

        /** Android 层（Service/Lockdown 执行器）写事件日志的公开桥 */
        public void uiLog(String line) { ui.onLog(line); }

        @Override public void onNoHuman(NoHumanDetector.TriggerResult t) {
            // ===== 时序（用户定义）：疑似操控瞬间先保护 + 弹窗，人工确认（或 10s 超时）后才断网 =====
            fireCount++;
            if (fireCount >= SILENT_AFTER_FIRES && !silentMode) {
                silentMode = true;
                ui.onLog("😴 触发退避：连续 " + fireCount + " 次触发 → 静默模式（保护动作照常，弹窗/kickHome 抑制，打开 APP 复位）");
            }
            ui.onLog("⚠ 疑似非人类操控：" + t.reason + "（距上次人类触摸 " + (t.idleMs / 1000) + "s）");

            // ① Lockdown 立即生效（无论误报与否）：隐私软件进前台即踢回主界面 + 拦截装/卸
            lockdown.engage();
            confirmHandled = false;
            confirmedCrack = false;
            ui.onLog("🛡 Lockdown 生效：微信等隐私软件进前台即踢回主界面，安装/卸载行为全部拦截");

            // ①b 静默掐不可信无障碍（防 dispatchGesture 假触摸骗撤防/确认窗）——零打扰，解除即还原
            actions.suspendUntrustedA11y();

            // ② 冻结涉嫌操控的包（触发窗口内活动的非白名单包）—— 源头切断底层调用：
            //    "恶意软件底层调微信发消息"随进程死亡失效
            for (String pkg : t.activePackages) {
                ui.onLog("🧊 冻结涉嫌操控进程：" + pkg + "（am force-stop via Shizuku）");
                actions.forceStop(pkg);
            }

            // ③ 采集证据 + 推举（趁数据新鲜）
            ui.onLog("📦 开始采集证据包（流量差分 / TCP 连接表 / 事件流 / 前台切换记录）");
            actions.collectEvidence(t);
            ui.onLog("🔍 启动四步溯源筛查…");
            lastVerdict = SuspectEngine.investigate(actions.collectProfiles(t));
            for (SuspectEngine.Suspect s : lastVerdict.ranked) ui.onLog(s.report());
            ui.onSuspectVerdict(lastVerdict);

            // ④ 踢回主界面 → 等 launcher 前台弹出"是否误报"人工确认窗（10s）。此时尚未断网。
            //    静默模式下抑制 kickHome 与确认窗弹起（保护动作照常），防高频假触发骚扰用户
            if (!silentMode) {
                ui.onLog("🏠 踢回主界面，等待人工确认（10s 内真人点击有效；无障碍/远程点击不算）");
                actions.kickHome();
            } else {
                // 静默模式：不打扰用户，但推举出的嫌疑直接走 per-app 精确断网（影响面小，可撤销）
                ui.onLog("😴 静默模式：跳过 kickHome 与确认窗");
                String p = (lastVerdict != null && lastVerdict.primary != null)
                        ? lastVerdict.primary.profile.pkg : null;
                if (p != null) {
                    confirmHandled = true;
                    confirmCrackNow(System.currentTimeMillis());
                } else {
                    ui.onLog("😴 无推举嫌疑 → 保持 Lockdown，等待用户回来复核");
                }
            }
        }

        /** Android 层检测到 launcher 进入前台时回调（FIRED 后弹人工确认窗；静默模式跳过） */
        public void onLauncherEntered(long ts) {
            if (confirmHandled || silentMode || !lockdown.isActive()) return;
            confirmDeadline = ts + HUMAN_CONFIRM_WINDOW_MS;
            ui.onLog("🔔 已进入系统主界面 → 弹出人工确认窗（10s 内无真人点击则确认恶意操控并断网）");
            ui.onHumanConfirmRequired(confirmDeadline);
        }

        /** 人工点击"是误报"（必须已通过 HumanVerifier 真伪鉴定）→ 解除 Lockdown，不断网 */
        public void humanConfirmDismiss(long ts) {
            if (confirmHandled) { ui.onLog("⚠ 确认窗已处理，撤销无效"); return; }
            if (ts > confirmDeadline) { ui.onLog("⚠ 确认窗已超时，撤销无效"); return; }
            confirmHandled = true;
            lockdown.disarm();
            actions.restoreA11y();             // 静默还原被掐的无障碍（原样合并回写）
            ui.onLog("✅ 人工确认为误报 → Lockdown 解除（网络未动，一切正常）");
        }

        /** 人工点击"确认恶意操控"（必须已通过 HumanVerifier 真伪鉴定）→ 立即断网 */
        public void humanConfirmCrack(long ts) {
            if (confirmHandled) { ui.onLog("⚠ 确认窗已处理"); return; }
            if (ts > confirmDeadline) { ui.onLog("⚠ 确认窗已超时"); return; }
            confirmHandled = true;
            confirmCrackNow(ts);
        }

        /** 10s 倒计时到点无真人确认（Android tick 每 500ms 推进）→ 确认恶意操控 → 断网 */
        public void onConfirmTimeout(long ts) {
            if (confirmHandled || ts < confirmDeadline || !lockdown.isActive()) return;
            ui.onLog("⏰ 10s 内无真人点击（无障碍/远程点击均无效）→ 确认恶意操控");
            confirmHandled = true;
            confirmCrackNow(ts);
        }

        private void confirmCrackNow(long ts) {
            if (appNetCutPkg != null || networkCut) {
                confirmedCrack = true;   // 已处于断网保护中（FIRED 重入）——仅刷新标志，不叠加规则
                return;
            }
            confirmedCrack = true;
            String primaryPkg = (lastVerdict != null && lastVerdict.primary != null)
                    ? lastVerdict.primary.profile.pkg : null;
            // per-app 精确断网优先（iptables owner match 只掐嫌疑 uid，不影响用户其他联网），
            // 失败/无推举 → 降级全局 svc 断网
            if (primaryPkg != null && actions.cutAppNet(primaryPkg)) {
                appNetCutPkg = primaryPkg;
                ui.onLog("⛔ 确认恶意操控 → 已精确切断 " + primaryPkg + " 的全部网络（iptables DROP，其余应用不受影响）");
            } else {
                ui.onLog("⛔ 确认恶意操控 → 立即切断 WiFi 与数据流量（svc wifi/data disable via Shizuku）");
                actions.cutNetwork();
                networkCut = true;
            }
            if (primaryPkg != null) {
                ui.onLog("👉 保持保护，等待人工确认卸载：" + primaryPkg + "（真人按压可撤销断网）");
            } else {
                ui.onLog("❓ 证据不足以推举主要嫌疑，保持断网保护，等待人工复核");
            }
        }

        private volatile String appNetCutPkg = null;  // per-app 断网的包名（null=全局断网或未断）

        /** 断网后撤销（真人校验后调用，UI 层负责 HumanVerifier）——"醒来发现误断"的出口 */
        public boolean humanCancelCrack(long ts) {
            if (!confirmedCrack) { ui.onLog("⚠ 当前未处于断网保护状态"); return false; }
            confirmedCrack = false;
            confirmHandled = true;
            if (appNetCutPkg != null) {
                actions.restoreAppNet(appNetCutPkg);
                appNetCutPkg = null;
            } else if (networkCut) {
                actions.restoreNetwork();
                networkCut = false;
            }
            lockdown.disarm();
            actions.restoreA11y();
            detector.reset();
            ui.onLog("✅ 真人撤销断网 → 保护解除，一切恢复正常");
            return true;
        }

        /** "醒来处置面板"数据源：MainActivity 读取渲染（断网状态/嫌疑/静默模式/冻结记录） */
        public String statusReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("守护状态: ").append(lockdown.isActive() ? "Lockdown 生效" : "正常值守").append('\n');
            sb.append("断网保护: ").append(appNetCutPkg != null ? ("精确断网 " + appNetCutPkg)
                    : (networkCut ? "全局断网" : "未断网")).append('\n');
            sb.append("确认恶意: ").append(confirmedCrack).append(" / 静默模式: ").append(silentMode)
              .append(" / 累计触发: ").append(fireCount).append('\n');
            if (lastVerdict != null && lastVerdict.primary != null) {
                sb.append("主要嫌疑: ").append(lastVerdict.primary.profile.pkg)
                  .append(" (").append(lastVerdict.primary.score).append(" 分)\n");
            }
            if (lastUninstalledPkg != null) {
                sb.append("最近卸载: ").append(lastUninstalledPkg).append("（复活监测中）\n");
            }
            return sb.toString();
        }

        /** 用户在溯源界面点击"自动卸载"（二次确认由 UI 层负责），卸载成功后恢复网络 */
        public void userConfirmedUninstall(String pkg) {
            ui.onLog("🧨 用户确认卸载：" + pkg + "（pm uninstall --user 0 via Shizuku）");
            boolean ok = actions.uninstall(pkg);
            ui.onLog(ok ? "✅ 卸载完成，恢复网络（svc wifi/data enable）" : "❌ 卸载失败，保持断网保护");
            if (ok) {
                if (appNetCutPkg != null) {
                    actions.restoreAppNet(appNetCutPkg);
                    appNetCutPkg = null;
                } else if (networkCut) {
                    actions.restoreNetwork();
                    networkCut = false;
                }
                lastUninstalledPkg = pkg;      // 交给复活监测：PACKAGE_ADDED 比对，防互相拉活重装
                lockdown.disarm();
                confirmHandled = true;
                confirmedCrack = false;
                actions.restoreA11y();         // 卸载完成 → 静默还原被掐的无障碍
                detector.reset();
            }
        }

        public SuspectEngine.Verdict getLastVerdict() { return lastVerdict; }
    }

    // =========================================================================
    // PC 仿真：九场景实测
    //   场景A 对照组 —— 正常人类操作 → 不触发
    //   场景B C1 —— 熄屏自亮 + 恶意 app 自启动 → Lockdown+冻结 → 弹窗超时 → 断网 → 卸载
    //   场景C C2 —— 静默运行（有人碰过但已空闲）→ 弹窗超时 → 断网
    //   场景D 误报防御 —— 看视频 7s 不触摸 → 缓冲期内继续触摸 → 撤防
    //   场景E 媒体豁免 —— 2h 长视频零触摸 → 不触发；中途被劫持 → 冻结 → 超时断网
    //   场景F 反隐藏 —— 伪装系统包名加 25 分；真系统包不加分
    //   场景G 健康自检 —— 无障碍/授权掉线 → onHealthChanged 报警
    //   场景H 人工确认误报 —— 三种程序点击全拒 → 真人点击 → Lockdown 解除不断网
    //   场景I Lockdown 三动作 —— 踢隐私软件 / 拦安装卸载 / 超时断网
    // =========================================================================
    public static void main(String[] args) {
        // 旧场景按上游语义跑（触摸后 HUMAN_IDLE_WINDOW_MS 即可判定），
        // 「真人驻留期」单独由场景 P 验证，避免把 20 个老场景的时间轴全部重排。
        long legacyPresence = NoHumanDetector.HUMAN_PRESENCE_MS;
        NoHumanDetector.HUMAN_PRESENCE_MS = 0;
        scenarioA();
        scenarioB();
        scenarioC();
        scenarioD();
        scenarioE();
        scenarioF();
        scenarioG();
        scenarioH();
        scenarioI();
        scenarioJ();
        scenarioK();
        scenarioL();
        scenarioM();
        scenarioN();
        scenarioO();
        NoHumanDetector.HUMAN_PRESENCE_MS = legacyPresence;
        scenarioP();
    }

    // ---------------- 共享 Mock ----------------
    static final class MockActions implements GuardActions {
        boolean networkUp = true, cut = false, restored = false;
        String uninstalled = null;
        List<SuspectEngine.AppProfile> profiles = new ArrayList<>();
        final List<String> evidenceCalls = new ArrayList<>();
        final List<String> forceStopped = new ArrayList<>();
        int kickHomeCount = 0;
        int a11ySuspendCount = 0, a11yRestoreCount = 0;
        final List<String> appNetCut = new ArrayList<>();        // per-app 断网记录
        boolean appNetSupported = true;                          // false = 模拟 iptables 不可用

        @Override public void cutNetwork() { cut = true; networkUp = false; System.out.println("    [Mock] exec: svc wifi disable / svc data disable"); }
        @Override public void restoreNetwork() { restored = true; networkUp = true; System.out.println("    [Mock] exec: svc wifi enable / svc data enable"); }
        @Override public void collectEvidence(NoHumanDetector.TriggerResult t) { evidenceCalls.add(t.reason); System.out.println("    [Mock] 证据包落盘 evidence-" + t.pendingConfirmedAt + "/"); }
        @Override public List<SuspectEngine.AppProfile> collectProfiles(NoHumanDetector.TriggerResult t) { return profiles; }
        @Override public boolean uninstall(String pkg) { uninstalled = pkg; return true; }
        @Override public void kickHome() { kickHomeCount++; System.out.println("    [Mock] 踢回系统主界面（GLOBAL_ACTION_HOME）"); }
        @Override public void forceStop(String pkg) { forceStopped.add(pkg); System.out.println("    [Mock] exec: am force-stop " + pkg); }
        @Override public void suspendUntrustedA11y() { a11ySuspendCount++; System.out.println("    [Mock] 静默掐不可信无障碍（快照→过滤→写回，零通知）"); }
        @Override public void restoreA11y() { a11yRestoreCount++; System.out.println("    [Mock] 静默还原无障碍（快照合并回写，零通知）"); }
        @Override public boolean cutAppNet(String pkg) {
            if (!appNetSupported) return false;
            appNetCut.add(pkg); System.out.println("    [Mock] exec: iptables -A OUTPUT -m owner --uid-owner <" + pkg + "> -j DROP");
            return true;
        }
        @Override public void restoreAppNet(String pkg) {
            appNetCut.remove(pkg); System.out.println("    [Mock] exec: iptables -D OUTPUT -m owner --uid-owner <" + pkg + "> -j DROP");
        }
    }

    static SuspectEngine.AppProfile profile(String pkg) {
        SuspectEngine.AppProfile p = new SuspectEngine.AppProfile();
        p.pkg = pkg;
        return p;
    }

    static void assertEq(String name, Object expect, Object actual) {
        boolean ok = (expect == null) ? actual == null : expect.equals(actual);
        System.out.println("    " + (ok ? "✔ " : "✘ ") + name + (ok ? "" : "  期望=" + expect + " 实际=" + actual));
        if (!ok) throw new AssertionError(name);
    }

    // ---------------- 场景A：正常人类操作 → 不触发 ----------------
    static void scenarioA() {
        System.out.println("== 场景A 对照组：人类正常操作微信，不得触发 ==");
        MockActions actions = new MockActions();
        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.SCREEN_ON, null));
        d.feed(new InteractionEvent(500, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(1000, InteractionEvent.Type.APP_RESUMED, "com.tencent.mm"));
        d.feed(new InteractionEvent(3000, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(5000, InteractionEvent.Type.UI_ACTIVITY, "com.tencent.mm"));
        d.onPendingTimeout(8000);
        assertEq("A/状态", NoHumanDetector.State.IDLE, d.getState());
        assertEq("A/网络未被切断", true, !actions.cut);
        assertEq("A/无证据采集", 0, actions.evidenceCalls.size());
        System.out.println("    [结果] 全程 IDLE，网络未动，零误报\n");
    }

    // ---------------- 场景B：C1 熄屏自亮 + 恶意 app 自启动 ----------------
    static void scenarioB() {
        System.out.println("== 场景B：熄屏 5 分钟后设备自己亮屏打开恶意 app 并外传流量 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.evil.spy");
        evil.requestInstallPackages = true;
        evil.declaredAccessibilityService = true;
        evil.overlay = true;
        evil.bootCompletedWithForeground = true;
        evil.trafficDelta = 8192; evil.trafficRank = 1;
        evil.behaviorHit = true;
        evil.behaviorDetails.add("300.0s 无人窗口内自行启动前台（自启动实锤）");
        SuspectEngine.AppProfile wechat = profile("com.tencent.mm");
        wechat.trafficDelta = 512; wechat.trafficRank = 2;
        actions.profiles.add(evil); actions.profiles.add(wechat);

        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> logs.add(log));
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));       // 人最后摸一下
        d.feed(new InteractionEvent(1000, InteractionEvent.Type.SCREEN_OFF, null));     // 熄屏
        d.feed(new InteractionEvent(300_000, InteractionEvent.Type.SCREEN_ON, null));   // 5min 后自己亮屏，无人触摸
        d.feed(new InteractionEvent(301_200, InteractionEvent.Type.APP_RESUMED, "com.evil.spy")); // 自己打开恶意 app
        d.feed(new InteractionEvent(301_800, InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy")); // 持续作案#1
        d.feed(new InteractionEvent(301_900, InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy")); // 持续作案#2 → 实锤
        assertEq("B/状态=FIRED", NoHumanDetector.State.FIRED, d.getState());
        assertEq("B/FIRED时未断网(先弹窗后断网)", false, actions.cut);
        assertEq("B/Lockdown已生效", true, g.lockdown.isActive());
        assertEq("B/嫌疑进程已冻结", true, actions.forceStopped.contains("com.evil.spy"));
        assertEq("B/已采集证据", 1, actions.evidenceCalls.size());
        assertEq("B/推举主要嫌疑", "com.evil.spy", g.getLastVerdict().primary.profile.pkg);
        SuspectEngine.Suspect ps = g.getLastVerdict().primary;
        assertEq("B/evil 得分", 30 + 20 + 15 + 10 + 20 + 50, ps.score);
        assertEq("B/微信只吃流量分(Step3)", 20, g.getLastVerdict().ranked.get(1).score);

        // 10s 人工确认窗：进入主界面弹窗，无人确认 → 确认恶意操控 → 此时才断网（per-app 优先）
        g.onLauncherEntered(301_900);
        g.onConfirmTimeout(311_900);
        assertEq("B/超时确认后精确断网嫌疑app", true, actions.appNetCut.contains("com.evil.spy"));
        assertEq("B/全局网络未动(其余应用无感)", false, actions.cut);
        assertEq("B/confirmedCrack", true, g.confirmedCrack);

        g.userConfirmedUninstall("com.evil.spy");
        assertEq("B/已卸载", "com.evil.spy", actions.uninstalled);
        assertEq("B/卸载后per-app规则清除", false, actions.appNetCut.contains("com.evil.spy"));
        assertEq("B/状态复位", NoHumanDetector.State.IDLE, d.getState());
        System.out.println("    [结果] Lockdown+冻结→取证推举→弹窗→10s超时→精确断网→卸载→清规则 全链路通过\n");
    }

    // ---------------- 场景C：C2 静默运行型 ----------------
    static void scenarioC() {
        System.out.println("== 场景C：人碰过一下但已空闲 10s，恶意 app 静默自弹界面 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.evil.remote");
        evil.enabledAccessibilityService = true;      // 无障碍已启用（可远程操控）
        evil.behaviorHit = true;
        evil.behaviorDetails.add("600s 内无触摸窗口自弹覆盖层");
        evil.trafficDelta = 1024; evil.trafficRank = 1;
        actions.profiles.add(evil);
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.remote")); // > 6s 窗口
        d.onPendingTimeout(12_500);  // 缓冲期结束仍无触摸 → 确认触发
        assertEq("C/状态=FIRED", NoHumanDetector.State.FIRED, d.getState());
        assertEq("C/FIRED时未断网", false, actions.cut);
        g.onLauncherEntered(12_600);
        g.onConfirmTimeout(22_600);  // 10s 无真人点击 → 断网
        assertEq("C/超时确认后精确断网", true, actions.appNetCut.contains("com.evil.remote"));
        assertEq("C/推举", "com.evil.remote", g.getLastVerdict().primary.profile.pkg);
        assertEq("C/得分=40+20+50", 40 + 20 + 50, g.getLastVerdict().primary.score);
        System.out.println("    [结果] C2 触发 → Lockdown → 弹窗超时 → 断网，无障碍启用者被推举\n");
    }

    // ---------------- 场景D：误报防御（看视频停顿） ----------------
    static void scenarioD() {
        System.out.println("== 场景D 误报防御：人看视频 7s 不触摸，界面活动命中但缓冲期内继续触摸 ==");
        MockActions actions = new MockActions();
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(7000, InteractionEvent.Type.APP_RESUMED, "com.video.player")); // 7s 无触摸 → PENDING
        assertEq("D/进入缓冲期", NoHumanDetector.State.PENDING, d.getState());
        assertEq("D/缓冲期不断网", true, !actions.cut);
        d.feed(new InteractionEvent(8000, InteractionEvent.Type.HUMAN_TOUCH, null));  // 人回来调音量 → 撤防
        assertEq("D/触摸撤防", NoHumanDetector.State.IDLE, d.getState());
        d.onPendingTimeout(10_000);
        assertEq("D/不触发不断网", true, !actions.cut);
        System.out.println("    [结果] PENDING 缓冲期被人类触摸正确撤防，零误报\n");
    }

    // ---------------- 场景E：媒体豁免（长视频 2 小时零触摸） + 媒体停了才恢复判定 ----------------
    static void scenarioE() {
        System.out.println("== 场景E 媒体豁免：看 2h 长视频零触摸 → 不误报；媒体期间劫持也不断网；停了才恢复判定 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile hijack = profile("com.evil.hijack");
        hijack.overlay = true;                                  // 悬浮窗劫持
        hijack.behaviorHit = true;
        hijack.behaviorDetails.add("媒体播放中被顶入前台，UsageStats ACTIVITY_RESUMED 交叉确认");
        hijack.trafficDelta = 4096; hijack.trafficRank = 1;
        actions.profiles.add(hijack);
        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> logs.add(log));
        NoHumanDetector d = g.detector;

        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));          // 按下播放键
        d.feed(new InteractionEvent(1000, InteractionEvent.Type.APP_RESUMED,
                "com.video.player", 0, true));                                             // UsageStats 高置信
        d.setMediaActive(true, "com.video.player");                                        // 音频开始，主人=视频 app

        // 2 小时视频播放：视频 app 自身的界面活动（切集/弹字幕控制条）全部豁免
        for (int t = 10_000; t <= 7_200_000; t += 600_000) {
            d.feed(new InteractionEvent(t, InteractionEvent.Type.UI_ACTIVITY, "com.video.player"));
        }
        d.onPendingTimeout(7_205_000);
        assertEq("E/媒体期零误报", NoHumanDetector.State.IDLE, d.getState());
        assertEq("E/媒体期不断网", true, !actions.cut);

        // 媒体播放期间恶意 app 顶入：此时「人在看视频」与「鬼在操控」分不开，而真人在场时
        // 恶意软件当面操控既不合理也立刻会被看见 —— 不自动判炸、不断网（§8.3.165）。
        d.feed(new InteractionEvent(7_210_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        d.feed(new InteractionEvent(7_211_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack"));
        d.feed(new InteractionEvent(7_212_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack"));
        d.onPendingTimeout(7_216_000);
        assertEq("E/媒体期劫持不自动判炸", NoHumanDetector.State.IDLE, d.getState());
        assertEq("E/媒体期不断网", false, actions.cut);

        // 媒体停了 → 恢复自动值守；此时无人触摸、界面持续异动，照常抓
        d.setMediaActive(false, null);
        d.feed(new InteractionEvent(7_220_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));                                              // 高置信 → PENDING
        assertEq("E/媒体停后劫持进入缓冲期", NoHumanDetector.State.PENDING, d.getState());
        d.feed(new InteractionEvent(7_221_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack")); // 作案#1
        d.feed(new InteractionEvent(7_222_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack")); // 作案#2 → 实锤
        assertEq("E/劫持实锤FIRED", NoHumanDetector.State.FIRED, d.getState());
        assertEq("E/FIRED时未断网(先保护后断网)", false, actions.cut);
        assertEq("E/劫持者已被冻结", true, actions.forceStopped.contains("com.evil.hijack"));
        g.onLauncherEntered(7_223_000);
        g.onConfirmTimeout(7_233_000);
        assertEq("E/超时确认后精确断网", true, actions.appNetCut.contains("com.evil.hijack"));
        assertEq("E/推举劫持者", "com.evil.hijack", g.getLastVerdict().primary.profile.pkg);
        System.out.println("    [结果] 媒体期零误报、不自动断网；媒体停后恢复判定 → 冻结 → 超时断网\n");
    }

    // ---------------- 场景F：反隐藏 —— 伪装系统包名 +25，真系统包不加分 ----------------
    static void scenarioF() {
        System.out.println("== 场景F 反隐藏：com.android.* 伪装包 vs 真系统包 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile fake = profile("com.android.service.core");   // 伪装系统范儿包名
        fake.isRealSystem = false;
        fake.behaviorHit = true;
        fake.trafficDelta = 2048; fake.trafficRank = 1;
        SuspectEngine.AppProfile realSys = profile("com.android.providers.media");
        realSys.isRealSystem = true;                            // 真系统包（通常被 Android 层过滤，这里对照）
        realSys.trafficDelta = 512; realSys.trafficRank = 2;    // 给它正常的流量分，证明"真系统不吃伪装分"
        actions.profiles.add(fake); actions.profiles.add(realSys);
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED,
                "com.android.service.core", 0, true));                          // 高置信单次实锤
        d.onPendingTimeout(12_500);
        assertEq("F/触发", NoHumanDetector.State.FIRED, d.getState());
        SuspectEngine.Suspect top = g.getLastVerdict().primary;
        assertEq("F/推举伪装包", "com.android.service.core", top.profile.pkg);
        assertEq("F/得分=20(流量)+50(行为)+25(伪装)", 20 + 50 + 25, top.score);
        boolean hasMaskEvidence = top.evidence.stream().anyMatch(x -> x.contains("伪装系统应用"));
        assertEq("F/证据链含伪装标记", true, hasMaskEvidence);
        SuspectEngine.Suspect sys = g.getLastVerdict().ranked.get(1);
        assertEq("F/真系统包无伪装加分", 20, sys.score);
        System.out.println("    [结果] 伪装包被点名+加分，真系统包不吃分\n");
    }

    // ---------------- 场景G：健康自检 —— 无障碍/授权掉线报警 ----------------
    static void scenarioG() {
        System.out.println("== 场景G 健康自检：无障碍掉线 → onHealthChanged 报警（Android 层随后 Shizuku 自动恢复） ==");
        MockActions actions = new MockActions();
        final int[] alerts = {0};
        GuardController g = new GuardController(actions, new GuardController.Ui() {
            @Override public void onLog(String line) {}
            @Override public void onHealthChanged(int flags) { alerts[0]++; }
        });
        g.setHealth(GuardController.HEALTH_ALL);
        assertEq("G/初始全绿无报警", 0, alerts[0]);
        g.setHealth(GuardController.HEALTH_SHIZUKU);                 // 无障碍掉了（bit0 清零）
        assertEq("G/掉线报警1次", 1, alerts[0]);
        g.setHealth(GuardController.HEALTH_SHIZUKU);                 // 同状态不重复报警
        assertEq("G/同态防刷屏", 1, alerts[0]);
        g.setHealth(GuardController.HEALTH_ALL);                     // Android 层 Shizuku 自动恢复后回绿
        assertEq("G/恢复再报1次", 2, alerts[0]);
        assertEq("G/健康位读回", GuardController.HEALTH_ALL, g.getHealth());
        System.out.println("    [结果] 掉线即报警、同态防刷屏、恢复回绿全部正确\n");
    }

    // ---------------- 场景H：人工确认误报路径（真伪点击鉴定 + Lockdown 解除） ----------------
    static void scenarioH() {
        System.out.println("== 场景H 人工确认误报：真人点击（含真伪鉴定）→ Lockdown 解除，网络未动 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.evil.remote");
        evil.enabledAccessibilityService = true; evil.behaviorHit = true;
        evil.trafficDelta = 1024; evil.trafficRank = 1;
        actions.profiles.add(evil);
        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> logs.add(log));
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true)); // 高置信
        d.onPendingTimeout(12_500);
        assertEq("H/FIRED", NoHumanDetector.State.FIRED, d.getState());

        // 程序点击三连拒（模拟恶意软件抢在 10s 内自动点掉弹窗）：
        // ① 无障碍 performAction：只有 VIEW_CLICKED 无触摸序列
        String r1 = HumanVerifier.verify(13_000, null, null);
        assertEq("H/拒无障碍模拟点击", true, r1 != null);
        // ② input tap 注入：DOWN/UP 同帧 5ms
        String r2 = HumanVerifier.verify(13_100, 13_095L, 13_100L);
        assertEq("H/拒 input tap 注入", true, r2 != null);
        // ③ 触摸与点击时间脱节（点击距触摸序列 1.75s > 1.5s 窗口）
        String r3 = HumanVerifier.verify(14_800, 13_000L, 13_050L);
        assertEq("H/拒时间脱节", true, r3 != null);
        // 拒绝的点击不算 → 确认窗保持未处理
        assertEq("H/无效点击不消耗窗口", false, g.confirmedCrack);

        // 真人点击：DOWN→按压 120ms→UP→VIEW_CLICKED
        Long down = 14_000L, up = 14_120L;
        assertEq("H/真人点击通过鉴定", null, HumanVerifier.verify(14_121, down, up));
        g.onLauncherEntered(12_600);
        g.humanConfirmDismiss(14_121);
        assertEq("H/Lockdown解除", false, g.lockdown.isActive());
        assertEq("H/全程未断网", false, actions.cut);
        assertEq("H/未确认恶意", false, g.confirmedCrack);
        System.out.println("    [结果] 三种程序点击全部无效；真人按压 120ms 点击成功撤销，网络全程未动\n");
    }

    // ---------------- 场景I：Lockdown 三动作（踢隐私软件 / 拦装卸 / 10s 超时断网） ----------------
    static void scenarioI() {
        System.out.println("== 场景I Lockdown：微信进前台即踢回；安装/卸载框出现即拦截；超时断网 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.evil.remote");
        evil.enabledAccessibilityService = true; evil.behaviorHit = true;
        actions.profiles.add(evil);
        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> logs.add(log));
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true));
        d.onPendingTimeout(12_500);
        assertEq("I/FIRED+Lockdown", true, g.lockdown.isActive());

        // ① 隐私软件进前台 → KICK_HOME（恶意软件底层调微信发消息 = 把微信调到前台，即被踢灭）
        assertEq("I/微信→踢回主界面", LockdownController.Action.KICK_HOME,
                g.lockdown.evaluate("com.tencent.mm"));
        assertEq("I/支付宝→踢回主界面", LockdownController.Action.KICK_HOME,
                g.lockdown.evaluate("com.eg.android.AlipayGphone"));
        // ② 安装/卸载确认框出现 → BLOCK_INSTALLER
        assertEq("I/安装框→拦截", LockdownController.Action.BLOCK_INSTALLER,
                g.lockdown.evaluate("com.android.packageinstaller"));
        // ③ launcher/守护自身 → 豁免（防冻结桌面）
        LockdownController.EXTRA_WHITELIST.add("com.android.launcher3");
        LockdownController.EXTRA_WHITELIST.add("com.zeus.guardlink");
        assertEq("I/桌面→豁免", LockdownController.Action.NONE,
                g.lockdown.evaluate("com.android.launcher3"));
        // ④ 陌生前台包（FIRED 后同伙新拉起）→ FREEZE 冻结
        assertEq("I/陌生包→冻结", LockdownController.Action.FREEZE_SUSPECT,
                g.lockdown.evaluate("com.evil.accomplice"));
        assertEq("I/Lockdown期间未断网", false, actions.cut);

        // 误报解除后同样行为不再拦截
        g.onLauncherEntered(12_600);
        g.humanConfirmDismiss(13_000);
        assertEq("I/解除后微信→不动作", LockdownController.Action.NONE,
                g.lockdown.evaluate("com.tencent.mm"));
        assertEq("I/解除后未断网", false, actions.cut);

        // 对照：无人确认 → 10s 超时 → 断网（三动作持续到解除为止）
        MockActions actions2 = new MockActions();
        SuspectEngine.AppProfile evil2 = profile("com.evil.remote");
        evil2.enabledAccessibilityService = true; evil2.behaviorHit = true;
        actions2.profiles.add(evil2);
        GuardController g2 = new GuardController(actions2, log -> {});
        NoHumanDetector d2 = g2.detector;
        d2.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d2.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true));
        d2.onPendingTimeout(12_500);
        g2.onLauncherEntered(12_600);
        g2.onConfirmTimeout(22_600);
        assertEq("I/超时精确断网", true, actions2.appNetCut.contains("com.evil.remote"));
        assertEq("I/确认后仍拦截微信", LockdownController.Action.KICK_HOME,
                g2.lockdown.evaluate("com.tencent.mm"));
        System.out.println("    [结果] 踢隐私/拦装卸/超时断网全部正确；误报解除立即恢复无拦截\n");
    }

    // ---------------- 场景J：无障碍防火墙 —— 只掐小厂，大厂/系统放行，全程静默还原 ----------------
    static void scenarioJ() {
        System.out.println("== 场景J A11yPolicy：大厂/知名/系统放行，小厂掐；FIRED 掐 → 解除原样还原 ==");
        // ---- 信任判定单元 ----
        assertEq("J/系统自带放行(TalkBack)",
                true, A11yPolicy.isTrusted("com.google.android.marvin.talkback", true));
        assertEq("J/真系统未知名放行",
                true, A11yPolicy.isTrusted("com.some.rom.service", true));
        assertEq("J/微信放行", true, A11yPolicy.isTrusted("com.tencent.mm", false));
        assertEq("J/支付宝放行", true, A11yPolicy.isTrusted("com.eg.android.AlipayGphone", false));
        assertEq("J/讯飞输入法放行", true, A11yPolicy.isTrusted("com.iflytek.inputmethod", false));
        assertEq("J/工行放行", true, A11yPolicy.isTrusted("com.icbc", false));
        assertEq("J/小米服务放行", true, A11yPolicy.isTrusted("com.xiaomi.smarthome", false));
        assertEq("J/小厂工具拦截", false, A11yPolicy.isTrusted("com.unknown.toolkit", false));
        assertEq("J/伪装知名拦截(com.tencentx.)", false, A11yPolicy.isTrusted("com.tencentx.evil", false));
        assertEq("J/null拦截", false, A11yPolicy.isTrusted(null, false));

        // ---- 时机链：FIRED 掐 → 误报撤销还原 / 确认恶意保持掐 → 卸载后还原 ----
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.unknown.toolkit");   // 小厂 = 嫌疑 + 可能持无障碍
        evil.enabledAccessibilityService = true; evil.behaviorHit = true;
        actions.profiles.add(evil);
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.unknown.toolkit", 0, true));
        d.onPendingTimeout(12_500);
        assertEq("J/FIRED时已静默掐", 1, actions.a11ySuspendCount);

        g.onLauncherEntered(12_600);
        g.humanConfirmDismiss(13_000);           // 误报撤销
        assertEq("J/撤销后静默还原", 1, actions.a11yRestoreCount);

        // 对照：确认恶意路径 —— 掐着不放，卸载完成后才还原
        MockActions actions2 = new MockActions();
        SuspectEngine.AppProfile evil2 = profile("com.unknown.toolkit");
        evil2.enabledAccessibilityService = true; evil2.behaviorHit = true;
        actions2.profiles.add(evil2);
        GuardController g2 = new GuardController(actions2, log -> {});
        NoHumanDetector d2 = g2.detector;
        d2.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d2.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.unknown.toolkit", 0, true));
        d2.onPendingTimeout(12_500);
        g2.onLauncherEntered(12_600);
        g2.onConfirmTimeout(22_600);
        assertEq("J/确认恶意后保持掐(还原0次)", 0, actions2.a11yRestoreCount);
        g2.userConfirmedUninstall("com.unknown.toolkit");
        assertEq("J/卸载完成后还原", 1, actions2.a11yRestoreCount);
        System.out.println("    [结果] 大厂/系统全放行，小厂保护期静默掐、解除原样还原，全程零通知\n");
    }

    // ---------------- 场景K：误报降噪 —— launcher 白名单 + C1 双条件（抬手亮屏不误报） ----------------
    static void scenarioK() {
        System.out.println("== 场景K 降噪：桌面 widget 刷新不计证据；抬手亮屏无自启动不触发 ==");
        MockActions actions = new MockActions();
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        NoHumanDetector.EXTRA_WHITELIST.add("com.android.launcher3");   // Android 层装配时注入

        // 桌面时钟 widget 每分钟自动刷新（WINDOW_STATE_CHANGED pkg=launcher）→ 白名单，不算证据
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        for (int t = 60_000; t <= 600_000; t += 60_000) {
            d.feed(new InteractionEvent(t, InteractionEvent.Type.UI_ACTIVITY, "com.android.launcher3"));
        }
        d.onPendingTimeout(700_000);
        assertEq("K/widget刷新零误报", NoHumanDetector.State.IDLE, d.getState());

        // 抬手亮屏（加速度计手势，无触摸）→ 只记录不触发
        d.feed(new InteractionEvent(800_000, InteractionEvent.Type.SCREEN_ON, null));
        d.onPendingTimeout(820_000);
        assertEq("K/光亮屏不触发", NoHumanDetector.State.IDLE, d.getState());
        assertEq("K/光亮屏不断网", false, actions.cut);

        // 亮屏 + 10s 内自启动（无人触摸）→ C1 复合型才触发
        d.feed(new InteractionEvent(810_000, InteractionEvent.Type.APP_RESUMED, "com.evil.spy", 0, true));
        assertEq("K/亮屏+自启动→PENDING", NoHumanDetector.State.PENDING, d.getState());
        d.feed(new InteractionEvent(811_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy"));
        d.feed(new InteractionEvent(812_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy"));
        assertEq("K/C1复合实锤", NoHumanDetector.State.FIRED, d.getState());
        System.out.println("    [结果] 桌面活动与抬手亮屏全部无感通过，真自启动照常抓\n");
    }

    // ---------------- 场景L：狼来了退避 + 醒来撤销断网 + 状态报告 ----------------
    static void scenarioL() {
        System.out.println("== 场景L 退避：连续触发进入静默（per-app 断网不弹窗）；真人撤销；醒来面板 ==");
        MockActions actions = new MockActions();
        SuspectEngine.AppProfile evil = profile("com.evil.remote");
        evil.enabledAccessibilityService = true; evil.behaviorHit = true;
        evil.trafficDelta = 1024; evil.trafficRank = 1;
        actions.profiles.add(evil);
        List<String> logs = new ArrayList<>();
        GuardController g = new GuardController(actions, log -> logs.add(log));
        NoHumanDetector d = g.detector;

        // 连续 3 次 FIRED（模拟假触发循环）→ 第 3 次进入静默
        for (int i = 1; i <= 3; i++) {
            long base = i * 60_000L;
            d.feed(new InteractionEvent(base, InteractionEvent.Type.HUMAN_TOUCH, null));
            d.feed(new InteractionEvent(base + 10_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true));
            d.onPendingTimeout(base + 12_500);
            d.feed(new InteractionEvent(base + 13_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.remote"));
            d.feed(new InteractionEvent(base + 14_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.remote"));
            d.reset();  // 每轮处置后复位
        }
        assertEq("L/3次后静默", true, g.isSilentMode());

        // 第 4 次 FIRED：静默模式 → 不 kickHome 不弹窗，但 per-app 精确断网照做
        int kickBefore = actions.kickHomeCount;
        d.feed(new InteractionEvent(240_000, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(250_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true));
        d.onPendingTimeout(252_500);
        d.feed(new InteractionEvent(253_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.remote"));
        d.feed(new InteractionEvent(254_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.remote"));
        assertEq("L/静默期不弹窗(无kickHome)", kickBefore, actions.kickHomeCount);
        assertEq("L/per-app精确断网", true, actions.appNetCut.contains("com.evil.remote"));
        assertEq("L/全局未断", false, actions.cut);

        // 用户回来（打开 APP 即调 exitSilentMode）→ 醒来面板 → 真人按压撤销断网
        g.exitSilentMode();
        String report = g.statusReport();
        assertEq("L/面板含精确断网", true, report.contains("精确断网 com.evil.remote"));
        assertEq("L/面板含主要嫌疑", true, report.contains("com.evil.remote"));
        assertEq("L/真人撤销断网", true, g.humanCancelCrack(260_000));
        assertEq("L/撤销后网络规则清除", false, actions.appNetCut.contains("com.evil.remote"));
        assertEq("L/撤销后Lockdown解除", false, g.lockdown.isActive());
        assertEq("L/撤销后状态复位", NoHumanDetector.State.IDLE, d.getState());
        System.out.println("    [结果] 高频假触发不再骚扰；精确断网可真人撤销；醒来面板信息完整\n");
    }

    // ---------------- 场景M：per-app 断网降级 —— iptables 不可用时回退全局 svc ----------------
    static void scenarioM() {
        System.out.println("== 场景M 降级链：iptables 不可用 → 自动回退全局 svc 断网 ==");
        MockActions actions = new MockActions();
        actions.appNetSupported = false;   // 模拟内核不支持 owner match
        SuspectEngine.AppProfile evil = profile("com.evil.remote");
        evil.enabledAccessibilityService = true; evil.behaviorHit = true;
        actions.profiles.add(evil);
        GuardController g = new GuardController(actions, log -> {});
        NoHumanDetector d = g.detector;
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED, "com.evil.remote", 0, true));
        d.onPendingTimeout(12_500);
        g.onLauncherEntered(12_600);
        g.onConfirmTimeout(22_600);
        assertEq("M/per-app失败→全局断网", true, actions.cut);
        assertEq("M/per-app零调用", 0, actions.appNetCut.size());
        assertEq("M/网络对账标志", true, g.networkCut);
        System.out.println("    [结果] 精确断网不可用时无缝降级全局保护，断网永不落空\n");
    }

    // ---------------- 场景N：权限面很肥的正常软件不得被点名（误报复核） ----------------
    static void scenarioN() {
        System.out.println("== 场景N 误报复核：只占权限、没有实锤的正常软件不得推举 ==");
        SuspectEngine.AppProfile health = profile("com.mi.health");
        health.requestInstallPackages = true;
        health.overlay = true;
        health.writeSettings = true;
        health.bootCompletedWithForeground = true;
        health.mediaProjection = true;
        health.notificationListener = true;
        health.notificationListenerEnabled = true;
        SuspectEngine.AppProfile kugou = profile("com.kugou.android");
        kugou.requestInstallPackages = true;
        kugou.overlay = true;
        kugou.writeSettings = true;
        kugou.notificationListener = true;
        kugou.notificationListenerEnabled = true;
        SuspectEngine.AppProfile evil = profile("com.evil.hidden");
        evil.enabledAccessibilityService = true;
        evil.behaviorHit = true;
        evil.behaviorDetails.add("302.0s 无人窗口内自行启动前台（自启动实锤）");
        List<SuspectEngine.AppProfile> list = new ArrayList<>();
        list.add(health);
        list.add(kugou);
        list.add(evil);
        SuspectEngine.Verdict v = SuspectEngine.investigate(list);
        assertEq("N/系统应用过线但不点名", false, v.ranked.get(0).isPrimary());
        assertEq("N/主流应用过线但不点名", false, v.ranked.get(1).isPrimary());
        assertEq("N/主嫌由实锤决定，排在后面的作案者被选中", "com.evil.hidden", v.primary.profile.pkg);
        System.out.println("    [结果] 能力面只用来排序，点名必须有实锤\n");
    }

    // ---------------- 场景O：小窗/后台看视频，媒体期一律不自动断网 ----------------
    static void scenarioO() {
        System.out.println("== 场景O 小窗播放：前台归属记成桌面、别的包顶入，媒体期都不自动断网 ==");
        boolean[] fired = {false};
        NoHumanDetector d = new NoHumanDetector(s -> fired[0] = true);
        // 音频开始那一刻 UsageStats 抓到的是桌面；真正的播放器在系统音频服务里
        d.setMediaActive(true, "com.miui.home",
                new LinkedHashSet<>(Arrays.asList("tv.danmaku.bili")));
        long t = 1_000_000;
        d.feed(new InteractionEvent(t, InteractionEvent.Type.HUMAN_TOUCH, "com.miui.home"));
        d.feed(new InteractionEvent(t + 10_000, InteractionEvent.Type.UI_ACTIVITY,
                "tv.danmaku.bili"));
        assertEq("O/播放器活动被豁免（不是 PENDING）",
                NoHumanDetector.State.IDLE, d.getState());
        d.feed(new InteractionEvent(t + 10_500, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        d.feed(new InteractionEvent(t + 11_000, InteractionEvent.Type.UI_ACTIVITY,
                "com.evil.hijack"));
        d.onPendingTimeout(t + 15_000);
        assertEq("O/媒体期非播放器包也不判炸",
                NoHumanDetector.State.IDLE, d.getState());
        assertEq("O/媒体期没触发 listener", false, fired[0]);

        // 媒体停了 → 恢复自动值守，同一条证据链照常抓
        d.setMediaActive(false, null);
        d.feed(new InteractionEvent(t + 20_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        assertEq("O/媒体停后进入缓冲期",
                NoHumanDetector.State.PENDING, d.getState());
        d.feed(new InteractionEvent(t + 21_000, InteractionEvent.Type.UI_ACTIVITY,
                "com.evil.hijack"));
        d.feed(new InteractionEvent(t + 22_000, InteractionEvent.Type.UI_ACTIVITY,
                "com.evil.hijack"));
        assertEq("O/媒体停后照常判炸", NoHumanDetector.State.FIRED, d.getState());
        assertEq("O/媒体停后确实触发 listener", true, fired[0]);

        // 缓冲期进行中视频开始播放 → 立即撤防
        NoHumanDetector d2 = new NoHumanDetector(s -> { });
        d2.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));
        d2.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        assertEq("O/先进缓冲期", NoHumanDetector.State.PENDING, d2.getState());
        d2.setMediaActive(true, "tv.danmaku.bili");
        assertEq("O/视频开始即撤防", NoHumanDetector.State.IDLE, d2.getState());
        assertEq("O/拦下计数 +1", 1, d2.mediaSuppressed);
        System.out.println("    [结果] 媒体在放时所有界面活动都不判炸；媒体停了恢复判定\n");
    }

    // ---------------- 场景P：真人驻留期（触摸/解锁后 5 分钟内不判无人操控） ----------------
    static void scenarioP() {
        System.out.println("== 场景P 真人驻留：触摸后 5 分钟内界面怎么变都不判炸 ==");
        boolean[] fired = {false};
        NoHumanDetector d = new NoHumanDetector(s -> fired[0] = true);
        d.feed(new InteractionEvent(0, InteractionEvent.Type.HUMAN_TOUCH, null));   // 真人碰一下
        d.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));                                      // 10s 后界面自己变
        d.feed(new InteractionEvent(12_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack"));
        d.onPendingTimeout(20_000);
        assertEq("P/驻留期内不进缓冲期",
                NoHumanDetector.State.IDLE, d.getState());
        assertEq("P/驻留期内不触发", false, fired[0]);

        // 超过驻留期（5 分钟）没碰手机：恢复判定，持续异动照常抓
        d.feed(new InteractionEvent(310_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        assertEq("P/驻留期过后进入缓冲期",
                NoHumanDetector.State.PENDING, d.getState());
        d.feed(new InteractionEvent(311_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack"));
        d.feed(new InteractionEvent(312_000, InteractionEvent.Type.UI_ACTIVITY, "com.evil.hijack"));
        assertEq("P/驻留期过后照常判炸", NoHumanDetector.State.FIRED, d.getState());
        assertEq("P/确实触发 listener", true, fired[0]);

        // 解锁也算「人在手机前」；熄屏则立即清空驻留期，C1 冷启动仍可判
        NoHumanDetector d2 = new NoHumanDetector(s -> { });
        d2.feed(new InteractionEvent(0, InteractionEvent.Type.USER_PRESENT, null));
        d2.feed(new InteractionEvent(10_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        assertEq("P/解锁后驻留期生效", NoHumanDetector.State.IDLE, d2.getState());
        d2.feed(new InteractionEvent(20_000, InteractionEvent.Type.SCREEN_OFF, null));
        d2.feed(new InteractionEvent(25_000, InteractionEvent.Type.APP_RESUMED,
                "com.evil.hijack", 0, true));
        assertEq("P/熄屏后驻留期清空，恢复判定",
                NoHumanDetector.State.PENDING, d2.getState());
        System.out.println("    [结果] 真人在场不判炸；人离开 / 熄屏后才恢复自动值守\n");
    }
}
