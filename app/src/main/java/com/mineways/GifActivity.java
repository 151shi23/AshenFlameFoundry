package com.mineways;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GIF 工作台（离线）：五合一
 *
 * <ol>
 *   <li><b>视频 → GIF</b>：MP4 / MKV / WebM / MOV…，可设起止时间、帧率、宽度；</li>
 *   <li><b>拆帧</b>：GIF 或视频 → 逐帧 PNG；</li>
 *   <li><b>合成</b>：多张图片 → GIF；</li>
 *   <li><b>旋转</b>：一张图 → 转一圈的 GIF（模型 / 建筑展示）；</li>
 *   <li><b>材质动画贴图</b>：GIF 或视频 → 垂直拼接 PNG + .mcmeta（丢进资源包就能动）。</li>
 * </ol>
 */
public class GifActivity extends AppCompatActivity {

    private static final int REQ_PICK = 2001;
    private static final int REQ_MULTI = 2002;

    private static final int BG = 0xFF0F1013, PANEL = 0xFF15171A, PANEL2 = 0xFF0C0D10,
            TEXT = 0xFFEDEDED, DIM = 0xFF8A9099, DIM2 = 0xFF5C6268, ACCENT = 0xFFD9603A;

    private static final String[] MODES = {
            "视频 → GIF", "拆帧 PNG", "多图合成", "图片旋转", "材质动画贴图"
    };
    private static final String[] FPS_NAMES = {"6 fps", "8 fps", "10 fps", "12 fps", "15 fps"};
    private static final int[] FPS_VALUES = {6, 8, 10, 12, 15};
    private static final String[] WIDTH_NAMES = {"320 px", "480 px", "640 px", "原尺寸"};
    private static final int[] WIDTH_VALUES = {320, 480, 640, 0};
    private static final String[] FRAME_NAMES = {"12 帧", "18 帧", "24 帧", "36 帧"};
    private static final int[] FRAME_VALUES = {12, 18, 24, 36};
    private static final String[] DELAY_NAMES = {"40 ms · 25 fps", "60 ms · 16 fps", "80 ms · 12 fps",
            "120 ms · 8 fps", "200 ms · 5 fps"};
    private static final int[] DELAY_VALUES = {40, 60, 80, 120, 200};
    private static final String[] MC_SIZE_NAMES = {"16×16（原版）", "32×32", "64×64", "128×128", "保持原尺寸"};
    private static final int[] MC_SIZE_VALUES = {16, 32, 64, 128, 0};

    private LinearLayout panelHost, chipRow;
    private ProgressBar progress;
    private TextView tvStatus, tvResult;

    private int mode = 0;
    private final List<TextView> chips = new ArrayList<>();

