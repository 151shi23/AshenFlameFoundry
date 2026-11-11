package com.mineways.guardlink;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 断网与恢复网络时的<b>开关快照</b>（纯计划 + 一份 prefs 存取）。
 *
 * <p>为什么要有这一层：原来两条命令是硬写的 —— 断就 {@code svc wifi disable; svc data disable}，
 * 恢复就 {@code svc wifi enable; svc data enable}。恢复时<b>并不知道断之前是什么样子</b>，
 * 于是「流量本来就没开」的人按一次「恢复网络」，流量被凭空打开（他报的正是这条）；
 * WiFi 关着等回家的人同理。
 *
 * <p>规则三条：
 * <ul>
 *   <li>断之前<b>开着</b>的那一样才记进快照，也只有它会被恢复。</li>
 *   <li>断之前<b>本来就关着</b>的那一样：连 disable 都不下发（没东西可关），更不替你打开。</li>
 *   <li>断之前<b>读不到状态</b>（ROM 不让查 / 没插卡）：disable 照下（保护优先，要断就断干净），
 *       但快照记成「不知道」，恢复时<b>不碰</b>并把这句话显示出来 ——
 *       宁可不帮你开，也不能凭空开走你的流量。</li>
 * </ul>
 *
 * <p>没有快照 = 本守护没断过全局网络（精确断网走 iptables 按 uid，不碰总开关，也不写这份快照），
 * 此时恢复一条命令都不下发。
 */
public final class GuardNetState {

    /** 快照里一个开关的三种状态。 */
    public static final int ON = 1;
    public static final int OFF = 0;
    public static final int UNKNOWN = -1;

    private static final String PREF = "guardlink";
    private static final String K_AT = "net_cut_at";
    private static final String K_WIFI = "net_cut_wifi";
    private static final String K_DATA = "net_cut_data";

    /** 一句「要执行哪些命令 + 界面上怎么说」。 */
    public static final class Plan {
        public final List<String> cmds;
        public final String note;

        Plan(List<String> cmds, String note) {
            this.cmds = cmds;
            this.note = note;
        }

        public boolean isEmpty() {
            return cmds.isEmpty();
        }
    }

    /** 读快照里某一样的原始值（没记录就是 {@link #OFF}：断之前本来就关着）。 */
    public static int stateOf(Context ctx, String key) {
        return sp(ctx).getInt(key, OFF);
    }

    public static int wifiWas(Context ctx) {
        return stateOf(ctx, K_WIFI);
    }

    public static int dataWas(Context ctx) {
        return stateOf(ctx, K_DATA);
    }

    /** 本守护有没有断过全局网络 —— 恢复动作唯一的凭据。 */
    public static boolean hasCut(Context ctx) {
        return sp(ctx).contains(K_AT);
    }

    /**
     * 断网计划。参数是<b>断之前</b>读到的状态（{@code null} = 读不到）。
     *
     * <p>{@code !Boolean.FALSE.equals(x)} 这个写法是有意的：开着和读不到都要下 disable，
     * 只有「确认本来就关着」才省掉这句。
     */
    public static Plan cut(Boolean wifiOn, Boolean dataOn) {
        List<String> c = new ArrayList<>();
        List<String> did = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        if (!Boolean.FALSE.equals(wifiOn)) {
            c.add("svc wifi disable");
            did.add(wifiOn == null ? "WiFi（状态读不到，照断）" : "WiFi");
        } else {
            kept.add("WiFi（本来就关着）");
        }
        if (!Boolean.FALSE.equals(dataOn)) {
            c.add("svc data disable");
            did.add(dataOn == null ? "数据流量（状态读不到，照断）" : "数据流量");
        } else {
            kept.add("数据流量（本来就关着）");
        }
        StringBuilder sb = new StringBuilder();
        if (did.isEmpty()) {
            sb.append("WiFi 与流量本来就都没开，总开关一个没动");
        } else {
            sb.append("已切断 ").append(join(did));
            if (!kept.isEmpty()) {
                sb.append(" · 没动 ").append(join(kept));
            }
        }
        return new Plan(c, sb.toString());
    }

