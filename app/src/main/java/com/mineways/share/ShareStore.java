package com.mineways.share;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 分享码的落地层：应用前备份、应用、撤销、历史记录。
 *
 * <p>所有写入只发生在用户确认之后，且先落一份"受影响键的原值"快照，
 * 因此覆盖模式也可一键还原。
 */
public final class ShareStore {

    private static final String PREFS = "share_box";
    private static final String K_HISTORY = "history";
    private static final int KEEP = 20;

    public static final int MODE_MERGE = 0;      // 只补齐本机没有的键
    public static final int MODE_OVERWRITE = 1;  // 全部写入

    public static final class Applied {
        public int written;
        public int unchanged;
        public int skipped;
        public final List<String> notes = new ArrayList<>();
        public String backupId = "";
    }

    private ShareStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 应用

    public static Applied apply(Context c, ShareCodec.Snapshot s, int mode) {
        Applied r = new Applied();
        JSONObject backup = new JSONObject();
        Map<String, Set<String>> touched = new LinkedHashMap<>();

        for (Map.Entry<String, TreeMap<String, Object[]>> g : s.prefs.entrySet()) {
            String pref = g.getKey();
            if (pref == null || pref.isEmpty()) continue;
            SharedPreferences p = c.getSharedPreferences(pref, Context.MODE_PRIVATE);
            Map<String, ?> current = p.getAll();
            SharedPreferences.Editor ed = p.edit();
            Set<String> keys = new HashSet<>();
            JSONObject bk = new JSONObject();
            boolean changed = false;

            for (Map.Entry<String, Object[]> kv : g.getValue().entrySet()) {
                String key = kv.getKey();
                Object[] v = kv.getValue();
                if (PrefCatalog.excluded(pref, key)) {
                    r.skipped++;
                    r.notes.add(pref + " · " + key + "：已被排除（密钥/账号/状态类）");
                    continue;
                }
                if (v == null || v.length < 2) {
                    r.skipped++;
                    continue;
                }
                if (current.containsKey(key)) {
                    Object[] old = ShareCodec.of(current.get(key));
                    boolean same = old != null && ShareCodec.display(old).equals(ShareCodec.display(v));
                    if (mode == MODE_MERGE) {
                        r.unchanged++;
                        continue;
                    }
                    if (same) {
                        r.unchanged++;
                        continue;
                    }
                }
                try {
                    bk.put(key, oldJson(current.containsKey(key) ? ShareCodec.of(current.get(key)) : null));
                    put(ed, key, v);
                    keys.add(key);
                    changed = true;
                    r.written++;
                } catch (Exception e) {
                    r.skipped++;
                    r.notes.add(pref + " · " + key + "：写入失败（" + e.getMessage() + "）");
                }
            }
            if (changed) {
                ed.apply();
                try {
                    backup.put(pref, bk);
                } catch (Exception ignored) {
                }
                touched.put(pref, keys);
            }
        }

        if (!s.presets.isEmpty()) {
            for (Map.Entry<String, String> e : s.presets.entrySet()) {
                PresetBridge.putPending(c, e.getKey(), e.getValue());
                r.written++;
                r.notes.add(PresetBridge.title(e.getKey()) + "：预设已暂存，打开该工具后自动生效");
            }
        }

        JSONArray assetRecs = new JSONArray();
        if (!s.assets.isEmpty()) {
            for (Map.Entry<String, String> e : s.assets.entrySet()) {
                try {
                    int n = CreativeAssets.apply(c, e.getValue(),
                            mode == MODE_OVERWRITE, assetRecs);
                    r.written += n;
                    if (n > 0) {
                        r.notes.add(CreativeAssets.title(e.getKey()) + "：已写入 " + n + " 个文件");
                    } else {
                        r.unchanged++;
                        r.notes.add(CreativeAssets.title(e.getKey()) + "：本机已有，未改动");
                    }
                } catch (Exception ex) {
                    r.skipped++;
                    r.notes.add(CreativeAssets.title(e.getKey()) + "：写入失败（" + ex.getMessage() + "）");
                }
            }
            if (assetRecs.length() > 0) {
                try {
                    backup.put("__assets__", assetRecs);
                } catch (Exception ignored) {
                }
            }
        }

        boolean hasFiles = assetRecs.length() > 0;
        if (!touched.isEmpty() || hasFiles) {
            String id = "bk" + System.currentTimeMillis();
            try {
                sp(c).edit().putString(id, backup.toString()).apply();
                r.backupId = id;
            } catch (Exception ignored) {
            }
            log(c, "imp", s, r.written, r.backupId);
        } else if (!s.presets.isEmpty() || !s.assets.isEmpty()) {
            log(c, "imp", s, r.written, "");
        }
        return r;
    }

