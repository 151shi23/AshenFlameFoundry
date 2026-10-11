package com.mineways;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.database.Cursor;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Pipeline;
import com.zeus.landscape.platform.AndroidLandscapeCodec;
import com.zeus.landscape.platform.AndroidWatermark;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 风景增强（PureLandscape 引擎）· 完全离线。
 *
 * <p>算法本体是 {@code com.zeus.landscape} 那套纯 Java 风景管线：多尺度感知 → 逐 tile 修改计划 →
 * 执行（白平衡 / 去雾 / Base-Detail 提亮 / CLAHE / OKLab 提饱和 / 显著度调制清晰度 / 锐化 / 影调锚定）。
 * 零第三方依赖、零 native，所以直接当普通工具进 APK，不碰 NDK、不加依赖。</p>
 *
 * <p>引擎的能力都摆在页面上：三种风格、日照金山强度、输出尺寸、相机水印（大疆 / 小米徕卡 / vivo 蔡司 /
 * 新年印章，文案默认取这张照片 EXIF 的真值）、三联对比图、逐张决策报告、一次选多张连跑。</p>
 *
 * <p>两个硬约束是这台机器上实测出来的，不是猜的：</p>
 * <ul>
 *   <li><b>内存</b>：管线全程 float 单精度、通道平面存储，约 <b>65MB Java 堆 / 百万像素</b>。
 *       437 万像素在 256MB 堆下直接 OutOfMemory、320MB 才过；13.5MP 连 512MB 都不够。
 *       所以先按「输出长边」降采样再进管线，批量也是一张一张跑完立刻放掉。</li>
 *   <li><b>耗时</b>：单线程桌面 JDK17 约 1.1 秒/百万像素（1.19MP 1.9s、3.24MP 4.2s、6MP 6.7s），
 *       手机按 3~6 倍估。引擎没有中断点，一批跑起来只能等它结束（换页/退出会停止后续张）。</li>
 * </ul>
 */
public class LandscapeEnhanceActivity extends AppCompatActivity {

    private static final int BG = 0xFF0F1013, PANEL = 0xFF15171A, PANEL2 = 0xFF1C1F24,
            TEXT = 0xFFEDEDED, DIM = 0xFF8A9099, DIM2 = 0xFF5C6268, ACCENT = 0xFFD9603A;

    private static final String ALBUM = "风景增强";

    /** 风格：{给引擎的 key, 界面名, 说明}。 */
    private static final String[][] STYLES = {
            {"vivid", "分区精修", "通透浓艳：语义分区调色 + 去雾 + 局部对比（默认）"},
            {"film", "胶片随手拍", "统一响应曲线 + 颗粒 + 暗角，不做分区魔法"},
            {"newyear", "新年红金", "红相增强 + 红金分离色调，节庆场景"},
    };

    /** 输出长边上限（0 = 原图）：直接决定内存与耗时。 */
    private static final int[] SIZE_CAPS = {1024, 1536, 2048, 0};
    private static final String[] SIZE_LABELS = {
            "长边 1024（约 5 秒，够发圈）",
            "长边 1536（推荐，约 10~20 秒）",
            "长边 2048（更细，约 20~40 秒）",
            "原图尺寸（很慢，可能内存不足）",
    };

    /** 日照金山强度（只在识别到山体时生效）。 */
    private static final float[] GOLDEN_LEVELS = {0f, 0.45f, 0.75f, 1.1f};
    private static final String[] GOLDEN_LABELS = {"关", "轻", "标准（0.75）", "风格化（1.1）"};

    private static final int DEFAULT_SIZE_INDEX = 1;
    private static final int COMPARE_PANEL_W = 640;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView preview;
    private TextView infoText, styleDescText, wmPreviewText, metricsText, reportText;
    private Button btnPick, btnPickMany, btnToggle, btnGo, btnSave, btnSaveAll, btnCopyReport;
    private CheckBox cbCompare, cbExifWm;
    private ProgressBar progress;
    private Spinner spStyle, spSize, spGolden, spWatermark;

    private Bitmap source;
    private Bitmap result;
    private File resultFile;
    private File compareFile;
    private boolean showingResult;
    private volatile boolean running;
    private volatile boolean stopQueue;
    private long startAt;
    private int procW, procH;

    /** 最近一次解码读到的水印文案（来自那张图的 EXIF），跑图时沿用。 */
    private AndroidWatermark.Config lastWmConfig = new AndroidWatermark.Config();

