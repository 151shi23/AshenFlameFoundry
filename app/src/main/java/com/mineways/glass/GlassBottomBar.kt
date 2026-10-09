package com.mineways.glass

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/**
 * 底部液态玻璃导航栏 —— 玻璃全部交给玄戒工具箱用的那个库（io.github.kyant0:backdrop）。
 *
 * 关键结构（照着上游 LiquidBottomTabs 的三层摆法）：
 *
 *   ① 透明副本：图标 + 文字用 alpha(0) 再画一遍，用 layerBackdrop 录进 tabsBackdrop —— 这是
 *      滑块能"碾糊"图标文字的素材来源（原生 View 画在 Compose 之上，录不进来，所以标签格
 *      必须由 Compose 画）。
 *   ② 背板：Capsule · vibrancy + colorControls（提饱和提对比）· lens(28dp, 38dp, 立体 + 色散)
 *      · blur(14dp) · 加宽边光 · 容器色 #121212@45%
 *      —— 顺序是"先折射再糊"：库用 blur 的半径把采样层向外撑开（padding），折射才采得到
 *      形状外侧的真实画面，否则会糊到边缘像素上出现暗缝。
 *   ③ 滑块（画在最上层）：背景 = combined(屏幕快照, 图标层)，所以拖动时边缘扫过的文字与图标
 *      会被折射 + 糊掉。强度 level = 0.45 + 0.55×按下进度 —— **静止时就有一档玻璃**，
 *      按下才拉满：lens(14dp·L, 22dp·L, 立体 + 色散) · blur(6dp·L) · 宽边光 · 投影 · 内阴影
 *
 * 触摸不在这里：标签格的原生触摸由 LiquidGlassNav 在 onInterceptTouchEvent 接管，
 * 再通过 [controller] 驱动这里的弹簧动画（spring(1f,1000f) 等，参数同玄戒）。
 */
