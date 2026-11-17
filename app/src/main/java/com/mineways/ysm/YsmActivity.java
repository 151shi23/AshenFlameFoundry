package com.mineways.ysm;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mineways.cloud.CloudFlags;
import com.mineways.ui.MwViews;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * YSM（Yes Steve Model）解密：把 .ysm 还原成可在 Blockbench 里继续编辑的工程。
 *
 * <p>内核来自 OpenYSM/YSMParser（MIT，C++/JNI）。界面只做三步：
 * 选文件 → 解密 → 打包分享。解析产物包含 models / animations / controller / textures /
 * sounds / lang / avatar / ysm.json，具体结构由内核写盘，本页不重复解释。
 */
public class YsmActivity extends Activity {

    private static final int REQ_PICK = 81;

    private File picked;
    private File outDir;
    private File zipFile;
    private boolean nativeReady;
    private TextView tvFile;
    private TextView tvState;
    private TextView tvOut;
    private TextView btnParse;
    private TextView btnZip;
    private TextView btnShare;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!CloudFlags.bool("ysm_on", true)) {
            Toast.makeText(this, "YSM 解密已被云端关闭", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        nativeReady = nativeReady();
        setContentView(buildUi());
        if (!nativeReady) {
            tvState.setText("当前设备不支持（仅 arm64-v8a）");
        }
        Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    private boolean nativeReady() {
        try {
            Class.forName("com.ysm.parser.YSMParser");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(MwViews.pageBackground(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MwViews.dp(this, 18), MwViews.dp(this, 10),
                MwViews.dp(this, 18), MwViews.dp(this, 36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(MwViews.header(this, "工具箱", "YSM 解密", null));

        LinearLayout fileCard = MwViews.card(this, 14);
        tvFile = MwViews.body(this, "未选择", MwViews.TEXT, 14f);
        fileCard.addView(tvFile);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, MwViews.dp(this, 10), 0, 0);
        TextView pick = MwViews.pillButton(this, "选择 .ysm", true, v -> pick());
        row.addView(pick);
        btnParse = MwViews.pillButton(this, "解密", false, v -> parse());
        btnParse.setEnabled(false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = MwViews.dp(this, 8);
        btnParse.setLayoutParams(plp);
        row.addView(btnParse);
        fileCard.addView(row);
        tvState = MwViews.body(this, "", MwViews.TEXT_2, 12f);
        tvState.setPadding(0, MwViews.dp(this, 8), 0, 0);
        fileCard.addView(tvState);
        root.addView(fileCard);

        LinearLayout outCard = MwViews.card(this, 14);
        tvOut = MwViews.body(this, "", MwViews.TEXT_2, 12f);
        tvOut.setTextIsSelectable(true);
        outCard.addView(tvOut);
        LinearLayout outRow = new LinearLayout(this);
        outRow.setOrientation(LinearLayout.HORIZONTAL);
        outRow.setPadding(0, MwViews.dp(this, 10), 0, 0);
        btnZip = MwViews.pillButton(this, "打包 zip", false, v -> zip());
        btnZip.setEnabled(false);
        outRow.addView(btnZip);
        btnShare = MwViews.pillButton(this, "分享", true, v -> share());
        btnShare.setEnabled(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = MwViews.dp(this, 8);
        btnShare.setLayoutParams(slp);
        outRow.addView(btnShare);
        outCard.addView(outRow);
        root.addView(outCard);

        return scroll;
    }

    private void pick() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        acceptIncoming(data.getData());
    }

    /** 外部「打开方式」与页内选择器共用同一条载入路径。 */
    private void acceptIncoming(Uri uri) {
        final String name = queryName(uri);
        tvState.setText("正在读取…");
        new Thread(() -> {
            String err = null;
            File f = null;
            try {
                f = copyToWork(uri, name);
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final File file = f;
            final String e = err;
            runOnUiThread(() -> {
                if (file == null) {
                    tvState.setText("读取失败：" + e);
                    return;
                }
                picked = file;
                zipFile = null;
                btnZip.setEnabled(false);
                btnShare.setEnabled(false);
                tvOut.setText("");
                tvFile.setText(name);
                btnParse.setEnabled(nativeReady);
                if (nativeReady) version(file);
            });
        }, "ysm-copy").start();
    }

    private void version(File f) {
        new Thread(() -> {
            String text;
            try {
                int v = com.ysm.parser.YSMParser.getVersion(f.getAbsolutePath());
                text = v < 0 ? "YSM 版本未识别" : "YSM v" + v;
            } catch (Throwable t) {
                text = "读取版本失败：" + msg(t);
            }
            final String s = text;
            runOnUiThread(() -> tvState.setText(s));
        }, "ysm-version").start();
    }

    private void parse() {
        if (picked == null || !nativeReady) return;
        btnParse.setEnabled(false);
        tvState.setText("正在解密…");
        final File in = picked;
        new Thread(() -> {
            File base = getExternalFilesDir("ysm");
            if (base == null) base = new File(getFilesDir(), "ysm");
            File dir = new File(base, stripExt(picked.getName()));
            deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                runOnUiThread(() -> {
                    tvState.setText("建不了输出目录");
                    btnParse.setEnabled(true);
                });
                return;
            }
            String err = null;
            boolean ok = false;
            try {
                ok = com.ysm.parser.YSMParser.parse(in.getAbsolutePath(), dir.getAbsolutePath());
            } catch (Throwable t) {
                err = msg(t);
            }
            final boolean success = ok;
            final String e = err;
            runOnUiThread(() -> {
                btnParse.setEnabled(true);
                if (!success) {
                    tvState.setText("解密失败" + (e == null ? "" : "：" + e));
                    return;
                }
                outDir = dir;
                int[] stat = count(dir);
                tvState.setText("完成");
                tvOut.setText(stat[0] + " 个文件 / " + human(stat[1]) + "\n" + dir.getAbsolutePath());
                btnZip.setEnabled(true);
            });
        }, "ysm-parse").start();
    }

    private void zip() {
        if (outDir == null || !outDir.isDirectory()) return;
        btnZip.setEnabled(false);
        tvState.setText("正在打包…");
        new Thread(() -> {
            File z = new File(outDir.getParentFile(), outDir.getName() + "-blockbench.zip");
            String err = null;
            try {
                zipDir(outDir, z);
            } catch (Throwable t) {
                err = msg(t);
            }
            final String e = err;
            runOnUiThread(() -> {
                btnZip.setEnabled(true);
                if (e != null) {
                    tvState.setText("打包失败：" + e);
                    return;
                }
                zipFile = z;
                btnShare.setEnabled(true);
                tvState.setText("已打包 " + human(z.length()));
            });
        }, "ysm-zip").start();
    }

    private void share() {
        if (zipFile == null || !zipFile.isFile()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", zipFile);
            Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享 YSM 工程"));
        } catch (Throwable t) {
            toast("分享失败：" + msg(t));
        }
    }

    private File copyToWork(Uri uri, String name) throws Exception {
        File dir = new File(getCacheDir(), "ysmin");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("建不了缓存目录");
        File dst = new File(dir, "in_" + Math.abs(name.hashCode()) + "_" + name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IllegalStateException("打不开输入流");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        return dst;
    }

    private String queryName(Uri uri) {
        String name = "model.ysm";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && s.length() > 0) name = s;
                }
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    private static void zipDir(File dir, File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zipInto(dir, dir, zos);
        }
    }

    private static void zipInto(File root, File f, ZipOutputStream zos) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) zipInto(root, k, zos);
            return;
        }
        String rel = root.toURI().relativize(f.toURI()).getPath();
        zos.putNextEntry(new ZipEntry(rel));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static int[] count(File dir) {
        int n = 0;
        long size = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    int[] c = count(k);
                    n += c[0];
                    size += c[1];
                } else {
                    n++;
                    size += k.length();
                }
            }
        }
        return new int[]{n, (int) Math.min(Integer.MAX_VALUE, size)};
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String human(long n) {
        if (n >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024L) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return n + " B";
    }

    private static String msg(Throwable t) {
        if (t == null) return "未知错误";
        String s = t.getMessage();
        return s == null || s.isEmpty() ? t.toString() : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
