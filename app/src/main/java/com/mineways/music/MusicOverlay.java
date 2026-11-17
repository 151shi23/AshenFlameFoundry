package com.mineways.music;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 系统级悬浮条：退到桌面或别的 App 也挂着，可自由拖动，位置记在本机。
 *
 * <p>三态与软件内那条一致：歌词胶囊 · 小圆 · 贴边一根边，并且**共用同一组本机键**
 * （{@code bar_mini} / {@code bar_peek} / {@code bar_x,y}）—— 用户在哪儿按的都是同一个开关；
 * 两处各记一份就会出现「里面收起了、外面还摊着」。
 *
 * <p>别人的画面上采不到背景，做不了首页搜索框那种真折射；Android 12+ 用
 * {@code setBackgroundBlurRadius} 让系统在窗口后面做真模糊，再叠一层半透明黑与高光边，
 * 视觉上向玻璃靠。低版本退化为半透明 + 阴影（仍是同一套圆角与白色描边语汇）。
 */
public class MusicOverlay extends Service {

    private static final String A_START = "com.mineways.music.OVERLAY_START";
    private static final String A_STOP = "com.mineways.music.OVERLAY_STOP";

    /** 窗口是不是真的挂着（给软件内那条判断用：只看偏好会把里面那条顶没）。 */
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    private WindowManager wm;
    private FrameLayout root;
    private LinearLayout capsule;
    private TextView lyric;
    private TextView sub;
    private TextView play;
    private TextView mini;
    private TextView peek;
    private WindowManager.LayoutParams lp;
    /** 现在摊开的是哪一态：0 歌词胶囊 · 1 小圆 · 2 贴边收纳。 */
    private int mode;
    private static final int MODE_CAPSULE = 0;
    private static final int MODE_MINI = 1;
    private static final int MODE_PEEK = 2;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final MusicCtl.Listener listener = this::render;

