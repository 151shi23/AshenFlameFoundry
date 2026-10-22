package com.mineways.ui;

/**
 * 白色流光的唯一几何源：光带的位置、方向、周期只在这里算一次，Compose 背板
 * （MwGlass.rememberFlowBackdrop / MwScreen 的 drawBehind）与原生 View 侧
 * （MwViews.FlowLightDrawable 页底、MwSurfaceDrawable 面板折射）都从这里取数。
 *
 * <p>为什么是解析函数而不是一张位图：玻璃「折射」的物理是采样背后的光。背景是我们自己画的
 * 已知函数，玻璃层直接按同一函数取值再放大偏移一点，就是零成本的折射 —— 不用捕图层，
 * 二十多个工具页的每颗按钮都赔得起。位图方案（每页软渲染）上一版已经证明会在低端机上拖帧。
 *
 * <p>运动学：光带沿固定法线方向往返漂移（正弦，循环处无跳变），主带 40s 一个来回，
 * 副带 67s 反相 —— 两道光错开走，底才不会像一条刷出来的高光。倾斜（陀螺仪）由调用方
 * 折进法线角，这里只管时间。
 */
public final class MwFlow {

    /** 主带周期（毫秒，一个来回）。 */
    public static final long PERIOD_MAIN = 40_000L;
    /** 副带周期：与主带不同步，两道光的相位永远错开。 */
    public static final long PERIOD_SECOND = 67_000L;

    /** 光带法线的基准角（弧度）：约 -52°，光从右上往左下走。 */
    public static final float BASE_ANGLE = (float) Math.toRadians(-52.0);

    /** 光带中心在法线方向上的行程（相对画布短边）：±0.55。 */
    public static final float TRAVEL = 0.55f;

    private MwFlow() {
    }

    /**
     * 算一条光带的渐变端点。
     *
     * @param w,h    画布尺寸（像素）
     * @param t      时间源（uptimeMillis / 帧时间）
     * @param period 周期
     * @param phaseShift 相位偏移 0..1（副带用 0.5 反相）
     * @param angle  法线角（弧度）；调用方把倾斜折进来
     * @param out    长度 ≥4：out[0..1] = 渐变起点，out[2..3] = 终点（像素坐标，中心对称铺满）
     */
    public static void band(float w, float h, long t, long period, float phaseShift,
            float angle, float[] out) {
        float phase = ((t % period) / (float) period + phaseShift) % 1.0f;
        // 正弦往返：-1..1，循环处速度连续，不会跳
        float c = (float) Math.sin(phase * Math.PI * 2.0) * TRAVEL;
        // 中心沿法线偏移；半径取对角线一半以上，保证渐变两端都盖出画布
        float cx = w * 0.5f + (float) Math.cos(angle) * c * Math.min(w, h);
        float cy = h * 0.5f + (float) Math.sin(angle) * c * Math.min(w, h);
        float half = (float) Math.hypot(w, h) * 0.62f;
        float dx = (float) Math.cos(angle) * half;
        float dy = (float) Math.sin(angle) * half;
        out[0] = cx - dx;
        out[1] = cy - dy;
        out[2] = cx + dx;
        out[3] = cy + dy;
    }

    /** 高斯剖面：两端透明、中峰最亮。stops 固定，调用方只填 alpha。 */
    public static final float[] STOPS = {0f, 0.32f, 0.5f, 0.68f, 1f};

    /** 按峰值 alpha 算五个渐变停靠点的 ARGB（白色光带）。 */
    public static int[] colors(float peak) {
        int a0 = Math.round(peak * 0.45f * 255f);
        int a1 = Math.round(peak * 255f);
        return new int[]{
                0x00FFFFFF,
                (a0 << 24) | 0x00FFFFFF,
                (a1 << 24) | 0x00FFFFFF,
                (a0 << 24) | 0x00FFFFFF,
                0x00FFFFFF,
        };
    }
}
