// Debug mode. The live overlay and speed graph are drawn by the scan screen; this is the panel:
//   Overview  device, engine and live diagnostics, and "Copy diagnostics"
//   Log       everything the app did, filterable
//   Tools     benchmarks, thread tuning, overrides and engine actions
//   Accuracy  field-test results, threshold tuning and the training-set export
// Unlocked like Android's developer options: tap the PlateSight logo seven times.
import { CFG, MODELS } from './engine.js';
import { samples, history as plates, SENS } from './store.js';
import { accuracyStats, thresholdCurve, suggestThreshold, confusions, datasetFiles } from './fieldtest.js';
import { zip } from './zip.js';
import { TIERS, ORDER } from './adaptive.js';
import {
  $, $$, settings, state, engine, on, log, logEntries, T0, setSetting, showSheet, copyText, shareOrDownload, mb, haptic,
} from './ctx.js';
import { startEngine, restartEngine, NEED, threadPlan, safeModeOn, resetSafeMode, engineLabel, setStatus } from './boot.js';
import { scan, detConf, stopScan, startScan, cameraInfo } from './scan.js';
import { drawSpark, TIER_COLORS } from './overlay.js';
import { diffHTML } from './detail.js';
import { icon, esc, toast, pct, pct1 } from './ui.js';

