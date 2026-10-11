package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 Blender 做 headless 渲染。
 *
 * <p>启动方式不是 {@code blender} 直接跑，而是：{@code sysroot 里的 ld-linux → --library-path → blender}。
 * 三个关键点：</p>
 * <ul>
 *   <li>{@code LD_LIBRARY_PATH} 必须显式指到 sysroot：glibc 的 ld.so 搜索顺序里
 *       默认目录在最后，会先摸到 Android 的 {@code /system/lib64/libEGL.so.1}（bionic 版），
 *       结果是 EGL/GL 一初始化就炸。</li>
 *   <li>{@code exec} 顶替 shell：这样取消渲染时能直接杀掉 Blender，而不是只杀掉外层 sh。</li>
 *   <li>EEVEE / Workbench 走 llvmpipe（软件 GL），要设 {@code EGL_PLATFORM=surfaceless}，
 *       否则在没有 framebuffer 的机器上会拿不到 surface。</li>
 * </ul>
 */
public final class BlenderRenderer {

    public interface Listener {
        void onLog(String line);

        void onFrame(int current, int total);

        void onFinished(boolean ok, String message);
    }

    /** 一次渲染任务。 */
    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile RootShell.Handle handle;
        volatile boolean done;

        public void cancel() {
            cancelled.set(true);
            final RootShell.Handle h = handle;
            if (h != null) h.cancel();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final Pattern P_SAMPLE = Pattern.compile("Sample\\s+(\\d+)/(\\d+)");
    private static final Pattern P_FRAME = Pattern.compile("MW_FRAME\\s+(\\d+)\\s+(\\d+)");
    private static final Pattern P_FRA = Pattern.compile("Fra:(\\d+)");
    private static final Pattern P_ERR = Pattern.compile("MW_ERROR\\s+(.*)");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "mw-blender-render");
        t.setDaemon(true);
        return t;
    });

    private BlenderRenderer() {
    }

    /** 在后台线程启动渲染。 */
    public static Task start(final Context app, final BlenderScript.Params params, final File project,
                             final Listener listener) {
        final Task task = new Task();
        EXEC.execute(() -> {
            boolean ok = false;
            String message = "未知错误";
            try {
                ok = run(app, params, project, listener, task);
                message = ok ? "渲染完成" : "渲染失败（见日志）";
            } catch (Throwable t) {
                message = "渲染异常：" + t;
            } finally {
                listener.onFinished(ok && !task.isCancelled(), message);
            }
        });
        return task;
    }

    private static boolean run(Context c, BlenderScript.Params params, File project,
                               Listener listener, Task task) throws IOException {
        final Frame f = renderOnce(c, params, project, new LineSink() {
            @Override
            public void onLine(String line) {
                listener.onLog(line);
            }
        }, task);
        task.done = true;
        if (!f.ok) {
            if (!f.crash.isEmpty()) throw new IOException(f.crash);
            throw new IOException("渲染失败（exit=" + f.code + "）");
        }
        return true;
    }

    /** 单次渲染的执行结果（重试与降级由 {@link RenderFallback} 编排）。 */
    public static final class Frame {
        public boolean ok;
        public int code;
        public String crash = "";
        public String tail = "";
    }

    /** 行输出接口。 */
    public interface LineSink {
        void onLine(String line);
    }

    /** 同步渲染一次，不做重试。 */
    public static Frame renderOnce(Context c, BlenderScript.Params params, File project, LineSink sink) {
        return renderOnce(c, params, project, sink, null);
    }

    /** 同步渲染一次；传入 {@link Task} 可以被外部取消。 */
    public static Frame renderOnce(Context c, BlenderScript.Params params, File project, LineSink sink,
                                   Task task) {
        final Frame f = new Frame();
        final String loader = BlenderEnv.loaderPath(c);
        final String bin = BlenderEnv.blenderBin(c);
        if (loader.isEmpty() || bin.isEmpty() || !new File(bin).isFile()) {
            f.tail = "Blender 未安装";
            return f;
        }
        if (project == null || !project.isFile()) {
            f.tail = "工程不存在：" + project;
            return f;
        }
        params.normalizeVideo();
        final File script;
        try {
            FileUtil.mkdirs(BlenderEnv.workDir(c));
            FileUtil.mkdirs(BlenderEnv.outDir(c));
            script = BlenderScript.write(BlenderEnv.workDir(c), params);
        } catch (IOException e) {
            f.tail = "写脚本失败：" + e.getMessage();
            return f;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(BlenderEnv.envPrefix(c));
        // 图形环境按「加速开关」决定：软件 llvmpipe / 硬件 Turnip+Zink / 自定义
        sb.append(BlenderEnv.gpuEnv(c, params.engine, BlenderRenderer.needsSoftwareGl(params.engine)));
        sb.append(BlenderScript.env(params)).append('\n');
        sb.append("cd ").append(sh(BlenderEnv.workDir(c).getAbsolutePath())).append('\n');
        sb.append("exec ").append(sh(loader))
                .append(" --library-path ").append(sh(BlenderEnv.libPath(c)))
                .append(' ').append(sh(bin))
                .append(" --factory-startup -noaudio")
                .append(" -b ").append(sh(project.getAbsolutePath()))
                .append(" -P ").append(sh(script.getAbsolutePath()))
                .append('\n');

        final StringBuilder tail = new StringBuilder();
        final RootShell.Handle handle = RootShell.stream(sb.toString(), line -> {
            if (sink != null) sink.onLine(line);
            synchronized (tail) {
                tail.append(line).append('\n');
                if (tail.length() > 8192) {
                    tail.delete(0, tail.length() - 8192);
                }
            }
        });
        if (handle == null) {
            f.tail = "无法启动渲染进程：Shizuku 未授权或已断开";
            return f;
        }
        if (task != null) {
            task.handle = handle;
        }
        f.code = handle.waitFor();
        f.tail = tail.toString();
        f.crash = BlenderOps.crashOf(f.code, f.tail);
        f.ok = f.code == 0;
        return f;
    }

    /** EEVEE / Workbench 需要 GL 上下文；Cycles CPU 不需要。 */
    private static boolean needsSoftwareGl(String engine) {
        return engine != null && engine.toUpperCase(Locale.ROOT).contains("EEVEE");
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String sh(String s) {
        return RootShell.q(s);
    }

    /** 静帧输出名（老 UI 用；新逻辑走 {@link #applyOutput}）。 */
    public static File stillOutput(Context c, String baseName) {
        return new File(BlenderEnv.outDir(c), baseName + ".png");
    }

    public static String animPrefix(String baseName) {
        return baseName + "_f";
    }

    /**
     * 按输出格式决定文件名并写回 {@code params.output}。
     *
     * <p>视频不给后缀：Blender 会按容器自己补（.mp4 / .mkv / .webm）；
     * 动画序列给前缀（{@code base_} → {@code base_0001.png}）。</p>
     */
    public static String applyOutput(Context c, BlenderScript.Params p, String base) {
        p.normalizeVideo();
        final File out = BlenderEnv.outDir(c);
        FileUtil.mkdirs(out);
        if (p.isVideo()) {
            p.output = new File(out, base).getAbsolutePath();
        } else if (p.animation) {
            p.output = new File(out, base + "_").getAbsolutePath();
        } else {
            p.output = new File(out, base + extOf(p.format)).getAbsolutePath();
        }
        return p.output;
    }

    public static String extOf(String format) {
        final String f = format == null ? "" : format.toUpperCase(Locale.ROOT);
        if (BlenderScript.Fmt.JPEG.equals(f)) return ".jpg";
        if (BlenderScript.Fmt.WEBP.equals(f)) return ".webp";
        if (BlenderScript.Fmt.TIFF.equals(f)) return ".tif";
        if (BlenderScript.Fmt.BMP.equals(f)) return ".bmp";
        if (BlenderScript.Fmt.EXR.equals(f)) return ".exr";
        return ".png";
    }

    /** 视频容器的后缀（用于展示与「存相册」前的预期文件名）。 */
    public static String videoExt(String container) {
        final String c = container == null ? "" : container.toUpperCase(Locale.ROOT);
        if ("MKV".equals(c)) return ".mkv";
        if ("WEBM".equals(c)) return ".webm";
        if ("OGG".equals(c)) return ".ogg";
        return ".mp4";
    }
}
