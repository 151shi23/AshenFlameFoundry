package com.mineways.conv;

import android.content.Context;

import com.mineways.blender.BlenderEnv;
import com.mineways.blender.RootShell;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 格式转换的「路由表」：某个源文件到底能转成哪些格式、用哪个引擎、保证不保证。
 *
 * <p>老页面的目标格式是写死的（图片固定 6 个、音频固定 3 个），既不随源文件过滤，
 * 也和真实能力不一致，用户只能靠试错，所以「规则不清楚、很割裂」。这里把规则集中成一张表。</p>
 *
 * <p>引擎分工：平台（Bitmap / MediaCodec，稳但缺 MP3/GIF 编码）、原生（Assimp + stb）、
 * ffmpeg（Debian 包，可选组件，补平台缺的编码器）、Blender（可选，补「其它模型 → .blend」）。</p>
 */
public final class ConvRouting {

    private ConvRouting() {
    }

    public enum Kind {IMAGE, AUDIO, MODEL, OTHER}

    public enum Engine {PLATFORM, NATIVE, FFMPEG, BLENDER}

    /** 一个「可以转成什么」。 */
    public static final class Target {
        public final String ext;
        public final Engine engine;
        public final boolean guaranteed;
        public final String note;
        public final String hint;
        public boolean available = true;

        Target(String ext, Engine engine, boolean guaranteed, String note, String hint) {
            this.ext = ext;
            this.engine = engine;
            this.guaranteed = guaranteed;
            this.note = note;
            this.hint = hint;
        }

        public String label() {
            final StringBuilder sb = new StringBuilder(ext.toUpperCase(Locale.ROOT));
            sb.append(" · ").append(engineName(engine));
            if (!guaranteed) sb.append(" · 看机型");
            return sb.toString();
        }
    }

    public static String engineName(Engine e) {
        switch (e) {
            case NATIVE:
                return "原生";
            case FFMPEG:
                return "ffmpeg";
            case BLENDER:
                return "Blender";
            default:
                return "平台";
        }
    }

    // ---------------------------------------------------------------- 源类型识别

    private static final String[] IMAGE_IN = {"png", "jpg", "jpeg", "jfif", "webp", "gif", "bmp", "tga",
            "psd", "hdr", "avif", "heic", "heif", "ico", "tif", "tiff"};
    private static final String[] AUDIO_IN = {"mp3", "m4a", "aac", "flac", "opus", "ogg", "oga", "wav",
            "amr", "3gp", "wma", "aiff", "aif", "mka", "ape", "mp2"};
    private static final String[] MODEL_IN = {"blend", "obj", "gltf", "glb", "fbx", "stl", "ply", "dae",
            "3ds", "md2", "md3", "objnomtl", "assbin", "assxml"};

    public static String extOf(File f) {
        final String n = f == null ? "" : f.getName().toLowerCase(Locale.ROOT);
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }

    /** 先看扩展名，拿不准就读文件头。 */
    public static Kind kindOf(File f) {
        final String ext = extOf(f);
        if (in(ext, IMAGE_IN)) return Kind.IMAGE;
        if (in(ext, AUDIO_IN)) return Kind.AUDIO;
        if (in(ext, MODEL_IN)) return Kind.MODEL;
        switch (sniff(f)) {
            case "png":
            case "jpg":
            case "gif":
            case "webp":
            case "bmp":
                return Kind.IMAGE;
            case "wav":
            case "ogg":
            case "mp3":
            case "mp4":
            case "flac":
                return Kind.AUDIO;
            default:
                return Kind.OTHER;
        }
    }

