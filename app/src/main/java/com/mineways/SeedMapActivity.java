package com.mineways;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 离线种子地图（cubiomes 内核，离线 · 不联网）。
 *
 * <p>输入种子 / 版本 / 维度 → 生成群系地图；可查结构、要塞、出生点、史莱姆区块，
 * 点地图任意位置查该处群系，长按把该点设为地图中心。</p>
 */
public class SeedMapActivity extends AppCompatActivity {

    private static final String[] VERSIONS = {
            "1.21", "1.20", "1.19", "1.18", "1.17", "1.16", "1.15", "1.14", "1.13", "1.12", "1.8"};
    private static final int[] DIM_IDS = {0, -1, 1};
    private static final String[] DIM_NAMES = {"主世界", "下界", "末地"};
    private static final int[] SCALES = {16, 4};
    private static final String[] SCALE_NAMES = {"每像素 16 方块（1 区块，视野大）", "每像素 4 方块（精细）"};

    /** 结构类型（与 cubiomes StructureType 枚举值一致）：{type, 中文名, 颜色}。 */
    private static final Object[][] ST = {
            {5, "村庄", 0xFFFFD24A}, {1, "沙漠神殿", 0xFFE8B84A}, {2, "丛林神庙", 0xFF3FBF5F},
            {3, "女巫小屋", 0xFF9A6ADA}, {4, "雪屋", 0xFFCFE8F0}, {8, "海底神殿", 0xFF4AD8E8},
            {9, "林地府邸", 0xFFB0603A}, {10, "掠夺者前哨", 0xFFFF7040}, {11, "废弃传送门", 0xFFA08A6A},
            {13, "远古城市", 0xFF6A6A9A}, {23, "古迹废墟", 0xFFC8A878}, {24, "试炼密室", 0xFFFFA040},
            {14, "埋藏的宝藏", 0xFFFFE08A}, {6, "海底废墟", 0xFF6AD8D0},
            {18, "下界要塞", 0xFFE05A5A}, {19, "堡垒遗迹", 0xFF9A7A4A}, {20, "末地城", 0xFFD8C8E8},
    };

    private static final int BG = 0xFF0F1013, PANEL = 0xFF15171A, TEXT = 0xFFEDEDED,
            DIM = 0xFF8A9099, DIM2 = 0xFF5C6268, ACCENT = 0xFFD9603A;

    private EditText etSeed, etCx, etCz;
    private Spinner spVer, spDim, spScale, spBiome;
    private CheckBox cbSlime;
    private ImageView ivMap;
    private TextView tvResult;

