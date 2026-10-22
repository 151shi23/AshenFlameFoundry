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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/**
 * 液态玻璃顶部导航栏容器
 *
 * 提供一个胶囊形（Capsule）的液态玻璃容器，内部可放置标题芯片、菜单按钮等内容。
 * 容器通过 [rememberLayerBackdrop] 创建并导出 backdrop，供子组件（如 LiquidTitleChip、
 * LiquidMenuButton）使用，形成玻璃层级关系。
 *
 * 视觉效果：
 * - 模糊（blur 14dp）
 * - 透镜折射（lens 20/22dp + 色差 chromaticAberration）
 * - 活力饱和度增强（vibrancy）
 * - 边缘高光（Highlight 18% alpha）
 * - 外阴影（Shadow radius 20dp, alpha 12%）
 * - 内阴影（InnerShadow radius 5dp, alpha 7%）
 * - 半透明容器底色（深色 0x111122 @ 40% / 浅色 0xFFFFFF @ 40%）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, Shadow, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.backdrops.* （rememberLayerBackdrop, LayerBackdrop）
 * - com.kyant.shapes.* （Capsule）
 *
 * @param backdrop 背景源，通常来自 rememberCombinedBackdrop 或 rememberLayerBackdrop
 * @param isDark 是否深色模式，影响容器底色
 * @param modifier 修饰符
 * @param content 内容 lambda，接收导出的 [LayerBackdrop] 供子组件使用
 *
 * 用法示例：
 * ```
 * LiquidTopBar(
 *     backdrop = combinedBackdrop,
 *     isDark = isDark,
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .padding(horizontal = 16.dp)
 *         .height(56.dp)
 * ) { topBarBackdrop ->
 *     LiquidTitleChip(
 *         backdrop = topBarBackdrop,
 *         title = "首页",
 *         isDark = isDark
 *     )
 *     LiquidMenuButton(
 *         backdrop = topBarBackdrop,
 *         isDark = isDark,
 *         onClick = { showMenu = !showMenu },
 *         modifier = Modifier
 *             .align(Alignment.CenterEnd)
 *             .padding(end = 6.dp)
 *             .size(44.dp)
 *     )
 * }
 * ```
 */
@Composable
fun LiquidTopBar(
    backdrop: Backdrop,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(topBarBackdrop: LayerBackdrop) -> Unit
) {
    val topBarBackdrop = rememberLayerBackdrop()
    val containerSurface = if (isDark) Color(0xFF111122).copy(alpha = 0.40f)
    else Color(0xFFFFFFFF).copy(alpha = 0.40f)

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                exportedBackdrop = topBarBackdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(14f.dp.toPx())
                    lens(20f.dp.toPx(), 22f.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Default.copy(alpha = 0.18f) },
                shadow = { Shadow(radius = 20f.dp, alpha = 0.12f) },
                innerShadow = { InnerShadow(radius = 5f.dp, alpha = 0.07f) },
                onDrawSurface = { drawRect(containerSurface) }
            ),
        contentAlignment = Alignment.Center
    ) {
        content(topBarBackdrop)
    }
}
