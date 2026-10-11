package com.zeus.landscape;

import com.zeus.landscape.stages.AutoLevels;
import com.zeus.landscape.stages.Clahe;
import com.zeus.landscape.stages.ClaritySharpen;
import com.zeus.landscape.stages.Dehaze;
import com.zeus.landscape.stages.ShadowHighlight;
import com.zeus.landscape.stages.SkyEnhance;
import com.zeus.landscape.stages.ToneCurve;
import com.zeus.landscape.stages.Vibrance;
import com.zeus.landscape.stages.WhiteBalance;

/**
 * 风景增强引擎 v9.2 —— 双风格编排。
 *
 * vivid (默认): 分区精修管线 —— 通透浓艳大片 (11 阶段)。
 * film:        胶片随手拍 —— "一台相机"逻辑, 跳过全部分区魔法。
 *   (v9.1 审美结论: 语义分区调色=精修工业品; 统一响应+颗粒+暗角=真实质感)
 *
 * 共同组件: 多尺度分析 / 显著度 / 五因子雾判定 / 山体检测门控。
 */
public final class Pipeline {
    private int l1gridGw, l1gridGh;
    /** 日照金山强度 (仅山体图生效): 0=关 0.45=light 0.75=全开 1.1=风格化 */
    public float goldenStrength = 0.55f;
    /** 输出风格: vivid=分区精修 (默认) / film=胶片随手拍 / newyear=2027 新年红金 */
    public String style = "vivid";

