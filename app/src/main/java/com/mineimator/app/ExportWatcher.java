package com.mineimator.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.mineways.MediaPublisher;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>导出自动接管</b>：工作室里点「导出」后，内核（自带 FFmpeg，H.264+AAC+MP4）会把成品写到应用私有目录；
 * 这里盯着新出现的视频/图片，一旦写稳就自动送进系统相册并提示 —— 用户不需要再手动保存。
 *
 * <p>实现要点：进入工作室时先把已有文件登记为"已知"（避免把旧作品重复推一次）；
 * 之后每 2.5 秒扫一次，文件大小连续两次不变才算写稳（防止推到半个文件）。
 */
public final class ExportWatcher {

    private static final long INTERVAL_MS = 2500L;

    private static final String[] MEDIA_EXT = {"mp4", "mov", "mkv", "webm", "gif", "png", "jpg", "jpeg"};

    /** 引擎解包出来的资源树与导入源文件不算作品。 */
    private static final String[] SKIP_DIRS = {"assets", "imports", "Data", "Sprites", "Compiled"};

    private final Activity activity;
    private final java.util.List<File> roots;
    private final Set<String> known = new HashSet<>();
    private final Map<String, Long> sizes = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private boolean enabled = true;

    public ExportWatcher(Activity activity, boolean enabled) {
        this.activity = activity;
        this.enabled = enabled;
        // 存储根可能被切到外部可见目录，两个根都盯着
        this.roots = ProjectStorage.allRoots(activity);
        for (File r : roots) {
            collectExisting(r, 0);
        }
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mim-export-watch");
        t.setDaemon(true);
        t.start();
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void stop() {
        running = false;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!enabled) {
                continue;
            }
            for (File r : roots) {
                scan(r, 0);
            }
        }
    }

    /** 进入工作室时登记已有文件，只对"新出现"的文件动手。 */
    private void collectExisting(File dir, int depth) {
        if (dir == null || depth > 6) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!skip(f)) {
                    collectExisting(f, depth + 1);
                }
            } else if (isMedia(f)) {
                known.add(f.getAbsolutePath());
            }
        }
    }

    private void scan(File dir, int depth) {
        if (dir == null || depth > 6 || !running) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!skip(f)) {
                    scan(f, depth + 1);
                }
                continue;
            }
            if (!isMedia(f) || known.contains(f.getAbsolutePath())) {
                continue;
            }
            final long size = f.length();
            final Long last = sizes.get(f.getAbsolutePath());
            if (size <= 0) {
                continue;
            }
            if (last == null || last != size) {
                // 还在写：记住当前大小，下一轮再看
                sizes.put(f.getAbsolutePath(), size);
                continue;
            }
            // 大小连续两轮不变 = 写稳了
            known.add(f.getAbsolutePath());
            publish(f);
        }
    }

    private void publish(final File f) {
        final String message = MediaPublisher.publish(activity, f);
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, "导出完成 · " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean skip(File dir) {
        for (String s : SKIP_DIRS) {
            if (s.equals(dir.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMedia(File f) {
        final String name = f.getName();
        final int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        final String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean knownExt = false;
        for (String e : MEDIA_EXT) {
            if (e.equals(ext)) {
                knownExt = true;
                break;
            }
        }
        if (!knownExt) {
            return false;
        }
        final boolean video = "mp4".equals(ext) || "mov".equals(ext) || "mkv".equals(ext)
                || "webm".equals(ext) || "gif".equals(ext);
        // 小图多为工程贴图，不当作品自动推；视频一律管
        return video || f.length() >= 32 * 1024L;
    }
}
