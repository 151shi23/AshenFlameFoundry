package com.mineways.mocap;

/**
 * Blockbench / Bedrock 骨骼欧拉（度）→ 旋转矩阵 —— 真机标定锁定版。
 *
 * 证据链（2026-11-10，web.blockbench.net Blockbench 5.2.2，tools/calibration.json）：
 *  1. bundle.js 源码：骨骼 THREE 节点创建时 rotation.order = Format.euler_order，
 *     bedrock 格式该值为 "ZYX"（动画播放 displayRotation 直接对 rotation.x/y/z 累加度数弧值）；
 *  2. 浏览器内矩阵等式验证：THREE Euler 'ZYX' ≡ R = Rz·Ry·Rx（对向量先施加 X）；
 *  3. 27 组欧拉组合真机矩阵对账：R=Rz·Ry·Rx max|Δ|=4.9e-08（float32 舍入级），
 *     其余候选序（Rx·Ry·Rz / Rz·Rx·Ry）偏差 0.35~0.68，唯一匹配。
 *
 * 即：bedrock rotation [x°,y°,z°] 的世界方向 = Rz(z)·Ry(y)·Rx(x)·v_local。
 * pivot 语义：world = pivot + R·(v_abs - pivot)（骨骼绕 origin 旋转）。
 */
public final class EulerBedrock {

    private EulerBedrock() {}

    /** 行主序 3x3 矩阵乘法：c = a * b。 */
    public static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c2 = 0; c2 < 3; c2++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += a[r * 3 + k] * b[k * 3 + c2];
                c[r * 3 + c2] = s;
            }
        return c;
    }

    public static Vec3 apply(double[] m, Vec3 v) {
        return new Vec3(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z);
    }

    public static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0,  0, c, -s,  0, s, c};
    }
    public static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s,  0, 1, 0,  -s, 0, c};
    }
    public static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0,  s, c, 0,  0, 0, 1};
    }

    /** 度 → 矩阵（行主序）：R = Rz·Ry·Rx（BB 5.2.2 真机标定锁定）。 */
    public static double[] toMatrix(double xDeg, double yDeg, double zDeg) {
        double x = Math.toRadians(xDeg), y = Math.toRadians(yDeg), z = Math.toRadians(zDeg);
        return mul(rotZ(z), mul(rotY(y), rotX(x)));
    }

    /** 通道间角度差（度，wrap 到 [-180,180]）。 */
    public static double angDiffDeg(double a, double b) {
        double d = (a - b) % 360.0;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
