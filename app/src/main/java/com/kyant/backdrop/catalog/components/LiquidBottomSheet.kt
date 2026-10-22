package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.ProgressConverter
import com.kyant.backdrop.catalog.utils.rememberUISensor
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.launch

/**
 * 液态玻璃底部弹窗
 *
 * 参考官方 ControlCenterContent 实现，包含：
 * - 拖拽展开/收起
 * - 内容渐进显示
 * - 环境光感应高光
 * - 动态模糊强度
 *
 * @param visible 是否可见
 * @param onDismiss 关闭回调
 * @param backdrop 背景源
 * @param modifier 修饰符
 * @param content 弹窗内容
 */
@Composable
fun LiquidBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(progress: Float) -> Unit
) {
    if (!visible) return

    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor = Color.Black.copy(alpha = 0.05f)
    val dimColor = Color.Black.copy(alpha = 0.4f)

    // 将弹窗内容导出为独立背景层，避免「玻璃上叠玻璃」导致 SIGSEGV 崩溃
    val bottomSheetBackdrop = rememberLayerBackdrop()

    val animationScope = rememberCoroutineScope()
    val enterProgressAnimation = remember { Animatable(1f) }
    val safeEnterProgressAnimation = remember { Animatable(1f) }
    val progress by remember {
        derivedStateOf {
            val progress = enterProgressAnimation.value
            when {
                progress < 0f -> ProgressConverter.Default.convert(progress)
                progress <= 1f -> progress
                else -> 1f + ProgressConverter.Default.convert(progress - 1f)
            }
        }
    }
    val maxDragHeight = 1000f

    val uiSensor = rememberUISensor()
    val glassHighlight = {
        Highlight(
            style = HighlightStyle.Default(
                angle = uiSensor.gravityAngle,
                falloff = 2f
            )
        )
    }
    val glassLayer: BackdropEffectScope.() -> Unit = {
        val progress = progress
        val safeProgress = safeEnterProgressAnimation.value
        // 注意：layerBlock 中的变换通过 drawBackdrop 的 layerBlock 参数处理
    }
    val glassSurface: BackdropEffectScope.() -> Unit = {
        val progress = safeEnterProgressAnimation.value
        if (progress > 0f) {
            // 表面颜色由 onDrawSurface 处理
        }
    }
    val glassEffects: BackdropEffectScope.() -> Unit = {
        val progress = safeEnterProgressAnimation.value
        vibrancy()
        lens(
            24f.dp.toPx() * progress,
            48f.dp.toPx() * progress,
            depthEffect = true
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .draggable(
                rememberDraggableState { delta ->
                    val targetProgress = enterProgressAnimation.value + delta / maxDragHeight
                    animationScope.launch {
                        launch {
                            enterProgressAnimation.snapTo(targetProgress)
                        }
                        launch {
                            safeEnterProgressAnimation.snapTo(targetProgress.fastCoerceIn(0f, 1f))
                        }
                    }
                },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    val targetProgress = when {
                        velocity < 0f -> 0f
                        velocity > 0f -> 1f
                        else -> if (enterProgressAnimation.value < 0.5f) 0f else 1f
                    }
                    animationScope.launch {
                        launch {
                            enterProgressAnimation.animateTo(
                                targetProgress,
                                if (targetProgress > 0.5f) {
                                    spring(0.5f, 300f, 0.5f / maxDragHeight)
                                } else {
                                    spring(1f, 300f, 0.01f)
                                },
                                velocity / maxDragHeight
                            )
                        }
                        launch {
                            safeEnterProgressAnimation.animateTo(
                                targetProgress,
                                spring(1f, 300f, 0.01f)
                            )
                        }
                    }
                }
            )
            .drawWithContent {
                val progress = safeEnterProgressAnimation.value

                drawContent()
                drawRect(dimColor.copy(dimColor.alpha * progress))
            }
            .graphicsLayer {
                val progress = safeEnterProgressAnimation.value

                val blurRadius = 4f.dp.toPx() * progress
                if (blurRadius > 0f) {
                    renderEffect = androidx.compose.ui.graphics.BlurEffect(blurRadius, blurRadius)
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 80f.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 拖拽指示器
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 40f.dp, height = 4.dp)
                    .clip(RoundedRectangle(2f.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 玻璃内容区域
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(24f.dp) },
                        effects = glassEffects,
                        highlight = glassHighlight,
                        shadow = null,
                        exportedBackdrop = bottomSheetBackdrop,
                        layerBlock = {
                            val progress = safeEnterProgressAnimation.value
                            translationY = -48f.dp.toPx() * (1f - progress)
                            alpha = EaseIn.transform(safeEnterProgressAnimation.value)
                            scaleX /= 1f + 0.1f * (progress - 1f).fastCoerceAtLeast(0f)
                            scaleY *= 1f + 0.1f * (progress - 1f).fastCoerceAtLeast(0f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    content(safeEnterProgressAnimation.value)
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) {
            enterProgressAnimation.snapTo(0f)
            safeEnterProgressAnimation.snapTo(0f)
        }
    }
}

/**
 * 底部弹窗内容项
 */
@Composable
fun BottomSheetItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp)
            .clip(RoundedRectangle(12f.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(40f.dp)
                .clip(RoundedRectangle(10f.dp))
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
