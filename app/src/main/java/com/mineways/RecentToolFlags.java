package com.mineways;

import com.mineways.cloud.CloudFlags;

/**
 * 近期大功能的云控总闸。
 *
 * <p>和普通 {@code FLAG} 同源，支持 if / rollout / from / until / minv / maxv 与链式覆盖。
 * 默认全开，云端关闭后入口、页面和对应的转换目标一起下线。
 *
 * <pre>
 * vault_on=0          文件保险箱
 * share_on=0          设置分享码（导出 / 导入）
 * conv_on=0           格式转换整页
 * conv_pmx_on=0       PMX → GLB
 * conv_pmx_fbx_on=0   PMX → FBX（依赖 PMX 通道）
 * bb_pen3d_on=0       内置 Blockbench 3D Pen
 * bb_ragdoll_on=0     内置 Blockbench Full Physics Ragdoll
 * </pre>
 */
public final class RecentToolFlags {

    private RecentToolFlags() {
    }

    public static boolean vault() {
        return bool("vault_on", true);
    }

    public static boolean share() {
        return bool("share_on", true);
    }

    public static boolean conv() {
        return bool("conv_on", true);
    }

    public static boolean pmx() {
        return conv() && bool("conv_pmx_on", true);
    }

    public static boolean pmxFbx() {
        return pmx() && bool("conv_pmx_fbx_on", true);
    }

    public static boolean bbPen3d() {
        return bool("bb_pen3d_on", true);
    }

    public static boolean bbRagdoll() {
        return bool("bb_ragdoll_on", true);
    }

    private static boolean bool(String key, boolean def) {
        try {
            return CloudFlags.bool(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
