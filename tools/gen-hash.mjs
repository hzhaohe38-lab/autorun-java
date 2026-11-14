// Reference hashSeed + mulberry32 draws, copied from server/auto-run.js.
function hashSeed(str) {
  let h = 2166136261 >>> 0;
  const s = String(str);
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function mulberry32(seed) {
  let a = seed >>> 0;
  return function rng() {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const keys = [
  '3983104:2026-11-14',
  '3983104:2026-11-15',
  '3983103:2026-11-14',
  'admin:2026-01-01',
];

const out = keys.map((key) => {
  const seed = hashSeed(key);
  const rng = mulberry32(seed);
  return { key, seed, draws: Array.from({ length: 5 }, () => rng()) };
});
process.stdout.write(JSON.stringify(out, null, 2));
