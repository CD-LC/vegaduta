// Local-code Q&A — the "ask" leg of the browser-local code assistant
// (web/app/lib/localcode/). Retrieval comes from index/indexer.ts (an index
// built in this tab out of a directory handle the person picked); generation
// runs on the same on-device tiers chat already uses.
//
// NETWORK, stated exactly rather than as "nothing here touches the network":
// requests can leave this module, and none of them carries user data.
// Resolving readiness calls getTier1RunModel, which — only on a device that has already
// finished a tier-1 download (tier1.ts short-circuits on hasAnyTier1Marker
// before any fetch) — may GET /api/edge/manifest, unauthenticated, to learn
// which models exist. That is the same public model catalog the /edge console
// reads, and it contains nothing of the person's; tier1.ts memoizes both the
// parsed result and the in-flight promise in module scope, so a page load pays
// it at most once however many questions are asked. Everything the promise is
// actually about stays here: no file content, chunk, embedding, question or
// answer is ever put on the wire — the retrieval half holds that end of it
// (index/indexer.ts property 1: it contains no fetch(), imports no api module,
// and refuses to search at all until the embedder's weights are already in the
// browser cache). There is deliberately NO hosted leg anywhere below — a
// "local was slow/short, so we asked the server" path would quietly break the
// only promise the feature makes, and would do it on exactly the repositories
// people are most careful about.
//
// Sibling of codegen.ts, and for the same reason stated in that file's
// header: this module only READS tier0/tier1's exported session primitives
// and never touches tierRouter's per-conversation session maps. One question
// is one request/response, not a turn in an ongoing conversation, so it opens
// a short-lived session of its own and destroys it immediately after.
//
// Two divergences from codegen.ts, both load-bearing:
//
//  1. No auth, no API_BASE, no server round-trip of its own. codegen.ts
//     re-checks its draft against POST /api/tools/code/validate because only
//     the server can sandbox-check the generated program; an answer about the
//     person's own code has no such authority to defer to, and asking for one
//     would leak the code AND require a login. This path must work signed-out
//     and must never consume a hosted quota.
//
//  2. Tier 0 is accepted ONLY at availability "available" — never
//     "downloadable"/"downloading", which tier0Available() also answers true
//     for (codegen.ts uses it, correctly: there, createTier0Session pulling
//     Chrome's own model with visible progress is an acceptable cost of an
//     explicit "generate" click). A typed question is not consent to start an
//     unattended model download. Tier 1 gets this for free: getTier1RunModel
//     only ever resolves a model whose weights are already cached here.
//
// Routing preferences (getEdgeRoutingPreference's "hosted-only",
// getStoredModelOverride's "hosted", getEdgeOptIn) are deliberately NOT
// consulted, unlike codegen.ts's eligibleTier. Those settings choose BETWEEN
// on-device and hosted for work that can run either way; this feature has no
// hosted leg, so honouring them would not route the question anywhere — it
// would refuse to answer, citing a platform model that was never going to be
// asked. edgeEnabled() (the deployment's build flag) IS honoured, because a
// deployment with the edge stack compiled out has no tier to run on at all.

import { detectCapabilities, edgeEnabled } from "../edge/capabilities";
import { edgePrompt } from "../edge/edgePrompts";
import { createTier0Session, Tier0Error, tier0Availability, type Tier0Session } from "../edge/tier0";
import {
  computeTier1PromptBudget,
  createTier1Session,
  estimateTier1Tokens,
  getTier1RunModel,
  resolveTier1ContextWindow,
  Tier1Error,
  WEBLLM_FALLBACK_CONTEXT_WINDOW,
  type Tier1Session,
} from "../edge/tier1";
import { getLocalIndexStatus, searchLocalIndex } from "./index/indexer";
import type { IndexStatus, RetrievedChunk } from "./types";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * A union, not one interface with a boolean field, because the boolean was
 * being read as something it can never mean. `ranLocally:false` is emitted
 * from exactly one place (unanswered() below) and always carries an empty
 * answer plus a `detail` saying what to do next — it is "this did not run",
 * never "a partial/retrieval-only answer you can display". Spelling that as
 * `answer: ""` makes a surface that renders a false-branch answer body a
 * compile error instead of an empty bubble under a confident badge.
 *
 * `citations` is RetrievedChunk[] on both branches (not `[]` on the false
 * one) so callers can map over it without narrowing first.
 */
