package com.mineways;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频抽帧（系统 MediaMetadataRetriever，不依赖第三方库）。
 *
 * <p>给「视频 → GIF」「视频 → 逐帧 PNG」「视频 → 材质动画贴图」用：
 * 按起始/结束时间与目标帧率均匀取样，自动处理手机竖拍的旋转信息，并限制最大宽度。</p>
 */
public final class VideoFrames {

    /** 抽帧进度回调（在主线程外调用，调用方自行切回 UI 线程）。 */
    public interface Progress {
        void onStep(int done, int total);
    }

    /** 视频基本信息。 */
    public static final class Info {
        public boolean ok;
        public long durationMs;
        public int width;
        public int height;
        public int rotation;
        public String error = "";
    }

    private VideoFrames() {
    }

    public static Info info(Context ctx, Uri uri) {
        Info i = new Info();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            i.durationMs = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            i.width = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            i.height = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            i.rotation = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            i.ok = i.durationMs > 0;
            if (!i.ok) {
                i.error = "读不到视频时长（可能不是视频文件）";
            }
        } catch (Throwable t) {
            i.error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        } finally {
            release(r);
        }
        return i;
    }

    /**
     * 抽帧。
     *
     * @param startSec 起始秒（&lt;0 视为 0）
     * @param endSec   结束秒（&lt;=0 或大于时长 → 到片尾）
     * @param fps      目标帧率（1~30，内部再按 maxFrames 兜底）
     * @param maxWidth 最大宽度（&lt;=0 不缩放）
     * @param maxFrames 帧数上限（防手滑导出几百帧）
     */
    public static List<Bitmap> extract(Context ctx, Uri uri, double startSec, double endSec,
                                       int fps, int maxWidth, int maxFrames, Progress cb) {
        List<Bitmap> out = new ArrayList<>();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            long durUs = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            if (durUs <= 0) {
                throw new IllegalArgumentException("视频时长读不出来，换个文件试试");
            }
            int rotation = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));

            long s = (long) (Math.max(0, startSec) * 1_000_000L);
            long e = endSec > 0 ? (long) (endSec * 1_000_000L) : durUs;
            if (e > durUs) {
                e = durUs;
            }
            if (e <= s) {
                s = 0;
                e = durUs;
            }

            int total = (int) Math.max(1, Math.round((e - s) / 1_000_000d * Math.max(1, fps)));
            int cap = maxFrames <= 0 ? 96 : maxFrames;
            if (total > cap) {
                total = cap;
            }

            double stepUs = (e - s) / (double) total;
            for (int i = 0; i < total; i++) {
                long t = s + (long) (i * stepUs);
                Bitmap b = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST);
                if (b != null) {
                    if (rotation != 0) {
                        b = rotate(b, rotation);
                    }
                    if (maxWidth > 0 && b.getWidth() > maxWidth) {
                        b = scale(b, maxWidth);
                    }
                    out.add(b);
                }
                if (cb != null) {
                    cb.onStep(i + 1, total);
                }
            }
        } finally {
            release(r);
        }
        return out;
    }

    /** 按扩展名/MIME 猜是不是视频（拆帧、材质贴图两种模式要区分 GIF 与视频）。 */
    public static boolean looksLikeVideo(Context ctx, Uri uri, String displayName) {
        String name = displayName == null ? "" : displayName.toLowerCase();
        if (name.endsWith(".gif")) {
            return false;
        }
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                || name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".3gp")
                || name.endsWith(".m4v") || name.endsWith(".flv")) {
            return true;
        }
        try {
            String type = ctx.getContentResolver().getType(uri);
            return type != null && type.startsWith("video/");
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Bitmap rotate(Bitmap b, int degrees) {
        try {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (out != b) {
                b.recycle();
            }
            return out;
        } catch (Throwable t) {
            return b;
        }
    }

    private static Bitmap scale(Bitmap b, int maxWidth) {
        float k = (float) maxWidth / b.getWidth();
        int w = Math.max(1, (int) (b.getWidth() * k));
        int h = Math.max(1, (int) (b.getHeight() * k));
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) {
            b.recycle();
        }
        return out;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void release(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (Throwable ignored) {
        }
    }
}
