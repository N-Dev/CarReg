// Field testing: reads the user marks right or wrong become an accuracy score and a training set
// in the format fast-plate-ocr's training tools expect. Pure functions here are unit-tested.
import { clean } from './formats.js';
import { editDistance } from './tracker.js';

/** Accuracy over labelled samples: exact-plate rate, character-level accuracy, per source, recent misses. */
export function accuracyStats(list) {
  const n = list.length;
  const bySource = {};
  let exact = 0;
  let chars = 0;
  let charTotal = 0;
  for (const s of list) {
    const truth = clean(s.truth);
    const pred = clean(s.predicted);
    const ok = truth === pred;
    if (ok) exact++;
    charTotal += truth.length;
    chars += Math.max(0, truth.length - editDistance(pred, truth));
    const b = bySource[s.source] || (bySource[s.source] = { n: 0, exact: 0 });
    b.n++;
    if (ok) b.exact++;
  }
  return {
    n,
    exact,
    exactRate: n ? exact / n : 0,
    charRate: charTotal ? chars / charTotal : 0,
    bySource,
    misses: list.filter((s) => clean(s.truth) !== clean(s.predicted)).slice(-12).reverse(),
  };
}

/**
 * Threshold tuning: if PlateSight only reported plates at or above each confidence threshold, how
 * many plates would it report (coverage) and how many of those would be right (accuracy)?
 */
export function thresholdCurve(list, thresholds = [0, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95]) {
  return thresholds.map((t) => {
    const kept = list.filter((s) => (s.conf || 0) >= t);
    const right = kept.filter((s) => clean(s.truth) === clean(s.predicted)).length;
    return { t, kept: kept.length, coverage: list.length ? kept.length / list.length : 0, accuracy: kept.length ? right / kept.length : 0 };
  });
}

/**
 * Lowest threshold whose reported plates are at least `target` accurate while still reporting at
 * least `minKept` plates. Null when there's too little data or no threshold gets there.
 */
export function suggestThreshold(list, { target = 0.95, minSamples = 20, minKept = 10 } = {}) {
  if (list.length < minSamples) return null;
  const curve = thresholdCurve(list, Array.from({ length: 20 }, (_, i) => i * 0.05));
  const ok = curve.find((c) => c.kept >= minKept && c.accuracy >= target);
  return ok ? { ...ok, target } : null;
}

/** Character alignment between what was read and the truth: [{ op: same|sub|ins|del, a, b }]. */
export function alignOps(read, truth) {
  const a = clean(read);
  const b = clean(truth);
  const m = a.length;
  const n = b.length;
  const d = Array.from({ length: m + 1 }, (_, i) => [i, ...new Array(n).fill(0)]);
  for (let j = 1; j <= n; j++) d[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  }
  const ops = [];
  let i = m;
  let j = n;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && d[i][j] === d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)) {
      ops.push({ op: a[i - 1] === b[j - 1] ? 'same' : 'sub', a: a[i - 1], b: b[j - 1] });
      i--; j--;
    } else if (i > 0 && d[i][j] === d[i - 1][j] + 1) {
      ops.push({ op: 'del', a: a[i - 1], b: '' }); // read a character that isn't there
      i--;
    } else {
      ops.push({ op: 'ins', a: '', b: b[j - 1] }); // missed a character
      j--;
    }
  }
  return ops.reverse();
}

/** Most frequent character confusions (truth -> read) across wrong samples, e.g. 8 read as B. */
export function confusions(list, top = 6) {
  const counts = new Map();
  for (const s of list) {
    for (const o of alignOps(s.predicted, s.truth)) {
      if (o.op === 'same') continue;
      const k = `${o.b || '∅'}→${o.a || '∅'}`;
      counts.set(k, (counts.get(k) || 0) + 1);
    }
  }
  return [...counts.entries()].sort((x, y) => y[1] - x[1]).slice(0, top).map(([pair, n]) => {
    const [truth, read] = pair.split('→');
    return { truth, read, n };
  });
}

