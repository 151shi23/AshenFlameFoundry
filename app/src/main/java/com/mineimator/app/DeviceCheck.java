package com.mineimator.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 开工前的能力评估。
 *
 * <p><b>设计原则：这一环本身绝不能崩。</b>每个探针独立 try/catch，任何探针抛异常都只当"未知"
 * （不拦人、按兼容模式放行）；整个评估再套一层兜底。宁可放过，不可误炸。
 *
 * <p>结论分两档：
 * <ul>
 *   <li><b>blockers</b>：硬性不达标（CPU 架构 / OpenGL ES 版本 / 存储空间），进去必死或必然中途失败 → 明确报错；</li>
 *   <li><b>warnings</b>：能跑但吃力（内存、核心数、系统版本、屏幕尺寸）→ 提示 + 保守适配，用户执意进入也照跑。</li>
 * </ul>
 */
public final class DeviceCheck {

    /** 内核（libmineimator.so）是按 Android 8.0 / API 26 编译的。 */
    public static final int MIN_API = 26;

    public static final class Report {
        public final List<String> blockers = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** 走保守适配（低内存/老机/用户执意进入）。 */
        public boolean lowProfile;

        public boolean ok() {
            return blockers.isEmpty();
        }

        public boolean needsNotice() {
            return !ok() || !warnings.isEmpty();
        }
    }

    private DeviceCheck() {
    }

    /** 评估（带总兜底，绝不抛异常）。 */
    public static Report runSafely(Activity activity) {
        final Report r = new Report();
        try {
            checkAbi(activity, r);
        } catch (Throwable t) {
            r.warnings.add("架构检测异常（已忽略）：" + t);
        }
        try {
            checkGles(activity, r);
        } catch (Throwable t) {
            r.warnings.add("图形能力检测异常（已忽略）：" + t);
        }
        try {
            checkMemory(activity, r);
        } catch (Throwable t) {
            r.warnings.add("内存检测异常（已忽略）：" + t);
            r.lowProfile = true;
        }
        try {
            checkCpu(r);
        } catch (Throwable t) {
            r.warnings.add("CPU 检测异常（已忽略）：" + t);
        }
        try {
            checkStorage(activity, r);
        } catch (Throwable t) {
            r.warnings.add("存储检测异常（已忽略）：" + t);
        }
        try {
            checkSystem(r);
        } catch (Throwable t) {
            r.warnings.add("系统版本检测异常（已忽略）：" + t);
        }
        try {
            checkScreen(activity, r);
        } catch (Throwable t) {
            r.warnings.add("屏幕检测异常（已忽略）：" + t);
        }
        return r;
    }

    // ------------------------------------------------------------------ 探针