    /**
     * 恢复计划：只把「断之前本来就是开的」那一样打开。
     *
     * @param hasCut 本守护是否真的断过全局网络
     * @param wifi   快照里的 WiFi（{@link #ON} / {@link #OFF} / {@link #UNKNOWN}）
     * @param data   快照里的流量
     */
    public static Plan restore(boolean hasCut, int wifi, int data) {
        if (!hasCut) {
            return new Plan(new ArrayList<String>(),
                    "本守护没有切断过网络总开关（精确断网只删 iptables 规则，不碰 WiFi 与流量），"
                            + "一个开关都不替你打开");
        }
        List<String> c = new ArrayList<>();
        List<String> back = new ArrayList<>();
        List<String> left = new ArrayList<>();
        pick(c, back, left, "svc wifi enable", "WiFi", wifi);
        pick(c, back, left, "svc data enable", "数据流量", data);
        StringBuilder sb = new StringBuilder();
        if (!back.isEmpty()) {
            sb.append("已恢复 ").append(join(back));
        }
        if (!left.isEmpty()) {
            sb.append(sb.length() > 0 ? " · 保持原样 " : "保持原样 ").append(join(left));
        }
        if (sb.length() == 0) {
            sb.append("网络总开关一个都没动");
        }
        return new Plan(c, sb.toString());
    }

    private static void pick(List<String> cmds, List<String> back, List<String> left,
                             String cmd, String name, int was) {
        if (was == ON) {
            cmds.add(cmd);
            back.add(name);
        } else if (was == UNKNOWN) {
            left.add(name + "（断之前读不到状态，没敢替你打开）");
        } else {
            left.add(name + "（断之前你就关着，不替你打开）");
        }
    }

    private static String join(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(" 与 ");
            }
            sb.append(v.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 存快照。<b>必须在下发 disable 之前</b>调用（那之后读到的都是关）；
     * 进程被杀、开机接回之后，恢复动作只能靠这份盘。
     */
    public static void saveCut(Context ctx, long at, Boolean wifiOn, Boolean dataOn) {
        SharedPreferences.Editor e = sp(ctx).edit();
        e.putLong(K_AT, at);
        e.putInt(K_WIFI, code(wifiOn));
        e.putInt(K_DATA, code(dataOn));
        e.apply();
    }

    private static int code(Boolean on) {
        if (on == null) {
            return UNKNOWN;
        }
        return on ? ON : OFF;
    }

    /** 恢复完就抹掉凭据：不留「上次断过」，免得下一次恢复凭空开一堆开关。 */
    public static void clear(Context ctx) {
        sp(ctx).edit().remove(K_AT).remove(K_WIFI).remove(K_DATA).apply();
    }

    /**
     * 把 {@code settings get global wifi_on|mobile_data} 的输出折成三态。
     *
     * <p>这一层之所以要写成纯函数：不同 ROM 在这两个 setting 上返回的形态不一样
     * （{@code 1}/{@code 0}、{@code true}/{@code false}、查不到时是字面量 {@code null}、
     * 有的还带尾空格）。**任何看不懂的值都算「读不到」**，绝不能猜成「开着」——
     * 猜错的后果就是替他打开流量的反面：该恢复的没恢复。
     */
    public static Boolean parseRadio(String out) {
        if (out == null) {
            return null;
        }
        String v = out.trim();
        if (v.length() == 0 || "null".equalsIgnoreCase(v)) {
            return null;
        }
        // 有的 ROM 一行里带别的输出，取第一个看得懂的 token
        for (String tok : v.split("[\\s,]+")) {
            if ("1".equals(tok) || "true".equalsIgnoreCase(tok)) {
                return Boolean.TRUE;
            }
            if ("0".equals(tok) || "false".equalsIgnoreCase(tok)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** 界面与状态明细那句「现在网络是什么状况」。 */
    public static String describe(Context ctx) {
        if (!hasCut(ctx)) {
            return "本守护没有切断网络总开关";
        }
        return "断网快照 " + GuardText.stamp(sp(ctx).getLong(K_AT, 0L))
                + " · WiFi " + word(wifiWas(ctx)) + " · 流量 " + word(dataWas(ctx));
    }

    private static String word(int was) {
        if (was == ON) {
            return "断之前开着（恢复会打开）";
        }
        if (was == UNKNOWN) {
            return "断之前读不到（恢复不碰）";
        }
        return "断之前就关着（不替你打开）";
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private GuardNetState() {
    }
}
