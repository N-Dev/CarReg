// Figures for a counting session: totals by kind and direction, vehicles per hour, speeds (mean, 85th
// percentile, share over the limit), long vehicles, and the CSV export. Pure logic, unit-tested.
import { KINDS, ORDER, LONG_VEHICLE_M } from './counter.js';

const HOUR = 3600000;

/** Percentile p (0..100) of sorted numbers, interpolating between neighbours (like PERCENTILE.INC). */
export function percentile(sorted, p) {
  if (!sorted.length) return null;
  const x = (p / 100) * (sorted.length - 1);
  const i = Math.floor(x);
  return i + 1 < sorted.length ? sorted[i] + (x - i) * (sorted[i + 1] - sorted[i]) : sorted[i];
}

const round1 = (x) => (x == null ? null : Math.round(x * 10) / 10);

/** Speed figures (km/h) for a list of speeds, against a limit. */
export function speedStats(speeds, limit) {
  const s = speeds.filter((v) => v != null).sort((a, b) => a - b);
  if (!s.length) return { n: 0 };
  const over = s.filter((v) => v > limit).length;
  return {
    n: s.length,
    mean: round1(s.reduce((a, b) => a + b, 0) / s.length),
    median: round1(percentile(s, 50)),
    p85: round1(percentile(s, 85)),
    max: round1(s[s.length - 1]),
    over,
    overPct: Math.round((over / s.length) * 1000) / 10,
    over10: s.filter((v) => v > limit + 10).length,
  };
}

/** Start of the local clock hour containing time t. */
export function hourStart(t) {
  const d = new Date(t);
  d.setMinutes(0, 0, 0);
  return d.getTime();
}

/**
 * Everything the results screen and the report show, for one session's records
 * ({ t, kind, dir, speed, length }) between `from` and `to`, with a speed `limit` in km/h.
 */
export function summarize(records, { from, to, limit = 50 } = {}) {
  let first = Infinity;
  let last = -Infinity;
  for (const r of records) { if (r.t < first) first = r.t; if (r.t > last) last = r.t; }
  const start = from ?? (records.length ? first : Date.now());
  const end = Math.max(to ?? start, records.length ? last : start);
  const totals = {};
  for (const k of ORDER) totals[k] = { 1: 0, 2: 0, all: 0 };
  let motor = 0;
  const motorDir = { 1: 0, 2: 0 };
  let long = 0;
  const hours = new Map();
  for (let h = hourStart(start); h <= end; h += HOUR) hours.set(h, emptyHour(h));
  for (const r of records) {
    if (!totals[r.kind]) continue;
    totals[r.kind][r.dir]++;
    totals[r.kind].all++;
    const h = hourStart(r.t);
    if (!hours.has(h)) hours.set(h, emptyHour(h));
    const bin = hours.get(h);
    bin.kinds[r.kind]++;
    bin.all++;
    if (KINDS[r.kind].motor) {
      motor++;
      motorDir[r.dir]++;
      bin.motor++;
      bin.dirs[r.dir]++;
      if (r.length != null && r.length >= LONG_VEHICLE_M) long++;
    }
  }
  const perHour = [...hours.values()].sort((a, b) => a.start - b.start);
  const peak = perHour.reduce((best, h) => (h.motor > (best ? best.motor : -1) ? h : best), null);
  const hoursCounted = Math.max((end - start) / HOUR, 1 / 60);
  const motorSpeeds = (dir) => records.filter((r) => KINDS[r.kind] && KINDS[r.kind].motor && (!dir || r.dir === dir)).map((r) => r.speed);
  const bikes = records.filter((r) => r.kind === 'bicycle').map((r) => r.speed);
  return {
    from: start,
    to: end,
    hours: Math.round(hoursCounted * 100) / 100,
    totals,
    all: records.filter((r) => totals[r.kind]).length,
    motor,
    motorDir,
    perHourAvg: Math.round((motor / hoursCounted) * 10) / 10,
    peak: peak && peak.motor ? { start: peak.start, motor: peak.motor } : null,
    perHour,
    long,
    limit,
    speeds: { all: speedStats(motorSpeeds(), limit), 1: speedStats(motorSpeeds(1), limit), 2: speedStats(motorSpeeds(2), limit) },
    bikeSpeeds: speedStats(bikes, limit),
    histogram: histogram(motorSpeeds().filter((v) => v != null)),
  };
}

function emptyHour(start) {
  const kinds = {};
  for (const k of ORDER) kinds[k] = 0;
  return { start, kinds, all: 0, motor: 0, dirs: { 1: 0, 2: 0 } };
}

/** Motor-vehicle speeds in 5 km/h bands: [{ from, to, n }], from 0 up to the fastest band used. */
export function histogram(speeds, step = 5) {
  if (!speeds.length) return [];
  let fastest = 0;
  for (const s of speeds) if (s > fastest) fastest = s;
  const top = Math.ceil((fastest + 0.001) / step) * step;
  const bins = [];
  for (let v = 0; v < top; v += step) bins.push({ from: v, to: v + step, n: 0 });
  for (const s of speeds) bins[Math.min(bins.length - 1, Math.floor(s / step))].n++;
  return bins;
}

const pad = (n) => String(n).padStart(2, '0');
/** Local date and time as "2026-09-29 18:05:31". */
export function localStamp(t) {
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

const csvCell = (v) => {
  const s = v == null ? '' : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

/** One row per road user counted: when, what, which way, speed and length (blank if not measured). */
export function toCSV(records, { dirNames = ['Direction 1', 'Direction 2'], site = '' } = {}) {
  const rows = [['time', 'type', 'direction', 'speed_kmh', 'length_m', 'site']];
  for (const r of [...records].sort((a, b) => a.t - b.t)) {
    rows.push([localStamp(r.t), KINDS[r.kind] ? KINDS[r.kind].one : r.kind, dirNames[r.dir - 1] || r.dir, r.speed ?? '', r.length ?? '', site]);
  }
  return `${rows.map((r) => r.map(csvCell).join(',')).join('\r\n')}\r\n`;
}
