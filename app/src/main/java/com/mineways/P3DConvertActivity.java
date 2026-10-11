package com.mineways;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.mineways.p3d.Mp;
import com.mineways.p3d.P3DConvert;
import com.mineways.p3d.P3DPack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * P3D 工程工具（纯本地）。
 *
 * <p>Prisma3D 工程包的结构是逆向出来的（详见 {@link P3DPack} 与 {@link Mp} 的注释）：
 * 2.0 是标准 ZIP 的单体工程，3.0 是"头部被改成 60 4B 的 ZIP + 按 UUID 拆分的模块"，
 * 数据全部是 MessagePack。
 *
 * <p>这一页目前提供确定性可靠的三件事：识别版本与内容、修复被改过的头部、解包到 Download，
 * 外加把内部数据结构导成文本报告（跨版本转换的字段核对就靠它）。
 */
public class P3DConvertActivity extends Activity {

    private static final int REQ_PICK = 8901;
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private static final int REPORT_VALUE_CHARS = 3000;

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int FIELD = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    private TextView tvInfo;
    private TextView tvStatus;
    private Button btnFix;
    private Button btnUnpack;
    private Button btnReport;
    private Button btnConvert20;

    private byte[] raw;
    private P3DPack.Pack pack;
    private String fileName = "project";

