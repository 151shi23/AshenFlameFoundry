'use strict';

/* 核心引擎：常量、素材、世界状态、物理、生成、陷阱判定 */

let W = 540;            // 逻辑宽度随屏幕比例伸缩（自适应）
let DPR = 1;            // 设备像素比（高清渲染用，上限 2，逻辑坐标仍是 W×H）
const H = 960;
const GROUND_Y = 700;
const GRAVITY = 2900;
const JUMP_V = -1350;
const BASE_SPEED = 330;
const MAX_SPEED = 820;
const PX_PER_M = 50;
// 下坠中补跳：给 2 次，用完进 4 秒 CD
const AIR_JUMP_V = -1150;
const AIR_JUMP_CD = 4;
const AIR_JUMP_MAX = 2;
// 冲刺：金币攒能量，攒满才放得出
const ENERGY_MAX = 100;
const DASH_TIME = 2.6;
let PLAYER_X = 152;   // 玩家横向位置：随屏宽伸缩（大屏时往前放，给蚩尤留出全身追逐的空间）
const PLAYER_H = 168;
const GRASS_H = 38;
const GRASS_TILE_W = 155;
const DIRT_TILE_W = 362;

// 底部两个操作键的位置（slide.x 由 resize 按屏宽调整）
const BTN = {
  jump: { x: 40, y: 800, w: 140, h: 140 },
  slide: { x: 360, y: 800, w: 140, h: 140 },
};

const rnd = (a, b) => a + Math.random() * (b - a);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/* ---------------- 素材 ---------------- */
const ASSETS = {
  bg:    { src: 'bg_forest_sky.png' },
  run:   { src: 'player_run_side.png',  crop: { x: 334, y: 200, w: 402, h: 594 } },
  run2:  { src: 'player_run_side_2.png', crop: { x: 297, y: 212, w: 423, h: 567 } },
  slide: { src: 'player_slide_side.png', crop: { x: 172, y: 365, w: 586, h: 299 } },
  idle:  { src: 'player_idle_front.png', crop: { x: 296, y: 136, w: 430, h: 756 } },
  coin:  { src: 'item_coin.png',        crop: { x: 58,  y: 44,  w: 210, h: 248 } },
  logo:  { src: 'ui_logo_title.png',    crop: { x: 20,  y: 236, w: 994, h: 462 } },
  grass: { src: 'tile_grass_ground.png', crop: { x: 68, y: 40, w: 895, h: 220 } },
  dirt:  { src: 'tile_dirt.png',        crop: { x: 7,  y: 26, w: 693,  h: 415 } },
};
const IMG = {};

/* 皮肤：优先读美术素材 <id>_run.png / <id>_run2.png / <id>_slide.png / <id>_idle.png，
   缺哪个动作就回落成原版素材染色，所以有几张图就能用几张。 */
const SKIN_KEYS = ['run', 'run2', 'slide', 'idle'];
const SKINS = [
  { id: 'white',  name: '原色',   color: '#ffffff', need: 0,  desc: '默认外观' },
  { id: 'candy',  name: '机灵糖', color: '#ff9ec4', need: 0,  desc: '甜到发光' },
  { id: 'forest', name: '森绿',   color: '#7de08a', need: 0,  desc: '林间跑者' },
  { id: 'ocean',  name: '深海',   color: '#63c9f0', need: 0,  desc: '海底冲刺' },
  { id: 'rabbit', name: '机灵玉兔', color: '#ffffff', need: 0,  desc: '蹦蹦跳跳' },
  { id: 'lava',   name: '熔岩',   color: '#ff9a5a', need: 0,  desc: '滚烫的脚力' },
  { id: 'shadow', name: '暗影',   color: '#b48cf0', need: 0,  desc: '夜里看不见' },
  { id: 'gold',   name: '黄金',   color: '#ffd34d', need: 0,  desc: '满星荣耀' },
  { id: 'maid',   name: '女仆机灵', color: '#e0455a', need: 0,  desc: '女仆装·围裙飘飘' },
];

const SKIN_CACHE = {};       // 当前皮肤实际用的图，换肤时重建
const SKIN_PREVIEW = {};     // 皮肤界面的缩略图缓存

/* 白像素 × 颜色保住明暗，再用原图 alpha 把透明区切干净 */
function tintSprite(img, crop, color) {
  const c = document.createElement('canvas');
  c.width = crop.w;
  c.height = crop.h;
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(img, crop.x, crop.y, crop.w, crop.h, 0, 0, crop.w, crop.h);
  g.globalCompositeOperation = 'multiply';
  g.fillStyle = color;
  g.fillRect(0, 0, crop.w, crop.h);
  g.globalCompositeOperation = 'destination-in';
  g.drawImage(img, crop.x, crop.y, crop.w, crop.h, 0, 0, crop.w, crop.h);
  g.globalCompositeOperation = 'source-over';
  return c;
}

/* 把每个皮肤的动作图都试加载一遍，没有的留空，不报错 */
function loadSkinSprites() {
  const jobs = [];
  for (const sk of SKINS) {
    sk.sprite = {};
    if (sk.id === 'white') continue;
    for (const k of SKIN_KEYS) {
      jobs.push(new Promise(res => {
        const im = new Image();
        im.onload = () => {
          sk.sprite[k] = { im: im, crop: { x: 0, y: 0, w: im.width, h: im.height } };
          res();
        };
        im.onerror = () => res();
        im.src = sk.id + '_' + k + '.png';
      }));
    }
  }
  return Promise.all(jobs);
}

function skinHasArt(i) {
  const sk = SKINS[i];
  if (!sk) return false;
  if (sk.id === 'white') return true;
  if (!sk.sprite) return false;
  for (const k of SKIN_KEYS) if (sk.sprite[k]) return true;
  return false;
}

/* 换肤时重建当前皮肤的绘制源：素材 > 原版染色 > 原版 */
function buildSkinSprites() {
  for (const k of SKIN_KEYS) delete SKIN_CACHE[k];
  const sk = SKINS[G.skinIdx] || SKINS[0];
  for (const k of SKIN_KEYS) {
    if (sk.sprite && sk.sprite[k]) {
      SKIN_CACHE[k] = sk.sprite[k];
    } else if (sk.id !== 'white' && IMG[k] && ASSETS[k] && ASSETS[k].crop) {
      // 染色出来的是一张新画布，裁剪区要从 0,0 开始
      const c = ASSETS[k].crop;
      SKIN_CACHE[k] = {
        im: tintSprite(IMG[k], c, sk.color),
        crop: { x: 0, y: 0, w: c.w, h: c.h },
      };
    }
  }
}

function setSkin(i) {
  G.skinIdx = clamp(i, 0, SKINS.length - 1);
  buildSkinSprites();
  for (const k in SKIN_PREVIEW) delete SKIN_PREVIEW[k];
  // 存皮肤 id（自定义皮肤是动态加载的，索引会漂移）；老存档是纯数字索引，恢复时兼容
  try { localStorage.setItem('jiling_skin', SKINS[G.skinIdx].id); } catch (e) {}
}

/* 把玩家自定义皮肤（IndexedDB）安装进 SKINS：卸载旧的 custom 项 → dataURL 载成 Image → 追加。
   装完若存档选中的皮肤 id 命中则恢复选中。返回 Promise（全部动作图加载完）。 */
function installCustomSkins(list) {
  for (let i = SKINS.length - 1; i >= 0; i--) {
    if (SKINS[i].custom) {
      if (G.skinIdx === i) G.skinIdx = 0;
      SKINS.splice(i, 1);
    }
  }
  const jobs = [];
  for (const s of (list || [])) {
    const sk = { id: 'c_' + s.id, name: s.name, color: '#7de08a', need: 0, desc: '自定义皮肤', custom: true, rawId: s.id, sprite: {} };
    for (const k of SKIN_KEYS) {
      const src = s.frames && s.frames[k];
      if (!src) continue;
      jobs.push(new Promise(res => {
        const im = new Image();
        im.onload = () => { sk.sprite[k] = { im: im, crop: { x: 0, y: 0, w: im.width, h: im.height } }; res(); };
        im.onerror = () => res();
        im.src = src;
      }));
    }
    SKINS.push(sk);
  }
  return Promise.all(jobs).then(() => {
    // 恢复选中：存档里是 id 就按 id 找回，老存档纯数字按索引 clamp
    try {
      const saved = localStorage.getItem('jiling_skin');
      if (saved) {
        const byId = SKINS.findIndex(x => x.id === saved);
        G.skinIdx = byId >= 0 ? byId : (/^\d+$/.test(saved) ? clamp(+saved, 0, SKINS.length - 1) : 0);
      }
    } catch (e) {}
    buildSkinSprites();
    for (const k in SKIN_PREVIEW) delete SKIN_PREVIEW[k];
  });
}

function totalStars() {
  return G.stars.reduce((a, b) => a + b, 0);
}

function skinUnlocked(i) {
  return totalStars() >= SKINS[i].need;
}

function loadAssets() {
  const jobs = Object.keys(ASSETS).map(key => new Promise((res, rej) => {
    const im = new Image();
    im.onload = () => { IMG[key] = im; res(); };
    im.onerror = () => rej(new Error(ASSETS[key].src));
    im.src = ASSETS[key].src;
  }));
  return Promise.all(jobs);
}

const cv = document.getElementById('game');
const ctx = cv.getContext('2d');
ctx.imageSmoothingEnabled = false;

function resize() {
  const vw = window.innerWidth, vh = window.innerHeight;
  /* 自适应：逻辑高度固定 960，宽度完全跟随屏幕比例（大屏铺满大屏）。
     仅给超长竖屏设下限防视野过窄。 */
  W = clamp(Math.round((H * vw) / vh), 444, 2560);
  /* 高清渲染：画布 backing store 按设备像素比放大（上限 2 倍，兼顾性能），
     逻辑坐标依旧是 W×H，所有绘制与输入换算都不用改。 */
  DPR = clamp(window.devicePixelRatio || 1, 1, 2);
  cv.width = Math.round(W * DPR);
  cv.height = Math.round(H * DPR);
  cv.style.width = vw + 'px';
  cv.style.height = vh + 'px';
  // 素材是柔和笔触的插画，缩放时用平滑采样更干净（像素字体在 ui.js 里单独关掉）
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  // 大屏时玩家位置前移（保持画面约 1/3 处），左侧留出空间看蚩尤全身追逐
  PLAYER_X = Math.round(clamp(W * 0.34, 152, 560));
  // 右下操作键跟随新宽度贴边
  BTN.slide.x = W - 180;
}

function drawImg(key, x, y, w, h, flip) {
  const sc = SKIN_CACHE[key];
  const im = sc ? sc.im : IMG[key];
  if (!im) return;
  const c = sc ? sc.crop
    : (ASSETS[key] && ASSETS[key].crop ? ASSETS[key].crop : { x: 0, y: 0, w: im.width, h: im.height });
  if (flip) {
    ctx.save();
    ctx.translate(x + w, y);
    ctx.scale(-1, 1);
    ctx.drawImage(im, c.x, c.y, c.w, c.h, 0, 0, w, h);
    ctx.restore();
  } else {
    ctx.drawImage(im, c.x, c.y, c.w, c.h, x, y, w, h);
  }
}

function drawTileBand(key, sx, sy, sw, sh, opt) {
  opt = opt || {};
  const crop = ASSETS[key].crop;
  const tileW = opt.tileW || (sh * crop.w / crop.h);
  const tileH = tileW * crop.h / crop.w;
  const drawY = (opt.drawY !== undefined) ? opt.drawY : sy;
  const phase = (opt.phase !== undefined) ? opt.phase : world.x;
  ctx.save();
  ctx.beginPath();
  ctx.rect(sx, sy, sw, sh);
  ctx.clip();
  const startK = Math.floor((phase + sx) / tileW);
  const dy = Math.round(drawY);
  const th = Math.round(tileH);
  for (let k = startK, x = startK * tileW - phase; x < sx + sw; k++, x += tileW) {
    const xs = Math.round(x);
    const xe = Math.round(x + tileW);
    drawImg(key, xs, dy, xe - xs, th, ((k % 2) + 2) % 2 === 1);
  }
  ctx.restore();
}

// ---- 限时增益 ----
const BUFF_DEF = {
  magnet: { name: '金币磁铁', dur: 25, color: '#ff9ed8', icon: '磁' },
  invinc: { name: '无敌',     dur: 8,  color: '#ffe066', icon: '星' },
  djump:  { name: '二段跳',   dur: 30, color: '#b6ec92', icon: '跳' },
  x2:     { name: '金币翻倍', dur: 25, color: '#ffd34d', icon: '×2' },
  slow:   { name: '减速缓冲', dur: 20, color: '#c8d8f5', icon: '慢' },
  sprint: { name: '极速冲刺', dur: 12, color: '#ff9a5a', icon: '速' },
  frenzy: { name: '狂暴',     dur: 18, color: '#ff6a8a', icon: '狂' },
  shield: { name: '护盾',     dur: 0,  color: '#8ce8ff', icon: '盾' },
  ghost:  { name: '幽灵穿透', dur: 8,  color: '#c9a0ff', icon: '灵' },
  rain:   { name: '金币雨',   dur: 10, color: '#ffe066', icon: '雨' },
  revive: { name: '复活',     dur: 0,  color: '#7de08a', icon: '生' },
};
const CRATE_POOL = ['magnet', 'invinc', 'djump', 'x2', 'slow', 'sprint', 'shield', 'ghost', 'rain'];

/* ---- 宠物系统：小鸡 + 奶龙，跟着玩家飞、吸金币、每 60 秒挡一刀 ---- */
const PET_IMG = { imgs: [] };
const PETS = [
  { id: 'chick',   name: '小鸡', src: 'pet.png' },
  { id: 'nailong', name: '奶龙', src: 'nailong.png' },
];
const PET_SAVE = 60;   // 挡伤冷却（秒）

