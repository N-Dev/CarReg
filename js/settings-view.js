// Settings sheet, "What's new" and the install prompt.
import { CFG } from './engine.js';
import { $, settings, state, engine, on, emit, log, setSetting, showSheet, hideSheet, mb } from './ctx.js';
import { safeModeOn, resetSafeMode, restartEngine } from './boot.js';
import { TIERS } from './adaptive.js';
import { scan } from './scan.js';
import { icon, esc, toast } from './ui.js';

export const devUnlocked = () => localStorage.getItem('ps_dev') === '1';

export function openSettings() { renderSettings(); showSheet('sheetSettings'); }

/** Bytes of AI files cached on the phone (models + runtime). */
async function cachedBytes() {
  let total = 0;
  for (const name of await caches.keys()) {
    if (!(name === 'ps-models' || name.startsWith('ps-ort-'))) continue;
    const c = await caches.open(name);
    for (const req of await c.keys()) {
      const res = await c.match(req);
      const n = +(res && res.headers.get('content-length'));
      total += n || (res ? (await res.blob()).size : 0);
    }
  }
  return total;
}

const QUALITY_TEXT = {
  auto: 'Uses the sharpest models this phone can run smoothly, and eases off when it’s busy or warm.',
  fast: 'Quickest models only. Best for older phones.',
  balanced: 'The accurate reader for new plates, the quick one once a plate is confirmed.',
  sharp: 'Sharper plate finder too, for small or distant plates. Needs a fast phone.',
};

export function renderSettings() {
  const i = engine.info || {};
  const seg = (key, opts) => `<div class="seg" data-setting="${key}">${opts.map(([v, l]) => `<button type="button" data-v="${v}" class="${String(settings[key]) === String(v) ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  const sw = (key, title, sub) => `<div class="row"><div class="row__text"><b>${title}</b>${sub ? `<small>${sub}</small>` : ''}</div><button type="button" class="switch" role="switch" data-toggle="${key}" aria-checked="${!!settings[key]}" aria-label="${title}"></button></div>`;
  const engineText = state.ready
    ? `ONNX Runtime ${i.version || CFG.ort.version} · ${i.ep === 'webgpu' ? 'GPU (WebGPU)' : `CPU · ${i.threads || 1} thread${i.threads > 1 ? 's' : ''}`}${i.safe ? ' · safe mode' : ''} · from ${i.source === 'cdn' ? 'CDN' : 'this site'}`
    : (state.bootError ? `Not loaded: ${state.bootError.message}` : 'Loading…');
  const now = TIERS[scan.adaptive.tier];
  const qualityNow = settings.quality === 'auto' && scan.running ? ` Now: <b class="hl">${now.label}</b>.` : '';
  $('#settingsBody').innerHTML = `
    <div class="group"><h4>Reading</h4><div class="group__box">
      <div class="row row--stack"><div class="row__text"><b>Plate format</b><small>Auto checks the format of the country the AI recognises on each plate.</small></div>
        ${seg('format', [['auto', 'Auto'], ['IE', 'Ireland'], ['UK', 'UK'], ['ANY', 'Any']])}</div>
      <div class="row row--stack"><div class="row__text"><b>Detection sensitivity</b><small>Higher finds smaller or partly hidden plates but makes more mistakes.</small></div>
        ${seg('sensitivity', [['low', 'Low'], ['medium', 'Medium'], ['high', 'High']])}</div>
    </div></div>
    <div class="group"><h4>Live scanning</h4><div class="group__box">
      <div class="row row--stack"><div class="row__text"><b>Quality</b><small>${QUALITY_TEXT[settings.quality] || ''}${qualityNow}</small></div>
        ${seg('quality', [['auto', 'Auto'], ['fast', 'Fast'], ['balanced', 'Balanced'], ['sharp', 'Sharp']])}</div>
      ${sw('stats', 'Show speed stats', 'Frames per second, model timings and a speed graph while scanning.')}
      ${sw('haptics', 'Vibrate on new plate')}
      ${sw('sound', 'Sound on new plate')}
    </div></div>
    <div class="group"><h4>Performance</h4><div class="group__box">
      <div class="row"><div class="row__text"><b>AI engine</b><small>${esc(engineText)}</small></div></div>
      ${sw('gpu', 'GPU acceleration (beta)', 'gpu' in navigator
    ? `Uses WebGPU. Downloads a larger engine (${mb(CFG.ort.gpu.wasmBytes)}) the first time.`
    : 'This browser doesn’t offer WebGPU, so the CPU is used either way.')}
      ${safeModeOn() ? '<div class="row"><div class="row__text"><b>Safe mode is on</b><small>The fast engine failed to start on this version, so PlateSight is using one CPU core. Try the fast engine again?</small></div><button type="button" class="chip-btn" data-action="unsafe">Try again</button></div>' : ''}
    </div></div>
    <div class="group"><h4>Privacy &amp; data</h4><div class="group__box">
      ${sw('history', 'Save plates to history', 'Kept only on this phone.')}
      ${sw('keepPhotos', 'Keep photos of plates', 'Off saves just the text.')}
      <div class="row row--stack"><div class="row__text"><b>Delete history after</b><small>Older plates are deleted automatically.</small></div>
        ${seg('retention', [['7', '7 days'], ['30', '30 days'], ['365', '1 year'], ['forever', 'Never']])}</div>
      <div class="row"><div class="row__text"><b>Downloaded AI files</b><small id="aiBytes">Checking…</small></div><button type="button" class="chip-btn" data-action="purge">Remove</button></div>
    </div></div>
    ${devUnlocked() ? `<div class="group"><h4>Developer</h4><div class="group__box">
      ${sw('debug', 'Debug mode', 'Live overlay, speed graph, diagnostics, read inspector and field testing.')}
      ${settings.debug ? `<div class="row"><div class="row__text"><b>Debug panel</b><small>Diagnostics, log, benchmarks, tuning and accuracy.</small></div><button type="button" class="chip-btn" data-action="debug">${icon('bug')}Open</button></div>` : ''}
    </div></div>` : ''}
    ${state.installEvt ? `<button type="button" class="btn btn--primary btn--lg" data-action="install">${icon('install')}Install PlateSight</button>` : ''}
    <p class="about"><b>PlateSight ${esc(CFG.release)}</b> <span class="muted">· build ${esc(CFG.app)} · <button type="button" class="linkish" data-action="whatsnew">What’s new</button></span><br><br>
    <b>Private by design.</b> PlateSight reads plates entirely on this phone. Camera frames, photos and videos are never uploaded. Number plates are personal data, so only scan where you have a good reason to, and don’t share or keep plates of people you don’t know.<br><br>Models: open-image-models (YOLOv9 plate detector) and fast-plate-ocr, both MIT licensed. Runtime: ONNX Runtime Web.</p>`;
  const bytesEl = $('#aiBytes');
  if ('caches' in window) {
    cachedBytes().then((n) => { if (bytesEl.isConnected) bytesEl.textContent = n ? `${mb(n)} on this phone. Remove to free space; they download again when needed.` : 'Nothing downloaded yet.'; })
      .catch(() => { bytesEl.textContent = 'Unknown size'; });
  } else bytesEl.textContent = 'Unknown size';
}

