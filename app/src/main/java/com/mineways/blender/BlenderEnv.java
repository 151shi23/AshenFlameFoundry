package com.mineways.blender;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Blender 集成环境的配置与目录布局。
 *
 * <p>整体思路（开源项目 + 移植，不把完整 Blender 内置进 APK）：</p>
 * <ol>
 *   <li>运行目录放在 {@code /data/local/tmp/mineways_blender}：Android 10+ 的应用私有目录是
 *       noexec，glibc 加载器和 Blender 可执行文件放进去必然 Permission denied；而
 *       {@code /data/local/tmp} 可执行、也能被 Shizuku 创建成 app 可写。</li>
 *   <li>sysroot：从 Debian 官方镜像只取「够跑 Blender」的那几百个库
 *       （glibc + X11 + GL/EGL + 可选的 Mesa 软渲染驱动），解成一棵纯 glibc 文件树。</li>
 *   <li>引擎：官方 {@code blender-x.y.z-linux-aarch64.tar.xz}，运行时下载后原样解压，
 *       由 sysroot 里的 {@code ld-linux-aarch64.so.1} 直接加载运行（不装 Termux、不用 chroot）。</li>
 * </ol>
 */
public final class BlenderEnv {

    private BlenderEnv() {
    }

    // ---------------------------------------------------------------- 默认配置（都可在页面里改）

    public static final String DEFAULT_VERSION = "4.2.3";
    public static final String DEFAULT_BLENDER_URL =
            "https://download.blender.org/release/Blender4.2/blender-4.2.3-linux-aarch64.tar.xz";
    /** 国内镜像（第一个是官方源，失败自动换下一个；用户也能在页面里粘贴自己的源）。 */
    public static final String DEFAULT_BLENDER_MIRRORS =
            "https://download.blender.org/release/Blender4.2/blender-4.2.3-linux-aarch64.tar.xz\n"
                    + "https://mirror.nju.edu.cn/blender/release/Blender4.2/blender-4.2.3-linux-aarch64.tar.xz\n"
                    + "https://mirrors.tuna.tsinghua.edu.cn/blender/release/Blender4.2/blender-4.2.3-linux-aarch64.tar.xz";
    public static final String DEFAULT_DEB_MIRRORS =
            "https://deb.debian.org/debian\n"
                    + "https://mirrors.aliyun.com/debian\n"
                    + "https://mirrors.ustc.edu.cn/debian";
    public static final String DEFAULT_DEB_SUITE = "bookworm";

    /**
     * Blender 官方 Linux 包的外部依赖（它自己 bundle 了 png/jpeg/openexr/zstd 等，
     * 但 X11 / GL / EGL / xkbcommon 这些是系统库），这里给出顶层包，依赖由索引自动闭包。
     */
    public static final String[] PKGS_CORE = {
            "libc6",            // glibc（含 ld-linux-aarch64.so.1）
            "libgcc-s1",
            "libstdc++6",
            "libx11-6",
            "libx11-xcb1",
            "libxext6",
            "libxi6",
            "libxfixes3",
            "libxrender1",
            "libxxf86vm1",
            "libxcb1",
            "libxkbcommon0",
            "libsm6",
            "libice6",
            "libgl1",
            "libglx0",
            "libegl1",
            "libglvnd0",
            "libglapi-mesa",
            "zlib1g",
    };

    /** 软件 OpenGL（llvmpipe）：EEVEE / Workbench / 材质预览需要，Cycles CPU 不需要。 */
    public static final String[] PKGS_SOFTGL = {
            "libgl1-mesa-dri",
            "libglx-mesa0",
            "libegl-mesa0",
            "libgomp1",
    };

    /** 可选：Debian 的 ffmpeg 命令行（序列→视频、转码、抽帧、剪片段）。Blender 自带 FFmpeg 渲视频，但要做这些还得靠它。 */
    public static final String[] PKGS_VIDEO = {
            "ffmpeg",
    };

    /** 可选：给 Blender 自带 Python 装第三方包用的（pip / 构建工具）。 */
    public static final String[] PKGS_PYTHON = {
            "python3",
            "python3-pip",
    };

