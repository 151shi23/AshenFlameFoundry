package com.mineways;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 像素地图开放平台（goto.pixelmap.cc）· 原生页面。
 *
 * <p>走站方公开的只读接口：{@code /api/open/v1/maps/search} 出列表，{@code /api/open/v1/maps/query/{slug}}
 * 出详情和下载地址。地图包按作者授权与否决定能不能下，能下的直接落进「下载/像素地图」，
 * 不用离开 App。站方要求带可识别的 User-Agent，这里统一用 App 名 + 版本。</p>
 */
public class PixelmapActivity extends Activity {

    private static final int BG = 0xFF15171A, CARD = 0xFF1E2126, FIELD = 0xFF262A31,
            TEXT = 0xFFECECEC, DIM = 0xFF9AA0A6, DIM2 = 0xFF5C6268, ACCENT = 0xFFD9603A;

    private static final String API = "https://goto.pixelmap.cc/api/open/v1/maps";
    /** 下载落盘目录：公共「下载」下的一级文件夹。 */
    private static final String SAVE_DIR = "像素地图";
    private static final int PAGE_SIZE = 20;

    private static final String[] SORTS = {"published", "updated", "views"};
    private static final String[] SORT_LABELS = {"最新发布", "最近更新", "最多浏览"};

    /** 一条地图：列表接口给的字段 + 详情接口补的链接。 */
    private static final class Item {
        String title = "", summary = "", author = "", slug = "", cover = "";
        String url = "", downloadUrl = "", extensionUrl = "";
        List<String> versions = new ArrayList<>(), categories = new ArrayList<>();
        int views;
        boolean downloadAllowed, extensionExist;
        /** 详情要重画一次界面，缓存解码好的封面免得重复下载。 */
        Bitmap thumb;
    }

    private ScrollView listPane, detailPane;
    private LinearLayout listCol, detailCol;
    private EditText etSearch;
    private TextView tvStatus;
    private final TextView[] chips = new TextView[SORTS.length];

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 接口、封面、大文件各占一条线程池：下载几百 MB 的地图包时列表照样能翻。 */
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final ExecutorService img = Executors.newFixedThreadPool(2);
    private final ExecutorService dl = Executors.newSingleThreadExecutor();
    private String ua = "MinewaysMobile";

