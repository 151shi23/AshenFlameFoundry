package com.mineways;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.mineways.pixelart.PixelArtConverter;
import com.mineways.pixelart.PixelArtAndroidIO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「PNG 转模型」独立界面：把纯色背景（或全透明背景）的像素图逐像素转成
 * Blockbench .bbmodel，并同时产出 .obj / .mtl / .png（OBJ 引用同名贴图）。
 *
 * <p>转换核心为 {@link PixelArtConverter}（纯算法、零 Android 依赖，含产物对账断言）；
 * Bitmap 边界在 {@link PixelArtAndroidIO}。本类只负责选图、参数、自动轻量化、预览、落盘与分享。
 *
 * <p>自动轻量化：前景立方体超过 {@link #CUBE_BUDGET} 时，按整数倍最近邻降采样重试，
 * 直到进入预算 —— 大图不再被拒绝，而是自动转成轻量模型（外观保持不变）。
 */
public class PngToModelActivity extends AppCompatActivity {

    /** 产物保存的子目录（Download/MinewaysMobile 下）。 */
    private static final String SUBDIR = "png转模型";
    /** 与 MainActivity 共用的偏好文件名。 */
    private static final String PREF = "export_options";
    private static final String PREF_TOL = "png_tol";

    /** 解图尺寸上限：像素图远小于此值，超过多为误选，直接拒绝以免 OOM。 */
    private static final int MAX_DIM = 2048;
    /** 轻量预算：前景立方体超过该值就自动降采样（与旧核心的默认上限一致）。 */
    private static final int CUBE_BUDGET = 16384;
    /** 自动降采样的最小边长下限（24×24 全前景 576 块，远低于预算）。 */
    private static final int MIN_DIM = 24;
    /** alpha 低于该值的像素一律按透明背景处理（与旧核心口径一致）。 */
    private static final int ALPHA_CUTOFF = 10;

    private ImageView ivPreview;
    private EditText etName, etTol;
    private TextView tvStatus;

    private Bitmap srcBitmap;
    /** 最近一次转换成功的产物（保存/分享用）。 */
    private PixelArtConverter.ConvertResult lastResult;
    private String lastName;

    private final ActivityResultLauncher<String[]> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    loadImage(uri);
                }
            });

    
    private String activationToken;
    private String activationFeature;
    private boolean activationDone;

    private void finishActivation(boolean success) {
        if (activationDone) {
            return;
        }
        activationDone = true;
        ActivationDialog.finish(this, activationFeature, activationToken, success);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activationToken = getIntent() != null ? getIntent().getStringExtra("activation_token") : null;
        activationFeature = getIntent() != null ? getIntent().getStringExtra("activation_feature") : null;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_png_to_model);

        MaterialToolbar bar = findViewById(R.id.png_toolbar);
        setSupportActionBar(bar);
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());

        ivPreview = findViewById(R.id.iv_png_preview);
        etName = findViewById(R.id.et_png_name);
        etTol = findViewById(R.id.et_png_tol);
        tvStatus = findViewById(R.id.tv_png_status);

        android.content.SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        etTol.setText(String.valueOf(sp.getInt(PREF_TOL, 12)));

        findViewById(R.id.btn_png_pick).setOnClickListener(v -> pickLauncher.launch(new String[]{"image/*"}));
        findViewById(R.id.btn_png_convert).setOnClickListener(v -> convert());
        findViewById(R.id.btn_png_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_png_share).setOnClickListener(v -> share());
        setBusy(false);
    }

    // ------------------------------------------------------------------ 选图

    private void loadImage(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null && (mime.startsWith("image/jpeg") || mime.startsWith("image/jpg"))) {
            toast(getString(R.string.png_reject_jpeg));
            return;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new Exception(getString(R.string.png_decode_failed));
            }
            if (bounds.outWidth > MAX_DIM || bounds.outHeight > MAX_DIM) {
                throw new Exception(getString(R.string.png_too_large, MAX_DIM));
            }

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;                        // 不做密度缩放，保证 1 像素 = 1 单位
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) {
                throw new Exception(getString(R.string.png_decode_failed));
            }

            if (srcBitmap != null && srcBitmap != bmp) {
                srcBitmap.recycle();
            }
            srcBitmap = bmp;
            etName.setText(baseName(uri));
            ivPreview.setImageBitmap(bmp);
            lastResult = null;
            lastName = null;
            setBusy(false);
            tvStatus.setText(getString(R.string.png_status_loaded,
                    bmp.getWidth(), bmp.getHeight()));
        } catch (Throwable t) {
            toast(t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    private String baseName(Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) {
            return "pixel_model";
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        s = s.replaceAll("[^0-9A-Za-z_\\u4e00-\\u9fa5]", "_");
        return s.isEmpty() ? "pixel_model" : s;
    }

    // ------------------------------------------------------------------ 转换

    private void convert() {
        final Bitmap bmp = srcBitmap;
        if (bmp == null) {
            toast(getString(R.string.png_need_image));
            return;
        }
        final String name = safeName(etName.getText().toString());
        final int tol = clampInt(parseInt(etTol.getText().toString(), 12), 0, 441);

        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(PREF_TOL, tol).apply();

        setBusy(true);
        tvStatus.setText(getString(R.string.png_status_working));

        new Thread(() -> {
            PixelArtConverter.ConvertResult res = null;
            String err = null;
            int factor = 1, srcW = 0, srcH = 0, outW = 0, outH = 0;
            try {
                int w = bmp.getWidth(), h = bmp.getHeight();
                srcW = w;
                srcH = h;
                int[][] px = PixelArtAndroidIO.bitmapToMatrix(bmp);
                // 与旧核心同口径：alpha 低于阈值的像素一律视为透明背景，
                // 避免个别应用"透明白"(0x00FFFFFF) 被新核心当成前景生成白块
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (((px[y][x] >>> 24) & 0xFF) < ALPHA_CUTOFF) {
                            px[y][x] = 0x00000000;
                        }
                    }
                }
                // 自动轻量化：新核心无前景上限，这里在调用侧保证轻量 ——
                // 立方体数超过预算时按整数倍最近邻降采样重试（最近邻保留像素画原始颜色，外观不变）
                while (true) {
                    res = PixelArtConverter.convert(px, w, h, 1, tol, 1.0, name);
                    if (res.cubes <= CUBE_BUDGET || (w <= MIN_DIM && h <= MIN_DIM) || factor >= 64) {
                        break;
                    }
                    int f = factor + 1;
                    int nw = Math.max(1, (srcW + f - 1) / f);
                    int nh = Math.max(1, (srcH + f - 1) / f);
                    if (nw >= w && nh >= h) {
                        break;
                    }
                    px = downscaleNearest(px, w, h, nw, nh);
                    w = nw;
                    h = nh;
                    factor = f;
                }
                // 交付核心在 OBJ 侧有三处会让"模型没颜色"的缺口（颜色本身是好的），这里按真实落盘名兜住：
                //   ① 输出里没有 mtllib 行 —— 查看器/Blender/内置预览都不会去加载 .mtl，模型全白；
                //   ② 材质与贴图被硬编码为 texture / texture.png，而落盘的是 <名字>.mtl / <名字>.png；
                //   ③ 面行写成 v/vt//vn（四段），不是 OBJ 标准的 v/vt/vn，部分解析器直接失败。
                res.obj = remapObj(res.obj, name);
                res.mtl = remapMtl(res.mtl, name);
                outW = res.lw;
                outH = res.lh;
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final PixelArtConverter.ConvertResult r = res;
            final String e = err;
            final int fFactor = factor, fSrcW = srcW, fSrcH = srcH, fW = outW, fH = outH;
            runOnUiThread(() -> {
                if (e != null) {
                    lastResult = null;
                    lastName = null;
                    setBusy(false);
                    tvStatus.setText(getString(R.string.png_status_failed, e));
            finishActivation(false);
                    return;
                }
                lastResult = r;
                lastName = name;
                setBusy(false);
                String msg = getString(R.string.png_status_done2,
                        r.lw, r.lh, r.cubes, r.bbmodelJson.length() / 1024);
                if (r.cubes > CUBE_BUDGET / 4) {
                    msg = msg + "\n" + getString(R.string.png_status_heavy, r.cubes);
                }
                if (fFactor > 1) {
                    msg = msg + "\n" + getString(R.string.png_status_shrunk,
                            fSrcW, fSrcH, fFactor, fW, fH);
                }
                tvStatus.setText(msg);
            finishActivation(true);
            });
        }, "png-to-model").start();
    }

    /**
     * OBJ 落盘前的引用对齐：补 mtllib 行、把材质名改成与落盘一致、面行规范为 v/vt/vn。
     * <p>不改动交付核心源码，缺口的成因与修法都写在调用处注释里。
     */
    private static String remapObj(String obj, String base) {
        StringBuilder sb = new StringBuilder(obj.length() + 64);
        boolean mtlDone = false;
        for (String line : obj.split("\n", -1)) {
            if (line.startsWith("# pixel-art")) {
                line = line.replace("tex=texture", "tex=" + base);
            } else if (line.startsWith("usemtl ")) {
                line = "usemtl " + base;
            } else if (line.startsWith("f ")) {
                // v/vt//vn → v/vt/vn（OBJ 标准三段式）
                line = line.replaceAll("(\\d+)/(\\d+)//(\\d+)", "$1/$2/$3");
            }
            sb.append(line).append('\n');
            if (!mtlDone && line.startsWith("# pixel-art")) {
                sb.append("mtllib ").append(base).append(".mtl\n");
                mtlDone = true;
            }
        }
        if (!mtlDone) {
            sb.insert(0, "mtllib " + base + ".mtl\n");
        }
        return sb.toString();
    }

    /** MTL 落盘前的引用对齐：材质名与 map_Kd 指向真实写出的 <base>.png。 */
    private static String remapMtl(String mtl, String base) {
        return mtl.replace("newmtl texture", "newmtl " + base)
                .replace("map_Kd texture.png", "map_Kd " + base + ".png");
    }

    /** 整数倍最近邻降采样（保留原始像素色，背景判定不受影响）。 */
    private static int[][] downscaleNearest(int[][] src, int w, int h, int nw, int nh) {
        int[][] out = new int[nh][nw];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, (y * h) / nh);
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(w - 1, (x * w) / nw);
                out[y][x] = src[sy][sx];
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 落盘

    /** 保存四件套（bbmodel/obj/mtl/png）：Android 10+ 走 MediaStore，低版本写应用外部目录。 */
    private void save() {
        PixelArtConverter.ConvertResult r = lastResult;
        if (r == null) {
            toast(getString(R.string.png_nothing));
            return;
        }
        String base = safeName(etName.getText().toString());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                saveMedia("Download/MinewaysMobile/" + SUBDIR, base + ".bbmodel", "application/json",
                        r.bbmodelJson.getBytes(StandardCharsets.UTF_8));
                saveMedia("Download/MinewaysMobile/" + SUBDIR, base + ".obj", "text/plain",
                        r.obj.getBytes(StandardCharsets.UTF_8));
                saveMedia("Download/MinewaysMobile/" + SUBDIR, base + ".mtl", "text/plain",
                        r.mtl.getBytes(StandardCharsets.UTF_8));
                saveMedia("Download/MinewaysMobile/" + SUBDIR, base + ".png", "image/png",
                        r.texturePng);
                toast(getString(R.string.png_saved_public, base + ".bbmodel（含 .obj/.mtl/.png）"));
            } else {
                File dir = getExternalFilesDir(SUBDIR);
                if (dir == null) {
                    dir = getFilesDir();
                }
                writeFile(new File(dir, base + ".bbmodel"), r.bbmodelJson.getBytes(StandardCharsets.UTF_8));
                writeFile(new File(dir, base + ".obj"), r.obj.getBytes(StandardCharsets.UTF_8));
                writeFile(new File(dir, base + ".mtl"), r.mtl.getBytes(StandardCharsets.UTF_8));
                writeFile(new File(dir, base + ".png"), r.texturePng);
                toast(getString(R.string.png_saved_private, new File(dir, base + ".bbmodel").getAbsolutePath()));
            }
        } catch (Throwable t) {
            toast(getString(R.string.png_save_failed,
                    t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    private void saveMedia(String relPath, String fileName, String mime, byte[] data) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            throw new Exception("create failed: " + fileName);
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                throw new Exception("open failed: " + fileName);
            }
            os.write(data);
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
            fo.write(data);
        }
    }

    /** 分享 bbmodel（经 FileProvider 授权）；同目录留一份 obj/mtl/png 便于取用。 */
    private void share() {
        PixelArtConverter.ConvertResult r = lastResult;
        if (r == null) {
            toast(getString(R.string.png_nothing));
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("mkdirs failed");
            }
            String base = safeName(etName.getText().toString());
            File out = new File(dir, base + ".bbmodel");
            writeFile(out, r.bbmodelJson.getBytes(StandardCharsets.UTF_8));
            writeFile(new File(dir, base + ".obj"), r.obj.getBytes(StandardCharsets.UTF_8));
            writeFile(new File(dir, base + ".mtl"), r.mtl.getBytes(StandardCharsets.UTF_8));
            writeFile(new File(dir, base + ".png"), r.texturePng);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.png_share)));
        } catch (Throwable t) {
            toast(getString(R.string.png_save_failed,
                    t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 小工具

    private void setBusy(boolean busy) {
        findViewById(R.id.btn_png_convert).setEnabled(!busy);
        findViewById(R.id.btn_png_pick).setEnabled(!busy);
        findViewById(R.id.btn_png_save).setEnabled(!busy && lastResult != null);
        findViewById(R.id.btn_png_share).setEnabled(!busy && lastResult != null);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String safeName(String s) {
        String t = s == null ? "" : s.trim().replaceAll("[^0-9A-Za-z_\\u4e00-\\u9fa5]", "_");
        return t.isEmpty() ? "pixel_model" : t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        finishActivation(false);   // 未确认就退出 -> release，不扣次
        super.onDestroy();
        if (srcBitmap != null) {
            srcBitmap.recycle();
            srcBitmap = null;
        }
    }
}
