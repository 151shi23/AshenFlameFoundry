package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「向上抬臂 / 向前向后挥拳识别不了」的回归：
 * ① 手臂竖直上举在欧拉空间是 180° 反极点，老的从 0 出发会原地返回 0（手臂不动）；
 * ② 出拳时手臂在画面里只剩一小截，只用 x/y 会算成朝下；要靠长度先验补回 fwd 分量。
 */
public class ArmReachTest {

    private static final int W = 1000, H = 1000;

    /** 只给躯干 + 指定手臂/腿点可见，其余全不可见。 */
    private static Pose33 pose(double lwX, double lwY, double lwZ,
                               double leX, double leY, double leZ) {
        Pose33 p = new Pose33();
        p.tMs = 0;
        set(p, Pose33.L_SHOULDER, 0.55, 0.40, 0);
        set(p, Pose33.R_SHOULDER, 0.45, 0.40, 0);
        set(p, Pose33.L_HIP, 0.53, 0.60, 0);
        set(p, Pose33.R_HIP, 0.47, 0.60, 0);
        set(p, Pose33.L_ELBOW, leX, leY, leZ);
        set(p, Pose33.L_WRIST, lwX, lwY, lwZ);
        set(p, Pose33.R_ELBOW, 0.45, 0.50, 0);
        set(p, Pose33.R_WRIST, 0.45, 0.60, 0);
        set(p, Pose33.L_KNEE, 0.53, 0.70, 0);
        set(p, Pose33.L_ANKLE, 0.53, 0.80, 0);
        set(p, Pose33.R_KNEE, 0.47, 0.70, 0);
        set(p, Pose33.R_ANKLE, 0.47, 0.80, 0);
        return p;
    }

    private static void set(Pose33 p, int i, double x, double y, double z) {
        p.x[i] = x;
        p.y[i] = y;
        p.z[i] = z;
        p.vis[i] = 1;
    }

    /** 解出的骨方向（rotDeg 已经是内部欧拉，直接用；本姿势的躯干基与世界轴重合）。 */
    private static Vec3 armDir(Pose33 p) {
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), W, H);
        PoseSolver.Frame f = solver.solveFrame(p);
        double[] e = f.rotDeg.get("leftArm");
        assertNotNull(e);
        return Vec3.norm(EulerBedrock.apply(
                EulerBedrock.toMatrix(e[0], e[1], e[2]), new Vec3(0, -1, 0)));
    }

    @Test
    public void straightUpArmIsRecognized() {
        // 腕正对肩上方（y 从 0.40 抬到 0.20）——精确 180°，正是老代码卡死的情形
        Vec3 d = armDir(pose(0.55, 0.20, 0, 0.55, 0.30, 0));
        assertTrue("手臂应朝上，实际 y=" + d.y, d.y > 0.95);
    }

    @Test
    public void punchTowardCameraGetsForwardDepth() {
        // 腕几乎缩回肩部（投影极短）+ 明显更靠近相机（z=-0.62）
        Vec3 d = armDir(pose(0.552, 0.398, -0.62, 0.55, 0.42, -0.30));
        assertTrue("向前出拳应指向相机，实际 z=" + d.z, d.z > 0.6);
    }

    @Test
    public void punchAwayFromCameraGetsBackwardDepth() {
        Vec3 d = armDir(pose(0.552, 0.398, 0.62, 0.55, 0.42, 0.30));
        assertTrue("向后挥拳应背离相机，实际 z=" + d.z, d.z < -0.6);
    }

    /** 反极点回退本身：rest 朝下 → 目标朝上（恰好 180°）。 */
    @Test
    public void antipodalFitFallsBackToOtherSeeds() {
        Vec3 rest = new Vec3(0, -1, 0);
        Vec3 tgt = new Vec3(0, 1, 0);
        double[] e = PoseSolver.fitDirection(rest, tgt, new double[]{0, 0, 0},
                new boolean[]{true, true, true});
        double dot = Vec3.dot(Vec3.norm(EulerBedrock.apply(
                EulerBedrock.toMatrix(e[0], e[1], e[2]), rest)), tgt);
        assertTrue("必须拟合到朝上，实际 cos=" + dot, dot > 0.99);
    }

    /**
     * 关键回归：投影短但 z 很弱时**绝不**凭空造出拳方向。
     * 旧实现会沿用上一帧的符号把 z 硬推到 ±1，日常弯肘/抱胸因此被随机推成前伸，
     * 整段动画看起来"像随地乱做"。
     */
    @Test
    public void weakDepthNeverInventsAPunch() {
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), 720, 1280);
        Vec3 raw = new Vec3(0.05, -0.05, 0.02);
        for (int i = 0; i < 6; i++) {
            Vec3 out = solver.completeArmDepth(raw, "leftArm");
            // 注：本工程的 JUnit 没有 (消息,double,double,double) 重载，只能用三参数版
            assertEquals(0.02, out.z, 1e-9);
        }
    }
}
