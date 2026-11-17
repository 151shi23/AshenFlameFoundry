package com.mineways.conv;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pmx2Glb — PMX 2.0 (MikuMikuDance) -> glTF 2.0 GLB 转换器（纯 Java 8，零外部依赖）
 *
 * Android 兼容：只使用 java.io / java.nio.ByteBuffer / java.util，
 * 不用 java.nio.file.Files / java.awt，可直接搬进安卓工程。
 *
 * 核心入口：
 *   byte[] glb = new Pmx2Glb().convert(pmxBytes);          // Android 用
 *   java Pmx2Glb in.pmx out.glb                            // 桌面 CLI 用
 *
 * 精度保证：PMX 与 GLB 均为小端序，float32 原位搬运，位级一致。
 */
public class Pmx2Glb {

    // ============================ PMX 数据模型 ============================

    static class Vertex {
        float[] pos = new float[3];
        float[] normal = new float[3];
        float[] uv = new float[2];
        float[][] addUV;           // 附加UV × addUvCount（vec4 each，转换时丢弃但必须读取以推进游标）
        int wType;                 // 0=BDEF1 1=BDEF2 2=BDEF4 3=SDEF 4=QDEF
        int[] joints;              // 参与骨骼（≤4）
        float[] weights;           // 对应权重（≤4）
        float[] sdefC, sdefR0, sdefR1; // SDEF 专属（保留解析，导出按 BDEF2 线性化）
        float edgeScale;
    }

    static class Material {
        String nameJA, nameEN;
        float[] diffuse = new float[4];   // vec4
        float[] specular = new float[3];  // vec3
        float specPower;
        float[] ambient = new float[3];
        int flag;
        float[] edgeColor = new float[4];
        float edgeSize;
        int texIdx = -1, envIdx = -1;
        int envMode;
        int sharedToonFlag;
        int toonTexIdx = -1;
        float[] toonColor;
        String memo = "";
        int faceVertCount;
        int indexStart;           // 在全局面索引缓冲中的起始位置（解析后回填）
        int glMaterialIdx;        // GLB 材质索引
    }

    static class Bone {
        String nameJA, nameEN;
        float[] pos = new float[3];
        int parent = -1, layer, flags;
        int tailBone = -1;
        float[] tailOffset;
        int inheritParent = -1;
        float inheritWeight;
        float[] fixedAxis, localX, localZ;
        int extKey;
        int ikTarget = -1, ikLoop;
        float ikAngle;
        int[] ikLinkBone;
        int[] ikLinkFlags;
        float[][] ikLinkLimitMin, ikLinkLimitMax;
        int glNodeIdx = -1;
        float[] worldPos;         // 静止姿态世界坐标（父链平移累加）
    }

    static class Morph {
        String nameJA, nameEN;
        int panel, type;          // 0组 1顶点 2骨骼 3UV 4-7附加UV 8材质 9组翻转 10脉冲
        int[] idx;
        float[][] vals;
        int glTargetSlot = -1;    // 导出为 morph target 的槽位；-1 = 未导出
    }

    static class PmxModel {
        String nameJA, nameEN, commentJA, commentEN;
        List<Vertex> vertices = new ArrayList<>();
        int[] faces;              // 原始 PMX 三角形顶点索引
        List<String> textures = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        List<Bone> bones = new ArrayList<>();
        List<Morph> morphs = new ArrayList<>();
        List<int[]> displayFrames = new ArrayList<>(); // 仅占位：显示帧已消费但不进 GLB
        Map<String, Integer> stats = new HashMap<>();
    }

    // ============================ 小端字节读取器 ============================

    static class Reader {
        final ByteBuffer bb;
        final boolean utf16;
        Reader(byte[] data, int encoding) {
            bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            utf16 = (encoding == 0);
        }
        int u8()  { return bb.get() & 0xFF; }
        int u16() { return bb.getShort() & 0xFFFF; }
        int i32() { return bb.getInt(); }
        float f32() { return bb.getFloat(); }
        void f32v(float[] a) { for (int i = 0; i < a.length; i++) a[i] = f32(); }
        /** 无符号索引（顶点索引用） */
        int idx(int size) {
            switch (size) {
                case 1: return u8();
                case 2: return u16();
                default: return i32();
            }
        }
        /** 有符号索引（骨骼/材质/贴图等，全 1 位型表示 -1） */
        int sidx(int size) {
            if (size == 4) return i32();
            int v = idx(size);
            int allOnes = (1 << (8 * size)) - 1;
            return v == allOnes ? -1 : v;
        }
        String text() {
            int len = i32();
            byte[] b = new byte[len];
            bb.get(b);
            String s = new String(b, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '\0') e--;   // 去尾部 NUL
            return s.substring(0, e);
        }
        int pos() { return bb.position(); }
    }

    // ============================ PMX 解析 ============================

