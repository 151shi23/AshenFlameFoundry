/*
 * MinewaysMobile ↔ MSCD 移植层（原生播放 + 云控/用户音源 + 自定义音源脚本）。
 *
 * 三件事，全部在 app.js 求值之前完成：
 *   1) 把原生给的音源配置塞进 window.MW_MUSIC_CONFIG（config.js 会合并它）；
 *   2) 把 window.Audio 换成「原生播放代理」—— MSCD 的队列/歌词/界面一行不用改，
 *      但声音由 Android 的 MediaPlayer + 前台服务出，退到后台、锁屏都能放；
 *   3) 提供 MWDefine()，让用户导入的 .js 音源脚本注册进 Mscd 的 api 层。
 */

const bridge = () => (window.MWMusic ? window.MWMusic : null);

/* ------------------------------------------------------------------ 配置 */

try {
    const raw = bridge() && bridge().configJson();
    if (raw) window.MW_MUSIC_CONFIG = JSON.parse(raw);
} catch (e) {
    window.MW_MUSIC_CONFIG = window.MW_MUSIC_CONFIG || {};
}

const cfg = window.MW_MUSIC_CONFIG || {};
window.MW_HAS_BACKEND = !!(cfg.backends && cfg.backends.meting && cfg.backends.meting.base);

/* ------------------------------------------------- 原生播放代理（Audio 替身） */

const DISPATCH = 'mw-native-dispatch';

class MWNativeAudio extends EventTarget {
    constructor() {
        super();
        this.preload = 'auto';
        this.crossOrigin = null;
        this._src = '';
        this._volume = 1;
        this._paused = true;
        this._t = 0;
        this._dur = 0;
        this._pending = null;
        this._pendingTimer = null;
        this._errored = false;

        window.addEventListener(DISPATCH, (ev) => this._onNative(ev.detail || {}));
    }

    get src() { return this._src; }
    set src(v) {
        this._src = v || '';
        this._t = 0;
        this._dur = 0;
        this._errored = false;
    }

    get currentTime() { return this._t; }
    set currentTime(v) {
        const t = Math.max(0, Number(v) || 0);
        this._t = t;
        try { bridge() && bridge().seek(Math.round(t * 1000)); } catch (e) { }
    }

    get duration() { return this._dur; }
    get paused() { return this._paused; }
    get volume() { return this._volume; }
    set volume(v) {
        this._volume = Math.max(0, Math.min(1, Number(v) || 0));
        try { bridge() && bridge().setVolume(this._volume); } catch (e) { }
    }

    load() { /* 地址在 play() 时才交给原生，这里刻意什么都不做 */ }
    removeAttribute() { this._src = ''; }
    canPlayType() { return 'maybe'; }

    play() {
        if (this._pending) this._settle(new Error('被新的播放请求打断'));
        if (!this._src) return Promise.reject(new Error('没有选中的歌曲地址'));
        const b = bridge();
        if (!b) return Promise.reject(new Error('原生播放服务不可用'));

        // 同一首歌暂停后继续：交给原生 resume()，否则会从头重新加载
        let nativeState = null;
        try { nativeState = JSON.parse(b.state() || '{}'); } catch (e) { }
        const canResume = nativeState && nativeState.url === this._src
            && !nativeState.playing && nativeState.position > 0;

        return new Promise((resolve, reject) => {
            this._pending = { resolve, reject, at: Date.now() };
            this._pendingTimer = setTimeout(() => {
                this._settle(new Error('播放超时（15 秒没有收到原生的开始回调）'));
            }, 15000);
            try {
                if (canResume) b.resume();
                else b.play(this._src, JSON.stringify(window.__mwMeta || {}));
            } catch (e) {
                this._settle(new Error('调用原生播放失败：' + (e && e.message ? e.message : e)));
            }
        });
    }

    pause() {
        try { bridge() && bridge().pause(); } catch (e) { }
        this._paused = true;
        this.dispatchEvent(new Event('pause'));
    }

    _settle(err) {
        const p = this._pending;
        this._pending = null;
        if (this._pendingTimer) { clearTimeout(this._pendingTimer); this._pendingTimer = null; }
        if (!p) return;
        if (err) p.reject(err); else p.resolve();
    }

