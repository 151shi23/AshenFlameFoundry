package com.mineways.plaza;

import com.mineways.cloud.CloudFlags;

/** 资源广场云控开关。所有键都走统一 CloudFlags，支持 if / rollout / 时间窗 / 版本窗。 */
public final class PlazaFlags {

    private PlazaFlags() {
    }

    public static boolean on() {
        return bool("plaza_on", true);
    }

    public static boolean homeBanner() {
        return on() && bool("plaza_home_banner", true);
    }

    public static int entryOrder() {
        try {
            return CloudFlags.num("plaza_entry_order", 0, -999, 999);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean allowCloud() {
        return on() && bool("plaza_allow_cloud", true);
    }

    public static boolean search() {
        return on() && bool("plaza_search", true);
    }

    public static boolean recommend() {
        return on() && bool("plaza_recommend", true);
    }

    public static boolean featured() {
        return on() && bool("plaza_featured", true);
    }

    public static boolean rankings() {
        return on() && bool("plaza_rankings", true);
    }

    public static boolean collections() {
        return on() && bool("plaza_collections", true);
    }

    public static boolean creators() {
        return on() && bool("plaza_creators", true);
    }

    public static boolean preview3d() {
        return on() && bool("plaza_preview3d", true);
    }

    public static boolean library() {
        return on() && bool("plaza_library", true);
    }

    public static boolean updates() {
        return on() && bool("plaza_updates", true);
    }

    public static String catalogUrl() {
        return str("plaza_catalog_url", PlazaRepo.DEFAULT_CATALOG);
    }

    public static String mirrorUrl() {
        return str("plaza_mirror_url", PlazaRepo.DEFAULT_MIRROR);
    }

    public static int cacheHours() {
        try {
            return CloudFlags.num("plaza_cache_hours", 6, 1, 168);
        } catch (Throwable t) {
            return 6;
        }
    }

    public static String submitUrl() {
        return str("plaza_submit_url", PlazaRepo.DEFAULT_SUBMIT);
    }

    private static String str(String key, String def) {
        try {
            String v = CloudFlags.str(key, def);
            return v == null || v.trim().isEmpty() ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean bool(String key, boolean def) {
        try {
            return CloudFlags.bool(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