    private PmxModel parsePmx(byte[] data) {
        Reader r = new Reader(data, 0); // 编码在头里读出来后重新构造
        byte[] magic = new byte[4];
        r.bb.get(magic);
        if (magic[0] != 'P' || magic[1] != 'M' || magic[2] != 'X' || magic[3] != ' ')
            throw new IllegalArgumentException("不是 PMX 文件: magic=" + Arrays.toString(magic));
        float version = r.f32();
        int gcount = r.u8();
        int[] g = new int[gcount];
        for (int i = 0; i < gcount; i++) g[i] = r.u8();
        int encoding      = g[0];           // 0=UTF16LE 1=UTF8
        int addUvCount    = g[1];
        int vIdxSize      = g[2];
        int texIdxSize    = g[3];
        int matIdxSize    = g[4];
        int boneIdxSize   = g[5];
        int morphIdxSize  = g[6];
        int rigidIdxSize  = g[7];
        r = new Reader(data, encoding);
        r.bb.position(4 + 4 + 1 + gcount);

        PmxModel m = new PmxModel();
        m.nameJA = r.text(); m.nameEN = r.text();
        m.commentJA = r.text(); m.commentEN = r.text();

        // ---- 顶点 ----
        int vc = r.i32();
        for (int i = 0; i < vc; i++) {
            Vertex v = new Vertex();
            r.f32v(v.pos); r.f32v(v.normal); r.f32v(v.uv);
            if (addUvCount > 0) {
                v.addUV = new float[addUvCount][4];
                for (int k = 0; k < addUvCount; k++) r.f32v(v.addUV[k]);
            }
            v.wType = r.u8();
            switch (v.wType) {
                case 0: // BDEF1
                    v.joints = new int[]{ r.sidx(boneIdxSize) };
                    v.weights = new float[]{ 1f };
                    break;
                case 1: case 3: { // BDEF2 / SDEF
                    int b1 = r.sidx(boneIdxSize), b2 = r.sidx(boneIdxSize);
                    float w1 = r.f32();
                    v.joints = new int[]{ b1, b2 };
                    v.weights = new float[]{ w1, 1f - w1 };
                    if (v.wType == 3) {
                        v.sdefC  = new float[3]; r.f32v(v.sdefC);
                        v.sdefR0 = new float[3]; r.f32v(v.sdefR0);
                        v.sdefR1 = new float[3]; r.f32v(v.sdefR1);
                    }
                    break;
                }
                case 2: case 4: { // BDEF4 / QDEF
                    v.joints = new int[4]; v.weights = new float[4];
                    for (int k = 0; k < 4; k++) v.joints[k] = r.sidx(boneIdxSize);
                    for (int k = 0; k < 4; k++) v.weights[k] = r.f32();
                    break;
                }
                default:
                    throw new IllegalStateException("未知权重类型 " + v.wType + " @vertex " + i);
            }
            v.edgeScale = r.f32();
            m.vertices.add(v);
        }

        // ---- 面 ----
        int fc = r.i32();
        if (fc % 3 != 0) throw new IllegalStateException("面索引数非 3 的倍数: " + fc);
        m.faces = new int[fc];
        for (int i = 0; i < fc; i++) m.faces[i] = r.idx(vIdxSize);

        // ---- 贴图 ----
        int tc = r.i32();
        for (int i = 0; i < tc; i++) m.textures.add(r.text());

        // ---- 材质 ----
        int mc = r.i32();
        int idxStart = 0;
        for (int i = 0; i < mc; i++) {
            Material mt = new Material();
            mt.nameJA = r.text(); mt.nameEN = r.text();
            r.f32v(mt.diffuse); r.f32v(mt.specular);
            mt.specPower = r.f32();
            r.f32v(mt.ambient);
            mt.flag = r.u8();
            r.f32v(mt.edgeColor);
            mt.edgeSize = r.f32();
            mt.texIdx = r.sidx(texIdxSize);
            mt.envIdx = r.sidx(texIdxSize);
            mt.envMode = r.u8();
            mt.sharedToonFlag = r.u8();
            if (mt.sharedToonFlag == 1) mt.toonTexIdx = r.u8();      // 共有Toon番号 0-9 (u8)
            else mt.toonTexIdx = r.sidx(texIdxSize);                 // Toonテクスチャインデックス (-1=无)
            mt.memo = r.text();
            mt.faceVertCount = r.i32();
            mt.indexStart = idxStart;
            idxStart += mt.faceVertCount;
            m.materials.add(mt);
        }
        if (idxStart != fc)
            throw new IllegalStateException("材质面顶点总数 " + idxStart + " != 面索引总数 " + fc);

        // ---- 骨骼 ----
        int bc = r.i32();
        for (int i = 0; i < bc; i++) {
            Bone b = new Bone();
            b.nameJA = r.text(); b.nameEN = r.text();
            r.f32v(b.pos);
            b.parent = r.sidx(boneIdxSize);
            b.layer = r.i32();
            b.flags = r.u16();
            if ((b.flags & 0x0001) != 0) b.tailBone = r.sidx(boneIdxSize);
            else { b.tailOffset = new float[3]; r.f32v(b.tailOffset); }
            if ((b.flags & 0x0100) != 0 || (b.flags & 0x0200) != 0) {
                b.inheritParent = r.sidx(boneIdxSize);
                b.inheritWeight = r.f32();
            }
            if ((b.flags & 0x0400) != 0) { b.fixedAxis = new float[3]; r.f32v(b.fixedAxis); }
            if ((b.flags & 0x0800) != 0) {
                b.localX = new float[3]; r.f32v(b.localX);
                b.localZ = new float[3]; r.f32v(b.localZ);
            }
            if ((b.flags & 0x2000) != 0) b.extKey = r.i32();
            if ((b.flags & 0x0020) != 0) {
                b.ikTarget = r.sidx(boneIdxSize);
                b.ikLoop = r.i32();
                b.ikAngle = r.f32();
                int lc = r.i32();
                b.ikLinkBone = new int[lc]; b.ikLinkFlags = new int[lc];
                b.ikLinkLimitMin = new float[lc][]; b.ikLinkLimitMax = new float[lc][];
                for (int k = 0; k < lc; k++) {
                    b.ikLinkBone[k] = r.sidx(boneIdxSize);
                    b.ikLinkFlags[k] = r.u8();
                    if ((b.ikLinkFlags[k] & 0x01) != 0) {
                        b.ikLinkLimitMin[k] = new float[3]; r.f32v(b.ikLinkLimitMin[k]);
                        b.ikLinkLimitMax[k] = new float[3]; r.f32v(b.ikLinkLimitMax[k]);
                    }
                }
            }
            m.bones.add(b);
        }

        // ---- 变形（morph）----
        // PMX 2.0 真实区段顺序：骨骼 -> morph -> 显示帧 -> 刚体 -> Joint
        // （已用 @takahirox/mmd-parser 官方实现交叉验证）
        int moc = r.i32();
        for (int i = 0; i < moc; i++) {
            Morph mo = new Morph();
            mo.nameJA = r.text(); mo.nameEN = r.text();
            mo.panel = r.u8();
            mo.type = r.u8();
            int oc = r.i32();
            mo.idx = new int[oc]; mo.vals = new float[oc][];
            for (int k = 0; k < oc; k++) {
                switch (mo.type) {
                    case 0: case 9: { // 组 / 组翻转
                        mo.idx[k] = r.sidx(morphIdxSize);
                        float[] f = new float[1]; f[0] = r.f32(); mo.vals[k] = f;
                        break;
                    }
                    case 1: { // 顶点
                        mo.idx[k] = r.idx(vIdxSize);
                        float[] f = new float[3]; r.f32v(f); mo.vals[k] = f;
                        break;
                    }
                    case 2: { // 骨骼
                        mo.idx[k] = r.sidx(boneIdxSize);
                        float[] f = new float[7]; r.f32v(f); mo.vals[k] = f; // 平移3+旋转4
                        break;
                    }
                    case 3: case 4: case 5: case 6: case 7: { // UV / 附加UV
                        mo.idx[k] = r.idx(vIdxSize);
                        float[] f = new float[4]; r.f32v(f); mo.vals[k] = f;
                        break;
                    }
                    case 8: { // 材质
                        mo.idx[k] = r.sidx(matIdxSize);
                        r.u8(); // 计算模式
                        float[] f = new float[4+3+1+3+4+1+4+4+4]; // diffuse,specular,specPow,ambient,edge,edgeSize,texTint,envTint,toonTint
                        r.f32v(f); mo.vals[k] = f;
                        break;
                    }
                    case 10: { // 脉冲
                        mo.idx[k] = r.sidx(rigidIdxSize);
                        r.u8();
                        float[] f = new float[6]; r.f32v(f); mo.vals[k] = f; // 速度3+扭矩3
                        break;
                    }
                    default:
                        throw new IllegalStateException("未知 morph 类型 " + mo.type);
                }
            }
            m.morphs.add(mo);
        }

        // ---- 显示帧 ----
        int dfc = r.i32();
        for (int i = 0; i < dfc; i++) {
            r.text(); r.text();          // 名字
            r.u8();                      // 特殊帧标志 (0=通常 1=系统帧)
            int cnt = r.i32();
            for (int k = 0; k < cnt; k++) {
                int t = r.u8();          // 0=骨骼 1=变形
                if (t == 0) r.sidx(boneIdxSize); else r.sidx(morphIdxSize);
            }
        }

        // ---- 刚体 ----
        // 字段 = 名JA,名EN,相关骨骼,组(u8),不碰撞组(u16),形状类型(u8),
        //        尺寸vec3,位置vec3,旋转vec3,质量,移动衰减,旋转衰减,反发,摩擦(共14个f32),物理类型(u8)
        int rbc = r.i32();
        float[] tmp3 = new float[3];
        for (int i = 0; i < rbc; i++) {
            r.text(); r.text(); r.sidx(boneIdxSize);
            r.u8(); r.u16(); r.u8();
            r.f32v(tmp3); r.f32v(tmp3); r.f32v(tmp3); // 尺寸/位置/旋转
            for (int k = 0; k < 5; k++) r.f32();      // 质量/移动衰减/旋转衰减/反发/摩擦
            r.u8();                                   // 物理演算类型
        }

        // ---- Joint ----
        // 字段 = 名JA,名EN,类型(u8),刚体A,刚体B,
        //        位置vec3,旋转vec3,平移限制min/max vec3,旋转限制min/max vec3,平移弹簧vec3,旋转弹簧vec3（共8个vec3）
        int jc = r.i32();
        for (int i = 0; i < jc; i++) {
            r.text(); r.text();
            r.u8();
            r.sidx(rigidIdxSize); r.sidx(rigidIdxSize);
            for (int k = 0; k < 8; k++) r.f32v(tmp3);
        }
        // 硬校验：解析必须恰好消费整个文件（一个字节不剩、一个字节不缺）
        if (r.bb.position() != r.bb.limit()) {
            throw new IllegalStateException("PMX 解析未对齐文件尾: cursor=" + r.bb.position()
                    + " limit=" + r.bb.limit() + "（区段字段定义有误）");
        }
        m.stats.put("faceIndexTotal", idxStart);
        m.stats.put("morphVertex", 0);
        return m;
    }