    /**
     * 可选：硬件加速（GPU）用的 Mesa Vulkan 栈。
     * Turnip（Adreno）/ PanVK 都在 mesa-vulkan-drivers 里，libvulkan1 是加载器，
     * Zink（GL→Vulkan）在已装的 libgl1-mesa-dri 里。
     */
    public static final String[] PKGS_GPU = {
            "mesa-vulkan-drivers",
            "libvulkan1",
    };

    /**
     * soname → Debian 包名。用于「启动失败自愈」：{@code blender --version} 报
     * {@code error while loading shared libraries: libXxx.so.N} 时按这里补装。
     * 映射不全也没关系，{@link #packageCandidates} 还有通用规则兜底。
     */
    private static final String[][] LIB_TO_PKG = {
            {"libX11.so.6", "libx11-6"},
            {"libX11-xcb.so.1", "libx11-xcb1"},
            {"libXext.so.6", "libxext6"},
            {"libXi.so.6", "libxi6"},
            {"libXxf86vm.so.1", "libxxf86vm1"},
            {"libXxf86dri.so.3", "libxxf86dri1"},
            {"libXfixes.so.3", "libxfixes3"},
            {"libXrender.so.1", "libxrender1"},
            {"libXinerama.so.1", "libxinerama1"},
            {"libXrandr.so.2", "libxrandr2"},
            {"libXcursor.so.1", "libxcursor1"},
            {"libGL.so.1", "libgl1"},
            {"libGLX.so.0", "libglx0"},
            {"libGLdispatch.so.0", "libglvnd0"},
            {"libEGL.so.1", "libegl1"},
            {"libOpenGL.so.0", "libopengl0"},
            {"libxkbcommon.so.0", "libxkbcommon0"},
            {"libxkbcommon-x11.so.0", "libxkbcommon-x11-0"},
            {"libwayland-client.so.0", "libwayland-client0"},
            {"libwayland-egl.so.1", "libwayland-egl1"},
            {"libwayland-cursor.so.0", "libwayland-cursor0"},
            {"libSM.so.6", "libsm6"},
            {"libICE.so.6", "libice6"},
            {"libopenal.so.1", "libopenal1"},
            {"libstdc++.so.6", "libstdc++6"},
            {"libgcc_s.so.1", "libgcc-s1"},
            {"libz.so.1", "zlib1g"},
            {"libexpat.so.1", "libexpat1"},
            {"libdrm.so.2", "libdrm2"},
            {"libbz2.so.1", "libbz2-1.0"},
            {"libpng16.so.16", "libpng16-16"},
            {"libjpeg.so.62", "libjpeg62-turbo"},
            {"libtiff.so.6", "libtiff6"},
            {"libwebp.so.7", "libwebp7"},
            {"libfreetype.so.6", "libfreetype6"},
            {"libfontconfig.so.1", "libfontconfig1"},
            {"libuuid.so.1", "libuuid1"},
    };

    /** soname → 候选 Debian 包名（按可能性排序，第一个装不上就试下一个）。 */
    public static List<String> packageCandidates(String soname) {
        final List<String> out = new ArrayList<>();
        if (soname == null || soname.isEmpty()) return out;
        for (String[] m : LIB_TO_PKG) {
            if (m[0].equals(soname)) {
                out.add(m[1]);
                return out;
            }
        }
        final int so = soname.indexOf(".so");
        if (so <= 0) return out;
        String stem = soname.substring(0, so);           // libX11 / libfoo
        final String ver = soname.substring(so + 3);      // 1.2.3
        final int dot = ver.indexOf('.');
        final String major = dot > 0 ? ver.substring(0, dot) : ver;
        if (!major.matches("\\d+")) return out;
        if (stem.startsWith("lib") && stem.length() > 3) stem = stem.substring(3);
        final String head = Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
        final String minor = major.length() > 1 ? major.substring(1) : "";
        out.add(head + major);
        out.add(head + "-" + major);
        if (!minor.isEmpty()) {
            out.add(head + minor);
            out.add(head + "-" + minor);
        }
        out.add(head);
        return out;
    }

    // ---------------------------------------------------------------- 目录

