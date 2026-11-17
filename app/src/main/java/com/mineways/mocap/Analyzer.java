package com.mineways.mocap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 动捕分析器：
 *  1) 每骨运动统计（角速度 p95/峰值、活动范围）；
 *  2) 分段能量表（arms/legs/head 三组角速度积分，5 帧滑动平均抑制高频解算噪声）
 *     → 标注"静止段"候选（阈值 = 最大段能量的 25%）；
 *  3) 解算性能基准（ms/帧，实时能力证据）。
 * 输出 Markdown 报告段落，供用户决定删哪些段（人工确认是最终裁决）。
 */
public final class Analyzer {
    private Analyzer() {}

    public static final class Seg {
        public double t0, t1;
        public double armsE, legsE, headE;
        public boolean quiet;
    }

    private static final String[][] GROUPS = {
        {"leftArm", "rightArm"}, {"leftLeg", "rightLeg"}, {"head"}};

    public static String report(List<PoseSolver.Frame> frames, long solveNanos) {
        StringBuilder b = new StringBuilder();
        b.append("## 逐骨运动统计\n\n");
        b.append("| 骨骼 | 活动范围(°) | 角速度 p95(°/s) | 峰值(°/s) |\n|---|---|---|---|\n");
        for (String bone : new String[]{"head", "leftArm", "rightArm", "leftLeg", "rightLeg"}) {
            Stats st = boneStats(frames, bone);
            b.append(String.format(Locale.US, "| %s | %.1f | %.1f | %.1f |\n",
                bone, st.range, st.p95, st.max));
        }

        double msPerFrame = frames.isEmpty() ? 0 : solveNanos / 1e6 / frames.size();
        b.append(String.format(Locale.US, "%n## 解算性能%n%n- %.3f ms/帧（30fps 实时预算 33.3ms，余量 %.0f 倍）%n",
            msPerFrame, 33.3 / Math.max(1e-9, msPerFrame)));

        List<Seg> segs = segments(frames, 0.5);
        double maxE = 1e-9;
        for (Seg s : segs) maxE = Math.max(maxE, Math.max(s.armsE, Math.max(s.legsE, s.headE)));
        b.append("\n## 分段活动范围表（0.5s/段，max-min°）\n\n");
        b.append("判定 = arms 与 legs 双主组均 < 峰值段 40%（AND）；头颈组因远距拍摄时耳距小、深度噪声大，本征噪声底高，仅展示不参与判定（近距拍摄可改善）。\n\n");
        b.append("| 段 | arms | legs | head | 判定 |\n|---|---|---|---|---|\n");
        int quietN = 0;
        for (Seg s : segs) {
            s.quiet = s.armsE < maxE * 0.40 && s.legsE < maxE * 0.40;
            if (s.quiet) quietN++;
            b.append(String.format(Locale.US, "| %.1f-%.1fs | %.1f | %.1f | %.1f | %s |\n",
                s.t0, s.t1, s.armsE, s.legsE, s.headE, s.quiet ? "**静止候选**" : "有效动作"));
        }
        b.append(String.format(Locale.US,
            "%n静默段候选 %d 个 —— 以上仅为候选标注，删哪些段由用户在剪辑器中手动确认（`trim` 命令 / 交互式 `edit`）。%n",
            quietN));
        return b.toString();
    }

    static final class Stats {
        double range, p95, max;
    }

    static Stats boneStats(List<PoseSolver.Frame> frames, String bone) {
        Stats st = new Stats();
        List<Double> vel = new ArrayList<Double>();
        double mn = 1e9, mx = -1e9;
        double[] prev = null;
        double prevT = 0;
        for (PoseSolver.Frame f : frames) {
            double[] e = f.rotDeg.get(bone);
            if (e == null) continue;
            mn = Math.min(mn, Math.min(e[0], Math.min(e[1], e[2])));
            mx = Math.max(mx, Math.max(e[0], Math.max(e[1], e[2])));
            if (prev != null) {
                double dt = Math.max(1e-4, (f.tMs - prevT) / 1000.0);
                double v = 0;
                for (int k = 0; k < 3; k++)
                    v = Math.max(v, Math.abs(EulerBedrock.angDiffDeg(e[k], prev[k])) / dt);
                vel.add(v);
            }
            prev = e;
            prevT = f.tMs;
        }
        st.range = mx - mn;
        vel.sort(Double::compare);
        if (!vel.isEmpty()) {
            st.p95 = vel.get((int) Math.min(vel.size() - 1, Math.floor(vel.size() * 0.95)));
            st.max = vel.get(vel.size() - 1);
        }
        return st;
    }

    /** 分段活动范围：每组独立统计段内角度 range（max-min，对高频抖动天然免疫，语义=该段有没有大幅动作）。 */
    public static List<Seg> segments(List<PoseSolver.Frame> frames, double segSec) {
        List<Seg> out = new ArrayList<Seg>();
        if (frames.isEmpty()) return out;
        int n = frames.size();
        double dur = frames.get(n - 1).tMs / 1000.0;
        for (double t0 = 0; t0 < dur - 1e-9; t0 += segSec) {
            Seg s = new Seg();
            s.t0 = t0;
            s.t1 = Math.min(dur, t0 + segSec);
            for (int gi = 0; gi < GROUPS.length; gi++) {
                double range = 0;
                for (String bone : GROUPS[gi]) {
                    double mn = 1e9, mx = -1e9;
                    boolean has = false;
                    for (PoseSolver.Frame f : frames) {
                        double t = f.tMs / 1000.0;
                        if (t < s.t0 - 1e-9 || t > s.t1 + 1e-9) continue;
                        double[] e = f.rotDeg.get(bone);
                        if (e == null) continue;
                        has = true;
                        for (int q = 0; q < 3; q++) {
                            mn = Math.min(mn, e[q]);
                            mx = Math.max(mx, e[q]);
                        }
                    }
                    if (has) range = Math.max(range, mx - mn);
                }
                if (gi == 0) s.armsE = range;
                else if (gi == 1) s.legsE = range;
                else s.headE = range;
            }
            out.add(s);
        }
        return out;
    }
}
