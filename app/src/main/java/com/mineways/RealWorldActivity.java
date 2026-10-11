package com.mineways;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.system.OsConstants;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 真实世界生成（Arnis 移植版）。
 *
 * <p>跑的是 <b>arnis 原项目</b>（louis-e/arnis v3.2.0）交叉编译出来的安卓 ARM64 可执行文件：
 * 关掉桌面 GUI（Cargo feature <code>gui</code>），只留取数据 + 生成世界的核心；
 * 内核以原生库形式随包（jniLibs/arm64-v8a/libarnis.so），系统安装时解压到有执行权限的
 * nativeLibraryDir，然后按它的命令行参数启动。
 * 界面 / 参数收集 / 进度回显 / 输出路径在这里，生成算法一行没改。</p>
 *
 * <p>数据源可以在「高级设置」里换：Overpass 镜像（建筑·道路·水系）、
 * OSM 瓦片归档地址、是否叠加 Overture 建筑、高程是否只走 AWS。</p>
 */
public class RealWorldActivity extends Activity {

    /**
     * 内核（Arnis）以**原生库**形式进包：app/src/main/jniLibs/arm64-v8a/libarnis.so。
     * 系统安装 APK 时会把它解压到 nativeLibraryDir（/data/app/&lt;包名&gt;/lib/arm64），
     * 那是全机唯一「应用可写且有执行权限」的地方；应用私有目录（files/、cache/）在
     * Android 10+ 起是 noexec 挂载，放那儿必然 error=13 Permission denied。
     */
    private static final String LIB_NAME = "libarnis.so";

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int FIELD = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    /** 免费 Overpass 镜像（实测可达的排前面）。 */
    private static final String[][] MIRRORS = {
            {"官方代理", "https://api.arnismc.com/overpass/api/interpreter"},
            {"overpass-api.de", "https://overpass-api.de/api/interpreter"},
            {"法国镜像", "https://overpass.openstreetmap.fr/api/interpreter"},
            {"private.coffee", "https://overpass.private.coffee/api/interpreter"},
    };

    private EditText etName, etLat, etLng, etMeters, etScale, etGround, etOverpass, etTilesUrl;
    private CheckBox cbOverture, cbAwsOnly, cbLegacy;
    private LinearLayout advancedBody;
    private TextView tvAdvancedToggle, tvStatus, tvLog;
    private Button btnRun;
    private volatile Process process;
    private File lastOutput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("真实世界生成");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setPadding(dp(20), dp(18), dp(20), dp(2));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("用真实地图数据生成 Minecraft 存档（Arnis 原项目内核）");
        sub.setTextSize(12.5f);
        sub.setTextColor(DIM);
        sub.setPadding(dp(20), 0, dp(20), dp(12));
        root.addView(sub);

        // ---- 参数区（可滚动，卡片式，留足间距）----
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(col);

        // 卡片 1：范围
        LinearLayout card1 = card(col, "范围");
        addField(card1, "世界名", "真实世界", false);
        etName = lastField;
        LinearLayout latRow = row(card1);
        etLat = field(latRow, "中心纬度", "39.9052");
        etLng = field(latRow, "中心经度", "116.3989");
        LinearLayout sizeRow = row(card1);
        etMeters = field(sizeRow, "范围（米）", "300");
        etScale = field(sizeRow, "缩放", "1.0");
        addHint(card1, "300 米约 300×300 格；范围越大越慢，先小范围试。");
        addChips(card1, new String[]{"天安门", "上海外滩", "广州塔"}, new double[][]{
                {39.9052, 116.3989}, {31.2395, 121.4900}, {23.1060, 113.3220}}, 300);

        // 卡片 2：高级设置（默认折叠）
        LinearLayout card2 = card(col, null);
        tvAdvancedToggle = new TextView(this);
        tvAdvancedToggle.setText("高级设置（数据源 · 缩放 · 地面）  ▾");
        tvAdvancedToggle.setTextSize(13.5f);
        tvAdvancedToggle.setTextColor(TEXT);
        tvAdvancedToggle.setPadding(0, 0, 0, 0);
        card2.addView(tvAdvancedToggle);
        advancedBody = new LinearLayout(this);
        advancedBody.setOrientation(LinearLayout.VERTICAL);
        advancedBody.setVisibility(View.GONE);
        card2.addView(advancedBody);
        tvAdvancedToggle.setOnClickListener(v -> {
            boolean show = advancedBody.getVisibility() != View.VISIBLE;
            advancedBody.setVisibility(show ? View.VISIBLE : View.GONE);
            tvAdvancedToggle.setText(show
                    ? "高级设置（数据源 · 缩放 · 地面）  ▴"
                    : "高级设置（数据源 · 缩放 · 地面）  ▾");
        });

