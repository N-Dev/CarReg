// What TrafficSight keeps, all on this phone: settings (localStorage) and counting sessions with one
// record per road user counted (IndexedDB). No images, video or number plates, ever.
import { DEFAULT_LINES } from './counter.js';

const KEY = 'tc_settings';
export const DEFAULTS = {
  lines: DEFAULT_LINES,
  distanceM: 20,          // measured distance between the two lines along the road
  limit: 50,              // speed limit, km/h
  dirNames: ['Left to right', 'Right to left'],
  site: '',
  model: 'auto',          // auto | tiny | nano
  sensitivity: 'medium',  // low | medium | high
  dimAfter: 60,           // seconds without a touch before the screen dims while counting (0 = never)
  pace: 'fast',           // fast: every frame the phone keeps up with | cool: about 10 a second, for long warm sessions
  setupDone: false,
};
export const SENS = { low: 0.45, medium: 0.3, high: 0.2 };

export function loadSettings() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || '{}');
    return { ...DEFAULTS, ...s, lines: { ...DEFAULTS.lines, ...(s.lines || {}) } };
  } catch (_) {
    return { ...DEFAULTS };
  }
}
export function saveSettings(s) {
  try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (_) { /* private mode: settings last this visit */ }
}

let dbp = null;
function db() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const r = indexedDB.open('trafficsight', 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        d.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
        const ev = d.createObjectStore('events', { keyPath: 'id', autoIncrement: true });
        ev.createIndex('session', 'session');
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }
  return dbp;
}
const done = (tx) => new Promise((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error); });
const req = (r) => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });

export const store = {
  async newSession(s) {
    const tx = (await db()).transaction('sessions', 'readwrite');
    const id = await req(tx.objectStore('sessions').add(s));
    await done(tx);
    return id;
  },
  async saveSession(s) {
    const tx = (await db()).transaction('sessions', 'readwrite');
    tx.objectStore('sessions').put(s);
    await done(tx);
  },
  async sessions() {
    const tx = (await db()).transaction('sessions');
    const all = await req(tx.objectStore('sessions').getAll());
    return all.sort((a, b) => b.started - a.started);
  },
  async session(id) {
    return req((await db()).transaction('sessions').objectStore('sessions').get(id));
  },
  async addEvents(session, records) {
    if (!records.length) return;
    const tx = (await db()).transaction('events', 'readwrite');
    const os = tx.objectStore('events');
    for (const r of records) os.add({ ...r, session });
    await done(tx);
  },
  async events(session) {
    const tx = (await db()).transaction('events');
    return req(tx.objectStore('events').index('session').getAll(session));
  },
  async deleteSession(id) {
    const d = await db();
    const tx = d.transaction(['sessions', 'events'], 'readwrite');
    tx.objectStore('sessions').delete(id);
    const idx = tx.objectStore('events').index('session');
    const keys = await req(idx.getAllKeys(id));
    for (const k of keys) tx.objectStore('events').delete(k);
    await done(tx);
  },
  async clear() {
    const tx = (await db()).transaction(['sessions', 'events'], 'readwrite');
    tx.objectStore('sessions').clear();
    tx.objectStore('events').clear();
    await done(tx);
  },
};