    public static String root(Context c) {
        return prefs(c).getString("root", "/data/local/tmp/mineways_blender");
    }

    public static void setRoot(Context c, String v) {
        prefs(c).edit().putString("root", v.trim()).apply();
    }

    public static File dir(Context c) {
        return new File(root(c));
    }

    public static File dlDir(Context c) {
        return new File(root(c), "dl");
    }

    public static File debsDir(Context c) {
        return new File(root(c), "dl/debs");
    }

    public static File sysrootDir(Context c) {
        return new File(root(c), "sysroot");
    }

    public static File blenderDir(Context c) {
        return new File(root(c), "bl");
    }

    public static File workDir(Context c) {
        return new File(root(c), "work");
    }

    public static File outDir(Context c) {
        return new File(root(c), "out");
    }

    public static File homeDir(Context c) {
        return new File(root(c), "home");
    }

    public static File tmpDir(Context c) {
        return new File(root(c), "tmp");
    }

    /** 运行时全部子目录。 */
    public static List<File> allDirs(Context c) {
        return Arrays.asList(dir(c), dlDir(c), debsDir(c), sysrootDir(c), blenderDir(c),
                workDir(c), outDir(c), homeDir(c), tmpDir(c));
    }

    // ---------------------------------------------------------------- 架构

    /** Debian 架构名。 */
    public static String debArch() {
        final String[] abis = Build.SUPPORTED_ABIS;
        final String arch = (abis != null && abis.length > 0) ? abis[0] : "";
        if (arch.startsWith("arm64") || "aarch64".equals(arch)) return "arm64";
        if ("x86_64".equals(arch)) return "amd64";
        return arch.isEmpty() ? "arm64" : arch;
    }

    /** glibc triplet（sysroot 里的库目录名）。 */
    public static String triplet() {
        final String a = debArch();
        if ("arm64".equals(a)) return "aarch64-linux-gnu";
        if ("amd64".equals(a)) return "x86_64-linux-gnu";
        return a + "-linux-gnu";
    }

    /** 官方动态链接器文件名。 */
    public static String loaderName() {
        final String a = debArch();
        if ("arm64".equals(a)) return "ld-linux-aarch64.so.1";
        if ("amd64".equals(a)) return "ld-linux-x86-64.so.2";
        return "ld-linux-" + a + ".so.1";
    }

