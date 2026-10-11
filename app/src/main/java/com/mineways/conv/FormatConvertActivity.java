package com.mineways.conv;

import android.app.Activity;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 格式转换（新工具）：图片 / 音频 / 模型 三类互转。
 *
 * <ul>
 *   <li><b>图片</b>：平台直读（WebP/AVIF/HEIC…）优先，stb 兜底（BMP/TGA/PSD/HDR/GIF 首帧）；</li>
 *   <li><b>音频</b>：平台 MediaCodec → WAV / M4A(AAC) / OGG(Opus, Android 10+)；</li>
 *   <li><b>模型</b>：Assimp 互转（obj/glb/gltf/stl/ply/dae/3ds/fbx…），
 *       <b>.blend 也行</b> —— 压缩过的会先用 zstd/gzip 解开。</li>
 * </ul>
 */
public class FormatConvertActivity extends Activity {

    private static final int REQ_PICK = 71;

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    private static final int TAB_IMAGE = 0;
    private static final int TAB_AUDIO = 1;
    private static final int TAB_MODEL = 2;

    private int tab = TAB_IMAGE;
    private TextView tabImage, tabAudio, tabModel;
    private TextView tvInfo;
    private TextView tvReport;
    private LinearLayout outBox;
    private LinearLayout formatRow;
    private Button btnPick;
    private Button btnGo;
    private String targetExt = "png";

    private File picked;          // 已复制到应用内的真实文件（原生内核要路径）
    private String pickedName = "";
    private final List<File> produced = new ArrayList<>();
    private TextView tvTargetLabel;
    private LinearLayout rulesBox;
    private List<ConvRouting.Target> routeTargets = new ArrayList<>();

    /** 三个引擎当前就绪情况（让"规则"看得见）。 */
    private String engineStatus() {
        final boolean ff = ConvRouting.ffmpegReady(this);
        final boolean bl = ConvRouting.blenderReady(this);
        return "  平台（Bitmap/MediaCodec）：就绪\n"
                + "  原生（Assimp/stb）：" + (FmtConv.available() ? "就绪" : "不可用：" + FmtConv.loadError()) + "\n"
                + "  ffmpeg：" + (ff ? "就绪（补充 MP3/GIF/FLAC 等编码器）"
                : "未安装 → 缺的方向会显示「需先装 ffmpeg 工具」") + "\n"
                + "  Blender：" + (bl ? "就绪（补 .blend 输出、全格式导出）"
                : "未安装 → 模型转 .blend 会显示「需先装 Blender 环境」");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        switchTab(TAB_IMAGE);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        final TextView title = new TextView(this);
        title.setText("格式转换");
        title.setTextColor(TEXT);
        title.setTextSize(22f);
        root.addView(title);

        final TextView sub = new TextView(this);
        sub.setText("图片 / 音频 / 模型 互转。模型支持 Assimp 全家桶，**.blend 也能读**"
                + "（压缩过的会先 zstd/gzip 解开）。");
        sub.setTextColor(DIM);
        sub.setTextSize(13f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(8), 0, dp(14));
        root.addView(sub);

        // 页签
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabImage = tabView("图片");
        tabAudio = tabView("音频");
        tabModel = tabView("模型");
        tabImage.setOnClickListener(v -> switchTab(TAB_IMAGE));
        tabAudio.setOnClickListener(v -> switchTab(TAB_AUDIO));
        tabModel.setOnClickListener(v -> switchTab(TAB_MODEL));
        addTab(tabs, tabImage, 0);
        addTab(tabs, tabAudio, 1);
        addTab(tabs, tabModel, 2);
        root.addView(tabs);

        // 操作行
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        actions.setLayoutParams(alp);

        btnPick = new Button(this);
        btnPick.setText("选择文件");
        btnPick.setAllCaps(false);
        btnPick.setOnClickListener(v -> pickFile());
        actions.addView(btnPick);

        btnGo = new Button(this);
        btnGo.setText("开始转换");
        btnGo.setAllCaps(false);
        btnGo.setBackground(round(ACCENT, 10));
        btnGo.setTextColor(TEXT);
        btnGo.setEnabled(false);
        final LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(10);
        btnGo.setLayoutParams(glp);
        btnGo.setOnClickListener(v -> startConvert());
        actions.addView(btnGo);
        root.addView(actions);

        // 目标格式
        tvTargetLabel = new TextView(this);
        tvTargetLabel.setText("目标格式（选文件后，这里只列这个文件真能转的）");
        tvTargetLabel.setTextColor(DIM);
        tvTargetLabel.setTextSize(12.5f);
        tvTargetLabel.setPadding(0, dp(14), 0, dp(6));
        root.addView(tvTargetLabel);

        final HorizontalScrollView hsv = new HorizontalScrollView(this);
        formatRow = new LinearLayout(this);
        formatRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(formatRow);
        root.addView(hsv);

        // 规则（展开看全部）；默认收起来，不占地方
        rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        rulesBox.setVisibility(View.GONE);
        final TextView rules = new TextView(this);
        rules.setText(ConvRouting.rules() + "\n\n" + "引擎状态：\n" + engineStatus());
        rules.setTextColor(0xFFB9C4CF);
        rules.setTextSize(12.5f);
        rules.setTextIsSelectable(true);
        rules.setLineSpacing(dp(3), 1f);
        rules.setBackground(round(CARD, 12));
        rules.setPadding(dp(14), dp(12), dp(14), dp(12));
        rulesBox.addView(rules);
        root.addView(rulesBox);

        final Button btnRules = new Button(this);
        btnRules.setText("规则 / 支持方向说明（点开）");
        btnRules.setAllCaps(false);
        btnRules.setTextSize(13f);
        btnRules.setOnClickListener(v -> {
            final boolean show = rulesBox.getVisibility() != View.VISIBLE;
            rulesBox.setVisibility(show ? View.VISIBLE : View.GONE);
            btnRules.setText(show ? "收起说明" : "规则 / 支持方向说明（点开）");
        });
        root.addView(btnRules);

        tvInfo = new TextView(this);
        tvInfo.setText("还没选文件。");
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

        tvReport = new TextView(this);
        tvReport.setTextColor(0xFFB9C4CF);
        tvReport.setTextSize(12.5f);
        tvReport.setTextIsSelectable(true);
        tvReport.setLineSpacing(dp(3), 1f);
        tvReport.setPadding(0, dp(12), 0, 0);
        root.addView(tvReport);

        outBox = new LinearLayout(this);
        outBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(outBox);
        return scroll;
    }

