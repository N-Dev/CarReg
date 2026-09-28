// Plate text normalisation, per-country validation and decoding.
// Pure functions: no DOM access, so they run in Node tests and in the browser.

const TO_DIGIT = { O: '0', Q: '0', D: '0', I: '1', L: '1', Z: '2', S: '5', G: '6', B: '8' };
const TO_LETTER = { 0: 'O', 1: 'I', 2: 'Z', 5: 'S', 6: 'G', 8: 'B' };

export const clean = (raw) => String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const fix = (s, table) => [...s].map((c) => table[c] ?? c).join('');
const digits = (s) => fix(s, TO_DIGIT);
const letters = (s) => fix(s, TO_LETTER);
const isDigits = (s) => /^\d+$/.test(s);

// ---------------------------------------------------------------- Ireland
export const IE_COUNTIES = {
  C: ['Cork', 'Corcaigh'], CE: ['Clare', 'An Clár'], CN: ['Cavan', 'An Cabhán'],
  CW: ['Carlow', 'Ceatharlach'], D: ['Dublin', 'Baile Átha Cliath'], DL: ['Donegal', 'Dún na nGall'],
  G: ['Galway', 'Gaillimh'], KE: ['Kildare', 'Cill Dara'], KK: ['Kilkenny', 'Cill Chainnigh'],
  KY: ['Kerry', 'Ciarraí'], L: ['Limerick', 'Luimneach'], LD: ['Longford', 'An Longfort'],
  LH: ['Louth', 'Lú'], LK: ['Limerick', 'Luimneach'], LM: ['Leitrim', 'Liatroim'],
  LS: ['Laois', 'Laois'], MH: ['Meath', 'An Mhí'], MN: ['Monaghan', 'Muineachán'],
  MO: ['Mayo', 'Maigh Eo'], OY: ['Offaly', 'Uíbh Fhailí'], RN: ['Roscommon', 'Ros Comáin'],
  SO: ['Sligo', 'Sligeach'], T: ['Tipperary', 'Tiobraid Árann'], TN: ['Tipperary North', 'Tiobraid Árann'],
  TS: ['Tipperary South', 'Tiobraid Árann'], W: ['Waterford', 'Port Láirge'], WD: ['Waterford', 'Port Láirge'],
  WH: ['Westmeath', 'An Iarmhí'], WX: ['Wexford', 'Loch Garman'],
};

function ieYear(y) {
  if (y.length === 2) {
    const n = +y;
    if (n >= 87) return { year: 1900 + n };
    if (n <= 12) return { year: 2000 + n };
    return null;
  }
  if (y.length === 3 && (y[2] === '1' || y[2] === '2')) {
    const n = +y.slice(0, 2);
    if (n >= 13 && n <= 40) return { year: 2000 + n, half: +y[2] };
  }
  return null;
}

// Number of characters changed by confusion fixes: fewer changes = more plausible reading.
const changes = (a, b) => { let n = 0; for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) n++; return n; };
const cheapest = (cands) => cands.reduce((best, c) => (!best || c.cost < best.cost ? c : best), null);

export function validateIE(raw) {
  const s = clean(raw);
  const cands = [];
  for (const ylen of [3, 2]) {
    for (const clen of [2, 1]) {
      if (s.length < ylen + clen + 1) continue;
      const year = digits(s.slice(0, ylen));
      const county = letters(s.slice(ylen, ylen + clen));
      const num = digits(s.slice(ylen + clen));
      if (isDigits(year) && IE_COUNTIES[county] && isDigits(num) && num.length <= 6 && num[0] !== '0' && ieYear(year)) {
        const key = year + county + num;
        cands.push({ cost: changes(s, key) + (ylen === 2 ? 0.2 : 0), text: `${year}-${county}-${num}`, key });
      }
    }
  }
  const best = cheapest(cands);
  if (best) return { text: best.text, key: best.key, valid: true, format: 'IE' };
  return { text: s, key: s, valid: false, format: 'IE' };
}

