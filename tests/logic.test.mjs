// Run with: node --test tests/
import test from 'node:test';
import assert from 'node:assert/strict';
import { validate, validateIE, validateUK, decodeIE, decodeUK, profileFor, flagFor, bandFor } from '../js/formats.js';
import { vote, Tracker } from '../js/tracker.js';

test('Irish plates: formatting, confusions and decoding', () => {
  assert.equal(validateIE('241D12345').text, '241-D-12345');
  assert.equal(validateIE('24lD12345').text, '241-D-12345');     // l -> 1 in the year
  assert.equal(validateIE('24lDl2345').text, '241-DL-2345');     // ambiguous: fewest changes wins (Donegal)
  assert.equal(validateIE('24lD1O345').text, '241-D-10345');     // O -> 0 in the number
  assert.equal(validateIE('191KE123').text, '191-KE-123');
  assert.equal(validateIE('06D12345').text, '06-D-12345');       // pre-2013 two-digit year
  assert.equal(validateIE('131CE7').text, '131-CE-7');
  assert.equal(validateIE('24ID12345').valid, true);              // I read as 1 in the year
  assert.equal(validateIE('ABC123').valid, false);
  assert.equal(validateIE('243D12345').valid, false);             // half-year must be 1 or 2
  assert.equal(validateIE('241XX123').valid, false);              // not a county
  const d = decodeIE('241D12345');
  assert.deepEqual([d.year, d.period, d.county, d.countyGa], [2024, 'Jan–Jun', 'Dublin', 'Baile Átha Cliath']);
  assert.equal(decodeIE('06D12345').year, 2006);
});

test('UK and Northern Ireland plates', () => {
  assert.equal(validateUK('AB12CDE').text, 'AB12 CDE');
  assert.equal(validateUK('A812CDE').text, 'AB12 CDE');           // 8 -> B in a letter slot
  assert.equal(validateUK('AB1ZCDE').text, 'AB12 CDE');           // Z -> 2 in a digit slot
  assert.equal(validateUK('AIZ1234').text, 'AIZ 1234');           // NI
  assert.equal(validateUK('LAZ12').valid, true);
  assert.equal(validateUK('IA1234').valid, true);                 // 2-letter NI code
  assert.equal(validateUK('ABC1234').valid, false);               // not an NI code, not GB
  assert.equal(validateUK('A123BCD').text, 'A123 BCD');           // prefix era (AI23 BCD is not a legal current mark)
  assert.equal(validateUK('ZZ12CDE').valid, false);               // Z is never an area letter
  const d = decodeUK('AB62CDE');
  assert.deepEqual([d.area, d.year, d.period], ['Anglia', 2012, 'Sep–Feb']);
  assert.equal(decodeUK('LAZ12').area, 'Northern Ireland');
});

test('profiles and flags', () => {
  assert.equal(profileFor('auto', 'Ireland'), 'IE');
  assert.equal(profileFor('auto', 'United Kingdom'), 'UK');
  assert.equal(profileFor('auto', 'Czech Republic'), 'ANY');
  assert.equal(profileFor('UK', 'Ireland'), 'UK');
  assert.equal(validate('5AU5341', 'ANY').valid, true);
  assert.equal(flagFor('Ireland'), '🇮🇪');
  assert.equal(bandFor('Ireland'), 'IRL');
  assert.equal(bandFor('United Kingdom'), 'UK');
});

const R = (text, conf = 0.95, region = 'Ireland', regionProb = 0.99) => ({ text, conf, region, regionProb });

test('voting fixes noisy frames and picks the country', () => {
  const r = vote([R('241D12345'), R('241D12845', 0.7), R('241D12345'), R('24lD12345', 0.8), R('241012345', 0.6), R('241D12345')]);
  assert.equal(r.text, '241-D-12345');
  assert.equal(r.valid, true);
  assert.equal(r.region, 'Ireland');
  assert.ok(r.conf > 0.5, `conf ${r.conf}`);
  assert.equal(r.info.county, 'Dublin');
});

test('voting ignores low-confidence reads and "Unknown" regions', () => {
  assert.equal(vote([R('ABC', 0.1)]), null);
  const r = vote([R('AB12CDE', 0.9, 'Unknown', 0.9), R('AB12CDE', 0.9, 'United Kingdom', 0.6)]);
  assert.equal(r.region, 'United Kingdom');
  assert.equal(r.text, 'AB12 CDE');
});

test('tracker keeps one identity per moving plate and confirms after agreement', () => {
  const tr = new Tracker({ format: 'auto' });
  let confirmed = [];
  for (let f = 0; f < 5; f++) {
    const x = 100 + f * 12;                       // plate drifting right
    const out = tr.update([
      { box: [x, 200, x + 120, 230], score: 0.9, read: R('241D12345') },
      { box: [600, 300, 700, 325], score: 0.8, read: R('AB12CDE', 0.9, 'United Kingdom', 0.9) },
    ], f * 100);
    confirmed = confirmed.concat(out.confirmed);
  }
  assert.equal(tr.tracks.length, 2);
  assert.deepEqual(confirmed.map((t) => t.result.text).sort(), ['241-D-12345', 'AB12 CDE']);
  const lost = tr.update([], 5000).lost;
  assert.equal(lost.length, 2);
  assert.equal(tr.tracks.length, 0);
});

