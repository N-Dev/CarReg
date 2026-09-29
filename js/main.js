import { Engine, MODELS, ORT_VERSION, setupServiceWorker } from './engine.js';
import { Tracker, vote } from './tracker.js';
import { loadSettings, saveSettings, SENS, history as plates } from './store.js';
import { clean, flagFor } from './formats.js';
import {
  icon, plateHTML, countryLabel, describe, pct, timeAgo, clock, mmss, dayLabel,
  toast, openSheet, closeSheet, bitmapToDataURL, esc, sleep,
} from './ui.js';

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const app = $('#app');
const settings = loadSettings();
const engine = new Engine();
const state = { mode: null, ready: false, bootError: null, installEvt: null };

// ============================================================ helpers
const score = (r) => Math.max(0, Math.min(1, (r.conf ?? 0) * (r.prob ?? 1)));
function formatName(r) {
  if (!r.valid) return '';
  if (r.profile === 'IE') return 'Irish';
  if (r.format === 'NI') return 'NI';
  if (r.profile === 'UK') return 'UK';
  return '';
}
function registered(r) {
  const i = r && r.info;
  if (!i || !i.year) return '';
  if (r.profile === 'IE') return i.period ? `${i.period} ${i.year}` : String(i.year);
  if (r.profile === 'UK') return i.period === 'Mar–Aug' ? `Mar–Aug ${i.year}` : `Sep ${i.year} – Feb ${i.year + 1}`;
  return String(i.year);
}
function haptic(ms = 18) { if (settings.haptics && navigator.vibrate) navigator.vibrate(ms); }
let audio = null;
function blip() {
  if (!settings.sound) return;
  try {
    audio = audio || new AudioContext();
    const o = audio.createOscillator();
    const g = audio.createGain();
    const t = audio.currentTime;
    o.type = 'sine';
    o.frequency.setValueAtTime(880, t);
    o.frequency.exponentialRampToValueAtTime(1320, t + 0.08);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.18, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.2);
    o.connect(g).connect(audio.destination);
    o.start(t);
    o.stop(t + 0.22);
  } catch (_) { /* audio unavailable */ }
}
async function copyText(text) {
  try { await navigator.clipboard.writeText(text); toast(`Copied ${text}`); } catch (_) { toast('Couldn’t copy on this device'); }
}
async function sharePlate(r) {
  const text = `${r.text}${r.region ? ` (${r.region})` : ''}`;
  if (navigator.share) { try { await navigator.share({ title: 'Number plate', text }); } catch (_) { /* cancelled */ } } else copyText(r.text);
}
function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}
function fitCanvas(canvas, el) {
  const w = el.clientWidth;
  const h = el.clientHeight;
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}
/** Maps frame coordinates into an element showing the frame with object-fit cover/contain. */
function mapper(w, h, fw, fh, mode) {
  const s = mode === 'cover' ? Math.max(w / fw, h / fh) : Math.min(w / fw, h / fh);
  const ox = (w - fw * s) / 2;
  const oy = (h - fh * s) / 2;
  return (b) => [ox + b[0] * s, oy + b[1] * s, ox + b[2] * s, oy + b[3] * s];
}
function drawPlateBox(ctx, [x1, y1, x2, y2], { color, label, solid, alpha = 1, badge = null, minTop = 0, maxW = Infinity }) {
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.lineWidth = 2.5;
  ctx.strokeStyle = color;
  ctx.shadowColor = color;
  ctx.shadowBlur = 14;
  roundRect(ctx, x1, y1, x2 - x1, y2 - y1, 6);
  ctx.stroke();
  ctx.shadowBlur = 0;
  if (badge != null) {
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x1, y1, 11, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#04221a';
    ctx.font = '800 12px Roboto, system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(badge), x1, y1 + 0.5);
    ctx.textAlign = 'left';
  }
  if (label) {
    ctx.font = '700 15px Roboto, system-ui, sans-serif';
    const tw = ctx.measureText(label).width + 22;
    const th = 28;
    const lx = Math.max(4, Math.min((x1 + x2) / 2 - tw / 2, maxW - tw - 4));
    let ly = y1 - th - 8;
    if (ly < minTop) ly = y2 + 8;
    ctx.fillStyle = solid ? color : 'rgba(12,15,21,0.84)';
    roundRect(ctx, lx, ly, tw, th, 14);
    ctx.fill();
    ctx.fillStyle = solid ? '#04221a' : '#ffffff';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, lx + 11, ly + th / 2 + 1);
  }
  ctx.restore();
}
const toDet = (d) => ({ box: d.box, score: d.score, read: d.reads ? d.reads[0] : null, thumb: d.thumb || null });

/** Collects confirmed plates by key, following a track even if its reading improves later. */
class Board {
  constructor() { this.map = new Map(); this.byTrack = new Map(); }
  put(t) {
    const r = t.result;
    if (!r) return null;
    const prev = this.byTrack.get(t.id);
    if (prev && prev !== r.key) {
      const pe = this.map.get(prev);
      if (pe) { pe.tracks.delete(t.id); if (!pe.tracks.size) this.map.delete(prev); }
    }
    let e = this.map.get(r.key);
    if (!e) {
      e = { key: r.key, r, first: t.first, last: t.last, tracks: new Set(), thumbURL: null, thumbScore: 0, brief: !t.confirmed, created: performance.now() };
      this.map.set(r.key, e);
    }
    e.tracks.add(t.id);
    this.byTrack.set(t.id, r.key);
    if (score(r) >= score(e.r) || e.r.n < r.n) e.r = r;
    e.first = Math.min(e.first, t.first);
    e.last = Math.max(e.last, t.last);
    e.brief = e.brief && !t.confirmed;
    if (t.thumb && t.thumbScore > e.thumbScore) {
      try { e.thumbURL = bitmapToDataURL(t.thumb); e.thumbScore = t.thumbScore; } catch (_) { /* closed bitmap */ }
    }
    return e;
  }
  get(key) { return this.map.get(key); }
  entries() { return [...this.map.values()]; }
  clear() { this.map.clear(); this.byTrack.clear(); }
}

function savePlate(r, source, thumb, time = Date.now()) {
  if (!settings.history || !r) return;
  plates.add({ key: r.key, text: r.text, region: r.region, profile: r.profile, valid: r.valid, conf: score(r), info: r.info, source, thumb, time })
    .then(() => { if (state.mode === 'history') renderHistory(); })
    .catch(() => {});
}

// ============================================================ status & boot
function setStatus(kind, text) {
  $('#status').dataset.state = kind;
  $('#statusText').textContent = text;
}
function engineLabel() {
  const i = engine.info || {};
  if (i.ep === 'webgpu') return 'On-device · GPU';
  if (i.safe) return 'On-device · safe mode';
  return i.threads > 1 ? `On-device · ${i.threads} cores` : 'On-device';
}
function showBoot(on) { $('#boot').hidden = !on; }

