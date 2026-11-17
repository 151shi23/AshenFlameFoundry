package com.mineways.mocap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已录动画的编辑层：读现有 {@code .animation.json} → 裁时间 / 删区间 / 变速 / 静音骨骼 → 原样写出。
 *
 * <p>全程在 <b>JSON 数值空间</b>里操作（不做 x/y 取反）：编辑只改时间轴与骨骼取舍，
 * 数值必须逐字保留，这样任何一次「打开→不编辑→导出」都不会动到动作本身。
 *
 * <p><b>无损范围（对齐 Blockbench 的字段契约）</b>：
 * <ul>
 *   <li>动画级：{@code loop} 三态、{@code animation_length}、
 *       {@code override_previous_animation}、{@code anim_time_update}、{@code blend_weight}、
 *       {@code start_delay}、{@code loop_delay}、{@code particle_effects}、{@code sound_effects}、
 *       {@code timeline} 以及任何不认识的字段 —— 全部原样保留；</li>
 *   <li>骨骼级：{@code relative_to}、{@code rotation}、{@code position}、{@code scale}、
 *       以及不认识的字段；</li>
 *   <li>关键帧：裸数组、单值、{@code {pre, post, lerp_mode}} 详情对象都能读能写，
 *       {@code catmullrom} 标记跟着时间编辑一起搬走；</li>
 *   <li>同一个 JSON 里的其它动画、以及 {@code animation_controllers} 等顶层节点不会被删掉；</li>
 *   <li>Molang 表达式通道（无法做数值编辑）原样透传，不会被 0 覆盖。</li>
 * </ul>
 *
 * <p>区间边界会按线性插值补一个关键帧（Bedrock 关键帧默认就是线性插值），
 * 所以裁出来的首尾不会跳变；时间统一从 0 起算。
 */
public final class AnimJson {
    private AnimJson() {}

    public static final class Clip {
        public String name;
        /** 原始 loop 值：{@code null}（缺省=单次）/ Boolean / "hold_on_last_frame" / 数字秒。 */
        public Object loop;
        public double length;
        public String formatVersion = AnimSpec.FORMAT_VERSION;
        /** 写出时数值/时间键的小数位（编辑路径默认 4，尽量逐字保留）。 */
        public int decimals = 4;
        public int timeDecimals = 4;

        /** rotation 通道。 */
        public final Map<String, List<KeyframeDecimator.Sample>> bones =
                new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        /** position 通道（body 跳跃等）。 */
        public final Map<String, List<KeyframeDecimator.Sample>> positions =
                new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        /** scale 通道（原样编辑，本管线不生成）。 */
        public final Map<String, List<KeyframeDecimator.Sample>> scales =
                new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();

        /** 骨骼 → 通道 → 原样 JSON：Molang 等无法数值化的内容，编辑时跳过、导出时照抄。 */
        public final Map<String, Map<String, Object>> opaque =
                new LinkedHashMap<String, Map<String, Object>>();
        /** 动画级其它字段（override / 特效时间轴 / 未知扩展）。 */
        public final Map<String, Object> extras = new LinkedHashMap<String, Object>();
        /** 骨骼级非通道字段（relative_to / 未知扩展）。 */
        public final Map<String, Map<String, Object>> boneExtras =
                new LinkedHashMap<String, Map<String, Object>>();
        /** 同一文件里的其它动画，原样写回。 */
        public final Map<String, Object> otherAnimations = new LinkedHashMap<String, Object>();
        /** animations / format_version 之外的顶层节点（animation_controllers 等）。 */
        public final Map<String, Object> rootExtras = new LinkedHashMap<String, Object>();

        public int keyCount() {
            int n = 0;
            for (List<KeyframeDecimator.Sample> l : bones.values()) n += l.size();
            for (List<KeyframeDecimator.Sample> l : positions.values()) n += l.size();
            for (List<KeyframeDecimator.Sample> l : scales.values()) n += l.size();
            for (Map<String, Object> ch : opaque.values()) n += ch.size();
            return n;
        }

