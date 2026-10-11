package com.mineways;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * OAuth 授权登录（Mode B）：用户在论坛生成 6 位授权码 → 抄进来 → 换 token → 拉资料。
 */
public final class OAuthDialog {

    public interface Callback {
        void onLoggedIn(OAuthStore.Account account);
    }

    private OAuthDialog() {
    }

    public static void show(final Activity activity, final Callback cb) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final int BG = 0xFF15171A, LINE = 0x1FFFFFFF, TEXT = 0xFFEDEDED, DIM = 0xFF8A9099, ACCENT = 0xFFD9603A;
        int pad = dp(activity, 20);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(activity, 16));
        bg.setStroke(1, LINE);
        root.setBackground(bg);

        TextView head = new TextView(activity);
        head.setText("登录 AshenFlame Foundry");
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(TEXT);
        root.addView(head);

        TextView tip = new TextView(activity);
        tip.setText("① 复制下面的 API Key → 打开论坛 → 设置 → 授权应用\n"
                + "② 在授权页填入 API Key → 生成 6 位授权码\n"
                + "③ 把授权码粘到下面 → 登录\n\n"
                + "授权码一次性、5 分钟内有效；App 只拿基本资料（用户名 / 头像 / 注册时间）。");
        tip.setTextSize(12);
        tip.setTextColor(DIM);
        tip.setLineSpacing(dp(activity, 4), 1f);
        tip.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
        root.addView(tip);

        TextView open = new TextView(activity);
        open.setText("打开论坛授权页 → " + OAuthClient.authorizePage());
        open.setTextSize(12);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setTextColor(ACCENT);
        open.setPadding(0, 0, 0, dp(activity, 12));
        open.setClickable(true);
        open.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(OAuthClient.authorizePage())));
            } catch (Throwable ignored) {
            }
        });
        root.addView(open);

        // 推荐路线：App 内打开论坛授权页，登录后自动取码并兑换（不用手抄）
        TextView oneTap = textBtn(activity, "▶ 在 App 内一键授权登录（推荐，不用抄码）", ACCENT);
        oneTap.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(activity, OAuthWebActivity.class));
                dialog.dismiss();
            } catch (Throwable t) {
                Toast.makeText(activity, "打不开内置授权页：" + t, Toast.LENGTH_LONG).show();
            }
        });
        root.addView(oneTap);

        TextView note = new TextView(activity);
        note.setText("手动抄码路线注意：网页生成的码是「待确认」状态，必须先去论坛「授权应用 → 待确认请求」"
                + "点一次『确认』，码才有效（否则会提示“授权码尚未被确认”）。");
        note.setTextSize(11.5f);
        note.setTextColor(0xFF6B7280);
        note.setLineSpacing(dp(activity, 4), 1f);
        note.setPadding(0, dp(activity, 6), 0, dp(activity, 10));
        root.addView(note);

        // 网页生成授权码要求提供「被授权应用的 API Key」→ 这里直接给出，点一下即复制
        LinearLayout apiKeyBox = ApiKeyView.create(activity);
        if (apiKeyBox != null) {
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            klp.bottomMargin = dp(activity, 12);
            root.addView(apiKeyBox, klp);
        }

        final EditText input = new EditText(activity);
        input.setHint("输入授权码（如 ABC123）");
        input.setHintTextColor(0xFF5C6268);
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setLetterSpacing(0.15f);
        // 不用 CAP_CHARACTERS：论坛的授权码可能区分大小写，键盘自动大写会把正确的码改坏
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFF0E0F11);
        fieldBg.setCornerRadius(dp(activity, 10));
        fieldBg.setStroke(1, LINE);
        input.setBackground(fieldBg);
        input.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView status = new TextView(activity);
        status.setTextSize(12);
        status.setTextColor(0xFFFF8A80);
        status.setPadding(0, dp(activity, 8), 0, 0);
        status.setVisibility(View.GONE);
        status.setTextIsSelectable(true);      // 诊断信息可直接长按复制
        root.addView(status);

        // 只在失败后出现：服务端若区分大小写，这里显式用大写再试一次（不做自动重试，免得把码打废）
        final TextView retryUpper = textBtn(activity, "用大写再试一次", DIM);
        retryUpper.setVisibility(View.GONE);
        root.addView(retryUpper);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(activity, 16), 0, 0);

        TextView paste = textBtn(activity, "粘贴", DIM);
        paste.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(activity);
                    input.setText(s == null ? "" : OAuthClient.normalizeCode(s.toString()));
                }
            } catch (Throwable ignored) {
            }
        });
        row.addView(paste);

        final TextView submit = textBtn(activity, "登录", ACCENT);
        submit.setPadding(dp(activity, 20), dp(activity, 8), 0, dp(activity, 8));
        row.addView(submit);
        root.addView(row);

        TextView cancel = textBtn(activity, "取消", DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(activity, 12), 0, 0);
        cancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        submit.setOnClickListener(v -> {
            final String code = OAuthClient.normalizeCode(input.getText().toString());
            if (!OAuthClient.looksLikeCode(code)) {
                show(status, "授权码格式不对：应为 4~32 位字母/数字（可含连字符），不要带空格与中文");
                return;
            }
            login(activity, dialog, submit, status, retryUpper, code, false, cb);
        });
        retryUpper.setOnClickListener(v -> {
            final String code = OAuthClient.normalizeCode(input.getText().toString());
            if (OAuthClient.looksLikeCode(code)) {
                login(activity, dialog, submit, status, retryUpper, code, true, cb);
            }
        });

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /**
     * 真正发起兑换（原文一次；按钮触发时才用大写再试）。
     * 失败时把服务端的原话 + HTTP 状态 + 原始响应一起显示出来，方便一眼定位。
     */
    private static void login(final Activity activity, final Dialog dialog, final TextView submit,
                              final TextView status, final TextView retryUpper, final String code,
                              final boolean upper, final Callback cb) {
        submit.setEnabled(false);
        submit.setText("登录中…");
        status.setVisibility(View.GONE);
        retryUpper.setVisibility(View.GONE);
        new Thread(() -> {
            // 第 1 步：先把码提交给论坛（unused → pending，会出现在「待确认请求」里）。
            // 服务端若已把生成的码直接标为 confirmed，这一步会失败 —— 忽略，继续兑换即可。
            final OAuthClient.Token sub = upper ? null : OAuthClient.submitCode(code);
            final OAuthClient.Token t = upper
                    ? OAuthClient.exchangeTokenUpper(code)
                    : OAuthClient.exchangeToken(code);
            final OAuthClient.User u = t.ok ? OAuthClient.userInfo(t.accessToken) : null;
            activity.runOnUiThread(() -> {
                submit.setEnabled(true);
                submit.setText("登录");
                if (t.ok) {
                    OAuthStore.save(activity, t.accessToken, u);
                    String name = (u != null && u.username.length() > 0) ? u.username : "已登录";
                    Toast.makeText(activity, "登录成功：" + name, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    cb.onLoggedIn(OAuthStore.load(activity));
                    return;
                }
                if (t.networkError) {
                    show(status, "网络异常，连不上论坛：" + t.message);
                    return;
                }
                String subInfo = "";
                if (sub != null) {
                    subInfo = "\n〔第 1 步 submit〕" + (sub.ok ? "已提交，码进入待确认"
                            : ("失败：" + sub.message + " （HTTP " + sub.httpStatus + "）"));
                }
                show(status, OAuthClient.friendlyError(t.code, t.message, t.httpStatus, t.rawBody)
                        + subInfo
                        + "\n〔本次提交原文〕" + code + (upper ? "（已转大写）" : ""));
                if (!upper) {
                    retryUpper.setVisibility(View.VISIBLE);
                }
            });
        }, "oauth-login").start();
    }

    private static void show(TextView tv, String text) {
        tv.setText(text);
        tv.setVisibility(View.VISIBLE);
    }

    private static TextView textBtn(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color);
        tv.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        tv.setClickable(true);
        return tv;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
