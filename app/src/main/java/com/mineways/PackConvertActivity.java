package com.mineways;

import android.app.Activity;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.mineways.pack.BedrockPackConverter;
import com.mineways.pack.Java2BedrockConverter;
import com.mineways.pack.PackCore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 材质包双向转换（纯本地，离线）：基岩 ⇄ Java。
 *
 * <p><b>基岩 → Java</b>：内核是 {@code libpackcore.so}（纯 C 算子 + 编译期内置的 957 条权威命名映射与
 * 2131 个 Java 原版命名），Java 侧编排层 {@link BedrockPackConverter}：解包、逐张走四级解析链、
 * flipbook 拆帧、{@code .lang → JSON}、{@code sound_definitions → sounds.json}、生成 {@code pack.mcmeta}
 * 再打包。命中率口径（作者在原包上实测，Faithful 64x）：进包 100%、方块 92.8%、实体+粒子 100%、
 * 物品图标 64.3%（未命中自动回落原版图标，功能不受影响）。
 *
 * <p><b>Java → 基岩</b>：编排层 {@link Java2BedrockConverter}，映射走 assets 里那份
 * {@code packconv/name_map_java2bedrock.json}（957 条反查）+ 官方转换文档的目录表 + HUD 精灵反查表。
 * 产物是 {@code .mcpack}：改名表反查、{@code .mcmeta} 动画反向写成 {@code flipbook_textures.json}
 * （横排/网格帧重排成基岩要求的竖条）、{@code lang/*.json} 反写 {@code texts/*.lang}
 * （键空间与语言代号大小写都还原）、{@code sounds.json} 连音频本体一起搬。
 * JVM 对账（基准 = 上游 node 实现那 958 条权威改名规则）：真实 Java 原版命名集里适用的 744 条，
 * 产物 100% 逐条吻合。
 *
 * <p>两个方向共用同一份口径：<b>能搬的一张不落，搬不了的列清单</b>。两版引擎机制不同的部分
 * （基岩的 models/particles/fogs/ui，Java 的 blockstates/模型 json/字体提供器/shader/overlay）
 * 列进「降级清单」，页面上看得见，不假装成功。
 */
public class PackConvertActivity extends Activity {

    private static final int REQ_PICK = 8931;
    /** 选「已解包目录」用的请求码（走 ACTION_OPEN_DOCUMENT_TREE）。 */
    private static final int REQ_DIR = 8932;
    private static final int MAX_BYTES = 320 * 1024 * 1024;

    // 这页直接按当前的纯黑体系写，不再依赖运行时的老配色转换
    private static final int BG = 0xFF000000;
    private static final int CARD = 0x14FFFFFF;
    private static final int FIELD = 0x1FFFFFFF;
    private static final int TEXT = 0xE6FFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int ACCENT = 0xFFE8E8E8;

    private TextView tvTitle;
    private TextView tvSub;
    private TextView tvInfo;
    private TextView tvNote;
    private TextView tvStatus;
    private Button btnPick;
    private Button btnPickDir;
    private Button btnPaste;
    private Button btnGo;
    private Button btnDegrade;
    private Button btnDiag;
    private Button btnDirA;
    private Button btnDirB;

    /** false = 基岩→Java（默认，老行为）；true = Java→基岩。 */
    private boolean toBedrock;

    private File src;                      // 选中的包（已复制进缓存）
    private String srcName = "";
    private Scan lastScan;                 // 选完包扫出来的条目清单，方向切换时直接重报
    private List<String> lastDegrade;      // null = 还没转换过
    private String lastSummary = "";
    private String lastResultLine = "";
    private String lastOut = "";
    private String lastError = "";
    private String kernelProbe = "未自检";
    private String j2bProbe = "未自检";
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        toBedrock = "java2bedrock".equals(getIntent().getStringExtra("dir"));
        setContentView(buildRoot());
        applyDirection();
        probeKernel();
        useAiDir();
    }

    /** 选完目录：能还原成真实路径就直接用，还原不了就把原因写在界面上（不假装成功）。 */
    private void importDir(Uri tree) {
        if (tree == null) {
            status("没收到目录");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // 拿不到持久授权不影响这一次读；下面真读不到会明说
        }
        File dir = treeToFile(tree);
        if (dir == null) {
            status("这个文件管理器给的目录不是真实路径（" + tree
                    + "）\n换系统「文件」或 MT 管理器再选一次，或者直接用「粘贴路径」");
            return;
        }
        useLocal(dir.getAbsolutePath());
    }

    /**
     * AI 调用 {@code open(name, input)} 时把材质包目录直接带进来的那条路
     * （见 {@link AiHandoff}）。<b>就地读，不复制</b>：用户那个目录可能几百 MB，
     * 复制一遍既慢又占缓存；输出仍然只写到 Download/材质包转换/，不动源目录。
     */
    private void useAiDir() {
        String raw = getIntent().getStringExtra(AiHandoff.EXTRA_DIR);
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        useLocal(raw.trim());
    }

    // ------------------------------------------------------------------ 界面

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), 0);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(TEXT);
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        tvSub = new TextView(this);
        tvSub.setTextColor(DIM);
        tvSub.setTextSize(11.5f);
        tvSub.setPadding(0, dp(6), 0, dp(10));
        root.addView(tvSub);

        LinearLayout dirs = new LinearLayout(this);
        dirs.setOrientation(LinearLayout.HORIZONTAL);
        dirs.setPadding(0, 0, 0, dp(4));
        btnDirA = smallBtn("基岩 → Java");
        btnDirA.setOnClickListener(v -> setDirection(false));
        dirs.addView(btnDirA);
        btnDirB = smallBtn("Java → 基岩");
        btnDirB.setOnClickListener(v -> setDirection(true));
        dirs.addView(btnDirB);
        root.addView(dirs);

        btnPick = primaryBtn("选择文件");
        btnPick.setOnClickListener(v -> pickFile());
        root.addView(btnPick);

        // 三个入口缺一不可：SAF 的文件选择器**给不了文件夹**，而说明里一直写着支持"已解包目录"；
        // 有些文件管理器只给加密虚拟路径，那就只能让用户把路径整段贴进来。
        LinearLayout picks = new LinearLayout(this);
        picks.setOrientation(LinearLayout.HORIZONTAL);
        picks.setPadding(0, dp(8), 0, 0);
        btnPickDir = smallBtn("选择已解包文件夹");
        btnPickDir.setOnClickListener(v -> pickDir());
        picks.addView(btnPickDir);
        btnPaste = smallBtn("粘贴路径");
        btnPaste.setOnClickListener(v -> pastePath());
        picks.addView(btnPaste);
        root.addView(picks);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        tvInfo = new TextView(this);
        tvInfo.setTextColor(TEXT);
        tvInfo.setTextSize(13);
        tvInfo.setBackground(round(CARD, 14));
        tvInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.addView(tvInfo);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        btnGo = primaryBtn("开始转换");
        btnGo.setOnClickListener(v -> convert());
        row.addView(btnGo);
        col.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(10), 0, 0);
        btnDegrade = smallBtn("看降级清单");
        btnDegrade.setOnClickListener(v -> showDegrade());
        row2.addView(btnDegrade);
        btnDiag = smallBtn("复制诊断");
        btnDiag.setOnClickListener(v -> copyDiagnostics());
        row2.addView(btnDiag);
        col.addView(row2);

        tvNote = new TextView(this);
        tvNote.setTextColor(DIM);
        tvNote.setTextSize(11.5f);
        col.addView(tvNote);

        tvStatus = new TextView(this);
        tvStatus.setText("空闲");
        tvStatus.setTextColor(DIM);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(10), 0, dp(16));
        col.addView(tvStatus);

        sv.addView(col);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, slp);
        return root;
    }

    private void setDirection(boolean j2b) {
        if (toBedrock == j2b) {
            return;
        }
        toBedrock = j2b;
        applyDirection();
    }

    /** 方向切换要把标题、说明、按钮文案、实测口径和自检结论一起换掉，不能只换个标题。 */
    private void applyDirection() {
        btnDirA.setBackground(round(toBedrock ? CARD : ACCENT, 999));
        btnDirA.setTextColor(toBedrock ? DIM : 0xFF000000);
        btnDirB.setBackground(round(toBedrock ? ACCENT : CARD, 999));
        btnDirB.setTextColor(toBedrock ? 0xFF000000 : DIM);
        btnPick.setText(toBedrock ? "选择 Java 材质包（.zip / 目录）" : "选择 .mcpack / .zip");
        if (toBedrock) {
            tvTitle.setText("Java 材质包转基岩");
            tvSub.setText("Java 版材质包（.zip / 已解包目录）→ 基岩版 .mcpack（离线，不联网）\n"
                    + "改名表反查 + 横排/网格动画帧重排成基岩竖条 + lang 反写 texts/*.lang"
                    + " + sounds.json 连音频一起搬；基岩没有对应机制的条目进「降级清单」，不假装成功");
            tvNote.setText("\n实测口径（对账基准 = 上游 node 实现里那 958 条权威改名规则）：\n"
                    + "· 2131 个真实 Java 原版命名的压测包：进包 2140 张，改名表里适用的 744 条规则"
                    + "产物 100% 逐条吻合，0 条走偏；\n"
                    + "· 原版 Java 包（3272 个条目）：进包 3229 张、动画 11 组，横排帧与"
                    + " sprite_width/height 网格都会自动重排成基岩要求的竖条；\n"
                    + "· 上游那套 node 工具漏掉的三件事这里补上了：.mcmeta 反写 flipbook_textures.json、"
                    + "lang 反写 texts/*.lang、sounds.json 连音频本体进包；\n"
                    + "· Java 特有机制（blockstates、模型 json、TTF 字体提供器、shader、overlay）"
                    + "基岩没有对应，逐类列进降级清单 —— 它自己的产物只有 textures + manifest。");
        } else {
            tvTitle.setText("基岩材质包转 Java");
            tvSub.setText(".mcpack / .zip / 已解包目录 → Java 版材质包（离线，内核 libpackcore.so）\n"
                    + "贴图无损搬入（.png 直搬、.tga/.jpg 重编码成 .png）+ flipbook 拆帧并按 "
                    + "ticks_per_frame 写帧速 + .lang 转小写 JSON 且改写成 Java 键 + 声音定义连音频本体一起搬；"
                    + "Java 侧没有对应机制的条目进「降级清单」，不假装成功");
            tvNote.setText("\n实测口径（Faithful 64x，1851 张）：\n"
                    + "· 进包率 100%（能搬的一张不落）；方块 92.8%、实体+粒子 100%、"
                    + "物品图标 64.3%（未命中回落原版图标，游戏功能无损）；\n"
                    + "· 结构性降级：models/entity 193、animations 143、particles 192、fogs 80、ui 207"
                    + " —— 这些 Java 原版材质包体系里没有对应机制；\n"
                    + "· pack.mcmeta 的 pack_format 按包自己的 min_engine_version 推（1.21→34、1.21.40→42、"
                    + "1.21.60→46、1.21.80→55、1.21.90→60、1.22→64），不再一律写死 34；CEM 实体模型那部分"
                    + "需要 Optifine/Iris 模组加载，这一版不生成模型文件。");
        }
        renderInfo();
        btnDegrade.setEnabled(lastDegrade != null && !lastDegrade.isEmpty());
    }

    private void renderInfo() {
        String body = src != null && lastScan != null
                ? report(lastScan)
                : (toBedrock
                        ? "还没选文件。选一个 Java 材质包（.zip 或已解包目录）后，这里显示条目统计和映射表自检结果。"
                        : "还没选文件。选一个基岩材质包后，这里显示包内条目统计和内核自检结果。");
        tvInfo.setText(body + "\n\n内核自检（基岩→Java）：\n" + kernelProbe
                + "\n\n映射表自检（Java→基岩）：\n" + j2bProbe);
    }

    // ------------------------------------------------------------------ 动作

    private void pickFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        // 以前这里限定了 application/zip + octet-stream：.mcpack 在不少文件管理器里推不出这两个 MIME，
        // 于是**整台机器的包都被灰掉选不动**（真机现象就是「选择不了文件」）。
        // 改成什么都让选，选完在下面按扩展名判，判不过就把原因写在界面上。
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            status("这个系统没给文件选择器：" + t.getClass().getSimpleName());
        }
    }

    /** 选「已解包的目录」：SAF 的文档选择器做不到，只能走 OPEN_DOCUMENT_TREE。 */
    private void pickDir() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_DIR);
        } catch (Throwable t) {
            status("这个文件管理器不支持选目录：" + t.getClass().getSimpleName()
                    + "；可以试试「粘贴路径」");
        }
    }

    /** 直接贴路径（AI 也是走这条：{@link AiHandoff#EXTRA_DIR}）。 */
    private void pastePath() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("/sdcard/Download/我的材质包 或 .mcpack/.zip 文件");
        in.setSingleLine(false);
        in.setTextSize(13);
        final ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(18), dp(10), dp(18), 0);
        sv.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("粘贴材质包路径")
                .setMessage("目录和文件都行；用文件管理器「复制路径」最稳")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("用这个", (d, w) -> useLocal(in.getText().toString().trim()))
                .show();
    }

    /** 用一个本机路径 / 文件（不复制、就地读）。 */
    private void useLocal(String raw) {
        if (raw.length() == 0) {
            status("什么都没写");
            return;
        }
        File f = AiOpsCore.resolve(raw);
        if (f == null) {
            status("这个路径不存在或读不到：" + raw + "\n（用文件管理器「复制路径」整段贴过来最稳）");
            return;
        }
        if (f.isFile() && !isPackFile(f.getName())) {
            status("这个文件不是材质包（要 .mcpack 或 .zip）：" + f.getName());
            return;
        }
        if (f.isDirectory() && !hasPackMarks(f)) {
            status("这个目录里没有 pack.mcmeta / pack.assets / textures / assets，"
                    + "不像是材质包的根目录：" + f.getName());
            return;
        }
        src = f;
        srcName = f.getName();
        lastDegrade = null;
        lastSummary = "";
        lastResultLine = "";
        lastOut = "";
        lastError = "";
        try {
            lastScan = scan(f);
            renderInfo();
            btnDegrade.setEnabled(false);
            btnGo.setEnabled(!busy);
            status("已读入「" + srcName + "」"
                    + (f.isDirectory() ? "（目录，就地读，不复制）" : "（" + mb(f.length()) + "）"));
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
            status("这个包读不出内容：" + lastError);
        }
    }

    private static boolean isPackFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mcpack") || n.endsWith(".zip");
    }

    private static boolean hasPackMarks(File dir) {
        return new File(dir, "pack.mcmeta").isFile() || new File(dir, "pack.assets").isFile()
                || new File(dir, "textures").isDirectory() || new File(dir, "assets").isDirectory();
    }

    /** 把 {@code content://…/tree/…} 还原成真实文件路径（内置存储与 file 系管理器）。 */
    private static File treeToFile(Uri tree) {
        try {
            if (tree == null) {
                return null;
            }
            String doc = android.provider.DocumentsContract.getTreeDocumentId(tree);
            if (doc == null || doc.length() == 0) {
                return null;
            }
            int colon = doc.indexOf(':');
            String vol = colon < 0 ? "primary" : doc.substring(0, colon);
            String rel = colon < 0 ? doc : doc.substring(colon + 1);
            File base;
            if ("primary".equalsIgnoreCase(vol)) {
                base = Environment.getExternalStorageDirectory();
            } else if ("file".equalsIgnoreCase(tree.getAuthority())) {
                base = new File("/");
            } else {
                base = new File("/storage/" + vol);
            }
            File out = rel.length() == 0 ? base : new File(base, rel);
            return out.isDirectory() && out.canRead() ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            return;
        }
        if (req == REQ_DIR) {
            importDir(data.getData());
            return;
        }
        if (req != REQ_PICK || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        status("正在读入…");
        new Thread(() -> {
            String msg;
            try {
                File dir = new File(getCacheDir(), "packconv");
                mkdirs(dir);
                String name = displayName(uri);
                if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    throw new IllegalStateException("这是单个 json 文件，不是材质包包体");
                }
                File out = new File(dir, "src_" + name);
                if (out.length() > MAX_BYTES) {
                    out.delete();
                }
                copy(uri, out);
                if (out.length() > MAX_BYTES) {
                    throw new IllegalStateException("包太大（" + mb(out.length())
                            + "），这一版上限 320 MB");
                }
                src = out;
                srcName = name;
                lastDegrade = null;
                lastSummary = "";
                lastResultLine = "";
                lastOut = "";
                lastError = "";
                lastScan = scan(out);
                msg = null;
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
                msg = "读入失败：" + lastError;
                final String fm = msg;
                runOnUiThread(() -> status(fm));
            }
            final boolean ready = msg == null;
            runOnUiThread(() -> {
                renderInfo();
                btnDegrade.setEnabled(false);
                if (ready) {
                    btnGo.setEnabled(!busy);
                    status(toBedrock ? "就绪。点「开始转换」输出 .mcpack 到 Download/材质包转换/"
                            : "就绪。点「开始转换」输出到 Download/材质包转换/");
                }
            });
        }).start();
    }

    private void convert() {
        if (src == null) {
            status(toBedrock ? "先选一个 Java 材质包（.zip 或已解包目录）" : "先选一个 .mcpack 或 .zip");
            return;
        }
        if (busy) {
            status("正在转换，等一下");
            return;
        }
        busy = true;
        btnGo.setEnabled(false);
        status("转换中…");
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "packconv_tmp");
                mkdirs(tmp);
                File outDir = new File(getCacheDir(), "packconv_out");
                mkdirs(outDir);
                String base = srcName.replaceFirst("(?i)\\.(mcpack|zip)$", "");
                long t0 = System.currentTimeMillis();
                if (toBedrock) {
                    File dest = new File(outDir, base + "_bedrock.mcpack");
                    Reader r = new InputStreamReader(
                            getAssets().open("packconv/name_map_java2bedrock.json"),
                            StandardCharsets.UTF_8);
                    Java2BedrockConverter.Result jr = Java2BedrockConverter.convert(
                            src, dest, new BitmapAdapter(), tmp, r, j2bProgress());
                    finishOk(dest, jr.toString(), summaryJ2B(jr, System.currentTimeMillis() - t0),
                            jr.degrade, "这个文件已经是 .mcpack：在文件管理器里点一下就会导入基岩版，"
                                    + "或在游戏「设置 → 全局资源 → 导入」里选它。");
                } else {
                    File dest = new File(outDir, base + "_java.zip");
                    BedrockPackConverter.Result br = BedrockPackConverter.convert(
                            src, dest, new BitmapAdapter(), tmp, b2jProgress());
                    finishOk(dest, br.toString(), summaryB2J(br, System.currentTimeMillis() - t0),
                            br.degrade, "解压后放进 Java 版的 resourcepacks 目录即可使用。");
                }
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
                final String err = lastError;
                runOnUiThread(() -> {
                    busy = false;
                    btnGo.setEnabled(true);
                    status("失败：" + err);
                    tvInfo.setText("转换失败，原因写在下面，点「复制诊断」可以整段带走。\n\n"
                            + "失败：" + err + "\n\n内核自检：" + kernelProbe
                            + "\n\n映射表自检：" + j2bProbe
                            + "\n\n包：" + srcName + "（" + mb(src.length()) + "）");
                });
            }
        }).start();
    }

    private BedrockPackConverter.Progress b2jProgress() {
        return (stage, done, moved, skipped) -> {
            if (done % 24 != 0) {
                return;
            }
            runOnUiThread(() -> status("转换中 · " + stage + " · 已处理 "
                    + done + " · 进包 " + moved + " · 降级 " + skipped));
        };
    }

    private Java2BedrockConverter.Progress j2bProgress() {
        return (stage, done, moved, skipped) -> {
            if (done % 24 != 0) {
                return;
            }
            runOnUiThread(() -> status("转换中 · " + stage + " · 已处理 "
                    + done + " · 进包 " + moved + " · 降级 " + skipped));
        };
    }

    private void finishOk(File dest, String resultLine, String summary, List<String> degrade,
            String tip) throws Exception {
        byte[] zip = readAll(dest);
        String where = saveToDownloads("材质包转换", dest.getName(), zip);
        lastResultLine = resultLine;
        lastSummary = summary;
        lastDegrade = degrade;
        lastOut = where == null ? "（写进 Download 失败，文件在缓存里："
                + dest.getAbsolutePath() + "）" : where;
        lastError = "";
        final String out = lastOut;
        final String line = "完成：" + resultLine + "\n输出：" + out;
        runOnUiThread(() -> {
            busy = false;
            btnGo.setEnabled(true);
            btnDegrade.setEnabled(degrade != null && !degrade.isEmpty());
            tvInfo.setText(summary);
            status(line);
            new AlertDialog.Builder(PackConvertActivity.this)
                    .setTitle("转换完成")
                    .setMessage(summary + "\n\n输出：" + out + "\n\n" + tip)
                    .setPositiveButton("知道了", null)
                    .show();
        });
    }

    /** 内核自检：真调一次 native、真读一次映射表，接错第一次进页面就看得见，不用等转换失败。 */
    private void probeKernel() {
        try {
            String a = PackCore.canonicalName("planks_oak");
            String b = PackCore.resolveTexturePath("textures/blocks/stone.png");
            String c = PackCore.buildPackMcmeta("probe", BedrockPackConverter.JAVA_PACK_FORMAT);
            kernelProbe = "canonicalName(planks_oak)=" + a
                    + "\nresolveTexturePath(textures/blocks/stone.png)=" + b
                    + "\npack.mcmeta 前缀=" + (c == null || c.length() < 24 ? c : c.substring(0, 24)) + "…";
        } catch (Throwable t) {
            kernelProbe = "载入/调用失败：" + t.getClass().getName()
                    + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
        }
        try {
            j2bProbe = Java2BedrockConverter.selfTest(new InputStreamReader(
                    getAssets().open("packconv/name_map_java2bedrock.json"), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            j2bProbe = "映射表读不到：" + t.getClass().getName()
                    + (t.getMessage() == null ? "" : ("：" + t.getMessage()));
        }
        if (tvInfo != null) {
            renderInfo();
        }
    }

    // ------------------------------------------------------------------ 报告

    private String report(Scan sc) {
        StringBuilder sb = new StringBuilder();
        sb.append(srcName).append("　").append(mb(sc.bytes)).append("\n");
        sb.append("条目 ").append(sc.entries).append(" 个，其中 PNG ").append(sc.png).append(" 张\n");
        if (toBedrock) {
            sb.append("pack.mcmeta ").append(has(sc, "pack.mcmeta") ? "有" : "没有")
                    .append("　assets/<命名空间>/textures ").append(has(sc, "assets/") ? "有" : "没有")
                    .append("\nlang/ ").append(has(sc, "lang/") ? "有" : "无")
                    .append("　sounds.json ").append(has(sc, "sounds.json") ? "有" : "无")
                    .append("　音频 ").append(countSuffix(sc, ".ogg") + countSuffix(sc, ".wav"))
                    .append(" 个");
            if (!has(sc, "assets/")) {
                sb.append("\n\n⚠ 没看到 assets/ 目录 —— 这多半不是 Java 材质包（可能是行为包或世界），"
                        + "转出来会是空包。");
            } else if (!has(sc, "pack.mcmeta")) {
                sb.append("\n\n⚠ 没有 pack.mcmeta —— 包名和描述会用默认值，贴图照样转。");
            }
            int meta = countSuffix(sc, ".mcmeta");
            if (meta > 0) {
                sb.append("\n带 .mcmeta 的动画贴图 ").append(meta)
                        .append(" 个 → 会反写成 flipbook_textures.json");
            }
        } else {
            sb.append("贴图目录 ").append(has(sc, "textures/") ? "有" : "没有")
                    .append("　flipbook_textures.json ")
                    .append(has(sc, "textures/flipbook_textures.json") ? "有" : "无")
                    .append("\nmanifest ").append(has(sc, "manifest.json") ? "有" : "无")
                    .append("　texts/ ").append(has(sc, "texts/") ? "有" : "无")
                    .append("　sounds/ ").append(has(sc, "sounds/") ? "有" : "无");
            if (!has(sc, "textures/")) {
                sb.append("\n\n⚠ 没看到 textures/ 目录 —— 这多半不是材质包（可能是行为包或世界），"
                        + "转出来会是空包。");
            }
        }
        return sb.toString();
    }

    private String summaryB2J(BedrockPackConverter.Result r, long ms) {
        return "贴图进包 " + r.texturesMoved + " 张　降级 " + r.texturesSkipped + " 张\n"
                + "其中 .tga/.jpg 重编码 " + r.reencoded + " 张　材质册别名救回 " + r.byAlias + " 张\n"
                + "flipbook 动画 " + r.flipbooks + " 组（含 .mcmeta，帧速按 ticks_per_frame）\n"
                + "语言 " + r.langs + " 个（键改写 " + r.langKeysFixed + " 条，文件名已小写）\n"
                + "声音定义 " + r.soundsDefs + " 条　音频文件 " + r.soundsFiles + " 个\n"
                + "pack.mcmeta pack_format " + r.packFormat + "\n"
                + "用时 " + ms + " ms";
    }

    private String summaryJ2B(Java2BedrockConverter.Result r, long ms) {
        return "贴图进包 " + r.texturesMoved + " 张　跳过 " + r.texturesSkipped + " 张\n"
                + "命中分级：表内精确 " + r.exactHits + "　同名印证 " + r.sameNameHits
                + "　词序重排 " + r.tokenHits + "　同名直搬 " + r.directMoves + "\n"
                + "动画 " + r.animations + " 组（其中横排/网格重排成竖条 " + r.animationsRebuilt + "）\n"
                + "语言 " + r.langs + " 个（键反写 " + r.langKeys + " 条，代号已还原大写）\n"
                + "声音定义 " + r.soundsDefs + " 条　音频文件 " + r.soundsFiles + " 个\n"
                + "非 png 重编码 " + r.reencoded + " 张　包图标：" + r.iconSource + "\n"
                + "来源 pack_format " + r.javaPackFormat + "　包名 " + r.packName + "\n"
                + "用时 " + ms + " ms";
    }

    private void showDegrade() {
        if (lastDegrade == null || lastDegrade.isEmpty()) {
            status("还没有降级清单（先转换一次）");
            return;
        }
        List<String> d = lastDegrade;
        int show = Math.min(d.size(), 40);
        StringBuilder sb = new StringBuilder();
        sb.append(toBedrock ? "基岩侧没有对应机制、原样列出的条目 " : "Java 侧没有对应机制、原样列出的条目 ")
                .append(d.size()).append(" 张/条：\n\n");
        for (int i = 0; i < show; i++) {
            sb.append("· ").append(d.get(i)).append('\n');
        }
        if (d.size() > show) {
            sb.append("… 其余 ").append(d.size() - show).append(" 条（点「复制诊断」拿全部）");
        }
        new AlertDialog.Builder(this)
                .setTitle("降级清单")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 诊断整段进剪贴板：他报障时不用截图、不用抓日志。 */
    private void copyDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("pack=").append(toBedrock ? "Java 材质包转基岩" : "基岩材质包转 Java").append('\n');
        sb.append("dir=").append(toBedrock ? "java2bedrock" : "bedrock2java").append('\n');
        sb.append("so=libpackcore.so 探针 ").append(kernelProbe.replace('\n', ' ')).append('\n');
        sb.append("map=assets/packconv 探针 ").append(j2bProbe.replace('\n', ' ')).append('\n');
        sb.append("src=").append(srcName).append(" size=").append(src == null ? 0 : src.length())
                .append('\n');
        sb.append("out=").append(lastOut.isEmpty() ? "未输出" : lastOut).append('\n');
        sb.append("result=").append(lastResultLine.isEmpty() ? "未转换" : lastResultLine).append('\n');
        sb.append("summary=").append(lastSummary.isEmpty() ? "无" : lastSummary.replace('\n', ' '))
                .append('\n');
        if (lastDegrade != null) {
            sb.append("degrade(").append(lastDegrade.size()).append(")=")
                    .append(String.join("|", lastDegrade)).append('\n');
        }
        sb.append("error=").append(lastError.isEmpty() ? "无" : lastError).append('\n');
        sb.append("abi=").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" model=").append(Build.MODEL).append('\n');
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("packconv", sb.toString()));
            Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            status("复制失败：" + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 包体扫描：条目数、PNG 数、关键路径存在性（不解压，只走一遍中央目录）。 */
    private static final class Scan {
        long bytes;
        int entries;
        int png;
        final List<String> names = new ArrayList<>();
    }

    private Scan scan(File f) throws Exception {
        Scan sc = new Scan();
        sc.bytes = f.length();
        if (f.isDirectory()) {
            walkDir(f, "", sc);
            return sc;
        }
        InputStream in = new java.io.FileInputStream(f);
        try {
            ZipInputStream zin = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                sc.entries++;
                String n = e.getName().replace('\\', '/');
                if (n.toLowerCase(Locale.ROOT).endsWith(".png") && !e.isDirectory()) {
                    sc.png++;
                }
                if (sc.names.size() < 8000) {
                    sc.names.add(e.isDirectory() ? n + "/" : n);
                }
                zin.closeEntry();
            }
            zin.close();
        } finally {
            in.close();
        }
        return sc;
    }

    private void walkDir(File dir, String rel, Scan sc) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            sc.entries++;
            if (k.isDirectory()) {
                sc.names.add(r + "/");
                walkDir(k, r, sc);
            } else {
                if (k.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    sc.png++;
                }
                sc.names.add(r);
            }
        }
    }

    private static boolean has(Scan sc, String prefix) {
        for (String n : sc.names) {
            if (n.startsWith(prefix) || n.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int countSuffix(Scan sc, String suffix) {
        int n = 0;
        for (String each : sc.names) {
            if (each.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    private void copy(Uri uri, File dest) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("读不到这个文件");
        }
        OutputStream os = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[32768];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) {
                    throw new IllegalStateException("超过 320 MB 上限");
                }
            }
        } finally {
            os.close();
            in.close();
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && s.length() > 0) {
                        return s.replaceAll("[^0-9A-Za-z._\\u4e00-\\u9fa5-]", "_");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.length() == 0 ? "pack.mcpack" : last;
    }

    private byte[] readAll(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(f.length(), 4096));
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 写进公共 Download：Android 10+ 走 MediaStore（免权限），老系统退回 File API。 */
    private String saveToDownloads(String relDir, String name, byte[] data) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
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
            mkdirs(dir);
            File f = new File(dir, name);
            OutputStream os = new FileOutputStream(f);
            os.write(data);
            os.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void mkdirs(File f) {
        if (f != null && !f.isDirectory()) {
            f.mkdirs();
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576d);
    }

    private Button primaryBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(0xFF000000);
        b.setBackground(round(ACCENT, 12));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
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

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), 0x1FFFFFFF);
        return gd;
    }

    private void status(String s) {
        if (tvStatus != null) {
            tvStatus.setText(s);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Android 侧的 PNG ↔ ARGB 实现（JVM 那份 javax.imageio 在这上面不存在）。 */
    private static final class BitmapAdapter implements BedrockPackConverter.ImageAdapter {

        @Override
        public int[] loadArgb(byte[] png, int[] sizeOut) {
            try {
                Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (b == null) {
                    return null;
                }
                int w = b.getWidth();
                int h = b.getHeight();
                int[] px = new int[w * h];
                b.getPixels(px, 0, w, 0, 0, w, h);
                b.recycle();
                sizeOut[0] = w;
                sizeOut[1] = h;
                return px;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public byte[] savePng(int[] argb, int w, int h) {
            Bitmap b = null;
            try {
                b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                if (!b.compress(Bitmap.CompressFormat.PNG, 100, bo)) {
                    return null;
                }
                return bo.toByteArray();
            } catch (Throwable t) {
                return null;
            } finally {
                if (b != null) {
                    b.recycle();
                }
            }
        }
    }
}
