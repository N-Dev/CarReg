// Plate detail sheet. In debug mode it adds the field test (is this read right?) and the read
// inspector: exactly what the plate reader saw, how sure it was of each character and what it
// considered instead, its country guess, and how the frames voted.
import { CFG } from './engine.js';
import { history as plates, samples } from './store.js';
import { clean } from './formats.js';
import { accuracyStats, alignOps } from './fieldtest.js';
import { $, settings, emit, log, score, formatName, registered, copyText, sharePlate, showSheet, hideSheet } from './ctx.js';
import { icon, plateHTML, countryLabel, pct, pct1, timeAgo, esc, toast, dataURLToBlob } from './ui.js';

const READER = { ocrFast: 'fast', ocrAcc: 'accurate' };
let current = null;            // { p, fromHistory, source, id }
const labelled = new Map();    // plate shown -> sample id, so a plate isn't labelled twice by accident

export function openDetail(p, { fromHistory = false, source = null } = {}) {
  current = { p, fromHistory, source: source || (fromHistory ? 'history' : 'live') };
  current.id = `${current.source}:${p.key}:${p.first || p.last || ''}`;
  render();
  showSheet('sheetDetail');
}

function render() {
  const { p, fromHistory } = current;
  const thumb = p.thumbURL || p.thumb || null;
  const reg = registered(p);
  const i = p.info || {};
  const cells = [
    ['Country', countryLabel(p.region)],
    ['Format', p.valid ? (formatName(p) ? `Valid ${formatName(p)}` : 'Looks valid') : 'Unverified'],
  ];
  if (reg) cells.push(['Registered', reg]);
  if (i.county) cells.push(['County', i.countyGa && i.countyGa !== i.county ? `${i.county} · ${i.countyGa}` : i.county]);
  if (i.area) cells.push(['Area', i.area]);
  cells.push(['Confidence', pct(fromHistory ? p.conf : score(p))]);
  if (fromHistory) {
    cells.push(['Seen', `${p.count}× · ${p.source}`]);
    cells.push(['Last seen', timeAgo(p.last)]);
  }
  $('#detailBody').innerHTML = `
    <div class="detail-hero">${thumb ? `<img src="${thumb}" alt="">` : ''}${plateHTML(p, 'lg')}</div>
    <div class="info-grid">${cells.map(([k, v]) => `<div class="info"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}</div>
    <div class="detail-actions">
      <button class="btn btn--ghost" type="button" data-act="copy">${icon('copy')}Copy</button>
      <button class="btn btn--ghost" type="button" data-act="share">${icon('share')}Share</button>
      ${fromHistory ? `<button class="btn btn--danger" type="button" data-act="delete">${icon('trash')}Delete</button>` : `<button class="btn btn--ghost" type="button" data-act="close">${icon('check')}Done</button>`}
    </div>
    ${settings.debug ? fieldTest() + inspector(p) : ''}`;
}

// ---------------------------------------------------------------- field test
function fieldTest() {
  const done = labelled.get(current.id);
  return `
    <section class="ft" id="ft">
      <div class="ft__head">${icon('flask')}<div><b>Field test</b><small>Is this reading right? Your answers build an accuracy score and a training set for the plate reader.</small></div></div>
      <div class="ft__btns" ${done ? 'hidden' : ''}>
        <button class="btn btn--ok" type="button" data-act="ft-right">${icon('ok')}Right</button>
        <button class="btn btn--bad" type="button" data-act="ft-wrong">${icon('x')}Wrong</button>
      </div>
      <div class="ft__fix" hidden>
        <label>What does the plate really say?</label>
        <div class="ft__row">
          <input id="ftTruth" type="text" autocomplete="off" autocapitalize="characters" spellcheck="false" value="${esc(current.p.text)}">
          <button class="btn btn--primary" type="button" data-act="ft-save">Save</button>
        </div>
      </div>
      <div class="ft__done" ${done ? '' : 'hidden'}>${done ? doneHTML(done) : ''}</div>
    </section>`;
}

function doneHTML({ id, ok, stats, truth }) {
  const diff = ok ? '' : `<div class="diff">${diffHTML(current.p.text, truth)}</div>`;
  return `${diff}<p>${icon('check')}<span>Saved as test sample #${id}.${stats ? ` ${stats.n} labelled so far, ${pct(stats.exactRate)} read right.` : ''}</span>
    <button type="button" class="chip-btn" data-act="ft-undo">Undo</button></p>`;
}

/** Read vs truth with the differences highlighted. */
export function diffHTML(read, truth) {
  return alignOps(read, truth).map((o) => {
    if (o.op === 'same') return `<span>${esc(o.a)}</span>`;
    if (o.op === 'sub') return `<span class="d-sub" title="read ${esc(o.a)}, really ${esc(o.b)}"><s>${esc(o.a)}</s>${esc(o.b)}</span>`;
    if (o.op === 'del') return `<span class="d-del"><s>${esc(o.a)}</s></span>`;
    return `<span class="d-ins">${esc(o.b)}</span>`;
  }).join('');
}

async function saveSample(truthRaw) {
  const { p, source, fromHistory } = current;
  const truth = clean(truthRaw);
  if (truth.length < 2) { toast('Type the plate as it really is'); return; }
  const crop = p.cropURL || null;
  const photo = p.thumbURL || p.thumb || null;
  const read = p.inputRead || null;
  const rec = {
    time: Date.now(),
    truth,
    predicted: clean(p.text),
    shown: p.text,
    conf: fromHistory ? (p.conf || 0) : score(p),
    source,
    region: p.region || null,
    valid: !!p.valid,
    model: p.model || (read && read.model) || null,
    image: crop ? dataURLToBlob(crop) : (photo ? dataURLToBlob(photo) : null),
    imageKind: crop ? 'crop' : (photo ? 'photo' : null),
    input: p.inputURL ? dataURLToBlob(p.inputURL) : null,
    app: CFG.app,
  };
  try {
    const id = await samples.add(rec);
    const stats = accuracyStats(await samples.all());
    const ok = truth === rec.predicted;
    labelled.set(current.id, { id, ok, stats, truth });
    log('data', `Field test #${id}: ${ok ? 'right' : `wrong (read ${rec.predicted}, really ${truth})`}`, { conf: +rec.conf.toFixed(3), source, imageKind: rec.imageKind });
    emit('samples-changed', {});
    const ft = $('#ft');
    if (ft) {
      ft.querySelector('.ft__btns').hidden = true;
      ft.querySelector('.ft__fix').hidden = true;
      const d = ft.querySelector('.ft__done');
      d.innerHTML = doneHTML(labelled.get(current.id));
      d.hidden = false;
    }
  } catch (err) {
    log('error', `Couldn’t save the test sample: ${err.message || err}`);
    toast('Couldn’t save the test sample');
  }
}

