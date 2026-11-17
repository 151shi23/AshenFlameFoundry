package com.mineways.mocap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 最小 JSON 解析/写出器（零依赖，Java 8 兼容）。 */
public final class MiniJson {

    // ---------- 解析 ----------
    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object o = p.value();
        p.ws();
        if (p.i < p.n) throw new RuntimeException("JSON 尾部多余字符 @" + p.i);
        return o;
    }

    private static final class P {
        final String s; final int n; int i;
        P(String s) { this.s = s; this.n = s.length(); }
        void ws() { while (i < n && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= n) throw new RuntimeException("JSON 提前结束"); return s.charAt(i); }
        Object value() {
            char c = peek();
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return num();
            }
        }
        void expect(String w) {
            if (!s.startsWith(w, i)) throw new RuntimeException("JSON 字面量错误 @" + i);
            i += w.length();
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (peek() != ':') throw new RuntimeException("JSON 缺少: @" + i);
                i++; ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new RuntimeException("JSON 对象错误 @" + i);
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<Object>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw new RuntimeException("JSON 数组错误 @" + i);
            }
        }
        String str() {
            if (peek() != '"') throw new RuntimeException("JSON 字符串错误 @" + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new RuntimeException("JSON 转义错误 \\" + e);
                    }
                } else b.append(c);
            }
        }
        Object num() {
            int st = i;
            if (peek() == '-') i++;
            while (i < n && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty() || t.equals("-")) throw new RuntimeException("JSON 数字错误 @" + st);
            return Double.valueOf(t);
        }
    }

    // ---------- 写出 ----------
    public static String write(Object o, boolean pretty) {
        StringBuilder b = new StringBuilder();
        wv(o, pretty, 0, b);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void wv(Object o, boolean pretty, int indent, StringBuilder b) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { wstr((String) o, b); return; }
        if (o instanceof Boolean) { b.append(o.toString()); return; }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(fmt(d));
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                if (pretty) b.append('\n').append(pad(indent + 1));
                wstr(e.getKey(), b);
                b.append(pretty ? ": " : ":");
                wv(e.getValue(), pretty, indent + 1, b);
            }
            if (pretty) b.append('\n').append(pad(indent));
            b.append('}');
            return;
        }
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append('[');
            boolean first = true;
            for (Object v : l) {
                if (!first) b.append(',');
                first = false;
                if (pretty) b.append('\n').append(pad(indent + 1));
                wv(v, pretty, indent + 1, b);
            }
            if (pretty) b.append('\n').append(pad(indent));
            b.append(']');
            return;
        }
        if (o instanceof double[]) {
            double[] a = (double[]) o;
            b.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(fmt(a[i])); }
            b.append(']');
            return;
        }
        throw new RuntimeException("不可序列化类型: " + o.getClass());
    }

    public static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(java.util.Locale.US, "%.4f", d);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s += '0';
        return s;
    }

    /** 时间轴键格式：与 Blockbench 时间码一致 —— 3 位小数舍入、去尾零、保底 1 位（"0.067"/"1.0"/"2.2"）。 */
    public static String fmtTime(double t) {
        return fmtTime(t, 3);
    }

    /** 同上，可指定小数位（编辑已有文件用 4 位，保住 BB 的 "0.0417"）。 */
    public static String fmtTime(double t, int decimals) {
        String s = String.format(java.util.Locale.US, "%." + Math.max(1, Math.min(6, decimals)) + "f",
                Math.max(0, t));
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s += '0';
        return s;
    }

    private static void wstr(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    private static String pad(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("  ");
        return b.toString();
    }
}
