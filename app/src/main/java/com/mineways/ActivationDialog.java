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
 * 激活码流程（改写版）：
 *
 * <pre>
 *   输入码 → check（只读，不锁定）→ 有效则把「码」存到本地（长期有效，直到使用为止）
 *   点「开始使用」→ verify（锁定）→ 执行功能 → 成功 confirm（扣）/ 失败 release（不扣）
 * </pre>
 *
 * token 只在使用过程中存在，绝不长期保存；confirm/release 先落待补发记录，防秒退漏账。
 */
public final class ActivationDialog {

    public interface Callback {
        /** 本地已有可用激活记录（新存或早就存好），可以进入「使用」流程。 */
        void onReady();
    }

    private ActivationDialog() {
    }

    /**
     * 确保本地有可用记录：有 → 直接回调；没有 → 弹窗让人输入。
     *
     * @param featureKey 功能标识（用图标 key，如 png2model）
     */
    public static void ensureReady(final Activity activity, final String title,
                                   final String featureKey, final Callback cb) {
        ActivationStore.Record rec = ActivationStore.load(activity, featureKey);
        if (rec.usable()) {
            cb.onReady();
            return;
        }
        show(activity, title, featureKey, cb);
    }

    /** 弹出输入框（只在本地没有可用记录时调用）。 */
    private static void show(final Activity activity, final String title,
                             final String featureKey, final Callback cb) {
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
        head.setText(title + " · 需要激活码");
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(TEXT);
        root.addView(head);

        TextView tip = new TextView(activity);
        tip.setText("① 打开社区网页 → 每日签到领 1 个激活码（管理员批量发的码也会自动发到你的账号）\n"
                + "② 粘到下面校验一次 → 以后**一直存在本机**，直到你真正使用为止\n\n"
                + "校验只做检查，不会扣次；真正使用成功才消耗 1 次，失败不扣。");
        tip.setTextSize(12);
        tip.setTextColor(DIM);
        tip.setLineSpacing(dp(activity, 4), 1f);
        tip.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
        root.addView(tip);

        TextView where = new TextView(activity);
        where.setText("还没激活码？点这里打开签到领码页 → " + ActivationConfig.CHECKIN_PAGE);
        where.setTextSize(12);
        where.setTypeface(Typeface.DEFAULT_BOLD);
        where.setTextColor(ACCENT);
        where.setLineSpacing(dp(activity, 4), 1f);
        where.setPadding(0, 0, 0, dp(activity, 12));
        where.setClickable(true);
        where.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(ActivationConfig.CHECKIN_PAGE)));
            } catch (Throwable ignored) {
            }
        });
        root.addView(where);

        // 网页生成激活码 / 授权码要求提供本应用 API Key → 这里给出可复制版本
        LinearLayout apiKeyBox = ApiKeyView.create(activity);
        if (apiKeyBox != null) {
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            klp.bottomMargin = dp(activity, 12);
            root.addView(apiKeyBox, klp);
        }

        final EditText input = new EditText(activity);
        input.setHint("粘贴激活码");
        input.setHintTextColor(0xFF5C6268);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setSingleLine(true);
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
        root.addView(status);

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
                    input.setText(s == null ? "" : s.toString().trim());
                }
            } catch (Throwable ignored) {
            }
        });
        row.addView(paste);

        final TextView submit = textBtn(activity, "校验并保存", ACCENT);
        submit.setPadding(dp(activity, 16), dp(activity, 8), 0, dp(activity, 8));
        row.addView(submit);
        root.addView(row);

        TextView cancel = textBtn(activity, "取消", DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(activity, 12), 0, 0);
        cancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        submit.setOnClickListener(v -> {
            // 先修格式：去空白、转大写、缺横杠的补成 5-5-5，并把规范形式回填到输入框
            final String code = ActivationClient.normalize(input.getText().toString());
            input.setText(code);
            input.setSelection(code.length());
            if (!ActivationClient.looksLikeCode(code)) {
                show(status, "激活码格式不正确，请检查是否复制完整（形如 ABCDE-FGHJK-LMNPQ）");
                return;
            }
            submit.setEnabled(false);
            submit.setText("校验中…");
            status.setVisibility(View.GONE);
            new Thread(() -> {
                final ActivationClient.Result r = ActivationClient.check(code);   // 只读，不锁定
                activity.runOnUiThread(() -> {
                    submit.setEnabled(true);
                    submit.setText("校验并保存");
                    if (r.ok) {
                        ActivationStore.save(activity, featureKey, code);
                        Toast.makeText(activity, "已保存到本机：使用时才会消耗 1 次",
                                Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                        cb.onReady();
                        return;
                    }
                    show(status, ActivationClient.friendlyError(r));
                });
            }, "activation-check").start();
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

    // ---------------------------------------------------------------- 使用阶段

    /**
     * 「开始使用」：verify（锁定）→ 返回 token 给调用方去执行功能。
     * 失败时给出提示；码已失效则清掉本地记录。
     */
    public static void beginUse(final Activity activity, final String featureKey, final UseCallback cb) {
        final ActivationStore.Record rec = ActivationStore.load(activity, featureKey);
        if (!rec.usable()) {
            toast(activity, "本机没有可用的激活码，请先签到领取");
            cb.onDenied();
            return;
        }
        new Thread(() -> {
            ActivationClient.Result r = ActivationClient.verify(rec.code);
            activity.runOnUiThread(() -> {
                if (r.ok && r.token.length() > 0) {
                    // 锁定成功的瞬间就落盘一条「待补发 = release」：
                    // 之后无论是正常完成（finish 覆盖成 confirm）、用户秒退、加载界面被杀，
                    // 下次启动 flushPending 都能把这次锁定释放掉，不会卡在「激活码使用中」。
                    ActivationStore.setPending(activity, "release", featureKey, r.token);
                    cb.onToken(r.token);
                    return;
                }
                if (r.networkError) {
                    toast(activity, "网络异常，暂时无法核销，请稍后重试");
                    cb.onDenied();
                    return;
                }
                // 码本身作废了（不存在 / 已被用 / 被管理员作废 / 格式不对）→ 清本地，让他重新领；
                // 注意 LOCK_IN_PROGRESS 不清：那只是别人/上次操作没结束，10 分钟后这码还能用
                if ("CODE_NOT_FOUND".equals(r.code) || "ALREADY_REDEEMED".equals(r.code)
                        || "CODE_REVOKED".equals(r.code) || "INVALID_FORMAT".equals(r.code)) {
                    ActivationStore.clear(activity, featureKey);
                }
                toast(activity, ActivationClient.friendlyError(r));
                cb.onDenied();
            });
        }, "activation-verify").start();
    }

    public interface UseCallback {
        void onToken(String operationToken);

        void onDenied();
    }

    /**
     * 功能结束后：成功 confirm（扣 1 次）/ 失败 release（不扣）。
     * 先落待补发记录再发请求 —— 用户秒退也不会漏账。
     */
    public static void finish(final Activity activity, final String featureKey,
                              final String token, final boolean success) {
        if (token == null || token.length() == 0) {
            return;
        }
        final String action = success ? "confirm" : "release";
        ActivationStore.setPending(activity, action, featureKey, token);
        new Thread(() -> {
            ActivationClient.Result r = success
                    ? ActivationClient.confirm(token)
                    : ActivationClient.release(token, "feature failed or cancelled");
            // 成功 or 服务端明确拒绝（幂等重复）→ 结束待办；纯网络失败 → 留着下次补
            if (r.ok || !r.networkError) {
                ActivationStore.clearPending(activity);
            }
            if (success && r.ok) {
                ActivationStore.clear(activity, featureKey);   // 这 1 次机会用掉了
            }
        }, "activation-finish").start();
    }

    /** 兼容旧调用名。 */
    public static void finishAsync(Activity activity, String token, boolean success) {
        finish(activity, "", token, success);
    }

    private static void show(TextView tv, String text) {
        tv.setText(text);
        tv.setVisibility(View.VISIBLE);
    }

    private static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
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
