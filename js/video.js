// Video mode: plays the clip muted, analysing frames as it goes (slowing playback on slower phones so
// enough frames get read), tracks every plate across frames and lists each with when it appears.
import { Tracker } from './tracker.js';
import { SENS } from './store.js';
import { $, settings, engine, state, on, log, haptic, score, savePlate, setMode, waitReady } from './ctx.js';
import { Board, closeDet } from './board.js';
import { fitCanvas, mapper, drawPlateBox } from './overlay.js';
import { mmss } from './ui.js';
import { resultCard, bindCards } from './cards.js';

export const vjob = {
  seq: 0, active: false, done: false, url: null, tracker: null, board: new Board(), ema: 0, frameW: 0, frameH: 0,
  busy: false, paused: false, shown: new Set(), frames: 0,
};

const toDet = (d) => ({
  box: d.box, score: d.score, thumb: d.thumb || null, crop: d.crop || null, ocrInput: d.ocrInput || null,
  read: d.reads ? { ...d.reads[0], partial: !!d.edge, model: d.model } : null,
});

function once(v, ev, errEv = 'error') {
  return new Promise((resolve, reject) => {
    const ok = () => { v.removeEventListener(errEv, bad); resolve(); };
    const bad = () => { v.removeEventListener(ev, ok); reject(new Error('This video format can’t be played here')); };
    v.addEventListener(ev, ok, { once: true });
    v.addEventListener(errEv, bad, { once: true });
  });
}

function nextFrame(v, cb) {
  if ('requestVideoFrameCallback' in HTMLVideoElement.prototype) v.requestVideoFrameCallback(cb);
  else requestAnimationFrame((t) => cb(t, null));
}

export async function handleVideo(file) {
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
  Object.assign(vjob, { active: true, done: false, paused: false, busy: false, ema: 0, frames: 0, shown: new Set(), board: new Board(), started: performance.now() });
  vjob.tracker = new Tracker({ format: settings.format, maxAge: 1500, minReads: 2, minScore: settings.minScore });
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
    log('video', `Scanning ${v.videoWidth}×${v.videoHeight} video, ${mmss(v.duration)}`, { size: file.size, type: file.type });
  } catch (err) {
    vjob.active = false;
    log('error', `Video: ${err.message || err}`);
    $('#vidStatus').textContent = err.message || 'Couldn’t open this video';
    $('#videoAgainRow').hidden = false;
    $('#btnVidStop').hidden = true;
    return;
  }
  const step = async (_now, meta) => {
    if (id !== vjob.seq || !vjob.active) return;
    if (!vjob.busy && !v.paused && state.ready) {
      vjob.busy = true;
      const mt = ((meta && meta.mediaTime) ?? v.currentTime) * 1000;
      try {
        const bmp = await createImageBitmap(v);
        const res = await engine.analyze(bmp, {
          det: 'det384', ocr: 'ocrFast', conf: settings.dbgConf || SENS[settings.sensitivity], maxPlates: 8, thumbs: true, minW: 32, debug: settings.debug,
        });
        if (id === vjob.seq && vjob.active) {
          vjob.frames++;
          vjob.frameW = res.w;
          vjob.frameH = res.h;
          const { confirmed, lost } = vjob.tracker.update(res.dets.map(toDet), mt);
          if (confirmed.length) {
            haptic(12);
            confirmed.forEach((t) => { vjob.board.put(t, settings.debug); log('track', `Confirmed ${t.result.text} at ${mmss(mt / 1000)}`, { track: t.id, reads: t.reads.length }); });
            renderVideoResults(false);
          }
          collectVideo(lost);
          adaptRate(res.ms.total);
          drawVideoOverlay(mt);
        } else res.dets.forEach(closeDet);
      } catch (err) { if (!/^RESTART/.test(err.message)) log('error', `Video frame failed: ${err.message}`); }
      vjob.busy = false;
    }
    if (id === vjob.seq && vjob.active && !v.ended) nextFrame(v, step);
  };
  nextFrame(v, step);
}

