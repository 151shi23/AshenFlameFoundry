package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 头部稳定性 / 容错回归：
 * - 转头不许出现 180° 翻转、360° 转圈（解剖学限位 + 连续性）；
 * - 躯干退化的一帧不许把整段解算抛异常；
 * - NaN 不许漏进欧拉（否则导出 JSON 非法）。
 */
public class PoseHeadTest {

    private static double maxAbs(double[] v) {
        double m = 0;
        for (double d : v) m = Math.max(m, Math.abs(d));
        return m;
    }

    @Test
    public void clampHeadRejectsImpossibleAngles() {
        double[] c = PoseSolver.clampHead(new double[]{200, 179, -170});
        assertEquals(PoseSolver.HEAD_PITCH_LIMIT, c[0], 1e-9);
        assertEquals(PoseSolver.HEAD_YAW_LIMIT, c[1], 1e-9);
        assertEquals(-PoseSolver.HEAD_ROLL_LIMIT, c[2], 1e-9);
    }

    /** 只有“头向上”一条弱约束时，等价解有一整圈；必须挑离上一帧最近的那组，而不是 yaw=180。 */
    @Test
    public void fitHeadPicksContinuousBranch() {
        Vec3[] rest = {new Vec3(0, 1, 0)};
        Vec3[] tgt = {new Vec3(0, 1, 0)};
        double[] nearZero = PoseSolver.fitHead(rest, tgt, new double[]{180, 180, 180},
                new double[]{0, 0, 0});
        assertTrue("应回到 0 附近，实际 " + maxAbs(nearZero), maxAbs(nearZero) < 5);

        double[] near80 = PoseSolver.fitHead(rest, tgt, new double[]{0, 0, 0},
                new double[]{0, 80, 0});
        assertTrue("应贴着上一帧 80°，实际 yaw=" + near80[1], Math.abs(near80[1] - 80) < 5);
    }

    /** 合成一段“头转到 170°”（超出人体范围）的动作：输出必须被限位且不许帧间跳 180°。 */
    @Test
    public void headYawIsLimitedAndContinuous() {
        SyntheticMocap.Gen g = new SyntheticMocap.Gen();
        g.name = "headturn";
        g.loop = false;
        g.durSec = 2.0;
        g.fps = 30;
        g.angles = new SyntheticMocap.Angles() {
            @Override
            public double[] at(String kind, double tSec) {
                if ("head".equals(kind)) return new double[]{0, 170.0 * tSec / 2.0, 0};
                if ("armL".equals(kind) || "armR".equals(kind)) return new double[]{8, 0, 0};
                if ("legL".equals(kind) || "legR".equals(kind)) return new double[]{0, 0, 0};
                return new double[]{0, 0, 0};
            }
        };
        SyntheticMocap.Result r = SyntheticMocap.generate(g, 7L);
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), r.clip.imgW, r.clip.imgH);
        List<PoseSolver.Frame> frames = solver.solveClip(r.clip.frames);

        double prevYaw = 0;
        for (int i = 0; i < frames.size(); i++) {
            double[] e = frames.get(i).rotDeg.get("head");
            assertTrue("头 yaw 超限：" + e[1], Math.abs(e[1]) <= PoseSolver.HEAD_YAW_LIMIT + 0.5);
            assertTrue("头 pitch 超限：" + e[0], Math.abs(e[0]) <= PoseSolver.HEAD_PITCH_LIMIT + 0.5);
            assertTrue("头 roll 超限：" + e[2], Math.abs(e[2]) <= PoseSolver.HEAD_ROLL_LIMIT + 0.5);
            if (i > 0) {
                double d = Math.abs(EulerBedrock.angDiffDeg(e[1], prevYaw));
                assertTrue("第 " + i + " 帧头 yaw 跳了 " + d + "°（应为连续转动）", d < 20);
            }
            prevYaw = e[1];
        }
        // 末帧应转到限位附近（证明不是靠“一直不动”通过测试）
        double[] last = frames.get(frames.size() - 1).rotDeg.get("head");
        assertTrue("头应转到限位附近，实际 " + last[1], Math.abs(last[1]) > 60);
    }

    /** 躯干关键点叠在一起（检测到半个人）时，不许抛异常，整段仍能解出五根骨。 */
    @Test
    public void degenerateTorsoDoesNotAbortTheClip() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        int n = r.clip.frames.size();
        // 整段都退化成“画面中心一个点”：以前 torsoPx≈0 会 throw，整段转换直接失败
        for (Pose33 p : r.clip.frames) {
            for (int k = 0; k < 33; k++) {
                p.x[k] = 0.5;
                p.y[k] = 0.5;
                p.z[k] = 0;
                p.vis[k] = 1.0;
                p.pres[k] = 1.0;
            }
        }
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), r.clip.imgW, r.clip.imgH);
        List<PoseSolver.Frame> frames = solver.solveClip(r.clip.frames);
        assertEquals(n, frames.size());
        for (PoseSolver.Frame f : frames) {
            assertTrue("每帧都必须有五根骨", f.rotDeg.size() >= 5);
            assertTrue("退化帧应被标成 missing，实际 " + f.missing.size(), f.missing.size() >= 5);
        }
    }

    /** NaN 不许漏进关键帧（否则导出的 JSON 直接非法）。 */
    @Test
    public void sanitizeRemovesNaN() {
        List<PoseSolver.Frame> frames = new ArrayList<PoseSolver.Frame>();
        PoseSolver.Frame a = new PoseSolver.Frame();
        a.tMs = 0;
        a.rotDeg.put("head", new double[]{Double.NaN, 12, Double.POSITIVE_INFINITY});
        PoseSolver.Frame b = new PoseSolver.Frame();
        b.tMs = 33;
        b.rotDeg.put("head", new double[]{4, Double.NaN, 6});
        frames.add(a);
        frames.add(b);
        PoseSolver.sanitize(frames);
        for (PoseSolver.Frame f : frames) {
            for (double d : f.rotDeg.get("head")) {
                assertTrue("仍有非有限值", !Double.isNaN(d) && !Double.isInfinite(d));
            }
        }
        assertEquals(0.0, frames.get(0).rotDeg.get("head")[0], 1e-9);
        assertEquals(12.0, frames.get(1).rotDeg.get("head")[1], 1e-9);
    }
}
