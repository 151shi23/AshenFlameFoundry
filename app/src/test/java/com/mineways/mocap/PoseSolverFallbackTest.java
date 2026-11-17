package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 「脚踝没入画 → 生成的 JSON 整个人是散的」这条的断言。
 *
 * <p>旧解算器把「这一帧没看见关节」当成 (0,0,0) —— 那是相机空间原点，也就是画面正中。
 * 髋在画面中点附近、脚踝被放到正中间，髋→踝就变成朝上的向量，于是整段动画腿朝上、
 * 在 Blockbench 里根本看不出是什么动作（实测他交的 walk 片段：左腿 96% 的帧朝上）。
 *
 * <p>现在：远端关节（踝/腕）不可见 → 退到近端（膝/肘）；近端也不可见 → 沿用上一姿态，
 * 从没解出过就保持静止；且**永远不拿 0 点冒充关节**。
 */
public class PoseSolverFallbackTest {

    private static MocapIO.Clip hide(MocapIO.Clip src, int... joints) {
        MocapIO.Clip c = new MocapIO.Clip();
        c.imgW = src.imgW;
        c.imgH = src.imgH;
        c.fps = src.fps;
        for (Pose33 p : src.frames) {
            Pose33 q = new Pose33();
            q.tMs = p.tMs;
            System.arraycopy(p.x, 0, q.x, 0, 33);
            System.arraycopy(p.y, 0, q.y, 0, 33);
            System.arraycopy(p.z, 0, q.z, 0, 33);
            System.arraycopy(p.vis, 0, q.vis, 0, 33);
            for (int i : joints) q.vis[i] = 0;
            c.frames.add(q);
        }
        return c;
    }