    /** 批量队列：一次选多张时按顺序跑，跑完统一入库。 */
    private final List<Uri> queue = new ArrayList<>();
    private final List<File> produced = new ArrayList<>();
    private final StringBuilder batchReport = new StringBuilder();

    private final ActivityResultLauncher<String[]> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    queue.clear();
                    produced.clear();
                    batchReport.setLength(0);
                    resetOutputArea();
                    loadSource(uri);
                }
            });

    private final ActivityResultLauncher<String[]> pickManyLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) {
                    return;
                }
                if (running) {
                    toast("正在处理，等这一批跑完再选");
                    return;
                }
                queue.clear();
                queue.addAll(uris);
                produced.clear();
                batchReport.setLength(0);
                resetOutputArea();
                previewFirst();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
    }

    // ---------------------------------------------------------------- 界面

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(col);

        col.addView(title("风景增强"));
        col.addView(body(DIM, 12.5f, 8, "对标大疆 Osmo Pocket 直出观感：先做多尺度感知与逐块修改计划，再按场景自适应地"
                + "走白平衡、去雾、提亮、局部对比、OKLab 提饱和、清晰度与锐化。全程本机计算，不联网、不上传。"));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(PANEL);
        col.addView(preview, matchWrap(dp(200), 14));

        LinearLayout rowA = row();
        btnPick = button("选一张", false);
        btnPick.setOnClickListener(v -> pickLauncher.launch(new String[]{"image/*"}));
        rowA.addView(btnPick);
        btnPickMany = button("选多张", true);
        btnPickMany.setOnClickListener(v -> pickManyLauncher.launch(new String[]{"image/*"}));
        rowA.addView(btnPickMany);
        btnToggle = button("看原图", true);
        btnToggle.setVisibility(View.GONE);
        btnToggle.setOnClickListener(v -> togglePreview());
        rowA.addView(btnToggle);
        col.addView(rowA);

        infoText = body(DIM, 12.5f, 12, "还没选照片。JPG / PNG 都行，风景照效果最好。");
        col.addView(infoText);

        col.addView(section("风格"));
        spStyle = spinner(new String[]{STYLES[0][1], STYLES[1][1], STYLES[2][1]}, 0);
        spStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                styleDescText.setText(STYLES[clamp(pos, STYLES.length)][2]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        col.addView(spStyle);
        styleDescText = body(DIM2, 11.5f, 6, STYLES[0][2]);
        col.addView(styleDescText);

        col.addView(section("日照金山（只在识别到山体的图生效）"));
        spGolden = spinner(GOLDEN_LABELS, 2);
        col.addView(spGolden);

        col.addView(section("相机水印（固定右下角，字号按图宽算）"));
        final AndroidWatermark.Style[] wmStyles = AndroidWatermark.Style.values();
        String[] wmLabels = new String[wmStyles.length];
        for (int i = 0; i < wmStyles.length; i++) {
            wmLabels[i] = wmStyles[i].label;
        }
        spWatermark = spinner(wmLabels, 0);
        spWatermark.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                refreshWatermarkPreview(wmStyles[clamp(pos, wmStyles.length)]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        col.addView(spWatermark);
        cbExifWm = check("机型与参数用这张照片 EXIF 的真值", true);
        cbExifWm.setOnCheckedChangeListener((b, checked) -> refreshWatermarkPreview(currentWmStyle()));
        col.addView(cbExifWm);
        wmPreviewText = body(DIM2, 11.5f, 6, "");
        col.addView(wmPreviewText);

        col.addView(section("输出"));
        spSize = spinner(SIZE_LABELS, DEFAULT_SIZE_INDEX);
        col.addView(spSize);
        cbCompare = check("再输出一张三联对比图（原图 | 增强 | 五五混合）", false);
        col.addView(cbCompare);
        col.addView(body(DIM2, 11.5f, 6,
                "管线按 float 全精度算，约 65MB 内存 / 百万像素：尺寸越大越慢也越吃内存，"
                        + "先用 1536 确认风格，满意再换大尺寸或整批连跑。"));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        col.addView(progress, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, 14));

        LinearLayout rowB = row();
        btnGo = button("开始增强", false);
        btnGo.setOnClickListener(v -> run());
        rowB.addView(btnGo);
        btnSave = button("保存成品", true);
        btnSave.setEnabled(false);
        btnSave.setOnClickListener(v -> saveOne());
        rowB.addView(btnSave);
        col.addView(rowB);

        LinearLayout rowC = row();
        btnSaveAll = button("保存整批到相册", true);
        btnSaveAll.setEnabled(false);
        btnSaveAll.setOnClickListener(v -> saveAll());
        rowC.addView(btnSaveAll);
        btnCopyReport = button("复制决策报告", true);
        btnCopyReport.setEnabled(false);
        btnCopyReport.setOnClickListener(v -> copyReport());
        rowC.addView(btnCopyReport);
        col.addView(rowC);

        metricsText = body(ACCENT, 12.5f, 14, "");
        metricsText.setLineSpacing(dp(2), 1f);
        col.addView(metricsText);

        col.addView(section("引擎决策报告"));
        reportText = new TextView(this);
        reportText.setTextSize(11.5f);
        reportText.setTextColor(DIM);
        reportText.setTypeface(Typeface.MONOSPACE);
        reportText.setTextIsSelectable(true);
        reportText.setLineSpacing(dp(2), 1f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(round(PANEL, 12));
        box.addView(reportText);
        col.addView(box, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        return sv;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        return t;
    }

    private TextView section(String s) {
        TextView t = body(TEXT, 12, 18, s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView body(int color, float sizeSp, int topDp, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        t.setLayoutParams(matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, topDp));
        return t;
    }

    private CheckBox check(String s, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(12);
        c.setTextColor(DIM);
        c.setChecked(def);
        c.setLayoutParams(matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        return c;
    }

    private LinearLayout.LayoutParams matchWrap(int height, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        return r;
    }

    private Button button(String text, boolean secondary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(secondary ? DIM : Color.WHITE);
        b.setBackground(round(secondary ? PANEL2 : ACCENT, 12));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private Spinner spinner(String[] items, int def) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(def);
        s.setBackground(round(PANEL2, 10));
        s.setLayoutParams(matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        return s;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int i, int len) {
        return Math.max(0, Math.min(len - 1, i));
    }

    // ---------------------------------------------------------------- 选图 / 解码

    private void resetOutputArea() {
        if (result != null) {
            result.recycle();
            result = null;
        }
        resultFile = null;
        compareFile = null;
        showingResult = false;
        btnToggle.setVisibility(View.GONE);
        btnSave.setEnabled(false);
        btnSaveAll.setEnabled(false);
        btnCopyReport.setEnabled(false);
        metricsText.setText("");
        reportText.setText("");
    }

    /** 批量：先把第一张解出来当预览，但不锁死「当前照片」语义（跑批按队列逐张重解）。 */
    private void previewFirst() {
        Bitmap decoded = decodeScaled(queue.get(0), currentCap());
        if (decoded == null) {
            infoText.setText("已排入 " + queue.size() + " 张，但第一张读不出来");
            return;
        }
        setSource(decoded);
        preview.setImageBitmap(source);
        infoText.setText(String.format(Locale.ROOT, "已排入 %d 张 · 预览第 1 张 %d×%d · 点「开始增强」连跑",
                queue.size(), source.getWidth(), source.getHeight()));
        refreshWatermarkPreview(currentWmStyle());
    }

    private void loadSource(Uri uri) {
        Bitmap decoded = decodeScaled(uri, currentCap());
        if (decoded == null) {
            toast("这张图读不出来，换一张试试");
            return;
        }
        setSource(decoded);
        preview.setImageBitmap(source);
        infoText.setText(String.format(Locale.ROOT, "已选：%d×%d · 约 %.1f 百万像素 · 预计 %.0f 秒",
                source.getWidth(), source.getHeight(), megapixels(source),
                estimateSeconds(source.getWidth(), source.getHeight())));
        refreshWatermarkPreview(currentWmStyle());
    }

    private void setSource(Bitmap bmp) {
        if (source != null && source != bmp) {
            source.recycle();
        }
        source = bmp;
    }

    private int currentCap() {
        int cap = SIZE_CAPS[clamp(spSize == null ? DEFAULT_SIZE_INDEX
                : spSize.getSelectedItemPosition(), SIZE_CAPS.length)];
        return cap <= 0 ? Integer.MAX_VALUE : cap;
    }

    private AndroidWatermark.Style currentWmStyle() {
        AndroidWatermark.Style[] all = AndroidWatermark.Style.values();
        return all[clamp(spWatermark == null ? 0 : spWatermark.getSelectedItemPosition(), all.length)];
    }

    private void refreshWatermarkPreview(AndroidWatermark.Style style) {
        if (style == AndroidWatermark.Style.NONE) {
            wmPreviewText.setText("不加水印。");
            return;
        }
        boolean fromExif = cbExifWm != null && cbExifWm.isChecked() && hasExifData();
        AndroidWatermark.Config cfg = fromExif ? lastWmConfig : new AndroidWatermark.Config();
        wmPreviewText.setText("将写入：" + AndroidWatermark.previewText(style, cfg)
                + (fromExif ? "（来自这张照片的 EXIF）" : "（该风格默认文案）"));
    }

    private boolean hasExifData() {
        return lastWmConfig != null && (lastWmConfig.model != null || lastWmConfig.lens != null
                || lastWmConfig.aperture != null || lastWmConfig.shutter != null || lastWmConfig.iso != null);
    }

    /**
     * 按长边上限解码，并把 EXIF 方向转正、顺带读出水印要的机型参数。
     *
     * <p>EXIF 必须自己处理：{@code BitmapFactory} 不读方向标签，而相册里的照片几乎都靠它记方向 ——
     * 不转就会把横图当竖图喂给管线，成品是躺着的。{@code android.media.ExifInterface} 是框架类，
     * 不需要额外依赖。</p>
     */
    private Bitmap decodeScaled(Uri uri, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int orient = 1;
            AndroidWatermark.Config cfg = new AndroidWatermark.Config();
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    orient = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    cfg = readWatermarkConfig(exif);
                }
            } catch (Throwable ignored) {
                // 截图 / 转发图常常没 EXIF：那就用风格默认文案，不影响增强本身
            }
            lastWmConfig = cfg;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opt.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (opt.inSampleSize * 2L) >= maxEdge) {
                opt.inSampleSize *= 2;
            }
            Bitmap raw;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(is, null, opt);
            }
            if (raw == null) {
                return null;
            }
            boolean swapped = orient >= 5 && orient <= 8;
            int rotW = swapped ? raw.getHeight() : raw.getWidth();
            int rotH = swapped ? raw.getWidth() : raw.getHeight();
            Matrix m = orientationMatrix(orient);
            float k = Math.max(rotW, rotH) > maxEdge ? maxEdge / (float) Math.max(rotW, rotH) : 1f;
            if (k < 1f) {
                m.postScale(k, k);
            }
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (out != raw) {
                raw.recycle();
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 机型 / 焦距 / 光圈 / 快门 / ISO —— 拼成水印那三行里的后两行。 */
    private static AndroidWatermark.Config readWatermarkConfig(ExifInterface exif) {
        AndroidWatermark.Config c = new AndroidWatermark.Config();
        String model = clean(exif.getAttribute(ExifInterface.TAG_MODEL));
        c.model = model != null ? model : clean(exif.getAttribute(ExifInterface.TAG_MAKE));
        double focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
        if (focal <= 0) {
            focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
        }
        if (focal > 0) {
            c.lens = String.format(Locale.ROOT, "%dmm", Math.round(focal));
        }
        double ap = exif.getAttributeDouble(ExifInterface.TAG_APERTURE, 0);
        if (ap > 0) {
            c.aperture = String.format(Locale.ROOT, "f/%.1f", ap);
        }
        try {
            String shutterRaw = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (shutterRaw != null) {
                double sec = Double.parseDouble(shutterRaw.trim());
                if (sec > 0 && sec < 1) {
                    c.shutter = "1/" + Math.round(1 / sec) + "s";
                } else if (sec >= 1) {
                    c.shutter = String.format(Locale.ROOT, "%.1fs", sec);
                }
            }
        } catch (Throwable ignored) {
        }
        String iso = clean(exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS));
        if (iso == null) {
            iso = clean(exif.getAttribute(ExifInterface.TAG_ISO));
        }
        if (iso != null) {
            c.iso = "ISO" + iso.replaceFirst("(?i)iso", "");
        }
        return c;
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    private Matrix orientationMatrix(int orient) {
        Matrix m = new Matrix();
        switch (orient) {
            case 2:
                m.postScale(-1f, 1f);
                break;
            case 3:
                m.postRotate(180f);
                break;
            case 4:
                m.postRotate(180f);
                m.postScale(-1f, 1f);
                break;
            case 5:
                m.postRotate(90f);
                m.postScale(-1f, 1f);
                break;
            case 6:
                m.postRotate(90f);
                break;
            case 7:
                m.postRotate(-90f);
                m.postScale(-1f, 1f);
                break;
            case 8:
                m.postRotate(-90f);
                break;
            default:
                break;
        }
        return m;
    }

    private float megapixels(Bitmap b) {
        return b.getWidth() * b.getHeight() / 1e6f;
    }

    private float estimateSeconds(int w, int h) {
        // 桌面 JDK17 实测约 1.1 秒/百万像素，手机按 4 倍给数量级
        return w * h / 1e6f * 1.1f * 4f;
    }

    // ---------------------------------------------------------------- 处理

    private void togglePreview() {
        if (result == null) {
            return;
        }
        showingResult = !showingResult;
        preview.setImageBitmap(showingResult ? result : source);
        btnToggle.setText(showingResult ? "看原图" : "看结果");
    }

    private void run() {
        if (running) {
            toast("正在处理，等一下下");
            return;
        }
        boolean batch = !queue.isEmpty();
        if (!batch && source == null) {
            toast("先选一张照片");
            return;
        }
        final String style = STYLES[clamp(spStyle.getSelectedItemPosition(), STYLES.length)][0];
        final float golden = GOLDEN_LEVELS[clamp(spGolden.getSelectedItemPosition(), GOLDEN_LEVELS.length)];
        final AndroidWatermark.Style wm = currentWmStyle();
        final AndroidWatermark.Config wmCfg =
                cbExifWm.isChecked() && hasExifData() ? lastWmConfig : new AndroidWatermark.Config();
        final boolean wantCompare = cbCompare.isChecked();
        final int cap = currentCap();

        produced.clear();
        batchReport.setLength(0);
        running = true;
        stopQueue = false;
        startAt = System.currentTimeMillis();
        btnGo.setEnabled(false);
        btnPick.setEnabled(false);
        btnPickMany.setEnabled(false);
        btnSave.setEnabled(false);
        btnSaveAll.setEnabled(false);
        btnCopyReport.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        metricsText.setText("");
        reportText.setText("");

        if (batch) {
            procW = 0;
            procH = 0;
        } else {
            procW = source.getWidth();
            procH = source.getHeight();
        }
        tick();

        new Thread(() -> {
            if (batch) {
                runQueue(style, golden, wmCfg, wm, wantCompare, cap);
            } else {
                runOne(source, "landscape_" + System.currentTimeMillis(), style, golden, wmCfg, wm,
                        wantCompare, false, 1, 1);
            }
        }, "landscape-enhance").start();
    }

    private void runQueue(String style, float golden, AndroidWatermark.Config wmCfg,
                          AndroidWatermark.Style wm, boolean wantCompare, int cap) {
        final int total = queue.size();
        for (int i = 0; i < total; i++) {
            if (stopQueue) {
                break;
            }
            final int index = i + 1;
            Uri uri = queue.get(i);
            Bitmap bmp = decodeScaled(uri, cap);
            if (bmp == null) {
                ui.post(() -> appendBatchLine(index + "/" + total + "  读图失败，已跳过"));
                continue;
            }
            final String base = baseNameOf(uri);
            final int bw = bmp.getWidth();
            final int bh = bmp.getHeight();
            ui.post(() -> infoText.setText(String.format(Locale.ROOT, "批量 %d/%d · %dx%d · 已用 %d 秒",
                    index, total, bw, bh, (System.currentTimeMillis() - startAt) / 1000)));
            runOne(bmp, base, style, golden, wmCfg, wm, wantCompare, true, index, total);
            bmp.recycle();
        }
        ui.post(this::finishBatch);
    }

    /**
     * 单张：解码 → 增强 → 水印 → 落盘（后台线程）。
     *
     * <p>批量时位图由 {@link #runQueue} 负责回收；单张时 {@code input} 就是页面持有的那张预览图。</p>
     */
    private boolean runOne(Bitmap input, String base, String style, float golden,
                           AndroidWatermark.Config wmCfg, AndroidWatermark.Style wm,
                           boolean wantCompare, boolean batch, int index, int total) {
        try {
            FloatImage image = AndroidLandscapeCodec.decode(input);
            Pipeline p = new Pipeline();
            p.style = style;
            p.goldenStrength = golden;
            Pipeline.Result r = p.enhance(image);
            final Bitmap out = AndroidLandscapeCodec.encode(r.output);
            AndroidWatermark.apply(out, wm, wmCfg);
            File jpg = writeJpeg(out, base + "_enhanced.jpg");
            if (wantCompare) {
                File strip = writeJpeg(compareStrip(input, out), base + "_compare.jpg");
                if (batch) {
                    produced.add(strip);
                } else {
                    compareFile = strip;
                }
            } else if (!batch) {
                compareFile = null;
            }
            File txt = writeReport(r, base, out);
            if (batch) {
                produced.add(jpg);
                produced.add(txt);
                final String line = index + "/" + total + "  " + base + "  " + r.elapsedMs + "ms  "
                        + r.after.diff(r.before);
                ui.post(() -> {
                    appendBatchLine(line);
                    if (out != null) {
                        out.recycle();
                    }
                });
            } else {
                ui.post(() -> done(out, jpg, r, System.currentTimeMillis() - startAt));
            }
            return true;
        } catch (OutOfMemoryError oom) {
            if (batch) {
                ui.post(() -> appendBatchLine(index + "/" + total + "  " + base + "  内存不足，已跳过"));
            } else {
                ui.post(() -> fail("内存不够：这张太大。把「输出尺寸」换成 1024 或 1536 再试一次。"));
            }
            return false;
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (batch) {
                ui.post(() -> appendBatchLine(index + "/" + total + "  " + base + "  失败：" + msg));
            } else {
                ui.post(() -> fail("处理失败：" + msg));
            }
            return false;
        }
    }

    private void appendBatchLine(String line) {
        if (batchReport.length() > 0) {
            batchReport.append('\n');
        }
        batchReport.append(line);
        reportText.setText(batchReport);
        btnCopyReport.setEnabled(true);
    }

    private void finishBatch() {
        running = false;
        progress.setVisibility(View.GONE);
        btnGo.setEnabled(true);
        btnPick.setEnabled(true);
        btnPickMany.setEnabled(true);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int jpgs = 0;
        for (File f : produced) {
            if (f.getName().endsWith(".jpg")) {
                jpgs++;
            }
        }
        metricsText.setText(String.format(Locale.ROOT,
                "整批完成：%d 张成品（另含对比图与报告），总用时 %.1f 秒。",
                jpgs, (System.currentTimeMillis() - startAt) / 1000f));
        infoText.setText("点「保存整批到相册」一次性入库");
        btnSaveAll.setEnabled(jpgs > 0);
        queue.clear();
    }

    private void tick() {
        if (!running || isFinishing() || isDestroyed()) {
            return;
        }
        long s = (System.currentTimeMillis() - startAt) / 1000;
        if (procW > 0) {
            infoText.setText(String.format(Locale.ROOT,
                    "处理中… 已用 %d 秒（引擎一口气跑完，中途停不了）· %d×%d", s, procW, procH));
        } else {
            infoText.setText(String.format(Locale.ROOT, "批量处理中… 已用 %d 秒", s));
        }
        ui.postDelayed(this::tick, 500);
    }

    private void done(Bitmap out, File file, Pipeline.Result r, long ms) {
        running = false;
        progress.setVisibility(View.GONE);
        btnGo.setEnabled(true);
        btnPick.setEnabled(true);
        btnPickMany.setEnabled(true);
        if (isFinishing() || isDestroyed()) {
            out.recycle();
            return;
        }
        if (result != null && result != out) {
            result.recycle();
        }
        result = out;
        resultFile = file;
        showingResult = true;
        preview.setImageBitmap(result);
        btnToggle.setVisibility(View.VISIBLE);
        btnToggle.setText("看原图");
        btnSave.setEnabled(true);
        btnCopyReport.setEnabled(true);
        metricsText.setText(String.format(Locale.ROOT, "用时 %.1f 秒（引擎内部 %d ms）\n%s",
                ms / 1000f, r.elapsedMs, r.after.diff(r.before)));
        reportText.setText(buildReportText(r, "单张", result.getWidth(), result.getHeight()));
        infoText.setText(String.format(Locale.ROOT, "成品：%dx%d，可保存", result.getWidth(), result.getHeight()));
    }

    private void fail(String message) {
        running = false;
        progress.setVisibility(View.GONE);
        btnGo.setEnabled(true);
        btnPick.setEnabled(true);
        btnPickMany.setEnabled(true);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        infoText.setText(message);
        toast(message);
    }

    /** 三联对比图：原图 | 增强 | 五五混合（与引擎 README 那张对比图同构）。 */
    private Bitmap compareStrip(Bitmap before, Bitmap after) {
        float scale = COMPARE_PANEL_W / (float) before.getWidth();
        int pw = COMPARE_PANEL_W;
        int ph = Math.max(1, Math.round(before.getHeight() * scale));
        int gap = 4;
        Bitmap strip = Bitmap.createBitmap(pw * 3 + gap * 2, ph, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect src = new Rect(0, 0, before.getWidth(), before.getHeight());
        canvas.drawBitmap(before, src, new Rect(0, 0, pw, ph), paint);
        canvas.drawBitmap(after, src, new Rect(pw + gap, 0, pw * 2 + gap, ph), paint);
        Rect third = new Rect((pw + gap) * 2, 0, pw * 3 + gap * 2, ph);
        canvas.drawBitmap(before, src, third, paint);
        paint.setAlpha(128);
        canvas.drawBitmap(after, src, third, paint);
        return strip;
    }

    // ---------------------------------------------------------------- 落盘 / 入库

    private File outDir() {
        File dir = new File(getFilesDir(), "landscape");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建临时目录");
        }
        return dir;
    }

    private File writeJpeg(Bitmap out, String name) throws Exception {
        File f = new File(outDir(), name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            if (!out.compress(Bitmap.CompressFormat.JPEG, AndroidLandscapeCodec.JPEG_QUALITY, fos)) {
                throw new IllegalStateException("JPEG 编码失败");
            }
        }
        return f;
    }

    /** 决策报告按引擎自己的口径落一份 txt：日志 + 前后指标，跟桌面端那份 {@code *_report.txt} 对齐。 */
    private File writeReport(Pipeline.Result r, String base, Bitmap out) {
        String text = buildReportText(r, base, out.getWidth(), out.getHeight());
        try {
            File f = new File(outDir(), base + "_report.txt");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(text.getBytes(Charset.forName("UTF-8")));
            }
            return f;
        } catch (Throwable t) {
            return new File(outDir(), base + "_report.txt");
        }
    }

    private String buildReportText(Pipeline.Result r, String base, int w, int h) {
        return "== " + base + " ==\n尺寸: " + w + "x" + h + " | 引擎耗时: " + r.elapsedMs + " ms\n"
                + r.log + "\n指标 (前→后):\n" + r.after.diff(r.before) + "\n";
    }

    private void saveOne() {
        if (resultFile == null || !resultFile.exists()) {
            toast("还没有成品可保存");
            return;
        }
        String msg = MediaPublisher.publish(this, resultFile, ALBUM);
        if (compareFile != null && compareFile.exists()) {
            msg += "；对比图" + (MediaPublisher.publish(this, compareFile, ALBUM).startsWith("已保存")
                    ? "也已存入" : "存入失败");
        }
        toast(msg);
    }

    private void saveAll() {
        int ok = 0;
        int bad = 0;
        String last = "";
        for (File f : produced) {
            if (!f.getName().endsWith(".jpg") || !f.exists()) {
                continue;
            }
            last = MediaPublisher.publish(this, f, ALBUM);
            if (last.startsWith("已保存")) {
                ok++;
            } else {
                bad++;
            }
        }
        toast("已存进相册「" + ALBUM + "」" + ok + " 个"
                + (bad > 0 ? "，失败 " + bad + " 个：" + last : ""));
    }

    private void copyReport() {
        CharSequence text = reportText.getText();
        if (TextUtils.isEmpty(text)) {
            toast("报告还是空的");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("landscape-report", text));
            toast("决策报告已复制");
        }
    }

    /** 尽量拿回 SAF 里的原始文件名，成品/对比图/报告都按原名配对；拿不到就用时间戳。 */
    private String baseNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.trim().length() > 0) {
                    int dot = n.lastIndexOf('.');
                    return dot > 0 ? n.substring(0, dot) : n;
                }
            }
        } catch (Throwable ignored) {
        }
        return "photo_" + System.currentTimeMillis();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        stopQueue = true;
        if (source != null && !source.isRecycled()) {
            source.recycle();
            source = null;
        }
        if (result != null && !result.isRecycled()) {
            result.recycle();
            result = null;
        }
        super.onDestroy();
    }
}
