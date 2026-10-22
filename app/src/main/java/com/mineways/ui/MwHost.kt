package com.mineways.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Java 侧把 Compose 界面挂上去的唯一入口。
 *
 * 走的是这个 App 里已经验证过的路子：底部玻璃导航栏（{@link com.mineways.LiquidGlassNav}）
 * 就是「View 壳里塞一个 ComposeView」，它不手动设任何 owner —— 因为 ComposeView 找宿主环境时
 * 会沿父链往上找，而页面本来就挂在 Activity 的视图树里。照抄这个成熟做法，不引入新依赖，
 * 也不必为 API 30 以下写一套降级（那正是"ComposeView requires a ViewTreeLifecycleOwner"
 * 崩溃最容易冒出来的地方）。
 */
object MwHost {

    /** 页签屏（全部 / 免费 / 白名单功能 / 限制）。 */
    @JvmStatic
    fun tabView(context: Context, index: Int, bottomPadDp: Int): ComposeView =
            view(context, bottomPadDp) { host, pad -> MwTabScreen(host, index, pad) }

    /** 关于页。 */
    @JvmStatic
    fun aboutView(context: Context, bottomPadDp: Int): ComposeView =
            view(context, bottomPadDp) { host, pad -> MwAboutScreen(host, pad) }

    private fun view(context: Context, bottomPadDp: Int,
            content: @Composable (MwUiHost, Dp) -> Unit): ComposeView {
        val host = context as? MwUiHost
                ?: throw IllegalStateException("宿主 Activity 必须实现 MwUiHost")
        val view = ComposeView(context)
        // 玻璃导航栏悬浮在内容之上，页面要能滚到它下面那片空白才算到底
        view.clipToPadding = false
        view.setContent { content(host, bottomPadDp.dp) }
        return view
    }
}
