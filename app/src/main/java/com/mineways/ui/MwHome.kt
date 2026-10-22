package com.mineways.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Foundation
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow as TextShadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidMenuButton
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.catalog.components.LiquidTitleChip
import com.kyant.backdrop.catalog.components.LiquidTopBar
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.mineways.AiPolicy
import com.mineways.AiTools
import com.mineways.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 首页与其它三个页签。
 *
 * 版式与 backdrop 层级照 liquid-glass 技能的 **完整应用框架**
 * （`templates/glass-app-framework.kt`）：
 *
 * ```
 * 真实照片（layerBackdrop → sceneBd）
 *   └─ 页面内容（layerBackdrop → contentBd）
 *        └─ combinedBackdrop(sceneBd, contentBd) → 顶栏 / 搜索条
 * ```
 *
 * 为什么上一版"不是真玻璃"：玻璃背后是自己画的渐变 + 点阵，没有真实画面可折。现在背后
 * 是一张真实的星空雪山照片 —— 星星、山脊、银河的颗粒就是折射的显影剂；顶栏和搜索条再用
 * combinedBackdrop，把**正在滚动的列表内容**也折进去，内容从玻璃下面穿过去时边缘会真的弯。
 *
 * 反 AI 味：删掉四团彩色光斑、糖果色图标底托、3D 方块插画、"精选"大卡。图标统一白色，
 * 颜色交给照片；版式回到"顶栏 + 搜索 + 分组列表"。
 *
 * 性能：一屏 6~9 个玻璃层；列表行不是玻璃；照片只画一次。BALANCED/FLAT 不做 contentBd
 * 捕获（那是一层全屏录制），顶栏只折射照片。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MwHomeScreen(
        host: MwUiHost,
        index: Int,
        spec: MwTokens.Spec,
        meta: MwTabMeta,
        blocks: List<MwBlock>,
        query: String,
        onQuery: (String) -> Unit,
        rail: Int,
        onRail: (Int) -> Unit,
        bottomPad: Dp,
        onCell: (MwCell) -> Unit,
        onAsk: (MwCell) -> Unit,
) {
    val context = LocalContext.current
    val tier = rememberMwTier(context)
    val tilt = rememberMwTilt(spec.motion)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navDp by MwInsets.bottomNavDp
    val navPad = if (navDp > 0) navDp.dp else bottomPad
    // 云端背景图版本：BG 落地/删除后 recreateUi 重建界面，这里只在版本变化时重新解码
    val photoVer = remember { com.mineways.cloud.CloudAssets.backgroundVersion() }
    // 展示栏视频（VIDEO add，云控下发）；序号顺延一位给 quick 之后的入场
    val uiVideos = remember { com.mineways.cloud.CloudCatalog.uiVideos() }
    // 入场只播一次：滚回来重组的 item 不再重播（否则就成了"每次滚动都动画"）
    val played = remember { HashSet<Int>() }

    // 三个点 → 液态玻璃水波纹 + AI 输入悬浮框。
    // 云控 ui_ai=0 时退回「关于」页（那条路以前断在 MainActivity.run 的 default 分支，已补）。
    val aiGate = remember { com.mineways.cloud.CloudCatalog.gate() }
    // 放行 = 白名单 / 特定机型 / 特定系统 任一命中；环境按人群算（白名单可单独走 dev）
    val aiAccess = remember(aiGate) {
        com.mineways.AiAccess.refreshAndAllowed(context) to com.mineways.ApiEnv.isDev()
    }
    val aiEnabled = aiAccess.first
    val aiDev = aiAccess.second
    val aiRipple = remember(aiGate) { aiGate.flag("ui_ai_ripple", "") != "0" }
    val aiHint = remember(aiGate) {
        aiGate.flag("ui_ai_hint", "").ifBlank { "问我任何问题，比如：帮我找一个转换工具…" }
    }
    // 会话状态全站共享（首页与各功能页同一条上下文、同一份 chat.json）
    val aiChat = MwAi.chat
    // 全局开关：MainActivity 的返回键要靠它先收面板（而不是直接切页/退出）
    var aiOpen by MwAiBus.open
    // 面板展开时把底部导航栏藏掉 —— 它是后加进 FrameLayout 的，会压住全屏输入条
    LaunchedEffect(aiOpen, aiEnabled) {
        if (aiEnabled) {
            host.run(MwUiHost.ACTION_AI_OPEN, null, if (aiOpen) "1" else "0")
        }
    }
    /**
     * 长按卡片「问 AI」是否生效：人群放行 + 云控 `ai_entries_off` 没点名 card。
     * 关掉时长按就是没有这个手势（不是画了个按钮再禁用）。
     */
    val askCell: (MwCell) -> Unit =
            if (aiEnabled && AiPolicy.entryAllowed(AiTools.FLAGS, AiPolicy.E_CARD)) {
                onAsk
            } else {
                {}
            }
    /** 搜索没搜到时的「问 AI」兜底（`ai_entries_off=search` 可关）。 */
    val askSearch: (() -> Unit)? =
            if (aiEnabled && AiPolicy.entryAllowed(AiTools.FLAGS, AiPolicy.E_SEARCH)) {
                {
                    MwAiBus.prefill.value = if (query.isBlank()) {
                        "帮我从这些功能里挑：我还说不清要哪一个"
                    } else {
                        "首页里搜不到「$query」，有没有能实现这个目的的功能？"
                    }
                    aiOpen = true
                }
            } else {
                null
            }

    val shown = remember(blocks, query) { filterBlocks(blocks, query) }
    val allCells = remember(blocks) { blocks.flatMap { it.cells } }
    val shownCells = remember(shown) { shown.flatMap { it.cells } }

    val quick = remember(allCells) {
        val out = ArrayList<MwCell>()
        val seen = HashSet<String>()
        for (c in allCells) {
            if (c.iconRes == 0 || !seen.add(c.title)) {
                continue
            }
            out.add(c)
            if (out.size >= 8) {
                break
            }
        }
        out
    }

    val showQuick = query.isBlank() && quick.isNotEmpty()
    val showCats = query.isBlank() && meta.categories.size > 1
    val toolsIndex = 1 + (if (showQuick) 1 else 0) + (if (showCats) 1 else 0)

    val sceneBd = rememberLayerBackdrop()
    val contentBd = rememberLayerBackdrop()
    // 只有 HIGH 档才把滚动内容折进顶栏：contentBd 是一层全屏录制，低端机赔不起。
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
        // ① 真实照片 + 压暗。玻璃从这里取折射。
        //    ui_flow=0 → 退回纯深色静底（不画照片），这是低端机/嫌花时的逃生门。
        Box(Modifier.fillMaxSize().layerBackdrop(sceneBd)) {
            if (spec.flow) {
                // 云端下发的背景图优先（BG set，sha256 校验过的那张），没有 = 包内星空
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
                        // 底部别压太死：导航栏就在这条带上取折射
                        Color.Black.copy(alpha = 0.45f)))))
                MwGyroGlow(tilt, spec.motion)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color(0xFF07080D), Color(0xFF0C0E16), Color(0xFF05060A)))))
            }
        }

        // ② 内容层：从顶栏下面滚过去
        // 关掉安卓 12+ 的 overscroll stretch：玻璃层叠在内容上时，
        // 系统的拉伸动画会把整页（连同背板）拉成一大团，观感极差
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                        .then(contentModifier),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                        top = 142.dp, bottom = navPad + 26.dp),
        ) {
            item(key = "intro") {
                Column(Modifier.then(rememberMwEnter(0, spec.motion, played))) {
                    MwGlassPanel(sceneBd, spec, tier, Modifier.fillMaxWidth(),
                            quality = MwGlassQuality.LITE, radius = spec.radius) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(8.dp).clip(Capsule())
                                    .background(spec.accent.copy(alpha = 0.9f)))
                            BasicText(
                                    meta.hint.ifEmpty { meta.stats },
                                    style = MwType.body.copy(fontSize = 12.5.sp,
                                            color = Color.White.copy(alpha = 0.78f)),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // 展示栏：云控下发的内嵌视频（B 站/抖音官方播放器），单条或多条轮播
            if (uiVideos.isNotEmpty()) {
                item(key = "video") {
                    Column(Modifier.then(rememberMwEnter(1, spec.motion, played))) {
                        MwVideoPanel(uiVideos, spec, tier, sceneBd, context)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            if (showQuick) {
                item(key = "quick") {
                    Column(Modifier.then(rememberMwEnter(2, spec.motion, played))) {
                        MwQuickPanel(quick, spec, tier, sceneBd, onCell, askCell,
                                title = "快捷访问", trailing = "更多",
                                onTrailing = {
                                    scope.launch { listState.animateScrollToItem(toolsIndex) }
                                }, accent = spec.accent)
                    }
                }
            }
            if (showCats) {
                item(key = "cats") {
                    Column(Modifier.then(rememberMwEnter(3, spec.motion, played))) {
                        MwCategoryPanel(meta.categories, rail, spec, tier, sceneBd,
                                onSelect = { i ->
                                    onRail(i)
                                    scope.launch { listState.animateScrollToItem(toolsIndex) }
                                },
                                title = "分类", accent = spec.accent)
                    }
                }
            }
            item(key = "tools-title") {
                Column(Modifier.then(rememberMwEnter(if (uiVideos.isEmpty()) 3 else 4, spec.motion, played))) {
                    MwGlassPanel(sceneBd, spec, tier, Modifier.fillMaxWidth(),
                            quality = MwGlassQuality.LITE, radius = spec.radius) {
                        Column(Modifier.fillMaxWidth()) {
                        MwPanelHeader(
                                title = when {
                                    query.isNotBlank() -> "搜索结果"
                                    rail > 0 && rail < meta.categories.size -> meta.categories[rail]
                                    index == 0 -> "全部工具"
                                    else -> meta.title
                                },
                                subtitle = if (query.isNotBlank()) "匹配到 ${shownCells.size} 项" else null,
                                trailing = if (rail > 0 || query.isNotBlank()) "清除"
                                else "${shownCells.size} 项",
                                onTrailing = if (rail > 0 || query.isNotBlank()) {
                                    {
                                        onRail(0)
                                        onQuery("")
                                    }
                                } else {
                                    null
                                },
                                accent = spec.accent)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") { MwEmptyPanel(query, askSearch) }
            } else {
                itemsIndexed(shown, key = { i, b -> "g" + i + "-" + (b.title ?: "") }) { i, block ->
                    // 只有前 3 组做入场：后面的组是滚出来的，做了就成"每次滚动都重播"
                    val enter = if (i < 3) rememberMwEnter((if (uiVideos.isEmpty()) 4 else 5) + i, spec.motion, played) else Modifier
                    Column(Modifier.then(enter)) {
                        MwGroupPanel(block, spec, tier, sceneBd, onCell, askCell)
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
            item(key = "footer") {
                Spacer(Modifier.height(6.dp))
                MwGlassPanel(sceneBd, spec, tier, Modifier.fillMaxWidth(),
                        quality = MwGlassQuality.LITE, radius = spec.radius) {
                    BasicText("共 ${shownCells.size} 项 · 云端配置在启动时自动同步",
                            style = MwType.caption.copy(textAlign = TextAlign.Center,
                                    color = Color.White.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        }

        // ③ 悬浮玻璃顶栏 + 搜索：折射照片与滚动内容
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
            MwTopBar(
                    title = if (index == 0) "工具箱" else meta.title,
                    spec = spec, tier = tier, bd = chromeBd,
                    actionIcon = Icons.Rounded.MoreHoriz,
                    onAction = {
                        if (aiEnabled) {
                            aiOpen = true
                        } else {
                            host.run(MwUiHost.ACTION_ABOUT, null, null)
                        }
                    })
            Spacer(Modifier.height(10.dp))
            MwSearchField(query, onQuery,
                    meta.searchHint.ifEmpty { "搜索功能、站点、分类" },
                    spec, tier, chromeBd)
        }

        // ④ AI 入口：暗场 + 液态玻璃水波纹 + 悬浮输入框（画在最上层，压住顶栏与列表）
        if (aiEnabled) {
            MwAiOverlay(
                    visible = aiOpen,
                    onDismiss = { aiOpen = false },
                    backdrop = chromeBd,
                    spec = spec,
                    tier = tier,
                    hint = aiHint,
                    ripple = aiRipple && spec.motion,
                    dev = aiDev,
                    chat = aiChat)
        }
    }
}

/**
 * 顶栏：三种形态，确保云控开关都真的生效。
 *  · 玻璃开 + 动效开：技能的 LiquidTopBar + LiquidTitleChip（+ LiquidMenuButton）
 *  · 玻璃开 + ui_motion=0：静态玻璃胶囊，不做按压缩放
 *  · ui_glass=0 或 FLAT 档：半透明暗底胶囊 + 1px 描边
 */
@Composable
internal fun MwTopBar(
        title: String,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
        actionIcon: ImageVector,
        onAction: () -> Unit,
) {
    val glassOn = spec.glass && tier != MwTier.FLAT && MwGlass.supported()
    if (glassOn && spec.motion) {
        LiquidTopBar(
                backdrop = bd,
                isDark = true,
                modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { topBarBackdrop ->
            LiquidTitleChip(
                    backdrop = topBarBackdrop,
                    title = title,
                    isDark = true,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
            if (actionIcon == Icons.Rounded.MoreHoriz) {
                LiquidMenuButton(
                        backdrop = topBarBackdrop,
                        isDark = true,
                        onClick = onAction,
                        // 这里原来没给尺寸，Box 只裹住 20×28dp 的三个点 —— 手指根本点不中，
                        // 这也是「点三个点没反应」的一半原因。补到 44dp 的标准触控热区。
                        modifier = Modifier.align(Alignment.CenterEnd)
                                .padding(end = 6.dp).size(44.dp))
            } else {
                MwGlassPanel(topBarBackdrop, spec, tier,
                        Modifier.size(38.dp).align(Alignment.CenterEnd).padding(end = 6.dp),
                        quality = MwGlassQuality.FULL, radius = 100.dp, onClick = onAction) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        IconVec(actionIcon, Color.White.copy(alpha = 0.92f), 17.dp)
                    }
                }
            }
        }
        return
    }
    if (glassOn) {
        // ui_motion=0：玻璃还在，但不做任何按压动画
        MwGlassPanel(bd, spec, tier, Modifier.fillMaxWidth().height(56.dp),
                quality = MwGlassQuality.FULL, radius = 100.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, style = MwType.headline.copy(fontSize = 15.sp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(38.dp).clip(Capsule()).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onAction),
                        contentAlignment = Alignment.Center) {
                    IconVec(actionIcon, Color.White.copy(alpha = 0.9f), 18.dp)
                }
            }
        }
        return
    }
    // ui_glass=0 / FLAT：不画 RenderEffect，结构与上面一致
    val shape = Capsule()
    Row(Modifier.fillMaxWidth().height(56.dp).clip(shape)
            .background(Color(0xFF0A0C12).copy(alpha = 0.74f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = MwType.headline.copy(fontSize = 15.sp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(38.dp).clip(Capsule()).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onAction),
                contentAlignment = Alignment.Center) {
            IconVec(actionIcon, Color.White.copy(alpha = 0.9f), 18.dp)
        }
    }
}

// ================================================================ 搜索

@Composable
private fun MwSearchField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "search-ring")
    Box(Modifier.fillMaxWidth().height(48.dp)
            .border(1.dp, spec.accent.copy(alpha = 0.55f * ring), Capsule())) {
    MwGlassPanel(bd, spec, tier, Modifier.fillMaxSize(),
            quality = MwGlassQuality.FULL, radius = 100.dp, chromatic = true) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconVec(Icons.Rounded.Search, Color.White.copy(alpha = 0.55f), 18.dp)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = MwType.body.copy(fontSize = 13.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MwType.headline.copy(fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        cursorBrush = SolidColor(spec.accent),
                        modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { focused = it.isFocused })
            }
            if (value.isNotEmpty()) {
                Box(Modifier.size(26.dp).clip(Capsule()).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null) { onValueChange("") },
                        contentAlignment = Alignment.Center) {
                    IconVec(Icons.Rounded.Close, Color.White.copy(alpha = 0.6f), 14.dp)
                }
            }
        }
    }
    }
}

// ================================================================ 快捷访问 / 分类

@Composable
private fun MwQuickPanel(
        cells: List<MwCell>,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
        onCell: (MwCell) -> Unit,
        onAsk: (MwCell) -> Unit,
        title: String,
        trailing: String? = null,
        onTrailing: (() -> Unit)? = null,
        accent: Color = MwTokens.accentDefault,
) {
    MwGlassPanel(bd, spec, tier, Modifier.fillMaxWidth(),
            quality = MwGlassQuality.FULL, radius = spec.radius) {
        // Box 会把所有子元素叠在一起：标题和内容必须包进同一个 Column
        Column(Modifier.fillMaxWidth()) {
        MwPanelHeader(title, null, trailing, onTrailing, accent)
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp)) {
            var i = 0
            while (i < cells.size) {
                Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in 0..3) {
                        val c = cells.getOrNull(i + k)
                        if (c != null) {
                            MwQuickTile(c, spec.motion, Modifier.weight(1f),
                                    onClick = { onCell(c) },
                                    onAsk = if (c.item == null) null else {
                                        { onAsk(c) }
                                    })
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                i += 4
            }
        }
    }
    }
}

/** 快捷格：白色图标 + 一层很薄的底托。不再用糖果色 —— 颜色交给背后的照片。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MwQuickTile(cell: MwCell, motion: Boolean, modifier: Modifier = Modifier,
        onAsk: (() -> Unit)? = null, onClick: () -> Unit) {
    // 技能 InteractiveHighlight：按压 1+4dp/h 放大、tanh 跟手、方向性拉伸
    val scope = rememberCoroutineScope()
    val hl = remember(scope, motion) { if (motion) InteractiveHighlight(scope) else null }
    Column(modifier.heightIn(min = 82.dp)
            .graphicsLayer {
                val h = size.height.coerceAtLeast(1f)
                val w = size.width.coerceAtLeast(1f)
                val p = hl?.pressProgress ?: 0f
                val base = lerp(1f, 1f + 4.dp.toPx() / h, p)
                val maxOff = size.minDimension.coerceAtLeast(1f)
                val off = hl?.offset ?: Offset.Zero
                translationX = maxOff * tanh(0.05f * off.x / maxOff)
                translationY = maxOff * tanh(0.05f * off.y / maxOff)
                val maxDrag = 4.dp.toPx() / h
                val angle = atan2(off.y, off.x)
                scaleX = base + maxDrag * abs(cos(angle) * off.x / size.maxDimension) *
                        (w / h).fastCoerceAtMost(1f)
                scaleY = base + maxDrag * abs(sin(angle) * off.y / size.maxDimension) *
                        (h / w).fastCoerceAtMost(1f)
            }
            .clip(RoundedRectangle(16.dp))
            .drawBehind {
                val p = hl?.pressProgress ?: 0f
                if (p > 0f) {
                    drawRect(Color.White.copy(alpha = 0.07f * p), size = size)
                }
            }
            .combinedClickable(interactionSource = null, indication = null,
                    onClick = onClick, onLongClick = onAsk)
            .then(if (hl != null) Modifier.then(hl.modifier).then(hl.gestureModifier) else Modifier)
            .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(RoundedRectangle(14.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedRectangle(14.dp)),
                contentAlignment = Alignment.Center) {
            IconRes(cell.iconRes, Color.White.copy(alpha = 0.95f), 22.dp)
        }
        BasicText(cell.title, style = MwType.caption.copy(fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.68f), textAlign = TextAlign.Center),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp))
    }
}

@Composable
private fun MwCategoryPanel(
        labels: List<String>,
        selected: Int,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
        onSelect: (Int) -> Unit,
        title: String,
        accent: Color = MwTokens.accentDefault,
) {
    MwGlassPanel(bd, spec, tier, Modifier.fillMaxWidth(),
            quality = MwGlassQuality.FULL, radius = spec.radius) {
        Column(Modifier.fillMaxWidth()) {
        MwPanelHeader(title, null, null, null, accent)
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            var i = 0
            while (i < labels.size) {
                Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in 0..1) {
                        val idx = i + k
                        val label = labels.getOrNull(idx)
                        if (label != null) {
                            MwCategoryRow(label, idx == selected, spec.accent, spec.motion,
                                    Modifier.weight(1f)) { onSelect(idx) }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (i + 2 < labels.size) {
                    Spacer(Modifier.height(6.dp))
                }
                i += 2
            }
        }
    }
    }
}

/** 分类行：中性玻璃。彩色胶囊是"AI 仪表盘"的典型味道，这里不用。 */
@Composable
private fun MwCategoryRow(
        label: String,
        selected: Boolean,
        accent: Color,
        motion: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
) {
    val shape = RoundedRectangle(14.dp)
    val scope = rememberCoroutineScope()
    val hl = remember(scope, motion) { if (motion) InteractiveHighlight(scope) else null }
    // 选中态用颜色动画过渡（180ms），不是硬切
    val fill by animateColorAsState(
            if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
            tween(180), label = "cat-fill")
    val edge by animateColorAsState(
            if (selected) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f),
            tween(180), label = "cat-edge")
    Row(modifier.height(50.dp).clip(shape)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .then(if (hl != null) Modifier.then(hl.modifier).then(hl.gestureModifier) else Modifier)
            .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedRectangle(9.dp))
                .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center) {
            IconVec(MwCategoryIcons.of(label), Color.White.copy(alpha = 0.85f), 14.dp)
        }
        BasicText(label, style = MwType.headline.copy(fontSize = 12.5.sp, lineHeight = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.82f)),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        IconVec(Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                Color.White.copy(alpha = 0.32f), 14.dp)
    }
}

// ================================================================ 分组列表

@Composable
private fun MwGroupPanel(
        block: MwBlock,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
        onCell: (MwCell) -> Unit,
        onAsk: (MwCell) -> Unit,
) {
    val all = block.cells
    if (all.isEmpty()) {
        return
    }
    var expanded by remember(block.title) { mutableStateOf(false) }
    val collapsed = !expanded && all.size > 8
    val cells = if (collapsed) all.subList(0, 6) else all

    MwGlassPanel(bd, spec, tier, Modifier.fillMaxWidth(),
            quality = MwGlassQuality.FULL, radius = spec.radius) {
        Column(Modifier.fillMaxWidth()) {
        MwPanelHeader(block.title ?: "", block.subtitle, block.trailing, null, spec.accent)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .animateContentSize(spring(dampingRatio = 1f, stiffness = 320f))) {
            for (j in cells.indices) {
                val cell = cells[j]
                if (j > 0) {
                    MwDivider(inset = 64.dp)
                }
                MwToolRow(cell, onCell, onAsk)
            }
        }
    }
    }
    if (collapsed) {
        Spacer(Modifier.height(8.dp))
        MwExpandPill("展开其余 ${all.size - cells.size} 项", spec, tier, bd) { expanded = true }
    }
}

/** 工具行：点开是打开，长按是「问 AI」（云控 `ai_entries_off=card` 关掉后长按无效果）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MwToolRow(cell: MwCell, onCell: (MwCell) -> Unit,
        onAsk: (MwCell) -> Unit = {}) {
    val note = cell.note
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (pressed) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .combinedClickable(interactionSource = src, indication = null,
                    onClick = { onCell(cell) },
                    onLongClick = if (cell.item == null) null else {
                        { onAsk(cell) }
                    })
            .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedRectangle(12.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedRectangle(12.dp)),
                contentAlignment = Alignment.Center) {
            IconRes(cell.iconRes, Color.White.copy(alpha = 0.95f), 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(cell.title, style = MwType.headline.copy(fontSize = 14.5.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                val badge = cell.badge
                if (badge != null) {
                    MwBadge(badge, Color(cell.badgeArgb))
                }
            }
            if (cell.sub.isNotEmpty()) {
                BasicText(cell.sub, style = MwType.caption.copy(fontSize = 12.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
            }
            if (note != null) {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconVec(Icons.Rounded.CloudOff, Color(cell.noteArgb), 12.dp)
                    BasicText(note, style = MwType.caption.copy(fontSize = 11.sp,
                            color = Color(cell.noteArgb)), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconVec(Icons.AutoMirrored.Rounded.ArrowForwardIos,
                Color.White.copy(alpha = 0.28f), 11.dp)
    }
}

@Composable
private fun MwExpandPill(text: String, spec: MwTokens.Spec, tier: MwTier, bd: Backdrop,
        onClick: () -> Unit) {
    MwGlassButton(text, false, spec, tier, bd,
            Modifier.fillMaxWidth().height(42.dp), onClick)
}

/**
 * 玻璃按钮：主按钮 = 强调色实底（参照图的 CTA），次按钮 = 真·液态玻璃胶囊。
 * 次按钮走 [MwGlassPanel]，带 InteractiveHighlight 的液态按压与 ui_glass/ui_tier 降级。
 */
@Composable
internal fun MwGlassButton(
        text: String,
        primary: Boolean,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop?,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
) {
    if (primary) {
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val scale by animateFloatAsState(
                if (pressed && spec.motion) 0.96f else 1f, MwGlass.Press, label = "btn-scale")
        Box(modifier.height(40.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(Capsule())
                .background(spec.accent)
                .clickable(interactionSource = src, indication = null, onClick = onClick)
                .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
            BasicText(text, style = MwType.headline.copy(fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold, color = MwTokens.textInverse))
        }
        return
    }
    // 内容横向贴文字（fillMaxHeight 只吃高度），否则胶囊会撑满整行、把文字压在底下
    MwGlassPanel(bd, spec, tier, modifier.height(40.dp).widthIn(min = 96.dp),
            quality = MwGlassQuality.FULL, radius = 100.dp, liquid = true, onClick = onClick) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
            BasicText(text, style = MwType.headline.copy(fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.88f)))
        }
    }
}

