// A small PDF writer: one or more A4 pages of text, lines and rectangles in the standard Helvetica
// fonts, which every PDF reader has built in (so nothing is embedded and it works offline).
// Coordinates are points from the top-left corner. Pure logic, unit-tested.

export const A4 = { w: 595.28, h: 841.89 };

// Character widths (1/1000 em) for ASCII 32..126, from the standard Helvetica font metrics.
const W_REG = [278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
  278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722,
  667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556,
  333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584];
const W_BOLD = [278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
  333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722,
  667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611,
  389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584];

// Characters outside ASCII that WinAnsiEncoding has, with their byte and an approximate width.
const EXTRA = {
  '–': [0x96, 556], '—': [0x97, 1000], '‘': [0x91, 222], '’': [0x92, 222], '“': [0x93, 333], '”': [0x94, 333],
  '•': [0x95, 350], '…': [0x85, 1000], '€': [0x80, 556], '·': [0xb7, 278], '°': [0xb0, 400], '×': [0xd7, 584], '±': [0xb1, 584],
};

/** WinAnsi byte and width for one character (accented Latin letters take their base letter's width). */
function glyph(ch, bold) {
  const W = bold ? W_BOLD : W_REG;
  const c = ch.charCodeAt(0);
  if (c >= 32 && c <= 126) return [c, W[c - 32]];
  if (EXTRA[ch]) return EXTRA[ch];
  if (c >= 0xa0 && c <= 0xff) {
    const base = ch.normalize('NFD')[0];
    const b = base.charCodeAt(0);
    return [c, b >= 32 && b <= 126 ? W[b - 32] : 556];
  }
  return [63, W[63 - 32]]; // '?'
}

/** Width of a string in points at a font size. */
export function textWidth(str, size, bold = false) {
  let w = 0;
  for (const ch of String(str)) w += glyph(ch, bold)[1];
  return (w * size) / 1000;
}

/** A PDF string literal: printable ASCII as is, everything else as octal escapes. */
function pdfString(str, bold) {
  let out = '(';
  for (const ch of String(str)) {
    const [b] = glyph(ch, bold);
    if (b === 40 || b === 41 || b === 92) out += `\\${String.fromCharCode(b)}`;
    else if (b >= 32 && b <= 126) out += String.fromCharCode(b);
    else out += `\\${b.toString(8).padStart(3, '0')}`;
  }
  return `${out})`;
}

const num = (v) => (Math.round(v * 100) / 100).toString();
const rgb = (c) => {
  const h = c.replace('#', '');
  return [0, 2, 4].map((i) => num(parseInt(h.slice(i, i + 2), 16) / 255)).join(' ');
};

export class Pdf {
  constructor({ title = '', author = '' } = {}) {
    this.title = title;
    this.author = author;
    this.pages = [];
    this.addPage();
  }

  addPage() {
    this.ops = [];
    this.pages.push(this.ops);
    return this;
  }

  /** Text at (x, y = baseline). align: left | right | center (x is then the right edge or the middle). */
  text(x, y, str, { size = 10, bold = false, color = '#111111', align = 'left' } = {}) {
    const s = String(str);
    let tx = x;
    if (align !== 'left') { const w = textWidth(s, size, bold); tx = align === 'right' ? x - w : x - w / 2; }
    this.ops.push(`BT /${bold ? 'F2' : 'F1'} ${num(size)} Tf ${rgb(color)} rg ${num(tx)} ${num(A4.h - y)} Td ${pdfString(s, bold)} Tj ET`);
    return this;
  }

  /** Text wrapped to a width; returns the y of the line after the last. */
  paragraph(x, y, str, width, { size = 9, lead = 1.35, ...opts } = {}) {
    const words = String(str).split(/\s+/);
    let line = '';
    let yy = y;
    for (const w of words) {
      const next = line ? `${line} ${w}` : w;
      if (line && textWidth(next, size, opts.bold) > width) {
        this.text(x, yy, line, { size, ...opts });
        yy += size * lead;
        line = w;
      } else line = next;
    }
    if (line) { this.text(x, yy, line, { size, ...opts }); yy += size * lead; }
    return yy;
  }

