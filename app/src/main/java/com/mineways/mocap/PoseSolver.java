package com.mineways.mocap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心解算器：MediaPipe 33 关键点序列 → MC 人模骨骼 ZXY 欧拉（度）。
 *
 * 坐标链：
 *   图像归一化(x,y,z_mp) → 相机空间像素单位(X=(x-.5)W, Y=(.5-y)H, Z=-z·W)
 *   → 躯干长度归一化 → 躯干解剖基{side=+左, up=+上, fwd=+面向相机}
 *   → 各骨目标方向（躯干基分量 = 骨骼局部空间）→ EulerFit 数值拟合 → bedrock rotation。
 *
 * 面向相机/背对相机自动兼容：躯干基由解剖关键点自身构建，与拍摄视角无关。
 */
public final class PoseSolver {

    public static final class Frame {
        public double tMs;
        public final Map<String, double[]> rotDeg = new HashMap<String, double[]>();
        /** 实体朝向（躯干 yaw，度）：body 被动时用于根运动，见报告。 */
        public double entityYawDeg;
        /** 躯干长度（像素单位），供尺度诊断。 */
        public double torsoPx;
        /** 髋中点高度（躯干长度归一化）：跳跃位移从它减基线得到；躯干不可用时为 NaN。 */
        public double hipYNorm = Double.NaN;
        /** 本帧躯干基（手部解算要用同一套世界坐标）；躯干不可用时为 null。 */
        public Vec3 side, up, fwd;
        /** 这一帧用近端关节（膝/肘）代替远端解出的骨（脚踝/手腕没入画时）。 */
        public final java.util.Set<String> fallback = new java.util.HashSet<String>();
        /** 这一帧远端与近端关节都不可见、只能用上一帧姿态顶着的骨。 */
        public final java.util.Set<String> missing = new java.util.HashSet<String>();
    }

    /** 高置信可见度门限：直接信这颗关节的坐标。 */
    static final double VIS_MIN = 0.3;
    /**
     * 低置信门限：脚踝/手腕在全身画面里很小，MediaPipe 的 visibility 常年只有 0.1~0.3
     * （脚明明在画面里）。低于 {@link #VIS_MIN} 但高于它的坐标**仍然可用**，
     * 只是必须和近端关节（膝/肘）的方向互相验证 —— 见 {@code solveLimb} 的一致性门。
     */
    static final double VIS_WEAK = 0.12;
    /** 两点间距小于它（躯干长度归一化后）视为退化，不拿方向，避免噪声放大。 */
    static final double MIN_SEG = 1e-3;

    private final RigConfig rig;
    private final int imgW, imgH;
    private final OneEuro[][] filters = new OneEuro[33][3];
    private final double[][] held = new double[33][3];
    /** 每个关节有没有出现过至少一帧可信数据 —— 没有就绝不能用 (0,0,0) 冒充（那就是画面中心，腿会朝上）。 */
    private final boolean[] seen = new boolean[33];
    /** 每根骨上一次解出的欧拉：关节整段不可见时沿用它，避免姿态瞬间弹回 0。 */
    private final Map<String, double[]> lastE = new HashMap<String, double[]>();
    /** 每根骨从哪一刻开始"一直看不见"（毫秒）：腿要先保持一小会儿、再平滑回到站立。 */
    private final Map<String, Long> missingSinceMs = new HashMap<String, Long>();
    /** 每个关节最后一次**当帧看见**的时间：冻结的旧坐标只在这个窗口内还算数（腿专用）。 */
    private final double[] lastSeenTMs = new double[33];
    /**
     * 手臂"朝前/朝后"的上一帧符号（+1 朝相机 / -1 背向 / 0 未知）。
     * 出拳时手臂在画面里变短，MediaPipe 的 z 又弱，靠它做迟滞才不会被噪声来回翻。
     */
    private final Map<String, Integer> armFwdSign = new HashMap<String, Integer>();
    /** 真人肩→腕 / 髋→踝 相对躯干长度的比例（躯干＝肩中→髋中）。 */
    private static final double ARM_LEN_TORSO = 1.20;
    /**
     * 深度补全必须"证据很强"才做，否则弯肘、手贴胸这类日常动作会被强行推成前伸：
     * 投影要短到 45% 以内；z 要么非常明确（≥0.35 躯干），要么连续两帧同号（≥0.15）。
     */
    private static final double ARM_PUNCH_RATIO = 0.45;
    private static final double ARM_Z_STRONG = 0.35;
    private static final double ARM_Z_WEAK = 0.15;
    /** 腿看不见时先保持多久（毫秒），之后用多久淡回站立姿态。 */
    private static final double LEG_HOLD_MS = 250;
    private static final double LEG_FADE_MS = 400;
    /** 头部平滑：死区累积（±4° 抖动原地不动，左右转头 y 更钝 ±6°）+ 220°/s 限速。 */
    static final double HEAD_DEAD_DEG = 4.0;
    static final double HEAD_DEAD_YAW_DEG = 6.0;
    static final double HEAD_MAX_RATE_DEG_S = 220.0;
    /**
     * 头部相对躯干的解剖学上限。人类颈椎左右各约 80°，后仰/前俯约 60~70°，
     * 侧倾约 55° —— 任何解算结果超出这个范围都是拟合跳支（典型表现就是他看到的
     * “头 180° 翻转 / 360° 转圈”），必须在这里截断，而不是等导出后靠限速慢慢转回来。
     */
    static final double HEAD_YAW_LIMIT = 80.0;
    static final double HEAD_PITCH_LIMIT = 72.0;
    static final double HEAD_ROLL_LIMIT = 55.0;
    /** 单帧跳变超过它先当噪声（连续 3 帧同方向才认账，避免真·快速回头被误杀）。 */
    static final double HEAD_JUMP_REJECT_DEG = 40.0;
    static final int HEAD_JUMP_CONFIRM = 3;
    /** 头部关键点超过这么久没被“当帧”看见，就不再参与定朝向（冻结坐标只值 0.2s）。 */
    static final double HEAD_STALE_MS = 200.0;
    private double[] lastHeadE;
    private final double[] headAcc = new double[3];
    /** 单帧大跳变的连续计数与方向，用于区分“噪声甩飞”和“真的快速转头”。 */
    private final int[] headJump = new int[3];
    private final double[] headJumpSign = new double[3];
    private double lastHeadT = Double.NaN;
    /** 侧身时肩线会退化，上一帧的躯干基留作后备。 */
    private Vec3 lastSide, lastUp, lastFwd;

