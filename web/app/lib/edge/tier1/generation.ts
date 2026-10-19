// One streaming completion, the generation deadlines, the tool-call grammar
// and the warm-only structured-JSON primitive. Split out of ../tier1.ts.

import type {
  ChatCompletionChunk,
  ChatCompletionMessageParam,
  ChatCompletionRequestStreaming,
  MLCEngineInterface,
} from "@mlc-ai/web-llm";
import { edgeEnabled } from "../capabilities";
import { tier1Engine } from "./engineState";
import { Tier1Error } from "./errors";
import { logTier1Event } from "./log";
import {
  MULTI_CHAR_HOLD_BACK_OPENERS,
  TOOL_CALL_TAG_CLOSE,
  TOOL_CALL_TAG_OPEN,
  looksLikeAttemptedToolCall,
  parseToolCall,
} from "./toolProtocol";

/**
 * Bug fix (2026-08-06 follow-up): withEngineLoadDeadline above only bounds
 * engine LOAD — the actual per-turn generation call below had no ceiling at
 * all (a stalled shader/kernel, or a GPU that quietly stopped producing
 * tokens, previously hung the same call chain withEngineLoadDeadline was
 * written to unblock: runTurn -> createTier1Session's prompt() -> the tier
 * router -> sendViaEdge). Applied two ways, same Promise.race-with-deadline
 * shape as withEngineLoadDeadline (not cancelling the underlying WebLLM
 * call — this API gives no way to force that — only ending the caller's
 * wait): a fixed bound on obtaining the first response from
 * engine.chat.completions.create(), and a per-chunk idle ceiling during
 * iteration that resets on every emitted chunk, so a legitimately long reply
 * that keeps producing tokens is never punished — only a real stall trips it.
 */
const TIER1_GENERATION_IDLE_TIMEOUT_MS = 30_000;

/**
 * Phase 2 fix (2026-08-06): distinct from the idle timeout above. Idle means
 * "no new chunk arrived for N seconds" — it resets on every chunk, so a
 * pathological stream that keeps emitting a chunk just under every 30s would
 * satisfy it forever and never time out. This is a hard wall-clock ceiling on
 * the WHOLE turn — the sum of every streamCompletion() call across every
 * tool-round inside one runTurn() (up to MAX_TOOL_ROUNDS + 1 calls) — cutting
 * the turn once it's run this long in total, regardless of whether individual
 * chunks kept trickling in.
 */
export const TIER1_TURN_CEILING_MS = 120_000;

export function withGenerationStartDeadline<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(
        new Tier1Error(
          "generation-timeout",
          "The on-device model stopped responding.",
          "generation-start-timeout"
        )
      );
    }, ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

/** Wraps a chunk stream so a stall — no new chunk within `idleMs` of the last
 * one (or of iteration start) — fails fast instead of hanging forever, via a
 * fresh Promise.race per `iterator.next()` call (reset each iteration, unlike
 * withEngineLoadDeadline's single fixed deadline). `onIdleTimeout` is a
 * best-effort nudge (engine.interruptGenerate()) to try to actually stop the
 * underlying generation; either way the timeout itself ends the caller's
 * wait and the router's existing fall-through takes over. */
async function* withGenerationIdleTimeout<T>(
  source: AsyncIterable<T>,
  idleMs: number,
  onIdleTimeout: () => void
): AsyncGenerator<T> {
  if (idleMs <= 0) {
    yield* source;
    return;
  }
  const iterator = source[Symbol.asyncIterator]();
  for (;;) {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        onIdleTimeout();
        reject(
          new Tier1Error(
            "generation-timeout",
            "The on-device model stopped responding.",
            "generation-idle-timeout"
          )
        );
      }, idleMs);
    });
    let result: IteratorResult<T>;
    try {
      result = await Promise.race([iterator.next(), deadline]);
    } finally {
      if (timer) clearTimeout(timer);
    }
    if (result.done) return;
    yield result.value;
  }
}

/**
 * One streaming completion. When tools are active, the start of the reply is
 * held back until it's clearly NOT a tool-call envelope ('{' or a code
 * fence): plain replies stream through live; envelope-shaped replies are
 * buffered whole (and, if they turn out to be plain text after all, emitted
 * as one delta by the caller-visible flush below). Deltas forwarded to
 * onToken always concatenate to the returned string.
 */
