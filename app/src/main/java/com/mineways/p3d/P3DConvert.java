package com.mineways.p3d;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;

/**
 * Prisma3D 3.0 工程包 → 2.0 单体工程包。
 *
 * <p>字段依据：从 APK 的 IL2CPP metadata 里抽出的 v200 / v300 模型字段表，再用两个真实工程样本
 * 逐段核对（对象、标签、属性、材质、网格、骨骼、动画曲线都能字段级对上）。
 *
 * <pre>
 *   v300.JProject   : renderSettings, rootObjects, materials, tracks, projectMinutes
 *   v300.JObject    : id,title,tags,parentId,visibility,animationFormat,referenceId,restrictAssetId
 *   v300.JProperty  : name, serializedValue, propertyType
 *   v300.JMaterial  : id,name,shaderProperties,referenceId,shaderName
 *   v300.JMesh/JMesh2: id,faceSubmeshes,faceLengths,faceNormals,faceVertices,faceUvs
 *                     [,faceTwinInfos,faceTriangulations],vertices,boneWeights,bindPoses
 *   v300.JAnimation : curves, metaData
 *   v200.JProject   : renderSettings, objects, materials, meshes
 *   v200.JObject    : id,title,tags,parentId,visibility,keepChildrenVisible
 *   v200.JProperty  : name, serializedValue, animation
 *   v200.JMaterial  : id,name,properties
 *   v200.JPMesh     : id,faceSubmeshes,faceLengths,faceNormals,faceVertices,faceUvs,
 *                     vertices,boneWeights,bindPoses
 * </pre>
 *
 * 需要专门处理的四件事（漏一个 2.0 就打不开或数据错位）：
 * <ol>
 *   <li>UUID → 整数索引：对象/材质/网格都要建映射，并把属性值里 JSON 形式的 uuid 改写掉；</li>
 *   <li>对象树 → 扁平列表：深度优先展开（父在前），因为 2.0 用列表下标当 id；</li>
 *   <li>JMesh2 的两个新字段必须跳过，否则 faceUvs 之后全体错位；</li>
 *   <li>动画从 pclip 的「路径 → 曲线」搬回属性的第三项（v300 那里放的是类型名）。</li>
 * </ol>
 *
 * <p>2.0 侧的额外属性（normalBreakAngle / normalMap / specularMap / cullMode）默认剔除，
 * 让产物尽量贴近 2.0 原生结构；要保留可以改 {@link #DROP_V300_ONLY}。
 */
public final class P3DConvert {

    /** 2.0 里不存在的 v300 属性：默认剔除（保留原样也能被 2.0 忽略，但贴近原生更稳）。 */
    private static final boolean DROP_V300_ONLY = true;
    private static final Set<String> V300_ONLY_PROPS = new HashSet<>(Arrays.asList(
            "normalBreakAngle", "normalMap", "specularMap", "cullMode"));

    private static final Pattern UUID_STR = Pattern.compile("\"([0-9a-f]{32})\"");