function collectVideo(tracks) {
  let changed = false;
  for (const t of tracks) {
    const r = t.result;
    // Also keep a plate seen only briefly if every read agreed on a valid plate.
    if (r && (t.confirmed || (r.valid && r.prob >= 0.9 && t.fullReads() > 0 && (!settings.minScore || score(r) >= settings.minScore)))) {
      vjob.board.put(t, settings.debug);
      changed = true;
    }
    t.closeThumbs();
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
  if (!v.duration || !Number.isFinite(v.duration)) return;
  $('#vidBar').style.width = `${Math.min(100, (v.currentTime / v.duration) * 100).toFixed(1)}%`;
  if (vjob.active) {
    const n = vjob.board.entries().length;
    $('#vidStatus').textContent = `Scanning ${mmss(v.currentTime)} / ${mmss(v.duration)} · ${n} plate${n === 1 ? '' : 's'}${v.playbackRate < 0.99 ? ` · ${v.playbackRate.toFixed(2)}×` : ''}`;
  }
}

function drawVideoOverlay(mt) {
  const { ctx, w, h } = fitCanvas($('#vidOverlay'), $('#videoStage'));
  if (!vjob.tracker || !vjob.frameW) return;
  const map = mapper(w, h, vjob.frameW, vjob.frameH, 'contain');
  for (const t of vjob.tracker.tracks) {
    if (mt - t.last > 300) continue;
    const r = t.result;
    drawPlateBox(ctx, map(t.box), {
      color: t.confirmed ? '#6ee7b7' : r ? '#60a5fa' : 'rgba(255,255,255,0.9)',
      label: r ? r.text : null,
      sub: settings.debug ? `#${t.id} · ${t.reads.length} reads` : null,
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
  collectVideo(vjob.tracker.flush());
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
  log('video', `Done: ${n} plate${n === 1 ? '' : 's'}, ${vjob.frames} frames analysed in ${((performance.now() - vjob.started) / 1000).toFixed(1)} s`, {
    plates: entries.map((e) => e.r.text),
  });
}

function stopVideoScan() {
  if (!vjob.active) return;
  $('#vid').pause();
  finishVideo(vjob.seq);
}
function pauseVideo() { const v = $('#vid'); if (vjob.active && !v.paused) { v.pause(); vjob.paused = true; } }
function resumeVideo() { const v = $('#vid'); if (vjob.active && vjob.paused) { vjob.paused = false; v.play().catch(() => {}); } }

function renderVideoResults(final) {
  const entries = vjob.board.entries().sort((a, b) => a.first - b.first);
  const n = entries.length;
  $('#videoHead').innerHTML = n ? `<h3>${final ? `${n} plate${n === 1 ? '' : 's'}` : 'Found so far'}</h3><span>${final ? 'tap “at 0:00” to jump there' : ''}</span>` : '';
  $('#videoResults').innerHTML = n
    ? entries.map((e) => {
      const extra = `<span class="tag" data-seek="${e.first / 1000}">▶ at ${mmss(e.first / 1000)}</span>${e.brief ? '<span class="tag tag--warn">Seen briefly</span>' : ''}`;
      const html = resultCard(vjob.board.plate(e), null, extra, !vjob.shown.has(e.key));
      vjob.shown.add(e.key);
      return html;
    }).join('')
    : (final ? '<div class="none"><b>No plates found</b>Plates need to be reasonably large and sharp in the frame. Try a steadier or closer clip.</div>' : '');
}

function pick(id) { const input = $(id); input.value = ''; input.click(); }

export function initVideo() {
  $('#btnVideoCam').onclick = () => pick('#fileVideoCam');
  $('#btnVideoPick').onclick = () => pick('#fileVideo');
  $('#btnVideoAgain').onclick = () => pick('#fileVideo');
  $('#btnVidStop').onclick = stopVideoScan;
  $('#fileVideoCam').onchange = (e) => handleVideo(e.target.files[0]);
  $('#fileVideo').onchange = (e) => handleVideo(e.target.files[0]);
  $('#videoResults').addEventListener('click', (e) => {
    const seek = e.target.closest('[data-seek]');
    if (!seek || !vjob.done) return;
    const v = $('#vid');
    v.currentTime = Math.max(0, +seek.dataset.seek - 0.3);
    v.play().catch(() => {});
    $('#videoStage').scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  bindCards($('#videoResults'), (key) => { const e = vjob.board.get(key); return e && vjob.board.plate(e); }, 'video');
  on('mode', ({ prev, mode }) => {
    if (prev === 'video') pauseVideo();
    if (mode === 'video') resumeVideo();
  });
  on('settings', ({ key }) => {
    if (key === 'format' && vjob.tracker) vjob.tracker.setFormat(settings.format);
    if (key === 'minScore' && vjob.tracker) vjob.tracker.opts.minScore = settings.minScore;
  });
  on('history-deleted', ({ key }) => { if (vjob.done && vjob.board.remove(key)) renderVideoResults(true); });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) pauseVideo();
    else if (state.mode === 'video') resumeVideo();
  });
}
