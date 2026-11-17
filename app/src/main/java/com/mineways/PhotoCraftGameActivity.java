package com.mineways;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PhotoCraft 的 Android 宿主 Activity。
 * <p>
 * 为什么继承 {@code com.google.androidgamesdk.GameActivity}：窗口、输入法、Unicode IME、
 * Surface 生命周期和原生胶水都在这个 AAR 类里；Rust 侧 {@code android_main} 就是它启动的。
 * 继承后原生胶水仍然拿 {@code this} 当 activity 句柄，行为不变，只是我们多了一个能接
 * {@code onActivityResult} 的落点。
 * <p>
 * 文件桥：
 * <ul>
 *   <li>Rust 的 {@code Services::file_dialog} 通过 JNI 调 {@link #requestFileDialog}
 *       在 UI 线程拉起 SAF（打开 / 另存为 / 选择文件夹）；</li>
 *   <li>选完在这里读成字节，再调 {@code nativeDialogResult} 把结果推回 Rust；
 *       Rust 用 {@code FileDialogAnswer::Contents}/{@code SaveTo} 继续走内核流程；</li>
 *   <li>保存时 Rust 的 {@code Services::write} 调 {@link #writeUri}，用
 *       {@code ContentResolver.openOutputStream} 写进用户刚选的 URI。</li>
 * </ul>
 * 另外补上偏好目录（Rust 存 preferences / GPU memo）和系统剪贴板图像。
 */
public class PhotoCraftGameActivity extends com.google.androidgamesdk.GameActivity {

    private static final String TAG = "PhotoCraft";

    /** 与 Rust 侧 FileDialogKind 的数值保持一致。 */
    private static final int KIND_OPEN = 0;
    private static final int KIND_SAVE = 1;
    private static final int KIND_FOLDER = 2;
    /** Rust 内置浏览器专用：列一个 SAF 目录、授权一个目录树、读一组 content:// 文档。 */
    private static final int KIND_LIST = 3;
    private static final int KIND_ADD_TREE = 4;
    private static final int KIND_READ_URI = 5;

    /**
     * 请求字段分隔符。用 \u0001 而不是 '|'：文件名和 content URI 都可能含 '|'，
     * 用竖线切分会把参数切错（这也正是上次请求解析容易踩的坑）。
     */
    private static final String FIELD = "\u0001";

    /**
     * 官方取结果 API（developer.android.com《Get a result from an activity》）。
     *
     * <p>为什么不再用 {@code startActivityForResult}：它已废弃，而且本 Activity 在清单里是
     * {@code launchMode="singleTask"} —— 这种启动模式下发起的 request 常常拿不到结果，
     * 表现就是"点了按钮毫无反应"。{@code GameActivity} 继承自 {@code AppCompatActivity}，
     * 也就是 {@code ComponentActivity}，所以 {@code registerForActivityResult} 直接可用，
     * 且回调跟着生命周期走，冷启动/重建都不会丢。
     */
    private ActivityResultLauncher<Void> openDocument;
    /** 多选版：输出由契约解析成 Uri 列表。 */
    private ActivityResultLauncher<Void> openDocuments;
    private ActivityResultLauncher<String> createDocument;
    private ActivityResultLauncher<Uri> openTree;
    /** 内置浏览器的「＋ 添加文件夹」：一次授权，之后可由 DocumentsContract 列目录。 */
    private ActivityResultLauncher<Uri> addTree;
    private final CreateAny createContract = new CreateAny();
    /** `nativeSetActivity` 只在库真正加载后才会成功；轮询里补一次，避免 onCreate 时抢跑。 */
    private boolean nativeActivityShared = false;

    /** 最近一次另存为建议的文件名，SAF 查不到显示名时用它兜底。 */
    private volatile String lastSuggested = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 先交句柄：super.onCreate 已经把 native 库载起来了（GameActivity 的原生胶水）。
        try {
            nativeSetActivity(this);
            nativeActivityShared = true;
        } catch (Throwable t) {
            Log.w(TAG, "nativeSetActivity failed", t);
        }
        // 返回键与返回手势：官方 OnBackPressedDispatcher（AppCompatActivity 自带，手势导航也会走这里）。
        // 交给 egui 按 Esc 处理，关掉最上层对话框；否则有的界面根本没有出口。
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                try {
                    nativeBackKey();
                } catch (Throwable t) {
                    finish();      // 原生没接住时至少还能退出
                }
            }
        });
        // 开启轮询：Rust 那边的选择器请求靠这条通道送上来（不依赖出站 JNI）。
        dialogPoll.postDelayed(dialogPollTask, 250);
        android.net.Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
        // 必须在 STARTED 之前注册（官方要求），所以在 onCreate 里、拉起界面前完成。
        openDocument = registerForActivityResult(new OpenOne(),
                uri -> onPicked(KIND_OPEN, uri == null ? null : Collections.singletonList(uri)));
        openDocuments = registerForActivityResult(new OpenMany(),
                uris -> onPicked(KIND_OPEN, uris));
        createDocument = registerForActivityResult(createContract,
                uri -> onPicked(KIND_SAVE, uri == null ? null : Collections.singletonList(uri)));
        openTree = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> onPicked(KIND_FOLDER, uri == null ? null : Collections.singletonList(uri)));
        addTree = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> onPicked(KIND_ADD_TREE, uri == null ? null : Collections.singletonList(uri)));
        // A phone in landscape has no room for the status/navigation bars on top of the editor:
        // hide them so the Surface owns the whole display (edge to edge; no black strip above).
        applyImmersive();
        View decor = getWindow().getDecorView();
        decor.setFocusable(true);
        decor.setFocusableInTouchMode(true);
        decor.requestFocus();
        // 输入法目标先建好：输入法第一次要连接时它必须已经在视图树里，否则又要等下一轮。
        setupImeTarget();
        try {
            nativeWindowFocus(true);
        } catch (Throwable ignored) {
            // 原生库还没起来；Rust 侧默认就是"有焦点"。
        }
    }

    /**
     * 输入法目标：一个 1×1、全透明的 {@link android.widget.EditText}。
     *
     * <p><b>为什么不能包 Activity 的 onCreateInputConnection</b>：{@code GameActivity} 的内容视图是
     * {@code GameActivity$InputEnabledSurfaceView}，它自己覆写了 {@code onCreateInputConnection}
     * 并返回 {@code gametextinput.InputConnection}。输入法服务的是<b>这个 SurfaceView</b>，不是
     * Activity —— 所以 Activity 上的 onCreateInputConnection 永远不会被调用，上一版写在这里的
     * 包装是死代码，于是"键盘弹得出来、字一个都进不来"。
     *
     * <p>这里自己造一个输入法目标：它一定被当成 text editor，onCreateInputConnection 一定被调用，
     * 文字 / 预编辑 / 删除 / 按键全部转成 native 调用（Java → Rust 这个方向在本机是可靠的）。
     * 目标本身不存任何文字，缓冲区永远是空的，所以不需要跟文档对账。
     */
    private android.widget.EditText imeTarget;

    private void setupImeTarget() {
        if (imeTarget != null) {
            return;
        }
        try {
            android.widget.EditText edit = new android.widget.EditText(this) {
                @Override
                public boolean onCheckIsTextEditor() {
                    return true;
                }

                @Override
                public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                    if (outAttrs != null) {
                        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
                        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                | EditorInfo.IME_ACTION_NONE;
                        outAttrs.initialSelStart = 0;
                        outAttrs.initialSelEnd = 0;
                    }
                    dialogLog("IME 目标连接已建立（文字改走 native）");
                    return new BridgeInputConnection(this);
                }
            };
            edit.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edit.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE);
            edit.setSingleLine(false);
            edit.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            edit.setTextColor(android.graphics.Color.TRANSPARENT);
            edit.setCursorVisible(false);
            // 不是 GONE / INVISIBLE：不可见的 View 拿不到焦点，输入法就不会认它。
            edit.setAlpha(0.02f);
            edit.setFocusable(true);
            edit.setFocusableInTouchMode(true);
            edit.setSoundEffectsEnabled(false);
            android.widget.FrameLayout host = new android.widget.FrameLayout(this);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(2, 2);
            host.addView(edit, lp);
            addContentView(host, new android.view.ViewGroup.LayoutParams(2, 2));
            imeTarget = edit;
            dialogLog("IME 目标已创建");
        } catch (Throwable t) {
            Log.w(TAG, "setupImeTarget failed", t);
            dialogLog("IME 目标创建失败: " + t);
        }
    }

    /** 轮询通道要的那一步：先给目标聚焦，再让输入法弹出来（都必须在 UI 线程）。 */
    private void showImeTarget() {
        setupImeTarget();
        if (imeTarget == null) {
            return;
        }
        try {
            imeTarget.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            boolean ok = imm != null
                    && imm.showSoftInput(imeTarget, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            dialogLog("showImeTarget ok=" + ok + " focused=" + imeTarget.isFocused());
        } catch (Throwable t) {
            Log.w(TAG, "showImeTarget failed", t);
        }
        try {
            nativeImeAck();
        } catch (Throwable ignored) {
            // 原生库还没起来：下一轮再试。
        }
    }

    private void hideImeTarget() {
        if (imeTarget == null) {
            return;
        }
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(imeTarget.getWindowToken(), 0);
            }
            imeTarget.clearFocus();
        } catch (Throwable t) {
            Log.w(TAG, "hideImeTarget failed", t);
        }
        try {
            nativeImeAck();
        } catch (Throwable ignored) {
            // 原生库还没起来。
        }
    }

    /**
     * 把输入法的编辑转给 Rust。
     *
     * <p>所有方法都返回 true 且<b>不委托</b>：目标缓冲区保持为空，既不会和文档里的文字打架，
     * 也不会有第二份文字从别处进来。
     */
    private final class BridgeInputConnection extends InputConnectionWrapper {
        BridgeInputConnection(View target) {
            super(new BaseInputConnection(target, false), false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (text != null && text.length() > 0) {
                try {
                    nativeImeCommit(text.toString());
                } catch (Throwable t) {
                    Log.w(TAG, "nativeImeCommit failed", t);
                }
            }
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            try {
                nativeImePreedit(text == null ? "" : text.toString());
            } catch (Throwable ignored) {
                // The native library may not be loaded yet.
            }
            return true;
        }

        @Override
        public boolean finishComposingText() {
            try {
                nativeImePreedit("");
            } catch (Throwable ignored) {
                // Nothing to finish before the library is up.
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            try {
                nativeImeDelete(beforeLength, afterLength);
            } catch (Throwable ignored) {
                // The native library may not be loaded yet.
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return event != null && forwardHardwareKey(event);
        }

        // 输入法会来问上下文；这里一律"空"，它就不会尝试自己维护一份文本。
        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            return "";
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            return "";
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return null;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        @Override
        public boolean setSelection(int start, int end) {
            return true;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }
    }

    /**
     * Hand one hardware key to Rust. Returns true when Rust has a mapping for it, so the caller can
     * consume the event — otherwise winit would deliver a second copy (double typing / double
     * shortcut).
     */
    private boolean forwardHardwareKey(KeyEvent event) {
        int code = event.getKeyCode();
        // Back, volume, media and other unhandled keys stay with the system.
        if (code == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        int meta = event.getMetaState();
        int unicode = 0;
        try {
            unicode = event.getUnicodeChar(meta);
        } catch (Throwable ignored) {
            // Some keys have no character; the key mapping alone is enough.
        }
        boolean repeat = event.getRepeatCount() > 0;
        try {
            return nativeKeyEvent(code, event.getAction(), repeat, maskOf(meta), unicode);
        } catch (Throwable t) {
            Log.w(TAG, "nativeKeyEvent failed", t);
            return false;
        }
    }

    /** Hardware modifier state in the shape Rust expects: 1 shift, 2 ctrl, 4 alt, 8 meta. */
    private static int maskOf(int meta) {
        int mask = 0;
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) mask |= 1;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) mask |= 2;
        if ((meta & KeyEvent.META_ALT_ON) != 0) mask |= 4;
        if ((meta & KeyEvent.META_META_ON) != 0) mask |= 8;
        return mask;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The system bars come back after the picker or a swipe; re-hide them on every focus gain.
        if (hasFocus) applyImmersive();
        // winit 的 Focused 事件常常在事件循环起来之前就被吃掉，Rust 那边于是整场认为窗口没有焦点，
        // 文字工具的路由门（type_tool::ime::reset_route_eligible）就一直关着 —— 字符、退格、
        // 方向键全被静静丢掉。这里把真实焦点状态交给 Rust。
        try {
            nativeWindowFocus(hasFocus);
        } catch (Throwable ignored) {
            // 原生库还没起来。
        }
        // A modifier released while the picker had focus never reaches us: drop it explicitly,
        // otherwise every later keystroke would still look like Ctrl is held down.
        if (!hasFocus) {
            lastKeyMask = -1;
            try {
                nativeKeyModifiers(0);
            } catch (Throwable ignored) {
                // The native library may not be loaded yet.
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // 鼠标 / 触控板
    //
    // winit 的 Android 后端只把 MotionEvent 当成 Touch：`ACTION_SCROLL`、`ACTION_HOVER_MOVE`、
    // `ACTION_BUTTON_PRESS/RELEASE` 都不在它的 match 里（源码里那句 `_ => None // TODO mouse
    // events`）。所以外接鼠标的滚轮、悬停、右键在这条链路上是彻底消失的 —— 跟 dispatchKeyEvent
    // 一点关系都没有。这里自己接过来，转成 native 的指针事件。
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_SCROLL) {
            float dx = event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL);
            float dy = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
            if (dx != 0f || dy != 0f) {
                try {
                    nativeMouseWheel(dx, dy);
                } catch (Throwable ignored) {
                    // 原生库还没起来。
                }
                return true;
            }
        }
        if (action == android.view.MotionEvent.ACTION_HOVER_MOVE) {
            try {
                nativeMouseMove(event.getX(), event.getY());
            } catch (Throwable ignored) {
                // 原生库还没起来。
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        // 只有中键 / 右键走这里；左键继续交给 winit 的触控路径，免得同一次点击进来两遍。
        if (action == android.view.MotionEvent.ACTION_BUTTON_PRESS
                || action == android.view.MotionEvent.ACTION_BUTTON_RELEASE) {
            int buttons = event.getButtonState();
            int which = 0;
            if ((buttons & android.view.MotionEvent.BUTTON_SECONDARY) != 0) {
                which = 1;
            } else if ((buttons & android.view.MotionEvent.BUTTON_TERTIARY) != 0) {
                which = 2;
            }
            if (which != 0) {
                try {
                    nativeMouseButton(which, action == android.view.MotionEvent.ACTION_BUTTON_PRESS,
                            event.getX(), event.getY());
                } catch (Throwable ignored) {
                    // 原生库还没起来。
                }
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void applyImmersive() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
            guardCutoutInsets(window);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                // Hidden until a swipe; the bars then time out and hide themselves again.
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /**
     * Keep the UI out of the camera cutout without bringing back the black band above the top bar.
     *
     * <p>SHORT_EDGES lets the app draw under a punch-hole in portrait, and the menu bar / home
     * header sit exactly there ("顶栏被遮挡" again, just a different cause). LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
     * would fix the overlap but reintroduces the black strip he already rejected, so instead pad the
     * surface by the cutout's safe insets: content stays edge-to-edge everywhere except the notch.
     */
    private void guardCutoutInsets(Window window) {
        final View root = window.getDecorView();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0, top = 0, right = 0, bottom = 0;
            try {
                android.view.DisplayCutout cut = insets.getDisplayCutout();
                if (cut != null) {
                    left = cut.getSafeInsetLeft();
                    top = cut.getSafeInsetTop();
                    right = cut.getSafeInsetRight();
                    bottom = cut.getSafeInsetBottom();
                }
            } catch (Throwable ignored) {
                // No cutout information: keep the surface whole.
            }
            if (v.getPaddingLeft() != left || v.getPaddingTop() != top
                    || v.getPaddingRight() != right || v.getPaddingBottom() != bottom) {
                v.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    /** Last mask handed to Rust, so the in-app log gets one line per chord, not one per repeat. */
    private int lastKeyMask = -1;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // winit's Android backend sends KeyboardInput but never ModifiersChanged, so egui would see
        // every Ctrl / Shift / Alt combination as unmodified. Forward the real state instead.
        int mask = maskOf(event.getMetaState());
        if (mask != lastKeyMask) {
            lastKeyMask = mask;
            dialogLog("修饰键变更: mask=" + mask + " 键="
                    + KeyEvent.keyCodeToString(event.getKeyCode()));
        }
        nativeKeyModifiers(mask);
        // 软键盘也会把一部分按键从这里送进来（不少输入法对英文/退格就是发 KeyEvent）。
        // 以前这里直接交给 super，于是这些按键被焦点里的 EditText 自己吞掉、写进它那个没人看的
        // 缓冲区 —— Rust 一个字符都收不到。现在一律先问 Rust：它有映射就消费掉，没有才放行。
        // 软键盘的 commitText 走的是 InputConnection（另一条路），两边不会重复。
        if (forwardHardwareKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Writes a picker step to logcat and to PhotoCraft's in-app log. */
    private void dialogLog(String message) {
        Log.i(TAG, message);
        try {
            nativeDialogLog(message);
        } catch (Throwable ignored) {
            // The native library may not be loaded yet; logcat still has the line.
        }
    }

    // ---- 由 Rust 导出的 JNI 回调（方法名不能改）----

    private static native void nativeDialogResult(int kind, String name, byte[] data, String uri);

    private static native void nativeDialogDone(int kind, int count);

    private static native void nativeDialogError(int kind, String message);

    /** Hardware modifier state: 1 shift, 2 ctrl, 4 alt, 8 meta. */
    private static native void nativeKeyModifiers(int mask);

    /** One committed run from the IME (`InputConnection.commitText`). */
    private static native void nativeImeCommit(String text);

    /** The IME's live composition; `""` ends it. */
    private static native void nativeImePreedit(String text);

    /** `InputConnection.deleteSurroundingText`, in characters. */
    private static native void nativeImeDelete(int before, int after);

    /** One hardware key; returns true when Rust has a mapping (caller then consumes the event). */
    private static native boolean nativeKeyEvent(int keyCode, int action, boolean repeat, int mask, int unicode);

    /** The soft-keyboard request queue: 1 = show, 2 = hide, 0 = nothing to do. */
    private static native int nativePollIme();

    /** The IME target is focused and the keyboard was asked for; stops Rust's fallback. */
    private static native void nativeImeAck();

    /** The window's real focus, which winit's Android backend usually never reports. */
    private static native void nativeWindowFocus(boolean focused);

    /** One wheel notch (physical pixels of scroll), from `onGenericMotionEvent`. */
    private static native void nativeMouseWheel(float dx, float dy);

    /** A mouse hover position in physical pixels. */
    private static native void nativeMouseMove(float x, float y);

    /** A secondary (1) or middle (2) mouse button, in physical pixels. */
    private static native void nativeMouseButton(int button, boolean pressed, float x, float y);

    /** One diagnostic line from the picker, shown in PhotoCraft's in-app log. */
    private static native void nativeDialogLog(String message);

    /** One child of a SAF folder listing; `nativeDialogDone(KIND_LIST, n)` closes the batch. */
    private static native void nativeListEntry(String name, String uri, boolean isDir, long size);

    /**
     * 把 {@code this} 交给 Rust，由它 {@code NewGlobalRef} 永久持有。
     *
     * <p>为什么必须有这一步：原生侧原来缓存的是 {@code AndroidApp::activity_as_ptr()} —— 那是
     * <b>local 引用</b>，只在拿到它的线程/帧里有效。渲染线程之后再拿它做 JNI 查找全部失败，
     * 于是选择器永远拉不起来（日志 "no Activity or SAF unavailable"），剪贴板写入也一起废掉。
     * 只有 global 引用能跨线程用。
     */
    private static native void nativeSetActivity(Activity activity);

    /** 返回键 / 返回手势：转成 Esc 交给 egui，关掉最上层对话框（否则有的界面退不出来）。 */
    private static native void nativeBackKey();

    /**
     * 告诉原生侧「系统选择器真的拉起来了」。没有这条，原生侧分不清
     * 「用户正在挑文件」和「窗口根本没出现」—— 两者在他眼里都是没反应，
     * 于是只能 4 秒后一律切内置浏览器（会把正在挑文件的用户打断）。
     */
    private static native void nativeDialogLaunched(int kind);

    /**
     * Java 主动来取：Rust 排队的请求，格式 {@code kind\u0001multiple\u0001suggested\u0001arg}，
     * 没有请求时是空串。每次调用取走一条，所以轮询不会连开两个选择器。
     *
     * <p>这是本机唯一可靠的通道：从 Rust 出站调 Java（attach 线程 → 找类 → 找方法 → 调用）
     * 在 GameActivity 的胶水上反复失败，反方向则没有任何这些环节。
     */
    private static native String nativePollDialog();

    /** Rust 排队的导出写入，格式 {@code "<id>|<uri>"}；没有时是空串。 */
    private static native String nativePollWrite();

    /** 取走 {@code nativePollWrite} 那条写入的字节（大文件只跨一次 JNI）。 */
    private static native byte[] nativeTakeWriteBytes(int id);

    /** 写入结束回执；{@code message} 可带“已保存到 …”的回退路径。 */
    private static native void nativeWriteDone(int id, boolean ok, String message);

    /**
     * 由 Rust 在渲染线程调用：转到 UI 线程再启动 SAF。
     *
     * @param kind      0=打开，1=另存为，2=选择文件夹
     * @param multiple  打开时是否允许多选
     * @param suggested 另存为的默认文件名（可能带目录）
     */
    public void requestFileDialog(int kind, boolean multiple, String suggested) {
        // Direct JNI-in path: kept for the case where the outbound call does work. The poll loop
        // below is what actually runs on this device.
        runOnUiThread(() -> launchPicker(kind, multiple, suggested));
    }

    /** 轮询 Rust 的请求队列：本机唯一可靠的入口（不需要 attach / 方法查找）。 */
    private final Handler dialogPoll = new Handler(Looper.getMainLooper());
    private final Runnable dialogPollTask = new Runnable() {
        @Override
        public void run() {
            if (!nativeActivityShared) {
                // IME / 剪贴板 / 偏好目录都靠这个 global 引用；库刚加载完成时补交一次。
                try {
                    nativeSetActivity(PhotoCraftGameActivity.this);
                    nativeActivityShared = true;
                    dialogLog("nativeSetActivity 补交完成");
                } catch (Throwable ignored) {
                    // 还没加载好：下一轮再试。
                }
            }
            try {
                String request = nativePollDialog();
                if (request != null && !request.isEmpty()) {
                    String[] parts = request.split(java.util.regex.Pattern.quote(FIELD), 4);
                    int kind = Integer.parseInt(parts[0].trim());
                    boolean multiple = parts.length > 1 && "1".equals(parts[1].trim());
                    String suggested = parts.length > 2 ? parts[2] : "";
                    String arg = parts.length > 3 ? parts[3] : "";
                    dialogLog("轮询取到请求: kind=" + kind + " multiple=" + multiple
                            + " name=" + suggested + " arg=" + arg);
                    dispatch(kind, multiple, suggested, arg);
                }
            } catch (Throwable t) {
                Log.w(TAG, "dialog poll failed", t);
            }
            try {
                // 导出写入也走轮询队列：从 Rust 出站调 writeUri 在这台机器上到不了 Activity，
                // 结果就是每次导出都只留下 CREATE_DOCUMENT 建出来的 0 字节文件。
                String write = nativePollWrite();
                if (write != null && !write.isEmpty()) {
                    int bar = write.indexOf('|');
                    int id = Integer.parseInt(write.substring(0, bar).trim());
                    String uri = write.substring(bar + 1);
                    dialogLog("轮询取到写入: id=" + id + " uri=" + uri);
                    new Thread(() -> performWrite(id, uri), "photocraft-write").start();
                }
            } catch (Throwable t) {
                Log.w(TAG, "write poll failed", t);
            }
            try {
                // 软键盘：Rust 只举旗，聚焦 + showSoftInput 必须在 UI 线程做。
                int ime = nativePollIme();
                if (ime == 1) {
                    showImeTarget();
                } else if (ime == 2) {
                    hideImeTarget();
                }
            } catch (Throwable t) {
                Log.w(TAG, "ime poll failed", t);
            }
            dialogPoll.postDelayed(this, 250);
        }
    };

    /**
     * 请求分派：0~2 是系统选择器，3 是 SAF 列目录，4 是授权目录树，5 是读 content:// 文档。
     * 后三种是内置浏览器在 scoped storage 下能用的唯一零权限路径。
     */
    private void dispatch(int kind, boolean multiple, String suggested, String arg) {
        switch (kind) {
            case KIND_LIST:
                listChildren(arg);
                break;
            case KIND_ADD_TREE:
                try {
                    addTree.launch(null);
                } catch (Throwable t) {
                    dialogLog("目录授权拉起失败: " + t);
                    nativeDialogError(KIND_ADD_TREE, String.valueOf(t));
                }
                break;
            case KIND_READ_URI:
                readRequest(arg);
                break;
            default:
                launchPicker(kind, multiple, suggested);
                break;
        }
    }

    /** `arg` = {@code "<target kind>|<uri1>\n<uri2>..."}。 */
    private void readRequest(String arg) {
        int bar = arg.indexOf('|');
        if (bar <= 0) {
            nativeDialogError(KIND_OPEN, "读取请求缺少目标 kind: " + arg);
            return;
        }
        int target;
        try {
            target = Integer.parseInt(arg.substring(0, bar).trim());
        } catch (Throwable t) {
            target = KIND_OPEN;
        }
        List<Uri> uris = new ArrayList<>();
        for (String s : arg.substring(bar + 1).split("\n")) {
            String one = s.trim();
            if (!one.isEmpty()) uris.add(Uri.parse(one));
        }
        dialogLog("读取内置浏览器所选: " + uris.size() + " 个");
        readAndDeliver(target, uris);
    }

    /**
     * 列 SAF 目录：只查询 provider，不碰文件系统，所以 scoped storage 下也能列出
     * Download / DCIM / SD 卡的真实内容（前提是用户用「＋ 添加文件夹」授权过）。
     */
    private void listChildren(final String uriString) {
        new Thread(() -> {
            int count = 0;
            try {
                Uri uri = Uri.parse(uriString);
                String docId = DocumentsContract.getDocumentId(uri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId);
                String[] projection = new String[]{
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                };
                try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
                    if (c == null) throw new IOException("provider 没有返回目录内容");
                    while (c.moveToNext()) {
                        String name = c.getString(0);
                        String childId = c.getString(1);
                        String mime = c.getString(2);
                        long size = c.isNull(3) ? 0 : c.getLong(3);
                        if (name == null || childId == null) continue;
                        boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                        Uri child = DocumentsContract.buildDocumentUriUsingTree(uri, childId);
                        nativeListEntry(name, child.toString(), isDir, size);
                        count++;
                    }
                }
                dialogLog("SAF 目录列表: " + count + " 项 " + uriString);
                nativeDialogDone(KIND_LIST, count);
            } catch (Throwable t) {
                Log.w(TAG, "listChildren failed", t);
                dialogLog("SAF 列目录失败: " + t);
                nativeDialogError(KIND_LIST, String.valueOf(t));
            }
        }, "photocraft-saf-list").start();
    }

    private void launchPicker(int kind, boolean multiple, String suggested) {
        toast("正在打开系统文件选择器…");
        dialogLog("选择器启动: kind=" + kind + " multiple=" + multiple + " suggested=" + suggested
                + " state=" + getLifecycle().getCurrentState());
        try {
            if (kind == KIND_SAVE) {
                String title = fileName(suggested);
                lastSuggested = title;
                createContract.setMime(mimeOf(title));
                dialogLog("CreateDocument 拉起: title=" + title + " type=" + createContract.mime());
                createDocument.launch(title.isEmpty() ? "untitled" : title);
            } else if (kind == KIND_FOLDER) {
                dialogLog("OpenDocumentTree 拉起");
                openTree.launch(null);
            } else if (multiple) {
                dialogLog("OpenDocument(多选) 拉起");
                openDocuments.launch(null);
            } else {
                dialogLog("OpenDocument 拉起");
                openDocument.launch(null);
            }
            // 真的拉起来了才回报：原生侧据此关掉「没出现就切备选」的看门狗。
            nativeDialogLaunched(kind);
        } catch (Throwable t) {
            Log.w(TAG, "launchPicker failed", t);
            dialogLog("选择器启动失败: " + t);
            toast("无法打开文件选择器：" + t.getClass().getSimpleName());
            nativeDialogError(kind, String.valueOf(t));
            nativeDialogDone(kind, 0);
        }
    }

    private void toast(final String text) {
        runOnUiThread(() -> {
            try {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // No window to toast on: the in-app log still has the line.
            }
        });
    }

    /**
     * 单文件打开。
     *
     * <p>**不再传 {@code EXTRA_MIME_TYPES}**：{@code setType} 用通配 MIME 本身就是"什么都能选"，
     * 再塞一个同样通配的 MIME 数组，部分 ROM 的 DocumentsUI 会把它当成一份严格的过滤表，
     * 结果是 PSD/PSB 这类没有标准 MIME 的文件被挡在选择器里看不见。
     * 官方契约 {@code OpenDocument} 写死要传 MIME 数组，所以这里自己写一个最小契约。
     */
    private static final class OpenOne extends ActivityResultContract<Void, Uri> {
        @Override
        public Intent createIntent(Context context, Void input) {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
        }

        @Override
        public Uri parseResult(int resultCode, @Nullable Intent intent) {
            return (resultCode == Activity.RESULT_OK && intent != null) ? intent.getData() : null;
        }
    }

    /**
     * 官方契约的多选版本：{@code ActivityResultContracts.OpenDocument} 只会要一个文件，
     * 而 PhotoCraft 的导入要多选。契约本身是官方推荐的自定义方式（createIntent / parseResult）。
     *
     * <p>同样不传 {@code EXTRA_MIME_TYPES}，理由见 {@link OpenOne}。
     */
    private static final class OpenMany extends ActivityResultContract<Void, List<Uri>> {
        @Override
        public Intent createIntent(Context context, Void input) {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        @Override
        public List<Uri> parseResult(int resultCode, @Nullable Intent intent) {
            if (resultCode != Activity.RESULT_OK || intent == null) return Collections.emptyList();
            ArrayList<Uri> out = new ArrayList<>();
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) out.add(uri);
                }
            } else if (intent.getData() != null) {
                out.add(intent.getData());
            }
            return out;
        }
    }

    /**
     * {@code CreateDocument} 的 MIME 是构造时定死的，而 PhotoCraft 一份文档可以另存成
     * PSD / PNG / TIFF…，只能在每次请求前改：官方契约允许覆写 {@code createIntent} 这样做。
     */
    private static final class CreateAny extends ActivityResultContracts.CreateDocument {
        private String type = "application/octet-stream";

        CreateAny() {
            super("*/*");
        }

        void setMime(String mime) {
            type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
        }

        String mime() {
            return type;
        }

        @Override
        public Intent createIntent(Context context, String input) {
            return super.createIntent(context, input).setType(type);
        }
    }

    /**
     * 由 Rust 的 {@code Services::write} 调用：把字节写进 SAF 给的 {@code content://} URI。
     * 只有整份内容写入成功才返回 true，PhotoCraft 才会把文档标记为“已保存”。
     */
    public boolean writeUri(String uri, byte[] data) {
        if (uri == null || data == null) return false;
        try {
            return writeTo(Uri.parse(uri), data, "wt");
        } catch (Throwable t) {
            Log.w(TAG, "writeUri (wt) failed: " + uri, t);
            dialogLog("writeUri(wt) 失败: " + t);
        }
        try {
            return writeTo(Uri.parse(uri), data, "w");
        } catch (Throwable t) {
            Log.w(TAG, "writeUri failed: " + uri, t);
            dialogLog("writeUri(w) 失败: " + t);
            return false;
        }
    }

    private boolean writeTo(Uri uri, byte[] data, String mode) throws IOException {
        try (OutputStream out = getContentResolver().openOutputStream(uri, mode)) {
            if (out == null) return false;
            out.write(data);
            out.flush();
            return true;
        }
    }

    /**
     * 轮询队列取到的一条导出：先写用户选的 URI，失败就落到 {@code Download/PhotoCraft}。
     * 无论走哪条路都回执给 Rust，导出才不会永远停在 0 字节。
     */
    private void performWrite(int id, String uri) {
        byte[] data;
        try {
            data = nativeTakeWriteBytes(id);
        } catch (Throwable t) {
            Log.w(TAG, "nativeTakeWriteBytes failed", t);
            data = null;
        }
        if (data == null) {
            dialogLog("写入失败: 没取到字节 (id=" + id + ")");
            nativeWriteDone(id, false, "写入数据丢失");
            return;
        }
        String name = writeName(uri);
        dialogLog("开始写入: " + name + " " + data.length + " 字节 -> " + uri);
        if (writeUri(uri, data)) {
            dialogLog("写入完成: " + name);
            nativeWriteDone(id, true, "");
            return;
        }
        dialogLog("所选目录写入失败，回退 Download/PhotoCraft");
        String saved = writeToDownloads(name, data);
        if (saved != null) {
            dialogLog("回退保存成功: " + saved);
            nativeWriteDone(id, true, "已保存到 " + saved);
        } else {
            dialogLog("回退保存也失败");
            nativeWriteDone(id, false, "所选目录和 Download 都写不进去");
        }
    }

    /** 用户选的 URI 里的显示名；查不到时用另存为时的建议名。 */
    private String writeName(String uri) {
        String name = null;
        try {
            name = displayName(Uri.parse(uri));
        } catch (Throwable ignored) {
            // fall through
        }
        if (name == null || name.isEmpty() || "document".equals(name)) name = lastSuggested;
        if (name == null || name.isEmpty()) name = "photocraft-" + System.currentTimeMillis();
        if (name.indexOf('.') < 0) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeOf(name));
            if (ext != null && !ext.isEmpty()) name = name + "." + ext;
        }
        return name;
    }

    /**
     * 无权限也能写的固定目录：API 29+ 用 MediaStore 插进 {@code Download/PhotoCraft}，
     * 老系统退回应用自己的外部 Downloads 目录（{@code /Android/data/<pkg>/files/Download}）。
     */
    private String writeToDownloads(String name, byte[] data) {
        String mime = mimeOf(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/PhotoCraft");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = null;
            try {
                item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (item == null) return null;
                try (OutputStream out = getContentResolver().openOutputStream(item)) {
                    if (out == null) {
                        getContentResolver().delete(item, null, null);
                        return null;
                    }
                    out.write(data);
                    out.flush();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(item, done, null, null);
                return "Download/PhotoCraft/" + name;
            } catch (Throwable t) {
                dialogLog("MediaStore 写入失败: " + t);
                if (item != null) {
                    try {
                        getContentResolver().delete(item, null, null);
                    } catch (Throwable ignored) {
                        // already gone
                    }
                }
            }
        }
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PhotoCraft");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
            return file.getAbsolutePath();
        } catch (Throwable t) {
            dialogLog("应用 Downloads 目录写入失败: " + t);
            return null;
        }
    }

    /** 屏幕逻辑密度（1dp 的物理像素数），供 Rust 校准 egui 的缩放比例。 */
    public float getScreenDensity() {
        return getResources().getDisplayMetrics().density;
    }

    /** 把纯文本放进系统剪贴板：PhotoCraft 的“复制日志”用它。 */
    public boolean setClipboardText(String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("PhotoCraft log", text == null ? "" : text));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setClipboardText failed", t);
            return false;
        }
    }

    /** 把 RGBA8 图像放进系统剪贴板。 */
    public boolean setClipboardImage(int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0 || rgba == null) return false;
        if (rgba.length != width * height * 4) return false;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // ClipData 没有公开的 Bitmap 工厂；隐藏构造函数 Item(Bitmap) 是系统复制图片走的路径。
            Constructor<ClipData.Item> constructor =
                    ClipData.Item.class.getConstructor(Bitmap.class);
            ClipData.Item item = constructor.newInstance(bitmap);
            manager.setPrimaryClip(new ClipData("PhotoCraft", new String[]{"image/*"}, item));
            return true;
        } catch (Throwable t) {
            // Android 10+ 在后台读剪贴板会抛异常；图内剪切板仍然可用，不算错误。
            Log.w(TAG, "setClipboardImage failed", t);
            return false;
        }
    }

    /**
     * 读系统剪贴板里的图像：返回 {@code [w:int][h:int][RGBA...]}，没有图像时返回 null。
     * 头两个 int 用大端序（与 {@link ByteBuffer#wrap(byte[])} 的默认字节序一致）。
     */
    public byte[] getClipboardImage() {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            ClipData.Item item = clip.getItemAt(0);
            Bitmap bitmap = null;
            Uri uri = item.getUri();
            if (uri != null) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        bitmap = BitmapFactory.decodeStream(in, null, options);
                    }
                }
            }
            if (bitmap == null) return null;
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            byte[] out = new byte[8 + width * height * 4];
            ByteBuffer header = ByteBuffer.wrap(out);
            header.putInt(width);
            header.putInt(height);
            ByteBuffer pixels = ByteBuffer.wrap(out, 8, width * height * 4);
            bitmap.copyPixelsToBuffer(pixels);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "getClipboardImage failed", t);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        dialogPoll.removeCallbacks(dialogPollTask);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        // 选择器不再走这条：ActivityResultLauncher 有自己的回调。这里保留 super，
        // 让 GameActivity 的原生胶水继续收到它自己的结果（输入法、权限弹窗等）。
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 官方 API 的回调：打开/另存/选目录都汇到这里，按 kind 分派。 */
    private void onPicked(int kind, List<Uri> uris) {
        dialogLog("选择器返回: kind=" + kind + " 数量=" + (uris == null ? 0 : uris.size()));
        if (uris == null || uris.isEmpty()) {
            nativeDialogDone(kind, 0); // 取消
            return;
        }
        if (kind != KIND_OPEN) {
            Uri uri = uris.get(0);
            persistPermission(uri);
            String name = displayName(uri);
            if (kind == KIND_SAVE && (name.isEmpty() || name.equals("document"))) {
                name = lastSuggested;
            }
            // 保存/选目录只需要 URI；内容在 Rust 的 write 阶段再写。
            // 目录授权（KIND_ADD_TREE）也走这条：Rust 收下 uri 存成可浏览位置。
            dialogLog("选择器完成: kind=" + kind + " name=" + name + " uri=" + uri);
            nativeDialogResult(kind, name, new byte[0], uri.toString());
            nativeDialogDone(kind, 1);
            return;
        }
        readAndDeliver(kind, uris);
    }

    /**
     * 从 {@code content://} 读字节并回推给 Rust。
     *
     * <p>读大文件不能占 UI 线程，否则选完 PSD 会卡住界面；结果从工作线程回推很安全。
     * 系统选择器和内置浏览器（SAF 目录树）共用这一段，字节的落点完全一致。
     */
    /**
     * 外部「打开方式」：直投给 Rust，同时复制一份到公共 Download/PhotoCraft 兜底。
     * native 等待态不保证自动打开，兜底副本让用户能在「打开」里找到。
     */
    private void acceptIncoming(final Uri uri) {
        new Thread(() -> {
            final String name = displayName(uri);
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "PhotoCraft");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dst = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in != null) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "incoming fallback copy failed", t);
            }
            runOnUiThread(() -> toast("已交给修图；未自动打开时可在「打开」里找 Download/PhotoCraft/" + name));
        }, "photocraft-incoming").start();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> onPicked(KIND_OPEN, Collections.singletonList(uri)), 800);
    }

    private void readAndDeliver(final int kind, final List<Uri> uris) {
        final List<Uri> picked = uris;
        new Thread(() -> {
            int count = 0;
            try {
                for (Uri uri : picked) {
                    persistPermission(uri);
                    String name = displayName(uri);
                    byte[] bytes = readBytes(uri);
                    dialogLog("收到文件: " + name + " " + bytes.length + " 字节 " + uri);
                    nativeDialogResult(kind, name, bytes, uri.toString());
                    count++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "file dialog deliver failed", t);
                dialogLog("读取所选文件失败: " + t);
                nativeDialogError(kind, String.valueOf(t));
                count = 0;
            } finally {
                dialogLog("读取完成: count=" + count);
                nativeDialogDone(kind, count);
            }
        }, "photocraft-file-dialog").start();
    }

    /** 官方 SAF 建议：把授权持久化，进程重启后同一个 URI 仍可读写。 */
    private void persistPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
            // CREATE_DOCUMENT 的授权本就只在本生命周期内有效，失败不影响本次保存。
        }
    }

    private static String fileName(String path) {
        if (path == null) return "";
        String name = new File(path).getName();
        return name == null ? "" : name;
    }

    /**
     * MIME for a save dialog. {@link MimeTypeMap} knows nothing about the formats PhotoCraft
     * writes beyond the common image ones, and an "application/octet-stream" save lets some
     * document providers strip the extension — the engine then cannot tell PSD from PSB.
     * The table below is authoritative for our own formats.
     */
    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String ext = name.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "psd":
                case "psb":
                    return "image/vnd.adobe.photoshop";
                case "heic":
                case "heif":
                    return "image/heic";
                case "pcraft":
                    return "application/x-photocraft";
                case "tif":
                case "tiff":
                    return "image/tiff";
                case "tga":
                    return "image/x-tga";
                case "exr":
                    return "image/x-exr";
                case "hdr":
                    return "image/vnd.radiance";
                case "dds":
                    return "image/vnd-ms.dds";
                case "ora":
                    return "image/openraster";
                case "svg":
                    return "image/svg+xml";
                case "webp":
                    return "image/webp";
                case "avif":
                    return "image/avif";
                default:
                    break;
            }
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = c.getString(idx);
                    if (value != null && !value.isEmpty()) name = value;
                }
            }
        } catch (Throwable ignored) {
            // 退回到路径尾段
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            if (last != null && !last.isEmpty()) {
                int slash = last.lastIndexOf('/');
                name = slash >= 0 ? last.substring(slash + 1) : last;
            }
        }
        if (name == null || name.isEmpty()) name = "document";
        return withExtension(uri, name);
    }

    /**
     * SAF display names can lack an extension (numeric provider ids): the engine detects most
     * formats by magic, but the ones routed by suffix (SVG, ORA, workspace bundles) need one.
     */
    private String withExtension(Uri uri, String name) {
        if (name.indexOf('.') >= 0 || name.startsWith(".")) return name;
        try {
            String mime = getContentResolver().getType(uri);
            if (mime != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null && mime.startsWith("image/")) ext = mime.substring("image/".length());
                if (ext != null && !ext.isEmpty()) return name + "." + ext;
            }
        } catch (Throwable ignored) {
            // No type information: keep the bare name.
        }
        return name;
    }

    /**
     * Imported files are handed to the engine as one byte array, so a phone-sized cap applies:
     * a 300 MB PSD would die in the heap before the engine's own memory gate ever saw it. The
     * message says the size and the limit, so "为什么打不开" is answerable from the log window.
     */
    private static final long MAX_IMPORT_BYTES = 512L * 1024 * 1024;

    private byte[] readBytes(Uri uri) throws IOException {
        long size = contentSize(uri);
        if (size > MAX_IMPORT_BYTES) {
            throw new IOException("文件 " + (size / (1024 * 1024)) + " MB 超过导入上限 "
                    + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB：" + uri);
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("openInputStream returned null for " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) Math.min(size, 16 * 1024 * 1024) : 64 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMPORT_BYTES) {
                    throw new IOException("文件超过导入上限 " + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB：" + uri);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** The provider's reported size, or -1 when it does not say. */
    private long contentSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable ignored) {
            // Unknown size: the streaming cap still applies.
        }
        return -1;
    }
}
