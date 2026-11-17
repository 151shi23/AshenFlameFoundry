package com.mineways.mocap;

import java.util.ArrayList;
import java.util.List;

/**
 * 动画在某一时刻的**二维火柴人**（喂给预览控件；纯函数，可在 JVM 断言）。
 *
 * <p>用原版人模的枢轴与骨骼长度：髋 y=12、肩 y=24、头 +8、臂/腿各 12、手 +4、指 +3。
 * 角度按 BB 的显示语义换算（JSON → 内部 = -x,-y,z，R = Rz·Ry·Rx），
 * 与导出的动画在 Blockbench 里看到的姿态一致。投影采用轻微 3/4 视角（x 加 z 的 0.35 倍），
 * 这样前后摆动的手脚也看得出来。
 */
public final class AnimPose {
    private AnimPose() {}

    public static final int TORSO = 0, ARM = 1, LEG = 2, HEAD_LINE = 3, HAND = 4, FINGER = 5;

    public static final class Seg {
        public final float x1, y1, x2, y2;
        public final int kind;
        /** 属于哪根骨（可视化拖拽用）；躯干为 null。 */
        public final String bone;

        Seg(float x1, float y1, float x2, float y2, int kind, String bone) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.kind = kind;
            this.bone = bone;
        }
    }

    public static final class Snapshot {
        public final List<Seg> segs = new ArrayList<Seg>();
        public boolean hasHands;
        public boolean hasFingers;
    }

    /** 模型坐标（x 右、y 上，原版人模像素）→ 屏幕；只做轻微 3/4 透视。 */
    private static float sx(double x, double z) {
        return (float) (x + 0.35 * z);
    }

    public static Snapshot at(AnimJson.Clip c, double t) {
        Snapshot s = new Snapshot();
        if (c == null) return s;
        double hipY = 12, shoulderY = 24;

        // 躯干：本解算不导出 body 旋转（被动），保持直立
        Vec3 hipMid = new Vec3(0, hipY, 0);
        Vec3 shoulderMid = new Vec3(0, shoulderY, 0);
        s.segs.add(new Seg(sx(0, 0), (float) -hipY, sx(0, 0), (float) -shoulderY, TORSO, null));

        // 头：静止方向朝上，长 8
        Vec3 headDir = dir(euler(c, "head", t), true);
        Vec3 headEnd = add(shoulderMid, scale(headDir, 8));
        s.segs.add(new Seg(sx(shoulderMid.x, shoulderMid.z), (float) -shoulderMid.y,
                sx(headEnd.x, headEnd.z), (float) -headEnd.y, HEAD_LINE, "head"));

        // 四肢：静止方向朝下，长 12
        s.segs.add(limb(c, t, "leftArm", 5, 22, 12, ARM));
        s.segs.add(limb(c, t, "rightArm", -5, 22, 12, ARM));
        s.segs.add(limb(c, t, "leftLeg", 2, 12, 12, LEG));
        s.segs.add(limb(c, t, "rightLeg", -2, 12, 12, LEG));

        // 手：接在臂端，长 4；手指：接在手端，长 3
        for (int i = 0; i < 2; i++) {
            boolean left = i == 0;
            String arm = (left ? "left" : "right") + "Arm";
            String hand = (left ? "left" : "right") + "Hand";
            if (!c.bones.containsKey(hand)) continue;
            s.hasHands = true;
            Vec3 pivot = new Vec3(left ? 5 : -5, 22, 0);
            Vec3 handStart = add(pivot, scale(dir(euler(c, arm, t), false), 12));
            Vec3 handEnd = add(handStart, scale(dir(euler(c, hand, t), false), 4));
            s.segs.add(new Seg(sx(handStart.x, handStart.z), (float) -handStart.y,
                    sx(handEnd.x, handEnd.z), (float) -handEnd.y, HAND, hand));
            for (String f : HandSolver.FINGER_NAMES) {
                String bone = (left ? "left" : "right") + f;
                if (!c.bones.containsKey(bone)) continue;
                s.hasFingers = true;
                Vec3 tip = add(handEnd, scale(dir(euler(c, bone, t), false), 3));
                s.segs.add(new Seg(sx(handEnd.x, handEnd.z), (float) -handEnd.y,
                        sx(tip.x, tip.z), (float) -tip.y, FINGER, bone));
            }
        }

        // 跳跃：body.position.y（模型像素）整体抬升。屏幕 y 与模型 y 相反，所以减。
        List<KeyframeDecimator.Sample> pos = c.positions.get("body");
        if (pos != null) {
            double[] v = AnimJson.valueAt(pos, t);
            double dy = v == null ? 0 : v[1];
            if (Math.abs(dy) > 0.01) {
                List<Seg> shifted = new ArrayList<Seg>(s.segs.size());
                for (Seg g : s.segs) {
                    shifted.add(new Seg(g.x1, (float) (g.y1 - dy), g.x2, (float) (g.y2 - dy), g.kind, g.bone));
                }
                s.segs.clear();
                s.segs.addAll(shifted);
            }
        }
        return s;
    }

    private static Seg limb(AnimJson.Clip c, double t, String bone, double px, double py,
                            double len, int kind) {
        Vec3 pivot = new Vec3(px, py, 0);
        Vec3 end = add(pivot, scale(dir(euler(c, bone, t), false), len));
        return new Seg(sx(pivot.x, pivot.z), (float) -pivot.y,
                sx(end.x, end.z), (float) -end.y, kind, bone);
    }

    /** 某根骨在 t 时刻的 JSON 欧拉（缺失＝静止）。 */
    private static double[] euler(AnimJson.Clip c, String bone, double t) {
        List<KeyframeDecimator.Sample> l = c.bones.get(bone);
        if (l == null || l.isEmpty()) return new double[]{0, 0, 0};
        double[] v = AnimJson.valueAt(l, t);
        return v == null ? new double[]{0, 0, 0} : v;
    }

    /** JSON 欧拉 → 世界方向（rest 朝下/朝上），与 BB 的显示一致。 */
    static Vec3 dir(double[] jsonEuler, boolean upRest) {
        double x = -jsonEuler[0], y = -jsonEuler[1], z = jsonEuler[2];
        double[] m = EulerBedrock.toMatrix(x, y, z);
        Vec3 rest = new Vec3(0, upRest ? 1 : -1, 0);
        return EulerBedrock.apply(m, rest);
    }

    private static Vec3 add(Vec3 a, Vec3 b) {
        return new Vec3(a.x + b.x, a.y + b.y, a.z + b.z);
    }

    private static Vec3 scale(Vec3 a, double s) {
        return new Vec3(a.x * s, a.y * s, a.z * s);
    }
}
