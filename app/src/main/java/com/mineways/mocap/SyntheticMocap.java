package com.mineways.mocap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 合成真值动捕发生器：参数化动作（角度空间真值）→ 前向运动学(FK)出关节点
 * → 针孔相机投影成 MediaPipe 归一化坐标（含 z 深度语义）→ 高斯噪声 → Pose33。
 *
 * 噪声水平取真实 MediaPipe Pose 中档手机实测量级：
 *   σ_xy = 0.0035（归一化坐标），σ_z = 0.011（约 3 倍于 xy，深度弱），
 *   2% 帧随机遮挡一个肢体点（vis=0.05，考验门控）。
 * 真值与解算共用同一 EulerBedrock 实现 → 欧拉序/符号由 BB 标定统一裁决，闭环无循环论证。
 */
public final class SyntheticMocap {
    private SyntheticMocap() {}

    public interface Angles { double[] at(String kind, double tSec); }

    public static final class Gen {
        public String name;
        public boolean loop;
        public double durSec;
        public int fps = 30;
        public Angles angles;
        /** 肘/膝弯曲（度），恒定；仅影响单段有效方向，用于方向误差检验的诚实性。 */
        public double elbowBend = 12;
        /** 鼻部可见度（背对相机时应 < 0.3，模拟后脑勺遮挡）。 */
        public double noseVis = 1.0;
    }

    public static final class Result {
        public MocapIO.Clip clip;
        public Map<String, List<KeyframeDecimator.Sample>> gt = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        /** 手臂“单段有效方向”真值（body 局部空间）：Ra·Rf·(0,-1,0)，供方向误差检验。 */
        public Map<String, List<Vec3>> gtEffDir = new LinkedHashMap<String, List<Vec3>>();
    }

    // ---- 人体测量（米） ----
    static final double HIP_H = 0.95, TORSO = 0.45, SH_HALF = 0.18, HIP_HALF = 0.12;
    static final double ARM = 0.28, FOREARM = 0.26, THIGH_SHIN = 0.42;
    static final double HEAD_PIVOT_UP = 0.045, EAR_UP = 0.115, EAR_HALF = 0.09, NOSE_FWD = 0.10;

    static final int IMG_W = 1280, IMG_H = 720;
    static final double CAM_DIST = 2.6, CAM_H = 1.10, FOV_Y = 55;

