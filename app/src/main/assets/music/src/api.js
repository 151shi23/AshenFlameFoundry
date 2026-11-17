import { config } from '../config.js';
import { store } from './store.js';

function applyApiProxy(url) {
    if (!url) return url;
    if (url.startsWith(config.proxy)) return url;
    let settings;
    try {
        settings = store.get().settings;
    } catch {
        return url;
    }
    if (!settings || !settings.apiProxy) return url;
    if (!config.proxy) return url;
    return config.proxy + encodeURIComponent(url);
}

function withTimeout(promise, ms) {
    return Promise.race([
        promise,
        new Promise((_, reject) =>
            setTimeout(() => reject(new Error('请求超时')), ms)
        ),
    ]);
}

async function fetchWithRetry(url, options = {}, retryConfig = config.retry) {
    const { maxRetries, baseDelay } = retryConfig;
    let lastError;
    url = applyApiProxy(upgradeToHttps(url));

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), config.timeout);

            const response = await fetch(url, {
                ...options,
                signal: controller.signal,
                redirect: 'follow',
            });

            clearTimeout(timeoutId);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            return response;
        } catch (error) {
            lastError = error;

            if (error.name === 'AbortError') {
                throw new Error('请求超时');
            }

            if (attempt === maxRetries) break;

            const delay = baseDelay * Math.pow(2, attempt - 1);
            await new Promise(resolve => setTimeout(resolve, delay));
        }
    }

    throw lastError;
}

function upgradeToHttps(url) {
    if (!url) return url;
    if (url.startsWith('http://')) return 'https://' + url.slice(7);
    return url;
}

function extractIdFromUrl(url) {
    if (!url) return '';
    try {
        return new URL(url).searchParams.get('id') || '';
    } catch {
        return '';
    }
}

// 导出来只为一件事：D:/scratch/music/api-check.mjs 用 node 直接跑这份真实文件断言字段兼容，
// 不再拿「我自己重写一遍」当验收（那次 97.6% vs 11.6% 的教训）。
export function normalizeSong(raw) {
    if (!raw || typeof raw !== 'object') return null;

    const artist = Array.isArray(raw.artist)
        ? raw.artist.filter(Boolean).join(' / ')
        : typeof raw.artist === 'string'
            ? raw.artist
            : Array.isArray(raw.artists)
                ? raw.artists.map(a => a?.name).filter(Boolean).join(' / ')
                // 有的 meting 实例（saop / mikus / 030456 这类只挂网易的）回的是 author 而不是 artist，
                // 不认它就会整站歌手名空白 —— 看着像源坏了
                : (typeof raw.author === 'string' ? raw.author
                    : (typeof raw.singer === 'string' ? raw.singer : ''));

    const album = typeof raw.album === 'string'
        ? raw.album
        : raw.album?.name
        ?? raw.al?.name
        ?? '';

    const idFromUrl = extractIdFromUrl(raw.url);
    const id = String(raw.id ?? raw.url_id ?? raw.song_id ?? idFromUrl ?? '');

    return {
        id,
        title: raw.name ?? raw.title ?? '',
        artist,
        artists: [],
        album,
        albumId: '',
        pop: null,
        // 封面与歌词也一律升 https：这些实例的 pic 直接给 http://p*.music.126.net，
        // 页面是 https 的（appassets.androidplatform.net），明文图会被混合内容拦成「封面永远加载失败」
        url: upgradeToHttps(raw.url ?? ''),
        pic: upgradeToHttps(raw.pic ?? raw.cover ?? ''),
        lrc: upgradeToHttps(raw.lrc ?? ''),
        duration: raw.duration ?? 0,
        mv: raw.mv != null ? String(raw.mv) : (raw.mvid != null ? String(raw.mvid) : ''),
    };
}

/*
 * 用户导入的音源脚本（mw-boot.js 里 MWDefine 注册）。脚本只跑在 WebView 沙箱内，
 * 拿到的是搜索词/歌曲对象，拿不到任何原生接口 —— 治理在原生侧另有一套开关。
 */
