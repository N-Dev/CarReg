// Settings (localStorage), plate history and field-test samples (IndexedDB). Everything stays on the phone.

const DEFAULTS = {
  format: 'auto',        // auto | IE | UK | ANY
  sensitivity: 'medium', // low | medium | high
  quality: 'auto',       // live quality: auto | fast | balanced | sharp
  gpu: false,
  stats: false,
  haptics: true,
  sound: false,
  history: true,
  keepPhotos: true,
  retention: '30',       // days to keep history: 7 | 30 | 365 | forever
  debug: false,
  // Debug-mode tuning (only changeable while debug mode is on)
  threads: 0,            // CPU threads for the AI engine, 0 = automatic
  idle: true,            // slow down while no plate is in view
  dbgConf: 0,            // detection threshold override, 0 = from sensitivity
  minScore: 0,           // only confirm plates at or above this confidence (0 = off), tuned from field tests
  dbgRaw: true,          // debug overlay: show every raw detection
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
      const req = indexedDB.open('platesight', 2);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('plates')) db.createObjectStore('plates', { keyPath: 'key' });
        if (!db.objectStoreNames.contains('samples')) db.createObjectStore('samples', { keyPath: 'id', autoIncrement: true });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

async function withStore(name, mode, fn) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(name, mode);
    let out;
    fn(tx.objectStore(name), (v) => { out = v; });
    tx.oncomplete = () => resolve(out);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

const getAll = (name) => withStore(name, 'readonly', (st, done) => {
  const r = st.getAll();
  r.onsuccess = () => done(r.result || []);
});

/** Which saved plates are past the retention period ('forever' keeps everything). */
export function expired(entries, retention, now = Date.now()) {
  const days = retention === 'forever' ? Infinity : Number(retention) || 30;
  if (!Number.isFinite(days)) return [];
  const cutoff = now - days * 86400000;
  return entries.filter((e) => (e.last || 0) < cutoff).map((e) => e.key);
}

export const history = {
  /** Insert or merge a sighting: { key, text, region, valid, conf, source, thumb, info, profile, time }. */
  add(entry) {
    return withStore('plates', 'readwrite', (st, done) => {
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
  async all() { return (await getAll('plates')).sort((a, b) => b.last - a.last); },
  remove(key) { return withStore('plates', 'readwrite', (st) => { st.delete(key); }); },
  clear() { return withStore('plates', 'readwrite', (st) => { st.clear(); }); },
  /** Deletes plates older than the retention period; resolves to the number removed. */
  async prune(retention) {
    const keys = expired(await getAll('plates'), retention);
    if (keys.length) await withStore('plates', 'readwrite', (st) => { for (const k of keys) st.delete(k); });
    return keys.length;
  },
  /** Removes the saved photo from every plate (keeps the text). */
  stripPhotos() {
    return withStore('plates', 'readwrite', (st) => {
      const r = st.openCursor();
      r.onsuccess = () => {
        const c = r.result;
        if (!c) return;
        if (c.value.thumb) c.update({ ...c.value, thumb: null });
        c.continue();
      };
    });
  },
};

/** Field-test samples: reads the user marked right or wrong, with the plate image, for accuracy and training data. */
export const samples = {
  add(rec) { return withStore('samples', 'readwrite', (st, done) => { const r = st.add(rec); r.onsuccess = () => done(r.result); }); },
  async all() { return (await getAll('samples')).sort((a, b) => a.time - b.time); },
  remove(id) { return withStore('samples', 'readwrite', (st) => { st.delete(id); }); },
  clear() { return withStore('samples', 'readwrite', (st) => { st.clear(); }); },
};
