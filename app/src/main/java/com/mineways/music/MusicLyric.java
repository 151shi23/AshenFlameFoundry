package com.mineways.music;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词在网页里，但悬浮胶囊和系统悬浮条采不到 WebView —— 所以网页把解析好的歌词行交给原生一份。
 *
 * <p>传输格式故意做得很笨：一行一条，{@code 毫秒\t文本}。好处是原生侧不用 JSON 库也能解析，
 * 纯 JVM 就能跑断言；网页那边只多一次 map/join。
 */
public final class MusicLyric {

    /** 一次最多收这么多行（正常歌曲一两百行，超出说明喂进来的不是歌词）。 */
    public static final int MAX_LINES = 800;
    /** 报文体积闸：比脚本小得多，歌词没道理要 MB 级。 */
    public static final int MAX_BYTES = 96 * 1024;

    public static final MusicLyric EMPTY = new MusicLyric(new long[0], new String[0]);

    public final long[] times;
    public final String[] text;

    private MusicLyric(long[] times, String[] text) {
        this.times = times;
        this.text = text;
    }

    public boolean isEmpty() {
        return times.length == 0;
    }

    /**
     * 解析 {@code 毫秒\t文本} 逐行报文。坏行跳过（不整首作废），文本里的换行在网页侧已经被压成空格。
     */
    public static MusicLyric parse(String wire) {
        if (wire == null || wire.length() == 0) {
            return EMPTY;
        }
        if (wire.length() > MAX_BYTES) {
            return EMPTY;
        }
        List<Long> ts = new ArrayList<>();
        List<String> tx = new ArrayList<>();
        for (String raw : wire.split("\n")) {
            if (ts.size() >= MAX_LINES) {
                break;
            }
            int tab = raw.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String t = raw.substring(tab + 1).trim();
            if (t.length() == 0) {
                continue;                       // 空行没有展示价值
            }
            long ms;
            try {
                ms = Long.parseLong(raw.substring(0, tab).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (ms < 0) {
                continue;
            }
            ts.add(ms);
            tx.add(t);
        }
        if (ts.isEmpty()) {
            return EMPTY;
        }
        // 网页那边按 LRC 原序给，个别实例的时间是乱序的 —— 定位靠二分，必须先排序
        long[] times = new long[ts.size()];
        String[] text = new String[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            times[i] = ts.get(i);
            text[i] = tx.get(i);
        }
        sortByTime(times, text);
        return new MusicLyric(times, text);
    }

    /** 歌词上百行，按时间排序后再二分定位。 */
    private static void sortByTime(long[] times, String[] text) {
        Integer[] idx = new Integer[times.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(times[a], times[b]));
        long[] t2 = new long[times.length];
        String[] x2 = new String[text.length];
        for (int i = 0; i < idx.length; i++) {
            t2[i] = times[idx[i]];
            x2[i] = text[idx[i]];
        }
        System.arraycopy(t2, 0, times, 0, times.length);
        System.arraycopy(x2, 0, text, 0, text.length);
    }

    /** 与网页 {@code findCurrentIndex} 同口径的二分：最后一行 time <= pos。没到第一行返回 -1。 */
    public int indexAt(long posMs) {
        if (times.length == 0) {
            return -1;
        }
        int lo = 0;
        int hi = times.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** 当前该显示哪一行；没有歌词或还没唱到就回空串（悬浮条据此退回歌名/歌手）。 */
    public String lineAt(long posMs) {
        int i = indexAt(posMs);
        return i < 0 ? "" : text[i];
    }
}