    public PoseSolver(RigConfig rig, int imgW, int imgH) {
        this.rig = rig;
        this.imgW = imgW;
        this.imgH = imgH;
        for (int i = 0; i < 33; i++)
            for (int k = 0; k < 3; k++)
                // 头部关键点（鼻/双耳）段长短、噪声占比高 → 重滤波（0.30Hz 截止：他两次反馈
                // 头部摆动灵敏度偏高）；躯干/肢体段长 → 默认 1.2Hz。
                filters[i][k] = (i == Pose33.NOSE || i == Pose33.L_EAR || i == Pose33.R_EAR)
                    ? new OneEuro(0.30, 0.008, 1.0)
                    : OneEuro.poseDefault();
        java.util.Arrays.fill(lastSeenTMs, -1e18);
    }

    public List<Frame> solveClip(List<Pose33> frames) {
        List<Frame> out = new java.util.ArrayList<Frame>();
        for (Pose33 p : frames) out.add(solveFrame(p));
        // 先拆 ±360 跳变再做中值：在 ±180 边界上直接取中值会得到完全错误的角度
        unwrapAngles(out);
        median5(out);
        // 最后兜一层：把 NaN/无穷换成上一帧的值（坏帧不许污染整段 JSON）
        sanitize(out);
        return out;
    }

    /**
     * 数值兜底：任何非有限值都退回上一帧（没有上一帧就 0）。
     *
     * <p>MediaPipe 偶尔会返回 NaN 的 z / 退化的躯干，一旦漏进欧拉拟合就会写出
     * {@code NaN} 关键帧——JSON 直接非法，BB 打开时整段动画丢失。这里统一清理，
     * 让“坏帧”最多影响几毫秒的姿态，不会毁掉整个文件。
     */
    static void sanitize(List<Frame> frames) {
        Map<String, double[]> last = new HashMap<String, double[]>();
        for (Frame f : frames) {
            for (Map.Entry<String, double[]> e : f.rotDeg.entrySet()) {
                double[] v = e.getValue();
                if (v == null) {
                    v = new double[]{0, 0, 0};
                    e.setValue(v);
                }
                double[] prev = last.get(e.getKey());
                for (int k = 0; k < 3; k++) {
                    if (Double.isNaN(v[k]) || Double.isInfinite(v[k])) {
                        v[k] = prev == null ? 0 : prev[k];
                    }
                }
                last.put(e.getKey(), v);
            }
        }
    }

    /**
     * 每根骨逐通道拆掉 ±360 的跳变（把 179°→-179° 记成 179°→181°）。
     *
     * <p>不拆的话导出的两个相邻关键帧看起来差 358°，Blockbench 会按线性插值把手臂
     * **反着转一大圈**；拆完以后同一段旋转走最短路径，任意 360° 环绕的姿势也能正确回放。
     */
    static void unwrapAngles(List<Frame> frames) {
        if (frames.isEmpty()) return;
        for (String bone : frames.get(0).rotDeg.keySet().toArray(new String[0])) {
            double[] prev = null;
            for (Frame f : frames) {
                double[] e = f.rotDeg.get(bone);
                if (e == null) continue;
                if (prev != null) {
                    for (int k = 0; k < 3; k++) {
                        e[k] = prev[k] + EulerBedrock.angDiffDeg(e[k], prev[k]);
                    }
                }
                prev = e;
            }
        }
    }