        public boolean hasPosition(String bone) {
            return positions.containsKey(bone) && !positions.get(bone).isEmpty();
        }

        public boolean hasRotation(String bone) {
            return bones.containsKey(bone) && !bones.get(bone).isEmpty();
        }

        public boolean hasScale(String bone) {
            return scales.containsKey(bone) && !scales.get(bone).isEmpty();
        }

        /** 这根骨有没有任何可动内容（旋转/位移/缩放/不透明通道）。 */
        public boolean hasBone(String bone) {
            return hasRotation(bone) || hasPosition(bone) || hasScale(bone)
                    || (opaque.containsKey(bone) && !opaque.get(bone).isEmpty());
        }

        public String loopLabel() {
            return AnimSpec.loopLabel(loop);
        }
    }

    @SuppressWarnings("unchecked")
    public static Clip read(String path) throws IOException {
        String s = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Object rootObj = MiniJson.parse(s);
        Map<String, Object> r = (Map<String, Object>) rootObj;
        Object animsObj = r.get("animations");
        if (!(animsObj instanceof Map) || ((Map<String, Object>) animsObj).isEmpty()) {
            throw new IOException("这个 JSON 里没有 animations（不是 Blockbench/基岩动画文件？）");
        }
        Map<String, Object> anims = (Map<String, Object>) animsObj;
        Map.Entry<String, Object> first = anims.entrySet().iterator().next();
        Map<String, Object> a = (Map<String, Object>) first.getValue();

        Clip c = new Clip();
        c.name = first.getKey();
        Object fv = r.get("format_version");
        if (fv instanceof String) c.formatVersion = (String) fv;
        c.loop = a.get("loop");
        Object len = a.get("animation_length");
        if (len instanceof Number) c.length = ((Number) len).doubleValue();

        for (String ch : AnimSpec.EXTRA_ANIM_FIELDS) {
            if (a.containsKey(ch)) c.extras.put(ch, a.get(ch));
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            String k = e.getKey();
            if ("loop".equals(k) || "animation_length".equals(k) || "bones".equals(k)) continue;
            if (!c.extras.containsKey(k)) c.extras.put(k, e.getValue());
        }

        Object bonesObj = a.get("bones");
        if (bonesObj instanceof Map) {
            Map<String, Object> bones = (Map<String, Object>) bonesObj;
            for (Map.Entry<String, Object> be : bones.entrySet()) {
                if (!(be.getValue() instanceof Map)) continue;
                String bone = be.getKey();
                Map<String, Object> node = (Map<String, Object>) be.getValue();
                Map<String, Object> ex = AnimSpec.extrasOf(node, AnimSpec.CHANNELS);
                if (!ex.isEmpty()) c.boneExtras.put(bone, ex);
                readChannel(c, node, bone, "rotation", c.bones);
                readChannel(c, node, bone, "position", c.positions);
                readChannel(c, node, bone, "scale", c.scales);
            }
        }

        for (Map.Entry<String, Object> e : anims.entrySet()) {
            if (e.getKey().equals(c.name)) continue;
            c.otherAnimations.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : r.entrySet()) {
            if ("format_version".equals(e.getKey()) || "animations".equals(e.getKey())) continue;
            c.rootExtras.put(e.getKey(), e.getValue());
        }

        if (c.length <= 0) {
            double max = 0;
            for (Map<String, List<KeyframeDecimator.Sample>> m : channels(c))
                for (List<KeyframeDecimator.Sample> l : m.values())
                    for (KeyframeDecimator.Sample sp : l) max = Math.max(max, sp.t);
            for (String k : new String[]{"particle_effects", "sound_effects", "timeline"}) {
                max = Math.max(max, AnimSpec.maxEventTime(c.extras.get(k)));
            }
            c.length = max;
        }
        return c;
    }