async function modelsCached(keys) {
  try {
    if (!('caches' in window)) return false;
    const c = await caches.open('ps-models-v1');
    for (const k of keys) if (!(await c.match(new URL(MODELS[k].url, location.href).href))) return false;
    return true;
  } catch (_) { return false; }
}

async function startEngine() {
  // A previous start that never finished (e.g. the tab crashed) means multi-threading is unsafe here.
  if (localStorage.getItem('ps_boot') === 'pending') localStorage.setItem('ps_force_safe', '1');
  const forceSafe = localStorage.getItem('ps_force_safe') === '1';
  const need = ['det384', 'ocrFast'];
  const total = need.reduce((a, k) => a + MODELS[k].bytes, 0);
  const firstRun = !(await modelsCached(need));
  if (firstRun) showBoot(true);
  setStatus('loading', 'Loading AI…');

  // Fastest setup first, then progressively safer ones, all without reloading the page.
  const cores = Math.max(1, Math.min(4, navigator.hardwareConcurrency || 4));
  const multi = self.crossOriginIsolated === true && cores > 1 && !forceSafe;
  const attempts = [];
  if (multi) attempts.push({ prefer: 'site', threads: cores, gpu: settings.gpu }, { prefer: 'cdn', threads: cores });
  attempts.push({ prefer: 'site', threads: 1, safe: true }, { prefer: 'cdn', threads: 1, safe: true });

  const got = new Map();
  let hang = null;
  const onProgress = (e) => {
    const p = e.detail;
    if (!need.includes(p.key)) return;
    if (p.stage === 'init') {
      localStorage.setItem('ps_boot', 'pending');
      if (hang && !hang.timer) {
        hang.timer = setTimeout(() => hang.reject(new Error('HANG: the AI engine stopped responding while starting')), 40000);
      }
      $('#bootMeta').textContent = 'Starting the AI engine…';
      return;
    }
    got.set(p.key, p.loaded);
    const loaded = [...got.values()].reduce((a, b) => a + b, 0);
    const f = Math.min(1, loaded / total);
    $('#bootBar').style.width = `${(f * 100).toFixed(1)}%`;
    $('#bootMeta').textContent = `${(loaded / 1e6).toFixed(1)} of ${(total / 1e6).toFixed(1)} MB`;
    setStatus('loading', `Loading AI ${Math.round(f * 100)}%`);
  };
  engine.addEventListener('progress', onProgress);

  const errors = [];
  let threadTrouble = false;
  try {
    for (const [i, a] of attempts.entries()) {
      if (i > 0) $('#bootMeta').textContent = a.safe ? 'Retrying in safe mode (one core)…' : 'Retrying with the backup download…';
      const guard = new Promise((_, reject) => { hang = { reject, timer: 0 }; });
      const work = (async () => {
        await engine.boot(a);
        await Promise.all(need.map((k) => engine.load(k)));
      })();
      work.catch(() => {});
      try {
        await Promise.race([work, guard]);
      } catch (err) {
        clearTimeout(hang.timer);
        engine.terminate();
        errors.push(`${a.prefer}, ${a.threads} thread${a.threads > 1 ? 's' : ''}: ${err.message}`);
        // Runtime or model downloads failing won't be fixed by a different engine setup.
        if (/^(DOWNLOAD|RUNTIME):/.test(err.message)) break;
        if (a.threads > 1) threadTrouble = true;
        continue;
      }
      clearTimeout(hang.timer);
      localStorage.setItem('ps_boot', 'ok');
      if (a.safe && threadTrouble) localStorage.setItem('ps_force_safe', '1');
      state.engineErrors = errors;
      state.ready = true;
      setStatus('ready', engineLabel());
      showBoot(false);
      if (settings.gpu && engine.info.gpuError) toast('GPU isn’t available here, using the CPU');
      if (a.safe) toast('Running in safe mode (one CPU core)', { action: 'Details', onAction: openSettings, ms: 6000 });
      return;
    }
    localStorage.setItem('ps_boot', 'ok');
    state.engineErrors = errors;
    state.bootError = new Error(errors[errors.length - 1] || 'unknown error');
    setStatus('error', 'AI didn’t load');
    showBoot(true);
    $('#bootTitle').textContent = 'Couldn’t start the AI';
    $('#bootText').textContent = navigator.onLine === false
      ? 'You’re offline. The first launch needs internet to download the AI (about 15 MB). After that PlateSight works offline.'
      : 'PlateSight couldn’t load its AI engine. Check your connection and try again. If it keeps happening, send a screenshot of the details below.';
    $('#bootBar').parentElement.hidden = true;
    const meta = $('#bootMeta');
    meta.classList.add('boot__meta--details');
    meta.textContent = `${errors.join('\n')}\nisolated: ${self.crossOriginIsolated === true} · service worker: ${!!(navigator.serviceWorker && navigator.serviceWorker.controller)}`;
    $('#bootRetry').hidden = false;
  } finally {
    engine.removeEventListener('progress', onProgress);
  }
}

function waitReady() {
  if (state.ready) return Promise.resolve();
  if (state.bootError) return Promise.reject(state.bootError);
  return new Promise((resolve, reject) => {
    const iv = setInterval(() => {
      if (state.ready) { clearInterval(iv); resolve(); } else if (state.bootError) { clearInterval(iv); reject(state.bootError); }
    }, 100);
  });
}

async function loadWithProgress(keys, onProgress) {
  const total = keys.reduce((a, k) => a + MODELS[k].bytes, 0);
  const got = new Map();
  const h = (e) => {
    const p = e.detail;
    if (!keys.includes(p.key) || p.stage) return;
    got.set(p.key, p.loaded);
    onProgress(Math.min(1, [...got.values()].reduce((a, b) => a + b, 0) / total));
  };
  engine.addEventListener('progress', h);
  try { await Promise.all(keys.map((k) => engine.load(k))); } finally { engine.removeEventListener('progress', h); }
}

