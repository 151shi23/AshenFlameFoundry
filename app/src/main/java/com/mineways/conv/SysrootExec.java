package com.mineways.conv;

import android.content.Context;

import com.mineways.blender.BlenderEnv;
import com.mineways.blender.RootShell;

import java.io.File;

/**
 * 跑 sysroot（Debian/glibc）里的命令行程序，例如 ffmpeg。
 *
 * <p>关键点和 Blender 一样：这类程序是 glibc 动态链接的，Android 没有 glibc，
 * 必须用 sysroot 里的 {@code ld-linux} 显式加载，并且把 {@code LD_LIBRARY_PATH} 指到 sysroot
 * （否则会先摸到 Android 的 bionic 同名库，直接崩）。</p>
 */
public final class SysrootExec {

    public static final class Result {
        public final boolean ok;
        public final int code;
        public final String out;

        Result(boolean ok, int code, String out) {
            this.ok = ok;
            this.code = code;
            this.out = out == null ? "" : out;
        }

        /** 取最后几行当人话。 */
        public String tail(int lines) {
            final String[] all = out.trim().split("\\r?\\n");
            final StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(all[i]);
            }
            return sb.toString();
        }
    }

    private SysrootExec() {
    }

    public static boolean has(Context c, String binName) {
        return BlenderEnv.sysrootBin(c, binName) != null;
    }

    /** 同步执行（带超时）。{@code args} 里自己带引号，路径直接拼。 */
    public static Result run(Context c, String binName, String args, int timeoutSec) {
        final String loader = BlenderEnv.loaderPath(c);
        final File bin = BlenderEnv.sysrootBin(c, binName);
        if (loader.isEmpty() || bin == null) {
            return new Result(false, -1, "没有 " + binName + "（需要先装扩展组件）");
        }
        if (!RootShell.isReady()) {
            return new Result(false, -1, "Shizuku 未授权，跑不了扩展引擎");
        }
        final StringBuilder cmd = new StringBuilder();
        cmd.append(BlenderEnv.envPrefix(c));
        cmd.append("cd ").append(RootShell.q(BlenderEnv.tmpDir(c).getAbsolutePath())).append('\n');
        cmd.append("timeout ").append(Math.max(10, timeoutSec)).append(' ')
                .append(RootShell.q(loader))
                .append(" --library-path ").append(RootShell.q(BlenderEnv.libPath(c)))
                .append(' ').append(RootShell.q(bin.getAbsolutePath()))
                .append(' ').append(args)
                .append(" 2>&1 | tail -20\n");
        // 注：timeout 是 toybox 自带命令；某些老机型没有就退化成直接跑
        final RootShell.Result r = RootShell.sh(cmd.toString());
        final boolean ok = r.ok() && !r.out.contains("Permission denied") && !r.out.contains("not found");
        return new Result(ok, r.code, r.out);
    }
}
