package com.mineways;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 外部 txt 的读取与解析（纯本地，不联网）。
 *
 * <pre>
 *   串词 txt   一行一条：# 开头是注释、空行忽略，一条里要换行就写成 \n
 *   谐音 txt   一行一对：原词 → 替换（也认 -> / => / = / ：/ Tab / |）
 * </pre>
 *
 * 只做「文件 → 结构」，入库交给 {@link PhraseStore}，这样解析逻辑可以单独验。
 * 选文件走 SAF（ACTION_OPEN_DOCUMENT），不需要任何存储权限。
 */
public final class PhraseImporter {

    public static final int KIND_PHRASE = 0;
    public static final int KIND_HOMO = 1;
    /** 交给 {@link #guessKind(String)} 自动判断。 */
    public static final int KIND_AUTO = 2;

    /** 单次导入上限：防止手滑选到几百 MB 的文件把内存撑爆。 */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** 谐音对的候选分隔符：取一行里最靠前的那个，避免替换值里的符号干扰。 */
    private static final String[] SEPS = {"→", "⇢", "->", "=>", "＝", "=", "：", "\t", "|"};

    private PhraseImporter() {
    }

    // ------------------------------------------------------------------ 读文件

    /** 读一个外部 txt 成字符串（UTF-8 / GBK / 带 BOM 的记事本文件都认）。 */
    public static String read(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalArgumentException("打不开这个文件");
        }
        BufferedInputStream bis = new BufferedInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bis.read(buf)) > 0) {
                if (bos.size() + n > MAX_BYTES) {
                    throw new IllegalStateException("文件太大（上限 8MB）");
                }
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                bis.close();
            } catch (Throwable ignored) {
            }
        }
        return decode(bos.toByteArray());
    }

    /** 先看 BOM，再按严格 UTF-8 试解，解不通就按 GBK（Windows 记事本默认编码）解。 */
    public static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(data, Charset.forName("GBK"));
            } catch (Throwable t) {
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 串词：一行一条（文件内重复的只留第一条），「\n」还原成换行。 */
    public static List<String> parsePhrases(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null && raw.length() > 0) {
            for (String line : lines(raw)) {
                String s = line.trim();
                if (s.length() == 0 || isComment(s)) {
                    continue;
                }
                String body = s.replace("\\n", "\n").trim();
                if (body.length() > 0) {
                    set.add(body);
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** 谐音：一行一对；同一个原词写多行 → 以最后一行为准；写残（只有半边）的忽略。 */
    public static LinkedHashMap<String, String> parseHomophones(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw != null && raw.length() > 0) {
            for (String line : lines(raw)) {
                String s = line.trim();
                if (s.length() == 0 || isComment(s)) {
                    continue;
                }
                String[] p = splitPair(s);
                if (p != null) {
                    out.put(p[0], p[1]);
                }
            }
        }
        return out;
    }

    /** 猜这份 txt 是哪种：非注释行里键值对占一半以上，就当谐音表。 */
    public static int guessKind(String raw) {
        int pair = 0;
        int other = 0;
        if (raw != null && raw.length() > 0) {
            for (String line : lines(raw)) {
                String s = line.trim();
                if (s.length() == 0 || isComment(s)) {
                    continue;
                }
                if (splitPair(s) != null) {
                    pair++;
                } else {
                    other++;
                }
            }
        }
        return pair > 0 && pair >= other ? KIND_HOMO : KIND_PHRASE;
    }

    // ------------------------------------------------------------------ 内部

    /** 统一换行符后切行（\r\n 和单独的 \r 都当换行）。 */
    private static String[] lines(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static boolean isComment(String s) {
        return s.charAt(0) == '#';
    }

    /** 一行拆成「原词 / 替换」；不是键值对返回 null。 */
    private static String[] splitPair(String line) {
        int best = -1;
        String hit = null;
        for (String sep : SEPS) {
            int i = line.indexOf(sep);
            if (i > 0 && (best < 0 || i < best)) {
                best = i;
                hit = sep;
            }
        }
        if (hit == null) {
            return null;
        }
        String from = line.substring(0, best).trim();
        String to = line.substring(best + hit.length()).trim();
        if (from.length() == 0 || to.length() == 0) {
            return null;
        }
        return new String[]{from, to};
    }
}