    /** 5 点中值滤波（逐骨逐通道）：剔除稀疏离群尖峰，对平滑运动零畸变（动捕标准后处理）。 */
    static void median5(List<Frame> frames) {
        for (String bone : frames.isEmpty() ? new String[0] : frames.get(0).rotDeg.keySet().toArray(new String[0])) {
            for (int q = 0; q < 3; q++) {
                double[] ch = new double[frames.size()];
                // 缺帧（躯干整段不可用的段落）用 0 填充，避免直接 NPE
                for (int i = 0; i < frames.size(); i++) {
                    double[] e = frames.get(i).rotDeg.get(bone);
                    ch[i] = e == null ? 0 : e[q];
                }
                for (int i = 0; i < frames.size(); i++) {
                    double[] cur = frames.get(i).rotDeg.get(bone);
                    if (cur == null) continue;
                    int lo = Math.max(0, i - 2), hi = Math.min(frames.size() - 1, i + 2);
                    double[] w = new double[hi - lo + 1];
                    for (int j = lo; j <= hi; j++) w[j - lo] = ch[j];
                    java.util.Arrays.sort(w);
                    cur[q] = w[w.length / 2];
                }
            }
        }
    }

    public Frame solveFrame(Pose33 p) {
        double tSec = p.tMs / 1000.0;

        // 1) 重建相机空间 + 可见度门控（低可见点沿用上一帧；从没见过的点标为不可用，
        //    <b>绝不能用 0 冒充</b>：0 是相机空间原点＝画面正中，脚踝放这儿就是“腿朝上”）
        double[][] P = new double[33][3];
        boolean[] ok = new boolean[33];
        boolean[] strong = new boolean[33];
        for (int i = 0; i < 33; i++) {
            boolean inFr = inFrame(p.x[i], p.y[i]);
            boolean sane = finite(p.x[i]) && finite(p.y[i]) && finite(p.z[i])
                    && p.x[i] > -0.3 && p.x[i] < 1.3 && p.y[i] > -0.3 && p.y[i] < 1.3;
            // presence 说"在画面里"时，visibility 再低也按可用处理（脚小、偏暗时 vis 会低到 0.1）
            double conf = p.vis[i];
            if (p.pres[i] >= 0.5 && inFr) {
                conf = Math.max(conf, VIS_MIN);
            }
            boolean visible = sane && (conf >= VIS_MIN || (conf >= VIS_WEAK && inFr));
            if (!visible) {
                ok[i] = seen[i];
                if (seen[i]) {
                    System.arraycopy(held[i], 0, P[i], 0, 3);
                }
                continue;
            }
            seen[i] = true;
            ok[i] = true;
            strong[i] = conf >= VIS_MIN;
            lastSeenTMs[i] = p.tMs;
            double raw0 = (p.x[i] - 0.5) * imgW;
            double raw1 = (0.5 - p.y[i]) * imgH;
            double raw2 = -p.z[i] * imgW;
            for (int k = 0; k < 3; k++) {
                double raw = k == 0 ? raw0 : (k == 1 ? raw1 : raw2);
                held[i][k] = raw;
                P[i][k] = filters[i][k].filter(tSec, raw);
            }
        }

        // 2) 躯干基（四个点缺一个就整帧没法判姿态：不拿假点凑）
        if (!(ok[Pose33.L_SHOULDER] && ok[Pose33.R_SHOULDER]
                && ok[Pose33.L_HIP] && ok[Pose33.R_HIP])) {
            return missingFrame(p);
        }
        Vec3 shL = pt(P, Pose33.L_SHOULDER), shR = pt(P, Pose33.R_SHOULDER);
        Vec3 hipL = pt(P, Pose33.L_HIP), hipR = pt(P, Pose33.R_HIP);
        Vec3 shMid = Vec3.scale(Vec3.add(shL, shR), 0.5);
        Vec3 hipMid = Vec3.scale(Vec3.add(hipL, hipR), 0.5);
        double torsoPx = Vec3.sub(shMid, hipMid).len();
        // 退化帧（点叠在一起 / 检测到半个人）以前会 throw，整段录制直接报废。
        // 现在只把这一帧标成“看不见”，沿用上一帧姿态继续往下解 —— 容错优先。
        if (!(torsoPx >= 1.0)) {
            return missingFrame(p);
        }
        // 躯干长度归一化（消除拍摄距离/体型尺度）
        shL = Vec3.scale(shL, 1 / torsoPx); shR = Vec3.scale(shR, 1 / torsoPx);
        hipL = Vec3.scale(hipL, 1 / torsoPx); hipR = Vec3.scale(hipR, 1 / torsoPx);
        shMid = Vec3.scale(shMid, 1 / torsoPx);
        hipMid = Vec3.scale(hipMid, 1 / torsoPx);

        // 解剖学左：侧身（90°）时肩线在画面里几乎缩成一点，先退到髋线，再退到上一帧的基。
        // 不这样处理，转身经过 90° 的那几帧整套姿态会抖成随机值。
        Vec3 shSpan = Vec3.sub(shL, shR);
        Vec3 hipSpan = Vec3.sub(hipL, hipR);
        Vec3 sideRaw;
        if (shSpan.len() >= 0.08) {
            sideRaw = Vec3.norm(shSpan);
        } else if (hipSpan.len() >= 0.08) {
            sideRaw = Vec3.norm(hipSpan);
        } else if (lastSide != null) {
            sideRaw = lastSide;
        } else {
            sideRaw = Vec3.norm(shSpan);
        }
        Vec3 upRaw = Vec3.norm(Vec3.sub(shMid, hipMid));              // 上
        if (upRaw.len() < 0.5 && lastUp != null) {
            upRaw = lastUp;
        }
        Vec3 fwdRaw = Vec3.norm(Vec3.cross(sideRaw, upRaw));          // 面向相机(+Z) / 背对(-Z) 自适应
        if (fwdRaw.len() < 0.5 && lastFwd != null) {
            fwdRaw = lastFwd;
        }
        Vec3 side = Vec3.norm(Vec3.sub(sideRaw, Vec3.scale(fwdRaw, Vec3.dot(sideRaw, fwdRaw))));
        Vec3 fwd = fwdRaw;
        Vec3 up = Vec3.cross(fwd, side);                              // 正交化
        if (side.len() > 0.5 && up.len() > 0.5) {
            lastSide = side;
            lastUp = up;
            lastFwd = fwd;
        }

        Frame fr = new Frame();
        fr.tMs = p.tMs;
        fr.torsoPx = torsoPx;
        fr.hipYNorm = hipMid.y;
        fr.side = side;
        fr.up = up;
        fr.fwd = fwd;
        fr.entityYawDeg = Math.toDegrees(Math.atan2(fwd.x, fwd.z));   // 面向相机 ≈ 0

        // 3) 躯干骨（若配置为可动）：相机空间三方向拟合
        RigConfig.Bone body = rig.byKind("body");
        if (body != null && body.animated) {
            Vec3[] rest = {new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1)};
            Vec3[] tgt = {up, side, fwd};
            double[] init = {-Math.asin(clamp(fwd.y, -1, 1)), -Math.asin(clamp(side.z, -1, 1)),
                             -Math.asin(clamp(up.x, -1, 1))};
            fr.rotDeg.put(body.name, EulerFit.solve(new EulerFit.Problem(rest, tgt), init));
        }

