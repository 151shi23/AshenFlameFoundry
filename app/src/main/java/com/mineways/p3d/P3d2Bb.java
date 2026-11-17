package com.mineways.p3d;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Prisma3D 工程包（.prisma，2.0 / 3.0）→ Blockbench（.bbmodel）。
 *
 * <p>这一份是上游工具包 {@code p3d转blockbench工具包 v5.2} 里 {@code p3d2bb.py} 的移植，语义以那份
 * Python 为准 —— 真 Blockbench 4.12 的实机验收（骨骼版与静态版逐部件世界坐标差 = 0）是按它做的。
 * 随包那份 {@code java_src/P3d2Bb.java} 只到 v4 的静态烘焙，缺三样：
 * <ol>
 *   <li><b>骨骼版</b>：中间组一律零变换、完整世界变换只放在叶子部件组上 —— 版本兼容扁平方案，
 *       旧版 Blockbench 不实现「骨骼 origin 差减」时也不会把部件甩飞；</li>
 *   <li><b>灯光</b>：PLight → locator 元素，完整参数另存根字段可回转；</li>
 *   <li><b>环境光 / 背景色</b>：header 里那两个颜色存进 {@code unhandled_root_fields}，
 *       Blockbench 原样保留。</li>
 * </ol>
 * 另外那份 Java 的纯色贴图用 {@code java.awt} + {@code javax.imageio} 生成 —— 安卓没有这两个类，
 * 工程里只要有一个「没贴图的材质」就会当场抛，这里按 PNG 规范手写（与 Python 的 zlib level 9 同构）。
 *
 * <p>格式结论（实测逆向）：{@code .prisma} = ZIP 壳 + MessagePack 流，两版均未加密。3.0 的「加密」
 * 只是首成员本地头魔数第 1 字节被改坏（'P' 0x50 → 0x60），中央目录完好，所以本实现按中央目录定位
 * 数据流绕过那个字节。2.0 是 {@code <name>.proj} 单体；3.0 是 {@code project.proj}（场景 + 材质）
 * + {@code content/*.pobject}（子树 + 网格）+ {@code clip/*.pclip}（动画），
 * 每个数据流都是 [4 字节前缀][msgpack]。
 *
 * <p>坐标：P3D 是 Unity 系（左手 Y-up）→ Blockbench free（右手 Y-up），映射
 * {@code (x,y,z) → (x,y,-z)} 并反转三角形绕序；UV 默认按 Unity 惯例翻 v。
 */
public final class P3d2Bb {

    /** bbmodel 的 UV 空间分辨率，同时写进 resolution 与每张贴图的 uv_width/uv_height。 */
    public static final int UV_RES = 1024;

    private P3d2Bb() {
    }

    // ==================================================================== 对外入口

    /** 转换选项，默认值与 Python 的函数签名一一对应。 */
    public static final class Opts {
        /** true = 骨骼版（部件在 Blockbench 里可摆姿势）；false = 静态版（位置已烘焙进顶点）。 */
        public boolean rig;
        /**
         * 骨骼版是否把关节位置写进组的 {@code origin}（轴心）。
         *
         * <p>默认 true：转某根骨时子部件绕关节摆动。但 Blockbench 各版本对「组轴心是否平移子级」
         * 的语义并不一致，个别版本会因此整包位移；关掉它（false）就是把所有组的 origin 归零，
         * 位置与静态版逐点相同，代价是每根骨的旋转轴心都在原点。
         */
        public boolean rigPivot = true;
        /** P3D 的 v 原点在下，默认翻 v；贴图上下颠倒时关掉。 */
        public boolean uvFlip = true;
        /** 单个网格的三角面上限，超过就整个网格不导出（null 或 0 = 不限）。 */
        public Long maxTris;
        /** 只转某个对象及其整条父链（null = 全部）。 */
        public String onlyName;
    }

    /** 转换结果：bbmodel 正文（UTF-8）+ 统计。 */
    public static final class Result {
        public final byte[] bbmodel;
        public final int version;
        public final String sceneName;
        public final boolean rig;
        /** 骨骼版是否写了关节轴心（`Opts.rigPivot`）。 */
        public final boolean rigPivot;
        public final long groups;
        public final long meshes;
        public final long tris;
        public final long verts;
        public final long lights;
        public final long noTexture;
        public final long skippedBig;
        public final int textures;
        public final int clips;

        Result(byte[] bbmodel, int version, String sceneName, boolean rig, boolean rigPivot, long[] st,
               int textures, int clips) {
            this.bbmodel = bbmodel;
            this.version = version;
            this.sceneName = sceneName;
            this.rig = rig;
            this.rigPivot = rigPivot;
            this.groups = st[0];
            this.meshes = st[1];
            this.tris = st[2];
            this.verts = st[3];
            this.lights = st[4];
            this.noTexture = st[5];
            this.skippedBig = st[6];
            this.textures = textures;
            this.clips = clips;
        }

        /** 一行一句中文摘要，给完成弹窗用。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("工程版本：P3D ").append(version).append(".0");
            sb.append("\n工程名：").append(sceneName);
            sb.append("\n输出：").append(rig
                    ? (rigPivot ? "骨骼版（部件可摆姿势，关节轴心已写）" : "骨骼版·轴心归零（位置最保险，轴心都在原点）")
                    : "静态版（位置已烘焙进顶点）");
            sb.append("\n\n对象 ").append(groups)
                    .append(" · 网格元素 ").append(meshes)
                    .append(" · 三角面 ").append(tris)
                    .append(" · 顶点 ").append(verts);
            sb.append("\n贴图 ").append(textures)
                    .append(" · 灯光 ").append(lights)
                    .append(" · 动画 clip ").append(clips);
            if (noTexture > 0) {
                sb.append("\n（").append(noTexture)
                        .append(" 个面所属材质没有贴图，已按材质颜色补了纯色图）");
            }
            if (skippedBig > 0) {
                sb.append("\n（超过面数上限、整个跳过的网格 ").append(skippedBig).append(" 个）");
            }
            sb.append("\n\n蒙皮已按导出时的姿态烘进顶点（蒙皮权重与动画 clip 不搬）；"
                    + "灯光在 Blockbench 里是 locator 记号，不是可渲染光源。");
            return sb.toString();
        }
    }

    /** 内存转换：.prisma 字节 → .bbmodel 字节。不碰文件系统，界面侧读进来、写出去各一次就够。 */
    public static Result convert(byte[] prisma, Opts opt) throws IOException {
        Build b = new Build(opt == null ? new Opts() : opt);
        b.run(prisma);
        return b.assemble();
    }

    /** JVM 对拍入口：java com.mineways.p3d.P3d2Bb &lt;in.prisma&gt; &lt;out.bbmodel&gt; [--rig] [--no-uv-flip] [--max-tris N] [--only NAME] */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: java com.mineways.p3d.P3d2Bb <in.prisma> <out.bbmodel>"
                    + " [--rig] [--no-uv-flip] [--max-tris N] [--only NAME]");
            return;
        }
        Opts o = new Opts();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--rig":
                    o.rig = true;
                    break;
                case "--no-pivot":
                    o.rigPivot = false;
                    break;
                case "--no-uv-flip":
                    o.uvFlip = false;
                    break;
                case "--only":
                    o.onlyName = args[++i];
                    break;
                case "--max-tris":
                    o.maxTris = Long.parseLong(args[++i]);
                    break;
                default:
                    break;
            }
        }
        Result r = convert(readAll(args[0]), o);
        OutputStream fos = new FileOutputStream(args[1]);
        try {
            fos.write(r.bbmodel);
        } finally {
            fos.close();
        }
        System.out.printf("[P3d2Bb] P3D %d.0  '%s'  ->  %s%n", r.version, r.sceneName, args[1]);
        System.out.printf("  对象 %d | 网格元素 %d | 三角面 %d | 顶点 %d | 贴图 %d | 灯光 %d | clip %d%s%n",
                r.groups, r.meshes, r.tris, r.verts, r.textures, r.lights, r.clips,
                r.rig ? (o.rigPivot ? " | 骨骼模式" : " | 骨骼模式（轴心归零）") : "");
        if (r.skippedBig > 0) {
            System.out.printf("  (跳过超限网格 %d 个)%n", r.skippedBig);
        }
    }

    static byte[] readAll(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    // ==================================================================== 数据模型

    static final class Obj {
        String id;
        String name;
        String parent;
        boolean visible = true;
        double[] pos = {0, 0, 0};
        double[] eul = {0, 0, 0};
        double[] scl = {1, 1, 1};
        String mesh;
        List<String> matIds = Collections.emptyList();
        /** 渲染器 `isSkinned`：顶点在骨骼空间里，必须按权重逐点变换才落到身上。 */
        boolean skinned;
        /** 渲染器 `skinBones.ids`：蒙皮索引指向的骨骼对象 id。 */
        List<String> skinBones = Collections.emptyList();
        /** PLight 组件属性表；这个对象不是灯就是 null。 */
        Map<String, Object> light;
        final List<String> children = new ArrayList<>();
        double[] worldM = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double[] worldT = {0, 0, 0};
        /** 纯旋转累计（不含缩放）：骨骼版的组旋转由它分解而来。 */
        double[] worldR = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double[] rigOrigin;
        double[] rigRot;
        double[] rigVScale;
    }

    static final class MeshData {
        int[] slot;
        int[] indices;
        double[][] uvs;
        double[][] verts;
        /**
         * 每面角点数（2.0 = mesh 字段 2，3.0 = submesh 字段 2）。
         *
         * <p><b>必须按它切面</b>：索引缓冲是「按面顺序摊平的角点」，面可能是三角、四边形或五边形。
         * 以前直接拿 {@code indices.length / 3} 当三角形数，四边形会被切错 —— 头发是 44 个四边形，
         * 于是被当成 58 个三角形，发片全撕裂。
         */
        int[] corners;
        /** 每顶点 8 个蒙皮数：4 个骨骼下标 + 4 个权重；没有蒙皮时为 null。 */
        double[][] skin;
        /**
         * 每根骨骼一个 4×4 绑定矩阵，**列主序摊平**（平移在 [12][13][14]，第 16 个是 1）。
         * 这就是 Unity 的 inverseBindPose：蒙皮要算 {@code 骨骼世界矩阵 × 这个}，
         * 少了它顶点会留在绑定空间里，头发、四肢会炸开。
         */
        double[][] binds;
    }

    static final class Mat {
        String id;
        Object texture;
        Object color;
    }

    static final class Tex {
        String name;
        byte[] blob;
        String uuid;

        /** 只发被面真正用到的；id 是它在输出数组里的序号（同 Python）。 */
        String json(int index) {
            int[] wh = pngSize(blob);
            String mime = blob.length > 2 && (blob[0] & 0xff) == 0xff && (blob[1] & 0xff) == 0xd8
                    ? "image/jpeg" : "image/png";
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"name\":\"").append(jsonEsc(name)).append("\",\"id\":\"").append(index).append('"');
            sb.append(",\"particle\":false,\"source\":\"data:").append(mime).append(";base64,")
                    .append(Base64.getEncoder().encodeToString(blob)).append('"');
            sb.append(",\"mode\":\"bitmap\",\"uuid\":\"").append(uuid).append('"');
            sb.append(",\"width\":").append(wh[0] < 0 ? "null" : Integer.toString(wh[0]));
            sb.append(",\"height\":").append(wh[1] < 0 ? "null" : Integer.toString(wh[1]));
            sb.append(",\"uv_width\":").append(UV_RES).append(",\"uv_height\":").append(UV_RES);
            sb.append(",\"internal\":true}");
            return sb.toString();
        }
    }

    static final class Scene {
        String name = "p3d";
        int version;
        final Map<String, Obj> objects = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        final Map<String, MeshData> meshes = new LinkedHashMap<>();
        final Map<String, Mat> materials = new LinkedHashMap<>();
        List<Object> clips = new ArrayList<>();
        Object ambient;
        Object background;
    }

    // ==================================================================== 转换主体

    private static final class Build {
        final Opts opt;
        final Scene sc = new Scene();
        /** 材质 id → 贴图 uuid。solid_* 是纯色图的键，solid_ffffff 是最终兜底。 */
        final Map<String, String> matTex = new LinkedHashMap<>();
        /** 贴图 uuid → 条目，按创建顺序（决定 textures 数组顺序，同 Python 的 dict）。 */
        final Map<String, Tex> texs = new LinkedHashMap<>();
        final Set<String> usedTex = new LinkedHashSet<>();
        final List<String> elementJsons = new ArrayList<>();
        final List<String> groupJsons = new ArrayList<>();
        final List<String> lightJsons = new ArrayList<>();
        /** [对象数, 网格元素数, 三角面, 顶点, 灯光, 无贴图面, 超限跳过] */
        final long[] st = new long[7];
        Set<String> keep;

        Build(Opts opt) {
            this.opt = opt;
        }

        void run(byte[] zipData) throws IOException {
            Map<String, byte[]> files = extractZipTolerant(zipData);
            parseScene(files);
            collectTextures(files);
            if (opt.onlyName != null && opt.onlyName.length() > 0) {
                filterSubtree(opt.onlyName);
            }
            bakeTransforms();
            for (String oid : sc.order) {
                Obj o = sc.objects.get(oid);
                if (o.parent != null && sc.objects.containsKey(o.parent)) {
                    continue;
                }
                if (keep != null && !keep.contains(oid)) {
                    continue;
                }
                String g = emitGroup(oid);
                if (g != null) {
                    groupJsons.add(g);
                }
            }
            emitLights();
        }

        Result assemble() {
            List<String> texJson = new ArrayList<>();
            for (Map.Entry<String, Tex> e : texs.entrySet()) {
                if (!usedTex.contains(e.getKey())) {
                    continue;
                }
                texJson.add(e.getValue().json(texJson.size()));
            }
            StringBuilder sb = new StringBuilder(1 << 20);
            sb.append("{\"meta\":{\"format_version\":\"4.5\",\"model_format\":\"free\",\"box_uv\":false},");
            sb.append("\"name\":\"").append(jsonEsc(sc.name + " · p3d2bb-v5")).append("\",");
            sb.append("\"model_identifier\":\"").append(jsonEsc(sc.name)).append("\",");
            sb.append("\"visible_box\":[1,1,0],\"variable_placeholders\":\"\",");
            sb.append("\"variable_placeholder_buttons\":[],\"timeline_setups\":[],");
            sb.append("\"unhandled_root_fields\":{\"p3d_ambient\":");
            jsonOf(sc.ambient, sb);
            sb.append(",\"p3d_background\":");
            jsonOf(sc.background, sb);
            sb.append(",\"p3d_lights\":[");
            join(sb, lightJsons);
            sb.append("],\"p3d_rig_mode\":").append(opt.rig).append("},");
            sb.append("\"resolution\":{\"width\":").append(UV_RES)
                    .append(",\"height\":").append(UV_RES).append("},");
            sb.append("\"elements\":[");
            join(sb, elementJsons);
            sb.append("],\"outliner\":[");
            join(sb, groupJsons);
            sb.append("],\"textures\":[");
            join(sb, texJson);
            sb.append("]}");
            return new Result(sb.toString().getBytes(StandardCharsets.UTF_8),
                    sc.version, sc.name, opt.rig, opt.rigPivot, st, texJson.size(), sc.clips.size());
        }

        // ---------------- 解析：两版差异在这一步收敛

        void parseScene(Map<String, byte[]> files) throws IOException {
            boolean v3 = false;
            for (String n : files.keySet()) {
                if (n.endsWith(".pobject")) {
                    v3 = true;
                    break;
                }
            }
            sc.version = v3 ? 3 : 2;
            String projName = null;
            for (String n : files.keySet()) {
                if (n.endsWith(".proj") && !n.endsWith("+1.proj")) {
                    projName = n;
                    break;
                }
            }
            if (projName == null) {
                throw new IOException("包里找不到工程数据（*.proj）");
            }
            List<Object> root = listOf(MsgP.skip4(files.get(projName)));
            List<Object> header = root.isEmpty() ? null : asList(root.get(0));
            if (!v3) {
                sc.name = baseName(projName);
                if (header != null && header.size() > 6) {
                    sc.ambient = header.get(6);
                }
                parseObjects(asList(root.size() > 1 ? root.get(1) : null));
                for (Object m : orEmpty(asList(root.size() > 2 ? root.get(2) : null))) {
                    addMaterial(orEmpty(asList(m)), false);
                }
                for (Object m : orEmpty(asList(root.size() > 3 ? root.get(3) : null))) {
                    List<Object> mm = orEmpty(asList(m));
                    if (mm.isEmpty()) {
                        continue;
                    }
                    addMesh2(str(mm.get(0)), mm.subList(1, mm.size()));
                }
            } else {
                for (String n : files.keySet()) {
                    if (!n.endsWith(".meta")) {
                        continue;
                    }
                    Object meta = MiniJson.parseSafe(new String(files.get(n), StandardCharsets.UTF_8));
                    if (meta instanceof Map) {
                        Object pn = MiniJson.mapOf(meta).get("projectName");
                        if (pn != null) {
                            sc.name = String.valueOf(pn);
                        }
                    }
                    break;
                }
                if (header != null && header.size() > 6) {
                    sc.ambient = header.get(6);
                }
                if (header != null && header.size() > 7) {
                    sc.background = header.get(7);
                }
                parseObjects(asList(root.size() > 1 ? root.get(1) : null));
                for (Object m : orEmpty(asList(root.size() > 2 ? root.get(2) : null))) {
                    addMaterial(orEmpty(asList(m)), true);
                }
                if (root.size() > 3 && root.get(3) instanceof List) {
                    sc.clips = asList(root.get(3));
                }
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    if (!e.getKey().endsWith(".pobject")) {
                        continue;
                    }
                    List<Object> po = listOf(MsgP.skip4(e.getValue()));
                    parseObjects(asList(po.size() > 1 ? po.get(1) : null));
                    for (Object sub : orEmpty(asList(po.size() > 2 ? po.get(2) : null))) {
                        addSubmesh3(orEmpty(asList(sub)));
                    }
                }
            }
            for (Obj o : sc.objects.values()) {
                if (o.parent != null && sc.objects.containsKey(o.parent)) {
                    sc.objects.get(o.parent).children.add(o.id);
                }
            }
        }

        void parseObjects(List<Object> objs) {
            if (objs == null) {
                return;
            }
            for (Object oo : objs) {
                List<Object> o = asList(oo);
                if (o == null || o.size() < 4) {
                    continue;
                }
                Obj rec = new Obj();
                rec.id = str(o.get(0));
                rec.name = str(o.get(1));
                // 3.0 的 pobject 根与 project.proj 里的对象同 uuid，先到先得
                if (rec.id == null || sc.objects.containsKey(rec.id)) {
                    continue;
                }
                rec.parent = o.get(3) == null ? null : str(o.get(3));
                rec.visible = Boolean.TRUE.equals(o.get(4));
                List<Object> comps = o.size() > 2 ? asList(o.get(2)) : null;
                for (Object cc : orEmpty(comps)) {
                    List<Object> c = asList(cc);
                    if (c == null || c.isEmpty()) {
                        continue;
                    }
                    String cname = str(c.get(0));
                    Map<String, Object> d = propsMap(c.size() > 1 ? asList(c.get(1)) : null);
                    if ("PTransform".equals(cname)) {
                        double[] v = vec(d.get("localPosition"));
                        if (v != null) {
                            rec.pos = v;
                        }
                        v = vec(d.get("localEulerAnglesHint"));
                        if (v != null) {
                            rec.eul = v;
                        }
                        v = vec(d.get("localScale"));
                        if (v != null) {
                            rec.scl = v;
                        }
                    } else if ("PLight".equals(cname)) {
                        rec.light = d;
                    } else if ("PMeshRenderer".equals(cname)) {
                        Object pm = parseJson(d.get("pMesh"));
                        Object mm = parseJson(d.get("materials"));
                        if (pm instanceof Map) {
                            rec.mesh = str(MiniJson.mapOf(pm).get("id"));
                        }
                        if (mm instanceof Map) {
                            List<Object> ids = asList(MiniJson.mapOf(mm).get("ids"));
                            List<String> ls = new ArrayList<>();
                            for (Object x : orEmpty(ids)) {
                                ls.add(str(x));
                            }
                            rec.matIds = ls;
                        }
                        // 蒙皮：不读这两个字段，顶点就留在骨骼空间里，整包散架。
                        rec.skinned = "true".equalsIgnoreCase(str(d.get("isSkinned")));
                        Object sb = parseJson(d.get("skinBones"));
                        if (sb instanceof Map) {
                            List<Object> ids = asList(MiniJson.mapOf(sb).get("ids"));
                            List<String> ls = new ArrayList<>();
                            for (Object x : orEmpty(ids)) {
                                ls.add(str(x));
                            }
                            rec.skinBones = ls;
                        }
                    }
                }
                sc.objects.put(rec.id, rec);
                sc.order.add(rec.id);
            }
        }

        void addMaterial(List<Object> m, boolean v3) {
            if (m.isEmpty()) {
                return;
            }
            Mat mat = new Mat();
            mat.id = str(m.get(0));
            Map<String, Object> d = propsMap(m.size() > 2 ? asList(m.get(2)) : null);
            mat.texture = d.get("texture");
            mat.color = parseJson(d.get("color"));
            sc.materials.put(mat.id, mat);
        }

        /** 2.0 mesh 去掉首元素后 = [slot, topo, normals, indices, uvs, verts, weights, …]。 */
        void addMesh2(String id, List<Object> f) {
            if (id == null || f.size() < 6) {
                return;
            }
            int[] indices = intArray(f.get(3));
            double[][] verts = vec3Array(f.get(5));
            if (indices == null || indices.length == 0 || verts == null || verts.length == 0) {
                return;
            }
            MeshData md = new MeshData();
            md.slot = intArray(f.get(0));
            md.indices = indices;
            md.uvs = vec2Array(f.get(4));
            md.verts = verts;
            // f 已去掉首元素：0=每面材质槽 1=每面角点数 2=每面法线 3=索引 4=uv 5=顶点 6=蒙皮 7=bindpose
            md.corners = intArray(f.get(1));
            md.skin = vec8Array(f.get(6));
            md.binds = mat16Array(f.get(7));
            sc.meshes.put(id, md);
        }

        /** 3.0 submesh = [uuid, slot, topo, normals, indices, uvs, ref, cycle, verts, weights, bindpose]。 */
        void addSubmesh3(List<Object> s) {
            if (s.size() < 9) {
                return;
            }
            String id = str(s.get(0));
            int[] indices = intArray(s.get(4));
            double[][] verts = vec3Array(s.get(8));
            if (id == null || indices == null || indices.length == 0
                    || verts == null || verts.length == 0) {
                return;
            }
            MeshData md = new MeshData();
            md.slot = intArray(s.get(1));
            md.indices = indices;
            md.uvs = vec2Array(s.get(5));
            md.verts = verts;
            md.corners = intArray(s.get(2));
            md.skin = vec8Array(s.get(9));
            // 3.0 的 bindpose 在第 11 槽（0-based 10）；老样本可能没有，缺了就退回骨骼世界矩阵
            md.binds = s.size() > 10 ? mat16Array(s.get(10)) : null;
            sc.meshes.put(id, md);
        }

        // ---------------- 贴图收集：真贴图一趟，纯色图一趟，最后补白图兜底

        void collectTextures(Map<String, byte[]> files) {
            for (Map.Entry<String, Mat> e : sc.materials.entrySet()) {
                String mk = e.getKey();
                Object tp = e.getValue().texture;
                if (tp == null || String.valueOf(tp).length() == 0) {
                    continue;
                }
                String fn = fileNameOf(String.valueOf(tp).replace("./", ""));
                String match = null;
                String any = null;
                for (String n : files.keySet()) {
                    if (!fileNameOf(n).equals(fn)) {
                        continue;
                    }
                    if (any == null) {
                        any = n;
                    }
                    if (n.indexOf('/') >= 0) {
                        match = n;
                        break;
                    }
                }
                if (match == null) {
                    match = any;
                }
                if (match == null) {
                    continue;
                }
                byte[] blob = files.get(match);
                if (blob == null || blob.length == 0) {
                    continue;
                }
                Tex t = new Tex();
                t.name = fn;
                t.blob = blob;
                t.uuid = UUID.randomUUID().toString();
                texs.put(t.uuid, t);
                matTex.put(mk, t.uuid);
            }
            for (Map.Entry<String, Mat> e : sc.materials.entrySet()) {
                String mk = e.getKey();
                if (matTex.containsKey(mk)) {
                    continue;
                }
                Object col = e.getValue().color;
                Map<String, Object> cm = col instanceof Map ? MiniJson.mapOf(col) : null;
                int r = 255, g = 255, b = 255;
                if (cm != null) {
                    r = clamp8((int) Math.round(num(cm.get("r")) * 255));
                    g = clamp8((int) Math.round(num(cm.get("g")) * 255));
                    b = clamp8((int) Math.round(num(cm.get("b")) * 255));
                }
                String key = String.format(Locale.ROOT, "solid_%02x%02x%02x", r, g, b);
                String tu = matTex.get(key);
                if (tu == null) {
                    Tex t = new Tex();
                    t.name = key + ".png";
                    t.blob = solidPng(r, g, b);
                    t.uuid = UUID.randomUUID().toString();
                    texs.put(t.uuid, t);
                    tu = t.uuid;
                    matTex.put(key, tu);
                }
                matTex.put(mk, tu);
            }
            if (!matTex.containsKey("solid_ffffff")) {
                Tex t = new Tex();
                t.name = "solid_ffffff.png";
                t.blob = solidPng(255, 255, 255);
                t.uuid = UUID.randomUUID().toString();
                texs.put(t.uuid, t);
                matTex.put("solid_ffffff", t.uuid);
            }
        }

        // ---------------- 子树过滤：命中名字的整条父链都留下

        void filterSubtree(String only) {
            Set<String> k = new LinkedHashSet<>();
            for (String oid : sc.order) {
                List<String> chain = new ArrayList<>();
                String cur = oid;
                while (cur != null) {
                    Obj o = sc.objects.get(cur);
                    chain.add(cur);
                    if (o == null) {
                        break;
                    }
                    cur = o.parent;
                }
                for (String c : chain) {
                    Obj o = sc.objects.get(c);
                    if (o != null && only.equals(o.name)) {
                        k.addAll(chain);
                        break;
                    }
                }
            }
            keep = k;
            List<String> left = new ArrayList<>();
            for (String oid : sc.order) {
                if (k.contains(oid)) {
                    left.add(oid);
                }
            }
            sc.order = left;
        }

        // ---------------- 变换烘焙 + 骨骼数据

        void bakeTransforms() {
            for (String oid : sc.order) {
                Obj o = sc.objects.get(oid);
                List<String> chain = new ArrayList<>();
                String cur = oid;
                // 大工程里对象多、层级深；父指针一旦成环（坏文件、编辑器残留），无上限的 while
                // 会把 App 直接挂死。给个远大于真实深度的上限，宁可截断也不要死循环。
                int guard = 0;
                while (cur != null && sc.objects.containsKey(cur) && guard++ < 4096) {
                    chain.add(cur);
                    cur = sc.objects.get(cur).parent;
                }
                double[] m = {1, 0, 0, 0, 1, 0, 0, 0, 1};
                double[] t = {0, 0, 0};
                double[] racc = {1, 0, 0, 0, 1, 0, 0, 0, 1};
                for (int i = chain.size() - 1; i >= 0; i--) {
                    Obj c = sc.objects.get(chain.get(i));
                    double[] r = eulerYXZ(c.eul);
                    double[] ms = new double[9];
                    for (int a = 0; a < 3; a++) {
                        for (int b = 0; b < 3; b++) {
                            ms[a * 3 + b] = r[a * 3 + b] * c.scl[b];
                        }
                    }
                    // 正确的合成顺序：T = T + M_parent·pos_self，M = M_parent·(R·S)_self。
                    // 写成 T = Ms·T + pos 会把父链的平移也转一遍，骨骼链一深角色就散架。
                    double[] tn = new double[3];
                    for (int a = 0; a < 3; a++) {
                        tn[a] = m[a * 3] * c.pos[0] + m[a * 3 + 1] * c.pos[1]
                                + m[a * 3 + 2] * c.pos[2] + t[a];
                    }
                    m = matMul(m, ms);
                    racc = matMul(racc, r);
                    t = tn;
                }
                o.worldM = m;
                o.worldT = t;
                o.worldR = racc;
                if (opt.rig) {
                    // 骨骼版只要关节位置：顶点已经烘成世界坐标，组旋转恒为 0，
                    // 于是「加载即与静态版重合，转某根骨时子部件绕关节摆动」。
                    o.rigOrigin = new double[]{t[0], t[1], t[2]};
                }
            }
            // 第二趟：蒙皮部件的真正关节在**骨骼**上，不在对象自己的 PTransform 上 ——
            // 对象那层往往只是绑定空间里的占位，拿它当轴心会让整块绕一个空点转。
            // 取权重和最大的那根骨骼（"主导骨骼"）的世界位置当轴心。
            // 必须等第一趟把所有对象的 worldT 都算完，所以单独一趟。
            if (opt.rig) {
                for (String oid : sc.order) {
                    Obj o = sc.objects.get(oid);
                    if (!o.skinned || o.mesh == null || o.skinBones.isEmpty()) {
                        // 非蒙皮部件：轴心用对象自己的 origin，但若它离网格质心太远
                        // （实测手臂的 origin 落在脚底，离手臂 1.87），就改用质心 ——
                        // 宁可绕自己中心转，也不要绕一个两米外的空点甩。
                        if (o.mesh != null && o.rigOrigin != null) {
                            MeshData m2 = sc.meshes.get(o.mesh);
                            if (m2 != null && m2.verts.length > 0) {
                                double cx = 0, cy = 0, cz = 0;
                                for (int i = 0; i < m2.verts.length; i++) {
                                    double[] w = worldVertex(m2, o, i);
                                    cx += w[0];
                                    cy += w[1];
                                    cz += w[2];
                                }
                                int n = m2.verts.length;
                                cx /= n;
                                cy /= n;
                                cz /= n;
                                double dx = o.rigOrigin[0] - cx, dy = o.rigOrigin[1] - cy, dz = o.rigOrigin[2] - cz;
                                if (dx * dx + dy * dy + dz * dz > 1.0) {
                                    o.rigOrigin = new double[]{cx, cy, cz};
                                }
                            }
                        }
                        continue;
                    }
                    MeshData md = sc.meshes.get(o.mesh);
                    if (md == null || md.skin == null || md.skin.length == 0) {
                        continue;
                    }
                    double[] acc = new double[o.skinBones.size()];
                    for (double[] s : md.skin) {
                        for (int k = 0; k < 4; k++) {
                            int bi = (int) s[k];
                            double w = s[4 + k];
                            if (w > 0 && bi >= 0 && bi < acc.length) {
                                acc[bi] += w;
                            }
                        }
                    }
                    int best = 0;
                    for (int i = 1; i < acc.length; i++) {
                        if (acc[i] > acc[best]) {
                            best = i;
                        }
                    }
                    Obj bone = sc.objects.get(o.skinBones.get(best));
                    if (bone != null) {
                        o.rigOrigin = new double[]{bone.worldT[0], bone.worldT[1], bone.worldT[2]};
                    }
                    // 只用"权重最大的骨骼"当轴心还不够：一个部件的权重常常摊在几根骨上，
                    // 权重最大的那根可能是很靠上的根骨（实测手臂的轴心落到了脚底 y=0.74）。
                    // 改成：先算蒙皮后的质心，再在"真正影响这块网格的骨骼"里挑离质心最近的那根 ——
                    // 这样轴心一定贴着这块肉，转骨时是绕着关节摆，不是绕着一个空点甩。
                    if (md.verts.length > 0) {
                        double cx = 0, cy = 0, cz = 0;
                        for (int i = 0; i < md.verts.length; i++) {
                            double[] w = worldVertex(md, o, i);
                            cx += w[0];
                            cy += w[1];
                            cz += w[2];
                        }
                        int n = md.verts.length;
                        cx /= n;
                        cy /= n;
                        cz /= n;
                        double bestD = -1;
                        Obj near = null;
                        for (int i = 0; i < acc.length; i++) {
                            if (acc[i] <= 0) {
                                continue;
                            }
                            Obj b = sc.objects.get(o.skinBones.get(i));
                            if (b == null) {
                                continue;
                            }
                            double dx = b.worldT[0] - cx, dy = b.worldT[1] - cy, dz = b.worldT[2] - cz;
                            double d = dx * dx + dy * dy + dz * dz;
                            if (bestD < 0 || d < bestD) {
                                bestD = d;
                                near = b;
                            }
                        }
                        if (near != null) {
                            o.rigOrigin = new double[]{near.worldT[0], near.worldT[1], near.worldT[2]};
                        }
                    }
                }
            }
        }

        // ---------------- 层级输出

        /** 先递归孩子再发自己的网格（elements 后序，同 Python）。空组返回 null。 */
        String emitGroup(String oid) {
            return emitGroup(oid, 0);
        }

        /**
         * @param depth 层级深度。超大型工程的父链可能有几百层，递归写死会栈溢出；
         *              超过上限就只发这一层、不再往下走（宁可少几层嵌套，也不要整包转换崩掉）。
         */
        String emitGroup(String oid, int depth) {
            if (depth > 512) {
                return null;
            }
            Obj o = sc.objects.get(oid);
            st[0]++;
            List<String> kids = new ArrayList<>();
            for (String c : o.children) {
                if (!sc.objects.containsKey(c) || (keep != null && !keep.contains(c))) {
                    continue;
                }
                String cj = emitGroup(c, depth + 1);
                if (cj != null) {
                    kids.add(cj);
                }
            }
            boolean hasMeshId = o.mesh != null && o.mesh.length() > 0;
            List<String> children = new ArrayList<>();
            if (hasMeshId && sc.meshes.containsKey(o.mesh) && (keep == null || keep.contains(oid))) {
                String elUuid = emitMeshElement(o);
                if (elUuid != null) {
                    children.add("\"" + elUuid + "\"");
                }
            }
            children.addAll(kids);
            if (children.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"name\":\"").append(jsonEsc(o.name)).append('"');
            if (opt.rig) {
                // 骨骼版：每个组只带**关节位置**（世界平移），旋转恒为 0。
                // 顶点已经是世界坐标，所以零旋转下整棵树与静态版逐点相同；
                // 旋转某个组时，子部件绕这个关节摆动 —— 这才是 Blockbench 认的骨骼。
                // rigPivot=false 时连轴心都不写（全 0），用于 Blockbench 把 origin 当位移的版本。
                sb.append(",\"origin\":").append(opt.rigPivot ? vecJson(o.rigOrigin) : "[0,0,0]")
                        .append(",\"rotation\":[0,0,0]");
            } else {
                // 顶点已烘世界坐标，而 Blockbench 把 group origin 当旋转轴心用 ——
                // 静态版不需要轴心，保持 0。
                sb.append(",\"origin\":[0,0,0]");
            }
            sb.append(",\"uuid\":\"").append(UUID.randomUUID()).append("\",\"children\":[");
            join(sb, children);
            sb.append("],\"export\":true}");
            return sb.toString();
        }

        /** 面数：优先用角点表，其次材质槽表，最后按纯三角形兜底。 */
        static int faceCount(MeshData md) {
            if (md.corners != null && md.corners.length > 0) {
                return md.corners.length;
            }
            if (md.slot != null && md.slot.length > 0) {
                return md.slot.length;
            }
            return md.indices.length / 3;
        }

        /** 第 f 个面的角点数（非法值按三角形处理）。 */
        static int cornerCount(MeshData md, int f) {
            if (md.corners != null && f >= 0 && f < md.corners.length && md.corners[f] >= 3) {
                return md.corners[f];
            }
            return 3;
        }

        /** 元素 JSON 推进 elementJsons，返回它的 uuid；没内容返回 null。 */
        String emitMeshElement(Obj o) {
            MeshData md = sc.meshes.get(o.mesh);
            if (md == null) {
                return null;
            }
            int nface = faceCount(md);
            if (opt.maxTris != null && opt.maxTris > 0) {
                int ntri = 0;
                for (int f = 0; f < nface; f++) {
                    int n = cornerCount(md, f);
                    if (n >= 3) {
                        ntri += n - 2;
                    }
                }
                if (ntri > opt.maxTris) {
                    st[6]++;
                    return null;
                }
            }
            Map<Integer, String> vmap = new LinkedHashMap<>();
            List<String> vertJson = new ArrayList<>();
            List<String> faceJson = new ArrayList<>();
            int fi = 0;
            int pos = 0;
            for (int f = 0; f < nface; f++) {
                int n = cornerCount(md, f);
                if (n < 3 || pos + n > md.indices.length) {
                    pos += Math.max(n, 0);
                    continue;
                }
                // 材质是**按面**存的，不是按角点：以前拿 corners[0] 当槽位下标，多材质工程会错。
                int slot = md.slot != null && f < md.slot.length ? md.slot[f] : 0;
                String matId = null;
                if (o.matIds != null && !o.matIds.isEmpty()) {
                    matId = slot < o.matIds.size() ? o.matIds.get(slot) : o.matIds.get(0);
                }
                String tu = matId == null ? null : matTex.get(matId);
                if (tu == null) {
                    tu = matTex.get("solid_ffffff");
                }
                if (matId == null) {
                    st[5]++;
                }
                if (tu != null) {
                    usedTex.add(tu);
                }
                // 扇形拆三角。**不反绕序**：原始索引顺序与数据里存的每面法线 100% 同向
                // （实测 2.0 样本 916 个面全部一致），反了等于把模型翻成内表面。
                for (int k = 1; k + 1 < n; k++) {
                    int c0 = pos, c1 = pos + k, c2 = pos + k + 1;
                    int[] corners = {c0, c1, c2};
                    String[] vkeys = new String[3];
                    Map<String, double[]> uvFace = new LinkedHashMap<>();
                    for (int q = 0; q < 3; q++) {
                        int ci = corners[q];
                        vkeys[q] = gv(vmap, vertJson, md, o, md.indices[ci]);
                        double u = ci < md.uvs.length ? md.uvs[ci][0] : 0;
                        double v = ci < md.uvs.length ? md.uvs[ci][1] : 0;
                        // 顶点键重复时后写覆盖 —— 与 Python 的 dict 同语义
                        uvFace.put(vkeys[q], new double[]{u, opt.uvFlip ? 1.0 - v : v});
                    }
                    StringBuilder uv = new StringBuilder(96);
                    uv.append('{');
                    boolean first = true;
                    for (Map.Entry<String, double[]> e : uvFace.entrySet()) {
                        if (!first) {
                            uv.append(',');
                        }
                        first = false;
                        uv.append('"').append(e.getKey()).append("\":[")
                                .append(r3(e.getValue()[0] * UV_RES)).append(',')
                                .append(r3(e.getValue()[1] * UV_RES)).append(']');
                    }
                    uv.append('}');
                    faceJson.add("\"f" + fi + "\":{\"uv\":" + uv + ",\"vertices\":[\""
                            + vkeys[0] + "\",\"" + vkeys[1] + "\",\"" + vkeys[2] + "\"],\"texture\":"
                            + (tu == null ? "null" : "\"" + tu + "\"") + "}");
                    fi++;
                    st[2]++;
                }
                pos += n;
            }
            st[1]++;
            st[3] += vmap.size();
            String uuid = UUID.randomUUID().toString();
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\"name\":\"").append(jsonEsc(o.name)).append("\",\"color\":0,");
            // 顶点是世界坐标，元素自身不需要轴心：旋转由所属组承担。
            sb.append("\"origin\":[0,0,0],\"rotation\":[0,0,0],");
            sb.append("\"vertices\":{");
            join(sb, vertJson);
            sb.append("},\"faces\":{");
            join(sb, faceJson);
            sb.append("},\"type\":\"mesh\",\"uuid\":\"").append(uuid).append("\"}");
            elementJsons.add(sb.toString());
            return uuid;
        }

        String gv(Map<Integer, String> vmap, List<String> vertJson, MeshData md, Obj o, int vi) {
            String k = vmap.get(vi);
            if (k != null) {
                return k;
            }
            double[] p = worldVertex(md, o, vi);
            // 左手 Y-up → 右手 Y-up：z 取反。
            double x = p[0], y = p[1], z = p[2];
            k = "v" + vmap.size();
            vmap.put(vi, k);
            vertJson.add("\"" + k + "\":[" + r5(x) + "," + r5(y) + "," + r5(z) + "]");
            return k;
        }

        /**
         * 顶点在世界里的位置。
         *
         * <p>**蒙皮网格必须逐点按权重走骨骼矩阵**：这类工程的顶点是存在骨骼空间里的，
         * 只用对象自己的矩阵会把所有部件留在绑定位置——头发炸成一团球、四肢贴在原点，
         * 看起来就是"整包散架 / 位移"。非蒙皮网格才用对象的累积矩阵。
         */
        /**
         * 骨骼世界矩阵 × bindpose，得到一个 3×4 仿射矩阵（行主序 12 个数）。
         *
         * <p>bindpose 是**列主序**的 4×4：第 j 列第 k 行 = {@code b[j*4+k]}，平移在 b[12..14]。
         * 所以乘积的每个元素都是一次点积，不需要真的展开 4×4：
         * {@code M[i][j] = Σ_k W[i][k] · b[j*4+k]}。
         *
         * <p>少了这一步（只乘骨骼世界矩阵），顶点会留在绑定空间里 —— 头发炸成尖刺、
         * 头飘到身体外面，就是这么来的。
         */
        double[] skinMatrix(Obj bone, double[] b) {
            double[] w = {
                bone.worldM[0], bone.worldM[1], bone.worldM[2], bone.worldT[0],
                bone.worldM[3], bone.worldM[4], bone.worldM[5], bone.worldT[1],
                bone.worldM[6], bone.worldM[7], bone.worldM[8], bone.worldT[2],
                0, 0, 0, 1,
            };
            double[] m = new double[12];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 4; j++) {
                    double s = 0;
                    for (int k = 0; k < 4; k++) {
                        s += w[i * 4 + k] * b[j * 4 + k];
                    }
                    m[i * 4 + j] = s;
                }
            }
            return m;
        }

        double[] worldVertex(MeshData md, Obj o, int vi) {
            double[] p = md.verts[vi];
            if (o.skinned && md.skin != null && vi < md.skin.length && !o.skinBones.isEmpty()) {
                double[] s = md.skin[vi];
                double ax = 0, ay = 0, az = 0, tw = 0;
                Obj first = null;
                double[] firstM = null;
                for (int k = 0; k < 4; k++) {
                    int bi = (int) s[k];
                    double w = s[4 + k];
                    if (bi < 0 || bi >= o.skinBones.size()) {
                        continue;
                    }
                    Obj bone = sc.objects.get(o.skinBones.get(bi));
                    if (bone == null) {
                        continue;
                    }
                    double[] m = (md.binds != null && bi < md.binds.length)
                            ? skinMatrix(bone, md.binds[bi])
                            : null;
                    if (m == null) {
                        // 没有 bindpose 的工程（3.0 分包）退回骨骼世界矩阵
                        m = new double[]{
                            bone.worldM[0], bone.worldM[1], bone.worldM[2], bone.worldT[0],
                            bone.worldM[3], bone.worldM[4], bone.worldM[5], bone.worldT[1],
                            bone.worldM[6], bone.worldM[7], bone.worldM[8], bone.worldT[2],
                        };
                    }
                    if (first == null) {
                        first = bone;
                        firstM = m;
                    }
                    if (w == 0) {
                        continue;
                    }
                    ax += (m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3]) * w;
                    ay += (m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7]) * w;
                    az += (m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]) * w;
                    tw += w;
                }
                if (tw > 0) {
                    return new double[]{ax / tw, ay / tw, az / tw};
                }
                // 权重全 0（数据坏了）：贴到第一根有效骨骼上。绝不能退回对象矩阵 ——
                // 那是绑定空间里的占位，退回去这一块就飞出去了。
                if (firstM != null) {
                    return new double[]{
                        firstM[0] * p[0] + firstM[1] * p[1] + firstM[2] * p[2] + firstM[3],
                        firstM[4] * p[0] + firstM[5] * p[1] + firstM[6] * p[2] + firstM[7],
                        firstM[8] * p[0] + firstM[9] * p[1] + firstM[10] * p[2] + firstM[11],
                    };
                }
            }
            double[] m = o.worldM;
            double[] t = o.worldT;
            return new double[]{
                m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + t[0],
                m[3] * p[0] + m[4] * p[1] + m[5] * p[2] + t[1],
                m[6] * p[0] + m[7] * p[1] + m[8] * p[2] + t[2],
            };
        }

        // ---------------- 灯光 → locator 元素 + 根字段里的完整参数

        void emitLights() {
            for (String oid : sc.order) {
                Obj o = sc.objects.get(oid);
                if (o == null || o.light == null) {
                    continue;
                }
                Map<String, Object> lt = o.light;
                Object col = parseJson(lt.get("color"));
                Map<String, Object> cm = col instanceof Map ? MiniJson.mapOf(col) : null;
                String hexc = String.format(Locale.ROOT, "#%02x%02x%02x",
                        clamp8((int) Math.round(channel(cm, "r") * 255)),
                        clamp8((int) Math.round(channel(cm, "g") * 255)),
                        clamp8((int) Math.round(channel(cm, "b") * 255)));
                String ltype = lt.containsKey("lightType") ? str(lt.get("lightType")) : "Light";
                String inten = lt.containsKey("intensity") ? str(lt.get("intensity")) : "?";
                double[] t = o.worldT;
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"type\":\"locator\",\"name\":\"")
                        .append(jsonEsc("💡" + ltype + " 强度" + inten + " " + hexc))
                        .append("\",\"position\":[").append(r4(t[0])).append(',')
                        .append(r4(t[1])).append(',').append(r4(-t[2])).append("],\"uuid\":\"")
                        .append(UUID.randomUUID()).append("\"}");
                elementJsons.add(sb.toString());

                StringBuilder rec = new StringBuilder(256);
                rec.append("{\"name\":\"").append(jsonEsc(o.name)).append("\",\"type\":\"")
                        .append(jsonEsc(ltype)).append("\",\"color\":");
                jsonOf(col, rec);
                scalar(rec, "intensity", lt, "intensity");
                // 记录里的键是小写的（同 Python），但取值要按 P3D 属性原名（Range/Angle/Shadow）查
                scalar(rec, "range", lt, "Range");
                scalar(rec, "angle", lt, "Angle");
                scalar(rec, "shadow", lt, "Shadow");
                rec.append(",\"position\":[").append(Double.toString(t[0])).append(',')
                        .append(Double.toString(t[1])).append(',')
                        .append(Double.toString(t[2])).append("]}");
                lightJsons.add(rec.toString());
                st[4]++;
            }
        }

        void scalar(StringBuilder sb, String outKey, Map<String, Object> lt, String propKey) {
            sb.append(",\"").append(outKey).append("\":");
            jsonOf(lt.containsKey(propKey) ? lt.get(propKey) : null, sb);
        }
    }

    static double channel(Map<String, Object> cm, String key) {
        return cm == null ? 0 : num(cm.get(key));
    }

    // ==================================================================== 输出与数值工具

    static void join(StringBuilder sb, List<String> parts) {
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
    }

    /** Blockbench 世界系：z 取负。 */
    static String vecJson(double[] t) {
        if (t == null) {
            return "[0,0,0]";
        }
        return "[" + r5(t[0]) + "," + r5(t[1]) + "," + r5(t[2]) + "]";
    }

    /** 不做四舍五入的一组数（骨骼欧拉用，同 Python 直接写 float）。 */
    static String numsJson(double[] v) {
        StringBuilder sb = new StringBuilder(64).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Double.toString(v[i]));
        }
        return sb.append(']').toString();
    }

    static String r3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String r4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    static String r5(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static double clampRange(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    /** P3D 属性里那些「值本身是一段 JSON 文本」的字段。 */
    static Object parseJson(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Map || o instanceof List) {
            return o;
        }
        return MiniJson.parseSafe(String.valueOf(o));
    }

    /** 对应 Python 的 {@code if p:} —— 不是字典、或空字典，都算没给。 */
    static double[] vec(Object jsonish) {
        Object v = parseJson(jsonish);
        if (!(v instanceof Map)) {
            return null;
        }
        Map<String, Object> mv = MiniJson.mapOf(v);
        if (mv.isEmpty()) {
            return null;
        }
        return new double[]{num(mv.get("x")), num(mv.get("y")), num(mv.get("z"))};
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static List<Object> orEmpty(List<Object> l) {
        return l == null ? Collections.emptyList() : l;
    }

    @SuppressWarnings("unchecked")
    static List<Object> listOf(Object o) throws IOException {
        if (!(o instanceof List)) {
            throw new IOException("预期的 MessagePack 顶层不是数组");
        }
        return (List<Object>) o;
    }

    static Map<String, Object> propsMap(List<Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object pp : orEmpty(props)) {
            List<Object> pr = asList(pp);
            if (pr != null && pr.size() > 1) {
                m.put(str(pr.get(0)), pr.get(1));
            }
        }
        return m;
    }

    static int[] intArray(Object o) {
        List<Object> l = asList(o);
        if (l == null) {
            return null;
        }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = (int) num(l.get(i));
        }
        return a;
    }

    static double[][] vec2Array(Object o) {
        List<Object> l = asList(o);
        if (l == null) {
            return new double[0][2];
        }
        double[][] a = new double[l.size()][2];
        for (int i = 0; i < a.length; i++) {
            List<Object> v = asList(l.get(i));
            if (v == null) {
                continue;
            }
            a[i][0] = num(v.get(0));
            a[i][1] = v.size() > 1 ? num(v.get(1)) : 0;
        }
        return a;
    }

    /** 每顶点 8 个数的蒙皮数组：4 个骨骼下标 + 4 个权重。 */
    static double[][] vec8Array(Object o) {
        List<Object> l = asList(o);
        if (l == null || l.isEmpty()) {
            return null;
        }
        double[][] a = new double[l.size()][8];
        for (int i = 0; i < a.length; i++) {
            List<Object> v = asList(l.get(i));
            if (v == null) {
                continue;
            }
            for (int k = 0; k < 8 && k < v.size(); k++) {
                a[i][k] = num(v.get(k));
            }
        }
        return a;
    }

    /** 16 个一组的矩阵数组（bindpose）。 */
    static double[][] mat16Array(Object o) {
        List<Object> l = asList(o);
        if (l == null || l.isEmpty()) {
            return null;
        }
        double[][] a = new double[l.size()][16];
        for (int i = 0; i < a.length; i++) {
            List<Object> v = asList(l.get(i));
            if (v == null) {
                continue;
            }
            for (int k = 0; k < 16 && k < v.size(); k++) {
                a[i][k] = num(v.get(k));
            }
            if (a[i][15] == 0) {
                a[i][15] = 1;
            }
        }
        return a;
    }

    static double[][] vec3Array(Object o) {        List<Object> l = asList(o);
        if (l == null) {
            return null;
        }
        double[][] a = new double[l.size()][3];
        for (int i = 0; i < a.length; i++) {
            List<Object> v = asList(l.get(i));
            if (v == null) {
                continue;
            }
            for (int k = 0; k < 3 && k < v.size(); k++) {
                a[i][k] = num(v.get(k));
            }
        }
        return a;
    }

    static String fileNameOf(String p) {
        String s = p.replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        int j = path.lastIndexOf('.');
        return j > i ? path.substring(i + 1, j) : path.substring(i + 1);
    }

    static String jsonEsc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** 把 msgpack / JSON 解出来的任意值原样写成 JSON（环境光、灯光参数这些根字段要用）。 */
    static StringBuilder jsonOf(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(Double.toString(((Number) v).doubleValue()));
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(jsonEsc(String.valueOf(e.getKey()))).append("\":");
                jsonOf(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                jsonOf(x, sb);
            }
            sb.append(']');
        } else {
            sb.append('"').append(jsonEsc(String.valueOf(v))).append('"');
        }
        return sb;
    }

    // ==================================================================== 容错 ZIP

    /** 走中央目录定位每个条目，对 3.0 首成员被改坏的魔数免疫。 */
    static Map<String, byte[]> extractZipTolerant(byte[] data) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int eocd = -1;
        for (int i = data.length - 22; i >= 0 && i > data.length - 66000; i--) {
            if (data[i] == 'P' && data[i + 1] == 'K' && data[i + 2] == 5 && data[i + 3] == 6) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("这个文件里没有 ZIP 中央目录，不是 .prisma 工程包");
        }
        int cdCount = MsgP.u32s16(data, eocd + 10);
        int p = (int) MsgP.u32s(data, eocd + 16);
        for (int n = 0; n < cdCount; n++) {
            if (MsgP.u32s(data, p) != 0x02014b50L) {
                break;
            }
            int method = MsgP.u32s16(data, p + 10);
            int csize = (int) MsgP.u32s(data, p + 20);
            int nameLen = MsgP.u32s16(data, p + 28);
            int extraLen = MsgP.u32s16(data, p + 30);
            int commLen = MsgP.u32s16(data, p + 32);
            int lho = (int) MsgP.u32s(data, p + 42);
            String name = new String(data, p + 46, nameLen, StandardCharsets.UTF_8);
            byte[] content = readEntry(data, lho, name, method, csize);
            if (content != null) {
                out.put(name, content);
            }
            p += 46 + nameLen + extraLen + commLen;
        }
        if (out.isEmpty()) {
            throw new IOException("中央目录里一个条目都读不出来");
        }
        return out;
    }

    /** 按本地头取数据起点；魔数被改坏时退回中央目录已知的名字长度。 */
    static byte[] readEntry(byte[] data, int lho, String name, int method, int csize) {
        try {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int nlen = MsgP.u32s16(data, lho + 26);
            int elen = MsgP.u32s16(data, lho + 28);
            if (nlen != nameBytes.length || (long) lho + 30 + nlen + elen > data.length) {
                nlen = nameBytes.length;
                elen = 0;
            }
            int dstart = lho + 30 + nlen + elen;
            int dend = (int) Math.min(data.length, (long) dstart + Math.max(csize, 0));
            if (method != 0 && dend <= dstart) {
                dend = data.length;
            }
            byte[] src = Arrays.copyOfRange(data, dstart, dend);
            if (method == 0) {
                return src;
            }
            if (method == 8) {
                Inflater inf = new Inflater(true);
                inf.setInput(src);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, src.length * 4));
                byte[] buf = new byte[65536];
                while (!inf.finished()) {
                    int r = inf.inflate(buf);
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) {
                        break;
                    }
                    bos.write(buf, 0, r);
                }
                inf.end();
                return bos.toByteArray();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================================================================== MessagePack

    /** 极简 msgpack 解码，覆盖 P3D 用到的全部类型。bin 与 str 都按 UTF-8 文本读（同 Python raw=False）。 */
    static final class MsgP {
        final byte[] d;
        int p;

        MsgP(byte[] d) {
            this.d = d;
        }

        /** 跳过 P3D 每个数据流开头的 4 字节前缀，读出那一个顶层值。 */
        static Object skip4(byte[] d) throws IOException {
            return new MsgP(Arrays.copyOfRange(d, 4, d.length)).next();
        }

        Object next() throws IOException {
            int b = d[p++] & 0xff;
            if (b < 0x80) {
                return (long) b;
            }
            if (b >= 0xe0) {
                return (long) (b - 0x100);
            }
            switch (b & 0xf0) {
                case 0x80:
                    return map(b & 0x0f);
                case 0x90:
                    return arr(b & 0x0f);
                case 0xa0:
                case 0xb0:
                    return str(b & 0x1f);
                default:
                    break;
            }
            switch (b) {
                case 0xc0:
                    return null;
                case 0xc2:
                    return Boolean.FALSE;
                case 0xc3:
                    return Boolean.TRUE;
                case 0xc4: {
                    int n = u8();
                    return strRaw(p, n);
                }
                case 0xc5: {
                    int n = u16();
                    return strRaw(p, n);
                }
                case 0xc6: {
                    int n = (int) u32(p);
                    p += 4;
                    return strRaw(p, n);
                }
                case 0xca: {
                    float f = Float.intBitsToFloat((int) u32(p));
                    p += 4;
                    return (double) f;
                }
                case 0xcb: {
                    long v = u64(p);
                    p += 8;
                    return Double.longBitsToDouble(v);
                }
                case 0xcc:
                    return (long) u8();
                case 0xcd:
                    return (long) u16();
                case 0xce: {
                    long v = u32(p);
                    p += 4;
                    return v;
                }
                case 0xcf: {
                    long v = u64(p);
                    p += 8;
                    return v;
                }
                case 0xd0:
                    return (long) (byte) u8();
                case 0xd1:
                    return (long) (short) u16();
                case 0xd2: {
                    int v = (int) u32(p);
                    p += 4;
                    return (long) v;
                }
                case 0xd3: {
                    long v = u64(p);
                    p += 8;
                    return v;
                }
                case 0xd9: {
                    int n = u8();
                    return strRaw(p, n);
                }
                case 0xda: {
                    int n = u16();
                    return strRaw(p, n);
                }
                case 0xdb: {
                    int n = (int) u32(p);
                    p += 4;
                    return strRaw(p, n);
                }
                case 0xdc:
                    return arr(u16());
                case 0xdd: {
                    int n = (int) u32(p);
                    p += 4;
                    return arr(n);
                }
                case 0xde:
                    return map(u16());
                case 0xdf: {
                    int n = (int) u32(p);
                    p += 4;
                    return map(n);
                }
                default:
                    throw new IOException(String.format(Locale.ROOT,
                            "msgpack 里遇到不支持的类型 0x%02x（偏移 %d）", b, p - 1));
            }
        }

        List<Object> arr(int n) throws IOException {
            List<Object> l = new ArrayList<>(Math.min(Math.max(n, 0), 1 << 20));
            for (int i = 0; i < n; i++) {
                l.add(next());
            }
            return l;
        }

        Map<String, Object> map(int n) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Object k = next();
                m.put(k == null ? "null" : k.toString(), next());
            }
            return m;
        }

        int u8() {
            return d[p++] & 0xff;
        }

        int u16() {
            int v = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
            p += 2;
            return v;
        }

        long u32(int at) {
            return u32be(d, at);
        }

        long u64(int at) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[at + i] & 0xff);
            }
            return v;
        }

        String str(int n) {
            String s = new String(d, p, n, StandardCharsets.UTF_8);
            p += n;
            return s;
        }

        String strRaw(int at, int n) {
            p = at + n;
            return new String(d, at, n, StandardCharsets.UTF_8);
        }

        static int u32s16(byte[] d, int at) {
            return (d[at] & 0xff) | ((d[at + 1] & 0xff) << 8);
        }

        static long u32s(byte[] d, int at) {
            return (d[at] & 0xffL) | ((d[at + 1] & 0xffL) << 8)
                    | ((d[at + 2] & 0xffL) << 16) | ((d[at + 3] & 0xffL) << 24);
        }
    }

    static long u32be(byte[] d, int at) {
        return ((long) (d[at] & 0xff) << 24) | ((long) (d[at + 1] & 0xff) << 16)
                | ((long) (d[at + 2] & 0xff) << 8) | (d[at + 3] & 0xff);
    }

    // ==================================================================== 变换

    static double[] matMul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    /**
     * 欧拉角（度）→ 旋转矩阵，XYZ 顺序、角度不取反。
     *
     * <p>这是拿工程自带缩略图当判据标定出来的：同一份 `xk.prisma`，用 XYZ 时角色是站立的、
     * 双臂贴身；用 {@code Ry(-x)Rx(-y)Rz(-z)} 时左臂会甩到两米开外。OBJ 出口（`P3DToObj`）
     * 一直用的是这一套，两边必须一致，否则同一个工程导出的 OBJ 和 BB 姿态不同。
     */
    static double[] eulerYXZ(double[] e) {
        double rx = Math.toRadians(e[0]), ry = Math.toRadians(e[1]), rz = Math.toRadians(e[2]);
        double cx = Math.cos(rx / 2), sx = Math.sin(rx / 2);
        double cy = Math.cos(ry / 2), sy = Math.sin(ry / 2);
        double cz = Math.cos(rz / 2), sz = Math.sin(rz / 2);
        double qw = cx * cy * cz - sx * sy * sz;
        double qx = sx * cy * cz - cx * sy * sz;
        double qy = cx * sy * cz + sx * cy * sz;
        double qz = cx * cy * sz + sx * sy * cz;
        return new double[]{
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        };
    }

    /**
     * 旋转矩阵 → Blockbench 骨骼的 ZYX 欧拉（度）。
     * 关键是<b>先对矩阵做 z 镜像共轭（M·R·M）再分解</b>；分解完再取反分量并不等价，这个坑上游踩过。
     */
    static double[] eulerZyxForBone(double[] r) {
        double[] m = {1, 1, -1};
        double[] rm = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rm[i * 3 + j] = r[i * 3 + j] * m[i] * m[j];
            }
        }
        double b = -Math.asin(clampRange(rm[6]));
        double a = Math.atan2(rm[3], rm[0]);
        double c = Math.atan2(rm[7], rm[8]);
        return new double[]{Math.toDegrees(c), Math.toDegrees(b), Math.toDegrees(a)};
    }

    // ==================================================================== 纯色 PNG（安卓可用）

    /**
     * 16×16 纯色 PNG。上游 Java 版用 ImageIO，安卓没有 javax.imageio；这里按 PNG 规范手写
     * IHDR/IDAT/IEND，与 Python 的 {@code zlib.compress(raw, 9)} 同构（解出来像素一致）。
     */
    static byte[] solidPng(int r, int g, int b) {
        final int size = 16;
        int rgb = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
        byte[] raw = new byte[size * (1 + size * 3)];
        int at = 0;
        for (int y = 0; y < size; y++) {
            raw[at++] = 0;                                    // 每行的 filter 字节：None
            for (int x = 0; x < size; x++) {
                raw[at++] = (byte) ((rgb >> 16) & 0xff);
                raw[at++] = (byte) ((rgb >> 8) & 0xff);
                raw[at++] = (byte) (rgb & 0xff);
            }
        }
        Deflater def = new Deflater(9);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            body.write(buf, 0, def.deflate(buf));
        }
        def.end();
        byte[] ihdr = {
                0, 0, 0, (byte) size,
                0, 0, 0, (byte) size,
                8, 2, 0, 0, 0
        };
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        png.write(magic, 0, magic.length);
        writeChunk(png, "IHDR", ihdr);
        writeChunk(png, "IDAT", body.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    static void writeChunk(ByteArrayOutputStream out, String tag, byte[] data) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        int len = data.length;
        out.write(len >>> 24);
        out.write(len >>> 16);
        out.write(len >>> 8);
        out.write(len);
        out.write(t, 0, t.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long v = crc.getValue();
        out.write((int) (v >>> 24));
        out.write((int) (v >>> 16));
        out.write((int) (v >>> 8));
        out.write((int) v);
    }

    /** 只有真 PNG 才有尺寸；JPEG 返回 -1，输出成 null（与 Python 的 None 一致）。 */
    static int[] pngSize(byte[] b) {
        if (b != null && b.length >= 24 && (b[0] & 0xff) == 0x89
                && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R') {
            return new int[]{(int) u32be(b, 16), (int) u32be(b, 20)};
        }
        return new int[]{-1, -1};
    }

    // ==================================================================== 迷你 JSON

    /** 只用来解析 P3D 属性里那些小 JSON（向量、颜色、网格引用）。 */
    static final class MiniJson {
        private final String s;
        private int p;

        MiniJson(String s) {
            this.s = s == null || s.length() == 0 ? "null" : s;
        }

        static Object parseSafe(String s) {
            try {
                return new MiniJson(s).parse();
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> mapOf(Object o) {
            return (Map<String, Object>) o;
        }

        Object parse() {
            skip();
            return val();
        }

        void skip() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
        }

        Object val() {
            skip();
            if (p >= s.length()) {
                return null;
            }
            char c = s.charAt(p);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                p++;
                skip();
                if (p < s.length() && s.charAt(p) == '}') {
                    p++;
                    return m;
                }
                while (p < s.length()) {
                    skip();
                    String k = readStr();
                    skip();
                    expect(':');
                    m.put(k, val());
                    skip();
                    if (p < s.length() && s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    expect('}');
                    break;
                }
                return m;
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                p++;
                skip();
                if (p < s.length() && s.charAt(p) == ']') {
                    p++;
                    return l;
                }
                while (p < s.length()) {
                    l.add(val());
                    skip();
                    if (p < s.length() && s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    expect(']');
                    break;
                }
                return l;
            }
            if (c == '"') {
                return readStr();
            }
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", p)) {
                p += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", p)) {
                p += 4;
                return null;
            }
            int st = p;
            while (p < s.length() && "-+.eE0123456789".indexOf(s.charAt(p)) >= 0) {
                p++;
            }
            String n = s.substring(st, p);
            if (n.isEmpty()) {
                p++;
                return null;
            }
            return n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0
                    ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        String readStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (p < s.length() && s.charAt(p) != '"') {
                char c = s.charAt(p++);
                if (c == '\\') {
                    char e = s.charAt(p++);
                    switch (e) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            p += 4;
                            break;
                        default:
                            sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            p++;
            return sb.toString();
        }

        void expect(char c) {
            if (p >= s.length() || s.charAt(p) != c) {
                throw new RuntimeException("JSON 期望 " + c + "，实际在偏移 " + p);
            }
            p++;
        }
    }
}
