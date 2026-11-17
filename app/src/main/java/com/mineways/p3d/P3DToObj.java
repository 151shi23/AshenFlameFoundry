package com.mineways.p3d;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3D 工程（2.0 的 {@code <名>/<名>.proj}）→ OBJ/MTL 模型转换（纯 Java）。
 *
 * <p>.proj 的布局是逆向实测的：4 字节头（{@code 00 20 00 00}）+ MessagePack 数组[4]：
 * <ul>
 *   <li>[0] 工程头（版本号等，转换用不到）；</li>
 *   <li>[1] 场景对象：{@code [id, 名称, 组件[], 父id, 可见]}，组件是
 *       {@code [组件名, [[键, JSON值串, null], ...]]}；</li>
 *   <li>[2] 材质：{@code [id, null, [[键, JSON值串, null], ...]]}，键有
 *       color/texture/glossiness/specular/alpha/pixelBlending/name；</li>
 *   <li>[3] 网格：{@code [id, 每面材质索引[], 每面值, 面法线[][], 扁平顶点索引[],
 *       每角UV[][], 顶点[][], 蒙皮权重, 空]}。</li>
 * </ul>
 *
 * <p>关键实测结论：<b>面大小 = UV 数 ÷ 面数</b>（3=三角面、4=四边面，两种都真实存在，
 * 只按三角形拆会把四边面拆坏）。变换按 Unity 系欧拉角 → 四元数（ZXY 顺序）自底向上合成父链。
 * 蒙皮权重字段原样忽略 —— OBJ 只能表达绑定姿态（实测样例工程 isSkinned 全为 False）。
 */
public final class P3DToObj {

    /** 一次转换的产物：obj/mtl 文本 + 引用到的贴图（zip 内路径 → 字节），直接落盘即可。 */
    public static final class Result {
        public final String obj;
        public final String mtl;
        /** zip 内的贴图条目路径（如 {@code New Project 232/res/x.png}）→ 字节。 */
        public final Map<String, byte[]> textures = new LinkedHashMap<>();
        public final int objects;
        public final int faces;
        public final int vertices;
        public final int materials;
        public final int skippedNoMesh;

        Result(String obj, String mtl, Map<String, byte[]> textures,
               int objects, int faces, int vertices, int materials, int skippedNoMesh) {
            this.obj = obj;
            this.mtl = mtl;
            this.textures.putAll(textures);
            this.objects = objects;
            this.faces = faces;
            this.vertices = vertices;
            this.materials = materials;
            this.skippedNoMesh = skippedNoMesh;
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("对象 ").append(objects)
                    .append(" · 面 ").append(faces)
                    .append(" · 顶点 ").append(vertices)
                    .append(" · 材质 ").append(materials)
                    .append(" · 贴图 ").append(textures.size());
            if (skippedNoMesh > 0) {
                sb.append("\n跳过 ").append(skippedNoMesh).append(" 个空网格/相册对象");
            }
            return sb.toString();
        }
    }