/**
 * 空列表。搜不到 ≠ 没这个能力 —— 放行且云控没关 `search` 入口时，这里给一颗「问 AI」，
 * 点了直接把这句搜索词交给 AI 让它从功能清单里挑。
 */
@Composable
private fun MwEmptyPanel(query: String, onAsk: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().heightIn(min = 140.dp)
            .clip(RoundedRectangle(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedRectangle(20.dp)),
            contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 16.dp)) {
            IconVec(Icons.Rounded.Search, Color.White.copy(alpha = 0.45f), 26.dp)
            BasicText(if (query.isBlank()) "这一页还没有功能" else "没有找到「$query」",
                    style = MwType.headline)
            BasicText(if (onAsk == null) "换个关键词，或点上面的分类看看"
            else "换个关键词、点上面的分类，或者让 AI 替你在清单里挑", style = MwType.caption)
            if (onAsk != null) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.clip(Capsule())
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), Capsule())
                        .clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = onAsk)
                        .padding(horizontal = 18.dp, vertical = 9.dp)) {
                    // 不叫「问 AI」：那两颗占位置的按钮已经按他的意思删了，这里只在
                    // 搜不到东西的那一刻出现，文案要说清它干什么。
                    BasicText("让 AI 帮我找", style = MwType.headline.copy(fontSize = 13.sp))
                }
            }
        }
    }
}

