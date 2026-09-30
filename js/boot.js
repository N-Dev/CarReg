// AI engine start-up. Shows download progress on the first launch, tries the fastest setup first and
// falls back to safer ones without reloading the page, and explains clearly when the AI can't start.
//
// Watchdog: an attempt fails only after a long silence (no download progress at all), never because
// a download is slow. Before the engine starts initialising a silence means the download stalled;
// after, it means the engine hung (the only case that falls back to single-core safe mode).
import { CFG, MODELS, modelUrl } from './engine.js';
import { $, engine, state, settings, emit, log, mb, modelBytes } from './ctx.js';
import { toast, esc } from './ui.js';

export const NEED = ['det384', 'ocrFast'];
const LABELS = { runtime: 'AI engine', det384: 'Plate finder', det640: 'Plate finder (sharp)', ocrFast: 'Plate reader', ocrAcc: 'Plate reader (accurate)' };
const hangMs = () => Number(localStorage.getItem('ps_hang_ms')) || 40000;    // tests shorten these
const stallMs = () => Number(localStorage.getItem('ps_stall_ms')) || 60000;

export function setStatus(kind, text) {
  $('#status').dataset.state = kind;
  $('#statusText').textContent = text;
}

export function engineLabel() {
  const i = engine.info || {};
  if (i.ep === 'webgpu') return 'On-device · GPU';
  if (i.safe) return 'On-device · safe mode';
  return i.threads > 1 ? `On-device · ${i.threads} cores` : 'On-device';
}

/** Runtime files for the chosen mode (GPU runtime only when enabled and the browser has WebGPU). */
export function runtimeMode() { return settings.gpu && 'gpu' in navigator ? CFG.ort.gpu : CFG.ort.cpu; }

/** Threads: automatic = up to 4 (big cores on most phones), or the debug-mode override. */
export function threadPlan() {
  const cores = navigator.hardwareConcurrency || 4;
  const auto = Math.max(1, Math.min(4, cores));
  return { cores, auto, want: settings.threads > 0 ? Math.min(settings.threads, cores) : auto };
}

// Safe mode is remembered per app version, so every update gets a fresh chance at the fast engine.
export const safeModeOn = () => localStorage.getItem('ps_force_safe') === CFG.app;
export const resetSafeMode = () => localStorage.removeItem('ps_force_safe');

/** True when the runtime and these models are already on the phone (so no big download). */
async function alreadyDownloaded(keys, mode) {
  try {
    if (!('caches' in window)) return false;
    const models = await caches.open('ps-models');
    for (const k of keys) if (!(await models.match(modelUrl(k)))) return false;
    const rt = await caches.open(`ps-ort-${CFG.ort.version}`);
    return !!(await rt.match(new URL(`ort/${mode.wasm}`, location.href).href));
  } catch (_) { return false; }
}

// ---------------------------------------------------------------- first-launch screen
function bootScreen(keys, mode) {
  $('#boot').hidden = false;
  $('#bootTitle').textContent = 'Setting up PlateSight';
  const total = modelBytes(keys) + mode.wasmBytes;
  $('#bootText').textContent = `Downloading the on-device AI (${mb(total)}). This happens once, and nothing you scan ever leaves your phone.`;
  const parts = [['runtime', mode.wasmBytes], ...keys.map((k) => [k, MODELS[k].bytes])];
  $('#bootParts').innerHTML = parts.map(([k, bytes]) => `
    <li data-part="${k}"><span class="bp__name">${esc(LABELS[k] || k)}</span><span class="bp__size">${mb(bytes)}</span>
      <span class="bp__bar"><i></i></span></li>`).join('');
  $('#bootParts').hidden = false;
}

function bootPart(key, f) {
  const li = document.querySelector(`#bootParts [data-part="${key}"]`);
  if (!li) return;
  li.querySelector('i').style.width = `${(f * 100).toFixed(1)}%`;
  li.classList.toggle('done', f >= 1);
}

// ---------------------------------------------------------------- start-up
let running = null;
let shownAt = 0;
document.addEventListener('visibilitychange', () => { if (!document.hidden) shownAt = performance.now(); });

/** Starts the AI engine (only one start-up at a time). keys: models to load. */
export function startEngine(opts = {}) {
  if (!running) running = run(opts).finally(() => { running = null; });
  return running;
}

/** Restarts the engine in place, reloading every model that was loaded before. */
export function restartEngine(reason = 'restart') {
  const keys = [...new Set([...NEED, ...engine.ready])];
  engine.terminate();
  state.ready = false;
  return startEngine({ reason, keys });
}

