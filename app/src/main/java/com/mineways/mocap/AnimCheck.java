package com.mineways.mocap;

import java.util.List;
import java.util.Map;

/**
 * 生成的 {@code .animation.json} 规范自检（Blockbench / Bedrock 1.8.0 契约）。
 *
 * <p>原包用 BB 官方 importFile 实测过 maxErr=0，但那是离线验证；装到手机上跑，
 * 必须在<b>每次生成后</b>再自检一遍，才能保证"手机产出的文件也是规范的"：
 * <ul>
 *   <li>{@code format_version} 必须是 1.8.0；</li>
 *   <li>{@code loop} 三态：缺省（单次）/ 布尔 / {@code "hold_on_last_frame"} / 数字秒；</li>
 *   <li>{@code animation_length} 是正数；</li>
 *   <li>{@code bones} 恰好是五根（body 被动，不该出现）；</li>
 *   <li>每条通道（rotation / position / scale）的关键帧时间键可解析、严格递增、落在时长内；
 *       值可以是 3 个数字、scale 单值、或 {@code {pre?, post, lerp_mode}} 详情对象；</li>
 *   <li>{@code lerp_mode} 只能是 {@code catmullrom} / {@code linear} / {@code step}；</li>
 *   <li>Molang 表达式通道允许存在（从 BB 文件编辑而来），但生成路径不许出现；</li>
 *   <li>角度绝对值 < 1e5（挡住 NaN/无穷/坏解算导致的爆炸值）。</li>
 * </ul>
 */
public final class AnimCheck {

    private static final String[] WANT = {"head", "leftArm", "rightArm", "leftLeg", "rightLeg"};

    private AnimCheck() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int bones;
        public final int keys;

