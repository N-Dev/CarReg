// Plates confirmed during one live scan or one video, keyed by plate. Follows each track, so if a
// track's reading improves later its entry moves with it, and keeps a photo that matches the text.
// In debug mode each entry also keeps what the read inspector and field test need.
import { bitmapToDataURL } from './ui.js';
import { score } from './ctx.js';

/** Closes the images attached to a detection that won't be used. */
export function closeDet(d) { for (const k of ['thumb', 'crop', 'ocrInput']) if (d[k] && d[k].close) d[k].close(); }

export class Board {
  /** max: most plates kept (oldest dropped first). Live scanning caps it; a video keeps every plate. */
  constructor({ max = Infinity } = {}) { this.map = new Map(); this.byTrack = new Map(); this.max = max; }

  put(t, debug = false) {
    const r = t.result;
    if (!r) return null;
    const prev = this.byTrack.get(t.id);
    if (prev && prev !== r.key) {
      const pe = this.map.get(prev);
      if (pe) { pe.tracks.delete(t.id); if (!pe.tracks.size) this.map.delete(prev); }
    }
    let e = this.map.get(r.key);
    if (!e) {
      e = { key: r.key, r, rTrack: t.id, first: t.first, last: t.last, tracks: new Set(), thumbURL: null, thumbScore: 0, brief: !t.confirmed, created: performance.now() };
      this.map.set(r.key, e);
      // Plates are saved to history when their track ends, so dropping old ones here loses nothing.
      if (this.map.size > this.max) this.remove(this.map.keys().next().value);
    }
    e.tracks.add(t.id);
    this.byTrack.set(t.id, r.key);
    if (score(r) >= score(e.r) || e.r.n < r.n) { e.r = r; e.rTrack = t.id; }
    e.first = Math.min(e.first, t.first);
    e.last = Math.max(e.last, t.last);
    e.brief = e.brief && !t.confirmed;
    // Photo from a frame that read this plate, so the picture always matches the text.
    const th = t.thumbFor(r.key);
    if (th && th.score > e.thumbScore) {
      try { e.thumbURL = bitmapToDataURL(th.bmp); e.thumbScore = th.score; } catch (_) { /* closed bitmap */ }
    }
    if (debug) this._debug(e, t, r);
    return e;
  }

  _debug(e, t, r) {
    const crop = t.cropFor(r.key);
    if (crop && crop.score > (e.cropScore || 0)) {
      try { e.cropURL = bitmapToDataURL(crop.bmp, 0.92); e.cropScore = crop.score; } catch (_) { /* closed */ }
    }
    const input = t.inputFor(r.key);
    if (input && input.score > (e.inputScore || 0)) {
      try { e.inputURL = bitmapToDataURL(input.bmp, 1, 'image/png'); e.inputRead = input.read; e.inputScore = input.score; } catch (_) { /* closed */ }
    }
    // The vote breakdown shown must be the one behind the reading shown (a plate can span tracks).
    if (e.rTrack !== t.id && e.reads) return;
    e.reads = t.reads.map((x) => ({ text: x.text, conf: x.conf, partial: !!x.partial, model: x.model || null, weight: x.weight ?? 1 }));
    e.track = { id: t.id, hits: t.hits, frames: t.reads.length, endReason: t.endReason };
  }

  /** Plate object for cards and the detail sheet (includes debug extras when present). */
  plate(e) {
    return {
      ...e.r, thumbURL: e.thumbURL, cropURL: e.cropURL || null, inputURL: e.inputURL || null,
      inputRead: e.inputRead || null, reads: e.reads || null, track: e.track || null,
    };
  }

  get(key) { return this.map.get(key); }
  entries() { return [...this.map.values()]; }

  remove(key) {
    const e = this.map.get(key);
    if (!e) return false;
    for (const id of e.tracks) this.byTrack.delete(id);
    this.map.delete(key);
    return true;
  }

  clear() { this.map.clear(); this.byTrack.clear(); }
}