    /**
     * 读一条通道。三种合法写法都认：关键帧对象、裸数组（整段常量）、单值（scale 常量）；
     * 含 Molang 或结构不认识时整条通道降级为「不透明」，原样保留。
     */
    @SuppressWarnings("unchecked")
    private static void readChannel(Clip c, Map<String, Object> node, String bone,
                                    String channel,
                                    Map<String, List<KeyframeDecimator.Sample>> dst) {
        Object o = node.get(channel);
        if (o == null) return;
        if (!(o instanceof Map)) {
            AnimSpec.Key k = AnimSpec.decode(o);
            if (k == null) returnOpaque(c, bone, channel, o);
            else {
                List<KeyframeDecimator.Sample> l = new ArrayList<KeyframeDecimator.Sample>();
                l.add(new KeyframeDecimator.Sample(0, k.value, k.pre, k.lerp));
                dst.put(bone, l);
            }
            return;
        }
        Map<String, Object> ch = (Map<String, Object>) o;
        if (ch.isEmpty()) return;
        List<KeyframeDecimator.Sample> list = new ArrayList<KeyframeDecimator.Sample>();
        for (Map.Entry<String, Object> ke : ch.entrySet()) {
            double t;
            try {
                t = Double.parseDouble(ke.getKey());
            } catch (NumberFormatException bad) {
                returnOpaque(c, bone, channel, o);
                return;
            }
            AnimSpec.Key k = AnimSpec.decode(ke.getValue());
            if (k == null) {
                returnOpaque(c, bone, channel, o);
                return;
            }
            list.add(new KeyframeDecimator.Sample(t, k.value, k.pre, k.lerp));
        }
        list.sort((x, y) -> Double.compare(x.t, y.t));
        dst.put(bone, dedupe(list));
    }

    private static void returnOpaque(Clip c, String bone, String channel, Object raw) {
        Map<String, Object> m = c.opaque.get(bone);
        if (m == null) {
            m = new LinkedHashMap<String, Object>();
            c.opaque.put(bone, m);
        }
        m.put(channel, raw);
    }

    private static List<Map<String, List<KeyframeDecimator.Sample>>> channels(Clip c) {
        List<Map<String, List<KeyframeDecimator.Sample>>> out =
                new ArrayList<Map<String, List<KeyframeDecimator.Sample>>>();
        out.add(c.bones);
        if (!c.positions.isEmpty()) out.add(c.positions);
        if (!c.scales.isEmpty()) out.add(c.scales);
        return out;
    }

