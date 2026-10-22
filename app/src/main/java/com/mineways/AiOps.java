package com.mineways;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.mineways.pack.BedrockPackConverter;
import com.mineways.pack.Java2BedrockConverter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
/**
 * AI 的<b>干活层</b>：不打开界面，直接把事做完，产物落进 Download 再把路径回给模型。
 *
 * <p><b>只做代码里已经跑通过的事</b>：转换调的是与转换页同一套内核
 * （{@link BedrockPackConverter} / {@link Java2BedrockConverter}，含 {@code libpackcore.so}），
 * GIF 调的是 {@link GifTool}，语法检查调的是 {@link Expr}。<b>没有任何一行"为了 AI 另写一份逻辑"</b> ——
 * 另写一份早晚和内核对不上，那时 AI 报出来的结果就是错的。
 *
 * <p>判定（方向 / 命名 / 撞名 / 体积上限）全在 {@link AiOpsCore}，那层是纯 Java、有 67 条 JVM 断言。
 *
 * <p><b>三条安全线</b>：
 * <ol>
 *   <li>只写 {@code Download/<功能名>/}，源目录一个字节都不动；</li>
 *   <li>产物撞名一律编号避让（{@code _2} {@code _3}），AI 不许吃掉用户的旧产物；</li>
 *   <li>大任务在一条 daemon 线程上跑，界面先出「正在算」卡片，算完再回喂结果 ——
 *       卡住主线程或者让人对着静止界面干等，都算 bug。</li>
 * </ol>
 */
public final class AiOps {

    /** Download 下的子目录名，与转换页、GIF 页保持一致，用户知道去哪找。 */
    public static final String DIR_PACK = "材质包转换";
    public static final String DIR_GIF = "GIF 工具";

    /** 与转换页同一道上限（320 MB），超了别白跑一趟。 */
    private static final long MAX_BYTES = AiOpsCore.MAX_BYTES;

    /** {@code pick} 的挂起句柄（一个对话里同时只可能等一个选择）。 */
    private static volatile Pending pickWaiter;

    /** 界面把 {@code ACTION_OPEN_DOCUMENT} 的结果送回来（取消就传 null）。 */
    public static void resolvePick(String uriOrNull, String displayName, Context c) {
        Pending p = pickWaiter;
        pickWaiter = null;
        if (p == null) {
            return;
        }
        JSONObject r = new JSONObject();
        try {
            r.put("tool", "pick");
            if (uriOrNull == null) {
                r.put("ok", false);
                r.put("error", "用户取消了选择");
            } else {
                r.put("ok", true);
                r.put("uri", uriOrNull);
                r.put("name", displayName == null ? "" : displayName);
                r.put("result", "用户选了「" + r.optString("name") + "」，已经记住这个文件；"
                        + "接下来 ops(op=pack) / ops(op=gif) 会自动用它，"
                        + "不用再向用户要路径");
                AiMemory.remember(c, "用户选的文件", uriOrNull
                        + (displayName == null || displayName.isEmpty() ? "" : ("（" + displayName + "）")));
            }
        } catch (Throwable ignored) {
        }
        p.settle(r.toString());
    }

    /** 有没有正在等用户选文件（界面据此决定要不要把结果转交给它）。 */
    public static boolean waitingForPick() {
        return pickWaiter != null;
    }

    private AiOps() {
    }

    // ---------------------------------------------------------------- 异步句柄

    /**
     * 一次执行的句柄：{@link #done} 为假表示还在算，界面先把「正在算」画出来，
     * 算完由 {@link #settle} 把同一个 JSON 交出去。
     */
    public static final class Pending {
        private volatile boolean done;
        private volatile String result;
        private OnSettled cb;

        /** 立刻就能回结论的工具走这条（同步做完）。 */
        public static Pending now(String json) {
            Pending p = new Pending();
            p.done = true;
            p.result = json == null ? "{\"ok\":false}" : json;
            return p;
        }

        public boolean done() {
            return done;
        }