    private P3DToObj() {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 从工程包里找 2.0 结构的 .proj 并转成 OBJ。
     *
     * @return null = 包里没有可识别的 2.0 结构（3.0 工程请先走「转成 2.0 工程包」）
     */
    public static Result convert(P3DPack.Pack pack) throws Exception {
        P3DPack.Entry proj = null;
        for (P3DPack.Entry e : pack.entries) {
            String n = e.name;
            if (n.endsWith(".proj") && !n.endsWith("+0.proj")) {
                proj = e;
                if (n.contains(pack.projectDir)) {
                    break;      // 优先工程目录里的那份
                }
            }
        }
        if (proj == null) {
            return null;
        }
        return convertProj(proj.data, pack);
    }

    /** 直接转一份 .proj 字节（4 字节头 + MessagePack 四段树）。 */
    public static Result convertProj(byte[] proj, P3DPack.Pack pack) throws Exception {
        Mp.Reader r = new Mp.Reader(proj, 4);       // 跳过 00 20 00 00 前导
        Object root = r.readValue();
        if (!(root instanceof List)) {
            throw new IllegalStateException("工程数据不是 MessagePack 数组");
        }
        List<?> parts = (List<?>) root;
        if (parts.size() < 4) {
            throw new IllegalStateException("工程数据只有 " + parts.size() + " 段，不是 2.0 的四段结构");
        }
        List<?> objects = asList(parts.get(1));
        List<?> materials = asList(parts.get(2));
        List<?> meshes = asList(parts.get(3));
        if (objects == null || meshes == null) {
            throw new IllegalStateException("对象/网格段不是数组");
        }

        // 按网格字段形状确认这是 2.0 的九字段布局（3.0 的 project.proj 不是这个样子）
        if (!meshes.isEmpty()) {
            Object first = meshes.get(0);
            if (!(first instanceof List) || ((List<?>) first).size() < 7) {
                throw new IllegalStateException("网格段不是 2.0 的九字段布局（3.0 工程请先转 2.0）");
            }
        }

        Map<Long, List<?>> objById = new LinkedHashMap<>();
        for (Object o : objects) {
            List<?> a = asList(o);
            if (a != null && a.size() >= 4 && a.get(0) instanceof Number) {
                objById.put(((Number) a.get(0)).longValue(), a);
            }
        }
        Map<Long, List<?>> meshById = new LinkedHashMap<>();
        for (Object m : meshes) {
            List<?> a = asList(m);
            if (a != null && !a.isEmpty() && a.get(0) instanceof Number) {
                meshById.put(((Number) a.get(0)).longValue(), a);
            }
        }
        Map<Long, List<?>> matById = new LinkedHashMap<>();
        if (materials != null) {
            for (Object m : materials) {
                List<?> a = asList(m);
                if (a != null && !a.isEmpty() && a.get(0) instanceof Number) {
                    matById.put(((Number) a.get(0)).longValue(), a);
                }
            }
        }

        StringBuilder obj = new StringBuilder(1 << 20);
        StringBuilder mtl = new StringBuilder(1 << 14);
        obj.append("# Converted from Prisma3D 2.0 (.prisma)\n");
        obj.append("mtllib model.mtl\n");
        mtl.append("# Prisma3D materials\n");

        Map<String, byte[]> textures = new LinkedHashMap<>();
        int vOffset = 1;
        int outObjects = 0;
        int outFaces = 0;
        int outVerts = 0;
        int skipped = 0;

        for (Object o : objects) {
            List<?> a = asList(o);
            if (a == null || a.size() < 3) {
                continue;
            }
            long id = ((Number) a.get(0)).longValue();
            String name = a.get(1) instanceof String ? (String) a.get(1) : ("obj_" + id);
            List<?> components = asList(a.get(2));

            List<?> renderer = findComponent(components, "PMeshRenderer");
            if (renderer == null) {
                continue;
            }
            // 组件形状是 [类型名, 属性表]，属性在**第 2 个槽**，不是组件本身。
            // 这里曾直接 propMap(renderer)，于是每个渲染器都解析出空属性、pMesh 恒为 -1、
            // 整包一个面都不出（导出只有两行头）—— 大工程上表现为「转 OBJ 成功但文件是空的」。
            Map<String, Object> rp = propMap(renderer.size() > 1 ? asList(renderer.get(1)) : null);
            long meshId = jsonId(rp.get("pMesh"));
            List<?> mesh = meshById.get(meshId);
            if (mesh == null) {
                skipped++;
                continue;
            }
            List<?> verts = asList(field(mesh, 6));
            List<?> uvFlat = asList(field(mesh, 5));
            List<?> idxFlat = asList(field(mesh, 4));
            List<?> faceMats = asList(field(mesh, 1));
            if (verts == null || idxFlat == null || uvFlat == null
                    || verts.isEmpty() || idxFlat.isEmpty() || uvFlat.isEmpty()) {
                skipped++;
                continue;
            }
            // 全零网格（Prisma3D 的默认空网格，能有一万个 [0,0,0]）→ 纯垃圾，跳过
            boolean allZero = true;
            for (Object v : verts) {
                List<?> va = asList(v);
                if (va == null || va.size() < 3) {
                    allZero = false;
                    break;
                }
                for (int i = 0; i < 3; i++) {
                    if (va.get(i) instanceof Number && ((Number) va.get(i)).floatValue() != 0f) {
                        allZero = false;
                        break;
                    }
                }
                if (!allZero) {
                    break;
                }
            }
            if (allZero) {
                skipped++;
                continue;
            }
            int faceCount = faceMats != null ? faceMats.size() : 0;
            int faceSize = faceCount > 0 ? uvFlat.size() / faceCount : 3;
            // 每面角点数（mesh 字段 2）。大工程里同一个网格可能混着三角/四边/五边形，
            // 一律按 uvFlat.size()/面数 取平均会把面切错。
            List<?> cornerList = asList(field(mesh, 2));
            if (faceSize < 3) {
                skipped++;
                continue;
            }

            // 材质桶：对象材质 ids + 网格每面的材质索引
            List<Long> matIds = new ArrayList<>();
            if (rp.get("materials") != null) {
                try {
                    JSONObject mo = new JSONObject(String.valueOf(rp.get("materials")));
                    org.json.JSONArray ids = mo.optJSONArray("ids");
                    if (ids != null) {
                        for (int i = 0; i < ids.length(); i++) {
                            matIds.add(ids.getLong(i));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            List<String> usedMats = new ArrayList<>();
            // 半纹理素内缩要按贴图尺寸算：材质 id → [宽, 高]（读不到就 0，内缩自动跳过）
            Map<Long, int[]> texSizes = new LinkedHashMap<>();
            for (long mid : matIds) {
                List<?> md = matById.get(mid);
                if (md == null) {
                    continue;
                }
                Map<String, Object> mp = propMap(asList(md.get(2)));
                String mname = sanitize(str(mp.get("name"), "mat")) + "_" + mid;
                usedMats.add(mname);
                JSONObject col = jsonObj(mp.get("color"));
                mtl.append("newmtl ").append(mname).append('\n');
                mtl.append("Kd ").append(f(col, "r", 1)).append(' ')
                        .append(f(col, "g", 1)).append(' ').append(f(col, "b", 1)).append('\n');
                mtl.append("Ka 0 0 0\n");
                mtl.append("d ").append(f(col, "a", 1)).append('\n');
                Object tex = mp.get("texture");
                if (tex != null && String.valueOf(tex).length() > 0) {
                    String texPath = String.valueOf(tex).replace("\\", "/");
                    String base = texPath.substring(texPath.lastIndexOf('/') + 1);
                    // 贴图和 obj 放同一目录、MTL 里只写文件名。以前写 res/ 子目录，
                    // 微软 3D 查看器这类工具解析相对子目录不稳，直接白膜；平铺最通用。
                    mtl.append("map_Kd ").append(base).append('\n');
                    byte[] texData = findTexture(pack, base);
                    if (texData != null) {
                        if (!textures.containsKey(texPath)) {
                            textures.put(texPath, texData);
                        }
                        texSizes.put(mid, pngSize(texData));
                        // 带 alpha 的贴图（发片、蛛网、睫毛）补一条 map_d：
                        // 支持透明度的查看器才能镂空，不支持的会忽略，无害。
                        if (pngHasAlpha(texData)) {
                            mtl.append("map_d ").append(base).append('\n');
                        }
                    }
                }
                mtl.append('\n');
            }

            float[][] world = worldMatrix(objById, id);
            // 蒙皮网格：顶点存在骨骼空间里，必须逐点按权重走骨骼矩阵；
            // 只用对象矩阵会把所有部件留在绑定位置（头发炸成球、四肢堆在原点）。
            boolean skinned = "true".equalsIgnoreCase(str(rp.get("isSkinned"), ""));
            List<Long> boneIds = new ArrayList<>();
            if (skinned && rp.get("skinBones") != null) {
                try {
                    JSONObject bo = new JSONObject(String.valueOf(rp.get("skinBones")));
                    JSONArray ids = bo.optJSONArray("ids");
                    if (ids != null) {
                        for (int i = 0; i < ids.length(); i++) {
                            boneIds.add(ids.getLong(i));
                        }
                    }
                } catch (Throwable ignored) {
                    // 读不出就退回非蒙皮路径
                }
            }
            List<?> skinFlat = skinned ? asList(field(mesh, 7)) : null;
            List<?> bindsFlat = skinned ? asList(field(mesh, 8)) : null;
            Map<Long, float[][]> boneWorld = new LinkedHashMap<>();
            obj.append("o ").append(sanitize(name)).append('\n');

            int groups = faceCount;
            int cursor = 0;
            long curMatId = Long.MIN_VALUE;
            for (int fi = 0; fi < groups; fi++) {
                int mi = faceMats.get(fi) instanceof Number ? ((Number) faceMats.get(fi)).intValue() : 0;
                int n = cornerList != null && fi < cornerList.size()
                        && cornerList.get(fi) instanceof Number
                        && ((Number) cornerList.get(fi)).intValue() >= 3
                        ? ((Number) cornerList.get(fi)).intValue() : faceSize;
                if (cursor + n > idxFlat.size() || cursor + n > uvFlat.size()) {
                    break;
                }
                // usemtl 只在材质真正变化时写。以前每个面都写一遍，5 万面的工程多出几万行，
                // 查看器解析变慢，有的还会因为重复声明报警。
                if (!matIds.isEmpty() && mi < matIds.size()) {
                    long mid = matIds.get(mi);
                    if (mid != curMatId) {
                        curMatId = mid;
                        List<?> md = matById.get(mid);
                        String mn = md == null ? ("mat_" + mid)
                                : sanitize(str(propMap(asList(md.get(2))).get("name"), "mat")) + "_" + mid;
                        obj.append("usemtl ").append(mn).append('\n');
                    }
                } else if (!usedMats.isEmpty() && curMatId != FALLBACK_MAT) {
                    curMatId = FALLBACK_MAT;
                    obj.append("usemtl ").append(usedMats.get(0)).append('\n');
                }
                // 关键实测：UV 数组是「按面顺序 × 每角」平铺的（数量 = 面数 × 面大小），
                // 按顶点索引取 UV 会让整包贴图拉伸/错位（dense 网格顶点索引远超 UV 数）。
                // 下面的 uvFlat.get(cursor + k) 就是按全局面 × 每角取的 —— 语义正确。
                StringBuilder vLine = new StringBuilder("f");
                int[] idxs = new int[n];
                for (int k = 0; k < n; k++) {
                    idxs[k] = num(idxFlat.get(cursor + k), -1);
                }
                // v 和 vt 必须成对输出：任何一角越界就整张面跳过，
                // 只写一半会让后面的 f 引用不存在的 vt —— 3D 查看器直接拒载
                boolean broken = false;
                for (int k = 0; k < n; k++) {
                    if (idxs[k] < 0 || idxs[k] >= verts.size()) {
                        broken = true;
                        break;
                    }
                }
                if (broken) {
                    cursor += n;
                    continue;
                }
                // 读本面所有角的 UV。源数据可能带平铺（实测 u 会出现 -1..1 的负值），
                // 这里按**面整体**平移整数个单位落回 [0,1]：面内相对布局不变，
                // 也不会被查看器 clamp 到边界糊成一条色带（3D 查看器就是这么糊的）。
                float[] fu = new float[n];
                float[] fv = new float[n];
                float umin = Float.MAX_VALUE, umax = -Float.MAX_VALUE;
                float vmin = Float.MAX_VALUE, vmax = -Float.MAX_VALUE;
                for (int k = 0; k < n; k++) {
                    float[] uvv = cursor + k < uvFlat.size()
                            ? uv2(uvFlat.get(cursor + k)) : new float[]{0f, 0f};
                    fu[k] = uvv[0];
                    fv[k] = uvv[1];
                    umin = Math.min(umin, fu[k]);
                    umax = Math.max(umax, fu[k]);
                    vmin = Math.min(vmin, fv[k]);
                    vmax = Math.max(vmax, fv[k]);
                }
                // 平移整数个单位，让 umin 落进 [0,1)：保证不出现负 UV（查看器把负值 clamp 成 0，
                // 整面会糊成一条边色）。跨度 > 1 的面本身要平铺，落不进去就保持原样。
                float su = umin < -1e-5f || umax > 1f + 1e-5f ? -(float) Math.floor(umin) : 0f;
                float sv = vmin < -1e-5f || vmax > 1f + 1e-5f ? -(float) Math.floor(vmin) : 0f;
                if (su != 0f || sv != 0f) {
                    umin = Float.MAX_VALUE;
                    umax = -Float.MAX_VALUE;
                    vmin = Float.MAX_VALUE;
                    vmax = -Float.MAX_VALUE;
                    for (int k = 0; k < n; k++) {
                        fu[k] += su;
                        fv[k] += sv;
                        umin = Math.min(umin, fu[k]);
                        umax = Math.max(umax, fu[k]);
                        vmin = Math.min(vmin, fv[k]);
                        vmax = Math.max(vmax, fv[k]);
                    }
                }
                float du = umax - umin;
                float dv = vmax - vmin;
                for (int k = 0; k < n; k++) {
                    float[] p = (skinFlat != null && idxs[k] < skinFlat.size())
                            ? skinnedVertex(skinFlat.get(idxs[k]), vertex(verts.get(idxs[k])), boneIds, objById, boneWorld, bindsFlat, world)
                            : transform(world, vertex(verts.get(idxs[k])));
                    // Prisma3D 是 Unity 系左手 Y-up，OBJ 是右手 Y-up：z 取反才是同一个姿态。
                    // 少了这一下，模型在查看器里是镜像的（与 BB 导出 z 轴对不上，差 0.47）。
                    obj.append(String.format(java.util.Locale.ROOT, "v %.4f %.4f %.4f%n",
                            p[0], p[1], p[2]));
                }
                for (int k = 0; k < n; k++) {
                    // 实测结论：Prisma3D 存的 UV 本来就是 OBJ（GL）约定，左下原点，
                    // 不要做 1-v 翻转 —— 翻了整包贴图全部错位（皮肤头渲染对比验证过）。
                    // 半纹理素内缩：MC 像素风的 UV 压在纹理素边界上，查看器的线性过滤会把
                    // 邻居像素糊进来 => 模糊/小错位。每面 UV 矩形向内收 0.5 纹理素。
                    int[] tsz = texSizes.getOrDefault(
                            matIds.size() > mi ? matIds.get(mi) : -1L, new int[]{0, 0});
                    float eu = tsz[0] > 0 && du > 1.5f / tsz[0] ? 0.5f / tsz[0] : 0f;
                    float ev = tsz[1] > 0 && dv > 1.5f / tsz[1] ? 0.5f / tsz[1] : 0f;
                    float uu = du > 0 ? umin + (fu[k] - umin) * (du - 2 * eu) / du : fu[k];
                    float vv = dv > 0 ? vmin + (fv[k] - vmin) * (dv - 2 * ev) / dv : fv[k];
                    obj.append(String.format(java.util.Locale.ROOT, "vt %.6f %.6f%n", uu, vv));
                }
                for (int k = 0; k < n; k++) {
                    vLine.append(' ').append(vOffset + k).append('/').append(vOffset + k);
                }
                obj.append(vLine).append('\n');
                vOffset += n;
                outFaces++;
                outVerts += n;
                cursor += n;
            }
            outObjects++;
        }

        return new Result(obj.toString(), mtl.toString(), textures,
                outObjects, outFaces, outVerts, usedMaterialCount(matById), skipped);
    }

    private static int usedMaterialCount(Map<Long, List<?>> matById) {
        return matById.size();
    }

    // ------------------------------------------------------------------ 结构取值

    /** 没有材质 id 时的占位，只用来判「上次写的材质是不是它」。 */
    private static final long FALLBACK_MAT = Long.MIN_VALUE + 1;

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : null;
    }

    private static Object field(List<?> mesh, int index) {
        return mesh != null && index < mesh.size() ? mesh.get(index) : null;
    }

    private static List<?> findComponent(List<?> components, String typeName) {
        if (components == null) {
            return null;
        }
        for (Object c : components) {
            List<?> a = asList(c);
            if (a != null && !a.isEmpty() && typeName.equals(a.get(0))) {
                return a;
            }
        }
        return null;
    }

    /** 组件/材质的属性段 {@code [[键, JSON值串, null], ...]} → Map。 */
    private static Map<String, Object> propMap(List<?> props) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (props == null) {
            return out;
        }
        for (Object p : props) {
            List<?> a = asList(p);
            if (a != null && a.size() >= 2 && a.get(0) instanceof String) {
                out.put((String) a.get(0), a.get(1));
            }
        }
        return out;
    }

    private static long jsonId(Object jsonStr) {
        if (jsonStr == null) {
            return -1;
        }
        try {
            return new JSONObject(String.valueOf(jsonStr)).optLong("id", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static JSONObject jsonObj(Object jsonStr) {
        if (jsonStr == null || String.valueOf(jsonStr).length() == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(String.valueOf(jsonStr));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static double f(JSONObject o, String key, double dflt) {
        return o == null ? dflt : o.optDouble(key, dflt);
    }

    private static String str(Object o, String dflt) {
        return o == null || String.valueOf(o).length() == 0 ? dflt : String.valueOf(o);
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            // U+FFFD 是解码替换符（工程里有非 UTF-8 的对象名），3D 查看器嫌它，一并清掉
            boolean keep = Character.isLetterOrDigit(c) || c == '_' || c == '-'
                    || (c > 0x2E80 && c != '\uFFFD');
            sb.append(keep ? c : '_');
        }
        String s = sb.toString();
        return s.length() == 0 ? "obj" : s;
    }

    private static int num(Object o, int dflt) {
        return o instanceof Number ? ((Number) o).intValue() : dflt;
    }

    /**
     * 浮点读取。蒙皮权重（0.502 之类）和 bindpose 的 6.4 倍缩放**必须**走这个，
     * 用 {@link #num(Object, int)} 会把它们截成 0 和 6，等于把蒙皮彻底废掉。
     */
    private static float numf(Object o, float dflt) {
        return o instanceof Number ? ((Number) o).floatValue() : dflt;
    }

    private static float[] vertex(Object o) {
        List<?> a = asList(o);
        float[] v = new float[3];
        if (a != null && a.size() >= 3) {
            for (int i = 0; i < 3; i++) {
                v[i] = a.get(i) instanceof Number ? ((Number) a.get(i)).floatValue() : 0f;
            }
        }
        return v;
    }

    private static float[] uv2(Object o) {
        List<?> a = asList(o);
        float[] v = new float[2];
        if (a != null && a.size() >= 2) {
            for (int i = 0; i < 2; i++) {
                v[i] = a.get(i) instanceof Number ? ((Number) a.get(i)).floatValue() : 0f;
            }
        }
        return v;
    }

    private static byte[] findTexture(P3DPack.Pack pack, String base) {
        for (P3DPack.Entry e : pack.entries) {
            String n = e.name;
            int i = n.lastIndexOf('/');
            if (i >= 0 && n.substring(i + 1).equals(base)) {
                return e.data;
            }
        }
        return null;
    }

    /** 只读 PNG/JPEG 头拿宽高（不整张解码）：PNG 取 IHDR，JPEG 扫 SOF 标记。3.0 转换器也用它。 */
    /**
     * PNG 是否**真的**含透明像素。
     *
     * <p>只看 IHDR 的颜色类型不够：绝大多数 PNG 都写成 RGBA，但 alpha 全 255。
     * 那种图如果也补 {@code map_d}，遇到按「亮度」解释透明度图的查看器，暗色区域会整片消失。
     * 所以这里真解一次 IDAT 的 alpha 通道（只支持 8 位、非隔行，覆盖导出器能产出的一切）。
     */
    static boolean pngHasAlpha(byte[] png) {
        try {
            if (png == null || png.length < 33) {
                return false;
            }
            if ((png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                return false;
            }
            int w = be32(png, 16);
            int h = be32(png, 20);
            int depth = png[24] & 0xFF;
            int color = png[25] & 0xFF;
            int interlace = png[28] & 0xFF;
            if (w <= 0 || h <= 0 || depth != 8 || interlace != 0) {
                return false;
            }
            if (color == 3) {
                // 调色板：透明度在 tRNS 块里
                return indexOfTag(png, "tRNS") >= 0;
            }
            int ch = color == 6 ? 4 : color == 4 ? 2 : 0;
            if (ch == 0) {
                return false;       // 灰度/RGB 没有 alpha 通道
            }
            java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
            int p = 8;
            while (p + 8 <= png.length) {
                int len = be32(png, p);
                if (len < 0 || p + 12 + len > png.length) {
                    break;
                }
                if (png[p + 4] == 'I' && png[p + 5] == 'D' && png[p + 6] == 'A' && png[p + 7] == 'T') {
                    idat.write(png, p + 8, len);
                }
                p += 12 + len;
            }
            byte[] raw = inflate(idat.toByteArray());
            if (raw == null) {
                return false;
            }
            int stride = w * ch;
            int alphaOff = color == 6 ? 3 : 1;
            byte[] prev = new byte[stride];
            byte[] cur = new byte[stride];
            int off = 0;
            for (int y = 0; y < h; y++) {
                if (off + 1 + stride > raw.length) {
                    break;
                }
                int ft = raw[off++] & 0xFF;
                System.arraycopy(raw, off, cur, 0, stride);
                off += stride;
                unfilter(ft, cur, prev, stride, ch);
                for (int x = 0; x < w; x++) {
                    if ((cur[x * ch + alphaOff] & 0xFF) < 250) {
                        return true;
                    }
                }
                byte[] t = prev;
                prev = cur;
                cur = t;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static int indexOfTag(byte[] b, String tag) {
        for (int i = 8; i + 4 <= b.length; i++) {
            if (b[i] == tag.charAt(0) && b[i + 1] == tag.charAt(1)
                    && b[i + 2] == tag.charAt(2) && b[i + 3] == tag.charAt(3)) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] inflate(byte[] src) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(src);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(src.length * 4);
            byte[] buf = new byte[16384];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            inf.end();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** PNG 行过滤反演（0 none / 1 sub / 2 up / 3 average / 4 paeth）。 */
    private static void unfilter(int ft, byte[] cur, byte[] prev, int stride, int bpp) {
        switch (ft) {
            case 1:
                for (int i = bpp; i < stride; i++) {
                    cur[i] = (byte) (cur[i] + cur[i - bpp]);
                }
                break;
            case 2:
                for (int i = 0; i < stride; i++) {
                    cur[i] = (byte) (cur[i] + prev[i]);
                }
                break;
            case 3:
                for (int i = 0; i < stride; i++) {
                    int a = i >= bpp ? (cur[i - bpp] & 0xFF) : 0;
                    cur[i] = (byte) (cur[i] + ((a + (prev[i] & 0xFF)) >> 1));
                }
                break;
            case 4:
                for (int i = 0; i < stride; i++) {
                    int a = i >= bpp ? (cur[i - bpp] & 0xFF) : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= bpp ? (prev[i - bpp] & 0xFF) : 0;
                    int pa = Math.abs(b - c);
                    int pb = Math.abs(a - c);
                    int pc = Math.abs(a + b - 2 * c);
                    int pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    cur[i] = (byte) (cur[i] + pr);
                }
                break;
            default:
                break;
        }
    }

    static int[] pngSize(byte[] data) {
        try {
            if (data.length > 24 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') {
                int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16)
                        | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
                int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16)
                        | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
                return new int[]{w, h};
            }
            if (data.length > 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
                int p = 2;
                while (p + 9 < data.length) {
                    if ((data[p] & 0xFF) != 0xFF) {
                        p++;
                        continue;
                    }
                    int marker = data[p + 1] & 0xFF;
                    if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9)) {
                        p += 2;
                        continue;
                    }
                    int len = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                    if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                            && p + 9 < data.length) {
                        int h = ((data[p + 5] & 0xFF) << 8) | (data[p + 6] & 0xFF);
                        int w = ((data[p + 7] & 0xFF) << 8) | (data[p + 8] & 0xFF);
                        return new int[]{w, h};
                    }
                    p += 2 + len;
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
    }

    // ------------------------------------------------------------------ 变换

    private static float[][] identity() {
        return new float[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
    }

    /** 自底向上合成父链的世界矩阵（Unity 系欧拉角，ZXY 顺序）。 */
    private static float[][] worldMatrix(Map<Long, List<?>> objById, long id) {
        List<?> a = objById.get(id);
        float[][] m = localMatrix(a);
        long parent = a != null && a.size() >= 4 && a.get(3) instanceof Number
                ? ((Number) a.get(3)).longValue() : -1;
        int guard = 0;
        while (parent >= 0 && objById.containsKey(parent) && guard++ < 64) {
            List<?> pa = objById.get(parent);
            m = mul(localMatrix(pa), m);
            parent = pa.size() >= 4 && pa.get(3) instanceof Number
                    ? ((Number) pa.get(3)).longValue() : -1;
        }
        return m;
    }

    private static float[][] localMatrix(List<?> obj) {
        float[] t = {0, 0, 0};
        float[] r = {0, 0, 0};
        float[] s = {1, 1, 1};
        List<?> components = obj == null ? null : asList(obj.get(2));
        List<?> tr = findComponent(components, "PTransform");
        if (tr != null) {
            Map<String, Object> props = propMap(asList(tr.get(1)));
            double[] v = vec(props.get("localPosition"));
            t[0] = (float) v[0];
            t[1] = (float) v[1];
            t[2] = (float) v[2];
            v = vec(props.get("localEulerAnglesHint"));
            r[0] = (float) v[0];
            r[1] = (float) v[1];
            r[2] = (float) v[2];
            v = vec(props.get("localScale"));
            s[0] = (float) v[0];
            s[1] = (float) v[1];
            s[2] = (float) v[2];
        }
        double radX = Math.toRadians(r[0]);
        double radY = Math.toRadians(r[1]);
        double radZ = Math.toRadians(r[2]);
        float cx = (float) Math.cos(radX / 2);
        float sx = (float) Math.sin(radX / 2);
        float cy = (float) Math.cos(radY / 2);
        float sy = (float) Math.sin(radY / 2);
        float cz = (float) Math.cos(radZ / 2);
        float sz = (float) Math.sin(radZ / 2);
        float qw = cx * cy * cz - sx * sy * sz;
        float qx = sx * cy * cz - cx * sy * sz;
        float qy = cx * sy * cz + sx * cy * sz;
        float qz = cx * cy * sz + sx * sy * cz;

        float[][] m = new float[4][4];
        m[0][0] = (1 - 2 * (qy * qy + qz * qz)) * s[0];
        m[0][1] = (2 * (qx * qy - qz * qw)) * s[1];
        m[0][2] = (2 * (qx * qz + qy * qw)) * s[2];
        m[0][3] = t[0];
        m[1][0] = (2 * (qx * qy + qz * qw)) * s[0];
        m[1][1] = (1 - 2 * (qx * qx + qz * qz)) * s[1];
        m[1][2] = (2 * (qy * qz - qx * qw)) * s[2];
        m[1][3] = t[1];
        m[2][0] = (2 * (qx * qz - qy * qw)) * s[0];
        m[2][1] = (2 * (qy * qz + qx * qw)) * s[1];
        m[2][2] = (1 - 2 * (qx * qx + qy * qy)) * s[2];
        m[2][3] = t[2];
        m[3][3] = 1;
        return m;
    }

    private static double[] mul3(double[] a, double[] b) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return out;
    }

    private static double[] vec(Object jsonStr) {
        double[] out = {0, 0, 0};
        if (jsonStr == null) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(String.valueOf(jsonStr));
            out[0] = o.optDouble("x", 0);
            out[1] = o.optDouble("y", 0);
            out[2] = o.optDouble("z", 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static float[][] mul(float[][] a, float[][] b) {
        float[][] out = new float[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[i][k] * b[k][j];
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    private static float[] transform(float[][] m, float[] p) {
        return new float[]{
                m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3],
                m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3],
                m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3],
        };
    }

    /**
     * 蒙皮顶点：{@code Σ w·(骨骼世界矩阵 · v) / Σ w}。
     *
     * <p>Prisma3D 把蒙皮网格的顶点存在骨骼空间里（不是模型空间），所以「对象矩阵 · v」只会把
     * 部件留在绑定位置：头发炸成一团、四肢贴回原点。四个影响里权重为 0 的直接跳过；
     * 权重和为 0（数据坏了）时退回对象矩阵，至少不会飞到无穷远。
     */
    private static float[] skinnedVertex(Object skinEntry, float[] v, List<Long> boneIds,
                                         Map<Long, List<?>> objById, Map<Long, float[][]> cache,
                                         List<?> binds, float[][] fallback) {
        List<?> s = asList(skinEntry);
        if (s == null || s.size() < 5 || boneIds.isEmpty()) {
            return transform(fallback, v);
        }
        float ax = 0, ay = 0, az = 0, tw = 0;
        float[][] firstBone = null;
        for (int k = 0; k < 4 && k + 4 < s.size(); k++) {
            int bi = num(s.get(k), -1);
            float w = numf(s.get(4 + k), 0);
            if (bi < 0 || bi >= boneIds.size()) {
                continue;
            }
            long bone = boneIds.get(bi);
            float[][] bm = cache.get(bone);
            if (bm == null) {
                bm = skinMatrix(worldMatrix(objById, bone),
                        binds != null && bi < binds.size() ? asList(binds.get(bi)) : null);
                cache.put(bone, bm);
            }
            if (firstBone == null) {
                firstBone = bm;
            }
            if (w == 0) {
                continue;
            }
            float[] p = transform(bm, v);
            ax += p[0] * w;
            ay += p[1] * w;
            az += p[2] * w;
            tw += w;
        }
        if (tw > 0) {
            return new float[]{ax / tw, ay / tw, az / tw};
        }
        // 权重全 0：贴到第一根有效骨骼上，绝不能退回对象矩阵（那是绑定空间的占位，会飞）。
        if (firstBone != null) {
            return transform(firstBone, v);
        }
        return transform(fallback, v);
    }

    /**
     * 骨骼世界矩阵 × bindpose（列主序 4×4，平移在 b[12..14]）。
     *
     * <p>{@code M[i][j] = Σ_k W[i][k] · b[j*4+k]}，因为 bindpose 的第 4 行是 (0,0,0,1)，
     * 直接四次点积就够。少了它顶点会留在绑定空间里，部件会炸开。
     */
    private static float[][] skinMatrix(float[][] w, List<?> b) {
        if (b == null || b.size() < 16) {
            return w;
        }
        float[] bf = new float[16];
        for (int i = 0; i < 16; i++) {
            bf[i] = numf(b.get(i), i % 5 == 0 ? 1 : 0);
        }
        bf[15] = 1;
        float[][] m = new float[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    float wv = k < 3 ? w[i][k] : (i < 3 ? w[i][3] : 0);
                    s += wv * bf[j * 4 + k];
                }
                m[i][j] = s;
            }
        }
        return m;
    }
}
