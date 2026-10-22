package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * 液态玻璃对话框
 *
 * 参考官方 DialogContent 实现，包含：
 * - 模态玻璃卡片
 * - dimOverlay 遮罩
 * - colorControls 亮度/饱和度控制
 * - depthEffect 折射
 * - Highlight.Plain 高光
 * - BlendMode.Plus 文本增强
 *
 * @param visible 是否可见
 * @param onDismiss 关闭回调
 * @param backdrop 背景源
 * @param modifier 修饰符
 * @param title 标题
 * @param content 内容
 * @param confirmButton 确认按钮
 * @param dismissButton 取消按钮
 */
@Composable
fun LiquidDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    title: String = "Dialog Title",
    content: @Composable () -> Unit = {},
    showDismissButton: Boolean = true,
    showConfirmButton: Boolean = true,
    dismissButton: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit = {}
) {
    if (!visible) return

    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(alpha = 0.6f)
        else Color(0xFF121212).copy(alpha = 0.4f)
    val dimColor =
        if (isLightTheme) Color(0xFF29293A).copy(alpha = 0.23f)
        else Color(0xFF121212).copy(alpha = 0.56f)

    Box(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRect(dimColor)
            }
    ) {
        Column(
            Modifier
                .padding(40f.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(48f.dp) },
                    effects = {
                        colorControls(
                            brightness = if (isLightTheme) 0.2f else 0f,
                            saturation = 1.5f
                        )
                        blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                        lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                    },
                    highlight = { Highlight.Plain },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .fillMaxWidth()
        ) {
            BasicText(
                title,
                Modifier.padding(28f.dp, 24f.dp, 28f.dp, 12f.dp),
                style = TextStyle(contentColor, 24f.sp, FontWeight.Medium)
            )

            Box(
                Modifier
                    .then(
                        if (isLightTheme) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer(blendMode = BlendMode.Plus)
                        }
                    )
                    .padding(24f.dp, 12f.dp, 24f.dp, 12f.dp)
            ) {
                content()
            }

            Row(
                Modifier
                    .padding(24f.dp, 12f.dp, 24f.dp, 24f.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16f.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDismissButton) {
                    Row(
                        Modifier
                            .clip(Capsule())
                            .background(containerColor.copy(alpha = 0.2f))
                            .clickable(onClick = onDismiss)
                            .height(48f.dp)
                            .weight(1f)
                            .padding(horizontal = 16f.dp),
                        horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton()
                    }
                }
                if (showConfirmButton) {
                    Row(
                        Modifier
                            .clip(Capsule())
                            .background(accentColor)
                            .clickable(onClick = onDismiss)
                            .height(48f.dp)
                            .weight(1f)
                            .padding(horizontal = 16f.dp),
                        horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        confirmButton()
                    }
                }
            }
        }
    }
}
