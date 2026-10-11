package com.mineways.repair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * BB（Blockbench 离线版）导出物修复。
 *
 * <ul>
 *   <li><b>.bbmodel / .json</b>：JSON 修复 —— 去 BOM、去注释、去尾逗号、单引号转双引号、NaN/Infinity 归一、
 *       转义控制字符、截断时按括号栈补闭合，最后用 org.json 复核；</li>
 *   <li><b>.obj</b>：补 <code>mtllib</code>、丢弃非法面行（顶点引用不足 3 个）；</li>
 *   <li><b>.mtl</b>：给缺 <code>Kd</code> 的材质补默认漫反射。</li>
 * </ul>
 */
public final class BbFix {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Result {
        public String kind = "未知";
        public String ext = "";
        public byte[] data;
        public final List<String> notes = new ArrayList<>();
    }

    private BbFix() {
    }

    /** 是不是 BB 导出物（.bbmodel 的 JSON / obj / mtl）。 */
    public static boolean looksLikeJson(byte[] d) {
        final String s = head(d, 400);
        return s.startsWith("{") || s.startsWith("[") || s.contains("\"elements\"")
                || s.contains("\"meta\"") || s.contains("\"resolution\"");
    }

    // ------------------------------------------------------------------ JSON
    public static Result fixJson(byte[] d) {
        final Result r = new Result();
        r.kind = "BB 模型（JSON）";
        r.ext = "bbmodel";
        String s = new String(d, UTF8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
            r.notes.add("去掉了 UTF-8 BOM");
        }
        final int before = s.length();
        s = stripComments(s);
        if (s.length() != before) {
            r.notes.add("去掉了 " + (before - s.length()) + " 个字符的注释");
        }
        final int t1 = s.length();
        s = s.replaceAll(",\\s*([}\\]])", "$1");
        if (s.length() != t1) {
            r.notes.add("去掉了尾逗号");
        }
        final int t2 = s.length();
        s = s.replaceAll("(?<![\\w\"])NaN(?![\\w\"])", "0")
                .replaceAll("(?<![\\w\"])-Infinity(?![\\w\"])", "0")
                .replaceAll("(?<![\\w\"])Infinity(?![\\w\"])", "0");
        if (s.length() != t2) {
            r.notes.add("把 NaN / Infinity 归一成 0");
        }
        final String converted = singleToDouble(s, r.notes);
        s = converted;
        final String escaped = escapeControls(s, r.notes);
        s = escaped;
        final int closers = countMissingClosers(s);
        if (closers > 0) {
            final StringBuilder sb = new StringBuilder(s);
            appendClosers(s, sb);
            s = sb.toString();
            r.notes.add("JSON 被截断：按括号栈补了 " + closers + " 个闭合符号");
        }
        boolean valid = false;
        try {
            final String t = s.trim();
            if (t.startsWith("{")) {
                new JSONObject(t);
                valid = true;
            } else if (t.startsWith("[")) {
                new JSONArray(t);
                valid = true;
            }
        } catch (Throwable ignored) {
        }
        r.notes.add(valid ? "JSON 复核通过 ✓" : "⚠ 修完仍未通过 JSON 复核（可能损坏较重，已尽力）");
        r.data = s.getBytes(UTF8);
        return r;
    }

