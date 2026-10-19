// FAQ eval harness for the on-device edge tiers.
//
// WHY THIS EXISTS. core/quality's EvalRunner measures hosted and
// OpenAI-compatible backends from the JVM, but the edge tier (WebLLM /
// Chrome built-in / WASM) only exists inside a real browser — it cannot be
// exercised from CI's JVM or from jsdom. This module is the browser-side
// half of that eval: it runs the SAME canonical dataset
// (core/quality/src/main/resources/edge-faq-dataset.json, served to the page
// by /api/eval/edge-faq) through the REAL routeTurn() — the exact entry
// point AskAiHelper/AgentPlayground/CallPanel use — and emits a
// machine-readable report.
//
// Honesty contract (deliberate, do not relax):
//   * `handled === true` is the ONLY result counted as an on-device answer.
//   * A hosted fallthrough is recorded as outcome "fallback" with its
//     HostedFallthroughReason — never silently retried hosted and never
//     counted as an edge pass.
//   * If NO case was handled and every fallthrough reason is
//     availability-shaped (edge-disabled / no-local-tier /
//     awaiting-user-choice / user-hosted-override / offline), the report
//     status is "edge-unavailable" — an explicit SKIP signal for CI, not a
//     pass and not a model-quality failure.
//   * Answer quality is NOT judged here. The rubric text is copied into
//     each result so the same judge used by EvalRunner (or a human) can
//     score it offline. The only in-harness signal is abstention
//     DETECTION on cases the dataset marks UNDER_SPECIFIED, via the
//     heuristic in detectAbstention() — labelled heuristicAbstention, not
//     ground truth.

import {
  routeTurn,
  resetEdgeSession,
  type RouteTurnResult,
  type HostedFallthroughReason,
} from "./tierRouter";

export interface FaqEvalCase {
  id: string;
  persona: string;
  category: string;
  systemPrompt: string;
  userMessage: string;
  rubric: string;
  /** "UNDER_SPECIFIED" = the correct answer is to abstain. */
  feasibility?: string;
}

export type FaqEvalOutcome = "handled" | "fallback" | "error";

export interface FaqEvalCaseResult {
  id: string;
  persona: string;
  outcome: FaqEvalOutcome;
  /** 0 | 1 | 2 when outcome === "handled". */
  tier?: 0 | 1 | 2;
  downgradedFrom?: { tier: 0 | 1 | 2; detail: string };
  /** Wall-clock ms for the routeTurn call only (includes any model
   * warmup the router performs inline). */
  latencyMs: number;
  /** The answer text when handled; undefined otherwise. */
  text?: string;
  reason?: HostedFallthroughReason;
  detail?: string;
  error?: string;
  /** Dataset said abstain (feasibility === "UNDER_SPECIFIED"). */
  abstentionExpected: boolean;
  /** Heuristic abstention signal on the returned text. null when the
   * case was not handled (nothing to inspect). */
  heuristicAbstention: boolean | null;
  rubric: string;
}

export interface FaqEvalReport {
  status: "completed" | "edge-unavailable" | "aborted";
  generatedAt: string;
  datasetCaseCount: number;
  results: FaqEvalCaseResult[];
  summary: {
    handled: number;
    fallback: number;
    errors: number;
    abstentionExpected: number;
    abstentionDetected: number;
    abstentionMissed: number;
    latencyMsP50: number | null;
    latencyMsMax: number | null;
  };
}

/** Route function signature — matches routeTurn so tests can inject a
 * fake while the page passes the real one. */
export type FaqRouteFn = (
  input: string,
  options: { sessionKey?: string; systemPrompt?: string; signal?: AbortSignal },
) => Promise<RouteTurnResult>;

/** Reasons that mean "the edge path was never really attempted" — as
 * opposed to "edge tried and the model failed". If EVERY case ends in one
 * of these, the honest verdict is edge-unavailable, not a bad model. */
const AVAILABILITY_REASONS: ReadonlySet<HostedFallthroughReason> = new Set([
  "edge-disabled",
  "no-local-tier",
  "awaiting-user-choice",
  "user-hosted-override",
  "offline",
]);

const ABSTENTION_PHRASES = [
  "don't know",
  "do not know",
  "not sure",
  "no information",
  "not listed",
  "isn't listed",
  "not mentioned",
  "outside",
  "can't help with that",
  "cannot help with that",
  "connect you",
  "connect a human",
  "human agent",
  "our team",
  "the team",
  "staff",
  "representative",
];

/** Cheap abstention heuristic — NOT a judge. Used only to flag whether a
 * case that should abstain plausibly did; the rubric is what a real
 * scorer judges against. */
