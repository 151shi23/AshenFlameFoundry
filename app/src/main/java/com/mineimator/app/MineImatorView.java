package com.mineimator.app;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 画面载体：GLES3 的 GLSurfaceView，每帧回调原生引擎；触控与输入法事件也转发给原生。
 * 引擎的整个界面（面板、时间轴、视口）都是原生自绘的，这里只提供 GL 上下文与输入通道。
 */
public final class MineImatorView extends GLSurfaceView {

    public MineImatorView(Context context) {
        this(context, false);
    }

    /**
     * @param lowProfile 保守适配（低内存 / 老机型）：画面缓冲降到 16 位色（RGB565 + 16 位深度），
     *                   帧缓冲带宽与显存占用直接减半 —— 弱 GPU 上更稳，代价是渐变色略有色带。
     */
    public MineImatorView(Context context, boolean lowProfile) {
        super(context);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        if (lowProfile) {
            setEGLConfigChooser(5, 6, 5, 0, 16, 0);
        }
        setRenderer(new FrameRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(false);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT;
        // 与原包一致：IME_ACTION_DONE | IME_FLAG_NO_FULLSCREEN
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new MiInput(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int masked = event.getActionMasked();
        final int nativeAction;
        switch (masked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                nativeAction = NativeHost.TOUCH_DOWN;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                nativeAction = NativeHost.TOUCH_UP;
                break;
            case MotionEvent.ACTION_MOVE:
                nativeAction = NativeHost.TOUCH_MOVE;
                break;
            case MotionEvent.ACTION_CANCEL:
                nativeAction = NativeHost.TOUCH_CANCEL;
                break;
            default:
                return false;
        }
        if (masked == MotionEvent.ACTION_MOVE) {
            // 移动事件要把每一根手指都报上去（多指同时拖动/缩放）
            final int count = event.getPointerCount();
            for (int i = 0; i < count; i++) {
                NativeHost.nativeTouch(NativeHost.TOUCH_MOVE, event.getPointerId(i),
                        event.getX(i), event.getY(i));
            }
        } else {
            final int index = event.getActionIndex();
            NativeHost.nativeTouch(nativeAction, event.getPointerId(index),
                    event.getX(index), event.getY(index));
        }
        return true;
    }

    /** 输入法桥：中文候选/上屏文本与退格、回车都转成原生引擎认识的事件。 */
    private static final class MiInput extends BaseInputConnection {

        private String composing = "";

        MiInput(View view) {
            super(view, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            composing = "";
            if (text != null && text.length() > 0) {
                NativeHost.nativeCommitText(text.toString());
            }
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            composing = (text == null) ? "" : text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (composing.length() > 0) {
                NativeHost.nativeCommitText(composing);
                composing = "";
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (composing.length() > 0) {
                final int drop = Math.min(beforeLength, composing.length());
                composing = composing.substring(0, composing.length() - drop);
                return true;
            }
            for (int i = 0; i < beforeLength; i++) {
                NativeHost.nativeKey(8);
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_ENTER:
                    finishComposingText();
                    NativeHost.nativeKey(13);
                    break;
                case KeyEvent.KEYCODE_DEL:
                    if (composing.length() > 0) {
                        composing = composing.substring(0, composing.length() - 1);
                    } else {
                        NativeHost.nativeKey(8);
                    }
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    private final class FrameRenderer implements GLSurfaceView.Renderer {

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            NativeHost.nativeSurfaceCreated();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            NativeHost.nativeResize(width, height, getResources().getDisplayMetrics().density);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            NativeHost.nativeFrame();
        }
    }
}