    public static boolean undo(Context c, String backupId) {
        if (backupId == null || backupId.isEmpty()) return false;
        String raw = sp(c).getString(backupId, "");
        if (raw == null || raw.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray assets = root.optJSONArray("__assets__");
            if (assets != null) CreativeAssets.restore(c, assets);
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pref = it.next();
                JSONObject bk = root.optJSONObject(pref);
                if (bk == null) continue;
                SharedPreferences p = c.getSharedPreferences(pref, Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = p.edit();
                for (Iterator<String> k = bk.keys(); k.hasNext(); ) {
                    String key = k.next();
                    JSONArray a = bk.optJSONArray(key);
                    if (a == null) continue;
                    if (a.length() == 1 && "absent".equals(a.optString(0))) {
                        ed.remove(key);
                    } else {
                        Object[] v = readValue(a);
                        if (v != null) put(ed, key, v);
                    }
                }
                ed.apply();
            }
            sp(c).edit().remove(backupId).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONArray oldJson(Object[] v) {
        JSONArray a = new JSONArray();
        if (v == null) {
            a.put("absent");
            return a;
        }
        char t = (Character) v[0];
        a.put(String.valueOf(t));
        Object o = v[1];
        try {
            if (t == 'f') a.put(Float.toString((Float) o));
            else if (t == 'S') {
                JSONArray arr = new JSONArray();
                for (Object x : (List<?>) o) arr.put(String.valueOf(x));
                a.put(arr);
            } else a.put(o);
        } catch (Exception ignored) {
        }
        return a;
    }

    private static Object[] readValue(JSONArray a) {
        String t = a.optString(0, "");
        if (t.length() != 1) return null;
        try {
            switch (t.charAt(0)) {
                case 'b': return new Object[]{'b', a.optBoolean(1)};
                case 'i': return new Object[]{'i', a.optInt(1)};
                case 'l': return new Object[]{'l', a.optLong(1)};
                case 'f': return new Object[]{'f', Float.parseFloat(a.optString(1, "0"))};
                case 's': return new Object[]{'s', a.optString(1, "")};
                case 'S': {
                    JSONArray arr = a.optJSONArray(1);
                    List<String> list = new ArrayList<>();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i, ""));
                    return new Object[]{'S', list};
                }
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor ed, String key, Object[] v) {
        char t = (Character) v[0];
        Object o = v[1];
        switch (t) {
            case 'b': ed.putBoolean(key, (Boolean) o); break;
            case 'i': ed.putInt(key, ((Number) o).intValue()); break;
            case 'l': ed.putLong(key, ((Number) o).longValue()); break;
            case 'f': ed.putFloat(key, ((Number) o).floatValue()); break;
            case 's': ed.putString(key, String.valueOf(o)); break;
            case 'S': ed.putStringSet(key, new HashSet<>((List<String>) o)); break;
            default: break;
        }
    }

    // ------------------------------------------------------------ 历史

    private static void log(Context c, String kind, ShareCodec.Snapshot s, int n, String backupId) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_HISTORY, "[]"));
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("name", s.name == null ? "" : s.name);
            o.put("note", s.note == null ? "" : s.note);
            o.put("ts", System.currentTimeMillis() / 1000L);
            o.put("n", n);
            o.put("bk", backupId == null ? "" : backupId);
            JSONArray tools = new JSONArray();
            for (String k : s.prefs.keySet()) tools.put(k);
            for (String k : s.presets.keySet()) tools.put("preset:" + k);
            for (String k : s.assets.keySet()) tools.put("asset:" + k);
            o.put("tools", tools);
            arr.put(o);
            while (arr.length() > KEEP) arr.remove(0);
            sp(c).edit().putString(K_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 保存一次「生成」记录（码本身不落盘，只记元信息与摘要）。 */
    public static void rememberGenerated(Context c, ShareCodec.Snapshot s, String code) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_HISTORY, "[]"));
            JSONObject o = new JSONObject();
            o.put("kind", "gen");
            o.put("name", s.name == null ? "" : s.name);
            o.put("note", s.note == null ? "" : s.note);
            o.put("ts", System.currentTimeMillis() / 1000L);
            o.put("n", s.keyCount() + s.presets.size() + s.assets.size());
            o.put("bk", "");
            o.put("dg", ShareCodec.digest(code));
            JSONArray tools = new JSONArray();
            for (String k : s.prefs.keySet()) tools.put(k);
            for (String k : s.presets.keySet()) tools.put("preset:" + k);
            for (String k : s.assets.keySet()) tools.put("asset:" + k);
            o.put("tools", tools);
            arr.put(o);
            while (arr.length() > KEEP) arr.remove(0);
            sp(c).edit().putString(K_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static JSONArray history(Context c) {
        try {
            return new JSONArray(sp(c).getString(K_HISTORY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void clearHistory(Context c) {
        sp(c).edit().putString(K_HISTORY, "[]").apply();
    }
}
