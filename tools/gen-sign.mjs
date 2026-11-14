// Reference signatures, computed with the ORIGINAL genSign algorithm
// (app/src/utils/sign.js). Self-contained because sign.js imports the Vite env.

import crypto from 'node:crypto';

const APP_KEY = '389885588s0648fa';
const APP_SECRET = '56E39A1658455588885690425C0FD16055A21676';

function genSign(query = null, body = null) {
  let signStr = '';
  if (query !== null) {
    const normalized = Object.entries(query).reduce((acc, [k, v]) => {
      acc[k] = v === null ? '' : String(v);
      return acc;
    }, {});
    for (const key of Object.keys(normalized).sort()) {
      const value = normalized[key];
      if (value !== '') signStr += key + value;
    }
  }
  signStr += APP_KEY;
  signStr += APP_SECRET;
  if (body !== null) signStr += JSON.stringify(body);

  let replaced = false;
  for (const ch of [' ', '~', '!', '(', ')', "'"]) {
    if (signStr.includes(ch)) {
      const escaped = ch.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      signStr = signStr.replace(new RegExp(escaped, 'g'), '');
      replaced = true;
    }
  }
  if (replaced) signStr = encodeURIComponent(signStr);

  let sign = crypto.createHash('md5').update(signStr).digest('hex').toUpperCase();
  if (replaced) sign += 'encodeutf8';
  return sign;
}

const cases = [
  { name: 'empty', query: {}, body: null },
  { name: 'signInTf', query: { studentId: 3983104 }, body: null },
  {
    name: 'signBody',
    query: null,
    body: {
      activityId: 12345,
      latitude: '30.5806854',
      longitude: '103.9857758',
      signType: '1',
      studentId: 3983104,
    },
  },
  {
    name: 'specialChars',
    query: null,
    body: { name: "成都信息工程大学 a(b)c'd!e~f", note: 'has space' },
  },
  {
    name: 'runRecord',
    query: null,
    body: {
      againRunStatus: '0',
      againRunTime: 0,
      appVersions: '3.6.0',
      brand: 'Apple',
      mobileType: 'iPhone',
      sysVersions: '18.6',
      trackPoints: '["103.98643281-30.58113441","103.98639557-30.58115000"]',
      distanceTimeStatus: '1',
      innerSchool: '1',
      runDistance: 3000,
      runTime: 20,
      userId: 140842,
      vocalStatus: '1',
      yearSemester: '20262',
      recordDate: '2026-11-14',
    },
  },
  {
    name: 'records',
    query: { pageNum: 1, pageSize: 15 },
    body: null,
  },
];

const out = cases.map((c) => ({
  name: c.name,
  sign: genSign(c.query, c.body),
}));
process.stdout.write(JSON.stringify(out, null, 2));