function onSettingsClick(e) {
  const b = e.target.closest('button');
  if (!b) return;
  const segEl = b.closest('.seg');
  if (segEl && b.dataset.v != null) {
    setSetting(segEl.dataset.setting, b.dataset.v);
    renderSettings();
    return;
  }
  if (b.dataset.toggle) {
    const key = b.dataset.toggle;
    setSetting(key, !settings[key]);
    b.setAttribute('aria-checked', String(!!settings[key]));
    if (key === 'gpu') toast('Restart the AI engine to apply', { action: 'Restart', onAction: () => restartEngine('GPU setting changed'), ms: 8000 });
    if (key === 'debug') renderSettings();
    return;
  }
  const act = b.dataset.action;
  if (act === 'install' && state.installEvt) state.installEvt.prompt();
  else if (act === 'unsafe') {
    resetSafeMode();
    log('engine', 'Safe mode reset by the user');
    toast('Trying the fast engine…');
    restartEngine('safe mode reset').then(() => renderSettings());
  } else if (act === 'purge') {
    toast('Remove downloaded AI files?', {
      action: 'Remove',
      ms: 6000,
      onAction: async () => {
        for (const k of await caches.keys()) if (k === 'ps-models' || k.startsWith('ps-ort-')) await caches.delete(k);
        log('data', 'Downloaded AI files removed');
        toast('Removed. They’ll download again next time they’re needed.');
        renderSettings();
      },
    });
  } else if (act === 'whatsnew') openWhatsNew();
  else if (act === 'debug') emit('open-debug', {});
}

// ---------------------------------------------------------------- what's new
const NEW = [
  ['gauge', 'Smarter live scanning', 'Uses sharper AI models when your phone has headroom, and eases off automatically when it’s busy or getting warm.'],
  ['bolt', 'Easier on the battery', 'While no plate is in view, PlateSight checks four times a second instead of fifteen.'],
  ['shield', 'History that tidies itself', 'Plates are deleted after 30 days (you can change that), and you can choose not to keep photos at all.'],
  ['refresh', 'Sturdier start-up', 'A slow first download no longer drops the app into safe mode, and updates never interrupt a download.'],
  ['sparkle', 'Something for tinkerers', 'There’s a hidden developer mode for testing accuracy. Android fans will know how to find it.'],
];

export function openWhatsNew() {
  $('#newBody').innerHTML = `
    <p class="new__lead">PlateSight ${esc(CFG.release)}</p>
    <ul class="new__list">${NEW.map(([ic, t, d]) => `<li><span class="new__ic">${icon(ic)}</span><div><b>${esc(t)}</b><small>${esc(d)}</small></div></li>`).join('')}</ul>
    <button type="button" class="btn btn--primary btn--lg" data-close-new>Got it</button>`;
  showSheet('sheetNew');
}

/** Shows "What's new" once after an update to a new release (not on a fresh install). */
export function maybeWhatsNew() {
  const seen = localStorage.getItem('ps_release');
  const upgraded = seen ? seen !== CFG.release : !!localStorage.getItem('ps_boot');
  localStorage.setItem('ps_release', CFG.release);
  if (upgraded) setTimeout(() => { if (!document.querySelector('.sheet.open')) openWhatsNew(); }, 1200);
}

export function initSettings() {
  $('#settingsBody').addEventListener('click', onSettingsClick);
  $('#newBody').addEventListener('click', (e) => { if (e.target.closest('[data-close-new]')) hideSheet(); });
  $('#btnSettings').onclick = openSettings;
  $('#status').onclick = openSettings;
  on('open-settings', openSettings);
  on('ready', () => { if ($('#sheetSettings').classList.contains('open')) renderSettings(); });
  on('tier', () => { if ($('#sheetSettings').classList.contains('open')) renderSettings(); });
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    state.installEvt = e;
    if (!localStorage.getItem('ps_install_nudged')) {
      localStorage.setItem('ps_install_nudged', '1');
      setTimeout(() => toast('Add PlateSight to your home screen', { action: 'Install', onAction: () => e.prompt(), ms: 9000 }), 5000);
    }
  });
  window.addEventListener('appinstalled', () => { state.installEvt = null; toast('Installed. PlateSight is on your home screen.'); });
}
