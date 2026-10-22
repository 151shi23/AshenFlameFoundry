package com.mineways.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import com.mineways.R

/**
 * 两个屏的入口：
 *  · [MwTabScreen] —— 全部 / 免费 / 白名单功能 / 限制，四个页签共用 [MwHomeScreen]；
 *  · [MwAboutScreen] —— 关于页，沿用同一张照片背景与同一枚顶栏玻璃。
 *
 * 判定一个字节都不做：点哪、能不能点、点了要问谁，全部回给 Java 的 [MwUiHost.run]，
 * 所以云控的档位、停用、mode=ask、激活码与白名单顺序完全沿用原路径。
 */

@Composable
fun MwTabScreen(host: MwUiHost, index: Int, bottomPad: Dp) {
    val spec = remember(host) { host.spec() }
    val meta = remember(host, index) { host.tabMeta(index) }
    var query by rememberSaveable("q$index") { mutableStateOf("") }
    var rail by rememberSaveable("r$index") { mutableStateOf(0) }
    val blocks = remember(host, index, rail) { host.blocksFiltered(index, rail) }

    MwHomeScreen(
            host = host,
            index = index,
            spec = spec,
            meta = meta,
            blocks = blocks,
            query = query,
            onQuery = { query = it },
            rail = rail,
            onRail = { rail = it },
            bottomPad = bottomPad,
            onCell = { cell ->
                if (cell.action >= 0) {
                    host.run(cell.action, cell.item, cell.url)
                } else {
                    cell.item?.let { host.run(MwUiHost.ACTION_OPEN_ITEM, it, null) }
                }
            },
            onAsk = { cell ->
                cell.item?.let { host.run(MwUiHost.ACTION_AI_ASK, it, cell.title) }
            })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MwAboutScreen(host: MwUiHost, bottomPad: Dp) {
    val spec = remember(host) { host.spec() }
    val meta = remember(host) { host.aboutMeta() }
    val blocks = remember(host) { host.aboutBlocks() }
    val tier = rememberMwTier(LocalContext.current)
    val tilt = rememberMwTilt(spec.motion)
    val listState = rememberLazyListState()
    val navDp by MwInsets.bottomNavDp
    val navPad = if (navDp > 0) navDp.dp else bottomPad
    val played = remember { HashSet<Int>() }
    val context = LocalContext.current
    val photoVer = remember { com.mineways.cloud.CloudAssets.backgroundVersion() }

    val sceneBd = rememberLayerBackdrop()
    val contentBd = rememberLayerBackdrop()
    val chromeBd = if (tier == MwTier.HIGH) {
        rememberCombinedBackdrop(sceneBd, contentBd)
    } else {
        sceneBd
    }
    val contentModifier = if (tier == MwTier.HIGH) {
        Modifier.layerBackdrop(contentBd)
    } else {
        Modifier
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 背景层：云端背景图优先，包内星空兜底；暗幕 + 陀螺仪高光都画在这一层
        Box(Modifier.fillMaxSize().layerBackdrop(sceneBd)) {
            if (spec.flow) {
                val cloudBg = remember(photoVer) {
                    com.mineways.cloud.CloudAssets.backgroundBitmap(context)
                }
                if (cloudBg != null) {
                    Image(
                            bitmap = cloudBg.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(
                                    ColorMatrix().apply { setToScale(0.45f, 0.45f, 0.45f, 1f) }),
                            modifier = Modifier.fillMaxSize())
                } else {
                    Image(
                            painter = painterResource(R.drawable.mw_bg),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(
                                    ColorMatrix().apply { setToScale(0.45f, 0.45f, 0.45f, 1f) }),
                            modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color.Black.copy(alpha = 0.35f),
                        Color.Black.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.45f)))))
                MwGyroGlow(tilt, spec.motion)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color(0xFF07080D), Color(0xFF0C0E16), Color(0xFF05060A)))))
            }
        }

        // 内容层：关掉安卓 12+ 的 overscroll stretch（玻璃层叠在内容上时，系统拉伸会把整页拉成一大团）
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                        .then(contentModifier),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                        top = 84.dp, bottom = navPad + 26.dp),
        ) {
            item(key = "about-intro") {
                Column(Modifier.then(rememberMwEnter(0, spec.motion, played))) {
                    BasicText(meta.hint, style = MwType.body.copy(fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.72f), shadow = textShadow()),
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
            }
            item(key = "about-status") {
                Column(Modifier.then(rememberMwEnter(1, spec.motion, played))) {
                    MwGlassPanel(sceneBd, spec, tier, Modifier.fillMaxWidth(),
                            quality = MwGlassQuality.FULL, radius = spec.radius) {
                        Column(Modifier.fillMaxWidth()) {
                            MwPanelHeader("云端配置", null, null, null, spec.accent)
                            Column(Modifier.fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BasicText(meta.stats, style = MwType.caption.copy(fontSize = 12.sp),
                                        modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            itemsIndexed(blocks, key = { i, b -> "a" + i + "-" + (b.title ?: "") }) { i, block ->
                val enter = if (i < 3) rememberMwEnter(2 + i, spec.motion, played) else Modifier
                Column(Modifier.then(enter)) {
                    if (block.cells.isNotEmpty()) {
                        MwGlassPanel(sceneBd, spec, tier, Modifier.fillMaxWidth(),
                                quality = MwGlassQuality.LITE, radius = spec.radius) {
                            Column(Modifier.fillMaxWidth()) {
                                MwPanelHeader(block.title ?: "", block.subtitle, block.trailing,
                                        null, spec.accent)
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    for (j in block.cells.indices) {
                                        if (j > 0) {
                                            MwDivider(inset = if (block.cells[j].iconRes != 0) 64.dp else 14.dp)
                                        }
                                        MwAboutCell(block.cells[j], host, spec, tier, sceneBd)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            item(key = "about-footer") {
                Spacer(Modifier.height(8.dp))
                BasicText("MinewaysMobile · 界面由云控 ui_* 开关控制，出问题可在「关于」里回滚",
                        style = MwType.caption.copy(textAlign = TextAlign.Center,
                                color = Color.White.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            }
        }
        }

        // 悬浮玻璃顶栏（折射照片与滚动内容）
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
            MwTopBar(
                    title = "关于",
                    spec = spec, tier = tier, bd = chromeBd,
                    actionIcon = Icons.Rounded.Refresh,
                    onAction = { host.run(MwUiHost.ACTION_REFRESH, null, null) })
        }

        // 统一登录面板：与顶栏同一层 backdrop，所以它折射的是背后真实的页面，不是半透明黑块
        MwLoginSheet(host, spec, tier, chromeBd, tilt)
    }
}
/** 关于页的一行：整段说明 / 工具行 / 带按钮的行 / 「标签—值」行 / 纯文字行。 */
@Composable
private fun MwAboutCell(cell: MwCell, host: MwUiHost, spec: MwTokens.Spec,
        tier: MwTier, bd: Backdrop?) {
    val click: (() -> Unit)? = if (cell.action >= 0) {
        { host.run(cell.action, cell.item, cell.url) }
    } else {
        null
    }
    val paragraph = cell.paragraph
    val value = cell.value
    val actionLabel = cell.actionLabel

    when {
        paragraph != null -> Column(Modifier.fillMaxWidth()
                .then(if (click != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = click) else Modifier)
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (cell.title.isNotEmpty()) {
                BasicText(cell.title, style = MwType.headline.copy(fontSize = 14.sp))
            }
            BasicText(paragraph, style = MwType.body.copy(fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = if (cell.title.isNotEmpty()) 5.dp else 0.dp))
        }

        // 关于页的收录清单不需要「问 AI」，所以 onAsk 用默认空实现；onCell 必须显式命名传，
        // 否则尾随 lambda 会绑到最后那个参数（onAsk），编译器就报 onCell 缺失。
        cell.iconRes != 0 -> MwToolRow(cell, onCell = { c ->
            if (c.action >= 0) {
                host.run(c.action, c.item, c.url)
            } else {
                c.item?.let { host.run(MwUiHost.ACTION_OPEN_ITEM, it, null) }
            }
        })

        actionLabel != null -> Row(Modifier.fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText(cell.title, style = MwType.headline.copy(fontSize = 14.sp))
                if (cell.sub.isNotEmpty()) {
                    BasicText(cell.sub, style = MwType.caption,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp))
                }
            }
            MwGlassButton(actionLabel, cell.primary, spec, tier, bd) { click?.invoke() }
        }

        value != null -> Row(Modifier.fillMaxWidth()
                .then(if (click != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = click) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(cell.title, style = MwType.headline.copy(fontSize = 13.5.sp),
                    maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            BasicText(value, style = MwType.body.copy(fontSize = 12.sp,
                    textAlign = TextAlign.End), maxLines = 5)
        }

        else -> Column(Modifier.fillMaxWidth()
                .then(if (click != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = click) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicText(cell.title, style = MwType.headline.copy(fontSize = 14.sp))
            if (cell.sub.isNotEmpty()) {
                BasicText(cell.sub, style = MwType.caption,
                        modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}


