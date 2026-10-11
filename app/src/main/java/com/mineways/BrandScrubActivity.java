package com.mineways;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mineways.scrub.Mp4Scrubber;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 剪辑标识清除（工具箱）：把剪映 / 快影 / 必剪 写进视频元数据里的标识抹掉，让抖音 / 快手 /
 * B 站读不到「剪辑软件同款」的线索，不再挂广告入口。
 *
 * <p>不转码的盒子级清洗：命中元数据盒改成协议里的 {@code free}（读取方必须忽略）并清零载荷，
 * 落在 {@code hdlr}、压缩器名这类必需盒里的字段只抹字段本身。画面音频一个字节都不动，文件
 * 长度和所有绝对偏移保持不变，所以 {@code stco} 分块表、fMP4 的 {@code moof} 索引都不用重算。
 * 只有「写入自己的标识」在文件里没有同键条目时才会变长，那时由 {@link Mp4Scrubber} 统一搬移
 * 分块偏移并做结构自检。</p>
 *
 * <p>界面按「选文件 → 看清单 → 调范围 → 清除」排：文件卡折成一行，点开才铺清单；四个开关和
 * 写标识收在顶部那条可折叠的「清除范围」里；底部粘着计数与主按钮。</p>
 */
public class BrandScrubActivity extends Activity {

    private static final int REQ_PICK = 61;

    private static final int BG = 0xFF0E0F11;
    private static final int CARD = 0xFF15171A;
    private static final int CARD_HI = 0xFF1B1E22;
    private static final int LINE = 0x1FFFFFFF;
    private static final int TEXT = 0xFFECEFF1;
    private static final int DIM = 0xFF8C99A6;
    private static final int WARN = 0xFFE6A23C;
    private static final int OK = 0xFF8FE3B0;
    private static final int ACCENT = 0xFFD9603A;
    private static final int BLUE = 0xFF8FD3FF;

    /** 相册里的落点。 */
    private static final String ALBUM_DIR = Environment.DIRECTORY_MOVIES + "/剪辑标识清除";

    /** 自定义标识常用键，点一下换键，省得手打非 ASCII 的 ©。 */
    private static final String[] KEY_PRESETS = {"\u00a9too", "\u00a9cmt", "desc", "AIGC",
            "\u00a9nam", "\u00a9day"};

    private Switch swBrand;
    private Switch swLavf;
    private Switch swAigc;
    private Switch swFull;
    private TextView tvAigcNote;
    private LinearLayout settingsPanel;
    private TextView settingsToggle;
    private boolean openSettings = true;

    private EditText etKey;
    private EditText etText;
    private TextView tvKeyHint;
    private final List<TextView> keyChips = new ArrayList<TextView>();

    private LinearLayout filesBox;
    private View emptyView;
    private TextView tvStatus;
    private TextView tvCount;
    private Button btnGo;
    private boolean busy;

    private final List<Card> cards = new ArrayList<Card>();

    /** 一个文件一张卡：分析结果、逐条勾选、清除结果都挂在这里。 */
    private final class Card {
        String name;
        File src;
        boolean temp;
        Mp4Scrubber.Report rep;
        View view;
        TextView metaLine;
        TextView stateLine;
        TextView expander;
        LinearLayout detail;
        LinearLayout hitBox;
        LinearLayout otherBox;
        TextView otherToggle;
        LinearLayout resultBox;
        final List<CheckBox> boxes = new ArrayList<CheckBox>();
        final List<Mp4Scrubber.Entry> entries = new ArrayList<Mp4Scrubber.Entry>();
        boolean dirty;
        /** 程序在改勾选（按开关重置），这时不该把卡片标成「用户手动改过」。 */
        boolean applying;
        /** 0 = 待分析 / 分析中，1 = 已分析，2 = 分析失败。 */
        int state;
        boolean open;

        Set<String> picked() {
            final Set<String> set = new HashSet<String>();
            for (int i = 0; i < boxes.size(); i++) {
                if (((CheckBox) boxes.get(i)).isChecked()) {
                    set.add(((Mp4Scrubber.Entry) entries.get(i)).path);
                }
            }
            return set;
        }

        int checkedCount() {
            int n = 0;
            for (int i = 0; i < boxes.size(); i++) {
                if (((CheckBox) boxes.get(i)).isChecked()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 一个文件的产物。 */
    private static final class Done {
        File file;
        String name;
        String where;
    }

    /** 限制功能（激活码）：从 MainActivity 带进来的本次令牌，白名单账号进来时这两个是 null。 */
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
        setContentView(buildUi());
        refreshSettings();
        updateCounts();
    }

    // ------------------------------------------------------------------ 骨架

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(topBar());

        final ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        filesBox = new LinearLayout(this);
        filesBox.setOrientation(LinearLayout.VERTICAL);
        filesBox.setPadding(dp(14), dp(2), dp(14), dp(16));
        scroll.addView(filesBox);
        root.addView(scroll);

        filesBox.addView(settingsBlock());
        filesBox.addView(emptyCard());
        root.addView(bottomBar());
        return root;
    }

    private View topBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(14), dp(6));

