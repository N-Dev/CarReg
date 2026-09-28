// Multi-frame tracking and voting: turns noisy per-frame reads into one stable plate per vehicle.
import { validate, profileFor, decode } from './formats.js';

const argmax = (map) => {
  let best = null;
  let bw = -Infinity;
  for (const [k, w] of map) if (w > bw) { best = k; bw = w; }
  return [best, bw];
};
const add = (map, k, w) => map.set(k, (map.get(k) || 0) + w);

/**
 * Consensus over OCR reads [{ text, conf, region, regionProb }].
 * Returns { text, key, valid, conf (agreement 0..1), prob (mean OCR prob), region, regionConf, profile, n, info }.
 */
export function vote(reads, format = 'auto', minConf = 0.35) {
  const good = (reads || []).filter((r) => r && r.text && r.conf >= minConf);
  if (!good.length) return null;

  // Country: weighted by the region head's probability, ignoring "Unknown" when anything else was seen.
  const regions = new Map();
  let rTotal = 0;
  for (const r of good) {
    if (!r.region) continue;
    const w = (r.regionProb ?? 0.5) * r.conf;
    rTotal += w;
    if (r.region !== 'Unknown') add(regions, r.region, w);
  }
  const [region, rw] = argmax(regions);
  const regionConf = rTotal ? Math.max(0, rw) / rTotal : 0;
  const profile = profileFor(format, region);

  const items = good
    .map((r) => { const v = validate(r.text, profile); return { v, p: r.conf, w: r.conf * (v.valid ? 1.6 : 0.6) }; })
    .filter((x) => x.v.key);
  if (!items.length) return null;

  // 1) dominant length, 2) weighted vote per character position.
  const byLen = new Map();
  for (const x of items) add(byLen, x.v.key.length, x.w);
  const [len] = argmax(byLen);
  const same = items.filter((x) => x.v.key.length === len);
  let chars = '';
  for (let i = 0; i < len; i++) {
    const sc = new Map();
    for (const x of same) add(sc, x.v.key[i], x.w);
    chars += argmax(sc)[0];
  }
  let best = validate(chars, profile);
  if (!best.valid) {
    // Character voting produced an invalid plate: fall back to the strongest valid read, if any.
    const totals = new Map();
    for (const x of items) if (x.v.valid) add(totals, x.v.key, x.w);
    const [k] = argmax(totals);
    if (k) best = validate(k, profile);
  }
  const total = items.reduce((a, x) => a + x.w, 0) || 1;
  const agreeing = items.filter((x) => x.v.key === best.key);
  const agree = agreeing.reduce((a, x) => a + x.w, 0);
  const prob = agreeing.length ? agreeing.reduce((a, x) => a + x.p, 0) / agreeing.length : 0;
  return {
    text: best.text,
    key: best.key,
    valid: best.valid,
    format: best.format,
    conf: Math.min(1, agree / total),
    prob,
    region: region || null,
    regionConf,
    profile,
    n: items.length,
    info: decode(best.key, profile),
  };
}

const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
export function iou(a, b) {
  const w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
  const h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
  if (w <= 0 || h <= 0) return 0;
  const i = w * h;
  return i / (area(a) + area(b) - i);
}

export class Track {
  constructor(id, det, now, opts) {
    this.id = id;
    this.box = det.box.slice();
    this.shown = det.box.slice();
    this.vel = [0, 0, 0, 0];
    this.first = now;
    this.last = now;
    this.hits = 0;
    this.reads = [];
    this.result = null;
    this.confirmed = false;
    this.thumb = null;
    this.thumbScore = 0;
    this.observe(det, now, opts, true);
  }

  predict(now) {
    const dt = Math.min(Math.max(0, now - this.last), 400) / 1000;
    return this.box.map((v, k) => v + this.vel[k] * dt);
  }

  observe(det, now, opts, initial = false) {
    if (!initial) {
      const dt = Math.max(16, now - this.last) / 1000;
      for (let k = 0; k < 4; k++) this.vel[k] = this.vel[k] * 0.5 + ((det.box[k] - this.box[k]) / dt) * 0.5;
    }
    this.box = det.box.slice();
    this.last = now;
    this.hits += 1;
    this.score = det.score;
    if (det.read && det.read.text) {
      this.reads.push(det.read);
      if (this.reads.length > opts.maxReads) this.reads.shift();
      this.recompute(opts.format);
    }
    if (det.thumb) {
      const s = det.score * area(det.box) * ((det.read && det.read.conf) || 0.3);
      if (s > this.thumbScore) {
        if (this.thumb && this.thumb.close) this.thumb.close();
        this.thumb = det.thumb;
        this.thumbScore = s;
      } else if (det.thumb.close) det.thumb.close();
    }
  }

  recompute(format) { this.result = vote(this.reads, format); }

  confirmable(opts) {
    const r = this.result;
    if (!r || r.n < opts.minReads || r.conf < opts.minAgree) return false;
    return r.valid || (r.n >= 4 && r.conf >= 0.8 && r.prob >= 0.85);
  }
}

export class Tracker {
  constructor(opts = {}) {
    this.opts = { iou: 0.15, maxAge: 1200, maxReads: 24, format: 'auto', minReads: 2, minAgree: 0.6, ...opts };
    this.tracks = [];
    this.nextId = 1;
  }

  setFormat(format) {
    this.opts.format = format;
    for (const t of this.tracks) t.recompute(format);
  }

  /** dets: [{ box, score, read, thumb }]; now: ms. Returns newly confirmed and lost tracks. */
  update(dets, now) {
    const preds = this.tracks.map((t) => t.predict(now));
    const pairs = [];
    dets.forEach((d, i) => {
      preds.forEach((p, j) => {
        const o = iou(d.box, p);
        const cx = (d.box[0] + d.box[2]) / 2 - (p[0] + p[2]) / 2;
        const cy = (d.box[1] + d.box[3]) / 2 - (p[1] + p[3]) / 2;
        const size = Math.max(p[2] - p[0], p[3] - p[1], 1);
        const dist = Math.hypot(cx, cy) / size;
        const s = o >= this.opts.iou ? o : dist < 0.8 ? (0.8 - dist) * 0.1 : 0;
        if (s > 0) pairs.push([s, i, j]);
      });
    });
    pairs.sort((a, b) => b[0] - a[0]);
    const usedD = new Set();
    const usedT = new Set();
    for (const [, i, j] of pairs) {
      if (usedD.has(i) || usedT.has(j)) continue;
      usedD.add(i);
      usedT.add(j);
      this.tracks[j].observe(dets[i], now, this.opts);
    }
    dets.forEach((d, i) => {
      if (!usedD.has(i)) this.tracks.push(new Track(this.nextId++, d, now, this.opts));
    });

    const confirmed = [];
    const lost = [];
    const keep = [];
    for (const t of this.tracks) {
      if (!t.confirmed && t.confirmable(this.opts)) { t.confirmed = true; confirmed.push(t); }
      (now - t.last > this.opts.maxAge ? lost : keep).push(t);
    }
    this.tracks = keep;
    return { confirmed, lost };
  }

  /** Ends all tracks (e.g. when scanning stops) and returns them. */
  flush() {
    const all = this.tracks;
    this.tracks = [];
    for (const t of all) if (!t.confirmed && t.confirmable(this.opts)) t.confirmed = true;
    return all;
  }
}
