package com.mineways.mocap;

import java.util.Arrays;

/**
 * 数值欧拉拟合器：给定若干“骨骼静止方向 → 目标方向”约束，
 * 用阻尼高斯-牛顿最小二乘求 ZXY 欧拉角（度）。
 *
 * 例：手臂骨静止时几何朝下 (0,-1,0)，动捕测得手臂方向 d，
 *     拟合 R(e)·(0,-1,0) ≈ d 即得该骨骼的 bedrock rotation。
 * 多方向约束（头/躯干用 up+side+fwd 三方向）天然消除多解。
 */
public final class EulerFit {
    private EulerFit() {}

    public static final class Problem {
        final Vec3[] restDirs;
        final Vec3[] targets;
        public Problem(Vec3[] restDirs, Vec3[] targets) {
            if (restDirs.length != targets.length) throw new IllegalArgumentException();
            this.restDirs = restDirs;
            this.targets = targets;
        }
    }

    private static double[] residual(Problem p, double[] e) {
        double[] R = EulerBedrock.toMatrix(e[0], e[1], e[2]);
        double[] r = new double[p.restDirs.length * 3];
        for (int i = 0; i < p.restDirs.length; i++) {
            Vec3 got = EulerBedrock.apply(R, p.restDirs[i]);
            Vec3 tgt = p.targets[i];
            r[i * 3] = got.x - tgt.x;
            r[i * 3 + 1] = got.y - tgt.y;
            r[i * 3 + 2] = got.z - tgt.z;
        }
        return r;
    }

    /** 求解。init: 初始欧拉估计（度，可全 0），返回精化欧拉（度）。 */
    public static double[] solve(Problem p, double[] init) {
        return solveMask(p, init, new boolean[]{true, true, true});
    }

    /** 带轴锁求解：free[k]=false 的轴固定不动（用于近奇异时锁掉不可观测的扭转）。 */
    public static double[] solveMask(Problem p, double[] init, boolean[] free) {
        double[] e = Arrays.copyOf(init, 3);
        double lambda = 1e-3;
        double[] r = residual(p, e);
        double cost = cost(r);
        final double h = 0.01; // 数值雅可比步长（度）
        for (int iter = 0; iter < 60; iter++) {
            if (cost < 1e-14) break;
            double[][] J = new double[r.length][3];
            for (int k = 0; k < 3; k++) {
                if (!free[k]) { for (int i = 0; i < r.length; i++) J[i][k] = 0; continue; }
                double[] ep = Arrays.copyOf(e, 3); ep[k] += h;
                double[] em = Arrays.copyOf(e, 3); em[k] -= h;
                double[] rp = residual(p, ep);
                double[] rm = residual(p, em);
                for (int i = 0; i < r.length; i++)
                    J[i][k] = (rp[i] - rm[i]) / (2 * h);
            }
            // (J^T J + λI) δ = -J^T r
            double[][] jtj = new double[3][3];
            double[] jtr = new double[3];
            for (int i = 0; i < r.length; i++) {
                for (int a = 0; a < 3; a++) {
                    jtr[a] += J[i][a] * r[i];
                    for (int b = 0; b < 3; b++) jtj[a][b] += J[i][a] * J[i][b];
                }
            }
            for (int a = 0; a < 3; a++) jtj[a][a] += lambda;
            double[] delta = solve3(jtj, scale(jtr, -1));
            if (delta == null) { lambda *= 10; continue; }
            double[] e2 = {e[0] + delta[0], e[1] + delta[1], e[2] + delta[2]};
            double[] r2 = residual(p, e2);
            double cost2 = cost(r2);
            if (cost2 < cost) {
                double step = Math.abs(delta[0]) + Math.abs(delta[1]) + Math.abs(delta[2]);
                e = e2; cost = cost2; r = r2;
                lambda = Math.max(1e-6, lambda * 0.5);
                if (step < 1e-7) break;
            } else {
                lambda *= 5;
                if (lambda > 1e6) break;
            }
        }
        // 规范化到 [-180,180]
        for (int k = 0; k < 3; k++) {
            e[k] = e[k] % 360.0;
            if (e[k] > 180) e[k] -= 360;
            if (e[k] < -180) e[k] += 360;
        }
        return e;
    }

    private static double cost(double[] r) {
        double s = 0;
        for (double v : r) s += v * v;
        return s;
    }

    private static double[] scale(double[] v, double s) {
        double[] o = new double[v.length];
        for (int i = 0; i < v.length; i++) o[i] = v[i] * s;
        return o;
    }

    /** 3x3 高斯消元（部分主元）。 */
    static double[] solve3(double[][] A, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(A[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-15) return null;
            double[] t = m[col]; m[col] = m[piv]; m[piv] = t;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int c = col; c < 4; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] x = new double[3];
        for (int i = 0; i < 3; i++) x[i] = m[i][3] / m[i][i];
        return x;
    }
}
