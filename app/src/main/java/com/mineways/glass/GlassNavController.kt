package com.mineways.glass

/**
 * 触摸在 View 层、动画在 Compose 层 —— 这个小对象把两边接起来。
 *
 * 为什么触摸必须放在 View 层：标签格是原生 View（AndroidView 嵌进 Compose），
 * 安卓的 View 会先消费触摸事件，Compose 的 pointerInput 收不到，
 * 所以长按/拖动得由 LiquidGlassNav 自己 onInterceptTouchEvent 接管。
 */
class GlassNavController {

    internal var pressAction: () -> Unit = {}
    internal var releaseAction: () -> Unit = {}
    internal var dragAction: (Float) -> Unit = {}
    internal var settleAction: (Boolean) -> Unit = {}
    internal var indexAction: (Float) -> Int = { 0 }

    /** 按下：滑块放大 + 内阴影/高光起来 */
    fun press() = pressAction()

    /** 抬手/取消：恢复 */
    fun release() = releaseAction()

    /** 拖动增量（像素，正右负左）：滑块跟手 */
    fun dragBy(dxPx: Float) = dragAction(dxPx)

    /** 松手：dragged=true 按落点切页；false 表示这是一次点击，交给格子自己的点击监听 */
    fun settle(dragged: Boolean) = settleAction(dragged)

    /** 屏幕 x → 第几格 */
    fun indexAt(xPx: Float): Int = indexAction(xPx)
}
