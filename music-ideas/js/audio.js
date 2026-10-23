/* MUSIC_IDEAS — synthèse sonore simple (Web Audio) pour l'écoute des notes. */
'use strict';

const Sound = {
  ctx: null, master: null, playBus: null, raf: 0,

  ensure() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return null;
      this.ctx = new AC();
      const comp = this.ctx.createDynamicsCompressor();
      comp.threshold.value = -18; comp.ratio.value = 4;
      this.master = this.ctx.createGain();
      this.master.gain.value = 0.55;
      this.master.connect(comp).connect(this.ctx.destination);
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
    return this.ctx;
  },

  /** Note de type piano : attaque franche, décroissance, relâchement. */
  note(midi, when, dur, vel, bus) {
    const c = this.ctx, f = 440 * Math.pow(2, (midi - 69) / 12);
    const g = c.createGain(), lp = c.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.setValueAtTime(Math.min(12000, f * 8 + 800), when);
    lp.frequency.setTargetAtTime(Math.min(8000, f * 2.5 + 300), when, 0.25);
    const peak = 0.22 * vel;
    const decay = 0.35 + Math.max(0, (84 - midi)) * 0.025;
    g.gain.setValueAtTime(0.0001, when);
    g.gain.exponentialRampToValueAtTime(peak, when + 0.006);
    g.gain.setTargetAtTime(peak * 0.25, when + 0.01, decay);
    const end = when + Math.max(0.08, dur);
    g.gain.setTargetAtTime(0.0001, end, 0.07);
    g.connect(lp).connect(bus || this.master);
    const parts = [['triangle', 1, 1], ['sine', 2, 0.35], ['sine', 3, 0.12], ['sawtooth', 1, 0.05]];
    for (const [type, mul, amp] of parts) {
      const o = c.createOscillator(), og = c.createGain();
      o.type = type; o.frequency.value = f * mul; og.gain.value = amp;
      o.connect(og).connect(g);
      o.start(when); o.stop(end + 0.6);
    }
  },

  preview(midis) {
    if (!this.ensure()) return;
    const t = this.ctx.currentTime + 0.01;
    midis.forEach(m => this.note(m, t, 0.45, 0.9));
  },

  /** Joue la partition ; onTick(positions) reçoit l'indice de l'événement joué par portée. */
  play(score, onTick, onEnd) {
    if (!this.ensure()) return;
    this.stop();
    const c = this.ctx;
    const spt = 60 / score.tempo / DIV;
    const t0 = c.currentTime + 0.12;
    this.playBus = c.createGain();
    this.playBus.connect(this.master);
    const notes = noteList(score);
    for (const n of notes) this.note(n.midi, t0 + n.start * spt, (n.end - n.start) * spt, n.k ? 0.75 : 0.9, this.playBus);
    const timeline = score.staves.map(evs => { let t = 0; return evs.map(ev => { const s = t; t += evTicks(ev); return [s, t]; }); });
    const total = Math.max(0, ...timeline.map(tl => tl.length ? tl[tl.length - 1][1] : 0));
    let last = '';
    const loop = () => {
      const now = (c.currentTime - t0) / spt;
      if (now > total + DIV) { this.stop(); onTick(null); if (onEnd) onEnd(); return; }
      const pos = timeline.map(tl => tl.findIndex(([a, b]) => now >= a && now < b));
      const sig = pos.join(',');
      if (sig !== last) { last = sig; onTick(pos); }
      this.raf = requestAnimationFrame(loop);
    };
    this.raf = requestAnimationFrame(loop);
  },

  stop() {
    cancelAnimationFrame(this.raf);
    if (this.playBus) {
      const bus = this.playBus;
      bus.gain.setTargetAtTime(0, this.ctx.currentTime, 0.02);
      setTimeout(() => bus.disconnect(), 300);
      this.playBus = null;
    }
  },

  get playing() { return !!this.playBus; }
};
