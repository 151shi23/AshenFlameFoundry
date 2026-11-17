package com.mineways.gjgjx;

import android.content.Context;

import com.mineways.cloud.CloudFlags;

/**
 * 「搞机工具箱」的云控开关。
 *
 * <p>与其余云控一致：只看云端下发的 {@code FLAG}，本机不覆盖 ——
 * 这个工具能直接对另一台手机执行 ADB 命令，开关必须握在云端手里。
 *
 * <pre>
 * FLAG key=gjgjx_on     to=1|0    总开关；0 = 入口隐藏、直接打开也会被拦下
 * FLAG key=gjgjx_usb    to=1|0    只放行页面浏览，不许连 USB（总开关开着时才生效）
 * FLAG key=gjgjx_notice to=文字   关闭 USB 时给用户看的理由，原样显示在连接失败提示里
 * </pre>
 *
 * <p>任何键读不到或写坏都按出厂值处理（1），云控故障不会把功能误关。
 */
public final class GjgjxFlags {

    public static final String ON = "gjgjx_on";
    public static final String USB = "gjgjx_usb";
    public static final String NOTICE = "gjgjx_notice";

    private GjgjxFlags() {
    }

    /** 总开关：0 时整页不可用。 */
    public static boolean on(Context ctx) {
        return CloudFlags.bool(ON, true);
    }

    /** 是否允许通过 USB 连机；总开关关掉时永远是 false。 */
    public static boolean usb(Context ctx) {
        return on(ctx) && CloudFlags.bool(USB, true);
    }

    /** 云端让用户看到的关闭理由（可空）。 */
    public static String notice(Context ctx) {
        return CloudFlags.str(NOTICE, "").trim();
    }
}
