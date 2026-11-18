package com.mineways.plaza;

import org.json.JSONObject;

import java.util.Locale;

/** search.json 里的主题分类：id + 显示名 + 类型 + 标签。 */
public final class PlazaCategory {
    public final String id;
    public final String label;
    public final String type;   // all / map / model / texture / other
    public final String tag;

    private PlazaCategory(JSONObject o) {
        id = o.optString("id", "").trim();
        label = o.optString("label", "").trim();
        type = o.optString("type", "all").trim().toLowerCase(Locale.ROOT);
        tag = o.optString("tag", "").trim();
    }

    public static PlazaCategory parse(JSONObject o) {
        if (o == null) return null;
        PlazaCategory c = new PlazaCategory(o);
        if (!c.id.matches("[a-zA-Z0-9._-]{1,80}")) return null;
        if (c.label.length() == 0 || c.label.length() > 16) return null;
        if (!("all".equals(c.type) || "map".equals(c.type) || "model".equals(c.type)
                || "texture".equals(c.type) || "other".equals(c.type))) return null;
        if (c.tag.length() > 40) return null;
        return c;
    }
}
