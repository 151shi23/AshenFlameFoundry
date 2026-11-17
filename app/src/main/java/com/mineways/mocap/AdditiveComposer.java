package com.mineways.mocap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可叠加特性（layering）演示：将叠加层动画逐骨逐时刻加到基础层上（离线烘焙合成），
 * 同时保留两份独立文件供运行时原生分层（Bedrock animation controller / GeckoLib additive）。
 *
 * Bedrock JSON 动画可叠加的根基：骨骼名一致 + 旋转通道按骨骼独立叠加，
 * 不同动画文件对同一骨骼的 rotation 在运行时按层混合；离线烘焙即 f(t) = base(t) + add(t)。
 */
public final class AdditiveComposer {
    private AdditiveComposer() {}

    @SuppressWarnings("unchecked")
    public static void compose(String basePath, String addPath, String outPath,
                               String outAnimName) throws java.io.IOException {
        Map<String, Object> base = asAnim(MiniJson.parse(
            new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(basePath)),
                java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, Object> add = asAnim(MiniJson.parse(
            new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(addPath)),
                java.nio.charset.StandardCharsets.UTF_8)));

        Map<String, List<KeyframeDecimator.Sample>> baseBones = extract(base);
        Map<String, List<KeyframeDecimator.Sample>> addBones = extract(add);

        // 合成后长度
        double len = Math.max(length(base), length(add));

        // 时间轴并集
        java.util.TreeSet<Double> times = new java.util.TreeSet<Double>();
        for (List<KeyframeDecimator.Sample> l : baseBones.values()) for (KeyframeDecimator.Sample s : l) times.add(s.t);
        for (List<KeyframeDecimator.Sample> l : addBones.values()) for (KeyframeDecimator.Sample s : l) times.add(s.t);

        Map<String, Object> bonesOut = new LinkedHashMap<String, Object>();
        java.util.Set<String> allBones = new java.util.LinkedHashSet<String>();
        allBones.addAll(baseBones.keySet());
        allBones.addAll(addBones.keySet());
        for (String bone : allBones) {
            Map<String, Object> rot = new LinkedHashMap<String, Object>();
            for (double t : times) {
                double[] e = new double[3];
                for (int k = 0; k < 3; k++) e[k] = 0;
                List<KeyframeDecimator.Sample> bl = baseBones.get(bone);
                if (bl != null) {
                    double[] v = sample(bl, t);
                    for (int k = 0; k < 3; k++) e[k] += v[k];
                }
                List<KeyframeDecimator.Sample> al = addBones.get(bone);
                if (al != null) {
                    double[] v = sample(al, t);
                    for (int k = 0; k < 3; k++) e[k] += v[k];
                }
                rot.put(MiniJson.fmtTime(t), new double[]{r2(e[0]), r2(e[1]), r2(e[2])});
            }
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            b.put("rotation", rot);
            bonesOut.put(bone, b);
        }

        Object baseLoop = base.get("loop");
        Map<String, Object> anim = new LinkedHashMap<String, Object>();
        // loop 三态原样继承（true / "hold_on_last_frame" / 数字秒）；缺省=单次，不写
        if (baseLoop != null) anim.put("loop", baseLoop);
        anim.put("animation_length", AnimSpec.rd(len, 4));
        if (base.containsKey("override_previous_animation")) {
            anim.put("override_previous_animation", base.get("override_previous_animation"));
        }
        anim.put("bones", bonesOut);
        Map<String, Object> anims = new LinkedHashMap<String, Object>();
        anims.put(outAnimName, anim);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format_version", "1.8.0");
        root.put("animations", anims);
        AnimationWriter.writeFile(outPath, MiniJson.write(root, true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asAnim(Object root) {
        Map<String, Object> anims = (Map<String, Object>) ((Map<String, Object>) root).get("animations");
        return (Map<String, Object>) anims.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<KeyframeDecimator.Sample>> extract(Map<String, Object> anim) {
        Map<String, List<KeyframeDecimator.Sample>> out = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        Map<String, Object> bones = (Map<String, Object>) anim.get("bones");
        if (bones == null) return out;
        for (Map.Entry<String, Object> e : bones.entrySet()) {
            Map<String, Object> b = (Map<String, Object>) e.getValue();
            Object rot = b.get("rotation");
            List<KeyframeDecimator.Sample> list = new ArrayList<KeyframeDecimator.Sample>();
            if (rot instanceof Map) {
                for (Map.Entry<String, Object> k : ((Map<String, Object>) rot).entrySet()) {
                    double t;
                    try {
                        t = Double.parseDouble(k.getKey());
                    } catch (NumberFormatException bad) {
                        continue;
                    }
                    AnimSpec.Key key = AnimSpec.decode(k.getValue());
                    // 不透明（Molang）通道无法参与烘焙，跳过整根骨
                    if (key == null) { list.clear(); break; }
                    list.add(new KeyframeDecimator.Sample(t, key.value, key.pre, key.lerp));
                }
            } else if (rot != null) {
                AnimSpec.Key key = AnimSpec.decode(rot);
                if (key != null) list.add(new KeyframeDecimator.Sample(0, key.value, key.pre, key.lerp));
            }
            java.util.Collections.sort(list, new java.util.Comparator<KeyframeDecimator.Sample>() {
                public int compare(KeyframeDecimator.Sample a, KeyframeDecimator.Sample b) { return Double.compare(a.t, b.t); }
            });
            if (!list.isEmpty()) out.put(e.getKey(), list);
        }
        return out;
    }

    private static double[] toArr(Object o) {
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            return new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2))};
        }
        double v = num(o);
        return new double[]{v, v, v};
    }

    private static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }

    /** 线性插值采样。 */
    static double[] sample(List<KeyframeDecimator.Sample> list, double t) {
        if (list.isEmpty()) return new double[3];
        if (t <= list.get(0).t) return copy(list.get(0).e);
        if (t >= list.get(list.size() - 1).t) return copy(list.get(list.size() - 1).e);
        int lo = 0, hi = list.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (list.get(mid).t <= t) lo = mid; else hi = mid;
        }
        KeyframeDecimator.Sample a = list.get(lo), b = list.get(hi);
        double f = (t - a.t) / Math.max(1e-9, b.t - a.t);
        double[] o = new double[3];
        for (int k = 0; k < 3; k++) o[k] = a.e[k] + (b.e[k] - a.e[k]) * f;
        return o;
    }

    private static double[] copy(double[] a) { return new double[]{a[0], a[1], a[2]}; }
    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double length(Map<String, Object> anim) {
        Object l = anim.get("animation_length");
        return l instanceof Number ? ((Number) l).doubleValue() : 0;
    }
}
