#!/usr/bin/env node
/**
 * "I want entire text on the website to get converted to language, not just
 * simple menu items - I'm ok to buy any LLM for translation purposes, where
 * it will check weekly for new translations or updates - platform should be
 * intelligent about it."
 *
 * This is that automation. It does NOT translate `en.json` itself (that's
 * the source of truth a person writes) - it diffs every other
 * `messages/{locale}.json` file against `en.json`, finds keys that are
 * missing or have gone stale (English text changed since that key was last
 * translated), and asks an LLM to translate exactly those keys, nothing
 * more. Run it:
 *
 *   - locally / on demand:  npm run translate-messages
 *   - weekly, unattended:   wire this script into a CI cron (GitHub Actions
 *     `schedule:`, a Vercel Cron Job, or a k8s CronJob - whichever this app
 *     is actually deployed on) so new/changed English strings get
 *     translated automatically within a week of being written, without a
 *     person remembering to run it.
 *
 * Why a standalone script and not a backend endpoint: `messages/*.json` are
 * static, build-time UI strings bundled into the Next.js app itself - they
 * aren't tenant data, so there's no tenant/session to authenticate a live
 * API call against, and "weekly" describes a schedule for a job, not a
 * per-request feature. A script that reads/writes these files directly (and
 * that CI can run before `next build`) is the natural fit; the existing
 * per-tenant multi-LLM-provider system in `core/providers` stays exactly
 * what it always was - for AGENTS talking to customers, not for this
 * platform's own UI chrome.
 *
 * Which LLM: any OpenAI-Chat-Completions-compatible endpoint, OR Claude's own
 * native Messages API directly (added because Anthropic's own docs say their
 * OpenAI-compatibility shim is a testing convenience, not meant for
 * production/unattended use - this script's weekly cron + on-demand trigger
 * both count as "unattended", so it talks to /v1/messages directly instead
 * of routing through that shim). Configure via env vars so "buy any LLM" is
 * a config change, not a code change:
 *   TRANSLATE_LLM_PROVIDER  "openai" (default) or "anthropic"
 *   TRANSLATE_LLM_API_KEY   required (or ANTHROPIC_API_KEY, either works when
 *                           TRANSLATE_LLM_PROVIDER=anthropic)
 *   TRANSLATE_LLM_API_BASE  default: https://api.openai.com/v1, or
 *                           https://api.anthropic.com/v1 when provider=anthropic
 *   TRANSLATE_LLM_MODEL     default: gpt-4o-mini, or claude-sonnet-4-5 when
 *                           provider=anthropic
 * Any other OpenAI-compatible provider (Azure OpenAI, etc.) still works by
 * just pointing TRANSLATE_LLM_API_BASE at it - no code change needed.
 *
 * Every locale file also carries a hidden `_sourceHash` per key (see
 * KEY_HASH_FIELD below) - a hash of the English string that key was
 * translated FROM. That's what "check weekly for... updates" actually
 * means: if `en.json`'s text for a key changes, this script notices the
 * hash no longer matches and re-translates that key, rather than only ever
 * filling in brand-new keys and letting stale translations silently drift
 * (the exact risk docs/I18N-MIGRATION-PLAN.md's translation-quality caveat
 * warns about).
 */
