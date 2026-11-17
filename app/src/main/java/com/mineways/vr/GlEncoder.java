package com.mineways.vr;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * GL 直接渲染到 MediaCodec 的输入 Surface，边画边硬编。
 *
 * <p>为什么不复用 {@code blender.SequenceEncoder}：那个接口是「PNG 序列 → MP4」，
 * 4K 一帧 PNG 就有十几 MB，180 帧要写 2~3 GB 磁盘再读回来，手机上根本跑不动。
 * 这里全程在显存里转，不落中间文件。
 *
 * <p>输出固定 H.264（抖音只收这个），yuv420p，码率按分辨率自适应。
 */
public final class GlEncoder {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int TIMEOUT_US = 10_000;

    /** 每一帧要画什么。实现方自己绑纹理、设 uniform、画一个全屏三角形。 */
    public interface FrameDrawer {
        /**
         * @param index   第几帧（从 0 开始）
         * @param timeSec 这一帧对应的时间（秒）
         */
        void drawFrame(int index, float timeSec);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private GlEncoder() {
    }

    /** 按分辨率与帧率给码率：默认 0.12 bit/像素，夹在 8~120 Mbps。 */
    public static int bitrateFor(int w, int h, int fps) {
        return bitrateFor(w, h, fps, 12);
    }

    /**
     * @param bppPercent 每像素每帧的比特数（百分比）：8 = 省空间，12 = 标准，20 = 高画质
     */
    public static int bitrateFor(int w, int h, int fps, int bppPercent) {
        long raw = (long) w * h * Math.max(1, fps) * bppPercent / 100;
        return (int) Math.max(8_000_000L, Math.min(120_000_000L, raw));
    }

    /**
     * 本机 H.264 编码器支持的最大宽高。
     *
     * <p>8K（7680×3840）只有少数旗舰机的硬编吃得住，所以选 8K 前先问一下，
     * 超了就按比例降到 4K，而不是让用户等到最后一步才失败。
     */
    public static int[] maxEncodeSize() {
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            int maxW = 0;
            int maxH = 0;
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(type)) continue;
                    android.media.MediaCodecInfo.VideoCapabilities vc =
                            info.getCapabilitiesForType(type).getVideoCapabilities();
                    maxW = Math.max(maxW, vc.getSupportedWidths().getUpper());
                    maxH = Math.max(maxH, vc.getSupportedHeights().getUpper());
                }
            }
            if (maxW > 0 && maxH > 0) return new int[]{maxW, maxH};
        } catch (Throwable ignored) {
        }
        return new int[]{3840, 2160};
    }

    /**
     * 渲染并编码。
     *
     * @param out      输出 MP4（会被覆盖）
     * @param width    宽，必须是偶数
     * @param height   高，必须是偶数
     * @param fps      帧率
     * @param seconds  时长（秒）
     * @param drawer   每帧绘制回调（在 GL 线程上调用，必须是当前线程）
     * @param progress 进度回调，可以为 null
     */
    public static void encode(File out, int width, int height, int fps, int seconds,
                              FrameDrawer drawer, Progress progress) throws IOException {
        encode(out, width, height, fps, seconds, bitrateFor(width, height, fps),
                drawer, progress);
    }

    /** 指定码率的版本。 */
    public static void encode(File out, int width, int height, int fps, int seconds, int bitrate,
                              FrameDrawer drawer, Progress progress) throws IOException {
        int w = (width / 2) * 2;
        int h = (height / 2) * 2;
        int total = Math.max(1, fps * Math.max(1, seconds));

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", w, h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // 让编码器按质量优先，别为了省码率把 360 画面糊掉
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            }

            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = codec.createInputSurface();
            codec.start();

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ---- EGL：把 GL 输出接到编码器的输入 Surface 上
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(display, ver, 0, ver, 1);

            int[] cfgAttrs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw new IOException("找不到可用的 EGL 配置");
            }

            int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
            int[] surfAttrs = {EGL14.EGL_NONE};
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input, surfAttrs, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw new IOException("创建 EGLSurface 失败");
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IOException("eglMakeCurrent 失败");
            }

            // ---- 逐帧渲染
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean muxerStarted = false;
            int track = -1;

            for (int i = 0; i < total; i++) {
                drawer.drawFrame(i, i / (float) fps);
                EGLExt.eglPresentationTimeANDROID(display, surface, i * 1_000_000_000L / fps);
                if (!EGL14.eglSwapBuffers(display, surface)) {
                    throw new IOException("eglSwapBuffers 失败 @" + i);
                }

                // 取编码输出
                while (true) {
                    int idx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            track = muxer.addTrack(codec.getOutputFormat());
                            muxer.start();
                            muxerStarted = true;
                        }
                    } else if (idx >= 0) {
                        ByteBuffer buf = codec.getOutputBuffer(idx);
                        if (buf != null && info.size > 0 && muxerStarted
                                && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            muxer.writeSampleData(track, buf, info);
                        }
                        codec.releaseOutputBuffer(idx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    } else {
                        break;
                    }
                }

                if (progress != null && (i % 5 == 0 || i == total - 1)) {
                    progress.onProgress(i + 1, total);
                }
            }

            // ---- 收尾
            codec.signalEndOfInputStream();
            boolean eos = false;
            long deadline = System.currentTimeMillis() + 15_000;
            while (!eos && System.currentTimeMillis() < deadline) {
                int idx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                    }
                    codec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 继续等
                }
            }
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
