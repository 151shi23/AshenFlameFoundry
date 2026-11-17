/*
 * 音源配置。原版 MSCD 把公共实例写死在这里；移植进 App 后改由「云控 + 用户导入」决定：
 * 原生侧在页面加载前把 window.MW_MUSIC_CONFIG 塞进来（mw-boot.js 会在本模块求值前跑）。
 * 不做任何内置：没有配置时 backend 为空，页面会显示「未配置音源」而不是偷偷连公共实例。
 */
const fallback = {
    backend: '',
    proxy: '',
    backends: {
        meting: { base: '' },
        netease: { base: '' },
    },
    roots: [],
    wiki: { summaryApi: '', detailApi: '' },
    services: {
        search: 'meting',
        song: 'meting',
        url: 'meting',
        lyric: 'meting',
        album: 'netease',
        playlist: 'netease',
        artist: 'netease',
        user: 'netease',
        mv: 'netease',
    },
    timeout: 15000,
    retry: { maxRetries: 3, baseDelay: 1000 },
    quality: {
        levels: [
            { id: 128, label: '标准' },
            { id: 192, label: '较高' },
            { id: 320, label: 'HQ' },
            { id: 2000, label: '无损' },
        ],
        default: 320,
        download: 320,
    },
    mv: {
        levels: [
            { br: 480, label: '480P' },
            { br: 720, label: '720P' },
            { br: 1080, label: '1080P' },
        ],
        default: 1080,
        download: 1080,
    },
    download: { concurrency: 3, retry: 3, retryDelay: 1000 },
    ui: { perPage: 50, themeColor: '', defaultTheme: 'mono', themeManifest: 'themes/index.json' },
    about: {
        version: '2026-10',
        content: `
播放器内核移植自 [MSCD](https://github.com/ENA-QWQ/MSCD)（MIT License），
界面与交互归原作者；音源、后台播放与悬浮条由 MinewaysMobile 提供。

- 音源由云控下发或你在设置里自行导入，本应用不内置任何公共实例。
- 播放与下载仅限你有权访问的内容，请遵守当地法律与平台条款。
`,
    },
};

function deepMerge(base, over) {
    if (!over || typeof over !== 'object') return base;
    const out = Array.isArray(base) ? base.slice() : { ...base };
    for (const k of Object.keys(over)) {
        const v = over[k];
        if (v && typeof v === 'object' && !Array.isArray(v) && base && typeof base[k] === 'object' && !Array.isArray(base[k])) {
            out[k] = deepMerge(base[k], v);
        } else if (v !== undefined && v !== null && v !== '') {
            out[k] = v;
        }
    }
    return out;
}

const injected = (typeof window !== 'undefined' && window.MW_MUSIC_CONFIG) || {};
export const config = deepMerge(fallback, injected);
