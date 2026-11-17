package com.mineways.mocap;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键帧抽取：Ramer–Douglas–Peucker 变体。
 * 保证：任意相邻关键帧之间按线性插值重建的曲线，与原逐帧曲线
 * 在任一通道上的最大偏差 ≤ tolDeg（L∞ 保真）。
 * 这是“高精度”的量化承诺：tolDeg=0.5 时丢帧不引入超过半度的误差。
 */
public final class KeyframeDecimator {
    private KeyframeDecimator() {}

    public static final class Sample {
        public final double t;      // 秒
        public final double[] e;    // 欧拉（度）
        /**
         * 关键帧前切线值（Bedrock 的 {@code pre}，JSON 数值空间），{@code null} = 不写。
         * 只在「上一个关键帧不是 catmullrom」时 BB 才写 pre，本字段原样保存以便无损回写。
         */
        public double[] pre;
        /** 插值模式：{@code null}（线性，BB 不写字段）/ {@code "catmullrom"}。 */
        public String lerp;

        public Sample(double t, double[] e) { this(t, e, null, null); }

        public Sample(double t, double[] e, double[] pre, String lerp) {
            this.t = t;
            this.e = e;
            this.pre = pre;
            this.lerp = lerp;
        }

        public Sample at(double nt) {
            return new Sample(nt, e.clone(), pre == null ? null : pre.clone(), lerp);
        }

        public Sample copy() {
            return new Sample(t, e.clone(), pre == null ? null : pre.clone(), lerp);
        }
    }

    public static List<Sample> decimate(List<Sample> in, double tolDeg) {
        if (in.size() <= 2) return new ArrayList<Sample>(in);
        boolean[] keep = new boolean[in.size()];
        keep[0] = true;
        keep[in.size() - 1] = true;
        simplify(in, 0, in.size() - 1, tolDeg, keep);
        List<Sample> out = new ArrayList<Sample>();
        for (int i = 0; i < in.size(); i++) if (keep[i]) out.add(in.get(i));
        return out;
    }

    private static void simplify(List<Sample> in, int lo, int hi, double tol, boolean[] keep) {
        if (hi - lo < 2) return;
        Sample a = in.get(lo), b = in.get(hi);
        double span = Math.max(1e-9, b.t - a.t);
        double maxDev = -1;
        int maxIdx = -1;
        for (int i = lo + 1; i < hi; i++) {
            Sample s = in.get(i);
            double t = (s.t - a.t) / span;
            double dev = 0;
            for (int k = 0; k < 3; k++) {
                double want = a.e[k] + (b.e[k] - a.e[k]) * t;
                double d = Math.abs(EulerBedrock.angDiffDeg(s.e[k], want));
                if (d > dev) dev = d;
            }
            if (dev > maxDev) { maxDev = dev; maxIdx = i; }
        }
        if (maxDev > tol) {
            keep[maxIdx] = true;
            simplify(in, lo, maxIdx, tol, keep);
            simplify(in, maxIdx, hi, tol, keep);
        }
    }
}
