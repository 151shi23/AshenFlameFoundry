package com.mineways.prism;

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mineways.blender.RootShell;
import com.mineways.cloud.CloudCatalog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 棱镜玩机：按本机系统自动裁剪的玩机工具箱。
 *
 * <p>界面结构与「三大工具箱」一致：状态卡（本机/通道）→ 分区功能行 → 底部说明。
 * 所有功能都是「读现值 → 写 → 回读校验 → 失败回滚」，并把原值存进 original_*；
 * 能做的判断（键是否存在、ROM 是否接受该值）都交给真实读写，而不是只看品牌名。
 *
 * <p>品牌判断只决定「先展示哪套界面」：小米 → 澎湃OS 表，vivo/iQOO → OriginOS 表，
 * OPPO/一加/真我 → ColorOS 表，其他 → 通用表。用户可手动切换（ROM 移植/识别错误）。
 */
public class PrismActivity extends AppCompatActivity {

    private static final int BG = 0xFF0E0E10;
    private static final int CARD = 0xFF17181B;
    private static final int CARD_HI = 0xFF22242A;
    private static final int LINE = 0xFF2C2E34;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int DIM = 0xFF9A9DA6;
    private static final int WARN = 0xFFFFB24D;
    private static final int OK = 0xFF58D68D;
    private static final int DANGER = 0xFFFF6B6B;

    private static final int REQ_SHIZUKU = 14711;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PrismShell shell;
    private PrismProfile profile;

    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout listHost;
    private LinearLayout chipsBar;
    private EditText searchBox;
    private String query = "";
    private int sectionFilter = -1;
    private TextView statusText;
    private TextView channelBadge;
    private TextView headerTitle;
    private TextView headerSub;

