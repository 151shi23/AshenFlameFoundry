package com.mineways.plaza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 本地搜索与推荐；不采集用户数据。 */
public final class PlazaSearchIndex {

    private final PlazaCatalog.Snapshot snapshot;
    private final Map<String, List<String>> aliases = new HashMap<>();
    private final Map<String, List<String>> synonyms = new HashMap<>();
    private final List<String> hot;

    public PlazaSearchIndex(PlazaCatalog.Snapshot s) {
        snapshot = s;
        if (s != null && s.search != null) {
            aliases.putAll(s.search.aliases);
            synonyms.putAll(s.search.synonyms);
            hot = s.search.hot;
        } else {
            hot = Collections.emptyList();
        }
    }

    public List<String> hotWords() {
        return hot;
    }

    public List<PlazaItem> search(String query, String type) {
        if (snapshot == null) return new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            List<PlazaItem> out = snapshot.itemsOf(type);
            out.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
            return out;
        }
        Set<String> terms = expand(q);
        List<Score> scored = new ArrayList<>();
        for (PlazaItem it : snapshot.itemsOf(type)) {
            int score = score(it, q, terms);
            if (score > 0) scored.add(new Score(it, score));
        }
        scored.sort((a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            return b.item.updatedAt.compareTo(a.item.updatedAt);
        });
        List<PlazaItem> out = new ArrayList<>();
        for (Score s : scored) out.add(s.item);
        return out;
    }

    public List<PlazaItem> recommend(PlazaItem seed, int limit) {
        if (snapshot == null || seed == null) return new ArrayList<>();
        Set<String> seedTags = new HashSet<>(seed.tags);
        List<Score> scored = new ArrayList<>();
        for (PlazaItem it : snapshot.items) {
            if (it.id.equals(seed.id)) continue;
            int score = 0;
            if (!it.creatorId.isEmpty() && it.creatorId.equals(seed.creatorId)) score += 80;
            for (String t : it.tags) if (seedTags.contains(t)) score += 30;
            if (it.type.equals(seed.type)) score += 10;
            if (score > 0) scored.add(new Score(it, score));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<PlazaItem> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && out.size() < limit; i++) out.add(scored.get(i).item);
        return out;
    }

    /** 首页/空搜索的“猜你喜欢”：优先官方、精选、近期。 */
    public List<PlazaItem> picks(int limit) {
        if (snapshot == null) return new ArrayList<>();
        List<PlazaItem> out = new ArrayList<>();
        if (snapshot.featured != null) {
            for (String id : snapshot.featured.ids) {
                PlazaItem it = snapshot.byId.get(id);
                if (it != null) out.add(it);
                if (out.size() >= limit) return out;
            }
        }
        List<PlazaItem> rest = new ArrayList<>(snapshot.items);
        rest.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
        for (PlazaItem it : rest) {
            if (!out.contains(it)) out.add(it);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private int score(PlazaItem it, String q, Set<String> terms) {
        String title = norm(it.title);
        String author = norm(it.author);
        String creator = norm(it.creatorId);
        String summary = norm(it.summary);
        int score = 0;
        if (title.equals(q)) score += 1000;
        else if (title.startsWith(q)) score += 700;
        else if (title.contains(q)) score += 450;
        for (String t : it.tags) {
            String tag = norm(t);
            if (tag.equals(q)) score += 350;
            else if (tag.contains(q)) score += 180;
        }
        for (String a : aliases.getOrDefault(it.id, Collections.emptyList())) {
            if (norm(a).contains(q)) score += 260;
        }
        if (author.contains(q)) score += 160;
        if (creator.contains(q)) score += 150;
        if (summary.contains(q)) score += 70;
        for (String term : terms) {
            if (term.equals(q)) continue;
            if (title.contains(term)) score += 80;
            if (summary.contains(term)) score += 25;
        }
        return score;
    }

    private Set<String> expand(String q) {
        Set<String> out = new HashSet<>();
        out.add(q);
        for (Map.Entry<String, List<String>> e : synonyms.entrySet()) {
            String k = norm(e.getKey());
            if (q.contains(k) || k.contains(q)) out.addAll(e.getValue());
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    private static final class Score {
        final PlazaItem item;
        final int score;

        Score(PlazaItem item, int score) {
            this.item = item;
            this.score = score;
        }
    }
}
