package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.cloud.CloudFlags;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

/**
 * 串词工具箱的数据层。
 *
 * <pre>
 *   内置串词   assets/phrase/phrases.json     只读，改了要重新打包
 *   用户串词   本机 prefs（JSON 数组）         应用内新增 / 编辑
 *   隐藏清单   prefs 里记内置条目的 id         "删除"内置条目 = 藏起来，不动 assets
 *   谐音表     assets/phrase/homophones.json + prefs 覆盖项
 * </pre>
 *
 * 全部纯本地，不联网。
 */
public final class PhraseStore {

    /** 一条串词。 */
    public static final class Item {
        public final String id;
        public final String cat;
        public final String text;
        /** 内置条目只能「隐藏」，用户条目可编辑 / 删除。 */
        public final boolean builtin;

        Item(String id, String cat, String text, boolean builtin) {
            this.id = id;
            this.cat = cat;
            this.text = text;
            this.builtin = builtin;
        }
    }

    private static final String PREFS = "phrase_box";
    private static final String K_USER = "user_items";
    private static final String K_HIDDEN = "hidden_builtin";
    private static final String K_HOMO = "homo_overrides";
    private static final String K_RANDOM = "homo_random_max";
    /** Pro Max：收藏、最近使用、使用次数，全在本机。 */
    private static final String K_FAVORITES = "favorites";
    private static final String K_RECENT = "recent";
    private static final String K_USAGE = "usage";
    private static final int RECENT_MAX = 60;
    private static final String ASSET_PHRASES = "phrase/phrases.json";
    private static final String ASSET_HOMO = "phrase/homophones.json";
    private static final String CLOUD_PREFIX = "MWP1.";
    private static final int CLOUD_MAX_DECODED = 256 * 1024;
    private static final int CLOUD_MAX_ITEMS = 500;
    private static final int CLOUD_MAX_TEXT = 500;

    /** 内置串词只解析一次（assets 里读 IO，慢）。 */
    private static List<Item> builtinCache;
    /** 云控词库缓存：原始 FLAG 值不变就不重复解压/解析。 */
    private static String cloudCacheRaw;
    private static CloudPack cloudCache;

    /** 一次云控词库解析出来的全部操作（包内可见，方便 JVM 单测）。 */
    static final class CloudPack {
        final List<Item> phrases = new ArrayList<>();
        final List<String> categories = new ArrayList<>();
        final Map<String, String> renames = new HashMap<>();
        final Set<String> deletedCats = new HashSet<>();
        final Set<String> hide = new HashSet<>();
        final Map<String, String> moveCats = new HashMap<>();
        final Map<String, String> homoAdd = new LinkedHashMap<>();
        final Set<String> homoRemove = new HashSet<>();
    }

    private PhraseStore() {
    }

    // ------------------------------------------------------------------ 串词

    /** 全部串词：内置 + 云端新增，再应用云端分类/移动/隐藏，最后是用户条目。 */
    public static List<Item> list(Context ctx) {
        CloudPack cp = cloudPack(ctx);
        Set<String> hidden = new HashSet<>(hiddenIds(ctx));
        hidden.addAll(cp.hide);

        List<Item> raw = new ArrayList<>(builtin(ctx));
        raw.addAll(cp.phrases);
        raw.addAll(userItems(ctx));

        List<Item> out = new ArrayList<>();
        for (Item it : raw) {
            if (hidden.contains(it.id) || hidden.contains(it.text)) continue;
            String moved = cp.moveCats.get(it.id);
            String cat = applyCloudCat(moved != null ? moved : it.cat, cp);
            out.add(new Item(it.id, cat, it.text, it.builtin));
        }
        return out;
    }

