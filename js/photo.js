// Photo mode: the high-accuracy models, three slightly different crops per plate (test-time
// augmentation) voted together, and a tiled deep scan when no plate is found at first.
import { vote } from './tracker.js';
import { SENS } from './store.js';
import {
  $, settings, engine, log, haptic, score, savePlate, setMode, waitReady, loadWithProgress, mb, modelBytes,
} from './ctx.js';
import { fitCanvas, mapper, drawPlateBox, drawRegion, drawRaw, drawTiles } from './overlay.js';
import { bitmapToDataURL, esc } from './ui.js';
import { resultCard, bindCards } from './cards.js';
import { closeDet } from './board.js';

export const photo = { url: null, result: null, busy: false };
const PRECISE = ['det640', 'ocrAcc'];
const VARIANTS = ['as detected', 'wider crop', 'tighter crop'];

const sub = (text) => { const s = $('#photoSub'); if (s) s.textContent = text; };

export async function handlePhoto(file) {
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
  const debug = settings.debug;
  try {
    await img.decode().catch(() => {});
    await waitReady();
    const needs = PRECISE.filter((k) => !engine.has(k));
    if (needs.length) {
      const size = mb(modelBytes(needs));
      sub(`Getting the high-accuracy models (${size}, first time only)…`);
      await loadWithProgress(needs, (f) => sub(`Downloading high-accuracy models ${Math.round(f * 100)}% of ${size}`));
    }
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const conf = Math.min(0.3, settings.dbgConf || SENS[settings.sensitivity]);
    const res = await engine.analyze(bmp, {
      det: 'det640', ocr: 'ocrAcc', conf, maxPlates: 12, tta: true, deep: true, thumbs: true, minW: 14, debug,
    });
    const found = [];
    for (const d of res.dets) {
      const r = d.reads ? vote(d.reads, settings.format, 0.2) : null;
      if (r && r.key.length >= 3 && r.prob >= 0.35) {
        const p = { ...r, box: d.box, thumbURL: d.thumb ? bitmapToDataURL(d.thumb) : null, detScore: d.score };
        if (debug) {
          p.cropURL = d.crop ? bitmapToDataURL(d.crop, 0.92) : null;
          p.inputURL = d.ocrInput ? bitmapToDataURL(d.ocrInput, 1, 'image/png') : null;
          p.inputRead = d.reads[0];
          p.reads = d.reads.map((x, i) => ({ text: x.text, conf: x.conf, model: d.model, variant: VARIANTS[i] || `variant ${i + 1}` }));
          p.model = d.model;
        }
        found.push(p);
      }
      closeDet(d);
    }
    found.sort((a, b) => (a.box[0] - b.box[0]) || (a.box[1] - b.box[1]));
    photo.result = {
      w: res.w, h: res.h, plates: found, deep: !!res.tiles, tiles: res.tiles, ms: res.ms, raw: res.raw, region: res.region, conf,
    };
    renderPhotoResult();
    if (found.length) haptic(24);
    log('photo', `${found.length} plate${found.length === 1 ? '' : 's'} in ${res.w}×${res.h} photo`, {
      ms: res.ms, deep: !!res.tiles, plates: found.map((p) => ({ text: p.text, conf: +score(p).toFixed(3), region: p.region })),
    });
    for (const p of found) if (!settings.minScore || score(p) >= settings.minScore) savePlate(p, 'photo', p.thumbURL);
  } catch (err) {
    log('error', `Photo failed: ${err.message || err}`);
    $('#photoHead').innerHTML = '<h3>Couldn’t read this photo</h3>';
    $('#photoResults').innerHTML = `<div class="none"><b>Something went wrong</b>${esc(err.message || err)}</div>`;
  } finally {
    $('#photoStage').classList.remove('scanning');
    photo.busy = false;
  }
}

export function drawPhotoOverlay() {
  const { ctx, w, h } = fitCanvas($('#photoOverlay'), $('#photoStage'));
  const r = photo.result;
  if (!r) return;
  const map = mapper(w, h, r.w, r.h, 'contain');
  if (settings.debug) {
    if (r.tiles) drawTiles(ctx, map, r.tiles);
    else if (r.region) drawRegion(ctx, map, r.region, 'analysed · 640 px');
    if (settings.dbgRaw && r.raw) drawRaw(ctx, map, r.raw, r.conf);
  }
  r.plates.forEach((p, i) => drawPlateBox(ctx, map(p.box), { color: p.valid ? '#6ee7b7' : '#fbbf24', badge: i + 1 }));
}

function renderPhotoResult() {
  const r = photo.result;
  const n = r.plates.length;
  const timing = settings.debug
    ? `find ${r.ms.det} ms · read ${r.ms.ocr} ms${r.deep ? ' · deep scan' : ''}`
    : `${r.deep ? 'deep scan · ' : ''}${(r.ms.total / 1000).toFixed(1)} s`;
  $('#photoHead').innerHTML = n
    ? `<h3>${n} plate${n === 1 ? '' : 's'} found</h3><span>${timing}</span>`
    : `<h3>No plates found</h3>${settings.debug ? `<span>${timing}</span>` : ''}`;
  $('#photoResults').innerHTML = n
    ? r.plates.map((p, i) => resultCard(p, i + 1)).join('')
    : '<div class="none"><b>Nothing readable here</b>Try a closer, sharper shot with the plate facing the camera. Glare and steep angles make plates hard to read.</div>';
  drawPhotoOverlay();
}

function pick(id) { const input = $(id); input.value = ''; input.click(); }

export function initPhoto() {
  $('#btnPhotoCam').onclick = () => pick('#filePhotoCam');
  $('#btnPhotoPick').onclick = () => pick('#filePhoto');
  $('#btnPhotoAgain').onclick = () => pick('#filePhotoCam');
  $('#btnPhotoAgainPick').onclick = () => pick('#filePhoto');
  $('#filePhotoCam').onchange = (e) => handlePhoto(e.target.files[0]);
  $('#filePhoto').onchange = (e) => handlePhoto(e.target.files[0]);
  bindCards($('#photoResults'), (key) => photo.result && photo.result.plates.find((p) => p.key === key), 'photo');
  new ResizeObserver(() => { if (photo.result) drawPhotoOverlay(); }).observe($('#photoStage'));
}
