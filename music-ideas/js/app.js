/* MUSIC_IDEAS — interface : saisie tactile, clavier, bibliothèque, exports. */
'use strict';

const $ = s => document.querySelector(s);
const LIB_KEY = 'music_ideas_lib_v1', SET_KEY = 'music_ideas_settings_v1';

const store = {
  get(k, def) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : def; } catch (e) { return def; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; } }
};

const settings = Object.assign({ sp: 10, sound: true, kbd: true, mode: 'download', lastFmt: 'pdf', kbOct: [4, 2], helped: false },
  store.get(SET_KEY, {}));
const saveSettings = () => store.set(SET_KEY, settings);

/* ── Bibliothèque ────────────────────────────────────────────────────── */
let lib = store.get(LIB_KEY, null);
if (!lib || typeof lib.ideas !== 'object') lib = { current: null, ideas: {} };
for (const id of Object.keys(lib.ideas)) {
  try { lib.ideas[id] = sanitizeScore(lib.ideas[id]); } catch (e) { delete lib.ideas[id]; }
}
let score = lib.ideas[lib.current] || Object.values(lib.ideas).sort((a, b) => b.updated.localeCompare(a.updated))[0];
if (!score) { score = newScore(); lib.ideas[score.id] = score; }
lib.current = score.id;

let saveTimer = 0;
function save(now) {
  score.updated = new Date().toISOString();
  lib.ideas[score.id] = score;
  lib.current = score.id;
  clearTimeout(saveTimer);
  const doSave = () => { if (!store.set(LIB_KEY, lib)) toast('⚠ Mémoire pleine : exportez vos idées (Projet .json)'); };
  if (now) doSave(); else saveTimer = setTimeout(doSave, 250);
}

/* ── État de l'éditeur ───────────────────────────────────────────────── */
const st = { staff: 0, cur: [0, 0], sel: false, ni: 0, dur: 'q', dot: 0, tup: 0, chord: false, undo: [], redo: [] };
function resetEditor() {
  st.cur = [score.staves[0].length, score.staves[1].length];
  st.staff = 0; st.sel = false; st.ni = 0; st.undo = []; st.redo = [];
}
resetEditor();

const curEv = () => score.staves[st.staff][st.cur[st.staff] - 1] || null;
const focusNote = () => { const ev = curEv(); return ev && !ev.r ? ev.n[Math.min(st.ni, ev.n.length - 1)] : null; };

function snapshot() { return JSON.stringify({ s: score, cur: st.cur, staff: st.staff }); }
function restore(json) {
  const o = JSON.parse(json);
  score = o.s; st.cur = o.cur; st.staff = o.staff; st.sel = false;
}
function mutate(fn) {
  st.undo.push(snapshot());
  if (st.undo.length > 300) st.undo.shift();
  st.redo = [];
  fn();
  save();
  render();
}
function undo() { if (!st.undo.length) return; st.redo.push(snapshot()); restore(st.undo.pop()); save(); render(); }
function redo() { if (!st.redo.length) return; st.undo.push(snapshot()); restore(st.redo.pop()); save(); render(); }

/* ── Actions d'édition ───────────────────────────────────────────────── */
function hear(midis) { if (settings.sound) Sound.preview(midis); }

/** Ajoute une hauteur dans la portée k (nouvel événement, ou accord). */
function addPitch(k, p, opts = {}) {
  let played = [midiOf(p)];
  mutate(() => {
    if (st.staff !== k) st.sel = false;
    st.staff = k;
    const evs = score.staves[k];
    const into = opts.into != null ? opts.into : ((st.chord || opts.chord) ? st.cur[k] - 1 : -1);
    const target = evs[into];
    if (target && !target.r) {
      const m = midiOf(p);
      const i = target.n.findIndex(q => midiOf(q) === m);
      if (i >= 0 && opts.toggle && target.n.length > 1) { target.n.splice(i, 1); st.ni = 0; played = []; }
      else if (i >= 0) st.ni = i;
      else { target.n.push(p); st.ni = target.n.length - 1; played = target.n.map(midiOf); }
      st.cur[k] = into + 1;
    } else {
      evs.splice(st.cur[k], 0, { n: [p], d: st.dur, dot: st.dot, tup: st.tup, tie: 0 });
      st.cur[k]++;
      st.ni = 0;
      st.sel = false;
    }
  });
  hear(played);
  scrollToCursor();
}

function addRest() {
  mutate(() => {
    score.staves[st.staff].splice(st.cur[st.staff], 0, { r: 1, d: st.dur, dot: st.dot, tup: st.tup });
    st.cur[st.staff]++;
    st.sel = false;
  });
  scrollToCursor();
}

