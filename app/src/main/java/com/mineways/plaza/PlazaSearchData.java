package com.mineways.plaza;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** search.json：热门词、同义词、资源别名。 */
public final class PlazaSearchData {
    public final List<String> hot;
    public final Map<String, List<String>> synonyms;
    public final Map<String, List<String>> aliases;

    private PlazaSearchData(JSONObject o) {
        hot = readList(o.optJSONArray("hot"));
        synonyms = readMap(o.optJSONObject("synonyms"));
        aliases = readMap(o.optJSONObject("aliases"));
    }

    public static PlazaSearchData parse(JSONObject o) {
        return o == null ? new PlazaSearchData(new JSONObject()) : new PlazaSearchData(o);
    }

    private static List<String> readList(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length() && out.size() < 30; i++) {
                String s = a.optString(i, "").trim();
                if (s.length() > 0 && s.length() <= 40) out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, List<String>> readMap(JSONObject o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o == null) return out;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext() && out.size() < 200) {
            String k = it.next();
            if (k == null || k.trim().isEmpty()) continue;
            JSONArray a = o.optJSONArray(k);
            if (a == null) continue;
            List<String> vals = new ArrayList<>();
            for (int i = 0; i < a.length() && vals.size() < 12; i++) {
                String v = a.optString(i, "").trim();
                if (v.length() > 0 && v.length() <= 60) vals.add(v);
            }
            out.put(k, vals);
        }
        return out;
    }
}
