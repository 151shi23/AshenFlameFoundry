/*
 * Copyright (c) 2026
 *
 * This file is derived from AndroidLiquidGlass (Backdrop):
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Copyright (c) Kyant0 and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

/**
 * 液态玻璃弹出菜单
 *
 * 带有弹簧动画的圆角弹出菜单容器。出现时从 75% 缩放到 100%（spring dampingRatio=0.55,
 * stiffness=380），透明度从 0 渐变到 1，transformOrigin 为右上角 (1, 0)。
 *
 * 容器通过 [rememberLayerBackdrop] 导出 backdrop 供菜单项（[LiquidMenuItem]）使用。
 * 调用者通过 [content] lambda 提供菜单项内容，可在其中使用 [LiquidMenuItem] 组合项。
 *
 * 视觉效果：
 * - 模糊（blur 22dp）
 * - 透镜折射（lens 16/20dp + 色差 chromaticAberration）
 * - 活力饱和度增强（vibrancy）
 * - 边缘高光（Highlight 20% alpha）
 * - 外阴影（Shadow radius 24dp, alpha 18%）
 * - 内阴影（InnerShadow radius 6dp, alpha 8%）
 * - 半透明容器底色（深色 0x101020 @ 40% / 浅色 0xF5F5FA @ 40%）
 * - 弹簧入场动画（scale 0.75→1.0, alpha 0→1, transformOrigin 右上角）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, Shadow, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.backdrops.* （rememberLayerBackdrop, LayerBackdrop）
 * - androidx.compose.animation.core.* （Animatable, spring）
 *
 * @param backdrop 背景源，通常来自 rememberCombinedBackdrop
 * @param isDark 是否深色模式，影响容器底色和文字颜色
 * @param onDismiss 关闭菜单回调
 * @param modifier 修饰符
 * @param content 内容 lambda，接收导出的 [LayerBackdrop]、文字颜色和强调色供菜单项使用
 *
 * 用法示例：
 * ```
 * LiquidPopupMenu(
 *     backdrop = combinedBackdrop,
 *     isDark = isDark,
 *     onDismiss = { showMenu = false },
 *     modifier = Modifier
 *         .align(Alignment.TopEnd)
 *         .padding(end = 16.dp)
 *         .padding(top = 76.dp)
 * ) { menuBackdrop, textColor, accentColor ->
 *     listOf("设置", "关于", "退出").forEachIndexed { idx, label ->
 *         LiquidMenuItem(
 *             backdrop = menuBackdrop,
 *             label = label,
 *             isAccent = false,
 *             isDark = isDark,
 *             textColor = textColor,
 *             accentColor = accentColor,
 *             onClick = { showMenu = false }
 *         )
 *         if (idx < 2) {
 *             Spacer(
 *                 Modifier
 *                     .padding(horizontal = 10.dp)
 *                     .fillMaxWidth()
 *                     .height(0.5f.dp)
 *                     .drawBehind {
 *                         drawRect(
 *                             if (isDark) Color.White.copy(alpha = 0.09f)
 *                             else Color.Black.copy(alpha = 0.08f)
 *                         )
 *                     }
 *             )
 *         }
 *     }
 * }
 * ```
 */