function selectEvent(k, idx, p) {
  const ev = score.staves[k][idx];
  st.staff = k; st.cur[k] = idx + 1; st.sel = true;
  st.dur = ev.d; st.dot = ev.dot ? 1 : 0; st.tup = ev.tup ? 1 : 0;
  if (!ev.r) {
    let best = 0, bd = 1e9;
    ev.n.forEach((q, i) => { const d = Math.abs(diOf(q) - STAFF_REF[k] - p); if (d < bd) { bd = d; best = i; } });
    st.ni = best;
    hear(ev.n.map(midiOf));
  }
  render();
}

function setDur(d) {
  st.dur = d;
  if (st.sel && curEv()) mutate(() => { curEv().d = d; });
  else renderPalette();
}
function toggleDot() {
  st.dot ^= 1;
  if (st.sel && curEv()) mutate(() => { curEv().dot = st.dot; }); else renderPalette();
}
function toggleTup() {
  st.tup ^= 1;
  if (st.sel && curEv()) mutate(() => { curEv().tup = st.tup; }); else renderPalette();
}
function toggleTie() {
  const ev = curEv();
  if (!ev || ev.r) return toast('Sélectionnez une note à lier à la suivante');
  mutate(() => { ev.tie = ev.tie ? 0 : 1; });
}
function setAcc(kind) {
  const nt = focusNote();
  if (!nt) return toast('Aucune note sélectionnée');
  mutate(() => {
    const n = focusNote();
    if (kind === 1) n.a = n.a === 1 ? 2 : 1;
    else if (kind === -1) n.a = n.a === -1 ? -2 : -1;
    else n.a = 0;
  });
  hear([midiOf(focusNote())]);
}
function stepNote(dir) {
  if (!focusNote()) return toast('Aucune note sélectionnée');
  mutate(() => {
    const ev = curEv(), i = Math.min(st.ni, ev.n.length - 1);
    const np = fromDi(diOf(ev.n[i]) + dir, score.key);
    if (np.o < 0 || np.o > 8) return;
    ev.n[i] = np;
  });
  hear([midiOf(focusNote())]);
}
function octave(dir) {
  const ev = curEv();
  if (!ev || ev.r) return;
  if (ev.n.some(q => q.o + dir < 0 || q.o + dir > 8)) return;
  mutate(() => { curEv().n.forEach(q => { q.o += dir; }); });
  hear(curEv().n.map(midiOf));
}
function deleteEv() {
  if (!curEv()) return;
  mutate(() => {
    score.staves[st.staff].splice(st.cur[st.staff] - 1, 1);
    st.cur[st.staff]--;
    st.sel = false;
  });
}
function moveCursor(dir) {
  const k = st.staff, len = score.staves[k].length;
  st.cur[k] = Math.max(0, Math.min(len, st.cur[k] + dir));
  const ev = curEv();
  st.sel = !!ev;
  if (ev) { st.dur = ev.d; st.dot = ev.dot ? 1 : 0; st.tup = ev.tup ? 1 : 0; st.ni = 0; }
  render();
  scrollToCursor();
}
function toggleStaff() { st.staff = 1 - st.staff; st.sel = false; render(); renderKeyboard(); scrollToCursor(); }

/* ── Rendu ───────────────────────────────────────────────────────────── */
const scoreEl = $('#score');
let lay = null, svgEl = null;

function render() {
  const sp = settings.sp;
  lay = layoutScore(score, { width: scoreEl.clientWidth || 360, sp, extra: st.staff, title: false, margin: sp * 0.8 });
  const page = compose(lay, Infinity, 0)[0];
  let extra = '';
  lay.systems.forEach(sys => {
    extra += `<rect class="staff-hl" x="0" y="${sys.dy + (TOP[st.staff] - 1.5) * sp}" width="${lay.width}" height="${7 * sp}"/>`;
  });
  for (const k of [0, 1]) {
    const c = lay.cursorAt(k, st.cur[k]);
    const sys = lay.systems[c.sys];
    extra += `<rect class="cursor${k === st.staff ? ' active' : ''}" id="cursor${k}" x="${c.x - 0.18 * sp}" ` +
      `y="${sys.dy + (TOP[k] - 1) * sp}" width="${0.36 * sp}" height="${6 * sp}" rx="${0.18 * sp}"/>`;
  }
  const svg = pageToSVG(page, lay.width);
  scoreEl.innerHTML = svg.replace(/<g fill="currentColor">/, m => extra + m);
  svgEl = scoreEl.firstElementChild;
  highlight();
  renderTop();
  renderPalette();
  $('#hint').textContent = !score.staves[0].length && !score.staves[1].length
    ? 'Touchez la portée (ou le clavier) pour écrire une note.\nMaintenez puis glissez pour viser la hauteur.' : '';
}

