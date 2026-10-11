package com.mineways.conv;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 音频格式转换（平台 MediaCodec / MediaExtractor，零额外体积）。
 *
 * <pre>
 *   输入：mp3 / m4a(aac) / flac / opus / ogg / wav / amr / 3gp …（能被系统解码的都行）
 *   输出：wav（PCM 16bit，最稳）、m4a（AAC）、ogg（Opus，Android 10+）
 * </pre>
 *
 * <p>MP3 <b>编码</b>系统不提供（要 FFmpeg/lame），所以没有 mp3 输出 —— 需要时再挂原生库。
 */
public final class AudioConvert {

    public static final String[] OUTPUT_EXTS = {"wav", "m4a", "ogg"};

    public static final class Result {
        public boolean ok;
        public String message = "";

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final long TIMEOUT_US = 15000;

    private AudioConvert() {
    }

    public static Result convert(File in, File out, String target) {
        final String t = target == null ? "wav" : target.toLowerCase();
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(in.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final MediaFormat f = ex.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) {
                return new Result(false, "这个文件里没有音频轨（是不是视频？先把视频里的音轨提出来）");
            }
            ex.selectTrack(track);
            final String srcMime = fmt.getString(MediaFormat.KEY_MIME);
            if ("wav".equals(t)) {
                return decodeToWav(ex, fmt, in, out, srcMime);
            }
            return decodeEncodeMux(ex, fmt, out, t, srcMime);
        } catch (Throwable e) {
            return new Result(false, "音频转换失败：" + e);
        } finally {
            if (ex != null) {
                try {
                    ex.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 解码 → WAV

    private static Result decodeToWav(MediaExtractor ex, MediaFormat fmt, File in, File out, String srcMime)
            throws Exception {
        final MediaCodec dec = MediaCodec.createDecoderByType(srcMime);
        dec.configure(fmt, null, null, 0);
        dec.start();

        RandomAccessFile raf = null;
        int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        long pcmBytes = 0;
        long frames = 0;
        try {
            raf = new RandomAccessFile(out, "rw");
            raf.setLength(0);
            writeWavHeader(raf, sampleRate, channels, 0);

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    final int inIdx = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        final ByteBuffer buf = dec.getInputBuffer(inIdx);
                        final int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                final int outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    final ByteBuffer buf = dec.getOutputBuffer(outIdx);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        final byte[] chunk = new byte[info.size];
                        buf.get(chunk);
                        raf.write(chunk);
                        pcmBytes += chunk.length;
                        frames += chunk.length / (2L * Math.max(1, channels));
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat o = dec.getOutputFormat();
                    if (o.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (o.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            writeWavHeader(raf, sampleRate, channels, pcmBytes);
        } finally {
            try {
                dec.stop();
                dec.release();
            } catch (Throwable ignored) {
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (pcmBytes == 0) {
            return new Result(false, "解出来是空的（这个音频可能不可解码）");
        }
        final double sec = frames / (double) Math.max(1, sampleRate);
        return new Result(true, String.format("转 WAV 完成：%s → %s\n%d Hz · %d 声道 · %.1f 秒 · 输出 %s",
                in.getName(), out.getName(), sampleRate, channels, sec, human(out.length())));
    }

    private static void writeWavHeader(RandomAccessFile raf, int rate, int ch, long dataLen) throws Exception {
        final int bits = 16;
        final int blockAlign = ch * bits / 8;
        raf.seek(0);
        raf.write(bytes("RIFF"));
        raf.write(intLe(36 + dataLen));
        raf.write(bytes("WAVE"));
        raf.write(bytes("fmt "));
        raf.write(intLe(16));
        raf.write(shortLe((short) 1));            // PCM
        raf.write(shortLe((short) ch));
        raf.write(intLe(rate));
        raf.write(intLe(rate * blockAlign));
        raf.write(shortLe((short) blockAlign));
        raf.write(shortLe((short) bits));
        raf.write(bytes("data"));
        raf.write(intLe(dataLen));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] intLe(long v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array();
    }

    private static byte[] shortLe(short v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
    }

    // ---------------------------------------------------------------- 解码 → 编码 → 封装

    private static Result decodeEncodeMux(MediaExtractor ex, MediaFormat inFmt, File out,
                                          String target, String srcMime) throws Exception {
        final boolean ogg = "ogg".equals(target);
        final String encMime = ogg ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        final int outFormat = ogg ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

        if (ogg && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Result(false, "OGG(Opus) 封装需要 Android 10 及以上；这台设备请选 wav 或 m4a");
        }

        final MediaCodec dec = MediaCodec.createDecoderByType(srcMime);
        dec.configure(inFmt, null, null, 0);
        dec.start();

        // 解码先拿到输出格式，才能按真实采样率/声道配置编码器
        MediaFormat decOut = null;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        boolean inputDone = false;
        while (decOut == null) {
            if (!inputDone) {
                final int inIdx = dec.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    final ByteBuffer buf = dec.getInputBuffer(inIdx);
                    final int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                    if (n < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        dec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            final int outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                final ByteBuffer buf = dec.getOutputBuffer(outIdx);
                if (buf != null && info.size > 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    final byte[] chunk = new byte[info.size];
                    buf.get(chunk);
                    pending.write(chunk);
                }
                dec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decOut = dec.getOutputFormat();
            }
        }

        int sampleRate = decOut.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? decOut.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = decOut.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? decOut.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

        MediaCodec enc = MediaCodec.createEncoderByType(encMime);
        final MediaFormat encFmt = MediaFormat.createAudioFormat(encMime, sampleRate, channels);
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE, ogg ? 96000 : 128000);
        encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        if (encMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC)) {
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        }
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux = null;
        int trackIdx = -1;
        boolean muxStarted = false;
        long written = 0;
        try {
            mux = new MediaMuxer(out.getAbsolutePath(), outFormat);
            final byte[] pcm = pending.toByteArray();
            final int bytesPerFrame = 2 * Math.max(1, channels);
            final int frameChunk = bytesPerFrame * 1024;   // 每次喂 ~1024 帧
            int offset = 0;
            long ptsFrames = 0;
            boolean encInputDone = false, encOutputDone = false;
            final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            while (!encOutputDone) {
                if (!encInputDone) {
                    final int inIdx = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        final ByteBuffer buf = enc.getInputBuffer(inIdx);
                        buf.clear();
                        if (offset >= pcm.length) {
                            enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInputDone = true;
                        } else {
                            final int n = Math.min(frameChunk, pcm.length - offset);
                            buf.put(pcm, offset, n);
                            final long ptsUs = ptsFrames * 1000000L / Math.max(1, sampleRate);
                            enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
                            offset += n;
                            ptsFrames += n / (long) bytesPerFrame;
                        }
                    }
                }
                final int outIdx = enc.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (outIdx >= 0) {
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0) {
                        final ByteBuffer buf = enc.getOutputBuffer(outIdx);
                        if (buf != null) {
                            buf.position(encInfo.offset);
                            buf.limit(encInfo.offset + encInfo.size);
                            if (muxStarted) {
                                mux.writeSampleData(trackIdx, buf, encInfo);
                                written += encInfo.size;
                            }
                        }
                    }
                    enc.releaseOutputBuffer(outIdx, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encOutputDone = true;
                    }
                }
            }
        } finally {
            try {
                dec.stop();
                dec.release();
            } catch (Throwable ignored) {
            }
            try {
                enc.stop();
                enc.release();
            } catch (Throwable ignored) {
            }
            if (mux != null) {
                try {
                    if (muxStarted) {
                        mux.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    mux.release();
                } catch (Throwable ignored) {
                }
            }
        }

        if (written == 0) {
            return new Result(false, "编码器没吐出数据（" + target + " 在这台设备上可能不支持）");
        }
        return new Result(true, String.format("转换完成 → %s\n%d Hz · %d 声道 · 输出 %s",
                target, sampleRate, channels, human(out.length())));
    }

    private static String human(long bytes) {
        if (bytes >= 1048576) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
