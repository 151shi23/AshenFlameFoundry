package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 渲染前预检：把会出错的事在开渲前查出来，并给出可一键执行的修复。 */
public final class Preflight {

    private Preflight() {
    }

    public static final class Item {
        public final String name;
        public final int level;          // 0 ok / 1 warn / 2 fail
        public final String detail;
        public final String fixId;

        Item(String name, int level, String detail, String fixId) {
            this.name = name;
            this.level = level;
            this.detail = detail;
            this.fixId = fixId;
        }
    }

    public static final class Report {
        public final List<Item> items = new ArrayList<>();
        public ProjectOps.Info info;
        public long freeKb = -1L;
        public long memKb = -1L;
        public int cores = 1;

        public boolean canRender() {
            for (Item i : items) {
                if (i.level >= 2) return false;
            }
            return true;
        }

        public Item firstFixable() {
            for (Item i : items) {
                if (i.level >= 1 && i.fixId != null) return i;
            }
            return null;
        }

        public String text() {
            final StringBuilder sb = new StringBuilder();
            for (Item i : items) {
                sb.append(i.level == 0 ? "[ok] " : i.level == 1 ? "[warn] " : "[fail] ")
                        .append(i.name).append("：").append(i.detail).append('\n');
            }
            return sb.toString();
        }
    }

    /** 跑一遍预检。probeInfo=true 会打开工程做深度体检（慢，但能查贴图/UV/相机）。 */
    public static Report check(Context c, File project, BlenderScript.Params params,
                               boolean probeInfo, BlenderOps.Log log) {
        final Report r = new Report();
        r.cores = BlenderEnv.cpuCores();
        r.memKb = BlenderEnv.availableMemoryKb();
        r.freeKb = BlenderEnv.freeSpaceKb(c);

        if (!RootShell.isReady()) {
            r.items.add(new Item("Shizuku", 2, "未授权，高权限进程跑不起来", null));
            return r;
        }
        r.items.add(new Item("Shizuku", 0, RootShell.isRoot() ? "root 模式" : "adb shell 模式", null));

        if (!BlenderEnv.installed(c)) {
            r.items.add(new Item("渲染环境", 2, "还没安装", null));
            return r;
        }
        final BlenderInstaller.Probe probe = BlenderInstaller.probeBlender(c);
        if (!probe.ok) {
            final int lvl = probe.missingLib.isEmpty() ? 2 : 1;
            r.items.add(new Item("Blender 启动", lvl,
                    probe.missingLib.isEmpty() ? probe.output : "缺少 " + probe.missingLib, "deps"));
            return r;
        }
        r.items.add(new Item("Blender", 0, "版本 " + probe.version, null));

        if (project == null || !project.isFile()) {
            r.items.add(new Item("工程文件", 2, "未选择或不存在", null));
            return r;
        }
        if (project.length() < 32) {
            r.items.add(new Item("工程文件", 2, "文件太小，不像 .blend", null));
            return r;
        }
        r.items.add(new Item("工程文件", 0,
                project.getName() + "（" + FileUtil.human(project.length()) + "）", null));

        if (probeInfo) {
            r.info = ProjectOps.inspect(c, project, log);
            if (!r.info.hasMesh && !r.info.warnings.isEmpty()) {
                r.items.add(new Item("打开工程", 2, "打不开：" + r.info.warnings.get(0), null));
                return r;
            }
            r.items.add(new Item("网格 / 材质", 0,
                    r.info.meshes.size() + " 个网格 · " + r.info.materials.size() + " 个材质", null));
            if (r.info.hasCamera) {
                r.items.add(new Item("相机", 0, r.info.cameras.toString(), null));
            } else {
                r.items.add(new Item("相机", 1, "工程里没有相机，渲出来会是空画面", "camera"));
            }
            if (!r.info.missingFiles.isEmpty()) {
                r.items.add(new Item("外部贴图", 1,
                        r.info.missingFiles.size() + " 个找不到：" + r.info.missingFiles.get(0), "missing_tex"));
            } else if (r.info.imageCount > 0) {
                r.items.add(new Item("外部贴图", 0,
                        r.info.imageCount + " 张（已打包 " + r.info.packedImages.size() + "）", null));
            }
            if (!r.info.noUvMeshes.isEmpty()) {
                r.items.add(new Item("UV", 1,
                        r.info.noUvMeshes.size() + " 个网格没有 UV 层，贴图会花", "uv"));
            }
            for (String w : r.info.warnings) {
                r.items.add(new Item("工程提示", 1, w, null));
            }
        }

        // GPU 加速开关
        final String mode = BlenderEnv.accelMode(c);
        final GpuProbe.Info gpu = GpuProbe.detect(c);
        if ("soft".equals(mode)) {
            r.items.add(new Item("GPU 加速", 0, "已关闭（走软件 llvmpipe）", null));
        } else if (BlenderEnv.gpuProbeHw(c)) {
            r.items.add(new Item("GPU 加速", 0, "已启用：" + BlenderEnv.gpuProbeName(c), null));
        } else {
            final boolean canHw = gpu.hasKgsl || gpu.hasMali || gpu.hasDri;
            r.items.add(new Item("GPU 加速", canHw ? 1 : 0,
                    canHw ? ("还没探测/探测失败；候选：" + (gpu.hasKgsl ? "Turnip(Adreno)" : "Panfrost(Mali)"))
                            : "这台设备没暴露 GPU 内核节点，只能软件渲染",
                    canHw ? "gpu" : null));
        }

        if (params != null) {
            if (params.isVideo() && r.info != null && !r.info.ffmpegSupported) {
                r.items.add(new Item("视频编码", 2, "这个 Blender 构建没有 FFmpeg，只能出序列", "to_sequence"));
            }
            final boolean wantsGl = params.engine != null && params.engine.contains("EEVEE");
            if (wantsGl && !BlenderEnv.softGlOk(c) && !BlenderEnv.gpuProbeHw(c)) {
                r.items.add(new Item("EEVEE", 1, "GL 自检没过（软件/硬件都没成），建议改用 Cycles", "engine"));
            }
            if (params.engine != null && params.engine.contains("CYCLES")) {
                r.items.add(new Item("Cycles 后端", 0,
                        "CPU 渲染（Blender 3.0 起 Cycles 没有 OpenCL，Android 上不存在 GPU 光追）", null));
            }
        }

        if (r.freeKb >= 0) {
            final long need = estimateNeedKb(params);
            if (need > 0 && r.freeKb < need) {
                r.items.add(new Item("磁盘空间", 1, "剩 " + FileUtil.human(r.freeKb * 1024L)
                        + "，预计要 " + FileUtil.human(need * 1024L) + "（可先清旧产物）", null));
            } else {
                r.items.add(new Item("磁盘空间", 0,
                        "剩 " + FileUtil.human(Math.max(0, r.freeKb) * 1024L), null));
            }
        }
        if (r.memKb > 0) {
            final long need = estimateMemKb(params);
            final int level = r.memKb < need ? 1 : 0;
            r.items.add(new Item("内存", level, "可用 " + FileUtil.human(r.memKb * 1024L)
                    + " / 估算峰值 " + FileUtil.human(need * 1024L)
                    + (level == 1 ? "（建议降分辨率或采样）" : ""), level == 1 ? "downscale" : null));
        }
        r.items.add(new Item("CPU", 0, r.cores + " 核（Cycles 会用满）", null));
        return r;
    }

