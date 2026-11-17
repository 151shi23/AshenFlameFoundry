package com.mineways.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设置分享 / 导入的唯一界面。
 *
 * <p>入口在「关于」页顶部，也可被云控隐藏。页面分三段：导出、导入、历史。
 * 所有会改动本机数据的动作都必须经过预览与确认，并在写入前自动备份。
 */
public class ShareActivity extends Activity {

    public static final String EXTRA_MODE = "mode";   // "export" / "import"
    public static final String MODE_EXPORT = "export";
    public static final String MODE_IMPORT = "import";

    /** 工具抓完预设回传：预设 id 与内容（由 Blockbench / Snowstorm 带回）。 */
    public static final String EXTRA_PRESET_ID = "preset_id";
    public static final String EXTRA_PRESET_BODY = "preset_body";

    private static final int BG = 0xFF0E1420;
    private static final int CARD = 0xFF1A2334;
    private static final int LINE = 0xFF2C3A52;
    private static final int TXT = 0xFFEAF1FF;
    private static final int DIM = 0xFF93A6C4;
    private static final int ACCENT = 0xFF4FA3FF;
    private static final int GOLD = 0xFFF2B13A;

    private LinearLayout body;
    private TextView tabExport, tabImport, tabHistory;

    private List<PrefCatalog.Group> groups = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    /** 用户勾选要放进分享码的创作资产 id。 */
    private final Set<String> assetsSelected = new HashSet<>();

    private EditText nameInput;
    private EditText noteInput;

