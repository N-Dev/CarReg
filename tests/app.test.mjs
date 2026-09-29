// Run with: node --test tests/*.test.mjs
// Checks the single source of truth for versions, the offline file list, and the pure logic behind
// adaptive quality, idle mode, history retention, field testing and the dataset export.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync, writeFileSync, mkdtempSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { Adaptive, Idle, TIERS, ORDER } from '../js/adaptive.js';
import { expired } from '../js/store.js';
import { crc32, zip } from '../js/zip.js';
import { accuracyStats, thresholdCurve, suggestThreshold, alignOps, confusions, datasetFiles } from '../js/fieldtest.js';
import { Tracker } from '../js/tracker.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(path.join(ROOT, f), 'utf8');
function loadConfig() {
  const box = { self: {} };
  vm.runInNewContext(read('js/config.js'), box);
  return box.self.PS_CONFIG;
}

// ---------------------------------------------------------------- versions and files
test('config: every model file matches its size and revision (sha256 prefix)', () => {
  const cfg = loadConfig();
  assert.match(cfg.ort.version, /^\d+\.\d+\.\d+$/);
  assert.equal(cfg.app, 'dev', 'the publish workflow stamps the build id; keep "dev" in the repository');
  for (const [key, m] of Object.entries(cfg.models)) {
    const file = path.join(ROOT, m.file);
    assert.ok(existsSync(file), `${key}: ${m.file} missing`);
    const bytes = readFileSync(file);
    assert.equal(bytes.length, m.bytes, `${key}: bytes in js/config.js should be ${bytes.length}`);
    const rev = createHash('sha256').update(bytes).digest('hex').slice(0, 12);
    assert.equal(m.rev, rev, `${key}: rev in js/config.js should be '${rev}' (the file changed)`);
  }
  for (const t of Object.values(TIERS)) for (const k of [t.det, t.read, t.readNew]) assert.ok(cfg.models[k], `tier uses unknown model ${k}`);
});

test('offline file list: the service worker caches every app file that exists', () => {
  const sw = read('sw.js');
  const start = sw.indexOf('const SHELL = [');
  const shell = [...sw.slice(start, sw.indexOf('];', start)).matchAll(/'([^']+)'/g)].map((m) => m[1]);
  const js = readdirSync(path.join(ROOT, 'js')).filter((f) => f.endsWith('.js')).map((f) => `js/${f}`);
  for (const f of js) assert.ok(shell.includes(f), `${f} is not in the service worker's SHELL list`);
  for (const f of shell) if (f !== './') assert.ok(existsSync(path.join(ROOT, f)), `SHELL lists ${f}, which doesn't exist`);
  assert.ok(sw.includes("importScripts('js/config.js')"), 'service worker must take versions from js/config.js');
  assert.match(sw, /^\/\/ build: dev$/m, 'the publish workflow stamps the "// build: dev" line');
});

