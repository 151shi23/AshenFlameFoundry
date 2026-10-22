/*
 * 网页就地汉化：把页面上的英文按云端下发的词表换成中文。
 *
 * 与"浮动翻译"不同的地方 —— 这里直接改文本节点本身，页面结构、样式、点击区域都不动；
 * 原文留在节点的 data-aff-orig 上，切回原文时逐字还原。
 *
 * 词表由 App 侧喂进来（__affI18n.feed），并且**已经按原文长度从长到短排好**，
 * 所以整句一定优先于短语命中："Sound Effects" 不会被 "Sound" 抢成"声音效果"。
 */
(function () {
  'use strict';

  if (window.__affI18n && window.__affI18n.alive) {
    window.__affI18n.restart();
    return;
  }

  // 这些标签里的文字要么不可见、要么是代码/输入，换了会出事
  var SKIP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1,
    CODE: 1, PRE: 1, KBD: 1, SAMP: 1, VAR: 1, SVG: 1, CANVAS: 1, AUDIO: 1, VIDEO: 1,
    TEMPLATE: 1
  };
  var PUNCT = ' \t\r\n.,!?;:"\'`()[]{}<>|、。「」『』（）：；？！，';

  var TABLE = null;          // 规范化英文 → 中文
  var KEYS = null;          // 按长度降序的原文（feed 时 Java 已排好，这里再排一次以防万一）
  var cache = Object.create(null);
  var done = Object.create(null);   // 原文 → 有没有换过，避免同一句反复试
  var origByNode = new WeakMap();   // 文本节点 → 原文
  var touched = [];                 // 换过的节点，切回原文时逐个还原
  var stats = { replaced: 0, nodes: 0, missed: 0 };
  var observer = null;
  var timer = 0;

  function isSpace(c) {
    return c === ' ' || c === '\t' || c === '\n' || c === '\r';
  }

  /** 与 Java 侧 FeatureCatalog.normalizeEn 对齐：小写 + 空白并一格 + 去首尾标点。 */
  function norm(s) {
    var out = '', prevSpace = false, i, c;
    s = String(s).toLowerCase();
    for (i = 0; i < s.length; i++) {
      c = s.charAt(i);
      if (isSpace(c) || c === '\u3000') {
        prevSpace = out.length > 0;
        continue;
      }
      if (prevSpace) { out += ' '; }
      prevSpace = false;
      out += c;
    }
    var a = 0, b = out.length;
    while (a < b && PUNCT.indexOf(out.charAt(a)) >= 0) { a++; }
    while (b > a && PUNCT.indexOf(out.charAt(b - 1)) >= 0) { b--; }
    return out.substring(a, b);
  }

  function hasLatin(s) {
    return /[A-Za-z]/.test(s);
  }

  /** 已经含中日韩字的节点不动它：中文站、混排品牌名、以及上一轮换好的文字。 */
  function hasCjk(s) {
    return /[㐀-䶿一-鿿぀-ヿ가-힯]/.test(s);
  }

  /** 整句查不到时，按"长词优先"在句子里就地替换，最多换 4 处，防止句子被拆碎。 */
  /** 命中处两侧不能再接字母或数字：否则词表里的 "Art" 会把 "Smart" 换成 "S艺术m"。 */
  function isWordHit(low, pos, len) {
    var before = pos > 0 ? low.charAt(pos - 1) : '';
    var after = pos + len < low.length ? low.charAt(pos + len) : '';
    return !/[a-z0-9]/.test(before) && !/[a-z0-9]/.test(after);
  }

  function patchInside(text) {
    if (!KEYS || !KEYS.length) { return null; }
    var out = text, hits = 0, i, k, low, pos;
    for (i = 0; i < KEYS.length && hits < 4; i++) {
      k = KEYS[i];
      if (k.length < 3) { continue; }           // 太短的词容易误伤（a / of / the 这种）
      low = out.toLowerCase();
      pos = low.indexOf(k.toLowerCase());
      // 只有作为独立单词出现才替换，嵌在长单词里的不算
      while (pos >= 0 && !isWordHit(low, pos, k.length)) {
        pos = low.indexOf(k.toLowerCase(), pos + 1);
      }
      if (pos < 0) { continue; }
      out = out.substring(0, pos) + TABLE[k] + out.substring(pos + k.length);
      hits++;
    }
    return hits > 0 ? out : null;
  }

  /** 一段文字要换成什么；返回 null 表示不动。 */
  function translate(text) {
    if (!TABLE) { return null; }
    if (!hasLatin(text) || hasCjk(text)) { return null; }
    var key = norm(text);
    if (!key) { return null; }
    if (key in cache) { return cache[key]; }
    var hit = TABLE[key];
    if (hit === undefined) {
      // 尾点差异在 norm 里已经抹平了；还差一步：整句里有多个大小写变体时再试一次原文本身
      hit = TABLE[text] === undefined ? undefined : TABLE[text];
    }
    var r = null;
    if (hit !== undefined && String(hit).length > 0) {
      r = hit;
    } else {
      r = patchInside(text);
    }
    cache[key] = r;
    return r;
  }

  function skipNode(node) {
    var p = node.parentElement;
    while (p) {
      var tag = p.tagName ? p.tagName.toUpperCase() : '';
      if (SKIP[tag]) { return true; }
      if (p.isContentEditable) { return true; }
      // 图标字体：元素里只有一个字符且带 icon/fa/glyph 类名，那是图标不是文字
      if (p.className && typeof p.className === 'string' && node.nodeValue &&
        node.nodeValue.trim().length <= 2 && /icon|fa-|glyph|material|feather/i.test(p.className)) {
        return true;
      }
      p = p.parentElement;
    }
    return false;
  }

  function walkAndReplace(root) {
    if (!root || !TABLE) { return; }
    var walker, node, changed = 0, tried = 0;
    try {
      walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    } catch (e) {
      return;
    }
    while ((node = walker.nextNode())) {
      var text = node.nodeValue;
      if (!text || text.trim().length === 0) { continue; }
      if (skipNode(node)) { continue; }
      tried++;
      var hit = translate(text);
      if (!hit || hit === text) {
        if (hasLatin(text) && !hasCjk(text) && norm(text)) { stats.missed++; }
        continue;
      }
      if (!node.parentNode) { continue; }
      // 原文记在节点自己身上：一个元素里常常有好几个文本节点（"Download " + "free" + " assets"），
      // 记在元素上会互相覆盖，切回原文时就把页面拼坏了
      if (!origByNode.has(node)) {
        origByNode.set(node, text);
        touched.push(node);
      }
      node.nodeValue = hit;
      stats.replaced++;
      changed++;
      if (changed > 400) { break; }     // 一次别改太多，否则长页面会卡住主线程
    }
    stats.nodes += tried;
  }

  function walkShadow(root) {
    // 开了 shadow root 的组件（不少新站用）也要走一遍；closed 的走不到，这是浏览器的限制
    var all, i, el, host;
    try {
      all = root.querySelectorAll('*');
    } catch (e) {
      return;
    }
    for (i = 0; i < all.length; i++) {
      el = all[i];
      host = el.shadowRoot;
      if (host) {
        walkAndReplace(host);
        walkShadow(host);
      }
    }
  }

  function run() {
    if (!TABLE) { return; }
    walkAndReplace(document.body);
    walkShadow(document.body);
    report();
  }

  var reportTimer = 0;
  function report() {
    if (reportTimer) { return; }
    reportTimer = setTimeout(function () {
      reportTimer = 0;
      try {
        if (window.AFF_I18N && AFF_I18N.stats) {
          AFF_I18N.stats(stats.replaced, stats.missed);
        }
      } catch (e) { }
    }, 500);
  }

  function schedule() {
    if (timer) { return; }
    timer = setTimeout(function () {
      timer = 0;
      run();
    }, 220);
  }

  function observe() {
    if (observer) { return; }
    try {
      observer = new MutationObserver(function (ms) {
        var i, added = false;
        for (i = 0; i < ms.length; i++) {
          if (ms[i].addedNodes && ms[i].addedNodes.length) { added = true; break; }
          if (ms[i].type === 'characterData') { return; }   // 我们自己改的，别再触发一轮
        }
        if (added) { schedule(); }
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) { }
  }

  /** 切回原文：把换过的文本节点逐个还原，并停掉观察器。 */
  function restore() {
    var i, n;
    for (i = 0; i < touched.length; i++) {
      n = touched[i];
      try {
        if (n.parentNode && origByNode.has(n)) {
          n.nodeValue = origByNode.get(n);
        }
      } catch (e) { }
    }
    touched = [];
    stats = { replaced: 0, nodes: 0, missed: 0 };
    cache = Object.create(null);
  }

  window.__affI18n = {
    alive: true,
    /** Java 喂词表：{ 规范化英文: 中文 }，顺序已按长度降序。 */
      /**
     * Java 喂词表：{ 规范化英文: 中文 }。
     * append=true 表示这是**续传的分块**（整站逐句翻译会有几千上万条，一次 evaluateJavascript
     * 塞不下）：只并进表里，先不扫页面，等 Java 分块喂完再调 restart() 统一开跑。
     */
    feed: function (map, append) {
      if (!append || !TABLE) {
        TABLE = {};
        KEYS = [];
      }
      var k;
      try {
        for (k in map) {
          if (Object.prototype.hasOwnProperty.call(map, k)) {
            TABLE[k] = String(map[k]);
            if (KEYS.indexOf(k) < 0) { KEYS.push(k); }
          }
        }
      } catch (e) { }
      // 长句要排在短句前面，否则 "Sound Effects" 会被 "Sound" 抢先换掉
      KEYS.sort(function (a, b) { return b.length - a.length; });
      cache = Object.create(null);
      if (append) { return KEYS.length; }
      stats = { replaced: 0, nodes: 0, missed: 0 };
      run();
      observe();
      // 前端站首屏常是骨架屏，稍后再补两轮
      setTimeout(run, 900);
      setTimeout(run, 2600);
      return true;
    },
    restart: function () {
      run();
      observe();
    },
    stop: function () {
      if (observer) {
        try { observer.disconnect(); } catch (e) { }
        observer = null;
      }
      restore();
      return true;
    },
    count: function () {
      return stats.replaced;
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', schedule);
  } else {
    schedule();
  }
  observe();
})();
