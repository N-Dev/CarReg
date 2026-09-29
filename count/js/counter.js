// Counting road users. Follows what the detector finds from frame to frame (tracks), notices when a
// track crosses one of the two counting lines, and works out its direction, speed and length.
// Coordinates are fractions of the frame (0..1); times are milliseconds. Pure logic (no DOM), unit-tested.

/** COCO classes worth counting on a road, by the detector's class number. */
export const COCO = { 0: 'person', 1: 'bicycle', 2: 'car', 3: 'motorbike', 5: 'bus', 7: 'truck', 16: 'dog', 17: 'horse' };

/**
 * What gets counted. group: detections only continue a track of the same group (the detector often
 * calls one van a car in one frame and a truck in the next). motor: included in the speed figures.
 */
export const KINDS = {
  car: { label: 'Cars', one: 'car', group: 'vehicle', motor: true },
  truck: { label: 'Vans & trucks', one: 'van or truck', group: 'vehicle', motor: true },
  bus: { label: 'Buses', one: 'bus', group: 'vehicle', motor: true },
  motorbike: { label: 'Motorbikes', one: 'motorbike', group: 'two', motor: true },
  bicycle: { label: 'Bicycles', one: 'bicycle', group: 'two', motor: false },
  person: { label: 'People', one: 'person', group: 'person', motor: false },
  dog: { label: 'Dogs', one: 'dog', group: 'animal', motor: false },
  horse: { label: 'Horses', one: 'horse', group: 'animal', motor: false },
};
export const ORDER = ['car', 'truck', 'bus', 'motorbike', 'bicycle', 'person', 'dog', 'horse'];
export const LONG_VEHICLE_M = 7.5; // "long vehicles" in the report: most lorries, coaches and buses

const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
function inter(a, b) {
  const w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
  const h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
  return w > 0 && h > 0 ? w * h : 0;
}
export function iou(a, b) {
  const i = inter(a, b);
  return i ? i / (area(a) + area(b) - i) : 0;
}
const shift = (b, dx, dy) => [b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy];
const centre = (b) => [(b[0] + b[2]) / 2, (b[1] + b[3]) / 2];
/** Where a road user touches the ground: the bottom middle of its box. Lines are drawn on the road. */
export const foot = (b) => [(b[0] + b[2]) / 2, b[3]];

/**
 * A person on a bicycle, motorbike or horse is its rider: dropped, so a cyclist isn't also counted as
 * a pedestrian.
 */
export function dropRiders(dets) {
  const mounts = dets.filter((d) => d.cls === 'bicycle' || d.cls === 'motorbike' || d.cls === 'horse');
  if (!mounts.length) return dets;
  return dets.filter((d) => {
    if (d.cls !== 'person') return true;
    const cx = (d.box[0] + d.box[2]) / 2;
    return !mounts.some((m) => cx > m.box[0] && cx < m.box[2] && inter(d.box, m.box) > 0.25 * area(d.box));
  });
}

// ---------------------------------------------------------------- tracking
let nextId = 1;

export class Tracker {
  /**
   * maxAgeMs: how long a track is kept (moving on its last speed) without being seen, which carries it
   * past a parked car or a lamp post. aspect: frame height / width, so distances are measured in pixels.
   */
  constructor({ maxAgeMs = 1500, iouMin = 0.08, aspect = 9 / 16 } = {}) {
    this.maxAge = maxAgeMs;
    this.iouMin = iouMin;
    this.aspect = aspect;
    this.tracks = [];
  }

  predict(tr, t) {
    const dt = t - tr.lastT;
    return shift(tr.box, tr.vx * dt, tr.vy * dt);
  }

