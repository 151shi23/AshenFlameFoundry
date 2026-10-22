package com.mineways;

import android.content.ComponentName;
import android.content.Intent;
import android.text.TextUtils;

import com.mineways.home.HomeIndex;

import java.io.File;

/**
 * AI 带着内容（一段文字 / 一个路径）打开功能页时的<b>投递表</b>。
 *
 * <p>为什么要有这张表：App 里的二十多个页面只有少数几个能吃得下「外部塞进来的内容」，
 * 其余页面收到一段文字什么也不会发生。如果不登记就直接把 extra 塞进 Intent，
 * 模型会收到「已带上内容」这种假成功，然后照着它往下编 —— 宁可如实说「这个页面不接收内容」。
 *
 * <p>新增一个可接收内容的页面，只需要在这里加一行 + 那个 Activity 里读一次 extra。
 */
public final class AiHandoff {

    /** 材质包目录这类「本地文件夹」入参。 */
    public static final String EXTRA_DIR = "ai_dir";

    private AiHandoff() {
    }

    /** 这个条目接收什么内容；返回 null = 不接收（describe 与 open 的结论都依据它）。 */
    public static String accepts(HomeIndex.Item tool) {
        String cls = className(tool);
        if ("PackConvertActivity".equals(cls)) {
            return "本地文件夹路径（待转换的材质包目录）";
        }
        return null;
    }

    /** 内容是不是真的投递上了（没投递上就别白跳一页，直接把原因回给模型）。 */
    public static boolean landed(Intent go, HomeIndex.Item tool) {
        String kind = accepts(tool);
        if (kind == null || go == null) {
            return false;
        }
        if ("PackConvertActivity".equals(className(tool))) {
            return go.hasExtra(EXTRA_DIR);
        }
        return false;
    }

    /**
     * 把内容投递到<b>调用方复制出来的</b> Intent 上（首页缓存的那条 intent 绝不能改，
     * 否则用户下次手点会带上 AI 留下的路径）。
     *
     * @return 一句给模型看的结论；null = 这个页面不接收内容。投递是否成功请用 {@link #landed}
     */
    public static String apply(Intent go, HomeIndex.Item tool, String input) {
        String kind = accepts(tool);
        if (kind == null || go == null || TextUtils.isEmpty(input)) {
            return null;
        }
        String cls = className(tool);
        if ("PackConvertActivity".equals(cls)) {
            File dir = dirOf(input);
            if (dir == null) {
                return "这个页面要的是一个文件夹路径（材质包目录），你给的「" + input + "」不是存在的文件夹。"
                        + "请让用户把路径整段贴过来，别自己猜一个";
            }
            go.putExtra(EXTRA_DIR, dir.getAbsolutePath());
            return "已把「" + dir.getName() + "」当作待转换的材质包目录带进转换页："
                    + dir.getAbsolutePath();
        }
        return null;
    }

    private static String className(HomeIndex.Item tool) {
        if (tool == null || tool.intent == null) {
            return "";
        }
        ComponentName cn = tool.intent.getComponent();
        if (cn == null) {
            return "";
        }
        String full = cn.getClassName();
        int p = full == null ? -1 : full.lastIndexOf('.');
        return p < 0 ? String.valueOf(full) : full.substring(p + 1);
    }

    /** 容忍 `file://`、反引号、结尾斜杠这些常见写法；不是真实在的目录就返回 null。 */
    private static File dirOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            return null;
        }
        s = s.replace("`", "").replace("\"", "").replace("'", "");
        if (s.startsWith("file://")) {
            s = s.substring("file://".length());
        }
        if (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            File f = new File(s);
            return f.isDirectory() && f.canRead() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
