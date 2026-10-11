package com.mineways.scrub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MP4 / MOV（ISO 14496-12 盒子容器）剪辑软件标识清洗器。
 *
 * <p>剪映 / 快影 / 必剪 导出时把自己的名字写进 {@code moov.udta}、{@code meta.ilst}
 * 键盒、{@code hdlr} 名字段、XMP / uuid 这类<b>纯元数据</b>位置；平台读到这些字段就挂
 * 「剪辑软件同款」入口。这些位置全都不参与解码，所以只动它们就够了，不用重编码。</p>
 *
 * <p>删除手法是「就地改类型 + 清零载荷」：把盒子的 fourcc 改成协议里表示「空白、读取方必须
 * 忽略」的 {@code free}，再把盒内字节全部清零。这样<b>文件总长度和每个字节偏移都不变</b>，
 * {@code stco}/{@code co64} 的绝对分块偏移、fMP4 的 {@code moof}/{@code mfra} 索引统统
 * 不用重算 —— 「删盒导致偏移错位」这个最容易翻车的坑从根上绕开了。</p>
 *
 * <p>只有「写入自定义标识」在文件里找不到同键条目可复用时才真的变长：统一在元数据表尾部开
 * 一个插入点，修正祖先盒 size 和所有指向插入点之后的 {@code stco}/{@code co64} 绝对偏移。
 * 分片 MP4 的绝对偏移不止这两处（{@code sidx}/{@code tfra}），所以分片文件一律拒绝变长写入。</p>
 *
 * <p>纯 JDK 实现，不含 android 依赖，可以用 {@link #main(String[])} 在桌面直接跑真文件。</p>
 */
public final class Mp4Scrubber {

    /** 剪映 / 快影 / 必剪 的品牌词（含抖音、快手、B 站、CapCut 等同族词）。 */
    public static final int FLAG_BRAND = 1;

    /** 压制器指纹：Lavf 版本号、CAPD MTS 封装器署名、快影的转码参数注释、ffmpeg 默认 hdlr 名。 */
    public static final int FLAG_LAVF = 2;

    /** 生成合成（AIGC）标识。默认不勾，理由见 {@link #AIGC_LEGAL_NOTE}。 */
    public static final int FLAG_AIGC = 4;

    /** 彻底模式：清掉全部可删元数据盒（创作备注、封面、章节之类一并没了）。 */
    public static final int FLAG_FULL = 8;

    public static final int FLAGS_DEFAULT = FLAG_BRAND | FLAG_LAVF;

    public static final String AIGC_LEGAL_NOTE =
            "《人工智能生成合成内容标识办法》要求 AI 生成合成内容带显式标识，抖音 / 快手 / B 站也各有"
                    + " AIGC 声明规则。清除 AIGC 元数据可能违反平台规则甚至法规，只在内容确为模板误标、"
                    + "或作品自有且非 AI 生成时才建议勾选。";

    /** 单个叶子盒最多读这么多字节用于匹配，再大只按类型判断（防超大元数据盒吃干内存）。 */
    private static final int SCAN_CAP = 2 << 20;

    /** 复用已有条目原地改写时允许一次性构造的最大填充。 */
    private static final int WRITE_PAD_CAP = 4 << 20;

    private static final Charset LATIN = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.isSupported("GBK") ? Charset.forName("GBK") : UTF8;
    private static final byte[] FREE = "free".getBytes(LATIN);
    private static final byte[] DATA = "data".getBytes(LATIN);

    /** 品牌组：第 0 项是给人看的标签，其余是匹配词（ASCII 词一律小写，中文按 UTF-8/GBK 找）。 */
    private static final String[][] BRANDS = {
            {"剪映", "jianying", "capcut", "pippit", "video_fusion", "videofusion", "lv_0_",
                    "bytedance", "douyin", "字节跳动", "剪映", "抖音"},
            {"快影", "kuaiying", "kwai", "kuaishou", "gifshow", "快影", "快手"},
            {"必剪", "bcut", "bijian", "bilibili", "必剪", "哔哩哔哩"},
            {"AI 生成器", "doubao", "jimeng", "即梦", "豆包", "volcengine", "volces", "火山引擎",
                    "kling", "可灵", "hailuo", "vidu", "pixverse"},
    };

    /**
     * 压制器指纹。两个真实样本里最关键的一条就在这里：快影导出的 {@code ©too} 写的是
     * {@code Lavf88.76.100}，通篇没有「快影」二字 —— 只按品牌词匹配会整条漏掉。
     */
    private static final String[] LAVF_WORDS = {
            "lavf", "lavc", "lavu", "lavr", "libav", "ffmpeg", "ffprobe", "mediabunny",
            "mp4box", "gpac", "handbrake", "x264", "x265", "openh264", "libvpx", "nvenc",
            "qmtx", "faac", "makvmerge",
    };

    /** 封装器 / 转码器署名（剪映写 {@code desc = "Tencent CAPD MTS (T-tech)"}）。 */
    private static final String[] MUXER_WORDS = {
            "capd mts", "t-tech", "tencent capd",
    };

    /** ffmpeg 默认写进 hdlr 的名字，出现即说明这片子是 ffmpeg 系封装的。 */
    private static final String[] GENERIC_HANDLERS = {
            "videohandler", "soundhandler", "core media", "garageband", "nullhandler",
    };

    /** 快影 / 快手一类把转码参数塞进注释盒的写法：[s=1080x1080][vb=12000k][ab=192k] */
    private static final String[] PARAM_WORDS = {"[s=", "[vb=", "[ab="};

    /** 生成合成内容标识词。命中即认为该盒带 AIGC 标识，只有勾了 {@link #FLAG_AIGC} 才清除。 */
    private static final String[] AIGC_WORDS = {
            "aigc", "tc260", "ai_gen", "generated_by_ai", "ai-generated", "深度合成", "生成合成",
            "ai生成", "人工智能生成",
            // GB 45438 / TC260 那套隐式标识 JSON 的字段名，出现即认定是 AIGC 标识
            "contentproducer", "contentpropagator", "produceid", "reservedcode",
    };

    /** 需要下钻的容器盒。 */
    private static final Set CONTAINERS = set(
            "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "mvex",
            "udta", "meta", "ilst", "moof", "traf");

    /** 子盒之前还带一段 version+flags（4 字节）的容器盒。 */
    private static final Set FULLBOXES = set("meta", "moof", "traf", "mvex");

    /** 纯元数据盒：作废它们不影响解码播放。 */
    private static final Set METADATA = set(
            "udta", "meta", "ilst", "keys", "mdta", "uuid", "XMP ", "xml ", "kind", "chpl",
            "\u00a9too", "\u00a9cmt", "\u00a9day", "\u00a9nam", "\u00a9art", "\u00a9alb",
            "\u00a9gen", "\u00a9lyr", "\u00a9url", "\u00a9grp", "\u00a9wrt", "\u00a9xyz",
            "cprt", "auth", "desc", "loci", "proj", "sele", "hmm\u00a9", "koko",
            "AIGC", "aigr", "catg", "trkn", "gnre", "covr", "data", "mean", "name");

    /** 条目集合盒：它们是「装着一堆条目的容器」，本身不作为精准模式下的单条目标。 */
    private static final Set ENTRY_PARENT = set("udta", "meta", "ilst", "keys", "mdta");

    /** 版权保护相关：绝不碰，误删会让受保护内容彻底播不出来。 */
    private static final Set DRM = set("pssh", "sinf", "senc", "schi", "frma", "tenc", "egid");

    /** 记录绝对文件偏移的样本表：变长写入时必须整体平移。 */
    private static final Set CHUNK_OFFSETS = set("stco", "co64");

    private static final int MAX_DEPTH = 40;
    private static final int MAX_BOXES = 500000;
    private static final int MAX_CHUNK_ENTRIES = 200000;

    /** 结构自检用的必需盒白名单：清洗前后必须逐一对齐。 */
    private static final Set REQUIRED = set(
            "ftyp", "moov", "mdat", "trak", "mdia", "minf", "stbl", "dinf", "edts", "mvex",
            "mvhd", "tkhd", "mdhd", "elst", "stsd", "stts", "stss", "stsc", "stsz", "stco",
            "co64", "ctts", "sdtp", "sbgp", "sgpd", "dref", "moof", "mfhd", "traf", "tfhd",
            "trun");

    private Mp4Scrubber() {
    }

    // ------------------------------------------------------------------ 数据结构

    private static final class Box {
        long off;
        long size;
        int hdr;
        String type;
        long dataOff;
        long dataLen;
        boolean truncated;
        Box parent;
        final List kids = new ArrayList();

        long[] chunk;     // stco/co64 里的绝对分块偏移
        List hits;        // 每项 int[]{相对载荷起点的偏移, 长度}
        int why;          // FLAG_BRAND | FLAG_LAVF | FLAG_AIGC 位或
        String label;     // 命中来源，给界面显示
        String value;     // 给界面展示的可读值
        String kname;     // keys 盒回灌的键名：整数键条目（mdta 方案）没有可读 fourcc
        boolean dead;     // 自己或某个祖先已被作废
        boolean written;  // 已被自定义标识改写
    }

    /** 界面上的一行：视频里当前存在的一条标识 / 元数据。 */
    public static final class Entry {
        public String path;
        public String key;
        public String value = "";
        public long off;
        public long size;
        public int why;               // 命中了哪些开关（0 = 干净，只有彻底模式会动）
        public boolean removable;     // false 表示只能就地抹字段（hdlr、压缩器名）
        public boolean aigc;          // 带 AIGC 标识，默认保留
        public String note = "";

        public String whyText() {
            if (why == 0) {
                return "普通元数据";
            }
            final StringBuilder sb = new StringBuilder();
            if ((why & FLAG_BRAND) != 0) {
                sb.append("剪辑软件");
            }
            if ((why & FLAG_LAVF) != 0) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append("压制器");
            }
            if ((why & FLAG_AIGC) != 0) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append("AIGC");
            }
            return sb.toString();
        }
    }

    /** 用户要写进去的自定义标识。 */
    public static final class Write {
        public String key = "\u00a9too";
        public String text = "";

        public Write() {
        }

        public Write(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }

    /** 一次清洗的全部输入。 */
    public static final class Options {
        public int flags = FLAGS_DEFAULT;
        /** 非 null 时只处理这些 path 的条目（界面逐条勾选）；flags 仍决定 AIGC / 彻底语义。 */
        public Set picked;
        public final List writes = new ArrayList();
    }

    private static final class Scan {
        long fileSize;
        final List top = new ArrayList();
        final List all = new ArrayList();
        final List entries = new ArrayList();
        final List keyNames = new ArrayList();   // keys 盒：下标 0 对应键 ID 1
        boolean fragmented;
        boolean truncated;
        boolean parsed = true;
        int count;
    }

    /** 分析报告。 */
    public static final class Report {
        public long fileSize;
        public boolean fragmented;
        public boolean truncated;
        public String topLevel = "";
        public final List entries = new ArrayList();
        public final Map brandCount = new LinkedHashMap();
        public int hitCount;
        public int aigcCount;
        public String summary = "";
    }

    /** 清洗结果。 */
    public static final class Result {
        public boolean ok;
        public long bytes;
        public long delta;
        public int freeCount;
        public int blankCount;
        public int aigcProtected;
        public int writeCount;
        public String writeSkipped = "";
        public Report report;
        public String message = "";
    }

    /** 进度回调（大文件复制阶段）。 */
    public interface Progress {
        void onStep(String text);
    }

    /** 一段就地补丁。{@code bytes} 非空写定长，否则写 {@code zeroLen} 个 0。 */
    private static final class Edit {
        long off;
        int seq;
        final byte[] bytes;
        final long zeroLen;

        Edit(long off, byte[] bytes) {
            this.off = off;
            this.bytes = bytes;
            this.zeroLen = bytes.length;
        }

        Edit(long off, long zeroLen) {
            this.off = off;
            this.bytes = null;
            this.zeroLen = zeroLen;
        }
    }

    private static final class Ctx {
        final List edits = new ArrayList();
        final Set writtenPaths = new HashSet();
        final Set growChain = new HashSet();   // 插入点落在这些盒里：禁止整岛作废，且要改 size
        long appendAt = -1;
        long appendDelta;
        byte[] appendBytes;
        int seqGen;
        int freeCount;
        int blankCount;
        int aigcProtected;
        int writeCount;
    }

    // ------------------------------------------------------------------ 对外入口

    /** 只分析：这个文件里现在有哪些标识 / 元数据。 */
    public static Report analyze(File f) throws IOException {
        return report(scanFile(f));
    }

    /** 便捷入口：按开关清洗。 */
    public static Result scrub(File in, File out, int flags, Progress cb) throws IOException {
        final Options o = new Options();
        o.flags = flags;
        return scrub(in, out, o, cb);
    }

    /** 便捷入口：只清界面里勾选出来的那几条。 */
    public static Result scrubPicked(File in, File out, Set paths, int flags, Progress cb)
            throws IOException {
        final Options o = new Options();
        o.flags = flags;
        o.picked = paths;
        return scrub(in, out, o, cb);
    }

    /** 清洗：把 in 复制到 out，在 out 上作废标识盒 / 写入自定义标识。in 不会被改动。 */
    public static Result scrub(File in, File out, Options opt, Progress cb) throws IOException {
        final Result r = new Result();
        if (opt == null) {
            opt = new Options();
        }
        if (in.getCanonicalPath().equals(out.getCanonicalPath())) {
            r.message = "输入和输出是同一个文件，为避免改坏原件已停止。";
            return r;
        }
        final Scan scan = scanFile(in);
        r.report = report(scan);
        if (!scan.parsed || scan.top.isEmpty()) {
            r.message = "解析不出盒子，这不是 MP4/MOV，或者文件头已经坏了。";
            return r;
        }

        final Ctx ctx = new Ctx();
        decideWrites(scan, opt, ctx, r);   // 先定插入点：它决定哪些岛不能整块作废
        plan(scan, opt, ctx);
        if (ctx.appendAt >= 0) {
            patchChunkOffsets(scan, ctx);
        }
        r.delta = ctx.appendDelta;

        if (r.delta != 0 && scan.fragmented) {
            r.message = "这是分片 MP4，新增标识要搬动全部数据偏移，已停止以免改坏文件。"
                    + "去掉自定义标识这一项再试。";
            return r;
        }

        if (ctx.appendAt < 0) {
            copy(in, out, cb, r);
            applyEdits(out, ctx.edits);
        } else {
            rebuild(in, out, ctx, cb);
        }

        final Scan after = scanFile(out);
        r.bytes = after.fileSize;
        r.freeCount = ctx.freeCount;
        r.blankCount = ctx.blankCount;
        r.aigcProtected = ctx.aigcProtected;
        r.writeCount = ctx.writeCount;

        if (r.delta == 0) {
            if (after.fileSize != scan.fileSize) {
                r.message = "内部错误：清洗后长度变了（" + group(scan.fileSize) + " → "
                        + group(after.fileSize) + "），请删掉输出文件重试。";
                return r;
            }
            if (!structuralKey(scan).equals(structuralKey(after))) {
                r.message = "内部错误：清洗后结构盒对不上了，输出文件不要用，删掉重试。";
                return r;
            }
        } else {
            final String bad = verifyShift(scan, after, ctx.appendAt, ctx.appendDelta,
                    ctx.growChain);
            if (bad != null) {
                r.message = "内部错误：写入自定义标识后 " + bad + "，输出文件不要用，删掉重试。";
                return r;
            }
        }

        final int left = residue(after, opt, ctx.writtenPaths);
        if (left > 0) {
            r.message = "清洗后仍残留 " + left + " 处要求清除的标识（多半落在超大元数据盒里），详见报告。";
            return r;
        }
        r.ok = true;
        final StringBuilder sb = new StringBuilder("完成：作废元数据盒 ");
        sb.append(ctx.freeCount).append(" 个，抹掉字段 ").append(ctx.blankCount).append(" 处");
        if (ctx.writeCount > 0) {
            sb.append("，写入自定义标识 ").append(ctx.writeCount).append(" 条");
        }
        if (ctx.aigcProtected > 0) {
            sb.append("，按默认保留 AIGC ").append(ctx.aigcProtected).append(" 处");
        }
        sb.append("。画面音频数据未动");
        sb.append(r.delta == 0 ? "，文件长度与原文件逐字节一致。"
                : "，文件增加 " + ctx.appendDelta + " 字节。");
        r.message = sb.toString();
        return r;
    }

    // ------------------------------------------------------------------ 解析 + 内容扫描

    private static Scan scanFile(File f) throws IOException {
        final Scan s = new Scan();
        final RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            s.fileSize = raf.length();
            s.top.addAll(parse(raf, 0, s.fileSize, null, 0, s));
            if (s.top.isEmpty()) {
                s.parsed = false;
            }
            flatten(s.top, s.all);
            for (int i = 0; i < s.all.size(); i++) {
                final Box b = (Box) s.all.get(i);
                if (CHUNK_OFFSETS.contains(b.type)) {
                    b.chunk = readChunkOffsets(raf, b);
                    continue;
                }
                if (!b.kids.isEmpty() || "mdat".equals(b.type) || b.dataLen <= 0) {
                    continue;
                }
                // 只读前 SCAN_CAP 字节做匹配；dataLen 保持真实长度，作废时要把整盒清零。
                final int len = (int) Math.min(b.dataLen, SCAN_CAP);
                inspect(b, readBytes(raf, b.dataOff, len));
            }
            readKeys(raf, s);
            attributeKeys(s);
            collectEntries(s);
        } finally {
            raf.close();
        }
        return s;
    }

    private static List parse(RandomAccessFile raf, long start, long end, Box parent,
                              int depth, Scan s) throws IOException {
        final List out = new ArrayList();
        if (depth > MAX_DEPTH) {
            return out;
        }
        long o = start;
        while (o + 8 <= end) {
            if (s.count++ > MAX_BOXES) {
                s.truncated = true;
                return out;
            }
            final Box b = new Box();
            b.off = o;
            b.parent = parent;
            raf.seek(o);
            long size = readU32(raf);
            b.type = readType(raf);
            b.hdr = 8;
            if (size == 1) {
                b.hdr = 16;
                raf.seek(o + 8);
                size = readU64(raf);
            } else if (size == 0) {
                size = end - o;
            }
            if (size < b.hdr || size > end - o) {
                b.size = end - o;
                b.dataOff = o + b.hdr;
                b.dataLen = Math.max(0, b.size - b.hdr);
                b.truncated = true;
                out.add(b);
                s.truncated = true;
                return out;
            }
            b.size = size;
            b.dataOff = o + b.hdr;
            b.dataLen = size - b.hdr;
            out.add(b);
            if ("moof".equals(b.type) || "sidx".equals(b.type)) {
                s.fragmented = true;
            }
            if (CONTAINERS.contains(b.type) || isIlstItem(b)) {
                final int skip = FULLBOXES.contains(b.type) ? 4 : 0;
                b.kids.addAll(parse(raf, b.dataOff + skip, o + size, b, depth + 1, s));
            } else if ("stsd".equals(b.type) && b.dataLen >= 8) {
                // sample entry 本身也是盒子，但内部字段布局太杂，整块当叶子扫描。
                b.kids.addAll(parse(raf, b.dataOff + 8, o + size, b, depth + 1, s));
            }
            o += size;
        }
        return out;
    }

    private static void flatten(List top, List out) {
        for (int i = 0; i < top.size(); i++) {
            final Box b = (Box) top.get(i);
            out.add(b);
            flatten(b.kids, out);
        }
    }

    private static long[] readChunkOffsets(RandomAccessFile raf, Box b) throws IOException {
        final boolean wide = "co64".equals(b.type);
        final int stride = wide ? 8 : 4;
        if (b.dataLen < 8 + stride) {
            return null;
        }
        // 载荷是 version+flags(4) + entry_count(4) + n 个偏移，表长一律按 entry_count 与剩余字节取小。
        raf.seek(b.dataOff + 4);
        final long count = readU32(raf);
        final long n = Math.min(count, (b.dataLen - 8) / stride);
        if (n <= 0 || n > MAX_CHUNK_ENTRIES) {
            return null;
        }
        final long[] v = new long[(int) n];
        for (int i = 0; i < n; i++) {
            v[i] = wide ? readU64(raf) : readU32(raf);
        }
        return v;
    }

    /** 在叶子载荷里找 AIGC 标识、品牌词、压制器指纹；命中位置记成相对载荷的字节偏移。 */
    private static void inspect(Box b, byte[] payload) {
        if ("keys".equals(b.type)) {
            return;   // 只当登记表读，AIGC 归属交给 attributeKeys 按条目判定
        }
        final String raw = new String(payload, LATIN);   // 一字节一字符，下标即字节偏移
        final String ci = raw.toLowerCase(Locale.ROOT);
        b.value = printableRun(payload);

        scanGroup(b, raw, ci, payload, AIGC_WORDS, FLAG_AIGC, "AI 生成合成标识");
        scanGroup(b, raw, ci, payload, MUXER_WORDS, FLAG_LAVF, "封装器署名");
        scanGroup(b, raw, ci, payload, LAVF_WORDS, FLAG_LAVF, "压制器指纹");
        if (hdlrLike(b)) {
            scanGroup(b, raw, ci, payload, GENERIC_HANDLERS, FLAG_LAVF, "ffmpeg 默认 hdlr 名");
        }
        if (isParamComment(b)) {
            scanGroup(b, raw, ci, payload, PARAM_WORDS, FLAG_LAVF, "转码参数注释");
        }
        for (int g = 0; g < BRANDS.length; g++) {
            scanGroup(b, raw, ci, payload, groupWords(BRANDS[g]), FLAG_BRAND, BRANDS[g][0]);
        }
    }

    /**
     * 是不是「把转码参数塞进注释」那种写法。
     *
     * <p>下钻 {@code ilst} 条目之后，{@code ©cmt} 的文本叶子类型是 {@code data}，只按
     * {@code b.type} 判会把快影那条 {@code [s=1080x1080][vb=12000k][ab=192k]} 漏掉，
     * 所以要连着看父盒的键。</p>
     */
    private static boolean isParamComment(Box b) {
        if ("\u00a9cmt".equals(b.type)) {
            return true;
        }
        for (Box c = b; c != null; c = c.parent) {
            if ("\u00a9cmt".equals(c.type)) {
                return true;
            }
        }
        return false;
    }

    /** 一组词的扫描：命中就把「外扩到整条可读串」的字节范围记进 hits。 */
    private static void scanGroup(Box b, String raw, String ci, byte[] p, String[] words,
                                  int why, String label) {
        for (int i = 0; i < words.length; i++) {
            final String word = words[i];
            int at;
            int len;
            if (isAscii(word)) {
                at = ci.indexOf(word);
                len = word.length();
            } else {
                int[] r = locate(raw, word.getBytes(UTF8));
                if (r == null) {
                    r = locate(raw, word.getBytes(GBK));
                }
                at = r == null ? -1 : r[0];
                len = r == null ? 0 : r[1];
            }
            if (at < 0) {
                continue;
            }
            if (b.label == null || why == FLAG_BRAND) {
                b.why |= why;
                b.label = label;
            } else {
                b.why |= why;
            }
            addSpan(b, p, at, len);
        }
    }

    private static String[] groupWords(String[] group) {
        final String[] w = new String[group.length - 1];
        System.arraycopy(group, 1, w, 0, w.length);
        return w;
    }

    /**
     * 把命中范围外扩到整条可读串。只抹品牌词会把「剪映专业版」剩成「专业版」，比原文更容易
     * 被认出来；命中落在必需盒（hdlr、sample entry 的压缩器名）里时，抹的就是整条串。
     */
    private static void addSpan(Box b, byte[] p, int at, int len) {
        int s = at;
        int e = at + len;
        while (s > 0 && readable(p[s - 1]) && at - s < 200) {
            s--;
        }
        while (e < p.length && readable(p[e]) && e - (at + len) < 200) {
            e++;
        }
        if (s > 0 && (p[s - 1] & 0xFF) == e - s) {
            s--; // Pascal 串：把长度字节一起算进要清零的范围
        }
        if (b.hits == null) {
            b.hits = new ArrayList();
        }
        for (int i = 0; i < b.hits.size(); i++) {
            final int[] k = (int[]) b.hits.get(i);
            if (k[0] <= s && e <= k[0] + k[1]) {
                return;
            }
        }
        b.hits.add(new int[]{s, e - s});
    }

    private static boolean hdlrLike(Box b) {
        return "hdlr".equals(b.type) || "nhdl".equals(b.type);
    }

    /** 取载荷里最长的可打印串，给界面显示这条标识的内容。 */
    private static String printableRun(byte[] p) {
        int best = -1;
        int bestLen = 0;
        int i = 0;
        while (i < p.length) {
            if (readable(p[i])) {
                int s = i;
                while (i < p.length && readable(p[i])) {
                    i++;
                }
                if (i - s > bestLen) {
                    best = s;
                    bestLen = i - s;
                }
            } else {
                i++;
            }
        }
        if (best < 0 || bestLen < 2) {
            return "";
        }
        String s = new String(p, best, bestLen, UTF8);
        if (s.indexOf('\uFFFD') >= 0) {
            s = new String(p, best, bestLen, GBK);
        }
        final StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length() && sb.length() < 120; k++) {
            final char c = s.charAt(k);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 在 LATIN 视图里按字节序列定位。 */
    private static int[] locate(String hay, byte[] needle) {
        if (needle.length == 0 || hay.length() < needle.length) {
            return null;
        }
        final int at = hay.indexOf(new String(needle, LATIN));
        return at < 0 ? null : new int[]{at, needle.length};
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean readable(byte b) {
        final int v = b & 0xFF;
        return v >= 0x20 && v != 0x7F;
    }

    // ------------------------------------------------------------------ 现有标识清单

    /**
     * 把每条可操作的元数据 / 字段整理成一行，供界面勾选；命中的和没命中的都列出来，
     * 这样用户能先看清「这个视频现在到底写了什么」再决定删哪条。
     */
    private static void collectEntries(Scan s) {
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            final Box entry = entryOf(b);
            if (entry != null) {
                // udta / meta / ilst 是装条目的容器，不当单条目标（彻底模式会整岛处理）
                if (ENTRY_PARENT.contains(entry.type) || indexOf(s.entries, entry.off) >= 0) {
                    continue;
                }
                final Entry en = new Entry();
                en.path = pathOf(entry);
                en.key = boxName(entry);
                en.off = entry.off;
                en.size = entry.size;
                en.why = mergedWhy(entry);
                en.aigc = hasAigc(entry);
                en.removable = true;
                en.value = leafValue(entry);
                en.note = noteFor(en, entry);
                s.entries.add(en);
                continue;
            }
            if ((hdlrLike(b) || isSampleEntry(b)) && b.why != 0
                    && indexOf(s.entries, b.off) < 0) {
                final Entry en = new Entry();
                en.path = pathOf(b);
                en.key = tag(b.type);
                en.off = b.off;
                en.size = b.size;
                en.why = b.why;
                en.aigc = (b.why & FLAG_AIGC) != 0;
                en.removable = false;
                en.value = b.value == null ? "" : b.value;
                en.note = "必需盒，只抹命中的字段";
                s.entries.add(en);
            }
        }
    }

    private static int indexOf(List list, long off) {
        for (int i = 0; i < list.size(); i++) {
            if (((Entry) list.get(i)).off == off) {
                return i;
            }
        }
        return -1;
    }

    private static int mergedWhy(Box entry) {
        int w = entry.why;
        for (int i = 0; i < entry.kids.size(); i++) {
            w |= mergedWhy((Box) entry.kids.get(i));
        }
        return w;
    }

    /** 命中来源标签：下钻以后真正命中的是条目盒里的 data 叶，标签得从子树并上来。 */
    private static String mergedLabel(Box entry) {
        if (entry.label != null) {
            return entry.label;
        }
        for (int i = 0; i < entry.kids.size(); i++) {
            final String l = mergedLabel((Box) entry.kids.get(i));
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private static String noteFor(Entry en, Box entry) {
        if ("covr".equals(entry.type)) {
            return "封面图像";
        }
        final String label = mergedLabel(entry);
        if ((en.why & FLAG_BRAND) != 0) {
            return label == null ? "命中剪辑软件词" : "命中：" + label;
        }
        if ((en.why & FLAG_LAVF) != 0) {
            return label == null ? "压制器指纹" : label;
        }
        if ((en.why & FLAG_AIGC) != 0) {
            return "AI 生成合成标识";
        }
        return "普通元数据（默认不动）";
    }

    private static String leafValue(Box entry) {
        if (entry.kids.isEmpty()) {
            return entry.value == null ? "" : entry.value;
        }
        final String v = leafValue0(entry);
        return v == null ? "" : v;
    }

    private static String leafValue0(Box b) {
        if (b.kids.isEmpty()) {
            return b.value;
        }
        for (int i = 0; i < b.kids.size(); i++) {
            final Box k = (Box) b.kids.get(i);
            final String v = k.kids.isEmpty() ? k.value : leafValue0(k);
            if (v != null && v.length() > 0) {
                return v;
            }
        }
        return null;
    }

    private static boolean isSampleEntry(Box b) {
        return b != null && b.parent != null && "stsd".equals(b.parent.type);
    }

    /**
     * 命中所在的「可作废条目」：往上并到条目层再停。
     *
     * <p>QuickTime 元数据的实际形状是 {@code udta/meta/ilst/©too/data}，真正要连着清掉的是
     * 条目盒（{@code ©too}）而不是它里面那个 {@code data} 值盒——只清 {@code data} 会在
     * 条目盒里留下一个头字段全零的残盒子，严格播放器可能报错。所以只要父盒还是个「包装盒」
     * （属于元数据）就一直往上并；并到 {@code ilst}／{@code udta} 这类条目集合时，
     * 作废的就是这一条，兄弟条目原样保留。</p>
     */
    private static Box entryOf(Box b) {
        if (!isRemovableMetadata(b) || hasDrmAncestor(b)) {
            return null; // 命中落在必需盒（hdlr、stsd 的压缩器名等）里，只抹命中的那几个字节
        }
        Box t = b;
        while (t.parent != null && isRemovableMetadata(t.parent)
                && !ENTRY_PARENT.contains(t.parent.type)) {
            t = t.parent;
        }
        return t;
    }

    private static boolean isRemovableMetadata(Box b) {
        return b != null && (METADATA.contains(b.type) || isIlstItem(b)) && !DRM.contains(b.type)
                && !"mdat".equals(b.type) && !b.truncated;
    }

    /**
     * 是不是 {@code ilst} 的直接孩子（一条元数据条目）。
     *
     * <p>两种方案条目头不一样：剪映 / 快影写的 {@code ©too}、{@code desc} 是可读 fourcc；
     * 豆包 / 即梦用的是 {@code keys} + {@code mdta} 方案，条目头是 4 字节大端整数键 ID，
     * 真名字存在兄弟盒 {@code keys} 里。后者过不了 METADATA 白名单，不特殊处理的话整条
     * AIGC 标识在清单里连影子都没有。</p>
     */
    private static boolean isIlstItem(Box b) {
        return b != null && !b.truncated && b.parent != null && "ilst".equals(b.parent.type)
                && !ENTRY_PARENT.contains(b.type) && !DRM.contains(b.type);
    }

    /** 整数键 ID；不是「00 00 00 id」形式返回 0。 */
    private static int keyId(String t) {
        if (t == null || t.length() != 4 || t.charAt(0) != 0 || t.charAt(1) != 0
                || t.charAt(2) != 0) {
            return 0;
        }
        return t.charAt(3) & 0xFF;
    }

    /** 界面与路径用的名字：整数键条目用 keys 里的键名，其余用 fourcc。 */
    private static String boxName(Box b) {
        return b.kname != null && b.kname.length() > 0 ? b.kname : tag(b.type);
    }

    /** 读 {@code keys}：载荷是 version+flags(4) + 条目数(4) + 每项 size(4)+ostype(4)+键名。 */
    private static void readKeys(RandomAccessFile raf, Scan s) throws IOException {
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (!"keys".equals(b.type) || b.dataLen < 12) {
                continue;
            }
            final byte[] p = readBytes(raf, b.dataOff, (int) Math.min(b.dataLen, 1 << 16));
            final long n = u32at(p, 4);
            int o = 8;
            for (int e = 0; e < n && o + 8 <= p.length; e++) {
                final long sz = u32at(p, o);
                if (sz < 8 || o + sz > p.length) {
                    break;
                }
                final String os = new String(p, o + 4, 4, LATIN);
                final String nm = new String(p, o + 8, (int) sz - 8, LATIN);
                s.keyNames.add("mdta".equals(os) ? nm : os + nm);
                o += (int) sz;
            }
            return;   // 一个 meta 里只有一套 keys
        }
    }

    /** 把键名回灌给整数键条目：显示用；键名带 AIGC 字样的直接判定为 AIGC 标识。 */
    private static void attributeKeys(Scan s) {
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (!isIlstItem(b)) {
                continue;
            }
            final int id = keyId(b.type);
            if (id <= 0 || id > s.keyNames.size()) {
                continue;
            }
            final String nm = (String) s.keyNames.get(id - 1);
            if (nm == null || nm.length() == 0) {
                continue;
            }
            b.kname = nm;
            if ((b.why & FLAG_AIGC) == 0 && matchesAigc(nm)) {
                b.why |= FLAG_AIGC;
                b.label = "AI 生成合成标识（键名）";
            }
        }
    }

    private static boolean matchesAigc(String name) {
        final String lc = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < AIGC_WORDS.length; i++) {
            if (isAscii(AIGC_WORDS[i]) && lc.indexOf(AIGC_WORDS[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static long u32at(byte[] p, int at) {
        return ((p[at] & 0xFFL) << 24) | ((p[at + 1] & 0xFFL) << 16)
                | ((p[at + 2] & 0xFFL) << 8) | (p[at + 3] & 0xFFL);
    }

    private static boolean hasDrmAncestor(Box b) {
        for (Box p = b.parent; p != null; p = p.parent) {
            if (DRM.contains(p.type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAigc(Box b) {
        if ((b.why & FLAG_AIGC) != 0) {
            return true;
        }
        for (int i = 0; i < b.kids.size(); i++) {
            if (hasAigc((Box) b.kids.get(i))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 计划

    private static final int ACT_SKIP = 0;
    private static final int ACT_DO = 1;
    private static final int ACT_PROTECT = 2;

    private static void plan(Scan s, Options o, Ctx ctx) {
        for (int i = 0; i < s.entries.size(); i++) {
            final Entry en = (Entry) s.entries.get(i);
            final Box b = boxAt(s, en.off);
            if (b == null || b.dead || b.written) {
                continue;
            }
            final int act = actionFor(en, o);
            if (act == ACT_PROTECT) {
                ctx.aigcProtected++;
                continue;
            }
            if (act != ACT_DO) {
                continue;
            }
            if (en.removable) {
                freeMetadata(b, ctx, (o.flags & FLAG_AIGC) != 0);
            } else {
                blankHits(b, ctx);
                if ((o.flags & FLAG_FULL) != 0 && hdlrLike(b)) {
                    blankHandlerName(b, ctx);
                }
            }
        }
        if (o.picked == null && (o.flags & FLAG_FULL) != 0) {
            walkFull(s.top, ctx, (o.flags & FLAG_AIGC) != 0);
        }
    }

    /** 这条要不要动：界面勾选优先；没勾 AIGC 开关时 AIGC 条目永远保留。 */
    private static int actionFor(Entry en, Options o) {
        final boolean aigcOn = (o.flags & FLAG_AIGC) != 0;
        final boolean brandOn = (o.flags & FLAG_BRAND) != 0;
        final boolean lavfOn = (o.flags & FLAG_LAVF) != 0;
        final boolean full = (o.flags & FLAG_FULL) != 0;

        boolean wanted;
        if (o.picked != null) {
            wanted = o.picked.contains(en.path);
        } else {
            wanted = full
                    || (brandOn && (en.why & FLAG_BRAND) != 0)
                    || (lavfOn && (en.why & FLAG_LAVF) != 0)
                    || (aigcOn && (en.why & FLAG_AIGC) != 0);
        }
        if (!wanted) {
            return ACT_SKIP;
        }
        return (!aigcOn && en.aigc) ? ACT_PROTECT : ACT_DO;
    }

    /** 彻底模式：自上而下，能整岛作废就作废；留下来的 hdlr 名字也抹平。 */
    private static void walkFull(List list, Ctx ctx, boolean aigcOn) {
        for (int i = 0; i < list.size(); i++) {
            final Box b = (Box) list.get(i);
            if (b.dead) {
                continue;
            }
            if (isRemovableMetadata(b) && !hasDrmAncestor(b)) {
                freeMetadata(b, ctx, aigcOn);
                continue;
            }
            if (hdlrLike(b) && b.kids.isEmpty()) {
                blankHandlerName(b, ctx);
            }
            walkFull(b.kids, ctx, aigcOn);
        }
    }

    /**
     * 作废一整个元数据岛。岛里若混着要保留的 AIGC 标识、或插入点落在岛里（新增自定义标识），
     * 就退回逐条处理：那些子树原样留下，其余元数据子盒作废。
     */
    private static void freeMetadata(Box island, Ctx ctx, boolean aigcOn) {
        if (island.dead) {
            return;
        }
        final boolean aigcHold = !aigcOn && hasAigc(island);
        if (aigcHold || ctx.growChain.contains(Long.valueOf(island.off))) {
            if (aigcHold) {
                ctx.aigcProtected++;
            }
            for (int i = 0; i < island.kids.size(); i++) {
                final Box k = (Box) island.kids.get(i);
                if (k.dead) {
                    continue;
                }
                // 整数键条目的名字全存在 keys 表里：留了 AIGC 就得连字典一起留，
                // 否则留下的标识没有键名，平台读不出它是 AIGC。
                if (aigcHold && "keys".equals(k.type)) {
                    continue;
                }
                if (isRemovableMetadata(k) && !hasDrmAncestor(k)) {
                    freeMetadata(k, ctx, aigcOn);
                } else if (hdlrLike(k)) {
                    blankHandlerName(k, ctx);
                }
            }
            return;
        }
        markDead(island);
        ctx.edits.add(next(ctx, new Edit(island.off + 4, FREE)));            // fourcc -> free
        ctx.edits.add(next(ctx, new Edit(island.dataOff, island.dataLen)));  // 载荷清零
        ctx.freeCount++;
    }

    private static Edit next(Ctx ctx, Edit e) {
        e.seq = ctx.seqGen++;
        return e;
    }

    private static void markDead(Box b) {
        b.dead = true;
        for (int i = 0; i < b.kids.size(); i++) {
            markDead((Box) b.kids.get(i));
        }
    }

    /** 命中落在必需盒里：只把命中的那几段字节抹平，盒结构一个不动。 */
    private static void blankHits(Box b, Ctx ctx) {
        if (b.hits == null || b.dead) {
            return;
        }
        for (int h = 0; h < b.hits.size(); h++) {
            final int[] k = (int[]) b.hits.get(h);
            ctx.edits.add(next(ctx, new Edit(b.dataOff + k[0], new byte[k[1]])));
            ctx.blankCount++;
        }
    }

    private static void blankHandlerName(Box b, Ctx ctx) {
        // hdlr：version+flags(4) + predefined(4) + handler_type(4) + reserved(12) 之后才是名字
        if (b.dataLen <= 24 || b.dead) {
            return;
        }
        ctx.edits.add(next(ctx, new Edit(b.dataOff + 24, b.dataLen - 24)));
        ctx.blankCount++;
    }

    // ------------------------------------------------------------------ 写入自定义标识

    /**
     * 定下每条自定义标识的去向：文件里已有同键条目（含刚被作废的，作废只改类型不改长度）就
     * 原地复用，长度不变；实在放不下才在元数据表尾部追加，并占住祖先岛不许整块作废。
     */
    private static void decideWrites(Scan s, Options o, Ctx ctx, Result r) {
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final List pendingPaths = new ArrayList();
        for (int i = 0; i < o.writes.size(); i++) {
            final Write w = (Write) o.writes.get(i);
            if (w == null || w.text == null || w.text.length() == 0) {
                continue;
            }
            final byte[] entry = buildEntryBox(w);
            if (entry == null) {
                r.writeSkipped = "自定义标识的键名必须是 4 个字节（例如 \u00a9too、\u00a9cmt、AIGC），已跳过。";
                continue;
            }
            final Box host = reusableHost(s, w.key, entry.length - 8);
            if (host != null) {
                final byte[] fill = new byte[(int) host.dataLen];
                System.arraycopy(entry, 8, fill, 0, entry.length - 8);  // 余下全是 NUL
                ctx.edits.add(next(ctx, new Edit(host.off + 4, fourcc(w.key))));
                ctx.edits.add(next(ctx, new Edit(host.dataOff, fill)));
                host.written = true;
                ctx.writtenPaths.add(pathOf(host));
                ctx.writeCount++;
                continue;
            }
            pending.write(entry, 0, entry.length);
            pendingPaths.add(tag(w.key));
        }
        if (pending.size() == 0) {
            return;
        }
        final Box table = findMetadataTable(s);
        if (table == null) {
            if (r.writeSkipped.length() == 0) {
                r.writeSkipped = "这个文件里没有元数据表（udta / ilst），无法新增标识，"
                        + "只能改写已有的那几条。";
            }
            return;
        }
        final byte[] add = pending.toByteArray();
        ctx.appendAt = table.off + table.size;
        ctx.appendDelta = add.length;
        ctx.appendBytes = add;
        for (Box p = table; p != null; p = p.parent) {
            ctx.growChain.add(Long.valueOf(p.off));
            ctx.edits.add(next(ctx, sizePatch(p, add.length)));
        }
        for (int i = 0; i < pendingPaths.size(); i++) {
            ctx.writtenPaths.add(pathOf(table) + "/" + pendingPaths.get(i) + "(新增)");
            ctx.writeCount++;
        }
    }

    /** QuickTime 文本条目：key{ data{ version+flags=1, reserved=0, utf8 } } */
    private static byte[] buildEntryBox(Write w) {
        final byte[] k = fourcc(w.key);
        if (k == null) {
            return null;
        }
        final byte[] text = w.text.getBytes(UTF8);
        final int dataSize = 16 + text.length;
        final byte[] out = new byte[8 + dataSize];
        int p = be32(out, 0, dataSize + 8);
        System.arraycopy(k, 0, out, p, 4);
        p += 4;
        p = be32(out, p, dataSize);
        System.arraycopy(DATA, 0, out, p, 4);
        p += 4;
        p = be32(out, p, 1);   // version=0 flags=1：UTF-8 文本
        p = be32(out, p, 0);   // reserved / locale
        System.arraycopy(text, 0, out, p, text.length);
        return out;
    }

    /** 恰好 4 个字节的 fourcc；允许 "©nam" 这类带高位字节的名。 */
    private static byte[] fourcc(String key) {
        if (key == null) {
            return null;
        }
        final byte[] b = key.getBytes(LATIN);
        if (b.length != 4) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            final int v = b[i] & 0xFF;
            if (v < 0x20 || v == 0x7F) {
                return null;
            }
        }
        return b;
    }

    private static int be32(byte[] out, int at, long v) {
        out[at] = (byte) (v >> 24);
        out[at + 1] = (byte) (v >> 16);
        out[at + 2] = (byte) (v >> 8);
        out[at + 3] = (byte) v;
        return at + 4;
    }

    /** 找一个能原地改写的同键条目：内层 {@code data} 盒装得下新值，且没被别的改写占用。 */
    private static Box reusableHost(Scan s, String key, int need) {
        final byte[] k = fourcc(key);
        if (k == null) {
            return null;
        }
        final String want = new String(k, LATIN);
        Box best = null;
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (!want.equals(b.type) || b.written || b.truncated || hasDrmAncestor(b)
                    || ENTRY_PARENT.contains(b.type)) {
                continue;
            }
            if (b.parent == null || !isRemovableMetadata(b.parent)) {
                continue;
            }
            if (b.dataLen >= need && b.dataLen <= WRITE_PAD_CAP
                    && (best == null || b.dataLen < best.dataLen)) {
                best = b;
            }
        }
        return best;
    }

    /** 能安全追加的位置：优先 ilst，退回 udta；都没有就不新增。 */
    private static Box findMetadataTable(Scan s) {
        Box udta = null;
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (b.truncated || hasDrmAncestor(b) || !isRemovableMetadata(b)) {
                continue;
            }
            if ("ilst".equals(b.type)) {
                return b;
            }
            if (udta == null && "udta".equals(b.type)) {
                udta = b;
            }
        }
        return udta;
    }

    /** 插入点之后的样本偏移整体后移；之前的一个不动。 */
    private static void patchChunkOffsets(Scan s, Ctx ctx) {
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (b.chunk == null) {
                continue;
            }
            final int stride = "co64".equals(b.type) ? 8 : 4;
            for (int e = 0; e < b.chunk.length; e++) {
                final long v = b.chunk[e];
                if (v < ctx.appendAt) {
                    continue;
                }
                final long at = b.dataOff + 8 + (long) e * stride;
                ctx.edits.add(next(ctx, new Edit(at, beBytes(v + ctx.appendDelta, stride))));
            }
        }
    }

    private static byte[] beBytes(long v, int width) {
        final byte[] b = new byte[width];
        for (int i = 0; i < width; i++) {
            b[i] = (byte) (v >> (8 * (width - 1 - i)));
        }
        return b;
    }

    /** 改祖先盒的 size 字段（4 字节或 64 位两种写法）。 */
    private static Edit sizePatch(Box b, long delta) {
        if (b.hdr == 16) {
            return new Edit(b.off + 8, beBytes(b.size + delta, 8));
        }
        return new Edit(b.off, beBytes(b.size + delta, 4));
    }

    // ------------------------------------------------------------------ 落盘

    /** 就地补丁：同偏移的按登记先后覆盖，所以「改写」一定赢过「作废」。 */
    private static void applyEdits(File f, List edits) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            writeEdits(raf, sorted(edits));
            raf.getFD().sync();
        } finally {
            raf.close();
        }
    }

    private static List sorted(List edits) {
        final List out = new ArrayList(edits);
        Collections.sort(out, new Comparator() {
            public int compare(Object a, Object b) {
                final Edit x = (Edit) a;
                final Edit y = (Edit) b;
                if (x.off != y.off) {
                    return Long.compare(x.off, y.off);
                }
                return x.seq - y.seq;
            }
        });
        return out;
    }

    private static void writeEdits(RandomAccessFile raf, List sorted) throws IOException {
        final byte[] zeros = new byte[1 << 16];
        for (int i = 0; i < sorted.size(); i++) {
            final Edit e = (Edit) sorted.get(i);
            raf.seek(e.off);
            if (e.bytes != null) {
                raf.write(e.bytes);
            } else {
                long left = e.zeroLen;
                while (left > 0) {
                    final int n = (int) Math.min(zeros.length, left);
                    raf.write(zeros, 0, n);
                    left -= n;
                }
            }
        }
    }

    private static void copy(File in, File out, Progress cb, Result r) throws IOException {
        final RandomAccessFile src = new RandomAccessFile(in, "r");
        final RandomAccessFile dst = new RandomAccessFile(out, "rw");
        try {
            dst.setLength(0);
            final long total = src.length();
            final FileChannel cs = src.getChannel();
            final FileChannel cd = dst.getChannel();
            pump(cs, cd, 0, total, 0, cb, total);
            r.bytes = total;
        } finally {
            dst.getFD().sync();
            close(dst);
            close(src);
        }
    }

    /**
     * 变长写出：原样复制到插入点 → 写新增盒 → 复制余下部分，最后统一落补丁
     * （补丁偏移按「位于插入点之后的整体后移 delta」换算）。
     */
    private static void rebuild(File in, File out, Ctx ctx, Progress cb) throws IOException {
        final RandomAccessFile src = new RandomAccessFile(in, "r");
        final RandomAccessFile dst = new RandomAccessFile(out, "rw");
        try {
            dst.setLength(0);
            final FileChannel cs = src.getChannel();
            final FileChannel cd = dst.getChannel();
            final long total = src.length();
            pump(cs, cd, 0, ctx.appendAt, 0, cb, total);
            // transferFrom 用的是显式目标位置，不会推进通道自身位置，这里必须自己挪到插入点
            cd.position(ctx.appendAt);
            cd.write(ByteBuffer.wrap(ctx.appendBytes));
            cd.position(ctx.appendAt + ctx.appendBytes.length);
            pump(cs, cd, ctx.appendAt, total, ctx.appendDelta, cb, total);
            dst.setLength(total + ctx.appendDelta);
            dst.getFD().sync();
        } finally {
            close(dst);
            close(src);
        }

        final List edits = new ArrayList(ctx.edits);
        for (int i = 0; i < edits.size(); i++) {
            final Edit e = (Edit) edits.get(i);
            if (e.off >= ctx.appendAt) {
                e.off += ctx.appendDelta;
            }
        }
        final RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            writeEdits(raf, sorted(edits));
            raf.getFD().sync();
        } finally {
            raf.close();
        }
    }

    /**
     * 顺序搬运一段字节。目标位置 = 源位置 + {@code shift}：变长写出时插入点之后的那段必须
     * 整体后移，否则会把刚插进去的新盒盖掉。
     */
    private static void pump(FileChannel cs, FileChannel cd, long from, long to, long shift,
                             Progress cb, long total) throws IOException {
        final long step = 1L << 24;
        long pos = from;
        while (pos < to) {
            final long want = Math.min(step, to - pos);
            long moved = 0;
            while (moved < want) {
                final long n = cd.transferFrom(cs, pos + moved + shift, want - moved);
                if (n <= 0) {
                    return;
                }
                moved += n;
            }
            pos += moved;
            if (cb != null && total > 0) {
                cb.onStep("写出中 " + (int) (pos * 100 / total) + "%");
            }
        }
    }

    private static void close(RandomAccessFile f) {
        try {
            f.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 必需盒的「类型@偏移#大小」清单：长度不变的清洗前后必须逐字相同。元数据盒（含被作废成
     * free 的那些）一律不参与比较，它们本来就该消失。
     */
    private static String structuralKey(Scan s) {
        final List keys = new ArrayList();
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (REQUIRED.contains(b.type)) {
                keys.add(tag(b.type) + "@" + b.off + "#" + b.size);
            }
        }
        Collections.sort(keys);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            sb.append(keys.get(i)).append(' ');
        }
        return sb.toString();
    }

    /**
     * 变长写入后：必需盒要么不动、要么整体后移 delta；插入点所在祖先链（growChain）的 size
     * 加上 delta。用链上的盒判断，不能按「插入点是否落在盒内」算 —— 追加位置常常正好是
     * ilst / meta / udta / moov 的末尾边界，几何包含判断会漏掉整条链。
     */
    private static String verifyShift(Scan before, Scan after, long appendAt, long delta,
                                      Set growChain) {
        final Set keys = new HashSet();
        int afterCount = 0;
        for (int i = 0; i < after.all.size(); i++) {
            final Box b = (Box) after.all.get(i);
            if (REQUIRED.contains(b.type)) {
                keys.add(tag(b.type) + "@" + b.off + "#" + b.size);
                afterCount++;
            }
        }
        int beforeCount = 0;
        for (int i = 0; i < before.all.size(); i++) {
            final Box b = (Box) before.all.get(i);
            if (!REQUIRED.contains(b.type)) {
                continue;
            }
            beforeCount++;
            final long eoff = b.off >= appendAt ? b.off + delta : b.off;
            final long esize = growChain.contains(Long.valueOf(b.off)) ? b.size + delta : b.size;
            if (!keys.contains(tag(b.type) + "@" + eoff + "#" + esize)) {
                return "必需盒 /" + tag(b.type) + "（原 @" + group(b.off) + "，应 @" + group(eoff)
                        + "）对不上";
            }
        }
        if (afterCount != beforeCount) {
            return "必需盒数量变了（" + beforeCount + " → " + afterCount + "）";
        }
        // 只核对盒子几何不够：偏移表里漏掉一项，盒子大小完全对得上，文件照样播不出来。
        final List old = chunkTables(before);
        final List neu = chunkTables(after);
        if (old.size() != neu.size()) {
            return "分块偏移表数量变了（" + old.size() + " → " + neu.size() + "）";
        }
        for (int i = 0; i < old.size(); i++) {
            final long[] a = (long[]) old.get(i);
            final long[] c = (long[]) neu.get(i);
            if (a.length != c.length) {
                return "分块偏移表条目数变了（" + a.length + " → " + c.length + "）";
            }
            for (int e = 0; e < a.length; e++) {
                final long expect = a[e] >= appendAt ? a[e] + delta : a[e];
                if (c[e] != expect) {
                    return "第 " + (e + 1) + " 个分块偏移没搬对（应 " + group(expect) + "，实 "
                            + group(c[e]) + "）";
                }
            }
        }
        return null;
    }

    /** 按文档顺序取出全部 stco/co64 的偏移表，供搬移前后逐项对照。 */
    private static List chunkTables(Scan s) {
        final List out = new ArrayList();
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (b.chunk != null) {
                out.add(b.chunk);
            }
        }
        return out;
    }

    /** 清洗后还剩几处「用户要求清掉」的标识；用户自己写进去的那几条不算残留。 */
    private static int residue(Scan after, Options o, Set writtenPaths) {
        int n = 0;
        for (int i = 0; i < after.entries.size(); i++) {
            final Entry en = (Entry) after.entries.get(i);
            if (en.why == 0 || writtenPaths.contains(en.path)) {
                continue;
            }
            final boolean aigcOnly = en.aigc && en.why == FLAG_AIGC;
            if (aigcOnly && (o.flags & FLAG_AIGC) == 0) {
                continue; // 按规则保留，不算残留
            }
            if ((o.picked != null && o.picked.contains(en.path))
                    || ((o.flags & FLAG_FULL) != 0 && en.removable)) {
                n++;
                continue;
            }
            if ((o.flags & FLAG_BRAND) != 0 && (en.why & FLAG_BRAND) != 0) {
                n++;
                continue;
            }
            if ((o.flags & FLAG_LAVF) != 0 && (en.why & FLAG_LAVF) != 0) {
                n++;
                continue;
            }
            if ((o.flags & FLAG_AIGC) != 0 && (en.why & FLAG_AIGC) != 0) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 报告文案

    private static Report report(Scan s) {
        final Report rep = new Report();
        rep.fileSize = s.fileSize;
        rep.fragmented = s.fragmented;
        rep.truncated = s.truncated;
        rep.topLevel = joinTypes(s.top);
        rep.entries.addAll(s.entries);
        for (int i = 0; i < s.entries.size(); i++) {
            final Entry en = (Entry) s.entries.get(i);
            if (en.why == 0) {
                continue;
            }
            rep.hitCount++;
            if (en.aigc) {
                rep.aigcCount++;
            }
            final String k = en.whyText();
            Integer n = (Integer) rep.brandCount.get(k);
            rep.brandCount.put(k, n == null ? 1 : n.intValue() + 1);
        }
        rep.summary = text(rep);
        return rep;
    }

    private static String text(Report rep) {
        final StringBuilder sb = new StringBuilder();
        sb.append("容器：").append(rep.topLevel)
                .append("　大小：").append(group(rep.fileSize)).append(" 字节");
        if (rep.truncated) {
            sb.append("\n警告：有盒子声明的长度超过文件末尾，这个文件可能本身就不完整。");
        }
        if (rep.fragmented) {
            sb.append("\n这是分片 MP4：只能就地改字节，不能新增标识。");
        }
        if (rep.entries.isEmpty()) {
            sb.append("\n这个文件里没有任何元数据盒，本来就没有可清除的标识。");
            return sb.toString();
        }
        sb.append("\n现有标识 / 元数据 ").append(rep.entries.size()).append(" 项，命中 ")
                .append(rep.hitCount).append(" 项：");
        int i = 0;
        while (i < rep.entries.size() && i < 60) {
            final Entry e = (Entry) rep.entries.get(i);
            sb.append("\n  ").append(e.why != 0 ? "＊" : "·").append(' ').append(e.path);
            if (e.value.length() > 0) {
                sb.append(" = 「").append(cut(e.value)).append('」');
            }
            sb.append("　").append(e.note);
            if (!e.removable) {
                sb.append("［只抹字段］");
            }
            i++;
        }
        if (rep.entries.size() > 60) {
            sb.append("\n  …其余 ").append(rep.entries.size() - 60).append(" 项略");
        }
        if (rep.aigcCount > 0) {
            sb.append("\n其中 ").append(rep.aigcCount).append(" 项带 AI 生成合成标识，默认保留。")
                    .append("\n").append(AIGC_LEGAL_NOTE);
        }
        return sb.toString();
    }

    private static String cut(String s) {
        return s.length() <= 48 ? s : s.substring(0, 48) + "…";
    }

    private static String joinTypes(List top) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(tag(((Box) top.get(i)).type));
        }
        return sb.toString();
    }

    /**
     * 盒路径。同层出现多个同名盒（两个 trak 的 mdia/hdlr 就是）时补上同型兄弟序号，
     * 保证路径能唯一标识一行，界面勾选才不会一次选中两条。
     */
    private static String pathOf(Box b) {
        final List parts = new ArrayList();
        for (Box c = b; c != null; c = c.parent) {
            parts.add(0, boxName(c) + siblingSuffix(c));
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append('/').append(parts.get(i));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String siblingSuffix(Box b) {
        if (b.parent == null) {
            return "";
        }
        final List kids = b.parent.kids;
        int same = 0;
        int mine = -1;
        for (int i = 0; i < kids.size(); i++) {
            if (b.type.equals(((Box) kids.get(i)).type)) {
                if (kids.get(i) == b) {
                    mine = same;
                }
                same++;
            }
        }
        return same > 1 ? "[" + mine + "]" : "";
    }

    private static Box boxAt(Scan s, long off) {
        for (int i = 0; i < s.all.size(); i++) {
            final Box b = (Box) s.all.get(i);
            if (b.off == off) {
                return b;
            }
        }
        return null;
    }

    private static String tag(String t) {
        if (t == null) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (c == 0xA9) {
                sb.append('©');   // QuickTime 的 ©too / ©cmt 这类键，Latin-1 的 0xA9
            } else {
                sb.append(c >= 0x20 && c < 0x7F ? c : '?');
            }
        }
        return sb.toString().trim();
    }

    static String group(long v) {
        final String s = Long.toString(v);
        final StringBuilder sb = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.insert(0, s.charAt(i));
            if (++c % 3 == 0 && i > 0) {
                sb.insert(0, ',');
            }
        }
        return sb.toString();
    }

    private static Set set(String... w) {
        final Set s = new HashSet();
        for (int i = 0; i < w.length; i++) {
            s.add(w[i]);
        }
        return s;
    }

    // ------------------------------------------------------------------ 字节工具

    private static long readU32(RandomAccessFile raf) throws IOException {
        return ((long) raf.read() << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    }

    private static long readU64(RandomAccessFile raf) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | raf.read();
        }
        return v;
    }

    private static String readType(RandomAccessFile raf) throws IOException {
        final byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, LATIN);
    }

    private static byte[] readBytes(RandomAccessFile raf, long off, int len) throws IOException {
        raf.seek(off);
        final byte[] b = new byte[len];
        raf.readFully(b);
        return b;
    }

    // ------------------------------------------------------------------ 桌面自检入口

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：analyze <文件>");
            System.out.println("     scrub <输入> <输出> [brand,lavf,aigc,full] [键=文本]");
            return;
        }
        if ("analyze".equals(args[0])) {
            System.out.println(analyze(new File(args[1])).summary);
            return;
        }
        final Options o = new Options();
        o.flags = 0;
        if (args.length > 3) {
            final String[] parts = args[3].split(",");
            for (int i = 0; i < parts.length; i++) {
                final String p = parts[i].trim().toLowerCase(Locale.ROOT);
                if ("brand".equals(p)) {
                    o.flags |= FLAG_BRAND;
                } else if ("lavf".equals(p)) {
                    o.flags |= FLAG_LAVF;
                } else if ("aigc".equals(p)) {
                    o.flags |= FLAG_AIGC;
                } else if ("full".equals(p)) {
                    o.flags |= FLAG_FULL;
                }
            }
        }
        if (o.flags == 0) {
            o.flags = FLAGS_DEFAULT;
        }
        if (args.length > 4) {
            final int eq = args[4].indexOf('=');
            if (eq > 0) {
                o.writes.add(new Write(args[4].substring(0, eq), args[4].substring(eq + 1)));
            }
        }
        final Result r = scrub(new File(args[1]), new File(args[2]), o, null);
        System.out.println((r.ok ? "OK  " : "FAIL ") + r.message);
        System.out.println("作废 " + r.freeCount + " 盒，抹字段 " + r.blankCount + " 处，写入 "
                + r.writeCount + " 条，保留 AIGC " + r.aigcProtected + " 处，字节 "
                + group(r.bytes) + (r.delta == 0 ? "" : "（ +" + r.delta + "）"));
        if (r.writeSkipped.length() > 0) {
            System.out.println("提示：" + r.writeSkipped);
        }
        if (r.report != null) {
            System.out.println(r.report.summary);
        }
    }
}