function normalizeScriptSong(raw, fallbackId = '') {
    if (!raw || typeof raw !== 'object') return null;
    const artist = Array.isArray(raw.artist)
        ? raw.artist.filter(Boolean).join(' / ')
        : (typeof raw.artist === 'string' ? raw.artist : (raw.artists || []).map(a => a?.name).filter(Boolean).join(' / '));
    return {
        id: String(raw.id ?? raw.songmid ?? raw.mid ?? fallbackId),
        title: raw.title ?? raw.name ?? '',
        artist,
        artists: Array.isArray(raw.artists) ? raw.artists : [],
        album: raw.album ?? raw.albumName ?? '',
        albumId: String(raw.albumId ?? ''),
        pop: typeof raw.pop === 'number' ? raw.pop : null,
        url: raw.url ?? '',
        pic: raw.pic ?? raw.cover ?? '',
        lrc: raw.lrc ?? '',
        duration: Number(raw.duration) || 0,
        mv: raw.mv != null ? String(raw.mv) : '',
    };
}

class ScriptAdapter {
    constructor(src) {
        this.src = src;
        this.name = 'script:' + src.id;
    }

    async search(keyword, { limit = 50, offset = 0, searchType = 1 } = {}) {
        if (typeof this.src.search !== 'function') return { items: [], total: 0 };
        const r = await this.src.search(keyword, { limit, offset, searchType });
        const list = Array.isArray(r) ? r : (r && (r.songs || r.items || r.data)) || [];
        const items = list.map(s => normalizeScriptSong(s)).filter(s => s && s.id);
        return { items, total: (r && r.total) || items.length };
    }

    async song(id) {
        if (typeof this.src.song !== 'function') return null;
        return normalizeScriptSong(await this.src.song(id), id);
    }

    async songUrl(id) {
        if (typeof this.src.url !== 'function') throw new Error('这个音源脚本没有提供取播放地址的能力');
        return this.src.url({ id });
    }

    async resolve(song) {
        if (typeof this.src.url !== 'function') throw new Error('这个音源脚本没有提供取播放地址的能力');
        const u = await this.src.url(song);
        if (!u || typeof u !== 'string') throw new Error('音源脚本没有返回播放地址');
        return u;
    }

    async lyric(id) {
        if (typeof this.src.lyric !== 'function') return '';
        return (await this.src.lyric({ id })) || '';
    }
}

class MetingAdapter {
    /**
     * 可以传单个根址（老用法），也可以传 [{url, server}] 列表：
     * 云控下发的每一条音源都带自己的平台，某条挂了自动换下一条。
     */
    constructor(baseOrRoots, server = 'netease', rotate = 0) {
        const list = Array.isArray(baseOrRoots) ? baseOrRoots : [{ url: baseOrRoots, server }];
        const roots = list
            .filter(r => r && (r.url || typeof r === 'string'))
            .map(r => (typeof r === 'string'
                ? { url: r, server }
                : { url: r.url, server: r.server || server }));
        this.roots = roots.length ? roots : [{ url: baseOrRoots || '', server }];
        // 云控 music_rotate：坏点时最多换几条。0 或越界的值一律回到「有几条换几条」。
        const n = Math.floor(Number(rotate));
        this.maxTry = n >= 1 && n <= this.roots.length ? n : this.roots.length;
        this.idx = 0;
    }

    get base() {
        return this.roots[this.idx % this.roots.length].url;
    }

    get server() {
        return this.roots[this.idx % this.roots.length].server || 'netease';
    }

    rotate() {
        if (this.roots.length > 1) {
            this.idx = (this.idx + 1) % this.roots.length;
        }
    }

    async _try(fn) {
        let lastError;
        const n = this.maxTry || this.roots.length;
        for (let k = 0; k < n; k++) {
            try {
                return await fn();
            } catch (e) {
                lastError = e;
                if (k < n - 1) this.rotate();
            }
        }
        throw lastError || new Error('没有可用的音源');
    }

