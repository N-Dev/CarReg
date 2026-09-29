// PlateSight entry point: wires the screens together and starts the AI engine.
//   ctx.js           shared state, settings, events, debug log, helpers
//   boot.js          AI engine start-up         scan.js      live camera scanning
//   photo.js         photo mode                 video.js     video mode
//   history-view.js  history                    detail.js    plate details, read inspector, field test
//   settings-view.js settings, what's new       debug.js     debug panel
import { CFG, setupServiceWorker } from './engine.js';
import { $, $$, settings, engine, state, setMode, hideSheet, closeSheet, log, setSetting, logEntries } from './ctx.js';
import { initBoot, startEngine, onAppUpdate } from './boot.js';
import { scan, initScan, maybeAutoStart } from './scan.js';
import { photo, initPhoto, handlePhoto } from './photo.js';
import { vjob, initVideo, handleVideo } from './video.js';
import { initHistory, pruneHistory } from './history-view.js';
import { initDetail } from './detail.js';
import { initSettings, maybeWhatsNew } from './settings-view.js';
import { initDebug } from './debug.js';
import { icon } from './ui.js';

function paintIcons() {
  $$('[data-icon]').forEach((el) => { el.innerHTML = icon(el.dataset.icon); });
  $('#btnSettings').innerHTML = icon('settings');
  $('#btnDebug').innerHTML = icon('bug');
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

/** A photo or video shared to the installed app ("Share → PlateSight"). */
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
    log('app', `Opened a shared ${file.type.startsWith('video/') ? 'video' : 'photo'}`);
    if (file.type.startsWith('video/')) handleVideo(file);
    else handlePhoto(file);
  } catch (_) { /* nothing shared */ }
}

// Anything that goes wrong ends up in the debug log.
window.addEventListener('error', (e) => log('error', `Script error: ${e.message}`, { at: `${String(e.filename || '').split('/').pop()}:${e.lineno}` }));
window.addEventListener('unhandledrejection', (e) => log('error', `Unhandled: ${(e.reason && e.reason.message) || e.reason}`));

async function main() {
  paintIcons();
  initBoot();
  initScan();
  initPhoto();
  initVideo();
  initHistory();
  initDetail();
  initSettings();
  initDebug();
  $('#tabbar').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setMode(b.dataset.mode); });
  $('#backdrop').onclick = hideSheet;
  $$('[data-close]').forEach((b) => { b.onclick = hideSheet; });
  window.addEventListener('popstate', () => closeSheet());

  const params = new URLSearchParams(location.search);
  log('app', `PlateSight ${CFG.release} (build ${CFG.app})`, { standalone: matchMedia('(display-mode: standalone)').matches, debug: settings.debug });
  setMode(params.get('mode') || 'scan');
  const sw = await setupServiceWorker(onAppUpdate);
  if (sw.reloading) return;
  state.swSetup = false;
  if (sw.error) log('error', `Service worker: ${sw.error.message || sw.error}`);
  if (params.has('mode') || params.has('shared')) window.history.replaceState(null, '', location.pathname);
  if (params.get('shared')) openShared();
  maybeWhatsNew();
  pruneHistory();
  if (state.mode === 'scan') maybeAutoStart();
  await startEngine();
}

main();

// Test hook (used by the automated browser tests only; harmless in normal use).
window.__plateSight = { engine, state, scan, photo, vjob, settings, setSetting, logEntries };
