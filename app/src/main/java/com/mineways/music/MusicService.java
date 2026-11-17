package com.mineways.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 真正出声的地方：MediaPlayer + 前台服务 + MediaSession + 通知栏。
 *
 * <p>网页只是「点歌台」——地址解析、队列顺序都在 JS 里，声音与锁屏控制在这里。
 * 所以退到后台、锁屏、切到别的 App 都不断，回来时 {@link MusicCtl} 把状态回推给网页。
 */
public class MusicService extends Service {

    public static final String A_PLAY = "com.mineways.music.PLAY";
    public static final String A_TOGGLE = "com.mineways.music.TOGGLE";
    public static final String A_PAUSE = "com.mineways.music.PAUSE";
    public static final String A_RESUME = "com.mineways.music.RESUME";
    public static final String A_NEXT = "com.mineways.music.NEXT";
    public static final String A_PREV = "com.mineways.music.PREV";
    public static final String A_SEEK = "com.mineways.music.SEEK";
    public static final String A_VOLUME = "com.mineways.music.VOLUME";
    public static final String A_STOP = "com.mineways.music.STOP";

    public static final String E_URL = "url";
    public static final String E_META = "meta";
    public static final String E_POS = "pos";
    public static final String E_VOL = "vol";

    private static final String CHANNEL = "mw_music";
    private static final int NOTIF_ID = 0x4D55;
    private static final String UA = "MinewaysMobile";

    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 每次点歌 +1：后台跟跳转那段时间里用户切歌/停止时，陈旧回调要能认出来并作废。 */
    private volatile int playSeq;
    private boolean foregroundStarted;
    private boolean pausedByFocus;
    private Bitmap artwork;
    private String artworkFor = "";
    private long lastNotifAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    MusicCtl.setProgress(pos, dur);
                    MusicCtl.notifyListeners();
                    // 必须回推网页：HTMLAudioElement 在浏览器里自带 timeupdate，换成原生代理后
                    // 这一路没人喂，进度就是死的 —— 歌词不高亮不滚动、进度条冻住就是这么来的
                    pushToPage("timeupdate", pos, dur);
                    pushSession(pos, dur);
                    long now = System.currentTimeMillis();
                    if (now - lastNotifAt > 1000) {
                        lastNotifAt = now;
                        updateNotification();
                    }
                } catch (Throwable ignored) {
                }
                ui.postDelayed(this, 500);
            }
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                pausedByFocus = false;
                pause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pausedByFocus = isPlaying();
                pause();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                if (pausedByFocus) {
                    pausedByFocus = false;
                    resume();
                }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                try {
                    if (player != null) {
                        player.setVolume(0.25f, 0.25f);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mineways:music");
            wakeLock.setReferenceCounted(false);
        }
        initSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 只要是被 startForegroundService 拉起来的，就必须在 5 秒内 startForeground，否则系统直接崩
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        foregroundStarted = true;

        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return START_NOT_STICKY;
        }
        switch (action) {
            case A_PLAY:
                play(intent.getStringExtra(E_URL), parseMeta(intent.getStringExtra(E_META)));
                break;
            case A_TOGGLE:
                if (isPlaying()) {
                    pause();
                } else {
                    resume();
                }
                break;
            case A_PAUSE:
                pause();
                break;
            case A_RESUME:
                resume();
                break;
            case A_NEXT:
                remoteAction("next");
                break;
            case A_PREV:
                remoteAction("prev");
                break;
            case A_SEEK:
                seekTo(intent.getIntExtra(E_POS, 0));
                break;
            case A_VOLUME:
                setVolume(intent.getFloatExtra(E_VOL, 1f));
                break;
            case A_STOP:
                stopEverything();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(ticker);
        try {
            unregisterReceiver(noisy);
        } catch (Throwable ignored) {
        }
        abandonFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        releasePlayer();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        MusicCtl.reset();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 播放

    private static JSONObject parseMeta(String raw) {
        if (raw == null || raw.length() == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private void play(String url, JSONObject meta) {
        if (url == null || url.length() == 0) {
            MusicCtl.setError("没有拿到播放地址");
            updateNotification();
            return;
        }
        final int seq = ++playSeq;
        MusicCtl.setPreparing(url, meta);
        requestFocus();
        releasePlayer();
        updateNotification();

        // 音源给的是 302 端点，真实 CDN 可能是明文 http（实测酷狗就是）。直接把端址丢给
        // MediaPlayer，被明文策略拦下只会得到 1/-2147483648，等于没有原因 —— 先在后台跟到底。
        new Thread(() -> {
            String resolved;
            String note;
            try {
                MusicResolve.Result r = MusicResolve.audioUrl(url, UA);
                resolved = r.url;
                note = r.note;
            } catch (Throwable t) {
                if (seq != playSeq) {
                    return;                     // 用户已经切歌/停掉了，这条陈旧失败别去打扰
                }
                String msg = "音源取不到音频地址：" + MusicResolve.why(t);
                ui.post(() -> {
                    MusicCtl.setError(msg);
                    pushToPage("error", 0, 0, msg);
                    updateNotification();
                });
                return;
            }
            final String audio = resolved;
            final String route = note;
            ui.post(() -> {
                if (seq != playSeq) {
                    return;                     // 等地址这段时间里用户已经切歌了
                }
                MusicCtl.setRoute(route);
                openPlayer(audio, meta);
            });
        }, "mw-audio-resolve").start();
    }

    /** 拿到真实音频地址之后才建播放器。只能在主线程调（play() 的回调里）。 */
    private void openPlayer(String url, JSONObject meta) {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setVolume(MusicSources.volume(this), MusicSources.volume(this));

        player.setOnPreparedListener(mp -> {
            try {
                mp.start();
            } catch (Throwable ignored) {
            }
            if (wakeLock != null) {
                wakeLock.acquire(10 * 60 * 1000L);
            }
            MusicCtl.setPlaying(true, 0, mp.getDuration());
            updateMetadata(meta);
            pushSession(0, mp.getDuration());
            updateNotification();
            ui.removeCallbacks(ticker);
            ui.post(ticker);
            pushToPage("loadedmetadata", 0, mp.getDuration());
            pushToPage("playing", 0, mp.getDuration());
        });
        player.setOnCompletionListener(mp -> {
            MusicCtl.setPlaying(false, mp.getDuration(), mp.getDuration());
            pushToPage("ended", mp.getDuration(), mp.getDuration());
            updateNotification();
            releaseWake();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            // 1/-2147483648 是 framework 的 UNKNOWN_ERROR，什么也没说；能说的只有「哪个地址、怎么来的」
            String msg = "播放失败（" + what + "/" + extra + "）"
                    + (extra == Integer.MIN_VALUE ? " · framework 没给原因" : "")
                    + " · 实播=" + hostOf(url)
                    + (MusicCtl.route().length() == 0 ? "" : " · " + MusicCtl.route());
            MusicCtl.setError(msg);
            pushToPage("error", 0, 0, msg);
            updateNotification();
            releaseWake();
            return true;
        });
        player.setOnBufferingUpdateListener((mp, percent) -> {
            if (percent < 100 && percent >= 0) {
                MusicCtl.notifyListeners();
            }
        });

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            if (url.contains("music.126.net")) {
                headers.put("Referer", "https://music.163.com/");
            } else if (url.contains("kugou.com")) {
                // 跟完跳转的直链地址，CDN 认酷狗自家 Referer（实测不带也给 206，带上更稳）
                headers.put("Referer", "https://www.kugou.com/");
            }
            player.setDataSource(this, Uri.parse(url), headers);
            player.prepareAsync();
        } catch (Throwable t) {
            MusicCtl.setError("无法打开音频：" + t.getMessage());
            pushToPage("error", 0, 0, String.valueOf(t.getMessage()));
            updateNotification();
        }
    }

    private void pause() {
        if (player != null && isPlaying()) {
            try {
                player.pause();
            } catch (Throwable ignored) {
            }
        }
        MusicCtl.setPlaying(false, MusicCtl.positionMs(), MusicCtl.durationMs());
        pushToPage("paused", MusicCtl.positionMs(), MusicCtl.durationMs());
        releaseWake();
        pushSession(MusicCtl.positionMs(), MusicCtl.durationMs());
        updateNotification();
    }

    private void resume() {
        if (player == null) {
            return;
        }
        requestFocus();
        try {
            player.start();
            if (wakeLock != null) {
                wakeLock.acquire(10 * 60 * 1000L);
            }
            MusicCtl.setPlaying(true, player.getCurrentPosition(), player.getDuration());
            pushToPage("playing", player.getCurrentPosition(), player.getDuration());
            ui.removeCallbacks(ticker);
            ui.post(ticker);
            pushSession(player.getCurrentPosition(), player.getDuration());
            updateNotification();
        } catch (Throwable t) {
            MusicCtl.setError("继续播放失败：" + t.getMessage());
            updateNotification();
        }
    }

    private void seekTo(int ms) {
        if (player == null) {
            return;
        }
        try {
            player.seekTo(Math.max(0, ms));
            MusicCtl.setProgress(ms, player.getDuration());
            pushToPage("timeupdate", ms, player.getDuration());
            pushSession(ms, player.getDuration());
            updateNotification();
        } catch (Throwable ignored) {
        }
    }

    private void setVolume(float v) {
        try {
            if (player != null) {
                player.setVolume(v, v);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopEverything() {
        // 作废还在路上的地址解析：不然「停止」之后跟跳转的线程回来还会把播放器重新起起来
        playSeq++;
        abandonFocus();
        releasePlayer();
        releaseWake();
        MusicCtl.reset();
        if (session != null) {
            session.setActive(false);
        }
        stopForeground(true);
        foregroundStarted = false;
        stopSelf();
    }

    private void releasePlayer() {
        ui.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private void remoteAction(String name) {
        MusicCtl.Remote r = MusicCtl.remote();
        if (r != null) {
            try {
                r.action(name);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 网页不在了：上一首/下一首没人算队列，只能把状态说清楚
        if (!"ended".equals(name)) {
            MusicCtl.setError("队列在播放页里，回到播放页再切歌");
            updateNotification();
        }
    }

    // ---------------------------------------------------------------- 焦点

    private void requestFocus() {
        if (audio == null) {
            return;
        }
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
        }
        try {
            audio.requestAudioFocus(focusRequest);
        } catch (Throwable ignored) {
        }
    }

    private void abandonFocus() {
        if (audio != null && focusRequest != null) {
            try {
                audio.abandonAudioFocusRequest(focusRequest);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 通知与会话

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, MusicService.class).setAction(act);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, code, i, flags);
    }

    private Notification buildNotification() {
        boolean playingNow = isPlaying();
        Intent open = new Intent(this, MusicActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 1, open, pflags);

        String t = MusicCtl.title().length() == 0 ? "音乐" : MusicCtl.title();
        String a = MusicCtl.artist().length() == 0 ? "MinewaysMobile" : MusicCtl.artist();
        if (MusicCtl.error().length() > 0) {
            a = MusicCtl.error();
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t)
                .setContentText(a)
                .setContentIntent(content)
                .setOngoing(playingNow)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "上一首", action(A_PREV, 11))
                .addAction(playingNow ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playingNow ? "暂停" : "播放", action(A_TOGGLE, 12))
                .addAction(android.R.drawable.ic_media_next, "下一首", action(A_NEXT, 13))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", action(A_STOP, 14));
        if (MusicCtl.durationMs() > 0) {
            b.setProgress(MusicCtl.durationMs(), MusicCtl.positionMs(), false);
        }
        if (artwork != null) {
            b.setLargeIcon(artwork);
        }
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    private void initSession() {
        session = new MediaSession(this, "mineways-music");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                remoteAction("next");
            }

            @Override
            public void onSkipToPrevious() {
                remoteAction("prev");
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                stopEverything();
            }
        });
        session.setActive(true);
    }

    private void pushSession(int pos, int dur) {
        if (session == null) {
            return;
        }
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_STOP;
            int state = isPlaying() ? PlaybackState.STATE_PLAYING
                    : (MusicCtl.preparing() ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(state, pos, isPlaying() ? 1f : 0f)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    private void updateMetadata(JSONObject meta) {
        if (session == null || meta == null) {
            return;
        }
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, meta.optString("title", ""))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.optString("artist", ""))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.optString("album", ""));
            if (durationMsSafe() > 0) {
                mb.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMsSafe());
            }
            session.setMetadata(mb.build());
        } catch (Throwable ignored) {
        }

        final String cover = meta.optString("cover", "");
        if (cover.length() == 0 || cover.equals(artworkFor)) {
            return;
        }
        artworkFor = cover;
        new Thread(() -> {
            Bitmap bmp = fetchCover(cover);
            if (bmp != null) {
                artwork = bmp;
                ui.post(this::updateNotification);
            }
        }, "mw-cover").start();
    }

    private int durationMsSafe() {
        try {
            return player == null ? 0 : player.getDuration();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 出错信息里只放主机名：整串签名地址又长又带 token，塞进状态条根本读不完。 */
    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null || h.length() == 0 ? url : h;
        } catch (Throwable t) {
            return url;
        }
    }

    private Bitmap fetchCover(String url) {
        HttpURLConnection conn = null;
        try {
            if (!url.startsWith("https://")) {
                return null;
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", UA);
            InputStream in = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(in);
            in.close();
            if (bmp == null) {
                return null;
            }
            int max = 512;
            if (bmp.getWidth() > max || bmp.getHeight() > max) {
                float s = max / (float) Math.max(bmp.getWidth(), bmp.getHeight());
                Bitmap small = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int) (bmp.getWidth() * s)),
                        Math.max(1, (int) (bmp.getHeight() * s)), true);
                if (small != bmp) {
                    bmp.recycle();
                }
                return small;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 把事件推给网页（网页不在就静默丢弃，状态仍在 MusicCtl 里）。 */
    private void pushToPage(String type, int pos, int dur) {
        pushToPage(type, pos, dur, "");
    }

    private void pushToPage(String type, int pos, int dur, String message) {
        MusicActivity.dispatchToPage(type, pos / 1000.0, dur / 1000.0, message);
    }
}