// ============================================================ modes
function setMode(mode) {
  if (!['scan', 'photo', 'video', 'history'].includes(mode)) mode = 'scan';
  if (state.mode === mode) return;
  const prev = state.mode;
  if (prev === 'scan') stopScan();
  if (prev === 'video') pauseVideo();
  state.mode = mode;
  app.dataset.mode = mode;
  $$('.view').forEach((v) => { v.hidden = v.dataset.view !== mode; });
  $$('#tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  if (mode === 'history') renderHistory();
  if (mode === 'scan') maybeAutoStart();
  if (mode === 'video') resumeVideo();
}

// ============================================================ live scan
const scan = {
  stream: null, track: null, running: false, loop: 0, tracker: null, frameW: 0, frameH: 0,
  fps: 0, lastT: 0, ms: null, wake: null, torch: false, zoom: 1, board: new Board(), resume: false,
};

async function maybeAutoStart() {
  if (scan.running || state.booting) return;
  try {
    const p = await navigator.permissions.query({ name: 'camera' });
    if (p.state === 'granted' && state.mode === 'scan' && !document.hidden) startScan();
  } catch (_) { /* permissions API unavailable: wait for a tap */ }
}

async function startCamera() {
  if (scan.stream) return true;
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    camMessage('Camera not available', 'Open PlateSight from its https address (or install it) so Chrome allows the camera.');
    return false;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } },
    });
    if (state.mode !== 'scan') { stream.getTracks().forEach((t) => t.stop()); return false; }
    scan.stream = stream;
    scan.track = stream.getVideoTracks()[0];
    camMessage(CAM_TEXT.title, CAM_TEXT.text, CAM_TEXT.button);
    const cam = $('#cam');
    cam.srcObject = stream;
    await cam.play().catch(() => {});
    setupCameraControls();
    return true;
  } catch (err) {
    const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
    camMessage(
      denied ? 'Camera access is blocked' : 'Couldn’t start the camera',
      denied ? 'Allow the camera for PlateSight in Chrome (tap the icon left of the address, or App info → Permissions), then try again.' : (err.message || String(err)),
    );
    return false;
  }
}
const CAM_TEXT = {
  title: 'Live plate scanning',
  text: 'Point your camera at cars and PlateSight reads the plates as you go. Everything runs on this phone.',
  button: 'Start camera',
};
function camMessage(title, text, button = 'Try again') {
  $('#camEmpty h2').textContent = title;
  $('#camEmptyText').textContent = text;
  $('#btnStartCam').textContent = button;
}
function stopCamera() {
  if (scan.stream) scan.stream.getTracks().forEach((t) => t.stop());
  scan.stream = null;
  scan.track = null;
  $('#cam').srcObject = null;
  $('#btnTorch').hidden = true;
  $('#zoomCtl').hidden = true;
}
function setupCameraControls() {
  const caps = (scan.track && scan.track.getCapabilities && scan.track.getCapabilities()) || {};
  scan.torch = false;
  $('#btnTorch').classList.remove('on');
  $('#btnTorch').hidden = !caps.torch;
  if (caps.focusMode && caps.focusMode.includes('continuous')) {
    scan.track.applyConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(() => {});
  }
  const zc = $('#zoomCtl');
  if (caps.zoom && caps.zoom.max >= 2) {
    const levels = [1, 2, 3].filter((z) => z >= (caps.zoom.min || 1) && z <= caps.zoom.max);
    scan.zoom = 1;
    zc.innerHTML = levels.map((z) => `<button type="button" data-z="${z}" class="${z === 1 ? 'on' : ''}">${z}×</button>`).join('');
    zc.hidden = levels.length < 2;
  } else zc.hidden = true;
}
async function toggleTorch() {
  if (!scan.track) return;
  const want = !scan.torch;
  try { await scan.track.applyConstraints({ advanced: [{ torch: want }] }); scan.torch = want; } catch (_) { scan.torch = false; }
  $('#btnTorch').classList.toggle('on', scan.torch);
}
async function setZoom(z) {
  if (!scan.track) return;
  try { await scan.track.applyConstraints({ advanced: [{ zoom: z }] }); scan.zoom = z; } catch (_) { return; }
  $$('#zoomCtl button').forEach((b) => b.classList.toggle('on', +b.dataset.z === z));
}
async function requestWake() { try { scan.wake = await navigator.wakeLock.request('screen'); } catch (_) { scan.wake = null; } }
function releaseWake() { try { if (scan.wake) scan.wake.release(); } catch (_) { /* ignore */ } scan.wake = null; }

async function startScan() {
  if (scan.running) return;
  const btn = $('#btnScan');
  btn.disabled = true;
  const ok = await startCamera();
  btn.disabled = false;
  if (!ok || state.mode !== 'scan') return;
  scan.running = true;
  scan.tracker = new Tracker({ format: settings.format });
  scan.lastT = 0;
  scan.fps = 0;
  btn.classList.add('running');
  btn.setAttribute('aria-label', 'Stop scanning');
  $('#camEmpty').hidden = true;
  $('#reticle').hidden = false;
  $('#hud').hidden = !settings.stats;
  requestWake();
  const id = ++scan.loop;
  liveLoop(id);
  requestAnimationFrame(drawLive);
}

function stopScan() {
  const wasRunning = scan.running;
  scan.running = false;
  scan.loop++;
  if (wasRunning && scan.tracker) finishTracks(scan.tracker.flush(), 'live');
  releaseWake();
  stopCamera();
  const btn = $('#btnScan');
  btn.classList.remove('running');
  btn.setAttribute('aria-label', 'Start scanning');
  $('#camEmpty').hidden = false;
  $('#reticle').hidden = true;
  $('#hud').hidden = true;
  const c = $('#camOverlay');
  c.getContext('2d').clearRect(0, 0, c.width, c.height);
}

async function liveLoop(id) {
  const cam = $('#cam');
  while (scan.running && id === scan.loop) {
    if (!state.ready || cam.readyState < 2 || !cam.videoWidth) { updateHud(); await sleep(150); continue; }
    const t0 = performance.now();
    let bmp;
    try { bmp = await createImageBitmap(cam); } catch (_) { await sleep(80); continue; }
    let res;
    try {
      res = await engine.analyze(bmp, {
        det: 'det384', ocr: 'ocrFast', conf: SENS[settings.sensitivity], maxPlates: 6, thumbs: true, minW: 40, roi: 'portrait',
      });
    } catch (err) {
      console.warn(err);
      await sleep(300);
      continue;
    }
    if (!scan.running || id !== scan.loop) { res.dets.forEach((d) => d.thumb && d.thumb.close()); break; }
    const now = performance.now();
    scan.frameW = res.w;
    scan.frameH = res.h;
    const { confirmed, lost } = scan.tracker.update(res.dets.map(toDet), now);
    for (const t of confirmed) { haptic(); blip(); t.fresh = true; }
    finishTracks(lost, 'live');
    refreshTray();
    if (scan.lastT) scan.fps = scan.fps ? scan.fps * 0.85 + (1000 / (now - scan.lastT)) * 0.15 : 1000 / (now - scan.lastT);
    scan.lastT = now;
    scan.ms = res.ms;
    updateHud();
    const spent = performance.now() - t0;
    if (spent < 66) await sleep(66 - spent);
  }
}

function finishTracks(tracks, source) {
  for (const t of tracks) {
    if (t.confirmed && t.result) {
      const e = scan.board.put(t);
      savePlate(t.result, source, e ? e.thumbURL : null);
    }
    if (t.thumb && t.thumb.close) t.thumb.close();
    t.thumb = null;
  }
  refreshTray();
}