export type LocalAnswer =
  | {
      ranLocally: true;
      answer: string;
      /** EXACTLY the excerpts that went into the prompt — see askLocalCode. */
      citations: RetrievedChunk[];
      detail?: string;
    }
  | {
      ranLocally: false;
      answer: "";
      citations: RetrievedChunk[];
      /** Always present on this branch: a refusal with no reason is the one
       * thing a person cannot act on. */
      detail: string;
    };

export interface AskLocalCodeOptions {
  /** Receives DELTAS, passed straight through to the session's prompt() —
   * both tiers stream the same way, so this module adds no buffering of its
   * own (and never reassembles tokens it would then have to re-split). */
  onToken?: (t: string) => void;
  signal?: AbortSignal;
}

// ---------------------------------------------------------------------------
// Prompt
// ---------------------------------------------------------------------------

/**
 * An UPPER BOUND on how many excerpts retrieval may hand back, and nothing
 * more. It is deliberately not a claim about how many will fit: what fits is
 * derived per ask, at runtime, from the tier's real budget —
 * computeTier1PromptBudget over the context window resolved for the model that
 * is actually going to run — and fitChunks then measures the assembled prompt
 * with the same estimator tier1 prompts with and drops the weakest chunks until
 * it fits. Whatever survives that walk is what the model reads and what the
 * citation list shows, so no arithmetic here has to be right for the prompt to
 * be correctly sized.
 *
 * The bound only has to be high enough that TRIMMING, not retrieval, is what
 * decides the citation list — because the two failures are not symmetric.
 * Over-asking costs some local vector math over rows already in memory.
 * Under-asking permanently hides a file the answer needed, and nothing in the
 * UI would show that it was never in the running. Passed explicitly because
 * searchLocalIndex's own default (DEFAULT_TOP_K in index/indexer.ts) is
 * smaller.
 */
const RETRIEVAL_K = 12;

/**
 * The instruction for the on-device model: the registry key `edge.localcode_ask.system` (edgePrompts.ts), shipped
 * wording as the default. Read, never fetched, here: this feature only ever runs on the device and sends nothing
 * off it, so it uses whatever wording the edge runtime already holds (the shipped text when none has been fetched).
 */
function systemPrompt(): string {
  return edgePrompt("edge.localcode_ask.system");
}

const NO_MODEL_DETAIL =
  "No on-device model is ready in this browser. Open VegaDūta Edge (/edge) and download a " +
  "local model, then ask again — this feature only ever runs on your device, so it has " +
  "nothing to fall back to.";

/**
 * Exported so a caller can recognise the stopped case without matching on
 * prose. THE ABORT DECISION, settled here rather than left to each surface:
 * askLocalCode resolves with this detail and an EMPTY answer, and the partial
 * text stays where it already is — in whatever the caller accumulated from
 * `onToken`. Returning the partial as the answer would have to return
 * citations with it, and those citations would claim a grounding the model
 * never finished reading; a truncated answer under a full citation list is
 * the precise way this feature loses the trust it is built on. So: the module
 * owns "was it a complete on-device answer" (no), and the UI owns "do I keep
 * the half-written text on screen" (it should, labelled with this detail,
 * rather than blanking it — see the note in the UI review).
 */
export const LOCAL_ANSWER_STOPPED_DETAIL = "Stopped before the on-device model finished.";

function excerptBlock(chunk: RetrievedChunk): string {
  return `--- ${chunk.path}:${chunk.startLine}-${chunk.endLine} ---\n${chunk.text}`;
}

function buildUserPrompt(question: string, chunks: RetrievedChunk[]): string {
  return (
    `Question: ${question}\n\n` +
    `Code excerpts from this repository:\n\n` +
    `${chunks.map(excerptBlock).join("\n\n")}\n\n` +
    `Answer using only the excerpts above. Cite each claim as path:startLine-endLine.`
  );
}

/**
 * Keep the highest-scoring chunks that fit the tier's prompt budget, dropping
 * the weakest first.
 *
 * Two choices worth naming. First, each step estimates the prompt that would
 * ACTUALLY be sent — system message plus the fully assembled user prompt —
 * rather than summing a per-chunk proxy: a proxy drifts from the string the
 * moment the framing text changes, and the failure it produces (a context
 * overflow mid-generation) stays invisible until a real turn pays for it.
 *
 * Second, it STOPS at the first chunk that overflows instead of skipping past
 * it to squeeze in a smaller, weaker one. "The top matches that fit" is a rule
 * a person can check against the citation list they were handed; "some of the
 * top matches, chosen partly by byte size" silently promotes a weaker chunk
 * over a stronger one the model never saw. Same stop-at-first-overflow walk
 * assembleTier1Prompt makes over history turns.
 */