  /** Feeds one frame's detections ({ cls, box, score }); returns the tracks seen now and those that ended. */
  update(dets, t) {
    const pairs = [];
    const preds = this.tracks.map((tr) => this.predict(tr, t));
    this.tracks.forEach((tr, i) => {
      const p = preds[i];
      const pc = centre(p);
      const size = Math.max(p[2] - p[0], (p[3] - p[1]) * this.aspect);
      // With one sighting there's no speed to predict from yet, so look further: a fast car at a low
      // frame rate can move more than its own length between frames.
      const reach = (tr.hits < 2 ? 2.2 : 0.8) * size;
      dets.forEach((d, j) => {
        if (KINDS[d.cls].group !== tr.group) return;
        let s = iou(p, d.box);
        if (s < this.iouMin) {
          // Fast or briefly hidden: accept a detection near where the track should be, of similar size.
          const dc = centre(d.box);
          const dist = Math.hypot(dc[0] - pc[0], (dc[1] - pc[1]) * this.aspect);
          const ratio = area(d.box) / Math.max(1e-9, area(p));
          if (dist > reach || ratio < 0.35 || ratio > 3) return;
          s = 0.001 * (1 - dist / reach);
        }
        pairs.push([s, i, j]);
      });
    });
    pairs.sort((a, b) => b[0] - a[0]);
    const usedT = new Set();
    const usedD = new Set();
    const seen = [];
    for (const [, i, j] of pairs) {
      if (usedT.has(i) || usedD.has(j)) continue;
      usedT.add(i);
      usedD.add(j);
      this._observe(this.tracks[i], dets[j], t);
      seen.push(this.tracks[i]);
    }
    dets.forEach((d, j) => {
      if (usedD.has(j)) return;
      const tr = {
        id: nextId++, group: KINDS[d.cls].group, votes: {}, hits: 0, firstT: t, lastT: t,
        box: d.box, obs: [], vx: 0, vy: 0, first: foot(d.box), cross: {}, counted: false,
      };
      this._observe(tr, d, t);
      this.tracks.push(tr);
      seen.push(tr);
    });
    const lost = [];
    this.tracks = this.tracks.filter((tr, i) => {
      if (usedT.has(i) || tr.lastT === t) return true;
      const p = preds[i] || tr.box;
      const gone = t - tr.lastT > this.maxAge || p[2] < -0.02 || p[0] > 1.02 || p[3] < -0.02 || p[1] > 1.02;
      if (gone) lost.push(tr);
      return !gone;
    });
    return { seen, lost };
  }

  _observe(tr, d, t) {
    tr.prev = tr.obs.length ? tr.obs[tr.obs.length - 1] : null;
    tr.obs.push({ t, box: d.box });
    if (tr.obs.length > 60) tr.obs.shift();
    tr.box = d.box;
    tr.lastT = t;
    tr.hits++;
    tr.votes[d.cls] = (tr.votes[d.cls] || 0) + (d.score || 0.5);
    // Speed of the box over roughly the last half second (steadier than frame to frame).
    let k = tr.obs.length - 1;
    while (k > 0 && t - tr.obs[k - 1].t <= 600) k--;
    const o = tr.obs[k];
    if (o.t < t) {
      const [x0, y0] = centre(o.box);
      const [x1, y1] = centre(d.box);
      tr.vx = (x1 - x0) / (t - o.t);
      tr.vy = (y1 - y0) / (t - o.t);
    }
  }
}

/** The class a track was seen as most (weighted by the detector's confidence). */
export function kindOf(tr) {
  let best = null;
  for (const [k, v] of Object.entries(tr.votes)) if (!best || v > tr.votes[best]) best = k;
  return best;
}

// ---------------------------------------------------------------- lines
const cross2 = (ax, ay, bx, by) => ax * by - ay * bx;

/**
 * Where the step from p0 (time t0) to p1 (time t1) crosses the line segment [x1, y1, x2, y2], or null.
 * `side` is +1 if it ends up on the line's left (a positive cross product), -1 otherwise; `t` is the
 * crossing time, interpolated between the two frames.
 */
export function crossing(line, p0, t0, p1, t1, aspect = 1) {
  const [x1, y1, x2, y2] = line;
  const dx = x2 - x1;
  const dy = (y2 - y1) * aspect;
  const s0 = cross2(dx, dy, p0[0] - x1, (p0[1] - y1) * aspect);
  const s1 = cross2(dx, dy, p1[0] - x1, (p1[1] - y1) * aspect);
  if ((s0 < 0) === (s1 < 0)) return null;
  const f = s0 / (s0 - s1);
  const qx = p0[0] + f * (p1[0] - p0[0]);
  const qy = (p0[1] + f * (p1[1] - p0[1]) - y1) * aspect;
  const u = ((qx - x1) * dx + qy * dy) / (dx * dx + dy * dy || 1);
  if (u < -0.15 || u > 1.15) return null; // passed beyond the end of the line
  return { t: t0 + f * (t1 - t0), side: s1 > 0 ? 1 : -1 };
}

/** Which side of `line` the point is on: +1 left, -1 right. */
function sideOf(line, p, aspect) {
  const [x1, y1, x2, y2] = line;
  return cross2(x2 - x1, (y2 - y1) * aspect, p[0] - x1, (p[1] - y1) * aspect) > 0 ? 1 : -1;
}
const mid = (l) => [(l[0] + l[2]) / 2, (l[1] + l[3]) / 2];

