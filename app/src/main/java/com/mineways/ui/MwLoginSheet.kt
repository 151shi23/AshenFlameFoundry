package com.mineways.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.mineways.Identities
import com.mineways.LoginAccess
import com.mineways.OAuthDialog
import com.mineways.ProLoginDialog
import com.mineways.SocialLogin
import com.mineways.cloud.LoginPolicy

/**
 * 统一的登录面板（液态玻璃）。
 *
 * Java 侧那份 [com.mineways.LoginDialog] 只在云控把界面退回旧 View 界面（{@code ui_restyle=0}）时才用。
 * 平时是这一份：它挂在关于页自己的 Compose 树里，拿的是与顶栏**同一层** backdrop，
 * 所以折射的是背后真实的页面，而不是把面板涂成半透明黑 —— 那正是「看着不像玻璃」的根因。
 */
object MwLoginBus {
    /** Java 侧（关于页那一行 / 「需要登录」弹窗）只改这一个值，面板自己跟着动。 */
    @JvmField
    val open = mutableStateOf(false)
}

@Composable
fun MwLoginSheet(host: MwUiHost, spec: MwTokens.Spec, tier: MwTier, bd: Backdrop, tilt: MwTilt?) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val open = MwLoginBus.open.value
    val scrim by animateFloatAsState(if (open) 0.62f else 0f,
            tween(if (open) 200 else 150), label = "scrim")
    if (!open && scrim <= 0.002f) {
        return
    }
    val close: () -> Unit = { MwLoginBus.open.value = false }
    // 登录态一变就先收面板再重建界面：面板的开关是全局值，不先关掉的话 recreate 完它又亮起来
    val changed: () -> Unit = {
        MwLoginBus.open.value = false
        host.refreshAfterLogin()
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .pointerInput(open) { if (open) detectTapGestures(onTap = { close() }) })

        AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(190)) + slideInVertically(tween(240)) { it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 3 },
        ) {
            Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Bottom,
            ) {
                Spacer(Modifier.height(24.dp))
                // 整块只开一层真玻璃（与顶栏同层）：一行一张玻璃层就是当初 8 Elite 掉帧的成因
                MwGlassCard(
                        backdrop = bd,
                        modifier = Modifier.fillMaxWidth(),
                        tier = tier,
                        tilt = tilt,
                        emphasize = true,
                        radiusDp = spec.radius + 6.dp,
                ) {
                    Column(Modifier.padding(PaddingValues(20.dp, 18.dp, 20.dp, 16.dp))) {
                        BasicText("登录", style = MwType.title)
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                                "用哪一种方式登录都行。哪个功能要哪种身份、要不要在名单里，" +
                                        "由云端配置逐条规定。",
                                style = MwType.caption)
                        Spacer(Modifier.height(14.dp))
                        for (scope in LoginPolicy.SCOPES) {
                            LoginRow(tier, bd, tilt, scope, act, changed, close)
                        }
                        if (Identities.hasAny(ctx)) {
                            Spacer(Modifier.height(4.dp))
                            SheetButton(bd, tier, tilt, "全部退出", true, fill = true) {
                                Identities.logoutAll(ctx)
                                toast(act, "已退出全部登录")
                                changed()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/** 一档一行：身份名 + 现状 + 这一档自己的动作。 */
@Composable
private fun LoginRow(tier: MwTier, bd: Backdrop, tilt: MwTilt?,
        scope: String, act: Activity?, changed: () -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val acc = Identities.of(ctx, scope)
    val listed = LoginAccess.hitsList(ctx, scope)
    val forum = scope == LoginPolicy.FORUM
    val canSocial = !forum && SocialLogin.available(scope)

    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (acc.live()) MwTokens.textPrimary else MwTokens.textTertiary))
            Spacer(Modifier.width(9.dp))
            BasicText(LoginPolicy.scopeCn(scope), style = MwType.headline)
            if (acc.live()) {
                Spacer(Modifier.width(8.dp))
                MwBadge(if (listed) "已在名单" else "未进名单",
                        if (listed) MwTokens.okFree else MwTokens.limitAmber)
            }
        }
        Spacer(Modifier.height(3.dp))
        BasicText(
                if (acc.live()) "已登录：" + acc.display() else SocialLogin.statusOf(ctx, scope),
                style = MwType.caption)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                acc.live() && forum -> {
                    SheetButton(bd, tier, tilt, "退出", true) {
                        Identities.logout(ctx, scope)
                        toast(act, "已退出" + LoginPolicy.scopeCn(scope))
                        changed()
                    }
                    SheetButton(bd, tier, tilt, "再授权一次", true) {
                        if (act != null) OAuthDialog.show(act) { changed() }
                    }
                }
                acc.live() -> {
                    SheetButton(bd, tier, tilt, "退出", true) {
                        Identities.logout(ctx, scope)
                        toast(act, "已退出" + LoginPolicy.scopeCn(scope))
                        changed()
                    }
                    SheetButton(bd, tier, tilt, "换一个账号", true) {
                        if (act != null && SocialLogin.available(scope)) {
                            close()
                            SocialLogin.start(act, scope) { ok, _, msg ->
                                toast(act, (if (ok) "登录成功：" else "没成：") + msg)
                                if (ok) {
                                    changed()
                                }
                            }
                        }
                    }
                }
                forum -> {
                    SheetButton(bd, tier, tilt, "授权码 / 一键授权", true) {
                        if (act != null) OAuthDialog.show(act) { changed() }
                    }
                    SheetButton(bd, tier, tilt, "账号 / 验证码", true) {
                        if (act != null) ProLoginDialog.show(act) { changed() }
                    }
                }
                else -> {
                    SheetButton(bd, tier, tilt, "一键授权", canSocial) {
                        if (act == null) {
                            return@SheetButton
                        }
                        if (!canSocial) {
                            toast(act, SocialLogin.cfg(scope).whyNot())
                            return@SheetButton
                        }
                        close()
                        SocialLogin.start(act, scope) { ok, _, msg ->
                            toast(act, (if (ok) "登录成功：" else "没成：") + msg)
                            if (ok) {
                                changed()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 面板里的玻璃胶囊按钮。不可用时**只降亮度、不藏起来** —— 藏了用户不知道还有这一档，
 * 点了还要把原因（「还没下发 Client ID」）说出来，不能静默。
 */
@Composable
private fun SheetButton(bd: Backdrop, tier: MwTier, tilt: MwTilt?, text: String, enabled: Boolean,
        fill: Boolean = false, onClick: () -> Unit) {
    MwGlassPill(bd,
            modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
            tilt = tilt,
            tier = tier,
            radiusDp = 100.dp,
            onClick = if (enabled) onClick else null) {
        Box(Modifier.padding(horizontal = if (fill) 8.dp else 16.dp, vertical = 9.dp),
                contentAlignment = if (fill) Alignment.Center else Alignment.CenterStart) {
            BasicText(text, style = MwType.body.copy(
                    color = if (enabled) MwTokens.textPrimary else MwTokens.textTertiary))
        }
    }
}

private fun toast(act: Activity?, s: String) {
    if (act != null) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    }
}