export function detectAbstention(text: string): boolean {
  const t = text.toLowerCase();
  return ABSTENTION_PHRASES.some((p) => t.includes(p));
}

export interface RunFaqEvalOptions {
  route?: FaqRouteFn;
  signal?: AbortSignal;
  onProgress?: (done: number, total: number, last: FaqEvalCaseResult) => void;
  /** sessionKey prefix — each case gets `${prefix}${case.id}` and a
   * resetEdgeSession before it, so no conversation state leaks between
   * cases (same statelessness rule AskAiHelper applies). */
  sessionPrefix?: string;
}

export async function runFaqEval(
  cases: FaqEvalCase[],
  opts: RunFaqEvalOptions = {},
): Promise<FaqEvalReport> {
  const route = opts.route ?? routeTurn;
  const prefix = opts.sessionPrefix ?? "faq-eval:";
  const results: FaqEvalCaseResult[] = [];

  for (let i = 0; i < cases.length; i++) {
    const c = cases[i];
    const sessionKey = `${prefix}${c.id}`;
    const abstentionExpected = c.feasibility === "UNDER_SPECIFIED";
    const base = {
      id: c.id,
      persona: c.persona,
      abstentionExpected,
      rubric: c.rubric,
    };

    let result: FaqEvalCaseResult;
    if (opts.signal?.aborted) {
      result = { ...base, outcome: "error", latencyMs: 0, error: "aborted", heuristicAbstention: null };
    } else {
      resetEdgeSession(sessionKey);
      const started = performance.now();
      try {
        const r = await route(c.userMessage, {
          sessionKey,
          systemPrompt: c.systemPrompt,
          signal: opts.signal,
        });
        const latencyMs = Math.round(performance.now() - started);
        if (r.handled === true) {
          result = {
            ...base,
            outcome: "handled",
            tier: r.tier,
            downgradedFrom: r.downgradedFrom,
            latencyMs,
            text: r.text,
            heuristicAbstention: detectAbstention(r.text),
          };
        } else {
          result = {
            ...base,
            outcome: "fallback",
            latencyMs,
            reason: r.reason,
            detail: r.detail,
            heuristicAbstention: null,
          };
        }
      } catch (err) {
        result = {
          ...base,
          outcome: "error",
          latencyMs: Math.round(performance.now() - started),
          error: err instanceof Error ? err.message : String(err),
          heuristicAbstention: null,
        };
      }
    }

    results.push(result);
    opts.onProgress?.(i + 1, cases.length, result);
    if (opts.signal?.aborted && i < cases.length - 1) {
      // Fill remaining cases as aborted so the report stays complete-shaped.
      for (const rest of cases.slice(i + 1)) {
        results.push({
          id: rest.id,
          persona: rest.persona,
          outcome: "error",
          latencyMs: 0,
          error: "aborted",
          abstentionExpected: rest.feasibility === "UNDER_SPECIFIED",
          heuristicAbstention: null,
          rubric: rest.rubric,
        });
      }
      break;
    }
  }

  return {
    status: reportStatus(results, opts.signal?.aborted === true),
    generatedAt: new Date().toISOString(),
    datasetCaseCount: cases.length,
    results,
    summary: summarize(results),
  };
}

function reportStatus(results: FaqEvalCaseResult[], aborted: boolean): FaqEvalReport["status"] {
  if (aborted) return "aborted";
  const anyHandled = results.some((r) => r.outcome === "handled");
  if (!anyHandled && results.length > 0 &&
      results.every((r) => r.outcome !== "handled" &&
        (r.outcome !== "fallback" || AVAILABILITY_REASONS.has(r.reason as HostedFallthroughReason)))) {
    return "edge-unavailable";
  }
  return "completed";
}

function summarize(results: FaqEvalCaseResult[]): FaqEvalReport["summary"] {
  const handled = results.filter((r) => r.outcome === "handled");
  const latencies = handled.map((r) => r.latencyMs).sort((a, b) => a - b);
  return {
    handled: handled.length,
    fallback: results.filter((r) => r.outcome === "fallback").length,
    errors: results.filter((r) => r.outcome === "error").length,
    abstentionExpected: results.filter((r) => r.abstentionExpected).length,
    abstentionDetected: results.filter((r) => r.abstentionExpected && r.heuristicAbstention === true).length,
    abstentionMissed: results.filter((r) => r.abstentionExpected && r.heuristicAbstention === false).length,
    latencyMsP50: latencies.length ? latencies[Math.floor((latencies.length - 1) / 2)] : null,
    latencyMsMax: latencies.length ? latencies[latencies.length - 1] : null,
  };
}