/**
 * Distance between the two lines along a direction of travel through point p (in frame widths, with
 * heights scaled by `aspect`), or null. Measured at the road user's own position, so it allows for
 * perspective (a far lane looks shorter).
 */
export function gapAlong(a, b, p, dir, aspect = 1) {
  const len = Math.hypot(dir[0], dir[1] * aspect);
  if (!len) return null;
  const ux = dir[0] / len;
  const uy = (dir[1] * aspect) / len;
  const hit = (l) => {
    const lx = l[2] - l[0];
    const ly = (l[3] - l[1]) * aspect;
    const den = cross2(ux, uy, lx, ly);
    if (Math.abs(den) < 1e-9) return null;
    return cross2(l[0] - p[0], (l[1] - p[1]) * aspect, lx, ly) / den; // distance along u from p
  };
  const sa = hit(a);
  const sb = hit(b);
  return sa == null || sb == null ? null : Math.abs(sa - sb);
}

// ---------------------------------------------------------------- counting
/**
 * Counts road users crossing two lines drawn across the road, `distanceM` metres apart. Direction 1
 * is from line A towards line B; direction 2 the other way. Each road user is counted once, when it
 * first crosses either line; crossing the second line as well gives its speed and length.
 */
export class Counter {
  /** roi: the analysed part of the frame [x, y, w, h]; boxes touching its edges are cut off. */
  constructor({ lines, distanceM = 20, aspect = 9 / 16, maxAgeMs = 1500, roi = [0, 0, 1, 1] } = {}) {
    this.tracker = new Tracker({ aspect, maxAgeMs });
    this.setLines(lines, distanceM);
    this.setAspect(aspect);
    this.roi = roi;
    this.pending = new Map(); // counted tracks still in view: id -> track
  }

  setLines(lines, distanceM = this.distanceM) {
    this.lines = lines;
    this.distanceM = distanceM;
  }

  setAspect(aspect) {
    this.aspect = aspect;
    this.tracker.aspect = aspect;
  }

  /**
   * One analysed frame: detections ({ cls, box, score }) at time t. Returns `counted` (tracks counted
   * in this frame, for the live display) and `done` (finished records: see record()).
   */
  update(dets, t) {
    const { seen, lost } = this.tracker.update(dropRiders(dets.filter((d) => KINDS[d.cls])), t);
    const counted = [];
    for (const tr of seen) if (tr.prev && this._check(tr)) counted.push(tr);
    const done = [];
    for (const tr of lost) {
      if (!tr.counted) continue;
      this.pending.delete(tr.id);
      done.push(this.record(tr));
    }
    return { counted, done, tracks: this.tracker.tracks };
  }

  /** Ends every track (at the end of a session) and returns the records of those counted. */
  flush() {
    const done = this.tracker.tracks.filter((tr) => tr.counted).map((tr) => this.record(tr));
    this.tracker.tracks = [];
    this.pending.clear();
    return done;
  }

  _check(tr) {
    const { a, b } = this.lines;
    const p0 = foot(tr.prev.box);
    const p1 = foot(tr.box);
    let newlyCounted = false;
    for (const [name, line, other] of [['a', a, b], ['b', b, a]]) {
      if (tr.cross[name]) continue; // only the first crossing of each line counts
      const c = crossing(line, p0, tr.prev.t, p1, tr.lastT, this.aspect);
      if (!c) continue;
      // Heading towards the other line: A->B is direction 1 when crossing A, direction 2 when crossing B.
      const towardsOther = c.side === sideOf(line, mid(other), this.aspect);
      const dir = name === 'a' ? (towardsOther ? 1 : 2) : (towardsOther ? 2 : 1);
      tr.cross[name] = { t: c.t, dir, box: tr.box };
      if (!tr.counted && this._moved(tr)) {
        tr.counted = true;
        tr.dir = dir;
        tr.countT = c.t;
        this.pending.set(tr.id, tr);
        newlyCounted = true;
      }
    }
    return newlyCounted;
  }

  /** Really moving, not a parked car or someone standing on the line whose box jitters across it. */
  _moved(tr) {
    const [x0, y0] = tr.first;
    const [x1, y1] = foot(tr.box);
    const dist = Math.hypot(x1 - x0, (y1 - y0) * this.aspect);
    return tr.hits >= 2 && dist >= Math.max(0.015, 0.35 * (tr.box[2] - tr.box[0]));
  }