    buildUrl(params) {
        const url = new URL(this.base);
        Object.entries(params).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== '') {
                url.searchParams.set(key, String(value));
            }
        });
        return url.toString();
    }

    async request(params) {
        return this._try(async () => {
            const url = this.buildUrl(params);
            const response = await fetchWithRetry(url);
            const text = await response.text();
            if (!text) return null;
            try {
                return JSON.parse(text);
            } catch {
                return text;
            }
        });
    }

    pickArray(data) {
        if (Array.isArray(data)) return data;
        if (data && Array.isArray(data.data)) return data.data;
        if (data && Array.isArray(data.result)) return data.result;
        if (data && Array.isArray(data.songs)) return data.songs;
        if (data && Array.isArray(data.tracks)) return data.tracks;
        if (data && Array.isArray(data.playlists)) return data.playlists;
        if (data && Array.isArray(data.albums)) return data.albums;
        return [];
    }

    async search(keyword, { limit = 50, offset = 0, searchType = 1 } = {}) {
        const page = Math.floor(offset / limit) + 1;
        const data = await this.request({
            server: this.server,
            type: 'search',
            id: keyword,
            search_type: searchType,
            limit,
            page,
        });
        const list = this.pickArray(data);
        const items = list.map(normalizeSong).filter(Boolean);
        return { items, total: null };
    }

    async song(id) {
        const data = await this.request({ server: this.server, type: 'song', id });
        const list = this.pickArray(data);
        return list.map(normalizeSong).filter(Boolean)[0] ?? null;
    }

    async songUrl(id, br) {
        return this._try(async () => {
            const url = upgradeToHttps(this.buildUrl({ server: this.server, type: 'url', id, br }));
            // 原生播放器自己会跟 302，而音频站（*.126.net 等）不带 CORS 头：
            // 网页去 fetch 它只会得到 Failed to fetch，所以这里直接把 302 入口地址交出去。
            if (typeof window !== 'undefined' && window.MWMusic) return url;
            const response = await fetch(url, {
                method: 'GET',
                redirect: 'follow',
                referrerPolicy: 'no-referrer',
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            const finalUrl = response.url;

            if (finalUrl && finalUrl !== url) {
                try { response.body?.cancel(); } catch {}
                return upgradeToHttps(finalUrl);
            }

            const text = (await response.text()).trim();
            if (text.startsWith('@')) return upgradeToHttps(text.slice(1));
            if (text.startsWith('http')) return upgradeToHttps(text);
            throw new Error('无法解析音频地址');
        });
    }

    async lyric(id) {
        try {
            const url = this.buildUrl({ server: this.server, type: 'lrc', id });
            const response = await fetchWithRetry(url, {}, { maxRetries: 2, baseDelay: 800 });
            return await response.text();
        } catch {
            return '';
        }
    }

    async playlist(id) {
        const data = await this.request({ server: this.server, type: 'playlist', id });
        const list = this.pickArray(data);
        return list.map(normalizeSong).filter(Boolean);
    }

    async album(id) {
        const data = await this.request({ server: this.server, type: 'album', id });
        const list = this.pickArray(data);
        return list.map(normalizeSong).filter(Boolean);
    }
}

class NeteaseAdapter {
    constructor(base) {
        this.base = base.replace(/\/+$/, '');
    }

    buildUrl(path, params = {}) {
        const url = new URL(this.base + path);
        Object.entries(params).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== '') {
                url.searchParams.set(key, String(value));
            }
        });
        return url.toString();
    }

    async request(path, params = {}) {
        const url = this.buildUrl(path, params);
        const response = await fetchWithRetry(url);
        const text = await response.text();
        if (!text) return null;
        return JSON.parse(text);
    }

    pickArray(list) {
        if (!Array.isArray(list)) return [];
        return list.filter(Boolean);
    }

    normalizeSong(item) {
        if (!item) return null;

        let artists = item.ar || item.artists || [];
        if (!Array.isArray(artists) && artists?.name) {
            artists = [artists];
        }
        const artistList = artists
            .map(a => ({ id: String(a?.id ?? ''), name: a?.name ?? '' }))
            .filter(a => a.name);
        const artist = artistList.map(a => a.name).join(' / ');

        const albumObj = item.al || item.album || {};
        const albumName = albumObj.name
            ?? item.al?.name
            ?? item.album?.name
            ?? '';
        const albumId = String(albumObj.id ?? item.al?.id ?? item.album?.id ?? '');
        const pic = albumObj.picUrl ?? albumObj.pic ?? '';

        let duration = Number(item.dt ?? item.duration ?? 0) || 0;
        if (duration > 10000) duration = Math.round(duration / 1000);

        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist,
            artists: artistList,
            album: albumName,
            albumId,
            pop: typeof item.pop === 'number' ? item.pop : null,
            url: '',
            pic,
            lrc: '',
            duration,
            mv: item.mv != null ? String(item.mv) : (item.mvid != null ? String(item.mvid) : ''),
        };
    }

    normalizeMv(item) {
        if (!item) return null;
        const rawArtists = Array.isArray(item.artists) ? item.artists : [];
        const artists = rawArtists
            .map(a => ({ id: String(a?.id ?? ''), name: a?.name ?? '' }))
            .filter(a => a.name);
        const artist = item.artistName || artists.map(a => a.name).join(' / ');
        const duration = Math.round((Number(item.duration) || 0) / 1000);
        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist,
            artists,
            album: '',
            albumId: '',
            pop: null,
            url: '',
            pic: upgradeToHttps(item.cover ?? ''),
            lrc: '',
            duration,
            mv: String(item.id ?? ''),
            _type: 'mv',
            _extra: {
                playCount: Number(item.playCount) || 0,
                briefDesc: item.briefDesc ?? '',
                desc: item.desc ?? '',
            },
        };
    }

    normalizeArtist(item) {
        if (!item) return null;
        const alias = Array.isArray(item.alias)
            ? item.alias.filter(Boolean).join(' / ')
            : (typeof item.alias === 'string' ? item.alias : '');
        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist: alias,
            url: '',
            pic: item.picUrl ?? item.img1v1Url ?? '',
            lrc: '',
            duration: 0,
            _type: 'artist',
            _extra: {
                albumSize: item.albumSize ?? 0,
                musicSize: item.musicSize ?? 0,
            },
        };
    }

    normalizeUser(item) {
        if (!item) return null;
        return {
            uid: String(item.userId ?? ''),
            nickname: item.nickname ?? '',
            avatarUrl: item.avatarUrl ?? '',
            signature: item.signature ?? '',
        };
    }

    normalizeUserPlaylist(item) {
        if (!item) return null;
        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist: item.creator?.nickname ?? '',
            pic: item.coverImgUrl ?? '',
            lrc: '',
            duration: 0,
            _type: 'playlist',
            _extra: {
                trackCount: item.trackCount ?? 0,
                playCount: item.playCount ?? 0,
                description: item.description ?? '',
            },
            _creatorId: String(item.creator?.userId ?? ''),
            _specialType: item.specialType ?? 0,
            _privacy: item.privacy ?? 0,
            _subscribed: item.subscribed === true,
        };
    }

    normalizeAlbum(item) {
        if (!item) return null;
        const rawArtists = Array.isArray(item.artists) ? item.artists : (item.artist ? [item.artist] : []);
        const artists = rawArtists
            .map(a => ({ id: String(a?.id ?? ''), name: a?.name ?? '' }))
            .filter(a => a.name);
        const artist = artists.map(a => a.name).join(' / ');
        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist,
            artists,
            url: '',
            pic: item.picUrl ?? item.blurPicUrl ?? '',
            lrc: '',
            duration: 0,
            _type: 'album',
            _extra: {
                size: item.size ?? 0,
                publishTime: item.publishTime ?? 0,
                description: item.description ?? '',
            },
        };
    }

    normalizePlaylist(item) {
        if (!item) return null;
        return {
            id: String(item.id ?? ''),
            title: item.name ?? '',
            artist: item.creator?.nickname ?? '',
            url: '',
            pic: item.coverImgUrl ?? '',
            lrc: '',
            duration: 0,
            _type: 'playlist',
            _extra: {
                trackCount: item.trackCount ?? 0,
                playCount: item.playCount ?? 0,
                description: item.description ?? '',
            },
        };
    }

    async search(keyword, { limit = 30, offset = 0, searchType = 1 } = {}) {
        const data = await this.request('/cloudsearch', {
            keywords: keyword,
            type: searchType,
            limit,
            offset,
        });

        if (!data || !data.result) return { items: [], total: 0 };

        const r = data.result;
        let items = [];
        let total = 0;

        if (searchType === 1) {
            items = this.pickArray(r.songs || []).map(s => this.normalizeSong(s));
            total = r.songCount ?? items.length;
        } else if (searchType === 10) {
            items = this.pickArray(r.albums || []).map(a => this.normalizeAlbum(a));
            total = r.albumCount ?? items.length;
        } else if (searchType === 100) {
            items = this.pickArray(r.artists || []).map(a => this.normalizeArtist(a));
            total = r.artistCount ?? items.length;
        } else if (searchType === 1000) {
            items = this.pickArray(r.playlists || []).map(p => this.normalizePlaylist(p));
            total = r.playlistCount ?? items.length;
        } else if (searchType === 1004) {
            items = this.pickArray(r.mvs || []).map(m => this.normalizeMv(m)).filter(Boolean);
            total = r.mvCount ?? items.length;
        }

        return { items, total };
    }

    async song(id) {
        const data = await this.request('/song/detail', { ids: id });
        const songs = data?.songs || [];
        return songs.length ? this.normalizeSong(songs[0]) : null;
    }

    async songUrl(id, br) {
        const brLevel = br >= 2000 ? 999000 : br >= 320 ? 320000 : 128000;

        const data = await this.request('/song/url', { id, br: brLevel });
        const item = data?.data?.[0];

        if (!item || !item.url) {
            try {
                const level = br >= 2000 ? 'lossless' : br >= 320 ? 'exhigh' : 'standard';
                const data2 = await this.request('/song/url/v1', { id, level });
                const item2 = data2?.data?.[0];
                if (item2?.url) return upgradeToHttps(item2.url);
            } catch {
            }
            throw new Error('无法获取歌曲 URL');
        }

        return upgradeToHttps(item.url);
    }

    async lyric(id) {
        try {
            const data = await this.request('/lyric', { id });
            return data?.lrc?.lyric ?? '';
        } catch {
            return '';
        }
    }

    async lyricNew(id) {
        try {
            const url = applyApiProxy(upgradeToHttps(this.buildUrl('/lyric/new', { id })));
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 30000);
            let response;
            try {
                response = await fetch(url, {
                    signal: controller.signal,
                    redirect: 'follow',
                });
            } finally {
                clearTimeout(timer);
            }
            if (!response.ok) return { lrc: '', yrc: '', tlyric: '' };
            const data = JSON.parse(await response.text());
            return {
                lrc: data?.lrc?.lyric ?? '',
                yrc: data?.yrc?.lyric ?? '',
                tlyric: data?.tlyric?.lyric ?? '',
            };
        } catch {
            return { lrc: '', yrc: '', tlyric: '' };
        }
    }

    async playlist(id) {
        const data = await this.request('/playlist/detail', { id });
        const playlist = data?.playlist;
        if (!playlist) return [];

        const total = playlist.trackCount || 0;
        const pageSize = 500;
        const pages = Math.max(1, Math.ceil(total / pageSize));

        const tasks = [];
        for (let i = 0; i < pages; i++) {
            tasks.push(this.request('/playlist/track/all', {
                id,
                limit: pageSize,
                offset: i * pageSize,
            }));
        }

        const results = await Promise.all(tasks);
        const all = [];
        for (const r of results) {
            if (r && Array.isArray(r.songs)) {
                for (const s of r.songs) {
                    const song = this.normalizeSong(s);
                    if (song) all.push(song);
                }
            }
        }

        if (!all.length) {
            return (playlist.tracks || []).map(t => this.normalizeSong(t)).filter(Boolean);
        }

        return all;
    }

    async album(id) {
        const data = await this.request('/album', { id });
        const songs = data?.songs || [];
        return songs.map(s => this.normalizeSong(s)).filter(Boolean);
    }

    async albumInfo(id) {
        const data = await this.request('/album', { id });
        const album = data?.album;
        if (!album) return null;
        return this.normalizeAlbum(album);
    }

    async playlistInfo(id) {
        const data = await this.request('/playlist/detail', { id });
        const playlist = data?.playlist;
        if (!playlist) return null;
        return this.normalizePlaylist(playlist);
    }

    async artistDetail(id) {
        const data = await this.request('/artist/detail', { id });
        const a = data?.data?.artist;
        if (!a) throw new Error('无法获取歌手信息');
        const alias = Array.isArray(a.alias)
            ? a.alias.filter(Boolean).join(' / ')
            : (typeof a.alias === 'string' ? a.alias : '');
        return {
            id: String(a.id ?? id),
            name: a.name ?? '',
            pic: a.cover ?? a.avatar ?? '',
            alias,
            briefDesc: a.briefDesc ?? '',
            albumSize: a.albumSize ?? 0,
            musicSize: a.musicSize ?? 0,
            mvSize: a.mvSize ?? 0,
        };
    }

    async artistSongs(id, { limit = 50, offset = 0 } = {}) {
        const data = await this.request('/artists', { id, limit, offset });
        const songs = (data?.hotSongs || []).map(s => this.normalizeSong(s)).filter(Boolean);
        return { songs };
    }

    async artistAlbums(id, { limit = 50, offset = 0 } = {}) {
        const data = await this.request('/artist/album', { id, limit, offset });
        const albums = (data?.hotAlbums || []).map(a => this.normalizeAlbum(a)).filter(Boolean);
        const total = data?.artist?.albumSize || albums.length;
        return { albums, total };
    }

    async searchUser(keyword, { limit = 30, offset = 0 } = {}) {
        const data = await this.request('/cloudsearch', {
            keywords: keyword,
            type: 1002,
            limit,
            offset,
        });
        const r = data?.result;
        if (!r) return { users: [], total: 0 };
        const users = (r.userprofiles || []).map(u => this.normalizeUser(u)).filter(Boolean);
        return { users, total: r.userprofileCount ?? users.length };
    }

    async userDetail(uid) {
        const data = await this.request('/user/detail', { uid });
        const profile = data?.profile;
        if (!profile) throw new Error('无法获取用户信息');
        return {
            uid: String(profile.userId ?? uid),
            nickname: profile.nickname ?? '',
            avatarUrl: profile.avatarUrl ?? '',
            signature: profile.signature ?? '',
            description: profile.description ?? '',
            playlistCount: profile.playlistCount ?? 0,
            followeds: profile.followeds ?? 0,
            follows: profile.follows ?? 0,
            level: profile.level ?? 0,
            listenSongs: profile.listenSongs ?? 0,
        };
    }

    async userPlaylists(uid) {
        const data = await this.request('/user/playlist', { uid });
        const playlists = (data?.playlist || []).map(p => this.normalizeUserPlaylist(p)).filter(Boolean);
        return { playlists, more: data?.more === true };
    }

    async userFollows(uid, { limit = 100, offset = 0 } = {}) {
        const data = await this.request('/user/follows', { uid, limit, offset });
        const follows = (data?.follow || []).map((u) => {
            if (!u) return null;
            return {
                uid: String(u.userId ?? ''),
                nickname: u.nickname ?? '',
                avatarUrl: u.avatarUrl ?? '',
                signature: u.signature ?? '',
                userType: u.userType ?? 0,
                artistId: u.artistId ? String(u.artistId) : '',
                musicSize: u.musicSize ?? u.artist?.musicSize ?? 0,
                albumSize: u.albumSize ?? u.artist?.albumSize ?? 0,
            };
        }).filter(Boolean);
        return { follows, more: data?.more === true };
    }

    async playlistTrackAll(id, { limit = 500, offset = 0 } = {}) {
        const data = await this.request('/playlist/track/all', { id, limit, offset });
        const songs = (data?.songs || []).map(s => this.normalizeSong(s)).filter(Boolean);
        return { songs };
    }

    async mvDetail(mvid) {
        const data = await this.request('/mv/detail', { mvid });
        const detail = data?.data;
        if (!detail) throw new Error('无法获取 MV 详情');
        return {
            id: String(detail.id ?? mvid),
            name: detail.name ?? '',
            artistName: detail.artistName ?? '',
            cover: upgradeToHttps(detail.cover ?? ''),
            duration: Number(detail.duration ?? 0),
            playCount: detail.playCount ?? 0,
            publishTime: detail.publishTime ?? '',
            brs: Array.isArray(detail.brs)
                ? detail.brs.filter(b => b && typeof b.br === 'number')
                : [],
        };
    }

    async mvUrl(id, r = 1080) {
        const data = await this.request('/mv/url', { id, r });
        const item = data?.data;
        if (!item || !item.url) throw new Error('无法获取 MV 地址');
        return {
            url: upgradeToHttps(item.url),
            r: item.r ?? r,
            size: item.size ?? 0,
            expi: item.expi ?? 0,
        };
    }
}

