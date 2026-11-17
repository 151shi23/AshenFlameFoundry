// ============================================================================
// PoseCaptureBridge.java —— Android 端采集桥接参考实现（源码参考，不随桌面端编译）
//
// 作用：手机相机 → MediaPipe Pose Landmarker（实时）→ 33 关键点 CSV
//       （格式与桌面端 MocapIO 完全同源）+ 实时关节标注叠加层
// 采集的 CSV 用桌面端 `mocap4mc convert/product` 完成解算与动画输出。
// PoseSolver/EulerBedrock/Annotator 均为纯 Java 8 零依赖，也可直接搬进 App
// 在手机本地解算（省去传 CSV）。
//
// 依赖（build.gradle, minSdk 24+）：
//   implementation 'com.google.mediapipe:tasks-vision:1.0.0'
//   def camerax = '1.4.2'
//   implementation "androidx.camera:camera-core:$camerax"
//   implementation "androidx.camera:camera-camera2:$camerax"
//   implementation "androidx.camera:camera-lifecycle:$camerax"
//   implementation "androidx.camera:camera-view:$camerax"
// 模型：pose_landmarker_lite.task 放 assets/
//   （https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task）
// ============================================================================
//
package com.mineways.mocap;

import android.content.Context;
import android.graphics.*;
import android.util.Size;
import android.view.View;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PoseCaptureBridge {

    private PoseLandmarker landmarker;
    /** 手部模型（超高精度模式）：解码 21 点/手，给手指骨骼用。 */
    private HandLandmarker handLandmarker;
    private volatile String handModelError;
    private volatile boolean handsEnabled = true;
    private BufferedWriter csv;
    private long frameMs = 0, seq = 0;
    private int imgW = 1280, imgH = 720;
    private OverlayView overlay;          // 实时标注层（见下）
    private boolean frontCamera = true;   // 前摄画面默认镜像
    /** 录制中的帧：本地解算直接吃内存对象，省一次 CSV 往返（CSV 仍然照写，留作复算）。 */
    private final List<Pose33> frames = new ArrayList<Pose33>();
    private volatile boolean recording = false;
    private String csvPath;
    /** 实际加载的模型文件名（heavy / full / lite）。 */
    private String modelAsset;
    private volatile String modelError;
    private volatile String poseError;
    private volatile String frameError;
    private final java.util.concurrent.atomic.AtomicLong inFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong detectedFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong resultFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong handResultFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong handsDetected = new java.util.concurrent.atomic.AtomicLong();
    /** 最近一次有效姿态：检测短暂丢帧（≤400ms）时用它顶住，避免录制出现时间空洞。 */
    private Pose33 lastPose;
    private long lastPoseMs;
    static final long HOLD_MAX_MS = 400;
    /** 录制中的帧按 MediaPipe 时间戳索引：手部结果是异步回来的，靠 ts 精确对到同一帧。 */
    private final Map<Long, Pose33> recentFrames = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<Long, Pose33>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Pose33> e) {
                    return size() > 120;
                }
            });
    /** MediaPipe 要求时间戳**严格递增**，同一毫秒送两帧会整帧被丢。 */
    private volatile long lastTimestampMs = 0;
    /** 本段录制的起始时刻（0 = 还没开始）。帧时间戳一律减它，保证动画从 0 秒起算。 */
    private volatile long recordStartMs = 0;
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();

    public void start(Context ctx, LifecycleOwner owner, PreviewView view) throws Exception {
        overlay = new OverlayView(ctx);              // 实时标注层（原先无参构造，这里补 Context）

        // 1) CameraX 先起：预览立刻可见，用户马上能判断"相机正常"。
        //    RGBA_8888 直出：省掉 YUV→Bitmap 的转换（原稿的 yuvToBitmap 只是占位）。
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(view.getSurfaceProvider());
        final ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(960, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        // 分析器**先挂上**：模型未就绪时 analyze 只计数就关帧（代价极小），
        // 就绪后同一路径直接开跑。反过来"绑定后再 setAnalyzer"在部分 CameraX 版本上
        // 不会重新配置 pipeline → 表现为永远没有帧。
        analysis.setAnalyzer(analyzer, this::analyze);
        ProcessCameraProvider.getInstance(ctx).addListener(() -> {
            try {
                ProcessCameraProvider p = ProcessCameraProvider.getInstance(ctx).get();
                p.unbindAll();
                p.bindToLifecycle(owner,
                        frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);
            } catch (Throwable e) {
                android.util.Log.e("mocap4mc", "camera bind failed", e);
                modelError = "相机绑定失败：" + android.util.Log.getStackTraceString(e);
            }
        }, ContextCompat.getMainExecutor(ctx));

        openCsv(ctx);

        // 2) 模型在**后台线程**加载：heavy 29MB 在部分机上要几秒，放主线程会卡死/ANR，
        //    表现出来就是"模型没加载"。逐档降级，成功后立刻开始分析。
        new Thread(() -> {
            Throwable last = null;
            for (String name : MODELS) {
                if (!assetExists(ctx, name)) {
                    continue;
                }
                try {
                    PoseLandmarker lm = PoseLandmarker.createFromOptions(ctx, optionsFor(name));
                    landmarker = lm;
                    modelAsset = name;
                    modelError = null;
                    android.util.Log.i("mocap4mc", "pose model loaded: " + name);
                    loadHandModel(ctx);
                    return;
                } catch (Throwable e) {
                    last = e;
                    android.util.Log.w("mocap4mc", "pose model failed: " + name, e);
                }
            }
            modelError = last == null
                    ? "assets 里没有 pose_landmarker_*.task"
                    : android.util.Log.getStackTraceString(last);
            android.util.Log.e("mocap4mc", "no pose model loaded: " + modelError);
        }, "mocap-model").start();
    }

    /** 精度优先的模型档位。 */
    private static final String[] MODELS = {
            "pose_landmarker_heavy.task", "pose_landmarker_full.task", "pose_landmarker_lite.task"};
    private static final String HAND_MODEL = "hand_landmarker.task";

    /**
     * 手部模型（21 点/手）。只在超高精度模式下加载；失败不影响身体动捕，原因显示在页面上。
     * 输入图像是**未镜像**的相机画面，而 MediaPipe 的左右手判定假设输入是镜像的 —— 结果里要交换。
     */
    private void loadHandModel(Context ctx) {
        if (!handsEnabled) {
            return;
        }
        if (!assetExists(ctx, HAND_MODEL)) {
            handModelError = "assets 里没有 " + HAND_MODEL;
            return;
        }
        if (!taskBundleComplete(ctx, HAND_MODEL)) {
            // 下载被截断时（文件开头是 zip、结尾却没有 zip 目录）MediaPipe 只会抛
            // 「Unable to open zip archive」，看不出是文件坏了 —— 这里先判死并说清楚。
            handModelError = HAND_MODEL + " 不完整（文件结尾缺 zip 目录，通常是下载中断）。"
                    + "请换一份完整的模型文件重新打包。";
            return;
        }
        try {
            BaseOptions base = BaseOptions.builder().setModelAssetPath(HAND_MODEL).build();
            HandLandmarker.HandLandmarkerOptions opt = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener((r, input) -> onHands(r))
                    .setErrorListener(e -> {
                        handModelError = android.util.Log.getStackTraceString(e);
                        android.util.Log.e("mocap4mc", "hand error", e);
                    })
                    .build();
            handLandmarker = HandLandmarker.createFromOptions(ctx, opt);
            handModelError = null;
            android.util.Log.i("mocap4mc", "hand model loaded");
        } catch (Throwable e) {
            handModelError = android.util.Log.getStackTraceString(e);
            android.util.Log.e("mocap4mc", "hand model failed", e);
        }
    }

    /** 手部结果：按时间戳对回同一帧，填进 21 点。 */
    private void onHands(HandLandmarkerResult r) {
        handResultFrames.incrementAndGet();
        if (!recording || r == null) {
            return;
        }
        Pose33 p = recentFrames.get(r.timestampMs());
        if (p == null) {
            return;
        }
        List<List<NormalizedLandmark>> all = r.landmarks();
        List<List<com.google.mediapipe.tasks.components.containers.Category>> handed = r.handedness();
        for (int h = 0; h < all.size() && h < 2; h++) {
            boolean isLeft = true;
            if (handed != null && h < handed.size() && !handed.get(h).isEmpty()) {
                // 输入未镜像 → MediaPipe 的 Left/Right 是反的
                isLeft = !"Left".equalsIgnoreCase(handed.get(h).get(0).categoryName());
            }
            int slot = isLeft ? 0 : 1;
            List<NormalizedLandmark> lm = all.get(h);
            for (int i = 0; i < 21 && i < lm.size(); i++) {
                NormalizedLandmark m = lm.get(i);
                p.handX[slot][i] = frontCamera ? 1.0 - m.x() : m.x();
                p.handY[slot][i] = m.y();
                p.handZ[slot][i] = m.z();
                p.handVis[slot][i] = m.visibility().orElse(1f);
            }
            p.handSeen[slot] = true;
            handsDetected.incrementAndGet();
        }
    }

    private static boolean assetExists(Context ctx, String name) {
        try (java.io.InputStream in = ctx.getAssets().open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** MediaPipe 的 .task 是一个 zip：最后 22 字节必须是 EOCD（"PK\x05\x06"）。截断的包会缺它。 */
    private static boolean taskBundleComplete(Context ctx, String name) {
        try (java.io.InputStream in = ctx.getAssets().open(name)) {
            int size = in.available();
            if (size < 1024) {
                return false;
            }
            long skip = size - 22L;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) {
                    break;
                }
                skip -= n;
            }
            byte[] tail = new byte[22];
            int read = 0;
            while (read < tail.length) {
                int n = in.read(tail, read, tail.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return read == 22 && tail[0] == 'P' && tail[1] == 'K'
                    && tail[2] == 5 && tail[3] == 6;
        } catch (Throwable e) {
            return false;
        }
    }

    private PoseLandmarker.PoseLandmarkerOptions optionsFor(String asset) {
        BaseOptions base = BaseOptions.builder().setModelAssetPath(asset).build();
        return PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                // 追踪/检测门限降到 0.4：动捕是连续单人画面，宁可多给几帧也不要在
                // 侧身、抬手遮脸时整帧丢检测（丢帧 → 时间轴空洞 → 动作一顿一顿）。
                // 误检由解算器的可见度门 + 中值/平滑后处理兜住。
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.4f)
                .setMinPosePresenceConfidence(0.4f)
                .setMinTrackingConfidence(0.4f)
                .setOutputSegmentationMasks(false)
                .setResultListener((result, input) -> onPose(result))
                .setErrorListener(e -> {
                    poseError = android.util.Log.getStackTraceString(e);
                    android.util.Log.e("mocap4mc", "pose error", e);
                })
                .build();
    }

    /** 实际用的精度档位（heavy / full / lite），UI 上要显示出来。 */
    public String modelAsset() {
        return modelAsset == null ? "?" : modelAsset;
    }

    /** 一帧分析：RGBA_8888 → Bitmap → MediaPipe（时间戳必须严格递增）。 */
    private void analyze(ImageProxy proxy) {
        inFrames.incrementAndGet();
        try {
            ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
            int w = proxy.getWidth();
            int h = proxy.getHeight();
            java.nio.ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (pixelStride == 4 && rowStride == w * 4) {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            } else if (pixelStride >= 4) {
                // 行填充 / 像素跨距：直接 copyPixelsFromBuffer 会抛 BufferUnderflow，
                // 而那个异常被 catch 吞掉 —— 表现就是"相机帧在涨、检测帧永远是 0"。
                byte[] row = new byte[Math.max(rowStride, w * pixelStride)];
                int[] px = new int[w * h];
                buf.rewind();
                for (int y = 0; y < h; y++) {
                    buf.position(y * rowStride);
                    int n = Math.min(row.length, buf.remaining());
                    buf.get(row, 0, n);
                    for (int x = 0; x < w; x++) {
                        int i = x * pixelStride;
                        if (i + 3 >= n) {
                            break;
                        }
                        int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF, a = row[i + 3] & 0xFF;
                        px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
                bmp.setPixels(px, 0, w, 0, 0, w, h);
            } else {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            }
            int rotation = proxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false);
            }
            imgW = bmp.getWidth();
            imgH = bmp.getHeight();
            PoseLandmarker lm = landmarker;
            HandLandmarker hl = handLandmarker;
            if (lm != null || hl != null) {
                // ns → ms；同一毫秒里的第二帧必须往后推 1ms，否则 MediaPipe 直接丢帧
                long ts = proxy.getImageInfo().getTimestamp() / 1000L;
                if (ts <= lastTimestampMs) {
                    ts = lastTimestampMs + 1;
                }
                lastTimestampMs = ts;
                if (lm != null) {
                    lm.detectAsync(new BitmapImageBuilder(bmp).build(), ts);
                    detectedFrames.incrementAndGet();
                }
                if (hl != null) {
                    // 同一帧、同一时间戳送手部模型：结果回来靠 ts 对回这一帧
                    hl.detectAsync(new BitmapImageBuilder(bmp).build(), ts);
                }
            }
        } catch (Throwable e) {
            frameError = android.util.Log.getStackTraceString(e);
            android.util.Log.w("mocap4mc", "analyze frame failed", e);
        } finally {
            proxy.close();
        }
    }

    /**
     * 不依赖相机的自检：用纯色 Bitmap 在 IMAGE 模式下跑一次真实推理。
     *
     * <p>这是把"模型/推理坏了"和"相机帧没进来"彻底分开的手段 —— 纯色图不会有人体，
     * 所以 landmarks 为空是正常的；**只要不抛异常、能返回结果对象，就证明模型与 native 链路是好的**。
     */
    public String selfTest(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("模型文件：");
        for (String n : MODELS) {
            sb.append(n.replace("pose_landmarker_", "").replace(".task", ""))
                    .append(assetExists(ctx, n) ? "✓ " : "✗ ");
        }
        sb.append("\n");
        for (String n : MODELS) {
            if (!assetExists(ctx, n)) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            PoseLandmarker lm = null;
            try {
                PoseLandmarker.PoseLandmarkerOptions opt = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(n).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .build();
                lm = PoseLandmarker.createFromOptions(ctx, opt);
                long loadMs = System.currentTimeMillis() - t0;
                Bitmap b = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
                b.eraseColor(0xFF808080);
                t0 = System.currentTimeMillis();
                PoseLandmarkerResult r = lm.detect(new BitmapImageBuilder(b).build());
                sb.append(n).append("：加载 ").append(loadMs).append("ms · 推理 ")
                        .append(System.currentTimeMillis() - t0).append("ms · 结果组数 ")
                        .append(r.landmarks().size()).append(" → 模型链路正常\n");
                sb.append(handSelfTest(ctx));
                return sb.toString();
            } catch (Throwable e) {
                sb.append(n).append("：失败\n").append(android.util.Log.getStackTraceString(e)).append("\n");
            } finally {
                if (lm != null) {
                    try {
                        lm.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 手部模型自检：纯色图跑一次真实推流，验证模型/native 链路（没有人手时结果为空是正常的）。 */
    private String handSelfTest(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(HAND_MODEL).append("：");
        if (!assetExists(ctx, HAND_MODEL)) {
            return sb.append("✗ 不在 assets（手指细分不可用）\n").toString();
        }
        if (!taskBundleComplete(ctx, HAND_MODEL)) {
            return sb.append("✗ 文件不完整（结尾缺 zip 目录，下载中断）\n").toString();
        }
        HandLandmarker lm = null;
        try {
            long t0 = System.currentTimeMillis();
            HandLandmarker.HandLandmarkerOptions opt = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAND_MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .build();
            lm = HandLandmarker.createFromOptions(ctx, opt);
            long loadMs = System.currentTimeMillis() - t0;
            Bitmap b = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF808080);
            t0 = System.currentTimeMillis();
            HandLandmarkerResult r = lm.detect(new BitmapImageBuilder(b).build());
            sb.append("加载 ").append(loadMs).append("ms · 推理 ")
                    .append(System.currentTimeMillis() - t0).append("ms · 结果组数 ")
                    .append(r.landmarks().size()).append(" → 手指链路正常\n");
        } catch (Throwable e) {
            sb.append("失败\n").append(android.util.Log.getStackTraceString(e)).append("\n");
        } finally {
            if (lm != null) {
                try {
                    lm.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }
    public View overlayView() {
        return overlay;
    }

    /** 开始录制：清空上一段，CSV 从头写。 */
    public void startRecording() {
        frames.clear();
        recentFrames.clear();
        seq = 0;
        recordStartMs = 0L;
        lastPose = null;      // 上一段的姿态不能带到新录制里
        recording = true;
    }

    /** 停止录制并交回帧（Activity 拿去本地解算）。 */
    public List<Pose33> stopRecording() {
        recording = false;
        return new ArrayList<Pose33>(frames);
    }

    public boolean isRecording() {
        return recording;
    }

    /** 已录帧数（UI 显示进度用）。 */
    public int frameCount() {
        return frames.size();
    }

    /** 相机送来多少帧（证明相机链路通不通）。 */
    public long inFrames() {
        return inFrames.get();
    }

    /** 真正送进 MediaPipe 多少帧（证明模型在跑）。 */
    public long detectedFrames() {
        return detectedFrames.get();
    }

    /** 模型真实回调了多少次（>0 才可能有骨架）。 */
    public long resultFrames() {
        return resultFrames.get();
    }

    /** MediaPipe 自己的错误回调（推理失败会写这里）。 */
    public String poseError() {
        return poseError;
    }

    /** 单帧转换失败的原因（rowStride 填充、Bitmap 尺寸不符等）。 */
    public String frameError() {
        return frameError;
    }

    public boolean modelReady() {
        return landmarker != null;
    }

    /** 开/关手指细分（要在 start() 之前设置；运行中改只影响下次进入）。 */
    public void setHandsEnabled(boolean on) {
        handsEnabled = on;
    }

    public boolean handsEnabled() {
        return handsEnabled;
    }

    public boolean handReady() {
        return handLandmarker != null;
    }

    /** 手部模型加载失败原因（成功为 null）。 */
    public String handModelError() {
        return handModelError;
    }

    /** 手部模型回调次数 / 检测到的手次（左右各算一次）。 */
    public long handResultFrames() {
        return handResultFrames.get();
    }

    public long handsDetected() {
        return handsDetected.get();
    }

    /** 模型加载失败的完整原因（没有失败时为 null）。 */
    public String modelError() {
        return modelError;
    }

    /** 分析帧的实际宽高（竖屏时是 720x1280，解算的像素尺度必须跟它一致）。 */
    public int imgWidth() {
        return imgW;
    }

    public int imgHeight() {
        return imgH;
    }

    public String csvPath() {
        return csvPath;
    }

    public void stop() throws Exception {
        recording = false;
        if (landmarker != null) { landmarker.close(); landmarker = null; }
        if (handLandmarker != null) { handLandmarker.close(); handLandmarker = null; }
        if (csv != null) { csv.close(); csv = null; }
        analyzer.shutdownNow();
    }

    // 3) 结果 → 内存帧（本地解算）+ CSV 行（mocap4mc-poses v1 同源格式）+ 实时标注
    private void onPose(PoseLandmarkerResult r) {
        resultFrames.incrementAndGet();
        if (r == null) return;
        if (r.landmarks().isEmpty()) {
            if (overlay != null) overlay.clear();
            holdLastPose();            // 短暂丢帧：用上一帧顶住，别让时间轴出现空洞
            return;
        }
        List<NormalizedLandmark> lm = r.landmarks().get(0); // 33 点, x/y/z/visibility
        if (lm.size() < 33) return;   // 结构异常（模型被裁/版本不符）：绝不越界索引
        // 时间轴必须是**从 0 开始**的相对毫秒：用 System.currentTimeMillis() 的绝对时间会让
        // 解算器算出 1794303556 秒（≈57 年）的 animation_length，BB 里拖到底也看不到动作。
        // 用 elapsedRealtime（单调，不受改系统时间影响）并减去本段第一帧。
        long now = android.os.SystemClock.elapsedRealtime();
        if (recordStartMs == 0L) {
            recordStartMs = now;
        }
        long t = now - recordStartMs;
        Pose33 p = new Pose33();
        p.tMs = t;
        for (int i = 0; i < 33; i++) {
            NormalizedLandmark m = lm.get(i);
            p.x[i] = frontCamera ? 1.0 - m.x() : m.x(); // 前摄镜像 → 解算侧无需再翻转
            p.y[i] = m.y();
            p.z[i] = m.z();
            p.vis[i] = m.visibility().orElse(1f);
            // presence 单独记：脚在画面里但很小/偏暗时 visibility 会低到 0.1，presence 仍接近 1。
            // 只存 visibility 就会把这种脚踝当"没看见"，位置退回画面正中 → 腿朝上。
            p.pres[i] = m.presence().isPresent() ? m.presence().get() : -1f;
        }
        lastPose = p;
        lastPoseMs = now;
        if (recording) {
            frames.add(p);
            recentFrames.put(r.timestampMs(), p);
            writeCsv(p);
        }
        if (overlay != null) {
            overlay.setLandmarks(lm, frontCamera, imgW, imgH, t); // 实时关节标注（与预览同一 FIT_CENTER 几何）
        }
    }

    /**
     * 检测短暂丢失时用上一帧姿态顶住（最多 {@value #HOLD_MAX_MS}ms）。
     *
     * <p>动捕连续帧一旦断档，解算器只能靠关键点冻结窗口，时间轴本身仍是空的；
     * 导出后这一段时间会被前后插值“拉长”，播放起来就是一顿。这里把断档补成
     * “保持上一姿态”，既保住时间连续，也不会凭空造出新动作。
     */
    private void holdLastPose() {
        if (!recording || lastPose == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPoseMs > HOLD_MAX_MS) return;
        if (recordStartMs == 0L) recordStartMs = now;
        long t = now - recordStartMs;
        if (!frames.isEmpty() && t - (long) frames.get(frames.size() - 1).tMs < 15) return;
        Pose33 q = copyPose(lastPose, t);
        frames.add(q);
        // 手部结果按输入时间戳异步回来：把这一帧也挂到最近一次送检时间戳上，
        // 否则“身体丢了但手还在”的那几帧手部数据会被直接丢弃。
        recentFrames.put(lastTimestampMs, q);
        writeCsv(q);
    }

    private static Pose33 copyPose(Pose33 src, long tMs) {
        Pose33 q = new Pose33();
        q.tMs = tMs;
        System.arraycopy(src.x, 0, q.x, 0, 33);
        System.arraycopy(src.y, 0, q.y, 0, 33);
        System.arraycopy(src.z, 0, q.z, 0, 33);
        System.arraycopy(src.vis, 0, q.vis, 0, 33);
        System.arraycopy(src.pres, 0, q.pres, 0, 33);
        for (int h = 0; h < 2; h++) {
            System.arraycopy(src.handX[h], 0, q.handX[h], 0, 21);
            System.arraycopy(src.handY[h], 0, q.handY[h], 0, 21);
            System.arraycopy(src.handZ[h], 0, q.handZ[h], 0, 21);
            System.arraycopy(src.handVis[h], 0, q.handVis[h], 0, 21);
            q.handSeen[h] = src.handSeen[h];
        }
        return q;
    }

    /** CSV 行（mocap4mc-poses v1）：内存帧与落盘保持同源，外部复算不会再得到另一套结果。 */
    private void writeCsv(Pose33 p) {
        try {
            if (seq == 0 && csv != null) {
                csv.write("# mocap4mc-poses v1\n");
                csv.write("# image " + imgW + "x" + imgH + "\n");
                csv.write("# fps 30\n");
                csv.write("t_ms,idx,x,y,z,vis,pres\n");
            }
            if (csv != null) {
                for (int i = 0; i < 33; i++) {
                    csv.write(String.format(Locale.US, "%d,%d,%.6f,%.6f,%.6f,%.3f,%.3f%n",
                            (long) p.tMs, i, p.x[i], p.y[i], p.z[i], p.vis[i], p.pres[i]));
                }
            }
            seq++;
        } catch (IOException e) {
            android.util.Log.w("mocap4mc", "csv write failed", e);
        }
    }

    private void openCsv(Context ctx) throws IOException {
        File dir = ctx.getExternalFilesDir("mocap");
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        File f = new File(dir, "session_" + System.currentTimeMillis() + ".csv");
        csvPath = f.getAbsolutePath();
        csv = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
    }

    // 4) 实时标注叠加层：躯干青线 / 手臂橙线 / 腿黄绿线 / 关节绿点 / 低可见红点
    //    坐标系必须和预览一致：PreviewView 是 FIT_CENTER（画面缩成中间一块，两侧留黑），
    //    标注层却铺满整个舞台 —— 直接按舞台宽高乘归一化坐标，骨架就会被横向拉成一条线。
    //    这里按同样的 fit 规则算出画面在 View 里的矩形，再往里映射。
    static class OverlayView extends View {
        private List<NormalizedLandmark> lm;
        private boolean mirror;
        private int srcW = 720, srcH = 1280;

        OverlayView(Context c) {
            super(c);
            // 关键：View 没有背景时 willNotDraw=true，onDraw 永远不会被调用 ——
            // 原包这个类没写这句，表现就是"相机有画面但一根线都不画"。
            setWillNotDraw(false);
        }
        void setLandmarks(List<NormalizedLandmark> lm, boolean mirror, int srcW, int srcH, long t) {
            this.lm = lm;
            this.mirror = mirror;
            if (srcW > 0 && srcH > 0) {
                this.srcW = srcW;
                this.srcH = srcH;
            }
            invalidate();
        }
        void clear() { lm = null; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            if (lm == null) return;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float w = getWidth(), h = getHeight();
            float scale = Math.min(w / (float) srcW, h / (float) srcH);
            float dw = srcW * scale, dh = srcH * scale;
            float dx = (w - dw) / 2f, dy = (h - dh) / 2f;
            int[][] limbs = {{11,13},{13,15},{12,14},{14,16},{23,25},{25,27},{24,26},{26,28},{11,12},{11,23},{12,24},{23,24},{7,8},{0,7},{0,8}};
            p.setStrokeWidth(Math.max(2f, dw / 180f));
            for (int[] b : limbs) {
                NormalizedLandmark a = lm.get(b[0]), d = lm.get(b[1]);
                p.setColor(0xFF00C8FF);
                c.drawLine(x(a, dx, dw), y(a, dy, dh), x(d, dx, dw), y(d, dy, dh), p);
            }
            for (NormalizedLandmark m : lm) {
                boolean vis = m.visibility().orElse(1f) >= 0.3f;
                p.setColor(vis ? 0xFF50FF78 : 0xFFFF5050);
                float r = Math.max(2f, dw / 120f);
                c.drawCircle(x(m, dx, dw), y(m, dy, dh), r, p);
            }
        }
        private float x(NormalizedLandmark m, float dx, float dw) {
            return dx + (mirror ? 1f - m.x() : m.x()) * dw;
        }
        private float y(NormalizedLandmark m, float dy, float dh) {
            return dy + m.y() * dh;
        }
    }
}
//
// ============================================================================
// 采集建议（影响精度，务必遵守）：
//   1. 距离 1.5~2.5m，全身入画，人物占画面高 ≥60% —— 人物越小头颈关键点噪声越大；
//   2. 正对或侧后 45° 拍摄；完全背对时鼻部不可见（解算自动降级为耳双目标，头颈精度下降）；
//   3. 光照均匀、避免逆光；着装与背景对比清晰；
//   4. 单人入画（多人帧取 landmarks()[0]，建议实拍时屏掉）；
//   5. 循环动作（走路）请从相位起点开始录，剪辑时保留整数个周期 loop 才无缝。
// ============================================================================