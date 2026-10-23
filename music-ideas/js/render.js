/* MUSIC_IDEAS — mise en page de la double portée et rendu SVG.
 *
 * layoutScore() produit une liste d'éléments graphiques indépendante du support
 * (glyphes, lignes, rectangles, chemins, textes) organisée en systèmes.
 * compose() empile les systèmes sur une ou plusieurs pages ; toSVG() / toPDF() dessinent.
 */
'use strict';

const SYS_H = 22;            // hauteur d'un système, en interlignes
const TOP = [4, 14];         // haut de chaque portée dans le système
const TITLE_H = 5.5;         // bloc titre
const SHARP_P = [8, 5, 9, 6, 3, 7, 4];
const FLAT_P = [4, 7, 3, 6, 2, 5, 1];
const ACC_GLYPH = { '-2': 'dflat', '-1': 'flat', '0': 'natural', '1': 'sharp', '2': 'dsharp' };
const FLAG_GLYPH = { e: '8th', s: '16th', t: '32nd' };
const REST_GLYPH = { w: 'restWhole', h: 'restHalf', q: 'restQuarter', e: 'rest8th', s: 'rest16th', t: 'rest32nd' };

function layoutScore(score, o) {
  const sp = o.sp, margin = o.margin != null ? o.margin : sp;
  const norm = normalize(score, { extra: o.extra });
  const { M, n, staves } = norm;
  const key = score.key, nk = Math.abs(key);
  const gw = name => GLYPHS[name].w / 100 * sp;
  const headName = d => d === 'w' ? 'noteheadWhole' : d === 'h' ? 'noteheadHalf' : 'noteheadBlack';
  const stemW = 0.12 * sp;
  const yP = (k, p) => (TOP[k] + 4) * sp - p * sp / 2;

  /* ── 1. Préparation des fragments ──────────────────────────────────── */
  staves.forEach((st, k) => st.forEach(meas => {
    for (const pc of meas.pieces) {
      if (pc.rest) { pc.accW = 0; pc.rightW = gw(REST_GLYPH[pc.d]) + (pc.dot ? 0.8 * sp : 0); continue; }
      pc.notes.forEach(nt => { nt.p = nt.di - STAFF_REF[k]; });
      pc.notes.sort((a, b) => a.p - b.p);
      pc.minP = pc.notes[0].p;
      pc.maxP = pc.notes[pc.notes.length - 1].p;
      pc.hw = gw(headName(pc.d));
      pc.dir = (pc.maxP - 4) + (pc.minP - 4) >= 0 ? -1 : 1;     // 1 = hampe en haut
    }
    for (const g of meas.beams) {
      const pcs = g.map(i => meas.pieces[i]);
      const hi = Math.max(...pcs.map(p => p.maxP)), lo = Math.min(...pcs.map(p => p.minP));
      const dir = (hi - 4) + (lo - 4) >= 0 ? -1 : 1;
      pcs.forEach(p => { p.dir = dir; p.beamed = true; });
    }
    for (const pc of meas.pieces) {
      if (pc.rest) continue;
      pc.notes.forEach(nt => { nt.shift = 0; });
      const ns = pc.notes;
      if (pc.dir === 1 || pc.d === 'w') {
        for (let i = 1; i < ns.length; i++)
          if (ns[i].p - ns[i - 1].p === 1 && !ns[i - 1].shift) ns[i].shift = 1;
      } else {
        for (let i = ns.length - 2; i >= 0; i--)
          if (ns[i + 1].p - ns[i].p === 1 && !ns[i + 1].shift) ns[i].shift = -1;
      }
      pc.rShift = ns.some(nt => nt.shift > 0);
      pc.lShift = ns.some(nt => nt.shift < 0);
      const cols = [];
      ns.filter(nt => nt.acc != null).sort((a, b) => b.p - a.p).forEach(nt => {
        let c = 0;
        while (cols[c] && cols[c].some(p => Math.abs(p - nt.p) < 6)) c++;
        (cols[c] = cols[c] || []).push(nt.p);
        nt.col = c;
      });
      pc.accW = (cols.length ? (0.25 + cols.length * 1.15) * sp : 0) + (pc.lShift ? pc.hw : 0);
      pc.rightW = pc.hw * (pc.rShift ? 2 : 1) + (pc.dot ? 0.9 * sp : 0) +
        (!pc.beamed && BEAMS[pc.d] && pc.dir === 1 ? 1.0 * sp : 0);
    }
  }));

  /* ── 2. Largeur des mesures ────────────────────────────────────────── */
  const padL = 1.0 * sp, padR = 0.6 * sp;
  const mInfo = [];
  for (let m = 0; m < n; m++) {
    const pcs = [...staves[0][m].pieces, ...staves[1][m].pieces];
    const onsets = [...new Set(pcs.map(p => p.start))].sort((a, b) => a - b);
    const slots = onsets.map((t, i) => {
      const gap = (i + 1 < onsets.length ? onsets[i + 1] : M) - t;
      const at = pcs.filter(p => p.start === t);
      const acc = Math.max(0, ...at.map(p => p.accW));
      const right = Math.max(0, ...at.map(p => p.rightW));
      const flex = sp * (1.25 + 1.5 * Math.sqrt(gap / 48));
      return { t, acc, w: flex, min: right + 0.5 * sp };
    });
    const fixed = padL + padR + slots.reduce((s, x) => s + x.acc, 0);
    const flex = slots.reduce((s, x) => s + Math.max(x.w, x.min), 0) || 5 * sp;
    mInfo.push({ slots, fixed, flex, empty: !slots.length });
  }

  const tsDigits = [String(score.ts[0]), String(score.ts[1])];
  const digW = s => [...s].reduce((w, c) => w + gw('t' + c), 0);
  const tsW = Math.max(digW(tsDigits[0]), digW(tsDigits[1]));
  const keyW = nk ? nk * (key > 0 ? 1.0 : 0.95) * sp + 0.5 * sp : 0;
  const headerW = first => 0.5 * sp + gw('gClef') + 0.7 * sp + keyW + (first ? tsW + 0.9 * sp : 0) + 0.3 * sp;

  /* ── 3. Découpage en systèmes ──────────────────────────────────────── */
  const x0 = margin + 1.8 * sp;
  const avail = o.width - margin - x0;
  const systems = [];
  let cur = null;
  for (let m = 0; m < n; m++) {
    const mw = mInfo[m].fixed + mInfo[m].flex;
    if (!cur || (cur.ms.length && cur.used + mw > avail)) {
      cur = { ms: [], header: headerW(!systems.length), first: !systems.length };
      cur.used = cur.header;
      systems.push(cur);
    }
    cur.ms.push(m);
    cur.used += mw;
  }
  systems.forEach((sys, si) => {
    const fixed = sys.header + sys.ms.reduce((s, m) => s + mInfo[m].fixed, 0);
    const flex = sys.ms.reduce((s, m) => s + mInfo[m].flex, 0);
    let f = (avail - fixed) / flex;
    if (si === systems.length - 1 && sys.used < avail * 0.8) f = Math.min(f, 1);
    sys.f = Math.max(f, 0.75);
  });

  /* ── 4. Dessin ─────────────────────────────────────────────────────── */
  const hits = [], evFirst = [[], []], evLast = [[], []], mStart = [];
  let maxRight = o.width;

  systems.forEach((sys, si) => {
    const items = sys.items = [];
    const G = (n, x, y, s, ev) => items.push({ t: 'g', n, x, y, s: (s || 1) * sp / 100, ev });
    const L = (x1, y1, x2, y2, w, ev) => items.push({ t: 'l', x1, y1, x2, y2, w, ev });
    const P = (d, ev) => items.push({ t: 'p', d, ev });
    const T = (x, y, str, size, anchor, extra) => items.push({ t: 't', x, y, str, size, anchor, ...extra });

    let x = x0;
    // en-tête : clés, armure, chiffrage
    G('gClef', x + 0.5 * sp, yP(0, 2));
    G('fClef', x + 0.5 * sp, yP(1, 6));
    let hx = x + 0.5 * sp + gw('gClef') + 0.7 * sp;
    for (let i = 0; i < nk; i++) {
      const name = key > 0 ? 'sharp' : 'flat';
      const p = (key > 0 ? SHARP_P : FLAT_P)[i];
      G(name, hx, yP(0, p));
      G(name, hx, yP(1, p - 2));
      hx += (key > 0 ? 1.0 : 0.95) * sp;
    }
    if (nk) hx += 0.5 * sp;
    if (sys.first) {
      for (const k of [0, 1]) tsDigits.forEach((ds, j) => {
        let dx = hx + (tsW - digW(ds)) / 2;
        for (const c of ds) { G('t' + c, dx, (TOP[k] + 1 + 2 * j) * sp); dx += gw('t' + c); }
      });
    }
    x += sys.header;
    sys.contentX = x;
    if (sys.ms[0] > 0) T(x0, (TOP[0] - 1.3) * sp, String(sys.ms[0] + 1), 1.3 * sp, 'start', { italic: true });

    for (const m of sys.ms) {
      const info = mInfo[m];
      const mx = x;
      const xs = {};
      let cx = mx + padL;
      for (const sl of info.slots) {
        cx += sl.acc;
        xs[sl.t] = cx;
        cx += Math.max(sl.w * sys.f, sl.min);
      }
      const mw = info.empty ? info.flex * sys.f + info.fixed : cx + padR - mx;
      mStart[m] = { sys: si, x: mx + padL };

      for (const k of [0, 1]) {
        const meas = staves[k][m];
        const other = staves[1 - k][m];
        if (!meas.pieces.length) {
          if (other.pieces.length) G('restWhole', mx + mw / 2 - gw('restWhole') / 2, yP(k, 6));
          continue;
        }
        // hampes des groupes ligaturés
        for (const g of meas.beams) {
          const pcs = g.map(i => meas.pieces[i]);
          const dir = pcs[0].dir;
          pcs.forEach(p => { p.x = xs[p.start]; p.sx = dir === 1 ? p.x + p.hw - stemW / 2 : p.x + stemW / 2; });
          const levels = Math.max(...pcs.map(p => BEAMS[p.d]));
          const minLen = (3.0 + 0.75 * (levels - 1)) * sp;
          const near = p => dir === 1 ? yP(k, p.maxP) : yP(k, p.minP);
          const a = pcs[0], z = pcs[pcs.length - 1];
          const ideal = p => near(p) - dir * 3.5 * sp;
          let slope = (ideal(z) - ideal(a)) / Math.max(1, z.sx - a.sx);
          slope = Math.max(-0.2, Math.min(0.2, slope));
          let b = ideal(a);
          for (const p of pcs) {
            const need = near(p) - dir * minLen - slope * (p.sx - a.sx);
            b = dir === 1 ? Math.min(b, need) : Math.max(b, need);
          }
          pcs.forEach(p => { p.stemEnd = b + slope * (p.sx - a.sx); });
          meas.beamLines = meas.beamLines || [];
          meas.beamLines.push({ pcs, b, slope, dir });
        }

        for (const pc of meas.pieces) {
          const px = xs[pc.start];
          pc.x = px;
          const ev = k + '-' + pc.idx;
          if (pc.rest) {
            const gname = REST_GLYPH[pc.d];
            G(gname, px, yP(k, pc.d === 'w' ? 6 : 4), 1, ev);
            if (pc.dot) G('dot', px + gw(gname) + 0.3 * sp, yP(k, 5), 1, ev);
            pc.pos = { sys: si, notes: {} };
            hits.push({ sys: si, k, idx: pc.idx, x1: px - 0.4 * sp, x2: px + gw(gname) + 0.4 * sp,
              y1: yP(k, 8) , y2: yP(k, 0) });
          } else {
            const up = pc.dir === 1, hw = pc.hw;
            const ns = pc.notes;
            // lignes supplémentaires
            const lx1 = px - 0.35 * sp - (pc.lShift ? hw : 0), lx2 = px + hw + 0.35 * sp + (pc.rShift ? hw : 0);
            for (let lp = -2; lp >= pc.minP; lp -= 2) L(lx1, yP(k, lp), lx2, yP(k, lp), 0.16 * sp, ev);
            for (let lp = 10; lp <= pc.maxP; lp += 2) L(lx1, yP(k, lp), lx2, yP(k, lp), 0.16 * sp, ev);
            pc.pos = { sys: si, notes: {} };
            const dots = new Set();
            for (const nt of ns) {
              const nx = nt.shift > 0 ? px + hw - stemW : nt.shift < 0 ? px - hw + stemW : px;
              G(headName(pc.d), nx, yP(k, nt.p), 1, ev);
              pc.pos.notes[nt.midi] = { x: nx, r: nx + hw, y: yP(k, nt.p) };
              if (nt.acc != null) {
                const an = ACC_GLYPH[nt.acc];
                G(an, px - (pc.lShift ? hw : 0) - 0.2 * sp - nt.col * 1.15 * sp - gw(an), yP(k, nt.p), 1, ev);
              }
              if (pc.dot) dots.add(nt.p % 2 === 0 ? nt.p + 1 : nt.p);
            }
            const dx = px + hw * (pc.rShift ? 2 : 1) + 0.3 * sp;
            dots.forEach(p => G('dot', dx, yP(k, p), 1, ev));
            if (pc.d !== 'w') {
              const sx = up ? px + hw - stemW / 2 : px + stemW / 2;
              const y1 = up ? yP(k, pc.minP) - 0.17 * sp : yP(k, pc.maxP) + 0.17 * sp;
              let y2 = pc.stemEnd;
              if (!pc.beamed) {
                const extra = { s: 0.5, t: 1.2 }[pc.d] || 0;
                y2 = up ? Math.min(yP(k, pc.maxP) - (3.5 + extra) * sp, yP(k, 4))
                        : Math.max(yP(k, pc.minP) + (3.5 + extra) * sp, yP(k, 4));
                pc.stemEnd = y2;
              }
              L(sx, y1, sx, y2, stemW, ev);
              if (!pc.beamed && FLAG_GLYPH[pc.d])
                G('flag' + FLAG_GLYPH[pc.d] + (up ? 'Up' : 'Down'), sx - stemW / 2, y2, 1, ev);
            }
            hits.push({ sys: si, k, idx: pc.idx, x1: px - 0.5 * sp - (pc.lShift ? hw : 0),
              x2: px + hw * (pc.rShift ? 2 : 1) + 0.5 * sp, y1: yP(k, pc.maxP) - 1.5 * sp, y2: yP(k, pc.minP) + 1.5 * sp });
          }
          if (pc.first) evFirst[k][pc.idx] = { sys: si, x: px - pc.accW };
          evLast[k][pc.idx] = { sys: si, x: px + pc.rightW };
        }

        // ligatures
        for (const bl of meas.beamLines || []) {
          const { pcs, b, slope, dir } = bl;
          const th = 0.5 * sp, a = pcs[0];
          const yAt = (xx, lvl) => b + slope * (xx - a.sx) + dir * lvl * 0.75 * sp;
          const seg = (xa, xb, lvl, ev) => {
            const ya = yAt(xa, lvl), yb = yAt(xb, lvl);
            P(`M${xa} ${ya}L${xb} ${yb}L${xb} ${yb + dir * th}L${xa} ${ya + dir * th}Z`, ev);
          };
          const ev = k + '-' + a.idx;
          const L0 = a.sx - stemW / 2, ex = p => p.sx + stemW / 2;
          seg(L0, ex(pcs[pcs.length - 1]), 0, ev);
          for (let lvl = 1; lvl < 3; lvl++) {
            pcs.forEach((p, i) => {
              if (BEAMS[p.d] <= lvl) return;
              const nx = pcs[i + 1], pv = pcs[i - 1];
              if (nx && BEAMS[nx.d] > lvl) seg(p.sx - stemW / 2, ex(nx), lvl, ev);
              else if (!pv || BEAMS[pv.d] <= lvl) {
                const len = 1.1 * sp;
                if (nx || !pv) seg(p.sx - stemW / 2, p.sx + len, lvl, ev);
                else seg(p.sx - len, ex(p), lvl, ev);
              }
            });
          }
        }
        delete meas.beamLines;

        // triolets
        for (const tg of meas.tuplets) {
          const pcs = tg.map(i => meas.pieces[i]);
          const a = pcs[0], z = pcs[pcs.length - 1];
          const cxm = (a.x + z.x + (z.hw || gw('restQuarter'))) / 2;
          const notes = pcs.filter(p => !p.rest);
          const dir = notes.length ? notes[0].dir : 1;
          let y;
          if (dir === 1) y = Math.min(yP(k, 9), ...notes.map(p => Math.min(p.stemEnd != null ? p.stemEnd : 1e9, yP(k, p.maxP)))) - 0.6 * sp;
          else y = Math.max(yP(k, -1), ...notes.map(p => Math.max(p.stemEnd != null ? p.stemEnd : -1e9, yP(k, p.minP)))) + 1.9 * sp;
          G('tuplet3', cxm - gw('tuplet3') * 0.4, y, 0.8, k + '-' + a.idx);
        }
      }

      x = mx + mw;
      const last = m === n - 1;
      if (last) {
        L(x - 0.75 * sp, TOP[0] * sp, x - 0.75 * sp, (TOP[1] + 4) * sp, 0.16 * sp);
        L(x - 0.25 * sp, TOP[0] * sp, x - 0.25 * sp, (TOP[1] + 4) * sp, 0.5 * sp);
      } else L(x, TOP[0] * sp, x, (TOP[1] + 4) * sp, 0.16 * sp);
    }
    sys.right = x;
    maxRight = Math.max(maxRight, x + margin);
    // portées, barre initiale, accolade (dessinées sous les notes)
    const under = [];
    for (const k of [0, 1]) for (let l = 0; l < 5; l++)
      under.push({ t: 'l', x1: x0, y1: (TOP[k] + l) * sp, x2: x, y2: (TOP[k] + l) * sp, w: 0.11 * sp });
    under.push({ t: 'l', x1: x0, y1: TOP[0] * sp, x2: x0, y2: (TOP[1] + 4) * sp, w: 0.16 * sp });
    under.push({ t: 'g', n: 'brace', x: x0 - 1.35 * sp, y: (TOP[1] + 4) * sp, s: 3.5 * sp / 100 });
    sys.items = under.concat(items);
  });

  /* ── 5. Liaisons de prolongation ───────────────────────────────────── */
  const tie = (sys, x1, x2, y, side) => {
    if (x2 - x1 < 0.4 * sp) x2 = x1 + 0.4 * sp;
    const h = side * Math.min(0.45 * sp + 0.04 * (x2 - x1), 1.3 * sp), th = side * 0.16 * sp;
    const q = (x2 - x1) / 4;
    systems[sys].items.push({ t: 'p', d: `M${x1} ${y}C${x1 + q} ${y + h} ${x2 - q} ${y + h} ${x2} ${y}` +
      `C${x2 - q} ${y + h - th} ${x1 + q} ${y + h - th} ${x1} ${y}Z` });
  };
  staves.forEach(st => {
    const flat = [];
    st.forEach(meas => meas.pieces.forEach(pc => flat.push(pc)));
    flat.forEach((pc, i) => {
      if (!pc.tieNext) return;
      const nx = flat[i + 1];
      if (!nx || !nx.pos) return;
      pc.notes.forEach((nt, j) => {
        if (!nt.tie) return;
        const a = pc.pos.notes[nt.midi], b = nx.pos.notes[nt.midi];
        if (!a || !b) return;
        const side = pc.notes.length > 1 ? (j >= pc.notes.length / 2 ? -1 : 1) : -pc.dir;
        const dy = side * 0.45 * sp;
        if (pc.pos.sys === nx.pos.sys) tie(pc.pos.sys, a.r + 0.1 * sp, b.x - 0.1 * sp, a.y + dy, side);
        else {
          tie(pc.pos.sys, a.r + 0.1 * sp, systems[pc.pos.sys].right - 0.2 * sp, a.y + dy, side);
          tie(nx.pos.sys, systems[nx.pos.sys].contentX - 0.8 * sp, b.x - 0.1 * sp, b.y + dy, side);
        }
      });
    });
  });

  /* ── 6. Bloc titre ─────────────────────────────────────────────────── */
  const titleItems = [];
  if (o.title) {
    titleItems.push({ t: 't', x: o.width / 2, y: 2.6 * sp, str: score.title || '', size: 2.1 * sp, anchor: 'middle', bold: true });
    const ty = 4.6 * sp;
    titleItems.push({ t: 'g', n: 'noteheadBlack', x: x0, y: ty, s: 0.75 * sp / 100 });
    titleItems.push({ t: 'l', x1: x0 + 0.84 * sp, y1: ty - 0.15 * sp, x2: x0 + 0.84 * sp, y2: ty - 2.6 * sp, w: 0.1 * sp });
    titleItems.push({ t: 't', x: x0 + 1.4 * sp, y: ty + 0.45 * sp, str: '= ' + score.tempo, size: 1.4 * sp, anchor: 'start' });
  }

  function cursorAt(k, c) {
    const evs = score.staves[k];
    if (c < evs.length && evFirst[k][c]) {
      const f = evFirst[k][c];
      return { sys: f.sys, x: f.x - 0.35 * sp };
    }
    const T = staffTicks(evs.slice(0, c));
    if (T % M === 0 && mStart[T / M]) return { sys: mStart[T / M].sys, x: mStart[T / M].x - 0.4 * sp };
    const l = evLast[k][c - 1];
    if (l) return { sys: l.sys, x: l.x + 0.25 * sp };
    return { sys: 0, x: mStart[0].x };
  }

  return { sp, width: Math.max(o.width, maxRight), systems, titleItems, hits, cursorAt, n, M, x0,
    hasTitle: !!o.title };
}

