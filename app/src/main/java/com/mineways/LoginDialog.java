package com.mineways;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.mineways.cloud.LoginPolicy;

import java.util.List;

/**
 * 统一的登录入口 —— 关于页只有<b>一行</b>「登录」，点开这里。
 *
 * <p>以前关于页并排两行（社区授权登录 / 白名单账号登录），两个按钮各说各的登录态，
 * 用户要自己猜「点哪个才算是登录成功了」。现在三档身份（论坛 / Google / 微软）在同一张面板里，
 * 每档一行：状态、登录、退出，一目了然；点登录之后才给这一档自己的方式。
 *
 * <p>面板不做判定 —— 谁是白名单、哪个功能要哪一档，全在 {@link LoginPolicy} 与云控里。
 * 这里只负责把 {@link Identities} 的现状摆出来。
 */
public final class LoginDialog {

    private static final int PANEL = 0xFF15171A, TEXT = 0xFFEDEDED, DIM = 0xFF9AA1AB,
            ACCENT = 0xFFD9D9D9;

    /** 登录态变了（成功或退出），界面要重建一次。 */
    public interface Changed {
        void onLoginChanged();
    }

    private LoginDialog() {
    }

    public static void show(final Activity a, final Changed cb) {
        if (a == null || a.isFinishing()) {
            return;
        }
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 6));

        TextView head = new TextView(a);
        head.setTextSize(17);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(TEXT);
        head.setText("登录");
        root.addView(head);

        TextView sub = new TextView(a);
        sub.setTextSize(12);
        sub.setTextColor(DIM);
        sub.setLineSpacing(dp(a, 3), 1f);
        sub.setPadding(0, dp(a, 8), 0, dp(a, 14));
        sub.setText("用哪一种方式登录都行。功能要不要登录、要不要在名单里，由云端配置逐条规定；"
                + "现在这一版给 " + LoginAccess.gatedCount() + " 个功能配了登录门禁。\n"
                + LoginAccess.debugLine(a));
        root.addView(sub);

        for (String scope : LoginPolicy.SCOPES) {
            root.addView(row(a, scope, cb));
        }

        if (Identities.hasAny(a)) {
            TextView out = btn(a, "全部退出", false);
            out.setOnClickListener(v -> {
                Identities.logoutAll(a);
                toast(a, "已退出全部登录");
                close(cb, a);
            });
            root.addView(wrap(a, out));
        }

        new AlertDialog.Builder(a)
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 一档一行：档名 + 现状 + 登录 / 退出。 */
    private static View row(Activity a, String scope, Changed cb) {
        Identities.Account acc = Identities.of(a, scope);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        line.setBackground(round(a, PANEL, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        line.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(a);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(TEXT);
        name.setText(LoginPolicy.scopeCn(scope));
        texts.addView(name);

        TextView st = new TextView(a);
        st.setTextSize(11);
        st.setTextColor(DIM);
        st.setPadding(0, dp(a, 3), 0, 0);
        st.setText(acc.live()
                ? ("已登录：" + acc.display() + (hits(a, scope) ? " · 已在名单里" : " · 还没进名单"))
                : SocialLogin.statusOf(a, scope));
        texts.addView(st);
        line.addView(texts);

        if (acc.live()) {
            line.addView(btn(a, "退出", false, v -> {
                Identities.logout(a, scope);
                toast(a, "已退出" + LoginPolicy.scopeCn(scope));
                close(cb, a);
            }));
        } else {
            line.addView(btn(a, "登录", true, v -> enter(a, scope, cb)));
        }
        return line;
    }

    /** 这一档点「登录」之后走哪条路。 */
    private static void enter(Activity a, String scope, Changed cb) {
        if (LoginPolicy.FORUM.equals(scope)) {
            forumChooser(a, cb);
            return;
        }
        if (!SocialLogin.available(scope)) {
            toast(a, SocialLogin.cfg(scope).whyNot());
            return;
        }
        SocialLogin.start(a, scope, (ok, s, msg) -> {
            toast(a, (ok ? "登录成功：" : "没成：") + msg);
            if (ok) {
                close(cb, a);
            }
        });
    }

    /**
     * 论坛档的两种方式（都在同一个入口里，不再各占关于页一行）：
     * ① 授权码 / 一键授权（论坛 OAuth，能同步社区身份）；② 账号密码或邮件验证码。
     *
     * <p>注意这里以前崩过一次：点按钮时先 {@code close(cb)} 再 {@code show(子对话框)}，
     * 而 {@code cb} 会 {@code recreate()} 掉整个 Activity —— 窗口 token 当场失效，表现就是「一点就闪退」。
     * 现在只关自己的面板，重建界面交给<b>登录成功</b>那条回调。
     */
    private static void forumChooser(final Activity a, final Changed cb) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 6));
        TextView t = new TextView(a);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setText("论坛登录");
        box.addView(t);
        TextView s = new TextView(a);
        s.setTextSize(12);
        s.setTextColor(DIM);
        s.setPadding(0, dp(a, 6), 0, dp(a, 12));
        s.setText("授权码登录能带回头像与注册时间；只注册过、没设过密码的账号用「验证码登录」。");
        box.addView(s);

        // 先拿住 holder：按钮的点击回调要在对话框创建之前写好，dismiss 只能延后引用
        final AlertDialog[] self = new AlertDialog[1];
        box.addView(btn(a, "授权码 / 一键授权", true, v -> {
            dismiss(self);
            OAuthDialog.show(a, account -> close(cb, a));
        }, true));
        box.addView(btn(a, "账号密码 / 邮件验证码", true, v -> {
            dismiss(self);
            ProLoginDialog.show(a, record -> close(cb, a));
        }, true));
        self[0] = new AlertDialog.Builder(a).setView(box).setNegativeButton("返回", null).show();
    }

    private static void dismiss(AlertDialog[] d) {
        if (d[0] != null && d[0].isShowing()) {
            d[0].dismiss();
        }
    }

    // ============================================================ 小件

    private static boolean hits(Activity a, String scope) {
        return LoginAccess.hitsList(a, scope);
    }

    private static void close(Changed cb, Activity a) {
        if (a != null && !a.isFinishing()) {
            a.runOnUiThread(() -> {
                if (cb != null) {
                    cb.onLoginChanged();
                }
            });
        }
    }

    private static TextView btn(Activity a, String text, boolean accent) {
        return btn(a, text, accent, null, false);
    }

    private static TextView btn(Activity a, String text, boolean accent,
            android.view.View.OnClickListener click) {
        return btn(a, text, accent, click, false);
    }

    private static TextView btn(Activity a, String text, boolean accent,
            android.view.View.OnClickListener click, boolean fullWidth) {
        TextView b = new TextView(a);
        b.setTextSize(13);
        b.setTextColor(accent ? 0xFF101010 : TEXT);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
        b.setBackground(round(a, accent ? ACCENT : 0x33FFFFFF, 999));
        if (click != null) {
            b.setOnClickListener(click);
        }
        if (fullWidth) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(a, 8);
            b.setLayoutParams(lp);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(a, 10);
            b.setLayoutParams(lp);
        }
        return b;
    }

    private static View wrap(Activity a, TextView b) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 6);
        box.setLayoutParams(lp);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(b);
        return box;
    }

    private static GradientDrawable round(Activity a, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a, radiusDp));
        return d;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static void toast(Activity a, String s) {
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 便于状态面板复用：现在的身份摘要（与关于页那一行同一份数据）。 */
    public static String summary(Activity a) {
        List<String> s = new java.util.ArrayList<>();
        for (Identities.Account acc : Identities.all(a)) {
            s.add(LoginPolicy.scopeCn(acc.scope) + (acc.live() ? "√" : "×"));
        }
        return String.join(" ", s);
    }
}
