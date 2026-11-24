// GREEN PHONE EDIT
// The Board: the phone's storage drawn as a circuit board, in the style of Circuit MusicFX.
// The core is the folder you are in, chips are its subfolders sized by bytes, components are its files,
// red jumpers are duplicates, and the LED strip is the timeline. Every file can wear its own look.
(function () {
"use strict";

// ------------------------------------------------------------------ palette and symbols
var KIND_COLOR = { image: "#1084d0", video: "#ff7a18", audio: "#8a3ad0", document: "#d03030", text: "#5a7a90",
  code: "#000080", archive: "#c09000", apk: "#1a9a5a", data: "#008080", other: "#808080" };
var KIND_SHAPE = { image: "res", video: "cap", audio: "ind", document: "diode", text: "fuse", code: "gate",
  archive: "mosfet", apk: "driver", data: "xtal", other: "sw" };
var SHAPES = [
  ["res", "Resistor"], ["cap", "Capacitor"], ["ind", "Coil"], ["diode", "Diode"], ["led", "LED"], ["mosfet", "Transistor"],
  ["gate", "Logic gate"], ["sw", "Switch"], ["driver", "Chip"], ["xtal", "Crystal"], ["fuse", "Fuse"], ["battery", "Battery"],
  ["speaker", "Speaker"], ["antenna", "Antenna"], ["star", "Star"], ["heart", "Heart"], ["diamond", "Diamond"],
  ["hexnut", "Hex nut"], ["bolt", "Bolt"], ["ring", "Ring"], ["flag", "Flag"], ["key", "Key"], ["eye", "Eye"], ["gnd", "Ground"]
];
var COLORS = ["#d00000", "#ff7a18", "#e0a000", "#c8c800", "#1a9a5a", "#00a868", "#008080", "#0088cc", "#1f6fd0", "#000080",
  "#5a2ad0", "#8a3ad0", "#c030a0", "#ff40a0", "#8b4513", "#404040"];
var EFFECTS = [["", "None"], ["glow", "Glow"], ["pulse", "Pulse"], ["blink", "Blink"], ["sparkle", "Sparkle"], ["orbit", "Orbit"]];
var SIZES = ["Small", "Normal", "Large", "Huge"];
var SIZE_MUL = [0.7, 1, 1.45, 2.0];

function parseLook(s) {
  if (!s) return null;
  if (typeof s === "object") return s;
  var p = String(s).split("|");
  if (!p[0] && !p[1] && !p[2] && !p[4] && (p[3] === "1" || !p[3])) return null;
  return { color: p[0] || "", shape: p[1] || "", label: p[2] || "", size: p[3] === undefined || p[3] === "" ? 1 : +p[3], fx: p[4] || "" };
}
function lookFor(file) {
  var l = parseLook(file.look);
  return {
    color: (l && l.color) || KIND_COLOR[file.kind] || "#808080",
    shape: (l && l.shape) || KIND_SHAPE[file.kind] || "sw",
    label: (l && l.label) || "",
    size: l ? l.size : 1,
    fx: (l && l.fx) || "",
    custom: !!l
  };
}

function rr(c, x, y, w, h, r) {
  c.beginPath();
  c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r);
  c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r);
  c.arcTo(x, y, x + w, y, r);
  c.closePath();
}
function ln(c, x1, y1, x2, y2) { c.beginPath(); c.moveTo(x1, y1); c.lineTo(x2, y2); c.stroke(); }