    /** 受限功能的记账：主界面带过来的 token，用掉一次就 confirm，没用上就 release。 */
    private String activationToken;
    private String activationFeature;
    private boolean activationDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent from = getIntent();
        activationToken = from == null ? null : from.getStringExtra("activation_token");
        activationFeature = from == null ? null : from.getStringExtra("activation_feature");
        setContentView(buildRoot());
        refreshButtons();
    }

    /** 成功做出东西（修复包 / 解包 / 报告）→ 扣 1 次；进来啥也没干就退出 → 不扣。 */
    private void finishActivation(boolean success) {
        if (activationDone || activationToken == null || activationToken.length() == 0) {
            return;
        }
        activationDone = true;
        ActivationDialog.finish(this, activationFeature, activationToken, success);
    }

    @Override
    protected void onDestroy() {
        finishActivation(false);    // 没确认就退出 → release，不扣次
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextColor(DIM);
        back.setTextSize(13);
        back.setPadding(0, dp(4), dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = new TextView(this);
        title.setText("P3D 工程工具");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("Prisma3D 工程包（.prisma）：识别版本 · 修头 · 解包 · 数据报告\n"
                + "2.0 = 标准 ZIP 单体工程　3.0 = 头部 60 4B + 按 UUID 拆分");
        sub.setTextColor(DIM);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        root.addView(sub);

        Button pick = primaryBtn("选择 .prisma 工程包");
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        tvInfo = new TextView(this);
        tvInfo.setText("还没选文件。选一个 .prisma 后这里显示识别结果。");
        tvInfo.setTextColor(TEXT);
        tvInfo.setTextSize(13);
        tvInfo.setBackground(round(CARD, 14));
        tvInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.addView(tvInfo);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        btnFix = smallBtn("修复头部另存");
        btnFix.setOnClickListener(v -> doFixHeader());
        row.addView(btnFix);

        btnUnpack = smallBtn("解包到 Download");
        btnUnpack.setOnClickListener(v -> doUnpack());
        row.addView(btnUnpack);

        btnReport = smallBtn("导出数据报告");
        btnReport.setOnClickListener(v -> doReport());
        row.addView(btnReport);

        col.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        btnConvert20 = smallBtn("转成 2.0 工程包（3.0 → 2.0）");
        btnConvert20.setOnClickListener(v -> doConvert());
        row2.addView(btnConvert20);
        col.addView(row2);

        TextView note = new TextView(this);
        note.setText("\n说明：\n"
                + "· 「修复头部另存」把 3.0 的 60 4B 头改成标准 50 4B，"
                + "产出物可以直接用 MT 管理器 / 电脑解压看贴图和数据；\n"
                + "· 「解包」按原目录结构导出全部条目（含 res/ 贴图）；\n"
                + "· 「数据报告」把工程内部数据结构（MessagePack 结构树）导成 txt，"
                + "跨版本转换的字段核对用它。");
        note.setTextColor(DIM);
        note.setTextSize(11.5f);
        col.addView(note);

        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvStatus = new TextView(this);
        tvStatus.setTextColor(ACCENT);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(tvStatus);
        return root;
    }

    private void refreshButtons() {
        boolean ready = pack != null;
        btnFix.setEnabled(ready);
        btnUnpack.setEnabled(ready);
        btnReport.setEnabled(ready);
        btnFix.setAlpha(ready ? 1f : 0.45f);
        btnUnpack.setAlpha(ready ? 1f : 0.45f);
        btnReport.setAlpha(ready ? 1f : 0.45f);
        boolean v3 = ready && pack.version == P3DPack.V_300;
        btnConvert20.setEnabled(v3);
        btnConvert20.setAlpha(v3 ? 1f : 0.45f);
    }

    // ------------------------------------------------------------------ 选文件

    private void pickFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_PICK);
            status("选一个 .prisma 文件…");
        } catch (Throwable t) {
            status("这台设备拉不起文件选择器");
            toast("拉不起文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            status("没拿到文件");
            return;
        }
        load(uri);
    }

    private void load(final Uri uri) {
        status("正在读取…");
        new Thread(() -> {
            byte[] bytes = null;
            String err = null;
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IllegalStateException("打不开这个文件");
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                byte[] buf = new byte[65536];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IllegalStateException("文件太大（上限 256MB）");
                    }
                    bos.write(buf, 0, n);
                }
                in.close();
                bytes = bos.toByteArray();
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final byte[] fBytes = bytes;
            final String fErr = err;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (fErr != null) {
                    status("读取失败：" + fErr);
                    return;
                }
                raw = fBytes;
                fileName = baseName(uri.getLastPathSegment());
                try {
                    pack = P3DPack.open(raw);
                    tvInfo.setText(P3DPack.describe(pack));
                    status("已识别：" + (pack.version == P3DPack.V_300 ? "3.0" : "2.0")
                            + " · " + pack.entries.size() + " 个条目");
                } catch (Throwable t) {
                    pack = null;
                    tvInfo.setText("这个文件不是可识别的 P3D 工程包：\n"
                            + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    status("识别失败");
                }
                refreshButtons();
            });
        }).start();
    }

    // ------------------------------------------------------------------ 三个动作

    private void doFixHeader() {
        final P3DPack.Pack p = pack;
        if (p == null) {
            return;
        }
        status("正在写修复版…");
        new Thread(() -> {
            String msg;
            try {
                byte[] out = P3DPack.zip(p, false);      // 50 4B：普通解压器能直接打开
                String name = stripExt(fileName) + "_fixed.prisma";
                String where = saveToDownloads("P3D", name, out);
                msg = where == null ? "写入失败（检查存储空间）"
                        : "已写出：" + where + "（" + (out.length / 1024) + " KB）";
                if (where != null) {
                    finishActivation(true);     // 真写出东西了才算用掉一次
                }
            } catch (Throwable t) {
                msg = "失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            finishMsg(msg);
        }).start();
    }

    private void doUnpack() {
        final P3DPack.Pack p = pack;
        if (p == null) {
            return;
        }
        status("正在解包…");
        new Thread(() -> {
            int ok = 0;
            int bad = 0;
            String root = "P3D/unpack/" + stripExt(fileName);
            for (P3DPack.Entry e : p.entries) {
                String path = e.name;
                int slash = path.lastIndexOf('/');
                String dir = slash < 0 ? root : root + "/" + path.substring(0, slash);
                String name = slash < 0 ? path : path.substring(slash + 1);
                if (saveToDownloads(dir, name, e.data) != null) {
                    ok++;
                } else {
                    bad++;
                }
            }
            if (ok > 0) {
                finishActivation(true);
            }
            finishMsg("解包完成：" + ok + " 个条目"
                    + (bad > 0 ? ("，" + bad + " 个失败") : "")
                    + " → Download/" + root + "/");
        }).start();
    }

    private void doReport() {
        final P3DPack.Pack p = pack;
        if (p == null) {
            return;
        }
        status("正在生成报告…");
        new Thread(() -> {
            String msg;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("Prisma3D 工程包数据报告\n");
                sb.append("文件：").append(fileName).append('\n');
                sb.append(P3DPack.describe(p)).append("\n\n");
                for (P3DPack.Entry e : p.entries) {
                    if (!isDataEntry(e.name)) {
                        continue;
                    }
                    sb.append("==================== ").append(e.name)
                            .append("  (").append(e.data.length).append(" B)\n");
                    try {
                        List<Object> vals = new Mp.Reader(e.data, 0).readAll();
                        for (int i = 0; i < vals.size(); i++) {
                            String s = Mp.toString(vals.get(i));
                            if (s.length() > REPORT_VALUE_CHARS) {
                                s = s.substring(0, REPORT_VALUE_CHARS) + " …(共 " + s.length() + " 字符)";
                            }
                            sb.append('#').append(i).append(" : ").append(s).append('\n');
                        }
                    } catch (Throwable t) {
                        sb.append("解析失败：")
                                .append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                                .append('\n');
                    }
                    sb.append('\n');
                }
                String name = stripExt(fileName) + "_report.txt";
                String where = saveToDownloads("P3D/report", name,
                        sb.toString().getBytes("UTF-8"));
                msg = where == null ? "写入失败" : "报告已写出：" + where;
                if (where != null) {
                    finishActivation(true);
                }
            } catch (Throwable t) {
                msg = "失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            finishMsg(msg);
        }).start();
    }

    /**
     * 3.0 → 2.0：按 v200/v300 模型字段表做的结构转换（UUID→索引、对象树→扁平列表、
     * 动画从 pclip 搬回属性、JMesh2 的两个新字段丢弃）。
     */
    private void doConvert() {
        final byte[] data = raw;
        final P3DPack.Pack p = pack;
        if (data == null || p == null) {
            return;
        }
        if (p.version != P3DPack.V_300) {
            toast("只有 3.0 的工程包需要转 2.0（2.0 工程 3.0 本来就能直接打开）");
            return;
        }
        status("正在转换…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                P3DConvert.Result r = P3DConvert.toV200(data);
                String name = stripExt(fileName) + "_v20.prisma";
                String where = saveToDownloads("P3D", name, r.zip);
                ok = where != null;
                msg = !ok ? "写入失败（检查存储空间）"
                        : "已转出 2.0 工程包：\n" + where + "\n\n" + r.report();
            } catch (Throwable t) {
                msg = "转换失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String out = msg;
            final boolean done = ok;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (done) {
                    finishActivation(true);     // 真转出来了才算用掉一次
                }
                status(done ? "转换完成，去 Download/P3D/ 取文件" : "转换失败");
                new AlertDialog.Builder(this)
                        .setTitle(done ? "转换完成" : "转换失败")
                        .setMessage(out)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }).start();
    }

    /** 数据类条目才值得报告（贴图那种二进制跳过）。 */
    private static boolean isDataEntry(String name) {
        return name.endsWith(".proj") || name.endsWith(".pobject")
                || name.endsWith(".pclip") || name.endsWith(".meta");
    }

    // ------------------------------------------------------------------ 落盘

    /** 写进公共 Download 目录：Android 10+ 走 MediaStore（不用权限），老系统退回 File API。 */
    private String saveToDownloads(String relDir, String name, byte[] data) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + relDir);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                        return "Download/" + relDir + "/" + name;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), relDir);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("建不了目录");
            }
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private void finishMsg(final String msg) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            status(msg);
            new AlertDialog.Builder(this)
                    .setTitle("完成")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show();
        });
    }

    private static String baseName(String s) {
        if (s == null || s.length() == 0) {
            return "project.prisma";
        }
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return i < 0 ? s : s.substring(i + 1);
    }

    private static String stripExt(String s) {
        int i = s.toLowerCase(Locale.ROOT).lastIndexOf(".prisma");
        return i > 0 ? s.substring(0, i) : s;
    }

    private Button primaryBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(round(ACCENT, 12));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    private Button smallBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(TEXT);
        b.setBackground(round(FIELD, 999));
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void status(String s) {
        tvStatus.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private android.graphics.drawable.GradientDrawable round(int fill, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
