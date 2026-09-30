// Shared app context: settings, the AI engine, app state, the event bus, the debug log and small
// helpers used by every screen. Screens talk to each other through events (on/emit) instead of
// importing each other, so each screen's module stays independent.
import { Engine, MODELS } from './engine.js';
import { loadSettings, saveSettings, history as plates } from './store.js';
import { toast, openSheet, closeSheet } from './ui.js';

export const $ = (s, root = document) => root.querySelector(s);
export const $$ = (s, root = document) => [...root.querySelectorAll(s)];
export const settings = loadSettings();
export const engine = new Engine();
export const state = {
  mode: null,
  ready: false,        // AI engine loaded and warmed up
  starting: false,     // engine start-up in progress
  swSetup: true,       // service worker still being set up (the page may reload once)
  bootError: null,
  engineErrors: [],
  installEvt: null,
  updatePending: false,
};
export function save() { saveSettings(settings); }

/** Changes a setting, saves it and tells every screen. */
export function setSetting(key, value) {
  if (settings[key] === value) return;
  settings[key] = value;
  save();
  emit('settings', { key, value });
}

// ---------------------------------------------------------------- events
const bus = new EventTarget();
/** Subscribes to an app event; returns a function that unsubscribes. */
export function on(type, fn) {
  const h = (e) => fn(e.detail);
  bus.addEventListener(type, h);
  return () => bus.removeEventListener(type, h);
}
export function emit(type, detail = {}) { bus.dispatchEvent(new CustomEvent(type, { detail })); }

// ---------------------------------------------------------------- debug log (ring buffer)
const LOG = [];
const LOG_MAX = 600;
export const T0 = performance.now();
/** cat: app | engine | scan | track | perf | photo | video | data | error */
export function log(cat, msg, data = null) {
  const e = { t: performance.now(), wall: Date.now(), cat, msg, data };
  LOG.push(e);
  if (LOG.length > LOG_MAX) LOG.shift();
  if (cat === 'error') console.warn(`[PlateSight] ${msg}`, data ?? '');
  emit('log', e);
  return e;
}
export const logEntries = () => LOG.slice();

// ---------------------------------------------------------------- screens and sheets
const MODES = ['scan', 'photo', 'video', 'history'];
export function setMode(mode) {
  if (!MODES.includes(mode)) mode = 'scan';
  if (state.mode === mode) return;
  const prev = state.mode;
  state.mode = mode;
  $('#app').dataset.mode = mode;
  $$('.view').forEach((v) => { v.hidden = v.dataset.view !== mode; });
  $$('#tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  emit('mode', { prev, mode });
}

/** Opens a bottom sheet; the phone's back button closes it. */
export function showSheet(id) {
  openSheet(id);
  if (!(window.history.state && window.history.state.sheet)) window.history.pushState({ sheet: 1 }, '');
}
export function hideSheet() {
  if (window.history.state && window.history.state.sheet) window.history.back();
  else closeSheet();
}

// ---------------------------------------------------------------- plate helpers
/** Overall confidence 0..1: how much the frames agreed x how sure the reader was. */
export const score = (r) => Math.max(0, Math.min(1, (r.conf ?? 0) * (r.prob ?? 1)));

export function formatName(r) {
  if (!r.valid) return '';
  if (r.profile === 'IE') return 'Irish';
  if (r.format === 'NI') return 'NI';
  if (r.profile === 'UK') return 'UK';
  return '';
}

export function registered(r) {
  const i = r && r.info;
  if (!i || !i.year) return '';
  if (r.profile === 'IE') return i.period ? `${i.period} ${i.year}` : String(i.year);
  if (r.profile === 'UK') return i.period === 'Mar–Aug' ? `Mar–Aug ${i.year}` : `Sep ${i.year} – Feb ${i.year + 1}`;
  return String(i.year);
}

export const mb = (bytes) => `${(bytes / 1e6).toFixed(bytes >= 1e7 ? 0 : 1)} MB`;
export const modelBytes = (keys) => keys.reduce((a, k) => a + MODELS[k].bytes, 0);

// ---------------------------------------------------------------- feedback
export function haptic(ms = 18) { if (settings.haptics && navigator.vibrate) navigator.vibrate(ms); }

let audio = null;
export function blip() {
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

export async function copyText(text, label = null) {
  try { await navigator.clipboard.writeText(text); toast(label || `Copied ${text}`); return true; } catch (_) { toast('Couldn’t copy on this device'); return false; }
}

export async function sharePlate(r) {
  const text = `${r.text}${r.region ? ` (${r.region})` : ''}`;
  if (navigator.share) { try { await navigator.share({ title: 'Number plate', text }); } catch (_) { /* cancelled */ } } else copyText(r.text);
}

/** Shares a file (Android share sheet) or, where that isn't possible, downloads it. */
export async function shareOrDownload(file, title, savedText) {
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title }); return; } catch (err) { if (err && err.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = file.name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  toast(savedText || `${file.name} saved to Downloads`);
}

// ---------------------------------------------------------------- history
/** Saves a sighting to history (if enabled); the photo only if the user keeps photos. */
export function savePlate(r, source, thumb, time = Date.now()) {
  if (!settings.history || !r) return Promise.resolve(null);
  return plates.add({
    key: r.key, text: r.text, region: r.region, profile: r.profile, valid: r.valid, conf: score(r), info: r.info,
    source, thumb: settings.keepPhotos ? thumb : null, time,
  })
    .then((rec) => { emit('history-changed', { key: r.key }); return rec; })
    .catch((err) => { log('error', 'Couldn’t save to history', String(err)); return null; });
}

// ---------------------------------------------------------------- engine helpers
/** Resolves once the AI engine is ready (rejects if it failed to start). */
export function waitReady() {
  if (state.ready) return Promise.resolve();
  if (state.bootError) return Promise.reject(state.bootError);
  return new Promise((resolve, reject) => {
    const offs = [
      on('ready', () => { offs.forEach((f) => f()); resolve(); }),
      on('boot-failed', ({ error }) => { offs.forEach((f) => f()); reject(error); }),
    ];
  });
}

/** Loads models, reporting the combined download progress 0..1. */
export async function loadWithProgress(keys, onProgress) {
  const total = modelBytes(keys);
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

export { closeSheet };