        // 4) 头：耳线给“左右”，鼻尖给“前方”，两者叉乘天然定死 yaw/pitch。
        //    - 耳朵没入画（侧脸/头发遮住）→ 退到双眼连线（精度略降但方向不错）；
        //    - 眼睛也没有 → 退到躯干基并记 fallback（绝不拿冻结的旧耳朵硬转）；
        //    - 头部朝向一律过 fitHead（多候选 + 连续性 + 解剖学限位），
        //      避免 ZXY 欧拉在 ±180 分支间跳支，导出后看起来就是“头 180°/360° 乱转”。
        RigConfig.Bone head = rig.byKind("head");
        if (head != null && head.animated) {
            boolean ears = fresh(ok, Pose33.L_EAR, p.tMs) && fresh(ok, Pose33.R_EAR, p.tMs);
            boolean eyes = fresh(ok, Pose33.L_EYE, p.tMs) && fresh(ok, Pose33.R_EYE, p.tMs);
            Vec3 headUp, earDir;
            Vec3 earMid = null;
            if (ears) {
                Vec3 earL = pt(P, Pose33.L_EAR), earR = pt(P, Pose33.R_EAR);
                earMid = Vec3.scale(Vec3.add(earL, earR), 0.5);
                Vec3 upG = Vec3.sub(earMid, shMid);
                headUp = upG.len() > 0.05 ? Vec3.norm(upG) : up;
                earDir = Vec3.norm(Vec3.sub(earL, earR));
            } else if (eyes) {
                Vec3 eyeL = pt(P, Pose33.L_EYE), eyeR = pt(P, Pose33.R_EYE);
                earMid = Vec3.scale(Vec3.add(eyeL, eyeR), 0.5);
                Vec3 upG = Vec3.sub(earMid, shMid);
                headUp = upG.len() > 0.05 ? Vec3.norm(upG) : up;
                earDir = Vec3.norm(Vec3.sub(eyeL, eyeR));
                fr.fallback.add(head.name);
            } else {
                fr.fallback.add(head.name);
                headUp = up;
                earDir = side;
            }
            java.util.List<Vec3> restL = new java.util.ArrayList<Vec3>();
            java.util.List<Vec3> tgtL = new java.util.ArrayList<Vec3>();
            restL.add(new Vec3(0, 1, 0));
            tgtL.add(comp(headUp, side, up, fwd));
            restL.add(new Vec3(1, 0, 0));
            tgtL.add(comp(earDir, side, up, fwd));
            double initX = 0;
            if (earMid != null && fresh(ok, Pose33.NOSE, p.tMs) && strong[Pose33.NOSE]) {
                Vec3 noseF = Vec3.norm(Vec3.sub(pt(P, Pose33.NOSE), earMid));
                restL.add(new Vec3(0, 0, 1));
                tgtL.add(comp(noseF, side, up, fwd));
                initX = -Math.asin(clamp(tgtL.get(2).y, -1, 1));
            }
            double[] init = {initX, -Math.asin(clamp(tgtL.get(1).z, -1, 1)),
                             -Math.asin(clamp(tgtL.get(0).x, -1, 1))};
            double[] e = fitHead(restL.toArray(new Vec3[0]), tgtL.toArray(new Vec3[0]), init, lastHeadE);
            e = smoothHead(e, p.tMs);
            fr.rotDeg.put(head.name, e);
            lastE.put(head.name, e.clone());
        }