    public static Result generate(Gen g, long seed) {
        Random rnd = new Random(seed);
        double dt = 1.0 / g.fps;
        int n = (int) Math.round(g.durSec * g.fps) + 1;
        MocapIO.Clip clip = new MocapIO.Clip();
        clip.imgW = IMG_W;
        clip.imgH = IMG_H;
        clip.fps = g.fps;
        Result res = new Result();
        String[] kinds = {"body", "head", "armL", "armR", "legL", "legR"};
        Map<String, List<KeyframeDecimator.Sample>> gt = res.gt;
        for (String k : kinds) gt.put(k, new ArrayList<KeyframeDecimator.Sample>());
        res.gtEffDir.put("armL", new ArrayList<Vec3>());
        res.gtEffDir.put("armR", new ArrayList<Vec3>());

        double tanHalf = Math.tan(Math.toRadians(FOV_Y / 2));
        double fPx = (IMG_H / 2.0) / tanHalf;

        for (int f = 0; f < n; f++) {
            double t = f * dt;
            Pose33 p = new Pose33();
            p.tMs = Math.round(t * 1000);
            Map<String, double[]> A = new LinkedHashMap<String, double[]>();
            for (String k : kinds) A.put(k, g.angles.at(k, t));

            // FK（相机空间 = 世界空间；人面向 +Z，相机在 +Z 处看向 -Z）
            double[] Rb = mat("body", A);
            Vec3 hipMid = new Vec3(0, HIP_H, 0);
            Vec3 hipL = Vec3.add(hipMid, EulerBedrock.apply(Rb, new Vec3(HIP_HALF, 0, 0)));
            Vec3 hipR = Vec3.add(hipMid, EulerBedrock.apply(Rb, new Vec3(-HIP_HALF, 0, 0)));
            Vec3 shMid = Vec3.add(hipMid, EulerBedrock.apply(Rb, new Vec3(0, TORSO, 0)));
            Vec3 shL = Vec3.add(shMid, EulerBedrock.apply(Rb, new Vec3(SH_HALF, 0.02, 0)));
            Vec3 shR = Vec3.add(shMid, EulerBedrock.apply(Rb, new Vec3(-SH_HALF, 0.02, 0)));

            double[] RaL = mat("armL", A), RaR = mat("armR", A);
            double[] RlL = mat("legL", A), RlR = mat("legR", A);
            double[] Rh = mat("head", A);
            double[] RfL = EulerBedrock.toMatrix(g.elbowBend, 0, 0); // 肘弯：臂局部 X 轴
            double[] RfR = EulerBedrock.toMatrix(g.elbowBend, 0, 0);

            // 世界位移 = Rb·(Ra·offset)， Ra 为骨骼局部欧拉，链式右结合
            double[] RbRaL = EulerBedrock.mul(Rb, RaL), RbRaR = EulerBedrock.mul(Rb, RaR);
            double[] RbRaLf = EulerBedrock.mul(RbRaL, RfL), RbRaRf = EulerBedrock.mul(RbRaR, RfR);
            double[] RbRlL = EulerBedrock.mul(Rb, RlL), RbRlR = EulerBedrock.mul(Rb, RlR);
            double[] RbRh = EulerBedrock.mul(Rb, Rh);

            Vec3 elbL = Vec3.add(shL, EulerBedrock.apply(RbRaL, new Vec3(0, -ARM, 0)));
            Vec3 wriL = Vec3.add(elbL, EulerBedrock.apply(RbRaLf, new Vec3(0, -FOREARM, 0)));
            Vec3 elbR = Vec3.add(shR, EulerBedrock.apply(RbRaR, new Vec3(0, -ARM, 0)));
            Vec3 wriR = Vec3.add(elbR, EulerBedrock.apply(RbRaRf, new Vec3(0, -FOREARM, 0)));

            Vec3 kneeL = Vec3.add(hipL, EulerBedrock.apply(RbRlL, new Vec3(0, -THIGH_SHIN, 0)));
            Vec3 ankL = Vec3.add(kneeL, EulerBedrock.apply(RbRlL, new Vec3(0, -THIGH_SHIN, 0)));
            Vec3 kneeR = Vec3.add(hipR, EulerBedrock.apply(RbRlR, new Vec3(0, -THIGH_SHIN, 0)));
            Vec3 ankR = Vec3.add(kneeR, EulerBedrock.apply(RbRlR, new Vec3(0, -THIGH_SHIN, 0)));

            Vec3 headP = Vec3.add(shMid, EulerBedrock.apply(Rb, new Vec3(0, HEAD_PIVOT_UP, 0)));
            Vec3 earMid = Vec3.add(headP, EulerBedrock.apply(RbRh, new Vec3(0, EAR_UP, 0)));
            Vec3 earL = Vec3.add(headP, EulerBedrock.apply(RbRh, new Vec3(EAR_HALF, EAR_UP, 0)));
            Vec3 earR = Vec3.add(headP, EulerBedrock.apply(RbRh, new Vec3(-EAR_HALF, EAR_UP, 0)));
            Vec3 nose = Vec3.add(headP, EulerBedrock.apply(RbRh, new Vec3(0, EAR_UP - 0.03, NOSE_FWD)));

            Map<Integer, Vec3> lm = new LinkedHashMap<Integer, Vec3>();
            lm.put(Pose33.NOSE, nose);
            lm.put(Pose33.L_EAR, earL);
            lm.put(Pose33.R_EAR, earR);
            lm.put(Pose33.L_SHOULDER, shL);
            lm.put(Pose33.R_SHOULDER, shR);
            lm.put(Pose33.L_ELBOW, elbL);
            lm.put(Pose33.R_ELBOW, elbR);
            lm.put(Pose33.L_WRIST, wriL);
            lm.put(Pose33.R_WRIST, wriR);
            lm.put(Pose33.L_HIP, hipL);
            lm.put(Pose33.R_HIP, hipR);
            lm.put(Pose33.L_KNEE, kneeL);
            lm.put(Pose33.R_KNEE, kneeR);
            lm.put(Pose33.L_ANKLE, ankL);
            lm.put(Pose33.R_ANKLE, ankR);

            double depthRef = CAM_DIST - hipMid.z;
            double kMetric = 2 * tanHalf * depthRef * (IMG_W / (double) IMG_H);

            // 遮挡模拟：2% 帧随机肢体点不可见
            int[] limbIds = {13, 14, 15, 16, 25, 26, 27, 28};
            int occluded = rnd.nextDouble() < 0.02 ? limbIds[rnd.nextInt(limbIds.length)] : -1;

            for (int i = 0; i < 33; i++) {
                Vec3 w = lm.get(i);
                if (w == null) { p.vis[i] = 0.2; continue; }   // 未用关键点：不可见
                Vec3 r = Vec3.sub(w, new Vec3(0, CAM_H, CAM_DIST));
                double depth = -r.z;
                double xPx = IMG_W / 2.0 + fPx * r.x / depth;
                double yPx = IMG_H / 2.0 - fPx * r.y / depth;
                p.x[i] = xPx / IMG_W + rnd.nextGaussian() * 0.0035;
                p.y[i] = yPx / IMG_H + rnd.nextGaussian() * 0.0035;
                p.z[i] = -(depthRef - depth) / kMetric + rnd.nextGaussian() * 0.011;
                p.vis[i] = (i == occluded) ? 0.05 : 1.0;
            }
            p.vis[Pose33.NOSE] = g.noseVis; // 鼻部可见度由动作场景决定（背视 < 0.3 触发解算降级）
            clip.frames.add(p);

            // 手臂单段有效方向真值（body 局部空间）：RaL·[(0,-ARM,0)+RfL·(0,-FOREARM,0)] 归一化
            Vec3 upSegL = EulerBedrock.apply(RaL, new Vec3(0, -ARM, 0));
            Vec3 foSegL = EulerBedrock.apply(EulerBedrock.mul(RaL, RfL), new Vec3(0, -FOREARM, 0));
            Vec3 upSegR = EulerBedrock.apply(RaR, new Vec3(0, -ARM, 0));
            Vec3 foSegR = EulerBedrock.apply(EulerBedrock.mul(RaR, RfR), new Vec3(0, -FOREARM, 0));
            res.gtEffDir.get("armL").add(Vec3.norm(Vec3.add(upSegL, foSegL)));
            res.gtEffDir.get("armR").add(Vec3.norm(Vec3.add(upSegR, foSegR)));

            for (String k : kinds) {
                double[] a = A.get(k);
                gt.get(k).add(new KeyframeDecimator.Sample(t, new double[]{a[0], a[1], a[2]}));
            }
        }
        res.clip = clip;
        return res;
    }