    /** 各模式的参数控件（每次重建面板时重新指向）。 */
    private Spinner spFps, spWidth, spFrames, spDelay, spMcSize;
    private EditText etStart, etEnd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        selectMode(0);
    }

    // ---------------------------------------------------------------- 界面骨架

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(18), dp(16), dp(30));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("GIF 工作台");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setLetterSpacing(-0.01f);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("视频、图片和 GIF 互相转，都在本地处理。成品存到「下载/AFF/」，相册里能直接看到。");
        sub.setTextSize(12.5f);
        sub.setTextColor(DIM);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(8), 0, dp(14));
        col.addView(sub);

        // 模式胶囊
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, 0, 0, dp(4));
        hs.addView(chipRow);
        col.addView(hs);
        for (int i = 0; i < MODES.length; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(MODES[i]);
            c.setTextSize(13);
            c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setPadding(dp(14), dp(9), dp(14), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            c.setLayoutParams(lp);
            c.setClickable(true);
            c.setOnClickListener(v -> selectMode(idx));
            chips.add(c);
            chipRow.addView(c);
        }

        // 进度
        FrameLayout progBox = new FrameLayout(this);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        progBox.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.topMargin = dp(16);
        col.addView(progBox, plp);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(DIM2);
        tvStatus.setPadding(0, dp(8), 0, 0);
        col.addView(tvStatus);

        // 模式面板
        panelHost = new LinearLayout(this);
        panelHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(12);
        col.addView(panelHost, hlp);

        // 结果
        tvResult = new TextView(this);
        tvResult.setTextSize(12.5f);
        tvResult.setTextColor(TEXT);
        tvResult.setLineSpacing(dp(5), 1f);
        tvResult.setTextIsSelectable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        col.addView(tvResult, rlp);

        tvResult.setVisibility(View.GONE);
        tvStatus.setText("选一种模式开始。");
        return sv;
    }

    private void selectMode(int m) {
        mode = m;
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == m;
            chips.get(i).setBackground(round(on ? ACCENT : PANEL, 20));
            chips.get(i).setTextColor(on ? 0xFF15171A : DIM);
        }
        panelHost.removeAllViews();
        tvResult.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        progress.setProgress(0);
        tvStatus.setText("");

        switch (m) {
            case 0:
                buildVideoPanel();
                break;
            case 1:
                buildSplitPanel();
                break;
            case 2:
                buildMergePanel();
                break;
            case 3:
                buildRotatePanel();
                break;
            default:
                buildTexturePanel();
                break;
        }
    }

    /** 一张“卡片”：标题 + 说明 + 内容区（内容区由调用方继续加控件）。 */
    private LinearLayout card(String title, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(PANEL, 16));
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(18)));
        bar.setBackground(round(ACCENT, 2));
        head.addView(bar);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        t.setLayoutParams(tlp);
        head.addView(t);
        box.addView(head);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextSize(12);
            d.setTextColor(DIM);
            d.setLineSpacing(dp(4), 1f);
            d.setPadding(dp(13), dp(8), 0, 0);
            box.addView(d);
        }
        return box;
    }

    private void buildVideoPanel() {
        LinearLayout c = card("视频 → GIF", "支持 MP4 / MKV / WebM / MOV / 3GP 等（系统能播的都能转）。"
                + "竖屏视频会自动摆正。");
        LinearLayout row = row();
        spFps = spinner(FPS_NAMES, 2);
        row.addView(box("帧率", spFps, false));
        spWidth = spinner(WIDTH_NAMES, 1);
        row.addView(box("输出宽度", spWidth, false));
        c.addView(row);

        LinearLayout row2 = row();
        etStart = input("留空 = 0");
        row2.addView(inputBox("起始秒", etStart));
        etEnd = input("留空 = 片尾");
        row2.addView(inputBox("结束秒", etEnd));
        c.addView(row2);

        Button go = primary("选择视频并转换");
        go.setOnClickListener(v -> {
            wantMode = 0;
            pick("video/*");
        });
        c.addView(go);
        panelHost.addView(c);
    }

    private void buildSplitPanel() {
        LinearLayout c = card("拆帧 PNG", "GIF 或视频都行：逐帧导出成 name_0001.png…（最多 96 帧，视频时长较长的建议先设起止秒）。");
        LinearLayout row = row();
        spFps = spinner(FPS_NAMES, 2);
        row.addView(box("视频帧率", spFps, false));
        spWidth = spinner(WIDTH_NAMES, 1);
        row.addView(box("最大宽度", spWidth, false));
        c.addView(row);

        LinearLayout row2 = row();
        etStart = input("留空 = 0");
        row2.addView(inputBox("起始秒（视频）", etStart));
        etEnd = input("留空 = 片尾");
        row2.addView(inputBox("结束秒（视频）", etEnd));
        c.addView(row2);

        Button go = primary("选择 GIF / 视频并拆帧");
        go.setOnClickListener(v -> {
            wantMode = 1;
            pick("*/*");
        });
        c.addView(go);
        panelHost.addView(c);
    }

    private void buildMergePanel() {
        LinearLayout c = card("多张图片 → 合成 GIF", "按选择顺序合成；尺寸以第一张为准，其余自动缩放。");
        spDelay = spinner(DELAY_NAMES, 2);
        c.addView(box("每帧时长", spDelay, true));
        Button go = primary("选择多张图片并合成");
        go.setOnClickListener(v -> {
            wantMode = 2;
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("image/*");
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(it, REQ_MULTI);
        });
        c.addView(go);
        panelHost.addView(c);
    }

    private void buildRotatePanel() {
        LinearLayout c = card("图片 → 旋转 GIF", "给模型渲染图 / 建筑截图做一个转一圈的动图，背景透明，适合发帖、当缩略图。");
        LinearLayout row = row();
        spFrames = spinner(FRAME_NAMES, 2);
        row.addView(box("帧数", spFrames, false));
        spDelay = spinner(DELAY_NAMES, 2);
        row.addView(box("每帧时长", spDelay, false));
        c.addView(row);
        Button go = primary("选择图片并生成");
        go.setOnClickListener(v -> {
            wantMode = 3;
            pick("image/*");
        });
        c.addView(go);
        panelHost.addView(c);
    }

    private void buildTexturePanel() {
        LinearLayout c = card("材质动画贴图", "GIF 或视频 → 垂直拼接 PNG + .mcmeta。"
                + "放进资源包 assets/minecraft/textures/… 下，游戏里就会动（帧数 = 高度 ÷ 单帧高）。");
        LinearLayout row = row();
        spMcSize = spinner(MC_SIZE_NAMES, 1);
        c.addView(box("单帧尺寸", spMcSize, true));
        LinearLayout row2 = row();
        spFps = spinner(FPS_NAMES, 2);
        row2.addView(box("视频帧率", spFps, false));
        spWidth = spinner(WIDTH_NAMES, 0);
        row2.addView(box("最大宽度（视频）", spWidth, false));
        c.addView(row2);
        Button go = primary("选择 GIF / 视频并生成");
        go.setOnClickListener(v -> {
            wantMode = 4;
            pick("*/*");
        });
        c.addView(go);
        panelHost.addView(c);
    }

    // ---------------------------------------------------------------- 选文件 → 干活

    private int wantMode;

    private void pick(String mime) {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(mime);
        startActivityForResult(it, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != Activity.RESULT_OK || data == null) {
            return;
        }
        if (req == REQ_PICK) {
            Uri u = data.getData();
            if (u != null) {
                run(wantMode, u, null);
            }
        } else if (req == REQ_MULTI) {
            List<Uri> list = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    list.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                list.add(data.getData());
            }
            run(2, null, list);
        }
    }

    private void run(final int m, final Uri one, final List<Uri> many) {
        tvResult.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        new Thread(() -> {
            final StringBuilder log = new StringBuilder();
            try {
                switch (m) {
                    case 0:
                        videoToGif(one, log);
                        break;
                    case 1:
                        splitAny(one, log);
                        break;
                    case 2:
                        mergeImages(many, log);
                        break;
                    case 3:
                        rotateImage(one, log);
                        break;
                    default:
                        textureAny(one, log);
                        break;
                }
            } catch (Throwable t) {
                log.append("失败：").append(t.getClass().getSimpleName())
                        .append(t.getMessage() == null ? "" : (" · " + t.getMessage()));
            }
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                tvStatus.setText("完成");
                tvResult.setText(log.toString());
                tvResult.setBackground(round(PANEL, 14));
                tvResult.setPadding(dp(14), dp(14), dp(14), dp(14));
                tvResult.setVisibility(View.VISIBLE);
            });
        }, "gif-work").start();
    }

    /** ① 视频 → GIF。 */
    private void videoToGif(Uri src, StringBuilder log) throws Exception {
        int fps = FPS_VALUES[spFps.getSelectedItemPosition()];
        int width = WIDTH_VALUES[spWidth.getSelectedItemPosition()];
        double s = parseDouble(etStart, 0), e = parseDouble(etEnd, 0);

        VideoFrames.Info info = VideoFrames.info(this, src);
        if (!info.ok) {
            log.append("读不了这个视频：").append(info.error);
            return;
        }
        log.append("原视频 ").append(fmt(info.durationMs)).append(" · ")
                .append(info.width).append("×").append(info.height)
                .append(info.rotation != 0 ? (" · 旋转 " + info.rotation + "°") : "")
                .append("\n正在抽帧…");

        List<Bitmap> frames = VideoFrames.extract(this, src, s, e, fps, width, 96, (done, total) ->
                runOnUiThread(() -> {
                    progress.setMax(total);
                    progress.setProgress(done);
                    tvStatus.setText("抽帧 " + done + "/" + total + " …");
                }));
        if (frames.isEmpty()) {
            log.append("\n没抽到任何帧（换个时间段或换个视频）。");
            return;
        }
        tvStatus.setText("正在压缩 GIF…");
        int delay = Math.max(20, 1000 / fps);
        byte[] gif = GifTool.encode(frames, delay, true);
        String name = "video_" + stamp() + ".gif";
        String path = save("GIF", name, gif, "image/gif");

        log.setLength(0);
        log.append("✅ 视频 → GIF 完成\n")
                .append("文件：").append(path == null ? "保存失败" : path).append("\n")
                .append("帧数 ").append(frames.size()).append(" · ").append(fps).append(" fps · 每帧 ")
                .append(delay).append(" ms\n")
                .append("尺寸 ").append(frames.get(0).getWidth()).append("×").append(frames.get(0).getHeight())
                .append(" · 体积 ").append(kb(gif.length));
    }

    /** ② 拆帧（GIF 或视频）。 */
    private void splitAny(Uri src, StringBuilder log) throws Exception {
        String name = displayName(src);
        boolean video = VideoFrames.looksLikeVideo(this, src, name);
        List<Bitmap> frames;
        if (video) {
            int fps = FPS_VALUES[spFps.getSelectedItemPosition()];
            int width = WIDTH_VALUES[spWidth.getSelectedItemPosition()];
            double s = parseDouble(etStart, 0), e = parseDouble(etEnd, 0);
            frames = VideoFrames.extract(this, src, s, e, fps, width, 96, (done, total) ->
                    runOnUiThread(() -> {
                        progress.setMax(total);
                        progress.setProgress(done);
                        tvStatus.setText("抽帧 " + done + "/" + total + " …");
                    }));
        } else {
            tvStatus.setText("解析 GIF…");
            frames = GifTool.decode(open(src), 96);
        }
        String base = "frame_" + stamp();
        int ok = 0;
        progress.setMax(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Bitmap b = frames.get(i);
            String png = String.format(Locale.US, "%s_%04d.png", base, i + 1);
            if (save("GIF拆帧", png, png(b), "image/png") != null) {
                ok++;
            }
            final int done = i + 1;
            runOnUiThread(() -> {
                progress.setProgress(done);
                tvStatus.setText("导出 PNG " + done + "/" + frames.size() + " …");
            });
        }
        log.setLength(0);
        log.append("✅ 拆帧完成（").append(video ? "视频" : "GIF").append("）\n")
                .append("目录：下载/AFF/GIF拆帧/\n")
                .append("文件：").append(base).append("_0001.png …（共 ").append(ok).append(" 个）\n")
                .append("单帧尺寸 ").append(frames.get(0).getWidth()).append("×").append(frames.get(0).getHeight());
    }

    /** ③ 多图合成。 */
    private void mergeImages(List<Uri> uris, StringBuilder log) throws Exception {
        if (uris == null || uris.isEmpty()) {
            log.append("没选到图片。");
            return;
        }
        int delay = DELAY_VALUES[spDelay.getSelectedItemPosition()];
        List<Bitmap> frames = new ArrayList<>();
        int i = 0;
        progress.setMax(uris.size());
        for (Uri u : uris) {
            Bitmap b = BitmapFactory.decodeStream(open(u));
            if (b != null) {
                frames.add(b);
            }
            final int done = ++i;
            runOnUiThread(() -> progress.setProgress(done));
        }
        if (frames.isEmpty()) {
            log.append("这些文件都读不出来（要图片格式）。");
            return;
        }
        int w = frames.get(0).getWidth(), h = frames.get(0).getHeight();
        List<Bitmap> norm = new ArrayList<>();
        for (Bitmap b : frames) {
            norm.add(b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true));
        }
        tvStatus.setText("正在压缩 GIF…");
        byte[] gif = GifTool.encode(norm, delay, true);
        String path = save("GIF", "merge_" + stamp() + ".gif", gif, "image/gif");
        log.setLength(0);
        log.append("✅ 合成完成\n文件：").append(path == null ? "保存失败" : path)
                .append("\n").append(norm.size()).append(" 帧 · 每帧 ").append(delay).append(" ms · ")
                .append(w).append("×").append(h).append(" · ").append(kb(gif.length));
    }

    /** ④ 图片旋转。 */
    private void rotateImage(Uri src, StringBuilder log) throws Exception {
        int count = FRAME_VALUES[spFrames.getSelectedItemPosition()];
        int delay = DELAY_VALUES[spDelay.getSelectedItemPosition()];
        Bitmap s = BitmapFactory.decodeStream(open(src));
        if (s == null) {
            log.append("读不出这张图。");
            return;
        }
        if (Math.max(s.getWidth(), s.getHeight()) > 720) {
            float k = 720f / Math.max(s.getWidth(), s.getHeight());
            s = Bitmap.createScaledBitmap(s, (int) (s.getWidth() * k), (int) (s.getHeight() * k), true);
        }
        int w = s.getWidth(), h = s.getHeight();
        int size = (int) Math.ceil(Math.hypot(w, h));
        List<Bitmap> frames = new ArrayList<>();
        progress.setMax(count);
        for (int i = 0; i < count; i++) {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Matrix m = new Matrix();
            m.postRotate(360f * i / count, w / 2f, h / 2f);
            m.postTranslate((size - w) / 2f, (size - h) / 2f);
            c.drawBitmap(s, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            frames.add(b);
            final int done = i + 1;
            runOnUiThread(() -> progress.setProgress(done));
        }
        byte[] gif = GifTool.encode(frames, delay, true);
        String path = save("GIF", "rotate_" + stamp() + ".gif", gif, "image/gif");
        log.setLength(0);
        log.append("✅ 旋转 GIF 完成\n文件：").append(path == null ? "保存失败" : path)
                .append("\n").append(count).append(" 帧 · 每帧 ").append(delay).append(" ms · 背景透明 · ")
                .append(size).append("×").append(size).append(" · ").append(kb(gif.length));
    }

    /** ⑤ 材质动画贴图（GIF 或视频）。 */
    private void textureAny(Uri src, StringBuilder log) throws Exception {
        String name = displayName(src);
        boolean video = VideoFrames.looksLikeVideo(this, src, name);
        int want = MC_SIZE_VALUES[spMcSize.getSelectedItemPosition()];
        List<Bitmap> frames;
        if (video) {
            int fps = FPS_VALUES[spFps.getSelectedItemPosition()];
            int width = WIDTH_VALUES[spWidth.getSelectedItemPosition()];
            int cap = want > 0 ? Math.min(64, 4096 / Math.max(1, want)) : 64;
            frames = VideoFrames.extract(this, src, 0, 0, fps, width, cap, (done, total) ->
                    runOnUiThread(() -> {
                        progress.setMax(total);
                        progress.setProgress(done);
                        tvStatus.setText("抽帧 " + done + "/" + total + " …");
                    }));
        } else {
            tvStatus.setText("解析 GIF…");
            frames = GifTool.decode(open(src), 128);
        }
        if (frames.isEmpty()) {
            log.append("没有可用的帧。");
            return;
        }
        if (want > 0) {
            List<Bitmap> scaled = new ArrayList<>();
            for (Bitmap b : frames) {
                scaled.add(Bitmap.createScaledBitmap(b, want, want, false));
            }
            frames = scaled;
        } else if (Math.max(frames.get(0).getWidth(), frames.get(0).getHeight()) > 256) {
            List<Bitmap> scaled = new ArrayList<>();
            for (Bitmap b : frames) {
                scaled.add(Bitmap.createScaledBitmap(b, 128, 128, false));
            }
            frames = scaled;
        }
        Bitmap strip = GifTool.stackVertical(frames);
        if (strip == null) {
            log.append("拼接失败。");
            return;
        }
        String base = "anim_" + stamp();
        String pngName = base + ".png";
        String p1 = save("GIF贴图", pngName, png(strip), "image/png");
        String p2 = save("GIF贴图", pngName + ".mcmeta", GifTool.mcmeta(2).getBytes("UTF-8"), "text/plain");
        log.setLength(0);
        log.append("✅ 材质动画贴图完成\nPNG：").append(p1 == null ? "保存失败" : p1)
                .append("\nMCMETA：").append(p2 == null ? "保存失败" : p2)
                .append("\n帧数 ").append(frames.size())
                .append(" · 单帧 ").append(frames.get(0).getWidth()).append("×").append(frames.get(0).getHeight())
                .append("\n拼接图 ").append(strip.getWidth()).append("×").append(strip.getHeight())
                .append("\n\n用法：改名为你的贴图名，放进资源包 assets/minecraft/textures/block/ 下即可。");
    }

    // ---------------------------------------------------------------- 文件

    private InputStream open(Uri u) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) {
            throw new IllegalArgumentException("打不开这个文件");
        }
        return in;
    }

    private String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return u.getLastPathSegment();
    }

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    /** 存到「下载/AFF/<子目录>/」，返回可见路径（失败回退应用外部目录）。 */
    private String save(String sub, String name, byte[] data, String mime) {
        String rel = Environment.DIRECTORY_DOWNLOADS + "/AFF/" + sub;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                        return "下载/AFF/" + sub + "/" + name;
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "AFF/" + sub);
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, name);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(data);
                    fos.close();
                    return f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = new File(getExternalFilesDir(null), "AFF/" + sub);
            if (dir.exists() || dir.mkdirs()) {
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
    }

    private static String kb(int bytes) {
        return bytes < 1024 * 1024
                ? (bytes / 1024) + " KB"
                : String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static double parseDouble(EditText e, double def) {
        try {
            String s = e == null || e.getText() == null ? "" : e.getText().toString().trim();
            return s.length() == 0 ? def : Double.parseDouble(s);
        } catch (Throwable t) {
            return def;
        }
    }

    // ---------------------------------------------------------------- 小控件

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    private Spinner spinner(String[] items, int def) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(def);
        s.setBackground(round(PANEL2, 10));
        return s;
    }

    /** 下拉的“标签 + 控件”盒子；full=true 独占一行，false 在横向行里并排。 */
    private View box(String title, Spinner s, boolean full) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setLayoutParams(full
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setPadding(full ? 0 : dp(4), 0, full ? 0 : dp(4), dp(4));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11.5f);
        t.setTextColor(DIM);
        t.setPadding(0, 0, 0, dp(5));
        b.addView(t);
        b.addView(s);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM2);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(round(PANEL2, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private View inputBox(String title, EditText e) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setPadding(dp(4), 0, dp(4), dp(4));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11.5f);
        t.setTextColor(DIM);
        t.setPadding(0, 0, 0, dp(5));
        b.addView(t);
        b.addView(e);
        return b;
    }

    /** 主按钮：独占一行、够高、圆角。 */
    private Button primary(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFF15171A);
        b.setBackground(round(ACCENT, 14));
        b.setPadding(dp(10), dp(14), dp(10), dp(14));
        b.setMinHeight(dp(52));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
