// Live-scan speed figures for diagnostics and tuning. For each engine setup (the CPU with n threads,
// or the GPU) and quality tier: how long frames take with a plate in view ("active") and without
// ("idle"), and how long the plate finder and plate reader take; plus a minute-by-minute timeline that
// shows a phone slowing down as it warms up. Fixed-size histograms, so memory stays flat however long
// the session runs. Pure logic (no DOM), so it's unit-tested.

const STEP = 5; // ms per histogram bucket: percentiles are within 2.5 ms

/** Histogram of frame times; frames slower than maxMs count as maxMs. */
export class Hist {
  constructor(maxMs = 2000) {
    this.n = 0;
    this.b = new Uint32Array(Math.ceil(maxMs / STEP) + 1);
  }

  add(ms) {
    if (!Number.isFinite(ms) || ms < 0) return;
    this.n++;
    this.b[Math.min(this.b.length - 1, Math.floor(ms / STEP))]++;
  }

  /** The f-quantile (0.5 = median) in whole ms, or null before the first value. */
  q(f) {
    if (!this.n) return null;
    const want = Math.max(1, Math.ceil(f * this.n));
    const last = this.b.length - 1;
    for (let i = 0, seen = 0; i <= last; i++) {
      seen += this.b[i];
      if (seen >= want) return i === last ? last * STEP : Math.round(i * STEP + STEP / 2);
    }
    return last * STEP;
  }
}

const histFor = (obj, key) => obj[key] || (obj[key] = new Hist());
const medians = (obj) => Object.fromEntries(Object.entries(obj).map(([k, h]) => [k, h.q(0.5)]));

/** Drops empty fields, so a pasted summary stays short. */
function compact(o) {
  for (const [k, v] of Object.entries(o)) if (v == null || (typeof v === 'object' && !Object.keys(v).length)) delete o[k];
  return o;
}

export class SpeedLog {
  /** minuteMs: timeline step; maxMinutes: timeline length kept (the per-tier figures keep counting). */
  constructor({ minuteMs = 60000, maxMinutes = 120 } = {}) {
    this.minuteMs = minuteMs;
    this.maxMinutes = maxMinutes;
    this.t0 = null;
    this.tLast = null;
    this.frames = 0;
    this.groups = new Map(); // `${setup}|${tier}` -> figures
    this.minutes = new Map(); // `${minute}|${setup}` -> timeline entry
  }

  /**
   * One analysed frame. t: timestamp (ms); total, finder, reader: how long it took (ms);
   * det, ocr: the plate finder and plate reader models; tier: quality tier; idle: idle mode (no plate
   * in view); plates: plates read; setup: engine setup, e.g. 'cpu-4t' or 'gpu'.
   */
  add({ t, total, finder = 0, reader = 0, det = null, ocr = null, tier, idle = false, plates = 0, setup = 'cpu' }) {
    if (this.t0 == null) this.t0 = t;
    this.tLast = t;
    this.frames++;
    const gk = `${setup}|${tier}`;
    let g = this.groups.get(gk);
    if (!g) this.groups.set(gk, (g = { frames: 0, active: new Hist(), idle: new Hist(), finder: {}, reader: {}, plates: 0 }));
    g.frames++;
    (idle ? g.idle : g.active).add(total);
    if (!idle) g.plates += plates;
    if (det) histFor(g.finder, det).add(finder);
    if (plates > 0 && ocr) histFor(g.reader, ocr).add(reader / plates);

    const m = Math.floor((t - this.t0) / this.minuteMs);
    if (m >= this.maxMinutes) return;
    const mk = `${m}|${setup}`;
    let e = this.minutes.get(mk);
    if (!e) this.minutes.set(mk, (e = { min: m, setup, frames: 0, tiers: {}, active: new Hist(), finder: {} }));
    e.frames++;
    e.tiers[tier] = (e.tiers[tier] || 0) + 1;
    if (!idle) e.active.add(total);
    if (det) histFor(e.finder, det).add(finder);
  }

  /** Plain data for the diagnostics (all times are medians in ms unless named p95), or null before any frame. */
  summary() {
    if (this.t0 == null) return null;
    const setups = {};
    for (const [gk, g] of this.groups) {
      const [setup, tier] = gk.split('|');
      (setups[setup] || (setups[setup] = {}))[tier] = compact({
        frames: g.frames,
        idleFrames: g.idle.n,
        active: g.active.q(0.5),
        activeP95: g.active.q(0.95),
        idle: g.idle.q(0.5),
        finder: medians(g.finder),
        readerPerPlate: medians(g.reader),
        platesPerActiveFrame: g.active.n ? Math.round((g.plates / g.active.n) * 100) / 100 : null,
      });
    }
    const timeline = [...this.minutes.values()]
      .sort((a, b) => a.min - b.min || a.setup.localeCompare(b.setup))
      .map((e) => compact({
        min: e.min,
        setup: e.setup,
        frames: e.frames,
        tier: Object.entries(e.tiers).sort((a, b) => b[1] - a[1])[0][0],
        active: e.active.q(0.5),
        finder: medians(e.finder),
      }));
    return { spanMin: Math.round(((this.tLast - this.t0) / 60000) * 10) / 10, frames: this.frames, setups, timeline };
  }
}
