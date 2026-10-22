package com.mineways.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.atan2
import android.content.Context
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 液态玻璃的落地层：库的效果链 + 动效规范收在一处。
 *
 * **分档**（[MwTier]，云控 `ui_tier` 可强指定）——按 liquid-glass 技能作者自己的告警
 * 「玻璃图层数量超硬件上限 → 部分设备崩溃/效果丢失」和 Emil Kowalski 的
 * 「小层比全屏层便宜得多；120Hz 的帧预算是 8ms 不是 16.7ms」：
 *  · HIGH（Android 13+ 且不是小内存机）：vibrancy → blur 2dp → lens + 色散，高光跟随机身；
 *  · BALANCED：vibrancy → blur 2dp → lens，**没有色散**（色散是 RGB 各采一次的最贵一档），高光固定；
 *  · FLAT（Android 12 以下，没有 RenderEffect）：半透底 + 一道 1px 描边，绝不出现"空玻璃"。
 *
 * **动效参数按 Emil `apple-design` §4 取临界阻尼**：默认 `dampingRatio = 1.0`（不过冲），
 * 只有拖拽释放这类"甩动"才允许 0.8 的轻微回弹。技能规范 v2 的 spring(0.5, 300) 是欠阻尼、
 * 会过冲 —— Hallmark 清单把 bounce/elastic/overshoot 直接列为"模板味"，所以这里改掉了，
 * 其余（blur 2dp、lens 12/24、高光 0.15→0.15+0.35p、阴影 0.08→+0.22p、内阴影 4→12dp）沿用 v2。
 *
 * 形状一律 `com.kyant.shapes` 的 RoundedRectangle / Capsule：lens 的 refractionHeight 不得超过
 * shape 最小圆角，系统的 RoundedCornerShape 在部分版本取不到 minCornerRadius，折射会静默消失。
 */
object MwGlass {

    /** 能不能跑真玻璃（RenderEffect = Android 12 / API 31 起）。 */
    fun supported(): Boolean = android.os.Build.VERSION.SDK_INT >= 31

    /** 玻璃表面的白纱：6% 白。全透明玻璃的关键是纱要薄，层次交给折射与 hairline。 */
    fun mist(dark: Boolean): Color =
        if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFFAFAFA).copy(alpha = 0.15f)

    fun shape(radiusDp: Dp = 16.dp): Shape =
        if (radiusDp >= 100.dp) Capsule() else RoundedRectangle(radiusDp)

    /** 按压：临界阻尼，跟手但不弹（Emil：默认 UI spring damping 1.0 / response 0.3~0.4）。 */
    val Press = spring<Float>(dampingRatio = 1.0f, stiffness = 320f)

    @Composable
    fun rememberPress(): InteractiveHighlight {
        val scope = rememberCoroutineScope()
        return remember(scope) { InteractiveHighlight(animationScope = scope) }
    }

    /** 系统把动画时长缩放设为 0（开发者选项 / 无障碍"移除动画"）时，只留淡入淡出。 */
    @JvmStatic
    fun reducedMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (t: Throwable) {
        false
    }
}

/** 流光带的渐变停靠点（主带 / 副带峰值不同）。 */
internal fun mainStops(): Array<Pair<Float, Color>> =
        Array(MwFlow.STOPS.size) { i -> MwFlow.STOPS[i] to Color(MwFlow.colors(MwTokens.flowPeak)[i]) }

internal fun secondStops(): Array<Pair<Float, Color>> =
        Array(MwFlow.STOPS.size) { i -> MwFlow.STOPS[i] to Color(MwFlow.colors(MwTokens.flowPeak2)[i]) }

/*
 * 这里原本还有一个 rememberFlowBackdrop()：用 withInfiniteAnimationFrameNanos 每帧改时间、
 * 每帧重录全屏背板。实测这就是待机发烫/滚动掉帧的主因 —— 背板一变，所有玻璃跟着重采样。
 * 2026-10-17 首页重写时删掉，改成 MwHome.drawMwScene() 的**静态场景**（只录一次）。
 * 需要背景流光时它照样在，只是不再逐帧动。
 */