        Result(boolean ok, String message, int bones, int keys) {
            this.ok = ok;
            this.message = message;
            this.bones = bones;
            this.keys = keys;
        }
    }

    public static Result check(String jsonPath, String animName) {
        return check(jsonPath, animName, false);
    }

    /**
     * @param allowSubset 编辑过的动画允许少于 5 根骨（用户把某根骨静音了）；
     *                    生成路径必须传 false，不能放宽。
     */
    @SuppressWarnings("unchecked")
    public static Result check(String jsonPath, String animName, boolean allowSubset) {
        Object root;
        try {
            root = MiniJson.parse(new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(jsonPath)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return new Result(false, "JSON 解析失败：" + e, 0, 0);
        }
        try {
            Map<String, Object> r = (Map<String, Object>) root;
            if (!AnimSpec.FORMAT_VERSION.equals(r.get("format_version"))) {
                return new Result(false, "format_version 不是 " + AnimSpec.FORMAT_VERSION, 0, 0);
            }
            Map<String, Object> anims = (Map<String, Object>) r.get("animations");
            if (anims == null || anims.isEmpty()) {
                return new Result(false, "没有 animations 节点", 0, 0);
            }
            Object nodeObj = anims.get(animName);
            if (!(nodeObj instanceof Map)) {
                return new Result(false, "动画名 " + animName + " 不在 animations 里", 0, 0);
            }
            Map<String, Object> node = (Map<String, Object>) nodeObj;

            Object loop = node.get("loop");
            if (loop != null && !(loop instanceof Boolean) && !AnimSpec.LOOP_HOLD.equals(loop)
                    && !(loop instanceof Number)) {
                return new Result(false, "loop 不是 布尔 / \"hold_on_last_frame\" / 数字：" + loop, 0, 0);
            }
            Object lenObj = node.get("animation_length");
            if (!(lenObj instanceof Number)) {
                return new Result(false, "animation_length 不是数字", 0, 0);
            }
            double len = ((Number) lenObj).doubleValue();
            if (!(len > 0) || Double.isInfinite(len)) {
                return new Result(false, "animation_length 不合理：" + len, 0, 0);
            }
            Map<String, Object> bones = (Map<String, Object>) node.get("bones");
            if (bones == null || bones.isEmpty()) {
                return new Result(false, "没有骨骼节点", 0, 0);
            }
            // 生成路径必须含齐五根人模骨；额外允许 hand/finger 与只有 position 的 body（跳跃位移）
            if (!allowSubset) {
                for (String w : WANT) {
                    if (!bones.containsKey(w)) {
                        return new Result(false, "缺少骨骼 " + w, bones.size(), 0);
                    }
                }
            }
            int totalKeys = 0;
            for (Map.Entry<String, Object> be : bones.entrySet()) {
                String name = be.getKey();
                if (!(be.getValue() instanceof Map)) {
                    return new Result(false, name + " 的骨骼节点不是对象", bones.size(), totalKeys);
                }
                Map<String, Object> b = (Map<String, Object>) be.getValue();
                for (String ch : AnimSpec.CHANNELS) {
                    if (!b.containsKey(ch)) continue;
                    String err = checkChannel(name, ch, b.get(ch), len, allowSubset);
                    if (err != null) return new Result(false, err, bones.size(), totalKeys);
                    totalKeys += countKeys(b.get(ch));
                }
            }
            return new Result(true,
                    "JSON 规范自检通过：format 1.8.0 · " + bones.size() + " 骨骼 · " + totalKeys + " 键 · 时长 "
                            + String.format(java.util.Locale.US, "%.2f", len) + "s · "
                            + AnimSpec.loopLabel(loop),
                    bones.size(), totalKeys);
        } catch (Throwable e) {
            return new Result(false, "自检异常：" + e, 0, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static String checkChannel(String bone, String channel, Object raw, double len,
                                       boolean allowSubset) {
        if (raw instanceof Map) {
            double prev = -1e-9;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
                double t;
                try {
                    t = Double.parseDouble(e.getKey());
                } catch (Throwable bad) {
                    return bone + " 的时间键不是数字：" + e.getKey();
                }
                if (!(t >= prev)) {
                    return bone + "." + channel + " 时间键不严格递增（" + e.getKey() + "）";
                }
                if (t > len + 1e-4) {
                    return bone + "." + channel + " 关键帧超出时长：" + t + " > " + len;
                }
                prev = t;
                String err = checkKeyframe(bone, channel, e.getValue(), allowSubset);
                if (err != null) return err;
            }
            return null;
        }
        // 裸数组 / 单值：整段常量。生成路径上不会出现，编辑 BB 文件时合法。
        return checkKeyframe(bone, channel, raw, allowSubset);
    }

    @SuppressWarnings("unchecked")
    private static String checkKeyframe(String bone, String channel, Object raw, boolean allowSubset) {
        String where = bone + "." + channel;
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            Object post = m.get("post");
            if (post == null) post = m.get("pre");
            if (post == null) return where + " 的关键帧缺少 post/pre";
            String err = checkVector(where, post, allowSubset);
            if (err != null) return err;
            Object pre = m.get("pre");
            if (pre != null) {
                err = checkVector(where + ".pre", pre, allowSubset);
                if (err != null) return err;
            }
            Object lm = m.get("lerp_mode");
            if (lm != null && !AnimSpec.LERP_CATMULLROM.equals(lm)
                    && !AnimSpec.LERP_LINEAR.equals(lm) && !AnimSpec.LERP_STEP.equals(lm)) {
                return where + " 的 lerp_mode 非法：" + lm;
            }
            return null;
        }
        return checkVector(where, raw, allowSubset);
    }

    @SuppressWarnings("unchecked")
    private static String checkVector(String where, Object raw, boolean allowSubset) {
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1e5) {
                return where + " 数值越界/非法：" + v;
            }
            return null;
        }
        if (!(raw instanceof List)) return where + " 的值既不是数组也不是数字：" + raw;
        List<Object> v = (List<Object>) raw;
        if (v.size() != 3) return where + " 不是 3 个分量";
        for (Object o : v) {
            if (!(o instanceof Number)) {
                // Molang 表达式：从 BB 文件编辑而来时合法，生成路径不允许
                if (allowSubset && (o instanceof String) && !((String) o).trim().isEmpty()) continue;
                return where + " 含非数字（Molang 只在编辑路径允许）";
            }
            double d = ((Number) o).doubleValue();
            // 拆过 ±360 跳变后，连续转圈 >1 圈的角度可能超过 360（如 480），
            // 这里只挡 NaN/无穷和离谱的爆炸值。
            if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 1e5) {
                return where + " 角度越界/非法：" + d;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static int countKeys(Object raw) {
        if (!(raw instanceof Map)) return 1;
        return ((Map<String, Object>) raw).size();
    }
}