@Composable
fun LiquidPopupMenu(
    backdrop: Backdrop,
    isDark: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(
        menuBackdrop: LayerBackdrop,
        textColor: Color,
        accentColor: Color
    ) -> Unit
) {
    val menuBackdrop = rememberLayerBackdrop()

    val containerSurface = if (isDark) Color(0xFF101020).copy(alpha = 0.40f)
    else Color(0xFFF5F5FA).copy(alpha = 0.40f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1A2E)
    val accentColor = Color(0xFF0088FF)

    val scaleAnim = remember { Animatable(0.75f) }
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scaleAnim.animateTo(1f, spring(0.55f, 380f, 0.001f)) }
        launch { alphaAnim.animateTo(1f, spring(0.55f, 380f, 0.001f)) }
    }

    Box(
        modifier.graphicsLayer {
            alpha = alphaAnim.value
        }
    ) {
        Column(
            Modifier
                .widthIn(min = 130.dp, max = 155.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    exportedBackdrop = menuBackdrop,
                    shape = { RoundedCornerShape(20.dp) },
                    effects = {
                        vibrancy()
                        blur(22f.dp.toPx())
                        lens(16f.dp.toPx(), 20f.dp.toPx(), chromaticAberration = true)
                    },
                    layerBlock = {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        transformOrigin = TransformOrigin(1f, 0f)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.20f) },
                    shadow = { Shadow(radius = 24f.dp, alpha = 0.18f) },
                    innerShadow = { InnerShadow(radius = 6f.dp, alpha = 0.08f) },
                    onDrawSurface = { drawRect(containerSurface) }
                )
                .padding(vertical = 4.dp)
        ) {
            content(menuBackdrop, textColor, accentColor)
        }
    }
}

/**
 * 液态玻璃菜单项
 *
 * 弹出菜单中的单个菜单项，带有按压形变效果。按压时模糊和透镜折射效果从零渐增，
 * 同时产生轻微缩小（1.0 → 0.97）和高光、内阴影增强。
 *
 * 视觉效果：
 * - 动态模糊（blur 2dp * pressProgress，仅按压时激活）
 * - 动态透镜折射（lens 6/10dp * pressProgress，仅按压时激活）
 * - 按压缩放：lerp(1f, 0.97f, pressProgress)
 * - 动态高光（0 + 0.38 * pressProgress）
 * - 动态内阴影（radius 3dp * pressProgress, alpha 0.20 * pressProgress）
 * - 按压底色（深色 White @ 5.5% / 浅色 Black @ 4%，随 pressProgress 渐显）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.catalog.utils.InteractiveHighlight
 *
 * @param backdrop 背景源，来自 [LiquidPopupMenu] 导出的 menuBackdrop
 * @param label 菜单项文字
 * @param isAccent 是否使用强调色（true 时使用 accentColor，false 时使用 textColor）
 * @param isDark 是否深色模式，影响按压底色
 * @param textColor 普通文字颜色
 * @param accentColor 强调文字颜色
 * @param onClick 点击回调
 * @param modifier 修饰符
 *
 * 用法示例：
 * ```
 * LiquidMenuItem(
 *     backdrop = menuBackdrop,
 *     label = "设置",
 *     isAccent = false,
 *     isDark = isDark,
 *     textColor = textColor,
 *     accentColor = accentColor,
 *     onClick = { showMenu = false }
 * )
 * ```
 */
@Composable
fun LiquidMenuItem(
    backdrop: Backdrop,
    label: String,
    isAccent: Boolean,
    isDark: Boolean,
    textColor: Color,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animScope = rememberCoroutineScope()
    val hl = remember(animScope) { InteractiveHighlight(animScope) }
    val color = textColor

    Box(
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(12.dp) },
                effects = {
                    val p = hl.pressProgress
                    if (p > 0.01f) {
                        blur(2f.dp.toPx() * p)
                        lens(6f.dp.toPx() * p, 10f.dp.toPx() * p)
                    }
                },
                layerBlock = {
                    val p = hl.pressProgress
                    val sc = lerp(1f, 0.97f, p)
                    scaleX = sc
                    scaleY = sc
                },
                highlight = {
                    val p = hl.pressProgress
                    Highlight.Default.copy(alpha = 0f + 0.38f * p)
                },
                shadow = null,
                innerShadow = {
                    val p = hl.pressProgress
                    InnerShadow(radius = 3f.dp * p, alpha = 0.20f * p)
                },
                onDrawSurface = {
                    val p = hl.pressProgress
                    if (p > 0.01f) {
                        drawRect(
                            if (isDark) Color.White.copy(alpha = 0.055f * p)
                            else Color.Black.copy(alpha = 0.04f * p)
                        )
                    }
                }
            )
            .then(hl.modifier)
            .then(hl.gestureModifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