function fitChunks(question: string, ranked: RetrievedChunk[], budgetTokens: number): RetrievedChunk[] {
  const kept: RetrievedChunk[] = [];
  for (const chunk of ranked) {
    const candidate = [...kept, chunk];
    const tokens =
      estimateTier1Tokens(systemPrompt()) + estimateTier1Tokens(buildUserPrompt(question, candidate));
    if (tokens > budgetTokens) break;
    kept.push(chunk);
  }
  return kept;
}

// ---------------------------------------------------------------------------
// Tier selection — READY-only: a typed question never starts a model
// DOWNLOAD. It may load an already-downloaded tier-1 engine into memory
// (createTier1Session → acquireEngine reads the browser cache), which is the
// same warm-up codegen.ts's one-shot pays and is not a fetch of weights.
// ---------------------------------------------------------------------------

interface ReadyTier {
  tier: 0 | 1;
  /** Prompt-token budget for this tier, from tier1.ts's budgeter — never a
   * hand-guessed chunk count. */
  budgetTokens: number;
  open(systemPrompt: string): Promise<Tier0Session | Tier1Session>;
}

async function resolveReadyTier(): Promise<ReadyTier | null> {
  try {
    if (typeof window === "undefined" || !edgeEnabled()) return null;
    const caps = await detectCapabilities();
    // Tier 1 first for the same reason codegen.ts prefers it: tier 0 is
    // Chrome's small built-in model, and reading unfamiliar source is exactly
    // the work where the larger local model earns its warm-up.
    const model = await getTier1RunModel(caps);
    if (model) {
      return {
        tier: 1,
        budgetTokens: computeTier1PromptBudget(resolveTier1ContextWindow(model)),
        open: (systemPrompt: string) => createTier1Session(caps, { systemPrompt }),
      };
    }
    if ((await tier0Availability()) === "available") {
      return {
        tier: 0,
        // tier0.ts reads no context window from the Prompt API, so this module
        // has no honest number to budget tier 0 against. Budget it against tier 1's
        // documented last-resort window (WEBLLM_FALLBACK_CONTEXT_WINDOW) and
        // let Chrome's own QuotaExceededError (tier0.ts's
        // "resource-exhausted") be the backstop if that under-reads the real
        // one. Same direction of error
        // tier1's token estimator is deliberately tuned to make.
        budgetTokens: computeTier1PromptBudget(WEBLLM_FALLBACK_CONTEXT_WINDOW),
        open: (systemPrompt: string) => createTier0Session(systemPrompt),
      };
    }
    return null;
  } catch {
    // A device that cannot even be probed reads as "nothing ready" —
    // capability detection must never surface as a thrown error in front of a
    // question someone just typed.
    return null;
  }
}

/**
 * True when a question typed right now would actually run on this device.
 *
 * Exported because the alternative is what the UI did: re-implement the same
 * probe inline and drop a clause from it (the edgeEnabled() guard), so the
 * Ask button went live on a deployment where the edge stack is compiled out
 * and every question came back refused. Same role as codegen.ts's
 * edgeCodegenReady, and the same guarantee: pure query, starts nothing,
 * never rejects.
 *
 * Scope note: MODEL readiness only. It says nothing about whether a repo is
 * indexed or the embedding model is installed — a caller that wants to gate
 * on those reads getLocalIndexStatus()/the indexer's own readiness check, and
 * askLocalCode reports both cases with a specific detail anyway.
 */
export async function localAskReady(): Promise<boolean> {
  return (await resolveReadyTier()) !== null;
}

function unanswered(detail: string): LocalAnswer {
  return { answer: "", citations: [], ranLocally: false, detail };
}

