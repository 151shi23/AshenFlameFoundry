package com.mineways.assets;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 内置静态站（莱茵生命终端 / 寂零快跑）的「按需下载 + 外部优先」支持。
 *
 * <p>这三个站的页面本身是纯静态的，资源和 OpenCut 一样放在 AshenFlameAssets 的 Release 里。
 * 这类的共同点：入口是 {@code <id>/index.html}，WebView 通过
 * {@code /assets/<id>/...} 取文件。
 *
 * <p>兼容老包：APK 里还带着站点的版本不弹下载，直接用内置那份；只有新包（素材已移出）才走下载。
 */
public final class PackAssets {

    private PackAssets() {
    }

    /** 下载后的安装目录：filesDir/assets/<id>。 */
    public static File dir(Context c, String id) {
        return new File(new File(c.getFilesDir(), "assets"), id);
    }

    /** 下载版是否已就绪（以 index.html 为准）。 */
    public static boolean ready(Context c, String id) {
        return new File(dir(c, id), "index.html").isFile();
    }

    /** APK 内置素材里还有没有这个站（老包兼容）。 */
    public static boolean hasBuiltIn(Context c, String id) {
        try (InputStream in = c.getAssets().open(id + "/index.html")) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * WebView 的 {@code /assets/} 处理器：下载版优先，找不到再回落 APK 内置。
     */
    public static WebViewAssetLoader.PathHandler handler(Context c, final String id) {
        final WebViewAssetLoader.AssetsPathHandler apk =
                new WebViewAssetLoader.AssetsPathHandler(c);
        final File root = dir(c, id);
        String rootPath;
        try {
            rootPath = root.getCanonicalPath();
        } catch (Throwable t) {
            rootPath = root.getAbsolutePath();
        }
        final String prefix = id + "/";
        final String base = rootPath;
        final String idPrefix = prefix;

        return path -> {
            if (path != null && path.startsWith(idPrefix)) {
                try {
                    File f = new File(root, path.substring(idPrefix.length())).getCanonicalFile();
                    if (f.getPath().startsWith(base + File.separator) && f.isFile()) {
                        return new WebResourceResponse(mime(path), null,
                                new BufferedInputStream(new FileInputStream(f), 64 * 1024));
                    }
                } catch (Throwable ignored) {
                    // 落到内置那份
                }
            }
            return apk.handle(path);
        };
    }

    /**
     * 进站前确保资源就绪：已装/内置直接放行；否则弹确认 → 下载 → 校验 → 放行。
     *
     * @param onReady 资源就绪后回调（主线程），调用方在这里 loadUrl
     */
    public static void ensure(final Activity activity, final String packId,
                              final String title, final Runnable onReady) {
        if (ready(activity, packId) || hasBuiltIn(activity, packId)) {
            onReady.run();
            return;
        }
        new Thread(() -> {
            final String[] mirror = new String[1];
            List<AssetPacks.Pack> packs;
            try {
                packs = AssetPacks.fetchManifest(mirror);
            } catch (Throwable t) {
                activity.runOnUiThread(() -> error(activity, "拿不到资源清单", t.getMessage(), () ->
                        ensure(activity, packId, title, onReady)));
                return;
            }
            AssetPacks.Pack found = null;
            for (AssetPacks.Pack p : packs) {
                if (packId.equals(p.id)) {
                    found = p;
                    break;
                }
            }
            if (found == null) {
                activity.runOnUiThread(() -> error(activity, "资源清单里没有 " + packId, "",
                        () -> ensure(activity, packId, title, onReady)));
                return;
            }
            final AssetPacks.Pack pack = found;
            activity.runOnUiThread(() -> confirm(activity, pack, title, mirror[0], onReady));
        }, "pack-manifest").start();
    }

    private static void confirm(final Activity activity, final AssetPacks.Pack pack,
                                final String title, String mirror, final Runnable onReady) {
        String msg = "「" + title + "」的素材不随安装包分发，需要单独下载一次。\n\n"
                + "体积：" + pack.sizeText() + "（解压后约 " + AssetPacks.unpackedMb(pack) + " MB）\n"
                + "来源：" + (mirror == null ? "GitHub" : mirror) + "\n\n"
                + "下载一次就够了，之后不用联网。";
        new AlertDialog.Builder(activity)
                .setTitle("需要下载素材包")
                .setMessage(msg)
                .setPositiveButton("下载", (d, w) -> download(activity, pack, title, onReady))
                .setNegativeButton("返回", (d, w) -> activity.finish())
                .setCancelable(false)
                .show();
    }

    private static void download(final Activity activity, final AssetPacks.Pack pack,
                                 final String title, final Runnable onReady) {
        final boolean[] cancel = {false};
        final PackProgressDialog progress = PackProgressDialog.show(activity,
                "正在下载" + title, () -> cancel[0] = true);

        new Thread(() -> {
            try {
                AssetPacks.install(activity.getApplicationContext(), pack, new AssetPacks.Progress() {
                    @Override
                    public void onProgress(String stage, long done, long total) {
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()
                                    || !progress.isAlive()) return;
                            progress.update(stage, done, total);
                        });
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancel[0];
                    }
                });
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    progress.dismiss();
                    if (!ready(activity, pack.id)) {
                        error(activity, "素材包结构不对",
                                "解压完成，但没有找到 index.html。\n目录：" + dir(activity, pack.id),
                                () -> download(activity, pack, title, onReady));
                        return;
                    }
                    onReady.run();
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    progress.dismiss();
                    String m = t.getMessage();
                    if (m != null && m.contains("已取消")) {
                        activity.finish();
                    } else {
                        error(activity, "下载失败", m, () -> download(activity, pack, title, onReady));
                    }
                });
            }
        }, "pack-dl").start();
    }

    private static void error(final Activity activity, String title, String detail, Runnable retry) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(detail == null || detail.isEmpty() ? "请检查网络后重试" : detail)
                .setPositiveButton("重试", (d, w) -> retry.run())
                .setNegativeButton("返回", (d, w) -> activity.finish())
                .setCancelable(false)
                .show();
    }

    private static String mime(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".woff2")) return "font/woff2";
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            String ext = lower.substring(dot + 1);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}
