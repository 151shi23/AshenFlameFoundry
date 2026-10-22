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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 液态玻璃标题芯片
 *
 * 带有按压形变效果的胶囊形玻璃芯片，用于显示标题文字。按压时会产生基于
 * [InteractiveHighlight] 的位移（tanh 阻尼）和方向性拉伸形变，同时高光、
 * 阴影和内阴影随按压力度动态增强。
 *
 * 视觉效果：
 * - 模糊（blur 2dp）
 * - 透镜折射（lens 10/18dp + 色差 chromaticAberration）
 * - 活力饱和度增强（vibrancy）
 * - 按压位移：tanh 阻尼函数控制，最大偏移不超过组件尺寸
 * - 方向性拉伸：基于按压点的 atan2 角度，X/Y 方向独立缩放（fastCoerceAtMost 0.15）
 * - 动态高光（0.15 + 0.35 * pressProgress）
 * - 动态阴影（0.06 + 0.18 * pressProgress）
 * - 动态内阴影（radius 4+6dp, alpha 0.08+0.20 按压递增）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, Shadow, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.catalog.utils.InteractiveHighlight
 * - com.kyant.shapes.* （Capsule）
 *
 * @param backdrop 背景源，通常来自父容器的 exportedBackdrop
 * @param title 标题文字
 * @param isDark 是否深色模式，影响底色和文字颜色
 * @param modifier 修饰符
 *
 * 用法示例：
 * ```
 * LiquidTitleChip(
 *     backdrop = topBarBackdrop,
 *     title = "首页",
 *     isDark = isDark
 * )
 * ```
 */
@Composable
fun LiquidTitleChip(
    backdrop: Backdrop,
    title: String,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val animScope = rememberCoroutineScope()
    val hl = remember(animScope) { InteractiveHighlight(animScope) }
    val surface = if (isDark) Color(0xFF202040).copy(alpha = 0.22f)
    else Color(0xFFFFFFFF).copy(alpha = 0.22f)

    Box(
        modifier
            .height(38.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(10f.dp.toPx(), 18f.dp.toPx(), chromaticAberration = true)
                },
                layerBlock = {
                    val p = hl.pressProgress
                    val sc = lerp(1f, 1f + 3f.dp.toPx() / size.height, p)
                    val maxOff = size.minDimension
                    val off = hl.offset
                    translationX = maxOff * tanh(0.05f * off.x / maxOff)
                    translationY = maxOff * tanh(0.05f * off.y / maxOff)
                    scaleX = sc + lerp(0f, 4f.dp.toPx() / size.height, p) *
                        abs(cos(atan2(off.y, off.x)) * off.x / size.maxDimension)
                            .fastCoerceAtMost(0.15f)
                    scaleY = sc + lerp(0f, 4f.dp.toPx() / size.height, p) *
                        abs(sin(atan2(off.y, off.x)) * off.y / size.maxDimension)
                            .fastCoerceAtMost(0.15f)
                },
                highlight = {
                    val p = hl.pressProgress
                    Highlight.Default.copy(alpha = 0.15f + 0.35f * p)
                },
                shadow = {
                    val p = hl.pressProgress
                    Shadow(alpha = 0.06f + 0.18f * p)
                },
                innerShadow = {
                    val p = hl.pressProgress
                    InnerShadow(radius = 4f.dp + 6f.dp * p, alpha = 0.08f + 0.20f * p)
                },
                onDrawSurface = { drawRect(surface) }
            )
            .then(hl.modifier)
            .then(hl.gestureModifier)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            title,
            style = TextStyle(
                color = if (isDark) Color.White else Color(0xFF1A1A2E),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
