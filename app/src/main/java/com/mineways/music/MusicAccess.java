package com.mineways.music;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐三档能力的纯判定层：<b>总闸 / 软件内播放 / 系统外播放</b>，每档都能按
 * 机型与「已安装 / 未安装的包名」单独开关，并带一条可自定义的「不支持」提示语。
 *
 * <pre>
 * FLAG key=music_off    to=1            # 总闸：关掉（内置功能默认就是开的，不需要 music_on）
 * FLAG key=music_on     to=0            # 等价写法
 * FLAG key=music_in_off to=1            # 软件内播放（默认开）
 * FLAG key=music_bg_off to=1            # 系统外播放（默认开）：退后台/锁屏/悬浮条
 *
 * # 每档都能加同一组条件（前缀 music_ / music_in_ / music_bg_）：
 * FLAG key=music_models      to=K70*,Xiaomi 14   # 只在这些机型上开（* 通配）
 * FLAG key=music_models_not  to=Pixel 6          # 这些机型上关
 * FLAG key=music_pkg         to=com.a,com.b      # 装了才开（默认任一；pkg_all=1 要全装）
 * FLAG key=music_pkg_none    to=com.blocker      # 装了它就关（= 未安装才开）
 * FLAG key=music_pkg_strict  to=1                # 读不到包列表按「没装」处理（默认放行）
 * FLAG key=music_tip         to=这台设备暂不支持 # 「不支持」提示语；写 off 表示不弹
 * </pre>
 *
 * <p>条件只决定「开不开」，不改变能力本身。任何读不到的配置都按「没配」处理（不限制），
 * 只有明确写了 off 才关 —— 与云控其它开关的 fail-open 口径一致。
 */
public final class MusicAccess {

    public static final String CAP_MAIN = "music_";
    public static final String CAP_IN = "music_in_";
    public static final String CAP_BG = "music_bg_";

    /** 判定所需的环境；安卓侧由 {@code MusicAccessCtx} 提供，断言里用假的。 */
    public interface Env {
        /** 机型是否命中（内部型号 / 设置里显示的名字，支持 * 通配）。 */
        boolean modelHit(String spec);

        /** 某个包是否已安装。 */
        boolean installed(String pkg);

        /** 能否正常枚举应用列表（Android 11+ 包可见性受限时为 false）。 */
        boolean packagesReadable();
    }

    /** 读 FLAG 的最小接口，安卓侧直接包一层 FeatureGate。 */
    public interface Flags {
        String get(String key, String def);
    }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;     // 机器可读的原因（也用于关于页/诊断）
        public final String tip;        // 给人看的一句话；空 = 不显示

        Decision(boolean allowed, String reason, String tip) {
            this.allowed = allowed;
            this.reason = reason;
            this.tip = tip;
        }

        @Override
        public String toString() {
            return (allowed ? "允许" : "拒绝") + " · " + reason
                    + (tip.length() == 0 ? "" : " · " + tip);
        }
    }

    public static final class Result {
        public final Decision main;
        public final Decision inApp;
        public final Decision background;
        /** 只影响日志：条件读不全时的说明，不给用户看。 */
        public final List<String> notes = new ArrayList<>();

        Result(Decision main, Decision inApp, Decision background) {
            this.main = main;
            this.inApp = inApp;
            this.background = background;
        }

        public boolean anyAllowed() {
            return main.allowed;
        }
    }

    private MusicAccess() {
    }

    public static Result evaluate(Flags f, Env env) {
        List<String> notes = new ArrayList<>();
        // 内置功能：默认开，云控只负责关（music_off=1 或 music_on=0）
        Decision main = grade(f, env, CAP_MAIN, "音乐", notes);
        Decision in = main.allowed
                ? grade(f, env, CAP_IN, "软件内播放", notes)
                : new Decision(false, main.reason, main.tip);
        Decision bg = main.allowed
                ? grade(f, env, CAP_BG, "系统外播放", notes)
                : new Decision(false, main.reason, main.tip);
        Result r = new Result(main, in, bg);
        r.notes.addAll(notes);
        return r;
    }

    private static Decision grade(Flags f, Env env, String prefix, String label, List<String> notes) {
        String tip = tipOf(f, prefix, label);
        if ("1".equals(v(f, prefix + "off"))) {
            return deny("OFF", tip, label + "已被云端停发");
        }
        if ("0".equals(v(f, prefix + "on"))) {
            return deny("OFF", tip, label + "已被云端关闭");
        }

        String models = v(f, prefix + "models");
        if (models.length() > 0) {
            boolean hit;
            try {
                hit = env.modelHit(models);
            } catch (Throwable t) {
                hit = true;                                  // 读不到机型 = 不拦
                notes.add(prefix + "models 求值失败，按放行");
            }
            if (!hit) {
                return deny("MODEL_MISS", tip, "机型不在" + label + "的开放范围（" + models + "）");
            }
        }
        String notModels = v(f, prefix + "models_not");
        if (notModels.length() > 0) {
            boolean hit;
            try {
                hit = env.modelHit(notModels);
            } catch (Throwable t) {
                hit = false;
            }
            if (hit) {
                return deny("MODEL_EXCLUDED", tip, "该机型已关闭" + label + "（" + notModels + "）");
            }
        }

        List<String> need = split(v(f, prefix + "pkg"));
        if (!need.isEmpty()) {
            boolean all = "1".equals(v(f, prefix + "pkg_all"));
            List<String> missing = new ArrayList<>();
            for (String pkg : need) {
                if (!safeInstalled(env, pkg)) {
                    missing.add(pkg);
                }
            }
            boolean ok = all ? missing.isEmpty() : missing.size() < need.size();
            if (!ok) {
                boolean readable = safeReadable(env);
                boolean strict = "1".equals(v(f, prefix + "pkg_strict"));
                if (!readable && !strict) {
                    // 包可见性受限且没要求严格：宁可放行，也不因为读不到就砍功能
                    notes.add(prefix + "pkg 读不到应用列表，按放行");
                } else {
                    return deny("PKG_MISSING", tip,
                            "未安装：" + join(missing, "、"));
                }
            }
        }

        List<String> blocked = split(v(f, prefix + "pkg_none"));
        if (!blocked.isEmpty()) {
            List<String> present = new ArrayList<>();
            for (String pkg : blocked) {
                if (safeInstalled(env, pkg)) {
                    present.add(pkg);
                }
            }
            if (!present.isEmpty()) {
                return deny("PKG_PRESENT", tip,
                        "检测到 " + join(present, "、") + "，已关闭" + label);
            }
        }
        return new Decision(true, "OK", "");
    }

    private static Decision deny(String code, String tip, String detail) {
        // 提示语写给用户看：自定义优先；写 off/none 表示不弹
        String shown = tip;
        if ("off".equalsIgnoreCase(tip) || "none".equalsIgnoreCase(tip)) {
            shown = "";
        }
        return new Decision(false, code + "：" + detail, shown);
    }

    private static String tipOf(Flags f, String prefix, String label) {
        String custom = v(f, prefix + "tip");
        if (custom.length() > 0) {
            return custom;
        }
        return "这台设备暂不支持" + label;
    }

    private static boolean safeInstalled(Env env, String pkg) {
        try {
            return env.installed(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeReadable(Env env) {
        try {
            return env.packagesReadable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String v(Flags f, String key) {
        try {
            String s = f.get(key, "");
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String s : spec.split("[,，\\s]+")) {
            if (s != null && s.trim().length() > 0) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
