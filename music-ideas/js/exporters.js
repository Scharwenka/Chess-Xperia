/* MUSIC_IDEAS — exports : MusicXML, MIDI, PDF, PNG, SVG, ABC, LilyPond, JSON, ZIP. */
'use strict';

const TYPE_XML = { w: 'whole', h: 'half', q: 'quarter', e: 'eighth', s: '16th', t: '32nd' };
const ACC_XML = { '-2': 'flat-flat', '-1': 'flat', '0': 'natural', '1': 'sharp', '2': 'double-sharp' };

function beamValues(pcs) {
  // pcs : fragments d'un groupe ligaturé → valeurs MusicXML par niveau
  return pcs.map((p, i) => {
    const out = [];
    for (let l = 1; l <= BEAMS[p.d]; l++) {
      const prev = i > 0 && BEAMS[pcs[i - 1].d] >= l, next = i < pcs.length - 1 && BEAMS[pcs[i + 1].d] >= l;
      out.push(prev && next ? 'continue' : prev ? 'end' : next ? 'begin' : (i < pcs.length - 1 ? 'forward hook' : 'backward hook'));
    }
    return out;
  });
}

/* ── MusicXML 4.0 ────────────────────────────────────────────────────── */
function toMusicXML(score) {
  const { M, n, staves } = normalize(score, { pad: true });
  const date = new Date().toISOString().slice(0, 10);
  let x = '<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n' +
    '<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">\n' +
    '<score-partwise version="4.0">\n' +
    `  <work><work-title>${escXml(score.title)}</work-title></work>\n` +
    `  <identification><encoding><software>MUSIC_IDEAS</software><encoding-date>${date}</encoding-date></encoding></identification>\n` +
    '  <part-list><score-part id="P1"><part-name>Piano</part-name>' +
    '<score-instrument id="P1-I1"><instrument-name>Piano</instrument-name></score-instrument>' +
    '<midi-instrument id="P1-I1"><midi-channel>1</midi-channel><midi-program>1</midi-program></midi-instrument>' +
    '</score-part></part-list>\n  <part id="P1">\n';
  for (let m = 0; m < n; m++) {
    x += `    <measure number="${m + 1}">\n`;
    if (m === 0) {
      x += `      <attributes><divisions>${DIV}</divisions><key><fifths>${score.key}</fifths></key>` +
        `<time><beats>${score.ts[0]}</beats><beat-type>${score.ts[1]}</beat-type></time><staves>2</staves>` +
        '<clef number="1"><sign>G</sign><line>2</line></clef><clef number="2"><sign>F</sign><line>4</line></clef></attributes>\n' +
        '      <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit>' +
        `<per-minute>${score.tempo}</per-minute></metronome></direction-type><staff>1</staff><sound tempo="${score.tempo}"/></direction>\n`;
    }
    [0, 1].forEach(k => {
      const meas = staves[k][m];
      const voice = k === 0 ? 1 : 5, staff = k + 1;
      if (k === 1) x += `      <backup><duration>${M}</duration></backup>\n`;
      const beams = {}, tupS = {}, tupE = {};
      for (const g of meas.beams) {
        const vals = beamValues(g.map(i => meas.pieces[i]));
        g.forEach((i, j) => { beams[i] = vals[j]; });
      }
      for (const tg of meas.tuplets) { tupS[tg[0]] = true; tupE[tg[tg.length - 1]] = true; }
      meas.pieces.forEach((pc, i) => {
        const common = (pc.whole ? '' : `<type>${TYPE_XML[pc.d]}</type>${pc.dot ? '<dot/>' : ''}`);
        const tm = pc.tup ? '<time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>' : '';
        const tupN = (tupS[i] ? '<tuplet type="start" bracket="yes"/>' : '') + (tupE[i] ? '<tuplet type="stop"/>' : '');
        if (pc.rest) {
          x += `      <note>${pc.whole ? '<rest measure="yes"/>' : '<rest/>'}<duration>${pc.dur}</duration>` +
            `<voice>${voice}</voice>${common}${tm}<staff>${staff}</staff>${tupN ? `<notations>${tupN}</notations>` : ''}</note>\n`;
          return;
        }
        pc.notes.slice().sort((a, b) => a.midi - b.midi).forEach((nt, j) => {
          let s = '      <note>' + (j ? '<chord/>' : '') +
            `<pitch><step>${STEPS[nt.s]}</step>${nt.a ? `<alter>${nt.a}</alter>` : ''}<octave>${nt.o}</octave></pitch>` +
            `<duration>${pc.dur}</duration>` +
            (nt.tieStop ? '<tie type="stop"/>' : '') + (nt.tie ? '<tie type="start"/>' : '') +
            `<voice>${voice}</voice>${common}` +
            (nt.acc != null ? `<accidental>${ACC_XML[nt.acc]}</accidental>` : '') + tm +
            `<staff>${staff}</staff>`;
          if (!j && beams[i]) beams[i].forEach((v, l) => { s += `<beam number="${l + 1}">${v}</beam>`; });
          const nots = (nt.tieStop ? '<tied type="stop"/>' : '') + (nt.tie ? '<tied type="start"/>' : '') + (j ? '' : tupN);
          if (nots) s += `<notations>${nots}</notations>`;
          x += s + '</note>\n';
        });
      });
    });
    if (m === n - 1) x += '      <barline location="right"><bar-style>light-heavy</bar-style></barline>\n';
    x += '    </measure>\n';
  }
  return x + '  </part>\n</score-partwise>\n';
}

