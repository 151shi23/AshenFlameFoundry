'use strict';

/* 渲染、机关绘制、输入、主循环 */

// ---- 尖刺 ----
function drawSpikeRow(x, baseY, w, n, h, withBase) {
  if (h <= 1) return;
  const sw = w / n;
  for (let i = 0; i < n; i++) {
    const x0 = x + i * sw;
    const tipX = x0 + sw / 2;
    const tipY = baseY - h;
    // 右暗面
    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(x0 + sw, baseY);
    ctx.lineTo(tipX, baseY);
    ctx.closePath();
    ctx.fillStyle = '#6f7684';
    ctx.fill();
    // 左亮面
    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(x0 + 1, baseY);
    ctx.lineTo(tipX, baseY);
    ctx.closePath();
    ctx.fillStyle = '#d5dae4';
    ctx.fill();
    // 高光脊
    ctx.beginPath();
    ctx.moveTo(tipX - 1, tipY + 2);
    ctx.lineTo(tipX - 1, baseY);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = 'rgba(255,255,255,0.85)';
    ctx.stroke();
    // 尖端高光
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(tipX - 1.5, tipY, 3, 4);
  }
  if (withBase) {
    ctx.fillStyle = '#39404e';
    ctx.fillRect(x - 5, baseY - 3, w + 10, 12);
    ctx.fillStyle = '#5a6376';
    ctx.fillRect(x - 5, baseY - 3, w + 10, 4);
    for (let i = 0; i <= n; i++) {
      ctx.fillStyle = '#98a1b4';
      ctx.fillRect(x - 5 + i * sw - 1.5, baseY + 1, 3, 3);
    }
  }
}

// ---- 场景绘制 ----
function drawBackground() {
  // 自定义背景优先级：单关背景（关卡 bgAsset / mod 的 api.bg）> 全局背景 > 默认森林
  const bgim = UGC_BG.img || (UGC_BG.src ? ugcImg(UGC_BG.src) : null) || GLOBAL_BG.img;
  if (bgim) {
    const bh = GROUND_Y;
    const bw = bgim.width / bgim.height * bh;
    const phase = world.x * 0.12 + G.time * 10;
    let x = -(phase % bw);
    let guard = 0;
    while (x < W && guard++ < 12) {
      ctx.drawImage(bgim, x, 0, bw, bh);
      x += bw;
    }
    const shade = ctx.createLinearGradient(0, GROUND_Y - 340, 0, GROUND_Y);
    shade.addColorStop(0, 'rgba(16,44,30,0)');
    shade.addColorStop(1, 'rgba(16,44,30,0.3)');
    ctx.fillStyle = shade;
    ctx.fillRect(0, GROUND_Y - 340, W, 340);
    return;
  }
  const im = IMG.bg;
  if (!im) return;
  const bh = GROUND_Y;
  const bw = bh * im.width / im.height;
  const phase = world.x * 0.12 + G.time * 10;
  let k = Math.floor(phase / bw);
  let x = k * bw - phase;
  while (x < W) {
    if (((k % 2) + 2) % 2 === 1) {
      ctx.save();
      ctx.translate(x + bw, 0);
      ctx.scale(-1, 1);
      ctx.drawImage(im, 0, 0, bw, bh);
      ctx.restore();
    } else {
      ctx.drawImage(im, x, 0, bw, bh);
    }
    k++;
    x += bw;
  }
  const shade = ctx.createLinearGradient(0, GROUND_Y - 340, 0, GROUND_Y);
  shade.addColorStop(0, 'rgba(16,44,30,0)');
  shade.addColorStop(1, 'rgba(16,44,30,0.3)');
  ctx.fillStyle = shade;
  ctx.fillRect(0, GROUND_Y - 340, W, 340);
}

