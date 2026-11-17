package com.mineways.mocap;

/**
 * 手部 21 点 → 手掌 + 五指骨骼旋转（Bedrock/Blockbench 内部欧拉，度）。
 *
 * <p>骨骼约定与 {@link PoseSolver} 一致：每根骨的静止方向都是 (0,-1,0)（自然下垂），
 * 输出的是"把静止方向转到目标方向"的 ZYX 欧拉；躯干基来自摆姿解算（side/up/fwd），
 * 所以手部与身体在同一套世界坐标里，不受拍摄视角影响。
 *
 * <p>骨骼命名（每只手 6 根）：{@code leftHand}/{@code rightHand} +
 * {@code leftThumb/leftIndex/leftMiddle/leftRing/leftPinky}（右手同理）。
 * 手指骨的旋转是**相对手掌**的（手掌转完再顺手指数），导出到模型时手指骨必须挂在
 * 对应 Hand 骨下面，否则会双重旋转。
 *
 * <p>点序（MediaPipe Hand Landmarker）：0 腕；1-4 拇指；5-8 食指；9-12 中指；
 * 13-16 无名指；17-20 小指，每指依次 MCP/PIP/DIP/TIP。
 */
public final class HandSolver {
    private HandSolver() {}

    public static final String[] FINGER_NAMES = {"Thumb", "Index", "Middle", "Ring", "Pinky"};
    private static final int[][] CHAIN = {
            {1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
    private static final int WRIST = 0, MIDDLE_MCP = 9, INDEX_MCP = 5, PINKY_MCP = 17;
    /** 掌轴/指节退化门限（像素单位，已按躯干长度归一化后约 0.002 躯干）。 */
    private static final double MIN_SEG = 1e-6;

    public static final class Result {
        public double[] hand;
        /** 指名（Thumb…）→ 欧拉（度，相对手掌）。 */
        public final java.util.Map<String, double[]> fingers = new java.util.LinkedHashMap<String, double[]>();
        /** 手指点缺失时的提示（哪根指没解出来）。 */
        public final java.util.Set<String> missing = new java.util.HashSet<String>();
    }

    /**
     * @param pts  21 个手部关键点（相机空间统一单位）
     * @param side 躯干基三轴（与 PoseSolver 的 comp 完全相同）
     */
    public static Result solve(Vec3[] pts, Vec3 side, Vec3 up, Vec3 fwd) {
        if (pts == null || pts.length < 21) return null;
        Vec3 palmFwdW = Vec3.norm(Vec3.sub(pts[MIDDLE_MCP], pts[WRIST]));
        Vec3 palmSideW = Vec3.norm(Vec3.sub(pts[INDEX_MCP], pts[PINKY_MCP]));
        if (palmFwdW.len() < MIN_SEG || palmSideW.len() < MIN_SEG) return null;

        Vec3 tFwd = comp(palmFwdW, side, up, fwd);
        Vec3 tSide = comp(palmSideW, side, up, fwd);
        if (tFwd.len() < MIN_SEG || tSide.len() < MIN_SEG) return null;

        Result r = new Result();
        Vec3[] rest2 = {new Vec3(0, -1, 0), new Vec3(1, 0, 0)};
        Vec3[] tgt2 = {Vec3.norm(tFwd), Vec3.norm(tSide)};
        double[] init = {0, -Math.asin(clamp(tgt2[1].z, -1, 1)),
                -Math.asin(clamp(tgt2[0].x, -1, 1))};
        r.hand = EulerFit.solve(new EulerFit.Problem(rest2, tgt2), init);

        double[] m = EulerBedrock.toMatrix(r.hand[0], r.hand[1], r.hand[2]);
        // 手骨局部 = R^T · 世界（R 是手→世界的旋转）
        for (int f = 0; f < CHAIN.length; f++) {
            Vec3 mcp = pts[CHAIN[f][0]];
            Vec3 pip = pts[CHAIN[f][1]];
            Vec3 dW = Vec3.norm(Vec3.sub(pip, mcp));
            if (dW.len() < MIN_SEG) {
                r.missing.add(FINGER_NAMES[f]);
                continue;
            }
            Vec3 dT = comp(dW, side, up, fwd);
            Vec3 local = new Vec3(
                    m[0] * dT.x + m[3] * dT.y + m[6] * dT.z,
                    m[1] * dT.x + m[4] * dT.y + m[7] * dT.z,
                    m[2] * dT.x + m[5] * dT.y + m[8] * dT.z);
            if (local.len() < MIN_SEG) {
                r.missing.add(FINGER_NAMES[f]);
                continue;
            }
            Vec3 tgt = Vec3.norm(local);
            double[] e = EulerFit.solveMask(
                    new EulerFit.Problem(new Vec3[]{new Vec3(0, -1, 0)}, new Vec3[]{tgt}),
                    new double[]{-Math.asin(clamp(tgt.z, -1, 1)), 0, Math.asin(clamp(tgt.x, -1, 1))},
                    new boolean[]{true, false, true});
            r.fingers.put(FINGER_NAMES[f], e);
        }
        return r;
    }

    public static String boneName(boolean left, String finger) {
        return (left ? "left" : "right") + finger;
    }

    private static Vec3 comp(Vec3 v, Vec3 side, Vec3 up, Vec3 fwd) {
        return new Vec3(Vec3.dot(v, side), Vec3.dot(v, up), Vec3.dot(v, fwd));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