export async function streamCompletion(
  engine: MLCEngineInterface,
  messages: ChatCompletionMessageParam[],
  toolsActive: boolean,
  onToken?: (delta: string) => void,
  signal?: AbortSignal,
  // Remaining turn-ceiling budget (see TIER1_TURN_CEILING_MS), passed down
  // from runTurn so a single very-long call can't outlive the ceiling on its
  // own — the effective idle timeout used below is whichever is SMALLER: the
  // normal idle window, or what's left of the turn's total budget.
  remainingBudgetMs?: number
): Promise<string> {
  // Verify-pass fix: precisely tracks "is the shared engine object currently
  // being read" (incremented for the exact span this function is calling
  // engine.chat.completions.create, decremented in the finally below on
  // every exit path) - see waitForEngineReadersIdle's doc comment for why
  // this replaced the broader per-turn getEdgeActivity() signal a reload
  // used to wait on (that signal was already active for THIS turn's own
  // call by the time it reached acquireEngine for a mid-conversation model
  // switch, so waiting on it could mean waiting on itself).
  tier1Engine.readerCount += 1;
  try {
    return await streamCompletionInner(engine, messages, toolsActive, onToken, signal, remainingBudgetMs);
  } finally {
    tier1Engine.readerCount = Math.max(0, tier1Engine.readerCount - 1);
  }
}