// ================================================================ 小件

/** 分类行的小图标：按板块名取，取不到就用文件夹。 */
internal object MwCategoryIcons {
    fun of(label: String): ImageVector = when {
        label.contains("全部") -> Icons.Rounded.Apps
        label.contains("本地") -> Icons.Rounded.PhoneAndroid
        label.contains("素材") || label.contains("视频") -> Icons.Rounded.Image
        label.contains("皮肤") || label.contains("头像") -> Icons.Rounded.Face
        label.contains("模型") || label.contains("粒子") -> Icons.Rounded.ViewInAr
        label.contains("材质") -> Icons.Rounded.Texture
        label.contains("建筑") -> Icons.Rounded.Foundation
        label.contains("命令") -> Icons.Rounded.Terminal
        label.contains("像素") -> Icons.Rounded.GridOn
        label.contains("音乐") -> Icons.Rounded.MusicNote
        label.contains("社区") -> Icons.Rounded.Groups
        label.contains("服务器") -> Icons.Rounded.Dns
        else -> Icons.Rounded.Folder
    }
}

/**
 * 入场编排（技能 `press-effects.md` 的节奏：错峰 45ms、总时长 <300ms、只动 transform/opacity）。
 *
 * `order` 封顶 5：后面的组不再等，避免"越往下越慢"的拖沓感。
 * `motion=false`（云控 ui_motion=0）时直接是终态，一帧都不动。
 */