import { readFile, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MESSAGES_DIR = path.resolve(__dirname, "..", "messages");
const EN_FILE = path.join(MESSAGES_DIR, "en.json");

// Keep in sync with app/lib/i18n-config.js's SUPPORTED_LOCALES (English
// excluded - it's the source, never a translation target). Indian-language
// block added per "add all indian language support" - all 22 Eighth
// Schedule languages, so LOCALE_NAMES gives the LLM an unambiguous language
// name (several of these share a two-letter code prefix with unrelated
// languages, e.g. "or" isn't a stopword here, it's Odia).
const ALL_TARGET_LOCALES = [
  "es", "fr", "de", "pt", "ar", "zh", "ja",
  "hi", "bn", "mr", "te", "ta", "gu", "ur", "kn", "or", "ml", "pa",
  "as", "mai", "sat", "ks", "ne", "kok", "sd", "doi", "mni", "brx", "sa",
];

// Optional scoping for local/manual test runs - e.g. `TRANSLATE_LOCALES=ar
// npm run translate-messages` to translate just Arabic instead of every
// supported locale. Unset (the normal weekly-cron and on-demand-trigger
// path) still runs the full ALL_TARGET_LOCALES list - this never narrows
// production runs, only opt-in local testing.
const TARGET_LOCALES = process.env.TRANSLATE_LOCALES
  ? process.env.TRANSLATE_LOCALES.split(",").map((s) => s.trim()).filter(Boolean)
  : ALL_TARGET_LOCALES;

// Optional scoping to a subset of top-level namespaces (dot-path prefixes) -
// e.g. `TRANSLATE_KEY_PREFIXES=landing,senses,try` to translate only the
// landing page + /try sandbox instead of every namespace in en.json. Same
// opt-in-only rule as TRANSLATE_LOCALES above: unset runs every namespace.
const KEY_PREFIXES = process.env.TRANSLATE_KEY_PREFIXES
  ? process.env.TRANSLATE_KEY_PREFIXES.split(",").map((s) => s.trim()).filter(Boolean)
  : null;
function inKeyScope(key) {
  return !KEY_PREFIXES || KEY_PREFIXES.includes(key.split(".")[0]);
}

const LOCALE_NAMES = {
  es: "Spanish", fr: "French", de: "German", pt: "Portuguese",
  ar: "Arabic (Modern Standard)", zh: "Simplified Chinese", ja: "Japanese",
  hi: "Hindi", bn: "Bengali", mr: "Marathi", te: "Telugu", ta: "Tamil",
  gu: "Gujarati", ur: "Urdu", kn: "Kannada", or: "Odia", ml: "Malayalam",
  pa: "Punjabi (Gurmukhi script)", as: "Assamese", mai: "Maithili",
  sat: "Santali (write in Ol Chiki script)", ks: "Kashmiri (Perso-Arabic script)",
  ne: "Nepali", kok: "Konkani (Devanagari script)", sd: "Sindhi (Perso-Arabic script)",
  doi: "Dogri", mni: "Manipuri/Meitei (write in Bengali script)", brx: "Bodo",
  sa: "Sanskrit",
};

const PROVIDER = (process.env.TRANSLATE_LLM_PROVIDER || "openai").toLowerCase(); // "openai" | "anthropic"
const API_KEY = process.env.TRANSLATE_LLM_API_KEY || process.env.ANTHROPIC_API_KEY;
const API_BASE =
  process.env.TRANSLATE_LLM_API_BASE ||
  (PROVIDER === "anthropic" ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1");
const MODEL = process.env.TRANSLATE_LLM_MODEL || (PROVIDER === "anthropic" ? "claude-sonnet-4-5" : "gpt-4o-mini");
const ANTHROPIC_VERSION = process.env.ANTHROPIC_VERSION || "2023-06-01";
const HASH_KEY = "_sourceHash"; // one reserved top-level key per locale file, e.g. { "_sourceHash": { "nav.dashboard": "ab12..." }, "nav": {...} }

function hashOf(text) {
  return createHash("sha256").update(text, "utf8").digest("hex").slice(0, 16);
}

function flatten(obj, prefix = "", out = {}) {
  for (const [key, value] of Object.entries(obj)) {
    if (key === HASH_KEY) continue;
    const path_ = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === "object" && !Array.isArray(value)) {
      flatten(value, path_, out);
    } else {
      out[path_] = value;
    }
  }
  return out;
}

function setByPath(obj, path_, value) {
  const parts = path_.split(".");
  let node = obj;
  for (let i = 0; i < parts.length - 1; i += 1) {
    node[parts[i]] = node[parts[i]] || {};
    node = node[parts[i]];
  }
  node[parts[parts.length - 1]] = value;
}

async function loadJson(file) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (e) {
    if (e.code === "ENOENT") return {};
    throw e;
  }
}

// Some models (Claude included, despite instructions) occasionally wrap
// their JSON reply in a ```json fenced code block - strip that before
// parsing rather than letting a well-formed response fail JSON.parse().
function extractJson(text) {
  const trimmed = text.trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
  return fenced ? fenced[1].trim() : trimmed;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Retries a 429 ("Resource exhausted") with exponential backoff before
// giving up - added after a real run against a fresh Vertex AI project hit
// its default per-minute quota almost immediately under any concurrency
// above 1, and the caller-level retry (translate-all wrapper) wasn't waiting
// long enough for the per-minute window to actually reset. 5 attempts,
// starting at 20s and doubling, covers a couple of quota-window resets
// without making a single stuck locale block forever.
async function fetchWithBackoff(url, opts) {
  const MAX_ATTEMPTS = 5;
  let delay = 20000;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
    const res = await fetch(url, opts);
    if (res.status !== 429) return res;
    if (attempt === MAX_ATTEMPTS) return res;
    console.warn(`  ! 429 rate-limited, waiting ${Math.round(delay / 1000)}s (attempt ${attempt}/${MAX_ATTEMPTS})...`);
    await sleep(delay);
    delay *= 2;
  }
  return fetch(url, opts); // unreachable, satisfies control-flow analysis
}