async function streamCompletionInner(
  engine: MLCEngineInterface,
  messages: ChatCompletionMessageParam[],
  toolsActive: boolean,
  onToken?: (delta: string) => void,
  signal?: AbortSignal,
  remainingBudgetMs?: number
): Promise<string> {
  const effectiveIdleMs =
    remainingBudgetMs != null
      ? Math.min(TIER1_GENERATION_IDLE_TIMEOUT_MS, remainingBudgetMs)
      : TIER1_GENERATION_IDLE_TIMEOUT_MS;
  // Triggered-tag constrained decoding (2026-09-12). The objection recorded
  // below — that a hard schema would force plain-text answers into JSON shape
  // and break ordinary conversation — is specific to a WHOLE-response
  // constraint. A triggered tag is not one: nothing is constrained until the
  // model emits `<tool_call>`, and everything outside a tag is free text.
  // Verified against the installed bundle's own xgrammar (run out of
  // node_modules on 2026-09-12, Grammar.fromStructuralTag +
  // Testings.isGrammarAcceptString): plain prose, prose containing a stray
  // "<", prose FOLLOWED by a call, and two calls in one reply are all
  // accepted; `<tool_call>nope</tool_call>` and a body missing `name` are
  // rejected. So the JSON inside a tag can no longer be malformed, which is
  // the parse failure the repair below exists for.
  const request: ChatCompletionRequestStreaming = { stream: true, messages };
  if (toolsActive) request.response_format = TOOL_CALL_RESPONSE_FORMAT;
  const rawChunks = await withGenerationStartDeadline(
    engine.chat.completions.create(request),
    effectiveIdleMs
  );
  const chunks = withGenerationIdleTimeout(
    rawChunks as AsyncIterable<ChatCompletionChunk>,
    effectiveIdleMs,
    () => {
      try {
        // interruptGenerate() is synchronous (void), unlike the manual
        // abort() path below which awaits it — best-effort either way.
        engine.interruptGenerate();
      } catch {
        // Best-effort.
      }
    }
  );
  let full = "";
  let decided = !toolsActive; // no tools → stream everything immediately
  let holdingBack = false;
  const abort = async () => {
    try {
      // Typed void on MLCEngineInterface, but MLCEngine's implementation returns
      // Promise<void> - Promise.resolve() keeps awaiting that at runtime.
      await Promise.resolve(engine.interruptGenerate());
    } catch {
      // Best-effort.
    }
    throw new Tier1Error("aborted", "On-device reply was stopped.");
  };
  for await (const chunk of chunks) {
    if (signal?.aborted) await abort();
    const delta = chunk.choices?.[0]?.delta?.content;
    if (!delta) continue;
    full += delta;
    if (!decided) {
      const lead = full.trimStart();
      if (!lead) continue; // still whitespace — undecided
      // A lead shorter than a multi-character opener may still be GROWING
      // into it. Deciding now would flush "<" (or a lone "`") to the person
      // and then stream the whole envelope behind it — exactly what this
      // hold-back prevents for the single-character "{" shape. Added with
      // <tool_call>; "```" had the same hole, reachable whenever a chunk
      // carries fewer than three characters.
      if (MULTI_CHAR_HOLD_BACK_OPENERS.some((p) => lead.length < p.length && p.startsWith(lead))) continue;
      decided = true;
      holdingBack = lead.startsWith("{") || MULTI_CHAR_HOLD_BACK_OPENERS.some((p) => lead.startsWith(p));
      if (!holdingBack && onToken) onToken(full); // flush everything so far
    } else if (!holdingBack && onToken) {
      onToken(delta);
    }
  }
  if (signal?.aborted) await abort();
  if (!decided && onToken && full) {
    // The entire reply was shorter than an opener it might have been growing
    // into, so the loop above never flushed it. Deliver it once here rather
    // than silently dropping it from the stream (the return value was always
    // correct; the person's view was not).
    decided = true;
    onToken(full);
  }
  if (holdingBack && parseToolCall(full) === null) {
    // Looked like a tool-call envelope, wasn't valid JSON — one bounded,
    // schema-constrained retry (2026-08-06, tool-router reliability
    // research) before giving up. WebLLM's response_format JSON-schema mode
    // is model-agnostic constrained decoding at the WASM level (masks
    // invalid tokens each step), separate from and more reliable than its
    // still-WIP native tool-calling (which only works on a handful of
    // models, none in this file's manifest) — see this repo's own comment
    // on toolProtocolInstructions above for why the hand-rolled envelope
    // exists instead of native `tools`. Retry is a fresh non-streamed call
    // (we already know the exact shape needed; no token-by-token delivery
    // required for one retry attempt).
    //
    // NARROWED 2026-09-12, and this matters: the retry does not "repair"
    // text, it GENERATES a fresh, grammar-forced tool-call envelope and, if
    // that parses, returns it INSTEAD of what the model actually said. On any
    // `{`-leading reply that was simply JSON the person asked for, that
    // silently replaces their answer with a fabricated tool call. The
    // original justification for accepting that risk was that unparseable
    // envelope-shaped output probably WAS a botched tool call — a premise the
    // triggered structural tag above has now removed for the tag shape, since
    // the grammar makes a malformed body unreachable. So the retry now
    // requires evidence the model was actually attempting the protocol.
    if (looksLikeAttemptedToolCall(full)) {
      try {
        const retry = await withGenerationStartDeadline(
          engine.chat.completions.create({
            messages,
            // `schema` is a JSON **string**, not an object. The package DOES type
            // it (node_modules/@mlc-ai/web-llm/lib/openai_api_protocols/
            // chat_completion.d.ts:804-812 declares `schema?: string`), and the
            // engine's own internal use of the field passes a template string
            // (lib/index.js:9478, officialHermes2FunctionCallSchemaArray). The
            // cast that used to sit here — justified by a comment claiming the
            // field was untyped — is what let an object through: it reaches
            // xgrammar's embind binding (lib/index.js:8816 →
            // handle.CompileJSONSchema) which rejects any non-string with
            // `BindingError: Cannot pass non-string to std::string`
            // (lib/index.js:8314). Verified by running the installed bundle's own
            // xgrammar out of node_modules on 2026-09-12: the object form throws
            // that BindingError, the stringified form compiles. The cast is gone
            // so tsc, not a comment, keeps this a string from here on.
            response_format: { type: "json_object", schema: TOOL_CALL_ENVELOPE_SCHEMA_JSON },
          }),
          TIER1_GENERATION_IDLE_TIMEOUT_MS
        );
        const retryText = retry?.choices?.[0]?.message?.content;
        if (retryText && parseToolCall(retryText) !== null) {
          if (onToken) onToken(retryText);
          return retryText;
        }
        // The repair ran and still produced nothing parseable. Logged for the
        // same reason as the catch below: the only other exit from here is the
        // plain-prose fall-through, which looks identical to never having tried.
        logTier1Event({ phase: "tool-call-repair-unparsed", chars: retryText ? retryText.length : 0 });
      } catch (err) {
        // Fall through to the exact pre-existing behavior below (deliver the
        // malformed text as plain prose) — but say what happened first. Until
        // 2026-09-12 this catch was empty, and because of the schema-type bug
        // above it caught EVERY attempt, so the repair path was dead and
        // unobservably so.
        logTier1Event({
          phase: "tool-call-repair-failed",
          error: err instanceof Error ? `${err.name}: ${err.message}` : String(err),
        });
      }
    }
    if (onToken) onToken(full);
  }
  return full;
}