@Composable
internal fun rememberMwEnter(order: Int, motion: Boolean, played: MutableSet<Int>): Modifier {
    val play = motion && played.add(order)
    val alpha = remember { Animatable(if (play) 0f else 1f) }
    val shift = remember { Animatable(if (play) 14f else 0f) }
    LaunchedEffect(Unit) {
        if (!play) {
            return@LaunchedEffect
        }
        delay((order.coerceAtMost(5) * 45).toLong())
        coroutineScope {
            launch { alpha.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
            launch { shift.animateTo(0f, spring(dampingRatio = 1f, stiffness = 320f)) }
        }
    }
    return Modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = shift.value
    }
}

/**
 * 面板内的小节标题：首页"全覆盖"后，节标题都住进各自的玻璃面板里，
 * 不再裸排在照片上（裸排的部分就是"没被玻璃盖住"的部分）。
 */
@Composable
internal fun MwPanelHeader(
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        onTrailing: (() -> Unit)? = null,
        accent: Color = MwTokens.accentDefault,
) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(14.dp).background(accent, RoundedRectangle(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = MwType.title.copy(fontSize = 15.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = MwType.caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            val click = if (onTrailing != null) {
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onTrailing)
            } else {
                Modifier
            }
            Row(click.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(trailing, style = MwType.caption.copy(
                        color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp))
                if (onTrailing != null) {
                    IconVec(Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            Color.White.copy(alpha = 0.5f), 14.dp)
                }
            }
        }
    }
}