/**
 * The never-translate brand list, injected into every system prompt.
 *
 * This script used to send no brand rule at all, while the Academy's
 * `scripts/i18n/translate-catalog.mjs` has always taken a `--glossary`. The
 * asymmetry was invisible in Latin-script targets - French, Spanish and even
 * Chinese and Arabic left "VegaDūta" alone unprompted - but the Indic models
 * transliterate a proper noun into the target script by default, which is the
 * right instinct for an ordinary word and wrong for a brand. Result: 124
 * strings across hi/ta/te/kn carried the product name in Devanagari, Tamil,
 * Telugu and Kannada script (Telugu worst, at 49 of 70).
 *
 * `glossary.json`'s `families` policy deliberately DOES transliterate core
 * agentic vocabulary (agent, token, prompt, guardrail, workflow) for those
 * same locales - so the fix is not "never transliterate", it is "never
 * transliterate the names in `neverTranslate`".
 *
 * Read once and cached; a missing/broken glossary degrades to no brand rule
 * rather than failing the run, since this script runs unattended on a cron.
 */
let brandRuleCache = null;
function brandRule() {
  if (brandRuleCache !== null) return brandRuleCache;
  try {
    const p = path.resolve(__dirname, "..", "..", "scripts", "i18n", "glossary.json");
    const names = JSON.parse(readFileSync(p, "utf8")).neverTranslate || [];
    brandRuleCache = names.length
      ? `Leave these names EXACTLY as written, in Latin script - never translate, transliterate ` +
        `or re-spell them, even when the target language uses a different script: ${names.join(", ")}. ` +
        `Inflect around them if the grammar needs it (e.g. a case suffix attached to the Latin form). `
      : "";
  } catch {
    brandRuleCache = "";
  }
  return brandRuleCache;
}

async function translateBatch(entries, targetLocale) {
  // entries: [{ key, text }] - one LLM call per locale per run, not per
  // key, so a diff of 200 keys doesn't mean 200 round trips.
  const systemPrompt =
    `You translate UI copy for a software product from English into ${LOCALE_NAMES[targetLocale]}. ` +
    `Preserve any {placeholder} tokens exactly as-is (do not translate their names). ` +
    `Preserve any HTML markup (<strong>, <em>, <br/> ...) exactly as-is - translate only the text between tags. ` +
    brandRule() +
    `Keep the tone (some strings are intentionally sci-fi/creative product copy, not literal dictionary phrasing) ` +
    `and keep translations concise - these render inside fixed-width UI elements. ` +
    `Reply with ONLY a JSON object mapping each given key to its translation, no commentary.`;
  const userContent = JSON.stringify(Object.fromEntries(entries.map((e) => [e.key, e.text])), null, 2);

  if (PROVIDER === "anthropic") {
    // Native Messages API, not the OpenAI-compatibility shim - Anthropic's
    // own docs flag that shim as a testing convenience, not for production/
    // unattended use (see this file's header comment), and this script runs
    // unattended on a weekly cron plus an on-demand trigger.
    const res = await fetchWithBackoff(`${API_BASE}/messages`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": API_KEY,
        "anthropic-version": ANTHROPIC_VERSION,
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 8192,
        temperature: 0,
        system: systemPrompt,
        messages: [{ role: "user", content: userContent }],
      }),
    });
    if (!res.ok) {
      throw new Error(`Translation LLM call failed (${res.status}): ${await res.text()}`);
    }
    const data = await res.json();
    const raw = data.content?.[0]?.text || "{}";
    return JSON.parse(extractJson(raw));
  }

  // Default: any OpenAI-Chat-Completions-compatible endpoint (OpenAI itself,
  // Azure OpenAI, etc.)
  const body = {
    model: MODEL,
    temperature: 0,
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userContent },
    ],
  };

  const res = await fetchWithBackoff(`${API_BASE}/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${API_KEY}` },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(`Translation LLM call failed (${res.status}): ${await res.text()}`);
  }
  const data = await res.json();
  const raw = data.choices?.[0]?.message?.content || "{}";
  return JSON.parse(extractJson(raw));
}