        // 5) 四肢：单段有效方向（肩→腕 / 髋→踝）。
        //    远端关节不可见时退到近端（肩→肘 / 髋→膝）；两个都不可见就用上一帧姿态并记 missing。
        //    远端「弱可见」时必须与近端方向一致才采用（脚踝被 MediaPipe 猜偏时用它就是错的，
        //    膝比踝稳得多；一致则保留踝，因为踝才包含小腿的真实摆角）。
        solveLimb(fr, P, ok, strong, "armL", Pose33.L_SHOULDER, Pose33.L_WRIST, Pose33.L_ELBOW,
                side, up, fwd, 0.985, 130.0, 40.0, p.tMs, true, torsoPx);
        solveLimb(fr, P, ok, strong, "armR", Pose33.R_SHOULDER, Pose33.R_WRIST, Pose33.R_ELBOW,
                side, up, fwd, 0.985, 130.0, 40.0, p.tMs, true, torsoPx);
        solveLimb(fr, P, ok, strong, "legL", Pose33.L_HIP, Pose33.L_ANKLE, Pose33.L_KNEE,
                side, up, fwd, 0.985, 100.0, 35.0, p.tMs, false, torsoPx);
        solveLimb(fr, P, ok, strong, "legR", Pose33.R_HIP, Pose33.R_ANKLE, Pose33.R_KNEE,
                side, up, fwd, 0.985, 100.0, 35.0, p.tMs, false, torsoPx);
        return fr;
    }

    /**
     * 头部灵敏度压低 + 抗翻转（他反馈"头经常 180°/360° 转"）。
     *
     * <p>三层保护，缺一不可：
     * <ol>
     *   <li><b>解剖学限位</b>：先 clamp 到 ±80° yaw / ±72° pitch / ±55° roll。
     *       180° 与 360° 在这里就被物理否决，不可能再进关键帧；</li>
     *   <li><b>单帧大跳拒绝</b>：与上帧差 &gt;40° 先当噪声（连续 3 帧同方向才认账），
     *       单帧被 MediaPipe 甩飞不会带着头转半圈；</li>
     *   <li><b>死区累积 + 限速</b>：累计不超过 4°（yaw 6°）原地不动，逐帧抖动被吃掉；
     *       超过死区的部分每帧最多走 220°/s × dt，慢速转头不丢、快速回头不飞。</li>
     * </ol>
     */
    double[] smoothHead(double[] e, double tMs) {
        double[] target = clampHead(e);
        if (lastHeadE == null || Double.isNaN(lastHeadT)) {
            lastHeadE = target.clone();
            lastHeadT = tMs;
            java.util.Arrays.fill(headAcc, 0);
            java.util.Arrays.fill(headJump, 0);
            return target.clone();
        }
        double dt = Math.max(0.015, Math.min(0.2, (tMs - lastHeadT) / 1000.0));
        double maxStep = HEAD_MAX_RATE_DEG_S * dt;
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            double d = EulerBedrock.angDiffDeg(target[k], lastHeadE[k]);
            if (Math.abs(d) > HEAD_JUMP_REJECT_DEG) {
                double sign = Math.signum(d);
                if (headJumpSign[k] != sign) headJump[k] = 0;
                headJumpSign[k] = sign;
                headJump[k]++;
                if (headJump[k] < HEAD_JUMP_CONFIRM) {
                    out[k] = lastHeadE[k];
                    headAcc[k] = 0;
                    continue;
                }
            } else {
                headJump[k] = 0;
            }
            double dead = k == 1 ? HEAD_DEAD_YAW_DEG : HEAD_DEAD_DEG;
            headAcc[k] = clamp(headAcc[k] + d, -3 * dead, 3 * dead);
            if (Math.abs(headAcc[k]) <= dead) {
                out[k] = lastHeadE[k];
                continue;
            }
            double move = headAcc[k] - Math.signum(headAcc[k]) * dead;
            move = clamp(move, -maxStep, maxStep);
            out[k] = lastHeadE[k] + move;
            headAcc[k] = clamp(headAcc[k] - move, -3 * dead, 3 * dead);
        }
        out = clampHead(out);
        lastHeadE = out.clone();
        lastHeadT = tMs;
        return out;
    }

    /**
     * 头部多候选拟合：方向残差达标的候选里，选「离上一帧最近 + yaw 最小」的那个。
     *
     * <p>头的两/三方向约束在正脸附近会接近奇异，ZXY 欧拉有多组等价解
     * （典型是 yaw 179° 与 -1°）。只取 init 附近的那组会在帧间跳支，导出后
     * 表现为头突然转 180°；这里把等价孪生也列进来，用连续性统一裁决。
     */
    static double[] fitHead(Vec3[] rest, Vec3[] tgt, double[] init, double[] prev) {
        EulerFit.Problem prob = new EulerFit.Problem(rest, tgt);
        java.util.List<double[]> cands = new java.util.ArrayList<double[]>();
        cands.add(EulerFit.solve(prob, init));
        if (prev != null) cands.add(EulerFit.solve(prob, prev));
        double[][] seeds = {{0, 0, 0}, {180, 0, 0}, {0, 180, 0}, {0, -180, 0},
                {0, 0, 180}, {0, 0, -180}, {90, 0, 90}, {-90, 0, -90}};
        for (double[] s : seeds) cands.add(EulerFit.solve(prob, s));
        // ZXY 欧拉孪生：(x+180, 180-y, z+180) 与 (x,y,z) 表示同一个旋转。
        // 是否真的等价由下面的方向残差门判定，不等价的会被自动筛掉。
        int n = cands.size();
        for (int i = 0; i < n; i++) {
            double[] c = cands.get(i);
            cands.add(new double[]{norm180(c[0] + 180), norm180(180 - c[1]), norm180(c[2] + 180)});
        }
        double bestDot = -2;
        for (double[] c : cands) bestDot = Math.max(bestDot, minDirDot(c, rest, tgt));
        // 相对门限：只接受“和最优解几乎一样好”的几何解（≈1.4°），
        // 既不会因为鼻/耳深度噪声把候选筛光，也不会为了连续性牺牲精度。
        double floor = bestDot - 5e-4;
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (double[] c : cands) {
            if (minDirDot(c, rest, tgt) < floor) continue;
            double cost = 0;
            if (prev != null) {
                for (int k = 0; k < 3; k++) {
                    cost = Math.max(cost, Math.abs(EulerBedrock.angDiffDeg(c[k], prev[k])));
                }
            }
            // 同等几何解里偏向小 yaw：正常人不会一上来就 180° 回头
            cost += Math.max(0, Math.abs(norm180(c[1])) - HEAD_YAW_LIMIT * 0.75) * 0.8;
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
        return clampHead(best != null ? best : cands.get(0));
    }

    /** 多条“静止方向 → 目标方向”约束里最差的那条点积（1 = 完全吻合）。 */
    private static double minDirDot(double[] e, Vec3[] rest, Vec3[] tgt) {
        double[] R = EulerBedrock.toMatrix(e[0], e[1], e[2]);
        double min = 2;
        for (int i = 0; i < rest.length; i++) {
            Vec3 got = Vec3.norm(EulerBedrock.apply(R, rest[i]));
            min = Math.min(min, Vec3.dot(got, tgt[i]));
        }
        return min;
    }

    /** 头部解剖学限位：超出的头部旋转一律截断（yaw ±80 / pitch ±72 / roll ±55）。 */
    static double[] clampHead(double[] e) {
        return new double[]{
                clamp(e[0], -HEAD_PITCH_LIMIT, HEAD_PITCH_LIMIT),
                clamp(e[1], -HEAD_YAW_LIMIT, HEAD_YAW_LIMIT),
                clamp(e[2], -HEAD_ROLL_LIMIT, HEAD_ROLL_LIMIT)};
    }

    private static double norm180(double v) {
        double x = v % 360.0;
        if (x > 180) x -= 360;
        if (x < -180) x += 360;
        return x;
    }

    /** 头部关键点是否“这一帧刚看见”（冻结坐标最多用 HEAD_STALE_MS）。 */
    private boolean fresh(boolean[] ok, int i, double tMs) {
        return ok[i] && tMs - lastSeenTMs[i] <= HEAD_STALE_MS;
    }

    /** 躯干不可用（点缺失/长度退化）的一帧：所有骨沿用上一帧姿态并标 missing，绝不中断整段解算。 */
    private Frame missingFrame(Pose33 p) {
        Frame fr = new Frame();
        fr.tMs = p.tMs;
        for (RigConfig.Bone b : rig.bones) {
            if (!b.animated) continue;
            fr.missing.add(b.name);
            fr.rotDeg.put(b.name, poseForMissing(b.name, p.tMs));
        }
        return fr;
    }

    /** 近垂直奇异性保护：肢体接近竖直时 y 扭转不可观测 → 锁 0 重拟合。 */
    private void solveLimb(Frame fr, double[][] P, boolean[] ok, boolean[] strong, String kind,
                           int aIdx, int bIdx, int fallbackIdx,
                           Vec3 side, Vec3 up, Vec3 fwd, double singThr,
                           double strongDisagreeDeg, double weakDisagreeDeg, double tMs,
                           boolean arm, double torsoPx) {
        RigConfig.Bone bone = rig.byKind(kind);
        if (bone == null || !bone.animated) return;
        boolean leg = "leftLeg".equals(bone.name) || "rightLeg".equals(bone.name);
        // 腿只用"这帧刚看见"的点：冻结的旧坐标最多算 LEG_HOLD_MS，之后就该回站立了。
        // （手臂不套这条：手可以一直出画，冻结着比乱挥好。）
        boolean aOk = ok[aIdx] && (!leg || tMs - lastSeenTMs[aIdx] <= LEG_HOLD_MS);
        boolean bOk = ok[bIdx] && (!leg || tMs - lastSeenTMs[bIdx] <= LEG_HOLD_MS);
        boolean fOk = ok[fallbackIdx] && (!leg || tMs - lastSeenTMs[fallbackIdx] <= LEG_HOLD_MS);
        Vec3 a = aOk ? pt(P, aIdx) : null;
        Vec3 d = null;
        boolean viaFallback = false;
        Vec3 distal = a != null && bOk ? Vec3.sub(pt(P, bIdx), a) : null;
        Vec3 proximal = a != null && fOk ? Vec3.sub(pt(P, fallbackIdx), a) : null;
        if (distal != null && proximal != null
                && distal.len() >= MIN_SEG && proximal.len() >= MIN_SEG) {
            double ang = angleDeg(distal, proximal);
            double limit = strong[bIdx] ? strongDisagreeDeg : weakDisagreeDeg;
            if (ang > limit) {
                d = proximal;                 // 踝/腕与膝/肘方向矛盾：信近端
                viaFallback = true;
            } else {
                d = distal;                   // 一致：用远端（更精细，含小腿/小臂摆角）
            }
        } else if (distal != null && distal.len() >= MIN_SEG) {
            d = distal;
        } else if (proximal != null && proximal.len() >= MIN_SEG) {
            d = proximal;
            viaFallback = true;
        }
        if (d == null) {
            fr.missing.add(bone.name);
            fr.rotDeg.put(bone.name, poseForMissing(bone.name, tMs));
            return;
        }
        missingSinceMs.remove(bone.name);
        if (viaFallback) {
            fr.fallback.add(bone.name);
        }
        // 方向：先在躯干基里换成"躯干单位"，手臂再按长度先验补回缺失的深度
        // （出拳时手臂在画面里只剩一小截，靠 x/y 会算成朝下；用肩→腕≈1.2 躯干长度反推 fwd 分量）。
        Vec3 tgt = Vec3.scale(comp(d, side, up, fwd), 1.0 / torsoPx);
        if (arm) {
            Vec3 completed = completeArmDepth(tgt, bone.name);
            if (completed != tgt && proximal != null) {
                // 只给"整条手臂伸直朝前"补深度：上臂（肩→肘）也得指向同一方向。
                // 弯腰/抱胸这类弯肘动作，肘朝下、腕被补成朝前 → 方向矛盾，直接丢弃补全。
                Vec3 proxT = Vec3.norm(comp(proximal, side, up, fwd));
                boolean agree = Vec3.dot(Vec3.norm(completed), proxT)
                        > Math.cos(Math.toRadians(60));
                if (!agree) {
                    completed = tgt;
                }
            }
            tgt = completed;
        }
        tgt = Vec3.norm(tgt);
        Vec3 rest = new Vec3(0, -1, 0);
        double[] init = {-Math.asin(clamp(tgt.z, -1, 1)), 0, Math.asin(clamp(tgt.x, -1, 1))};
        boolean[] free = Math.abs(tgt.y) > singThr
                ? new boolean[]{true, false, true}
                : new boolean[]{true, true, true};
        double[] e = fitDirection(rest, tgt, init, free, lastE.get(bone.name));
        fr.rotDeg.put(bone.name, e);
        lastE.put(bone.name, e.clone());
    }

    /**
     * 手臂长度先验：肩→腕的 3D 长度约 1.2 个躯干。仅当"证据很强"才补：
     * 投影短到 45% 以内，并且 MediaPipe 的 z 要么非常明确（≥0.35 躯干）、要么连续两帧同号（≥0.15）。
     * 日常弯肘/抱胸的手也离肩很近，但 z 模糊 —— 那种情况**不猜**，宁可少识别一次出拳，
     * 也不要把正常动作随机推成前伸（他会觉得"整段像乱做"）。
     */
    Vec3 completeArmDepth(Vec3 t, String bone) {
        double p = Math.hypot(t.x, t.y);
        if (p > ARM_LEN_TORSO * ARM_PUNCH_RATIO) {
            return t;
        }
        double az = Math.abs(t.z);
        int sign = t.z > 0 ? 1 : -1;
        if (az >= ARM_Z_STRONG) {
            armFwdSign.put(bone, sign);            // 深度证据足够强：直接用
        } else if (az >= ARM_Z_WEAK && armFwdSign.get(bone) != null
                && armFwdSign.get(bone) == sign) {
            // 弱证据：必须与上一帧同号（迟滞），避免符号抖动把手臂前后翻
        } else {
            if (az >= ARM_Z_WEAK) {
                armFwdSign.put(bone, sign);
            }
            return t;
        }
        double f = Math.min(0.95, Math.sqrt(Math.max(0, ARM_LEN_TORSO * ARM_LEN_TORSO - p * p)));
        return new Vec3(t.x, t.y, sign * f);
    }

    /**
     * 单方向拟合 + 反极点回退 + 连续性选择。
     *
     * <p>单方向约束有一整圈等价解（绕骨轴自转看不出来），帧与帧之间可能跳到另一圈上，
     * 导出后插值会"反着转一大圈"。这里把 {@code prev}（上一帧欧拉）也当一个种子，
     * 并在残差接近最优的候选里挑**离上一帧最近**的那个 —— 覆盖整个球面且不会翻。
     */
    static double[] fitDirection(Vec3 rest, Vec3 tgt, double[] init, boolean[] free) {
        return fitDirection(rest, tgt, init, free, null);
    }

    static double[] fitDirection(Vec3 rest, Vec3 tgt, double[] init, boolean[] free, double[] prev) {
        EulerFit.Problem prob = new EulerFit.Problem(new Vec3[]{rest}, new Vec3[]{tgt});
        java.util.List<double[]> cands = new java.util.ArrayList<double[]>();
        cands.add(EulerFit.solveMask(prob, init, free));
        double[][] seeds = {{180, 0, 0}, {0, 0, 180}, {0, 0, -180},
                {95, 0, 95}, {-95, 0, -95}, {0, 180, 0}};
        for (double[] seed : seeds) {
            cands.add(EulerFit.solveMask(prob, seed, free));
        }
        if (prev != null) {
            cands.add(EulerFit.solveMask(prob, prev, free));
        }
        double bestDot = -2;
        for (double[] c : cands) {
            bestDot = Math.max(bestDot, dirDot(c, rest, tgt));
        }
        double[] best = cands.get(0);
        if (prev != null && bestDot > 0.9) {
            double bestDist = Double.MAX_VALUE;
            double floor = Math.max(0.9, bestDot - 5e-3);
            for (double[] c : cands) {
                if (dirDot(c, rest, tgt) < floor) continue;
                double dist = 0;
                for (int k = 0; k < 3; k++) {
                    dist = Math.max(dist, Math.abs(EulerBedrock.angDiffDeg(c[k], prev[k])));
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
            return best;
        }
        for (double[] c : cands) {
            if (dirDot(c, rest, tgt) >= bestDot - 1e-9) {
                best = c;
                break;
            }
        }
        return best;
    }

    private static double dirDot(double[] e, Vec3 rest, Vec3 tgt) {
        Vec3 got = Vec3.norm(EulerBedrock.apply(
                EulerBedrock.toMatrix(e[0], e[1], e[2]), rest));
        return Vec3.dot(got, tgt);
    }

    private static double angleDeg(Vec3 a, Vec3 b) {
        double c = Vec3.dot(Vec3.norm(a), Vec3.norm(b));
        return Math.toDegrees(Math.acos(clamp(c, -1, 1)));
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** 归一化坐标落在画面里（含一点点边距）：画面外的点即使有数值也不能拿来定方向。 */
    private static boolean inFrame(double x, double y) {
        return x >= -0.02 && x <= 1.02 && y >= -0.02 && y <= 1.02;
    }

    /**
     * 这一帧看不见某根骨时给什么姿态。
     *
     * <p><b>腿</b>：先保持 {@value #LEG_HOLD_MS}ms（躲开一两帧的抖动），再用
     * {@value #LEG_FADE_MS}ms 平滑回到 (0,0,0) = 站立、脚在地面 —— 否则一次起跳后
     * 腿就再也"看不见"，整段动画会一直保持收腿姿势。
     * <p><b>其他骨</b>：沿用最后一次姿态；从没解出来过就是静止。
     */
    private double[] poseForMissing(String bone, double tMs) {
        double[] last = lastE.get(bone);
        if (last == null) {
            return new double[]{0, 0, 0};
        }
        if (!"leftLeg".equals(bone) && !"rightLeg".equals(bone)) {
            return last.clone();
        }
        Long since0 = missingSinceMs.get(bone);
        long since;
        if (since0 == null) {
            since = (long) tMs;
            missingSinceMs.put(bone, since);
        } else {
            since = since0;
        }
        double miss = tMs - since;
        if (miss <= LEG_HOLD_MS) {
            return last.clone();
        }
        double k = Math.min(1.0, (miss - LEG_HOLD_MS) / LEG_FADE_MS);
        double[] out = {last[0] * (1 - k), last[1] * (1 - k), last[2] * (1 - k)};
        lastE.put(bone, out.clone());
        return out;
    }

    /** 手部 21 点 → 手掌 + 五指旋转（与身体同一套躯干基与相机空间尺度）。 */
    public HandSolver.Result solveHand(Pose33 p, Frame f, boolean left) {
        int h = left ? 0 : 1;
        if (!p.handSeen[h] || f == null || f.side == null) return null;
        Vec3[] pts = new Vec3[21];
        for (int i = 0; i < 21; i++) {
            pts[i] = new Vec3((p.handX[h][i] - 0.5) * imgW,
                    (0.5 - p.handY[h][i]) * imgH,
                    -p.handZ[h][i] * imgW);
        }
        return HandSolver.solve(pts, f.side, f.up, f.fwd);
    }

    /** 世界向量 → 躯干基分量（骨骼局部坐标）。 */
    private static Vec3 comp(Vec3 v, Vec3 side, Vec3 up, Vec3 fwd) {
        return new Vec3(Vec3.dot(v, side), Vec3.dot(v, up), Vec3.dot(v, fwd));
    }

    private static Vec3 pt(double[][] P, int i) { return new Vec3(P[i][0], P[i][1], P[i][2]); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