    /** 全部分类：云端声明（含空分类）+ 实际条目里出现的分类，按首次出现排序。 */
    public static List<String> categories(Context ctx) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        CloudPack cp = cloudPack(ctx);
        for (String c : cp.categories) {
            String name = applyCloudCat(c, cp);
            if (name.length() > 0) set.add(name);
        }
        for (Item it : list(ctx)) {
            if (it.cat != null && it.cat.length() > 0) set.add(it.cat);
        }
        return new ArrayList<>(set);
    }

    private static String applyCloudCat(String cat, CloudPack cp) {
        String c = cat == null ? "" : cat.trim();
        if (c.length() == 0 || cp == null) return c;
        String renamed = cp.renames.get(c);
        if (renamed != null && renamed.length() > 0) c = renamed;
        if (cp.deletedCats.contains(cat) || cp.deletedCats.contains(c)) return "";
        return c;
    }

    public static void addUser(Context ctx, String cat, String text) {
        List<Item> items = userItems(ctx);
        items.add(new Item("u" + System.currentTimeMillis(), safeCat(cat), text, false));
        saveUser(ctx, items);
    }

    /**
     * 批量导入串词（外部 txt 用）：正文与已有条目（内置含隐藏的 + 用户）相同的直接跳过。
     *
     * @return {新增条数, 跳过条数}
     */
    public static int[] addUserBatch(Context ctx, List<String> texts) {
        List<Item> items = userItems(ctx);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Item it : builtin(ctx)) {
            seen.add(it.text);
        }
        for (Item it : items) {
            seen.add(it.text);
        }
        int added = 0;
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null) {
                continue;
            }
            String s = t.trim();
            if (s.length() == 0 || !seen.add(s)) {
                continue;
            }
            items.add(new Item("u" + stamp + "_" + i, "", s, false));
            added++;
        }
        if (added > 0) {
            saveUser(ctx, items);
        }
        return new int[]{added, texts.size() - added};
    }

    public static void updateUser(Context ctx, String id, String cat, String text) {
        List<Item> items = userItems(ctx);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.set(i, new Item(id, safeCat(cat), text, false));
                break;
            }
        }
        saveUser(ctx, items);
    }

    /** 用户条目真删；内置条目只是记进隐藏清单。 */
    public static void remove(Context ctx, Item item) {
        if (!item.builtin) {
            List<Item> items = userItems(ctx);
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).id.equals(item.id)) {
                    items.remove(i);
                }
            }
            saveUser(ctx, items);
            return;
        }
        List<String> hidden = hiddenIds(ctx);
        if (!hidden.contains(item.id)) {
            hidden.add(item.id);
        }
        JSONArray arr = new JSONArray();
        for (String h : hidden) {
            arr.put(h);
        }
        prefs(ctx).edit().putString(K_HIDDEN, arr.toString()).apply();
    }

    /** 恢复被隐藏的内置串词。 */
    public static void restoreBuiltin(Context ctx) {
        prefs(ctx).edit().remove(K_HIDDEN).apply();
    }

    // ------------------------------------------------------------------ 云控词库

    /**
     * 云控词库：FLAG {@code phrase_cloud_pack=MWP1.<base64url(deflate(JSON))>}。
     *
     * <p>支持新建/改名/删除分类、批量加词、移动分类、隐藏词、加/删谐音：
     * <pre>
     * {
     *   "categories": ["日常", {"name":"某个管理员"}],
     *   "rename_categories": {"日常":"生活"},
     *   "delete_categories": ["旧分类"],
     *   "phrases": [{"cat":"生活","text":"..."}],
     *   "move_phrases": [{"id":"b0","cat":"生活"}],
     *   "hide_phrases": ["b1","要隐藏的正文"],
     *   "homophones": {"笑":"校"},
     *   "remove_homophones": ["笑"]
     * }
     * </pre>
     * 只处理文本，不执行任何内容；解析失败按空包处理。
     */
    private static CloudPack cloudPack(Context ctx) {
        String raw = "";
        boolean enabled = true;
        try {
            enabled = CloudFlags.bool("phrase_cloud_pack_on", true);
            if (enabled) raw = CloudFlags.str("phrase_cloud_pack", "").trim();
        } catch (Throwable ignored) {
        }
        if (raw.length() > 300_000) raw = "";
        String key = enabled ? raw : "\u0000off";
        synchronized (PhraseStore.class) {
            if (key.equals(cloudCacheRaw) && cloudCache != null) {
                return cloudCache;
            }
            CloudPack parsed = decodeCloud(key);
            cloudCacheRaw = key;
            cloudCache = parsed;
            invalidateHomo();
            return parsed;
        }
    }

    static CloudPack decodeCloud(String raw) {
        CloudPack pack = new CloudPack();
        if (raw == null || raw.isEmpty() || "\u0000off".equals(raw)) return pack;
        try {
            String json;
            if (raw.startsWith("{") || raw.startsWith("[")) {
                json = raw;
            } else {
                if (!raw.startsWith(CLOUD_PREFIX)) return pack;
                byte[] packed = Base64.getUrlDecoder().decode(raw.substring(CLOUD_PREFIX.length()));
                Inflater inflater = new Inflater();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try {
                    inflater.setInput(packed);
                    byte[] buf = new byte[8192];
                    while (!inflater.finished()) {
                        int n = inflater.inflate(buf);
                        if (n <= 0) break;
                        if (bo.size() + n > CLOUD_MAX_DECODED) return pack;
                        bo.write(buf, 0, n);
                    }
                } finally {
                    inflater.end();
                }
                json = bo.toString(StandardCharsets.UTF_8.name());
            }

            JSONArray phrases;
            if (json.trim().startsWith("[")) {
                phrases = new JSONArray(json);
            } else {
                JSONObject root = new JSONObject(json);
                // 分类
                JSONArray cats = root.optJSONArray("categories");
                if (cats == null) cats = root.optJSONArray("cats");
                if (cats != null) {
                    for (int i = 0; i < cats.length() && pack.categories.size() < 100; i++) {
                        Object n = cats.opt(i);
                        String name = n instanceof JSONObject
                                ? ((JSONObject) n).optString("name",
                                ((JSONObject) n).optString("id", ""))
                                : cats.optString(i, "");
                        name = name == null ? "" : name.trim();
                        if (name.length() > 0 && name.length() <= 80
                                && !pack.categories.contains(name)) {
                            pack.categories.add(name);
                        }
                    }
                }
                // 改名
                JSONObject ren = root.optJSONObject("rename_categories");
                if (ren == null) ren = root.optJSONObject("rename_cats");
                if (ren != null) {
                    java.util.Iterator<String> it = ren.keys();
                    while (it.hasNext()) {
                        String from = it.next();
                        String to = ren.optString(from, "").trim();
                        if (from != null && from.length() > 0 && to.length() > 0) {
                            pack.renames.put(from.trim(), to);
                        }
                    }
                }
                // 删除分类
                JSONArray del = root.optJSONArray("delete_categories");
                if (del == null) del = root.optJSONArray("delete_cats");
                if (del != null) {
                    for (int i = 0; i < del.length(); i++) {
                        String c = del.optString(i, "").trim();
                        if (c.length() > 0) pack.deletedCats.add(c);
                    }
                }
                // 移动串词分类
                JSONArray moves = root.optJSONArray("move_phrases");
                if (moves == null) moves = root.optJSONArray("move");
                if (moves != null) {
                    for (int i = 0; i < moves.length(); i++) {
                        JSONObject o = moves.optJSONObject(i);
                        if (o != null) {
                            String id = o.optString("id", "").trim();
                            String cat = o.optString("cat", "").trim();
                            if (id.length() > 0) pack.moveCats.put(id, cat);
                        }
                    }
                }
                // 隐藏
                JSONArray hide = root.optJSONArray("hide_phrases");
                if (hide == null) hide = root.optJSONArray("hide");
                if (hide != null) {
                    for (int i = 0; i < hide.length(); i++) {
                        String s = hide.optString(i, "").trim();
                        if (s.length() > 0) pack.hide.add(s);
                    }
                }
                // 谐音增删
                Object homo = root.opt("homophones");
                if (homo instanceof JSONObject) {
                    JSONObject o = (JSONObject) homo;
                    java.util.Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = o.optString(k, "");
                        if (k != null && k.length() > 0 && v != null && v.length() > 0) {
                            pack.homoAdd.put(k, v);
                        }
                    }
                } else if (homo instanceof JSONArray) {
                    JSONArray a = (JSONArray) homo;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        String k = o.optString("from", o.optString("key", ""));
                        String v = o.optString("to", o.optString("value", ""));
                        if (k.length() > 0 && v.length() > 0) pack.homoAdd.put(k, v);
                    }
                }
                JSONArray rmHomo = root.optJSONArray("remove_homophones");
                if (rmHomo == null) rmHomo = root.optJSONArray("remove_homo");
                if (rmHomo != null) {
                    for (int i = 0; i < rmHomo.length(); i++) {
                        String k = rmHomo.optString(i, "").trim();
                        if (k.length() > 0) pack.homoRemove.add(k);
                    }
                }
                phrases = root.optJSONArray("phrases");
                if (phrases == null) phrases = root.optJSONArray("items");
            }

            if (phrases != null) {
                for (int i = 0; i < phrases.length() && pack.phrases.size() < CLOUD_MAX_ITEMS; i++) {
                    Object node = phrases.opt(i);
                    String text;
                    String cat = "云端";
                    if (node instanceof JSONObject) {
                        JSONObject o = (JSONObject) node;
                        text = o.optString("text", "");
                        String c = o.optString("cat", "").trim();
                        if (c.length() > 0) cat = c;
                    } else {
                        text = phrases.optString(i, "");
                    }
                    if (text == null) continue;
                    text = text.trim();
                    if (text.length() == 0 || text.length() > CLOUD_MAX_TEXT) continue;
                    String id = "c" + Integer.toHexString(text.hashCode()) + "_" + i;
                    pack.phrases.add(new Item(id, cat, text, true));
                }
            }
        } catch (Throwable ignored) {
            return new CloudPack();
        }
        return pack;
    }

    // ------------------------------------------------------------------ Pro Max：收藏 / 最近 / 次数

    /** 收藏的条目 id（只存 id，内置和用户条目通用）。 */
    public static java.util.Set<String> favorites(Context ctx) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_FAVORITES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (id.length() > 0) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean isFavorite(Context ctx, String id) {
        return id != null && favorites(ctx).contains(id);
    }

    /** 返回切换后的状态：true = 已收藏。 */
    public static boolean toggleFavorite(Context ctx, String id) {
        if (id == null || id.length() == 0) return false;
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(favorites(ctx));
        boolean now;
        if (ids.remove(id)) {
            now = false;
        } else {
            ids.add(id);
            now = true;
        }
        JSONArray arr = new JSONArray();
        for (String s : ids) arr.put(s);
        prefs(ctx).edit().putString(K_FAVORITES, arr.toString()).apply();
        return now;
    }

    /** 最近使用 id，最新在前。 */
    public static List<String> recentIds(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_RECENT, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (id.length() > 0) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static int useCount(Context ctx, String id) {
        if (id == null) return 0;
        try {
            return new JSONObject(prefs(ctx).getString(K_USAGE, "{}")).optInt(id, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 复制 / 出图成功后记一次：最近使用置顶，累计次数 +1。 */
    public static void markUsed(Context ctx, String id) {
        if (id == null || id.length() == 0) return;
        try {
            List<String> recent = recentIds(ctx);
            recent.remove(id);
            recent.add(0, id);
            while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
            JSONArray arr = new JSONArray();
            for (String s : recent) arr.put(s);

            JSONObject usage;
            try {
                usage = new JSONObject(prefs(ctx).getString(K_USAGE, "{}"));
            } catch (Throwable t) {
                usage = new JSONObject();
            }
            usage.put(id, Math.min(999999, usage.optInt(id, 0) + 1));
            prefs(ctx).edit()
                    .putString(K_RECENT, arr.toString())
                    .putString(K_USAGE, usage.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 清空最近使用记录（不动使用次数和收藏）。 */
    public static void clearRecent(Context ctx) {
        prefs(ctx).edit().remove(K_RECENT).apply();
    }

    // ------------------------------------------------------------------ 谐音表

    /** 谐音表缓存：列表页每张卡片都要替换一次，不缓存的话每次都要读 prefs + 重排。 */
    private static LinkedHashMap<String, String> homoCache;
    private static List<Map.Entry<String, String>> homoPairs;
    /** 再按首字分桶（桶内仍是长键在前）：随机替换只查同首字的候选，不用每次扫全表。 */
    private static LinkedHashMap<Character, List<Integer>> homoByFirst;

    /** 谐音表：内置 + 用户覆盖 + 云端增删。 */
    public static LinkedHashMap<String, String> homophones(Context ctx) {
        CloudPack cp = cloudPack(ctx);
        if (homoCache != null) {
            return homoCache;
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>(builtinHomo(ctx));
        try {
            String raw = prefs(ctx).getString(K_HOMO, "");
            if (raw != null && raw.length() > 0) {
                JSONObject o = new JSONObject(raw);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, o.optString(k, ""));
                }
            }
        } catch (Throwable ignored) {
        }
        if (cp != null) {
            for (String k : cp.homoRemove) map.remove(k);
            map.putAll(cp.homoAdd);
        }
        // 长键优先，避免「这样」先被换掉导致「这样子」匹配不到；这个顺序只跟长度有关，一起缓存。
        List<Map.Entry<String, String>> pairs = new ArrayList<>(map.entrySet());
        pairs.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        homoPairs = pairs;
        LinkedHashMap<Character, List<Integer>> bucket = new LinkedHashMap<>();
        for (int i = 0; i < pairs.size(); i++) {
            String k = pairs.get(i).getKey();
            if (k == null || k.length() == 0) {
                continue;
            }
            Character c = k.charAt(0);
            List<Integer> list = bucket.get(c);
            if (list == null) {
                list = new ArrayList<>();
                bucket.put(c, list);
            }
            list.add(i);
        }
        homoByFirst = bucket;
        homoCache = map;
        return map;
    }

    /** 谐音表被改过就把缓存丢掉，下次读新的。 */
    private static void invalidateHomo() {
        homoCache = null;
        homoPairs = null;
        homoByFirst = null;
    }

    /** 新增 / 修改一对（应用内手填、导入都走这里）。 */
    public static void putHomophone(Context ctx, String from, String to) {
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString(K_HOMO, "{}"));
            o.put(from, to);
            prefs(ctx).edit().putString(K_HOMO, o.toString()).apply();
            invalidateHomo();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 批量导入谐音对（外部 txt 用）：同键覆盖；值跟内置表一样的就不留覆盖项了。
     *
     * @return {新增对数, 覆盖对数}
     */
    public static int[] putHomophonesBatch(Context ctx, Map<String, String> pairs) {
        LinkedHashMap<String, String> eff = homophones(ctx);
        LinkedHashMap<String, String> builtin = builtinHomo(ctx);
        JSONObject o;
        try {
            o = new JSONObject(prefs(ctx).getString(K_HOMO, "{}"));
        } catch (Throwable t) {
            o = new JSONObject();
        }
        int added = 0;
        int updated = 0;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.length() == 0 || v == null || v.length() == 0) {
                continue;
            }
            String old = eff.get(k);
            if (v.equals(old)) {
                continue;   // 一模一样，不算改动
            }
            if (old == null) {
                added++;
            } else {
                updated++;
            }
            try {
                if (v.equals(builtin.get(k))) {
                    o.remove(k);    // 跟内置值相同：删掉覆盖项，等于跟随内置
                } else {
                    o.put(k, v);
                }
            } catch (Throwable ignored) {
            }
        }
        prefs(ctx).edit().putString(K_HOMO, o.toString()).apply();
        invalidateHomo();
        return new int[]{added, updated};
    }

    /** 删掉一条：内置自带的记为「置空」而不是删除，避免它又冒出来。 */
    public static void removeHomophone(Context ctx, String from) {
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString(K_HOMO, "{}"));
            if (builtinHomo(ctx).containsKey(from)) {
                o.put(from, "");
            } else {
                o.remove(from);
            }
            prefs(ctx).edit().putString(K_HOMO, o.toString()).apply();
            invalidateHomo();
        } catch (Throwable ignored) {
        }
    }

    /** 按谐音表替换（长键优先，避免「这样」先被换掉导致「这样子」匹配不到）。 */
    public static String applyHomophones(Context ctx, String text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        homophones(ctx);    // 顺便把替换顺序备好
        String out = text;
        for (Map.Entry<String, String> e : homoPairs) {
            String from = e.getKey();
            String to = e.getValue();
            if (from != null && from.length() > 0 && to != null && to.length() > 0) {
                out = out.replace(from, to);
            }
        }
        return out;
    }

    /**
     * 随机替换：在这条串词命中的位置里随机挑 1~max 处换成谐音写法，每次结果都不一样
     * （不想让同一句话反复复制出去长得完全一样时用）。
     *
     * @param max &lt;= 0 时等价于全部替换；命中不足 max 处就按命中数来
     */
    public static String applyHomophonesRandom(Context ctx, String text, int max) {
        if (text == null || text.length() == 0) {
            return "";
        }
        if (max <= 0) {
            return applyHomophones(ctx, text);
        }
        if (homoPairs == null || homoByFirst == null) {
            homophones(ctx);    // 缓存没建才读表，建好之后这里不再碰 ctx
        }
        List<int[]> hits = findHits(text);
        if (hits.isEmpty()) {
            return text;
        }
        int limit = Math.min(max, hits.size());
        int count = 1 + (int) (Math.random() * limit);   // 每次换 1~limit 处
        List<int[]> pool = new ArrayList<>(hits);
        List<int[]> picked = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            picked.add(pool.remove((int) (Math.random() * pool.size())));
        }
        // 从后往前替换，免得前面的替换把后面的下标顶偏
        picked.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(text);
        for (int[] h : picked) {
            String to = homoPairs.get(h[2]).getValue();
            if (to != null && to.length() > 0) {
                sb.replace(h[0], h[1], to);
            }
        }
        return sb.toString();
    }

    /** 找出所有「命中且互不重叠」的位置：{起点, 终点, 谐音对下标}，按起点升序。 */
    private static List<int[]> findHits(String text) {
        List<int[]> out = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            List<Integer> bucket = homoByFirst.get(text.charAt(i));
            if (bucket != null) {
                int hit = -1;
                for (int p = 0; p < bucket.size(); p++) {
                    int idx = bucket.get(p);
                    String k = homoPairs.get(idx).getKey();
                    if (k.length() <= len - i && text.startsWith(k, i)) {
                        hit = idx;      // 桶内长键在前，第一个命中的就是最长的那个
                        break;
                    }
                }
                if (hit >= 0) {
                    String k = homoPairs.get(hit).getKey();
                    out.add(new int[]{i, i + k.length(), hit});
                    i += k.length();
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** 谐音替换模式：0 = 全部替换；&gt;0 = 每次复制随机换 1~N 处。 */
    public static int getHomoRandomMax(Context ctx) {
        return prefs(ctx).getInt(K_RANDOM, 0);
    }

    public static void setHomoRandomMax(Context ctx, int max) {
        prefs(ctx).edit().putInt(K_RANDOM, Math.max(0, max)).apply();
    }

    // ------------------------------------------------------------------ 内部

    private static List<Item> builtin(Context ctx) {
        if (builtinCache != null) {
            return builtinCache;
        }
        List<Item> out = new ArrayList<>();
        try {
            String raw = readAsset(ctx, ASSET_PHRASES);
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    // 新格式：数组里直接是字符串；老格式 {"cat":"..","text":".."} 也照样认。
                    Object node = arr.opt(i);
                    JSONObject obj = node instanceof JSONObject ? (JSONObject) node : null;
                    String text = obj != null ? obj.optString("text", "") : arr.optString(i, "");
                    if (text == null || text.length() == 0) {
                        continue;
                    }
                    // 内置串词也支持 cat（例如 admin），云端可按串词包整组下架。
                    String cat = obj == null ? "" : obj.optString("cat", "");
                    out.add(new Item("b" + i, cat, text, true));
                }
            }
        } catch (Throwable ignored) {
        }
        builtinCache = out;
        return out;
    }

    private static LinkedHashMap<String, String> builtinHomo(Context ctx) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        try {
            String raw = readAsset(ctx, ASSET_HOMO);
            JSONObject o = new JSONObject(raw).optJSONObject("map");
            if (o != null) {
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, o.optString(k, ""));
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private static List<Item> userItems(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_USER, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                out.add(new Item(o.optString("id", "u" + i),
                        safeCat(o.optString("cat", "我的")),
                        o.optString("text", ""), false));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveUser(Context ctx, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("cat", it.cat);
                o.put("text", it.text);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        prefs(ctx).edit().putString(K_USER, arr.toString()).apply();
    }

    private static List<String> hiddenIds(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_HIDDEN, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i, ""));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String readAsset(Context ctx, String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(path), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            return "";
        }
        return sb.toString();
    }

    private static String safeCat(String cat) {
        return cat == null || cat.trim().length() == 0 ? "未分类" : cat.trim();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
