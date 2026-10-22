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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.tanh

/**
 * 液态玻璃搜索框
 *
 * 胶囊形搜索输入框，使用 [BasicTextField] 显示搜索文本，
 * 内置搜索图标（⌕）、占位文字和清除按钮（✕）。文本框启用系统交互
 * （长按复制/粘贴、光标定位、粘贴），获焦时自动抑制系统键盘并触发
 * [onToggleKeyboard] 回调显示自定义键盘。
 *
 * 视觉效果：
 * - 模糊（blur 10dp）
 * - 透镜折射（lens 20/22dp + 色差 chromaticAberration）
 * - 活力饱和度增强（vibrancy）
 * - 按压位移：tanh 阻尼函数控制
 * - 均匀缩放：lerp(1f, 1f + 2dp/height, pressProgress)
 * - 动态高光（0.12 + 0.28 * pressProgress）
 * - 动态阴影（radius 16dp, alpha 0.07 + 0.15 * pressProgress）
 * - 动态内阴影（radius 4+5dp, alpha 0.08 + 0.18 按压递增）
 *
 * 内部布局：
 * - 左侧搜索图标 ⌕（20sp，placeholderColor @ 65%）
 * - 中间文本区域（BasicTextField + 占位文字"搜索…"，获焦时抑制系统键盘）
 * - 右侧清除按钮 ✕（仅 value.text 非空时显示）
 *
 * 依赖：
 * - com.kyant.backdrop.* （drawBackdrop, Highlight, Shadow, InnerShadow, lens, blur, vibrancy）
 * - com.kyant.backdrop.catalog.utils.InteractiveHighlight
 * - com.kyant.shapes.* （Capsule）
 * - androidx.compose.foundation.text.BasicTextField
 *
 * @param backdrop 背景源，通常来自 rememberCombinedBackdrop
 * @param value 当前文本值（包含光标选区）
 * @param onValueChange 文本值变更回调
 * @param isDark 是否深色模式，影响底色和文字颜色
 * @param onToggleKeyboard 点击搜索框时切换键盘的回调
 * @param onTextLayout 文本布局完成回调（用于光标放大镜等场景）
 * @param textFieldModifier 应用到内部 BasicTextField 的额外修饰符（如 onGloballyPositioned）
 * @param modifier 修饰符
 *
 * 用法示例：
 * ```
 * LiquidSearchBar(
 *     backdrop = combinedBackdrop,
 *     value = textFieldValue,
 *     onValueChange = { textFieldValue = it },
 *     isDark = isDark,
 *     onToggleKeyboard = {
 *         keyboardController?.hide()
 *         showKeyboard = !showKeyboard
 *     },
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .padding(horizontal = 16.dp)
 *         .height(48.dp)
 * )
 * ```
 */
@Composable
fun LiquidSearchBar(
    backdrop: Backdrop,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isDark: Boolean,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    textFieldModifier: Modifier = Modifier
) {
    val animScope = rememberCoroutineScope()
    val hl = remember(animScope) { InteractiveHighlight(animScope) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val surface = if (isDark) Color(0xFF0D0D1A).copy(alpha = 0.35f)
    else Color(0xFFFFFFFF).copy(alpha = 0.35f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1A2E)
    val placeholderColor = if (isDark) Color.White.copy(alpha = 0.40f)
    else Color(0xFF1A1A2E).copy(alpha = 0.40f)

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(10f.dp.toPx())
                    lens(20f.dp.toPx(), 22f.dp.toPx(), chromaticAberration = true)
                },
                layerBlock = {
                    val p = hl.pressProgress
                    val sc = lerp(1f, 1f + 2f.dp.toPx() / size.height, p)
                    val maxOff = size.minDimension
                    val off = hl.offset
                    translationX = maxOff * tanh(0.05f * off.x / maxOff)
                    translationY = maxOff * tanh(0.05f * off.y / maxOff)
                    scaleX = sc
                    scaleY = sc
                },
                highlight = {
                    val p = hl.pressProgress
                    Highlight.Default.copy(alpha = 0.12f + 0.28f * p)
                },
                shadow = {
                    val p = hl.pressProgress
                    Shadow(radius = 16f.dp, alpha = 0.07f + 0.15f * p)
                },
                innerShadow = {
                    val p = hl.pressProgress
                    InnerShadow(radius = 4f.dp + 5f.dp * p, alpha = 0.08f + 0.18f * p)
                },
                onDrawSurface = { drawRect(surface) }
            )
            .then(hl.modifier)
            .then(hl.gestureModifier)
            .clickable(interactionSource = null, indication = null, onClick = onToggleKeyboard),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "⌕",
                style = TextStyle(color = placeholderColor.copy(alpha = 0.65f), fontSize = 20.sp)
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    BasicText(
                        "搜索…",
                        style = TextStyle(color = placeholderColor, fontSize = 15.sp)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = true,
                    readOnly = false,
                    onTextLayout = onTextLayout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(textFieldModifier)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                keyboardController?.hide()
                                onToggleKeyboard()
                            }
                        },
                    textStyle = TextStyle(color = textColor, fontSize = 15.sp),
                    singleLine = true
                )
            }
            if (value.text.isNotEmpty()) {
                BasicText(
                    "✕",
                    modifier = Modifier.clickable {
                        onValueChange(TextFieldValue(""))
                    },
                    style = TextStyle(color = placeholderColor, fontSize = 14.sp)
                )
            }
        }
    }
}
