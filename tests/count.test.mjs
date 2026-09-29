// TrafficSight unit tests: counting, directions, speeds and lengths from synthetic traffic, the figures
// and CSV, the PDF report, and that the app's files, models and service worker line up.
// Run with: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { Counter, crossing, gapAlong, dropRiders, regionFor, DEFAULT_LINES } from '../count/js/counter.js';
import { summarize, percentile, speedStats, histogram, toCSV } from '../count/js/stats.js';
import { Pdf, textWidth } from '../count/js/pdf.js';
import { councilReport } from '../count/js/report.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(path.join(ROOT, f), 'utf8');

// ---------------------------------------------------------------- synthetic traffic
// Frame: 1280 x 720 (aspect 9/16). Lines A at x = 0.35 and B at x = 0.65, 20 m apart, so one frame
// width is 66.7 m along the road.
const ASPECT = 9 / 16;
const LINES = { a: [0.35, 0.3, 0.35, 0.95], b: [0.65, 0.3, 0.65, 0.95] };
const M_PER_WIDTH = 20 / 0.3;

function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 2 ** 32; };
}

/**
 * Detections for road users moving across the frame. Each mover: { cls, kmh, dir: 1 | -1, lengthM,
 * y (ground line), h (box height), start (ms), hideFrom/hideTo (ms: not detected, e.g. behind a van) }.
 */
function scene(movers, { fps = 9, ms = 12000, jitter = 0.0015, seed = 7 } = {}) {
  const r = rng(seed);
  const frames = [];
  for (let t = 0; t <= ms; t += 1000 / fps) {
    const dets = [];
    for (const m of movers) {
      if (t < m.start) continue;
      const v = ((m.kmh / 3.6) / M_PER_WIDTH / 1000) * m.dir; // frame widths per ms
      const w = m.lengthM / M_PER_WIDTH;
      const cx = (m.dir > 0 ? -w / 2 : 1 + w / 2) + v * (t - m.start);
      if (m.hideFrom != null && t >= m.hideFrom && t < m.hideTo) continue;
      const x1 = Math.max(0, cx - w / 2);
      const x2 = Math.min(1, cx + w / 2);
      if (x2 - x1 < 0.003) continue;
      const j = () => (r() - 0.5) * 2 * jitter;
      dets.push({ cls: m.cls, score: 0.8, box: [x1 + j(), m.y - m.h + j(), x2 + j(), m.y + j()] });
    }
    frames.push({ t, dets });
  }
  return frames;
}

function count(frames, opts = {}) {
  const c = new Counter({ lines: LINES, distanceM: 20, aspect: ASPECT, ...opts });
  const records = [];
  for (const f of frames) records.push(...c.update(f.dets, f.t).done);
  records.push(...c.flush());
  return records;
}

const near = (a, b, tol) => typeof a === 'number' && Math.abs(a - b) <= tol;

test('counter: a car crossing both lines is counted once, with its direction, speed and length', () => {
  const recs = count(scene([{ cls: 'car', kmh: 50, dir: 1, lengthM: 4.5, y: 0.7, h: 0.12, start: 0 }]));
  assert.equal(recs.length, 1, JSON.stringify(recs));
  const r = recs[0];
  assert.equal(r.kind, 'car');
  assert.equal(r.dir, 1, 'A to B');
  assert.ok(near(r.speed, 50, 2), `speed ${r.speed}`);
  assert.ok(near(r.length, 4.5, 0.6), `length ${r.length}`);
});

test('counter: the other way is direction 2; a lorry is long', () => {
  const recs = count(scene([{ cls: 'truck', kmh: 38, dir: -1, lengthM: 11, y: 0.55, h: 0.2, start: 0 }]));
  assert.equal(recs.length, 1);
  assert.equal(recs[0].dir, 2, 'B to A');
  assert.ok(near(recs[0].speed, 38, 2), `speed ${recs[0].speed}`);
  assert.ok(recs[0].length > 9.5, `length ${recs[0].length}`);
});

test('counter: speeds stay right at a low frame rate (crossing times are interpolated)', () => {
  for (const kmh of [30, 60, 90]) {
    const recs = count(scene([{ cls: 'car', kmh, dir: 1, lengthM: 4.5, y: 0.7, h: 0.12, start: 0 }], { fps: 4 }));
    assert.equal(recs.length, 1, `${kmh} km/h: ${JSON.stringify(recs)}`);
    assert.ok(near(recs[0].speed, kmh, kmh * 0.05), `${kmh} km/h measured ${recs[0].speed}`);
  }
});

