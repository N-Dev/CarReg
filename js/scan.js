// Live scanning: camera -> AI engine -> tracker -> tray of confirmed plates.
// Quality adapts to the phone (sharper models when there's headroom, faster ones when it's busy or
// hot), and while no plate is in view it analyses fewer frames to save battery and heat.
import { Tracker, iou } from './tracker.js';
import { SENS } from './store.js';
import { TIERS, Adaptive, Idle } from './adaptive.js';
import { flagFor } from './formats.js';
import { $, $$, settings, engine, state, on, emit, log, haptic, blip, score, savePlate } from './ctx.js';
import { Board, closeDet } from './board.js';
import { fitCanvas, mapper, drawPlateBox, drawRegion, drawRaw, drawSpark, sizeOf } from './overlay.js';
import { plateHTML, pct, sleep } from './ui.js';
import { openDetail } from './detail.js';

const FRAME_MS = 66;      // at most ~15 analysed frames per second
const PERF_KEEP = 150;    // frames kept for the speed graph and diagnostics

export const scan = {
  stream: null, track: null, running: false, loop: 0, tracker: null, frameW: 0, frameH: 0,
  fps: 0, lastT: 0, ms: null, wake: null, torch: false, zoom: 1, board: new Board({ max: 120 }), resume: false,
  adaptive: new Adaptive({ mode: settings.quality }), idle: new Idle(), isIdle: false,
  pressure: null, simPressure: null, perf: [], frames: 0, last: null, fetching: false, wantFailedAt: -1e9,
};

/** Detection threshold: the sensitivity setting, unless overridden in debug mode. */
export const detConf = () => settings.dbgConf || SENS[settings.sensitivity];
const pressureNow = () => scan.simPressure || scan.pressure;
const tierReady = (name) => { const t = TIERS[name]; return [t.det, t.read, t.readNew].every((k) => engine.has(k)); };
let el = null;

const toDet = (d) => ({
  box: d.box, score: d.score, thumb: d.thumb || null, crop: d.crop || null, ocrInput: d.ocrInput || null,
  // Cut off by the frame edge = only part of the plate. The accurate reader's votes count a bit more.
  read: d.reads ? { ...d.reads[0], partial: !!d.edge, model: d.model, weight: d.model === 'ocrAcc' ? 1.25 : 1 } : null,
});

// ---------------------------------------------------------------- camera
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
    el.cam.srcObject = stream;
    await el.cam.play().catch(() => {});
    setupCameraControls();
    return true;
  } catch (err) {
    const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
    log('error', `Camera: ${err.name || ''} ${err.message || err}`);
    camMessage(
      denied ? 'Camera access is blocked' : 'Couldn’t start the camera',
      denied ? 'Allow the camera for PlateSight in Chrome (tap the icon left of the address, or App info → Permissions), then try again.' : (err.message || String(err)),
    );
    return false;
  }
}