test('versions come from one place: page, workflow and tools read js/config.js', () => {
  const html = read('index.html');
  assert.ok(html.indexOf('js/config.js') > 0 && html.indexOf('js/config.js') < html.indexOf('js/main.js'), 'index.html must load js/config.js before js/main.js');
  const wf = read('.github/workflows/publish.yml');
  assert.ok(wf.includes('js/config.js'), 'the workflow must read the runtime version from js/config.js');
  assert.ok(read('tools/vendor_ort.py').includes('config.js'), 'tools/vendor_ort.py must read the runtime version from js/config.js');
  for (const f of readdirSync(path.join(ROOT, 'js'))) {
    if (f === 'config.js') continue;
    assert.ok(!/['"]1\.\d+\.\d+['"]/.test(read(`js/${f}`)), `js/${f} hard-codes a version number`);
  }
});

// ---------------------------------------------------------------- adaptive quality
function feed(a, { from = 0, to, every = 70, ms, pressure = null, available = () => true }) {
  const out = [];
  for (let t = from; t <= to; t += every) out.push({ t, ...a.observe(ms, t, { pressure, available }) });
  return out;
}

test('adaptive: steps up with sustained headroom, never before', () => {
  const a = new Adaptive();
  assert.equal(a.tier, 'fast');
  feed(a, { to: 2800, ms: 20 });
  assert.equal(a.tier, 'fast', 'no upgrade within the first 3 s');
  const steps = feed(a, { from: 2870, to: 3500, ms: 20 });
  assert.equal(a.tier, 'balanced');
  assert.ok(steps.some((s) => s.changed && s.changed.from === 'fast' && s.changed.to === 'balanced'));
  feed(a, { from: 3570, to: 9000, ms: 20 });
  assert.equal(a.tier, 'sharp');
  feed(a, { from: 9070, to: 20000, ms: 20 });
  assert.equal(a.tier, 'sharp', 'sharp is the top tier');
});

test('adaptive: asks for missing models instead of switching to a tier it can’t run', () => {
  const a = new Adaptive();
  const steps = feed(a, { to: 4000, ms: 20, available: (t) => t === 'fast' });
  assert.equal(a.tier, 'fast');
  assert.ok(steps.some((s) => s.want === 'balanced'));
});

test('adaptive: steps down when frames are too slow and backs off before retrying', () => {
  const a = new Adaptive({ mode: 'sharp' });
  a.setMode('auto', 0);
  assert.equal(a.tier, 'sharp');
  feed(a, { to: 1900, ms: 60 });
  assert.equal(a.tier, 'sharp', 'within budget: stays');
  const steps = feed(a, { from: 1970, to: 4000, ms: 140 });
  assert.equal(a.tier, 'balanced');
  const ch = steps.find((s) => s.changed).changed;
  assert.match(ch.reason, /too slow/);
  // Plenty of headroom again, but sharp proved too slow: not retried for 30 s.
  feed(a, { from: 4070, to: 30000, ms: 20 });
  assert.equal(a.tier, 'balanced');
  feed(a, { from: 30070, to: 40000, ms: 20 });
  assert.equal(a.tier, 'sharp', 'retried after the back-off');
  // Too slow a second time: waits twice as long.
  feed(a, { from: 40070, to: 43000, ms: 140 });
  assert.equal(a.tier, 'balanced');
  const failedAt = 43000;
  feed(a, { from: 43070, to: failedAt + 45000, ms: 20 });
  assert.equal(a.tier, 'balanced', 'second back-off is 60 s');
  feed(a, { from: failedAt + 45070, to: failedAt + 70000, ms: 20 });
  assert.equal(a.tier, 'sharp');
});

test('adaptive: a busy phone holds its quality; a hot one steps down even when frames are fast', () => {
  const a = new Adaptive({ mode: 'balanced' });
  a.setMode('auto', 0);
  feed(a, { to: 8000, ms: 20, pressure: 'serious' });
  assert.equal(a.tier, 'balanced', "'serious' pressure (our own load) holds: no upgrade, no downgrade");
  feed(a, { from: 8070, to: 10500, ms: 20, pressure: 'critical' });
  assert.equal(a.tier, 'fast');
  assert.match(a.reason, /critical/);
  feed(a, { from: 10570, to: 20000, ms: 20, pressure: 'serious' });
  assert.equal(a.tier, 'fast', 'no upgrades while under pressure');
  feed(a, { from: 20070, to: 24000, ms: 20, pressure: 'nominal' });
  assert.equal(a.tier, 'balanced', 'upgrades again once it cools down');
});

test('adaptive: a fixed quality never changes', () => {
  for (const mode of ORDER) {
    const a = new Adaptive({ mode });
    feed(a, { to: 10000, ms: 500, pressure: 'critical' });
    feed(a, { from: 10070, to: 20000, ms: 5 });
    assert.equal(a.tier, mode);
  }
});

test('idle mode: slows down after 2.5 s without a plate, wakes on the next one', () => {
  const idle = new Idle();
  assert.equal(idle.observe(1, 0), false);
  assert.equal(idle.observe(0, 2000), false);
  assert.equal(idle.observe(0, 2600), true);
  assert.equal(idle.observe(1, 2700), false);
  assert.equal(idle.frameMs, 250);
});

// ---------------------------------------------------------------- history retention
test('history retention: deletes only plates older than the chosen period', () => {
  const day = 86400000;
  const now = 100 * day;
  const entries = [{ key: 'A', last: now - 2 * day }, { key: 'B', last: now - 8 * day }, { key: 'C', last: now - 40 * day }, { key: 'D', last: now - 400 * day }];
  assert.deepEqual(expired(entries, '7', now), ['B', 'C', 'D']);
  assert.deepEqual(expired(entries, '30', now), ['C', 'D']);
  assert.deepEqual(expired(entries, '365', now), ['D']);
  assert.deepEqual(expired(entries, 'forever', now), []);
});

// ---------------------------------------------------------------- tracker threshold
test('tracker: the tuned confirm threshold holds back low-confidence plates', () => {
  const read = (text, conf) => ({ text, conf, minP: conf, region: 'Ireland', regionProb: 0.95 });
  const det = (r) => ({ box: [100, 100, 220, 130], score: 0.9, read: r });
  const run = (minScore) => {
    const tr = new Tracker({ minScore });
    let confirmed = 0;
    for (let i = 0; i < 6; i++) confirmed += tr.update([det(read('241D12345', 0.72))], i * 70).confirmed.length;
    return confirmed;
  };
  assert.equal(run(0), 1);
  assert.equal(run(0.9), 0);
});

// ---------------------------------------------------------------- field test + export
const S = (id, truth, predicted, conf, source = 'live') => ({ id, truth, predicted, conf, source, region: 'Ireland', time: 1700000000000 + id });

test('accuracy: exact and character-level rates, per source, recent misses', () => {
  const list = [S(1, '241D12345', '241D12345', 0.95), S(2, '12D15405', '12D15406', 0.6), S(3, 'AB12CDE', 'AB12CDE', 0.9, 'photo'), S(4, '201D8573', '201D873', 0.5, 'photo')];
  const st = accuracyStats(list);
  assert.equal(st.n, 4);
  assert.equal(st.exact, 2);
  assert.equal(st.exactRate, 0.5);
  assert.equal(st.charRate, (9 + 7 + 7 + 7) / (9 + 8 + 7 + 8));
  assert.deepEqual(st.bySource, { live: { n: 2, exact: 1 }, photo: { n: 2, exact: 1 } });
  assert.deepEqual(st.misses.map((m) => m.id), [4, 2]);
});

test('threshold tuning: coverage and accuracy per threshold, and a suggestion once there is enough data', () => {
  const list = [];
  for (let i = 0; i < 30; i++) list.push(S(i, 'AA', 'AA', 0.8 + (i % 5) * 0.04));   // confident and right
  for (let i = 0; i < 10; i++) list.push(S(100 + i, 'AA', 'AB', 0.5 + (i % 3) * 0.05)); // unsure and wrong
  const curve = thresholdCurve(list, [0, 0.7]);
  assert.deepEqual(curve[0], { t: 0, kept: 40, coverage: 1, accuracy: 0.75 });
  assert.deepEqual(curve[1], { t: 0.7, kept: 30, coverage: 0.75, accuracy: 1 });
  const s = suggestThreshold(list);
  assert.ok(s && s.t > 0.6 && s.t <= 0.8, JSON.stringify(s));
  assert.equal(s.accuracy, 1);
  assert.equal(suggestThreshold(list.slice(0, 10)), null, 'too few samples');
});

test('diff and confusions: which characters get mixed up', () => {
  assert.deepEqual(alignOps('12D15406', '12D15405').filter((o) => o.op !== 'same'), [{ op: 'sub', a: '6', b: '5' }]);
  assert.deepEqual(alignOps('201D873', '201D8573').filter((o) => o.op !== 'same'), [{ op: 'ins', a: '', b: '5' }]);
  assert.deepEqual(alignOps('AB12CDEE', 'AB12CDE').filter((o) => o.op !== 'same'), [{ op: 'del', a: 'E', b: '' }]);
  const c = confusions([S(1, 'AB8', 'ABB', 1), S(2, '88', 'BB', 1), S(3, 'D0', 'DD', 1)]);
  assert.deepEqual(c[0], { truth: '8', read: 'B', n: 3 });
});

test('dataset export: fast-plate-ocr layout, validation split, every label in predictions.csv', () => {
  const list = [];
  const images = new Map();
  for (let i = 1; i <= 15; i++) {
    list.push(S(i, i === 3 ? '12-D-"1"' : `241D1234${i % 10}`, `241D1234${i % 10}`, 0.9));
    if (i !== 5) images.set(i, new Uint8Array([0xff, 0xd8, i]));
  }
  const files = datasetFiles(list, images, 'T');
  const byName = new Map(files.map((f) => [f.name, f.data]));
  const train = byName.get('platesight-dataset-T/train/annotations.csv').trim().split('\n');
  const val = byName.get('platesight-dataset-T/val/annotations.csv').trim().split('\n');
  assert.equal(train[0], 'image_path,plate_text,plate_region');
  assert.equal(train.length - 1 + val.length - 1, 14, 'one sample had no image');
  assert.equal(val.length - 1, 2, 'every 7th image goes to validation');
  assert.ok(train.includes('images/00003.jpg,12D1,Ireland'), 'plate text is cleaned to A-Z/0-9');
  for (const line of [...train.slice(1), ...val.slice(1)]) {
    const [img] = line.split(',');
    const split = train.includes(line) ? 'train' : 'val';
    assert.ok(byName.has(`platesight-dataset-T/${split}/${img}`), `${img} missing from ${split}/`);
  }
  const preds = byName.get('platesight-dataset-T/predictions.csv').trim().split('\n');
  assert.equal(preds.length - 1, 15, 'predictions.csv lists every labelled plate');
  assert.ok(preds.some((l) => l.startsWith(',not exported')), 'the sample without an image is marked');
  assert.ok(byName.get('platesight-dataset-T/README.txt').includes('fast-plate-ocr'));
});

test('zip: valid archive that standard tools can read', async () => {
  const files = [
    { name: 'set/README.txt', data: 'héllo plates\n' },
    { name: 'set/images/00001.jpg', data: new Uint8Array(Array.from({ length: 3000 }, (_, i) => (i * 7) % 256)) },
  ];
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926);
  const blob = zip(files, new Date(2026, 8, 29, 14, 30, 10));
  const buf = Buffer.from(await blob.arrayBuffer());
  const dir = mkdtempSync(path.join(tmpdir(), 'ps-zip-'));
  const out = path.join(dir, 'a.zip');
  writeFileSync(out, buf);
  assert.ok(statSync(out).size > 3000);
  let py = null;
  try { execFileSync('python3', ['--version']); py = 'python3'; } catch (_) { /* no python */ }
  if (!py) return;
  const report = execFileSync(py, ['-c', `
import zipfile, sys, json
z = zipfile.ZipFile(sys.argv[1])
assert z.testzip() is None
print(json.dumps({i.filename: [i.file_size, i.date_time[:5]] for i in z.infolist()} | {"text": z.read("set/README.txt").decode()}))
`, out]).toString();
  const r = JSON.parse(report);
  assert.deepEqual(r['set/images/00001.jpg'], [3000, [2026, 9, 29, 14, 30]]);
  assert.equal(r.text, 'héllo plates\n');
});
