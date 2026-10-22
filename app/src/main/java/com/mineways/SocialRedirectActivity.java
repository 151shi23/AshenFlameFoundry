package com.mineways;

import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 浏览器授权后的回跳落点（清单里那条 {@code mineways://login} 的 intent-filter）。
 *
 * <p>它只做三件事：把回跳地址交给 {@link SocialLogin}、显示一行「正在完成登录」、然后自己退场。
 * 真正的换票与存身份都在 {@code SocialLogin} 的后台线程里，登录面板注册的回调会负责刷新界面 ——
 * 所以这里不需要、也不应该再建第二套状态。
 */
public class SocialRedirectActivity extends AppCompatActivity {

    private static final int BG = 0xFF000000, PANEL = 0xCC15171A, TEXT = 0xFFEDEDED;

    private TextView tv;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        Uri data = getIntent() == null ? null : getIntent().getData();
        String url = data == null ? "" : data.toString();
        if (url.length() == 0) {
            show("回跳是空的 —— 这次登录没完成，回 App 重新点一次登录。");
            scheduleFinish(3000L);
            return;
        }
        show("已收到" + SocialLogin.label(SocialLogin.pendingScope()) + "的回跳，正在换取登录状态…");
        SocialLogin.handleRedirect(url, "系统浏览器回跳");
        scheduleFinish(6000L);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);

        tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(TEXT);
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(dp(22), dp(22), dp(22), dp(22));
        tv.setText("正在完成登录…");
        GradientDrawable d = new GradientDrawable();
        d.setColor(PANEL);
        d.setCornerRadius(dp(16));
        tv.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        root.addView(tv, lp);

        TextView back = new TextView(this);
        back.setTextSize(14);
        back.setTextColor(TEXT);
        back.setPadding(dp(20), dp(14), dp(20), dp(14));
        back.setText("回到 App");
        GradientDrawable b = new GradientDrawable();
        b.setColor(0x33FFFFFF);
        b.setCornerRadius(dp(999));
        back.setBackground(b);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        root.addView(back, blp);
        return root;
    }

    private void show(String s) {
        if (tv != null) {
            tv.setText(s);
        }
    }

    /** 自己退场，别让用户停在一个空白页上；换票还在跑也没关系，它在 Service 侧的线程上。 */
    private void scheduleFinish(long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, delayMs);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
