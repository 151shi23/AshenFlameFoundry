package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 渲染编排：降级链 + 逐帧续渲 + 输出校验 + 视频回退。
 *
 * <p>四道防线：产物必须校验（存在 + 大小 + 文件头）；动画逐帧渲并支持断点续渲；
 * 失败按采样减半、分辨率减半降级重试；视频直出失败自动改走 PNG 序列加系统硬编。</p>
 */
public final class RenderFallback {

    private RenderFallback() {
    }

    public interface Listener {
        void onLog(String line);

        void onStage(String stage);

        void onProgress(int current, int total);
    }

    public static final class Outcome {
        public boolean ok;
        public String message = "";
        public File output;
        public final List<String> attempts = new ArrayList<>();
    }

    private static final int FRAME_RETRIES = 3;

    /** 入口：按 params 决定走静帧、视频还是序列。 */
    public static Outcome render(Context c, File project, BlenderScript.Params params, Listener l) {
        return render(c, project, params, l, null);
    }

    /** 入口（可传 Task 以便取消：会杀掉当前帧进程，并在帧之间停止）。 */
    public static Outcome render(Context c, File project, BlenderScript.Params params, Listener l,
                                 BlenderRenderer.Task task) {
        final Outcome out = new Outcome();
        params.normalizeVideo();
        final String base = baseName(project == null ? "out" : project.getName());
        if (params.isVideo()) {
            renderVideo(c, project, params, base, out, l, task);
        } else if (params.animation) {
            renderSequence(c, project, params, base, out, l, task);
        } else {
            renderStill(c, project, params, base, out, l, task);
        }
        out.message = out.ok ? "渲染完成" : ("渲染失败：" + out.message);
        return out;
    }

    private static boolean cancelled(BlenderRenderer.Task t) {
        return t != null && t.isCancelled();
    }

    // ---------------------------------------------------------------- 静帧

