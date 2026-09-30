// History: every plate saved on this phone, grouped by day, with search, CSV export, automatic
// deletion after the retention period, and an option not to keep photos.
import { history as plates } from './store.js';
import { clean, flagFor } from './formats.js';
import { $, settings, state, on, emit, log, registered, shareOrDownload, setSetting } from './ctx.js';
import { icon, plateHTML, dayLabel, clock, esc, toast } from './ui.js';
import { openDetail } from './detail.js';

export const RETENTION = { 7: '7 days', 30: '30 days', 365: 'a year', forever: 'forever' };

// The list is read from the database once and kept until something changes, so typing in the
// search box filters in memory instead of re-reading every plate on each keystroke.
let cache = null;
let gen = 0;
let searchTimer = 0;
function invalidate() { gen++; cache = null; }
async function entries() {
  if (cache) return cache;
  const g = gen;
  const all = await plates.all().catch(() => []);
  if (g === gen) cache = all;
  return all;
}

function footNote() {
  if (!settings.history) {
    return `<div class="hist-note"><span>${icon('info')}Saving to history is off, so new plates aren’t kept.</span><button type="button" class="chip-btn" data-hact="enable">Turn on</button></div>`;
  }
  const kept = settings.retention === 'forever' ? 'until you delete them' : `for ${RETENTION[settings.retention] || `${settings.retention} days`}, then deleted automatically`;
  return `<p class="hist-foot">Plates are kept on this phone only, ${kept}${settings.keepPhotos ? '' : ' · photos aren’t kept'}.</p>`;
}

export async function renderHistory() {
  const all = await entries();
  if (state.mode !== 'history') return;
  const raw = $('#histSearch').value;
  const q = clean(raw);
  const list = q ? all.filter((e) => e.key.includes(q) || clean(e.text).includes(q)) : all;
  $('#histCount').textContent = all.length ? String(all.length) : '';
  const box = $('#histList');
  if (!all.length) {
    box.innerHTML = `<div class="hist-empty"><div class="hero-icon">${icon('history')}</div><b>No plates yet</b><span>Plates you scan are kept here, on this phone only.</span></div>${footNote()}`;
    return;
  }
  if (!list.length) {
    box.innerHTML = `<div class="hist-empty"><b>No matches</b><span>Nothing matches “${esc(raw)}”.</span></div>`;
    return;
  }
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
  box.innerHTML = html + footNote();
}

/** Deletes plates older than the retention setting; returns how many were deleted. */
export async function pruneHistory() {
  try {
    const n = await plates.prune(settings.retention);
    if (n) {
      log('data', `Auto-deleted ${n} plate${n === 1 ? '' : 's'} older than ${RETENTION[settings.retention] || settings.retention}`);
      emit('history-changed', {});
    }
    return n;
  } catch (err) {
    log('error', `Couldn’t tidy history: ${err.message || err}`);
    return 0;
  }
}

async function exportCsv() {
  const all = await entries();
  if (!all.length) { toast('Nothing to export yet'); return; }
  const q = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const rows = [['plate', 'country', 'valid_format', 'confidence', 'times_seen', 'first_seen', 'last_seen', 'source', 'registered', 'county_or_area']];
  for (const e of all) {
    const i = e.info || {};
    rows.push([e.text, e.region || '', e.valid, (e.conf || 0).toFixed(2), e.count, new Date(e.first).toISOString(), new Date(e.last).toISOString(), e.source, registered(e), i.county || i.area || '']);
  }
  const csv = rows.map((r) => r.map(q).join(',')).join('\n');
  const name = `platesight-${new Date().toISOString().slice(0, 10)}.csv`;
  await shareOrDownload(new File([csv], name, { type: 'text/csv' }), 'PlateSight history', 'CSV saved to Downloads');
}

export function initHistory() {
  $('#histSearch').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(renderHistory, 120);
  });
  $('#histList').addEventListener('click', async (e) => {
    const act = e.target.closest('[data-hact]');
    if (act) { if (act.dataset.hact === 'enable') { setSetting('history', true); toast('Plates will be saved again'); } return; }
    const b = e.target.closest('.hist-item');
    if (!b) return;
    const it = (await entries()).find((x) => x.key === b.dataset.key);
    if (it) openDetail(it, { fromHistory: true, source: 'history' });
  });
  $('#btnExport').onclick = exportCsv;
  $('#btnClearHist').onclick = () => toast('Delete all saved plates?', {
    action: 'Delete all',
    ms: 6000,
    onAction: async () => {
      await plates.clear();
      log('data', 'History cleared');
      emit('history-cleared', {});
      emit('history-changed', {});
      toast('History cleared');
    },
  });
  on('history-changed', () => { invalidate(); if (state.mode === 'history') renderHistory(); });
  on('mode', ({ mode }) => { if (mode === 'history') renderHistory(); });
  on('settings', async ({ key }) => {
    if (key === 'retention') {
      const n = await pruneHistory();
      if (n) toast(`Deleted ${n} older plate${n === 1 ? '' : 's'}`);
      else if (state.mode === 'history') renderHistory();
    }
    if (key === 'keepPhotos' && !settings.keepPhotos) {
      toast('Also remove photos already saved?', {
        action: 'Remove',
        ms: 8000,
        onAction: async () => {
          await plates.stripPhotos();
          log('data', 'Saved photos removed');
          emit('history-changed', {});
          toast('Saved photos removed');
        },
      });
    }
    if ((key === 'history' || key === 'keepPhotos') && state.mode === 'history') renderHistory();
  });
  // The app can stay open for days: tidy up whenever it comes back to the foreground.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) pruneHistory(); });
}
