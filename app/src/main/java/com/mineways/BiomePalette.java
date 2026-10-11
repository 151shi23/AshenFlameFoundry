package com.mineways;

import java.util.HashMap;

/**
 * 群系 id → 中文名 + 参考配色（离线种子地图上色用）。
 *
 * <p>id 取值与 cubiomes 的 {@code enum BiomeID} 一致；变异群系（1.12 的 +128 变种）
 * 自动归一到基础 id 并加「（变种）」后缀。配色为近似色，用于区分地块，不追求与原版贴图一致。</p>
 */
public final class BiomePalette {

    /** id|中文名|颜色（RRGGBB）。 */
    private static final String[] RAW = {
            "0|海洋|3B6FD6", "1|平原|8DB360", "2|沙漠|E8D26B", "3|山地|7A7A7A",
            "4|森林|3F8F3F", "5|针叶林|4E7A5A", "6|沼泽|4C6B3C", "7|河流|4A7FD6",
            "8|下界荒地|7A2E2E", "9|末地|C9C97E", "10|冻洋|35527A", "11|冻河|4A6E9C",
            "12|雪原|E8F0F0", "13|雪山|B9C7D0", "14|蘑菇岛|B08AB0", "15|蘑菇岛岸|9A7A9A",
            "16|沙滩|E8DC9C", "17|沙漠丘陵|D8C060", "18|森林丘陵|356F35", "19|针叶林丘陵|46684F",
            "20|山地边缘|888888", "21|丛林|2E7A28", "22|丛林丘陵|276A22", "23|丛林边缘|3F8C36",
            "24|深海|2A4E9C", "25|石岸|8A8A8A", "26|雪滩|E0E8E8", "27|桦木森林|6FA85A",
            "28|桦木丘陵|5F9450", "29|黑森林|2F5B2F", "30|积雪针叶林|6E8C8C", "31|积雪针叶林丘陵|5F7B7B",
            "32|巨型针叶林|3C6B4A", "33|巨型针叶林丘陵|345C40", "34|林地山地|6E7A6E", "35|热带草原|BFB755",
            "36|热带高原|A8A045", "37|恶地|B85C2A", "38|疏林恶地|A0552A", "39|恶地高原|C07030",
            "40|末地小岛|C8C8A0", "41|末地内陆|CFCFA8", "42|末地高地|D8D8B0", "43|末地荒岛|C0C090",
            "44|温暖海洋|4A9CD6", "45|温和海洋|4A86C8", "46|寒冷海洋|3E6E9E", "47|深暖海洋|2E7BB8",
            "48|深温和海洋|2E6BA8", "49|深冷海洋|2A5580", "50|深冻海洋|234567",
            "51|季节森林|6BA05A", "52|雨林|2E8B2E", "53|灌木地|7A9A5A",
            "127|虚空|101010",
            "168|竹林|44A03C", "169|竹林丘陵|3C8F35",
            "170|灵魂沙峡谷|4A5A70", "171|绯红森林|A03030", "172|诡异森林|2E7A6A",
            "173|玄武岩三角洲|4A4A52", "174|溶洞|8A6A4A", "175|繁茂洞穴|4E7A3C",
            "177|草甸|7AA85A", "178|雪林|6E8A7A", "179|积雪山坡|DCE6EE", "180|尖峭山峰|C8D2DA",
            "181|冰封山峰|E4EEF4", "182|裸岩山峰|9AA0A6",
            "183|深暗之域|2A2A3A", "184|红树林沼泽|3F6B4A", "185|樱花树林|E8A8C8", "186|苍白花园|9A9A96",
    };

    private static final HashMap<Integer, String> NAMES = new HashMap<>();
    private static final HashMap<Integer, Integer> COLORS = new HashMap<>();

    static {
        for (String row : RAW) {
            String[] p = row.split("\\|");
            int id = Integer.parseInt(p[0]);
            NAMES.put(id, p[1]);
            COLORS.put(id, 0xFF000000 | Integer.parseInt(p[2], 16));
        }
    }

    /** 「找最近群系」里可选的常见目标。 */
    public static final int[] TARGETS = {
            14, 185, 186, 37, 21, 29, 3, 12, 6, 184, 35, 2, 32, 183, 9, 171, 172, 170, 173, 127,
    };

    private BiomePalette() {
    }

    private static int base(int id) {
        return (id >= 128 && id <= 167) ? id - 128 : id;
    }

    /** 中文名（未知 id 显示「未知(id)」）。 */
    public static String name(int id) {
        if (id < 0) {
            return "未知";
        }
        String s = NAMES.get(id);
        if (s == null) {
            s = NAMES.get(base(id));
        }
        if (s == null) {
            return "未知(" + id + ")";
        }
        return (id >= 128 && id <= 167) ? s + "（变种）" : s;
    }

    /** 颜色（ARGB）。 */
    public static int color(int id) {
        Integer c = COLORS.get(id);
        if (c == null) {
            c = COLORS.get(base(id));
        }
        return c == null ? 0xFF7F7F7F : c;
    }
}
