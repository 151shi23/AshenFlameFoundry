package com.mineways.mocap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剪辑器：用户手动删除多余动作段。
 *
 * 输入 = 保留区间列表（秒），语义 = 删除区间外所有关键帧 + 平移时间轴拼回。
 * 精度红线：**纯删键帧 + 平移，零重采样** —— 保留键的值与相邻键间线性插值形状
 * 完全不变（时间平移是刚体变换），RDP 0.5° 保真承诺原样传递到成品。
 */
public final class TrimEditor {
    private TrimEditor() {}

    public static final class Plan {
        public double[] newLen = {0};
        public int dropped = 0, kept = 0;
    }

    /** 解析 "1.1-3.3, 4.0-5.5" 形式区间串；自动排序+合并重叠。 */
    public static double[][] parseRanges(String spec, double totalLen) {
        List<double[]> rs = new ArrayList<double[]>();
        for (String part : spec.split(",")) {
            String[] ab = part.trim().split("-");
            double a = Double.parseDouble(ab[0].trim());
            double b = ab.length > 1 ? Double.parseDouble(ab[1].trim()) : Math.min(a + 1e-9, totalLen);
            a = Math.max(0, Math.min(a, totalLen));
            b = Math.max(0, Math.min(b, totalLen));
            if (b > a + 1e-9) rs.add(new double[]{a, b});
        }
        rs.sort((x, y) -> Double.compare(x[0], y[0]));
        List<double[]> merged = new ArrayList<double[]>();
        for (double[] r : rs) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1] + 1e-9) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else merged.add(r);
        }
        return merged.toArray(new double[0][]);
    }

    /** 区间 → 新时间映射函数（闭区间拼回）。 */
    public static double remap(double t, double[][] ranges) {
        double acc = 0;
        for (double[] r : ranges) {
            if (t < r[0]) return acc; // 删除区
            if (t <= r[1]) return acc + (t - r[0]);
            acc += r[1] - r[0];
        }
        return acc;
    }

    public static double newLength(double[][] ranges) {
        double s = 0;
        for (double[] r : ranges) s += r[1] - r[0];
        return s;
    }

    /** 对动画关键帧执行保留区间剪辑。返回剪辑后的逐骨键帧。 */
    public static Map<String, List<KeyframeDecimator.Sample>> trim(
            Map<String, List<KeyframeDecimator.Sample>> bones, double[][] ranges, Plan plan) {
        Map<String, List<KeyframeDecimator.Sample>> out = new LinkedHashMap<String, List<KeyframeDecimator.Sample>>();
        double eps = 1e-9;
        for (Map.Entry<String, List<KeyframeDecimator.Sample>> e : bones.entrySet()) {
            List<KeyframeDecimator.Sample> src = e.getValue();
            List<KeyframeDecimator.Sample> dst = new ArrayList<KeyframeDecimator.Sample>();
            for (KeyframeDecimator.Sample s : src) {
                boolean keep = false;
                for (double[] r : ranges) if (s.t >= r[0] - eps && s.t <= r[1] + eps) { keep = true; break; }
                if (keep) {
                    dst.add(new KeyframeDecimator.Sample(remap(s.t, ranges),
                        new double[]{s.e[0], s.e[1], s.e[2]}));
                    plan.kept++;
                } else plan.dropped++;
            }
            if (!dst.isEmpty()) out.put(e.getKey(), dst);
        }
        plan.newLen[0] = newLength(ranges);
        return out;
    }
}
