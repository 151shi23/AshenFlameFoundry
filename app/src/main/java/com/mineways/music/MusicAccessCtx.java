package com.mineways.music;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 把云控 FLAG 与真实设备（机型 / 已装包）接进 {@link MusicAccess}。
 * 判定逻辑一行都不在这里，这里只搬事实、带缓存。
 */
final class MusicAccessCtx {

    /** 正常设备枚举到几百个包；少于这个数就认为 Android 11+ 的包可见性被限制了。 */
    private static final int VISIBLE_ENOUGH = 20;

    private static volatile Boolean sReadable;

    private MusicAccessCtx() {
    }

    static MusicAccess.Result evaluate(Context c) {
        final Context app = c.getApplicationContext();
        MusicAccess.Flags flags = new MusicAccess.Flags() {
            @Override
            public String get(String key, String def) {
                return CloudGate.flag(key, def);
            }
        };
        MusicAccess.Env env = new MusicAccess.Env() {
            @Override
            public boolean modelHit(String spec) {
                return com.mineways.DeviceId.hitAny(spec);
            }

            @Override
            public boolean installed(String pkg) {
                try {
                    app.getPackageManager().getPackageInfo(pkg, 0);
                    return true;
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public boolean packagesReadable() {
                Boolean cached = sReadable;
                if (cached != null) {
                    return cached;
                }
                boolean v;
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    v = true;
                } else {
                    try {
                        v = app.getPackageManager().getInstalledPackages(0).size() >= VISIBLE_ENOUGH;
                    } catch (Throwable t) {
                        v = false;
                    }
                }
                sReadable = v;
                return v;
            }
        };
        return MusicAccess.evaluate(flags, env);
    }

    static void invalidate() {
        sReadable = null;
    }
}
