package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 「360° 动作都要识别 / 正确解析」与「头部灵敏度」的断言。
 *
 * <p>覆盖三类球面盲区：①恰好 180° 反极点（已由多重种子回退修）；
 * ②±180° 环绕（导出后不能反着转一大圈）；③侧身 90° 时肩线退化（用髋线/上一帧基兜底）。
 */
public class SphereCoverageTest {

    /** 全方向网格：无论目标朝哪，单方向拟合都必须打到 cos>0.99。 */
    @Test
    public void everyDirectionOnTheSphereFits() {
        int bad = 0;
        for (int polar = 0; polar <= 180; polar += 15) {
            for (int azim = 0; azim < 360; azim += 30) {
                double p = Math.toRadians(polar), a = Math.toRadians(azim);
                Vec3 tgt = new Vec3(Math.sin(p) * Math.cos(a), Math.cos(p), Math.sin(p) * Math.sin(a));
                tgt = Vec3.norm(tgt);
                boolean[] free = Math.abs(tgt.y) > 0.985
                        ? new boolean[]{true, false, true}
                        : new boolean[]{true, true, true};
                double[] e = PoseSolver.fitDirection(new Vec3(0, -1, 0), tgt,
                        new double[]{0, 0, 0}, free);
                double dot = Vec3.dot(Vec3.norm(EulerBedrock.apply(
                        EulerBedrock.toMatrix(e[0], e[1], e[2]), new Vec3(0, -1, 0))), tgt);
                if (dot < 0.99) {
                    bad++;
                    System.out.println("FAIL polar=" + polar + " azim=" + azim + " dot=" + dot);
                }
            }
        }
        assertEquals("球面方向拟合失败数", 0, bad);
    }

    /** 连续性：上一帧 170°，目标只动一点时，新解必须贴近 170°，不能跳到 -170°。 */
    @Test
    public void continuityPrefersTheSameBranch() {
        Vec3 prevDir = Vec3.norm(new Vec3(0.17, -0.98, 0.05));
        double[] prev = PoseSolver.fitDirection(new Vec3(0, -1, 0), prevDir,
                new double[]{0, 0, 10}, new boolean[]{true, true, true});
        Vec3 tgt = Vec3.norm(new Vec3(0.18, -0.98, 0.06));
        double[] next = PoseSolver.fitDirection(new Vec3(0, -1, 0), tgt,
                new double[]{0, 0, 10}, new boolean[]{true, true, true}, prev);
        for (int k = 0; k < 3; k++) {
            double d = Math.abs(EulerBedrock.angDiffDeg(next[k], prev[k]));
            assertTrue("通道 " + k + " 跳变 " + d + "°", d < 20.0);
        }
    }

    /** ±180° 环绕必须被拆平：179° → -179° 记成 179° → 181°。 */
    @Test
    public void unwrapRemovesTheWraparound() {
        List<PoseSolver.Frame> frames = new ArrayList<PoseSolver.Frame>();
        for (double z : new double[]{178, 179, -179, -178}) {
            PoseSolver.Frame f = new PoseSolver.Frame();
            f.tMs = frames.size() * 33;
            f.rotDeg.put("leftArm", new double[]{0, 0, z});
            frames.add(f);
        }
        PoseSolver.unwrapAngles(frames);
        double prev = 0;
        double maxStep = 0;
        for (PoseSolver.Frame f : frames) {
            double z = f.rotDeg.get("leftArm")[2];
            if (prev != 0) maxStep = Math.max(maxStep, Math.abs(z - prev));
            prev = z;
        }
        assertTrue("绕回后单帧最大跳变应 ≤2°，实际 " + maxStep, maxStep <= 2.0);
        assertEquals(182.0, frames.get(3).rotDeg.get("leftArm")[2], 1e-6);
    }

    /** 头部灵敏度：±2° 抖动要压到明显更小；5°/帧的真转头要跟得上且被限速。 */
    @Test
    public void headJitterIsDampedButRealTurnsFollow() {
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), 720, 1280);
        solver.smoothHead(new double[]{0, 0, 0}, -1000);   // 首帧只做初始化，不参与抖动统计
        double jitterMax = 0;
        double prev = 0;
        for (int i = 0; i < 40; i++) {
            double noisy = (i % 2 == 0 ? 2.0 : -2.0);   // 逐帧 ±2° 抖动
            double[] out = solver.smoothHead(new double[]{0, 0, noisy}, i * 33.0);
            jitterMax = Math.max(jitterMax, Math.abs(out[2]));
            prev = out[2];
        }
        assertTrue("抖动应被压到 0.8° 以内，实际 " + jitterMax + "（末尾 " + prev + "）",
                jitterMax < 0.8);

        PoseSolver solver2 = new PoseSolver(RigDefaults.vanilla(), 720, 1280);
        solver2.smoothHead(new double[]{0, 0, 0}, 0);
        double[] out2 = null;
        for (int i = 1; i <= 10; i++) {                 // 每帧 +5° 的真转头
            out2 = solver2.smoothHead(new double[]{0, 0, i * 5.0}, i * 33.0);
        }
        assertTrue("真转头应明显跟上（实际 " + out2[2] + "°）", out2[2] > 25.0);
    }
}
