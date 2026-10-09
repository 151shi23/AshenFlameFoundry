package com.mineways

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mineways.glass.GlassBottomBar
import com.mineways.glass.GlassNavController
import com.mineways.glass.ViewBackdrop
import kotlin.math.abs

/**
 * 底部导航栏外壳：对外接口基本不变，里面全是 Compose —— 玻璃由 io.github.kyant0:backdrop
 * （玄戒工具箱用的那个库）绘制。
 *
 * 三件事都在这里兜住：
 *  1. 长按 / 拖动：触摸在 View 层接管（栏里有原生 View，会先吃掉触摸），再驱动 Compose 弹簧动画；
 *  2. 实时渲染：Choreographer 每帧（60fps）抓一次背景快照 + 重画玻璃，跑在 TRAVERSAL 之前；
 *  3. 模糊：库的 blur / lens / vibrancy 直接作用在这张快照 + 栏自身的图标文字层上。
 *
 * 注意：标签格由 Compose 画（不是原生 View）—— 只有画在 Compose 层里，滑块的玻璃才能
 * 把图标和文字录进背景，拖动时边缘扫过它们才会有折射与模糊。
 */
class LiquidGlassNav(context: Context) : FrameLayout(context) {

    fun interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val titles = mutableStateListOf<String>()
    private val icons = mutableStateListOf<Int>()
    private val accentColor = mutableIntStateOf(0xFFD9603A.toInt())
    private val dimColor = mutableIntStateOf(0xFF5C6268.toInt())
    private val selectedIndex = mutableIntStateOf(0)
    private val composeView = ComposeView(context)
    private val controller = GlassNavController()
    private var onTabSelectedListener: OnTabSelectedListener? = null
    private var backdropSource: View? = null

