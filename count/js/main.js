// TrafficSight: counts road users passing two lines on a live camera, with their speeds.
import { Engine, setupServiceWorker, PS, TC } from './engine.js';
import { Counter, KINDS, ORDER, regionFor, DEFAULT_LINES } from './counter.js';
import { summarize, toCSV } from './stats.js';
import { councilReport, when, period, rate } from './report.js';
import { drawHourChart, drawSpeedChart, COLORS } from './charts.js';
import { store, loadSettings, saveSettings, SENS } from './store.js';

const $ = (s) => document.querySelector(s);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (n) => Number(n).toLocaleString('en-IE');
const pad2 = (n) => String(n).padStart(2, '0');
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// Minimum time between analysed frames: while something moves, and while nothing has for a few seconds.
// "Fastest" takes every new camera frame the phone can keep up with; "Cooler" is for long, warm sessions.
const PACE = { fast: { active: 0, idle: 125 }, cool: { active: 100, idle: 333 } };
const IDLE_AFTER = 3000;
const TINY_MAX_MS = 60; // Auto keeps the standard model only while it runs this fast (about 15 frames a second)

const settings = loadSettings();
const engine = new Engine();
// The AI worker can be stopped by the phone (low memory): start it again; counting carries on.
engine.addEventListener('crash', () => { if (state.ready) { toast('The AI stopped. Restarting it…'); startEngine(); } });
const state = {
  ready: false, error: null, model: null, running: false, loop: 0, camera: false, editing: false,
  session: null, sessionId: null, counted: [], frames: 0, fpsT: [], ms: [], lastMove: 0, idle: false,
  aspect: 9 / 16, roi: [0, 0, 1, 1], lastRecord: null, flash: new Map(), pausedAt: null, lastTouch: Date.now(),
  updateWaiting: false, battery: null, totals: emptyTotals(), stages: [], seen: 0, stale: 0,
};
let counter = null;

const ICON = {
  chart: '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 20h16M7 16v-5M12 16V6M17 16v-8"/></svg>',
  gear: '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
  back: '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>',
};

// ---------------------------------------------------------------- small UI helpers
let toastTimer = 0;
function toast(text, { action = null, onAction = null, ms = 3200 } = {}) {
  const t = $('#toast');
  t.replaceChildren(document.createTextNode(text));
  if (action) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = action;
    b.onclick = () => { t.classList.remove('show'); onAction(); };
    t.append(b);
  }
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

function setStatus(kind, text) {
  $('#status').dataset.kind = kind;
  $('#statusText').textContent = text;
}

function openSheet(id) {
  $('#backdrop').hidden = false;
  $(id).hidden = false;
  requestAnimationFrame(() => $(id).classList.add('open'));
}
function closeSheets() {
  for (const s of document.querySelectorAll('.sheet')) { s.classList.remove('open'); s.hidden = true; }
  $('#backdrop').hidden = true;
}

async function shareOrDownload(blob, name, title) {
  const file = new File([blob], name, { type: blob.type });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title }); return; } catch (err) { if (err && err.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}

// ---------------------------------------------------------------- AI engine
function threads() { return Math.min(4, navigator.hardwareConcurrency || 4); }

async function startEngine() {
  state.ready = false;
  $('#btnStart').disabled = true;
  setStatus('loading', 'Starting the AI…');
  const onProgress = (e) => {
    const p = e.detail;
    if (p.stage === 'init') setStatus('loading', 'Starting the AI…');
    else if (p.total) setStatus('loading', `Downloading the AI ${Math.floor((p.loaded / p.total) * 100)}%`);
  };
  engine.addEventListener('progress', onProgress);
  try {
    try {
      await engine.boot({ threads: threads() });
    } catch (err) {
      if (threads() === 1) throw err;
      await engine.boot({ threads: 1 }); // multi-core start failed: one core still works
    }
    const want = settings.model === 'nano' ? 'nano' : 'tiny';
    await engine.load(want);
    state.model = want;
    if (settings.model === 'auto') {
      // Time the standard model; on a slow phone, switch to the light one.
      const b = await engine.bench('tiny', 6);
      if (b.p50 > TINY_MAX_MS) { await engine.load('nano'); state.model = 'nano'; }
      state.benchMs = b.p50;
    }
    state.ready = true;
    state.error = null;
    readyStatus();
    $('#btnStart').disabled = !state.camera;
  } catch (err) {
    state.error = err;
    setStatus('error', 'Couldn’t start the AI');
    toast(/DOWNLOAD|RUNTIME/.test(err.message) ? 'Couldn’t download the AI. The first start needs the internet.' : `The AI couldn’t start: ${err.message}`, { action: 'Try again', onAction: startEngine, ms: 12000 });
  } finally {
    engine.removeEventListener('progress', onProgress);
  }
}

function readyStatus() {
  if (!state.ready) return;
  const cores = engine.info && engine.info.threads > 1 ? `${engine.info.threads} cores` : '1 core';
  setStatus('ready', state.running ? 'Counting' : `Ready · ${TC.models[state.model].label} · ${cores}`);
}

// ---------------------------------------------------------------- camera
async function startCamera() {
  if (state.camera) return true;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
    });
    const cam = $('#cam');
    cam.srcObject = stream;
    await cam.play().catch(() => {});
    state.stream = stream;
    state.camera = true;
    $('#camEmpty').hidden = true;
    $('#btnStart').disabled = !state.ready;
    state.loop++;
    loop(state.loop);
    return true;
  } catch (err) {
    const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
    $('#camEmptyText').textContent = denied
      ? 'Camera access is blocked. Allow the camera for TrafficSight in Chrome (the icon left of the address, or App info → Permissions), then try again.'
      : `Couldn’t start the camera: ${err.message || err}`;
    $('#btnStartCam').textContent = 'Try again';
    return false;
  }
}

