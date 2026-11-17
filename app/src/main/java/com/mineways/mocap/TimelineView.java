package com.mineways.mocap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 可视时间轴：关键帧刻度、选区（左右手柄可拖）、播放头（点/拖任意处即可拖时间）。
 *
 * <p>选区就是「保留 / 删除区间」的输入，不再靠手输秒数。
 */
public class TimelineView extends View {

    public interface Listener {
        void onScrub(double t);

        void onRange(double a, double b);
    }

    private double length = 1, playhead = 0, selA = 0, selB = 1;
    private float[] ticks = new float[0];
    private Listener listener;
    private int dragging; // 0=无 1=左手柄 2=右手柄 3=播放头
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    public TimelineView(Context c) {
        super(c);
        setBackgroundColor(0xFF0B0E13);
        text.setColor(0xFF8A919B);
        text.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setData(double length, float[] tickTimes) {
        this.length = Math.max(0.05, length);
        this.ticks = tickTimes == null ? new float[0] : tickTimes;
        this.selA = 0;
        this.selB = this.length;
        this.playhead = 0;
        invalidate();
    }

    public void setPlayhead(double t) {
        playhead = Math.max(0, Math.min(length, t));
        invalidate();
    }

    public void setSelection(double a, double b) {
        selA = Math.max(0, Math.min(length, Math.min(a, b)));
        selB = Math.max(selA, Math.min(length, Math.max(a, b)));
        invalidate();
    }

    public double selectionStart() {
        return selA;
    }

    public double selectionEnd() {
        return selB;
    }

    private float xOf(double t, float w) {
        float pad = 10f;
        return pad + (float) (t / length) * (w - 2 * pad);
    }

    private double tOf(double x, float w) {
        float pad = 10f;
        double k = (x - pad) / Math.max(1f, w - 2 * pad);
        return Math.max(0, Math.min(length, k * length));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        bar.set(10f, 26f, w - 10f, h - 16f);
        p.setColor(0xFF151A21);
        cv.drawRoundRect(bar, 6f, 6f, p);

        // 选区
        p.setColor(0x334FC3F7);
        cv.drawRect(xOf(selA, w), bar.top, xOf(selB, w), bar.bottom, p);

        // 关键帧刻度（多个骨骼同一时刻只画一根）
        p.setStrokeWidth(1f);
        p.setColor(0x554FC3F7);
        for (float t : ticks) {
            float x = xOf(t, w);
            cv.drawLine(x, bar.top + 2f, x, bar.bottom - 2f, p);
        }

        // 播放头
        p.setColor(0xFFFFC24B);
        p.setStrokeWidth(2f);
        float ph = xOf(playhead, w);
        cv.drawLine(ph, bar.top - 6f, ph, bar.bottom + 4f, p);

        // 手柄
        p.setColor(0xFF4FC3F7);
        cv.drawCircle(xOf(selA, w), bar.top - 8f, 6f, p);
        cv.drawCircle(xOf(selB, w), bar.top - 8f, 6f, p);

        cv.drawText("0.00s", 10f, h - 3f, text);
        String end = String.format(Locale.US, "%.2fs", length);
        cv.drawText(end, w - 10f - text.measureText(end), h - 3f, text);
        String sel = String.format(Locale.US, "选区 %.2f ~ %.2f s", selA, selB);
        cv.drawText(sel, (w - text.measureText(sel)) / 2f, h - 3f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 时间轴的横向拖动不能被外层 ScrollView 抢走
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float ax = xOf(selA, w), bx = xOf(selB, w);
                if (Math.abs(e.getX() - ax) < 26f) {
                    dragging = 1;
                } else if (Math.abs(e.getX() - bx) < 26f) {
                    dragging = 2;
                } else {
                    dragging = 3;
                    seek(e.getX(), w);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == 3) {
                    seek(e.getX(), w);
                } else if (dragging == 1) {
                    selA = Math.min(tOf(e.getX(), w), selB);
                    fireRange();
                } else if (dragging == 2) {
                    selB = Math.max(tOf(e.getX(), w), selA);
                    fireRange();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = 0;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void seek(float x, float w) {
        playhead = tOf(x, w);
        if (listener != null) listener.onScrub(playhead);
        invalidate();
    }

    private void fireRange() {
        if (listener != null) listener.onRange(selA, selB);
    }
}