// Keyed DOM update so existing chips don't re-animate every frame.
function refreshTray() {
  if (scan.tracker) for (const t of scan.tracker.tracks) if (t.confirmed && t.result) scan.board.put(t);
  const tray = $('#scanTray');
  const entries = scan.board.entries().sort((a, b) => b.created - a.created).slice(0, 24);
  const keep = new Set(entries.map((e) => e.key));
  for (const el of [...tray.children]) if (!keep.has(el.dataset.key)) el.remove();
  const byKey = new Map([...tray.children].map((el) => [el.dataset.key, el]));
  let prev = null;
  for (const e of entries) {
    let el = byKey.get(e.key);
    if (!el) {
      el = document.createElement('button');
      el.type = 'button';
      el.className = 'tray-chip fresh';
      el.dataset.key = e.key;
      setTimeout(() => el.classList.remove('fresh'), 1800);
    }
    const sig = `${e.r.text}|${e.r.region}|${Math.round(score(e.r) * 100)}|${e.thumbURL ? e.thumbURL.length : 0}`;
    if (el.dataset.sig !== sig) {
      el.dataset.sig = sig;
      el.innerHTML = `${e.thumbURL ? `<img src="${e.thumbURL}" alt="">` : ''}<span>${plateHTML(e.r, 'sm')}<span class="meta">${flagFor(e.r.region)} ${pct(score(e.r))}</span></span>`;
    }
    const ref = prev ? prev.nextSibling : tray.firstChild;
    if (el !== ref) tray.insertBefore(el, ref);
    prev = el;
  }
}

function updateHud() {
  const hud = $('#hud');
  if (!settings.stats || !scan.running) { hud.hidden = true; return; }
  hud.hidden = false;
  if (!state.ready) { hud.textContent = 'Loading AI…'; return; }
  const ms = scan.ms || { det: 0, ocr: 0 };
  const i = engine.info || {};
  hud.textContent = `${scan.fps.toFixed(1)} fps · det ${ms.det} ms · ocr ${ms.ocr} ms · ${i.ep === 'webgpu' ? 'GPU' : `CPU×${i.threads || 1}`}`;
}

function drawLive() {
  if (!scan.running) return;
  requestAnimationFrame(drawLive);
  const vf = $('#viewfinder');
  const { ctx, w, h } = fitCanvas($('#camOverlay'), vf);
  const cam = $('#cam');
  const fw = scan.frameW || cam.videoWidth;
  const fh = scan.frameH || cam.videoHeight;
  if (!fw || !scan.tracker) return;
  const map = mapper(w, h, fw, fh, 'cover');
  const now = performance.now();
  const topSafe = document.querySelector('.topbar').offsetHeight || 60;
  let visible = 0;
  for (const t of scan.tracker.tracks) {
    const age = now - t.last;
    if (age > 700) continue;
    const target = t.predict(now);
    t.shown = t.shown.map((v, k) => v + (target[k] - v) * 0.35);
    const r = t.result;
    const color = t.confirmed ? '#6ee7b7' : r ? '#60a5fa' : 'rgba(255,255,255,0.9)';
    const label = r ? `${flagFor(r.region) ? `${flagFor(r.region)} ` : ''}${r.text}` : 'Reading…';
    drawPlateBox(ctx, map(t.shown), { color, label, solid: t.confirmed, alpha: age < 350 ? 1 : 1 - (age - 350) / 350, minTop: topSafe + 8, maxW: w });
    visible++;
  }
  const ret = $('#reticle');
  if (ret.hidden !== visible > 0) ret.hidden = visible > 0;
}

// ============================================================ photo
const photo = { url: null, result: null, busy: false };

async function handlePhoto(file) {
  if (!file || photo.busy) return;
  photo.busy = true;
  setMode('photo');
  $('#photoEmpty').hidden = true;
  $('#photoResult').hidden = false;
  if (photo.url) URL.revokeObjectURL(photo.url);
  photo.url = URL.createObjectURL(file);
  photo.result = null;
  const img = $('#photoImg');
  img.src = photo.url;
  $('#photoStage').classList.add('scanning');
  $('#photoHead').innerHTML = '<h3>Reading plates…</h3><span id="photoSub"></span>';
  $('#photoResults').innerHTML = '';
  drawPhotoOverlay();
  try {
    await img.decode().catch(() => {});
    await waitReady();
    const needs = ['det640', 'ocrAcc'].filter((k) => !engine.has(k));
    if (needs.length) {
      const sub = $('#photoSub');
      if (sub) sub.textContent = 'Getting precision models…';
      await loadWithProgress(needs, (f) => { const s = $('#photoSub'); if (s) s.textContent = `Downloading precision models ${Math.round(f * 100)}%`; });
    }
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const res = await engine.analyze(bmp, {
      det: 'det640', ocr: 'ocrAcc', conf: Math.min(0.3, SENS[settings.sensitivity]), maxPlates: 12,
      tta: true, deep: true, thumbs: true, minW: 14,
    });
    const found = [];
    for (const d of res.dets) {
      const r = d.reads ? vote(d.reads, settings.format, 0.2) : null;
      if (r && r.key.length >= 3 && r.prob >= 0.35) found.push({ ...r, box: d.box, thumbURL: d.thumb ? bitmapToDataURL(d.thumb) : null });
      if (d.thumb) d.thumb.close();
    }
    found.sort((a, b) => (a.box[0] - b.box[0]) || (a.box[1] - b.box[1]));
    photo.result = { w: res.w, h: res.h, plates: found, deep: res.deep, ms: res.ms };
    renderPhotoResult();
    if (found.length) haptic(24);
    for (const p of found) savePlate(p, 'photo', p.thumbURL);
  } catch (err) {
    $('#photoHead').innerHTML = '<h3>Couldn’t read this photo</h3>';
    $('#photoResults').innerHTML = `<div class="none"><b>Something went wrong</b>${esc(err.message || err)}</div>`;
  } finally {
    $('#photoStage').classList.remove('scanning');
    photo.busy = false;
  }
}

function drawPhotoOverlay() {
  const stage = $('#photoStage');
  const { ctx, w, h } = fitCanvas($('#photoOverlay'), stage);
  const r = photo.result;
  if (!r || !r.plates.length) return;
  const map = mapper(w, h, r.w, r.h, 'contain');
  r.plates.forEach((p, i) => drawPlateBox(ctx, map(p.box), { color: p.valid ? '#6ee7b7' : '#fbbf24', badge: i + 1 }));
}

