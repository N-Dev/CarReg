// Canvas drawing: plate boxes over the camera, photo and video, the debug layers, and the speed graph.

// Element sizes are cached from a ResizeObserver, so drawing every frame never forces a layout.
const sizes = new WeakMap();
const ro = typeof ResizeObserver === 'function'
  ? new ResizeObserver((entries) => {
    for (const e of entries) {
      const bb = e.borderBoxSize && e.borderBoxSize[0];
      sizes.set(e.target, bb ? { w: bb.inlineSize, h: bb.blockSize } : { w: e.contentRect.width, h: e.contentRect.height });
    }
  })
  : null;

/** Current size of an element ({ w, h }), measured once and then kept up to date by a ResizeObserver. */
export function sizeOf(el) {
  let s = sizes.get(el);
  if (!s) {
    s = { w: el.offsetWidth, h: el.offsetHeight };
    sizes.set(el, s);
    if (ro) ro.observe(el);
  }
  return s;
}

export function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

/** Sizes a canvas to its element at device resolution and clears it. */
export function fitCanvas(canvas, el) {
  const { w, h } = sizeOf(el);
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const cw = Math.max(1, Math.round(w * dpr));
  const ch = Math.max(1, Math.round(h * dpr));
  if (canvas.width !== cw || canvas.height !== ch) { canvas.width = cw; canvas.height = ch; }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}

/** Maps frame coordinates into an element showing the frame with object-fit cover/contain. */
export function mapper(w, h, fw, fh, mode) {
  const s = mode === 'cover' ? Math.max(w / fw, h / fh) : Math.min(w / fw, h / fh);
  const ox = (w - fw * s) / 2;
  const oy = (h - fh * s) / 2;
  return (b) => [ox + b[0] * s, oy + b[1] * s, ox + b[2] * s, oy + b[3] * s];
}

const FONT = 'Roboto, system-ui, sans-serif';

function pill(ctx, text, x, y, { bg, fg, font = `700 15px ${FONT}`, h = 28, pad = 11, maxW = Infinity, minTop = 0, below = null }) {
  ctx.font = font;
  const tw = ctx.measureText(text).width + pad * 2;
  const lx = Math.max(4, Math.min(x - tw / 2, maxW - tw - 4));
  let ly = y - h - 8;
  if (ly < minTop && below != null) ly = below + 8;
  ctx.fillStyle = bg;
  roundRect(ctx, lx, ly, tw, h, h / 2);
  ctx.fill();
  ctx.fillStyle = fg;
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'left';
  ctx.fillText(text, lx + pad, ly + h / 2 + 1);
}

