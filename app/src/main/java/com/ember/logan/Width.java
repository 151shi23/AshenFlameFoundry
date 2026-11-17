package com.ember.logan;

/** 中英文混排等宽对齐工具：中文/全角按 2 列计宽。 */
public final class Width {
    private Width() {}

    public static int w(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x1100 && (
                    c <= 0x115F ||                      // Hangul Jamo
                    c == 0x2329 || c == 0x232A ||       // 角括号
                    (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F) || // CJK
                    (c >= 0xAC00 && c <= 0xD7A3) ||     // Hangul
                    (c >= 0xF900 && c <= 0xFAFF) ||     // CJK 兼容
                    (c >= 0xFE30 && c <= 0xFE4F) ||     // CJK 兼容形式
                    (c >= 0xFF00 && c <= 0xFF60) ||     // 全角
                    (c >= 0xFFE0 && c <= 0xFFE6))) {    // 全角符号
                w += 2;
            } else {
                w += 1;
            }
        }
        return w;
    }

    /** 按显示宽度右补空格到 len 列。 */
    public static String padEnd(String s, int len) {
        int diff = len - w(s);
        return diff <= 0 ? s : s + spaces(diff);
    }

    /** 按显示宽度左补空格到 len 列。 */
    public static String padStart(String s, int len) {
        int diff = len - w(s);
        return diff <= 0 ? s : spaces(diff) + s;
    }

    /** 居中（右侧多补一格，视觉均衡）。 */
    public static String center(String s, int len) {
        int diff = len - w(s);
        if (diff <= 0) return s;
        int left = diff / 2;
        return spaces(left) + s + spaces(diff - left);
    }

    public static String spaces(int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** 截断到显示宽度 max 列，超出加省略号。 */
    public static String trunc(String s, int max) {
        if (w(s) <= max) return s;
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = w(String.valueOf(c));
            if (acc + cw > max - 2) break;
            sb.append(c);
            acc += cw;
        }
        return sb.append("…").toString();
    }

    /** 文本进度条：bar(pct, 20) => ████████░░░░░░░░░░░░ */
    public static String bar(double pct, int cells) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, pct)) / 100.0 * cells);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells; i++) sb.append(i < filled ? '█' : '░');
        return sb.toString();
    }

    /** 星级评分：stars(2, 5) => ★★☆☆☆ */
    public static String stars(int n, int total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) sb.append(i < n ? '★' : '☆');
        return sb.toString();
    }
}
