'use strict';

/* 界面层：像素组件、HUD、菜单、关卡选择、暂停、结算 */

const PAL = {
  edge: '#0b1120',
  panel: '#1b2436',
  panelHi: '#31405c',
  line: 'rgba(140,175,225,0.22)',
  text: '#ffffff',
};

const SKIN = {
  gold:  { main: '#f2b13a', hi: '#ffe08a', dark: '#b8741a', deep: '#7a4808', edge: '#4a2a04', text: '#3d2404' },
  blue:  { main: '#4a8fd4', hi: '#a8d8ff', dark: '#2c6199', deep: '#1b4170', edge: '#0e2444', text: '#08213c' },
  green: { main: '#63b344', hi: '#b6ec92', dark: '#3e8028', deep: '#265c16', edge: '#12300a', text: '#0d2c06' },
  slate: { main: '#38455e', hi: '#6b7c9c', dark: '#243047', deep: '#161f30', edge: '#0b1120', text: '#dfe8f5' },
  red:   { main: '#d84a3c', hi: '#ff9a8c', dark: '#a02c22', deep: '#6c1a12', edge: '#3c0c08', text: '#2e0806' },
};

// ---- 像素基元 ----
function pxRect(x, y, w, h, c) {
  x = Math.round(x); y = Math.round(y); w = Math.round(w); h = Math.round(h);
  ctx.beginPath();
  ctx.moveTo(x + c, y);
  ctx.lineTo(x + w - c, y);
  ctx.lineTo(x + w, y + c);
  ctx.lineTo(x + w, y + h - c);
  ctx.lineTo(x + w - c, y + h);
  ctx.lineTo(x + c, y + h);
  ctx.lineTo(x, y + h - c);
  ctx.lineTo(x, y + c);
  ctx.closePath();
}

function pxPanel(x, y, w, h, opt) {
  opt = opt || {};
  const cut = opt.cut || 10;
  pxRect(x + 5, y + 6, w, h, cut);
  ctx.fillStyle = 'rgba(6,10,18,0.45)';
  ctx.fill();
  pxRect(x, y, w, h, cut);
  ctx.fillStyle = opt.edge || PAL.edge;
  ctx.fill();
  pxRect(x + 3, y + 3, w - 6, h - 6, Math.max(2, cut - 2));
  ctx.fillStyle = opt.bg || PAL.panel;
  ctx.fill();
  ctx.fillStyle = opt.hi || PAL.panelHi;
  ctx.fillRect(x + cut, y + 3, w - cut * 2, 3);
  ctx.fillStyle = 'rgba(0,0,0,0.4)';
  ctx.fillRect(x + cut, y + h - 6, w - cut * 2, 3);
  pxRect(x + 7, y + 7, w - 14, h - 14, Math.max(2, cut - 5));
  ctx.lineWidth = 2;
  ctx.strokeStyle = opt.line || PAL.line;
  ctx.stroke();
  // 四角像素铆钉
  ctx.fillStyle = opt.rivet || 'rgba(150,185,235,0.45)';
  ctx.fillRect(x + 8, y + 8, 4, 4);
  ctx.fillRect(x + w - 12, y + 8, 4, 4);
  ctx.fillRect(x + 8, y + h - 12, 4, 4);
  ctx.fillRect(x + w - 12, y + h - 12, 4, 4);
}

function pxButton(b, pressed) {
  const k = SKIN[b.kind || 'gold'];
  const cut = b.cut || 12;
  const y = b.y + (pressed ? 4 : 0);
  if (!pressed) {
    pxRect(b.x, b.y + 7, b.w, b.h, cut);
    ctx.fillStyle = k.deep;
    ctx.fill();
  }
  pxRect(b.x, y, b.w, b.h, cut);
  ctx.fillStyle = k.main;
  ctx.fill();
  // 上高光 / 下暗面
  ctx.fillStyle = k.hi;
  ctx.fillRect(b.x + cut, y + 3, b.w - cut * 2, 4);
  ctx.fillRect(b.x + 8, y + 7, b.w - 16, 3);
  ctx.fillStyle = k.dark;
  ctx.fillRect(b.x + cut, y + b.h - 8, b.w - cut * 2, 5);
  ctx.fillStyle = 'rgba(0,0,0,0.22)';
  ctx.fillRect(b.x + 8, y + b.h - 12, b.w - 16, 3);
  pxRect(b.x + 1, y + 1, b.w - 2, b.h - 2, cut);
  ctx.lineWidth = 3;
  ctx.strokeStyle = k.edge;
  ctx.stroke();
  if (b.glow) {
    pxRect(b.x - 4, y - 4, b.w + 8, b.h + 8, cut + 3);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(255,228,140,' + (0.28 + 0.32 * Math.sin(G.time * 4.5)) + ')';
    ctx.stroke();
  }
  if (b.icon) b.icon(b.x + b.w / 2, y + b.h * 0.36, Math.min(b.h * 0.3, 34));
  if (b.label) {
    const size = b.labelSize || Math.min(38, b.h * 0.40);
    pxText(b.label, b.x + b.w / 2, b.sub ? y + b.h * 0.40 : y + b.h / 2 + 1, size, k.text, k.hi);
  }
  if (b.sub) pxText(b.sub, b.x + b.w / 2, y + b.h * 0.73, Math.min(21, b.h * 0.21), 'rgba(255,255,255,0.92)', k.dark);
}

