package com.mineways.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mineways.cloud.CloudCatalog

/**
 * 界面重构的唯一设计源：颜色、圆角、间距、字号、时长全部从这里出，界面代码里不许再写十六进制色。
 *
 * 2026-10-17 二次重写，他拍板的方向（推翻上一版「禁纯黑纯白」的自审规则——那是我的旧判例，
 * 这一条以他这次的话为准）：
 *
 *  · **纯黑打底** `#000000`：内容坐在黑上，层次靠透明白的明度阶梯，不再有暖黑/暖白/灰色块；
 *  · **白色流光是唯一光源**：光带画在共享背板里（[MwFlow]），玻璃折射它；强调色 = 白本身，
 *    熔炉橙与橙→玫红渐变退役；
 *  · **玻璃是次界面**：全透明 + 全折射，浮在黑底与流光之上。
 *
 * 云控（铁律：效果只能云控下发，绝不写死进包）：
 *
 * ```
 * FLAG key=ui_home to=legacy           # 整个首页退回旧 View 实现
 * FLAG key=ui_accent to=#RRGGBB        # 强行换一支流光/强调色（不填 = 白）
 * FLAG key=ui_radius to=16             # 卡片圆角（dp）
 * FLAG key=ui_glass to=0               # 关玻璃层（低端机省电省合成）
 * FLAG key=ui_flow to=0                # 关流光/照片（纯黑静底）
 * FLAG key=ui_motion to=0              # 关动效
 * FLAG key=ui_tier to=high|balanced|flat   # 强制设备档位
 * FLAG key=ui_blur to=6                # FULL 面板模糊半径（dp，0~24）
 * FLAG key=ui_refract to=16            # lens 折射强度（dp，0~48）
 * ```
 *
 * 首页右上角「三个点」= AI 入口（液态玻璃水波纹 + 悬浮输入框），单独三个开关：
 *
 * ```
 * FLAG key=ui_ai to=0                  # 旧包总闸；新包配了任一 ai_* 后忽略它
 * FLAG key=ai_off to=1                 # 硬闸：新旧包都关（可选）
 * FLAG key=ui_ai_ripple to=0           # 只关水波纹，AI 框照常出现（低端机省一次全屏重绘）
 * FLAG key=ui_ai_hint to=...           # 自定义输入框占位文案
 * FLAG key=ai_whitelist to=1           # 白名单用户可用（默认 1，写 0 关闭）
 * FLAG key=ai_models to=2210132C,PJD*  # 特定机型（逗号/空格分隔，* 通配）
 * FLAG key=ai_sdk_min to=33            # 特定系统：最低 SDK
 * FLAG key=ai_sdk_max to=99            # 特定系统：最高 SDK
 * FLAG key=ai_all to=1                 # 全员开放（慎用）
 * FLAG key=api_env to=prod             # 默认环境
 * FLAG key=api_env_whitelist to=dev    # 白名单用户单独走的环境（优先）
 * FLAG key=api_base to=https://...     # 直接指定 AI 接口域名（优先级最高）
 * ```
 *
 * 全部支持 `if=` 条件 —— 按机型/系统版本下发示例：
 *
 * ```
 * FLAG key=ui_tier to=balanced if="sdk_int < 33"
 * FLAG key=ui_blur to=6 if="brand 开头是 Xiaomi"
 * FLAG key=ui_refract to=12 if="model 匹配 2210132C"
 * ```
 *
 * 读不到云端（没网、没生效）时全部走下面的出厂默认值，界面照常能用。
 */
object MwTokens {

    // ---------------------------------------------------------------- 色板（纯黑 · 白光）

    /** 屏幕底色：纯黑。导航栏采样的背板（MainActivity.BG）必须与它是同一支。 */
    val bg = Color(0xFF000000)

    /** 保留给渐变调用点的名字：纯黑体系里没有「顶部微亮」，两端同色。 */
    val bgTop = Color(0xFF000000)

    /** FLAT 档 / 关玻璃时的兜底面：透明白的深浅阶梯，不是灰色块。 */
    val surface = Color(0x0DFFFFFF)
    val surfaceHigh = Color(0x14FFFFFF)
    val surfaceTop = Color(0x1FFFFFFF)

    /** 玻璃层：只留给「浮在内容之上」的合法用法（dock、页头、弹层），全透明。 */
    val glassFill = Color(0x0AFFFFFF)
    val glassFillStrong = Color(0x14FFFFFF)
    val glassEdgeTop = Color(0x33FFFFFF)
    val glassEdgeBottom = Color(0x08FFFFFF)

