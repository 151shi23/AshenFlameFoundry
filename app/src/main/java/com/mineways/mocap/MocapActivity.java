package com.mineways.mocap;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动作捕捉 → MC 五骨骼 {@code .animation.json}。
 *
 * <p>这一页只是壳：采集交给原包的 {@link PoseCaptureBridge}（已按官方 API 去注释适配），
 * 解算与写文件交给原包的 {@code Main.pipeline} —— 一行核心算法都没有重写。
 *
 * <p>两条入口：
 * <ul>
 *   <li><b>实时采集</b>：相机 → MediaPipe Pose（heavy 优先）→ 33 点（归一化）→ 内存帧 + CSV；</li>
 *   <li><b>从 CSV 生成</b>：把外面已有的 {@code mocap4mc-poses v1} CSV 直接解算成动画。</li>
 * </ul>
 */
public class MocapActivity extends AppCompatActivity {

    private static final String TAG = "mocap4mc";
    private static final int REQ_CAM = 11;
    private static final int REQ_CSV = 12;
    private static final int REQ_JSON = 13;
    private static final int REQ_PREVIEW = 14;
    /** 生成选项持久化（SharedPreferences，重启后保留）。 */
    private static final String PREFS = "mocap_ui";
    private static final String K_JUMP = "opt_jump", K_FINGER = "opt_finger", K_ULTRA = "opt_ultra";
    /** Blockbench 动画字段选项（见 AnimSpec）：平滑 / 覆盖前序 / 保持末帧。 */
    private static final String K_SMOOTH = "opt_smooth", K_OVERRIDE = "opt_override", K_HOLD = "opt_hold";
    /** RDP 抽帧容差：0.5° = 原包验证过的保真上限（E5：max 0.493°）。 */
    private static final double TOL_DEG = 0.5;

    private LinearLayout root;
    private TextView status;
    private TextView result;
    private EditText animName;
    private CheckBox loop;
    /** 生成选项：跳跃根位移 / 手指细分 / 超高精度。 */
    private CheckBox cbRoot, cbFingers, cbUltra;
    /** 动画字段选项：平滑插值 / 覆盖前序动画 / 保持末帧。 */
    private CheckBox cbSmooth, cbOverride, cbHold;
    /** 单设备传感器动捕：绑在四肢之一，全部传感器采集 → JSON。 */
    private SensorCapture sensorCapture;
    private TextView sensorStatus;
    private Button sensorBtn;
    private CheckBox sensorReverse;
    private boolean sensorRunning;
    private SensorMocap.Limb sensorLimb = SensorMocap.Limb.LEFT_ARM;

    private PoseCaptureBridge bridge;
    private PreviewView preview;
    private Button recordBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 正在编辑的动画（JSON 数值空间）。 */
    private AnimJson.Clip editClip;
    /** 编辑页里那块实时概要（导出后就地更新，不重建整页）。 */
    private TextView editInfo;
    /** 可视化编辑：预览与时间轴。 */
    private AnimPreviewView previewView;
    private TimelineView timelineView;
    private boolean playing;
    private double playT;
    private final Runnable playTick = new Runnable() {
        @Override
        public void run() {
            if (!playing || editClip == null || previewView == null) return;
            playT += 0.033;
            if (playT > editClip.length) playT = 0;
            if (timelineView != null) timelineView.setPlayhead(playT);
            previewView.setSnapshot(AnimPose.at(editClip, playT));
            ui.postDelayed(this, 33);
        }
    };
    /** 导出后那颗「分享」按钮：重复导出时先摘掉旧的，避免越叠越多。 */
    private View shareBtn;
    /** 生成后那颗「编辑这个动画」按钮，同样只保留一个。 */
    private View genEditBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = buildHome();
        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
        Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    /** 外部「打开方式」：csv 走解算，json / animation.json 走编辑器。 */
    private void acceptIncoming(Uri uri) {
        String name = com.mineways.open.OpenInbox.nameOf(this, uri);
        String ext = com.mineways.open.OpenInbox.extOf(name);
        boolean json = "json".equals(ext) || "animation.json".equals(ext);
        try {
            File cache = new File(getCacheDir(), json ? "mocap_edit_in.json" : "mocap_in.csv");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (json) {
                editClip = AnimJson.read(cache.getAbsolutePath());
                showEditor(null, true);
                return;
            }
            MocapIO.Clip clip = MocapIO.readCsv(cache.getAbsolutePath());
            status.setText("读到 CSV：" + clip.frames.size() + " 帧 " + clip.imgW + "x" + clip.imgH);
            generate(clip.frames, "外部", clip.imgW, clip.imgH);
        } catch (Throwable e) {
            status.setText((json ? "JSON 读取失败：" : "CSV 读取失败：") + e);
        }
    }

    // ------------------------------------------------------------------ 首页