    _onNative(d) {
        switch (d.type) {
            case 'playing':
                this._paused = false;
                this._settle();
                this.dispatchEvent(new Event('playing'));
                break;
            case 'paused':
                this._paused = true;
                this.dispatchEvent(new Event('pause'));
                break;
            case 'timeupdate':
                if (typeof d.currentTime === 'number') this._t = d.currentTime;
                if (typeof d.duration === 'number' && d.duration > 0) this._dur = d.duration;
                this.dispatchEvent(new Event('timeupdate'));
                break;
            case 'loadedmetadata':
                if (typeof d.duration === 'number') this._dur = d.duration;
                this.dispatchEvent(new Event('loadedmetadata'));
                break;
            case 'ended':
                this._paused = true;
                this.dispatchEvent(new Event('ended'));
                break;
            case 'error':
                this._paused = true;
                this._errored = true;
                this._settle(new Error(d.message || '原生播放失败'));
                this.dispatchEvent(new Event('error'));
                break;
        }
    }
}

try {
    window.Audio = MWNativeAudio;
} catch (e) { }

/* 原生 → JS 的统一入口（MusicActivity 用 evaluateJavascript 调它） */
window.MWNativeAudio = {
    dispatch(type, payload) {
        try {
            window.dispatchEvent(new CustomEvent(DISPATCH, { detail: Object.assign({ type }, payload || {}) }));
        } catch (e) { }
    },
};

/* 原生点「上一首/下一首/播放」时，把动作交回 Mscd 的 Player（队列在 JS 里） */
window.MWRemote = {
    action(name) {
        const p = window.__mwPlayer;
        if (!p) return;
        try {
            if (name === 'toggle') p.toggle();
            else if (name === 'next') p.next();
            else if (name === 'prev') p.prev();
        } catch (e) { }
    },
    seek(sec) {
        const p = window.__mwPlayer;
        if (!p || !p.audio || !p.audio.duration) return;
        try { p.seek(Math.max(0, Math.min(1, sec / p.audio.duration))); } catch (e) { }
    },
};

/* ------------------------------------------------------- 用户音源脚本（JS） */

window.MWSources = window.MWSources || { list: [], byId: {} };

window.MWDefine = function (def) {
    if (!def || typeof def !== 'object') throw new Error('MWDefine 需要一个对象');
    const id = String(def.id || '').trim();
    if (!id) throw new Error('音源缺少 id');
    if (!/^[a-zA-Z0-9_.-]{1,48}$/.test(id)) throw new Error('音源 id 只能用字母数字._-');
    if (typeof def.search !== 'function' && typeof def.url !== 'function') {
        throw new Error('音源至少要提供 search() 或 url()');
    }
    const entry = {
        id,
        name: String(def.name || id),
        version: String(def.version || ''),
        hint: String(def.hint || ''),
        search: def.search,
        url: def.url,
        lyric: def.lyric,
        song: def.song,
    };
    window.MWSources.byId[id] = entry;
    if (!window.MWSources.list.some((s) => s.id === id)) window.MWSources.list.push(entry);
    return entry;
};

/** 当前搜索平台。落雪源的 musicInfo 要靠它决定填 songmid 还是 hash。 */
function lxPlatform() {
    const c = window.MW_MUSIC_CONFIG || {};
    return c.server
        || (c.backends && c.backends.meting && c.backends.meting.server)
        || 'netease';
}

async function loadUserSources() {
    if (!bridge() || !bridge().scriptsEnabled || !bridge().scriptsEnabled()) return;
    let manifest = [];
    try {
        const r = await fetch('/userdata/music-sources/manifest.json', { cache: 'no-store' });
        if (r.ok) manifest = await r.json();
    } catch (e) {
        return;
    }
    if (!Array.isArray(manifest)) return;
    for (const item of manifest) {
        if (!item || !item.file) continue;
        const url = '/userdata/music-sources/' + encodeURIComponent(item.file);
        try {
            // 落雪源是 CommonJS（靠 module.exports 导出），ESM 里没有 module 这个标识符，
            // 直接 import() 会 ReferenceError。所以先探一下格式，落雪的交给 shim 用 Function 执行。
            let code = '';
            try {
                const r = await fetch(url, { cache: 'no-store' });
                if (r.ok) code = await r.text();
            } catch (_) { /* 探不到就走 import */ }

            if (code && window.MWIsLxSource && window.MWIsLxSource(code)) {
                // 第二个参数传文件名（item.id），不要传 name —— 落雪源的名字常是中文，
                // 拿去当 id 会变成一串下划线
                const src = await window.MWLoadLxSource(url, item.id || item.file, lxPlatform());
                console.info('[mw] 落雪音源已注册：' + src.name);
                try { bridge() && bridge().report('script', '落雪音源已就绪：' + src.name); } catch (_) { }
            } else {
                await import(url);
            }
        } catch (e) {
            const msg = '音源脚本「' + (item.name || item.file) + '」加载失败：' +
                (e && e.message ? e.message : e);
            console.error('[mw] ' + msg);
            try { bridge() && bridge().report('script', msg); } catch (_) { }
        }
    }
}

