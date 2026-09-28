// Settings (localStorage) and plate history (IndexedDB). Everything stays on the phone.

const DEFAULTS = {
  format: 'auto',        // auto | IE | UK | ANY
  sensitivity: 'medium', // low | medium | high
  gpu: false,
  stats: false,
  haptics: true,
  sound: false,
  history: true,
};
export const SENS = { low: 0.5, medium: 0.35, high: 0.25 };

export function loadSettings() {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem('ps_settings') || '{}') }; } catch (_) { return { ...DEFAULTS }; }
}
export function saveSettings(s) {
  try { localStorage.setItem('ps_settings', JSON.stringify(s)); } catch (_) { /* storage unavailable */ }
}

let dbPromise = null;
function openDb() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open('platesight', 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('plates')) db.createObjectStore('plates', { keyPath: 'key' });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

async function withStore(mode, fn) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('plates', mode);
    let out;
    fn(tx.objectStore('plates'), (v) => { out = v; });
    tx.oncomplete = () => resolve(out);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const history = {
  /** Insert or merge a sighting: { key, text, region, valid, conf, source, thumb, info, profile, time }. */
  add(entry) {
    return withStore('readwrite', (st, done) => {
      const get = st.get(entry.key);
      get.onsuccess = () => {
        const old = get.result;
        const t = entry.time || Date.now();
        const rec = old
          ? {
            ...old,
            text: entry.text || old.text,
            region: entry.region || old.region,
            profile: entry.profile || old.profile,
            valid: !!(entry.valid || old.valid),
            info: entry.info || old.info,
            count: (old.count || 1) + 1,
            last: t,
            source: entry.source || old.source,
            conf: Math.max(old.conf || 0, entry.conf || 0),
            thumb: entry.thumb && (entry.conf || 0) >= (old.conf || 0) - 0.05 ? entry.thumb : old.thumb,
          }
          : {
            key: entry.key, text: entry.text, region: entry.region || null, profile: entry.profile || 'ANY',
            valid: !!entry.valid, info: entry.info || null, conf: entry.conf || 0, count: 1,
            first: t, last: t, source: entry.source || 'live', thumb: entry.thumb || null,
          };
        st.put(rec);
        done(rec);
      };
    });
  },
  all() {
    return withStore('readonly', (st, done) => {
      const r = st.getAll();
      r.onsuccess = () => done((r.result || []).sort((a, b) => b.last - a.last));
    });
  },
  remove(key) { return withStore('readwrite', (st) => { st.delete(key); }); },
  clear() { return withStore('readwrite', (st) => { st.clear(); }); },
};
