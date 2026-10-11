package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.DetailDenoise;
import com.zeus.landscape.EnhancePlan;
import com.zeus.landscape.Filters;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.GuidedFilter;
import com.zeus.landscape.Stage;

/**
 * 阶段 9: 通透度 (Clarity) + 阶段 10: 锐化 (Sharpen)。
 *
 * Clarity: 大半径中频对比。base = 引导滤波(guide=L, r=w/30, eps=1e-3),
 * detail = L - base, L' = L + amount·detail。
 * 用引导滤波而非裸 USM: detail 层贴着边缘走, 无 halo (DXO Clarity 同原理)。
 *
 * Sharpen: 小半径 USM (r=1, 低量), 补偿去雾/缩放的细节软化。
 * 两者都在亮度域操作, 色度不受影响。
 */
public final class ClaritySharpen implements Stage {
    private final boolean sharpenOnly;
    private float global = 1f;
    private EnhancePlan plan;

    @Override
    public Stage strength(float g) { this.global = g; return this; }

    public ClaritySharpen plan(EnhancePlan p) { this.plan = p; return this; }

    public ClaritySharpen(boolean sharpenOnly) { this.sharpenOnly = sharpenOnly; }

    @Override
    public String apply(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        StringBuilder sb = new StringBuilder();

        if (!sharpenOnly) {
            // Clarity: 大半径
            int r = Math.max(15, Math.min(w, h) / 30);
            GuidedFilter gf = new GuidedFilter(L, w, h, r, 1e-3f);
            float[] base = gf.filter(L);
            float[] detailDn = new float[n];
            for (int i = 0; i < n; i++) detailDn[i] = L[i] - base[i];
            // v3 新增: 保细节去噪 (软阈值收缩) —— 清晰度增强作用在去噪后的 detail 上,
            // 纹理增强而噪声不放大 (提亮链路积累的噪声在此终结)
            DetailDenoise.shrink(detailDn, 0.6f);
            float amount = 0.40f;
            float invW = plan != null ? (float) plan.gw / w : 0f;
            float invH = plan != null ? (float) plan.gh / h : 0f;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float detail = detailDn[i];
                    float aPx = amount;
                    if (plan != null) {
                        aPx *= Math.max(0.2f, Math.min(1.15f,
                                plan.sample(plan.clarity, w, h, x + 0.5f, y + 0.5f)));
                    }
                    float nl = Math.max(0f, Math.min(1f, L[i] + aPx * detail));
                    float base2 = Math.max(1e-4f, L[i]);
                    float ratio = nl / base2;
                    img.data[0][i] *= ratio;
                    img.data[1][i] *= ratio;
                    img.data[2][i] *= ratio;
                }
            }
            sb.append("clarity(").append(r).append("px, ").append(amount).append(plan != null ? ", 空间自适应" : "").append(") ");
        }

        if (sharpenOnly) {
            // Sharpen: 小半径 USM on L (只在此模式执行, 修复 v1 双重锐化 bug)
            float[] L2 = ColorOps.lumaPlane(img);
            float[] blur = Filters.gaussianBlur(L2, w, h, 1.2f);
            float amt = 0.35f * global;
            for (int i = 0; i < n; i++) {
                float detail = L2[i] - blur[i];
                float nl = Math.max(0f, Math.min(1f, L2[i] + amt * detail));
                float base2 = Math.max(1e-4f, L2[i]);
                float ratio = nl / base2;
                img.data[0][i] *= ratio;
                img.data[1][i] *= ratio;
                img.data[2][i] *= ratio;
            }
            sb.append("sharpen(1.2px, ").append(amt).append(")");
        }
        return sb.toString();
    }
}
