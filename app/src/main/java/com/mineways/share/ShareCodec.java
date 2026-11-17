package com.mineways.share;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 设置分享码的编解码。
 *
 * <p>码格式：{@code MWSC1.<Base64URL(deflate(JSON))>.<4 位校验>}。
 * 只承载数据，不承载任何可执行内容；所有失败都返回可读中文原因。
 */
public final class ShareCodec {

    public static final String PREFIX = "MWSC1.";
    /** 单码建议上限（字符）。超过时界面改走文件导出，不截断。 */
    public static final int MAX_CODE = 60000;

    private static final String B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 一份分享快照。值统一存成 {类型字符, 值}。 */
    public static final class Snapshot {
        public String name = "";
        public String note = "";
        public long ts = System.currentTimeMillis() / 1000L;
        /** pref 名 → 键 → {类型, 值}。 */
        public final LinkedHashMap<String, TreeMap<String, Object[]>> prefs = new LinkedHashMap<>();
        /** 预设名 → 原始 JSON 文本（模型器 / 粒子编辑器的整包预设）。 */
        public final LinkedHashMap<String, String> presets = new LinkedHashMap<>();
        /** 创作资产 id → 原始 JSON 文本（词库、音源、字体、AI 记忆等用户内容）。 */
        public final LinkedHashMap<String, String> assets = new LinkedHashMap<>();

        public boolean isEmpty() {
            return prefs.isEmpty() && presets.isEmpty() && assets.isEmpty();
        }

        public int keyCount() {
            int n = 0;
            for (TreeMap<String, Object[]> m : prefs.values()) n += m.size();
            return n;
        }
    }

    private ShareCodec() {
    }

    // ------------------------------------------------------------ 编码