function highlight() {
  if (!svgEl) return;
  svgEl.querySelectorAll('.cur,.sel').forEach(e => e.classList.remove('cur', 'sel'));
  const i = st.cur[st.staff] - 1;
  if (i >= 0) svgEl.querySelectorAll(`[data-ev="${st.staff}-${i}"]`).forEach(e => e.classList.add(st.sel ? 'sel' : 'cur'));
}

function scrollToCursor() {
  const c = document.getElementById('cursor' + st.staff);
  if (!c) return;
  const top = +c.getAttribute('y') - settings.sp * 4, h = settings.sp * (SYS_H - 2);
  if (top < scoreEl.scrollTop || top + h > scoreEl.scrollTop + scoreEl.clientHeight)
    scoreEl.scrollTo({ top: Math.max(0, top - settings.sp * 2), behavior: 'smooth' });
  const x = +c.getAttribute('x');
  if (x < scoreEl.scrollLeft || x > scoreEl.scrollLeft + scoreEl.clientWidth - 20)
    scoreEl.scrollTo({ left: Math.max(0, x - scoreEl.clientWidth / 2) });
}

function renderTop() {
  $('#title-text').textContent = score.title;
  const nM = Math.max(1, ...score.staves.map(evs => Math.ceil(staffTicks(evs) / measureTicks(score.ts))));
  $('#title-meta').textContent = `${score.ts[0]}/${score.ts[1]} · ${score.tempo} bpm · ${KEY_NAMES_FR[score.key].split(' / ')[0]} · ${nM} mes.`;
  $('#btn-undo').disabled = !st.undo.length;
  $('#btn-redo').disabled = !st.redo.length;
}

/* ── Palette ─────────────────────────────────────────────────────────── */
const gp = (n, x, y, s) => `<path transform="translate(${x} ${y})${s ? ` scale(${s})` : ''}" d="${GLYPHS[n].d}"/>`;
function noteIco(d, dot) {
  const head = d === 'w' ? 'noteheadWhole' : d === 'h' ? 'noteheadHalf' : 'noteheadBlack';
  let s = gp(head, 20, 0);
  if (d !== 'w') s += `<rect x="126" y="-330" width="12" height="313"/>`;
  if (FLAG_GLYPH[d]) s += gp('flag' + FLAG_GLYPH[d] + 'Up', 126, -330);
  if (dot) s += gp('dot', 175, -40);
  return `<svg class="ico" viewBox="0 -360 ${dot ? 240 : 250} 420">${s}</svg>`;
}
const ICONS = {
  rest: `<svg class="ico" viewBox="-20 -170 150 340">${gp('restQuarter', 0, 0)}</svg>`,
  sharp: `<svg class="ico small" viewBox="-20 -160 140 320">${gp('sharp', 0, 0)}</svg>`,
  flat: `<svg class="ico small" viewBox="-20 -200 130 320">${gp('flat', 0, 0)}</svg>`,
  natural: `<svg class="ico small" viewBox="-30 -160 130 320">${gp('natural', 0, 0)}</svg>`,
  tup: `<svg class="ico small" viewBox="-20 -170 160 190">${gp('tuplet3', 0, 0)}</svg>`,
  tie: `<svg class="ico" viewBox="-10 -120 300 240">${gp('noteheadBlack', 0, -20)}${gp('noteheadBlack', 162, -20)}` +
    `<path d="M40 40C90 110 190 110 240 40C190 85 90 85 40 40Z"/></svg>`,
  chord: `<svg class="ico" viewBox="0 -420 200 480">${gp('noteheadBlack', 20, 0)}${gp('noteheadBlack', 20, -100)}${gp('noteheadBlack', 20, -200)}` +
    `<rect x="126" y="-530" width="12" height="512"/></svg>`,
  g: `<svg class="ico" viewBox="-20 -460 310 740">${gp('gClef', 0, 0)}</svg>`,
  f: `<svg class="ico" viewBox="-20 -150 320 420">${gp('fClef', 0, 0)}</svg>`
};