/* --------------------------------------------------------- 落地时的状态同步 */

window.__mwSyncPlayer = function (player, store) {
    try {
        const raw = bridge() && bridge().state();
        if (!raw) return;
        const st = JSON.parse(raw);
        window.__mwNativeState = st;
        window.__mwMeta = st.meta || {};
        if (!st.playing) return;
        const q = store.get().queue;
        const cur = q.currentIndex >= 0 ? q.tracks[q.currentIndex] : null;
        if (cur && String(cur.id) === String(st.songId || cur.id)) {
            store.update({ queue: { ...q, isPlaying: true, currentTime: st.position || 0, duration: st.duration || 0 } });
            if (player.audio && player.audio._onNative) {
                player.audio._src = st.url || player.audio._src;
                player.audio._paused = false;
                player.audio._dur = st.duration || 0;
                player.audio._t = st.position || 0;
            }
        }
    } catch (e) { }
};

/* 未配置音源时，给一个能操作的提示，而不是让页面莫名报错 */
function bannerIfNoBackend() {
    if (window.MW_HAS_BACKEND) return;
    const box = document.createElement('div');
    box.id = 'mw-no-source';
    box.style.cssText = 'position:fixed;left:50%;top:18%;transform:translateX(-50%);z-index:99999;'
        + 'padding:18px 20px;border-radius:18px;max-width:86vw;text-align:center;'
        + 'background:rgba(24,24,28,.92);color:#fff;backdrop-filter:blur(18px);'
        + 'border:1px solid rgba(255,255,255,.14);font:14px/1.6 system-ui,-apple-system,sans-serif;'
        + 'box-shadow:0 18px 60px rgba(0,0,0,.55)';
    box.innerHTML = '<div style="font-size:16px;font-weight:600;margin-bottom:6px">还没有配置音源</div>'
        + '<div style="opacity:.72">云端没有下发，本机也没有导入。点下面的按钮填一个 Meting 兼容的 API 地址，或导入音源脚本。</div>'
        + '<div style="margin-top:14px;display:flex;gap:10px;justify-content:center">'
        + '<button id="mw-src-set" style="all:unset;cursor:pointer;padding:8px 16px;border-radius:999px;'
        + 'background:#fff;color:#111;font-weight:600">设置音源</button>'
        + '<button id="mw-src-js" style="all:unset;cursor:pointer;padding:8px 16px;border-radius:999px;'
        + 'border:1px solid rgba(255,255,255,.35);color:#fff">导入脚本</button></div>';
    document.body.appendChild(box);
    box.querySelector('#mw-src-set').onclick = () => { try { bridge().openSourceSetup(); } catch (e) { } };
    box.querySelector('#mw-src-js').onclick = () => { try { bridge().importScript(); } catch (e) { } };
}

/* --------------------------------------------------------- 和 App 一致的深色 */

try {
    // 站点默认跟随系统；App 是纯黑界面，跟随系统会在浅色手机上变成一整页白底。
    // 在 app.js 读状态之前把主题定成 dark（用户自己改过就尊重他的选择）。
    const raw = localStorage.getItem('meting-app-state');
    const st = raw ? JSON.parse(raw) : {};
    st.settings = st.settings || {};
    if (!st.settings.themeMode) {
        st.settings.themeMode = 'dark';
        localStorage.setItem('meting-app-state', JSON.stringify(st));
    }
} catch (e) { }

/* --------------------------------------------------------- 页面错误的可见出口 */

let jsErrors = 0;

addEventListener('error', (e) => {
    if (jsErrors++ > 2) return;            // 只报前三条，避免刷屏
    try {
        const where = String(e.filename || '').split('/').pop();
        bridge() && bridge().report('脚本', (e.message || '') + ' @' + where + ':' + (e.lineno || 0));
    } catch (_) { }
});

addEventListener('unhandledrejection', (e) => {
    const r = e && e.reason;
    const msg = r && (r.message || String(r));
    if (!msg) return;
    try { bridge() && bridge().report('异步', msg); } catch (_) { }
});

/* ------------------------------------------------------------------- 启动 */

(async function boot() {
    await loadUserSources();
    const ready = () => {
        bannerIfNoBackend();
        try { bridge() && bridge().ready(); } catch (e) { }
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', ready, { once: true });
    } else {
        ready();
    }
})();
