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
import com.mineways.p3d.P3DToObj;
import com.mineways.p3d.P3DToObj3;
import com.mineways.p3d.P3d2Bb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P3D 工程工具（纯本地）。
 *
 * <p>Prisma3D 工程包的结构是逆向出来的（详见 {@link P3DPack} 与 {@link Mp} 的注释）：
 * 2.0 是标准 ZIP 的单体工程，3.0 是"头部被改成 60 4B 的 ZIP + 按 UUID 拆分的模块"，
 * 数据全部是 MessagePack。
 *
 * <p>这一页目前提供确定性可靠的三件事：识别版本与内容、修复被改过的头部、解包到 Download，
 * 外加把内部数据结构导成文本报告（跨版本转换的字段核对就靠它）。两个模型出口是「转 OBJ」
 * （Blender / 通用查看器）与「转 Blockbench」（静态版 / 骨骼版，内核见 {@link P3d2Bb}）。
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
    private Button btnObj;
    private Button btnBbStatic;
    private Button btnBbRig;
    /** 骨骼版但所有组轴心归零：Blockbench 把 origin 当位移时的保险选项。 */
    private Button btnBbRigZero;

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
        android.net.Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            load(open);
        }
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
        // 页头原来有一枚「‹ 返回」（点了就 finish），系统本来就有返回键/返回手势，它在第一行
        // 既占位置又容易误触 —— 已去掉，标题直接开头。
        TextView title = new TextView(this);
        title.setText("P3D 工程工具");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

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

        // 主行动：转 OBJ 通栏大按钮（最常用，放最显眼的位置）
        btnObj = primaryBtn("转 OBJ 模型（含贴图）");
        btnObj.setOnClickListener(v -> doExportObj());
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctaLp.topMargin = dp(12);
        btnObj.setLayoutParams(ctaLp);
        col.addView(btnObj);

        // 工具按钮 2×2 等宽网格：行内两个按钮各占一半，中间留 8dp 缝
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        btnFix = gridBtn("修复头部另存", true);
        btnFix.setOnClickListener(v -> doFixHeader());
        row.addView(btnFix);
        btnUnpack = gridBtn("解包到 Download", false);
        btnUnpack.setOnClickListener(v -> doUnpack());
        row.addView(btnUnpack);
        col.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(10), 0, 0);
        btnReport = gridBtn("导出数据报告", true);
        btnReport.setOnClickListener(v -> doReport());
        row2.addView(btnReport);
        btnConvert20 = gridBtn("转 2.0 工程包", false);
        btnConvert20.setOnClickListener(v -> doConvert());
        row2.addView(btnConvert20);
        col.addView(row2);

        // Blockbench 出口两个版本：静态（位置烘进顶点）与骨骼（部件可摆姿势）
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(10), 0, 0);
        btnBbStatic = gridBtn("转 Blockbench 静态版", true);
        btnBbStatic.setOnClickListener(v -> doExportBb(false, true));
        row3.addView(btnBbStatic);
        btnBbRig = gridBtn("转 Blockbench 骨骼版", false);
        btnBbRig.setOnClickListener(v -> doExportBb(true, true));
        row3.addView(btnBbRig);
        col.addView(row3);

        // 第三行：骨骼版的两个轴心选项各占一个按钮，点哪个就出哪个，不用先设开关。
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.setPadding(0, dp(8), 0, 0);
        btnBbRigZero = gridBtn("骨骼版·轴心归零", true);
        btnBbRigZero.setOnClickListener(v -> doExportBb(true, false));
        row4.addView(btnBbRigZero);
        TextView pivotHint = new TextView(this);
        pivotHint.setText("← Blockbench 里整包位移时用这个");
        pivotHint.setTextColor(DIM);
        pivotHint.setTextSize(11f);
        pivotHint.setPadding(dp(8), 0, 0, 0);
        pivotHint.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row4.addView(pivotHint);
        col.addView(row4);

        TextView note = new TextView(this);
        note.setText("\n说明：\n"
                + "· 「修复头部另存」把 3.0 的 60 4B 头改成标准 50 4B，"
                + "产出物可以直接用 MT 管理器 / 电脑解压看贴图和数据；\n"
                + "· 「解包」按原目录结构导出全部条目（含 res/ 贴图）；\n"
                + "· 「数据报告」把工程内部数据结构（MessagePack 结构树）导成 txt，"
                + "跨版本转换的字段核对用它；\n"
                + "· 「转 OBJ」把工程里的全部网格合成一个 model.obj（+ model.mtl 和贴图），"
                + "坐标系按父级变换合成，可直接进 Blender / 3D 查看器；\n"
                + "· 「转 Blockbench」出一个自包含的 .bbmodel（贴图内嵌在里面），2.0 / 3.0 都支持，"
                + "三个出口按需选：\n"
                + "  ① 静态版：世界位置烘进顶点，没有组变换，最不容易出问题；\n"
                + "  ② 骨骼版：每个部件一条可摆姿势的组骨，关节位置写进组轴心，转骨时子部件绕关节摆动；\n"
                + "  ③ 骨骼版·轴心归零：骨骼树一样有，但所有组轴心归零——某些 Blockbench 版本会把组的 "
                + "origin 当成位移，导进去整包偏移时用这个，位置与静态版逐点相同。\n"
                + "  蒙皮网格已按导出时的姿态逐顶点算过（权重和骨骼矩阵都参与），"
                + "所以头发、四肢会贴回身上；蒙皮权重与动画 clip 不搬。\n"
                + "  灯光转成 locator 记号（不是可渲染光源），环境光 / 背景色存在文件根字段里。");
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
        // OBJ 出口 2.0 / 3.0 都直接支持（3.0 走 P3DToObj3，不再置灰引导）
        btnObj.setEnabled(ready);
        btnObj.setAlpha(ready ? 1f : 0.45f);
        // Blockbench 出口同样两版都吃（内核按 .pobject 自己判版本）
        btnBbStatic.setEnabled(ready);
        btnBbStatic.setAlpha(ready ? 1f : 0.45f);
        btnBbRig.setEnabled(ready);
        btnBbRig.setAlpha(ready ? 1f : 0.45f);
        btnBbRigZero.setEnabled(ready);
        btnBbRigZero.setAlpha(ready ? 1f : 0.45f);
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

    /**
     * 转 OBJ：工程里的全部网格合成一个 model.obj（+ model.mtl + 用到的贴图），
     * 输出到 Download/P3D/obj/&lt;工程名&gt;/。
     */
    private void doExportObj() {
        final P3DPack.Pack p = pack;
        if (p == null) {
            return;
        }
        status("正在转 OBJ…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                P3DToObj.Result r = p.version == P3DPack.V_300
                        ? P3DToObj3.convert(p) : P3DToObj.convert(p);
                if (r == null) {
                    msg = "包里没有可识别的工程数据（2.0 要有 .proj，3.0 要有 project.proj + *.pobject）";
                } else {
                    String root = "P3D/obj/" + stripExt(fileName);
                    int written = 0;
                    String whereObj = saveToDownloads(root, "model.obj", r.obj.getBytes("UTF-8"));
                    if (whereObj != null) {
                        written++;
                    }
                    String whereMtl = saveToDownloads(root, "model.mtl", r.mtl.getBytes("UTF-8"));
                    if (whereMtl != null) {
                        written++;
                    }
                    // 贴图与 model.obj 放同一目录（MTL 里只写文件名）。
                    // 微软 3D 查看器对 res/ 这类相对子目录解析不稳，平铺最兼容。
                    for (Map.Entry<String, byte[]> t : r.textures.entrySet()) {
                        String zipPath = t.getKey().replace("\\", "/");
                        String base = zipPath.substring(zipPath.lastIndexOf('/') + 1);
                        if (saveToDownloads(root, base, t.getValue()) != null) {
                            written++;
                        }
                    }
                    ok = whereObj != null;
                    msg = !ok ? "写入失败（检查存储空间）"
                            : "OBJ 已导出：" + whereObj + "\n\n" + r.report()
                            + "\n贴图等共 " + written + " 个文件 → Download/P3D/obj/"
                            + stripExt(fileName) + "/";
                }
                if (ok) {
                    finishActivation(true);
                }
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
                    status("OBJ 导出完成，去 Download/P3D/obj/ 取文件");
                } else {
                    status("OBJ 导出失败");
                }
                new AlertDialog.Builder(this)
                        .setTitle(done ? "OBJ 导出完成" : "OBJ 导出失败")
                        .setMessage(out)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }).start();
    }

    /**
     * 转 Blockbench：一个自包含的 .bbmodel（贴图 base64 内嵌），写到 Download/P3D/bb/。
     * rig=false 是静态版（世界位置烘进顶点，最稳），rig=true 是骨骼版（每个部件一条可摆姿势的组骨）。
     * pivot 只在骨骼版有意义：true 把关节位置写进组轴心（能绕关节摆），false 全部归零（位置最保险）。
     */
    private void doExportBb(final boolean rig, final boolean pivot) {
        final byte[] data = raw;
        if (data == null || pack == null) {
            return;
        }
        status(!rig ? "正在转 Blockbench（静态版）…"
                : pivot ? "正在转 Blockbench（骨骼版·带轴心）…" : "正在转 Blockbench（骨骼版·轴心归零）…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                P3d2Bb.Opts o = new P3d2Bb.Opts();
                o.rig = rig;
                o.rigPivot = pivot;
                P3d2Bb.Result r = P3d2Bb.convert(data, o);
                String name = stripExt(fileName)
                        + (!rig ? "_静态" : pivot ? "_骨骼" : "_骨骼_轴心归零") + ".bbmodel";
                String where = saveToDownloads("P3D/bb", name, r.bbmodel);
                ok = where != null;
                if (ok) {
                    finishActivation(true);
                }
                msg = !ok ? "写入失败（检查存储空间）"
                        : "已写出：" + where + "（" + (r.bbmodel.length / 1024) + " KB）\n\n" + r.report();
            } catch (Throwable t) {
                msg = "转换失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String out = msg;
            final boolean done = ok;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                status(done ? "Blockbench 文件已写到 Download/P3D/bb/" : "转 Blockbench 失败");
                new AlertDialog.Builder(this)
                        .setTitle(done ? "转 Blockbench 完成" : "转 Blockbench 失败")
                        .setMessage(out)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }).start();
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
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(round(ACCENT, 12));
        b.setMinHeight(dp(50));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    /** 网格里的一半宽按钮：weight 均分整行，first 挂右侧 8dp 缝，两颗自然等宽。 */
    private Button gridBtn(String text, boolean first) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(TEXT);
        b.setBackground(round(FIELD, 12));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (first) {
            lp.rightMargin = dp(8);
        }
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