const palButtons = {};
function palBtn(row, id, html, label, onTap, onLong) {
  const b = document.createElement('button');
  b.className = 'pal';
  b.innerHTML = html;
  b.setAttribute('aria-label', label);
  b.title = label;
  let timer = 0, long = false;
  if (onLong) {
    b.addEventListener('pointerdown', () => { long = false; timer = setTimeout(() => { long = true; onLong(); if (navigator.vibrate) navigator.vibrate(15); }, 450); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(t => b.addEventListener(t, () => clearTimeout(timer)));
  }
  b.addEventListener('click', () => { if (long) { long = false; return; } onTap(); });
  b.addEventListener('contextmenu', e => e.preventDefault());
  row.appendChild(b);
  palButtons[id] = b;
  return b;
}

function buildPalette() {
  const r1 = $('#row-dur'), r2 = $('#row-edit');
  for (const d of DUR_ORDER) palBtn(r1, 'd' + d, noteIco(d), DUR_NAMES[d], () => setDur(d));
  palBtn(r1, 'dot', noteIco('q', true), 'Note pointée', toggleDot).classList.add('sep');
  palBtn(r1, 'tup', ICONS.tup, 'Triolet', toggleTup);
  palBtn(r1, 'tie', ICONS.tie, 'Lier à la note suivante', toggleTie);

  palBtn(r2, 'staff', ICONS.g, 'Portée active (clé de sol / clé de fa)', toggleStaff);
  palBtn(r2, 'left', '◀', 'Curseur à gauche', () => moveCursor(-1));
  palBtn(r2, 'right', '▶', 'Curseur à droite', () => moveCursor(1));
  palBtn(r2, 'rest', ICONS.rest, 'Ajouter un silence', addRest).classList.add('sep');
  palBtn(r2, 'chord', ICONS.chord, 'Mode accord', () => { st.chord = !st.chord; renderPalette(); toast(st.chord ? 'Accord : les notes s\'empilent' : 'Accord désactivé'); });
  palBtn(r2, 'sharp', ICONS.sharp, 'Dièse', () => setAcc(1)).classList.add('sep');
  palBtn(r2, 'flat', ICONS.flat, 'Bémol', () => setAcc(-1));
  palBtn(r2, 'natural', ICONS.natural, 'Bécarre', () => setAcc(0));
  palBtn(r2, 'up', '▲', 'Monter d\'un degré (appui long : octave)', () => stepNote(1), () => octave(1)).classList.add('sep');
  palBtn(r2, 'down', '▼', 'Descendre d\'un degré (appui long : octave)', () => stepNote(-1), () => octave(-1));
  palBtn(r2, 'del', '⌫', 'Effacer la note', deleteEv).classList.add('sep');
}

function renderPalette() {
  for (const d of DUR_ORDER) palButtons['d' + d].classList.toggle('on', st.dur === d);
  palButtons.dot.classList.toggle('on', !!st.dot);
  palButtons.tup.classList.toggle('on', !!st.tup);
  const ev = curEv();
  palButtons.tie.classList.toggle('on', !!(ev && ev.tie));
  palButtons.chord.classList.toggle('on', st.chord);
  palButtons.staff.innerHTML = st.staff ? ICONS.f : ICONS.g;
  const nt = focusNote();
  palButtons.sharp.classList.toggle('on', !!nt && nt.a > 0);
  palButtons.flat.classList.toggle('on', !!nt && nt.a < 0);
  palButtons.natural.classList.toggle('on', !!nt && nt.a === 0 && keyAlter(score.key, nt.s) !== 0);
}

/* ── Saisie sur la portée ────────────────────────────────────────────── */
const bubble = $('#bubble');
const P_RANGE = [[-9, 21], [-11, 19]];
let touch = null;

function locate(clientX, clientY, fixed) {
  const r = svgEl.getBoundingClientRect();
  const x = clientX - r.left, y = clientY - r.top, sp = lay.sp;
  let si = fixed ? fixed.si : 0;
  if (!fixed) lay.systems.forEach((s, i) => { if (y >= s.dy) si = i; });
  const sys = lay.systems[si];
  const ly = y - sys.dy;
  const k = fixed ? fixed.k : (ly < 11 * sp ? 0 : 1);
  let p = Math.round(((TOP[k] + 4) * sp - ly) / (sp / 2));
  p = Math.max(P_RANGE[k][0], Math.min(P_RANGE[k][1], p));
  return { x, y, ly, si, k, p, pitch: fromDi(STAFF_REF[k] + p, score.key) };
}

function hitEvent(loc) {
  return lay.hits.find(h => h.sys === loc.si && h.k === loc.k && loc.x >= h.x1 && loc.x <= h.x2 && loc.ly >= h.y1 && loc.ly <= h.y2);
}

function showGhost(loc, clientX, clientY) {
  const sp = lay.sp, sys = lay.systems[loc.si];
  const c = lay.cursorAt(loc.k, st.cur[loc.k]);
  const gx = c.sys === loc.si ? c.x + 0.4 * sp : loc.x - 0.6 * sp;
  const yP = p => sys.dy + (TOP[loc.k] + 4) * sp - p * sp / 2;
  let g = `<g class="ghost">`;
  for (let lp = -2; lp >= loc.p; lp -= 2) g += `<line x1="${gx - 0.4 * sp}" x2="${gx + 1.6 * sp}" y1="${yP(lp)}" y2="${yP(lp)}" stroke-width="${0.16 * sp}"/>`;
  for (let lp = 10; lp <= loc.p; lp += 2) g += `<line x1="${gx - 0.4 * sp}" x2="${gx + 1.6 * sp}" y1="${yP(lp)}" y2="${yP(lp)}" stroke-width="${0.16 * sp}"/>`;
  g += `<path transform="translate(${gx} ${yP(loc.p)}) scale(${sp / 100})" d="${GLYPHS.noteheadBlack.d}"/></g>`;
  hideGhost();
  svgEl.insertAdjacentHTML('beforeend', g);
  const wr = $('#score-wrap').getBoundingClientRect();
  bubble.textContent = pitchNameFr(loc.pitch) + (st.chord ? '  (accord)' : '');
  bubble.style.left = Math.max(50, Math.min(wr.width - 50, clientX - wr.left)) + 'px';
  bubble.style.top = Math.max(40, clientY - wr.top - 36) + 'px';
  bubble.classList.add('show');
}
function hideGhost() {
  if (svgEl) svgEl.querySelectorAll('.ghost').forEach(e => e.remove());
}
function hideBubble(delay) { clearTimeout(hideBubble.t); hideBubble.t = setTimeout(() => bubble.classList.remove('show'), delay || 0); }

scoreEl.addEventListener('pointerdown', e => {
  if (!svgEl || (e.pointerType === 'mouse' && e.button !== 0)) return;
  Sound.ensure();
  const loc = locate(e.clientX, e.clientY);
  touch = { id: e.pointerId, x0: e.clientX, y0: e.clientY, loc, drag: false, moved: false };
  const startDrag = () => {
    if (!touch) return;
    touch.drag = true;
    if (navigator.vibrate) navigator.vibrate(8);
    showGhost(touch.loc, touch.x0, touch.y0);
    hear([midiOf(touch.loc.pitch)]);
  };
  if (e.pointerType === 'mouse') touch.timer = setTimeout(startDrag, 180);
  else touch.timer = setTimeout(startDrag, 260);
});

scoreEl.addEventListener('pointermove', e => {
  if (!touch || e.pointerId !== touch.id) return;
  if (!touch.drag) {
    if (Math.hypot(e.clientX - touch.x0, e.clientY - touch.y0) > 9) { touch.moved = true; clearTimeout(touch.timer); }
    return;
  }
  const loc = locate(e.clientX, e.clientY, touch.loc);
  if (loc.p !== touch.loc.p) { touch.loc = loc; hear([midiOf(loc.pitch)]); }
  showGhost(touch.loc, e.clientX, e.clientY);
});

scoreEl.addEventListener('touchmove', e => { if (touch && touch.drag) e.preventDefault(); }, { passive: false });

scoreEl.addEventListener('pointerup', e => {
  if (!touch || e.pointerId !== touch.id) return;
  clearTimeout(touch.timer);
  const t = touch;
  touch = null;
  hideGhost();
  if (t.drag) {
    hideBubble(500);
    const h = hitEvent(t.loc);
    addPitch(t.loc.k, t.loc.pitch, st.chord && h && h.idx >= 0 ? { into: h.idx } : {});
    return;
  }
  if (t.moved) return;
  const h = hitEvent(t.loc);
  if (h && h.idx >= 0 && !st.chord) { selectEvent(h.k, h.idx, t.loc.p); return; }
  addPitch(t.loc.k, t.loc.pitch, h && h.idx >= 0 ? { into: h.idx, toggle: true } : {});
  bubble.textContent = pitchNameFr(t.loc.pitch);
  const wr = $('#score-wrap').getBoundingClientRect();
  bubble.style.left = Math.max(50, Math.min(wr.width - 50, e.clientX - wr.left)) + 'px';
  bubble.style.top = Math.max(40, e.clientY - wr.top - 30) + 'px';
  bubble.classList.add('show');
  hideBubble(700);
});

scoreEl.addEventListener('pointercancel', () => { if (touch) clearTimeout(touch.timer); touch = null; hideGhost(); hideBubble(); });
scoreEl.addEventListener('contextmenu', e => e.preventDefault());

/* ── Clavier de piano ────────────────────────────────────────────────── */
const kbEl = $('#keyboard');
const kbDown = new Map();

function renderKeyboard() {
  const base = settings.kbOct[st.staff];
  const whites = [0, 2, 4, 5, 7, 9, 11], blackAfter = { 0: 1, 2: 3, 5: 6, 7: 8, 9: 10 };
  const nW = 14 + 1;
  let html = '';
  for (let i = 0; i < nW; i++) {
    const oct = base + Math.floor(i / 7), semi = whites[i % 7];
    const m = 12 * (oct + 1) + semi;
    html += `<div class="wk" data-m="${m}">${semi === 0 ? 'Do' + oct : ''}</div>`;
  }
  for (let i = 0; i < nW - 1; i++) {
    const oct = base + Math.floor(i / 7), semi = whites[i % 7];
    if (blackAfter[semi] == null) continue;
    const m = 12 * (oct + 1) + blackAfter[semi];
    html += `<div class="bk" data-m="${m}" style="left:calc(${(i + 1) * 100 / nW}% - ${100 / nW * 0.3}%);width:${100 / nW * 0.6}%"></div>`;
  }
  kbEl.innerHTML = html;
  $('#kb-label').textContent = `${st.staff ? '𝄢 Clé de fa' : '𝄞 Clé de sol'} · Do${base} – Do${base + 2}`;
}

function keyAt(x, y) {
  const el = document.elementFromPoint(x, y);
  return el && el.dataset && el.dataset.m ? el : null;
}

kbEl.addEventListener('pointerdown', e => {
  e.preventDefault();
  Sound.ensure();
  const key = keyAt(e.clientX, e.clientY);
  if (!key) return;
  const m = +key.dataset.m;
  const chord = kbDown.size > 0;
  kbDown.set(e.pointerId, key);
  key.classList.add('down');
  addPitch(st.staff, fromMidi(m, score.key), chord ? { chord: true } : {});
});
const kbUp = e => { const k = kbDown.get(e.pointerId); if (k) k.classList.remove('down'); kbDown.delete(e.pointerId); };
['pointerup', 'pointercancel', 'pointerleave'].forEach(t => kbEl.addEventListener(t, kbUp));
kbEl.addEventListener('contextmenu', e => e.preventDefault());

$('#kb-down').addEventListener('click', () => { settings.kbOct[st.staff] = Math.max(0, settings.kbOct[st.staff] - 1); saveSettings(); renderKeyboard(); });
$('#kb-up').addEventListener('click', () => { settings.kbOct[st.staff] = Math.min(6, settings.kbOct[st.staff] + 1); saveSettings(); renderKeyboard(); });

/* ── Lecture ─────────────────────────────────────────────────────────── */
function togglePlay() {
  const btn = $('#btn-play');
  if (Sound.playing) { Sound.stop(); clearPlay(); btn.textContent = '▶'; btn.classList.remove('on'); return; }
  if (!score.staves[0].length && !score.staves[1].length) return toast('La partition est vide');
  btn.textContent = '■'; btn.classList.add('on');
  Sound.play(score, pos => {
    clearPlay();
    if (!pos) return;
    let first = null;
    pos.forEach((i, k) => {
      if (i < 0) return;
      svgEl.querySelectorAll(`[data-ev="${k}-${i}"]`).forEach(el => { el.classList.add('play'); first = first || el; });
    });
    if (first) {
      const r = first.getBoundingClientRect(), wr = scoreEl.getBoundingClientRect();
      if (r.top < wr.top || r.bottom > wr.bottom) scoreEl.scrollBy({ top: r.top - wr.top - wr.height / 3, behavior: 'smooth' });
    }
  }, () => { btn.textContent = '▶'; btn.classList.remove('on'); });
}
function clearPlay() { if (svgEl) svgEl.querySelectorAll('.play').forEach(e => e.classList.remove('play')); }

/* ── Feuilles ────────────────────────────────────────────────────────── */
const backdrop = $('#sheet-backdrop');
function openSheet(id) {
  closeSheets();
  $(id).classList.add('show');
  backdrop.classList.add('show');
}
function closeSheets() {
  document.querySelectorAll('.sheet.show').forEach(s => s.classList.remove('show'));
  backdrop.classList.remove('show');
}
backdrop.addEventListener('click', closeSheets);
document.querySelectorAll('.sheet .close').forEach(b => b.addEventListener('click', closeSheets));

// Bibliothèque
function fmtDate(iso) {
  const d = new Date(iso);
  return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }) + ' ' +
    d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}