const csvCell = (v) => {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};
const csv = (rows) => `${rows.map((r) => r.map(csvCell).join(',')).join('\n')}\n`;

/**
 * Lays samples out as a fast-plate-ocr dataset: train/ and val/ (every 7th image, ~15%), each with
 * annotations.csv (image_path, plate_text, plate_region) and images/, plus predictions.csv (every
 * labelled sample, with what PlateSight read) and a README. `images` maps sample id -> JPEG bytes of
 * the tight plate crop; samples without one (labelled from history) are only in predictions.csv.
 * Returns [{ name, data }] ready for zip().
 */
export function datasetFiles(list, images, stamp) {
  const root = `platesight-dataset-${stamp}`;
  const files = [];
  const head = ['image_path', 'plate_text', 'plate_region'];
  const rows = { train: [head], val: [head] };
  const preds = [['image', 'split', 'plate_text', 'app_read', 'correct', 'confidence', 'source', 'region', 'model', 'time']];
  let k = 0;
  for (const s of list) {
    const bytes = images.get(s.id);
    let path = '';
    let split = 'not exported (no plate crop)';
    if (bytes) {
      split = k % 7 === 6 ? 'val' : 'train';
      const name = `${String(k + 1).padStart(5, '0')}.jpg`;
      k++;
      path = `${split}/images/${name}`;
      files.push({ name: `${root}/${path}`, data: bytes });
      rows[split].push([`images/${name}`, clean(s.truth), s.region || 'Unknown']);
    }
    preds.push([path, split, clean(s.truth), clean(s.predicted), clean(s.truth) === clean(s.predicted),
      (s.conf || 0).toFixed(3), s.source, s.region || '', s.model || '', new Date(s.time).toISOString()]);
  }
  files.push({ name: `${root}/train/annotations.csv`, data: csv(rows.train) });
  files.push({ name: `${root}/val/annotations.csv`, data: csv(rows.val) });
  files.push({ name: `${root}/predictions.csv`, data: csv(preds) });
  files.push({ name: `${root}/README.txt`, data: README(k, list.length) });
  return files;
}

const README = (n, total) => `PlateSight field-test dataset: ${n} plate images (${total} labelled plates in all)

train/ and val/ follow the dataset format of fast-plate-ocr (https://github.com/ankandrew/fast-plate-ocr),
the library behind PlateSight's plate readers:
  annotations.csv  image_path,plate_text,plate_region   (image paths are relative to the CSV)
  images/          tight plate crops, as the app saw them

predictions.csv lists every labelled plate with what PlateSight read, so you can see where it goes wrong.
Plates labelled from History have no crop, so they appear only there.

Fine-tuning a plate reader on these (Python; a GPU helps):
  1. pip install "fast-plate-ocr[train]"
  2. From the fast-plate-ocr releases, download the model config, plate config and pre-trained .keras
     weights for cct-s-v2-global (PlateSight's accurate reader) or cct-xs-v2-global (its fast reader).
  3. KERAS_BACKEND=tensorflow fast-plate-ocr train \\
       --model-config-file cct_s_v2_global_model_config.yaml \\
       --plate-config-file cct_s_v2_global_plate_config.yaml \\
       --annotations train/annotations.csv \\
       --val-annotations val/annotations.csv \\
       --weights-path <pre-trained weights>.keras \\
       --epochs 30 --batch-size 32 --output-dir trained/
  4. fast-plate-ocr export --model trained/best.keras \\
       --plate-config-file cct_s_v2_global_plate_config.yaml --format onnx
  5. Replace models/plate-ocr-accurate.onnx in PlateSight with the exported model and update its size
     and revision in js/config.js (the unit tests print the right values).

A few hundred labelled plates is a sensible minimum. Keep collecting across daylight, night, rain and
angles, and keep some plates back to check the new reader really beats the old one.
`;
