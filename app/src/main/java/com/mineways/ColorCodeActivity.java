package com.mineways;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * 颜色代码工具（Java / 基岩通用）· 完全离线。
 *
 * <p>给聊天、告示牌、命名牌、书、tellraw / 命令用：16 个基础色（§0~§f）、格式代码
 * （§k §l §m §n §o §r）、基岩版的 §g（Minecoin 金）、Java 1.16+ 真彩色（§x§R§R§G§G§B§B），
 * 以及最接近原版色的自动匹配 + 一键复制（§ 形式 / &amp; 形式 / JSON 形式）。</p>
 */
public class ColorCodeActivity extends AppCompatActivity {

    private static final int BG = 0xFF0F1013, PANEL = 0xFF15171A, TEXT = 0xFFEDEDED,
            DIM = 0xFF8A9099, DIM2 = 0xFF5C6268, ACCENT = 0xFFD9603A;

    /** {代码, 中文名, 颜色} —— 0~f 十六色（Java / 基岩通用）。 */
    private static final Object[][] COLORS = {
            {"0", "黑", 0x000000}, {"1", "深蓝", 0x0000AA}, {"2", "深绿", 0x00AA00},
            {"3", "深青", 0x00AAAA}, {"4", "深红", 0xAA0000}, {"5", "深紫", 0xAA00AA},
            {"6", "金", 0xFFAA00}, {"7", "灰", 0xAAAAAA}, {"8", "深灰", 0x555555},
            {"9", "蓝", 0x5555FF}, {"a", "绿", 0x55FF55}, {"b", "青", 0x55FFFF},
            {"c", "红", 0xFF5555}, {"d", "粉", 0xFF55FF}, {"e", "黄", 0xFFFF55},
            {"f", "白", 0xFFFFFF}, {"g", "金币金（基岩版）", 0xDDD605},
    };

    /** {代码, 中文名, 效果说明}。 */
    private static final String[][] FORMATS = {
            {"k", "乱码", "随机字符（obfuscated）"},
            {"l", "粗体", "bold"},
            {"m", "删除线", "strikethrough"},
            {"n", "下划线", "underline"},
            {"o", "斜体", "italic"},
            {"r", "重置", "清除之前所有颜色与格式"},
    };