async function run({ reason = 'launch', keys = NEED } = {}) {
  const t0 = performance.now();
  state.ready = false;
  state.starting = true;
  state.bootError = null;

  // A start that never finished (the tab crashed or froze while starting multi-threaded) means
  // multi-threading isn't safe on this phone, at least with this version of the app.
  const pending = localStorage.getItem('ps_boot') || '';
  if (pending.startsWith('pending:')) {
    const [threads, ver] = pending.slice(8).split('@');
    if (+threads > 1 && ver === CFG.app) localStorage.setItem('ps_force_safe', CFG.app);
    log('engine', 'The previous start-up never finished', { pending });
  }
  const forceSafe = safeModeOn();
  const { want } = threadPlan();
  const coi = self.crossOriginIsolated === true;
  const multi = coi && want > 1 && !forceSafe;
  const gpu = runtimeMode() === CFG.ort.gpu;
  // Fastest setup first. If it fails, the kind of failure picks the next try, without reloading:
  //   the engine hung or crashed    -> the same runtime on one core (safe mode, remembered)
  //   anything else (e.g. a damaged  -> the CDN's copy of the runtime, on one core
  //   copy of the runtime)
  //   a download failed              -> stop: no other setup will help
  // (Damaged runtime files are also caught earlier, by the service worker and the engine worker,
  // which then use the CDN's copy through this site, keeping every core.)
  const queue = [{ prefer: 'site', threads: multi ? want : 1, gpu, safe: forceSafe }];
  const tried = new Set();

  const firstMode = gpu ? CFG.ort.gpu : CFG.ort.cpu;
  const firstRun = !(await alreadyDownloaded(keys, firstMode));
  if (firstRun) bootScreen(keys, firstMode);
  setStatus('loading', 'Loading AI…');
  log('engine', `Starting (${reason})`, { keys, firstRun, coi, forceSafe, threads: multi ? want : 1, gpu });

  const errors = [];
  let threadTrouble = false;
  for (let i = 0; queue.length; i++) {
    const a = queue.shift();
    if (tried.has(`${a.prefer}:${a.threads}`)) continue;
    tried.add(`${a.prefer}:${a.threads}`);
    const mode = a.gpu ? CFG.ort.gpu : CFG.ort.cpu;
    const total = modelBytes(keys) + mode.wasmBytes;
    const sizes = { runtime: mode.wasmBytes, ...Object.fromEntries(keys.map((k) => [k, MODELS[k].bytes])) };
    if (i > 0) {
      $('#bootMeta').textContent = a.safe ? 'Retrying in safe mode (one core)…' : 'Retrying with the backup copy of the AI engine…';
      log('engine', `Retrying: ${a.prefer}, ${a.threads} thread${a.threads > 1 ? 's' : ''}`);
    }
    const got = new Map();
    let initStarted = false;
    let fail = null;
    let timer = 0;
    const watchdog = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        // A phone that hid or froze the app meanwhile hasn't stalled or hung: give it a fresh window.
        if (document.hidden || performance.now() - shownAt < 5000) { watchdog(); return; }
        fail(new Error(initStarted
          ? 'HANG: the AI engine stopped responding while starting'
          : 'DOWNLOAD: the download stalled (no data for a minute)'));
      }, initStarted ? hangMs() : stallMs());
    };
    const onProgress = (e) => {
      const p = e.detail;
      watchdog(); // any sign of life resets the watchdog
      if (p.stage === 'init') {
        if (!keys.includes(p.key)) return;
        initStarted = true;
        localStorage.setItem('ps_boot', `pending:${a.threads}@${CFG.app}`);
        $('#bootMeta').textContent = 'Starting the AI engine…';
        return;
      }
      if (!(p.key in sizes)) return;
      got.set(p.key, p.loaded);
      if (firstRun) bootPart(p.key, p.loaded / (p.total || sizes[p.key]));
      const loaded = [...got.values()].reduce((x, y) => x + y, 0);
      const f = Math.min(1, loaded / total);
      $('#bootBar').style.width = `${(f * 100).toFixed(1)}%`;
      if (!initStarted) $('#bootMeta').textContent = `${mb(loaded)} of ${mb(total)}`;
      setStatus('loading', `Loading AI ${Math.round(f * 100)}%`);
    };
    engine.addEventListener('progress', onProgress);
    const guard = new Promise((_, reject) => { fail = reject; });
    const work = (async () => {
      await engine.boot(a);
      await Promise.all(keys.map((k) => engine.load(k)));
    })();
    work.catch(() => {});
    watchdog();
    try {
      await Promise.race([work, guard]);
    } catch (err) {
      clearTimeout(timer);
      engine.removeEventListener('progress', onProgress);
      engine.terminate();
      const what = `${a.prefer}, ${a.threads} thread${a.threads > 1 ? 's' : ''}${a.gpu ? ', GPU' : ''}: ${err.message}`;
      errors.push(what);
      log('error', `Start-up attempt failed (${what})`);
      localStorage.setItem('ps_boot', 'ok'); // a failure we caught is not a crash
      if (/^(DOWNLOAD|RUNTIME):/.test(err.message)) {
        // A damaged model download must not be reused next time.
        if (/damaged/.test(err.message)) caches.open('ps-models').then((c) => Promise.all(keys.map((k) => c.delete(modelUrl(k))))).catch(() => {});
        break;
      }
      if (/^(HANG|CRASH):/.test(err.message)) {
        if (a.threads > 1) { threadTrouble = true; queue.push({ ...a, threads: 1, safe: true }); }
      } else if (a.prefer === 'site') {
        // The saved runtime may be damaged: drop it, and as a last resort use the CDN's copy directly
        // (only on one core: the runtime can't start its extra threads from another site).
        caches.delete(`ps-ort-${CFG.ort.version}`).catch(() => {});
        queue.push({ ...a, prefer: 'cdn', threads: 1, safe: a.threads > 1 });
      }
      continue;
    }
    clearTimeout(timer);
    engine.removeEventListener('progress', onProgress);
    localStorage.setItem('ps_boot', 'ok');
    if (a.safe && threadTrouble) localStorage.setItem('ps_force_safe', CFG.app);
    engine.info.safe = !!a.safe;
    state.engineErrors = errors;
    state.ready = true;
    state.starting = false;
    setStatus('ready', engineLabel());
    $('#boot').hidden = true;
    const ms = Math.round(performance.now() - t0);
    log('engine', `Ready in ${(ms / 1000).toFixed(1)} s · ${engineLabel()}`, { ...engine.info, timings: engine.timings, firstRun });
    emit('ready', { info: engine.info, firstRun, ms });
    if (settings.gpu && engine.info.gpuError) toast('GPU isn’t available here, using the CPU');
    if (a.safe) toast('Running in safe mode (one CPU core)', { action: 'Details', onAction: () => emit('open-settings'), ms: 6000 });
    if (state.updatePending) {
      state.updatePending = false;
      setTimeout(() => toast('PlateSight was updated', { action: 'Reload', onAction: () => location.reload(), ms: 12000 }), 1500);
    }
    return true;
  }

  state.engineErrors = errors;
  state.bootError = new Error(errors[errors.length - 1] || 'unknown error');
  state.starting = false;
  setStatus('error', 'AI didn’t load');
  $('#boot').hidden = false;
  $('#bootParts').hidden = true;
  $('#bootTitle').textContent = 'Couldn’t start the AI';
  $('#bootText').textContent = navigator.onLine === false
    ? `You’re offline. The first launch needs internet to download the AI (${mb(modelBytes(NEED) + runtimeMode().wasmBytes)}). After that PlateSight works offline.`
    : 'PlateSight couldn’t load its AI engine. Check your connection and try again. If it keeps happening, send a screenshot of the details below.';
  $('#bootBar').parentElement.hidden = true;
  const meta = $('#bootMeta');
  meta.classList.add('boot__meta--details');
  meta.textContent = `${errors.join('\n')}\nisolated: ${self.crossOriginIsolated === true} · service worker: ${!!(navigator.serviceWorker && navigator.serviceWorker.controller)} · app ${CFG.app}`;
  $('#bootRetry').hidden = false;
  log('error', 'The AI engine couldn’t start', errors);
  emit('boot-failed', { error: state.bootError });
  return false;
}

/** A new version of the app took over (service worker update). */
export function onAppUpdate() {
  log('app', 'A new version of PlateSight took over');
  if (state.bootError) { location.reload(); return; }      // stuck on an error: the new version may fix it
  if (!state.ready) { state.updatePending = true; return; } // never interrupt a download in progress
  toast('PlateSight was updated', { action: 'Reload', onAction: () => location.reload(), ms: 12000 });
}

export function initBoot() {
  $('#bootRetry').onclick = () => location.reload();
  engine.addEventListener('crash', (e) => {
    if (!state.ready) return; // failures while starting are handled by the retries
    state.ready = false;
    setStatus('error', 'AI engine stopped');
    log('error', 'The AI engine stopped', e.detail && e.detail.message);
    toast('The AI engine stopped', { action: 'Restart', onAction: () => restartEngine('crash'), ms: 15000 });
  });
  engine.addEventListener('loaded', (e) => {
    const d = e.detail || {};
    if (d.cached) return;
    log('engine', `Loaded ${d.key}`, { downloadMs: d.downloadMs, initMs: d.initMs, ep: d.ep });
  });
}