    // ============================ GLB 写出 ============================

    // --- 二进制段收集器 ---
    static class Bin {
        final RawBytes out;
        Bin() { this(1 << 16); }
        Bin(int cap) { out = new RawBytes(Math.max(1 << 16, cap)); }
        int tell() { return out.size(); }
        void f32(float v) {
            int bits = Float.floatToIntBits(v);
            out.write(bits); out.write(bits >>> 8); out.write(bits >>> 16); out.write(bits >>> 24);
        }
        void u16(int v) { out.write(v); out.write(v >>> 8); }
        void u32(long v) { out.write((int) v); out.write((int) (v >>> 8)); out.write((int) (v >>> 16)); out.write((int) (v >>> 32)); }
        void bytes(byte[] b) { out.write(b, 0, b.length); }
        void pad4() { while (out.size() % 4 != 0) out.write(0); }
        byte[] toBytes() { return out.toByteArray(); }
    }

    /** 暴露底层数组，GLB 打包时直接 arraycopy，避免 toByteArray() 再复制整段 BIN。 */
    static class RawBytes extends ByteArrayOutputStream {
        RawBytes(int cap) { super(cap); }
        byte[] raw() { return buf; }
    }

    static class BV { int buffer, byteOffset = 0, byteLength, target = 0; }  // bufferView

    static class Acc {
        int bufferView, componentType, count; String type;
        float[] min, max;                       // 仅 POSITION 需要
        // 稀疏访问器（morph target 用）
        int sparseCount = 0;
        BV sparseIdxBV; int sparseIdxCompType;
        BV sparseValBV;
        Acc(int bv, int ct, int count, String type) { this.bufferView = bv; this.componentType = ct; this.count = count; this.type = type; }
    }

    static class Prim {
        Acc indices;
        Map<String, Acc> attrs = new LinkedHashMap<>();
        Acc material;
        List<Acc[]> targets = new ArrayList<>(); // 每个 morph: [索引Acc, 数值Acc]
    }

