package com.mineways.vr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 360 全景视频。
 *
 * <p>两种输入：
 * <ul>
 *   <li><b>1 张</b>等距柱状全景图（手机「全景」模式拍的）→ 直接渲染；</li>
 *   <li><b>多张</b>多角度照片（游戏内截图也行）→ OpenCV 拼成全景再渲染。</li>
 * </ul>
 *
 * <p>输出支持 4K / 8K、2D 平面 / 3D 全景，自动写球形元数据，存进 Download。
 */
public class PanoActivity extends Activity {

    private static final int REQ_PICK = 0x7A01;

    private static final int ACCENT = 0xFFFFC86B;
    private static final int BG = 0xFF0E1216;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView preview;
    private TextView hint;
    private final List<Uri> uris = new ArrayList<>();

    private int seconds = 6;
    private float yawSpeed = 6f;
    private int mode = PanoExport.MODE_360;
    private int resolution = PanoExport.RES_4K;
    private int quality = PanoExport.Q_HIGH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        android.net.Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    /** 外部「打开方式」：进拼接队列，与页内选择同一条路。 */
    private void acceptIncoming(Uri u) {
        uris.clear();
        uris.add(u);
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        refreshPreview();
    }

    // ============================================================ 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        bar.setPadding(pad, dp(12), pad, dp(8));
        root.addView(bar);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText("360 全景视频");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(ACCENT);
        titles.addView(t1);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(4), pad, dp(24));
        scroll.addView(col);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF10161C);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        pp.bottomMargin = dp(10);
        col.addView(preview, pp);

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(0x99FFFFFF);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, 0, 0, dp(12));
        hint.setText(defaultHint());
        col.addView(hint);

        col.addView(btn("选择照片（可多选）", true, v -> pick()));
        col.addView(btn("清空重选", false, v -> {
            uris.clear();
            preview.setImageDrawable(null);
            hint.setText(defaultHint());
        }));

        col.addView(space());
        col.addView(label("输出类型"));
        col.addView(row(new String[]{"3D 全景", "2D 平面"},
                new Runnable[]{() -> mode = PanoExport.MODE_360, () -> mode = PanoExport.MODE_FLAT},
                () -> mode == PanoExport.MODE_360, () -> mode == PanoExport.MODE_FLAT));

        col.addView(space());
        col.addView(label("分辨率"));
        col.addView(row(new String[]{"4K", "8K"},
                new Runnable[]{() -> resolution = PanoExport.RES_4K,
                        () -> resolution = PanoExport.RES_8K},
                () -> resolution == PanoExport.RES_4K, () -> resolution == PanoExport.RES_8K));

        col.addView(space());
        col.addView(label("画质"));
        col.addView(row(new String[]{"标准", "高", "极高"},
                new Runnable[]{() -> quality = PanoExport.Q_STD,
                        () -> quality = PanoExport.Q_HIGH, () -> quality = PanoExport.Q_MAX},
                () -> quality == PanoExport.Q_STD, () -> quality == PanoExport.Q_HIGH,
                () -> quality == PanoExport.Q_MAX));

        col.addView(space());
        col.addView(label("时长"));
        col.addView(row(new String[]{"4 秒", "6 秒", "8 秒", "10 秒"},
                new Runnable[]{() -> seconds = 4, () -> seconds = 6,
                        () -> seconds = 8, () -> seconds = 10},
                () -> seconds == 4, () -> seconds == 6, () -> seconds == 8, () -> seconds == 10));

        col.addView(space());
        col.addView(label("转速"));
        col.addView(row(new String[]{"慢", "中", "快"},
                new Runnable[]{() -> yawSpeed = 3f, () -> yawSpeed = 6f, () -> yawSpeed = 12f},
                () -> yawSpeed == 3f, () -> yawSpeed == 6f, () -> yawSpeed == 12f));

        col.addView(space());
        col.addView(btn("开始导出", true, v -> startExport()));

        TextView tip = new TextView(this);
        tip.setText("\n3D 全景：4K = 3840×1920，8K = 7680×3840，写入全景元数据，抖音可转头看。\n"
                + "2D 平面：4K = 3840×2160，普通视频，不写全景元数据。\n"
                + "8K 要看机器硬编是否支持，不支持会自动降到 4K 并提示。\n"
                + "多张照片：相邻两张至少留 30% 重合，最多 " + PanoStitcher.MAX_IMAGES + " 张。\n"
                + "存到 Download/" + PanoExport.FOLDER + "/；发抖音时在「高级设置」里打开「全景播放」。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setTextColor(0x70FFFFFF);
        tip.setLineSpacing(dp(3), 1f);
        col.addView(tip);

        return root;
    }

    private String defaultHint() {
        return "选 1 张：等距柱状全景图（2:1，手机「全景」模式拍的那种）。\n"
                + "选多张：绕着拍的一圈照片，会自动拼接成全景。";
    }

    private View label(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTextColor(0x99FFFFFF);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private View space() {
        return new View(this);
    }

    private View btn(String text, boolean primary, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(13), 0, dp(13));
        v.setTextColor(primary ? Color.BLACK : Color.WHITE);
        v.setBackgroundColor(primary ? ACCENT : 0xFF1D252D);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private View row(String[] names, Runnable[] actions, BooleanSupplier... selected) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[names.length];

        Runnable refresh = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = selected[i].getAsBoolean();
                chips[i].setTextColor(on ? ACCENT : 0xFF9AA6B2);
                chips[i].setBackgroundColor(on ? 0x33FFC86B : 0xFF1D252D);
            }
        };

        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView c = new TextView(this);
            c.setText(names[i]);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(10), dp(8), dp(10), dp(8));
            c.setOnClickListener(v -> {
                actions[k].run();
                refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            r.addView(c, lp);
            chips[i] = c;
        }
        refresh.run();
        return r;
    }

    // ============================================================ 选图

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;

        uris.clear();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        refreshPreview();
    }

    private void refreshPreview() {
        if (uris.isEmpty()) {
            preview.setImageDrawable(null);
            hint.setText(defaultHint());
            return;
        }
        new Thread(() -> {
            try {
                Bitmap bmp = PanoExport.loadPano(this, uris.get(0), 1200);
                ui.post(() -> {
                    preview.setImageBitmap(bmp);
                    if (uris.size() == 1) {
                        float ratio = bmp.getHeight() / (float) bmp.getWidth();
                        boolean ok = Math.abs(ratio - 0.5f) < 0.06f;
                        hint.setText("已选 1 张：" + bmp.getWidth() + "×" + bmp.getHeight()
                                + (ok ? "\n比例 2:1，符合全景图要求 ✓（直接渲染，不拼接）"
                                : "\n⚠ 比例不是 2:1。要么换成全景图，要么再选几张做拼接。"));
                    } else {
                        hint.setText("已选 " + uris.size() + " 张，将先拼接成全景再渲染。\n"
                                + "相邻两张要留 30% 以上重合，顺序不用管。");
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, "读图失败：" + t.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "pano-preview").start();
    }

    // ============================================================ 导出

    private void startExport() {
        if (uris.isEmpty()) {
            Toast.makeText(this, "先选照片", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage("准备中…");
        dlg.setCancelable(false);
        dlg.show();

        final PanoExport.Options opt = new PanoExport.Options();
        opt.seconds = seconds;
        opt.yawSpeed = yawSpeed;
        opt.mode = mode;
        opt.resolution = resolution;
        opt.quality = quality;

        final List<Uri> list = new ArrayList<>(uris);

        new Thread(() -> {
            Bitmap pano = null;
            try {
                if (list.size() >= 2) {
                    // 拼接固定按 4K 出图：8K 拼接内存翻四倍，手机上基本必崩
                    pano = PanoStitcher.stitch(this, list, 3840, 1920,
                            (stage, done, total) -> ui.post(() -> dlg.setMessage(
                                    stage + "\n" + done + " / " + total)));
                } else {
                    pano = PanoExport.loadPano(this, list.get(0), 4096);
                }

                PanoExport.Result r = PanoExport.export(this, pano, opt,
                        (stage, done, total) -> ui.post(() -> dlg.setMessage(
                                stage + "\n" + done + " / " + total)));

                ui.post(() -> {
                    dlg.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("导出完成")
                            .setMessage("已保存：\n" + r.displayPath
                                    + "\n\n发抖音时：发布页 → 高级设置 → 打开「全景播放」。")
                            .setPositiveButton("好", null)
                            .show();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    dlg.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("导出失败")
                            .setMessage(String.valueOf(t.getMessage()))
                            .setPositiveButton("好", null)
                            .show();
                });
            } finally {
                if (pano != null && !pano.isRecycled()) pano.recycle();
            }
        }, "pano-export").start();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
