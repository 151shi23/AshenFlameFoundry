/*
 * 落雪（LX Music）音源适配层
 * ============================================================
 *
 * 落雪源和本项目原来的自定义音源是**两套格式**：
 *
 *   本项目：  window.MWDefine({ id, name, search, url, lyric })
 *   落雪：    module.exports = { name, actions, qualitys, musicUrl(info, quality) }
 *            或 globalThis.lx.on(EVENT_NAMES.request, ...) + send(EVENT_NAMES.inited, ...)
 *
 * 这个文件干三件事：
 *   1. 造一个 `lx` 全局对象（落雪源依赖 request / utils.crypto / EVENT_NAMES）
 *   2. 用 Function 执行脚本并把 `module.exports` 接住
 *   3. 把落雪对象转成 MWDefine 能认的形状，注册进 MWSources
 *
 * 一个必须说清的限制：**落雪源不提供搜索**。
 * 它的 actions 只有 musicUrl / lyric / pic，搜索靠播放器自己的源。
 * 所以落雪源在这里的角色是「地址解析器」——搜索走网易/QQ，取地址时优先问它，
 * 这样 VIP 曲和版权曲才有地址。
 */
(function () {
    'use strict';

    /* ------------------------------------------------------------ md5
     * 落雪源高频依赖 lx.utils.crypto.md5（签名参数用）。WebView 没有 node crypto，
     * 这里给一个纯 JS 实现。
     *
     * 口径按官方 user-api-preload.js 来：native 那边算的是
     * `md5(encodeURIComponent(str))` —— 先把字符串转成 %XX 形式，再对那串 ASCII 求 md5。
     * 纯 ASCII 输入两种算法结果一样，但中文完全不同，所以这里必须先 encodeURIComponent。 */
    function md5(str) {
        str = encodeURIComponent(String(str));
        function cmn(q, a, b, x, s, t) {
            a = (a + q + x + t) | 0;
            return ((a << s) | (a >>> (32 - s))) + b | 0;
        }

        function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
        function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
        function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
        function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }

        function md5blk(s) {
            const blks = [];
            for (let i = 0; i < 64; i += 4) {
                blks[i >> 2] = s.charCodeAt(i) + (s.charCodeAt(i + 1) << 8)
                    + (s.charCodeAt(i + 2) << 16) + (s.charCodeAt(i + 3) << 24);
            }
            return blks;
        }

        function rhex(n) {
            const s = '0123456789abcdef';
            let out = '';
            for (let j = 0; j < 4; j++) {
                out += s.charAt((n >> (j * 8 + 4)) & 0x0F) + s.charAt((n >> (j * 8)) & 0x0F);
            }
            return out;
        }

        // UTF-8 编码成字节串
        let bytes = '';
        const utf8 = unescape(encodeURIComponent(String(str)));
        for (let i = 0; i < utf8.length; i++) bytes += String.fromCharCode(utf8.charCodeAt(i));

        const n = bytes.length;
        const state = [1732584193, -271733879, -1732584194, 271733878];
        let i;
        for (i = 64; i <= bytes.length; i += 64) {
            const blk = md5blk(bytes.substring(i - 64, i));
            let a = state[0], b = state[1], c = state[2], d = state[3];
            a = ff(a, b, c, d, blk[0], 7, -680876936); d = ff(d, a, b, c, blk[1], 12, -389564586);
            c = ff(c, d, a, b, blk[2], 17, 606105819); b = ff(b, c, d, a, blk[3], 22, -1044525330);
            a = ff(a, b, c, d, blk[4], 7, -176418897); d = ff(d, a, b, c, blk[5], 12, 1200080426);
            c = ff(c, d, a, b, blk[6], 17, -1473231341); b = ff(b, c, d, a, blk[7], 22, -45705983);
            a = ff(a, b, c, d, blk[8], 7, 1770035416); d = ff(d, a, b, c, blk[9], 12, -1958414417);
            c = ff(c, d, a, b, blk[10], 17, -42063); b = ff(b, c, d, a, blk[11], 22, -1990404162);
            a = ff(a, b, c, d, blk[12], 7, 1804603682); d = ff(d, a, b, c, blk[13], 12, -40341101);
            c = ff(c, d, a, b, blk[14], 17, -1502002290); b = ff(b, c, d, a, blk[15], 22, 1236535329);
            a = gg(a, b, c, d, blk[1], 5, -165796510); d = gg(d, a, b, c, blk[6], 9, -1069501632);
            c = gg(c, d, a, b, blk[11], 14, 643717713); b = gg(b, c, d, a, blk[0], 20, -373897302);
            a = gg(a, b, c, d, blk[5], 5, -701558691); d = gg(d, a, b, c, blk[10], 9, 38016083);
            c = gg(c, d, a, b, blk[15], 14, -660478335); b = gg(b, c, d, a, blk[4], 20, -405537848);
            a = gg(a, b, c, d, blk[9], 5, 568446438); d = gg(d, a, b, c, blk[14], 9, -1019803690);
            c = gg(c, d, a, b, blk[3], 14, -187363961); b = gg(b, c, d, a, blk[8], 20, 1163531501);
            a = gg(a, b, c, d, blk[13], 5, -1444681467); d = gg(d, a, b, c, blk[2], 9, -51403784);
            c = gg(c, d, a, b, blk[7], 14, 1735328473); b = gg(b, c, d, a, blk[12], 20, -1926607734);
            a = hh(a, b, c, d, blk[5], 4, -378558); d = hh(d, a, b, c, blk[8], 11, -2022574463);
            c = hh(c, d, a, b, blk[11], 16, 1839030562); b = hh(b, c, d, a, blk[14], 23, -35309556);
            a = hh(a, b, c, d, blk[1], 4, -1530992060); d = hh(d, a, b, c, blk[4], 11, 1272893353);
            c = hh(c, d, a, b, blk[7], 16, -155497632); b = hh(b, c, d, a, blk[10], 23, -1094730640);
            a = hh(a, b, c, d, blk[13], 4, 681279174); d = hh(d, a, b, c, blk[0], 11, -358537222);
            c = hh(c, d, a, b, blk[3], 16, -722521979); b = hh(b, c, d, a, blk[6], 23, 76029189);
            a = hh(a, b, c, d, blk[9], 4, -640364487); d = hh(d, a, b, c, blk[12], 11, -421815835);
            c = hh(c, d, a, b, blk[15], 16, 530742520); b = hh(b, c, d, a, blk[2], 23, -995338651);
            a = ii(a, b, c, d, blk[0], 6, -198630844); d = ii(d, a, b, c, blk[7], 10, 1126891415);
            c = ii(c, d, a, b, blk[14], 15, -1416354905); b = ii(b, c, d, a, blk[5], 21, -57434055);
            a = ii(a, b, c, d, blk[12], 6, 1700485571); d = ii(d, a, b, c, blk[3], 10, -1894986606);
            c = ii(c, d, a, b, blk[10], 15, -1051523); b = ii(b, c, d, a, blk[1], 21, -2054922799);
            a = ii(a, b, c, d, blk[8], 6, 1873313359); d = ii(d, a, b, c, blk[15], 10, -30611744);
            c = ii(c, d, a, b, blk[6], 15, -1560198380); b = ii(b, c, d, a, blk[13], 21, 1309151649);
            a = ii(a, b, c, d, blk[4], 6, -145523070); d = ii(d, a, b, c, blk[11], 10, -1120210379);
            c = ii(c, d, a, b, blk[2], 15, 718787259); b = ii(b, c, d, a, blk[9], 21, -343485551);
            state[0] = (state[0] + a) | 0;
            state[1] = (state[1] + b) | 0;
            state[2] = (state[2] + c) | 0;
            state[3] = (state[3] + d) | 0;
        }

        const tail = bytes.substring(i - 64);
        const blk = new Array(16).fill(0);
        for (let j = 0; j < tail.length; j++) {
            blk[j >> 2] |= tail.charCodeAt(j) << ((j % 4) << 3);
        }
        blk[tail.length >> 2] |= 0x80 << ((tail.length % 4) << 3);
        if (tail.length > 55) {
            for (let j = 0; j < 16; j++) if (!blk[j]) blk[j] = 0;
        }
        blk[14] = n * 8;

        let a = state[0], b = state[1], c = state[2], d = state[3];
        a = ff(a, b, c, d, blk[0], 7, -680876936); d = ff(d, a, b, c, blk[1], 12, -389564586);
        c = ff(c, d, a, b, blk[2], 17, 606105819); b = ff(b, c, d, a, blk[3], 22, -1044525330);
        a = ff(a, b, c, d, blk[4], 7, -176418897); d = ff(d, a, b, c, blk[5], 12, 1200080426);
        c = ff(c, d, a, b, blk[6], 17, -1473231341); b = ff(b, c, d, a, blk[7], 22, -45705983);
        a = ff(a, b, c, d, blk[8], 7, 1770035416); d = ff(d, a, b, c, blk[9], 12, -1958414417);
        c = ff(c, d, a, b, blk[10], 17, -42063); b = ff(b, c, d, a, blk[11], 22, -1990404162);
        a = ff(a, b, c, d, blk[12], 7, 1804603682); d = ff(d, a, b, c, blk[13], 12, -40341101);
        c = ff(c, d, a, b, blk[14], 17, -1502002290); b = ff(b, c, d, a, blk[15], 22, 1236535329);
        a = gg(a, b, c, d, blk[1], 5, -165796510); d = gg(d, a, b, c, blk[6], 9, -1069501632);
        c = gg(c, d, a, b, blk[11], 14, 643717713); b = gg(b, c, d, a, blk[0], 20, -373897302);
        a = gg(a, b, c, d, blk[5], 5, -701558691); d = gg(d, a, b, c, blk[10], 9, 38016083);
        c = gg(c, d, a, b, blk[15], 14, -660478335); b = gg(b, c, d, a, blk[4], 20, -405537848);
        a = gg(a, b, c, d, blk[9], 5, 568446438); d = gg(d, a, b, c, blk[14], 9, -1019803690);
        c = gg(c, d, a, b, blk[3], 14, -187363961); b = gg(b, c, d, a, blk[8], 20, 1163531501);
        a = gg(a, b, c, d, blk[13], 5, -1444681467); d = gg(d, a, b, c, blk[2], 9, -51403784);
        c = gg(c, d, a, b, blk[7], 14, 1735328473); b = gg(b, c, d, a, blk[12], 20, -1926607734);
        a = hh(a, b, c, d, blk[5], 4, -378558); d = hh(d, a, b, c, blk[8], 11, -2022574463);
        c = hh(c, d, a, b, blk[11], 16, 1839030562); b = hh(b, c, d, a, blk[14], 23, -35309556);
        a = hh(a, b, c, d, blk[1], 4, -1530992060); d = hh(d, a, b, c, blk[4], 11, 1272893353);
        c = hh(c, d, a, b, blk[7], 16, -155497632); b = hh(b, c, d, a, blk[10], 23, -1094730640);
        a = hh(a, b, c, d, blk[13], 4, 681279174); d = hh(d, a, b, c, blk[0], 11, -358537222);
        c = hh(c, d, a, b, blk[3], 16, -722521979); b = hh(b, c, d, a, blk[6], 23, 76029189);
        a = hh(a, b, c, d, blk[9], 4, -640364487); d = hh(d, a, b, c, blk[12], 11, -421815835);
        c = hh(c, d, a, b, blk[15], 16, 530742520); b = hh(b, c, d, a, blk[2], 23, -995338651);
        a = ii(a, b, c, d, blk[0], 6, -198630844); d = ii(d, a, b, c, blk[7], 10, 1126891415);
        c = ii(c, d, a, b, blk[14], 15, -1416354905); b = ii(b, c, d, a, blk[5], 21, -57434055);
        a = ii(a, b, c, d, blk[12], 6, 1700485571); d = ii(d, a, b, c, blk[3], 10, -1894986606);
        c = ii(c, d, a, b, blk[10], 15, -1051523); b = ii(b, c, d, a, blk[1], 21, -2054922799);
        a = ii(a, b, c, d, blk[8], 6, 1873313359); d = ii(d, a, b, c, blk[15], 10, -30611744);
        c = ii(c, d, a, b, blk[6], 15, -1560198380); b = ii(b, c, d, a, blk[13], 21, 1309151649);
        a = ii(a, b, c, d, blk[4], 6, -145523070); d = ii(d, a, b, c, blk[11], 10, -1120210379);
        c = ii(c, d, a, b, blk[2], 15, 718787259); b = ii(b, c, d, a, blk[9], 21, -343485551);
        state[0] = (state[0] + a) | 0;
        state[1] = (state[1] + b) | 0;
        state[2] = (state[2] + c) | 0;
        state[3] = (state[3] + d) | 0;

        return rhex(state[0]) + rhex(state[1]) + rhex(state[2]) + rhex(state[3]);
    }

    /* ------------------------------------------------------------ lx 全局 */

    const EVENT_NAMES = {
        request: 'request',
        inited: 'inited',
        updateAlert: 'updateAlert',
    };
    const EVENT_LIST = Object.values(EVENT_NAMES);

    /**
     * 发一个 HTTP 请求，语义对齐官方 user-api-preload.js：
     *
     *   request(url, { method, timeout, headers, body, form, formData, binary }, callback)
     *     → callback(err, resp, body)
     *     → 返回值是**取消函数**，不是 Promise
     *
     * resp = { statusCode, statusMessage, headers, body }
     *
     * body 的处理：官方是 native 直接给的，实测各音源用法不一致 ——
     * juhe/huibq/ikun 直接写 `body.code`（要对象），xinghai 自己判断
     * `typeof body === 'string'` 再 parse（两种都认）。
     * 所以这里「能 parse 成 JSON 就给对象，否则给原字符串」，两边都能活。
     */
    function doRequest(url, options, callback) {
        const opts = options || {};
        const method = String(opts.method || 'get').toUpperCase();
        const headers = Object.assign({}, opts.headers || {});

        let body;
        if (opts.body != null) {
            body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
        } else if (opts.form) {
            const usp = new URLSearchParams();
            Object.keys(opts.form).forEach((k) => usp.append(k, String(opts.form[k])));
            body = usp.toString();
            if (!headers['Content-Type'] && !headers['content-type']) {
                headers['Content-Type'] = 'application/x-www-form-urlencoded';
            }
        } else if (opts.formData) {
            // multipart：音源极少用，先拼一个够用的
            const bd = '----mwLx' + Date.now();
            const parts = [];
            Object.keys(opts.formData).forEach((k) => {
                parts.push('--' + bd + '\r\nContent-Disposition: form-data; name="' + k + '"\r\n\r\n'
                    + opts.formData[k] + '\r\n');
            });
            body = parts.join('') + '--' + bd + '--\r\n';
            if (!headers['Content-Type'] && !headers['content-type']) {
                headers['Content-Type'] = 'multipart/form-data; boundary=' + bd;
            }
        }

        const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        const timer = opts.timeout && opts.timeout > 0
            ? setTimeout(() => { if (ctrl) ctrl.abort(); }, Math.min(opts.timeout, 60000))
            : null;

        const init = { method, headers };
        if (body != null && method !== 'GET' && method !== 'HEAD') init.body = body;
        if (ctrl) init.signal = ctrl.signal;

        let done = false;
        const finish = (err, resp, raw) => {
            if (done) return;
            done = true;
            if (timer) clearTimeout(timer);
            if (typeof callback === 'function') callback(err, resp, raw);
        };

        fetch(url, init).then(async (r) => {
            const text = await r.text();
            let parsed = text;
            // 看起来像 JSON 才尝试，纯文本 / HTML 保持原样
            const t = text.trim();
            if (t.startsWith('{') || t.startsWith('[')) {
                try { parsed = JSON.parse(t); } catch (e) { parsed = text; }
            }
            const hdrs = {};
            r.headers.forEach((v, k) => { hdrs[k] = v; });
            finish(null, {
                statusCode: r.status,
                statusMessage: r.statusText,
                headers: hdrs,
                body: parsed,
            }, parsed);
        }).catch((e) => {
            finish(e, null, null);
        });

        return () => {
            if (timer) clearTimeout(timer);
            if (ctrl) ctrl.abort();
        };
    }

    /**
     * 每个音源脚本一个**独立**的 lx 实例。
     *
     * 原来 isInited / handlers / initedInfo 是模块级的，多个源一起加载时必然打架：
     * juhe 的初始化请求慢，回来时 huibq 已经把 isInited 置成 true，juhe 再 send(inited)
     * 就抛 "Script is inited"，整个加载链断掉。状态必须按脚本隔离。
     */
    function makeLx() {
        const state = { handlers: {}, inited: false, initedInfo: null };
        const lx = {
        version: '2.0.0',
        env: 'mobile',
        EVENT_NAMES,
        currentScriptInfo: {
            name: '',
            description: '',
            version: '',
            author: '',
            homepage: '',
            rawScript: '',
        },
        on(name, fn) {
            if (EVENT_LIST.indexOf(name) < 0) {
                return Promise.reject(new Error('The event is not supported: ' + name));
            }
            if (name === EVENT_NAMES.request) {
                state.handlers[name] = fn;
            }
            return Promise.resolve();
        },
        send(name, data) {
            if (EVENT_LIST.indexOf(name) < 0) {
                return Promise.reject(new Error('The event is not supported: ' + name));
            }
            if (name === EVENT_NAMES.inited) {
                if (state.inited) return Promise.reject(new Error('Script is inited'));
                state.inited = true;
                state.initedInfo = data;
                return Promise.resolve();
            }
            if (name === EVENT_NAMES.updateAlert) {
                // 更新提示在播放器里没地方展示，收下但不打扰用户
                return Promise.resolve();
            }
            return Promise.resolve();
        },
        request(url, options, callback) {
            // 官方签名是 (url, options, callback)，也见过只传 (url, callback)
            if (typeof options === 'function') {
                return doRequest(url, {}, options);
            }
            return doRequest(url, options, callback);
        },
        utils: {
            buffer: {
                from(input, encoding) {
                    if (typeof input === 'string') {
                        if (encoding === 'base64') {
                            const bin = atob(input);
                            const arr = new Uint8Array(bin.length);
                            for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                            return arr;
                        }
                        if (encoding === 'hex') {
                            const m = input.match(/.{1,2}/g) || [];
                            return new Uint8Array(m.map((b) => parseInt(b, 16)));
                        }
                        return new TextEncoder().encode(input);
                    }
                    if (Array.isArray(input)) return new Uint8Array(input);
                    if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer);
                    throw new Error('Unsupported input type: ' + typeof input);
                },
                bufToString(buf, format) {
                    const arr = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
                    if (format === 'base64') {
                        let s = '';
                        for (let i = 0; i < arr.length; i++) s += String.fromCharCode(arr[i]);
                        return btoa(s);
                    }
                    if (format === 'hex') {
                        return Array.prototype.map.call(arr,
                            (b) => b.toString(16).padStart(2, '0')).join('');
                    }
                    return new TextDecoder('utf-8').decode(arr);
                },
            },
            crypto: {
                md5,
                randomBytes(n) {
                    const arr = new Uint8Array(n);
                    if (window.crypto && window.crypto.getRandomValues) {
                        window.crypto.getRandomValues(arr);
                    } else {
                        for (let i = 0; i < n; i++) arr[i] = Math.floor(Math.random() * 256);
                    }
                    return arr;
                },
                /**
                 * aesEncrypt(buffer, mode, key, iv)
                 *   mode: 'aes-128-cbc' | 'aes-128-ecb'
                 *   返回 Uint8Array
                 *
                 * 官方是靠 native 调 Java 的 Cipher 实现的。WebView 里 crypto.subtle 只有异步接口，
                 * 而音源脚本要的是同步返回值，所以这里自己实现 AES-128。
                 */
                aesEncrypt(buffer, mode, key, iv) {
                    const data = toBytes(buffer);
                    const k = toBytes(key);
                    const ivb = iv == null ? null : toBytes(iv);
                    if (k.length !== 16) throw new Error('aes-128 需要 16 字节密钥，收到 ' + k.length);
                    if (mode === 'aes-128-cbc') {
                        if (!ivb || ivb.length !== 16) throw new Error('aes-128-cbc 需要 16 字节 iv');
                        return aesCbcEncrypt(data, k, ivb);
                    }
                    if (mode === 'aes-128-ecb') {
                        return aesEcbEncrypt(data, k);
                    }
                    throw new Error('不支持的 AES 模式：' + mode);
                },
                rsaEncrypt(buffer, key) {
                    throw new Error('rsaEncrypt 还没实现（等实测确认哪些音源真的用）');
                },
            },
        },
        };
        return { lx, state };
    }

    /* ------------------------------------------------------------ AES-128（同步） */

    function toBytes(v) {
        if (v instanceof Uint8Array) return v;
        if (ArrayBuffer.isView(v)) return new Uint8Array(v.buffer);
        if (Array.isArray(v)) return new Uint8Array(v);
        if (typeof v === 'string') return new TextEncoder().encode(v);
        throw new Error('无法转成字节：' + typeof v);
    }

    // 标准 S 盒
    const SBOX = new Uint8Array([
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    ]);

    function xtime(a) {
        return ((a << 1) ^ ((a & 0x80) ? 0x1b : 0)) & 0xff;
    }

    function expandKey(key) {
        const w = new Uint8Array(176);
        w.set(key);
        let rcon = 1;
        for (let i = 16; i < 176; i += 4) {
            let t0 = w[i - 4], t1 = w[i - 3], t2 = w[i - 2], t3 = w[i - 1];
            if (i % 16 === 0) {
                const tmp = t0;
                t0 = SBOX[t1] ^ rcon;
                t1 = SBOX[t2];
                t2 = SBOX[t3];
                t3 = SBOX[tmp];
                rcon = xtime(rcon);
            }
            w[i] = w[i - 16] ^ t0;
            w[i + 1] = w[i - 15] ^ t1;
            w[i + 2] = w[i - 14] ^ t2;
            w[i + 3] = w[i - 13] ^ t3;
        }
        return w;
    }

    function encryptBlock(s, w, out, off) {
        const st = new Uint8Array(16);
        for (let i = 0; i < 16; i++) st[i] = s[i] ^ w[i];

        for (let round = 1; round <= 10; round++) {
            // SubBytes + ShiftRows
            let t = new Uint8Array(16);
            t[0] = SBOX[st[0]]; t[1] = SBOX[st[5]]; t[2] = SBOX[st[10]]; t[3] = SBOX[st[15]];
            t[4] = SBOX[st[4]]; t[5] = SBOX[st[9]]; t[6] = SBOX[st[14]]; t[7] = SBOX[st[3]];
            t[8] = SBOX[st[8]]; t[9] = SBOX[st[13]]; t[10] = SBOX[st[2]]; t[11] = SBOX[st[7]];
            t[12] = SBOX[st[12]]; t[13] = SBOX[st[1]]; t[14] = SBOX[st[6]]; t[15] = SBOX[st[11]];

            if (round !== 10) {
                // MixColumns
                for (let c = 0; c < 4; c++) {
                    const i = c * 4;
                    const a0 = t[i], a1 = t[i + 1], a2 = t[i + 2], a3 = t[i + 3];
                    const b0 = xtime(a0), b1 = xtime(a1), b2 = xtime(a2), b3 = xtime(a3);
                    t[i] = b0 ^ b1 ^ a1 ^ a2 ^ a3;
                    t[i + 1] = a0 ^ b1 ^ b2 ^ a2 ^ a3;
                    t[i + 2] = a0 ^ a1 ^ b2 ^ b3 ^ a3;
                    t[i + 3] = b0 ^ a0 ^ a1 ^ a2 ^ b3;
                }
            }
            // AddRoundKey
            for (let i = 0; i < 16; i++) st[i] = t[i] ^ w[round * 16 + i];
        }
        for (let i = 0; i < 16; i++) out[off + i] = st[i];
    }

    /** PKCS7 补位。 */
    function padPkcs7(data) {
        const pad = 16 - (data.length % 16);
        const out = new Uint8Array(data.length + pad);
        out.set(data);
        for (let i = data.length; i < out.length; i++) out[i] = pad;
        return out;
    }

    function aesCbcEncrypt(data, key, iv) {
        const w = expandKey(key);
        const src = padPkcs7(data);
        const out = new Uint8Array(src.length);
        let prev = iv;
        const block = new Uint8Array(16);
        for (let off = 0; off < src.length; off += 16) {
            for (let i = 0; i < 16; i++) block[i] = src[off + i] ^ prev[i];
            encryptBlock(block, w, out, off);
            prev = out.subarray(off, off + 16);
        }
        return out;
    }

    /** ECB 按官方口径是 NoPadding —— 不足 16 字节的整数倍时补 0。 */
    function aesEcbEncrypt(data, key) {
        const w = expandKey(key);
        const n = Math.ceil(data.length / 16) * 16 || 16;
        const src = new Uint8Array(n);
        src.set(data);
        const out = new Uint8Array(n);
        for (let off = 0; off < n; off += 16) {
            encryptBlock(src.subarray(off, off + 16), w, out, off);
        }
        return out;
    }

    // window.lx 不在这里设 —— 每个脚本要看到自己的实例，
    // 由 runScript 在执行前挂上去（脚本都是同步解构 globalThis.lx 的）
    try { window.globalThis = window; } catch (e) { /* 只读就算了 */ }

    // 这个文件在 mw-boot 之前加载，但真正注册要等 mw-boot 初始化完，
    // 所以这里只做兜底，避免时序问题
    window.MWSources = window.MWSources || { list: [], byId: {} };

    /* ------------------------------------------------------------ 构造 musicInfo */

    /**
     * 落雪源要的是 `musicInfo`，字段名随平台而异：
     *   网易(wy) / QQ(tx) → songmid
     *   酷狗(kg)          → hash
     *   酷我(kw)          → musicrid / rid
     * 我们自己的 song 只有 id，所以按当前平台把 id 映射过去，能填的都填上。
     */
    function buildMusicInfo(song, platform) {
        const id = String(song && song.id != null ? song.id : '');
        const info = {
            songmid: id,
            mid: id,
            id,
            name: (song && song.title) || '',
            singer: (song && song.artist) || '',
            albumName: (song && song.album) || '',
            album: (song && song.album) || '',
            source: platform || 'wy',
            interval: '00:00',
            img: (song && song.cover) || '',
        };
        if (platform === 'kg') {
            info.hash = id;
            info.albumId = (song && song.albumId) || '';
        } else if (platform === 'kw') {
            info.musicrid = id;
            info.rid = id;
        } else if (platform === 'tx') {
            info.songmid = id;
            info.strMediaMid = id;
        }
        return info;
    }

    /**
     * 平台标识映射。
     *
     * 落雪用的是两字母缩写（官方 user-api-preload.js 里 allSources = ['kw','kg','tx','wy','mg','local']），
     * 我们这边是 Meting 的全名。不转的话传过去 source 是 'netease'，音源直接不认。
     */
    const PLATFORM_MAP = {
        netease: 'wy',
        tencent: 'tx',
        qq: 'tx',
        kugou: 'kg',
        kuwo: 'kw',
        migu: 'mg',
        local: 'local',
    };

    function toLxSource(p) {
        const s = String(p || 'netease').toLowerCase();
        return PLATFORM_MAP[s] || s;
    }

    /**
     * 当前平台（落雪标识）。用户随时会切源，所以每次取地址都重新读一遍，
     * 不能在注册时定死 —— 定死的话切到酷狗还按网易的 songmid 去问，必然空地址。
     */
    function currentPlatform(fallback) {
        try {
            const c = window.MW_MUSIC_CONFIG || {};
            const raw = c.server
                || (c.backends && c.backends.meting && c.backends.meting.server)
                || fallback
                || 'netease';
            return toLxSource(raw);
        } catch (e) {
            return toLxSource(fallback);
        }
    }

    /** 按源声明支持的音质挑一个，从高到低。 */
    function pickQuality(def, want) {
        const list = Array.isArray(def && def.qualitys) ? def.qualitys : [];
        if (!list.length) return want || '320k';
        if (want && list.includes(want)) return want;
        const order = ['flac24bit', 'flac', '320k', '192k', '128k'];
        for (const q of order) {
            if (list.includes(q)) return q;
        }
        return list[0];
    }

    /* ------------------------------------------------------------ 执行脚本 */

    /**
     * 跑一个落雪源脚本，返回它导出的对象。
     *
     * 用 Function 而不是 import()：落雪源是 CommonJS，靠 `module.exports` 导出，
     * 而 ESM 里没有 module 这个标识符，直接 import 会 ReferenceError。
     */
    async function runScript(code, name) {
        // 每个脚本一个全新的 lx 实例 —— 状态互不干扰
        const { lx, state } = makeLx();
        // 脚本里写 globalThis.lx 时，globalThis 就是 window，所以这里要指到自己的实例
        window.lx = lx;

        const mod = { exports: {} };
        const sandbox = {
            module: mod,
            exports: mod.exports,
            lx,
            globalThis: window,
            window,
            self: window,
            console,
            setTimeout,
            clearTimeout,
            setInterval,
            clearInterval,
            URL,
            URLSearchParams,
            TextEncoder,
            TextDecoder,
            fetch,
            atob,
            btoa,
            Buffer: lx.utils.buffer,
            // 有些源用 require 拿内置模块，这里给个明确的失败信息
            require(m) {
                throw new Error('这个音源用了 require("' + m + '")，WebView 里没有 Node 模块');
            },
        };
        const keys = Object.keys(sandbox);
        const fn = new Function(...keys, '"use strict";\n' + code);
        fn(...keys.map((k) => sandbox[k]));

        let def = mod.exports;
        // 新格式：靠 send(EVENT_NAMES.inited) 注册，exports 是空的。
        // inited 的结构是 { sources: { wy: { type, actions, qualitys }, ... } }，没有 name。
        if ((!def || typeof def !== 'object' || typeof def.musicUrl !== 'function') && state.initedInfo) {
            def = fromInited(state.initedInfo, name);
        }
        if (!def || typeof def !== 'object') {
            throw new Error('脚本没有导出音源对象（既没有 module.exports 也没有 send(inited)）');
        }
        if (!def.name) def.name = name || '落雪音源';
        return { def, state };
    }

    /**
     * 把 `send(EVENT_NAMES.inited, { sources: {...} })` 转成统一形状。
     *
     * 这种源不提供 musicUrl，所有动作都走 `on(EVENT_NAMES.request)` 的事件回调，
     * 所以这里只负责把音质声明合并出来。
     */
    function fromInited(info, name) {
        const sources = (info && info.sources) || {};
        const keys = Object.keys(sources);
        if (!keys.length) {
            throw new Error('inited 里没有声明任何音源平台（sources 为空）');
        }
        const qs = [];
        keys.forEach((k) => {
            const q = sources[k] && sources[k].qualitys;
            if (Array.isArray(q)) {
                q.forEach((x) => { if (qs.indexOf(x) < 0) qs.push(x); });
            }
        });
        return {
            name: name || '落雪音源',
            qualitys: qs,
            __sources: keys,
        };
    }

    /* ------------------------------------------------------------ 转成 MWDefine 形状 */

    function toMWSource(def, fallbackId, platform, state) {
        const rawName = String(def.name || fallbackId || 'lx');
        // id **必须**和原生 MusicSources 存的 scriptId 完全一致，否则选了源也找不到。
        // 原生那边存的是文件名生成的 safeId（index.txt 第一列），所以这里原样用，
        // 绝不能加前缀 —— 加个 'lx_' 就会变成「界面上选了，但取地址时说没这个源」。
        const id = String(fallbackId || rawName)
            .replace(/[^a-zA-Z0-9_.-]/g, '_')
            .slice(0, 48);
        const qualitys = Array.isArray(def.qualitys) ? def.qualitys : [];

        const callMusicUrl = async (song) => {
            const pf = currentPlatform(platform);
            // 落雪的 info.type 是**音质标识**（'128k' / '320k' / 'flac'），不是 'music'。
            // 传错的话音源会把它当 quality 拼进请求，实测 ikun 会拼出 `quality=music` 然后返回空地址。
            const quality = pickQuality(def, song && song.quality);

            if (typeof def.musicUrl !== 'function') {
                // 新格式：request 事件里处理 musicUrl
                const h = state && state.handlers[EVENT_NAMES.request];
                if (!h) throw new Error('这个音源没有 musicUrl，也没有注册 request 处理器');
                const r = await h({
                    source: pf,
                    action: 'musicUrl',
                    info: { type: quality, musicInfo: buildMusicInfo(song, pf) },
                });
                if (!r) throw new Error('音源没有返回地址');
                return typeof r === 'string' ? r : r.url;
            }
            const info = { type: quality, musicInfo: buildMusicInfo(song, pf) };
            const out = await def.musicUrl(info, quality);
            if (!out) throw new Error('音源没有返回地址（可能是 VIP 曲或该音质不可用）');
            if (typeof out === 'string') return out;
            return out.url || out.data || '';
        };

        return {
            id,
            name: rawName,
            version: String(def.version || ''),
            hint: '落雪音源 · 支持 ' + (qualitys.length ? qualitys.join(' / ') : '默认音质'),
            // 落雪源不提供搜索，交给播放器自己的源
            search: null,
            song: null,
            url: callMusicUrl,
            lyric: typeof def.lyric === 'function'
                ? async (song) => {
                    const r = await def.lyric({
                        // 和 musicUrl 同一口径：type 是音质标识
                        type: pickQuality(def, song && song.quality),
                        musicInfo: buildMusicInfo(song, currentPlatform(platform)),
                    });
                    return typeof r === 'string' ? r : (r && r.lyric) || '';
                }
                : null,
        };
    }

    /**
     * 加载并注册一个落雪源。
     *
     * @param url      脚本地址（虚拟路径 /userdata/music-sources/xxx.js 也行）
     * @param fallbackId 脚本没写 name 时用的标识
     * @param platform  当前搜索平台，决定 musicInfo 里填 songmid 还是 hash
     */
    window.MWLoadLxSource = async function (url, fallbackId, platform) {
        const resp = await fetch(url, { cache: 'no-store' });
        if (!resp.ok) throw new Error('读取脚本失败 HTTP ' + resp.status);
        const code = await resp.text();
        const { def, state } = await runScript(code, fallbackId);
        const src = toMWSource(def, fallbackId, platform, state);
        window.MWSources.byId[src.id] = src;
        if (!window.MWSources.list.some((s) => s.id === src.id)) {
            window.MWSources.list.push(src);
        }
        return src;
    };

    /** 判断一段脚本是不是落雪格式（用来给导入界面提示）。 */
    window.MWIsLxSource = function (code) {
        if (!code) return false;
        return /module\.exports\s*=/.test(code)
            || /globalThis\.lx/.test(code)
            || /EVENT_NAMES\.request/.test(code)
            || /\bmusicUrl\s*[:(]/.test(code);
    };
})();