/** JSON Schema for the hand-rolled tool-call envelope (see
 * toolProtocolInstructions above for the matching prose instruction) — used
 * ONLY for the bounded retry above, never on the first attempt. Deliberately
 * permissive on `arguments` (schema-validating each tool's actual argument
 * shape is executeEdgeTool's job server-side, the real trust boundary per
 * this file's header — this schema only guarantees valid, well-shaped JSON,
 * never a security boundary on its own). */
const TOOL_CALL_ENVELOPE_SCHEMA = {
  type: "object",
  properties: {
    tool_call: {
      type: "object",
      properties: {
        name: { type: "string" },
        arguments: { type: "object" },
      },
      required: ["name"],
    },
  },
  required: ["tool_call"],
};

/** The same schema in the form the engine actually accepts — a JSON string
 * (see the repair call above for the evidence). Stringified once at module
 * load rather than per call, which also keeps the engine's grammar-matcher
 * cache key stable across retries (lib/index.js:10176 getResponseFormatKey
 * returns `schema` verbatim for json_object, and a matching key skips a
 * recompile at lib/index.js:10260). */
const TOOL_CALL_ENVELOPE_SCHEMA_JSON = JSON.stringify(TOOL_CALL_ENVELOPE_SCHEMA);

/** The body of a <tool_call> block — Hermes/Qwen's trained shape, and the only
 * thing the structural tag constrains. `arguments` is left as a bare
 * `{"type":"object"}`, which xgrammar treats as "any object": measured on the
 * installed bundle (2026-09-12), that form accepts string, number, boolean,
 * null, array, nested-object and multi-key argument objects, while a body that
 * is not JSON, or that omits `name`, is rejected. Per-tool argument validation
 * remains executeEdgeTool's job server-side and runLocalTool's in-page — this
 * grammar guarantees well-formed JSON, never a security boundary. */
const TOOL_CALL_BODY_SCHEMA = {
  type: "object",
  properties: {
    name: { type: "string" },
    arguments: { type: "object" },
  },
  required: ["name", "arguments"],
};

/**
 * The tool protocol as a triggered structural tag, passed on every tool-active
 * streaming turn.
 *
 * TWO tags, one trigger, deliberately. The delimiters are matched EXACTLY —
 * measured on the installed bundle (2026-09-12): with only
 * begin `<tool_call>` / end `</tool_call>`, the Hermes-native
 * `<tool_call>\n{…}\n</tool_call>` is REJECTED, and with only the newline
 * variant the compact form is. Listing both means whichever shape a model's
 * fine-tune prefers is reachable, instead of the grammar masking it into the
 * other one.
 *
 * The structural_tag field takes an object (unlike `schema` above, which must
 * be a string): the engine stringifies it for us at lib/index.js:8397
 * structuralTagArgsToJSONString, and keys its grammar cache on that string at
 * lib/index.js:10176, so one module-level constant means one compile per
 * engine rather than one per turn.
 */
const TOOL_CALL_RESPONSE_FORMAT = {
  type: "structural_tag" as const,
  structural_tag: {
    type: "structural_tag",
    format: {
      type: "triggered_tags",
      triggers: [TOOL_CALL_TAG_OPEN],
      tags: [
        {
          type: "tag",
          begin: `${TOOL_CALL_TAG_OPEN}\n`,
          end: `\n${TOOL_CALL_TAG_CLOSE}`,
          content: { type: "json_schema", json_schema: TOOL_CALL_BODY_SCHEMA },
        },
        {
          type: "tag",
          begin: TOOL_CALL_TAG_OPEN,
          end: TOOL_CALL_TAG_CLOSE,
          content: { type: "json_schema", json_schema: TOOL_CALL_BODY_SCHEMA },
        },
      ],
    },
  },
};

