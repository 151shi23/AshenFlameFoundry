package com.mineways.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * 入场编排。
 *
 * 原来这里还有两件事，自检时按 Hallmark《Anti-patterns — the named tells》删了：
 *  · **MwAurora**（两团缓慢漂移的径向光斑）= Critical 级 "Aurora-blob background" +
 *    "Floating-orb decoration"，而且 `infiniteRepeatable` 还在 Never 清单里
 *    （"Infinite loops — they pull the eye and never let go"）。页面"活着"改由
 *    [rememberFlowBackdrop] 的**重力流光**负责：光随机身转，有物理理由，不是装饰。
 *  · **MwConfetti**（解锁放彩纸）= "Celebratory success toasts"，Emil 的频率表规定
 *    每天看几十上百次的动作不许加庆祝；登录成功本来就是常规状态变化。
 *
 * 节奏参数按 Emil 的规范：错峰 40ms（他的区间是 30–80ms），入场 240ms（UI 一律 <300ms），
 * 弹簧**临界阻尼不过冲**（bounce/elastic/overshoot 在清单里被点名为"模板味"），
 * 只动 transform/opacity（合成器属性，不触发布局）。
 */
@Composable
fun Modifier.mwEnter(order: Int, motion: Boolean): Modifier {
    if (!motion) {
        return this
    }
    val alpha = remember(order) { Animatable(0f) }
    val shift = remember(order) { Animatable(10f) }
    LaunchedEffect(order) {
        delay((order.coerceAtMost(9) * 40L))
        alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        shift.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 1.0f))
    }
    return this.then(Modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = shift.value
    })
}