    private EditText etText, etHex;
    private TextView tvPreview, tvOut;
    private int r = 0x55, g = 0xFF, b = 0x55;
    private String pickedCode = "a";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        refreshPreview();
        refreshOut();
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("颜色代码");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Java 和基岩通用的 § 颜色、格式代码。点一行就复制，"
                + "聊天、告示牌、命名牌、书、命令都能用。");
        sub.setTextSize(12.5f);
        sub.setTextColor(DIM);
        sub.setPadding(0, dp(6), 0, dp(10));
        col.addView(sub);

        // 预览
        col.addView(section("预览"));
        tvPreview = new TextView(this);
        tvPreview.setTextSize(16);
        tvPreview.setPadding(dp(12), dp(14), dp(12), dp(14));
        tvPreview.setBackground(round(PANEL, 12));
        tvPreview.setText("AshenFlame Foundry 颜色预览");
        col.addView(tvPreview);

        etText = input("要上色的文字（改这里预览会实时更新）");
        etText.setText("AshenFlame Foundry 颜色预览");
        etText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshPreview();
                refreshOut();
            }
        });
        col.addView(etText);

        // 十六色
        col.addView(section("16 色代码（点行复制 §代码 / &代码）"));
        for (Object[] c : COLORS) {
            col.addView(colorRow((String) c[0], (String) c[1], (Integer) c[2]));
        }

        // 格式
        col.addView(section("格式代码（§后接字母）"));
        for (String[] f : FORMATS) {
            col.addView(formatRow(f[0], f[1], f[2]));
        }

        // 真彩色
        col.addView(section("自选真彩色（Java 1.16+ 用 §x§R§R§G§G§B§B；基岩版部分场景支持）"));
        etHex = input("十六进制颜色，如 #55FF55");
        etHex.setText("#55FF55");
        etHex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                syncFromHex();
            }
        });
        col.addView(etHex);

        col.addView(slider("红 R", 0, new int[]{r}));
        col.addView(slider("绿 G", 1, new int[]{g}));
        col.addView(slider("蓝 B", 2, new int[]{b}));

        tvOut = new TextView(this);
        tvOut.setTextSize(12.5f);
        tvOut.setTextColor(TEXT);
        tvOut.setLineSpacing(dp(5), 1f);
        tvOut.setPadding(dp(12), dp(12), dp(12), dp(12));
        tvOut.setBackground(round(PANEL, 12));
        tvOut.setTextIsSelectable(true);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(10);
        col.addView(tvOut, olp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView copyRaw = chip("复制「§ 代码 + 文字」");
        copyRaw.setOnClickListener(v -> copy(esc(pickedCode) + text(), "已复制（§ 形式）"));
        row.addView(copyRaw);
        TextView copyAmp = chip("复制「& 形式」");
        copyAmp.setOnClickListener(v -> copy(amp(pickedCode) + text(), "已复制（& 形式，部分插件/平台用）"));
        row.addView(copyAmp);
        TextView copyHex = chip("复制真彩色代码");
        copyHex.setOnClickListener(v -> copy(hexCode(), "已复制真彩色代码"));
        row.addView(copyHex);
        col.addView(row);

        TextView note = new TextView(this);
        note.setText("小贴士：\n"
                + "· Java 版聊天/命令里可以直接打 §（部分输入法打不出可用 \\u00A7 转义）；\n"
                + "· 插件 / 服务器（如 Essentials、PlaceholderAPI）一般用 & 代替 §；\n"
                + "· tellraw / JSON 文本用 \"color\":\"#RRGGBB\" 才是真彩色；\n"
                + "· 基岩版额外支持 §g（Minecoin 金），§ 后接 0~f 与 Java 相同；\n"
                + "· §r 重置会清掉之前所有颜色与格式，写在末尾最省事。");
        note.setTextSize(12);
        note.setTextColor(DIM2);
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(0, dp(14), 0, 0);
        col.addView(note);

        return sv;
    }

    // ---------------------------------------------------------------- 交互

    private View colorRow(final String code, String name, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(round(PANEL, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            pickedCode = code;
            refreshPreview();
            refreshOut();
            copy(esc(code), "已复制 " + esc(code) + "（" + name + "）");
        });

        View swatch = new View(this);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        swatch.setBackground(round(0xFF000000 | color, 6));
        row.addView(swatch);

        TextView t = new TextView(this);
        t.setText("§" + code + "    " + name);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setPadding(dp(12), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView hex = new TextView(this);
        hex.setText(String.format(Locale.US, "#%06X", color).toUpperCase(Locale.US));
        hex.setTextSize(11.5f);
        hex.setTextColor(DIM2);
        row.addView(hex);
        return row;
    }

    private View formatRow(final String code, String name, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(round(PANEL, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> copy(esc(code), "已复制 " + esc(code) + "（" + name + "）"));

        TextView t = new TextView(this);
        t.setText("§" + code + "    " + name);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11.5f);
        d.setTextColor(DIM2);
        row.addView(d);
        return row;
    }

    private View slider(String label, final int channel, int[] holder) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(6), dp(4), 0);
        TextView t = new TextView(this);
        t.setText(label + "：" + holder[0]);
        t.setTextSize(12.5f);
        t.setTextColor(DIM);
        box.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(255);
        sb.setProgress(holder[0]);
        final TextView tv = t;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (channel == 0) {
                    r = value;
                } else if (channel == 1) {
                    g = value;
                } else {
                    b = value;
                }
                tv.setText(label + "：" + value);
                if (etHex != null) {
                    String hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b);
                    if (!hex.equalsIgnoreCase(etHex.getText().toString())) {
                        etHex.setText(hex);
                    }
                }
                refreshPreview();
                refreshOut();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(sb);
        return box;
    }

    private void syncFromHex() {
        if (etHex == null) {
            return;
        }
        String s = etHex.getText().toString().trim().replace("#", "");
        if (s.length() != 6) {
            return;
        }
        try {
            int v = Integer.parseInt(s, 16);
            r = (v >> 16) & 0xFF;
            g = (v >> 8) & 0xFF;
            b = v & 0xFF;
            refreshPreview();
            refreshOut();
        } catch (Throwable ignored) {
        }
    }

    private void refreshPreview() {
        if (tvPreview == null) {
            return;
        }
        String t = text();
        SpannableString sp = new SpannableString(t);
        int base = nearestVanillaColor();
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000 | base), 0, sp.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if ("l".equals(pickedCode)) {
            // 格式代码选了粗体时预览也加粗
            sp.setSpan(new StyleSpan(Typeface.BOLD), 0, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvPreview.setText(sp);
    }

    private void refreshOut() {
        if (tvOut == null) {
            return;
        }
        String near = "§" + nearestVanillaCode() + " 最接近的原版色代码";
        String s = "当前选择：§" + pickedCode + "  颜色 #"
                + String.format(Locale.US, "%02X%02X%02X", r, g, b).toUpperCase(Locale.US) + "\n\n"
                + "§ 形式： " + esc(pickedCode) + text() + "\n"
                + "& 形式： " + amp(pickedCode) + text() + "\n"
                + "真彩色： " + hexCode() + "\n"
                + "JSON：   \"color\":\"#" + String.format(Locale.US, "%02X%02X%02X", r, g, b).toUpperCase(Locale.US) + "\"\n"
                + near + "\n\n（点上面的行或下面的按钮即可复制）";
        tvOut.setText(s);
    }

    private String text() {
        String t = etText == null || etText.getText() == null ? "" : etText.getText().toString();
        return t.length() == 0 ? "文字" : t;
    }

    /** § 字符本身（U+00A7）。 */
    private static String esc(String code) {
        return "\u00A7" + code;
    }

    private static String amp(String code) {
        return "&" + code;
    }

    private String hexCode() {
        String rr = String.format(Locale.US, "%02X", r);
        String gg = String.format(Locale.US, "%02X", g);
        String bb = String.format(Locale.US, "%02X", b);
        return esc("x") + esc(rr.substring(0, 1)) + esc(rr.substring(1))
                + esc(gg.substring(0, 1)) + esc(gg.substring(1))
                + esc(bb.substring(0, 1)) + esc(bb.substring(1));
    }

    /** 与 #RRGGBB 最接近的原版 16 色代码（用于老版本 / 兼容场景）。 */
    private String nearestVanillaCode() {
        int best = 0xFFFFFF;
        String bestCode = "f";
        for (Object[] c : COLORS) {
            String code = (String) c[0];
            if ("g".equals(code)) {
                continue;   // 基岩版专有，不参与匹配
            }
            int col = (Integer) c[2];
            int d = dist(col, 0xFF000000 | (r << 16) | (g << 8) | b);
            if (d < dist(best, 0xFF000000 | (r << 16) | (g << 8) | b)) {
                best = col;
                bestCode = code;
            }
        }
        return bestCode;
    }

    private int nearestVanillaColor() {
        String code = nearestVanillaCode();
        for (Object[] c : COLORS) {
            if (code.equals(c[0])) {
                return (Integer) c[2];
            }
        }
        return 0xFFFFFF;
    }

    private static int dist(int a, int b) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (ar - br) * (ar - br) + (ag - bg) * (ag - bg) + (ab - bb) * (ab - bb);
    }

    // ---- 小控件 ----

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        t.setPadding(0, dp(18), 0, dp(8));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setBackground(round(PANEL, 20));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setClickable(true);
        return t;
    }

    private void copy(String text, String msg) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("minecraft-color", text));
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