    private LinearLayout buildHome() {
        LinearLayout box = column();
        box.addView(title("动作捕捉 → MC 五骨骼动画"));
        box.addView(note("手机相机实时采集 33 关节点，本地解算成 Blockbench / 基岩版 .animation.json。\n"
                + "精度链路：MediaPipe（按 heavy > full > lite 自动取最高档）→ One-Euro 滤波 → 躯干解剖基 → "
                + "阻尼高斯-牛顿欧拉拟合 → 5 点中值 → RDP 抽帧（≤0.5°）。\n"
                + "拍摄要求：距离 1.5~2.5 米、全身入画且人占画面高 60% 以上、光照均匀、单人入镜。"));

        box.addView(note("生成选项（作用于下一次生成；会记住你的选择）："));
        cbRoot = check("跳跃位移：离地高度写进 body.position（BB 里整个人会跳起来）", pref(K_JUMP, true));
        cbFingers = check("手指细分（超高精度）：双手 21 点 → 手掌 + 五指骨骼", pref(K_FINGER, true));
        cbUltra = check("超高精度导出：逐帧关键帧 + 3 位小数（文件更大）", pref(K_ULTRA, true));
        cbRoot.setOnCheckedChangeListener((b, on) -> savePref(K_JUMP, on));
        cbFingers.setOnCheckedChangeListener((b, on) -> savePref(K_FINGER, on));
        cbUltra.setOnCheckedChangeListener((b, on) -> savePref(K_ULTRA, on));
        box.addView(cbRoot);
        box.addView(cbFingers);
        box.addView(cbUltra);

        box.addView(note("动画字段（逐条对应 Blockbench 导出的 .animation.json）："));
        cbSmooth = check("平滑插值：关键帧写 lerp_mode:\"catmullrom\"（BB 里显示「平滑」，不再是折线）",
                pref(K_SMOOTH, true));
        cbOverride = check("覆盖前序动画：写 override_previous_animation（动捕是绝对姿态，防止和待机动画叠加）",
                pref(K_OVERRIDE, true));
        cbHold = check("不循环时保持末帧：loop:\"hold_on_last_frame\"（否则播完弹回静止姿势）",
                pref(K_HOLD, true));
        cbSmooth.setOnCheckedChangeListener((b, on) -> savePref(K_SMOOTH, on));
        cbOverride.setOnCheckedChangeListener((b, on) -> savePref(K_OVERRIDE, on));
        cbHold.setOnCheckedChangeListener((b, on) -> savePref(K_HOLD, on));
        box.addView(cbSmooth);
        box.addView(cbOverride);
        box.addView(cbHold);

        Button live = button("开始实时采集");
        live.setOnClickListener(v -> requestCamera());
        box.addView(live);

        Button csv = button("从 CSV 生成（已有 mocap4mc-poses）");
        csv.setOnClickListener(v -> pickCsv());
        box.addView(csv);

        Button edit = button("编辑已录动画（可视化拖骨骼 / 裁剪 / 变速）");
        edit.setOnClickListener(v -> pickJson(false));
        box.addView(edit);

        Button preview = button("预览动画 JSON（只读，不改文件）");
        preview.setOnClickListener(v -> pickJson(true));
        box.addView(preview);

        // ---------- 单设备传感器动捕 ----------
        box.addView(note("单设备传感器动捕：把手机长边沿着肢体绑好，选一根骨；开始后先保持静止 2 秒校准，"
                + "再开始做动作。只动所选那一根骨，其余保持静止。传感器全部登记（旋转矢量 + 陀螺 + 加速度 + 磁力计）。\n"
                + "注意：一根手机只能测整根肢体的朝向；弯肘 / 弯膝这种两段折叠，后续要用两段绑法分别录。"));
        RadioGroup limbs = new RadioGroup(this);
        limbs.setOrientation(LinearLayout.HORIZONTAL);
        for (SensorMocap.Limb l : SensorMocap.Limb.values()) {
            RadioButton rb = new RadioButton(this);
            rb.setText(l.label);
            rb.setTag(l);
            rb.setId(View.generateViewId());
            limbs.addView(rb);
        }
        if (limbs.getChildCount() > 0) {
            limbs.check(limbs.getChildAt(0).getId());
        }
        limbs.setOnCheckedChangeListener((g, id) -> {
            View v = g.findViewById(id);
            if (v != null && v.getTag() instanceof SensorMocap.Limb) {
                sensorLimb = (SensorMocap.Limb) v.getTag();
            }
        });
        box.addView(limbs);
        sensorReverse = check("手机倒装（顶端朝肩 / 髋）", false);
        box.addView(sensorReverse);
        sensorStatus = note("传感器：未开始");
        box.addView(sensorStatus);
        sensorBtn = button("开始校准 + 采集（单设备传感器）");
        sensorBtn.setOnClickListener(v -> {
            if (sensorRunning) {
                stopSensorAndGenerate();
            } else {
                startSensor();
            }
        });
        box.addView(sensorBtn);

        // 自检：不装相机也能验证"模型能不能加载、能不能推理"，
        // 纯色图在 IMAGE 模式跑一次真实推理，结果直接打在页面上。
        Button selfTest = button("模型自检（不用相机）");
        selfTest.setOnClickListener(v -> {
            status.setText("自检中（加载模型 + 一次推理）…");
            new Thread(() -> {
                String r = new PoseCaptureBridge().selfTest(this);
                ui.post(() -> status.setText(r));
            }, "mocap-selftest").start();
        });
        box.addView(selfTest);

        Button open = button("打开输出目录");
        open.setOnClickListener(v -> openOutputDir());
        box.addView(open);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        animName = new EditText(this);
        animName.setHint("动画名，例如 walk");
        animName.setText("walk");
        animName.setSingleLine(true);
        animName.setInputType(InputType.TYPE_CLASS_TEXT);
        row.addView(animName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        loop = new CheckBox(this);
        loop.setText("循环");
        loop.setChecked(true);
        row.addView(loop);
        box.addView(row);

        status = note("就绪");
        result = note("");
        box.addView(status);
        box.addView(result);
        return box;
    }

    // ------------------------------------------------------------------ 单设备传感器动捕

    private void startSensor() {
        if (sensorRunning) return;
        final SensorCapture cap = new SensorCapture();
        if (!cap.available(this)) {
            sensorStatus.setText("这台设备没有旋转矢量传感器，无法做单设备动捕");
            return;
        }
        try {
            cap.start(this, (phase, frames, gyroFrames) -> ui.post(() -> {
                if (!sensorRunning) return;
                sensorStatus.setText(phase + "\n姿态帧 " + frames + " · 陀螺帧 " + gyroFrames);
            }));
        } catch (Throwable e) {
            sensorStatus.setText("传感器启动失败：" + e.getMessage());
            return;
        }
        sensorCapture = cap;
        sensorRunning = true;
        sensorBtn.setText("停止并生成 JSON");
        sensorStatus.setText("校准中：保持静止 2 秒…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 离开页面 / 销毁时静默放弃这次采集。 */
    private void cancelSensor(String why) {
        if (!sensorRunning) return;
        sensorRunning = false;
        if (sensorCapture != null) {
            try {
                sensorCapture.stop();
            } catch (Throwable ignored) {
            }
            sensorCapture = null;
        }
        if (sensorBtn != null) sensorBtn.setText("开始校准 + 采集（单设备传感器）");
        if (sensorStatus != null) sensorStatus.setText(why);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void stopSensorAndGenerate() {
        if (!sensorRunning || sensorCapture == null) return;
        sensorRunning = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SensorCapture.Capture c = sensorCapture.stop();
        sensorCapture = null;
        sensorBtn.setText("开始校准 + 采集（单设备传感器）");
        if (c.rv.size() < 2) {
            sensorStatus.setText("没有采到姿态：校准期间保持静止，校准结束后再做动作。\n" + c.health.text());
            return;
        }
        final SensorMocap.Limb limb = sensorLimb;
        final boolean reversed = sensorReverse != null && sensorReverse.isChecked();
        final boolean ultra = cbUltra != null && cbUltra.isChecked();
        sensorStatus.setText("解算中（" + c.rv.size() + " 帧）…");
        new Thread(() -> {
            String msg;
            File out = null;
            try {
                SensorMocap.Result r = SensorMocap.build(limb, reversed, c.q0, c.rv, c.gyro,
                        ultra ? 0.02 : TOL_DEG, 50);
                RigConfig rig = RigDefaults.vanilla();
                Map<String, List<KeyframeDecimator.Sample>> curves =
                        new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
                for (RigConfig.Bone b : rig.bones) {
                    if (!b.animated) continue;
                    if (b.name.equals(limb.bone)) {
                        curves.put(b.name, r.curve);
                    } else {
                        List<KeyframeDecimator.Sample> zero = new ArrayList<KeyframeDecimator.Sample>();
                        zero.add(new KeyframeDecimator.Sample(0, new double[]{0, 0, 0}));
                        zero.add(new KeyframeDecimator.Sample(r.durationSec, new double[]{0, 0, 0}));
                        curves.put(b.name, zero);
                    }
                }
                File dir = outDir();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String name = TextUtils.isEmpty(animName.getText())
                        ? "sensor" : animName.getText().toString().trim();
                out = new File(dir, "mocap_sensor_" + limb.bone + "_" + stamp + ".animation.json");

                AnimationWriter.Settings st = new AnimationWriter.Settings();
                st.loop = loop.isChecked();
                st.holdLastFrame = cbHold == null || cbHold.isChecked();
                st.overridePreviousAnimation = cbOverride == null || cbOverride.isChecked();
                st.smooth = cbSmooth == null || cbSmooth.isChecked();
                st.decimals = ultra ? 3 : 2;
                st.rotations = curves;
                st.positions = null;
                AnimationWriter.write(out.getAbsolutePath(), name, st, r.durationSec);

                AnimCheck.Result chk = AnimCheck.check(out.getAbsolutePath(), name);
                int kf = 0;
                for (List<KeyframeDecimator.Sample> l : curves.values()) kf += l.size();
                msg = "完成（单设备传感器 · " + limb.label + (reversed ? " · 倒装" : "") + "）\n"
                        + "关键帧 " + kf + " · 时长 " + String.format(Locale.US, "%.2f", r.durationSec)
                        + " 秒 · " + String.format(Locale.US, "%.0f", r.hz) + "Hz 重采样\n"
                        + "最大角速度 " + Math.round(r.maxRateDegPerSec) + "°/s · 断档 " + r.gaps + " 次\n"
                        + c.health.text() + "\n"
                        + "标定样本 " + c.calibSamples + "\n"
                        + chk.message + "\n" + out.getAbsolutePath();
                if (!chk.ok) {
                    msg = "⚠ 生成成功但规范自检未通过：\n" + chk.message + "\n" + out.getAbsolutePath();
                }
            } catch (Throwable e) {
                msg = "传感器解算失败：" + e;
                out = null;
            }
            final String fm = msg;
            final File fo = out;
            ui.post(() -> {
                sensorStatus.setText(fm);
                if (fo != null) {
                    showShare(fo);
                }
            });
        }, "sensor-solve").start();
    }

    // ------------------------------------------------------------------ 实时采集

    private void requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        showCapture();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_CAM) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                showCapture();
            } else {
                toast("没有相机权限，无法实时采集");
            }
        }
    }

