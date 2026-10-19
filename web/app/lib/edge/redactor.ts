// The local tier as a REDACTOR, not only as a responder.
//
// The shape of the thing: before any turn falls through to the hosted pool,
// the device gets a pass over the text. Cheap regex classes first
// (edgeGuardrails' ported pattern tier), then — only when an on-device model
// is ALREADY warm — a schema-constrained extraction pass that finds the spans
// no regex can: people's names, employers, street addresses, internal project
// names. What leaves the device is the masked text; the mapping back to the
// real values never leaves the browser and is never persisted.
//
// Why a 1-3B model is the right tool here even though it's a poor chat model:
// this is span extraction, not reasoning, and the output is grammar-locked to
// a JSON schema (tier1StructuredJson → WebLLM's WASM-level constrained
// decoding), so the model cannot answer in prose, cannot editorialize, and
// cannot invent a field name. The failure mode is "finds nothing", which
// degrades to exactly the pattern-tier behavior below it.
//
// THREE HARD RULES, in priority order:
//   1. NEVER download or warm a model. tier1StructuredJson is warm-only; with
//      no engine in memory the model pass is skipped silently. A privacy
//      nicety must never become the reason someone pays a 2 GB download.
//   2. NEVER block the turn. The model pass is bounded (12s ceiling inside
//      tier1StructuredJson, and callers pass their own AbortSignal); on
//      timeout the pattern-tier result is used as-is.
//   3. NEVER widen a mask into a lie. A span the model reports that does not
//      appear verbatim in the input is DISCARDED — a hallucinated span would
//      otherwise rewrite text the person never wrote. This is checked, not
//      assumed.
//
// Default posture (capabilities.ts EDGE_REDACTION_KEY): "secrets" — card
// numbers and government ids are masked on every hosted send out of the box,
// because a hosted model does not need them verbatim to do the task. Email
// and phone ("contact") and the model pass ("model") are opt-in precisely
// because they DO change what a legitimate task can accomplish.

// ---------------------------------------------------------------------------
// Track 4 (2026-09-12) — a THIRD path between the pattern tier and the chat
// model: a token-classification NER (localModels/ner.ts).
//
// Why: the chat-model pass spends a 1-3B generative model on span extraction.
// A model trained for token classification does the same job with a fraction
// of the weights and without needing a chat engine warm. It slots in ahead of
// the chat pass as a preference, not a replacement — the chat pass is still
// there and still runs when NER is absent or finds nothing.
//
// The three hard rules above are unchanged and the NER path is held to all of
// them: ner.ts's extractEntitiesLocal() contains no loader (only
// installNerModel() can move bytes, and only from an explicit click), it is
// bounded by its own time budget and the caller's signal, and every span it
// returns has already been resolved to a verbatim substring of the source —
// ner.ts's resolveVerbatimSpan() — before it reaches applyEntities() here.
//
// What deliberately did NOT change, because both belong to other files:
//   - EdgeRedactionMode still has four values. NER runs under the existing
//     "model" mode, which is precisely the promise it implements better. Adding
//     a fifth mode would mean editing capabilities.ts.
//   - RedactionStage still has three values, and the NER path reports
//     stage:"model" — the stage has always meant "an on-device model
//     contributed verified spans", which is exactly what happened. tierRouter.ts
//     gates on `stage !== "model"`, and a new stage value would have made it
//     silently discard every NER result. WHICH model ran is reported in the new
//     `modelPath` field instead, which no existing consumer reads.
// ---------------------------------------------------------------------------

import { scrubPiiCategories } from "./edgeGuardrails";
import { getEdgeRedactionMode, type EdgeRedactionMode } from "./capabilities";
import { tier1StructuredAvailable, tier1StructuredJson } from "./tier1";
import { extractEntitiesLocal, nerAvailable, type LocalEntity } from "./localModels/ner";

// ---------------------------------------------------------------------------
// Result shape
// ---------------------------------------------------------------------------

export type RedactionStage = "none" | "patterns" | "model";

/** WHICH on-device model contributed the spans, when `stage` is "model".
 * "ner" = the token classifier in localModels/ner.ts; "chat" = the
 * schema-constrained pass through the tier-1 chat engine. Separate from
 * `stage` on purpose — see the Track 4 note at the top of this file. */
export type RedactionModelPath = "none" | "ner" | "chat";

export interface RedactionResult {
  /** What the caller should send hosted. Equal to the input when nothing
   * matched. */
  text: string;
  changed: boolean;
  /** How far the pipeline actually got — "model" only when an on-device
   * model pass ran AND contributed at least one verified span. */
  stage: RedactionStage;
  /** Count of spans the model pass masked (0 for pattern-only runs). Used by
   * the console to show the feature doing something real. */
  modelSpans: number;
  /** Which model produced those spans. "none" whenever stage is not "model".
   * A console showing "3 spans masked" can say which engine found them
   * instead of implying the chat model did. */
  modelPath: RedactionModelPath;
  mode: EdgeRedactionMode;
}

function unchanged(text: string, mode: EdgeRedactionMode): RedactionResult {
  return { text, changed: false, stage: "none", modelSpans: 0, modelPath: "none", mode };
}