export class Meting {
    constructor(cfg = config) {
        this.config = cfg;

        const backends = cfg.backends || {};
        const metingBase = (backends.meting && backends.meting.base) || cfg.backend || '';
        const neteaseBase = (backends.netease && backends.netease.base) || '';
        // netease 是 Meting 的默认音源；云控可以用 server=tencent/kugou/kuwo/migu 换台
        const server = cfg.server || cfg.metingServer || 'netease';

        this.adapters = {
            meting: new MetingAdapter(
                (Array.isArray(cfg.roots) && cfg.roots.length) ? cfg.roots : [{ url: metingBase, server }],
                server,
                cfg.rotate),
        };

        const services = { ...(cfg.services || {}) };
        if (neteaseBase) {
            this.adapters.netease = new NeteaseAdapter(neteaseBase);
        } else {
            // 没配直连后端时，把走 netease 的能力全部回落给 Meting，免得拼出空地址
            for (const k of Object.keys(services)) {
                if (services[k] === 'netease') services[k] = 'meting';
            }
        }
        this.services = services;

        // 用户导入的 JS 音源优先接管 search/song/url/lyric
        const scriptId = cfg.customSource || '';
        const src = scriptId && window.MWSources && window.MWSources.byId
            ? window.MWSources.byId[scriptId]
            : null;
        if (src) {
            this.adapters.script = new ScriptAdapter(src);
            const hasSearch = typeof src.search === 'function';
            if (hasSearch) {
                for (const k of ['search', 'song', 'url', 'lyric']) this.services[k] = 'script';
            } else {
                // 落雪（LX Music）音源只声明 musicUrl / lyric / pic，**没有 search** ——
                // 它的价值是给 VIP 曲、版权曲解出播放地址，不是当搜索后端。
                // 所以只接管取地址，搜索继续走 Meting，否则选上落雪源后整站搜不出东西。
                this.services.url = 'script';
                if (typeof src.lyric === 'function') this.services.lyric = 'script';
            }
        }
    }