        final TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(DIM);
        back.setTextSize(20f);
        back.setPadding(dp(6), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        final TextView title = new TextView(this);
        title.setText("剪辑标识清除");
        title.setTextColor(TEXT);
        title.setTextSize(19f);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView add = ghost("＋ 添加");
        add.setOnClickListener(v -> pick());
        bar.addView(add);
        return bar;
    }

    private View bottomBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(14), dp(8), dp(14), dp(12));

        tvStatus = new TextView(this);
        tvStatus.setTextColor(DIM);
        tvStatus.setTextSize(11.5f);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(tvStatus);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row.setLayoutParams(rlp);

        tvCount = new TextView(this);
        tvCount.setTextColor(TEXT);
        tvCount.setTextSize(13f);
        row.addView(tvCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button add = new Button(this);
        add.setText("添加视频");
        add.setAllCaps(false);
        add.setTextSize(12.5f);
        add.setTextColor(DIM);
        add.setBackground(round(CARD_HI, 10));
        add.setOnClickListener(v -> pick());
        row.addView(add, new LinearLayout.LayoutParams(dp(100), dp(46)));

        btnGo = new Button(this);
        btnGo.setText("开始清除");
        btnGo.setAllCaps(false);
        btnGo.setTextSize(14.5f);
        btnGo.setTextColor(0xFFFFFFFF);
        btnGo.setBackground(round(ACCENT, 10));
        btnGo.setEnabled(false);
        btnGo.setOnClickListener(v -> startScrub());
        final LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(128), dp(46));
        glp.leftMargin = dp(8);
        row.addView(btnGo, glp);