        public String result() {
            return result;
        }

        /**
         * 干完了：先存结果，再把回调<b>切回主线程</b>交出去 —— 界面拿它改 Compose 状态
         * （消息列表），在非主线程改会当场崩。
         */
        void settle(String json) {
            result = json == null ? "{\"ok\":false}" : json;
            done = true;
            fire();
        }

        /** 界面挂自己的一条回调；<b>一定在主线程被调</b>，直接改状态就行。 */
        public void onSettled(OnSettled c) {
            cb = c;
            if (done) {
                fire();
            }
        }

        private void fire() {
            final OnSettled c = cb;
            if (c == null) {
                return;
            }
            final String json = result;
            MAIN.post(() -> {
                try {
                    c.onSettled(json);
                } catch (Throwable ignored) {
                    // 面板已经关了：结果就在 result 里，别把进程带崩
                }
            });
        }

        private static final android.os.Handler MAIN =
                new android.os.Handler(android.os.Looper.getMainLooper());

        public interface OnSettled {
            void onSettled(String json);
        }
    }

    /** {@code pending} 的对外入口（AiTools 只看这个）。 */
    public static Pending exec(String tool, JSONObject args, Context ctx) {
        String path = arg(args, "path", "input", "文件", "目录", "路径");
        if (AiPolicy.T_PICK.equals(tool)) {
            return pick(ctx, arg(args, "what", "kind", "description", "说明"));
        }
        if (AiPolicy.T_OPS.equals(tool)) {
            String op = arg(args, "op", "name", "操作").toLowerCase(Locale.ROOT);
            if (op.contains("pack") || op.contains("材质") || op.length() == 0) {
                return packConvert(ctx, pickPath(ctx, path), arg(args, "to", "direction", "方向"));
            }
            if (op.contains("gif") || op.contains("动图") || op.contains("帧")) {
                return framesGif(ctx, pickPath(ctx, path), intArg(args, "frame_h", "frameH", "帧高"),
                        intArg(args, "frametime", "ticks", "帧时长"));
            }
            if (op.contains("expr") || op.contains("语法") || op.contains("条件")) {
                return Pending.now(exprCheck(AiOpsCore.textArg(arg(args, "text", "expr", "内容"))));
            }
            return Pending.now("{\"ok\":false,\"tool\":\"ops\",\"error\":\"没有这个操作：" + op
                    + "；现在能直接干的是 pack（材质包双向转换）、gif（图集→动图）、expr（条件表达式语法检查）\"}");
        }
        if ("pack_convert".equals(tool)) {
            return packConvert(ctx, path, arg(args, "to", "direction", "方向"));
        }
        if ("frames_gif".equals(tool)) {
            return framesGif(ctx, path, intArg(args, "frame_h", "frameH", "帧高"),
                    intArg(args, "frametime", "ticks", "帧时长"));
        }
        if ("expr_check".equals(tool)) {
            return Pending.now(exprCheck(AiOpsCore.textArg(arg(args, "text", "expr", "内容"))));
        }
        return Pending.now("{\"ok\":false,\"error\":\"没有这个操作：" + tool + "\"}");
    }

    /** 有哪些活能干（写进提示词，也从 {@code describe} 里回给用户）。 */
    public static String catalog() {
        return "pack（材质包双向转换）/ gif（竖条图集转动图）/ expr（条件表达式语法检查）";
    }

    // ---------------------------------------------------------------- 让用户自己选文件

    private static Pending pick(Context ctx, String what) {
        if (pickWaiter != null) {
            return Pending.now("{\"ok\":false,\"tool\":\"pick\",\"error\":\"已经在等你选文件了，先选一下\"}");
        }
        final Pending p = new Pending();
        pickWaiter = p;
        if (!(ctx instanceof android.app.Activity)
                || !com.mineways.ui.MwAi.requestPick(ctx, what == null ? "" : what)) {
            pickWaiter = null;
            return Pending.now("{\"ok\":false,\"tool\":\"pick\",\"error\":\"这台机器上打不开文件选择器；"
                    + "请让用户直接把他那个文件夹/文件的路径贴过来\"}");
        }
        return p;   // 用户选完（或取消）由 resolvePick 收尾
    }