/* ── MIDI (format 1, 480 ticks/noire) ────────────────────────────────── */
function toMIDI(score) {
  const PPQ = 480, f = PPQ / DIV;
  const vlq = v => { const b = [v & 0x7f]; while ((v >>= 7)) b.unshift((v & 0x7f) | 0x80); return b; };
  const str = s => [...unescape(encodeURIComponent(s))].map(c => c.charCodeAt(0));
  const track = evs => {
    evs.sort((a, b) => a.t - b.t || a.o - b.o);
    const data = []; let last = 0;
    for (const e of evs) { data.push(...vlq(e.t - last), ...e.b); last = e.t; }
    data.push(0, 0xff, 0x2f, 0);
    return [0x4d, 0x54, 0x72, 0x6b, (data.length >>> 24) & 255, (data.length >>> 16) & 255, (data.length >>> 8) & 255, data.length & 255, ...data];
  };
  const us = Math.round(60000000 / score.tempo);
  const name = str(score.title);
  const t0 = [
    { t: 0, o: 0, b: [0xff, 0x03, ...vlq(name.length), ...name] },
    { t: 0, o: 0, b: [0xff, 0x51, 3, (us >> 16) & 255, (us >> 8) & 255, us & 255] },
    { t: 0, o: 0, b: [0xff, 0x58, 4, score.ts[0], Math.log2(score.ts[1]), 24, 8] },
    { t: 0, o: 0, b: [0xff, 0x59, 2, score.key & 255, 0] }
  ];
  const notes = noteList(score);
  const tracks = [0, 1].map(k => {
    const nm = str(k ? 'Main gauche' : 'Main droite');
    const evs = [{ t: 0, o: 0, b: [0xff, 0x03, ...vlq(nm.length), ...nm] }, { t: 0, o: 0, b: [0xc0 | k, 0] }];
    for (const nt of notes.filter(q => q.k === k)) {
      evs.push({ t: Math.round(nt.start * f), o: 2, b: [0x90 | k, nt.midi, k ? 72 : 84] });
      evs.push({ t: Math.round(nt.end * f), o: 1, b: [0x80 | k, nt.midi, 64] });
    }
    return track(evs);
  });
  const head = [0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, 0, 3, PPQ >> 8, PPQ & 255];
  return new Uint8Array([...head, ...track(t0), ...tracks[0], ...tracks[1]]);
}

/* ── ABC ─────────────────────────────────────────────────────────────── */
const ABC_KEYS = ['Cb', 'Gb', 'Db', 'Ab', 'Eb', 'Bb', 'F', 'C', 'G', 'D', 'A', 'E', 'B', 'F#', 'C#'];

