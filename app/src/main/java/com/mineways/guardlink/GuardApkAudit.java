package com.mineways.guardlink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安装包体检的纯规则层（零 Android 依赖，PC 可单测）。
 *
 * <p>只回答一个问题：<b>这个 APK 在装进来之前，值不值得放行</b>。判据全部来自压缩包结构、
 * 清单权限面与组件声明，不执行任何代码，也不依赖联网查毒 —— 断网、样本没入库时同样有效。
 *
 * <p>特征本身由 {@link GuardFeatureSet} 管理：内置规则是出厂默认，云端可以对同 id 改分值/文案、
 * 用 {@code gsd_} 停用，也可以新增静态特征。{@link #audit(Meta)} 用出厂特征，
 * {@link #audit(Meta, GuardFeatureSet)} 用当前生效特征（云控接入走后者）。
 */
public final class GuardApkAudit {

    private GuardApkAudit() {
    }

    /** 体检结论三档。 */
    public enum Level { CLEAN, SUSPICIOUS, DANGEROUS }

    /** 从 APK 里抽出来的事实（Android 层由 {@link GuardApkReader} 填充，测试直接构造）。 */
    public static final class Meta {
        public String pkg;
        public String label;
        public String signerCn;
        public long manifestBytes;
        public int entryCount;
        public int dexEntryCount;
        /** 不在根目录、键名伪装成别的文件的 DEX 数量（隐藏载荷）。 */
        public int hiddenDexCount;
        public int resEntryCount;
        public int componentCount;
        public boolean hasScrcpy;
        public boolean hasSpake2;
        public boolean zipUnreadable;
        public boolean manifestUnparsable;
        public boolean a11yService;
        public boolean notificationListenerService;
        public boolean vpnService;
        public boolean deviceAdminReceiver;
        public boolean mediaProjection;
        public boolean requestDelete;
        public boolean overlay;
        public boolean boot;
        public boolean sms;
        public boolean contacts;
        public boolean recordAudio;
        public boolean camera;
        public boolean manageStorage;
        public boolean installPackages;
        public boolean readPhoneState;
        public final List<String> permissions = new ArrayList<>();
        /** 压缩包条目名（供云端静态特征 entry:/asset: 匹配；Reader 有上限地收集）。 */
        public final List<String> entries = new ArrayList<>();
        /** 组件全名（pkg/cls），供 component: 匹配。 */
        public final List<String> components = new ArrayList<>();
    }

    /** 一条命中项：权重 + 标题 + 给用户看的解释。 */
    public static final class Risk {
        public final int weight;
        public final String title;
        public final String detail;

        Risk(int weight, String title, String detail) {
            this.weight = weight;
            this.title = title;
            this.detail = detail;
        }
    }

    /** 体检结果。 */
    public static final class Report {
        public final int score;
        public final Level level;
        public final List<Risk> risks;

        Report(int score, Level level, List<Risk> risks) {
            this.score = score;
            this.level = level;
            this.risks = risks;
        }

        public String headline() {
            switch (level) {
                case DANGEROUS:
                    return "高危 —— 强烈建议不要安装";
                case SUSPICIOUS:
                    return "可疑 —— 不建议安装，除非你完全确认来源";
                default:
                    return "未见明显恶意特征 —— 仍不代表绝对安全";
            }
        }

        /** 给弹窗 / 剪贴板用的完整文本。 */
        public String report(Meta m) {
            StringBuilder sb = new StringBuilder();
            sb.append("体检结论：").append(headline())
                    .append("（风险分 ").append(score).append("/100）\n");
            if (m != null) {
                sb.append("包名：").append(nullTo(m.pkg, "解析不到"))
                        .append("　显示名：").append(nullTo(m.label, "解析不到")).append('\n');
                sb.append("签名：").append(nullTo(m.signerCn, "解析不到"))
                        .append("　清单：").append(manifestText(m.manifestBytes))
                        .append("　条目：").append(m.entryCount)
                        .append("　DEX：").append(m.dexEntryCount)
                        .append("（隐藏 ").append(m.hiddenDexCount).append("）\n");
            }
            if (risks.isEmpty()) {
                sb.append("命中项：无\n");
            } else {
                sb.append("命中 ").append(risks.size()).append(" 项：\n");
                for (Risk r : risks) {
                    sb.append("• [").append(r.weight).append("] ")
                            .append(r.title).append(" —— ").append(r.detail).append('\n');
                }
            }
            sb.append("说明：本体检只看结构与权限面，不执行代码，也不代替在线查毒；"
                    + "未命中不等于安全。");
            return sb.toString();
        }
    }

    /** 出厂特征体检（无云控配置时与历史行为完全一致）。 */
    public static Report audit(Meta m) {
        return audit(m, GuardFeatureSet.builtin());
    }

    /** 跑一遍规则：内置项受特征集的「停用/改分/改文案」影响，最后叠加云端新增静态特征。 */
    public static Report audit(Meta m, GuardFeatureSet fs) {
        if (fs == null) {
            fs = GuardFeatureSet.builtin();
        }
        List<Risk> risks = new ArrayList<>();
        int total = 0;
        if (m == null) {
            return new Report(0, Level.CLEAN, risks);
        }

        // ---- 结构性证据（正常安装包不会有） ----
        if (m.hasScrcpy) {
            total += add(fs, risks, GuardFeatureSet.ST_SCRCPY, 50,
                    "内置 scrcpy 远控服务",
                    "安装包里带着 scrcpy server。它配合无线调试可以把屏幕实时镜像到远端并注入键鼠，"
                            + "正常视频/天气类应用没有任何理由内置它。", null);
        }
        if (m.hasSpake2) {
            total += add(fs, risks, GuardFeatureSet.ST_SPAKE2, 40,
                    "内置 ADB 无线调试配对库（SPAKE2）",
                    "SPAKE2 是 ADB 无线调试配对专用算法。应用自己实现配对，等于准备自己拿 ADB shell 权限。", null);
        }
        if (m.hiddenDexCount > 0) {
            total += add(fs, risks, GuardFeatureSet.ST_HIDDEN_DEX, 40,
                    "隐藏 DEX 载荷",
                    "有 {v} 个 DEX 文件的路径伪装成 classes.dex / 资源文件等其它条目，"
                            + "是加壳/反分析的典型手法；真实代码在运行期才解密加载。",
                    String.valueOf(m.hiddenDexCount));
        }
        if (m.zipUnreadable) {
            total += add(fs, risks, GuardFeatureSet.ST_ZIP, 30,
                    "压缩包无法正常解析",
                    "APK 中央目录异常，常规检查工具读不全。这本身可能是反分析构造。", null);
        }
        if (m.manifestBytes > 2_000_000L) {
            total += add(fs, risks, GuardFeatureSet.ST_MANIFEST_BOMB, 40,
                    "清单体积异常（清单炸弹）",
                    "AndroidManifest.xml 解压后 {v}。正常应用通常只有几百 KB 以内，超大清单用于撑爆解析器、干扰扫描。",
                    manifestText(m.manifestBytes));
        } else if (m.manifestBytes > 500_000L) {
            total += add(fs, risks, GuardFeatureSet.ST_MANIFEST_LARGE, 15,
                    "清单体积偏大",
                    "AndroidManifest.xml 解压后 {v}，高于常见水平。", manifestText(m.manifestBytes));
        }
        if (m.manifestUnparsable) {
            total += add(fs, risks, GuardFeatureSet.ST_MANIFEST_UNPARSABLE, 25,
                    "清单无法解析",
                    "系统包管理器读不出这个 APK 的清单（可能被刻意构造）。装前无法确认它到底申请了什么。", null);
        }
        if (m.dexEntryCount >= 8) {
            total += add(fs, risks, GuardFeatureSet.ST_DEX_COUNT, 10,
                    "DEX 数量异常", "共 {v} 个 DEX 文件，显著多于常规应用；常见于加壳或多阶段载荷。",
                    String.valueOf(m.dexEntryCount));
        }
        if (m.resEntryCount > 500) {
            total += add(fs, risks, GuardFeatureSet.ST_RES_HUGE, 15,
                    "资源条目异常膨胀", "资源目录条目 {v} 个，疑似用大量随机资源名干扰静态分析。",
                    String.valueOf(m.resEntryCount));
        } else if (m.resEntryCount > 150) {
            total += add(fs, risks, GuardFeatureSet.ST_RES_MANY, 10,
                    "资源条目偏多", "资源目录条目 {v} 个。", String.valueOf(m.resEntryCount));
        }

        // ---- 高价值组件 ----
        if (m.a11yService) {
            total += add(fs, risks, GuardFeatureSet.ST_A11Y, 25,
                    "声明无障碍服务",
                    "清单里挂了 BIND_ACCESSIBILITY_SERVICE。它能读屏、模拟点击，是远控与自动操作的入口；"
                            + "装前就该确认这个应用是否真的需要它。", null);
        }
        if (m.notificationListenerService) {
            total += add(fs, risks, GuardFeatureSet.ST_NOTIFY, 20,
                    "声明通知监听服务", "能读取全部通知（含短信验证码、银行动账、社交消息）。", null);
        }
        if (m.vpnService) {
            total += add(fs, risks, GuardFeatureSet.ST_VPN, 20,
                    "声明 VPN 服务", "能把整机流量经自己转发，具备中间人式劫持能力。", null);
        }
        if (m.deviceAdminReceiver) {
            total += add(fs, risks, GuardFeatureSet.ST_ADMIN, 20,
                    "声明设备管理员", "可锁屏、清数据、改密码、阻止卸载，是勒索与反卸载的常用入口。", null);
        }

        // ---- 危险权限组合（单条不算，按数量计） ----
        int combo = 0;
        if (m.mediaProjection) combo++;
        if (m.requestDelete) combo++;
        if (m.overlay) combo++;
        if (m.manageStorage) combo++;
        if (m.sms) combo++;
        if (m.contacts) combo++;
        if (m.recordAudio) combo++;
        if (m.camera) combo++;
        if (m.installPackages) combo++;
        if (m.boot) combo++;
        if (combo >= 4) {
            total += add(fs, risks, GuardFeatureSet.ST_COMBO_MANY, 30,
                    "危险权限组合（{v} 项）",
                    "同时申请投屏/删包/悬浮窗/存储/短信/通讯录/录音/相机/装包/开机自启中的 {v} 项。"
                            + "正常应用很少需要这样一套采集面。", String.valueOf(combo));
        } else if (combo >= 2) {
            total += add(fs, risks, GuardFeatureSet.ST_COMBO_SOME, 15,
                    "危险权限组合（{v} 项）",
                    "同时申请 {v} 项高危能力，需结合应用用途判断。", String.valueOf(combo));
        }
        if (m.requestDelete && m.mediaProjection) {
            total += add(fs, risks, GuardFeatureSet.ST_DELETE_PROJECTION, 15,
                    "删包 + 投屏", "既能抓屏又能卸载其它应用：既能看屏幕，也能清掉取证/竞品应用。", null);
        }

        // ---- 签名 ----
        if (junkSigner(m.signerCn)) {
            total += add(fs, risks, GuardFeatureSet.ST_SIGNER, 15,
                    "签名证书可疑",
                    "证书主体是随机十六进制串（{v}），常见于自动化重打包与批量伪造。", m.signerCn);
        }

        // ---- 云端新增静态特征 ----
        for (GuardFeatureSet.Feature f : fs.customStatic()) {
            if (GuardFeatureSet.matchStatic(f, m)) {
                risks.add(new Risk(f.weight, f.title, f.detail));
                total += f.weight;
            }
        }

        if (m.pkg == null && m.label == null && total == 0) {
            // 什么都没读到（多半是选了非 APK 文件）：不下结论
            return new Report(0, Level.CLEAN, risks);
        }

        int score = Math.min(100, total);
        Level level = score >= 80 ? Level.DANGEROUS
                : (score >= 40 ? Level.SUSPICIOUS : Level.CLEAN);
        return new Report(score, level, risks);
    }

    /** 取当前生效的权重/标题/说明并追加一条；{@code {v}} 用 {@code value} 替换。 */
    private static int add(GuardFeatureSet fs, List<Risk> risks, String id, int defWeight,
                           String defTitle, String defDetail, String value) {
        if (!fs.enabled(id)) {
            return 0;
        }
        int w = fs.weight(id, defWeight);
        if (w <= 0) {
            return 0;
        }
        risks.add(new Risk(w, fill(fs.title(id, defTitle), value),
                fill(fs.detail(id, defDetail), value)));
        return w;
    }

    private static String fill(String s, String value) {
        return s == null ? null : s.replace("{v}", value == null ? "" : value);
    }

    private static boolean junkSigner(String cn) {
        if (cn == null) {
            return false;
        }
        String s = cn.toLowerCase(Locale.ROOT);
        // 真实证书常见 CN=公司名/个人名；批量重打包多为 CN=r<一串十六进制>
        return s.matches(".*cn=r[0-9a-f]{8,}.*");
    }

    private static String manifestText(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0);
    }

    private static String nullTo(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v;
    }
}
