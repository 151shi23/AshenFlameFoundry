package com.mineways.plaza

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.mineways.blender.Downloader
import com.mineways.ui.MwInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 底部「资源」Tab 的 Compose 宿主；玻璃导航栏仍由 MainActivity 的 LiquidGlassNav 负责。 */
class PlazaTabView(context: Context) : FrameLayout(context) {
    init {
        val compose = androidx.compose.ui.platform.ComposeView(context)
        compose.clipToPadding = false
        compose.setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF101114),
                    primary = Color.White,
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White,
                )
            ) {
                PlazaTabScreen(context)
            }
        }
        addView(compose, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlazaTabScreen(context: Context) {
    var snapshot by remember { mutableStateOf<PlazaCatalog.Snapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("all") }
    var category by remember { mutableStateOf<PlazaCategory?>(null) }
    var selected by remember { mutableStateOf<PlazaItem?>(null) }
    var showLibrary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            snapshot = withContext(Dispatchers.IO) {
                PlazaCatalog.load(context, reload > 0)
            }
            snapshot?.warnings?.firstOrNull()?.let { error = it }
        } catch (t: Throwable) {
            error = t.message ?: "资源目录加载失败"
        } finally {
            loading = false
        }
    }

    val navDp by MwInsets.bottomNavDp
    val bottomPad = if (navDp > 0) navDp.dp else 96.dp
    val index = remember(snapshot) { snapshot?.let { PlazaSearchIndex(it) } }
    val categories = snapshot?.search?.categories ?: emptyList()
    val visibleCategories = if (PlazaFlags.categories()) categories else emptyList()
    val primary = listOf(
        "all" to "全部",
        "map" to "地图",
        "model" to "人物",
        "texture" to "纹理",
        "other" to "通用"
    )

    val searched = remember(snapshot, index, query, type) {
        val base = if (index != null) index.search(query, type) else snapshot?.items ?: emptyList()
        if (query.isBlank()) base.sortedByDescending { it.updatedAt } else base
    }
    val shown = searched.filter { item ->
        val c = category
        if (!PlazaFlags.allowCloud() && item.isCloud()) false
        else if (c == null) true
        else (c.type == "all" || c.type == item.type)
                && (c.tag.isBlank() || item.tags.contains(c.tag))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF08090C), Color.Black, Color(0xFF050506))
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPad + 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Header(
                    onRefresh = { reload++ },
                    onLibrary = { showLibrary = true }
                )
            }
            item {
                SearchField(query) { query = it }
            }
            item {
                ChipRow(primary.map { it.second }, primary.map { it.first }, type) { value ->
                    type = value
                    category = null
                }
            }
            if (visibleCategories.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(visibleCategories) { c ->
                            Chip(
                                label = c.label,
                                selected = category?.id == c.id,
                                onClick = {
                                    category = if (category?.id == c.id) null else c
                                    type = if (c.type == "all") type else c.type
                                }
                            )
                        }
                    }
                }
            }

            if (loading && snapshot == null) {
                item { SkeletonBlock() }
            } else if (snapshot == null || (shown.isEmpty() && snapshot!!.items.isEmpty())) {
                item { EmptyState(error) { reload++ } }
            } else {
                val fs = snapshot!!.featured
                if (PlazaFlags.featured() && fs != null && query.isBlank() && type == "all" && category == null) {
                    val featured = fs.ids.mapNotNull { snapshot!!.byId[it] }
                        .filter { PlazaFlags.allowCloud() || !it.isCloud() }
                    if (featured.isNotEmpty()) {
                        item { SectionHeader("本周精选", fs.week) }
                        item { FeaturedRow(featured) { selected = it } }
                    }
                }
                if (PlazaFlags.recommend() && query.isBlank() && shown.isNotEmpty()) {
                    item { SectionHeader("猜你喜欢", "为你挑选") }
                    item { FeaturedRow(shown.take(6)) { selected = it } }
                }
                if (PlazaFlags.rankings() && query.isBlank() && snapshot!!.rankings.isNotEmpty()) {
                    item { SectionHeader("排行榜", "编辑精选 · 每周更新") }
                    item { RankingBlock(snapshot!!) { selected = it } }
                }
                if (PlazaFlags.collections() && query.isBlank() && category == null
                    && snapshot!!.collections.isNotEmpty()
                ) {
                    item { SectionHeader("合集", "主题 / 官方创作者") }
                    item { CollectionRow(snapshot!!, categories) { c -> selected = c } }
                }
                item {
                    SectionHeader(
                        if (query.isBlank()) "全部资源" else "搜索结果",
                        "${shown.size} 条"
                    )
                }
                if (shown.isEmpty()) {
                    item { EmptyState("当前分类还没有资源") { reload++ } }
                } else {
                    items(shown.chunked(2)) { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { item ->
                                ResourceCard(item, Modifier.weight(1f)) { selected = item }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111216)
        ) {
            DetailSheet(context, item, scope = scope) { selected = null }
        }
    }
    if (showLibrary) {
        ModalBottomSheet(
            onDismissRequest = { showLibrary = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111216)
        ) {
            LibrarySheet(context, onClose = { showLibrary = false })
        }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit, onLibrary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("资源广场", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("官方精选 · 每周更新", color = Color(0x99FFFFFF), fontSize = 11.sp)
        }
        TextButton(onClick = onRefresh) { Text("刷新", color = Color(0xCCFFFFFF), fontSize = 12.sp) }
        TextButton(onClick = onLibrary) { Text("管家", color = Color(0xCCFFFFFF), fontSize = 12.sp) }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1C21))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text("搜标题、创作者、标签、简介", color = Color(0x66FFFFFF), fontSize = 13.sp)
            }
            inner()
        }
    )
}

