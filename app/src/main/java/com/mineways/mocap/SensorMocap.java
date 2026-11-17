package com.mineways.mocap;

import java.util.ArrayList;
import java.util.List;

/**
 * 单设备传感器 → 骨骼旋转（纯 Java，可 JVM 单测）。
 *
 * <p>输入是手机姿态（Android 旋转矢量，device→world 四元数）与陀螺（device 角速度），
 * 先用 2 秒静止姿态做标定（只保留相对旋转，绑法任意），再把手机长轴方向映射成骨骼的
 * 静止方向 (0,-1,0)，复用相机动捕同一套欧拉拟合（多种子 + 连续性 + 反极点回退）。
 *
 * <p>「转 JSON 能力增强」体现在这一路的后处理：
 * 陀螺高频预测 + 旋转矢量纠偏 → 统一 50Hz 重采样 → 断档保持/淡回站立 →
 * One-Euro 平滑 → ±360 展开 → 5 点中值 → RDP（超高精度 0.02°）。
 * 出包前由 {@link KeyframeDecimator} 保证任意相邻关键帧线性重建误差 ≤ tolDeg。
 */
public final class SensorMocap {

    /** 四根可绑骨的骨骼名（与 RigDefaults 的香草人模一致）。 */
    public enum Limb {
        LEFT_ARM("leftArm", "左臂"),
        RIGHT_ARM("rightArm", "右臂"),
        LEFT_LEG("leftLeg", "左腿"),
        RIGHT_LEG("rightLeg", "右腿");

        public final String bone;
        public final String label;

        Limb(String bone, String label) {
            this.bone = bone;
            this.label = label;
        }
    }

    /** 旋转矢量样本：q = (w,x,y,z)，device→world。 */
    public static final class Rv {
        public final double tMs;
        public final double w, x, y, z;