  rect(x, y, w, h, { fill = null, stroke = null, width = 1 } = {}) {
    const parts = [];
    if (fill) parts.push(`${rgb(fill)} rg`);
    if (stroke) parts.push(`${rgb(stroke)} RG ${num(width)} w`);
    parts.push(`${num(x)} ${num(A4.h - y - h)} ${num(w)} ${num(h)} re ${fill && stroke ? 'B' : fill ? 'f' : 'S'}`);
    this.ops.push(`q ${parts.join(' ')} Q`);
    return this;
  }

  /** A column rising from a baseline at y + h, with its top corners rounded by r. */
  column(x, y, w, h, fill, r = 2) {
    if (h <= 0 || w <= 0) return this;
    const rr = Math.min(r, w / 2, h);
    const X0 = x;
    const X1 = x + w;
    const Yb = A4.h - (y + h); // baseline (PDF y goes up)
    const Yt = A4.h - y;
    const k = 0.5523 * rr;
    this.ops.push(`q ${rgb(fill)} rg ${num(X0)} ${num(Yb)} m ${num(X0)} ${num(Yt - rr)} l `
      + `${num(X0)} ${num(Yt - rr + k)} ${num(X0 + rr - k)} ${num(Yt)} ${num(X0 + rr)} ${num(Yt)} c ${num(X1 - rr)} ${num(Yt)} l `
      + `${num(X1 - rr + k)} ${num(Yt)} ${num(X1)} ${num(Yt - rr + k)} ${num(X1)} ${num(Yt - rr)} c ${num(X1)} ${num(Yb)} l h f Q`);
    return this;
  }

  line(x1, y1, x2, y2, { color = '#999999', width = 0.5, dash = null } = {}) {
    this.ops.push(`q ${rgb(color)} RG ${num(width)} w ${dash ? `[${dash.join(' ')}] 0 d ` : ''}${num(x1)} ${num(A4.h - y1)} m ${num(x2)} ${num(A4.h - y2)} l S Q`);
    return this;
  }

  /** The finished file as bytes. */
  bytes() {
    const objs = [];
    const add = (s) => { objs.push(s); return objs.length; };
    const catalog = add(null);
    const pagesObj = add(null);
    const f1 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');
    const f2 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>');
    const kids = [];
    for (const ops of this.pages) {
      const content = ops.join('\n');
      const c = add(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
      kids.push(add(`<< /Type /Page /Parent ${pagesObj} 0 R /MediaBox [0 0 ${A4.w} ${A4.h}] /Resources << /Font << /F1 ${f1} 0 R /F2 ${f2} 0 R >> >> /Contents ${c} 0 R >>`));
    }
    objs[catalog - 1] = `<< /Type /Catalog /Pages ${pagesObj} 0 R >>`;
    objs[pagesObj - 1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(' ')}] /Count ${kids.length} >>`;
    const d = new Date();
    const stamp = `D:${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}`
      + `${String(d.getUTCHours()).padStart(2, '0')}${String(d.getUTCMinutes()).padStart(2, '0')}${String(d.getUTCSeconds()).padStart(2, '0')}Z`;
    const info = add(`<< /Title ${pdfString(this.title)} /Author ${pdfString(this.author)} /Producer (TrafficSight) /CreationDate (${stamp}) >>`);

    let out = '%PDF-1.4\n%âãÏÓ\n';
    const offsets = [];
    objs.forEach((o, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
    const xref = out.length;
    out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
    for (const off of offsets) out += `${String(off).padStart(10, '0')} 00000 n \n`;
    out += `trailer\n<< /Size ${objs.length + 1} /Root ${catalog} 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
    const bytes = new Uint8Array(out.length);
    for (let i = 0; i < out.length; i++) bytes[i] = out.charCodeAt(i) & 0xff;
    return bytes;
  }
}