/**
 * 展示栏：内嵌视频轮播。
 *
 * 只用一个 WebView：轮播切视频 = 往同一个 WebView 里 load 下一条播放页地址，
 * 避免多个 WebView 叠加的内存与手势冲突；8 秒自动换片，圆点可点。
 * WebView 只允许加载两家官方播放器域名（VideoEmbed.isAllowedPlayerUrl 把关）。
 */
@Composable
internal fun MwVideoPanel(
        videos: List<Array<String>>,
        spec: MwTokens.Spec,
        tier: MwTier,
        bd: Backdrop,
        context: Context,
) {
    val page = remember { mutableIntStateOf(0) }
    val webview = remember { mutableStateOf<WebView?>(null) }
    val loaded = remember { mutableStateOf("") }
    // 容器比例跟着视频走：抖音以竖屏/方图为主，固定 16:9 会把画面裁得只剩半截
    val aspect = remember { mutableFloatStateOf(16f / 9f) }
    // 加载失败要在界面上说出来（「直接不加载」最忌讳的就是一片空白什么都不写）
    val fault = remember { mutableStateOf("") }

    MwGlassPanel(bd, spec, tier, Modifier.fillMaxWidth(),
            quality = MwGlassQuality.FULL, radius = spec.radius) {
        Column(Modifier.fillMaxWidth()) {
            MwPanelHeader("视频展示", null,
                    if (videos.size > 1) "${page.intValue + 1}/${videos.size}" else null,
                    null, spec.accent)
            // 容器比例完全跟随视频（全界面显示）：竖屏/方图给足高度，不再封顶裁画面
            Box(Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .aspectRatio(aspect.floatValue)
                    .clip(RoundedRectangle(14.dp))) {
                AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            // 自定义触摸：按下就向上申请「不许父级拦截」——双保险，配合
                            // isNestedScrollingEnabled=false，视频框内的滚动/点击一定归 WebView
                            object : WebView(ctx) {
                                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                                        parent?.requestDisallowInterceptTouchEvent(true)
                                    }
                                    return super.onTouchEvent(ev)
                                }
                            }.apply {
                                settings.javaScriptEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = true
                                // 包装页走 file:// 直载（cacheDir），必须允许文件访问
                                settings.allowFileAccess = true
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = true
                                // iframe 相对 file:// 父页是第三方上下文：WebView 默认不给
                                // 第三方 iframe 发 cookie，播放器可能因此拒绝渲染 → 打开
                                android.webkit.CookieManager.getInstance()
                                        .setAcceptThirdPartyCookies(this, true)
                                // 关键：WebView 默认参与嵌套滚动，播放器页的滚动手势会被
                                // 首页的滚动容器抢走（页面有滚动条却滚不动）。关掉嵌套滚动 =
                                // 视频框内的滚动归 WebView 自己，首页只在视频框外滚。
                                isNestedScrollingEnabled = false
                                // 不透明纯黑：视频没画出来时看得见「是黑的」而不是「透出玻璃」，
                                // 排查时这两种情况含义完全不同；黑也正是这套设计的底色
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    // 播放器右下角「全屏」：没有这个回调，点全屏是死的（12 分钟的
                                    // 视频塞在首页小框里不放全屏，等于没法看）
                                    override fun onShowCustomView(view: android.view.View,
                                            cb: android.webkit.WebChromeClient.CustomViewCallback) {
                                        val host = (context as? android.app.Activity)
                                                ?.window?.decorView as? android.widget.FrameLayout
                                        if (host == null || view.parent != null) {
                                            cb.onCustomViewHidden()
                                            return
                                        }
                                        videoFullCb = cb
                                        videoFullView = view
                                        host.addView(view, android.widget.FrameLayout
                                                .LayoutParams(-1, -1))
                                    }

                                    override fun onHideCustomView() {
                                        val v = videoFullView
                                        videoFullView = null
                                        (v?.parent as? android.view.ViewGroup)?.removeView(v)
                                        try {
                                            videoFullCb?.onCustomViewHidden()
                                        } catch (t: Throwable) {
                                        }
                                        videoFullCb = null
                                    }

                                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage)
                                            : Boolean {
                                        // 只留错误类日志做线索，成功路径不写（否则会把真错误挤掉）
                                        val s = m.message() ?: ""
                                        if (lastVideoFault.startsWith("加载中")
                                                && (s.contains("error", true) || s.contains("fail", true)
                                                        || s.contains("denied", true) || s.contains("412")
                                                        || s.contains("352"))) {
                                            lastVideoFault = "页内报错 " + s.take(160)
                                        }
                                        return super.onConsoleMessage(m)
                                    }
                                }
                                // 播放器页面里会点到 snssdk1128://（抖音唤端）这类 scheme：
                                // 交给系统唤起对应 App，没装/被 ROM 拦就静默放弃（绝不让 WebView 报错页）
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: android.webkit.WebResourceRequest,
                                    ): Boolean {
                                        val scheme = request.url.scheme ?: return false
                                        val s = scheme.lowercase()
                                        // http(s) 与 WebView 自己的内部 scheme 一律放行
                                        if (s == "http" || s == "https" || s == "data" || s == "about"
                                                || s == "file" || s == "blob" || s == "javascript") {
                                            return false
                                        }
                                        // 其余（snssdk1128 / bilibili / intent / taobao…）都按唤端处理
                                        return try {
                                            view.context.startActivity(
                                                    android.content.Intent.parseUri(
                                                            request.url.toString(),
                                                            android.content.Intent.URI_INTENT_SCHEME)
                                                            .addFlags(
                                                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                            true
                                        } catch (t: Throwable) {
                                            true   // 没装就安静留着，不让它变成整页错误
                                        }
                                    }

                                    override fun onPageStarted(view: WebView, url: String?,
                                            favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        lastVideoFault = "加载中 " + (url?.take(70) ?: "空")
                                        fault.value = lastVideoFault
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (lastVideoFault.startsWith("加载中")) {
                                            lastVideoFault = "页面已加载完，标题：" + view.title
                                        }
                                        fault.value = if (view.progress >= 100) "" else lastVideoFault
                                    }

                                    override fun onReceivedError(view: WebView,
                                            req: android.webkit.WebResourceRequest,
                                            err: android.webkit.WebResourceError) {
                                        super.onReceivedError(view, req, err)
                                        if (req.isForMainFrame) {
                                            lastVideoFault = "主文档失败 code=${err.errorCode} " +
                                                    "${err.description} host=${req.url?.host}"
                                            fault.value = lastVideoFault
                                        }
                                    }

                                    override fun onReceivedHttpError(view: WebView,
                                            req: android.webkit.WebResourceRequest,
                                            resp: android.webkit.WebResourceResponse) {
                                        super.onReceivedHttpError(view, req, resp)
                                        if (req.isForMainFrame) {
                                            lastVideoFault = "主文档 HTTP ${resp.statusCode} " +
                                                    "${resp.reasonPhrase} ${req.url}"
                                            fault.value = lastVideoFault
                                        }
                                    }

                                    override fun onReceivedSslError(view: WebView,
                                            handler: android.webkit.SslErrorHandler,
                                            error: android.net.http.SslError) {
                                        // 不放行：证书有问题就当失败写出来，让它可见而不是悄悄放行
                                        lastVideoFault = "SSL 错误 ${error.primaryError} " +
                                                "${error.url}"
                                        fault.value = lastVideoFault
                                        handler.cancel()
                                    }

                                    override fun onRenderProcessGone(view: WebView,
                                            detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                        lastVideoFault = "WebView 渲染进程退出（内存不足或被系统杀）"
                                        fault.value = lastVideoFault
                                        return true
                                    }
                                }
                                webview.value = this
                                fault.value = ""
                                loadVideoUrl(videos[page.intValue][2],
                                        { aspect.floatValue = it }) { src ->
                                    applyVideo(this, src)
                                }
                                loaded.value = videos[page.intValue][2]
                            }
                        },
                        update = { wv ->
                            val want = videos[page.intValue][2]
                            if (loaded.value != want) {
                                loaded.value = want
                                lastVideoFault = ""
                                fault.value = ""
                                loadVideoUrl(want, { aspect.floatValue = it }) { src ->
                                    applyVideo(wv, src)
                                }
                            }
                        })
                // 诊断芯片：WebView 吃掉视频区的所有触摸，放一枚角标做「点击复制诊断」
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(Capsule())
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null) {
                            copyVideoDiagnostics(context, webview.value,
                                    loaded.value, aspect.floatValue)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    BasicText("诊断", style = MwType.badge.copy(color = Color.White))
                }
                // 加载状态/失败原因直接写在画面上：一片黑却什么都不说是最难查的
                if (fault.value.isNotEmpty()) {
                    BasicText(
                            fault.value,
                            modifier = Modifier.align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .clip(RoundedRectangle(8.dp))
                                    .background(Color.Black.copy(alpha = 0.78f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MwType.badge.copy(color = Color(0xFFFFC9C9)),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (videos.size > 1) {
                Row(Modifier.fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                    for (i in videos.indices) {
                        val on = i == page.intValue
                        Box(Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (on) 8.dp else 6.dp)
                                .clip(Capsule())
                                .background(if (on) spec.accent
                                else Color.White.copy(alpha = 0.30f))
                                .clickable(interactionSource = remember { MutableInteractionSource() },
                                        indication = null) { page.intValue = i })
                    }
                }
            }
        }
    }

    // 自动轮播：8 秒换下一条（ui_motion=0 关掉）
    if (videos.size > 1 && spec.motion) {
        LaunchedEffect(videos.size) {
            while (true) {
                delay(8000)
                page.intValue = (page.intValue + 1) % videos.size
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webview.value?.stopLoading()
            webview.value?.onPause()
        }
    }
}

/**
 * 陀螺仪高光：一道隐隐约约的白光铺在背景上，手机往哪边倾，光就往哪边照。
 *
 * 实现要点（这是它"不卡"的原因）：光斑是预画的径向渐变，动的是 `graphicsLayer` 的
 * **transform** —— 传感器事件只挪图层，不触发背板重录，玻璃照常按同一帧采样。
 * `MwTilt` 是加速度计低通（不漂、不用权限），静止在阈值内时高光停在中间。
 * 强度刻意压到 9%：是"隐约能看见"，不是"很明显"。
 */
@Composable
internal fun BoxScope.MwGyroGlow(tilt: MwTilt?, enabled: Boolean) {
    if (!enabled) {
        return
    }
    Box(Modifier.matchParentSize()
            .graphicsLayer {
                translationX = (tilt?.x ?: 0f) * size.width * 0.30f
                translationY = (tilt?.y ?: 0f) * size.height * 0.18f
            }
            .drawBehind {
                drawRect(Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.42f),
                        radius = size.width * 0.62f))
            })
}