    public static final class Result {
        public byte[] zip;
        public String projectName = "Project";
        public int objects;
        public int materials;
        public int meshes;
        public int animationTracks;
        public final List<String> notes = new ArrayList<>();

        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("工程名：").append(projectName).append('\n');
            sb.append("对象 ").append(objects)
                    .append(" · 材质 ").append(materials)
                    .append(" · 网格 ").append(meshes)
                    .append(" · 动画轨 ").append(animationTracks).append('\n');
            for (String n : notes) {
                sb.append("· ").append(n).append('\n');
            }
            sb.append("\n产物是 Prisma3D 2.0 的单体工程包（<工程名>/<工程名>.proj + res/ + 截图）。");
            return sb.toString();
        }
    }

    private P3DConvert() {
    }

    // ------------------------------------------------------------------ 主流程

    public static Result toV200(byte[] raw) throws Exception {
        P3DPack.Pack pack = P3DPack.open(raw);
        if (pack.version != P3DPack.V_300) {
            throw new IllegalStateException("这是 Prisma3D "
                    + (pack.version == P3DPack.V_200 ? "2.0" : "未知版本")
                    + " 的工程包，只能把 3.0 转成 2.0（2.0 工程 3.0 本来就能直接打开）");
        }
        final Result r = new Result();
        if (!pack.projectName.isEmpty()) {
            r.projectName = pack.projectName;
        }

        List<Object> projectProj = null;
        List<List<Object>> objects = new ArrayList<>();
        List<List<Object>> meshes = new ArrayList<>();
        Map<String, Object> anims = new LinkedHashMap<>();

        for (P3DPack.Entry e : pack.entries) {
            String n = e.name;
            if (n.contains("+0.")) {
                continue;                   // 同代快照副本，跳过
            }
            if (n.endsWith("project.proj")) {
                projectProj = new Mp.Reader(e.data, 0).readAll();
            } else if (n.endsWith(".pobject")) {
                List<Object> vals = new Mp.Reader(e.data, 0).readAll();
                int before = objects.size();
                for (Object v : vals) {
                    if (looksLikeObjects(v)) {
                        for (Object o : asList(v)) {
                            objects.add(asList(o));
                        }
                    } else if (looksLikeMeshes(v)) {
                        for (Object o : asList(v)) {
                            meshes.add(asList(o));
                        }
                    }
                }
                r.notes.add("pobject " + leaf(n) + "：对象 +" + (objects.size() - before)
                        + "，网格 " + meshes.size());
            } else if (n.endsWith(".pclip")) {
                Map<String, Object> got = parsePclip(new Mp.Reader(e.data, 0).readAll());
                anims.putAll(got);
                r.notes.add("pclip " + leaf(n) + "：" + got.size() + " 条动画轨");
            }
        }
        if (projectProj == null) {
            throw new IllegalStateException("包里没有 project.proj，不是标准的 3.0 工程包");
        }
        r.animationTracks = anims.size();

        // ---- 材质：v300 [uuid, name, shaderProperties, referenceId, shaderName]
        //            -> v200 [id, name, properties]（id 从 1 开始，与 2.0 样本一致）
        List<Object> materialsRaw = projectProj.size() > 12 ? asList(projectProj.get(12)) : new ArrayList<>();
        Map<String, Integer> matIndex = new LinkedHashMap<>();
        List<Object> matEntries = new ArrayList<>();
        int matId = 1;
        for (Object mo : materialsRaw) {
            List<Object> m = asList(mo);
            String uuid = m.isEmpty() ? "" : String.valueOf(m.get(0));
            matIndex.put(uuid, matId);
            List<Object> props = new ArrayList<>();
            if (m.size() > 2) {
                for (Object po : asList(m.get(2))) {
                    List<Object> p = asList(po);
                    if (p.isEmpty()) {
                        continue;
                    }
                    String pname = String.valueOf(p.get(0));
                    Object pval = p.size() > 1 ? p.get(1) : null;
                    if (DROP_V300_ONLY && V300_ONLY_PROPS.contains(pname)) {
                        continue;
                    }
                    props.add(new ArrayList<>(Arrays.asList(pname, pval, null)));
                }
            }
            String name = m.size() > 1 ? (m.get(1) instanceof String ? (String) m.get(1) : null) : null;
            // 真 2.0 样本的材质属性里有一条 name，3.0 没有 → 补上，免得 2.0 里材质没名字
            boolean hasName = false;
            for (Object po : props) {
                List<Object> p = asList(po);
                if (!p.isEmpty() && "name".equals(String.valueOf(p.get(0)))) {
                    hasName = true;
                }
            }
            if (!hasName) {
                props.add(new ArrayList<>(Arrays.asList((Object) "name",
                        name == null || name.length() == 0 ? "Material" : name, null)));
            }
            matEntries.add(new ArrayList<>(Arrays.asList((Object) (long) matId, name, props)));
            matId++;
        }

        // ---- 网格：JMesh/JMesh2 -> JPMesh（字段同序，JMesh2 多出的两个必须跳过）
        Map<String, Integer> meshIndex = new LinkedHashMap<>();
        List<Object> meshEntries = new ArrayList<>();
        for (int i = 0; i < meshes.size(); i++) {
            List<Object> m = meshes.get(i);
            meshIndex.put(String.valueOf(m.get(0)), i);
            List<Object> core = new ArrayList<>();
            if (m.size() >= 11) {
                core.addAll(m.subList(1, 6));
                core.addAll(m.subList(8, 11));
            } else {
                core.addAll(m.subList(1, Math.min(9, m.size())));
            }
            List<Object> row = new ArrayList<>();
            row.add((long) i);
            row.addAll(core);
            meshEntries.add(row);
        }

        // ---- 对象：树 -> 扁平列表（深度优先），顺手建 uuid -> 下标
        Map<String, List<Object>> byUuid = new LinkedHashMap<>();
        for (List<Object> o : objects) {
            if (!o.isEmpty()) {
                byUuid.put(String.valueOf(o.get(0)), o);
            }
        }
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (List<Object> o : objects) {
            if (o.isEmpty()) {
                continue;
            }
            String uuid = String.valueOf(o.get(0));
            Object pid = o.size() > 3 ? o.get(3) : null;
            if (pid instanceof String && byUuid.containsKey(pid)) {
                List<String> list = children.get(pid);
                if (list == null) {
                    list = new ArrayList<>();
                    children.put((String) pid, list);
                }
                list.add(uuid);
            } else {
                roots.add(uuid);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(roots.get(i));
        }
        while (!stack.isEmpty()) {
            String u = stack.pop();
            if (!seen.add(u)) {
                continue;
            }
            order.add(u);
            List<String> ch = children.get(u);
            if (ch != null) {
                for (int i = ch.size() - 1; i >= 0; i--) {
                    stack.push(ch.get(i));
                }
            }
        }
        for (String u : byUuid.keySet()) {
            if (!seen.contains(u)) {
                order.add(u);
            }
        }
        Map<String, Integer> objIndex = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            objIndex.put(order.get(i), i);
        }

        // ---- 对象转 v200 结构
        List<Object> v200Objects = new ArrayList<>();
        for (String uuid : order) {
            List<Object> o = byUuid.get(uuid);
            String title = o.size() > 1 && o.get(1) instanceof String ? (String) o.get(1) : "";
            boolean visibility = !(o.size() > 4) || !Boolean.FALSE.equals(o.get(4));
            Object pidRaw = o.size() > 3 ? o.get(3) : null;
            Integer pid = pidRaw instanceof String ? objIndex.get(pidRaw) : null;
            List<Object> tags = new ArrayList<>();
            if (o.size() > 2) {
                for (Object to : asList(o.get(2))) {
                    List<Object> tag = asList(to);
                    if (tag.isEmpty()) {
                        continue;
                    }
                    String tagType = String.valueOf(tag.get(0));
                    String path = tagPath(tagType);
                    List<Object> props = new ArrayList<>();
                    if (tag.size() > 1) {
                        for (Object po : asList(tag.get(1))) {
                            List<Object> p = asList(po);
                            if (p.isEmpty()) {
                                continue;
                            }
                            String pname = String.valueOf(p.get(0));
                            if (DROP_V300_ONLY && V300_ONLY_PROPS.contains(pname)) {
                                continue;
                            }
                            Object pval = p.size() > 1 ? p.get(1) : null;
                            Object anim = anims.get(uuid + "/" + path + "." + pname);
                            if (pval instanceof String) {
                                pval = remap((String) pval, refKind(pname), objIndex, matIndex, meshIndex);
                            }
                            props.add(new ArrayList<>(Arrays.asList(pname, pval, anim)));
                        }
                    }
                    tags.add(new ArrayList<>(Arrays.asList((Object) tagType, props)));
                }
            }
            List<Object> row = new ArrayList<>();
            row.add((long) (int) objIndex.get(uuid));
            row.add(title);
            row.add(tags);
            row.add(pid == null ? -1L : (long) (int) pid);
            row.add(visibility);
            v200Objects.add(row);
        }

        // ---- renderSettings：v300 那 8 个值的对位没确认（字段表是 10 个），
        //      这里按 2.0 真实样本的同构写法给保守值（分辨率可用 2.0 里再改）
        List<Object> renderVals = new ArrayList<>();
        for (int i = 3; i < Math.min(11, projectProj.size()); i++) {
            renderVals.add(projectProj.get(i));
        }
        Object ambient = null;
        for (Object v : renderVals) {
            if (v instanceof List && ((List<?>) v).size() == 4) {
                ambient = v;
                break;
            }
        }
        if (ambient == null) {
            ambient = new ArrayList<>(Arrays.asList((Object) 0.77f, 0.79f, 0.8f, 1.0f));
        }
        List<Object> render = new ArrayList<>(Arrays.asList(
                (Object) 1280L, 720L, 30L,
                (long) Integer.MIN_VALUE, (long) Integer.MIN_VALUE, -1L, ambient));

        List<Object> project = new ArrayList<>(Arrays.asList(
                (Object) render, v200Objects, matEntries, meshEntries));
        byte[] body = Mp.writeAll(Arrays.asList((Object) 0L, 32L, 0L, 0L, project));

        // ---- 打包成 2.0 结构：<工程名>/<工程名>.proj + <工程名>/res/* + <工程名>/screenshot.png
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            putEntry(zos, r.projectName + "/" + r.projectName + ".proj", body);
            for (P3DPack.Entry e : pack.entries) {
                int i = e.name.indexOf("/res/");
                if (i >= 0) {
                    putEntry(zos, r.projectName + "/res/" + e.name.substring(i + 5), e.data);
                } else if (e.name.endsWith("screenshot.png")) {
                    putEntry(zos, r.projectName + "/screenshot.png", e.data);
                }
            }
        } finally {
            zos.close();
        }
        r.zip = bos.toByteArray();
        r.objects = v200Objects.size();
        r.materials = matEntries.size();
        r.meshes = meshEntries.size();
        r.notes.add("已丢弃 2.0 不支持的属性：" + V300_ONLY_PROPS);
        r.notes.add("分辨率按 1280×720@30 写入（v300 里对位未确认）");
        return r;
    }

    // ------------------------------------------------------------------ 内部

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(0L);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    private static String leaf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** PTransform -> transform（pclip 里的路径前缀就是这么写的）。 */
    private static String tagPath(String tagType) {
        if (tagType == null || tagType.length() == 0) {
            return "";
        }
        if (tagType.length() > 1 && tagType.charAt(0) == 'P') {
            return Character.toLowerCase(tagType.charAt(1)) + tagType.substring(2);
        }
        return Character.toLowerCase(tagType.charAt(0)) + tagType.substring(1);
    }

    private static String refKind(String propName) {
        if ("pMesh".equals(propName) || "mesh".equals(propName) || "meshes".equals(propName)) {
            return "mesh";
        }
        if ("materials".equals(propName) || "material".equals(propName)
                || "materialIds".equals(propName)) {
            return "material";
        }
        return "object";
    }

    /** 把属性值（JSON 字符串）里的 uuid 换成对应的整数索引。 */
    private static String remap(String value, String kind, Map<String, Integer> objIndex,
                                Map<String, Integer> matIndex, Map<String, Integer> meshIndex) {
        if (value.indexOf('"') < 0) {
            return value;
        }
        Matcher m = UUID_STR.matcher(value);
        StringBuffer sb = new StringBuffer(value.length());
        while (m.find()) {
            String u = m.group(1);
            Integer idx;
            if ("mesh".equals(kind)) {
                idx = meshIndex.get(u);
            } else if ("material".equals(kind)) {
                idx = matIndex.get(u);
            } else {
                idx = objIndex.get(u);
            }
            m.appendReplacement(sb, idx == null ? "-1" : String.valueOf((int) idx));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeObjects(Object v) {
        List<Object> l = asList(v);
        if (l.isEmpty()) {
            return false;
        }
        List<Object> f = asList(l.get(0));
        return f.size() >= 8 && f.get(0) instanceof String && f.get(1) instanceof String
                && f.get(2) instanceof List;
    }

    private static boolean looksLikeMeshes(Object v) {
        List<Object> l = asList(v);
        if (l.isEmpty()) {
            return false;
        }
        List<Object> f = asList(l.get(0));
        return f.size() >= 9 && f.get(0) instanceof String && f.get(1) instanceof List
                && f.get(2) instanceof List && f.get(3) instanceof List;
    }

    /** pclip 里那张 animations map：键 = <uuid>/<组件>.<属性>，值 = [curves, metaData] → 取 curves。 */
    private static Map<String, Object> parsePclip(List<Object> vals) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object v : vals) {
            Object cand = v;
            if (v instanceof List && ((List<?>) v).size() == 1
                    && ((List<?>) v).get(0) instanceof Map) {
                cand = ((List<?>) v).get(0);
            }
            if (!(cand instanceof Map)) {
                continue;
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) cand).entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.indexOf('/') < 0) {
                    continue;
                }
                Object val = e.getValue();
                out.put(k, val instanceof List && !((List<?>) val).isEmpty()
                        ? ((List<?>) val).get(0) : val);
            }
        }
        return out;
    }
}