function resultCard(p, n, extra = '', animate = true) {
  const s = score(p);
  const desc = describe(p);
  const fmt = formatName(p);
  return `
  <article class="card" style="${animate ? `animation-delay:${Math.min(n || 0, 8) * 55}ms` : 'animation:none'}">
    ${p.thumbURL ? `<img class="card__thumb" src="${p.thumbURL}" alt="">` : ''}
    <div class="card__top">
      ${n != null ? `<span class="badge-n">${n}</span>` : ''}
      ${plateHTML(p)}
      <span class="ring${p.valid ? '' : ' ring--warn'}" style="--p:${Math.round(s * 100)}"><span>${pct(s)}</span></span>
    </div>
    <div class="tags">
      <span class="tag">${esc(countryLabel(p.region))}</span>
      ${desc ? `<span class="tag">${esc(desc)}</span>` : ''}
      ${p.valid ? `<span class="tag tag--ok">${fmt ? `Valid ${fmt} plate` : 'Looks valid'}</span>` : '<span class="tag tag--warn">Unverified format</span>'}
      ${extra}
    </div>
    <div class="card__actions">
      <button class="chip-btn" type="button" data-act="copy" data-key="${esc(p.key)}">${icon('copy')}Copy</button>
      <button class="chip-btn" type="button" data-act="share" data-key="${esc(p.key)}">${icon('share')}Share</button>
      <button class="chip-btn" type="button" data-act="detail" data-key="${esc(p.key)}">${icon('chevron')}Details</button>
    </div>
  </article>`;
}

function renderPhotoResult() {
  const r = photo.result;
  const n = r.plates.length;
  $('#photoHead').innerHTML = n
    ? `<h3>${n} plate${n === 1 ? '' : 's'} found</h3><span>${r.deep ? 'deep scan · ' : ''}${(r.ms.total / 1000).toFixed(1)} s</span>`
    : '<h3>No plates found</h3>';
  $('#photoResults').innerHTML = n
    ? r.plates.map((p, i) => resultCard(p, i + 1)).join('')
    : '<div class="none"><b>Nothing readable here</b>Try a closer, sharper shot with the plate facing the camera. Glare and steep angles make plates hard to read.</div>';
  drawPhotoOverlay();
}

// ============================================================ video
const vjob = { seq: 0, active: false, done: false, url: null, tracker: null, board: new Board(), ema: 0, frameW: 0, frameH: 0, busy: false, paused: false };

function once(el, ev, errEv = 'error') {
  return new Promise((resolve, reject) => {
    const ok = () => { el.removeEventListener(errEv, bad); resolve(); };
    const bad = () => { el.removeEventListener(ev, ok); reject(new Error('This video format can’t be played here')); };
    el.addEventListener(ev, ok, { once: true });
    el.addEventListener(errEv, bad, { once: true });
  });
}

async function handleVideo(file) {
  if (!file) return;
  const id = ++vjob.seq;
  setMode('video');
  const v = $('#vid');
  v.pause();
  $('#videoEmpty').hidden = true;
  $('#videoWork').hidden = false;
  $('#videoAgainRow').hidden = true;
  $('#btnVidStop').hidden = false;
  $('#videoResults').innerHTML = '';
  $('#videoHead').innerHTML = '';
  $('#vidBar').style.width = '0%';
  $('#vidStatus').textContent = 'Opening video…';
  fitCanvas($('#vidOverlay'), $('#videoStage'));
  if (vjob.url) URL.revokeObjectURL(vjob.url);
  vjob.url = URL.createObjectURL(file);
  vjob.active = true;
  vjob.done = false;
  vjob.paused = false;
  vjob.busy = false;
  vjob.ema = 0;
  vjob.shown = new Set();
  vjob.board = new Board();
  vjob.tracker = new Tracker({ format: settings.format, maxAge: 1500, minReads: 2 });
  v.controls = false;
  v.muted = true;
  v.playsInline = true;
  v.src = vjob.url;
  try {
    await once(v, 'loadedmetadata');
    await waitReady();
    if (id !== vjob.seq) return;
    v.playbackRate = 1;
    v.onended = () => finishVideo(id);
    v.ontimeupdate = () => videoProgress();
    await v.play();
  } catch (err) {
    vjob.active = false;
    $('#vidStatus').textContent = err.message || 'Couldn’t open this video';
    $('#videoAgainRow').hidden = false;
    $('#btnVidStop').hidden = true;
    return;
  }
  const step = async (_now, meta) => {
    if (id !== vjob.seq || !vjob.active) return;
    if (!vjob.busy && !v.paused) {
      vjob.busy = true;
      const mt = ((meta && meta.mediaTime) ?? v.currentTime) * 1000;
      try {
        const bmp = await createImageBitmap(v);
        const res = await engine.analyze(bmp, {
          det: 'det384', ocr: 'ocrFast', conf: SENS[settings.sensitivity], maxPlates: 8, thumbs: true, minW: 32,
        });
        if (id === vjob.seq && vjob.active) {
          vjob.frameW = res.w;
          vjob.frameH = res.h;
          const { confirmed, lost } = vjob.tracker.update(res.dets.map(toDet), mt);
          if (confirmed.length) { haptic(12); confirmed.forEach((t) => vjob.board.put(t)); renderVideoResults(false); }
          collectVideo(lost, true);
          adaptRate(res.ms.total);
          drawVideoOverlay(mt);
        } else res.dets.forEach((d) => d.thumb && d.thumb.close());
      } catch (err) { console.warn(err); }
      vjob.busy = false;
    }
    if (id === vjob.seq && vjob.active && !v.ended) nextFrame(v, step);
  };
  nextFrame(v, step);
}

function nextFrame(v, cb) {
  if ('requestVideoFrameCallback' in HTMLVideoElement.prototype) v.requestVideoFrameCallback(cb);
  else requestAnimationFrame((t) => cb(t, null));
}

function collectVideo(tracks, closeThumbs) {
  let changed = false;
  for (const t of tracks) {
    const r = t.result;
    if (r && (t.confirmed || (r.valid && r.prob >= 0.9))) { vjob.board.put(t); changed = true; }
    if (closeThumbs && t.thumb && t.thumb.close) { t.thumb.close(); t.thumb = null; }
  }
  if (changed) renderVideoResults(false);
}

function adaptRate(ms) {
  const v = $('#vid');
  vjob.ema = vjob.ema ? vjob.ema * 0.8 + ms * 0.2 : ms;
  // Aim for at least ~5 analysed frames per second of video; slow playback down on slower phones.
  const rate = Math.max(0.25, Math.min(1, 1000 / vjob.ema / 5));
  if (Math.abs(rate - v.playbackRate) > 0.08) v.playbackRate = Math.round(rate * 20) / 20;
}

function videoProgress() {
  const v = $('#vid');
  if (!v.duration || !isFinite(v.duration)) return;
  $('#vidBar').style.width = `${Math.min(100, (v.currentTime / v.duration) * 100).toFixed(1)}%`;
  if (vjob.active) {
    const n = vjob.board.entries().length;
    $('#vidStatus').textContent = `Scanning ${mmss(v.currentTime)} / ${mmss(v.duration)} · ${n} plate${n === 1 ? '' : 's'}${v.playbackRate < 0.99 ? ` · ${v.playbackRate.toFixed(2)}×` : ''}`;
  }
}