    private void showCapture() {
        LinearLayout box = column();
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(title("实时采集"));
        box.addView(note("对准全身，人占画面高 60% 以上。先看骨架是否稳定（红线=被遮挡/不可见），"
                + "再开始录制。"));

        FrameLayout stage = new FrameLayout(this);
        preview = new PreviewView(this);
        // FIT_CENTER：完整画面进框（默认 FILL_CENTER 会裁掉两边，"录制区域少"就是它）
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        stage.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 取景区吃满剩余空间（weight=1），不再是写死的高度
        box.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = note("相机启动中…");
        box.addView(status);

        bridge = new PoseCaptureBridge();
        bridge.setHandsEnabled(cbFingers == null || cbFingers.isChecked());
        try {
            bridge.start(this, this, preview);
            View overlay = bridge.overlayView();
            if (overlay != null) {
                stage.addView(overlay, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // 相机已经起来；模型在后台加载，状态由 tickStats 常驻刷新（帧计数就是诊断）
            tickStats();
        } catch (Throwable e) {
            // 完整堆栈给到屏幕上：模型/相机失败必须能定位（不再只说一句"失败"）
            status.setText("相机/模型启动失败：\n" + android.util.Log.getStackTraceString(e));
            Button back0 = button("返回");
            back0.setOnClickListener(v -> {
                stopBridge();
                recreate();
            });
            box.addView(back0);
            setContentView(box);
            return;
        }

        recordBtn = button("开始录制");
        recordBtn.setOnClickListener(v -> {
            if (!bridge.isRecording()) {
                bridge.startRecording();
                recordBtn.setText("停止并生成动画");
            } else {
                recordBtn.setEnabled(false);
                recordBtn.setText("生成中…");
                List<Pose33> frames = bridge.stopRecording();
                generate(frames, "实时采集", bridge.imgWidth(), bridge.imgHeight());
            }
        });
        box.addView(recordBtn);

        Button back = button("返回");
        back.setOnClickListener(v -> {
            stopBridge();
            recreate();
        });
        box.addView(back);

        // 不再用 ScrollView：取景区要靠 weight 吃满屏，外面再套一层滚动会把它压回去
        setContentView(box);
    }

    /**
     * 常驻状态：相机帧 / 检测帧 / 模型状态 / 已录帧。
     *
     * <p>这三个计数就是诊断本身：相机帧在涨而检测帧为 0，说明模型没加载；
     * 两个都不涨，说明相机没出帧；检测帧在涨但没骨架，才是标注层的问题。
     */
    private void tickStats() {
        if (bridge == null) {
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append("相机帧：").append(bridge.inFrames())
                .append(" · 送入检测：").append(bridge.detectedFrames())
                .append(" · 模型回调：").append(bridge.resultFrames());
        if (bridge.handsEnabled()) {
            s.append("\n手部：回调 ").append(bridge.handResultFrames())
                    .append(" · 检出 ").append(bridge.handsDetected()).append(" 手次");
            if (bridge.handModelError() != null) {
                s.append("\n手部模型失败（手指细分不可用）：\n").append(bridge.handModelError());
            }
        }
        if (bridge.modelReady()) {
            s.append(" · 模型：").append(bridge.modelAsset());
        } else if (bridge.modelError() != null) {
            s.append("\n模型加载失败：\n").append(bridge.modelError());
        } else {
            s.append(" · 模型加载中…（heavy 29MB，一般 2~6 秒）");
        }
        if (bridge.frameError() != null) {
            s.append("\n帧转换错误：\n").append(bridge.frameError());
        }
        if (bridge.poseError() != null) {
            s.append("\n推理错误：\n").append(bridge.poseError());
        }
        if (bridge.isRecording()) {
            s.append("\n已录：").append(bridge.frameCount()).append(" 帧（约 ")
                    .append(String.format(Locale.US, "%.1f", bridge.frameCount() / 30.0)).append(" 秒）");
        }
        status.setText(s.toString());
        ui.postDelayed(this::tickStats, 300);
    }

    private void stopBridge() {
        if (bridge != null) {
            try {
                bridge.stop();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorRunning) {
            cancelSensor("已停止传感器采集（离开页面）");
        }
    }

    @Override
    protected void onDestroy() {
        playing = false;
        ui.removeCallbacks(playTick);
        if (sensorRunning) {
            cancelSensor("已停止传感器采集（页面关闭）");
        }
        stopBridge();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 从 CSV

    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_CSV);
        } catch (Throwable e) {
            toast("没有可用的文件选择器");
        }
    }

    /** 选一份已经生成好的 .animation.json：编辑或只读预览。 */
    private void pickJson(boolean previewOnly) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, previewOnly ? REQ_PREVIEW : REQ_JSON);
        } catch (Throwable e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_CSV && req != REQ_JSON && req != REQ_PREVIEW) || res != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        boolean json = req == REQ_JSON || req == REQ_PREVIEW;
        Uri uri = data.getData();
        try {
            File cache = new File(getCacheDir(), json ? "mocap_edit_in.json" : "mocap_in.csv");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (json) {
                editClip = AnimJson.read(cache.getAbsolutePath());
                showEditor(null, req == REQ_JSON);
                return;
            }
            MocapIO.Clip clip = MocapIO.readCsv(cache.getAbsolutePath());
            status.setText("读到 CSV：" + clip.frames.size() + " 帧 " + clip.imgW + "x" + clip.imgH);
            generate(clip.frames, "CSV", clip.imgW, clip.imgH);
        } catch (Throwable e) {
            status.setText((json ? "JSON 读取失败：" : "CSV 读取失败：") + e);
            if (json) {
                toast("不是 Blockbench/基岩动画文件：" + e.getMessage());
                recreate();
            }
        }
    }

    // ------------------------------------------------------------------ 编辑已录动画

    /** 编辑/预览页：可视化拖骨骼（编辑）、区间裁剪、变速、静音、导出。 */
    private void showEditor(String flash, boolean editable) {
        if (editClip == null) {
            recreate();
            return;
        }
        LinearLayout box = column();
        box.addView(title(editable ? "编辑动画" : "预览动画"));
        editInfo = note(describe(editClip));
        box.addView(editInfo);
        TextView tip = note(flash != null ? flash : editable
                ? "拖动预览里的骨头可直接改这一帧的姿态（自动在当前时间打关键帧）；"
                + "拖时间轴换帧。改完点最下面的「导出 JSON」，不会改原文件。"
                : "只读预览：拖时间轴看每一帧、可播放；不会改动文件。");
        tip.setTextColor(0xFFFFC24B);
        box.addView(tip);

        final EditText start = editable ? numField("起（秒）", "0") : null;
        final EditText end = editable
                ? numField("止（秒）", String.format(Locale.US, "%.2f", editClip.length)) : null;
        if (editable) {
            box.addView(start);
            box.addView(end);
        }

        // ---- 可视化：实时姿势预览 + 可拖时间轴（选区就是「保留/删除」的输入）
        playing = false;
        ui.removeCallbacks(playTick);
        previewView = new AnimPreviewView(this);
        previewView.setSnapshot(AnimPose.at(editClip, 0));
        box.addView(previewView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        if (editable) {
            previewView.setBoneDragListener((bone, dx, dy) -> {
                if (editClip == null) return;
                playing = false;
                ui.removeCallbacks(playTick);
                if (AnimJson.drag(editClip, bone, playT, dx, dy) != null) {
                    previewView.setSnapshot(AnimPose.at(editClip, playT));
                    if (timelineView != null) timelineView.setPlayhead(playT);
                    if (editInfo != null) editInfo.setText(describe(editClip));
                }
            });
        }
        timelineView = new TimelineView(this);
        timelineView.setData(editClip.length, keyTimes(editClip));
        timelineView.setListener(new TimelineView.Listener() {
            @Override
            public void onScrub(double t) {
                playT = t;
                if (previewView != null && editClip != null) {
                    previewView.setSnapshot(AnimPose.at(editClip, t));
                }
            }

            @Override
            public void onRange(double a, double b) {
                if (start == null || end == null) return;
                start.setText(String.format(Locale.US, "%.2f", a));
                end.setText(String.format(Locale.US, "%.2f", b));
            }
        });
        box.addView(timelineView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));
        Button play = button("播放 / 暂停（拖动时间轴可逐帧看姿势）");
        box.addView(play);
        play.setOnClickListener(v -> {
            if (playing) {
                playing = false;
                ui.removeCallbacks(playTick);
                return;
            }
            playing = true;
            playT = 0;
            ui.post(playTick);
        });

        if (!editable) {
            Button back = button("返回");
            back.setOnClickListener(v -> recreate());
            box.addView(back);
            ScrollView sc = new ScrollView(this);
            sc.addView(box);
            setContentView(sc);
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button keep = button("保留区间");
        Button cut = button("删除区间");
        row.addView(keep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cut, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        keep.setOnClickListener(v -> applyRange(true, start, end));
        cut.setOnClickListener(v -> applyRange(false, start, end));

        EditText factor = numField("变速倍率（>1 变慢）", "1.0");
        box.addView(factor);
        Button doScale = button("整体变速");
        box.addView(doScale);
        doScale.setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(factor.getText().toString().trim());
                AnimJson.scale(editClip, f);
                showEditor(String.format(Locale.US, "已变速 ×%.2f → %.2f 秒", f, editClip.length), true);
            } catch (Throwable e) {
                showEditor("变速失败：" + e.getMessage(), true);
            }
        });

        box.addView(note("骨骼（取消勾选 = 导出时删掉这根骨的所有通道）："));
        final Map<String, CheckBox> boxes = new LinkedHashMap<String, CheckBox>();
        java.util.Set<String> boneNames = new java.util.LinkedHashSet<String>();
        boneNames.addAll(editClip.bones.keySet());
        boneNames.addAll(editClip.positions.keySet());
        boneNames.addAll(editClip.scales.keySet());
        boneNames.addAll(editClip.opaque.keySet());
        for (String bone : boneNames) {
            CheckBox cb = new CheckBox(this);
            int n = 0;
            if (editClip.bones.get(bone) != null) n += editClip.bones.get(bone).size();
            if (editClip.positions.get(bone) != null) n += editClip.positions.get(bone).size();
            if (editClip.scales.get(bone) != null) n += editClip.scales.get(bone).size();
            boolean opaque = editClip.opaque.get(bone) != null;
            cb.setText(bone + "（" + (opaque ? "原样保留的表达式通道" : n + " 键") + "）");
            cb.setChecked(true);
            cb.setTextColor(Color.WHITE);
            boxes.put(bone, cb);
            box.addView(cb);
        }

        Button export = button("导出编辑后的 JSON");
        box.addView(export);
        export.setOnClickListener(v -> exportEdited(boxes));
        Button reload = button("放弃修改，重新载入原文件");
        box.addView(reload);
        reload.setOnClickListener(v -> {
            try {
                editClip = AnimJson.read(cacheJson());
                showEditor("已重新载入原文件", true);
            } catch (Throwable e) {
                showEditor("重新载入失败：" + e, true);
            }
        });
        Button back = button("返回");
        back.setOnClickListener(v -> recreate());
        box.addView(back);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    private String cacheJson() {
        return new File(getCacheDir(), "mocap_edit_in.json").getAbsolutePath();
    }

    private void applyRange(boolean keep, EditText start, EditText end) {
        try {
            double a = Double.parseDouble(start.getText().toString().trim());
            double b = Double.parseDouble(end.getText().toString().trim());
            if (b <= a) throw new IllegalArgumentException("止要大于起");
            if (keep) {
                AnimJson.keep(editClip, a, b);
            } else {
                AnimJson.cut(editClip, a, b);
            }
            showEditor(String.format(Locale.US, "已%s %.2f~%.2f 秒 → 现在 %.2f 秒",
                    keep ? "保留" : "删除", a, b, editClip.length), true);
        } catch (Throwable e) {
            showEditor("区间操作失败：" + e.getMessage(), true);
        }
    }

    private void exportEdited(Map<String, CheckBox> boxes) {
        AnimJson.Clip out = AnimJson.copy(editClip);
        for (Map.Entry<String, CheckBox> e : boxes.entrySet()) {
            if (!e.getValue().isChecked()) {
                AnimJson.mute(out, e.getKey());
            }
        }
        boolean any = false;
        for (String bone : boxes.keySet()) any |= out.hasBone(bone);
        if (!any) {
            toast("所有骨骼都被静音了，导出会没有任何动画");
            return;
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String base = out.name == null || out.name.trim().isEmpty() ? "mocap" : out.name.trim();
            File f = new File(outDir(), "mocap_" + base.replaceAll("[^A-Za-z0-9._-]", "_")
                    + "_edit_" + stamp + ".animation.json");
            AnimJson.write(out, f.getAbsolutePath());
            AnimCheck.Result chk = AnimCheck.check(f.getAbsolutePath(), out.name, true);
            if (editInfo != null) {
                editInfo.setText(describe(out) + "\n" + chk.message + "\n" + f.getAbsolutePath());
            }
            if (editInfo != null && editInfo.getParent() instanceof ViewGroup) {
                showShareIn((ViewGroup) editInfo.getParent(), f);
            }
        } catch (Throwable e) {
            toast("导出失败：" + e);
        }
    }

    private static float[] keyTimes(AnimJson.Clip c) {
        java.util.TreeSet<Float> set = new java.util.TreeSet<Float>();
        for (List<KeyframeDecimator.Sample> l : c.bones.values()) {
            for (KeyframeDecimator.Sample s : l) set.add((float) s.t);
        }
        for (List<KeyframeDecimator.Sample> l : c.positions.values()) {
            for (KeyframeDecimator.Sample s : l) set.add((float) s.t);
        }
        for (List<KeyframeDecimator.Sample> l : c.scales.values()) {
            for (KeyframeDecimator.Sample s : l) set.add((float) s.t);
        }
        float[] out = new float[set.size()];
        int i = 0;
        for (Float f : set) out[i++] = f;
        return out;
    }

    private static double maxPos(Map<String, List<KeyframeDecimator.Sample>> pos) {
        double mx = 0;
        for (List<KeyframeDecimator.Sample> l : pos.values()) {
            for (KeyframeDecimator.Sample s : l) mx = Math.max(mx, Math.abs(s.e[1]));
        }
        return mx;
    }

    private String describe(AnimJson.Clip c) {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> bones = new java.util.LinkedHashSet<String>();
        bones.addAll(c.bones.keySet());
        bones.addAll(c.positions.keySet());
        bones.addAll(c.scales.keySet());
        bones.addAll(c.opaque.keySet());
        b.append("动画 ").append(c.name).append(" · ").append(String.format(Locale.US, "%.2f", c.length))
                .append(" 秒 · ").append(bones.size()).append(" 骨骼 · ")
                .append(c.keyCount()).append(" 键 · ").append(c.loopLabel());
        if (!c.extras.isEmpty()) b.append(" · 保留字段 ").append(c.extras.keySet());
        if (!c.otherAnimations.isEmpty()) b.append(" · 同文件动画 ").append(c.otherAnimations.keySet());
        for (String bone : bones) {
            List<KeyframeDecimator.Sample> l = c.bones.get(bone);
            if (l == null) l = c.positions.get(bone);
            b.append("\n  ").append(bone).append("：")
                    .append(l == null ? "不透明通道（原样保留）" :
                            (l.size() + " 键" + (l.isEmpty() ? ""
                                    : String.format(Locale.US, "  %.2f~%.2f s", l.get(0).t, l.get(l.size() - 1).t))));
        }
        return b.toString();
    }

    private EditText numField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    /** 把「分享 / 导出」按钮挂到指定容器（旧的先摘掉，重复导出不会叠）。 */
    private void showShareIn(ViewGroup parent, File f) {
        if (shareBtn != null && shareBtn.getParent() instanceof ViewGroup) {
            ((ViewGroup) shareBtn.getParent()).removeView(shareBtn);
        }
        Button share = button("分享 / 导出这个动画");
        shareBtn = share;
        share.setOnClickListener(v -> {
            try {
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "导出动画"));
            } catch (Throwable e) {
                toast("分享失败：" + e.getMessage());
            }
        });
        parent.addView(share);
    }

    // ------------------------------------------------------------------ 解算 + 写文件

    /** 帧 → 动画。跑在原包的 Main.pipeline 上（解算/抽帧/写 JSON 全是它的实现）。 */
    private void generate(List<Pose33> frames, String from, int w, int h) {
        if (frames == null || frames.size() < 8) {
            status.setText("帧数太少（" + (frames == null ? 0 : frames.size()) + "），至少录 8 帧再生成");
            resetRecordBtn();
            return;
        }
        status.setText("解算中…（" + frames.size() + " 帧）");
        new Thread(() -> {
            String msg;
            File out = null;
            try {
                MocapIO.Clip clip = new MocapIO.Clip();
                clip.frames.addAll(normalized(frames));
                clip.fps = estimateFps(frames);
                // 分辨率决定解算器的像素尺度：竖屏是 720x1280，横屏是 1280x720。
                // 这里必须用采集时的真实尺寸，写死会让 x/y 各向异性（角度被拉偏）。
                clip.imgW = w > 0 ? w : 720;
                clip.imgH = h > 0 ? h : 1280;

                File dir = outDir();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String name = TextUtils.isEmpty(animName.getText()) ? "mocap" : animName.getText().toString().trim();
                out = new File(dir, "mocap_" + name + "_" + stamp + ".animation.json");

                Main.Options opt = new Main.Options();
                opt.rootMotion = cbRoot == null || cbRoot.isChecked();
                opt.fingers = cbFingers == null || cbFingers.isChecked();
                opt.ultra = cbUltra == null || cbUltra.isChecked();
                opt.smooth = cbSmooth == null || cbSmooth.isChecked();
                opt.overridePose = cbOverride == null || cbOverride.isChecked();
                opt.holdLastFrame = cbHold == null || cbHold.isChecked();
                RigConfig rig = opt.fingers ? RigDefaults.vanillaWithHands() : RigDefaults.vanilla();
                Main.PipelineOut po = Main.pipeline(clip, rig, name,
                        loop.isChecked(), out.getAbsolutePath(), null,
                        opt.ultra ? 0.02 : TOL_DEG, opt);
                int kf = 0;
                for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : po.keyframes.entrySet()) {
                    kf += e.getValue().size();
                }
                AnimCheck.Result chk = AnimCheck.check(out.getAbsolutePath(), name);
                // 关节没入画 / 方向异常时生成的文件依旧"规范"，但动作是猜的 —— 这两句必须排在自检前面
                String q = Main.qualityLine(po);
                String o = Main.orientationLine(po);
                StringBuilder extra = new StringBuilder();
                if (opt.rootMotion) {
                    extra.append(po.positions == null
                            ? "跳跃位移：这段录制里没有明显的离地动作（未写 position）\n"
                            : "跳跃位移：已写入 body.position（最高 " 
                              + String.format(Locale.US, "%.1f", maxPos(po.positions)) + " 像素）\n");
                }
                if (opt.fingers) {
                    extra.append(po.handFrames == 0
                            ? "手指：这段录制没有检测到手（保持关闭状态即可）\n"
                            : "手指：有手的帧 " + po.handFrames + " · 左手 " + po.leftHands
                              + " 帧 · 右手 " + po.rightHands + " 帧\n");
                }
                StringBuilder form = new StringBuilder("动画形式：");
                form.append(opt.smooth ? "Catmull-Rom 平滑（lerp_mode）" : "线性");
                if (opt.overridePose) form.append(" · 覆盖前序动画");
                form.append(loop.isChecked() ? " · 循环"
                        : (opt.holdLastFrame ? " · 保持末帧" : " · 单次"));
                msg = "完成（" + from + "）\n骨骼 " + po.keyframes.size() + " 根 · 关键帧 " + kf
                        + " · 时长 " + String.format(Locale.US, "%.2f", po.durSec) + " 秒\n"
                        + form + "\n"
                        + extra
                        + (q == null ? "" : q + "\n")
                        + (o == null ? "" : o + "\n")
                        + chk.message + "\n" + out.getAbsolutePath();
                if (!chk.ok) {
                    msg = "⚠ 生成成功但规范自检未通过，别直接用：\n" + chk.message
                            + "\n" + out.getAbsolutePath();
                }
            } catch (Throwable e) {
                android.util.Log.w(TAG, "generate failed", e);
                msg = "生成失败：" + e;
                out = null;
            }
            final String fmsg = msg;
            final File fout = out;
            ui.post(() -> {
                status.setText(fmsg);
                resetRecordBtn();
                if (fout != null) {
                    showShare(fout);
                }
            });
        }, "mocap-solve").start();
    }

    private void resetRecordBtn() {
        if (recordBtn != null) {
            recordBtn.setEnabled(true);
            recordBtn.setText("开始录制");
        }
    }

    private void showShare(File f) {
        if (!(status.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) status.getParent();
        if (genEditBtn != null && genEditBtn.getParent() instanceof ViewGroup) {
            ((ViewGroup) genEditBtn.getParent()).removeView(genEditBtn);
        }
        Button edit = button("编辑这个动画（裁剪 / 变速 / 静音骨骼）");
        genEditBtn = edit;
        edit.setOnClickListener(v -> {
            try {
                editClip = AnimJson.read(f.getAbsolutePath());
                showEditor(null, true);
            } catch (Throwable e) {
                toast("读取失败：" + e.getMessage());
            }
        });
        parent.addView(edit);
        showShareIn(parent, f);
    }

    /**
     * 时间轴归零：不管帧来自新采集、旧版采集还是外部 CSV，都从 0 毫秒开始。
     *
     * <p>早期采集用的是 `System.currentTimeMillis()` 的绝对时间，解算器照单全收，导出的
     * `animation_length` 是 17 亿秒（≈57 年）—— BB 里时间轴拖到底也看不到动作。
     */
    private static List<Pose33> normalized(List<Pose33> frames) {
        double min = Double.MAX_VALUE;
        for (Pose33 p : frames) {
            if (p.tMs < min) {
                min = p.tMs;
            }
        }
        if (min == Double.MAX_VALUE || min == 0.0) {
            return frames;
        }
        java.util.List<Pose33> out = new java.util.ArrayList<Pose33>(frames.size());
        for (Pose33 p : frames) {
            Pose33 q = new Pose33();
            q.tMs = p.tMs - min;
            System.arraycopy(p.x, 0, q.x, 0, 33);
            System.arraycopy(p.y, 0, q.y, 0, 33);
            System.arraycopy(p.z, 0, q.z, 0, 33);
            System.arraycopy(p.vis, 0, q.vis, 0, 33);
            out.add(q);
        }
        return out;
    }

    private static double estimateFps(List<Pose33> frames) {
        double span = (frames.get(frames.size() - 1).tMs - frames.get(0).tMs) / 1000.0;
        double fps = span > 0.1 ? (frames.size() - 1) / span : 30.0;
        return Math.max(10.0, Math.min(60.0, fps));
    }

    private File outDir() {
        File dir = getExternalFilesDir("mocap");
        if (dir == null) {
            dir = new File(getFilesDir(), "mocap");
        }
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void openOutputDir() {
        File dir = outDir();
        toast(dir.getAbsolutePath());
    }

    // ------------------------------------------------------------------ 小工具

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(24));
        return l;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFFB0B0B0);
        t.setPadding(0, dp(6), 0, dp(6));
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(on);
        c.setTextColor(0xFFB0B0B0);
        return c;
    }

    private boolean pref(String key, boolean def) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, def);
    }

    private void savePref(String key, boolean value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