function abcLen(ticks) {   // L:1/16 → 12 ticks
  const num = ticks, den = 12;
  const g = (a, b) => b ? g(b, a % b) : a;
  const d = g(num, den), p = num / d, q = den / d;
  if (q === 1) return p === 1 ? '' : String(p);
  if (p === 1) return q === 2 ? '/' : '/' + q;
  return p + '/' + q;
}

function toABC(score) {
  const { M, n, staves } = normalize(score, { pad: true });
  const pitch = nt => {
    let s = nt.o >= 5 ? STEPS[nt.s].toLowerCase() : STEPS[nt.s];
    if (nt.o > 5) s += "'".repeat(nt.o - 5);
    if (nt.o < 4) s += ','.repeat(4 - nt.o);
    return s;
  };
  const voices = staves.map(st => {
    const bars = st.map(meas => {
      const map = {};
      let out = '', tupLeft = 0;
      for (const pc of meas.pieces) {
        if (pc.whole) { out += 'z' + abcLen(M); continue; }
        const written = DUR[pc.d] * (pc.dot ? 1.5 : 1);
        if (pc.tup && tupLeft === 0) { out += '(3'; tupLeft = 3; }
        if (pc.tup) tupLeft--;
        else tupLeft = 0;
        const len = abcLen(written);
        if (pc.rest) { out += 'z' + len; continue; }
        const one = pc.notes.slice().sort((a, b) => a.midi - b.midi).map(nt => {
          const cur = nt.di in map ? map[nt.di] : keyAlter(score.key, nt.s);
          let acc = '';
          if (cur !== nt.a) { acc = ['__', '_', '=', '^', '^^'][nt.a + 2]; map[nt.di] = nt.a; }
          return acc + pitch(nt);
        });
        const tie = pc.notes.some(nt => nt.tie) ? '-' : '';
        out += (one.length > 1 ? '[' + one.join('') + ']' : one[0]) + len + tie;
        if (pc.d === 'q' || pc.d === 'h' || pc.d === 'w' || (pc.start + pc.dur) % 48 === 0) out += ' ';
      }
      return out.trim();
    });
    let s = '';
    bars.forEach((b, i) => { s += b + (i === bars.length - 1 ? ' |]' : ' | ') + ((i + 1) % 4 === 0 && i < bars.length - 1 ? '\n' : ''); });
    return s;
  });
  const lines = voices.map(v => v.split('\n'));
  let body = '';
  for (let i = 0; i < lines[0].length; i++) body += `[V:RH] ${lines[0][i]}\n[V:LH] ${lines[1][i]}\n`;
  return `X:1\nT:${score.title}\nC:MUSIC_IDEAS\nM:${score.ts[0]}/${score.ts[1]}\nL:1/16\nQ:1/4=${score.tempo}\n` +
    `%%score {RH | LH}\nV:RH clef=treble name="Piano"\nV:LH clef=bass\nK:${ABC_KEYS[score.key + 7]}\n` + body;
}

/* ── LilyPond ────────────────────────────────────────────────────────── */
const LY_KEYS = ['ces', 'ges', 'des', 'aes', 'ees', 'bes', 'f', 'c', 'g', 'd', 'a', 'e', 'b', 'fis', 'cis'];
const LY_DUR = { w: '1', h: '2', q: '4', e: '8', s: '16', t: '32' };