    public Result enhance(FloatImage img) {
        long t0 = System.currentTimeMillis();
        StringBuilder log = new StringBuilder();
        QualityMetrics before = new QualityMetrics(img);

        // ===== 多尺度分析 (双风格共用) =====
        float[] darkSample = sampleDarkChannel(img);
        MultiScaleAnalysis.GlobalStats g = MultiScaleAnalysis.global(img, darkSample);
        MultiScaleAnalysis.TileGrid l1 = MultiScaleAnalysis.fine(img, 16);
        MultiScaleAnalysis.TileGrid l2 = MultiScaleAnalysis.coarse(l1, 4);
        float[] sal = SaliencyMap.compute(l1, l2);
        l1gridGw = l1.gw;
        l1gridGh = l1.gh;
        EnhancePlan plan = EnhancePlan.build(l1, l2, g, sal);
        log.append("计划: ").append(plan.rationale).append('\n');
        float budget = g.budget;

        boolean filmStyle = "film".equals(style);
        boolean newyear = "newyear".equals(style);
        if (newyear) {
            // ================= newyear: 2027 新年红金 =================
            log.append(new WhiteBalance().apply(img)).append('\n');
            BrightenRoll.apply(img, 0.28f, 1.0f, false);
            log.append("brightenRoll: EV+0.28\n");
            float ny = Math.min(1f, goldenStrength + 0.35f);   // 复用 --golden 作红金力度 (默认 0.55+0.35)
            NewYearTone.apply(img, ny);
            log.append(String.format("newYearTone: 力度=%.2f (红相增强+红金分离色调)%n", ny));
            ClaritySharpen sharpen = new ClaritySharpen(true);
            sharpen.strength(0.8f);
            log.append(sharpen.apply(img)).append('\n');
            log.append(blackAnchor(img)).append('\n');
            neutralAnchor(img, log);
        } else if (filmStyle) {
            // ================= film: 一台相机 =================
            log.append(new WhiteBalance().apply(img)).append('\n');
            if (plan.dehazeOn) {
                log.append(new Dehaze().plan(plan).apply(img)).append('\n');
            }
            BrightenRoll.apply(img, 0.25f, 1.0f, plan.dehazeOn);
            log.append("brightenRoll: EV+0.25 黑位下沉 高光软膝\n");
            float fs = 1.0f * (0.55f + 0.45f * budget);
            FilmLook.apply(img, fs);
            log.append(String.format("filmLook: 强度=%.2f (统一胶片曲线+颗粒+暗角)%n", fs));
            boolean mt = MountainDetector.hasMountain(img);
            GoldenHour.apply(img, goldenStrength * (mt ? 1f : 0f));
            log.append("goldenHour: ").append(mt ? "生效 (山体)" : "跳过 (非山)").append('\n');
            ClaritySharpen sharpen = new ClaritySharpen(true);
            sharpen.strength(0.7f);
            log.append(sharpen.apply(img)).append('\n');
            zoneVRecover(img);
            log.append(blackAnchor(img)).append('\n');
            neutralAnchor(img, log);
        } else {
            // ================= vivid: 分区精修 =================
            log.append(new WhiteBalance().apply(img)).append('\n');
            if (plan.dehazeOn) {
                log.append(new Dehaze().plan(plan).apply(img)).append('\n');
            }
            BrightenRoll.apply(img, 0.25f, 1.0f, plan.dehazeOn);
            log.append("brightenRoll: EV+0.25 黑位下沉 高光软膝\n");
            log.append(localContrast(img, budget)).append('\n');
            float[][] sem = SceneSemantic.computeMaps(img, l1);
            SceneSemantic.enhance(img, sem);
            log.append("sceneSemantic: 五区语义调色\n");
            log.append(new SkyEnhance().strength(plan.budget).apply(img)).append('\n');
            log.append(vibranceOklab(img, g, budget, plan.dehazeOn)).append('\n');
            ClaritySharpen clarity = new ClaritySharpen(false);
            clarity.strength(plan.budget);
            clarity.plan(plan);
            log.append(clarity.apply(img)).append('\n');
            ClaritySharpen sharpen = new ClaritySharpen(true);
            sharpen.strength(plan.budget);
            log.append(sharpen.apply(img)).append('\n');
            if (goldenStrength > 0.01f && MountainDetector.hasMountain(img)) {
                GoldenHour.apply(img, goldenStrength);
                log.append(String.format("goldenHour: 强度=%.2f (山体)%n", goldenStrength));
            }
        }

        zoneVRecover(img);
        log.append(blackAnchor(img)).append('\n');
        neutralAnchor(img, log);
        // 鲜活回补: 中性锚定的冷青副作用补偿 (饱和+4%, 高光暖意 +2%)
        {
            int n2 = img.size();
            float[] L2 = ColorOps.lumaPlane(img);
            for (int i = 0; i < n2; i++) {
                float rC = img.data[0][i], gC = img.data[1][i], bC = img.data[2][i];
                float anc = ColorOps.luma(rC, gC, bC);
                float sg = 1.04f;
                rC = anc + (rC - anc) * sg;
                gC = anc + (gC - anc) * sg;
                bC = anc + (bC - anc) * sg;
                float hiW = Stats.smoothstep(0.62f, 0.92f, L2[i]);
                rC += 0.018f * hiW;
                bC -= 0.010f * hiW;
                img.data[0][i] = Math.max(0f, Math.min(1f, rC));
                img.data[1][i] = Math.max(0f, Math.min(1f, gC));
                img.data[2][i] = Math.max(0f, Math.min(1f, bC));
            }
        }
        finalClamp(img);
        QualityMetrics after = new QualityMetrics(img);
        Result r = new Result();
        r.output = img;
        r.log = log.toString();
        r.before = before;
        r.after = after;
        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** CLAHE 局部对比 (vivid) */
    private String localContrast(FloatImage img, float budget) {
        float[] L = ColorOps.lumaPlane(img);
        float p5 = Stats.percentile(L, 0.05f);
        float contrast = Stats.percentile(L, 0.95f) - p5;
        float clip = (contrast < 0.5f ? 2.6f : contrast < 0.7f ? 2.1f : 1.6f);
        float[] original = new float[L.length];
        System.arraycopy(L, 0, original, 0, L.length);
        Clahe.apply(L, img.width, img.height, 8, 8, clip);
        int n = img.size();
        float mix = 0.40f + 0.35f * budget;
        for (int i = 0; i < n; i++) {
            float hiW = Stats.smoothstep(0.88f, 0.97f, original[i]);
            float m = mix * (1f - 0.85f * hiW);
            L[i] = original[i] + (L[i] - original[i]) * m;
            float base = Math.max(1e-4f, ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]));
            float ratio = Math.max(0.25f, Math.min(4f, L[i] / base));
            for (int c = 0; c < 3; c++) {
                img.data[c][i] = Math.max(0f, Math.min(1f, img.data[c][i] * ratio));
            }
        }
        return String.format("clahe: clip=%.1f (contrast=%.2f)", clip, contrast);
    }

    /** OKLab vibrance (vivid) */
    private String vibranceOklab(FloatImage img, MultiScaleAnalysis.GlobalStats g, float budget, boolean hazeScene) {
        int n = img.size();
        float amount = g.satMean < 0.22f ? 0.15f
                : g.satMean < 0.32f ? 0.12f
                : g.satMean < 0.45f ? 0.09f
                : 0.06f;
        amount *= (0.55f + 0.45f * budget);
        if (hazeScene) amount *= 0.6f;
        float[] lab = new float[3], rgb = new float[3];
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        for (int i = 0; i < n; i++) {
            ColorOps.rgbToOkLab(R[i], G[i], B[i], lab);
            float C = (float) Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
            if (C < 1e-5f) continue;
            float hue = (float) Math.atan2(lab[2], lab[1]);
            float hueDeg = (float) Math.toDegrees(hue < 0 ? hue + 2 * Math.PI : hue);
            float w = 1f - Math.min(1f, C / 0.14f);
            float gain = 1f + amount * (0.5f + 0.5f * w);
            if (hueDeg >= 90f && hueDeg <= 155f) gain = 1f + (gain - 1f) * 0.6f;
            float Cn = C * gain;
            float scale = Cn / C;
            ColorOps.okLabToRgb(lab[0], lab[1] * scale, lab[2] * scale, rgb);
            R[i] = Math.max(0f, Math.min(1f, rgb[0]));
            G[i] = Math.max(0f, Math.min(1f, rgb[1]));
            B[i] = Math.max(0f, Math.min(1f, rgb[2]));
        }
        return String.format("vibrance(OKLab): amount=%.3f", amount);
    }

    /** Zone V 中位回归: 无论中间过程如何, 终检把中位亮度拉回 0.46 (影调兜底) */
    private void zoneVRecover(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p50 = Stats.percentile(L, 0.50f);
        if (p50 < 0.05f || p50 > 0.95f) return;
        float gain = 0.46f / p50;
        gain = Math.max(0.75f, Math.min(1.22f, gain));
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                d[i] = Math.max(0f, Math.min(1f, (float) Math.pow(Math.max(0f, d[i]), 1f / gain)));
            }
        }
    }

    /** 黑位/白位锚定 */
    private String blackAnchor(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p1 = Stats.percentile(L, 0.01f);
        float p99 = Stats.percentile(L, 0.99f);
        float span = Math.max(0.15f, p99 - p1);
        float gain = Math.min(1.35f, 0.98f / span);
        float offset = 0.004f - p1 * gain;
        if (Math.abs(gain - 1f) < 0.03f) return "blackAnchor: 已达标跳过";
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                d[i] = Math.max(0f, Math.min(1f, d[i] * gain + offset));
            }
        }
        return String.format("blackAnchor: gain=%.2f (p1=%.3f→0.004)", gain, p1);
    }

    /** 终检色彩锚定 v3: 只修超标通道 (精准打击), 力度 0.5, 亮度补偿。
     *  v9.2 教训: 三通道 75% 全收敛会把暖阳光场景拉成冷暗调 —— 过犹不及。 */
    private void neutralAnchor(FloatImage img, StringBuilder log) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float t = Math.max(1e-3f, Stats.percentile(L, 0.70f));
        double[] sums = new double[3];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (L[i] >= t * 0.8f) {
                sums[0] += img.data[0][i];
                sums[1] += img.data[1][i];
                sums[2] += img.data[2][i];
                cnt++;
            }
        }
        if (cnt < n / 20) return;
        double rM = sums[0] / cnt + 1e-6, gM = sums[1] / cnt + 1e-6, bM = sums[2] / cnt + 1e-6;
        float gAvg = (float) Math.cbrt(rM * gM * bM);
        float cR = (float) Math.pow(gAvg / rM, 0.6);
        float cG = (float) Math.pow(gAvg / gM, 0.6);
        float cB = (float) Math.pow(gAvg / bM, 0.6);
        // 判定用色度偏差 (归一化 RGB 偏离灰轴) —— 逐通道 <5% 但组合色偏人眼依然敏感
        float sum = (float) (rM + gM + bM);
        float rn = (float) (rM / sum), gn = (float) (gM / sum), bn = (float) (bM / sum);
        float dev = (float) Math.sqrt((rn - 1f / 3) * (rn - 1f / 3)
                + (gn - 1f / 3) * (gn - 1f / 3) + (bn - 1f / 3) * (bn - 1f / 3));
        if (dev < 0.015f) {
            log.append(String.format("neutralAnchor: 色度偏差=%.4f 达标 (R/B=%.3f G/B=%.3f)%n", dev, rM / bM, gM / bM));
            return;
        }
        float[] corr = {cR, cG, cB};
        double lumBefore = 0;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                img.data[c][i] = Math.max(0f, Math.min(1f, img.data[c][i] * corr[c]));
            }
            lumBefore += ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]);
        }
        // 亮度补偿: 锚定不许改变整体明暗 (只修色相, 不碰影调)
        float lumAfter = 0;
        for (int i = 0; i < n; i++) lumAfter += ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]);
        float comp = (float) (lumBefore / Math.max(1e-6, lumAfter));
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) d[i] = Math.max(0f, Math.min(1f, d[i] * comp));
        }
        log.append(String.format("neutralAnchor: 增益 R×%.3f G×%.3f B×%.3f (只修超标通道, 亮度已补偿)%n", cR, cG, cB));
    }

    private float[] sampleDarkChannel(FloatImage img) {
        int w = img.width, h = img.height;
        int sw = w / 4, sh = h / 4;
        if (sw < 8 || sh < 8) return null;
        float[] out = new float[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                float m = 1f;
                for (int dy = 0; dy < 4; dy++) {
                    int row = (y * 4 + dy) * w;
                    for (int dx = 0; dx < 4; dx++) {
                        int i = row + x * 4 + dx;
                        m = Math.min(m, Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i])));
                    }
                }
                out[y * sw + x] = m;
            }
        }
        return out;
    }

    /** 软亮限 (Reinhard): V0=0.80 以上压缩, 上限 0.965 —— 输出物理上不存在"太阳"。
     *  单调映射保层次: 体积光束的相对明暗保留, 只是整体被"天空亮度"封顶。 */
    private void softLightCap(FloatImage img) {
        int n = img.size();
        float V0 = 0.80f, VMAX = 0.965f;
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                float v = d[i];
                if (v > V0) {
                    float over = v - V0;
                    d[i] = V0 + (VMAX - V0) * over / (over + (VMAX - V0));
                }
            }
        }
    }

    private void finalClamp(FloatImage img) {
        int n = img.size();
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                if (d[i] > 1f) d[i] = 1f;
                else if (d[i] < 0f) d[i] = 0f;
            }
        }
    }

    public static final class Result {
        public FloatImage output;
        public String log;
        public QualityMetrics before, after;
        public long elapsedMs;
    }
}