    // 玻璃背景：每帧把内容快照成一张离屏位图（0.5 倍缩放，只取栏周围一条）
    private var backdropBitmap: Bitmap? = null
    private var bitmapOrigin = Offset.Zero
    private val navLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    // 长按 / 拖动
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = false
    private var downX = 0f
    private var lastX = 0f
    private val longPressRunnable = Runnable {
        if (!dragging) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            controller.press()          // 长按 = 稳稳捏住（缩放 + 透镜 + 内阴影）
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) {
                return
            }
            // 抓帧发生在 TRAVERSAL 之前：先快照内容，再让玻璃按同一帧重画 —— 真·实时
            if (isShown) {
                snapshotBackdrop()
                composeView.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        composeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        val backdrop = ViewBackdrop(
            bitmap = { backdropBitmap },
            bitmapOriginInWindow = { bitmapOrigin },
            bitmapScale = { SNAPSHOT_SCALE },
            // 库要在栏外一圈取像素做折射/模糊：这里的单位是像素，必须按密度换算，
            // 否则 3x 屏上 48px 只剩 16dp，比快照的 48dp 窄得多，折射边缘会糊成暗缝
            paddingPx = PADDING_DP * resources.displayMetrics.density
        )
        composeView.setContent {
            GlassBottomBar(
                titles = titles,
                iconRes = icons,
                selectedIndex = selectedIndex.intValue,
                backdrop = backdrop,
                accentColor = Color(accentColor.intValue),
                dimColor = Color(dimColor.intValue),
                onSelect = { index -> onTabSelectedListener?.onTabSelected(index) },
                controller = controller,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 9.dp
                    )
            )
        }
        addView(composeView)
    }

    fun setOnTabSelectedListener(listener: OnTabSelectedListener?) {
        onTabSelectedListener = listener
    }

    /** 标签数据：图标资源 + 标题，交给 Compose 画。 */
    fun setTabs(iconRes: IntArray, tabTitles: Array<String>) {
        icons.clear()
        icons.addAll(iconRes.toList())
        titles.clear()
        titles.addAll(tabTitles.toList())
    }

    /** 选中色（强调色）/ 未选中色。 */
    fun setColors(accent: Int, dim: Int) {
        accentColor.intValue = accent
        dimColor.intValue = dim
    }

    /** 兼容旧调用：只给强调色。 */
    fun setAccentColor(accent: Int) {
        accentColor.intValue = accent
    }

    /** 玻璃后面要采样的那块内容（现在是承载 NavHost 的 FrameLayout）。 */
    fun setBackdropSource(content: View?) {
        backdropSource = content
        composeView.invalidate()
    }

    fun setSelected(index: Int, animate: Boolean) {
        selectedIndex.intValue = index
    }

    /** 内容变了（切页、滚动）→ 让玻璃重新采一次背后的画面。 */
    fun refreshBackdrop() {
        composeView.invalidate()
    }

    // ------------------------------------------------------------ 手势
    //
    // 必须在 dispatchTouchEvent 里抢，不能用 onInterceptTouchEvent：
    // ComposeView 会消费 DOWN 并让父级不要拦截，走 intercept 就收不到 MOVE —— 那正是
    // "只能点击、拖不动"的原因。父级永远最先拿到事件，所以在这里接管最稳。

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downX = ev.x
                lastX = ev.x
                controller.press()      // 一按就有反馈
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    cancelChildren(ev)  // 手势被接管，给子 View 补个 CANCEL
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
            }
        }

        if (dragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    controller.dragBy(dx)       // 滑块跟手（60fps 那边同步重画玻璃）
                }

                MotionEvent.ACTION_UP -> {
                    dragging = false
                    controller.settle(true)     // 落到最近一格并切页
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    controller.settle(true)
                }
            }
            return true
        }

        val handled = super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                controller.release()
                val index = tabIndexAt(ev.x)
                if (index != selectedIndex.intValue) {
                    onTabSelectedListener?.onTabSelected(index)
                }
            }

            MotionEvent.ACTION_CANCEL -> controller.release()
        }
        return handled || ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean = true

    /** 手势被我们从子 View 手里接管时，补一个 CANCEL，免得它以为手指还在。 */
    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** 屏幕 x（本 View 内）→ 第几格。栏左右各留 12dp、内部再留 4dp。 */
    private fun tabIndexAt(xInNav: Float): Int {
        val count = titles.size
        if (count == 0) {
            return 0
        }
        val inset = 16f * resources.displayMetrics.density
        val usable = width - inset * 2f
        if (usable <= 0f) {
            return 0
        }
        return ((xInNav - inset) / (usable / count)).toInt().coerceIn(0, count - 1)
    }

    // ------------------------------------------------------------ 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        composeView.setPadding(0, 0, 0, bottomInset())
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        composeView.setPadding(0, 0, 0, bottomInset())
    }

    /** 手势条 / 三键导航的高度，避免胶囊被系统条压住。 */
    private fun bottomInset(): Int {
        val insets = ViewCompat.getRootWindowInsets(this) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
    }

    // ------------------------------------------------------------ 背景快照

    /**
     * 把内容视图（栏周围一条）软件快照成位图。
     *
     * 只在帧与帧之间调用（Choreographer 回调），**绝不能在 onDraw 里调**：那时整棵 View 树正在录
     * DisplayList，再进去会抛 "Recording currently in progress"。软件 Canvas 不走 RenderNode，
     * 所以安全；失败就退化成"只有底色"，不会把应用带崩。
     */
    private fun snapshotBackdrop() {
        val source = backdropSource ?: return
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) {
            return
        }
        if (!source.isAttachedToWindow) {
            return
        }
        try {
            val density = resources.displayMetrics.density
            val padPx = PADDING_DP * density
            getLocationInWindow(navLocation)
            source.getLocationInWindow(sourceLocation)
            val bitmapWidth = ((width + padPx * 2f) * SNAPSHOT_SCALE).toInt().coerceAtLeast(1)
            val bitmapHeight = ((height + padPx * 2f) * SNAPSHOT_SCALE).toInt().coerceAtLeast(1)
            var bitmap = backdropBitmap
            if (bitmap == null || bitmap.isRecycled ||
                bitmap.width != bitmapWidth || bitmap.height != bitmapHeight
            ) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                backdropBitmap = bitmap
            }
            bitmap.eraseColor(0)
            val canvas = Canvas(bitmap)
            canvas.scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE)
            canvas.translate(
                -(navLocation[0] - sourceLocation[0] - padPx),
                -(navLocation[1] - sourceLocation[1] - padPx)
            )
            source.draw(canvas)
            bitmapOrigin = Offset(navLocation[0] - padPx, navLocation[1] - padPx)
        } catch (t: Throwable) {
            backdropBitmap?.recycle()
            backdropBitmap = null
        }
    }

    private companion object {
        /** 快照缩放：0.5 倍够用（后面还要模糊），CPU 省一半。 */
        const val SNAPSHOT_SCALE = 0.5f

        /** 快照向外多取一圈，给折射/模糊留采样余量（dp）。 */
        const val PADDING_DP = 48f
    }
}