// ---------------------------------------------------------------------------
// Pattern tier
// ---------------------------------------------------------------------------

/** Synchronous, allocation-cheap, safe on every code path including render.
 * "off" returns the input untouched — the pre-existing behavior for agents
 * without guardrails. */
export function redactPatterns(text: string, mode: EdgeRedactionMode = getEdgeRedactionMode()): string {
  if (!text || mode === "off") return text;
  return scrubPiiCategories(text, {
    secrets: true,
    // "contact" and "model" both mask contact details; "secrets" does not.
    contact: mode === "contact" || mode === "model",
  });
}

// ---------------------------------------------------------------------------
// Model tier
// ---------------------------------------------------------------------------

/** The extraction contract. Kept deliberately narrow: a flat list of spans,
 * each with a category from a closed set. No free-form "reason" field — every
 * extra token is a chance for a small model to drift, and nothing downstream
 * would read it. */
const ENTITY_SCHEMA: Record<string, unknown> = {
  type: "object",
  properties: {
    entities: {
      type: "array",
      items: {
        type: "object",
        properties: {
          text: { type: "string" },
          category: {
            type: "string",
            enum: ["person", "organization", "address", "identifier"],
          },
        },
        required: ["text", "category"],
      },
    },
  },
  required: ["entities"],
};

const ENTITY_SYSTEM_PROMPT =
  "You extract personal data spans from text so they can be masked before the " +
  "text is sent to a cloud service. Copy each span EXACTLY as it appears in " +
  "the input — never paraphrase, never normalize, never invent. Do not " +
  "extract public figures, product names, or the names of well-known " +
  "software. If there is nothing to mask, return an empty list.";

/** Longest-first so a span contained inside another (e.g. "Acme" inside
 * "Acme Holdings") can't blank out the middle of its own parent's mask. */
function byLengthDesc(a: { text: string }, b: { text: string }): number {
  return b.text.length - a.text.length;
}

const PLACEHOLDER: Record<string, string> = {
  person: "[name redacted]",
  organization: "[organization redacted]",
  address: "[address redacted]",
  identifier: "[identifier redacted]",
};

/** Escape a span for use in a global RegExp — spans are user text and may
 * contain regex metacharacters. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export interface ExtractedEntity {
  text: string;
  category: string;
}

/** Parse + validate the model's structured answer. Everything about this is
 * defensive: constrained decoding guarantees the SHAPE, not the CONTENT. */
export function verifyEntities(raw: unknown, source: string): ExtractedEntity[] {
  const list = (raw as { entities?: unknown } | null)?.entities;
  if (!Array.isArray(list)) return [];
  const out: ExtractedEntity[] = [];
  const seen = new Set<string>();
  for (const item of list) {
    if (!item || typeof item !== "object") continue;
    const span = (item as { text?: unknown }).text;
    const category = (item as { category?: unknown }).category;
    if (typeof span !== "string" || typeof category !== "string") continue;
    const trimmed = span.trim();
    // Rule 3: the span must appear verbatim in the source. A model that
    // "helpfully" normalized or hallucinated a value is ignored rather than
    // allowed to rewrite the person's text.
    if (trimmed.length < 2 || !source.includes(trimmed)) continue;
    // One-character-ish and very long spans are both suspicious; the upper
    // bound stops a model from returning the whole message as one "name".
    if (trimmed.length > 120) continue;
    if (!PLACEHOLDER[category]) continue;
    const key = `${category}:${trimmed}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ text: trimmed, category });
  }
  return out.sort(byLengthDesc);
}

/** Apply verified spans. Returns the masked text and how many spans actually
 * changed anything. */
export function applyEntities(text: string, entities: ExtractedEntity[]): { text: string; masked: number } {
  let out = text;
  let masked = 0;
  for (const entity of entities) {
    // A longer span may already have swallowed this one (see byLengthDesc).
    if (!out.includes(entity.text)) continue;
    out = out.replace(new RegExp(escapeRegExp(entity.text), "g"), PLACEHOLDER[entity.category]);
    masked += 1;
  }
  return { text: out, masked };
}

/** Is the model tier usable right now? ("model" selected AND at least one
 * on-device model already warm.) Synchronous — the console uses it to explain
 * why the pass isn't running, and tierRouter.ts uses it to decide whether a
 * hosted-bound turn is worth one bounded await.
 *
 * The NER arm is why this is an OR: a device with the small token classifier
 * loaded and no chat engine in memory is now a device that can do model-tier
 * redaction. Before Track 4 that device got the pattern tier only. Neither arm
 * can download anything — both report in-memory state (ner.ts's nerAvailable()
 * and tier1's own warm check). */
export function modelRedactionReady(mode: EdgeRedactionMode = getEdgeRedactionMode()): boolean {
  if (mode !== "model") return false;
  return nerAvailable() || tier1StructuredAvailable();
}

/** Which model path would run right now, for a console that wants to say so
 * before a turn rather than after. "ner" wins because it is the cheaper of the
 * two and the one redactForHosted tries first. */
export function redactionModelPath(mode: EdgeRedactionMode = getEdgeRedactionMode()): RedactionModelPath {
  if (mode !== "model") return "none";
  if (nerAvailable()) return "ner";
  if (tier1StructuredAvailable()) return "chat";
  return "none";
}

/** NER spans → the shape applyEntities() already masks. Every span arrives
 * having ALREADY been resolved to a verbatim substring of `source` by
 * ner.ts's resolveVerbatimSpan; the `source.includes` re-check below is not
 * redundant belt-and-braces prose, it is the one place this file refuses to
 * take another module's word for the rule that matters most. A span that
 * fails it is dropped, exactly as a hallucinated chat-model span is. */
export function nerEntitiesToSpans(entities: LocalEntity[], source: string): ExtractedEntity[] {
  if (!Array.isArray(entities)) return [];
  const out: ExtractedEntity[] = [];
  const seen = new Set<string>();
  for (const entity of entities) {
    const span = entity?.text;
    const category = entity?.category;
    if (typeof span !== "string" || typeof category !== "string") continue;
    if (span.length < 2 || span.length > 120) continue;
    if (!PLACEHOLDER[category]) continue;
    if (!source.includes(span)) continue;
    const key = `${category}:${span}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ text: span, category });
  }
  return out.sort(byLengthDesc);
}