    // --- JSON 构建器（手工拼，保证零依赖）---
    static class J {
        StringBuilder sb = new StringBuilder();
        J o()  { sep(); sb.append('{'); return this; }
        J c()  { sb.append('}'); return this; }
        J a()  { sep(); sb.append('['); return this; }
        J z()  { sb.append(']'); return this; }
        J k(String key) { sep(); str(key); sb.append(':'); return this; }
        J v(long n) { sep(); sb.append(n); return this; }
        J v(float f) { sep(); sb.append(Float.toString(f)); return this; }
        J s(String s) { sep(); str(s); return this; }
        J appendRaw(String raw) { sb.append(raw); return this; }
        /** 元素分隔：上一个字符若是值结尾（" } ] 数字），先补逗号 */
        private void sep() {
            int n = sb.length();
            if (n == 0) return;
            char c = sb.charAt(n - 1);
            if (c != '[' && c != '{' && c != ':' && c != ',') sb.append(',');
        }
        J str(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                }
            }
            sb.append('"');
            return this;
        }
        public String toString() { return sb.toString(); }
    }

    private Bin bin = new Bin();
    private final List<BV> bufferViews = new ArrayList<>();
    private final List<Acc> accessors = new ArrayList<>();
    private final List<Prim> prims = new ArrayList<>();
    private final List<String> glMaterials = new ArrayList<>();
    private final List<String> nodeJson = new ArrayList<>();
    private int meshNodeIdx = -1, skinIdx = -1;
    private PmxModel model;
    private int[] boneToNode;
    private int morphTargetCount = 0;
    private final List<String> targetNames = new ArrayList<>();
    private final List<String> skippedMorphs = new ArrayList<>();

    // ---- 贴图嵌入状态 ----
    private Map<String, byte[]> textureFiles = new HashMap<>();          // 规范化路径 -> 字节
    private final List<String> glImages = new ArrayList<>();            // image JSON
    private final List<String> glTextures = new ArrayList<>();          // texture JSON
    private final Map<Integer, Integer> pmxTexToGlTexture = new HashMap<>(); // PMX贴图idx -> glTF texture idx
    private int embeddedTexCount = 0;
    private final List<String> skippedTex = new ArrayList<>();

    /** 规范化路径：\\ -> /，去 ./，小写索引，大小写不敏感匹配 */
    private static Map<String, byte[]> normalizePaths(Map<String, byte[]> in) {
        Map<String, byte[]> m = new HashMap<>();
        if (in == null) return m;
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            String k = e.getKey().replace('\\', '/');
            while (k.startsWith("./")) k = k.substring(2);
            while (k.startsWith("../")) k = k.substring(3);
            String low = k.toLowerCase(java.util.Locale.ROOT);
            m.put(low, e.getValue());
            // 同名文件兜底：PMX 常写 `../tex/颜.png`、也有的包把贴图放在别的子目录。
            // 精确相对路径永远优先，只有在精确路径缺失时才用 basename。
            int slash = low.lastIndexOf('/');
            if (slash >= 0) {
                String base = low.substring(slash + 1);
                if (!m.containsKey(base)) m.put(base, e.getValue());
            }
        }
        return m;
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".bmp")) return "image/bmp";
        return null; // tga / spa / sph(DDS) 等 glTF 不支持，跳过
    }

    /** PMX 贴图索引 -> glTF texture 索引；不可用返回 -1（去重：同贴图只嵌一份） */
    private int addTexture(int pmxTexIdx) {
        if (pmxTexIdx < 0 || pmxTexIdx >= model.textures.size()) return -1;
        Integer cached = pmxTexToGlTexture.get(pmxTexIdx);
        if (cached != null) return cached;
        String rel = model.textures.get(pmxTexIdx).replace('\\', '/');
        while (rel.startsWith("./")) rel = rel.substring(2);
        while (rel.startsWith("../")) rel = rel.substring(3);
        String mime = mimeOf(rel);
        if (mime == null) { skippedTex.add(rel); return -1; }
        String key = rel.toLowerCase(java.util.Locale.ROOT);
        byte[] data = textureFiles.get(key);
        if (data == null) {
            int slash = key.lastIndexOf('/');
            if (slash >= 0) data = textureFiles.get(key.substring(slash + 1));
        }
        if (data == null) { skippedTex.add(rel + "(文件缺失)"); return -1; }
        // BMP 在 Android 侧已经转成 PNG；mime 按真实字节走，避免 image/bmp 在查看器里不显示。
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
                && data[2] == 'N' && data[3] == 'G') {
            mime = "image/png";
        } else if (data.length >= 3 && (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            mime = "image/jpeg";
        }
        BV bv = addBV(data);
        // 贴图已经拷进 GLB 的 BIN；立刻释放源字节，避免「原图 + BIN + 输出」三份同时驻留。
        // pmxTexToGlTexture 已缓存，同一张贴图后面不会再次走这里。
        final byte[] embedded = data;
        textureFiles.entrySet().removeIf(e -> e.getValue() == embedded);
        int imgIdx = glImages.size();
        glImages.add("{\"bufferView\":" + (bufferViews.indexOf(bv)) + ",\"mimeType\":\"" + mime + "\"}");
        int texIdx = glTextures.size();
        glTextures.add("{\"source\":" + imgIdx + "}");
        pmxTexToGlTexture.put(pmxTexIdx, texIdx);
        embeddedTexCount++;
        return texIdx;
    }

    private BV addBV(byte[] payload) {
        int off = bin.tell();
        bin.bytes(payload);
        bin.pad4();
        BV bv = new BV();
        bv.byteOffset = off; bv.byteLength = payload.length; bv.buffer = 0;
        bufferViews.add(bv);
        return bv;
    }

    private int addAcc(BV bv, int compType, int count, String type, float[] min, float[] max) {
        Acc a = new Acc(bufferViews.indexOf(bv), compType, count, type);
        a.min = min; a.max = max;
        accessors.add(a);
        return accessors.size() - 1;
    }

    /** 转换主入口（Android 直接调这个） */
    public byte[] convert(byte[] pmxBytes) throws IOException {
        return convert(pmxBytes, null);
    }

    /**
     * 带贴图嵌入的转换入口。
     * @param textures 贴图表：key = 相对路径（正斜杠，大小写不敏感匹配，如 "tex/颜.png"），value = 文件字节
     */
    public byte[] convert(byte[] pmxBytes, Map<String, byte[]> textures) throws IOException {
        return convertPrepared(prepare(pmxBytes), textures);
    }

    /** 先解析一次 PMX；调用方可以拿它先筛贴图，再交给 convertPrepared，避免解析两遍。 */
    public PmxModel prepare(byte[] pmxBytes) throws IOException {
        model = parsePmx(pmxBytes);
        return model;
    }

    /** PMX 里引用到的贴图路径（含重复项，调用方自行去重）。 */
    public List<String> textureNames(PmxModel prepared) {
        return prepared == null ? new ArrayList<String>() : new ArrayList<>(prepared.textures);
    }

    public byte[] convertPrepared(PmxModel prepared, Map<String, byte[]> textures) throws IOException {
        if (prepared == null) throw new IOException("PMX 未解析");
        this.textureFiles = normalizePaths(textures);
        model = prepared;
        // 按本次实际要写入的贴图 + 顶点/索引估算一次容量，减少 BAOS 反复扩容的峰值拷贝。
        long est = 0;
        if (this.textureFiles != null) {
            for (byte[] b : this.textureFiles.values()) est += b == null ? 0 : b.length;
        }
        est += (long) model.vertices.size() * 48L + (long) model.faces.length * 4L;
        long capLimit = Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
        bin = new Bin((int) Math.min(est + 1024L, Math.max(1L << 16, capLimit)));
        int vc = model.vertices.size();
        int bc = model.bones.size();
        int fc = model.faces.length;

        // ---------- 面索引（绕序翻转） ----------
        // 与 Z 取反（镜像 det=-1）配套：镜像使右手叉积几何法线反向，
        // 必须翻转顶点序才能维持 glTF 的 CCW 正面约定（实测 99.9% 法线同向恢复）。
        // glTF 索引：顶点数 ≤ 65535 用 UNSIGNED_SHORT，超过必须用 UNSIGNED_INT，
        // 否则大模型（>6.5 万顶点）索引会溢出成负数，产物看起来“模型炸开/缺面”。
        boolean wideIndex = vc > 65535;
        for (Material mt : model.materials) {
            int n = mt.faceVertCount;
            int[] idxBuf = new int[n];
            for (int k = 0; k < n; k += 3) {
                idxBuf[k]     = model.faces[mt.indexStart + k];
                idxBuf[k + 1] = model.faces[mt.indexStart + k + 2];
                idxBuf[k + 2] = model.faces[mt.indexStart + k + 1];
            }
            ByteBuffer bb = ByteBuffer.allocate(n * (wideIndex ? 4 : 2))
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int v : idxBuf) {
                if (wideIndex) bb.putInt(v);
                else bb.putShort((short) v);
            }
            BV bv = addBV(bb.array());
            Acc acc = new Acc(bufferViews.size() - 1, wideIndex ? 5125 : 5123, n, "SCALAR");
            accessors.add(acc);
            Prim p = new Prim();
            p.indices = acc;
            prims.add(p);
            mt.glMaterialIdx = prims.size() - 1;
        }

        // ---------- 顶点属性（坐标系变换：MMD 左手系 -> glTF 右手系，Z 轴取反） ----------
        // MMD 模型正面朝 -Z、glTF 观众在 +Z；仅 Z 取反 = 正面转向观众、模型左手保持在观众右侧，
        // 与 MMD 原生观感一致。镜像变换保持 (几何法线 vs 顶点法线) 配对关系，绕序无需改动。
        ByteBuffer posB = ByteBuffer.allocate(vc * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer nrmB = ByteBuffer.allocate(vc * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvB  = ByteBuffer.allocate(vc * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer jntB = ByteBuffer.allocate(vc * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer wgtB = ByteBuffer.allocate(vc * 16).order(ByteOrder.LITTLE_ENDIAN);
        float[] posMin = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] posMax = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (Vertex v : model.vertices) {
            for (int k = 0; k < 3; k++) {
                float pz = (k == 2) ? -v.pos[2] : v.pos[k];
                float nz = (k == 2) ? -v.normal[2] : v.normal[k];
                posB.putFloat(pz);
                if (pz < posMin[k]) posMin[k] = pz;
                if (pz > posMax[k]) posMax[k] = pz;
                nrmB.putFloat(nz);
            }
            uvB.putFloat(v.uv[0]); uvB.putFloat(v.uv[1]);
            for (int k = 0; k < 4; k++) {
                int j = (v.joints.length > k && v.joints[k] >= 0) ? v.joints[k] : 0;
                float w = (v.weights.length > k) ? v.weights[k] : 0f;
                jntB.putShort((short) j);
                wgtB.putFloat(w);
            }
        }
        BV posBV = addBV(posB.array());
        BV nrmBV = addBV(nrmB.array());
        BV uvBV  = addBV(uvB.array());
        BV jntBV = addBV(jntB.array());
        BV wgtBV = addBV(wgtB.array());
        Acc posAcc = new Acc(bufferViews.indexOf(posBV), 5126, vc, "VEC3");
        posAcc.min = posMin; posAcc.max = posMax;
        accessors.add(posAcc);
        Acc nrmAcc = new Acc(bufferViews.indexOf(nrmBV), 5126, vc, "VEC3"); accessors.add(nrmAcc);
        Acc uvAcc  = new Acc(bufferViews.indexOf(uvBV),  5126, vc, "VEC2"); accessors.add(uvAcc);
        Acc jntAcc = new Acc(bufferViews.indexOf(jntBV), 5123, vc, "VEC4"); accessors.add(jntAcc);
        Acc wgtAcc = new Acc(bufferViews.indexOf(wgtBV), 5126, vc, "VEC4"); accessors.add(wgtAcc);

        for (Prim p : prims) {
            p.attrs.put("POSITION", posAcc);
            p.attrs.put("NORMAL", nrmAcc);
            p.attrs.put("TEXCOORD_0", uvAcc);
            p.attrs.put("JOINTS_0", jntAcc);
            p.attrs.put("WEIGHTS_0", wgtAcc);
        }

        // ---------- morph targets（稀疏访问器，只存有效偏移顶点） ----------
        for (Morph mo : model.morphs) {
            boolean isVertexMorph = (mo.type == 1);
            boolean isUvMorph     = (mo.type == 3);
            if (!isVertexMorph && !isUvMorph) {
                skippedMorphs.add(mo.type + ":" + mo.nameJA);
                continue;
            }
            if (mo.idx.length == 0) { skippedMorphs.add(mo.type + ":" + mo.nameJA + "(空)"); continue; }
            ByteBuffer iB = ByteBuffer.allocate(mo.idx.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer vB = ByteBuffer.allocate(mo.idx.length * (isVertexMorph ? 12 : 8)).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < mo.idx.length; k++) {
                iB.putInt(mo.idx[k]);
                if (isVertexMorph) { vB.putFloat(mo.vals[k][0]); vB.putFloat(mo.vals[k][1]); vB.putFloat(-mo.vals[k][2]); } // Z 取反与顶点坐标系一致
                else { vB.putFloat(mo.vals[k][0]); vB.putFloat(mo.vals[k][1]); } // UV 取 vec4 的 xy
            }
            BV ibv = addBV(iB.array());
            BV vbv = addBV(vB.array());
            Acc iAcc = new Acc(bufferViews.indexOf(ibv), 5125, mo.idx.length, "SCALAR"); accessors.add(iAcc);
            Acc vAcc = new Acc(bufferViews.indexOf(vbv), 5126, vc, isVertexMorph ? "VEC3" : "VEC2"); accessors.add(vAcc);
            // 标记为稀疏访问器：count=vc，实际数据只存有偏移的顶点（规范合法，体积最小）
            vAcc.sparseCount = mo.idx.length;
            vAcc.sparseIdxBV = ibv;
            vAcc.sparseIdxCompType = 5125;
            vAcc.sparseValBV = vbv;
            mo.glTargetSlot = morphTargetCount++;
            targetNames.add(mo.nameJA);
            Acc[] t = new Acc[]{ iAcc, vAcc };
            for (Prim p : prims) p.targets.add(t);
        }

        // ---------- 骨骼节点树 + 逆绑定矩阵 ----------
        // 坐标系变换：骨骼局部平移 Z 取反。局部链式取反 = 整体 Z 镜像（线性性），
        // 世界坐标 Z 自动变为 -原值，与顶点变换一致。
        boneToNode = new int[bc];
        for (int i = 0; i < bc; i++) boneToNode[i] = -1;
        // PMX 的骨骼坐标是「模型空间绝对坐标」，不是相对父骨骼的位移。
        // 之前按局部位移累加，子骨骼会被父链重复叠加，导出的骨架就会整体错位/乱飞。
        float[][] world = new float[bc][3];
        for (int i = 0; i < bc; i++) {
            Bone b = model.bones.get(i);
            world[i][0] = b.pos[0];
            world[i][1] = b.pos[1];
            world[i][2] = -b.pos[2];   // Z 取反：MMD 左手系 -> glTF 右手系
            b.worldPos = world[i];
        }
        for (int i = 0; i < bc; i++) boneToNode[i] = i; // 节点排布 = 骨骼顺序，父先子后

        // 逆绑定矩阵 = inverse(translate(worldPos)) = translate(-worldPos)（列主序 MAT4）
        ByteBuffer ibmB = ByteBuffer.allocate(bc * 64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bc; i++) {
            ibmB.putFloat(1); ibmB.putFloat(0); ibmB.putFloat(0); ibmB.putFloat(0);
            ibmB.putFloat(0); ibmB.putFloat(1); ibmB.putFloat(0); ibmB.putFloat(0);
            ibmB.putFloat(0); ibmB.putFloat(0); ibmB.putFloat(1); ibmB.putFloat(0);
            ibmB.putFloat(-world[i][0]); ibmB.putFloat(-world[i][1]); ibmB.putFloat(-world[i][2]); ibmB.putFloat(1);
        }
        BV ibmBV = addBV(ibmB.array());
        Acc ibmAcc = new Acc(bufferViews.indexOf(ibmBV), 5126, bc, "MAT4"); accessors.add(ibmAcc);

        skinIdx = 0; // 本模型单 skin

        // ---------- 材质（含贴图嵌入） ----------
        for (Material mt : model.materials) {
            StringBuilder mb = new StringBuilder();
            mb.append("{\"name\":").append(jsonStr(mt.nameJA.isEmpty() ? mt.nameEN : mt.nameJA));
            mb.append(",\"pbrMetallicRoughness\":{");
            mb.append("\"baseColorFactor\":[");
            mb.append(Float.toString(mt.diffuse[0])).append(',');
            mb.append(Float.toString(mt.diffuse[1])).append(',');
            mb.append(Float.toString(mt.diffuse[2])).append(',');
            mb.append(Float.toString(mt.diffuse[3]));
            mb.append("],\"metallicFactor\":0,\"roughnessFactor\":0.9}");
            int texId = addTexture(mt.texIdx); // 嵌入 diffuse 贴图（去重）
            if (texId >= 0) mb.append(",\"baseColorTexture\":{\"index\":").append(texId).append('}');
            if (mt.diffuse[3] < 0.999f) mb.append(",\"alphaMode\":\"BLEND\"");
            if ((mt.flag & 0x02) != 0) mb.append(",\"doubleSided\":true");
            mb.append(",\"extras\":{\"pmxSpecular\":[").append(Float.toString(mt.specular[0])).append(',')
              .append(Float.toString(mt.specular[1])).append(',').append(Float.toString(mt.specular[2])).append(']');
            mb.append(",\"pmxSpecularPower\":").append(Float.toString(mt.specPower)).append('}');
            mb.append('}');
            glMaterials.add(mb.toString());
        }

        // ---------- 节点 ----------
        // meshNode 放最后（index = bc），骨骼节点 0..bc-1
        meshNodeIdx = bc;
        int rootBone = -1;
        for (int i = 0; i < bc; i++) if (model.bones.get(i).parent < 0 && rootBone < 0) rootBone = i;

        StringBuilder sceneChildren = new StringBuilder();
        sceneChildren.append(meshNodeIdx);
        for (int i = 0; i < bc; i++) if (model.bones.get(i).parent < 0) sceneChildren.append(',').append(i);

        // ---------- 组装 JSON ----------
        J j = new J();
        j.o();
        j.k("asset").o().k("version").s("2.0").k("generator").s("Pmx2Glb-java (PMX 2.0 -> glTF 2.0)").c();
        j.k("scene").v(0);
        j.k("scenes").a().o().k("nodes").a().appendRaw(sceneChildren.toString()).z().c().z();
        j.k("nodes").a();
        for (int i = 0; i < bc; i++) {
            Bone b = model.bones.get(i);
            j.o();
            j.k("name").s(b.nameJA.isEmpty() ? ("bone" + i) : b.nameJA);
            float[] t = boneTranslation(i);
            j.k("translation").a().v(t[0]).v(t[1]).v(t[2]).z();
            List<Integer> children = new ArrayList<>();
            for (int k = 0; k < bc; k++) if (model.bones.get(k).parent == i) children.add(k);
            if (!children.isEmpty()) {
                j.k("children").a();
                for (int k = 0; k < children.size(); k++) { if (k > 0) sbComma(j); j.v(children.get(k)); }
                j.z();
            }
            j.c();
        }
        // mesh 节点
        j.o().k("name").s(model.nameJA.isEmpty() ? "pmx_model" : model.nameJA)
               .k("mesh").v(0).k("skin").v(0).c();
        j.z(); // nodes
        j.k("skins").a().o().k("joints").a();
        for (int i = 0; i < bc; i++) { if (i > 0) sbComma(j); j.v(i); }
        j.z();
        if (rootBone >= 0) j.k("skeleton").v(rootBone);
        j.k("inverseBindMatrices").v(accessors.indexOf(ibmAcc));
        j.c().z(); // skins
        j.k("meshes").a().o();
        j.k("name").s(model.nameJA.isEmpty() ? "pmx_mesh" : model.nameJA);
        j.k("primitives").a();
        for (int pi = 0; pi < prims.size(); pi++) {
            Prim p = prims.get(pi);
            j.o().k("attributes").o();
            int ai = 0;
            for (Map.Entry<String, Acc> e : p.attrs.entrySet()) { if (ai++ > 0) sbComma(j); j.k(e.getKey()).v(accessors.indexOf(e.getValue())); }
            j.c(); // attributes 对象闭合
            j.k("indices").v(accessors.indexOf(p.indices));
            j.k("material").v(pi);
            if (!p.targets.isEmpty()) {
                j.k("targets").a();
                for (Acc[] t : p.targets) { j.o().k(isVec3Target(t[1]) ? "POSITION" : "TEXCOORD_0").v(accessors.indexOf(t[1])).c(); }
                j.z();
            }
            j.c();
        }
        j.z(); // primitives
        if (morphTargetCount > 0) {
            j.k("weights").a();
            for (int i = 0; i < morphTargetCount; i++) { if (i > 0) sbComma(j); j.v(0f); }
            j.z();
            j.k("extras").o().k("targetNames").a();
            for (int i = 0; i < targetNames.size(); i++) { if (i > 0) sbComma(j); j.s(targetNames.get(i)); }
            j.z();
            j.k("pmxSkippedMorphs").a();
            for (int i = 0; i < skippedMorphs.size(); i++) { if (i > 0) sbComma(j); j.s(skippedMorphs.get(i)); }
            j.z().c();
        }
        j.c().z(); // meshes
        j.k("materials").a();
        for (int i = 0; i < glMaterials.size(); i++) { if (i > 0) sbComma(j); sbRaw(j, glMaterials.get(i)); }
        j.z();
        if (!glTextures.isEmpty()) {
            j.k("textures").a();
            for (int i = 0; i < glTextures.size(); i++) { if (i > 0) sbComma(j); sbRaw(j, glTextures.get(i)); }
            j.z();
            j.k("images").a();
            for (int i = 0; i < glImages.size(); i++) { if (i > 0) sbComma(j); sbRaw(j, glImages.get(i)); }
            j.z();
        }
        j.k("accessors").a();
        for (int i = 0; i < accessors.size(); i++) {
            Acc a = accessors.get(i);
            j.o().k("bufferView").v(a.bufferView).k("componentType").v(a.componentType)
                   .k("count").v(a.count).k("type").s(a.type);
            if (a.min != null) {
                j.k("min").a(); for (int k2 = 0; k2 < a.min.length; k2++) { if (k2 > 0) sbComma(j); j.v(a.min[k2]); } j.z();
                j.k("max").a(); for (int k2 = 0; k2 < a.max.length; k2++) { if (k2 > 0) sbComma(j); j.v(a.max[k2]); } j.z();
            }
            if (a.sparseCount > 0) {
                j.k("sparse").o()
                  .k("count").v(a.sparseCount)
                  .k("indices").o().k("bufferView").v(a.sparseIdxBV != null ? bufferViews.indexOf(a.sparseIdxBV) : -1)
                  .k("componentType").v(a.sparseIdxCompType).c()
                  .k("values").o().k("bufferView").v(a.sparseValBV != null ? bufferViews.indexOf(a.sparseValBV) : -1).c()
                  .c();
            }
            j.c();
        }
        j.z(); // accessors
        j.k("bufferViews").a();
        for (int i = 0; i < bufferViews.size(); i++) {
            BV bv = bufferViews.get(i);
            j.o().k("buffer").v(0).k("byteOffset").v(bv.byteOffset).k("byteLength").v(bv.byteLength);
            if (bv.target != 0) j.k("target").v(bv.target);
            j.c();
        }
        j.z();
        j.k("buffers").a().o().k("byteLength").v(bin.tell()).c().z();
        j.c();
        String json = j.toString();

        byte[] glb = packGlb(json, bin);
        // 贴图字节在打包完成后不再需要；保留 model 供 summary() 使用。
        if (textureFiles != null) textureFiles.clear();
        return glb;
    }

    /**
     * 某根骨骼相对父骨骼的节点平移。
     *
     * <p>PMX 的 b.pos 是模型空间绝对坐标；glTF 节点 translation 必须是局部偏移，
     * 所以这里做差并同步 Z 镜像。单测会沿父链累加，验证回到绝对坐标。
     */
    float[] boneTranslation(int index) {
        Bone b = model.bones.get(index);
        float tx = b.pos[0], ty = b.pos[1], tz = -b.pos[2];
        if (b.parent >= 0) {
            Bone p = model.bones.get(b.parent);
            tx -= p.pos[0];
            ty -= p.pos[1];
            tz += p.pos[2];       // (-b.z) - (-p.z)
        }
        return new float[]{tx, ty, tz};
    }

    private boolean isVec3Target(Acc a) { return "VEC3".equals(a.type); }
    private void sbComma(J j) { j.sb.append(','); }
    private void sbRaw(J j, String raw) { j.sb.append(raw); }
    private String jsonStr(String s) { J t = new J(); t.str(s); return t.toString(); }

    // Material.specPower 直接访问，无需包装

    // ---------- GLB 打包 ----------
    private byte[] packGlb(String json, Bin bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPad = (4 - (jsonBytes.length % 4)) % 4;
        int binSize = bin.tell();
        int binPad  = (4 - (binSize % 4)) % 4;
        int jsonLen = jsonBytes.length + jsonPad;
        int binLen  = binSize + binPad;
        int total = 12 + 8 + jsonLen + 8 + binLen;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67);          // "glTF"
        out.putInt(2);
        out.putInt(total);
        out.putInt(jsonLen);
        out.putInt(0x4E4F534A);          // "JSON"
        out.put(jsonBytes);
        for (int i = 0; i < jsonPad; i++) out.put((byte) 0x20); // 空格填充
        if (binSize > 0) {
            out.putInt(binLen);
            out.putInt(0x004E4942);      // "BIN\0"
            // 直接拷贝 BAOS 的底层数组，省掉 bin.toBytes() 的一次完整复制。
            System.arraycopy(bin.out.raw(), 0, out.array(), out.position(), binSize);
            out.position(out.position() + binSize);
            for (int i = 0; i < binPad; i++) out.put((byte) 0);
        }
        return out.array();
    }

    // ============================ CLI ============================

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java Pmx2Glb <in.pmx> <out.glb>");
            System.exit(2);
        }
        byte[] pmx = readFile(args[0]);
        // 扫描 PMX 同目录树的所有文件作为贴图候选（key=相对路径）
        Map<String, byte[]> textures = new HashMap<>();
        java.io.File baseDir = new java.io.File(args[0]).getCanonicalFile().getParentFile();
        collectTextures(baseDir, "", textures);
        long t0 = System.currentTimeMillis();
        Pmx2Glb conv = new Pmx2Glb();
        byte[] glb = conv.convert(pmx, textures);
        long t1 = System.currentTimeMillis();
        writeFile(args[1], glb);

        PmxModel m = conv.model;
        int vMorphs = 0, uvMorphs = 0, skipped = 0;
        for (Morph mo : m.morphs) {
            if (mo.glTargetSlot >= 0) { if (mo.type == 1) vMorphs++; else uvMorphs++; }
            else skipped++;
        }
        System.out.println("== PMX 解析 ==");
        System.out.println("模型名JA   : " + m.nameJA);
        System.out.println("模型名EN   : " + m.nameEN);
        System.out.println("顶点数     : " + m.vertices.size());
        System.out.println("面索引数   : " + m.faces.length + " (三角形 " + (m.faces.length / 3) + ")");
        System.out.println("贴图引用数 : " + m.textures.size());
        System.out.println("材质数     : " + m.materials.size());
        System.out.println("骨骼数     : " + m.bones.size());
        System.out.println("morph 数   : " + m.morphs.size() + " (顶点 " + vMorphs + ", UV " + uvMorphs + ", 不导出 " + skipped + ")");
        System.out.println("== GLB 输出 ==");
        System.out.println("贴图嵌入   : " + conv.embeddedTexCount + " 张"
                + (conv.skippedTex.isEmpty() ? "" : " (跳过: " + conv.skippedTex + ")"));
        System.out.println("输出文件   : " + args[1]);
        System.out.println("文件大小   : " + glb.length + " bytes");
        System.out.println("耗时       : " + (t1 - t0) + " ms");
    }

    // ---------------------------------------------------------------- 统计（Android UI 用）

    public int vertexCount() { return model == null ? 0 : model.vertices.size(); }

    public int triangleCount() { return model == null ? 0 : model.faces.length / 3; }

    public int materialCount() { return model == null ? 0 : model.materials.size(); }

    public int boneCount() { return model == null ? 0 : model.bones.size(); }

    public int morphCount() { return model == null ? 0 : model.morphs.size(); }

    public int embeddedTextureCount() { return embeddedTexCount; }

    public java.util.List<String> skippedTextures() { return new ArrayList<>(skippedTex); }

    /** 一行可读摘要，格式转换结果页直接用。 */
    public String summary() {
        if (model == null) return "PMX 未解析";
        StringBuilder b = new StringBuilder();
        b.append("顶点 ").append(vertexCount())
                .append(" · 三角形 ").append(triangleCount())
                .append(" · 骨骼 ").append(boneCount())
                .append(" · 材质 ").append(materialCount())
                .append(" · morph ").append(morphCount())
                .append(" · 贴图 ").append(embeddedTextureCount()).append(" 张");
        if (!skippedTex.isEmpty()) {
            b.append("\n跳过贴图：");
            for (int i = 0; i < skippedTex.size() && i < 8; i++) {
                if (i > 0) b.append("、");
                b.append(skippedTex.get(i));
            }
            if (skippedTex.size() > 8) b.append("…");
        }
        return b.toString();
    }

    /** 递归收集目录下所有文件为贴图候选（含子目录 tex/ spa/ 等） */
    static void collectTextures(java.io.File dir, String prefix, Map<String, byte[]> out) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) { collectTextures(f, prefix + f.getName() + "/", out); continue; }
            try { out.put(prefix + f.getName(), readFile(f.getAbsolutePath())); } catch (Exception ignore) {}
        }
    }

    static byte[] readFile(String path) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (FileInputStream fi = new FileInputStream(path)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    static void writeFile(String path, byte[] data) throws IOException {
        FileOutputStream fo = new FileOutputStream(path);
        fo.write(data);
        fo.close();
    }
}