  /** Speed (km/h) between the lines, if the track crossed both in its direction of travel. */
  speedOf(tr) {
    const { a, b } = tr.cross;
    if (!a || !b || a.dir !== tr.dir || b.dir !== tr.dir) return null;
    const dt = tr.dir === 1 ? b.t - a.t : a.t - b.t;
    if (dt < 80) return null;
    const kmh = (this.distanceM / (dt / 1000)) * 3.6;
    return kmh >= 1 && kmh <= 200 ? kmh : null;
  }

  /** Length (m) along the direction of travel, from the box sizes between the lines, if measurable. */
  lengthOf(tr) {
    const { a, b } = tr.cross;
    if (!a || !b) return null;
    const t0 = Math.min(a.t, b.t) - 150;
    const t1 = Math.max(a.t, b.t) + 150;
    const obs = tr.obs.filter((o) => o.t >= t0 && o.t <= t1);
    if (obs.length < 2) return null;
    const first = foot(obs[0].box);
    const last = foot(obs[obs.length - 1].box);
    const dir = [last[0] - first[0], last[1] - first[1]];
    const len = Math.hypot(dir[0], dir[1] * this.aspect);
    if (!len) return null;
    const ux = dir[0] / len;
    const uy = (dir[1] * this.aspect) / len;
    const sizes = [];
    for (const o of obs) {
      const [x1, y1, x2, y2] = o.box;
      if (x1 <= this.roi[0] + 0.005 || x2 >= this.roi[0] + this.roi[2] - 0.005) continue; // cut off by the edge
      const extent = Math.abs((x2 - x1) * ux) + Math.abs((y2 - y1) * this.aspect * uy);
      const gap = gapAlong(this.lines.a, this.lines.b, foot(o.box), dir, this.aspect);
      if (gap) sizes.push((extent * this.distanceM) / gap);
    }
    if (!sizes.length) return null;
    sizes.sort((x, y) => x - y);
    const m = sizes[Math.floor(sizes.length / 2)];
    return m >= 0.3 && m <= 25 ? m : null;
  }

  /** What's kept for a counted road user: when, what, which way, speed and length. No images. */
  record(tr) {
    const speed = this.speedOf(tr);
    const length = speed != null ? this.lengthOf(tr) : null;
    const conf = tr.votes[kindOf(tr)] / tr.hits;
    return {
      t: tr.countT, kind: kindOf(tr), dir: tr.dir,
      speed: speed == null ? null : Math.round(speed * 10) / 10,
      length: length == null ? null : Math.round(length * 10) / 10,
      conf: Math.round(conf * 100) / 100, frames: tr.hits,
    };
  }
}

/** Default lines: two upright lines across the middle of the picture. */
export const DEFAULT_LINES = { a: [0.35, 0.3, 0.35, 0.95], b: [0.65, 0.3, 0.65, 0.95] };

/**
 * The part of the frame worth analysing: around both lines, with room either side for road users to be
 * picked up before they reach a line. Returned as fractions of the frame: [x, y, w, h].
 */
export function regionFor(lines, aspect = 9 / 16) {
  const xs = [lines.a[0], lines.a[2], lines.b[0], lines.b[2]];
  const ys = [lines.a[1], lines.a[3], lines.b[1], lines.b[3]];
  const gap = Math.max(0.12, Math.max(...xs) - Math.min(...xs));
  let x0 = Math.max(0, Math.min(...xs) - 0.6 * gap);
  let x1 = Math.min(1, Math.max(...xs) + 0.6 * gap);
  let y0 = Math.max(0, Math.min(...ys) - 0.15);
  let y1 = Math.min(1, Math.max(...ys) + 0.08);
  // Never narrower than half the frame: a road user needs a few frames in view before the first line.
  if (x1 - x0 < 0.5) { const c = (x0 + x1) / 2; x0 = Math.max(0, Math.min(0.5, c - 0.25)); x1 = x0 + 0.5; }
  // Not a thin strip either (in pixels, at least a third as tall as it is wide), so tall vehicles fit.
  const minH = Math.min(1, (0.35 * (x1 - x0)) / aspect);
  if (y1 - y0 < minH) { const c = (y0 + y1) / 2; y0 = Math.max(0, Math.min(1 - minH, c - minH / 2)); y1 = y0 + minH; }
  return [x0, y0, x1 - x0, y1 - y0];
}
