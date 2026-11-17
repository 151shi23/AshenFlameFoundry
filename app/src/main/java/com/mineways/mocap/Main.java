package com.mineways.mocap;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * mocap4mc CLI
 *   test     — 运行验证套件（含 BB 真机标定对账与合成真值精度统计）
 *   demo     — 生成三段合成动捕样例 → 全管线 → samples/ 下动画/报告/模板几何
 *   convert  — 真实动捕 CSV → 动画 JSON（接 Android PoseCaptureBridge 输出）
 *   compose  — 两份动画离线叠加烘焙
 */
public final class Main {

    /**
     * 生成选项：跳跃根位移 / 手指细分 / 超高精度（逐帧 + 3 位小数）。
     *
     * <p>后三项对应 Blockbench 的动画字段（见 {@link AnimSpec}）：
     * <ul>
     *   <li>{@link #smooth}：关键帧写成 Catmull-Rom（{@code lerp_mode:"catmullrom"}），
     *       BB 时间轴里显示为「平滑」，播放不再是一段段折线；</li>
     *   <li>{@link #overridePose}：写 {@code override_previous_animation:true}，
     *       播放前把骨骼重置到模型默认姿势（动捕是绝对姿态，必须这么做）；</li>
     *   <li>{@link #holdLastFrame}：不循环时写 {@code loop:"hold_on_last_frame"}，
     *       播完停在末帧，而不是弹回静止姿势。</li>
     * </ul>
     */
    public static final class Options {
        public boolean rootMotion;
        public boolean fingers;
        public boolean ultra;
        public boolean smooth;
        public boolean overridePose;
        public boolean holdLastFrame;
    }

    // ---------- 全管线：CSV → 解算 → 抽帧 → 动画 JSON + 快照 ----------
    static PipelineOut pipeline(MocapIO.Clip clip, RigConfig rig, String animName, boolean loop,
                                String outJson, String outSnap, double tolDeg) throws Exception {
        return pipeline(clip, rig, animName, loop, outJson, outSnap, tolDeg, new Options());
    }

