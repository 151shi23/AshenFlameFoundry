package com.mineways.plaza;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资源广场目录：多 JSON 清单 + 逐文件哈希 + 完整快照切换。
 *
 * <p>先拉 catalog.json，再拉 items/creators/collections/featured/rankings/search。
 * 任一文件缺失、哈希错、catalog_version 不一致，就继续用上一份完整快照，绝不混用。</p>
 */
public final class PlazaCatalog {

    private static final String[] REQUIRED = {
            "items.json", "creators.json", "collections.json",
            "featured.json", "rankings.json", "search.json"
    };
    private static final int MAX_FILE = 4 * 1024 * 1024;

    public static final class Snapshot {
        public final String version;
        public final String updatedAt;
        public final List<PlazaItem> items;
        public final Map<String, PlazaItem> byId;
        public final List<PlazaCreator> creators;
        public final Map<String, PlazaCreator> creatorById;
        public final List<PlazaCollection> collections;
        public final List<PlazaRanking> rankings;
        public final PlazaFeatured featured;
        public final PlazaSearchData search;
        public final List<String> warnings;
        /** 缓存原始多文件，保证刷新失败/离线时能完整还原所有模块。 */
        final Map<String, JSONObject> docs;

        Snapshot(String version, String updatedAt, List<PlazaItem> items,
                 List<PlazaCreator> creators, List<PlazaCollection> collections,
                 List<PlazaRanking> rankings, PlazaFeatured featured,
                 PlazaSearchData search, List<String> warnings,
                 Map<String, JSONObject> docs) {
            this.version = version;
            this.updatedAt = updatedAt;
            this.items = Collections.unmodifiableList(items);
            Map<String, PlazaItem> m = new LinkedHashMap<>();
            for (PlazaItem it : items) m.put(it.id, it);
            this.byId = Collections.unmodifiableMap(m);
            this.creators = Collections.unmodifiableList(creators);
            Map<String, PlazaCreator> cm = new LinkedHashMap<>();
            for (PlazaCreator c : creators) cm.put(c.id, c);
            this.creatorById = Collections.unmodifiableMap(cm);
            this.collections = Collections.unmodifiableList(collections);
            this.rankings = Collections.unmodifiableList(rankings);
            this.featured = featured;
            this.search = search == null ? PlazaSearchData.parse(null) : search;
            this.warnings = Collections.unmodifiableList(warnings);
            this.docs = docs == null ? Collections.<String, JSONObject>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(docs));
        }

        public List<PlazaItem> itemsOf(String type) {
            if (type == null || type.isEmpty() || "all".equals(type)) return items;
            List<PlazaItem> out = new ArrayList<>();
            for (PlazaItem it : items) if (type.equals(it.type)) out.add(it);
            return out;
        }

        public PlazaCreator creatorOf(PlazaItem it) {
            return it == null ? null : creatorById.get(it.creatorId);
        }

        public List<PlazaItem> collectionItems(PlazaCollection c) {
            List<PlazaItem> out = new ArrayList<>();
            if (c == null) return out;
            for (String id : c.items) {
                PlazaItem it = byId.get(id);
                if (it != null) out.add(it);
            }
            return out;
        }

