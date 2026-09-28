// Small UI toolkit: icons, the plate component, toasts, bottom sheets, formatting helpers.
import { flagFor, bandFor } from './formats.js';

const P = {
  scan: '<path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2"/><rect x="7" y="10" width="10" height="4" rx="1"/>',
  photo: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21"/>',
  video: '<rect x="2" y="6" width="14" height="12" rx="2"/><path d="m22 8-6 4 6 4V8z"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
  settings: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  bolt: '<path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/>',
  copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/>',
  trash: '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
  camera: '<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/>',
  gallery: '<path d="M18 22H4a2 2 0 0 1-2-2V6"/><rect x="6" y="2" width="16" height="16" rx="2"/><circle cx="12" cy="8" r="2"/><path d="m22 13-2.3-2.3a2 2 0 0 0-2.8 0L10 18"/>',
  record: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4" fill="currentColor"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  install: '<rect x="5" y="2" width="14" height="20" rx="2"/><path d="M12 7v7M9 11l3 3 3-3"/>',
  chip: '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  play: '<path d="M7 4v16l13-8z"/>',
};

export function icon(name, cls = '') {
  return `<svg class="ic ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[name] || ''}</svg>`;
}

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const STARS = (() => {
  let s = '<svg class="stars" viewBox="0 0 20 20" aria-hidden="true">';
  for (let k = 0; k < 12; k++) {
    const a = (Math.PI * 2 * k) / 12;
    s += `<circle cx="${(10 + 7 * Math.cos(a)).toFixed(2)}" cy="${(10 + 7 * Math.sin(a)).toFixed(2)}" r="1.25"/>`;
  }
  return `${s}</svg>`;
})();

/** Stylised number plate. r: { text, region, profile, format, valid, info } */
export function plateHTML(r, size = 'md') {
  const text = r.text || '…';
  let style = 'eu';
  if (r.profile === 'IE') style = 'ie';
  else if (r.profile === 'UK') style = 'uk';
  const band = bandFor(r.region) || (r.profile === 'IE' ? 'IRL' : r.profile === 'UK' ? 'UK' : '');
  const top = r.profile === 'IE' && r.info && r.info.countyGa ? `<small>${esc(r.info.countyGa)}</small>` : '';
  const cls = `plate plate--${style} plate--${size}${r.valid ? '' : ' plate--unsure'}`;
  const bandHtml = band ? `<span class="plate__band">${STARS}<b>${esc(band)}</b></span>` : '';
  return `<span class="${cls}">${bandHtml}<span class="plate__body">${top}<strong>${esc(text)}</strong></span></span>`;
}

export function countryLabel(region) {
  if (!region) return 'Unknown country';
  return `${flagFor(region)} ${region}`.trim();
}

/** Short human description of what the plate encodes (year, county, area). */
export function describe(r) {
  const i = r && r.info;
  if (!i) return '';
  const parts = [];
  if (i.year) parts.push(i.period ? `${i.year} · ${i.period}` : String(i.year));
  if (i.county) parts.push(i.county);
  if (i.area) parts.push(i.area);
  return parts.join(' · ');
}

export function pct(x) { return `${Math.round((x || 0) * 100)}%`; }

export function timeAgo(t) {
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 45) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
export function clock(t) { return new Date(t).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }); }
export function mmss(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
export function dayLabel(t) {
  const d = new Date(t);
  const today = new Date();
  const y = new Date(); y.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: d.getFullYear() === today.getFullYear() ? undefined : 'numeric' });
}

// ---------------------------------------------------------------- toast
let toastTimer = 0;
export function toast(message, { action = null, onAction = null, ms = 2800 } = {}) {
  const el = document.getElementById('toast');
  el.innerHTML = `<span>${esc(message)}</span>${action ? `<button type="button">${esc(action)}</button>` : ''}`;
  if (action) el.querySelector('button').onclick = () => { hide(); if (onAction) onAction(); };
  el.classList.add('show');
  clearTimeout(toastTimer);
  const hide = () => el.classList.remove('show');
  toastTimer = setTimeout(hide, ms);
}

// ---------------------------------------------------------------- bottom sheets
let openSheetEl = null;
export function openSheet(id) {
  closeSheet();
  const el = document.getElementById(id);
  openSheetEl = el;
  document.getElementById('backdrop').classList.add('show');
  el.classList.add('open');
  el.setAttribute('aria-hidden', 'false');
}
export function closeSheet() {
  if (!openSheetEl) return;
  openSheetEl.classList.remove('open');
  openSheetEl.setAttribute('aria-hidden', 'true');
  openSheetEl = null;
  document.getElementById('backdrop').classList.remove('show');
}

/** Converts an ImageBitmap (or canvas) to a small JPEG data URL. */
export function bitmapToDataURL(bmp, quality = 0.82) {
  if (!bmp) return null;
  const c = document.createElement('canvas');
  c.width = bmp.width;
  c.height = bmp.height;
  c.getContext('2d').drawImage(bmp, 0, 0);
  return c.toDataURL('image/jpeg', quality);
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
