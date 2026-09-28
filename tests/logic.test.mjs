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
