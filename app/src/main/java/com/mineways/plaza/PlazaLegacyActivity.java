package com.mineways.plaza;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mineways.open.OpenWithActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 资源广场：官方 Release 下载 + 云盘跳转 + 搜索/推荐/榜单/合集/资源管家。 */
public class PlazaLegacyActivity extends Activity {

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int FIELD = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;
    private static final int GOLD = 0xFFF2B13A;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status;
    private EditText search;
    private String query = "";
    private String type = "all";
    private PlazaCatalog.Snapshot snapshot;
    private PlazaSearchIndex index;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!PlazaFlags.on()) {
            Toast.makeText(this, "资源广场已被云端关闭", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(BG);
        setContentView(buildUi());
        load(false);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(10), dp(14), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 28, TEXT);
        back.setPadding(dp(4), 0, dp(10), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("资源广场", 21, TEXT, Typeface.BOLD));
        TextView sub = text("官方精选 · 每周更新", 11, DIM);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(button("投稿", v -> openUrl(PlazaFlags.submitUrl()), false));
        if (PlazaFlags.library()) {
            bar.addView(button("管家", v -> showLibrary(), false));
        }
        bar.addView(button("刷新", v -> load(true), false));
        root.addView(bar);

        search = new EditText(this);
        search.setHint("搜标题、创作者、标签、简介");
        search.setHintTextColor(0xFF5C6268);
        search.setTextColor(TEXT);
        search.setTextSize(13.5f);
        search.setSingleLine(true);
        search.setBackground(round(FIELD, 12));
        search.setPadding(dp(13), dp(9), dp(13), dp(9));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                render();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        root.addView(search, slp);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(9), 0, 0);
        addChip(chips, "全部", "all");
        addChip(chips, "地图", "map");
        addChip(chips, "人物模型", "model");
        addChip(chips, "纹理包", "texture");
        addChip(chips, "通用", "other");
        chipScroll.addView(chips);
        root.addView(chipScroll);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(30));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = text("", 11, DIM);
        status.setPadding(0, dp(4), 0, dp(10));
        root.addView(status);
        return root;
    }

    private void addChip(LinearLayout row, String label, String value) {
        TextView chip = text(label, 12, type.equals(value) ? 0xFFFFFFFF : DIM);
        chip.setPadding(dp(13), dp(6), dp(13), dp(6));
        chip.setBackground(round(type.equals(value) ? ACCENT : FIELD, 999));
        chip.setOnClickListener(v -> {
            type = value;
            render();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(7);
        row.addView(chip, lp);
    }

    private void load(boolean force) {
        status.setText(force ? "正在刷新目录…" : "正在加载资源…");
        content.removeAllViews();
        io.execute(() -> {
            PlazaCatalog.Snapshot s = null;
            try {
                s = PlazaCatalog.load(this, force);
            } catch (Throwable t) {
                ui.post(() -> status.setText("加载失败：" + t.getMessage()));
            }
            final PlazaCatalog.Snapshot loaded = s;
            ui.post(() -> {
                if (loaded == null) return;
                snapshot = loaded;
                index = new PlazaSearchIndex(loaded);
                if (!loaded.warnings.isEmpty()) {
                    status.setText(loaded.warnings.get(0));
                }
                render();
            });
        });
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        if (snapshot == null) {
            content.addView(empty("还没加载到目录"));
            return;
        }
        List<PlazaItem> visible = new ArrayList<>();
        for (PlazaItem it : snapshot.items) {
            if (!PlazaFlags.allowCloud() && it.isCloud()) continue;
            visible.add(it);
        }
        if (visible.isEmpty()) {
            content.addView(empty(snapshot.warnings.isEmpty()
                    ? "这里还没有资源" : snapshot.warnings.get(0)));
            return;
        }

        if (PlazaFlags.featured() && snapshot.featured != null && query.isEmpty() && "all".equals(type)) {
            List<PlazaItem> fs = new ArrayList<>();
            for (String id : snapshot.featured.ids) {
                PlazaItem it = snapshot.byId.get(id);
                if (it != null && (PlazaFlags.allowCloud() || !it.isCloud())) fs.add(it);
            }
            if (!fs.isEmpty()) {
                addSectionTitle("本周精选", snapshot.featured.week);
                addFeatured(fs);
            }
        }

        if (PlazaFlags.recommend() && query.isEmpty()) {
            List<PlazaItem> picks = visiblePicks(6);
            if (!picks.isEmpty()) {
                addSectionTitle("猜你喜欢", "按类型、标签和本地下载偏好");
                addGrid(picks);
            }
        }

        if (PlazaFlags.rankings() && snapshot.rankings != null && query.isEmpty()) {
            addRankings();
        }

        if (PlazaFlags.collections() && snapshot.collections != null && query.isEmpty() && "all".equals(type)) {
            addCollections();
        }

        List<PlazaItem> list;
        if (index == null) list = visible;
        else list = index.search(query, type);
        List<PlazaItem> filtered = new ArrayList<>();
        for (PlazaItem it : list) {
            if (!PlazaFlags.allowCloud() && it.isCloud()) continue;
            filtered.add(it);
        }
        addSectionTitle(query.isEmpty() ? "全部资源" : "搜索结果", filtered.size() + " 条");
        if (filtered.isEmpty()) {
            String hint = snapshot.search == null || snapshot.search.hot.isEmpty()
                    ? "换个词试试" : "试试：" + join(snapshot.search.hot, " / ");
            content.addView(empty(hint));
        } else {
            addGrid(filtered);
        }
        if (!snapshot.warnings.isEmpty()) status.setText(snapshot.warnings.get(0));
    }

    private List<PlazaItem> visiblePicks(int limit) {
        if (snapshot == null) return new ArrayList<>();
        List<PlazaItem> out = new ArrayList<>();
        if (snapshot.featured != null) {
            for (String id : snapshot.featured.ids) {
                PlazaItem it = snapshot.byId.get(id);
                if (it != null && (PlazaFlags.allowCloud() || !it.isCloud())) out.add(it);
            }
        }
        List<PlazaItem> rest = new ArrayList<>(snapshot.items);
        rest.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
        for (PlazaItem it : rest) {
            if (!PlazaFlags.allowCloud() && it.isCloud()) continue;
            if (!out.contains(it)) out.add(it);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private void addSectionTitle(String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 16, TEXT, Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (sub != null && !sub.isEmpty()) row.addView(text(sub, 11, DIM));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(8);
        content.addView(row, lp);
    }

    private void addFeatured(List<PlazaItem> items) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (PlazaItem it : items) {
            LinearLayout c = card(it, 245);
            row.addView(c);
        }
        hs.addView(row);
        content.addView(hs);
    }

    private void addGrid(List<PlazaItem> items) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout c = card(items.get(i), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(8);
            lp.bottomMargin = dp(9);
            row.addView(c, lp);
            if (i == items.size() - 1 && items.size() % 2 == 1) {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private LinearLayout card(PlazaItem it, int fixedWidth) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 14));
        c.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (fixedWidth > 0) {
            c.setLayoutParams(new LinearLayout.LayoutParams(dp(fixedWidth), -2));
            ((LinearLayout.LayoutParams) c.getLayoutParams()).rightMargin = dp(9);
        }
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(FIELD, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(fixedWidth > 0 ? 135 : 115));
        c.addView(cover, ilp);
        if (!it.previewCover.isEmpty()) PlazaImages.load(it.previewCover, cover);
        TextView title = text(it.title, 13.5f, TEXT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setPadding(0, dp(8), 0, dp(2));
        c.addView(title);
        String creator = it.author.isEmpty() ? "未知创作者" : it.author;
        c.addView(text(creator, 11, DIM));
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(5), 0, 0);
        meta.addView(text(it.typeLabel(), 10, ACCENT));
        meta.addView(text("  ·  " + it.sizeText(), 10, DIM));
        c.addView(meta);
        LinearLayout tags = new LinearLayout(this);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        tags.addView(text(it.sourceLabel(), 10, GOLD));
        if (PlazaFlags.library() && PlazaLibrary.find(this, it) != null) {
            tags.addView(text("  · 已下载", 10, 0xFF4CAF50));
            if (PlazaFlags.updates() && PlazaLibrary.hasUpdate(this, it)) {
                tags.addView(text("  · 有新版本", 10, GOLD));
            }
        }
        c.addView(tags);
        c.setOnClickListener(v -> showDetail(it));
        return c;
    }

    private void addRankings() {
        List<PlazaRanking> rs = snapshot.rankings;
        if (rs == null || rs.isEmpty()) return;
        addSectionTitle("排行榜", "编辑精选 · 每周更新");
        for (int k = 0; k < Math.min(2, rs.size()); k++) {
            PlazaRanking r = rs.get(k);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(round(CARD, 14));
            panel.setPadding(dp(12), dp(10), dp(12), dp(6));
            panel.addView(text(r.title, 13.5f, TEXT, Typeface.BOLD));
            int n = 0;
            for (PlazaRanking.Entry e : r.entries) {
                PlazaItem it = snapshot.byId.get(e.id);
                if (it == null) continue;
                if (!PlazaFlags.allowCloud() && it.isCloud()) continue;
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(0, dp(7), 0, dp(7));
                line.addView(text(String.valueOf(e.rank), 13, e.rank <= 3 ? GOLD : DIM, Typeface.BOLD),
                        new LinearLayout.LayoutParams(dp(28), -2));
                line.addView(text(it.title, 12.5f, TEXT), new LinearLayout.LayoutParams(0, -2, 1f));
                line.addView(text(it.sourceLabel(), 10, DIM));
                line.setOnClickListener(v -> showDetail(it));
                panel.addView(line);
                if (++n >= 5) break;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            content.addView(panel, lp);
        }
    }

    private void addCollections() {
        if (snapshot.collections == null || snapshot.collections.isEmpty()) return;
        addSectionTitle("合集", "主题 / 官方创作者");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int n = 0;
        for (PlazaCollection c : snapshot.collections) {
            if ("creator".equals(c.kind) && !PlazaFlags.creators()) continue;
            List<PlazaItem> ci = snapshot.collectionItems(c);
            if (ci.isEmpty()) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(CARD, 14));
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setLayoutParams(new LinearLayout.LayoutParams(dp(210), -2));
            ((LinearLayout.LayoutParams) card.getLayoutParams()).rightMargin = dp(8);
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(round(FIELD, 10));
            card.addView(cover, new LinearLayout.LayoutParams(-1, dp(95)));
            if (!c.cover.isEmpty()) PlazaImages.load(c.cover, cover);
            TextView t = text(c.title, 13, TEXT, Typeface.BOLD);
            t.setMaxLines(1);
            t.setPadding(0, dp(7), 0, 0);
            card.addView(t);
            card.addView(text((c.official ? "官方收录 · " : "") + ci.size() + " 个资源", 10, DIM));
            card.setOnClickListener(v -> showCollection(c));
            row.addView(card);
            if (++n >= 8) break;
        }
        if (n > 0) {
            hs.addView(row);
            content.addView(hs);
        }
    }

    private void showCollection(PlazaCollection c) {
        List<PlazaItem> items = snapshot.collectionItems(c);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView title = text(c.title, 18, TEXT, Typeface.BOLD);
        box.addView(title);
        box.addView(text((c.official ? "官方收录 · " : "") + items.size() + " 个资源", 11, DIM));
        for (PlazaItem it : items) {
            TextView row = text(it.title + "  ·  " + it.typeLabel(), 13, TEXT);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setOnClickListener(v -> showDetail(it));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setView(sv).setNegativeButton("关闭", null).show();
    }

    private void showDetail(PlazaItem it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(4));

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(FIELD, 12));
        box.addView(cover, new LinearLayout.LayoutParams(-1, dp(170)));
        if (!it.previewCover.isEmpty()) PlazaImages.load(it.previewCover, cover);

        TextView title = text(it.title, 19, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(2));
        box.addView(title);
        PlazaCreator creator = snapshot.creatorOf(it);
        String who = creator != null ? creator.name : (it.author.isEmpty() ? "未知创作者" : it.author);
        box.addView(text(who + (creator != null && creator.official ? "  ·  官方收录" : ""), 12, ACCENT));
        box.addView(text(it.typeLabel() + " · " + it.sizeText() + " · " + it.version, 11, DIM));

        TextView desc = text(it.summary.isEmpty() ? "暂无简介" : it.summary, 13, TEXT);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(0, dp(8), 0, dp(6));
        box.addView(desc);

        if (!it.tags.isEmpty()) box.addView(text(join(it.tags, "  "), 11, DIM));
        box.addView(text("许可证：" + it.license, 11, DIM));

        if (it.isOfficial()) {
            box.addView(text("Release：" + it.releaseTag, 10, DIM));
            box.addView(text("SHA-256：" + shortSha(it.sha256), 10, DIM));
        } else {
            box.addView(text(PlazaItem.providerLabel(it.provider), 11, GOLD));
            if (!it.cloudNote.isEmpty()) box.addView(text(it.cloudNote, 11, DIM));
        }

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(6)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        box.addView(actions);
        if (it.isOfficial()) {
            File local = PlazaDownloader.downloaded(this, it);
            Button dl = button(local != null ? "打开已下载" : "下载", null, true);
            dl.setOnClickListener(v -> {
                if (PlazaDownloader.downloaded(this, it) != null) {
                    openFile(PlazaDownloader.downloaded(this, it));
                    return;
                }
                dl.setEnabled(false);
                dl.setText("下载中…");
                bar.setVisibility(View.VISIBLE);
                io.execute(() -> {
                    try {
                        File f = PlazaDownloader.download(this, it, (done, total) -> {
                            if (total > 0) {
                                int p = (int) Math.min(100, done * 100 / total);
                                ui.post(() -> bar.setProgress(p));
                            }
                        }, com.mineways.blender.Downloader.NEVER);
                        PlazaLibrary.remember(this, it, f);
                        ui.post(() -> {
                            bar.setVisibility(View.GONE);
                            dl.setEnabled(true);
                            dl.setText("打开");
                            status.setText("下载完成：" + f.getName());
                            dl.setOnClickListener(x -> openFile(f));
                        });
                    } catch (Throwable t) {
                        ui.post(() -> {
                            bar.setVisibility(View.GONE);
                            dl.setEnabled(true);
                            dl.setText("重试");
                            status.setText("下载失败：" + t.getMessage());
                        });
                    }
                });
            });
            actions.addView(dl);
            if (!PlazaFlags.preview3d() || it.previewModel.isEmpty()) {
                if (local != null) actions.addView(button("分享", v -> shareFile(local), false));
            }
            if (PlazaFlags.preview3d() && !it.previewModel.isEmpty()) {
                actions.addView(button("3D 预览", v -> open3dPreview(it), false));
            }
        } else {
            actions.addView(button("打开云盘", v -> {
                if (!PlazaCloudLinks.open(this, it)) {
                    Toast.makeText(this, "云盘链接不可用", Toast.LENGTH_SHORT).show();
                }
            }, true));
            if (!it.accessCode.isEmpty()) {
                actions.addView(button("复制提取码", v -> {
                    if (PlazaCloudLinks.copyCode(this, it)) {
                        Toast.makeText(this, "提取码已复制", Toast.LENGTH_SHORT).show();
                    }
                }, false));
            }
        }
        Button close = button("关闭", null, false);
        actions.addView(close);

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(box)
                .create();
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void showLibrary() {
        List<PlazaLibrary.Entry> entries = PlazaLibrary.list(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(text("资源管家", 18, TEXT, Typeface.BOLD));
        box.addView(text(entries.size() + " 个官方资源 · " + PlazaRepo.humanSize(PlazaLibrary.totalSize(this)), 11, DIM));
        if (entries.isEmpty()) {
            box.addView(text("还没有下载资源", 13, DIM));
        }
        for (PlazaLibrary.Entry e : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(e.title.isEmpty() ? e.id : e.title, 13, TEXT));
            info.addView(text(PlazaRepo.humanSize(e.size) + " · " + e.releaseTag, 10, DIM));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(button("打开", v -> openFile(new File(e.path)), false));
            row.addView(button("删除", v -> {
                PlazaLibrary.remove(this, e.id);
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            }, false));
            box.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setView(sv).setNegativeButton("关闭", null).show();
    }

    private void open3dPreview(PlazaItem it) {
        if (it == null || it.previewModel.isEmpty()) return;
        status.setText("正在加载 3D 预览…");
        io.execute(() -> {
            try {
                File base = getExternalFilesDir("plaza");
                if (base == null) base = new File(getFilesDir(), "plaza");
                File dir = new File(base, "preview/" + PlazaDownloader.safeName(it.id));
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("无法创建预览目录");
                String suffix = it.previewModel.toLowerCase(Locale.ROOT).endsWith(".obj") ? ".obj" : ".glb";
                File f = new File(dir, "preview" + suffix);
                if (!f.isFile()) {
                    com.mineways.blender.Downloader.download(it.previewModel, f, null, null,
                            com.mineways.blender.Downloader.NEVER);
                }
                File finalF = f;
                ui.post(() -> openFile(finalF));
            } catch (Throwable t) {
                ui.post(() -> status.setText("3D 预览失败：" + t.getMessage()));
            }
        });
    }

    private void openFile(File f) {
        if (f == null || !f.isFile()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        try {
            Intent it = new Intent(this, OpenWithActivity.class);
            it.setAction(Intent.ACTION_VIEW);
            it.setData(uri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.setClipData(ClipData.newRawUri("plaza", uri));
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "已保存：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile(File f) {
        if (f == null || !f.isFile()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("application/octet-stream");
        it.putExtra(Intent.EXTRA_STREAM, uri);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(it, "分享资源"));
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private View empty(String s) {
        TextView t = text(s, 13, DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(36), 0, dp(36));
        return t;
    }

    private TextView text(String s, float size, int color) {
        return text(s, size, color, Typeface.NORMAL);
    }

    private TextView text(String s, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private Button button(String label, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setBackground(round(primary ? ACCENT : FIELD, 999));
        b.setPadding(dp(12), dp(2), dp(12), dp(2));
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 20 ? sha : sha.substring(0, 20) + "…";
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