function stopCamera() {
  if (state.stream) state.stream.getTracks().forEach((t) => t.stop());
  state.stream = null;
  state.camera = false;
  frames.video = null; // watch the next stream's frames afresh
  for (const w of frames.waiters.splice(0)) w();
  $('#cam').srcObject = null;
  state.loop++;
}

async function maybeAutoStartCamera() {
  try {
    const p = await navigator.permissions.query({ name: 'camera' });
    if (p.state === 'granted' && !document.hidden) startCamera();
  } catch (_) { /* wait for a tap */ }
}

// The camera's frames as they arrive: a count and the newest one's capture time, so the loop can take
// the latest frame straight away instead of waiting for the next (worth up to a frame interval).
const frames = { n: 0, t: 0, waiters: [], video: null };
function watchFrames(v) {
  if (!v.requestVideoFrameCallback || frames.video === v) return;
  frames.video = v;
  const cb = (now, md) => {
    const ts = md.captureTime || md.presentationTime || now;
    frames.t = Number.isFinite(ts) && Math.abs(ts - performance.now()) < 2000 ? ts : performance.now(); // performance.now()'s clock
    frames.n++;
    for (const w of frames.waiters.splice(0)) w();
    if (state.camera) v.requestVideoFrameCallback(cb); else frames.video = null;
  };
  v.requestVideoFrameCallback(cb);
}
/**
 * Resolves true as soon as there's a camera frame newer than `seen`, or false after 100 ms without one
 * (or when frames can't be watched): the loop then goes on with the picture as it is and the clock time.
 */
function newFrame(seen) {
  if (!frames.video) return Promise.resolve(false);
  if (frames.n > seen) return Promise.resolve(true);
  return new Promise((r) => { frames.waiters.push(() => r(true)); setTimeout(() => r(frames.n > seen), 100); });
}

// ---------------------------------------------------------------- the counting loop
function newCounter() {
  state.roi = regionFor(settings.lines, state.aspect);
  counter = new Counter({ lines: settings.lines, distanceM: settings.distanceM, aspect: state.aspect, roi: state.roi });
}

async function loop(id) {
  const cam = $('#cam');
  while (state.camera && id === state.loop) {
    if (!state.ready || cam.readyState < 2 || !cam.videoWidth) { await sleep(150); continue; }
    const t0 = performance.now();
    watchFrames(cam);
    const fresh = await newFrame(state.seen);
    state.seen = frames.n;
    const t = fresh ? frames.t : performance.now();
    // Frames stopped being reported (a new video stream, say): start watching again.
    state.stale = fresh || !frames.video ? 0 : state.stale + 1;
    if (state.stale > 5) { frames.video = null; state.stale = 0; }
    const t1 = performance.now();
    const W = cam.videoWidth;
    const H = cam.videoHeight;
    const aspect = H / W;
    if (!counter || Math.abs(aspect - state.aspect) > 0.01) {
      if (counter && state.running) toast('The phone was turned round. Keep it still while counting.');
      state.aspect = aspect;
      newCounter();
    }
    // Only the area around the lines, already shrunk to the size the AI takes: much less to copy
    // between threads and to read back from the graphics chip than a whole camera frame.
    const size = TC.models[state.model].size;
    const [rx, ry, rw, rh] = state.roi;
    const sx = Math.round(rx * W);
    const sy = Math.round(ry * H);
    const sw = Math.max(1, Math.round(rw * W));
    const sh = Math.max(1, Math.round(rh * H));
    const scale = Math.min(size / sw, size / sh);
    let bmp;
    try {
      bmp = await createImageBitmap(cam, sx, sy, sw, sh, { resizeWidth: Math.max(1, Math.round(sw * scale)), resizeHeight: Math.max(1, Math.round(sh * scale)), resizeQuality: 'medium' });
    } catch (_) { await sleep(60); continue; }
    const t2 = performance.now();
    let res;
    try {
      res = await engine.detect(bmp, { key: state.model, crop: [sx, sy, sw, sh], frame: [W, H], conf: SENS[settings.sensitivity] || 0.3 });
    } catch (err) {
      if (!/^RESTART/.test(err.message)) { state.lastError = err.message; await sleep(400); }
      continue;
    }
    if (!state.camera || id !== state.loop) break;
    const t3 = performance.now();
    state.frames++;
    state.lastDets = res.dets;
    const out = counter.update(res.dets, t);
    if (state.running) {
      for (const tr of out.counted) { state.flash.set(tr.id, performance.now()); haptic(); }
      if (out.done.length) saveRecords(out.done);
    }
    // Moving things wake the loop up; parked cars don't.
    if (out.tracks.some((tr) => performance.now() - tr.lastT < 400 && Math.hypot(tr.vx, tr.vy * state.aspect) > 0.00003)) state.lastMove = t;
    state.idle = t - state.lastMove > IDLE_AFTER;
    const spent = performance.now() - t0;
    // Where each frame's time goes, for the status readout and the browser tests.
    state.stages.push({ wait: t1 - t0, grab: t2 - t1, ai: t3 - t2, prep: res.ms.prep, infer: res.ms.infer, post: res.ms.post, main: performance.now() - t3, total: spent });
    if (state.stages.length > 60) state.stages.shift();
    state.ms.push(res.ms.total);
    if (state.ms.length > 40) state.ms.shift();
    state.fpsT.push(performance.now());
    while (state.fpsT.length && performance.now() - state.fpsT[0] > 5000) state.fpsT.shift();
    maybeLighterModel();
    renderLive();
    const pace = PACE[settings.pace] || PACE.fast;
    const gap = state.idle ? pace.idle : pace.active;
    if (spent < gap) await sleep(gap - spent);
  }
}