    private int sortIdx = 0;
    private int page = 0;
    private int loaded = 0;
    private int total = -1;
    private boolean loading;
    private volatile boolean cancelDownload;
    private volatile boolean downloading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ua = "MinewaysMobile/" + BuildConfig.VERSION_NAME + " (Android; 离线工具箱)";
        setContentView(buildRoot());
        search(false);
    }

    @Override
    protected void onDestroy() {
        cancelDownload = true;
        net.shutdownNow();
        img.shutdownNow();
        dl.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), dp(6));

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
        title.setText("像素地图存档");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("点卡片看详情 · 可下载的地图包存进「下载/" + SAVE_DIR + "」");
        sub.setTextColor(DIM);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        root.addView(sub);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        etSearch = new EditText(this);
        etSearch.setHint("搜地图名字（留空=浏览全部）");
        etSearch.setHintTextColor(DIM2);
        etSearch.setTextColor(TEXT);
        etSearch.setTextSize(13.5f);
        etSearch.setSingleLine(true);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etSearch.setBackground(round(FIELD, 10));
        etSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(false);
                return true;
            }
            return false;
        });
        bar.addView(etSearch, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView btnGo = pill("搜索");
        btnGo.setBackground(round(ACCENT, 10));
        btnGo.setTextColor(0xFFFFFFFF);
        btnGo.setTypeface(Typeface.DEFAULT_BOLD);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setOnClickListener(v -> search(false));
        LinearLayout.LayoutParams glp = lp(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        glp.leftMargin = dp(8);
        bar.addView(btnGo, glp);
        root.addView(bar);

        HorizontalScrollView sorter = new HorizontalScrollView(this);
        sorter.setHorizontalScrollBarEnabled(false);
        sorter.setPadding(0, dp(10), 0, dp(10));
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            final int idx = i;
            TextView c = chip(SORT_LABELS[i]);
            c.setOnClickListener(v -> {
                if (sortIdx == idx) return;
                sortIdx = idx;
                syncChips();
                search(false);
            });
            chips[i] = c;
            sortRow.addView(c);
        }
        sorter.addView(sortRow);
        root.addView(sorter);

        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listPane = new ScrollView(this);
        listPane.setVerticalScrollBarEnabled(false);
        listPane.addView(listCol);
        // 纵向 LinearLayout 里 weight 只分高度，宽度必须显式 MATCH_PARENT，写 0 就是 0 像素宽
        root.addView(listPane, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detailCol = new LinearLayout(this);
        detailCol.setOrientation(LinearLayout.VERTICAL);
        detailPane = new ScrollView(this);
        detailPane.setVerticalScrollBarEnabled(false);
        detailPane.addView(detailCol);
        detailPane.setVisibility(View.GONE);
        root.addView(detailPane, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        syncChips();

        tvStatus = new TextView(this);
        tvStatus.setText("正在加载…");
        tvStatus.setTextColor(DIM);
        tvStatus.setTextSize(11.5f);
        tvStatus.setPadding(0, dp(10), 0, dp(4));
        root.addView(tvStatus);
        return root;
    }

    private void syncChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == sortIdx;
            chips[i].setBackground(round(on ? ACCENT : FIELD, 9));
            chips[i].setTextColor(on ? 0xFFFFFFFF : DIM);
        }
    }

    // ------------------------------------------------------------------ 列表

    private void search(final boolean more) {
        if (loading) {
            toast("上一个请求还没回，稍等一下");
            return;
        }
        final String kw = etSearch.getText().toString().trim();
        if (!more) {
            page = 0;
            loaded = 0;
            moreBtn = null;
            listCol.removeAllViews();
            showList();
            hideKeyboard();
        }
        final int pageNo = page + 1;
        String url = API + "/search?sort=" + SORTS[sortIdx] + "&page=" + pageNo + "&limit=" + PAGE_SIZE;
        if (kw.length() > 0) {
            try {
                url += "&keyword=" + URLEncoder.encode(kw, "UTF-8");
            } catch (Throwable ignored) {
                url += "&keyword=" + kw;
            }
        }
        loading = true;
        status(more ? "加载中…" : "搜索中…");
        final String req = url;
        net.execute(() -> {
            String err = null;
            List<Item> items = new ArrayList<>();
            int tot = -1;
            try {
                JSONObject o = getJson(req);
                JSONArray arr = o.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        items.add(parse(arr.getJSONObject(i)));
                    }
                }
                tot = o.optInt("total", -1);
            } catch (Throwable t) {
                err = tip(t);
            }
            final String e = err;
            final List<Item> got = items;
            final int count = tot;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                if (e != null) {
                    status("搜索失败：" + e);
                    if (!more || page == 0) renderEmptyHint();
                    return;
                }
                page = pageNo;
                total = count;
                loaded += got.size();
                for (Item it : got) listCol.addView(row(it));
                renderFooter();
                status(loaded == 0
                        ? "没搜到：换个词，或者清空搜索框浏览全部"
                        : "共 " + total + " 个地图，已显示 " + Math.min(loaded, total) + " 个");
            });
        });
    }

    /** 列表尾部的「加载更多」：每次重建，避免翻页后堆出多个按钮。 */
    private View moreBtn;

    private void renderFooter() {
        if (moreBtn != null) {
            listCol.removeView(moreBtn);
            moreBtn = null;
        }
        if (total > loaded) {
            TextView btn = pill("加载更多（还有 " + (total - loaded) + " 个）");
            btn.setGravity(Gravity.CENTER);
            btn.setOnClickListener(v -> search(true));
            moreBtn = btn;
            LinearLayout.LayoutParams p =
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
            p.topMargin = dp(12);
            listCol.addView(moreBtn, p);
        }
    }

    private void renderEmptyHint() {
        TextView t = new TextView(this);
        t.setText("没有结果。可以清空搜索框浏览全部，或者换个关键词。");
        t.setTextColor(DIM);
        t.setTextSize(12.5f);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        listCol.addView(t);
    }

    private View row(final Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(round(CARD, 12));
        card.setPadding(dp(11), dp(11), dp(9), dp(11));
        // 纵向容器里的子 View 必须是 MATCH_PARENT：写 width=0+weight=0 会被压成 0 宽，整页看不见
        LinearLayout.LayoutParams clp =
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(round(FIELD, 8));
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(86), dp(58));
        ilp.rightMargin = dp(11);
        card.addView(iv, ilp);
        if (it.cover != null && it.cover.length() > 0) loadThumb(iv, it.cover, dp(240), it);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(it.title);
        name.setTextColor(TEXT);
        name.setTextSize(13.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        TextView meta = new TextView(this);
        meta.setText(metaLine(it));
        meta.setTextColor(DIM);
        meta.setTextSize(11);
        meta.setPadding(0, dp(4), 0, 0);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(meta, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        card.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("›");
        go.setTextColor(DIM2);
        go.setTextSize(19);
        go.setPadding(dp(6), 0, dp(2), 0);
        card.addView(go);

        card.setClickable(true);
        card.setForeground(pressOverlay());
        card.setOnClickListener(v -> openDetail(it));
        return card;
    }

    /** 按下时给卡片叠一层浅色遮罩：只改背景色的话，手指按没按上完全看不出来。 */
    private StateListDrawable pressOverlay() {
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x1AFFFFFF);
        pressed.setCornerRadius(dp(12));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, pressed);
        d.addState(new int[]{android.R.attr.state_selected}, pressed);
        return d;
    }

    private String metaLine(Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.author.length() > 0) sb.append(it.author);
        if (!it.versions.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append("版本 ").append(join(it.versions));
        sb.append(sb.length() > 0 ? " · " : "").append(it.views).append(" 次浏览");
        if (!it.downloadAllowed) sb.append(" · 未开放下载");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 详情

    private void openDetail(final Item it) {
        hideKeyboard();
        renderDetail(it);
        showDetail();
        status("载入详情…");
        net.execute(() -> {
            String err = null;
            try {
                JSONObject o = getJson(API + "/query/" + URLEncoder.encode(it.slug, "UTF-8"));
                fillDetail(it, o);
            } catch (Throwable t) {
                err = tip(t);
            }
            final String e = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (e != null) {
                    renderDetail(it);
                    status("详情没取到（" + e + "），下面用列表里的信息，下载地址可能要回站内拿");
                    return;
                }
                renderDetail(it);
                status("就绪");
            });
        });
    }

    private void renderDetail(Item it) {
        detailCol.removeAllViews();

        TextView back = new TextView(this);
        back.setText("‹ 返回列表");
        back.setTextColor(DIM);
        back.setTextSize(13);
        back.setPadding(0, dp(2), dp(14), dp(10));
        back.setOnClickListener(v -> {
            showList();
            status("共 " + total + " 个地图，已显示 " + Math.min(loaded, total) + " 个");
        });
        detailCol.addView(back);

        if (it.cover.length() > 0) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(FIELD);
            LinearLayout.LayoutParams ilp =
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), 0f);
            ilp.bottomMargin = dp(11);
            detailCol.addView(iv, ilp);
            loadCoverLarge(iv, it.cover);
        }

        TextView name = new TextView(this);
        name.setText(it.title);
        name.setTextColor(TEXT);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        detailCol.addView(name);

        TextView meta = new TextView(this);
        meta.setText(metaLine(it));
        meta.setTextColor(DIM);
        meta.setTextSize(11.5f);
        meta.setPadding(0, dp(5), 0, 0);
        detailCol.addView(meta);

        if (!it.categories.isEmpty()) {
            TextView cat = new TextView(this);
            cat.setText("分类 " + join(it.categories));
            cat.setTextColor(DIM);
            cat.setTextSize(11.5f);
            cat.setPadding(0, dp(3), 0, 0);
            detailCol.addView(cat);
        }

        if (it.summary.length() > 0) {
            TextView box = new TextView(this);
            box.setText(it.summary);
            box.setTextColor(TEXT);
            box.setTextSize(12.5f);
            box.setLineSpacing(dp(3), 1f);
            box.setPadding(dp(12), dp(12), dp(12), dp(12));
            box.setBackground(round(CARD, 10));
            LinearLayout.LayoutParams blp =
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
            blp.topMargin = dp(12);
            detailCol.addView(box, blp);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp =
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        rlp.topMargin = dp(14);
        detailCol.addView(btns, rlp);

        if (it.downloadAllowed && it.downloadUrl.length() > 0) {
            TextView d = pill("⇩ 下载地图包");
            d.setBackground(round(ACCENT, 10));
            d.setTextColor(0xFFFFFFFF);
            d.setTypeface(Typeface.DEFAULT_BOLD);
            d.setOnClickListener(v -> download(it, it.downloadUrl, "地图包"));
            btns.addView(d);
        } else {
            TextView d = pill("⇩ 下载地图包");
            d.setTextColor(DIM2);
            d.setAlpha(0.5f);
            d.setOnClickListener(v -> status(it.downloadAllowed
                    ? "这个地图没带回直链，回站内详情页下载" : "作者没开放直接下载，只能回站内看"));
            btns.addView(d);
        }

        if (it.extensionExist && it.extensionUrl.length() > 0) {
            TextView e = pill("⇩ 下载附加资源");
            LinearLayout.LayoutParams elp = wrapL(dp(8));
            e.setOnClickListener(v -> download(it, it.extensionUrl, "附加资源"));
            btns.addView(e, elp);
        }

        if (it.url.length() > 0) {
            TextView w = pill("在站内打开详情页");
            w.setTextColor(DIM);
            LinearLayout.LayoutParams wlp = wrapL(dp(8));
            w.setOnClickListener(v -> {
                Intent it2 = new Intent(this, WebToolActivity.class);
                it2.putExtra(WebToolActivity.EXTRA_URL, it.url);
                it2.putExtra(WebToolActivity.EXTRA_TITLE, it.title);
                startActivity(it2);
            });
            btns.addView(w, wlp);
        }

        if (downloading) {
            TextView c = pill("取消下载");
            c.setTextColor(ACCENT);
            LinearLayout.LayoutParams clp = wrapL(dp(8));
            c.setOnClickListener(v -> {
                cancelDownload = true;
                status("正在取消…");
            });
            btns.addView(c, clp);
        }
    }

    /** 横向按钮行里的子按钮：宽度必须 WRAP_CONTENT，width=0 会直接把它压没。 */
    private LinearLayout.LayoutParams wrapL(int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = leftDp;
        return p;
    }

    private void showList() {
        listPane.setVisibility(View.VISIBLE);
        detailPane.setVisibility(View.GONE);
    }

    private void showDetail() {
        listPane.setVisibility(View.GONE);
        detailPane.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ 下载

    private void download(final Item it, final String url, final String what) {
        if (downloading) {
            toast("已经有一个下载在跑了");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            status("先允许存储权限，再点一次下载");
            return;
        }
        downloading = true;
        cancelDownload = false;
        renderDetail(it);    // 让「取消下载」出现
        status(what + "：准备下载…");
        dl.execute(() -> {
            String done = null, err = null;
            File tmp = null;
            Uri row = null;
            HttpURLConnection c = null;
            long got = 0, nextMark = 256 * 1024;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                long totalBytes = c.getContentLengthLong();
                // 文件名用地图标题（接口里是正经 UTF-8），扩展名跟站方给的一致
                String name = safeName(it.title, extOf(c.getHeaderField("Content-Disposition"), url));
                OutputStream os;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 高版本直接写进 MediaStore，省一遍临时文件（地图包动辄几百 MB）
                    row = insertDownloadRow(name);
                    if (row == null) throw new IOException("建下载记录失败");
                    os = getContentResolver().openOutputStream(row);
                    if (os == null) throw new IOException("打不开下载目录的写入流");
                    done = "下载/" + SAVE_DIR + "/" + name;
                } else {
                    tmp = new File(getCacheDir(), name);
                    os = new FileOutputStream(tmp);
                }
                try (InputStream in = c.getInputStream(); OutputStream out = os) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelDownload) {
                            err = "已取消";
                            break;
                        }
                        out.write(buf, 0, n);
                        got += n;
                        if (got >= nextMark) {
                            nextMark += 512 * 1024;
                            postProgress(what, got, totalBytes);
                        }
                    }
                }
                if (err == null && tmp != null) {
                    done = publishLegacy(tmp);
                    if (done == null) err = "存进「下载」目录失败，看看存储权限";
                }
            } catch (Throwable t) {
                err = tip(t);
            } finally {
                if (c != null) c.disconnect();
                if (tmp != null) tmp.delete();
                // 取消或出错的半截文件不能留在用户下载目录里
                if (err != null && row != null) {
                    try {
                        getContentResolver().delete(row, null, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
            final String d = done, e = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                downloading = false;
                if (e != null) {
                    status(e.equals("已取消") ? "下载已取消" : "下载失败：" + e);
                    renderDetail(it);
                    return;
                }
                toast("已存到 " + d);
                status("下载完成：" + d);
                renderDetail(it);
            });
        });
    }

    private Uri insertDownloadRow(String name) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(name));
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR);
        return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
    }

    /** 低版本（Android 9 及以下）：先落缓存，再拷进公共下载目录。 */
    private String publishLegacy(File src) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), SAVE_DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dst = new File(dir, src.getName());
            if (dst.exists()) {
                int dot = src.getName().lastIndexOf('.');
                String stem = dot > 0 ? src.getName().substring(0, dot) : src.getName();
                String ext = dot > 0 ? src.getName().substring(dot) : "";
                dst = new File(dir, stem + "-" + System.currentTimeMillis() + ext);
            }
            try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
                copyStream(in, os);
            }
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void postProgress(final String what, final long got, final long totalBytes) {
        final String line = totalBytes > 0
                ? what + "：" + fmtSize(got) + " / " + fmtSize(totalBytes)
                : what + "：" + fmtSize(got);
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed() && downloading) status(line);
        });
    }

    private static String safeName(String raw, String ext) {
        String s = raw == null ? "" : raw.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ");
        s = s.replaceAll(" {2,}", " ").trim();
        if (s.length() > 90) s = s.substring(0, 90);
        if (s.length() == 0) s = "pixelmap";
        if (!s.toLowerCase(Locale.US).endsWith("." + ext)) s = s + "." + ext;
        return s;
    }

    /** 扩展名优先跟 URL 走，取不到再看站方给的 Content-Disposition。 */
    private static String extOf(String disposition, String url) {
        String e = extFrom(url);
        if (e != null) return e;
        e = extFrom(disposition);
        return e != null ? e : "bin";
    }

    private static String extFrom(String s) {
        if (s == null) return null;
        int q = s.indexOf('?');
        String p = q > 0 ? s.substring(0, q) : s;
        int dot = p.lastIndexOf('.');
        if (dot < 0 || dot == p.length() - 1) return null;
        String ext = p.substring(dot + 1).toLowerCase(Locale.US);
        // 头里的文件名常带引号或分号收尾，剔掉非字母数字再说
        int end = ext.length();
        while (end > 0 && !Character.isLetterOrDigit(ext.charAt(end - 1))) end--;
        ext = ext.substring(0, end);
        return ext.matches("[a-z0-9]{1,5}") ? ext : null;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rar")) return "application/vnd.rar";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static void copyStream(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ 网络 / 图片

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua);
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                String msg = body.startsWith("{") ? new JSONObject(body).optString("message", "") : "";
                throw new IOException("HTTP " + code + (msg.length() > 0 ? "：" + msg : ""));
            }
            return new JSONObject(body);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), Charset.forName("UTF-8")).trim();
    }

    /** 详情页的大封面：详情取完要重画一次界面，缓存住省得再下一遍。 */
    private Bitmap bigCover;
    private String bigCoverUrl;

    private void loadCoverLarge(final ImageView iv, final String url) {
        iv.setTag(url);
        if (bigCover != null && url.equals(bigCoverUrl)) {
            iv.setImageBitmap(bigCover);
            return;
        }
        img.execute(() -> {
            Bitmap b = decodeImage(url, dp(520));
            if (b != null) {
                bigCover = b;
                bigCoverUrl = url;
            }
            final Bitmap got = b;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || got == null) return;
                if (!url.equals(iv.getTag())) return;
                iv.setImageBitmap(got);
            });
        });
    }

    /** 封面：按目标边长降采样再解码，列表二十张也不会把内存吃穿。 */
    private void loadThumb(final ImageView iv, final String url, final int maxPx, final Item it) {
        iv.setTag(url);
        img.execute(() -> {
            Bitmap b = it.thumb;
            if (b == null) {
                b = decodeImage(url, maxPx);
                if (b != null) it.thumb = b;
            }
            final Bitmap got = b;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!url.equals(iv.getTag()) || got == null) return;
                iv.setImageBitmap(got);
            });
        });
    }

    private Bitmap decodeImage(String url, int maxPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua);
            if (c.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 6 * 1024 * 1024) return null;    // 封面不该这么大
                }
                bytes = bos.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            int sample = 1;
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (longSide / sample > maxPx && sample <= 16) sample *= 2;
            opt.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ 解析 / 小工具

    private static Item parse(JSONObject o) {
        Item it = new Item();
        it.title = o.optString("title");
        it.summary = o.optString("summary");
        it.author = o.optString("author");
        it.slug = o.optString("slug");
        it.cover = o.optString("preview_image");
        it.views = o.optInt("views");
        it.downloadAllowed = o.optBoolean("download_allowed");
        it.extensionExist = o.optBoolean("extension_exist");
        it.versions = list(o.optJSONArray("versions"));
        it.categories = list(o.optJSONArray("categories"));
        return it;
    }

    /** 详情接口在列表字段之外补了三个链接。 */
    private static void fillDetail(Item it, JSONObject o) {
        it.url = o.optString("url");
        it.downloadUrl = o.optString("download_url");
        it.extensionUrl = o.optString("extension_url");
        it.downloadAllowed = o.optBoolean("download_allowed", it.downloadAllowed);
        it.extensionExist = o.optBoolean("extension_exist", it.extensionExist);
        if (it.summary.length() == 0) it.summary = o.optString("summary");
        if (it.cover.length() == 0) it.cover = o.optString("preview_image");
    }

    private static List<String> list(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    private static String join(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (String s : v) sb.append(sb.length() > 0 ? " / " : "").append(s);
        return sb.toString();
    }

    private static String tip(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.length() == 0 ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : m;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(FIELD, 9));
        t.setTextColor(DIM);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(TEXT);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(round(FIELD, 10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(p);
        return t;
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        return p;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && etSearch.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
        etSearch.clearFocus();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void status(String s) {
        tvStatus.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