    /** 粗略估输出体积（KB）。序列按 PNG 约 1.5MB/张，视频按 8MB/秒。 */
    static long estimateNeedKb(BlenderScript.Params p) {
        if (p == null) return 0L;
        final long px = (long) p.resX * p.resY * Math.max(1, p.resPercent / 100);
        if (p.isVideo()) {
            final int frames = p.animation ? Math.max(1, p.frameEnd - p.frameStart + 1) : 1;
            final double seconds = frames / (double) Math.max(1, p.fps);
            return (long) (seconds * 8000L);
        }
        if (p.animation) {
            final int frames = Math.max(1, p.frameEnd - p.frameStart + 1);
            return px * 1500L * frames / 1000L;
        }
        return px * 1500L / 1000L;
    }

    /** 粗略估内存峰值（KB）。Cycles ≈ 像素 × 采样 × 24B 的四分之一；EEVEE 低一个量级。 */
    static long estimateMemKb(BlenderScript.Params p) {
        if (p == null) return 0L;
        final long px = (long) p.resX * p.resY * Math.max(1, p.resPercent / 100);
        final int samples = Math.max(1, p.samples);
        if (p.engine != null && p.engine.contains("EEVEE")) {
            return px * 16L / 1024L;
        }
        return Math.max(256L * 1024L, px * 24L / 1024L * Math.min(samples, 512) / 4L);
    }

