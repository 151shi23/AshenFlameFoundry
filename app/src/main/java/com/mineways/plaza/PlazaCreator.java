package com.mineways.plaza;

import org.json.JSONObject;

/** 创作者资料。 */
public final class PlazaCreator {
    public final String id;
    public final String name;
    public final String avatar;
    public final String bio;
    public final String github;
    public final boolean official;

    private PlazaCreator(JSONObject o) {
        id = o.optString("id", "").trim();
        name = o.optString("name", "").trim();
        avatar = o.optString("avatar", "").trim();
        bio = o.optString("bio", "").trim();
        JSONObject links = o.optJSONObject("links");
        github = links == null ? "" : links.optString("github", "").trim();
        official = o.optBoolean("official", false);
    }

    public static PlazaCreator parse(JSONObject o) {
        if (o == null) return null;
        PlazaCreator c = new PlazaCreator(o);
        if (!c.id.matches("[a-zA-Z0-9._-]{1,80}")) return null;
        if (c.name.length() == 0 || c.name.length() > 80) return null;
        if (!c.avatar.isEmpty() && !PlazaRepo.https(c.avatar)) return null;
        if (!c.github.isEmpty() && !PlazaRepo.https(c.github)) return null;
        return c;
    }
}
