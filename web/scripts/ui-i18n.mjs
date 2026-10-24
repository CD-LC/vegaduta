// Completeness of the translations for the agent-control screens (runtime settings, settings catalog copy, approvals
// queue, prompt registry, turn timeline, eval controls, agent Behavior tab, "Where do I control...?").
//
//   node scripts/ui-i18n.mjs --check   fail when a locale file lacks a key of the control UI (CI guard)
//   node scripts/ui-i18n.mjs --fill    add every missing key to every locale file with the English text as the fallback
//   node scripts/ui-i18n.mjs --todo    (re)write docs/engineering/ui-translation-todo.md: what still needs a human translation
//   --since <git ref>                  the commit the control UI work started from (default below)
//
// "Key of the control UI" = every key of the namespaces this work created (UI_NAMESPACES) plus every key added to any
// other namespace since BASELINE_REF. A locale that has the key with the English text still counts as present (the
// runtime would fall back to English anyway) but is listed in the translation todo until a person translates it.
// Locale files are written as JSON.stringify(_, null, 2) + "\n" (en.json has no trailing newline); a file keeps whichever
// ending it had, so --fill never reformats anything else.
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const MESSAGES_DIR = path.resolve(here, "../messages");
export const TODO_DOC = path.resolve(here, "../../docs/engineering/ui-translation-todo.md");

/** Namespaces created by the agent-control work: every key in them belongs to the control UI. */
export const UI_NAMESPACES = ["settings", "settingsUi", "runtimeSettings", "approvalsQueue", "evalControls", "agentBehavior", "controls"];

/** The commit the work started from; keys added to other namespaces after it also belong to the control UI. */
export const BASELINE_REF = "857274b5";

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/** Leaf paths with their values ("a.b.c" -> value). Arrays are leaves; keys starting with "_" are pipeline metadata. */
export function leaves(tree, prefix = "", out = new Map()) {
  for (const [k, v] of Object.entries(tree)) {
    if (k.startsWith("_")) continue;
    const p = prefix ? `${prefix}.${k}` : k;
    if (isObj(v)) leaves(v, p, out);
    else out.set(p, v);
  }
  return out;
}

/** The English keys that belong to the control UI: whole UI namespaces, plus keys absent from `baseline` (if given). */
export function controlUiKeys(en, baseline = null, namespaces = UI_NAMESPACES) {
  const now = leaves(en);
  const before = baseline ? leaves(baseline) : null;
  const out = [];
  for (const k of now.keys()) {
    const ns = k.split(".")[0];
    if (namespaces.includes(ns) || (before && !before.has(k))) out.push(k);
  }
  return out;
}