    private static void renderStill(Context c, File project, BlenderScript.Params p, String base,
                                    Outcome out, Listener l, BlenderRenderer.Task task) {
        final File target = new File(BlenderEnv.outDir(c), base + BlenderRenderer.extOf(p.format));
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (cancelled(task)) {
                out.message = "已取消";
                return;
            }
            if (attempt == 2) {
                l.onStage("降级重试：采样减半");
                p.samples = Math.max(4, p.samples / 2);
            } else if (attempt == 3) {
                l.onStage("再次降级：分辨率与采样各减半");
                p.resPercent = Math.max(25, p.resPercent / 2);
                p.samples = Math.max(4, p.samples / 2);
            }
            BlenderRenderer.applyOutput(c, p, base);
            l.onStage("渲染静帧（第 " + attempt + " 次）");
            l.onProgress(0, 1);
            final BlenderRenderer.Frame f = BlenderRenderer.renderOnce(c, p, project, l::onLog, task);
            final String why = verify(target, p.format);
            out.attempts.add("静帧 #" + attempt + " → "
                    + (f.ok && why.isEmpty() ? "成功" : reason(f, why)));
            if (f.ok && why.isEmpty()) {
                out.ok = true;
                out.output = target;
                l.onProgress(1, 1);
                return;
            }
            out.message = reason(f, why);
            if (looksFatal(f)) return;
        }
    }

    // ---------------------------------------------------------------- 序列（逐帧 + 续渲）

    private static void renderSequence(Context c, File project, BlenderScript.Params p, String base,
                                       Outcome out, Listener l, BlenderRenderer.Task task) {
        final int start = Math.min(p.frameStart, p.frameEnd);
        final int end = Math.max(p.frameStart, p.frameEnd);
        final int total = end - start + 1;
        final List<File> produced = new ArrayList<>();
        int done = 0;
        for (int f = start; f <= end; f++) {
            if (cancelled(task)) {
                out.message = "已取消";
                out.output = produced.isEmpty() ? null : produced.get(0);
                return;
            }
            final File dst = new File(BlenderEnv.outDir(c), frameName(base, f, p.format));
            if (isValid(dst, p.format)) {          // 断点续渲：已有就跳过
                produced.add(dst);
                done++;
                l.onProgress(done, total);
                continue;
            }
            final BlenderScript.Params one = copyForFrame(p, f, dst);
            boolean ok = false;
            String why = "";
            for (int attempt = 1; attempt <= FRAME_RETRIES && !ok; attempt++) {
                if (attempt == 2) {
                    l.onStage("第 " + f + " 帧重试：采样减半");
                    one.samples = Math.max(4, one.samples / 2);
                } else if (attempt == 3) {
                    l.onStage("第 " + f + " 帧再试：分辨率减半");
                    one.resPercent = Math.max(25, one.resPercent / 2);
                    one.samples = Math.max(4, one.samples / 2);
                }
                l.onStage("渲染第 " + f + " 帧（第 " + attempt + " 次）");
                final BlenderRenderer.Frame fr = BlenderRenderer.renderOnce(c, one, project, l::onLog, task);
                why = reason(fr, verify(dst, p.format));
                ok = fr.ok && verify(dst, p.format).isEmpty();
                out.attempts.add("帧 " + f + " #" + attempt + " → " + (ok ? "成功" : why));
            }
            if (ok) {
                produced.add(dst);
                done++;
                l.onProgress(done, total);
            } else {
                out.message = "第 " + f + " 帧失败：" + why;
                out.output = produced.isEmpty() ? null : produced.get(0);
                return;
            }
        }
        out.ok = true;
        out.output = produced.isEmpty() ? null : produced.get(0);
        out.message = produced.size() + " 帧已就绪";
    }

    // ---------------------------------------------------------------- 视频（含回退到序列）

    private static void renderVideo(Context c, File project, BlenderScript.Params p, String base,
                                    Outcome out, Listener l, BlenderRenderer.Task task) {
        final String ext = BlenderRenderer.videoExt(p.videoContainer);
        final File dir = BlenderEnv.outDir(c);
        deleteOld(dir, base, ext);
        BlenderRenderer.applyOutput(c, p, base);
        l.onStage("直出视频（Blender 内置 FFmpeg）");
        l.onProgress(0, 1);
        final BlenderRenderer.Frame f = BlenderRenderer.renderOnce(c, p, project, l::onLog, task);
        final File produced = newestWithSuffix(dir, base, ext);
        out.attempts.add("视频直出 → " + ((f.ok && produced != null) ? "成功" : reason(f, "没有产出视频")));
        if (f.ok && produced != null && produced.length() > 4096) {
            out.ok = true;
            out.output = produced;
            l.onProgress(1, 1);
            return;
        }
        out.message = reason(f, "没有产出视频");

        l.onStage("回退方案：改出 PNG 序列，再用系统硬编压成 MP4");
        final BlenderScript.Params seq = copyForSequence(p);
        final Outcome seqOut = new Outcome();
        renderSequence(c, project, seq, base, seqOut, l, task);
        if (!seqOut.ok) {
            out.message = "视频与序列两条路都失败：" + seqOut.message;
            out.attempts.add("序列回退 → 失败：" + seqOut.message);
            return;
        }
        out.attempts.add("序列回退 → " + seqOut.message);
        final List<File> frames = SequenceEncoder.framesIn(dir, base + "_f");
        if (frames.isEmpty()) {
            out.message = "序列回退后没找到帧文件";
            return;
        }
        try {
            final File mp4 = new File(dir, base + "_" + System.currentTimeMillis() + ".mp4");
            l.onStage("系统硬编压成 MP4（" + frames.size() + " 帧）");
            final File encoded = SequenceEncoder.encode(frames, mp4, Math.max(1, seq.fps),
                    (d, t) -> l.onProgress(d, t), Downloader.NEVER);
            out.ok = true;
            out.output = encoded;
        } catch (Throwable e) {
            out.message = "硬编失败：" + e.getMessage() + "（PNG 序列已保留）";
        }
    }

    // ---------------------------------------------------------------- 参数复制

    static BlenderScript.Params copyForFrame(BlenderScript.Params p, int frame, File dst) {
        final BlenderScript.Params q = copyBase(p);
        q.animation = false;
        q.frame = frame;
        q.output = dst.getAbsolutePath();
        q.normalizeVideo();
        return q;
    }

    private static BlenderScript.Params copyForSequence(BlenderScript.Params p) {
        final BlenderScript.Params q = copyBase(p);
        q.format = BlenderScript.Fmt.PNG;
        q.animation = true;
        q.frameStart = Math.min(p.frameStart, p.frameEnd);
        q.frameEnd = Math.max(p.frameStart, p.frameEnd);
        q.normalizeVideo();
        return q;
    }

    private static BlenderScript.Params copyBase(BlenderScript.Params p) {
        final BlenderScript.Params q = new BlenderScript.Params();
        q.engine = p.engine;
        q.samples = p.samples;
        q.threads = p.threads;
        q.resX = p.resX;
        q.resY = p.resY;
        q.resPercent = p.resPercent;
        q.format = p.format;
        q.imageQuality = p.imageQuality;
        q.fps = p.fps;
        q.filmTransparent = p.filmTransparent;
        q.viewTransform = p.viewTransform;
        q.camera = p.camera;
        return q;
    }

    static String frameName(String base, int frame, String format) {
        return base + "_f" + String.format(java.util.Locale.US, "%04d", frame)
                + BlenderRenderer.extOf(format);
    }

    // ---------------------------------------------------------------- 校验与小工具

    /** 校验产物：存在 + 够大 + 文件头对得上。@return 空串 = 通过 */
    static String verify(File f, String format) {
        if (f == null || !f.isFile()) return "文件不存在";
        final long len = f.length();
        if (len < 512) return "文件太小（" + len + " 字节）";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            final byte[] head = new byte[12];
            final int n = raf.read(head);
            if (n < 4) return "读不出文件头";
            final String fmt = format == null ? "" : format.toUpperCase(java.util.Locale.ROOT);
            if (BlenderScript.Fmt.PNG.equals(fmt)) {
                if (!((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G')) {
                    return "不是 PNG（可能写坏了）";
                }
            } else if (BlenderScript.Fmt.JPEG.equals(fmt)) {
                if (!((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8)) return "不是 JPEG";
            } else if (BlenderScript.Fmt.EXR.equals(fmt)) {
                if (head[0] != 0x76 || head[1] != 0x2F || head[2] != 0x31 || head[3] != 0x01) {
                    return "不是 OpenEXR";
                }
            }
        } catch (Throwable t) {
            return "读文件失败：" + t.getClass().getSimpleName();
        }
        return "";
    }

    static boolean isValid(File f, String format) {
        return verify(f, format).isEmpty();
    }

    private static String reason(BlenderRenderer.Frame f, String verifyMsg) {
        if (verifyMsg != null && !verifyMsg.isEmpty()) return verifyMsg;
        if (f == null) return "无返回";
        if (!f.crash.isEmpty()) return f.crash;
        if (f.code != 0) return "退出码 " + f.code + " " + firstLine(f.tail);
        return "未知失败";
    }

    /** 崩溃类/缺库类错误重试没意义。 */
    private static boolean looksFatal(BlenderRenderer.Frame f) {
        if (f == null) return false;
        if (f.code == 137) return true;
        final String t = f.tail == null ? "" : f.tail;
        return t.contains("error while loading shared libraries");
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        final int i = s.indexOf('\n');
        return i < 0 ? s.trim() : s.substring(0, i).trim();
    }

    private static void deleteOld(File dir, String base, String ext) {
        final File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            final String n = f.getName();
            if (n.startsWith(base) && n.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static File newestWithSuffix(File dir, String base, String ext) {
        final File[] kids = dir.listFiles();
        if (kids == null) return null;
        File best = null;
        for (File f : kids) {
            final String n = f.getName();
            if (!n.startsWith(base)) continue;
            if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    static String baseName(String name) {
        String n = name == null ? "out" : name;
        final int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
