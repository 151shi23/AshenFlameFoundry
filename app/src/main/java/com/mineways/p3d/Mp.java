package com.mineways.p3d;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prisma3D 工程数据的最小 MessagePack 读写实现（纯 Java，不依赖任何库）。
 *
 * <p>逆向依据：工程文件（2.0 的 *&#47;*.proj、3.0 的 project.proj / *.pobject / *.pclip）
 * 里的"二进制控制符"就是 MessagePack 类型标记，例如 0x94=fixarray(4)、0xD9 20=str8(32)、
 * 0xA4="Cube"、0xCA=float32、0xD2=int32。格式是公开规范，所以解码是确定的。
 *
 * <p>值映射：nil→null、布尔→Boolean、整数→Long、float32→Float、float64→Double、
 * 字符串→String、bin→byte[]、ext→{@link Ext}、数组→List、映射→LinkedHashMap。
 * 整数按范围选最紧凑的 tag；float32/float64 靠 Float/Double 区分，保证往返不改精度。
 */
public final class Mp {

    /** MessagePack 扩展类型（0xD4-0xD8 / 0xC7-0xC9），原样保留以便写回。 */
    public static final class Ext {
        public final int type;
        public final byte[] data;

        public Ext(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Mp() {
    }

    // ------------------------------------------------------------------ 读

    public static final class Reader {
        private final byte[] b;
        private int p;

        public Reader(byte[] data, int offset) {
            this.b = data;
            this.p = offset;
        }

        public int pos() {
            return p;
        }

        public boolean hasMore() {
            return p < b.length;
        }

        /** 连续读到结尾：3.0 的文件是「一串字段值」，不是单个值。 */
        public List<Object> readAll() {
            List<Object> out = new ArrayList<>();
            while (hasMore()) {
                out.add(readValue());
            }
            return out;
        }

        public Object readValue() {
            int c = u8();
            if (c <= 0x7F) {
                return (long) c;
            }
            if (c >= 0xE0) {
                return (long) (c - 0x100);
            }
            if (c >= 0xA0 && c <= 0xBF) {
                return readStr(c & 0x1F);
            }
            if (c >= 0x90 && c <= 0x9F) {
                return readArray(c & 0x0F);
            }
            if (c >= 0x80 && c <= 0x8F) {
                return readMap(c & 0x0F);
            }
            switch (c) {
                case 0xC0:
                    return null;
                case 0xC2:
                    return Boolean.FALSE;
                case 0xC3:
                    return Boolean.TRUE;
                case 0xC4:
                    return take(u8());
                case 0xC5:
                    return take(u16());
                case 0xC6:
                    return take((int) u32());
                case 0xC7:
                    return readExt(u8());
                case 0xC8:
                    return readExt(u16());
                case 0xC9:
                    return readExt((int) u32());
                case 0xCA:
                    return readF32();
                case 0xCB:
                    return readF64();
                case 0xCC:
                    return (long) u8();
                case 0xCD:
                    return (long) u16();
                case 0xCE:
                    return u32();
                case 0xCF:
                    return u64();
                case 0xD0:
                    return (long) b[p++];
                case 0xD1:
                    return (long) (short) u16();
                case 0xD2:
                    return (long) (int) u32();
                case 0xD3:
                    return u64();
                case 0xD4:
                case 0xD5:
                case 0xD6:
                case 0xD7:
                case 0xD8:
                    return readExt(1 << (c - 0xD4));
                case 0xD9:
                    return readStr(u8());
                case 0xDA:
                    return readStr(u16());
                case 0xDB:
                    return readStr((int) u32());
                case 0xDC:
                    return readArray(u16());
                case 0xDD:
                    return readArray((int) u32());
                case 0xDE:
                    return readMap(u16());
                case 0xDF:
                    return readMap((int) u32());
                default:
                    throw new IllegalStateException("未知的 MessagePack 标记 0x"
                            + Integer.toHexString(c) + " @ " + (p - 1));
            }
        }

        private int u8() {
            return b[p++] & 0xFF;
        }

        private int u16() {
            int v = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
            return v;
        }

        private long u32() {
            long v = ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                    | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            p += 4;
            return v;
        }

        private long u64() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (b[p + i] & 0xFF);
            }
            p += 8;
            return v;
        }

        private byte[] take(int n) {
            byte[] v = new byte[n];
            System.arraycopy(b, p, v, 0, n);
            p += n;
            return v;
        }

        private String readStr(int n) {
            String s = new String(b, p, n, UTF8);
            p += n;
            return s;
        }

        private Float readF32() {
            int bits = (int) u32();
            return Float.intBitsToFloat(bits);
        }

        private Double readF64() {
            return Double.longBitsToDouble(u64());
        }

        private Ext readExt(int n) {
            int t = b[p++] & 0xFF;
            return new Ext(t, take(n));
        }

        private List<Object> readArray(int n) {
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(readValue());
            }
            return out;
        }

        private Map<String, Object> readMap(int n) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Object k = readValue();
                Object v = readValue();
                out.put(k instanceof String ? (String) k : String.valueOf(k), v);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 写