function renderLib() {
  const ul = $('#lib-list');
  ul.innerHTML = '';
  Object.values(lib.ideas).sort((a, b) => b.updated.localeCompare(a.updated)).forEach(s => {
    const li = document.createElement('li');
    if (s.id === score.id) li.className = 'current';
    const n = s.staves[0].length + s.staves[1].length;
    li.innerHTML = `<button class="lib-open"><span class="lib-title"></span><span class="lib-meta">${fmtDate(s.updated)} · ${n} note${n > 1 ? 's' : ''}</span></button>` +
      `<button class="lib-act" aria-label="Dupliquer">⧉</button><button class="lib-act danger" aria-label="Supprimer">🗑</button>`;
    li.querySelector('.lib-title').textContent = s.title;
    const [open, dup, del] = li.querySelectorAll('button');
    open.addEventListener('click', () => { openIdea(s.id); closeSheets(); });
    dup.addEventListener('click', () => {
      const c = sanitizeScore(JSON.parse(JSON.stringify(s)));
      c.id = uid(); c.title = s.title + ' (copie)'; c.created = c.updated = new Date().toISOString();
      lib.ideas[c.id] = c; save(true); renderLib();
    });
    del.addEventListener('click', () => {
      if (!confirm(`Supprimer « ${s.title} » ?`)) return;
      delete lib.ideas[s.id];
      if (s.id === score.id) {
        const next = Object.values(lib.ideas).sort((a, b) => b.updated.localeCompare(a.updated))[0];
        if (next) openIdea(next.id); else { score = newScore(); resetEditor(); render(); }
      }
      save(true); renderLib();
    });
    ul.appendChild(li);
  });
}
function openIdea(id) {
  Sound.stop();
  save(true);
  score = lib.ideas[id];
  lib.current = id;
  resetEditor();
  store.set(LIB_KEY, lib);
  render();
  renderKeyboard();
  scoreEl.scrollTo(0, 0);
}
$('#btn-lib').addEventListener('click', () => { renderLib(); openSheet('#sheet-lib'); });
$('#lib-new').addEventListener('click', () => {
  save(true);
  score = newScore();
  lib.ideas[score.id] = score;
  resetEditor(); save(true); render(); renderKeyboard(); closeSheets();
  toast('Nouvelle idée créée');
});
$('#lib-import').addEventListener('click', () => $('#import-file').click());
$('#import-file').addEventListener('change', async e => {
  const f = e.target.files[0];
  e.target.value = '';
  if (!f) return;
  try {
    const s = sanitizeScore(JSON.parse(await f.text()));
    if (lib.ideas[s.id]) s.id = uid();
    lib.ideas[s.id] = s;
    save(true);
    openIdea(s.id);
    closeSheets();
    toast(`« ${s.title} » importée`);
  } catch (err) { toast('Import impossible : ' + err.message); }
});