function toLilyPond(score) {
  const { n, staves } = normalize(score, { pad: true });
  const pitch = nt => 'cdefgab'[nt.s] + ['eses', 'es', '', 'is', 'isis'][nt.a + 2] +
    (nt.o >= 3 ? "'".repeat(nt.o - 3) : ','.repeat(3 - nt.o));
  const music = staves.map(st => st.map(meas => {
    let out = '', inTup = false;
    for (const pc of meas.pieces) {
      if (pc.tup && !inTup) { out += '\\tuplet 3/2 { '; inTup = true; }
      if (!pc.tup && inTup) { out += '} '; inTup = false; }
      if (pc.whole) { out += `R1*${score.ts[0]}/${score.ts[1]} `; continue; }
      const d = LY_DUR[pc.d] + (pc.dot ? '.' : '');
      if (pc.rest) { out += 'r' + d + ' '; continue; }
      const ps = pc.notes.slice().sort((a, b) => a.midi - b.midi).map(pitch);
      out += (ps.length > 1 ? '<' + ps.join(' ') + '>' : ps[0]) + d + (pc.notes.some(nt => nt.tie) ? '~' : '') + ' ';
    }
    if (inTup) out += '} ';
    return out + '|';
  }));
  const staff = (k, clef) => `    \\new Staff = "${k ? 'lh' : 'rh'}" {\n      \\clef ${clef}\n      \\global\n` +
    music[k].map((b, i) => `      ${b}  % ${i + 1}`).join('\n') + '\n      \\bar "|."\n    }\n';
  return `\\version "2.24.0"\n\n\\header {\n  title = "${score.title.replace(/["\\]/g, '')}"\n  tagline = "MUSIC_IDEAS"\n}\n\n` +
    `global = {\n  \\key ${LY_KEYS[score.key + 7]} \\major\n  \\time ${score.ts[0]}/${score.ts[1]}\n  \\tempo 4 = ${score.tempo}\n}\n\n` +
    `\\score {\n  \\new PianoStaff \\with { instrumentName = "Piano" } <<\n` + staff(0, 'treble') + staff(1, 'bass') +
    `  >>\n  \\layout { }\n  \\midi { }\n}\n`;
}

/* ── SVG / PNG ───────────────────────────────────────────────────────── */
function toSVGFile(score) {
  const lay = layoutScore(score, { width: 1000, sp: 10, title: true, margin: 30 });
  const page = compose(lay, Infinity, 20)[0];
  return '<?xml version="1.0" encoding="UTF-8"?>\n' + pageToSVG(page, lay.width, { color: '#000', background: '#fff' });
}

function svgToPNG(svg, scale) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    img.onload = () => {
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * scale); c.height = Math.round(img.height * scale);
      const g = c.getContext('2d');
      g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
      g.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      c.toBlob(b => b ? b.arrayBuffer().then(a => resolve(new Uint8Array(a))) : reject(new Error('PNG')), 'image/png');
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Rendu PNG impossible')); };
    img.src = url;
  });
}

function toPNG(score) {
  const lay = layoutScore(score, { width: 1000, sp: 10, title: true, margin: 30 });
  const page = compose(lay, Infinity, 20)[0];
  return svgToPNG(pageToSVG(page, lay.width, { color: '#000', background: '#fff' }), 2);
}

/* ── PDF vectoriel (A4) ──────────────────────────────────────────────── */
const CP1252 = { '€': 128, '‚': 130, 'ƒ': 131, '„': 132, '…': 133, '†': 134, '‡': 135, 'ˆ': 136, '‰': 137, 'Š': 138, '‹': 139,
  'Œ': 140, 'Ž': 142, '‘': 145, '’': 146, '“': 147, '”': 148, '•': 149, '–': 150, '—': 151, '˜': 152, '™': 153, 'š': 154,
  '›': 155, 'œ': 156, 'ž': 158, 'Ÿ': 159 };

function pdfStr(s) {
  let out = '';
  for (const ch of s) {
    let c = ch.codePointAt(0);
    if (CP1252[ch]) c = CP1252[ch];
    else if (c > 255 || (c >= 128 && c < 160)) c = 63;
    const k = String.fromCharCode(c);
    out += (k === '(' || k === ')' || k === '\\') ? '\\' + k : k;
  }
  return '(' + out + ')';
}

