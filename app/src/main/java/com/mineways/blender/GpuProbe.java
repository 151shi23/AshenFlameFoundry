package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * GPU 硬件加速探测。
 *
 * <p>Android 上能用的硬件加速只有一条主线：Vulkan。桌面级 OpenGL（EEVEE 要求 GL 4.3+）
 * 只能靠 Zink（把 GL 翻译成 Vulkan）+ Mesa 的 Vulkan 驱动：Adreno 用 Turnip
 * （走 /dev/kgsl-3d0），Mali 用 Panfrost/PanVK（走 /dev/mali0）。</p>
 *
 * <p>厂商自带的 Vulkan 驱动是 bionic 库，没法直接给 glibc 的 Blender 用，
 * 所以要让 Mesa 自己去对话内核驱动，就得能访问 /dev/kgsl-3d0 这类节点，
 * 这也正是本项目 Shizuku 提权的前提。</p>
 *
 * <p>Cycles 不受益：Blender 3.0 之后 Cycles 没有 OpenCL，手机上只能 CPU 跑。
 * 硬件加速的收益点是 EEVEE / Workbench。</p>
 */
public final class GpuProbe {

    private GpuProbe() {
    }

    /** 静态探测结果（不跑渲染，秒回）。 */
    public static final class Info {
        public String soc = "";
        public String hardware = "";
        public String gpuModel = "";
        public String devices = "";
        public String icd = "";
        public String driDrivers = "";
        public String recommend = "";
        public boolean hasKgsl;
        public boolean hasMali;
        public boolean hasDri;

        public String summary() {
            final StringBuilder sb = new StringBuilder();
            sb.append("SoC/平台：").append(soc).append(" · 硬件：").append(hardware).append('\n');
            sb.append("GPU：").append(gpuModel.isEmpty() ? "未识别" : gpuModel).append('\n');
            sb.append("内核设备：").append(devices.isEmpty() ? "什么都没看到（没 root？）" : devices).append('\n');
            sb.append("sysroot 的 Vulkan ICD：").append(icd.isEmpty() ? "无（要装 Mesa Vulkan 驱动）" : icd).append('\n');
            sb.append("sysroot 的 DRI 驱动：").append(driDrivers.isEmpty() ? "无" : driDrivers).append('\n');
            sb.append("建议：").append(recommend);
            return sb.toString();
        }
    }

    /** 一次候选环境的试跑结果。 */
    public static final class ProbeResult {
        public final String name;
        public boolean ok;
        public String renderer = "";
        public String version = "";
        public String log = "";

        ProbeResult(String name) {
            this.name = name;
        }

        public String line() {
            return ok ? (name + " → 可用：" + renderer + " / GL " + version)
                    : (name + " → 不可用：" + firstLine(log));
        }
    }

