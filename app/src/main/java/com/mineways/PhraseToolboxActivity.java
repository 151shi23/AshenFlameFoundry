package com.mineways;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextWatcher;
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

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 串词工具箱（纯本地）。
 *
 * <ul>
 *   <li>内置一堆串词（{@code assets/phrase/phrases.json}），可在应用内新增 / 编辑 / 隐藏；</li>
 *   <li>复制串词时可一键把词换成谐音写法（{@code assets/phrase/homophones.json} + 自定义）；</li>
 *   <li>一键把串词渲染成图片表情包：存进相册并直接拉起分享，发到聊天软件就能用。</li>
 * </ul>
 */
public class PhraseToolboxActivity extends Activity {

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int FIELD = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    private LinearLayout listCol;
    private EditText etSearch;
    private CheckBox cbHomo;
    private Button btnHomoMode;
    private TextView tvStatus;

    private String keyword = "";

    /** 受限功能的记账：主界面带过来的 token，用掉一次就 confirm，没用上就 release。 */
    private String activationToken;
    private String activationFeature;
    private boolean activationDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent from = getIntent();
        activationToken = from == null ? null : from.getStringExtra("activation_token");
        activationFeature = from == null ? null : from.getStringExtra("activation_feature");
        setContentView(buildRoot());
        reload();
    }

    /** 完成一次实际使用（复制 / 出图 / 导入成功）→ 扣 1 次；进来啥也没干就退出 → 不扣。 */
    private void finishActivation(boolean success) {
        if (activationDone || activationToken == null || activationToken.length() == 0) {
            return;
        }
        activationDone = true;
        ActivationDialog.finish(this, activationFeature, activationToken, success);
    }

    @Override
    protected void onDestroy() {
        finishActivation(false);    // 没确认就退出 → release，不扣次
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextColor(DIM);
        back.setTextSize(13);
        back.setPadding(0, dp(4), dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = new TextView(this);
        title.setText("串词工具箱");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("点卡片=复制 · 开谐音就换成谐音写法 · 存表情包=出图直接发\n"
                + "支持导入外部 txt 串词 / 谐音表 · v" + BuildConfig.VERSION_NAME);
        sub.setTextColor(DIM);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        root.addView(sub);

        etSearch = new EditText(this);
        etSearch.setHint("搜串词（点卡片复制，或点「随机来一条」）");
        etSearch.setHintTextColor(0xFF5C6268);
        etSearch.setTextColor(TEXT);
        etSearch.setTextSize(13.5f);
        etSearch.setSingleLine(true);
        etSearch.setBackground(round(FIELD, 10));
        etSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s == null ? "" : s.toString().trim();
                reload();
            }
        });
        root.addView(etSearch);

        LinearLayout act = new LinearLayout(this);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.setGravity(Gravity.CENTER_VERTICAL);
        act.setPadding(0, dp(12), 0, 0);
        cbHomo = new CheckBox(this);
        cbHomo.setText("谐音替换");
        cbHomo.setTextColor(TEXT);
        cbHomo.setTextSize(12.5f);
        cbHomo.setChecked(true);
        cbHomo.setPadding(0, dp(8), 0, dp(8));
        cbHomo.setOnCheckedChangeListener((v, b) -> reload());
        act.addView(cbHomo);
        // 按钮变多了，窄屏就横滑，别把「导入」挤出屏幕。
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btnHomoMode = smallBtn(homoModeLabel());
        // 点一下就换档（全部 → 1~2 → 1~3 → 1~5 → 全部…），不弹框；长按才给自定义
        btnHomoMode.setOnClickListener(v -> cycleHomoMode());
        btnHomoMode.setOnLongClickListener(v -> {
            showHomoModeDialog();
            return true;
        });
        btns.addView(btnHomoMode);
        Button btnRandom = smallBtn("随机来一条");
        btnRandom.setOnClickListener(v -> copyRandom());
        btns.addView(btnRandom);
        Button btnHomo = smallBtn("谐音表");
        btnHomo.setOnClickListener(v -> showHomoDialog());
        btns.addView(btnHomo);
        Button btnAdd = smallBtn("＋ 新增");
        btnAdd.setOnClickListener(v -> showEditDialog(null));
        btns.addView(btnAdd);
        Button btnImport = smallBtn("⇩ 导入 txt");
        // 点一下直接拉系统文件选择器（自动认类型），不弹框；长按才给剪贴板 / 扫文件夹等入口
        btnImport.setOnClickListener(v -> quickImport());
        btnImport.setOnLongClickListener(v -> {
            showImportMenu();
            return true;
        });
        btns.addView(btnImport);
        scroller.addView(btns);
        act.addView(scroller, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(act);

        ScrollView sv = new ScrollView(this);
        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.setPadding(0, dp(10), 0, dp(12));
        sv.addView(listCol);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvStatus = new TextView(this);
        tvStatus.setTextColor(ACCENT);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(tvStatus);
        return root;
    }

    /** 重新渲染列表：不分分类，直接搜，或者点「随机来一条」。 */
    private void reload() {
        listCol.removeAllViews();
        List<PhraseStore.Item> all = PhraseStore.list(this);
        int shown = 0;
        for (PhraseStore.Item it : all) {
            if (keyword.length() > 0 && !it.text.contains(keyword)) {
                continue;
            }
            listCol.addView(card(it));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(all.isEmpty()
                    ? "这里还没内容。点「＋ 新增」自己写一条。"
                    : "没搜到，换个词试试。");
            empty.setTextColor(DIM);
            empty.setTextSize(13);
            empty.setPadding(0, dp(18), 0, 0);
            listCol.addView(empty);
        }
        status("共 " + shown + " 条"
                + (cbHomo.isChecked() ? " · 复制时换谐音（" + homoModeLabel() + "）" : ""));
    }

    private View card(PhraseStore.Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14));
        c.setPadding(dp(14), dp(12), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);

        if (!it.builtin) {
            TextView tag = new TextView(this);
            tag.setText("我的");
            tag.setTextSize(10.5f);
            tag.setTextColor(ACCENT);
            c.addView(tag);
        }

        TextView body = new TextView(this);
        body.setText(it.text);
        body.setTextColor(TEXT);
        body.setTextSize(14.5f);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(6), 0, dp(8));
        c.addView(body);

        if (cbHomo.isChecked()) {
            String h = homoText(it.text);
            if (!h.equals(it.text)) {
                TextView pv = new TextView(this);
                pv.setText("谐音（" + homoModeLabel() + "）：" + h);
                pv.setTextColor(DIM);
                pv.setTextSize(12);
                pv.setPadding(0, 0, 0, dp(8));
                c.addView(pv);
            }
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = smallBtn("复制");
        copy.setOnClickListener(v -> copyItem(it));
        row.addView(copy);
        Button img = smallBtn("存表情包");
        img.setOnClickListener(v -> saveSticker(it));
        row.addView(img);
        c.addView(row);

        c.setOnClickListener(v -> copyItem(it));
        c.setOnLongClickListener(v -> {
            showItemMenu(it);
            return true;
        });
        return c;
    }

    // ------------------------------------------------------------------ 复制 / 存图

    private void copyItem(PhraseStore.Item it) {
        boolean homo = cbHomo.isChecked();
        String out = homoText(it.text);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("串词", out));
        }
        status((homo ? "已复制（谐音·" + homoModeLabel() + "）：" : "已复制：") + shortText(out));
        finishActivation(true);     // 复制成功即算用掉一次
    }

    private void saveSticker(PhraseStore.Item it) {
        String text = homoText(it.text);
        Bitmap bmp;
        try {
            bmp = renderSticker(text);
        } catch (Throwable t) {
            status("出图失败：" + t.getMessage());
            return;
        }
        Uri uri = saveImage(bmp);
        if (uri == null) {
            status("存图失败（可能是没给存储权限）");
            return;
        }
        status("已出图：" + bmp.getWidth() + "×" + bmp.getHeight()
                + "，相册 Pictures/串词表情包（老系统退到应用目录）");
        finishActivation(true);     // 出图成功算用掉一次
        shareImage(uri);
    }

    /** 把串词画成一张白底图片：字号自适应，一行行折行。 */
    private Bitmap renderSticker(String text) {
        final int width = 1080;
        final int pad = 84;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setColor(0xFF16181C);

        float size = 76f;
        List<String> lines = null;
        for (int i = 0; i < 6; i++) {
            paint.setTextSize(size);
            lines = wrap(text, paint, width - pad * 2f);
            if (lines.size() <= 14) {
                break;
            }
            size -= 8f;
        }
        if (lines == null || lines.isEmpty()) {
            lines = new ArrayList<>();
            lines.add("");
        }
        float lineH = size * 1.45f;
        int height = (int) (pad * 2 + lineH * lines.size());
        Bitmap bmp = Bitmap.createBitmap(width, Math.max(height, 240), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFFFFFFFF);

        Paint bar = new Paint();
        bar.setColor(ACCENT);
        canvas.drawRect(0, 0, width, 16, bar);

        float y = pad + size;
        for (String line : lines) {
            canvas.drawText(line, pad, y, paint);
            y += lineH;
        }
        return bmp;
    }

    /** 中文没有空格，按字符宽度硬折行。 */
    private List<String> wrap(String text, Paint paint, float maxWidth) {
        List<String> out = new ArrayList<>();
        String[] paras = (text == null ? "" : text).split("\n", -1);
        for (String para : paras) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                if (line.length() > 0 && paint.measureText(line.toString() + ch) > maxWidth) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(ch);
            }
            out.add(line.toString());
        }
        return out;
    }

    /** 存相册；Android 9 及以下没授权 / 任何异常都退到应用私有目录，保证能出图。 */
    private Uri saveImage(Bitmap bmp) {
        String name = "chuanc_" + System.currentTimeMillis() + ".png";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/串词表情包");
            }
            Uri uri = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.close();
                    return uri;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = new File(getExternalFilesDir(null), "stickers");
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        } catch (Throwable t) {
            return null;
        }
    }

    private void shareImage(Uri uri) {
        try {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("image/png");
            it.putExtra(Intent.EXTRA_STREAM, uri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "把表情包发到…"));
        } catch (Throwable t) {
            status("已保存，但没找到可分享的应用");
        }
    }

    /** 随机来一条：从当前（搜索后的）列表里抽一条直接复制，不用挑。 */
    private void copyRandom() {
        List<PhraseStore.Item> pool = new ArrayList<>();
        for (PhraseStore.Item it : PhraseStore.list(this)) {
            if (keyword.length() == 0 || it.text.contains(keyword)) {
                pool.add(it);
            }
        }
        if (pool.isEmpty()) {
            status("没有可抽的串词");
            return;
        }
        copyItem(pool.get((int) (Math.random() * pool.size())));
    }

    // ------------------------------------------------------------------ 编辑 / 谐音表

    private void showItemMenu(PhraseStore.Item it) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(shortText(it.text));
        if (it.builtin) {
            b.setItems(new String[]{"隐藏这条（内置）", "复制", "存表情包"}, (d, w) -> {
                d.dismiss();
                if (w == 0) {
                    PhraseStore.remove(this, it);
                    reload();
                    status("已隐藏，点「恢复」可以放回来");
                } else if (w == 1) {
                    copyItem(it);
                } else {
                    saveSticker(it);
                }
            });
        } else {
            b.setItems(new String[]{"编辑", "删除", "复制", "存表情包"}, (d, w) -> {
                d.dismiss();
                if (w == 0) {
                    showEditDialog(it);
                } else if (w == 1) {
                    PhraseStore.remove(this, it);
                    reload();
                    status("已删除");
                } else if (w == 2) {
                    copyItem(it);
                } else {
                    saveSticker(it);
                }
            });
        }
        b.setNegativeButton("取消", null).show();
    }

    private void showEditDialog(final PhraseStore.Item editing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText etText = new EditText(this);
        etText.setHint("串词正文，可以多行");
        etText.setHintTextColor(0xFF5C6268);
        etText.setTextColor(TEXT);
        etText.setTextSize(14);
        etText.setMinLines(3);
        etText.setGravity(Gravity.TOP);
        if (editing != null) {
            etText.setText(editing.text);
            etText.setSelection(editing.text.length());
        }
        box.addView(etText);

        new AlertDialog.Builder(this)
                .setTitle(editing == null ? "新增串词" : "编辑串词")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String txt = etText.getText().toString().trim();
                    if (txt.length() == 0) {
                        status("正文不能为空");
                        return;
                    }
                    if (editing == null) {
                        PhraseStore.addUser(this, "", txt);
                        status("已添加");
                    } else {
                        PhraseStore.updateUser(this, editing.id, editing.cat, txt);
                        status("已保存");
                    }
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 谐音替换模式

    /** 模式按钮上的字：0 = 全部替换，>0 = 每次随机换 1~N 处。 */
    private String homoModeLabel() {
        int max = PhraseStore.getHomoRandomMax(this);
        return max <= 0 ? "全部替换" : ("随机 1~" + max);
    }

    /** 点一下换一档：全部替换 → 随机 1~2 → 随机 1~3 → 随机 1~5 → 回到全部替换。 */
    private void cycleHomoMode() {
        int cur = PhraseStore.getHomoRandomMax(this);
        int next;
        if (cur <= 0) {
            next = 2;
        } else if (cur < 3) {
            next = 3;
        } else if (cur < 5) {
            next = 5;
        } else {
            next = 0;
        }
        setHomoMode(next);
    }

    /** 按当前「谐音替换」勾选与模式，把串词变成要复制 / 出图的样子。 */
    private String homoText(String text) {
        if (!cbHomo.isChecked()) {
            return text;
        }
        int max = PhraseStore.getHomoRandomMax(this);
        return max <= 0
                ? PhraseStore.applyHomophones(this, text)
                : PhraseStore.applyHomophonesRandom(this, text, max);
    }

    private void showHomoModeDialog() {
        final int cur = PhraseStore.getHomoRandomMax(this);
        final String[] items = new String[]{
                (cur <= 0 ? "✓ " : "") + "全部替换（命中的全换掉）",
                (cur == 3 ? "✓ " : "") + "随机替换 1~3 处（每回复制都不一样）",
                (cur == 5 ? "✓ " : "") + "随机替换 1~5 处",
                "自定义随机上限…"
        };
        new AlertDialog.Builder(this)
                .setTitle("谐音替换模式")
                .setMessage("随机模式：每次复制 / 出图时，在命中的位置里随机挑 1~N 处换成谐音写法。")
                .setItems(items, (d, w) -> {
                    d.dismiss();
                    if (w == 0) {
                        setHomoMode(0);
                    } else if (w == 1) {
                        setHomoMode(3);
                    } else if (w == 2) {
                        setHomoMode(5);
                    } else {
                        showCustomHomoMaxDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setHomoMode(int max) {
        PhraseStore.setHomoRandomMax(this, max);
        btnHomoMode.setText(homoModeLabel());
        cbHomo.setChecked(true);    // 换模式顺手把替换打开，免得改了看不出效果
        reload();
        String msg = max <= 0 ? "谐音：全部替换" : ("谐音：每次随机换 1~" + max + " 处");
        status(msg);
        toast(msg);     // 弹一下确认切成功了，免得以为点完没反应
    }

    private void showCustomHomoMaxDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("填 1~20，比如 4");
        et.setHintTextColor(0xFF5C6268);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        int cur = PhraseStore.getHomoRandomMax(this);
        et.setText(String.valueOf(cur > 0 ? cur : 3));
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("随机替换上限")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    int n = 3;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Throwable ignored) {
                    }
                    setHomoMode(Math.max(1, Math.min(20, n)));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHomoDialog() {
        final Map<String, String> map = PhraseStore.homophones(this);
        List<String> keys = new ArrayList<>(map.keySet());
        final String[] labels = new String[keys.size() + 1];
        labels[0] = "＋ 添加一对（原词 → 谐音写法）";
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = map.get(k);
            labels[i + 1] = k + "  →  " + (v == null || v.length() == 0 ? "（已停用）" : v);
        }
        new AlertDialog.Builder(this)
                .setTitle("谐音表 · 共 " + keys.size() + " 对")
                .setItems(labels, (d, w) -> {
                    d.dismiss();
                    if (w == 0) {
                        showAddHomoDialog();
                    } else {
                        showRemoveHomoDialog(keys.get(w - 1), map.get(keys.get(w - 1)));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAddHomoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText etFrom = new EditText(this);
        etFrom.setHint("原词（越长越优先）");
        etFrom.setHintTextColor(0xFF5C6268);
        etFrom.setTextColor(TEXT);
        etFrom.setTextSize(14);
        box.addView(etFrom);
        final EditText etTo = new EditText(this);
        etTo.setHint("替换成（谐音写法）");
        etTo.setHintTextColor(0xFF5C6268);
        etTo.setTextColor(TEXT);
        etTo.setTextSize(14);
        box.addView(etTo);
        new AlertDialog.Builder(this)
                .setTitle("添加谐音对")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String from = etFrom.getText().toString().trim();
                    String to = etTo.getText().toString().trim();
                    if (from.length() == 0 || to.length() == 0) {
                        status("两边都要填");
                        return;
                    }
                    PhraseStore.putHomophone(this, from, to);
                    reload();
                    status("已添加：" + from + " → " + to);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRemoveHomoDialog(final String from, String to) {
        new AlertDialog.Builder(this)
                .setTitle(from + " → " + (to == null ? "" : to))
                .setMessage("删掉这一对？（复制时就按原样，不再替换）")
                .setPositiveButton("删掉", (d, w) -> {
                    PhraseStore.removeHomophone(this, from);
                    reload();
                    status("已删掉：" + from);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 外部导入

    private static final int REQ_IMPORT = 8801;
    private static final int REQ_PERM_FILES = 8802;
    private static final int REQ_PERM_SETTINGS = 8803;

    /** 用户选的导入类型：0=串词 1=谐音 2=自动判断。 */
    private int importMode;

    private void showImportMenu() {
        new AlertDialog.Builder(this)
                .setTitle("从外部导入 txt")
                .setMessage("点「⇩ 导入 txt」= 直接拉系统选择器、自动认类型；这里是备选入口（长按该按钮也能进来）。\n"
                        + "· 串词：一行一条，# 开头是注释，一条里要换行就写 \\n\n"
                        + "· 谐音：一行一对，写成「原词 → 替换」或「原词=替换」")
                .setItems(new String[]{
                        "导入串词（追加，重复的自动跳过）",
                        "导入谐音表（同键覆盖）",
                        "导入并自动判断是哪种",
                        "从剪贴板导入（自动判断）",
                        "从手机文件夹导入（要读存储权限，扫 Download 等）"
                }, (d, w) -> {
                    // 列表项点完不会自动关框，得手动关：不关的话选完文件回来它还压在界面上，
                    // 看着就像“只弹了个框、什么都没导进去”。
                    d.dismiss();
                    final int mode = w;
                    // 等这一帧画完再拉系统文件选择器，避免对话框正在消失时 start 被吞掉
                    etSearch.post(() -> {
                        if (mode == 3) {
                            importFromClipboard();
                        } else if (mode == 4) {
                            importFromStorage();
                        } else {
                            importMode = mode;
                            pickTxt();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点一下的直接路径：类型自动判断，直接拉系统文件选择器。 */
    private void quickImport() {
        importMode = PhraseImporter.KIND_AUTO;
        pickTxt();
    }

    /**
     * 拉文件选择器：OPEN_DOCUMENT 起不来再试 GET_CONTENT，两个都真的起不来才退回菜单。
     *
     * <p>这里刻意不用 resolveActivity() 预判：Android 11+ 有包可见性限制，manifest 里没声明
     * &lt;queries&gt; 时它会一律返回 null，于是「明明装了文件管理器却说没有」。
     */
    private void pickTxt() {
        if (startPicker(Intent.ACTION_OPEN_DOCUMENT) || startPicker(Intent.ACTION_GET_CONTENT)) {
            status("选一个 .txt 文件…");
            return;
        }
        status("拉不起文件选择器，用「从剪贴板导入」或「从手机文件夹导入」");
        toast("没有能响应文件选择的 App，请用剪贴板或扫文件夹导入");
        showImportMenu();
    }

    /** 试一次：真启动了返回 true；只有 ActivityNotFoundException 这类才算起不来。 */
    private boolean startPicker(String action) {
        Intent it = new Intent(action);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        // txt 在各家 ROM / 文件管理器里报的 MIME 五花八门，干脆全放行，内容我们自己解析
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_IMPORT);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拖不起系统文件选择器时的兜底：先把 txt 全文复制，再点这里。 */
    private void importFromClipboard() {
        String text = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                text = cs == null ? "" : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        if (text.trim().length() == 0) {
            status("剪贴板是空的：先把 txt 全文复制，再点「从剪贴板导入」");
            return;
        }
        doImportText(text, PhraseImporter.KIND_AUTO);
    }

    /** 「从手机文件夹导入」：先要读存储的权限，再扫目录里的 txt 列出来点选。 */
    private void importFromStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                scanStorage();
                return;
            }
            try {
                startActivityForResult(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())),
                        REQ_PERM_SETTINGS);
                status("把「所有文件访问」打开，回来会自动开始找 txt");
            } catch (Throwable t) {
                status("这台设备没有「所有文件访问」的入口，改用系统选择器或剪贴板导入");
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            scanStorage();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_FILES);
    }

    /** 扫存储根目录 / Download / Documents / 应用目录（各往下两层），找 .txt。 */
    private void scanStorage() {
        status("正在找手机里的 txt…");
        new Thread(() -> {
            final List<File> found = new ArrayList<>();
            List<File> roots = new ArrayList<>();
            try {
                roots.add(Environment.getExternalStorageDirectory());
                roots.add(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS));
                roots.add(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOCUMENTS));
            } catch (Throwable ignored) {
            }
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                roots.add(ext);
            }
            for (File root : roots) {
                collectTxt(root, found, 0);
            }
            found.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            runOnUiThread(() -> showTxtPicker(found));
        }).start();
    }

    private void collectTxt(File dir, List<File> out, int depth) {
        if (dir == null || depth > 2 || out.size() >= 200) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (out.size() >= 200) {
                return;
            }
            String name = f.getName();
            if (f.isDirectory()) {
                if (name.startsWith(".") || name.startsWith("Android")) {
                    continue;   // 隐藏目录 / Android/data 这种不钻
                }
                collectTxt(f, out, depth + 1);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                out.add(f);
            }
        }
    }

    private void showTxtPicker(final List<File> files) {
        if (files.isEmpty()) {
            status("没找到 .txt：把文件放进 Download 目录，或用「从剪贴板导入」");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            labels[i] = f.getName() + "  (" + (f.length() / 1024 + 1) + "KB · "
                    + fmt.format(new Date(f.lastModified())) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("找到 " + files.size() + " 个 txt，点一个导入")
                .setItems(labels, (d, w) -> {
                    d.dismiss();
                    importFile(files.get(w));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 直接读本地文件（不走 SAF）：字节读完交给 PhraseImporter 认编码。 */
    private void importFile(final File file) {
        status("正在导入 " + file.getName() + "…");
        new Thread(() -> {
            String raw;
            try {
                FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 8 * 1024 * 1024) {
                            throw new IllegalStateException("文件太大（上限 8MB）");
                        }
                        bos.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                raw = PhraseImporter.decode(bos.toByteArray());
            } catch (Throwable t) {
                finishImport("读文件失败：" + tip(t), false);
                return;
            }
            importNow(raw, PhraseImporter.KIND_AUTO);
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req != REQ_PERM_FILES) {
            return;
        }
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            scanStorage();
        } else {
            status("没给读存储权限，改用「从剪贴板导入」吧");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PERM_SETTINGS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                scanStorage();
            } else {
                status("「所有文件访问」还没打开，扫不了手机里的 txt");
            }
            return;
        }
        if (req != REQ_IMPORT || res != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            status("没拿到文件");
            return;
        }
        doImport(uri, importMode);
    }

    /** 读文件 + 解析丢后台线程，完了回主线程刷新列表。 */
    private void doImport(final Uri uri, final int mode) {
        status("正在导入…");
        new Thread(() -> {
            String raw;
            try {
                raw = PhraseImporter.read(this, uri);
            } catch (Throwable t) {
                finishImport("读文件失败：" + tip(t), false);
                return;
            }
            importNow(raw, mode);
        }).start();
    }

    /** 剪贴板那条路：内容已经在手上，直接解析。 */
    private void doImportText(final String raw, final int mode) {
        status("正在导入…");
        new Thread(() -> importNow(raw, mode)).start();
    }

    /** 解析 + 入库；跑在后台线程，结束由 finishImport 回主线程报。 */
    private void importNow(String raw, int mode) {
        String msg;
        boolean homo = false;
        try {
            int kind = mode == 2 ? PhraseImporter.guessKind(raw) : mode;
            if (kind == PhraseImporter.KIND_HOMO) {
                homo = true;
                LinkedHashMap<String, String> pairs = PhraseImporter.parseHomophones(raw);
                int[] r = PhraseStore.putHomophonesBatch(this, pairs);
                msg = pairs.isEmpty()
                        ? "没解析到谐音对（写成「原词 → 替换」，一行一对）"
                        : "谐音表导入完成：读到 " + pairs.size() + " 对 · 新增 " + r[0]
                                + " · 覆盖 " + r[1];
            } else {
                List<String> texts = PhraseImporter.parsePhrases(raw);
                int[] r = PhraseStore.addUserBatch(this, texts);
                msg = texts.isEmpty()
                        ? "没解析到串词（一行一条，# 开头是注释）"
                        : "串词导入完成：读到 " + texts.size() + " 条 · 新增 " + r[0]
                                + " · 跳过重复 " + r[1];
            }
        } catch (Throwable t) {
            msg = "导入失败：" + tip(t);
        }
        finishImport(msg, homo);
    }

    /** 回主线程：刷新列表 + 弹一条结果，让人一眼看出“真导进去了”。 */
    private void finishImport(final String msg, final boolean homo) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            if (homo) {
                cbHomo.setChecked(true);    // 导完就能直接看到谐音效果
            }
            reload();
            status(msg);
            // 导入成功也算用掉一次；读取失败 / 没解析到则不算，退出时走 release
            if (!msg.startsWith("读文件失败") && !msg.startsWith("导入失败")
                    && !msg.startsWith("没解析到")) {
                finishActivation(true);
            }
            new AlertDialog.Builder(this)
                    .setTitle("导入结果")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show();
        });
    }

    private static String tip(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ 小工具

    private Button smallBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(round(FIELD, 999));
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void status(String s) {
        tvStatus.setText(s);
    }

    private String shortText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