/** Median time of each step of recent frames (ms), and frames a second: for the status readout. */
function stageMedians() {
  const s = state.stages;
  if (s.length < 5) return null;
  const med = (k) => { const v = s.map((x) => x[k]).sort((a, b) => a - b); return Math.round(v[v.length >> 1]); };
  return { wait: med('wait'), grab: med('grab'), prep: med('prep'), infer: med('infer'), total: med('total'), fps: (state.fpsT.length / 5).toFixed(1) };
}

/** Auto: a phone that can't keep up with the standard model (hot, or just slow) moves to the light one. */
function maybeLighterModel() {
  if (settings.model !== 'auto' || state.model !== 'tiny' || state.switching || state.ms.length < 40) return;
  const sorted = [...state.ms].sort((a, b) => a - b);
  if (sorted[20] < TINY_MAX_MS * 1.5) return;
  state.switching = true;
  engine.load('nano').then(() => { state.model = 'nano'; state.ms = []; readyStatus(); toast('Switched to the light model to keep up'); })
    .catch(() => {}).finally(() => { state.switching = false; });
}

function haptic() { try { if (navigator.vibrate) navigator.vibrate(12); } catch (_) { /* not supported */ } }

// ---------------------------------------------------------------- sessions
async function startCounting() {
  if (!state.ready || !state.camera || state.running) return;
  closeEditor();
  newCounter();
  const now = Date.now();
  state.session = {
    site: settings.site, started: now, ended: now, lines: settings.lines, distanceM: settings.distanceM, limit: settings.limit,
    dirNames: [...settings.dirNames], model: state.model, gaps: [], frames: 0, app: PS.app, release: TC.release,
  };
  state.sessionId = await store.newSession(state.session);
  state.session.id = state.sessionId;
  state.counted = [];
  state.totals = emptyTotals();
  state.running = true;
  state.lastTouch = Date.now();
  state.frames = 0;
  $('#btnStart').textContent = 'Stop';
  $('#btnStart').classList.replace('btn--primary', 'btn--stop');
  $('#btnSetup').disabled = true;
  requestWake();
  readyStatus();
  state.saveTimer = setInterval(saveSession, 30000);
  watchBattery();
}

async function stopCounting() {
  if (!state.running) return;
  state.running = false;
  const rest = counter ? counter.flush() : [];
  await saveRecords(rest);
  clearInterval(state.saveTimer);
  await saveSession();
  releaseWake();
  $('#btnStart').textContent = 'Start counting';
  $('#btnStart').classList.replace('btn--stop', 'btn--primary');
  $('#btnSetup').disabled = false;
  undim();
  readyStatus();
  let n = 0;
  let others = 0;
  for (const k of ORDER) { const c = state.totals[k][1] + state.totals[k][2]; if (KINDS[k].motor) n += c; else others += c; }
  toast(`Saved: ${fmt(n)} motor vehicle${n === 1 ? '' : 's'}, ${fmt(others)} other road user${others === 1 ? '' : 's'}`, { action: 'See results', onAction: () => showResults(state.sessionId) });
  if (state.updateWaiting) offerUpdate();
}

async function saveRecords(records) {
  if (!records.length || !state.sessionId) return;
  // Frame times are on the page's clock (ms since it opened); store the real date and time.
  const real = records.map((r) => ({ ...r, t: Math.round(performance.timeOrigin + r.t) }));
  state.counted.push(...real);
  for (const r of real) if (state.totals[r.kind]) state.totals[r.kind][r.dir]++;
  state.lastRecord = real[real.length - 1];
  try { await store.addEvents(state.sessionId, real); } catch (err) { toast(`Couldn’t save counts: ${err.message}`); }
}

async function saveSession() {
  if (!state.session) return;
  state.session.ended = Date.now();
  state.session.frames = (state.session.frames || 0) + state.frames;
  state.frames = 0;
  try { await store.saveSession(state.session); } catch (_) { /* tried again in 30 s */ }
}

// Screen wake lock: the phone mustn't sleep while counting.
async function requestWake() { try { state.wake = await navigator.wakeLock.request('screen'); } catch (_) { state.wake = null; } }
function releaseWake() { try { if (state.wake) state.wake.release(); } catch (_) { /* ignore */ } state.wake = null; }

async function watchBattery() {
  try {
    const b = await navigator.getBattery();
    const check = () => {
      state.battery = b;
      if (state.running && !b.charging && b.level < 0.25 && !state.warnedBattery) {
        state.warnedBattery = true;
        toast('Battery below 25%. Plug the phone in to keep counting.', { ms: 8000 });
      }
    };
    b.addEventListener('levelchange', check);
    b.addEventListener('chargingchange', check);
    check();
  } catch (_) { /* no battery info */ }
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    if (state.running) state.pausedAt = Date.now();
    if (state.camera) { stopCamera(); state.resumeCamera = true; }
  } else {
    if (state.running && state.pausedAt) {
      state.session.gaps.push([state.pausedAt, Date.now()]);
      state.pausedAt = null;
      toast('Counting paused while TrafficSight was in the background');
    }
    if (state.resumeCamera) { state.resumeCamera = false; startCamera(); }
    if (state.running) requestWake();
  }
});

