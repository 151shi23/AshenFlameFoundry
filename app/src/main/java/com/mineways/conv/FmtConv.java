package com.mineways.conv;

/**
 * 原生格式转换内核（libfmtconv.so）的 Java 侧。
 *
 * <p>内核实现：Assimp（模型互转，含 .blend）、zstd/zlib（解开压缩的 .blend）、stb（图片）。
 * native 返回约定：首字符 <code>'1'</code> = 成功、<code>'0'</code> = 失败，第二行起是给人看的说明。
 */
public final class FmtConv {

    public static final class Result {
        public boolean ok;
        public String message = "";

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    private static boolean loaded;
    private static String loadError = "";

    static {
        try {
            System.loadLibrary("fmtconv");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            loadError = String.valueOf(t.getMessage());
        }
    }

    private FmtConv() {
    }

    /** 内核是否可用（缺 .so 时返回 false，界面走纯平台路线）。 */
    public static boolean available() {
        return loaded;
    }

    public static String loadError() {
        return loadError;
    }

    // ---------------------------------------------------------------- 探测

    /** 魔数探测：返回如 "blend-zstd"、"png"、"glb"（空串 = 认不出）。 */
    public static String sniff(String path) {
        if (!loaded) {
            return "";
        }
        try {
            Result r = split(nativeSniff(path));
            return r.ok ? r.message : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 运行时真实可用的格式（Assimp 自己注册的列表）：import=…  export=… */
    public static String formats() {
        if (!loaded) {
            return "";
        }
        try {
            return split(nativeFormats()).message;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解析 {@link #formats()} 里的一组扩展名（key 传 "import" 或 "export"）。 */
    public static java.util.List<String> exts(String key) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : formats().split("\\r?\\n")) {
            final String t = line.trim();
            if (!t.startsWith(key + "=")) continue;
            final String body = t.substring(key.length() + 1);
            for (String e : body.split("[,\\s]+")) {
                final String s = e.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** 内核真正支持的导出格式（空 = 内核没加载，界面给保守清单）。 */
    public static java.util.List<String> exportExts() {
        return exts("export");
    }

    /** 内核真正支持的导入格式。 */
    public static java.util.List<String> importExts() {
        return exts("import");
    }

    // ---------------------------------------------------------------- 转换

    public static Result convertModel(String in, String out) {
        if (!loaded) {
            return new Result(false, "原生内核没加载成功：" + loadError);
        }
        try {
            return split(nativeConvertModel(in, out));
        } catch (Throwable t) {
            return new Result(false, "调用内核异常：" + t);
        }
    }

    public static Result convertImage(String in, String out, int quality) {
        if (!loaded) {
            return new Result(false, "原生内核没加载成功：" + loadError);
        }
        try {
            return split(nativeConvertImage(in, out, quality));
        } catch (Throwable t) {
            return new Result(false, "调用内核异常：" + t);
        }
    }

    public static Result imageInfo(String in) {
        if (!loaded) {
            return new Result(false, "原生内核没加载成功：" + loadError);
        }
        try {
            return split(nativeImageInfo(in));
        } catch (Throwable t) {
            return new Result(false, "调用内核异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Result split(String raw) {
        if (raw == null || raw.length() == 0) {
            return new Result(false, "内核没有返回内容");
        }
        final boolean ok = raw.charAt(0) == '1';
        final int nl = raw.indexOf('\n');
        return new Result(ok, nl >= 0 ? raw.substring(nl + 1) : "");
    }

    // ---------------------------------------------------------------- native
    private static native String nativeSniff(String path);

    private static native String nativeFormats();

    private static native String nativeConvertModel(String in, String out);

    private static native String nativeConvertImage(String in, String out, int quality);

    private static native String nativeImageInfo(String in);
}