        bar.addView(row);
        return bar;
    }

    // ------------------------------------------------------------------ 清除范围

    private View settingsBlock() {
        final LinearLayout box = card(CARD, dp(8));

        settingsToggle = new TextView(this);
        settingsToggle.setTextColor(TEXT);
        settingsToggle.setTextSize(14f);
        settingsToggle.setPadding(0, dp(2), 0, dp(2));
        settingsToggle.setOnClickListener(v -> {
            openSettings = !openSettings;
            refreshSettings();
        });
        box.addView(settingsToggle);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        box.addView(settingsPanel);

        swBrand = addSwitch("剪辑软件品牌字样",
                "剪映 / 快影 / 必剪，含抖音、快手、B 站、CapCut 等同族词");
        swLavf = addSwitch("压制器指纹（Lavf 那一套）",
                "Lavf 版本号、Tencent CAPD MTS 署名、[s=][vb=][ab=] 转码参数注释、ffmpeg 默认 hdlr 名");
        swAigc = addSwitch("AI 生成合成（AIGC）标识",
                "默认关。命中的盒子按法规保留，勾了才清");
        swFull = addSwitch("彻底模式",
                "连创作备注、封面、章节、日期等普通元数据一起清，留下来的 hdlr 名字也抹平");
        swBrand.setChecked(true);
        swLavf.setChecked(true);

        tvAigcNote = new TextView(this);
        tvAigcNote.setText(Mp4Scrubber.AIGC_LEGAL_NOTE);
        tvAigcNote.setTextColor(WARN);
        tvAigcNote.setTextSize(11.5f);
        tvAigcNote.setLineSpacing(dp(3), 1f);
        tvAigcNote.setPadding(dp(2), dp(8), dp(2), dp(2));
        tvAigcNote.setVisibility(View.GONE);
        settingsPanel.addView(tvAigcNote);

        final CompoundButton.OnCheckedChangeListener tick = (b, checked) -> {
            tvAigcNote.setVisibility(swAigc.isChecked() ? View.VISIBLE : View.GONE);
            refreshSettings();
            applyAuto();
            repaintCards();
            updateCounts();
        };
        swBrand.setOnCheckedChangeListener(tick);
        swLavf.setOnCheckedChangeListener(tick);
        swAigc.setOnCheckedChangeListener(tick);
        swFull.setOnCheckedChangeListener(tick);

        settingsPanel.addView(writeBlock());
        return box;
    }

    private Switch addSwitch(String label, String hint) {
        final Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(13.5f);
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        s.setLayoutParams(p);
        settingsPanel.addView(s);
        final TextView t = new TextView(this);
        t.setText(hint);
        t.setTextColor(DIM);
        t.setTextSize(11.5f);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(2), dp(2), dp(2), 0);
        settingsPanel.addView(t);
        return s;
    }

    private View writeBlock() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD_HI, 10));
        box.setPadding(dp(12), dp(10), dp(12), dp(12));
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        box.setLayoutParams(p);

        final TextView head = new TextView(this);
        head.setText("写入自己的标识（可选）");
        head.setTextColor(TEXT);
        head.setTextSize(13.5f);
        box.addView(head);

        final TextView hint = new TextView(this);
        hint.setText("填了才写，写给所有选中文件。已有同一条目就原地改写（长度不变），没有才"
                + "新增并搬正分块偏移（文件长几十到几百字节）；分片 MP4 不能新增。");
        hint.setTextColor(DIM);
        hint.setTextSize(11.5f);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(4), 0, dp(8));
        box.addView(hint);

        final HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < KEY_PRESETS.length; i++) {
            chips.addView(keyChip(KEY_PRESETS[i]));
        }
        hsv.addView(chips);
        box.addView(hsv);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        etKey = new EditText(this);
        etKey.setText(KEY_PRESETS[0]);
        etKey.setTextColor(TEXT);
        etKey.setHintTextColor(DIM);
        etKey.setTextSize(13f);
        etKey.setSingleLine(true);
        etKey.setBackground(round(BG, 8));
        etKey.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(etKey, new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        etText = new EditText(this);
        etText.setHint("文字，例如 由 Mineways 制作");
        etText.setTextColor(TEXT);
        etText.setHintTextColor(DIM);
        etText.setTextSize(13f);
        etText.setSingleLine(true);
        etText.setBackground(round(BG, 8));
        etText.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(etText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        tvKeyHint = new TextView(this);
        tvKeyHint.setTextSize(11.5f);
        tvKeyHint.setPadding(dp(2), dp(6), dp(2), 0);
        box.addView(tvKeyHint);

        final TextWatcher tw = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                syncWrite();
                updateCounts();
            }
        };
        etKey.addTextChangedListener(tw);
        etText.addTextChangedListener(tw);
        syncWrite();
        return box;
    }

    private TextView keyChip(final String key) {
        final TextView t = new TextView(this);
        t.setText(key);
        t.setTextSize(12.5f);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        t.setOnClickListener(v -> {
            etKey.setText(key);
            etKey.setSelection(key.length());
        });
        keyChips.add(t);
        return t;
    }

    private void syncWrite() {
        final String key = trim(etKey.getText().toString());
        final String text = trim(etText.getText().toString());
        for (int i = 0; i < keyChips.size(); i++) {
            final TextView t = (TextView) keyChips.get(i);
            final boolean on = KEY_PRESETS[i].equals(key);
            t.setTextColor(on ? 0xFFFFFFFF : DIM);
            t.setBackground(round(on ? ACCENT : BG, 8));
        }
        if (text.length() == 0) {
            tvKeyHint.setText("不填文字就不写入，只清除。");
            tvKeyHint.setTextColor(DIM);
        } else if (key.length() != 4) {
            tvKeyHint.setText("键必须正好 4 个字符（现在 " + key.length() + " 个），不会写入。");
            tvKeyHint.setTextColor(WARN);
        } else {
            tvKeyHint.setText("将写入：" + key + " = 「" + cut(text) + "」");
            tvKeyHint.setTextColor(BLUE);
        }
    }

    /** 折叠行：把四个开关压成一句话，展开才看到开关本体。 */
    private void refreshSettings() {
        final StringBuilder sb = new StringBuilder();
        sb.append(openSettings ? "▾" : "▸").append("  清除范围：");
        if (flags() == 0) {
            sb.append("全没勾（不清任何东西）");
        } else if (swFull.isChecked()) {
            sb.append("彻底模式 · 所有元数据");
            if (swAigc.isChecked()) {
                sb.append(" ＋ AIGC");
            }
        } else {
            final List parts = new ArrayList();
            if (swBrand.isChecked()) {
                parts.add("品牌字样");
            }
            if (swLavf.isChecked()) {
                parts.add("压制器指纹");
            }
            if (swAigc.isChecked()) {
                parts.add("AIGC 标识");
            }
            sb.append(join(parts, " · "));
        }
        settingsToggle.setText(sb.toString());
        settingsPanel.setVisibility(openSettings ? View.VISIBLE : View.GONE);
    }

    private static String join(List list, String sep) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 空状态

    private View emptyCard() {
        final LinearLayout box = card(CARD, dp(10));

        final TextView t = new TextView(this);
        t.setText("还没有选视频");
        t.setTextColor(TEXT);
        t.setTextSize(15f);
        box.addView(t);

        final TextView s = new TextView(this);
        s.setText("抹掉剪辑软件写进视频元数据的标识：不转码，画面音频一个字节都不动，产物另存到"
                + "相册（" + ALBUM_DIR + "），原件不改。\n"
                + "支持 .mp4 / .mov，可多选。烧进画面的片尾 logo、平台侧的内容指纹不属于元数据，"
                + "处理不了。");
        s.setTextColor(DIM);
        s.setTextSize(12f);
        s.setLineSpacing(dp(3), 1f);
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        s.setLayoutParams(slp);
        box.addView(s);

        final Button go = new Button(this);
        go.setText("选择视频");
        go.setAllCaps(false);
        go.setTextSize(14.5f);
        go.setTextColor(0xFFFFFFFF);
        go.setBackground(round(ACCENT, 10));
        go.setOnClickListener(v -> pick());
        final LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        glp.topMargin = dp(12);
        go.setLayoutParams(glp);
        box.addView(go);

        emptyView = box;
        return box;
    }

    private void updateEmpty() {
        emptyView.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ 文件卡

    private LinearLayout card(int color, int topMarginDp) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(color, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMarginDp;
        box.setLayoutParams(p);
        return box;
    }

    private View buildCard(final Card cd) {
        final LinearLayout box = card(CARD, dp(10));

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setOnClickListener(v -> setOpen(cd, !cd.open));

        final TextView name = new TextView(this);
        name.setText(cd.name);
        name.setTextColor(TEXT);
        name.setTextSize(14f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name);

        cd.metaLine = new TextView(this);
        cd.metaLine.setText("正在读取…");
        cd.metaLine.setTextColor(DIM);
        cd.metaLine.setTextSize(11.5f);
        cd.metaLine.setPadding(0, dp(3), 0, 0);
        head.addView(cd.metaLine);

        cd.expander = new TextView(this);
        cd.expander.setTextColor(ACCENT);
        cd.expander.setTextSize(12.5f);
        cd.expander.setPadding(0, dp(8), 0, 0);
        head.addView(cd.expander);
        box.addView(head);

        cd.stateLine = new TextView(this);
        cd.stateLine.setTextColor(DIM);
        cd.stateLine.setTextSize(11.5f);
        cd.stateLine.setLineSpacing(dp(2), 1f);
        cd.stateLine.setPadding(0, dp(6), 0, 0);
        box.addView(cd.stateLine);

        cd.detail = new LinearLayout(this);
        cd.detail.setOrientation(LinearLayout.VERTICAL);
        box.addView(cd.detail);

        cd.resultBox = new LinearLayout(this);
        cd.resultBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(cd.resultBox);

        cd.view = box;
        setOpen(cd, false);
        return box;
    }

    private void setOpen(Card cd, boolean open) {
        cd.open = open;
        cd.detail.setVisibility(open ? View.VISIBLE : View.GONE);
        repaintHead(cd);
    }

    /** 卡片头那几行小字：文件多大、命中多少、点开还是收起。 */
    private void repaintHead(Card cd) {
        final Mp4Scrubber.Report r = cd.rep;
        if (cd.state == 2) {
            cd.expander.setText("读不了，点开看原因 ▾");
            return;
        }
        if (r == null) {
            cd.expander.setText(cd.open ? "收起 ▴" : "展开清单 ▾");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(group(r.fileSize)).append(" 字节　容器 ").append(r.topLevel);
        if (r.fragmented) {
            sb.append("　分片 MP4：不能新增标识");
        }
        if (r.truncated) {
            sb.append("　盒长超过文件末尾，文件可能本身就不完整");
        }
        cd.metaLine.setText(sb.toString());
        cd.expander.setText(cd.open ? "收起 ▴"
                : "清单：命中 " + r.hitCount + " / " + r.entries.size() + " 条，点开 ▾");
    }

    private void repaintCards() {
        for (int i = 0; i < cards.size(); i++) {
            repaintHead((Card) cards.get(i));
        }
    }

    private void fill(Card cd, int f) {
        cd.detail.removeAllViews();
        cd.boxes.clear();
        cd.detail.addView(cardTools(cd));

        cd.hitBox = new LinearLayout(this);
        cd.hitBox.setOrientation(LinearLayout.VERTICAL);
        cd.otherBox = new LinearLayout(this);
        cd.otherBox.setOrientation(LinearLayout.VERTICAL);

        int hits = 0;
        int others = 0;
        for (int i = 0; i < cd.entries.size(); i++) {
            final Mp4Scrubber.Entry en = (Mp4Scrubber.Entry) cd.entries.get(i);
            final View row = entryRow(cd, en, f);
            if (en.why != 0) {
                cd.hitBox.addView(row);
                hits++;
            } else {
                cd.otherBox.addView(row);
                others++;
            }
        }
        cd.detail.addView(label(hits == 0
                ? "没检出剪辑软件标识，下面是文件里现有的元数据"
                : "检出的标识（" + hits + "）· 勾上的才清", hits == 0 ? DIM : OK));
        cd.detail.addView(cd.hitBox);
        if (others > 0) {
            final String tail = "其他元数据（" + others + "）· 默认不动";
            cd.otherToggle = new TextView(this);
            cd.otherToggle.setTextColor(DIM);
            cd.otherToggle.setTextSize(12f);
            cd.otherToggle.setPadding(0, dp(12), 0, dp(2));
            cd.otherToggle.setOnClickListener(v -> {
                final boolean show = cd.otherBox.getVisibility() != View.VISIBLE;
                cd.otherBox.setVisibility(show ? View.VISIBLE : View.GONE);
                cd.otherToggle.setText((show ? "▾  " : "▸  ") + tail);
            });
            cd.detail.addView(cd.otherToggle);
            cd.otherBox.setVisibility(hits == 0 ? View.VISIBLE : View.GONE);
            cd.otherToggle.setText((hits == 0 ? "▾  " : "▸  ") + tail);
            cd.detail.addView(cd.otherBox);
        }
    }

    private TextView label(String s, int color) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(11.5f);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private View cardTools(final Card cd) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tool("按开关重选", v -> {
            resetTo(cd, flags());
            repaintHead(cd);
            updateCounts();
        }));
        row.addView(tool("全选", v -> setAll(cd, true)));
        row.addView(tool("全不勾", v -> setAll(cd, false)));
        return row;
    }

    private void setAll(Card cd, boolean on) {
        cd.applying = true;
        for (int i = 0; i < cd.boxes.size(); i++) {
            ((CheckBox) cd.boxes.get(i)).setChecked(on);
        }
        cd.applying = false;
        cd.dirty = true;
        updateCounts();
    }

    private TextView tool(String name, View.OnClickListener go) {
        final TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(ACCENT);
        t.setTextSize(12f);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(round(CARD_HI, 8));
        t.setOnClickListener(go);
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    private View entryRow(final Card cd, final Mp4Scrubber.Entry en, int f) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        final CheckBox cb = new CheckBox(this);
        cb.setText("");
        cb.setChecked(auto(en, f));
        cb.setOnCheckedChangeListener((b, checked) -> {
            if (!cd.applying) {
                cd.dirty = true;
            }
            updateCounts();
        });
        row.addView(cb);

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(line(en.key, en.why == 0 ? DIM : TEXT, 13f));
        if (en.value != null && en.value.length() > 0) {
            col.addView(line(cut(en.value), DIM, 11.5f));
        }
        col.addView(line(en.note.length() == 0 ? en.path : en.note,
                en.aigc ? WARN : DIM, 11f));
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tag = new TextView(this);
        tag.setText(en.removable ? "整盒作废" : "只抹字段");
        tag.setTextColor(en.why == 0 ? DIM : BLUE);
        tag.setTextSize(11f);
        tag.setPadding(dp(8), 0, 0, 0);
        row.addView(tag);

        row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        cd.boxes.add(cb);
        return row;
    }

    private TextView line(String s, int color, float sp) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(2), dp(4), 0);
        return t;
    }

    // ------------------------------------------------------------------ 开关 -> 默认勾选

    private int flags() {
        int f = 0;
        if (swBrand.isChecked()) {
            f |= Mp4Scrubber.FLAG_BRAND;
        }
        if (swLavf.isChecked()) {
            f |= Mp4Scrubber.FLAG_LAVF;
        }
        if (swAigc.isChecked()) {
            f |= Mp4Scrubber.FLAG_AIGC;
        }
        if (swFull.isChecked()) {
            f |= Mp4Scrubber.FLAG_FULL;
        }
        return f;
    }

    /**
     * 这条按当前开关该不该默认勾上 —— 和核心 {@code decideAction} 保持一致。
     *
     * <p>AIGC 的判定要排在彻底模式前面：开了彻底模式但没勾 AIGC 开关时，核心是按法规保留那些
     * 盒子的，界面把它们显示成「已勾＝会被清」就是骗人。</p>
     */
    private boolean auto(Mp4Scrubber.Entry en, int f) {
        if (en.aigc && (f & Mp4Scrubber.FLAG_AIGC) == 0) {
            return false;
        }
        if ((f & Mp4Scrubber.FLAG_FULL) != 0) {
            return true;
        }
        if ((en.why & Mp4Scrubber.FLAG_BRAND) != 0 && (f & Mp4Scrubber.FLAG_BRAND) != 0) {
            return true;
        }
        if ((en.why & Mp4Scrubber.FLAG_LAVF) != 0 && (f & Mp4Scrubber.FLAG_LAVF) != 0) {
            return true;
        }
        return (en.why & Mp4Scrubber.FLAG_AIGC) != 0 && (f & Mp4Scrubber.FLAG_AIGC) != 0;
    }

    /** 开关变了只重排没手动改过的卡片；改过的保持用户自己的勾选。 */
    private void applyAuto() {
        final int f = flags();
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            if (!cd.dirty) {
                resetTo(cd, f);
            }
        }
    }

    /** 把一张卡片的勾选恢复成「由开关决定」的样子，并清掉手动标记。 */
    private void resetTo(Card cd, int f) {
        cd.dirty = false;
        cd.applying = true;
        for (int i = 0; i < cd.boxes.size(); i++) {
            ((CheckBox) cd.boxes.get(i)).setChecked(
                    auto((Mp4Scrubber.Entry) cd.entries.get(i), f));
        }
        cd.applying = false;
    }

    private void updateCounts() {
        int checked = 0;
        int hits = 0;
        int ready = 0;
        int manual = 0;
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            if (cd.state != 1 || cd.rep == null) {
                continue;
            }
            ready++;
            hits += cd.rep.hitCount;
            checked += cd.checkedCount();
            if (cd.dirty) {
                manual++;
            }
        }
        final StringBuilder sb = new StringBuilder();
        if (ready == 0) {
            sb.append("先添加视频");
        } else {
            sb.append("已勾 ").append(checked).append(" 处　").append(ready).append(" 个文件")
                    .append("　检出 ").append(hits).append(" 处");
            if (manual > 0) {
                sb.append("　手动改过 ").append(manual).append(" 个文件");
            }
        }
        final String note = writeNote();
        if (note != null) {
            sb.append("　").append(note);
        }
        tvCount.setText(sb.toString());
        btnGo.setEnabled(ready > 0 && !busy && (checked > 0 || writeActive()));
    }

    /** 写标识是否成立（文字非空且键够 4 字节）。 */
    private boolean writeActive() {
        return trim(etText.getText().toString()).length() > 0
                && trim(etKey.getText().toString()).length() == 4;
    }

    private String writeNote() {
        if (trim(etText.getText().toString()).length() == 0) {
            return null;
        }
        return writeActive() ? "并写入标识" : "标识键要 4 个字符，暂不写入";
    }

    // ------------------------------------------------------------------ 选文件 + 分析

    private void pick() {
        final Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("video/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("这台设备没有可用的文件选择器：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        final List uris = new ArrayList();
        final android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        clearCards();
        for (int i = 0; i < uris.size(); i++) {
            final Card cd = new Card();
            cd.name = nameOf((Uri) uris.get(i));
            cards.add(cd);
            filesBox.addView(buildCard(cd));
        }
        updateEmpty();
        openSettings = false;
        refreshSettings();
        setStatus("正在读取 " + uris.size() + " 个文件…");
        lock(true, null);
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < uris.size(); i++) {
                    analyze(i, (Uri) uris.get(i));
                }
                summarize();
                lock(false, null);
            }
        }).start();
    }

    /** 落地成能随机读的文件，再把每条元数据铺成一行勾选。 */
    private void analyze(final int index, final Uri uri) {
        final Card cd = (Card) cards.get(index);
        try {
            final File[] got = materialize(uri, cd.name);
            cd.src = got[0];
            cd.temp = got[1] != null;
            final Mp4Scrubber.Report r = Mp4Scrubber.analyze(got[0]);
            cd.rep = r;
            cd.entries.addAll(r.entries);
            cd.state = 1;
            runOnUiThread(new Runnable() {
                public void run() {
                    fill(cd, flags());
                    setOpen(cd, cards.size() == 1);
                    updateCounts();
                }
            });
        } catch (Throwable e) {
            cd.state = 2;
            runOnUiThread(new Runnable() {
                public void run() {
                    cd.stateLine.setTextColor(WARN);
                    cd.stateLine.setText("读不了：" + e);
                    repaintHead(cd);
                    updateCounts();
                }
            });
        }
    }

    /** 全部分析完，底部一句总结。 */
    private void summarize() {
        int hits = 0;
        int aigc = 0;
        int files = 0;
        int failed = 0;
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            if (cd.state == 2) {
                failed++;
            } else if (cd.state == 1 && cd.rep != null) {
                files++;
                hits += cd.rep.hitCount;
                aigc += cd.rep.aigcCount;
            }
        }
        final StringBuilder sb = new StringBuilder();
        if (files == 0) {
            sb.append("这几个文件都读不了，换一次试试。");
        } else if (hits == 0) {
            sb.append(files).append(" 个文件里没有剪辑软件标识，清了也不会变什么。");
        } else {
            sb.append(files).append(" 个文件、检出 ").append(hits).append(" 处标识。")
                    .append("点「清单」逐条改勾选，产物写进相册，原件不动。");
            if (aigc > 0) {
                sb.append("其中 ").append(aigc).append(" 处 AIGC 标识默认保留。");
            }
        }
        if (failed > 0) {
            sb.append("　").append(failed).append(" 个读不了。");
        }
        final String s = sb.toString();
        runOnUiThread(new Runnable() {
            public void run() {
                setStatus(s);
                updateCounts();
            }
        });
    }

    // ------------------------------------------------------------------ 清除

    private void startScrub() {
        final int f = flags();
        if (f == 0 && checkedTotal() == 0 && !writeActive()) {
            toast("开关全没勾、条目全没选，没有要处理的东西。");
            return;
        }
        final String text = trim(etText.getText().toString());
        final String key = trim(etKey.getText().toString());
        if (text.length() > 0 && key.length() != 4) {
            toast("自定义标识的键必须是 4 个字符（例如 ©too、desc、AIGC）。");
            return;
        }
        final List<Pref> prefs = new ArrayList<Pref>();
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            final Pref pf = new Pref();
            pf.picked = cd.dirty ? cd.picked() : null;
            pf.write = text.length() > 0 ? new Mp4Scrubber.Write(key, text) : null;
            prefs.add(pf);
        }
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            cd.resultBox.removeAllViews();
            cd.stateLine.setText("");
            cd.stateLine.setTextColor(DIM);
        }
        lock(true, "正在清除…");
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < cards.size(); i++) {
                    if (((Card) cards.get(i)).state == 1) {
                        runOne(i, f, (Pref) prefs.get(i));
                    }
                }
                lock(false, null);
            }
        }).start();
    }

    private int checkedTotal() {
        int n = 0;
        for (int i = 0; i < cards.size(); i++) {
            n += ((Card) cards.get(i)).checkedCount();
        }
        return n;
    }

    private static final class Pref {
        Set picked;
        Mp4Scrubber.Write write;
    }

    private void runOne(final int index, int f, Pref pf) {
        final Card cd = (Card) cards.get(index);
        final Mp4Scrubber.Options o = new Mp4Scrubber.Options();
        o.flags = f;
        o.picked = pf.picked;
        if (pf.write != null) {
            o.writes.add(pf.write);
        }
        setStatus(cd, "处理中…");
        File dst = null;
        try {
            dst = new File(workDir(), outName(cd.name));
            final Mp4Scrubber.Progress cb = text -> setStatus(cd, text);
            final Mp4Scrubber.Result r = Mp4Scrubber.scrub(cd.src, dst, o, cb);
            if (!r.ok) {
                dst.delete();
                finishFail(cd, r.message);
                return;
            }
            final String where = publish(dst, outName(cd.name));
            if (where == null) {
                finishFail(cd, r.message + "\n相册没写进去，产物留在：" + dst.getAbsolutePath());
                return;
            }
            final Done dn = new Done();
            dn.file = dst;
            dn.name = outName(cd.name);
            dn.where = where;
            // 写进相册才算真用掉一次（多文件时只核销一次，后面的靠 activationDone 挡住）
            finishActivation(true);
            finishOk(cd, detail(r), dn, r.writeSkipped);
        } catch (Throwable e) {
            if (dst != null) {
                dst.delete();
            }
            finishFail(cd, "失败：" + e);
        }
    }

    private static String detail(Mp4Scrubber.Result r) {
        final StringBuilder sb = new StringBuilder();
        sb.append("作废 ").append(r.freeCount).append(" 盒 · 抹字段 ").append(r.blankCount)
                .append(" 处");
        if (r.writeCount > 0) {
            sb.append(" · 写入 ").append(r.writeCount).append(" 条");
        }
        if (r.aigcProtected > 0) {
            sb.append(" · 按法规保留 AIGC ").append(r.aigcProtected).append(" 处");
        }
        sb.append(" · 字节 ").append(group(r.bytes));
        if (r.delta != 0) {
            sb.append(r.delta > 0 ? "（ +" : "（ ").append(Math.abs(r.delta)).append("）");
        }
        return sb.toString();
    }

    private void finishOk(final Card cd, final String stat, final Done dn, final String skipped) {
        runOnUiThread(new Runnable() {
            public void run() {
                cd.stateLine.setTextColor(OK);
                cd.stateLine.setText("✓ " + stat);
                final LinearLayout row = new LinearLayout(cd.stateLine.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                final TextView name = new TextView(cd.stateLine.getContext());
                name.setText(dn.name);
                name.setTextColor(TEXT);
                name.setTextSize(13f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                final Button share = new Button(cd.stateLine.getContext());
                share.setText("分享");
                share.setAllCaps(false);
                share.setTextSize(12f);
                share.setTextColor(ACCENT);
                share.setBackground(round(CARD_HI, 8));
                share.setOnClickListener(v -> shareFile(dn.file));
                row.addView(share, new LinearLayout.LayoutParams(dp(72), dp(40)));
                cd.resultBox.addView(row);
                plain(cd.resultBox, dn.where, DIM);
                if (skipped != null && skipped.length() > 0) {
                    plain(cd.resultBox, skipped, WARN);
                }
                updateCounts();
            }
        });
    }

    private void finishFail(final Card cd, final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                cd.stateLine.setTextColor(WARN);
                cd.stateLine.setText("没清成");
                plain(cd.resultBox, text, WARN);
                updateCounts();
            }
        });
    }

    private void plain(LinearLayout box, String text, int color) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(11.5f);
        t.setLineSpacing(dp(2), 1f);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(6), 0, dp(2));
        box.addView(t);
    }

    private void setStatus(final Card cd, final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                cd.stateLine.setText(text);
            }
        });
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                tvStatus.setText(text);
            }
        });
    }

    // ------------------------------------------------------------------ 输入落地

    /**
     * 能还原出真实路径（有「所有文件访问」权限时多数情况可以）就直接用原文件，省掉一次整文件
     * 复制；还原不出来才复制进应用目录。
     *
     * @return [0] 可读文件；[1] 复制出来的临时文件（没复制时为 null）
     */
    private File[] materialize(Uri uri, String name) throws Exception {
        final File real = realPath(uri);
        if (real != null && real.canRead()) {
            return new File[]{real, null};
        }
        final File tmp = new File(workDir(), "in-" + name);
        final InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("打不开输入流");
        }
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            pump(in, os);
        } finally {
            close(in);
            close(os);
        }
        return new File[]{tmp, tmp};
    }

    private String outName(String name) {
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : ".mp4";
        return base + "-无标识" + ext;
    }

    private File workDir() {
        final File dir = new File(getExternalFilesDir(null), "scrub");
        dir.mkdirs();
        return dir;
    }

    private void clearCards() {
        for (int i = 0; i < cards.size(); i++) {
            final Card cd = (Card) cards.get(i);
            if (cd.temp && cd.src != null) {
                cd.src.delete();
            }
            if (cd.view != null) {
                filesBox.removeView(cd.view);
            }
        }
        cards.clear();
        updateEmpty();
    }

    @Override
    protected void onDestroy() {
        finishActivation(false);   // 没成功就退出 → release，不扣次
        clearCards();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 输入路径还原
    /** 从 Uri 里还原真实文件路径；还原不出来返回 null。 */
    private File realPath(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                return new File(uri.getPath());
            }
            final File byDoc = docPath(uri);
            if (byDoc != null && byDoc.isFile()) {
                return byDoc;
            }
            final String col = MediaStore.MediaColumns.DATA;
            final Cursor c = getContentResolver().query(uri, new String[]{col}, null, null, null);
            try {
                if (c != null && c.moveToFirst()) {
                    final int i = c.getColumnIndex(col);
                    if (i >= 0) {
                        final String p = c.getString(i);
                        if (p != null && p.length() > 0) {
                            final File f = new File(p);
                            if (f.isFile()) {
                                return f;
                            }
                        }
                    }
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** SAF 的 externalstorage 文档 Uri 里带的就是相对公共存储的路径，直接拼出来。 */
    private File docPath(Uri uri) {
        if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) {
            return null;
        }
        final String seg;
        try {
            seg = android.provider.DocumentsContract.getDocumentId(uri);
        } catch (Throwable t) {
            return null;   // 不是文档 Uri（例如 content://media/…）
        }
        if (seg == null || !seg.startsWith("primary:")) {
            return null;
        }
        final String rel = Uri.decode(seg.substring("primary:".length()));
        return new File(Environment.getExternalStorageDirectory(), rel);
    }

    // ------------------------------------------------------------------ 存相册
    private String publish(File src, String name) {
        final String mime = name.toLowerCase(Locale.ROOT).endsWith(".mov")
                ? "video/quicktime" : "video/mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                final ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_DIR);
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                final Uri uri = getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    return null;
                }
                final OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    return null;
                }
                InputStream in = null;
                try {
                    in = new FileInputStream(src);
                    pump(in, os);
                } finally {
                    close(os);
                    if (in != null) {
                        close(in);
                    }
                }
                final ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return "相册 · " + ALBUM_DIR;
            } catch (Throwable ignored) {
                // 相册写不进去就退回应用目录，别让成果丢了
            }
        }
        try {
            final File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "剪辑标识清除");
            dir.mkdirs();
            final File to = new File(dir, name);
            final InputStream in = new FileInputStream(src);
            OutputStream os = null;
            try {
                os = new FileOutputStream(to);
                pump(in, os);
            } finally {
                close(in);
                close(os);
            }
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(to)));
            return "相册 · " + dir.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void shareFile(File f) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            final Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享清洗后的视频"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage() + "\n（产物在 " + f.getAbsolutePath() + "）");
        }
    }

    // ------------------------------------------------------------------ 小工具
    private void lock(final boolean busyNow, final String msg) {
        busy = busyNow;
        runOnUiThread(new Runnable() {
            public void run() {
                if (msg != null) {
                    tvStatus.setText(msg);
                }
                updateCounts();
            }
        });
    }

    private TextView ghost(String name) {
        final TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(ACCENT);
        t.setTextSize(13f);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(round(CARD_HI, 10));
        return t;
    }

    private void pump(InputStream in, OutputStream out) throws Exception {
        final byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private String nameOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
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
        return p == null || p.length() == 0 ? "video.mp4" : p;
    }

    private static String cut(String s) {
        final String v = s == null ? "" : s;
        return v.length() <= 58 ? v : v.substring(0, 58) + "…";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String group(long v) {
        return String.format(Locale.ROOT, "%,d", Long.valueOf(v));
    }

    private static void close(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), LINE);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
