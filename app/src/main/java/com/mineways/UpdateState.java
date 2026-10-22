package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 更新闸门 → 其它云控通道 的状态回传。
 *
 * <p>闸门是 LAUNCHER，比主界面早跑，它算出来的结论没法直接交给公告 / 功能目录，
 * 所以这里落一份到 SharedPreferences，{@code AnnouncementCenter.vars()} 再读出来，
 * 注入成 {@code update_state} / {@code update_target} —— 于是公告能写
 * {@code when="update_state != 'force'"}，配置也能写 {@code if="update_state == 'none'"}。
 *
 * <p>取值六种，缺省 {@code none}（读不到 = 按"没有更新"处理，fail-open）：
 * <pre>
 *   off     云控 update_off=1，整层闸门被关掉
 *   quiet   云控 update_quiet=1，闸门静音（只算状态，不弹窗不拦人）
 *   hold    云控 update_hold 命中当前版本，被要求先降级
 *   force   本次被强制更新（用户到不了主界面）
 *   notify  有新版但只提示
 *   none    无新版 / 已是最新 / 检查失败放行
 * </pre>
 *
 * <p>注意：这里只做"状态"回传，<b>不做</b>反向的拦截能力。闸门是唯一入口，
 * 一个写错的开关就能让所有人打不开 App，所以云端只能让它更宽松，不能让它更严。
 * 唯一的例外是 {@link #HOLD}（回收坏版本），它只认<b>精确版本号</b>，不接受区间，
 * 出错的爆炸半径限定在"装了那个具体版本的人"。
 */
public final class UpdateState {

    /** 云控 update_off=1：整层闸门被关掉。 */
    public static final String OFF = "off";
    /** 云控 update_quiet=1：闸门静音，只算状态不弹窗不拦人。 */
    public static final String QUIET = "quiet";
    /** 云控 update_hold 命中当前版本：被要求先降级。 */
    public static final String HOLD = "hold";
    /** 本次被强制更新。 */
    public static final String FORCE = "force";
    /** 有新版但只提示。 */
    public static final String NOTIFY = "notify";
    /** 无新版 / 已是最新 / 检查失败放行。 */
    public static final String NONE = "none";

    /** 与 {@code UpdateGateActivity.CACHE_PREFS} 同一个文件，只是键不同。 */
    static final String PREFS = "update_gate_cache";
    private static final String K_STATE = "upd_state";
    private static final String K_TARGET = "upd_target";
    private static final String K_AT = "upd_at";

    private UpdateState() {
    }

    /** 闸门算完一次判定就写一次；调用方保证在主线程或后台线程都安全（apply 是异步落盘）。 */
    public static void save(Context c, String state, String target) {
        if (c == null) {
            return;
        }
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(K_STATE, state == null || state.length() == 0 ? "none" : state)
                    .putString(K_TARGET, target == null ? "" : target)
                    .putLong(K_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** off / force / notify / none。 */
    public static String state(Context c) {
        return read(c, K_STATE, "none");
    }

    /** 目标版本号（tag，可能带 v 前缀）；没有更新时是空串。 */
    public static String target(Context c) {
        return read(c, K_TARGET, "");
    }

    /** 这次启动是不是"被闸门拦住了"（force / hold）。公告与功能目录用它决定要不要让开。 */
    public static boolean blocking(Context c) {
        String s = state(c);
        return FORCE.equals(s) || HOLD.equals(s);
    }

    /** 上次写入状态的时间（0 = 这次装完还没跑过闸门）。 */
    public static long at(Context c) {
        if (c == null) {
            return 0L;
        }
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String read(Context c, String key, String def) {
        if (c == null) {
            return def;
        }
        try {
            String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, def);
            return v == null || v.length() == 0 ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }
}
