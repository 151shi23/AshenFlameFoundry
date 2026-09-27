package com.mineways;

import java.util.Locale;

/**
 * P3D（Prisma3D）兼容策略 —— 集中的"公版"阈值与判定，供各导出器统一调用。
 *
 * <p>背景：Prisma3D 基于 Unity + TriLib，导入超大 OBJ 会因顶点索引/内存爆掉而崩溃或极卡。
 * 这里用一套通用阈值（不针对任何单个模型）在导出侧把关：超出即降级或提示。
 *
 * <p>阈值来源：
 * <ul>
 *   <li>{@link #MAX_FACES}：Unity 默认 16 位索引缓冲的安全线（65535），超过必须拆分为多组/多文件；</li>
 *   <li>{@link #TARGET_FACES}：超限后的降面目标（一半），保证外观基本不变；</li>
 *   <li>{@link #MAX_TEXTURE}：贴图边长上限，控制显存峰值（2048 在移动端 P3D 上很稳）。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   // 导出 OBJ 文本前
 *   P3dCompat.FaceInfo info = P3dCompat.analyzeObj(objText);
 *   if (!info.ok()) { status += "  ⚠ " + P3dCompat.advice(info); }
 *   // 若需要降面（方块/网格类模型）：
 *   int step = P3dCompat.suggestStep(info.faces);
 * </pre>
 */
public final class P3dCompat {

    /** Unity 16 位索引安全线：超过这个面数，P3D 导入就会非常吃力甚至崩溃。 */
    public static final int MAX_FACES = 65535;

    /** 超限后的降面目标面数（约一半），外观基本不变但导入稳定。 */
    public static final int TARGET_FACES = 32768;

    /** 贴图边长上限（显存预算）。超过就缩到该值。 */
    public static final int MAX_TEXTURE = 2048;

    /** P3D 兼容模式总开关（UI 上可关，默认开）。 */
    public static boolean enabled = true;

    private P3dCompat() {
    }

    /** OBJ 面数/顶点数统计结果。 */
    public static final class FaceInfo {
        public final int faces;
        public final int verts;
        public final int textures;

        FaceInfo(int faces, int verts, int textures) {
            this.faces = faces;
            this.verts = verts;
            this.textures = textures;
        }

        public boolean ok() {
            return !enabled || faces <= MAX_FACES;
        }

        public boolean heavy() {
            return faces > TARGET_FACES;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "面 %d / 顶点 %d / 贴图 %d", faces, verts, textures);
        }
    }

    /**
     * 统计 OBJ 文本的规模。只做前缀匹配，O(行数)，可安全用于几十万行的大文件。
     */
    public static FaceInfo analyzeObj(String obj) {
        if (obj == null || obj.isEmpty()) {
            return new FaceInfo(0, 0, 0);
        }
        int faces = 0, verts = 0, slices = 1;
        int from = 0;
        while (from < obj.length()) {
            int nl = obj.indexOf('\n', from);
            int end = nl < 0 ? obj.length() : nl;
            if (end - from >= 2) {
                char c0 = obj.charAt(from);
                char c1 = obj.charAt(from + 1);
                if (c0 == 'f' && c1 == ' ') {
                    faces++;
                } else if (c0 == 'v' && c1 == ' ') {
                    verts++;
                } else if (c0 == 'g' && c1 == ' ') {
                    slices++;
                }
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return new FaceInfo(faces, verts, slices);
    }

    /** 给出人话建议（用于状态栏/提示）。 */
    public static String advice(FaceInfo info) {
        if (info.ok()) {
            return "规模适合 P3D 导入（" + info + "）";
        }
        int step = suggestStep(info.faces);
        return "超出 P3D 安全面数（" + info.faces + " > " + MAX_FACES + "），建议按 1/" + step
                + " 降面后导出（目标 ≈ " + TARGET_FACES + " 面），否则 P3D 可能导入崩溃";
    }

    /** 建议的整数降面倍数（1 = 不降）。 */
    public static int suggestStep(int faces) {
        int step = 1;
        while (faces / (step * step) > TARGET_FACES && step < 64) {
            step++;
        }
        return step;
    }

    /** 贴图是否需要缩到上限。 */
    public static boolean needsTextureShrink(int width, int height) {
        return enabled && (width > MAX_TEXTURE || height > MAX_TEXTURE);
    }

    /** 按上限等比缩放后的尺寸（返回 int[2]，永不放大）。 */
    public static int[] fitTexture(int width, int height) {
        if (!needsTextureShrink(width, height)) {
            return new int[]{width, height};
        }
        float k = (float) MAX_TEXTURE / Math.max(width, height);
        return new int[]{Math.max(1, (int) (width * k)), Math.max(1, (int) (height * k))};
    }
}