function textWidth(s, size, bold) {
  let w = 0;
  for (const ch of s) w += /[A-Z]/.test(ch) ? 0.69 : /[mwMW]/.test(ch) ? 0.8 : /[ilj.,'!|:;]/.test(ch) ? 0.28 : ch === ' ' ? 0.25 : 0.49;
  return w * size * (bold ? 1.05 : 1);
}

function pathToPdf(d, tx, ty, sx, sy) {
  const n = v => (Math.round(v * 100) / 100).toString();
  const tok = d.match(/[MLHVCZ]|-?\d*\.?\d+(?:e[-+]?\d+)?/gi) || [];
  let i = 0, cx = 0, cy = 0, cmd = '', out = '';
  const num = () => parseFloat(tok[i++]);
  const P = (x, y) => n(tx + x * sx) + ' ' + n(ty + y * sy);
  while (i < tok.length) {
    if (/[A-Za-z]/.test(tok[i])) cmd = tok[i++];
    switch (cmd) {
      case 'M': cx = num(); cy = num(); out += P(cx, cy) + ' m\n'; cmd = 'L'; break;
      case 'L': cx = num(); cy = num(); out += P(cx, cy) + ' l\n'; break;
      case 'H': cx = num(); out += P(cx, cy) + ' l\n'; break;
      case 'V': cy = num(); out += P(cx, cy) + ' l\n'; break;
      case 'C': { const a = num(), b = num(), c = num(), e = num(); cx = num(); cy = num();
        out += P(a, b) + ' ' + P(c, e) + ' ' + P(cx, cy) + ' c\n'; break; }
      case 'Z': case 'z': out += 'h\n'; cmd = ''; break;
      default: i++;
    }
  }
  return out;
}

function toPDF(score) {
  const W = 595.28, H = 841.89, margin = 42;
  const lay = layoutScore(score, { width: W, sp: 6.3, title: true, margin });
  const pages = compose(lay, H, margin);
  const n = v => (Math.round(v * 100) / 100).toString();
  const streams = pages.map((pg, pi) => {
    let s = `1 0 0 -1 0 ${n(H)} cm\n0 g 0 G\n`;
    for (const grp of pg.groups) {
      s += `q 1 0 0 1 0 ${n(grp.dy)} cm\n`;
      for (const it of grp.items) {
        if (it.t === 'g') s += pathToPdf(GLYPHS[it.n].d, it.x, it.y, it.s, it.sy || it.s) + 'f\n';
        else if (it.t === 'l') s += `${n(it.w)} w ${n(it.x1)} ${n(it.y1)} m ${n(it.x2)} ${n(it.y2)} l S\n`;
        else if (it.t === 'r') s += `${n(it.x)} ${n(it.y)} ${n(it.w)} ${n(it.h)} re f\n`;
        else if (it.t === 'p') s += pathToPdf(it.d, 0, 0, 1, 1) + 'f\n';
        else if (it.t === 't') {
          const font = it.bold ? '/F2' : it.italic ? '/F3' : '/F1';
          const w = textWidth(it.str, it.size, it.bold);
          const x = it.anchor === 'middle' ? it.x - w / 2 : it.anchor === 'end' ? it.x - w : it.x;
          s += `BT ${font} ${n(it.size)} Tf 1 0 0 -1 ${n(x)} ${n(it.y)} Tm ${pdfStr(it.str)} Tj ET\n`;
        }
      }
      s += 'Q\n';
    }
    if (pages.length > 1) {
      const lbl = `${pi + 1} / ${pages.length}`;
      s += `BT /F1 8 Tf 1 0 0 -1 ${n(W / 2 - textWidth(lbl, 8) / 2)} ${n(H - 20)} Tm ${pdfStr(lbl)} Tj ET\n`;
    }
    return s;
  });

  const objs = [];
  const np = pages.length;
  objs[1] = '<< /Type /Catalog /Pages 2 0 R >>';
  objs[2] = `<< /Type /Pages /Kids [${pages.map((_, i) => `${6 + 2 * i} 0 R`).join(' ')}] /Count ${np} >>`;
  objs[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>';
  objs[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>';
  objs[5] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Italic /Encoding /WinAnsiEncoding >>';
  streams.forEach((st, i) => {
    objs[6 + 2 * i] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(W)} ${n(H)}] ` +
      `/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${7 + 2 * i} 0 R >>`;
    objs[7 + 2 * i] = `<< /Length ${st.length} >>\nstream\n${st}endstream`;
  });
  const info = objs.length;
  objs[info] = `<< /Title ${pdfStr(score.title)} /Producer (MUSIC_IDEAS) /CreationDate (D:${new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)}) >>`;
  let out = '%PDF-1.4\n%\xe2\xe3\xcf\xd3\n';
  const offs = [];
  for (let i = 1; i < objs.length; i++) { offs[i] = out.length; out += `${i} 0 obj\n${objs[i]}\nendobj\n`; }
  const xref = out.length;
  out += `xref\n0 ${objs.length}\n0000000000 65535 f \n` + offs.slice(1).map(o => String(o).padStart(10, '0') + ' 00000 n \n').join('');
  out += `trailer\n<< /Size ${objs.length} /Root 1 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  const bytes = new Uint8Array(out.length);
  for (let i = 0; i < out.length; i++) bytes[i] = out.charCodeAt(i) & 255;
  return bytes;
}

/* ── ZIP (sans compression) ──────────────────────────────────────────── */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) { let c = i; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[i] = c >>> 0; }
  return t;
})();
function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }

