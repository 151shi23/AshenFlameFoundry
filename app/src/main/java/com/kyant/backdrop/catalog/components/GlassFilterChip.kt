/*
 * Copyright (c) 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 液态玻璃功能 Chip（v2 — 统一规范）
 * 36dp 胶囊形液态玻璃按钮，选中时叠加主题色 tint。
 * 适用于筛选标签、分类切换等场景。
 */
@Composable
fun GlassFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val selectedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop, shape = { RoundedCornerShape(50) },
                effects = {
                    vibrancy(); blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx(), chromaticAberration = true)
                },
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
                layerBlock = {
                    val p = interactiveHighlight.pressProgress
                    val scale = lerp(1f, 1.15f, p)
                    scaleX = scale; scaleY = scale
                },
                onDrawSurface = { if (selected) drawRect(selectedTint) },
            )
            .then(interactiveHighlight.modifier).then(interactiveHighlight.gestureModifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .height(36.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1)
    }
}
