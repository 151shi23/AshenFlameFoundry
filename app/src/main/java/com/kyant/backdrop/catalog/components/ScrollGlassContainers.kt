package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/**
 * 滚动玻璃容器
 *
 * 参考官方 ScrollContainerContent 和 LazyScrollContainerContent 实现。
 *
 * @param backdrop 背景源
 * @param modifier 修饰符
 * @param content 滚动内容
 */
@Composable
fun ScrollGlassContainer(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
    }
}

/**
 * 懒加载滚动玻璃列表
 *
 * 参考官方 LazyScrollContainerContent 实现。
 *
 * @param backdrop 背景源
 * @param modifier 修饰符
 * @param itemsCount 列表项数量
 * @param content 列表项内容
 */
@Composable
fun LazyScrollGlassContainer(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    itemsCount: Int = 20,
    content: @Composable (index: Int) -> Unit
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16f.dp),
        verticalArrangement = Arrangement.spacedBy(16f.dp)
    ) {
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.systemBars))
        }
        items(itemsCount) { index ->
            Box(
                Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(32f.dp) },
                        effects = {
                            vibrancy()
                            lens(16f.dp.toPx(), 32f.dp.toPx())
                        }
                    )
                    .height(160f.dp)
                    .fillMaxWidth()
            ) {
                content(index)
            }
        }
        item {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }
}
