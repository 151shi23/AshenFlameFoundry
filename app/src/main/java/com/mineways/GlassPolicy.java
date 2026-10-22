package com.mineways;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.cloud.CloudCatalog;
import com.mineways.ui.MwTier;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.Locale;

/**
 * 液态玻璃的<b>机型策略</b>落地层：现读云控 {@code ui_glass_models}，按本机两个身份
 * （{@link DeviceId} 内部型号 + 设置里显示的名字）决定 <b>降参数 / 关闭 / 询问</b>。
 *
 * <p>解析与判定全在 {@link GlassRules}（纯 Java、有 JVM 断言），这里只做三件事：
 * 读云控、存用户的选择、把结论塞进已有的两个取值口（{@code MwViews.style().glass} 与
 * {@code MwDevice.tierWithCloud()}）—— 不新造一套开关，免得出现"某条路径绕过了门"。
 *
 * <p>云控（全部可选，不写=不干预）：
 * <pre>
 * FLAG key=ui_glass_models to=K70*:low,K60*:off,K80*:ask   # 先匹配到的生效
 * FLAG key=ui_glass_low_tier to=balanced                   # 小幅度降到哪档（high/balanced/flat）
 * FLAG key=ui_glass_deep_blur to=1                         # 大幅度时模糊最多几 dp
 * FLAG key=ui_glass_deep_refract to=0                      # 大幅度时折射强度（0=关折射）
 * FLAG key=ui_glass_low_motion to=0                        # 大幅度时是否也关动效（默认关）
 * FLAG key=ui_glass_ask_text to=…                          # 询问弹窗正文
 * </pre>
 *
 * <p>三档力度的实际差别（"降低"和"关闭"不是一回事）：
 * <ul>
 *   <li>{@code low} 小幅度：HIGH → BALANCED，去掉色散、重力高光、错峰入场，折射与模糊保持；</li>
 *   <li>{@code deep} 大幅度：再关折射、模糊压到 1dp、关流光与动效 —— 只剩一层薄毛玻璃底；</li>
 *   <li>{@code off} 关闭：FLAT，连 RenderEffect 都不用，退回半透底 + 一道描边。</li>
 * </ul>
 *
 * <p>{@code ask} 只问一次：答案记在 SharedPreferences 里，之后按用户选的走；
 * 云控改了规则（名单或动作变了）会重新问一次 —— 因为那已经是另一件事了。
 */
public final class GlassPolicy {

    /** 用户选择与规则指纹一起存：规则变了就重新问，不会永远卡在旧答案上。 */
    private static final String PREF = "glass_policy";
    private static volatile String cachedKey;
    private static volatile String cachedAction;

    private GlassPolicy() {
    }