// Component geometry from Circuit MusicFX, extended with extra symbols so every file can be told apart.
function shape(c, kind, s) {
  c.lineCap = "round"; c.lineJoin = "round";
  switch (kind) {
    case "res": { var bl = s * 1.4, bh = s * 0.46; ln(c, -s, 0, -bl / 2, 0); ln(c, bl / 2, 0, s, 0); rr(c, -bl / 2, -bh / 2, bl, bh, s * 0.08); c.fill(); c.stroke();
      c.save(); c.lineWidth *= 0.8; for (var b = -1; b <= 1; b++) ln(c, b * s * 0.3, -bh / 2, b * s * 0.3, bh / 2); c.restore(); break; }
    case "cap": { var g = s * 0.26, ph = s * 0.8; ln(c, -s * 0.9, 0, -g, 0); ln(c, g, 0, s * 0.9, 0); c.save(); c.lineWidth *= 1.8; ln(c, -g, -ph / 2, -g, ph / 2); ln(c, g, -ph / 2, g, ph / 2); c.restore(); break; }
    case "diode": { var t = s * 0.7; ln(c, -s * 0.9, 0, -t / 2, 0); ln(c, t / 2, 0, s * 0.9, 0); c.beginPath(); c.moveTo(-t / 2, -t * 0.55); c.lineTo(t / 2, 0); c.lineTo(-t / 2, t * 0.55); c.closePath(); c.fill(); c.stroke(); ln(c, t / 2, -t * 0.55, t / 2, t * 0.55); break; }
    case "ind": { ln(c, -s * 0.9, 0, -s * 0.6, 0); ln(c, s * 0.6, 0, s * 0.9, 0); var n = 4, bw = s * 1.2 / n; c.beginPath(); for (var k = 0; k < n; k++) { var cx = -s * 0.6 + bw * (k + 0.5); c.moveTo(cx + bw / 2, 0); c.arc(cx, 0, bw / 2, 0, Math.PI, true); } c.stroke(); break; }
    case "mosfet": { ln(c, -s * 0.95, 0, -s * 0.5, 0); ln(c, s * 0.5, 0, s * 0.95, 0); c.beginPath(); c.arc(0, 0, s * 0.5, 0, 7); c.fill(); c.stroke(); ln(c, -s * 0.18, -s * 0.3, -s * 0.18, s * 0.3); ln(c, -s * 0.18, -s * 0.16, s * 0.28, -s * 0.32); ln(c, -s * 0.18, s * 0.16, s * 0.28, s * 0.32); break; }
    case "gate": { ln(c, -s * 0.95, -s * 0.22, -s * 0.45, -s * 0.22); ln(c, -s * 0.95, s * 0.22, -s * 0.45, s * 0.22); c.beginPath(); c.moveTo(-s * 0.45, -s * 0.42); c.lineTo(0, -s * 0.42); c.arc(0, 0, s * 0.42, -Math.PI / 2, Math.PI / 2); c.lineTo(-s * 0.45, s * 0.42); c.closePath(); c.fill(); c.stroke(); ln(c, s * 0.42, 0, s * 0.95, 0); break; }
    case "sw": { ln(c, -s * 0.95, 0, -s * 0.4, 0); ln(c, s * 0.4, 0, s * 0.95, 0); c.beginPath(); c.arc(-s * 0.4, 0, s * 0.1, 0, 7); c.fill(); c.stroke(); c.beginPath(); c.arc(s * 0.4, 0, s * 0.1, 0, 7); c.fill(); c.stroke(); ln(c, -s * 0.4, 0, s * 0.3, -s * 0.45); break; }
    case "driver": { var w = s * 1.3, h = s * 0.95; ln(c, -s * 0.95, 0, -w / 2, 0); ln(c, s * 0.95, 0, w / 2, 0); rr(c, -w / 2, -h / 2, w, h, s * 0.1); c.fill(); c.stroke(); for (var d = 0; d < 3; d++) { var px = -w / 2 + w * (d + 1) / 4; ln(c, px, -h / 2, px, -h / 2 - s * 0.16); ln(c, px, h / 2, px, h / 2 + s * 0.16); } c.beginPath(); c.arc(-w / 2 + s * 0.17, -h / 2 + s * 0.17, s * 0.08, 0, 7); c.stroke(); break; }
    case "led": { ln(c, -s * 0.9, 0, -s * 0.3, 0); ln(c, s * 0.3, 0, s * 0.9, 0); c.beginPath(); c.moveTo(-s * 0.3, -s * 0.38); c.lineTo(s * 0.3, 0); c.lineTo(-s * 0.3, s * 0.38); c.closePath(); c.fill(); c.stroke(); ln(c, s * 0.3, -s * 0.38, s * 0.3, s * 0.38); ln(c, 0, -s * 0.5, s * 0.25, -s * 0.8); ln(c, s * 0.2, -s * 0.45, s * 0.45, -s * 0.75); break; }
    case "xtal": { ln(c, -s * 0.9, 0, -s * 0.35, 0); ln(c, s * 0.35, 0, s * 0.9, 0); ln(c, -s * 0.35, -s * 0.4, -s * 0.35, s * 0.4); ln(c, s * 0.35, -s * 0.4, s * 0.35, s * 0.4); rr(c, -s * 0.2, -s * 0.45, s * 0.4, s * 0.9, s * 0.04); c.fill(); c.stroke(); break; }
    case "fuse": { ln(c, -s * 0.95, 0, -s * 0.55, 0); ln(c, s * 0.55, 0, s * 0.95, 0); rr(c, -s * 0.55, -s * 0.25, s * 1.1, s * 0.5, s * 0.25); c.fill(); c.stroke(); c.beginPath(); c.moveTo(-s * 0.55, 0); c.bezierCurveTo(-s * 0.2, -s * 0.3, s * 0.2, s * 0.3, s * 0.55, 0); c.stroke(); break; }
    case "battery": { ln(c, -s * 0.95, 0, -s * 0.3, 0); ln(c, s * 0.3, 0, s * 0.95, 0); c.save(); c.lineWidth *= 1.6; ln(c, -s * 0.3, -s * 0.5, -s * 0.3, s * 0.5); ln(c, -s * 0.05, -s * 0.25, -s * 0.05, s * 0.25); ln(c, s * 0.15, -s * 0.5, s * 0.15, s * 0.5); ln(c, s * 0.38, -s * 0.25, s * 0.38, s * 0.25); c.restore(); break; }
    case "speaker": { ln(c, -s * 0.95, 0, -s * 0.5, 0); c.beginPath(); c.moveTo(-s * 0.5, -s * 0.22); c.lineTo(-s * 0.2, -s * 0.22); c.lineTo(s * 0.25, -s * 0.55); c.lineTo(s * 0.25, s * 0.55); c.lineTo(-s * 0.2, s * 0.22); c.lineTo(-s * 0.5, s * 0.22); c.closePath(); c.fill(); c.stroke(); c.beginPath(); c.arc(s * 0.3, 0, s * 0.35, -0.8, 0.8); c.stroke(); c.beginPath(); c.arc(s * 0.3, 0, s * 0.6, -0.7, 0.7); c.stroke(); break; }
    case "antenna": { ln(c, 0, s * 0.6, 0, -s * 0.5); c.beginPath(); c.moveTo(-s * 0.45, -s * 0.5); c.lineTo(0, 0); c.lineTo(s * 0.45, -s * 0.5); c.stroke(); c.beginPath(); c.arc(0, -s * 0.5, s * 0.12, 0, 7); c.fill(); c.stroke(); c.beginPath(); c.arc(0, -s * 0.5, s * 0.5, -2.4, -0.7); c.stroke(); break; }
    case "star": { c.beginPath(); for (var i = 0; i < 10; i++) { var a = -Math.PI / 2 + i * Math.PI / 5, r = i % 2 ? s * 0.32 : s * 0.75; c.lineTo(Math.cos(a) * r, Math.sin(a) * r); } c.closePath(); c.fill(); c.stroke(); break; }
    case "heart": { c.beginPath(); c.moveTo(0, s * 0.6); c.bezierCurveTo(-s * 0.95, 0, -s * 0.5, -s * 0.75, 0, -s * 0.3); c.bezierCurveTo(s * 0.5, -s * 0.75, s * 0.95, 0, 0, s * 0.6); c.closePath(); c.fill(); c.stroke(); break; }
    case "diamond": { c.beginPath(); c.moveTo(0, -s * 0.7); c.lineTo(s * 0.55, -s * 0.15); c.lineTo(0, s * 0.7); c.lineTo(-s * 0.55, -s * 0.15); c.closePath(); c.fill(); c.stroke(); ln(c, -s * 0.55, -s * 0.15, s * 0.55, -s * 0.15); break; }
    case "hexnut": { c.beginPath(); for (var j = 0; j < 6; j++) { var b2 = j * Math.PI / 3; c.lineTo(Math.cos(b2) * s * 0.65, Math.sin(b2) * s * 0.65); } c.closePath(); c.fill(); c.stroke(); c.beginPath(); c.arc(0, 0, s * 0.27, 0, 7); c.stroke(); break; }
    case "bolt": { c.beginPath(); c.moveTo(s * 0.15, -s * 0.75); c.lineTo(-s * 0.4, s * 0.1); c.lineTo(-s * 0.02, s * 0.1); c.lineTo(-s * 0.2, s * 0.75); c.lineTo(s * 0.45, -s * 0.15); c.lineTo(s * 0.05, -s * 0.15); c.closePath(); c.fill(); c.stroke(); break; }
    case "ring": { c.beginPath(); c.arc(0, 0, s * 0.6, 0, 7); c.stroke(); c.beginPath(); c.arc(0, 0, s * 0.3, 0, 7); c.fill(); c.stroke(); ln(c, -s * 0.95, 0, -s * 0.6, 0); ln(c, s * 0.6, 0, s * 0.95, 0); break; }
    case "flag": { ln(c, -s * 0.35, s * 0.75, -s * 0.35, -s * 0.75); c.beginPath(); c.moveTo(-s * 0.35, -s * 0.75); c.lineTo(s * 0.6, -s * 0.45); c.lineTo(-s * 0.35, -s * 0.1); c.closePath(); c.fill(); c.stroke(); break; }
    case "key": { c.beginPath(); c.arc(-s * 0.45, 0, s * 0.3, 0, 7); c.fill(); c.stroke(); ln(c, -s * 0.15, 0, s * 0.8, 0); ln(c, s * 0.45, 0, s * 0.45, s * 0.25); ln(c, s * 0.7, 0, s * 0.7, s * 0.3); break; }
    case "eye": { c.beginPath(); c.moveTo(-s * 0.8, 0); c.quadraticCurveTo(0, -s * 0.7, s * 0.8, 0); c.quadraticCurveTo(0, s * 0.7, -s * 0.8, 0); c.closePath(); c.fill(); c.stroke(); c.save(); c.fillStyle = c.strokeStyle; c.beginPath(); c.arc(0, 0, s * 0.22, 0, 7); c.fill(); c.restore(); break; }
    default: { ln(c, 0, -s * 0.4, 0, s * 0.2); ln(c, -s * 0.5, s * 0.2, s * 0.5, s * 0.2); ln(c, -s * 0.32, s * 0.42, s * 0.32, s * 0.42); ln(c, -s * 0.15, s * 0.64, s * 0.15, s * 0.64); }
  }
}

