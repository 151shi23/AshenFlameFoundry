package com.mineways.prism;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mineways.LiquidGlassDrawable;
import com.mineways.ui.MwViews;

import java.util.List;

/**
 * 棱镜玩机自建弹窗（不用系统 AlertDialog）。
 *
 * <p>为什么必须自建：本工程的 Material3 主题没有给系统 AlertDialog 配列表项颜色，
 * {@code setItems()} 在暗色玻璃页上会出现「有弹窗、没有选项」——文字与背景同色。
 * 这里所有行、按钮、输入框都显式给色，选项永远看得见。
 *
 * <p>视觉跟首页/工具页一致：整块底走 {@link MwViews#glass} 的液态玻璃
 * （云控 {@code ui_glass=0} 时自动退化为平面），选项与按钮是独立的玻璃胶囊，
 * 有边光、折射与投影，不依赖系统 Dialog 主题。
 */
public final class PrismDialog {

    private static final int TEXT = 0xFFF2F3F5;
    private static final int DIM = 0xFF9A9DA6;
    private static final int DANGER = 0xFFFF6B6B;
    private static final int ON_LIGHT = 0xFF101114;

    /** 关窗后稍等再弹下一个，避免两个 Window 抢层级（表现就是新窗被压在旧窗下面）。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REOPEN_DELAY = 90L;

    private PrismDialog() {
    }

    public interface Action {
        void run();
    }

    public interface TextAction {
        void run(String text);
    }

    public static final class Item {
        public final String title;
        public final String subtitle;
        public final boolean danger;
        public final Action action;

        public Item(String title, String subtitle, boolean danger, Action action) {
            this.title = title;
            this.subtitle = subtitle;
            this.danger = danger;
            this.action = action;
        }

        public Item(String title, Action action) {
            this(title, null, false, action);
        }
    }

    // ---------------------------------------------------------------- 列表

    public static Dialog list(Activity a, String title, String message, List<Item> items) {
        return list(a, title, message, items, null, null);
    }

    public static Dialog list(Activity a, String title, String message, List<Item> items,
                              String neutral, final Action neutralAction) {
        LinearLayout root = base(a, title, message);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        if (!items.isEmpty()) {
            for (Item it : items) box.addView(optionRow(a, it));
        }

        // 先把内容全部装好，再 setContentView：避免个别 ROM 在 show 之后加子视图时
        // 只量到标题和底部按钮（表现就是“弹窗只有取消”）。
        if (!items.isEmpty()) {
            ScrollView sc = new ScrollView(a);
            sc.setVerticalScrollBarEnabled(false);
            sc.addView(box);
            int h = Math.min(dp(a, 380), Math.max(dp(a, 64), dp(a, 60) * items.size()));
            // 横屏/小屏时窗口高度只有 400dp 左右：不封顶会把操作项挤出屏幕，
            // 用户只看得见底部按钮，误以为「没有冻结这一项」。
            h = Math.min(h, bodyCap(a));
            root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        } else {
            root.addView(box);
        }

        final Dialog dlg = wrap(a);
        for (int i = 0; i < box.getChildCount() && i < items.size(); i++) {
            final Item it = items.get(i);
            box.getChildAt(i).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    afterDismiss(dlg, it.action);
                }
            });
        }

        // 空列表也要有出口，否则只能点空白关闭（个别 ROM 上点不出去）。
        String tail = neutral != null ? neutral : (items.isEmpty() ? "关闭" : null);
        if (tail != null) {
            root.addView(buttonRow(a, new String[]{tail}, new int[]{0},
                    new Action[]{new Action() {
                        @Override
                        public void run() {
                            afterDismiss(dlg, neutralAction);
                        }
                    }}));
        }
        return present(dlg, root);
    }

    /**
     * 先关窗、再执行动作。
     *
     * <p>之前是 {@code dismiss(); action.run()}：同一个点击里立刻 show 下一个 Dialog，
     * 新窗口有时会压在正在退场的旧窗口下面（用户只看到旧窗的「取消」）。挂到
     * OnDismiss 回调里，保证旧窗口真正销毁后再弹下一个。
     */
    private static void afterDismiss(final Dialog dlg, final Action action) {
        if (dlg == null) {
            if (action != null) action.run();
            return;
        }
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                if (action == null) return;
                // 延迟一帧多再弹：旧 Window 的退场动画还没走完就 show 新窗，
                // 部分 ROM 会把新窗排到下面，用户看到的是旧窗残留的按钮。
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        action.run();
                    }
                }, REOPEN_DELAY);
            }
        });
        dlg.dismiss();
    }

    // ---------------------------------------------------------------- 确认 / 提示

    public static Dialog confirm(Activity a, String title, String message, String okText, final Action ok) {
        return two(a, title, message, "取消", null, okText, ok, false);
    }

    public static Dialog confirmDanger(Activity a, String title, String message, String okText, final Action ok) {
        return two(a, title, message, "取消", null, okText, ok, true);
    }

    public static Dialog info(Activity a, String title, String message, String actionLabel, final Action action) {
        return two(a, title, message, actionLabel == null ? "关闭" : "关闭", null,
                actionLabel, action, false);
    }

    private static Dialog two(Activity a, String title, String message,
                              String negLabel, final Action neg,
                              String posLabel, final Action pos, boolean danger) {
        LinearLayout root = base(a, title, message);
        final Dialog dlg = wrap(a);
        if (posLabel == null) {
            root.addView(buttonRow(a, new String[]{negLabel}, new int[]{0},
                    new Action[]{new Action() {
                        @Override
                        public void run() {
                            afterDismiss(dlg, neg);
                        }
                    }}));
        } else {
            root.addView(buttonRow(a, new String[]{negLabel, posLabel},
                    new int[]{0, danger ? 1 : 2},
                    new Action[]{
                            new Action() {
                                @Override
                                public void run() {
                                    afterDismiss(dlg, neg);
                                }
                            },
                            new Action() {
                                @Override
                                public void run() {
                                    afterDismiss(dlg, pos);
                                }
                            }}));
        }
        return present(dlg, root);
    }

    // ---------------------------------------------------------------- 输入

    public static Dialog input(Activity a, String title, String message, String hint,
                               String prefill, boolean number, String okText, final TextAction ok) {
        LinearLayout root = base(a, title, message);
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(prefill == null ? "" : prefill);
        input.setSelection(input.getText().length());
        input.setTextColor(TEXT);
        input.setHintTextColor(0xFF6E727B);
        input.setTextSize(14f);
        input.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        input.setBackground(glass(a, 14, 0));
        input.setInputType(number
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED)
                : InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(a, 4);
        root.addView(input, ilp);

        final Dialog dlg = wrap(a);
        root.addView(buttonRow(a, new String[]{"取消", okText}, new int[]{0, 2},
                new Action[]{
                        new Action() {
                            @Override
                            public void run() {
                                afterDismiss(dlg, null);
                            }
                        },
                        new Action() {
                            @Override
                            public void run() {
                                final String s = input.getText().toString().trim();
                                afterDismiss(dlg, new Action() {
                                    @Override
                                    public void run() {
                                        if (ok != null) ok.run(s);
                                    }
                                });
                            }
                        }}));
        return present(dlg, root);
    }

    // ---------------------------------------------------------------- 自定义内容

    public static Dialog content(Activity a, String title, String message, View body,
                                 String posLabel, final Action pos,
                                 String neutralLabel, final Action neutral,
                                 String negLabel, final Action neg) {
        LinearLayout root = base(a, title, message);
        if (body != null) root.addView(body);
        final Dialog dlg = wrap(a);
        java.util.List<String> labels = new java.util.ArrayList<String>();
        java.util.List<Integer> styles = new java.util.ArrayList<Integer>();
        java.util.List<Action> actions = new java.util.ArrayList<Action>();
        if (negLabel != null) {
            labels.add(negLabel);
            styles.add(0);
            actions.add(new Action() {
                @Override
                public void run() {
                    afterDismiss(dlg, neg);
                }
            });
        }
        if (neutralLabel != null) {
            labels.add(neutralLabel);
            styles.add(0);
            actions.add(new Action() {
                @Override
                public void run() {
                    afterDismiss(dlg, neutral);
                }
            });
        }
        if (posLabel != null) {
            labels.add(posLabel);
            styles.add(2);
            actions.add(new Action() {
                @Override
                public void run() {
                    afterDismiss(dlg, pos);
                }
            });
        }
        if (!labels.isEmpty()) {
            root.addView(buttonRow(a, labels.toArray(new String[0]),
                    toIntArray(styles), actions.toArray(new Action[0])));
        }
        return present(dlg, root);
    }

    // ---------------------------------------------------------------- 结构

    private static LinearLayout base(Activity a, String title, String message) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 18), dp(a, 18), dp(a, 18), dp(a, 16));
        // 液态玻璃：与首页/工具页同一套（云控 ui_glass=0 时自动退化）
        root.setBackground(MwViews.glass(a, MwViews.style(a), 26, 0xFF141821, root));

        TextView head = new TextView(a);
        head.setText(title == null ? "" : title);
        head.setTextColor(TEXT);
        head.setTextSize(16.5f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(head);

        if (!TextUtils.isEmpty(message)) {
            TextView msg = new TextView(a);
            msg.setText(message);
            msg.setTextColor(DIM);
            msg.setTextSize(12.5f);
            msg.setLineSpacing(dp(a, 4), 1f);
            msg.setTextIsSelectable(true);
            msg.setPadding(0, dp(a, 8), 0, dp(a, 4));
            // 长文本（诊断结果 / 授权步骤）必须能滚：否则横屏只有 400dp 高，
            // 正文会把下面的选项与按钮顶出屏幕。
            if (message.split("\n", -1).length > 6) {
                ScrollView ms = new ScrollView(a);
                ms.setVerticalScrollBarEnabled(false);
                ms.addView(msg);
                int mh = Math.min(dp(a, 150), Math.max(dp(a, 72), bodyCap(a) / 2));
                root.addView(ms, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, mh));
            } else {
                root.addView(msg);
            }
        }
        return root;
    }

    /**
     * 弹窗正文可用高度上限：按屏幕高度的 60% 算，横屏（约 430dp 高）时约 258dp。
     * 标题 + 按钮 + 内边距另占约 120dp，整体不会超出屏幕。
     */
    private static int bodyCap(Activity a) {
        int h = a.getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(a, 120), Math.min((int) (h * 0.60f), dp(a, 460)));
    }

    private static View optionRow(Activity a, Item it) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        // 选项行用「半透明玻璃色 + 边光」而不是折射 Drawable：
        // 选项必须 100% 可见，折射/阴影只留给整块底与按钮。
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0x26FFFFFF);
        rbg.setCornerRadius(dp(a, 14));
        rbg.setStroke(Math.max(1, dp(a, 1)), 0x33FFFFFF);
        row.setBackground(rbg);
        row.setMinimumHeight(dp(a, 52));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        row.setLayoutParams(lp);

        TextView t = new TextView(a);
        t.setText(it.title == null ? "" : it.title);
        t.setTextColor(it.danger ? DANGER : 0xFFFFFFFF);
        t.setTextSize(15f);
        row.addView(t);

        if (!TextUtils.isEmpty(it.subtitle)) {
            TextView sub = new TextView(a);
            sub.setText(it.subtitle);
            sub.setTextColor(DIM);
            sub.setTextSize(11.5f);
            sub.setPadding(0, dp(a, 4), 0, 0);
            row.addView(sub);
        }
        return row;
    }

    private static View buttonRow(Activity a, String[] labels, int[] styles, final Action[] actions) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(a, 8);
        row.setLayoutParams(rlp);

        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            final Action act = actions[i];
            TextView b = new TextView(a);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(14f);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
            int style = i < styles.length ? styles[i] : 0;
            if (style == 2) {
                b.setTextColor(ON_LIGHT);
                b.setBackground(glass(a, 22, 0xFFEDEFF3));
            } else if (style == 1) {
                b.setTextColor(0xFFFFFFFF);
                b.setBackground(glass(a, 22, 0xFFD24747));
            } else {
                b.setTextColor(TEXT);
                b.setBackground(glass(a, 22, 0));
            }
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (act != null) act.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(a, 8);
            row.addView(b, lp);
        }
        return row;
    }

    /**
     * 只建窗口，不装内容。所有子视图（尤其是底部按钮）必须先 addView 完，
     * 再 setContentView + show：个别 ROM 在 setContentView 之后加子视图会漏测高度，
     * 表现就是“弹窗只剩标题和取消，操作项不见了”。
     */
    private static Dialog wrap(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.55f;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        return d;
    }

    private static Dialog present(Dialog d, LinearLayout root) {
        d.setContentView(root);
        d.show();
        return d;
    }

    // ---------------------------------------------------------------- 小件

    private static Drawable glass(Context c, int radiusDp, int tint) {
        if (tint == 0) return new LiquidGlassDrawable(dp(c, radiusDp), 0, c.getResources().getDisplayMetrics().density);
        return new LiquidGlassDrawable(dp(c, radiusDp), tint, c.getResources().getDisplayMetrics().density);
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