        public List<PlazaItem> resolveRanking(PlazaRanking r) {
            List<PlazaItem> out = new ArrayList<>();
            if (r == null) return out;
            for (PlazaRanking.Entry e : r.entries) {
                PlazaItem it = byId.get(e.id);
                if (it != null) out.add(it);
            }
            return out;
        }
    }

    private PlazaCatalog() {
    }

    /** 读缓存优先；过期/强制时尝试网络，失败回退旧快照。 */
    public static Snapshot load(Context ctx, boolean force) throws IOException {
        File root = new File(ctx.getFilesDir(), "plaza/" + PlazaRepo.REPO_PATH.replace('/', '_'));
        String pointer = readPointer(root);
        Snapshot cached = null;
        if (pointer != null) {
            File dir = new File(root, pointer);
            if (dir.isDirectory()) {
                try {
                    cached = parseDir(dir);
                } catch (Throwable ignored) {
                }
            }
        }
        boolean fresh = false;
        if (cached != null && !force) {
            File meta = new File(new File(root, pointer), "meta.json");
            long t = meta.isFile() ? meta.lastModified() : 0;
            fresh = t > 0 && System.currentTimeMillis() - t < PlazaFlags.cacheHours() * 3600_000L;
        }
        if (fresh) return cached;

        try {
            Snapshot net = fetch(ctx);
            writeSnapshot(root, net);
            return net;
        } catch (Throwable t) {
            if (cached != null) {
                List<String> w = new ArrayList<>(cached.warnings);
                w.add("目录刷新失败，使用上次缓存：" + shortMsg(t));
                return new Snapshot(cached.version, cached.updatedAt, cached.items,
                        cached.creators, cached.collections, cached.rankings,
                        cached.featured, cached.search, w, cached.docs);
            }
            List<String> w = new ArrayList<>();
            w.add("资源目录暂时不可用：" + shortMsg(t));
            return new Snapshot("", "", new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), null,
                    PlazaSearchData.parse(null), w, Collections.<String, JSONObject>emptyMap());
        }
    }

    private static Snapshot fetch(Context ctx) throws Exception {
        String[] catalogUrls = {PlazaFlags.catalogUrl(), PlazaFlags.mirrorUrl()};
        Throwable last = null;
        String usedCatalog = null;
        String catalogJson = null;
        for (String url : catalogUrls) {
            if (url == null || url.isEmpty()) continue;
            try {
                catalogJson = fetchText(url);
                usedCatalog = url;
                break;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (catalogJson == null) throw new IOException("拉不到 catalog.json", last);

        JSONObject catalog = new JSONObject(catalogJson);
        int schema = catalog.optInt("schema", 0);
        if (schema < 2) throw new IOException("不支持的目录 schema：" + schema);
        String version = catalog.optString("catalog_version", "").trim();
        if (version.isEmpty() || version.length() > 60) throw new IOException("目录版本非法");
        String updated = catalog.optString("updated_at", "").trim();

        JSONArray files = catalog.optJSONArray("files");
        if (files == null) throw new IOException("catalog.json 缺少 files");
        Map<String, String> hashes = new HashMap<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("name", "").trim();
            String sha = f.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || !PlazaRepo.validSha(sha)) continue;
            if (name.contains("/") || name.contains("\\") || !name.endsWith(".json")) continue;
            hashes.put(name, sha);
        }
        String base = baseOf(usedCatalog);
        Map<String, JSONObject> docs = new LinkedHashMap<>();
        for (String name : REQUIRED) {
            String sha = hashes.get(name);
            if (sha == null) throw new IOException("catalog.json 缺少文件：" + name);
            String json = null;
            Throwable lastErr = null;
            String[] bases = {base, PlazaRepo.MIRROR_BASE};
            for (String b : bases) {
                if (b == null || b.isEmpty()) continue;
                try {
                    byte[] data = fetchBytes(b + name);
                    if (!sha.equals(sha256(data))) throw new IOException("sha256 不匹配：" + name);
                    json = new String(data, StandardCharsets.UTF_8);
                    break;
                } catch (Throwable t) {
                    lastErr = t;
                }
            }
            if (json == null) throw new IOException("拉取失败：" + name, lastErr);
            JSONObject o = new JSONObject(json);
            if (!version.equals(o.optString("catalog_version", ""))) {
                throw new IOException("文件版本不一致：" + name);
            }
            docs.put(name, o);
        }
        return build(version, updated, docs);
    }

    private static Snapshot build(String version, String updated, Map<String, JSONObject> docs) {
        List<String> warnings = new ArrayList<>();
        List<PlazaItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JSONArray ia = docs.get("items.json").optJSONArray("items");
        if (ia != null) {
            for (int i = 0; i < ia.length() && items.size() < 500; i++) {
                PlazaItem it = PlazaItem.parse(ia.optJSONObject(i));
                if (it == null) {
                    warnings.add("跳过无效资源 #" + (i + 1));
                    continue;
                }
                if (!ids.add(it.id)) {
                    warnings.add("跳过重复资源：" + it.id);
                    continue;
                }
                items.add(it);
            }
        }

        List<PlazaCreator> creators = new ArrayList<>();
        Set<String> cids = new HashSet<>();
        JSONArray ca = docs.get("creators.json").optJSONArray("creators");
        if (ca != null) {
            for (int i = 0; i < ca.length() && creators.size() < 300; i++) {
                PlazaCreator c = PlazaCreator.parse(ca.optJSONObject(i));
                if (c != null && cids.add(c.id)) creators.add(c);
            }
        }

        // creator_id 必须可解析；引用坏掉就丢弃资源，避免详情页显示空作者。
        List<PlazaItem> validItems = new ArrayList<>();
        for (PlazaItem it : items) {
            if (!it.creatorId.isEmpty() && !cids.contains(it.creatorId)) {
                warnings.add("跳过创作者不存在的资源：" + it.id);
                continue;
            }
            validItems.add(it);
        }
        items = validItems;
        Map<String, PlazaItem> byId = new HashMap<>();
        for (PlazaItem it : items) byId.put(it.id, it);

        List<PlazaCollection> collections = new ArrayList<>();
        JSONArray coa = docs.get("collections.json").optJSONArray("collections");
        if (coa != null) {
            for (int i = 0; i < coa.length() && collections.size() < 200; i++) {
                PlazaCollection c = PlazaCollection.parse(coa.optJSONObject(i));
                if (c == null) continue;
                boolean ok = true;
                if (c.isCreator() && !cids.contains(c.creatorId)) {
                    ok = false;
                    warnings.add("创作者合集 " + c.id + " 的 creator 不存在");
                }
                for (String id : c.items) {
                    PlazaItem it = byId.get(id);
                    if (it == null) {
                        ok = false;
                        warnings.add("合集 " + c.id + " 引用了不存在的资源 " + id);
                        break;
                    }
                    if (c.isCreator() && !c.creatorId.equals(it.creatorId)) {
                        ok = false;
                        warnings.add("创作者合集 " + c.id + " 混入了其他创作者资源 " + id);
                        break;
                    }
                }
                if (ok) collections.add(c);
            }
        }

        PlazaFeatured featured = null;
        JSONObject fo = docs.get("featured.json");
        PlazaFeatured f = PlazaFeatured.parse(fo);
        if (f != null) {
            List<String> keep = new ArrayList<>();
            for (String id : f.ids) if (byId.containsKey(id)) keep.add(id);
            if (keep.size() != f.ids.size()) warnings.add("本周精选有失效条目，已过滤");
            featured = new PlazaFeaturedBuilder().build(f.week, f.title, keep);
        }

        List<PlazaRanking> rankings = new ArrayList<>();
        JSONArray ra = docs.get("rankings.json").optJSONArray("rankings");
        if (ra != null) {
            for (int i = 0; i < ra.length() && rankings.size() < 30; i++) {
                PlazaRanking r = PlazaRanking.parse(ra.optJSONObject(i));
                if (r == null) continue;
                // 保留条目中存在的资源；空榜单不展示。
                List<PlazaRanking.Entry> keep = new ArrayList<>();
                for (PlazaRanking.Entry e : r.entries) if (byId.containsKey(e.id)) keep.add(e);
                if (!keep.isEmpty()) rankings.add(new PlazaRankingBuilder().build(r, keep));
            }
        }

        PlazaSearchData search = PlazaSearchData.parse(docs.get("search.json"));
        return new Snapshot(version, updated, items, creators, collections,
                rankings, featured, search, warnings, docs);
    }

    /** 小工具：在不改 JSON 模型的前提下重建过滤后的精选/榜单。 */
    private static final class PlazaFeaturedBuilder {
        PlazaFeatured build(String week, String title, List<String> ids) {
            JSONObject o = new JSONObject();
            try {
                o.put("week", week);
                o.put("title", title);
                JSONArray a = new JSONArray();
                for (String id : ids) a.put(id);
                o.put("ids", a);
            } catch (Throwable ignored) {
            }
            return PlazaFeatured.parse(o);
        }
    }

    private static final class PlazaRankingBuilder {
        PlazaRanking build(PlazaRanking src, List<PlazaRanking.Entry> entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", src.id);
                o.put("kind", src.kind);
                o.put("title", src.title);
                o.put("type", src.type);
                o.put("window", src.window);
                JSONArray a = new JSONArray();
                for (PlazaRanking.Entry e : entries) {
                    JSONObject x = new JSONObject();
                    x.put("id", e.id);
                    x.put("rank", e.rank);
                    x.put("score", e.score);
                    x.put("delta", e.delta);
                    a.put(x);
                }
                o.put("items", a);
            } catch (Throwable ignored) {
            }
            return PlazaRanking.parse(o);
        }
    }

    private static void writeSnapshot(File root, Snapshot s) {
        if (s == null || s.version == null || s.version.isEmpty() || s.docs.isEmpty()) return;
        File dir = new File(root, s.version);
        File tmp = new File(root, s.version + ".tmp");
        try {
            deleteTree(tmp);
            if (!tmp.mkdirs()) return;
            for (Map.Entry<String, JSONObject> e : s.docs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getKey().contains("/") || e.getKey().contains("\\")) continue;
                write(new File(tmp, e.getKey()),
                        e.getValue().toString().getBytes(StandardCharsets.UTF_8));
            }
            writeMeta(tmp, s);
            deleteTree(dir);
            if (!tmp.renameTo(dir)) {
                deleteTree(tmp);
                return;
            }
            File pointer = new File(root, "current.txt");
            File pTmp = new File(root, "current.tmp");
            write(pTmp, s.version.getBytes(StandardCharsets.UTF_8));
            if (pTmp.exists()) {
                if (pointer.exists()) pointer.delete();
                pTmp.renameTo(pointer);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeMeta(File dir, Snapshot s) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("version", s.version);
            o.put("updated_at", s.updatedAt);
            o.put("fetched_at", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        write(new File(dir, "meta.json"), o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Snapshot parseDir(File dir) throws Exception {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) return null;
        JSONObject mo = new JSONObject(readText(meta));
        String version = mo.optString("version", rootVersion(dir));
        String updated = mo.optString("updated_at", "");
        if (version.isEmpty()) return null;
        Map<String, JSONObject> docs = new LinkedHashMap<>();
        for (String name : REQUIRED) {
            File f = new File(dir, name);
            if (!f.isFile()) return null;
            JSONObject o = new JSONObject(readText(f));
            if (!version.equals(o.optString("catalog_version", ""))) return null;
            docs.put(name, o);
        }
        return build(version, updated, docs);
    }

    private static String rootVersion(File dir) {
        try {
            JSONObject o = new JSONObject(readText(new File(dir, "items.json")));
            return o.optString("catalog_version", "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readPointer(File root) {
        File f = new File(root, "current.txt");
        if (!f.isFile()) return null;
        try {
            String s = readText(f).trim();
            if (s.isEmpty() || s.contains("/") || s.contains("\\") || s.contains("..")) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String fetchText(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "MinewaysPlaza/1.0");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (bo.size() + n > MAX_FILE) throw new IOException("文件过大");
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String baseOf(String catalogUrl) {
        int slash = catalogUrl.lastIndexOf('/');
        return slash > 0 ? catalogUrl.substring(0, slash + 1) : PlazaRepo.RAW_BASE;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String shortMsg(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        return m == null || m.isEmpty() ? "网络错误" : m;
    }
}