    /** 骨方向的 y 分量（EulerBedrock 的 R=Rz·Ry·Rx 约定，rest=(0,-1,0)）。 */
    private static double dirY(double[] e) {
        double[][] R = new double[3][3];
        double[] m = EulerBedrock.toMatrix(e[0], e[1], e[2]);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) R[i][j] = m[i * 3 + j];
        }
        return -R[1][1];
    }

    @Test
    public void invisibleAnklesFallBackToKneesAndKeepLegsDown() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        MocapIO.Clip clip = hide(r.clip, Pose33.L_ANKLE, Pose33.R_ANKLE);
        List<PoseSolver.Frame> frames = new PoseSolver(RigDefaults.vanilla(), clip.imgW, clip.imgH)
                .solveClip(clip.frames);
        assertFalse(frames.isEmpty());
        for (String bone : new String[]{"leftLeg", "rightLeg"}) {
            int down = 0;
            for (PoseSolver.Frame f : frames) {
                if (dirY(f.rotDeg.get(bone)) < -0.5) down++;
            }
            assertTrue(bone + " 应该靠膝保持朝下（实际 " + down + "/" + frames.size() + "）",
                    down > frames.size() * 0.9);
        }
    }

    @Test
    public void fullyInvisibleLegsStayAtRestInsteadOfPointingUp() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        MocapIO.Clip clip = hide(r.clip, Pose33.L_ANKLE, Pose33.R_ANKLE,
                Pose33.L_KNEE, Pose33.R_KNEE);
        PoseSolver solver = new PoseSolver(RigDefaults.vanilla(), clip.imgW, clip.imgH);
        List<PoseSolver.Frame> frames = solver.solveClip(clip.frames);
        for (PoseSolver.Frame f : frames) {
            for (String bone : new String[]{"leftLeg", "rightLeg"}) {
                double[] e = f.rotDeg.get(bone);
                assertNotNull(e);
                assertEquals("整段看不到的骨应保持静止", 0.0, e[0], 1e-9);
                assertEquals(0.0, e[1], 1e-9);
                assertEquals(0.0, e[2], 1e-9);
                assertTrue(bone + " 应被标为 missing", f.missing.contains(bone));
            }
        }
    }

    @Test
    public void qualityLineExplainsMissingJoints() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        File tmp = File.createTempFile("mocap-quality", ".animation.json");
        tmp.deleteOnExit();
        Main.PipelineOut clean = Main.pipeline(r.clip, RigDefaults.vanilla(), "walk", true,
                tmp.getAbsolutePath(), null, 0.5);
        assertNull("数据完整时不该出现警告", Main.qualityLine(clean));

        MocapIO.Clip clip = hide(r.clip, Pose33.L_ANKLE, Pose33.R_ANKLE);
        Main.PipelineOut degraded = Main.pipeline(clip, RigDefaults.vanilla(), "walk", true,
                tmp.getAbsolutePath(), null, 0.5);
        String line = Main.qualityLine(degraded);
        assertNotNull(line);
        assertTrue(line, line.contains("左腿"));
        assertTrue(line, line.contains("近端关节代替"));
        assertTrue(line, String.format(Locale.US, "%s", line).contains("重"));
    }

    // ------------------------------------------------ 置信度分层 + 膝/踝一致性（§8.3.165）

    /** 脚在画面里但 visibility 很低（0.05）：presence 说它在画面里就该用它的坐标。 */
    @Test
    public void presencePromotesLowVisibilityAnkles() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        MocapIO.Clip clip = copy(r.clip);
        for (Pose33 p : clip.frames) {
            p.vis[Pose33.L_ANKLE] = 0.05;
            p.vis[Pose33.R_ANKLE] = 0.05;
            p.pres[Pose33.L_ANKLE] = 0.95;
            p.pres[Pose33.R_ANKLE] = 0.95;
        }
        List<PoseSolver.Frame> frames = new PoseSolver(RigDefaults.vanilla(), clip.imgW, clip.imgH)
                .solveClip(clip.frames);
        for (String bone : new String[]{"leftLeg", "rightLeg"}) {
            int down = 0;
            for (PoseSolver.Frame f : frames) {
                if (dirY(f.rotDeg.get(bone)) < -0.5) down++;
                assertFalse("presence 可用时不该记成 fallback", f.fallback.contains(bone));
            }
            assertTrue(bone, down > frames.size() * 0.9);
        }
    }

    /** 弱可见的踝被模型猜反（指向髋上方）：必须被膝一致性门挡掉，腿仍然朝下。 */
    @Test
    public void garbageWeakAnkleIsRejectedByKneeAgreement() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        MocapIO.Clip clip = copy(r.clip);
        for (Pose33 p : clip.frames) {
            for (int side : new int[]{0, 1}) {
                int hip = side == 0 ? Pose33.L_HIP : Pose33.R_HIP;
                int ank = side == 0 ? Pose33.L_ANKLE : Pose33.R_ANKLE;
                // 关于髋关节做点对称：踝跑到髋上方，方向完全反过来
                p.x[ank] = 2 * p.x[hip] - p.x[ank];
                p.y[ank] = 2 * p.y[hip] - p.y[ank];
                p.z[ank] = 2 * p.z[hip] - p.z[ank];
                p.vis[ank] = 0.2;      // 弱可见（<0.3）
                p.pres[ank] = -1;      // presence 未知 → 只能按弱置信处理
            }
        }
        List<PoseSolver.Frame> frames = new PoseSolver(RigDefaults.vanilla(), clip.imgW, clip.imgH)
                .solveClip(clip.frames);
        for (String bone : new String[]{"leftLeg", "rightLeg"}) {
            int down = 0;
            for (PoseSolver.Frame f : frames) {
                if (dirY(f.rotDeg.get(bone)) < -0.5) down++;
            }
            assertTrue(bone + " 必须退回膝方向（实际朝下 " + down + "/" + frames.size() + "）",
                    down > frames.size() * 0.9);
        }
    }

    /** 数据坏到"腿全程朝上"时必须点名（schema 合规、自检全过也照样要报）。 */
    @Test
    public void orientationLineFlagsLimbsThatNeverPointDown() {
        Main.PipelineOut po = new Main.PipelineOut();
        po.frames = new java.util.ArrayList<PoseSolver.Frame>();
        for (int i = 0; i < 10; i++) {
            PoseSolver.Frame f = new PoseSolver.Frame();
            f.rotDeg.put("leftLeg", new double[]{0, 0, 180});   // Rz(180) 把向下的腿转到朝上
            f.rotDeg.put("rightLeg", new double[]{0, 0, 0});
            po.frames.add(f);
        }
        String line = Main.orientationLine(po);
        assertNotNull(line);
        assertTrue(line, line.contains("左腿"));
        assertTrue(line, line.contains("朝上"));
        assertFalse(line, line.contains("右腿"));
    }

    @Test
    public void orientationLineSilentOnHealthyClip() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        File tmp = File.createTempFile("mocap-orient", ".animation.json");
        tmp.deleteOnExit();
        Main.PipelineOut po = Main.pipeline(r.clip, RigDefaults.vanilla(), "walk", true,
                tmp.getAbsolutePath(), null, 0.5);
        assertNull(Main.orientationLine(po));
    }

    /** 录到一半腿突然不可见：先保持片刻，然后必须平滑回到站立（腿放下），不能一直收着。 */
    @Test
    public void legsRelaxToStandingWhenTheyDisappearMidClip() {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        MocapIO.Clip clip = copy(r.clip);
        for (Pose33 p : clip.frames) {
            if (p.tMs >= 1000) {
                for (int i : new int[]{Pose33.L_ANKLE, Pose33.R_ANKLE,
                        Pose33.L_KNEE, Pose33.R_KNEE}) {
                    p.vis[i] = 0;
                    p.pres[i] = -1;
                }
            }
        }
        List<PoseSolver.Frame> frames = new PoseSolver(RigDefaults.vanilla(), clip.imgW, clip.imgH)
                .solveClip(clip.frames);
        PoseSolver.Frame last = frames.get(frames.size() - 1);
        for (String bone : new String[]{"leftLeg", "rightLeg"}) {
            double[] e = last.rotDeg.get(bone);
            assertEquals(bone + " 应回到站立（x）", 0.0, e[0], 0.05);
            assertEquals(bone + " 应回到站立（y）", 0.0, e[1], 0.05);
            assertEquals(bone + " 应回到站立（z）", 0.0, e[2], 0.05);
            assertTrue("应被标为 missing", last.missing.contains(bone));
        }
    }

    private static MocapIO.Clip copy(MocapIO.Clip src) {        MocapIO.Clip c = new MocapIO.Clip();
        c.imgW = src.imgW;
        c.imgH = src.imgH;
        c.fps = src.fps;
        for (Pose33 p : src.frames) {
            Pose33 q = new Pose33();
            q.tMs = p.tMs;
            System.arraycopy(p.x, 0, q.x, 0, 33);
            System.arraycopy(p.y, 0, q.y, 0, 33);
            System.arraycopy(p.z, 0, q.z, 0, 33);
            System.arraycopy(p.vis, 0, q.vis, 0, 33);
            System.arraycopy(p.pres, 0, q.pres, 0, 33);
            c.frames.add(q);
        }
        return c;
    }
}
