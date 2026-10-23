/* MUSIC_IDEAS — modèle de partition (double portée piano).
 *
 * Une partition contient deux portées indépendantes (0 = clé de sol, 1 = clé de fa).
 * Chaque portée est une suite d'événements :
 *   { n: [{s, a, o}, ...], d: 'q', dot: 0|1, tup: 0|1, tie: 0|1 }  — note ou accord
 *   { r: 1, d: 'q', dot: 0|1, tup: 0|1 }                            — silence
 * s = degré (0 = Do … 6 = Si), a = altération (-2 … 2), o = octave (Do3 = do central = 4).
 * Les barres de mesure sont calculées : un événement qui déborde est coupé et lié.
 */
'use strict';

const DIV = 48;                                   // ticks par noire
const DUR = { w: 192, h: 96, q: 48, e: 24, s: 12, t: 6 };
const DUR_ORDER = ['w', 'h', 'q', 'e', 's', 't'];
const DUR_NAMES = { w: 'Ronde', h: 'Blanche', q: 'Noire', e: 'Croche', s: 'Double croche', t: 'Triple croche' };
const BEAMS = { e: 1, s: 2, t: 3 };
const STEPS = 'CDEFGAB';
const STEP_SEMI = [0, 2, 4, 5, 7, 9, 11];
const FR_STEPS = ['Do', 'Ré', 'Mi', 'Fa', 'Sol', 'La', 'Si'];
const SHARP_ORDER = [3, 0, 4, 1, 5, 2, 6];        // Fa Do Sol Ré La Mi Si
const FLAT_ORDER = [6, 2, 5, 1, 4, 0, 3];         // Si Mi La Ré Sol Do Fa
const STAFF_REF = [30, 18];                       // degré de la 1re ligne : Mi4 (sol), Sol2 (fa)
const KEY_NAMES_FR = {
  '-7': 'Do♭ maj. / La♭ min.', '-6': 'Sol♭ maj. / Mi♭ min.', '-5': 'Ré♭ maj. / Si♭ min.',
  '-4': 'La♭ maj. / Fa min.', '-3': 'Mi♭ maj. / Do min.', '-2': 'Si♭ maj. / Sol min.',
  '-1': 'Fa maj. / Ré min.', '0': 'Do maj. / La min.', '1': 'Sol maj. / Mi min.',
  '2': 'Ré maj. / Si min.', '3': 'La maj. / Fa♯ min.', '4': 'Mi maj. / Do♯ min.',
  '5': 'Si maj. / Sol♯ min.', '6': 'Fa♯ maj. / Ré♯ min.', '7': 'Do♯ maj. / La♯ min.'
};

function evTicks(ev) {
  let t = DUR[ev.d];
  if (ev.dot) t *= 1.5;
  if (ev.tup) t = t * 2 / 3;
  return t;
}

function measureTicks(ts) { return ts[0] * 192 / ts[1]; }

function staffTicks(evs) { return evs.reduce((t, ev) => t + evTicks(ev), 0); }

function keyAlter(key, s) {
  if (key > 0) return SHARP_ORDER.indexOf(s) < key ? 1 : 0;
  if (key < 0) return FLAT_ORDER.indexOf(s) < -key ? -1 : 0;
  return 0;
}

function midiOf(p) { return 12 * (p.o + 1) + STEP_SEMI[p.s] + p.a; }
function diOf(p) { return p.o * 7 + p.s; }

function fromDi(di, key) {
  const s = ((di % 7) + 7) % 7;
  return { s, a: keyAlter(key, s), o: Math.floor(di / 7) };
}

/** Orthographie d'une note MIDI, en privilégiant les notes de la tonalité. */
function fromMidi(m, key) {
  const pc = ((m % 12) + 12) % 12, o = Math.floor(m / 12) - 1;
  const cands = [];
  for (let s = 0; s < 7; s++) {
    const a = pc - STEP_SEMI[s];
    if (a >= -1 && a <= 1) cands.push({ s, a, o });
  }
  const rank = p => (keyAlter(key, p.s) === p.a ? 0 : 3) + (p.a === 0 ? 0 : 1) +
    (p.a === (key < 0 ? -1 : 1) ? 0 : 1);
  cands.sort((x, y) => rank(x) - rank(y));
  return cands[0];
}