    pick(feature) {
        const name = this.services[feature] || 'meting';
        const adapter = this.adapters[name];
        if (!adapter) throw new Error(`未知后端: ${name}`);
        return adapter;
    }

    async enrichSongsWithAlbum(songs) {
        if (!Array.isArray(songs) || !songs.length) return songs;

        const needs = songs.filter((s) => s && s.id && (!s.album || !s.albumId || !s.artists || !s.artists.length));
        if (!needs.length) return songs;

        const netease = this.adapters && this.adapters.netease;
        if (!netease || typeof netease.request !== 'function') return songs;

        const detailById = new Map();
        const ids = needs.map((s) => String(s.id));
        const BATCH = 100;

        for (let i = 0; i < ids.length; i += BATCH) {
            const batch = ids.slice(i, i + BATCH);
            try {
                const data = await netease.request('/song/detail', { ids: batch.join(',') });
                const list = (data && data.songs) || [];
                for (const ds of list) {
                    const albumName = ds?.al?.name || ds?.album?.name || '';
                    const albumId = String(ds?.al?.id ?? ds?.album?.id ?? '');
                    const rawArtists = ds?.ar || ds?.artists || [];
                    const artists = (Array.isArray(rawArtists) ? rawArtists : [])
                        .map(a => ({ id: String(a?.id ?? ''), name: a?.name ?? '' }))
                        .filter(a => a.name);
                    const pop = typeof ds?.pop === 'number' ? ds.pop : null;
                    detailById.set(String(ds.id), { album: albumName, albumId, artists, pop });
                }
            } catch {}
        }

        if (!detailById.size) return songs;

        return songs.map((s) => {
            if (!s || !s.id) return s;
            const info = detailById.get(String(s.id));
            if (!info) return s;
            const out = { ...s };
            if (!out.album && info.album) out.album = info.album;
            if (!out.albumId && info.albumId) out.albumId = info.albumId;
            if ((!out.artists || !out.artists.length) && info.artists.length) out.artists = info.artists;
            if (out.pop == null && info.pop != null) out.pop = info.pop;
            return out;
        });
    }

