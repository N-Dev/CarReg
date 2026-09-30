// Charts on the results screen, drawn on canvas: motor vehicles per hour (stacked by direction) and
// the speed distribution. Thin columns with rounded tops, a 2 px gap between touching marks, hairline
// grid, and a tap/hover tooltip on every column. Colours were checked for colour-blind separation.
export const COLORS = {
  dir: ['#3987e5', '#d95926'],
  within: '#3987e5',
  over: '#d03b3b',
  text: '#eef1f7',
  muted: '#8f98ad',
  grid: 'rgba(255,255,255,0.07)',
  axis: 'rgba(255,255,255,0.18)',
  surface: '#11151d',
};

const pad2 = (n) => String(n).padStart(2, '0');
const hourLabel = (t) => `${pad2(new Date(t).getHours())}:00`;

function niceStep(max, ticks = 4) {
  const raw = Math.max(1, max) / ticks;
  const p = 10 ** Math.floor(Math.log10(raw));
  return Math.max(1, [1, 2, 5, 10].map((m) => m * p).find((s) => s >= raw)); // whole numbers: these are counts
}

function setup(canvas, height) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || canvas.parentElement.clientWidth || 320;
  canvas.style.height = `${height}px`;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(height * dpr);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, height);
  ctx.font = '11px Roboto, system-ui, sans-serif';
  return { ctx, w, h: height };
}

/** A column from the baseline (y + h) up, top corners rounded. */
function column(ctx, x, y, w, h, color, round = true) {
  if (h <= 0.5) return;
  const r = round ? Math.min(4, w / 2, h) : 0;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x, y + h);
  ctx.lineTo(x, y + r);
  if (r) ctx.arcTo(x, y, x + r, y, r);
  ctx.lineTo(x + w - r, y);
  if (r) ctx.arcTo(x + w, y, x + w, y + r, r);
  ctx.lineTo(x + w, y + h);
  ctx.closePath();
  ctx.fill();
}

function axes(ctx, left, top, width, height, max) {
  const step = niceStep(max);
  const vmax = Math.ceil(max / step) * step || step;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let v = 0; v <= vmax; v += step) {
    const y = Math.round(top + height - (v / vmax) * height) + 0.5;
    ctx.strokeStyle = v ? COLORS.grid : COLORS.axis;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(left, y);
    ctx.lineTo(left + width, y);
    ctx.stroke();
    ctx.fillStyle = COLORS.muted;
    ctx.fillText(v.toLocaleString('en-IE'), left - 6, y);
  }
  return vmax;
}

/** Wires a tooltip: hits(x) returns { title, rows: [[value, label]] } or null for the pointer's x. */
function tooltip(canvas, tip, hits) {
  const show = (e) => {
    const r = canvas.getBoundingClientRect();
    const h = hits(e.clientX - r.left);
    if (!h) { tip.hidden = true; return; }
    tip.replaceChildren();
    const t = document.createElement('div');
    t.className = 'tip__title';
    t.textContent = h.title;
    tip.append(t);
    for (const [value, label, color] of h.rows) {
      const row = document.createElement('div');
      row.className = 'tip__row';
      const key = document.createElement('i');
      key.style.background = color;
      const b = document.createElement('b');
      b.textContent = value;
      const s = document.createElement('span');
      s.textContent = label;
      row.append(key, b, s);
      tip.append(row);
    }
    tip.hidden = false;
    const x = Math.min(Math.max(8, e.clientX - r.left - tip.offsetWidth / 2), r.width - tip.offsetWidth - 8);
    tip.style.left = `${x}px`;
    tip.style.top = '0px';
  };
  canvas.onpointermove = show;
  canvas.onpointerdown = show;
  canvas.onpointerleave = () => { tip.hidden = true; };
}