    public static String encode(Snapshot s) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("ts", s.ts);
        root.put("name", s.name == null ? "" : s.name);
        root.put("note", s.note == null ? "" : s.note);

        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, TreeMap<String, Object[]>> e : s.prefs.entrySet()) {
            JSONObject one = new JSONObject();
            for (Map.Entry<String, Object[]> kv : e.getValue().entrySet()) {
                one.put(kv.getKey(), toJson(kv.getValue()));
            }
            prefs.put(e.getKey(), one);
        }
        root.put("prefs", prefs);

        if (!s.presets.isEmpty()) {
            JSONObject presets = new JSONObject();
            for (Map.Entry<String, String> e : s.presets.entrySet()) {
                presets.put(e.getKey(), e.getValue());
            }
            root.put("presets", presets);
        }

        if (!s.assets.isEmpty()) {
            JSONObject assets = new JSONObject();
            for (Map.Entry<String, String> e : s.assets.entrySet()) {
                assets.put(e.getKey(), e.getValue());
            }
            root.put("assets", assets);
        }

        byte[] raw = root.toString().getBytes("UTF-8");
        byte[] zip = deflate(raw);
        String body = Base64.encodeToString(zip, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return PREFIX + body + "." + check(zip);
    }

    public static Snapshot decode(String code) throws Exception {
        if (code == null) throw new IllegalArgumentException("码为空");
        String t = code.trim();
        // 允许用户把整段话粘进来：取第一个 MWSC1. 开头的片段
        int at = t.indexOf(PREFIX);
        if (at < 0) throw new IllegalArgumentException("不是设置分享码（应以 " + PREFIX + " 开头）");
        t = t.substring(at);
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        String[] parts = t.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("码不完整：缺少校验段");
        byte[] zip;
        try {
            zip = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalArgumentException("码内容损坏，无法解码");
        }
        if (!check(zip).equalsIgnoreCase(parts[2])) {
            throw new IllegalArgumentException("校验不符：码被改动过，或复制时少了一段");
        }
        String json;
        try {
            json = new String(inflate(zip), "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException("码解压失败，可能不完整");
        }
        JSONObject root = new JSONObject(json);
        if (root.optInt("v", 1) != 1) {
            throw new IllegalArgumentException("这是更新版本的分享码，请先升级 App");
        }
        Snapshot s = new Snapshot();
        s.ts = root.optLong("ts", 0L);
        s.name = root.optString("name", "");
        s.note = root.optString("note", "");
        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs != null) {
            for (java.util.Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                String pref = it.next();
                JSONObject one = prefs.optJSONObject(pref);
                if (one == null) continue;
                TreeMap<String, Object[]> map = new TreeMap<>();
                for (java.util.Iterator<String> k = one.keys(); k.hasNext(); ) {
                    String key = k.next();
                    JSONArray a = one.optJSONArray(key);
                    Object[] v = fromJson(a);
                    if (v != null) map.put(key, v);
                }
                if (!map.isEmpty()) s.prefs.put(pref, map);
            }
        }
        JSONObject presets = root.optJSONObject("presets");
        if (presets != null) {
            for (java.util.Iterator<String> it = presets.keys(); it.hasNext(); ) {
                String id = it.next();
                String body = presets.optString(id, "");
                if (body != null && body.length() > 0) s.presets.put(id, body);
            }
        }
        JSONObject assets = root.optJSONObject("assets");
        if (assets != null) {
            for (java.util.Iterator<String> it = assets.keys(); it.hasNext(); ) {
                String id = it.next();
                String body = assets.optString(id, "");
                if (body != null && body.length() > 0) s.assets.put(id, body);
            }
        }
        if (s.isEmpty()) throw new IllegalArgumentException("码里没有可导入的内容");
        return s;
    }

    // ------------------------------------------------------------ 取值 / 赋值

    public static Object[] of(Object v) {
        if (v instanceof Boolean) return new Object[]{'b', v};
        if (v instanceof Integer) return new Object[]{'i', v};
        if (v instanceof Long) return new Object[]{'l', v};
        if (v instanceof Float) return new Object[]{'f', v};
        if (v instanceof String) return new Object[]{'s', v};
        if (v instanceof Set) {
            List<String> list = new ArrayList<>();
            for (Object o : (Set<?>) v) if (o != null) list.add(String.valueOf(o));
            java.util.Collections.sort(list);
            return new Object[]{'S', list};
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<String> asStringList(Object[] v) {
        if (v == null || v.length < 2) return new ArrayList<>();
        Object o = v[1];
        if (o instanceof List) return (List<String>) o;
        List<String> one = new ArrayList<>();
        if (o != null) one.add(String.valueOf(o));
        return one;
    }

    public static String display(Object[] v) {
        if (v == null || v.length < 2) return "";
        char t = (Character) v[0];
        Object o = v[1];
        if (t == 'S') {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<?>) o) {
                if (sb.length() > 0) sb.append('、');
                sb.append(x);
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    private static JSONArray toJson(Object[] v) throws Exception {
        JSONArray a = new JSONArray();
        char t = (Character) v[0];
        a.put(String.valueOf(t));
        switch (t) {
            case 'f':
                a.put(Float.toString((Float) v[1]));
                break;
            case 'S':
                JSONArray arr = new JSONArray();
                for (Object x : (List<?>) v[1]) arr.put(String.valueOf(x));
                a.put(arr);
                break;
            default:
                a.put(v[1]);
        }
        return a;
    }

    private static Object[] fromJson(JSONArray a) {
        if (a == null || a.length() < 2) return null;
        String t = a.optString(0, "");
        if (t.isEmpty()) return null;
        char c = t.charAt(0);
        try {
            switch (c) {
                case 'b':
                    return new Object[]{'b', a.optBoolean(1)};
                case 'i':
                    return new Object[]{'i', a.optInt(1)};
                case 'l':
                    return new Object[]{'l', a.optLong(1)};
                case 'f':
                    return new Object[]{'f', Float.parseFloat(a.optString(1, "0"))};
                case 's':
                    return new Object[]{'s', a.optString(1, "")};
                case 'S': {
                    JSONArray arr = a.optJSONArray(1);
                    List<String> list = new ArrayList<>();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i, ""));
                    return new Object[]{'S', list};
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 压缩 / 校验

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Deflater d = new Deflater(9, true);
        DeflaterOutputStream dos = new DeflaterOutputStream(bos, d, 4096);
        dos.write(raw);
        dos.finish();
        dos.close();
        d.end();
        return bos.toByteArray();
    }

    private static byte[] inflate(byte[] zip) throws IOException {
        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(zip), new java.util.zip.Inflater(true));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static String check(byte[] zip) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(zip);
        int v = ((h[0] & 0xFF) << 12) | ((h[1] & 0xFF) << 4) | ((h[2] & 0xF0) >> 4);
        StringBuilder sb = new StringBuilder(4);
        for (int i = 3; i >= 0; i--) sb.append(B32.charAt((v >> (i * 5)) & 31));
        return sb.toString();
    }

    /** 生成码摘要，用于历史记录去重/展示。 */
    public static String digest(String code) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(code.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static Set<String> newSet() {
        return new LinkedHashSet<>();
    }
}
