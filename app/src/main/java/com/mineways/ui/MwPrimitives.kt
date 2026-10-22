package com.mineways.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计层的基元：页面里所有可见东西都由这几块拼出来。
 *
 * 之所以不用现成 `Card`/`Surface`：Material 默认件一眼就是"安卓原生"。国产 App 的观感差别
 * 主要落在**层次**上 —— 半透玻璃、上亮下暗的描边、按下时整块微缩、分组之间的呼吸。
 * 这些必须亲手控。
 */

/** 字阶：中文界面比拉丁文要大半号，这套按主流国产 App 的实测取。 */
object MwType {
    val display = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp,
            color = MwTokens.textPrimary)
    val title = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp,
            color = MwTokens.textPrimary)
    val headline = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 21.sp,
            color = MwTokens.textPrimary)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp,
            color = MwTokens.textSecondary)
    val caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp,
            color = MwTokens.textTertiary)
    val badge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 13.sp,
            letterSpacing = 0.6.sp)
    val mono = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace, color = MwTokens.textTertiary)
}

/** 云控没生效时的出厂参数；界面代码写默认值时引用它，不要各处 new。 */
val MwDefaultSpec = MwTokens.Spec(
        accent = MwTokens.accentDefault,
        radius = 14.dp,
        glass = true,
        motion = true,
        flow = true,
        blurDp = 2.dp,
        refractDp = 32.dp,
        legacyHome = false,
        fromCloud = false)

/**
 * 屏幕底：纯黑。层次全部交给白色流光与玻璃，这里只负责把底铺死。
 */
@Composable
fun MwScreenBackground(spec: MwTokens.Spec, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().drawWithCache {
            onDrawBehind { drawRect(MwTokens.bg) }
        })
        content()
    }
}

/**
 * 玻璃面板：内部一层白纱 + 上亮下暗的描边 + 一点点模糊。
 * `spec.glass=false`（云控关掉）时退成实心 surface，低端机不吃合成开销。
 */
@Composable
fun MwGlass(
        modifier: Modifier = Modifier,
        spec: MwTokens.Spec = MwDefaultSpec,
        strong: Boolean = false,
        content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(spec.radius)
    val edge = Brush.verticalGradient(listOf(MwTokens.glassEdgeTop, MwTokens.glassEdgeBottom))
    val inner = if (!spec.glass) {
        Brush.verticalGradient(listOf(MwTokens.surface, MwTokens.surface))
    } else {
        Brush.verticalGradient(listOf(
                MwTokens.glassFillStrong.copy(alpha = if (strong) 0.80f else 0.55f),
                MwTokens.glassFill.copy(alpha = if (strong) 0.42f else 0.26f)))
    }
    // 不再给面板自己套 Modifier.blur(0.6.dp)：那既不是“透出背后”的真模糊（模糊的是面板自己的
    // 内容），又要每个面板多开一个 RenderEffect 图层 —— 长列表里就是纯掉帧。
    Box(modifier.clip(shape).background(inner)
            .border(1.dp, edge, shape)) { content() }
}

/** 带统一内边距的玻璃块，绝大多数分区用它。 */
@Composable
fun MwPanel(
        modifier: Modifier = Modifier,
        spec: MwTokens.Spec = MwDefaultSpec,
        inner: PaddingValues = PaddingValues(MwTokens.gapLg),
        strong: Boolean = false,
        content: @Composable () -> Unit,
) {
    MwGlass(modifier, spec, strong) {
        Box(Modifier.padding(inner)) { content() }
    }
}

/**
 * 按压缩放：整块缩到 0.975 并略微变暗，松手用**弹簧**弹回。
 * 国产 App 的"跟手"感几乎全靠这个；线性的 Material ripple 反而更像系统控件。
 */
