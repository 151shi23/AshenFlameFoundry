package com.mineways.blender;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 工程工具页：把「第三方开源模板 / 别人给的 .blend」改成能用的状态。
 *
 * <p>六块：① 体检与环境诊断；② 结构化列工程内容；③ 材质批量改值与换贴图；
 * ④ UV 展开；⑤ 场景与输出设置；⑥ 打包 / 另存 / 导出 / 第三方模板下载。</p>
 *
 * <p>所有操作都跑在沙箱里的 bpy 上（{@link ProjectOps}），失败只影响那一项。</p>
 */
public class ProjectToolsActivity extends ComponentActivity {

    public static final String EXTRA_PROJECT = "project";

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int INPUT = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;
    private static final int OK = 0xFF8FE3B0;

    private static final String[] MAPS = {"基础色 base_color", "法线 normal", "粗糙度 roughness",
            "金属度 metallic", "发光 emission", "透明度 alpha", "AO ao"};
    private static final String[] MAP_IDS = {"base_color", "normal", "roughness", "metallic",
            "emission", "alpha", "ao"};
    private static final String[] FIELDS = {"基础色", "金属度", "粗糙度", "IOR", "alpha",
            "自发光颜色", "自发光强度", "高光 Specular", "清漆 Coat", "透射 Transmission"};
    private static final String[] FIELD_IDS = {"Base Color", "Metallic", "Roughness", "IOR", "Alpha",
            "Emission Color", "Emission Strength", "Specular", "Coat Weight", "Transmission Weight"};
    private static final String[] UV_OPS = {"Smart UV Project（推荐）", "角度展开 Unwrap", "立方投影",
            "最小曲面（平铺用）"};
    private static final String[] UV_IDS = {"smart_project", "unwrap", "cube", "clear"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private File project;
    private TextView tvProject;
    private TextView tvInfo;
    private TextView tvStatus;
    private TextView tvLog;
    private EditText etMatRegex;
    private EditText etObjRegex;
    private Spinner spMap;
    private Spinner spField;
    private Spinner spUvOp;
    private EditText etColorRgb;
    private EditText etValue;
    private EditText etAngle;
    private EditText etMargin;
    private EditText etStart;
    private EditText etEnd;
    private EditText etFps;
    private EditText etResX;
    private EditText etResY;
    private EditText etTemplateUrl;
    private EditText etHdriStrength;
    private LinearLayout polyBox;
    private boolean busy;

    private final ActivityResultLauncher<Intent> pickFile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    pickLocalTexture(result.getData().getData());
                }
            });

    private final ActivityResultLauncher<Intent> pickBlend = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    importProject(result.getData().getData());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PROJECT);
        if (path != null) {
            final File f = new File(path);
            if (f.isFile()) project = f;
            else {
                final File alt = new File(BlenderEnv.workDir(this), path);
                if (alt.isFile()) project = alt;
            }
        }
        try {
            setContentView(buildUi());
            refreshProject();
            tvLog.setText("");
        } catch (Throwable t) {
            // 界面构建失败也不闪退，直接显示堆栈
            final LinearLayout box = col();
            box.setBackgroundColor(BG);
            box.setPadding(dp(16), dp(24), dp(16), dp(24));
            box.addView(text("工程工具页构建失败（已兜底）", 18f, ACCENT));
            final java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            final TextView tv = text(sw.toString(), 11.5f, TEXT);
            tv.setTextIsSelectable(true);
            box.addView(pad(tv, 0, 10, 0, 10));
            final ScrollView wrap = new ScrollView(this);
            wrap.addView(box);
            setContentView(wrap);
        }
    }

    // ================================================================ 界面

    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        final LinearLayout root = col();
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(root);

        root.addView(text("工程工具", 22f, TEXT));
        root.addView(pad(text("改材质、换贴图、套 UV、打包贴图、另存 / 导出、下载第三方开源模板。"
                + "所有操作都在 Blender 里真跑（bpy），失败只影响那一条。", 13f, DIM), 0, 8, 0, 14));

        // ① 工程 + 体检
        final LinearLayout c1 = card();
        c1.addView(text("① 工程与体检", 15f, TEXT));
        tvProject = text("", 13f, TEXT);
        c1.addView(pad(tvProject, 6, 0, 0, 6));
        final LinearLayout r1 = row();
        r1.addView(btn("选择 .blend", v -> chooseBlend()));
        r1.addView(btn("体检（读工程内容）", v -> inspect()));
        r1.addView(btn("环境诊断", v -> tvLog.setText(Preflight.diagnostics(this))));
        c1.addView(r1);
        tvInfo = text("", 12.5f, DIM);
        c1.addView(pad(tvInfo, 8, 0, 0, 0));
        root.addView(c1);

        // ② 材质
        final LinearLayout c2 = card();
        c2.addView(text("② 材质（批量改值 / 换贴图）", 15f, TEXT));
        c2.addView(label("材质名正则（留空 = 全部材质）"));
        etMatRegex = input("例如 ^Wood.* 或留空", InputType.TYPE_CLASS_TEXT);
        c2.addView(etMatRegex);
        c2.addView(label("要改的字段"));
        spField = spinner(FIELDS);
        c2.addView(spField);
        final LinearLayout r2 = row();
        etColorRgb = input("颜色 r,g,b（0~1）", InputType.TYPE_CLASS_TEXT);
        etValue = input("数值", InputType.TYPE_CLASS_TEXT);
        r2.addView(etColorRgb, weight());
        r2.addView(etValue, weight());
        c2.addView(pad(r2, 6, 0, 0, 6));
        c2.addView(btn("应用字段值", v -> applyField()));
        c2.addView(label("贴图槽位"));
        spMap = spinner(MAPS);
        c2.addView(spMap);
        final LinearLayout r3 = row();
        r3.addView(btn("用本地图片", v -> pickTexture()));
        r3.addView(btn("从 Poly Haven 选", v -> polyHaven()));
        c2.addView(r3);
        c2.addView(pad(text("Poly Haven 资产为 CC0（可商用免署名）；本页调用其公开 API，"
                + "Powered by Poly Haven。", 11.5f, DIM), 6, 0, 0, 0));
        root.addView(c2);

        // ③ UV
        final LinearLayout c3 = card();
        c3.addView(text("③ UV", 15f, TEXT));
        c3.addView(label("对象名正则（留空 = 全部网格）"));
        etObjRegex = input("例如 Body|Head 或留空", InputType.TYPE_CLASS_TEXT);
        c3.addView(etObjRegex);
        c3.addView(label("展开方式"));
        spUvOp = spinner(UV_OPS);
        c3.addView(spUvOp);
        final LinearLayout r4 = row();
        etAngle = input("角度 66", InputType.TYPE_CLASS_NUMBER);
        etMargin = input("间距 0.02", InputType.TYPE_CLASS_NUMBER);
        r4.addView(etAngle, weight());
        r4.addView(etMargin, weight());
        c3.addView(pad(r4, 6, 0, 0, 6));
        c3.addView(btn("开始展开", v -> runUv()));
        root.addView(c3);

        // ④ 场景与输出
        final LinearLayout c4 = card();
        c4.addView(text("④ 场景与输出", 15f, TEXT));
        final LinearLayout r5 = row();
        etFps = input("帧率", InputType.TYPE_CLASS_NUMBER);
        etStart = input("起始帧", InputType.TYPE_CLASS_NUMBER);
        etEnd = input("结束帧", InputType.TYPE_CLASS_NUMBER);
        r5.addView(etFps, weight());
        r5.addView(etStart, weight());
        r5.addView(etEnd, weight());
        c4.addView(pad(r5, 6, 0, 0, 6));
        final LinearLayout r6 = row();
        etResX = input("宽", InputType.TYPE_CLASS_NUMBER);
        etResY = input("高", InputType.TYPE_CLASS_NUMBER);
        r6.addView(etResX, weight());
        r6.addView(etResY, weight());
        c4.addView(pad(r6, 6, 0, 0, 6));
        c4.addView(btn("写入工程设置", v -> applyScene()));
        root.addView(c4);

        // ⑤ 打包 / 保存 / 导出
        final LinearLayout c5 = card();
        c5.addView(text("⑤ 打包 / 另存 / 导出", 15f, TEXT));
        c5.addView(pad(text("贴图打包进 .blend 后，工程就变成单文件，换设备/上传都不会丢图。",
                12f, DIM), 0, 4, 0, 6));
        final LinearLayout r7 = row();
        r7.addView(btn("打包所有贴图", v -> packAll()));
        r7.addView(btn("另存为新工程", v -> saveAs()));
        r7.addView(btn("导出 GLB / OBJ / USD / FBX", v -> exportDialog()));
        c5.addView(r7);
        root.addView(c5);

        // ⑥ 第三方模板 / HDRI
        final LinearLayout c6 = card();
        c6.addView(text("⑥ 第三方模板与 HDRI", 15f, TEXT));
        c6.addView(label("模板包直链（.blend 或含 .blend 的 .zip）"));
        etTemplateUrl = input("https://…/scene.blend", InputType.TYPE_CLASS_TEXT);
        c6.addView(etTemplateUrl);
        c6.addView(btn("下载并设为当前工程", v -> fetchTemplate()));
        c6.addView(label("Poly Haven HDRI 强度"));
        etHdriStrength = input("1.0", InputType.TYPE_CLASS_NUMBER);
        c6.addView(etHdriStrength);
        c6.addView(btn("从 Poly Haven 选 HDRI", v -> polyHavenHdri()));
        polyBox = col();
        c6.addView(polyBox);
        root.addView(c6);

        tvStatus = text("", 13f, ACCENT);
        root.addView(tvStatus);

        final LinearLayout c7 = card();
        c7.addView(text("运行记录", 15f, TEXT));
        tvLog = text("", 11.5f, DIM);
        tvLog.setTextIsSelectable(true);
        final ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(round(0xFF111316, 10));
        logScroll.addView(tvLog);
        c7.addView(pad(logScroll, 8, 0, 0, 0));
        root.addView(c7);
        return scroll;
    }

    // ================================================================ 工程选择

    private void refreshProject() {
        if (tvProject == null) return;
        if (project == null || !project.isFile()) {
            tvProject.setText("还没选工程。可以先选本机 .blend，或到下面下一份第三方模板。");
            return;
        }
        tvProject.setText("当前工程：" + project.getName()
                + "（" + FileUtil.human(project.length()) + "）\n" + project.getAbsolutePath());
    }

    private void chooseBlend() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-blender", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pickBlend.launch(i);
    }

    private void importProject(Uri uri) {
        final String name = queryName(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".blend")) {
            toast("只支持 .blend 工程文件");
            return;
        }
        run("复制工程", () -> {
            final File dir = new File(BlenderEnv.workDir(this), "proj");
            FileUtil.mkdirs(dir);
            final File dst = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) return "读不到文件（没有授权？）";
                final byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                //noinspection ResultOfMethodCallIgnored
                dst.setReadable(true, false);
                project = dst;
                refreshProject();
                return "已导入：" + name;
            } catch (Throwable t) {
                return "导入失败：" + t;
            }
        });
    }

    // ================================================================ 体检

    private void inspect() {
        if (!requireProject()) return;
        run("体检中（打开工程读一遍）", () -> {
            final ProjectOps.Info info = ProjectOps.inspect(this, project, this::log);
            final String text = info.summary();
            ui.post(() -> tvInfo.setText(text));
            return text;
        });
    }

    private boolean requireProject() {
        if (project != null && project.isFile()) return true;
        toast("先选一个 .blend 工程");
        return false;
    }

    private boolean requireEnv() {
        if (!RootShell.isReady()) {
            toast("先授权 Shizuku");
            return false;
        }
        if (!BlenderEnv.installed(this)) {
            toast("先到「Blender 渲染」页装好环境");
            return false;
        }
        return true;
    }

    // ================================================================ 材质

    private void applyField() {
        if (!requireProject() || !requireEnv()) return;
        final int idx = Math.max(0, Math.min(spField.getSelectedItemPosition(), FIELD_IDS.length - 1));
        final String field = FIELD_IDS[idx];
        final double[] value = parseValue(etValue.getText().toString(), etColorRgb.getText().toString(),
                field.contains("Color") || "Base Color".equals(field));
        if (value == null) {
            toast("数值填法：颜色用 r,g,b（0~1），其它填一个数");
            return;
        }
        final String regex = etMatRegex.getText().toString().trim();
        run("改材质：" + FIELDS[idx], () -> {
            final BlenderOps.Result r = ProjectOps.setMaterialValues(this, project, regex, field, value,
                    this::log);
            return describe(r, "已改 " + countOf(r, "changed") + " 个材质");
        });
    }

    private void pickTexture() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pickFile.launch(i);
    }

    private void pickLocalTexture(Uri uri) {
        if (!requireProject() || !requireEnv()) return;
        final String name = queryName(uri);
        run("复制贴图并替换", () -> {
            final File dir = new File(BlenderEnv.workDir(this), "tex");
            FileUtil.mkdirs(dir);
            final File dst = new File(dir, name == null ? "tex.png" : name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) return "读不到图片";
                final byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                //noinspection ResultOfMethodCallIgnored
                dst.setReadable(true, false);
            } catch (Throwable t) {
                return "复制图片失败：" + t;
            }
            final String map = MAP_IDS[Math.max(0, Math.min(spMap.getSelectedItemPosition(), MAP_IDS.length - 1))];
            final BlenderOps.Result r = AssetLib.applyTexture(this, project, regex(), map, dst, this::log);
            return describe(r, "贴图已替换到 " + countOf(r, "changed") + " 个材质");
        });
    }

    private String regex() {
        final String s = etMatRegex.getText().toString().trim();
        return s;
    }

    // ================================================================ Poly Haven

    private void polyHaven() {
        if (!requireProject() || !requireEnv()) return;
        final EditText q = input("关键词，例如 wood / fabric / concrete", InputType.TYPE_CLASS_TEXT);
        final LinearLayout box = col();
        box.addView(q);
        final ScrollView wrap = new ScrollView(this);
        wrap.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("搜 Poly Haven 贴图（CC0）")
                .setMessage("搜索会拉一次资产列表（约几 MB，之后本地缓存一天）。")
                .setView(wrap)
                .setPositiveButton("搜索", (d, w) -> {
                    final String key = q.getText().toString().trim();
                    run("搜索中", () -> {
                        final List<AssetLib.Asset> list = AssetLib.search(this, AssetLib.TYPE_TEXTURE, key, 40);
                        ui.post(() -> showTexturePicker(list));
                        return "找到 " + list.size() + " 个贴图资产";
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTexturePicker(final List<AssetLib.Asset> list) {
        if (list.isEmpty()) {
            toast("没搜到，换个词试试（Poly Haven 的资产名是英文）");
            return;
        }
        final String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = list.get(i).name + "\n" + list.get(i).id;
        }
        new AlertDialog.Builder(this)
                .setTitle("选一个贴图资产")
                .setItems(items, (d, i) -> downloadTexture(list.get(i)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadTexture(final AssetLib.Asset asset) {
        run("取文件清单：" + asset.id, () -> {
            final List<AssetLib.AssetFile> files = AssetLib.files(this, asset.id);
            final String map = MAP_IDS[Math.max(0, Math.min(spMap.getSelectedItemPosition(), MAP_IDS.length - 1))];
            AssetLib.AssetFile pick = null;
            for (AssetLib.AssetFile f : files) {
                if (f.key.equals(map)) {
                    pick = f;
                    break;
                }
            }
            if (pick == null) {
                for (AssetLib.AssetFile f : files) {
                    if (f.key.equals("base_color") || f.key.equals("blend")) {
                        pick = f;
                        break;
                    }
                }
            }
            if (pick == null) {
                return "这个资产没有可用的贴图文件（" + files.size() + " 项）";
            }
            final File local = AssetLib.download(this, pick, asset.id + "_" + pick.key,
                    (done, total) -> ui.post(() -> status("下载 " + FileUtil.human(done)
                            + (total > 0 ? " / " + FileUtil.human(total) : ""))), Downloader.NEVER);
            if ("blend".equals(pick.key)) {
                return "拿到的是 .bl 材质库（" + local.getName()
                        + "）。建议改选具体贴图（base_color 等）直接替换；";
            }
            final String mapKey = pick.key;
            final BlenderOps.Result r = AssetLib.applyTexture(this, project, regex(), mapKey, local, this::log);
            return describe(r, "已用 Poly Haven 的 " + asset.name + " 替换 " + mapKey);
        });
    }

    private void polyHavenHdri() {
        if (!requireProject() || !requireEnv()) return;
        final EditText q = input("关键词，例如 studio / sunset / night", InputType.TYPE_CLASS_TEXT);
        final ScrollView wrap = new ScrollView(this);
        final LinearLayout box = col();
        box.addView(q);
        wrap.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("搜 Poly Haven HDRI（CC0）")
                .setView(wrap)
                .setPositiveButton("搜索", (d, w) -> {
                    final String key = q.getText().toString().trim();
                    run("搜索中", () -> {
                        final List<AssetLib.Asset> list = AssetLib.search(this, AssetLib.TYPE_HDRI, key, 30);
                        ui.post(() -> showHdriPicker(list));
                        return "找到 " + list.size() + " 个 HDRI";
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHdriPicker(final List<AssetLib.Asset> list) {
        if (list.isEmpty()) {
            toast("没搜到 HDRI");
            return;
        }
        final String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = list.get(i).name + "\n" + list.get(i).id;
        }
        new AlertDialog.Builder(this)
                .setTitle("选一个 HDRI")
                .setItems(items, (d, i) -> downloadHdri(list.get(i)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadHdri(final AssetLib.Asset asset) {
        run("取文件清单：" + asset.id, () -> {
            final List<AssetLib.AssetFile> files = AssetLib.files(this, asset.id);
            AssetLib.AssetFile pick = null;
            for (AssetLib.AssetFile f : files) {
                if ("hdr".equals(f.key)) {
                    pick = f;
                    break;
                }
            }
            if (pick == null && !files.isEmpty()) pick = files.get(0);
            if (pick == null) return "这个 HDRI 没有可下载文件";
            final File local = AssetLib.download(this, pick, asset.id + "_hdri",
                    (done, total) -> ui.post(() -> status("下载 HDRI " + FileUtil.human(done))),
                    Downloader.NEVER);
            final double strength = parseDouble(etHdriStrength, 1.0);
            final BlenderOps.Result r = AssetLib.applyHdri(this, project, local, strength, this::log);
            return describe(r, "已把 " + asset.name + " 设为世界环境（强度 " + strength + "）");
        });
    }

    // ================================================================ UV / 场景 / 打包 / 导出

    private void runUv() {
        if (!requireProject() || !requireEnv()) return;
        final int idx = Math.max(0, Math.min(spUvOp.getSelectedItemPosition(), UV_IDS.length - 1));
        final String op = UV_IDS[idx];
        final double angle = parseDouble(etAngle, 66);
        final double margin = parseDouble(etMargin, 0.02);
        final String objRegex = etObjRegex.getText().toString().trim();
        run("UV：" + UV_OPS[idx], () -> {
            final BlenderOps.Result r = ProjectOps.uvOp(this, project, op, objRegex, angle, margin,
                    this::log);
            final String done = countOf(r, "done");
            final String skipped = r.warnings.isEmpty() ? "" : ("；跳过 " + r.warnings.get(0));
            return describe(r, "展开完成 " + done + " 个网格" + skipped);
        });
    }

    private void applyScene() {
        if (!requireProject() || !requireEnv()) return;
        final ProjectOps.ScenePatch patch = new ProjectOps.ScenePatch();
        patch.fps = parseInt(etFps);
        patch.frameStart = parseInt(etStart);
        patch.frameEnd = parseInt(etEnd);
        patch.resX = parseInt(etResX);
        patch.resY = parseInt(etResY);
        run("写入工程设置", () -> {
            final BlenderOps.Result r = ProjectOps.sceneOp(this, project, patch, this::log);
            return describe(r, "已写入 " + countOf(r, "changed") + " 项设置");
        });
    }

    private void packAll() {
        if (!requireProject() || !requireEnv()) return;
        run("打包贴图（大工程要等）", () -> {
            final BlenderOps.Result r = ProjectOps.packAll(this, project, this::log);
            return describe(r, "已打包 " + countOf(r, "packed") + " 张贴图（记得再点「另存为新工程」）");
        });
    }

    private void saveAs() {
        if (!requireProject() || !requireEnv()) return;
        final File dir = new File(BlenderEnv.workDir(this), "saved");
        FileUtil.mkdirs(dir);
        final String name = baseName(project.getName()) + "_packed.blend";
        final boolean pack = true;
        run("另存并打包贴图", () -> {
            final File target = new File(dir, name);
            final BlenderOps.Result r = ProjectOps.saveAs(this, project, target, pack, this::log);
            if (!r.ok || !target.isFile()) return describe(r, "另存失败");
            ui.post(() -> {
                project = target;
                refreshProject();
            });
            return "已另存：" + target.getAbsolutePath() + "（" + FileUtil.human(target.length()) + "）";
        });
    }

    private void exportDialog() {
        if (!requireProject() || !requireEnv()) return;
        final String[] formats = {"GLB（单文件 glTF，推荐）", "glTF 分离", "OBJ", "USD", "FBX"};
        final String[] ids = {"GLB", "GLTF", "OBJ", "USD", "FBX"};
        new AlertDialog.Builder(this)
                .setTitle("导出成别的格式")
                .setItems(formats, (d, i) -> exportAs(ids[i]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportAs(final String fmt) {
        final File dir = new File(BlenderEnv.workDir(this), "export");
        FileUtil.mkdirs(dir);
        run("导出 " + fmt, () -> {
            final String ext = "GLB".equals(fmt) ? ".glb" : "GLTF".equals(fmt) ? ".gltf"
                    : "OBJ".equals(fmt) ? ".obj" : "USD".equals(fmt) ? ".usdc" : ".fbx";
            final File target = new File(dir, baseName(project.getName()) + ext);
            final BlenderOps.Result r = ProjectOps.export(this, project, target, fmt, this::log);
            if (!r.ok || !target.isFile()) return describe(r, "导出失败");
            return "已导出：" + target.getAbsolutePath();
        });
    }

    private void fetchTemplate() {
        if (!requireEnv()) return;
        final String url = etTemplateUrl.getText().toString().trim();
        if (url.isEmpty() || !url.startsWith("http")) {
            toast("填一个直链（.blend 或 .zip）");
            return;
        }
        run("下载模板包", () -> {
            final List<File> blends = AssetLib.fetchTemplatePack(this, url,
                    (done, total) -> ui.post(() -> status("下载 " + FileUtil.human(done)
                            + (total > 0 ? " / " + FileUtil.human(total) : ""))), Downloader.NEVER);
            final File first = blends.get(0);
            ui.post(() -> {
                project = first;
                refreshProject();
            });
            return "已加入 " + blends.size() + " 个 .blend，当前用：" + first.getName()
                    + "（" + FileUtil.human(first.length()) + "）";
        });
    }

    // ================================================================ 执行与输出

    /** 任务体：返回给用户看的一句话。 */
    private interface Job {
        String run() throws Throwable;
    }

    private void run(final String stage, final Job job) {
        if (busy) {
            toast("上一个操作还没完");
            return;
        }
        busy = true;
        status(stage + "…");
        log("── " + stage + " ──");
        new Thread(() -> {
            String msg;
            try {
                msg = job.run();
            } catch (Throwable t) {
                msg = "出错了：" + (t.getMessage() == null ? t.toString() : t.getMessage());
            }
            final String m = msg == null ? "完成" : msg;
            ui.post(() -> {
                busy = false;
                status(m);
                log(m);
                refreshProject();
            });
        }, "mw-project-tools").start();
    }

    /** 把脚本结果翻成人话。 */
    private static String describe(BlenderOps.Result r, String okText) {
        if (r == null) return "没有返回";
        if (r.ok) {
            final StringBuilder sb = new StringBuilder(okText == null ? "完成" : okText);
            if (!r.warnings.isEmpty()) {
                sb.append("；注意：").append(join(r.warnings, 2));
            }
            return sb.toString();
        }
        if (!r.error.isEmpty()) {
            return "失败：" + r.error + (r.crash.isEmpty() ? "" : "（" + r.crash + "）");
        }
        return "失败：" + join(r.errors.isEmpty() ? r.warnings : r.errors, 2);
    }

    private static String countOf(BlenderOps.Result r, String key) {
        if (r == null || r.data == null) return "0";
        final org.json.JSONArray a = r.data.optJSONArray(key);
        if (a != null) return String.valueOf(a.length());
        if (r.data.has(key)) return String.valueOf(r.data.opt(key));
        return "0";
    }

    private static String join(List<String> list, int max) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < max; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(list.get(i));
        }
        if (list.size() > max) sb.append(" …");
        return sb.toString();
    }

    private void status(final String s) {
        ui.post(() -> tvStatus.setText(s));
    }

    private void log(String line) {
        ui.post(() -> {
            final String old = tvLog.getText().toString();
            final String next = line + "\n" + old;
            tvLog.setText(next.length() > 20000 ? next.substring(0, 20000) : next);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ 解析

    private double[] parseValue(String single, String color, boolean wantColor) {
        try {
            if (wantColor) {
                final String[] parts = color.replace("，", ",").split(",");
                if (parts.length < 3) {
                    // 允许 r,g,b 留空时用单一数值当灰度
                    final double v = Double.parseDouble(single.trim());
                    return new double[]{v, v, v, 1.0};
                }
                return new double[]{Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 1.0};
            }
            return new double[]{Double.parseDouble(single.trim())};
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseInt(EditText e) {
        try {
            final String s = e.getText().toString().trim();
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double parseDouble(EditText e, double def) {
        try {
            final String s = e.getText().toString().trim();
            return s.isEmpty() ? def : Double.parseDouble(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String baseName(String name) {
        String n = name == null ? "scene" : name;
        final int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        }
        final String p = uri.getLastPathSegment();
        return p == null ? null : p;
    }

    // ================================================================ 小组件

    private LinearLayout col() {
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        final LinearLayout l = col();
        l.setBackground(round(CARD, 14));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView text(String s, float size, int color) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView label(String s) {
        final TextView t = text(s, 13f, DIM);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private View pad(View v, int l, int t, int r, int b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
        return v;
    }

    private LinearLayout.LayoutParams weight() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private Button btn(String s, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(round(INPUT, 10));
        b.setOnClickListener(l);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText input(String hint, int type) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13f);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setBackground(round(INPUT, 10));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
    }

    private Spinner spinner(String[] items) {
        final Spinner s = new Spinner(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setBackground(round(INPUT, 10));
        return s;
    }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
