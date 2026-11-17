package com.mineways.music;

/**
 * 队列推进的纯判定层。口径与移植过来的 MSCD `player.js` 完全一致 ——
 * 原生通知栏/悬浮条按上一首下一首时，JS 队列走的是同一套规则，不会两边各算一套。
 */
public final class QueueCore {

    public static final String SHUFFLE = "shuffle";
    public static final String REPEAT_ONE = "repeat-one";
    public static final String REPEAT_ALL = "repeat-all";

    public interface RandomInt {
        /** 返回 [0, bound) 的随机整数。 */
        int next(int bound);
    }

    private QueueCore() {
    }

    /** 下一首的索引；-1 表示这一轮到头了（暂停，不循环）。 */
    public static int next(int current, int size, String mode, int direction,
                           boolean fromPrev, RandomInt rnd) {
        if (size <= 0) {
            return -1;
        }
        int cur = current < 0 ? 0 : current;

        if (SHUFFLE.equals(mode)) {
            if (size == 1) {
                return 0;
            }
            int idx;
            int guard = 0;
            do {
                idx = rnd == null ? (int) (Math.random() * size) : rnd.next(size);
            } while (idx == cur && ++guard < 32);
            return idx < 0 || idx >= size ? 0 : idx;
        }

        int idx = cur + direction;
        if (idx < 0) {
            return REPEAT_ALL.equals(mode) ? size - 1 : (fromPrev ? 0 : -1);
        }
        if (idx >= size) {
            return REPEAT_ALL.equals(mode) ? 0 : -1;
        }
        return idx;
    }

    /** 一首放完之后去哪：单曲循环留在原地，其余按方向 +1。 */
    public static int onEnded(int current, int size, String mode, RandomInt rnd) {
        if (REPEAT_ONE.equals(mode)) {
            return current < 0 ? 0 : current;
        }
        return next(current, size, mode, 1, false, rnd);
    }

    /** 「上一首」：播放超过 3 秒先回到本曲开头，这个判断留在界面层，这里只算索引。 */
    public static int prev(int current, int size, String mode, RandomInt rnd) {
        return next(current, size, mode, -1, true, rnd);
    }

    public static String cycleMode(String mode) {
        if (SHUFFLE.equals(mode)) {
            return REPEAT_ONE;
        }
        if (REPEAT_ONE.equals(mode)) {
            return REPEAT_ALL;
        }
        return SHUFFLE;
    }

    public static String modeLabel(String mode) {
        if (REPEAT_ONE.equals(mode)) {
            return "单曲循环";
        }
        if (REPEAT_ALL.equals(mode)) {
            return "列表循环";
        }
        return "随机播放";
    }

    public static boolean isMode(String mode) {
        return SHUFFLE.equals(mode) || REPEAT_ONE.equals(mode) || REPEAT_ALL.equals(mode);
    }
}