    static PipelineOut pipeline(MocapIO.Clip clip, RigConfig rig, String animName, boolean loop,
                                String outJson, String outSnap, double tolDeg, Options opt)
            throws Exception {
        if (opt == null) opt = new Options();
        PoseSolver solver = new PoseSolver(rig, clip.imgW, clip.imgH);
        List<PoseSolver.Frame> frames = solver.solveClip(clip.frames);
        double dur = clip.frames.isEmpty() ? 0
            : clip.frames.get(clip.frames.size() - 1).tMs / 1000.0;
        int handFrames = 0, leftHands = 0, rightHands = 0;
        if (opt.fingers) {
            for (int i = 0; i < frames.size(); i++) {
                Pose33 p = clip.frames.get(i);
                PoseSolver.Frame f = frames.get(i);
                boolean any = false;
                for (int h = 0; h < 2; h++) {
                    if (!p.handSeen[h]) continue;
                    HandSolver.Result hr = solver.solveHand(p, f, h == 0);
                    if (hr == null) continue;
                    any = true;
                    if (h == 0) leftHands++;
                    else rightHands++;
                    f.rotDeg.put((h == 0 ? "left" : "right") + "Hand", hr.hand);
                    for (Map.Entry<String, double[]> e : hr.fingers.entrySet()) {
                        f.rotDeg.put(HandSolver.boneName(h == 0, e.getKey()), e.getValue());
                    }
                }
                if (any) handFrames++;
            }
        }

        Map<String, List<KeyframeDecimator.Sample>> curves = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        Map<String, List<KeyframeDecimator.Sample>> bonesJson = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        for (RigConfig.Bone b : rig.bones) {
            if (!b.animated) continue;
            boolean handBone = b.kind != null
                    && (b.kind.startsWith("hand") || b.kind.startsWith("finger"));
            if (handBone && (!opt.fingers || handFrames == 0)) continue;
            boolean anyData = false;
            for (PoseSolver.Frame f : frames) {
                if (f.rotDeg.get(b.name) != null) {
                    anyData = true;
                    break;
                }
            }
            if (!anyData) continue;
            List<KeyframeDecimator.Sample> s = new ArrayList<KeyframeDecimator.Sample>();
            for (PoseSolver.Frame f : frames) {
                double[] e = f.rotDeg.get(b.name);
                s.add(new KeyframeDecimator.Sample(f.tMs / 1000.0,
                        e == null ? new double[]{0, 0, 0} : e));
            }
            curves.put(b.name, s);
            List<KeyframeDecimator.Sample> dec = KeyframeDecimator.decimate(s, tolDeg);
            bonesJson.put(b.name, dec);
        }
        Map<String, List<KeyframeDecimator.Sample>> positions = opt.rootMotion
                ? rootMotion(frames, dur) : null;
        AnimationWriter.Settings settings = new AnimationWriter.Settings();
        settings.loop = loop;
        settings.holdLastFrame = opt.holdLastFrame;
        settings.overridePreviousAnimation = opt.overridePose;
        settings.smooth = opt.smooth;
        settings.decimals = opt.ultra ? 3 : 2;
        settings.rotations = bonesJson;
        settings.positions = positions;
        AnimationWriter.write(outJson, animName, settings, dur);
        if (outSnap != null) {
            Map<String, double[]> pivots = new LinkedHashMap<String, double[]>();
            for (RigConfig.Bone b : rig.bones) pivots.put(b.name, b.pivot);
            AnimationWriter.writeSnapshots(outSnap, rig.rigName, pivots, frames, 6);
        }
        PipelineOut po = new PipelineOut();
        po.frames = frames;
        po.keyframes = bonesJson;
        po.rawFrames = curves;
        po.positions = positions;
        po.durSec = dur;
        po.frameCount = frames.size();
        po.handFrames = handFrames;
        po.leftHands = leftHands;
        po.rightHands = rightHands;
        for (RigConfig.Bone b : rig.bones) {
            if (!b.animated) continue;
            if (frames.isEmpty() || frames.get(0).rotDeg.get(b.name) == null) continue;
            int viaFallback = 0, missing = 0;
            for (PoseSolver.Frame f : frames) {
                if (f.fallback.contains(b.name)) viaFallback++;
                if (f.missing.contains(b.name)) missing++;
            }
            po.quality.put(b.name, new int[]{viaFallback, missing});
        }
        return po;
    }

    /**
     * 跳跃根位移：以髋中点高度（躯干长度归一化）相对整段中位数的偏移作为"离地量"，
     * 换算成模型像素（原版人模髋→肩 12 px = 1 躯干长度），写到 {@code body.position.y}。
     *
     * <p>只在有明显竖向位移（>4% 躯干）时才写得动，站立/走路的 ±2cm 起伏会被死区吃掉；
     * 曲线按 0.05 px 抽帧。
     */
    private static Map<String, List<KeyframeDecimator.Sample>> rootMotion(
            List<PoseSolver.Frame> frames, double dur) {
        List<Double> ys = new ArrayList<Double>();
        for (PoseSolver.Frame f : frames) {
            if (!Double.isNaN(f.hipYNorm)) ys.add(f.hipYNorm);
        }
        if (ys.size() < 3) return null;
        java.util.Collections.sort(ys);
        double base = ys.get(ys.size() / 2);
        List<KeyframeDecimator.Sample> out = new ArrayList<KeyframeDecimator.Sample>();
        boolean moved = false;
        for (PoseSolver.Frame f : frames) {
            double dy = Double.isNaN(f.hipYNorm) ? 0 : f.hipYNorm - base;
            if (Math.abs(dy) < 0.04) dy = 0;
            double px = dy * 12.0;
            if (Math.abs(px) > 0.05) moved = true;
            out.add(new KeyframeDecimator.Sample(f.tMs / 1000.0, new double[]{0, px, 0}));
        }
        if (!moved) return null;
        List<KeyframeDecimator.Sample> dec = KeyframeDecimator.decimate(out, 0.05);
        Map<String, List<KeyframeDecimator.Sample>> m =
                new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        m.put("body", dec);
        return m;
    }