    private long seed;
    private long genPtr;
    private int mapSize = 256;
    private int lastX0, lastZ0, lastScale = 16, lastDim = 0;
    private String lastVer = "1.21";
    private Bitmap lastMap;
    private final List<int[]> markers = new ArrayList<>();   // {blockX, blockZ, color}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        ivMap.setOnClickListener(v -> {
            toast("点地图查群系 · 长按设为地图中心");
        });
        ivMap.setOnLongClickListener(v -> {
            toast("用下面的「中心 X / Z」+ 生成按钮可换位置");
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (genPtr != 0) {
            SeedMapNative.nativeFree(genPtr);
            genPtr = 0;
        }
    }

    // ---------------------------------------------------------------- 界面

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("离线种子地图");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("按种子推算群系、结构、要塞、出生点，全程离线（内核 cubiomes）。");
        sub.setTextSize(12.5f);
        sub.setTextColor(DIM);
        sub.setPadding(0, dp(6), 0, dp(14));
        col.addView(sub);

        // 种子
        col.addView(label("种子（数字或文字都行，文字按游戏规则换算成数字）"));
        etSeed = input("例如 12345 或 我的世界");
        etSeed.setText("-6169203938496914245");
        col.addView(etSeed);

        // 版本 / 维度
        LinearLayout row1 = row();
        spVer = spinner(VERSIONS, 0);
        row1.addView(spinnerBox("版本（Java 版生成规则）", spVer, false));
        spDim = spinner(DIM_NAMES, 0);
        row1.addView(spinnerBox("维度", spDim, false));
        col.addView(row1);

        // 分辨率
        spScale = spinner(SCALE_NAMES, 0);
        col.addView(spinnerBox("地图分辨率", spScale, true));

        // 中心
        LinearLayout row2 = row();
        etCx = input("中心 X");
        etCx.setText("0");
        row2.addView(inputBox("中心 X（方块）", etCx));
        etCz = input("中心 Z");
        etCz.setText("0");
        row2.addView(inputBox("中心 Z（方块）", etCz));
        col.addView(row2);

        cbSlime = new CheckBox(this);
        cbSlime.setText("显示史莱姆区块（主世界 · 每像素 16 方块时）");
        cbSlime.setTextSize(12.5f);
        cbSlime.setTextColor(DIM);
        cbSlime.setPadding(0, dp(6), 0, dp(6));
        col.addView(cbSlime);

        // 按钮
        Button gen = button("生成地图", true, true);
        gen.setOnClickListener(v -> generateMap());
        col.addView(gen);

        LinearLayout row3 = row();
        Button st = button("找结构（±4000 方块）", false, false);
        st.setOnClickListener(v -> findStructures());
        row3.addView(st);
        Button sh = button("找要塞", false, false);
        sh.setOnClickListener(v -> findStrongholds());
        row3.addView(sh);
        col.addView(row3);

        LinearLayout row4 = row();
        Button spawn = button("出生点", false, false);
        spawn.setOnClickListener(v -> showSpawn());
        row4.addView(spawn);
        Button copySeed = button("复制种子", false, false);
        copySeed.setOnClickListener(v -> copy(String.valueOf(seed), "种子已复制"));
        row4.addView(copySeed);
        col.addView(row4);

        // 找群系
        final int[] biomes = BiomePalette.TARGETS;
        String[] names = new String[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            names[i] = BiomePalette.name(biomes[i]);
        }
        spBiome = spinner(names, 0);
        col.addView(spinnerBox("找最近群系（半径 6000 方块，4 方块精度扫描）", spBiome, true));
        Button findBiome = button("查找最近群系", false, true);
        findBiome.setOnClickListener(v -> findBiome());
        col.addView(findBiome);

        // 地图
        FrameLayout mapBox = new FrameLayout(this);
        GradientDrawable mbg = round(PANEL, 12);
        mbg.setStroke(1, 0x22FFFFFF);
        mapBox.setBackground(mbg);
        int pad = dp(6);
        mapBox.setPadding(pad, pad, pad, pad);

        ivMap = new ImageView(this);
        ivMap.setAdjustViewBounds(true);
        ivMap.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivMap.setMinimumHeight(dp(220));
        mapBox.addView(ivMap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(14);
        mlp.bottomMargin = dp(10);
        col.addView(mapBox, mlp);

        ivMap.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    downTime = System.currentTimeMillis();
                    return true;
                }
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    float dx = Math.abs(e.getX() - downX);
                    float dy = Math.abs(e.getY() - downY);
                    if (dx < 12 && dy < 12) {
                        boolean longPress = System.currentTimeMillis() - downTime > 450;
                        queryAt(e.getX(), e.getY(), longPress);
                    }
                    return true;
                }
                return false;
            }
        });

        tvResult = new TextView(this);
        tvResult.setTextSize(12.5f);
        tvResult.setTextColor(TEXT);
        tvResult.setLineSpacing(dp(4), 1f);
        tvResult.setPadding(dp(12), dp(12), dp(12), dp(12));
        tvResult.setBackground(round(PANEL, 12));
        tvResult.setTextIsSelectable(true);
        tvResult.setText("还没有地图。填好种子后点「生成地图」。");
        col.addView(tvResult);

        return sv;
    }

    // ---------------------------------------------------------------- 逻辑

    private void generateMap() {
        if (!SeedMapNative.available()) {
            toast("native 库没加载成功：" + SeedMapNative.loadError());
            return;
        }
        seed = parseSeed(text(etSeed));
        lastVer = VERSIONS[spVer.getSelectedItemPosition()];
        lastDim = DIM_IDS[spDim.getSelectedItemPosition()];
        lastScale = SCALES[spScale.getSelectedItemPosition()];
        final int cx = parseInt(text(etCx), 0);
        final int cz = parseInt(text(etCz), 0);
        final boolean slime = cbSlime.isChecked() && lastScale == 16 && lastDim == 0;

        tvResult.setText("正在计算 " + mapSize + "×" + mapSize + " 采样点…");
        new Thread(() -> {
            final int scale = lastScale;
            final int half = mapSize / 2;
            final int x0 = (cx / scale) - half;
            final int z0 = (cz / scale) - half;
            final int yUnit = (lastDim == 0 ? 319 : (lastDim == -1 ? 127 : 79)) / scale;
            freeGen();
            genPtr = SeedMapNative.nativeCreate(lastVer, lastDim, seed);
            final int[] ids = genPtr == 0 ? null
                    : SeedMapNative.nativeMap(genPtr, scale, x0, z0, mapSize, mapSize, yUnit);
            final Bitmap bmp = ids == null ? null : render(ids, mapSize, mapSize, slime, x0, z0, scale);
            runOnUiThread(() -> {
                if (bmp == null) {
                    tvResult.setText("生成失败：种子/版本组合算不出来，换个版本试试。");
                    return;
                }
                lastX0 = x0;
                lastZ0 = z0;
                lastMap = bmp;
                markers.clear();
                drawMarkers();
                tvResult.setText("地图已生成：\n  版本 " + lastVer + " · " + DIM_NAMES[spDim.getSelectedItemPosition()]
                        + " · 每像素 " + scale + " 方块\n  覆盖范围 X " + (lastX0 * scale)
                        + " ~ " + ((lastX0 + mapSize) * scale) + " , Z " + (lastZ0 * scale)
                        + " ~ " + ((lastZ0 + mapSize) * scale)
                        + "\n  （点地图查群系，长按看提示；下面按钮可找结构 / 要塞 / 出生点）");
            });
        }, "seedmap").start();
    }

    /** 群系 id 表 → 位图（含可选史莱姆区块叠加）。 */
    private Bitmap render(int[] ids, int w, int h, boolean slime, int x0, int z0, int scale) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                px[j * w + i] = BiomePalette.color(ids[j * w + i]);
            }
        }
        if (slime) {
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    if (SeedMapNative.nativeSlimeChunk(seed, x0 + i, z0 + j)) {
                        px[j * w + i] = blend(px[j * w + i], 0xFF39FF6A, 0.45f);
                    }
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static int blend(int base, int over, float a) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        int r = (int) (br * (1 - a) + or * a), g = (int) (bg * (1 - a) + og * a);
        int b = (int) (bb * (1 - a) + ob * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 把标记点画到当前地图（副本）上。 */
    private void drawMarkers() {
        if (lastMap == null) {
            return;
        }
        Bitmap bmp = lastMap.copy(Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        for (int[] m : markers) {
            int px = (m[0] / lastScale) - lastX0;
            int pz = (m[1] / lastScale) - lastZ0;
            if (px < 0 || pz < 0 || px >= bmp.getWidth() || pz >= bmp.getHeight()) {
                continue;
            }
            p.setColor(m[2]);
            c.drawCircle(px, pz, 4f, p);
            c.drawPoint(px, pz, p);
        }
        ivMap.setImageBitmap(bmp);
    }

    private void findStructures() {
        if (genPtr == 0 || lastMap == null) {
            toast("先生成地图");
            return;
        }
        final int cx = lastX0 * lastScale + (mapSize / 2) * lastScale;
        final int cz = lastZ0 * lastScale + (mapSize / 2) * lastScale;
        tvResult.setText("正在定位结构…");
        new Thread(() -> {
            final int[] r = SeedMapNative.nativeStructures(genPtr, lastVer, seed, lastDim,
                    cx, cz, 4000, 400);
            runOnUiThread(() -> showStructures(r, cx, cz));
        }, "seedmap-st").start();
    }

    private void showStructures(int[] r, int cx, int cz) {
        if (r == null || r.length == 0) {
            tvResult.setText("±4000 方块内没找到（这个版本可能没有这些结构，或该区域确实没有）。");
            return;
        }
        markers.clear();
        int n = r.length / 3;
        final List<Object[]> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int type = r[3 * i], x = r[3 * i + 1], z = r[3 * i + 2];
            int color = stColor(type);
            list.add(new Object[]{x, z, type, color, (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz)});
            markers.add(new int[]{x, z, color});
        }
        Collections.sort(list, Comparator.comparingLong(o -> (Long) o[4]));
        drawMarkers();

        StringBuilder sb = new StringBuilder();
        sb.append("±4000 方块内共 ").append(n).append(" 个结构（按距离排序，最多列 40 个）：\n");
        int shown = Math.min(n, 40);
        for (int i = 0; i < shown; i++) {
            Object[] o = list.get(i);
            sb.append("  · ").append(stName((Integer) o[2]))
              .append("  X ").append(o[0]).append("  Z ").append(o[1])
              .append("  （").append(Math.round(Math.sqrt((Long) o[4]))).append(" 格）\n");
        }
        if (n > shown) {
            sb.append("  …其余 ").append(n - shown).append(" 个略\n");
        }
        sb.append("\n提示：地图上的圆圈就是它们的位置（颜色对应结构类型）。");
        tvResult.setText(sb.toString());
    }

    private void findStrongholds() {
        if (genPtr == 0 || lastMap == null) {
            toast("先生成地图");
            return;
        }
        if (lastDim != 0) {
            toast("要塞只在主世界");
            return;
        }
        final int cx = lastX0 * lastScale + (mapSize / 2) * lastScale;
        final int cz = lastZ0 * lastScale + (mapSize / 2) * lastScale;
        tvResult.setText("正在定位要塞…");
        new Thread(() -> {
            final int[] r = SeedMapNative.nativeStrongholds(genPtr, lastVer, seed, cx, cz, 6000, 24);
            runOnUiThread(() -> {
                if (r == null || r.length == 0) {
                    tvResult.setText("±6000 方块内没有要塞（记录里一共只有 3 环，可能都在更远处）。");
                    return;
                }
                markers.clear();
                StringBuilder sb = new StringBuilder("要塞（第一个通常在 1400~2700 格外）：\n");
                for (int i = 0; i < r.length / 2; i++) {
                    int x = r[2 * i], z = r[2 * i + 1];
                    long d = (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz);
                    sb.append("  · 要塞 ").append(i + 1).append("  X ").append(x).append("  Z ").append(z)
                      .append("  （").append(Math.round(Math.sqrt(d))).append(" 格）\n");
                    markers.add(new int[]{x, z, 0xFF4AD8E8});
                }
                drawMarkers();
                tvResult.setText(sb.toString());
            });
        }, "seedmap-sh").start();
    }

    private void showSpawn() {
        if (genPtr == 0) {
            toast("先生成地图");
            return;
        }
        new Thread(() -> {
            final int[] p = SeedMapNative.nativeSpawn(genPtr);
            runOnUiThread(() -> {
                if (p == null) {
                    tvResult.setText("出生点算不出来，换个版本试试。");
                    return;
                }
                markers.clear();
                markers.add(new int[]{p[0], p[1], 0xFFFFFFFF});
                drawMarkers();
                tvResult.setText("出生点（估算）： X " + p[0] + "  Z " + p[1]
                        + "\n\n注意：1.18+ 的出生点还跟地表高度有关，这里是 cubiomes 的估算值。");
            });
        }, "seedmap-spawn").start();
    }

    private void findBiome() {
        if (!SeedMapNative.available()) {
            toast("native 库没加载成功");
            return;
        }
        final int target = BiomePalette.TARGETS[spBiome.getSelectedItemPosition()];
        final String ver = VERSIONS[spVer.getSelectedItemPosition()];
        final int dim = DIM_IDS[spDim.getSelectedItemPosition()];
        final int cx = parseInt(text(etCx), 0);
        final int cz = parseInt(text(etCz), 0);
        final long s = parseSeed(text(etSeed));
        tvResult.setText("正在扫描 " + BiomePalette.name(target) + "…");
        new Thread(() -> {
            final long ptr = SeedMapNative.nativeCreate(ver, dim, s);
            if (ptr == 0) {
                runOnUiThread(() -> tvResult.setText("生成器创建失败"));
                return;
            }
            final int scale = 4;
            final int y = (dim == 0 ? 319 : (dim == -1 ? 127 : 79)) >> 2;
            final int step = 16;        // 16 个采样点 = 64 方块
            final int max = 6000 / 64;  // 半径 6000 方块
            int[] found = null;
            outer:
            for (int ring = 0; ring <= max; ring++) {
                for (int i = -ring; i <= ring; i++) {
                    for (int k = -ring; k <= ring; k++) {
                        if (ring > 0 && Math.abs(i) != ring && Math.abs(k) != ring) {
                            continue;
                        }
                        int x = ((cx >> 2) + i * step), z = ((cz >> 2) + k * step);
                        if (SeedMapNative.nativeBiomeAt(ptr, scale, x, y, z) == target) {
                            found = new int[]{x * scale + scale / 2, z * scale + scale / 2};
                            break outer;
                        }
                    }
                }
            }
            SeedMapNative.nativeFree(ptr);
            final int[] f = found;
            runOnUiThread(() -> {
                if (f == null) {
                    tvResult.setText("半径 6000 方块内没找到「" + BiomePalette.name(target) + "」。\n可以换个更常见的群系，或换版本（不同版本群系分布差别很大）。");
                    return;
                }
                markers.clear();
                markers.add(new int[]{f[0], f[1], 0xFFFFE04A});
                drawMarkers();
                tvResult.setText("最近的「" + BiomePalette.name(target) + "」：\n  X " + f[0] + "  Z " + f[1]
                        + "\n\n把中心 X / Z 填成这组坐标再点「生成地图」，就能看到它周围的样子。");
            });
        }, "seedmap-find").start();
    }

    private void queryAt(float screenX, float screenY, boolean longPress) {
        if (lastMap == null) {
            return;
        }
        float w = ivMap.getWidth(), h = ivMap.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int px = (int) (screenX * lastMap.getWidth() / w);
        int pz = (int) (screenY * lastMap.getHeight() / h);
        if (px < 0 || pz < 0 || px >= lastMap.getWidth() || pz >= lastMap.getHeight()) {
            return;
        }
        final int bx = (lastX0 + px) * lastScale;
        final int bz = (lastZ0 + pz) * lastScale;
        if (longPress) {
            etCx.setText(String.valueOf(bx));
            etCz.setText(String.valueOf(bz));
            queryAt(screenX, screenY, false);
            toast("已设为地图中心，点「生成地图」刷新");
            return;
        }
        if (!SeedMapNative.available() || genPtr == 0) {
            return;
        }
        final int scale = lastScale;
        final int yUnit = (lastDim == 0 ? 319 : (lastDim == -1 ? 127 : 79)) / scale;
        final int id = SeedMapNative.nativeBiomeAt(genPtr, scale, bx / scale, yUnit, bz / scale);
        final int chunkX = bx >> 4, chunkZ = bz >> 4;
        final boolean slime = lastDim == 0 && SeedMapNative.nativeSlimeChunk(seed, chunkX, chunkZ);
        Toast.makeText(this, "X " + bx + " Z " + bz + " · " + BiomePalette.name(id)
                + " · 区块 " + chunkX + "," + chunkZ + (slime ? "（史莱姆区块 ✓）" : ""),
                Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 工具

    private void freeGen() {
        if (genPtr != 0) {
            SeedMapNative.nativeFree(genPtr);
            genPtr = 0;
        }
    }

    private static String stName(int type) {
        for (Object[] o : ST) {
            if ((Integer) o[0] == type) {
                return (String) o[1];
            }
        }
        return "结构#" + type;
    }

    private static int stColor(int type) {
        for (Object[] o : ST) {
            if ((Integer) o[0] == type) {
                return (Integer) o[2];
            }
        }
        return 0xFFFF8080;
    }

    /** 种子解析：纯数字（含负号）按数字；否则按 Java 的 String.hashCode（与游戏一致）。 */
    private static long parseSeed(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() == 0) {
            return 0L;
        }
        try {
            return Long.parseLong(t);
        } catch (Throwable ignored) {
        }
        return t.hashCode();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString();
    }

    private void copy(String text, String msg) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("seed", text));
            }
        } catch (Throwable ignored) {
        }
        toast(msg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- 小控件 ----

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(DIM);
        t.setPadding(0, dp(10), 0, dp(6));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM2);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(round(PANEL, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private View inputBox(String title, EditText e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.setPadding(dp(4), 0, dp(4), 0);
        box.addView(label(title));
        box.addView(e);
        return box;
    }

    private Spinner spinner(String[] items, int def) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(def);
        s.setBackground(round(PANEL, 10));
        return s;
    }

    private View spinnerBox(String title, Spinner s, boolean full) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(full
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.setPadding(dp(4), 0, dp(4), 0);
        box.addView(label(title));
        box.addView(s);
        return box;
    }

    /**
     * 按钮。full=true 用于独占一行（宽 match_parent）；
     * full=false 用于横向一行里并排（宽 0 + weight 1）—— 竖向列表里绝不能用后者，否则宽度被压成 0、按钮看不见。
     */
    private Button button(String text, boolean primary, boolean full) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFF15171A : TEXT);
        b.setBackground(round(primary ? ACCENT : PANEL, 12));
        LinearLayout.LayoutParams lp = full
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(lp);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
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
}