// ---------------------------------------------------------------------------
// Warm-only structured generation (constrained decoding as a primitive)
//
// WHY THIS EXISTS, separately from runTurn: the tool loop above uses
// response_format only as a bounded REPAIR step after a parse failure,
// because forcing JSON on every conversational turn would break plain
// prose. Other on-device jobs are the exact opposite — the caller wants a
// data structure and nothing else (today: redactor.ts's entity pass before a
// hosted send). For those, schema-constrained decoding is the whole point:
// the WASM-level token mask makes a malformed reply impossible, which is
// what lets a 1-3B model do the job at all.
//
// THE HARD RULE, per E-0b ("routeTurn never downloads"): this NEVER loads a
// model. It runs only when an engine for the given model is already warm in
// memory, and answers null otherwise. A background job must never be the
// reason a person pays a multi-GB download or a cold WebGPU warm-up.
// ---------------------------------------------------------------------------

/** Ceiling for one structured call. Deliberately well under the 30s
 * generation deadline used for user-facing turns: these calls sit in front of
 * something else the person is waiting for (a hosted send), so a slow device
 * must degrade to "no structured result" quickly rather than add seconds of
 * silence. */
export const TIER1_STRUCTURED_TIMEOUT_MS = 12_000;

export interface Tier1StructuredOptions {
  /** System prompt for the one-shot call (no session, no history — these
   * calls are stateless by construction so they can never pollute a
   * conversation's context). */
  systemPrompt?: string;
  signal?: AbortSignal;
  timeoutMs?: number;
}

/**
 * One stateless, schema-constrained completion on the ALREADY-WARM tier-1
 * engine. Resolves the parsed JSON value, or null when: no engine is warm,
 * the engine build doesn't support response_format, the call times out or
 * aborts, or the reply doesn't parse. Never rejects, never downloads, never
 * touches any chat session.
 */
export async function tier1StructuredJson(
  prompt: string,
  schema: Record<string, unknown>,
  options: Tier1StructuredOptions = {}
): Promise<unknown | null> {
  if (typeof window === "undefined" || !edgeEnabled()) return null;
  // Warm-only gate: read the singleton directly rather than via
  // isTier1EngineWarm(modelId) — the caller has no reason to know which
  // model happens to be loaded, and any warm model can do this job.
  const engine = tier1Engine.instance;
  if (!engine || !tier1Engine.modelId || tier1Engine.tearingDown !== null || tier1Engine.inFlight !== null) return null;
  if (options.signal?.aborted) return null;
  try {
    const messages = [
      ...(options.systemPrompt ? [{ role: "system" as const, content: options.systemPrompt }] : []),
      { role: "user" as const, content: prompt },
    ];
    const res = await withGenerationStartDeadline(
      engine.chat.completions.create({
        messages,
        // Same field, same bug, same fix as the tool-call repair above:
        // `schema` must be a JSON string. This call site had it too, so
        // redactor.ts's entity pass has never produced a structured result
        // either — it hit the identical BindingError and returned null.
        response_format: { type: "json_object", schema: JSON.stringify(schema) },
      }),
      options.timeoutMs ?? TIER1_STRUCTURED_TIMEOUT_MS
    );
    if (options.signal?.aborted) return null;
    const text = res?.choices?.[0]?.message?.content;
    if (!text) return null;
    return JSON.parse(text);
  } catch (err) {
    // Unsupported engine build, timeout, abort, or unparseable output — the
    // caller's documented "no structured result" path, never an error. Logged
    // since 2026-09-12 for the same reason as the tool-call repair's catch:
    // a null here is indistinguishable from "no engine was warm", so a
    // permanently broken call looked exactly like a quiet, correct skip.
    logTier1Event({
      phase: "structured-json-failed",
      error: err instanceof Error ? `${err.name}: ${err.message}` : String(err),
    });
    return null;
  }
}

/** True when tier1StructuredJson could actually run right now (an engine is
 * warm). Synchronous and cheap — callers use it to skip building a prompt
 * they can't use. */
export function tier1StructuredAvailable(): boolean {
  return tier1Engine.instance !== null && tier1Engine.tearingDown === null && tier1Engine.inFlight === null;
}
