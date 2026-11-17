package com.mineways.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mineways.open.OpenInbox;
import com.mineways.ui.MwViews;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件保险箱：口令 + 密钥双因子，任意单文件 ⇄ 加密容器（.寂密）。
 *
 * <p>全部离线，容器格式见 {@link VaultFormat}：Argon2id 派生主密钥，AES-256-GCM 双层分块加密，
 * 文件头 / 元数据 / 每个数据块都带认证，改一个字节都打不开。产物固定写到
 * {@code 下载/文件保险箱/}：加密产物直接放在这一层，解密还原的明文放在 {@code 解密/} 子目录。
 *
 * <p>这里只做界面与文件搬运，密码学与容器全部在 {@link VaultCodec} 里，不重复实现。
 */
public class VaultActivity extends Activity {

    private static final int REQ_PICK = 301;
    private static final int REQ_KEY_IN = 302;
    private static final int REQ_KEY_OUT = 303;

    private static final String PREFS = "mwvault";
    private static final String PREF_KEY = "user_key";
    private static final String OUT_DIR = "文件保险箱";
    private static final String SUFFIX = ".寂密";

    private boolean encrypt = true;
    private VaultCrypto.KdfParams kdf = VaultCrypto.KdfParams.defaults();
    private byte[] userKey;
    private boolean running;
    private volatile boolean cancelled;

