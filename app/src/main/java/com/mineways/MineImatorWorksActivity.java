package com.mineways;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 我的作品：列出 Mine-imator 动画工作室产出的视频 / 图片，一键「保存到相册」或「分享」。
 *
 * <p>为什么要这一页：引擎把产物写在应用私有目录（<code>/data/data/com.mineways/files/</code>）里，
 * 相册和图库看不到。这里把它们挑出来送进系统相册（MediaStore），顺便能看到文件落在哪。
 */
public class MineImatorWorksActivity extends Activity {

    private static final String[] MEDIA_EXT = {
            "mp4", "mov", "mkv", "webm", "gif", "png", "jpg", "jpeg"
    };

    /** 引擎解包出来的资源树与导入源文件不算作品，列出来会淹没结果。 */
    private static final String[] SKIP_DIRS = {"assets", "imports", "Data", "Sprites", "Compiled"};

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        final TextView title = new TextView(this);
        title.setText("我的作品");
        title.setTextColor(TEXT);
        title.setTextSize(22f);
        root.addView(title);

        final TextView sub = new TextView(this);
        sub.setText("动画工作室导出的视频 / 图片会出现在这里，点「保存到相册」就能在系统相册里看到。\n"
                + "还没作品？进工作室后点右上角 ☰ →「怎么导出视频？」看步骤。");
        sub.setTextColor(DIM);
        sub.setTextSize(13f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(10), 0, dp(14));
        root.addView(sub);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        actions.addView(refresh);

        final Button crash = new Button(this);
        crash.setText("崩溃记录");
        crash.setAllCaps(false);
        crash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashReport.show(MineImatorWorksActivity.this);
            }
        });
        actions.addView(crash);

        root.addView(actions);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(14), 0, 0);
        root.addView(list);
        return scroll;
    }

    private void refresh() {
        list.removeAllViews();
        final List<File> works = new ArrayList<>();
        scan(getFilesDir(), works, 0);
        final List<File> extra = new ArrayList<>();
        // 顺带看看外部专属目录（有的导出路径会落在这里）
        final File ext = getExternalFilesDir(null);
        if (ext != null && ext.isDirectory()) {
            scan(ext, extra, 0);
        }
        works.addAll(extra);
        java.util.Collections.sort(works, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        if (works.isEmpty()) {
            list.addView(hint("暂时没有找到导出的视频或图片。"));
            return;
        }
        int shown = 0;
        for (File f : works) {
            if (shown++ >= 200) {
                break;
            }
            list.addView(card(f));
        }
    }

    private void scan(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                boolean skip = false;
                for (String s : SKIP_DIRS) {
                    if (s.equals(f.getName())) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    scan(f, out, depth + 1);
                }
            } else if (isMedia(f.getName(), f.length())) {
                out.add(f);
            }
        }
    }

    private static boolean isMedia(String name, long size) {
        final int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        final String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean known = false;
        for (String e : MEDIA_EXT) {
            if (e.equals(ext)) {
                known = true;
                break;
            }
        }
        if (!known) {
            return false;
        }
        final boolean video = "mp4".equals(ext) || "mov".equals(ext) || "mkv".equals(ext)
                || "webm".equals(ext) || "gif".equals(ext);
        // 小图多半是工程里的贴图，不算"作品"；视频一律列出
        return video || size >= 32 * 1024L;
    }

    private View card(final File f) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);

        final TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextColor(TEXT);
        name.setTextSize(15f);
        box.addView(name);

        final TextView meta = new TextView(this);
        final String path = f.getAbsolutePath().replace(getFilesDir().getAbsolutePath(), "私有目录")
                .replace(getExternalFilesDir(null) == null ? "\u0000" : getExternalFilesDir(null).getAbsolutePath(), "专属目录");
        meta.setText(size(f.length()) + " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(f.lastModified()))
                + "\n" + path);
        meta.setTextColor(DIM);
        meta.setTextSize(11.5f);
        meta.setPadding(0, dp(6), 0, dp(10));
        box.addView(meta);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("保存到相册", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToAlbum(f);
            }
        }));
        row.addView(button("分享", 0xFF2B3038, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share(f);
            }
        }));
        row.addView(button("打开", 0xFF2B3038, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(f);
            }
        }));
        box.addView(row);
        return box;
    }

    private Button button(String text, int color, View.OnClickListener click) {
        final Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setBackground(round(color, 10));
        b.setTextColor(TEXT);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnClickListener(click);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View hint(String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(DIM);
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(30), 0, dp(30));
        return tv;
    }

    private void saveToAlbum(File f) {
        toast(MediaPublisher.publish(this, f));
    }

    private void share(File f) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            final Intent it = new Intent(Intent.ACTION_SEND)
                    .setType(mimeOf(f.getName()))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享作品"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage());
        }
    }

    private void open(File f) {
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            final Intent it = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeOf(f.getName()))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(it);
        } catch (Throwable t) {
            toast("没有能打开它的应用");
        }
    }

    private static String mimeOf(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (n.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private static String size(long bytes) {
        if (bytes >= 1048576) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576f);
        }
        if (bytes >= 1024) {
            return String.format(Locale.CHINA, "%.0f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
