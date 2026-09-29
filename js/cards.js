// Result cards for the photo and video screens.
import { icon, plateHTML, countryLabel, describe, pct, esc } from './ui.js';
import { score, formatName, settings, copyText, sharePlate } from './ctx.js';
import { openDetail } from './detail.js';

/** Handles Copy / Share / Details on the cards in `container`; find(key) returns the plate. */
export function bindCards(container, find, source) {
  container.addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const p = find(b.dataset.key);
    if (!p) return;
    if (b.dataset.act === 'copy') copyText(p.text);
    else if (b.dataset.act === 'share') sharePlate(p);
    else if (b.dataset.act === 'detail') openDetail(p, { source });
  });
}

/** p: plate (vote result + thumbURL). n: badge number (or null). extra: more tag HTML. */
export function resultCard(p, n, extra = '', animate = true) {
  const s = score(p);
  const desc = describe(p);
  const fmt = formatName(p);
  const low = settings.minScore > 0 && s < settings.minScore;
  return `
  <article class="card${low ? ' card--low' : ''}" style="${animate ? `animation-delay:${Math.min(n || 0, 8) * 55}ms` : 'animation:none'}">
    ${p.thumbURL ? `<img class="card__thumb" src="${p.thumbURL}" alt="">` : ''}
    <div class="card__top">
      ${n != null ? `<span class="badge-n">${n}</span>` : ''}
      ${plateHTML(p)}
      <span class="ring${p.valid && !low ? '' : ' ring--warn'}" style="--p:${Math.round(s * 100)}"><span>${pct(s)}</span></span>
    </div>
    <div class="tags">
      <span class="tag">${esc(countryLabel(p.region))}</span>
      ${desc ? `<span class="tag">${esc(desc)}</span>` : ''}
      ${p.valid ? `<span class="tag tag--ok">${fmt ? `Valid ${fmt} plate` : 'Looks valid'}</span>` : '<span class="tag tag--warn">Unverified format</span>'}
      ${low ? '<span class="tag tag--warn">Below your confidence threshold</span>' : ''}
      ${extra}
    </div>
    <div class="card__actions">
      <button class="chip-btn" type="button" data-act="copy" data-key="${esc(p.key)}">${icon('copy')}Copy</button>
      <button class="chip-btn" type="button" data-act="share" data-key="${esc(p.key)}">${icon('share')}Share</button>
      <button class="chip-btn" type="button" data-act="detail" data-key="${esc(p.key)}">${icon('chevron')}Details</button>
    </div>
  </article>`;
}