test('single read is never confirmed on its own', () => {
  const tr = new Tracker();
  const out = tr.update([{ box: [0, 0, 100, 25], score: 0.9, read: R('241D12345') }], 0);
  assert.equal(out.confirmed.length, 0);
});

// ---------------------------------------------------------------- moving from one car to the next
import { editDistance, similar } from '../js/tracker.js';

test('edit distance and plate similarity', () => {
  assert.equal(editDistance('12D15405', '12D15405'), 0);
  assert.equal(editDistance('12D15405', '12D15406'), 1);
  assert.ok(similar('241D12345', '241D12845'));      // one OCR slip: same plate
  assert.ok(!similar('12D15405', '201D8573'));       // different plates
});

test('votes never blend two different plates into a third', () => {
  const reads = [
    ...Array(6).fill(0).map(() => R('12D15405')),
    ...Array(4).fill(0).map(() => R('201D8573')),
  ];
  const r = vote(reads);
  assert.equal(r.text, '12-D-15405');
  assert.ok(r.conf < 0.7, `mixed reads must lower agreement, got ${r.conf}`);
});

test('a different plate in the same spot starts a new track and ends the old one', () => {
  const tr = new Tracker({ format: 'auto' });
  const box = [300, 600, 520, 660];
  const thumbA = { id: 'A', close() {} };
  const thumbB = { id: 'B', close() {} };
  let t = 0;
  const confirmed = [];
  const lost = [];
  for (let f = 0; f < 4; f++, t += 100) {
    const out = tr.update([{ box, score: 0.9, read: R('12D15405'), thumb: f === 1 ? thumbA : null }], t);
    confirmed.push(...out.confirmed); lost.push(...out.lost);
  }
  // User re-centres on the next car: same position, different plate.
  for (let f = 0; f < 4; f++, t += 100) {
    const out = tr.update([{ box, score: 0.9, read: R('201D8573'), thumb: f === 1 ? thumbB : null }], t);
    confirmed.push(...out.confirmed); lost.push(...out.lost);
  }
  const texts = confirmed.map((x) => x.result.text);
  assert.deepEqual(texts, ['12-D-15405', '201-D-8573']);
  assert.equal(lost.length, 1, 'first car is finished as soon as the second plate replaces it');
  assert.equal(lost[0].result.text, '12-D-15405');
  assert.equal(lost[0].result.conf, 1, 'no reads of the second car leaked into the first');
  assert.equal(lost[0].thumbFor(lost[0].result.key).bmp.id, 'A');
  assert.equal(tr.tracks[0].thumbFor(tr.tracks[0].result.key).bmp.id, 'B');
});

test('an unsure read does not split a track', () => {
  const tr = new Tracker();
  const box = [0, 0, 200, 50];
  for (let f = 0; f < 3; f++) tr.update([{ box, score: 0.9, read: R('12D15405') }], f * 100);
  tr.update([{ box, score: 0.9, read: R('201D8573', 0.5) }], 400);   // blurry frame, low confidence
  assert.equal(tr.tracks.length, 1);
  assert.equal(tr.tracks[0].result.text, '12-D-15405');
});

// ---------------------------------------------------------------- plates cut off by the frame edge
const P = (text, region = 'United Kingdom') => ({ ...R(text, 0.95, region), partial: true });

test('a plate entering the frame cut off is read as one plate once fully visible', () => {
  const tr = new Tracker();
  const confirmed = [];
  const box = (x) => [x, 300, x + 200, 350];
  let t = 0;
  for (const read of [P('AB12'), P('AB12'), P('AB12C')]) confirmed.push(...tr.update([{ box: box(700 - t / 10), score: 0.9, read }], t += 100).confirmed);
  assert.equal(confirmed.length, 0, 'never confirmed from partial reads');
  for (let f = 0; f < 3; f++) confirmed.push(...tr.update([{ box: box(690 - t / 10), score: 0.9, read: R('AB12CDE', 0.95, 'United Kingdom') }], t += 100).confirmed);
  assert.equal(tr.tracks.length, 1);
  assert.deepEqual(confirmed.map((x) => x.result.text), ['AB12 CDE']);
});

test('a plate leaving the frame keeps its full reading', () => {
  const tr = new Tracker();
  const box = [0, 300, 200, 350];
  for (let f = 0; f < 3; f++) tr.update([{ box, score: 0.9, read: R('241D12345') }], f * 100);
  tr.update([{ box: [0, 300, 120, 350], score: 0.8, read: P('D12345', 'Denmark') }], 300);
  tr.update([{ box: [0, 300, 90, 350], score: 0.8, read: P('12345', 'Denmark') }], 400);
  assert.equal(tr.tracks.length, 1);
  assert.equal(tr.tracks[0].result.text, '241-D-12345');
});

test('a plate only ever seen cut off is never confirmed', () => {
  const tr = new Tracker({ format: 'ANY' });
  const out = [];
  for (let f = 0; f < 6; f++) out.push(...tr.update([{ box: [0, 0, 90, 40], score: 0.9, read: P('D12345', 'Denmark') }], f * 100).confirmed);
  out.push(...tr.flush().filter((x) => x.confirmed));
  assert.equal(out.length, 0);
});