/**
 * 玻璃底扩展（顶层函数：成员级的 Modifier 扩展在别的文件里不能直接 Modifier.glass(...)）。
 *
 * @param tier 档位；null 时按当前设备检测。列表里的格子传 BALANCED 档参数即可（内部按档位裁）
 * @param emphasize true = 这块值得最贵的一档（大格、dock、弹层）：HIGH 档才上色散
 * @param highlight 传了才做「tanh 拖拽跟随 + 方向性拉伸」形变（技能 v2 的 LiquidButton 规范）
 */
fun Modifier.glass(
        backdrop: Backdrop,
        shape: Shape,
        tint: Color = Color.Unspecified,
        press: Float = 0f,
        plain: Boolean = false,
        highlight: InteractiveHighlight? = null,
        tier: MwTier? = null,
        emphasize: Boolean = false,
        tilt: MwTilt? = null,
        blurDp: Float = 2f,
        refractionDp: Float = 12f,
        refractionAmountDp: Float = 24f,
        dark: Boolean = true,
): Modifier {
    val t = tier ?: MwTier.BALANCED
    if (!MwGlass.supported() || t == MwTier.FLAT) {
        // 降级分支：半透底 + 一道描边，结构不变、不空玻璃（技能交付自检明确要求）
        val base = if (tint.isSpecified) tint else MwGlass.mist(dark)
        return this.then(Modifier.clip(shape).background(base)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape))
    }
    val onSurface: DrawScope.() -> Unit = {
        drawRect(MwGlass.mist(dark))
        if (tint.isSpecified) {
            drawRect(tint.copy(alpha = 0.18f))
        }
    }
    // 只动 transform/opacity 之外的开销都要算：色散（RGB 各采一次）只给"值得的大块"。
    val chromatic = t == MwTier.HIGH && emphasize
    val effects: BackdropEffectScope.() -> Unit = {
        vibrancy()
        blur(blurDp.dp.toPx())                                   // 规范 v2：2dp，别拿模糊糊内容
        lens(refractionDp.dp.toPx(), refractionAmountDp.dp.toPx(), chromaticAberration = chromatic)
    }
    val layer: ((androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit))? =
        if (highlight != null) {
            {
                val h = size.height
                val w = size.width
                val p = highlight.pressProgress
                val base = lerp(1f, 1f + 4f.dp.toPx() / h, p)
                val maxOffset = size.minDimension
                val off = highlight.offset
                translationX = maxOffset * tanh(0.05f * off.x / maxOffset)
                translationY = maxOffset * tanh(0.05f * off.y / maxOffset)
                val maxDrag = 4f.dp.toPx() / h
                val angle = atan2(off.y, off.x)
                scaleX = base + maxDrag * abs(cos(angle) * off.x / size.maxDimension) *
                        (w / h).fastCoerceAtMost(1f)
                scaleY = base + maxDrag * abs(sin(angle) * off.y / size.maxDimension) *
                        (h / w).fastCoerceAtMost(1f)
            }
        } else {
            null
        }
    if (plain) {
        return this.drawPlainBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = effects,
                layerBlock = layer,
                onDrawSurface = onSurface,
        )
    }
    return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = effects,
            highlight = {
                Highlight(
                        width = 0.7f.dp,
                        blurRadius = 0.35f.dp,
                        alpha = 0.15f + 0.35f * press,          // 规范 v2
                        style = HighlightStyle.Default(
                                angle = if (t == MwTier.HIGH) (tilt?.angleRad ?: -2.356f)
                                else -2.356f,
                                falloff = 1.6f),
                )
            },
            shadow = { Shadow(alpha = 0.08f + 0.22f * press) },
            innerShadow = {
                InnerShadow(radius = 4f.dp + 8f.dp * press, alpha = 0.1f + 0.3f * press)
            },
            layerBlock = layer,
            onDrawSurface = onSurface,
    )
}