/* 模式卡片：左图标 + 标题 + 副标题 + 右箭头 */
function pxCard(b, pressed) {
  const k = SKIN[b.kind || 'gold'];
  const cut = 14;
  const y = b.y + (pressed ? 3 : 0);
  if (!pressed) {
    pxRect(b.x, b.y + 7, b.w, b.h, cut);
    ctx.fillStyle = k.deep;
    ctx.fill();
  }
  pxRect(b.x, y, b.w, b.h, cut);
  ctx.fillStyle = k.main;
  ctx.fill();
  ctx.fillStyle = k.hi;
  ctx.fillRect(b.x + cut, y + 3, b.w - cut * 2, 4);
  ctx.fillStyle = k.dark;
  ctx.fillRect(b.x + cut, y + b.h - 8, b.w - cut * 2, 5);
  pxRect(b.x + 1, y + 1, b.w - 2, b.h - 2, cut);
  ctx.lineWidth = 3;
  ctx.strokeStyle = k.edge;
  ctx.stroke();
  if (b.glow) {
    pxRect(b.x - 4, y - 4, b.w + 8, b.h + 8, cut + 3);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(255,228,140,' + (0.26 + 0.3 * Math.sin(G.time * 4.5)) + ')';
    ctx.stroke();
  }
  // 图标圆盘
  const icx = b.x + 56;
  const icy = y + b.h / 2;
  ctx.beginPath();
  ctx.arc(icx, icy, 34, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(0,0,0,0.24)';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(icx, icy - 2, 34, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(255,255,255,0.18)';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(icx, icy - 2, 31, Math.PI * 1.05, Math.PI * 1.95);
  ctx.lineWidth = 4;
  ctx.strokeStyle = k.hi;
  ctx.stroke();
  if (b.icon) b.icon(icx, icy - 2, 24);
  // 文字
  pxText(b.label, b.x + 106, icy - 15, 31, k.text, k.hi, 'left');
  pxText(b.sub, b.x + 106, icy + 19, 18, 'rgba(255,255,255,0.92)', k.dark, 'left');
  // 右箭头
  const ax = b.x + b.w - 42;
  ctx.globalAlpha = pressed ? 0.4 : 0.62;
  ctx.fillStyle = k.text;
  ctx.beginPath();
  ctx.moveTo(ax, icy - 15);
  ctx.lineTo(ax + 16, icy);
  ctx.lineTo(ax, icy + 15);
  ctx.closePath();
  ctx.fill();
  ctx.globalAlpha = 1;
}

function pxText(text, x, y, size, fill, stroke, align) {
  ctx.font = 'bold ' + Math.round(size) + 'px "Microsoft YaHei", "PingFang SC", sans-serif';
  ctx.textAlign = align || 'center';
  ctx.textBaseline = 'middle';
  ctx.lineJoin = 'miter';
  ctx.miterLimit = 2;
  ctx.lineWidth = Math.max(3, Math.round(size * 0.24));
  ctx.strokeStyle = stroke || 'rgba(10,18,32,0.92)';
  ctx.strokeText(text, x, y);
  ctx.fillStyle = fill;
  ctx.fillText(text, x, y);
}

function pxNum(text, x, y, size, fill, stroke, align) {
  ctx.font = 'bold ' + Math.round(size) + 'px Consolas, "Courier New", monospace';
  ctx.textAlign = align || 'left';
  ctx.textBaseline = 'middle';
  ctx.lineWidth = Math.max(3, Math.round(size * 0.22));
  ctx.strokeStyle = stroke || 'rgba(10,18,32,0.92)';
  ctx.strokeText(text, x, y);
  ctx.fillStyle = fill;
  ctx.fillText(text, x, y);
}

function pxStar(cx, cy, r, color) {
  ctx.save();
  ctx.translate(Math.round(cx), Math.round(cy));
  ctx.beginPath();
  for (let i = 0; i < 10; i++) {
    const ang = -Math.PI / 2 + i * Math.PI / 5;
    const rr = i % 2 === 0 ? r : r * 0.45;
    if (i === 0) ctx.moveTo(Math.cos(ang) * rr, Math.sin(ang) * rr);
    else ctx.lineTo(Math.cos(ang) * rr, Math.sin(ang) * rr);
  }
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
  ctx.restore();
}

// ---- 图标 ----
const ICO = {
  play: (cx, cy, s) => {
    ctx.fillStyle = 'rgba(61,36,4,0.9)';
    ctx.beginPath();
    ctx.moveTo(cx - s * 0.42, cy - s * 0.6);
    ctx.lineTo(cx + s * 0.66, cy);
    ctx.lineTo(cx - s * 0.42, cy + s * 0.6);
    ctx.closePath();
    ctx.fill();
  },
  grid: (cx, cy, s) => {
    ctx.fillStyle = 'rgba(8,33,60,0.9)';
    const u = s * 0.42;
    for (let i = 0; i < 2; i++) {
      for (let j = 0; j < 2; j++) {
        ctx.fillRect(cx - u - 3 + i * (u + 6), cy - u - 3 + j * (u + 6), u, u);
      }
    }
  },
  pause: () => {
    ctx.fillStyle = '#fff';
    ctx.fillRect(-13, -15, 10, 30);
    ctx.fillRect(4, -15, 10, 30);
  },
  /* 八分音符（BGM 切歌按钮） */
  music: (cx, cy, s) => {
    ctx.strokeStyle = '#fff';
    ctx.fillStyle = '#fff';
    ctx.lineCap = 'round';
    ctx.lineWidth = Math.max(2.5, s * 0.18);
    ctx.beginPath();
    ctx.moveTo(cx + s * 0.42, cy - s * 0.72);
    ctx.lineTo(cx + s * 0.42, cy + s * 0.2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(cx + s * 0.42, cy - s * 0.72);
    ctx.quadraticCurveTo(cx + s * 0.95, cy - s * 0.5, cx + s * 0.45, cy - s * 0.08);
    ctx.stroke();
    ctx.beginPath();
    ctx.ellipse(cx + s * 0.12, cy + s * 0.38, s * 0.36, s * 0.26, -0.35, 0, Math.PI * 2);
    ctx.fill();
  },
  /* 创造中心：四角星光 */
  creative: (cx, cy, s) => {
    ctx.fillStyle = '#dfe8f5';
    ctx.beginPath();
    ctx.moveTo(cx, cy - s);
    ctx.lineTo(cx + s * 0.22, cy - s * 0.22);
    ctx.lineTo(cx + s, cy);
    ctx.lineTo(cx + s * 0.22, cy + s * 0.22);
    ctx.lineTo(cx, cy + s);
    ctx.lineTo(cx - s * 0.22, cy + s * 0.22);
    ctx.lineTo(cx - s, cy);
    ctx.lineTo(cx - s * 0.22, cy - s * 0.22);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + s * 0.72, cy - s * 0.72, s * 0.15, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx - s * 0.66, cy + s * 0.6, s * 0.1, 0, Math.PI * 2);
    ctx.fill();
  },
  sound: () => {
    ctx.fillStyle = '#fff';
    ctx.fillRect(-15, -6, 8, 12);
    ctx.beginPath();
    ctx.moveTo(-7, -6);
    ctx.lineTo(2, -15);
    ctx.lineTo(2, 15);
    ctx.lineTo(-7, 6);
    ctx.closePath();
    ctx.fill();
    if (Snd.on) {
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(3, 0, 9, -0.95, 0.95);
      ctx.arc(3, 0, 15, -0.95, 0.95);
      ctx.stroke();
    } else {
      ctx.strokeStyle = '#ff8a8a';
      ctx.lineWidth = 4;
      ctx.beginPath();
      ctx.moveTo(7, -11);
      ctx.lineTo(20, 11);
      ctx.moveTo(20, -11);
      ctx.lineTo(7, 11);
      ctx.stroke();
    }
  },
};

// ---- 目标徽章 ----
const OBJ_CH = {
  reach: '旗', coins: '金', time: '时', combo: '连', nohit: '盾',
  crate: '箱', pad: '弹', jump: '跳', slide: '滑', air: '空',
};

function objBadge(cx, cy, r, obj) {
  cx = Math.round(cx); cy = Math.round(cy);
  ctx.beginPath();
  ctx.arc(cx, cy + 2, r, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(6,10,18,0.5)';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fillStyle = obj.color;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, r - 2, 0, Math.PI * 2);
  ctx.lineWidth = 2;
  ctx.strokeStyle = 'rgba(255,255,255,0.55)';
  ctx.stroke();
  ctx.font = 'bold ' + Math.round(r * 1.22) + 'px "Microsoft YaHei", "PingFang SC", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = 'rgba(20,30,48,0.92)';
  ctx.fillText(OBJ_CH[obj.id] || '?', cx, cy + 1);
}

// ---- 小方按钮 ----
function pxSquareBtn(x, y, size, pressed, icon, dim) {
  const press = pressed ? 3 : 0;
  const k = SKIN.slate;
  if (!pressed) {
    pxRect(x, y + 4, size, size, 12);
    ctx.fillStyle = k.deep;
    ctx.fill();
  }
  pxRect(x, y + press, size, size, 12);
  ctx.fillStyle = dim ? 'rgba(30,40,58,0.75)' : 'rgba(56,69,94,0.92)';
  ctx.fill();
  ctx.fillStyle = k.hi;
  ctx.fillRect(x + 12, y + press + 3, size - 24, 3);
  pxRect(x + 1, y + press + 1, size - 2, size - 2, 12);
  ctx.lineWidth = 3;
  ctx.strokeStyle = k.edge;
  ctx.stroke();
  ctx.save();
  ctx.translate(x + size / 2, y + press + size / 2);
  icon();
  ctx.restore();
}

/* 无尽模式专属 HUD：追击脱身进度 / 天气 / 悬赏 / 陷阱风暴 */
function drawEndlessHud() {
  // ── 追击脱身：跑得好进度涨，被追上就掉 ──
  const cy = G.chiyou;
  if (cy && !G.boss) {
    if (cy.lost) {
      // 刚甩掉的那一刻高亮一下，之后转入冷静的倒计时
      const hot = (G.escapeFlash || 0) > 0;
      const t = '已甩掉追击者 ' + Math.max(0, cy.lostT).toFixed(0) + 's' + (hot ? ' · 喘息中' : '');
      pxText(t, 34, 152, 17, hot ? '#c8f4ff' : '#8ce8ff', 'rgba(10,18,32,0.9)', 'left');
    } else if ((cy.escape || 0) > 0.02 || (cy.aggro || 0) > 0.6) {
      const esc = clamp(cy.escape || 0, 0, 1);
      const bx = 34, by = 150, bw = Math.min(W - 240, 250), bh = 13;
      pxText('甩掉追击', bx, by - 13, 16, 'rgba(200,220,245,0.9)', 'rgba(10,18,32,0.9)', 'left');
      pxRect(bx, by, bw, bh, 5);
      ctx.fillStyle = 'rgba(10,18,32,0.72)';
      ctx.fill();
      ctx.fillStyle = esc > 0.72 ? '#8ce8ff' : (esc > 0.4 ? '#ffd34d' : '#ff8f7e');
      ctx.fillRect(bx + 2, by + 2, Math.max(2, (bw - 4) * esc), bh - 4);
      if (esc >= 1) {
        pxText('甩掉了！', bx + bw + 10, by + 6, 16, '#8ce8ff', 'rgba(10,18,32,0.9)', 'left');
      } else if ((cy.aggro || 0) > 0.75) {
        pxText('快追上了！', bx + bw + 10, by + 6, 16, '#ff8f7e', 'rgba(10,18,32,0.9)', 'left');
      }
    }
  }

  // ── 悬赏：目标 + 进度条 ──
  const b = G.bounty;
  if (b) {
    const need = b.need;
    const have = clamp(b.id === 'nohit' ? bountyHave() : b.have, 0, need);
    const bw2 = Math.min(W - 250, 200), bx2 = 34, by2 = 196, bh2 = 9;
    pxText(b.name, bx2, by2 - 16, 16, b.color, 'rgba(10,18,32,0.9)', 'left');
    pxNum(have + '/' + need, bx2 + bw2 + 10, by2 + 4, 17, '#ffffff', 'rgba(10,18,32,0.9)', 'left');
    pxRect(bx2, by2, bw2, bh2, 4);
    ctx.fillStyle = 'rgba(10,18,32,0.72)';
    ctx.fill();
    ctx.fillStyle = b.color;
    ctx.fillRect(bx2 + 2, by2 + 2, Math.max(2, (bw2 - 4) * (have / need)), bh2 - 4);
  }

  // ── 天气 / 陷阱风暴（右上角，避开方块按钮） ──
  const w = G.weather;
  let ry = 108;
  if (w) {
    pxText(w.name + ' ' + Math.ceil(G.weatherT) + 's', W - 34, ry, 18, w.color, 'rgba(10,18,32,0.9)', 'right');
    ry += 28;
  }
  if (G.surgeT > 0) {
    pxText('陷阱风暴 ' + Math.ceil(G.surgeT) + 's', W - 34, ry, 18,
      'rgba(255,143,126,0.95)', 'rgba(10,18,32,0.9)', 'right');
    ry += 28;
  }
  // ── 月面 / 疾风 / 身法 / 强化等级 ──
  if (G.moonT > 0) {
    pxText('月面段 ' + Math.ceil(G.moonT) + 's', W - 34, ry, 18, '#cfe0ff', 'rgba(10,18,32,0.9)', 'right');
    ry += 28;
  }
  if (G.galeT > 0) {
    pxText('疾风段 ' + Math.ceil(G.galeT) + 's', W - 34, ry, 18, '#8ce8ff', 'rgba(10,18,32,0.9)', 'right');
    ry += 28;
  }
  if ((G.nearStreak || 0) > 0) {
    pxText('身法 ' + G.nearStreak + '/4', W - 34, ry, 18, '#ffe066', 'rgba(10,18,32,0.9)', 'right');
    ry += 28;
  }
  if ((G.altarCount || 0) > 0) {
    pxText('强化 ×' + G.altarCount, W - 34, ry, 18, '#d3a8ff', 'rgba(10,18,32,0.9)', 'right');
  }
}

// ---- HUD ----
function drawHUD() {
  const st = G.state;
  if (st === 'menu' || st === 'levels' || st === 'loading') return;

  // 左上数据面板：无尽精简为两行（速度感交给特效表达）
  const pw = G.mode === 'level' ? 356 : 176;
  const ph = G.mode === 'level' ? 132 : 92;
  pxPanel(18, 16, pw, ph, { cut: 10, bg: 'rgba(23,32,49,0.82)' });

  if (G.mode === 'level') {
    pxText('第 ' + (G.levelIndex + 1) + ' 关', 38, 40, 20, 'rgba(190,214,242,0.92)', 'rgba(10,18,32,0.9)', 'left');
    let ttl = G.levelTitle || '随机赛道';
    if (ttl.length > 8) ttl = ttl.slice(0, 8);
    pxText(ttl, 116, 40, 21, '#ffffff', 'rgba(10,18,32,0.9)', 'left');
    pxText('金币', 38, 68, 19, 'rgba(190,214,242,0.92)', 'rgba(10,18,32,0.9)', 'left');
    pxNum(G.coins + ' / ' + G.levelCoinTotal, 86, 68, 21, '#ffd34d', 'rgba(10,18,32,0.9)', 'left');

    // 分隔线 + 通关目标
    ctx.fillStyle = 'rgba(140,175,225,0.2)';
    ctx.fillRect(32, 84, pw - 28, 2);
    if (G.objective) {
      const done = objectiveDone(G.objective);
      objBadge(48, 108, 15, G.objective);
      pxText(G.objective.name, 70, 101, 19, G.objective.color, 'rgba(10,18,32,0.92)', 'left');
      const prog = objectiveProg(G.objective);
      if (done) {
        pxText('目标已达成', 70, 124, 17, '#7de08a', 'rgba(10,18,32,0.9)', 'left');
      } else if (prog) {
        pxNum(prog, 70, 124, 17, 'rgba(255,255,255,0.95)', 'rgba(10,18,32,0.9)', 'left');
      } else {
        let dsc = objectiveDesc(G.objective);
        if (dsc.length > 15) dsc = dsc.slice(0, 15);
        pxText(dsc, 70, 124, 16, 'rgba(255,255,255,0.8)', 'rgba(10,18,32,0.9)', 'left');
      }
    }
  } else {
    pxText('距离', 34, 44, 20, 'rgba(200,220,245,0.9)', 'rgba(10,18,32,0.9)', 'left');
    pxNum(Math.floor(world.x / PX_PER_M) + 'm', 88, 44, 23, '#ffffff', 'rgba(10,18,32,0.9)', 'left');
    pxText('金币', 34, 82, 20, 'rgba(200,220,245,0.9)', 'rgba(10,18,32,0.9)', 'left');
    pxNum(String(G.coins), 88, 82, 23, '#ffd34d', 'rgba(10,18,32,0.9)', 'left');
  }

  // 标题居中放在顶部空档
  if (G.mode === 'endless' && st !== 'levelclear') {
    const lw = 142;
    const lh = lw * ASSETS.logo.crop.h / ASSETS.logo.crop.w;
    drawImg('logo', Math.round((W - lw) / 2), 24, lw, lh);
  }
  // 无尽模式：追击脱身 / 天气 / 悬赏 / 陷阱风暴
  if (G.mode === 'endless' && (st === 'playing' || st === 'paused')) drawEndlessHud();

  // 关卡进度条
  if ((G.mode === 'level' || G.mode === 'ugc') && st !== 'levelclear' && st !== 'levelfail') {
    const p = clamp(world.x / G.levelEndX, 0, 1);
    const bx = 24, by = 162, bw = W - 48, bh = 16;
    pxRect(bx, by, bw, bh, 4);
    ctx.fillStyle = '#0b1120';
    ctx.fill();
    ctx.fillStyle = '#26324a';
    ctx.fillRect(bx + 3, by + 3, bw - 6, bh - 6);
    ctx.fillStyle = p > 0.85 ? '#ffd34d' : '#63c9a0';
    ctx.fillRect(bx + 3, by + 3, Math.max(3, (bw - 6) * p), bh - 6);
    ctx.fillStyle = 'rgba(255,255,255,0.35)';
    ctx.fillRect(bx + 3, by + 3, Math.max(3, (bw - 6) * p), 3);
    const fx2 = bx + 3 + (bw - 6) * p;
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(fx2 - 2, by - 12, 4, 16);
    ctx.beginPath();
    ctx.moveTo(fx2 + 2, by - 12);
    ctx.lineTo(fx2 + 18, by - 6);
    ctx.lineTo(fx2 + 2, by);
    ctx.closePath();
    ctx.fillStyle = '#ffd34d';
    ctx.fill();
  }

  if (st === 'playing' || st === 'paused') {
    pxSquareBtn(W - 84, 20, 64, !!G.pressed.pause, ICO.pause);
    pxSquareBtn(W - 160, 20, 64, !!G.pressed.sound, ICO.sound);
    if (!Snd.on) {
      ctx.fillStyle = 'rgba(0,0,0,0.25)';
      ctx.fillRect(W - 160, 20, 64, 64);
    }
    pxSquareBtn(W - 236, 20, 64, !!G.pressed.music, ICO.music);
    if (Music.idx < 0) {
      ctx.fillStyle = 'rgba(0,0,0,0.25)';
      ctx.fillRect(W - 236, 20, 64, 64);
    }
    drawActionBtn(BTN.jump, !!G.pressed.jump, 'jump');
    drawActionBtn(BTN.slide, !!G.pressed.slide, 'slide');
    drawAirJumpHUD();
    drawEnergyHUD();
  }
  drawPowerHUD();
  drawCardReveal();
}

/* 被动补跳：剩余次数 + 冷却进度 */
function drawAirJumpHUD() {
  const bx = 44, by = 764;
  pxText('补跳', bx, by - 28, 15, 'rgba(190,214,242,0.9)', 'rgba(10,18,32,0.9)', 'left');
  for (let i = 0; i < AIR_JUMP_MAX; i++) {
    const cx = bx + 12 + i * 34;
    const on = i < G.airJump && G.airCd <= 0;
    ctx.beginPath();
    ctx.arc(cx, by, 13, 0, Math.PI * 2);
    ctx.fillStyle = on ? 'rgba(140,232,255,0.95)' : 'rgba(88,108,138,0.55)';
    ctx.fill();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = on ? '#e8fbff' : 'rgba(160,185,215,0.35)';
    ctx.stroke();
    if (on) {
      ctx.fillStyle = 'rgba(16,40,58,0.92)';
      ctx.beginPath();
      ctx.moveTo(cx, by - 6);
      ctx.lineTo(cx + 5, by + 4);
      ctx.lineTo(cx - 5, by + 4);
      ctx.closePath();
      ctx.fill();
    }
  }
  if (G.airCd > 0) {
    const p = 1 - G.airCd / AIR_JUMP_CD;
    ctx.fillStyle = 'rgba(10,16,28,0.78)';
    ctx.fillRect(bx, by + 20, 76, 6);
    ctx.fillStyle = '#8ce8ff';
    ctx.fillRect(bx + 1, by + 21, 74 * p, 4);
    pxNum(G.airCd.toFixed(1) + 's', bx + 82, by + 23, 14, 'rgba(200,232,255,0.92)', 'rgba(10,18,32,0.9)', 'left');
  }
}

/* 能量槽 / 冲刺按钮（底部中间） */
function drawEnergyHUD() {
  const bx = Math.round((W - 164) / 2), by = 852, bw = 164, bh = 58;
  if (G.dashT > 0) {
    pxRect(bx, by, bw, bh, 10);
    ctx.fillStyle = 'rgba(20,44,62,0.92)';
    ctx.fill();
    pxText('冲刺中', bx + bw / 2, by + 22, 21, '#8ce8ff', 'rgba(10,18,32,0.9)');
    ctx.fillStyle = 'rgba(10,16,28,0.8)';
    ctx.fillRect(bx + 14, by + 42, bw - 28, 8);
    ctx.fillStyle = '#8ce8ff';
    ctx.fillRect(bx + 15, by + 43, (bw - 30) * (G.dashT / DASH_TIME), 6);
  } else if (G.energy >= ENERGY_MAX) {
    const pulse = 0.5 + 0.5 * Math.sin(G.time * 6);
    const pressed = !!G.pressed.dash;
    const y = by + (pressed ? 4 : 0);
    pxRect(bx, by + 6, bw, bh, 10);
    ctx.fillStyle = '#08283a';
    ctx.fill();
    pxRect(bx, y, bw, bh, 10);
    ctx.fillStyle = '#1b6f8c';
    ctx.fill();
    ctx.fillStyle = '#8ce8ff';
    ctx.fillRect(bx + 10, y + 3, bw - 20, 4);
    pxRect(bx, y, bw, bh, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = '#0a3444';
    ctx.stroke();
    pxRect(bx - 4, y - 4, bw + 8, bh + 8, 14);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(140,232,255,' + (0.25 + 0.5 * pulse) + ')';
    ctx.stroke();
    pxText('冲刺', bx + bw / 2, y + bh / 2 + 1, 29, '#e8fbff', 'rgba(10,44,62,0.95)');
  } else {
    pxText('能量', bx + bw / 2, by + 6, 15, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.9)');
    pxRect(bx, by + 18, bw, 24, 8);
    ctx.fillStyle = 'rgba(10,16,28,0.8)';
    ctx.fill();
    const p = G.energy / ENERGY_MAX;
    if (p > 0.02) {
      pxRect(bx + 3, by + 21, Math.max(6, (bw - 6) * p), 18, 5);
      ctx.fillStyle = p >= 0.99 ? '#ffd34d' : 'rgba(140,232,255,0.92)';
      ctx.fill();
    }
  }
}

// 当前生效的增益，带倒计时
function drawPowerHUD() {
  if (G.state !== 'playing' && G.state !== 'paused') return;
  const items = [];
  for (const k in G.buff) {
    if (G.buff[k] > 0) items.push([BUFF_DEF[k].icon + Math.ceil(G.buff[k]), BUFF_DEF[k].color]);
  }
  if (G.shield > 0) items.push(['盾' + G.shield, '#8ce8ff']);
  if (!items.length) return;
  let x = 22;
  const y = G.mode === 'level' ? 190 : 118;   // 关卡模式避开进度条，无尽紧贴面板
  for (const it of items) {
    const w = 62;
    pxRect(x, y, w, 26, 6);
    ctx.fillStyle = 'rgba(11,17,32,0.9)';
    ctx.fill();
    pxRect(x + 2, y + 2, w - 4, 22, 5);
    ctx.globalAlpha = 0.9;
    ctx.fillStyle = it[1];
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.font = 'bold 15px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#0b1120';
    ctx.fillText(it[0], x + w / 2, y + 14);
    x += w + 6;
  }
}

/* 单张卡面：稀有度边框 + 名称 + 词缀 + 危险度 */
function drawCardFace(c, x, y, w, h, alpha) {
  const R = RARITY[c.rarity];
  ctx.save();
  ctx.globalAlpha = alpha === undefined ? 1 : alpha;
  // 投影
  pxRect(x + 3, y + 5, w, h, 8);
  ctx.fillStyle = 'rgba(6,10,18,0.5)';
  ctx.fill();
  // 稀有度外框
  pxRect(x, y, w, h, 8);
  ctx.fillStyle = R.c1;
  ctx.fill();
  // 卡面主体
  pxRect(x + 3, y + 3, w - 6, h - 6, 6);
  ctx.fillStyle = c.rarity >= 3 ? '#1a1430' : '#101a2c';
  ctx.fill();
  // 顶部稀有度光带
  const g = ctx.createLinearGradient(x, y, x + w, y);
  g.addColorStop(0, R.c2);
  g.addColorStop(0.6, R.c1);
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.globalAlpha = (alpha === undefined ? 1 : alpha) * 0.55;
  ctx.fillStyle = g;
  ctx.fillRect(x + 6, y + 3, w - 12, 3);
  ctx.globalAlpha = alpha === undefined ? 1 : alpha;
  // 左侧稀有度条
  pxRect(x + 4, y + 6, 7, h - 12, 3);
  ctx.fillStyle = R.c2;
  ctx.fill();
  // 名称
  ctx.font = 'bold 16px "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#ffffff';
  ctx.fillText(c.name.length > 8 ? c.name.slice(0, 8) : c.name, x + 18, y + 17);
  // 词缀 / 描述
  ctx.font = 'bold 12px "Microsoft YaHei", sans-serif';
  ctx.fillStyle = c.rarity >= 3 ? R.c2 : 'rgba(190,210,240,0.85)';
  const tag = (c.tag || '').length > 11 ? c.tag.slice(0, 11) : (c.tag || '');
  ctx.fillText(tag, x + 18, y + h - 15);
  // 危险度点
  const dn = c.danger;
  for (let i = 0; i < 3; i++) {
    ctx.fillStyle = i < dn ? (dn >= 3 ? '#ff7a6a' : dn === 2 ? '#f5c05a' : '#7cc0f2') : 'rgba(255,255,255,0.14)';
    ctx.fillRect(x + w - 26 + i * 7, y + h - 19, 5, 5);
  }
  // 传说呼吸光
  if (c.rarity === 4) {
    ctx.globalAlpha = (alpha === undefined ? 1 : alpha) * (0.25 + 0.22 * Math.sin(G.time * 5 + x));
    pxRect(x - 3, y - 3, w + 6, h + 6, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = R.c2;
    ctx.stroke();
    ctx.globalAlpha = alpha === undefined ? 1 : alpha;
  }
  // 高光
  ctx.globalAlpha = (alpha === undefined ? 1 : alpha) * 0.16;
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(x + 10, y + h - 8, w - 20, 2);
  ctx.restore();
}

/* 关卡开场：13 张牌依次翻出 */
function drawCardReveal() {
  const cards = G.levelCards;
  if (!cards) return;
  // 只在游戏进行/暂停时展示牌组，结算与死亡界面不再残留
  if (G.state !== 'playing' && G.state !== 'paused') return;
  if (G.cardRevealT > 3.4) return;
  const t = G.cardRevealT;
  const fade = t > 2.8 ? Math.max(0, 1 - (t - 2.8) / 0.6) : 1;
  const cols = 3, cw = 158, ch = 54, gapX = 10, gapY = 8;
  const totalW = cols * cw + (cols - 1) * gapX;
  const x0 = (W - totalW) / 2;
  const y0 = 218;   // 让开上方的目标面板与关卡进度条
  ctx.save();
  ctx.globalAlpha = fade;
  pxText('本 关 牌 组', W / 2, y0 - 26, 20, 'rgba(255,255,255,0.95)', 'rgba(10,18,32,0.92)');
  pxText(cards.length + ' / ' + CARD_TOTAL + ' 张', W / 2, y0 - 4, 14, 'rgba(200,220,245,0.85)', 'rgba(10,18,32,0.9)');
  for (let i = 0; i < cards.length; i++) {
    const k = clamp((t - i * 0.06) / 0.24, 0, 1);
    if (k <= 0) continue;
    const col = i % cols, row = Math.floor(i / cols);
    const x = x0 + col * (cw + gapX);
    const y = y0 + 14 + row * (ch + gapY);
    const s = ease.outBack(k);
    ctx.save();
    ctx.translate(x + cw / 2, y + ch / 2);
    ctx.scale(s, s);
    ctx.translate(-cw / 2, -ch / 2);
    drawCardFace(cards[i], 0, 0, cw, ch, fade);
    ctx.restore();
  }
  ctx.restore();
}

/* 底部操作键：像素圆盘 + 动作图标 */
function drawActionBtn(b, pressed, kind) {
  const cx = Math.round(b.x + b.w / 2);
  const cy = Math.round(b.y + b.h / 2 + (pressed ? 4 : 0));
  const R = Math.round(b.w / 2);
  const k = kind === 'jump' ? SKIN.green : SKIN.blue;

  if (!pressed) {
    ctx.beginPath();
    ctx.arc(cx, cy + 8, R - 2, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(6,10,18,0.42)';
    ctx.fill();
  }
  ctx.beginPath();
  ctx.arc(cx, cy, R - 2, 0, Math.PI * 2);
  ctx.fillStyle = k.deep;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, R - 7, 0, Math.PI * 2);
  ctx.fillStyle = pressed ? k.main : k.dark;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy - 5, R - 16, Math.PI * 1.05, Math.PI * 1.95);
  ctx.lineWidth = 5;
  ctx.strokeStyle = k.hi;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, R - 7, 0, Math.PI * 2);
  ctx.lineWidth = 3;
  ctx.strokeStyle = 'rgba(255,255,255,0.32)';
  ctx.stroke();

  if (kind === 'jump') {
    // 按钮上的小人也要跟着皮肤走
    const sc = SKIN_CACHE.run2;
    const im = sc ? sc.im : IMG.run2;
    const c = sc ? sc.crop : (ASSETS.run2 ? ASSETS.run2.crop : null);
    if (im && c) {
      const h = (R - 16) * 2.0;
      const w = h * c.w / c.h;
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(im, c.x, c.y, c.w, c.h, cx - w / 2, cy - h / 2 + 2, w, h);
    }
  } else {
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(cx - 8, cy - 32, 16, 24);
    ctx.beginPath();
    ctx.moveTo(cx - 23, cy - 8);
    ctx.lineTo(cx + 23, cy - 8);
    ctx.lineTo(cx, cy + 26);
    ctx.closePath();
    ctx.fill();
  }
}

// ---- 主菜单 ----
function drawMenu() {
  const t = G.time;
  ctx.fillStyle = 'rgba(8,14,26,0.34)';
  ctx.fillRect(0, 0, W, H);

  // 顶部装饰条
  ctx.fillStyle = 'rgba(11,17,32,0.55)';
  ctx.fillRect(0, 0, W, 8);
  ctx.fillStyle = 'rgba(140,175,225,0.28)';
  ctx.fillRect(0, 8, W, 3);

  // 标题
  const lw = (W < 640 ? 300 : 344) + Math.sin(t * 1.9) * 8;
  const lh = lw * ASSETS.logo.crop.h / ASSETS.logo.crop.w;
  const ly = 84 + Math.sin(t * 1.5) * 6;
  ctx.save();
  ctx.globalAlpha = 0.35;
  ctx.fillStyle = '#7ec8ff';
  const glow = ctx.createRadialGradient(W / 2, ly + lh / 2, 20, W / 2, ly + lh / 2, 240);
  glow.addColorStop(0, 'rgba(140,210,255,0.5)');
  glow.addColorStop(1, 'rgba(140,210,255,0)');
  ctx.fillStyle = glow;
  ctx.fillRect(W / 2 - 260, ly - 60, 520, lh + 120);
  ctx.restore();
  ctx.save();
  ctx.shadowColor = 'rgba(20,60,110,0.7)';
  ctx.shadowOffsetY = 10;
  drawImg('logo', W / 2 - lw / 2, ly, lw, lh);
  ctx.restore();

  // 副标题
  const by = ly + lh + 12;
  const mx = Math.min(140, Math.round(W * 0.1));   // 分隔线左右对称
  ctx.fillStyle = 'rgba(255,255,255,0.18)';
  ctx.fillRect(mx, by, W - mx * 2, 3);

  // 模式卡片（mini 小卡 = mod 注册的菜单按钮/自定义模式，用按钮样式画）
  const ubList = getUIButtons().list;
  for (const b of ubList) if (b.mini) pxButton(b, !!G.pressed[b.id]);
  for (const b of ubList.filter(x => !x.mini && x.id !== 'skin' && x.id !== 'pet' && x.id !== 'music' && x.id !== 'chaser')) pxCard(b, !!G.pressed[b.id]);

  // 皮肤按钮（数据面板右边那块空位）
  const skBtn = getUIButtons().list.find(x => x.id === 'skin');
  if (skBtn) {
    const pressed = !!G.pressed.skin;
    const y = skBtn.y + (pressed ? 4 : 0);
    const k = SKIN.slate;
    pxRect(skBtn.x, skBtn.y + 6, skBtn.w, skBtn.h, 10);
    ctx.fillStyle = k.deep;
    ctx.fill();
    pxRect(skBtn.x, y, skBtn.w, skBtn.h, 10);
    ctx.fillStyle = k.main;
    ctx.fill();
    ctx.fillStyle = k.hi;
    ctx.fillRect(skBtn.x + 10, y + 3, skBtn.w - 20, 4);
    pxRect(skBtn.x, y, skBtn.w, skBtn.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = k.edge;
    ctx.stroke();
    // 当前皮肤的颜色球
    ctx.beginPath();
    ctx.arc(skBtn.x + skBtn.w / 2, y + 27, 12, 0, Math.PI * 2);
    ctx.fillStyle = SKINS[G.skinIdx].color;
    ctx.fill();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = '#0b1120';
    ctx.stroke();
    pxText('皮肤', skBtn.x + skBtn.w / 2, y + 58, 17, '#dfe8f5', 'rgba(10,18,32,0.9)');
  }

  // 宠物按钮（皮肤按钮左边那块）：当前宠物头像 + 三态循环
  const petBtn = getUIButtons().list.find(x => x.id === 'pet');
  if (petBtn) {
    const cur = petCur();
    const pressed = !!G.pressed.pet;
    const y = petBtn.y + (pressed ? 4 : 0);
    const k = SKIN.slate;
    pxRect(petBtn.x, petBtn.y + 6, petBtn.w, petBtn.h, 10);
    ctx.fillStyle = k.deep;
    ctx.fill();
    pxRect(petBtn.x, y, petBtn.w, petBtn.h, 10);
    ctx.fillStyle = cur ? k.main : 'rgba(40,50,70,0.92)';
    ctx.fill();
    ctx.fillStyle = k.hi;
    ctx.fillRect(petBtn.x + 10, y + 3, petBtn.w - 20, 4);
    pxRect(petBtn.x, y, petBtn.w, petBtn.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = cur ? 'rgba(255,215,90,0.85)' : k.edge;
    ctx.stroke();
    if (cur) {
      // 当前宠物头像（微浮动）
      const bob = Math.sin(G.time * 3) * 3;
      ctx.save();
      const ph = 38;
      const pw2 = ph * cur.width / cur.height;
      ctx.imageSmoothingEnabled = true;
      ctx.drawImage(cur, petBtn.x + petBtn.w / 2 - pw2 / 2, y + 6 + bob, pw2, ph);
      ctx.restore();
    } else {
      // 休息态：Z 字气泡
      pxText('zZ', petBtn.x + petBtn.w / 2, y + 24, 22, 'rgba(200,220,245,0.75)', 'rgba(10,18,32,0.9)');
    }
    const nm = G.petIdx < 0 ? '宠物·休' : PETS[G.petIdx].name;
    pxText(nm, petBtn.x + petBtn.w / 2, y + 58, 16,
      cur ? '#ffe9a8' : 'rgba(190,205,225,0.6)', 'rgba(10,18,32,0.9)');
  }

  // 音乐按钮（宠物左边那块）：点击循环 默认 BGM → 备选 → 关
  const muBtn = getUIButtons().list.find(x => x.id === 'music');
  if (muBtn) {
    const pressed = !!G.pressed.music;
    const y = muBtn.y + (pressed ? 4 : 0);
    const k = SKIN.slate;
    pxRect(muBtn.x, muBtn.y + 6, muBtn.w, muBtn.h, 10);
    ctx.fillStyle = k.deep;
    ctx.fill();
    pxRect(muBtn.x, y, muBtn.w, muBtn.h, 10);
    ctx.fillStyle = Music.idx < 0 ? 'rgba(40,50,70,0.92)' : k.main;
    ctx.fill();
    ctx.fillStyle = k.hi;
    ctx.fillRect(muBtn.x + 10, y + 3, muBtn.w - 20, 4);
    pxRect(muBtn.x, y, muBtn.w, muBtn.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = k.edge;
    ctx.stroke();
    ICO.music(muBtn.x + muBtn.w / 2, y + 26, 20);
    if (Music.idx < 0) {   // 关闭态：红色斜杠
      ctx.strokeStyle = '#ff8a8a';
      ctx.lineWidth = 4;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(muBtn.x + 14, y + 10);
      ctx.lineTo(muBtn.x + muBtn.w - 14, y + 40);
      ctx.stroke();
    }
    pxText(Music.idx < 0 ? '音乐·关' : Music.shortName(), muBtn.x + muBtn.w / 2, y + 58, 16,
      Music.idx < 0 ? 'rgba(190,205,225,0.6)' : '#dfe8f5', 'rgba(10,18,32,0.9)');
  }

  // 追击者按钮（音乐左边那块）：当前追击者头像 + 点击切换 蚩尤↔阿坚（局外生效）
  const chBtn = getUIButtons().list.find(x => x.id === 'chaser');
  if (chBtn) {
    const pressed = !!G.pressed.chaser;
    const y = chBtn.y + (pressed ? 4 : 0);
    const k = SKIN.slate;
    const aj = chaserSkin() === 'aj';
    pxRect(chBtn.x, chBtn.y + 6, chBtn.w, chBtn.h, 10);
    ctx.fillStyle = k.deep;
    ctx.fill();
    pxRect(chBtn.x, y, chBtn.w, chBtn.h, 10);
    ctx.fillStyle = aj ? 'rgba(84,54,122,0.92)' : k.main;
    ctx.fill();
    ctx.fillStyle = k.hi;
    ctx.fillRect(chBtn.x + 10, y + 3, chBtn.w - 20, 4);
    pxRect(chBtn.x, y, chBtn.w, chBtn.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = aj ? 'rgba(200,150,255,0.9)' : k.edge;
    ctx.stroke();
    // 当前追击者头像（微浮动）
    const cim = BOSS_IMG[aj ? 'aj_run1' : 'chiyou_run1'] || BOSS_IMG[aj ? 'aj_idle' : 'chiyou_idle'];
    if (cim) {
      const bob = Math.sin(G.time * 3) * 3;
      ctx.save();
      const chh = 38;
      const cw2 = chh * cim.width / cim.height;
      ctx.imageSmoothingEnabled = true;
      ctx.drawImage(cim, chBtn.x + chBtn.w / 2 - cw2 / 2, y + 6 + bob, cw2, chh);
      ctx.restore();
    }
    pxText(aj ? '追击者·阿坚' : '追击者·蚩尤', chBtn.x + chBtn.w / 2, y + 58, 14,
      aj ? '#e6ccff' : '#dfe8f5', 'rgba(10,18,32,0.9)');
  }

  // 数据面板：宽屏固定左下与三小钮同行；窄屏（W<640）居中上移进标题空档，避免与按钮重叠
  const stars = G.stars.reduce((a, b) => a + b, 0);
  const pw0 = Math.min(288, W - 56), ph0 = 76;
  const py0 = W >= 640 ? 606 : 258;
  const px0 = W >= 640 ? 28 : Math.round((W - pw0) / 2);
  pxPanel(px0, py0, pw0, ph0, { cut: 10, bg: 'rgba(23,32,49,0.88)' });
  const c1 = px0 + pw0 * 0.26;
  const c2 = px0 + pw0 * 0.74;
  ctx.fillStyle = 'rgba(255,255,255,0.14)';
  ctx.fillRect(px0 + pw0 / 2 - 1.5, py0 + 12, 3, 52);
  pxText('最高纪录', c1, py0 + 20, 16, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.9)');
  pxNum(G.best + ' m', c1, py0 + 50, 25, '#ffd34d', 'rgba(10,18,32,0.95)');
  pxText('收集星星', c2, py0 + 20, 16, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.9)');
  const starTxt = stars + '/' + (LEVELS.length * 3);
  ctx.font = 'bold 25px Consolas, "Courier New", monospace';
  const stw = ctx.measureText(starTxt).width;
  const totalW = 24 + 8 + stw;
  const gx = c2 - totalW / 2;
  pxStar(gx + 12, py0 + 48, 12, '#ffd34d');
  pxNum(starTxt, gx + 32, py0 + 50, 25, '#ffffff', 'rgba(10,18,32,0.95)', 'left');

  // 底部操作指南：半透明底板统一收拢成 4 行（窄屏字号略降不贴边）
  const hw = Math.min(560, W - 24), hx = (W - hw) / 2;
  const hfs = W < 520 ? 15 : 17;
  pxRect(hx, 794, hw, 112, 10);
  ctx.fillStyle = 'rgba(8,14,26,0.55)';
  ctx.fill();
  pxRect(hx, 794, hw, 112, 10);
  ctx.lineWidth = 2;
  ctx.strokeStyle = 'rgba(140,175,225,0.2)';
  ctx.stroke();
  pxText('空格 / ↑ 跳跃 · ↓ 滑铲 · P 暂停 · M 静音', W / 2, 813, W < 520 ? 16 : 18, 'rgba(255,255,255,0.92)');
  pxText('先按 ↓ 再跳 = 矮跳钻刺 · 下坠按跳 = 补跳 ×2', W / 2, 839, hfs, '#8ce8ff');
  pxText('低杆 / 摆锤 / 石板 → 滑铲 · 能量攒满 = 无敌冲刺', W / 2, 865, hfs, 'rgba(255,255,255,0.78)');
  pxText('无尽：穿门选增益 · 连击攒 FEVER · 夜里小心', W / 2, 891, hfs, '#ffe9a8');
  pxText('机灵糖1', W - 28, 944, 17, 'rgba(255,255,255,0.5)', 'rgba(10,18,32,0.75)', 'right');

  // mod 主菜单绘制钩子（api.on('menu')）：最后执行，可自由叠加装饰/演出（ctx 全局可用）
  if (typeof UGC_MENU_HOOKS !== 'undefined') {
    for (const h of UGC_MENU_HOOKS) {
      try { h.fn(buildUgcApi(h.mod || null), { w: W, h: H }); } catch (e) {}
    }
  }
}

// ---- 皮肤选择 ----
/* 缩略图：优先皮肤自己的素材，没有就用原版染色 */
function skinThumb(idx) {
  const k = 'idle_' + idx;
  if (!SKIN_PREVIEW[k]) {
    const sk = SKINS[idx];
    if (sk.id === 'white') {
      if (!IMG.idle) return null;
      SKIN_PREVIEW[k] = { im: IMG.idle, crop: ASSETS.idle.crop };
    } else if (sk.sprite && sk.sprite.idle) {
      SKIN_PREVIEW[k] = sk.sprite.idle;
    } else if (sk.sprite && sk.sprite.run) {
      SKIN_PREVIEW[k] = sk.sprite.run;
    } else if (IMG.idle) {
      const c = ASSETS.idle.crop;
      SKIN_PREVIEW[k] = {
        im: tintSprite(IMG.idle, c, sk.color),
        crop: { x: 0, y: 0, w: c.w, h: c.h },
      };
    } else {
      return null;
    }
  }
  return SKIN_PREVIEW[k];
}

function drawSkinSelect() {
  ctx.fillStyle = 'rgba(8,14,26,0.62)';
  ctx.fillRect(0, 0, W, H);

  const stars = totalStars();
  pxPanel(24, 16, W - 48, 76, { cut: 12, bg: 'rgba(23,32,49,0.9)' });
  pxText('皮肤', 52, 54, 32, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
  pxText('全部已解锁 · 点击即换', 128, 58, 17, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.9)', 'left');
  const starTxt = stars + ' / ' + (LEVELS.length * 3);
  ctx.font = 'bold 23px Consolas, "Courier New", monospace';
  const stw = ctx.measureText(starTxt).width;
  pxNum(starTxt, W - 52, 54, 23, '#ffffff', 'rgba(10,18,32,0.95)', 'right');
  pxStar(W - 52 - stw - 24, 54, 13, '#ffd34d');

  const list = getUIButtons().list.filter(b => b.skin !== undefined);
  for (const b of list) {
    const i = b.skin;
    const sk = SKINS[i];
    const unlocked = skinUnlocked(i);
    const using = G.skinIdx === i;
    const pressed = !!G.pressed[b.id];
    const y = b.y + (pressed ? 3 : 0);

    pxRect(b.x, b.y + 5, b.w, b.h, 10);
    ctx.fillStyle = 'rgba(6,10,18,0.45)';
    ctx.fill();
    pxRect(b.x, y, b.w, b.h, 10);
    ctx.fillStyle = using ? '#22384e' : unlocked ? '#1e2a3e' : '#151c2a';
    ctx.fill();
    // 顶部颜色条
    ctx.globalAlpha = unlocked ? 0.9 : 0.25;
    ctx.fillStyle = sk.color;
    ctx.fillRect(b.x + 10, y + 3, b.w - 20, 3);
    ctx.globalAlpha = 1;
    pxRect(b.x, y, b.w, b.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = using ? '#c9962a' : unlocked ? '#3d4f6d' : '#222c3e';
    ctx.stroke();

    // 缩略图（高度随卡片自适应）
    const t = skinThumb(i);
    if (t) {
      const th = Math.min(86, b.h - 24);
      const tw = th * t.crop.w / t.crop.h;
      ctx.imageSmoothingEnabled = false;
      ctx.globalAlpha = unlocked ? 1 : 0.25;
      ctx.drawImage(t.im, t.crop.x, t.crop.y, t.crop.w, t.crop.h,
        b.x + 16, y + b.h / 2 - th / 2, tw, th);
      ctx.globalAlpha = 1;
    }

    const tx = b.x + 96;
    pxText(sk.name, tx, y + b.h * 0.31, 24,
      unlocked ? '#ffffff' : 'rgba(150,170,200,0.45)', 'rgba(10,18,32,0.92)', 'left');
    if (using) {
      pxText('使用中', tx, y + b.h * 0.66, 19, '#7de08a', 'rgba(10,18,32,0.9)', 'left');
    } else if (unlocked) {
      // 有素材的皮肤标出来，方便一眼看出哪套是真素材
      pxText(sk.custom ? '自定义皮肤 · 点击使用' : (skinHasArt(i) ? '素材皮肤 · 点击使用' : '配色皮肤 · 点击使用'),
        tx, y + b.h * 0.66, 16, sk.custom ? 'rgba(140,232,170,0.9)' : 'rgba(180,230,255,0.85)', 'rgba(10,18,32,0.9)', 'left');
    } else {
      ctx.save();
      ctx.translate(b.x + b.w - 32, y + 32);
      ctx.fillStyle = 'rgba(190,210,240,0.5)';
      ctx.fillRect(-9, -1, 18, 14);
      ctx.beginPath();
      ctx.arc(0, -2, 6.5, Math.PI, 0);
      ctx.lineWidth = 3.5;
      ctx.strokeStyle = 'rgba(190,210,240,0.5)';
      ctx.stroke();
      ctx.restore();
      pxText('需要 ' + sk.need + ' 星', tx, y + b.h * 0.66, 18, 'rgba(255,180,140,0.85)', 'rgba(10,18,32,0.9)', 'left');
    }

    // 自定义皮肤的「删」角标（对应 skdel 按钮）
    if (sk.custom) {
      const dp = !!G.pressed['skdel' + i];
      const dx = b.x + b.w - 46, dy = y + 6;
      pxRect(dx, dy, 38, 30, 7);
      ctx.fillStyle = 'rgba(120,40,50,0.85)';
      ctx.fill();
      pxRect(dx, dy - (dp ? 2 : 0), 38, 30, 7);
      ctx.lineWidth = 2;
      ctx.strokeStyle = 'rgba(255,140,140,0.7)';
      ctx.stroke();
      pxText('删', dx + 19, dy + 20 - (dp ? 2 : 0), 17, '#ffb9b9', 'rgba(20,8,10,0.9)');
    }
  }

  pxText('皮肤只改外观，不影响到手感 · 自定义皮肤在「＋添加」里上传图片', W / 2, 662, 18, 'rgba(255,255,255,0.78)');

  const back = getUIButtons().list.filter(b => b.id === 'back');
  if (back[0]) pxButton(back[0], !!G.pressed.back);
  const skAdd = getUIButtons().list.filter(b => b.id === 'skadd');
  if (skAdd[0]) pxButton(skAdd[0], !!G.pressed.skadd);
}

// ---- 关卡选择 ----
function drawLevelSelect() {
  ctx.fillStyle = 'rgba(8,14,26,0.62)';
  ctx.fillRect(0, 0, W, H);

  const stars = G.stars.reduce((a, b) => a + b, 0);
  pxPanel(24, 16, W - 48, 76, { cut: 12, bg: 'rgba(23,32,49,0.9)' });
  pxText('关卡模式', 52, 54, 32, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
  const starTxt = stars + ' / ' + (LEVELS.length * 3);
  ctx.font = 'bold 23px Consolas, "Courier New", monospace';
  const stw2 = ctx.measureText(starTxt).width;
  pxNum(starTxt, W - 52, 54, 23, '#ffffff', 'rgba(10,18,32,0.95)', 'right');
  pxStar(W - 52 - stw2 - 24, 54, 13, '#ffd34d');

  const list = getUIButtons().list.filter(b => b.level !== undefined);
  for (const b of list) {
    const idx = b.level;
    const unlocked = idx === 0 || G.stars[idx - 1] > 0 || G.stars[idx] > 0;
    const cleared = G.stars[idx] > 0;
    const pressed = !!G.pressed[b.id];
    const data = unlocked ? levelData(idx) : null;
    const y = b.y + (pressed ? 3 : 0);
    const c1 = b.x + 10, cw = b.w - 20;

    // 底影 + 面板
    pxRect(b.x, b.y + 5, b.w, b.h, 10);
    ctx.fillStyle = 'rgba(6,10,18,0.45)';
    ctx.fill();
    pxRect(b.x, y, b.w, b.h, 10);
    ctx.fillStyle = cleared ? '#22384e' : unlocked ? '#1e2a3e' : '#151c2a';
    ctx.fill();
    // 顶部彩条用目标色，一眼看出这关要什么
    ctx.fillStyle = unlocked && data ? data.objective.color : 'rgba(120,140,170,0.18)';
    ctx.globalAlpha = unlocked ? 0.8 : 1;
    ctx.fillRect(c1, y + 3, cw, 3);
    ctx.globalAlpha = 1;
    pxRect(b.x, y, b.w, b.h, 10);
    ctx.lineWidth = 3;
    ctx.strokeStyle = cleared ? '#c9962a' : unlocked ? '#3d4f6d' : '#222c3e';
    ctx.stroke();
    pxRect(b.x + 5, y + 5, b.w - 10, b.h - 10, 7);
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = cleared ? 'rgba(255,220,130,0.28)' : 'rgba(160,195,240,0.12)';
    ctx.stroke();

    // 第一行：序号 + 关卡名
    pxNum(String(idx + 1).padStart(2, '0'), b.x + 12, y + 27, 27,
      unlocked ? '#ffffff' : 'rgba(150,170,200,0.35)', 'rgba(10,18,32,0.95)', 'left');
    let nm = unlocked ? (data ? data.title : '抽卡关卡') : '未解锁';
    if (nm.length > 7) nm = nm.slice(0, 7);
    pxText(nm, b.x + 58, y + 27, 20,
      unlocked ? '#ffffff' : 'rgba(150,170,200,0.4)', 'rgba(10,18,32,0.9)', 'left');

    // 第二行：通关目标 + 星级
    if (unlocked && data) {
      objBadge(b.x + 26, y + 62, 13, data.objective);
      pxText(data.objective.name, b.x + 46, y + 62, 19, data.objective.color, 'rgba(10,18,32,0.92)', 'left');
    } else {
      ctx.save();
      ctx.translate(b.x + 26, y + 60);
      ctx.fillStyle = 'rgba(190,210,240,0.45)';
      ctx.fillRect(-9, -1, 18, 14);
      ctx.beginPath();
      ctx.arc(0, -2, 6.5, Math.PI, 0);
      ctx.lineWidth = 3.5;
      ctx.strokeStyle = 'rgba(190,210,240,0.45)';
      ctx.stroke();
      ctx.restore();
      pxText('通关上一关后解锁', b.x + 46, y + 62, 17, 'rgba(170,190,220,0.5)', 'rgba(10,18,32,0.9)', 'left');
    }
    for (let s = 0; s < 3; s++) {
      pxStar(b.x + b.w - 62 + s * 21, y + 62, 8, s < G.stars[idx] ? '#ffd34d' : 'rgba(255,255,255,0.15)');
    }
  }

  // —— 我的创造（UGC）——
  const ugcItems = ugcListSync();
  if (ugcItems.length) {
    pxText('— 我的创造 · 点卡片试玩，右下可分享/删除 —', W / 2, 688, 18, 'rgba(190,214,242,0.8)');
    const allBtns = getUIButtons().list;
    const ugcCards = allBtns.filter(b => b.ugc);
    const ugcMinis = allBtns.filter(b => b.ugcShare || b.ugcDel);
    for (const b of ugcCards) {
      const it = b.ugc;
      const y = b.y + (!!G.pressed[b.id] ? 3 : 0);
      pxRect(b.x, b.y + 5, b.w, b.h, 9);
      ctx.fillStyle = 'rgba(6,10,18,0.45)';
      ctx.fill();
      pxRect(b.x, y, b.w, b.h, 9);
      ctx.fillStyle = it.ratingStrs && it.ratingStrs.reach ? '#2b2542' : '#241f38';
      ctx.fill();
      // 顶部彩条（紫，代表玩家创造）
      ctx.fillStyle = '#b48cf0';
      ctx.fillRect(b.x + 8, y + 3, b.w - 16, 3);
      let nm = it.title || '无名关卡';
      if (nm.length > 8) nm = nm.slice(0, 8);
      pxText(nm, b.x + 10, y + 28, 20, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
      const dn = { simple: '易', normal: '普', hard: '硬' }[it.difficulty] || '普';
      pxText(dn + ' · ' + it.lenM + 'm', b.x + 10, y + 53, 16, '#b9a6e8', 'rgba(10,18,32,0.9)', 'left');
      const st4 = it.ratingStrs || {};
      const ns = (st4.reach ? 1 : 0) + (st4.noHit ? 1 : 0) + (st4.coin70 ? 1 : 0);
      for (let s = 0; s < 3; s++) pxStar(b.x + b.w - 60 + s * 21, y + 20, 8, s < ns ? '#ffd34d' : 'rgba(255,255,255,0.15)');
    }
    for (const b of ugcMinis) {
      if (b.ugcShare) drawMiniBtn(b, '分享');
      if (b.ugcDel) drawMiniBtn(b, '删');
    }
    if (ugcItems.length > 4) pxText('更多作品请在创造中心查看', W / 2, 780, 16, 'rgba(255,255,255,0.55)');
  } else {
    pxText('在创造中心让 AI 帮你做一关试试！', W / 2, 690, 19, 'rgba(255,255,255,0.75)', 'rgba(10,18,32,0.95)');
  }

  const back = getUIButtons().list.filter(b => b.id === 'back');
  if (back[0]) pxButton(back[0], !!G.pressed.back);
}

/* 画 UGC 作品小按钮（分享/删除等） */
function drawMiniBtn(b, label) {
  if (!b) return;
  const y = b.y + (!!G.pressed[b.id] ? 2 : 0);
  pxRect(b.x, y, b.w, b.h, 6);
  ctx.fillStyle = 'rgba(28,38,62,0.95)';
  ctx.fill();
  pxRect(b.x, y, b.w, b.h, 6);
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = 'rgba(160,195,240,0.3)';
  ctx.stroke();
  pxText(label, b.x + b.w / 2, y + b.h / 2 + 1, 15, label === '删' ? 'rgba(255,150,150,0.95)' : 'rgba(190,220,255,0.95)', 'rgba(10,18,32,0.9)');
}

/* 画 UGC 主作品卡 */
function drawUgcCard(b, it) {
  const y = b.y + (!!G.pressed[b.id] ? 3 : 0);
  pxRect(b.x, b.y + 5, b.w, b.h, 9);
  ctx.fillStyle = 'rgba(6,10,18,0.45)';
  ctx.fill();
  pxRect(b.x, y, b.w, b.h, 9);
  ctx.fillStyle = it.ratingStrs && it.ratingStrs.reach ? '#2b2542' : '#241f38';
  ctx.fill();
  ctx.fillStyle = '#b48cf0';
  ctx.fillRect(b.x + 8, y + 3, b.w - 16, 3);
  let nm = it.title || '无名关卡';
  if (nm.length > 8) nm = nm.slice(0, 8);
  pxText(nm, b.x + 10, y + 25, 20, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
  const dn = { simple: '易', normal: '普', hard: '硬' }[it.difficulty] || '普';
  pxText(dn + ' · ' + it.lenM + 'm', b.x + 10, y + 49, 16, '#b9a6e8', 'rgba(10,18,32,0.9)', 'left');
  const st4 = it.ratingStrs || {};
  const ns = (st4.reach ? 1 : 0) + (st4.noHit ? 1 : 0) + (st4.coin70 ? 1 : 0);
  for (let s = 0; s < 3; s++) pxStar(b.x + b.w - 56 + s * 20, y + 20, 8, s < ns ? '#ffd34d' : 'rgba(255,255,255,0.15)');
}

/* 创造中心分区纵坐标（getUIButtons 与 drawCreative 共用，改布局只动这里） */
const CRE_Y = {
  headH: 104,   // 头部面板高（面板从 y=24 起）
  secA: 152,    // 分区标题：跑酷关卡
  diffY: 166,   // 三档提示词按钮行
  impY: 258,    // 粘贴 AI 结果 / 分享码行
  secB: 360,    // 分区标题：机关 mod
  modImpY: 374, // 复制 mod 提示词 / 粘贴 mod 导入行
  modRowY: 466, // mod 库 / 示例包 / 全局背景行
  secC: 548,    // 分区标题：我的作品
  cardY: 566,   // 作品卡首行（行距 92，卡高 86）
  backY: 872,   // 返回按钮
};

/* 分区标题（紫色小竖条 + 左对齐文字） */
function pxSection(y, txt) {
  ctx.fillStyle = 'rgba(180,140,240,0.95)';
  ctx.fillRect(44, y - 10, 5, 20);
  pxText(txt, 60, y, 20, '#e9dcff', 'rgba(10,18,32,0.95)', 'left');
}

function drawCreative() {
  ctx.fillStyle = 'rgba(8,14,26,0.9)';
  ctx.fillRect(0, 0, W, H);
  pxPanel(24, 24, W - 48, CRE_Y.headH, { cut: 14, bg: 'rgba(26,32,48,0.94)' });
  pxText('创造中心', W / 2, 62, 40, '#d6a8ff');
  pxText('跑酷关卡与机关 mod 都交给 AI 做：复制提示词 → 粘回结果', W / 2, 102, 19, 'rgba(190,214,242,0.8)');

  const btns = getUIButtons().list;
  pxSection(CRE_Y.secA, '跑酷关卡 · 复制提示词给 AI，粘回结果生成一关');
  pxSection(CRE_Y.secB, '机关 mod · 装进 mod 库，无尽模式全局生效');
  pxSection(CRE_Y.secC, '我的关卡作品 · 点卡片试玩');
  // 主按钮（排除作品卡与返回）
  for (const b of btns) {
    if (b.id === 'back' || b.ugc || b.ugcShare || b.ugcDel || b.ugcEditA) continue;
    pxButton(b, !!G.pressed[b.id]);
  }
  // 作品卡
  const ugcCards = btns.filter(b => b.ugc);
  const ugcMinis = btns.filter(b => b.ugcShare || b.ugcDel || b.ugcEditA);
  for (const b of ugcCards) drawUgcCard(b, b.ugc);
  for (const b of ugcMinis) {
      if (b.ugcShare) drawMiniBtn(b, '分享');
      if (b.ugcEditA) drawMiniBtn(b, '素材');
      if (b.ugcDel) drawMiniBtn(b, '删');
    }
  for (const b of ugcCards) {
    if (b.ugc.bgAsset) pxText('·图', b.x + 62, b.y + 18, 14, 'rgba(140,232,255,0.8)', 'rgba(10,18,32,0.9)', 'left');
  }
  if (!ugcCards.length) pxText('还没有作品，先复制上方提示词去生成一关吧', W / 2, CRE_Y.cardY + 90, 20, 'rgba(255,255,255,0.6)');
  else if (ugcListSync().length > 4) pxText('更多作品…', W / 2, CRE_Y.cardY + 92 * 2 + 16, 17, 'rgba(255,255,255,0.45)');
  const back = btns.find(b => b.id === 'back');
  if (back) pxButton(back, !!G.pressed.back);
}

/* 素材管理（ugcedit） */
function drawUgcEdit() {
  ctx.fillStyle = 'rgba(8,14,26,0.92)';
  ctx.fillRect(0, 0, W, H);
  const it = ugcListSync().find(x => x.id === G.ugcEditId);
  if (!it) { pxText('作品不存在', W / 2, 300, 34, '#ffffff'); return; }
  pxPanel(24, 24, W - 48, 620, { cut: 14, bg: 'rgba(26,32,48,0.94)' });
  pxText('素材管理', W / 2, 70, 40, '#d6a8ff');
  pxText('「' + (it.title || '无名关卡') + '」', W / 2, 116, 26, '#ffffff');
  pxText('给关卡配图：上传一张本地图片，或粘贴图片链接', W / 2, 152, 19, 'rgba(190,214,242,0.85)');

  const assets = it.assets || [];
  if (!assets.length) {
    pxText('还没有素材。上传一张图后，可设为关卡背景，或让 AI 在 JSON 里引用', W / 2, 320, 19, 'rgba(255,255,255,0.6)');
  }
  assets.forEach((a, i) => {
    const y = 220 + i * 90;
    pxRect(44, y, W - 88, 80, 10);
    ctx.fillStyle = 'rgba(14,22,40,0.9)';
    ctx.fill();
    if (a.srcType === 'data') {
      const im = ugcImg(a.src);
      if (im) ctx.drawImage(im, 52, y + 10, 60, 60);
    } else {
      pxText('URL', 82, y + 46, 18, 'rgba(140,232,255,0.9)', 'rgba(10,18,32,0.9)');
    }
    pxText(a.name, 130, y + 32, 21, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
    const tag = it.bgAsset === a.id ? '已是关卡背景' : (a.srcType === 'data' ? '本地图' : '网络图');
    pxText(tag, 130, y + 60, 16, it.bgAsset === a.id ? '#ffd34d' : 'rgba(190,214,242,0.75)', 'rgba(10,18,32,0.9)', 'left');
    pxMini({ x: W - 240, y: y + 26, w: 96, h: 32 }, it.bgAsset === a.id ? '当前背景' : '设为背景', 'rgba(40,60,100,0.95)', it.bgAsset === a.id ? 'rgba(255,220,130,0.5)' : 'rgba(140,195,255,0.4)');
    pxMini({ x: W - 136, y: y + 26, w: 60, h: 32 }, '删', 'rgba(60,30,40,0.95)', 'rgba(255,150,150,0.4)');
    // 点击热区（onDown 记录 ugcEditTap）
    const p = ugcEditTap;
    if (p && G.time - (ugcTapT || 0) < 0.1) {
      if (p.x >= W - 240 && p.x <= W - 144 && p.y >= y + 26 && p.y <= y + 58) {
        ugcSetBg(it, a.id); ugcEditTap = null;
      } else if (p.x >= W - 136 && p.x <= W - 76 && p.y >= y + 26 && p.y <= y + 58) {
        ugcDelAsset(it, a.id); ugcEditTap = null;
      }
    }
  });
  for (const b of getUIButtons().list) {
    if (b.id === 'back') continue;
    pxButton(b, !!G.pressed[b.id]);
  }
  const back = getUIButtons().list.find(b => b.id === 'back');
  if (back) pxButton(back, !!G.pressed.back);
}

function pxMini(b, label, fill, edge) {
  pxRect(b.x, b.y, b.w, b.h, 7);
  ctx.fillStyle = fill;
  ctx.fill();
  pxRect(b.x, b.y, b.w, b.h, 7);
  ctx.lineWidth = 2;
  ctx.strokeStyle = edge;
  ctx.stroke();
  pxText(label, b.x + b.w / 2, b.y + b.h / 2 + 1, 16, '#ffffff', 'rgba(10,18,32,0.95)');
}

// ---- 暂停与结算 ----
function drawPause() {
  ctx.fillStyle = 'rgba(8,14,26,0.66)';
  ctx.fillRect(0, 0, W, H);
  pxText('暂停', W / 2, 286, 62, '#ffffff');
  ctx.fillStyle = 'rgba(255,255,255,0.2)';
  ctx.fillRect(150, 330, W - 300, 3);
  for (const b of getUIButtons().list) pxButton(b, !!G.pressed[b.id]);
}

function drawGameOver() {
  ctx.fillStyle = 'rgba(8,14,26,0.68)';
  ctx.fillRect(0, 0, W, H);
  // 自定义模式（api.gameOver）结算：mod 给的标题/副标题，否则走默认文案
  const co = G.mode === 'custom' ? G.customOver : null;
  const fail = G.mode === 'level' || (co && !co.win);
  pxPanel(48, 218, W - 96, 470, { cut: 16, bg: 'rgba(26,32,48,0.94)' });

  pxText(co && co.title ? co.title : (fail ? '挑战失败' : '游戏结束'), W / 2, 290, 52,
    co && !co.win ? '#ff8f7e' : (co && co.win ? '#7de08a' : (fail ? '#ff8f7e' : '#ffffff')));

  if (co && co.sub) {
    pxText(co.sub, W / 2, 372, 26, '#ffffff');
    pxText('金币', 150, 424, 24, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.95)');
    pxNum(String(G.coins), 390, 424, 32, '#ffd34d', 'rgba(10,18,32,0.95)', 'right');
    pxText(G.customMode ? G.customMode.name : '', W / 2, 482, 24, 'rgba(125,224,138,0.9)');
  } else if (!fail) {
    const m = Math.floor(world.x / PX_PER_M);
    pxText('距离', 150, 374, 24, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.95)');
    pxNum(m + ' m', 390, 374, 32, '#ffffff', 'rgba(10,18,32,0.95)', 'right');
    pxText('金币', 150, 424, 24, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.95)');
    pxNum(String(G.coins), 390, 424, 32, '#ffd34d', 'rgba(10,18,32,0.95)', 'right');
    pxText(m >= G.best ? '新纪录！' : '最高 ' + G.best + ' m', W / 2, 482, 27, m >= G.best ? '#ffe066' : 'rgba(255,255,255,0.88)');
  } else {
    pxText('第 ' + (G.levelIndex + 1) + ' 关 · ' + (G.levelTitle || '随机赛道'), W / 2, 372, 26, '#ffffff');
    pxText('金币', 150, 426, 22, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.95)');
    pxNum(G.coins + '/' + G.levelCoinTotal, 390, 426, 28, '#ffd34d', 'rgba(10,18,32,0.95)', 'right');
    // 复盘：本次抽到的目标与进度
    if (G.objective) {
      const pr = objectiveProg(G.objective);
      const tip = G.objective.name + '：' + (pr || objectiveDesc(G.objective));
      let t = tip.length > 18 ? tip.slice(0, 18) : tip;
      pxText(t, W / 2, 484, 21, G.objective.color, 'rgba(10,18,32,0.95)');
    } else {
      pxText('再试一次，就差一点点', W / 2, 482, 24, 'rgba(255,255,255,0.85)');
    }
  }

  for (const b of getUIButtons().list) pxButton(b, !!G.pressed[b.id]);
}

/* 抵达终点但目标没完成 */
function drawLevelFail() {
  ctx.fillStyle = 'rgba(8,14,26,0.7)';
  ctx.fillRect(0, 0, W, H);
  pxPanel(48, 176, W - 96, 614, { cut: 16, bg: 'rgba(32,26,34,0.95)' });

  pxText('目标未达成', W / 2, 246, 48, '#ff8f7e');
  pxText('第 ' + (G.levelIndex + 1) + ' 关 · ' + (G.levelTitle || '随机赛道'), W / 2, 296, 23, 'rgba(255,255,255,0.9)');

  // 目标卡片：抽到的目标 vs 你的成绩
  pxPanel(76, 330, W - 152, 216, { cut: 10, bg: 'rgba(20,28,42,0.94)' });
  if (G.objective) {
    objBadge(114, 372, 21, G.objective);
    pxText(G.objective.name, 150, 366, 26, G.objective.color, 'rgba(10,18,32,0.92)', 'left');
    let dsc = objectiveDesc(G.objective);
    if (dsc.length > 16) dsc = dsc.slice(0, 16);
    pxText(dsc, 150, 398, 19, 'rgba(255,255,255,0.88)', 'rgba(10,18,32,0.9)', 'left');
    ctx.fillStyle = 'rgba(140,175,225,0.22)';
    ctx.fillRect(94, 422, W - 188, 2);
    pxText('你的成绩', 100, 450, 20, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.9)', 'left');
    const prog = objectiveProg(G.objective);
    pxNum(prog || '未达标', 100, 490, 26, '#ff9c8a', 'rgba(10,18,32,0.9)', 'left');
  }

  pxText('抵达终点只是前提，完成抽到的目标才算通关', W / 2, 572, 19, 'rgba(255,255,255,0.75)');

  // 身法提示：直接告诉玩家这次该怎么躲
  const TIP = {
    bar:      '低横杆按 ↓ 滑铲钻过去',
    ceil:     '顶刺要按住 ↓ 再跳，用矮跳钻过去',
    crusher:  '石板下压时按 ↓ 滑铲穿过去',
    pendulum: '摆锤荡到两侧时再滑铲钻过去',
    spike:    '尖刺起跳早一点，或轻点矮跳贴地过',
    flame:    '喷口红光是预警，火柱一起就跳过去',
    claw:     '蚩尤冲过来时跳起来，横扫扫不到空中',
    quake:    '刺浪从后面追来，看准浪头跳过去',
    rock:     '滚来的石头跳过去就没事',
    fall:     '别贴着坑边跳，起跳早一点',
    bullet:   '蚩尤的弹幕用跳和滑铲躲，别硬吃',
    boss:     '蚩尤突进时跳起来，贴地滑行就跳过去',
    spring:   '陷阱弹簧要跳过去，踩上就飞进刺里',
    enemy:    '浮空怪踩它头顶就能弹起来，别撞侧面',
  };
  const tip = TIP[G.deathCause];
  if (tip) pxText(tip, W / 2, 738, 20, '#8ce8ff', 'rgba(10,18,32,0.95)');

  for (const b of getUIButtons().list) pxButton(b, !!G.pressed[b.id]);
}

function drawLevelClear() {
  ctx.fillStyle = 'rgba(8,14,26,0.6)';
  ctx.fillRect(0, 0, W, H);
  pxPanel(48, 176, W - 96, 530, { cut: 16, bg: 'rgba(26,32,48,0.94)' });
  pxText('关卡完成！', W / 2, 242, 50, '#ffe066');

  pxText(G.mode === 'ugc'
    ? '自定义关卡 · ' + (G.levelTitle || '我的创造')
    : '第 ' + (G.levelIndex + 1) + ' 关 · ' + (G.levelTitle || '随机赛道'), W / 2, 294, 23, 'rgba(255,255,255,0.92)');

  // 达成的通关目标
  if (G.objective) {
    const txt = G.objective.name + ' · 达成';
    ctx.font = 'bold 22px "Microsoft YaHei", "PingFang SC", sans-serif';
    const tw = ctx.measureText(txt).width;
    const startX = W / 2 - (tw + 40) / 2;
    objBadge(startX + 16, 330, 16, G.objective);
    pxText(txt, startX + 40, 330, 22, '#7de08a', 'rgba(10,18,32,0.92)', 'left');
  }

  for (let i = 0; i < 3; i++) {
    const sx = W / 2 + (i - 1) * 100;
    const sy = 396;
    const shown = G.clearStars > i;
    const local = clamp((G.clearAnim - 0.25 - i * 0.22) / 0.3, 0, 1);
    const scale = shown ? 0.2 + 0.8 * ease.outElastic(local) : 1;
    ctx.save();
    ctx.translate(sx, sy);
    ctx.scale(scale, scale);
    if (shown) {
      ctx.globalAlpha = 0.35 + 0.3 * Math.sin(G.time * 5 + i);
      pxStar(0, 0, 52, 'rgba(255,230,120,0.5)');
      ctx.globalAlpha = 1;
      pxStar(0, 0, 42, '#ffd34d');
      pxStar(0, -2, 22, 'rgba(255,255,255,0.55)');
    } else {
      pxStar(0, 0, 40, 'rgba(255,255,255,0.14)');
    }
    ctx.restore();
  }

  pxText('金币', W / 2 - 62, 476, 22, 'rgba(190,214,242,0.85)', 'rgba(10,18,32,0.95)');
  pxNum(G.coins + ' / ' + G.levelCoinTotal, W / 2 + 62, 476, 28, '#ffd34d', 'rgba(10,18,32,0.95)', 'right');
  if (G.mode === 'ugc') {
    pxText('★ 到终点 · ★★ 全程无伤 · ★★★ 金币 70%', W / 2, 522, 22, 'rgba(255,255,255,0.9)');
  } else {
    const best = G.stars[G.levelIndex];
    pxText(best >= 3 ? '完美通关！' : '最佳评价 ' + best + ' 星', W / 2, 522, 24, 'rgba(255,255,255,0.9)');
  }

  for (const b of getUIButtons().list) pxButton(b, !!G.pressed[b.id]);
}

// ---- 按钮布局 ----
function getUIButtons() {
  const st = G.state;
  const list = [];
  const add = o => { list.push(o); return o; };
  const cx = w => Math.round((W - w) / 2);              // 水平居中
  const gw = Math.min(242, Math.round((W - 56) / 2));   // 两列卡片自适应宽

  // 祭坛三选一：热区与 drawAltarPanel 共用 altarCards()，保证所见即所点
  if (st === 'altar') {
    altarCards().forEach((c, i) => add({
      id: 'altar' + i, kind: 'gold', label: '',
      x: c.x, y: c.y, w: c.w, h: c.h,
      act: () => pickUpgrade(c.u.id),
    }));
  }

  if (st === 'menu') {
    const mw = Math.min(484, W - 40);
    // mod 注册的主菜单按钮（api.menuButton）：左上角一排小按钮
    (typeof UGC_MENU_BTNS !== 'undefined' ? UGC_MENU_BTNS : []).slice(0, 4).forEach((b, i) => {
      add({
        id: 'umb' + i, kind: 'slate', label: b.label, mini: true, menuMini: true, labelSize: 19,
        x: 16 + i * 118, y: 18, w: 108, h: 44,
        act: () => { try { b.fn(buildUgcApi(b.mod || null)); } catch (e) { try { (world.ugcLog = world.ugcLog || []).push('菜单按钮·' + b.label + ': ' + e.message); } catch (x) {} } },
      });
    });
    add({
      id: 'endless', kind: 'gold', label: '无尽模式', sub: '机关随距离逐步解锁',
      x: cx(mw), y: 356, w: mw, h: 104, glow: true, icon: ICO.play,
      act: () => startEndless(),
    });
    // mod 注册的自定义模式（api.mode）：副标题与无尽之间的小卡行（无 mod 模式时不占位）
    const modes = (typeof UGC_MODES !== 'undefined' ? UGC_MODES : []).slice(0, 4);
    if (modes.length) {
      const n = modes.length;
      const cw2 = Math.floor((mw - 12 * (n - 1)) / n);
      modes.forEach((d, i) => {
        add({
          id: 'cmode' + d.id, kind: 'green', label: d.name, sub: d.desc || 'mod 自定义模式',
          mini: true, labelSize: n > 2 ? 21 : 25,
          x: cx(mw) + i * (cw2 + 12), y: 268, w: cw2, h: 76,
          act: () => startCustomMode(d),
        });
      });
    }
    add({ id: 'levelmode', kind: 'blue', label: '关卡模式', sub: '牌库 ' + CARD_TOTAL + ' 张 · 每关抽 ' + CARDS_PER_LEVEL + ' 张',
      x: cx(mw), y: 472, w: mw, h: 104, icon: ICO.grid,
      act: () => openLevelSelect(),
    });
    add({ id: 'creative', kind: 'slate', label: '创造中心', sub: '复制提示词，让 AI 帮你做一关',
      x: cx(mw), y: 684, w: mw, h: 92, icon: ICO.creative,
      act: () => openCreative(),
    });
    // 皮肤/宠物/音乐/追击者：宽屏靠右与数据面板同行；窄屏居中一行（数据面板已上移让位）
    if (W >= 640) {
      add({ id: 'skin', x: W - 100, y: 606, w: 72, h: 76, act: () => openSkinSelect() });
      add({ id: 'pet', x: W - 180, y: 606, w: 72, h: 76, act: () => togglePet() });
      add({ id: 'music', x: W - 260, y: 606, w: 72, h: 76, act: () => musicCycle() });
      add({ id: 'chaser', x: W - 340, y: 606, w: 72, h: 76, act: () => cycleChaser() });
    } else {
      const bx0 = Math.round(W / 2) - 200;
      add({ id: 'music', x: bx0, y: 606, w: 72, h: 76, act: () => musicCycle() });
      add({ id: 'pet', x: bx0 + 84, y: 606, w: 72, h: 76, act: () => togglePet() });
      add({ id: 'skin', x: bx0 + 168, y: 606, w: 72, h: 76, act: () => openSkinSelect() });
      add({ id: 'chaser', x: bx0 + 252, y: 606, w: 72, h: 76, act: () => cycleChaser() });
    }
  }

  if (st === 'skins') {
    // 自定义皮肤数量动态变化 → 列数/行高自适应，保证不压到底部按钮
    const cols = W >= 900 ? 3 : 2;
    const rows = Math.ceil(SKINS.length / cols);
    const rowH = Math.min(118, Math.floor(556 / rows));
    const cardH = rowH - 8;
    for (let i = 0; i < SKINS.length; i++) {
      const col = i % cols, row = Math.floor(i / cols);
      const bx = 22 + col * (gw + 12), by = 104 + row * rowH;
      // 自定义皮肤「删」小按钮先注册先命中（onDown 正序短路），不挡其余区域的换肤点击
      if (SKINS[i].custom) {
        add({ id: 'skdel' + i, skinDel: i, x: bx + gw - 46, y: by + 6, w: 38, h: 30, act: () => tryDeleteCustomSkin(i) });
      }
      add({ id: 'sk' + i, skin: i, x: bx, y: by, w: gw, h: cardH, act: () => trySetSkin(i) });
    }
    add({ id: 'skadd', kind: 'blue', label: '＋ 添加自定义皮肤', sub: '上传图片做一套机灵皮肤（最多 ' + UGC_MAX_SKINS + ' 套）',
      x: cx(240) + 124, y: 700, w: 240, h: 78, act: () => openSkinAddUI() });
    add({ id: 'back', kind: 'slate', label: '返回主菜单', x: cx(240) - 124, y: 700, w: 240, h: 78, act: () => backToMenu() });
  }

  if (st === 'levels') {
    for (let i = 0; i < LEVELS.length; i++) {
      const col = i % 2, row = Math.floor(i / 2);
      add({
        id: 'lv' + i, level: i,
        x: 22 + col * (gw + 12), y: 104 + row * 94, w: gw, h: 86,
        act: () => tryStartLevel(i),
      });
    }
    // 我的创造：点卡试玩；右下小按钮 = 分享 / 删除（小按钮先注册，命中优先于整卡）
    ugcListSync().slice(0, 4).forEach((it, i) => {
      const col = i % 2, row = Math.floor(i / 2);
      const bx = 22 + col * (gw + 12), by = 704 + row * 82, bw = gw, bh = 70;
      add({ id: 'uss' + it.id, ugcShare: it, x: bx, y: by + 40, w: bw * 0.55, h: 30, act: () => showUgcShare(it) });
      add({ id: 'usd' + it.id, ugcDel: it, x: bx + bw * 0.55, y: by + 40, w: bw * 0.45, h: 30, act: () => ugcDeleteAsk(it) });
      add({ id: 'up' + it.id, ugc: it, x: bx, y: by, w: bw, h: bh, act: () => startUgcLevel(it) });
    });
    add({ id: 'back', kind: 'slate', label: '返回主菜单', x: cx(240), y: 874, w: 240, h: 78, act: () => backToMenu() });
  }

  if (st === 'creative') {
    const iX0 = Math.round((W - (gw * 2 + 12)) / 2);
    const dw = Math.floor((gw * 2 - 24) / 3);   // 三列等宽（与两列区左右对齐）
    // ── 分区 A：跑酷关卡 ── 三档难度提示词复制
    const diffs = [
      { id: 'simple', label: '简单', sub: '坑浅速慢' },
      { id: 'normal', label: '普通', sub: '常规跑酷' },
      { id: 'hard', label: '硬核', sub: '给老玩家' },
    ];
    diffs.forEach((d, i) => {
      add({
        id: 'cd' + d.id, kind: i === 1 ? 'gold' : 'blue', label: d.label, sub: d.sub,
        x: iX0 + i * (dw + 12), y: CRE_Y.diffY, w: dw, h: 84, labelSize: 30,
        act: () => copyPrompt(d.id),
      });
    });
    add({
      id: 'import', kind: 'gold', label: '粘贴 AI 结果', sub: 'AI 返回的关卡 JSON 粘这里',
      x: iX0, y: CRE_Y.impY, w: gw, h: 84,
      act: () => ugcImportUI(false),
    });
    add({
      id: 'importshare', kind: 'blue', label: '粘贴分享码', sub: '朋友的 UGC1: 码，粘了就能玩',
      x: iX0 + gw + 12, y: CRE_Y.impY, w: gw, h: 84,
      act: () => ugcImportUI(true),
    });
    // ── 分区 B：机关 mod ── 与关卡同级：提示词 / 导入 平铺在主界面
    add({
      id: 'modprompt', kind: 'green', label: '复制 mod 提示词', sub: '让 AI 帮你写机关 mod',
      x: iX0, y: CRE_Y.modImpY, w: gw, h: 84, labelSize: 26,
      act: () => copyModPrompt(),
    });
    add({
      id: 'modimport', kind: 'gold', label: '粘贴 mod 导入', sub: 'mod JSON 或 MOD1: 码',
      x: iX0 + gw + 12, y: CRE_Y.modImpY, w: gw, h: 84, labelSize: 26,
      act: () => ugcModImportUI(),
    });
    const row2 = [
      { id: 'modlib', kind: 'green', label: 'mod 库', sub: '列表·分享·删', act: () => ugcModsUI() },
      { id: 'moddemo', kind: 'blue', label: '载入示例包', sub: '官方 5 个 mod', act: () => ugcModDemoLoad() },
      { id: 'globg', kind: 'slate', label: '全局背景', sub: '通用背景图', act: () => ugcGlobalBgUI() },
      { id: 'gltex', kind: 'slate', label: '全局材质', sub: '金币·蚩尤·地面', act: () => ugcGlobalTexUI() },
    ];
    const dw4 = Math.floor((gw * 2 - 36) / 4);   // 四列等宽
    row2.forEach((b, i) => {
      add({ ...b, x: iX0 + i * (dw4 + 12), y: CRE_Y.modRowY, w: dw4, h: 64, labelSize: 19 });
    });
    // ── 分区 C：我的关卡作品 ── 点卡试玩；卡内小按钮 = 分享/素材/删（先注册，命中优先）
    const items = ugcListSync();
    items.slice(0, 4).forEach((it, i) => {
      const col = i % 2, row = Math.floor(i / 2);
      const bx = iX0 + col * (gw + 12), by = CRE_Y.cardY + row * 92, bw = gw, bh = 86;
      add({ id: 'cs' + it.id, ugcShare: it, x: bx, y: by + 58, w: bw * 0.46, h: 24, act: () => showUgcShare(it) });
      add({ id: 'cid' + it.id, ugcEditA: it, x: bx + bw * 0.48, y: by + 58, w: bw * 0.28, h: 24, act: () => openUgceEdit(it.id) });
      add({ id: 'ce' + it.id, ugcDel: it, x: bx + bw * 0.78, y: by + 58, w: bw * 0.22, h: 24, act: () => ugcDeleteAsk(it) });
      add({ id: 'cp' + it.id, ugc: it, x: bx, y: by, w: bw, h: bh, act: () => startUgcLevel(it) });
    });
    add({ id: 'back', kind: 'slate', label: '返回主菜单', x: cx(240), y: CRE_Y.backY, w: 240, h: 74, act: () => backToMenu() });
  }

  if (st === 'ugcedit') {
    const it = ugcListSync().find(x => x.id === G.ugcEditId);
    if (it) {
      const eX0 = Math.round((W - (gw * 2 + 12)) / 2);
      add({ id: 'assetfile', kind: 'blue', label: '上传图片', x: eX0, y: 700, w: gw, h: 76, labelSize: 26, act: () => ugcPickFile(it) });
      add({ id: 'asseturl', kind: 'slate', label: '粘贴图片链接', x: eX0 + gw + 12, y: 700, w: gw, h: 76, labelSize: 26, act: () => ugcAskUrl(it) });
    }
    add({ id: 'back', kind: 'slate', label: '返回创造中心', x: cx(240), y: 872, w: 240, h: 74, act: () => { G.state = 'creative'; } });
  }

  if (st === 'playing') {
    add({ id: 'pause', x: W - 84, y: 20, w: 64, h: 64, act: () => pauseGame(), silent: true });
    add({ id: 'sound', x: W - 160, y: 20, w: 64, h: 64, act: () => toggleSound(), silent: true });
    add({ id: 'music', x: W - 236, y: 20, w: 64, h: 64, act: () => musicCycle(), silent: true });
    add({ id: 'jump', x: BTN.jump.x, y: BTN.jump.y, w: BTN.jump.w, h: BTN.jump.h, act: () => doJump(), silent: true });
    add({ id: 'slide', x: BTN.slide.x, y: BTN.slide.y, w: BTN.slide.w, h: BTN.slide.h, act: () => doSlide(), silent: true });
    // 能量满了才显示冲刺键
    if (G.energy >= ENERGY_MAX && G.dashT <= 0) {
      add({ id: 'dash', x: cx(164), y: 852, w: 164, h: 58, act: () => doDash() });
    }
  }

  if (st === 'paused') {
    add({ id: 'resume', kind: 'gold', label: '继续游戏', x: cx(284), y: 382, w: 284, h: 84, act: () => resumeGame() });
    add({ id: 'restart', kind: 'blue', label: '重新开始', x: cx(284), y: 482, w: 284, h: 84, act: () => restartCurrent() });
    add({ id: 'home', kind: 'slate', label: '返回主菜单', x: cx(284), y: 582, w: 284, h: 78, act: () => backToMenu() });
  }

  if (st === 'gameover') {
    add({
      id: 'retry', kind: 'gold', label: G.mode === 'level' ? '再试一次' : '再来一次',
      x: cx(284), y: 540, w: 284, h: 82, act: () => restartCurrent(),
    });
    add({ id: 'home', kind: 'slate', label: '返回主菜单', x: cx(260), y: 634, w: 260, h: 72, act: () => backToMenu() });
  }

  if (st === 'levelfail') {
    add({
      id: 'retry', kind: 'gold', label: '重抽赛道与目标', labelSize: 30,
      x: cx(308), y: 618, w: 308, h: 82, act: () => restartCurrent(),
    });
    add({ id: 'home', kind: 'slate', label: '返回主菜单', x: cx(260), y: 712, w: 260, h: 72, act: () => backToMenu() });
  }

  if (st === 'levelclear') {
    if (G.mode === 'ugc') {
      add({ id: 'again', kind: 'gold', label: '再来一次', x: 128, y: 560, w: 284, h: 78, act: () => restartUgc() });
    } else {
      add({
        id: 'next', kind: 'gold',
        label: G.levelIndex + 1 < LEVELS.length ? '下一关' : '返回主菜单',
        x: 128, y: 560, w: 284, h: 78, act: () => nextLevel(),
      });
    }
    add({ id: 'home', kind: 'slate', label: '返回主菜单', x: 140, y: 652, w: 260, h: 70, act: () => backToMenu() });
  }

  return { list };
}
