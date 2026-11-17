package com.mineways.plaza;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 本周精选。 */
public final class PlazaFeatured {
    public final String week;
    public final String title;
    public final List<String> ids;

    private PlazaFeatured(JSONObject o) {
        week = o.optString("week", "").trim();
        title = o.optString("title", "本周精选").trim();
        List<String> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("ids");
        if (a != null) {
            for (int i = 0; i < a.length() && out.size() < 12; i++) {
                String s = a.optString(i, "").trim();
                if (s.length() > 0) out.add(s);
            }
        }
        ids = Collections.unmodifiableList(out);
    }

    public static PlazaFeatured parse(JSONObject o) {
        if (o == null) return null;
        PlazaFeatured f = new PlazaFeatured(o);
        if (f.week.length() > 20 || f.title.length() > 40) return null;
        return f;
    }
}
