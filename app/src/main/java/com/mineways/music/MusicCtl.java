package com.mineways.music;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放状态的唯一进程内中转站。
 *
 * <p>音频在 {@link MusicService} 的 MediaPlayer 里，队列在网页的 JS 里；两边都只认这一个
 * 状态对象。界面（悬浮条 / 音乐页 / 通知栏）订阅它，服务更新它，网页通过桥读它。
 */
public final class MusicCtl {

    public interface Listener {
        void onMusicState();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean playing;
    private static volatile boolean preparing;
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile String cover = "";
    private static volatile String songId = "";
    private static volatile String url = "";
    private static volatile String route = "";
    private static volatile MusicLyric lyric = MusicLyric.EMPTY;
    private static volatile String error = "";
    private static volatile int positionMs;
    private static volatile int durationMs;

    /** 网页活着时由 MusicActivity 挂上，用来把通知栏/悬浮条的上一首下一首打回 JS 队列。 */
    public interface Remote {
        void action(String name);

        void seek(double seconds);
    }

    private static volatile Remote remote;

    private MusicCtl() {
    }

    public static void bindRemote(Remote r) {
        remote = r;
    }

    public static void unbindRemote(Remote r) {
        if (remote == r) {
            remote = null;
        }
    }

    public static Remote remote() {
        return remote;
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void notifyListeners() {
        for (Listener l : LISTENERS) {
            try {
                l.onMusicState();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    public static boolean playing() {
        return playing;
    }

    public static boolean preparing() {
        return preparing;
    }

    public static String title() {
        return title;
    }

    public static String artist() {
        return artist;
    }

    public static String cover() {
        return cover;
    }

    public static String songId() {
        return songId;
    }

    public static String error() {
        return error;
    }

    public static String url() {
        return url;
    }

    /** 跟完跳转之后这一句「直连 / 已改走 https / 只有明文」，是播放失败时唯一能说清协议问题的字段。 */
    public static String route() {
        return route;
    }

    public static void setRoute(String note) {
        route = note == null ? "" : note;
        notifyListeners();
    }

    /**
     * 网页把解析好的歌词行递过来（{@code 毫秒\t文本} 逐行）。换曲时由 {@link #setPreparing} 清空，
     * 所以下一首的胶囊不会挂着上一首的词。
     */
    public static void setLyric(String wire) {
        lyric = MusicLyric.parse(wire);
        notifyListeners();
    }

    public static MusicLyric lyric() {
        return lyric;
    }

    /** 现在该显示哪一句；没歌词或还没唱到就回空串，悬浮条据此退回歌名。 */
    public static String lyricLine() {
        return lyric.lineAt(positionMs);
    }

    /** 按任意位置取歌词：悬浮条要在两帧之间做平滑插值，不能只读最近一次上报。 */
    public static String lyricLineAt(int ms) {
        return lyric.lineAt(Math.max(0, ms));
    }

    public static int positionMs() {
        return positionMs;
    }

    public static int durationMs() {
        return durationMs;
    }

    public static boolean active() {
        return playing || preparing || positionMs > 0;
    }

    static void setPreparing(String newUrl, JSONObject meta) {
        preparing = true;
        error = "";
        route = "";
        url = newUrl == null ? "" : newUrl;
        applyMeta(meta);
        notifyListeners();
    }

    static void applyMeta(JSONObject meta) {
        if (meta == null) {
            return;
        }
        String nextId = meta.optString("songId", songId);
        if (!nextId.equals(songId)) {
            lyric = MusicLyric.EMPTY;          // 换曲：上一首的歌词行绝不能还挂在胶囊上
        }
        title = meta.optString("title", title);
        artist = meta.optString("artist", artist);
        cover = meta.optString("cover", cover);
        songId = meta.optString("songId", songId);
    }

    /** 网页里的元数据（歌词页/歌单点播都会更新），只是给通知栏与悬浮条看的。 */
    public static void pageMeta(JSONObject meta) {
        applyMeta(meta);
        notifyListeners();
    }

    /**
     * 网页会把 playing/paused/none 汇报过来。真正的播放状态以服务里的 MediaPlayer 为准，
     * 这里只用来兜住「服务还没回调、界面先切歌」那一瞬间。
     */
    public static void pageState(String state) {
        if ("playing".equals(state) && !preparing) {
            // 等服务回调，避免界面先亮成播放中而实际没出声
            return;
        }
    }

    static void setPlaying(boolean v, int pos, int dur) {
        playing = v;
        preparing = false;
        positionMs = Math.max(0, pos);
        durationMs = Math.max(0, dur);
        notifyListeners();
    }

    static void setProgress(int pos, int dur) {
        positionMs = Math.max(0, pos);
        durationMs = Math.max(0, dur);
        // 以前这里不通知：网页每 500ms 推 timeupdate，但悬浮条/通知栏收不到，进度和歌词像冻住。
        notifyListeners();
    }

    static void setError(String msg) {
        playing = false;
        preparing = false;
        error = msg == null ? "" : msg;
        notifyListeners();
    }

    static void reset() {
        playing = false;
        preparing = false;
        positionMs = 0;
        durationMs = 0;
        notifyListeners();
    }

    public static String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("playing", playing);
            o.put("preparing", preparing);
            o.put("title", title);
            o.put("artist", artist);
            o.put("cover", cover);
            o.put("songId", songId);
            o.put("url", url);
            o.put("route", route);
            // 「悬浮条为什么不显示歌词」的线索：收到几行、当前落在哪一句
            o.put("lyricRows", lyric.times.length);
            o.put("lyricLine", lyricLine());
            o.put("position", positionMs / 1000.0);
            o.put("duration", durationMs / 1000.0);
            o.put("error", error);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ 指令

    public static void play(Context c, String url, JSONObject meta) {
        Intent i = new Intent(c, MusicService.class).setAction(MusicService.A_PLAY);
        i.putExtra(MusicService.E_URL, url);
        i.putExtra(MusicService.E_META, meta == null ? "" : meta.toString());
        start(c, i);
    }

    public static void toggle(Context c) {
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_TOGGLE));
    }

    public static void pause(Context c) {
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_PAUSE));
    }

    public static void resume(Context c) {
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_RESUME));
    }

    public static void next(Context c) {
        if (remote != null) {
            try {
                remote.action("next");
                return;
            } catch (Throwable ignored) {
            }
        }
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_NEXT));
    }

    public static void prev(Context c) {
        if (remote != null) {
            try {
                remote.action("prev");
                return;
            } catch (Throwable ignored) {
            }
        }
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_PREV));
    }

    public static void seekTo(Context c, int ms) {
        Intent i = new Intent(c, MusicService.class).setAction(MusicService.A_SEEK);
        i.putExtra(MusicService.E_POS, ms);
        start(c, i);
    }

    public static void setVolume(Context c, float v) {
        MusicSources.setVolume(c, v);
        Intent i = new Intent(c, MusicService.class).setAction(MusicService.A_VOLUME);
        i.putExtra(MusicService.E_VOL, v);
        start(c, i);
    }

    public static void stop(Context c) {
        start(c, new Intent(c, MusicService.class).setAction(MusicService.A_STOP));
    }

    private static void start(Context c, Intent i) {
        Context app = c.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Throwable t) {
            // 后台启动受限时退回普通 startService；再失败就把原因摆到界面上
            try {
                app.startService(i);
            } catch (Throwable t2) {
                setError("启动播放服务失败：" + t2);
            }
        }
    }
}