    /** 只保留 [t0,t1]，并把时间平移到 0 起点。 */
    public static void keep(Clip c, double t0, double t1) {
        if (t1 - t0 < 1e-3) throw new IllegalArgumentException("保留区间太短");
        double a = Math.max(0, t0), b = Math.min(c.length, t1);
        for (Map<String, List<KeyframeDecimator.Sample>> map : channels(c)) {
            for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : map.entrySet()) {
                List<KeyframeDecimator.Sample> in = e.getValue();
                List<KeyframeDecimator.Sample> out = new ArrayList<KeyframeDecimator.Sample>();
                double[] va = valueAt(in, a);
                if (va != null) out.add(new KeyframeDecimator.Sample(0, va));
                for (KeyframeDecimator.Sample s : in) {
                    if (s.t > a + 1e-9 && s.t < b - 1e-9) out.add(s.at(s.t - a));
                }
                double[] vb = valueAt(in, b);
                if (vb != null) out.add(new KeyframeDecimator.Sample(b - a, vb));
                e.setValue(dedupe(out));
            }
        }
        remapEvents(c, t -> (t < a - 1e-9 || t > b + 1e-9) ? Double.NaN : t - a);
        c.length = b - a;
    }

    /** 删掉 [t0,t1] 并把后面的关键帧整体前移，接缝处补一个关键帧。 */
    public static void cut(Clip c, double t0, double t1) {
        double a = Math.max(0, t0), b = Math.min(c.length, t1);
        double removed = b - a;
        if (removed <= 1e-3) throw new IllegalArgumentException("删除区间太短");
        for (Map<String, List<KeyframeDecimator.Sample>> map : channels(c)) {
            for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : map.entrySet()) {
                List<KeyframeDecimator.Sample> in = e.getValue();
                List<KeyframeDecimator.Sample> out = new ArrayList<KeyframeDecimator.Sample>();
                for (KeyframeDecimator.Sample s : in) {
                    if (s.t < a - 1e-9) out.add(s.at(s.t));
                }
                double[] va = valueAt(in, a);
                if (va != null) out.add(new KeyframeDecimator.Sample(a, va));
                for (KeyframeDecimator.Sample s : in) {
                    if (s.t > b + 1e-9) out.add(s.at(s.t - removed));
                }
                e.setValue(dedupe(out));
            }
        }
        remapEvents(c, t -> (t > a - 1e-9 && t < b + 1e-9) ? Double.NaN : (t > b ? t - removed : t));
        c.length = Math.max(0, c.length - removed);
    }

    /** 整体变速：倍率 >1 变慢（时长变长）。 */
    public static void scale(Clip c, double factor) {
        if (!(factor > 0.05 && factor < 20)) throw new IllegalArgumentException("倍率要在 0.05~20 之间");
        for (Map<String, List<KeyframeDecimator.Sample>> map : channels(c)) {
            for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : map.entrySet()) {
                List<KeyframeDecimator.Sample> out = new ArrayList<KeyframeDecimator.Sample>(e.getValue().size());
                for (KeyframeDecimator.Sample s : e.getValue()) out.add(s.at(s.t * factor));
                e.setValue(out);
            }
        }
        remapEvents(c, t -> t * factor);
        c.length = c.length * factor;
    }

    /**
     * 特效/时间轴这类「时间 → 事件」的字典：跟着剪辑一起改时间键（事件值原样）。
     * 映射返回 {@code NaN} 表示该事件被裁掉。
     */
    @SuppressWarnings("unchecked")
    private static void remapEvents(Clip c, EventMap f) {
        for (String key : new String[]{"particle_effects", "sound_effects", "timeline"}) {
            Object o = c.extras.get(key);
            if (!(o instanceof Map)) continue;
            Map<String, Object> src = (Map<String, Object>) o;
            List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(src.entrySet());
            entries.sort((x, y) -> Double.compare(timeOf(x.getKey()), timeOf(y.getKey())));
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : entries) {
                double nt = f.map(timeOf(e.getKey()));
                if (Double.isNaN(nt) || nt < 0) continue;
                out.put(MiniJson.fmtTime(nt, c.timeDecimals), e.getValue());
            }
            if (out.isEmpty()) c.extras.remove(key);
            else c.extras.put(key, out);
        }
    }

    private static double timeOf(String key) {
        try {
            return Double.parseDouble(key);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    private interface EventMap {
        double map(double t);
    }

    public static void mute(Clip c, String bone) {
        c.bones.remove(bone);
        c.positions.remove(bone);
        c.scales.remove(bone);
        c.opaque.remove(bone);
        c.boneExtras.remove(bone);
    }

    public static Clip copy(Clip c) {
        Clip n = new Clip();
        n.name = c.name;
        n.loop = c.loop;
        n.length = c.length;
        n.formatVersion = c.formatVersion;
        n.decimals = c.decimals;
        n.timeDecimals = c.timeDecimals;
        copyChannels(c.bones, n.bones);
        copyChannels(c.positions, n.positions);
        copyChannels(c.scales, n.scales);
        deepCopyMap(c.extras, n.extras);
        deepCopyMap(c.otherAnimations, n.otherAnimations);
        deepCopyMap(c.rootExtras, n.rootExtras);
        for (Map.Entry<String, Map<String, Object>> e : c.opaque.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            deepCopyMap(e.getValue(), m);
            if (!m.isEmpty()) n.opaque.put(e.getKey(), m);
        }
        for (Map.Entry<String, Map<String, Object>> e : c.boneExtras.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            deepCopyMap(e.getValue(), m);
            if (!m.isEmpty()) n.boneExtras.put(e.getKey(), m);
        }
        return n;
    }

    private static void copyChannels(Map<String, List<KeyframeDecimator.Sample>> src,
                                     Map<String, List<KeyframeDecimator.Sample>> dst) {
        for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : src.entrySet()) {
            List<KeyframeDecimator.Sample> l = new ArrayList<KeyframeDecimator.Sample>(e.getValue().size());
            for (KeyframeDecimator.Sample s : e.getValue()) l.add(s.copy());
            dst.put(e.getKey(), l);
        }
    }

    /** 深拷贝一段 JSON 子树（MiniJson 往返，代价小、语义可靠）。 */
    @SuppressWarnings("unchecked")
    private static void deepCopyMap(Map<String, Object> src, Map<String, Object> dst) {
        if (src.isEmpty()) return;
        Object o = MiniJson.parse(MiniJson.write(src, false));
        dst.putAll((Map<String, Object>) o);
    }

    /** 原样（JSON 数值空间）写出，不做 x/y 取反。 */
    public static void write(Clip c, String path) throws IOException {
        Map<String, Object> anim = new LinkedHashMap<String, Object>();
        if (c.loop != null) anim.put("loop", c.loop);
        if (c.length > 0) anim.put("animation_length", AnimSpec.rd(c.length, 4));
        for (Map.Entry<String, Object> e : c.extras.entrySet()) anim.put(e.getKey(), e.getValue());

        Map<String, Object> bones = new LinkedHashMap<String, Object>();
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        names.addAll(c.boneExtras.keySet());
        names.addAll(c.bones.keySet());
        names.addAll(c.positions.keySet());
        names.addAll(c.scales.keySet());
        names.addAll(c.opaque.keySet());
        for (String name : names) {
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            Map<String, Object> ex = c.boneExtras.get(name);
            if (ex != null) b.putAll(ex);
            for (String ch : AnimSpec.CHANNELS) {
                Map<String, Object> opq = c.opaque.get(name);
                if (opq != null && opq.containsKey(ch)) {
                    b.put(ch, opq.get(ch));
                    continue;
                }
                Map<String, List<KeyframeDecimator.Sample>> src =
                        "rotation".equals(ch) ? c.bones
                        : "position".equals(ch) ? c.positions : c.scales;
                Object enc = AnimSpec.encodeChannel(src.get(name), ch, c.decimals, c.timeDecimals);
                if (enc != null) b.put(ch, enc);
            }
            Map<String, Object> opq = c.opaque.get(name);
            if (opq != null) {
                for (Map.Entry<String, Object> e : opq.entrySet()) {
                    boolean known = false;
                    for (String ch : AnimSpec.CHANNELS) known |= ch.equals(e.getKey());
                    if (!known) b.put(e.getKey(), e.getValue());
                }
            }
            if (!b.isEmpty()) bones.put(name, b);
        }
        if (!bones.isEmpty()) anim.put("bones", bones);

        // 被编辑的动画必须留在第一个：read() 取第一个动画，顺序一变就会「越编辑越改错对象」
        Map<String, Object> anims = new LinkedHashMap<String, Object>();
        anims.put(c.name, anim);
        anims.putAll(c.otherAnimations);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format_version", c.formatVersion == null ? AnimSpec.FORMAT_VERSION : c.formatVersion);
        root.put("animations", anims);
        root.putAll(c.rootExtras);
        AnimationWriter.writeFile(path, MiniJson.write(root, true));
    }

    /**
     * 可视化拖拽一根骨：在 t 时刻把它的方向改成「当前方向 + 屏幕位移」，
     * 自动写/改一个关键帧（数值编辑由它取代）。
     *
     * @param dx,dy 模型坐标下的位移（x 右、y 上，单位＝原版人模像素）
     * @return 写入的 JSON 欧拉（度），失败返回 null
     */
    public static double[] drag(Clip c, String bone, double t, double dx, double dy) {
        if (c == null || bone == null || bone.isEmpty()) return null;
        boolean up = "head".equals(bone);
        List<KeyframeDecimator.Sample> list = c.bones.get(bone);
        double[] json = list == null ? null : valueAt(list, t);
        if (json == null) json = new double[]{0, 0, 0};
        double ix = -json[0], iy = -json[1], iz = json[2];
        Vec3 rest = new Vec3(0, up ? 1 : -1, 0);
        Vec3 cur = EulerBedrock.apply(EulerBedrock.toMatrix(ix, iy, iz), rest);
        Vec3 target = Vec3.norm(new Vec3(cur.x + dx, cur.y + dy, cur.z));
        if (target.len() < 1e-6) return null;
        double[] e = EulerFit.solveMask(
                new EulerFit.Problem(new Vec3[]{rest}, new Vec3[]{target}),
                new double[]{ix, iy, iz}, new boolean[]{true, false, true});
        double[] out = {-e[0], -e[1], e[2]};
        setKey(c, bone, t, out);
        return out;
    }

    /** 在 t 处写一个关键帧（已有就替换，保留原来的插值标记），保持时间排序与时长。 */
    public static void setKey(Clip c, String bone, double t, double[] euler) {
        List<KeyframeDecimator.Sample> l = c.bones.get(bone);
        if (l == null) {
            l = new ArrayList<KeyframeDecimator.Sample>();
            c.bones.put(bone, l);
        }
        double tt = Math.round(t * 1000.0) / 1000.0;
        for (int i = 0; i < l.size(); i++) {
            if (Math.abs(l.get(i).t - tt) < 1e-4) {
                KeyframeDecimator.Sample old = l.get(i);
                l.set(i, new KeyframeDecimator.Sample(tt, euler.clone(), old.pre, old.lerp));
                return;
            }
        }
        l.add(new KeyframeDecimator.Sample(tt, euler.clone()));
        l.sort((a, b) -> Double.compare(a.t, b.t));
        if (tt > c.length) c.length = tt;
    }

    /** 某时刻的插值值（JSON 空间线性；catmullrom 只影响运行/BB 播放，预览按线性近似）。 */
    static double[] valueAt(List<KeyframeDecimator.Sample> l, double t) {
        if (l == null || l.isEmpty()) return null;
        if (t <= l.get(0).t) return l.get(0).e.clone();
        if (t >= l.get(l.size() - 1).t) return l.get(l.size() - 1).e.clone();
        for (int i = 0; i < l.size() - 1; i++) {
            KeyframeDecimator.Sample a = l.get(i), b = l.get(i + 1);
            if (Math.abs(a.t - t) < 1e-9) return a.e.clone();
            if (a.t < t && t < b.t) {
                double k = (t - a.t) / (b.t - a.t);
                return new double[]{
                        a.e[0] + (b.e[0] - a.e[0]) * k,
                        a.e[1] + (b.e[1] - a.e[1]) * k,
                        a.e[2] + (b.e[2] - a.e[2]) * k};
            }
        }
        return l.get(l.size() - 1).e.clone();
    }

    /** 时间键严格递增（同一时刻只留最后一个），保证导出后符合 Bedrock 契约。 */
    static List<KeyframeDecimator.Sample> dedupe(List<KeyframeDecimator.Sample> in) {
        List<KeyframeDecimator.Sample> out = new ArrayList<KeyframeDecimator.Sample>();
        for (KeyframeDecimator.Sample s : in) {
            if (!out.isEmpty() && Math.abs(out.get(out.size() - 1).t - s.t) < 1e-6) {
                out.set(out.size() - 1, s);
            } else {
                out.add(s);
            }
        }
        return out;
    }
}
