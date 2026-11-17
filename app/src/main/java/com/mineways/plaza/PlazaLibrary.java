package com.mineways.plaza;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 本地资源管家：只记录官方下载，不把云盘文件伪装成本地资源。 */
public final class PlazaLibrary {

    public static final class Entry {
        public String id;
        public String releaseTag;
        public String sha256;
        public String path;
        public String title;
        public String type;
        public long size;
        public long downloadedAt;
    }

    private PlazaLibrary() {
    }

    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(read(ctx));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.id = o.optString("id", "");
                e.releaseTag = o.optString("release_tag", "");
                e.sha256 = o.optString("sha256", "");
                e.path = o.optString("path", "");
                e.title = o.optString("title", "");
                e.type = o.optString("type", "other");
                e.size = o.optLong("size", 0);
                e.downloadedAt = o.optLong("downloaded_at", 0);
                if (!e.id.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static Entry find(Context ctx, PlazaItem item) {
        if (item == null) return null;
        for (Entry e : list(ctx)) {
            if (e.id.equals(item.id) && e.releaseTag.equals(item.releaseTag)) return e;
        }
        return null;
    }

    public static void remember(Context ctx, PlazaItem item, File file) {
        if (ctx == null || item == null || file == null || !item.isOfficial()) return;
        List<Entry> entries = list(ctx);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.id.equals(item.id)) entries.remove(i);
        }
        Entry e = new Entry();
        e.id = item.id;
        e.releaseTag = item.releaseTag;
        e.sha256 = item.sha256;
        e.path = file.getAbsolutePath();
        e.title = item.title;
        e.type = item.type;
        e.size = file.length();
        e.downloadedAt = System.currentTimeMillis();
        entries.add(0, e);
        save(ctx, entries);
    }

    public static void remove(Context ctx, String id) {
        List<Entry> entries = list(ctx);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).id.equals(id)) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(entries.get(i).path).delete();
                } catch (Throwable ignored) {
                }
                entries.remove(i);
            }
        }
        save(ctx, entries);
    }

    public static long totalSize(Context ctx) {
        long n = 0;
        for (Entry e : list(ctx)) n += Math.max(0, e.size);
        return n;
    }

    /** 是否有同 id 的更高 Release；只提示，不自动替换。 */
    public static boolean hasUpdate(Context ctx, PlazaItem item) {
        if (item == null) return false;
        for (Entry e : list(ctx)) {
            if (e.id.equals(item.id)) return !e.releaseTag.equals(item.releaseTag);
        }
        return false;
    }

    private static void save(Context ctx, List<Entry> entries) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("release_tag", e.releaseTag);
                o.put("sha256", e.sha256);
                o.put("path", e.path);
                o.put("title", e.title);
                o.put("type", e.type);
                o.put("size", e.size);
                o.put("downloaded_at", e.downloadedAt);
                a.put(o);
            }
            write(ctx, a.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String read(Context ctx) {
        try (FileInputStream in = new FileInputStream(file(ctx))) {
            byte[] data = new byte[(int) Math.min(file(ctx).length(), 1024 * 1024)];
            int n = in.read(data);
            return new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "[]";
        }
    }

    private static void write(Context ctx, String s) {
        try {
            File f = file(ctx);
            File p = f.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "plaza/library.json");
    }
}