    private final List<Uri> files = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private TextView tabEncrypt;
    private TextView tabDecrypt;
    private EditText edPass;
    private EditText edPass2;
    private TextView tvKey;
    private TextView tvFiles;
    private TextView tvKdfStd;
    private TextView tvKdfHigh;
    private TextView tvState;
    private TextView btnStart;
    private ProgressBar bar;
    private LinearLayout resultBox;
    private LinearLayout kdfCard;
    private LinearLayout pass2Box;
    private TextView tvPath;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!com.mineways.RecentToolFlags.vault()) {
            android.widget.Toast.makeText(this, "文件保险箱已被云端关闭", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        userKey = loadKey();
        setContentView(buildUi());
        applyMode();
        renderKey();
        Uri open = OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(MwViews.pageBackground(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MwViews.dp(this, 18), MwViews.dp(this, 10),
                MwViews.dp(this, 18), MwViews.dp(this, 36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(MwViews.header(this, "工具箱", "文件保险箱", "口令 + 密钥双因子 · 全离线"));

        // 模式
        LinearLayout mode = new LinearLayout(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        mode.setPadding(0, MwViews.dp(this, 6), 0, 0);
        tabEncrypt = MwViews.pillButton(this, "加密", true, v -> setMode(true));
        tabDecrypt = MwViews.pillButton(this, "解密", false, v -> setMode(false));
        mode.addView(tabEncrypt);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = MwViews.dp(this, 8);
        tabDecrypt.setLayoutParams(mlp);
        mode.addView(tabDecrypt);
        root.addView(mode);

        // 凭据
        LinearLayout credCard = MwViews.card(this, 14);
        credCard.addView(MwViews.section(this, "口令"));
        edPass = MwViews.input(this, "至少 8 位，建议一句话");
        edPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credCard.addView(edPass);

        pass2Box = new LinearLayout(this);
        pass2Box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams p2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2lp.topMargin = MwViews.dp(this, 8);
        pass2Box.setLayoutParams(p2lp);
        edPass2 = MwViews.input(this, "再输一次，确认没打错");
        edPass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass2Box.addView(edPass2);
        credCard.addView(pass2Box);

        credCard.addView(MwViews.section(this, "密钥"));
        tvKey = MwViews.body(this, "", MwViews.TEXT_2, 12f);
        tvKey.setTextIsSelectable(true);
        credCard.addView(tvKey);

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setPadding(0, MwViews.dp(this, 8), 0, 0);
        keyRow.addView(MwViews.pillButton(this, "生成新密钥", false, v -> resetKey()));
        TextView imp = MwViews.pillButton(this, "导入", false, v -> pickKeyFile());
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.leftMargin = MwViews.dp(this, 8);
        imp.setLayoutParams(klp);
        keyRow.addView(imp);
        TextView exp = MwViews.pillButton(this, "导出", false, v -> exportKey());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = MwViews.dp(this, 8);
        exp.setLayoutParams(elp);
        keyRow.addView(exp);
        credCard.addView(keyRow);

        TextView keyTip = MwViews.body(this,
                "密钥只保存在本机。丢了密钥，只有口令也打不开；换手机时先用「导出」把密钥带走。",
                MwViews.TEXT_3, 11f);
        keyTip.setPadding(0, MwViews.dp(this, 8), 0, 0);
        credCard.addView(keyTip);
        root.addView(credCard);

        // 强度
        kdfCard = MwViews.card(this, 12);
        kdfCard.addView(MwViews.section(this, "强度"));
        LinearLayout kdfRow = new LinearLayout(this);
        kdfRow.setOrientation(LinearLayout.HORIZONTAL);
        tvKdfStd = MwViews.pillButton(this, "标准", true, v -> setKdf(false));
        tvKdfHigh = MwViews.pillButton(this, "强力（更慢）", false, v -> setKdf(true));
        kdfRow.addView(tvKdfStd);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = MwViews.dp(this, 8);
        tvKdfHigh.setLayoutParams(hlp);
        kdfRow.addView(tvKdfHigh);
        kdfCard.addView(kdfRow);
        TextView kdfTip = MwViews.body(this,
                "标准：Argon2id 32 MiB × 3\n强力：Argon2id 256 MiB × 6，抗暴力更强，老机可能卡几十秒",
                MwViews.TEXT_3, 11f);
        kdfTip.setPadding(0, MwViews.dp(this, 8), 0, 0);
        kdfCard.addView(kdfTip);
        root.addView(kdfCard);

        // 文件
        LinearLayout fileCard = MwViews.card(this, 12);
        fileCard.addView(MwViews.section(this, "文件"));
        tvFiles = MwViews.body(this, "未选择", MwViews.TEXT_2, 12f);
        tvFiles.setTextIsSelectable(true);
        fileCard.addView(tvFiles);
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setPadding(0, MwViews.dp(this, 8), 0, 0);
        fileRow.addView(MwViews.pillButton(this, "选择文件", true, v -> pickFiles()));
        TextView clear = MwViews.pillButton(this, "清空", false, v -> clearFiles());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = MwViews.dp(this, 8);
        clear.setLayoutParams(clp);
        fileRow.addView(clear);
        fileCard.addView(fileRow);
        root.addView(fileCard);

        // 执行
        LinearLayout runCard = MwViews.card(this, 12);
        btnStart = MwViews.pillButton(this, "开始加密", true, v -> startRun());
        runCard.addView(btnStart);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MwViews.dp(this, 6));
        blp.topMargin = MwViews.dp(this, 12);
        bar.setLayoutParams(blp);
        runCard.addView(bar);
        tvState = MwViews.body(this, "", MwViews.TEXT_2, 12f);
        tvState.setPadding(0, MwViews.dp(this, 8), 0, 0);
        runCard.addView(tvState);
        root.addView(runCard);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);

        TextView path = MwViews.body(this, "", MwViews.TEXT_3, 11f);
        path.setPadding(0, MwViews.dp(this, 14), 0, 0);
        tvPath = path;
        root.addView(path);

        return scroll;
    }

    private void setMode(boolean enc) {
        encrypt = enc;
        applyMode();
        clearFiles();
        tvState.setText("");
        resultBox.removeAllViews();
        bar.setProgress(0);
    }

    private void applyMode() {
        tabEncrypt.setBackground(encrypt ? MwViews.accentFill(this, 100, true)
                : MwViews.outline(this, 100, MwViews.HAIRLINE));
        tabEncrypt.setTextColor(encrypt ? MwViews.ON_ACCENT : MwViews.TEXT_2);
        tabDecrypt.setBackground(!encrypt ? MwViews.accentFill(this, 100, true)
                : MwViews.outline(this, 100, MwViews.HAIRLINE));
        tabDecrypt.setTextColor(!encrypt ? MwViews.ON_ACCENT : MwViews.TEXT_2);
        pass2Box.setVisibility(encrypt ? View.VISIBLE : View.GONE);
        kdfCard.setVisibility(encrypt ? View.VISIBLE : View.GONE);
        btnStart.setText(encrypt ? "开始加密" : "开始解密");
        if (tvPath != null) {
            tvPath.setText("产物目录：下载/" + OUT_DIR + (encrypt ? "/" : "/解密/"));
        }
    }

    private void setKdf(boolean strong) {
        kdf = strong
                ? new VaultCrypto.KdfParams(VaultCrypto.KDF_ARGON2ID, 256 * 1024, 6, 1)
                : VaultCrypto.KdfParams.defaults();
        tvKdfStd.setBackground(!strong ? MwViews.accentFill(this, 100, true)
                : MwViews.outline(this, 100, MwViews.HAIRLINE));
        tvKdfStd.setTextColor(!strong ? MwViews.ON_ACCENT : MwViews.TEXT_2);
        tvKdfHigh.setBackground(strong ? MwViews.accentFill(this, 100, true)
                : MwViews.outline(this, 100, MwViews.HAIRLINE));
        tvKdfHigh.setTextColor(strong ? MwViews.ON_ACCENT : MwViews.TEXT_2);
    }

    // ------------------------------------------------------------------ 密钥

    private byte[] loadKey() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = sp.getString(PREF_KEY, null);
        if (s != null) {
            try {
                byte[] k = Base64.decode(s, Base64.NO_WRAP);
                if (k.length == VaultCrypto.KEY_BYTES) {
                    return k;
                }
            } catch (Throwable ignored) {
            }
        }
        byte[] k = VaultCrypto.random(VaultCrypto.KEY_BYTES);
        sp.edit().putString(PREF_KEY, Base64.encodeToString(k, Base64.NO_WRAP)).apply();
        return k;
    }

    private void saveKey(byte[] k) {
        userKey = k;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_KEY, Base64.encodeToString(k, Base64.NO_WRAP)).apply();
        renderKey();
    }

    private void renderKey() {
        if (userKey == null) {
            tvKey.setText("密钥缺失：点「生成新密钥」");
            return;
        }
        tvKey.setText("指纹 " + VaultCrypto.fingerprint(userKey) + " · " + VaultCrypto.encodeKeyText(userKey));
    }

    private void resetKey() {
        if (running) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("生成新密钥？")
                .setMessage("换密钥后，之前用旧密钥加密的文件只能用旧密钥打开。\n如果那些文件还要用，先把旧密钥「导出」存好。")
                .setPositiveButton("生成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showKeyOnce(VaultCrypto.random(VaultCrypto.KEY_BYTES));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 新密钥先给用户看一眼并要求导出，避免「换完就忘」。 */
    private void showKeyOnce(byte[] k) {
        saveKey(k);
        new AlertDialog.Builder(this)
                .setTitle("新密钥已生成")
                .setMessage(VaultCrypto.encodeKeyText(k) + "\n\n指纹 " + VaultCrypto.fingerprint(k)
                        + "\n\n现在导出一份存到别处，丢了就解不开了。")
                .setPositiveButton("导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        exportKey();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void pickKeyFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_KEY_IN);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    private void importKey(Uri uri) {
        try {
            byte[] raw = readSmall(uri);
            String text = new String(raw, "UTF-8").trim();
            byte[] k = VaultCrypto.decodeKeyText(text);
            saveKey(k);
            toast("密钥已导入：" + VaultCrypto.fingerprint(k));
        } catch (Throwable t) {
            toast("导入失败：" + msg(t));
        }
    }

    private void exportKey() {
        if (userKey == null) {
            toast("还没有密钥");
            return;
        }
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_TITLE, "mwvault-" + VaultCrypto.fingerprint(userKey) + ".mwkey");
        try {
            startActivityForResult(it, REQ_KEY_OUT);
        } catch (Throwable t) {
            toast("没有可用的保存对话框");
        }
    }

    private void writeKey(Uri uri) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                throw new IOException("打不开输出流");
            }
            try {
                out.write(VaultCrypto.encodeKeyText(userKey).getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            toast("密钥已导出");
        } catch (Throwable t) {
            toast("导出失败：" + msg(t));
        }
    }

    // ------------------------------------------------------------------ 选文件

    private void pickFiles() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
        if (res != RESULT_OK || data == null) {
            return;
        }
        if (req == REQ_KEY_IN && data.getData() != null) {
            importKey(data.getData());
            return;
        }
        if (req == REQ_KEY_OUT && data.getData() != null) {
            writeKey(data.getData());
            return;
        }
        if (req != REQ_PICK) {
            return;
        }
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                addFile(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addFile(data.getData());
        }
        renderFiles();
    }

    /** 外部「打开方式」进来自动选好模式并载入。 */
    private void acceptIncoming(Uri uri) {
        String name = OpenInbox.nameOf(this, uri);
        boolean vaultFile = name.toLowerCase(Locale.ROOT).endsWith(SUFFIX)
                || name.toLowerCase(Locale.ROOT).endsWith(".mwv");
        if (vaultFile != !encrypt) {
            encrypt = vaultFile;
            applyMode();
        }
        files.clear();
        names.clear();
        addFile(uri);
        renderFiles();
        tvState.setText(vaultFile ? "已载入容器，输入口令后点「开始解密」" : "已载入文件，输入口令后点「开始加密」");
    }

    private void addFile(Uri uri) {
        if (uri == null || files.contains(uri)) {
            return;
        }
        files.add(uri);
        names.add(OpenInbox.nameOf(this, uri));
    }

    private void clearFiles() {
        files.clear();
        names.clear();
        renderFiles();
    }

    private void renderFiles() {
        if (files.isEmpty()) {
            tvFiles.setText("未选择");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            long size = OpenInbox.sizeOf(this, files.get(i));
            sb.append(i + 1).append(". ").append(names.get(i));
            if (size >= 0) {
                sb.append("（").append(OpenInbox.humanSize(size)).append("）");
            }
            if (i + 1 < names.size()) {
                sb.append('\n');
            }
        }
        tvFiles.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 执行

    private void startRun() {
        if (running) {
            cancelled = true;
            tvState.setText("正在取消…");
            return;
        }
        if (userKey == null) {
            toast("请先生成或导入密钥");
            return;
        }
        char[] pass = edPass.getText().toString().toCharArray();
        if (pass.length < 8) {
            toast("口令至少 8 位");
            return;
        }
        if (encrypt && !edPass.getText().toString().equals(edPass2.getText().toString())) {
            toast("两次口令不一致");
            return;
        }
        if (files.isEmpty()) {
            toast("先选文件");
            return;
        }
        final List<Uri> uris = new ArrayList<>(files);
        final List<String> fileNames = new ArrayList<>(names);
        final boolean enc = encrypt;
        final VaultCrypto.KdfParams params = kdf;
        final byte[] key = userKey;

        running = true;
        cancelled = false;
        btnStart.setText("取消");
        resultBox.removeAllViews();
        bar.setProgress(0);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder report = new StringBuilder();
                String fatal = null;
                try {
                    if (enc) {
                        doEncrypt(uris, fileNames, pass, key, params, report);
                    } else {
                        doDecrypt(uris, fileNames, pass, key, report);
                    }
                } catch (Throwable t) {
                    fatal = msg(t);
                } finally {
                    VaultCrypto.wipe(pass);
                }
                final String f = fatal;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        running = false;
                        btnStart.setText(encrypt ? "开始加密" : "开始解密");
                        if (cancelled) {
                            tvState.setText(report + "\n已取消");
                        } else if (f != null) {
                            tvState.setText(report + "\n失败：" + f);
                            bar.setProgress(0);
                        } else {
                            tvState.setText(report + "\n完成");
                            bar.setProgress(1000);
                        }
                    }
                });
            }
        }, "vault").start();
    }

    private void doEncrypt(List<Uri> uris, List<String> fileNames, char[] pass, byte[] key,
                           VaultCrypto.KdfParams params, StringBuilder report) throws Exception {
        final VaultCodec.Session session = VaultCodec.Session.create(pass, key, params);
        try {
            for (int i = 0; i < uris.size(); i++) {
                if (cancelled) {
                    return;
                }
                final Uri uri = uris.get(i);
                final String name = fileNames.get(i);
                final int idx = i + 1;
                final int total = uris.size();

                File temp = null;
                long size = OpenInbox.sizeOf(this, uri);
                InputStream in;
                if (size < 0) {
                    temp = copyToCache(uri, name);
                    size = temp.length();
                    in = new FileInputStream(temp);
                } else {
                    in = openIn(uri);
                }

                Dest dest = null;
                try {
                    dest = openDest(baseName(name) + SUFFIX, "");
                    VaultFormat.Meta meta = new VaultFormat.Meta(VaultFormat.META_FILE, name, size,
                            lastModified(uri));
                    final int basePercent = (int) ((i * 1000L) / total);
                    VaultCodec.Result r = session.encrypt(in, dest.out, meta,
                            new VaultCodec.Progress() {
                                @Override
                                public void onProgress(long done, long total2) {
                                    final int p = basePercent + (int) ((done * 1000L / Math.max(1, total2))
                                            / total);
                                    progress(p, "加密 " + idx + "/" + total + " · " + name);
                                }
                            },
                            new VaultCodec.Cancel() {
                                @Override
                                public boolean cancelled() {
                                    return cancelled;
                                }
                            });
                    dest.commit();
                    report.append("· ").append(name).append(" → ").append(dest.shown)
                            .append("（").append(OpenInbox.humanSize(r.containerBytes)).append("）\n");
                    addResult(dest);
                } finally {
                    closeQuietly(in);
                    if (temp != null) {
                        temp.delete();
                    }
                    if (dest != null) {
                        dest.abortIfOpen();
                    }
                }
            }
        } finally {
            session.close();
        }
    }

    private void doDecrypt(List<Uri> uris, List<String> fileNames, char[] pass, byte[] key,
                           StringBuilder report) throws Exception {
        Map<String, VaultCodec.Session> cache = new HashMap<>();
        try {
            for (int i = 0; i < uris.size(); i++) {
                if (cancelled) {
                    return;
                }
                final Uri uri = uris.get(i);
                final int idx = i + 1;
                final int total = uris.size();

                VaultFormat.Header header;
                InputStream probe = openIn(uri);
                try {
                    header = VaultFormat.read(probe);
                } finally {
                    closeQuietly(probe);
                }
                VaultCodec.Session session = sessionFor(cache, header, pass, key);

                VaultCodec.Open open = session.openContainer(openIn(uri), new VaultCodec.Cancel() {
                    @Override
                    public boolean cancelled() {
                        return cancelled;
                    }
                });
                Dest dest = null;
                try {
                    VaultFormat.Meta meta = open.meta();
                    String outName = safeName(meta.name);
                    final Dest dd = openDest(outName, "/解密");
                    dest = dd;
                    final int basePercent = (int) ((i * 1000L) / total);
                    final VaultCodec.Result r = open.decryptTo(new VaultCodec.Sink() {
                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            dd.out.write(b, off, len);
                        }
                    }, new VaultCodec.Progress() {
                        @Override
                        public void onProgress(long done, long total2) {
                            final int p = basePercent + (int) ((total2 > 0
                                    ? done * 1000L / total2 : 500) / total);
                            progress(p, "解密 " + idx + "/" + total + " · " + outName);
                        }
                    }, new VaultCodec.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return cancelled;
                        }
                    });
                    dest.commit();
                    report.append("· ").append(fileNames.get(i)).append(" → ").append(dest.shown)
                            .append("（").append(OpenInbox.humanSize(r.plainBytes)).append("）\n");
                    addResult(dest);
                } finally {
                    open.close();
                    if (dest != null) {
                        dest.abortIfOpen();
                    }
                }
            }
        } finally {
            for (VaultCodec.Session s : cache.values()) {
                s.close();
            }
            cache.clear();
        }
    }

    /** 同一组 KDF 参数 + 盐只跑一次 Argon2id（批量解密时省时间）。 */
    private VaultCodec.Session sessionFor(Map<String, VaultCodec.Session> cache,
                                          VaultFormat.Header h, char[] pass, byte[] key)
            throws VaultException {
        if (!h.kdf.sane()) {
            throw new VaultException("读取文件头", "KDF 参数超出支持范围（文件可能被伪造）");
        }
        String ck = h.kdf.id + ":" + h.kdf.memKiB + ":" + h.kdf.iters + ":" + h.kdf.lanes + ":"
                + VaultCrypto.hex(h.saltKdf, h.saltKdf.length) + ":"
                + VaultCrypto.hex(h.saltHkdf, h.saltHkdf.length);
        VaultCodec.Session s = cache.get(ck);
        if (s == null) {
            s = VaultCodec.Session.open(pass, key, h.kdf, h.saltKdf, h.saltHkdf);
            cache.put(ck, s);
        }
        return s;
    }

    private void progress(final int perMille, final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bar.setProgress(Math.max(0, Math.min(1000, perMille)));
                tvState.setText(text);
            }
        });
    }

    // ------------------------------------------------------------------ 输出

    private final class Dest implements Closeable {
        OutputStream out;
        Uri uri;
        File file;
        String shown;
        boolean done;

        @Override
        public void close() throws IOException {
            if (out != null) {
                out.close();
                out = null;
            }
        }

        void commit() throws IOException {
            close();
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                try {
                    getContentResolver().update(uri, v, null, null);
                } catch (Throwable ignored) {
                }
            }
            done = true;
        }

        void abortIfOpen() {
            if (done) {
                return;
            }
            closeQuietly(out);
            out = null;
            try {
                if (uri != null) {
                    getContentResolver().delete(uri, null, null);
                } else if (file != null) {
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private Dest openDest(String displayName, String sub) throws IOException {
        Dest d = new Dest();
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + OUT_DIR + sub;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new IOException("无法在「下载」里创建文件");
            }
            d.uri = uri;
            d.out = getContentResolver().openOutputStream(uri);
            if (d.out == null) {
                throw new IOException("打不开新文件的输出流");
            }
            d.shown = "下载/" + OUT_DIR + sub + "/" + displayName;
            return d;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), OUT_DIR + sub);
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(getExternalFilesDir(null), OUT_DIR + sub);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                throw new IOException("无法创建输出目录");
            }
        }
        File f = new File(dir, displayName);
        d.file = f;
        d.out = new FileOutputStream(f);
        d.shown = f.getAbsolutePath();
        return d;
    }

    private void addResult(final Dest d) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                LinearLayout card = MwViews.card(VaultActivity.this, 10);
                TextView name = MwViews.body(VaultActivity.this, d.shown, MwViews.TEXT, 13f);
                card.addView(name);
                TextView share = MwViews.pillButton(VaultActivity.this, "分享 / 导出", false,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                shareUri(d.uri, d.file);
                            }
                        });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = MwViews.dp(VaultActivity.this, 8);
                share.setLayoutParams(lp);
                card.addView(share);
                resultBox.addView(card);
            }
        });
    }

    private void shareUri(Uri contentUri, File f) {
        try {
            Uri uri = contentUri;
            if (uri == null) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            }
            Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享"));
        } catch (Throwable t) {
            toast("分享失败：" + msg(t));
        }
    }

    // ------------------------------------------------------------------ 小工具

    private InputStream openIn(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("打不开输入流");
        }
        return in;
    }

    private byte[] readSmall(Uri uri) throws IOException {
        InputStream in = openIn(uri);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int guard = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                guard += n;
                if (guard > 64 * 1024) {
                    throw new IOException("文件太大，不像是密钥文件");
                }
            }
            return bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private File copyToCache(Uri uri, String name) throws IOException {
        File dir = new File(getCacheDir(), "vault-in");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("缓存目录不可用");
        }
        File f = new File(dir, "in-" + System.nanoTime() + "-" + safeName(name));
        InputStream in = openIn(uri);
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            closeQuietly(in);
        }
        return f;
    }

    private long lastModified(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                long v = c.getLong(0);
                return v > 100000000000L ? v : v * 1000L;
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String baseName(String name) {
        if (name == null || name.isEmpty()) {
            return "文件";
        }
        return name;
    }

    /** 明文文件名只保留最后一段，并挡掉路径分隔符与保留字符。 */
    private static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "解密文件";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String s = slash >= 0 ? name.substring(slash + 1) : name;
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
            return "解密文件";
        }
        return s;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static String msg(Throwable t) {
        if (t instanceof VaultException) {
            return ((VaultException) t).display();
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        if (userKey != null) {
            VaultCrypto.wipe(userKey);
        }
    }
}
