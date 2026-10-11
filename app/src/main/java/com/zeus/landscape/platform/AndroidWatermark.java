package com.zeus.landscape.platform;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 相机水印（安卓侧实现）—— 把 {@code com.zeus.landscape.Watermark} 那套桌面排版搬到
 * {@code android.graphics.Canvas} 上。
 *
 * <p>上游那份用的是 {@code java.awt}（Graphics2D / TextLayout / RoundRectangle2D），安卓没有，
 * 所以这不是"复制粘贴"而是"按同一份排版规格重写"。所有尺寸比例都照抄上游，改的只有绘制后端：</p>
 * <ul>
 *   <li>铁律：<b>固定右下角</b>（上游注释原话：真实手机水印从不换位，"合适"由拍摄者构图决定）；</li>
 *   <li>字号相对图宽：品牌行 {@code w*0.0195}、型号行 {@code w*0.0150}、参数行 {@code w*0.0128}，行距 1.32×；</li>
 *   <li>质感来源是排版不是特效：无衬底条，只有 1.3px、α95 的淡阴影；</li>
 *   <li>LEICA 红条 {@code #E20613} / 新年印章中国红 {@code #C8102E} + 金字 {@code #FFD700}。</li>
 * </ul>
 *
 * <p>唯一有意的差别：上游把 DejaVu 字体打包进桌面运行，这里直接用系统字体 —— 安卓自带中文，
 * 不必为水印再塞一份字库。</p>
 */
public final class AndroidWatermark {

    private AndroidWatermark() {
    }

    public enum Style {
        NONE("不加水印"),
        DJI("大疆 DJI"),
        XIAOMI_LEICA("小米徕卡"),
        ZEISS("vivo 蔡司"),
        NEWYEAR("新年红金印章");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    /** 水印文字；为空的字段用该风格的默认值。 */
    public static final class Config {
        public String model;
        public String lens;
        public String aperture;
        public String shutter;
        public String iso;
        public String year;
        public String greeting;

        /** 参数行：非空片段以两个空格连接，全空则用默认串。 */
        public String paramLine(String def) {
            StringBuilder sb = new StringBuilder();
            for (String seg : new String[]{lens, aperture, shutter, iso}) {
                if (seg != null && seg.trim().length() > 0) {
                    if (sb.length() > 0) {
                        sb.append("  ");
                    }
                    sb.append(seg.trim());
                }
            }
            return sb.length() > 0 ? sb.toString() : def;
        }

        public static Config of(String model, String lens, String aperture, String shutter, String iso) {
            Config c = new Config();
            c.model = model;
            c.lens = lens;
            c.aperture = aperture;
            c.shutter = shutter;
            c.iso = iso;
            return c;
        }
    }

    /** 各风格默认文案（照抄上游）。 */
    public static String defaultModel(Style style) {
        switch (style) {
            case DJI:
                return "DJI Osmo Pocket 3";
            case XIAOMI_LEICA:
                return "Xiaomi 14 Ultra";
            case ZEISS:
                return "vivo X100s";
            default:
                return "";
        }
    }

    public static String defaultParams(Style style) {
        switch (style) {
            case XIAOMI_LEICA:
                return "23mm  1:1.63  1/120s  ISO100";
            case ZEISS:
                return "T*  24mm  f/1.75  1/250s  ISO64";
            default:
                return "24mm  f/1.7  1/500s  ISO100";
        }
    }

    /**
     * 在位图右下角画水印（原地绘制，位图必须可变）。
     *
     * <p>上游是在 FloatImage→BufferedImage 上画完再拆回 float；这里直接在编码后的位图上画，
     * 少一次整图往返，文字抗锯齿也交给系统，比手搓 float 混合更干净。版式数值全在
     * {@link WatermarkSpec} 里（那份是纯数值，可以在桌面上原样复算目检）。</p>
     */
    public static void apply(Bitmap bitmap, Style style, Config cfg) {
        if (bitmap == null || style == null || style == Style.NONE) {
            return;
        }
        Config c = cfg == null ? new Config() : cfg;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        WatermarkSpec s = WatermarkSpec.of(w, h);
        float brandH = s.brandH;
        float modelH = s.modelH;
        float paramH = s.paramH;
        float right = s.right;
        float yBrand = s.yBrand;
        float yModel = s.yModel;
        float yParam = s.yParam;

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(Color.WHITE);

        String model = c.model != null && c.model.trim().length() > 0 ? c.model.trim() : defaultModel(style);
        String params = c.paramLine(defaultParams(style));

        switch (style) {
            case XIAOMI_LEICA: {
                float bh = brandH * 0.72f;
                float bw = brandH * 2.9f;
                float by = yBrand - bh * 0.85f;
                float bx = right - bw;
                paint.setColor(Color.rgb(0xE2, 0x06, 0x13));
                canvas.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), bh * 0.16f, bh * 0.16f, paint);
                paint.setTextSize(bh * 0.62f);
                paint.setColor(Color.WHITE);
                String brand = "LEICA";
                float lw = paint.measureText(brand);
                canvas.drawText(brand, bx + (bw - lw) / 2f, by + bh * 0.74f, paint);
                drawLine(canvas, paint, model, modelH, right, yModel);
                drawLine(canvas, paint, params, paramH, right, yParam);
                break;
            }
            case ZEISS: {
                drawLine(canvas, paint, "ZEISS", brandH, right, yBrand);
                drawLine(canvas, paint, model, modelH, right, yModel);
                drawLine(canvas, paint, params, paramH, right, yParam);
                break;
            }
            case DJI: {
                drawLine(canvas, paint, "DJI", brandH, right, yBrand);
                drawLine(canvas, paint, model, modelH, right, yModel);
                drawLine(canvas, paint, params, paramH, right, yParam);
                break;
            }
            case NEWYEAR: {
                float bh = brandH * 0.78f;
                float bw = brandH * 1.55f;
                float by = yBrand - bh * 0.92f;
                float bx = right - bw;
                paint.setColor(Color.rgb(0xC8, 0x10, 0x2E));
                canvas.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), bh * 0.12f, bh * 0.12f, paint);
                String year = c.year != null && c.year.trim().length() > 0 ? c.year.trim() : "2027";
                paint.setTextSize(bh * 0.58f);
                paint.setColor(Color.rgb(0xFF, 0xD7, 0x00));
                float yw = paint.measureText(year);
                canvas.drawText(year, bx + (bw - yw) / 2f, by + bh * 0.76f, paint);
                String greet = c.greeting != null && c.greeting.trim().length() > 0
                        ? c.greeting.trim() : "恭贺新禧 · 新年快乐";
                paint.setTextSize(modelH * 1.05f);
                float gw = paint.measureText(greet);
                float gx = right - gw;
                paint.setColor(Color.argb(110, 0, 0, 0));
                canvas.drawText(greet, gx + 1.2f, yModel + 1.2f, paint);
                paint.setColor(Color.rgb(0xFF, 0xD7, 0x00));
                canvas.drawText(greet, gx, yModel, paint);
                drawLine(canvas, paint, params, paramH, right, yParam);
                break;
            }
            default:
                break;
        }
    }

    /** 单行：白字 + 1.3px 淡阴影，右对齐到 anchorX。 */
    private static void drawLine(Canvas canvas, Paint paint, String text, float size,
                                 float anchorX, float baselineY) {
        paint.setTextSize(size);
        float tw = paint.measureText(text);
        float dx = anchorX - tw;
        paint.setColor(Color.argb(95, 0, 0, 0));
        canvas.drawText(text, dx + 1.3f, baselineY + 1.3f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(text, dx, baselineY, paint);
    }

    /** 界面上给一行预告：到底会往图上写什么字。 */
    public static String previewText(Style style, Config cfg) {
        if (style == null || style == Style.NONE) {
            return "";
        }
        Config c = cfg == null ? new Config() : cfg;
        String model = c.model != null && c.model.trim().length() > 0 ? c.model.trim() : defaultModel(style);
        if (style == Style.NEWYEAR) {
            String year = c.year != null && c.year.trim().length() > 0 ? c.year.trim() : "2027";
            String greet = c.greeting != null && c.greeting.trim().length() > 0
                    ? c.greeting.trim() : "恭贺新禧 · 新年快乐";
            return year + " · " + greet + " · " + c.paramLine(defaultParams(style));
        }
        String brand = style == Style.DJI ? "DJI" : style == Style.ZEISS ? "ZEISS" : "LEICA";
        return brand + " / " + model + " / " + c.paramLine(defaultParams(style));
    }
}
