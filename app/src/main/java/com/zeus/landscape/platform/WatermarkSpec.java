package com.zeus.landscape.platform;

/**
 * 相机水印的排版规格 —— 纯数值，不含任何安卓类型，所以能在桌面 JDK 上原样复算并目检。
 *
 * <p>数字全部照抄上游 {@code com.zeus.landscape.Watermark}（安卓没有 {@code java.awt}，绘制后端换了，
 * 版式不许换）：固定右下角；字号相对图宽 —— 品牌行 {@code w*0.0195}、型号行 {@code w*0.0150}、
 * 参数行 {@code w*0.0128}；行距 1.32×；边距 x={@code 4.0%w}、y={@code 5.5%h}。</p>
 *
 * <p>三个 y 都是<b>基线</b>（baseline），与 AWT 的 {@code TextLayout.draw} 和安卓的
 * {@code Canvas.drawText} 语义一致，所以两端复算出来的位置可以直接对。</p>
 */
public final class WatermarkSpec {

    public final int imageW, imageH;
    public final float marginX, marginY;
    public final float brandH, modelH, paramH, lineGap;
    /** 右对齐锚点（= imageW - marginX）。 */
    public final float right;
    public final float yBrand, yModel, yParam;

    public WatermarkSpec(int w, int h) {
        imageW = w;
        imageH = h;
        marginX = w * 0.040f;
        marginY = h * 0.055f;
        brandH = w * 0.0195f;
        modelH = w * 0.0150f;
        paramH = w * 0.0128f;
        lineGap = 1.32f;
        right = w - marginX;
        yBrand = h - marginY - (brandH * 2.1f + modelH * 1.3f + paramH * 1.2f);
        yModel = yBrand + brandH * lineGap;
        yParam = yModel + modelH * lineGap;
    }

    public static WatermarkSpec of(int w, int h) {
        return new WatermarkSpec(w, h);
    }
}
