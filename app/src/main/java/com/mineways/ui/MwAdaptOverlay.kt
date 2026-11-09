package com.mineways.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.mineways.glass.ViewBackdrop

/**
 * 「第一次下发 JS」的告知弹窗（液态玻璃）。
 *
 * 为什么单独一份而不是 AlertDialog：AlertDialog 是涂在半透明黑上的一块板，看不出背后是什么；
 * 真玻璃要折射背后的页面，就得把下面那块视图按帧快照喂给 backdrop ——
 * 与软件内玻璃悬浮条、玻璃导航栏同一条路子（[ViewBackdrop]），不是新发明的第二套玻璃。
 *
 * 触发只在 Java 侧一句话：[show] 成功返回 true，返回 false 时调用方自己退回普通对话框
 * （没有玻璃档、或者快照起不来时就不该硬撑）。
 */
class MwAdaptOverlay private constructor(
        context: Activity,
        private val source: View?,
        private val title: String,
        private val body: String,
        private val onAgree: Runnable,
        private val onDecline: Runnable,
) : FrameLayout(context) {

    private val composeView = ComposeView(context)
    private val text = mutableStateOf(title to body)
    private var backdropBitmap: Bitmap? = null
    private var bitmapOrigin = Offset.Zero
    private val here = IntArray(2)
    private val there = IntArray(2)
    private val scale = 0.75f
    private val padPx = 48f * resources.displayMetrics.density
    private var looping = false

    init {
        clipChildren = false
        clipToPadding = false
        val backdrop = ViewBackdrop(
                bitmap = { backdropBitmap },
                bitmapOriginInWindow = { bitmapOrigin },
                bitmapScale = { scale },
                paddingPx = padPx,
        )
        composeView.setContent { Panel(backdrop) }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 弹窗期间画面还在动（游戏 60fps），所以按帧抓；关掉就停，别留一个空转的回调。 */
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping || !isAttachedToWindow) {
                stopLoop()
                return
            }
            snapshot()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) {
            return
        }
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopLoop() {
        looping = false
        try {
            Choreographer.getInstance().removeFrameCallback(frame)
        } catch (t: Throwable) {
            // 没排上过就没有移除的必要
        }
    }

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

    @Composable
    private fun Panel(backdrop: Backdrop) {
        val tier = MwDevice.tierWithCloud(context)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center) {
            MwGlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    tier = tier,
                    emphasize = true,
                    radiusDp = 22.dp,
            ) {
                Column(Modifier.padding(PaddingValues(20.dp, 18.dp, 20.dp, 14.dp))) {
                    BasicText(text.value.first, style = MwType.title)
                    Spacer(Modifier.height(8.dp))
                    BasicText(text.value.second, style = MwType.caption)
                    Spacer(Modifier.height(14.dp))
                    Row2(
                            onAgreeLabel = "知道了",
                            onDeclineLabel = "关闭 JS 功能",
                            agree = { dismiss(); onAgree.run() },
                            decline = { dismiss(); onDecline.run() },
                    )
                }
            }
        }
    }

    @Composable
    private fun Row2(onAgreeLabel: String, onDeclineLabel: String, agree: () -> Unit, decline: () -> Unit) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            Clickable(onAgreeLabel, agree, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Clickable(onDeclineLabel, decline, Modifier.weight(1f))
        }
    }

    @Composable
    private fun Clickable(label: String, onTap: () -> Unit, mod: Modifier) {
        Box(mod.background(Color(0x2EFFFFFF), RoundedCornerShape(12.dp)).clickable { onTap() },
                contentAlignment = Alignment.Center) {
            BasicText(label, style = MwType.caption, modifier = Modifier.padding(vertical = 10.dp))
        }
    }

    private fun dismiss() {
        stopLoop()
        backdropBitmap?.recycle()
        backdropBitmap = null
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {
        /**
         * 弹出来就返回 true（调用方不用再走普通对话框）。
         * 起不来的情况（拿不到内容根视图、没有玻璃档、构图抛错）返回 false，让调用方降级。
         */
        @JvmStatic
        fun show(act: Activity, title: String, body: String, onAgree: Runnable, onDecline: Runnable): Boolean {
            return try {
                val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return false
                val src = if (content.childCount > 0) content.getChildAt(0) else null
                val ov = MwAdaptOverlay(act, src, title, body, onAgree, onDecline)
                content.addView(ov, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                ov.startLoop()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}
