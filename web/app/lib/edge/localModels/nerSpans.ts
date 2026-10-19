// Track 4 — the pure text side of the on-device NER (split out of ner.ts, which
// re-exports it): the result shape, CoNLL label mapping, the verbatim resolver
// that keeps every span a real substring of the source, and the chunker that
// stops the tokenizer from silently truncating a long message.

/** Characters per model call. The text is split on paragraph/line boundaries
 * below this; the tokenizer's own truncation would otherwise silently drop the
 * tail of a long message, and a dropped tail is an unmasked name. */
const MAX_CHUNK_CHARS = 900;

// ---------------------------------------------------------------------------
// Result shape
// ---------------------------------------------------------------------------

/** The closed set redactor.ts already knows how to mask. Keeping this
 * identical to redactor.ts's PLACEHOLDER keys is what lets the NER path reuse
 * the existing, tested apply step unchanged. */
export type LocalEntityCategory = "person" | "organization" | "address" | "identifier";

export interface LocalEntity {
  /** A VERBATIM substring of the text that was passed in — resolved, never
   * the model's decoded token string. See resolveVerbatimSpan. */
  text: string;
  category: LocalEntityCategory;
  /** The model's own confidence for the grouped span, 0..1. */
  score: number;
}

export type NerReason =
  /** The model ran over the whole input. `entities` is the complete answer,
   * and an empty array genuinely means "found nothing". */
  | "ok"
  /** Ran, but stopped early on the time budget. `entities` holds what was
   * found so far — safe to use, but NOT a complete answer. */
  | "partial"
  /** Input was longer than MAX_TOTAL_CHARS; the tail was not examined. */
  | "truncated"
  /** No model in memory. The ordinary state, and the one that must never
   * trigger a download. */
  | "not-loaded"
  | "edge-off"
  | "no-input"
  /** The caller's signal fired before anything was scanned. */
  | "aborted"
  /** Inference threw. */
  | "failed";

export interface NerOutcome {
  entities: LocalEntity[];
  /** Property 4: the difference between "looked and found nothing" ("ok" with
   * an empty array) and "could not look" (every other reason with an empty
   * array) is carried here, so a caller can never present the second as the
   * first. */
  reason: NerReason;
  detail: string;
}

export function refusal(reason: NerReason, detail: string): NerOutcome {
  return { entities: [], reason, detail };
}

// ---------------------------------------------------------------------------
// Label mapping
// ---------------------------------------------------------------------------

/**
 * CoNLL-2003 tag → the redactor's category, or null for "do not mask".
 *
 * Accepts both the grouped form ("PER") and a raw BIO tag ("B-PER"), because
 * a caller that passes aggregation_strategy:"none" gets the latter.
 */
export function mapNerLabel(label: unknown): LocalEntityCategory | null {
  if (typeof label !== "string") return null;
  const bare = label.replace(/^[BIESO]-/, "").toUpperCase();
  if (bare === "PER") return "person";
  if (bare === "ORG") return "organization";
  if (bare === "LOC") return "address";
  // MISC and O deliberately unmapped — see NER_DEFAULT_THRESHOLDS.
  return null;
}

// ---------------------------------------------------------------------------
// The verbatim resolver — the rule the header is about
// ---------------------------------------------------------------------------

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Find `span` in `source` and return THE SOURCE'S OWN characters for it, or
 * null when it isn't really there.
 *
 * Two passes, in decreasing strictness:
 *   1. An exact substring. The common case, and the only one the chat-model
 *      path in redactor.ts uses.
 *   2. A whitespace-tolerant match: the span's whitespace-separated pieces,
 *      each escaped, joined by `\s*`. This recovers the decode artefacts
 *      described in the header ("O ' Brien" for O'Brien, a token join that
 *      inserted or dropped a space) WITHOUT loosening the guarantee, because
 *      the value returned is the matched slice of `source` — still text the
 *      person actually wrote. `\s*` can only span whitespace, so it cannot
 *      reach across unrelated words.
 *
 * Anything else returns null and the span is dropped. A hallucinated,
 * normalized or expanded span therefore cannot reach the text.
 */
export function resolveVerbatimSpan(source: string, span: unknown): string | null {
  if (typeof source !== "string" || typeof span !== "string") return null;
  const trimmed = span.trim();
  // Under two characters is noise; over 120 is a model returning half the
  // message as one "name". Both bounds match redactor.ts's existing chat-path
  // check so the two paths cannot disagree about what a plausible span is.
  if (trimmed.length < 2 || trimmed.length > 120) return null;
  if (source.includes(trimmed)) return trimmed;
  const pieces = trimmed.split(/\s+/).filter((piece: string) => piece.length > 0);
  if (pieces.length === 0) return null;
  try {
    const pattern = new RegExp(pieces.map(escapeRegExp).join("\\s*"));
    const match = pattern.exec(source);
    if (!match || typeof match[0] !== "string") return null;
    const resolved = match[0];
    if (resolved.length < 2 || resolved.length > 120) return null;
    // Belt and braces: the thing we are about to hand back must be in the
    // source. It is, by construction — assert it anyway, because this is the
    // one invariant in this file that must never be wrong.
    return source.includes(resolved) ? resolved : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/**
 * Split on paragraph then line then hard character boundaries, so no piece
 * exceeds MAX_CHUNK_CHARS.
 *
 * Why split at all: the tokenizer truncates silently at the model's window,
 * and a truncated tail is an UNMASKED NAME — the one failure this feature
 * cannot have. Splitting on blank lines first keeps sentences (and therefore
 * the casing/context the tagger relies on) intact where possible.
 *
 * Exported for the test that pins the no-silent-truncation behaviour.
 */
export function chunkForNer(text: string, maxChars: number = MAX_CHUNK_CHARS): string[] {
  if (typeof text !== "string" || text.length === 0) return [];
  const limit = Math.max(64, Math.floor(maxChars) || MAX_CHUNK_CHARS);
  const out: string[] = [];
  const pushSplit = (piece: string, separator: string | null): void => {
    if (piece.length === 0) return;
    if (piece.length <= limit) {
      out.push(piece);
      return;
    }
    if (separator === "\n\n") {
      for (const part of piece.split("\n")) pushSplit(part, "\n");
      return;
    }
    if (separator === "\n") {
      for (let i = 0; i < piece.length; i += limit) pushSplit(piece.slice(i, i + limit), null);
      return;
    }
    for (let i = 0; i < piece.length; i += limit) out.push(piece.slice(i, i + limit));
  };
  for (const paragraph of text.split("\n\n")) pushSplit(paragraph, "\n\n");
  return out.filter((piece: string) => piece.trim().length > 0);
}
