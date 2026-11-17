package com.mineways.mocap;

/**
 * One-Euro 自适应低通滤波（Casiez 2012），动捕轨迹去抖标准件：
 * 静止时重截止频率（强平滑），快速运动时自适应放开（低延迟）。
 * 对每个标量通道独立滤波。
 */
public final class OneEuro {
    private final double minCutoff, beta, dCutoff;
    private boolean started = false;
    private double xPrev, dxPrev, tPrev;

    public OneEuro(double minCutoff, double beta, double dCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.dCutoff = dCutoff;
    }

    /** 默认参数：动捕归一化坐标经验值。 */
    public static OneEuro poseDefault() { return new OneEuro(1.2, 0.015, 1.0); }

    private static double alpha(double cutoff, double dt) {
        double tau = 1.0 / (2 * Math.PI * cutoff);
        return 1.0 / (1.0 + tau / dt);
    }

    public double filter(double tSec, double x) {
        if (!started) {
            started = true;
            xPrev = x; dxPrev = 0; tPrev = tSec;
            return x;
        }
        double dt = Math.max(1e-4, tSec - tPrev);
        tPrev = tSec;
        double dx = (x - xPrev) / dt;
        double aD = alpha(dCutoff, dt);
        double dxHat = aD * dx + (1 - aD) * dxPrev;
        double cutoff = minCutoff + beta * Math.abs(dxHat);
        double a = alpha(cutoff, dt);
        double xHat = a * x + (1 - a) * xPrev;
        xPrev = xHat; dxPrev = dxHat;
        return xHat;
    }
}