function accSymbol(a) { return ['𝄫', '♭', '', '♯', '𝄪'][a + 2]; }
function pitchNameFr(p) { return FR_STEPS[p.s] + accSymbol(p.a) + p.o; }
function pitchNameEn(p) { return STEPS[p.s] + ['bb', 'b', '', '#', '##'][p.a + 2] + p.o; }

/* ── Découpage d'une durée en valeurs écrivables ─────────────────────── */
const CANDS = (() => {
  const c = [];
  for (const d of DUR_ORDER) {
    c.push({ d, dot: 0, tup: 0, t: DUR[d] });
    c.push({ d, dot: 1, tup: 0, t: DUR[d] * 1.5 });
    if (d !== 'w') c.push({ d, dot: 0, tup: 1, t: DUR[d] * 2 / 3 });
  }
  return c.sort((a, b) => b.t - a.t);
})();

function decompose(t) {
  const out = [];
  const plainOnly = t % 6 === 0;
  while (t > 0) {
    const c = CANDS.find(c => c.t <= t && (!plainOnly || !c.tup));
    if (!c) { out.push({ d: 't', dot: 0, tup: 1, t }); break; }
    out.push(c);
    t -= c.t;
  }
  return out;
}

/* ── Normalisation : événements → mesures → fragments ────────────────── */
function splitStaff(evs, M, k) {
  const measures = [];
  let t = 0;
  evs.forEach((ev, idx) => {
    const total = evTicks(ev);
    let rem = total, first = true;
    while (rem > 0) {
      const m = Math.floor(t / M), off = t - m * M;
      while (measures.length <= m) measures.push({ pieces: [] });
      const take = Math.min(rem, M - off);
      const parts = (first && take === total)
        ? [{ d: ev.d, dot: ev.dot ? 1 : 0, tup: ev.tup ? 1 : 0, t: take }]
        : decompose(take);
      for (const pt of parts) {
        rem -= pt.t;
        measures[m].pieces.push({
          k, idx, ev, first, last: rem <= 0,
          start: t - m * M, dur: pt.t, d: pt.d, dot: pt.dot, tup: pt.tup,
          rest: !!ev.r,
          notes: ev.r ? [] : ev.n.map(p => ({ s: p.s, a: p.a, o: p.o, midi: midiOf(p), di: diOf(p) }))
        });
        t += pt.t;
        first = false;
      }
    }
  });
  return measures;
}

/**
 * opts.pad   : compléter les mesures avec des silences (exports)
 * opts.extra : indice de portée — ajoute une mesure vide en fin si cette portée est pleine
 */
function normalize(score, opts = {}) {
  const M = measureTicks(score.ts);
  const staves = score.staves.map((evs, k) => splitStaff(evs, M, k));
  let n = Math.max(1, staves[0].length, staves[1].length);
  if (opts.extra != null) {
    const tot = staffTicks(score.staves[opts.extra]);
    if (tot > 0 && tot % M === 0 && tot / M >= n) n = tot / M + 1;
  }
  for (const st of staves) while (st.length < n) st.push({ pieces: [] });

  if (opts.pad) {
    staves.forEach((st, k) => st.forEach(meas => {
      if (!meas.pieces.length) {
        meas.pieces.push({ k, idx: -1, filler: true, whole: true, rest: true, notes: [],
          start: 0, dur: M, d: 'w', dot: 0, tup: 0, first: true, last: true });
        return;
      }
      const lp = meas.pieces[meas.pieces.length - 1];
      let end = lp.start + lp.dur;
      if (end < M) for (const pt of decompose(M - end)) {
        meas.pieces.push({ k, idx: -1, filler: true, rest: true, notes: [], start: end, dur: pt.t,
          d: pt.d, dot: pt.dot, tup: pt.tup, first: true, last: true });
        end += pt.t;
      }
    }));
  }

  const compound = score.ts[1] === 8 && score.ts[0] % 3 === 0 && score.ts[0] > 3;
  const beat = compound ? 72 : (score.ts[1] === 8 ? (score.ts[0] === 3 ? 72 : 48) : 48);

  staves.forEach(st => {
    // liaisons
    const flat = [];
    st.forEach(meas => meas.pieces.forEach(pc => flat.push(pc)));
    flat.forEach((pc, i) => {
      pc.tieNext = !pc.rest && !pc.filler && (!pc.last || !!pc.ev.tie);
      if (!pc.tieNext) return;
      const nx = flat[i + 1];
      for (const nt of pc.notes) {
        const target = nx && !nx.rest ? nx.notes.find(q => q.midi === nt.midi && !q.tieStop) : null;
        if (target) { nt.tie = true; target.tieStop = true; }
      }
    });
    // altérations, ligatures, triolets
    st.forEach(meas => {
      const map = {};
      for (const pc of meas.pieces) for (const nt of pc.notes) {
        const cur = nt.di in map ? map[nt.di] : keyAlter(score.key, nt.s);
        if (nt.tieStop && pc.start === 0) { nt.acc = null; map[nt.di] = nt.a; }
        else if (cur !== nt.a) { nt.acc = nt.a; map[nt.di] = nt.a; }
        else nt.acc = null;
      }
      meas.beams = [];
      let g = [];
      const flush = () => { if (g.length > 1) meas.beams.push(g); g = []; };
      meas.pieces.forEach((pc, i) => {
        const ok = !pc.rest && BEAMS[pc.d];
        if (ok && g.length && Math.floor(pc.start / beat) === Math.floor(meas.pieces[g[0]].start / beat)) g.push(i);
        else { flush(); if (ok) g.push(i); }
      });
      flush();
      meas.tuplets = [];
      let tg = [], sum = 0, target = 0;
      meas.pieces.forEach((pc, i) => {
        if (!pc.tup) { if (tg.length) meas.tuplets.push(tg); tg = []; sum = 0; return; }
        if (!tg.length) target = DUR[pc.d] * 2;
        tg.push(i); sum += pc.dur;
        if (sum >= target) { meas.tuplets.push(tg); tg = []; sum = 0; }
      });
      if (tg.length) meas.tuplets.push(tg);
    });
  });
  return { M, n, staves, beat };
}

