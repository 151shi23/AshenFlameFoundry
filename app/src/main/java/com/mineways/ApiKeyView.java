package com.mineways;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「本应用 API Key」展示块（点一下即复制）。
 *
 * <p>社区站点（AshenFlame Foundry）生成授权码时，要求填写**需要被授权应用的 API Key** ——
 * 就是这里显示的 ak_… 这一串。用户从 App 复制、粘到网页输入框，即可拿到 6 位授权码。</p>
 *
 * <p>安全边界：这里只展示 <b>应用公钥</b>（{@code ak_…}，本身就是设计为可公开的客户端标识），
 * 绝不展示 {@code sk_…} appSecret / 服务端密钥。</p>
 */
public final class ApiKeyView {

    private static final int BG = 0xFF0E0F11;
    private static final int LINE = 0x1FFFFFFF;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8A9099;
    private static final int ACCENT = 0xFFD9603A;

    private ApiKeyView() {
    }

    /** 应用公钥（论坛授权页要填的那串）；未配置时返回空串。 */
    public static String apiKey() {
        try {
            String k = BuildConfig.AFF_OAUTH_APP_KEY;
            return k == null ? "" : k.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 是否可展示（配好了公钥）。 */
    public static boolean available() {
        return apiKey().length() > 0;
    }

    /**
     * 生成展示块。未配置 API Key 时返回 {@code null}，调用方判空跳过即可。
     */
    public static LinearLayout create(final Context ctx) {
        final String key = apiKey();
        if (key.length() == 0) {
            return null;
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(ctx, 10));
        bg.setStroke(1, LINE);
        box.setBackground(bg);
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 8));

        TextView label = new TextView(ctx);
        label.setText("本应用 API Key（网页生成授权码时填这个）");
        label.setTextSize(11);
        label.setTextColor(DIM);
        box.addView(label);

        TextView value = new TextView(ctx);
        value.setText(key);
        value.setTextSize(15);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setTextColor(TEXT);
        value.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        value.setTextIsSelectable(true);
        value.setOnClickListener(v -> copy(ctx, key, "API Key 已复制，去网页粘贴"));
        box.addView(value);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        TextView btn = new TextView(ctx);
        btn.setText("复制 API Key");
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(ACCENT);
        btn.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        btn.setClickable(true);
        btn.setOnClickListener(v -> copy(ctx, key, "API Key 已复制，去网页粘贴"));
        row.addView(btn);

        box.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** 写剪贴板 + 提示。 */
    public static void copy(Context ctx, String text, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("API Key", text));
            }
        } catch (Throwable ignored) {
        }
        try {
            Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 供外部（如 OAuthClient）取当前应用标识时复用。 */
    public static View attachTo(LinearLayout parent, Context ctx) {
        LinearLayout box = create(ctx);
        if (box != null) {
            parent.addView(box);
        }
        return box;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