// ---------------------------------------------------------------- dim screen
function noteTouch() {
  state.lastTouch = Date.now();
  if (!$('#dim').hidden) undim();
}
function undim() { $('#dim').hidden = true; }
setInterval(() => {
  if (state.running && settings.dimAfter && Date.now() - state.lastTouch > settings.dimAfter * 1000 && $('#dim').hidden && !state.editing) $('#dim').hidden = false;
}, 2000);

// ---------------------------------------------------------------- live display
function emptyTotals() {
  const t = {};
  for (const k of ORDER) t[k] = { 1: 0, 2: 0 };
  return t;
}
/** Saved counts plus road users counted but still in view. */
function liveTotals() {
  const t = emptyTotals();
  for (const k of ORDER) { t[k][1] = state.totals[k][1]; t[k][2] = state.totals[k][2]; }
  if (counter && state.running) for (const tr of counter.pending.values()) { const k = kindOf(tr); if (t[k]) t[k][tr.dir]++; }
  return t;
}
function kindOf(tr) {
  let best = null;
  for (const [k, v] of Object.entries(tr.votes)) if (!best || v > tr.votes[best]) best = k;
  return best;
}

let lastPanel = 0;
function renderLive() {
  const now = performance.now();
  if (now - lastPanel < 250) return;
  lastPanel = now;
  const t = liveTotals();
  const names = settings.dirNames;
  const rows = ORDER.filter((k) => t[k][1] + t[k][2] > 0 || ['car', 'truck', 'bicycle', 'person'].includes(k));
  $('#counts').innerHTML = `<table><thead><tr><th></th><th title="${esc(names[0])}">A→B</th><th title="${esc(names[1])}">B→A</th><th>Total</th></tr></thead><tbody>${
    rows.map((k) => `<tr><td>${KINDS[k].label}</td><td>${t[k][1]}</td><td>${t[k][2]}</td><td><b>${t[k][1] + t[k][2]}</b></td></tr>`).join('')}</tbody></table>`;
  if (state.running) {
    const secs = Math.floor((Date.now() - state.session.started) / 1000);
    $('#clock').textContent = `${Math.floor(secs / 3600)}:${pad2(Math.floor(secs / 60) % 60)}:${pad2(secs % 60)}`;
    $('#siteLabel').textContent = `counting${settings.site ? ` · ${settings.site}` : ''}`;
  } else {
    $('#clock').textContent = 'Not counting';
    $('#siteLabel').textContent = settings.site || (settings.setupDone ? '' : 'Tap Set up to put the lines on your road');
  }
  $('#dirKey').textContent = `A→B: ${names[0]} · B→A: ${names[1]}`;
  const fps = state.fpsT.length / 5;
  if (state.ready && state.camera) setStatus('ready', `${state.running ? 'Counting' : 'Watching'} · ${fps.toFixed(fps < 10 ? 1 : 0)} fps${state.idle ? ' · idle' : ''}`);
  const last = state.lastRecord;
  const pill = $('#speedPill');
  pill.hidden = !last;
  if (last) pill.textContent = `Last: ${KINDS[last.kind].one}${last.speed != null ? ` · ${Math.round(last.speed)} km/h` : ''}`;
  if (!$('#dim').hidden) {
    let motor = 0;
    for (const k of ORDER) if (KINDS[k].motor) motor += t[k][1] + t[k][2];
    $('#dimBig').textContent = fmt(motor);
    $('#dimRow').textContent = ['bicycle', 'person', 'dog'].map((k) => `${t[k][1] + t[k][2]} ${KINDS[k].label.toLowerCase()}`).join(' · ');
    const b = state.battery;
    $('#dimFoot').textContent = `Counting ${$('#clock').textContent}${b ? ` · battery ${Math.round(b.level * 100)}%${b.charging ? ', charging' : ''}` : ''} · tap to wake`;
  }
}

// ---------------------------------------------------------------- overlay (lines, boxes) and line editing
function videoRect() {
  const cam = $('#cam');
  const st = $('#stage').getBoundingClientRect();
  const vw = cam.videoWidth || 16;
  const vh = cam.videoHeight || 9;
  const s = Math.min(st.width / vw, st.height / vh);
  const w = vw * s;
  const h = vh * s;
  return { x: (st.width - w) / 2, y: (st.height - h) / 2, w, h, sw: st.width, sh: st.height };
}