        addField(advancedBody, "地面高度（方块 y）", "-62", false);
        etGround = lastField;
        addHint(advancedBody, "数据源：建筑 / 道路 / 水系来自 OSM，可以换镜像。");
        addChips(advancedBody, new String[]{"官方代理", "overpass-api.de", "法国镜像", "private.coffee"},
                null, 0);
        addField(advancedBody, "Overpass 镜像地址", MIRRORS[0][1], false);
        etOverpass = lastField;
        addField(advancedBody, "OSM 瓦片归档地址（可留空）", "", false);
        etTilesUrl = lastField;
        cbOverture = check(advancedBody, "叠加 Overture 建筑数据（中国大陆更全，走 AWS）", true);
        cbAwsOnly = check(advancedBody, "高程只走 AWS 地形瓦片（加速器不稳时用）", false);
        cbLegacy = check(advancedBody, "兼容旧版（Minecraft 1.21.4 以下 / 地图查看器）", false);
        addHint(advancedBody, "默认模式（One World）为了能不断拼新区域，会把整个世界放在「扩展建筑高度」里"
                + "（内容在 Y≈-1550），只有 Java 1.21.4+ 才看得见。\n"
                + "勾上兼容旧版：改用普通世界，地形落在 Y≈-62 原版高度，"
                + "任何版本、任何地图工具都能看见；代价是不同区域不能拼进同一个世界，"
                + "且每次都写进「世界名」那个文件夹（同名会覆盖旧内容）。");

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部固定区：按钮 + 状态 + 日志（永远可见，不再被挤掉）----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(BG);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(12));

        btnRun = new Button(this);
        btnRun.setText("开始生成");
        btnRun.setTextColor(Color.WHITE);
        btnRun.setTextSize(15);
        btnRun.setBackground(round(ACCENT, 14));
        btnRun.setOnClickListener(v -> startGenerate());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        btnRun.setLayoutParams(blp);
        bottom.addView(btnRun);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12.5f);
        tvStatus.setTextColor(ACCENT);
        tvStatus.setPadding(0, dp(10), 0, dp(6));
        tvStatus.setMaxLines(2);
        bottom.addView(tvStatus);

        tvLog = new TextView(this);
        tvLog.setTextSize(10.5f);
        tvLog.setTextColor(DIM);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setBackground(round(FIELD, 10));
        tvLog.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvLog.setTextIsSelectable(true);
        tvLog.setText("准备就绪。");
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(round(FIELD, 10));
        logScroll.addView(tvLog);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        logScroll.setLayoutParams(llp);
        bottom.addView(logScroll);

        root.addView(bottom);

        TextView back = new TextView(this);
        back.setText("← 返回工具箱");
        back.setTextSize(13);
        back.setTextColor(DIM);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(16), dp(12), dp(16), dp(16));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        return root;
    }

    // ---- 小部件工厂 ----

    private EditText lastField;

    private LinearLayout card(LinearLayout parent, String header) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        if (header != null) {
            TextView h = new TextView(this);
            h.setText(header);
            h.setTextSize(13.5f);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextColor(TEXT);
            h.setPadding(0, 0, 0, dp(6));
            c.addView(h);
        }
        parent.addView(c);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, 0);
        parent.addView(r);
        return r;
    }

    private void addField(LinearLayout parent, String label, String value, boolean wide) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11.5f);
        l.setTextColor(DIM);
        l.setPadding(0, dp(8), 0, dp(4));
        parent.addView(l);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(TEXT);
        et.setHintTextColor(DIM);
        et.setSingleLine(true);
        et.setBackground(round(FIELD, 10));
        et.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(lp);
        parent.addView(et);
        lastField = et;
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = dp(8);
        box.setLayoutParams(blp);
        parent.addView(box);

        TextView l = new TextView(this);
        l.setText(hint);
        l.setTextSize(11.5f);
        l.setTextColor(DIM);
        l.setPadding(0, dp(6), 0, dp(4));
        box.addView(l);

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(TEXT);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setBackground(round(FIELD, 10));
        et.setPadding(dp(12), dp(11), dp(12), dp(11));
        box.addView(et);
        return et;
    }

    private void addHint(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(DIM);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv);
    }

    private CheckBox check(LinearLayout parent, String text, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(12.5f);
        cb.setTextColor(TEXT);
        cb.setChecked(def);
        cb.setPadding(0, dp(10), 0, 0);
        parent.addView(cb);
        return cb;
    }

    /** 横向可滚的预设 chips，不会把卡片挤爆。 */
    private void addChips(LinearLayout parent, String[] names, double[][] centers, double meters) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(10), 0, 0);
        hs.addView(r);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(12);
            chip.setTextColor(TEXT);
            chip.setBackground(round(FIELD, 18));
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (centers != null && idx < centers.length) {
                    etLat.setText(String.format(Locale.US, "%.4f", centers[idx][0]));
                    etLng.setText(String.format(Locale.US, "%.4f", centers[idx][1]));
                    etMeters.setText(String.format(Locale.US, "%.0f", meters));
                } else if (idx < MIRRORS.length) {
                    etOverpass.setText(MIRRORS[idx][1]);
                }
            });
            r.addView(chip);
        }
        parent.addView(hs);
    }

    // ------------------------------------------------------------------ 运行

    private void startGenerate() {
        if (process != null) {
            toast("正在生成，等这次跑完再试");
            return;
        }
        double centerLat, centerLng, meters;
        try {
            centerLat = Double.parseDouble(safe(etLat.getText().toString(), "39.9052"));
            centerLng = Double.parseDouble(safe(etLng.getText().toString(), "116.3989"));
            meters = Double.parseDouble(safe(etMeters.getText().toString(), "300"));
        } catch (Throwable t) {
            toast("中心经纬度或范围填得不对");
            return;
        }
        if (Math.abs(centerLat) > 85 || Math.abs(centerLng) > 180 || meters <= 0 || meters > 8000) {
            toast("范围建议 50～8000 米，纬度需在 ±85 之内");
            return;
        }
        final String name = safe(etName.getText().toString(), "真实世界");
        final String scale = safe(etScale.getText().toString(), "1.0");
        final String ground = safe(etGround.getText().toString(), "-62");

        // 中心点 + 范围 → 经纬度框（经度按纬度做余弦修正）
        double halfLat = (meters / 2.0) / 111320.0;
        double mPerLng = 111320.0 * Math.cos(Math.toRadians(centerLat));
        double halfLng = (meters / 2.0) / Math.max(1.0, mPerLng);
        final String bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                centerLat - halfLat, centerLng - halfLng, centerLat + halfLat, centerLng + halfLng);

        File outDir = new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "真实世界地图"), name);
        if (!outDir.exists() && !outDir.mkdirs()) {
            toast("无法创建输出目录");
            return;
        }
        lastOutput = outDir;

        tvLog.setText("");
        tvStatus.setText("准备中…");
        btnRun.setEnabled(false);

        new Thread(() -> {
            try {
                File exe = ensureBinary();
                if (exe == null) {
                    runOnUiThread(() -> {
                        tvStatus.setText("内置内核缺失：本机 CPU 架构不受支持（需要 arm64）");
                        btnRun.setEnabled(true);
                    });
                    return;
                }

                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.add("--bbox");
                cmd.add(bbox);
                // 两种模式的内核参数不一样：
                //  · 默认（One World）：--world-name 只在 --one-world 下有效（否则内核直接报
                //    "--world-name only applies to --one-world."）；--output-dir 是「存档总目录」，
                //    世界落在 <总目录>/<世界名>，不同区域能不断拼进同一个世界。
                //  · 兼容旧版：关掉 --one-world，--output-dir 直接就是世界目录本身，
                //    地形落在原版高度（Y≈-62），任何 Minecraft 版本 / 地图工具都能看见。
                final boolean legacy = cbLegacy != null && cbLegacy.isChecked();
                if (legacy) {
                    cmd.add("--output-dir");
                    cmd.add(outDir.getAbsolutePath());
                } else {
                    cmd.add("--one-world");
                    cmd.add("--output-dir");
                    cmd.add(outDir.getParentFile().getAbsolutePath());
                    cmd.add("--world-name");
                    cmd.add(name);
                }
                cmd.add("--scale");
                cmd.add(scale);
                cmd.add("--ground-level");
                cmd.add(ground);
                cmd.add("--mode");
                cmd.add("geo-terrain");
                cmd.add("--overture");
                cmd.add(cbOverture != null && cbOverture.isChecked() ? "true" : "false");
                if (cbAwsOnly != null && cbAwsOnly.isChecked()) {
                    cmd.add("--aws-only-elevation");
                }
                String tiles = etTilesUrl == null ? "" : etTilesUrl.getText().toString().trim();
                if (!tiles.isEmpty()) {
                    cmd.add("--osm-tiles-url");
                    cmd.add(tiles);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(outDir.getParentFile());
                pb.redirectErrorStream(true);
                File cache = new File(getFilesDir(), "arnis_cache");
                cache.mkdirs();
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                pb.environment().put("XDG_CACHE_HOME", cache.getAbsolutePath());
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());

                // 移植版补丁：自定义 Overpass 源 + 备用镜像
                String overpass = etOverpass == null ? "" : etOverpass.getText().toString().trim();
                if (!overpass.isEmpty()) {
                    pb.environment().put("ARNIS_OVERPASS_URL", overpass);
                    StringBuilder mirrors = new StringBuilder();
                    for (String[] m : MIRRORS) {
                        if (!m[1].equals(overpass)) {
                            if (mirrors.length() > 0) {
                                mirrors.append(',');
                            }
                            mirrors.append(m[1]);
                        }
                    }
                    pb.environment().put("ARNIS_OVERPASS_MIRRORS", mirrors.toString());
                }

                runOnUiThread(() -> {
                    tvStatus.setText("正在取数据并生成…（首次要先下瓦片，稍等）");
                    appendLog("范围 " + bbox);
                    appendLog("世界名 " + name + " · 缩放 " + scale + " · 地面 " + ground
                            + (legacy ? " · 兼容模式（原版高度）" : " · One World（扩展高度，需 1.21.4+）"));
                    if (new File(outDir, "level.dat").isFile()) {
                        appendLog(legacy
                                ? "注意：这个文件夹里已经有一份存档 → 兼容模式会覆盖/混进旧内容，建议换个「世界名」"
                                : "注意：同名世界已存在 → 本次是往它里面继续扩（换「世界名」可重新生成）");
                    }
                    // 内核自检：路径 / 系统页大小 / 执行位。跑不起来时这三项一眼定位原因：
                    // 页大小 16KB 而内核按 4KB 链接 → Exec format error；可执行 false → noexec。
                    appendLog("内核 " + exe.getAbsolutePath()
                            + " · 系统页 " + pageSizeKb() + "KB"
                            + " · 可执行 " + exe.canExecute());
                });

                process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String l = line;
                    runOnUiThread(() -> appendLog(l));
                }
                int code = process.waitFor();
                final int exit = code;
                process = null;
                runOnUiThread(() -> {
                    btnRun.setEnabled(true);
                    String verdict = inspectWorld(lastOutput);
                    if (exit == 0) {
                        tvStatus.setText("完成 → 下载/真实世界地图/" + name);
                        appendLog(verdict);
                        appendLog("把整个文件夹丢进 .minecraft/saves 就能进游戏");
                    } else {
                        tvStatus.setText("失败（退出码 " + exit + "），日志见下");
                        appendLog(verdict);
                    }
                });
            } catch (Throwable t) {
                process = null;
                final String msg = humanError(t);
                runOnUiThread(() -> {
                    btnRun.setEnabled(true);
                    tvStatus.setText("出错：" + msg);
                    appendLog("✗ " + msg);
                });
            }
        }, "arnis-run").start();
    }

    /** 系统页大小（KB）。16KB 页机型上，按 4KB 页链接的可执行文件没法启动。 */
    private int pageSizeKb() {
        try {
            return (int) (android.system.Os.sysconf(OsConstants._SC_PAGESIZE) / 1024);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把 Java 的原始异常翻译成能看懂的一句话（尤其是被系统拦下的那两种）。 */
    private String humanError(Throwable t) {
        String raw = String.valueOf(t.getMessage());
        if (raw.contains("error=13") || raw.contains("Permission denied") || raw.contains("EACCES")) {
            return "内核没有执行权限、被系统 noexec 限制拦下 —— 需要装带 lib/arm64/libarnis.so 的包；原始信息：" + raw;
        }
        if (raw.contains("error=8") || raw.contains("Exec format error")) {
            return "内核与本机不兼容（本机需 arm64；若系统页为 16KB 而内核按 4KB 链接也会这样，"
                    + "本机页 " + pageSizeKb() + "KB）；原始信息：" + raw;
        }
        return raw;
    }

    private void appendLog(String line) {
        String old = tvLog.getText().toString();
        String add = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        String text = old.isEmpty() ? add : old + "\n" + add;
        if (text.length() > 8000) {
            text = text.substring(text.length() - 8000);
        }
        tvLog.setText(text);
        hintStatus(line);
    }

    /** 把 arnis 的输出翻译成中文状态，让"数据到底取到没有"一眼可见。 */
    private void hintStatus(String line) {
        String l = line.toLowerCase(Locale.US);
        if (l.contains("fetching data")) {
            tvStatus.setText("① 正在取地图数据（OSM 建筑 / 道路 / 水系）…");
        } else if (l.contains("falling back to overpass") || l.contains("tile archive unavailable")) {
            tvStatus.setText("① 瓦片归档不可用 → 改用 Overpass 镜像取数（正常兜底）");
        } else if (l.contains("overpass fetch failed on all")) {
            tvStatus.setText("❌ 所有 Overpass 镜像都取数失败：换镜像或开加速器");
        } else if (l.contains("timed out") || l.contains("timeout")) {
            tvStatus.setText("⚠ 取数超时：范围调小，或换个 Overpass 镜像");
        } else if (l.contains("fetching elevation") || l.contains("elevation data")) {
            tvStatus.setText("① 正在取高程数据（地形起伏）…");
        } else if (l.contains("[2/7]") || l.contains("[3/7]")) {
            tvStatus.setText("② 数据已到手，正在生成地形与建筑…");
        } else if (l.contains("[4/7]") || l.contains("[5/7]")) {
            tvStatus.setText("③ 正在铺方块、写区块…");
        } else if (l.contains("[6/7]") || l.contains("[7/7]")) {
            tvStatus.setText("④ 收尾：写 level.dat 与区块索引…");
        } else if (l.contains("cannot be added to the one world")) {
            tvStatus.setText("❌ 本次设置与同名存档不一致（缩放 / 地面高度 / 地形模式必须沿用第一次）：换「世界名」可开新世界");
        } else if (l.contains("is not a one world") || l.contains("no arnis_one_world.json")) {
            tvStatus.setText("❌ 同名文件夹已存在且不是 One World 存档：换个「世界名」，或删掉那个文件夹");
        }
    }

    /**
     * 生成的存档到底有没有内容 —— 最硬的判断依据：
     * level.dat 有没有、region 里有没有 .mca 区块文件、总共多大。
     */
    private String inspectWorld(File world) {
        if (world == null || !world.isDirectory()) {
            return "⚠ 没有输出目录，生成可能根本没跑起来";
        }
        boolean hasLevel = new File(world, "level.dat").isFile();
        File region = new File(world, "region");
        int mca = 0;
        long mcaBytes = 0;
        if (region.isDirectory()) {
            File[] files = region.listFiles((d, n) -> n.endsWith(".mca"));
            if (files != null) {
                mca = files.length;
                for (File f : files) {
                    mcaBytes += f.length();
                }
            }
        }
        long total = folderSize(world);
        String size = String.format(Locale.US, "%.1f MB", total / 1048576.0);
        if (hasLevel && mca > 0) {
            return "✓ 地图数据已取到并写出存档：level.dat + " + mca + " 个区块文件，共 " + size;
        }
        if (hasLevel) {
            return "⚠ 只写出了 level.dat，没有区块文件：取数失败或范围太小（总共 " + size + "）";
        }
        return "⚠ 输出目录里没有 level.dat：取数失败（换 Overpass 镜像 / 开加速器），共 " + size;
    }

    private long folderSize(File dir) {
        long sum = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            sum += f.isFile() ? f.length() : folderSize(f);
        }
        return sum;
    }

    /**
     * 取可执行内核：直接用系统安装时解压出来的原生库（nativeLibraryDir/libarnis.so）。
     *
     * 以前是从 assets 释放到 files/arnis_bin/ 再 setExecutable(true)，那在 Android 10+ 上
     * 必然失败：私有目录是 noexec 挂载，内核直接以 error=13 Permission denied 拒绝，
     * 跟文件权限位无关（W^X 限制）。nativeLibraryDir 里的库天生带执行位，所以内核改成
     * 以 libarnis.so 的形式随包发（见 app/build.gradle.kts 的 packagingOptions）。
     */
    private File ensureBinary() {
        File exe = new File(getApplicationInfo().nativeLibraryDir, LIB_NAME);
        if (!exe.isFile()) {
            return null;
        }
        // 个别 ROM / 换机恢复会掉执行位，补一次（nativeLibraryDir 本身允许执行）
        if (!exe.canExecute()) {
            exe.setExecutable(true, false);
        }
        return exe;
    }

    @Override
    protected void onDestroy() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 小工具

    private String safe(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
