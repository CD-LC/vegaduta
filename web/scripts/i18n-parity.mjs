// i18n parity tool for web/messages/*.json.
//   node scripts/i18n-parity.mjs                 report per-locale coverage and dead keys
//   node scripts/i18n-parity.mjs --prune         delete locale keys that no longer exist in en.json (rewrites files)
//   node scripts/i18n-parity.mjs --check         CI ratchet against messages/i18n-parity-baseline.json
//   node scripts/i18n-parity.mjs --update        rewrite the baseline (refuses to loosen it unless --force)
// Coverage = share of en.json leaf keys present in the locale. Dead = locale leaf keys absent from en.json.
// A "leaf" is any non-object value (strings, arrays, numbers). Locale files are written as JSON.stringify(_, null, 2) + "\n",
// which is exactly how they are stored, so --prune keeps formatting and key order.
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const MESSAGES_DIR = path.resolve(here, "../messages");
export const BASELINE_FILE = "i18n-parity-baseline.json";
// Coverage may dip by this many percentage points before --check fails (en.json grows while agents add strings).
export const COVERAGE_EPSILON = 0.01;

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/** Leaf paths of a message tree, joined with ".". */
export function flatten(tree, prefix = "", out = new Set()) {
  for (const [k, v] of Object.entries(tree)) {
    if (k.startsWith("_")) continue; // pipeline metadata (e.g. _sourceHash), not message keys
    const p = prefix ? `${prefix}.${k}` : k;
    if (isObj(v)) flatten(v, p, out);
    else out.add(p);
  }
  return out;
}

/** Compare a locale tree to the English tree. */
export function compare(en, locale) {
  const enKeys = flatten(en);
  const locKeys = flatten(locale);
  let present = 0;
  const dead = [];
  for (const k of locKeys) (enKeys.has(k) ? present++ : dead.push(k));
  return {
    enKeys: enKeys.size,
    present,
    missing: enKeys.size - present,
    dead: dead.length,
    deadKeys: dead,
    coverage: enKeys.size ? round((present / enKeys.size) * 100) : 100,
  };
}

const round = (n) => Math.round(n * 100) / 100;

/** Returns a copy of `locale` without leaves absent from `en`; namespaces left empty are dropped. */
export function prune(en, locale) {
  const out = {};
  for (const [k, v] of Object.entries(locale)) {
    if (k.startsWith("_")) {
      out[k] = v; // pipeline metadata (e.g. _sourceHash) is never a dead key
      continue;
    }
    if (!(k in en)) continue;
    const e = en[k];
    if (isObj(v)) {
      if (!isObj(e)) continue; // en has a leaf here, locale a subtree: dead
      const sub = prune(e, v);
      if (Object.keys(sub).length) out[k] = sub;
    } else if (!isObj(e)) {
      out[k] = v;
    }
  }
  return out;
}

/** Ratchet: coverage never down (beyond epsilon), dead never up, no new locales without a baseline entry. */
export function ratchet(baseline, current, epsilon = COVERAGE_EPSILON) {
  const errors = [];
  for (const [loc, cur] of Object.entries(current)) {
    const base = baseline[loc];
    if (!base) {
      errors.push(`${loc}: not in baseline (run --update)`);
      continue;
    }
    if (cur.coverage < base.coverage - epsilon)
      errors.push(`${loc}: coverage fell ${base.coverage}% -> ${cur.coverage}%`);
    if (cur.dead > base.dead) errors.push(`${loc}: dead keys rose ${base.dead} -> ${cur.dead} (run --prune)`);
  }
  for (const loc of Object.keys(baseline)) if (!current[loc]) errors.push(`${loc}: in baseline but locale file is gone`);
  return errors;
}

export const readJson = (f) => JSON.parse(readFileSync(f, "utf8"));
export const serialize = (tree) => JSON.stringify(tree, null, 2) + "\n";

export function localeFiles(dir = MESSAGES_DIR) {
  return readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "en.json" && f !== BASELINE_FILE).sort();
}

export function analyse(dir = MESSAGES_DIR) {
  const en = readJson(path.join(dir, "en.json"));
  const stats = {};
  for (const f of localeFiles(dir)) stats[f.replace(/\.json$/, "")] = compare(en, readJson(path.join(dir, f)));
  return { en, stats };
}

const slim = (s) => ({ enKeys: s.enKeys, present: s.present, coverage: s.coverage, dead: s.dead });

function printReport(stats) {
  const rows = Object.entries(stats);
  console.log("locale  coverage  present/en       missing   dead");
  for (const [l, s] of rows)
    console.log(
      `${l.padEnd(7)} ${(s.coverage.toFixed(2) + "%").padStart(8)}  ${`${s.present}/${s.enKeys}`.padEnd(15)} ${String(s.missing).padStart(7)} ${String(s.dead).padStart(6)}`,
    );
  const dead = rows.reduce((a, [, s]) => a + s.dead, 0);
  console.log(`total dead keys: ${dead}`);
}

function main(argv) {
  const has = (f) => argv.includes(f);
  const baselinePath = path.join(MESSAGES_DIR, BASELINE_FILE);
  let { en, stats } = analyse();
  if (has("--prune")) {
    console.log("before:");
    printReport(stats);
    for (const f of localeFiles()) {
      const file = path.join(MESSAGES_DIR, f);
      writeFileSync(file, serialize(prune(en, readJson(file))));
    }
    ({ stats } = analyse());
    console.log("after:");
    printReport(stats);
    return 0;
  }
  if (has("--update")) {
    const next = Object.fromEntries(Object.entries(stats).map(([l, s]) => [l, slim(s)]));
    if (existsSync(baselinePath) && !has("--force")) {
      const errors = ratchet(readJson(baselinePath), next);
      if (errors.length) {
        console.error("refusing to loosen the baseline (use --force):\n" + errors.join("\n"));
        return 1;
      }
    }
    writeFileSync(baselinePath, serialize(next));
    console.log(`wrote ${BASELINE_FILE}`);
    return 0;
  }
  if (has("--check")) {
    if (!existsSync(baselinePath)) {
      console.error(`${BASELINE_FILE} missing; run --update`);
      return 1;
    }
    const errors = ratchet(readJson(baselinePath), stats);
    if (errors.length) {
      console.error("i18n parity ratchet failed:\n" + errors.join("\n"));
      return 1;
    }
    console.log(`i18n parity ok (${Object.keys(stats).length} locales)`);
    return 0;
  }
  printReport(stats);
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main(process.argv.slice(2));
