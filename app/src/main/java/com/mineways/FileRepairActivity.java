package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mineways.repair.Repair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件修复（工具箱）：
 * Prisma3D 工程包（2.0 / 3.0）· 通用 ZIP · 图片 · 音频 · BB（Blockbench）导出物。
 * 流程：选文件 → 自动识别 → 开始修复 → 报告 + 产物（可分享）。产物写在应用外部目录的 repair/ 里。
 */
public class FileRepairActivity extends Activity {

    private static final int REQ_PICK = 51;

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    private TextView tvInfo;
    private LinearLayout outBox;
    private Button btnRepair;
    private Button btnToggle200;

    private byte[] data;
    private String fileName = "";
    private boolean toV200 = true;
    private final List<File> produced = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        final TextView title = new TextView(this);
        title.setText("文件修复");
        title.setTextColor(TEXT);
        title.setTextSize(22f);
        root.addView(title);

        final TextView sub = new TextView(this);
        sub.setText("流程：先体检（识别类型 · 校验结构 · 核对数据）→ 有损坏直接修；"
                + "没损坏会提示「本文件未损坏」，由你决定是否强制修复"
                + "（强制修复 = 最大数据保留：不删条目、不改写内容字节）。\n\n支持五类：\n"
                + "· Prisma3D 工程包（2.0 / 3.0）：修头、补 +0 快照与缩略图、重建容器；\n"
                + "  缺内容文件（相机/光源等「打开是空的」常见原因）会按 project.proj 里的对象记录补全，只补不删；可转 2.0\n"
                + "· ZIP 压缩包：中央目录坏了也能从本地头重建，重算 CRC\n"
                + "· 图片：按魔数判真格式、补 PNG IEND / JPEG EOI、剥尾部垃圾、纠扩展名\n"
                + "· 音频：WAV 长度字段重算、MP3 头修复、扩展名纠正\n"
                + "· BB（Blockbench）导出：bbmodel JSON 修复、obj 补 mtllib、mtl 补 Kd");
        sub.setTextColor(DIM);
        sub.setTextSize(13f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(10), 0, dp(14));
        root.addView(sub);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final Button pick = new Button(this);
        pick.setText("选择文件");
        pick.setAllCaps(false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        actions.addView(pick);

        btnRepair = new Button(this);
        btnRepair.setText("开始修复");
        btnRepair.setAllCaps(false);
        btnRepair.setEnabled(false);
        btnRepair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRepair();
            }
        });
        actions.addView(btnRepair);

        btnToggle200 = new Button(this);
        btnToggle200.setText("P3D 转 2.0：开");
        btnToggle200.setAllCaps(false);
        btnToggle200.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toV200 = !toV200;
                btnToggle200.setText("P3D 转 2.0：" + (toV200 ? "开" : "关"));
            }
        });
        actions.addView(btnToggle200);
        root.addView(actions);

        tvInfo = new TextView(this);
        tvInfo.setText("还没选文件。选一个文件后这里显示识别与修复报告。");
        tvInfo.setTextColor(TEXT);
        tvInfo.setTextSize(12.5f);
        tvInfo.setTextIsSelectable(true);
        tvInfo.setBackground(round(CARD, 12));
        tvInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(14);
        tvInfo.setLayoutParams(ilp);
        root.addView(tvInfo);

        outBox = new LinearLayout(this);
        outBox.setOrientation(LinearLayout.VERTICAL);
        outBox.setPadding(0, dp(14), 0, 0);
        root.addView(outBox);
        return scroll;
    }

    // ------------------------------------------------------------------ 选文件
    private void pickFile() {
        final Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("这台设备没有可用的文件选择器：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent dataIntent) {
        super.onActivityResult(requestCode, resultCode, dataIntent);
        if (requestCode != REQ_PICK) {
            return;
        }
        if (resultCode != RESULT_OK || dataIntent == null || dataIntent.getData() == null) {
            return;
        }
        final Uri uri = dataIntent.getData();
        try {
            fileName = queryName(uri);
            data = readAll(uri);
            tvInfo.setText("已选择：" + fileName + "（" + data.length + " 字节）\n\n点「开始修复」开始。");
            outBox.removeAllViews();
            produced.clear();
            btnRepair.setEnabled(true);
        } catch (Throwable t) {
            toast("读文件失败：" + t.getMessage());
        }
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                final int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    final String s = c.getString(i);
                    if (s != null && s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        final String p = uri.getLastPathSegment();
        return p == null ? "file" : p;
    }

    private byte[] readAll(Uri uri) throws Exception {
        final InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("打不开输入流");
        }
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 体检 → 修复
    private void startRepair() {
        if (data == null) {
            return;
        }
        btnRepair.setEnabled(false);
        outBox.removeAllViews();
        produced.clear();
        tvInfo.setText("正在体检（识别类型 · 校验结构 · 核对数据）…");
        final byte[] snapshot = data;
        final String name = fileName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Repair.Health health = Repair.diagnose(snapshot, name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (health.damaged) {
                            tvInfo.setText(health.summary() + "\n正在修复…");
                            doRepair(snapshot, name, false, health);
                        } else {
                            askForceRepair(snapshot, name, health);
                        }
                    }
                });
            }
        }, "file-diagnose").start();
    }

    /** 没检测到损坏：把决定权交给用户（可强制修复；强制修复按「最大数据保留」执行）。 */
    private void askForceRepair(final byte[] snapshot, final String name, final Repair.Health health) {
        final String msg = health.summary()
                + "\n强制修复只做「重建容器 / 补全缺失结构」，不删除、不改写内容：\n"
                + "· 压缩包 / P3D：条目一个不删，内容逐字节保留，原本不压缩的条目继续保持不压缩\n"
                + "· 图片 / 音频 / JSON / OBJ / MTL：内容完整时直接给原始字节副本，最多纠正扩展名";
        try {
            new AlertDialog.Builder(this)
                    .setTitle("本文件未损坏")
                    .setMessage(msg)
                    .setPositiveButton("强制修复", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            doRepair(snapshot, name, true, health);
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            btnRepair.setEnabled(true);
                            tvInfo.setText(health.summary() + "\n已取消：文件保持原样，没有做任何修改。");
                        }
                    })
                    .show();
        } catch (Throwable t) {
            // 极端情况下弹不出对话框：直接按体检结论展示，让用户再点一次
            btnRepair.setEnabled(true);
            tvInfo.setText(msg + "\n\n（对话框不可用：" + t.getMessage() + "；要强制修复请再点一次「开始修复」）");
        }
    }

    private void doRepair(final byte[] snapshot, final String name, final boolean force,
                          final Repair.Health health) {
        btnRepair.setEnabled(false);
        final boolean want200 = toV200;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Repair.Outcome outcome = Repair.run(snapshot, name, want200, force);
                final List<File> written = writeOutputs(outcome);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnRepair.setEnabled(true);
                        tvInfo.setText(health.summary() + "\n— 修复 —\n" + outcome.report());
                        showOutputs(written);
                        if (!outcome.ok) {
                            toast("没能修复（看报告）");
                        } else {
                            toast(force ? "强制修复完成（最大数据保留）" : "修复完成");
                        }
                    }
                });
            }
        }, "file-repair").start();
    }

    private List<File> writeOutputs(Repair.Outcome outcome) {
        final List<File> out = new ArrayList<>();
        File dir = getExternalFilesDir("repair");
        if (dir == null) {
            dir = new File(getFilesDir(), "repair");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return out;
        }
        for (Repair.Out o : outcome.outputs) {
            try {
                final File f = new File(dir, o.name);
                final OutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(o.data);
                    fos.flush();
                } finally {
                    fos.close();
                }
                out.add(f);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private void showOutputs(List<File> files) {
        outBox.removeAllViews();
        produced.clear();
        produced.addAll(files);
        if (files.isEmpty()) {
            return;
        }
        for (final File f : files) {
            final LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(round(CARD, 12));
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            box.setLayoutParams(lp);

            final TextView nm = new TextView(this);
            nm.setText(f.getName());
            nm.setTextColor(TEXT);
            nm.setTextSize(15f);
            box.addView(nm);

            final TextView path = new TextView(this);
            path.setText(f.length() + " 字节 · " + f.getAbsolutePath());
            path.setTextColor(DIM);
            path.setTextSize(11f);
            path.setPadding(0, dp(6), 0, dp(10));
            box.addView(path);

            final Button share = new Button(this);
            share.setText("分享 / 导出");
            share.setAllCaps(false);
            share.setBackground(round(ACCENT, 10));
            share.setTextColor(TEXT);
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareFile(f);
                }
            });
            box.addView(share);
            outBox.addView(box);
        }
        final TextView tip = new TextView(this);
        tip.setText("产物目录：Android/data/" + getPackageName() + "/files/repair/"
                + "（文件管理器可见；也可点「分享 / 导出」直接发出去）");
        tip.setTextColor(DIM);
        tip.setTextSize(11.5f);
        tip.setPadding(0, dp(4), 0, dp(10));
        outBox.addView(tip);
    }

    private void shareFile(File f) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            final Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享修复产物"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 小工具
    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