    /** 面向相机（+Z）的三个内置动作。 */
    public static Gen walk() {
        Gen g = new Gen();
        g.name = "walk";
        g.loop = true;
        g.durSec = 2.2;
        final double w = 2 * Math.PI / 1.1;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                double s = Math.sin(w * t);
                if (kind.equals("legR")) return new double[]{26 * s, 0, -2};
                if (kind.equals("legL")) return new double[]{-26 * s, 0, 2};
                if (kind.equals("armR")) return new double[]{-22.1 * s, 0, -3};
                if (kind.equals("armL")) return new double[]{22.1 * s, 0, 3};
                if (kind.equals("body")) return new double[]{0, 9 * Math.sin(w * t), 0};
                if (kind.equals("head")) return new double[]{2 * Math.sin(1.3 * w * t), 14 * Math.sin(0.9 * w * t), 0};
                return new double[3];
            }
        };
        return g;
    }

    public static Gen wave() {
        Gen g = new Gen();
        g.name = "wave";
        g.loop = false;
        g.durSec = 1.8;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                double sm = smooth(Math.min(1, t / 0.5));
                if (kind.equals("armR")) {
                    double osc = t > 0.5 ? 5 * Math.sin(2 * Math.PI * 1.8 * (t - 0.5)) : 0;
                    return new double[]{-8 * sm, 0, -75 * sm - osc * sm};
                }
                if (kind.equals("armL")) return new double[]{0, 0, 3};
                if (kind.equals("head")) return new double[]{0, -10 * smooth(Math.min(1, t / 0.4)), 0};
                if (kind.equals("legR")) return new double[]{0, 0, -2};
                if (kind.equals("legL")) return new double[]{0, 0, 2};
                return new double[3];
            }
        };
        return g;
    }

    public static Gen idle() {
        Gen g = new Gen();
        g.name = "idle";
        g.loop = true;
        g.durSec = 3.0;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                double br = Math.sin(2 * Math.PI * t / 3.0);
                if (kind.equals("armR")) return new double[]{-2 + 1.5 * br, 0, -3};
                if (kind.equals("armL")) return new double[]{-2 + 1.5 * br, 0, 3};
                if (kind.equals("head")) return new double[]{2 + 1.2 * br, 3 * Math.sin(2 * Math.PI * t / 3.8), 0};
                if (kind.equals("legR")) return new double[]{0, 0, -2};
                if (kind.equals("legL")) return new double[]{0, 0, 2};
                return new double[3];
            }
        };
        return g;
    }

    /** 叠加层专用：呼吸微动作（振幅小、频率慢，适合叠加在任意基础层上）。 */
    public static Gen breatheAdditive() {
        Gen g = new Gen();
        g.name = "breathe";
        g.loop = true;
        g.durSec = 3.0;
        g.elbowBend = 0;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                double br = Math.sin(2 * Math.PI * t / 3.0);
                if (kind.equals("armR")) return new double[]{0, 0, -1.2 * br};
                if (kind.equals("armL")) return new double[]{0, 0, 1.2 * br};
                if (kind.equals("head")) return new double[]{1.0 * br, 0, 0};
                return new double[3];
            }
        };
        return g;
    }

    public static Gen breathe() {
        Gen g = new Gen();
        g.name = "breathe";
        g.loop = true;
        g.durSec = 3.0;
        g.elbowBend = 10;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                double w = 2 * Math.PI / 3.0;
                if ("armR".equals(kind)) return new double[]{-2 + 2 * Math.sin(w * t), 0, -2.5};
                if ("armL".equals(kind)) return new double[]{-2 + 2 * Math.sin(w * t), 0, 2.5};
                if ("head".equals(kind)) return new double[]{1.2 + 0.8 * Math.sin(w * t), 0, 0};
                if ("body".equals(kind)) return new double[]{0, 0, 0};
                if ("legR".equals(kind)) return new double[]{0, 0, -2};
                return new double[]{0, 0, 2};
            }
        };
        return g;
    }

    /** 带静止预备段的行走：0~1.1s 站立（多余动作候选），之后两个行走周期。用于"分析→剪辑→成品"全流程演示。 */
    public static Gen walkPrep() {
        final Gen w = walk();
        final SyntheticMocap.Angles inner = w.angles;
        Gen g = new Gen();
        g.name = "walk_prep";
        g.loop = false;
        g.durSec = 4.4;
        g.elbowBend = w.elbowBend;
        g.angles = new Angles() {
            public double[] at(String kind, double t) {
                if (t < 1.1) {
                    double u = t * 2 * Math.PI;
                    if ("armR".equals(kind)) return new double[]{1.5 * Math.sin(u), 0, -3};
                    if ("armL".equals(kind)) return new double[]{1.5 * Math.sin(u), 0, 3};
                    if ("head".equals(kind)) return new double[]{1.2, 0, 0};
                    if ("body".equals(kind)) return new double[]{0, 0, 0};
                    if ("legR".equals(kind)) return new double[]{0, 0, -2};
                    return new double[]{0, 0, 2};
                }
                return inner.at(kind, t - 1.1);
            }
        };
        return g;
    }

    static double smooth(double u) { return u * u * (3 - 2 * u); }

    private static double[] mat(String kind, Map<String, double[]> A) {
        double[] a = A.get(kind);
        return EulerBedrock.toMatrix(a[0], a[1], a[2]);
    }
}