    /** 去掉 // 与 /* *\/ 注释（只在字符串外动手）。 */
    private static String stripComments(String s) {
        final StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 把单引号字符串转成双引号（仅当里面没有双引号时）。 */
    private static String singleToDouble(String s, List<String> notes) {
        final StringBuilder out = new StringBuilder(s.length());
        boolean inDouble = false;
        boolean esc = false;
        int changed = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inDouble) {
                out.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '"') {
                inDouble = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                final int end = s.indexOf('\'', i + 1);
                if (end > 0) {
                    final String inner = s.substring(i + 1, end);
                    if (inner.indexOf('"') < 0) {
                        out.append('"').append(inner).append('"');
                        i = end;
                        changed++;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        if (changed > 0) {
            notes.add("把 " + changed + " 处单引号字符串改成双引号");
        }
        return out.toString();
    }

    /** 字符串里的裸控制字符转义掉（JSON 不允许）。 */
    private static String escapeControls(String s, List<String> notes) {
        final StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false;
        boolean esc = false;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                } else if (c < 0x20) {
                    out.append(String.format("\\u%04x", (int) c));
                    n++;
                    continue;
                }
            } else if (c == '"') {
                inStr = true;
            }
            out.append(c);
        }
        if (n > 0) {
            notes.add("转义了 " + n + " 个裸控制字符");
        }
        return out.toString();
    }

    private static int countMissingClosers(String s) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return Math.max(0, depth) + (inStr ? 1 : 0);
    }

    private static void appendClosers(String s, StringBuilder sb) {
        final StringBuilder stack = new StringBuilder();
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                stack.append('}');
            } else if (c == '[') {
                stack.append(']');
            } else if ((c == '}' || c == ']') && stack.length() > 0) {
                stack.setLength(stack.length() - 1);
            }
        }
        if (inStr) {
            sb.append('"');
        }
        for (int i = stack.length() - 1; i >= 0; i--) {
            sb.append(stack.charAt(i));
        }
    }

    // ------------------------------------------------------------------ OBJ / MTL
    public static Result fixObj(byte[] d, String mtlName) {
        final Result r = new Result();
        r.kind = "OBJ 模型";
        r.ext = "obj";
        final String s = new String(d, UTF8);
        final StringBuilder out = new StringBuilder(s.length() + 64);
        final String[] lines = s.split("\n", -1);
        boolean hasMtlLib = false;
        int dropped = 0;
        for (String line : lines) {
            final String t = line.trim();
            if (t.startsWith("mtllib")) {
                hasMtlLib = true;
            }
            if (t.startsWith("f ")) {
                final String[] parts = t.split("\\s+");
                if (parts.length < 4) {
                    dropped++;
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        if (dropped > 0) {
            r.notes.add("丢弃了 " + dropped + " 行非法面（顶点少于 3 个）");
        }
        if (!hasMtlLib && mtlName != null && !mtlName.isEmpty()) {
            out.insert(0, "mtllib " + mtlName + "\n");
            r.notes.add("补了 mtllib " + mtlName);
        }
        r.data = out.toString().getBytes(UTF8);
        return r;
    }

    public static Result fixMtl(byte[] d) {
        final Result r = new Result();
        r.kind = "MTL 材质";
        r.ext = "mtl";
        final String s = new String(d, UTF8);
        final String[] lines = s.split("\n", -1);
        final StringBuilder out = new StringBuilder(s.length() + 64);
        int materials = 0;
        boolean hasKd = false;
        int added = 0;
        for (String line : lines) {
            final String t = line.trim();
            if (t.startsWith("newmtl ")) {
                if (materials > 0 && !hasKd) {
                    out.append("Kd 1.000000 1.000000 1.000000\n");
                    added++;
                }
                materials++;
                hasKd = false;
            } else if (t.startsWith("Kd ")) {
                hasKd = true;
            }
            out.append(line).append('\n');
        }
        if (materials > 0 && !hasKd) {
            out.append("Kd 1.000000 1.000000 1.000000\n");
            added++;
        }
        if (materials == 0) {
            r.notes.add("⚠ 没有 newmtl 材质块，可能不是 MTL 文件");
        } else if (added > 0) {
            r.notes.add("给 " + added + " 个缺漫反射的材质补了 Kd 1 1 1");
        }
        r.data = out.toString().getBytes(UTF8);
        return r;
    }

    private static String head(byte[] d, int n) {
        final int len = Math.min(n, d.length);
        return new String(d, 0, len, UTF8).trim();
    }
}
