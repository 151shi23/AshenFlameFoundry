package com.zeus.landscape;

/** 管线阶段接口。实现必须做到: 幂等安全、输出 clamp 前不产生 NaN。 */
public interface Stage {
    /**
     * 全局增强预算 [0,1]: 输入图质量越高值越小 (好图轻处理)。
     * 各阶段把自己的自适应强度再乘以该系数, 防止对成品图过度处理。
     * 默认不感知; 需要的阶段在 apply 前接收。
     */
    default Stage strength(float global) { return this; }

    /** @return 简要描述本次执行的自适应决策 (写入处理报告) */
    String apply(FloatImage img);
}