    /** 读前 16 字节判断真实类型（扩展名骗人时用）。 */
    public static String sniff(File f) {
        if (f == null || !f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] h = new byte[16];
            final int n = in.read(h);
            if (n < 4) return "";
            final int b0 = h[0] & 0xFF;
            final int b1 = h[1] & 0xFF;
            if (b0 == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
            if (b0 == 0xFF && b1 == 0xD8) return "jpg";
            if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E') return "webp";
            if (h[0] == 'B' && h[1] == 'M') return "bmp";
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A') return "wav";
            if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
            if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
            if ((b0 == 0xFF) && ((b1 & 0xE0) == 0xE0)) return "mp3";
            if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return "mp4";
            if (h[0] == 'g' && h[1] == 'l' && h[2] == 'T' && h[3] == 'F') return "glb";
            if (h[0] == 'B' && h[1] == 'L' && h[2] == 'E' && h[3] == 'N') return "blend";
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean in(String ext, String[] list) {
        for (String s : list) {
            if (s.equals(ext)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 路由

    public static boolean ffmpegReady(Context c) {
        return BlenderEnv.sysrootBin(c, "ffmpeg") != null;
    }

    public static boolean blenderReady(Context c) {
        return RootShell.isReady() && BlenderEnv.installed(c);
    }

    /**
     * 这个源文件能转成哪些格式（按类别给全表，并把当前不可用的标出来）。
     *
     * @return 目标列表（含 available / hint），UI 直接画按钮
     */
    public static List<Target> targetsFor(Context c, File src) {
        final List<Target> out = new ArrayList<>();
        final Kind kind = kindOf(src);
        final String srcExt = extOf(src);
        final boolean ff = ffmpegReady(c);
        final boolean bl = blenderReady(c);

        if (kind == Kind.IMAGE) {
            add(out, "png", Engine.PLATFORM, true, "无损，平台直接写", null);
            add(out, "jpg", Engine.PLATFORM, true, "有损，平台直接写", null);
            add(out, "webp", Engine.PLATFORM, false, "平台编码器，机型差异大", null);
            add(out, "bmp", Engine.NATIVE, true, "无压缩位图（stb）", null);
            add(out, "tga", Engine.NATIVE, true, "TGA（stb）", null);
            add(out, "hdr", Engine.NATIVE, true, "Radiance HDR 浮点（stb）", null);
            add(out, "gif", Engine.FFMPEG, false, "平台没有 GIF 编码器，只能靠 ffmpeg",
                    ff ? null : "需要先装「ffmpeg 工具」（在 Blender 渲染页 → 可选组件）");
            add(out, "tif", Engine.FFMPEG, false, "TIFF 由 ffmpeg 编码",
                    ff ? null : "需要先装「ffmpeg 工具」");
            add(out, "avif", Engine.FFMPEG, false, "实验性，取决于 ffmpeg 构建",
                    ff ? null : "需要先装「ffmpeg 工具」");
        } else if (kind == Kind.AUDIO) {
            add(out, "wav", Engine.PLATFORM, true, "PCM 16bit，平台解码后自写头", null);
            add(out, "m4a", Engine.PLATFORM, true, "AAC 封装", null);
            add(out, "ogg", Engine.PLATFORM, false, "Opus 封装，需 Android 10+", null);
            add(out, "mp3", Engine.FFMPEG, false, "Android 平台没有 MP3 编码器（这是老版单向的根因）",
                    ff ? null : "需要先装「ffmpeg 工具」（Blender 渲染页 → 可选组件）");
            add(out, "flac", Engine.FFMPEG, false, "无损，ffmpeg 编码",
                    ff ? null : "需要先装「ffmpeg 工具」");
            add(out, "opus", Engine.FFMPEG, false, "ffmpeg 编码",
                    ff ? null : "需要先装「ffmpeg 工具」");
            add(out, "aac", Engine.FFMPEG, false, "裸 AAC 流",
                    ff ? null : "需要先装「ffmpeg 工具」");
            add(out, "ogg2", Engine.FFMPEG, false, "Vorbis 编码（与平台 Opus 版不同）",
                    ff ? null : "需要先装「ffmpeg 工具」");
        } else if (kind == Kind.MODEL) {
            // 原生（Assimp）双向：以编译进去的导入/导出器为准
            addModelTargets(c, out, srcExt);
            add(out, "blend", Engine.BLENDER, false,
                    "Assimp 没有 .blend 导出器，只能用真 Blender 导入后另存（会保留贴图）",
                    bl ? null : "需要先装「Blender 环境」（Blender 渲染页 → 安装 / 更新）");
        }
        return out;
    }

    /** 模型：用 native 报出来的真实导入清单，避免展示假格式。 */
    private static void addModelTargets(Context c, List<Target> out, String srcExt) {
        final List<String> exp = FmtConv.exportExts();
        if (exp.isEmpty()) {
            for (String e : new String[]{"obj", "glb", "gltf", "stl", "ply", "dae", "3ds", "fbx"}) {
                add(out, e, Engine.NATIVE, true, "原生 Assimp 导出", null);
            }
            return;
        }
        for (String e : exp) {
            if (e.equals(srcExt)) continue;                  // 同格式不列
            if (e.equals("assbin") || e.equals("assxml") || e.equals("objnomtl")) continue;  // 冷门
            add(out, e, Engine.NATIVE, true, "原生 Assimp 导出", null);
        }
    }

    private static void add(List<Target> list, String ext, Engine engine, boolean guaranteed,
                            String note, String hint) {
        final Target t = new Target(ext, engine, guaranteed, note, hint);
        t.available = hint == null;
        for (Target old : list) {
            if (old.ext.equals(ext)) return;                 // 去重
        }
        list.add(t);
    }

    /** 规则说明（页面顶部那一段，解释引擎分工）。 */
    public static String rules() {
        return "规则（就三条）：\n"
                + "① 目标只列这个源文件真能转的格式，按钮上标了引擎；不支持的直接不显示，不用试错。\n"
                + "② 标「平台/原生」的是本机自带的，一定不失败；标「看机型」的是系统编码器，个别机型可能没有。\n"
                + "③ 标「ffmpeg / Blender」的要用扩展引擎（在 Blender 渲染页装一次即可），"
                + "装它就是补 Android 缺的那些编码器（MP3、GIF 等）。\n"
                + "\n"
                + "为什么有的格式天生单向：Android 只提供「解码器」，不提供 MP3 / GIF 编码器，"
                + "Assimp 也没有 .blend 导出器 —— 这些方向不是没做，是得靠外挂引擎补。";
    }
}