    /** 分隔线：1px 细线，白 10%。 */
    val hairline = Color(0x1AFFFFFF)

    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0x99FFFFFF)
    val textTertiary = Color(0x5CFFFFFF)
    val textInverse = Color(0xFF000000)

    /** 强调色出厂值 = 白（光本身的颜色）；ui_accent 可覆盖成任意一支。 */
    val accentDefault = Color(0xFFFFFFFF)
    val accentSoft = Color(0x1AFFFFFF)
    val onAccent = Color(0xFF000000)

    /** 档位与状态：免费绿 / 白名单蓝 / 限制橙 / 停用红，全站只用这四个语义色，只出现在小徽标。 */
    val okFree = Color(0xFF4FBF7B)
    val paidBlue = Color(0xFF59A8FF)
    val limitAmber = Color(0xFFE09551)
    val danger = Color(0xFFE07B7B)

    /** 页面底流光的峰值透明度（主带 / 副带）。玻璃里再叠一份，所以底上不能太亮。 */
    const val flowPeak = 0.13f
    const val flowPeak2 = 0.07f

    // ---------------------------------------------------------------- 尺寸（8dp 栅格）

    val gutter = 16.dp            // 页面左右留白
    val gapXs = 4.dp
    val gapSm = 8.dp
    val gapMd = 12.dp
    val gapLg = 16.dp
    val gapXl = 24.dp
    val gapXxl = 32.dp

    val rowMinHeight = 56.dp      // 列表行最小高（命中区一定 ≥48dp）
    val cardMinHeight = 116.dp    // 网格卡最小高
    val iconTile = 40.dp          // 图标底托（squircle）
    val iconGlyph = 22.dp
    val heroHeight = 168.dp       // 首页沉浸头图区

    /**
     * 悬浮 dock 的高度：搜索胶囊 46 + 上下各 8 + 分类导轨 44 ≈ 106，取 108。
     * 内容整块向下让出这个高度，dock 浮在它上面 —— 滚动内容从玻璃下面穿过。
     */
    val dockHeight = 108.dp

    // ---------------------------------------------------------------- 时长

    val pressMs = 170
    val enterMs = 240
    val swapMs = 200

    // ---------------------------------------------------------------- 云控覆盖

    /**
     * 每次建页面读一次就够（门面是内存里的最终状态，不便宜到可以随便轮询）。
     * `legacy` 判定也在这里，界面宿主只问这一个对象。
     */
    class Spec(
            val accent: Color,
            val radius: Dp,
            val glass: Boolean,
            val motion: Boolean,
            /** 流光开关（ui_flow=0 关掉，页面退纯黑静底）。 */
            val flow: Boolean,
            /** FULL 面板的模糊半径（ui_blur，dp）。云控可按机型/系统版本条件下发。 */
            val blurDp: Dp,
            /** lens 折射强度（ui_refract，dp）。0 = 关折射。 */
            val refractDp: Dp,
            val legacyHome: Boolean,
            /** 云控里到底有没有动过这些值 —— 状态面板要说清是出厂还是下发。 */
            val fromCloud: Boolean,
    )

    fun spec(context: Context): Spec {
        val gate = CloudCatalog.gate()
        val home = gate.flag("ui_home", "")
        val accentRaw = gate.flag("ui_accent", "")
        val radiusRaw = gate.flag("ui_radius", "")
        val glassRaw = gate.flag("ui_glass", "")
        val motionRaw = gate.flag("ui_motion", "")
        val flowRaw = gate.flag("ui_flow", "")
        val blurRaw = gate.flag("ui_blur", "")
        val refractRaw = gate.flag("ui_refract", "")
        val cloud = home.length > 0 || accentRaw.length > 0 || radiusRaw.length > 0 ||
                glassRaw.length > 0 || motionRaw.length > 0 || flowRaw.length > 0 ||
                blurRaw.length > 0 || refractRaw.length > 0
        /*
         * 机型策略压在最上面一层：`deep` 时模糊与折射各有上限，流光与动效一起关。
         * 这里改的是**同一份 Spec**，所以 Compose 与 View 两条路自动一致 ——
         * 如果只在其中一处判断，就会出现"顶栏还是玻璃、面板已经变平"那种半截效果。
         */
        var blur = parseDpRange(blurRaw, 2.dp, 0, 24)
        var refract = parseDpRange(refractRaw, 32.dp, 0, 48)
        val capBlur = com.mineways.GlassPolicy.blurCapDp()
        if (capBlur >= 0 && blur > capBlur.dp) {
            blur = capBlur.dp
        }
        val capRefract = com.mineways.GlassPolicy.refractCapDp()
        if (capRefract >= 0 && refract > capRefract.dp) {
            refract = capRefract.dp
        }
        return Spec(
                accent = parseColor(accentRaw) ?: accentDefault,
                radius = parseDp(radiusRaw) ?: 20.dp,
                glass = com.mineways.GlassPolicy.glassOn(context, !"0".equals(glassRaw)),
                motion = !"0".equals(motionRaw) && !com.mineways.GlassPolicy.dropMotion(),
                flow = !"0".equals(flowRaw) && !com.mineways.GlassPolicy.dropFlow(),
                blurDp = blur,
                refractDp = refract,
                legacyHome = "legacy".equals(home, ignoreCase = true),
                fromCloud = cloud,
        )
    }

    /** `#RRGGBB` / `#AARRGGBB`，坏值一律当没填（不能因为一条手误的配置炸界面）。 */
    private fun parseColor(raw: String): Color? {
        var s = raw.trim()
        if (s.isEmpty()) {
            return null
        }
        if (s.startsWith("#")) {
            s = s.substring(1)
        }
        if (s.length != 6 && s.length != 8) {
            return null
        }
        return try {
            val v = s.toLong(16)
            if (s.length == 6) Color(0xFF000000L or v) else Color(v)
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseDp(raw: String): Dp? {
        val n = raw.trim().toIntOrNull() ?: return null
        if (n < 0 || n > 32) {
            return null
        }
        return n.dp
    }

    /** 带范围的解析：坏值回默认（不能因为一条手误的配置炸界面）。 */
    private fun parseDpRange(raw: String, fallback: Dp, min: Int, max: Int): Dp {
        val n = raw.trim().toIntOrNull() ?: return fallback
        if (n < min || n > max) {
            return fallback
        }
        return n.dp
    }
}