@Composable
private fun ChipRow(labels: List<String>, values: List<String>, selected: String,
                    onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(labels.size) { i ->
            Chip(labels[i], selected == values[i]) { onSelect(values[i]) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color(0xCCFFFFFF),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color.White else Color(0xFF1A1C21))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    )
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text(sub, color = Color(0x66FFFFFF), fontSize = 10.sp)
    }
}

@Composable
private fun FeaturedRow(items: List<PlazaItem>, onClick: (PlazaItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { item ->
            Column(
                Modifier
                    .width(238.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF17191E))
                    .clickable { onClick(item) }
            ) {
                Cover(item.previewCover, Modifier.fillMaxWidth().height(132.dp))
                Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 11.dp, end = 11.dp, top = 9.dp))
                Text(item.author.ifBlank { "未知创作者" }, color = Color(0x99FFFFFF),
                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun ResourceCard(item: PlazaItem, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF17191E))
            .clickable(onClick = onClick)
    ) {
        Cover(item.previewCover, Modifier.fillMaxWidth().height(118.dp))
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        Text(item.author.ifBlank { "未知创作者" }, color = Color(0x99FFFFFF),
            fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(item.typeLabel(), color = Color(0xCCFFFFFF), fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(item.sizeText(), color = Color(0x66FFFFFF), fontSize = 9.sp)
        }
        if (item.isCloud()) {
            Text(item.sourceLabel(), color = Color(0xFFE0A35A), fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        } else {
            Text("官方 Release", color = Color(0xFF9E9E9E), fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun Cover(url: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF25282E))) {
        if (url.isNotBlank()) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { image -> PlazaImages.load(url, image) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RankingBlock(snapshot: PlazaCatalog.Snapshot, onClick: (PlazaItem) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0xFF17191E))) {
        snapshot.rankings.take(2).forEach { ranking ->
            Text(ranking.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 13.dp, top = 11.dp, bottom = 4.dp))
            ranking.entries.take(5).forEach { entry ->
                val item = snapshot.byId[entry.id] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(item) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${entry.rank}", color = if (entry.rank <= 3) Color.White else Color(0x66FFFFFF),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp))
                    Text(item.title, color = Color.White, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(item.typeLabel(), color = Color(0x66FFFFFF), fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(snapshot: PlazaCatalog.Snapshot, categories: List<PlazaCategory>,
                           onClick: (PlazaItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(snapshot.collections) { c ->
            val list = snapshot.collectionItems(c)
            if (list.isEmpty()) return@items
            Column(
                Modifier.width(205.dp).clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF17191E)).clickable {
                        onClick(list.first())
                    }
            ) {
                Cover(c.cover, Modifier.fillMaxWidth().height(88.dp))
                Text(c.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 7.dp))
                Text((if (c.official) "官方收录 · " else "") + "${list.size} 个资源",
                    color = Color(0x99FFFFFF), fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun SkeletonBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(210.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF17191E))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("暂时没有资源", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(message ?: "官方资源正在整理，稍后再来看看",
            color = Color(0x66FFFFFF), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("重新加载", fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(context: Context, item: PlazaItem,
                        scope: kotlinx.coroutines.CoroutineScope, onClose: () -> Unit) {
    var progress by remember { mutableFloatStateOf(-1f) }
    var downloading by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        file = withContext(Dispatchers.IO) { PlazaDownloader.downloaded(context, item) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Cover(item.previewCover, Modifier.fillMaxWidth().height(190.dp))
        Text(item.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        val creator = item.author.ifBlank { "未知创作者" }
        Text(creator, color = Color(0xFFD7A36A), fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp))
        Text("${item.typeLabel()} · ${item.sizeText()} · ${item.version}",
            color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        if (item.summary.isNotBlank()) {
            Text(item.summary, color = Color(0xCCFFFFFF), fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
        if (item.isOfficial()) {
            Text("Release  ${item.releaseTag}", color = Color(0x66FFFFFF), fontSize = 10.sp,
                modifier = Modifier.padding(top = 10.dp))
            Text("SHA-256  ${item.sha256.take(24)}…", color = Color(0x66FFFFFF), fontSize = 10.sp)
        } else {
            Text(item.sourceLabel() + if (item.cloudNote.isBlank()) "" else " · ${item.cloudNote}",
                color = Color(0xFFE0A35A), fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
        message?.let {
            Text(it, color = Color(0xFFE07B7B), fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (progress >= 0f) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp))
                    .background(Color(0xFF2A2D33))
            ) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (item.isOfficial()) {
            Button(
                onClick = {
                    if (file != null) {
                        openPlazaFile(context, file!!)
                        return@Button
                    }
                    if (downloading) return@Button
                    downloading = true
                    progress = 0f
                    message = null
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) {
                                PlazaDownloader.download(context, item,
                                    Downloader.Progress { done, total ->
                                        if (total > 0) progress = done.toFloat() / total
                                    }, Downloader.NEVER)
                            }
                            PlazaLibrary.remember(context, item, f)
                            file = f
                            progress = -1f
                            message = "下载完成，已加入资源管家"
                        } catch (t: Throwable) {
                            progress = -1f
                            message = t.message ?: "下载失败"
                        } finally {
                            downloading = false
                        }
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text(if (file != null) "打开资源" else if (downloading) "下载中…" else "下载",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (file != null) {
                TextButton(onClick = { sharePlazaFile(context, file!!) }, modifier = Modifier.fillMaxWidth()) {
                    Text("分享文件", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
            }
        } else {
            Button(
                onClick = { PlazaCloudLinks.open(context, item) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("打开云盘", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            if (item.accessCode.isNotBlank()) {
                TextButton(
                    onClick = {
                        if (PlazaCloudLinks.copyCode(context, item)) message = "提取码已复制"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制提取码 ${item.accessCode}", color = Color(0xCCFFFFFF), fontSize = 12.sp) }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("关闭", color = Color(0x66FFFFFF), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySheet(context: Context, onClose: () -> Unit) {
    var items by remember { mutableStateOf(PlazaLibrary.list(context)) }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("资源管家", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${items.size} 个资源 · ${PlazaRepo.humanSize(PlazaLibrary.totalSize(context))}",
            color = Color(0x66FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        if (items.isEmpty()) {
            Text("还没有下载资源", color = Color(0x66FFFFFF), fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 22.dp))
        }
        items.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.id }, color = Color.White, fontSize = 13.sp)
                    Text(PlazaRepo.humanSize(entry.size) + " · " + entry.releaseTag,
                        color = Color(0x66FFFFFF), fontSize = 10.sp)
                }
                TextButton(onClick = { openPlazaFile(context, File(entry.path)) }) {
                    Text("打开", color = Color.White, fontSize = 11.sp)
                }
                TextButton(onClick = {
                    PlazaLibrary.remove(context, entry.id)
                    items = PlazaLibrary.list(context)
                }) { Text("删除", color = Color(0xFFE07B7B), fontSize = 11.sp) }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("关闭", color = Color(0x66FFFFFF), fontSize = 12.sp)
        }
    }
}

private fun openPlazaFile(context: Context, file: File) {
    if (!file.isFile) return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val it = android.content.Intent(context, com.mineways.open.OpenWithActivity::class.java)
        it.action = android.content.Intent.ACTION_VIEW
        it.data = uri
        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(it)
    } catch (_: Throwable) {
    }
}

private fun sharePlazaFile(context: Context, file: File) {
    if (!file.isFile) return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val it = android.content.Intent(android.content.Intent.ACTION_SEND)
        it.type = "application/octet-stream"
        it.putExtra(android.content.Intent.EXTRA_STREAM, uri)
        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(it, "分享资源"))
    } catch (_: Throwable) {
    }
}