/** English tree of the baseline commit, or null when git or the commit is not available (shallow clone, tarball). */
export function readBaseline(ref = BASELINE_REF) {
  try {
    const text = execFileSync("git", ["show", `${ref}:web/messages/en.json`], {
      cwd: here,
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
      stdio: ["ignore", "pipe", "ignore"],
    });
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const getAt = (tree, dotted) => dotted.split(".").reduce((n, p) => (isObj(n) ? n[p] : undefined), tree);

export function setAt(tree, dotted, value) {
  const parts = dotted.split(".");
  let node = tree;
  for (const p of parts.slice(0, -1)) {
    if (!isObj(node[p])) node[p] = {};
    node = node[p];
  }
  node[parts[parts.length - 1]] = value;
}

export function localeFiles(dir = MESSAGES_DIR) {
  return readdirSync(dir)
    .filter((f) => /^[A-Za-z-]+\.json$/.test(f) && f !== "en.json" && !f.startsWith("i18n-parity"))
    .sort();
}

const read = (f) => {
  const raw = readFileSync(f, "utf8");
  return { tree: JSON.parse(raw), trailingNewline: raw.endsWith("\n") };
};

/** For each locale: the control-UI keys it lacks, and the ones it has with the English text still in place. */
export function analyse({ dir = MESSAGES_DIR, ref = BASELINE_REF, baseline = readBaseline(ref) } = {}) {
  const en = read(path.join(dir, "en.json")).tree;
  const enLeaves = leaves(en);
  const keys = controlUiKeys(en, baseline);
  /** @type {Record<string, { missing: string[]; english: string[] }>} */
  const locales = {};
  for (const f of localeFiles(dir)) {
    const tree = read(path.join(dir, f)).tree;
    const missing = [];
    const english = [];
    for (const k of keys) {
      const v = getAt(tree, k);
      if (v === undefined || isObj(v)) missing.push(k);
      else if (JSON.stringify(v) === JSON.stringify(enLeaves.get(k))) english.push(k);
    }
    locales[f.replace(/\.json$/, "")] = { missing, english };
  }
  return { en, enLeaves, keys, locales, usedBaseline: baseline !== null };
}

/** Adds every missing control-UI key to every locale file, with the English text. Returns how many keys were added. */
export function fill({ dir = MESSAGES_DIR, ref = BASELINE_REF } = {}) {
  const { enLeaves, locales } = analyse({ dir, ref });
  let added = 0;
  for (const f of localeFiles(dir)) {
    const loc = locales[f.replace(/\.json$/, "")];
    if (!loc.missing.length) continue;
    const file = path.join(dir, f);
    const { tree, trailingNewline } = read(file); // re-read just before writing: other work edits these files too
    for (const k of loc.missing) {
      if (getAt(tree, k) !== undefined && !isObj(getAt(tree, k))) continue;
      setAt(tree, k, structuredClone(enLeaves.get(k)));
      added += 1;
    }
    writeFileSync(file, JSON.stringify(tree, null, 2) + (trailingNewline ? "\n" : ""));
  }
  return added;
}

const clip = (v) => String(Array.isArray(v) ? v.join(" | ") : v).replace(/\s+/g, " ").replace(/\|/g, "\\|");

/** Markdown for docs/engineering/ui-translation-todo.md. */
export function todoMarkdown(result) {
  const { enLeaves, keys, locales } = result;
  const localeIds = Object.keys(locales);
  // Keys still in English in EVERY locale are the ones nobody has translated yet.
  const untranslated = keys.filter((k) => localeIds.every((l) => locales[l].english.includes(k)));
  const byNs = new Map();
  for (const k of keys) {
    const ns = k.split(".")[0];
    byNs.set(ns, [...(byNs.get(ns) ?? []), k]);
  }
  const lines = [];
  lines.push("# UI translation todo", "");
  lines.push(
    "Generated by `node scripts/ui-i18n.mjs --todo` (from `web/`). Do not edit by hand; regenerate after translating.",
    ""
  );
  lines.push(
    `The agent-control screens (runtime settings, the settings catalog copy, the approvals queue, the prompt registry, the turn`,
    `timeline, the eval controls, the agent Behavior tab and "Where do I control...?") added **${keys.length} message keys**. Every one`,
    `of them exists in every locale file in \`web/messages\`, so no screen shows a raw key. Where nobody has translated a key yet, the`,
    `locale file carries the English text as the fallback. This list is what a translator has to do.`,
    ""
  );
  lines.push(
    `**${untranslated.length} of ${keys.length}** keys are still English in all ${localeIds.length} non-English locales.`,
    "A key whose English text is correct in a language (a product name, \"OK\") will show up here too; leave it and move on.",
    ""
  );
  lines.push("## How to translate", "");
  lines.push(
    "1. Open `web/messages/<locale>.json` and find the key (the table gives the dotted path: `namespace.key.path`).",
    "2. Replace the English value with the translation. Keep `{placeholders}` exactly as they are and keep `<strong>` tags if present.",
    "3. Run `node scripts/ui-i18n.mjs --check` (every key present) and `node scripts/ui-i18n.mjs --todo` (refresh this file).",
    "4. A new message added to `en.json` in these namespaces needs `node scripts/ui-i18n.mjs --fill` so every locale has at least the English fallback; `app/lib/uiI18n.test.ts` fails otherwise.",
    ""
  );
  lines.push("## Still English, per locale", "", "| Locale | Keys still in English | Keys translated |", "|---|---|---|");
  for (const l of localeIds) {
    const e = locales[l].english.length;
    lines.push(`| ${l} | ${e} | ${keys.length - e - locales[l].missing.length} |`);
  }
  lines.push("", "## Keys needing translation, by namespace", "");
  for (const [ns, nsKeys] of byNs) {
    const todo = nsKeys.filter((k) => untranslated.includes(k));
    lines.push(`### \`${ns}\` (${todo.length} of ${nsKeys.length} to translate)`, "");
    if (!todo.length) {
      lines.push("All keys are translated in at least one locale.", "");
      continue;
    }
    lines.push("<details><summary>Show the keys and their English text</summary>", "", "| Key | English |", "|---|---|");
    for (const k of todo) lines.push(`| \`${k}\` | ${clip(enLeaves.get(k))} |`);
    lines.push("", "</details>", "");
  }
  return lines.join("\n");
}

function main(argv) {
  const has = (f) => argv.includes(f);
  const sinceAt = argv.indexOf("--since");
  const ref = sinceAt >= 0 ? argv[sinceAt + 1] : BASELINE_REF;
  if (has("--fill")) {
    const added = fill({ ref });
    console.log(`added ${added} key(s) (English fallback) across the locale files`);
  }
  const result = analyse({ ref });
  if (!result.usedBaseline) console.warn(`note: git ref ${ref} not available; checking the control-UI namespaces only`);
  if (has("--todo")) {
    writeFileSync(TODO_DOC, todoMarkdown(result).replace(/\s+$/, "") + "\n");
    console.log(`wrote ${path.relative(process.cwd(), TODO_DOC)}`);
  }
  const missing = Object.entries(result.locales).filter(([, v]) => v.missing.length);
  console.log(`${result.keys.length} control-UI keys; ${missing.length} locale(s) missing some`);
  for (const [l, v] of missing) console.log(`  ${l}: ${v.missing.length} missing, e.g. ${v.missing.slice(0, 3).join(", ")}`);
  return has("--check") && missing.length ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
