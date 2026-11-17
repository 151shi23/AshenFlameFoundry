package com.mineways.mocap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockbench / 基岩 {@code actor_animation:1.8.0} 的字段契约（单一事实来源）。
 *
 * <p><b>依据</b>：本机内置的 Blockbench（{@code assets/blockbench/dist/bundle.js}，
 * {@code AnimationCodec.bedrock.compileAnimation} / {@code Keyframe.compileBedrockKeyframe}）
 * 与官方动画定义文档逐条对照。凡是 BB「重新导入再导出」会改写的地方，这里都必须一致。
 *
 * <p><b>动画级</b>：{@code loop}（{@code true} / {@code false} / {@code "hold_on_last_frame"} /
 * 数字=秒）、{@code animation_length}、{@code override_previous_animation}、
 * {@code anim_time_update}、{@code blend_weight}、{@code start_delay}、
 * {@code loop_delay}（仅循环动画有效）、{@code particle_effects}、{@code sound_effects}、
 * {@code timeline}。
 *
 * <p><b>骨骼级</b>：{@code relative_to}（{@code {"rotation":"entity"}}）、
 * {@code rotation}、{@code position}、{@code scale}。
 *
 * <p><b>关键帧</b>：{@code [x,y,z]} / 单值（scale）/ {@code {pre?, post, lerp_mode}}；
 * {@code lerp_mode} 官方只认 {@code "catmullrom"}（缺省=线性）。
 *
 * <p><b>BB 导出细节（本类逐条复刻）</b>：
 * <ul>
 *   <li>「单次播放」不写 {@code loop} 字段（缺省即停止），「保持末帧」写 {@code "hold_on_last_frame"}；</li>
 *   <li>{@code animation_length} 保留 4 位小数；</li>
 *   <li>通道只有一个线性关键帧 → 塌缩成裸数组；{@code scale} 三分量相同 → 塌缩成单值；</li>
 *   <li>{@code catmullrom} 关键帧写 {@code {post:[...], lerp_mode:"catmullrom"}}，
 *       只有「上一个关键帧不是 catmullrom」时才带 {@code pre}；</li>
 *   <li>{@code position} / {@code rotation} 导出时 x、y 取反，{@code scale} 不取反；</li>
 *   <li>骨骼节点只放有内容的通道，未知字段（特效、relative_to 等）原样透传。</li>
 * </ul>
 */
public final class AnimSpec {

    private AnimSpec() {}

    public static final String FORMAT_VERSION = "1.8.0";
    public static final String LOOP_HOLD = "hold_on_last_frame";
    public static final String LERP_CATMULLROM = "catmullrom";
    public static final String LERP_LINEAR = "linear";
    public static final String LERP_STEP = "step";

    /** Bedrock 的骨骼变换通道（BB 的 Animator.possible_channels）。 */
    public static final String[] CHANNELS = {"rotation", "position", "scale"};

    public static final String[] EXTRA_ANIM_FIELDS = {
            "override_previous_animation", "anim_time_update", "blend_weight",
            "start_delay", "loop_delay", "particle_effects", "sound_effects", "timeline"
    };

    // ---------------------------------------------------------------- loop 三态

    /** {@code loop} 是否为循环（true 或正数秒）。 */
    public static boolean isLooping(Object loop) {
        if (Boolean.TRUE.equals(loop)) return true;
        return loop instanceof Number && ((Number) loop).doubleValue() > 0;
    }

    /** {@code loop} 是否为「停在最后一帧」。 */
    public static boolean isHold(Object loop) {
        return LOOP_HOLD.equals(loop);
    }

    /** 人话标签（界面用）。 */
    public static String loopLabel(Object loop) {
        if (isHold(loop)) return "保持末帧";
        if (isLooping(loop)) return "循环";
        return "单次";
    }

    /**
     * 写进 JSON 的 loop 值：BB 对「单次」不写字段（返回 {@code null}），
     * 对「保持末帧」写字符串，对循环写 {@code true}。
     */
    public static Object loopJson(boolean loop, boolean holdLastFrame) {
        if (loop) return Boolean.TRUE;
        return holdLastFrame ? LOOP_HOLD : null;
    }

    // ---------------------------------------------------------------- 关键帧编解码

    /** 解出来的单个关键帧：值 + 可选前切线 + 可选插值模式。 */
    public static final class Key {
        public final double[] value;
        public final double[] pre;
        public final String lerp;

        public Key(double[] value, double[] pre, String lerp) {
            this.value = value;
            this.pre = pre;
            this.lerp = lerp;
        }
    }

