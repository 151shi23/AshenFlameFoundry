package com.mineways.mocap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** 动画某一帧的二维火柴人预览；拖动发光的骨骼即可改姿态（由宿主写回关键帧）。 */
public class AnimPreviewView extends View {

    public interface BoneDragListener {
        /** dx,dy 单位＝模型像素（x 右、y 上）。 */
        void onBoneDrag(String bone, float dx, float dy);
    }

    private AnimPose.Snapshot snap;
    private BoneDragListener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float scale = 1f, cx = 0f, cy = 0f;
    private String dragBone;
    private float lastX, lastY;

    public AnimPreviewView(Context c) {
        super(c);
        setBackgroundColor(0xFF0B0E13);
        text.setColor(0xFF6B7480);
        text.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setSnapshot(AnimPose.Snapshot s) {
        this.snap = s;
        invalidate();
    }

    public void setBoneDragListener(BoneDragListener l) {
        listener = l;
    }

    private void updateTransform() {
        float w = getWidth(), h = getHeight();
        scale = Math.min(w / 30f, h / 42f) * 0.92f;
        cx = w / 2f;
        cy = h / 2f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        updateTransform();
        float w = getWidth(), h = getHeight();
        float groundY = cy + 17f * scale;
        p.setColor(0xFF1B2028);
        p.setStrokeWidth(1.5f);
        cv.drawLine(0, groundY, w, groundY, p);

        if (snap == null) {
            cv.drawText("无动画", cx - 18f, cy, text);
            return;
        }
        for (AnimPose.Seg s : snap.segs) {
            p.setColor(colorOf(s.kind));
            p.setStrokeWidth(strokeOf(s.kind) * Math.max(0.6f, scale / 4f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float x1 = sx(s.x1), y1 = sy(s.y1), x2 = sx(s.x2), y2 = sy(s.y2);
            cv.drawLine(x1, y1, x2, y2, p);
            if (s.kind == AnimPose.HEAD_LINE) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(x2, y2, 4f * scale, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }
    }

    private float sx(float x) {
        return cx + x * scale;
    }

    private float sy(float y) {
        return cy + (y + 17f) * scale;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragBone = boneAt(e.getX(), e.getY());
                if (dragBone == null) {
                    return false;   // 没点到骨头 → 交给外层滚动
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = e.getX();
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragBone == null || listener == null) return false;
                float dx = (e.getX() - lastX) / Math.max(0.5f, scale);
                float dy = -(e.getY() - lastY) / Math.max(0.5f, scale);
                lastX = e.getX();
                lastY = e.getY();
                if (Math.abs(dx) + Math.abs(dy) > 0.01f) {
                    listener.onBoneDrag(dragBone, dx, dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragBone = null;
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** 找离手指最近、且在 32dp 以内的骨段。 */
    private String boneAt(float x, float y) {
        if (snap == null) return null;
        float best = 32f * getResources().getDisplayMetrics().density;
        String bestBone = null;
        for (AnimPose.Seg s : snap.segs) {
            if (s.bone == null) continue;
            float d = distToSeg(x, y, sx(s.x1), sy(s.y1), sx(s.x2), sy(s.y2));
            if (d < best) {
                best = d;
                bestBone = s.bone;
            }
        }
        return bestBone;
    }

    private static float distToSeg(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        float t = len2 < 1e-6f ? 0 : ((px - x1) * dx + (py - y1) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        float qx = x1 + t * dx, qy = y1 + t * dy;
        return (float) Math.hypot(px - qx, py - qy);
    }

    private static int colorOf(int kind) {
        switch (kind) {
            case AnimPose.TORSO: return 0xFF4FC3F7;
            case AnimPose.ARM: return 0xFFFFB74D;
            case AnimPose.LEG: return 0xFF81C784;
            case AnimPose.HEAD_LINE: return 0xFFB39DDB;
            case AnimPose.HAND: return 0xFFF06292;
            default: return 0xFFBA68C8;
        }
    }

    private static float strokeOf(int kind) {
        switch (kind) {
            case AnimPose.TORSO: return 9f;
            case AnimPose.ARM:
            case AnimPose.LEG: return 7f;
            case AnimPose.HEAD_LINE: return 6f;
            case AnimPose.HAND: return 4.5f;
            default: return 2.6f;
        }
    }
}
