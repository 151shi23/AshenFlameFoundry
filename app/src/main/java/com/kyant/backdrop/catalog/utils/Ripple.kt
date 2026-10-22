/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kyant.backdrop.catalog.utils

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.unit.Dp

/**
 * 涟漪（Ripple）效果工具
 *
 * 提供基于 [IndicationNodeFactory] 的涟漪效果实现，源自 AOSP
 * （Android Open Source Project）。用于在可点击组件上显示水波纹反馈。
 *
 * 功能：
 * - 支持有界（bounded）和无界（unbounded）涟漪
 * - 支持自定义半径和颜色
 * - 支持动态颜色（[ColorProducer]）
 * - 主题感知：通过 [DelegatingThemeAwareRippleNode] 自动适应主题
 *
 * 默认涟漪透明度（[RippleDefaults.RippleAlpha]）：
 * - pressed: 0.10
 * - focused: 0.10
 * - dragged: 0.16
 * - hovered: 0.08
 *
 * 依赖：
 * - androidx.compose.foundation.IndicationNodeFactory
 * - androidx.compose.material.ripple.* （RippleAlpha, createRippleModifierNode）
 * - androidx.compose.ui.node.* （DelegatableNode, DelegatingNode, ObserverModifierNode 等）
 *
 * 用法示例：
 * ```
 * Box(
 *     modifier = Modifier
 *         .clickable(
 *             interactionSource = remember { MutableInteractionSource() },
 *             indication = ripple()
 *         ) { /* handleClick() */ }
 * )
 * ```
 *
 * 自定义颜色和半径：
 * ```
 * ripple(color = Color.Blue, radius = 24.dp)
 * ```
 */

@Stable
fun ripple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified
): IndicationNodeFactory {
    return if (radius == Dp.Unspecified && color == Color.Unspecified) {
        if (bounded) DefaultBoundedRipple else DefaultUnboundedRipple
    } else {
        RippleNodeFactory(bounded, radius, color)
    }
}

@Stable
fun ripple(
    color: ColorProducer,
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified
): IndicationNodeFactory {
    return RippleNodeFactory(bounded, radius, color)
}

object RippleDefaults {

    val RippleAlpha: RippleAlpha =
        RippleAlpha(
            pressedAlpha = 0.1f,
            focusedAlpha = 0.1f,
            draggedAlpha = 0.16f,
            hoveredAlpha = 0.08f
        )
}

@Immutable
class RippleConfiguration(
    val color: Color = Color.Unspecified,
    val rippleAlpha: RippleAlpha? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RippleConfiguration) return false

        if (color != other.color) return false
        if (rippleAlpha != other.rippleAlpha) return false

        return true
    }

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + (rippleAlpha?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "RippleConfiguration(color=$color, rippleAlpha=$rippleAlpha)"
    }
}

@Stable
private class RippleNodeFactory
private constructor(
    private val bounded: Boolean,
    private val radius: Dp,
    private val colorProducer: ColorProducer?,
    private val color: Color
) : IndicationNodeFactory {

    constructor(
        bounded: Boolean,
        radius: Dp,
        colorProducer: ColorProducer
    ) : this(bounded, radius, colorProducer, Color.Unspecified)

    constructor(bounded: Boolean, radius: Dp, color: Color) : this(bounded, radius, null, color)

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        val colorProducer = colorProducer ?: ColorProducer { color }
        return DelegatingThemeAwareRippleNode(interactionSource, bounded, radius, colorProducer)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RippleNodeFactory) return false

        if (bounded != other.bounded) return false
        if (radius != other.radius) return false
        if (colorProducer != other.colorProducer) return false
        return color == other.color
    }

    override fun hashCode(): Int {
        var result = bounded.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + colorProducer.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }
}

private class DelegatingThemeAwareRippleNode(
    private val interactionSource: InteractionSource,
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: ColorProducer,
) : DelegatingNode(), CompositionLocalConsumerModifierNode, ObserverModifierNode {

    private var rippleNode: DelegatableNode? = null

    override fun onAttach() {
        updateConfiguration()
    }

    override fun onObservedReadsChanged() {
        updateConfiguration()
    }

    private fun updateConfiguration() {
        observeReads {
            if (rippleNode == null) {
                attachNewRipple()
            }
        }
    }

    private fun attachNewRipple() {
        rippleNode =
            delegate(
                createRippleModifierNode(
                    interactionSource = interactionSource,
                    bounded = bounded,
                    radius = radius,
                    color = color,
                    rippleAlpha = { RippleDefaults.RippleAlpha }
                )
            )
    }
}

private val DefaultBoundedRipple =
    RippleNodeFactory(bounded = true, radius = Dp.Unspecified, color = Color.Unspecified)
private val DefaultUnboundedRipple =
    RippleNodeFactory(bounded = false, radius = Dp.Unspecified, color = Color.Unspecified)