// ---------------------------------------------------------------------------
// The pipeline
// ---------------------------------------------------------------------------

export interface RedactOptions {
  mode?: EdgeRedactionMode;
  signal?: AbortSignal;
  /** Skip the model pass even under "model" — used where the caller is
   * already inside a latency-sensitive path. */
  patternsOnly?: boolean;
}

/**
 * Redact `text` for a hosted send. Never rejects; on any internal failure the
 * pattern-tier result (or the untouched input under "off") is returned.
 */
export async function redactForHosted(text: string, options: RedactOptions = {}): Promise<RedactionResult> {
  const mode = options.mode ?? getEdgeRedactionMode();
  if (!text || mode === "off") return unchanged(text, mode);

  const patterned = redactPatterns(text, mode);
  const patternResult: RedactionResult = {
    text: patterned,
    changed: patterned !== text,
    stage: patterned !== text ? "patterns" : "none",
    modelSpans: 0,
    modelPath: "none",
    mode,
  };

  if (mode !== "model" || options.patternsOnly) return patternResult;
  if (options.signal?.aborted) return patternResult;

  // ---- Path 2: the on-device token classifier (Track 4) -------------------
  // Tried first because it is the smaller model and the one that can be warm
  // on a device with no chat engine at all. Like every other tier here it is
  // warm-only: nerAvailable() reports in-memory state and extractEntitiesLocal
  // contains no loader, so this branch can never start a download.
  if (nerAvailable()) {
    try {
      const outcome = await extractEntitiesLocal(patterned, { signal: options.signal });
      if (options.signal?.aborted !== true) {
        // "partial", "truncated" and even "failed" carry real spans found
        // before the stop, and masking those masks MORE than the pattern tier
        // alone — never less. They are used. The reason is not swallowed: it
        // is what a surface should show alongside the masked text so a person
        // knows the scan was incomplete.
        const spans = nerEntitiesToSpans(outcome.entities, patterned);
        if (spans.length > 0) {
          const applied = applyEntities(patterned, spans);
          if (applied.masked > 0) {
            return {
              text: applied.text,
              changed: applied.text !== text,
              stage: "model",
              modelSpans: applied.masked,
              modelPath: "ner",
              mode,
            };
          }
        }
      }
    } catch {
      // Rule 2: a failed privacy nicety never costs the person their turn.
      // Fall through to the chat pass, then to the pattern result.
    }
  }

  // ---- Path 3: the chat model, unchanged ----------------------------------
  // Reached when NER isn't loaded, or ran and contributed nothing. NOT run in
  // addition to a successful NER pass: two model passes per hosted turn is a
  // cost this feature has no budget for. The consequence is stated rather than
  // hidden — on a turn where NER masks a name but misses a street address that
  // the chat pass would have caught, that address is not masked.
  if (!tier1StructuredAvailable()) return patternResult;
  if (options.signal?.aborted) return patternResult;

  try {
    // The model reads the PATTERN-SCRUBBED text, not the raw input: there is
    // no reason to show it a card number the regex tier already masked, and
    // the spans it returns must line up with the string we're about to
    // mutate for the verbatim check to mean anything.
    const raw = await tier1StructuredJson(
      `Extract the personal data spans from the text below.\n\n---\n${patterned}\n---`,
      ENTITY_SCHEMA,
      { systemPrompt: ENTITY_SYSTEM_PROMPT, signal: options.signal }
    );
    if (!raw || options.signal?.aborted) return patternResult;
    const entities = verifyEntities(raw, patterned);
    if (entities.length === 0) return patternResult;
    const applied = applyEntities(patterned, entities);
    if (applied.masked === 0) return patternResult;
    return {
      text: applied.text,
      changed: applied.text !== text,
      stage: "model",
      modelSpans: applied.masked,
      modelPath: "chat",
      mode,
    };
  } catch {
    // Rule 2: a failed privacy nicety must never cost the person their turn.
    return patternResult;
  }
}