async function main() {
  if (!API_KEY) {
    // Not configured yet, not broken - the weekly scheduled workflow calls
    // this unconditionally, so exiting 1 here would red-flag CI every week
    // forever until someone adds the secret, exactly the kind of permanent
    // false-alarm noise this codebase's other optional/deployment-config
    // gates (e.g. the "knowledge" Spring profile) deliberately avoid by
    // treating "not configured" as a no-op, not a failure.
    console.log(
      "No API key set - nothing to do. Set TRANSLATE_LLM_API_KEY (or ANTHROPIC_API_KEY when "
        + "TRANSLATE_LLM_PROVIDER=anthropic) as a repo secret to enable this. See the header "
        + "comment in this file for setup."
    );
    return;
  }
  console.log(`Using provider "${PROVIDER}", model "${MODEL}", base URL "${API_BASE}".`);

  const en = await loadJson(EN_FILE);
  const enFlat = flatten(en);

  let totalTranslated = 0;
  for (const locale of TARGET_LOCALES) {
    const file = path.join(MESSAGES_DIR, `${locale}.json`);
    const existing = await loadJson(file);
    const hashes = existing[HASH_KEY] || {};
    const existingFlat = flatten(existing);

    const needsTranslation = [];
    for (const [key, text] of Object.entries(enFlat)) {
      if (typeof text !== "string") continue;
      if (!inKeyScope(key)) continue;
      const currentHash = hashOf(text);
      const alreadyTranslated = key in existingFlat;
      const hashMatches = hashes[key] === currentHash;
      if (!alreadyTranslated || !hashMatches) {
        needsTranslation.push({ key, text, hash: currentHash });
      }
    }

    if (needsTranslation.length === 0) {
      console.log(`[${locale}] up to date - nothing new or changed.`);
      continue;
    }

    console.log(`[${locale}] translating ${needsTranslation.length} key(s)...`);
    // Chunk to keep any single request reasonably sized.
    const CHUNK = 40;
    const updatedHashes = { ...hashes };
    const nextMessages = JSON.parse(JSON.stringify(existing));
    delete nextMessages[HASH_KEY];

    // Write after every chunk, not just once at the end - a run against a
    // real quota-limited project can fail on chunk 50 of 70, and losing all
    // 49 already-translated chunks because of one straggler defeats the
    // whole point of chunking. A chunk that still fails after
    // fetchWithBackoff's retries is logged and skipped (its keys keep their
    // old hash mismatch, so the next run picks them back up) rather than
    // aborting the entire locale.
    let chunkFailures = 0;
    for (let i = 0; i < needsTranslation.length; i += CHUNK) {
      const chunk = needsTranslation.slice(i, i + CHUNK);
      let translations;
      try {
        translations = await translateBatch(chunk, locale);
      } catch (err) {
        chunkFailures += 1;
        console.warn(`  ! chunk ${i / CHUNK + 1} failed, skipping (will retry next run): ${err.message}`);
        continue;
      }
      for (const entry of chunk) {
        const translated = translations[entry.key];
        if (typeof translated === "string" && translated.trim()) {
          setByPath(nextMessages, entry.key, translated);
          updatedHashes[entry.key] = entry.hash;
        } else {
          console.warn(`  ! no translation returned for "${entry.key}" - left as-is.`);
        }
      }
      nextMessages[HASH_KEY] = updatedHashes;
      await writeFile(file, `${JSON.stringify(nextMessages, null, 2)}\n`, "utf8");
    }

    totalTranslated += needsTranslation.length;
    console.log(
      `[${locale}] wrote translations to ${path.relative(process.cwd(), file)}` +
        (chunkFailures > 0 ? ` (${chunkFailures} chunk(s) failed and were skipped - rerun to pick them up)` : "") +
        "."
    );
  }

  console.log(
    totalTranslated > 0
      ? `Done - ${totalTranslated} translation(s) updated across ${TARGET_LOCALES.length} locale(s). Recommend a native-speaker review pass before shipping (see docs/I18N-MIGRATION-PLAN.md).`
      : "Done - every locale file already matches the current en.json."
  );
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
