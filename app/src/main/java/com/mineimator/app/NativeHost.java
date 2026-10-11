package com.mineimator.app;

import android.app.Activity;
import android.content.res.AssetManager;

/**
 * 原生引擎的 Java 侧接口（Mine-imator 安卓移植层）。
 *
 * <p><b>包名 / 类名 / 方法名与签名不可改动</b>：libmineimator.so 里是按名字绑定的
 * （导出符号 {@code Java_com_mineimator_app_NativeHost_nativeXxx}），改名即 UnsatisfiedLinkError。
 * {@code nativePickResult} 在原包里是非静态方法（实例方法），此处保持一致。
 */
public final class NativeHost {

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_MOVE = 1;
    public static final int TOUCH_UP = 2;
    public static final int TOUCH_CANCEL = 3;

    public static final NativeHost INSTANCE = new NativeHost();

    static {
        System.loadLibrary("mineimator");
    }

    private NativeHost() {
    }

    /** 启动引擎：传入 Activity（拿窗口/IME 用）、AssetManager 与私有目录。 */
    public static native void nativeStart(Activity activity, AssetManager assets, String filesDir, String cacheDir);

    public static native void nativeStop();

    /** GL 上下文就绪（GLSurfaceView.Renderer.onSurfaceCreated）。 */
    public static native void nativeSurfaceCreated();

    /** 画面尺寸变化。density 为屏幕密度，引擎据此换算触控与 UI 尺寸。 */
    public static native void nativeResize(int width, int height, float density);

    /** 逐帧绘制（onDrawFrame）。 */
    public static native void nativeFrame();

    /** 触控：action 用上面的 TOUCH_* 常量，坐标为像素。 */
    public static native void nativeTouch(int action, int pointerId, float x, float y);

    /** 按键：8=退格，13=回车（与原包一致）。 */
    public static native void nativeKey(int code);

    /** 输入法上屏文本。 */
    public static native void nativeCommitText(String text);

    /** 文件选择结果回填：绝对路径；空串表示用户取消，或选择器打开失败。 */
    public native void nativePickResult(String path);
}
