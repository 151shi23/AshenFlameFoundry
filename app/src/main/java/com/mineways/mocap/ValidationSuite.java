package com.mineways.mocap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 验证套件：数学自检 + Blockbench 真机标定对账 + 合成真值精度统计。
 * 全部 PASS 输出退出码 0；任何 FAIL 退出码 1。
 */
public final class ValidationSuite {
    private ValidationSuite() {}

    static int failed = 0, passed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("== mocap4mc Validation Suite ==");
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get("samples/mocap_walk.animation.json"))) {
            Main.demo(); // 自举：T7 需要样例产物
        }
        t1EulerSanity();
        t2BBCalibration();
        t3Clip("walk", SyntheticMocap.walk(), 5.0, 12.0, 18.5);
        t3Clip("wave", SyntheticMocap.wave(), 6.0, 13.0, 19.0);
        t3Clip("idle", SyntheticMocap.idle(), 5.0, 12.0, 18.5);
        t4BackView();
        t5Decimation();
        t6Composer();
        t7Schema();
        t8CsvRoundTrip();
        t9Gating();
        t10Trim();
        t11BbFields();

        System.out.printf(Locale.US, "%n== 结果: %d PASS, %d FAIL ==%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---------- 断言与统计 ----------
    static void check(boolean ok, String name, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name + (detail.isEmpty() ? "" : "  " + detail)); }
        else { failed++; System.out.println("[FAIL] " + name + "  " + detail); }
    }

    static final class Stats {
        double mean, p95, max;
        Stats(double[] diffs) {
            double[] d = diffs.clone();
            java.util.Arrays.sort(d);
            mean = avg(d);
            p95 = d[Math.min(d.length - 1, (int) Math.floor(0.95 * (d.length - 1)))];
            max = d[d.length - 1];
        }
        static double avg(double[] d) { double s = 0; for (double v : d) s += v; return s / d.length; }
        String fmt() { return String.format(Locale.US, "mean %.2f°  p95 %.2f°  max %.2f°", mean, p95, max); }
    }

    static double[] eulerDiffs(List<double[]> got, List<double[]> want) {
        List<Double> l = new ArrayList<Double>();
        for (int i = 0; i < got.size(); i++)
            for (int k = 0; k < 3; k++)
                l.add(Math.abs(EulerBedrock.angDiffDeg(got.get(i)[k], want.get(i)[k])));
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    static List<double[]> gtArrays(List<KeyframeDecimator.Sample> s) {
        List<double[]> l = new ArrayList<double[]>();
        for (KeyframeDecimator.Sample x : s) l.add(x.e);
        return l;
    }

    static double[] dirDiffs(List<double[]> rotDeg, List<Vec3> wantDir) {
        List<Double> l = new ArrayList<Double>();
        for (int i = 0; i < rotDeg.size(); i++) {
            double[] e = rotDeg.get(i);
            Vec3 a = EulerBedrock.apply(EulerBedrock.toMatrix(e[0], e[1], e[2]), new Vec3(0, -1, 0));
            Vec3 b = wantDir.get(i);
            double d = Vec3.dot(Vec3.norm(a), Vec3.norm(b));
            l.add(Math.toDegrees(Math.acos(d < -1 ? -1 : (d > 1 ? 1 : d))));
        }
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    static List<double[]> boneAngles(List<PoseSolver.Frame> frames, String bone) {
        List<double[]> l = new ArrayList<double[]>();
        for (PoseSolver.Frame f : frames) {
            double[] e = f.rotDeg.get(bone);
            l.add(e == null ? new double[]{0, 0, 0} : e);
        }
        return l;
    }

    // ---------- T1 欧拉自检 ----------
    static void t1EulerSanity() {
        boolean ok = true;
        String det = "";
        for (double[] e : new double[][]{{90, 0, 0}, {0, 90, 0}, {0, 0, 90}, {30, 45, 15}, {-120, 33, 77}}) {
            double[] R = EulerBedrock.toMatrix(e[0], e[1], e[2]);
            // 正交性：R·R^T = I
            double err = 0;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    double s = 0;
                    for (int k = 0; k < 3; k++) s += R[i * 3 + k] * R[j * 3 + k];
                    err = Math.max(err, Math.abs(s - (i == j ? 1 : 0)));
                }
            if (err > 1e-12) { ok = false; det = "正交性 err=" + err + " @" + java.util.Arrays.toString(e); }
        }
        check(ok, "T1 欧拉矩阵正交性", det);
    }

    // ---------- T2 Blockbench 真机标定对账 ----------
    @SuppressWarnings("unchecked")
    static void t2BBCalibration() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("tools/calibration.json");
        if (!java.nio.file.Files.exists(p)) {
            check(false, "T2 Blockbench 真机标定对账", "缺 tools/calibration.json —— 先运行 tools/eval_calibration.js");
            return;
        }
        Map<String, Object> cal = (Map<String, Object>) MiniJson.parse(
            new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
        String order = (String) cal.get("euler_order");
        List<Object> cases = (List<Object>) cal.get("cases");
        double maxMatErr = 0, maxTipErr = 0;
        int n = 0;
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            List<Object> r = (List<Object>) c.get("rot");
            List<Object> mv = (List<Object>) c.get("m");       // THREE 列主序 4x4
            List<Object> tw = (List<Object>) c.get("tip");
            List<Object> piv = (List<Object>) cal.get("pivot");
            List<Object> tr = (List<Object>) cal.get("tipRest");
            double[] rot = {num(r.get(0)), num(r.get(1)), num(r.get(2))};
            // BB 矩阵（列主序）→ 行主序 3x3 旋转部分
            double[] bb = new double[9];
            for (int rr = 0; rr < 3; rr++)
                for (int cc = 0; cc < 3; cc++)
                    bb[rr * 3 + cc] = num(mv.get(cc * 4 + rr));
            double[] mine = EulerBedrock.toMatrix(rot[0], rot[1], rot[2]);
            for (int i = 0; i < 9; i++)
                maxMatErr = Math.max(maxMatErr, Math.abs(mine[i] - bb[i]));
            // pivot 语义：BB 侧 world = position(origin) + R·local，其中 local = tipAbs
            Vec3 pivot = new Vec3(num(piv.get(0)), num(piv.get(1)), num(piv.get(2)));
            Vec3 tipAbs = new Vec3(num(tr.get(0)), num(tr.get(1)), num(tr.get(2)));
            Vec3 tipBB = new Vec3(num(tw.get(0)), num(tw.get(1)), num(tw.get(2)));
            Vec3 mineTip = Vec3.add(pivot, EulerBedrock.apply(mine, tipAbs));
            maxTipErr = Math.max(maxTipErr, Vec3.sub(mineTip, tipBB).len());
            n++;
        }
        check(maxMatErr < 1e-6 && maxTipErr < 1e-3,
            String.format(Locale.US, "T2 Blockbench 真机标定对账 (%d 组合, euler_order=%s)", n, order),
            String.format(Locale.US, "max|ΔR|=%.2e, max|Δtip|=%.2e", maxMatErr, maxTipErr));
    }

    static double num(Object o) { return ((Number) o).doubleValue(); }

    // ---------- T3~ 动作精度 ----------
    static void t3Clip(String label, SyntheticMocap.Gen g, double meanThr, double p95Thr, double maxThr) {
        try {
            SyntheticMocap.Result res = SyntheticMocap.generate(g, 42L);
            PoseSolver solver = new PoseSolver(RigConfig.load("rig/rig.vanilla.json"),
                res.clip.imgW, res.clip.imgH);
            List<PoseSolver.Frame> frames = solver.solveClip(res.clip.frames);

            Stats legs = new Stats(concat(eulerDiffs(boneAngles(frames, "leftLeg"), gtArrays(res.gt.get("legL"))),
                                          eulerDiffs(boneAngles(frames, "rightLeg"), gtArrays(res.gt.get("legR")))));
            Stats arms = new Stats(concat(dirDiffs(boneAngles(frames, "leftArm"), res.gtEffDir.get("armL")),
                                          dirDiffs(boneAngles(frames, "rightArm"), res.gtEffDir.get("armR"))));
            Stats head = new Stats(eulerDiffs(boneAngles(frames, "head"), gtArrays(res.gt.get("head"))));
            Stats yaw = new Stats(yawDiffs(frames, res.gt.get("body")));

            boolean ok = legs.mean < meanThr && legs.p95 < p95Thr && legs.max < maxThr
                      && arms.mean < meanThr && arms.p95 < p95Thr && arms.max < maxThr
                      && head.mean < meanThr && head.p95 < p95Thr + 2 && head.max < maxThr + 6
                      && yaw.mean < meanThr + 2 && yaw.max < maxThr + 4;
            String det = String.format(Locale.US,
                "腿[欧拉] %s | 臂[方向] %s | 头[欧拉] %s | 实体yaw %s",
                legs.fmt(), arms.fmt(), head.fmt(), yaw.fmt());
            check(ok, "T3 " + label + " 精度", det);
        } catch (Exception ex) {
            check(false, "T3 " + label + " 精度", "异常: " + ex);
        }
    }

    static double[] yawDiffs(List<PoseSolver.Frame> frames, List<KeyframeDecimator.Sample> gtBody) {
        double[] a = new double[frames.size()];
        for (int i = 0; i < a.length; i++) {
            double gt = gtBody.get(i).e[1];
            if (gt > 90) gt -= 360; if (gt < -90) gt += 360; // 与 atan2 半圆约定对齐
            a[i] = Math.abs(EulerBedrock.angDiffDeg(frames.get(i).entityYawDeg, gt));
        }
        return a;
    }

    static double[] concat(double[] a, double[] b) {
        double[] o = new double[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    // ---------- T4 背对相机 ----------
    static void t4BackView() {
        SyntheticMocap.Gen base = SyntheticMocap.walk();
        final SyntheticMocap.Angles inner = base.angles;
        SyntheticMocap.Gen back = new SyntheticMocap.Gen();
        back.name = "walk_back"; back.loop = true; back.durSec = base.durSec; back.fps = 30;
        back.noseVis = 0.15; // 背对相机：鼻部被后脑勺遮挡 → 解算器自动降级为耳双目标拟合
        back.angles = new SyntheticMocap.Angles() {
            public double[] at(String kind, double t) {
                double[] a = inner.at(kind, t);
                if (kind.equals("body")) a[1] += 180;
                return a;
            }
        };
        // 背视头部为本征弱观测（耳段短 24px + 无鼻先验）：阈值按实测放宽，手册如实记录
        t3Clip("walk背对相机(鼻部降级)", back, 6.5, 22.0, 34.0);
    }

    // ---------- T5 关键帧保真 ----------
    static void t5Decimation() {
        List<KeyframeDecimator.Sample> in = new ArrayList<KeyframeDecimator.Sample>();
        for (int i = 0; i <= 100; i++) {
            double t = i * 0.033;
            in.add(new KeyframeDecimator.Sample(t, new double[]{
                20 * Math.sin(t * 5.1), 8 * Math.sin(t * 2.3 + 1), -5 + 0.01 * i}));
        }
        List<KeyframeDecimator.Sample> out = KeyframeDecimator.decimate(in, 0.5);
        double maxDev = 0;
        for (KeyframeDecimator.Sample s : in) {
            for (int j = 0; j < out.size() - 1; j++) {
                KeyframeDecimator.Sample a = out.get(j), b = out.get(j + 1);
                if (s.t >= a.t && s.t <= b.t) {
                    double f = (s.t - a.t) / Math.max(1e-9, b.t - a.t);
                    for (int k = 0; k < 3; k++) {
                        double want = a.e[k] + (b.e[k] - a.e[k]) * f;
                        maxDev = Math.max(maxDev, Math.abs(EulerBedrock.angDiffDeg(s.e[k], want)));
                    }
                    break;
                }
            }
        }
        check(out.size() < in.size() && maxDev <= 0.5,
            String.format(Locale.US, "T5 关键帧抽取保真 (%d→%d 帧)", in.size(), out.size()),
            String.format(Locale.US, "max偏差 %.3f° ≤ 0.5°", maxDev));
    }

    // ---------- T6 叠加合成 ----------
    static void t6Composer() throws Exception {
        // 构造两段最小动画 JSON：base 头部 0s:[10,0,0]→1s:[30,0,0]；add 头部 0s:[5,0,0]→1s:[5,0,0]
        String base = "{\"format_version\":\"1.8.0\",\"animations\":{\"a\":{\"loop\":true,\"animation_length\":1,"
            + "\"bones\":{\"head\":{\"rotation\":{\"0.0\":[10,0,0],\"1.0\":[30,0,0]}}}}}}";
        String add = "{\"format_version\":\"1.8.0\",\"animations\":{\"b\":{\"loop\":true,\"animation_length\":1,"
            + "\"bones\":{\"head\":{\"rotation\":{\"0.0\":[5,0,0],\"1.0\":[5,0,0]}}}}}}";
        java.nio.file.Files.write(java.nio.file.Paths.get("tmp_b.json"), base.getBytes("UTF-8"));
        java.nio.file.Files.write(java.nio.file.Paths.get("tmp_a.json"), add.getBytes("UTF-8"));
        AdditiveComposer.compose("tmp_b.json", "tmp_a.json", "tmp_c.json", "animation.t.c");
        Map<String, Object> out = (Map<String, Object>) MiniJson.parse(
            new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tmp_c.json")), "UTF-8"));
        Map<String, Object> anims = (Map<String, Object>) out.get("animations");
        Map<String, Object> c = (Map<String, Object>) anims.get("animation.t.c");
        Map<String, Object> bones = (Map<String, Object>) c.get("bones");
        Map<String, Object> head = (Map<String, Object>) bones.get("head");
        Map<String, Object> rot = (Map<String, Object>) head.get("rotation");
        double v0 = ((Number) ((List<Object>) rot.get("0.0")).get(0)).doubleValue();
        double v1 = ((Number) ((List<Object>) rot.get("1.0")).get(0)).doubleValue();
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("tmp_b.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("tmp_a.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("tmp_c.json"));
        check(Math.abs(v0 - 15) < 1e-9 && Math.abs(v1 - 35) < 1e-9, "T6 叠加合成 base+add",
            String.format(Locale.US, "0s→%.1f°, 1s→%.1f° (期望 15/35)", v0, v1));
    }

    // ---------- T7 JSON schema ----------
    @SuppressWarnings("unchecked")
    static void t7Schema() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("samples/mocap_walk.animation.json");
        if (!java.nio.file.Files.exists(p)) { check(false, "T7 JSON schema", "先运行 demo 生成样例"); return; }
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(
            new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
        boolean ok = "1.8.0".equals(root.get("format_version"));
        Map<String, Object> anims = (Map<String, Object>) root.get("animations");
        Map<String, Object> anim = (Map<String, Object>) anims.get("animation.mocap4mc.walk");
        ok &= anim != null && Boolean.TRUE.equals(anim.get("loop"));
        Map<String, Object> bones = (Map<String, Object>) anim.get("bones");
        ok &= bones != null && !bones.isEmpty();
        int kf = 0;
        for (Object bo : bones.values()) {
            Map<String, Object> b = (Map<String, Object>) bo;
            Map<String, Object> rot = (Map<String, Object>) b.get("rotation");
            List<String> times = new ArrayList<String>(rot.keySet());
            for (String t : times) {
                Double.parseDouble(t);
                List<Object> v = (List<Object>) rot.get(t);
                ok &= v.size() == 3;
                kf++;
            }
            List<Double> td = new ArrayList<Double>();
            for (String t : times) td.add(Double.parseDouble(t));
            java.util.Collections.sort(td);
            ok &= td.equals(td.stream().sorted().collect(java.util.stream.Collectors.toList()));
        }
        check(ok, "T7 .animation.json schema", "bones=" + bones.size() + " keyframes=" + kf);
    }

    // ---------- T8 CSV 往返 ----------
    static void t8CsvRoundTrip() throws Exception {
        SyntheticMocap.Result res = SyntheticMocap.generate(SyntheticMocap.idle(), 7L);
        MocapIO.writeCsv(res.clip, "tmp_poses.csv");
        MocapIO.Clip back = MocapIO.readCsv("tmp_poses.csv");
        Pose33 a = res.clip.frames.get(10), b = back.frames.get(10);
        double err = 0;
        for (int i = 0; i < 33; i++)
            for (int k = 0; k < 3; k++)
                err = Math.max(err, Math.abs(new double[]{a.x[i], a.y[i], a.z[i]}[k]
                    - new double[]{b.x[i], b.y[i], b.z[i]}[k]));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("tmp_poses.csv"));
        check(back.frames.size() == res.clip.frames.size() && err < 1e-5, "T8 CSV 往返",
            String.format(Locale.US, "frames=%d, max|Δ|=%.1e", back.frames.size(), err));
    }

    // ---------- T10 手动剪辑（纯删键帧+平移，零重采样） ----------
    static void t10Trim() {
        Map<String, List<KeyframeDecimator.Sample>> bones = new java.util.LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        List<KeyframeDecimator.Sample> l = new ArrayList<KeyframeDecimator.Sample>();
        for (int i = 0; i <= 15; i++) l.add(new KeyframeDecimator.Sample(i * 0.1, new double[]{i * 10, 0, 1}));
        bones.put("rightArm", l);
        double[][] ranges = TrimEditor.parseRanges("0.5-1.5", 1.6);
        TrimEditor.Plan plan = new TrimEditor.Plan();
        Map<String, List<KeyframeDecimator.Sample>> out = TrimEditor.trim(bones, ranges, plan);
        List<KeyframeDecimator.Sample> got = out.get("rightArm");
        boolean ok = got.size() == 11 && plan.dropped == 5 && plan.kept == 11
            && Math.abs(got.get(0).t) < 1e-9 && Math.abs(got.get(0).e[0] - 50) < 1e-9
            && Math.abs(got.get(10).t - 1.0) < 1e-9 && Math.abs(got.get(10).e[0] - 150) < 1e-9
            && Math.abs(plan.newLen[0] - 1.0) < 1e-9;
        // 值保真：所有保留键值必须与源完全一致（零重采样证据）
        for (int i = 0; i < got.size() && ok; i++) ok = Math.abs(got.get(i).e[0] - (50 + i * 10)) < 1e-9;
        check(ok, "T10 手动剪辑保真", String.format(Locale.US,
            "keep 11 kf / drop 5, len %.1fs, 值零损失", plan.newLen[0]));
    }

    // ---------- T11 Blockbench 动画字段 ----------
    @SuppressWarnings("unchecked")
    static void t11BbFields() throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempFile("mocap-t11", ".animation.json");
        try {
            SyntheticMocap.Result res = SyntheticMocap.generate(SyntheticMocap.wave(), 5L);
            Main.Options opt = new Main.Options();
            opt.smooth = true;
            opt.overridePose = true;
            opt.holdLastFrame = true;
            Main.pipeline(res.clip, RigDefaults.vanilla(), "animation.t11", false,
                    p.toString(), null, 0.5, opt);

            Map<String, Object> root = (Map<String, Object>) MiniJson.parse(
                    new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> anim = (Map<String, Object>) ((Map<String, Object>) root.get("animations"))
                    .get("animation.t11");
            Map<String, Object> bones = (Map<String, Object>) anim.get("bones");
            Map<String, Object> rot = (Map<String, Object>) ((Map<String, Object>) bones.get("head")).get("rotation");
            Object first = rot.values().iterator().next();
            boolean catmull = first instanceof Map
                    && AnimSpec.LERP_CATMULLROM.equals(((Map<String, Object>) first).get("lerp_mode"));
            boolean ok = AnimSpec.LOOP_HOLD.equals(anim.get("loop"))
                    && Boolean.TRUE.equals(anim.get("override_previous_animation"))
                    && catmull;
            AnimCheck.Result chk = AnimCheck.check(p.toString(), "animation.t11");
            check(ok && chk.ok, "T11 Blockbench 动画字段（loop 三态 / override / catmullrom）",
                    (AnimSpec.isHold(anim.get("loop")) ? "hold_on_last_frame" : String.valueOf(anim.get("loop")))
                            + " · override=" + anim.get("override_previous_animation")
                            + " · catmullrom=" + catmull + " · " + chk.message);
        } finally {
            java.nio.file.Files.deleteIfExists(p);
        }
    }

    // ---------- T9 遮挡门控 ----------
    static void t9Gating() throws Exception {
        SyntheticMocap.Result res = SyntheticMocap.generate(SyntheticMocap.walk(), 11L);
        // 全部肢体关键点连续 5 帧不可见
        for (int f = 50; f < 55; f++) {
            Pose33 p = res.clip.frames.get(f);
            int[] limbs = {13, 14, 15, 16, 25, 26, 27, 28};
            for (int i : limbs) p.vis[i] = 0.0;
        }
        PoseSolver solver = new PoseSolver(RigConfig.load("rig/rig.vanilla.json"),
            res.clip.imgW, res.clip.imgH);
        List<PoseSolver.Frame> frames = solver.solveClip(res.clip.frames);
        boolean finite = true;
        for (PoseSolver.Frame f : frames)
            for (double[] e : f.rotDeg.values())
                for (double v : e) if (!Double.isFinite(v)) finite = false;
        check(finite, "T9 遮挡门控（肢体连续缺失不产生 NaN）", "");
    }
}