@Composable
fun GlassBottomBar(
    titles: List<String>,
    iconRes: List<Int>,
    selectedIndex: Int,
    backdrop: Backdrop,
    accentColor: Color,
    dimColor: Color,
    onSelect: (Int) -> Unit,
    controller: GlassNavController = remember { GlassNavController() },
    modifier: Modifier = Modifier,
    barHeight: Dp = 64.dp,
) {
    val tabsCount = titles.size
    if (tabsCount == 0 || iconRes.size != tabsCount) {
        return
    }
    // 库的模糊要 API 31+、折射要 33+；更低的机器上改用接近不透明的底色兜底
    val containerColor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Color(0xFF121212).copy(alpha = 0.45f)
        } else {
            Color(0xFF141414).copy(alpha = 0.94f)
        }
    val animationScope = rememberCoroutineScope()
    val currentOnSelect by rememberUpdatedState(onSelect)
    var tabWidth by remember { mutableFloatStateOf(0f) }

    // ② 背板玻璃、③ 滑块玻璃分别采样：屏幕内容 + 栏自身的图标文字层
    val tabsBackdrop = rememberLayerBackdrop()
    val pillBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    val dampedDragAnimation = remember(animationScope, tabsCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDrag = { _, dragAmount ->
                if (tabWidth > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in 0 until tabsCount) {
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    SideEffect {
        controller.pressAction = { dampedDragAnimation.press() }
        controller.releaseAction = { dampedDragAnimation.release() }
        controller.dragAction = { dx ->
            if (tabWidth > 0f) {
                dampedDragAnimation.updateValue(
                    (dampedDragAnimation.targetValue + dx / tabWidth)
                        .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                )
            }
        }
        controller.settleAction = { dragged ->
            val index =
                if (dragged) {
                    dampedDragAnimation.targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                } else {
                    selectedIndex
                }
            dampedDragAnimation.animateToValue(index.toFloat())
            if (dragged && index != selectedIndex) {
                currentOnSelect(index)
            }
        }
        controller.indexAction = { x ->
            if (tabWidth > 0f) {
                (x / tabWidth).toInt().fastCoerceIn(0, tabsCount - 1)
            } else {
                0
            }
        }
    }

    val tabsContent: @Composable RowScope.() -> Unit = {
        titles.forEachIndexed { index, title ->
            Box(Modifier.weight(1f).fillMaxHeight()) {
                TabItem(
                    title = title,
                    iconRes = iconRes[index],
                    selected = index == selectedIndex,
                    accentColor = accentColor,
                    dimColor = dimColor
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        // ① 透明副本：只为把图标 + 文字录进 tabsBackdrop
        Row(
            Modifier
                .clearAndSetSemantics { }
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .height(barHeight)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabsContent()
        }

        // ② 背板：饱和 + 提对比 + 折射（含立体感与色散）+ 模糊 + 容器色
        Row(
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        colorControls(brightness = 0.02f, contrast = 1.08f, saturation = 1.25f)
                        // 先折射再糊：blur 的半径会把采样层向外撑开（scope.padding），
                        // 折射才采得到形状之外的真实画面；反过来写 padding 会被 lens 吃掉
                        lens(
                            28f.dp.toPx(),
                            38f.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                        blur(14f.dp.toPx())
                    },
                    highlight = {
                        Highlight(
                            width = 1f.dp,
                            blurRadius = 1.4f.dp,
                            alpha = 0.8f,
                            style = HighlightStyle.Default(angle = 60f, falloff = 0.7f)
                        )
                    },
                    shadow = {
                        Shadow(radius = 26f.dp, offset = DpOffset(0f.dp, 7f.dp), alpha = 0.9f)
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .drawWithContent {
                    val progress = dampedDragAnimation.pressProgress
                    if (progress > 0f) {
                        drawRect(Color.White.copy(0.34f * progress), blendMode = BlendMode.Plus)
                    }
                    drawContent()
                }
                .height(barHeight)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabsContent()
        }

        // ③ 滑块（最上层）：拖动时边缘碾过图标与文字 —— 折射 + 模糊实时跟着走
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidth
                }
                .drawBackdrop(
                    backdrop = pillBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val level = 0.45f + 0.55f * dampedDragAnimation.pressProgress
                        lens(
                            14f.dp.toPx() * level,
                            22f.dp.toPx() * level,
                            depthEffect = true,
                            chromaticAberration = true
                        )
                        blur(6f.dp.toPx() * level)
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight(
                            width = 1.2f.dp,
                            blurRadius = 1.6f.dp,
                            alpha = 0.55f + 0.45f * progress,
                            style = HighlightStyle.Default(angle = 60f, falloff = 0.72f)
                        )
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(
                            radius = 22f.dp,
                            offset = DpOffset(0f.dp, 6f.dp),
                            alpha = 0.4f + 0.6f * progress
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        val level = 0.45f + 0.55f * progress
                        InnerShadow(
                            radius = 12f.dp * level,
                            offset = DpOffset(0f.dp, 4f.dp * level),
                            color = Color.Black.copy(alpha = 0.22f),
                            alpha = 0.5f + 0.5f * progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        // 静止态就是一块有明暗的厚玻璃：上亮下暗
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.18f),
                                1f to Color.White.copy(alpha = 0.06f)
                            ),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.05f * progress))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

/** 一个标签格：图标 + 文字（Compose 画，才能被录进玻璃背景）。 */
@Composable
private fun TabItem(
    title: String,
    iconRes: Int,
    selected: Boolean,
    accentColor: Color,
    dimColor: Color,
) {
    val color = if (selected) accentColor else dimColor
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = title,
            style = TextStyle(
                color = color,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

/**
 * 把「玻璃后面那块屏幕内容」交给库：模糊 / 折射 / 高光仍由它的着色器算，这里只负责把背景铺上去。
 *
 * 关键：**不能**在绘制里直接 view.draw()。同一个窗口的 View 树此刻正在录 DisplayList，
 * 再进一次 beginRecording 就抛 "Recording currently in progress - missing #endRecording()"。
 * 所以内容由外面按帧快照成一张**软件位图**（Canvas(bitmap) 走软件路径，不碰 RenderNode），
 * 绘制阶段只贴这张图。
 */
class ViewBackdrop(
    private val bitmap: () -> Bitmap?,
    private val bitmapOriginInWindow: () -> Offset,
    private val bitmapScale: () -> Float,
    private val paddingPx: Float = 48f,
) : Backdrop {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val bmp = bitmap() ?: return
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
            return
        }
        val scale = bitmapScale().coerceAtLeast(0.01f)
        val origin = bitmapOriginInWindow()
        val node = coordinates?.positionInWindow() ?: Offset.Zero
        val left = origin.x - node.x
        val top = origin.y - node.y
        val right = left + bmp.width / scale
        val bottom = top + bmp.height / scale
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            // 玻璃只采样本节点附近的像素，裁一圈就够
            native.clipRect(-paddingPx, -paddingPx, size.width + paddingPx, size.height + paddingPx)
            native.drawBitmap(bmp, null, RectF(left, top, right, bottom), paint)
            native.restoreToCount(save)
        }
    }
}