// Réglages
const TS_LIST = [[2, 4], [3, 4], [4, 4], [5, 4], [6, 4], [2, 2], [3, 8], [6, 8], [9, 8], [12, 8], [5, 8], [7, 8]];
function fillSettings() {
  $('#set-title').value = score.title;
  $('#set-tempo').value = score.tempo;
  const ts = $('#set-ts');
  const list = TS_LIST.some(t => t[0] === score.ts[0] && t[1] === score.ts[1]) ? TS_LIST : TS_LIST.concat([score.ts]);
  ts.innerHTML = list.map(t => `<option value="${t[0]}/${t[1]}">${t[0]}/${t[1]}</option>`).join('');
  ts.value = score.ts.join('/');
  const key = $('#set-key');
  key.innerHTML = '';
  for (let k = -7; k <= 7; k++) {
    const sig = k === 0 ? 'Sans altération' : `${Math.abs(k)} ${k > 0 ? '♯' : '♭'}`;
    key.insertAdjacentHTML('beforeend', `<option value="${k}">${sig} — ${KEY_NAMES_FR[k]}</option>`);
  }
  key.value = score.key;
  $('#set-zoom').value = settings.sp;
  $('#set-sound').checked = settings.sound;
  $('#set-kbd').checked = settings.kbd;
}
$('#btn-title').addEventListener('click', () => { fillSettings(); openSheet('#sheet-settings'); });
$('#set-title').addEventListener('input', e => { score.title = e.target.value.slice(0, 120) || 'Sans titre'; save(); renderTop(); });
$('#set-tempo').addEventListener('change', e => {
  const v = Math.max(20, Math.min(300, parseInt(e.target.value, 10) || 90));
  e.target.value = v;
  mutate(() => { score.tempo = v; });
});
$('#set-ts').addEventListener('change', e => { const [a, b] = e.target.value.split('/').map(Number); mutate(() => { score.ts = [a, b]; }); });
$('#set-key').addEventListener('change', e => { const v = +e.target.value; mutate(() => { score.key = v; }); });
$('#set-zoom').addEventListener('input', e => { settings.sp = +e.target.value; saveSettings(); render(); });
$('#set-sound').addEventListener('change', e => { settings.sound = e.target.checked; saveSettings(); });
$('#set-kbd').addEventListener('change', e => { settings.kbd = e.target.checked; saveSettings(); applyKbd(); });
$('#set-help').addEventListener('click', () => openSheet('#sheet-help'));
$('#set-clear').addEventListener('click', () => {
  if (!confirm('Effacer toutes les notes de cette idée ?')) return;
  mutate(() => { score.staves = [[], []]; st.cur = [0, 0]; st.sel = false; });
  closeSheets();
});
function applyKbd() { document.body.classList.toggle('no-kbd', !settings.kbd); requestAnimationFrame(render); }

