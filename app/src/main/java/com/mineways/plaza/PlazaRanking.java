package com.mineways.plaza;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个排行榜。 */
public final class PlazaRanking {
    public static final class Entry {
        public final String id;
        public final int rank;
        public final long score;
        public final int delta;

        Entry(String id, int rank, long score, int delta) {
            this.id = id;
            this.rank = rank;
            this.score = score;
            this.delta = delta;
        }
    }

    public final String id;
    public final String kind;      // editorial / new / downloads
    public final String title;
    public final String type;      // all / map / model / texture / other
    public final String window;
    public final List<Entry> entries;

    private PlazaRanking(JSONObject o) {
        id = o.optString("id", "").trim();
        kind = o.optString("kind", "editorial").trim().toLowerCase(java.util.Locale.ROOT);
        title = o.optString("title", "").trim();
        type = o.optString("type", "all").trim().toLowerCase(java.util.Locale.ROOT);
        window = o.optString("window", "").trim();
        List<Entry> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("items");
        if (a != null) {
            for (int i = 0; i < a.length() && out.size() < 100; i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String rid = e.optString("id", "").trim();
                if (rid.isEmpty()) continue;
                out.add(new Entry(rid, e.optInt("rank", i + 1),
                        e.optLong("score", 0), e.optInt("delta", 0)));
            }
        }
        entries = Collections.unmodifiableList(out);
    }

    public static PlazaRanking parse(JSONObject o) {
        if (o == null) return null;
        PlazaRanking r = new PlazaRanking(o);
        if (!r.id.matches("[a-zA-Z0-9._-]{1,80}")) return null;
        if (!("editorial".equals(r.kind) || "new".equals(r.kind) || "downloads".equals(r.kind))) {
            return null;
        }
        if (r.title.length() == 0 || r.title.length() > 60) return null;
        if (!("all".equals(r.type) || "map".equals(r.type) || "model".equals(r.type)
                || "texture".equals(r.type) || "other".equals(r.type))) return null;
        return r;
    }
}