export function decodeIE(key) {
  const m = /^(\d{2,3})([A-Z]{1,2})(\d{1,6})$/.exec(key || '');
  if (!m) return null;
  const y = ieYear(m[1]);
  const c = IE_COUNTIES[m[2]];
  if (!y || !c) return null;
  return {
    year: y.year,
    period: y.half ? (y.half === 1 ? 'Jan–Jun' : 'Jul–Dec') : null,
    county: c[0],
    countyGa: c[1],
  };
}

// ---------------------------------------------------------------- United Kingdom
export const UK_AREAS = {
  A: 'Anglia', B: 'Birmingham', C: 'Cymru', D: 'Deeside', E: 'Essex', F: 'Forest & Fens',
  G: 'Garden of England', H: 'Hampshire & Dorset', K: 'Milton Keynes', L: 'London',
  M: 'Manchester & Merseyside', N: 'North', O: 'Oxford', P: 'Preston', R: 'Reading',
  S: 'Scotland', V: 'Severn Valley', W: 'West of England', Y: 'Yorkshire',
};

const ukAgeOk = (n) => (n >= 2 && n <= 40) || (n >= 51 && n <= 90);
// NI county codes are I? (IA, IB, IG, IJ, IL, IW) or ?Z; a 3-letter mark is serial letter + code.
const isNICode = (L) => /^(I[A-Z]|[A-Z]Z)$/.test(L.length === 3 ? L.slice(1) : L);

