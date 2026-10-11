package com.mineways.conv;

import android.content.Context;

import com.mineways.blender.RootShell;

import java.io.File;
import java.util.Locale;

/**
 * 用 Debian 的 ffmpeg 做转换 —— 专门补 Android 平台缺的那些编码器。
 *
 * <p>为什么需要它：Android 只给解码器，不给 MP3 / GIF 编码器，所以老版本里
 * 「MP3 只能进不能出」「GIF 只能进不能出」。ffmpeg 一装，这些方向立刻变成双向。</p>
 */
public final class FfmpegConv {

    private FfmpegConv() {
    }

    /** 按输出扩展名给编码参数（够用即可，不做花活）。 */
    static String argsFor(String ext, int quality) {
        final String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        switch (e) {
            case "mp3":
                return "-vn -c:a libmp3lame -q:a " + q(quality, 2);
            case "flac":
                return "-vn -c:a flac -compression_level 5";
            case "opus":
                return "-vn -c:a libopus -b:a 160k";
            case "ogg2":
            case "vorbis":
                return "-vn -c:a libvorbis -q:a " + q(quality, 5);
            case "aac":
                return "-vn -c:a aac -b:a 192k";
            case "m4a":
                return "-vn -c:a aac -b:a 192k";
            case "wav":
                return "-vn -c:a pcm_s16le";
            case "gif":
                return "-c:v gif";
            case "tif":
            case "tiff":
                return "-c:v tiff";
            case "webp":
                return "-c:v libwebp -q:v " + q(quality, 90);
            case "avif":
                return "-c:v libaom-av1 -still-picture 1 -crf 30";
            case "jpg":
            case "jpeg":
                return "-c:v mjpeg -q:v 3";
            case "png":
                return "-c:v png";
            case "mp4":
                return "-c:v libx264 -crf 23 -preset medium -c:a aac -b:a 192k -movflags +faststart";
            case "webm":
                return "-c:v libvpx-vp9 -crf 32 -b:v 0 -c:a libopus";
            case "mkv":
                return "-c:v libx264 -crf 23 -c:a aac";
            default:
                return null;   // 没模板就别瞎跑，让上层走别的引擎
        }
    }

    private static int q(int quality, int def) {
        return quality >= 1 && quality <= 10 ? quality : def;
    }

    /** 这个输出格式有没有 ffmpeg 模板。 */
    public static boolean canOutput(String ext) {
        return argsFor(ext, 0) != null;
    }

    /**
     * 转换。{@code outExt} 用 {@link ConvRouting} 给的目标扩展名
     * （注意 {@code ogg2} 表示 ffmpeg 的 Vorbis 版 ogg，落盘时仍写 .ogg）。
     */
    public static FmtConv.Result convert(Context c, File in, File out, String outExt, int quality) {
        final String args = argsFor(outExt, quality);
        if (args == null) {
            return new FmtConv.Result(false, "ffmpeg 没有 " + outExt + " 的编码参数，换一个目标格式。");
        }
        if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) {
            return new FmtConv.Result(false, "建不了输出目录：" + out.getParentFile());
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        final String cmd = "-y -hide_banner -nostdin -loglevel error -i "
                + RootShell.q(in.getAbsolutePath()) + " " + args + " "
                + RootShell.q(out.getAbsolutePath());
        final SysrootExec.Result r = SysrootExec.run(c, "ffmpeg", cmd, 1800);
        if (!out.isFile() || out.length() < 32) {
            final String why = r.out.trim().isEmpty() ? "ffmpeg 没有输出（退出码 " + r.code + "）" : r.tail(4);
            return new FmtConv.Result(false, "ffmpeg 转换失败：\n" + why
                    + "\n（常见原因：该 ffmpeg 构建没有这个编码器，换目标格式或换输出目录试试）");
        }
        com.mineways.blender.FileUtil.makeTreeReadable(out.getParentFile());
        return new FmtConv.Result(true, "用 ffmpeg 转成 " + outExt.toUpperCase(Locale.ROOT)
                + "：" + out.getName() + "（" + com.mineways.blender.FileUtil.human(out.length()) + "）");
    }

    /** ffmpeg 版本（给页面显示"引擎就绪"用）。 */
    public static String version(Context c) {
        if (!SysrootExec.has(c, "ffmpeg")) return "";
        final SysrootExec.Result r = SysrootExec.run(c, "ffmpeg", "-version", 30);
        for (String line : r.out.split("\\r?\\n")) {
            if (line.startsWith("ffmpeg version")) return line.trim();
        }
        return r.ok ? "ffmpeg 已就绪" : "";
    }
}