/** 照片上的文字要一层很轻的投影，否则压到亮部（山脊/银河）会糊。 */
internal fun textShadow(): TextShadow = TextShadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1.5f),
        blurRadius = 6f)

@Composable
internal fun MwSectionTitle(
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        onTrailing: (() -> Unit)? = null,
        accent: Color = MwTokens.accentDefault,
) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
        // 3dp 强调色短条：ui_accent 在 Compose 侧最直观的落点
        Box(Modifier.width(3.dp).height(14.dp).background(accent, RoundedRectangle(2.dp)))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = MwType.title.copy(fontSize = 16.sp, shadow = textShadow()),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = MwType.caption.copy(
                        color = Color.White.copy(alpha = 0.6f), shadow = textShadow()),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            val click = if (onTrailing != null) {
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onTrailing)
            } else {
                Modifier
            }
            Row(click.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(trailing, style = MwType.caption.copy(
                        color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp,
                        shadow = textShadow()))
                if (onTrailing != null) {
                    IconVec(Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            Color.White.copy(alpha = 0.5f), 14.dp)
                }
            }
        }
    }
}

@Composable
internal fun IconVec(vector: ImageVector, tint: Color, size: Dp = 18.dp,
        modifier: Modifier = Modifier) {
    Image(imageVector = vector, contentDescription = null,
            modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
internal fun IconRes(id: Int, tint: Color, size: Dp = 20.dp,
        modifier: Modifier = Modifier) {
    if (id == 0) {
        IconVec(Icons.Rounded.Apps, tint, size, modifier)
    } else {
        Image(painter = painterResource(id), contentDescription = null,
                modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
    }
}

private fun filterBlocks(blocks: List<MwBlock>, query: String): List<MwBlock> {
    if (query.isBlank()) {
        return blocks
    }
    val q = query.trim().lowercase()
    val out = ArrayList<MwBlock>()
    for (b in blocks) {
        val kept = ArrayList<MwCell>()
        for (c in b.cells) {
            if ((c.title + " " + c.sub).lowercase().contains(q)) {
                kept.add(c)
            }
        }
        if (kept.isNotEmpty()) {
            val copy = MwBlock()
            copy.title = b.title
            copy.subtitle = b.subtitle
            copy.trailing = kept.size.toString() + " 项"
            copy.grid = b.grid
            copy.expandable = b.expandable
            copy.cells = kept
            out.add(copy)
        }
    }
    return out
}

// ================================================================ 玻璃面板

enum class MwGlassQuality {
    FULL,
    LITE,
}

/**
 * 一块液态玻璃。参数沿用技能规范：blur 2dp、lens 12/24、高光 0.15 / 阴影 0.08 / 内阴影 4dp·0.1。
 *
 * lens 提到 16/32：背后是真实照片（星星/山脊），折射深一点才看得出来；形状最小圆角 ≥20dp，
 * 16dp 的折射高度安全。色散只给顶栏、搜索条（HIGH 档）。
 */
@Composable
fun MwGlassPanel(
        bd: Backdrop?,
        spec: MwTokens.Spec,
        tier: MwTier,
        modifier: Modifier = Modifier,
        quality: MwGlassQuality = MwGlassQuality.FULL,
        radius: Dp = spec.radius,
        tint: Color = Color.Unspecified,
        chromatic: Boolean = false,
        liquid: Boolean = false,
        onClick: (() -> Unit)? = null,
        content: @Composable BoxScope.() -> Unit,
) {
    val shape = if (radius >= 100.dp) Capsule() else RoundedRectangle(radius)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // ui_motion=0：按压不动画（直接到位），高光也不随按压增强
    val press by animateFloatAsState(if (pressed && spec.motion) 1f else 0f,
            MwGlass.Press, label = "mw-glass-press")
    val glassOn = bd != null && spec.glass && tier != MwTier.FLAT && MwGlass.supported()
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = src, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    // 深色玻璃要有"暗底"：照片亮部（银河/山脊）透过来时，白字才压得住。
    val base = if (tint.isSpecified) tint else Color(0xFF0A0C12).copy(alpha = 0.40f)

    if (!glassOn) {
        Box(modifier.clip(shape).background(base)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape).then(click),
                content = content)
        return
    }

    val surface: DrawScope.() -> Unit = {
        // 深色玻璃 = 暗底 + 顶部一道白反光（不是白纱）。技能规范里深色底就是 121212 一档。
        drawRect(Color(0xFF0A0C12).copy(alpha = 0.34f))
        drawRect(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = 0.05f + 0.05f * press),
                Color.Transparent), startY = 0f, endY = size.height * 0.38f))
        if (tint.isSpecified) {
            drawRect(tint.copy(alpha = 0.16f))
        }
    }
    val sheen: ((androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit))? =
            if (liquid && spec.motion) {
                {
                    val k = 1f - 0.014f * press
                    scaleX = k
                    scaleY = k
                }
            } else {
                null
            }
    val m = if (quality == MwGlassQuality.LITE) {
        modifier.drawPlainBackdrop(
                backdrop = bd,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(maxOf(6.dp, spec.blurDp).toPx())
                },
                layerBlock = sheen,
                onDrawSurface = surface,
        )
    } else {
        modifier.drawBackdrop(
                backdrop = bd,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(spec.blurDp.toPx())
                    // 折射高度不能超过形状最小圆角（技能硬约束），小圆角时自动收窄；
                    // ui_refract=0 时 lens 内部直接跳过（关折射）
                    val refr = if (radius >= 100.dp) minOf(16.dp, spec.refractDp)
                    else minOf(spec.refractDp, radius)
                    lens(refr.toPx(), spec.refractDp.toPx(),
                            chromaticAberration = chromatic && tier == MwTier.HIGH)
                },
                highlight = {
                    Highlight(width = 0.8f.dp, blurRadius = 0.4f.dp,
                            alpha = 0.18f + 0.40f * press,
                            style = HighlightStyle.Default(angle = -2.356f, falloff = 1.5f))
                },
                shadow = { Shadow(alpha = 0.10f + 0.24f * press) },
                innerShadow = { InnerShadow(radius = 4f.dp + 8f.dp * press,
                        alpha = 0.12f + 0.30f * press) },
                layerBlock = sheen,
                onDrawSurface = surface,
        )
    }
    Box(m.then(click), content = content)
}