        public Rv(double tMs, double w, double x, double y, double z) {
            this.tMs = tMs;
            this.w = w;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 陀螺样本：rad/s，device 坐标系。 */
    public static final class Gyro {
        public final double tMs;
        public final double wx, wy, wz;

        public Gyro(double tMs, double wx, double wy, double wz) {
            this.tMs = tMs;
            this.wx = wx;
            this.wy = wy;
            this.wz = wz;
        }
    }

    public static final class Result {
        public final List<KeyframeDecimator.Sample> curve;
        public final double durationSec;
        /** 断档次数（>150ms 没有姿态更新）。 */
        public final int gaps;
        public final double maxRateDegPerSec;
        public final double hz;

        Result(List<KeyframeDecimator.Sample> curve, double durationSec, int gaps,
               double maxRateDegPerSec, double hz) {
            this.curve = curve;
            this.durationSec = durationSec;
            this.gaps = gaps;
            this.maxRateDegPerSec = maxRateDegPerSec;
            this.hz = hz;
        }
    }

    /** 标定平均（四元数符号对齐后累加），返回单位四元数。 */
    public static double[] calibrate(List<Rv> samples) {
        if (samples == null || samples.isEmpty()) {
            return new double[]{1, 0, 0, 0};
        }
        double[] ref = null;
        double[] sum = {0, 0, 0, 0};
        for (Rv r : samples) {
            double[] q = norm(new double[]{r.w, r.x, r.y, r.z});
            if (ref == null) {
                ref = q;
            }
            if (dot(ref, q) < 0) {
                for (int i = 0; i < 4; i++) {
                    q[i] = -q[i];
                }
            }
            for (int i = 0; i < 4; i++) {
                sum[i] += q[i];
            }
        }
        return norm(sum);
    }

    /**
     * 主解算：标定 + 融合 + 重采样 + 平滑 + 欧拉拟合 + 后处理 + 抽帧。
     *
     * @param limb     绑在哪个肢体
     * @param reversed 手机顶端朝肩/髋（倒装）；false = 顶端朝手/脚
     * @param q0       标定姿态（{@link #calibrate}）
     * @param rv       旋转矢量样本（时间单调）
     * @param gyro     陀螺样本（时间单调）
     * @param tolDeg   RDP 容差（超高精度 0.02，普通 0.5）
     * @param hz       输出重采样频率（推荐 50）
     */
    public static Result build(Limb limb, boolean reversed, double[] q0,
                               List<Rv> rv, List<Gyro> gyro, double tolDeg, double hz) {
        if (rv == null || rv.size() < 2) {
            throw new IllegalArgumentException("姿态采样太少（至少 2 帧），无法生成动画");
        }
        List<Rv> fused = fuse(rv, gyro);
        if (fused.size() < 2) {
            throw new IllegalArgumentException("融合后姿态采样太少");
        }
        double t0 = fused.get(0).tMs;
        double t1 = fused.get(fused.size() - 1).tMs;
        if (t1 - t0 < 120) {
            throw new IllegalArgumentException("采集时长太短（不足 0.12 秒）");
        }
        double step = 1000.0 / Math.max(10.0, Math.min(200.0, hz));

        Vec3 axis = reversed ? new Vec3(0, -1, 0) : new Vec3(0, 1, 0);
        OneEuro fx = OneEuro.poseDefault();
        OneEuro fy = OneEuro.poseDefault();
        OneEuro fz = OneEuro.poseDefault();

        List<PoseSolver.Frame> frames = new ArrayList<PoseSolver.Frame>();
        int gaps = 0;
        double[] prevE = null;
        int i = 0;
        boolean inGap = false;
        double gapStart = 0;
        Vec3 gapDir = null;
        Vec3 rest = new Vec3(0, -1, 0);

        for (double t = t0; t <= t1 + 1e-6; t += step) {
            while (i < fused.size() - 2 && fused.get(i + 1).tMs <= t) {
                i++;
            }
            Rv a = fused.get(i);
            Rv b = fused.get(Math.min(i + 1, fused.size() - 1));
            Vec3 d;
            if (b.tMs - a.tMs > 150.0) {
                if (t >= b.tMs - 1e-6) {
                    // 断档结束点：这一帧是真实姿态，不能丢
                    inGap = false;
                    d = dirAt(axis, reversed, q0, b);
                } else {
                    // 断档：先保持 250ms，再用 400ms 淡回站立（与相机路径腿缺失同口径）
                    double since = t - a.tMs;
                    if (!inGap) {
                        inGap = true;
                        gapStart = a.tMs;
                        gaps++;
                    }
                    Vec3 base = dirAt(axis, reversed, q0, a);
                    double miss = t - gapStart;
                    if (miss <= 250) {
                        d = base;
                    } else {
                        double k = Math.min(1.0, (miss - 250) / 400.0);
                        d = Vec3.norm(lerp(base, rest, k));
                    }
                }
            } else {
                inGap = false;
                double span = Math.max(1e-6, b.tMs - a.tMs);
                double u = (t - a.tMs) / span;
                double[] q = slerp(new double[]{a.w, a.x, a.y, a.z},
                        new double[]{b.w, b.x, b.y, b.z}, clamp(u, 0, 1));
                d = dirOf(axis, reversed, q0, q);
            }
            d = new Vec3(fx.filter(t / 1000.0, d.x),
                    fy.filter(t / 1000.0, d.y),
                    fz.filter(t / 1000.0, d.z));
            Vec3 tgt = Vec3.norm(d);
            double[] init = {-Math.asin(clamp(tgt.z, -1, 1)), 0,
                    Math.asin(clamp(tgt.x, -1, 1))};
            boolean[] free = Math.abs(tgt.y) > 0.985
                    ? new boolean[]{true, true, true}
                    : new boolean[]{true, false, true};
            double[] e = PoseSolver.fitDirection(rest, tgt, init, free, prevE);
            prevE = e;
            PoseSolver.Frame f = new PoseSolver.Frame();
            f.tMs = t - t0;
            f.rotDeg.put(limb.bone, e);
            frames.add(f);
        }

        // 与相机路径同一套后处理：先拆 ±360 再做中值，保证 Blockbench 里走最短路径
        PoseSolver.unwrapAngles(frames);
        PoseSolver.median5(frames);
        List<KeyframeDecimator.Sample> raw = new ArrayList<KeyframeDecimator.Sample>();
        for (PoseSolver.Frame f : frames) {
            raw.add(new KeyframeDecimator.Sample(f.tMs / 1000.0, f.rotDeg.get(limb.bone)));
        }
        List<KeyframeDecimator.Sample> dec = KeyframeDecimator.decimate(raw, tolDeg);
        return new Result(dec, (t1 - t0) / 1000.0, gaps, maxRate(fused), step > 0 ? 1000.0 / step : 0);
    }

    /** 静止时的方向：把手机长轴映射到骨骼静止方向 (0,-1,0)。 */
    static Vec3 dirOf(Vec3 axis, boolean reversed, double[] q0, double[] q) {
        double[] rel = mul(conj(norm(q0)), norm(q));
        Vec3 v = rotate(rel, axis);
        if (!reversed) {
            // 顶端朝手/脚：设备 +Y 朝远端；标定系里 +Y 就是"沿肢体"，翻成模型 -Y
            v = new Vec3(v.x, -v.y, -v.z);
        }
        return v.len() < 1e-9 ? new Vec3(0, -1, 0) : Vec3.norm(v);
    }

    private static Vec3 dirAt(Vec3 axis, boolean reversed, double[] q0, Rv r) {
        return dirOf(axis, reversed, q0, new double[]{r.w, r.x, r.y, r.z});
    }

    /** 旋转矢量为主、陀螺高频预测：每个 RV 纠偏一次，RV 之间用陀螺积分。 */
    static List<Rv> fuse(List<Rv> rv, List<Gyro> gyro) {
        List<Rv> out = new ArrayList<Rv>();
        if (gyro == null || gyro.isEmpty()) {
            out.addAll(rv);
            return out;
        }
        int ri = 0, gi = 0;
        double[] q = null;
        double lastT = Double.NaN;
        while (ri < rv.size() || gi < gyro.size()) {
            boolean takeRv = gi >= gyro.size()
                    || (ri < rv.size() && rv.get(ri).tMs <= gyro.get(gi).tMs);
            if (takeRv) {
                Rv r = rv.get(ri++);
                q = norm(new double[]{r.w, r.x, r.y, r.z});
                out.add(r);
                lastT = r.tMs;
            } else {
                Gyro g = gyro.get(gi++);
                if (q == null) {
                    continue;                 // 还没有第一帧姿态，陀螺无法定绝对朝向
                }
                double dt = (g.tMs - lastT) / 1000.0;
                if (dt <= 0 || dt > 0.12) {
                    continue;                 // 时间倒退或长断档：交给下一帧 RV 纠偏
                }
                q = norm(mul(q, expGyro(g.wx * dt, g.wy * dt, g.wz * dt)));
                out.add(new Rv(g.tMs, q[0], q[1], q[2], q[3]));
                lastT = g.tMs;
            }
        }
        return out;
    }

    private static double maxRate(List<Rv> fused) {
        double max = 0;
        for (int i = 1; i < fused.size(); i++) {
            double dt = (fused.get(i).tMs - fused.get(i - 1).tMs) / 1000.0;
            if (dt <= 1e-4) {
                continue;
            }
            Rv a = fused.get(i - 1), b = fused.get(i);
            double d = Math.abs(dot(new double[]{a.w, a.x, a.y, a.z},
                    new double[]{b.w, b.x, b.y, b.z}));
            double ang = 2 * Math.acos(clamp(d, -1, 1));
            max = Math.max(max, Math.toDegrees(ang) / dt);
        }
        return max;
    }

    // ---------------------------------------------------------------- 四元数

    static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
                a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
                a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
                a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]};
    }

    static double[] conj(double[] q) {
        return new double[]{q[0], -q[1], -q[2], -q[3]};
    }

    static double[] norm(double[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-12) {
            return new double[]{1, 0, 0, 0};
        }
        return new double[]{q[0] / n, q[1] / n, q[2] / n, q[3] / n};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    static double[] slerp(double[] a, double[] b, double t) {
        double d = dot(a, b);
        if (d < 0) {
            b = new double[]{-b[0], -b[1], -b[2], -b[3]};
            d = -d;
        }
        if (d > 0.9995) {
            return norm(new double[]{
                    a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t});
        }
        double theta = Math.acos(clamp(d, -1, 1));
        double sin = Math.sin(theta);
        double wa = Math.sin((1 - t) * theta) / sin;
        double wb = Math.sin(t * theta) / sin;
        return new double[]{
                a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb,
                a[2] * wa + b[2] * wb, a[3] * wa + b[3] * wb};
    }

    static Vec3 rotate(double[] q, Vec3 v) {
        double[] qv = {0, v.x, v.y, v.z};
        double[] r = mul(mul(q, qv), conj(q));
        return new Vec3(r[1], r[2], r[3]);
    }

    /** 陀螺增量（body frame）：q ⊗ exp(ω·dt)。 */
    static double[] expGyro(double rx, double ry, double rz) {
        double theta = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (theta < 1e-9) {
            return new double[]{1, 0, 0, 0};
        }
        double s = Math.sin(theta / 2) / theta;
        return new double[]{Math.cos(theta / 2), rx * s, ry * s, rz * s};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }
}