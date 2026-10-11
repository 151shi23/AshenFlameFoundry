package com.mineways.blender;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 高权限 shell 执行器。
 *
 * <p>Blender 官方 Linux 包是 glibc 动态链接程序，Android 没有 glibc，
 * 只能靠「自带 glibc + 官方加载器」的方式跑起来；而 Android 10+ 的应用私有目录
 * （{@code files/}、{@code cache/}）是 noexec 挂载，二进制放进去必然
 * {@code Permission denied}（本项目 arnis 内核踩过同一个坑，见 PROJECT_NOTES 3.1）。
 * 所以整个运行环境必须落在 {@code /data/local/tmp} 这类「可执行 + shell 可读写」的目录，
 * 而创建该目录并调整权限、读取应用私有目录里的安装包，都需要 Shizuku 提权。</p>
 */
public final class RootShell {

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static final int REQ_CODE = 14711;

    /** 行回调。 */
    public interface LineSink {
        void onLine(String line);
    }

    /** 执行结果。 */
    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    /** 流式执行的句柄。 */
    public static final class Handle {
        private final Process process;
        private volatile boolean cancelled;

        Handle(Process process) {
            this.process = process;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        /** 终止渲染进程（命令里用 exec 让子进程顶替 shell，所以这里能直接杀掉 Blender）。 */
        public void cancel() {
            cancelled = true;
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
            try {
                if (process.isAlive()) process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }

        public int waitFor() {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        /** 进程是否还活着（用于超时轮询）。 */
        public boolean isAlive() {
            try {
                return process.isAlive();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static Boolean sRoot;
    private static Boolean sXzAvailable;
    private static Method sNewProcess;

    private RootShell() {
    }

    // ---------------------------------------------------------------- 权限

    public static boolean isReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isInstalled() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发起授权请求，结果通过 {@link #addPermissionListener} 回调。 */
    public static boolean requestPermission() {
        try {
            Shizuku.requestPermission(REQ_CODE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.addRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Shizuku API 13.x 把 {@code newProcess} 标成了 private，用反射拿；
     * release 构建关闭了混淆（build.gradle.kts 里 isMinifyEnabled = false），方法名不会被改写。
     */
    private static synchronized Method newProcessMethod() {
        if (sNewProcess == null) {
            try {
                sNewProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                sNewProcess.setAccessible(true);
            } catch (Throwable t) {
                sNewProcess = null;
            }
        }
        return sNewProcess;
    }

    private static Process newProcess(String[] cmd) {
        final Method m = newProcessMethod();
        if (m == null) return null;
        try {
            return (Process) m.invoke(null, (Object) cmd, null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 执行

    /** 是否已经拿到 root（uid 0）。root 与「adb shell」差别很大：noexec 兜底要靠它。 */
    public static boolean isRoot() {
        Boolean cached = sRoot;
        if (cached != null) return cached;
        Result r = sh("id -u 2>/dev/null");
        boolean root = r.ok() && r.out.trim().equals("0");
        sRoot = root;
        return root;
    }

    public static void invalidateRootCache() {
        sRoot = null;
    }

    /** 同步执行并收集全部输出。 */
    public static Result exec(String... cmd) {
        if (!isReady()) return new Result(-1, "shizuku-not-ready");
        final Process p = newProcess(cmd);
        if (p == null) return new Result(-1, "newProcess-failed");
        final StringBuilder sb = new StringBuilder();
        read(p.getInputStream(), sb, null);
        read(p.getErrorStream(), sb, null);
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        return new Result(code, sb.toString());
    }

    /** 执行一段 sh 脚本。 */
    public static Result sh(String script) {
        return exec("/system/bin/sh", "-c", script);
    }

    /** 流式执行一段 sh 脚本：stdout/stderr 逐行回调（stderr 行前缀 {@code ! }）。 */
    public static Handle stream(String script, LineSink sink) {
        if (!isReady()) return null;
        final Process p = newProcess(new String[]{"/system/bin/sh", "-c", script});
        if (p == null) return null;
        final Handle h = new Handle(p);
        Thread a = new Thread(() -> read(p.getInputStream(), null, sink), "mw-blender-out");
        Thread b = new Thread(() -> read(p.getErrorStream(), null, sink), "mw-blender-err");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        return h;
    }

    private static void read(InputStream is, StringBuilder sb, LineSink sink) {
        if (is == null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.replace("\r", "").trim();
                if (line.isEmpty()) continue;
                if (sb != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                    if (sb.length() > 1 << 20) sb.delete(0, sb.length() - (1 << 20));
                } else if (sink != null) {
                    sink.onLine(line);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 单引号转义，拼 shell 命令用。 */
    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 设备上有没有某个命令（优先 toybox/系统，其次 XZ 兜底脚本用）。 */
    public static boolean hasCommand(String name) {
        Result r = sh("command -v " + name + " 2>/dev/null");
        return r.ok() && !r.out.trim().isEmpty();
    }

    /** 读一个文本文件（走 shell，因为 /data/local/tmp 里的文件 app 可能读不到）。 */
    public static String cat(String path, int maxBytes) {
        Result r = sh("head -c " + maxBytes + " " + q(path) + " 2>/dev/null");
        return r.out;
    }

    public static boolean deleteRec(String path) {
        return sh("rm -rf " + q(path)).ok();
    }

    /** 判断路径是否可写（用高权限试写一个临时文件）。 */
    public static boolean canWrite(String dir) {
        Result r = sh("touch " + q(dir + "/.mw_probe") + " 2>/dev/null && rm -f " + q(dir + "/.mw_probe"));
        return r.ok();
    }

    /** 是否已经装了 XZ 解码器（org.tukaani:xz，在线构建会打进 APK；离线构建则回退 shell xz）。 */
    public static synchronized boolean xzAvailable() {
        if (sXzAvailable == null) {
            boolean ok;
            try {
                Class.forName("org.tukaani.xz.XZInputStream");
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            sXzAvailable = ok;
        }
        return sXzAvailable;
    }

    /** 去掉路径里的软链接（用于 /data/local/tmp 可能被做成软链的机型）。 */
    public static String realPath(String path) {
        Result r = sh("readlink -f " + q(path) + " 2>/dev/null");
        String out = r.out.trim();
        return out.isEmpty() ? path : out;
    }

    public static Context app(Context c) {
        return c.getApplicationContext();
    }
}