function makeZip(files) {
  const enc = new TextEncoder(), d = new Date();
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  const parts = [], central = [];
  let off = 0;
  const u16 = v => [v & 255, (v >> 8) & 255], u32 = v => [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255];
  for (const f of files) {
    const name = enc.encode(f.name), data = typeof f.data === 'string' ? enc.encode(f.data) : f.data, crc = crc32(data);
    const common = [...u16(20), ...u16(0x0800), ...u16(0), ...u16(time), ...u16(date), ...u32(crc), ...u32(data.length), ...u32(data.length), ...u16(name.length), ...u16(0)];
    const local = new Uint8Array([0x50, 0x4b, 3, 4, ...common, ...name]);
    central.push(new Uint8Array([0x50, 0x4b, 1, 2, ...u16(20), ...common, ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(off), ...name]));
    parts.push(local, data);
    off += local.length + data.length;
  }
  const cdSize = central.reduce((s, c) => s + c.length, 0);
  const end = new Uint8Array([0x50, 0x4b, 5, 6, 0, 0, 0, 0, ...u16(files.length), ...u16(files.length), ...u32(cdSize), ...u32(off), 0, 0]);
  return new Blob([...parts, ...central, end], { type: 'application/zip' });
}

/* ── Catalogue des formats ───────────────────────────────────────────── */
const EXPORTS = {
  pdf:      { label: 'PDF', ext: 'pdf', mime: 'application/pdf', desc: 'Partition à imprimer', make: toPDF },
  musicxml: { label: 'MusicXML', ext: 'musicxml', mime: 'application/vnd.recordare.musicxml+xml', desc: 'MuseScore, Sibelius, Dorico, Finale', make: toMusicXML },
  midi:     { label: 'MIDI', ext: 'mid', mime: 'audio/midi', desc: 'DAW, séquenceur, clavier', make: toMIDI },
  png:      { label: 'Image PNG', ext: 'png', mime: 'image/png', desc: 'Messagerie, réseaux', make: toPNG },
  svg:      { label: 'SVG', ext: 'svg', mime: 'image/svg+xml', desc: 'Image vectorielle', make: toSVGFile },
  abc:      { label: 'ABC', ext: 'abc', mime: 'text/plain', desc: 'Notation texte ABC', make: toABC },
  ly:       { label: 'LilyPond', ext: 'ly', mime: 'text/plain', desc: 'Gravure LilyPond / Frescobaldi', make: toLilyPond },
  json:     { label: 'Projet', ext: 'json', mime: 'application/json', desc: 'Sauvegarde ré-importable', make: s => JSON.stringify(s, null, 1) }
};

function fileBase(score) {
  const slug = (score.title || 'idee').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, 50) || 'idee';
  return 'MUSIC_IDEAS_' + slug;
}

async function buildExport(fmt, score) {
  if (fmt === 'zip') {
    const base = fileBase(score), files = [];
    for (const [k, e] of Object.entries(EXPORTS)) files.push({ name: `${base}.${e.ext}`, data: await e.make(score) });
    return new File([makeZip(files)], base + '.zip', { type: 'application/zip' });
  }
  const e = EXPORTS[fmt];
  const data = await e.make(score);
  return new File([data], `${fileBase(score)}.${e.ext}`, { type: e.mime });
}
