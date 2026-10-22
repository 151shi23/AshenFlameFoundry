package com.mineways.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.mineways.cloud.CloudCatalog
import com.mineways.glass.ViewBackdrop

/**
 * 给原生 View 工具页套上「悬浮玻璃顶栏」—— 二十多个页面一次换上同一套新结构。
 *
 * 做法与底部导航栏同一套机制（那条栏已经在真机跑通，不另起炉灶）：
 *  1. 页面原来的根 View 塞进 [MwGlassRoot]（FrameLayout），顶栏作为它的兄弟浮在上方；
 *  2. 根 View 顶部补一段 padding = 顶栏高度并 clipToPadding=false，内容滚动时就从玻璃下面
 *     穿过去 —— 层次感正是来自这里；
 *  3. 顶栏反射的背景 = 页面内容每帧的软件快照（[ViewBackdrop]）：它不是纯色半透明，
 *     而是真把滚过去的字与图标折射了一遍。
 *
 * 用软件快照而不是 Compose 的 layerBackdrop，是因为页面主体是原生 View，Compose 抓不到它们；
 * `source.draw(canvas)` 走软件 Canvas 不写 DisplayList，不会撞
 * "Recording currently is in progress"（导航栏那边踩过这个坑，注释就在那儿）。
 *
 * 三条兜底：
 *  · 云控 `ui_page_glass=0`、或设备没有 RenderEffect（Android 12 以下）→ 不套顶栏，
 *    页面退回 [MwViews] 的平面重写，照常可用（顶栏里那颗返回胶囊已经删掉了，返回交给系统返回键）；
 *  · 页面不是「单一根 View」（自己叠了浮层）或根就是 WebView → 不动它，返回 false 让调用方兜底；
 *  · 剪辑 / Blockbench / 小游戏这类自绘界面在 [SKIP] 名单里，不套顶栏。
 */
class MwPageGlass private constructor(private val act: Activity) {

    /** 套好了 true；false 表示这个页面没被改写过。 */
    fun attach(): Boolean {
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content !is FrameLayout || content.childCount != 1) {
            return false
        }
        // 有的页面会再 setContentView 一次（WebTool 重载那种）：那时我们的容器被整块换掉了，
        // 标记还在但界面上没有玻璃顶栏 —— 所以这里以「当前有没有宿主」为准，不能只看标记。
        if (content.getChildAt(0) is MwGlassRoot) {
            return true
        }
        val body = content.getChildAt(0)
        if (body is android.webkit.WebView) {
            return false
        }
        val st = MwViews.style(act)
        // ui_glass=0 / ui_tier=flat / 系统没有 RenderEffect：一律不套玻璃顶栏
        if (!st.glass || !MwGlass.supported()
                || MwDevice.tierWithCloud(act) == MwTier.FLAT) {
            return false
        }
        if ("0" == CloudCatalog.gate().flag("ui_page_glass", "")) {
            return false
        }

        val host = MwGlassRoot(act)
        content.removeView(body)
        try {
            content.addView(host, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            host.bindBody(body)

            val title = pageTitle(act)
            val header = ComposeView(act)
            host.bindHeader(header)
            header.setContent {
                MwPageHeader(host.backdrop(), title, Color(st.accent))
            }
        } catch (t: Throwable) {
            // 铁律：包装失败必须把原页面原样还回去。body 已经被摘下来了，
            // 这里不还回去，用户看到的就是一张空页（白屏 / 深底）。
            Log.e("MwPageGlass", "attach failed on " + act.javaClass.simpleName, t)
            try {
                content.removeView(host)
                content.addView(body)
            } catch (ignored: Throwable) {
            }
            return false
        }
        return true
    }

    companion object {
        /**
         * 顶栏标题：清单里配的 android:label（ActivityInfo.loadLabel，取不到就用类名）。
         * 用 Activity.getLabel() 编不过 —— 那个方法在公开 SDK 里没有。
         */
        @JvmStatic
        fun pageTitle(act: Activity): String {
            val simple = act.javaClass.simpleName.removeSuffix("Activity")
            return try {
                val pm = act.packageManager
                val ai = pm.getActivityInfo(act.componentName, 0)
                val l = ai.loadLabel(pm)?.toString().orEmpty()
                if (l.isBlank()) simple else l
            } catch (t: Throwable) {
                simple
            }
        }

        /** 这些页面自己画界面，不套顶栏。 */
        private val SKIP = hashSetOf(
                "MainActivity", "OpenCutActivity", "BlockbenchActivity", "AiAnimActivity",
                "MiniGameActivity", "SnowstormActivity", "RhineLabActivity",
                "ObjPreviewActivity", "UpdateGateActivity", "CloudGateActivity")

        @JvmStatic
        fun allowed(act: Activity): Boolean = !SKIP.contains(act.javaClass.simpleName)

        @JvmStatic
        fun attach(act: Activity): Boolean = MwPageGlass(act).attach()
    }
}

/**
 * 页面容器：每帧把 body 顶部那一条画面快照成位图，交给顶栏玻璃去折射。
 *
 * 快照只在顶栏可见、尺寸就绪时做；画之前先 clipRect 到顶栏附近那一条，再按 0.45 倍缩放 ——
 * 后面本来就要模糊，精度用不上，CPU 直接省一半以上。
 */