function drawOverlay() {
  requestAnimationFrame(drawOverlay);
  const c = $('#overlay');
  const r = videoRect();
  const dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(r.sw * dpr) || c.height !== Math.round(r.sh * dpr)) { c.width = Math.round(r.sw * dpr); c.height = Math.round(r.sh * dpr); }
  const ctx = c.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, r.sw, r.sh);
  if (!state.camera && !state.editing) return;
  const X = (x) => r.x + x * r.w;
  const Y = (y) => r.y + y * r.h;
  // The analysed area, as faint corner marks.
  const [rx, ry, rw, rh] = state.roi;
  ctx.strokeStyle = 'rgba(255,255,255,0.35)';
  ctx.lineWidth = 1.5;
  const k = 14;
  for (const [x, y, dx, dy] of [[rx, ry, 1, 1], [rx + rw, ry, -1, 1], [rx, ry + rh, 1, -1], [rx + rw, ry + rh, -1, -1]]) {
    ctx.beginPath();
    ctx.moveTo(X(x) + dx * k, Y(y));
    ctx.lineTo(X(x), Y(y));
    ctx.lineTo(X(x), Y(y) + dy * k);
    ctx.stroke();
  }
  // Tracks seen recently.
  const now = performance.now();
  if (counter) {
    ctx.font = '600 12px Roboto, system-ui, sans-serif';
    for (const tr of counter.tracker.tracks) {
      if (now - tr.lastT > 500) continue;
      // Where it is now, not where the AI last saw it: moved on at its measured speed, so boxes glide
      // with the picture between analysed frames (up to a third of a second ahead).
      const ahead = Math.max(0, Math.min(330, now - tr.lastT));
      const [x1, y1, x2, y2] = [tr.box[0] + tr.vx * ahead, tr.box[1] + tr.vy * ahead, tr.box[2] + tr.vx * ahead, tr.box[3] + tr.vy * ahead];
      const flash = state.flash.get(tr.id);
      const hot = flash && now - flash < 600;
      ctx.lineWidth = hot ? 3 : 1.5;
      ctx.strokeStyle = tr.counted ? '#6ee7b7' : 'rgba(255,255,255,0.55)';
      ctx.strokeRect(X(x1), Y(y1), X(x2) - X(x1), Y(y2) - Y(y1));
      const kind = kindOf(tr);
      const sp = tr.counted ? counter.speedOf(tr) : null;
      const label = `${KINDS[kind] ? KINDS[kind].one : kind}${sp != null ? ` ${Math.round(sp)} km/h` : ''}`;
      const tw = ctx.measureText(label).width + 10;
      ctx.fillStyle = tr.counted ? 'rgba(6,40,30,0.85)' : 'rgba(0,0,0,0.55)';
      ctx.fillRect(X(x1), Y(y1) - 18, tw, 17);
      ctx.fillStyle = tr.counted ? '#6ee7b7' : '#fff';
      ctx.fillText(label, X(x1) + 5, Y(y1) - 5);
    }
  }
  // The counting lines.
  const lines = state.editing ? state.draft.lines : settings.lines;
  for (const [name, l] of [['A', lines.a], ['B', lines.b]]) {
    ctx.lineCap = 'round';
    ctx.strokeStyle = 'rgba(0,0,0,0.55)';
    ctx.lineWidth = 6;
    ctx.beginPath(); ctx.moveTo(X(l[0]), Y(l[1])); ctx.lineTo(X(l[2]), Y(l[3])); ctx.stroke();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 2.5;
    ctx.beginPath(); ctx.moveTo(X(l[0]), Y(l[1])); ctx.lineTo(X(l[2]), Y(l[3])); ctx.stroke();
    const top = l[1] < l[3] ? [l[0], l[1]] : [l[2], l[3]];
    ctx.fillStyle = '#fff';
    ctx.beginPath(); ctx.arc(X(top[0]), Y(top[1]) - 16, 11, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#07090d';
    ctx.font = '700 13px Roboto, system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(name, X(top[0]), Y(top[1]) - 11.5);
    ctx.textAlign = 'left';
    if (state.editing) {
      for (const [hx, hy] of [[l[0], l[1]], [l[2], l[3]]]) {
        ctx.fillStyle = 'rgba(110,231,183,0.25)';
        ctx.beginPath(); ctx.arc(X(hx), Y(hy), 18, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = '#6ee7b7';
        ctx.beginPath(); ctx.arc(X(hx), Y(hy), 7, 0, Math.PI * 2); ctx.fill();
      }
    }
  }
  // The measured distance between them.
  const ma = [(lines.a[0] + lines.a[2]) / 2, (lines.a[1] + lines.a[3]) / 2];
  const mb = [(lines.b[0] + lines.b[2]) / 2, (lines.b[1] + lines.b[3]) / 2];
  const dist = state.editing ? state.draft.distanceM : settings.distanceM;
  const label = `${dist} m`;
  ctx.font = '600 12px Roboto, system-ui, sans-serif';
  const lw = ctx.measureText(label).width + 12;
  const lx = X((ma[0] + mb[0]) / 2) - lw / 2;
  const ly = Y(Math.max(lines.a[3], lines.b[3], lines.a[1], lines.b[1])) + 8;
  ctx.fillStyle = 'rgba(0,0,0,0.6)';
  ctx.fillRect(lx, Math.min(ly, r.y + r.h - 20), lw, 18);
  ctx.fillStyle = '#fff';
  ctx.fillText(label, lx + 6, Math.min(ly, r.y + r.h - 20) + 13);
}

// Dragging line ends (or whole lines) while setting up.
let drag = null;
function pointerToFrame(e) {
  const r = videoRect();
  const b = $('#overlay').getBoundingClientRect();
  return [(e.clientX - b.left - r.x) / r.w, (e.clientY - b.top - r.y) / r.h, r];
}
function onPointerDown(e) {
  if (!state.editing) return;
  const [fx, fy, r] = pointerToFrame(e);
  const near = (x, y) => Math.hypot((x - fx) * r.w, (y - fy) * r.h);
  let best = null;
  for (const name of ['a', 'b']) {
    const l = state.draft.lines[name];
    for (const end of [0, 1]) {
      const d = near(l[end * 2], l[end * 2 + 1]);
      if (d < 30 && (!best || d < best.d)) best = { name, end, d };
    }
  }
  if (!best) {
    // Grab a whole line near the pointer.
    for (const name of ['a', 'b']) {
      const l = state.draft.lines[name];
      const dx = (l[2] - l[0]) * r.w;
      const dy = (l[3] - l[1]) * r.h;
      const len = Math.hypot(dx, dy) || 1;
      const u = (((fx - l[0]) * r.w) * dx + ((fy - l[1]) * r.h) * dy) / (len * len);
      const px = l[0] + u * (l[2] - l[0]);
      const py = l[1] + u * (l[3] - l[1]);
      const d = near(px, py);
      if (u > 0 && u < 1 && d < 24 && (!best || d < best.d)) best = { name, end: -1, d, from: [fx, fy], orig: [...l] };
    }
  }
  if (!best) return;
  drag = best;
  $('#overlay').setPointerCapture(e.pointerId);
  e.preventDefault();
}
function onPointerMove(e) {
  if (!drag) return;
  const [fx, fy] = pointerToFrame(e);
  const clamp = (v) => Math.max(0, Math.min(1, v));
  const l = state.draft.lines[drag.name];
  if (drag.end >= 0) { l[drag.end * 2] = clamp(fx); l[drag.end * 2 + 1] = clamp(fy); } else {
    const dx = fx - drag.from[0];
    const dy = fy - drag.from[1];
    const o = drag.orig;
    const mx = Math.max(-Math.min(o[0], o[2]), Math.min(dx, 1 - Math.max(o[0], o[2])));
    const my = Math.max(-Math.min(o[1], o[3]), Math.min(dy, 1 - Math.max(o[1], o[3])));
    state.draft.lines[drag.name] = [o[0] + mx, o[1] + my, o[2] + mx, o[3] + my];
  }
}
function onPointerUp() { drag = null; }

function limitChips(value) {
  $('#fLimit').innerHTML = [30, 50, 60, 80, 100, 120].map((v) => `<button type="button" data-v="${v}" class="${v === value ? 'on' : ''}">${v}</button>`).join('');
}

function openEditor() {
  if (state.running) return;
  $('#toast').classList.remove('show');
  state.editing = true;
  state.draft = { lines: JSON.parse(JSON.stringify(settings.lines)), distanceM: settings.distanceM, limit: settings.limit };
  $('#fDistance').value = settings.distanceM;
  limitChips(settings.limit);
  $('#fDir1').value = settings.dirNames[0];
  $('#fDir2').value = settings.dirNames[1];
  $('#fSite').value = settings.site;
  $('#setup').hidden = false;
  $('#editTip').hidden = false;
  $('#tcApp').classList.add('is-editing');
}

function closeEditor(save = false) {
  if (!state.editing) return;
  if (save) {
    const d = parseFloat($('#fDistance').value);
    settings.lines = state.draft.lines;
    settings.distanceM = Number.isFinite(d) && d >= 1 ? Math.round(d * 10) / 10 : settings.distanceM;
    settings.limit = state.draft.limit;
    settings.dirNames = [$('#fDir1').value.trim() || 'A to B', $('#fDir2').value.trim() || 'B to A'];
    settings.site = $('#fSite').value.trim();
    settings.setupDone = true;
    saveSettings(settings);
    newCounter();
  }
  state.editing = false;
  $('#setup').hidden = true;
  $('#editTip').hidden = true;
  $('#tcApp').classList.remove('is-editing');
  renderLive();
}

// Default direction names from where the lines are: A left of B means A→B is left to right.
function suggestNames(lines) {
  const ax = (lines.a[0] + lines.a[2]) / 2;
  const bx = (lines.b[0] + lines.b[2]) / 2;
  return ax <= bx ? ['Left to right', 'Right to left'] : ['Right to left', 'Left to right'];
}

// ---------------------------------------------------------------- results
async function showResults(id = null) {
  $('#tcApp').dataset.screen = 'results';
  $('#screenCount').hidden = true;
  $('#screenResults').hidden = false;
  $('#btnResults').innerHTML = ICON.back;
  $('#btnResults').setAttribute('aria-label', 'Back to counting');
  if (id) await showSession(id);
  else await showSessionList();
}

function showCount() {
  $('#tcApp').dataset.screen = 'count';
  $('#screenResults').hidden = true;
  $('#screenCount').hidden = false;
  $('#btnResults').innerHTML = ICON.chart;
  $('#btnResults').setAttribute('aria-label', 'Results');
}

async function showSessionList() {
  if (state.running) await saveSession();
  const list = await store.sessions();
  const el = $('#results');
  if (!list.length) {
    el.innerHTML = '<div class="empty"><h2>No counts yet</h2><p>Set up the lines, then tap Start counting. Each counting session is saved here, with its charts, a CSV and a report for the council.</p></div>';
    return;
  }
  const rows = await Promise.all(list.map(async (s) => {
    const ev = await store.events(s.id);
    const motor = ev.filter((r) => KINDS[r.kind] && KINDS[r.kind].motor).length;
    const mins = Math.max(1, Math.round((s.ended - s.started) / 60000));
    return `<button class="sess" type="button" data-id="${s.id}"><div><b>${esc(s.site || 'Untitled site')}</b><small>${when(s.started)} · ${mins >= 60 ? `${Math.floor(mins / 60)} h ${pad2(mins % 60)}` : `${mins} min`}${state.running && s.id === state.sessionId ? ' · counting now' : ''}</small></div><span><b>${fmt(motor)}</b><small>vehicles</small></span></button>`;
  }));
  el.innerHTML = `<h2 class="results__title">Counting sessions</h2>${rows.join('')}`;
  el.querySelectorAll('.sess').forEach((b) => { b.onclick = () => showSession(Number(b.dataset.id)); });
}

async function showSession(id) {
  if (state.running && id === state.sessionId) await saveSession();
  const s = await store.session(id);
  if (!s) { showSessionList(); return; }
  const events = await store.events(id);
  const sum = summarize(events, { from: s.started, to: s.ended, limit: s.limit });
  const sp = sum.speeds.all;
  const names = s.dirNames;
  const el = $('#results');
  const tiles = [
    ['Motor vehicles', fmt(sum.motor), rate(sum)],
    ['Busiest hour', sum.peak ? fmt(sum.peak.motor) : '–', sum.peak ? `${pad2(new Date(sum.peak.start).getHours())}:00 to ${pad2((new Date(sum.peak.start).getHours() + 1) % 24)}:00` : ''],
    ['85% at or under', sp.n ? `${Math.round(sp.p85)} km/h` : '–', sp.n ? `${fmt(sp.n)} speeds` : 'no speeds yet'],
    [`Over ${s.limit} km/h`, sp.n ? `${sp.overPct}%` : '–', sp.n ? `${fmt(sp.over)} vehicles` : ''],
  ];
  const kinds = ORDER.filter((k) => sum.totals[k].all);
  el.innerHTML = `
    <button class="linkish" id="backToList" type="button">${ICON.back} All sessions</button>
    <h2 class="results__title">${esc(s.site || 'Untitled site')}</h2>
    <p class="results__sub">${period(sum.from, sum.to)} · lines ${s.distanceM} m apart</p>
    <div class="tiles">${tiles.map(([l, v, sub]) => `<div class="tile"><small>${l}</small><b>${v}</b><span>${sub}</span></div>`).join('')}</div>
    <section class="card">
      <h3>Motor vehicles per hour</h3>
      <div class="legend"><span><i style="background:${COLORS.dir[0]}"></i>${esc(names[0])}</span><span><i style="background:${COLORS.dir[1]}"></i>${esc(names[1])}</span></div>
      <div class="chart"><canvas id="chHours"></canvas><div class="tip" id="tipHours" hidden></div></div>
      <details class="tableview"><summary>Show as a table</summary><table><thead><tr><th>Hour</th><th>${esc(names[0])}</th><th>${esc(names[1])}</th><th>All road users</th></tr></thead><tbody>${
  sum.perHour.map((h) => `<tr><td>${pad2(new Date(h.start).getHours())}:00</td><td>${h.dirs[1]}</td><td>${h.dirs[2]}</td><td>${h.all}</td></tr>`).join('')}</tbody></table></details>
    </section>
    <section class="card">
      <h3>Speeds of motor vehicles</h3>
      <div class="legend"><span><i style="background:${COLORS.within}"></i>Within ${s.limit} km/h</span><span><i style="background:${COLORS.over}"></i>Over the limit</span></div>
      <div class="chart"><canvas id="chSpeeds"></canvas><div class="tip" id="tipSpeeds" hidden></div></div>
      <table class="numbers"><thead><tr><th></th><th>Both ways</th><th>${esc(names[0])}</th><th>${esc(names[1])}</th></tr></thead><tbody>
        ${[['Measured', (x) => fmt(x.n)], ['Average', (x) => (x.n ? x.mean : '–')], ['85th percentile', (x) => (x.n ? x.p85 : '–')], ['Fastest', (x) => (x.n ? x.max : '–')], [`Over ${s.limit}`, (x) => (x.n ? `${x.over} (${x.overPct}%)` : '–')]]
    .map(([l, f]) => `<tr><td>${l}</td><td>${f(sum.speeds.all)}</td><td>${f(sum.speeds[1])}</td><td>${f(sum.speeds[2])}</td></tr>`).join('')}
      </tbody></table>
    </section>
    <section class="card">
      <h3>Everything counted</h3>
      <table class="numbers"><thead><tr><th></th><th>${esc(names[0])}</th><th>${esc(names[1])}</th><th>Total</th></tr></thead><tbody>
        ${(kinds.length ? kinds : ['car']).map((k) => `<tr><td>${KINDS[k].label}</td><td>${sum.totals[k][1]}</td><td>${sum.totals[k][2]}</td><td><b>${sum.totals[k].all}</b></td></tr>`).join('')}
        <tr class="sub"><td>Long vehicles (7.5 m+)</td><td></td><td></td><td>${sum.long}</td></tr>
      </tbody></table>
    </section>
    <div class="actions">
      <button class="btn btn--primary" id="btnReport" type="button">Report for the council (PDF)</button>
      <button class="btn btn--ghost" id="btnCSV" type="button">Export CSV</button>
      <button class="btn btn--danger" id="btnDelete" type="button"${state.running && id === state.sessionId ? ' disabled' : ''}>Delete</button>
    </div>`;
  drawHourChart($('#chHours'), $('#tipHours'), sum.perHour, names);
  drawSpeedChart($('#chSpeeds'), $('#tipSpeeds'), sum);
  const base = `traffic-${(s.site || 'survey').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'survey'}-${new Date(s.started).toISOString().slice(0, 10)}`;
  $('#backToList').onclick = () => showSessionList();
  $('#btnCSV').onclick = () => shareOrDownload(new Blob([toCSV(events, { dirNames: names, site: s.site })], { type: 'text/csv' }), `${base}.csv`, 'Traffic counts');
  $('#btnReport').onclick = () => {
    const bytes = councilReport(s, sum, { app: PS.app, release: TC.release });
    shareOrDownload(new Blob([bytes], { type: 'application/pdf' }), `${base}.pdf`, 'Traffic survey');
  };
  $('#btnDelete').onclick = () => toast('Delete this session and its counts?', {
    action: 'Delete', ms: 6000, onAction: async () => { await store.deleteSession(id); showSessionList(); },
  });
  state.shownSession = { id, sum };
}

// ---------------------------------------------------------------- settings
function renderSettings() {
  document.querySelectorAll('#sheetSettings .seg').forEach((seg) => {
    const key = seg.dataset.set;
    seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', String(settings[key]) === b.dataset.v));
  });
  $('#aboutText').textContent = `TrafficSight ${TC.release} (build ${PS.app}). Detection by YOLOX (Megvii, Apache-2.0) running in ONNX Runtime Web ${PS.ort.version}. `
    + `Speeds are estimates from the time between the two lines.`;
}

function offerUpdate() {
  if (state.running) { state.updateWaiting = true; return; }
  state.updateWaiting = false;
  toast('TrafficSight was updated', { action: 'Reload', onAction: () => location.reload(), ms: 15000 });
}

// ---------------------------------------------------------------- wiring
function bind() {
  $('#btnResults').innerHTML = ICON.chart;
  $('#btnSettings').innerHTML = ICON.gear;
  $('#btnStartCam').onclick = () => startCamera();
  $('#btnStart').onclick = () => (state.running ? stopCounting() : startCounting());
  $('#btnSetup').onclick = () => (state.editing ? closeEditor(true) : openEditor());
  $('#btnSetupDone').onclick = () => closeEditor(true);
  $('#btnResetLines').onclick = () => {
    state.draft.lines = JSON.parse(JSON.stringify(DEFAULT_LINES));
    const n = suggestNames(state.draft.lines);
    $('#fDir1').value = n[0];
    $('#fDir2').value = n[1];
  };
  $('#setup').addEventListener('click', (e) => {
    const step = e.target.closest('[data-step]');
    if (step) {
      const v = parseFloat($('#fDistance').value) || settings.distanceM;
      $('#fDistance').value = Math.max(1, Math.round((v + Number(step.dataset.step)) * 2) / 2);
      state.draft.distanceM = parseFloat($('#fDistance').value);
    }
    const chip = e.target.closest('#fLimit button');
    if (chip) { state.draft.limit = Number(chip.dataset.v); limitChips(state.draft.limit); }
  });
  $('#fDistance').addEventListener('input', () => { const v = parseFloat($('#fDistance').value); if (Number.isFinite(v)) state.draft.distanceM = v; });
  const ov = $('#overlay');
  ov.addEventListener('pointerdown', onPointerDown);
  ov.addEventListener('pointermove', onPointerMove);
  ov.addEventListener('pointerup', onPointerUp);
  ov.addEventListener('pointercancel', onPointerUp);
  $('#btnResults').onclick = () => ($('#tcApp').dataset.screen === 'results' ? showCount() : showResults());
  $('#btnSettings').onclick = () => { renderSettings(); openSheet('#sheetSettings'); };
  $('#status').onclick = () => {
    if (state.error) { startEngine(); return; }
    const i = engine.info || {};
    const m = stageMedians();
    toast(`${state.model ? TC.models[state.model].label : 'No'} model · ${i.threads || 1} core${i.threads > 1 ? 's' : ''}`
      + (m ? ` · ${m.fps} fps · a frame takes ${m.total} ms: AI ${m.infer}, picture ${m.grab + m.prep}, waiting for the camera ${m.wait}` : ''), { ms: 8000 });
  };
  $('#backdrop').onclick = closeSheets;
  $('#sheetSettings').addEventListener('click', (e) => {
    const b = e.target.closest('.seg button');
    if (!b) return;
    const seg = b.closest('.seg');
    const key = seg.dataset.set;
    settings[key] = seg.dataset.num ? Number(b.dataset.v) : b.dataset.v;
    saveSettings(settings);
    renderSettings();
    if (key === 'model' && state.ready && !state.running) startEngine();
  });
  $('#btnClearAll').onclick = () => toast('Delete every counting session?', {
    action: 'Delete all', ms: 6000, onAction: async () => { await store.clear(); closeSheets(); toast('All counts deleted'); },
  });
  $('#dim').addEventListener('pointerdown', (e) => { e.stopPropagation(); noteTouch(); });
  document.addEventListener('pointerdown', noteTouch, true);
}

async function main() {
  // An older PlateSight service worker can hand this address PlateSight's page: take over and reload.
  if (!document.getElementById('tcApp')) {
    await setupServiceWorker();
    location.reload();
    return;
  }
  const sw = await setupServiceWorker(offerUpdate);
  if (sw.reloading) return;
  if (!settings.setupDone) settings.dirNames = suggestNames(settings.lines);
  bind();
  renderLive();
  requestAnimationFrame(drawOverlay);
  startEngine();
  maybeAutoStartCamera();
  if (!settings.setupDone) toast('Tip: tap Set up to put the lines on your road', { ms: 6000 });
}

// Test hooks for the browser tests.
window.__trafficSight = {
  state, settings, engine, store, startCounting, stopCounting, showResults, openEditor, closeEditor, startCamera, stopCamera,
  get counter() { return counter; },
  applySetup(cfg) { Object.assign(settings, cfg); saveSettings(settings); newCounter(); },
};

main();