    private TextView tabView(String label) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(11), dp(14), dp(11));
        return t;
    }

    private void addTab(LinearLayout row, TextView t, int index) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) {
            lp.leftMargin = dp(8);
        }
        t.setLayoutParams(lp);
        row.addView(t);
    }

    /** 只更新页签外观（不动已选文件与目标列表）。 */
    private void applyTabLook(int t) {
        tab = t;
        tabImage.setBackground(round(t == TAB_IMAGE ? CARD : 0x00000000, 12));
        tabAudio.setBackground(round(t == TAB_AUDIO ? CARD : 0x00000000, 12));
        tabModel.setBackground(round(t == TAB_MODEL ? CARD : 0x00000000, 12));
        tabImage.setTextColor(t == TAB_IMAGE ? TEXT : DIM);
        tabAudio.setTextColor(t == TAB_AUDIO ? TEXT : DIM);
        tabModel.setTextColor(t == TAB_MODEL ? TEXT : DIM);
    }

    private void switchTab(int t) {
        applyTabLook(t);

        // 目标列表不再写死：按「已选文件」动态生成（见 rebuildTargets）
        routeTargets = new ArrayList<>();
        targetExt = "";
        picked = null;
        pickedName = "";
        outBox.removeAllViews();
        produced.clear();
        tvReport.setText("");
        tvInfo.setText("还没选文件。选完后：自动切到对应类别，并只列出这个文件真能转成的格式。");
        rebuildTargets();
    }

    /** 按已选文件生成目标按钮（含引擎标注 / 是否可用 / 失败原因）。 */
    private void rebuildTargets() {
        formatRow.removeAllViews();
        if (picked == null || !picked.isFile()) {
            tvTargetLabel.setText("目标格式（选文件后，这里只列这个文件真能转的）");
            final TextView hint = new TextView(this);
            hint.setText("先在上面选文件");
            hint.setTextColor(DIM);
            hint.setTextSize(13f);
            hint.setPadding(0, dp(4), 0, dp(4));
            formatRow.addView(hint);
            btnGo.setEnabled(false);
            return;
        }
        final ConvRouting.Kind kind = ConvRouting.kindOf(picked);
        routeTargets = ConvRouting.targetsFor(this, picked);
        if (routeTargets.isEmpty()) {
            tvTargetLabel.setText("目标格式");
            final TextView hint = new TextView(this);
            hint.setText("认不出这个源文件（类别：" + kind + "）。图片/音频/模型之外的先不支持。");
            hint.setTextColor(ACCENT);
            hint.setTextSize(13f);
            formatRow.addView(hint);
            btnGo.setEnabled(false);
            return;
        }
        tvTargetLabel.setText("目标格式（共 " + routeTargets.size() + " 个方向，标了引擎）");
        String first = "";
        for (ConvRouting.Target t : routeTargets) {
            final Button b = new Button(this);
            b.setText(t.label());
            b.setAllCaps(false);
            b.setTextSize(13f);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            lp.bottomMargin = dp(6);
            b.setLayoutParams(lp);
            if (!t.available) {
                b.setTextColor(DIM);
                b.setBackground(round(0xFF22262B, 10));
                b.setOnClickListener(v -> explainUnavailable(t));
            } else {
                b.setTextColor(TEXT);
                b.setBackground(round(t.ext.equals(targetExt) ? ACCENT : CARD, 10));
                b.setOnClickListener(v -> {
                    targetExt = t.ext;
                    rebuildTargets();
                    tvReport.setText("目标：" + t.label()
                            + (t.note == null || t.note.isEmpty() ? "" : "（" + t.note + "）"));
                });
            }
            formatRow.addView(b);
            if (first.isEmpty() && t.available) first = t.ext;
        }
        if (targetExt.isEmpty() || !hasAvailable(targetExt)) targetExt = first;
        btnGo.setEnabled(!targetExt.isEmpty());
    }

    private boolean hasAvailable(String ext) {
        for (ConvRouting.Target t : routeTargets) {
            if (t.ext.equals(ext)) return t.available;
        }
        return false;
    }

    private ConvRouting.Target targetOf(String ext) {
        for (ConvRouting.Target t : routeTargets) {
            if (t.ext.equals(ext)) return t;
        }
        return null;
    }

    /** 目标不可用时说清楚缺什么，并给一键去装。 */
    private void explainUnavailable(final ConvRouting.Target t) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(t.ext.toUpperCase(Locale.ROOT) + " 现在不可用")
                .setMessage(t.note + "\n\n" + (t.hint == null ? "" : t.hint))
                .setPositiveButton("去装组件", (d, w) -> {
                    try {
                        startActivity(new Intent(this, com.mineways.blender.BlenderActivity.class));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 模型输出格式：优先用内核真实注册的导出器（Assimp 运行时列表）。 */
    private String[] modelTargets() {
        final List<String> out = new ArrayList<>();
        final String all = FmtConv.formats();
        for (String line : all.split("\n")) {
            if (line.startsWith("export=")) {
                for (String e : line.substring(7).split(",")) {
                    final String x = e.trim().toLowerCase(Locale.ROOT);
                    if (x.length() > 0 && x.length() <= 6 && !out.contains(x)) {
                        out.add(x);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add("obj");
            out.add("glb");
            out.add("gltf");
            out.add("stl");
            out.add("ply");
            out.add("dae");
            out.add("3ds");
        }
        // 常用优先排序
        final List<String> pref = new java.util.ArrayList<>(
                java.util.Arrays.asList("obj", "glb", "gltf", "stl", "ply", "dae", "3ds", "fbx"));
        out.sort((a, b) -> {
            final int ia = pref.indexOf(a);
            final int ib = pref.indexOf(b);
            return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
        });
        return out.toArray(new String[0]);
    }

    private void buildFormatRow(String[] targets) {
        formatRow.removeAllViews();
        for (final String ext : targets) {
            final Button b = new Button(this);
            b.setText(ext.toUpperCase(Locale.ROOT));
            b.setAllCaps(false);
            b.setTextSize(13f);
            b.setPadding(dp(16), dp(6), dp(16), dp(6));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            b.setLayoutParams(lp);
            b.setBackground(round(ext.equals(targetExt) ? ACCENT : CARD, 10));
            b.setOnClickListener(v -> {
                targetExt = ext;
                buildFormatRow(targets);
            });
            formatRow.addView(b);
        }
    }

    // ---------------------------------------------------------------- 选文件

    private void pickFile() {
        final Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("没有可用的文件选择器：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        outBox.removeAllViews();
        produced.clear();
        tvReport.setText("");
        tvInfo.setText("正在读文件…");
        final Uri uri = data.getData();
        final String name = queryName(uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                File f = null;
                try {
                    f = copyToWork(uri, name);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final File file = f;
                final String e = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (file == null) {
                            tvInfo.setText("读文件失败：" + e);
                            return;
                        }
                        picked = file;
                        pickedName = name;
                        showPicked();
                    }
                });
            }
        }, "fmt-copy").start();
    }

    private void showPicked() {
        // 按真实类别自动切页签（用户不用猜自己该在哪个页）
        final ConvRouting.Kind kind = ConvRouting.kindOf(picked);
        final int want = kind == ConvRouting.Kind.IMAGE ? TAB_IMAGE
                : kind == ConvRouting.Kind.AUDIO ? TAB_AUDIO
                : kind == ConvRouting.Kind.MODEL ? TAB_MODEL : tab;
        if (want != tab) {
            applyTabLook(want);
            toast("已按文件类型切到「" + (want == TAB_AUDIO ? "音频" : want == TAB_MODEL ? "模型" : "图片") + "」");
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("已选择：").append(pickedName)
                .append("（").append(human(picked.length())).append("）\n");
        sb.append("类别：").append(kind == ConvRouting.Kind.IMAGE ? "图片"
                : kind == ConvRouting.Kind.AUDIO ? "音频"
                : kind == ConvRouting.Kind.MODEL ? "模型" : "其它（暂不支持）").append('\n');
        final String sniffKind = FmtConv.available() ? FmtConv.sniff(picked.getAbsolutePath()) : "";
        if (sniffKind.length() > 0) {
            sb.append("原生内核探测到：").append(sniffKind).append('\n');
        }
        if (tab == TAB_IMAGE) {
            sb.append(ImageConvert.info(picked));
        } else if (tab == TAB_MODEL && sniffKind.startsWith("blend")) {
            sb.append("这是 Blender 工程：\n")
              .append("· 转成别的格式（obj/glb…）走原生 Assimp，快，但复杂节点/修改器会丢；\n")
              .append("· 要保真：目标选 .blend（走真 Blender）或去 Blender 渲染页的工程工具导出。");
        } else if (tab == TAB_MODEL && !FmtConv.available()) {
            sb.append("原生内核不可用：").append(FmtConv.loadError());
        }
        tvInfo.setText(sb.toString());
        btnPick.setText("换个文件");
        rebuildTargets();          // 只列这个文件真能转的方向
        btnGo.setEnabled(!targetExt.isEmpty());
        tvReport.setText(targetExt.isEmpty() ? "这个文件没有可用的转换方向。"
                : "选好目标格式后点「开始转换」（当前：" + targetExt.toUpperCase(Locale.ROOT) + "）。");
    }

    // ---------------------------------------------------------------- 转换

    private void startConvert() {
        if (picked == null) {
            return;
        }
        final File in = picked;
        final String ext = targetExt;
        final int which = tab;
        tvReport.setText("正在转换…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = getExternalFilesDir("convert");
                if (dir == null) {
                    dir = new File(getFilesDir(), "convert");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    final String msg = "建不了输出目录";
                    runOnUiThread(() -> tvReport.setText(msg));
                    return;
                }
                final String base = stripExt(pickedName);
                // ogg2 只是路由里的"ffmpeg 的 Vorbis 版"，落盘还是 .ogg
                final String diskExt = "ogg2".equals(ext) ? "ogg" : ext;
                final File out = new File(dir, base + "." + diskExt);
                final ConvRouting.Target t = targetOf(ext);
                final ConvRouting.Engine engine = t == null ? ConvRouting.Engine.PLATFORM : t.engine;
                if (engine == ConvRouting.Engine.FFMPEG) {
                    final FmtConv.Result r = FfmpegConv.convert(FormatConvertActivity.this, in, out, ext, 2);
                    finishConvert(out, r.message, r.ok);
                } else if (engine == ConvRouting.Engine.BLENDER) {
                    if ("blend".equals(diskExt)) {
                        final FmtConv.Result r = BlenderConv.toBlend(FormatConvertActivity.this, in, out,
                                line -> runOnUiThread(() -> tvReport.setText(line)));
                        finishConvert(out, r.message, r.ok);
                    } else {
                        finishConvert(out, "Blender 引擎当前只接 .blend 输出（USD 请用 Blender 渲染页的工程工具导出）。", false);
                    }
                } else if (which == TAB_IMAGE) {
                    final ImageConvert.Result r = ImageConvert.convert(in, out, diskExt, 92);
                    finishConvert(out, r.message, r.ok);
                } else if (which == TAB_AUDIO) {
                    final AudioConvert.Result r = AudioConvert.convert(in, out, diskExt);
                    finishConvert(out, r.message, r.ok);
                } else {
                    final FmtConv.Result r = FmtConv.convertModel(in.getAbsolutePath(), out.getAbsolutePath());
                    finishConvert(out, r.message, r.ok);
                }
            }
        }, "fmt-convert").start();
    }

    private void finishConvert(final File out, final String report, final boolean ok) {
        // 产物二次校验：不是"文件存在"就算成功（空文件/半截文件也算失败）
        final String check = ok ? verifyOutput(out) : "";
        final boolean reallyOk = ok && check.isEmpty();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvReport.setText(report + (check.isEmpty() ? "" : "\n产物校验没通过：" + check));
                if (reallyOk) {
                    showOutput(out);
                }
                toast(reallyOk ? "转换完成" : "转换失败（看说明）");
            }
        });
    }

    /** 非零字节 + 文件头对得上（图片/模型/音频各查一点）。 */
    private static String verifyOutput(File f) {
        if (f == null || !f.isFile()) return "没有产出文件";
        if (f.length() < 64) return "文件太小（" + f.length() + " 字节）";
        final String ext = ConvRouting.extOf(f);
        final String magic = ConvRouting.sniff(f);
        if ("mp3".equals(ext) || "wav".equals(ext) || "ogg".equals(ext) || "flac".equals(ext)
                || "m4a".equals(ext) || "aac".equals(ext) || "opus".equals(ext)) {
            if (magic.isEmpty()) return "不像音频（头认不出）";
        } else if ("png".equals(ext) || "jpg".equals(ext) || "gif".equals(ext) || "webp".equals(ext)
                || "bmp".equals(ext)) {
            if (!magic.equals(ext) && !("jpg".equals(magic) && "jpeg".equals(ext))) {
                return "不像 " + ext + "（探测到 " + (magic.isEmpty() ? "未知" : magic) + "）";
            }
        } else if ("glb".equals(ext)) {
            if (!"glb".equals(magic)) return "不像 GLB";
        } else if ("obj".equals(ext) || "mtl".equals(ext)) {
            // OBJ 是文本，抽查有没有顶点/面或材质引用
            final String head = com.mineways.blender.FileUtil.readText(f, 4096);
            if (!head.contains("v ") && !head.contains("mtllib") && !head.contains("usemtl")
                    && !head.toLowerCase(Locale.ROOT).contains("vertex")) {
                return "OBJ 里没看到顶点/材质行（可能是空导出）";
            }
        }
        return "";
    }

    private void showOutput(final File f) {
        outBox.removeAllViews();
        produced.clear();
        produced.add(f);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);

        final TextView nm = new TextView(this);
        nm.setText(f.getName());
        nm.setTextColor(TEXT);
        nm.setTextSize(15f);
        box.addView(nm);

        final TextView path = new TextView(this);
        path.setText(human(f.length()) + " · " + f.getAbsolutePath());
        path.setTextColor(DIM);
        path.setTextSize(11f);
        path.setPadding(0, dp(6), 0, dp(10));
        box.addView(path);

        final Button share = new Button(this);
        share.setText("分享 / 导出");
        share.setAllCaps(false);
        share.setBackground(round(ACCENT, 10));
        share.setTextColor(TEXT);
        share.setOnClickListener(v -> shareFile(f));
        box.addView(share);
        outBox.addView(box);

        final TextView tip = new TextView(this);
        tip.setText("产物目录：Android/data/" + getPackageName() + "/files/convert/");
        tip.setTextColor(DIM);
        tip.setTextSize(11.5f);
        tip.setPadding(0, dp(6), 0, 0);
        outBox.addView(tip);
    }

    private void shareFile(File f) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            final Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享转换产物"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- 文件工具

    private File copyToWork(Uri uri, String name) throws Exception {
        File dir = new File(getCacheDir(), "fmtin");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("建不了缓存目录");
        }
        final File dst = new File(dir, "in_" + Math.abs(name.hashCode()) + "_" + name);
        final InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new IllegalStateException("打不开输入流");
        }
        try {
            final OutputStream os = new FileOutputStream(dst);
            try {
                final byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                os.close();
            }
        } finally {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
        }
        return dst;
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

    private static String stripExt(String name) {
        final int i = name == null ? -1 : name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : (name == null ? "file" : name);
    }

    private static String human(long bytes) {
        if (bytes >= 1048576) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    // ---------------------------------------------------------------- 小工具

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
        try {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