    async search(keyword, options = {}) {
        const adapter = this.pick('search');
        const searchType = options.searchType ?? 1;
        return adapter.search(keyword, { ...options, searchType });
    }

    async song(id, options = {}) {
        const song = await this.pick('song').song(id, options);
        if (!song) return song;
        const enriched = await this.enrichSongsWithAlbum([song]);
        return enriched[0] || song;
    }

    async resolveAudio(song, quality) {
        if (!song?.id) throw new Error('歌曲缺少 ID');
        const adapter = this.pick('url');
        if (adapter instanceof ScriptAdapter) return adapter.resolve(song, quality);
        return adapter.songUrl(song.id, quality);
    }

    async resolveLyric(song) {
        if (!song?.id) return '';
        const netease = this.adapters && this.adapters.netease;
        if (netease && typeof netease.lyricNew === 'function') {
            try {
                const result = await netease.lyricNew(song.id);
                if (result && (result.yrc || result.lrc)) return result;
            } catch {}
        }
        const adapter = this.pick('lyric');
        const text = await adapter.lyric(song.id);
        return text || '';
    }

    async resolveCover(song) {
        return song?.pic || '';
    }

    async playlist(id) {
        const songs = await this.pick('playlist').playlist(id);
        return await this.enrichSongsWithAlbum(songs);
    }