test('counter: hidden for a moment between the lines (behind a parked van) is still one vehicle, with a speed', () => {
  const recs = count(scene([{ cls: 'car', kmh: 45, dir: 1, lengthM: 4.2, y: 0.8, h: 0.12, start: 0, hideFrom: 2000, hideTo: 2700 }]));
  assert.equal(recs.length, 1, JSON.stringify(recs));
  assert.ok(near(recs[0].speed, 45, 2.5), `speed ${recs[0].speed}`);
});

test('counter: a parked car, or someone standing on a line, is never counted', () => {
  const frames = [];
  const r = rng(3);
  for (let t = 0; t < 20000; t += 111) {
    const j = () => (r() - 0.5) * 0.02;
    frames.push({ t, dets: [
      { cls: 'car', score: 0.9, box: [0.30 + j(), 0.55, 0.40 + j(), 0.7] },     // parked across line A
      { cls: 'person', score: 0.8, box: [0.645 + j(), 0.5, 0.657 + j(), 0.72] }, // standing on line B
    ] });
  }
  assert.equal(count(frames).length, 0);
});

test('counter: following vehicles, both directions and road users of every kind', () => {
  const recs = count(scene([
    { cls: 'car', kmh: 48, dir: 1, lengthM: 4.4, y: 0.75, h: 0.12, start: 0 },
    { cls: 'car', kmh: 48, dir: 1, lengthM: 4.4, y: 0.75, h: 0.12, start: 1300 },
    { cls: 'bus', kmh: 40, dir: -1, lengthM: 11.5, y: 0.55, h: 0.22, start: 500 },
    { cls: 'bicycle', kmh: 18, dir: 1, lengthM: 1.8, y: 0.82, h: 0.1, start: 0 },
    { cls: 'person', kmh: 5, dir: -1, lengthM: 0.6, y: 0.93, h: 0.2, start: 0 },
    { cls: 'dog', kmh: 5, dir: -1, lengthM: 0.8, y: 0.935, h: 0.06, start: 300 },
  ], { ms: 26000 }));
  const kinds = recs.map((r) => `${r.kind}:${r.dir}`).sort();
  assert.deepEqual(kinds, ['bicycle:1', 'bus:2', 'car:1', 'car:1', 'dog:2', 'person:2'], JSON.stringify(recs));
  const bike = recs.find((r) => r.kind === 'bicycle');
  assert.ok(near(bike.speed, 18, 1.5), `bike ${bike.speed}`);
});

test('counter: a cyclist counts as a bicycle, not also as a pedestrian', () => {
  const dets = [
    { cls: 'bicycle', score: 0.8, box: [0.4, 0.6, 0.48, 0.8] },
    { cls: 'person', score: 0.8, box: [0.415, 0.45, 0.465, 0.75] }, // the rider
    { cls: 'person', score: 0.8, box: [0.7, 0.5, 0.72, 0.9] },      // someone walking
  ];
  assert.deepEqual(dropRiders(dets).map((d) => d.cls), ['bicycle', 'person']);
});

test('counter: crossing only one line (turning into a driveway) counts, without a speed', () => {
  // Heads for B but disappears between the lines.
  const recs = count(scene([{ cls: 'car', kmh: 30, dir: 1, lengthM: 4.5, y: 0.7, h: 0.12, start: 0, hideFrom: 3200, hideTo: 1e9 }]));
  assert.equal(recs.length, 1);
  assert.equal(recs[0].speed, null);
  assert.equal(recs[0].length, null);
});

test('lines: crossing times are interpolated; the gap between slanted lines allows for perspective', () => {
  const c = crossing([0.5, 0, 0.5, 1], [0.4, 0.5], 1000, [0.6, 0.5], 1100);
  assert.ok(c && near(c.t, 1050, 1e-6) && c.side === -1, JSON.stringify(c));
  assert.equal(crossing([0.5, 0, 0.5, 0.4], [0.4, 0.8], 0, [0.6, 0.8], 100), null, 'passes below the end of the line');
  // Lines closer together further up the picture (the far side of the road).
  const a = [0.3, 0.9, 0.4, 0.3];
  const b = [0.8, 0.9, 0.6, 0.3];
  const nearGap = gapAlong(a, b, [0.5, 0.85], [1, 0], 1);
  const farGap = gapAlong(a, b, [0.5, 0.35], [1, 0], 1);
  assert.ok(nearGap > farGap * 1.5, `${nearGap} vs ${farGap}`);
  const [x, y, w, h] = regionFor(DEFAULT_LINES, ASPECT);
  assert.ok(x < 0.35 - 0.1 && x + w > 0.65 + 0.1 && y < 0.3 && y + h >= 0.95, 'the analysed area has room either side of the lines');
});

