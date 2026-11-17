package com.mineways.astrbot;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import com.mineways.blender.RootShell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 部署前环境自检：先判这台手机能不能跑这套 proot 双容器。
 *
 * <p>门槛值云控可调（{@code astrbot_cfg.min_free_mb} / {@code require_arm64}）；
 * 任何一项必需条件不满足都会给出原因，并禁用「一键部署」。</p>
 */
public final class AstrBotPreflight {

    public static final class Item {
        public final String label;
        public final boolean ok;
        public final boolean required;
        public final String detail;

        Item(String label, boolean ok, boolean required, String detail) {
            this.label = label;
            this.ok = ok;
            this.required = required;
            this.detail = detail;
        }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();

        public boolean ok() {
            for (Item i : items) {
                if (i.required && !i.ok) return false;
            }
            return true;
        }

        /** 给按钮下方的原因行。 */
        public String reason() {
            for (Item i : items) {
                if (i.required && !i.ok) return i.label + "：" + i.detail;
            }
            return "";
        }
    }

    private AstrBotPreflight() {
    }

    public static Result run(Context c) {
        final Result r = new Result();

        boolean arm64 = false;
        final StringBuilder abis = new StringBuilder();
        try {
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abis.length() > 0) abis.append('/');
                abis.append(abi);
                if ("arm64-v8a".equals(abi)) arm64 = true;
            }
        } catch (Throwable ignored) {
        }
        r.items.add(new Item("CPU 架构", arm64, AstrBotEnv.requireArm64(c),
                arm64 ? "arm64-v8a" : ("非 arm64（" + abis + "）")));

        r.items.add(new Item("系统版本", Build.VERSION.SDK_INT >= 26, true,
                "Android " + Build.VERSION.RELEASE));

        final boolean shizuku = RootShell.isReady();
        r.items.add(new Item("Shizuku 权限", shizuku, true,
                shizuku ? "已授权" : "未授权（容器必须放 /data/local/tmp）"));

        final long freeMb = freeMb(c);
        final int need = AstrBotEnv.minFreeMb(c);
        r.items.add(new Item("剩余空间", freeMb < 0 || freeMb >= need, true,
                (freeMb < 0 ? "未知" : freeMb + " MB") + " / 需要 ≥ " + need + " MB"));

        boolean exec = false;
        String execDetail = "未授权，无法检测";
        if (shizuku) {
            exec = probeExec();
            execDetail = exec ? "/data/local/tmp 可执行" : "/data/local/tmp 不可执行";
        }
        r.items.add(new Item("运行目录", exec, true, execDetail));

        final boolean root = RootShell.isRoot();
        r.items.add(new Item("Root", root, false, root ? "有（可选）" : "无（不需要）"));
        return r;
    }

    private static long freeMb(Context c) {
        try {
            final StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
            return fs.getAvailableBytes() / (1024L * 1024L);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 真写一个脚本并执行：只 test -x 不可靠，必须真跑过。 */
    private static boolean probeExec() {
        final String p = "/data/local/tmp/.mw_astrbot_probe.sh";
        try {
            RootShell.sh("printf '#!/system/bin/sh\\necho MW_PROBE_OK\\n' > " + RootShell.q(p)
                    + " && chmod 755 " + RootShell.q(p));
            final RootShell.Result r = RootShell.sh(RootShell.q(p));
            return r.ok() && r.out != null && r.out.contains("MW_PROBE_OK");
        } catch (Throwable t) {
            return false;
        } finally {
            RootShell.sh("rm -f " + RootShell.q(p));
        }
    }

    static String mib(long mb) {
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024f);
    }
}