/**
 * 展示栏唯一的加载方式：**把各家官方「复制嵌入代码」给的那段 iframe 原样放进一个宿主页里** ——
 * 也就是任何网站嵌 B 站/抖音视频的标准做法。不做直链抓取，不重写它们的参数。
 *
 * 为什么必须是 iframe 嵌入而不是把播放器地址当顶层页面打开：实测 B 站对**顶层导航**会 302 到
 * `/blackboard/webplayer/mbplayer.html`（手机版页面），那份页面在这台 WebView 里数据全通
 * （readyState=4、时长、直链都有）却不画画面；作为 iframe 嵌入才是它设计出来的用法。
 * iframe 的尺寸用 JS 赋像素：这台 WebView 会把 `aspect-ratio` / `min()` / `calc()` 量成 0
 * （抖音那轮用诊断芯片证实过），而容器比例已经跟着视频真实宽高，铺满即可、不用再加减。
 */
private fun applyVideo(wv: WebView, src: String) {
    val html = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:rgb(0,0,0);overflow:hidden}
#box{position:absolute;left:0;top:0;right:0;bottom:0}
iframe{border:0;position:absolute;left:0;top:0}</style></head>
<body><div id="box"><iframe src="$src" scrolling="no" frameborder="no" framespacing="0"
 allowfullscreen="true" referrerpolicy="origin"
 onload="window.__iframeOk=1" onerror="window.__iframeErr=1"></iframe></div>
<script>
window.__boot=1;
function vp(){var v=window.visualViewport;return [Math.round(v&&v.width||window.innerWidth||0),
 Math.round(v&&v.height||window.innerHeight||document.documentElement.clientHeight||0)]}
function size(){try{var f=document.querySelector('iframe');if(!f){window.__noIframe=1;return}
 var s=vp();if(!s[0]||!s[1]){setTimeout(size,120);return}
 f.style.width=s[0]+'px';f.style.height=s[1]+'px';window.__fit=s[0]+'x'+s[1];}
 catch(e){window.__jsError=String(e)}}
function tell(msg){var d=document.createElement('div');
 d.style.cssText='position:absolute;left:0;top:0;right:0;bottom:0;color:#fff;font-size:13px;'
 +'display:flex;align-items:center;justify-content:center;text-align:center;padding:10px;'
 +'background:rgb(0,0,0);pointer-events:none;line-height:1.5';
 d.textContent=msg;document.body.appendChild(d);window.__told=msg}