/** Empile les systèmes en pages (pageH = Infinity pour l'écran). */
function compose(lay, pageH, topMargin) {
  const sp = lay.sp, sysH = SYS_H * sp, tm = topMargin || 0;
  const pages = [];
  let page = { groups: [], h: pageH };
  let y = tm;
  if (lay.hasTitle) { page.groups.push({ dy: y, items: lay.titleItems }); y += TITLE_H * sp; }
  else y += 0.5 * sp;
  pages.push(page);
  lay.systems.forEach((sys, si) => {
    if (y + sysH > pageH - tm && page.groups.length && isFinite(pageH)) {
      page = { groups: [], h: pageH };
      pages.push(page);
      y = tm;
    }
    sys.dy = y;
    sys.page = pages.length - 1;
    page.groups.push({ dy: y, items: sys.items, sys: si });
    y += sysH;
  });
  if (!isFinite(pageH)) page.h = y + sp;
  return pages;
}

/* ── Rendu SVG ───────────────────────────────────────────────────────── */
const r2 = v => Math.round(v * 100) / 100;
function escXml(s) {
  return String(s).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c]);
}

function itemsToSVG(items) {
  let out = '', curEv = null;
  for (const it of items) {
    const ev = it.ev || null;
    if (ev !== curEv) {
      if (curEv) out += '</g>';
      if (ev) out += `<g data-ev="${ev}">`;
      curEv = ev;
    }
    switch (it.t) {
      case 'g': {
        const sy = it.sy || it.s;
        out += `<path transform="translate(${r2(it.x)} ${r2(it.y)}) scale(${+it.s.toFixed(4)}${sy !== it.s ? ' ' + sy.toFixed(4) : ''})" d="${GLYPHS[it.n].d}"/>`;
        break;
      }
      case 'l':
        out += `<line x1="${r2(it.x1)}" y1="${r2(it.y1)}" x2="${r2(it.x2)}" y2="${r2(it.y2)}" stroke-width="${r2(it.w)}"/>`;
        break;
      case 'r':
        out += `<rect x="${r2(it.x)}" y="${r2(it.y)}" width="${r2(it.w)}" height="${r2(it.h)}"/>`;
        break;
      case 'p':
        out += `<path d="${it.d.replace(/-?\d+\.\d+/g, m => r2(+m))}"/>`;
        break;
      case 't':
        out += `<text x="${r2(it.x)}" y="${r2(it.y)}" font-size="${r2(it.size)}" text-anchor="${it.anchor || 'start'}"` +
          `${it.bold ? ' font-weight="bold"' : ''}${it.italic ? ' font-style="italic"' : ''}>${escXml(it.str)}</text>`;
        break;
    }
  }
  if (curEv) out += '</g>';
  return out;
}

function pageToSVG(page, width, opts = {}) {
  const color = opts.color || 'currentColor';
  let body = '';
  for (const g of page.groups)
    body += `<g transform="translate(0 ${r2(g.dy)})"${g.sys != null ? ` data-sys="${g.sys}"` : ''}>${itemsToSVG(g.items)}</g>`;
  const bg = opts.background ? `<rect width="100%" height="100%" fill="${opts.background}"/>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${r2(width)}" height="${r2(page.h)}" viewBox="0 0 ${r2(width)} ${r2(page.h)}"` +
    ` font-family="Georgia, 'Times New Roman', serif">${bg}` +
    `<g fill="${color}">` + body.replace(/<line /g, `<line stroke="${color}" `) + `</g></svg>`;
}
