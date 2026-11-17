package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** 跳跃根位移 / 手指细分 / 超高精度导出。 */
public class PoseEnhanceTest {

    /** 造一只"手掌朝下、五指伸直"的手；curl>0 时把近端指节朝前弯。 */
    private static Vec3[] hand(double curlRad) {
        Vec3[] p = new Vec3[21];
        Vec3 wrist = new Vec3(0, 0, 0);
        Vec3 middleMcp = new Vec3(0, -0.10, 0);
        p[0] = wrist;
        p[9] = middleMcp;
        p[5] = new Vec3(0.03, -0.097, 0);
        p[13] = new Vec3(-0.015, -0.097, 0);
        p[17] = new Vec3(-0.03, -0.097, 0);
        p[1] = new Vec3(0.045, -0.085, 0);   // 拇指根（CMC）
        int[][] chain = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
        Vec3[] bases = {p[1], p[5], p[9], p[13], p[17]};
        for (int f = 0; f < chain.length; f++) {
            Vec3 base = bases[f];
            double dirY = -Math.cos(curlRad), dirZ = Math.sin(curlRad);
            p[chain[f][0]] = base;
            p[chain[f][1]] = new Vec3(base.x, base.y + dirY * 0.035, base.z + dirZ * 0.035);
            p[chain[f][2]] = new Vec3(base.x, base.y + dirY * 0.065, base.z + dirZ * 0.065);
            p[chain[f][3]] = new Vec3(base.x, base.y + dirY * 0.09, base.z + dirZ * 0.09);
        }
        return p;
    }

    @Test
    public void straightHandSolvesToRest() {
        HandSolver.Result r = HandSolver.solve(hand(0),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        assertNotNull(r);
        assertEquals(0.0, r.hand[0], 2.0);
        assertEquals(0.0, r.hand[2], 2.0);
        double idx = Math.abs(r.fingers.get("Index")[0]) + Math.abs(r.fingers.get("Index")[2]);
        assertTrue("伸直的手指应接近 0°，实际 " + idx, idx < 3.0);
    }

    @Test
    public void curledFingersGetNonZeroRotation() {
        HandSolver.Result r = HandSolver.solve(hand(Math.toRadians(70)),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        assertNotNull(r);
        double idx = Math.abs(r.fingers.get("Index")[0]) + Math.abs(r.fingers.get("Index")[2]);
        assertTrue("近端指节弯 70°，手指骨必须有明显旋转，实际 " + idx, idx > 20.0);
    }

    private static MocapIO.Clip liftHips(MocapIO.Clip src, double deltaNorm, int from, int to) {
        MocapIO.Clip c = new MocapIO.Clip();
        c.imgW = src.imgW;
        c.imgH = src.imgH;
        c.fps = src.fps;
        for (int i = 0; i < src.frames.size(); i++) {
            Pose33 p = src.frames.get(i);
            Pose33 q = new Pose33();
            q.tMs = p.tMs;
            System.arraycopy(p.x, 0, q.x, 0, 33);
            System.arraycopy(p.y, 0, q.y, 0, 33);
            System.arraycopy(p.z, 0, q.z, 0, 33);
            System.arraycopy(p.vis, 0, q.vis, 0, 33);
            if (i >= from && i <= to) {
                for (int j = 0; j < 33; j++) q.y[j] -= deltaNorm;
            }
            c.frames.add(q);
        }
        return c;
    }

    @Test
    public void jumpLiftsBodyPosition() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        int n = r.clip.frames.size();
        MocapIO.Clip clip = liftHips(r.clip, 0.18, n / 3, 2 * n / 3);
        Main.Options opt = new Main.Options();
        opt.rootMotion = true;
        File out = File.createTempFile("jump", ".animation.json");
        out.deleteOnExit();
        Main.PipelineOut po = Main.pipeline(clip, RigDefaults.vanilla(), "jump", true,
                out.getAbsolutePath(), null, 0.5, opt);
        assertNotNull("必须写出 body.position", po.positions);
        List<KeyframeDecimator.Sample> body = po.positions.get("body");
        assertNotNull(body);
        double max = 0;
        for (KeyframeDecimator.Sample s : body) max = Math.max(max, s.e[1]);
        assertTrue("离地量应明显为正，实际 " + max, max > 1.0);
        assertTrue("必须有跳的写入", AnimCheck.check(out.getAbsolutePath(), "jump").ok);
    }

    @Test
    public void noJumpMeansNoPositionChannel() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        Main.Options opt = new Main.Options();
        opt.rootMotion = true;
        File out = File.createTempFile("nojump", ".animation.json");
        out.deleteOnExit();
        Main.PipelineOut po = Main.pipeline(r.clip, RigDefaults.vanilla(), "walk", true,
                out.getAbsolutePath(), null, 0.5, opt);
        if (po.positions != null) {
            double max = 0;
            for (KeyframeDecimator.Sample s : po.positions.get("body")) {
                max = Math.max(max, Math.abs(s.e[1]));
            }
            assertTrue("走路的小起伏不该变成可见离地，实际 " + max + " px", max < 1.0);
        }
    }

    @Test
    public void ultraPrecisionKeepsMoreKeysAndThreeDecimals() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        File normal = File.createTempFile("norm", ".animation.json");
        File ultra = File.createTempFile("ultra", ".animation.json");
        normal.deleteOnExit();
        ultra.deleteOnExit();
        Main.PipelineOut a = Main.pipeline(r.clip, RigDefaults.vanilla(), "w", true,
                normal.getAbsolutePath(), null, 0.5, new Main.Options());
        Main.Options opt = new Main.Options();
        opt.ultra = true;
        Main.PipelineOut b = Main.pipeline(r.clip, RigDefaults.vanilla(), "w", true,
                ultra.getAbsolutePath(), null, 0.02, opt);
        int ka = 0, kb = 0;
        for (List<KeyframeDecimator.Sample> l : a.keyframes.values()) ka += l.size();
        for (List<KeyframeDecimator.Sample> l : b.keyframes.values()) kb += l.size();
        assertTrue("超高精度关键帧应更多（" + kb + " ≥ " + ka + "）", kb >= ka);
        assertTrue(AnimCheck.check(ultra.getAbsolutePath(), "w").ok);
    }

    @Test
    public void handRigAddsBonesOnlyWhenAsked() {
        assertEquals(6, RigDefaults.vanilla().bones.size());
        assertEquals(18, RigDefaults.vanillaWithHands().bones.size());
    }
}
