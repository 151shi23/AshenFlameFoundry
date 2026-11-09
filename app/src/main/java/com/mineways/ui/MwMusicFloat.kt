package com.mineways.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.mineways.glass.ViewBackdrop
import com.mineways.music.MusicCtl
import com.mineways.music.MusicSources
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 软件内悬浮播放条：挂在 Activity 的内容层上，所以**切到任何页面都还在**；
 * 背景是把下面那块原生视图按帧快照成位图喂给 [ViewBackdrop] —— 跟玻璃导航栏同一套
 * 真液态玻璃路子，折射和模糊采的是页面本身的内容。
 *
 * 三态收纳：**歌词胶囊**（300dp，上行当前歌词、下行进度与 收起/上一首/播放暂停/下一首/停止）
 * · **小圆**（56dp 带进度环，点一下展开，长按收纳）· **贴边一根发光边**（14dp，碰它就出来）。
 * 折叠态与收纳态都记在本机，并与系统悬浮条共用同一组键（用户只表达过一次「现在不想看到它」）。
 * 拖动只能改自己的 margin，松手贴左右边缘。
 *
 * 画不画完全由 [com.mineways.music.MusicBarPolicy] 决定：用户同意过、云控没关「软件内播放」、
 * 云控没关悬浮条（music_bar_off）、正在播放、系统级悬浮条没开着。少一条就不画、也不抓帧。
 */
class MwMusicFloat(context: Context) : FrameLayout(context) {

    private val composeView = androidx.compose.ui.platform.ComposeView(context)
    private var source: View? = null
    private var backdropBitmap: Bitmap? = null
    private var bitmapOrigin = Offset.Zero
    private val here = IntArray(2)
    private val there = IntArray(2)

    /** 与导航栏同档：太高浪费带宽，太低折射采不到文字边缘。 */
    private val scale = 0.75f
    private val padPx = 48f * resources.displayMetrics.density