    /**
     * 模型没给路径时，退到「用户刚在对话里选过的那个文件」。
     *
     * <p>安卓给的是 {@code content://} 而不是真实路径，模型看不懂也不该背 —— 所以路径由界面记着，
     * 这里替它取回来。记忆就 12 条、同步写，倒着找一次是常数级。
     */
    private static String pickPath(Context ctx, String given) {
        if (given != null && given.trim().length() > 0) {
            return given;
        }
        try {
            for (String s : AiMemory.items(ctx)) {
                if (s.startsWith("用户选的文件：")) {
                    String v = s.substring("用户选的文件：".length());
                    int paren = v.indexOf('（');
                    return paren > 0 ? v.substring(0, paren).trim() : v.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return given;
    }

    // ---------------------------------------------------------------- 材质包双向转换

    /**
     * 把 {@code content://}（系统选择器给的东西）或 {@code file://} 弄成一个能直接读的文件。
     *
     * <p>转换内核要的是 {@code File}，而安卓 10 之后选择器只给 URI —— 所以只能先落到缓存再转。
     * 复制在后台线程做（包可能几百 MB），界面那张「正在执行」卡片会一直亮着。
     *
     * @return null 表示这不是 URI，调用方按普通路径处理
     */
    private static Pending importContent(final Context ctx, final String uriText, final String kind) {
        final Pending p = new Pending();
        Thread t = new Thread(() -> {
            JSONObject r = new JSONObject();
            try {
                Uri u = Uri.parse(uriText);
                r.put("tool", kind);
                r.put("uri", uriText);
                String name = nameOf(ctx, u);
                File dir = new File(ctx.getCacheDir(), "aiops");
                mkdirs(dir);
                if ("file".equalsIgnoreCase(u.getScheme())) {
                    File f = new File(u.getPath() == null ? "" : u.getPath());
                    if (!f.exists()) {
                        throw new IllegalStateException("这个路径不存在：" + f);
                    }
                    r.put("ok", true);
                    r.put("staged", f.getAbsolutePath());
                } else {
                    File dst = new File(dir, AiOpsCore.unique(dir, "in_" + name));
                    long n = copyTo(ctx, u, dst);
                    if (n <= 0) {
                        throw new IllegalStateException("这个文件是空的或读不到");
                    }
                    if (n > MAX_BYTES) {
                        dst.delete();
                        throw new IllegalStateException("超过 " + AiOpsCore.human(MAX_BYTES) + " 上限");
                    }
                    r.put("ok", true);
                    r.put("staged", dst.getAbsolutePath());
                    r.put("size", AiOpsCore.human(n));
                }
                r.put("result", "已取到文件：" + r.optString("staged"));
            } catch (Throwable err) {
                try {
                    r.put("ok", false);
                    r.put("error", "读不到用户选的文件：" + why(err)
                            + "；有些文件管理器给的是加密虚拟路径，换一个（MT 管理器 / 文件）再试");
                } catch (Throwable ignored) {
                }
            }
            p.settle(r.toString());
        }, "mw-ai-stage");
        t.setDaemon(true);
        t.start();
        return p;
    }

    /** 查询文件名（拿不到就用 URI 末段，再拿不到就叫 in_pack）。 */
    private static String nameOf(Context ctx, Uri u) {
        try (android.database.Cursor c = ctx.getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                String s = i >= 0 ? c.getString(i) : null;
                if (s != null && s.trim().length() > 0) {
                    return s.replaceAll("[^0-9A-Za-z._\\u4e00-\\u9fa5-]", "_");
                }
            }
        } catch (Throwable ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null || last.length() == 0 ? "in_pack"
                : last.replaceAll("[^0-9A-Za-z._\\u4e00-\\u9fa5-]", "_");
    }

    private static long copyTo(Context ctx, Uri u, File dst) throws Exception {
        long total = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(u);
             OutputStream os = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IllegalStateException("没有读权限");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES + 1024 * 1024) {
                    break;      // 明显超上限就别再搬了
                }
            }
        }
        return total;
    }