    private ShareCodec.Snapshot pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!com.mineways.RecentToolFlags.share()) {
            Toast.makeText(this, "设置分享已被云端关闭", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(BG);
        groups = PrefCatalog.list(this);
        for (PrefCatalog.Group g : groups) selected.add(g.prefs);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(14);
        root.setPadding(pad, dp(10), pad, 0);
        setContentView(root);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("←", 26, TXT);
        back.setPadding(dp(6), 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("设置分享", 20, TXT));
        TextView sub = text("把本机设置做成码，或导入别人的码", 12, DIM);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);

        // 页签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(42), 1);
        tabExport = tab("导出", tp);
        tabImport = tab("导入", new LinearLayout.LayoutParams(0, dp(42), 1));
        tabHistory = tab("历史", new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tabExport);
        tabs.addView(tabImport);
        tabs.addView(tabHistory);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, -2);
        tabsLp.topMargin = dp(12);
        root.addView(tabs, tabsLp);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1);
        svLp.topMargin = dp(10);
        root.addView(sv, svLp);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(28));
        sv.addView(body);

        String mode = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_IMPORT.equals(mode)) showImport();
        else showExport();
        handlePresetIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showExport();
        handlePresetIntent();
    }

    /** 工具抓完预设回到本页时，直接生成分享码。 */
    private void handlePresetIntent() {
        Intent it = getIntent();
        if (it == null) return;
        String id = it.getStringExtra(EXTRA_PRESET_ID);
        String body = it.getStringExtra(EXTRA_PRESET_BODY);
        if (id == null || body == null) return;
        it.removeExtra(EXTRA_PRESET_ID);
        it.removeExtra(EXTRA_PRESET_BODY);
        if ("{}".equals(body.trim())) {
            Toast.makeText(this, "没有读到可分享的预设：先在该工具里调整设置再试", Toast.LENGTH_LONG).show();
            return;
        }
        ShareCodec.Snapshot s = new ShareCodec.Snapshot();
        s.name = PresetBridge.title(id);
        s.presets.put(id, body);
        try {
            String code = ShareCodec.encode(s);
            if (code.length() > ShareCodec.MAX_CODE) {
                Toast.makeText(this, "预设内容过长（" + code.length() + " 字符），无法生成分享码", Toast.LENGTH_LONG).show();
                return;
            }
            showCodeDialog("已读取「" + PresetBridge.title(id) + "」预设", code, s);
        } catch (Exception e) {
            Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ 导出

    private void showExport() {
        selectTab(0);
        body.removeAllViews();

        body.addView(label("名称"));
        nameInput = input("例如：我的导出配置", 1);
        body.addView(nameInput);

        body.addView(label("备注（对方能看到）"));
        noteInput = input("可留空，例如：给群友的材质包方案", 1);
        body.addView(noteInput);

        addPresetSection();
        addAssetSection();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(14);
        head.addView(text("选择要分享的设置", 15, TXT), new LinearLayout.LayoutParams(0, -2, 1));
        TextView all = smallBtn("全选");
        all.setOnClickListener(v -> {
            for (PrefCatalog.Group g : groups) selected.add(g.prefs);
            showExport();
        });
        TextView none = smallBtn("清空");
        none.setOnClickListener(v -> {
            selected.clear();
            showExport();
        });
        head.addView(all);
        head.addView(none);
        body.addView(head, hp);

        if (groups.isEmpty()) {
            body.addView(card("本机没有可分享的设置", "打开任意本地工具并调整一次设置后再来。"));
        }

        for (PrefCatalog.Group g : groups) {
            body.addView(groupRow(g));
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(16);
        Button one = primary("生成分享码");
        one.setOnClickListener(v -> doExport());
        Button per = new Button(this);
        per.setText("每项各生成一个");
        per.setAllCaps(false);
        per.setTextColor(TXT);
        per.setBackground(round(CARD, LINE, 12));
        per.setOnClickListener(v -> doExportEach());
        acts.addView(one, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams perLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        perLp.leftMargin = dp(10);
        acts.addView(per, perLp);
        body.addView(acts, ap);

        TextView tip = text("密钥、账号、激活、云控缓存不会进入分享码；创作内容只打包你勾选的文件。导入前自动备份，可一键撤销。", 12, DIM);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.topMargin = dp(12);
        body.addView(tip, tipLp);
    }

    // ------------------------------------------------------------ 生产工具预设

    private void addPresetSection() {
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(16);
        TextView head = text("生产工具预设（一键分享）", 15, TXT);
        head.setLayoutParams(hp);
        body.addView(head);

        TextView sub = text("读取工具当前的完整预设：建模器与粒子动画会短暂打开一次工具抓取，PS 直接读本机文件。", 12, DIM);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = dp(8);
        sub.setLayoutParams(sp);
        body.addView(sub);

        for (String id : PresetBridge.all()) body.addView(presetRow(id));
    }

    private View presetRow(String id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(CARD, LINE, 12));
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(PresetBridge.title(id), 15, TXT));
        tx.addView(text(PresetBridge.hint(id), 12, DIM));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));

        TextView get = smallBtn("读取");
        get.setTextColor(GOLD);
        get.setOnClickListener(v -> capturePreset(id));
        row.addView(get);
        return row;
    }

    private void capturePreset(String id) {
        if (PresetBridge.PHOTOCRAFT.equals(id)) {
            String body;
            try {
                body = PresetBridge.readFilePreset(this);
            } catch (Exception e) {
                Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            if (body == null) {
                Toast.makeText(this, "还没有可分享的 PS 预设：先打开 PhotoCraft 调一次设置或保存一个预设", Toast.LENGTH_LONG).show();
                return;
            }
            ShareCodec.Snapshot s = new ShareCodec.Snapshot();
            s.name = presetName(id);
            s.note = noteInput == null ? "" : noteInput.getText().toString().trim();
            s.presets.put(id, body);
            try {
                String code = ShareCodec.encode(s);
                if (code.length() > ShareCodec.MAX_CODE) {
                    Toast.makeText(this, "预设内容过长（" + code.length() + " 字符），无法生成分享码", Toast.LENGTH_LONG).show();
                    return;
                }
                showCodeDialog("「" + PresetBridge.title(id) + "」预设已生成", code, s);
            } catch (Exception e) {
                Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        // 网页型：短暂打开工具抓取一份 localStorage 快照，抓完自动回到本页
        Intent it;
        if (PresetBridge.BLOCKBENCH.equals(id)) {
            it = new Intent(this, com.mineways.BlockbenchActivity.class);
            it.putExtra(com.mineways.BlockbenchActivity.EXTRA_MODE,
                    com.mineways.BlockbenchActivity.MODE_OFFLINE);
            it.putExtra(com.mineways.BlockbenchActivity.EXTRA_SHARE_CAPTURE, true);
        } else {
            it = new Intent(this, com.mineways.SnowstormActivity.class);
            it.putExtra(com.mineways.SnowstormActivity.EXTRA_SHARE_CAPTURE, true);
        }
        startActivity(it);
    }

    private String presetName(String id) {
        String n = nameInput == null ? "" : nameInput.getText().toString().trim();
        return n.isEmpty() ? PresetBridge.title(id) : n;
    }

    // ------------------------------------------------------------ 创作内容

    private void addAssetSection() {
        List<CreativeAssets.Item> items = CreativeAssets.list(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(16);
        TextView head = text("创作内容（自选放进分享码）", 15, TXT);
        head.setLayoutParams(hp);
        body.addView(head);

        TextView sub = text("音源脚本、剪辑字体、上传素材、AI 记忆等用户文件；勾选什么就打包什么，体积过大的会禁用。", 12, DIM);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = dp(8);
        sub.setLayoutParams(sp);
        body.addView(sub);

        for (CreativeAssets.Item it : items) body.addView(assetRow(it));
    }

    private View assetRow(CreativeAssets.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(CARD, LINE, 12));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        CheckBox cb = new CheckBox(this);
        cb.setEnabled(it.shareable());
        cb.setChecked(it.shareable() && assetsSelected.contains(it.id));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) assetsSelected.add(it.id);
            else assetsSelected.remove(it.id);
        });
        row.addView(cb);

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(it.title + " · " + it.tool, 15, TXT));
        String info = it.sizeText();
        if (!it.shareable() && it.files > 0) info += " · 体积过大，无法放进分享码";
        tx.addView(text(info + "　" + it.hint, 12, DIM));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View groupRow(PrefCatalog.Group g) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(CARD, LINE, 12));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(g.prefs));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) selected.add(g.prefs);
            else selected.remove(g.prefs);
        });
        row.addView(cb);

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(g.title, 15, TXT));
        String info = g.count() + " 项设置";
        if (g.secretCount > 0) info += " · 已排除 " + g.secretCount + " 项（密钥 / 本机路径）";
        tx.addView(text(info, 12, DIM));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        row.setLayoutParams(rp);
        return row;
    }

    private void doExport() {
        ShareCodec.Snapshot s = buildSnapshot(false);
        if (s == null) return;
        try {
            String code = ShareCodec.encode(s);
            if (code.length() > ShareCodec.MAX_CODE) {
                Toast.makeText(this, "内容过长（" + code.length() + " 字符），请减少勾选项或改用「每项各生成一个」", Toast.LENGTH_LONG).show();
                return;
            }
            showCodeDialog("分享码已生成", code, s);
        } catch (Exception e) {
            Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doExportEach() {
        List<PrefCatalog.Group> picked = new ArrayList<>();
        for (PrefCatalog.Group g : groups) if (selected.contains(g.prefs)) picked.add(g);
        if (picked.isEmpty()) {
            Toast.makeText(this, "请先勾选要分享的设置", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (PrefCatalog.Group g : picked) {
                ShareCodec.Snapshot s = new ShareCodec.Snapshot();
                s.name = (nameInput.getText().toString().trim().isEmpty() ? g.title : nameInput.getText().toString().trim());
                s.note = noteInput.getText().toString().trim();
                s.prefs.put(g.prefs, g.keys);
                sb.append("【").append(g.title).append("】\n")
                        .append(ShareCodec.encode(s)).append("\n\n");
            }
        } catch (Exception e) {
            Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        showCodeDialog("已生成 " + picked.size() + " 个分享码", sb.toString().trim(), null);
    }

    private ShareCodec.Snapshot buildSnapshot(boolean all) {
        ShareCodec.Snapshot s = new ShareCodec.Snapshot();
        s.name = nameInput.getText().toString().trim();
        s.note = noteInput.getText().toString().trim();
        for (PrefCatalog.Group g : groups) {
            if (all || selected.contains(g.prefs)) s.prefs.put(g.prefs, g.keys);
        }
        for (String id : assetsSelected) {
            try {
                String body = CreativeAssets.read(this, id);
                if (body != null) s.assets.put(id, body);
            } catch (Exception ignored) {
            }
        }
        if (s.prefs.isEmpty() && s.assets.isEmpty()) {
            Toast.makeText(this, "请先勾选要分享的设置或创作内容", Toast.LENGTH_SHORT).show();
            return null;
        }
        return s;
    }

    private void showCodeDialog(String title, String code, ShareCodec.Snapshot s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(6));
        TextView cd = text(code, 11, TXT);
        cd.setTextIsSelectable(true);
        cd.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(cd);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(190)));
        TextView info = text("共 " + code.length() + " 字符", 11, DIM);
        box.addView(info);

        AlertDialog.Builder bd = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("复制", (d, w) -> {
                    copy(code);
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("分享", (d, w) -> {
                    Intent it = new Intent(Intent.ACTION_SEND);
                    it.setType("text/plain");
                    it.putExtra(Intent.EXTRA_TEXT, code);
                    startActivity(Intent.createChooser(it, "分享设置码"));
                });
        if (s != null) {
            bd.setNegativeButton("存为历史", (d, w) -> {
                try {
                    ShareStore.rememberGenerated(this, s, code);
                    Toast.makeText(this, "已存入历史", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            bd.setNegativeButton("关闭", null);
        }
        bd.show();
    }

    // ------------------------------------------------------------ 导入

    private void showImport() {
        selectTab(1);
        body.removeAllViews();
        body.addView(label("粘贴分享码"));
        EditText code = new EditText(this);
        code.setHint("MWSC1.…");
        code.setHintTextColor(0xFF6C7E9C);
        code.setTextColor(TXT);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        code.setMinLines(4);
        code.setGravity(Gravity.TOP);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        code.setBackground(round(CARD, LINE, 12));
        code.setPadding(dp(10), dp(8), dp(10), dp(8));
        body.addView(code);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(10);
        Button paste = new Button(this);
        paste.setText("读剪贴板");
        paste.setAllCaps(false);
        paste.setTextColor(TXT);
        paste.setBackground(round(CARD, LINE, 12));
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                code.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));
            }
        });
        Button parse = primary("解析并预览");
        parse.setOnClickListener(v -> parseCode(code.getText().toString()));
        acts.addView(paste, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        p2.leftMargin = dp(10);
        acts.addView(parse, p2);
        body.addView(acts, ap);

        body.addView(text("导入只会写入可识别的设置键；写入前自动备份，导入后可在「历史」里撤销。", 12, DIM));

        if (pending != null) body.addView(previewCard(pending));
    }

    private void parseCode(String raw) {
        try {
            pending = ShareCodec.decode(raw);
            Toast.makeText(this, "识别成功：" + (pending.keyCount() + pending.presets.size() + pending.assets.size()) + " 项内容", Toast.LENGTH_SHORT).show();
            showImport();
        } catch (Exception e) {
            pending = null;
            new AlertDialog.Builder(this)
                    .setTitle("无法识别")
                    .setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private View previewCard(ShareCodec.Snapshot s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, LINE, 12));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(14);
        box.setLayoutParams(bp);

        box.addView(text(s.name == null || s.name.isEmpty() ? "未命名分享" : s.name, 16, GOLD));
        if (s.note != null && !s.note.isEmpty()) box.addView(text("备注：" + s.note, 12, DIM));
        box.addView(text("包含 " + s.prefs.size() + " 组 / " + s.keyCount() + " 项设置", 12, DIM));
        if (!s.presets.isEmpty()) {
            box.addView(text("另有 " + s.presets.size() + " 个生产工具预设", 12, GOLD));
            for (Map.Entry<String, String> e : s.presets.entrySet()) {
                box.addView(text("预设 · " + PresetBridge.title(e.getKey()), 14, TXT));
                int kb = e.getValue() == null ? 0 : e.getValue().length() / 1024;
                box.addView(text(kb + " KB，打开对应工具后自动生效", 11, DIM));
            }
        }
        if (!s.assets.isEmpty()) {
            box.addView(text("另有 " + s.assets.size() + " 项创作内容", 12, GOLD));
            for (Map.Entry<String, String> e : s.assets.entrySet()) {
                box.addView(text("内容 · " + CreativeAssets.title(e.getKey()), 14, TXT));
                int kb = e.getValue() == null ? 0 : e.getValue().length() / 1024;
                box.addView(text(kb + " KB，导入后写入应用目录，可在历史里撤销", 11, DIM));
            }
        }

        for (Map.Entry<String, java.util.TreeMap<String, Object[]>> g : s.prefs.entrySet()) {
            String pref = g.getKey();
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Map.Entry<String, Object[]> kv : g.getValue().entrySet()) {
                if (i++ >= 6) {
                    sb.append("…共 ").append(g.getValue().size()).append(" 项");
                    break;
                }
                if (sb.length() > 0) sb.append("、");
                sb.append(kv.getKey()).append("=").append(ShareCodec.display(kv.getValue()));
            }
            String title = pref;
            for (PrefCatalog.Group pg : groups) if (pg.prefs.equals(pref)) title = pg.title;
            box.addView(text(title, 14, TXT));
            TextView det = text(sb.toString(), 11, DIM);
            det.setMaxLines(3);
            box.addView(det);
        }

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton merge = new RadioButton(this);
        merge.setText("只补齐（推荐）");
        merge.setTextColor(TXT);
        merge.setId(1001);
        RadioButton over = new RadioButton(this);
        over.setText("覆盖本机");
        over.setTextColor(TXT);
        over.setId(1002);
        rg.addView(merge);
        rg.addView(over);
        rg.check(1001);
        box.addView(rg);

        Button apply = primary("应用（先备份）");
        apply.setOnClickListener(v -> {
            int mode = rg.getCheckedRadioButtonId() == 1002 ? ShareStore.MODE_OVERWRITE : ShareStore.MODE_MERGE;
            confirmApply(s, mode);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(48));
        alp.topMargin = dp(10);
        box.addView(apply, alp);
        return box;
    }

    private void confirmApply(ShareCodec.Snapshot s, int mode) {
        String msg = mode == ShareStore.MODE_OVERWRITE
                ? "将把分享码里的设置写入本机，覆盖你已改过的同名项。\n\n写入前会自动备份，可在历史里撤销。"
                : "只补充本机还没有的设置项，本机已改过的项保持不变。\n\n写入前会自动备份。";
        new AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导入", (d, w) -> {
                    ShareStore.Applied r = ShareStore.apply(this, s, mode);
                    String text = "已写入 " + r.written + " 项，保持不变 " + r.unchanged + " 项，跳过 " + r.skipped + " 项。";
                    if (!r.backupId.isEmpty()) text += "\n\n已自动备份，可在「历史」里撤销。";
                    if (!r.notes.isEmpty()) {
                        StringBuilder sb = new StringBuilder(text).append("\n\n");
                        for (int i = 0; i < Math.min(6, r.notes.size()); i++) sb.append("· ").append(r.notes.get(i)).append("\n");
                        text = sb.toString();
                    }
                    AlertDialog.Builder done = new AlertDialog.Builder(this)
                            .setTitle("导入完成")
                            .setMessage(text);
                    String first = s.presets.isEmpty() ? null : s.presets.keySet().iterator().next();
                    if (first != null) {
                        final String tool = first;
                        done.setPositiveButton("打开 " + PresetBridge.title(tool), (dl, wl) -> {
                            Intent it = PresetBridge.launchIntent(this, tool);
                            if (it != null) startActivity(it);
                        });
                        done.setNegativeButton("稍后", null);
                    } else {
                        done.setPositiveButton("好", null);
                    }
                    done.show();
                    pending = null;
                })
                .show();
    }

    // ------------------------------------------------------------ 历史

    private void showHistory() {
        selectTab(2);
        body.removeAllViews();
        JSONArray arr = ShareStore.history(this);
        if (arr.length() == 0) {
            body.addView(card("还没有记录", "生成或导入分享码后，这里会保留最近 20 条。"));
            return;
        }
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(round(CARD, LINE, 12));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            row.setLayoutParams(rp);

            String kind = "imp".equals(o.optString("kind")) ? "导入" : "导出";
            String nm = o.optString("name");
            if (nm.isEmpty()) nm = "未命名";
            row.addView(text(kind + " · " + nm, 15, TXT));
            String note = o.optString("note");
            if (!note.isEmpty()) row.addView(text("备注：" + note, 12, DIM));
            JSONArray tools = o.optJSONArray("tools");
            int tg = tools == null ? 0 : tools.length();
            row.addView(text(o.optInt("n") + " 项 · " + tg + " 组", 12, DIM));
            String bk = o.optString("bk");
            if (!bk.isEmpty()) {
                TextView undo = smallBtn("撤销这次导入");
                undo.setTextColor(GOLD);
                undo.setOnClickListener(v -> {
                    boolean ok = ShareStore.undo(this, bk);
                    Toast.makeText(this, ok ? "已恢复导入前的值" : "撤销失败：备份不存在", Toast.LENGTH_SHORT).show();
                    showHistory();
                });
                row.addView(undo);
            }
            body.addView(row);
        }
    }

    // ------------------------------------------------------------ 组件

    private TextView tab(String s, LinearLayout.LayoutParams lp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            if ("导出".equals(s)) showExport();
            else if ("导入".equals(s)) showImport();
            else showHistory();
        });
        return t;
    }

    private void selectTab(int i) {
        TextView[] ts = {tabExport, tabImport, tabHistory};
        for (int k = 0; k < ts.length; k++) {
            if (ts[k] == null) continue;
            boolean on = k == i;
            ts[k].setTextColor(on ? Color.WHITE : DIM);
            ts[k].setBackground(on ? round(0xFF23314A, ACCENT, 10) : round(CARD, LINE, 10));
        }
    }

    private TextView label(String s) {
        TextView t = text(s, 13, DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(5);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText input(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF6C7E9C);
        e.setTextColor(TXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(lines == 1);
        e.setBackground(round(CARD, LINE, 10));
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        return e;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private TextView smallBtn(String s) {
        TextView t = text(s, 12, ACCENT);
        t.setPadding(dp(10), dp(6), dp(6), dp(6));
        return t;
    }

    private Button primary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFF0B1424);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(round(GOLD, 0, 12));
        return b;
    }

    private View card(String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, LINE, 12));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(text(title, 15, TXT));
        c.addView(text(sub, 12, DIM));
        return c;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("设置分享码", s));
    }
}