    static String firstLine(String s) {
        if (s == null || s.trim().isEmpty()) return "无输出";
        for (String line : s.split("\\r?\\n")) {
            final String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "无输出";
    }

    // ---------------------------------------------------------------- 静态探测

    public static Info detect(Context c) {
        final Info info = new Info();
        info.soc = RootShell.sh("getprop ro.soc.model; getprop ro.board.platform").out.replace("\n", " ").trim();
        info.hardware = RootShell.sh("getprop ro.hardware").out.trim();
        final StringBuilder dev = new StringBuilder();
        check(info, dev, "/dev/kgsl-3d0", true);
        check(info, dev, "/dev/mali0", false);
        final String dri = RootShell.sh("ls -1 /dev/dri 2>/dev/null | tr '\\n' ' '").out.trim();
        if (!dri.isEmpty()) {
            info.hasDri = true;
            dev.append("/dev/dri/ ").append(dri).append(' ');
        }
        info.devices = dev.toString().trim();
        info.gpuModel = RootShell.sh("cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null; "
                + "cat /sys/class/kgsl/kgsl-3d0/device/gpu_model 2>/dev/null; "
                + "cat /sys/class/misc/mali0/device/gpuinfo 2>/dev/null; "
                + "getprop ro.hardware.egl").out.trim().replace("\n", " ");

        info.icd = listNames(new File(BlenderEnv.sysrootDir(c), "usr/share/vulkan/icd.d"), ".json");
        info.driDrivers = listNames(new File(BlenderEnv.sysrootDir(c),
                "usr/lib/" + BlenderEnv.triplet() + "/dri"), "_dri.so");

        if (info.hasKgsl) {
            info.recommend = "Adreno 设备：装「Mesa Vulkan 驱动」后选「硬件 Turnip + Zink」。";
        } else if (info.hasMali) {
            info.recommend = "Mali 设备：Debian 的 panfrost 走不通常见的 Android kbase 内核，"
                    + "需要外部构建（panfork / panvk-kbase-android），用「自定义」把驱动塞进 sysroot。";
        } else if (info.hasDri) {
            info.recommend = "有 DRM 渲染节点：可以试「硬件 Turnip + Zink」或自定义 GALLIUM_DRIVER。";
        } else {
            info.recommend = "没看到 GPU 内核节点：先确认 Shizuku 是 root 模式；"
                    + "否则只能软件渲染（Cycles CPU 本来也不需要 GPU）。";
        }
        return info;
    }

    private static void check(Info info, StringBuilder dev, String path, boolean kgsl) {
        if (RootShell.sh("test -e " + RootShell.q(path) + " && echo yes").out.contains("yes")) {
            dev.append(path).append(' ');
            if (kgsl) info.hasKgsl = true;
            else info.hasMali = true;
        }
    }

    private static String listNames(File dir, String suffix) {
        final StringBuilder sb = new StringBuilder();
        final File[] kids = dir.listFiles();
        if (kids == null) return "";
        for (File f : kids) {
            if (f.getName().endsWith(suffix)) sb.append(f.getName()).append(' ');
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 试跑探针

    /** 候选环境（名字 → 额外 env）。 */
    public static List<String[]> candidates(Context c) {
        final List<String[]> out = new java.util.ArrayList<>();
        final String icd = freedrenoIcd(c);
        final String base = "EGL_PLATFORM=surfaceless ";
        out.add(new String[]{"软件 llvmpipe（基准）",
                base + "LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe"});
        if (!icd.isEmpty()) {
            out.add(new String[]{"硬件 Turnip + Zink（Adreno）",
                    base + "GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink "
                            + "VK_ICD_FILENAMES=" + icd + " "
                            + "MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460"});
        }
        out.add(new String[]{"硬件 freedreno 直连（Adreno，GLES 上限）",
                base + "GALLIUM_DRIVER=freedreno MESA_LOADER_DRIVER_OVERRIDE=freedreno"});
        out.add(new String[]{"硬件 panfrost（Mali）",
                base + "GALLIUM_DRIVER=panfrost MESA_LOADER_DRIVER_OVERRIDE=panfrost"});
        final String custom = BlenderEnv.customGpuEnv(c);
        if (custom != null && !custom.trim().isEmpty()) {
            out.add(new String[]{"自定义", custom.trim()});
        }
        return out;
    }

    /** 在给定 env 下跑一次 Blender，问它拿到了什么 GL/GPU。 */
    public static ProbeResult probe(Context c, String name, String env, BlenderOps.Log log) {
        final ProbeResult r = new ProbeResult(name);
        final String loader = BlenderEnv.loaderPath(c);
        final String bin = BlenderEnv.blenderBin(c);
        if (loader.isEmpty() || bin.isEmpty()) {
            r.log = "Blender 未安装";
            return r;
        }
        final File script = new File(BlenderEnv.tmpDir(c), "mw_gpu.py");
        try {
            FileUtil.writeText(script, "import sys\n"
                    + "try:\n"
                    + "    import gpu\n"
                    + "    print(\"MW_GPU_OK \" + str(gpu.platform.renderer_get()) + \"|\" + str(gpu.platform.version_get()))\n"
                    + "except BaseException as e:\n"
                    + "    print(\"MW_GPU_FAIL \" + str(e))\n");
            FileUtil.makeExecutable(script);
        } catch (Throwable t) {
            r.log = "写探针脚本失败：" + t;
            return r;
        }
        final String cmd = BlenderEnv.envPrefix(c)
                + "export " + env + "\n"
                + "cd " + RootShell.q(BlenderEnv.tmpDir(c).getAbsolutePath()) + "\n"
                + "exec " + RootShell.q(loader)
                + " --library-path " + RootShell.q(BlenderEnv.libPath(c))
                + " " + RootShell.q(bin)
                + " --factory-startup -noaudio -b -P " + RootShell.q(script.getAbsolutePath())
                + " 2>&1 | tail -12\n";
        final RootShell.Result res = RootShell.sh(cmd);
        r.log = res.out;
        for (String line : res.out.split("\\r?\\n")) {
            if (line.startsWith("MW_GPU_OK")) {
                final String[] parts = line.substring("MW_GPU_OK".length()).trim().split("\\|");
                r.ok = true;
                r.renderer = parts.length > 0 ? parts[0].trim() : "";
                r.version = parts.length > 1 ? parts[1].trim() : "";
            }
        }
        if (log != null) log.onLog("· GPU 探针 [" + name + "] " + firstLine(res.out));
        return r;
    }

    /**
     * 逐个候选试跑，把能用的那个记进 prefs。
     *
     * @return 每个候选的结果（给用户看的表格）
     */
    public static List<ProbeResult> tryHardware(Context c, int timeoutSec, BlenderOps.Log log) {
        final List<ProbeResult> results = new java.util.ArrayList<>();
        String best = "";
        String bestName = "";
        for (String[] cand : candidates(c)) {
            if ("软件 llvmpipe（基准）".equals(cand[0])) continue;   // 基准放最后单独确认
            final ProbeResult r = probe(c, cand[0], cand[1], log);
            results.add(r);
            if (r.ok && best.isEmpty() && cand[1].contains("zink")) {
                best = cand[1];
                bestName = cand[0];
            }
        }
        final ProbeResult soft = probe(c, "软件 llvmpipe（基准）", "EGL_PLATFORM=surfaceless "
                + "LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe", log);
        results.add(soft);
        if (best.isEmpty() && soft.ok) {
            best = "EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe";
            bestName = soft.name;
        }
        BlenderEnv.setGpuProbeResult(c, !bestName.startsWith("软件"), bestName, best);
        if (log != null) {
            log.onLog("· 探测结论：" + (bestName.isEmpty() ? "没有可用组合" : bestName));
        }
        return results;
    }

    /** sysroot 里 Turnip 的 ICD 路径（没有就空串）。 */
    public static String freedrenoIcd(Context c) {
        final File dir = new File(BlenderEnv.sysrootDir(c), "usr/share/vulkan/icd.d");
        final File[] kids = dir.listFiles();
        if (kids == null) return "";
        for (File f : kids) {
            final String n = f.getName();
            if (n.contains("freedreno") || n.contains("turnip")) return f.getAbsolutePath();
        }
        return "";
    }
}