    private static Pending packConvert(final Context ctx, final String rawPath, final String want) {
        String raw = rawPath == null ? "" : rawPath.trim();
        if (raw.startsWith("content://") || raw.startsWith("file://")) {
            final Pending imp = importContent(ctx, raw, "pack");
            if (imp != null) {
                if (!imp.done()) {
                    return imp;
                }
                // 已经拿到本地副本：接着按普通路径走一遍
                try {
                    JSONObject o = new JSONObject(imp.result());
                    if (o.optBoolean("ok")) {
                        return packConvert(ctx, o.getString("staged"), want);
                    }
                } catch (Throwable ignored) {
                }
                return imp;
            }
        }
        final AiOpsCore.Plan plan = AiOpsCore.planPack(
                AiOpsCore.resolve(raw), want, downloadsRoot(), DIR_PACK);
        if (!plan.ok()) {
            return Pending.now("{\"ok\":false,\"tool\":\"pack\",\"error\":\""
                    + esc(plan.error) + "\"}");
        }
        final Pending p = new Pending();
        Thread t = new Thread(() -> {
            String json;
            try {
                json = runPack(ctx, plan);
            } catch (Throwable err) {
                json = "{\"ok\":false,\"tool\":\"pack\",\"error\":\"" + esc(why(err)) + "\"}";
            }
            p.settle(json);
        }, "mw-ai-pack");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private static String runPack(Context ctx, AiOpsCore.Plan plan) throws Exception {
        File cache = new File(ctx.getCacheDir(), "aiops");
        mkdirs(cache);
        File tmp = new File(cache, "tmp_" + System.currentTimeMillis());
        mkdirs(tmp);
        File dest = new File(cache, plan.outName);
        long t0 = System.currentTimeMillis();
        String summary;
        try {
            if (AiOpsCore.TO_BEDROCK.equals(plan.direction)) {
                Reader r = new InputStreamReader(
                        ctx.getAssets().open("packconv/name_map_java2bedrock.json"),
                        StandardCharsets.UTF_8);
                Java2BedrockConverter.Result jr = Java2BedrockConverter.convert(
                        plan.input, dest, new Adapter(), tmp, r, null);
                summary = "进包 " + jr.texturesMoved + " 张贴图（表里精确对上 " + jr.exactHits
                        + "、同名 " + jr.sameNameHits + "、词序还原 " + jr.tokenHits
                        + "）、重排动画 " + jr.animations + " 张（重建帧序 "
                        + jr.animationsRebuilt + "）、lang " + jr.langs + " 个文件 /"
                        + jr.langKeys + " 条、声音 " + jr.soundsDefs + " 组 /"
                        + jr.soundsFiles + " 个文件、重编码 " + jr.reencoded + " 张；"
                        + "没对上直接搬的 " + jr.texturesSkipped + " 张";
            } else {
                BedrockPackConverter.Result br = BedrockPackConverter.convert(
                        plan.input, dest, new Adapter(), tmp, null);
                summary = br.toString();
            }
            String where = writeDownload(ctx, DIR_PACK, dest);
            AiMemory.remember(ctx, "产物", where == null ? dest.getAbsolutePath() : where);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("tool", "pack");
            o.put("direction", AiOpsCore.TO_BEDROCK.equals(plan.direction)
                    ? "Java → 基岩 .mcpack" : "基岩 → Java 资源包 zip");
            o.put("input", plan.input.getAbsolutePath());
            o.put("size", AiOpsCore.human(plan.input.isFile()
                    ? plan.input.length() : AiOpsCore.walk(plan.input)));
            o.put("out", where == null ? dest.getAbsolutePath() : where);
            o.put("fallback", where == null ? "没能写进公共 Download，文件在应用缓存里，用户可以用文件管理器打开" : "");
            o.put("result", summary);
            o.put("elapsed_ms", System.currentTimeMillis() - t0);
            o.put("next", "把 out 这个路径原样念给用户；要导入基岩版就点那个 .mcpack");
            return o.toString();
        } finally {
            deleteTree(tmp);
        }
    }

    // ---------------------------------------------------------------- 竖条图集 → GIF

    private static Pending framesGif(final Context ctx, final String rawPath,
                                     final int frameH, final int frametime) {
        File f = AiOpsCore.resolve(rawPath);
        AiOpsCore.Plan plan = AiOpsCore.planFrames(f, frameH, downloadsRoot(), DIR_GIF);
        if (!plan.ok()) {
            return Pending.now("{\"ok\":false,\"tool\":\"gif\",\"error\":\""
                    + esc(plan.error) + "\"}");
        }
        final Pending p = new Pending();
        final File in = f;
        Thread t = new Thread(() -> {
            String json;
            try {
                json = runGif(ctx, in, plan, frameH, frametime);
            } catch (Throwable err) {
                json = "{\"ok\":false,\"tool\":\"gif\",\"error\":\"" + esc(why(err)) + "\"}";
            }
            p.settle(json);
        }, "mw-ai-gif");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private static String runGif(Context ctx, File in, AiOpsCore.Plan plan,
                                 int frameH, int frametime) throws Exception {
        Bitmap strip = BitmapFactory.decodeFile(in.getAbsolutePath());
        if (strip == null) {
            return "{\"ok\":false,\"tool\":\"gif\",\"error\":\"这张图解不开，可能不是 png/jpg\"}";
        }
        List<Bitmap> frames = new ArrayList<>();
        try {
            int w = strip.getWidth();
            int h = strip.getHeight();
            int fh = frameH > 0 && frameH <= h ? frameH : h;
            // 方形帧是材质包动画的常见排布：宽 = 高时按竖条切；否则整张当一帧
            if (frameH <= 0 && w > 0 && h > w && h % w == 0) {
                fh = w;
            }
            for (int y = 0; y + fh <= h; y += fh) {
                frames.add(Bitmap.createBitmap(strip, 0, y, w, fh));
            }
            if (frames.size() < 2) {
                return "{\"ok\":false,\"tool\":\"gif\",\"error\":\"这张图切不出两帧以上，"
                        + "它大概不是一张动画图集；要转 GIF 请给竖条（宽=单帧宽，高=帧数×单帧高）\"}";
            }
            int ticks = frametime > 0 ? frametime : 1;
            int delayMs = ticks * 50;               // 材质包 1 tick = 50 ms
            byte[] gif = GifTool.encode(frames, delayMs, true);
            File cache = new File(ctx.getCacheDir(), "aiops");
            mkdirs(cache);
            File dest = new File(cache, plan.outName);
            try (OutputStream os = new FileOutputStream(dest)) {
                os.write(gif);
            }
            String where = writeDownload(ctx, DIR_GIF, dest);
            AiMemory.remember(ctx, "产物", where == null ? dest.getAbsolutePath() : where);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("tool", "gif");
            o.put("frames", frames.size());
            o.put("frame", w + "×" + fh);
            o.put("frametime_ticks", ticks);
            o.put("delay_ms", delayMs);
            o.put("out", where == null ? dest.getAbsolutePath() : where);
            o.put("result", "已把 " + frames.size() + " 帧竖条图集转成 GIF（每帧 " + ticks
                    + " tick / " + delayMs + " ms）");
            o.put("mcmeta_hint", GifTool.mcmeta(ticks));
            return o.toString();
        } finally {
            for (Bitmap b : frames) {
                if (b != null && b != strip) {
                    b.recycle();
                }
            }
            strip.recycle();
        }
    }

    // ---------------------------------------------------------------- 条件表达式语法检查

    private static String exprCheck(String src) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("tool", "expr");
            if (src.length() == 0) {
                o.put("result", "没给内容");
                o.put("note", "把要检查的条件整段贴过来，比如 vip>=3 && gamemode==\"survival\"");
                return o.toString();
            }
            if (src.length() > AiOpsCore.MAX_TEXT) {
                o.put("ok", false);
                o.put("error", "内容太长（" + src.length() + " 字，上限 " + AiOpsCore.MAX_TEXT
                        + "）；一条条件表达式不该这么长，多半是你把整个 particles.json 贴上来了");
                return o.toString();
            }
            java.util.Map<String, String> vars = new java.util.HashMap<>();
            vars.put("vip", "3");
            Expr.test(src, vars);                 // 语法错会抛 Expr.Error
            o.put("result", "语法通过");
            o.put("sample_eval", String.valueOf(Expr.test(src, vars)));
            o.put("note", "用的是网易粒子/条件表达式同一套解析器（Expr），用一组示例变量求的值；"
                    + "真实结果取决于玩家当时的变量");
            return o.toString();
        } catch (Expr.Error bad) {
            try {
                o.put("ok", false);
                o.put("error", "语法不过：" + bad.getMessage());
                o.put("hint", "改完再检查一次，别直接开页面");
            } catch (Throwable ignored) {
            }
            return o.toString();
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("error", "检查失败：" + why(t));
            } catch (Throwable ignored) {
            }
            return o.toString();
        }
    }

    // ---------------------------------------------------------------- 落盘与工具方法

    private static File downloadsRoot() {
        try {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写进公共 Download：Android 10+ 走 MediaStore（免权限），老系统退回 File API。
     * 与转换页那条同型，差别是这里<b>流式搬运</b> —— 材质包可能上百 MB，读成 byte[] 会当场吃满内存。
     *
     * @return 给人看的路径；写失败返回 null（调用方会退回把缓存路径说清楚）
     */
    private static String writeDownload(Context c, String relDir, File src) {
        String name = src.getName();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(name));
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + relDir);
                Uri uri = c.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            pump(in, os);
                            return "Download/" + relDir + "/" + name;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 落到下面那条 File API 路子
            }
        }
        try {
            File dir = new File(downloadsRoot(), relDir);
            mkdirs(dir);
            File out = new File(dir, name);
            try (InputStream in = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(out)) {
                pump(in, os);
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/zip";
    }

    private static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void deleteTree(File f) {
        try {
            if (f == null) {
                return;
            }
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        deleteTree(k);
                    }
                }
            }
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void mkdirs(File f) {
        if (f != null && !f.isDirectory()) {
            f.mkdirs();
        }
    }

    private static String arg(JSONObject a, String... names) {
        if (a != null) {
            for (String n : names) {
                String v = a.optString(n, "").trim();
                if (v.length() > 0) {
                    return v;
                }
            }
        }
        return "";
    }

    private static int intArg(JSONObject a, String... names) {
        String s = arg(a, names);
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String why(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.length() == 0 ? "" : ("：" + m));
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")
                .replace("\r", " ").replace("\t", " ");
    }

    /** 与转换页同一个适配器：png 字节 ↔ ARGB 像素。 */
    public static final class Adapter implements BedrockPackConverter.ImageAdapter {

        @Override
        public int[] loadArgb(byte[] png, int[] sizeOut) {
            try {
                Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (b == null) {
                    return null;
                }
                int w = b.getWidth();
                int h = b.getHeight();
                int[] px = new int[w * h];
                b.getPixels(px, 0, w, 0, 0, w, h);
                b.recycle();
                sizeOut[0] = w;
                sizeOut[1] = h;
                return px;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public byte[] savePng(int[] argb, int w, int h) {
            Bitmap b = null;
            try {
                b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                if (!b.compress(Bitmap.CompressFormat.PNG, 100, bo)) {
                    return null;
                }
                return bo.toByteArray();
            } catch (Throwable t) {
                return null;
            } finally {
                if (b != null) {
                    b.recycle();
                }
            }
        }
    }

}