    private static void checkAbi(Activity activity, Report r) {
        boolean arm64 = false;
        final String[] abis = (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0)
                ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String a : abis) {
                if ("arm64-v8a".equals(a)) {
                    arm64 = true;
                    break;
                }
            }
        }
        final File lib = new File(activity.getApplicationInfo().nativeLibraryDir, "libmineimator.so");
        if (!arm64) {
            r.blockers.add("CPU 架构：本机是 " + Arrays.toString(abis)
                    + "，动画内核只有 arm64-v8a（64 位 ARM）版本。");
        } else if (!lib.isFile()) {
            r.blockers.add("内核文件缺失：安装包可能没装全（需要 64 位 ARM 版），建议卸载后重新安装。");
        }
    }

    private static void checkGles(Activity activity, Report r) {
        final ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        final ConfigurationInfo info = am.getDeviceConfigurationInfo();
        if (info == null) {
            return;
        }
        if (info.reqGlEsVersion < 0x30000) {
            r.blockers.add("图形能力：本机 OpenGL ES 版本为 0x"
                    + Integer.toHexString(info.reqGlEsVersion) + "，内核需要 OpenGL ES 3.0 及以上。");
        } else if (info.reqGlEsVersion < 0x30002) {
            r.warnings.add("图形驱动较旧（OpenGL ES "
                    + ((info.reqGlEsVersion >> 16)) + "." + (info.reqGlEsVersion & 0xFFFF)
                    + "），复杂场景可能掉帧或闪退。");
            r.lowProfile = true;
        }
    }

    private static void checkMemory(Activity activity, Report r) {
        final ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        final long total = mi.totalMem;
        if (total <= 0) {
            return;
        }
        final long gb1 = 1024L * 1024 * 1024;
        if (total < 2 * gb1) {
            r.warnings.add("内存偏小（约 " + (total / gb1) + " GB）：动画渲染吃内存，"
                    + "建议分辨率用 720p、渲染设置选「性能」预设。");
            r.lowProfile = true;
        } else if (total < 3 * gb1) {
            r.warnings.add("内存约 " + (total / gb1) + " GB：4K 导出和大场景可能失败，建议 1080p。");
            r.lowProfile = true;
        }
    }

    private static void checkCpu(Report r) {
        final int cores = Runtime.getRuntime().availableProcessors();
        if (cores <= 4) {
            r.warnings.add("CPU 核心数 " + cores + " 个：渲染较慢，建议用「性能」预设并降低分辨率。");
            r.lowProfile = true;
        }
    }

    private static void checkStorage(Activity activity, Report r) {
        final File dir = activity.getFilesDir();
        if (dir == null) {
            return;
        }
        long free;
        try {
            final StatFs stat = new StatFs(dir.getAbsolutePath());
            free = stat.getAvailableBytes();
        } catch (Throwable t) {
            final StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            free = stat.getAvailableBytes();
        }
        final long mb = 1024L * 1024;
        if (free < 200 * mb) {
            r.blockers.add("可用空间只剩 " + (free / mb) + " MB：内核要解包 38 MB 资源、渲染还要写文件，"
                    + "请先清理出至少 700 MB 再进。");
        } else if (free < 700 * mb) {
            r.warnings.add("可用空间偏紧（" + (free / mb) + " MB）：导出长视频可能中途失败，建议先清理。");
        }
    }

    private static void checkSystem(Report r) {
        final int sdk = Build.VERSION.SDK_INT;
        if (sdk < MIN_API) {
            r.blockers.add("系统版本 Android " + sdk + " 低于内核要求（Android 8.0 / API " + MIN_API + "）。");
        } else if (sdk <= 28) {
            r.warnings.add("系统较旧（Android " + sdk + "）：已自动切到保守适配。");
            r.lowProfile = true;
        }
    }

    private static void checkScreen(Activity activity, Report r) {
        final DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int sw = activity.getResources().getConfiguration().smallestScreenWidthDp;
        if (sw > 0 && sw < 480) {
            r.warnings.add("屏幕较小（最小宽度 " + sw + "dp，约 " + dm.widthPixels + "×" + dm.heightPixels
                    + "）：界面会很挤，横屏 + 双指缩放使用更好。");
        }
    }

    // ------------------------------------------------------------------ 文案

    public static String blockersText(Report r) {
        final StringBuilder sb = new StringBuilder("这台设备暂时跑不了动画工作室：\n\n");
        for (String s : r.blockers) {
            sb.append("• ").append(s).append('\n');
        }
        sb.append("\n如果只是空间不足，清理后重进即可；架构与图形能力的限制无法绕过。");
        return sb.toString();
    }

    public static String warningsText(Report r) {
        final StringBuilder sb = new StringBuilder("你的设备可以运行，但属于「吃力」一档：\n\n");
        for (String s : r.warnings) {
            sb.append("• ").append(s).append('\n');
        }
        sb.append("\n仍然进入的话，我们会自动开启保守适配：\n")
                .append("• 画面缓冲降到 16 位色（省内存、省带宽）\n")
                .append("• 渲染预设优先「性能」\n")
                .append("• 导出建议 720p / 1080p，避免 4K\n\n")
                .append("要现在就进去吗？");
        return sb.toString();
    }
}
