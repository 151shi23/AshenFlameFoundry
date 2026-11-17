package com.mineways;

/**
 * 「装了 / 没装某些应用」这一条门禁的纯判定，没有任何安卓依赖，可以直接 javac 跑断言。
 *
 * <p>故事墙用它表达两件事：{@code story_pkg}（装了才给看）与
 * {@code story_pkg_none}（装了任一个就藏起来，也就是「没装的人才给看」）。
 * 两者共用同一套算法，只是 {@code negate} 取不同值 —— 分开放在 StoryGate 里就会各写一套、
 * 各漏一个分支。
 */
public final class StoryPackages {

    private StoryPackages() {
    }

    /**
     * @param hit        名单里在本机查到的个数
     * @param size       名单长度
     * @param negate     true = 反向（命中就隐藏）；false = 正向（必须命中才显示）
     * @param all        true = 要求「全部命中」；false = 任一命中就算命中
     * @param strict     读不到应用列表时按「没装」处理
     * @param suspicious 本机是否疑似被包可见性限制（枚举到的应用少得离谱）
     * @return 这一条是否放行
     */
    public static boolean verdict(int hit, int size, boolean negate, boolean all,
                                  boolean strict, boolean suspicious) {
        if (size <= 0) {
            return true;                                    // 没配 = 不限制
        }
        if (hit == 0) {
            if (negate) {
                return true;                                // 一个都没装到 → 反向条件成立 → 显示
            }
            // 正向：读不到就当没装（strict 时直接隐藏）；能正常读到却说没装，那是真的没装
            return suspicious && !strict;
        }
        if (negate) {
            return all ? hit < size : false;                // 装了 → 隐藏；all 时要全中才隐藏
        }
        return !all || hit == size;                         // 正向：任一命中即显示 / all 要求全装
    }
}