function drawVideoOverlay(mt) {
  const stage = $('#videoStage');
  const { ctx, w, h } = fitCanvas($('#vidOverlay'), stage);
  if (!vjob.tracker || !vjob.frameW) return;
  const map = mapper(w, h, vjob.frameW, vjob.frameH, 'contain');
  for (const t of vjob.tracker.tracks) {
    if (mt - t.last > 300) continue;
    const r = t.result;
    drawPlateBox(ctx, map(t.box), {
      color: t.confirmed ? '#6ee7b7' : r ? '#60a5fa' : 'rgba(255,255,255,0.9)',
      label: r ? r.text : null,
      solid: t.confirmed,
      maxW: w,
    });
  }
}

function finishVideo(id) {
  if (id !== vjob.seq || vjob.done) return;
  vjob.active = false;
  vjob.done = true;
  const v = $('#vid');
  collectVideo(vjob.tracker.flush(), true);
  const entries = vjob.board.entries();
  for (const e of entries) savePlate(e.r, 'video', e.thumbURL);
  fitCanvas($('#vidOverlay'), $('#videoStage'));
  v.playbackRate = 1;
  v.controls = true;
  v.currentTime = 0;
  $('#vidBar').style.width = '100%';
  $('#btnVidStop').hidden = true;
  $('#videoAgainRow').hidden = false;
  const n = entries.length;
  $('#vidStatus').textContent = `Done · ${n} plate${n === 1 ? '' : 's'} in ${mmss(v.duration)}`;
  renderVideoResults(true);
  if (n) haptic(24);
}

function stopVideoScan() {
  const v = $('#vid');
  if (!vjob.active) return;
  v.pause();
  finishVideo(vjob.seq);
}
function pauseVideo() { const v = $('#vid'); if (vjob.active && !v.paused) { v.pause(); vjob.paused = true; } }
function resumeVideo() { const v = $('#vid'); if (vjob.active && vjob.paused) { vjob.paused = false; v.play().catch(() => {}); } }

function renderVideoResults(final) {
  const entries = vjob.board.entries().sort((a, b) => a.first - b.first);
  const n = entries.length;
  const shown = vjob.shown || (vjob.shown = new Set());
  $('#videoHead').innerHTML = n ? `<h3>${final ? `${n} plate${n === 1 ? '' : 's'}` : 'Found so far'}</h3><span>${final ? 'tap “at 0:00” to jump there' : ''}</span>` : '';
  $('#videoResults').innerHTML = n
    ? entries.map((e) => {
      const extra = `<span class="tag" data-seek="${e.first / 1000}">▶ at ${mmss(e.first / 1000)}</span>${e.brief ? '<span class="tag tag--warn">Seen briefly</span>' : ''}`;
      const html = resultCard({ ...e.r, thumbURL: e.thumbURL }, null, extra, !shown.has(e.key));
      shown.add(e.key);
      return html;
    }).join('')
    : (final ? '<div class="none"><b>No plates found</b>Plates need to be reasonably large and sharp in the frame. Try a steadier or closer clip.</div>' : '');
}

// ============================================================ history
async function renderHistory() {
  const q = clean($('#histSearch').value);
  let all = [];
  try { all = await plates.all(); } catch (_) { all = []; }
  const list = q ? all.filter((e) => e.key.includes(q) || clean(e.text).includes(q)) : all;
  $('#histCount').textContent = all.length ? String(all.length) : '';
  const box = $('#histList');
  if (!all.length) {
    box.innerHTML = `<div class="hist-empty"><div class="hero-icon">${icon('history')}</div><b>No plates yet</b><span>Plates you scan are kept here, on this phone only.</span></div>`;
    return;
  }
  if (!list.length) { box.innerHTML = `<div class="hist-empty"><b>No matches</b><span>Nothing matches “${esc($('#histSearch').value)}”.</span></div>`; return; }
  let html = '';
  let day = '';
  for (const e of list) {
    const d = dayLabel(e.last);
    if (d !== day) { day = d; html += `<div class="hist-day">${esc(d)}</div>`; }
    html += `
      <button type="button" class="hist-item" data-key="${esc(e.key)}">
        ${e.thumb ? `<img src="${e.thumb}" alt="">` : `<span class="noimg">${icon('scan')}</span>`}
        <span>${plateHTML(e, 'sm')}<span class="meta">${flagFor(e.region)} ${esc(e.region || 'Unknown')} · ${esc(e.source)} · ${e.count > 1 ? `${e.count}× · ` : ''}${clock(e.last)}</span></span>
        ${icon('chevron')}
      </button>`;
  }
  box.innerHTML = html;
}