/**
 * 一屏一次的入场编排：错峰 40ms、只动 transform/opacity，总时长封顶 ~360ms。
 *
 * `play` 由调用方给**一次性门控**：瀑布流里 `remember { Animatable(...) }` 会随着条目滚出
 * 可视区而被销毁重建，不门控就成了"每滚一次重播一次入场" —— 那正是 Hallmark 清单里的
 * animate-on-scroll on everything（"the page never settles"）。只让首屏前几格播一次，
 * 之后进来的和回炉的一律直接是终态。
 */
@Composable
fun rememberMwEnter(order: Int, tier: MwTier, reduced: Boolean, play: Boolean = true): Modifier {
    val animate = play && tier != MwTier.FLAT && !reduced
    val shift = remember { Animatable(if (animate) 10f else 0f) }
    val fade = remember { Animatable(if (animate) 0f else 1f) }
    val capped = order.coerceAtMost(9)
    androidx.compose.runtime.LaunchedEffect(capped, animate) {
        if (!animate) {
            shift.snapTo(0f)
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay((capped * 40).toLong())
        kotlinx.coroutines.coroutineScope {
            launch { shift.animateTo(0f, MwGlass.Press) }
            launch { fade.animateTo(1f, MwGlass.Press) }
        }
    }
    return Modifier.graphicsLayer {
        alpha = fade.value
        translationY = shift.value
    }
}

/** 一张可按压的液态玻璃卡。backdrop 一定要传**不含自己**的那一层（循环绘制会 SIGSEGV）。 */
@Composable
fun MwGlassCard(
        backdrop: Backdrop,
        modifier: Modifier = Modifier,
        tint: Color = Color.Unspecified,
        plain: Boolean = false,
        dark: Boolean = isSystemInDarkTheme(),
        deform: Boolean = false,
        emphasize: Boolean = false,
        tier: MwTier? = null,
        tilt: MwTilt? = null,
        radiusDp: Dp = 16.dp,
        onClick: (() -> Unit)? = null,
        onPressStart: (() -> Unit)? = null,
        content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val t = tier ?: MwDevice.tierWithCloud(ctx)
    // 频率表：首页格子每天点几十次 → 默认不做形变大动画（deform=false），只留高光/内阴影增强
    val press = if (onClick != null && deform && t != MwTier.FLAT) MwGlass.rememberPress() else null
    val source = remember { MutableInteractionSource() }
    Box(
            modifier = modifier
                    .glass(
                            backdrop = backdrop,
                            shape = MwGlass.shape(radiusDp),
                            tint = tint,
                            press = press?.pressProgress ?: 0f,
                            plain = plain,
                            highlight = press,
                            tier = t,
                            emphasize = emphasize,
                            tilt = tilt,
                            dark = dark,
                    )
                    .then(if (onClick != null) {
                        Modifier.clickable(
                                interactionSource = source,
                                indication = null,
                                onClick = onClick)
                    } else {
                        Modifier
                    })
                    .then(if (press != null) {
                        Modifier.then(press.modifier).then(press.gestureModifier)
                    } else {
                        Modifier
                    }),
            content = { content() },
    )
}

/** 胶囊玻璃：dock、筛选 chip、玻璃按钮。小件一律不开色散。 */
@Composable
fun MwGlassPill(
        backdrop: Backdrop,
        modifier: Modifier = Modifier,
        selected: Boolean = false,
        accent: Color = MwTokens.accentDefault,
        tilt: MwTilt? = null,
        tier: MwTier? = null,
        radiusDp: Dp = 100.dp,
        onClick: (() -> Unit)? = null,
        content: @Composable () -> Unit,
) {
    MwGlassCard(
            backdrop = backdrop,
            modifier = modifier,
            tint = if (selected) accent else Color.Unspecified,
            plain = true,
            deform = false,
            emphasize = false,
            tier = tier,
            tilt = tilt,
            radiusDp = radiusDp,
            onClick = onClick,
            content = content,
    )
}

/** 悬浮玻璃条的一行骨架（左占满、右贴尾）。 */
@Composable
fun MwGlassBarRow(
        modifier: Modifier = Modifier,
        content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = MwTokens.gapMd),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content,
    )
}
