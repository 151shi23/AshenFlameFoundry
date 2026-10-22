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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import kotlin.math.tanh

/**
 * 液态玻璃三点菜单按钮
 *
 * 圆形（CircleShape）液态玻璃按钮，内部用 Canvas 绘制三个垂直排列的圆点。
 * 按压时通过 [InteractiveHighlight] 产生位移（tanh 阻尼）和均匀缩放（1.0 → 1.15），
 * 高光、阴影和内阴影随按压力度动态增强。
 *
 * 视觉效果：
 * - 模糊（blur 2dp）
 * - 透镜折射（lens 12/20dp + 色差 chromaticAberration）
 * - 活力饱和度增强（vibrancy）
 * - 按压位移：tanh 阻尼函数控制
 * - 均匀缩放：lerp(1f, 1.15f, pressProgress)
 * - 动态高光（0.15 + 0.35 * pressProgress）
 * - 动态阴影（0.08 + 0.22 * pressProgress）
 * - 动态内阴影（radius 4+8dp, alpha 0.10+0.25 按压递增）
 * - Canvas 绘制三个圆点（半径 2.5dp，间距 9dp）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, Shadow, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.catalog.utils.InteractiveHighlight
 *
 * @param backdrop 背景源，通常来自父容器的 exportedBackdrop
 * @param isDark 是否深色模式，影响底色和圆点颜色
 * @param onClick 点击回调
 * @param modifier 修饰符
 *
 * 用法示例：
 * ```
 * LiquidMenuButton(
 *     backdrop = topBarBackdrop,
 *     isDark = isDark,
 *     onClick = { showMenu = !showMenu },
 *     modifier = Modifier
 *         .align(Alignment.CenterEnd)
 *         .padding(end = 6.dp)
 *         .size(44.dp)
 * )
 * ```
 */
@Composable
fun LiquidMenuButton(
    backdrop: Backdrop,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animScope = rememberCoroutineScope()
    val hl = remember(animScope) { InteractiveHighlight(animScope) }
    val surface = if (isDark) Color(0xFF202040).copy(alpha = 0.22f)
    else Color(0xFFFFFFFF).copy(alpha = 0.22f)

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 20f.dp.toPx(), chromaticAberration = true)
                },
                layerBlock = {
                    val p = hl.pressProgress
                    val sc = lerp(1f, 1.15f, p)
                    val maxOff = size.minDimension
                    val off = hl.offset
                    translationX = maxOff * tanh(0.05f * off.x / maxOff)
                    translationY = maxOff * tanh(0.05f * off.y / maxOff)
                    scaleX = sc
                    scaleY = sc
                },
                highlight = {
                    val p = hl.pressProgress
                    Highlight.Default.copy(alpha = 0.15f + 0.35f * p)
                },
                shadow = {
                    val p = hl.pressProgress
                    Shadow(alpha = 0.08f + 0.22f * p)
                },
                innerShadow = {
                    val p = hl.pressProgress
                    InnerShadow(radius = 4f.dp + 8f.dp * p, alpha = 0.10f + 0.25f * p)
                },
                onDrawSurface = { drawRect(surface) }
            )
            .then(hl.modifier)
            .then(hl.gestureModifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val dotColor = if (isDark) Color.White.copy(alpha = 0.82f) else Color(0xFF1A1A2E).copy(alpha = 0.78f)
        Canvas(Modifier.size(20.dp, 28.dp)) {
            val r = 2.5.dp.toPx()
            val cx = size.width / 2f
            val spacing = 9.dp.toPx()
            val startY = (size.height - 2 * spacing) / 2f
            repeat(3) { i ->
                drawCircle(dotColor, r, center = Offset(cx, startY + i * spacing))
            }
        }
    }
}