size();
setTimeout(size,150);setTimeout(size,500);setTimeout(size,1200);
window.addEventListener('resize',size);
window.addEventListener('message',function(){size()});
// 3.5 秒后还立不起来，就把现场写在视频区里（不等日志、不靠截图）
setTimeout(function(){size();var f=document.querySelector('iframe');
 var r=f?f.getBoundingClientRect():[0,0,0,0,0,0];
 if(!window.__iframeOk){tell('官方播放器没响应：iframe 没有触发加载（网络被拦 / 地址失效？）');return}
 if(!f||r.width<8||r.height<8){tell('播放器加载了但没立起来：'+Math.round(r?r.width:0)+'x'
  +Math.round(r?r.height:0)+' 视口'+vp()[0]+'x'+vp()[1]+' 脚本'+(window.__boot?'跑了':'没跑'))}
},3500);
</script></body></html>"""
    writeWrapperAndLoad(wv, html, src)
}

private fun writeWrapperAndLoad(wv: WebView, html: String, fallbackUrl: String) {
    try {
        val f = java.io.File(wv.context.cacheDir, "player_embed.html")
        java.io.FileWriter(f).use { it.write(html) }
        wv.loadUrl("file://" + f.absolutePath)
    } catch (t: Throwable) {
        wv.loadUrl(fallbackUrl)   // 写文件失败就直载，宁可画面不完美不能没视频
    }
}

/** 抓分享页：返回（正文, Set-Cookie 里的 ttwid）。第一跳分享页自己会种 ttwid。 */
private fun fetchDouyinShare(share: String, cookie: String?): Pair<String, String?> {
    var ttwid: String? = null
    val conn = java.net.URL(share).openConnection() as java.net.HttpURLConnection
    try {
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", DOUYIN_UA)
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        conn.responseCode
        for ((k, v) in conn.headerFields) {
            if (k != null && k.equals("set-cookie", ignoreCase = true)) {
                for (c in v) {
                    val pair = c.substringBefore(';').trim()
                    if (pair.startsWith("ttwid=")) ttwid = pair
                }
            }
        }
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        return body to ttwid
    } finally {
        conn.disconnect()
    }
}

/**
 * 「诊断」芯片：把视频加载链的现场状态整段复制到剪贴板（他报「黑屏」时贴回来定位）。
 * 收集：目标地址、容器比例、ttwid cookie 有无、WebView 版本、页面 url/title/进度/内容高度、
 * 包装文件的存在与开头 300 字节。
 */
/** 诊断用：最近一次 ttwid 预取的结果。 */
private var lastPrewarmStatus: String = "未运行"

/** 诊断用：视频 WebView 最近一次加载状态/失败原因（主线程读写）。 */
private var lastVideoFault: String = ""

/** 播放器全屏用：退出全屏时要回给 WebView 的那个回调。 */
private var videoFullCb: android.webkit.WebChromeClient.CustomViewCallback? = null

/** 播放器全屏用：盖在 decor 上的那个视图，退出时按它摘下来。 */
private var videoFullView: android.view.View? = null

private fun copyVideoDiagnostics(context: Context, wv: WebView?, loaded: String, aspect: Float) {
    // WebView 的所有 getter 都必须在主线程调（后台线程调用会直接抛 RuntimeException）
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        val sb = StringBuilder()
        try {
            sb.append("loaded=").append(loaded).append('\n')
            sb.append("aspect=").append(aspect).append('\n')
            sb.append("prewarm=").append(lastPrewarmStatus).append('\n')
            sb.append("fault=").append(lastVideoFault).append('\n')
            sb.append("ua=").append(if (wv == null) "无 WebView" else {
                val uaNow = wv.settings.userAgentString ?: ""
                (if (uaNow.contains("; wv")) "仍带 wv 标记" else "浏览器 UA") + " len=" + uaNow.length
            }).append('\n')
            try {
                val ck = android.webkit.CookieManager.getInstance()
                        .getCookie("https://open.douyin.com/")
                sb.append("cookie=").append(
                        if (ck != null && ck.contains("ttwid")) "ttwid有(" +
                                (ck?.length ?: 0) + "B)" else "ttwid无 raw=" + (ck ?: "空")).append('\n')
            } catch (t: Throwable) {
                sb.append("cookie=读取失败 ").append(t.javaClass.simpleName).append('\n')
            }
            try {
                val pi = context.packageManager.getPackageInfo("com.google.android.webview", 0)
                sb.append("wvVer=").append(pi.versionName).append('\n')
            } catch (t: Throwable) {
                sb.append("wvVer=未知\n")
            }
            if (wv == null) {
                sb.append("wv=null\n")
            } else {
                sb.append("wv.url=").append(wv.url).append('\n')
                sb.append("wv.progress=").append(wv.progress).append('\n')
                sb.append("wv.contentHeight=").append(wv.contentHeight).append('\n')
                sb.append("wv.size=").append(wv.width).append('x').append(wv.height).append('\n')
                sb.append("wv.layer=").append(wv.layerType).append('\n')
            }
            val f = java.io.File(context.cacheDir, "player_embed.html")
            sb.append("wrapper.exists=").append(f.exists())
            if (f.exists()) {
                sb.append(" len=").append(f.length()).append('\n')
            }
            // 页面内部：iframe（抖音包装页）与 video（B 站直载）都探一遍，谁在、多大、有没有报错
            if (wv != null) {
                wv.evaluateJavascript(
                        "(function(){var f=document.querySelector('iframe');" +
                                "var v=document.querySelector('video');" +
                                "var r=f?f.getBoundingClientRect():null;" +
                                "return JSON.stringify({n:document.querySelectorAll('iframe').length," +
                                "ok:window.__iframeOk===1,err:window.__iframeErr===1," +
                                "iw:window.innerWidth,ih:window.innerHeight,fit:window.__fit||'未执行'," +
                                "w:r?Math.round(r.width):0,h:r?Math.round(r.height):0," +
                                "boot:window.__boot===1,noIf:window.__noIframe===1," +
                                "jsErr:(window.__jsError||'').slice(0,80)," +
                                "told:(window.__told||'').slice(0,70)," +
                                "istyle:f?(f.getAttribute('style')||'未写尺寸').slice(0,60):''," +
                                "url:location.href.slice(0,110),title:(document.title||'').slice(0,40)," +
                                "vid:!!v,vready:v?v.readyState:-1,verr:v&&v.error?v.error.code:0," +
                                "vdur:v?v.duration:-1,vcur:v?v.currentTime:-1," +
                                "vr:v?(function(){var r=v.getBoundingClientRect();" +
                                "return Math.round(r.width)+'x'+Math.round(r.height)+'@'+Math.round(r.left)+','+Math.round(r.top)})():''," +
                                "pr:v&&v.parentElement?(function(){var r=v.parentElement.getBoundingClientRect();" +
                                "return Math.round(r.width)+'x'+Math.round(r.height)})():''," +
                                "cs:v?(function(){var c=getComputedStyle(v);" +
                                "return c.display+'/'+c.visibility+'/'+c.opacity+'/'+c.objectFit})():''," +
                                "vw:v?v.videoWidth:0,vh:v?v.videoHeight:0," +
                                "vis:document.visibilityState," +
                                "vsrc:v?(v.currentSrc||v.src||'').slice(0,70):''," +
                                "txt:(document.body?document.body.innerText:'')" +
                                ".replace(/\\s+/g,' ').slice(0,140)});})()",
                ) { result ->
                    val extra = sb.toString() + "\npage=" +
                            result.removeSurrounding("\"").replace("\\\"", "\"")
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("video-diag", extra))
                        android.widget.Toast.makeText(context, "诊断已复制，发给开发者",
                                android.widget.Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                    }
                }
                return@post
            }
        } catch (t: Throwable) {
            sb.append("diagErr=").append(t.javaClass.name).append(':').append(t.message).append('\n')
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("video-diag", sb.toString()))
            android.widget.Toast.makeText(context, "诊断已复制，发给开发者",
                    android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
        }
    }
}

/**
 * 加载前置（后台线程跑，完了回主线程交给 [go]）。**不改写、不替换官方嵌入地址**，只做两件配套事：
 *  · **ttwid 预取**：抖音侧对没有 ttwid cookie 的请求会 SSR 出「视频只能在抖音观看」的兜底页
 *    （实测），所以先 HTTP 跳一次分享页，把 Set-Cookie 里的 ttwid 写进 CookieManager。
 *  · **真实宽高**：问开放平台 `get_iframe_by_video`（官方接口、无需权限）要 `video_width/height`
 *    回报 [onAspect] —— 抖音以竖屏/方图为主，容器固定 16:9 会把画面裁掉半截。
 * B 站的官方嵌入码本身就是 16:9，不需要预取。任一环节失败都只是不预取，绝不阻塞加载。
 */
private fun loadVideoUrl(url: String, onAspect: (Float) -> Unit, go: (String) -> Unit) {
    val host = runCatching { android.net.Uri.parse(url).host ?: "" }.getOrDefault("")
    if (!host.contains("douyin")) {
        go(url)
        return
    }
    val vid = Regex("vid=(\\d{6,})").find(url)?.groupValues?.get(1).orEmpty()
    Thread {
        // 只为拿 ttwid cookie（短链/分享页那条路要用），视频本身仍走官方嵌入地址。
        var ttwid = ""
        if (vid.isNotEmpty()) {
            try {
                val share = "https://www.iesdouyin.com/share/video/" + vid + "/"
                ttwid = fetchDouyinShare(share, null).second.orEmpty()
                lastPrewarmStatus = "ttwid=" + (if (ttwid.isEmpty()) "无（响应里没给）" else "有")
            } catch (t: Throwable) {
                lastPrewarmStatus = "分享页失败:" + t.javaClass.simpleName
            }
        }
        // 官方 API 拿真实宽高（video_width / video_height），换算容器比例
        var ratio = 0f
        if (vid.isNotEmpty()) {
            try {
                val api = java.net.URL("https://open.douyin.com/api/douyin/v1/video/"
                        + "get_iframe_by_video?video_id=" + vid)
                        .openConnection() as java.net.HttpURLConnection
                api.connectTimeout = 8000
                api.readTimeout = 8000
                api.setRequestProperty("User-Agent", DOUYIN_UA)
                api.responseCode
                val body = api.inputStream.readBytes().decodeToString()
                api.disconnect()
                val w = Regex("\"video_width\"\\s*:\\s*(\\d+)").find(body)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val h = Regex("\"video_height\"\\s*:\\s*(\\d+)").find(body)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                if (w > 0f && h > 0f) {
                    ratio = (w / h).coerceIn(0.5f, 2.0f)
                }
            } catch (t: Throwable) {
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = android.webkit.CookieManager.getInstance()
                if (ttwid.isNotEmpty()) {
                    // setCookie 不带 Domain 属性 = host-only，必须每个要用的域各写一份
                    cm.setCookie("https://www.iesdouyin.com", ttwid)
                    cm.setCookie("https://open.douyin.com", ttwid)
                    cm.setCookie("https://www.douyin.com", ttwid)
                }
                cm.flush()
            } catch (t: Throwable) {
            }
            if (ratio > 0f) {
                onAspect(ratio)
            }
            go(url)
        }
    }.start()
}

private const val DOUYIN_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120 Mobile Safari/537.36"