    public static byte[] writeAll(List<Object> values) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        for (Object v : values) {
            writeValue(os, v);
        }
        return os.toByteArray();
    }

    public static byte[] write(Object v) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        writeValue(os, v);
        return os.toByteArray();
    }

    public static void writeValue(ByteArrayOutputStream os, Object v) {
        if (v == null) {
            os.write(0xC0);
        } else if (v instanceof Boolean) {
            os.write(((Boolean) v) ? 0xC3 : 0xC2);
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            writeInt(os, ((Number) v).longValue());
        } else if (v instanceof Float) {
            writeF32(os, (Float) v);
        } else if (v instanceof Double) {
            writeF64(os, (Double) v);
        } else if (v instanceof String) {
            writeStr(os, (String) v);
        } else if (v instanceof byte[]) {
            writeBin(os, (byte[]) v);
        } else if (v instanceof Ext) {
            writeExt(os, (Ext) v);
        } else if (v instanceof List) {
            writeArray(os, (List<?>) v);
        } else if (v instanceof Map) {
            writeMap(os, (Map<?, ?>) v);
        } else {
            throw new IllegalArgumentException("不支持的类型：" + v.getClass().getName());
        }
    }

    private static void writeInt(ByteArrayOutputStream os, long v) {
        if (v >= 0) {
            if (v <= 0x7FL) {
                os.write((int) v);
            } else if (v <= 0xFFL) {
                os.write(0xCC);
                os.write((int) v);
            } else if (v <= 0xFFFFL) {
                os.write(0xCD);
                writeBE(os, v, 2);
            } else if (v <= 0xFFFFFFFFL) {
                os.write(0xCE);
                writeBE(os, v, 4);
            } else {
                os.write(0xCF);
                writeBE(os, v, 8);
            }
        } else {
            if (v >= -32L) {
                os.write((int) (0x100 + v));
            } else if (v >= -128L) {
                os.write(0xD0);
                writeBE(os, v, 1);
            } else if (v >= -32768L) {
                os.write(0xD1);
                writeBE(os, v, 2);
            } else if (v >= Integer.MIN_VALUE) {
                os.write(0xD2);
                writeBE(os, v, 4);
            } else {
                os.write(0xD3);
                writeBE(os, v, 8);
            }
        }
    }

    private static void writeBE(ByteArrayOutputStream os, long v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            os.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }

    private static void writeF32(ByteArrayOutputStream os, float f) {
        os.write(0xCA);
        writeBE(os, Float.floatToRawIntBits(f) & 0xFFFFFFFFL, 4);
    }

    private static void writeF64(ByteArrayOutputStream os, double d) {
        os.write(0xCB);
        writeBE(os, Double.doubleToRawLongBits(d), 8);
    }

    private static void writeStr(ByteArrayOutputStream os, String s) {
        byte[] raw = s.getBytes(UTF8);
        int n = raw.length;
        if (n <= 0x1F) {
            os.write(0xA0 | n);
        } else if (n <= 0xFF) {
            os.write(0xD9);
            os.write(n);
        } else if (n <= 0xFFFF) {
            os.write(0xDA);
            writeBE(os, n, 2);
        } else {
            os.write(0xDB);
            writeBE(os, n, 4);
        }
        os.write(raw, 0, n);
    }

    private static void writeBin(ByteArrayOutputStream os, byte[] data) {
        int n = data.length;
        if (n <= 0xFF) {
            os.write(0xC4);
            os.write(n);
        } else if (n <= 0xFFFF) {
            os.write(0xC5);
            writeBE(os, n, 2);
        } else {
            os.write(0xC6);
            writeBE(os, n, 4);
        }
        os.write(data, 0, n);
    }

    private static void writeExt(ByteArrayOutputStream os, Ext e) {
        int n = e.data.length;
        if (n == 1 || n == 2 || n == 4 || n == 8 || n == 16) {
            os.write(0xD4 + (Integer.numberOfTrailingZeros(n)));
        } else if (n <= 0xFF) {
            os.write(0xC7);
            os.write(n);
        } else if (n <= 0xFFFF) {
            os.write(0xC8);
            writeBE(os, n, 2);
        } else {
            os.write(0xC9);
            writeBE(os, n, 4);
        }
        os.write(e.type & 0xFF);
        os.write(e.data, 0, n);
    }

    private static void writeArray(ByteArrayOutputStream os, List<?> list) {
        int n = list.size();
        if (n <= 0x0F) {
            os.write(0x90 | n);
        } else if (n <= 0xFFFF) {
            os.write(0xDC);
            writeBE(os, n, 2);
        } else {
            os.write(0xDD);
            writeBE(os, n, 4);
        }
        for (Object v : list) {
            writeValue(os, v);
        }
    }

    private static void writeMap(ByteArrayOutputStream os, Map<?, ?> map) {
        int n = map.size();
        if (n <= 0x0F) {
            os.write(0x80 | n);
        } else if (n <= 0xFFFF) {
            os.write(0xDE);
            writeBE(os, n, 2);
        } else {
            os.write(0xDF);
            writeBE(os, n, 4);
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            writeValue(os, String.valueOf(e.getKey()));
            writeValue(os, e.getValue());
        }
    }

    // ------------------------------------------------------------------ 调试输出

    /** 打成近似 JSON 的字符串（结构树），只用于日志与转换报告。 */
    public static String toString(Object v) {
        StringBuilder sb = new StringBuilder();
        dump(sb, v, 0, 32);
        return sb.toString();
    }

    private static void dump(StringBuilder sb, Object v, int depth, int maxDepth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof String) {
            sb.append('"').append(escape((String) v)).append('"');
        } else if (v instanceof byte[]) {
            sb.append("bin(").append(((byte[]) v).length).append(')');
        } else if (v instanceof Ext) {
            sb.append("ext(").append(((Ext) v).type).append(',')
                    .append(((Ext) v).data.length).append(')');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (depth >= maxDepth) {
                    sb.append("...");
                    break;
                }
                dump(sb, l.get(i), depth + 1, maxDepth);
            }
            sb.append(']');
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (depth >= maxDepth) {
                    sb.append("...");
                    break;
                }
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                dump(sb, e.getValue(), depth + 1, maxDepth);
            }
            sb.append('}');
        } else {
            sb.append(v);
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
