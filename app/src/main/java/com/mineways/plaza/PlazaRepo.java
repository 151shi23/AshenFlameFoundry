package com.mineways.plaza;

import java.net.URI;
import java.util.Locale;

/** 资源仓库的固定身份与 URL 规则。 */
public final class PlazaRepo {

    public static final String OWNER = "151shi23";
    public static final String REPO = "MinewaysMobile-Resources";
    public static final String REPO_PATH = OWNER + "/" + REPO;
    public static final String RAW_BASE = "https://raw.githubusercontent.com/" + REPO_PATH + "/main/";
    public static final String MIRROR_BASE = "https://cdn.jsdelivr.net/gh/" + REPO_PATH + "@main/";
    public static final String RELEASE_BASE =
            "https://github.com/" + REPO_PATH + "/releases/download/";
    public static final String DEFAULT_CATALOG = RAW_BASE + "catalog.json";
    public static final String DEFAULT_MIRROR = MIRROR_BASE + "catalog.json";
    public static final String DEFAULT_SUBMIT =
            "https://github.com/" + REPO_PATH + "/issues/new?template=resource.yml";

    private PlazaRepo() {
    }

    public static String raw(String file) {
        return RAW_BASE + cleanFile(file);
    }

    public static String mirror(String file) {
        return MIRROR_BASE + cleanFile(file);
    }

    public static String releaseUrl(String tag, String asset) {
        return RELEASE_BASE + cleanTag(tag) + "/" + cleanAsset(asset);
    }

    public static boolean validTag(String tag) {
        return tag != null && tag.matches("res-[a-z0-9-]+-v[0-9][a-z0-9.]*");
    }

    public static boolean validAsset(String asset) {
        if (asset == null || asset.isEmpty() || asset.length() > 180) return false;
        if (asset.contains("/") || asset.contains("\\") || asset.contains("..")) return false;
        return asset.matches("[A-Za-z0-9._()\\-\\u4e00-\\u9fa5 ]+");
    }

    public static boolean validSha(String sha) {
        return sha != null && sha.matches("[0-9a-f]{64}");
    }

    public static String cleanFile(String file) {
        String s = file == null ? "" : file.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    public static String cleanTag(String tag) {
        return tag == null ? "" : tag.trim();
    }

    public static String cleanAsset(String asset) {
        return asset == null ? "" : asset.trim();
    }

    /** 只信任本仓库的 Releases 路径，拒绝任意 github.com 链接。 */
    public static boolean trustedReleaseUrl(String url, String tag, String asset) {
        if (url == null || tag == null || asset == null) return false;
        if (!validTag(tag) || !validAsset(asset)) return false;
        return url.equals(releaseUrl(tag, asset));
    }

    /** 预览也必须是该资源 Release 下的资产，不能引用其他 Release/仓库。 */
    public static boolean trustedPreview(String url, String tag) {
        if (url == null || url.isEmpty() || tag == null || !validTag(tag)) return false;
        String prefix = RELEASE_BASE + tag + "/";
        if (!url.startsWith(prefix)) return false;
        String asset = url.substring(prefix.length());
        return !asset.isEmpty() && !asset.contains("?") && !asset.contains("#")
                && validAsset(asset);
    }

    public static boolean https(String url) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String humanSize(long n) {
        if (n < 0) return "";
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }
}
