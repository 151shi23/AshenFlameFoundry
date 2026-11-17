package com.mineways.p3d;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3D 工程（3.0 的 {@code <uuid>/project.proj} + {@code content/*.pobject}）→ OBJ/MTL
 * 模型转换（纯 Java）。语义与 Python 参考实现 to_obj_v3.py 逐条对齐，并用 2.0 已验收
 * 导出做过硬校验：canonical 面交集 19814/19814 = 100%。
 *
 * <p>3.0 的布局是逆向实测的：数据文件 = 4 字节魔数（{@code 14 d3 7a 44}）+ MessagePack。
 * <ul>
 *   <li>{@code project.proj} = 数组[5] = [settings, sceneRoots, materials, clips, duration]；
 *       材质 = {@code [uuid, 名称, props]}，props 是 {@code [键, JSON值串, 类型]} 列表，
 *       texture 没有贴图时是字面量字符串 "None"（不是 null！）；</li>
 *   <li>{@code content/*.pobject}（跳过 {@code +0.pobject} 副本）= 数组[4] =
 *       {@code [ownUuid, objects[], meshes[], materialUuids]}；
 *       对象 = {@code [uuid, 名称, 组件[], parentUuid(str), 可见, "", prefabRef, null]}；</li>
 *   <li>网格 11 字段 = {@code [uuid, 每面材质索引[], 每面角数[], 每角法线[][],
 *       每角顶点索引[], 每角UV[], packedLegacyKeys[], triCornerLocalIdx[], 顶点[][], skin, []]}。</li>
 * </ul>
 *
 * <p>关键实测结论（都交过学费）：
 * <ul>
 *   <li><b>场景根是包装对象</b>：其 uuid == 某 pobject 的 uuid（prefab 实例化），包装对象的
 *       PTransform 与 prefab 内部根完全相同（编辑器残留），绝不能再乘一次 ——
 *       转换从恒等矩阵起步，只走内部层级，否则整包坐标飞散；</li>
 *   <li><b>面材质挂在渲染器上</b>：PMeshRenderer.props.materials = {"ids":[uuid…]}，
 *       面的材质下标 mi → ids[mi] → project.proj 材质；不是 pobject 第 4 槽的 materialUuids；</li>
 *   <li><b>欧拉角语义与 2.0 完全一致</b>（TEST 双版本逐字段对照证实），四元数公式沿用
 *       {@link P3DToObj} 那套 —— 所谓"骨骼欧拉角顺序"是发射循环漏推进 pos 造成的假象；</li>
 *   <li><b>每面角数是变长的</b>（3=三角、4=四边，四边按 [0,1,2],[3,0,2] 拆，&gt;4 扇形拆），
 *       遍历时游标 pos 每张面都必须前进（Python 版曾在这里栽过 —— 全部对象反复发射第一张面，
 *       表象是"人群堆叠成一坨"）；</li>
 *   <li>材质名含空格 → 写进 newmtl/usemtl 前必须清洗，否则 OBJ 按 token 断开、贴图全丢；
 *       UV 的半纹素内缩沿用 pngSize()，v/vt 必须成对，坏面整张跳过。</li>
 * </ul>
 */
public final class P3DToObj3 {

    private P3DToObj3() {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 把 3.0 工程包转成 OBJ。包不是 3.0（没有 project.proj / *.pobject）时返回 null。
     */
    public static P3DToObj.Result convert(P3DPack.Pack pack) throws Exception {
        P3DPack.Entry proj = null;
        for (P3DPack.Entry e : pack.entries) {
            String n = e.name;
            if (n.endsWith("/project.proj") && !n.endsWith("+0.proj")) {
                proj = e;
                break;
            }
        }
        if (proj == null) {
            return null;
        }
        return convertProj(proj.data, pack);
    }

    /** 直接转一份 project.proj 字节（4 字节魔数 + MessagePack 五段树）。 */
    public static P3DToObj.Result convertProj(byte[] proj, P3DPack.Pack pack) throws Exception {
        Object root = new Mp.Reader(proj, 4).readValue();
        if (!(root instanceof List)) {
            throw new IllegalStateException("project.proj 不是 MessagePack 数组");
        }
        List<?> parts = (List<?>) root;
        if (parts.size() < 3) {
            throw new IllegalStateException("project.proj 只有 " + parts.size()
                    + " 段，不是 3.0 的五段结构");
        }
        List<?> sceneRoots = asList(parts.get(1));
        List<?> materials = asList(parts.get(2));
        if (sceneRoots == null) {
            throw new IllegalStateException("sceneRoots 段不是数组");
        }

        // 材质表：uuid(str) → [uuid, 名称, props]
        Map<String, List<?>> matByUuid = new LinkedHashMap<>();
        if (materials != null) {
            for (Object m : materials) {
                List<?> a = asList(m);
                if (a != null && a.size() >= 3 && a.get(0) instanceof String) {
                    matByUuid.put((String) a.get(0), a);
                }
            }
        }

        // prefab 表：pobject 的 ownUuid → 数据（跳过 +0 副本）
        Map<String, List<?>> prefabs = new LinkedHashMap<>();
        for (P3DPack.Entry e : pack.entries) {
            String n = e.name;
            if (n.endsWith(".pobject") && !n.endsWith("+0.pobject")) {
                Object po = new Mp.Reader(e.data, 4).readValue();
                List<?> a = asList(po);
                if (a != null && a.size() >= 3 && a.get(0) instanceof String) {
                    prefabs.put((String) a.get(0), a);
                }
            }
        }

        StringBuilder obj = new StringBuilder(1 << 20);
        StringBuilder mtl = new StringBuilder(1 << 14);
        obj.append("# Converted from Prisma3D 3.0 (.prisma)\n");
        obj.append("mtllib model.mtl\n");
        mtl.append("# Prisma3D materials\n");

        Map<String, byte[]> textures = new LinkedHashMap<>();
        Set<String> usedMtl = new HashSet<>();
        int[] st = new int[6];      // objects, faces, verts, skipped, vOffset, textures
        st[4] = 1;

        // 遍历场景根：包装对象用恒等矩阵起步（注释见类头）
        for (Object r : sceneRoots) {
            List<?> rootObj = asList(r);
            if (rootObj == null || !(rootObj.get(0) instanceof String)) {
                continue;
            }
            List<?> prefab = prefabs.get(rootObj.get(0));
            if (prefab == null) {
                continue;       // 相机等场景直属对象（无网格）
            }
            Map<String, List<?>> meshesByUuid = new LinkedHashMap<>();
            List<?> meshes = asList(prefab.get(2));
            if (meshes != null) {
                for (Object m : meshes) {
                    List<?> a = asList(m);
                    if (a != null && !a.isEmpty() && a.get(0) instanceof String) {
                        meshesByUuid.put((String) a.get(0), a);
                    }
                }
            }
            List<?> objects = asList(prefab.get(1));
            if (objects == null) {
                continue;
            }
            // 先收全 uuid（父对象可能排在子对象后面），再挂 children / 判 roots
            Map<String, List<?>> byUuid = new LinkedHashMap<>();
            for (Object o : objects) {
                List<?> a = asList(o);
                if (a != null && !a.isEmpty() && a.get(0) instanceof String) {
                    byUuid.put((String) a.get(0), a);
                }
            }
            List<List<?>> roots = new ArrayList<>();
            Map<String, List<List<?>>> children = new LinkedHashMap<>();
            for (Object o : objects) {
                List<?> a = asList(o);
                if (a == null || a.isEmpty() || !(a.get(0) instanceof String)) {
                    continue;
                }
                Object parent = a.size() >= 4 ? a.get(3) : null;
                if (parent instanceof String && byUuid.containsKey(parent)
                        && !parent.equals(a.get(0))) {
                    List<List<?>> list = children.get(parent);
                    if (list == null) {
                        list = new ArrayList<>();
                        children.put((String) parent, list);
                    }
                    list.add(a);
                } else {
                    roots.add(a);
                }
            }
            float[][] world = identity();
            for (List<?> r2 : roots) {
                emitTree(r2, world, meshesByUuid, matByUuid, children, byUuid,
                        obj, mtl, textures, usedMtl, pack, st);
            }
        }

        return new P3DToObj.Result(obj.toString(), mtl.toString(), textures,
                st[0], st[1], st[2], matByUuid.size(), st[3]);
    }

    // ------------------------------------------------------------------ 层级遍历

    private static void emitTree(List<?> o, float[][] wm,
                                 Map<String, List<?>> meshesByUuid,
                                 Map<String, List<?>> matByUuid,
                                 Map<String, List<List<?>>> children,
                                 Map<String, List<?>> byUuid,
                                 StringBuilder obj, StringBuilder mtl,
                                 Map<String, byte[]> textures, Set<String> usedMtl,
                                 P3DPack.Pack pack, int[] st) {
        float[][] m = mul(wm, localMatrix(o));
        emitObject(o, m, meshesByUuid, matByUuid, byUuid, obj, mtl, textures, usedMtl, pack, st);
        List<List<?>> kids = children.get(o.get(0));
        if (kids != null) {
            for (List<?> c : kids) {
                emitTree(c, m, meshesByUuid, matByUuid, children, byUuid,
                        obj, mtl, textures, usedMtl, pack, st);
            }
        }
    }

    // ------------------------------------------------------------------ 对象发射

    private static void emitObject(List<?> o, float[][] m,
                                   Map<String, List<?>> meshesByUuid,
                                   Map<String, List<?>> matByUuid,
                                   Map<String, List<?>> byUuid,
                                   StringBuilder obj, StringBuilder mtl,
                                   Map<String, byte[]> textures, Set<String> usedMtl,
                                   P3DPack.Pack pack, int[] st) {
        List<?> comps = asList(o.get(2));
        List<?> rend = findComponent(comps, "PMeshRenderer");
        if (rend == null) {
            return;
        }
        Map<String, Object> rp = propMap(asList(rend.get(1)));
        String meshRef = jsonStr(rp.get("pMesh"), "id");
        List<?> mesh = meshRef == null ? null : meshesByUuid.get(meshRef);
        if (mesh == null) {
            st[3]++;
            return;
        }

        // 面材质索引 → 渲染器 materials.ids[mi] → project.proj 材质（同 2.0 语义）
        List<String> matIds = new ArrayList<>();
        if (rp.get("materials") != null) {
            try {
                JSONArray ids = new JSONObject(String.valueOf(rp.get("materials")))
                        .optJSONArray("ids");
                if (ids != null) {
                    for (int i = 0; i < ids.length(); i++) {
                        matIds.add(ids.optString(i));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        List<?> verts = asList(field(mesh, 8));
        List<?> cornerCounts = asList(field(mesh, 2));
        List<?> uvValues = asList(field(mesh, 5));
        List<?> vidx = asList(field(mesh, 4));
        List<?> faceMats = asList(field(mesh, 1));
        if (verts == null || cornerCounts == null || vidx == null
                || verts.isEmpty() || cornerCounts.isEmpty() || vidx.isEmpty()) {
            st[3]++;
            return;
        }

        // ---- 蒙皮：3.0 的 skin 在 submesh 字段 9，bindpose 在字段 10（每骨骼 16 个数 = 4×4 列主序）
        boolean skinned = "true".equalsIgnoreCase(String.valueOf(rp.get("isSkinned")).trim());
        List<String> boneIds = new ArrayList<>();
        if (skinned && rp.get("skinBones") != null) {
            try {
                JSONArray ids = new JSONObject(String.valueOf(rp.get("skinBones")))
                        .optJSONArray("ids");
                if (ids != null) {
                    for (int i = 0; i < ids.length(); i++) {
                        boneIds.add(ids.optString(i));
                    }
                }
            } catch (Throwable ignored) {
                // 读不出来就退回对象矩阵
            }
        }
        List<?> skinRows = skinned && !boneIds.isEmpty() ? asList(field(mesh, 9)) : null;
        List<?> bindRows = skinRows != null ? asList(field(mesh, 10)) : null;
        Map<String, float[][]> boneCache = new HashMap<>();

        String name = o.get(1) instanceof String && ((String) o.get(1)).length() > 0
                ? (String) o.get(1) : "obj";
        obj.append("o ").append(sanitize(name)).append('\n');
        st[0]++;

        // 当前材质的贴图尺寸（半纹素内缩用），换材质时重置
        int[] curTex = {0, 0};
        int curMat = -1;
        int pos = 0;
        for (int fi = 0; fi < cornerCounts.size(); fi++) {
            int cnum = cornerCounts.get(fi) instanceof Number
                    ? ((Number) cornerCounts.get(fi)).intValue() : 0;
            int mi = faceMats != null && fi < faceMats.size()
                    && faceMats.get(fi) instanceof Number
                    ? ((Number) faceMats.get(fi)).intValue() : 0;
            if (mi != curMat) {
                curMat = mi;
                curTex[0] = 0;
                curTex[1] = 0;
                if (mi >= 0 && mi < matIds.size()) {
                    List<?> md = matByUuid.get(matIds.get(mi));
                    if (md != null) {
                        // OBJ/MTL 名不能含空白（token 解析会断开，贴图全丢）
                        String mname = sanitize(matName(md));
                        obj.append("usemtl ").append(mname).append('\n');
                        if (usedMtl.add(mname)) {
                            curTex = writeMtl(mname, md, pack, textures, mtl);
                        }
                    }
                }
            }
            if (cnum < 3 || pos + cnum > vidx.size() || pos + cnum > uvValues.size()) {
                pos += cnum;        // 游标无论如何都要前进（参考实现曾漏掉这句）
                continue;
            }
            int[] corners = new int[cnum];
            boolean broken = false;
            for (int k = 0; k < cnum; k++) {
                corners[k] = vidx.get(pos + k) instanceof Number
                        ? ((Number) vidx.get(pos + k)).intValue() : -1;
                if (corners[k] < 0 || corners[k] >= verts.size()) {
                    broken = true;
                    break;
                }
            }
            if (broken) {
                pos += cnum;
                continue;
            }
            // 每面 UV 矩形（半纹素内缩的基准）
            float umin = Float.MAX_VALUE, umax = -Float.MAX_VALUE;
            float vmin = Float.MAX_VALUE, vmax = -Float.MAX_VALUE;
            float[] us = new float[cnum];
            float[] vs = new float[cnum];
            for (int k = 0; k < cnum; k++) {
                float[] uvv = uv2(uvValues.get(pos + k));
                us[k] = uvv[0];
                vs[k] = uvv[1];
                umin = Math.min(umin, uvv[0]);
                umax = Math.max(umax, uvv[0]);
                vmin = Math.min(vmin, uvv[1]);
                vmax = Math.max(vmax, uvv[1]);
            }
            // 源 UV 可能带平铺（u 出现 -1..1 的负值）。按面整体平移整数个单位落回 [0,1]：
            // 面内相对布局不变，也不会被查看器 clamp 到边界糊成一条色带。
            // 平移整数个单位，让 umin 落进 [0,1)：保证不出现负 UV。
            float su = umin < -1e-5f || umax > 1f + 1e-5f ? -(float) Math.floor(umin) : 0f;
            float sv = vmin < -1e-5f || vmax > 1f + 1e-5f ? -(float) Math.floor(vmin) : 0f;
            if (su != 0f || sv != 0f) {
                umin = Float.MAX_VALUE;
                umax = -Float.MAX_VALUE;
                vmin = Float.MAX_VALUE;
                vmax = -Float.MAX_VALUE;
                for (int k = 0; k < cnum; k++) {
                    us[k] += su;
                    vs[k] += sv;
                    umin = Math.min(umin, us[k]);
                    umax = Math.max(umax, us[k]);
                    vmin = Math.min(vmin, vs[k]);
                    vmax = Math.max(vmax, vs[k]);
                }
            }
            float du = umax - umin;
            float dv = vmax - vmin;
            float eu = curTex[0] > 0 && du > 1.5f / curTex[0] ? 0.5f / curTex[0] : 0f;
            float ev = curTex[1] > 0 && dv > 1.5f / curTex[1] ? 0.5f / curTex[1] : 0f;
            // v 和 vt 成对输出；四边面拆 [0,1,2],[3,0,2]（同 2.0），>4 扇形拆
            for (int k = 0; k < cnum; k++) {
                float[] vraw = vertex(verts.get(corners[k]));
                float[] p = (skinRows != null && corners[k] < skinRows.size())
                        ? skinnedVertex(skinRows.get(corners[k]), vraw, boneIds, byUuid,
                                boneCache, bindRows, m)
                        : transform(m, vraw);
                // 左手 Y-up → 右手 Y-up：z 取反，与 BB 导出同一姿态。
                obj.append(String.format(java.util.Locale.ROOT, "v %.4f %.4f %.4f%n",
                        p[0], p[1], p[2]));
            }
            for (int k = 0; k < cnum; k++) {
                float uu = du > 0 ? umin + (us[k] - umin) * (du - 2 * eu) / du : us[k];
                float vv = dv > 0 ? vmin + (vs[k] - vmin) * (dv - 2 * ev) / dv : vs[k];
                obj.append(String.format(java.util.Locale.ROOT, "vt %.6f %.6f%n", uu, vv));
            }
            int tris = cnum == 3 ? 1 : cnum == 4 ? 2 : cnum - 2;
            for (int t = 0; t < tris; t++) {
                int a, b, c;
                if (cnum == 3) {
                    a = 0;
                    b = 1;
                    c = 2;
                } else if (cnum == 4) {
                    a = t == 0 ? 0 : 3;
                    b = t == 0 ? 1 : 0;
                    c = 2;
                } else {
                    a = 0;
                    b = t + 1;
                    c = t + 2;
                }
                obj.append("f ").append(st[4] + a).append('/').append(st[4] + a)
                        .append(' ').append(st[4] + b).append('/').append(st[4] + b)
                        .append(' ').append(st[4] + c).append('/').append(st[4] + c)
                        .append('\n');
                st[1]++;
            }
            pos += cnum;            // 关键：每张面之后游标必须前进
            st[4] += cnum;
            st[2] += cnum;
        }
    }

    /** 四边面 [0,1,2],[3,0,2]；三角 [0,1,2]；n 边扇形 [0,i,i+1]。 */

    // ------------------------------------------------------------------ 材质

    /** 写一段 newmtl（全局只写一次），返回这张材质贴图的 [宽, 高]（读不到就 0,0）。 */
    private static int[] writeMtl(String mname, List<?> md, P3DPack.Pack pack,
                                  Map<String, byte[]> textures, StringBuilder mtl) {
        Map<String, Object> mp = propMap(asList(md.get(2)));
        JSONObject col = jsonObj(mp.get("color"));
        mtl.append("newmtl ").append(mname).append('\n');
        mtl.append("Kd ").append(f(col, "r", 1)).append(' ')
                .append(f(col, "g", 1)).append(' ').append(f(col, "b", 1)).append('\n');
        mtl.append("Ka 0 0 0\n");
        mtl.append("d ").append(f(col, "a", 1)).append('\n');
        int[] size = {0, 0};
        Object tex = mp.get("texture");
        // 3.0 没贴图时 texture 是字面量 "None"，不是 null —— 要按内容判
        if (tex != null && String.valueOf(tex).length() > 0
                && !"None".equals(String.valueOf(tex))) {
            String texPath = String.valueOf(tex).replace("\\", "/");
            String base = texPath.substring(texPath.lastIndexOf('/') + 1);
            // 贴图和 obj 同目录、只写文件名：微软 3D 查看器对 res/ 子目录解析不稳。
            mtl.append("map_Kd ").append(base).append('\n');
            byte[] texData = findTexture(pack, base);
            if (texData != null) {
                textures.put(base, texData);
                size = P3DToObj.pngSize(texData);
                // 带 alpha 的贴图补 map_d，支持透明度的查看器才能镂空
                if (P3DToObj.pngHasAlpha(texData)) {
                    mtl.append("map_d ").append(base).append('\n');
                }
            }
        }
        mtl.append('\n');
        return size;
    }

    private static String matName(List<?> md) {
        String name = md.get(1) instanceof String ? (String) md.get(1) : "mat";
        String uuid = md.get(0) instanceof String ? (String) md.get(0) : "";
        String suffix = uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
        return name + "_" + suffix;
    }

    // ------------------------------------------------------------------ 结构取值

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

    /** 组件/材质的属性段 {@code [[键, JSON值串, 类型], ...]} → Map。 */
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

    /** {"id":"uuid"} 之类的单字段 JSON 串取值。 */
    private static String jsonStr(Object jsonStr, String key) {
        if (jsonStr == null) {
            return null;
        }
        try {
            String v = new JSONObject(String.valueOf(jsonStr)).optString(key, null);
            return v == null || v.length() == 0 ? null : v;
        } catch (Throwable t) {
            return null;
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

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            // 空白一定换下划线（OBJ 按 token 断开）；U+FFFD 是解码替换符，查看器嫌它，清掉
            boolean keep = !Character.isWhitespace(c) && c != '\uFFFD'
                    && (Character.isLetterOrDigit(c) || c == '_' || c == '-'
                    || c > 0x2E80);
            sb.append(keep ? c : '_');
        }
        String s = sb.toString();
        return s.length() == 0 ? "obj" : s;
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

    // ------------------------------------------------------------------ 变换

    private static float[][] identity() {
        return new float[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
    }

    /**
     * 局部矩阵：欧拉角语义与 2.0 完全一致（TEST 双版本逐字段对照证实），公式沿用
     * {@link P3DToObj}（用户已在 3D 查看器验收）。
     */
    private static float[][] localMatrix(List<?> objA) {
        float[] t = {0, 0, 0};
        float[] r = {0, 0, 0};
        float[] s = {1, 1, 1};
        List<?> components = objA == null ? null : asList(objA.get(2));
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

    /**
     * 沿父链累乘得到世界矩阵（3.0 用字符串 uuid 当键）。
     *
     * <p>注意顺序：{@code emitTree} 里是 {@code m = mul(wm, local)}，即「父 × 子」；
     * 所以自底向上累乘要写成 {@code m = mul(local, m)}，反了整包会绕原点乱转。
     */
    private static float[][] worldMatrixOf(Map<String, List<?>> byUuid, String id) {
        float[][] m = identity();
        String cur = id;
        int guard = 0;
        while (cur != null && byUuid.containsKey(cur) && guard++ < 512) {
            List<?> a = byUuid.get(cur);
            m = mul(localMatrix(a), m);
            Object par = a != null && a.size() >= 4 ? a.get(3) : null;
            cur = par instanceof String ? (String) par : null;
        }
        return m;
    }

    /**
     * 骨骼世界矩阵 × bindpose（列主序 4×4，平移在 b[12..14]）。
     * {@code M[i][j] = Σ_k W[i][k] · b[j*4+k]}，四次点积即可。
     * 少了它顶点会留在绑定空间里，头发和四肢会炸开。
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
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    float wv = k < 3 ? w[i][k] : w[i][3];
                    sum += wv * bf[j * 4 + k];
                }
                m[i][j] = sum;
            }
        }
        return m;
    }

    /** 线性混合蒙皮：{@code Σ w·(BoneWorld·BindPose)·v / Σ w}。 */
    private static float[] skinnedVertex(Object skinEntry, float[] v, List<String> boneIds,
                                         Map<String, List<?>> byUuid,
                                         Map<String, float[][]> cache,
                                         List<?> binds, float[][] fallback) {
        List<?> s = asList(skinEntry);
        if (s == null || s.size() < 5 || boneIds.isEmpty()) {
            return transform(fallback, v);
        }
        float ax = 0, ay = 0, az = 0, tw = 0;
        float[][] firstM = null;
        for (int k = 0; k < 4 && k + 4 < s.size(); k++) {
            int bi = s.get(k) instanceof Number ? ((Number) s.get(k)).intValue() : -1;
            float w = numf(s.get(4 + k), 0);
            if (bi < 0 || bi >= boneIds.size()) {
                continue;
            }
            String bone = boneIds.get(bi);
            float[][] bm = cache.get(bone);
            if (bm == null) {
                bm = skinMatrix(worldMatrixOf(byUuid, bone),
                        binds != null && bi < binds.size() ? asList(binds.get(bi)) : null);
                cache.put(bone, bm);
            }
            if (firstM == null) {
                firstM = bm;
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
        // 权重全 0：贴到第一根有效骨骼上，绝不退回对象矩阵（那是绑定空间里的占位，会飞）。
        if (firstM != null) {
            return transform(firstM, v);
        }
        return transform(fallback, v);
    }

    /** 浮点读取。权重（0.502）和 bindpose 的 6.4 倍缩放必须走这个，不能用 intValue。 */
    private static float numf(Object o, float dflt) {
        return o instanceof Number ? ((Number) o).floatValue() : dflt;
    }

    private static float[] transform(float[][] m, float[] p) {
        return new float[]{
                m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3],
                m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3],
                m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3],
        };
    }
}