// Export
function renderExport() {
  const grid = $('#exp-grid');
  grid.innerHTML = '';
  for (const [fmt, e] of Object.entries(EXPORTS)) {
    const b = document.createElement('button');
    b.className = 'exp-btn' + (settings.lastFmt === fmt ? ' last' : '');
    b.innerHTML = `<b>${e.label}</b><span>.${e.ext} — ${e.desc}</span>`;
    b.addEventListener('click', () => doExport(fmt, b));
    grid.appendChild(b);
  }
  document.querySelectorAll('#exp-mode button').forEach(b => {
    b.classList.toggle('on', b.dataset.mode === settings.mode);
    b.setAttribute('aria-checked', b.dataset.mode === settings.mode);
  });
}
document.querySelectorAll('#exp-mode button').forEach(b => b.addEventListener('click', () => {
  settings.mode = b.dataset.mode; saveSettings(); renderExport();
}));
$('#btn-export').addEventListener('click', () => {
  if (!score.staves[0].length && !score.staves[1].length) toast('Astuce : la partition est encore vide');
  renderExport(); openSheet('#sheet-export');
});
$('#exp-zip').addEventListener('click', e => doExport('zip', e.currentTarget));

function download(file) {
  const url = URL.createObjectURL(file);
  const a = document.createElement('a');
  a.href = url; a.download = file.name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 20000);
}