@Composable
fun Modifier.mwPress(source: MutableInteractionSource, motion: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    // 云控关掉动效时用极硬的弹簧（几乎瞬时到位），类型仍然是 Float 的动画规范
    // 按压反馈：Emil 的 apple-design 表 —— 默认 UI 弹簧是**临界阻尼**（damping 1.0，不过冲），
    // 回弹只留给"甩动/拖拽释放"；时长 100~160ms。0.62 的欠阻尼会抖一下，正是清单里的
    // "Bounce / elastic / overshoot on UI = 模板味"。云控关动效时用极硬弹簧，几乎瞬时到位。
    val press = spring<Float>(dampingRatio = 1.0f, stiffness = 320f)
    val scale = animateFloatAsState(
            if (pressed && motion) 0.972f else 1f,
            if (motion) press else spring(stiffness = Spring.StiffnessHigh), label = "mw-press")
    val dim = animateFloatAsState(
            if (pressed && motion) 0.86f else 1f,
            if (motion) press else spring(stiffness = Spring.StiffnessHigh), label = "mw-press-dim")
    return this.then(Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        alpha = dim.value
    })
}

/** 图标底托：把线条图标垫在渐变 squircle 里，替掉"系统默认图标"的观感。 */
@Composable
fun MwIconTile(
        tint: Color,
        modifier: Modifier = Modifier,
        size: Dp = MwTokens.iconTile,
        content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(
                    tint.copy(alpha = 0.30f), tint.copy(alpha = 0.10f))))
            .border(1.dp, tint.copy(alpha = 0.26f), shape),
            contentAlignment = Alignment.Center) { content() }
}

/** 语义徽标：档位、云端新增、已停用都用它，全站一套尺寸与字距。 */
@Composable
fun MwBadge(text: String, color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.heightIn(min = 18.dp)
            .background(if (filled) color else color.copy(alpha = 0.16f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)) {
        BasicText(text, style = MwType.badge.copy(
                color = if (filled) MwTokens.onAccent else color))
    }
}

/** 可点行：命中区一律不小于 56dp（技能硬规定 ≥48dp，这里留足余量）。 */
@Composable
fun MwRow(
        modifier: Modifier = Modifier,
        onClick: (() -> Unit)? = null,
        interaction: MutableInteractionSource? = null,
        spec: MwTokens.Spec = MwDefaultSpec,
        verticalPadding: Dp = 13.dp,
        content: @Composable RowScope.() -> Unit,
) {
    val src = interaction ?: rememberMutableSource()
    val clickableModifier = if (onClick == null) Modifier else Modifier
            .clip(RoundedCornerShape(spec.radius))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    Row(modifier.fillMaxWidth().heightIn(min = MwTokens.rowMinHeight)
            .then(clickableModifier)
            .then(if (onClick == null) Modifier else Modifier.mwPress(src, spec.motion))
            .padding(vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MwTokens.gapMd),
            content = content)
}

/** interactionSource 的默认值：用 remember 自己造，不依赖各版本挪来挪去的工厂函数。 */
@Composable
private fun rememberMutableSource(): MutableInteractionSource =
        androidx.compose.runtime.remember { MutableInteractionSource() }

/** 分组头：左侧强调色短条 + 标题 + 这一组几条，节奏和抖音/QQ 一致。 */
@Composable
fun MwSectionHeader(title: String, subtitle: String? = null, trailing: String? = null,
        spec: MwTokens.Spec = MwDefaultSpec,
        modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = MwTokens.gapXl, bottom = MwTokens.gapMd),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MwTokens.gapSm)) {
        Box(Modifier.width(3.dp).height(15.dp)
                .background(spec.accent, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = MwType.title)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = MwType.caption, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            BasicText(trailing, style = MwType.caption)
        }
    }
}

/** 组内分割线：左内缩到文字起点（微信的做法），否则像系统 ListView。 */
@Composable
fun MwDivider(inset: Dp = 52.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).padding(start = inset)
            .background(MwTokens.hairline))
}

/** 一行小灰字说明。 */
@Composable
fun MwNote(text: String, modifier: Modifier = Modifier, style: TextStyle = MwType.caption) {
    BasicText(text, modifier = modifier, style = style)
}

/** 条目序号（保留原来 01/02 的秩序感，但压到角落，不跟标题抢）。 */
@Composable
fun MwIndex(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, style = MwType.mono)
}
