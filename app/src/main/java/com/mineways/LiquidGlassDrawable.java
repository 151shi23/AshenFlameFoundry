package com.mineways;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 液态玻璃底色：玻璃主体（上浅下深）+ 顶亮底暗的边光 + 左上斜向反射 + 柔和外投影。
 * 给搜索框、筛选胶囊这类"小块玻璃"用 —— 它们贴在页面里，身后就是自己的父容器，
 * 做实时模糊没有画面收益，所以走"边光 + 反射 + 投影"这套视觉语，跟底部胶囊保持一致。
 *
 * <p>{@code tint = 0} 是透明玻璃；传颜色则按该色着色（选中态用）。</p>
 */
public class LiquidGlassDrawable extends Drawable {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cast = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radius;
    private final int tint;
    private final float stroke;
    private final float shadowRadius;

    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();

    public LiquidGlassDrawable(float radiusPx, int tintColor, float density) {
        radius = radiusPx;
        tint = tintColor;
        stroke = Math.max(1f, density);
        shadowRadius = Math.max(1f, 10f * density);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);

        cast.setColor(0x3D000000);
        cast.setMaskFilter(new BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) {
            return;
        }
        rect.set(b);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);

        // 1) 外投影（浮起来）
        canvas.save();
        canvas.translate(0, shadowRadius * 0.28f);
        canvas.drawRoundRect(rect, r, r, cast);
        canvas.restore();

        // 2) 玻璃主体：垂直渐变（上浅下深）
        canvas.drawRoundRect(rect, r, r, body);

        // 3) 左上斜向反射
        canvas.drawRoundRect(rect, r, r, sheen);

        // 4) 边光：上亮下暗，往内缩半个笔宽，免得被视图边界裁掉
        rimRect.set(rect);
        rimRect.inset(stroke / 2f, stroke / 2f);
        float rr = Math.max(0f, Math.min(radius, Math.min(rimRect.width(), rimRect.height()) / 2f));
        canvas.drawRoundRect(rimRect, rr, rr, rim);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float w = Math.max(1, bounds.width());
        float h = Math.max(1, bounds.height());

        if (tint == 0) {
            body.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    new int[]{0x38FFFFFF, 0x0BFFFFFF}, null, Shader.TileMode.CLAMP));
        } else {
            body.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    new int[]{withAlpha(lighten(tint), 0.95f), withAlpha(tint, 0.80f)},
                    null, Shader.TileMode.CLAMP));
        }
        rim.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0x73FFFFFF, 0x0FFFFFFF}, null, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(bounds.left, bounds.top,
                bounds.left + w * 0.6f, bounds.top + h * 0.95f,
                new int[]{0x26FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override
    public void setAlpha(int alpha) {
        body.setAlpha(alpha);
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        body.setColorFilter(colorFilter);
        sheen.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (255 * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int lighten(int color) {
        int r = Math.min(255, (int) (Color.red(color) * 1.22f) + 16);
        int g = Math.min(255, (int) (Color.green(color) * 1.22f) + 16);
        int b = Math.min(255, (int) (Color.blue(color) * 1.22f) + 16);
        return Color.rgb(r, g, b);
    }
}