// ---------------------------------------------------------------- figures
test('stats: percentiles and speed figures, as councils use them', () => {
  assert.equal(percentile([10, 20, 30, 40], 50), 25);
  assert.equal(percentile([10, 20, 30, 40, 50], 85), 44);
  const s = speedStats([30, 32, 35, 38, 41, 44, 47, 52, 58, 63, null], 50);
  assert.equal(s.n, 10);
  assert.equal(s.over, 3);
  assert.equal(s.overPct, 30);
  assert.equal(s.over10, 1);
  assert.equal(s.max, 63);
  assert.ok(near(s.p85, 55.9, 0.05), `p85 ${s.p85}`);
  assert.deepEqual(histogram([2, 7, 7.5, 12]).map((b) => b.n), [1, 2, 1]);
});

test('stats: totals by type and direction, per hour, busiest hour and long vehicles', () => {
  const t0 = new Date(2026, 8, 29, 7, 30).getTime();
  const min = 60000;
  const recs = [
    { t: t0 + 5 * min, kind: 'car', dir: 1, speed: 44, length: 4.5 },
    { t: t0 + 35 * min, kind: 'car', dir: 2, speed: 56, length: 4.4 },
    { t: t0 + 40 * min, kind: 'truck', dir: 1, speed: 38, length: 11.2 },
    { t: t0 + 50 * min, kind: 'bus', dir: 2, speed: null, length: null },
    { t: t0 + 55 * min, kind: 'person', dir: 1, speed: 5, length: 0.5 },
    { t: t0 + 95 * min, kind: 'bicycle', dir: 2, speed: 21, length: 1.7 },
  ];
  const s = summarize(recs, { from: t0, to: t0 + 100 * min, limit: 50 });
  assert.equal(s.motor, 4, 'people and bicycles are not motor vehicles');
  assert.deepEqual([s.totals.car[1], s.totals.car[2], s.totals.car.all], [1, 1, 2]);
  assert.equal(s.all, 6);
  assert.deepEqual(s.perHour.map((h) => new Date(h.start).getHours()), [7, 8, 9]);
  assert.deepEqual(s.perHour.map((h) => h.motor), [1, 3, 0]);
  assert.equal(new Date(s.peak.start).getHours(), 8);
  assert.equal(s.long, 1);
  assert.equal(s.speeds.all.n, 3, 'only measured motor-vehicle speeds');
  assert.equal(s.speeds.all.over, 1);
  assert.equal(s.speeds[1].n, 2);
  assert.equal(s.bikeSpeeds.n, 1);
});

test('csv: one row per road user, readable by spreadsheets', () => {
  const csv = toCSV([{ t: new Date(2026, 8, 29, 8, 5, 9).getTime(), kind: 'truck', dir: 2, speed: 41.5, length: null }],
    { dirNames: ['To the village', 'To the N11, "main" road'], site: 'Main St' });
  const lines = csv.trim().split('\r\n');
  assert.equal(lines[0], 'time,type,direction,speed_kmh,length_m,site');
  assert.equal(lines[1], '2026-09-29 08:05:09,van or truck,"To the N11, ""main"" road",41.5,,Main St');
});

// ---------------------------------------------------------------- the report
function pdfObjects(bytes) {
  const s = Buffer.from(bytes).toString('latin1');
  const start = Number(s.match(/startxref\n(\d+)/)[1]);
  assert.ok(s.startsWith('%PDF-1.4') && s.trimEnd().endsWith('%%EOF'));
  assert.equal(s.slice(start, start + 4), 'xref', 'startxref points at the xref table');
  const [, first, n] = s.slice(start).match(/xref\n(\d+) (\d+)/);
  assert.equal(Number(first), 0);
  const entries = s.slice(start).split('\n').slice(2, 2 + Number(n));
  entries.slice(1).forEach((e, i) => {
    assert.match(e, /^\d{10} 00000 n $/);
    assert.ok(s.slice(Number(e.slice(0, 10))).startsWith(`${i + 1} 0 obj`), `object ${i + 1} is where the xref says`);
  });
  return s;
}