// ---- 天气实体：雷暴落雷 / 酸雨腐蚀区 ----
function drawWeather() {
  // 落雷：预警圈（天上引线）→ 折线雷柱
  for (const b of world.bolts) {
    const sx = b.x - world.x;
    if (sx < -90 || sx > W + 90) continue;
    if (b.t < b.warn) {
      const k = clamp(b.t / b.warn, 0, 1);
      const r = 76 - 34 * k;
      ctx.beginPath();
      ctx.ellipse(sx, GROUND_Y - 4, r, r * 0.32, 0, 0, Math.PI * 2);
      ctx.lineWidth = 4;
      ctx.strokeStyle = 'rgba(150,190,255,' + (0.35 + 0.4 * k).toFixed(3) + ')';
      ctx.stroke();
      ctx.fillStyle = 'rgba(140,180,255,' + (0.10 + 0.16 * k).toFixed(3) + ')';
      ctx.fill();
      ctx.save();
      ctx.globalAlpha = 0.22 + 0.34 * k;
      ctx.lineWidth = 3;
      ctx.strokeStyle = 'rgba(200,225,255,0.9)';
      ctx.beginPath();
      ctx.moveTo(sx, 0);
      ctx.lineTo(sx, GROUND_Y - 6);
      ctx.stroke();
      ctx.restore();
    } else {
      const life = clamp(1 - (b.t - b.warn) / 0.45, 0, 1);
      ctx.save();
      ctx.globalAlpha = life;
      ctx.lineWidth = 7;
      ctx.lineJoin = 'round';
      ctx.strokeStyle = 'rgba(236,246,255,0.95)';
      ctx.beginPath();
      ctx.moveTo(sx, 0);
      let yy = 0, xx = sx;
      while (yy < GROUND_Y) {
        yy += 96;
        xx = sx + rnd(-16, 16);
        ctx.lineTo(xx, Math.min(yy, GROUND_Y));
      }
      ctx.stroke();
      const g2 = ctx.createRadialGradient(sx, GROUND_Y - 10, 6, sx, GROUND_Y - 10, 110);
      g2.addColorStop(0, 'rgba(255,255,255,' + (0.55 * life).toFixed(3) + ')');
      g2.addColorStop(1, 'rgba(180,210,255,0)');
      ctx.fillStyle = g2;
      ctx.fillRect(sx - 110, GROUND_Y - 120, 220, 130);
      ctx.restore();
    }
  }
  // 酸雨腐蚀区：绿色酸池 + 气泡，踩进去会被拖慢
  for (const p of world.acidPools) {
    const sx = p.x - world.x;
    if (sx < -120 || sx > W + 120) continue;
    const grow = clamp(p.t / 0.25, 0, 1);
    const fade = clamp((p.life - p.t) / 0.6, 0, 1);
    ctx.save();
    ctx.globalAlpha = grow * fade;
    const g3 = ctx.createRadialGradient(sx, GROUND_Y + 6, 4, sx, GROUND_Y + 6, 72);
    g3.addColorStop(0, 'rgba(186,240,110,0.85)');
    g3.addColorStop(0.55, 'rgba(140,210,70,0.5)');
    g3.addColorStop(1, 'rgba(110,180,50,0)');
    ctx.fillStyle = g3;
    ctx.beginPath();
    ctx.ellipse(sx, GROUND_Y + 6, 72, 20, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(226,255,170,0.8)';
    for (let i = 0; i < 3; i++) {
      const t2 = (G.time * 1.6 + i * 0.7) % 1;
      ctx.beginPath();
      ctx.arc(sx - 34 + i * 32 + Math.sin(G.time * 3 + i) * 6, GROUND_Y + 4 - t2 * 26, 3 + t2 * 3, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }
}

// ---- 天气氛围：整屏色调 + 风沙/雾气颗粒感 ----
function drawWeatherOverlay() {
  const w = G.weather;
  if (!w) return;
  const fadeIn = clamp(w.t / 1.4, 0, 1);
  const fadeOut = clamp((w.life - w.t) / 2.2, 0, 1);
  const a = fadeIn * fadeOut;
  if (a <= 0.01) return;
  if (w.id === 'fog') {
    ctx.fillStyle = 'rgba(206,220,238,' + (0.34 * a).toFixed(3) + ')';
  } else if (w.id === 'sand') {
    ctx.fillStyle = 'rgba(214,164,88,' + (0.3 * a).toFixed(3) + ')';
  } else if (w.id === 'storm') {
    ctx.fillStyle = 'rgba(22,32,62,' + (0.2 * a).toFixed(3) + ')';
  } else {
    ctx.fillStyle = 'rgba(128,190,64,' + (0.13 * a).toFixed(3) + ')';
  }
  ctx.fillRect(0, 0, W, H);
}

// ---- 暗角：固定画面质感，避免全屏死平 ----
let VIGNETTE = null;
function makeVignette(w, h) {
  const c = document.createElement('canvas');
  const s = 0.25;   // 低分辨率生成一张就够，绘制时拉满全屏
  c.width = Math.max(2, Math.round(w * s));
  c.height = Math.max(2, Math.round(h * s));
  const g = c.getContext('2d');
  const r = g.createRadialGradient(c.width / 2, c.height * 0.46, c.height * 0.22,
    c.width / 2, c.height * 0.5, c.height * 0.74);
  r.addColorStop(0, 'rgba(0,0,0,0)');
  r.addColorStop(0.72, 'rgba(6,10,20,0.10)');
  r.addColorStop(1, 'rgba(4,8,16,0.30)');
  g.fillStyle = r;
  g.fillRect(0, 0, c.width, c.height);
  return { cv: c, w: w, h: h };
}
function drawVignette() {
  if (!VIGNETTE || VIGNETTE.w !== W || VIGNETTE.h !== H) VIGNETTE = makeVignette(W, H);
  ctx.drawImage(VIGNETTE.cv, 0, 0, W, H);
}

/* ---- 天空渐变：白天青蓝 → 夜晚深靛（带地平线暖光） ---- */
function drawSkyGradient(na) {
  const top = lerpColor('#2f8fe0', '#0c1330', na);
  const mid = lerpColor('#63b8f2', '#16204a', na);
  const hor = lerpColor('#a8dcf5', '#2b3566', na);
  const g = ctx.createLinearGradient(0, 0, 0, GROUND_Y);
  g.addColorStop(0, top);
  g.addColorStop(0.62, mid);
  g.addColorStop(1, hor);
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, GROUND_Y);
}

/* ---- 云层：预生成一张可平铺的软云，按 0.28 的视差慢慢飘 ---- */
let CLOUD_TILE = null;
function makeCloudTile() {
  const cw = 1024, ch = 300;
  const c = document.createElement('canvas');
  c.width = cw; c.height = ch;
  const g = c.getContext('2d');
  for (let i = 0; i < 16; i++) {
    const cx = rnd(0, cw), cy = rnd(30, ch - 60);
    const rx = rnd(70, 165), ry = rx * rnd(0.34, 0.5);
    const grd = g.createRadialGradient(cx, cy, ry * 0.2, cx, cy, rx);
    grd.addColorStop(0, 'rgba(255,255,255,0.55)');
    grd.addColorStop(0.6, 'rgba(255,255,255,0.22)');
    grd.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grd;
    g.beginPath();
    g.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
    g.fill();
  }
  return c;
}
function drawClouds(na) {
  if (!CLOUD_TILE) CLOUD_TILE = makeCloudTile();
  const tileW = Math.max(640, W * 0.9);
  const tileH = tileW * CLOUD_TILE.height / CLOUD_TILE.width;
  const phase = world.x * 0.28 + G.time * 12;
  let x = -(phase % tileW);
  ctx.save();
  ctx.globalAlpha = 0.75 * (1 - na * 0.55);
  let guard = 0;
  while (x < W + tileW && guard++ < 8) {
    ctx.drawImage(CLOUD_TILE, x, GROUND_Y - tileH - 40, tileW, tileH);
    x += tileW;
  }
  ctx.restore();
}

/* ---- 前景草叶剪影：压在地面之上，视差 1.15，制造纵深 ---- */
let GRASS_FG = null;
function makeGrassFg() {
  const cw = 900, ch = 130;
  const c = document.createElement('canvas');
  c.width = cw; c.height = ch;
  const g = c.getContext('2d');
  for (let i = 0; i < 130; i++) {
    const bx = rnd(0, cw);
    const h = rnd(26, 84);
    const w = rnd(3, 7);
    const lean = rnd(-16, 16);
    const shade = rnd(0, 1);
    g.fillStyle = shade < 0.5 ? 'rgba(38,60,30,0.95)' : 'rgba(56,84,40,0.95)';
    g.beginPath();
    g.moveTo(bx, ch);
    g.quadraticCurveTo(bx + lean * 0.5, ch - h * 0.6, bx + lean, ch - h);
    g.quadraticCurveTo(bx + lean * 0.5 + w, ch - h * 0.55, bx + w, ch);
    g.closePath();
    g.fill();
  }
  return c;
}
function drawForegroundGrass() {
  if (!GRASS_FG) GRASS_FG = makeGrassFg();
  const tileW = Math.max(420, W * 0.62);
  const tileH = tileW * GRASS_FG.height / GRASS_FG.width;
  const phase = world.x * 1.15;
  let x = -(phase % tileW);
  ctx.save();
  ctx.globalAlpha = 0.9;
  let guard = 0;
  while (x < W + tileW && guard++ < 6) {
    ctx.drawImage(GRASS_FG, x, GROUND_Y - tileH * 0.62, tileW, tileH);
    x += tileW;
  }
  ctx.restore();
}

/* ---- 月面段：星空 + 冷色罩 ---- */
let STARFIELD = null;
function makeStarfield() {
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const g = c.getContext('2d');
  for (let i = 0; i < 190; i++) {
    const r = rnd(0.7, 2.1);
    g.globalAlpha = rnd(0.35, 0.95);
    g.fillStyle = '#ffffff';
    g.beginPath();
    g.arc(rnd(0, W), rnd(0, GROUND_Y - 20), r, 0, Math.PI * 2);
    g.fill();
  }
  return c;
}
function drawMoonOverlay() {
  if (!(G.moonT > 0)) return;
  if (!STARFIELD || STARFIELD.width !== Math.round(W)) STARFIELD = makeStarfield();
  const k = clamp(Math.min(G.moonT, 13 - G.moonT) / 1.2, 0, 1);
  ctx.save();
  ctx.globalAlpha = k;
  ctx.drawImage(STARFIELD, 0, 0, W, H);
  ctx.globalAlpha = 0.16 * k;
  ctx.fillStyle = '#8fb6ff';
  ctx.fillRect(0, 0, W, H);
  ctx.restore();
}

/* ---- 后期：胶片颗粒（预生成噪声贴图，低透明度平铺） ---- */
let GRAIN = null;
function makeGrain() {
  // 384 的一整张贴图拉满全屏：噪声颗粒约 1.4px，视觉上还是颗粒，但每帧只需一次绘制
  const s = 384;
  const c = document.createElement('canvas');
  c.width = s; c.height = s;
  const g = c.getContext('2d');
  const img = g.createImageData(s, s);
  for (let i = 0; i < img.data.length; i += 4) {
    const v = 120 + Math.random() * 135;
    img.data[i] = v; img.data[i + 1] = v; img.data[i + 2] = v;
    img.data[i + 3] = Math.random() < 0.5 ? 15 : 6;
  }
  g.putImageData(img, 0, 0);
  return c;
}
function drawGrain() {
  if (!GRAIN) GRAIN = makeGrain();
  ctx.save();
  ctx.globalAlpha = 0.42;
  const ox = -((G.time * 60) % 30), oy = -((G.time * 44) % 30);
  ctx.drawImage(GRAIN, ox, oy, W * 1.06, H * 1.06);
  ctx.restore();
}

/* ---- 高速时的径向速度线（在已有 speedLines 之上再压一层纵深） ---- */
function drawRadialSpeed() {
  const k = FX.speedLines;
  if (k <= 0.28) return;
  const cx = PLAYER_X + 40, cy = H * 0.52;
  const a = (k - 0.28) * 0.5;
  ctx.save();
  ctx.globalAlpha = a;
  ctx.strokeStyle = 'rgba(210,240,255,0.5)';
  ctx.lineWidth = 2;
  for (let i = 0; i < 14; i++) {
    const ang = (i / 14) * Math.PI * 2 + G.time * 0.6;
    const r0 = 220 + 60 * Math.sin(i * 2.3 + G.time * 3);
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(ang) * r0, cy + Math.sin(ang) * r0 * 0.75);
    ctx.lineTo(cx + Math.cos(ang) * (r0 + 130), cy + Math.sin(ang) * (r0 + 130) * 0.75);
    ctx.stroke();
  }
  ctx.restore();
}

function drawGaps() {
  for (const g of world.gaps) {
    const sx = g.x - world.x;
    if (sx > W || sx + g.w < 0) continue;
    ctx.fillStyle = '#1d1710';
    ctx.fillRect(sx, GROUND_Y, g.w, H - GROUND_Y);
    ctx.fillStyle = 'rgba(0,0,0,0.5)';
    ctx.fillRect(sx, GROUND_Y, 8, H - GROUND_Y);
    ctx.fillRect(sx + g.w - 8, GROUND_Y, 8, H - GROUND_Y);
    // 坑底尖刺
    const baseY = GROUND_Y + 152;
    const n = Math.max(2, g.spikes);
    drawSpikeRow(sx + 2, baseY, g.w - 4, n, 54, true);
    ctx.fillStyle = '#120d08';
    ctx.fillRect(sx, baseY + 8, g.w, H - baseY);
  }
}

function drawGround() {
  const x0 = world.x, x1 = world.x + W;
  let runs = [[x0, x1]];
  const holes = world.gaps.concat(world.fakeFloors.filter(f => f.broken));
  for (const g of holes) {
    if (g.x > x1 || g.x + g.w < x0) continue;
    const next = [];
    for (const seg of runs) {
      const a = seg[0], b = seg[1];
      if (g.x >= b || g.x + g.w <= a) { next.push(seg); continue; }
      if (g.x > a) next.push([a, Math.min(g.x, b)]);
      if (g.x + g.w < b) next.push([Math.max(g.x + g.w, a), b]);
    }
    runs = next;
  }
  for (const seg of runs) {
    const sx = seg[0] - world.x;
    const sw = seg[1] - seg[0];
    if (sx > W || sx + sw < 0 || sw <= 0) continue;
    ctx.fillStyle = '#68893a';
    ctx.fillRect(sx, GROUND_Y, sw, GRASS_H + 5);
    ctx.fillStyle = '#5c4128';
    ctx.fillRect(sx, GROUND_Y + GRASS_H + 5, sw, H - GROUND_Y - GRASS_H - 5);
    drawTileBand('grass', sx, GROUND_Y, sw, GRASS_H, { tileW: GRASS_TILE_W });
    ctx.fillStyle = 'rgba(58,40,26,0.55)';
    ctx.fillRect(sx, GROUND_Y + GRASS_H, sw, 5);
    drawTileBand('dirt', sx, GROUND_Y + GRASS_H + 5, sw, H - GROUND_Y - GRASS_H - 5, { tileW: DIRT_TILE_W });
    // 草皮顶沿受光 + 泥层纵深：地面不再是两块平色
    ctx.fillStyle = 'rgba(198,238,142,0.45)';
    ctx.fillRect(sx, GROUND_Y + 1, sw, 3);
    const dg = ctx.createLinearGradient(0, GROUND_Y + GRASS_H, 0, H);
    dg.addColorStop(0, 'rgba(0,0,0,0)');
    dg.addColorStop(1, 'rgba(0,0,0,0.45)');
    ctx.fillStyle = dg;
    ctx.fillRect(sx, GROUND_Y + GRASS_H, sw, H - GROUND_Y - GRASS_H);
  }
}

function drawFakeFloors() {
  for (const ff of world.fakeFloors) {
    if (ff.broken) continue;
    const sx = ff.x - world.x;
    if (sx > W || sx + ff.w < 0) continue;
    ctx.fillStyle = '#68893a';
    ctx.fillRect(sx, GROUND_Y, ff.w, GRASS_H + 5);
    ctx.fillStyle = '#5c4128';
    ctx.fillRect(sx, GROUND_Y + GRASS_H + 5, ff.w, H - GROUND_Y - GRASS_H - 5);
    drawTileBand('grass', sx, GROUND_Y, ff.w, GRASS_H, { tileW: GRASS_TILE_W });
    ctx.fillStyle = 'rgba(58,40,26,0.55)';
    ctx.fillRect(sx, GROUND_Y + GRASS_H, ff.w, 5);
    drawTileBand('dirt', sx, GROUND_Y + GRASS_H + 5, ff.w, H - GROUND_Y - GRASS_H - 5, { tileW: DIRT_TILE_W });
    const stress = ff.t > 0 ? 0.3 : 0.12;
    ctx.fillStyle = 'rgba(0,0,0,' + stress + ')';
    ctx.fillRect(sx, GROUND_Y, ff.w, H - GROUND_Y);
    ctx.strokeStyle = 'rgba(28,18,10,' + (ff.t > 0 ? 0.95 : 0.6) + ')';
    ctx.lineWidth = ff.t > 0 ? 3.5 : 2;
    const wob = ff.t > 0 ? Math.sin(G.time * 40) * 2 : 0;
    ctx.beginPath();
    ctx.moveTo(sx + ff.w * 0.34 + wob, GROUND_Y + 3);
    ctx.lineTo(sx + ff.w * 0.45, GROUND_Y + 30);
    ctx.lineTo(sx + ff.w * 0.3, GROUND_Y + 58);
    ctx.moveTo(sx + ff.w * 0.68 + wob, GROUND_Y + 5);
    ctx.lineTo(sx + ff.w * 0.58, GROUND_Y + 34);
    ctx.stroke();
  }
}

function drawPlatformAt(sx, w, top, h) {
  ctx.fillStyle = 'rgba(0,0,0,0.22)';
  ctx.fillRect(sx + 5, top + 8, w, h);
  ctx.fillStyle = '#68893a';
  ctx.fillRect(sx, top, w, GRASS_H + 5);
  ctx.fillStyle = '#5c4128';
  ctx.fillRect(sx, top + GRASS_H + 5, w, h - GRASS_H - 10);
  drawTileBand('grass', sx, top, w, GRASS_H, { tileW: GRASS_TILE_W, phase: 0 });
  ctx.fillStyle = 'rgba(58,40,26,0.55)';
  ctx.fillRect(sx, top + GRASS_H, w, 5);
  drawTileBand('dirt', sx, top + GRASS_H + 5, w, h - GRASS_H - 10, {
    tileW: DIRT_TILE_W, phase: 0, drawY: top + GRASS_H + 5,
  });
  ctx.fillStyle = '#241c14';
  ctx.fillRect(sx, top + h - 5, w, 5);
}

function drawPlatforms() {
  for (const p of world.platforms) {
    const sx = p.x - world.x;
    if (sx > W || sx + p.w < 0) continue;
    drawPlatformAt(sx, p.w, p.top, p.h);
  }
}

function drawMovingPlatforms() {
  for (const mp of world.movingPlatforms) {
    const sx = mp.x - world.x;
    if (sx > W || sx + mp.w < 0) continue;
    const top = movingPlatTop(mp);
    drawPlatformAt(sx, mp.w, top, 60);
    // 链条
    ctx.strokeStyle = 'rgba(120,132,154,0.55)';
    ctx.lineWidth = 4;
    ctx.setLineDash([8, 8]);
    ctx.beginPath();
    ctx.moveTo(sx + 22, 0);
    ctx.lineTo(sx + 22, top);
    ctx.moveTo(sx + mp.w - 22, 0);
    ctx.lineTo(sx + mp.w - 22, top);
    ctx.stroke();
    ctx.setLineDash([]);
  }
}

/* UGC 自定义机关：有 img 贴图（随 spd 旋转），无图则画默认旋转刀锋 */
function drawUgcHazards() {
  if (G.mode !== 'ugc' && G.mode !== 'endless') return;
  for (const e of world.ugcHazards || []) {
    if (e.dead) continue;
    const sx = e.x - world.x;
    if (sx < -180 || sx > W + 180) continue;
    const w = e.w || 52, h = e.h || 52;
    // 贴地影子
    ctx.save();
    ctx.globalAlpha = 0.22;
    ctx.fillStyle = '#000';
    ctx.beginPath();
    ctx.ellipse(sx, GROUND_Y - 6, w * 0.42, 7, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
    const im = e.img ? ugcImg(e.img) : null;
    if (im && im.width > 0 && im.height > 0) {
      // SVG 无固有尺寸时 width/height 可能为 0：此时回退默认刀锋绘制
      const rot = G.time * (e.spd || 0) + (e.phase || 0);
      ctx.save();
      ctx.translate(sx, e.y);
      if (rot) ctx.rotate(rot);
      try { ctx.drawImage(im, -w / 2, -h / 2, w, h); } catch (err) {}
      ctx.restore();
      continue;
    }
    // 默认刀锋：金属圆盘 + 三片旋转刃
    const rot = G.time * (e.spd || 7) + (e.phase || 0);
    ctx.save();
    ctx.translate(sx, e.y);
    ctx.rotate(rot);
    for (let i = 0; i < 3; i++) {
      ctx.rotate(Math.PI * 2 / 3);
      ctx.beginPath();
      ctx.moveTo(0, -w * 0.34);
      ctx.lineTo(w * 0.52, -w * 0.10);
      ctx.lineTo(w * 0.30, w * 0.24);
      ctx.lineTo(0, w * 0.30);
      ctx.closePath();
      ctx.fillStyle = '#c7cfdd';
      ctx.fill();
      ctx.strokeStyle = '#5c6680';
      ctx.lineWidth = 2;
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.arc(0, 0, w * 0.30, 0, Math.PI * 2);
    ctx.fillStyle = '#39415a';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(0, 0, w * 0.13, 0, Math.PI * 2);
    ctx.fillStyle = '#8c97b4';
    ctx.fill();
    ctx.restore();
  }
}

/* mod 注册的自定义 UI：api.hud(key, '文本' | {text,color,size} | {label,val,max,color}) */
function drawUgcHud() {
  // mod 主动技能按钮（右下角纵排；onUpdate 里每帧调 api.button 才持续显示）
  const btns = world.ugcBtns || [];
  for (let i = 0; i < btns.length && i < 4; i++) {
    const b = btns[i];
    const bx = b.x !== undefined ? b.x : W - 36 - b.w;
    const by = b.y !== undefined ? b.y : H - 214 - i * (b.h + 14);
    b.x = bx; b.y = by;   // 回写实际坐标供命中检测
    ctx.globalAlpha = 0.92;
    pxRect(bx + 3, by + 5, b.w, b.h, 12);
    ctx.fillStyle = 'rgba(6,10,18,0.42)';
    ctx.fill();
    pxRect(bx, by, b.w, b.h, 12);
    ctx.fillStyle = b.color || '#c9962a';
    ctx.fill();
    pxRect(bx + 2, by + 2, b.w - 4, b.h - 4, 10);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.stroke();
    ctx.globalAlpha = 1;
    pxText(b.label, bx + b.w / 2, by + b.h / 2, Math.min(26, b.w * 0.28), '#ffffff', 'rgba(10,18,32,0.9)');
  }
  const hud = world.ugcHud;
  if (!hud) return;
  const keys = Object.keys(hud).slice(0, 16);
  if (!keys.length) return;
  let y = 186;
  for (const k of keys) {
    const c = hud[k];
    if (c == null) continue;
    if (typeof c === 'object') {
      if (c.val !== undefined && c.max) {
        // 进度条：左侧短条 + 右侧标签
        const bw = Math.min(190, W * 0.26), bx = 18;
        pxRect(bx, y, bw, 20, 6);
        ctx.fillStyle = 'rgba(10,16,30,0.62)';
        ctx.fill();
        const frac = clamp(c.val / c.max, 0, 1);
        if (frac > 0.005) {
          pxRect(bx + 3, y + 3, Math.max(4, (bw - 6) * frac), 14, 4);
          ctx.fillStyle = c.color || '#ff8f7e';
          ctx.fill();
        }
        pxText(String(c.label || k), bx + bw + 10, y + 10, 15, '#ffffff', 'rgba(10,18,32,0.92)', 'left');
      } else if (c.text !== undefined) {
        pxText(String(c.text).slice(0, 18), 18, y, clamp(c.size || 18, 12, 30), c.color || '#ffffff', 'rgba(10,18,32,0.92)', 'left');
      }
    } else {
      pxText(String(c).slice(0, 18), 18, y, 18, '#ffffff', 'rgba(10,18,32,0.92)', 'left');
    }
    y += 34;
    if (y > H - 120) break;
  }
}

function drawSpikeTraps() {
  for (const tr of world.spikeTraps) {
    const sx = tr.x - world.x;
    if (sx > W || sx + tr.w < 0) continue;
    const hh = spikeTrapHeight(tr);
    const cyc = 2.6;
    const t = (G.time + tr.phase) % cyc;
    // 凹槽
    ctx.fillStyle = '#241d15';
    ctx.fillRect(sx - 4, GROUND_Y - 2, tr.w + 8, 12);
    ctx.fillStyle = '#4c5568';
    ctx.fillRect(sx - 6, GROUND_Y + 4, tr.w + 12, 9);
    ctx.fillStyle = '#68718a';
    ctx.fillRect(sx - 6, GROUND_Y + 4, tr.w + 12, 3);
    // 预警红光
    if (t > 0.62 && t < 0.85) {
      ctx.fillStyle = 'rgba(255,74,52,' + (0.25 + 0.45 * Math.sin(G.time * 32)) + ')';
      ctx.fillRect(sx - 2, GROUND_Y - 3, tr.w + 4, 10);
    }
    // 尖刺
    if (hh > 0.01) {
      const h = 58 * ease.outCubic(Math.min(1, hh * 1.15));
      ctx.save();
      ctx.beginPath();
      ctx.rect(sx - 8, GROUND_Y - h, tr.w + 16, h + 8);
      ctx.clip();
      drawSpikeRow(sx, GROUND_Y + 4, tr.w, Math.max(2, Math.round(tr.w / 30)), h, false);
      ctx.restore();
      if (hh > 0.9 && t < 1.02) {
        FX.sburst(sx + rnd(0, tr.w), GROUND_Y - 40, 1, {
          vx0: -60, vx1: 60, vy0: -120, vy1: -30,
          life0: 0.2, life1: 0.4, size0: 4, size1: 9, color: 'rgba(255,220,140,0.9)',
        });
      }
    }
  }
}

function drawLowBars() {
  for (const lb of world.lowBars) {
    const sx = lb.x - world.x;
    if (sx > W || sx + lb.w < 0) continue;
    const bottom = GROUND_Y - lb.gapH;
    ctx.fillStyle = '#474b59';
    ctx.fillRect(sx, 0, lb.w, bottom);
    ctx.fillStyle = '#5d6273';
    ctx.fillRect(sx, 0, lb.w * 0.26, bottom);
    ctx.fillStyle = 'rgba(0,0,0,0.25)';
    ctx.fillRect(sx + lb.w - 8, 0, 8, bottom);
    // 警示条纹
    const sh = 36;
    const bw2 = 20;
    for (let i = 0; i * bw2 < lb.w + bw2; i++) {
      const x0 = sx + i * bw2;
      ctx.save();
      ctx.beginPath();
      ctx.rect(x0, bottom - sh, bw2, sh);
      ctx.clip();
      ctx.fillStyle = i % 2 ? '#f0c341' : '#33333d';
      ctx.fillRect(x0, bottom - sh, bw2, sh);
      ctx.restore();
    }
    ctx.fillStyle = '#2b2b34';
    ctx.fillRect(sx - 4, bottom - 3, lb.w + 8, 8);
    // 底部锯齿
    const n = Math.max(3, Math.round(lb.w / 26));
    const sw = lb.w / n;
    for (let i = 0; i < n; i++) {
      ctx.beginPath();
      ctx.moveTo(sx + i * sw, bottom + 5);
      ctx.lineTo(sx + i * sw + sw / 2, bottom + 20);
      ctx.lineTo(sx + (i + 1) * sw, bottom + 5);
      ctx.closePath();
      ctx.fillStyle = '#3a3a46';
      ctx.fill();
    }
  }
}

function drawCeilSpikes() {
  for (const cs of world.ceilSpikes) {
    const sx = cs.x - world.x;
    if (sx > W || sx + cs.w < 0) continue;
    const bottom = GROUND_Y - cs.low;
    const bodyH = bottom - 46;
    ctx.fillStyle = '#474b59';
    ctx.fillRect(sx, 0, cs.w, bodyH);
    ctx.fillStyle = '#5d6273';
    ctx.fillRect(sx, 0, cs.w * 0.22, bodyH);
    ctx.fillStyle = 'rgba(0,0,0,0.22)';
    ctx.fillRect(sx + cs.w - 8, 0, 8, bodyH);
    // 横梁
    ctx.fillStyle = '#2b2b34';
    ctx.fillRect(sx - 5, bodyH - 6, cs.w + 10, 10);
    // 朝下尖刺排
    const n = Math.max(3, Math.round(cs.w / 30));
    const sw = cs.w / n;
    for (let i = 0; i < n; i++) {
      const x0 = sx + i * sw;
      const tipY = bottom;
      ctx.beginPath();
      ctx.moveTo(x0, tipY - 46);
      ctx.lineTo(x0 + sw / 2, tipY);
      ctx.lineTo(x0, tipY);
      ctx.closePath();
      ctx.fillStyle = '#d5dae4';
      ctx.fill();
      ctx.beginPath();
      ctx.moveTo(x0 + sw / 2, tipY);
      ctx.lineTo(x0 + sw, tipY - 46);
      ctx.lineTo(x0 + sw, tipY);
      ctx.closePath();
      ctx.fillStyle = '#6f7684';
      ctx.fill();
    }
  }
}

function drawBouncePads() {
  for (const bp of world.bouncePads) {
    const sx = bp.x - world.x;
    if (sx > W || sx + bp.w < 0) continue;
    const pop = bp.t > 0 ? Math.max(0, 1 - (G.time - bp.t) * 7) : 0;
    const topH = 32 - pop * 16;
    // 底座
    ctx.fillStyle = '#39404e';
    ctx.fillRect(sx, GROUND_Y - 8, bp.w, 10);
    ctx.fillStyle = '#545d72';
    ctx.fillRect(sx, GROUND_Y - 8, bp.w, 4);
    // 弹簧
    ctx.strokeStyle = '#9aa3b6';
    ctx.lineWidth = 4;
    ctx.beginPath();
    for (let i = 0; i <= 6; i++) {
      const px = sx + 10 + (bp.w - 20) * i / 6;
      const py = i % 2 ? GROUND_Y - topH + 8 : GROUND_Y - 10;
      if (i === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    }
    ctx.stroke();
    // 顶板
    ctx.fillStyle = '#2f8fd0';
    ctx.fillRect(sx, GROUND_Y - topH, bp.w, 12);
    const g = ctx.createLinearGradient(0, GROUND_Y - topH, 0, GROUND_Y - topH + 12);
    g.addColorStop(0, 'rgba(140,225,255,0.95)');
    g.addColorStop(1, 'rgba(50,150,210,0.4)');
    ctx.fillStyle = g;
    ctx.fillRect(sx, GROUND_Y - topH, bp.w, 12);
    ctx.fillStyle = 'rgba(255,255,255,0.85)';
    ctx.fillRect(sx, GROUND_Y - topH, bp.w, 3);
    // 上行箭头
    const ax = sx + bp.w / 2;
    const ay = GROUND_Y - topH - 22 - Math.sin(G.time * 6) * 3;
    ctx.fillStyle = 'rgba(160,230,255,0.95)';
    ctx.beginPath();
    ctx.moveTo(ax, ay - 12);
    ctx.lineTo(ax + 11, ay + 4);
    ctx.lineTo(ax - 11, ay + 4);
    ctx.closePath();
    ctx.fill();
  }
}

function drawSigns() {
  for (const s of world.signs) {
    const cx = s.x - world.x;
    if (cx < -150 || cx > W + 150) continue;
    const poleH = 104, poleW = 12;
    ctx.fillStyle = '#6d4a2f';
    ctx.fillRect(cx - poleW / 2, GROUND_Y - poleH, poleW, poleH);
    ctx.fillStyle = 'rgba(0,0,0,0.22)';
    ctx.fillRect(cx + poleW / 2 - 3, GROUND_Y - poleH, 3, poleH);
    const w = 96, h = 84;
    const topY = GROUND_Y - poleH - h + 16;
    ctx.beginPath();
    ctx.moveTo(cx, topY);
    ctx.lineTo(cx + w / 2, topY + h);
    ctx.lineTo(cx - w / 2, topY + h);
    ctx.closePath();
    ctx.fillStyle = '#f4e6c6';
    ctx.fill();
    ctx.lineWidth = 10;
    ctx.lineJoin = 'round';
    ctx.strokeStyle = '#6d4a2f';
    ctx.stroke();
    ctx.fillStyle = '#6d4a2f';
    ctx.fillRect(cx - 5.5, topY + 30, 11, 28);
    ctx.fillRect(cx - 5.5, topY + 65, 11, 12);
  }
}

function drawCoins() {
  for (const c of world.coins) {
    if (c.taken) continue;
    const sx = c.x - world.x;
    if (sx < -80 || sx > W + 80) continue;
    const bob = Math.sin(G.time * 3.4 + c.phase) * 7;
    const squash = 0.42 + 0.58 * Math.abs(Math.cos(G.time * 2.7 + c.phase));
    const h = 46;
    const w = h * ((ASSETS.coin.crop.w || 210) / (ASSETS.coin.crop.h || 248)) * squash;
    drawImg('coin', sx - w / 2, c.y + bob - h / 2, w, h);
  }
}

// 石球，滚石和落石共用
function drawRock(cx, cy, r, rough) {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.beginPath();
  ctx.arc(0, 0, r, 0, Math.PI * 2);
  ctx.fillStyle = '#574f45';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(-r * 0.18, -r * 0.22, r * 0.82, 0, Math.PI * 2);
  ctx.fillStyle = '#6e6458';
  ctx.fill();
  // 石纹
  ctx.strokeStyle = 'rgba(30,24,18,0.55)';
  ctx.lineWidth = Math.max(2, r * 0.09);
  ctx.beginPath();
  ctx.moveTo(-r * 0.6, -r * 0.3);
  ctx.lineTo(-r * 0.1, -r * 0.55);
  ctx.lineTo(r * 0.35, -r * 0.2);
  ctx.moveTo(-r * 0.45, r * 0.35);
  ctx.lineTo(r * 0.1, r * 0.15);
  ctx.lineTo(r * 0.62, r * 0.42);
  ctx.stroke();
  // 高光
  ctx.beginPath();
  ctx.arc(-r * 0.34, -r * 0.36, r * 0.26, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(255,240,215,0.35)';
  ctx.fill();
  if (rough) {
    ctx.beginPath();
    ctx.arc(0, 0, r, 0, Math.PI * 2);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(20,16,12,0.6)';
    ctx.stroke();
  }
  ctx.restore();
}

function drawPendulums() {
  for (const pd of world.pendulums) {
    const sx = pd.x - world.x;
    if (sx < -320 || sx > W + 520) continue;
    const hd = pendulumHead(pd);
    const hx = hd.x - world.x, hy = hd.y;
    // 悬挂座
    ctx.fillStyle = '#2b3346';
    ctx.fillRect(sx - 28, pd.pivotY - 18, 56, 22);
    ctx.fillStyle = '#4a5468';
    ctx.fillRect(sx - 28, pd.pivotY - 18, 56, 6);
    // 链条
    ctx.strokeStyle = '#6d7488';
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.moveTo(sx, pd.pivotY);
    ctx.lineTo(hx, hy);
    ctx.stroke();
    const segs = 9;
    for (let i = 1; i < segs; i++) {
      const t = i / segs;
      const cxp = sx + (hx - sx) * t;
      const cyp = pd.pivotY + (hy - pd.pivotY) * t;
      ctx.fillStyle = i % 2 ? '#99a2b6' : '#767e92';
      ctx.fillRect(cxp - 5, cyp - 5, 10, 10);
    }
    // 锤头尖刺
    for (let i = 0; i < 8; i++) {
      const a = i * Math.PI / 4 - Math.PI / 2;
      ctx.beginPath();
      ctx.moveTo(hx + Math.cos(a) * hd.r * 0.85, hy + Math.sin(a) * hd.r * 0.85);
      ctx.lineTo(hx + Math.cos(a + 0.26) * hd.r * 1.42, hy + Math.sin(a + 0.26) * hd.r * 1.42);
      ctx.lineTo(hx + Math.cos(a - 0.26) * hd.r * 1.42, hy + Math.sin(a - 0.26) * hd.r * 1.42);
      ctx.closePath();
      ctx.fillStyle = '#8c94a8';
      ctx.fill();
    }
    // 锤体
    ctx.beginPath();
    ctx.arc(hx, hy, hd.r, 0, Math.PI * 2);
    ctx.fillStyle = '#4e5769';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(hx - hd.r * 0.16, hy - hd.r * 0.18, hd.r * 0.8, 0, Math.PI * 2);
    ctx.fillStyle = '#6d7789';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(hx - hd.r * 0.36, hy - hd.r * 0.38, hd.r * 0.28, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,255,255,0.4)';
    ctx.fill();
  }
}

function drawRollingRocks() {
  for (const rk of world.rollingRocks) {
    if (!rk.alive) continue;
    const sx = rk.x - world.x;
    if (sx < -140 || sx > W + 140) continue;
    ctx.fillStyle = 'rgba(0,0,0,0.2)';
    ctx.beginPath();
    ctx.ellipse(sx, GROUND_Y + 2, rk.r * 0.95, 9, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.save();
    ctx.translate(sx, rk.y);
    ctx.rotate(rk.spin);
    ctx.translate(-sx, -rk.y);
    drawRock(sx, rk.y, rk.r, true);
    ctx.restore();
  }
}

function drawFallingRocks() {
  for (const fr of world.fallingRocks) {
    const sx = fr.x - world.x;
    if (sx < -140 || sx > W + 140) continue;
    if (!fr.triggered) {
      const a = 0.22 + 0.24 * Math.sin(G.time * 9 + fr.x);
      ctx.fillStyle = 'rgba(255,86,64,' + a + ')';
      ctx.beginPath();
      ctx.ellipse(sx, GROUND_Y - 3, fr.r * 1.15, 11, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = 'rgba(255,120,90,' + (a + 0.15) + ')';
      ctx.beginPath();
      ctx.moveTo(sx, GROUND_Y - 74);
      ctx.lineTo(sx + 15, GROUND_Y - 40);
      ctx.lineTo(sx - 15, GROUND_Y - 40);
      ctx.closePath();
      ctx.fill();
    } else if (fr.landed) {
      // 落地后碎成矮石堆：低矮造型明确提示「跳过去就行」
      if (fr.gone) continue;
      ctx.fillStyle = 'rgba(0,0,0,0.22)';
      ctx.beginPath();
      ctx.ellipse(sx, GROUND_Y - 3, 44, 9, 0, 0, Math.PI * 2);
      ctx.fill();
      const fade = fr.t > 1.05 ? Math.max(0.15, 1 - (fr.t - 1.05) / 0.28) : 1;
      ctx.globalAlpha = fade;
      drawRock(sx - 17, GROUND_Y - 15, 19, true);
      drawRock(sx + 18, GROUND_Y - 18, 23, true);
      drawRock(sx + 1, GROUND_Y - 33, 15, false);
      ctx.globalAlpha = 1;
    } else {
      ctx.fillStyle = 'rgba(190,178,158,0.3)';
      ctx.fillRect(sx - fr.r * 0.45, Math.max(-40, fr.y - 190), fr.r * 0.9, Math.min(180, fr.y + fr.r));
      drawRock(sx, fr.y, fr.r, true);
    }
  }
}

/* 小怪：浮空怪（可踩）/ 跳跳刺球（贴地弹跳）/ 盾怪（只能冲刺破） */
function drawEnemies() {
  for (const en of world.enemies) {
    const sx = en.x - world.x;
    if (sx < -90 || sx > W + 90) continue;
    const ey = enemyY(en);
    if (en.dead) {
      const k = Math.max(0, 1 - (en.deadT || 0) / 0.34);
      if (k <= 0) continue;
      ctx.globalAlpha = k;
      ctx.beginPath();
      ctx.arc(sx, ey, en.r * k * 1.25, 0, Math.PI * 2);
      ctx.fillStyle = en.armor ? 'rgba(140,232,255,0.9)' : 'rgba(168,232,108,0.9)';
      ctx.fill();
      ctx.globalAlpha = 1;
      continue;
    }
    if (en.kind === 'hopper') { drawHopper(sx, ey, en); continue; }
    if (en.kind === 'shield') { drawShieldEnemy(sx, ey, en); continue; }
    const glow = 0.16 + 0.12 * Math.sin(G.time * 4 + en.phase);
    ctx.beginPath();
    ctx.arc(sx, ey, en.r + 15, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,120,90,' + glow + ')';
    ctx.fill();
    ctx.fillStyle = '#39435a';
    for (let i = 0; i < 8; i++) {
      const a = i * Math.PI / 4 + Math.sin(G.time * 2.4 + en.phase) * 0.12;
      ctx.beginPath();
      ctx.moveTo(sx + Math.cos(a - 0.2) * en.r * 0.92, ey + Math.sin(a - 0.2) * en.r * 0.92);
      ctx.lineTo(sx + Math.cos(a + 0.2) * en.r * 0.92, ey + Math.sin(a + 0.2) * en.r * 0.92);
      ctx.lineTo(sx + Math.cos(a) * (en.r + 13), ey + Math.sin(a) * (en.r + 13));
      ctx.closePath();
      ctx.fill();
    }
    ctx.beginPath();
    ctx.arc(sx, ey, en.r, 0, Math.PI * 2);
    ctx.fillStyle = '#4d5870';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(sx - en.r * 0.22, ey - en.r * 0.26, en.r * 0.68, 0, Math.PI * 2);
    ctx.fillStyle = '#6d7a99';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(sx, ey, en.r - 1, 0, Math.PI * 2);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = '#26304a';
    ctx.stroke();
    const lk = Math.sin(G.time * 3 + en.phase) * 2;
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(sx - 8, ey - 3, 6, 0, Math.PI * 2);
    ctx.arc(sx + 8, ey - 3, 6, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#12203a';
    ctx.beginPath();
    ctx.arc(sx - 8 + lk, ey - 3, 3, 0, Math.PI * 2);
    ctx.arc(sx + 8 + lk, ey - 3, 3, 0, Math.PI * 2);
    ctx.fill();
    const by = ey - en.r - 26;
    ctx.fillStyle = 'rgba(168,232,108,' + (0.5 + 0.3 * Math.sin(G.time * 5 + en.phase)) + ')';
    ctx.beginPath();
    ctx.moveTo(sx, by + 10);
    ctx.lineTo(sx - 8, by - 2);
    ctx.lineTo(sx + 8, by - 2);
    ctx.closePath();
    ctx.fill();
  }
}

/* 跳跳刺球：贴地弹跳，带刺，挡在跑道上（跳过或铲过） */
function drawHopper(sx, ey, en) {
  const r = en.r;
  const squash = clamp(1 - Math.abs(en.vy || 0) / 1500, 0.82, 1);
  ctx.save();
  ctx.translate(sx, ey);
  // 落影
  ctx.globalAlpha = 0.22;
  ctx.beginPath();
  ctx.ellipse(0, GROUND_Y - ey - 2, r * 0.85, 5, 0, 0, Math.PI * 2);
  ctx.fillStyle = '#000000';
  ctx.fill();
  ctx.globalAlpha = 1;
  ctx.scale(1 / squash, squash);
  // 尖刺
  ctx.fillStyle = '#8a3f3f';
  for (let i = 0; i < 8; i++) {
    const a = i * Math.PI / 4 + G.time * 1.5;
    ctx.beginPath();
    ctx.moveTo(Math.cos(a - 0.22) * r * 0.9, Math.sin(a - 0.22) * r * 0.9);
    ctx.lineTo(Math.cos(a + 0.22) * r * 0.9, Math.sin(a + 0.22) * r * 0.9);
    ctx.lineTo(Math.cos(a) * (r + 12), Math.sin(a) * (r + 12));
    ctx.closePath();
    ctx.fill();
  }
  const grd = ctx.createRadialGradient(-r * 0.3, -r * 0.35, r * 0.2, 0, 0, r);
  grd.addColorStop(0, '#ff9a6a');
  grd.addColorStop(1, '#c8452e');
  ctx.beginPath();
  ctx.arc(0, 0, r, 0, Math.PI * 2);
  ctx.fillStyle = grd;
  ctx.fill();
  ctx.lineWidth = 3;
  ctx.strokeStyle = 'rgba(60,20,14,0.75)';
  ctx.stroke();
  ctx.fillStyle = '#ffffff';
  ctx.beginPath();
  ctx.arc(-8, -4, 6, 0, Math.PI * 2);
  ctx.arc(8, -4, 6, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = '#2a1008';
  ctx.beginPath();
  ctx.arc(-8, -4, 3, 0, Math.PI * 2);
  ctx.arc(8, -4, 3, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

/* 盾怪：正面挂着能量护盾，只有能量冲刺能破 */
function drawShieldEnemy(sx, ey, en) {
  const r = en.r;
  const grd = ctx.createRadialGradient(sx - r * 0.3, ey - r * 0.35, r * 0.2, sx, ey, r);
  grd.addColorStop(0, '#b9c6dd');
  grd.addColorStop(1, '#5d6a86');
  ctx.beginPath();
  ctx.arc(sx, ey, r, 0, Math.PI * 2);
  ctx.fillStyle = grd;
  ctx.fill();
  ctx.lineWidth = 3;
  ctx.strokeStyle = '#2b3550';
  ctx.stroke();
  // 正面护盾弧（朝玩家来向）
  const pulse = 0.45 + 0.25 * Math.sin(G.time * 5 + en.phase);
  ctx.beginPath();
  ctx.arc(sx + r * 0.15, ey, r + 12, -1.15, 1.15);
  ctx.lineWidth = 6;
  ctx.strokeStyle = 'rgba(140,232,255,' + pulse.toFixed(3) + ')';
  ctx.stroke();
  ctx.fillStyle = '#e8f2ff';
  for (let i = 0; i < 3; i++) {
    ctx.beginPath();
    ctx.arc(sx + r * 0.34, ey - 16 + i * 16, 3.2, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.fillStyle = '#12203a';
  ctx.beginPath();
  ctx.arc(sx - 12, ey - 6, 4.5, 0, Math.PI * 2);
  ctx.arc(sx, ey - 6, 4.5, 0, Math.PI * 2);
  ctx.fill();
}

function drawBoosters() {
  for (const bo of world.boosters) {
    const sx = bo.x - world.x;
    if (sx > W || sx + bo.w < 0) continue;
    const glow = bo.gl > 0;
    // 传送带底槽
    ctx.fillStyle = '#123039';
    ctx.fillRect(sx, GROUND_Y - 14, bo.w, 16);
    // 滚动条纹
    const off = (G.time * 430) % 26;
    ctx.save();
    ctx.beginPath();
    ctx.rect(sx, GROUND_Y - 12, bo.w, 12);
    ctx.clip();
    for (let i = -1; i * 26 < bo.w + 26; i++) {
      const bx = sx + i * 26 + off;
      ctx.fillStyle = glow ? 'rgba(190,250,255,0.95)' : 'rgba(92,192,226,0.75)';
      ctx.beginPath();
      ctx.moveTo(bx, GROUND_Y - 12);
      ctx.lineTo(bx + 13, GROUND_Y - 6);
      ctx.lineTo(bx, GROUND_Y);
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
    // 顶面光条
    ctx.fillStyle = glow ? 'rgba(205,250,255,0.95)' : 'rgba(122,215,245,0.6)';
    ctx.fillRect(sx, GROUND_Y - 15, bo.w, 3);
    // 两端金属头
    ctx.fillStyle = '#2c5464';
    ctx.fillRect(sx - 5, GROUND_Y - 17, 7, 19);
    ctx.fillRect(sx + bo.w - 2, GROUND_Y - 17, 7, 19);
    ctx.fillStyle = '#4d8296';
    ctx.fillRect(sx - 5, GROUND_Y - 17, 7, 3);
    ctx.fillRect(sx + bo.w - 2, GROUND_Y - 17, 7, 3);
    // 头顶的箭头，会脉动
    ctx.globalAlpha = glow ? 1 : 0.7;
    ctx.fillStyle = glow ? '#c8f5ff' : '#6fd0ee';
    const ay = GROUND_Y - 42 - Math.sin(G.time * 5) * 3;
    for (let i = 0; i < 2; i++) {
      const ax = sx + bo.w / 2 - 20 + i * 26;
      ctx.beginPath();
      ctx.moveTo(ax, ay);
      ctx.lineTo(ax + 14, ay + 11);
      ctx.lineTo(ax, ay + 22);
      ctx.lineTo(ax + 5, ay + 11);
      ctx.closePath();
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  }
}

function drawFakeCoins() {
  for (const c of world.fakeCoins) {
    if (c.taken) continue;
    const sx = c.x - world.x;
    if (sx < -80 || sx > W + 80) continue;
    const bob = Math.sin(G.time * 2.1 + c.phase) * 5;
    const squash = 0.5 + 0.5 * Math.abs(Math.cos(G.time * 3.6 + c.phase));
    const h = 44;
    const w = h * ((ASSETS.coin.crop.w || 210) / (ASSETS.coin.crop.h || 248)) * squash;
    drawImg('coin', sx - w / 2, c.y + bob - h / 2, w, h);
    ctx.save();
    ctx.globalAlpha = 0.42;
    ctx.beginPath();
    ctx.ellipse(sx, c.y + bob, w / 2, h / 2, 0, 0, Math.PI * 2);
    ctx.fillStyle = '#8e1030';
    ctx.fill();
    ctx.restore();
    ctx.save();
    ctx.globalAlpha = 0.55;
    ctx.beginPath();
    ctx.ellipse(sx, c.y + bob, w / 2, h / 2, 0, 0, Math.PI * 2);
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#3a0616';
    ctx.stroke();
    ctx.restore();
  }
}

/* 隐藏地刺：弹出前只有一道很浅的地缝 */
function drawHiddenSpikes() {
  for (const hs of world.hiddenSpikes) {
    const sx = hs.x - world.x;
    if (sx > W || sx + hs.w < 0) continue;
    ctx.fillStyle = 'rgba(26,18,12,0.5)';
    ctx.fillRect(sx + 5, GROUND_Y + 3, hs.w - 10, 4);
    ctx.fillStyle = 'rgba(0,0,0,0.35)';
    ctx.fillRect(sx + 5, GROUND_Y + 7, hs.w - 10, 3);
    if (hs.t < 0) continue;
    const hh = clamp((G.time - hs.t) / 0.11, 0, 1);
    const sh = 58 * ease.outCubic(hh);
    if (sh < 1) continue;
    ctx.save();
    ctx.beginPath();
    ctx.rect(sx - 8, GROUND_Y - sh, hs.w + 16, sh + 10);
    ctx.clip();
    drawSpikeRow(sx, GROUND_Y + 5, hs.w, Math.max(2, Math.round(hs.w / 30)), sh, false);
    ctx.restore();
  }
}

/* 陷阱弹簧：暗红黑纹，踩上直接把人送进上方的刺 */
function drawTrapSprings() {
  for (const ts of world.trapSprings) {
    const sx = ts.x - world.x;
    if (sx > W || sx + ts.w < 0) continue;
    const pop = ts.t > 0 ? Math.max(0, 1 - (G.time - ts.t) * 6) : 0;
    const topH = 28 - pop * 15;
    ctx.fillStyle = '#33161a';
    ctx.fillRect(sx, GROUND_Y - 8, ts.w, 10);
    ctx.fillStyle = '#4d2228';
    ctx.fillRect(sx, GROUND_Y - 8, ts.w, 4);
    ctx.strokeStyle = '#b04048';
    ctx.lineWidth = 4;
    ctx.beginPath();
    for (let i = 0; i <= 6; i++) {
      const px = sx + 10 + (ts.w - 20) * i / 6;
      const py = i % 2 ? GROUND_Y - topH + 7 : GROUND_Y - 10;
      if (i === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    }
    ctx.stroke();
    ctx.fillStyle = '#8e2f38';
    ctx.fillRect(sx, GROUND_Y - topH, ts.w, 11);
    ctx.fillStyle = '#d2545c';
    ctx.fillRect(sx, GROUND_Y - topH, ts.w, 3);
    for (let i = 0; i < ts.w / 16; i++) {
      ctx.fillStyle = i % 2 ? '#1c0d10' : '#d2545c';
      ctx.fillRect(sx + i * 16, GROUND_Y - topH + 4, Math.min(16, ts.w - i * 16), 4);
    }
  }
}

/* 天花板压制：上下往复的石板 */
function drawCrushers() {
  for (const cr of world.crushers) {
    const sx = cr.x - world.x;
    if (sx > W || sx + cr.w < 0) continue;
    const by = crusherBottom(cr);
    const bodyH = 54;
    const top = by - bodyH;
    // 导轨
    ctx.fillStyle = 'rgba(74,82,98,0.55)';
    ctx.fillRect(sx + 9, 0, 11, Math.max(0, top));
    ctx.fillRect(sx + cr.w - 20, 0, 11, Math.max(0, top));
    ctx.fillStyle = 'rgba(140,152,176,0.4)';
    ctx.fillRect(sx + 9, 0, 3, Math.max(0, top));
    ctx.fillRect(sx + cr.w - 20, 0, 3, Math.max(0, top));
    // 顶部横梁
    ctx.fillStyle = '#2b3346';
    ctx.fillRect(sx - 6, 0, cr.w + 12, 18);
    ctx.fillStyle = '#4a5468';
    ctx.fillRect(sx - 6, 0, cr.w + 12, 5);
    // 石板
    ctx.fillStyle = 'rgba(0,0,0,0.22)';
    ctx.fillRect(sx + 5, top + 6, cr.w, bodyH);
    ctx.fillStyle = '#474d5e';
    ctx.fillRect(sx, top, cr.w, bodyH);
    ctx.fillStyle = '#5c6377';
    ctx.fillRect(sx, top, cr.w * 0.3, bodyH);
    ctx.fillStyle = '#727a90';
    ctx.fillRect(sx, top, cr.w, 5);
    ctx.fillStyle = 'rgba(0,0,0,0.3)';
    ctx.fillRect(sx + cr.w - 9, top, 9, bodyH);
    // 铆钉
    ctx.fillStyle = '#98a1b4';
    for (let i = 0; i < 4; i++) {
      ctx.fillRect(sx + 10 + i * (cr.w - 26) / 3, top + 12, 5, 5);
      ctx.fillRect(sx + 10 + i * (cr.w - 26) / 3, top + 36, 5, 5);
    }
    // 底部尖齿
    const n = Math.max(3, Math.round(cr.w / 26));
    const sw = cr.w / n;
    for (let i = 0; i < n; i++) {
      const x0 = sx + i * sw;
      ctx.beginPath();
      ctx.moveTo(x0, top + bodyH - 2);
      ctx.lineTo(x0 + sw / 2, top + bodyH + 15);
      ctx.lineTo(x0 + sw, top + bodyH - 2);
      ctx.closePath();
      ctx.fillStyle = i % 2 ? '#c8ced9' : '#9aa2b0';
      ctx.fill();
    }
  }
}

/* 塌陷平台 */
function drawCrumbles() {
  for (const cm of world.crumbles) {
    const sx = cm.x - world.x;
    if (sx > W || sx + cm.w < 0) continue;
    const fall = cm.broken ? (cm.fall || 0) : 0;
    if (fall > 620) continue;
    const shakeX = (!cm.broken && cm.t > 0) ? Math.sin(G.time * 58) * 2.6 : 0;
    const top = cm.top + fall;
    ctx.save();
    ctx.globalAlpha = cm.broken ? Math.max(0, 1 - fall / 560) : 1;
    drawPlatformAt(sx + shakeX, cm.w, top, cm.h);
    // 裂纹（踩上去后逐渐显现）
    const crack = cm.broken ? 1 : (cm.t > 0 ? Math.min(1, cm.t / 0.42) : 0.25);
    ctx.strokeStyle = 'rgba(24,16,10,' + (0.45 + 0.5 * crack) + ')';
    ctx.lineWidth = 2 + crack * 2;
    ctx.beginPath();
    ctx.moveTo(sx + shakeX + cm.w * 0.3, top + 4);
    ctx.lineTo(sx + shakeX + cm.w * 0.42, top + 26);
    ctx.lineTo(sx + shakeX + cm.w * 0.28, top + 50);
    ctx.moveTo(sx + shakeX + cm.w * 0.66, top + 6);
    ctx.lineTo(sx + shakeX + cm.w * 0.58, top + 32);
    ctx.stroke();
    if (cm.t > 0 && !cm.broken) {
      ctx.fillStyle = 'rgba(255,120,90,' + (0.25 + 0.3 * Math.sin(G.time * 20)) + ')';
      ctx.fillRect(sx + shakeX, top - 4, cm.w, 4);
    }
    ctx.restore();
  }
}

/* 增益道具 */
const POWER_COLOR = {
  magnet: '#ff9ed8', shield: '#8ce8ff', djump: '#b6ec92',
  invinc: '#ffe066', x2: '#ffd34d', slow: '#c8d8f5', sprint: '#ff9a5a',
  ghost: '#c9a0ff', rain: '#ffe9a8', revive: '#7de08a', frenzy: '#ff6a8a', knife: '#8ce8ff',
};
const POWER_ICON = { magnet: '磁', shield: '盾', djump: '跳', invinc: '星', x2: '×2', slow: '慢', sprint: '速', ghost: '灵', rain: '雨', revive: '生', frenzy: '狂', knife: '刀' };

function drawPowers() {
  for (const p of world.powers) {
    if (p.taken) continue;
    const sx = p.x - world.x;
    if (sx < -80 || sx > W + 80) continue;
    const col = POWER_COLOR[p.type] || '#8ce8ff';
    const bob = Math.sin(G.time * 3 + p.x * 0.01) * 6;
    const y = p.y + bob;
    ctx.globalAlpha = 0.28 + 0.18 * Math.sin(G.time * 5);
    ctx.beginPath();
    ctx.arc(sx, y, 32, 0, Math.PI * 2);
    ctx.fillStyle = col;
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.save();
    ctx.translate(sx, y);
    ctx.rotate(Math.sin(G.time * 2 + p.x * 0.01) * 0.16);
    pxRect(-20, -20, 40, 40, 9);
    ctx.fillStyle = '#0b1120';
    ctx.fill();
    pxRect(-17, -17, 34, 34, 7);
    ctx.fillStyle = col;
    ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.55)';
    ctx.fillRect(-13, -13, 26, 3);
    ctx.font = 'bold 19px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#0b1120';
    ctx.fillText(POWER_ICON[p.type] || '?', 0, 2);
    ctx.restore();
  }
}

/* 战术道具箱 */
function drawCrates() {
  for (const cr of world.crates) {
    if (cr.taken) continue;
    const sx = cr.x - world.x;
    if (sx < -80 || sx > W + 80) continue;
    const bob = Math.sin(G.time * 2.4 + cr.ph) * 7;
    const y = cr.y + bob;
    const pulse = 0.3 + 0.2 * Math.sin(G.time * 4 + cr.ph);
    // 光柱：真箱金光；宝箱怪泛紫（看得出来的玩家能提前躲开）
    ctx.globalAlpha = pulse * 0.5;
    const g = ctx.createLinearGradient(0, y - 70, 0, y + 30);
    g.addColorStop(0, cr.mimic ? 'rgba(176,106,255,0)' : 'rgba(255,214,90,0)');
    g.addColorStop(1, cr.mimic ? 'rgba(176,106,255,0.5)' : 'rgba(255,214,90,0.55)');
    ctx.fillStyle = g;
    ctx.fillRect(sx - 22, y - 70, 44, 100);
    ctx.globalAlpha = 1;
    // 箱体
    ctx.save();
    ctx.translate(sx, y);
    ctx.rotate(Math.sin(G.time * 1.8 + cr.ph) * 0.1);
    ctx.fillStyle = 'rgba(0,0,0,0.22)';
    pxRect(-22, -18, 44, 40, 7);
    ctx.fill();
    pxRect(-22, -20, 44, 40, 7);
    ctx.fillStyle = '#7a4a18';
    ctx.fill();
    pxRect(-19, -17, 38, 34, 5);
    ctx.fillStyle = '#b8762a';
    ctx.fill();
    ctx.fillStyle = '#e0a04a';
    ctx.fillRect(-19, -17, 38, 5);
    // 金属包边
    ctx.fillStyle = '#d8c070';
    ctx.fillRect(-22, -6, 44, 6);
    ctx.fillRect(-4, -20, 8, 40);
    ctx.fillStyle = '#8a6a20';
    ctx.fillRect(-4, -20, 8, 3);
    // 问号（宝箱怪的问号带一点獠牙味）
    ctx.font = 'bold 20px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = cr.mimic ? '#3b1240' : '#3a2408';
    ctx.fillText('?', 0, -2);
    if (cr.mimic) {
      ctx.strokeStyle = 'rgba(226,150,255,0.85)';
      ctx.lineWidth = 2;
      pxRect(-20, -18, 40, 36, 6);
      ctx.stroke();
    }
    ctx.restore();
  }
}

/* 升级祭坛：世界层的基座 + 光柱，靠近会亮 */
function drawAltars() {
  for (const al of world.altars) {
    if (al.taken) continue;
    const sx = al.x - world.x;
    if (sx < -120 || sx > W + 120) continue;
    const y = al.y;
    const pulse = 0.5 + 0.5 * Math.sin(G.time * 2.6 + al.ph);
    // 光柱
    const g = ctx.createLinearGradient(0, y - 210, 0, y + 30);
    g.addColorStop(0, 'rgba(201,160,255,0)');
    g.addColorStop(1, 'rgba(201,160,255,' + (0.28 + 0.25 * pulse).toFixed(3) + ')');
    ctx.fillStyle = g;
    ctx.fillRect(sx - 34, y - 210, 68, 240);
    // 底座
    ctx.fillStyle = 'rgba(0,0,0,0.25)';
    ctx.beginPath();
    ctx.ellipse(sx, GROUND_Y - 3, 46, 11, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#3b3145';
    pxRect(sx - 30, GROUND_Y - 26, 60, 26, 8);
    ctx.fill();
    ctx.fillStyle = '#57496a';
    pxRect(sx - 26, GROUND_Y - 24, 52, 10, 6);
    ctx.fill();
    // 悬浮的符文核心
    const cy = y + Math.sin(G.time * 2 + al.ph) * 6;
    const rg = ctx.createRadialGradient(sx, cy, 4, sx, cy, 34);
    rg.addColorStop(0, 'rgba(232,214,255,' + (0.85 + 0.15 * pulse).toFixed(3) + ')');
    rg.addColorStop(0.5, 'rgba(176,106,255,0.6)');
    rg.addColorStop(1, 'rgba(120,60,200,0)');
    ctx.fillStyle = rg;
    ctx.beginPath();
    ctx.arc(sx, cy, 34, 0, Math.PI * 2);
    ctx.fill();
    ctx.save();
    ctx.translate(sx, cy);
    ctx.rotate(G.time * 0.9 + al.ph);
    ctx.strokeStyle = 'rgba(240,230,255,0.92)';
    ctx.lineWidth = 3;
    ctx.beginPath();
    for (let i = 0; i < 3; i++) {
      const a = i * Math.PI * 2 / 3;
      ctx.moveTo(0, 0);
      ctx.lineTo(Math.cos(a) * 15, Math.sin(a) * 15);
    }
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, 15, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }
}

/* 祭坛牌面图标 */
const UP_ICON = { djump: '跳', magnet: '磁', energy: '能', combo: '节', speed: '速', coin: '金', shieldMax: '盾' };

/* 祭坛面板：世界冻结，三张牌等玩家点 */
function drawAltarPanel() {
  const a = G.altar;
  if (!a) return;
  const k = clamp(G.altarT / 0.22, 0, 1);
  ctx.fillStyle = 'rgba(6,10,20,' + (0.74 * k).toFixed(3) + ')';
  ctx.fillRect(0, 0, W, H);
  // 顶部光带
  const hg = ctx.createLinearGradient(0, 120, 0, 300);
  hg.addColorStop(0, 'rgba(201,160,255,0)');
  hg.addColorStop(1, 'rgba(201,160,255,' + (0.16 * k).toFixed(3) + ')');
  ctx.fillStyle = hg;
  ctx.fillRect(0, 120, W, 180);
  pxText('强化祭坛', W / 2, 176, 46, '#e8dcff', 'rgba(10,18,32,0.95)');
  pxText('三选一 · 本局永久生效', W / 2, 226, 23, 'rgba(222,212,246,0.92)', 'rgba(10,18,32,0.9)');

  const cards = altarCards();
  for (let ci = 0; ci < cards.length; ci++) {
    const c = cards[ci];
    const s = ease.outBack(clamp((G.altarT - 0.06 - ci * 0.06) / 0.3, 0, 1));
    if (s <= 0.01) continue;
    ctx.save();
    ctx.translate(c.x + c.w / 2, c.y + c.h / 2);
    ctx.scale(s, s);
    ctx.translate(-(c.x + c.w / 2), -(c.y + c.h / 2));
    // 牌底 + 阴影
    pxRect(c.x + 4, c.y + 7, c.w, c.h, 16);
    ctx.fillStyle = 'rgba(6,10,18,0.55)';
    ctx.fill();
    pxRect(c.x, c.y, c.w, c.h, 16);
    ctx.fillStyle = 'rgba(30,38,60,0.97)';
    ctx.fill();
    // 顶部色带
    ctx.save();
    pxRect(c.x, c.y, c.w, 58, 16);
    ctx.clip();
    ctx.fillStyle = c.u.color;
    ctx.fillRect(c.x, c.y, c.w, 58);
    ctx.fillStyle = 'rgba(10,18,32,0.4)';
    ctx.fillRect(c.x, c.y + 34, c.w, 26);
    ctx.restore();
    pxText(c.u.name, c.x + c.w / 2, c.y + 29, 24, '#ffffff', 'rgba(10,18,32,0.9)');
    // 图标光晕
    const cx0 = c.x + c.w / 2, cy0 = c.y + 112;
    const glowT = 0.55 + 0.15 * Math.sin(G.time * 3 + ci);
    const rg = ctx.createRadialGradient(cx0, cy0, 3, cx0, cy0, 44);
    rg.addColorStop(0, hexA(c.u.color, 0.85 * glowT));
    rg.addColorStop(0.55, hexA(c.u.color, 0.3 * glowT));
    rg.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = rg;
    ctx.beginPath();
    ctx.arc(cx0, cy0, 44, 0, Math.PI * 2);
    ctx.fill();
    pxText(UP_ICON[c.u.id] || '强', cx0, cy0, 42, '#ffffff', 'rgba(10,18,32,0.85)');
    pxText(c.u.desc, cx0, c.y + c.h - 54, 18, 'rgba(230,238,250,0.95)', 'rgba(10,18,32,0.9)');
    pxText('等级 ' + c.lv + ' → ' + (c.lv + 1) + ' / ' + c.max, cx0, c.y + c.h - 26, 17, c.u.color, 'rgba(10,18,32,0.9)');
    pxRect(c.x, c.y, c.w, c.h, 16);
    ctx.lineWidth = 3;
    ctx.strokeStyle = hexA(c.u.color, 0.9);
    ctx.stroke();
    ctx.restore();
  }
  const tipY = cards.length ? cards[0].y + cards[0].h + 46 : H * 0.72;
  pxText('点一张牌继续跑', W / 2, tipY, 21, 'rgba(214,224,244,0.9)', 'rgba(10,18,32,0.9)');
}

/* 抽卡弹窗：道具箱开出的限时增益 */
function drawCardPop() {
  const p = G.cardPop;
  if (!p) return;
  const t = p.t;
  const k = clamp(t / 0.28, 0, 1);
  const s = ease.outBack(k);
  const fade = t > 1.3 ? Math.max(0, 1 - (t - 1.3) / 0.5) : 1;
  const cw = 232, ch = 108;
  const cx = W / 2, cy = 372;
  ctx.save();
  ctx.globalAlpha = fade;
  ctx.translate(cx, cy);
  ctx.scale(s, s);
  pxRect(-cw / 2 + 5, -ch / 2 + 7, cw, ch, 14);
  ctx.fillStyle = 'rgba(6,10,18,0.5)';
  ctx.fill();
  pxRect(-cw / 2, -ch / 2, cw, ch, 14);
  ctx.fillStyle = '#0b1120';
  ctx.fill();
  pxRect(-cw / 2 + 3, -ch / 2 + 3, cw - 6, ch - 6, 11);
  ctx.fillStyle = p.color;
  ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.5)';
  ctx.fillRect(-cw / 2 + 18, -ch / 2 + 7, cw - 36, 4);
  // 图标
  ctx.beginPath();
  ctx.arc(-cw / 2 + 46, 0, 26, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(0,0,0,0.28)';
  ctx.fill();
  ctx.font = 'bold 26px "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#0b1120';
  ctx.fillText(p.icon, -cw / 2 + 46, 2);
  // 名称与时长
  ctx.textAlign = 'left';
  ctx.font = 'bold 28px "Microsoft YaHei", sans-serif';
  ctx.fillStyle = '#0b1120';
  ctx.fillText(p.name, -cw / 2 + 84, -14);
  ctx.font = 'bold 20px Consolas, "Courier New", monospace';
  ctx.fillStyle = 'rgba(11,17,32,0.8)';
  ctx.fillText(p.dur > 0 ? '生效 ' + p.dur + ' 秒' : '抵挡一次死亡', -cw / 2 + 84, 22);
  ctx.restore();
}

function drawFinishGate() {
  if (G.mode !== 'level' && G.mode !== 'ugc') return;
  if (G.state === 'menu' || G.state === 'levels' || G.state === 'loading') return;
  const sx = G.levelEndX - world.x;
  if (sx < -260 || sx > W + 300) return;
  const gateW = 216;
  const left = sx - gateW / 2;
  const poleH = 330;
  // 柱
  ctx.fillStyle = '#5b6072';
  ctx.fillRect(left - 26, GROUND_Y - poleH, 24, poleH);
  ctx.fillRect(left + gateW + 2, GROUND_Y - poleH, 24, poleH);
  ctx.fillStyle = '#767c92';
  ctx.fillRect(left - 26, GROUND_Y - poleH, 8, poleH);
  ctx.fillRect(left + gateW + 2, GROUND_Y - poleH, 8, poleH);
  // 横梁
  ctx.fillStyle = '#e8564a';
  ctx.fillRect(left - 34, GROUND_Y - poleH - 44, gateW + 68, 46);
  ctx.fillStyle = 'rgba(255,255,255,0.85)';
  ctx.fillRect(left - 34, GROUND_Y - poleH - 44, gateW + 68, 6);
  ctx.font = 'bold 32px "Segoe UI", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#fff';
  ctx.fillText('FINISH', left + gateW / 2, GROUND_Y - poleH - 20);
  // 飘动的旗
  const wave = Math.sin(G.time * 4) * 8;
  ctx.beginPath();
  ctx.moveTo(left + gateW / 2 + 40, GROUND_Y - poleH - 44);
  ctx.quadraticCurveTo(left + gateW / 2 + 76 + wave, GROUND_Y - poleH - 60, left + gateW / 2 + 108, GROUND_Y - poleH - 46);
  ctx.lineTo(left + gateW / 2 + 40, GROUND_Y - poleH - 12);
  ctx.closePath();
  ctx.fillStyle = '#ffd34d';
  ctx.fill();
  // 终点线
  ctx.fillStyle = 'rgba(255,255,255,0.85)';
  for (let i = 0; i < 10; i++) {
    ctx.fillStyle = i % 2 ? '#ffffff' : '#e8564a';
    ctx.fillRect(left + i * 22, GROUND_Y - 14, 22, 14);
  }
}

function drawPlayer() {
  if (G.state === 'loading' || G.state === 'levels' || G.state === 'skins') return;
  const menu = (G.state === 'menu');
  let key;
  if (menu) key = 'idle';
  else if (player.sliding) key = 'slide';
  else if (!player.onGround) key = 'run';
  else key = (Math.floor(player.run * 0.8) % 2 === 0) ? 'run' : 'run2';
  // 走皮肤通道：素材优先，没有就用原版
  const sc = SKIN_CACHE[key];
  const im = sc ? sc.im : IMG[key];
  const crop = sc ? sc.crop : ASSETS[key].crop;
  // 尺寸基准取当前皮肤的跑步图，这样每套素材都按自己的比例缩放
  const baseH = (SKIN_CACHE.run && SKIN_CACHE.run.crop) ? SKIN_CACHE.run.crop.h : 594;
  const k = (PLAYER_H / baseH) * (menu ? 0.6 : 1);
  const h = crop.h * k;
  const w = crop.w * k;

  ctx.save();
  ctx.translate(menu ? 462 : PLAYER_X, player.y);
  // 护盾光环
  if (!menu && G.shield > 0) {
    ctx.beginPath();
    ctx.arc(0, -h * 0.45, h * 0.52, 0, Math.PI * 2);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(140,232,255,' + (0.45 + 0.22 * Math.sin(G.time * 4.5)) + ')';
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, -h * 0.45, h * 0.52 - 7, 0, Math.PI * 2);
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = 'rgba(200,245,255,' + (0.25 + 0.15 * Math.sin(G.time * 4.5 + 1)) + ')';
    ctx.stroke();
  }
  // 无敌闪烁
  if (!menu && G.buff.invinc > 0) {
    ctx.globalAlpha = 0.4 + 0.35 * Math.sin(G.time * 24);
  }
  // 幽灵穿透：半透明 + 紫色灵光环
  if (!menu && G.buff.ghost > 0) {
    ctx.globalAlpha = 0.55 + 0.18 * Math.sin(G.time * 6);
    ctx.beginPath();
    ctx.arc(0, -h * 0.45, h * 0.5, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(160,120,235,0.2)';
    ctx.fill();
  }
  if (player.onGround && G.state !== 'dying') {
    // 软接触阴影：比纯色椭圆更贴地，角色不再像贴纸
    const sg = ctx.createRadialGradient(0, 2, 2, 0, 2, w * 0.46);
    sg.addColorStop(0, 'rgba(0,0,0,0.24)');
    sg.addColorStop(0.6, 'rgba(0,0,0,0.10)');
    sg.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = sg;
    ctx.beginPath();
    ctx.ellipse(0, 2, w * 0.46, 9, 0, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.rotate(player.tilt + (G.state === 'dying' ? player.deadRot : 0));
  ctx.scale(player.sx, player.sy);
  if (player.spin) {
    ctx.translate(0, -h / 2);
    ctx.rotate(player.spin);
    ctx.translate(0, h / 2);
  }
  const bob = player.onGround && !player.sliding ? Math.sin(player.run * 2) * 3 : 0;
  if (G.state === 'dying') ctx.globalAlpha = Math.max(0, 1 - player.deadT * 1.05);
  ctx.imageSmoothingEnabled = true;   // 插画素材用平滑采样，缩放到任何屏幕都不毛边
  // 冲刺残影：高速时身后拖三道渐隐身形
  if (G.dashT > 0 || FX.speedLines > 0.55) {
    for (let i = 3; i >= 1; i--) {
      ctx.save();
      ctx.globalAlpha = (G.dashT > 0 ? 0.26 : 0.14) / i;
      ctx.drawImage(im, crop.x, crop.y, crop.w, crop.h, -w / 2 - i * 26, -h + bob + i * 2, w, h);
      ctx.restore();
    }
  }
  // 背光描边：先带阴影画一遍，再实画一遍，主体从背景里跳出来
  ctx.save();
  ctx.shadowColor = 'rgba(8,14,26,0.9)';
  ctx.shadowBlur = 9;
  ctx.shadowOffsetY = 3;
  ctx.drawImage(im, crop.x, crop.y, crop.w, crop.h, -w / 2, -h + bob, w, h);
  ctx.restore();
  ctx.drawImage(im, crop.x, crop.y, crop.w, crop.h, -w / 2, -h + bob, w, h);
  ctx.restore();
}

/* 常态蚩尤：远处缀着，放技能时上前一步，屏幕上提前画警告圈 */
function drawChiyou() {
  const c = G.chiyou;
  if (!c || G.boss) return;
  if (c.hidden || c.lost) return;   // 烟雾瞬步/登场中不画本体；被甩掉后人也退场
  const aj = c.skin === 'aj';
  /* 帧状态机：跑动帧循环，跳跃/滑铲/施法/受击各有专属帧，不再站立跑步乱闪 */
  let key;
  if (aj) {
    if (c.hurtT > 0) key = 'aj_dive';
    else if (c.sliding) key = 'aj_slide';
    else if (!c.onGround) key = 'aj_dive';   // 瞬步头顶下劈/跳跃都是腾空帧
    else if (c.skillT >= 0 && c.kind === 'nova') key = c.skillT < 0.3 ? 'aj_dive' : 'aj_slide';
    else if (c.skillT >= 0 && c.kind === 'shuriken') key = c.skillT < 0.55 ? 'aj_throw1' : 'aj_throw2';
    else if (c.skillT >= 0 && c.kind === 'sword') key = c.slashAnimT > 0 ? 'ajw_swing2' : c.skillT < 0.55 ? 'ajw_swing1' : 'ajw_swing2';
    else if (c.skillT >= 0 && c.kind === 'iai') key = c.skillT < 0.7 ? 'ajw_swing3' : 'ajw_swing2';
    else if (c.skillT >= 0 && c.kind === 'combo') key = c.skillT < 0.4 ? 'ajw_swing3' : (c.slashAnimT > 0 ? 'ajw_swing2' : 'aj_idle');
    else if (c.skillT >= 0 && c.kind === 'smoke' && c.skillT < 0.45) key = 'aj_throw1';
    else if (c.skillT >= 0 && c.skillT < 1.3) key = 'aj_throw1';
    else if (c.waitT > 0) key = 'aj_idle';
    else if (world.tstop > 0) key = 'aj_idle';
    else key = 'aj_run' + (1 + Math.floor(c.t * 8) % 2);
  } else {
    if (c.hurtT > 0) key = 'chiyou_punch2';
    else if (c.sliding) key = 'chiyou_slide';
    else if (!c.onGround) key = 'chiyou_dash';
    else if (c.skillT >= 0 && c.kind === 'claw' && c.skillT >= 0.6) key = 'chiyou_punch';
    else if (c.skillT >= 0 && c.skillT < 1.3) key = 'chiyou_skill';
    else if (c.waitT > 0) key = 'chiyou_idle';   // 距离过近停下等玩家，站立待机
    else if (world.tstop > 0) key = 'chiyou_idle';   // 被时停术冻结，定格站立
    else key = 'chiyou_run' + (1 + Math.floor(c.t * 8) % 3);
  }
  const im = BOSS_IMG[key];
  if (!im) return;
  let h = aj ? 200 : 196;
  if (c.sliding) h = 128;   // 滑铲姿态压低
  const w = h * im.width / im.height;

  // 陷阱落点预警红圈（只有蚩尤的改陷阱/骨刺技能带 warnX）
  if (!aj && c.skillT >= 0 && c.skillT < 1.3 && c.warnX !== undefined) {
    const sx = c.warnX - world.x + 76;
    if (sx > -60 && sx < W + 60) {
      const pr = 30 + Math.sin(c.t * 11) * 7;
      ctx.beginPath();
      ctx.arc(sx, GROUND_Y - 66, pr, 0, Math.PI * 2);
      ctx.lineWidth = 4;
      ctx.strokeStyle = 'rgba(255,70,50,' + (0.45 + 0.3 * Math.sin(c.t * 11)) + ')';
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(sx, GROUND_Y - 66, pr * 0.5, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(255,70,50,0.14)';
      ctx.fill();
    }
  }

  // 影子（跟他的实际高度走）：软阴影，和玩家统一
  const sg2 = ctx.createRadialGradient(c.bx + w * 0.42, GROUND_Y - 4, 2, c.bx + w * 0.42, GROUND_Y - 4, w * 0.46);
  sg2.addColorStop(0, 'rgba(0,0,0,0.22)');
  sg2.addColorStop(0.6, 'rgba(0,0,0,0.09)');
  sg2.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = sg2;
  ctx.beginPath();
  ctx.ellipse(c.bx + w * 0.42, GROUND_Y - 4, w * 0.46, 11, 0, 0, Math.PI * 2);
  ctx.fill();

  ctx.save();
  ctx.globalAlpha = c.hurtT > 0 ? 0.5 + 0.4 * Math.sin(c.t * 30) : 0.88;
  ctx.imageSmoothingEnabled = true;
  // 追击者同样加一层背光描边，和玩家保持一致的画面语言
  ctx.shadowColor = 'rgba(8,14,26,0.85)';
  ctx.shadowBlur = 9;
  ctx.shadowOffsetY = 3;
  ctx.drawImage(im, 0, 0, im.width, im.height, c.bx, c.y - h, w, h);
  ctx.shadowBlur = 0;
  ctx.shadowColor = 'transparent';
  ctx.shadowOffsetY = 0;
  ctx.drawImage(im, 0, 0, im.width, im.height, c.bx, c.y - h, w, h);
  ctx.restore();

  if (aj) {
    // 忍印 HUD：头顶三格紫色忍印，满三格闪烁（大招影分身将至）
    if (c.marks > 0) {
      const mw = 16, gap = 6, total = 3 * mw + 2 * gap;
      const mx0 = c.bx + w * 0.42 - total / 2, my = c.y - h - 22;
      for (let i = 0; i < 3; i++) {
        const full = i < c.marks;
        ctx.fillStyle = full
          ? (c.marks >= 3 ? 'rgba(255,110,220,' + (0.6 + 0.4 * Math.sin(G.time * 10)) + ')' : 'rgba(176,106,255,0.95)')
          : 'rgba(28,36,58,0.75)';
        ctx.fillRect(mx0 + i * (mw + gap), my, mw, 7);
        if (full) {
          ctx.fillStyle = 'rgba(230,204,255,0.9)';
          ctx.fillRect(mx0 + i * (mw + gap) + 2, my + 2, mw - 4, 3);
        }
      }
    }
    // 大招影分身：缀在后方一起追的半透明影子（帧率错开制造错位感）
    if (c.cloneOn) {
      const cim = BOSS_IMG['aj_run' + (1 + Math.floor(c.t * 8 + 1) % 2)];
      if (cim) {
        const ch2 = h * 0.98;
        const cw2 = ch2 * cim.width / cim.height;
        ctx.save();
        ctx.globalAlpha = 0.5 + 0.08 * Math.sin(G.time * 7);
        ctx.drawImage(cim, 0, 0, cim.width, cim.height, c.cloneBx, GROUND_Y - ch2, cw2, ch2);
        ctx.restore();
      }
    }
  }
}

/* BOSS：按状态挑帧，冲刺时留残影；阿坚=影分身（真身红圈标记） */
function drawBoss() {
  const b = G.boss;
  if (!b) return;
  const aj = b.skin === 'aj';
  // 跑动帧：蚩尤三帧循环，阿坚两帧
  const f = Math.floor(b.frame * 8) % (aj ? 2 : 3);
  let key;
  if (aj) {
    if (b.state === 'transform') key = 'aj_dive';
    else if (b.state === 'dashAtk') key = b.t < 0.4 ? 'ajw_swing3' : 'ajw_swing2';
    else if (b.state === 'slideAtk') key = 'aj_slide';
    else if (b.state === 'quakeAtk') key = b.t < 0.45 ? 'ajw_swing1' : 'ajw_swing2';
    else if (b.state === 'chainAtk') key = b.t < 0.4 ? 'aj_throw1' : 'ajw_swing2';
    else if (b.state === 'dead') key = 'aj_slide';
    else if (b.state === 'retreat') key = 'aj_run2';
    else key = ['aj_run1', 'aj_run2'][f];
  } else {
  if (b.state === 'transform') key = 'chiyou_stand_wing';
  else if (b.state === 'dashAtk') key = (b.t > 0.4 && b.t < 0.78) ? 'chiyou_punch' : 'chiyou_dash';
  else if (b.state === 'slideAtk') key = 'chiyou_slide';
  else if (b.state === 'quakeAtk') key = b.t < 0.45 ? 'chiyou_skill' : 'chiyou_punch2';
  else if (b.state === 'chainAtk') key = b.t < 0.4 ? 'chiyou_skill' : 'chiyou_punch';
  else if (b.state === 'dead') key = 'chiyou_punch2';
  else if (b.state === 'retreat') key = 'chiyou_run3';
  else if (b.mech) key = ['chiyou_mech_run', 'chiyou_mech_run2', 'chiyou_mech_full'][f];
  else key = ['chiyou_run1', 'chiyou_run2', 'chiyou_run3'][f];
  }

  const im = BOSS_IMG[key] || BOSS_IMG[aj ? 'aj_run1' : 'chiyou_run1'];
  if (!im) return;
  const h = 224;
  const w = h * im.width / im.height;
  const flick = (b.state === 'transform') ? (0.5 + 0.5 * Math.sin(b.t * 18)) : 1;
  const glowColor = aj ? '176,106,255' : '255,60,40';

  const drawBody = (x, alpha) => {
    ctx.save();
    ctx.globalAlpha = alpha * flick;
    // 出场/压制的地光（蚩尤红 / 阿坚紫）
    if (b.state === 'fight' || b.state === 'mech' || b.state === 'enter') {
      const gl = ctx.createLinearGradient(x - 40, GROUND_Y - 60, x + w + 40, GROUND_Y);
      gl.addColorStop(0, 'rgba(' + glowColor + ',0)');
      gl.addColorStop(0.5, 'rgba(' + glowColor + ',' + (0.25 + 0.1 * Math.sin(G.time * 6)) + ')');
      gl.addColorStop(1, 'rgba(' + glowColor + ',0)');
      ctx.fillStyle = gl;
      ctx.fillRect(x - 40, GROUND_Y - 60, w + 80, 62);
    }
    // BOSS 在玩家身后向右追，素材朝向正好
    ctx.drawImage(im, 0, 0, im.width, im.height, x, GROUND_Y - h, w, h);
    ctx.restore();
  };

  if (aj && b.clones && b.state !== 'dead' && b.state !== 'retreat') {
    // 影分身：真身脚下红圈是唯一弱点；分身半透明
    for (const cl of b.clones) {
      if (cl.dead > 0) continue;
      const x = b.bx + cl.dx;
      if (cl.real) {
        const ra = 0.4 + 0.3 * Math.sin(G.time * 9);
        ctx.strokeStyle = 'rgba(255,70,90,' + ra.toFixed(3) + ')';
        ctx.lineWidth = 4;
        ctx.beginPath();
        ctx.ellipse(x + w * 0.42, GROUND_Y - 6, w * 0.34, 9, 0, 0, Math.PI * 2);
        ctx.stroke();
        drawBody(x, 0.95);
      } else {
        drawBody(x, 0.48);
      }
    }
  } else {
    drawBody(b.bx, 1);
  }

  // 飞刀（旋转的蓝色小刀）
  for (const kn of world.knives) {
    ctx.save();
    ctx.translate(kn.x, kn.y);
    ctx.rotate(kn.spin);
    ctx.fillStyle = '#8ce8ff';
    ctx.fillRect(-3, -14, 6, 24);
    ctx.fillStyle = '#e8fbff';
    ctx.beginPath();
    ctx.moveTo(0, -18);
    ctx.lineTo(6, -8);
    ctx.lineTo(-6, -8);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }

  // 弹幕：阿坚的画成旋转手里剑，蚩尤的画血珠；wave=贴地剑气波（宽扁贴图不旋转）
  const sim = BOSS_IMG.aj_shuriken;
  const wsim = BOSS_IMG.ajw_slash;
  for (const bl of world.bullets) {
    if (bl.wave) {
      // BOSS 战剑气波：宽扁贴图 + 向后拖尾，朝左飞
      const bw2 = bl.r * 5.6, bh2 = bl.r * 2.2;
      if (wsim && wsim.width > 0) {
        ctx.save();
        ctx.translate(bl.x, bl.y);
        ctx.scale(-1, 1);   // 弹幕朝左飞，贴图水平翻转
        for (let tr = 2; tr >= 1; tr--) {
          ctx.globalAlpha = tr === 1 ? 0.35 : 0.18;
          ctx.drawImage(wsim, -bw2 / 2 + tr * 18, -bh2 / 2, bw2, bh2);
        }
        ctx.globalAlpha = 1;
        ctx.drawImage(wsim, -bw2 / 2, -bh2 / 2, bw2, bh2);
        ctx.restore();
      } else {
        ctx.save();
        ctx.translate(bl.x, bl.y);
        ctx.strokeStyle = 'rgba(255,70,50,0.9)';
        ctx.lineWidth = 6;
        ctx.beginPath();
        ctx.arc(0, 0, bl.r, Math.PI - 0.9, Math.PI + 0.9);
        ctx.stroke();
        ctx.restore();
      }
      continue;
    }
    if (bl.sp && sim && sim.width > 0) {
      ctx.save();
      ctx.translate(bl.x, bl.y);
      ctx.rotate(bl.spin || 0);
      ctx.drawImage(sim, -bl.r * 2, -bl.r * 2, bl.r * 4, bl.r * 4);
      ctx.restore();
      continue;
    }
    ctx.beginPath();
    ctx.arc(bl.x, bl.y, bl.r, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,90,70,0.95)';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(bl.x - bl.r * 0.3, bl.y - bl.r * 0.3, bl.r * 0.42, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255,220,180,0.9)';
    ctx.fill();
  }
}

/* BOSS 血条 + 倒计时 */
function drawBossHUD() {
  const b = G.boss;
  if (!b) return;
  const bw = 300, bx = (W - bw) / 2, by = 182;
  pxRect(bx, by, bw, 22, 6);
  ctx.fillStyle = 'rgba(8,12,22,0.85)';
  ctx.fill();
  const p = b.hp / b.maxHp;
  const aj = b.skin === 'aj';
  if (p > 0.01) {
    pxRect(bx + 2, by + 2, Math.max(6, (bw - 4) * p), 18, 4);
    ctx.fillStyle = aj ? (b.mech ? '#ff7ab8' : '#b06aff') : (b.mech ? '#8ce8ff' : '#ff5a4a');
    ctx.fill();
  }
  pxText(aj ? '阿坚' : '蚩尤', bx + 12, by + 11, 15, '#ffffff', 'rgba(10,18,32,0.95)', 'left');
  if (b.state === 'fight' || b.state === 'mech') {
    const left = Math.max(0, 30 - b.fightT);
    pxNum(left.toFixed(0) + 's', bx + bw + 8, by + 11, 16, '#8ce8ff', 'rgba(10,18,32,0.9)', 'left');
  }
  // 飞刀余量
  if ((G.knives || 0) > 0) {
    pxNum('飞刀 ×' + G.knives, W - 24, by + 11, 16, '#8ce8ff', 'rgba(10,18,32,0.9)', 'right');
  }
}

/* ---------------- 火焰喷射口 / 传送带 / 上升气流 绘制 ---------------- */
function drawNewHazards() {
  // 传送带（覆盖在地面之上）
  for (const b of world.belts) {
    const sx = Math.round(b.x - world.x);
    if (sx > W + 60 || sx + b.w < -60) continue;
    ctx.fillStyle = b.fast ? 'rgba(28,66,92,0.96)' : 'rgba(74,60,44,0.96)';
    ctx.fillRect(sx, GROUND_Y - 14, b.w, 14);
    const off = (G.time * (b.fast ? 200 : 95)) % 46;
    ctx.fillStyle = b.fast ? 'rgba(140,225,255,0.92)' : 'rgba(186,154,112,0.92)';
    for (let ax = sx - off + 8; ax < sx + b.w - 10; ax += 46) {
      if (ax < sx + 2) continue;
      ctx.beginPath();
      if (b.fast) { ctx.moveTo(ax, GROUND_Y - 12); ctx.lineTo(ax + 15, GROUND_Y - 7); ctx.lineTo(ax, GROUND_Y - 2); }
      else { ctx.moveTo(ax + 15, GROUND_Y - 12); ctx.lineTo(ax, GROUND_Y - 7); ctx.lineTo(ax + 15, GROUND_Y - 2); }
      ctx.closePath();
      ctx.fill();
    }
    ctx.strokeStyle = b.fast ? 'rgba(140,225,255,0.55)' : 'rgba(186,154,112,0.45)';
    ctx.lineWidth = 2;
    ctx.strokeRect(sx, GROUND_Y - 14, b.w, 14);
  }
  // 火焰喷射口
  for (const f of world.flames) {
    const sx = Math.round(f.x - world.x);
    if (sx > W + 60 || sx + f.w < -60) continue;
    ctx.fillStyle = '#2c3547';
    ctx.fillRect(sx - 6, GROUND_Y - 16, f.w + 12, 16);
    ctx.fillStyle = '#4a586f';
    ctx.fillRect(sx - 6, GROUND_Y - 16, f.w + 12, 5);
    ctx.fillStyle = '#121826';
    for (let i = 0; i < 3; i++) ctx.fillRect(sx + 7 + i * 14, GROUND_Y - 11, 8, 5);
    const fire = f.t >= 2.25 && f.t < 3.1;
    if (f.t >= 1.55 && f.t < 2.25) {
      const a = 0.28 + 0.5 * Math.abs(Math.sin(f.t * 22));
      ctx.fillStyle = 'rgba(255,80,60,' + a.toFixed(2) + ')';
      ctx.fillRect(sx + 4, GROUND_Y - 152, f.w - 8, 138);
    }
    if (fire) {
      const grow = Math.min(1, (f.t - 2.25) / 0.16);
      const fade = f.t > 2.92 ? Math.max(0.25, 1 - (f.t - 2.92) / 0.18) : 1;
      const hgt = 150 * grow * fade;
      const g1 = ctx.createLinearGradient(0, GROUND_Y - hgt, 0, GROUND_Y);
      g1.addColorStop(0, 'rgba(255,140,60,0.88)');
      g1.addColorStop(1, 'rgba(255,70,40,0.96)');
      ctx.fillStyle = g1;
      ctx.fillRect(sx + 3, GROUND_Y - hgt, f.w - 6, hgt);
      const g2 = ctx.createLinearGradient(0, GROUND_Y - hgt * 0.82, 0, GROUND_Y);
      g2.addColorStop(0, 'rgba(255,232,150,0.95)');
      g2.addColorStop(1, 'rgba(255,190,90,0.9)');
      ctx.fillStyle = g2;
      ctx.fillRect(sx + 12, GROUND_Y - hgt * 0.82, f.w - 24, hgt * 0.82);
      if (Math.random() < 0.5) {
        FX.burst(f.x + rnd(6, f.w - 6), GROUND_Y - hgt, 1, {
          vx0: -40, vx1: 40, vy0: -170, vy1: -60,
          life0: 0.2, life1: 0.4, size0: 4, size1: 9, color: 'rgba(255,170,80,0.9)',
        });
      }
    }
  }
  // 上升气流
  for (const u of world.updrafts) {
    const sx = Math.round(u.x - world.x);
    if (sx > W + 60 || sx + u.w < -60) continue;
    const top = GROUND_Y - u.h;
    const g = ctx.createLinearGradient(0, GROUND_Y, 0, top);
    g.addColorStop(0, 'rgba(120,230,210,0.17)');
    g.addColorStop(1, 'rgba(120,230,210,0.04)');
    ctx.fillStyle = g;
    ctx.fillRect(sx, top, u.w, u.h);
    for (let i = 0; i < 3; i++) {
      const ly = GROUND_Y - ((G.time * 250 + i * 137) % u.h);
      ctx.fillStyle = 'rgba(150,240,220,0.42)';
      ctx.fillRect(sx + 18 + i * ((u.w - 40) / 2), ly, 3, 26);
    }
    ctx.fillStyle = 'rgba(150,240,220,0.55)';
    ctx.fillRect(sx, top, u.w, 3);
  }
}

/* ---------------- 地刺浪 / 陨石 / 链锤 绘制 ---------------- */
function drawChiyouSkills() {
  // 地刺浪：一排行进的土刺
  for (const q of world.quakes) {
    const sx = Math.round(q.x - world.x);
    if (sx < -80 || sx > W + 80) continue;
    ctx.fillStyle = '#8a7658';
    for (let i = 0; i < 4; i++) {
      const h = (34 + (i % 2) * 16) * Math.min(1, q.t / 0.12);
      const bx2 = sx - 26 + i * 15;
      ctx.beginPath();
      ctx.moveTo(bx2 - 8, GROUND_Y);
      ctx.lineTo(bx2, GROUND_Y - h);
      ctx.lineTo(bx2 + 8, GROUND_Y);
      ctx.closePath();
      ctx.fill();
    }
    ctx.fillStyle = 'rgba(255,120,80,0.35)';
    ctx.fillRect(sx - 30, GROUND_Y - 88, 60, 88);
  }
  // 陨石：预警红圈 → 火球下落 → 爆炸圈
  for (const m of world.meteors) {
    const sx = Math.round(m.tx - world.x);
    if (sx < -120 || sx > W + 120) continue;
    if (m.boom > 0) {
      const p = m.boom / 0.5;
      ctx.strokeStyle = 'rgba(255,140,70,' + (1 - p).toFixed(3) + ')';
      ctx.lineWidth = 7;
      ctx.beginPath();
      ctx.arc(sx, GROUND_Y - 60, 40 + p * 60, 0, Math.PI * 2);
      ctx.stroke();
      continue;
    }
    // 落点预警红圈
    const warn = 0.4 + 0.4 * Math.sin(G.time * 12);
    ctx.strokeStyle = 'rgba(255,70,50,' + warn.toFixed(3) + ')';
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.ellipse(sx, GROUND_Y - 6, 58, 14, 0, 0, Math.PI * 2);
    ctx.stroke();
    // 下落火球（斜向）
    const p = m.t / m.dur;
    const my = -60 + (GROUND_Y - 40) * p * p;
    const mx = sx + 150 * (1 - p);
    ctx.fillStyle = '#ff9a5a';
    ctx.beginPath();
    ctx.arc(mx, my, 15, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(255,220,120,0.9)';
    ctx.beginPath();
    ctx.arc(mx - 8, my - 8, 16, 0, Math.PI * 2);
    ctx.fill();
  }
  // 旋转链锤：链子 + 锤头
  for (const ch of world.chains) {
    const x0 = ch.x0 - world.x;
    if (x0 > W + 100 || x0 + ch.len < -100) continue;
    const hx = x0 + ch.len;
    ctx.strokeStyle = 'rgba(120,110,96,0.9)';
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.moveTo(x0, GROUND_Y - 120);
    ctx.lineTo(hx, GROUND_Y - 46);
    ctx.stroke();
    ctx.save();
    ctx.translate(hx, GROUND_Y - 46);
    ctx.rotate(ch.t * 14);
    ctx.fillStyle = '#5a5248';
    ctx.fillRect(-17, -17, 34, 34);
    ctx.fillStyle = '#786d5c';
    ctx.fillRect(-12, -12, 24, 24);
    ctx.restore();
  }
}

/* ---------------- 蚩尤常态攻击：骨刺弹 + 落点刺坑 ---------------- */
function drawChiyouAttacks() {
  // 阿坚的手里剑（常态技能弹，屏幕坐标，旋转贴图）；巨剑更大且落地后贴地旋转
  // wave = 剑气波：宽扁贴图不旋转，贴地飞行（阿坚专属武器·剑气）
  const sim = BOSS_IMG.aj_shuriken;
  const wsim = BOSS_IMG.ajw_slash;
  if (world.shurikens && world.shurikens.length) {
    for (const st of world.shurikens) {
      // 剑气波绘制：90×36 宽扁，红色拖尾
      if (st.wave) {
        const wx = st.x, wy = st.y;
        if (wsim && wsim.width > 0) {
          ctx.save();
          ctx.translate(wx, wy);
          // 拖尾残影（向后渐隐两层）
          for (let tr = 2; tr >= 1; tr--) {
            ctx.globalAlpha = tr === 1 ? 0.35 : 0.18;
            ctx.drawImage(wsim, -45 - tr * 16, -18, 90, 36);
          }
          ctx.globalAlpha = 1;
          // 本体随时间轻微上浮波动，更有"剑气"感
          const wob = Math.sin(st.x * 0.03) * 4;
          ctx.drawImage(wsim, -45, -18 + wob, 90, 36);
          ctx.restore();
        } else {
          // 无贴图兜底：红色月牙形剑气
          ctx.save();
          ctx.translate(wx, wy);
          ctx.strokeStyle = 'rgba(255,70,50,0.9)';
          ctx.lineWidth = 7;
          ctx.beginPath();
          ctx.arc(0, 0, 20, -0.9, 0.9);
          ctx.stroke();
          ctx.strokeStyle = 'rgba(255,160,120,0.5)';
          ctx.lineWidth = 3;
          ctx.beginPath();
          ctx.arc(-8, 0, 20, -0.8, 0.8);
          ctx.stroke();
          ctx.restore();
        }
        continue;
      }
      const big = !!st.big;
      const sz = big ? (st.landed ? 64 : 56) : 46;
      const sy = big && st.landed ? GROUND_Y - 30 : st.y;
      // 贴地剑阵的地面影子
      if (big && st.landed) {
        ctx.fillStyle = 'rgba(0,0,0,0.18)';
        ctx.beginPath();
        ctx.ellipse(st.x, GROUND_Y - 4, sz * 0.55, 7, 0, 0, Math.PI * 2);
        ctx.fill();
      }
      if (sim && sim.width > 0) {
        ctx.save();
        ctx.translate(st.x, sy);
        ctx.rotate(st.spin || 0);
        ctx.drawImage(sim, -sz / 2, -sz / 2, sz, sz);
        ctx.restore();
      } else {
        ctx.beginPath();
        ctx.arc(st.x, sy, sz * 0.28, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(200,140,255,0.95)';
        ctx.fill();
      }
    }
  }
  // 空中旋转骨刺
  for (const fb of world.fireballs) {
    const sx = fb.wx0 + (fb.wx1 - fb.wx0) * (fb.t / fb.dur) - world.x;
    const sy = GROUND_Y - 200 - Math.sin((fb.t / fb.dur) * Math.PI) * 265;
    if (sx < -60 || sx > W + 60) continue;
    ctx.save();
    ctx.translate(sx, sy);
    ctx.rotate(fb.t * 9);
    ctx.fillStyle = '#e8dcc2';
    ctx.beginPath();
    ctx.arc(0, 0, 13, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#c9b790';
    for (let i = 0; i < 6; i++) {
      const a = i * Math.PI / 3;
      ctx.beginPath();
      ctx.moveTo(Math.cos(a) * 11, Math.sin(a) * 11);
      ctx.lineTo(Math.cos(a + 0.42) * 25, Math.sin(a + 0.42) * 25);
      ctx.lineTo(Math.cos(a + 0.84) * 11, Math.sin(a + 0.84) * 11);
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
  }
  // 落点刺坑：红光 + 骨刺阵
  for (const p of world.firePits) {
    const px = Math.round(p.x - world.x);
    if (px < -90 || px > W + 90) continue;
    if (p.t < 0.55) {
      const a = Math.max(0, 1 - p.t / 0.55);
      ctx.fillStyle = 'rgba(255,90,60,' + (a * 0.38).toFixed(3) + ')';
      ctx.fillRect(px - 52, GROUND_Y - 122, 104, 122);
      ctx.fillStyle = '#e8dcc2';
      for (let i = 0; i < 5; i++) {
        const h = (30 + (i % 2) * 20) * Math.min(1, p.t / 0.09);
        const bx2 = px - 40 + i * 20;
        ctx.beginPath();
        ctx.moveTo(bx2 - 7, GROUND_Y);
        ctx.lineTo(bx2, GROUND_Y - h);
        ctx.lineTo(bx2 + 7, GROUND_Y);
        ctx.closePath();
        ctx.fill();
      }
    }
  }
}

/* ---------------- 命运之门绘制 ---------------- */
function drawGates() {
  for (const gt of world.gates) {
    const sx = Math.round(gt.x - world.x);
    if (sx < -170 || sx > W + 170) continue;
    const fade = gt.used ? Math.max(0, 1 - gt.hitT / 0.6) : 1;
    ctx.globalAlpha = fade;
    drawGateOne(sx, gt.top, true);
    drawGateOne(sx, gt.bot, false);
    ctx.globalAlpha = 1;
  }
}

function drawGateOne(sx, g, isTop) {
  const w = 124, h = 188;
  const by = isTop ? GROUND_Y - 214 : GROUND_Y;   // 上门悬空（与下门完全分开），下门落地
  const ty = by - h;
  const pulse = 0.5 + 0.5 * Math.sin(G.time * 4.2 + (isTop ? 0 : 2));

  // 门内流光
  const grd = ctx.createLinearGradient(0, ty, 0, by);
  grd.addColorStop(0, hexA(g.color, 0.46 + pulse * 0.14));
  grd.addColorStop(1, hexA(g.color, 0.05));
  ctx.fillStyle = grd;
  ctx.fillRect(sx - w / 2 + 10, ty + 11, w - 20, h - 11);

  // 门框
  ctx.fillStyle = g.color;
  ctx.fillRect(sx - w / 2, ty, 11, h);
  ctx.fillRect(sx + w / 2 - 11, ty, 11, h);
  ctx.fillRect(sx - w / 2, ty, w, 11);
  // 高光
  ctx.fillStyle = 'rgba(255,255,255,0.5)';
  ctx.fillRect(sx - w / 2, ty, w, 3);
  // 风险门：红色警示斜纹
  if (g.risk) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(sx - w / 2 + 11, ty + 11, w - 22, h - 11);
    ctx.clip();
    ctx.strokeStyle = 'rgba(255,90,90,' + (0.2 + pulse * 0.2) + ')';
    ctx.lineWidth = 7;
    for (let i = -2; i < 8; i++) {
      ctx.beginPath();
      ctx.moveTo(sx - w / 2 + i * 26, by);
      ctx.lineTo(sx - w / 2 + i * 26 + 60, ty);
      ctx.stroke();
    }
    ctx.restore();
  }

  // 图标 + 名称 + 操作提示
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = 'bold 36px "Microsoft YaHei", "PingFang SC", sans-serif';
  ctx.fillStyle = 'rgba(16,24,40,0.9)';
  ctx.fillText(g.icon, sx, ty + 58);
  ctx.font = 'bold 17px "Microsoft YaHei", "PingFang SC", sans-serif';
  ctx.fillStyle = 'rgba(16,24,40,0.92)';
  ctx.fillText(g.name, sx, ty + 96);
  ctx.font = 'bold 15px "Microsoft YaHei", "PingFang SC", sans-serif';
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  ctx.fillText(isTop ? '↑ 跳着穿' : '↓ 跑过 / 滑铲', sx, by - 12);
}

/* ---------------- FEVER 狂热 HUD ---------------- */
function drawFeverHud() {
  if (G.mode !== 'endless' || G.state !== 'playing') return;
  if (G.feverT > 0) {
    // 顶部 FEVER 计时条
    const bw = 216, bx = (W - bw) / 2, by = 148;
    ctx.fillStyle = 'rgba(10,16,28,0.55)';
    ctx.fillRect(bx - 4, by - 4, bw + 8, 30);
    const p = G.feverT / 5;
    ctx.fillStyle = '#ffd34d';
    ctx.fillRect(bx, by, bw * p, 22);
    ctx.fillStyle = 'rgba(255,255,255,' + (0.35 + 0.25 * Math.sin(G.time * 10)) + ')';
    ctx.fillRect(bx, by, bw * p, 5);
    ctx.font = 'bold 17px "Microsoft YaHei", "PingFang SC", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#1a1206';
    ctx.fillText('FEVER  ' + G.feverT.toFixed(1) + 's', W / 2, by + 12);
    // 屏幕金边脉冲
    ctx.strokeStyle = 'rgba(255,211,77,' + (0.22 + 0.18 * Math.sin(G.time * 9)).toFixed(3) + ')';
    ctx.lineWidth = 10;
    ctx.strokeRect(5, 5, W - 10, H - 10);
  } else if (G.combo >= G.feverNeed - 8) {
    // 临近 FEVER：提示还差几个连击
    ctx.font = 'bold 18px "Microsoft YaHei", "PingFang SC", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = 'rgba(255,225,130,' + (0.65 + 0.3 * Math.sin(G.time * 6)).toFixed(3) + ')';
    ctx.fillText('再 ' + (G.feverNeed - G.combo) + ' 连击 → FEVER !', W / 2, 200);
  }
}

function drawSpeedLines() {
  if (FX.speedLines < 0.06) return;
  ctx.globalAlpha = FX.speedLines * 0.42;
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 3;
  for (let i = 0; i < 9; i++) {
    const seed = i * 137.5;
    const y = ((G.time * 1250 + seed * 7) % 1150) - 120;
    const x = i % 2 ? 22 + (seed % 46) : W - 22 - (seed % 46);
    const len = 70 + (seed % 60);
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x, y + len);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x + 14, y + 30);
    ctx.lineTo(x + 14, y + 30 + len * 0.6);
    ctx.globalAlpha = FX.speedLines * 0.24;
    ctx.stroke();
    ctx.globalAlpha = FX.speedLines * 0.42;
  }
  ctx.globalAlpha = 1;
}

// ---- 覆盖层 ----
function drawOverlays() {
  switch (G.state) {
    case 'menu':
      ctx.fillStyle = 'rgba(10,20,36,0.26)';
      ctx.fillRect(0, 0, W, H);
      drawMenu();
      break;
    case 'levels':
      ctx.fillStyle = 'rgba(10,20,36,0.55)';
      ctx.fillRect(0, 0, W, H);
      drawLevelSelect();
      break;
    case 'skins':
      ctx.fillStyle = 'rgba(10,20,36,0.55)';
      ctx.fillRect(0, 0, W, H);
      drawSkinSelect();
      break;
    case 'creative':
      drawCreative();
      break;
    case 'ugcedit':
      drawUgcEdit();
      break;
    case 'paused': drawPause(); break;
    case 'gameover': drawGameOver(); break;
    case 'levelclear': drawLevelClear(); break;
    case 'levelfail': drawLevelFail(); break;
  }
}

/* ---------------- 渲染入口 ---------------- */
/* 十六进制颜色 → rgba 字符串 */
function hexA(hex, a) {
  const r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16);
  return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
}
/* 颜色插值（#rrggbb 之间） */
function lerpColor(c1, c2, t) {
  const p = (h, i) => parseInt(h.slice(i, i + 2), 16);
  const r = Math.round(p(c1, 1) + (p(c2, 1) - p(c1, 1)) * t);
  const g = Math.round(p(c1, 3) + (p(c2, 3) - p(c1, 3)) * t);
  const b = Math.round(p(c1, 5) + (p(c2, 5) - p(c1, 5)) * t);
  return 'rgb(' + r + ',' + g + ',' + b + ')';
}

/* 夜空：星星 + 月亮（白天淡出） */
function drawCelestial(na) {
  if (na <= 0.02) return;
  for (let i = 0; i < 26; i++) {
    const sx = ((i * 197 + 61) % 520) + 10;
    const sy = ((i * 131 + 37) % 240) + 190;   // 避开顶部 HUD 区域
    const tw = 0.4 + 0.6 * Math.abs(Math.sin(G.time * (1.2 + (i % 5) * 0.3) + i * 2.3));
    ctx.globalAlpha = na * tw * 0.9;
    ctx.fillStyle = i % 6 === 0 ? '#ffe9a8' : '#ffffff';
    ctx.fillRect(sx, sy, i % 4 === 0 ? 3 : 2, i % 4 === 0 ? 3 : 2);
  }
  ctx.globalAlpha = 1;
  // 月亮：右上角偏下，避开 HUD（用挖圆做出弯月）
  const mx = W - 74, my = 212;
  ctx.globalAlpha = na * 0.95;
  ctx.fillStyle = '#f4ecd2';
  ctx.beginPath();
  ctx.arc(mx, my, 34, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = lerpColor('#3b9cec', '#131c42', na);
  ctx.beginPath();
  ctx.arc(mx - 13, my - 9, 29, 0, Math.PI * 2);
  ctx.fill();
  ctx.globalAlpha = 1;
}

function render() {
  // 高清渲染：按设备像素比缩放整个坐标系（逻辑坐标保持 W×H）
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  // 昼夜天空：分层渐变（顶部深、地平线暖），比单色平涂更有空间感
  const na = nightAmount();
  ctx.fillStyle = lerpColor('#a8dcf5', '#2b3566', na);
  ctx.fillRect(0, 0, W, H);
  drawSkyGradient(na);
  drawCelestial(na);

  ctx.save();
  if (FX.shake > 0.01) {
    ctx.translate(rnd(-1, 1) * FX.shake * 12, rnd(-1, 1) * FX.shake * 9);
  }
  drawBackground();
  drawClouds(na);
  drawFinishGate();
  drawPlatforms();
  drawMovingPlatforms();
  drawGaps();
  drawGround();
  drawForegroundGrass();   // 前景草叶剪影：给地面压出一道纵深
  drawFakeFloors();
  drawBoosters();
  drawCrumbles();
  drawLowBars();
  drawCeilSpikes();
  drawCrushers();
  drawSpikeTraps();
  drawHiddenSpikes();
  drawTrapSprings();
  drawNewHazards();
  drawWeather();
  drawUgcHazards();
  runUgcHook('draw', { worldX: world.x });   // mod 自定义绘制钩子（ctx 全局可用，画实体世界坐标 - worldX）
  drawBouncePads();
  drawFallingRocks();
  drawEnemies();
  drawChiyou();
  drawChiyouAttacks();
  drawChiyouSkills();
  drawBoss();
  drawPet();
  drawSigns();
  drawGates();
  drawAltars();
  drawCoins();
  drawPowers();
  drawFakeCoins();
  drawPendulums();
  drawRollingRocks();
  FX.drawWorld(ctx, world.x);
  // 世界里的小人只在游戏进行时画，菜单/选择界面各有各的立绘
  const inGame = G.state === 'playing' || G.state === 'paused' || G.state === 'dying' || G.state === 'altar'
    || G.state === 'gameover' || G.state === 'levelclear' || G.state === 'levelfail';
  if (inGame) drawPlayer();
  ctx.restore();

  // 天气氛围（沙尘/浓雾/酸雨的天色）：罩世界，不罩 HUD
  drawWeatherOverlay();

  // 夜幕：全屏压暗 + 玩家周围光圈（只罩世界，不罩 HUD）
  if (na > 0.02) {
    ctx.fillStyle = 'rgba(10,14,38,' + (na * 0.42).toFixed(3) + ')';
    ctx.fillRect(0, 0, W, H);
    ctx.globalCompositeOperation = 'destination-out';
    const lx = PLAYER_X + 30, ly = player.y - 74;
    const grd = ctx.createRadialGradient(lx, ly, 30, lx, ly, 252);
    grd.addColorStop(0, 'rgba(0,0,0,' + (na * 0.92).toFixed(3) + ')');
    grd.addColorStop(0.62, 'rgba(0,0,0,' + (na * 0.4).toFixed(3) + ')');
    grd.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = grd;
    ctx.fillRect(0, 0, W, H);
    ctx.globalCompositeOperation = 'source-over';
  }

  drawVignette();
  drawMoonOverlay();   // 月面段的星空与冷色罩
  drawRadialSpeed();   // 高速时的径向速度线
  drawGrain();         // 轻微胶片颗粒，统一画面质感

  drawSpeedLines();
  drawFeverHud();
  drawBossHUD();
  drawHUD();
  drawUgcHud();   // mod 注册的自定义 UI（最上层，被遮罩压暗也保持可读）
  drawAltarPanel();
  drawCardPop();
  FX.drawScreen(ctx);
  drawOverlays();
  // 主菜单角色绘制在暗色遮罩之上，保持通透明亮
  if (G.state === 'menu') drawPlayer();
}

// ---- 输入 ----
function pointerPos(e) {
  const r = cv.getBoundingClientRect();
  const t = (e.touches && e.touches[0]) ? e.touches[0] : e;
  return {
    x: (t.clientX - r.left) * (W / r.width),
    y: (t.clientY - r.top) * (H / r.height),
  };
}

function onDown(e) {
  Snd.init();
  Music.start();   // 首次手势点亮 BGM（浏览器自动播放限制，静默失败无害）
  const p = pointerPos(e);
  const pid = (e.pointerId !== undefined) ? e.pointerId : 'mouse';
  const list = getUIButtons().list;
  for (const b of list) {
    if (p.x >= b.x && p.x <= b.x + b.w && p.y >= b.y && p.y <= b.y + b.h) {
      G.pressed[b.id] = true;
      activePtr.set(pid, b.id);
      refreshHolds();
      if (!b.silent) {
        FX.ripple(b.x + b.w / 2, b.y + b.h / 2, { r1: Math.max(b.w, b.h) * 0.75 });
        Snd.click();
      }
      b.act();
      return;
    }
  }
  if (G.state === 'ugcedit') {
    // 素材列表行内按钮的手动热区（设背景/删）
    ugcEditTap = { x: p.x, y: p.y };
    ugcTapT = G.time;
    return;
  }
  if (G.state === 'playing') {
    // mod 主动技能按钮优先命中（点中不触发跳跃）
    const mb = (world.ugcBtns || []).find(b => b.fn && p.x >= b.x && p.x <= b.x + b.w && p.y >= b.y && p.y <= b.y + b.h);
    if (mb) {
      FX.ripple(p.x, p.y, { r1: 60 });
      runUgcHook('_btn', {}, [mb]);
      return;
    }
    runUgcHook('tap', { x: p.x, y: p.y });   // mod 点击钩子（不影响原生跳跃判定）
    if (p.y < 780) {
      // 点上半屏也算按住跳跃，另一根手指按着下滑就能矮跳
      activePtr.set(pid, 'jump');
      refreshHolds();
      doJump();
      FX.sburst(p.x, p.y, 5, {
        vx0: -70, vx1: 70, vy0: -90, vy1: -20,
        life0: 0.2, life1: 0.4, size0: 4, size1: 9, color: 'rgba(255,255,255,0.7)',
      });
    }
  } else if (G.state === 'menu') {
    startEndless();
  }
}

/* 多指触控：每个手指各自记录按住的按钮，互不覆盖 */
const activePtr = new Map();   // pointerId -> 按钮 id

function refreshHolds() {
  let jump = false, slide = false;
  for (const id of activePtr.values()) {
    if (id === 'jump') jump = true;
    if (id === 'slide') slide = true;
  }
  G.holdJump = jump;
  G.holdSlide = slide;
}

function onUp(e) {
  const pid = (e && e.pointerId !== undefined) ? e.pointerId : null;
  if (pid !== null) activePtr.delete(pid);
  else activePtr.clear();
  const pressed = {};
  for (const id of activePtr.values()) pressed[id] = true;
  G.pressed = pressed;
  refreshHolds();
}

cv.addEventListener('pointerdown', e => { e.preventDefault(); onDown(e); });
window.addEventListener('pointerup', e => onUp(e));
window.addEventListener('pointercancel', e => onUp(e));
window.addEventListener('resize', resize);
window.addEventListener('orientationchange', () => setTimeout(resize, 120));

window.addEventListener('keydown', e => {
  // 弹窗里的输入框不被游戏快捷键劫持（空格/方向键可正常输入，Escape 退出输入）
  const tag = e.target && e.target.tagName;
  if (tag === 'TEXTAREA' || tag === 'INPUT') {
    if (e.code === 'Escape') e.target.blur();
    return;
  }
  if (e.code === 'Space' || e.code === 'ArrowUp' || e.code === 'ArrowDown') e.preventDefault();
  if (e.repeat) return;
  if (G.state === 'playing') runUgcHook('key', { code: e.code, key: e.key });   // mod 按键钩子（E/Q/F 等自由键位）
  switch (e.code) {
    case 'Space':
    case 'ArrowUp':
    case 'KeyW':
      Snd.init();
      Music.start();
      if (G.state === 'menu') startEndless();
      else if (G.state === 'levels') { /* 需点选关卡 */ }
      else if (G.state === 'gameover') restartCurrent();
      else if (G.state === 'paused') resumeGame();
      else if (G.state === 'levelclear') nextLevel();
      else {
        G.holdJump = true;   // 按住跳 = 满跳；松手会截断成小跳（可变跳跃高度）
        doJump();
      }
      break;
    case 'ArrowDown':
    case 'KeyS':
      G.holdSlide = true;
      doSlide();
      break;
    case 'KeyP':
    case 'Escape':
      if (G.state === 'playing') pauseGame();
      else if (G.state === 'paused') resumeGame();
      else if (G.state === 'creative' || G.state === 'ugcedit') backToMenu();
      else if (G.state === 'levels') backToMenu();
      break;
    case 'KeyC':
      if (G.state === 'menu') openCreative();
      break;
    case 'KeyR':
      if (G.state === 'playing' || G.state === 'paused' || G.state === 'gameover' || G.state === 'levelfail') restartCurrent();
      break;
    case 'KeyM':
      toggleSound();
      break;
  }
});

/* 松键：按多久决定跳多高 / 滑多远 */
window.addEventListener('keyup', e => {
  switch (e.code) {
    case 'Space':
    case 'ArrowUp':
    case 'KeyW':
      G.holdJump = false;
      break;
    case 'ArrowDown':
    case 'KeyS':
      G.holdSlide = false;
      break;
  }
});

document.addEventListener('visibilitychange', () => {
  if (document.hidden && G.state === 'playing') pauseGame();
});

/* ---------------- 主循环 ---------------- */
let lastTs = 0;
function loop(ts) {
  const rawDt = Math.min(0.033, lastTs ? (ts - lastTs) / 1000 : 0);
  lastTs = ts;
  // 全局时间流速（api.timeScale）：倒计时走真实时间，倍率作用于整个逻辑帧（子弹时间/快放）
  if (world.tsT > 0) {
    world.tsT = Math.max(0, world.tsT - rawDt);
    if (world.tsT <= 0) world.tsMul = 1;
  }
  const dt = rawDt * (world.tsMul || 1);
  try {
    update(dt);
    render();
  } catch (e) {
    window.__err = 'RENDER:' + (e && e.message ? e.message : String(e));
    // 直接把错误画在画面上，方便一眼定位
    try {
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
      ctx.fillStyle = 'rgba(0,0,0,0.9)';
      ctx.fillRect(0, 0, W, 200);
      ctx.fillStyle = '#7dff9a';
      ctx.font = 'bold 14px Consolas, monospace';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      const lines = (e && e.stack) ? String(e.stack).split('\n') : [String(e)];
      lines.slice(0, 7).forEach((ln, i) => {
        ctx.fillText(ln.trim().slice(0, 76), 10, 14 + i * 26);
      });
    } catch (x) {}
  }
  requestAnimationFrame(loop);
}

/* 宠物切换（主菜单按钮）：小鸡 → 奶龙 → 休息 → 循环 */
function togglePet() {
  G.petIdx = G.petIdx + 1 >= PETS.length ? -1 : G.petIdx + 1;
  try { localStorage.setItem('jiling_petIdx', String(G.petIdx)); } catch (e) {}
  Snd.click();
  const msg = G.petIdx < 0 ? '宠物回家睡觉' : PETS[G.petIdx].name + ' 出战！';
  FX.float(W / 2, 640, msg, { color: '#ffd34d', size: 26 });
}

/* 追击者切换（主菜单按钮，局外生效）：蚩尤 ↔ 忍者阿坚，BOSS 战同步换人 */
function cycleChaser() {
  const cur = chaserSkin();
  const next = cur === 'aj' ? 'chiyou' : 'aj';
  try { localStorage.setItem('jiling_chaser', next); } catch (e) {}
  Snd.click();
  FX.float(W / 2, 640, next === 'aj' ? '忍者阿坚 接替追击！' : '蚩尤 重新出战！', { color: next === 'aj' ? '#d3a8ff' : '#ff9a8a', size: 26 });
}

/* 宠物绘制：跟在玩家头顶右上，扑翅浮动，挡刀冷却时打盹 */
function drawPet() {
  if (!petEnabled() || !(G.state === 'playing' || G.state === 'paused' || G.state === 'menu')) return;
  const im = petCur();
  const h = 76;
  const w = h * im.width / im.height;
  const bob = Math.sin(G.time * 3.2) * 9;
  const flap = Math.sin(G.time * 9) * 0.15;
  const sleepy = (G.petCd || 0) > 0;
  ctx.save();
  ctx.translate(PLAYER_X + 30, player.y - 236 + bob);
  ctx.rotate(flap);
  ctx.globalAlpha = 0.2 + 0.1 * Math.sin(G.time * 4);
  ctx.beginPath();
  ctx.arc(0, 0, h * 0.64, 0, Math.PI * 2);
  ctx.fillStyle = sleepy ? 'rgba(160,175,205,0.6)' : '#ffe066';
  ctx.fill();
  ctx.globalAlpha = 1;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(im, -w / 2, -h / 2, w, h);
  ctx.restore();
  // 挡刀冷却：头顶画个打盹气泡
  if (sleepy) {
    pxText('zZ', PLAYER_X + 52, player.y - 262 + bob, 15, 'rgba(220,232,250,0.85)', 'rgba(10,18,32,0.8)');
  }
}

// ---- 启动 ----
loadAssets()
  .then(loadSkinSprites)
  .then(loadBossSprites)
  .then(loadPetSprite)
  .then(() => {
    const el = document.getElementById('loading');
    if (el) el.style.display = 'none';
    resize();
    clearWorld();
    buildSkinSprites();
    G.state = 'menu';
    // 全局背景 + 独立 mod 库：启动即预载，保证无尽模式开局就能挂上；
    // 预载完成后做菜单探测（mod 注册的模式入口/菜单按钮/菜单钩子在主菜单可见）
    // 注意：仅在仍是主菜单时探测——demo/ugcplay 等调试分支会立即开局，不能被 probe 的 clearWorld 清场
    loadGlobalBg();
    loadGlobalTex();
    ugcModsAll().catch(() => {}).then(() => { try { if (G.state === 'menu') ugcMenuProbe(); } catch (e) {} });
    // 自定义机灵皮肤：启动即装载进 SKINS（异步补装，装完按存档 id 恢复选中）
    ugcSkinsAll().catch(() => {}).then(list => installCustomSkins(list).catch(() => {}));
    try {
      const qs = new URLSearchParams(location.search);
      if (qs.get('autostart') === '1') {
        startEndless();
        const at = +qs.get('at');
        if (at > 0) {
          world.x = at;
          world.speed = Math.min(MAX_SPEED, BASE_SPEED + world.x * 0.0075);
          world.genCursor = at - 400;
          world.gaps = []; world.coins = []; world.signs = []; world.platforms = [];
          world.fakeFloors = []; world.spikeTraps = []; world.lowBars = [];
          world.ceilSpikes = []; world.bouncePads = []; world.movingPlatforms = [];
          world.pendulums = []; world.rollingRocks = []; world.fallingRocks = [];
          world.boosters = []; world.fakeCoins = [];
          G.baseSpeed = world.speed;
          generateAhead();
        }
        const lv = +qs.get('level');
        if (qs.get('level') !== null && !isNaN(lv)) startLevel(clamp(lv, 0, LEVELS.length - 1));
        const lx = +qs.get('lx');
        if (lx > 0) world.x = lx;
        const gt = +qs.get('gt');
        if (gt > 0) G.time = gt;
        const pose = qs.get('pose');
        if (pose === 'jump') {
          player.y = GROUND_Y - 120;
          player.vy = -120;
          player.onGround = false;
          player.spinOn = true;
          player.spinT = 0.2;
        } else if (pose === 'slide') {
          player.sliding = true;
          player.slidingT = 999;
        }
        // 调试：?boss=1 直接拉一场蚩尤追逐战（&ajboss=1 换阿坚影分身战）
        if (qs.get('boss') === '1') {
          world.x = 395 * PX_PER_M;
          world.speed = Math.min(MAX_SPEED, BASE_SPEED + world.x * 0.0075);
          world.genCursor = world.x - 400;
          G.baseSpeed = world.speed;
          generateAhead();
          if (qs.get('ajboss') === '1') G.chaserOverride = 'aj';
          startBoss();
        }
        // 调试：?ptrtest=1 验证多指按压状态机
        if (qs.get('ptrtest') === '1') {
          const log = [];
          activePtr.set(1, 'jump'); refreshHolds();
          log.push('一指跳:' + G.holdJump + '/' + G.holdSlide);
          activePtr.set(2, 'slide'); refreshHolds();
          log.push('加二指滑:' + G.holdJump + '/' + G.holdSlide);
          activePtr.delete(1); refreshHolds();
          log.push('松一指:' + G.holdJump + '/' + G.holdSlide);
          activePtr.delete(2); refreshHolds();
          log.push('全松:' + G.holdJump + '/' + G.holdSlide);
          // 矮跳判定：按住下滑时起跳
          G.state = 'playing';
          G.holdSlide = true;
          player.onGround = true; player.y = GROUND_Y; player.vy = 0;
          doJump();
          log.push('矮跳速度:' + Math.round(player.vy) + '(满跳' + Math.round(JUMP_V) + ')');
          window.__ptrtest = log.join(' | ');
          G.state = 'menu';
        }
        // 调试：?en=<数值> 直接设定冲刺能量
        const en0 = +qs.get('en');
        if (en0 > 0) G.energy = clamp(en0, 0, ENERGY_MAX);
        // 调试：?obj=<目标id> 强制指定本关通关目标
        const fo = qs.get('obj');
        if (fo && G.mode === 'level') {
          const d = levelData(G.levelIndex);
          const O = OBJECTIVES.find(o => o.id === fo);
          if (O) {
            d.objective = {
              id: O.id, name: O.name, color: O.color,
              data: O.roll({
                idx: G.levelIndex, coinTotal: d.coinTotal,
                crates: d.shell.crates.length, pads: d.shell.bouncePads.length,
                lenPx: d.endX, speed: d.baseSpeed,
              }),
            };
            G.objective = d.objective;
          }
        }
        const sim = +qs.get('sim');
        if (sim > 0) debugSim(sim);
      } else if (qs.get('demo') !== null) {
        // 调试：?demo=<秒> 或 ?demo=<秒>,<宠物序号> 开一局无尽并快进
        // &modload=1 先把示例 mod 包装进机关mod库，验证无尽挂 mod 链路
        const dv = qs.get('demo').split(',');
        const bootMod = () => {
          const pack = ugcModSample();
          if (pack && pack.ok) return Promise.all(pack.mods.map(m => ugcModSave({ ...m, createdAt: Date.now() })));
          return Promise.resolve();
        };
        const goDemo = () => {
          startEndless();
          if (qs.get('aj') === '1') G.chaserOverride = 'aj';   // 调试：忍者阿坚追击 + BOSS 战也换阿坚
          const cg = +qs.get('cygap');
          if (cg > 0) world.cyTune = { gap: cg };   // 调试：强制蚩尤贴脸跟随，验证停等规则
          if (qs.get('modsample') === '1') {        // 调试：同步注入示例 mod（不查库，截图验证按钮/机关渲染用）
            const pk = ugcModSample();
            world.ugcMods = (pk && pk.ok ? pk.mods : []).map(m => ({ id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0, slow: false, slowCd: 0 }));
            for (const mod of world.ugcMods) { compileUgcMod(mod); runUgcFn(mod, 'onSpawn', mod._spawnFn); }
          }
          if (dv[1] !== undefined) G.petIdx = clamp(+dv[1] || 0, 0, PETS.length - 1);
          debugSim(Math.max(1, +dv[0] || 19));
          if (world.ugcMods) document.title += ' mods=' + world.ugcMods.length;
        };
        // 统一等 mod 预载完成再开局（保证 demo 截图/快进也能挂上已装的 mod）
        const modsReady = qs.get('modload') === '1' ? bootMod() : ugcModsAll().catch(() => {});
        modsReady.then(goDemo).catch(goDemo);
      } else if (qs.get('ugc') === '1') {
        // 调试：注入内置示例项目并打开创造中心（含自定义机关与自定义背景）
        const sample = ugcSample();
        if (sample) ugcSave(sample).then(() => openCreative()).catch(() => openCreative());
        else openCreative();
      } else if (qs.get('ugcplay') === '1') {
        // 调试：直接试玩内置示例项目（跑到终点自动结算星级）
        const sample = ugcSample();
        if (qs.get('noblade') === '1' && sample) sample.mods = [];   // 调试：去刀锋，验证跑通终点结算
        const go = () => { if (sample) startUgcLevel(sample); };
        const sim = +qs.get('sim') || 0;
        if (sim > 0) { go(); debugSim(sim); }
        else go();
      } else if (qs.get('menudemo') === '1') {
        // 调试：同步注册示例 mod 的菜单能力（api.mode 模式入口 / api.menuButton 按钮 / api.on('menu') 钩子），
        // 验证主菜单渲染，不等 IDB 预载（headless 截图模式等不了任何真实 IO）
        const pk = ugcModSample();
        if (pk && pk.ok) {
          for (const m of pk.mods) {
            const mod = { id: m.id, name: m.name, onSpawn: m.onSpawn, onUpdate: m.onUpdate, img: m.img, err: 0 };
            compileUgcMod(mod);
            try { if (mod._spawnFn) mod._spawnFn(buildUgcApi(mod)); } catch (e) {}
          }
        }
        document.title += ' menu=' + (typeof UGC_MODES !== 'undefined' ? UGC_MODES.length : '?') + ' mbtn=' + UGC_MENU_BTNS.length;
      } else if (qs.get('levels') === '1') {
        openLevelSelect();
      } else if (qs.get('skins') === '1') {
        openSkinSelect();
      }
      // 调试：?skin=<皮肤id> 直接套上某套皮肤看效果
      const skId = qs.get('skin');
      if (skId) {
        const i = SKINS.findIndex(x => x.id === skId);
        if (i >= 0) setSkin(i);
      }
    } catch (e) { try { document.title = 'ERR ' + e.message + ' | ' + String(e.stack || '').split('\n').slice(1, 3).join(' <= ').slice(0, 300); } catch (x) {} }
    requestAnimationFrame(loop);
  })
  .catch(err => {
    const el = document.getElementById('loading');
    if (el) el.textContent = '素材加载失败：' + err.message;
  });

/* ---------------- UGC 交互：剪贴板 / DOM 弹窗 / 导入 / 分享 / 删除 / 素材 ---------------- */
let ugcEditTap = null;   // 素材管理里点击热区坐标（canvas 逻辑坐标）
let ugcTapT = 0;
let ugcPendingDel = null;

/* DOM 顶层 toast：弹窗（DOM 遮罩）盖住画布时，画布飘字看不见，用这个做明显反馈 */
function ugcToast(msg, color) {
  const t = document.createElement('div');
  t.textContent = msg;
  t.style.cssText = 'position:fixed;left:50%;top:13%;transform:translateX(-50%);background:rgba(10,18,32,0.94);'
    + 'border:2px solid ' + (color || '#7de08a') + ';color:' + (color || '#7de08a') + ';'
    + 'font-size:21px;font-weight:bold;padding:13px 30px;border-radius:12px;z-index:99999;'
    + 'pointer-events:none;box-shadow:0 6px 24px rgba(0,0,0,0.45);transition:opacity .45s;';
  document.body.appendChild(t);
  setTimeout(() => { t.style.opacity = '0'; }, 1500);
  setTimeout(() => t.remove(), 2050);
}

/* 复制文本：优先异步剪贴板，回退 execCommand，都失败返回 false */
function copyText(txt) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(txt);
      return true;
    }
  } catch (e) {}
  try {
    const ta = document.createElement('textarea');
    ta.value = txt;
    ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0.01;';
    document.body.appendChild(ta);
    ta.focus(); ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok === true;
  } catch (e) { return false; }
}

/* 通用弹窗层：覆盖 canvas，隔离点击 */
let ugcOverlayEl = null;
const UGC_OW = 'position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:99;display:flex;align-items:center;justify-content:center;background:rgba(6,10,20,0.85);font-family:"Microsoft YaHei","PingFang SC",sans-serif;';
const UGC_BOX = 'width:min(92vw,540px);max-height:88vh;overflow:auto;background:#1b2436;border:2px solid #3d4f6d;border-radius:16px;padding:22px;box-shadow:0 18px 60px rgba(0,0,0,0.55);';
const UGC_TA = 'width:100%;box-sizing:border-box;height:220px;resize:vertical;color:#dbe7f8;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px;font:14px/1.6 Consolas,monospace;';
const UGC_BTN = 'display:inline-block;margin:14px 8px 0 0;padding:10px 22px;border:0;border-radius:10px;font-size:16px;cursor:pointer;color:#fff;';
function ugcOverlayShow(boxHtml) {
  if (!ugcOverlayEl) {
    ugcOverlayEl = document.createElement('div');
    ugcOverlayEl.id = 'ugc-overlay';
    ugcOverlayEl.style.cssText = UGC_OW;
    document.body.appendChild(ugcOverlayEl);
    ugcOverlayEl.addEventListener('pointerdown', e => e.stopPropagation());
  }
  // 兼容两种入参：DOM 元素直接挂载；字符串按 HTML 渲染
  if (typeof boxHtml === 'string') ugcOverlayEl.innerHTML = boxHtml;
  else {
    ugcOverlayEl.innerHTML = '';
    ugcOverlayEl.appendChild(boxHtml);
  }
  ugcOverlayEl.style.display = 'flex';
  return ugcOverlayEl;
}
function ugcOverlayHide() {
  if (ugcOverlayEl) ugcOverlayEl.style.display = 'none';
}

/* ① 复制提示词：弹出预览（自动全选 + 尝试自动复制） */
function copyPrompt(diff) {
  const txt = ugcPrompt(diff);
  const ok0 = copyText(txt);
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:' + (ok0 ? '#7de08a' : '#ffd34d') + ';font-size:22px;font-weight:bold;margin-bottom:6px;">'
    + (ok0 ? '✓ 提示词已自动复制到剪贴板' : '未能自动复制 —— 请在框内按 Ctrl+C') + '</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">↓ 粘贴给任意 AI；把它返回的 JSON 粘回「粘贴 AI 结果」即可生成关卡</div>' +
    '<textarea id="ugc-cp" rows="14" spellcheck="false" style="' + UGC_TA + 'height:46vh;"></textarea>' +
    '<button id="ugc-cp-copy" style="background:#3d4f6d;' + UGC_BTN + '">重新复制</button>' +
    '<button id="ugc-cp-close" style="background:#223046;' + UGC_BTN + '">完成</button>';
  const ta = box.querySelector('#ugc-cp');
  ta.value = txt;
  box.querySelector('#ugc-cp-copy').onclick = () => {
    ta.focus(); ta.select();
    const ok = copyText(ta.value);
    box.querySelector('#ugc-cp-copy').textContent = ok ? '已复制 ✓' : '手动 Ctrl+C';
    if (ok) ugcToast('✓ 已复制');
  };
  box.querySelector('#ugc-cp-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => { ta.focus(); ta.select(); }, 60);
  if (ok0) ugcToast('✓ 提示词已复制，粘贴给 AI 就能生成');
}

/* mod 提示词复制：与 copyPrompt 同款弹窗，内容换成机关 mod 提示词 */
function copyModPrompt() {
  const txt = ugcModPrompt();
  const ok0 = copyText(txt);
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:' + (ok0 ? '#7de08a' : '#ffd34d') + ';font-size:22px;font-weight:bold;margin-bottom:6px;">'
    + (ok0 ? '✓ mod 提示词已自动复制到剪贴板' : '未能自动复制 —— 请在框内按 Ctrl+C') + '</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">↓ 粘贴给任意 AI；把它返回的 mod JSON 粘回「粘贴 mod 导入」即可装进 mod 库</div>' +
    '<textarea id="mod-cp" rows="14" spellcheck="false" style="' + UGC_TA + 'height:46vh;"></textarea>' +
    '<button id="mod-cp-copy" style="background:#3d4f6d;' + UGC_BTN + '">重新复制</button>' +
    '<button id="mod-cp-close" style="background:#223046;' + UGC_BTN + '">完成</button>';
  const ta = box.querySelector('#mod-cp');
  ta.value = txt;
  box.querySelector('#mod-cp-copy').onclick = () => {
    ta.focus(); ta.select();
    const ok = copyText(ta.value);
    box.querySelector('#mod-cp-copy').textContent = ok ? '已复制 ✓' : '手动 Ctrl+C';
    if (ok) ugcToast('✓ 已复制');
  };
  box.querySelector('#mod-cp-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => { ta.focus(); ta.select(); }, 60);
  if (ok0) ugcToast('✓ mod 提示词已复制，粘贴给 AI 就能生成');
}

/* 载入官方示例 mod 包（创造中心主界面按钮用，成功留在主界面只弹 toast） */
function ugcModDemoLoad() {
  const pack = ugcModSample();
  if (!pack || !pack.ok) { ugcToast('示例包校验失败', '#ff8f7e'); return; }
  Promise.all(pack.mods.map(m => ugcModSave({ ...m, createdAt: Date.now() })))
    .then(() => ugcToast('✓ 示例包已载入 ×' + pack.mods.length + '，无尽模式生效'))
    .catch(() => ugcToast('保存失败', '#ff8f7e'));
}

/* ② 粘贴 AI 结果 / ③ 粘贴分享码：弹输入框 */
function ugcImportUI(isShare) {
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#ffd34d;font-size:22px;font-weight:bold;margin-bottom:6px;">' + (isShare ? '粘贴分享码' : '粘贴 AI 生成的关卡') + '</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">' +
    (isShare ? '把别人发的 UGC1: 全下来粘贴进来，会自动存为你自己的新作品。' : '把 AI 返回的 JSON 整个粘贴进来（带 ``` 代码块也没关系）。') +
    '</div>' +
    '<textarea id="ugc-imp" rows="12" spellcheck="false" placeholder="' + (isShare ? 'UGC1:xxxx...' : '{"title":"我的关卡","lenM":300,...}') + '" style="' + UGC_TA + '"></textarea>' +
    '<div id="ugc-imp-err" style="color:#ff8f7e;font-size:14px;line-height:1.6;margin-top:8px;white-space:pre-wrap;"></div>' +
    '<button id="ugc-imp-ok" style="background:#c9962a;' + UGC_BTN + '">校验并保存</button>' +
    '<button id="ugc-imp-cancel" style="background:#223046;' + UGC_BTN + '">取消</button>';
  const ta = box.querySelector('#ugc-imp');
  const err = box.querySelector('#ugc-imp-err');
  box.querySelector('#ugc-imp-ok').onclick = () => {
    err.textContent = '';
    const text = (ta.value || '').trim();
    if (!text) { err.textContent = '内容为空，粘贴后再点保存'; return; }
    let raw = text;
    if (isShare) {
      const d = shareDecode(text);
      if (!d.ok) { err.textContent = d.error; return; }
      raw = d.raw;
    }
    const r = verifyUgc(raw);
    if (!r.ok) {
      err.textContent = r.errors.map(e => '• ' + e.where + '：' + e.msg).join('\n');
      return;
    }
    const it = r.item;
    // 导入/粘贴一律存为新作品，避免覆盖来源项目
    it.id = ugcUid();
    it.createdAt = Date.now();
    it.ratingStrs = { reach: 0, noHit: 0, coin70: 0 };
    ugcSave(it).then(() => {
      // 软提示（mod 语法错被丢弃、超大图被丢弃等）随成功一起展示
      if (r.warnings && r.warnings.length) {
        err.style.color = '#ffd34d';
        err.textContent = '已保存，但有几处自动处理：\n' + r.warnings.map(e => '• ' + e.where + '：' + e.msg).join('\n');
        setTimeout(ugcOverlayHide, 3600);
        return;
      }
      ugcOverlayHide();
      G.state = 'creative';
      G.pressed = {};
      FX.sfloat(W / 2, 240, '创造完成！', { color: '#ffd34d', size: 44, life: 1.5 });
      FX.ring(W / 2, 300, { r0: 20, r1: 200, max: 0.5, color: 'rgba(255,211,77,0.8)', width: 6 });
    }).catch(() => { err.textContent = '保存失败（可能图片过大或配额不足）'; });
  };
  box.querySelector('#ugc-imp-cancel').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => ta.focus(), 60);
}

/* 分享码弹窗 */
function showUgcShare(it) {
  const res = shareEncodeFmt(it);
  if (!res.code) { FX.sfloat(W / 2, 260, '生成失败', { color: '#ff8f7e', size: 30 }); return; }
  const big = res.mb > 0.5;
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">分享「' + (it.title || '我的创造') + '」</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">' +
    (big ? '这份码含图片，比较长（约 ' + res.mb.toFixed(1) + 'MB），适合用电脑端聊天软件/网盘发送。' : '把这个码发给你朋友，他粘贴到「粘贴分享码」就能玩。') +
    '</div>' +
    '<textarea id="ugc-sh" rows="8" readonly spellcheck="false" style="' + UGC_TA + 'height:30vh;"></textarea>' +
    '<button id="ugc-sh-copy" style="background:#3d4f6d;' + UGC_BTN + '">复制码</button>' +
    '<button id="ugc-sh-close" style="background:#223046;' + UGC_BTN + '">关闭</button>';
  const ta = box.querySelector('#ugc-sh');
  ta.value = res.code;
  box.querySelector('#ugc-sh-copy').onclick = () => {
    ta.focus(); ta.select();
    const ok = copyText(ta.value);
    box.querySelector('#ugc-sh-copy').textContent = ok ? '已复制 ✓' : '手动 Ctrl+C';
  };
  box.querySelector('#ugc-sh-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
}

/* mod 包分享码弹窗（MOD1: 前缀，粘贴导入时自动识别） */
function showModShare(name, mods) {
  const code = modShareEncode({ name: name || '我的mod包', mods });
  if (!code) { FX.sfloat(W / 2, 260, '生成失败', { color: '#ff8f7e', size: 30 }); return; }
  const big = code.length > 700000;
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">分享 mod「' + (name || '我的mod包') + '」</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">' +
    (big ? '这份码比较长（含贴图），建议用电脑端聊天软件/网盘发送。' : '把这个码发给你朋友，他在「机关mod库 → 粘贴导入」粘贴即可安装。') +
    '</div>' +
    '<textarea id="mod-sh" rows="8" readonly spellcheck="false" style="' + UGC_TA + 'height:30vh;"></textarea>' +
    '<button id="mod-sh-copy" style="background:#3d4f6d;' + UGC_BTN + '">复制码</button>' +
    '<button id="mod-sh-close" style="background:#223046;' + UGC_BTN + '">关闭</button>';
  const ta = box.querySelector('#mod-sh');
  ta.value = code;
  box.querySelector('#mod-sh-copy').onclick = () => {
    ta.focus(); ta.select();
    const ok = copyText(ta.value);
    box.querySelector('#mod-sh-copy').textContent = ok ? '已复制 ✓' : '手动 Ctrl+C';
  };
  box.querySelector('#mod-sh-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
}

/* 删除：两段确认 */
function ugcDeleteAsk(it) {
  if (ugcPendingDel !== it.id) {
    ugcPendingDel = it.id;
    FX.sfloat(W / 2, 250, '再点一次「删」确认删除', { color: '#ff8f7e', size: 24, life: 1.6 });
    setTimeout(() => { if (ugcPendingDel === it.id) ugcPendingDel = null; }, 3000);
    return;
  }
  ugcPendingDel = null;
  ugcDelete(it.id).then(() => {
    FX.sfloat(W / 2, 250, '已删除', { color: '#ff8f7e', size: 30, life: 1.2 });
  });
}

/* 素材管理 */
function openUgceEdit(id) {
  G.ugcEditId = id;
  G.state = 'ugcedit';
  G.pressed = {};
}

function ugcSaveAsset(it) {
  return ugcSave(it).then(() => { ugcListSync(); });
}
function ugcPickFile(it) {
  Snd.click();
  const inp = document.createElement('input');
  inp.type = 'file';
  inp.accept = 'image/png,image/jpeg,image/webp,image/gif';
  inp.onchange = () => {
    const f = inp.files && inp.files[0];
    if (!f) return;
    if (f.size > UGC_MAX_ASSET_MB * 1024 * 1024) {
      FX.sfloat(W / 2, 250, '图片超过 ' + UGC_MAX_ASSET_MB + 'MB', { color: '#ff8f7e', size: 26, life: 1.6 });
      return;
    }
    const rd = new FileReader();
    rd.onload = () => {
      const id = 'a' + Date.now().toString(36);
      it.assets = it.assets || [];
      it.assets.push({ id, name: f.name.replace(/\.[a-z0-9]+$/i, '').slice(0, 12), src: String(rd.result), srcType: 'data' });
      ugcSaveAsset(it).then(() => {
        FX.sfloat(W / 2, 250, '已上传，可右上「设为背景」', { color: '#7de08a', size: 24, life: 1.8 });
      });
    };
    rd.readAsDataURL(f);
  };
  inp.click();
}
function ugcAskUrl(it) {
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">添加网络图片</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">粘贴 http(s) 图片链接（离线时会加载失败并回退默认素材）</div>' +
    '<input id="ugc-url" type="text" placeholder="https://example.com/bg.png" style="width:100%;box-sizing:border-box;color:#dbe7f8;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px;font-size:15px;">' +
    '<button id="ugc-url-ok" style="background:#3d4f6d;' + UGC_BTN + '">添加</button>' +
    '<button id="ugc-url-close" style="background:#223046;' + UGC_BTN + '">取消</button>';
  const inp = box.querySelector('#ugc-url');
  box.querySelector('#ugc-url-ok').onclick = () => {
    const url = (inp.value || '').trim();
    if (!/^https?:\/\//i.test(url)) { FX.sfloat(W / 2, 250, '请输入 http(s) 链接', { color: '#ff8f7e', size: 24 }); return; }
    it.assets = it.assets || [];
    it.assets.push({ id: 'a' + Date.now().toString(36), name: '网络图' + (it.assets.length + 1), src: url, srcType: 'url' });
    ugcSaveAsset(it).then(() => { ugcOverlayHide(); });
  };
  box.querySelector('#ugc-url-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => inp.focus(), 60);
}
function ugcSetBg(it, assetId) {
  it.bgAsset = assetId;
  preloadUgcAssets(it);
  ugcSaveAsset(it).then(() => FX.sfloat(W / 2, 250, '已成为关卡背景', { color: '#ffd34d', size: 24, life: 1.6 }));
}

/* ---------- 自定义机灵皮肤：上传图片做皮肤（多个可切换，IndexedDB 持久化） ---------- */
let skinPendingDel = null;   // 两段删除确认

/* 动作图压缩：最长边 ≤512，保透明（PNG）。玩家原图可能 4K，直接存会爆库 */
function skinShrink(dataURL, cb) {
  const im = new Image();
  im.onload = () => {
    const scale = Math.min(1, 512 / Math.max(im.width, im.height));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(im.width * scale));
    c.height = Math.max(1, Math.round(im.height * scale));
    const g = c.getContext('2d');
    g.imageSmoothingEnabled = true;
    g.drawImage(im, 0, 0, c.width, c.height);
    cb(c.toDataURL('image/png'));
  };
  im.onerror = () => cb(dataURL);   // 解不了就原样交上去（verify 还会拦体积）
  im.src = dataURL;
}

function openSkinAddUI() {
  if (ugcSkinsSync().length >= UGC_MAX_SKINS) {
    FX.sfloat(W / 2, 250, '自定义皮肤最多 ' + UGC_MAX_SKINS + ' 套，先删一套再添加', { color: '#ff8f7e', size: 24, life: 1.8 });
    Snd.back();
    return;
  }
  Snd.click();
  const draft = { name: '', frames: {} };
  const KEYS = [['run', '跑步'], ['run2', '二段跑'], ['slide', '滑铲'], ['idle', '待机']];
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#7de08a;font-size:22px;font-weight:bold;margin-bottom:6px;">添加自定义机灵皮肤</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">至少上传 1 张动作图（跑步即可用）；缺的动作自动用原版染色补齐。推荐透明底 PNG，上传后自动压缩到 512px。</div>' +
    '<input id="sk-name" type="text" maxlength="12" placeholder="皮肤名字（最多 12 字）" style="width:100%;box-sizing:border-box;color:#dbe7f8;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px;font-size:15px;margin-bottom:10px;">' +
    KEYS.map(([k, zh]) =>
      '<div style="display:flex;align-items:center;margin-bottom:8px;">' +
      '<span style="width:76px;color:#b9cbe8;font-size:15px;">' + zh + '</span>' +
      '<button id="sk-f-' + k + '" style="flex:1;background:#3d4f6d;color:#dbe7f8;border:0;border-radius:8px;padding:8px;font-size:14px;cursor:pointer;">选择图片</button>' +
      '</div>').join('') +
    '<button id="sk-ok" style="background:#2c7a52;' + UGC_BTN + '">保存并穿上</button>' +
    '<button id="sk-cancel" style="background:#223046;' + UGC_BTN + '">取消</button>';
  const nameInp = box.querySelector('#sk-name');
  const stat = k => box.querySelector('#sk-f-' + k);
  const refresh = () => {
    for (const [k] of KEYS) {
      stat(k).textContent = draft.frames[k] ? '已选 ✓（点此重选）' : '选择图片';
      stat(k).style.background = draft.frames[k] ? '#2c7a52' : '#3d4f6d';
    }
  };
  for (const [k] of KEYS) {
    stat(k).onclick = () => {
      const inp = document.createElement('input');
      inp.type = 'file';
      inp.accept = 'image/png,image/jpeg,image/webp';
      inp.onchange = () => {
        const f = inp.files && inp.files[0];
        if (!f) return;
        if (f.size > UGC_MAX_ASSET_MB * 1024 * 1024) {
          FX.sfloat(W / 2, 250, '图片超过 ' + UGC_MAX_ASSET_MB + 'MB', { color: '#ff8f7e', size: 26, life: 1.6 });
          return;
        }
        const rd = new FileReader();
        rd.onload = () => skinShrink(String(rd.result), d => { draft.frames[k] = d; refresh(); });
        rd.readAsDataURL(f);
      };
      inp.click();
    };
  }
  box.querySelector('#sk-ok').onclick = () => {
    draft.name = (nameInp.value || '').trim();
    const v = verifyUgcSkin(draft);
    if (!v.ok) { FX.sfloat(W / 2, 250, v.errors, { color: '#ff8f7e', size: 24, life: 2 }); return; }
    ugcSkinSave(v.skin).then(() => installCustomSkins(ugcSkinsSync())).then(() => {
      const i = SKINS.findIndex(x => x.rawId === v.skin.id);
      if (i >= 0) setSkin(i);
      ugcOverlayHide();
      FX.sfloat(W / 2, 250, '自定义皮肤「' + v.skin.name + '」已穿上！', { color: '#7de08a', size: 28, life: 2 });
      Snd.clear();
    }).catch(() => FX.sfloat(W / 2, 250, '保存失败，重试一次', { color: '#ff8f7e', size: 24 }));
  };
  box.querySelector('#sk-cancel').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => nameInp.focus(), 60);
}

function tryDeleteCustomSkin(i) {
  Snd.click();
  const sk = SKINS[i];
  if (!sk || !sk.custom) return;
  if (skinPendingDel !== sk.rawId) {
    skinPendingDel = sk.rawId;
    FX.sfloat(W / 2, 250, '再点一次「删」确认删除「' + sk.name + '」', { color: '#ff8f7e', size: 24, life: 2 });
    setTimeout(() => { if (skinPendingDel === sk.rawId) skinPendingDel = null; }, 3000);
    return;
  }
  skinPendingDel = null;
  if (G.skinIdx === i) setSkin(0);   // 删的是正在用的 → 回原色
  ugcSkinDelete(sk.rawId).then(() => installCustomSkins(ugcSkinsSync())).then(() => {
    FX.sfloat(W / 2, 250, '已删除', { color: '#ff8f7e', size: 28, life: 1.4 });
  });
}

/* 机关mod库：无尽模式的机制/陷阱/界面 mod 管理（独立于关卡作品） */
const UGC_TAG_CH = { trap: '机关', mech: '机制', ui: '界面', fun: '整活' };
function ugcModsUI() {
  Snd.click();
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  const mods = ugcModsSync();
  const rows = mods.map(m =>
    '<div style="display:flex;align-items:center;gap:10px;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px 12px;margin-top:8px;">' +
    '<div style="flex:1;min-width:0;">' +
    '<div style="color:#dbe7f8;font-size:16px;font-weight:bold;">' + (m.name || m.id) +
    ' <span style="color:#8fa6c8;font-size:12px;font-weight:normal;">[' + (UGC_TAG_CH[m.tag] || '机关') + ']</span></div>' +
    '<div style="color:#8fa6c8;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + (m.desc || '无描述') + '</div>' +
    '</div>' +
    '<button data-share="' + m.id + '" style="background:#1d3a2c;border:1px solid #2f6a4a;color:#8ce8b0;border-radius:8px;padding:6px 12px;font-size:13px;cursor:pointer;">码</button>' +
    '<button data-del="' + m.id + '" style="background:#3a2330;border:1px solid #7e3a4a;color:#ff9a9a;border-radius:8px;padding:6px 12px;font-size:13px;cursor:pointer;">删</button>' +
    '</div>'
  ).join('');
  box.innerHTML =
    '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">机关 mod 库</div>' +
    '<div style="color:#8fa6c8;font-size:14px;line-height:1.6;">这里的 mod 在<b style="color:#dbe7f8;">无尽模式全局生效</b>（机关陷阱、玩家机制、蚩尤机制、自定义 UI 等），与关卡作品里的 mod 互不重合。复制提示词 / 粘贴导入 / 载入示例包在创造中心主界面，这里管理已有 mod：分享给朋友或删除。</div>' +
    '<div id="mod-list" style="margin-top:10px;max-height:44vh;overflow:auto;">' + (rows || '<div style="color:#8fa6c8;font-size:14px;margin-top:12px;">还没有 mod。回创造中心「载入示例包」或「复制 mod 提示词」开始。</div>') + '</div>' +
    '<button id="mod-share" style="background:#1d3a2c;' + UGC_BTN + '">分享全部</button>' +
    '<button id="mod-close" style="background:#223046;' + UGC_BTN + '">完成</button>';
  box.querySelector('#mod-share').onclick = () => {
    const ms = ugcModsSync();
    if (!ms.length) { ugcToast('还没有可分享的 mod', '#ff8f7e'); return; }
    showModShare('我的mod包', ms);
  };
  box.querySelector('#mod-close').onclick = () => ugcOverlayHide();
  box.querySelectorAll('[data-share]').forEach(b => {
    b.onclick = () => {
      const m = ugcModsSync().find(x => x.id === b.getAttribute('data-share'));
      if (m) showModShare(m.name || m.id, [m]);
    };
  });
  box.querySelectorAll('[data-del]').forEach(b => {
    b.onclick = () => {
      ugcModDelete(b.getAttribute('data-del')).then(() => { ugcToast('已删除'); ugcModsUI(); });
    };
  });
  ugcOverlayShow(box);
}

/* 粘贴导入 mod 包 */
function ugcModImportUI() {
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#ffd34d;font-size:22px;font-weight:bold;margin-bottom:6px;">粘贴 AI 生成的 mod 包</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">把 AI 返回的 JSON 整个粘进来（带 ``` 代码块也没关系），也支持朋友分享的 MOD1: 分享码。校验通过后装进机关mod库，无尽模式立即生效。</div>' +
    '<textarea id="mod-imp-ta" rows="12" spellcheck="false" placeholder=\'{"name":"我的mod包","mods":[{"id":"fireball","name":"火焰弹","onUpdate":"..."}]}\' style="' + UGC_TA + '"></textarea>' +
    '<div id="mod-imp-err" style="color:#ff8f7e;font-size:14px;line-height:1.6;margin-top:8px;white-space:pre-wrap;"></div>' +
    '<button id="mod-imp-ok" style="background:#c9962a;' + UGC_BTN + '">校验并安装</button>' +
    '<button id="mod-imp-cancel" style="background:#223046;' + UGC_BTN + '">取消</button>';
  const ta = box.querySelector('#mod-imp-ta');
  const err = box.querySelector('#mod-imp-err');
  box.querySelector('#mod-imp-ok').onclick = () => {
    err.style.color = '#ff8f7e';
    err.textContent = '';
    let txt = (ta.value || '').trim();
    if (txt.indexOf('MOD1:') === 0) {
      const d = modShareDecode(txt);
      if (!d.ok) { err.textContent = d.error; return; }
      txt = d.raw;
    }
    const r = verifyModsPack(txt);
    if (!r.ok) {
      err.textContent = r.errors.map(e => '• ' + e.where + '：' + e.msg).join('\n');
      return;
    }
    Promise.all(r.mods.map(m => ugcModSave({ ...m, createdAt: Date.now() })))
      .then(() => {
        if (r.warnings && r.warnings.length) {
          err.style.color = '#ffd34d';
          err.textContent = '已安装 ' + r.mods.length + ' 个 mod，但有几处自动处理：\n' + r.warnings.map(e => '• ' + e.where + '：' + e.msg).join('\n');
          setTimeout(ugcOverlayHide, 3200);
          return;
        }
        ugcOverlayHide();
        ugcToast('✓ mod 已安装 ×' + r.mods.length + '，无尽模式生效', '#7de08a');
        FX.sfloat(W / 2, 250, 'mod 已安装 ×' + r.mods.length, { color: '#7de08a', size: 28, life: 1.5 });
      })
      .catch(() => { err.textContent = '保存失败（可能图片过大或配额不足）'; });
  };
  box.querySelector('#mod-imp-cancel').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
  setTimeout(() => ta.focus(), 60);
}

/* 全局背景：所有模式通用的背景图（单关背景/mod api.bg 优先于它） */
function ugcGlobalBgUI() {
  Snd.click();
  const cur = globalBgGet();
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#ffd34d;font-size:22px;font-weight:bold;margin-bottom:6px;">全局背景</div>' +
    '<div style="color:#8fa6c8;font-size:14px;line-height:1.6;">给整个游戏换一张背景图：官方关卡、无尽模式都会用。单关自带的背景（在作品「素材」里设置）优先于全局背景。</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-top:8px;">当前状态：<b style="color:' + (cur ? '#7de08a' : '#8fa6c8') + ';">' + (cur ? '已设置自定义背景' : '默认森林') + '</b></div>' +
    '<button id="gbg-file" style="background:#3d4f6d;' + UGC_BTN + '">上传本地图片</button>' +
    '<button id="gbg-url" style="background:#2c6199;' + UGC_BTN + '">粘贴图片链接</button>' +
    '<button id="gbg-clear" style="background:#3a2330;' + UGC_BTN + '">恢复默认</button>' +
    '<button id="gbg-close" style="background:#223046;' + UGC_BTN + '">完成</button>';
  box.querySelector('#gbg-file').onclick = () => {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = 'image/png,image/jpeg,image/webp,image/gif';
    inp.onchange = () => {
      const f = inp.files && inp.files[0];
      if (!f) return;
      if (f.size > UGC_MAX_ASSET_MB * 1024 * 1024) {
        FX.sfloat(W / 2, 250, '图片超过 ' + UGC_MAX_ASSET_MB + 'MB', { color: '#ff8f7e', size: 26, life: 1.6 });
        return;
      }
      shrinkImageFile(f, 1600, 'image/jpeg', 0.85, (dataUrl, shrunk) => {
        if (!dataUrl) { FX.sfloat(W / 2, 250, '图片读取失败', { color: '#ff8f7e', size: 24, life: 1.6 }); return; }
        const ok = globalBgSet(dataUrl);
        FX.sfloat(W / 2, 250, ok ? '全局背景已更新！' : '图太大存不下，仅本次生效', { color: ok ? '#7de08a' : '#ffcf6e', size: ok ? 28 : 22, life: 1.8 });
        ugcOverlayHide();
      });
    };
    inp.click();
  };
  box.querySelector('#gbg-url').onclick = () => {
    ugcOverlayHide();
    const b2 = document.createElement('div');
    b2.style.cssText = UGC_BOX;
    b2.innerHTML =
      '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">全局背景 · 网络图片</div>' +
      '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">粘贴 http(s) 图片链接（离线时会加载失败并回退默认背景）</div>' +
      '<input id="gbg-url-inp" type="text" placeholder="https://example.com/bg.png" style="width:100%;box-sizing:border-box;color:#dbe7f8;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px;font-size:15px;">' +
      '<button id="gbg-url-ok" style="background:#3d4f6d;' + UGC_BTN + '">确定</button>' +
      '<button id="gbg-url-close" style="background:#223046;' + UGC_BTN + '">取消</button>';
    const inp = b2.querySelector('#gbg-url-inp');
    b2.querySelector('#gbg-url-ok').onclick = () => {
      const url = (inp.value || '').trim();
      if (!/^https?:\/\/|^data:image\//i.test(url)) { FX.sfloat(W / 2, 250, '请输入 http(s) 或 data:image 链接', { color: '#ff8f7e', size: 24 }); return; }
      const ok = globalBgSet(url);
      FX.sfloat(W / 2, 250, ok ? '全局背景已更新！' : '链接内容太大，仅本次生效', { color: ok ? '#7de08a' : '#ffcf6e', size: ok ? 28 : 22, life: 1.8 });
      ugcOverlayHide();
    };
    b2.querySelector('#gbg-url-close').onclick = () => ugcOverlayHide();
    ugcOverlayShow(b2);
    setTimeout(() => inp.focus(), 60);
  };
  box.querySelector('#gbg-clear').onclick = () => {
    globalBgSet('');
    FX.sfloat(W / 2, 250, '已恢复默认背景', { color: '#ffd34d', size: 26, life: 1.5 });
    ugcOverlayHide();
  };
  box.querySelector('#gbg-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
}

/* ---------- 全局材质：金币/标题/地面/蚩尤贴图替换 ---------- */
const TEX_SCENE_KEYS = ['coin', 'logo', 'grass', 'dirt'];
const TEX_NAMES = {
  coin: '金币', logo: '标题 Logo', grass: '草地皮', dirt: '泥土',
  chiyou_idle: '蚩尤·待机', chiyou_stand_wing: '蚩尤·变身',
  chiyou_run1: '蚩尤·跑 1', chiyou_run2: '蚩尤·跑 2', chiyou_run3: '蚩尤·跑 3',
  chiyou_skill: '蚩尤·施法', chiyou_dash: '蚩尤·冲刺',
  chiyou_punch: '蚩尤·爪击', chiyou_punch2: '蚩尤·被绊', chiyou_slide: '蚩尤·滑铲',
  chiyou_mech_run: '蚩尤·机甲跑 1', chiyou_mech_run2: '蚩尤·机甲跑 2', chiyou_mech_full: '蚩尤·机甲全身',
  aj_idle: '阿坚·待机', aj_run1: '阿坚·跑 1', aj_run2: '阿坚·跑 2',
  aj_throw1: '阿坚·瞄准', aj_throw2: '阿坚·掷剑', aj_dive: '阿坚·瞬步', aj_slide: '阿坚·影袭',
  aj_shuriken: '阿坚·手里剑',
  ajw_swing1: '阿坚·持剑蓄力', ajw_swing2: '阿坚·横扫斩', ajw_swing3: '阿坚·居合泛红', ajw_slash: '阿坚·剑气波',
};

/* 上传图片统一压缩：缩到 maxSide 内再转 dataURL。
   jpeg 用于背景（体积小），png 用于蚩尤帧等需透明图（小图原样返回避免重编码）。
   cb(dataUrl|null, shrunk) */
function shrinkImageFile(file, maxSide, mime, quality, cb) {
  const rd = new FileReader();
  rd.onload = () => {
    const src = String(rd.result);
    const im = new Image();
    im.onload = () => {
      try {
        const k = Math.min(1, maxSide / Math.max(im.width, im.height));
        if (k >= 1 && mime === 'image/png' && file.size <= 512 * 1024) { cb(src, false); return; }
        const c = document.createElement('canvas');
        c.width = Math.max(1, Math.round(im.width * k));
        c.height = Math.max(1, Math.round(im.height * k));
        const g = c.getContext('2d');
        g.imageSmoothingEnabled = true;
        g.drawImage(im, 0, 0, c.width, c.height);
        cb(c.toDataURL(mime, quality), true);
      } catch (e) { cb(src, false); }
    };
    im.onerror = () => cb(null, false);
    im.src = src;
  };
  rd.onerror = () => cb(null, false);
  rd.readAsDataURL(file);
}

/* 单个材质槽位：上传 / 链接 / 清除。成功后飘字并重开弹窗刷新状态 */
function ugcTexPickFile(key) {
  const inp = document.createElement('input');
  inp.type = 'file';
  inp.accept = 'image/png,image/jpeg,image/webp';
  inp.onchange = () => {
    const f = inp.files && inp.files[0];
    if (!f) return;
    if (f.size > UGC_MAX_ASSET_MB * 1024 * 1024 * 4) {
      FX.sfloat(W / 2, 250, '图片超过 ' + (UGC_MAX_ASSET_MB * 4) + 'MB', { color: '#ff8f7e', size: 26, life: 1.6 });
      return;
    }
    shrinkImageFile(f, 512, 'image/png', 1, (dataUrl) => {
      if (!dataUrl) { FX.sfloat(W / 2, 250, '图片读取失败', { color: '#ff8f7e', size: 24, life: 1.6 }); return; }
      const ok = setGlobalTex(key, dataUrl);
      FX.sfloat(W / 2, 250, ok ? (TEX_NAMES[key] || key) + ' 已替换！' : '图太大存不下，仅本次生效', { color: ok ? '#7de08a' : '#ffcf6e', size: ok ? 26 : 22, life: 1.8 });
      ugcGlobalTexUI();
    });
  };
  inp.click();
}

function ugcTexPickUrl(key) {
  ugcOverlayHide();
  const b2 = document.createElement('div');
  b2.style.cssText = UGC_BOX;
  b2.innerHTML =
    '<div style="color:#8ce8ff;font-size:22px;font-weight:bold;margin-bottom:6px;">' + (TEX_NAMES[key] || key) + ' · 网络图片</div>' +
    '<div style="color:#8fa6c8;font-size:14px;margin-bottom:10px;">粘贴 http(s) 图片链接（蚩尤帧等需要透明底的请用 png）</div>' +
    '<input id="gtx-inp" type="text" placeholder="https://example.com/tex.png" style="width:100%;box-sizing:border-box;color:#dbe7f8;background:#0e1626;border:1px solid #33425c;border-radius:10px;padding:10px;font-size:15px;">' +
    '<button id="gtx-ok" style="background:#3d4f6d;' + UGC_BTN + '">确定</button>' +
    '<button id="gtx-close" style="background:#223046;' + UGC_BTN + '">取消</button>';
  const inp = b2.querySelector('#gtx-inp');
  b2.querySelector('#gtx-ok').onclick = () => {
    const url = (inp.value || '').trim();
    if (!/^https?:\/\/|^data:image\//i.test(url)) { FX.sfloat(W / 2, 250, '请输入 http(s) 或 data:image 链接', { color: '#ff8f7e', size: 24 }); return; }
    const ok = setGlobalTex(key, url);
    FX.sfloat(W / 2, 250, ok ? (TEX_NAMES[key] || key) + ' 已替换！' : '链接内容太大，仅本次生效', { color: ok ? '#7de08a' : '#ffcf6e', size: ok ? 26 : 22, life: 1.8 });
    ugcGlobalTexUI();
  };
  b2.querySelector('#gtx-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(b2);
  setTimeout(() => inp.focus(), 60);
}

function ugcGlobalTexUI() {
  Snd.click();
  const map = globalTexMap();
  const mini = 'display:inline-block;padding:6px 12px;border:0;border-radius:8px;font-size:13px;cursor:pointer;color:#fff;';
  const mkRow = k => {
    const has = !!map[k];
    return '<div style="display:flex;align-items:center;gap:8px;margin-top:7px;">' +
      '<div style="flex:1;color:' + (has ? '#7de08a' : '#8fa6c8') + ';font-size:15px;">' + (TEX_NAMES[k] || k) + (has ? ' · 已自定义' : '') + '</div>' +
      '<button data-up="' + k + '" style="background:#3d4f6d;' + mini + '">上传</button>' +
      '<button data-url="' + k + '" style="background:#2c6199;' + mini + '">链接</button>' +
      '<button data-clr="' + k + '" style="background:#3a2330;' + mini + '">清</button>' +
      '</div>';
  };
  const box = document.createElement('div');
  box.style.cssText = UGC_BOX;
  box.innerHTML =
    '<div style="color:#ffd34d;font-size:22px;font-weight:bold;margin-bottom:6px;">全局材质</div>' +
    '<div style="color:#8fa6c8;font-size:14px;line-height:1.6;">替换游戏里的贴图：金币、标题、地面、蚩尤各动作帧。所有模式生效；玩家外观请在「皮肤」里换。蚩尤帧建议透明底 png。</div>' +
    '<div style="color:#dbe7f8;font-size:15px;font-weight:bold;margin-top:12px;">场景材质</div>' +
    TEX_SCENE_KEYS.map(mkRow).join('') +
    '<div style="color:#dbe7f8;font-size:15px;font-weight:bold;margin-top:14px;">蚩尤材质</div>' +
    BOSS_ASSETS.map(mkRow).join('') +
    '<button id="gtx-clearall" style="background:#3a2330;' + UGC_BTN + '">全部恢复默认</button>' +
    '<button id="gtx-close" style="background:#223046;' + UGC_BTN + '">完成</button>';
  box.addEventListener('click', e => {
    const t = e.target;
    if (t.dataset && t.dataset.up) ugcTexPickFile(t.dataset.up);
    else if (t.dataset && t.dataset.url) ugcTexPickUrl(t.dataset.url);
    else if (t.dataset && t.dataset.clr) {
      setGlobalTex(t.dataset.clr, null);
      FX.sfloat(W / 2, 250, (TEX_NAMES[t.dataset.clr] || t.dataset.clr) + ' 已恢复默认', { color: '#ffd34d', size: 22, life: 1.4 });
      ugcGlobalTexUI();
    }
  });
  box.querySelector('#gtx-clearall').onclick = () => {
    TEX_SCENE_KEYS.concat(BOSS_ASSETS).forEach(k => setGlobalTex(k, null));
    FX.sfloat(W / 2, 250, '材质已全部恢复默认', { color: '#ffd34d', size: 26, life: 1.5 });
    ugcGlobalTexUI();
  };
  box.querySelector('#gtx-close').onclick = () => ugcOverlayHide();
  ugcOverlayShow(box);
}
function ugcDelAsset(it, assetId) {
  it.assets = (it.assets || []).filter(a => a.id !== assetId);
  if (it.bgAsset === assetId) it.bgAsset = null;
  ugcSaveAsset(it).then(() => FX.sfloat(W / 2, 250, '素材已删除', { color: '#ff8f7e', size: 22, life: 1.4 }));
}

/* 调试用：捕获运行时错误并写入标题，便于自动化验证发现问题 */
window.__err = '';
window.addEventListener('error', e => {
  window.__err = (e.message || 'err') + '@' + (e.lineno || '?');
  // 直接把错误写进标题，方便无头浏览器定位渲染阶段的问题
  try { document.title = 'ERR ' + window.__err + ' line' + (e.lineno || '?'); } catch (x) {}
});

/* 自动化验证用：固定步长快速模拟，结果写入标题 */
function debugSim(seconds) {
  const steps = Math.min(3600, Math.round(seconds * 60));
  let deaths = 0;
  try {
  for (let i = 0; i < steps && G.state === 'playing'; i++) {
    G.holdJump = true;   // 自动化模拟统一视为「长按满跳」，行为与旧版一致
    if (player.onGround) {
      const look = world.x + PLAYER_X + 80;
      let acted = false;
      // 顶刺：按住下滑键起跳，矮跳钻过去
      for (const cs of world.ceilSpikes) {
        if (look > cs.x - 130 && look < cs.x + cs.w + 30) {
          G.holdSlide = true;
          doJump();
          G.holdSlide = false;
          acted = true; break;
        }
      }
      // 火焰喷射口：靠近就跳（火柱高 150，满跳可过）
      if (!acted) {
        for (const f of world.flames) {
          if (look > f.x - 120 && look < f.x + f.w + 20) { doJump(); acted = true; break; }
        }
      }
      // 骨刺落点 / 刺坑红光：提前起跳
      if (!acted) {
        for (const fb of world.fireballs) {
          const lx = fb.wx1 - world.x;
          if (look > lx - 130 && look < lx + 40) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const p of world.firePits) {
          const lx = p.x - world.x;
          if (p.t < 0.5 && look > lx - 120 && look < lx + 60) { doJump(); acted = true; break; }
        }
      }
      // 蚩尤爪击：他贴近且在冲锋就跳
      if (!acted && G.chiyou && (G.chiyou.kind === 'claw' || G.chiyou.kind === 'nova') && G.chiyou.skillT > 0.3 && G.chiyou.bx > 40) {
        doJump(); acted = true;
      }
      // 阿坚贴身连斩：连砍期间持续跳躲
      if (!acted && G.chiyou && G.chiyou.kind === 'combo' && G.chiyou.skillT > 0.4 && G.chiyou.comboN > 0 && player.onGround) {
        doJump(); acted = true;
      }
      // 地刺浪：浪头逼近就跳
      if (!acted) {
        for (const q of world.quakes) {
          const lx = q.x - world.x;
          if (lx > look - 60 && lx < look + 90) { doJump(); acted = true; break; }
        }
      }
      // 陨石落点：跳
      if (!acted) {
        for (const m of world.meteors) {
          if (m.boom > 0) continue;
          const lx = m.tx - world.x;
          if (look > lx - 110 && look < lx + 70) { doJump(); acted = true; break; }
        }
      }
      // 链锤扫过来：跳
      if (!acted) {
        for (const ch of world.chains) {
          const hx = ch.x0 + ch.len - world.x;
          if (ch.t > 0.12 && look > hx - 110 && look < hx + 60) { doJump(); acted = true; break; }
        }
      }
      // 低横杆 → 滑铲
      if (!acted) {
        for (const lb of world.lowBars) {
          if (look > lb.x - 90 && look < lb.x + lb.w) { doSlide(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const g of world.gaps) {
          if (look > g.x - 40 && look < g.x + g.w + 24) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const tr of world.spikeTraps) {
          if (look > tr.x - 30 && look < tr.x + tr.w + 20 && spikeTrapHeight(tr) > 0.2) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const hs of world.hiddenSpikes) {
          if (look > hs.x - 70 && look < hs.x + hs.w + 20) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const fr of world.fallingRocks) {
          if (fr.triggered && !fr.gone && look > fr.x - 90 && look < fr.x + 60) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const ts of world.trapSprings) {
          if (look > ts.x - 70 && look < ts.x + ts.w + 20) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        for (const ff of world.fakeFloors) {
          if (look > ff.x - 60 && look < ff.x + ff.w + 20) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        // 浮空怪：跳起来踩它头顶，能弹起还能回补跳
        for (const en of world.enemies) {
          if (en.dead) continue;
          const d = en.x - (world.x + PLAYER_X);
          if (d > -40 && d < 300) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        // 滚石 / 身后追石：跳过去
        for (const rk of world.rollingRocks) {
          if (!rk.alive) continue;
          const d = rk.x - (world.x + PLAYER_X);
          if (d > -130 && d < 250) { doJump(); acted = true; break; }
        }
      }
      if (!acted) {
        // 摆锤：滑铲从底下钻过去
        for (const pd of world.pendulums) {
          if (look > pd.x - 130 && look < pd.x + 130) { doSlide(); acted = true; break; }
        }
      }
      if (!acted) {
        // 石板：进入范围就滑铲穿过
        for (const cr of world.crushers) {
          if (look > cr.x - 160 && look < cr.x + cr.w + 60) { doSlide(); acted = true; break; }
        }
      }
    }
    // 空中补跳：下坠了但还在坑上方 → 再补一次
    if (!player.onGround && player.vy > 0 && G.airJump > 0 && G.airCd <= 0) {
      const px = world.x + PLAYER_X + 20;
      for (const g of world.gaps) {
        if (px > g.x - 12 && px < g.x + g.w + 12) { doJump(); break; }
      }
    }
    // 能量攒满就放冲刺
    if (G.energy >= ENERGY_MAX && G.dashT <= 0 && player.onGround) doDash();
    update(1 / 60);
  }
  } catch (ex) {
    window.__err = 'SIM:' + (ex && ex.message ? ex.message : String(ex));
  }
  try {
    document.title = 'SIM state=' + G.state + ' x=' + Math.round(world.x) +
      ' end=' + Math.round(G.levelEndX || 0) +
      ' obj=' + (G.objective ? G.objective.id + ':' + JSON.stringify(G.objective.data) : '-') +
      ' objDone=' + (G.objective ? objectiveDone(G.objective) : '-') +
      ' prog=' + (G.objective ? (objectiveProg(G.objective) || '-') : '-') +
      ' coins=' + G.coins + '/' + G.levelCoinTotal + ' crates=' + G.crateCount +
      ' gates=' + world.gates.length + '+' + (world.gateNextX || 0) +
      ' fever=' + (G.feverT || 0).toFixed(1) + '/' + (G.feverNeed || 0) +
      ' day=' + (G.dayT || 0).toFixed(0) +
      ' chy=' + (G.chiyou ? (G.chiyou.skillCd.toFixed(0) + 's ' + (G.chiyou.waitT > 0 ? 'wait' : (G.chiyou.skillT >= 0 ? G.chiyou.kind : 'idle')) + ' hurt=' + (G.chiyou.hurtT > 0 ? 1 : 0)) : 'null') +
      ' stars=' + G.clearStars + ' cause=' + (G.deathCause || '-') +
      ' air=' + G.airJump + '/' + G.airCd.toFixed(1) +
      ' en=' + Math.round(G.energy) + ' dash=' + G.dashT.toFixed(1) + ' dashN=' + G.dashN + ' stomp=' + (G.stompN || 0) +
      ' shield=' + G.shield + ' mode=' + G.mode + ' deaths=' + deaths +
      ' ptr=' + (window.__ptrtest || '-') +
      ' err=' + (window.__err || '-');
  } catch (e) {}
}