    private val mini = mutableStateOf(MusicSources.barCollapsed(context))
    private val peek = mutableStateOf(MusicSources.barPeek(context))
    private var wanted = false
    private var attached = false
    private var looping = false

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        val backdrop = ViewBackdrop(
            bitmap = { backdropBitmap },
            bitmapOriginInWindow = { bitmapOrigin },
            bitmapScale = { scale },
            paddingPx = padPx,
        )
        composeView.setContent { Pill(backdrop) }
        addView(composeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setBackdropSource(view: View?) {
        source = view
    }

    /** 播放状态、云控、用户刚给的答案变了就重算；不显示时连帧都不抓。 */
    fun refresh() {
        wanted = MusicSources.barPolicy(context).show
        applyLoop()
        if (wanted) {
            composeView.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        restorePosition()
        refresh()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopLoop()
        super.onDetachedFromWindow()
    }

    private fun applyLoop() {
        val should = wanted && attached
        if (should && !looping) {
            looping = true
            Choreographer.getInstance().postFrameCallback(frame)
        } else if (!should && looping) {
            stopLoop()
        }
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frame)
        backdropBitmap?.recycle()
        backdropBitmap = null
    }

    private val frame = object : Choreographer.FrameCallback {
        private var n = 0
        override fun doFrame(frameTimeNanos: Long) {
            if (!attached || !looping) {
                return
            }
            // 背景快照很贵（整棵视图 draw 进位图）：界面动画仍 60fps，折射底图 20fps 就够
            n++
            if (wanted && isShown && n % 3 == 0) {
                snapshot()
            }
            composeView.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 把下面那块内容画进一张软件位图。不能在 Compose 绘制里直接 `view.draw()`：
     * 同一棵 View 树此刻正在录 DisplayList，再进一次就抛 "Recording currently in progress"。
     */
    private fun snapshot() {
        val src = source ?: return
        if (width <= 0 || height <= 0 || src.width <= 0 || src.height <= 0 || !src.isAttachedToWindow) {
            return
        }
        try {
            getLocationInWindow(here)
            src.getLocationInWindow(there)
            val w = ((width + padPx * 2f) * scale).toInt().coerceAtLeast(1)
            val h = ((height + padPx * 2f) * scale).toInt().coerceAtLeast(1)
            var bmp = backdropBitmap
            if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                backdropBitmap = bmp
            }
            // 先铺场景最底色：源视图某处没画到时玻璃采到的是深蓝黑，不是 alpha=0 的死黑
            bmp.eraseColor(0xFF07080D.toInt())
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-(here[0] - there[0] - padPx), -(here[1] - there[1] - padPx))
            src.draw(canvas)
            bitmapOrigin = Offset(here[0] - padPx, here[1] - padPx)
        } catch (t: Throwable) {
            backdropBitmap?.recycle()
            backdropBitmap = null
        }
    }

    /** 视图尺寸 == 药丸尺寸，所以拖动只能改自己的 margin（整屏覆盖会把页面点击全吃掉）。 */
    private fun margin(): LayoutParams? = layoutParams as? LayoutParams

    fun moveBy(dx: Int, dy: Int) {
        val lp = margin() ?: return
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return
        lp.leftMargin = (lp.leftMargin + dx).coerceIn(0, (parentW - width).coerceAtLeast(0))
        lp.topMargin = (lp.topMargin + dy).coerceIn(0, (parentH - height).coerceAtLeast(0))
        layoutParams = lp
    }

    fun snapToEdge() {
        val lp = margin() ?: return
        val parent = parent as? View ?: return
        lp.leftMargin = if (lp.leftMargin + width / 2 < parent.width / 2) 0
        else (parent.width - width).coerceAtLeast(0)
        lp.topMargin = lp.topMargin.coerceIn(0, (parent.height - height).coerceAtLeast(0))
        layoutParams = lp
        MusicSources.setBarPos(context, lp.leftMargin, lp.topMargin)
    }

    private fun restorePosition() {
        val lp = margin() ?: return
        val x = MusicSources.barX(context)
        val y = MusicSources.barY(context)
        if (x >= 0 && y >= 0) {
            lp.leftMargin = x
            lp.topMargin = y
            layoutParams = lp
            return
        }
        // 第一次出现：还没存过位置。量完自己的尺寸才能算，放到贴着导航栏上方的中间
        post {
            val p = margin() ?: return@post
            val parent = parent as? View ?: return@post
            if (MusicSources.barX(context) >= 0) {
                return@post
            }
            p.leftMargin = ((parent.width - width) / 2).coerceAtLeast(0)
            p.topMargin = (parent.height - height - with(resources.displayMetrics) {
                (88 * density).toInt()
            }).coerceAtLeast(0)
            p.leftMargin = p.leftMargin.coerceIn(0, (parent.width - width).coerceAtLeast(0))
            layoutParams = p
        }
    }

    private fun setMini(value: Boolean) {
        mini.value = value
        MusicSources.setBarCollapsed(context, value)
        post { snapToEdge() }      // 尺寸变了，贴边要等新尺寸量完再算
    }

    /** 贴边收纳：整条只露一根发光边。碰它就回到小圆，位置不动。 */
    private fun setPeek(value: Boolean) {
        peek.value = value
        MusicSources.setBarPeek(context, value)
        post { snapToEdge() }
    }

    @Composable
    private fun Pill(backdrop: Backdrop) {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        DisposableEffect(Unit) {
            val l = MusicCtl.Listener { tick++ }
            MusicCtl.addListener(l)
            onDispose { MusicCtl.removeListener(l) }
        }
        val stamp = tick
        val show = MusicSources.barPolicy(ctx).show
        // 判定从「不画」翻成「画」时（比如从通知栏点了播放），要让外面把抓帧循环开起来
        LaunchedEffect(show) { refresh() }
        if (!show) {
            return
        }
        if (peek.value) {
            PeekPill(backdrop)
        } else if (mini.value) {
            MiniPill(backdrop, stamp)
        } else {
            LyricPill(backdrop, stamp)
        }
    }

    /** 收纳态：一根 14dp 的发光边。点它 = 出来（回小圆），拖它 = 也出来。 */
    @Composable
    private fun PeekPill(backdrop: Backdrop) {
        MwGlassPill(
            backdrop = backdrop,
            modifier = Modifier.size(width = 14.dp, height = 62.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { setPeek(false) }, onLongPress = { setPeek(false) })
                },
            radiusDp = 100.dp,
            onClick = { setPeek(false) },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.size(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f)))
            }
        }
    }

    @Composable
    private fun MiniPill(backdrop: Backdrop, stamp: Int) {
        MwGlassPill(
            backdrop = backdrop,
            modifier = Modifier.size(56.dp)
                .pointerInput(Unit) {
                    var moved = 0f
                    val slop = 8.dp.toPx()     // PointerInputScope 本身就是 Density
                    detectDragGestures(
                        onDragEnd = { if (moved >= slop) snapToEdge() },
                        onDrag = { change, drag ->
                            change.consume()
                            moved += abs(drag.x) + abs(drag.y)
                            moveBy(drag.x.roundToInt(), drag.y.roundToInt())
                        },
                    )
                }
                // 点 = 展开成歌词胶囊；长按 = 贴边收纳（两个手势分开挂，拖动吃掉 slop 之后的事件）
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { setMini(false) },
                        onLongPress = { setPeek(true) },
                    )
                },
            radiusDp = 100.dp,
            onClick = null,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Ring(50.dp)
                Icon(
                    imageVector = if (MusicCtl.playing()) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    /**
     * 歌词胶囊：封面 + 歌名/歌手 + 实时歌词（逐行上滑）+ 可拖动进度 + 一排图标键。
     *
     * <p>进度不再等 500ms 心跳：以上次上报为基准、用本地时钟插值，每 80ms 推一帧；
     * 拖动进度条时用 preview 位置，松手才真正 seek。拖动整条（标题区）仍然是挪位置。
     */
    @Composable
    private fun LyricPill(backdrop: Backdrop, stamp: Int) {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(nowMod()) }
        // 80ms 一帧只驱动本地插值；MusicCtl 的 500ms 心跳负责校正基准
        LaunchedEffect(Unit) {
            while (true) {
                tick = nowMod()
                delay(80)
            }
        }
        val reported = MusicCtl.positionMs()
        val duration = MusicCtl.durationMs()
        val playing = MusicCtl.playing()
        var basePos by remember { mutableIntStateOf(reported) }
        var baseAt by remember { mutableIntStateOf(nowMod()) }
        LaunchedEffect(stamp, reported, duration, playing) {
            basePos = reported
            baseAt = nowMod()
        }
        val smooth = if (playing) {
            (basePos + (tick - baseAt)).coerceIn(0, if (duration > 0) duration else Int.MAX_VALUE)
        } else basePos

        var scrubbing by remember { mutableStateOf(false) }
        var scrubPos by remember { mutableIntStateOf(0) }
        var trackW by remember { mutableIntStateOf(1) }
        val shown = if (scrubbing) scrubPos else smooth
        val line = MusicCtl.lyricLineAt(shown)

        MwGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(316.dp),
            radiusDp = 24.dp,
            onClick = null,
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        // 只让标题这一行承担「拖动挪位置」，进度条/按钮各自有自己的手势
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { snapToEdge() },
                                onDrag = { change, drag ->
                                    change.consume()
                                    moveBy(drag.x.roundToInt(), drag.y.roundToInt())
                                },
                            )
                        },
                ) {
                    Disc(34.dp, if (duration > 0) shown.toFloat() / duration else 0f)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = MusicCtl.title().ifEmpty { "未播放" },
                            style = MwType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (MusicCtl.artist().isNotEmpty()) {
                            BasicText(
                                text = MusicCtl.artist(),
                                style = MwType.caption.copy(fontSize = 10.5.sp,
                                    color = Color.White.copy(alpha = 0.58f)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                            .clickable { setMini(true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "收起", tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                AnimatedContent(
                    targetState = line,
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically { -it / 2 } + fadeOut())
                    },
                    label = "lyric",
                ) { value ->
                    BasicText(
                        text = value.ifEmpty { "暂无歌词" },
                        style = MwType.body.copy(
                            fontSize = if (value.isEmpty()) 12.sp else 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = if (value.isEmpty()) 0.42f else 0.96f),
                        ),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(9.dp))

                Box(
                    modifier = Modifier.fillMaxWidth().height(26.dp)
                        .onSizeChanged { trackW = it.width.coerceAtLeast(1) }
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            detectDragGestures(
                                onDragStart = { off ->
                                    scrubbing = true
                                    scrubPos = ((off.x / trackW).coerceIn(0f, 1f) * duration).toInt()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    scrubPos = ((change.position.x / trackW).coerceIn(0f, 1f) * duration).toInt()
                                },
                                onDragEnd = {
                                    scrubbing = false
                                    MusicCtl.seekTo(ctx, scrubPos)
                                },
                                onDragCancel = { scrubbing = false },
                            )
                        }
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            detectTapGestures { off ->
                                MusicCtl.seekTo(ctx, ((off.x / trackW).coerceIn(0f, 1f) * duration).toInt())
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cy = size.height / 2f
                        val f = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        drawLine(Color.White.copy(alpha = 0.20f), Offset(0f, cy),
                            Offset(size.width, cy), 3.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = 0.92f), Offset(0f, cy),
                            Offset(size.width * f, cy), 3.dp.toPx(), StrokeCap.Round)
                        val x = (size.width * f).coerceIn(6.dp.toPx(), size.width - 6.dp.toPx())
                        drawCircle(Color.White, 5.dp.toPx(), Offset(x, cy))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = clock(shown),
                        style = MwType.caption.copy(fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.55f)),
                    )
                    Spacer(Modifier.weight(1f))
                    IconAction(Icons.Rounded.SkipPrevious, "上一首", 22.dp) { MusicCtl.prev(ctx) }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable { MusicCtl.toggle(ctx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White, modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconAction(Icons.Rounded.SkipNext, "下一首", 22.dp) { MusicCtl.next(ctx) }
                    Spacer(Modifier.weight(1f))
                    BasicText(
                        text = clock(duration),
                        style = MwType.caption.copy(fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.55f)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape)
                            .clickable { MusicCtl.stop(ctx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, "停止", tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }

    /** 小圆形"唱片"：外圈进度 + 内圈柔光，不依赖网络封面。 */
    @Composable
    private fun Disc(diameter: androidx.compose.ui.unit.Dp, progress: Float) {
        Canvas(Modifier.size(diameter)) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f)),
                    center = center, radius = r,
                ),
                radius = r - 2.dp.toPx(),
            )
            drawCircle(Color.White.copy(alpha = 0.10f), radius = 3.2.dp.toPx(), center = center)
            drawCircle(Color.White.copy(alpha = 0.22f), radius = r - 1.5.dp.toPx(),
                style = Stroke(1.2.dp.toPx()))
            drawArc(Color.White.copy(alpha = 0.9f), -90f, 360f * progress.coerceIn(0f, 1f), false,
                size = Size((r - 1.5.dp.toPx()) * 2, (r - 1.5.dp.toPx()) * 2),
                topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }

    @Composable
    private fun IconAction(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        size: androidx.compose.ui.unit.Dp,
        onTap: () -> Unit,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size))
        }
    }

    private fun nowMod(): Int = (SystemClock.elapsedRealtime() and 0x3FFFFFFF).toInt()

    /** 悬浮条上的进度：mm:ss，超过一小时就直接分。 */
    private fun clock(ms: Int): String {        if (ms <= 0) {
            return "0:00"
        }
        val s = ms / 1000
        return (s / 60).toString() + ":" + String.format("%02d", s % 60)
    }

    /** 一圈进度：小圆和长条共用，比例直接取 MusicCtl 的位置/时长。 */
    @Composable
    private fun Ring(diameter: androidx.compose.ui.unit.Dp) {
        Canvas(Modifier.size(diameter)) {
            val d = size.minDimension - 2.dp.toPx()
            val top = (size.minDimension - d) / 2f
            val left = top
            drawArc(Color.White.copy(alpha = 0.24f), 0f, 360f, false,
                size = Size(d, d), topLeft = Offset(left, top),
                style = Stroke(width = 2.dp.toPx()))
            val total = MusicCtl.durationMs()
            val done = MusicCtl.positionMs()
            if (total > 0) {
                drawArc(Color.White.copy(alpha = 0.9f), -90f, 360f * done / total, false,
                    size = Size(d, d), topLeft = Offset(left, top),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