async function undoSample() {
  const done = labelled.get(current.id);
  if (!done) return;
  await samples.remove(done.id).catch(() => {});
  labelled.delete(current.id);
  log('data', `Field test #${done.id} removed`);
  emit('samples-changed', {});
  render();
}

// ---------------------------------------------------------------- read inspector
function inspector(p) {
  const read = p.inputRead;
  if (!read && !(p.reads && p.reads.length)) {
    return `<section class="insp insp--empty">${icon('target')}<span>Inspector data is captured for plates read while debug mode is on.</span></section>`;
  }
  let html = `<section class="insp"><h4>${icon('target')}Read inspector</h4>`;
  if (p.inputURL) {
    const model = (read && read.model) || p.model;
    html += `<figure class="insp__input"><img src="${p.inputURL}" alt="Plate reader input"><figcaption>Exactly what the plate reader saw (128 × 64 px)${model ? ` · ${READER[model] || model} reader` : ''}</figcaption></figure>`;
  }
  if (read && read.probs && read.text) {
    html += `<div class="insp__chars">${[...read.text].map((ch, k) => {
      const pr = read.probs[k] ?? 0;
      const alt = read.alts && read.alts[k];
      const cls = pr < 0.5 ? 'bad' : pr < 0.85 ? 'meh' : 'ok';
      const altTxt = alt && alt[1] >= 0.005 ? `${alt[0] === '_' ? '∅' : esc(alt[0])} ${pct1(alt[1])}` : '';
      return `<div class="ch ch--${cls}"><b>${esc(ch)}</b><i><span style="height:${Math.max(3, Math.round(pr * 100))}%"></span></i><small>${pct(pr)}</small><em>${altTxt || '&nbsp;'}</em></div>`;
    }).join('')}</div>
    <p class="insp__note">Bars: how sure the reader was of each character. Below: the runner-up it considered (∅ = no character).</p>`;
  }
  if (read && read.regions && read.regions.length) {
    html += `<div class="insp__kv"><span>Country guess</span><b>${read.regions.map(([r, pr]) => `${esc(r)} ${pct1(pr)}`).join(' · ')}</b></div>`;
  }
  const facts = [];
  if (p.conf != null && p.prob != null) facts.push(['Frames agreed', pct(p.conf)], ['Reader certainty', pct(p.prob)]);
  if (p.n) facts.push(['Reads used', String(p.n)]);
  if (p.track) facts.push(['Track', `#${p.track.id} · ${p.track.hits} frames`]);
  if (p.detScore != null) facts.push(['Detector score', p.detScore.toFixed(2)]);
  if (p.regionConf != null && p.region) facts.push(['Country agreement', pct(p.regionConf)]);
  if (facts.length) html += `<div class="insp__facts">${facts.map(([k, v]) => `<div><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}</div>`;
  if (p.reads && p.reads.length) html += votes(p.reads);
  return `${html}</section>`;
}

function votes(reads) {
  const groups = new Map();
  for (const r of reads) {
    const k = clean(r.text);
    const g = groups.get(k) || { text: k, n: 0, w: 0, models: {}, partial: 0, variants: [] };
    g.n++;
    g.w += (r.conf || 0) * (r.weight ?? 1) * (r.partial ? 0.3 : 1);
    if (r.model) g.models[r.model] = (g.models[r.model] || 0) + 1;
    if (r.partial) g.partial++;
    if (r.variant) g.variants.push(r.variant);
    groups.set(k, g);
  }
  const list = [...groups.values()].sort((a, b) => b.w - a.w);
  const total = list.reduce((a, g) => a + g.w, 0) || 1;
  const rows = list.map((g) => {
    const bits = [
      ...Object.entries(g.models).map(([m, c]) => `${READER[m] || m} ${c}`),
      g.partial ? `${g.partial} cut off` : '',
      g.variants.length ? g.variants.join(', ') : '',
    ].filter(Boolean).join(' · ');
    return `<div class="vote"><code>${esc(g.text || '—')}</code><i><span style="width:${((g.w / total) * 100).toFixed(1)}%"></span></i><small>${g.n}×${bits ? ` · ${esc(bits)}` : ''}</small></div>`;
  }).join('');
  return `<div class="insp__votes"><h5>How the ${reads.length} read${reads.length === 1 ? '' : 's'} voted</h5>${rows}</div>`;
}

// ---------------------------------------------------------------- wiring
export function initDetail() {
  $('#detailBody').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-act]');
    if (!b || !current) return;
    const { p } = current;
    const act = b.dataset.act;
    if (act === 'copy') copyText(p.text);
    else if (act === 'share') sharePlate(p);
    else if (act === 'close') hideSheet();
    else if (act === 'delete') {
      await plates.remove(p.key);
      log('data', `Deleted ${p.text} from history`);
      emit('history-deleted', { key: p.key });
      emit('history-changed', { key: p.key });
      hideSheet();
      toast(`Deleted ${p.text}`);
    } else if (act === 'ft-right') saveSample(p.text);
    else if (act === 'ft-wrong') {
      const ft = $('#ft');
      ft.querySelector('.ft__btns').hidden = true;
      ft.querySelector('.ft__fix').hidden = false;
      const input = $('#ftTruth');
      input.focus();
      input.select();
    } else if (act === 'ft-save') saveSample($('#ftTruth').value);
    else if (act === 'ft-undo') undoSample();
  });
  $('#detailBody').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.id === 'ftTruth') { e.preventDefault(); saveSample(e.target.value); }
  });
}
