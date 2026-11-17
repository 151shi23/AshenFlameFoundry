package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * PhotoCraft 的启动闸门。
 * <p>
 * PhotoCraft 是 Rust + egui + wgpu 写的完整修图软件，整个界面和引擎都静态链接在
 * {@code libphotocraft_android.so} 里（入口 {@code android_main}），Java 侧不需要渲染代码，
 * 只需要 {@code androidx.games} 的 {@code GameActivity} 提供窗口 / 输入 / 生命周期胶水。
 * <p>
 * 为什么不直接点 GameActivity：本包只带了 {@code arm64-v8a} 一份产物。若设备是
 * armeabi-v7a / x86_64，GameActivity 会在 {@code System.loadLibrary} 时抛
 * {@code UnsatisfiedLinkError} 直接崩，所以先在这里判断 ABI：不支持就弹一句说明并退回首页，
 * 支持才拉起真正的 GameActivity，然后自己立刻结束，不留在返回栈里。
 * <p>
 * 渲染走 wgpu：Vulkan 1.1+ 设备走 GPU，GPU 不可用时引擎内部会退回 CPU 画布，
 * 因此清单里把 {@code android.hardware.vulkan.version} 声明成可选依赖。
 */
public class PhotoCraftActivity extends Activity {

    /** GameActivity 的子类（多接了 SAF 文件选择），类名不能改。 */
    private static final String GAME_ACTIVITY = "com.mineways.PhotoCraftGameActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!hasArm64()) {
            showUnsupported();
            return;
        }
        try {
            // 分享码导入的预设：引擎启动时只读一次文件，所以必须在拉起 GameActivity 之前写回。
            com.mineways.share.PresetBridge.applyPendingFile(this);
            startActivity(new Intent().setClassName(this, GAME_ACTIVITY));
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Toast.makeText(this, getString(R.string.photocraft_launch_failed, msg),
                    Toast.LENGTH_LONG).show();
        }
        // 真正的界面在 GameActivity 里，这层壳拉起后就没用了；退掉它，
        // 从 PhotoCraft 退出时直接回首页，而不是回到一张空白页。
        finish();
    }

    /** 本包自带的 native 库只覆盖 arm64-v8a。 */
    private boolean hasArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private void showUnsupported() {
        String abis = Build.SUPPORTED_ABIS.length == 0
                ? "未知" : String.join(", ", Build.SUPPORTED_ABIS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.photocraft_title)
                .setMessage(getString(R.string.photocraft_unsupported, abis))
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
