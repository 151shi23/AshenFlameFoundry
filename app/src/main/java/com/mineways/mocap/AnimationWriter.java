package com.mineways.mocap;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blockbench / Bedrock {@code .animation.json} 写出器（schema: format 1.8.0）。
 *
 * <p>写出的每个字段都以 {@link AnimSpec} 为准（BB 5.x 的 compileAnimation 实证）：
 * <ul>
 *   <li>动画级：loop 三态、animation_length、override_previous_animation、
 *       anim_time_update / blend_weight / start_delay / loop_delay、特效时间轴；</li>
 *   <li>骨骼级：relative_to、rotation、position、scale；</li>
 *   <li>关键帧：线性裸数组；可选 Catmull-Rom（{@code {post, lerp_mode:"catmullrom"}}）；
 *       单键线性通道塌缩成裸数组（scale 同值再塌缩成单值）。</li>
 * </ul>
 *
 * <p>数值空间：JSON 的 rotation 是 BB 内部角的 X/Y 取反（compileAnimation 里
 * {@code [x,y,z] → [-x,-y,z]}），position 只取反 x，scale 不取反。默认入参是
 * 解算内部角，写出时统一在这里取反；编辑已有文件时把 {@link Settings#jsonSpace}
 * 置 true，原样输出。
 */
public final class AnimationWriter {
    private AnimationWriter() {}

    /** 生成参数（不传 = 老行为：循环、线性、2 位小数）。 */
    public static final class Settings {
        /** 是否循环；与 {@link #holdLastFrame} 组合成三态。 */
        public boolean loop = true;
        /** 不循环时停在最后一帧（{@code "hold_on_last_frame"}）。 */
        public boolean holdLastFrame;
        /** 绝对姿态：先重置骨骼再套本动画（动捕的语义就该如此）。 */
        public boolean overridePreviousAnimation;
        /** 平滑插值：整条曲线标 catmullrom（BB 里显示为「平滑」）。 */
        public boolean smooth;
        public int decimals = 2;
        /**
         * 入参是否已经是 JSON 数值（编辑器读出来的文件）。false = 解算内部角，
         * 写出时按 BB 编解码取反（rotation 的 X/Y、position 的 X）。
         */
        public boolean jsonSpace;
        /** Molang 字段：null/空 = 不写。 */
        public String animTimeUpdate, blendWeight, startDelay, loopDelay;

        public Map<String, List<KeyframeDecimator.Sample>> rotations;
        public Map<String, List<KeyframeDecimator.Sample>> positions;
        public Map<String, List<KeyframeDecimator.Sample>> scales;
        /** 骨骼 → "entity"（rotation 相对实体而非父骨骼）。 */
        public Map<String, String> relativeToRotation;
        /** particle_effects / sound_effects / timeline 等原样写入动画级。 */
        public Map<String, Object> extraAnimationFields;
    }

    public static void write(String outPath, String animName, boolean loop,
                             double lengthSec,
                             Map<String, List<KeyframeDecimator.Sample>> bones) throws IOException {
        write(outPath, animName, loop, lengthSec, bones, null, 2);
    }

    /**
     * @param positions 可选的位移通道（骨骼名 → 关键帧，值＝Bedrock 模型像素单位）：
     *                  跳跃的根位移就写在这里（body.position）。x 要取反（与 BB 编解码一致），
     *                  y/z 原样；没有位移的骨不出现该通道。
     * @param decimals  角度/位移的小数位：2＝常规（0.01°），3＝超高精度。
     */
    public static void write(String outPath, String animName, boolean loop,
                             double lengthSec,
                             Map<String, List<KeyframeDecimator.Sample>> bones,
                             Map<String, List<KeyframeDecimator.Sample>> positions,
                             int decimals) throws IOException {
        Settings s = new Settings();
        s.loop = loop;
        s.decimals = decimals;
        s.rotations = bones;
        s.positions = positions;
        write(outPath, animName, s, lengthSec);
    }

    /** 完整字段写出（动画级 + 骨骼级 + 关键帧插值）。 */
    public static void write(String outPath, String animName, Settings st, double lengthSec)
            throws IOException {
        if (st == null) st = new Settings();
        Map<String, Object> anim = new LinkedHashMap<String, Object>();

        Object loopJson = AnimSpec.loopJson(st.loop, st.holdLastFrame);
        if (loopJson != null) anim.put("loop", loopJson);
        if (lengthSec > 0) {
            anim.put("animation_length", AnimSpec.rd(lengthSec, 4));
        }
        if (st.overridePreviousAnimation) anim.put("override_previous_animation", Boolean.TRUE);
        if (notEmpty(st.animTimeUpdate)) anim.put("anim_time_update", st.animTimeUpdate.trim());
        if (notEmpty(st.blendWeight)) anim.put("blend_weight", st.blendWeight.trim());
        if (notEmpty(st.startDelay)) anim.put("start_delay", st.startDelay.trim());
        // loop_delay 只对循环动画有意义（BB 同样只在 e.loop 存在时才写）
        if (notEmpty(st.loopDelay) && AnimSpec.isLooping(loopJson)) {
            anim.put("loop_delay", st.loopDelay.trim());
        }
        if (st.extraAnimationFields != null && !st.extraAnimationFields.isEmpty()) {
            for (Map.Entry<String, Object> e : st.extraAnimationFields.entrySet()) {
                if (!anim.containsKey(e.getKey())) anim.put(e.getKey(), e.getValue());
            }
        }

        Map<String, Object> bones = new LinkedHashMap<String, Object>();
        for (String name : boneNames(st)) {
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            String rel = st.relativeToRotation == null ? null : st.relativeToRotation.get(name);
            if (rel != null) {
                Map<String, Object> rr = new LinkedHashMap<String, Object>();
                rr.put("rotation", rel);
                b.put("relative_to", rr);
                // BB 对「相对实体」的骨骼强制给一个非零旋转占位（0.01），否则通道不导出
                if (!hasChannel(st.rotations, name)) {
                    Map<String, Object> holder = new LinkedHashMap<String, Object>();
                    holder.put(MiniJson.fmtTime(0), new double[]{0, 0, 0.01});
                    b.put("rotation", holder);
                }
            }
            Object rot = channel(st, st.rotations, name, "rotation");
            if (rot != null) b.put("rotation", rot);
            Object pos = channel(st, st.positions, name, "position");
            if (pos != null) b.put("position", pos);
            Object sc = channel(st, st.scales, name, "scale");
            if (sc != null) b.put("scale", sc);
            if (!b.isEmpty()) bones.put(name, b);
        }
        if (!bones.isEmpty()) anim.put("bones", bones);
        else anim.put("bones", new LinkedHashMap<String, Object>());

        Map<String, Object> anims = new LinkedHashMap<String, Object>();
        anims.put(animName, anim);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format_version", AnimSpec.FORMAT_VERSION);
        root.put("animations", anims);
        writeFile(outPath, MiniJson.write(root, true));
    }

    /** 取一条通道 → JSON；内部角在这里按 BB 的符号约定换成 JSON 值。 */
    private static Object channel(Settings st, Map<String, List<KeyframeDecimator.Sample>> src,
                                  String bone, String channel) {
        List<KeyframeDecimator.Sample> list = src == null ? null : src.get(bone);
        if (list == null || list.isEmpty()) return null;
        List<KeyframeDecimator.Sample> work = new ArrayList<KeyframeDecimator.Sample>(list.size());
        for (KeyframeDecimator.Sample s : list) {
            double[] v = flip(s.e, channel, st.jsonSpace);
            double[] pre = s.pre == null ? null : flip(s.pre, channel, st.jsonSpace);
            work.add(new KeyframeDecimator.Sample(s.t, v, pre, s.lerp));
        }
        if (st.smooth) AnimSpec.markSmooth(work);
        return AnimSpec.encodeChannel(work, channel, st.decimals);
    }

    private static double[] flip(double[] v, String channel, boolean jsonSpace) {
        if (jsonSpace) return v;
        if ("rotation".equals(channel)) return toJsonRotation(v);
        if ("position".equals(channel)) return toJsonPosition(v);
        return v;
    }

    private static boolean hasChannel(Map<String, List<KeyframeDecimator.Sample>> m, String bone) {
        return m != null && m.get(bone) != null && !m.get(bone).isEmpty();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static Set<String> boneNames(Settings st) {
        Set<String> names = new LinkedHashSet<String>();
        if (st.rotations != null) names.addAll(st.rotations.keySet());
        if (st.positions != null) names.addAll(st.positions.keySet());
        if (st.scales != null) names.addAll(st.scales.keySet());
        if (st.relativeToRotation != null) names.addAll(st.relativeToRotation.keySet());
        return names;
    }

    /** 姿态快照（供 tools/bb_validate.js 真机摆姿势截图验证）。 */
    public static void writeSnapshots(String outPath, String rigName,
                                      Map<String, double[]> pivots,
                                      List<PoseSolver.Frame> frames,
                                      int nShots) throws IOException {
        List<Object> poses = new ArrayList<Object>();
        for (int s = 0; s < nShots; s++) {
            int idx = Math.min(frames.size() - 1,
                (int) Math.round(s * (frames.size() - 1) / (double) Math.max(1, nShots - 1)));
            PoseSolver.Frame f = frames.get(idx);
            Map<String, Object> bones = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, double[]> e : f.rotDeg.entrySet())
                bones.put(e.getKey(), new double[]{r2(e.getValue()[0]), r2(e.getValue()[1]), r2(e.getValue()[2])});
            Map<String, Object> pose = new LinkedHashMap<String, Object>();
            pose.put("t", MiniJson.fmt(f.tMs / 1000.0));
            pose.put("entity_yaw", MiniJson.fmt(f.entityYawDeg));
            pose.put("bones", bones);
            poses.add(pose);
        }
        Map<String, Object> pv = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, double[]> e : pivots.entrySet())
            pv.put(e.getKey(), new double[]{e.getValue()[0], e.getValue()[1], e.getValue()[2]});
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("rig", rigName);
        root.put("pivots", pv);
        root.put("poses", poses);
        writeFile(outPath, MiniJson.write(root, true));
    }

    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    static void writeFile(String path, String content) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
        try { w.write(content); } finally { w.close(); }
    }

    /**
     * 内部角（解算结果）→ JSON 值：rotation 取反 X/Y，position 取反 X。
     * 给生成路径与关键帧抽取共用，避免两处各写一份符号约定。
     */
    static double[] toJsonRotation(double[] internal) {
        return new double[]{-internal[0], -internal[1], internal[2]};
    }

    static double[] toJsonPosition(double[] internal) {
        return new double[]{-internal[0], internal[1], internal[2]};
    }
}