/* 矢量画一只奶龙（橙色圆身 + 白肚 + 绿眼 + 咧嘴笑） */
function buildNailong() {
  const cv2 = document.createElement('canvas');
  cv2.width = 360; cv2.height = 470;
  const c = cv2.getContext('2d');
  const cx = 180;
  const body = c.createRadialGradient(cx, 260, 40, cx, 300, 210);
  body.addColorStop(0, '#ffc766');
  body.addColorStop(1, '#ff9838');
  // 举起的左手
  c.fillStyle = '#ffb347';
  c.beginPath(); c.ellipse(cx - 122, 132, 36, 64, -0.5, 0, Math.PI * 2); c.fill();
  // 爪尖肉垫
  c.fillStyle = '#ffc9d4';
  c.beginPath(); c.ellipse(cx - 136, 90, 19, 15, -0.5, 0, Math.PI * 2); c.fill();
  // 身体 + 头（连体圆滚滚）
  c.fillStyle = body;
  c.beginPath(); c.ellipse(cx, 310, 128, 148, 0, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.ellipse(cx, 158, 116, 104, 0, 0, Math.PI * 2); c.fill();
  // 白肚皮
  c.fillStyle = '#fff3dc';
  c.beginPath(); c.ellipse(cx, 330, 76, 96, 0, 0, Math.PI * 2); c.fill();
  // 右手贴身
  c.fillStyle = '#ffab40';
  c.beginPath(); c.ellipse(cx + 112, 292, 30, 56, 0.32, 0, Math.PI * 2); c.fill();
  // 脚
  c.fillStyle = '#ffab40';
  c.beginPath(); c.ellipse(cx - 54, 446, 44, 25, 0, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.ellipse(cx + 62, 446, 44, 25, 0, 0, Math.PI * 2); c.fill();
  // 眼白 + 绿瞳
  c.fillStyle = '#ffffff';
  c.beginPath(); c.ellipse(cx - 42, 130, 27, 31, 0, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.ellipse(cx + 44, 130, 27, 31, 0, 0, Math.PI * 2); c.fill();
  c.fillStyle = '#3aa66a';
  c.beginPath(); c.ellipse(cx - 39, 132, 16, 20, 0, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.ellipse(cx + 47, 132, 16, 20, 0, 0, Math.PI * 2); c.fill();
  c.fillStyle = '#0a0a12';
  c.beginPath(); c.arc(cx - 36, 134, 8.5, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.arc(cx + 49, 134, 8.5, 0, Math.PI * 2); c.fill();
  // 咧嘴大笑 + 牙 + 舌头
  c.fillStyle = '#7a2f1d';
  c.beginPath(); c.ellipse(cx, 196, 52, 30, 0, 0, Math.PI); c.fill();
  c.fillStyle = '#ffffff';
  c.fillRect(cx - 40, 180, 80, 9);
  c.fillStyle = '#e86a7a';
  c.beginPath(); c.ellipse(cx, 212, 26, 12, 0, 0, Math.PI * 2); c.fill();
  // 腮红
  c.fillStyle = 'rgba(255,140,110,0.4)';
  c.beginPath(); c.ellipse(cx - 84, 176, 16, 10, 0, 0, Math.PI * 2); c.fill();
  c.beginPath(); c.ellipse(cx + 86, 176, 16, 10, 0, 0, Math.PI * 2); c.fill();
  return cv2;
}

function loadPetSprite() {
  const jobs = PETS.map((p, i) => new Promise(res => {
    const im = new Image();
    im.onload = () => { PET_IMG.imgs[i] = im; res(); };
    im.onerror = () => {
      // 图片缺失时用矢量奶龙兜底（仅奶龙有手绘版）
      if (i === 1) PET_IMG.imgs[i] = buildNailong();
      res();
    };
    im.src = p.src;
  }));
  return Promise.all(jobs);
}

function petCur() {
  return (G.petIdx >= 0 && G.petIdx < PETS.length) ? PET_IMG.imgs[G.petIdx] : null;
}
function petEnabled() {
  return !!petCur();
}

// ---- 全局状态 ----
const G = {
  state: 'loading',
  time: 0,
  coins: 0,
  best: 0,
  stars: [],
  mode: 'endless',
  levelIndex: 0,
  levelEndX: 0,
  levelCoinTotal: 0,
  clearStars: 0,
  clearAnim: 0,
  levelCards: null,
  levelTitle: '',
  ugcItem: null,
  ugcNoHit: true,
  ugcEditId: null,
  cardRevealT: 0,
  buff: { magnet: 0, invinc: 0, djump: 0, x2: 0, slow: 0, sprint: 0 },
  shield: 0,
  cardPop: null,
  crateCount: 0,
  comboMark: 0,
  nextMile: 100,
  combo: 0,
  comboDelay: 0,
  boostT: 0,
  pressed: {},
  /* ---- 通关目标与运行时统计 ---- */
  objective: null,      // 本关抽到的通关目标
  levelCache: {},       // 关卡数据缓存（界面预览与正式进入共用，保证一致）
  runTime: 0,           // 本关已用时间（秒）
  hurtCount: 0,         // 受伤次数（含被护盾挡下的）
  maxCombo: 0,          // 最高连击
  jumpCount: 0,
  slideCount: 0,
  airTime: 0,           // 累计滞空（秒）
  padBounce: 0,         // 踩弹跳板次数
  maxSpeed: 0,          // 本关最高速度
  objDone: false,       // 目标是否已达成
  objFlash: 0,          // 目标达成提示的计时
  /* ---- 操作手感 ---- */
  holdJump: true,       // 跳跃键是否按住（松手即削减上升 → 短跳/长跳可控）
  holdSlide: true,      // 滑铲键是否按住（松手可提前站起）
  nearCd: 0,            // 「险！」极限闪避提示冷却
  deathCause: '',       // 死亡原因（用于给玩家身法提示）
  skinIdx: 0,           // 当前皮肤
  /* ---- 被动补跳 / 能量冲刺 ---- */
  airJump: AIR_JUMP_MAX,  // 剩余空中补跳次数
  airCd: 0,               // 补跳冷却剩余（秒）
  energy: 0,              // 冲刺能量 0~100
  dashT: 0,               // 冲刺剩余时间
  dashN: 0,               // 本局冲刺次数
  /* ---- 局内升级等级（祭坛三选一，本局有效） ---- */
  up: { djump: 0, magnet: 0, energy: 0, combo: 0, speed: 0, coin: 0, shieldMax: 0 },
};
try { G.best = +(localStorage.getItem('jiling_best') || 0) || 0; } catch (e) { G.best = 0; }
try { const sv0 = localStorage.getItem('jiling_skin'); G.skinIdx = (/^\d+$/.test(sv0 || '') ? clamp(+sv0, 0, SKINS.length - 1) : 0); } catch (e) { G.skinIdx = 0; }   // 自定义皮肤 id 由 installCustomSkins 异步恢复
try {
  const pi = +(localStorage.getItem('jiling_petIdx') || 0);
  G.petIdx = (pi >= -1 && pi < PETS.length) ? pi : 0;   // -1 = 休息
} catch (e) { G.petIdx = 0; }
(function loadStars() {
  let s = [];
  try { s = JSON.parse(localStorage.getItem('jiling_stars') || '[]') || []; } catch (e) { s = []; }
  G.stars = LEVELS.map((_, i) => +(s[i] || 0) || 0);
})();
function saveStars() {
  try { localStorage.setItem('jiling_stars', JSON.stringify(G.stars)); } catch (e) {}
}

const world = {
  x: 0,
  speed: BASE_SPEED,
  genCursor: 0,
  gaps: [],
  fakeFloors: [],
  spikeTraps: [],
  lowBars: [],
  ceilSpikes: [],
  bouncePads: [],
  movingPlatforms: [],
  platforms: [],
  coins: [],
  signs: [],
  pendulums: [],
  rollingRocks: [],
  fallingRocks: [],
  boosters: [],
  fakeCoins: [],
  hiddenSpikes: [],
  trapSprings: [],
  crushers: [],
  crumbles: [],
  chaseTriggers: [],
  powers: [],
  crates: [],
  enemies: [],
  bullets: [],
  bolts: [],        // 雷暴落雷（无尽天气）
  acidPools: [],    // 酸雨腐蚀区（无尽天气）
  altars: [],       // 局内升级祭坛（三选一）
};

const player = {
  y: GROUND_Y,
  prevY: GROUND_Y,
  vy: 0,
  onGround: true,
  sliding: false,
  slidingT: 0,
  run: 0,
  tilt: 0,
  dustT: 0,
  sx: 1,
  sy: 1,
  spin: 0,
  spinT: 0,
  spinOn: false,
  deadT: 0,
  deadRot: 0,
  coyote: 0,
  jumpBuf: 0,
  jumps: 0,
};

/* ---------------- 世界重置 ---------------- */
function clearWorld() {
  world.x = 0;
  world.speed = BASE_SPEED;
  world.genCursor = 950;
  world.gaps = [];
  world.fakeFloors = [];
  world.spikeTraps = [];
  world.lowBars = [];
  world.ceilSpikes = [];
  world.bouncePads = [];
  world.movingPlatforms = [];
  world.platforms = [];
  world.coins = [];
  world.signs = [];
  world.pendulums = [];
  world.rollingRocks = [];
  world.fallingRocks = [];
  world.boosters = [];
  world.fakeCoins = [];
  world.hiddenSpikes = [];
  world.trapSprings = [];
  world.crushers = [];
  world.crumbles = [];
  world.chaseTriggers = [];
  world.powers = [];
  world.crates = [];
  world.enemies = [];
  world.bullets = [];
  world.bolts = [];
  world.acidPools = [];
  world.altars = [];
  world.gates = [];
  world.flames = [];
  world.belts = [];
  world.updrafts = [];
  world.fireballs = [];
  world.firePits = [];
  world.quakes = [];
  world.meteors = [];
  world.chains = [];
  world.knives = [];
  world.shurikens = [];    // 阿坚的手里剑（常态技能弹，屏幕坐标）
  world.nextPowerX = 2600;
  world.gateNextX = 1150;
  world.tstop = 0;          // 时停计时（api.timestop），跨局必须清零
  world.tsMul = 1; world.tsT = 0;   // 全局时间流速（api.timeScale），跨局必须还原
  world.ugcMods = [];
  world.ugcHazards = [];
  world.ugcLog = [];
  world.bgAsset = null;
  world.pTune = {};      // mod 修改的玩家属性（jumpMul/gravMul/speedMul/airJumps/magnet/dashSpd/startShield/revive）
  world.cyTune = {};     // mod 修改的蚩尤参数（gap/speed/skills）
  world.genTune = {};    // mod 修改的原版机关生成参数
  world.ugcHud = {};     // mod 注册的自定义 UI（每帧重建）
  world.ugcHooks = {};   // mod 注册的事件钩子（jump/coin/death）
  UGC_BG.src = '';       // 单关背景随局清空（全局背景不受影响）
  UGC_BG.img = null;
  G.boss = null;
  G.chiyou = null;    // 追击者每局按局外选择重建（蚩尤/阿坚）
  G.chaserOverride = null;    // 局内指定的追击者（plan.chaser / api.chaser）随局结束，不泄漏到下一局
  G.nextBossM = 200;

  G.boostT = 0;
  G.buff = { magnet: 0, invinc: 0, djump: 0, x2: 0, slow: 0, sprint: 0, frenzy: 0 };
  G.beltMul = 1;
  G.shield = 0;
  G.cardPop = null;
  G.crateCount = 0;
  G.comboMark = 0;

  player.y = GROUND_Y;
  player.prevY = GROUND_Y;
  player.vy = 0;
  player.onGround = true;
  player.sliding = false;
  player.slidingT = 0;
  player.run = 0;
  player.tilt = 0;
  player.dustT = 0;
  player.sx = 1;
  player.sy = 1;
  player.spin = 0;
  player.spinOn = false;
  player.deadT = 0;
  player.deadRot = 0;
  player.coyote = 0;
  player.jumpBuf = 0;
  player.jumps = 0;

  G.coins = 0;
  G.combo = 0;
  G.comboDelay = 0;
  G.nextMile = 100;
  G.clearStars = 0;
  G.clearAnim = 0;
  G.runTime = 0;
  G.hurtCount = 0;
  G.maxCombo = 0;
  G.jumpCount = 0;
  G.slideCount = 0;
  G.airTime = 0;
  G.padBounce = 0;
  G.maxSpeed = 0;
  G.objDone = false;
  G.objFlash = 0;
  G.nearCd = 0;
  G.holdJump = true;
  G.holdSlide = true;
  G.airJump = AIR_JUMP_MAX + upLv('djump');
  G.airCd = 0;
  G.energy = 0;
  G.dashT = 0;
  G.dashN = 0;
  G.feverT = 0;
  G.feverNeed = 20;
  G.dayT = 0;
  G.dayMark = 0;
  /* ---- 无尽模式：追击脱身 / 天气 / 悬赏 / 陷阱风暴 ---- */
  G.weather = null;       // 当前天气
  G.weatherT = 0;         // 当前天气剩余秒数
  G.weatherNext = 24;     // 距离下次天气
  G.weatherMark = '';
  G.acidUnder = false;    // 玩家是否踩在酸雨腐蚀区里（速度惩罚）
  G.bounty = null;        // 当前悬赏
  G.bountyDone = 0;       // 已完成悬赏数
  G.bountyNextM = 150;    // 下一个悬赏的里程
  G.surgeT = 0;           // 陷阱风暴剩余秒数
  G.surgeNextM = 500;     // 下一次陷阱风暴的里程
  G.surgeWin = 0;         // 撑过风暴次数
  G.escapeN = 0;          // 本局甩掉追击者次数
  G.escapeFlash = 0;      // 甩掉提示的高光计时
  /* ---- 局内升级（祭坛三选一，本局有效） ---- */
  G.up = { djump: 0, magnet: 0, energy: 0, combo: 0, speed: 0, coin: 0, shieldMax: 0 };
  G.altar = null;         // 当前正在选的三张牌
  G.altarT = 0;
  G.altarNextM = 320;     // 下一个祭坛的里程
  G.altarCount = 0;       // 本局吃了几个祭坛
  /* ---- 月面低重力段 / 疾风挑战段 ---- */
  G.moonT = 0;            // 月面剩余秒数
  G.moonNextM = 420;
  G.moonCount = 0;
  G.galeT = 0;            // 疾风段剩余秒数
  G.galeNextM = 780;
  G.galeCount = 0;
  G.nearStreak = 0;       // 连续极限闪避次数（身法连击）
  G.streakBest = 0;
  G.wallHit = 0;          // 刚刚撞过宝箱怪（用于视觉提示）
  G.revive = 0;
  G.knives = 0;
  FX.reset();
}

/* ---------------- 命运之门：跳起来穿上门，跑/滑铲穿下门 ---------------- */
const GATES = [
  { id: 'magnet', name: '磁铁 25s', color: '#7de0c8', icon: '吸', act() { G.buff.magnet = 25; } },
  { id: 'shield', name: '护盾 +1', color: '#63c9f0', icon: '盾', act() { G.shield = Math.min(shieldCap(), G.shield + 1); } },
  { id: 'rich',   name: '金币 +60', color: '#ffd34d', icon: '金', act() { G.coins += 60; } },
  { id: 'x2',     name: '双倍 25s', color: '#c9a0ff', icon: '×2', act() { G.buff.x2 = 25; } },
  { id: 'invinc', name: '无敌 6s', color: '#ff9a5a', icon: '无', act() { G.buff.invinc = Math.max(G.buff.invinc, 6); } },
  { id: 'energy', name: '能量充满', color: '#a8e86c', icon: '能', act() { G.energy = ENERGY_MAX; } },
  /* 风险门：速度+18% 但收益也大 */
  { id: 'frenzy', name: '狂暴 18s', color: '#ff6a8a', icon: '狂', risk: true, act() { G.buff.frenzy = 18; } },
];

function spawnGate(x) {
  const normal = GATES.slice(0, 6);
  let a = normal[Math.floor(Math.random() * normal.length)];
  let b = Math.random() < 0.34 ? GATES[6] : normal[Math.floor(Math.random() * normal.length)];
  if (Math.random() < 0.5) { const t = a; a = b; b = t; }
  world.gates.push({ x, top: a, bot: b, used: false, hitT: 0 });
}

function updateGates(dt) {
  // 生成：无尽模式约每 100~250 米一道（500 米内 1~3 扇，保持稀缺感）
  if (G.mode === 'endless' && world.x + W * 2 > world.gateNextX) {
    spawnGate(world.gateNextX);
    world.gateNextX += 5000 + Math.random() * 7500;
  }
  // 穿过判定
  for (const gt of world.gates) {
    if (!gt.used && world.x + PLAYER_X >= gt.x + 8) {
      gt.used = true;
      const up = player.y < GROUND_Y - 186;   // 腾空高度够 = 选上门
      const g = up ? gt.top : gt.bot;
      g.act();
      Snd.gate(!!g.risk);
      FX.ring(world.x + PLAYER_X, player.y - 64, { r0: 18, r1: 140, max: 0.55, color: g.color, width: 7 });
      FX.float(world.x + PLAYER_X + 10, player.y - 210, (g.risk ? '狂暴！' : g.name), { color: g.color, size: 33 });
      if (g.risk) { FX.flashScreen('#ff6a8a', 0.2); FX.addShake(0.3); }
      else FX.flashScreen(g.color, 0.12);
    }
    if (gt.used) gt.hitT += dt;
  }
  if (world.gates.length) {
    world.gates = world.gates.filter(gt => gt.x > world.x - 400 && (!gt.used || gt.hitT < 0.6));
  }
}

/* 地刺浪：常态蚩尤和 BOSS 共用的行进尖刺（全局更新，谁在场都有效） */
function updateQuakes(dt) {
  for (const q of world.quakes) {
    q.t += dt;
    q.x += q.v * dt;
    if (Math.random() < 0.5) {
      FX.burst(q.x - world.x + rnd(-20, 20), GROUND_Y - rnd(4, 22), 1, {
        vx0: -90, vx1: -30, vy0: -110, vy1: -20,
        life0: 0.2, life1: 0.45, size0: 4, size1: 9, color: 'rgba(170,150,120,0.85)',
      });
    }
    const px = world.x + PLAYER_X;
    if (Math.abs(q.x - px) < 52 && player.y > GROUND_Y - 92) { die('spike'); return; }
  }
  if (world.quakes.length) world.quakes = world.quakes.filter(q => q.t < 3.4 && q.x < world.x + W + 340);
}

/* ---------------- 新机关：火焰喷射口 / 传送带 / 上升气流 ---------------- */
function updateNewHazards(dt) {
  const box = playerBox();
  // 火焰喷射口：0~1.55 待机 → 1.55~2.25 红光预警 → 2.25~3.1 喷火
  for (const f of world.flames) {
    f.t += dt;
    if (f.t >= 3.2) f.t -= 3.2;
    if (f.t >= 2.25 && f.t < 3.1 && box.r > f.x && box.l < f.x + f.w && box.b > GROUND_Y - 150) {
      die('flame');
      return;
    }
  }
  // 传送带：踩上去改变全局速度
  G.beltMul = 1;
  if (player.onGround) {
    const px = world.x + PLAYER_X;
    for (const b of world.belts) {
      if (px > b.x && px < b.x + b.w) {
        G.beltMul = b.fast ? 1.42 : 0.72;
        if (b.fast && Math.random() < 0.3) {
          FX.burst(px + rnd(-46, 46), GROUND_Y - 8, 1, {
            vx0: -300, vx1: -160, vy0: -30, vy1: 20,
            life0: 0.15, life1: 0.3, size0: 4, size1: 8, color: 'rgba(140,225,255,0.85)',
          });
        }
        break;
      }
    }
  }
  // 上升气流：区域内缓缓托起，顶部有限高
  const px = world.x + PLAYER_X;
  for (const u of world.updrafts) {
    if (px > u.x && px < u.x + u.w && player.y > GROUND_Y - u.h) {
      player.vy -= 1150 * dt;                       // 抵消重力还略有盈余 → 缓慢上升
      if (player.vy > -175) player.vy = -175;       // 保持最低上升速度
      if (Math.random() < 0.22) {
        FX.burst(px + rnd(-32, 32), player.y - rnd(0, 46), 1, {
          vx0: -16, vx1: 16, vy0: -270, vy1: -150,
          life0: 0.3, life1: 0.6, size0: 3, size1: 7, color: 'rgba(150,240,220,0.72)',
        });
      }
    }
  }
}

/* ---------------- 昼夜循环：85 秒一天，夜晚有视野变化 ---------------- */
function nightAmount() {
  if (G.mode !== 'endless') return 0;
  const t = G.dayT % 85;
  if (t < 30) return 0;                    // 白天
  if (t < 40) return (t - 30) / 10;        // 黄昏入夜
  if (t < 70) return 1;                    // 深夜
  if (t < 80) return 1 - (t - 70) / 10;    // 黎明
  return 0;
}

/* ---------------- 无尽天气：雷暴 / 沙尘暴 / 酸雨 / 浓雾 ----------------
   每 40~62 秒随机来一种，持续 26~30 秒；撑过去有金币与能量奖励。
   天气只影响无尽模式，且都是「靠跑位能躲」的设计，不靠运气。 */
const WEATHERS = [
  { id: 'storm', name: '雷暴',   color: '#8cb4ff', life: 26, tip: '脚下蓝圈会落雷，跑开就行' },
  { id: 'sand',  name: '沙尘暴', color: '#d8a86a', life: 30, tip: '顶风跑会被拖慢，抓紧节奏' },
  { id: 'acid',  name: '酸雨',   color: '#a6e05a', life: 27, tip: '别站在腐蚀区里，会断连击' },
  { id: 'fog',   name: '浓雾',   color: '#c8d8f0', life: 30, tip: '看不远，但空气里飘满金币' },
];

function startWeather(w) {
  G.weather = {
    id: w.id, name: w.name, color: w.color, tip: w.tip,
    t: 0, life: w.life, boltCd: 1.1, acidCd: 0.6, moteCd: 0.25,
  };
  G.weatherT = w.life;
  G.weatherMark = w.id;
  FX.flashScreen(w.color, 0.2);
  FX.sfloat(W / 2, 256, '天象 · ' + w.name, { color: w.color, size: 40, life: 1.8, vy: -9 });
  FX.sfloat(W / 2, 312, w.tip, { color: 'rgba(255,255,255,0.95)', size: 25, life: 2.1, vy: -7 });
  Snd.whoosh();
}

function endWeather() {
  const w = G.weather;
  G.weather = null;
  G.weatherT = 0;
  G.acidUnder = false;
  if (!w) return;
  const bonus = 45;
  G.coins += bonus;
  G.energy = Math.min(ENERGY_MAX, G.energy + 40);
  FX.flashScreen('#ffe066', 0.16);
  FX.sfloat(W / 2, 262, '天气转晴  +' + bonus + ' 金币', { color: '#ffe066', size: 34, life: 1.7, vy: -9 });
  Snd.milestone();
}

/* 落雷落点：按当前速度预判玩家前方，给足 1 秒以上的跑位时间 */
function spawnBolt() {
  const lead = world.speed * rnd(1.3, 1.95);
  world.bolts.push({ x: world.x + PLAYER_X + clamp(lead, 240, W - 60), t: 0, warn: 1.05, hit: false });
}

/* 酸滴落点 → 砸出一小片腐蚀区 */
function spawnAcid() {
  const x = world.x + rnd(PLAYER_X - 70, W - 40);
  world.acidPools.push({ x: x, t: 0, life: 2.4, hit: false });
  FX.burst(x, GROUND_Y - 250, 3, {
    vx0: -14, vx1: 14, vy0: 300, vy1: 460,
    life0: 0.28, life1: 0.46, size0: 4, size1: 9, color: 'rgba(166,224,90,0.85)',
  });
}

function updateWeather(dt) {
  if (G.mode !== 'endless') return;
  if (!G.weather) {
    G.weatherNext -= dt;
    if (G.weatherNext <= 0) {
      startWeather(WEATHERS[Math.floor(Math.random() * WEATHERS.length)]);
      G.weatherNext = rnd(40, 62);
    }
    return;
  }
  const w = G.weather;
  w.t += dt;
  G.weatherT = Math.max(0, w.life - w.t);

  if (w.id === 'storm') {
    w.boltCd -= dt;
    if (w.boltCd <= 0) { w.boltCd = rnd(1.15, 2.0); spawnBolt(); Snd.spikeOut(); }
  }
  // 落雷推进与命中（竖雷：站在圈里就中，跳没用，必须横移）
  // 注意坐标系：bolt.x 与玩家判定框都是世界坐标，粒子也是世界层
  const box = playerBox();
  const pc = (box.l + box.r) / 2;
  for (const b of world.bolts) {
    b.t += dt;
    if (!b.hit && b.t >= b.warn && b.t < b.warn + 0.22) {
      b.hit = true;
      FX.flashScreen('#eaf3ff', 0.24);
      FX.addShake(0.4);
      Snd.crack();
      for (let i = 0; i < 10; i++) {
        FX.burst(b.x, GROUND_Y - rnd(0, 30), 1, {
          vx0: -280, vx1: 280, vy0: -320, vy1: -40,
          life0: 0.25, life1: 0.5, size0: 4, size1: 11, color: 'rgba(226,240,255,0.9)',
        });
      }
      if (Math.abs(pc - b.x) < 42) { die('bolt'); return; }
    }
  }
  if (world.bolts.length) world.bolts = world.bolts.filter(b => b.t < b.warn + 0.45);

  // 酸雨：腐蚀区拖慢脚步 + 断连击（不致死，压力型）
  if (w.id === 'acid') {
    w.acidCd -= dt;
    if (w.acidCd <= 0) { w.acidCd = rnd(0.75, 1.35); spawnAcid(); }
  }
  let under = false;
  if (world.acidPools.length) {
    for (const p of world.acidPools) {
      p.t += dt;
      if (!p.hit && p.t > 0.15 && Math.abs(pc - p.x) < 58 && player.y > GROUND_Y - 96) {
        p.hit = true;
        if (G.combo > 0) {
          G.combo = 0;
          FX.float(world.x + PLAYER_X + 30, player.y - 190, '连击断了', { color: '#a6e05a', size: 24 });
        }
        Snd.nearMiss();
      }
      if (Math.abs(pc - p.x) < 58 && player.y > GROUND_Y - 96) under = true;
    }
    world.acidPools = world.acidPools.filter(p => p.t < p.life);
  }
  G.acidUnder = under;

  // 沙尘 / 雾：飘动颗粒（氛围 + 能见度）
  if (w.id === 'sand' || w.id === 'fog') {
    w.moteCd -= dt;
    if (w.moteCd <= 0) {
      const sand = w.id === 'sand';
      w.moteCd = sand ? 0.05 : 0.16;
      // FX.burst 是世界层粒子：沙尘从屏幕右侧往左飘，雾在全屏随机
      FX.burst(world.x + rnd(sand ? W - 40 : 0, W + 120), rnd(60, GROUND_Y - 20), 1, {
        vx0: sand ? -430 : -90, vx1: sand ? -260 : -40,
        vy0: sand ? -30 : -8, vy1: sand ? 40 : 10,
        life0: sand ? 0.5 : 1.6, life1: sand ? 1.0 : 2.6,
        size0: sand ? 3 : 4, size1: sand ? 7 : 9,
        color: sand ? 'rgba(216,168,106,0.55)' : 'rgba(222,232,246,0.42)',
      });
    }
  }

  if (w.t >= w.life) endWeather();
}

/* ---------------- 无尽悬赏：每 150 米一个随机小目标，达成有奖 ---------------- */
const BOUNTIES = [
  { id: 'coin',  need: 30, name: '收集 30 枚金币',   color: '#ffd34d' },
  { id: 'combo', need: 16, name: '累计 16 连击',     color: '#ff9ed8' },
  { id: 'nohit', need: 220, name: '不失误跑 220 米', color: '#7de08a' },
  { id: 'jump',  need: 18, name: '跳跃 18 次',       color: '#8ce8ff' },
  { id: 'slide', need: 14, name: '滑铲 14 次',       color: '#c9a0ff' },
];

function rollBounty() {
  const b = BOUNTIES[Math.floor(Math.random() * BOUNTIES.length)];
  G.bounty = {
    id: b.id, name: b.name, color: b.color, need: b.need, have: 0,
    base: Math.floor(world.x / PX_PER_M),
  };
  FX.sfloat(W / 2, 300, '悬赏 · ' + b.name, { color: b.color, size: 31, life: 1.7, vy: -8 });
  Snd.spikeOut();
}

function bountyHave() {
  const b = G.bounty;
  if (!b) return 0;
  return b.id === 'nohit' ? Math.floor(world.x / PX_PER_M) - b.base : b.have;
}

function bountyAdd(id, n) {
  const b = G.bounty;
  if (!b || b.id !== id) return;
  b.have += (n || 1);
  if (b.have >= b.need) completeBounty();
}

function completeBounty() {
  const b = G.bounty;
  if (!b) return;
  G.bounty = null;
  G.bountyDone++;
  const gold = 60 + G.bountyDone * 15;
  G.coins += gold;
  G.energy = Math.min(ENERGY_MAX, G.energy + 45);
  if (Math.random() < 0.45) G.shield = Math.min(shieldCap(), G.shield + 1);
  FX.flashScreen(b.color, 0.18);
  FX.ring(world.x + PLAYER_X, player.y - 90, { r0: 18, r1: 170, max: 0.5, color: b.color, width: 7 });
  FX.sfloat(W / 2, 284, '悬赏达成 +' + gold + ' 金币', { color: b.color, size: 38, life: 1.8, vy: -10 });
  Snd.milestone();
}

function updateBounty() {
  if (G.mode !== 'endless') return;
  if (G.bounty && G.bounty.id === 'nohit' && bountyHave() >= G.bounty.need) completeBounty();
  const m = Math.floor(world.x / PX_PER_M);
  if (!G.bounty && m >= G.bountyNextM) {
    G.bountyNextM = m + 150;
    rollBounty();
  }
}

/* ---------------- 陷阱风暴：每 500 米一段高密度机关期 ---------------- */
function updateSurge(dt) {
  if (G.mode !== 'endless') return;
  if (G.surgeT > 0) {
    G.surgeT = Math.max(0, G.surgeT - dt);
    if (G.surgeT === 0) {
      const gold = 90 + (G.surgeWin || 0) * 30;
      G.surgeWin = (G.surgeWin || 0) + 1;
      G.coins += gold;
      G.energy = ENERGY_MAX;
      FX.flashScreen('#ffd34d', 0.22);
      FX.sfloat(W / 2, 286, '风暴平息 +' + gold + ' 金币', { color: '#ffe066', size: 38, life: 1.8, vy: -10 });
      Snd.clear();
    }
    return;
  }
  const m = Math.floor(world.x / PX_PER_M);
  if (m >= G.surgeNextM) {
    G.surgeNextM = m + 500;
    G.surgeT = 34;
    FX.flashScreen('#ff5a4a', 0.3);
    FX.addShake(0.55);
    FX.sfloat(W / 2, 258, '陷阱风暴！机关翻倍', { color: '#ff8f7e', size: 42, life: 1.9, vy: -11 });
    Snd.crack();
  }
}

/* ---------------- 局内升级：无尽祭坛三选一 ----------------
   每 320 米出现一座祭坛，碰到就暂停世界、亮出三张牌，选一张永久强化本局。
   词条全部挂到已有系统（跳跃/磁铁/能量/连击/速度/金币/护盾）上，不另造一套规则。 */
const UPGRADES = [
  { id: 'djump', name: '风息之靴', desc: '空中补跳 +1', color: '#8ce8ff', max: 3 },
  { id: 'magnet', name: '磁力核心', desc: '拾取范围 +45', color: '#ff9ed8', max: 4 },
  { id: 'energy', name: '超导线圈', desc: '冲刺能量攒得更快', color: '#a8e86c', max: 3 },
  { id: 'combo', name: '节拍器', desc: '连击窗口 +0.2 秒', color: '#c9a0ff', max: 3 },
  { id: 'speed', name: '疾风羽', desc: '奔跑速度 +5%', color: '#ff9a5a', max: 4 },
  { id: 'coin', name: '贪金护符', desc: '金币收益 +20%', color: '#ffd34d', max: 4 },
  { id: 'shieldMax', name: '守护刻印', desc: '立刻 +1 护盾，上限 +1', color: '#7de08a', max: 2 },
];

function upLv(id) { return (G.up && G.up[id]) || 0; }
/** 护盾上限：基础 2 + 祭坛「守护刻印」 */
function shieldCap() { return 2 + upLv('shieldMax'); }

/** 祭坛亮牌：抽 3 张还能升的；全满则直接折成金币。 */
function openAltar() {
  const pool = UPGRADES.filter(u => upLv(u.id) < u.max);
  if (!pool.length) {
    G.coins += 150;
    FX.sfloat(W / 2, 300, '祭坛 · 已无强化  +150 金币', { color: '#ffd34d', size: 32, life: 1.8, vy: -8 });
    Snd.milestone();
    return;
  }
  const picks = [];
  const left = pool.slice();
  while (picks.length < 3 && left.length) picks.push(left.splice(Math.floor(Math.random() * left.length), 1)[0]);
  G.altar = { picks: picks };
  G.altarT = 0;
  G.state = 'altar';
  G.altarCount = (G.altarCount || 0) + 1;
  FX.flashScreen('#c9a0ff', 0.22);
  Snd.gate(false);
}

/** 选牌生效（本局永久）。 */
function pickUpgrade(uid) {
  const a = G.altar;
  if (!a) return;
  const u = a.picks.find(x => x.id === uid);
  if (!u) return;
  if (!G.up) G.up = {};
  G.up[u.id] = upLv(u.id) + 1;
  G.altar = null;
  G.state = 'playing';
  G.buff.invinc = Math.max(G.buff.invinc, 1.3);      // 缓冲一下，避免刚恢复就被撞
  if (u.id === 'shieldMax') G.shield = Math.min(2 + upLv('shieldMax'), G.shield + 1);
  if (u.id === 'djump') G.airJump = AIR_JUMP_MAX + upLv('djump');
  if (u.id === 'magnet') applyBuff('magnet', true);
  FX.flashScreen(u.color, 0.24);
  FX.sfloat(W / 2, 300, u.name + ' · ' + u.desc, { color: u.color, size: 33, life: 1.9, vy: -9 });
  FX.ring(world.x + PLAYER_X, player.y - 90, { r0: 18, r1: 180, max: 0.5, color: u.color, width: 7 });
  Snd.clear();
}

/** 祭坛三张牌的坐标（绘制与点击热区共用，保证所见即所点）。 */
function altarCards() {
  const a = G.altar;
  if (!a) return [];
  const n = a.picks.length;
  const cw = Math.min(300, Math.round((W - 60) / Math.max(1, n)) - 12);
  const ch = 210;
  const totalW = n * cw + (n - 1) * 12;
  const x0 = Math.round((W - totalW) / 2);
  const y = Math.round(H * 0.46);
  return a.picks.map((u, i) => ({
    u: u, x: x0 + i * (cw + 12), y: y, w: cw, h: ch, lv: upLv(u.id), max: u.max,
  }));
}

/* ---------------- 月面低重力段 / 疾风挑战段 ----------------
   月面：重力降到 55%，跳得更高更飘；
   疾风：全程 +22% 速度、金币翻倍，跑满 15 秒给大奖。都是限时的节奏变化。 */
function moonActive() { return G.moonT > 0; }
function gravMul() { return moonActive() ? 0.55 : 1; }

function updateWorldEvents(dt) {
  if (G.mode !== 'endless') return;
  const m = Math.floor(world.x / PX_PER_M);
  // ── 月面段 ──
  if (G.moonT > 0) {
    G.moonT = Math.max(0, G.moonT - dt);
    // 月面上漂着成排金币（配合更高的跳跃）
    if (Math.random() < 0.07) {
      const bx0 = world.x + W + rnd(40, 240);
      const by = GROUND_Y - rnd(230, 330);
      for (let i = 0; i < 5; i++) {
        world.coins.push({
          x: bx0 + i * 74, y: by - Math.sin((i / 4) * Math.PI) * 42,
          phase: i * 1.1, taken: false,
        });
      }
    }
    if (Math.random() < 0.25) {
      FX.burst(world.x + rnd(0, W), rnd(GROUND_Y - 420, GROUND_Y - 250), 1, {
        vx0: -30, vx1: 10, vy0: -20, vy1: 10,
        life0: 0.9, life1: 1.8, size0: 2, size1: 5, color: 'rgba(220,235,255,0.75)',
      });
    }
    if (G.moonT === 0) {
      FX.sfloat(W / 2, 268, '回到地面 · 重力恢复', { color: '#a8c4f2', size: 32, life: 1.7, vy: -8 });
      FX.addShake(0.25);
    }
  } else if (m >= G.moonNextM) {
    G.moonNextM = m + 520;
    G.moonT = 13;
    G.moonCount = (G.moonCount || 0) + 1;
    FX.flashScreen('#b8d0ff', 0.26);
    FX.sfloat(W / 2, 262, '月面段 · 重力变轻', { color: '#cfe0ff', size: 42, life: 1.9, vy: -10 });
    FX.sfloat(W / 2, 316, '跳得更高更飘，别冲过头', { color: 'rgba(255,255,255,0.95)', size: 26, life: 2.1, vy: -7 });
    Snd.whoosh();
  }
  // ── 疾风段 ──
  if (G.galeT > 0) {
    G.galeT = Math.max(0, G.galeT - dt);
    if (Math.random() < 0.5) {
      FX.burst(world.x + rnd(W - 60, W + 80), rnd(60, GROUND_Y - 20), 1, {
        vx0: -620, vx1: -380, vy0: -20, vy1: 20,
        life0: 0.35, life1: 0.7, size0: 3, size1: 8, color: 'rgba(200,240,255,0.5)',
      });
    }
    if (G.galeT === 0) {
      const gold = 120;
      G.coins += gold;
      G.energy = ENERGY_MAX;
      FX.flashScreen('#8ce8ff', 0.22);
      FX.sfloat(W / 2, 280, '疾风跑完 +' + gold + ' 金币', { color: '#8ce8ff', size: 38, life: 1.9, vy: -10 });
      Snd.clear();
    }
  } else if (m >= G.galeNextM) {
    G.galeNextM = m + 820;
    G.galeT = 15;
    G.galeCount = (G.galeCount || 0) + 1;
    FX.flashScreen('#8ce8ff', 0.28);
    FX.addShake(0.4);
    FX.sfloat(W / 2, 258, '疾风段 · 全程提速', { color: '#8ce8ff', size: 44, life: 1.9, vy: -11 });
    FX.sfloat(W / 2, 314, '这一路金币翻倍，跑满 15 秒有大奖', { color: 'rgba(255,255,255,0.95)', size: 25, life: 2.1, vy: -7 });
    Snd.dash();
  }
}

/* 身法连击：连续极限闪避攒「身法」，满 4 次送一次短无敌 + 能量 */
function nearStreakAdd() {
  G.nearStreak = (G.nearStreak || 0) + 1;
  if (G.nearStreak > (G.streakBest || 0)) G.streakBest = G.nearStreak;
  if (G.nearStreak >= 4) {
    G.nearStreak = 0;
    G.buff.invinc = Math.max(G.buff.invinc, 1.1);
    G.energy = Math.min(ENERGY_MAX, G.energy + 25);
    G.coins += 20;
    FX.flashScreen('#ffe066', 0.18);
    FX.sfloat(W / 2, 292, '身法！+20 金币 · 短无敌', { color: '#ffe066', size: 34, life: 1.7, vy: -9 });
    Snd.milestone();
  } else {
    FX.float(world.x + PLAYER_X + 40, player.y - 214, '身法 ' + G.nearStreak + '/4', { color: '#ffe066', size: 19 });
  }
}

/* 撞碎障碍 / 踩死小怪的统一收益出口 */
function smashReward(x, y, label, gold) {
  G.coins += gold;
  G.combo++;
  G.comboDelay = 0.7;
  if (G.combo > G.maxCombo) G.maxCombo = G.combo;
  bountyAdd('combo', 1);
  FX.float(x, y, label + ' +' + gold, { color: '#8ce8ff', size: 24 });
  Snd.star(1);
}

// ---- 关卡数据：赛道 + 随机目标 ----

/* 用 world 全部字段造一个空壳，避免新增机关类型时漏字段 */
function newWorldShell() {
  const tmp = {};
  for (const k in world) tmp[k] = Array.isArray(world[k]) ? [] : world[k];
  tmp.x = 0;
  tmp.speed = BASE_SPEED;
  tmp.genCursor = 0;
  tmp.speedBonus = 0;
  return tmp;
}

/* 生成一关的全部数据：赛道 + 环境统计 + 随机通关目标 */
function genLevelData(idx) {
  const shell = newWorldShell();
  let cards = null, endX = 0, title = '', baseSpeed = 0;
  if (idx === 0) {
    // 第 1 关：固定新手关
    title = LEVELS[0].name;
    endX = buildLevel(0, shell);
    baseSpeed = LEVELS[0].speed;
  } else {
    // 第 2 关起：抽 13 张牌铺赛道，关号越高越坑
    cards = drawLevelCards(idx);
    endX = buildFromCards(cards, shell, idx);
    baseSpeed = 348 + Math.min(64, idx * 5) + rnd(-6, 6) + (shell.speedBonus || 0);
    // 用抽到的最危险的一张牌命名本关
    const hi = cards.filter(c => c.danger === 3);
    title = hi.length ? hi[Math.floor(Math.random() * hi.length)].name : '随机赛道';
  }
  return {
    idx: idx, shell: shell, cards: cards, endX: endX, title: title,
    baseSpeed: baseSpeed, coinTotal: shell.coins.length,
    objective: rollObjective(idx, shell, endX, baseSpeed),
  };
}

/* 取关卡数据：关卡界面的预览与正式进入共用同一份，保证所见即所得 */
function levelData(idx, reroll) {
  if (reroll || !G.levelCache[idx]) G.levelCache[idx] = genLevelData(idx);
  return G.levelCache[idx];
}

// ---- 流程 ----
function startEndless() {
  Snd.init();
  Snd.click();
  clearWorld();
  G.mode = 'endless';
  G.state = 'playing';
  G.baseSpeed = BASE_SPEED;
  G.pressed = {};
  // 挂载独立 mod 库：机关mod库里玩家安装的机制/陷阱 mod（与关卡作品的 mod 互不重合）
  // scope 字段：endless(默认)=只无尽 / level=只官方关 / all=全部模式都生效
  const lib = ugcModsSync().filter(m => !m.scope || m.scope === 'endless' || m.scope === 'all');
  world.ugcMods = lib.slice(0, UGC_MAX_MODS).map(m => ({
    id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0,
  }));
  for (const mod of world.ugcMods) compileUgcMod(mod);
  // 机制 mod 的 onSpawn 在开局生效（api.player / api.tune / api.chiyou 等）
  for (const mod of world.ugcMods) runUgcFn(mod, 'onSpawn', mod._spawnFn);
  applyPTuneStart();   // mod 给的开局护盾/复活次数
  if (world.ugcMods.length) FX.sfloat(W / 2, 210, 'mod 已启用 ×' + world.ugcMods.length, { color: '#8ce8ff', size: 30, life: 2 });
  generateAhead();
  Music.duck(0.2);
  Music.start();
}

function openLevelSelect() {
  Snd.init();
  Snd.click();
  clearWorld();
  G.mode = 'level';
  G.state = 'levels';
  G.pressed = {};
  clearTweens('all');
}

function openSkinSelect() {
  Snd.init();
  Snd.click();
  clearWorld();
  G.state = 'skins';
  G.pressed = {};
  clearTweens('all');
}

/* 换皮肤：已解锁才能用 */
function trySetSkin(i) {
  if (!skinUnlocked(i)) { Snd.back(); FX.addShake(0.25); return; }
  if (i === G.skinIdx) { Snd.click(); return; }
  setSkin(i);
  Snd.clear();
  FX.flashScreen(SKINS[i].color, 0.25);
}

function tryStartLevel(idx) {
  const unlocked = idx === 0 || G.stars[idx - 1] > 0 || G.stars[idx] > 0;
  if (!unlocked) { Snd.back(); FX.addShake(0.25); return; }
  startLevel(idx);
}

function startLevel(idx, reroll) {
  Snd.init();
  Snd.click();
  const data = levelData(idx, reroll);
  clearWorld();
  // 赛道在关卡界面就生成好了，这里直接搬过来，所见即所得
  for (const k in data.shell) world[k] = data.shell[k];
  G.mode = 'level';
  G.levelIndex = idx;
  G.cardRevealT = 0;
  G.levelCards = data.cards;
  G.levelTitle = data.title;
  G.levelEndX = data.endX;
  G.baseSpeed = data.baseSpeed;
  G.levelCoinTotal = data.coinTotal;
  G.objective = data.objective;
  G.objDone = false;
  world.x = 0;
  world.speed = G.baseSpeed;
  world.genCursor = G.levelEndX + 2000;
  G.state = 'playing';
  G.pressed = {};
  // 全局机制 mod（scope: level/all）：官方关卡也挂 mod 库，规则改写全局生效
  const lvLib = ugcModsSync().filter(m => m.scope === 'level' || m.scope === 'all').slice(0, UGC_MAX_MODS);
  if (lvLib.length) {
    world.ugcMods = lvLib.map(m => ({ id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0, scope: m.scope }));
    for (const mod of world.ugcMods) compileUgcMod(mod);
    for (const mod of world.ugcMods) runUgcFn(mod, 'onSpawn', mod._spawnFn);
    applyPTuneStart();
    FX.sfloat(W / 2, 466, '全局 mod ×' + world.ugcMods.length, { color: '#8ce8ff', size: 26, life: 1.6, vy: -6 });
  }
  Music.duck(0.2);
  Music.start();
  // 开场先亮一下关卡名和这次抽到的目标
  FX.sfloat(W / 2, 286, '第 ' + (idx + 1) + ' 关', { color: '#ffe066', size: 46, life: 1.4, vy: -6 });
  FX.sfloat(W / 2, 350, '目标 · ' + G.objective.name, { color: G.objective.color, size: 38, life: 1.7, vy: -6 });
  FX.sfloat(W / 2, 408, objectiveDesc(G.objective), { color: 'rgba(255,255,255,0.96)', size: 25, life: 1.9, vy: -6 });
}

/* ---------------- UGC 自定义关卡：构建 / 启动 / 沙箱 / 结算 ---------------- */
/* 与 levels.js buildLevel 保持同步：把 plan 铺进 shell。类型包含官方 19 种 +
   自定义机关类型名（落到 world.ugcHazards，由 mod 的 onUpdate 驱动）。 */
function buildUgcPlan(item, shell) {
  const P = PX_PER_M;
  const GY = GROUND_Y;
  if (!shell.chaseTriggers) shell.chaseTriggers = [];
  const plan = item.plan || [];
  for (let k = 0; k < plan.length; k++) {
    const type = plan[k][1];
    const opt = plan[k][2] || {};
    const x = plan[k][0] * P;
    switch (type) {
      case 'coins': {
        const n = opt.n || 3;
        const y = GY - (opt.high ? 152 : 112);
        for (let i = 0; i < n; i++) shell.coins.push({ x: x + i * 80, y: y, phase: i * 1.1, taken: false });
        break;
      }
      case 'fakeCoins': {
        const n = opt.n || 3;
        for (let i = 0; i < n; i++) shell.fakeCoins.push({ x: x + i * 74, y: GY - rnd(96, 128), phase: i * 1.4, taken: false });
        break;
      }
      case 'gap': {
        const w = opt.w || 150;
        shell.gaps.push({ x: x, w: w, spikes: Math.max(2, Math.round(w / 44)) });
        shell.signs.push({ x: x - 200 });
        if (!opt.noArc) addArcCoins(shell, x, w);
        break;
      }
      case 'fakeFloor':
        shell.fakeFloors.push({ x: x, w: opt.w || 130, broken: false, t: 0 });
        break;
      case 'spikeTrap':
        shell.spikeTraps.push({ x: x, w: opt.w || 110, phase: (k * 0.83) % 3 });
        break;
      case 'hiddenSpike':
        shell.hiddenSpikes.push({ x: x, w: opt.w || 104, t: -1 });
        break;
      case 'trapSpring': {
        const w = opt.w || 116;
        shell.trapSprings.push({ x: x, w: w, t: -1 });
        shell.ceilSpikes.push({ x: x - 40, w: w + 80, low: opt.low || rnd(298, 326) });
        break;
      }
      case 'crumble':
        shell.crumbles.push({ x: x, w: opt.w || 128, top: GY - (opt.h || 236), h: 62, t: -1, broken: false, fall: 0 });
        break;
      case 'crusher':
        shell.crushers.push({ x: x, w: opt.w || rnd(112, 148), high: GY - (opt.high || 300), travel: opt.travel || 206, spd: opt.spd || 1.05, phase: (k * 1.13) % 6.283 });
        break;
      case 'chaseRock':
        shell.chaseTriggers.push({ x: x, spawned: false, rel: opt.rel || rnd(122, 148) });
        break;
      case 'lowBar':
        shell.lowBars.push({ x: x, w: opt.w || 96, gapH: opt.gapH || 96 });
        break;
      case 'spikeCeil':
        shell.ceilSpikes.push({ x: x, w: opt.w || 60, low: opt.low || 278 });
        break;
      case 'bouncePad':
        shell.bouncePads.push({ x: x, w: opt.w || 130, t: -1, pop: 0 });
        break;
      case 'booster':
        shell.boosters.push({ x: x, w: opt.w || 150, gl: 0 });
        break;
      case 'pendulum':
        shell.pendulums.push({ x: x, pivotY: opt.pivotY || 96, len: opt.len || 470, r: opt.r || 42, amp: opt.amp || 0.62, spd: opt.spd || 1.15, phase: (k * 1.31) % 6.283 });
        break;
      case 'rollingRock':
        shell.rollingRocks.push({ x: x + 900, y: GY - 54, r: 54, vx: -(opt.vx || 150), spin: 0, spawnX: x, alive: true });
        break;
      case 'fallingRock':
        shell.fallingRocks.push({ x: x, y: -80, vy: 0, r: opt.r || 42, triggered: false, landed: false });
        break;
      case 'movingPlatform':
        shell.movingPlatforms.push({ x: x, w: opt.w || 180, y0: GY - (opt.h || 236), amp: opt.amp || 46, spd: opt.spd || 1.35, phase: (k * 1.7) % 6.283 });
        break;
      case 'platform': {
        const w = opt.w || 200;
        const top = GY - (opt.level === 'high' ? 268 : 240);
        shell.platforms.push({ x: x, w: w, top: top, h: 70 });
        const n = opt.coins || 3;
        for (let i = 0; i < n; i++) {
          shell.coins.push({ x: x + w * (0.25 + 0.5 * i / Math.max(1, n - 1)), y: top - 70, phase: i, taken: false });
        }
        break;
      }
      case 'bait': {
        const gw = opt.w || 166;
        const gx = x + 158;
        shell.gaps.push({ x: gx, w: gw, spikes: Math.max(2, Math.round(gw / 44)) });
        shell.signs.push({ x: gx - 210 });
        for (let i = 0; i < 3; i++) shell.coins.push({ x: gx - 160 + i * 64, y: GY - 68, phase: i * 0.8, taken: false });
        addArcCoins(shell, gx, gw);
        break;
      }
      default: {
        // 自定义机关：落到通用运行段，由对应对型 mod 的 onUpdate 驱动
        // x 为实体中心；gy = 离地高度（显式 y 优先），w/h 为碰撞盒；img 可写素材 id（如 "bg1"）自动换成 asset.src
        const modDef = (item.mods || []).find(m => m.id === type);
        let img = modDef ? modDef.img : null;
        if (typeof opt.img === 'string') {
          const ast = (item.assets || []).find(a => a.id === opt.img);
          img = ast ? ast.src : opt.img;
        }
        shell.ugcHazards.push({
          id: type, type: type, dead: false, img: img,
          ...(opt || {}),
          x: x,
          y: opt.y !== undefined ? opt.y : GY - (opt.gy !== undefined ? opt.gy : 96),
          w: opt.w !== undefined ? opt.w : 52,
          h: opt.h !== undefined ? opt.h : 52,
        });
        break;
      }
    }
  }
  return item.lenM * P;
}

/* 构造一次调用用的受限 api（每次构造，保证读到最新数值） */
function buildUgcApi(mod) {
  const api = {
    ver: 2,
    _mod: mod || null,
    x: world.x + PLAYER_X,
    y: player.y,
    dt: G.lastDt || 1 / 60,
    worldX: world.x,
    speed: world.speed,
    make: (type, o) => {
      const modDef = (world.ugcMods || []).find(m => m.id === type);
      const e = { id: type, type: type, dead: false, img: modDef ? modDef.img : null, w: 52, h: 52, ...(o || {}) };
      world.ugcHazards.push(e);
      return e;
    },
    time: () => G.time,
    rand: (a, b) => rnd(a, b),
    mode: G.mode,
    /* ---- 玩家机制：jumpMul 跳跃力 / gravMul 重力 / speedMul 速度 / airJumps 额外空中跳 ---- */
    player: patch => {
      const t = world.pTune || (world.pTune = {});
      if (!patch || typeof patch !== 'object') return { jumpMul: 1, gravMul: 1, speedMul: 1, airJumps: 0, magnet: 0, dashSpd: 1.55, startShield: 0, revive: 0, ...t };
      if (patch.jumpMul !== undefined) t.jumpMul = clamp(patch.jumpMul, 0.55, 1.7);
      if (patch.gravMul !== undefined) t.gravMul = clamp(patch.gravMul, 0.5, 1.9);
      if (patch.speedMul !== undefined) t.speedMul = clamp(patch.speedMul, 0.55, 1.9);
      if (patch.airJumps !== undefined) t.airJumps = clamp(Math.round(patch.airJumps), 0, 3);
      if (patch.magnet !== undefined) t.magnet = clamp(patch.magnet, 0, 800);
      if (patch.dashSpd !== undefined) t.dashSpd = clamp(patch.dashSpd, 1, 3);
      if (patch.startShield !== undefined) t.startShield = clamp(Math.round(patch.startShield), 0, 2);
      if (patch.revive !== undefined) t.revive = clamp(Math.round(patch.revive), 0, 9);
      return { jumpMul: 1, gravMul: 1, speedMul: 1, airJumps: 0, magnet: 0, dashSpd: 1.55, startShield: 0, revive: 0, ...t };
    },
    /* 立刻加复活次数（onDeath 钩子里做「自动复活」机制用） */
    revive: n => { G.revive = Math.min(9, (G.revive || 0) + clamp(Math.round(n || 1), 1, 9)); },
    /* 事件钩子：api.on('jump'|'coin'|'death'|'key'|'tap'|'draw'|'menu', fn) —— 主动/被动机制的基础
       key: 按键(info.code/key)；tap: 点击屏幕(info.x/y)；draw: 每帧自定义绘制(info.worldX)
       menu: 主菜单每帧绘制（注册类钩子，全局生效——菜单背景装饰/待机演出用） */
    on: (ev, fn) => {
      if (typeof fn !== 'function') return;
      const k = (['jump', 'coin', 'death', 'key', 'tap', 'draw', 'menu'].includes(ev)) ? ev : null;
      if (!k) return;
      if (k === 'menu') {
        ugcRegPush(UGC_MENU_HOOKS, (api._mod ? api._mod.id : 'x') + '#' + UGC_MENU_HOOKS.length, { mod: api._mod, fn });
        return;
      }
      const hs = world.ugcHooks || (world.ugcHooks = {});
      (hs[k] = hs[k] || []).push({ mod: api._mod, fn });
    },
    /* ---- 注册自定义模式：api.mode({ id, name, desc, chase?, onStart?, onFrame?, onDraw?, onKey?, onTap?, onDeath? })
       注册后主菜单出现模式入口（标题下方小卡行）；开局 = 空跑酷沙盒（无原版机关自动生成，chase:true 才有蚩尤），
       onFrame(api, { dt }) 每帧驱动玩法，api.gameOver({ win, title?, sub? }) 结束本局，api.hud/button/spawn 照常可用 ---- */
    mode: def => {
      if (!def || typeof def !== 'object' || !def.id) return;
      const d = {
        key: String(def.id).slice(0, 20),
        id: String(def.id).replace(/[^\w\u4e00-\u9fa5-]/g, '').slice(0, 16) || 'mode',
        name: String(def.name || '自定义模式').slice(0, 8),
        desc: String(def.desc || '').slice(0, 26),
        chase: !!def.chase,
        mod: api._mod ? { id: api._mod.id, name: api._mod.name, onSpawn: api._mod.onSpawn, onUpdate: api._mod.onUpdate, img: api._mod.img } : null,
        onStart: typeof def.onStart === 'function' ? def.onStart : null,
        onFrame: typeof def.onFrame === 'function' ? def.onFrame : null,
        onKey: typeof def.onKey === 'function' ? def.onKey : null,
        onTap: typeof def.onTap === 'function' ? def.onTap : null,
        onDeath: typeof def.onDeath === 'function' ? def.onDeath : null,
      };
      ugcRegPush(UGC_MODES, d.key, d);
    },
    /* ---- 主菜单按钮：api.menuButton({ label, fn, color? }) —— 主菜单左上角出现一排按钮（注册一次持续显示） ---- */
    menuButton: opt => {
      if (!opt || typeof opt.fn !== 'function') return;
      const label = String(opt.label || '?').slice(0, 5);
      ugcRegPush(UGC_MENU_BTNS, (api._mod ? api._mod.id : 'x') + ':' + label, {
        key: (api._mod ? api._mod.id : 'x') + ':' + label,
        label, mod: api._mod ? { id: api._mod.id, name: api._mod.name } : null, fn: opt.fn,
      });
    },
    /* ---- 结束自定义模式：api.gameOver({ win, title?, sub? }) —— win=true 胜利结算（撒花），false 失败 ---- */
    gameOver: opt => {
      if (G.mode !== 'custom' || G.state !== 'playing') return;
      opt = opt || {};
      G.customOver = { win: !!opt.win, title: String(opt.title || '').slice(0, 12), sub: String(opt.sub || '').slice(0, 24) };
      G.state = 'gameover';
      G.pressed = {};
      Music.duck(0.08);
      if (opt.win) {
        Snd.clear();
        FX.flashScreen('#ffffff', 0.35);
        for (let i = 0; i < 46; i++) {
          FX.sburst(rnd(60, W - 60), rnd(160, 420), 1, {
            vx0: -140, vx1: 140, vy0: -60, vy1: 160, life0: 0.7, life1: 1.5, size0: 5, size1: 14,
            color: ['#ffd34d', '#7de08a', '#8fc4f2', '#ff9c8a', '#ffffff'][i % 5],
          });
        }
      } else {
        Snd.fail();
      }
    },
    /* 主动技能按钮：api.button({ label, fn, x?, y?, w?, h?, color? }) —— 屏幕上画一个可点按钮
       （手机也能按）；每帧重新调用才会持续显示（与 api.hud 同款重建式）。点击时回调 fn(api) */
    button: opt => {
      if (!opt || typeof opt.fn !== 'function') return;
      const bs = world.ugcBtns || (world.ugcBtns = []);
      bs.push({
        mod: api._mod, label: String(opt.label || '?').slice(0, 4),
        x: opt.x, y: opt.y, w: clamp(opt.w || 92, 56, 200), h: clamp(opt.h || 92, 56, 200),
        color: typeof opt.color === 'string' ? opt.color : '#c9962a', fn: opt.fn,
      });
    },
    /* ---- 玩家状态读取（被动/触发机制用） ---- */
    stats: () => ({
      x: world.x + PLAYER_X, y: player.y, vy: player.vy,
      onGround: player.onGround, sliding: player.sliding, jumps: player.jumps || 0,
      coins: G.coins, combo: G.combo, mode: G.mode, time: G.time, speed: world.speed,
    }),
    /* ---- 时停：api.timestop(秒) —— 冻结蚩尤/BOSS 与弹幕，玩家照常行动（0~10 秒） ---- */
    timestop: dur => {
      world.tstop = Math.max(world.tstop || 0, clamp(+dur || 0, 0, 10));
    },
    /* ---- 全局时间流速：api.timeScale(倍率, 秒) —— 真·子弹时间（整个游戏逻辑变速，含玩家/蚩尤/机关） ---- */
    timeScale: (mul, dur) => {
      world.tsMul = clamp(+mul || 1, 0.2, 2.5);
      world.tsT = world.tsMul === 1 ? 0 : clamp(+dur || 0, 0.1, 20);
    },
    /* ---- 直给增益：api.buff('sprint', 秒?, quiet?) —— 键见 BUFF_DEF；给秒数则覆盖默认时长 ---- */
    buff: (key, dur, quiet) => {
      if (!BUFF_DEF[key]) return;
      applyBuff(key, !!quiet);
      if (dur !== undefined && G.buff[key] > 0) G.buff[key] = clamp(+dur || 1, 0.5, 30);
    },
    /* ---- 经济：api.coin(n) 加/扣金币（负数=消费，做商店用）；api.energy(v) 加/扣能量 ---- */
    coin: n => { G.coins = Math.max(0, (G.coins || 0) + Math.round(+n || 0)); },
    energy: v => { G.energy = clamp((G.energy || 0) + (+v || 0), 0, ENERGY_MAX); },
    /* ---- 音效：api.snd('jump') —— 按名播放任意内置音效（Snd.名字；菜单探测期静默） ---- */
    snd: name => { if (!UGC_PROBING) { const f = Snd[name]; if (typeof f === 'function') f(); } },
    /* ---- 播报：api.msg('大字', '小字?') —— 屏幕中央公告（菜单探测期静默） ---- */
    msg: (text, sub) => {
      if (!text || UGC_PROBING) return;
      FX.sfloat(W / 2, 296, String(text).slice(0, 12), { color: '#ffe066', size: 40, life: 1.6 });
      if (sub) FX.sfloat(W / 2, 350, String(sub).slice(0, 20), { color: 'rgba(255,255,255,0.92)', size: 24, life: 1.6 });
    },
    /* ---- 动态投放：api.spawn('coin'|'power', opt) —— 运行时生成原版拾取物
       opt.x 世界坐标（默认玩家前方 600px）；coin 可给 opt.gy 离地高(默认104) / opt.vy 下落速(金币雨式)；
       power 可给 opt.ptype（magnet/invinc/djump/x2/sprint/shield/revive/knife/ghost/rain，默认随机） ---- */
    spawn: (type, opt) => {
      opt = opt || {};
      const wx = isFinite(opt.x) ? opt.x : world.x + PLAYER_X + 600;
      if (type === 'coin') {
        if (isFinite(opt.vy)) world.coins.push({ x: wx, y: -30, phase: rnd(0, 6.28), taken: false, vy: clamp(opt.vy, 100, 600) });
        else world.coins.push({ x: wx, y: GY - clamp(isFinite(opt.gy) ? opt.gy : 104, 20, 900), phase: rnd(0, 6.28), taken: false });
      } else if (type === 'power') {
        const ok = ['magnet', 'invinc', 'djump', 'x2', 'sprint', 'shield', 'revive', 'knife', 'ghost', 'rain'];
        world.powers.push({ x: wx, y: GY - clamp(isFinite(opt.gy) ? opt.gy : 116, 40, 900), type: ok.includes(opt.ptype) ? opt.ptype : ok[Math.floor(Math.random() * 6)], taken: false });
      }
    },
    /* ---- 蚩尤机制：gap 跟随距离(越小越凶) / speed 追速倍率 / skills 技能频率 / stun 立即踉跄秒数 ---- */
    chiyou: patch => {
      const t = world.cyTune || (world.cyTune = {});
      if (!patch || typeof patch !== 'object') return { gap: 300, speed: 1, skills: 1, ...t };
      if (patch.gap !== undefined) t.gap = clamp(patch.gap, 80, 560);
      if (patch.speed !== undefined) t.speed = clamp(patch.speed, 0.3, 3);
      if (patch.skills !== undefined) t.skills = clamp(patch.skills, 0.2, 4);
      if (patch.stun !== undefined && G.chiyou) G.chiyou.hurtT = Math.max(G.chiyou.hurtT || 0, clamp(patch.stun, 0.1, 5));
      return { gap: 300, speed: 1, skills: 1, ...t };
    },
    /* ---- 追击者切换：'chiyou'=蚩尤 / 'aj'=忍者阿坚（BOSS 战同步换人；局内覆盖，不改局外选择） ---- */
    chaser: v => {
      if (v !== 'chiyou' && v !== 'aj') return G.chaserOverride || chaserSkin();
      G.chaserOverride = v;
      if (G.chiyou) { G.chiyou.skin = v; G.chiyou.hidden = false; G.chiyou.introT = 0; G.chiyou.cloneOn = false; G.chiyou.marks = 0; G.chiyou.markT = 0; }
      return v;
    },
    dist: () => {
      const c = G.chiyou;
      return c ? Math.abs((world.x + PLAYER_X) - (world.x + c.bx + 70)) : 9999;
    },
    /* ---- 原版机关参数改造：api.tune('gap', { w: 260 })，之后生成的该类机关按新参数落地 ---- */
    tune: (type, patch) => {
      if (typeof type !== 'string' || !patch || typeof patch !== 'object') return;
      const t = world.genTune || (world.genTune = {});
      const cur = t[type] || (t[type] = {});
      for (const k in patch) {
        const v = patch[k];
        if (typeof v === 'number' && isFinite(v)) cur[k] = v;
      }
    },
    /* ---- 换当前局背景（dataURL 或图片 URL，单关优先级；菜单探测期静默） ---- */
    bg: src => {
      if (UGC_PROBING || typeof src !== 'string' || !src) return;
      UGC_BG.src = src;
      UGC_BG.img = null;
    },
    /* ---- 注册自定义 UI：api.hud(key, '文本') 或 { text/color/size } 或 { label, val, max, color } 血条 ---- */
    hud: (key, content) => {
      if (!key || content === undefined) return;
      (world.ugcHud = world.ugcHud || {})[String(key).slice(0, 12)] = content;
    },
    hazards: t => world.ugcHazards.filter(e => (t ? e.type === t : true)),
    hitPlayer: e => {
      if (!e || !isFinite(e.x) || !isFinite(e.y)) return false;
      const box = playerBox();
      const w = e.w || 40, h = e.h || 40;
      if (e.x + w / 2 < box.l || e.x - w / 2 > box.r) return false;
      if (e.y + h / 2 < box.t || e.y - h / 2 > box.b) return false;
      return true;
    },
    kill: () => die('ugc'),
    give: n => { G.coins += n; if (G.coins > G.levelCoinTotal) G.coins = G.levelCoinTotal; },
    fx: {
      burst: (x, y, n, opt) => FX.burst(x, y, n || 8, opt || {}),
      float: (x, y, txt) => FX.float(x, y - 60, txt),
    },
  };
  return api;
}

/* 预编译 mod 代码：开局一次 new Function，之后每帧直接调用已编译函数 */
function compileUgcMod(mod) {
  mod.err = 0; mod.slow = false; mod.slowCd = 0;
  const mk = hook => {
    const code = mod[hook];
    if (!code) return null;
    try { return new Function('api', '"use strict";' + code); }
    catch (e) {
      (world.ugcLog = world.ugcLog || []).push((mod.name || mod.id) + '·' + hook + ' 语法错误: ' + e.message);
      return null;
    }
  };
  mod._spawnFn = mk('onSpawn');
  mod._updateFn = mk('onUpdate');
}

/* 沙箱执行已编译的 mod 函数；报错记入日志，连错 3 次停用该 mod；
   首次报错自动把「报错上下文 + 出错代码」复制到剪贴板，方便贴回给 AI 修复 */
function runUgcFn(mod, hook, fn) {
  if (!fn) return;
  try {
    fn(buildUgcApi(mod));
  } catch (e) {
    mod.err = (mod.err || 0) + 1;
    if (mod.err <= 3) (world.ugcLog = world.ugcLog || []).push((mod.name || mod.id) + '·' + hook + ': ' + e.message);
    if (mod.err === 1) {
      const info = '我游戏里的 mod 报错了，请修复并只回我修正后的完整 JSON mod 包（结构 {"name":"包名","mods":[...]}）：\n'
        + JSON.stringify({ mod: mod.id, name: mod.name, hook, error: String((e && e.message) || e), code: String(mod[hook] || '').slice(0, UGC_MAX_CODE) });
      if (copyText(info)) FX.sfloat(W / 2, 344, '报错已复制，贴给 AI 修吧', { color: '#ffd34d', size: 26, life: 2 });
    }
    if (mod.err === 3) FX.sfloat(W / 2, 300, 'mod 报错，已停用', { color: '#ff8f7e', size: 30, life: 1.6 });
  }
}

/* mod 事件钩子分发：jump 起跳 / coin 吃金币 / death 死亡 / key 按键 / tap 点击 / draw 每帧绘制
   （list 传入时直接执行该批 {mod, fn}，按钮点击复用同一容错路径；钩子报错计入所属 mod） */
function runUgcHook(kind, arg, list) {
  const hs = list || (world.ugcHooks && world.ugcHooks[kind]);
  if (!hs || !hs.length) return;
  for (const h of hs) {
    try { h.fn(buildUgcApi(h.mod), arg); }
    catch (e) {
      if (h.mod) {
        h.mod.err = (h.mod.err || 0) + 1;
        if (h.mod.err <= 3) (world.ugcLog = world.ugcLog || []).push((h.mod.name || h.mod.id) + '·' + (list ? kind : 'on' + kind) + ': ' + e.message);
      }
    }
  }
}

/* ---------------- 菜单层/全局层注册表：mod 注册的模式·菜单按钮·菜单绘制，跨局存在 ---------------- */
const UGC_MODES = [];      // api.mode 注册的自定义模式（主菜单入口，按 key 去重覆盖）
const UGC_MENU_BTNS = [];  // api.menuButton 注册的主菜单按钮（左上角一排）
const UGC_MENU_HOOKS = []; // api.on('menu', fn) 主菜单每帧绘制钩子
let UGC_PROBING = false;   // 菜单探测期：带副作用的接口（bg/msg/snd）静默，避免改菜单背景/乱响
function ugcRegPush(arr, key, val) {
  const i = arr.findIndex(x => x.key === key);
  if (i >= 0) arr[i] = val; else arr.push(val);
}
/* 菜单探测：在干净空世界跑一遍 mod 库的 onSpawn，收走「注册类」副作用（模式/菜单按钮/菜单钩子），
   其余副作用随后 clearWorld 抹掉。探测期报错静默（不弹复制报错，菜单不是跑 mod 的地方） */
function ugcMenuProbe() {
  clearWorld();
  UGC_PROBING = true;
  const lib = ugcModsSync().slice(0, UGC_MAX_MODS);
  for (const m of lib) {
    try {
      const mod = { id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0, scope: m.scope };
      compileUgcMod(mod);
      if (mod._spawnFn) mod._spawnFn(buildUgcApi(mod));
    } catch (e) {}
  }
  UGC_PROBING = false;
  clearWorld();
}

/* ---------------- 自定义模式：mod 用 api.mode 注册、主菜单进入的玩法沙盒 ----------------
   沙盒规则：无原版机关自动生成（mod 用 api.make/api.spawn 自建内容）；蚩尤默认不来
   （mode 定义里 chase:true 才追）；onFrame 每帧驱动玩法；api.gameOver 结束本局。 */
function startCustomMode(def) {
  Snd.init();
  Snd.click();
  clearWorld();
  G.mode = 'custom';
  G.state = 'playing';
  G.customMode = def;
  G.customOver = null;
  def._err = 0;
  G.baseSpeed = BASE_SPEED;
  G.pressed = {};
  const fakeMod = { id: 'mode_' + def.id, name: def.name || def.id };
  // 模式来源 mod 的 onSpawn 在本局重跑（api.player/api.hud 等注册全部生效）
  if (def.mod) {
    world.ugcMods = [{ id: def.mod.id, name: def.mod.name, onSpawn: def.mod.onSpawn, onUpdate: def.mod.onUpdate, img: def.mod.img, err: 0, slow: false, slowCd: 0 }];
    for (const mod of world.ugcMods) compileUgcMod(mod);
    for (const mod of world.ugcMods) runUgcFn(mod, 'onSpawn', mod._spawnFn);
  }
  // 模式钩子接入事件总线（key/tap/draw/death 走统一派发，报错计入 fakeMod 带容错停用）
  const hk = world.ugcHooks || (world.ugcHooks = {});
  if (def.onKey) (hk.key = hk.key || []).push({ mod: fakeMod, fn: def.onKey });
  if (def.onTap) (hk.tap = hk.tap || []).push({ mod: fakeMod, fn: def.onTap });
  if (def.onDraw) (hk.draw = hk.draw || []).push({ mod: fakeMod, fn: def.onDraw });
  if (def.onDeath) (hk.death = hk.death || []).push({ mod: fakeMod, fn: def.onDeath });
  world._cmFrame = def.onFrame ? { mod: fakeMod, fn: def.onFrame } : null;
  applyPTuneStart();
  if (def.onStart) runUgcFn(fakeMod, 'onStart', def.onStart);
  FX.sfloat(W / 2, 260, def.name || '自定义模式', { color: '#7de08a', size: 42, life: 1.5, vy: -8 });
  Music.duck(0.2);
  Music.start();
}

/* mod 设置的开局属性（护盾/复活）在 onSpawn 跑完后落地 */
function applyPTuneStart() {
  const t = world.pTune;
  if (!t) return;
  if (t.startShield) G.shield = Math.max(G.shield || 0, t.startShield);
  if (t.revive) G.revive = Math.max(G.revive || 0, t.revive);
}

/* 每帧驱动自定义机关：清 dead、重建 mod HUD、逐 mod 跑 onUpdate，带 8ms 时间预算 */
function updateUgcMods(dt) {
  world.ugcHud = {};   // mod HUD 每帧由 onUpdate 重新注册
  world.ugcBtns = [];  // mod 主动技能按钮同理（重建式）
  const mods = world.ugcMods || [];
  if (!mods.length) return;
  const hz = world.ugcHazards || [];
  for (let i = hz.length - 1; i >= 0; i--) if (hz[i].dead) hz.splice(i, 1);
  for (const mod of mods) {
    if (!mod._updateFn) continue;
    if (mod.slow) { if ((mod.slowCd = (mod.slowCd || 0) - dt) <= 0) mod.slow = false; continue; }
    const t0 = performance.now();
    runUgcFn(mod, 'onUpdate', mod._updateFn);
    if (performance.now() - t0 > 8) {
      mod.slow = true; mod.slowCd = 1.5;
      (world.ugcLog = world.ugcLog || []).push((mod.name || mod.id) + ': 执行过慢，已限频');
    }
  }
}

function startUgcLevel(item) {
  Snd.init();
  Snd.click();
  clearWorld();
  const shell = newWorldShell();
  const endX = buildUgcPlan(item, shell);
  // 作品自己的 mod + mod 库里 scope=all 的全局 mod（按 id 去重，作品优先）
  const allLib = ugcModsSync().filter(m => m.scope === 'all').slice(0, UGC_MAX_MODS);
  const ownIds = new Set((item.mods || []).map(m => m.id));
  shell.ugcMods = (item.mods || []).map(m => ({ id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0 }))
    .concat(allLib.filter(m => !ownIds.has(m.id)).map(m => ({ id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0, scope: m.scope })));
  shell.bgAsset = item.bgAsset || null;
  // world 是全局 const：把构建好的 shell 逐属性搬进 world（与 startLevel 同款）
  for (const k in shell) world[k] = shell[k];
  // 作品 plan 顶层 chaser 字段：指定本局追击者（'chiyou'=蚩尤 / 'aj'=忍者阿坚，chase:true 关卡与 BOSS 同步生效）
  const planObj = typeof item.plan === 'string' ? (() => { try { return JSON.parse(item.plan); } catch (e) { return null; } })() : (item.plan || null);
  if (planObj && (planObj.chaser === 'aj' || planObj.chaser === 'chiyou')) G.chaserOverride = planObj.chaser;
  if (item.bgAsset) preloadUgcAssets(item);
  G.mode = 'ugc';
  G.ugcItem = item;
  G.ugcNoHit = true;
  G.levelIndex = -1;
  G.levelTitle = item.title;
  G.levelEndX = endX;
  G.levelCoinTotal = shell.coins.length;
  G.baseSpeed = item.speed;
  G.objective = null;
  world.x = 0;
  world.speed = G.baseSpeed;
  world.genCursor = endX + 2000;
  G.state = 'playing';
  G.pressed = {};
  // 预编译 mod 并执行 onSpawn：注册自定义机关（可能生成在玩家前方）
  for (const mod of world.ugcMods) {
    compileUgcMod(mod);
    runUgcFn(mod, 'onSpawn', mod._spawnFn);
  }
  applyPTuneStart();   // mod 给的开局护盾/复活次数
  Music.duck(0.2);
  Music.start();
  FX.sfloat(W / 2, 286, item.title, { color: '#d6a8ff', size: 42, life: 1.5, vy: -6 });
  FX.sfloat(W / 2, 346, '目标 · 抵达终点', { color: '#8ce8ff', size: 30, life: 1.8, vy: -6 });
}

function restartUgc() {
  if (G.ugcItem) startUgcLevel(G.ugcItem);
  else backToMenu();
}

/* UGC 终点结算：到终点 1 星 / 全程无伤 2 星 / 收 70% 金币 3 星，星级跟项目走 */
function endUgcLevel() {
  const it = G.ugcItem;
  let stars = 1;
  if (G.ugcNoHit) stars = 2;
  if (it && G.levelCoinTotal > 0 && G.coins / G.levelCoinTotal >= 0.7) stars = 3;
  if (it) {
    it.ratingStrs = { reach: Math.max(it.ratingStrs.reach, 1), noHit: Math.max(it.ratingStrs.noHit, G.ugcNoHit ? 1 : 0), coin70: Math.max(it.ratingStrs.coin70, stars >= 3 ? 1 : 0) };
    ugcSave(it).catch(() => {});
  }
  G.clearStars = stars;
  G.state = 'levelclear';
  G.clearAnim = 0;
  Snd.clear();
  FX.flashScreen('#ffffff', 0.35);
  for (let i = 0; i < 46; i++) {
    FX.sburst(rnd(60, W - 60), rnd(160, 420), 1, {
      vx0: -140, vx1: 140, vy0: -60, vy1: 160,
      life0: 0.7, life1: 1.5, size0: 5, size1: 14,
      color: ['#ffd34d', '#7de08a', '#8fc4f2', '#ff9c8a', '#ffffff'][i % 5],
    });
  }
}

function openCreative() {
  Snd.init();
  Snd.click();
  clearWorld();
  G.mode = 'endless';
  G.state = 'creative';
  G.pressed = {};
  clearTweens('all');
  ugcList().catch(() => {});
  ugcModsAll().catch(() => {});
}

/* 预加载 UGC 项目里的背景图（dataURL 或 URL），失败静默回退默认背景 */
const UGC_BG = { img: null, src: '' };
function preloadUgcAssets(item) {
  const a = (item.assets || []).find(x => x.id === item.bgAsset);
  if (!a || a.src === UGC_BG.src) return;
  UGC_BG.src = a.src;
  const im = new Image();
  im.onload = () => { UGC_BG.img = im; };
  im.onerror = () => { UGC_BG.img = null; };
  im.src = a.src;
}

/* 全局背景：创造中心设置，所有模式生效（单关背景/mod 换背景优先于它） */
const GLOBAL_BG = { img: null, src: '' };
function loadGlobalBg() {
  let src = '';
  try { src = localStorage.getItem('jiling_globalbg') || ''; } catch (e) {}
  if (!src) { GLOBAL_BG.src = ''; GLOBAL_BG.img = null; return; }
  if (src === GLOBAL_BG.src) return;
  GLOBAL_BG.src = src;
  const im = new Image();
  im.onload = () => { GLOBAL_BG.img = im; };
  im.onerror = () => { GLOBAL_BG.img = null; };
  im.src = src;
}

/* 全局材质：创造中心替换金币/标题/地面/蚩尤等贴图，全模式生效。
   localStorage 'jiling_globaltex' = { key: src }；玩家四帧不走这里（皮肤系统管）。 */
const GLOBAL_TEX = {};   // key -> 已应用的替换 Image

function globalTexMap() {
  try { return JSON.parse(localStorage.getItem('jiling_globaltex') || '{}') || {}; }
  catch (e) { return {}; }
}

/* 应用替换：场景类换 IMG+crop（原值留 _orig 供还原），蚩尤类换 BOSS_IMG */
function globalTexApply(key, im) {
  GLOBAL_TEX[key] = im;
  if (ASSETS[key]) {
    if (!ASSETS[key]._origImg) { ASSETS[key]._origImg = IMG[key]; ASSETS[key]._origCrop = ASSETS[key].crop; }
    IMG[key] = im;
    ASSETS[key].crop = { x: 0, y: 0, w: im.width, h: im.height };
  } else if (BOSS_ASSETS.indexOf(key) >= 0) {
    if (!BOSS_IMG._orig) BOSS_IMG._orig = {};
    if (!BOSS_IMG._orig[key]) BOSS_IMG._orig[key] = BOSS_IMG[key];
    BOSS_IMG[key] = im;
  }
}

function globalTexRestore(key) {
  delete GLOBAL_TEX[key];
  if (ASSETS[key] && ASSETS[key]._origImg) {
    IMG[key] = ASSETS[key]._origImg;
    ASSETS[key].crop = ASSETS[key]._origCrop;
  } else if (BOSS_ASSETS.indexOf(key) >= 0 && BOSS_IMG._orig && BOSS_IMG._orig[key]) {
    BOSS_IMG[key] = BOSS_IMG._orig[key];
  }
}

function loadGlobalTex() {
  const map = globalTexMap();
  for (const k in map) {
    (k2 => {
      const im = new Image();
      im.onload = () => globalTexApply(k2, im);
      im.onerror = () => {};
      im.src = map[k2];
    })(k);
  }
}

/* 设置/清除一个材质槽位；返回 localStorage 是否写入成功（超 5MB 会 false：本次生效但刷新丢） */
function setGlobalTex(key, src) {
  const map = globalTexMap();
  if (src) map[key] = src;
  else { delete map[key]; globalTexRestore(key); }
  let ok = true;
  try { localStorage.setItem('jiling_globaltex', JSON.stringify(map)); } catch (e) { ok = false; }
  if (src) {
    const im = new Image();
    im.onload = () => globalTexApply(key, im);
    im.onerror = () => {};
    im.src = src;
  }
  return ok;
}

function restartCurrent() {
  // 重试就是重抽，赛道和目标都换新的
  if (G.mode === 'ugc') restartUgc();
  else if (G.mode === 'level') startLevel(G.levelIndex, true);
  else if (G.mode === 'custom' && G.customMode) startCustomMode(G.customMode);
  else startEndless();
}

function nextLevel() {
  if (G.levelIndex + 1 < LEVELS.length) startLevel(G.levelIndex + 1);
  else backToMenu();
}

function backToMenu() {
  Snd.back();
  Music.duck(0.2);   // 主菜单继续放 BGM（曲库模式不再停音乐）
  clearWorld();
  G.state = 'menu';
  G.mode = 'endless';
  G.customMode = null;
  G.customOver = null;
  G.pressed = {};
  clearTweens('all');
  ugcMenuProbe();   // 回菜单重探测 mod 库：刚装/删的 mod 的菜单入口（模式/按钮/钩子）即时生效
}

function pauseGame() {
  if (G.state !== 'playing') return;
  G.state = 'paused';
  G.pressed = {};
  Music.duck(0.08);
}

function resumeGame() {
  if (G.state !== 'paused') return;
  G.state = 'playing';
  G.pressed = {};
  Music.duck(0.2);
}

function toggleSound() {
  const on = Snd.toggle();
  G.pressed = {};
  if (on) { Snd.init(); Snd.click(); Music.duck(G.state === 'paused' ? 0.08 : 0.2); }
  else Music.duck(0.08);
}

/* 音乐切歌（按钮 / M 键旁路共用）：默认 BGM → 备选 → 关，循环 */
function musicCycle() {
  ugcToast(Music.cycle(), '#8ce8ff');
}

// 跑到终点还得看目标做没做到，做不到就是挑战失败
function endLevel() {
  if (objectiveDone(G.objective)) levelClear();
  else levelFail();
}

function levelFail() {
  G.state = 'levelfail';
  G.clearAnim = 0;
  G.objDone = false;
  Snd.fail();
  Music.duck(0.1);
  FX.addShake(0.45);
  FX.flashScreen('#ff8f7e', 0.28);
}

function levelClear() {
  G.state = 'levelclear';
  G.clearAnim = 0;
  // 通关保底 1 星，金币收得多才往上加
  const rate = G.levelCoinTotal > 0 ? G.coins / G.levelCoinTotal : 1;
  const stars = rate >= 0.9 ? 3 : rate >= 0.5 ? 2 : 1;
  G.clearStars = stars;
  if (stars > G.stars[G.levelIndex]) {
    G.stars[G.levelIndex] = stars;
    saveStars();
  }
  // 通关就清掉这关缓存，下次进来重抽
  G.levelCache[G.levelIndex] = null;
  Snd.clear();
  FX.flashScreen('#ffffff', 0.35);
  for (let i = 0; i < 46; i++) {
    FX.sburst(rnd(60, W - 60), rnd(160, 420), 1, {
      vx0: -140, vx1: 140, vy0: -60, vy1: 160,
      life0: 0.7, life1: 1.5, size0: 5, size1: 14,
      color: ['#ffd34d', '#7de08a', '#8fc4f2', '#ff9c8a', '#ffffff'][i % 5],
    });
  }
}

/* ---------------- 无尽模式生成 ---------------- */
function jumpReach() {
  const air = (2 * Math.abs(JUMP_V)) / GRAVITY;
  return world.speed * air;
}

function addArcCoins(gx, gw) {
  for (let i = 0; i < 5; i++) {
    const t = (i + 0.5) / 5;
    world.coins.push({
      x: gx + gw * t,
      y: GROUND_Y - 118 - Math.sin(t * Math.PI) * 100,
      phase: i * 0.9, taken: false,
    });
  }
}

/* mod 改的原版机关参数：ugcT('gap','w',默认值)；api.tune 写入 world.genTune */
function ugcT(type, key, def) {
  const t = world.genTune && world.genTune[type];
  const v = t ? t[key] : undefined;
  return typeof v === 'number' && isFinite(v) ? v : def;
}

function spawnFeature(kind, c, dM) {
  const reach = jumpReach();
  switch (kind) {
    /* UGC 自定义机关：无尽模式从玩家 mod 库随机投放（gy 40~210，低处跳过/高处跑过） */
    case 'ugc': {
      const mod = (world.ugcMods || [])[Math.floor(Math.random() * (world.ugcMods || []).length)];
      if (!mod) return c + 200;
      const gy = rnd(40, 210);
      const x = c + clamp(world.speed * 0.5, 190, 320);
      world.ugcHazards.push({
        id: mod.id, type: mod.id, dead: false, img: mod.img || null,
        x, y: GROUND_Y - gy, w: 56, h: 56, gy,
        dx: -(world.speed * 0.25 + 60) * rnd(0.7, 1.3),
        spd: rnd(4, 9), phase: rnd(0, 6.28),
      });
      return x + 60;
    }
    case 'gap': {
      const w = clamp(ugcT('gap', 'w', reach * rnd(0.42, 0.58)), 110, 430);
      const gx = c + clamp(world.speed * 0.55, 190, 340);
      world.gaps.push({ x: gx, w: w, spikes: Math.max(2, Math.round(w / 44)) });
      world.signs.push({ x: gx - 200 });
      if (Math.random() < 0.7) addArcCoins(gx, w);
      return gx + w;
    }
    case 'coins': {
      const n = 3 + Math.floor(Math.random() * 3);
      const y = GROUND_Y - (Math.random() < 0.5 ? 116 : 98);
      const x0 = c + rnd(170, 280);
      for (let i = 0; i < n; i++) world.coins.push({ x: x0 + i * 80, y, phase: i * 1.1, taken: false });
      return x0 + (n - 1) * 80;
    }
    case 'platform': {
      const pw = rnd(172, 268);
      const px = c + rnd(220, 340);
      const top = GROUND_Y - ugcT('platform', 'h', rnd(236, 262));
      world.platforms.push({ x: px, w: pw, top, h: 70 });
      const n = Math.random() < 0.45 ? 3 : 2;
      for (let i = 0; i < n; i++) {
        world.coins.push({ x: px + pw * (0.28 + 0.44 * i / Math.max(1, n - 1)), y: top - 70, phase: i * 1.2, taken: false });
      }
      return px + pw;
    }
    case 'spikeTrap': {
      const w = ugcT('spikeTrap', 'w', rnd(96, 132));
      const x = c + clamp(world.speed * 0.5, 180, 300);
      world.spikeTraps.push({ x, w, phase: rnd(0, 2.6) });
      return x + w;
    }
    case 'fakeFloor': {
      const w = ugcT('fakeFloor', 'w', rnd(112, 152));
      const x = c + clamp(world.speed * 0.5, 180, 320);
      world.fakeFloors.push({ x, w, broken: false, t: 0 });
      return x + w;
    }
    case 'lowBar': {
      const w = ugcT('lowBar', 'w', rnd(86, 118));
      const x = c + clamp(world.speed * 0.5, 190, 320);
      world.lowBars.push({ x, w, gapH: ugcT('lowBar', 'gapH', rnd(92, 100)) });
      return x + w;
    }
    case 'ceilingPair': {
      const w = clamp(reach * rnd(0.3, 0.4), 130, 240);
      const gx = c + clamp(world.speed * 0.55, 200, 340);
      world.gaps.push({ x: gx, w: w, spikes: Math.max(2, Math.round(w / 44)) });
      world.signs.push({ x: gx - 210 });
      // 顶刺压得比满跳低：必须用中低跳钻过去，跳高了就会撞刺
      world.ceilSpikes.push({ x: gx + w * 0.30, w: Math.max(46, w * 0.26), low: ugcT('spikeCeil', 'low', rnd(300, 318)) });
      return gx + w;
    }
    case 'bouncePad': {
      const w = 134;
      const x = c + clamp(world.speed * 0.5, 200, 320);
      world.bouncePads.push({ x, w, t: -1, pop: 0 });
      const gw = clamp(world.speed * 0.8, 240, 420);
      world.gaps.push({ x: x + w - 6, w: gw, spikes: Math.max(3, Math.round(gw / 44)) });
      world.signs.push({ x: x - 120 });
      return x + w + gw;
    }
    case 'movingPlatform': {
      const pw = rnd(160, 210);
      const px = c + rnd(220, 330);
      world.movingPlatforms.push({
        x: px, w: pw, y0: GROUND_Y - rnd(228, 250), amp: rnd(34, 52), spd: rnd(1.1, 1.7), phase: rnd(0, 6.28),
      });
      for (let i = 0; i < 2; i++) {
        world.coins.push({ x: px + pw * (0.3 + 0.4 * i), y: GROUND_Y - rnd(228, 250) - 70, phase: i * 1.2, taken: false });
      }
      return px + pw;
    }
    case 'bait': {
      const gw = rnd(150, 176);
      const gx = c + clamp(world.speed * 0.55, 200, 330) + 158;
      world.gaps.push({ x: gx, w: gw, spikes: Math.max(2, Math.round(gw / 44)) });
      world.signs.push({ x: gx - 210 });
      for (let i = 0; i < 3; i++) {
        world.coins.push({ x: gx - 160 + i * 64, y: GROUND_Y - 68, phase: i * 0.8, taken: false });
      }
      addArcCoins(gx, gw);
      return gx + gw;
    }
    case 'pendulum': {
      const x = c + clamp(world.speed * 0.55, 210, 350);
      world.pendulums.push({
        x, pivotY: 96, len: ugcT('pendulum', 'len', 470), r: 42,
        amp: ugcT('pendulum', 'amp', rnd(0.56, 0.7)), spd: ugcT('pendulum', 'spd', rnd(1.0, 1.3)), phase: rnd(0, 6.28),
      });
      return x + 60;
    }
    case 'rollingRock': {
      const x = c + clamp(world.speed * 0.6, 230, 360);
      world.rollingRocks.push({ x: x + 900, y: GROUND_Y - 54, r: 54, vx: -ugcT('rollingRock', 'vx', rnd(130, 175)), spin: 0, spawnX: x, alive: true });
      return x + 200;
    }
    case 'fallingRock': {
      const x = c + clamp(world.speed * 0.55, 220, 350);
      world.fallingRocks.push({ x, y: -80, vy: 0, r: 42, triggered: false, landed: false });
      return x + 120;
    }
    case 'booster': {
      const w = 150;
      const x = c + clamp(world.speed * 0.5, 200, 320);
      world.boosters.push({ x, w, gl: 0 });
      return x + w;
    }
    case 'crate': {
      const x = c + clamp(world.speed * 0.5, 200, 320);
      // 三成概率是宝箱怪：咬人一口（断连击 + 掉金币 + 减速），看得出来的玩家能提前躲开
      const mimic = Math.random() < 0.3;
      world.crates.push({ x, y: GROUND_Y - rnd(96, 152), taken: false, ph: rnd(0, 6.28), mimic: mimic });
      return x + 130;
    }
    case 'fork': {
      /* 双路线：上面是高台链（安全、金币多，但跳空就掉进坑），
         下面留一道路障（要滑铲/跳）。让玩家自己选一条路走。 */
      const x = c + clamp(world.speed * 0.55, 230, 360);
      const n = 2 + Math.floor(Math.random() * 2);          // 2~3 段高台
      const top = GROUND_Y - rnd(238, 262);
      const pw = rnd(180, 250);
      for (let i = 0; i < n; i++) {
        const px = x + i * (pw + rnd(96, 132));
        world.platforms.push({ x: px, w: pw, top: top - (i % 2) * 22, h: 70 });
        for (let k = 0; k < 3; k++) {
          world.coins.push({ x: px + pw * (0.22 + 0.28 * k), y: top - 74, phase: k * 1.1, taken: false });
        }
      }
      // 地面路线：一段坑 + 一排刺，走下面要会跳会铲
      const gw = rnd(140, 178);
      world.gaps.push({ x: x, w: gw, spikes: Math.max(2, Math.round(gw / 44)) });
      world.signs.push({ x: x - 200 });
      world.hiddenSpikes.push({ x: x + (n * (pw + 110)) * 0.5, w: 104, t: -1 });
      return x + n * (pw + 120) + 120;
    }
    case 'enemy': {
      const x = c + clamp(world.speed * 0.5, 210, 330);
      // 三种小怪：浮空怪（可踩）/ 跳跳刺球（贴地弹跳，要跳或铲）/ 盾怪（只能冲刺撞碎）
      const r0 = Math.random();
      const kind = r0 < 0.55 ? 'float' : r0 < 0.82 ? 'hopper' : 'shield';
      if (kind === 'hopper') {
        world.enemies.push({
          kind: kind, x, y0: GROUND_Y - 34, amp: 0, spd: 1, phase: rnd(0, 6.28),
          hopT: rnd(0, 0.9), hopCd: 1.15, vy: 0, y: GROUND_Y - 34, r: 30, dead: false, deadT: 0,
        });
      } else if (kind === 'shield') {
        world.enemies.push({
          kind: kind, x, y0: GROUND_Y - 74, amp: 12, spd: 1.4, phase: rnd(0, 6.28),
          y: GROUND_Y - 74, r: 34, dead: false, deadT: 0, armor: true,
        });
      } else {
        world.enemies.push({
          kind: 'float', x, y0: GROUND_Y - rnd(142, 186), amp: rnd(28, 40), spd: rnd(0.9, 1.3),
          phase: rnd(0, 6.28), r: 26, dead: false, deadT: 0,
        });
      }
      return x + 130;
    }
    case 'fakeCoins': {
      const n = 3;
      const x0 = c + rnd(180, 280);
      for (let i = 0; i < n; i++) {
        world.fakeCoins.push({ x: x0 + i * 74, y: GROUND_Y - rnd(96, 130), phase: i * 1.4, taken: false });
      }
      return x0 + n * 74;
    }
    case 'hiddenSpike': {
      const w = ugcT('hiddenSpike', 'w', rnd(92, 118));
      const x = c + clamp(world.speed * 0.45, 170, 280);
      world.hiddenSpikes.push({ x, w, t: -1 });
      return x + w;
    }
    case 'trapSpring': {
      const w = 116;
      const x = c + clamp(world.speed * 0.5, 190, 300);
      world.trapSprings.push({ x, w, t: -1 });
      world.ceilSpikes.push({ x: x - 40, w: w + 80, low: ugcT('spikeCeil', 'low', rnd(300, 330)) });
      return x + w;
    }
    case 'crusher': {
      const w = rnd(110, 150);
      const x = c + clamp(world.speed * 0.55, 210, 340);
      world.crushers.push({
        x, w, high: GROUND_Y - 300, travel: ugcT('crusher', 'travel', 208), spd: rnd(0.9, 1.3), phase: rnd(0, 6.28),
      });
      return x + w;
    }
    case 'crumble': {
      const n = 2;
      for (let i = 0; i < n; i++) {
        world.crumbles.push({ x: c + 210 + i * 150, w: 128, top: GROUND_Y - 236, h: 62, t: -1, broken: false, fall: 0 });
      }
      return c + 210 + n * 150;
    }
    case 'chaseRock': {
      const x = c + clamp(world.speed * 0.6, 240, 380);
      if (!world.chaseTriggers) world.chaseTriggers = [];
      world.chaseTriggers.push({ x, spawned: false, rel: rnd(120, 150) });
      return x + 240;
    }
    /* 火焰喷射口：周期喷火，看预警红光跳过或趁待机跑过 */
    case 'flame': {
      const n = 2 + (Math.random() < 0.4 ? 1 : 0);
      for (let i = 0; i < n; i++) {
        world.flames.push({ x: c + 210 + i * 310, w: 52, t: Math.random() * 3.2 });
      }
      return c + 210 + (n - 1) * 310 + 90;
    }
    /* 传送带：蓝色加速 / 泥色减速 */
    case 'belt': {
      const w = rnd(270, 430);
      const x = c + 210;
      world.belts.push({ x, w, fast: Math.random() < 0.62 });
      return x + w + 40;
    }
    /* 上升气流：缓缓托起，顶部挂一串金币 */
    case 'updraft': {
      const w = rnd(130, 180);
      const x = c + 230;
      world.updrafts.push({ x, w, h: 356 });
      for (let i = 0; i < 4; i++) {
        world.coins.push({ x: x + w / 2 + rnd(-26, 26), y: GROUND_Y - 140 - i * 74, phase: i * 1.1, taken: false });
      }
      return x + w + 70;
    }
  }
  return c + 400;
}

function generateAhead() {
  const horizon = world.x + W + 860;
  let guard = 0;
  while (world.genCursor < horizon && guard++ < 40) {
    const c = world.genCursor;
    const dM = c / PX_PER_M;
    const r = Math.random();
    let end;

    if (G.boss) {
      // BOSS 战期间只出金币和箱子，场地保持干净
      end = spawnFeature(Math.random() < 0.8 ? 'coins' : 'crate', c, dM);
    } else if (dM < 40) {
      end = spawnFeature(Math.random() < 0.68 ? 'coins' : 'gap', c, dM);
    } else {
      // 机关池：稀有机关 320m 前全部解锁，金币占比压低让路给机关
      const pool = ['gap', 'coins'];
      if (dM > 40) pool.push('platform', 'gap');
      if (dM > 65) pool.push('spikeTrap', 'crate');
      if (dM > 95) pool.push('lowBar', 'belt');
      if (dM > 125) pool.push('flame', 'movingPlatform');
      if (dM > 110) pool.push('fork');
      // 小怪解锁早、权重高：它是有互动性的对手（能踩能撞），不该跟纯陷阱一样稀有
      if (dM > 130) pool.push('enemy', 'enemy');
      if (dM > 155) pool.push('updraft', 'ceilingPair', 'fakeFloor');
      if (dM > 195) pool.push('pendulum', 'bouncePad', 'bait');
      if (dM > 235) pool.push('rollingRock', 'booster', 'fakeCoins');
      if (dM > 275) pool.push('fallingRock', 'hiddenSpike', 'crumble');
      if (dM > 315) pool.push('chaseRock', 'crusher', 'trapSpring', 'enemy');
      let pick = pool[Math.floor(Math.random() * pool.length)];
      // 玩家 mod 库非空时，18% 概率投放自定义机关（60 米后解锁）
      if (dM > 60 && (world.ugcMods || []).length && Math.random() < 0.18) pick = 'ugc';
      // 陷阱大减：危险机关有一半概率被换成金币，压力主要交给蚩尤（陷阱风暴期间不稀释）
      // 小怪不参与稀释——踩它/撞它是主动玩法，不是「又一处躲不掉的地刺」
      else if (G.surgeT <= 0 && pick !== 'coins' && pick !== 'gap' && pick !== 'platform' && pick !== 'crate'
        && pick !== 'enemy' && Math.random() < 0.55) {
        pick = 'coins';
      }
      // 浓雾天：空气里飘满金币
      if (G.weather && G.weather.id === 'fog' && pick !== 'coins' && Math.random() < 0.3) pick = 'coins';
      end = spawnFeature(pick, c, dM);
    }
    const ease = 1 - Math.min(0.45, dM / 2600);
    const step = clamp(rnd(360, 540) * ease, 260, 540);
    // 陷阱风暴：机关间距压缩，密度翻倍
    world.genCursor = end + (G.surgeT > 0 ? Math.max(190, step * 0.66) : step);
  }
}

/* ---------------- BOSS 蚩尤：追逐 + 弹幕 + 变身 ---------------- */
const BOSS_ASSETS = [
  'chiyou_idle', 'chiyou_stand_wing', 'chiyou_run1', 'chiyou_run2', 'chiyou_run3',
  'chiyou_skill', 'chiyou_dash', 'chiyou_punch', 'chiyou_punch2', 'chiyou_slide',
  'chiyou_mech_run', 'chiyou_mech_run2', 'chiyou_mech_full',
  /* 忍者阿坚（第二追击者）：手里剑三连射 / 影袭突进 / 烟雾瞬步；BOSS 战=影分身 */
  'aj_idle', 'aj_run1', 'aj_run2', 'aj_throw1', 'aj_throw2', 'aj_dive', 'aj_slide', 'aj_shuriken',
  /* 阿坚专属武器·黑红魔剑：剑气斩 / 居合十字斩；剑气波弹幕 */
  'ajw_swing1', 'ajw_swing2', 'ajw_swing3', 'ajw_slash',
];
const BOSS_IMG = {};

function loadBossSprites() {
  return Promise.all(BOSS_ASSETS.map(k => new Promise(res => {
    const im = new Image();
    im.onload = () => { BOSS_IMG[k] = im; res(); };
    im.onerror = () => res();
    im.src = k + '.png';
  })));
}

/* 追击者选择（局外切换）：'chiyou' | 'aj'，localStorage 'jiling_chaser'；?aj=1 调试覆盖 */
function chaserSkin() {
  if (G.chaserOverride) return G.chaserOverride;
  try { return localStorage.getItem('jiling_chaser') === 'aj' ? 'aj' : 'chiyou'; } catch (e) { return 'chiyou'; }
}

/* BOSS 战（天天酷跑式）：场景锁定不再前进，追击者从右侧压制，
   撑过 30 秒小奖，用飞刀/冲刺砸掉全部血量大奖。
   蚩尤=弹幕+机甲变身；阿坚=影分身（真身脚下红圈是唯一弱点，打中分身只碎烟雾） */
function startBoss() {
  G.bossCount = (G.bossCount || 0) + 1;
  const hp = 50 + (G.bossCount - 1) * 30;
  const skin = chaserSkin();
  G.boss = {
    hp: hp, maxHp: hp, mech: false, skin: skin,
    realDx: 0, swapT: 3.2,
    clones: skin === 'aj'
      ? [{ dx: -170, real: false, dead: 0 }, { dx: 0, real: true, dead: 0 }, { dx: 170, real: false, dead: 0 }]
      : null,
    bx: -260, by: GROUND_Y,
    state: 'enter', t: 0, fightT: 0, atkCd: 1.6, frame: 0,
    knifeCd: 0.5, spawned: false,
  };
  // 只清弹幕，机关照常生成——边跑酷边对决
  world.bullets = [];
  // 开场送一把飞刀，保证每场都有攻击手段
  G.knives = (G.knives || 0) + 4;
  FX.sfloat(W / 2, 346, '获得飞刀 ×4', { color: '#8ce8ff', size: 30, life: 1.6, vy: -8 });
  Snd.whoosh();
  FX.addShake(0.6);
  FX.flashScreen(skin === 'aj' ? '#b06aff' : '#ff5a4a', 0.3);
  FX.sfloat(W / 2, 236, skin === 'aj' ? '阿坚 影分身拦住去路！' : '蚩尤 拦住去路！', { color: skin === 'aj' ? '#d3a8ff' : '#ff8f7e', size: 44, life: 1.7, vy: -10 });
  Music.duck(0.15);
}

function bossFire(pattern) {
  const b = G.boss;
  const ox = b.bx + (b.realDx || 0) + 84, oy = GROUND_Y - 128;
  const px = PLAYER_X + 8, py = player.y - 66;
  const aj = b.skin === 'aj';   // 阿坚的弹幕画成旋转手里剑
  const fire = (ang, spd, r) => {
    world.bullets.push({ x: ox, y: oy, vx: Math.cos(ang) * spd, vy: Math.sin(ang) * spd, r: r || 12, sp: aj ? 1 : 0, spin: 0 });
  };
  const base = Math.atan2(py - oy, px - ox);
  if (pattern === 'fan') {
    const n = b.mech ? 5 : 3;
    for (let i = 0; i < n; i++) fire(base + (i - (n - 1) / 2) * 0.22, b.mech ? 235 : 185);
  } else if (pattern === 'ring') {
    const n = b.mech ? 10 : 7;
    for (let i = 0; i < n; i++) fire(i * 6.283 / n, 150);
  } else if (pattern === 'big') {
    // 阿坚：巨型手里剑，慢速大弹擦身才中
    fire(base, 285, 23);
    if (b.mech) fire(base + 0.2, 285, 23);
  } else if (pattern === 'rain') {
    // 阿坚狂化：手里剑雨从天而降（y 压在过滤界内）
    for (let i = 0; i < 5; i++) {
      world.bullets.push({ x: rnd(PLAYER_X - 80, W - 60), y: -30 - i * 8, vx: 0, vy: 370 + i * 24, r: 13, sp: 1, spin: Math.random() * 6, ground: 1 });
    }
  } else if (pattern === 'slash') {
    // 阿坚：贴地剑气波推进（跳过可躲），狂化后三连
    const n = b.mech ? 3 : 1;
    for (let i = 0; i < n; i++) {
      world.bullets.push({ x: ox + i * 70, y: GROUND_Y - 52, vx: -(295 + i * 65), vy: 0, r: 16, sp: 1, spin: 0, wave: 1 });
    }
  } else {
    fire(base, b.mech ? 305 : 245, 10);
    if (b.mech) fire(base + 0.14, 305, 10);
  }
  Snd.whoosh();
}

function bossDamage(n) {
  const b = G.boss;
  if (!b || b.state === 'transform' || b.state === 'dead' || b.state === 'retreat') return;
  b.hp = Math.max(0, b.hp - n);
  FX.float(b.bx + (b.realDx || 0) + 70, GROUND_Y - 240, '-' + n, { color: '#ffd34d', size: 34 });
  FX.burst(b.bx + (b.realDx || 0) + 70, GROUND_Y - 190, 20, {
    vx0: -320, vx1: 320, vy0: -320, vy1: 100,
    life0: 0.3, life1: 0.6, size0: 6, size1: 16,
    color: ['#ffd34d', '#ff9a5a', '#ffffff'][Math.floor(Math.random() * 3)],
  });
  FX.addShake(0.5);
  if (b.hp <= 0) {
    b.state = 'dead';
    b.t = 0;
    for (let i = 0; i < 40; i++) {
      world.coins.push({ x: world.x + rnd(240, W - 60), y: GROUND_Y - rnd(70, 320), phase: rnd(0, 6.28), taken: false });
    }
    G.energy = ENERGY_MAX;
    FX.flashScreen('#ffd34d', 0.5);
    FX.sfloat(W / 2, 300, b.skin === 'aj' ? '阿坚 被击退！' : '蚩尤 被击退！', { color: '#ffe066', size: 54, life: 1.7, vy: -10 });
    Snd.clear();
    return;
  }
  // 半血变身：蚩尤开机甲，阿坚残影狂化（换位加速 + 弹幕升级）
  if (b.hp <= b.maxHp / 2 && !b.mech) {
    b.mech = true;
    b.state = 'transform';
    b.t = 0;
    world.bullets = [];
    FX.flashScreen(b.skin === 'aj' ? '#ff7ab8' : '#8ce8ff', 0.4);
    FX.sfloat(W / 2, 256, b.skin === 'aj' ? '阿坚 残影狂化！' : '蚩尤 变身机甲！', { color: b.skin === 'aj' ? '#ff9ac2' : '#8ce8ff', size: 42, life: 1.6, vy: -8 });
    Snd.dash();
  }
}

function updateBoss(dt) {
  const b = G.boss;
  if (!b) return;
  b.t += dt;
  b.frame += dt;
  const aj = b.skin === 'aj';

  /* 阿坚影分身：分身重生计时 + 战斗中全员烟雾换位（真身脚下红圈是唯一破绽） */
  if (aj && b.clones) {
    for (const cl of b.clones) {
      if (cl.dead > 0) {
        cl.dead -= dt;
        if (cl.dead <= 0) {
          FX.burst(b.bx + cl.dx + 84, GROUND_Y - 110, 10, {
            vx0: -140, vx1: 140, vy0: -180, vy1: 20, life0: 0.25, life1: 0.5, size0: 6, size1: 15, color: 'rgba(176,106,255,0.8)',
          });
        }
      }
    }
    if (b.state === 'fight' || b.state === 'mech') {
      b.swapT -= dt;
      if (b.swapT <= 0) {
        b.swapT = b.mech ? 2.1 : 3.2;
        const slots = [-170, 0, 170];
        for (let i = slots.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); const tmp = slots[i]; slots[i] = slots[j]; slots[j] = tmp; }
        for (let i = 0; i < b.clones.length; i++) {
          const cl = b.clones[i];
          if (cl.dead <= 0) {
            FX.burst(b.bx + cl.dx + 84, GROUND_Y - 110, 8, {
              vx0: -120, vx1: 120, vy0: -160, vy1: 20, life0: 0.2, life1: 0.45, size0: 5, size1: 13, color: 'rgba(200,160,255,0.75)',
            });
          }
          cl.dx = slots[i];
          if (cl.dead <= 0) {
            FX.burst(b.bx + cl.dx + 84, GROUND_Y - 110, 8, {
              vx0: -120, vx1: 120, vy0: -160, vy1: 20, life0: 0.2, life1: 0.45, size0: 5, size1: 13, color: 'rgba(200,160,255,0.75)',
            });
          }
        }
        b.realDx = (b.clones.find(cl => cl.real) || {}).dx || 0;
        Snd.dash();
      }
    }
  }

  if (b.state === 'enter') {
    // 从左后方追上来，玩家始终跑在他前面
    b.bx += ((PLAYER_X - 320) - b.bx) * Math.min(1, dt * 1.1);
    if (b.t > 2) { b.state = 'fight'; b.t = 0; }
  } else if (b.state === 'fight' || b.state === 'mech') {
    b.fightT += dt;
    // 咬死在玩家身后 320px：大屏全身可见，窄屏露半身，永远不许越到玩家前面
    const target = Math.max(PLAYER_X - 320, -150);
    b.bx += (target - b.bx) * Math.min(1, dt * 0.5);
    b.atkCd -= dt;
    if (b.atkCd <= 0) {
      /* 招式池随场次扩展：第 n 场解锁 n 个招式，且一半概率直接亮出最新解锁的。
         阿坚专属池：扇形/贯穿换成巨型手里剑 + 狂化手里剑雨 */
      const POOL = aj
        ? ['fan', 'big', 'slash', 'direct', 'dashAtk', 'slideAtk', 'rain', 'chainAtk']
        : ['fan', 'ring', 'direct', 'dashAtk', 'slideAtk', 'quakeAtk', 'chainAtk'];
      const NAMES = aj
        ? { fan: '扇形剑幕', ring: '环形剑幕', big: '巨型手里剑', slash: '贴地剑气波', direct: '贯穿剑', dashAtk: '居合突进斩', slideAtk: '贴地滑行', rain: '手里剑雨', chainAtk: '旋转链锤', quakeAtk: '裂地波' }
        : { fan: '扇形弹幕', ring: '环形弹幕', direct: '贯穿弹', dashAtk: '突进爪击', slideAtk: '贴地滑行', quakeAtk: '裂地波', chainAtk: '旋转链锤' };
      const n = Math.min(POOL.length, 1 + (G.bossCount || 1));
      const showNew = n <= POOL.length && Math.random() < 0.5;
      let pick = showNew ? POOL[n - 1] : POOL[Math.floor(Math.random() * n)];
      if (pick === 'rain' && !b.mech) pick = 'fan';   // 手里剑雨是狂化后限定
      if (showNew && !b.usedNew) {
        b.usedNew = true;
        FX.sfloat(W / 2, 268, '新技能 · ' + NAMES[pick], { color: aj ? '#d3a8ff' : '#ff9a8a', size: 36, life: 1.5, vy: -8 });
        FX.flashScreen(aj ? '#b06aff' : '#ff5a4a', 0.12);
      }
      if (pick === 'fan') { bossFire('fan'); b.atkCd = b.mech ? rnd(0.9, 1.3) : rnd(1.2, 1.7); }
      else if (pick === 'ring') { bossFire('ring'); b.atkCd = b.mech ? rnd(1.0, 1.4) : rnd(1.4, 1.9); }
      else if (pick === 'direct') { bossFire('direct'); b.atkCd = b.mech ? rnd(0.8, 1.2) : rnd(1.1, 1.6); }
      else if (pick === 'big') { bossFire('big'); b.atkCd = b.mech ? rnd(1.1, 1.5) : rnd(1.5, 2.0); }
      else if (pick === 'rain') { bossFire('rain'); b.atkCd = rnd(1.4, 1.9); }
      else if (pick === 'dashAtk') { b.state = 'dashAtk'; b.t = 0; }
      else if (pick === 'slideAtk') { b.state = 'slideAtk'; b.t = 0; }
      else if (pick === 'quakeAtk') { b.state = 'quakeAtk'; b.t = 0; b.spawned = false; }
      else { b.state = 'chainAtk'; b.t = 0; b.spawned = false; }
    }
    if (b.fightT > 30) {
      b.state = 'retreat';
      for (let i = 0; i < 16; i++) {
        world.coins.push({ x: world.x + rnd(260, 490), y: GROUND_Y - rnd(80, 240), phase: rnd(0, 6.28), taken: false });
      }
      G.energy = Math.min(ENERGY_MAX, G.energy + 40);
      FX.sfloat(W / 2, 320, aj ? '阿坚 暂退了' : '蚩尤 暂退了', { color: '#8ce8ff', size: 40, life: 1.4, vy: -8 });
      Snd.clear();
    }
  } else if (b.state === 'transform') {
    if (b.t > 1.5) { b.state = 'mech'; b.t = 0; b.atkCd = 1.0; }
  } else if (b.state === 'quakeAtk') {
    // 跺地放裂地波，波会追着玩家跑
    if (b.t > 0.45 && !b.spawned) {
      b.spawned = true;
      world.quakes.push({ x: world.x + b.bx + 100, v: world.speed + 300, t: 0 });
      FX.addShake(0.42);
      Snd.crack();
    }
    if (b.t > 1.0) { b.state = b.mech ? 'mech' : 'fight'; b.t = 0; b.atkCd = rnd(1.2, 1.8); }
  } else if (b.state === 'chainAtk') {
    // 甩出旋转链锤贴地扫过来，跳起就安全
    if (b.t > 0.4 && !b.spawned) {
      b.spawned = true;
      world.chains.push({ x0: world.x + b.bx + 100, len: 0, maxLen: 660, t: 0 });
      Snd.whoosh();
    }
    if (b.t > 1.3) { b.state = b.mech ? 'mech' : 'fight'; b.t = 0; b.atkCd = rnd(1.2, 1.8); }
  } else if (b.state === 'dashAtk') {
    // 从身后猛冲到玩家脚边出拳——爪子向前够，但身体绝不越过玩家
    const rdx = b.realDx || 0;
    if (b.t < 0.4) {
      b.bx += ((PLAYER_X - 92 - rdx) - b.bx) * Math.min(1, dt * 9);
    } else if (b.t < 0.78) {
      b.bx += ((PLAYER_X - 74 - rdx) - b.bx) * Math.min(1, dt * 4);
      if (b.bx + rdx + 172 > PLAYER_X) { die('boss'); return; }
    } else {
      b.bx -= 330 * dt;
      if (b.t > 1.4) { b.state = b.mech ? 'mech' : 'fight'; b.t = 0; b.atkCd = rnd(1.2, 1.8); }
    }
  } else if (b.state === 'slideAtk') {
    // 贴地滑铲从后面扫上来，跳起来就安全
    const rdx = b.realDx || 0;
    if (b.t < 0.42) {
      b.bx += ((PLAYER_X - 78 - rdx) - b.bx) * Math.min(1, dt * 8);
    } else if (b.t < 0.92) {
      b.bx -= 70 * dt;
      if (b.bx + rdx + 152 > PLAYER_X - 14 && player.y > GROUND_Y - 98) { die('boss'); return; }
    } else {
      b.bx -= 350 * dt;
      if (b.t > 1.5) { b.state = b.mech ? 'mech' : 'fight'; b.t = 0; b.atkCd = rnd(1.2, 1.8); }
    }
  } else if (b.state === 'dead' || b.state === 'retreat') {
    b.bx -= 300 * dt;   // 向左退出屏幕
    if (b.state === 'dead' && Math.random() < 0.35) {
      FX.burst(b.bx + (b.realDx || 0) + 70, GROUND_Y - 140, 4, {
        vx0: -260, vx1: 260, vy0: -260, vy1: 80,
        life0: 0.3, life1: 0.6, size0: 6, size1: 15,
        color: ['#ff9a5a', '#ffd34d', '#ffffff'][Math.floor(Math.random() * 3)],
      });
    }
    if (b.bx < -520) {
      G.boss = null;   // 完全退出屏幕后才恢复世界滚动
      // BOSS 战期间世界照常滚动（30s+ 能跑出 200m+），下一场里程碑会被甩在身后导致刚结束就无缝连开
      // → 结束时把下一场推到当前距离之外，至少再跑 130m
      if (G.mode === 'endless') G.nextBossM = Math.max(G.nextBossM || 200, Math.ceil(world.x / PX_PER_M) + 130);
    }
  }

  /* 飞刀自动投掷：向身后掷向追兵，命中的伤害结算在刀更新里 */
  if (b.state !== 'enter' || b.t > 1.4) {
    b.knifeCd -= dt;
    if ((G.knives || 0) > 0 && b.knifeCd <= 0) {
      G.knives--;
      b.knifeCd = 0.55;
      world.knives.push({ x: PLAYER_X - 22, y: player.y - 74, vx: -620, spin: 0 });
      Snd.star(1);
    }
  }
  // 飞刀飞行与命中：蚩尤打本体；阿坚要辨认影分身——真身掉血，分身只碎烟雾
  for (const kn of world.knives) {
    kn.x += kn.vx * dt;
    kn.spin -= dt * 18;
    if (aj && b.clones) {
      for (const cl of b.clones) {
        if (cl.dead > 0) continue;
        const cx = b.bx + cl.dx;
        if (kn.x > cx - 30 && kn.x < cx + 160 && kn.y > GROUND_Y - 220) {
          kn.dead = true;
          if (cl.real) bossDamage(5);
          else {
            cl.dead = 2;
            FX.burst(cx + 84, GROUND_Y - 120, 14, {
              vx0: -180, vx1: 180, vy0: -220, vy1: 40, life0: 0.25, life1: 0.5, size0: 6, size1: 16, color: 'rgba(176,106,255,0.8)',
            });
            FX.float(cx + 70, GROUND_Y - 260, '是影子！', { color: '#d3a8ff', size: 24 });
            // 碎影奖励：真身被识破会迟滞，下次攻击延后
            b.atkCd += 1.3;
            FX.float(b.bx + (b.realDx || 0) + 70, GROUND_Y - 300, '真身迟滞！', { color: '#8ce8ff', size: 20 });
          }
          break;
        }
      }
    } else if (kn.x > b.bx - 30 && kn.x < b.bx + 160 && kn.y > GROUND_Y - 220) {
      kn.dead = true;
      bossDamage(5);
    }
  }
  world.knives = world.knives.filter(kn => !kn.dead && kn.x > -80);

  // 能量冲刺撞 BOSS：贴上去每 0.45 秒撞一下（只算真身）
  b.dashHitCd = Math.max(0, (b.dashHitCd || 0) - dt);
  if (G.dashT > 0 && b.dashHitCd <= 0 && Math.abs(b.bx + (b.realDx || 0) + 90 - (PLAYER_X + 34)) < 130) {
    b.dashHitCd = 0.45;
    bossDamage(8);
    FX.addShake(0.3);
  }

  // 弹幕（屏幕坐标系）：阿坚的手里剑额外旋转；剑雨落地即碎
  for (const bl of world.bullets) {
    bl.x += bl.vx * dt;
    bl.y += bl.vy * dt;
    if (bl.sp) bl.spin += dt * 11;
    if (bl.ground && bl.y > GROUND_Y - 8) {
      bl.dead = true;
      FX.burst(bl.x, GROUND_Y - 20, 6, { vx0: -120, vx1: 120, vy0: -140, vy1: 20, life0: 0.2, life1: 0.4, size0: 4, size1: 10, color: 'rgba(200,160,255,0.8)' });
    }
  }
  world.bullets = world.bullets.filter(bl => !bl.dead);
  world.bullets = world.bullets.filter(bl => bl.x > -80 && bl.x < W + 80 && bl.y > -80 && bl.y < H + 80);

  // 弹幕命中
  const pb = { l: PLAYER_X - 30, r: PLAYER_X + 30, t: player.y - 122, b: player.y };
  for (const bl of world.bullets) {
    const nx = clamp(bl.x, pb.l, pb.r);
    const ny = clamp(bl.y, pb.t, pb.b);
    const dx = bl.x - nx, dy = bl.y - ny;
    if (dx * dx + dy * dy < bl.r * bl.r) { die('bullet'); break; }
  }
}

/* 甩掉追击者：他退场一段，玩家拿到喘息与奖励（无尽模式的「以跑代打」） */
function shakeOffChaser(c) {
  const name = c.skin === 'aj' ? '阿坚' : '蚩尤';
  c.lost = true;
  c.lostT = rnd(13, 17);
  c.lostWarn = false;
  c.hidden = true;
  c.escape = 0;
  c.skillT = -1;
  c.sliding = false;
  c.slideT = 0;
  c.y = GROUND_Y;
  c.vy = 0;
  c.onGround = true;
  c.bx = -420;
  G.escapeN = (G.escapeN || 0) + 1;
  G.escapeFlash = 2.6;
  const bonus = 40 + G.escapeN * 20;
  G.coins += bonus;
  G.energy = Math.min(ENERGY_MAX, G.energy + 50);
  FX.flashScreen('#8ce8ff', 0.3);
  FX.addShake(0.35);
  FX.sfloat(W / 2, 248, '甩掉了！', { color: '#8ce8ff', size: 56, life: 1.9, vy: -12 });
  FX.sfloat(W / 2, 312, name + '被甩在身后  +' + bonus + ' 金币 · 能量 +50',
    { color: '#ffe066', size: 28, life: 2.0, vy: -9 });
  FX.burst(world.x + PLAYER_X, player.y - 90, 30, {
    vx0: -320, vx1: 320, vy0: -300, vy1: 60,
    life0: 0.35, life1: 0.8, size0: 5, size1: 13, color: 'rgba(140,232,255,0.95)',
  });
  Snd.clear();
}

/* 常态蚩尤：无尽模式里缀在身后，玩家失误就逼近；跑得好可以把他甩掉。
   三种技能轮换：改陷阱 / 掷骨刺 / 突进爪击 */
function updateChiyou(dt) {
  let c = G.chiyou;
  if (!c) {
    /* 追击者由局外选择：蚩尤 / 忍者阿坚（localStorage 'jiling_chaser'） */
    const skin = chaserSkin();
    c = G.chiyou = {
      skin: skin,
      bx: -230, t: 0, skillCd: rnd(4, 7), skillT: -1, spawned: false, warnKind: '', kind: 'trap', hitDone: false,
      /* 他自己的身体：真跳跃/滑铲物理，会被机关绊到 */
      y: GROUND_Y, vy: 0, onGround: true, sliding: false, slideT: 0, hurtT: 0,
      phase: 0, starsLeft: 0, starT: 0, bigDone: false,
      marks: 0, markT: 0,           // 忍印：随时间积攒，满 3 层放大招「影分身之术」
      cloneOn: false, cloneT: 0, cloneBx: 0, cloneStar: 0,   // 影分身活动状态
      /* 追击松紧：escape = 玩家脱身进度(0~1)，aggro = 他的压迫值(0~1)；
         跑得好他能被甩掉，失误就被重新咬住 */
      escape: 0, aggro: 0.35, lost: false, lostT: 0, lostWarn: false,
    };
    if (skin === 'aj') {
      // 忍者烟雾登场：藏 0.4 秒后现身
      c.hidden = true;
      c.introT = 0.4;
      FX.burst(96, GROUND_Y - 110, 16, {
        vx0: -160, vx1: 160, vy0: -200, vy1: 30, life0: 0.3, life1: 0.6, size0: 7, size1: 17, color: 'rgba(200,160,255,0.8)',
      });
      FX.sfloat(W / 2, 296, '忍者阿坚 现身！', { color: '#d3a8ff', size: 38, life: 1.5, vy: -9 });
      Snd.dash();
    }
  }
  c.t += dt;
  if (c.hurtT > 0) c.hurtT -= dt;
  /* 被甩掉后：他在后面重整旗鼓，玩家拿到一小段没有追击者的窗口 */
  if (c.lost) {
    c.lostT -= dt;
    if (c.lostT <= 3.2 && !c.lostWarn) {
      c.lostWarn = true;
      FX.sfloat(W / 2, 262, (c.skin === 'aj' ? '阿坚' : '蚩尤') + ' 又追上来了…',
        { color: '#ff9a8a', size: 32, life: 1.6, vy: -8 });
      Snd.whoosh();
    }
    if (c.lostT <= 0) {
      c.lost = false;
      c.lostWarn = false;
      c.hidden = false;
      c.bx = -260; c.escape = 0.2; c.aggro = 0.8;
      c.skillCd = rnd(2.6, 4.2); c.skillT = -1;
      c.y = GROUND_Y; c.vy = 0; c.onGround = true; c.sliding = false; c.waitT = 0;
      FX.burst(world.x + 70, GROUND_Y - 110, 14, {
        vx0: -150, vx1: 150, vy0: -190, vy1: 20, life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(255,150,120,0.8)',
      });
    }
  }
  // 烟雾登场计时：到点现身
  if (c.introT > 0) {
    c.introT -= dt;
    if (c.introT <= 0) {
      c.introT = 0;
      c.hidden = false;
      FX.burst(c.bx + 70, GROUND_Y - 110, 12, {
        vx0: -140, vx1: 140, vy0: -170, vy1: 20, life0: 0.25, life1: 0.5, size0: 6, size1: 15, color: 'rgba(200,160,255,0.8)',
      });
    }
  }

  if (!c.hidden) {
  /* ---- 蚩尤 AI：像玩家一样观察前方机关，跳坑跳刺、滑铲钻杆 ---- */
  const cwx = world.x + c.bx + 70;   // 蚩尤身体中心（世界坐标）
  const look = cwx + 115;            // 前视点
  c.slideT = Math.max(0, c.slideT - dt);
  if (c.sliding && c.slideT <= 0) c.sliding = false;

  if (c.onGround && c.hurtT <= 0) {
    let jumpPow = 0, wantSlide = false;
    // 顶刺 → 收着力矮跳钻过去
    for (const cs of world.ceilSpikes) {
      if (look > cs.x - 125 && look < cs.x + cs.w + 24) { jumpPow = 0.72; break; }
    }
    // 低横杆 → 滑铲
    if (!jumpPow) for (const lb of world.lowBars) {
      if (look > lb.x - 105 && look < lb.x + lb.w) { wantSlide = true; break; }
    }
    // 坑 → 满跳
    if (!jumpPow && !wantSlide) for (const g of world.gaps) {
      if (look > g.x - 62 && look < g.x + g.w + 24) { jumpPow = 1; break; }
    }
    // 伸缩刺 / 隐藏刺 → 跳
    if (!jumpPow && !wantSlide) for (const tr of world.spikeTraps) {
      if (spikeTrapHeight(tr) > 0.2 && look > tr.x - 44 && look < tr.x + tr.w + 18) { jumpPow = 1; break; }
    }
    if (!jumpPow && !wantSlide) for (const hs of world.hiddenSpikes) {
      if (look > hs.x - 74 && look < hs.x + hs.w + 18) { jumpPow = 1; break; }
    }
    // 喷火中的喷口 → 跳
    if (!jumpPow && !wantSlide) for (const f of world.flames) {
      if (f.t >= 1.9 && look > f.x - 105 && look < f.x + f.w + 18) { jumpPow = 1; break; }
    }
    // 骨刺坑 → 跳
    if (!jumpPow && !wantSlide) for (const p of world.firePits) {
      if (p.t < 0.5 && look > p.x - 115 && look < p.x + 62) { jumpPow = 1; break; }
    }
    // 落地的碎石堆 / 滚石 → 跳
    if (!jumpPow && !wantSlide) for (const fr of world.fallingRocks) {
      if (fr.landed && !fr.gone && look > fr.x - 85 && look < fr.x + 55) { jumpPow = 1; break; }
    }
    if (!jumpPow && !wantSlide) for (const rk of world.rollingRocks) {
      if (!rk.alive) continue;
      const d = rk.x - cwx;
      if (d > -140 && d < 225) { jumpPow = 1; break; }
    }
    if (jumpPow > 0) {
      c.vy = JUMP_V * jumpPow;
      c.onGround = false;
      c.sliding = false;
      FX.burst(world.x + c.bx + 70, GROUND_Y - 6, 4, {
        vx0: -120, vx1: 120, vy0: -70, vy1: 20,
        life0: 0.2, life1: 0.4, size0: 4, size1: 9, color: 'rgba(200,190,170,0.8)',
      });
    } else if (wantSlide) {
      c.sliding = true;
      c.slideT = 0.52;
    }
  }

  /* ---- 蚩尤物理 ---- */
  if (!c.onGround) {
    c.vy += GRAVITY * dt;
    c.y += c.vy * dt;
    if (c.y >= GROUND_Y) { c.y = GROUND_Y; c.vy = 0; c.onGround = true; }
  }

  /* ---- 蚩尤被机关绊到：击退 + 踉跄（他不是无敌的）---- */
  if (c.hurtT <= 0) {
    const headTop = c.sliding ? c.y - 86 : c.y - 132;
    let trapHit = false;
    // 贴地撞进坑
    if (!trapHit) for (const g of world.gaps) {
      if (c.onGround && cwx > g.x + 14 && cwx < g.x + g.w - 14 && c.y > GROUND_Y - 26) { trapHit = true; break; }
    }
    // 站着撞低横杆（滑铲能过）
    if (!trapHit) for (const lb of world.lowBars) {
      if (cwx > lb.x && cwx < lb.x + lb.w && !c.sliding && c.onGround) { trapHit = true; break; }
    }
    // 各类刺：贴地/低空经过就中
    if (!trapHit) for (const tr of world.spikeTraps) {
      if (spikeTrapHeight(tr) > 0.45 && cwx > tr.x + 6 && cwx < tr.x + tr.w - 6 && c.y > GROUND_Y - 56) { trapHit = true; break; }
    }
    if (!trapHit) for (const hs of world.hiddenSpikes) {
      if (cwx > hs.x + 6 && cwx < hs.x + hs.w - 6 && c.y > GROUND_Y - 60) { trapHit = true; break; }
    }
    if (!trapHit) for (const f of world.flames) {
      if (f.t >= 2.25 && f.t < 3.1 && cwx > f.x && cwx < f.x + f.w && c.y > GROUND_Y - 150) { trapHit = true; break; }
    }
    if (!trapHit) for (const p of world.firePits) {
      if (p.t > 0.05 && p.t < 0.55 && Math.abs(cwx - p.x) < 52 && c.y > GROUND_Y - 122) { trapHit = true; break; }
    }
    if (trapHit) {
      c.bx -= 135;              // 被弹开一段
      c.hurtT = 0.6;
      c.sliding = false;
      c.vy = -420;
      c.onGround = false;
      Snd.crack();
      FX.burst(world.x + c.bx + 70, c.y - 90, 8, {
        vx0: -220, vx1: 120, vy0: -240, vy1: -30,
        life0: 0.25, life1: 0.5, size0: 5, size1: 11, color: 'rgba(255,150,120,0.9)',
      });
      FX.float(world.x + c.bx + 60, c.y - 210, '被绊了！', { color: '#ffb08a', size: 22 });
      // 摔得够呛，掉一个道具
      if (Math.random() < 0.5) {
        const r = Math.random();
        const type = r < 0.14 ? 'revive' : r < 0.44 ? 'ghost' : r < 0.66 ? 'rain'
          : ['magnet', 'x2', 'sprint', 'invinc'][Math.floor(Math.random() * 4)];
        world.powers.push({ x: world.x + c.bx + 90, y: GROUND_Y - 116, type, taken: false });
        FX.float(world.x + c.bx + 80, GROUND_Y - 210, '掉了道具！', { color: '#ffe066', size: 22 });
      }
    }
  }

  /* ---- 追击松紧：跑得好就能拉开距离，失误就被压上来（他是能甩掉的） ----
     escape 涨满 + 他已经被甩到身后 → 脱身成功，退场一段；
     被减速/踩酸雨/被打断 → escape 掉、aggro 涨，他立刻逼近。
     mod 可改：cyTune.gap 基准跟随距离 / cyTune.speed 追速 / cyTune.skills 技能频率 */
  const cyGap = (world.cyTune && world.cyTune.gap) || 300;
  const cySpd = (world.cyTune && world.cyTune.speed) || 1;
  const clean = c.hurtT <= 0 && !(c.waitT > 0);
  const fast = world.speed > BASE_SPEED * 1.12 || G.dashT > 0 || G.buff.sprint > 0;
  const winded = (G.beltMul && G.beltMul < 1) || G.acidUnder || (G.weather && G.weather.id === 'sand');
  if (clean && fast && !winded) {
    c.escape = clamp((c.escape || 0) + dt * (0.08 + Math.min(0.05, G.combo * 0.0015)), 0, 1);
  } else if (winded) {
    c.escape = clamp((c.escape || 0) - dt * 0.14, 0, 1);
  } else {
    c.escape = clamp((c.escape || 0) - dt * 0.035, 0, 1);
  }
  c.aggro = clamp((c.aggro || 0.35) + dt * ((c.escape || 0) < 0.45 ? 0.07 : -0.14), 0, 1);
  // 追赶目标距离：脱身进度把他往后推，压迫值把他往前拉（最多拉到基准的 2.6 倍）
  const gap = clamp(cyGap * (1 + (c.escape || 0) * 1.7 - c.aggro * 0.3), 80, cyGap * 2.6);
  c.bx += ((PLAYER_X - gap) - c.bx) * Math.min(1, dt * 0.6 * cySpd * (1 + c.aggro * 0.6));
  // 强制规则：非技能状态下不许贴脸/重叠——距离过近就停在原地，等玩家跑过去（技能爪击突进不受此限）
  if (c.skillT < 0 && PLAYER_X - c.bx < 100) {
    c.bx = PLAYER_X - 100;
    c.waitT = (c.waitT || 0) + dt;
  } else if (c.waitT > 0) c.waitT = 0;
  // 脱身达成：他已经彻底落在身后 → 甩掉！
  if ((c.escape || 0) >= 1 && c.bx < -140 && c.skillT < 0) shakeOffChaser(c);
  // 追逐尘土：落地奔跑时才有（停下等待时不扬尘）
  if (c.onGround && c.hurtT <= 0 && !(c.waitT > 0) && Math.random() < 0.35) {
    FX.burst(c.bx + 60 + rnd(-30, 40), c.y - rnd(4, 26), 1, {
      vx0: -160, vx1: -60, vy0: -90, vy1: -10,
      life0: 0.25, life1: 0.5, size0: 4, size1: 10, color: 'rgba(196,188,172,0.8)',
    });
  }
  }   // end if (!c.hidden)：烟雾瞬步/登场期间不做 AI、物理、绊倒与跟随

  // 空中骨刺：抛物线飞向玩家前方，落地炸出地刺
  if (!world.fireballs) world.fireballs = [];
  for (const fb of world.fireballs) {
    fb.t += dt;
    if (Math.random() < 0.6) {
      const sx = fb.wx0 + (fb.wx1 - fb.wx0) * (fb.t / fb.dur) - world.x;
      const sy = GROUND_Y - 200 - Math.sin((fb.t / fb.dur) * Math.PI) * 265;
      FX.burst(sx, sy, 1, { vx0: -20, vx1: 20, vy0: -30, vy1: 30, life0: 0.2, life1: 0.4, size0: 3, size1: 6, color: 'rgba(240,226,196,0.8)' });
    }
  }
  const landed = world.fireballs.filter(fb => fb.t >= fb.dur);
  for (const fb of landed) {
    world.firePits.push({ x: fb.wx1, t: 0, hitDone: false });
    FX.addShake(0.25);
    Snd.crack();
  }
  if (world.fireballs.length) world.fireballs = world.fireballs.filter(fb => fb.t < fb.dur);
  // 地刺爆发坑：落地先炸一下，短刺立 0.55 秒，跳过就安全
  for (const p of world.firePits) {
    p.t += dt;
    const px = p.x - world.x;
    if (p.t > 0.06 && p.t < 0.55 && !p.hitDone) {
      const box = playerBox();
      if (box.r > px - 52 && box.l < px + 52 && player.y > GROUND_Y - 122) {
        p.hitDone = true;
        die('spike');
        return;
      }
    }
  }
  if (world.firePits.length) world.firePits = world.firePits.filter(p => p.t < 1.1);

  // 阿坚的手里剑：直线飞向玩家，长跳/滑铲可躲（站立必中）；巨剑落地成贴地剑阵
  if (!world.shurikens) world.shurikens = [];
  for (const st of world.shurikens) {
    if (st.big) {
      st.spin += dt * 13;
      if (!st.landed) {
        st.x += st.vx * dt;
        if (st.x >= PLAYER_X + 34) {
          st.landed = 1; st.landT = 2.5;
          FX.ring(world.x + st.x, GROUND_Y - 36, { r0: 10, r1: 90, max: 0.35, color: 'rgba(210,150,255,0.9)', width: 6 });
          FX.addShake(0.22);
          Snd.spikeOut();
          if (player.y > GROUND_Y - 90) { st.dead = true; die('shuriken'); return; }   // 落地瞬间贴地扫倒
        }
      } else {
        st.landT -= dt;
        if (st.landT <= 0) {
          st.dead = true;
          FX.burst(st.x, GROUND_Y - 30, 8, { vx0: -120, vx1: 120, vy0: -160, vy1: 20, life0: 0.2, life1: 0.45, size0: 5, size1: 12, color: 'rgba(200,160,255,0.8)' });
        } else if (player.y > GROUND_Y - 92 && Math.abs(st.x - (PLAYER_X + 34)) < 44) {
          st.dead = true; die('shuriken'); return;   // 贴地剑阵：站立/滑铲撞上都是死，跳过
        }
      }
      continue;
    }
    st.x += st.vx * dt;
    st.spin += dt * 15;
    if (st.wave) {
      // 剑气波：贴地飞行跳过可躲（站立/滑铲都会被切），竖向判定宽
      st.spin = 0;
      const box = playerBox();
      const swx = world.x + st.x;
      if (swx > box.l - 30 && swx < box.r + 30 && st.y > box.t - 30 && st.y < box.b + 30) {
        st.dead = true;
        die('slash');
        return;
      }
      continue;
    }
    const box = playerBox();
    const swx = world.x + st.x;   // 手里剑存屏幕坐标，转世界坐标再与玩家判定框比较
    if (swx > box.l - 16 && swx < box.r + 16 && st.y > box.t - 16 && st.y < box.b + 16) {
      st.dead = true;
      die('shuriken');
      return;
    }
  }
  world.shurikens = world.shurikens.filter(st => !st.dead && st.x > -120 && st.x < W + 120);

  // 忍印积攒：每 3.2 秒一层，满 3 层下次技能必放大招
  if (c.skin === 'aj' && c.marks < 3 && c.skillT < 0) {
    c.markT += dt;
    if (c.markT >= 3.2) { c.markT = 0; c.marks++; Snd.coin(1); }
  }

  // 影分身活动期：缀在本体后方一起追，周期性掷剑，到时烟雾消散
  if (c.cloneOn) {
    c.cloneT -= dt;
    if (c.cloneT <= 0) {
      c.cloneOn = false;
      FX.burst(c.cloneBx + 70, GROUND_Y - 110, 14, {
        vx0: -150, vx1: 150, vy0: -190, vy1: 20, life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(200,160,255,0.8)',
      });
    } else {
      c.cloneBx += ((c.bx - 150) - c.cloneBx) * Math.min(1, dt * 2.5);
      c.cloneStar -= dt;
      if (c.cloneStar <= 0) {
        c.cloneStar = rnd(2.0, 2.8);
        world.shurikens.push({ x: c.cloneBx + 96, y: GROUND_Y - 118, vx: 600, vy: 0, spin: 0 });
        Snd.star(1);
      }
    }
  }

  // 陨石：空中下落 → 落地爆出火焰圈
  for (const m of world.meteors) {
    m.t += dt;
    if (m.t >= m.dur && m.boom === 0) {
      m.boom = 0.001;
      FX.addShake(0.3);
      Snd.crack();
      FX.burst(m.tx - world.x, GROUND_Y - 26, 16, {
        vx0: -260, vx1: 260, vy0: -320, vy1: -40,
        life0: 0.3, life1: 0.6, size0: 5, size1: 13,
        color: ['#ff9a5a', '#ffd34d', '#ff7a4a'][Math.floor(Math.random() * 3)],
      });
    }
    if (m.boom > 0) {
      m.boom += dt;
      const px = world.x + PLAYER_X;
      if (m.boom < 0.4 && Math.abs(m.tx - px) < 68 && player.y > GROUND_Y - 135) { die('spike'); return; }
    }
  }
  if (world.meteors.length) world.meteors = world.meteors.filter(m => m.boom === 0 || m.boom < 0.55);

  // 技能轮换触发：蚩尤五招 / 阿坚六招轮着来
  // 被甩开的趋势越明显，他放技能越少（压迫感跟着追击距离走）
  c.skillCd -= dt * ((world.cyTune && world.cyTune.skills) || 1) * (0.6 + (c.aggro || 0.35) * 0.75);
  if (c.skillCd <= 0 && c.skillT < 0) {
    c.skillT = 0;
    c.spawned = false;
    if (c.skin === 'aj') {
      if (c.marks >= 3) {
        // 大招：影分身之术（耗尽 3 层忍印）
        c.kind = 'clone';
        c.marks = 0; c.markT = 0;
        Snd.dash();
        FX.flashScreen('#b06aff', 0.16);
        FX.float(W / 2 - 40, 296, '阿坚 影分身之术！', { color: '#e6ccff', size: 30 });
      } else {
      const r = Math.random();
      c.kind = r < 0.18 ? 'shuriken' : r < 0.36 ? 'nova' : r < 0.52 ? 'smoke' : r < 0.70 ? 'sword' : r < 0.84 ? 'iai' : 'combo';
      c.phase = 0;
      if (c.kind === 'shuriken') {
        c.starsLeft = 3; c.starT = 0; c.bigDone = false;
        Snd.spikeOut();
        FX.flashScreen('#b06aff', 0.1);
        FX.float(W / 2 - 40, 296, '阿坚 掷出手里的剑！', { color: '#d3a8ff', size: 26 });
      } else if (c.kind === 'nova') {
        c.hitDone = false;
        Snd.dash();
        FX.flashScreen('#b06aff', 0.1);
        FX.float(W / 2 - 40, 296, '阿坚 影袭来了！跳！', { color: '#c9a0ff', size: 27 });
      } else if (c.kind === 'sword') {
        c.swordLeft = 2; c.swordT = 0; c.swordSlash = false; c.slashAnimT = 0;
        Snd.spikeOut();
        FX.flashScreen('#ff4d6d', 0.12);
        FX.float(W / 2 - 40, 296, '阿坚 拔剑了！小心近身！', { color: '#ff8f9e', size: 27 });
      } else if (c.kind === 'iai') {
        c.hitDone = false;
        Snd.dash();
        FX.flashScreen('#ff4d6d', 0.16);
        FX.float(W / 2 - 40, 296, '阿坚 居合·十字斩！快跳！', { color: '#ffb0be', size: 29 });
      } else if (c.kind === 'combo') {
        c.comboN = 3; c.comboT = 0.05; c.comboHit = false; c.slashAnimT = 0;
        Snd.dash();
        FX.flashScreen('#ff4d6d', 0.14);
        FX.float(W / 2 - 40, 296, '阿坚 贴身连斩！快跳开！', { color: '#ff9aa5', size: 28 });
      } else {
        Snd.dash();
        FX.flashScreen('#b06aff', 0.1);
        FX.float(W / 2 - 40, 296, '阿坚 消失了！小心头顶！', { color: '#d3a8ff', size: 26 });
      }
      }
    } else {
    const r = Math.random();
    c.kind = r < 0.28 ? 'trap' : r < 0.52 ? 'fireball' : r < 0.70 ? 'claw' : r < 0.85 ? 'quake' : 'meteor';
    if (c.kind === 'trap') {
      c.warnKind = ['gap', 'spike', 'bar'][Math.floor(Math.random() * 3)];
      Snd.spikeOut();
      FX.flashScreen('#ff5a4a', 0.1);
      FX.float(W / 2 - 40, 296, '蚩尤 改变了地形！', { color: '#ff9a8a', size: 26 });
    } else if (c.kind === 'quake') {
      Snd.crack();
      FX.flashScreen('#ffb08a', 0.1);
      FX.float(W / 2 - 40, 296, '蚩尤 跺地了！刺浪来了', { color: '#ffb08a', size: 26 });
    } else if (c.kind === 'meteor') {
      // 三颗陨石砸向玩家前方（按速度预测落点）
      for (let i = 0; i < 3; i++) {
        world.meteors.push({
          tx: world.x + PLAYER_X + world.speed * 1.15 + i * 190 + rnd(-40, 60),
          t: 0, dur: 1.15, boom: 0,
        });
      }
      Snd.whoosh();
      FX.flashScreen('#ffb08a', 0.1);
      FX.float(W / 2 - 40, 296, '蚩尤 召唤陨石！', { color: '#ffb08a', size: 26 });
    } else if (c.kind === 'fireball') {
      // 落点按玩家速度预测：0.85 秒后玩家所在的位置，骨刺会落在必经之路上
      world.fireballs.push({
        wx0: world.x + c.bx + 70,
        wx1: world.x + PLAYER_X + world.speed * 0.85 + rnd(30, 190),
        t: 0, dur: 0.85,
      });
      Snd.whoosh();
      FX.flashScreen('#ffb08a', 0.1);
      FX.float(W / 2 - 40, 296, '蚩尤 掷出骨刺！', { color: '#ffb08a', size: 26 });
    } else {
      c.hitDone = false;
      Snd.dash();
      FX.float(W / 2 - 40, 296, '蚩尤 冲过来了！跳！', { color: '#ff9a8a', size: 27 });
    }
    }   // end skin 分支
  }
  if (c.skillT >= 0) {
    c.skillT += dt;
    if (c.skin === 'aj' && c.kind === 'shuriken') {
      // 上前半步瞄准 → 三连射 → 收尾掷出巨型手里剑，落地成贴地剑阵挡路 2.5 秒
      c.bx += ((PLAYER_X - 302) - c.bx) * Math.min(1, dt * 1.4);
      if (c.skillT > 0.55 && c.starsLeft > 0) {
        c.starT -= dt;
        if (c.starT <= 0) {
          c.starT = 0.17;
          c.starsLeft--;
          world.shurikens.push({ x: c.bx + 96, y: c.y - 118, vx: 640 + (2 - c.starsLeft) * 55, vy: 0, spin: 0 });
          Snd.star(1);
        }
      }
      if (c.starsLeft <= 0 && c.skillT > 1.35 && !c.bigDone) {
        c.bigDone = true;
        world.shurikens.push({ x: c.bx + 96, y: c.y - 118, vx: 430, vy: 0, spin: 0, big: 1, landed: 0, landT: 0 });
        Snd.star(1);
      }
      if (c.skillT > 2.2) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else if (c.skin === 'aj' && c.kind === 'nova') {
      // 蓄力抖动 → 贴地影袭突进到玩家脚边 → 横扫（贴地才中，跳起躲开）
      if (c.skillT < 0.3) {
        c.bx += rnd(-3, 3);
      } else if (c.skillT < 0.66) {
        c.bx += ((PLAYER_X - 58) - c.bx) * Math.min(1, dt * 12);
        if (Math.random() < 0.7) {
          FX.burst(c.bx + 60 + rnd(-20, 30), c.y - rnd(10, 60), 1, {
            vx0: -180, vx1: -40, vy0: -60, vy1: 10, life0: 0.2, life1: 0.4, size0: 4, size1: 10, color: 'rgba(160,110,255,0.8)',
          });
        }
      } else if (c.skillT < 0.92) {
        if (!c.hitDone) {
          c.hitDone = true;
          if (player.y > GROUND_Y - 112) { die('nova'); return; }
          FX.ring(world.x + PLAYER_X - 24, GROUND_Y - 58, { r0: 14, r1: 130, max: 0.42, color: 'rgba(170,120,255,0.9)', width: 7 });
          FX.addShake(0.32);
          Snd.whoosh();
        }
      }
      if (c.skillT > 1.6) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else if (c.skin === 'aj' && c.kind === 'smoke') {
      // 烟雾瞬步四段：烟雾消失 → 闪现玩家头顶 → 从天而降下劈（跳开躲）→ 落地炸出双向手里剑并归位
      if (c.skillT < 0.45 && c.phase !== 1) {
        c.phase = 1;
        c.hidden = true;
        FX.burst(c.bx + 70, c.y - 110, 14, {
          vx0: -150, vx1: 150, vy0: -190, vy1: 20, life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(200,160,255,0.8)',
        });
      } else if (c.skillT >= 0.45 && c.skillT < 0.9 && c.phase !== 2) {
        c.phase = 2;
        c.hidden = false;
        c.bx = PLAYER_X + 26;
        c.y = GROUND_Y - 336;
        c.vy = 0; c.onGround = false; c.sliding = false;
        FX.burst(c.bx + 70, c.y - 100, 14, {
          vx0: -150, vx1: 150, vy0: -190, vy1: 20, life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(200,160,255,0.8)',
        });
      } else if (c.skillT >= 0.9 && c.skillT < 1.5 && c.phase !== 3) {
        c.phase = 3;
        c.vy = 1750;   // 从天而降
      }
      // 下砸落地：踩中判死，跳开则炸出冲击波 + 朝玩家掷一枚回马剑
      if (c.phase === 3 && c.onGround) {
        c.phase = 4;
        if (player.y > GROUND_Y - 116) { die('nova'); return; }
        FX.ring(world.x + PLAYER_X - 8, GROUND_Y - 50, { r0: 14, r1: 150, max: 0.45, color: 'rgba(170,120,255,0.9)', width: 7 });
        FX.addShake(0.36);
        Snd.dash();
        world.shurikens.push({ x: c.bx + 60, y: c.y - 118, vx: -560, vy: 0, spin: 0 });
        Snd.star(1);
      }
      if (c.skillT >= 2.3) {
        c.phase = 0;
        c.hidden = false;
        c.bx = PLAYER_X - 300;
        c.y = GROUND_Y; c.vy = 0; c.onGround = true; c.sliding = false;
        c.skillT = -1;
        c.skillCd = rnd(6, 9);
      }
    } else if (c.skin === 'aj' && c.kind === 'sword') {
      // 剑气斩：拔剑上前贴近 → 贴身横扫一刀（跳可躲）→ 收尾两道贴地剑气波
      if (c.slashAnimT > 0) c.slashAnimT -= dt;
      if (c.skillT < 0.55) {
        c.bx += ((PLAYER_X - 150) - c.bx) * Math.min(1, dt * 1.8);
      } else if (c.skillT < 0.85) {
        if (!c.swordSlash) {
          c.swordSlash = true; c.slashAnimT = 0.24;
          Snd.whoosh(); FX.addShake(0.13);
          FX.ring(world.x + c.bx + 70, GROUND_Y - 90, { r0: 14, r1: 96, max: 0.3, color: 'rgba(255,80,95,0.85)', width: 5 });
          if (player.y > GROUND_Y - 118) { die('slash'); return; }
        }
      } else if (c.swordLeft > 0) {
        c.swordT -= dt;
        if (c.swordT <= 0) {
          c.swordT = 0.16;
          c.swordLeft--;
          world.shurikens.push({ x: c.bx + 110, y: GROUND_Y - 58, vx: 700 + (1 - c.swordLeft) * 70, vy: 0, spin: 0, wave: 1 });
          FX.burst(c.bx + 110, GROUND_Y - 70, 10, {
            vx0: 60, vx1: 320, vy0: -160, vy1: 60, life0: 0.2, life1: 0.45, size0: 5, size1: 13, color: 'rgba(255,60,80,0.85)',
          });
          Snd.whoosh();
        }
      }
      if (c.skillT > 1.9) { c.skillT = -1; c.skillCd = rnd(6, 9); c.swordSlash = false; }
    } else if (c.skin === 'aj' && c.kind === 'iai') {
      // 居合·十字斩：泛红蓄力 0.7s（全身高亮预警）→ 瞬间突进穿过玩家（横扫判定，跳开躲）→ 剑气残影
      if (c.skillT < 0.7) {
        c.bx += rnd(-3, 3);
        if (Math.random() < 0.4) {
          FX.burst(c.bx + 70 + rnd(-30, 30), c.y - rnd(40, 180), 1, {
            vx0: -40, vx1: 40, vy0: -120, vy1: -20, life0: 0.2, life1: 0.4, size0: 4, size1: 10, color: 'rgba(255,80,100,0.9)',
          });
        }
      } else if (c.skillT < 1.02) {
        c.bx += ((PLAYER_X + 40) - c.bx) * Math.min(1, dt * 14);
        if (Math.random() < 0.8) {
          FX.burst(c.bx + 60 + rnd(-20, 30), c.y - rnd(10, 100), 1, {
            vx0: -260, vx1: -60, vy0: -80, vy1: 10, life0: 0.2, life1: 0.4, size0: 4, size1: 11, color: 'rgba(255,60,80,0.85)',
          });
        }
      } else if (c.skillT < 1.3) {
        if (!c.hitDone) {
          c.hitDone = true;
          if (player.y > GROUND_Y - 112) { die('slash'); return; }
          FX.ring(world.x + PLAYER_X, GROUND_Y - 60, { r0: 16, r1: 160, max: 0.4, color: 'rgba(255,70,90,0.9)', width: 8 });
          FX.addShake(0.38);
          Snd.crack();
        }
      }
      if (c.skillT > 1.7) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else if (c.skin === 'aj' && c.kind === 'combo') {
      // 贴身三连斩：瞬间冲到玩家身前 → 三段横扫（每刀独立判定，跳开可躲，刀间 0.42s 间隙）
      if (c.slashAnimT > 0) c.slashAnimT -= dt;
      if (c.skillT < 0.4) {
        c.bx += ((PLAYER_X + 34) - c.bx) * Math.min(1, dt * 13);   // 红影突进贴身
        if (Math.random() < dt * 34) {
          FX.burst(c.bx + 44 + rnd(-16, 16), c.y - rnd(16, 110), 1, {
            vx0: -260, vx1: -60, vy0: -90, vy1: 20, life0: 0.15, life1: 0.4, size0: 4, size1: 11, color: 'rgba(255,60,85,0.85)',
          });
        }
      } else if (c.comboN > 0) {
        c.comboT -= dt;
        if (c.comboT <= 0) {
          c.comboT = 0.42; c.comboN--; c.comboHit = false; c.slashAnimT = 0.2;
          Snd.whoosh(); FX.addShake(0.14);
          FX.ring(world.x + c.bx + 64, GROUND_Y - 84, { r0: 14, r1: 92, max: 0.3, color: 'rgba(255,80,95,0.85)', width: 5 });
        }
        // 每刀触发后 0.2s 内的横扫判定：贴身站立/滑铲被切，跳过
        if (!c.comboHit && c.comboT > 0.2 && Math.abs(c.bx - PLAYER_X) < 105 && player.y > GROUND_Y - 118) {
          c.comboHit = true;
          die('slash');
          return;
        }
      }
      if (c.skillT > 2.2) { c.skillT = -1; c.skillCd = rnd(6, 9); c.comboN = 0; }
    } else if (c.skin === 'aj' && c.kind === 'clone') {
      // 大招影分身之术：烟雾蓄力 → 分出分身缀在后方一起追 8 秒（分身周期掷剑）
      if (c.skillT < 0.6) {
        if (!c.spawned) {
          c.spawned = true;
          FX.burst(c.bx + 70, c.y - 110, 18, {
            vx0: -170, vx1: 170, vy0: -200, vy1: 20, life0: 0.3, life1: 0.6, size0: 7, size1: 17, color: 'rgba(200,160,255,0.85)',
          });
          Snd.dash();
        }
      } else if (!c.cloneOn) {
        c.cloneOn = true;
        c.cloneT = 8;
        c.cloneBx = Math.min(c.bx - 150, PLAYER_X - 420);
        c.cloneStar = 2.0;
        FX.burst(c.cloneBx + 70, GROUND_Y - 110, 16, {
          vx0: -160, vx1: 160, vy0: -190, vy1: 20, life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(200,160,255,0.8)',
        });
        Snd.star(1);
      }
      if (c.skillT > 1.2) { c.skillT = -1; c.skillCd = rnd(7, 10); }
    } else if (c.kind === 'trap') {
      // 释放技能时上前半步，1.3 秒后凭空改出陷阱
      c.bx += ((PLAYER_X - 302) - c.bx) * Math.min(1, dt * 1.4);
      if (c.skillT > 1.3 && !c.spawned) {
        c.spawned = true;
        // 陷阱放在玩家 1.4 秒后跑到的地方——跑得越快放得越远，永远在必经之路上
        const wx = world.x + PLAYER_X + clamp(world.speed * 1.4, 430, W * 0.72);
        if (c.warnKind === 'gap') {
          world.gaps.push({ x: wx, w: 152, spikes: 3 });
          world.signs.push({ x: wx - 170 });
        } else if (c.warnKind === 'spike') {
          world.spikeTraps.push({ x: wx, w: 112, phase: 0 });
        } else {
          world.lowBars.push({ x: wx, w: 102, gapH: 96 });
        }
        FX.flashScreen('#ff5a4a', 0.12);
        FX.float(W - 128, GROUND_Y - 158, '陷阱出现！', { color: '#ff8f7e', size: 24 });
      }
      if (c.skillT > 2.3) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else if (c.kind === 'claw') {
      // 蓄力抖动 → 猛冲到玩家脚边 → 横扫（贴地才中，跳起躲开）→ 退回
      if (c.skillT < 0.35) {
        c.bx += rnd(-3, 3);
      } else if (c.skillT < 0.72) {
        c.bx += ((PLAYER_X - 58) - c.bx) * Math.min(1, dt * 11);
      } else if (c.skillT < 0.98) {
        if (!c.hitDone) {
          c.hitDone = true;
          if (player.y > GROUND_Y - 112) { die('claw'); return; }
          FX.ring(world.x + PLAYER_X - 24, GROUND_Y - 58, { r0: 14, r1: 130, max: 0.42, color: 'rgba(255,110,90,0.9)', width: 7 });
          FX.addShake(0.32);
          Snd.whoosh();
        }
      }
      if (c.skillT > 1.6) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else if (c.kind === 'quake') {
      // 跺脚 0.5 秒后放出刺浪
      if (c.skillT > 0.5 && !c.spawned) {
        c.spawned = true;
        world.quakes.push({ x: world.x + c.bx + 60, v: world.speed + 270, t: 0 });
        FX.addShake(0.32);
      }
      if (c.skillT > 0.9) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    } else {
      // fireball / meteor：弹已在飞，等前摇结束
      if (c.skillT > 0.9) { c.skillT = -1; c.skillCd = rnd(6, 9); }
    }
  }
}

// ---- 碰撞 ----
/* 判定盒刻意比视觉小一圈：擦身而过不会误判，身法才有发挥空间 */
function playerBox() {
  const w = player.sliding ? 86 : 66;
  const h = player.sliding ? 76 : 128;
  const cx = world.x + PLAYER_X;
  return { l: cx - w / 2, r: cx + w / 2, t: player.y - h, b: player.y, h };
}

/* 与机关擦身而过时的即时反馈：强化「身法」手感 */
function nearMiss() {
  if (G.nearCd > 0 || G.state !== 'playing') return;
  G.nearCd = 0.55;
  // 极限闪避 = 身法好：脱身进度小涨，奖励走位而不是硬吃
  if (G.chiyou && !G.chiyou.lost) {
    G.chiyou.escape = clamp((G.chiyou.escape || 0) + 0.025, 0, 1);
  }
  nearStreakAdd();   // 身法连击：满 4 次送短无敌
  Snd.nearMiss();
  FX.float(world.x + PLAYER_X + 34, player.y - 196, '险！', { color: '#ffe066', size: 25 });
  FX.ring(world.x + PLAYER_X, player.y - 70, {
    r0: 12, r1: 66, max: 0.32, color: 'rgba(255,230,120,0.85)', width: 3,
  });
}

function groundSupport() {
  const cx = world.x + PLAYER_X;
  for (const g of world.gaps) {
    if (cx - 18 > g.x + 4 && cx + 18 < g.x + g.w - 4) return false;
  }
  for (const ff of world.fakeFloors) {
    if (!ff.broken) continue;
    if (cx - 18 > ff.x + 4 && cx + 18 < ff.x + ff.w - 4) return false;
  }
  return true;
}

function movingPlatTop(mp) {
  return mp.y0 + Math.sin(G.time * mp.spd + mp.phase) * mp.amp;
}

/* 石板底沿位置：从高位向下压，再升回 */
function crusherBottom(cr) {
  const s = (Math.sin(G.time * cr.spd + cr.phase) + 1) / 2;   // 0..1
  return cr.high - s * cr.travel;
}

function pendulumHead(pd) {
  const ang = Math.sin(G.time * pd.spd + pd.phase) * pd.amp;
  return {
    x: pd.x + Math.sin(ang) * pd.len,
    y: pd.pivotY + Math.cos(ang) * pd.len,
    r: pd.r,
    ang: ang,
  };
}

/* 机关逐帧更新：滚石 / 落石 / 加速带 */
function updateHazards(dt) {
  const cx = world.x + PLAYER_X;
  if (G.nearCd > 0) G.nearCd = Math.max(0, G.nearCd - dt);
  // 限时增益倒计时
  for (const k in G.buff) {
    if (G.buff[k] > 0) G.buff[k] = Math.max(0, G.buff[k] - dt);
  }
  if (G.cardPop) {
    G.cardPop.t += dt;
    if (G.cardPop.t > 1.8) G.cardPop = null;
  }

  // 背后追石：玩家接近触发点后从左侧滚入
  for (const ct of (world.chaseTriggers || [])) {
    if (!ct.spawned && cx > ct.x - 250 && cx < ct.x + 140) {
      ct.spawned = true;
      world.rollingRocks.push({
        x: world.x - 430, y: GROUND_Y - 54, r: 54, chase: true,
        rel: ct.rel || 132, spin: 0, alive: true,
      });
      Snd.whoosh();
      FX.addShake(0.35);
      FX.float(W / 2, 288, '后面有石头！跳', { color: '#ff9a8a', size: 30 });
    }
  }

  for (const rk of world.rollingRocks) {
    if (!rk.alive) continue;
    if (rk.chase) rk.x += (world.speed + rk.rel) * dt;
    else rk.x += rk.vx * dt;
    rk.spin += dt * 3.4;
    if (rk.x < world.x - 220) { rk.alive = false; continue; }
    for (const g of world.gaps) {
      if (rk.x > g.x + 24 && rk.x < g.x + g.w - 24) {
        rk.alive = false;
        FX.addShake(0.3);
        FX.burst(rk.x, GROUND_Y + 24, 12, {
          vx0: -160, vx1: 160, vy0: -220, vy1: -20,
          life0: 0.3, life1: 0.6, size0: 6, size1: 15, color: 'rgba(120,110,100,0.95)', grav: 800,
        });
      }
    }
    if (Math.random() < 0.5) {
      FX.burst(rk.x - rk.r * 0.5, GROUND_Y - 4, 1, {
        vx0: -120, vx1: -40, vy0: -110, vy1: -30,
        life0: 0.2, life1: 0.42, size0: 5, size1: 11, color: 'rgba(150,138,122,0.85)',
      });
    }
  }

  for (const fr of world.fallingRocks) {
    if (!fr.triggered && cx > fr.x - 255 && cx < fr.x + 255) {
      fr.triggered = true;
      Snd.whoosh();
    }
    if (fr.triggered && !fr.landed) {
      fr.vy += GRAVITY * 1.2 * dt;
      fr.y += fr.vy * dt;
      if (fr.y >= GROUND_Y - fr.r) {
        fr.y = GROUND_Y - fr.r;
        fr.landed = true;
        fr.t = 0;
        Snd.crack();
        FX.addShake(0.95);
        FX.flashScreen('#000000', 0.12);
        FX.burst(fr.x, GROUND_Y - 8, 22, {
          vx0: -260, vx1: 260, vy0: -300, vy1: -30,
          life0: 0.35, life1: 0.75, size0: 7, size1: 18, color: 'rgba(140,126,110,0.95)', grav: 900, spin: 7,
        });
        FX.ring(fr.x, GROUND_Y - 12, { r0: 10, r1: 120, max: 0.45, color: 'rgba(255,220,160,0.75)', width: 6 });
      }
    }
    // 落地碎成矮堆，停 1.3 秒就散，这期间跳过去就行
    if (fr.landed) {
      fr.t = (fr.t || 0) + dt;
      if (fr.t > 1.3 && !fr.gone) {
        fr.gone = true;
        FX.burst(fr.x, GROUND_Y - 24, 10, {
          vx0: -190, vx1: 190, vy0: -170, vy1: 10,
          life0: 0.3, life1: 0.6, size0: 5, size1: 12, color: 'rgba(140,126,110,0.95)', grav: 900, spin: 5,
        });
      }
    }
  }

  // 小怪：死亡动画计时 + 跳跳刺球的弹跳物理 + 盾怪的浮动
  for (const en of world.enemies) {
    if (en.dead) { en.deadT = (en.deadT || 0) + dt; continue; }
    if (en.kind === 'hopper') {
      en.hopCd = (en.hopCd || 0) - dt;
      if (en.hopCd <= 0 && en.y >= GROUND_Y - 36) { en.vy = -790; en.hopCd = rnd(1.0, 1.5); }
      en.vy += GRAVITY * 0.62 * dt;
      en.y += en.vy * dt;
      if (en.y > GROUND_Y - 34) { en.y = GROUND_Y - 34; en.vy = 0; }
    } else if (en.kind === 'shield') {
      en.y = en.y0 + Math.sin(G.time * en.spd + en.phase) * en.amp;
    }
  }

  for (const bo of world.boosters) {
    bo.gl = Math.max(0, bo.gl - dt * 2.2);
    if (cx > bo.x && cx < bo.x + bo.w && player.onGround) {
      if (G.boostT <= 0.3) {
        Snd.whoosh();
        FX.ring(cx, GROUND_Y - 30, { r0: 10, r1: 110, max: 0.42, color: 'rgba(140,240,255,0.9)', width: 6 });
        FX.float(cx, GROUND_Y - 130, '加速！', { color: '#8ce8ff', size: 28 });
      }
      G.boostT = 1.7;
      bo.gl = 1;
    }
  }

  if (G.boostT > 0 && Math.random() < 0.85) {
    FX.burst(cx - 40, player.y - rnd(10, 90), 2, {
      vx0: -560, vx1: -300, vy0: -50, vy1: 50,
      life0: 0.16, life1: 0.32, size0: 6, size1: 14, color: 'rgba(150,235,255,0.9)',
    });
  }
  // 冲刺尾焰
  if (G.dashT > 0 && Math.random() < 0.95) {
    FX.burst(cx - 46, player.y - rnd(20, 120), 3, {
      vx0: -680, vx1: -380, vy0: -70, vy1: 70,
      life0: 0.14, life1: 0.3, size0: 7, size1: 16, color: 'rgba(150,235,255,0.95)',
    });
  }
}

/* 小怪位置：浮空怪上下浮动（可踩），跳跳刺球走自己的弹跳物理，盾怪小幅浮动 */
function enemyY(en) {
  if (en.kind === 'hopper') return en.y;
  return en.y0 + Math.sin(G.time * en.spd + en.phase) * en.amp;
}

function spikeTrapHeight(tr) {
  const cyc = 2.6;
  const t = (G.time + tr.phase) % cyc;
  if (t < 0.85) return 0;
  if (t < 1.0) return (t - 0.85) / 0.15;
  if (t < 1.7) return 1;
  if (t < 1.9) return 1 - (t - 1.7) / 0.2;
  return 0;
}

/* ---------------- 操作 ---------------- */
function doJump() {
  if (G.state !== 'playing') return;
  const canGround = player.onGround || player.coyote > 0;
  // 道具给的二段跳（祭坛「风息之靴」再放宽上限）
  const canBuff = G.buff.djump > 0 && !player.onGround && (player.jumps || 0) < 2 + upLv('djump');
  // 被动补跳：只能在下坠途中触发，且不在冷却里
  const canAir = !canGround && !canBuff && player.vy > 0 && G.airJump > 0 && G.airCd <= 0;
  // mod 给的固有空中跳（api.player({ airJumps })）
  const ajs = (world.pTune && world.pTune.airJumps) || 0;
  const canModAir = !canGround && !canBuff && ajs > 0 && (player.jumps || 0) >= 1 && (player.jumps || 0) < 1 + ajs;
  if (!canGround && !canBuff && !canAir && !canModAir) {
    player.jumpBuf = 0.18;   // 落地前抢按也算数（输入缓冲）
    return;
  }
  player.coyote = 0;
  player.jumpBuf = 0;
  player.onGround = false;
  // 按住下滑键再起跳就是矮跳，专门用来钻顶刺；滑铲超过 0.34s 再按跳 = 铲跳（满跳跳出去）
  const lowJump = canGround && (G.holdSlide && (!player.sliding || player.slidingT < 0.34));
  player.sliding = false;
  G.jumpCount++;
  bountyAdd('jump', 1);
  player.jumpCut = true;   // 本跳可被「松手截断」：长按 = 满跳，轻点 = 小跳
  const jm = (world.pTune && world.pTune.jumpMul) || 1;

  if (canGround) {
    player.vy = (lowJump ? JUMP_V * 0.62 : JUMP_V) * jm;
    player.jumps = 1;
    if (lowJump) {
      FX.float(world.x + PLAYER_X + 26, player.y - 132, '矮跳', { color: '#8ce8ff', size: 21 });
    }
  } else if (canBuff) {
    player.vy = JUMP_V * 0.92 * jm;
    player.jumps = (player.jumps || 0) + 1;
    Snd.double();
    FX.ring(world.x + PLAYER_X, player.y, { r0: 10, r1: 70, max: 0.36, color: 'rgba(182,236,146,0.9)', width: 5 });
  } else if (canModAir) {
    // mod 空中跳：紫色光环，不消耗道具补跳次数
    player.vy = AIR_JUMP_V * jm;
    player.jumps = (player.jumps || 0) + 1;
    Snd.airJump();
    FX.ring(world.x + PLAYER_X, player.y, { r0: 12, r1: 96, max: 0.4, color: 'rgba(214,168,255,0.95)', width: 6 });
  } else {
    // 被动补跳：下坠中翻身再起
    player.vy = AIR_JUMP_V;
    player.jumps = (player.jumps || 0) + 1;
    G.airJump--;
    if (G.airJump <= 0) G.airCd = AIR_JUMP_CD;
    Snd.airJump();
    FX.ring(world.x + PLAYER_X, player.y, { r0: 12, r1: 98, max: 0.42, color: 'rgba(140,232,255,0.95)', width: 6 });
    FX.burst(world.x + PLAYER_X, player.y, 12, {
      vx0: -260, vx1: 260, vy0: -160, vy1: 90,
      life0: 0.2, life1: 0.42, size0: 5, size1: 12, color: 'rgba(150,235,255,0.95)',
    });
    FX.float(world.x + PLAYER_X + 24, player.y - 164,
      G.airJump > 0 ? '补跳 ×' + G.airJump : '补跳 · 最后一发',
      { color: '#8ce8ff', size: 23 });
  }
  player.sx = 0.86;
  player.sy = 1.2;
  player.spin = 0;
  player.spinT = 0;
  player.spinOn = true;
  Snd.jump();
  FX.burst(world.x + PLAYER_X, player.y, 8, {
    vx0: -240, vx1: 140, vy0: -70, vy1: 50,
    life0: 0.2, life1: 0.42, size0: 6, size1: 13, color: 'rgba(255,255,255,0.92)',
  });
  FX.ring(world.x + PLAYER_X, player.y, { r0: 6, r1: 52, max: 0.32, color: 'rgba(255,255,255,0.5)', width: 4 });
  runUgcHook('jump', { jumps: player.jumps || 0, lowJump });
}

function doSlide() {
  if (G.state !== 'playing') return;
  if (player.onGround) {
    if (!player.sliding) {
      G.slideCount++;
      bountyAdd('slide', 1);
      Snd.slide();
      FX.burst(world.x + PLAYER_X - 30, player.y - 6, 8, {
        vx0: -300, vx1: -120, vy0: -120, vy1: -20,
        life0: 0.2, life1: 0.4, size0: 6, size1: 14, color: 'rgba(255,255,255,0.9)',
      });
    }
    player.sliding = true;
    player.slidingT = 0.55;
  } else {
    player.vy = Math.max(player.vy, 1500);
    player.sliding = false;
    player.spinOn = false;
    Snd.whoosh();
  }
}

/* 能量冲刺：消耗满槽能量，进入无敌高速状态，沿途机关直接撞碎 */
function doDash() {
  if (G.state !== 'playing') return;
  if (G.energy < ENERGY_MAX || G.dashT > 0) return;
  G.energy = 0;
  G.dashT = DASH_TIME;
  G.dashN++;
  // 冲刺正面砸中蚩尤 = 重伤害
  if (G.boss && (G.boss.state === 'fight' || G.boss.state === 'mech')) {
    bossDamage(25);
    G.boss.bx -= 26;
  }
  G.buff.invinc = Math.max(G.buff.invinc, DASH_TIME + 0.1);
  Snd.dash();
  FX.flashScreen('#8ce8ff', 0.38);
  FX.addShake(0.5);
  FX.sfloat(W / 2, 292, '能量冲刺！', { color: '#8ce8ff', size: 50, life: 1.2, vy: -18 });
  FX.ring(world.x + PLAYER_X, player.y - 80, { r0: 20, r1: 240, max: 0.5, color: 'rgba(140,232,255,0.95)', width: 8 });
  FX.burst(world.x + PLAYER_X, player.y - 80, 22, {
    vx0: -520, vx1: 200, vy0: -260, vy1: 260,
    life0: 0.25, life1: 0.55, size0: 6, size1: 16, color: 'rgba(150,235,255,0.95)',
  });
}

// ---- 物理 ----
function updatePlayer(dt) {
  player.prevY = player.y;
  // 重力 = 基准 × 月面段倍率 × mod 倍率
  player.vy += GRAVITY * gravMul() * ((world.pTune && world.pTune.gravMul) || 1) * dt;
  // 可变跳跃高度：上升中松开跳跃键 → 立刻砍动量快速回落（轻点小跳 / 长按满跳）
  if (player.vy < 0 && !G.holdJump && player.jumpCut) {
    player.jumpCut = false;
    player.vy = Math.max(player.vy * 0.4, -430);
  }
  player.y += player.vy * dt;
  player.coyote = Math.max(0, player.coyote - dt);
  player.jumpBuf = Math.max(0, player.jumpBuf - dt);
  if (player.jumpBuf > 0 && (player.onGround || player.coyote > 0)) {
    player.jumpBuf = 0;
    doJump();
  }
  if (player.sliding) {
    player.slidingT -= dt;
    if (player.slidingT <= 0) player.sliding = false;
    // 松手能提前站起来，但至少滑 0.21 秒，免得误触
    else if (!G.holdSlide && player.slidingT < 0.34) player.sliding = false;
  }

  const cx = world.x + PLAYER_X;

  // 弹跳板
  for (const bp of world.bouncePads) {
    const topY = GROUND_Y - 30;
    if (cx > bp.x - 8 && cx < bp.x + bp.w + 8 && player.vy >= 0 && player.prevY <= topY + 6 && player.y >= topY) {
      player.y = topY;
      player.vy = -ugcT('bouncePad', 'pow', 1980);
      player.onGround = false;
      player.sliding = false;
      bp.pop = 1;
      bp.t = G.time;
      G.padBounce++;
      Snd.bounce();
      FX.burst(cx, topY, 14, {
        vx0: -180, vx1: 180, vy0: -320, vy1: -60,
        life0: 0.25, life1: 0.5, size0: 6, size1: 14, color: 'rgba(160,220,255,0.95)',
      });
      FX.ring(cx, topY, { r0: 8, r1: 70, max: 0.4, color: 'rgba(160,220,255,0.8)', width: 5 });
      FX.float(cx, topY - 40, '弹起来！', { color: '#9be2ff', size: 26 });
      return;
    }
  }

  // 移动平台跟随
  let landY = null;
  const supported = groundSupport();
  for (const mp of world.movingPlatforms) {
    const top = movingPlatTop(mp);
    if (cx > mp.x - 12 && cx < mp.x + mp.w + 12 && Math.abs(player.y - top) < 28 && player.vy >= -60) {
      player.y = top;
      player.vy = 0;
      player.onGround = true;
      landY = top;
    }
  }

  if (supported && player.y >= GROUND_Y && player.prevY <= GROUND_Y + 2 && (landY === null || GROUND_Y < landY)) {
    landY = GROUND_Y;
  }
  for (const p of world.platforms) {
    if (cx > p.x - 4 && cx < p.x + p.w + 4) {
      if (player.y >= p.top && player.prevY <= p.top + 2 && (landY === null || p.top < landY)) landY = p.top;
    }
  }
  for (const cm of world.crumbles) {
    if (cm.broken) continue;
    if (cx > cm.x - 4 && cx < cm.x + cm.w + 4) {
      if (player.y >= cm.top && player.prevY <= cm.top + 2 && (landY === null || cm.top < landY)) landY = cm.top;
    }
  }

  if (landY !== null && player.vy >= 0) {
    if (!player.onGround) {
      Snd.land();
      player.sx = 1.16;
      player.sy = 0.84;
      player.spinOn = false;
      FX.burst(cx, landY, 9, {
        vx0: -220, vx1: 220, vy0: -150, vy1: -20,
        life0: 0.2, life1: 0.42, size0: 6, size1: 13, color: 'rgba(255,255,255,0.88)',
      });
      FX.ring(cx, landY, { r0: 4, r1: 40, max: 0.28, color: 'rgba(255,255,255,0.4)', width: 3 });
    }
    player.y = landY;
    player.vy = 0;
    player.onGround = true;
  } else {
    if (player.onGround) player.coyote = 0.13;   // 刚离开地面还能补跳（土狼时间）
    player.onGround = false;
  }

  // 撞头
  if (player.vy < 0) {
    const box = playerBox();
    for (const p of world.platforms) {
      const bottom = p.top + p.h;
      if (cx > p.x - 4 && cx < p.x + p.w + 4) {
        if (box.t <= bottom && box.b > p.top + 12) {
          player.y = bottom + box.h;
          player.vy = 0;
        }
      }
    }
  }

  const box = playerBox();

  // 低横杆，头顶给 5px 余量
  for (const lb of world.lowBars) {
    if (box.r > lb.x && box.l < lb.x + lb.w) {
      const barY = GROUND_Y - lb.gapH;
      if (box.t + 5 < barY) return die('bar');
      if (box.t < barY + 12) nearMiss();
    }
  }
  // 天花板尖刺
  for (const cs of world.ceilSpikes) {
    if (box.r > cs.x && box.l < cs.x + cs.w) {
      const cy = GROUND_Y - cs.low;
      if (box.t + 5 < cy) return die('ceil');
      if (box.t < cy + 12) nearMiss();
    }
  }
  // 伸缩刺，刺尖判定往里收 16px，贴着擦过去不算中
  for (const tr of world.spikeTraps) {
    const hh = spikeTrapHeight(tr);
    if (hh < 0.45) continue;
    const tipY = GROUND_Y - 58 * hh + 16;
    if (box.r > tr.x + 4 && box.l < tr.x + tr.w - 4) {
      if (box.b > tipY) return die('spike');
      if (box.b > tipY - 13) nearMiss();
    }
  }
  // 暗刺，没预警但位置固定，背板就能过
  for (const hs of world.hiddenSpikes) {
    if (hs.t < 0 && cx > hs.x - 210 && cx < hs.x + hs.w + 40) {
      hs.t = G.time;
      Snd.spikeOut();
      FX.addShake(0.35);
    }
    if (hs.t < 0) continue;
    const hh = clamp((G.time - hs.t) / 0.11, 0, 1);
    if (hh < 0.5) continue;
    const sh = 58 * ease.outCubic(hh);
    const tipY = GROUND_Y - sh + 16;
    if (box.r > hs.x + 4 && box.l < hs.x + hs.w - 4) {
      if (box.b > tipY) return die('spike');
      if (box.b > tipY - 13) nearMiss();
    }
  }

  // 陷阱弹簧，踩上去直接送进上面的刺
  for (const ts of world.trapSprings) {
    const topY = GROUND_Y - 26;
    if (cx > ts.x - 8 && cx < ts.x + ts.w + 8 && player.vy >= 0 && player.prevY <= topY + 8 && player.y >= topY) {
      player.y = topY;
      player.vy = -2180;
      player.onGround = false;
      player.sliding = false;
      player.spinOn = true;
      player.spinT = 0;
      ts.t = G.time;
      Snd.bounce();
      FX.addShake(0.4);
      FX.float(cx, topY - 46, '弹簧！下次跳过它', { color: '#ff9a8a', size: 30 });
      FX.ring(cx, topY, { r0: 8, r1: 80, max: 0.4, color: 'rgba(255,120,90,0.85)', width: 5 });
      return;
    }
  }

  // 压制板，顶部给 5px 余量
  for (const cr of world.crushers) {
    const by = crusherBottom(cr);
    if (box.r > cr.x && box.l < cr.x + cr.w) {
      if (box.t + 5 < by) return die('crusher');
      if (box.t < by + 14) nearMiss();
    }
  }

  // 摇摆锤
  for (const pd of world.pendulums) {
    const hd = pendulumHead(pd);
    const nx = clamp(hd.x, box.l, box.r);
    const ny = clamp(hd.y, box.t, box.b);
    const ddx = hd.x - nx, ddy = hd.y - ny;
    const pr = hd.r * 0.9;
    const pd2 = ddx * ddx + ddy * ddy;
    if (pd2 < pr * pr) return die('pendulum');
    if (pd2 < (pr + 16) * (pr + 16)) nearMiss();
  }
  // 滚石
  for (const rk of world.rollingRocks) {
    if (!rk.alive) continue;
    const nx = clamp(rk.x, box.l, box.r);
    const ny = clamp(rk.y, box.t, box.b);
    const ddx = rk.x - nx, ddy = rk.y - ny;
    const kd2 = ddx * ddx + ddy * ddy;
    const kr = rk.r * 0.9;
    if (kd2 < kr * kr) return die('rock');
    if (kd2 < (kr + 16) * (kr + 16)) nearMiss();
  }
  // 浮空怪：从上方踩下去可以借力弹起，撞到侧面会死
  for (const en of world.enemies) {
    if (en.dead) continue;
    const ey = enemyY(en);
    const nx = clamp(en.x, box.l, box.r);
    const ny = clamp(ey, box.t, box.b);
    const ddx = en.x - nx, ddy = ey - ny;
    const ed2 = ddx * ddx + ddy * ddy;
    const er = en.r * 0.88;
    if (ed2 < er * er) {
      const stomp = player.vy > 60 && player.prevY <= ey + 14;
      // 冲刺撞碎：盾怪唯一的解法（普通怪也能撞）
      if (G.dashT > 0) {
        en.dead = true;
        en.deadT = 0;
        smashReward(en.x, ey - 40, en.armor ? '破盾撞碎' : '撞碎', en.armor ? 15 : 9);
        FX.burst(en.x, ey, 16, {
          vx0: -260, vx1: 260, vy0: -240, vy1: 60,
          life0: 0.25, life1: 0.5, size0: 5, size1: 13, color: 'rgba(140,232,255,0.95)',
        });
        player.vy = Math.min(player.vy, -430);
        return;
      }
      if (stomp && !en.armor) {
        // 踩飞：弹起 + 回补一次补跳 + 掉落金币
        en.dead = true;
        en.deadT = 0;
        player.vy = -1430;
        player.onGround = false;
        player.jumps = 1;
        player.spinOn = true;
        player.spinT = 0;
        if (G.airCd <= 0 && G.airJump < AIR_JUMP_MAX + upLv('djump')) G.airJump++;
        G.coins += 3;
        G.stompN = (G.stompN || 0) + 1;
        Snd.stomp();
        FX.float(en.x + 20, ey - 54, '踩飞！+3 金币', { color: '#a8e86c', size: 25 });
        FX.ring(en.x, ey, { r0: 8, r1: 96, max: 0.42, color: 'rgba(168,232,108,0.92)', width: 5 });
        FX.burst(en.x, ey, 14, {
          vx0: -230, vx1: 230, vy0: -200, vy1: 60,
          life0: 0.25, life1: 0.5, size0: 5, size1: 13, color: 'rgba(168,232,108,0.95)',
        });
        return;
      }
      return die('enemy');
    }
    if (ed2 < (er + 16) * (er + 16)) nearMiss();
  }

  // 落石：落地后砸成低矮碎石堆，跳过去就能躲开
  for (const fr of world.fallingRocks) {
    if (!fr.triggered || fr.gone) continue;
    const cy = fr.landed ? GROUND_Y - 30 : fr.y;
    const rr = (fr.landed ? 30 : fr.r) * 0.9;
    const nx = clamp(fr.x, box.l, box.r);
    const ny = clamp(cy, box.t, box.b);
    const ddx = fr.x - nx, ddy = cy - ny;
    const fd2 = ddx * ddx + ddy * ddy;
    if (fd2 < rr * rr) return die('rock');
    if (fd2 < (rr + 14) * (rr + 14)) nearMiss();
  }

  // 塌陷平台计时
  for (const cm of world.crumbles) {
    if (cm.broken) { cm.fall = (cm.fall || 0) + dt * dt * 2600; continue; }
    if (player.onGround && Math.abs(player.y - cm.top) < 5 && cx > cm.x && cx < cm.x + cm.w) {
      if (cm.t < 0) {
        cm.t = 0;
        Snd.crack();
        FX.float(cm.x + cm.w / 2, cm.top - 44, '要塌了！', { color: '#ffb0a0', size: 24 });
      }
      cm.t += dt;
      if (cm.t > 0.42) {
        cm.broken = true;
        Snd.breakFloor();
        FX.addShake(0.55);
        FX.burst(cx, cm.top + 14, 16, {
          vx0: -220, vx1: 220, vy0: -260, vy1: 40,
          life0: 0.35, life1: 0.75, size0: 7, size1: 17, color: 'rgba(122,92,62,0.95)', grav: 900, spin: 6,
        });
      }
    }
  }

  // 假地面踩踏
  for (const ff of world.fakeFloors) {
    if (ff.broken) continue;
    if (cx > ff.x + 6 && cx < ff.x + ff.w - 6 && player.onGround) {
      ff.t += dt;
      if (ff.t > 0.14) {
        ff.broken = true;
        Snd.breakFloor();
        FX.addShake(0.55);
        FX.flashScreen('#000000', 0.12);
        FX.burst(cx, GROUND_Y + 16, 20, {
          vx0: -200, vx1: 200, vy0: -260, vy1: 40,
          life0: 0.4, life1: 0.85, size0: 8, size1: 18, color: 'rgba(122,92,62,0.95)', grav: 900, spin: 6,
        });
        FX.float(cx, GROUND_Y - 60, '塌了！', { color: '#ffb0a0', size: 26 });
      }
    }
  }

  // 掉坑：稍微离地就算坠落，立刻判死不拖泥带水
  if (!supported && player.y > GROUND_Y + 34) return die('fall');

  // 姿态与动画
  if (player.onGround) {
    player.tilt = player.sliding ? -0.34 : Math.sin(player.run * 2) * 0.055;
  } else {
    player.tilt = clamp(player.vy / 4600, -0.16, 0.15);
  }
  player.run += dt * (player.onGround ? world.speed / 46 : 2.4);
  player.sx += (1 - player.sx) * Math.min(1, dt * 12);
  player.sy += (1 - player.sy) * Math.min(1, dt * 12);

  // 空中前空翻
  if (player.spinOn && !player.onGround) {
    player.spinT += dt;
    player.spin = Math.PI * 2 * ease.outQuad(clamp(player.spinT / 0.62, 0, 1));
  } else if (player.onGround) {
    player.spin = 0;
  }

  // 跑步尘土
  if (player.onGround && !player.sliding) {
    player.dustT -= dt;
    if (player.dustT <= 0) {
      player.dustT = 0.042;
      FX.burst(world.x + PLAYER_X - 34, player.y - rnd(2, 16), 2, {
        vx0: -320, vx1: -150, vy0: -90, vy1: -10,
        life0: 0.22, life1: 0.44, size0: 7, size1: 16, color: 'rgba(255,255,255,0.9)',
      });
    }
  }
}

// 吃到增益，同一种取剩余时间更长的
function applyBuff(key, quiet) {
  const d = BUFF_DEF[key];
  if (!d) return;
  if (key === 'shield') {
    G.shield = Math.min(shieldCap(), G.shield + 1);
  } else if (key === 'revive') {
    G.revive = Math.min(1, (G.revive || 0) + 1);   // 一次性救命，最多攒 1 个
  } else {
    G.buff[key] = Math.max(G.buff[key] || 0, d.dur);
  }
  G.cardPop = { key: key, name: d.name, color: d.color, icon: d.icon, dur: d.dur, t: 0 };
  if (!quiet) {
    Snd.milestone();
    FX.flashScreen(d.color, 0.2);
    FX.ring(world.x + PLAYER_X, player.y - 90, { r0: 16, r1: 130, max: 0.45, color: 'rgba(255,255,255,0.7)', width: 6 });
    FX.burst(world.x + PLAYER_X, player.y - 90, 16, {
      vx0: -220, vx1: 220, vy0: -280, vy1: -40,
      life0: 0.3, life1: 0.65, size0: 5, size1: 13, color: d.color,
    });
  }
}

/* 道具箱：真箱抽 1 张限时增益；宝箱怪会咬人一口（断连击 + 掉金币 + 减速） */
function openCrate(cr) {
  if (cr && cr.mimic) {
    G.wallHit = 1.4;
    G.combo = 0;
    G.comboDelay = 0;
    G.nearStreak = 0;
    const loss = Math.min(G.coins, 25);
    G.coins -= loss;
    G.buff.slow = Math.max(G.buff.slow || 0, 1.2);
    Snd.crack();
    FX.flashScreen('#b06aff', 0.2);
    FX.addShake(0.5);
    FX.float(cr.x, cr.y - 70, '宝箱怪！-' + loss + ' 金币', { color: '#d3a8ff', size: 28 });
    FX.burst(cr.x, cr.y, 18, {
      vx0: -240, vx1: 240, vy0: -260, vy1: 20,
      life0: 0.25, life1: 0.55, size0: 5, size1: 12, color: 'rgba(176,106,255,0.9)',
    });
    return;
  }
  const key = CRATE_POOL[Math.floor(Math.random() * CRATE_POOL.length)];
  applyBuff(key);
  G.crateCount++;
  Snd.clear();
  FX.floats.push({
    x: cr ? cr.x : world.x + PLAYER_X, y: player.y - 170, text: '抽到 ' + BUFF_DEF[key].name,
    life: 0, max: 1.2, color: BUFF_DEF[key].color, size: 28, vy: -60, scale: 1,
  });
}

function die(cause) {
  if (G.state !== 'playing') return;
  // mod 死亡钩子：最早通知（钩子里可 api.revive() 续命、做特效/计数）
  runUgcHook('death', { cause: cause || '' });
  // 小鸡宠物：挺身而出替你挡一刀（坠坑它也够不着）
  if (petEnabled() && cause !== 'fall' && (G.petCd || 0) <= 0) {
    G.petCd = PET_SAVE;
    G.buff.invinc = Math.max(G.buff.invinc, 1.4);
    FX.float(world.x + PLAYER_X + 40, player.y - 230, '小鸡挡刀！', { color: '#ffd34d', size: 26 });
    FX.ring(world.x + PLAYER_X + 26, player.y - 226, { r0: 10, r1: 70, max: 0.4, color: 'rgba(255,225,130,0.9)', width: 5 });
    Snd.crack();
    return;
  }
  // 复活道具：原地起死回生（坠坑也能拉回来）
  if ((G.revive || 0) > 0) {
    G.revive--;
    G.buff.invinc = 2.2;
    if (player.y > GROUND_Y - 6) { player.y = GROUND_Y - 4; player.vy = 0; player.onGround = true; }
    FX.flashScreen('#7de08a', 0.3);
    FX.sfloat(W / 2, 316, '原地复活！', { color: '#7de08a', size: 46, life: 1.4, vy: -10 });
    FX.ring(world.x + PLAYER_X, player.y - 70, { r0: 16, r1: 190, max: 0.55, color: 'rgba(125,224,138,0.9)', width: 8 });
    Snd.charged();
    return;
  }
  // 幽灵穿透：机关伤害直接穿过去（坠坑拦不住，大地不讲道理）
  if (G.buff.ghost > 0 && cause !== 'fall') {
    FX.float(world.x + PLAYER_X + 36, player.y - 196, '穿透！', { color: '#c9a0ff', size: 24 });
    return;
  }
  // 无敌冲刺期间免疫一切
  if (G.buff.invinc > 0) return;
  // 能量冲刺撞碎一切：给金币 + 连击，冲刺期间就是一路碾过去
  if (G.dashT > 0) {
    smashReward(world.x + PLAYER_X + 40, player.y - 170, '撞碎', 6);
    G.energy = Math.min(ENERGY_MAX, G.energy + 2);
    return;
  }
  // 护盾能挡一次，但掉坑不算
  if (G.shield > 0 && cause !== 'fall') {
    G.shield--;
    G.hurtCount++;
    G.buff.invinc = 1.8;
    // 玩家露出破绽：追击者立刻压上来，脱身进度被打掉一截
    if (G.chiyou) {
      G.chiyou.bx += 96;
      G.chiyou.escape = Math.max(0, (G.chiyou.escape || 0) - 0.55);
      G.chiyou.aggro = Math.min(1, (G.chiyou.aggro || 0.35) + 0.35);
    }
    // 悬赏「不失误跑」被中断，里程重新计；身法连击也断
    if (G.bounty && G.bounty.id === 'nohit') G.bounty.base = Math.floor(world.x / PX_PER_M);
    G.nearStreak = 0;
    Snd.crack();
    FX.flashScreen('#8ce8ff', 0.42);
    FX.addShake(0.55);
    FX.float(world.x + PLAYER_X, player.y - 130, '护盾抵挡！', { color: '#8ce8ff', size: 30 });
    FX.ring(world.x + PLAYER_X, player.y - 60, { r0: 24, r1: 170, max: 0.5, color: 'rgba(140,232,255,0.95)', width: 8 });
    return;
  }
  G.state = 'dying';
  G.deathCause = cause;
  if (G.mode === 'ugc') G.ugcNoHit = false;
  player.deadT = 0;
  player.deadRot = 0;
  player.vy = -560;
  player.sliding = false;
  player.spinOn = false;
  Snd.die();
  FX.addShake(1.2);
  FX.flashScreen('#ff5a4a', 0.42);
  FX.slowMo(0.75, 0.32);
  FX.burst(world.x + PLAYER_X, player.y - 70, 26, {
    vx0: -340, vx1: 340, vy0: -460, vy1: -60,
    life0: 0.35, life1: 0.8, size0: 6, size1: 17, color: 'rgba(255,255,255,0.95)', spin: 8,
  });
  FX.burst(world.x + PLAYER_X, player.y - 60, 12, {
    vx0: -280, vx1: 280, vy0: -320, vy1: -40,
    life0: 0.3, life1: 0.65, size0: 5, size1: 14, color: 'rgba(120,214,246,0.95)',
  });
}

/* ---------------- 金币与清理 ---------------- */
function updateCoins(dt) {
  const box = playerBox();
  if (G.petCd > 0) G.petCd = Math.max(0, G.petCd - dt);
  // 金币雨掉落的金币：下坠到可吃高度后停住
  for (const c of world.coins) {
    if (c.vy !== undefined && c.y < GROUND_Y - 100) {
      c.vy += 850 * dt;
      c.y += c.vy * dt;
      if (c.y >= GROUND_Y - 104) { c.y = GROUND_Y - 104; c.vy = undefined; }
    }
  }
  G.comboDelay -= dt;
  // FEVER 期间连击不清零（宽容窗口）
  if (G.comboDelay <= 0 && G.feverT <= 0) G.combo = 0;
  const mag = (world.pTune && world.pTune.magnet) || 0;
  // 祭坛词条「磁力核心」直接加拾取半径
  const range = G.feverT > 0 ? 720
    : Math.max(G.buff.magnet > 0 ? 175 : (petEnabled() ? 130 : 44), mag) + 45 * upLv('magnet');
  const mul = (G.buff.x2 > 0 ? 2 : 1) * (G.buff.frenzy > 0 ? 2 : 1) * (G.galeT > 0 ? 2 : 1);

  // 关卡增益道具拾取
  for (const p of world.powers) {
    if (p.taken) continue;
    const nx = clamp(p.x, box.l, box.r);
    const ny = clamp(p.y, box.t, box.b);
    const dx = p.x - nx, dy = p.y - ny;
    if (dx * dx + dy * dy < 62 * 62) {
      if (p.type === 'knife') {
        // 飞刀：BOSS 战的攻击手段
        p.taken = true;
        G.knives = Math.min(12, (G.knives || 0) + 6);
        Snd.star(2);
        FX.float(p.x, p.y - 60, '飞刀 +6', { color: '#8ce8ff', size: 26 });
        continue;
      }
      p.taken = true;
      applyBuff(p.type);
    }
  }

  // 祭坛：碰到就亮出三张牌（世界随即冻结）
  for (const al of world.altars) {
    if (al.taken) continue;
    const nx = clamp(al.x, box.l, box.r);
    const ny = clamp(al.y, box.t, box.b);
    const dx = al.x - nx, dy = al.y - ny;
    if (dx * dx + dy * dy < 72 * 72) {
      al.taken = true;
      openAltar();
      return;
    }
  }

  // 道具箱
  for (const cr of world.crates) {
    if (cr.taken) continue;
    const nx = clamp(cr.x, box.l, box.r);
    const ny = clamp(cr.y, box.t, box.b);
    const dx = cr.x - nx, dy = cr.y - ny;
    if (dx * dx + dy * dy < 64 * 64) {
      cr.taken = true;
      openCrate(cr);
    }
  }

  for (const c of world.coins) {
    if (c.taken) continue;
    const nx = clamp(c.x, box.l, box.r);
    const ny = clamp(c.y, box.t, box.b);
    const dx = c.x - nx, dy = c.y - ny;
    if (dx * dx + dy * dy < range * range) {
      c.taken = true;
      // 「贪金护符」：金币收益 +20%/级
      G.coins += Math.max(1, Math.round(mul * (1 + 0.2 * upLv('coin'))));
      G.combo++;
      if (G.combo > G.maxCombo) G.maxCombo = G.combo;
      G.comboDelay = 0.7 + 0.2 * upLv('combo');
      bountyAdd('coin', 1);
      bountyAdd('combo', 1);
      runUgcHook('coin', { x: c.x, y: c.y, combo: G.combo });
      // 金币攒冲刺能量，翻倍时攒得更快；「超导线圈」再乘一道
      if (G.dashT <= 0 && G.energy < ENERGY_MAX) {
        G.energy = Math.min(ENERGY_MAX, G.energy + (G.buff.x2 > 0 ? 6 : 3) * (1 + 0.6 * upLv('energy')));
        if (G.energy >= ENERGY_MAX) {
          Snd.charged();
          FX.float(world.x + PLAYER_X + 20, player.y - 182, '冲刺就绪！', { color: '#8ce8ff', size: 26 });
        }
      }
      Snd.coin(G.combo - 1);
      // FEVER 触发：连击攒满即开启全屏吸金狂欢
      if (G.mode === 'endless' && G.feverT <= 0 && G.combo >= G.feverNeed) {
        G.feverT = 5;
        G.feverNeed += 10;   // 越玩要求越高，防无限狂欢
        Snd.fever();
        FX.flashScreen('#ffd34d', 0.24);
        FX.addShake(0.28);
        FX.sfloat(W / 2, 296, 'FEVER !!', { color: '#ffd34d', size: 58, life: 1.5, vy: -12 });
        FX.sfloat(W / 2, 352, '全场金币吸过来', { color: 'rgba(255,255,255,0.95)', size: 26, life: 1.4, vy: -8 });
      }
      // 连击里程碑：每 10 连击白送一个限时增益
      if (G.combo >= 10 && Math.floor(G.combo / 10) > G.comboMark) {
        G.comboMark = Math.floor(G.combo / 10);
        const k = ['magnet', 'x2', 'sprint'][Math.floor(Math.random() * 3)];
        applyBuff(k, true);
        FX.sfloat(W / 2, 360, G.combo + ' 连击！', { color: '#ffe066', size: 40, life: 1.1, vy: -40 });
        Snd.milestone();
      }
      FX.burst(c.x, c.y, 10, {
        vx0: -180, vx1: 180, vy0: -260, vy1: -40,
        life0: 0.25, life1: 0.55, size0: 5, size1: 13, color: 'rgba(255,206,84,0.95)',
      });
      FX.float(c.x, c.y - 26, '+1', { color: '#ffe066', size: 24, life: 0.7, vy: -120 });
    }
  }
  // 假金币：吃到扣分并炸黑烟
  for (const c of world.fakeCoins) {
    if (c.taken) continue;
    const nx = clamp(c.x, box.l, box.r);
    const ny = clamp(c.y, box.t, box.b);
    const dx = c.x - nx, dy = c.y - ny;
    if (dx * dx + dy * dy < 44 * 44) {
      c.taken = true;
      G.coins = Math.max(0, G.coins - 1);
      Snd.crack();
      FX.addShake(0.6);
      FX.flashScreen('#5a1020', 0.22);
      FX.float(c.x, c.y - 26, '-1', { color: '#ff8a8a', size: 26 });
      FX.burst(c.x, c.y, 14, {
        vx0: -200, vx1: 200, vy0: -200, vy1: 20,
        life0: 0.3, life1: 0.6, size0: 6, size1: 15, color: 'rgba(96,44,64,0.95)',
      });
    }
  }

  const cutoff = world.x - 320;
  if (world.coins.length) world.coins = world.coins.filter(c => !c.taken && c.x > cutoff);
  if (world.fakeCoins.length) world.fakeCoins = world.fakeCoins.filter(c => !c.taken && c.x > cutoff);
  if (world.pendulums.length) world.pendulums = world.pendulums.filter(s => s.x > cutoff);
  if (world.boosters.length) world.boosters = world.boosters.filter(s => s.x + s.w > cutoff);
  if (world.rollingRocks.length) world.rollingRocks = world.rollingRocks.filter(s => s.alive && s.x > cutoff);
  if (world.fallingRocks.length) world.fallingRocks = world.fallingRocks.filter(s => s.x > cutoff);
  if (world.hiddenSpikes.length) world.hiddenSpikes = world.hiddenSpikes.filter(s => s.x + s.w > cutoff);
  if (world.trapSprings.length) world.trapSprings = world.trapSprings.filter(s => s.x + s.w > cutoff);
  if (world.crushers.length) world.crushers = world.crushers.filter(s => s.x + s.w > cutoff);
  if (world.crumbles.length) world.crumbles = world.crumbles.filter(s => s.x + s.w > cutoff);
  if (world.chaseTriggers.length) world.chaseTriggers = world.chaseTriggers.filter(s => s.x > cutoff);
  if (world.powers.length) world.powers = world.powers.filter(s => !s.taken && s.x > cutoff);
  if (world.crates.length) world.crates = world.crates.filter(s => !s.taken && s.x > cutoff);
  if (world.altars.length) world.altars = world.altars.filter(s => !s.taken && s.x > cutoff);
  if (world.signs.length) world.signs = world.signs.filter(s => s.x > cutoff);
  if (world.platforms.length) world.platforms = world.platforms.filter(p => p.x + p.w > cutoff);
  if (world.gaps.length) world.gaps = world.gaps.filter(g => g.x + g.w > cutoff);
  if (world.fakeFloors.length) world.fakeFloors = world.fakeFloors.filter(f => f.x + f.w > cutoff);
  if (world.spikeTraps.length) world.spikeTraps = world.spikeTraps.filter(s => s.x + s.w > cutoff);
  if (world.lowBars.length) world.lowBars = world.lowBars.filter(s => s.x + s.w > cutoff);
  if (world.ceilSpikes.length) world.ceilSpikes = world.ceilSpikes.filter(s => s.x + s.w > cutoff);
  if (world.bouncePads.length) world.bouncePads = world.bouncePads.filter(s => s.x + s.w > cutoff);
  if (world.fallingRocks.length) world.fallingRocks = world.fallingRocks.filter(f => !f.gone);
  if (world.enemies.length) {
    world.enemies = world.enemies.filter(e => e.x > cutoff && (!e.dead || (e.deadT || 0) < 0.4));
  }
  if (world.movingPlatforms.length) world.movingPlatforms = world.movingPlatforms.filter(s => s.x + s.w > cutoff);
}

// ---- 主更新 ----
function update(dt) {
  updateTweens(dt);
  updateTweens(0);

  if (G.state === 'playing') {
    G.time += dt;
    if (G.boostT > 0) G.boostT -= dt;
    if (G.cardRevealT < 3.4) G.cardRevealT += dt;
    if (G.mode === 'endless') {
      // 基础速度渐近增长：前期温和、越往后增速越慢，平滑逼近上限（旧线性 0.0075 跑到 1000m 就 705px/s，太快）
      // 500m≈449 → 1000m≈523 → 2000m≈640 → 4000m≈741，永不超过 MAX_SPEED
      G.baseSpeed = BASE_SPEED + (MAX_SPEED - BASE_SPEED) * (1 - Math.exp(-world.x / 100000));
      generateAhead();
      const m = Math.floor(world.x / PX_PER_M);
      if (m >= G.nextMile) {
        G.nextMile += 100;
        FX.sfloat(W / 2, 400, m + ' m', { color: '#ffe066', size: 58, life: 1.2, vy: -30 });
        FX.flashScreen('#ffffff', 0.16);
        Snd.milestone();
      }
    }
    let spd = (G.baseSpeed || BASE_SPEED) + (G.boostT > 0 ? 190 : 0);
    // 速度增益叠乘：冲刺单独放宽，其余满叠也封顶——不封顶后期 1300+px/s 一跳飞 20m+，机关全部形同虚设
    let gain = 1;
    if (G.buff.sprint > 0) gain *= 1.25;
    if (G.buff.frenzy > 0) gain *= 1.18;
    gain *= 1 + Math.min(0.12, G.combo * 0.006);   // 连击越高跑得越快，最多 +12%
    if (G.dashT > 0) gain *= (world.pTune && world.pTune.dashSpd) || 1.55;   // 能量冲刺（倍率可被 mod 的 dashSpd 改）
    if (G.beltMul && G.beltMul > 1) gain *= G.beltMul;   // 传送带加速
    gain = Math.min(gain, (G.dashT > 0) ? 1.6 : 1.32);
    // 祭坛「疾风羽」与疾风段：额外的速度增益不吃上面的封顶，单独叠
    if (upLv('speed')) gain *= 1 + 0.05 * upLv('speed');
    if (G.galeT > 0) gain *= 1.22;
    if (G.buff.slow > 0) gain *= 0.85;   // 减速不吃上限
    if (G.beltMul && G.beltMul < 1) gain *= G.beltMul;   // 传送带减速
    if (G.weather && G.weather.id === 'sand') gain *= 0.92;   // 沙尘暴顶风
    if (G.acidUnder) gain *= 0.78;                            // 踩在酸雨腐蚀区里
    spd *= gain;
    // BOSS 战照常奔跑：边跑酷边对决，只稍微减速给反应空间
    if (G.boss) spd *= 0.96;
    // mod 改的玩家速度（api.player({ speedMul })）
    if (world.pTune && world.pTune.speedMul) spd *= world.pTune.speedMul;
    world.speed = spd;
    world.x += world.speed * dt;

    // 新机关（火焰/传送带/气流）、地刺浪与昼夜门
    updateNewHazards(dt);
    updateQuakes(dt);
    // 昼夜推进 + 命运之门（无尽专属）
    if (G.mode === 'endless') {
      G.dayT += dt;
      updateGates(dt);
      updateWeather(dt);
      updateBounty();
      updateSurge(dt);
      updateWorldEvents(dt);      // 月面段 / 疾风段
      if (G.escapeFlash > 0) G.escapeFlash = Math.max(0, G.escapeFlash - dt);
      if (G.wallHit > 0) G.wallHit = Math.max(0, G.wallHit - dt);
      if (G.feverT > 0) {
        G.feverT = Math.max(0, G.feverT - dt);
        if (G.feverT === 0) {
          FX.sburst(W / 2, 300, 26, {
            vx0: -260, vx1: 260, vy0: -160, vy1: 160,
            life0: 0.4, life1: 0.9, size0: 5, size1: 12, color: '#ffd34d',
          });
        }
      }
      // 入夜 / 天亮提示
      const ph = nightAmount() > 0.5 ? 1 : 0;
      if (ph !== G.dayMark) {
        G.dayMark = ph;
        if (ph === 1) FX.sfloat(W / 2, 260, '夜幕降临…机关更难看清', { color: '#a8c4f2', size: 30, life: 1.6, vy: -8 });
        else if (G.dayT > 20) FX.sfloat(W / 2, 260, '天亮了', { color: '#ffe066', size: 30, life: 1.4, vy: -8 });
      }
      // 萤火虫：夜晚的机关照明提示
      if (nightAmount() > 0.4 && Math.random() < 0.14) {
        FX.burst(world.x + rnd(60, W - 20), rnd(GROUND_Y - 330, GROUND_Y - 24), 1, {
          vx0: -30, vx1: 10, vy0: -16, vy1: 6,
          life0: 1.4, life1: 2.4, size0: 3, size1: 6, color: '#d8f28a', glow: true,
        });
      }
      // 金币雨：道具生效期间不停掉金币（按速度预测落点，快跑时不会撒到身后）
      if (G.buff.rain > 0 && Math.random() < 0.15) {
        const vy0 = rnd(120, 240);
        // 从 y=-30 下坠到可吃高度 GROUND_Y-104 的精确时间（解 0.5*a*t^2+v0*t=h）
        const tFall = (-vy0 + Math.sqrt(vy0 * vy0 + 2 * 850 * (GROUND_Y - 74))) / 850;
        world.coins.push({
          x: world.x + PLAYER_X + world.speed * tFall + rnd(0, 320), y: -30,
          phase: rnd(0, 6.28), taken: false, vy: vy0,
        });
      }
      // 祭坛：每 320 米一座，碰到就暂停世界三选一（本局永久强化）
      const altM = Math.floor(world.x / PX_PER_M);
      if (altM >= G.altarNextM) {
        G.altarNextM = altM + 320;
        world.altars.push({ x: world.x + W + 320, y: GROUND_Y - 118, taken: false, ph: rnd(0, 6.28) });
      }
      // 无尽模式：每 130~220m 在地面刷一枚发光道具球（含稀有复活与飞刀）
      if (world.x + W * 2 > (world.nextPowerX || 2600)) {
        const r = Math.random();
        const type = r < 0.12 ? 'revive' : r < 0.34 ? 'knife' : r < 0.52 ? 'ghost' : r < 0.68 ? 'rain'
          : ['magnet', 'invinc', 'djump', 'x2', 'sprint', 'shield'][Math.floor(Math.random() * 6)];
        world.powers.push({ x: world.nextPowerX, y: GROUND_Y - 116, type, taken: false });
        world.nextPowerX += 6500 + Math.random() * 4500;
      }
    }

    // 无尽模式：每 200m 一场蚩尤 BOSS 战，平时靠他放技能施压
    // 时停（world.tstop / api.timestop）：冻结蚩尤、BOSS 与弹幕，玩家照常跑跳
    if (world.tstop > 0) world.tstop = Math.max(0, world.tstop - dt);
    if (G.mode === 'endless' && !G.boss) {
      const nm = (G.nextBossM || 200);
      if (world.x >= nm * PX_PER_M) {
        G.nextBossM = nm + 200;
        G.chiyou = null;
        startBoss();
      } else if (!(world.tstop > 0)) {
        updateChiyou(dt);
      }
    }
    if (G.boss && !(world.tstop > 0)) updateBoss(dt);
    // 自定义模式：定义里 chase:true 时蚩尤照常追击（无 BOSS 战、无原版机关生成）
    if (G.mode === 'custom' && G.customMode && G.customMode.chase && !G.boss && !(world.tstop > 0)) updateChiyou(dt);

    // 被动补跳冷却
    if (G.airCd > 0) {
      G.airCd = Math.max(0, G.airCd - dt);
      if (G.airCd <= 0) {
        G.airJump = AIR_JUMP_MAX + upLv('djump');
        FX.float(world.x + PLAYER_X + 18, player.y - 172, '补跳就绪', { color: '#8ce8ff', size: 23 });
        Snd.charged();
      }
    }
    // 能量冲刺计时
    if (G.dashT > 0) {
      G.dashT = Math.max(0, G.dashT - dt);
      if (G.dashT <= 0) FX.speedLines = 0;
    }
    if (G.mode === 'level') {
      G.runTime += dt;
      if (world.speed > G.maxSpeed) G.maxSpeed = world.speed;
      // 累计型目标可以中途达成，即时给反馈
      if (!G.objDone && G.objective && objectiveIsAccum(G.objective) && objectiveDone(G.objective)) {
        G.objDone = true;
        G.objFlash = 2.4;
        Snd.milestone();
        FX.flashScreen(G.objective.color, 0.2);
        FX.sfloat(W / 2, 232, '目标达成！', { color: G.objective.color, size: 44, life: 1.3, vy: -14 });
        FX.ring(world.x + PLAYER_X, player.y - 90, { r0: 20, r1: 190, max: 0.5, color: 'rgba(255,255,255,0.85)', width: 7 });
      }
      if (G.objFlash > 0) G.objFlash = Math.max(0, G.objFlash - dt);
    }
    G.lastDt = dt;
    if (G.mode === 'ugc' && world.x >= G.levelEndX) {
      endUgcLevel();
    } else if (G.mode === 'level' && world.x >= G.levelEndX) {
      endLevel();
    } else {
      updateHazards(dt);
      updateUgcMods(dt);
      // 自定义模式：模式自己的 onFrame 每帧驱动（排在 mod onUpdate 之后，玩法层）
      if (G.mode === 'custom' && world._cmFrame) runUgcHook('frame', { dt }, [world._cmFrame]);
      updatePlayer(dt);
      updateCoins(dt);
      if (!player.onGround) G.airTime += dt;
    }
    FX.speedLines = clamp((world.speed - BASE_SPEED * 1.35) / (MAX_SPEED - BASE_SPEED * 1.35), 0, 1);
    FX.update(dt);
  } else if (G.state === 'dying') {
    G.time += dt;
    player.deadT += dt;
    player.vy += GRAVITY * dt;
    player.y += player.vy * dt;
    player.deadRot += dt * 7.5;
    world.x += world.speed * dt * Math.max(0, 1 - player.deadT * 3);
    FX.update(dt);
    if (player.deadT > 1.0) {
      if (G.mode === 'endless') {
        const m = Math.floor(world.x / PX_PER_M);
        if (m > G.best) {
          G.best = m;
          try { localStorage.setItem('jiling_best', String(m)); } catch (e) {}
        }
      } else {
        Snd.fail();
      }
      G.state = 'gameover';
      Music.duck(0.08);
    }
  } else if (G.state === 'altar') {
    // 祭坛三选一：世界冻结（时间与粒子仍在走，画面不死），等玩家点一张牌
    G.time += dt;
    G.altarT += dt;
    // 兜底：长时间不选就自动拿第一张，避免玩家被卡在牌面前
    if (G.altarT > 14 && G.altar && G.altar.picks.length) pickUpgrade(G.altar.picks[0].id);
    FX.update(dt);
  } else if (G.state === 'levelfail') {
    // 目标未达成：世界缓慢停下，粒子继续
    G.time += dt;
    G.clearAnim += dt;
    world.speed = Math.max(0, world.speed - dt * 1100);
    world.x += world.speed * dt;
    updatePlayer(dt);
    FX.update(dt);
  } else if (G.state === 'levelclear') {
    G.time += dt;
    G.clearAnim += dt;
    world.speed = Math.max(0, world.speed - dt * 1100);
    world.x += world.speed * dt;
    updatePlayer(dt);
    FX.update(dt);
    if (G.clearAnim < 0.9 && Math.random() < 0.35) {
      FX.sburst(rnd(40, W - 40), rnd(120, 380), 2, {
        vx0: -120, vx1: 120, vy0: -40, vy1: 140,
        life0: 0.6, life1: 1.3, size0: 5, size1: 13,
        color: ['#ffd34d', '#7de08a', '#8fc4f2', '#ffffff'][Math.floor(Math.random() * 4)],
      });
    }
    if (G.clearAnim > 0.35 && G.clearAnim - dt <= 0.35) Snd.star(0);
    if (G.clearAnim > 0.57 && G.clearAnim - dt <= 0.57 && G.clearStars >= 2) Snd.star(1);
    if (G.clearAnim > 0.79 && G.clearAnim - dt <= 0.79 && G.clearStars >= 3) Snd.star(2);
  } else if (G.state === 'menu' || G.state === 'levels' || G.state === 'skins') {
    G.time += dt;
    if (G.state === 'menu') {
      player.run += dt * 7;
      player.tilt = Math.sin(player.run * 2) * 0.05;
      if (Math.random() < 0.3) {
        FX.burst(world.x + PLAYER_X - 34, GROUND_Y - rnd(2, 14), 1, {
          vx0: -240, vx1: -130, vy0: -70, vy1: -10,
          life0: 0.2, life1: 0.42, size0: 6, size1: 13, color: 'rgba(255,255,255,0.85)',
        });
      }
    }
    FX.update(dt);
  } else {
    G.time += dt;
    FX.update(dt);
  }
}

function checkMissedLevel() {}