    // ---------------------------------------------------------------- 配置读写

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("blender_env", Context.MODE_PRIVATE);
    }

    public static String blenderMirrors(Context c) {
        return prefs(c).getString("blender_mirrors", DEFAULT_BLENDER_MIRRORS);
    }

    public static void setBlenderMirrors(Context c, String v) {
        prefs(c).edit().putString("blender_mirrors", v.trim()).apply();
    }

    public static String debMirrors(Context c) {
        return prefs(c).getString("deb_mirrors", DEFAULT_DEB_MIRRORS);
    }

    public static void setDebMirrors(Context c, String v) {
        prefs(c).edit().putString("deb_mirrors", v.trim()).apply();
    }

    public static String debSuite(Context c) {
        return prefs(c).getString("deb_suite", DEFAULT_DEB_SUITE);
    }

    public static void setDebSuite(Context c, String v) {
        prefs(c).edit().putString("deb_suite", v.trim()).apply();
    }

    /** 把多行文本拆成非空的候选地址列表。 */
    public static List<String> lines(String text) {
        final List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split("\\r?\\n")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 拼出跑 Blender 需要的 export 前缀（宿主、渲染、模板生成共用一份，避免各写各的）。 */
    public static String envPrefix(Context c) {
        final File top = blenderTop(c);
        final String libPath = libPath(c) + ":" + (top == null ? "" : top.getAbsolutePath() + "/lib");
        return "export LD_LIBRARY_PATH=" + libPath + "\n"
                + "export HOME=" + homeDir(c).getAbsolutePath() + "\n"
                + "export TMPDIR=" + tmpDir(c).getAbsolutePath() + "\n"
                + "export LANG=C.UTF-8 LC_ALL=C.UTF-8\n"
                + "export PYTHONDONTWRITEBYTECODE=1\n";
    }

    /** 软渲染相关环境变量（EEVEE / Workbench 用；Cycles CPU 不需要）。 */
    public static String softGlEnv() {
        return "export EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n"
                + "export MESA_GL_VERSION_OVERRIDE=4.5 MESA_GLSL_VERSION_OVERRIDE=450\n";
    }

    // ---------------------------------------------------------------- GPU 加速开关

    /** 加速模式：auto / soft / hw / custom。 */
    public static String accelMode(Context c) {
        return prefs(c).getString("accel_mode", "auto");
    }

    public static void setAccelMode(Context c, String mode) {
        prefs(c).edit().putString("accel_mode", mode).apply();
    }

    /** 自定义驱动 env（用户手填，用于 Mali 的 panfork / panvk-kbase 之类）。 */
    public static String customGpuEnv(Context c) {
        return prefs(c).getString("gpu_custom_env", "");
    }

    public static void setCustomGpuEnv(Context c, String v) {
        prefs(c).edit().putString("gpu_custom_env", v == null ? "" : v.trim()).apply();
    }

    /** 上次探测结论：是否找到可用硬件组合、叫什么名字、对应 env。 */
    public static boolean gpuProbeHw(Context c) {
        return prefs(c).getBoolean("gpu_hw_ok", false);
    }

    public static String gpuProbeName(Context c) {
        return prefs(c).getString("gpu_hw_name", "");
    }

    public static String gpuProbeEnv(Context c) {
        return prefs(c).getString("gpu_hw_env", "");
    }

    public static void setGpuProbeResult(Context c, boolean hwOk, String name, String env) {
        prefs(c).edit()
                .putBoolean("gpu_hw_ok", hwOk)
                .putString("gpu_hw_name", name == null ? "" : name)
                .putString("gpu_hw_env", env == null ? "" : env)
                .apply();
    }

    /**
     * 真正要注入给 Blender 的图形环境变量。
     *
     * @param engine 渲染引擎（Cycles CPU 不需要 GL，直接给软件空跑）
     */
    public static String gpuEnv(Context c, String engine, boolean prefersGl) {
        final String mode = accelMode(c);
        if (!prefersGl) {
            // Cycles CPU / 纯计算：GL 可有可无，给最稳的软件配置免得初始化卡住
            return softGlEnv();
        }
        switch (mode) {
            case "soft":
                return softGlEnv();
            case "custom": {
                final String custom = customGpuEnv(c);
                if (custom != null && !custom.trim().isEmpty()) {
                    final StringBuilder sb = new StringBuilder("export " + custom.trim() + "\n");
                    if (!custom.contains("EGL_PLATFORM")) {
                        sb.append("export EGL_PLATFORM=surfaceless\n");
                    }
                    return sb.toString();
                }
                return softGlEnv();
            }
            case "hw":
            case "auto":
            default: {
                final String env = gpuProbeEnv(c);
                if (gpuProbeHw(c) && env != null && !env.isEmpty()) {
                    return "export " + env + "\n"
                            + "export EGL_PLATFORM=surfaceless\n";
                }
                return softGlEnv();
            }
        }
    }

    /** sysroot 剩余空间（KB，-1 表示拿不到）。走 shell 是因为 app 可能读不到那个挂载点的 statfs。 */
    public static long freeSpaceKb(Context c) {
        final File dir = dir(c);
        if (!dir.isDirectory()) return -1L;
        final RootShell.Result r = RootShell.sh("df -k " + RootShell.q(dir.getAbsolutePath()) + " 2>/dev/null | tail -1");
        final String[] parts = r.out.trim().split("\\s+");
        if (parts.length < 4) return -1L;
        try {
            for (int i = parts.length - 1; i >= 0; i--) {
                final long v = Long.parseLong(parts[i]);
                if (v > 1024L) return v;   // 最后一个大数就是可用块（1K-blocks）
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /** 系统可用内存（KB，-1 = 拿不到）。 */
    public static long availableMemoryKb() {
        final RootShell.Result r = RootShell.sh("grep -m1 MemAvailable /proc/meminfo 2>/dev/null");
        final String out = r.out.trim();
        if (out.isEmpty()) return -1L;
        final String[] parts = out.split("\\s+");
        if (parts.length < 2) return -1L;
        try {
            return Long.parseLong(parts[1]);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** CPU 逻辑核心数（至少 1）。 */
    public static int cpuCores() {
        final RootShell.Result r = RootShell.sh("grep -c ^processor /proc/cpuinfo 2>/dev/null");
        try {
            final int n = Integer.parseInt(r.out.trim());
            return n > 0 ? n : 1;
        } catch (Throwable t) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    // ---------------------------------------------------------------- 安装状态

    public static boolean installed(Context c) {
        return !installedVersion(c).isEmpty();
    }

    public static String installedVersion(Context c) {
        return prefs(c).getString("installed_version", "");
    }

    /** sysroot 里的动态链接器绝对路径（安装时解析并记下）。 */
    public static String loaderPath(Context c) {
        return prefs(c).getString("loader", "");
    }

    /** Blender 可执行文件绝对路径。 */
    public static String blenderBin(Context c) {
        return prefs(c).getString("blender_bin", "");
    }

    /** 软渲染（llvmpipe）自检是否通过。 */
    public static boolean softGlOk(Context c) {
        return prefs(c).getBoolean("softgl", false);
    }

    // ---------------------------------------------------------------- 可选组件 / 引擎内部路径

    /** Blender 安装根（可执行文件所在那层）。 */
    public static File blenderTop(Context c) {
        final String bin = blenderBin(c);
        if (bin.isEmpty()) return null;
        final File f = new File(bin).getParentFile();
        return f != null && f.isDirectory() ? f : null;
    }

    /** 官方发行包自带的模板/启动文件目录（没有就返回 null，UI 自动隐藏这一节）。 */
    public static File templatesDir(Context c) {
        final File top = blenderTop(c);
        if (top == null) return null;
        final File a = new File(top, "release/datafiles/templates");
        if (a.isDirectory()) return a;
        final File p = top.getParentFile();
        if (p == null) return null;
        final File b = new File(p, "release/datafiles/templates");
        return b.isDirectory() ? b : null;
    }

    /** Blender 自带 Python（4.x 落在 python/bin/python3.x）。 */
    public static File bundledPython(Context c) {
        final File top = blenderTop(c);
        if (top == null) return null;
        final File bin = new File(top, "python/bin");
        final File[] kids = bin.listFiles();
        if (kids != null) {
            for (File k : kids) {
                final String n = k.getName();
                if (k.isFile() && (n.startsWith("python3") || n.equals("python"))) return k;
            }
        }
        return null;
    }

    /** sysroot 里的可执行文件（ffmpeg 等 Debian 包装的）。 */
    public static File sysrootBin(Context c, String name) {
        final File s = sysrootDir(c);
        final File a = new File(s, "usr/bin/" + name);
        if (a.isFile()) return a;
        final File b = new File(s, "bin/" + name);
        return b.isFile() ? b : null;
    }

    public static boolean extraInstalled(Context c, String key) {
        return prefs(c).getBoolean("extra_" + key, false);
    }

    public static void markExtra(Context c, String key, boolean on) {
        prefs(c).edit().putBoolean("extra_" + key, on).apply();
    }

    public static void markInstalled(Context c, String version, String loader, String bin, boolean softGl) {
        prefs(c).edit()
                .putString("installed_version", version)
                .putString("loader", loader)
                .putString("blender_bin", bin)
                .putBoolean("softgl", softGl)
                .apply();
    }

    public static void clearInstalled(Context c) {
        prefs(c).edit()
                .remove("installed_version")
                .remove("loader")
                .remove("blender_bin")
                .remove("softgl")
                .apply();
    }

    /** 库搜索路径：sysroot 的多级目录 + Blender 自带 lib（顺序很重要，见 BlenderRenderer 注释）。 */
    public static String libPath(Context c) {
        final File s = sysrootDir(c);
        final String t = triplet();
        return s + "/usr/lib/" + t + ":"
                + s + "/lib/" + t + ":"
                + s + "/usr/lib:"
                + s + "/lib";
    }
}