    public static void start(Context c) {
        Intent i = new Intent(c, MusicOverlay.class).setAction(A_START);
        try {
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, MusicOverlay.class).setAction(A_STOP);
        try {
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (A_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            // 云控不让系统外播放时，悬浮条也不该出现（它的存在就是「在别的 App 上播放」）
            if (!MusicSources.backgroundAllowed(this)) {
                MusicSources.setOverlayOn(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            show();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        MusicCtl.removeListener(listener);
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 构建

    @SuppressLint("ClickableViewAccessibility")
    private void show() {
        running = true;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }

        // 展开态 = 歌词胶囊：上行当前歌词（没收到词就退回歌名），下行进度与五个键
        capsule = new LinearLayout(this);
        capsule.setOrientation(LinearLayout.VERTICAL);
        capsule.setPadding(dp(14), dp(9), dp(6), dp(4));
        capsule.setBackground(glassBackground(dp(24)));
        if (Build.VERSION.SDK_INT >= 21) {
            capsule.setElevation(dp(10));
        }

        lyric = new TextView(this);
        lyric.setTextColor(Color.WHITE);
        lyric.setTextSize(15);
        lyric.setSingleLine(true);
        lyric.setEllipsize(TextUtils.TruncateAt.END);
        capsule.addView(lyric, new LinearLayout.LayoutParams(dp(228),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        sub = new TextView(this);
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(10);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(sub, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button("⟨", v -> setMode(MODE_MINI)));
        row.addView(button("⏮", v -> MusicCtl.prev(this)));
        play = button("⏯", v -> MusicCtl.toggle(this));
        row.addView(play);
        row.addView(button("⏭", v -> MusicCtl.next(this)));
        row.addView(button("✕", v -> {
            MusicSources.setOverlayOn(this, false);
            MusicCtl.stop(this);
            stopSelf();
        }));
        capsule.addView(row, new LinearLayout.LayoutParams(dp(228),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 折叠态：一颗小圆。点一下展开，长按收纳到边
        mini = new TextView(this);
        mini.setText("♪");
        mini.setTextColor(Color.WHITE);
        mini.setTextSize(18);
        mini.setGravity(Gravity.CENTER);
        mini.setBackground(circleBackground());
        if (Build.VERSION.SDK_INT >= 21) {
            mini.setElevation(dp(8));
        }

        // 收纳态：贴边只露一根发亮的边，碰它就出来
        peek = new TextView(this);
        peek.setText("♪");
        peek.setTextColor(0xB3FFFFFF);
        peek.setTextSize(9);
        peek.setGravity(Gravity.CENTER);
        peek.setBackground(glassBackground(dp(8)));

        root = new FrameLayout(this);
        root.addView(capsule, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(mini, new FrameLayout.LayoutParams(dp(56), dp(56)));
        root.addView(peek, new FrameLayout.LayoutParams(dp(14), dp(62)));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int sx = MusicSources.barX(this);
        int sy = MusicSources.barY(this);
        lp.x = sx < 0 ? dp(12) : sx;
        lp.y = sy < 0 ? dp(120) : sy;

        if (Build.VERSION.SDK_INT >= 31) {
            // setBackgroundBlurRadius 在公开 SDK 里不可见（@SystemApi），反射调用；
            // 设备不支持时静默退回半透明 + 阴影，不影响功能。
            try {
                java.lang.reflect.Method m = lp.getClass()
                        .getMethod("setBackgroundBlurRadius", int.class);
                m.invoke(lp, dp(28));
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            } catch (Throwable ignored) {
            }
        }

        mini.setOnClickListener(v -> setMode(MODE_CAPSULE));
        mini.setOnLongClickListener(v -> {
            setMode(MODE_PEEK);
            return true;
        });
        peek.setOnClickListener(v -> setMode(MODE_MINI));
        capsule.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, MusicActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        });
        for (View v : new View[]{capsule, mini, peek}) {
            v.setOnTouchListener(new DragTouch());
        }

        applyMode(storedMode());
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            stopSelf();
            return;
        }
        MusicCtl.addListener(listener);
        render();
    }

    /** 折叠与收纳都读同一组本机键：软件内那条改了，这边下一条心跳就跟着改。 */
    private int storedMode() {
        if (MusicSources.barPeek(this)) {
            return MODE_PEEK;
        }
        return MusicSources.barCollapsed(this) ? MODE_MINI : MODE_CAPSULE;
    }

    private void setMode(int next) {
        MusicSources.setBarCollapsed(this, next != MODE_CAPSULE);
        MusicSources.setBarPeek(this, next == MODE_PEEK);
        applyMode(next);
        // 尺寸变了要重新贴边，否则收纳之后半截留在屏外够不着
        ui.post(this::snapToEdge);
    }

    private void applyMode(int next) {
        mode = next;
        if (capsule == null) {
            return;
        }
        capsule.setVisibility(next == MODE_CAPSULE ? View.VISIBLE : View.GONE);
        mini.setVisibility(next == MODE_MINI ? View.VISIBLE : View.GONE);
        peek.setVisibility(next == MODE_PEEK ? View.VISIBLE : View.GONE);
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xE6FFFFFF);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setOnClickListener(click);
        return b;
    }

    private GradientDrawable glassBackground(int radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE61C1C22, 0xD9121216});
        g.setCornerRadius(radiusPx);
        g.setStroke(dp(1), 0x40FFFFFF);
        return g;
    }

    private GradientDrawable circleBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE614141A);
        g.setStroke(dp(1), 0x4DFFFFFF);
        return g;
    }

    private void render() {
        if (lyric == null) {
            return;
        }
        ui.post(() -> {
            if (lyric == null) {
                return;
            }
            // 软件内那条改了折叠 / 收纳，这边下一拍就跟上：两边共用同一组键
            int want = storedMode();
            if (want != mode) {
                applyMode(want);
            }
            String line = MusicCtl.lyricLine();
            lyric.setText(line.length() > 0 ? line
                    : (MusicCtl.title().length() == 0 ? "未播放" : MusicCtl.title()));
            String s = MusicCtl.artist();
            if (MusicCtl.error().length() > 0) {
                s = MusicCtl.error();
            } else if (MusicCtl.preparing()) {
                s = "缓冲中…";
            } else if (s.length() == 0) {
                s = clock(MusicCtl.positionMs()) + " / " + clock(MusicCtl.durationMs());
            }
            sub.setText(s);
            play.setText(MusicCtl.playing() ? "⏸" : "▶");
        });
    }

    private static String clock(int ms) {
        if (ms <= 0) {
            return "0:00";
        }
        int s = ms / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 松手贴左右边缘，和软件内那条同一套动作；收纳态不贴边就会半截留在屏外够不着。 */
    private void snapToEdge() {
        if (wm == null || lp == null || root == null) {
            return;
        }
        int w = root.getWidth();
        int screen = getResources().getDisplayMetrics().widthPixels;
        if (w <= 0 || screen <= 0) {
            return;
        }
        lp.x = lp.x + w / 2 < screen / 2 ? 0 : Math.max(0, screen - w);
        try {
            wm.updateViewLayout(root, lp);
            MusicSources.setBarPos(this, lp.x, lp.y);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 拖动

    private final class DragTouch implements View.OnTouchListener {
        private int downX;
        private int downY;
        private float rawX;
        private float rawY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lp.x;
                    downY = lp.y;
                    rawX = e.getRawX();
                    rawY = e.getRawY();
                    moved = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - rawX);
                    int dy = (int) (e.getRawY() - rawY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true;
                    }
                    if (moved) {
                        lp.x = downX + dx;
                        lp.y = Math.max(0, downY + dy);
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return moved;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        MusicSources.setBarPos(MusicOverlay.this, lp.x, lp.y);
                        snapToEdge();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }
    }
}
