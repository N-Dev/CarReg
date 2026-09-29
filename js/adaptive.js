// Live-scan quality controller. Picks the detector and plate reader from measured frame times and
// CPU pressure: sharper models when the phone has headroom, back to faster ones when it's busy or hot.
// Pure logic (no DOM), so it's unit-tested.

export const TIERS = {
  // readNew: reader for plates not yet confirmed; read: for plates already confirmed (just keeping track)
  fast: { det: 'det384', read: 'ocrFast', readNew: 'ocrFast', label: 'Fast' },
  balanced: { det: 'det384', read: 'ocrFast', readNew: 'ocrAcc', label: 'Balanced' },
  sharp: { det: 'det640', read: 'ocrFast', readNew: 'ocrAcc', label: 'Sharp' },
};
export const ORDER = ['fast', 'balanced', 'sharp'];
// CPU pressure from the phone. PlateSight's own inference keeps the CPU busy, so 'serious' alone only
// holds the quality where it is; 'critical' (likely hot and throttling) steps it down. Actual slowdowns
// from throttling are caught by the frame times either way.
const HOLD = new Set(['serious', 'critical']);

export class Adaptive {
  /**
   * mode: 'auto' or a fixed tier. budgetMs: frame time we aim to stay under (10 fps).
   * Upgrades need sustained headroom; downgrades react quickly; a tier that proved too slow is
   * not retried for a while, so the quality doesn't flap.
   */
  constructor({ mode = 'auto', budgetMs = 100, upAfterMs = 3000, downAfterMs = 1200, cooldownMs = 2000, retryAfterMs = 30000 } = {}) {
    this.mode = mode;
    this.budget = budgetMs;
    this.upAfter = upAfterMs;
    this.downAfter = downAfterMs;
    this.cooldown = cooldownMs;
    this.retryAfter = retryAfterMs;
    this.tier = mode === 'auto' ? 'fast' : mode;
    this.reset(0);
    this.blocked = {};
    this.fails = {};
    this.reason = mode === 'auto' ? 'starting fast' : 'fixed in settings';
  }

  reset(t) { this.ema = 0; this.since = t; this.fastSince = null; this.slowSince = null; }

  setMode(mode, t = 0) {
    this.mode = mode;
    if (mode !== 'auto') { this.tier = mode; this.reason = 'fixed in settings'; }
    this.reset(t);
  }

  /**
   * Feed one analysed frame (ms = its analysis time). `available(tier)` says whether that tier's models
   * are loaded. Returns { tier, want } where `want` is a better tier worth loading in the background.
   */
  observe(ms, t, { pressure = null, available = () => true } = {}) {
    if (this.mode !== 'auto') return { tier: this.tier, want: null };
    this.ema = this.ema ? this.ema * 0.8 + ms * 0.2 : ms;
    const i = ORDER.indexOf(this.tier);
    const settled = t - this.since >= this.cooldown;

    if (pressure === 'critical' && i > 0 && settled) return this._move(i - 1, t, 'phone is under critical load (hot)');

    const slow = this.ema > this.budget * 1.15;
    this.slowSince = slow ? (this.slowSince ?? t) : null;
    if (slow && i > 0 && settled && t - this.slowSince >= this.downAfter) {
      // Each time a tier proves too slow, wait twice as long before trying it again (up to 16x).
      const n = (this.fails[this.tier] = (this.fails[this.tier] || 0) + 1);
      this.blocked[this.tier] = t + this.retryAfter * 2 ** Math.min(4, n - 1);
      return this._move(i - 1, t, `${Math.round(this.ema)} ms per frame is too slow`);
    }

    const fast = this.ema < this.budget * 0.55 && !HOLD.has(pressure);
    this.fastSince = fast ? (this.fastSince ?? t) : null;
    const next = ORDER[i + 1];
    if (fast && next && !(this.blocked[next] > t) && settled && t - this.fastSince >= this.upAfter) {
      if (!available(next)) return { tier: this.tier, want: next };
      return this._move(i + 1, t, `headroom (${Math.round(this.ema)} ms per frame)`);
    }
    return { tier: this.tier, want: null };
  }

  _move(i, t, reason) {
    const from = this.tier;
    this.tier = ORDER[i];
    this.reason = reason;
    this.reset(t);
    return { tier: this.tier, want: null, changed: { from, to: this.tier, reason } };
  }
}

/** Idle mode: after `idleMs` without any plate in view, analyse only every `idleFrameMs`. */
export class Idle {
  constructor({ idleMs = 2500, idleFrameMs = 250 } = {}) { this.idleMs = idleMs; this.frameMs = idleFrameMs; this.lastSeen = null; }
  observe(detections, t) { if (detections > 0 || this.lastSeen == null) this.lastSeen = t; return this.isIdle(t); }
  isIdle(t) { return this.lastSeen != null && t - this.lastSeen > this.idleMs; }
  wake(t) { this.lastSeen = t; }
}