/** Motor vehicles per hour, direction 1 at the bottom of each column. */
export function drawHourChart(canvas, tip, perHour, dirNames) {
  const { ctx, w, h } = setup(canvas, 190);
  const left = 34;
  const top = 10;
  const bottom = 24;
  const pw = w - left - 6;
  const ph = h - top - bottom;
  const max = Math.max(1, ...perHour.map((b) => b.dirs[1] + b.dirs[2]));
  const vmax = axes(ctx, left, top, pw, ph, max);
  const n = Math.max(1, perHour.length);
  const slot = pw / n;
  const bw = Math.max(2, Math.min(24, slot - 2));
  const every = n <= 8 ? 1 : n <= 16 ? 2 : n <= 32 ? 4 : 6;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  perHour.forEach((b, i) => {
    const x = left + i * slot + (slot - bw) / 2;
    const h1 = (b.dirs[1] / vmax) * ph;
    const h2 = (b.dirs[2] / vmax) * ph;
    const gap = h1 > 0 && h2 > 0 ? 2 : 0;
    column(ctx, x, top + ph - h1, bw, h1, COLORS.dir[0], h2 <= 0);
    column(ctx, x, top + ph - h1 - gap - h2, bw, h2, COLORS.dir[1]);
    if (i % every === 0) { ctx.fillStyle = COLORS.muted; ctx.fillText(hourLabel(b.start).slice(0, 2), x + bw / 2, top + ph + 6); }
  });
  tooltip(canvas, tip, (px) => {
    const i = Math.floor((px - left) / slot);
    const b = perHour[i];
    if (!b) return null;
    const end = new Date(b.start + 3600000);
    return {
      title: `${hourLabel(b.start)} to ${pad2(end.getHours())}:00`,
      rows: [[String(b.dirs[1]), dirNames[0], COLORS.dir[0]], [String(b.dirs[2]), dirNames[1], COLORS.dir[1]]],
    };
  });
}

/** Speeds in 5 km/h bands, bands above the limit in red, with the limit and the 85th percentile marked. */
export function drawSpeedChart(canvas, tip, s) {
  const { ctx, w, h } = setup(canvas, 170);
  const left = 34;
  const top = 18;
  const bottom = 24;
  const pw = w - left - 6;
  const ph = h - top - bottom;
  const bins = s.histogram;
  if (!bins.length) {
    ctx.fillStyle = COLORS.muted;
    ctx.textAlign = 'center';
    ctx.fillText('No speeds measured yet', w / 2, h / 2);
    canvas.onpointermove = null;
    return;
  }
  const lastBand = Math.max(bins[bins.length - 1].to, s.limit + 10);
  const used = bins.find((b) => b.n > 0);
  const firstBand = Math.max(0, Math.min(Math.floor((used ? used.from : 0) / 10) * 10 - 10, s.limit - 20));
  const bands = [];
  for (let v = firstBand; v < lastBand; v += 5) bands.push(bins.find((b) => b.from === v) || { from: v, to: v + 5, n: 0 });
  const vmax = axes(ctx, left, top, pw, ph, Math.max(1, ...bands.map((b) => b.n)));
  const slot = pw / bands.length;
  const bw = Math.max(2, Math.min(24, slot - 2));
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  bands.forEach((b, i) => {
    const x = left + i * slot + (slot - bw) / 2;
    column(ctx, x, top + ph - (b.n / vmax) * ph, bw, (b.n / vmax) * ph, b.from >= s.limit ? COLORS.over : COLORS.within);
    if (b.from % (bands.length > 16 ? 20 : 10) === 0) { ctx.fillStyle = COLORS.muted; ctx.fillText(String(b.from), left + i * slot, top + ph + 6); }
  });
  const xAt = (kmh) => left + ((kmh - firstBand) / (lastBand - firstBand)) * pw;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
  ctx.strokeStyle = COLORS.muted;
  ctx.setLineDash([4, 3]);
  ctx.beginPath();
  ctx.moveTo(Math.round(xAt(s.limit)) + 0.5, top - 6);
  ctx.lineTo(Math.round(xAt(s.limit)) + 0.5, top + ph);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = COLORS.muted;
  ctx.fillText(`${s.limit} limit`, xAt(s.limit) + 4, top + 2);
  const p85 = s.speeds.all.p85;
  if (p85 != null) {
    ctx.strokeStyle = COLORS.text;
    ctx.beginPath();
    ctx.moveTo(Math.round(xAt(p85)) + 0.5, top + 10);
    ctx.lineTo(Math.round(xAt(p85)) + 0.5, top + ph);
    ctx.stroke();
    ctx.fillStyle = COLORS.text;
    const label = `85%: ${Math.round(p85)}`;
    const lx = xAt(p85) + 4 + ctx.measureText(label).width > w ? xAt(p85) - 4 - ctx.measureText(label).width : xAt(p85) + 4;
    ctx.fillText(label, lx, top + 16);
  }
  tooltip(canvas, tip, (px) => {
    const b = bands[Math.floor((px - left) / slot)];
    if (!b) return null;
    return { title: `${b.from} to ${b.to} km/h`, rows: [[String(b.n), b.n === 1 ? 'vehicle' : 'vehicles', b.from >= s.limit ? COLORS.over : COLORS.within]] };
  });
}
