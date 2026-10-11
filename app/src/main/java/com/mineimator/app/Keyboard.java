package com.mineimator.app;

import android.app.Activity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * 软键盘桥：原生引擎通过 {@code Keyboard.setVisible(activity, show)} 唤出 / 收起输入法。
 * 方法名与签名不可改（JNI 按名字找静态方法 {@code setVisible(Landroid/app/Activity;Z)V}）。
 */
public final class Keyboard {

    public static final Keyboard INSTANCE = new Keyboard();

    private static View target;

    private Keyboard() {
    }

    public void attach(View view) {
        target = view;
    }

    public static void setVisible(final Activity activity, final boolean show) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                apply(activity, show);
            }
        });
    }

    private static void apply(Activity activity, boolean show) {
        final View view = target;
        if (view == null) {
            return;
        }
        final InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        if (show) {
            view.setFocusableInTouchMode(true);
            view.requestFocus();
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        } else {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
            view.setFocusableInTouchMode(false);
        }
    }
}