    async album(id) {
        const songs = await this.pick('album').album(id);
        return await this.enrichSongsWithAlbum(songs);
    }

    async albumInfo(id) {
        const adapter = this.pick('album');
        if (typeof adapter.albumInfo !== 'function') return null;
        return adapter.albumInfo(id);
    }

    async playlistInfo(id) {
        const adapter = this.pick('playlist');
        if (typeof adapter.playlistInfo === 'function') {
            try {
                const result = await adapter.playlistInfo(id);
                if (result) return result;
            } catch {}
        }
        const netease = this.adapters.netease;
        if (netease && typeof netease.playlistInfo === 'function') {
            try {
                const result = await netease.playlistInfo(id);
                if (result) return result;
            } catch {}
        }
        return null;
    }

    async artistDetail(id) {
        const adapter = this.pick('artist');
        if (typeof adapter.artistDetail !== 'function') throw new Error('当前后端不支持歌手详情');
        return adapter.artistDetail(id);
    }

    async artistSongs(id, options = {}) {
        const adapter = this.pick('artist');
        if (typeof adapter.artistSongs !== 'function') throw new Error('当前后端不支持歌手单曲');
        return adapter.artistSongs(id, options);
    }

    async artistAlbums(id, options = {}) {
        const adapter = this.pick('artist');
        if (typeof adapter.artistAlbums !== 'function') throw new Error('当前后端不支持歌手专辑');
        return adapter.artistAlbums(id, options);
    }

