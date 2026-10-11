package com.mineimator.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目存储根目录。
 *
 * <p>背景：内核里的「更改项目文件夹」按钮走的是它自带的文件浏览器，而这个安卓移植版**只接了
 * "打开文件"（导入）这一条通道**，选目录/另存为没有出口，所以点了没反应。内核没有源码，加不了新调用。
 * 于是我们在外壳里给出等价能力：把内核的存储根在「应用私有目录」和「外部可见目录」之间切换
 * （后者在手机文件管理器里能直接看到、能拷进拷出），切换时自动把已有工程搬过去。
 */
public final class ProjectStorage {

    private static final String PREF = "mim_prefs";
    private static final String KEY_EXTERNAL = "storage_external";

    private ProjectStorage() {
    }

    /** 当前存储根（将作为 nativeStart 的 filesDir 传给内核）。 */
    public static File root(Context context) {
        if (useExternal(context)) {
            final File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                if (!ext.exists()) {
                    ext.mkdirs();
                }
                return ext;
            }
        }
        return context.getFilesDir();
    }

    public static boolean useExternal(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXTERNAL, false);
    }

    public static void setUseExternal(Context context, boolean value) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EXTERNAL, value).apply();
    }

    /** 找到真正的工程目录（内核可能写成 <root>/Mine-imator/Projects 或 <root>/Projects）。 */
    public static File projectsDir(File root) {
        final File a = new File(root, "Mine-imator/Projects");
        if (a.isDirectory()) {
            return a;
        }
        final File b = new File(root, "Projects");
        if (b.isDirectory()) {
            return b;
        }
        return a;
    }

    /** 给用户看的一段说明（真实路径 + 工程目录是否存在）。 */
    public static String describe(Context context) {
        final File root = root(context);
        final File projects = projectsDir(root);
        final StringBuilder sb = new StringBuilder();
        sb.append("当前存储根：\n").append(root.getAbsolutePath()).append("\n\n");
        sb.append("工程目录：\n").append(projects.getAbsolutePath())
                .append(projects.isDirectory() ? "（已存在）" : "（还没生成，进工作室新建工程后会出现）")
                .append("\n\n");
        sb.append(useExternal(context)
                ? "当前用「外部可见目录」：手机文件管理器里能直接看到、能拷进拷出。"
                : "当前用「应用私有目录」：最稳、最快，但文件管理器里看不到。")
                .append("\n\n注：内核里那个「更改文件夹」按钮是它自带的浏览器、安卓版没接通道，点它不会有反应；"
                        + "要换位置请用这里。");
        return sb.toString();
    }

    /** 切换存储根（异步搬运已有工程）。返回给用户看的结果文案。 */
    public static String switchTo(Context context, boolean external) {
        if (useExternal(context) == external) {
            return external ? "已经在用外部可见目录了。" : "已经在用应用私有目录了。";
        }
        final File from = root(context);
        final File oldProjects = projectsDir(from);
        setUseExternal(context, external);
        final File to = root(context);
        final File newProjects = new File(to, "Mine-imator/Projects");
        if (!oldProjects.isDirectory()) {
            return "已切换（旧目录里没有工程需要搬）。\n新位置：" + to.getAbsolutePath();
        }
        try {
            newProjects.getParentFile().mkdirs();
            copyTree(oldProjects, newProjects);
            return "已切换并搬运工程到：\n" + newProjects.getAbsolutePath()
                    + "\n\n重新进入工作室后生效。";
        } catch (Throwable t) {
            return "已切换，但搬运工程时出错：" + t.getMessage()
                    + "\n工程还留在：" + oldProjects.getAbsolutePath();
        }
    }

    private static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IllegalStateException("无法创建目录：" + dst);
            }
            final File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyTree(c, new File(dst, c.getName()));
                }
            }
            return;
        }
        if (dst.exists() && dst.length() == src.length()) {
            return;
        }
        final InputStream in = new FileInputStream(src);
        try {
            final OutputStream out = new FileOutputStream(dst);
            try {
                final byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** 需要一起扫描的根（内部 + 外部），供「我的作品」和导出监听使用。 */
    public static List<File> allRoots(Context context) {
        final List<File> out = new ArrayList<>();
        try {
            final File a = context.getFilesDir();
            if (a != null) {
                out.add(a);
            }
        } catch (Throwable ignored) {
        }
        try {
            final File ext = context.getExternalFilesDir(null);
            if (ext != null && ext.isDirectory()) {
                out.add(ext);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
