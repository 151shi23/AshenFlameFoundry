package com.mineways;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 白名单账号登录弹窗，支持两种方式：
 *
 * <pre>
 *   ① 密码登录    邮箱 + 密码（老方式，论坛账密接口 auth/login-password）
 *   ② 验证码登录  邮箱 + 邮件验证码（auth/send-code → auth/verify）
 * </pre>
 *
 * <p>加 ② 是为了解决"只注册过、没设置过密码"的账号：这类账号用密码登录必然失败，
 * 也会被白名单按邮箱判成"不在名单里"。验证码登录里邮箱是用户自己填的、又用邮件验证码
 * 证明了归属，所以白名单按邮箱配置也能命中。</p>
 *
 * <p>细节：60 秒重发冷却（避免触发服务端限流）、邮箱本地格式预检、上次填的邮箱会记住、
 * 失败时把服务端原话直接显示出来（诊断信息可长按复制）。</p>
 */
public final class ProLoginDialog {

    public interface Callback {
        void onLoggedIn(ProAuth.Record record);
    }

    private static final String PREFS = "pro_login";
    private static final String K_EMAIL = "last_email";

    private static final int BG = 0xFF15171A;
    private static final int LINE = 0x1FFFFFFF;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8A9099;
    private static final int ACCENT = 0xFFD9603A;
    private static final int OK_GREEN = 0xFF7DE08A;
    private static final int ERR_RED = 0xFFFF8A80;

    private ProLoginDialog() {
    }