    public static final class PipelineOut {
        public List<PoseSolver.Frame> frames;
        public Map<String, List<KeyframeDecimator.Sample>> keyframes;
        public Map<String, List<KeyframeDecimator.Sample>> rawFrames;
        /** 位移通道（body 的 y）：没有跳跃时为 null。 */
        public Map<String, List<KeyframeDecimator.Sample>> positions;
        public double durSec;
        public int frameCount;
        /** 手上解算：有手的帧数 / 左右手各多少帧。 */
        public int handFrames, leftHands, rightHands;
        /** 每根骨 {近端关节代替帧数, 完全不可见帧数}：给界面说清"这段数据缺了什么"。 */
        public final Map<String, int[]> quality = new LinkedHashMap<String, int[]>();
        int kfCount() { int n = 0; for (List<KeyframeDecimator.Sample> l : keyframes.values()) n += l.size(); return n; }
        int rawCount() { int n = 0; for (List<KeyframeDecimator.Sample> l : rawFrames.values()) n += l.size(); return n; }
    }

    /**
     * 「这段录制的关节可见性」一句话诊断：哪根骨是拿近端关节代替的、哪根骨整段都没看到。
     *
     * <p>这一行是给用户看的，不是给日志看的：脚踝/手腕没入画时，旧算法会把它们放到画面正中，
     * 于是腿朝上、整个人像被拆开；新算法会退到膝/肘，退不了就保持上一姿态 —— 但**取景还是错的**，
     * 所以必须把"缺了什么"直接写在结果里，让他去重拍，而不是只给一个看起来正常的小人。
     */
    public static String qualityLine(PipelineOut po) {
        if (po == null || po.frameCount == 0) return null;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : po.quality.entrySet()) {
            int viaFallback = e.getValue()[0], missing = e.getValue()[1];
            if (viaFallback == 0 && missing == 0) continue;
            if (b.length() > 0) b.append("；");
            b.append(label(e.getKey())).append("：");
            if (viaFallback > 0) {
                b.append("head".equals(e.getKey()) ? "双耳不可见，用肩轴代替 "
                        : "近端关节代替 ").append(pct(viaFallback, po.frameCount));
            }
            if (missing > 0) {
                if (viaFallback > 0) b.append("、");
                b.append("整段看不到 ").append(pct(missing, po.frameCount))
                        .append("（保持上一姿态/静止）");
            }
        }
        if (b.length() == 0) return null;
        return "⚠ 关节可见性：" + b + "。脚踝/手腕没入画时请重新取景（全身拍全、脚别出框），"
                + "否则这几根骨的动作是猜的。";
    }

    /**
     * 方向体检：某根肢体**整段朝上**（朝上帧 >80%）几乎只可能是数据坏了，而不是人在做动作。
     *
     * <p>本轮的真凶就是这种：脚踝被当成画面正中，髋→踝方向变成朝上，腿全程翘着。
     * 这类文件 schema 完全合规、自检全过，但人一看就是散的 —— 所以必须在结果里点名。
     */
    public static String orientationLine(PipelineOut po) {
        if (po == null || po.frames == null || po.frames.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        int n = po.frames.size();
        for (String bone : new String[]{"leftLeg", "rightLeg", "leftArm", "rightArm"}) {
            int up = 0;
            for (PoseSolver.Frame f : po.frames) {
                double[] e = f.rotDeg.get(bone);
                if (e != null && dirY(e) > 0.5) up++;
            }
            if (up > n * 0.8) {
                if (b.length() > 0) b.append("、");
                b.append(label(bone)).append(" ").append(pct(up, n)).append(" 的帧朝上");
            }
        }
        if (b.length() == 0) return null;
        return "⚠ 动作方向异常：" + b + "（人不可能一直保持这个姿势）——"
                + "多半是脚踝/手腕关键点被模型猜偏。请重录这段动作；重录后仍有这行就说明取景或光照不合格。";
    }

    /** 欧拉（度，R=Rz·Ry·Rx）作用在骨骼静止方向 (0,-1,0) 后的 y 分量（+上 / -下）。 */
    private static double dirY(double[] e) {
        return -EulerBedrock.toMatrix(e[0], e[1], e[2])[4];
    }

    private static String label(String bone) {
        if ("leftArm".equals(bone)) return "左臂";
        if ("rightArm".equals(bone)) return "右臂";
        if ("leftLeg".equals(bone)) return "左腿";
        if ("rightLeg".equals(bone)) return "右腿";
        if ("head".equals(bone)) return "头";
        return bone;
    }

    private static String pct(int n, int total) {
        return Math.round(100.0 * n / Math.max(1, total)) + "%";
    }

    // ---------- demo ----------
    static void demo() throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("samples/preview"));
        RigConfig rig = RigConfig.load("rig/rig.vanilla.json");
        String[][] clips = {
            {"walk", "samples/poses_walk.csv", "samples/mocap_walk.animation.json",
             "samples/preview/snapshots_walk.json", "true"},
            {"wave", "samples/poses_wave.csv", "samples/mocap_wave.animation.json",
             "samples/preview/snapshots_wave.json", "false"},
            {"idle", "samples/poses_idle.csv", "samples/mocap_idle.animation.json",
             "samples/preview/snapshots_idle.json", "true"},
        };
        StringBuilder rep = new StringBuilder();
        rep.append("# mocap4mc 管线运行报告\n\n生成时间见对话；解算口径与 docs/评估与技术手册.md 一致。\n\n");
        rep.append("| 片段 | 帧数 | 原始采样 | 关键帧(压缩后) | 压缩率 | 输出 |\n|---|---|---|---|---|---|\n");
        for (String[] c : clips) {
            SyntheticMocap.Result res = SyntheticMocap.generate(genOf(c[0]), 42L);
            MocapIO.writeCsv(res.clip, c[1]);
            PipelineOut po = pipeline(res.clip, rig, "animation.mocap4mc." + c[0],
                Boolean.parseBoolean(c[4]), c[2], c[3], 0.5);
            rep.append(String.format(Locale.US, "| %s | %d | %d | %d | %.1f%% | `%s` |%n",
                c[0], po.frames.size(), po.rawCount(), po.kfCount(),
                100.0 - 100.0 * po.kfCount() / Math.max(1, po.rawCount()), c[2]));
        }

        // 叠加合成演示：idle 基础层 + breathe 叠加层
        SyntheticMocap.Result br = SyntheticMocap.generate(SyntheticMocap.breathe(), 99L);
        MocapIO.writeCsv(br.clip, "samples/poses_breathe_additive.csv");
        pipeline(br.clip, rig, "animation.mocap4mc.breathe_additive", true,
            "samples/mocap_breathe_additive.animation.json", null, 0.5);
        AdditiveComposer.compose("samples/mocap_idle.animation.json",
            "samples/mocap_breathe_additive.animation.json",
            "samples/mocap_idle_composed.animation.json", "animation.mocap4mc.idle_composed");
        rep.append("\n## 可叠加演示\n\n- 基础层：`samples/mocap_idle.animation.json`\n");
        rep.append("- 叠加层：`samples/mocap_breathe_additive.animation.json`（呼吸微动）\n");
        rep.append("- 离线烘焙合成：`samples/mocap_idle_composed.animation.json`\n");
        rep.append("- 运行时原生分层：两份独立文件同骨骼名，Bedrock controller / GeckoLib additive 直接叠放。\n");

        writeText("samples/pipeline_report.md", rep.toString());
        System.out.println("demo 完成：samples/ 与 samples/preview/ 已生成，报告见 samples/pipeline_report.md");
    }

    static SyntheticMocap.Gen genOf(String name) {
        if ("walk".equals(name)) return SyntheticMocap.walk();
        if ("wave".equals(name)) return SyntheticMocap.wave();
        if ("idle".equals(name)) return SyntheticMocap.idle();
        if ("walk_prep".equals(name)) return SyntheticMocap.walkPrep();
        return SyntheticMocap.breathe();
    }

    /** 从动画 JSON 提取 per-bone 键帧（剪辑 CLI 用）。 */
    @SuppressWarnings("unchecked")
    static Map<String, List<KeyframeDecimator.Sample>> extractBones(Map<String, Object> anim) throws Exception {
        Map<String, List<KeyframeDecimator.Sample>> out = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        Map<String, Object> bones = (Map<String, Object>) anim.get("bones");
        for (Map.Entry<String, Object> e : bones.entrySet()) {
            Map<String, Object> b = (Map<String, Object>) e.getValue();
            Map<String, Object> rot = (Map<String, Object>) b.get("rotation");
            List<KeyframeDecimator.Sample> l = new ArrayList<KeyframeDecimator.Sample>();
            for (Map.Entry<String, Object> k : rot.entrySet()) {
                List<Object> v = (List<Object>) k.getValue();
                l.add(new KeyframeDecimator.Sample(Double.parseDouble(k.getKey()),
                    new double[]{((Number) v.get(0)).doubleValue(),
                                 ((Number) v.get(1)).doubleValue(),
                                 ((Number) v.get(2)).doubleValue()}));
            }
            l.sort((a, b2) -> Double.compare(a.t, b2.t));
            out.put(e.getKey(), l);
        }
        return out;
    }

    /**
     * product —— 用户要求的完整交付流程演示：
     * 合成动捕（带多余静止段）→ 解算（实时标注帧 + 性能基准）→ 动捕分析报告
     * → 手动剪辑（删多余段）→ 输出合规 JSON 成品 + 标注/姿态静帧（亲眼验证）。
     */

    static double[] round1(double[] v) {
        return new double[]{Math.round(v[0] * 10.0) / 10.0, Math.round(v[1] * 10.0) / 10.0,
                            Math.round(v[2] * 10.0) / 10.0};
    }

    /**
     * gallery —— 姿势对比图库：
     * 行走全周期 12 相位 / 多余静止段 3 帧 / 举手挥摆 6 帧 / 静息呼吸 5 帧 /
     * 背对相机 3 帧（视角不变性）。每张：左=关节标注，右=人模姿态+真值读数。
     */

    /**
     * video —— 连续动作对比视频帧序列：
     * 挥手 1.8s + 行走（剪辑后）3.3s @30fps = 153 帧；
     * 每帧：左=实时关节标注（[REC]），右=MC 人模解算姿态 + 真值线框 ghost（贴合度对比）。
     * 渲染完用 ffmpeg 合成（见 run.sh video 分支）。
     */

    /** 单帧合成：左=标注相机视图，右=解算姿态+GT ghost。 */

    static void putGt(Map<String, double[]> ghost, SyntheticMocap.Result res, int i,
                      String kind, String bone) {
        List<KeyframeDecimator.Sample> g = res.gt.get(kind);
        if (g != null && i < g.size()) ghost.put(bone, g.get(i).e);
    }

    // ---------- convert（真实动捕入口） ----------
    static void convert(String csv, String rigPath, String out, String animName, boolean loop,
                        double tolDeg) throws Exception {
        RigConfig rig = RigConfig.load(rigPath);
        MocapIO.Clip clip = MocapIO.readCsv(csv);
        pipeline(clip, rig, animName, loop, out, null, tolDeg);
        System.out.println("convert → " + out);
    }

    static void writeText(String path, String s) throws Exception {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
        try { w.write(s); } finally { w.close(); }
    }
}