    /**
     * 解析任意合法关键帧值。
     *
     * @return {@code null} 表示无法用数值表达（Molang 表达式 / 结构不认识）——
     *         调用方必须原样保留，不能拿 0 覆盖。
     */
    @SuppressWarnings("unchecked")
    public static Key decode(Object raw) {
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            return new Key(new double[]{v, v, v}, null, null);
        }
        if (raw instanceof List) {
            double[] v = numArray((List<Object>) raw);
            return v == null ? null : new Key(v, null, null);
        }
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            double[] post = numArray(m.get("post"));
            double[] pre = numArray(m.get("pre"));
            if (post == null) return null;   // 只有 pre / Molang / 其它结构 → 不透明
            Object lm = m.get("lerp_mode");
            String lerp = lm instanceof String ? (String) lm : null;
            return new Key(post, pre, lerp);
        }
        return null;
    }

    /** 3 个分量都必须是有限数字，否则视为不透明（Molang）。 */
    @SuppressWarnings("unchecked")
    private static double[] numArray(Object o) {
        if (!(o instanceof List)) return null;
        List<Object> l = (List<Object>) o;
        if (l.size() != 3) return null;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number)) return null;
            v[i] = ((Number) l.get(i)).doubleValue();
            if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) return null;
        }
        return v;
    }

    /** 编码单个关键帧（BB 规则：catmullrom 才写详情对象，否则裸数组）。 */
    public static Object encodeKey(double[] value, double[] pre, String lerp, int decimals) {
        double[] post = round(value, decimals);
        if (LERP_CATMULLROM.equals(lerp)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            if (pre != null) m.put("pre", round(pre, decimals));
            m.put("post", post);
            m.put("lerp_mode", LERP_CATMULLROM);
            return m;
        }
        return post;
    }

    // ---------------------------------------------------------------- 通道编码

    /**
     * 一个骨骼通道 → JSON。空列表返回 {@code null}（该通道不写）。
     *
     * <p>单关键帧线性通道按 BB 的做法塌缩成裸数组；scale 三分量相同再塌缩成单值。
     */
    public static Object encodeChannel(List<KeyframeDecimator.Sample> list, String channel, int decimals) {
        return encodeChannel(list, channel, decimals, 3);
    }

    /**
     * @param timeDecimals 时间键小数位：生成用 3（BB 时间码同款），
     *                     编辑已有文件用 4（BB 导出的 "0.0417" 原样保留）。
     */
    public static Object encodeChannel(List<KeyframeDecimator.Sample> list, String channel,
                                       int decimals, int timeDecimals) {
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) {
            KeyframeDecimator.Sample s = list.get(0);
            boolean plain = s.pre == null && s.lerp == null;
            if (plain) {
                double[] v = round(s.e, decimals);
                if ("scale".equals(channel) && v[0] == v[1] && v[1] == v[2]) return (Double) v[0];
                return v;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (KeyframeDecimator.Sample s : list) {
            out.put(MiniJson.fmtTime(s.t, timeDecimals), encodeKey(s.e, s.pre, s.lerp, decimals));
        }
        return out;
    }

    /**
     * 生成路径的平滑处理：给整条曲线打上 {@code catmullrom} 标记。
     *
     * <p>BB 的规则：只有「上一个关键帧不是 catmullrom」时才写 {@code pre}；
     * 因此除第一个关键帧外都不带 pre，曲线是穿过所有关键帧的 Catmull-Rom 样条。
     */
    public static void markSmooth(List<KeyframeDecimator.Sample> list) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            KeyframeDecimator.Sample s = list.get(i);
            KeyframeDecimator.Sample prev = i == 0 ? null : list.get(i - 1);
            boolean needPre = prev == null ? s.t > 1e-9 : !LERP_CATMULLROM.equals(prev.lerp);
            s.pre = needPre ? s.e.clone() : null;
            s.lerp = LERP_CATMULLROM;
        }
    }

    // ---------------------------------------------------------------- 小工具

    public static double[] round(double[] v, int decimals) {
        return new double[]{rd(v[0], decimals), rd(v[1], decimals), rd(v[2], decimals)};
    }

    public static double rd(double v, int decimals) {
        double k = Math.pow(10, Math.max(0, Math.min(6, decimals)));
        return Math.round(v * k) / k;
    }

    /** 取 JSON 里所有时间键的最大值（含特效时间轴），用于补算 animation_length。 */
    @SuppressWarnings("unchecked")
    public static double maxEventTime(Object node) {
        double max = 0;
        if (node instanceof Map) {
            for (Object k : ((Map<String, Object>) node).keySet()) {
                try {
                    max = Math.max(max, Double.parseDouble(String.valueOf(k)));
                } catch (NumberFormatException ignored) {
                    // 非时间键：忽略
                }
            }
        }
        return max;
    }

    /** 列出某骨骼节点里除标准通道外的字段（relative_to、未知扩展）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extrasOf(Map<String, Object> node, String... known) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        outer:
        for (Map.Entry<String, Object> e : node.entrySet()) {
            for (String k : known) if (k.equals(e.getKey())) continue outer;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 便于测试与日志：把关键帧列表压成 "t=值" 的可读串。 */
    public static String describe(List<KeyframeDecimator.Sample> l) {
        StringBuilder b = new StringBuilder();
        for (KeyframeDecimator.Sample s : l) {
            if (b.length() > 0) b.append(", ");
            b.append(MiniJson.fmtTime(s.t)).append('=').append(java.util.Arrays.toString(s.e));
            if (s.lerp != null) b.append('[').append(s.lerp).append(']');
        }
        return b.toString();
    }

    static List<KeyframeDecimator.Sample> newList() {
        return new ArrayList<KeyframeDecimator.Sample>();
    }
}