test('pdf: valid structure, escaped text, widths from the Helvetica metrics', () => {
  const p = new Pdf({ title: 'Test (1)' });
  p.text(40, 60, 'Hello (world) \\ Dún Laoghaire – 50 km/h', { size: 12 });
  p.rect(40, 80, 100, 20, { fill: '#2a78d6' });
  p.column(40, 120, 20, 50, '#eb6834');
  p.line(40, 200, 200, 200, { dash: [3, 2] });
  const s = pdfObjects(p.bytes());
  assert.ok(s.includes('(Hello \\(world\\) \\\\ D\\372n Laoghaire \\226 50 km/h) Tj'), 'brackets, backslash, fada and dash encoded');
  assert.ok(near(textWidth('Hello', 10), 22.78, 0.01));
});

test('report: a one-page council report with the figures in it', () => {
  const t0 = new Date(2026, 8, 29, 7, 0).getTime();
  const recs = [];
  const r = rng(11);
  for (let i = 0; i < 400; i++) {
    const kinds = ['car', 'car', 'car', 'car', 'truck', 'bus', 'bicycle', 'person', 'dog'];
    recs.push({ t: t0 + r() * 5 * 3600000, kind: kinds[Math.floor(r() * kinds.length)], dir: r() < 0.5 ? 1 : 2, speed: 25 + r() * 40, length: 4 + r() * 8 });
  }
  const session = { site: 'Main Street, outside no. 12', distanceM: 20, limit: 50, dirNames: ['Towards the village', 'Towards the N11'] };
  const sum = summarize(recs, { from: t0, to: t0 + 5 * 3600000, limit: 50 });
  const bytes = councilReport(session, sum, { app: 'abc1234', release: '1.0' });
  const s = pdfObjects(bytes);
  assert.equal((s.match(/\/Type \/Page /g) || []).length, 1, 'one page');
  for (const text of ['Traffic survey', 'Main Street, outside no. 12', 'Towards the village', '85th percentile', 'No images, video or number plates']) {
    assert.ok(s.includes(text), `mentions ${text}`);
  }
  assert.ok(s.includes(`(${sum.motor.toLocaleString('en-IE')}) Tj`), 'the motor-vehicle total is on it');
  // If qpdf is installed, have it check the file too.
  try {
    const dir = mkdtempSync(path.join(tmpdir(), 'tc-'));
    writeFileSync(path.join(dir, 'r.pdf'), bytes);
    const out = execFileSync('qpdf', ['--check', path.join(dir, 'r.pdf')], { encoding: 'utf8' });
    assert.match(out, /No syntax or stream encoding errors/);
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
  }
});

// ---------------------------------------------------------------- files and versions
function loadConfigs() {
  const box = { self: {} };
  vm.runInNewContext(read('js/config.js'), box);
  vm.runInNewContext(read('count/js/config.js'), box);
  return box.self;
}

test('config: each detector model matches its size and revision', () => {
  const { TC_CONFIG } = loadConfigs();
  for (const [key, m] of Object.entries(TC_CONFIG.models)) {
    const bytes = readFileSync(path.join(ROOT, 'count', m.file));
    assert.equal(bytes.length, m.bytes, `${key}: bytes in count/js/config.js should be ${bytes.length}`);
    const rev = createHash('sha256').update(bytes).digest('hex').slice(0, 12);
    assert.equal(m.rev, rev, `${key}: rev in count/js/config.js should be '${rev}'`);
  }
});

test('offline: the service worker caches every TrafficSight file; versions come from the shared config', () => {
  const sw = read('count/sw.js');
  const start = sw.indexOf('const SHELL = [');
  const shell = [...sw.slice(start, sw.indexOf('];', start)).matchAll(/'([^']+)'/g)].map((m) => m[1]);
  for (const f of readdirSync(path.join(ROOT, 'count/js'))) assert.ok(shell.includes(`js/${f}`), `js/${f} is not in count/sw.js's SHELL list`);
  for (const f of shell) if (f !== './') assert.ok(existsSync(path.join(ROOT, 'count', f)), `SHELL lists ${f}, which doesn't exist`);
  assert.ok(sw.includes("importScripts('../js/config.js', 'js/config.js')"), 'the service worker must read both configs');
  const html = read('count/index.html');
  const order = ['../js/config.js', '"js/config.js"', 'js/main.js'].map((s) => html.indexOf(s));
  assert.ok(order.every((i) => i > 0) && order[0] < order[1] && order[1] < order[2], 'index.html loads the configs before main.js');
  assert.ok(!/['"]1\.\d+\.\d+['"]/.test(read('count/js/engine.js') + read('count/js/worker.js')), 'no hard-coded runtime version');
  assert.match(read('sw.js'), /rel\.startsWith\('count\/'\)\) return;/, "PlateSight's service worker leaves TrafficSight alone");
});
