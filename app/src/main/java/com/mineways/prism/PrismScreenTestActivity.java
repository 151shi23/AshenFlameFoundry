package com.mineways.prism;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 屏幕体检：纯色 / 灰阶 / 触控网格。
 *
 * <p>查坏点、漏光、烧屏、断触。点击切换颜色，长按进入触控网格（手指划过的点会留下痕迹），
 * 返回退出。全屏沉浸、不联网、不申请任何权限。
 */
public final class PrismScreenTestActivity extends Activity {

    private static final int[] COLORS = {
            0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0xFF00FFFF, 0xFFFF00FF, 0xFFFFFF00, 0xFF808080, 0xFF303030
    };

    private int index;
    private boolean grid;
    private TouchView view;
    private TextView hint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        view = new TouchView();
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(0xFFEEEEEE);
        hint.setTextSize(12f);
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        hint.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(36);
        hp.leftMargin = dp(12);
        root.addView(hint, hp);
        setContentView(root);
        updateHint();
    }

    private void updateHint() {
        if (grid) {
            hint.setText("触控网格：手指划过的点会留下痕迹 · 长按切回纯色 · 返回退出");
            return;
        }
        hint.setText(String.format(Locale.US, "屏幕体检 %d/%d · 点击切换 · 长按网格 · 返回退出",
                index + 1, COLORS.length));
    }

    private final class TouchView extends View implements GestureDetector.OnGestureListener {
        private final Paint bg = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector detector;
        private final List<float[]> points = new ArrayList<float[]>();

        TouchView() {
            super(PrismScreenTestActivity.this);
            detector = new GestureDetector(PrismScreenTestActivity.this, this);
            line.setColor(0x33FFFFFF);
            line.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
            dot.setColor(0xCCFF3355);
            dot.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            bg.setColor(grid ? 0xFF101114 : COLORS[index]);
            canvas.drawRect(0, 0, getWidth(), getHeight(), bg);
            if (!grid) return;
            int step = Math.max(1, dp(48));
            for (int x = 0; x <= getWidth(); x += step) {
                canvas.drawLine(x, 0, x, getHeight(), line);
            }
            for (int y = 0; y <= getHeight(); y += step) {
                canvas.drawLine(0, y, getWidth(), y, line);
            }
            for (float[] p : points) {
                canvas.drawCircle(p[0], p[1], Math.max(6f, 9f * getResources().getDisplayMetrics().density), dot);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            detector.onTouchEvent(e);
            if (grid && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                points.add(new float[]{e.getX(), e.getY()});
                invalidate();
            }
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public void onShowPress(MotionEvent e) {
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (grid) {
                points.add(new float[]{e.getX(), e.getY()});
                invalidate();
            } else {
                index = (index + 1) % COLORS.length;
                updateHint();
                invalidate();
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            grid = !grid;
            points.clear();
            updateHint();
            invalidate();
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            return false;
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
