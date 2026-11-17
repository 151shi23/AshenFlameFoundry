package com.mineways.plaza;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 一条资源。官方资源与云盘资源共用，字段按 source 分支校验。 */
public final class PlazaItem {

    public final String id;
    public final String source;       // official / cloud
    public final String type;         // map / model / texture / other
    public final String creatorId;
    public final String title;
    public final String summary;
    public final String author;
    public final String license;
    public final String version;
    public final String updatedAt;
    public final String releaseTag;
    public final String assetName;
    public final long size;
    public final String sha256;
    public final String mime;
    public final String previewCover;
    public final String previewModel;
    public final List<String> gallery;
    public final List<String> tags;
    // 云盘
    public final String provider;
    public final String shareUrl;
    public final String accessCode;
    public final String cloudFileName;
    public final String cloudNote;
    public final String reviewedAt;

    private PlazaItem(JSONObject o) {
        id = o.optString("id", "").trim();
        source = o.optString("source", "official").trim().toLowerCase(Locale.ROOT);
        type = o.optString("type", "other").trim().toLowerCase(Locale.ROOT);
        creatorId = o.optString("creator_id", "").trim();
        title = o.optString("title", "").trim();
        summary = o.optString("summary", "").trim();
        author = o.optString("author", "").trim();
        license = o.optString("license", "").trim();
        version = o.optString("version", "").trim();
        updatedAt = o.optString("updated_at", "").trim();
        releaseTag = o.optString("release_tag", "").trim();
        JSONObject file = o.optJSONObject("file");
        assetName = file == null ? "" : file.optString("asset_name", "").trim();
        size = file == null ? -1 : file.optLong("size", -1);
        sha256 = file == null ? "" : file.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        mime = file == null ? "" : file.optString("mime", "").trim();
        JSONObject pv = o.optJSONObject("preview");
        previewCover = pv == null ? "" : pv.optString("cover", "").trim();
        previewModel = pv == null ? "" : pv.optString("model_3d", "").trim();
        gallery = readStrings(pv == null ? null : pv.optJSONArray("gallery"));
        tags = readStrings(o.optJSONArray("tags"));
        JSONObject cloud = o.optJSONObject("cloud");
        provider = o.optString("provider", cloud == null ? "" : cloud.optString("provider", ""))
                .trim().toLowerCase(Locale.ROOT);
        shareUrl = cloud == null ? "" : cloud.optString("share_url", "").trim();
        accessCode = cloud == null ? "" : cloud.optString("access_code", "").trim();
        cloudFileName = cloud == null ? "" : cloud.optString("file_name", "").trim();
        cloudNote = cloud == null ? "" : cloud.optString("note", "").trim();
        reviewedAt = o.optString("reviewed_at", "").trim();
    }

    public static PlazaItem parse(JSONObject o) {
        if (o == null) return null;
        PlazaItem it = new PlazaItem(o);
        return it.valid() ? it : null;
    }

    private boolean valid() {
        if (id.length() == 0 || id.length() > 80) return false;
        if (!id.matches("[a-zA-Z0-9._-]+")) return false;
        if (!("official".equals(source) || "cloud".equals(source))) return false;
        if (!("map".equals(type) || "model".equals(type)
                || "texture".equals(type) || "other".equals(type))) return false;
        if (title.length() == 0 || title.length() > 120) return false;
        if (summary.length() > 500 || author.length() > 80 || license.length() == 0) return false;
        if (updatedAt.length() == 0) return false;
        if (creatorId.length() > 80) return false;

        if (isOfficial()) {
            if (!PlazaRepo.validTag(releaseTag)) return false;
            if (!PlazaRepo.validAsset(assetName)) return false;
            if (size <= 0 || !PlazaRepo.validSha(sha256)) return false;
            if (!previewCover.isEmpty() && !PlazaRepo.trustedPreview(previewCover, releaseTag)) {
                return false;
            }
            if (!previewModel.isEmpty() && !PlazaRepo.trustedPreview(previewModel, releaseTag)) {
                return false;
            }
            for (String g : gallery) {
                if (!PlazaRepo.trustedPreview(g, releaseTag)) return false;
            }
        } else {
            if (!PlazaRepo.https(shareUrl)) return false;
            if (!providerAllowed(provider)) return false;
            if (reviewedAt.length() == 0) return false;
            if (cloudFileName.length() > 180) return false;
        }
        return true;
    }

    public static boolean providerAllowed(String p) {
        return "baidu".equals(p) || "quark".equals(p) || "aliyun".equals(p)
                || "123".equals(p) || "lanzou".equals(p) || "other".equals(p);
    }

    public static String providerLabel(String p) {
        if ("baidu".equals(p)) return "百度网盘";
        if ("quark".equals(p)) return "夸克网盘";
        if ("aliyun".equals(p)) return "阿里云盘";
        if ("123".equals(p)) return "123 云盘";
        if ("lanzou".equals(p)) return "蓝奏云";
        return "云盘";
    }

    public boolean isOfficial() {
        return "official".equals(source);
    }

    public boolean isCloud() {
        return "cloud".equals(source);
    }

    public String downloadUrl() {
        return isOfficial() ? PlazaRepo.releaseUrl(releaseTag, assetName) : shareUrl;
    }

    public String downloadName() {
        if (isOfficial()) return assetName;
        return cloudFileName.isEmpty() ? (id + ".zip") : cloudFileName;
    }

    public String sizeText() {
        return size > 0 ? PlazaRepo.humanSize(size) : "大小未标";
    }

    public String typeLabel() {
        if ("map".equals(type)) return "地图";
        if ("model".equals(type)) return "人物模型";
        if ("texture".equals(type)) return "纹理包";
        return "通用资源";
    }

    public String sourceLabel() {
        return isOfficial() ? "官方" : providerLabel(provider);
    }

    public String ext() {
        String n = downloadName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    private static List<String> readStrings(JSONArray a) {
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < 12; i++) {
            String s = a.optString(i, "").trim();
            if (s.length() > 0 && s.length() <= 160) out.add(s);
        }
        return out;
    }
}