export function validateUK(raw) {
  const s = clean(raw);
  const cands = [];
  // Current GB format (AB12 CDE): area letter + office letter (never I, Q, Z), age identifier, 3 random letters (never I, Q)
  if (s.length === 7) {
    const t = letters(s.slice(0, 2)) + digits(s.slice(2, 4)) + letters(s.slice(4));
    if (/^[A-HJ-PR-Y]{2}\d{2}[A-HJ-PR-Z]{3}$/.test(t) && UK_AREAS[t[0]] && ukAgeOk(+t.slice(2, 4))) {
      cands.push({ cost: changes(s, t), text: `${t.slice(0, 4)} ${t.slice(4)}`, key: t, format: 'UK' });
    }
  }
  // Northern Ireland: optional serial letter + 2-letter code (I? or ?Z), then up to 4 digits (ABZ 1234)
  for (const k of [3, 2]) {
    if (s.length <= k || s.length > k + 4) continue;
    const L = letters(s.slice(0, k));
    const N = digits(s.slice(k));
    if (/^[A-Z]+$/.test(L) && isNICode(L) && isDigits(N) && N[0] !== '0') {
      cands.push({ cost: changes(s, L + N) + 0.3, text: `${L} ${N}`, key: L + N, format: 'NI' });
    }
  }
  // Older GB prefix (A123 BCD) and suffix (ABC 123D) registrations: rarer on the road, so they cost more.
  let m = /^([A-HJ-NPR-Y])(\d{1,3})([A-Z]{3})$/.exec(s);
  if (m) cands.push({ cost: 1.5, text: `${m[1]}${m[2]} ${m[3]}`, key: s, format: 'UK-prefix' });
  m = /^([A-Z]{3})(\d{1,3})([A-HJ-NPR-Y])$/.exec(s);
  if (m) cands.push({ cost: 1.5, text: `${m[1]} ${m[2]}${m[3]}`, key: s, format: 'UK-suffix' });
  const best = cheapest(cands);
  if (best) return { text: best.text, key: best.key, valid: true, format: best.format };
  return { text: s, key: s, valid: false, format: 'UK' };
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export function decodeUK(key) {
  if (/^[A-Z]{2}\d{2}[A-Z]{3}$/.test(key || '')) {
    const n = +key.slice(2, 4);
    const march = n < 50;
    const year = 2000 + (march ? n : n - 50);
    return {
      area: UK_AREAS[key[0]] || null,
      year,
      period: march ? `${MONTHS[2]}–${MONTHS[7]}` : `${MONTHS[8]}–${MONTHS[1]}`,
    };
  }
  if (/^[A-Z]{2,3}\d{1,4}$/.test(key || '') && isNICode(key.replace(/\d/g, ''))) {
    return { area: 'Northern Ireland', year: null, period: null };
  }
  return null;
}

// ---------------------------------------------------------------- Generic
export function validateGeneric(raw) {
  const s = clean(raw);
  return { text: s, key: s, valid: s.length >= 4 && s.length <= 10 && /\d/.test(s), format: 'ANY' };
}

const VALIDATORS = { IE: validateIE, UK: validateUK, ANY: validateGeneric };

/** Pick the validator for a format setting ('auto' | 'IE' | 'UK' | 'ANY') and a predicted region. */
export function profileFor(setting, region) {
  if (setting && setting !== 'auto') return VALIDATORS[setting] ? setting : 'ANY';
  if (region === 'Ireland') return 'IE';
  if (region === 'United Kingdom') return 'UK';
  return 'ANY';
}

export function validate(raw, profile = 'ANY') {
  return (VALIDATORS[profile] || validateGeneric)(raw);
}

export function decode(key, profile) {
  if (profile === 'IE') return decodeIE(key);
  if (profile === 'UK') return decodeUK(key);
  return null;
}

// ---------------------------------------------------------------- Regions → flags
const ISO = {
  Albania: 'AL', Andorra: 'AD', Argentina: 'AR', Armenia: 'AM', Australia: 'AU', Austria: 'AT',
  Azerbaijan: 'AZ', Bahrain: 'BH', Belarus: 'BY', Belgium: 'BE', 'Bosnia and Herzegovina': 'BA',
  Brazil: 'BR', Bulgaria: 'BG', Cambodia: 'KH', Canada: 'CA', Croatia: 'HR', Cyprus: 'CY',
  'Czech Republic': 'CZ', Denmark: 'DK', Estonia: 'EE', Finland: 'FI', France: 'FR', Georgia: 'GE',
  Germany: 'DE', Gibraltar: 'GI', Greece: 'GR', Guernsey: 'GG', Hungary: 'HU', Iceland: 'IS',
  Indonesia: 'ID', Ireland: 'IE', Israel: 'IL', Italy: 'IT', Latvia: 'LV', Liechtenstein: 'LI',
  Lithuania: 'LT', Luxembourg: 'LU', Malaysia: 'MY', Malta: 'MT', Mexico: 'MX', Moldova: 'MD',
  Monaco: 'MC', Montenegro: 'ME', Netherlands: 'NL', 'New Zealand': 'NZ', 'North Macedonia': 'MK',
  Norway: 'NO', Poland: 'PL', Portugal: 'PT', Qatar: 'QA', Romania: 'RO', 'San Marino': 'SM',
  Serbia: 'RS', Singapore: 'SG', Slovakia: 'SK', Slovenia: 'SI', Spain: 'ES', Sweden: 'SE',
  Switzerland: 'CH', Thailand: 'TH', Turkey: 'TR', 'United States': 'US', Ukraine: 'UA',
  'United Kingdom': 'GB', Vietnam: 'VN',
};
// Code shown on the plate's blue band
const BAND = { IE: 'IRL', GB: 'UK', DE: 'D', FR: 'F', IT: 'I', ES: 'E', NL: 'NL', BE: 'B', PT: 'P', AT: 'A', PL: 'PL', CZ: 'CZ', SE: 'S', DK: 'DK', FI: 'FIN', NO: 'N', CH: 'CH', HU: 'H', SK: 'SK', SI: 'SLO', HR: 'HR', RO: 'RO', BG: 'BG', GR: 'GR', LU: 'L', LT: 'LT', LV: 'LV', EE: 'EST', MT: 'M', CY: 'CY' };

export function isoFor(region) { return ISO[region] || null; }
export function flagFor(region) {
  const iso = ISO[region];
  if (!iso) return '';
  return String.fromCodePoint(...[...iso].map((c) => 0x1f1a5 + c.charCodeAt(0)));
}
export function bandFor(region) { const iso = ISO[region]; return iso ? (BAND[iso] || iso) : ''; }