class MwGlassRoot(context: Context) : FrameLayout(context) {

    private var body: View? = null
    private var header: View? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val locHost = IntArray(2)
    private val locBody = IntArray(2)
    private var running = false
    private var originX = 0f
    private var originY = 0f

    // 背板只在「画面真的变了」时才重画。原来每帧无条件把整棵 body 软件重画一遍 + invalidate
    // ComposeView，静置不动也在跑满 60fps 的快照 —— 真机反馈「骁龙 8 至尊版都觉得卡」的主因。
    // 现在：滚动/布局变化置脏，doFrame 只在脏的那一帧抓图，静置 = 零开销。
    private var dirty = true
    private val scrollPump = object : ViewTreeObserver.OnScrollChangedListener {
        override fun onScrollChanged() {
            dirty = true
        }
    }
    private val layoutPump = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                    ol: Int, ot: Int, orr: Int, ob: Int) {
            dirty = true
        }
    }

    /** 顶栏玻璃要的背板：位图 + 它在窗口里的原点 + 缩放比。 */
    fun backdrop(): Backdrop = ViewBackdrop(
            bitmap = { bitmap },
            bitmapOriginInWindow = { Offset(originX, originY) },
            bitmapScale = { SCALE },
            paddingPx = PADDING_DP * resources.displayMetrics.density)

    fun bindBody(v: View) {
        body = v
        val lp = v.layoutParams
        v.layoutParams = null
        addView(v, 0, if (lp == null) LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) else lp)
        if (v is ScrollView) {
            v.clipToPadding = false
        } else if (v is ViewGroup) {
            v.clipToPadding = false
        }
        v.addOnLayoutChangeListener(layoutPump)
        v.setOnScrollChangeListener { _, _, _, _, _ -> dirty = true }
        v.viewTreeObserver.addOnScrollChangedListener(scrollPump)
        dirty = true
    }

    fun bindHeader(v: View) {
        header = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val b = body ?: return@addOnLayoutChangeListener
            val extra = (bottom - top) + (8f * resources.displayMetrics.density).toInt()
            if (bottom > top && b.paddingTop != extra) {
                b.setPadding(b.paddingLeft, extra, b.paddingRight, b.paddingBottom)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) {
            running = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        body?.let { v ->
            v.removeOnLayoutChangeListener(layoutPump)
            v.viewTreeObserver.removeOnScrollChangedListener(scrollPump)
        }
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !isAttachedToWindow) {
                return
            }
            // 没变化就一帧都不做：不抓图、不重绘 ComposeView
            if (isShown && dirty) {
                dirty = false
                snapshot()
                header?.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 抓顶栏附近那一条（顶栏贴在宿主顶部）。 */
    private fun snapshot() {
        val src = body ?: return
        val h = header ?: return
        if (width <= 0 || h.height <= 0 || src.width <= 0 || src.height <= 0) {
            return
        }
        try {
            val padPx = PADDING_DP * resources.displayMetrics.density
            val bandH = h.height + 2f * padPx
            val bw = ((width + 2f * padPx) * SCALE).toInt().coerceAtLeast(1)
            val bh = (bandH * SCALE).toInt().coerceAtLeast(1)
            var bmp = bitmap
            if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            getLocationInWindow(locHost)
            src.getLocationInWindow(locBody)
            val left = (locHost[0] - locBody[0] - padPx)
            val top = (locHost[1] - locBody[1] - padPx)
            bmp.eraseColor(0)
            val c = Canvas(bmp)
            c.scale(SCALE, SCALE)
            c.clipRect(left, top, left + width + 2f * padPx, top + bandH)
            c.translate(-left, -top)
            src.draw(c)
            originX = locHost[0] - padPx
            originY = locHost[1] - padPx
        } catch (t: Throwable) {
            bitmap?.recycle()
            bitmap = null
        }
    }

    private companion object {
        /** 0.45 → 0.35：后面要模糊 + 折射，精度用不上，位图小一圈像素量省 ~40%。 */
        const val SCALE = 0.35f

        /** 快照向外多取一圈给折射留余量；顶栏贴页面顶部，40dp 已经够折射采样。 */
        const val PADDING_DP = 24f
    }
}

/**
 * 顶栏本体：只有标题芯片。
 *
 * 返回胶囊已经**去掉**：手机本来就有返回键/返回手势，悬浮在左上角那枚圆既挡内容又容易误触
 * （它压着的正是页面第一行操作），而它的行为与系统返回完全重合（都是 `onBackPressed()`，
 * 网页页 [com.mineways.WebToolActivity] 自己已经把返回键接成「页内后退，到底再退出」）。
 *
 * 右上角的「问 AI」也**去掉**（用户判定：挡空间又没用）—— AI 的入口就在首页右上角三个点，
 * 不再往每个功能页的顶栏里塞东西。
 */
@Composable
private fun MwPageHeader(bd: Backdrop, title: String, accent: Color) {
    // 顶栏这件也跟着机身走光；工具页主体是原生 View，流光就落在玻璃本身上。
    val tilt = rememberMwTilt()
    Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MwGlassPill(bd, Modifier.height(36.dp), tilt = tilt) {
            BasicText(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MwType.headline.copy(color = MwTokens.textPrimary))
        }
    }
}
