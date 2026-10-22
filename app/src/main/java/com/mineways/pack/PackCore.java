package com.mineways.pack;

/**
 * 材质包转换的像素/字符串算子（JNI 桥）。
 *
 * <p>native 实现在 {@code app/src/main/cpp/packcore/packcore.c} → {@code libpackcore.so}。
 * 四级命名解析链、HUD 语义子表、957 条权威映射与 2131 个 Java 原版命名全部编译期内置在
 * {@code name_data.h} 里，所以**没有任何资源文件需要随包分发**，也不联网。
 *
 * <p>C 侧只搬运 ARGB 整数像素；PNG 编解码由宿主通过
 * {@link BedrockPackConverter.ImageAdapter} 注入（Android 用 {@code android.graphics.Bitmap}）。
 */
public final class PackCore {

    static {
        System.loadLibrary("packcore");
    }

    private PackCore() {
    }

    /**
     * flipbook 大图 → Java 竖条动画帧条（像素级重排）。
     *
     * @param argb   大图像素（imgW*imgH，ARGB，每 int 一像素）
     * @param imgW   大图宽
     * @param imgH   大图高
     * @param tileW  帧宽
     * @param tileH  帧高
     * @param frames 帧索引序列（如 [16,17,…]；null = 全部帧按序）
     * @return 竖条像素（tileW × tileH*frames.length，ARGB）
     */
    public static native int[] flipbookToStrip(int[] argb, int imgW, int imgH,
            int tileW, int tileH, int[] frames);

    /** 像素 diff 统计（往返一致性核对用）：返回不一致的像素数。 */
    public static native long pixelDiff(int[] a, int[] b);

    /** 贴图路径映射（一级查表）；降级返回空串。 */
    public static native String mapTexturePath(String bedrockRel);

    /** 命名规范化：token 重排（planks_oak ↔ oak_planks）。 */
    public static native String canonicalName(String textureName);

    /** 基岩 {@code .lang} 文本 → Java lang JSON。 */
    public static native String langToJson(String langContent);

    /** 生成 {@code pack.mcmeta}（pack_format + 描述转义）。 */
    public static native String buildPackMcmeta(String description, int javaPackFormat);

    /** 完整四级解析链：HUD 子表 / 权威表 / 同名 / 同目录 token 重排 / 跨目录；降级返回空串。 */
    public static native String resolveTexturePath(String bedrockRel);

    /** 生成 1.21.4+ 的物品模型引用 JSON（layer0 指向贴图路径）。 */
    public static native String buildItemModel(String modelName, String texturePath);
}
