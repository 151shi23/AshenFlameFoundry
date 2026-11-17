package com.mineways;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 编辑器类页面的独占全屏：状态栏与导航栏都收掉，网页铺满整块屏幕。
 *
 * <p>关键是行为选 {@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} —— 栏是**藏起来**而不是**禁掉**：
 * 从屏幕边缘划一下会短暂浮出，系统返回手势照样能用。用户要的是「手势返回主界面」，
 * 把栏彻底锁死反而会让返回键消失。
 *
 * <p>抄的是本仓 Mineimator 页已经跑通的那套（API 30+ 走 WindowInsetsController，
 * 老系统退回 SYSTEM_UI_FLAG_IMMERSIVE_STICKY），不另发明一套。
 */
final class EditorImmersive {

    private EditorImmersive() {
    }

    static void hideBars(Activity a) {
        if (a == null || a.getWindow() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = a.getWindow().getDecorView().getWindowInsetsController();
            if (c == null) {
                return;
            }
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            return;
        }
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