    /** 执行一个自动修复，返回给用户看的一句话。 */
    public static String applyFix(Context c, String fixId, File project, BlenderScript.Params params,
                                  BlenderOps.Log log) {
        if (fixId == null) return "没有可自动修复的项";
        try {
            switch (fixId) {
                case "deps": {
                    final String err = BlenderDeps.heal(c, msg -> {
                        if (log != null) log.onLog(msg);
                    }, Downloader.NEVER);
                    return err.isEmpty() ? "依赖已补齐。" : err;
                }
                case "camera": {
                    final BlenderOps.Result r = ProjectOps.ensureCamera(c, project, log);
                    return r.ok ? "已补一台默认相机。" : r.summary();
                }
                case "view": {
                    final ProjectOps.ScenePatch patch = new ProjectOps.ScenePatch();
                    patch.viewTransform = "AgX";
                    final BlenderOps.Result r = ProjectOps.sceneOp(c, project, patch, log);
                    return r.ok ? "视图变换已改为 AgX。" : r.summary();
                }
                case "missing_tex":
                    return "缺失贴图补不了：请到「工程工具 → 贴图」用本地图片或 Poly Haven 的 CC0 贴图补上。";
                case "uv":
                    return "请到「工程工具 → UV」跑一次 Smart UV Project，再回来渲染。";
                case "to_sequence":
                    if (params == null) return "没有可改的渲染参数";
                    params.format = BlenderScript.Fmt.PNG;
                    params.normalizeVideo();
                    return "已切成 PNG 序列；渲完用「序列 → MP4（硬编）」压成视频。";
                case "engine":
                    if (params == null) return "没有可改的渲染参数";
                    params.engine = "CYCLES";
                    return "已切到 Cycles（CPU）。";
                case "gpu":
                    return "请到「Blender 渲染 → 硬件加速」里点「GPU 探测并试跑」："
                            + "会自动逐个候选组合试，找到能用的就记住；找不到就继续软件渲染。"
                            + "（Adreno 需要先装 Mesa Vulkan 驱动）";
                case "downscale":
                    if (params == null) return "没有可改的渲染参数";
                    params.resPercent = Math.max(25, params.resPercent / 2);
                    params.samples = Math.max(8, params.samples / 2);
                    return "已降到 " + params.resPercent + "% 分辨率、" + params.samples + " 采样。";
                default:
                    return "未知的修复项：" + fixId;
            }
        } catch (Throwable t) {
            return "修复失败：" + t;
        }
    }

    /** 环境诊断报告（复制给用户/反馈用）。 */
    public static String diagnostics(Context c) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Blender 环境诊断\n");
        sb.append("运行目录：").append(BlenderEnv.root(c)).append('\n');
        sb.append("已装版本：").append(BlenderEnv.installedVersion(c)).append('\n');
        sb.append("软渲染(llvmpipe)：").append(BlenderEnv.softGlOk(c) ? "可用" : "不可用").append('\n');
        sb.append("Debian 套件：").append(BlenderEnv.debSuite(c))
                .append(" / 架构：").append(BlenderEnv.debArch()).append('\n');
        sb.append("Shizuku：").append(RootShell.isReady() ? "已授权" : "未授权")
                .append(" / root：").append(RootShell.isRoot()).append('\n');
        sb.append("CPU 核心：").append(BlenderEnv.cpuCores()).append('\n');
        sb.append("可用内存：").append(FileUtil.human(Math.max(0, BlenderEnv.availableMemoryKb()) * 1024L)).append('\n');
        sb.append("可用空间：").append(FileUtil.human(Math.max(0, BlenderEnv.freeSpaceKb(c)) * 1024L)).append('\n');
        sb.append("xz 解码器：").append(RootShell.xzAvailable() ? "内置（org.tukaani:xz）" : "回退系统 xz 命令").append('\n');
        sb.append('\n').append(GpuProbe.detect(c).summary()).append('\n');
        sb.append("加速开关：").append(BlenderEnv.accelMode(c))
                .append(BlenderEnv.gpuProbeHw(c) ? ("（探测到：" + BlenderEnv.gpuProbeName(c) + "）") : "（未探测到硬件组合）")
                .append('\n');
        if (!BlenderEnv.customGpuEnv(c).isEmpty()) {
            sb.append("自定义驱动 env：").append(BlenderEnv.customGpuEnv(c)).append('\n');
        }
        final File top = BlenderEnv.blenderTop(c);
        if (top != null) {
            sb.append("引擎目录：").append(top.getAbsolutePath()).append('\n');
            sb.append("官方模板：").append(TemplateLib.officialBlends(c).size()).append(" 个\n");
        }
        final BlenderInstaller.Probe probe = BlenderInstaller.probeBlender(c);
        sb.append("启动探测：").append(probe.ok ? "OK " + probe.version : "失败 " + probe.output).append('\n');
        return sb.toString();
    }
}