function stopCamera() {
  if (scan.stream) scan.stream.getTracks().forEach((t) => t.stop());
  scan.stream = null;
  scan.track = null;
  el.cam.srcObject = null;
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

/** Camera details for the debug log. */
export function cameraInfo() {
  try {
    const s = scan.track ? scan.track.getSettings() : null;
    return s ? { width: s.width, height: s.height, fps: s.frameRate, facing: s.facingMode, zoom: s.zoom, label: scan.track.label } : null;
  } catch (_) { return null; }
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

// CPU pressure (Chrome's Compute Pressure API): the phone telling us it's working hard or getting hot.
function watchPressure() {
  if (scan.pressureObs !== undefined) return;
  scan.pressureObs = null;
  if (!('PressureObserver' in window)) return;
  try {
    const obs = new PressureObserver((records) => {
      const s = records[records.length - 1].state;
      if (s !== scan.pressure) { log('perf', `CPU pressure: ${s}`); scan.pressure = s; }
    });
    obs.observe('cpu', { sampleInterval: 2000 }).catch((err) => log('perf', `CPU pressure unavailable (${err.message})`));
    scan.pressureObs = obs;
  } catch (err) { log('perf', `CPU pressure unavailable (${err.message})`); }
}

// ---------------------------------------------------------------- scanning
export async function maybeAutoStart() {
  if (scan.running || state.swSetup) return;
  try {
    const p = await navigator.permissions.query({ name: 'camera' });
    if (p.state === 'granted' && state.mode === 'scan' && !document.hidden) startScan();
  } catch (_) { /* permissions API unavailable: wait for a tap */ }
}

export async function startScan() {
  if (scan.running) return;
  const btn = $('#btnScan');
  btn.disabled = true;
  const ok = await startCamera();
  btn.disabled = false;
  if (!ok || state.mode !== 'scan' || scan.running) return;
  scan.running = true;
  scan.tracker = new Tracker({ format: settings.format, minScore: settings.minScore });
  scan.lastT = 0;
  scan.fps = 0;
  scan.isIdle = false;
  const t = performance.now();
  scan.idle.wake(t);
  scan.adaptive.reset(t);
  btn.classList.add('running');
  btn.setAttribute('aria-label', 'Stop scanning');
  $('#camEmpty').hidden = true;
  $('#reticle').hidden = false;
  requestWake();
  watchPressure();
  if (settings.quality !== 'auto') wantTier(settings.quality, true);
  log('scan', 'Live scan started', { quality: settings.quality, tier: scan.adaptive.tier, camera: cameraInfo() });
  updateHud();
  const id = ++scan.loop;
  liveLoop(id);
  requestAnimationFrame(drawLive);
}

export function stopScan() {
  const wasRunning = scan.running;
  scan.running = false;
  scan.loop++;
  if (wasRunning && scan.tracker) {
    finishTracks(scan.tracker.flush());
    refreshTray();
    log('scan', 'Live scan stopped', { frames: scan.frames });
  }
  releaseWake();
  stopCamera();
  const btn = $('#btnScan');
  btn.classList.remove('running');
  btn.setAttribute('aria-label', 'Start scanning');
  $('#camEmpty').hidden = false;
  $('#reticle').hidden = true;
  $('#hud').hidden = true;
  const c = el.overlay;
  c.getContext('2d').clearRect(0, 0, c.width, c.height);
}

/** Which models to use for the next frame. */
function framePlan() {
  const tier = TIERS[scan.adaptive.tier];
  const tracks = scan.tracker.tracks;
  // Plates still being worked out get the tier's (more accurate) reader for new plates;
  // once every plate in view is settled, the fast reader is enough to keep tracking them.
  const settled = tracks.length > 0 && tracks.every((t) => t.confirmed && t.result && t.result.conf >= 0.9);
  const reader = settled ? tier.read : tier.readNew;
  return { det: engine.has(tier.det) ? tier.det : 'det384', ocr: engine.has(reader) ? reader : 'ocrFast' };
}

async function liveLoop(id) {
  const cam = el.cam;
  while (scan.running && id === scan.loop) {
    if (!state.ready || cam.readyState < 2 || !cam.videoWidth) { updateHud(); await sleep(150); continue; }
    const t0 = performance.now();
    let bmp;
    try { bmp = await createImageBitmap(cam); } catch (_) { await sleep(80); continue; }
    const plan = framePlan();
    const conf = detConf();
    let res;
    try {
      res = await engine.analyze(bmp, {
        det: plan.det, ocr: plan.ocr, conf, maxPlates: 6, thumbs: true, minW: 40, roi: 'portrait', debug: settings.debug,
      });
    } catch (err) {
      if (!/^RESTART/.test(err.message)) log('error', `Live frame failed: ${err.message}`);
      await sleep(300);
      continue;
    }
    if (!scan.running || id !== scan.loop) { res.dets.forEach(closeDet); break; }
    const now = performance.now();
    scan.frames++;
    scan.frameW = res.w;
    scan.frameH = res.h;
    scan.last = { region: res.region, raw: res.raw, conf, det: res.det, ocr: res.ocr, t: now };
    const { confirmed, lost } = scan.tracker.update(res.dets.map(toDet), now);
    for (const t of confirmed) onConfirmed(t);
    finishTracks(lost);
    refreshTray();

    if (scan.lastT) scan.fps = scan.fps ? scan.fps * 0.85 + (1000 / (now - scan.lastT)) * 0.15 : 1000 / (now - scan.lastT);
    scan.lastT = now;
    scan.ms = res.ms;
    const idle = scan.idle.observe(res.dets.length, now) && settings.idle;
    if (idle !== scan.isIdle) {
      scan.isIdle = idle;
      log('perf', idle ? 'Idle: no plate in view, analysing 4 frames a second' : 'Plate in view: full speed');
    }
    const step = scan.adaptive.observe(res.ms.total, now, { pressure: pressureNow(), available: tierReady });
    if (step.changed) {
      log('perf', `Quality ${TIERS[step.changed.from].label} → ${TIERS[step.changed.to].label}: ${step.changed.reason}`, step.changed);
      emit('tier', step.changed);
    }
    if (step.want) wantTier(step.want);
    scan.perf.push({ t: now, ms: res.ms.total, det: res.ms.det, ocr: res.ms.ocr, tier: scan.adaptive.tier, idle, n: res.dets.length });
    if (scan.perf.length > PERF_KEEP) scan.perf.shift();
    updateHud();
    const gap = idle ? scan.idle.frameMs : FRAME_MS;
    const spent = performance.now() - t0;
    if (spent < gap) await sleep(gap - spent);
  }
}

/** Loads the models a better quality tier needs, in the background (not on Data Saver unless asked). */
function wantTier(name, force = false) {
  const t = TIERS[name];
  if (!t || scan.fetching || !state.ready) return;
  const keys = [...new Set([t.det, t.read, t.readNew])].filter((k) => !engine.has(k));
  if (!keys.length) return;
  const now = performance.now();
  if (!force && now - scan.wantFailedAt < 60000) return;
  if (!force && navigator.connection && navigator.connection.saveData) {
    if (!scan.saveDataNoted) { scan.saveDataNoted = true; log('perf', 'Data Saver is on, so sharper models aren’t downloaded automatically'); }
    return;
  }
  scan.fetching = true;
  log('engine', `Fetching ${keys.join(' + ')} for ${t.label} quality`);
  Promise.all(keys.map((k) => engine.load(k)))
    .catch((err) => { scan.wantFailedAt = performance.now(); log('error', `Couldn’t load ${keys.join(', ')}: ${err.message}`); })
    .finally(() => { scan.fetching = false; });
}

function onConfirmed(t) {
  haptic();
  blip();
  const r = t.result;
  log('track', `Confirmed ${r.text}`, {
    track: t.id, conf: +score(r).toFixed(3), agree: +r.conf.toFixed(2), reads: t.reads.length, region: r.region, tier: scan.adaptive.tier,
  });
}

function finishTracks(tracks) {
  for (const t of tracks) {
    if (t.confirmed && t.result) {
      const e = scan.board.put(t, settings.debug);
      savePlate(t.result, 'live', e ? e.thumbURL : null);
      log('track', `Finished ${t.result.text} (${t.endReason})`, { track: t.id, reads: t.reads.length, conf: +score(t.result).toFixed(3) });
    } else if (t.reads.length >= 2) {
      log('track', `Dropped unconfirmed track #${t.id} (${t.endReason})`, {
        best: t.result && t.result.text, reads: t.reads.length, agree: t.result && +t.result.conf.toFixed(2),
      });
    }
    t.closeThumbs();
  }
}

// Keyed DOM update so existing chips don't re-animate every frame.
function refreshTray() {
  if (scan.tracker) for (const t of scan.tracker.tracks) if (t.confirmed && t.result) scan.board.put(t, settings.debug);
  const tray = el.tray;
  const entries = scan.board.entries().sort((a, b) => b.created - a.created).slice(0, 24);
  const keep = new Set(entries.map((e) => e.key));
  for (const c of [...tray.children]) if (!keep.has(c.dataset.key)) c.remove();
  const byKey = new Map([...tray.children].map((c) => [c.dataset.key, c]));
  let prev = null;
  for (const e of entries) {
    let c = byKey.get(e.key);
    if (!c) {
      c = document.createElement('button');
      c.type = 'button';
      c.className = 'tray-chip fresh';
      c.dataset.key = e.key;
      setTimeout(() => c.classList.remove('fresh'), 1800);
    }
    const sig = `${e.r.text}|${e.r.region}|${Math.round(score(e.r) * 100)}|${e.thumbURL ? e.thumbURL.length : 0}`;
    if (c.dataset.sig !== sig) {
      c.dataset.sig = sig;
      c.innerHTML = `${e.thumbURL ? `<img src="${e.thumbURL}" alt="">` : ''}<span>${plateHTML(e.r, 'sm')}<span class="meta">${flagFor(e.r.region)} ${pct(score(e.r))}</span></span>`;
    }
    const ref = prev ? prev.nextSibling : tray.firstChild;
    if (c !== ref) tray.insertBefore(c, ref);
    prev = c;
  }
}

function updateHud() {
  const show = (settings.stats || settings.debug) && scan.running;
  if (el.hud.hidden === show) el.hud.hidden = !show;
  if (!show) return;
  if (!state.ready) { el.hudText.textContent = 'Loading AI…'; return; }
  const ms = scan.ms || { det: 0, ocr: 0, total: 0 };
  const i = engine.info || {};
  const tier = TIERS[scan.adaptive.tier];
  const lines = [
    `${scan.fps.toFixed(1)} fps · ${ms.total} ms · ${tier.label}${scan.isIdle ? ' · idle' : ''}`,
    `find ${ms.det} · read ${ms.ocr} ms · ${i.ep === 'webgpu' ? 'GPU' : `CPU×${i.threads || 1}`}`,
  ];
  if (settings.debug) lines.push(`${scan.adaptive.mode === 'auto' ? scan.adaptive.reason : 'quality fixed'}${pressureNow() ? ` · CPU ${pressureNow()}` : ''}`);
  el.hudText.textContent = lines.join('\n');
  drawSpark(el.hudGraph, scan.perf, { budget: scan.adaptive.budget });
}

const READER = { ocrFast: 'fast', ocrAcc: 'accurate' };

function drawLive() {
  if (!scan.running) return;
  requestAnimationFrame(drawLive);
  const { ctx, w, h } = fitCanvas(el.overlay, el.viewfinder);
  const fw = scan.frameW || el.cam.videoWidth;
  const fh = scan.frameH || el.cam.videoHeight;
  if (!fw || !scan.tracker) return;
  const map = mapper(w, h, fw, fh, 'cover');
  const now = performance.now();
  const topSafe = sizeOf(el.topbar).h || 60;
  const debug = settings.debug;
  if (debug && scan.last) {
    const L = scan.last;
    if (L.region) drawRegion(ctx, map, L.region, `analysed · ${L.det === 'det640' ? 640 : 384} px · ${READER[L.ocr] || L.ocr} reader`, { minTop: topSafe + 90, w, h });
    if (settings.dbgRaw && L.raw) drawRaw(ctx, map, L.raw, L.conf);
  }
  let visible = 0;
  const tracks = scan.tracker.tracks;
  for (const t of tracks) {
    const age = now - t.last;
    if (age > 700) continue;
    // Don't draw a fading box on top of a fresher one in the same place.
    if (age > 150 && tracks.some((o) => o !== t && o.last > t.last && iou(o.box, t.box) > 0.3)) continue;
    const target = t.predict(now);
    t.shown = t.shown.map((v, k) => v + (target[k] - v) * 0.35);
    const r = t.result;
    const color = t.confirmed ? '#6ee7b7' : r ? '#60a5fa' : 'rgba(255,255,255,0.9)';
    const flag = r && flagFor(r.region);
    const label = r ? `${flag ? `${flag} ` : ''}${r.text}` : 'Reading…';
    const sub = debug ? `#${t.id} · ${t.reads.length} read${t.reads.length === 1 ? '' : 's'}${r ? ` · agree ${pct(r.conf)} · ${pct(score(r))}` : ''}` : null;
    drawPlateBox(ctx, map(t.shown), {
      color, label, sub, solid: t.confirmed, alpha: age < 350 ? 1 : 1 - (age - 350) / 350, minTop: topSafe + 8, maxW: w,
    });
    visible++;
  }
  if (el.reticle.hidden !== visible > 0) el.reticle.hidden = visible > 0;
}

export function initScan() {
  el = {
    cam: $('#cam'), overlay: $('#camOverlay'), viewfinder: $('#viewfinder'), topbar: $('.topbar'),
    tray: $('#scanTray'), hud: $('#hud'), hudText: $('#hudText'), hudGraph: $('#hudGraph'), reticle: $('#reticle'),
  };
  $('#btnStartCam').onclick = startScan;
  $('#btnScan').onclick = () => (scan.running ? stopScan() : startScan());
  $('#btnTorch').onclick = toggleTorch;
  $('#zoomCtl').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setZoom(+b.dataset.z); });
  el.tray.addEventListener('click', (e) => {
    const b = e.target.closest('.tray-chip');
    const it = b && scan.board.get(b.dataset.key);
    if (it) openDetail(scan.board.plate(it), { source: 'live' });
  });

  on('mode', ({ prev, mode }) => {
    if (prev === 'scan') stopScan();
    if (mode === 'scan') maybeAutoStart();
  });
  on('settings', ({ key }) => {
    if (key === 'format' && scan.tracker) scan.tracker.setFormat(settings.format);
    if (key === 'minScore' && scan.tracker) scan.tracker.opts.minScore = settings.minScore;
    if (key === 'quality') {
      scan.adaptive.setMode(settings.quality, performance.now());
      log('perf', `Live quality set to ${settings.quality}`);
      if (settings.quality !== 'auto') wantTier(settings.quality, true);
    }
    if (key === 'stats' || key === 'debug') updateHud();
  });
  on('history-deleted', ({ key }) => { if (scan.board.remove(key)) refreshTray(); });
  on('history-cleared', () => { scan.board.clear(); refreshTray(); });
  on('ready', () => { if (settings.quality !== 'auto') wantTier(settings.quality, true); });

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      if (scan.running) { scan.resume = true; stopScan(); }
    } else if (scan.resume && state.mode === 'scan') {
      scan.resume = false;
      startScan();
    }
  });
}
