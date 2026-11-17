package com.mineways.assets;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 素材下载/更新的进度弹窗。
 *
 * <p>一百多 MB 的下载只给一条细进度条，用户不知道还要等多久；这里把百分比放到最大，
 * 下面依次是「已下 / 总量 · 速度 · 剩余」，再下面一行写阶段（下载 / 校验 / 解压）。
 * 首装和更新共用同一个弹窗。
 *
 * <p>所有方法都要在主线程调用（调用方在 runOnUiThread 里更新）。
 */
public final class PackProgressDialog {

    private final AlertDialog dialog;
    private final TextView percentView;
    private final TextView detailView;
    private final TextView stageView;
    private final ProgressBar bar;

    private long winStartAt;
    private long winStartBytes;
    private long lastAt;
    private double speed = 0;

    private PackProgressDialog(AlertDialog dialog, TextView percentView,
                               TextView detailView, TextView stageView, ProgressBar bar) {
        this.dialog = dialog;
        this.percentView = percentView;
        this.detailView = detailView;
        this.stageView = stageView;
        this.bar = bar;
    }

    /**
     * 弹出进度框。
     *
     * @param title    标题（「正在下载剪辑素材」/「正在更新剪辑素材」）
     * @param onCancel 用户点「取消」时回调（只置取消标记，真正停下由下载线程自己收尾）
     */
    public static PackProgressDialog show(Activity a, String title, Runnable onCancel) {
        int pad = dp(a, 22);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(a, 16), pad, dp(a, 4));

        TextView percent = new TextView(a);
        percent.setText("0%");
        percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        percent.setGravity(Gravity.CENTER);
        percent.setTextColor(0xFFF4F8FF);
        box.addView(percent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 10));
        barLp.topMargin = dp(a, 10);
        box.addView(bar, barLp);

        TextView detail = new TextView(a);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detail.setGravity(Gravity.CENTER);
        detail.setTextColor(0xFFC2CDDD);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(a, 12);
        box.addView(detail, detailLp);

        TextView stage = new TextView(a);
        stage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stage.setGravity(Gravity.CENTER);
        stage.setTextColor(0xFF8C98AA);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stageLp.topMargin = dp(a, 4);
        box.addView(stage, stageLp);

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (d, w) -> {
            if (onCancel != null) onCancel.run();
            stage.setText("正在取消…");
        });
        dlg.show();

        return new PackProgressDialog(dlg, percent, detail, stage, bar);
    }

    /**
     * 推进度。
     *
     * @param stage "下载" / "校验" / "解压" / 其它（原样显示）
     * @param done  已完成字节（解压阶段是压缩包内已处理的字节）
     * @param total 总量；未知传 -1
     */
    public void update(String stage, long done, long total) {
        if (dialog == null || !dialog.isShowing()) return;
        long now = System.currentTimeMillis();

        if ("校验".equals(stage)) {
            bar.setIndeterminate(true);
            percentView.setText("校验");
            detailView.setText("正在核对文件完整性，不会写入磁盘");
            stageView.setText(fmtMb(done) + " / " + fmtMb(total));
            return;
        }

        bar.setIndeterminate(false);
        int permille = total > 0 ? (int) Math.max(0, Math.min(1000, done * 1000 / total)) : 0;
        bar.setProgress(permille);

        if ("下载".equals(stage)) {
            percentView.setText((permille / 10) + "%");
            if (lastAt > 0 && now > lastAt && done >= 0) {
                // 1.5 秒窗口算一次速度，避免数字乱跳
                if (winStartAt == 0) {
                    winStartAt = now;
                    winStartBytes = done;
                } else if (now - winStartAt >= 1500) {
                    double inst = (done - winStartBytes) * 1000.0 / (now - winStartAt);
                    speed = speed <= 0 ? inst : speed * 0.5 + inst * 0.5;
                    winStartAt = now;
                    winStartBytes = done;
                }
            }
            lastAt = now;

            StringBuilder sb = new StringBuilder();
            sb.append(fmtMb(done)).append(" / ").append(fmtMb(total));
            if (speed > 0.05 * 1024 * 1024) {
                sb.append(" · ").append(String.format("%.1f", speed / 1048576)).append(" MB/s");
                long left = total > done ? (long) ((total - done) / speed) : 0;
                if (left > 0) {
                    sb.append(" · 约剩 ").append(left >= 60
                            ? (left / 60) + " 分 " + (left % 60) + " 秒"
                            : left + " 秒");
                }
            }
            detailView.setText(sb.toString());
            stageView.setText("下载中");
            return;
        }

        if ("解压".equals(stage)) {
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setProgress(permille);
                percentView.setText((permille / 10) + "%");
                detailView.setText("已解压 " + fmtMb(done) + " / " + fmtMb(total));
            } else {
                bar.setIndeterminate(true);
                percentView.setText("解压");
                detailView.setText("已解压 " + fmtMb(done));
            }
            stageView.setText("解压中（文件多时，手机会比下载慢一些）");
            return;
        }

        percentView.setText(permille / 10 + "%");
        detailView.setText(fmtMb(done) + " / " + fmtMb(total));
        stageView.setText(stage);
    }

    public void dismiss() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private static String fmtMb(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** 只有弹窗还活着才更新，省得销毁后还碰 View。 */
    public boolean isAlive() {
        return dialog != null && dialog.isShowing();
    }
}
