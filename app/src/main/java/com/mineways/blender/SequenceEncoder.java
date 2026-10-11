package com.mineways.blender;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * PNG 序列 → MP4，用 Android 自带的 {@link MediaCodec} 硬件编码器。
 *
 * <p>为什么要有这条「移植」出来的路：Blender 自带 FFmpeg 直出视频很方便，但它是软编码，
 * 又慢又吃电（手机没有 GPU 编码可用时尤其明显）。而渲染序列（可断点续渲、可先出图再看）
 * 再用系统硬编压成 mp4，是手机本地最省时间的组合。</p>
 */
public final class SequenceEncoder {

    public interface Progress {
        void onProgress(int done, int total);
    }

    private SequenceEncoder() {
    }

    /** 码率：按分辨率与帧率给一个偏保守的值（越高越清晰、文件越大）。 */
    private static int bitrateFor(int w, int h, int fps) {
        final double bpp = 0.12;
        final int raw = (int) (w * h * Math.max(1, fps) * bpp);
        return Math.max(2_000_000, Math.min(40_000_000, raw / 8 * 8));
    }

    public static File encode(List<File> frames, File out, int fps,
                              Progress pr, Downloader.Cancel cancel) throws Exception {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("没有待编码的帧");
        }
        FileUtil.mkdirs(out.getParentFile());

        // 以第一帧为准定尺寸（H.264 要求偶数）
        Bitmap first = decode(frames.get(0));
        if (first == null) throw new IllegalStateException("读不出第一帧：" + frames.get(0));
        int width = first.getWidth() & ~1;
        int height = first.getHeight() & ~1;
        first.recycle();

        final MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, fps));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        final MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        final Surface surface = codec.createInputSurface();
        codec.start();

        final MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        final int dstW = width;
        final int dstH = height;
        final Rect dstRect = new Rect(0, 0, dstW, dstH);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final MuxState mux = new MuxState();

        try {
            final int total = frames.size();
            for (int i = 0; i < total; i++) {
                if (cancel != null && cancel.isCancelled()) throw new IllegalStateException("已取消");
                final File f = frames.get(i);
                final Bitmap bmp = decode(f);
                if (bmp == null) {
                    throw new IllegalStateException("读不出帧：" + f.getName());
                }
                final Canvas canvas = surface.lockCanvas(null);
                try {
                    canvas.drawColor(0xFF000000);
                    final RectF dst = new RectF(dstRect);
                    canvas.drawBitmap(bmp, null, dst, null);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                    bmp.recycle();
                }
                if (pr != null) pr.onProgress(i + 1, total);
                drainOnce(codec, muxer, info, mux);
            }
            codec.signalEndOfInputStream();
            int guard = 0;
            while (guard++ < 600 && !mux.eos) {
                if (drainOnce(codec, muxer, info, mux) && mux.eos) break;
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            codec.release();
            if (mux.started) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {
                }
            }
            muxer.release();
        }
        if (!out.isFile() || out.length() < 1024) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IllegalStateException("编码失败：没有产出文件（机型可能不支持该尺寸的硬编）。");
        }
        return out;
    }

    private static final class MuxState {
        int track = -1;
        boolean started;
        boolean eos;
    }

    /** 抽一次编码器输出写进 mp4。 */
    private static boolean drainOnce(MediaCodec codec, MediaMuxer muxer, MediaCodec.BufferInfo info,
                                     MuxState mux) {
        try {
            final int r = codec.dequeueOutputBuffer(info, 20_000);
            if (r == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (r == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mux.started) {
                    mux.track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    mux.started = true;
                }
                return false;
            }
            if (r < 0) return false;
            final ByteBuffer buf = codec.getOutputBuffer(r);
            if (buf == null) return false;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                info.size = 0;
            }
            if (info.size > 0 && mux.started) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                muxer.writeSampleData(mux.track, buf, info);
            }
            final boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(r, false);
            if (end) mux.eos = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解码一张图（按目标尺寸降采样，避免 4K 图直接进内存爆掉）。 */
    private static Bitmap decode(File f) {
        try {
            final BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            int sample = 1;
            final int max = Math.max(opt.outWidth, opt.outHeight);
            while (max / sample > 2048) sample *= 2;
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            final Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp == null) {
                throw new IllegalStateException("BitmapFactory 返回 null：" + f.getName());
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 设备上的 H.264 编码器名（几乎都有硬编；这里用于在 UI 上说明）。 */
    public static String encoderName() {
        try {
            final MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
            final String name = codec.getCodecInfo().getName();
            codec.release();
            return name;
        } catch (Throwable t) {
            return "无（" + t.getClass().getSimpleName() + "）";
        }
    }

    /** 只保留图片文件，按文件名自然序（frame_0001 < frame_0002）。 */
    public static List<File> framesIn(File dir, String prefix) {
        final List<File> out = new java.util.ArrayList<>();
        final File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            final String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!n.endsWith(".png") && !n.endsWith(".jpg") && !n.endsWith(".jpeg")) continue;
            if (prefix != null && !prefix.isEmpty() && !f.getName().startsWith(prefix)) continue;
            out.add(f);
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }
}