    async searchUser(keyword, options = {}) {
        return this.pick('user').searchUser(keyword, options);
    }

    async userDetail(uid) {
        return this.pick('user').userDetail(uid);
    }

    async userPlaylists(uid) {
        return this.pick('user').userPlaylists(uid);
    }

    async userFollows(uid, options = {}) {
        return this.pick('user').userFollows(uid, options);
    }

    async playlistTrackAll(id, options = {}) {
        return this.pick('user').playlistTrackAll(id, options);
    }

    async mvDetail(mvid) {
        const adapter = this.pick('mv');
        if (typeof adapter.mvDetail !== 'function') {
            throw new Error('当前后端不支持 MV 详情');
        }
        return adapter.mvDetail(mvid);
    }

    async mvUrl(id, r) {
        const adapter = this.pick('mv');
        if (typeof adapter.mvUrl !== 'function') {
            throw new Error('当前后端不支持 MV 地址');
        }
        return adapter.mvUrl(id, r);
    }

    async fetchAllPlaylistTracks(id, totalCount) {
        const adapter = this.pick('user');
        const pageSize = 500;
        const total = totalCount || 0;
        if (total <= 0) {
            const result = await adapter.playlistTrackAll(id, { limit: pageSize, offset: 0 });
            return result.songs;
        }
        const pages = Math.ceil(total / pageSize);
        const tasks = [];
        for (let i = 0; i < pages; i++) {
            tasks.push(adapter.playlistTrackAll(id, {
                limit: pageSize,
                offset: i * pageSize,
            }));
        }
        const results = await Promise.all(tasks);
        const all = [];
        for (const r of results) {
            if (r && r.songs) all.push(...r.songs);
        }
        return all;
    }

    getQualityLevels() {
        return this.config.quality.levels;
    }
}