const ui = { tab: 'overview', filter: 'all', bench: null, threads: null, busy: false, timer: 0, urls: [], open: false, logQueued: false };
const LABEL = { det384: 'Plate finder 384', det640: 'Plate finder 640', ocrFast: 'Reader (fast)', ocrAcc: 'Reader (accurate)' };
const fmtT = (t) => `+${((t - T0) / 1000).toFixed(1)}s`;
const kv = (rows) => `<div class="kv">${rows.filter(([, v]) => v !== null && v !== undefined && v !== '')
  .map(([k, v]) => `<div><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}</div>`;
const section = (title, body, extra = '') => `<section class="dsec"><h4>${title}${extra}</h4>${body}</section>`;

// ---------------------------------------------------------------- diagnostics
function liveStats() {
  const ms = scan.perf.map((x) => x.ms).sort((a, b) => a - b);
  const q = (f) => (ms.length ? Math.round(ms[Math.min(ms.length - 1, Math.floor(ms.length * f))]) : null);
  return {
    running: scan.running,
    fps: +scan.fps.toFixed(1),
    frameMsP50: q(0.5),
    frameMsP95: q(0.95),
    quality: settings.quality,
    tier: scan.adaptive.tier,
    reason: scan.adaptive.reason,
    idle: scan.isIdle,
    pressure: scan.simPressure ? `${scan.simPressure} (simulated)` : scan.pressure,
    frames: scan.frames,
    detThreshold: detConf(),
    camera: cameraInfo(),
  };
}

/** Everything useful for diagnosing a problem on this phone (no plates except via the log). */
async function collect() {
  const nav = navigator;
  const d = {};
  d.app = {
    release: CFG.release, build: CFG.app, installed: matchMedia('(display-mode: standalone)').matches,
    serviceWorker: !!(nav.serviceWorker && nav.serviceWorker.controller), isolated: self.crossOriginIsolated === true,
    online: nav.onLine, uptime: `${Math.round((performance.now() - T0) / 1000)} s`,
  };
  let ua = null;
  try { if (nav.userAgentData) ua = await nav.userAgentData.getHighEntropyValues(['model', 'platformVersion', 'fullVersionList']); } catch (_) { /* not available */ }
  const conn = nav.connection || {};
  d.device = {
    model: (ua && ua.model) || null,
    os: ua ? `${ua.platform} ${ua.platformVersion || ''}`.trim() : null,
    browser: ua && ua.fullVersionList ? ua.fullVersionList.filter((b) => !/Not.?A.?Brand/i.test(b.brand)).map((b) => `${b.brand} ${b.version}`).join(', ') : nav.userAgent,
    cpuCores: nav.hardwareConcurrency || null,
    memory: nav.deviceMemory ? `${nav.deviceMemory} GB (rounded)` : null,
    screen: `${screen.width}×${screen.height} @${window.devicePixelRatio}x`,
    network: conn.effectiveType ? `${conn.effectiveType}${conn.downlink ? ` ~${conn.downlink} Mb/s` : ''}${conn.saveData ? ' · Data Saver' : ''}` : null,
    computePressure: 'PressureObserver' in window,
    wakeLock: 'wakeLock' in nav,
  };
  try {
    const b = await nav.getBattery();
    d.device.battery = `${Math.round(b.level * 100)}%${b.charging ? ', charging' : ''}`;
  } catch (_) { /* not available */ }
  if (nav.gpu) {
    try {
      const a = await nav.gpu.requestAdapter();
      if (a) {
        const info = a.info || (a.requestAdapterInfo ? await a.requestAdapterInfo() : {});
        d.gpu = { webgpu: true, vendor: info.vendor || null, architecture: info.architecture || null, description: info.description || null, fallbackAdapter: !!a.isFallbackAdapter };
      } else d.gpu = { webgpu: true, adapter: 'none' };
    } catch (e) { d.gpu = { webgpu: true, error: e.message }; }
  } else d.gpu = { webgpu: false };
  const i = engine.info || {};
  d.engine = {
    status: state.ready ? 'ready' : (state.bootError ? `failed: ${state.bootError.message}` : 'starting'),
    label: engineLabel(), runtime: i.version || CFG.ort.version, backend: i.ep || null, threads: i.threads || null,
    threadsSetting: settings.threads || 'auto', source: i.source || null, safeMode: safeModeOn(), gpuError: i.gpuError || null,
    runtimeDownloadMs: i.runtimeMs ?? null, loaded: [...engine.ready], timings: engine.timings, notes: i.tried && i.tried.length ? i.tried : null,
    startErrors: state.engineErrors.length ? state.engineErrors : null,
  };
  d.live = liveStats();
  // Per engine setup and quality tier, since the app opened (medians in ms), and a per-minute timeline.
  d.speed = scan.speed.summary();
  // Kept here as well as in the log, which only includes its last 200 lines.
  if (ui.bench || ui.threads) d.benchmarks = { models: ui.bench || null, threads: ui.threads ? ui.threads.rows : null };
  d.storage = {};
  try {
    const est = await nav.storage.estimate();
    d.storage.used = mb(est.usage || 0);
    d.storage.quota = mb(est.quota || 0);
    if (nav.storage.persisted) d.storage.persisted = await nav.storage.persisted();
  } catch (_) { /* not available */ }
  try {
    const names = await caches.keys();
    d.storage.caches = {};
    for (const n of names) d.storage.caches[n] = (await (await caches.open(n)).keys()).length;
  } catch (_) { /* not available */ }
  try {
    d.data = { historyPlates: (await plates.all()).length, testSamples: (await samples.all()).length };
  } catch (_) { /* db unavailable */ }
  d.settings = { ...settings };
  return d;
}

async function diagnosticsText() {
  const d = await collect();
  const lines = logEntries().slice(-200).map((e) => `${fmtT(e.t)} [${e.cat}] ${e.msg}${e.data ? ` ${JSON.stringify(e.data)}` : ''}`);
  return `PlateSight diagnostics · ${new Date().toISOString()}\n${JSON.stringify(d, null, 2)}\n\nLog (last ${lines.length}):\n${lines.join('\n')}\n`;
}

// ---------------------------------------------------------------- overview
async function renderOverview() {
  const body = $('#debugBody');
  if (!body.querySelector('.dov')) body.innerHTML = '<div class="dov"><p class="dnote">Collecting diagnostics…</p></div>';
  const d = await collect();
  if (ui.tab !== 'overview' || !ui.open) return;
  const t = engine.timings || {};
  const modelRows = Object.keys(MODELS).map((k) => {
    const tm = t[k];
    const on_ = engine.has(k);
    return `<tr><td>${esc(LABEL[k] || k)}</td><td>${mb(MODELS[k].bytes)}</td><td>${on_ ? (tm ? `${tm.downloadMs} ms` : 'cached') : '—'}</td><td>${on_ ? (tm ? `${tm.initMs} ms` : '✓') : 'not loaded'}</td></tr>`;
  }).join('');
  const live = d.live;
  body.innerHTML = `<div class="dov">
    ${section('Live', `
      <canvas class="dgraph" id="dbgGraph"></canvas>
      <div class="dlegend">${Object.entries(TIERS).map(([k, v]) => `<span><i style="background:${TIER_COLORS[k]}"></i>${v.label}</span>`).join('')}<span><i style="background:#f87171"></i>over budget</span><span><i class="dash"></i>${scan.adaptive.budget} ms budget</span></div>
      <div id="dbgLive">${liveKV(live)}</div>`)}
    ${section('Speed by quality', `<div id="dbgSpeed">${speedTable(d.speed)}</div>`)}
    ${section('AI engine', kv([
      ['Status', d.engine.status], ['Runs on', d.engine.label], ['ONNX Runtime', d.engine.runtime], ['Backend', d.engine.backend],
      ['Threads', d.engine.threads && `${d.engine.threads} (setting: ${d.engine.threadsSetting})`], ['Runtime from', d.engine.source],
      ['Runtime download', d.engine.runtimeDownloadMs != null ? `${d.engine.runtimeDownloadMs} ms` : null], ['Safe mode', d.engine.safeMode ? 'on' : 'off'],
      ['GPU error', d.engine.gpuError],
    ]) + `<table class="dtable"><thead><tr><th>Model</th><th>Size</th><th>Download</th><th>Start</th></tr></thead><tbody>${modelRows}</tbody></table>`
      + (d.engine.startErrors ? `<pre class="dpre">${esc(d.engine.startErrors.join('\n'))}</pre>` : ''))}
    ${section('Phone', kv([
      ['Model', d.device.model], ['System', d.device.os], ['Browser', d.device.browser], ['CPU cores', d.device.cpuCores], ['Memory', d.device.memory],
      ['Screen', d.device.screen], ['Battery', d.device.battery], ['Network', d.device.network],
      ['CPU pressure API', d.device.computePressure ? 'yes' : 'no'],
      ['WebGPU', d.gpu.webgpu ? (d.gpu.vendor || d.gpu.description ? `${d.gpu.vendor || ''} ${d.gpu.architecture || ''} ${d.gpu.description || ''}`.trim() : (d.gpu.error || d.gpu.adapter || 'yes')) : 'no'],
    ]))}
    ${section('App', kv([
      ['Version', `${d.app.release} · build ${d.app.build}`], ['Installed', d.app.installed ? 'yes' : 'no (browser tab)'],
      ['Offline support', d.app.serviceWorker ? 'yes' : 'no'], ['Multi-core capable', d.app.isolated ? 'yes' : 'no'],
      ['Storage used', d.storage.used && `${d.storage.used} of ${d.storage.quota}`], ['History', d.data && `${d.data.historyPlates} plates`],
      ['Test samples', d.data && String(d.data.testSamples)], ['Uptime', d.app.uptime],
    ]))}
    <div class="dactions">
      <button type="button" class="btn btn--primary" data-dact="copy">${icon('copy')}Copy diagnostics</button>
      <button type="button" class="btn btn--ghost" data-dact="share">${icon('share')}Share</button>
    </div>
    <p class="dnote">Diagnostics include the log, which lists plates read in this session.</p>
  </div>`;
  drawBigGraph();
}

function liveKV(l) {
  return kv([
    ['Scanning', l.running ? 'yes' : 'no'], ['Speed', l.running ? `${l.fps} fps · median ${l.frameMsP50 ?? '—'} ms · 95% under ${l.frameMsP95 ?? '—'} ms` : null],
    ['Quality', `${TIERS[l.tier].label}${l.quality === 'auto' ? ' (auto)' : ' (fixed)'}`], ['Why', l.quality === 'auto' ? l.reason : null],
    ['Idle mode', l.running ? (l.idle ? 'idle (no plate in view)' : 'active') : null], ['CPU pressure', l.pressure || (('PressureObserver' in window) ? 'no reading yet' : 'not reported by this browser')],
    ['Detection threshold', l.detThreshold], ['Camera', l.camera ? `${l.camera.width}×${l.camera.height} @ ${Math.round(l.camera.fps || 0)} fps` : null],
    ['Frames analysed', l.frames],
  ]);
}

function drawBigGraph() {
  const c = $('#dbgGraph');
  if (c) drawSpark(c, scan.perf, { budget: scan.adaptive.budget, span: 150 });
}

const setupLabel = (s) => (s === 'gpu' ? 'GPU' : `CPU, ${s.replace(/^cpu-(\d+)t$/, '$1')} thread${s === 'cpu-1t' ? '' : 's'}`);

/** Median frame times per engine setup and quality since the app opened (the detail is in the diagnostics). */
function speedTable(s) {
  if (!s) return '<p class="dnote">Nothing scanned yet.</p>';
  const rows = [];
  for (const [setup, tiers] of Object.entries(s.setups)) {
    for (const k of ORDER) {
      const r = tiers[k];
      if (r) rows.push(`<tr><td>${TIERS[k].label} <small>${esc(setupLabel(setup))}</small></td><td>${r.frames}</td><td>${r.active != null ? `${r.active} ms` : '—'}</td><td>${r.idle != null ? `${r.idle} ms` : '—'}</td></tr>`);
    }
  }
  return `<table class="dtable"><thead><tr><th>Quality</th><th>Frames</th><th>Plate in view</th><th>No plate</th></tr></thead><tbody>${rows.join('')}</tbody></table>
    <p class="dnote">Median time per frame since PlateSight opened. Live scanning aims for under ${scan.adaptive.budget} ms.</p>`;
}

function tickOverview() {
  if (!ui.open || ui.tab !== 'overview') return;
  const el = $('#dbgLive');
  if (el) el.innerHTML = liveKV(liveStats());
  const sp = $('#dbgSpeed');
  if (sp) sp.innerHTML = speedTable(scan.speed.summary());
  drawBigGraph();
}

// ---------------------------------------------------------------- log
const FILTERS = [['all', 'All'], ['engine', 'Engine'], ['scan', 'Scan'], ['perf', 'Speed'], ['data', 'Data'], ['error', 'Errors']];
function logMatch(e) {
  const f = ui.filter;
  if (f === 'all') return true;
  if (f === 'scan') return ['scan', 'track', 'photo', 'video'].includes(e.cat);
  if (f === 'data') return ['data', 'app'].includes(e.cat);
  return e.cat === f;
}

function renderLog() {
  const list = logEntries().filter(logMatch).slice(-300).reverse();
  $('#debugBody').innerHTML = `
    <div class="dfilters">${FILTERS.map(([k, l]) => `<button type="button" data-filter="${k}" class="${ui.filter === k ? 'on' : ''}">${l}</button>`).join('')}</div>
    <div class="dlog">${list.length ? list.map((e, n) => `
      <div class="lg lg--${e.cat}" data-i="${n}"><time>${fmtT(e.t)}</time><b>${esc(e.cat)}</b><span>${esc(e.msg)}</span>${e.data ? `<pre hidden>${esc(JSON.stringify(e.data, null, 1))}</pre>` : ''}</div>`).join('')
    : '<p class="dnote">Nothing logged yet.</p>'}</div>
    <div class="dactions"><button type="button" class="btn btn--ghost" data-dact="copylog">${icon('copy')}Copy log</button></div>`;
}

// ---------------------------------------------------------------- tools
function renderTools() {
  const { cores } = threadPlan();
  const seg = (key, opts, num = false) => `<div class="seg" data-dset="${key}" data-num="${num ? 1 : 0}">${opts.map(([v, l]) => `<button type="button" data-v="${v}" class="${String(settings[key]) === String(v) ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  const sw = (key, title, sub, on_ = settings[key]) => `<div class="row"><div class="row__text"><b>${title}</b>${sub ? `<small>${sub}</small>` : ''}</div><button type="button" class="switch" role="switch" data-dtoggle="${key}" aria-checked="${!!on_}" aria-label="${title}"></button></div>`;
  const threadOpts = [[0, 'Auto'], ...[1, 2, 4, 6, 8].filter((n) => n <= cores).map((n) => [n, String(n)])];
  const b = ui.bench;
  const th = ui.threads;
  const best = th && th.rows.filter((r) => !r.error).sort((x, y) => x.total - y.total)[0];
  $('#debugBody').innerHTML = `
    ${section('Benchmark', `<p class="dnote">Times each loaded model on this phone with ${engine.info && engine.info.threads > 1 ? `${engine.info.threads} threads` : '1 thread'}${engine.info && engine.info.ep === 'webgpu' ? ' on the GPU' : ''}.</p>
      ${b ? `<table class="dtable"><thead><tr><th>Model</th><th>Median</th><th>Mean</th><th>95%</th><th>Best</th></tr></thead><tbody>${b.results.map((r) => `<tr><td>${esc(LABEL[r.key] || r.key)}</td><td><b>${r.p50}</b></td><td>${r.mean}</td><td>${r.p95}</td><td>${r.min}</td></tr>`).join('')}</tbody></table><p class="dnote">Milliseconds per run, ${b.runs} runs each.</p>` : ''}
      <button type="button" class="btn btn--ghost" data-dact="bench" ${ui.busy ? 'disabled' : ''}>${icon('gauge')}Run benchmark</button>`)}
    ${section('CPU threads', `<p class="dnote">Finds the fastest thread count for this phone (${cores} cores). Takes about 20 seconds and pauses scanning.</p>
      ${th ? `<table class="dtable"><thead><tr><th>Threads</th><th>Finder</th><th>Reader</th><th>Total</th></tr></thead><tbody>${th.rows.map((r) => (r.error
    ? `<tr><td>${r.n}</td><td colspan="3">${esc(r.error)}</td></tr>`
    : `<tr class="${best && r.n === best.n ? 'best' : ''}"><td>${r.n}</td><td>${r.det} ms</td><td>${r.ocr} ms</td><td><b>${r.total.toFixed(1)} ms</b></td></tr>`)).join('')}</tbody></table>` : ''}
      <div class="dbtns">
        <button type="button" class="btn btn--ghost" data-dact="threads" ${ui.busy || !self.crossOriginIsolated ? 'disabled' : ''}>${icon('chip')}${th ? 'Run again' : 'Find the fastest'}</button>
        ${best && best.n !== settings.threads ? `<button type="button" class="btn btn--primary" data-dact="usethreads" data-n="${best.n}">Use ${best.n} thread${best.n > 1 ? 's' : ''}</button>` : ''}
      </div>
      ${self.crossOriginIsolated ? '' : '<p class="dnote">Multi-threading isn’t available in this tab (not cross-origin isolated).</p>'}`)}
    ${section('Tuning', `<div class="group__box">
      <div class="row row--stack"><div class="row__text"><b>Live quality</b><small>Now: ${TIERS[scan.adaptive.tier].label}${settings.quality === 'auto' ? ` — ${esc(scan.adaptive.reason)}` : ''}</small></div>
        ${seg('quality', [['auto', 'Auto'], ['fast', 'Fast'], ['balanced', 'Balanced'], ['sharp', 'Sharp']])}</div>
      <div class="row row--stack"><div class="row__text"><b>CPU threads</b><small>Applies after restarting the AI engine.</small></div>${seg('threads', threadOpts, true)}</div>
      <div class="row row--stack"><div class="row__text"><b>Detection threshold</b><small>Auto follows the sensitivity setting (${SENS[settings.sensitivity]}).</small></div>
        ${seg('dbgConf', [[0, 'Auto'], [0.15, '0.15'], [0.25, '0.25'], [0.35, '0.35'], [0.5, '0.50']], true)}</div>
      <div class="row row--stack"><div class="row__text"><b>Confirm threshold</b><small>Only confirm plates read with at least this confidence. The Accuracy tab suggests a value from your field tests.</small></div>
        ${seg('minScore', [[0, 'Off'], [0.5, '50%'], [0.7, '70%'], [0.8, '80%'], [0.9, '90%']], true)}</div>
      ${sw('idle', 'Idle mode', 'Analyse 4 frames a second while no plate is in view.')}
      ${sw('dbgRaw', 'Show raw detections', 'Every box the plate finder proposed, with its score.')}
      ${sw('simHot', 'Simulate a hot phone', 'Pretends the phone reports critical CPU pressure, to test the automatic step-down.', !!scan.simPressure)}
    </div>`)}
    ${section('Engine', `<div class="dbtns dbtns--wrap">
      <button type="button" class="btn btn--ghost" data-dact="restart">${icon('refresh')}Restart AI engine</button>
      <button type="button" class="btn btn--ghost" data-dact="loadall">${icon('download')}Download all models</button>
      ${safeModeOn() ? `<button type="button" class="btn btn--ghost" data-dact="unsafe">${icon('shield')}Leave safe mode</button>` : ''}
      <button type="button" class="btn btn--ghost" data-dact="clearcache">${icon('trash')}Clear cache & reload</button>
      <button type="button" class="btn btn--danger" data-dact="off">${icon('close')}Turn off debug mode</button>
    </div>`)}`;
}

async function runBench() {
  if (ui.busy || !state.ready) { toast(state.ready ? 'Busy' : 'The AI engine isn’t ready yet'); return; }
  ui.busy = true;
  renderTools();
  try {
    const runs = 20;
    const r = await engine.bench(runs);
    ui.bench = { ...r, runs };
    log('perf', 'Benchmark', r);
  } catch (err) {
    toast(`Benchmark failed: ${err.message}`);
    log('error', `Benchmark failed: ${err.message}`);
  }
  ui.busy = false;
  if (ui.tab === 'tools') renderTools();
}

async function runThreadBench() {
  if (ui.busy) return;
  ui.busy = true;
  const { cores } = threadPlan();
  const counts = [...new Set([1, 2, 4, 6, 8].filter((n) => n <= cores))];
  const had = [...new Set([...NEED, ...engine.ready])];
  const wasScanning = scan.running;
  if (wasScanning) stopScan();
  state.ready = false;
  const rows = [];
  ui.threads = { rows };
  renderTools();
  try {
    for (const n of counts) {
      setStatus('loading', `Testing ${n} thread${n > 1 ? 's' : ''}…`);
      try {
        await engine.boot({ threads: n, gpu: false, prefer: 'site' });
        await Promise.all(NEED.map((k) => engine.load(k)));
        const r = await engine.bench(12);
        const det = r.results.find((x) => x.key === 'det384');
        const ocr = r.results.find((x) => x.key === 'ocrFast');
        rows.push({ n, det: det.p50, ocr: ocr.p50, total: det.p50 + ocr.p50 });
      } catch (err) {
        rows.push({ n, error: err.message });
      }
      if (ui.tab === 'tools') renderTools();
    }
  } finally {
    engine.terminate();
    await startEngine({ reason: 'after the thread benchmark', keys: had });
    ui.busy = false;
    if (wasScanning && state.mode === 'scan') startScan();
  }
  const best = rows.filter((r) => !r.error).sort((x, y) => x.total - y.total)[0];
  log('perf', `Thread benchmark: fastest with ${best ? best.n : '?'} thread${best && best.n > 1 ? 's' : ''}`, rows);
  if (ui.tab === 'tools') renderTools();
}

// ---------------------------------------------------------------- accuracy
function chart(curve, sugg) {
  const W = 320;
  const H = 150;
  const px = (t) => 30 + (t / 0.95) * (W - 40);
  const py = (v) => 12 + (1 - v) * (H - 34);
  const line = (key) => curve.map((c) => `${px(c.t).toFixed(1)},${py(c[key]).toFixed(1)}`).join(' ');
  const grid = [0, 0.5, 1].map((v) => `<line x1="30" x2="${W - 10}" y1="${py(v)}" y2="${py(v)}" class="g"/><text x="24" y="${py(v) + 3.5}" text-anchor="end">${v * 100}%</text>`).join('');
  const xt = [0, 0.25, 0.5, 0.75, 0.95].map((t) => `<text x="${px(t)}" y="${H - 6}" text-anchor="middle">${Math.round(t * 100)}</text>`).join('');
  const mark = (t, cls, label) => `<line x1="${px(t)}" x2="${px(t)}" y1="8" y2="${H - 22}" class="${cls}"/><text x="${px(t) + 4}" y="18" class="${cls}">${label}</text>`;
  return `<svg class="dchart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Accuracy and coverage by confidence threshold">
    ${grid}${xt}
    ${settings.minScore ? mark(settings.minScore, 'cur', 'now') : ''}
    ${sugg ? mark(sugg.t, 'sug', 'suggested') : ''}
    <polyline points="${line('coverage')}" class="cov"/><polyline points="${line('accuracy')}" class="acc"/>
  </svg>
  <div class="dlegend"><span><i style="background:var(--mint)"></i>right, of plates reported</span><span><i style="background:var(--sky)"></i>plates still reported</span><span>x: minimum confidence %</span></div>`;
}

async function renderAccuracy() {
  ui.urls.forEach((u) => URL.revokeObjectURL(u));
  ui.urls = [];
  const list = await samples.all().catch(() => []);
  if (ui.tab !== 'accuracy' || !ui.open) return;
  const body = $('#debugBody');
  if (!list.length) {
    body.innerHTML = `<div class="dempty">${icon('flask')}<b>No field tests yet</b>
      <span>Open any plate you’ve read (tap a plate in the scan tray, a result card or a history entry) and mark the reading <em>Right</em> or <em>Wrong</em>. PlateSight works out its real-world accuracy from your answers, suggests a confidence threshold, and turns them into a training set for the plate reader.</span></div>`;
    return;
  }
  const st = accuracyStats(list);
  const curve = thresholdCurve(list, Array.from({ length: 20 }, (_, i) => i * 0.05));
  const sugg = suggestThreshold(list);
  const conf = confusions(list.filter((s) => s.truth !== s.predicted));
  const crops = list.filter((s) => s.image && s.imageKind === 'crop').length;
  const src = Object.entries(st.bySource).map(([k, v]) => `<span class="tag">${esc(k)} ${v.exact}/${v.n}</span>`).join('');
  let suggestion;
  if (sugg) {
    suggestion = `<p class="dnote">Plates read with <b>${Math.round(sugg.t * 100)}%</b> confidence or more were right <b>${pct(sugg.accuracy)}</b> of the time, and that still covers ${pct(sugg.coverage)} of plates.</p>
      ${Math.abs(settings.minScore - sugg.t) > 0.001 ? `<button type="button" class="btn btn--primary" data-dact="apply" data-t="${sugg.t}">Use ${Math.round(sugg.t * 100)}% as the confirm threshold</button>` : '<p class="dnote">✓ That’s your current confirm threshold.</p>'}`;
  } else if (list.length < 20) {
    suggestion = `<p class="dnote">Label at least 20 plates for a threshold suggestion (${list.length} so far).</p>`;
  } else {
    suggestion = '<p class="dnote">No threshold reaches 95% right yet with enough plates left; keep testing, or retrain the reader with the export below.</p>';
  }
  const misses = st.misses.map((s) => {
    let img = '';
    if (s.image) { const u = URL.createObjectURL(s.image); ui.urls.push(u); img = `<img src="${u}" alt="">`; }
    return `<div class="miss">${img}<div><div class="diff">${diffHTML(s.predicted, s.truth)}</div><small>read ${esc(s.predicted)} · really ${esc(s.truth)} · ${pct(s.conf || 0)} · ${esc(s.source)}</small></div>
      <button type="button" class="icon-btn" data-dact="delsample" data-id="${s.id}" aria-label="Delete sample">${icon('trash')}</button></div>`;
  }).join('');
  body.innerHTML = `
    <div class="dstats">
      <div><b>${st.n}</b><span>plates tested</span></div>
      <div><b>${pct(st.exactRate)}</b><span>read exactly right</span></div>
      <div><b>${pct1(st.charRate)}</b><span>characters right</span></div>
    </div>
    <div class="tags dsrc">${src}</div>
    ${section('Confidence threshold', chart(curve, sugg) + suggestion)}
    ${conf.length ? section('Common mix-ups', `<div class="tags">${conf.map((c) => `<span class="tag tag--warn">${esc(c.truth)} read as ${esc(c.read)} · ${c.n}×</span>`).join('')}</div>`) : ''}
    ${misses ? section('Recent misses', `<div class="misses">${misses}</div>`) : ''}
    ${section('Training set', `<p class="dnote">${crops} plate image${crops === 1 ? '' : 's'} ready to export in the format the plate reader is trained with (fast-plate-ocr), with a README on fine-tuning it.</p>
      <div class="dbtns"><button type="button" class="btn btn--primary" data-dact="export" ${crops ? '' : 'disabled'}>${icon('archive')}Export .zip</button>
      <button type="button" class="btn btn--ghost" data-dact="clearsamples">${icon('trash')}Clear</button></div>`)}`;
}

async function exportDataset() {
  const list = await samples.all();
  const images = new Map();
  for (const s of list) if (s.image && s.imageKind === 'crop') images.set(s.id, new Uint8Array(await s.image.arrayBuffer()));
  if (!images.size) { toast('No plate crops to export yet'); return; }
  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:]/g, '').replace('T', '-');
  const blob = zip(datasetFiles(list, images, stamp));
  const file = new File([blob], `platesight-dataset-${stamp}.zip`, { type: 'application/zip' });
  log('data', `Exported training set: ${images.size} images, ${list.length} labels, ${mb(blob.size)}`);
  await shareOrDownload(file, 'PlateSight training set', 'Training set saved to Downloads');
}

// ---------------------------------------------------------------- panel
function render() {
  $$('#debugTabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === ui.tab));
  if (ui.tab === 'overview') renderOverview();
  else if (ui.tab === 'log') renderLog();
  else if (ui.tab === 'tools') renderTools();
  else renderAccuracy();
}

export function openDebug() {
  ui.open = true;
  render();
  showSheet('sheetDebug');
  clearInterval(ui.timer);
  ui.timer = setInterval(() => {
    if (!$('#sheetDebug').classList.contains('open')) { ui.open = false; clearInterval(ui.timer); return; }
    tickOverview();
  }, 500);
}

async function onPanelClick(e) {
  const tab = e.target.closest('[data-tab]');
  if (tab) { ui.tab = tab.dataset.tab; $('#sheetDebug').scrollTop = 0; render(); return; }
  const f = e.target.closest('[data-filter]');
  if (f) { ui.filter = f.dataset.filter; renderLog(); return; }
  const lg = e.target.closest('.lg');
  if (lg && !e.target.closest('button')) { const pre = lg.querySelector('pre'); if (pre) pre.hidden = !pre.hidden; return; }
  const segEl = e.target.closest('[data-dset]');
  const sb = e.target.closest('button[data-v]');
  if (segEl && sb) {
    const key = segEl.dataset.dset;
    const v = segEl.dataset.num === '1' ? Number(sb.dataset.v) : sb.dataset.v;
    setSetting(key, v);
    log('app', `Debug: ${key} = ${v}`);
    if (key === 'threads') toast('Restart the AI engine to use it', { action: 'Restart', onAction: () => restartEngine('thread setting'), ms: 8000 });
    renderTools();
    return;
  }
  const tg = e.target.closest('[data-dtoggle]');
  if (tg) {
    const key = tg.dataset.dtoggle;
    if (key === 'simHot') {
      scan.simPressure = scan.simPressure ? null : 'critical';
      log('perf', scan.simPressure ? 'Simulating a hot phone (critical CPU pressure)' : 'Stopped simulating a hot phone');
    } else setSetting(key, !settings[key]);
    renderTools();
    return;
  }
  const b = e.target.closest('[data-dact]');
  if (!b) return;
  const act = b.dataset.dact;
  if (act === 'copy' || act === 'share') {
    const text = await diagnosticsText();
    if (act === 'copy') copyText(text, 'Diagnostics copied');
    else if (navigator.share) navigator.share({ title: 'PlateSight diagnostics', text }).catch(() => {});
    else copyText(text, 'Diagnostics copied');
  } else if (act === 'copylog') {
    copyText(logEntries().map((x) => `${fmtT(x.t)} [${x.cat}] ${x.msg}${x.data ? ` ${JSON.stringify(x.data)}` : ''}`).join('\n'), 'Log copied');
  } else if (act === 'bench') runBench();
  else if (act === 'threads') runThreadBench();
  else if (act === 'usethreads') {
    setSetting('threads', +b.dataset.n);
    toast(`Using ${b.dataset.n} threads`);
    restartEngine('thread setting').then(() => { if (ui.tab === 'tools') renderTools(); });
  } else if (act === 'restart') {
    toast('Restarting the AI engine…');
    restartEngine('restarted from debug panel').then(() => { if (ui.open) render(); });
  } else if (act === 'loadall') {
    toast('Downloading all models…');
    Promise.all(Object.keys(MODELS).map((k) => engine.load(k)))
      .then(() => { toast('All models ready'); if (ui.open) render(); })
      .catch((err) => toast(`Couldn’t load: ${err.message}`));
  } else if (act === 'unsafe') {
    resetSafeMode();
    restartEngine('left safe mode').then(() => { if (ui.open) render(); });
  } else if (act === 'clearcache') {
    toast('Clear every cached file and reload?', {
      action: 'Clear',
      ms: 6000,
      onAction: async () => {
        for (const k of await caches.keys()) await caches.delete(k);
        const regs = await navigator.serviceWorker.getRegistrations();
        await Promise.all(regs.map((r) => r.unregister()));
        location.reload();
      },
    });
  } else if (act === 'off') {
    setSetting('debug', false);
    toast('Debug mode off. Tap the logo 7 times to turn it back on.');
  } else if (act === 'apply') {
    setSetting('minScore', Number(b.dataset.t));
    toast(`Confirm threshold set to ${Math.round(Number(b.dataset.t) * 100)}%`);
    renderAccuracy();
  } else if (act === 'export') exportDataset();
  else if (act === 'delsample') { await samples.remove(Number(b.dataset.id)); renderAccuracy(); }
  else if (act === 'clearsamples') {
    toast('Delete all field-test samples?', { action: 'Delete', ms: 6000, onAction: async () => { await samples.clear(); log('data', 'Field-test samples cleared'); renderAccuracy(); } });
  }
}

function applyDebugUI() {
  $('#btnDebug').hidden = !settings.debug;
  document.body.classList.toggle('is-debug', !!settings.debug);
}

function wireEasterEgg() {
  let taps = 0;
  let last = 0;
  $('#brand').addEventListener('click', () => {
    const now = performance.now();
    taps = now - last < 1200 ? taps + 1 : 1;
    last = now;
    if (settings.debug) {
      if (taps >= 3) { taps = 0; toast('No need, you’re already a developer.', { action: 'Open', onAction: openDebug, ms: 4000 }); }
      return;
    }
    const left = 7 - taps;
    if (left <= 0) {
      taps = 0;
      localStorage.setItem('ps_dev', '1');
      setSetting('debug', true);
      haptic(60);
      log('app', 'Developer mode unlocked');
      toast('You are now a developer! Debug mode is on.', { action: 'Open', onAction: openDebug, ms: 7000 });
    } else if (taps >= 3) toast(`You are now ${left} step${left === 1 ? '' : 's'} away from being a developer.`, { ms: 1500 });
  });
}

export function initDebug() {
  $('#sheetDebug').addEventListener('click', onPanelClick);
  $('#btnDebug').onclick = openDebug;
  on('open-debug', openDebug);
  on('settings', ({ key }) => {
    if (key === 'debug') { applyDebugUI(); log('app', `Debug mode ${settings.debug ? 'on' : 'off'}`); }
  });
  on('samples-changed', () => { if (ui.open && ui.tab === 'accuracy') renderAccuracy(); });
  on('log', () => {
    if (!ui.open || ui.tab !== 'log' || ui.logQueued) return;
    if (document.querySelector('#debugBody .lg pre:not([hidden])')) return; // don't collapse what's being read
    ui.logQueued = true;
    requestAnimationFrame(() => { ui.logQueued = false; if (ui.open && ui.tab === 'log') renderLog(); });
  });
  applyDebugUI();
  wireEasterEgg();
}