async function exportCsv() {
  const all = await plates.all().catch(() => []);
  if (!all.length) { toast('Nothing to export yet'); return; }
  const q = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const rows = [['plate', 'country', 'valid_format', 'confidence', 'times_seen', 'first_seen', 'last_seen', 'source', 'registered', 'county_or_area']];
  for (const e of all) {
    const i = e.info || {};
    rows.push([e.text, e.region || '', e.valid, (e.conf || 0).toFixed(2), e.count, new Date(e.first).toISOString(), new Date(e.last).toISOString(), e.source, registered(e), i.county || i.area || '']);
  }
  const csv = rows.map((r) => r.map(q).join(',')).join('\n');
  const name = `platesight-${new Date().toISOString().slice(0, 10)}.csv`;
  const file = new File([csv], name, { type: 'text/csv' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title: 'PlateSight history' }); return; } catch (err) { if (err && err.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  toast('CSV saved to Downloads');
}

// ============================================================ sheets
function showSheet(id) {
  openSheet(id);
  if (!(window.history.state && window.history.state.sheet)) window.history.pushState({ sheet: 1 }, '');
}
function hideSheet() {
  if (window.history.state && window.history.state.sheet) window.history.back();
  else closeSheet();
}
window.addEventListener('popstate', () => closeSheet());

let detailItem = null;
function openDetail(p, { fromHistory = false } = {}) {
  detailItem = { p, fromHistory };
  const thumb = p.thumbURL || p.thumb || null;
  const reg = registered(p);
  const i = p.info || {};
  const cells = [
    ['Country', countryLabel(p.region)],
    ['Format', p.valid ? (formatName(p) ? `Valid ${formatName(p)}` : 'Looks valid') : 'Unverified'],
  ];
  if (reg) cells.push(['Registered', reg]);
  if (i.county) cells.push(['County', i.countyGa && i.countyGa !== i.county ? `${i.county} · ${i.countyGa}` : i.county]);
  if (i.area) cells.push(['Area', i.area]);
  cells.push(['Confidence', pct(fromHistory ? p.conf : score(p))]);
  if (fromHistory) {
    cells.push(['Seen', `${p.count}× · ${p.source}`]);
    cells.push(['Last seen', timeAgo(p.last)]);
  }
  $('#detailBody').innerHTML = `
    <div class="detail-hero">${thumb ? `<img src="${thumb}" alt="">` : ''}${plateHTML(p, 'lg')}</div>
    <div class="info-grid">${cells.map(([k, v]) => `<div class="info"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}</div>
    <div class="detail-actions">
      <button class="btn btn--ghost" type="button" data-act="copy">${icon('copy')}Copy</button>
      <button class="btn btn--ghost" type="button" data-act="share">${icon('share')}Share</button>
      ${fromHistory ? `<button class="btn btn--danger" type="button" data-act="delete">${icon('trash')}Delete</button>` : `<button class="btn btn--ghost" type="button" data-act="close">${icon('check')}Done</button>`}
    </div>`;
  showSheet('sheetDetail');
}

function openSettings() { renderSettings(); showSheet('sheetSettings'); }

function renderSettings() {
  const i = engine.info || {};
  const seg = (key, opts) => `<div class="seg" data-setting="${key}">${opts.map(([v, l]) => `<button type="button" data-v="${v}" class="${settings[key] === v ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  const sw = (key, title, sub) => `<div class="row"><div class="row__text"><b>${title}</b>${sub ? `<small>${sub}</small>` : ''}</div><button type="button" class="switch" role="switch" data-toggle="${key}" aria-checked="${!!settings[key]}" aria-label="${title}"></button></div>`;
  const engineText = state.ready
    ? `ONNX Runtime ${i.version || ORT_VERSION} · ${i.ep === 'webgpu' ? 'GPU (WebGPU)' : `CPU · ${i.threads || 1} thread${i.threads > 1 ? 's' : ''}`}${i.safe ? ' · safe mode' : ''} · from ${i.source === 'cdn' ? 'CDN' : 'this site'}`
    : (state.bootError ? `Not loaded: ${state.bootError.message}` : 'Loading…');
  const safe = localStorage.getItem('ps_force_safe') === '1';
  $('#settingsBody').innerHTML = `
    <div class="group"><h4>Reading</h4><div class="group__box">
      <div class="row row--stack"><div class="row__text"><b>Plate format</b><small>Auto checks the format of the country the AI recognises on each plate.</small></div>
        ${seg('format', [['auto', 'Auto'], ['IE', 'Ireland'], ['UK', 'UK'], ['ANY', 'Any']])}</div>
      <div class="row row--stack"><div class="row__text"><b>Detection sensitivity</b><small>Higher finds smaller or partly hidden plates but makes more mistakes.</small></div>
        ${seg('sensitivity', [['low', 'Low'], ['medium', 'Medium'], ['high', 'High']])}</div>
    </div></div>
    <div class="group"><h4>Feedback</h4><div class="group__box">
      ${sw('haptics', 'Vibrate on new plate')}
      ${sw('sound', 'Sound on new plate')}
      ${sw('stats', 'Show speed stats', 'Frames per second and model timings while scanning.')}
    </div></div>
    <div class="group"><h4>Performance</h4><div class="group__box">
      <div class="row"><div class="row__text"><b>AI engine</b><small>${esc(engineText)}</small></div></div>
      ${sw('gpu', 'GPU acceleration (beta)', 'Uses WebGPU when the phone supports it. Restarts the app.')}
      ${safe ? '<div class="row"><div class="row__text"><b>Safe mode is on</b><small>Turned on after the fast engine failed to start. Try the fast engine again?</small></div><button type="button" class="chip-btn" data-action="unsafe">Reset</button></div>' : ''}
    </div></div>
    <div class="group"><h4>Data</h4><div class="group__box">
      ${sw('history', 'Save plates to history', 'Kept only on this phone.')}
      <div class="row"><div class="row__text"><b>Downloaded AI models</b><small>About 35 MB when everything is downloaded. Remove to free space.</small></div><button type="button" class="chip-btn" data-action="purge">Remove</button></div>
    </div></div>
    ${state.installEvt ? `<button type="button" class="btn btn--primary btn--lg" data-action="install">${icon('install')}Install PlateSight</button>` : ''}
    <p class="about"><b>Private by design.</b> PlateSight reads plates entirely on this phone. Camera frames, photos and videos are never uploaded. Number plates are personal data, so only scan where you have a good reason to, and don’t share or keep plates of people you don’t know.<br><br>Models: open-image-models (YOLOv9 plate detector) and fast-plate-ocr, both MIT licensed. Runtime: ONNX Runtime Web.</p>`;
}

function onSettingsClick(e) {
  const b = e.target.closest('button');
  if (!b) return;
  const segEl = b.closest('.seg');
  if (segEl && b.dataset.v) {
    const key = segEl.dataset.setting;
    settings[key] = b.dataset.v;
    saveSettings(settings);
    if (key === 'format') {
      if (scan.tracker) scan.tracker.setFormat(settings.format);
      if (vjob.tracker) vjob.tracker.setFormat(settings.format);
    }
    renderSettings();
    return;
  }
  if (b.dataset.toggle) {
    const key = b.dataset.toggle;
    settings[key] = !settings[key];
    saveSettings(settings);
    b.setAttribute('aria-checked', String(!!settings[key]));
    if (key === 'stats') updateHud();
    if (key === 'gpu') toast('Restart PlateSight to apply', { action: 'Restart', onAction: () => location.reload(), ms: 8000 });
    return;
  }
  if (b.dataset.action === 'install' && state.installEvt) { state.installEvt.prompt(); return; }
  if (b.dataset.action === 'unsafe') {
    localStorage.removeItem('ps_force_safe');
    toast('Fast engine will be used next time', { action: 'Restart', onAction: () => location.reload(), ms: 8000 });
    renderSettings();
    return;
  }
  if (b.dataset.action === 'purge') {
    toast('Remove downloaded AI models?', {
      action: 'Remove',
      ms: 6000,
      onAction: async () => {
        for (const k of await caches.keys()) if (k.startsWith('ps-models') || k.startsWith('ps-ort')) await caches.delete(k);
        toast('Models removed. They’ll download again on next launch.');
      },
    });
  }
}

// ============================================================ shared files & install
async function openShared() {
  try {
    const c = await caches.open('ps-share');
    const keys = await c.keys();
    if (!keys.length) return;
    const res = await c.match(keys[0]);
    const blob = await res.blob();
    const name = decodeURIComponent(res.headers.get('X-File-Name') || 'shared');
    await c.delete(keys[0]);
    const file = new File([blob], name, { type: blob.type });
    if (file.type.startsWith('video/')) handleVideo(file);
    else handlePhoto(file);
  } catch (_) { /* nothing shared */ }
}

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  state.installEvt = e;
  if (!localStorage.getItem('ps_install_nudged')) {
    localStorage.setItem('ps_install_nudged', '1');
    setTimeout(() => toast('Add PlateSight to your home screen', { action: 'Install', onAction: () => e.prompt(), ms: 9000 }), 5000);
  }
});
window.addEventListener('appinstalled', () => { state.installEvt = null; toast('Installed. PlateSight is on your home screen.'); });

// ============================================================ wiring
function paintIcons() {
  $$('[data-icon]').forEach((el) => { el.innerHTML = icon(el.dataset.icon); });
  $('#btnSettings').innerHTML = icon('settings');
  $('#btnTorch').innerHTML = icon('bolt');
  $('#camHeroIcon').innerHTML = icon('scan');
  $('#btnPhotoCam').innerHTML = `${icon('camera')}Take photo`;
  $('#btnPhotoPick').innerHTML = `${icon('gallery')}Choose from gallery`;
  $('#btnPhotoAgain').innerHTML = `${icon('camera')}New photo`;
  $('#btnPhotoAgainPick').innerHTML = `${icon('gallery')}Gallery`;
  $('#btnVideoCam').innerHTML = `${icon('record')}Record a video`;
  $('#btnVideoPick').innerHTML = `${icon('gallery')}Choose a video`;
  $('#btnVideoAgain').innerHTML = `${icon('video')}Scan another video`;
  $('#btnVidStop').innerHTML = `${icon('stop')}Stop`;
  $('#btnExport').innerHTML = icon('download');
  $('#btnClearHist').innerHTML = icon('trash');
  $('#searchIcon').innerHTML = icon('search');
  $$('[data-close]').forEach((b) => { b.innerHTML = icon('close'); });
}

function pick(inputId) { const el = $(inputId); el.value = ''; el.click(); }

function findCardItem(key) {
  if (state.mode === 'photo' && photo.result) return photo.result.plates.find((p) => p.key === key);
  if (state.mode === 'video') { const e = vjob.board.get(key); return e && { ...e.r, thumbURL: e.thumbURL }; }
  return null;
}

function wire() {
  $('#tabbar').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setMode(b.dataset.mode); });
  $('#btnSettings').onclick = openSettings;
  $('#status').onclick = openSettings;
  $('#backdrop').onclick = hideSheet;
  $$('[data-close]').forEach((b) => { b.onclick = hideSheet; });
  $('#bootRetry').onclick = () => location.reload();

  // scan
  $('#btnStartCam').onclick = startScan;
  $('#btnScan').onclick = () => (scan.running ? stopScan() : startScan());
  $('#btnTorch').onclick = toggleTorch;
  $('#zoomCtl').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setZoom(+b.dataset.z); });
  $('#scanTray').addEventListener('click', (e) => {
    const b = e.target.closest('.tray-chip');
    const it = b && scan.board.get(b.dataset.key);
    if (it) openDetail({ ...it.r, thumbURL: it.thumbURL });
  });

  // photo
  $('#btnPhotoCam').onclick = () => pick('#filePhotoCam');
  $('#btnPhotoPick').onclick = () => pick('#filePhoto');
  $('#btnPhotoAgain').onclick = () => pick('#filePhotoCam');
  $('#btnPhotoAgainPick').onclick = () => pick('#filePhoto');
  $('#filePhotoCam').onchange = (e) => handlePhoto(e.target.files[0]);
  $('#filePhoto').onchange = (e) => handlePhoto(e.target.files[0]);

  // video
  $('#btnVideoCam').onclick = () => pick('#fileVideoCam');
  $('#btnVideoPick').onclick = () => pick('#fileVideo');
  $('#btnVideoAgain').onclick = () => pick('#fileVideo');
  $('#btnVidStop').onclick = stopVideoScan;
  $('#fileVideoCam').onchange = (e) => handleVideo(e.target.files[0]);
  $('#fileVideo').onchange = (e) => handleVideo(e.target.files[0]);

  // result cards (photo + video)
  const onCards = (e) => {
    const seek = e.target.closest('[data-seek]');
    if (seek && vjob.done) {
      const v = $('#vid');
      v.currentTime = Math.max(0, +seek.dataset.seek - 0.3);
      v.play().catch(() => {});
      $('#videoStage').scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const p = findCardItem(b.dataset.key);
    if (!p) return;
    if (b.dataset.act === 'copy') copyText(p.text);
    else if (b.dataset.act === 'share') sharePlate(p);
    else if (b.dataset.act === 'detail') openDetail(p);
  };
  $('#photoResults').addEventListener('click', onCards);
  $('#videoResults').addEventListener('click', onCards);

  // history
  $('#histSearch').addEventListener('input', () => renderHistory());
  $('#histList').addEventListener('click', async (e) => {
    const b = e.target.closest('.hist-item');
    if (!b) return;
    const all = await plates.all();
    const it = all.find((x) => x.key === b.dataset.key);
    if (it) openDetail(it, { fromHistory: true });
  });
  $('#btnExport').onclick = exportCsv;
  $('#btnClearHist').onclick = () => toast('Delete all saved plates?', {
    action: 'Delete all',
    ms: 6000,
    onAction: async () => { await plates.clear(); scan.board.clear(); refreshTray(); renderHistory(); toast('History cleared'); },
  });

  // sheets
  $('#detailBody').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-act]');
    if (!b || !detailItem) return;
    const { p } = detailItem;
    if (b.dataset.act === 'copy') copyText(p.text);
    else if (b.dataset.act === 'share') sharePlate(p);
    else if (b.dataset.act === 'close') hideSheet();
    else if (b.dataset.act === 'delete') {
      await plates.remove(p.key);
      hideSheet();
      renderHistory();
      toast(`Deleted ${p.text}`);
    }
  });
  $('#settingsBody').addEventListener('click', onSettingsClick);

  // lifecycle
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      if (scan.running) { scan.resume = true; stopScan(); }
      pauseVideo();
    } else {
      if (scan.resume && state.mode === 'scan') { scan.resume = false; startScan(); }
      if (state.mode === 'video') resumeVideo();
    }
  });
  const ro = new ResizeObserver(() => { if (photo.result) drawPhotoOverlay(); });
  ro.observe($('#photoStage'));
}

async function main() {
  paintIcons();
  wire();
  const params = new URLSearchParams(location.search);
  state.booting = true;
  setMode(params.get('mode') || 'scan');
  const sw = await setupServiceWorker(() => {
    // A new version took over. If the AI isn't running (e.g. stuck on an error), switch to it straight away.
    if (!state.ready) location.reload();
    else toast('PlateSight was updated', { action: 'Reload', onAction: () => location.reload(), ms: 12000 });
  });
  if (sw.reloading) return;
  state.booting = false;
  engine.addEventListener('crash', () => {
    if (!state.ready) return; // failures while starting are handled by startEngine's retries
    state.ready = false;
    setStatus('error', 'AI engine stopped');
    toast('The AI engine stopped', { action: 'Restart', onAction: () => location.reload(), ms: 15000 });
  });
  if (params.has('mode') || params.has('shared')) window.history.replaceState(null, '', location.pathname);
  if (params.get('shared')) openShared();
  if (state.mode === 'scan') maybeAutoStart();
  await startEngine();
}

main();

// Test hook (used by the automated browser tests only; harmless in normal use).
window.__plateSight = { engine, state, scan, photo, vjob, settings };