    private static String flag(String key, String def) {
        try {
            String v = CloudCatalog.gate().flag(key, def);
            return v == null ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }

    /** 本机命中的那条规则（没命中返回 null）。 */
    public static GlassRules.Rule rule() {
        List<GlassRules.Rule> rules = GlassRules.parse(flag("ui_glass_models", ""));
        if (rules.isEmpty()) {
            return null;
        }
        return GlassRules.decide(rules, DeviceId.names());
    }

    /**
     * 当前生效的动作：{@link GlassRules#LOW} / {@link GlassRules#OFF} /
     * {@link GlassRules#KEEP}；{@code ask} 且还没表态时返回 {@link GlassRules#ASK}
     *（此时<b>不动效果</b>，等用户答完再刷新界面）。
     */
    public static synchronized String action() {
        GlassRules.Rule r = rule();
        if (r == null) {
            return "";
        }
        String resolved = GlassRules.resolve(r, chosen(r));
        // 云控随时可改，界面每帧都在取值：这条只在"规则或选择变了"时重新解析
        String key = r.pattern + ":" + r.action + ">" + resolved;
        if (!key.equals(cachedKey)) {
            cachedKey = key;
            cachedAction = resolved;
        }
        return cachedAction;
    }

    /** 玻璃还开不开（{@code off} = 关；其余沿用云控 {@code ui_glass}）。 */
    public static boolean glassOn(Context c, boolean cloudSaysOn) {
        return cloudSaysOn && !GlassRules.OFF.equals(action());
    }

    /** 档位：{@code off} → FLAT；{@code low}/{@code deep} → 降一档（绝不往上抬）；其余原样。 */
    public static MwTier tier(Context c, MwTier base) {
        String a = action();
        if (GlassRules.OFF.equals(a)) {
            return MwTier.FLAT;
        }
        if (GlassRules.LOW.equals(a)) {
            // 小幅度默认只降一档；云控想更狠可以写 ui_glass_low_tier=flat
            String want = GlassRules.lowTier(flag("ui_glass_low_tier", "balanced"));
            if (base == MwTier.FLAT) {
                return MwTier.FLAT;
            }
            if ("flat".equals(want)) {
                return MwTier.FLAT;
            }
            return "high".equals(want) ? MwTier.HIGH : MwTier.BALANCED;
        }
        if (GlassRules.DEEP.equals(a)) {
            // 大幅度固定停在 BALANCED，再往下靠折射/模糊上限那两个闸，而不是一刀切成 FLAT
            return base == MwTier.FLAT ? MwTier.FLAT : MwTier.BALANCED;
        }
        return base;
    }

    /**
     * 大幅度时模糊最多几 dp（{@code ui_glass_deep_blur}，默认 1）；不适用时返回 -1 = 不额外限制。
     * 小幅度不动模糊 —— 那是"降低"和"只剩一层"之间的区别。
     */
    public static int blurCapDp() {
        if (!GlassRules.DEEP.equals(action())) {
            return -1;
        }
        try {
            return Integer.parseInt(flag("ui_glass_deep_blur", "1").trim());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 大幅度时折射强度（默认 0 = 关折射）；不适用时返回 -1。 */
    public static int refractCapDp() {
        return GlassRules.DEEP.equals(action())
                ? intOf(flag("ui_glass_deep_refract", "0"), 0) : -1;
    }

    /** 大幅度时是否连流光一起关（它是每帧重绘的那一层，最贵）。 */
    public static boolean dropFlow() {
        return GlassRules.DEEP.equals(action());
    }

    /** {@code low}/{@code deep} 时是否同时关动效（默认只有大幅度关）。 */
    public static boolean dropMotion() {
        if (!GlassRules.DEEP.equals(action())) {
            return false;
        }
        return !"0".equals(flag("ui_glass_low_motion", "1"));
    }

    private static int intOf(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 该不该问用户（规则是 ask 且这台机器还没答过）。 */
    public static boolean shouldAsk(Context c) {
        GlassRules.Rule r = rule();
        return r != null && GlassRules.ASK.equals(r.action)
                && GlassRules.resolve(r, chosen(r)).equals(GlassRules.ASK);
    }

    /** 弹一次询问。三个选项都写清楚后果，别用"确定/取消"糊弄。 */
    public static void ask(final Activity a) {
        final GlassRules.Rule r = rule();
        if (r == null || !GlassRules.ASK.equals(r.action)) {
            return;
        }
        String def = "检测到你这台是 " + DeviceId.marketing() + "（" + r.pattern + "），"
                + "云端建议降低液态玻璃效果与参数以换取流畅度。要怎么做？";
        String text = flag("ui_glass_ask_text", def);
        if (text.length() == 0) {
            text = def;
        }
        try {
            new AlertDialog.Builder(a)
                    .setTitle("是否降低玻璃效果")
                    .setMessage(text + "\n\n" + DeviceId.debugLine())
                    .setPositiveButton("降低效果", (d, w) -> save(a, r, GlassRules.LOW))
                    .setNegativeButton("保持现状", (d, w) -> save(a, r, GlassRules.KEEP))
                    .setNeutralButton("直接关闭", (d, w) -> save(a, r, GlassRules.OFF))
                    .show();
        } catch (Throwable ignored) {
            // 弹不出来就算了，下次进页面再问；绝不能因此把页面弄崩
        }
    }

    private static void save(Context c, GlassRules.Rule r, String action) {
        try {
            prefs(c).edit()
                    .putString("answer_" + fp(r), action)
                    .apply();
        } catch (Throwable ignored) {
        }
        cachedKey = null;      // 让下一次取值重新解析
        try {
            if (c instanceof Activity) {
                ((Activity) c).recreate();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 这台机器有没有对「询问」表过态（关于页据此决定要不要给"重新选择"）。 */
    public static boolean hasChoice(Context c) {
        try {
            for (String k : prefs(c).getAll().keySet()) {
                if (k.startsWith("answer_")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 手动恢复"未表态"（关于页/诊断用）。 */
    public static void reset(Context c) {
        try {
            prefs(c).edit().clear().apply();
        } catch (Throwable ignored) {
        }
        cachedKey = null;
    }

    private static String chosen(GlassRules.Rule r) {
        try {
            return prefsCloud().getString("answer_" + fp(r), "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 规则指纹：模式 + 动作。换了规则就等于换了个问题，旧答案不再作数。 */
    private static String fp(GlassRules.Rule r) {
        return (r.pattern + "|" + r.action).toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读的时候拿不到 Activity：用 Application 上下文（CloudCatalog 已经存了一份）。 */
    private static SharedPreferences prefsCloud() {
        Context c = CloudCatalog.app();
        return c == null ? null : c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 给界面/诊断的一行：命中了什么、现在按什么走。 */
    public static String debugLine() {
        GlassRules.Rule r = rule();
        if (r == null) {
            return "玻璃机型规则：没命中（按云控通用参数）　" + DeviceId.debugLine();
        }
        return "玻璃机型规则：命中 " + r.pattern + " → " + r.action
                + "，当前生效 " + action() + "　" + DeviceId.debugLine();
    }
}