async function doExport(fmt, btn) {
  if (btn.classList.contains('busy')) return;
  btn.classList.add('busy');
  try {
    save(true);
    const file = await buildExport(fmt, score);
    if (fmt !== 'zip') { settings.lastFmt = fmt; saveSettings(); }
    if (settings.mode === 'share' && navigator.share) {
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: score.title });
        return;
      }
      if (fmt === 'abc' || fmt === 'ly') {
        await navigator.share({ title: score.title, text: await file.text() });
        return;
      }
      toast(`Le partage de fichiers .${file.name.split('.').pop()} n'est pas permis par le téléphone : téléchargement à la place`);
    }
    download(file);
    if (settings.mode !== 'share') toast(`✓ ${file.name}\nenregistré dans Téléchargements`);
  } catch (err) {
    if (err && err.name !== 'AbortError') toast('Export impossible : ' + (err.message || err));
  } finally {
    btn.classList.remove('busy');
    renderExport();
  }
}

/* ── Divers ──────────────────────────────────────────────────────────── */
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.style.whiteSpace = 'pre-line';
  t.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => t.classList.remove('show'), 2600);
}

$('#btn-undo').addEventListener('click', undo);
$('#btn-redo').addEventListener('click', redo);
$('#btn-play').addEventListener('click', togglePlay);

// Raccourcis clavier (ordinateur ou clavier Bluetooth)
document.addEventListener('keydown', e => {
  if (e.target.matches('input, select, textarea') || document.querySelector('.sheet.show')) return;
  const k = e.key, ctrl = e.ctrlKey || e.metaKey;
  if (ctrl && k.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
  if (ctrl && k.toLowerCase() === 'y') { e.preventDefault(); redo(); return; }
  if (ctrl) return;
  const letter = 'cdefgab'.indexOf(k.toLowerCase());
  if (letter >= 0 && k.length === 1) {
    const prev = score.staves[st.staff].slice(0, st.cur[st.staff]).reverse().find(ev => !ev.r);
    const ref = prev ? diOf(prev.n[prev.n.length - 1]) : (st.staff ? 3 * 7 : 5 * 7);
    let di = Math.floor(ref / 7) * 7 + letter;
    if (di - ref > 3) di -= 7; else if (ref - di > 3) di += 7;
    addPitch(st.staff, fromDi(di, score.key), e.shiftKey ? { chord: true } : {});
    return;
  }
  const durKey = { '7': 'w', '6': 'h', '5': 'q', '4': 'e', '3': 's', '2': 't' }[k];
  if (durKey) return setDur(durKey);
  const map = {
    '.': toggleDot, '0': addRest, 'r': addRest, 't': toggleTie, '+': () => setAcc(1), '-': () => setAcc(-1), '=': () => setAcc(0),
    Backspace: deleteEv, Delete: deleteEv, ArrowLeft: () => moveCursor(-1), ArrowRight: () => moveCursor(1), Tab: toggleStaff, ' ': togglePlay
  };
  if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); const d = k === 'ArrowUp' ? 1 : -1; return e.altKey || e.shiftKey ? octave(d) : stepNote(d); }
  if (map[k]) { e.preventDefault(); map[k](); }
});

let resizeT = 0;
window.addEventListener('resize', () => { clearTimeout(resizeT); resizeT = setTimeout(render, 120); });
document.addEventListener('visibilitychange', () => { if (document.hidden) { save(true); Sound.stop(); } });

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

buildPalette();
document.body.classList.toggle('no-kbd', !settings.kbd);
renderKeyboard();
render();
if (!settings.helped) { settings.helped = true; saveSettings(); setTimeout(() => openSheet('#sheet-help'), 300); }