// a filled, readable symbol: tinted body with a dark outline in the look's color
function drawSymbol(c, kind, x, y, s, color, alpha) {
  c.save();
  c.translate(x, y);
  c.globalAlpha = alpha === undefined ? 1 : alpha;
  c.lineWidth = Math.max(1.2, s * 0.11);
  c.strokeStyle = color;
  c.fillStyle = tint(color, 0.72);
  shape(c, kind, s);
  c.restore();
}
function tint(hex, amt) {
  var n = parseInt(hex.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
  r = Math.round(r + (255 - r) * amt); g = Math.round(g + (255 - g) * amt); b = Math.round(b + (255 - b) * amt);
  return "rgb(" + r + "," + g + "," + b + ")";
}

var urlCache = {};
function symbolURL(kind, color, px) {
  var key = kind + color + px;
  if (urlCache[key]) return urlCache[key];
  var cv = document.createElement("canvas"), d = 2;
  cv.width = cv.height = px * d;
  var c = cv.getContext("2d");
  c.scale(d, d);
  drawSymbol(c, kind, px / 2, px / 2, px * 0.42, color, 1);
  return (urlCache[key] = cv.toDataURL());
}

// ------------------------------------------------------------------ seeded layout
function hashStr(s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
function makeRng(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function Board(canvas, data, opts) {
  this.cv = canvas;
  this.d = data;
  this.o = opts || {};
  this.win = null;        // [fromMs, toMs] window picked on the LED strip
  this.cut = null;        // replay cutoff
  this.tag = 0;           // tag to highlight
  this.pulses = [];
  this.flash = {};        // chip path -> flash level
  this.pop = {};          // file path -> pop level during replay
  this.dead = false;
  this.build();
  var self = this;
  this.loop = function (t) { if (self.dead || !document.body.contains(self.cv)) return; self.frame(t); requestAnimationFrame(self.loop); };
  requestAnimationFrame(this.loop);
  this.bindInput();
}

Board.prototype.build = function () {
  var cv = this.cv, d = this.d;
  var DPR = this.DPR = Math.min(2, window.devicePixelRatio || 1);
  var W = this.W = cv.clientWidth, H = this.H = cv.clientHeight;
  cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
  var rnd = makeRng(hashStr(d.root || "/"));
  var cell = this.cell = Math.max(13, Math.round(Math.min(W, H) / 30));
  var cols = Math.floor(W / cell), rows = Math.floor(H / cell);
  var occ = new Uint8Array(cols * rows);
  var self = this;
  function mark(x0, y0, x1, y1, pad) {
    var a = Math.max(0, Math.floor(x0 / cell) - pad), b = Math.min(cols - 1, Math.ceil(x1 / cell) + pad);
    var c0 = Math.max(0, Math.floor(y0 / cell) - pad), c1 = Math.min(rows - 1, Math.ceil(y1 / cell) + pad);
    for (var y = c0; y <= c1; y++) for (var x = a; x <= b; x++) occ[x + y * cols] = 1;
  }
  function free(x0, y0, x1, y1) {
    var a = Math.floor(x0 / cell), b = Math.ceil(x1 / cell), c0 = Math.floor(y0 / cell), c1 = Math.ceil(y1 / cell);
    if (a < 1 || c0 < 1 || b >= cols - 1 || c1 >= rows - 1) return false;
    for (var y = c0; y <= c1; y++) for (var x = a; x <= b; x++) if (occ[x + y * cols]) return false;
    return true;
  }
  function markLine(pts) {
    for (var i = 1; i < pts.length; i++) {
      var p = pts[i - 1], q = pts[i], n = Math.ceil(Math.hypot(q.x - p.x, q.y - p.y) / (cell * 0.5));
      for (var k = 0; k <= n; k++) { var x = p.x + (q.x - p.x) * k / n, y = p.y + (q.y - p.y) * k / n; var ix = Math.floor(x / cell), iy = Math.floor(y / cell); if (ix >= 0 && iy >= 0 && ix < cols && iy < rows) occ[ix + iy * cols] = 1; }
    }
  }

  // LED timeline strip along the bottom
  var months = Object.keys(d.months || {}).sort();
  var nLed = Math.min(24, Math.max(1, months.length));
  var buckets = [];
  for (var i = 0; i < nLed; i++) buckets.push({ months: [], count: 0 });
  months.forEach(function (m, j) { var b = Math.min(nLed - 1, Math.floor(j * nLed / months.length)); buckets[b].months.push(m); buckets[b].count += d.months[m]; });
  var sp = Math.min(cell * 1.15, (W - cell * 3) / nLed);
  var stripY = H - cell * 1.6, stripX = W / 2 - sp * (nLed - 1) / 2;
  buckets.forEach(function (b, j) {
    b.x = stripX + j * sp; b.y = stripY;
    var f = b.months[0].split("-"), l = b.months[b.months.length - 1].split("-");
    b.from = new Date(+f[0], +f[1] - 1, 1).getTime(); b.to = new Date(+l[0], +l[1], 1).getTime();
  });
  this.leds = buckets; this.ledSp = sp; this.ledMax = Math.max.apply(null, buckets.map(function (b) { return b.count; }).concat([1]));
  if (months.length) mark(stripX - cell, stripY - cell, stripX + sp * nLed, stripY + cell, 0);
  this.t0 = buckets.length && months.length ? buckets[0].from : Date.now();
  this.t1 = buckets.length && months.length ? buckets[buckets.length - 1].to : Date.now();

  // the core is this folder
  var core = this.core = { x: W / 2, y: H * 0.44, s: cell * 3.2 };
  mark(core.x - core.s / 2, core.y - core.s / 2, core.x + core.s / 2, core.y + core.s / 2, 1);

  // chips: subfolders, largest first, placed around the core
  var kids = (d.kids || []).slice(0, 26);
  var maxB = Math.max.apply(null, kids.map(function (k) { return k.bytes; }).concat([1]));
  this.chips = [];
  kids.forEach(function (k, idx) {
    var s = cell * (1.5 + 3.0 * Math.sqrt(k.bytes / maxB));
    var placed = null, ang0 = rnd() * 6.283;
    for (var tries = 0; tries < 500 && !placed; tries++) {
      var rad = core.s * 0.9 + s * 0.7 + cell * (1 + tries * 0.06);
      var ang = ang0 + tries * 2.399;
      var x = core.x + Math.cos(ang) * rad * 1.05, y = core.y + Math.sin(ang) * rad * 0.95;
      if (free(x - s / 2 - cell * 0.6, y - s / 2 - cell * 1.2, x + s / 2 + cell * 0.6, y + s / 2 + cell * 0.9)) placed = { x: x, y: y };
    }
    if (!placed) return;
    var kinds = k.kinds || {}, best = "other", bv = -1;
    Object.keys(kinds).forEach(function (kk) { if (kinds[kk] > bv) { bv = kinds[kk]; best = kk; } });
    var chip = { k: k, x: placed.x, y: placed.y, s: s, color: KIND_COLOR[best] || "#808080", name: k.path.split("/").pop(), idx: idx };
    mark(chip.x - s / 2, chip.y - s / 2 - cell * 0.9, chip.x + s / 2, chip.y + s / 2 + cell * 0.6, 0);
    self.chips.push(chip);
  });
  this.more = Math.max(0, (d.kids || []).length - this.chips.length);

  // leads from core pins to each chip, 45 degree routing, more leads for busier folders
  this.leads = [];
  this.chips.forEach(function (ch) {
    var n = 1 + Math.min(3, Math.floor(Math.log10(ch.k.count + 1)));
    var dx = ch.x - core.x, dy = ch.y - core.y, L = Math.hypot(dx, dy) || 1, nx = -dy / L, ny = dx / L;
    for (var i = 0; i < n; i++) {
      var off = (i - (n - 1) / 2) * cell * 0.36;
      var hs = core.s / 2, a = Math.atan2(dy, dx);
      var p0 = { x: core.x + Math.max(-hs, Math.min(hs, Math.cos(a) * hs * 1.5)) + nx * off, y: core.y + Math.max(-hs, Math.min(hs, Math.sin(a) * hs * 1.5)) + ny * off };
      var hc = ch.s / 2;
      var p1 = { x: ch.x - Math.max(-hc, Math.min(hc, Math.cos(a) * hc * 1.5)) + nx * off * 0.6, y: ch.y - Math.max(-hc, Math.min(hc, Math.sin(a) * hc * 1.5)) + ny * off * 0.6 };
      var ddx = p1.x - p0.x, ddy = p1.y - p0.y, adx = Math.abs(ddx), ady = Math.abs(ddy), mid;
      if (adx > ady) mid = { x: p0.x + Math.sign(ddx) * (adx - ady) * 0.5, y: p0.y };
      else mid = { x: p0.x, y: p0.y + Math.sign(ddy) * (ady - adx) * 0.5 };
      var mid2 = adx > ady ? { x: p1.x - Math.sign(ddx) * (adx - ady) * 0.5, y: p1.y } : { x: p1.x, y: p1.y - Math.sign(ddy) * (ady - adx) * 0.5 };
      var pts = [p0, mid, mid2, p1];
      self.leads.push(self.trace(pts, ch));
      markLine(pts);
    }
  });

  // this folder's own files become components in the free space nearest the core
  this.parts = [];
  var files = d.files || [];
  var cand = [];
  for (var y = 1; y < rows - 1; y++) for (var x = 1; x < cols - 1; x++) cand.push({ x: x, y: y, d: Math.hypot((x + 0.5) * cell - core.x, (y + 0.5) * cell - core.y) + rnd() * cell * 2 });
  cand.sort(function (a, b) { return a.d - b.d; });
  var maxF = Math.max.apply(null, files.map(function (f) { return f.size; }).concat([1]));
  files.forEach(function (f) {
    var lk = lookFor(f);
    var s = cell * 0.62 * (0.75 + 0.5 * Math.sqrt(f.size / maxF)) * SIZE_MUL[lk.size === undefined ? 1 : lk.size];
    var wc = Math.ceil((s * 2.1) / cell), hc = Math.ceil((s * 1.5 + (lk.label ? cell * 0.8 : 0)) / cell);
    for (var i = 0; i < cand.length; i++) {
      var c = cand[i];
      if (c.used) continue;
      var x0 = c.x * cell - (wc / 2) * cell, y0 = c.y * cell - (hc / 2) * cell;
      if (!free(x0, y0, x0 + wc * cell, y0 + hc * cell)) continue;
      var cx = x0 + wc * cell / 2, cy = y0 + hc * cell / 2;
      var ang = (rnd() < 0.5 ? 0 : (Math.abs(cx - core.x) < Math.abs(cy - core.y) ? Math.PI / 2 : 0));
      var part = { f: f, look: lk, x: cx, y: cy, s: s, ang: ang, ph: rnd() * 6.283 };
      mark(x0, y0, x0 + wc * cell, y0 + hc * cell, 0);
      // a short stub trace toward the core so the part sits on the board's wiring
      var tx = cx + Math.sign(core.x - cx) * cell * 1.2, ty = cy + Math.sign(core.y - cy) * cell * 0.8;
      part.stub = [{ x: cx + (ang ? 0 : Math.sign(core.x - cx) * s), y: cy + (ang ? Math.sign(core.y - cy) * s : 0) }, { x: tx, y: ty }];
      self.parts.push(part);
      c.used = true;
      break;
    }
  });
  this.hidden = files.length - this.parts.length + Math.max(0, (d.hereCount || 0) - files.length);

  // decorative background wiring, dim, from the same seed so a folder always looks the same
  this.deco = [];
  var dirs = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
  var target = Math.floor(cols * rows / 16), att = 0;
  while (this.deco.length < target && att < target * 6) {
    att++;
    var gx = (rnd() * cols) | 0, gy = (rnd() * rows) | 0;
    if (occ[gx + gy * cols]) continue;
    var dir = (rnd() * 8) | 0, pts2 = [{ x: gx, y: gy }];
    occ[gx + gy * cols] = 2;
    for (var st = 0; st < 6 + rnd() * 18; st++) {
      var r = rnd(); if (r < 0.17) dir = (dir + 1) & 7; else if (r < 0.34) dir = (dir + 7) & 7;
      var nx2 = gx + dirs[dir][0], ny2 = gy + dirs[dir][1];
      if (nx2 < 0 || ny2 < 0 || nx2 >= cols || ny2 >= rows || occ[nx2 + ny2 * cols]) break;
      gx = nx2; gy = ny2; occ[gx + gy * cols] = 2; pts2.push({ x: gx, y: gy });
    }
    if (pts2.length > 2) this.deco.push(pts2.map(function (p) { return { x: p.x * cell, y: p.y * cell }; }));
  }

  // duplicate jumpers
  var pos = {};
  this.chips.forEach(function (c) { pos[c.k.path] = c; });
  this.parts.forEach(function (p) { pos["f:" + p.f.path] = p; });
  this.jumpers = (d.links || []).filter(function (l) { return pos[l[0]] && pos[l[1]]; }).map(function (l) { return { a: pos[l[0]], b: pos[l[1]], n: l[2] }; });

  this.renderStatic();
};

Board.prototype.trace = function (pts, chip) {
  var len = 0, cum = [0];
  for (var i = 1; i < pts.length; i++) { len += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y); cum.push(len); }
  return { px: pts, len: len, cum: cum, chip: chip };
};

Board.prototype.renderStatic = function () {
  var W = this.W, H = this.H, cell = this.cell, DPR = this.DPR;
  var off = this.off = document.createElement("canvas");
  off.width = this.cv.width; off.height = this.cv.height;
  var c = off.getContext("2d");
  c.setTransform(DPR, 0, 0, DPR, 0, 0);
  c.fillStyle = "#f4f7f2"; c.fillRect(0, 0, W, H);
  c.strokeStyle = "rgba(40,100,140,0.08)"; c.lineWidth = 1;
  for (var gx = 0; gx < W; gx += cell) ln(c, gx, 0, gx, H);
  for (var gy = 0; gy < H; gy += cell) ln(c, 0, gy, W, gy);
  c.lineCap = "round"; c.lineJoin = "round";
  this.deco.forEach(function (px) { poly(c, px, cell * 0.07, "rgba(40,100,140,0.22)"); });
  this.deco.forEach(function (px) { var e = px[px.length - 1]; c.beginPath(); c.arc(e.x, e.y, cell * 0.12, 0, 7); c.fillStyle = "#fff"; c.fill(); c.lineWidth = 1; c.strokeStyle = "rgba(40,100,140,0.35)"; c.stroke(); });
  this.leads.forEach(function (t) {
    poly(c, t.px, cell * 0.2, "rgba(150,180,195,0.3)");
    poly(c, t.px, cell * 0.08, "rgba(40,100,140,0.6)");
  });
  this.parts.forEach(function (p) {
    poly(c, p.stub, cell * 0.06, "rgba(40,100,140,0.45)");
    var e = p.stub[1]; c.beginPath(); c.arc(e.x, e.y, cell * 0.13, 0, 7); c.fillStyle = "#fff"; c.fill(); c.lineWidth = 1; c.strokeStyle = "rgba(40,100,140,0.6)"; c.stroke();
  });
  // chips with silkscreen names
  var font = function (px, bold) { return (bold ? "bold " : "") + Math.round(px) + "px Tahoma, Verdana, sans-serif"; };
  this.chips.forEach(function (ch) {
    var s = ch.s;
    rr(c, ch.x - s / 2, ch.y - s / 2, s, s, cell * 0.14);
    c.fillStyle = "#e6ecf1"; c.fill(); c.lineWidth = Math.max(1, cell * 0.06); c.strokeStyle = "rgba(30,80,130,0.75)"; c.stroke();
    var pins = Math.max(2, Math.min(7, Math.round(s / cell))), pl = cell * 0.22;
    c.lineWidth = Math.max(1, cell * 0.05); c.strokeStyle = "rgba(170,120,40,0.85)";
    for (var p = 0; p < pins; p++) {
      var o = -s / 2 + s * (p + 1) / (pins + 1);
      ln(c, ch.x - s / 2, ch.y + o, ch.x - s / 2 - pl, ch.y + o); ln(c, ch.x + s / 2, ch.y + o, ch.x + s / 2 + pl, ch.y + o);
    }
    c.beginPath(); c.arc(ch.x - s / 2 + cell * 0.25, ch.y - s / 2 + cell * 0.25, cell * 0.09, 0, 7); c.strokeStyle = "rgba(30,80,130,0.6)"; c.lineWidth = 1; c.stroke();
    c.fillStyle = "#000080"; c.textAlign = "center"; c.textBaseline = "alphabetic";
    c.font = font(Math.max(9, cell * 0.62), true);
    var nm = ch.name.length > 14 ? ch.name.slice(0, 13) + "~" : ch.name;
    c.fillText(nm, ch.x, ch.y - s / 2 - cell * 0.32);
    c.fillStyle = "rgba(30,60,90,0.8)"; c.font = font(Math.max(8, cell * 0.5));
    c.fillText(fmtSize(ch.k.bytes), ch.x, ch.y + s / 2 + cell * 0.62);
  });
  // the core
  var co = this.core, hs = co.s / 2;
  rr(c, co.x - hs, co.y - hs, co.s, co.s, cell * 0.18);
  c.fillStyle = "#dfe6ee"; c.fill(); c.lineWidth = Math.max(1.5, cell * 0.08); c.strokeStyle = "rgba(30,80,130,0.85)"; c.stroke();
  c.fillStyle = "rgba(170,120,40,0.85)";
  for (var side = 0; side < 4; side++) for (var k = 1; k <= 6; k++) {
    var t = -hs + co.s * k / 7, px = side < 2 ? co.x + t : co.x + (side === 2 ? -hs : hs), py = side < 2 ? co.y + (side === 0 ? -hs : hs) : co.y + t;
    c.fillRect(px - cell * 0.08, py - cell * 0.08, cell * 0.16, cell * 0.16);
  }
  var rn = this.o.rootName || (this.d.root || "/").split("/").pop() || "/";
  c.fillStyle = "#000080"; c.textAlign = "center"; c.font = font(Math.max(9, cell * 0.62), true);
  c.fillText(rn.length > 12 ? rn.slice(0, 11) + "~" : rn, co.x, co.y - cell * 0.1);
  c.font = font(Math.max(8, cell * 0.48)); c.fillStyle = "rgba(30,60,90,0.85)";
  c.fillText(fmtSize(this.d.bytes || 0), co.x, co.y + cell * 0.55);
  c.fillText((this.d.count || 0).toLocaleString() + " files", co.x, co.y + cell * 1.1);
  // LED strip carrier
  if (this.leds.length && Object.keys(this.d.months || {}).length) {
    var L = this.leds, sp = this.ledSp;
    rr(c, L[0].x - sp * 0.7, L[0].y - sp * 0.65, L[L.length - 1].x - L[0].x + sp * 1.4, sp * 1.3, cell * 0.18);
    c.fillStyle = "#e6ecf1"; c.fill(); c.lineWidth = 1; c.strokeStyle = "rgba(40,100,140,0.55)"; c.stroke();
    c.fillStyle = "rgba(30,60,90,0.8)"; c.font = font(Math.max(8, cell * 0.45)); c.textAlign = "left";
    c.fillText(L[0].months[0], L[0].x - sp * 0.6, L[0].y + sp * 1.3);
    c.textAlign = "right";
    c.fillText(L[L.length - 1].months[L[L.length - 1].months.length - 1], L[L.length - 1].x + sp * 0.6, L[0].y + sp * 1.3);
  }
};
function poly(c, px, w, style) { c.beginPath(); c.moveTo(px[0].x, px[0].y); for (var i = 1; i < px.length; i++) c.lineTo(px[i].x, px[i].y); c.lineWidth = w; c.strokeStyle = style; c.stroke(); }
function fmtSize(b) { if (b < 1024) return b + " B"; var u = ["KB", "MB", "GB", "TB"], v = b, i = -1; do { v /= 1024; i++; } while (v >= 1024 && i < 3); return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + " " + u[i]; }

// how lit a chip or file is under the current window or replay
Board.prototype.inTime = function (dt) {
  if (this.cut !== null) return dt <= this.cut;
  if (this.win) return dt >= this.win[0] && dt < this.win[1];
  return true;
};
Board.prototype.chipLevel = function (ch) {
  if (this.cut === null && !this.win) return 1;
  var m = ch.k.months || {}, n = 0, self = this;
  Object.keys(m).forEach(function (k) { var p = k.split("-"), t = new Date(+p[0], +p[1] - 1, 15).getTime(); if (self.inTime(t)) n += m[k]; });
  if (this.cut !== null) return n / Math.max(1, ch.k.count);
  return n ? Math.min(1, 0.35 + n / Math.max(1, ch.k.count) * 3) : 0;
};
Board.prototype.tagged = function (tags) {
  if (!this.tag) return true;
  var list = Array.isArray(tags) ? tags.map(String) : String(tags || "").split(",");
  return list.indexOf(String(this.tag)) >= 0;
};

Board.prototype.frame = function (t) {
  var c = this.cv.getContext("2d"), W = this.W, H = this.H, cell = this.cell, self = this, now = t / 1000;
  if (this.cv.clientWidth !== W || this.cv.clientHeight !== H) { this.build(); }
  c.setTransform(this.DPR, 0, 0, this.DPR, 0, 0);
  c.drawImage(this.off, 0, 0, W, H);

  // replay clock
  if (this.replaying) {
    var k = Math.min(1, (performance.now() - this.replayStart) / this.replayDur);
    var prev = this.cut;
    this.cut = this.t0 + (this.t1 - this.t0) * k;
    this.parts.forEach(function (p) { if (p.f.dt > prev && p.f.dt <= self.cut) self.pop[p.f.path] = 1; });
    this.chips.forEach(function (ch) { var l = self.chipLevel(ch); if (l > (ch._l || 0) + 0.1) { ch.rflash = 0.7; ch._l = l; } });
    if (k >= 1) { this.replaying = false; this.cut = null; if (this.o.onReplayEnd) this.o.onReplayEnd(); }
  }

  // lead tint and chip fill by activity, type color, tag focus and flashes
  this.leads.forEach(function (tr) {
    var ch = tr.chip, lvl = self.chipLevel(ch) * (self.tagged(ch.k.tags) ? 1 : 0.15);
    if (lvl < 0.02) return;
    c.globalAlpha = 0.55 * lvl; poly(c, tr.px, cell * 0.11, ch.color);
  });
  this.chips.forEach(function (ch) {
    var lvl = self.chipLevel(ch), focus = self.tagged(ch.k.tags), fl = self.flash[ch.k.path] || 0;
    var s = ch.s;
    var rf = ch.rflash || 0;
    c.globalAlpha = (0.12 + 0.3 * lvl) * (focus ? 1 : 0.25) + fl * 0.5 + rf * 0.4;
    rr(c, ch.x - s / 2, ch.y - s / 2, s, s, cell * 0.14); c.fillStyle = fl > 0.05 ? (self.flashColor || "#ff7a18") : ch.color; c.fill();
    if (rf > 0.01) ch.rflash = rf * 0.9;
    c.globalAlpha = 1;
    // type mix bar inside the chip
    var kinds = ch.k.kinds || {}, tot = 0; Object.keys(kinds).forEach(function (kk) { tot += kinds[kk]; });
    var bx = ch.x - s / 2 + cell * 0.2, bw = s - cell * 0.4, by = ch.y + s / 2 - cell * 0.42, acc = 0;
    Object.keys(kinds).sort(function (a, b) { return kinds[b] - kinds[a]; }).forEach(function (kk) {
      var w = bw * kinds[kk] / (tot || 1); c.fillStyle = KIND_COLOR[kk] || "#808080"; c.globalAlpha = focus ? 0.9 : 0.25; c.fillRect(bx + acc, by, w, cell * 0.22); acc += w;
    });
    c.globalAlpha = 1;
    // marked files inside light up as LEDs in their own look color
    var marks = ch.k.marks || [], mx = ch.x - s / 2 + cell * 0.3, my = ch.y - s / 2 + cell * 0.55, per = Math.max(1, Math.floor((s - cell * 0.4) / (cell * 0.42)));
    marks.forEach(function (m, i) {
      if (i >= per * 2) return;
      var lk = parseLook(m.look), col = lk && lk.color ? lk.color : (self.o.tagColor ? self.o.tagColor(m.tags) : null) || KIND_COLOR[m.kind];
      var x = mx + (i % per) * cell * 0.42, y = my + Math.floor(i / per) * cell * 0.42;
      var on = self.inTime(m.dt) && self.tagged(m.tags);
      var blink = lk && lk.fx === "blink" ? (Math.sin(now * 6 + i) > 0 ? 1 : 0.25) : 1;
      c.globalAlpha = (on ? 1 : 0.2) * blink;
      if (lk && lk.shape && s > cell * 2.2) drawSymbol(c, lk.shape, x, y, cell * 0.2, col, c.globalAlpha);
      else { c.fillStyle = col; c.beginPath(); c.arc(x, y, cell * 0.13, 0, 7); c.fill(); }
      if (on && lk && (lk.fx === "glow" || lk.fx === "pulse")) { c.globalAlpha = 0.25 + 0.15 * Math.sin(now * 3 + i); c.fillStyle = col; c.beginPath(); c.arc(x, y, cell * 0.3, 0, 7); c.fill(); }
    });
    c.globalAlpha = 1;
    if (ch.k.dupes) { c.fillStyle = "#d00000"; c.font = "bold " + Math.max(8, Math.round(cell * 0.45)) + "px Tahoma"; c.textAlign = "right"; c.fillText("x" + ch.k.dupes, ch.x + s / 2 - cell * 0.12, ch.y - s / 2 + cell * 0.5); }
    if (fl > 0.01) self.flash[ch.k.path] = fl * 0.92;
  });

  // duplicate jumpers: red dashed wire hops between copies
  c.setLineDash([cell * 0.3, cell * 0.22]);
  this.jumpers.forEach(function (j) {
    var a = j.a, b = j.b;
    if ((a.f && !self.inTime(a.f.dt)) || (b.f && !self.inTime(b.f.dt))) return;
    if ((a.k && self.chipLevel(a) < 0.05) || (b.k && self.chipLevel(b) < 0.05)) return;
    var mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - Math.hypot(b.x - a.x, b.y - a.y) * 0.18;
    c.globalAlpha = 0.65; c.strokeStyle = "#d00000"; c.lineWidth = Math.max(1, cell * 0.07);
    c.beginPath(); c.moveTo(a.x, a.y); c.quadraticCurveTo(mx, my, b.x, b.y); c.stroke();
  });
  c.setLineDash([]); c.globalAlpha = 1;

  // components: each file in its own look
  this.parts.forEach(function (p) {
    var f = p.f, lk = p.look, on = self.inTime(f.dt), focus = self.tagged(f.tags);
    if (self.cut !== null && !on) return;
    var a = (on ? 1 : 0.18) * (focus ? 1 : 0.18), s = p.s, pop = self.pop[f.path] || 0;
    if (lk.fx === "blink") a *= Math.sin(now * 5 + p.ph) > -0.2 ? 1 : 0.2;
    if (lk.fx === "pulse") s *= 1 + 0.14 * Math.sin(now * 3 + p.ph);
    s *= 1 + pop * 0.6;
    if ((lk.fx === "glow" || pop > 0.05) && a > 0.5) {
      var g = c.createRadialGradient(p.x, p.y, s * 0.2, p.x, p.y, s * 1.9);
      g.addColorStop(0, hexA(lk.color, 0.35 + 0.15 * Math.sin(now * 2 + p.ph) + pop * 0.4)); g.addColorStop(1, hexA(lk.color, 0));
      c.fillStyle = g; c.beginPath(); c.arc(p.x, p.y, s * 1.9, 0, 7); c.fill();
    }
    c.save(); c.translate(p.x, p.y); c.rotate(p.ang); drawSymbol(c, lk.shape, 0, 0, s, lk.color, a); c.restore();
    if (lk.fx === "sparkle" && a > 0.5) {
      for (var i = 0; i < 4; i++) {
        var an = now * 1.3 + p.ph + i * 1.57, r = s * (1.1 + 0.25 * Math.sin(now * 4 + i)), tw = 0.5 + 0.5 * Math.sin(now * 7 + i * 2);
        drawSymbol(c, "star", p.x + Math.cos(an) * r, p.y + Math.sin(an) * r, s * 0.22 * tw + 1, lk.color, a * tw);
      }
    }
    if (lk.fx === "orbit" && a > 0.5) {
      var oa = now * 2 + p.ph; c.globalAlpha = a; c.fillStyle = lk.color;
      c.beginPath(); c.arc(p.x + Math.cos(oa) * s * 1.3, p.y + Math.sin(oa) * s * 0.7, Math.max(1.5, s * 0.14), 0, 7); c.fill();
      c.strokeStyle = hexA(lk.color, 0.3 * a); c.lineWidth = 1; c.beginPath(); c.ellipse(p.x, p.y, s * 1.3, s * 0.7, 0, 0, 7); c.stroke();
    }
    c.globalAlpha = a;
    if (f.ai) { c.strokeStyle = "#d00000"; c.lineWidth = 1.2; ln(c, p.x + s * 0.7, p.y - s * 0.2, p.x + s * 0.7, p.y - s * 0.9); c.beginPath(); c.arc(p.x + s * 0.7, p.y - s * 0.9, s * 0.3, -2.5, -0.6); c.stroke(); }
    if (f.gps) { c.strokeStyle = "#1a9a5a"; c.lineWidth = 1.2; c.beginPath(); c.arc(p.x - s * 0.8, p.y - s * 0.6, s * 0.18, 0, 7); c.stroke(); c.fillStyle = "#1a9a5a"; c.beginPath(); c.arc(p.x - s * 0.8, p.y - s * 0.6, s * 0.06, 0, 7); c.fill(); }
    var label = lk.label || (lk.custom ? "" : "");
    if (label) {
      c.font = "bold " + Math.max(8, Math.round(cell * 0.48)) + "px Tahoma"; c.textAlign = "center"; c.fillStyle = lk.color;
      c.fillText(label, p.x, p.y + s * 0.75 + cell * 0.55);
    }
    if (self.o.tagName && f.tags) {
      var tn = self.o.tagName(f.tags);
      if (tn) { c.font = Math.max(7, Math.round(cell * 0.4)) + "px Tahoma"; c.fillStyle = tn.color; c.textAlign = "center"; c.fillText(tn.name, p.x, p.y - s * 0.75 - cell * 0.15); }
    }
    c.globalAlpha = 1;
    if (pop > 0.01) self.pop[f.path] = pop * 0.9;
  });

  // scan pulses travelling the leads
  var alive = [];
  this.pulses.forEach(function (pu) {
    pu.pos += pu.speed * 0.016;
    if (pu.pos > pu.tr.len + cell * 2) return;
    alive.push(pu);
    var head = Math.min(pu.tr.len, pu.pos), tail = Math.max(0, head - cell * 2.4);
    var seg = sub(pu.tr, tail, head);
    if (!seg) return;
    c.globalAlpha = 0.25; poly(c, seg, cell * 0.36, pu.color);
    c.globalAlpha = 0.95; poly(c, seg, cell * 0.12, pu.color);
    c.globalAlpha = 1;
  });
  this.pulses = alive;
  if (this.coreFlash > 0.02) {
    var co = this.core; c.globalAlpha = this.coreFlash * 0.5; c.fillStyle = this.flashColor || "#ff7a18";
    rr(c, co.x - co.s / 2, co.y - co.s / 2, co.s, co.s, cell * 0.18); c.fill(); c.globalAlpha = 1; this.coreFlash *= 0.9;
  }

  // LED timeline
  var L = this.leds, sp = this.ledSp;
  if (L.length && Object.keys(this.d.months || {}).length) {
    L.forEach(function (b, i) {
      var lvl = Math.pow(b.count / self.ledMax, 0.6);
      var inWin = self.win ? (b.from >= self.win[0] && b.to <= self.win[1]) : true;
      var past = self.cut === null || b.from <= self.cut;
      var hue = 20 + 270 * i / Math.max(1, L.length - 1);
      c.globalAlpha = 1; c.fillStyle = "#fff"; c.strokeStyle = "rgba(40,100,140,0.6)"; c.lineWidth = 1;
      c.fillRect(b.x - sp * 0.4, b.y - sp * 0.4, sp * 0.8, sp * 0.8); c.strokeRect(b.x - sp * 0.4, b.y - sp * 0.4, sp * 0.8, sp * 0.8);
      c.globalAlpha = (inWin && past ? 1 : 0.15) * (0.15 + 0.85 * lvl);
      c.fillStyle = "hsl(" + hue + ",95%,48%)"; c.beginPath(); c.arc(b.x, b.y, sp * (0.12 + 0.22 * lvl), 0, 7); c.fill();
      if (self.win && inWin) { c.globalAlpha = 1; c.strokeStyle = "#000080"; c.lineWidth = 2; c.strokeRect(b.x - sp * 0.48, b.y - sp * 0.48, sp * 0.96, sp * 0.96); }
    });
    if (this.cut !== null) {
      var fx = L[0].x + (L[L.length - 1].x - L[0].x) * (this.cut - this.t0) / Math.max(1, this.t1 - this.t0);
      c.globalAlpha = 1; c.strokeStyle = "#d00000"; c.lineWidth = 2; ln(c, fx, L[0].y - sp * 0.7, fx, L[0].y + sp * 0.7);
      c.fillStyle = "#000080"; c.font = "bold " + Math.max(9, Math.round(cell * 0.6)) + "px Tahoma"; c.textAlign = "center";
      var dd = new Date(this.cut); c.fillText(dd.getFullYear() + "-" + ("0" + (dd.getMonth() + 1)).slice(-2), W / 2, L[0].y - sp * 1.0);
    }
  }
  c.globalAlpha = 1;
};
function sub(tr, d0, d1) {
  if (d1 <= d0) return null;
  var pts = [at(tr, d0)];
  for (var i = 0; i < tr.cum.length; i++) if (tr.cum[i] > d0 && tr.cum[i] < d1) pts.push(tr.px[i]);
  pts.push(at(tr, d1));
  return pts;
}
function at(tr, d) {
  if (d <= 0) return tr.px[0];
  if (d >= tr.len) return tr.px[tr.px.length - 1];
  var i = 1; while (i < tr.cum.length && tr.cum[i] < d) i++;
  var a = tr.px[i - 1], b = tr.px[i], f = (d - tr.cum[i - 1]) / ((tr.cum[i] - tr.cum[i - 1]) || 1);
  return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
}
function hexA(hex, a) { var n = parseInt(hex.slice(1), 16); return "rgba(" + (n >> 16) + "," + ((n >> 8) & 255) + "," + (n & 255) + "," + Math.max(0, Math.min(1, a)).toFixed(3) + ")"; }

// ------------------------------------------------------------------ input
Board.prototype.bindInput = function () {
  var self = this, cv = this.cv, drag = null;
  function pt(e) { var r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
  function ledAt(p) {
    var L = self.leds, sp = self.ledSp;
    if (!L.length || Math.abs(p.y - L[0].y) > sp * 1.1) return -1;
    var i = Math.round((p.x - L[0].x) / sp);
    return i >= 0 && i < L.length ? i : -1;
  }
  cv.addEventListener("pointerdown", function (e) {
    var p = pt(e), i = ledAt(p);
    if (i >= 0) { drag = { a: i, b: i, moved: false }; cv.setPointerCapture(e.pointerId); e.preventDefault(); }
  });
  cv.addEventListener("pointermove", function (e) {
    if (!drag) return;
    var i = ledAt({ x: pt(e).x, y: self.leds[0].y });
    if (i >= 0 && i !== drag.b) { drag.b = i; drag.moved = true; self.setWin(drag.a, drag.b); }
  });
  cv.addEventListener("pointerup", function (e) {
    if (drag) {
      var d = drag; drag = null;
      if (!d.moved) {
        var same = self.win && self.win[0] === self.leds[d.a].from && self.win[1] === self.leds[d.a].to;
        if (same) self.win = null; else self.setWin(d.a, d.a);
      }
      if (self.o.onWindow) self.o.onWindow(self.win);
      return;
    }
    var p = pt(e);
    for (var i = self.parts.length - 1; i >= 0; i--) {
      var q = self.parts[i];
      if (Math.hypot(p.x - q.x, p.y - q.y) < Math.max(q.s * 1.1, self.cell * 0.9)) { if (self.o.onFile) self.o.onFile(q.f); return; }
    }
    for (var j = 0; j < self.chips.length; j++) {
      var ch = self.chips[j];
      if (Math.abs(p.x - ch.x) < ch.s / 2 + self.cell * 0.4 && Math.abs(p.y - ch.y) < ch.s / 2 + self.cell * 0.9) { if (self.o.onChip) self.o.onChip(ch.k); return; }
    }
    var co = self.core;
    if (Math.abs(p.x - co.x) < co.s / 2 && Math.abs(p.y - co.y) < co.s / 2 && self.o.onCore) self.o.onCore();
  });
};
Board.prototype.setWin = function (a, b) {
  var lo = Math.min(a, b), hi = Math.max(a, b);
  this.win = [this.leds[lo].from, this.leds[hi].to];
};
Board.prototype.replay = function (ms) {
  if (!this.leds.length) return;
  this.win = null; this.pop = {};
  this.replayStart = performance.now(); this.replayDur = ms || 14000; this.replaying = true; this.cut = this.t0;
  this.chips.forEach(function (ch) { ch._l = 0; });
};
Board.prototype.stopReplay = function () { this.replaying = false; this.cut = null; };
// census progress: run a pulse to whichever folder the scanner is in
Board.prototype.scan = function (st) {
  if (!st || !st.running || !st.current) return;
  var cur = st.current, root = this.d.root, color = st.phase === "finding duplicates" ? "#d00000" : st.phase === "reading metadata" ? "#1f6fd0" : "#ff7a18";
  this.flashColor = color;
  if (cur.indexOf(root) !== 0) return;
  var hit = null;
  for (var i = 0; i < this.chips.length; i++) { var p = this.chips[i].k.path; if (cur === p || cur.indexOf(p + "/") === 0) { hit = this.chips[i]; break; } }
  if (!hit) { this.coreFlash = 1; return; }
  this.flash[hit.k.path] = 1;
  var self = this;
  this.leads.forEach(function (tr) { if (tr.chip === hit && self.pulses.length < 60) self.pulses.push({ tr: tr, pos: 0, speed: tr.len * 1.6, color: color }); });
};
Board.prototype.destroy = function () { this.dead = true; };

window.FCBoard = {
  Board: Board, SHAPES: SHAPES, COLORS: COLORS, EFFECTS: EFFECTS, SIZES: SIZES, KIND_COLOR: KIND_COLOR, KIND_SHAPE: KIND_SHAPE,
  parseLook: parseLook, lookFor: lookFor, symbolURL: symbolURL, drawSymbol: drawSymbol
};
})();