    public static void show(final Activity activity, final Callback cb) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final int pad = dp(activity, 20);
        final SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(activity, 16));
        bg.setStroke(dp(activity, 1), LINE);
        root.setBackground(bg);

        TextView head = new TextView(activity);
        head.setText("白名单账号登录");
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(TEXT);
        root.addView(head);

        TextView tip = new TextView(activity);
        tip.setText("用论坛账号登录，账号在授权名单里才会解锁白名单功能。\n"
                + "如果这个账号没设置过密码（只注册过 / 用第三方登录），请点上面的「验证码登录」。");
        tip.setTextSize(12);
        tip.setTextColor(DIM);
        tip.setLineSpacing(dp(activity, 4), 1f);
        tip.setPadding(0, dp(activity, 8), 0, dp(activity, 10));
        root.addView(tip);

        // ---- 方式切换 ----
        final boolean[] codeMode = {false};
        final TextView tabPw = tab(activity, "密码登录");
        final TextView tabCode = tab(activity, "验证码登录");
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, dp(activity, 10));
        tabs.addView(tabPw, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabCode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        // ---- 输入区 ----
        final EditText email = input(activity, "论坛邮箱（如 you@qq.com）", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String last = sp.getString(K_EMAIL, "");
        if (last.length() > 0) {
            email.setText(last);
            email.setSelection(last.length());
        }
        root.addView(email);

        final EditText pw = input(activity, "密码", true);
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pwLp.topMargin = dp(activity, 8);
        root.addView(pw, pwLp);

        final EditText code = input(activity, "邮件里的验证码", false);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        final TextView send = new TextView(activity);
        send.setText("获取验证码");
        send.setTextSize(13);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setTextColor(ACCENT);
        send.setPadding(dp(activity, 12), dp(activity, 10), 0, dp(activity, 10));
        send.setClickable(true);
        final LinearLayout codeRow = new LinearLayout(activity);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        codeLp.topMargin = dp(activity, 8);
        codeRow.addView(code, codeLp);
        codeRow.addView(send);
        root.addView(codeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView status = new TextView(activity);
        status.setTextSize(12);
        status.setTextColor(ERR_RED);
        status.setPadding(0, dp(activity, 10), 0, 0);
        status.setVisibility(View.GONE);
        status.setTextIsSelectable(true);       // 服务端原话可以长按复制
        root.addView(status);

        // ---- 按钮 ----
        final TextView submit = new TextView(activity);
        submit.setTextSize(15);
        submit.setTypeface(Typeface.DEFAULT_BOLD);
        submit.setTextColor(ACCENT);
        submit.setGravity(Gravity.CENTER);
        submit.setPadding(0, dp(activity, 14), 0, dp(activity, 6));
        submit.setClickable(true);
        root.addView(submit);

        TextView cancel = new TextView(activity);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(activity, 6), 0, 0);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancel);

        // ---- 60 秒重发冷却 ----
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] left = {0};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (left[0] <= 0) {
                    send.setText("获取验证码");
                    send.setEnabled(true);
                    return;
                }
                send.setText("重发 " + left[0] + "s");
                send.setEnabled(false);
                left[0]--;
                handler.postDelayed(this, 1000);
            }
        };

        // ---- 模式应用 ----
        final Runnable applyMode = new Runnable() {
            @Override
            public void run() {
                boolean cm = codeMode[0];
                pw.setVisibility(cm ? View.GONE : View.VISIBLE);
                codeRow.setVisibility(cm ? View.VISIBLE : View.GONE);
                tabPw.setTextColor(cm ? DIM : ACCENT);
                tabCode.setTextColor(cm ? ACCENT : DIM);
                submit.setText(cm ? "验证码登录" : "密码登录");
                status.setVisibility(View.GONE);
            }
        };
        tabPw.setOnClickListener(v -> {
            codeMode[0] = false;
            applyMode.run();
        });
        tabCode.setOnClickListener(v -> {
            codeMode[0] = true;
            applyMode.run();
            code.requestFocus();
        });
        applyMode.run();

        send.setOnClickListener(v -> {
            final String em = OAuthClient.normEmail(email.getText().toString());
            if (!looksLikeEmail(em)) {
                show(status, "邮箱格式不对，检查一下（要有 @ 和域名）", ERR_RED);
                return;
            }
            sp.edit().putString(K_EMAIL, em).apply();
            send.setEnabled(false);
            send.setText("发送中…");
            status.setVisibility(View.GONE);
            new Thread(() -> {
                final ProAuth.Result r = ProAuth.sendCode(em);
                activity.runOnUiThread(() -> {
                    if (r.ok) {
                        show(status, r.message, OK_GREEN);
                        left[0] = 60;
                        handler.post(tick);
                    } else {
                        send.setEnabled(true);
                        send.setText("获取验证码");
                        show(status, r.message, ERR_RED);
                    }
                });
            }, "pro-send-code").start();
        });

        submit.setOnClickListener(v -> {
            final String em = OAuthClient.normEmail(email.getText().toString());
            final String password = pw.getText().toString();
            final String cd = code.getText().toString().trim();
            if (!looksLikeEmail(em)) {
                show(status, "邮箱格式不对，检查一下（要有 @ 和域名）", ERR_RED);
                return;
            }
            if (codeMode[0] && cd.length() == 0) {
                show(status, "请填邮件里收到的验证码（没收到点「获取验证码」）", ERR_RED);
                return;
            }
            if (!codeMode[0] && password.length() == 0) {
                show(status, "请填密码；如果这个账号没有密码，点上面的「验证码登录」", ERR_RED);
                return;
            }
            sp.edit().putString(K_EMAIL, em).apply();
            submit.setEnabled(false);
            submit.setText("登录中…");
            status.setVisibility(View.GONE);
            new Thread(() -> {
                final ProAuth.Result r = codeMode[0]
                        ? ProAuth.loginByCode(em, cd)
                        : ProAuth.loginPassword(em, password);
                activity.runOnUiThread(() -> {
                    submit.setEnabled(true);
                    submit.setText(codeMode[0] ? "验证码登录" : "密码登录");
                    if (!r.ok) {
                        show(status, r.message, ERR_RED);
                        return;
                    }
                    ProAuth.save(activity, r.record);
                    ProAuth.reverifyInBackground(activity);
                    dialog.dismiss();
                    cb.onLoggedIn(r.record);
                });
            }, "pro-login").start();
        });

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ---------------------------------------------------------------- 小件

    /** 邮箱本地预检：有 @、@ 前后都有内容、域名里有点。 */
    static boolean looksLikeEmail(String email) {
        if (email == null) {
            return false;
        }
        String e = email.trim();
        int at = e.indexOf('@');
        if (at <= 0 || at != e.lastIndexOf('@') || at == e.length() - 1) {
            return false;
        }
        String domain = e.substring(at + 1);
        int dot = domain.indexOf('.');
        return dot > 0 && dot < domain.length() - 1 && e.indexOf(' ') < 0;
    }

    private static void show(TextView tv, String text, int color) {
        tv.setText(text);
        tv.setTextColor(color);
        tv.setVisibility(View.VISIBLE);
    }

    private static TextView tab(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        tv.setClickable(true);
        return tv;
    }

    private static EditText input(Context ctx, String hint, boolean password) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(0xFF5C6268);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setInputType(password
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFF0E0F11);
        fieldBg.setCornerRadius(dp(ctx, 10));
        fieldBg.setStroke(dp(ctx, 1), LINE);
        et.setBackground(fieldBg);
        et.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        return et;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