export function drawPlateBox(ctx, [x1, y1, x2, y2], { color, label, sub = null, solid, alpha = 1, badge = null, minTop = 0, maxW = Infinity, dash = null }) {
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.lineWidth = 2.5;
  ctx.strokeStyle = color;
  ctx.shadowColor = color;
  ctx.shadowBlur = 14;
  if (dash) ctx.setLineDash(dash);
  roundRect(ctx, x1, y1, x2 - x1, y2 - y1, 6);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.shadowBlur = 0;
  if (badge != null) {
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x1, y1, 11, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#04221a';
    ctx.font = `800 12px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(badge), x1, y1 + 0.5);
    ctx.textAlign = 'left';
  }
  if (label) {
    pill(ctx, label, (x1 + x2) / 2, y1, {
      bg: solid ? color : 'rgba(12,15,21,0.84)', fg: solid ? '#04221a' : '#ffffff', maxW, minTop, below: y2,
    });
  }
  if (sub) {
    // Debug caption under the box: track id, frames read, agreement.
    ctx.font = `600 11px ui-monospace, 'Roboto Mono', monospace`;
    const tw = ctx.measureText(sub).width + 12;
    const lx = Math.max(4, Math.min((x1 + x2) / 2 - tw / 2, maxW - tw - 4));
    ctx.fillStyle = 'rgba(0,0,0,0.66)';
    roundRect(ctx, lx, y2 + 6, tw, 18, 6);
    ctx.fill();
    ctx.fillStyle = '#cfe7ff';
    ctx.textBaseline = 'middle';
    ctx.fillText(sub, lx + 6, y2 + 15.5);
  }
  ctx.restore();
}

// ---------------------------------------------------------------- debug layers
const MONO = `600 10.5px ui-monospace, 'Roboto Mono', monospace`;

/** The part of the frame the detector analysed (dashed), labelled with the model's input size. */
export function drawRegion(ctx, map, region, label, { minTop = 0, w = Infinity, h = Infinity } = {}) {
  const [x1, y1, x2, y2] = map([region.x, region.y, region.x + region.w, region.y + region.h]);
  ctx.save();
  ctx.strokeStyle = 'rgba(250, 204, 21, 0.75)';
  ctx.lineWidth = 1.5;
  ctx.setLineDash([7, 6]);
  ctx.strokeRect(x1 + 0.75, y1 + 0.75, x2 - x1 - 1.5, y2 - y1 - 1.5);
  ctx.setLineDash([]);
  if (label) {
    // Keep the label on screen even when the analysed area extends past the visible part of the frame.
    ctx.font = MONO;
    const tw = ctx.measureText(label).width + 10;
    const lx = Math.max(6, Math.min(x1 + 6, w - tw - 6));
    const ly = Math.max(minTop + 6, Math.min(y1 + 6, h - 24));
    ctx.fillStyle = 'rgba(250, 204, 21, 0.85)';
    ctx.fillRect(lx, ly, tw, 16);
    ctx.fillStyle = '#1a1300';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, lx + 5, ly + 8.5);
  }
  ctx.restore();
}

/** Every raw detection above 0.1 with its score: kept ones solid white, rejected ones dim red. */
export function drawRaw(ctx, map, raw, threshold) {
  ctx.save();
  ctx.font = MONO;
  ctx.textBaseline = 'bottom';
  for (const d of raw) {
    const [x1, y1, x2, y2] = map(d.box);
    const kept = d.score >= threshold;
    ctx.strokeStyle = kept ? 'rgba(255,255,255,0.55)' : 'rgba(248,113,113,0.7)';
    ctx.lineWidth = 1;
    ctx.setLineDash(kept ? [] : [3, 3]);
    ctx.strokeRect(x1, y1, x2 - x1, y2 - y1);
    ctx.setLineDash([]);
    ctx.fillStyle = kept ? 'rgba(255,255,255,0.8)' : 'rgba(248,113,113,0.9)';
    ctx.fillText(d.score.toFixed(2), x2 + 3, y2);
  }
  ctx.restore();
}

/** Deep-scan tiles used on a photo where no plate was found at first. */
export function drawTiles(ctx, map, tiles) {
  ctx.save();
  ctx.strokeStyle = 'rgba(96,165,250,0.55)';
  ctx.setLineDash([4, 5]);
  for (const t of tiles) {
    const [x1, y1, x2, y2] = map(t);
    ctx.strokeRect(x1 + 1, y1 + 1, x2 - x1 - 2, y2 - y1 - 2);
  }
  ctx.restore();
}

// ---------------------------------------------------------------- speed graph
export const TIER_COLORS = { fast: '#60a5fa', balanced: '#6ee7b7', sharp: '#c084fc' };

/**
 * Speed graph for the HUD: one bar per analysed frame (height = analysis time, colour = quality tier,
 * dimmed while idle), with the frame-time budget as a dashed line.
 */
export function drawSpark(canvas, samples, { budget = 85, span = 90 } = {}) {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const size = sizeOf(canvas);
  const w = size.w || 180;
  const h = size.h || 36;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const top = Math.max(budget * 1.6, ...samples.map((s) => s.ms));
  const bw = w / span;
  const list = samples.slice(-span);
  const x0 = w - list.length * bw;
  list.forEach((s, i) => {
    const bh = Math.max(1, (s.ms / top) * (h - 2));
    ctx.globalAlpha = s.idle ? 0.35 : 0.95;
    ctx.fillStyle = s.ms > budget * 1.15 ? '#f87171' : TIER_COLORS[s.tier] || '#60a5fa';
    ctx.fillRect(x0 + i * bw, h - bh, Math.max(1, bw - 0.6), bh);
  });
  ctx.globalAlpha = 1;
  const by = h - (budget / top) * (h - 2);
  ctx.strokeStyle = 'rgba(255,255,255,0.45)';
  ctx.setLineDash([3, 3]);
  ctx.beginPath();
  ctx.moveTo(0, by);
  ctx.lineTo(w, by);
  ctx.stroke();
  ctx.setLineDash([]);
}
