package com.mineways.guardlink;

/** 界面与日志共用的小工具。 */
public final class GuardText {

    private GuardText() {
    }

    /** 折成一行进 app.log（多行事件会把日志文件冲散，查的人只能干瞪眼）。 */
    public static String flat(String s) {
        if (s == null) {
            return "";
        }
        // 先处理 \r\n，否则 CRLF 会被拆成两个空格，日志里全是断档
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String bytes(long v) {
        if (v < 1024) {
            return v + " B";
        }
        if (v < 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1f KB", v / 1024.0);
        }
        return String.format(java.util.Locale.CHINA, "%.1f MB", v / 1048576.0);
    }

    /** Core 的报告里用的是平台换行 {@code %n}，界面与剪贴板统一成 \n。 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r\n", "\n").replace(System.lineSeparator(), "\n");
    }

    /** 时刻戳成人能看的一行；0 或负数（没发生过）直接写「没发生过」，不显示 1970。 */
    public static String stamp(long ts) {
        if (ts <= 0L) {
            return "没发生过";
        }
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(ts));
    }

    /** 毫秒数说成人话：6000 → 「6 秒」，2500 → 「2.5 秒」，1500 → 「1.5 秒」，40 → 「40 毫秒」。 */
    public static String duration(long ms) {
        if (ms < 1000L) {
            return ms + " 毫秒";
        }
        double s = ms / 1000.0;
        if (Math.abs(s - Math.round(s)) < 0.0001) {
            return ((long) Math.round(s)) + " 秒";
        }
        return String.format(java.util.Locale.CHINA, "%.1f 秒", s);
    }
}