/** Liste des notes jouées (liaisons fusionnées), en ticks. */
function noteList(score) {
  const out = [];
  score.staves.forEach((evs, k) => {
    let t = 0, open = {};
    evs.forEach((ev, i) => {
      const d = evTicks(ev);
      const next = {};
      if (!ev.r) {
        const nxt = evs[i + 1];
        for (const p of ev.n) {
          const m = midiOf(p);
          let note = open[m];
          if (note) note.end = t + d;
          else { note = { midi: m, start: t, end: t + d, k, idx: i }; out.push(note); }
          if (ev.tie && nxt && !nxt.r && nxt.n.some(q => midiOf(q) === m)) next[m] = note;
        }
      }
      open = next;
      t += d;
    });
  });
  return out.sort((a, b) => a.start - b.start || a.midi - b.midi);
}

/* ── Partitions ──────────────────────────────────────────────────────── */
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

function newScore() {
  const d = new Date();
  const title = 'Idée du ' + d.toLocaleDateString('fr-FR') + ' ' +
    d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  return { app: 'MUSIC_IDEAS', v: 1, id: uid(), title, tempo: 90, ts: [4, 4], key: 0,
    staves: [[], []], created: d.toISOString(), updated: d.toISOString() };
}

function sanitizeScore(o) {
  if (!o || typeof o !== 'object' || !Array.isArray(o.staves)) throw new Error('Fichier non reconnu');
  const clampInt = (v, lo, hi, def) => { v = parseInt(v, 10); return isNaN(v) ? def : Math.max(lo, Math.min(hi, v)); };
  const ev = e => {
    const d = DUR[e.d] ? e.d : 'q';
    const base = { d, dot: e.dot ? 1 : 0, tup: e.tup ? 1 : 0 };
    if (e.r || !Array.isArray(e.n) || !e.n.length) return { r: 1, ...base };
    return { n: e.n.map(p => ({ s: clampInt(p.s, 0, 6, 0), a: clampInt(p.a, -2, 2, 0), o: clampInt(p.o, 0, 8, 4) })),
      ...base, tie: e.tie ? 1 : 0 };
  };
  const now = new Date().toISOString();
  return {
    app: 'MUSIC_IDEAS', v: 1, id: typeof o.id === 'string' ? o.id : uid(),
    title: String(o.title || 'Sans titre').slice(0, 120),
    tempo: clampInt(o.tempo, 20, 300, 90),
    ts: Array.isArray(o.ts) ? [clampInt(o.ts[0], 1, 16, 4), [2, 4, 8, 16].includes(+o.ts[1]) ? +o.ts[1] : 4] : [4, 4],
    key: clampInt(o.key, -7, 7, 0),
    staves: [0, 1].map(k => (o.staves[k] || []).map(ev)),
    created: o.created || now, updated: o.updated || now
  };
}
