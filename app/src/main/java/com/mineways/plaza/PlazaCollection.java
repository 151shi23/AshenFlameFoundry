package com.mineways.plaza;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 主题合集 / 官方创作者合集。 */
public final class PlazaCollection {
    public final String id;
    public final String kind;       // theme / creator
    public final String title;
    public final String summary;
    public final String creatorId;
    public final String cover;
    public final boolean official;
    public final String curatedBy;
    public final List<String> items;

    private PlazaCollection(JSONObject o) {
        id = o.optString("id", "").trim();
        kind = o.optString("kind", "theme").trim().toLowerCase(java.util.Locale.ROOT);
        title = o.optString("title", "").trim();
        summary = o.optString("summary", "").trim();
        creatorId = o.optString("creator_id", "").trim();
        cover = o.optString("cover", "").trim();
        official = o.optBoolean("official", false);
        curatedBy = o.optString("curated_by", "").trim();
        List<String> ids = new ArrayList<>();
        JSONArray a = o.optJSONArray("items");
        if (a != null) {
            for (int i = 0; i < a.length() && ids.size() < 200; i++) {
                String s = a.optString(i, "").trim();
                if (s.length() > 0) ids.add(s);
            }
        }
        items = Collections.unmodifiableList(ids);
    }

    public static PlazaCollection parse(JSONObject o) {
        if (o == null) return null;
        PlazaCollection c = new PlazaCollection(o);
        if (!c.id.matches("[a-zA-Z0-9._-]{1,80}")) return null;
        if (!("theme".equals(c.kind) || "creator".equals(c.kind))) return null;
        if (c.title.length() == 0 || c.title.length() > 120) return null;
        if (c.summary.length() > 300) return null;
        if (!c.cover.isEmpty() && !PlazaRepo.https(c.cover)) return null;
        if ("creator".equals(c.kind) && c.creatorId.isEmpty()) return null;
        return c;
    }

    public boolean isCreator() {
        return "creator".equals(kind);
    }
}