function describe(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function isAborted(err: unknown): boolean {
  if ((err instanceof Tier0Error || err instanceof Tier1Error) && err.code === "aborted") return true;
  return err instanceof DOMException && err.name === "AbortError";
}

/**
 * Why an empty retrieval came back empty.
 *
 * searchLocalIndex NEVER throws: an embedding model that was never installed,
 * a worker that won't start, a corrupt store — every one of them resolves to
 * `[]` with the real reason parked in the index status, and the indexer's own
 * doc comment makes telling those apart the caller's job. Reporting "nothing
 * matched your question" over a search that never ran is worse than useless:
 * it tells someone their code doesn't contain what they're looking at.
 */
function emptyRetrievalDetail(): string {
  let status: IndexStatus | null = null;
  try {
    status = getLocalIndexStatus();
  } catch {
    // A status reader that throws is not a reason to lose the question.
    status = null;
  }
  if (status && status.state === "error") {
    return status.detail
      ? `Local search couldn't run: ${status.detail}`
      : "Local search couldn't run on this device — check the on-device index in VegaDūta Edge (/edge).";
  }
  if (status && status.state === "indexing") {
    return "The local index is still building — ask again once it finishes.";
  }
  if (status && status.state === "ready") {
    return (
      "Nothing in the local index is close to that question. Naming a file, a symbol or an " +
      "error string from the code usually finds it."
    );
  }
  return "There's no local index yet — pick a folder and build the index first.";
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Answer a question about the indexed local repository, entirely on this
 * device.
 *
 * The citation contract is absolute: `citations` is the exact array that was
 * rendered into the prompt, in the order the model saw it — never the wider
 * retrieval set. A citation list longer than the excerpts the model actually
 * read invites someone to open a file the answer was never grounded in, which
 * is the specific way a code assistant loses trust.
 *
 * `ranLocally:false` always carries a `detail` saying what to do next, and
 * never a partial answer (see LocalAnswer and LOCAL_ANSWER_STOPPED_DETAIL).
 * Never throws.
 */
export async function askLocalCode(
  question: string,
  opts: AskLocalCodeOptions = {}
): Promise<LocalAnswer> {
  const { onToken, signal } = opts;
  try {
    // Normalized once, up front: the string that gets searched, the string
    // that gets budgeted and the string that reaches the model must be the
    // same one, or fitChunks budgets a prompt that isn't the prompt.
    const q = typeof question === "string" ? question.trim() : "";
    if (!q) return unanswered("Ask a question about the code first.");

    // The signal is checked BEFORE readiness, not after: resolveReadyTier
    // runs capability detection and can cost a manifest GET, and an
    // already-cancelled ask has no business spending either.
    if (signal?.aborted) return unanswered(LOCAL_ANSWER_STOPPED_DETAIL);

    // Readiness before retrieval: searching the index is local but not free,
    // and there is no point spending it on a device that cannot answer.
    const ready = await resolveReadyTier();
    if (!ready) return unanswered(NO_MODEL_DETAIL);
    if (signal?.aborted) return unanswered(LOCAL_ANSWER_STOPPED_DETAIL);

    let retrieved: RetrievedChunk[];
    try {
      retrieved = await searchLocalIndex(q, RETRIEVAL_K);
    } catch (err) {
      // Belt-and-braces: today searchLocalIndex swallows its own failures.
      return unanswered(`Couldn't search the local index: ${describe(err)}`);
    }
    if (!retrieved || retrieved.length === 0) return unanswered(emptyRetrievalDetail());

    // Sorted here rather than trusted from the index: fitChunks' drop-weakest
    // rule is only true if the walk really is strongest-first, and this file
    // is the one that has to keep that promise to the citation list.
    const ranked = [...retrieved].sort((a, b) => b.score - a.score);
    const cited = fitChunks(q, ranked, ready.budgetTokens);
    if (cited.length === 0) {
      return unanswered(
        "Even the closest matching excerpt is too long for the on-device model's context window. " +
          "Ask about a smaller piece of code, or download a larger model in VegaDūta Edge (/edge)."
      );
    }

    // Last cancellation check before the expensive half: opening a tier-1
    // session loads the engine and takes no signal of its own, so once it
    // starts, a stop request waits on it.
    if (signal?.aborted) return unanswered(LOCAL_ANSWER_STOPPED_DETAIL);

    let raw: string;
    try {
      const session = await ready.open(systemPrompt());
      try {
        raw = await session.prompt(buildUserPrompt(q, cited), onToken, signal);
      } finally {
        try {
          session.destroy();
        } catch {
          // Teardown only. A throw here would turn a finished answer into a
          // failure in the caller's hands — the generation already happened
          // and the person is owed its text more than a clean teardown.
        }
      }
    } catch (err) {
      if (isAborted(err)) return unanswered(LOCAL_ANSWER_STOPPED_DETAIL);
      return unanswered(`The on-device model couldn't answer: ${describe(err)}`);
    }

    const answer = (raw || "").trim();
    if (!answer) {
      // An empty reply is reported as a failure, not as a local answer that
      // happens to be blank: a caller showing "answered on your device" over
      // an empty bubble has told the person something ran when nothing usable
      // did. Same posture as codegen.ts's "empty-reply".
      return unanswered(
        "The on-device model returned an empty answer — try again, or ask something narrower."
      );
    }
    return { answer, citations: cited, ranLocally: true };
  } catch (err) {
    // Belt-and-braces, matching routeTurn's and generateToolOnDevice's
    // absolute never-throw-to-the-caller contract.
    return unanswered(`Local answer failed: ${describe(err)}`);
  }
}