    private final Map<String, Switch> switches = new LinkedHashMap<String, Switch>();
    private final Map<String, SSpec> specs = new LinkedHashMap<String, SSpec>();
    private final Map<String, LinearLayout> choiceBars = new LinkedHashMap<String, LinearLayout>();
    /** 本轮渲染时每条功能的云控状态（可见/可用/改名/置顶/排序），标题与说明都从这里取。 */
    private final Map<String, PrismFlags.State> cloudStates =
            new LinkedHashMap<String, PrismFlags.State>();
    /** 上一次生效的云控版本：页面在前台时配置更新，靠它发现并重建。 */
    private int lastRevision = Integer.MIN_VALUE;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(grantResult == PackageManager.PERMISSION_GRANTED
                                    ? "Shizuku 已授权" : "Shizuku 授权被拒绝");
                            rebuild();
                        }
                    });
                }
            };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 云端一票下架：首页入口已隐藏，这里再拦一次（可能从快捷方式/历史任务直接进来）
        if (!PrismFlags.on(this)) {
            String n = PrismFlags.notice(this);
            toast(n.isEmpty() ? "棱镜玩机已被云端关闭" : n);
            finish();
            return;
        }
        shell = new PrismShell(this);
        try {
            RootShell.addPermissionListener(permListener);
        } catch (Throwable ignored) {
        }
        setContentView(buildSkeleton());
        // 搜索框固定在顶部，不主动抢焦点，避免一进页面就弹出键盘挡住列表。
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // 品牌识别要跑 getprop，放后台，页面先出骨架
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismProfile detected;
                try {
                    detected = PrismProfile.detect(PrismActivity.this);
                } catch (Throwable t) {
                    detected = PrismProfile.fallback();
                }
                final PrismProfile p = detected;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        profile = p;
                        rebuild();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        int rev = CloudCatalog.appliedRevision();
        if (lastRevision == Integer.MIN_VALUE) {
            lastRevision = rev;
            return;
        }
        if (rev != lastRevision) {
            lastRevision = rev;
            // 云端换了配置（可能改了 prism_vendor / 关闭了某些功能）：重新识别 + 重建
            redetect();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 骨架

    private View buildSkeleton() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // 让根容器先拿焦点，搜索框不会在进页面时自动获得焦点。
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(13), dp(12), dp(3));

        TextView title = new TextView(this);
        title.setText("棱镜玩机");
        title.setTextColor(TEXT);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 状态胶囊：只显示「已授权 / 未授权」，通道细节与授权动作都收进弹窗。
        channelBadge = new TextView(this);
        channelBadge.setTextSize(11.5f);
        channelBadge.setTextColor(shell.any() ? OK : WARN);
        channelBadge.setText(shell.any() ? "● 已授权" : "○ 未授权");
        channelBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        channelBadge.setBackground(round(CARD_HI, 16));
        channelBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                channelDialog();
            }
        });
        bar.addView(channelBadge);

        TextView refresh = ghost("刷新");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        rlp.leftMargin = dp(8);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rebuild();
            }
        });
        bar.addView(refresh, rlp);
        root.addView(bar);

        // 过滤区固定在顶部：往下翻功能列表时，搜索与分类胶囊始终可用。
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);
        filters.setPadding(dp(14), dp(6), dp(14), dp(4));
        filters.addView(searchBar());
        filters.addView(chipsRow());
        root.addView(filters);

        scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(2), dp(14), dp(24));
        content.addView(note("正在识别本机系统…"));
        scroll.addView(content);
        root.addView(scroll);
        return root;
    }

    private void rebuild() {
        if (profile == null) {
            content.removeAllViews();
            content.addView(note("正在识别本机系统…"));
            return;
        }
        int keepY = scroll == null ? 0 : scroll.getScrollY();
        switches.clear();
        specs.clear();
        choiceBars.clear();
        content.removeAllViews();

        channelBadge.setText(shell.any() ? "● 已授权" : "○ 未授权");
        channelBadge.setTextColor(shell.any() ? OK : WARN);

        content.addView(systemStrip());
        View cloud = cloudNote();
        if (cloud != null) content.addView(cloud);
        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(listHost);

        renderFeatures();
        content.addView(explainLink());
        if (scroll != null) scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, keepY);
            }
        });
    }

    /** 只重绘功能区：搜索/分类切换时不丢焦点、不跳滚动位置。 */
    private void renderFeatures() {
        if (listHost == null || profile == null) return;
        switches.clear();
        specs.clear();
        choiceBars.clear();
        cloudStates.clear();
        listHost.removeAllViews();
        final List<PrismFeatures.Feature> features = PrismFeatures.forVendor(profile.vendor);
        for (PrismFeatures.Feature f : features) {
            cloudStates.put(f.id, PrismFlags.state(this, f.id));
        }
        // 换了适配系统后，旧分类可能已经不存在：自动退回「全部」。
        if (sectionFilter >= 0 && !sectionAlive(features)) sectionFilter = -1;

        final Map<String, Integer> baseIndex = new LinkedHashMap<String, Integer>();
        final List<PrismFeatures.Feature> visible = new ArrayList<PrismFeatures.Feature>();
        final List<PrismFeatures.Feature> pinned = new ArrayList<PrismFeatures.Feature>();
        final Map<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();
        int hidden = 0;
        int disabled = 0;
        for (int i = 0; i < features.size(); i++) {
            PrismFeatures.Feature f = features.get(i);
            baseIndex.put(f.id, i);
            PrismFlags.State st = cloudStates.get(f.id);
            if (st == null || !st.visible) {
                hidden++;
                continue;
            }
            if (!st.usable) disabled++;
            if (sectionFilter >= 0 && f.section != sectionFilter) continue;
            if (!matches(f)) continue;
            if (st.pinned) {
                pinned.add(f);
            } else {
                visible.add(f);
                // 置顶项挪到「常用」，不再计入原分区，否则分区标题上的数字会对不上
                Integer c = counts.get(f.section);
                counts.put(f.section, c == null ? 1 : c + 1);
            }
        }

        // 云端 FEAT order 小的在前；没排的保持出厂顺序。
        final Comparator<PrismFeatures.Feature> byOrder = new Comparator<PrismFeatures.Feature>() {
            @Override
            public int compare(PrismFeatures.Feature a, PrismFeatures.Feature b) {
                int oa = orderOf(a);
                int ob = orderOf(b);
                if (oa != ob) return oa < ob ? -1 : 1;
                return baseIndex.get(a.id) - baseIndex.get(b.id);
            }
        };
        Collections.sort(pinned, byOrder);
        Collections.sort(visible, new Comparator<PrismFeatures.Feature>() {
            @Override
            public int compare(PrismFeatures.Feature a, PrismFeatures.Feature b) {
                if (a.section != b.section) return a.section < b.section ? -1 : 1;
                return byOrder.compare(a, b);
            }
        });

        int lastSection = Integer.MIN_VALUE;
        LinearLayout box = null;
        if (!pinned.isEmpty()) {
            lastSection = -1;
            listHost.addView(sectionHeader(-1, pinned.size()));
            box = sectionCard();
            listHost.addView(box);
            for (PrismFeatures.Feature f : pinned) {
                if (box.getChildCount() > 0) box.addView(divider());
                box.addView(featureRow(f));
            }
        }
        for (PrismFeatures.Feature f : visible) {
            if (f.section != lastSection) {
                lastSection = f.section;
                Integer c = counts.get(f.section);
                listHost.addView(sectionHeader(f.section, c == null ? 0 : c));
                box = sectionCard();
                listHost.addView(box);
            }
            if (box.getChildCount() > 0) box.addView(divider());
            box.addView(featureRow(f));
        }
        if (visible.isEmpty() && pinned.isEmpty()) {
            listHost.addView(note(hidden > 0 && TextUtils.isEmpty(query)
                    ? "所有功能都被云端配置关闭了。" : "没有匹配的功能，换个关键词试试。"));
        }
        if (hidden + disabled > 0) {
            StringBuilder b = new StringBuilder("云端调整：");
            if (hidden > 0) b.append("隐藏 ").append(hidden).append(" 项");
            if (disabled > 0) {
                if (hidden > 0) b.append(" · ");
                b.append("停用 ").append(disabled).append(" 项（灰色行，点按看原因）");
            }
            TextView t = note(b.toString());
            t.setTextColor(WARN);
            listHost.addView(t);
        }
        updateChips();
        refreshStates();
        refreshChoices();
    }

    private boolean sectionAlive(List<PrismFeatures.Feature> features) {
        for (PrismFeatures.Feature f : features) {
            if (f.section != sectionFilter) continue;
            PrismFlags.State st = cloudStates.get(f.id);
            if (st != null && st.visible) return true;
        }
        return false;
    }

    private int orderOf(PrismFeatures.Feature f) {
        PrismFlags.State s = cloudStates.get(f.id);
        return s == null || s.order < 0 ? Integer.MAX_VALUE : s.order;
    }

    private String labelOf(PrismFeatures.Feature f) {
        PrismFlags.State s = cloudStates.get(f.id);
        return s != null && s.label != null && s.label.trim().length() > 0 ? s.label : f.title;
    }

    private String descOf(PrismFeatures.Feature f) {
        PrismFlags.State s = cloudStates.get(f.id);
        return s != null && s.desc != null && s.desc.trim().length() > 0 ? s.desc : f.desc;
    }

    private boolean matches(PrismFeatures.Feature f) {
        if (TextUtils.isEmpty(query)) return true;
        String q = query.toLowerCase(Locale.US);
        String title = labelOf(f).toLowerCase(Locale.US);
        String desc = descOf(f).toLowerCase(Locale.US);
        String tag = f.tag == null ? "" : f.tag.toLowerCase(Locale.US);
        return title.contains(q) || desc.contains(q) || tag.contains(q) || f.id.contains(q);
    }

    /** 一行系统画像：设备 · ROM；右侧「切换」只改适配表，授权细节都收进顶栏状态胶囊。 */
    private View systemStrip() {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(12), dp(10), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackground(round(profile.accent(), 3));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(4), dp(30));
        dlp.rightMargin = dp(10);
        top.addView(dot, dlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        headerTitle = new TextView(this);
        headerTitle.setText(profile.title());
        headerTitle.setTextColor(TEXT);
        headerTitle.setTextSize(14.5f);
        headerTitle.setMaxLines(1);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(headerTitle);
        headerSub = new TextView(this);
        headerSub.setText(profile.brandLabel());
        headerSub.setTextColor(DIM);
        headerSub.setTextSize(11f);
        headerSub.setPadding(0, dp(2), 0, 0);
        headerSub.setMaxLines(1);
        headerSub.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(headerSub);
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView change = ghost("切换");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        clp.leftMargin = dp(6);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseVendor();
            }
        });
        top.addView(change, clp);
        card.addView(top);

        // 授权状态只看右上角胶囊；不再用一整行解释「点哪里」。
        // 进度提示：没有消息时不占位。
        statusText = new TextView(this);
        statusText.setTextColor(DIM);
        statusText.setTextSize(11f);
        statusText.setPadding(dp(14), dp(6), 0, 0);
        statusText.setVisibility(View.GONE);
        card.addView(statusText);
        return card;
    }

    /** 两段长说明收进弹窗，列表尾部只留一个入口。 */
    private View explainLink() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(16), dp(8), dp(6));
        TextView t = new TextView(this);
        t.setText("说明");
        t.setTextColor(DIM);
        t.setTextSize(12f);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(DIM);
        arrow.setTextSize(18f);
        r.addView(arrow);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainDialog();
            }
        });
        return r;
    }

    private void explainDialog() {
        String tip;
        switch (profile.vendor) {
            case OPPO:
                tip = "ColorOS 15/16 覆盖最全；12~14 部分键无效。高刷/极暗/指纹动画等为 ColorOS 专属，其他品牌不会显示。";
                break;
            case VIVO:
                tip = "插帧/直驱供电/充电 AOD 为 vivo 专属，且依赖机型与系统版本；插帧重启后需要重新设置。";
                break;
            case XIAOMI:
                tip = "澎湃OS 的 5G / AOD 键族因版本而异，本页只保留可读写校验通过的项；写入被拒会自动回滚。";
                break;
            default:
                tip = "未识别到厂商 ROM，只显示 Android 原生通用项（解锁/冻结/卸载/动画速度/演示模式等）。";
                break;
        }
        PrismDialog.info(this, "说明与风险提示",
                tip + "\n\n所有可逆功能都会在首次修改前写入 original_<键> 备份；"
                        + "「一键恢复全部修改」只恢复设置键，不解冻/不装回应用。\n"
                        + "品牌专属项在其他 ROM 上命令必然无效，本页已按当前适配系统自动隐藏。",
                "知道了", null);
    }

    /** 云端下发的限制/公告：只在真的有内容时占一行。 */
    private View cloudNote() {
        String notice = PrismFlags.notice(this);
        int risk = PrismFlags.risk(this);
        if (notice.isEmpty() && risk >= PrismFeatures.DANGER) return null;
        StringBuilder b = new StringBuilder();
        if (!notice.isEmpty()) b.append(notice);
        if (risk < PrismFeatures.DANGER) {
            if (b.length() > 0) b.append('\n');
            b.append("云端限制：只开放到「").append(risk == 0 ? "安全" : "谨慎").append("」档功能。");
        }
        TextView t = note(b.toString());
        t.setTextColor(WARN);
        return t;
    }

    private void redetect() {
        setStatus("云控配置已更新，正在刷新…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismProfile p;
                try {
                    p = PrismProfile.detect(PrismActivity.this);
                } catch (Throwable t) {
                    p = PrismProfile.fallback();
                }
                final PrismProfile fp = p;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        profile = fp;
                        rebuild();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 功能行

    /** 云端停用的功能不再接命令，只保留一行说明；其余交给各类型卡片。 */
    private View featureRow(PrismFeatures.Feature f) {
        PrismFlags.State st = cloudStates.get(f.id);
        if (st != null && !st.usable) return disabledCard(f, st.notice);
        return buildFeatureRow(f);
    }

    private View disabledCard(final PrismFeatures.Feature f, final String notice) {
        LinearLayout card = row();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(rowTitle(f, true));
        // 只有谨慎/高风险项才补一句原因；安全项让标题和开关自己说明。
        if (f.risk != PrismFeatures.SAFE) col.addView(rowDesc(f, true));
        line.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText("已停用");
        badge.setTextColor(0xFF8A8D95);
        badge.setTextSize(10f);
        badge.setPadding(dp(7), dp(3), dp(7), dp(3));
        badge.setBackground(round(0xFF23252B, 6));
        line.addView(badge);
        card.addView(line);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrismDialog.info(PrismActivity.this, labelOf(f) + " · 已停用",
                        TextUtils.isEmpty(notice) ? "该功能已被云端停用，恢复时间以配置为准。" : notice,
                        null, null);
            }
        });
        return card;
    }

    private View buildFeatureRow(PrismFeatures.Feature f) {
        String id = f.id;
        if ("no_lock".equals(id)) {
            return switchCard(f, new SSpec("system", "screen_off_timeout", "2147483647", "60000", "nolock"));
        }
        if ("direct_charge".equals(id)) {
            return switchCard(f, new SSpec("secure", "settings_battery_charge_director", "1", "0", "numeric"));
        }
        if ("charging_aod".equals(id)) {
            return switchCard(f, new SSpec("secure", "power_engine_and_plugged_state_and_charging_aod", "1", "0", "numeric"));
        }
        if ("charge_anim".equals(id)) {
            return switchCard(f, new SSpec("system", "flash_charge_animation_style", "10", "1", "charge"));
        }
        if ("ultra_dark".equals(id)) {
            return switchCard(f, new SSpec("secure", "ultra_dark_mode_enabled", "1", "0", ""));
        }
        if ("chain3".equals(id)) {
            return switchCard(f, new SSpec("", "", "true", "false", "chain3"));
        }
        if ("install_popup".equals(id)) {
            return switchCard(f, new SSpec("", "com.oplus.appdetail", "1", "0", "install"));
        }
        if ("five_g".equals(id)) {
            return switchCard(f, new SSpec("global", "smart_5g_enabled", "1", "0", "fiveg"));
        }
        if ("block_update".equals(id)) {
            return switchCard(f, new SSpec("", "", "1", "0", "update"));
        }
        if ("frame_interp".equals(id)) {
            return choiceCard(f, new String[]{"off", "120", "144"},
                    new String[]{"关闭", "120Hz", "144Hz"});
        }
        if ("refresh_rate".equals(id)) {
            return choiceCard(f, new String[]{"60", "90", "120"}, new String[]{"60Hz", "90Hz", "120Hz"});
        }
        if ("anim_scale".equals(id)) {
            return choiceCard(f, new String[]{"0", "0.5", "1", "2"}, new String[]{"关", "0.5×", "1×", "2×"});
        }
        if ("app_actions".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("应用管理", true, new AppAction() {
                        @Override
                        public void run(AppEntry e) {
                            appActionDialog(e);
                        }
                    });
                }
            });
        }
        if ("app_network".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("分应用断网", true, new AppAction() {
                        @Override
                        public void run(final AppEntry e) {
                            networkDialog(e);
                        }
                    });
                }
            });
        }
        if ("game_space".equals(id)) {
            return actionCard(f, "打开", new Runnable() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            uiResult(shell.startGameSpace());
                        }
                    });
                }
            });
        }
        if ("scene".equals(id)) {
            return actionCard(f, "打开", new Runnable() {
                @Override
                public void run() {
                    openScene();
                }
            });
        }
        if ("ad_guide".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    guideDialog();
                }
            });
        }
        if ("fingerprint".equals(id)) {
            return actionCard(f, "选择动画", new Runnable() {
                @Override
                public void run() {
                    fingerprintDialog();
                }
            });
        }
        if ("demo_mode".equals(id)) {
            return actionCard(f, "演示模式", new Runnable() {
                @Override
                public void run() {
                    demoDialog();
                }
            });
        }
        if ("device_name".equals(id)) {
            return actionCard(f, "修改名称", new Runnable() {
                @Override
                public void run() {
                    renameDialog();
                }
            });
        }
        if ("grant_wss".equals(id)) {
            return actionCard(f, "授予", new Runnable() {
                @Override
                public void run() {
                    doGrantWss();
                }
            });
        }
        if ("restore_all".equals(id)) {
            return actionCard(f, "一键恢复", new Runnable() {
                @Override
                public void run() {
                    confirmRestoreAll();
                }
            });
        }
        if ("clear_update_notice".equals(id)) {
            return actionCard(f, "清除提醒", new Runnable() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            uiResult(shell.setChecked("system", "bbk_update_notice", "-1"));
                        }
                    });
                }
            });
        }
        if ("standby_bucket".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("应用待机分组", true, new AppAction() {
                        @Override
                        public void run(AppEntry e) {
                            standbyDialog(e);
                        }
                    });
                }
            });
        }
        if ("appops_bg".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("后台运行管控", true, new AppAction() {
                        @Override
                        public void run(AppEntry e) {
                            appopsDialog(e);
                        }
                    });
                }
            });
        }
        if ("component_viewer".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("组件查看器", true, new AppAction() {
                        @Override
                        public void run(AppEntry e) {
                            componentViewer(e);
                        }
                    });
                }
            });
        }
        if ("privacy_audit".equals(id)) {
            return actionCard(f, "开始体检", new Runnable() {
                @Override
                public void run() {
                    privacyAuditDialog();
                }
            });
        }
        if ("app_perms".equals(id)) {
            return actionCard(f, "选择应用", new Runnable() {
                @Override
                public void run() {
                    pickApp("权限审计", true, new AppAction() {
                        @Override
                        public void run(AppEntry e) {
                            appPermsDialog(e);
                        }
                    });
                }
            });
        }
        if ("bg_cleaner".equals(id)) {
            return actionCard(f, "立即清理", new Runnable() {
                @Override
                public void run() {
                    runBgCleaner();
                }
            });
        }
        if ("battery_cycle".equals(id)) {
            return actionCard(f, "查看", new Runnable() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            final String r = shell.batteryCycleReport();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    PrismDialog.info(PrismActivity.this, "电池循环与损耗",
                                            r + "\n\n健康度 = 满充容量 / 设计容量；90% 以上为良好。\n"
                                                    + "循环次数部分机型不公开，属正常。", null, null);
                                }
                            });
                        }
                    });
                }
            });
        }
        if ("refresh_lock".equals(id)) {
            return actionCard(f, "锁定", new Runnable() {
                @Override
                public void run() {
                    refreshDialog();
                }
            });
        }
        if ("wifi_adb".equals(id)) {
            return actionCard(f, "设置", new Runnable() {
                @Override
                public void run() {
                    wifiAdbDialog();
                }
            });
        }
        if ("hw_profile".equals(id)) {
            return actionCard(f, "查看", new Runnable() {
                @Override
                public void run() {
                    hwProfileDialog();
                }
            });
        }
        if ("lock_screen".equals(id)) {
            return actionCard(f, "锁屏", new Runnable() {
                @Override
                public void run() {
                    if (!shell.shizuku()) {
                        toast("一键锁屏需要 Shizuku");
                        return;
                    }
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            uiResult(shell.lockScreen());
                        }
                    });
                }
            });
        }
        if ("force_doze".equals(id)) {
            return actionCard(f, "控制", new Runnable() {
                @Override
                public void run() {
                    dozeDialog();
                }
            });
        }
        if ("force_dark".equals(id)) {
            return actionCard(f, "切换", new Runnable() {
                @Override
                public void run() {
                    darkDialog();
                }
            });
        }
        if ("display_size".equals(id)) {
            return actionCard(f, "调整", new Runnable() {
                @Override
                public void run() {
                    displayDialog();
                }
            });
        }
        if ("screen_test".equals(id)) {
            return actionCard(f, "开始", new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(PrismActivity.this, PrismScreenTestActivity.class));
                    } catch (Throwable t) {
                        toast("打不开屏幕体检：" + t.getMessage());
                    }
                }
            });
        }
        if ("battery_health".equals(id)) {
            return actionCard(f, "查看", new Runnable() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            final String report = shell.batteryReport();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    PrismDialog.info(PrismActivity.this, "电池体检", report, null, null);
                                }
                            });
                        }
                    });
                }
            });
        }
        if ("net_diag".equals(id)) {
            return actionCard(f, "开始诊断", new Runnable() {
                @Override
                public void run() {
                    netDiagDialog();
                }
            });
        }
        if ("battery_magic".equals(id)) {
            return actionCard(f, "设置", new Runnable() {
                @Override
                public void run() {
                    batteryDialog();
                }
            });
        }
        if ("hide_notifications".equals(id)) {
            return actionCard(f, "管理", new Runnable() {
                @Override
                public void run() {
                    notificationsDialog();
                }
            });
        }
        if ("wallpaper_store".equals(id)) {
            return actionCard(f, "打开", new Runnable() {
                @Override
                public void run() {
                    openWallpaperStore();
                }
            });
        }
        if ("dial_codes".equals(id)) {
            return actionCard(f, "选择暗码", new Runnable() {
                @Override
                public void run() {
                    dialDialog();
                }
            });
        }
        if ("settings_jumps".equals(id)) {
            return actionCard(f, "打开列表", new Runnable() {
                @Override
                public void run() {
                    jumpsDialog();
                }
            });
        }
        if ("shizuku_help".equals(id)) {
            return actionCard(f, "查看步骤", new Runnable() {
                @Override
                public void run() {
                    helpDialog();
                }
            });
        }
        return actionCard(f, "执行", new Runnable() {
            @Override
            public void run() {
                toast("这个功能还没有实现");
            }
        });
    }

    // ---------------------------------------------------------------- 三种行样式

    private static final class SSpec {
        final String table, key, on, off, special;

        SSpec(String table, String key, String on, String off, String special) {
            this.table = table;
            this.key = key;
            this.on = on;
            this.off = off;
            this.special = special;
        }
    }

    private View switchCard(final PrismFeatures.Feature f, final SSpec spec) {
        LinearLayout card = row();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(rowTitle(f));
        if (f.risk != PrismFeatures.SAFE) col.addView(rowDesc(f));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setEnabled(false);
        row.addView(sw);
        card.addView(row);

        switches.put(f.id, sw);
        specs.put(f.id, spec);
        sw.setOnCheckedChangeListener(null);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (sw.getTag() != null) return;   // 程序刷新状态时不触发
                if (f.risk == PrismFeatures.DANGER && isChecked) {
                    // 高风险项先回弹，等用户明确确认后再真正写
                    sw.setTag("sync");
                    sw.setChecked(false);
                    sw.setTag(null);
                    confirm("高风险操作", "「" + labelOf(f) + "」属于高风险项：\n" + descOf(f)
                            + "\n\n已知风险由你自行承担，确认继续？", new Runnable() {
                        @Override
                        public void run() {
                            sw.setTag("sync");
                            sw.setChecked(true);
                            sw.setTag(null);
                            applySwitch(f, spec, sw, true);
                        }
                    });
                    return;
                }
                applySwitch(f, spec, sw, isChecked);
            }
        });
        return card;
    }

    private View choiceCard(final PrismFeatures.Feature f, final String[] tokens, final String[] labels) {
        LinearLayout card = row();
        card.addView(rowTitle(f));
        if (f.risk != PrismFeatures.SAFE) card.addView(rowDesc(f));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < tokens.length; i++) {
            final String token = tokens[i];
            Button b = miniButton(labels[i], CARD_HI);
            b.setTag("choice|" + f.id + "|" + token + "|" + labels[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyChoice(f.id, token);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            bar.addView(b, lp);
        }
        card.addView(bar);
        choiceBars.put(f.id, bar);
        return card;
    }

    /** 读当前选中的档位，把对应按钮加粗并点一个小圆点（不依赖背景色，避免被全局视觉层覆盖）。 */
    private void refreshChoices() {
        if (choiceBars.isEmpty()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, String> active = new LinkedHashMap<String, String>();
                for (String id : choiceBars.keySet()) {
                    try {
                        active.put(id, readChoice(id));
                    } catch (Throwable t) {
                        active.put(id, "");
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        for (Map.Entry<String, String> e : active.entrySet()) {
                            paintChoice(choiceBars.get(e.getKey()), e.getValue());
                        }
                    }
                });
            }
        });
    }

    private String readChoice(String id) {
        if ("frame_interp".equals(id)) {
            String v = shell.get("system", "gamecube_frame_interpolation");
            if (v == null) return "";
            String t = v.trim();
            if (t.startsWith("0")) return "off";
            if (t.contains(":120")) return "120";
            if (t.contains(":144")) return "144";
            return "";
        }
        if ("refresh_rate".equals(id)) {
            String v = shell.get("system", "oplus_customize_screen_refresh_rate");
            if (v == null) return "";
            String t = v.trim();
            if ("1".equals(t) || "60".equals(t)) return "60";
            if ("2".equals(t) || "90".equals(t)) return "90";
            if ("3".equals(t) || "120".equals(t) || "144".equals(t)) return "120";
            return "";
        }
        if ("anim_scale".equals(id)) {
            String v = shell.get("global", "window_animation_scale");
            return v == null ? "" : v.trim();
        }
        return "";
    }

    private void paintChoice(LinearLayout bar, String active) {
        if (bar == null) return;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View v = bar.getChildAt(i);
            if (!(v instanceof Button)) continue;
            Button b = (Button) v;
            String tag = b.getTag() == null ? "" : String.valueOf(b.getTag());
            String[] p = tag.split("\\|");
            if (p.length < 4) continue;
            String token = p[2];
            String label = p[3];
            boolean on = token.equals(active);
            b.setText(on ? "● " + label : label);
            b.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private View actionCard(PrismFeatures.Feature f, String action, final Runnable run) {
        LinearLayout card = row();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(rowTitle(f));
        if (f.risk != PrismFeatures.SAFE) col.addView(rowDesc(f));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button b = miniButton(action, f.risk == PrismFeatures.DANGER ? DANGER : CARD_HI);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        blp.leftMargin = dp(10);
        row.addView(b, blp);
        card.addView(row);
        return card;
    }

    private View rowTitle(PrismFeatures.Feature f) {
        return rowTitle(f, false);
    }

    private View rowTitle(PrismFeatures.Feature f, boolean dim) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(labelOf(f));
        t.setTextColor(dim ? 0xFF8A8D95 : TEXT);
        t.setTextSize(14.5f);
        line.addView(t);
        if (f.tag != null && !f.tag.isEmpty()) {
            TextView tag = new TextView(this);
            tag.setText(f.tag);
            tag.setTextColor(0xFF7F838C);
            tag.setTextSize(9.5f);
            tag.setPadding(dp(5), dp(1), dp(5), dp(1));
            tag.setBackground(round(0xFF23252B, 5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(7);
            line.addView(tag, lp);
        }
        return line;
    }

    private TextView rowDesc(PrismFeatures.Feature f) {
        return rowDesc(f, false);
    }

    private TextView rowDesc(PrismFeatures.Feature f, boolean dim) {
        TextView t = new TextView(this);
        t.setText(descOf(f));
        t.setTextColor(dim ? 0xFF6E727B : DIM);
        t.setTextSize(11f);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(dp(1), 1f);
        t.setPadding(0, dp(3), dp(8), 0);
        return t;
    }

    // ---------------------------------------------------------------- 状态读取

    private void refreshStates() {
        if (switches.isEmpty() && profile == null) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, Boolean> states = new LinkedHashMap<String, Boolean>();
                for (Map.Entry<String, SSpec> e : specs.entrySet()) {
                    try {
                        states.put(e.getKey(), readSwitch(e.getValue()));
                    } catch (Throwable t) {
                        states.put(e.getKey(), false);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        for (Map.Entry<String, Boolean> e : states.entrySet()) {
                            Switch sw = switches.get(e.getKey());
                            if (sw == null) continue;
                            sw.setTag("sync");
                            sw.setChecked(e.getValue());
                            sw.setTag(null);
                            sw.setEnabled(shell.any());
                        }
                    }
                });
            }
        });
    }

    /** 读开关真实状态：能用命令读的读命令，读不到就按「不支持」显示为关。 */
    private boolean readSwitch(SSpec s) {
        if ("nolock".equals(s.special)) {
            String v = shell.get("system", "screen_off_timeout");
            try {
                return v != null && Long.parseLong(v.trim()) > 10000000L;
            } catch (Throwable t) {
                return false;
            }
        }
        if ("chain3".equals(s.special)) {
            PrismShell.Out o = shell.exec("cmd connectivity get-chain3-enabled");
            return o.ok && o.text.toLowerCase(Locale.ROOT).contains("true");
        }
        if ("install".equals(s.special)) {
            return shell.isSuspended("com.oplus.appdetail");
        }
        if ("update".equals(s.special)) {
            for (String p : PrismFeatures.otaPackages(profile.vendor)) {
                if (shell.isDisabled(p) || shell.isSuspended(p)) return true;
            }
            String key = PrismFeatures.updateNoticeKey(profile.vendor);
            return key != null && "-1".equals(shell.get("system", key));
        }
        if ("fiveg".equals(s.special)) {
            String a = shell.get("global", "smart_5g_enabled");
            String b = shell.get("global", "user_5g_enabled");
            return isTruthy(a) || isTruthy(b);
        }
        if ("numeric".equals(s.special)) {
            String v = shell.get(s.table, s.key);
            try {
                return v != null && Integer.parseInt(v.trim()) != 0;
            } catch (Throwable t) {
                return isTruthy(v);
            }
        }
        if ("charge".equals(s.special)) {
            return PrismShell.sameValue(shell.get(s.table, s.key), "10");
        }
        String cur = shell.get(s.table, s.key);
        return PrismShell.isOn(cur, s.on);
    }

    private static boolean isTruthy(String v) {
        if (v == null) return false;
        String t = v.trim().toLowerCase(Locale.ROOT);
        return "1".equals(t) || "true".equals(t) || "on".equals(t);
    }

    // ---------------------------------------------------------------- 写入

    private void applySwitch(final PrismFeatures.Feature f, final SSpec s, final Switch sw, final boolean want) {
        if (!shell.any()) {
            sw.setEnabled(false);
            toast("先激活 Shizuku 或授予写设置权限");
            return;
        }
        sw.setEnabled(false);
        setStatus("处理中：" + labelOf(f));
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Res r;
                try {
                    r = doSwitch(s, want);
                } catch (Throwable t) {
                    r = new PrismShell.Res(false, String.valueOf(t));
                }
                final PrismShell.Res fr = r;
                final boolean actual = readSwitch(s);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        sw.setTag("sync");
                        sw.setChecked(actual);
                        sw.setTag(null);
                        sw.setEnabled(shell.any());
                        setStatus(fr.msg);
                        toast(fr.msg);
                        if (!fr.ok) refreshStates();
                    }
                });
            }
        });
    }

    private PrismShell.Res doSwitch(SSpec s, boolean want) {
        if ("nolock".equals(s.special)) {
            if (want) return shell.setChecked("system", "screen_off_timeout", "2147483647");
            return shell.hasBackup("system", "screen_off_timeout")
                    ? shell.restore("system", "screen_off_timeout")
                    : shell.setChecked("system", "screen_off_timeout", "60000");
        }
        if ("chain3".equals(s.special)) {
            PrismShell.Out o = shell.exec("cmd connectivity set-chain3-enabled " + want);
            if (!o.ok) return new PrismShell.Res(false, "命令失败：" + o.text);
            PrismShell.Out q = shell.exec("cmd connectivity get-chain3-enabled");
            boolean now = q.text.toLowerCase(Locale.ROOT).contains("true");
            return new PrismShell.Res(now == want,
                    now == want ? "已" + (want ? "开启" : "关闭") : "系统未接受（ROM 可能裁剪了 chain3）");
        }
        if ("install".equals(s.special)) {
            return want ? shell.freeze("com.oplus.appdetail") : shell.unfreeze("com.oplus.appdetail");
        }
        if ("charge".equals(s.special)) {
            if (want) return shell.setChecked(s.table, s.key, "10");
            return shell.hasBackup(s.table, s.key)
                    ? shell.restore(s.table, s.key)
                    : shell.setChecked(s.table, s.key, "1");
        }
        if ("update".equals(s.special)) {
            StringBuilder msg = new StringBuilder();
            for (String p : PrismFeatures.otaPackages(profile.vendor)) {
                // OTA 用 disable-user（文档同款）：suspend 只挡启动，disable 才是“服务不存在”
                PrismShell.Res r = want
                        ? (shell.isDisabled(p) ? new PrismShell.Res(true, "") : shell.disablePackage(p))
                        : shell.enablePackage(p);
                if (!r.ok) msg.append(p).append("：").append(r.msg).append(' ');
            }
            String key = PrismFeatures.updateNoticeKey(profile.vendor);
            if (key != null) {
                PrismShell.Res r = shell.setChecked("system", key, want ? "-1" : "0");
                if (!r.ok) msg.append("提醒键：").append(r.msg);
            }
            boolean on = readSwitch(s);
            return new PrismShell.Res(on == want,
                    on == want ? (want ? "已屏蔽系统更新（OTA 已冻结）" : "已恢复系统更新")
                            : "部分包未处理成功：" + msg);
        }
        if ("fiveg".equals(s.special)) {
            String[] keys = {"smart_5g_enabled", "user_5g_enabled"};
            for (String k : keys) {
                String cur = shell.get("global", k);
                if (cur == null) continue;
                boolean boolStyle = "true".equalsIgnoreCase(cur) || "false".equalsIgnoreCase(cur);
                String on = boolStyle ? "true" : "1";
                String off = boolStyle ? "false" : "0";
                return shell.setChecked("global", k, want ? on : off);
            }
            return new PrismShell.Res(false, "本机没有可用的 5G 开关键（澎湃OS 版本差异）");
        }
        return shell.setChecked(s.table, s.key, want ? s.on : s.off);
    }

    private void applyChoice(final String id, final String token) {
        if (!shell.any()) {
            toast("先激活 Shizuku 或授予写设置权限");
            return;
        }
        setStatus("处理中…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Res r;
                String label;
                if ("frame_interp".equals(id)) {
                    String v = "off".equals(token) ? "0:-1:0:0:0" : ("1:" + token + ":0:0:0");
                    label = "off".equals(token) ? "已关闭全局插帧" : "已开启全局插帧 " + token + "Hz（重启后需重设）";
                    r = shell.setChecked("system", "gamecube_frame_interpolation", v);
                } else if ("refresh_rate".equals(id)) {
                    // 文档值域是 1/2/3，但部分 ROM 直接收 60/90/120；先备份关联键，再按枚举/HZ 依次试
                    for (String k : new String[]{"peak_refresh_rate", "min_refresh_rate", "user_refresh_rate"}) {
                        shell.ensureBackup("system", k);
                    }
                    String v = "60".equals(token) ? "1" : ("90".equals(token) ? "2" : "3");
                    label = "已切到 " + token + "Hz（值域按真机校准）";
                    r = shell.setChecked("system", "oplus_customize_screen_refresh_rate", v);
                    if (!r.ok) {
                        r = shell.setChecked("system", "oplus_customize_screen_refresh_rate", token);
                    }
                } else if ("anim_scale".equals(id)) {
                    r = writeAnimScale(token);
                    label = "动画倍率：" + token + "×";
                } else {
                    r = new PrismShell.Res(false, "未知选项");
                    label = "";
                }
                final PrismShell.Res fr = r;
                final String fl = label;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        setStatus(fr.msg);
                        toast(fr.ok ? fl : fr.msg);
                        rebuild();
                    }
                });
            }
        });
    }

    private PrismShell.Res writeAnimScale(String token) {
        String[] keys = {"animator_duration_scale", "transition_animation_scale", "window_animation_scale"};
        PrismShell.Res last = new PrismShell.Res(true, "");
        for (String k : keys) {
            PrismShell.Res r = shell.setChecked("global", k, token);
            if (!r.ok) last = r;
        }
        return last;
    }

    // ---------------------------------------------------------------- 头部动作

    private void activateShizuku() {
        if (shell.shizuku()) {
            toast("Shizuku 已就绪");
            return;
        }
        if (!shizukuInstalled()) {
            helpDialog();
            return;
        }
        if (!RootShell.isInstalled()) {
            PrismDialog.confirm(this, "Shizuku 没有运行",
                    "打开 Shizuku，按它首页的步骤用「无线调试」启动服务；启动后回到这里点「激活 Shizuku」授权。",
                    "打开 Shizuku", new PrismDialog.Action() {
                        @Override
                        public void run() {
                            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
                            if (i != null) {
                                try {
                                    startActivity(i);
                                } catch (Throwable t) {
                                    toast("打不开：" + t.getMessage());
                                }
                            }
                        }
                    });
            return;
        }
        RootShell.requestPermission();
        setStatus("已发起 Shizuku 授权，请在弹窗里点允许");
    }

    private boolean shizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.manager", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void doGrantWss() {
        if (shell.wss()) {
            toast("已经有写设置权限");
            return;
        }
        if (!shell.shizuku()) {
            toast("需要先用 Shizuku 授权一次");
            helpDialog();
            return;
        }
        setStatus("正在授权…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PrismShell.Res r = shell.grantWss();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        toast(r.msg);
                        rebuild();
                    }
                });
            }
        });
    }

    private void chooseVendor() {
        final String[] labels = {"自动识别（推荐）", "小米 / 红米 · 澎湃OS", "vivo / iQOO · OriginOS",
                "OPPO / 一加 / 真我 · ColorOS", "通用 Android"};
        final String[] values = {"", "xiaomi", "vivo", "oppo", "generic"};
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            items.add(new PrismDialog.Item(labels[i], new PrismDialog.Action() {
                @Override
                public void run() {
                    PrismProfile.setOverride(PrismActivity.this, values[idx]);
                    setStatus("正在切换适配系统…");
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            PrismProfile p;
                            try {
                                p = PrismProfile.detect(PrismActivity.this);
                            } catch (Throwable t) {
                                p = PrismProfile.fallback();
                            }
                            final PrismProfile fp = p;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing()) return;
                                    profile = fp;
                                    rebuild();
                                    toast("适配系统：" + labels[idx]);
                                }
                            });
                        }
                    });
                }
            }));
        }
        PrismDialog.list(this, "适配系统", null, items);
    }

    // ---------------------------------------------------------------- 应用管理

    private static final class AppEntry {
        final String label, pkg;
        final boolean system;

        AppEntry(String label, String pkg, boolean system) {
            this.label = label;
            this.pkg = pkg;
            this.system = system;
        }
    }

    private interface AppAction {
        void run(AppEntry e);
    }

    private void pickApp(final String title, final boolean includeSystem, final AppAction action) {
        setStatus("读取应用列表…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> list = new ArrayList<AppEntry>();
                try {
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : apps) {
                        boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        if (sys && !includeSystem) continue;
                        String label = String.valueOf(pm.getApplicationLabel(ai));
                        list.add(new AppEntry(label, ai.packageName, sys));
                    }
                    Collections.sort(list, new Comparator<AppEntry>() {
                        @Override
                        public int compare(AppEntry a, AppEntry b) {
                            if (a.system != b.system) return a.system ? 1 : -1;
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        showAppDialog(title, list, action);
                    }
                });
            }
        }, "prism-apps").start();
    }

    private void showAppDialog(String title, final List<AppEntry> all, final AppAction action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(6), dp(14), 0);

        final EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setTextSize(13f);
        search.setTextColor(TEXT);
        search.setHintTextColor(DIM);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(round(CARD_HI, 12));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(search);

        final ListView listView = new ListView(this);
        listView.setBackgroundColor(0x00000000);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        final AppAdapter adapter = new AppAdapter(all);
        listView.setAdapter(adapter);
        box.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bodyH()));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        final Dialog dlg = PrismDialog.content(this, title, null,
                box, null, null, null, null, "取消", null);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final AppEntry e = adapter.shown.get(position);
                // 等应用选择器真正关掉再弹操作框，避免新窗压在旧窗下面（只剩「取消」）
                dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface dialog) {
                        action.run(e);
                    }
                });
                dlg.dismiss();
            }
        });
    }

    private final class AppAdapter extends BaseAdapter {
        final List<AppEntry> all;
        List<AppEntry> shown;

        AppAdapter(List<AppEntry> all) {
            this.all = all;
            this.shown = new ArrayList<AppEntry>(all);
        }

        void filter(String q) {
            String s = PrismShell.lc(q).trim();
            if (s.isEmpty()) {
                shown = new ArrayList<AppEntry>(all);
            } else {
                shown = new ArrayList<AppEntry>();
                for (AppEntry e : all) {
                    if (PrismShell.lc(e.label).contains(s) || PrismShell.lc(e.pkg).contains(s)) shown.add(e);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(PrismActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            AppEntry e = shown.get(position);
            TextView a = new TextView(PrismActivity.this);
            a.setText(e.label + (e.system ? "（系统）" : ""));
            a.setTextColor(TEXT);
            a.setTextSize(14f);
            row.addView(a);
            TextView b = new TextView(PrismActivity.this);
            b.setText(e.pkg);
            b.setTextColor(DIM);
            b.setTextSize(11f);
            row.addView(b);
            return row;
        }
    }

    private void appActionDialog(final AppEntry e) {
        final boolean self = PrismFeatures.isOwnPackage(e.pkg, getPackageName());
        if (self) {
            toast("不能操作本应用");
            return;
        }
        final boolean protect = PrismFeatures.protectedPackage(e.pkg);
        final String[] labels = {"冻结（图标置灰，可解冻）", "解冻", "强制停止",
                "清除数据（不可恢复）", "卸载（仅当前用户，恢复出厂可回）",
                "禁用组件（精准掐广告）", "启用组件",
                "提取安装包（APK 存到下载）", "打开系统应用信息"};
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            items.add(new PrismDialog.Item(labels[i], new PrismDialog.Action() {
                @Override
                public void run() {
                    appAction(which, e, protect);
                }
            }));
        }
        PrismDialog.list(this, e.label,
                e.pkg + (protect ? "\n\n⚠ 系统关键应用：冻结/卸载已被保护名单拦截。" : ""), items);
    }

    private void appAction(int which, AppEntry e, boolean protect) {
        switch (which) {
                        case 0:
                            if (protect) {
                                toast("系统关键应用，禁止冻结");
                                return;
                            }
                            runPkgOp("冻结", e, new PkgOp() {
                                @Override
                                public PrismShell.Res run() {
                                    return shell.freeze(e.pkg);
                                }
                            });
                            break;
                        case 1:
                            runPkgOp("解冻", e, new PkgOp() {
                                @Override
                                public PrismShell.Res run() {
                                    return shell.unfreeze(e.pkg);
                                }
                            });
                            break;
                        case 2:
                            runPkgOp("强制停止", e, new PkgOp() {
                                @Override
                                public PrismShell.Res run() {
                                    return shell.forceStop(e.pkg);
                                }
                            });
                            break;
                        case 3:
                            if (protect) {
                                toast("系统关键应用，禁止清数据");
                                return;
                            }
                            confirm("清除数据", "将清空 " + e.label + " 的全部数据，不可恢复。继续？", new Runnable() {
                                @Override
                                public void run() {
                                    runPkgOp("清除数据", e, new PkgOp() {
                                        @Override
                                        public PrismShell.Res run() {
                                            return shell.clearData(e.pkg);
                                        }
                                    });
                                }
                            });
                            break;
                        case 5:
                            if (protect) {
                                toast("系统关键应用，禁止禁用组件");
                                return;
                            }
                            componentDialog(e, false);
                            break;
                        case 6:
                            componentDialog(e, true);
                            break;
                        case 7:
                            extractApk(e);
                            break;
                        case 8:
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + e.pkg)));
                            } catch (Throwable t) {
                                toast("打不开应用信息：" + t.getMessage());
                            }
                            break;
                        default:
                            if (protect || e.system) {
                                toast("系统应用已拦截（防变砖）");
                                return;
                            }
                            confirm("卸载", "仅对当前用户卸载 " + e.label + "，/system 分区不动，恢复出厂可还原。继续？",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            runPkgOp("卸载", e, new PkgOp() {
                                                @Override
                                                public PrismShell.Res run() {
                                                    return shell.uninstall(e.pkg);
                                                }
                                            });
                                        }
                                    });
                            break;
        }
    }

    private interface PkgOp {
        PrismShell.Res run();
    }

    private void runPkgOp(final String name, final AppEntry e, final PkgOp op) {
        setStatus(name + "：" + e.label);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PrismShell.Res r = op.run();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        setStatus(name + " · " + r.msg);
                        toast(r.msg);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 特色玩机

    private void standbyDialog(final AppEntry e) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final int cur = shell.standbyBucket(e.pkg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final int[] buckets = {10, 20, 30, 40, 45};
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        for (final int b : buckets) {
                            items.add(new PrismDialog.Item(
                                    (b == cur ? "● " : "○ ") + PrismShell.bucketName(b),
                                    b == 10 ? "系统优先保活，最不容易被冻结"
                                            : (b == 45 ? "最严格，退后台很快被限制" : "数值 " + b),
                                    false, new PrismDialog.Action() {
                                @Override
                                public void run() {
                                    io.execute(new Runnable() {
                                        @Override
                                        public void run() {
                                            uiResult(shell.setStandbyBucket(e.pkg, b));
                                        }
                                    });
                                }
                            }));
                        }
                        PrismDialog.list(PrismActivity.this, "待机分组 · " + e.label,
                                "当前：" + PrismShell.bucketName(cur), items);
                    }
                });
            }
        });
    }

    private void appopsDialog(final AppEntry e) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String cur = shell.appops(e.pkg, "RUN_ANY_IN_BACKGROUND");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        String[] modes = {"allow", "ignore", "deny"};
                        String[] labels = {"allow（正常后台）", "ignore（退后台即掐停）", "deny（完全拒绝后台）"};
                        for (int i = 0; i < modes.length; i++) {
                            final String mode = modes[i];
                            items.add(new PrismDialog.Item(
                                    (mode.equalsIgnoreCase(cur) ? "● " : "○ ") + labels[i],
                                    "AppOps RUN_ANY_IN_BACKGROUND = " + mode, false,
                                    new PrismDialog.Action() {
                                        @Override
                                        public void run() {
                                            io.execute(new Runnable() {
                                                @Override
                                                public void run() {
                                                    uiResult(shell.setAppops(e.pkg,
                                                            "RUN_ANY_IN_BACKGROUND", mode));
                                                }
                                            });
                                        }
                                    }));
                        }
                        PrismDialog.list(PrismActivity.this, "后台管控 · " + e.label,
                                "当前：" + (cur == null ? "读不到" : cur), items);
                    }
                });
            }
        });
    }

    private void componentViewer(final AppEntry e) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                try {
                    PackageManager pm = getPackageManager();
                    int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                            | PackageManager.GET_RECEIVERS;
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(e.pkg, flags);
                    if (pi.activities != null) {
                        for (android.content.pm.ActivityInfo ai : pi.activities) {
                            addComponent(items, "Activity", ai.name, ai.exported, ai.enabled);
                        }
                    }
                    if (pi.services != null) {
                        for (android.content.pm.ServiceInfo si : pi.services) {
                            addComponent(items, "Service", si.name, si.exported, si.enabled);
                        }
                    }
                    if (pi.receivers != null) {
                        for (android.content.pm.ActivityInfo ri : pi.receivers) {
                            addComponent(items, "Receiver", ri.name, ri.exported, ri.enabled);
                        }
                    }
                } catch (Throwable t) {
                    items.add(new PrismDialog.Item("读取失败", String.valueOf(t), true, null));
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        PrismDialog.list(PrismActivity.this, "组件 · " + e.label, null, items);
                    }
                });
            }
        }, "prism-components").start();
    }

    private void addComponent(List<PrismDialog.Item> items, final String type,
                              final String name, boolean exported, boolean enabled) {
        String sub = type + (exported ? " · exported" : " · 私有")
                + (enabled ? "" : " · 已禁用");
        items.add(new PrismDialog.Item(name, sub, false, new PrismDialog.Action() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("component", name));
                        toast("已复制：" + name);
                    }
                } catch (Throwable t) {
                    toast("复制失败：" + t.getMessage());
                }
            }
        }));
    }

    // ---------------------------------------------------------------- 特色玩机（v2）

    private String secure(String key) {
        try {
            return Settings.Secure.getString(getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private boolean systemOf(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void jump(String action) {
        jump(action, null);
    }

    private void jump(String action, Uri data) {
        try {
            Intent i = new Intent(action);
            if (data != null) i.setData(data);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("本机没有对应的设置页");
        }
    }

    /** 隐私体检：无障碍 / 通知监听 / 设备管理员 / 悬浮窗。 */
    private void privacyAuditDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                final java.util.HashSet<String> seen = new java.util.HashSet<String>();
                collectHolders(items, seen, "无障碍", secure("enabled_accessibility_services"),
                        Settings.ACTION_ACCESSIBILITY_SETTINGS);
                collectHolders(items, seen, "通知监听", secure("enabled_notification_listeners"),
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                    if (dpm != null && dpm.getActiveAdmins() != null) {
                        for (ComponentName cn : dpm.getActiveAdmins()) {
                            if (cn != null) {
                                addHolder(items, seen, "设备管理员", cn.getPackageName(),
                                        Settings.ACTION_SECURITY_SETTINGS);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 悬浮窗授权明细只有 appops 能给出准确的“已允许”列表
                try {
                    if (shell.shizuku()) {
                        PrismShell.Out o = shell.exec("cmd appops query-op SYSTEM_ALERT_WINDOW allow");
                        if (o.ok) {
                            for (String line : o.text.split("\n")) {
                                String p = line.trim();
                                int c = p.indexOf(':');
                                if (c > 0) p = p.substring(0, c).trim();
                                if (p.indexOf('.') > 0 && p.indexOf(' ') < 0) {
                                    addHolder(items, seen, "悬浮窗", p,
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (items.isEmpty()) {
                            PrismDialog.info(PrismActivity.this, "隐私体检",
                                    "没有发现无障碍 / 通知监听 / 设备管理员 / 悬浮窗授权。\n"
                                            + "保持现状即可；装了新应用可以再来体检一次。", null, null);
                            return;
                        }
                        PrismDialog.list(PrismActivity.this, "隐私体检",
                                "共 " + items.size() + " 项。⚠ = 第三方持有，建议核查；点条目直达系统设置撤销。",
                                items);
                    }
                });
            }
        });
    }

    private void collectHolders(List<PrismDialog.Item> items, java.util.HashSet<String> seen,
                                String kind, String raw, String action) {
        if (TextUtils.isEmpty(raw)) return;
        for (String part : raw.split(":")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int slash = s.indexOf('/');
            String pkg = slash > 0 ? s.substring(0, slash) : s;
            addHolder(items, seen, kind, pkg, action);
        }
    }

    private void addHolder(List<PrismDialog.Item> items, java.util.HashSet<String> seen,
                           String kind, final String pkg, final String action) {
        if (TextUtils.isEmpty(pkg) || !seen.add(kind + "|" + pkg)) return;
        String label = labelOf(pkg);
        boolean system = systemOf(pkg);
        boolean self = getPackageName().equals(pkg);
        String mark = self ? "● " : (system ? "○ " : "⚠ ");
        String sub = pkg + (self ? "（本应用）" : (system ? "（系统应用）" : "（第三方，建议核查）"));
        items.add(new PrismDialog.Item(mark + kind + " · " + label, sub, !system && !self,
                new PrismDialog.Action() {
                    @Override
                    public void run() {
                        if (Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(action)) {
                            jump(action, Uri.parse("package:" + pkg));
                        } else {
                            jump(action);
                        }
                    }
                }));
    }

    /** 权限审计：读 dumpsys 的 runtime permissions，可逐条撤销。 */
    private void appPermsDialog(final AppEntry e) {
        setStatus("正在读取 " + e.label + " 的权限…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<String[]> perms = PrismShell.parseRuntimePerms(shell.packageDump(e.pkg));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (perms.isEmpty()) {
                            PrismDialog.info(PrismActivity.this, "权限审计 · " + e.label,
                                    "没有读到运行时权限。可能该应用只有普通权限，"
                                            + "或需要 Shizuku 才能读取 dumpsys。", null, null);
                            return;
                        }
                        List<PrismDialog.Item> granted = new ArrayList<PrismDialog.Item>();
                        List<PrismDialog.Item> rest = new ArrayList<PrismDialog.Item>();
                        for (final String[] p : perms) {
                            String name = PrismShell.shortPerm(p[0]);
                            if ("1".equals(p[1])) {
                                granted.add(new PrismDialog.Item(name, "已授予 · 点按撤销", true,
                                        new PrismDialog.Action() {
                                            @Override
                                            public void run() {
                                                PrismDialog.confirm(PrismActivity.this, "撤销 " + PrismShell.shortPerm(p[0]),
                                                        p[0] + "\n撤销后该功能可能失效，可随时在系统设置里重新授予。",
                                                        "撤销", new PrismDialog.Action() {
                                                            @Override
                                                            public void run() {
                                                                io.execute(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        uiResult(shell.revokePermission(e.pkg, p[0]));
                                                                    }
                                                                });
                                                            }
                                                        });
                                            }
                                        }));
                            } else {
                                rest.add(new PrismDialog.Item(name, "未授予", false, null));
                            }
                        }
                        List<PrismDialog.Item> all = new ArrayList<PrismDialog.Item>();
                        all.addAll(granted);
                        all.addAll(rest);
                        PrismDialog.list(PrismActivity.this, "权限审计 · " + e.label,
                                "已授予 " + granted.size() + " 项 / 共 " + all.size() + " 项运行时权限。",
                                all);
                    }
                });
            }
        });
    }

    /** 后台清道夫：kill-all + trim-caches，给出前后可用内存。 */
    private void runBgCleaner() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final long before = availMem(am);
        setStatus("正在清理后台…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Res a = shell.killBackground();
                PrismShell.Res b = shell.trimCaches();
                final long after = availMem(am);
                final String msg = "清理前可用：" + mb(before) + "\n清理后可用：" + mb(after)
                        + "\n\n" + a.msg + "\n" + b.msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        setStatus("后台已清理");
                        PrismDialog.info(PrismActivity.this, "后台清道夫", msg, null, null);
                    }
                });
            }
        });
    }

    private long availMem(ActivityManager am) {
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    private String mb(long b) {
        return String.format(Locale.US, "%.0f MB", b / 1024.0 / 1024.0);
    }

    /** 刷新率锁定（AOSP 键）。 */
    private void refreshDialog() {
        if (!shell.any()) {
            toast("需要 Shizuku 或 WSS 授权");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String mn = shell.refreshMin();
                final String pk = shell.refreshPeak();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        items.add(new PrismDialog.Item("60Hz · 省电", "锁 60，最稳最省", false,
                                applyRefresh("60.0", "60.0")));
                        items.add(new PrismDialog.Item("90Hz · 平衡", "日常滑动够顺", false,
                                applyRefresh("90.0", "90.0")));
                        items.add(new PrismDialog.Item("120Hz · 满帧", "最顺滑，耗电更高", false,
                                applyRefresh("120.0", "120.0")));
                        items.add(new PrismDialog.Item("恢复系统自动", "撤销 min/peak 限制", false,
                                new PrismDialog.Action() {
                                    @Override
                                    public void run() {
                                        io.execute(new Runnable() {
                                            @Override
                                            public void run() {
                                                uiResult(shell.resetRefresh());
                                            }
                                        });
                                    }
                                }));
                        PrismDialog.list(PrismActivity.this, "刷新率锁定",
                                "当前 min=" + (mn == null ? "未设置" : mn)
                                        + "，peak=" + (pk == null ? "未设置" : pk)
                                        + "。仅对 AOSP 键生效，部分 ROM 会在亮屏后改回。", items);
                    }
                });
            }
        });
    }

    private PrismDialog.Action applyRefresh(final String min, final String peak) {
        return new PrismDialog.Action() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        uiResult(shell.setRefreshRange(min, peak));
                    }
                });
            }
        };
    }

    /** 无线调试开关。 */
    private void wifiAdbDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String cur = shell.wifiAdb();
                final String port = shell.adbPort();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        items.add(new PrismDialog.Item("开启无线调试", "Android 11+ 免数据线", false,
                                setWifiAdb(true)));
                        items.add(new PrismDialog.Item("关闭无线调试", "用完建议关掉", false,
                                setWifiAdb(false)));
                        PrismDialog.list(PrismActivity.this, "无线调试",
                                "当前：" + ("1".equals(cur) ? "已开启" : "未开启")
                                        + "\nadb tcp 端口：" + (TextUtils.isEmpty(port) ? "未开放" : port)
                                        + "\n\n开启后在开发者选项里用「无线调试」配对；"
                                        + "部分 ROM 需要重启 adbd 才生效。", items);
                    }
                });
            }
        });
    }

    private PrismDialog.Action setWifiAdb(final boolean on) {
        return new PrismDialog.Action() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        uiResult(shell.setWifiAdb(on));
                    }
                });
            }
        };
    }

    /** 硬件档案（只读）。 */
    private void hwProfileDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder b = new StringBuilder();
                b.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                if (Build.VERSION.SDK_INT >= 31 && !TextUtils.isEmpty(Build.SOC_MODEL)
                        && !"unknown".equals(Build.SOC_MODEL)) {
                    b.append("SoC：").append(Build.SOC_MODEL).append('\n');
                }
                b.append("平台：").append(Build.HARDWARE).append(" / ").append(Build.BOARD).append('\n');
                b.append("CPU：").append(Runtime.getRuntime().availableProcessors()).append(" 核\n");
                b.append("系统：Android ").append(Build.VERSION.RELEASE)
                        .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    b.append("内存：").append(mb(mi.totalMem)).append(" 总 / ")
                            .append(mb(mi.availMem)).append(" 可用\n");
                }
                try {
                    StatFs st = new StatFs(Environment.getDataDirectory().getAbsolutePath());
                    b.append("存储：").append(gb(st.getTotalBytes())).append(" 总 / ")
                            .append(gb(st.getAvailableBytes())).append(" 可用\n");
                } catch (Throwable ignored) {
                }
                DisplayMetrics dm = getResources().getDisplayMetrics();
                b.append("屏幕：").append(dm.widthPixels).append(" × ").append(dm.heightPixels)
                        .append(" @ ").append(dm.densityDpi).append("dpi\n");
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        Display d = getDisplay();
                        if (d != null) {
                            b.append("刷新率：")
                                    .append(String.format(Locale.US, "%.0f Hz", d.getRefreshRate()))
                                    .append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
                final String text = b.toString().trim();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        PrismDialog.info(PrismActivity.this, "硬件档案", text, null, null);
                    }
                });
            }
        });
    }

    private String gb(long b) {
        return String.format(Locale.US, "%.0f GB", b / 1024.0 / 1024.0 / 1024.0);
    }

    /** 应用选择列表高度：竖屏 360dp，矮屏按比例收，避免把「取消」挤到底部之外。 */
    private int bodyH() {
        int h = getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(180), Math.min(dp(360), (int) (h * 0.42f)));
    }

    private void dozeDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String state = shell.dozeState();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        items.add(new PrismDialog.Item("进入强制 Doze", "灭屏后生效；插电时可能被推迟",
                                false, new PrismDialog.Action() {
                            @Override
                            public void run() {
                                io.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        uiResult(shell.doze(true));
                                    }
                                });
                            }
                        }));
                        items.add(new PrismDialog.Item("退出 Doze", "恢复到正常待机策略",
                                false, new PrismDialog.Action() {
                            @Override
                            public void run() {
                                io.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        uiResult(shell.doze(false));
                                    }
                                });
                            }
                        }));
                        PrismDialog.list(PrismActivity.this, "强制深度睡眠",
                                "当前：" + (state.isEmpty() ? "未知" : state), items);
                    }
                });
            }
        });
    }

    private void darkDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String cur = shell.uiMode();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String[] modes = {"yes", "no", "auto"};
                        String[] labels = {"强制深色", "强制浅色", "跟随系统"};
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        for (int i = 0; i < modes.length; i++) {
                            final String mode = modes[i];
                            items.add(new PrismDialog.Item(
                                    (mode.equalsIgnoreCase(cur) ? "● " : "○ ") + labels[i],
                                    "cmd uimode night " + mode, false, new PrismDialog.Action() {
                                @Override
                                public void run() {
                                    io.execute(new Runnable() {
                                        @Override
                                        public void run() {
                                            uiResult(shell.setUiMode(mode));
                                        }
                                    });
                                }
                            }));
                        }
                        PrismDialog.list(PrismActivity.this, "强制深色模式",
                                "当前：" + (cur.isEmpty() ? "未知" : cur), items);
                    }
                });
            }
        });
    }

    private void displayDialog() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String size = shell.wmSize();
                final String density = shell.wmDensity();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        items.add(new PrismDialog.Item("恢复原生分辨率 / 密度",
                                "wm size reset && wm density reset", false,
                                new PrismDialog.Action() {
                                    @Override
                                    public void run() {
                                        io.execute(new Runnable() {
                                            @Override
                                            public void run() {
                                                uiResult(shell.resetWm());
                                            }
                                        });
                                    }
                                }));
                        items.add(new PrismDialog.Item("1080 × 2400 @ 420dpi",
                                "主流 1080P，省电与清晰度平衡", false,
                                new PrismDialog.Action() {
                                    @Override
                                    public void run() {
                                        applyDisplay("1080x2400", 420);
                                    }
                                }));
                        items.add(new PrismDialog.Item("720 × 1600 @ 320dpi",
                                "低分辨率，游戏帧率更高、更省电", false,
                                new PrismDialog.Action() {
                                    @Override
                                    public void run() {
                                        applyDisplay("720x1600", 320);
                                    }
                                }));
                        items.add(new PrismDialog.Item("1440 × 3200 @ 560dpi",
                                "2K 超清，OLED 机型更细腻（更耗电）", false,
                                new PrismDialog.Action() {
                                    @Override
                                    public void run() {
                                        applyDisplay("1440x3200", 560);
                                    }
                                }));
                        PrismDialog.list(PrismActivity.this, "分辨率 / 密度",
                                "当前：" + (size.isEmpty() ? "未知" : size)
                                        + " / " + (density.isEmpty() ? "未知" : density)
                                        + "\n10 秒内不点「保持」会自动恢复。",
                                items);
                    }
                });
            }
        });
    }

    private void applyDisplay(final String size, final int dpi) {
        PrismDialog.confirmDanger(this, "应用显示参数",
                "将设置为 " + size + " / " + dpi + "dpi。\n"
                        + "应用后 10 秒内点「保持」，否则自动恢复原生值。", "应用", new PrismDialog.Action() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        PrismShell.Res r = shell.setWmSize(size);
                        if (r.ok) r = shell.setWmDensity(dpi);
                        final PrismShell.Res fr = r;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(fr.msg);
                                if (!fr.ok) {
                                    io.execute(new Runnable() {
                                        @Override
                                        public void run() {
                                            shell.resetWm();
                                        }
                                    });
                                    return;
                                }
                                final Handler h = new Handler(Looper.getMainLooper());
                                final Runnable rollback = new Runnable() {
                                    @Override
                                    public void run() {
                                        io.execute(new Runnable() {
                                            @Override
                                            public void run() {
                                                shell.resetWm();
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        toast("已自动恢复原生显示参数");
                                                    }
                                                });
                                            }
                                        });
                                    }
                                };
                                h.postDelayed(rollback, 10000);
                                PrismDialog.confirm(PrismActivity.this, "已应用",
                                        "10 秒后自动恢复原生分辨率/密度。\n如果画面正常，点「保持」。",
                                        "保持", new PrismDialog.Action() {
                                            @Override
                                            public void run() {
                                                h.removeCallbacks(rollback);
                                                toast("已保持新显示参数");
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });
    }

    private void netDiagDialog() {
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        items.add(new PrismDialog.Item("完整三连测", "ping 网关 / DNS / 公网 223.5.5.5 + DNS 配置",
                false, new PrismDialog.Action() {
            @Override
            public void run() {
                runNetDiag("ping -c 3 -W 2 223.5.5.5; "
                        + "echo; ping -c 3 -W 2 www.baidu.com; "
                        + "echo; echo '--- DNS ---'; getprop | grep -i dns");
            }
        }));
        items.add(new PrismDialog.Item("只测公网 IP", "排除 DNS 因素，判断是不是根本没网",
                false, new PrismDialog.Action() {
            @Override
            public void run() {
                runNetDiag("ping -c 4 -W 2 223.5.5.5");
            }
        }));
        items.add(new PrismDialog.Item("只测域名解析", "能 ping 通 IP 但域名失败 = DNS 问题",
                false, new PrismDialog.Action() {
            @Override
            public void run() {
                runNetDiag("ping -c 3 -W 2 www.baidu.com; echo; getprop | grep -i dns");
            }
        }));
        PrismDialog.list(this, "网络诊断", null, items);
    }

    private void runNetDiag(final String cmd) {
        setStatus("网络诊断中…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Out o = shell.exec(cmd);
                final String text = o.ok || !o.text.isEmpty()
                        ? o.text : "命令执行失败（需要 Shizuku）";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView tv = new TextView(PrismActivity.this);
                        tv.setText(text);
                        tv.setTextColor(TEXT);
                        tv.setTextSize(11.5f);
                        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                        tv.setTextIsSelectable(true);
                        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                        ScrollView sc = new ScrollView(PrismActivity.this);
                        sc.addView(tv);
                        sc.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, bodyH()));
                        PrismDialog.content(PrismActivity.this, "诊断结果", null, sc,
                                null, null, null, null, "关闭", null);
                    }
                });
            }
        });
    }

    private void networkDialog(final AppEntry e) {
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        items.add(new PrismDialog.Item("断开网络", new PrismDialog.Action() {
            @Override
            public void run() {
                netOp(e, true);
            }
        }));
        items.add(new PrismDialog.Item("恢复网络", new PrismDialog.Action() {
            @Override
            public void run() {
                netOp(e, false);
            }
        }));
        PrismDialog.list(this, e.label, "断开后前后台全部断网。", items);
    }

    private void netOp(final AppEntry e, final boolean block) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Out o = shell.exec("cmd connectivity set-package-networking-enabled "
                        + e.pkg + " " + !block);
                final PrismShell.Res r = new PrismShell.Res(o.ok,
                        o.ok ? (block ? "已断开 " + e.label + " 的网络" : "已恢复网络") : o.text);
                uiResult(r);
            }
        });
    }

    // ---------------------------------------------------------------- 其余功能

    private void demoDialog() {
        String[] labels = {"进入演示模式", "显示秒钟", "隐藏状态栏图标", "退出演示模式"};
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            items.add(new PrismDialog.Item(labels[i], new PrismDialog.Action() {
                @Override
                public void run() {
                    demoOp(which);
                }
            }));
        }
        PrismDialog.list(this, "状态栏演示模式", null, items);
    }

    private void demoOp(int which) {
        String script;
        switch (which) {
            case 0:
                script = "settings put global sysui_demo_allowed 1 && "
                        + "am broadcast -a com.android.systemui.demo -e command enter";
                break;
            case 1:
                script = "settings put global sysui_demo_allowed 1 && "
                        + "am broadcast -a com.android.systemui.demo -e command enter && "
                        + "am broadcast -a com.android.systemui.demo -e command clock -e seconds show";
                break;
            case 2:
                script = "settings put global sysui_demo_allowed 1 && "
                        + "am broadcast -a com.android.systemui.demo -e command enter && "
                        + "am broadcast -a com.android.systemui.demo -e command status "
                        + "-e volume hide -e wifi hide -e battery hide -e mobile hide -e notifications hide";
                break;
            default:
                script = "am broadcast -a com.android.systemui.demo -e command exit";
                break;
        }
        final String cmd = script;
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Out o = shell.exec(cmd);
                uiResult(new PrismShell.Res(o.ok, o.ok ? "广播已发送" : ("需要 Shizuku：" + o.text)));
            }
        });
    }

    private void renameDialog() {
        String cur = shell.get("global", "vivo_user_define_device_name");
        PrismDialog.input(this, "手机改名", null,
                "例如：棱镜测试机", cur, false, "写入", new PrismDialog.TextAction() {
                    @Override
                    public void run(String text) {
                        final String name = text.trim();
                        if (TextUtils.isEmpty(name)) {
                            toast("名称不能为空");
                            return;
                        }
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                uiResult(shell.setChecked("global",
                                        "vivo_user_define_device_name", name));
                            }
                        });
                    }
                });
    }

    private void fingerprintDialog() {
        setStatus("枚举指纹动画 overlay…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                PrismShell.Out o = shell.exec("cmd overlay list");
                final List<String> names = new ArrayList<String>();
                final List<Boolean> enabled = new ArrayList<Boolean>();
                for (String line : o.text.split("\n")) {
                    String s = line.trim();
                    if (!s.contains("com.android.systemui.overlay.fingerprint.anim")) continue;
                    boolean on = s.startsWith("[x]");
                    String pkg = s.replace("[x]", "").replace("[ ]", "").trim();
                    names.add(pkg);
                    enabled.add(on);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (names.isEmpty()) {
                            toast("本机没有指纹动画 overlay（非 ColorOS 或 ROM 已裁剪）");
                            setStatus("未找到指纹动画 overlay");
                            return;
                        }
                        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
                        for (int i = 0; i < names.size(); i++) {
                            final int which = i;
                            items.add(new PrismDialog.Item(
                                    (enabled.get(i) ? "● " : "○ ")
                                            + names.get(i).replace(
                                            "com.android.systemui.overlay.fingerprint.anim.", ""),
                                    names.get(i), false, new PrismDialog.Action() {
                                @Override
                                public void run() {
                                    applyFingerprint(names, which);
                                }
                            }));
                        }
                        PrismDialog.list(PrismActivity.this, "指纹动画",
                                "当前启用的项前有 ●；切换时会自动关闭同族其它 overlay。", items);
                    }
                });
            }
        });
    }

    private void applyFingerprint(final List<String> all, final int which) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String target = all.get(which);
                StringBuilder log = new StringBuilder();
                for (int i = 0; i < all.size(); i++) {
                    String p = all.get(i);
                    PrismShell.Out o = shell.exec("cmd overlay " + (i == which ? "enable " : "disable ") + p);
                    if (!o.ok) log.append(p).append(' ');
                }
                final PrismShell.Res r = new PrismShell.Res(log.length() == 0,
                        log.length() == 0 ? "已切换指纹动画" : "部分 overlay 切换失败：" + log);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(r.msg);
                        toast(r.msg);
                    }
                });
            }
        });
    }

    private void guideDialog() {
        final List<PrismFeatures.Guide> guides = PrismFeatures.adGuides(profile.vendor);
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (final PrismFeatures.Guide g : guides) {
            items.add(new PrismDialog.Item(g.label, g.pkg, false, new PrismDialog.Action() {
                @Override
                public void run() {
                    openGuide(g);
                }
            }));
        }
        PrismDialog.list(this, "广告关闭向导", null, items);
    }

    /**
     * 先给步骤、再让用户点「打开」：跳转后本页退到后台，若先跳转，步骤框要等用户返回才看得到，
     * 顺序反了就等于没引导。
     */
    private void openGuide(final PrismFeatures.Guide g) {
        PrismDialog.info(this, g.label, g.steps, "打开 " + g.label, new PrismDialog.Action() {
            @Override
            public void run() {
                launchGuide(g);
            }
        });
    }

    private void launchGuide(final PrismFeatures.Guide g) {
        Intent launch = null;
        try {
            launch = getPackageManager().getLaunchIntentForPackage(g.pkg);
        } catch (Throwable ignored) {
        }
        if (launch != null) {
            try {
                startActivity(launch);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (g.component != null && shell.shizuku()) {
            PrismShell.Res r = shell.startComponent(g.component, g.pkg);
            if (r.ok) return;
        }
        if (shell.shizuku()) {
            PrismShell.Out o = shell.exec("monkey -p " + g.pkg + " -c android.intent.category.LAUNCHER 1");
            if (o.ok) return;
        }
        toast("打不开 " + g.label + "（系统里没有这个入口）");
    }

    private void jumpsDialog() {
        final List<PrismFeatures.Jump> jumps = PrismFeatures.settingsJumps(profile.vendor);
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (final PrismFeatures.Jump j : jumps) {
            items.add(new PrismDialog.Item(j.label,
                    j.action != null ? j.action : j.component, false, new PrismDialog.Action() {
                @Override
                public void run() {
                    openJump(j);
                }
            }));
        }
        PrismDialog.list(this, "系统设置直达", null, items);
    }

    private void openJump(final PrismFeatures.Jump j) {
        if (j.action != null) {
            // 系统 Action（如设备控制器）不需要 shell，直接起 Activity
            try {
                startActivity(new Intent(j.action));
                setStatus("已打开：" + j.label);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Throwable ignored) {
                }
                toast("该 ROM 没有这个页面，已退回系统设置");
            }
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PrismShell.Res r = shell.startComponent(j.component, null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!r.ok) toast("打开失败，已尝试兜底：" + r.msg);
                        setStatus(j.label + " · " + r.msg);
                    }
                });
            }
        });
    }

    private void confirmRestoreAll() {
        confirm("恢复全部修改", "将把本 App 改过的 " + shell.backupCount()
                + " 项设置写回 original_* 备份（不卸载应用、不解冻应用）。继续？", new Runnable() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        final PrismShell.Res r = shell.restoreAll();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus(r.msg);
                                toast(r.msg);
                                rebuild();
                            }
                        });
                    }
                });
            }
        });
    }

    private void helpDialog() {
        String msg = "1) 安装并打开 Shizuku（moe.shizuku.manager），按首页步骤用「无线调试」启动服务。\n"
                + "2) 回到本页点「激活 Shizuku」，在系统弹窗里允许。\n"
                + "3) 点「授予免 Root 写设置」，App 会执行 pm grant WRITE_SECURE_SETTINGS；之后即使 Shizuku 掉线，"
                + "设置类开关仍可用。\n\n"
                + "没有 Shizuku 时：只能用跳转/广告向导类功能；冻结、卸载、演示模式、分应用断网需要 shell 权限。\n\n"
                + "也可以用电脑 adb 授权：\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        PrismDialog.info(this, "通道与授权", msg, "打开 Shizuku", new PrismDialog.Action() {
            @Override
            public void run() {
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
                if (i != null) {
                    try {
                        startActivity(i);
                    } catch (Throwable t) {
                        toast("打不开：" + t.getMessage());
                    }
                } else {
                    toast("未安装 Shizuku，请先安装");
                }
            }
        });
    }

    // ---------------------------------------------------------------- 小工具

    // ---------------------------------------------------------------- 电量 / 通知 / 壁纸 / 暗码

    private void batteryDialog() {
        if (!shell.shizuku()) {
            toast("电量模拟需要 Shizuku（dumpsys battery 属于 shell 命令）");
            return;
        }
        final int[] levels = {100, 80, 50, 20};
        final String[] labels = {"显示 100%", "显示 80%", "显示 50%", "显示 20%", "恢复真实电量"};
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (int i = 0; i < labels.length; i++) {
            final boolean reset = i >= levels.length;
            final int level = reset ? -1 : levels[i];
            items.add(new PrismDialog.Item(labels[i], new PrismDialog.Action() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            uiResult(reset ? shell.resetBattery() : shell.setBatteryLevel(level));
                        }
                    });
                }
            }));
        }
        PrismDialog.list(this, "电量魔法师", "只改显示读数。", items);
    }

    private void notificationsDialog() {
        if (!PrismNotifications.access(this)) {
            PrismDialog.confirm(this, "需要「通知使用权」",
                    "到 设置 → 通知使用权 勾选「棱镜玩机」。",
                    "去授权", new PrismDialog.Action() {
                        @Override
                        public void run() {
                            try {
                                startActivity(PrismNotifications.accessIntent());
                            } catch (Throwable t) {
                                toast("打不开授权页：" + t.getMessage());
                            }
                        }
                    });
            return;
        }
        setStatus("读取通知…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<PrismNotificationService.Item> list = PrismNotificationService.snapshot();
                final int hidden = PrismNotifications.hiddenCount(PrismActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        showNotificationList(list, hidden);
                    }
                });
            }
        });
    }

    private void showNotificationList(final List<PrismNotificationService.Item> list, final int hidden) {
        if (list.isEmpty()) {
            PrismDialog.list(this, "当前没有可见通知",
                    PrismNotificationService.connected()
                            ? "通知栏是空的；下拉通知后回来再试。"
                            : "通知服务还没连接：确认系统「通知使用权」里已勾选本应用，然后重进本页。",
                    new ArrayList<PrismDialog.Item>(), "已隐藏 " + hidden + " 项",
                    new PrismDialog.Action() {
                        @Override
                        public void run() {
                            hiddenNotificationsDialog();
                        }
                    });
            return;
        }
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (final PrismNotificationService.Item it : list) {
            String text = it.text == null ? "" : it.text.replace('\n', ' ');
            if (text.length() > 48) text = text.substring(0, 48) + "…";
            items.add(new PrismDialog.Item(it.label(), text, false, new PrismDialog.Action() {
                @Override
                public void run() {
                    notificationItemDialog(it);
                }
            }));
        }
        PrismDialog.list(this, "选择要隐藏的通知（" + list.size() + "）",
                null, items, "已隐藏 " + hidden + " 项",
                new PrismDialog.Action() {
                    @Override
                    public void run() {
                        hiddenNotificationsDialog();
                    }
                });
    }

    private void notificationItemDialog(final PrismNotificationService.Item item) {
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        items.add(new PrismDialog.Item("隐藏 3 年（可恢复）", new PrismDialog.Action() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        uiResult(PrismNotifications.hide(PrismActivity.this, item));
                    }
                });
            }
        }));
        items.add(new PrismDialog.Item("恢复显示（100ms）", new PrismDialog.Action() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        uiResult(PrismNotifications.restore(PrismActivity.this, findHidden(item.key)));
                    }
                });
            }
        }));
        PrismDialog.list(this, item.label(), item.text == null ? "" : item.text, items);
    }

    private PrismNotifications.Record findHidden(String key) {
        for (PrismNotifications.Record r : PrismNotifications.hidden(this)) {
            if (r.key.equals(key)) return r;
        }
        return new PrismNotifications.Record(key, key);
    }

    private void hiddenNotificationsDialog() {
        final List<PrismNotifications.Record> list = PrismNotifications.hidden(this);
        if (list.isEmpty()) {
            toast("没有已隐藏的通知");
            return;
        }
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (final PrismNotifications.Record r : list) {
            items.add(new PrismDialog.Item(r.label, "点一下恢复显示", false, new PrismDialog.Action() {
                @Override
                public void run() {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            uiResult(PrismNotifications.restore(PrismActivity.this, r));
                        }
                    });
                }
            }));
        }
        PrismDialog.list(this, "已隐藏的通知（" + list.size() + "）", null, items);
    }

    private void openScene() {
        Intent i = null;
        try {
            i = getPackageManager().getLaunchIntentForPackage("com.omarea.vtools");
        } catch (Throwable ignored) {
        }
        if (i != null) {
            try {
                startActivity(i);
                setStatus("已启动 Scene");
                return;
            } catch (Throwable ignored) {
            }
        }
        PrismDialog.confirm(this, "未安装 Scene",
                "安装后可从这里直接拉起。", "去安装", new PrismDialog.Action() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.omarea.vtools")));
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.coolapk.com/apk/com.omarea.vtools")));
                            } catch (Throwable t2) {
                                toast("没有可用的应用商店/浏览器");
                            }
                        }
                    }
                });
    }

    private void openWallpaperStore() {
        for (String pkg : PrismFeatures.wallpaperPackages(profile.vendor)) {
            Intent i = null;
            try {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
            } catch (Throwable ignored) {
            }
            if (i != null) {
                try {
                    startActivity(i);
                    setStatus("已打开壁纸商店：" + pkg);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            startActivity(new Intent("android.settings.WALLPAPER_SETTINGS"));
            setStatus("已打开系统壁纸设置");
        } catch (Throwable t) {
            toast("本机没有可用的壁纸商店 / 系统壁纸设置");
        }
    }

    private void dialDialog() {
        final List<PrismFeatures.DialCode> codes = PrismFeatures.dialCodes();
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        for (final PrismFeatures.DialCode c : codes) {
            items.add(new PrismDialog.Item(c.label, c.code, false, new PrismDialog.Action() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_DIAL,
                                Uri.parse("tel:" + Uri.encode(c.code)));
                        startActivity(i);
                        toast("已填入拨号盘，请按拨号键");
                    } catch (Throwable t) {
                        toast("打不开拨号盘：" + t.getMessage());
                    }
                }
            }));
        }
        PrismDialog.list(this, "拨号暗码", null, items);
    }

    private void extractApk(final AppEntry e) {
        setStatus("提取安装包：" + e.label);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    ApplicationInfo ai = getPackageManager().getApplicationInfo(e.pkg, 0);
                    File src = new File(ai.sourceDir);
                    String safe = e.label.replaceAll("[\\\\/:*?\"<>|]", "_");
                    String name = safe + "-" + e.pkg + ".apk";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE,
                                "application/vnd.android.package-archive");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/Prism");
                        Uri uri = getContentResolver().insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (uri == null) throw new java.io.IOException("MediaStore 插入失败");
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new java.io.IOException("无法写入下载目录");
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        msg = "已保存到 下载/Prism/" + name;
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "Prism");
                        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("建目录失败");
                        File dst = new File(dir, name);
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = new FileOutputStream(dst)) {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        msg = "已保存到 " + dst.getAbsolutePath();
                    }
                } catch (Throwable t) {
                    msg = "提取失败：" + t.getMessage();
                }
                final String text = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(text);
                        toast(text);
                    }
                });
            }
        }, "prism-apk").start();
    }

    private void componentDialog(final AppEntry e, final boolean enable) {
        if (!shell.shizuku()) {
            toast("组件级禁用需要 Shizuku");
            return;
        }
        PrismDialog.input(this, (enable ? "启用" : "禁用") + "组件 · " + e.label, null,
                "Activity/Service 类名，如 .AdActivity 或 com.x.AdService", "", false,
                enable ? "启用" : "禁用", new PrismDialog.TextAction() {
                    @Override
                    public void run(String text) {
                        final String cls = text.trim();
                        if (cls.isEmpty()) {
                            toast("组件名不能为空");
                            return;
                        }
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                uiResult(shell.setComponent(e.pkg, cls, enable));
                            }
                        });
                    }
                });
    }

    private void confirm(String title, String msg, final Runnable ok) {
        PrismDialog.confirm(this, title, msg, "继续", new PrismDialog.Action() {
            @Override
            public void run() {
                ok.run();
            }
        });
    }

    private void uiResult(final PrismShell.Res r) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                setStatus(r.msg);
                toast(r.msg);
            }
        });
    }

    private void setStatus(String s) {
        if (statusText == null) return;
        boolean show = !TextUtils.isEmpty(s);
        statusText.setText(show ? s : "");
        statusText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s == null ? "" : s, Toast.LENGTH_SHORT).show();
    }

    /** 顶栏状态胶囊的出口：通道详情 + 三个授权动作。 */
    private void channelDialog() {
        List<PrismDialog.Item> items = new ArrayList<PrismDialog.Item>();
        items.add(new PrismDialog.Item(
                shell.shizuku() ? "Shizuku：已就绪（点此重新授权）" : "激活 Shizuku",
                "可执行 settings / pm / am / cmd / dumpsys",
                false, new PrismDialog.Action() {
            @Override
            public void run() {
                activateShizuku();
            }
        }));
        items.add(new PrismDialog.Item(
                shell.wss() ? "写设置权限：已授予" : "授予免 Root 写设置",
                "只放行 settings 读写；冻结/卸载仍需 Shizuku",
                false, new PrismDialog.Action() {
            @Override
            public void run() {
                doGrantWss();
            }
        }));
        items.add(new PrismDialog.Item("查看授权步骤",
                "Shizuku 安装与无线调试启动指引", false, new PrismDialog.Action() {
            @Override
            public void run() {
                helpDialog();
            }
        }));
        PrismDialog.list(this, "连接通道",
                "当前：" + shell.channelLabel() + "\n" + shell.channelDetail(), items);
    }

    private View searchBar() {
        EditText e = new EditText(this);
        e.setHint("搜索功能，如：冻结、刷新率、隐私");
        e.setHintTextColor(0xFF6E727B);
        e.setTextColor(TEXT);
        e.setTextSize(13.5f);
        e.setSingleLine(true);
        e.setMinHeight(0);
        e.setMinimumHeight(0);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        e.setBackground(round(CARD_HI, 16));
        e.setPadding(dp(14), dp(9), dp(14), dp(9));
        e.setText(query);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim();
                if (q.equals(query)) return;
                query = q;
                renderFeatures();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        e.setLayoutParams(lp);
        searchBox = e;
        return e;
    }

    private View chipsRow() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        chipsBar = new LinearLayout(this);
        chipsBar.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(chipsBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        h.setLayoutParams(lp);
        buildChips();
        return h;
    }

    private void updateChips() {
        if (chipsBar == null) return;
        buildChips();
    }

    private void buildChips() {
        if (chipsBar == null || profile == null) return;
        chipsBar.removeAllViews();
        addChip("全部", -1);
        int last = Integer.MIN_VALUE;
        for (PrismFeatures.Feature f : PrismFeatures.forVendor(profile.vendor)) {
            if (!PrismFlags.state(this, f.id).visible) continue;
            if (f.section == last) continue;
            last = f.section;
            addChip(PrismFeatures.sectionName(f.section), f.section);
        }
    }

    private void addChip(String label, final int section) {
        TextView t = new TextView(this);
        boolean on = sectionFilter == section;
        t.setText(label);
        t.setTextSize(12.5f);
        t.setTextColor(on ? 0xFF101114 : TEXT);
        t.setBackground(round(on ? profile.accent() : CARD_HI, 16));
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sectionFilter == section) return;
                sectionFilter = section;
                if (searchBox != null) searchBox.setText("");
                query = "";
                renderFeatures();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chipsBar.addView(t, lp);
    }

    private View sectionHeader(int section, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(16), dp(8), dp(8));

        View dot = new View(this);
        dot.setBackground(round(profile.accent(), 3));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView t = new TextView(this);
        t.setText(PrismFeatures.sectionName(section));
        t.setTextColor(DIM);
        t.setTextSize(12f);
        t.setLetterSpacing(0.08f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(String.valueOf(count));
        n.setTextColor(0xFF6E727B);
        n.setTextSize(11f);
        row.addView(n);
        return row;
    }

    /** 一个分区一张卡，行与行之间用极细分割线，避免几十张独立卡片堆成墙。 */
    private LinearLayout sectionCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    /** 功能行容器：无背景、只负责内边距，最终被 sectionCard 包住。 */
    private LinearLayout row() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(2), dp(4), dp(2), dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private Button miniButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(color == CARD_HI ? TEXT : 0xFF101114);
        b.setBackground(round(color, 19));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private TextView ghost(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(round(CARD_HI, 17));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
