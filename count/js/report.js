// The one-page report for the council: where and when, counts by type and direction, vehicles per
// hour, speeds, and how it was measured. Built with the small PDF writer, so it works offline.
import { Pdf, A4, textWidth } from './pdf.js';
import { KINDS, ORDER, LONG_VEHICLE_M } from './counter.js';

// Print colours (validated as a pair on white): direction 1, direction 2, over the limit.
const INK = '#111111';
const INK2 = '#52514e';
const MUTED = '#898781';
const GRID = '#e1e0d9';
const AXIS = '#c3c2b7';
const DIR = ['#2a78d6', '#eb6834'];
const WITHIN = '#2a78d6';
const OVER = '#d03b3b';

const M = 44; // page margin
const fmt = (n) => (n == null ? '–' : Number(n).toLocaleString('en-IE'));
const pad = (n) => String(n).padStart(2, '0');
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export function when(t) {
  const d = new Date(t);
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
/** "Tue 29 Sep 2026, 07:10 to 19:45", or both dates in full when it runs past midnight. */
export function period(from, to) {
  const a = new Date(from);
  const b = new Date(to);
  return a.toDateString() === b.toDateString() ? `${when(from)} to ${pad(b.getHours())}:${pad(b.getMinutes())}` : `${when(from)} to ${when(to)}`;
}
/** Vehicles an hour, or just the time counted when that's under half an hour (an hourly rate would mislead). */
export function rate(s) {
  return s.hours >= 0.5 ? `${fmt(s.perHourAvg)} an hour on average` : `in ${duration(s.hours)}`;
}
const hh = (t) => pad(new Date(t).getHours());
function duration(hours) {
  const mins = Math.round(hours * 60);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h ? `${h} h ${pad(m)} min` : `${m} min`;
}

/** Neat axis steps: 1, 2, 5, 10, 20, 50 ... so gridlines land on round numbers. */
function niceStep(max, ticks = 4) {
  const raw = Math.max(1, max) / ticks;
  const p = 10 ** Math.floor(Math.log10(raw));
  return Math.max(1, [1, 2, 5, 10].map((m) => m * p).find((s) => s >= raw)); // whole numbers: these are counts
}

/**
 * The report as PDF bytes. session: { site, distanceM, limit, dirNames, started, ended };
 * s: the summarize() figures for the session.
 */
export function councilReport(session, s, { app = '', release = '' } = {}) {
  const pdf = new Pdf({ title: `Traffic survey – ${session.site || 'untitled site'}`, author: 'TrafficSight' });
  const dirNames = session.dirNames || ['Direction 1', 'Direction 2'];
  const right = A4.w - M;
  let y = M + 20;
  pdf.text(M, y, 'Traffic survey', { size: 22, bold: true });
  y += 22;
  pdf.text(M, y, session.site || 'Untitled site', { size: 13, bold: true, color: INK });
  y += 16;
  pdf.text(M, y, `${period(s.from, s.to)} · ${duration(s.hours)} of counting`, { size: 9.5, color: INK2 });
  y += 26;

  // Key figures.
  const sp = s.speeds.all;
  const tiles = [
    ['Motor vehicles', fmt(s.motor), rate(s)],
    ['Busiest hour', s.peak ? fmt(s.peak.motor) : '–', s.peak ? `${hh(s.peak.start)}:00 to ${pad((new Date(s.peak.start).getHours() + 1) % 24)}:00` : 'no vehicles'],
    ['85% drove at or under', sp.n ? `${Math.round(sp.p85)} km/h` : '–', sp.n ? `from ${fmt(sp.n)} speeds measured` : 'no speeds measured'],
    [`Over the ${s.limit} km/h limit`, sp.n ? `${sp.overPct}%` : '–', sp.n ? `${fmt(sp.over)} vehicle${sp.over === 1 ? '' : 's'}` : ''],
  ];
  const tw = (A4.w - 2 * M) / tiles.length;
  tiles.forEach(([label, value, sub], i) => {
    const x = M + i * tw;
    pdf.text(x, y, label, { size: 8.5, color: INK2 });
    pdf.text(x, y + 22, value, { size: 19, bold: true });
    pdf.text(x, y + 36, sub, { size: 8, color: MUTED });
  });
  y += 58;
  pdf.line(M, y, right, y, { color: GRID, width: 0.75 });
  y += 22;

  // Counts (left) and speeds (right).
  const colW = (A4.w - 2 * M - 24) / 2;
  const top = y;
  pdf.text(M, y, 'Road users counted', { size: 11, bold: true });
  y += 18;
  const cx = [M, M + colW * 0.5, M + colW * 0.75, M + colW];
  // Column headings on up to two lines (direction names can be long), bottom-aligned.
  const head = (yy, cols, xs, width) => cols.forEach((c, i) => {
    const lines = wrap2(c, i ? width : 200, 8);
    lines.forEach((l, k) => pdf.text(xs[i], yy - (lines.length - 1 - k) * 9, l, { size: 8, color: INK2, bold: true, align: i ? 'right' : 'left' }));
  });
  y += 9;
  head(y, ['Type', dirNames[0], dirNames[1], 'Total'], [cx[0], cx[1], cx[2], cx[3]], colW * 0.24);
  y += 6;
  pdf.line(M, y, M + colW, y, { color: AXIS, width: 0.5 });
  y += 12;
  const rows = ORDER.filter((k) => s.totals[k].all || k === 'car');
  for (const k of rows) {
    const t = s.totals[k];
    pdf.text(cx[0], y, KINDS[k].label, { size: 9 });
    pdf.text(cx[1], y, fmt(t[1]), { size: 9, align: 'right' });
    pdf.text(cx[2], y, fmt(t[2]), { size: 9, align: 'right' });
    pdf.text(cx[3], y, fmt(t.all), { size: 9, align: 'right', bold: true });
    y += 14;
  }
  pdf.line(M, y - 9, M + colW, y - 9, { color: GRID, width: 0.5 });
  y += 2;
  pdf.text(cx[0], y, 'All motor vehicles', { size: 9, bold: true });
  pdf.text(cx[1], y, fmt(s.motorDir[1]), { size: 9, align: 'right', bold: true });
  pdf.text(cx[2], y, fmt(s.motorDir[2]), { size: 9, align: 'right', bold: true });
  pdf.text(cx[3], y, fmt(s.motor), { size: 9, align: 'right', bold: true });
  y += 14;
  pdf.text(cx[0], y, `Long vehicles (${LONG_VEHICLE_M} m or more)`, { size: 9, color: INK2 });
  pdf.text(cx[3], y, fmt(s.long), { size: 9, align: 'right', color: INK2 });
  const leftEnd = y;

  // Speeds table.
  y = top;
  const sx0 = M + colW + 24;
  pdf.text(sx0, y, 'Speeds of motor vehicles (km/h)', { size: 11, bold: true });
  y += 18;
  const sxs = [sx0, sx0 + colW * 0.52, sx0 + colW * 0.76, sx0 + colW];
  y += 9;
  head(y, ['', 'Both ways', dirNames[0], dirNames[1]], sxs, colW * 0.23);
  y += 6;
  pdf.line(sx0, y, sx0 + colW, y, { color: AXIS, width: 0.5 });
  y += 12;
  const S = [s.speeds.all, s.speeds[1], s.speeds[2]];
  const sRows = [
    ['Speeds measured', (x) => fmt(x.n)],
    ['Average', (x) => (x.n ? x.mean : '–')],
    ['85th percentile', (x) => (x.n ? x.p85 : '–'), true],
    ['Fastest', (x) => (x.n ? x.max : '–')],
    [`Over ${s.limit} km/h`, (x) => (x.n ? `${fmt(x.over)} (${x.overPct}%)` : '–')],
    [`Over ${s.limit + 10} km/h`, (x) => (x.n ? fmt(x.over10) : '–')],
  ];
  for (const [label, f, bold] of sRows) {
    pdf.text(sxs[0], y, label, { size: 9, bold: !!bold });
    S.forEach((x, i) => pdf.text(sxs[i + 1], y, String(f(x)), { size: 9, align: 'right', bold: !!bold }));
    y += 14;
  }
  y = Math.max(leftEnd, y) + 20;
  pdf.line(M, y - 8, right, y - 8, { color: GRID, width: 0.75 });
  y += 8;

  // Vehicles per hour, stacked by direction.
  pdf.text(M, y, 'Motor vehicles per hour', { size: 11, bold: true });
  legend(pdf, right, y, [[DIR[0], dirNames[0]], [DIR[1], dirNames[1]]]);
  y += 12;
  y = hourChart(pdf, M, y, A4.w - 2 * M, 118, s.perHour) + 18;

  // Speed distribution.
  pdf.text(M, y, 'How fast motor vehicles went', { size: 11, bold: true });
  legend(pdf, right, y, [[WITHIN, `Within ${s.limit} km/h`], [OVER, `Over ${s.limit} km/h`]]);
  y += 12;
  y = speedChart(pdf, M, y, A4.w - 2 * M, 104, s) + 16;

  // How it was measured.
  const note = `How this was measured: road users were counted automatically by TrafficSight, an app running on a phone `
    + `camera that analyses the picture on the phone itself. No images, video or number plates were recorded or kept. `
    + `Each road user is counted once, when it first crosses one of two lines marked across the road; its speed is the `
    + `${session.distanceM} m between the lines divided by the time it took, so speeds are estimates that have not been `
    + `checked against a calibrated speed device, and depend on how accurately that distance was measured. Vans and `
    + `lorries are counted together; long vehicles are those measured at ${LONG_VEHICLE_M} m or more. Vehicles hidden behind others `
    + `can be missed, so counts are a minimum.`;
  y = pdf.paragraph(M, Math.min(y, A4.h - 76), note, A4.w - 2 * M, { size: 7.5, color: INK2, lead: 1.4 });
  pdf.text(M, A4.h - 26, `Generated ${when(Date.now())} · TrafficSight ${release}${app && app !== 'dev' ? ` (build ${app})` : ''}`, { size: 7, color: MUTED });
  return pdf.bytes();
}

/** Text split over at most two lines of the given width (the second shortened with … if needed). */
function wrap2(text, width, size) {
  const words = String(text).split(/\s+/);
  const lines = [''];
  for (const w of words) {
    const cur = lines[lines.length - 1];
    const next = cur ? `${cur} ${w}` : w;
    if (cur && textWidth(next, size, true) > width && lines.length < 2) lines.push(w);
    else lines[lines.length - 1] = next;
  }
  let last = lines[lines.length - 1];
  while (textWidth(last, size, true) > width && last.length > 1) last = `${last.slice(0, -2)}…`;
  lines[lines.length - 1] = last;
  return lines;
}

function legend(pdf, rightX, y, items) {
  let x = rightX;
  for (const [color, label] of [...items].reverse()) {
    const w = textWidth(label, 8);
    x -= w;
    pdf.text(x, y, label, { size: 8, color: INK2 });
    x -= 12;
    pdf.rect(x, y - 7, 8, 8, { fill: color });
    x -= 14;
  }
}

/** Columns per hour, direction 1 at the bottom, with a 1.5 pt gap between segments and between columns. */
function hourChart(pdf, x, y, w, h, perHour) {
  const bins = perHour.length ? perHour : [];
  const max = Math.max(1, ...bins.map((b) => b.dirs[1] + b.dirs[2]));
  const step = niceStep(max);
  const top = Math.ceil(max / step) * step;
  const axisW = 28;
  const px = x + axisW;
  const pw = w - axisW;
  for (let v = 0; v <= top; v += step) {
    const gy = y + h - (v / top) * h;
    pdf.line(px, gy, px + pw, gy, { color: v ? GRID : AXIS, width: v ? 0.4 : 0.6 });
    pdf.text(px - 5, gy + 3, fmt(v), { size: 7, color: MUTED, align: 'right' });
  }
  const n = Math.max(1, bins.length);
  const slot = pw / n;
  const bw = Math.min(18, Math.max(1.5, slot - 2));
  const every = Math.ceil(n / 24) * (n > 12 ? 2 : 1);
  bins.forEach((b, i) => {
    const bx = px + i * slot + (slot - bw) / 2;
    const h1 = (b.dirs[1] / top) * h;
    const h2 = (b.dirs[2] / top) * h;
    const gap = h1 > 0 && h2 > 0 ? 1.5 : 0;
    if (h1 > 0) pdf.column(bx, y + h - h1, bw, h1, DIR[0], h2 > 0 ? 0 : 2);
    if (h2 > 0) pdf.column(bx, y + h - h1 - gap - h2, bw, h2, DIR[1], 2);
    if (i % every === 0) pdf.text(bx + bw / 2, y + h + 11, hh(b.start), { size: 7, color: MUTED, align: 'center' });
  });
  if (!bins.some((b) => b.motor)) pdf.text(px + pw / 2, y + h / 2, 'No motor vehicles counted', { size: 9, color: MUTED, align: 'center' });
  pdf.text(px + pw, y + h + 22, 'hour of the day', { size: 7, color: MUTED, align: 'right' });
  return y + h + 22;
}

/** Speeds in 5 km/h bands; bands above the limit in red; the limit and 85th percentile marked. */
function speedChart(pdf, x, y, w, h, s) {
  const bins = s.histogram;
  const axisW = 28;
  const px = x + axisW;
  const pw = w - axisW;
  if (!bins.length) {
    pdf.text(px + pw / 2, y + h / 2, 'No speeds measured', { size: 9, color: MUTED, align: 'center' });
    return y + h + 12;
  }
  const lastBand = Math.max(bins[bins.length - 1].to, s.limit + 10);
  const used = bins.find((b) => b.n > 0);
  const firstBand = Math.max(0, Math.min(Math.floor((used ? used.from : 0) / 10) * 10 - 10, s.limit - 20));
  const bands = [];
  for (let v = firstBand; v < lastBand; v += 5) bands.push(bins.find((b) => b.from === v) || { from: v, to: v + 5, n: 0 });
  const max = Math.max(1, ...bands.map((b) => b.n));
  const step = niceStep(max);
  const top = Math.ceil(max / step) * step;
  for (let v = 0; v <= top; v += step) {
    const gy = y + h - (v / top) * h;
    pdf.line(px, gy, px + pw, gy, { color: v ? GRID : AXIS, width: v ? 0.4 : 0.6 });
    pdf.text(px - 5, gy + 3, fmt(v), { size: 7, color: MUTED, align: 'right' });
  }
  const slot = pw / bands.length;
  const bw = Math.min(18, Math.max(1.5, slot - 2));
  const xAt = (kmh) => px + ((kmh - firstBand) / (lastBand - firstBand)) * pw;
  bands.forEach((b, i) => {
    const bh = (b.n / top) * h;
    const bx = px + i * slot + (slot - bw) / 2;
    if (bh > 0) pdf.column(bx, y + h - bh, bw, bh, b.from >= s.limit ? OVER : WITHIN, 2);
    if (b.from % 10 === 0) pdf.text(px + i * slot, y + h + 11, String(b.from), { size: 7, color: MUTED, align: 'center' });
  });
  pdf.text(px + pw, y + h + 22, 'km/h', { size: 7, color: MUTED, align: 'right' });
  const lx = xAt(s.limit);
  pdf.line(lx, y - 4, lx, y + h, { color: INK2, width: 0.8, dash: [3, 2] });
  pdf.text(lx + 3, y + 3, `${s.limit} km/h limit`, { size: 7.5, color: INK2 });
  const sp = s.speeds.all;
  if (sp.n) {
    const qx = xAt(sp.p85);
    pdf.line(qx, y + 10, qx, y + h, { color: INK, width: 0.8 });
    const label = `85% at or under ${Math.round(sp.p85)}`;
    // Near the right edge, the label goes on the line's left.
    if (qx + 3 + textWidth(label, 7.5) > px + pw) pdf.text(qx - 3, y + 16, label, { size: 7.5, color: INK, align: 'right' });
    else pdf.text(qx + 3, y + 16, label, { size: 7.5, color: INK });
  }
  return y + h + 22;
}
