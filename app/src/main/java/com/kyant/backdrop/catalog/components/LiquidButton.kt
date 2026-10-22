/*
 * Copyright (c) 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
 * 液态玻璃按钮（v2 — 统一规范）
 * 与 FAB / 底部导航栏统一参数规范：
 * - blur 2dp | lens 12/24 + chromaticAberration
 * - 15% 中性底色 + 常驻光影
 * - 按压时完整 LiquidButton 形变 + 光影增强
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val defaultSurface = if (!isSystemInDarkTheme()) Color(0xFFFAFAFA).copy(alpha = 0.15f)
        else Color(0xFF121212).copy(alpha = 0.15f)

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop, shape = { Capsule() },
                effects = {
                    vibrancy(); blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx(), chromaticAberration = true)
                },
                layerBlock = if (isInteractive) {
                    {
                        val w = size.width; val h = size.height
                        val p = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / h, p)
                        val maxOff = size.minDimension; val initDeriv = 0.05f; val off = interactiveHighlight.offset
                        translationX = maxOff * tanh(initDeriv * off.x / maxOff)
                        translationY = maxOff * tanh(initDeriv * off.y / maxOff)
                        val maxDrag = 4f.dp.toPx() / h; val angle = atan2(off.y, off.x)
                        scaleX = scale + maxDrag * abs(cos(angle) * off.x / size.maxDimension) * (w / h).fastCoerceAtMost(1f)
                        scaleY = scale + maxDrag * abs(sin(angle) * off.y / size.maxDimension) * (h / w).fastCoerceAtMost(1f)
                    }
                } else { null },
                highlight = {
                    val p = interactiveHighlight.pressProgress
                    Highlight.Default.copy(alpha = 0.15f + 0.35f * p)
                },
                shadow = {
                    val p = interactiveHighlight.pressProgress
                    Shadow(alpha = 0.08f + 0.22f * p)
                },
                innerShadow = {
                    val p = interactiveHighlight.pressProgress
                    InnerShadow(radius = 4f.dp + 8f.dp * p, alpha = 0.1f + 0.3f * p)
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) drawRect(surfaceColor)
                    else drawRect(defaultSurface)
                }
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button, onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier.then(interactiveHighlight.modifier).then(interactiveHighlight.gestureModifier)
                } else { Modifier }
            )
            .height(48f.dp).padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

/**
 * 带图标的液态玻璃圆形按钮（v2 — 统一规范）
 */
@Composable
fun LiquidIconButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    LiquidButton(
        onClick = onClick, backdrop = backdrop,
        modifier = modifier.size(48.dp), content = { icon() }
    )
}
