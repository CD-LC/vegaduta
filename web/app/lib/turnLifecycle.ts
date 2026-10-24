/**
 * The turn-lifecycle vocabulary of the chat step stream, in one place.
 *
 * Core interleaves step events with the reply tokens (AgentStepEventEmitter). Besides tool calls it can say that
 * a model call was retried, that the platform moved to a backup model, that a cut-off reply is being continued,
 * that a tool call is waiting for a person, and why the model stopped (the provider's finish reason). A newer core
 * adds these before the console knows them, so every name here is a SET of accepted spellings and anything else
 * stays an ordinary generic row in the activity trace.
 *
 * Pure, no React: the playground's live status chips, its outcome notices and the activity-trace normaliser all
 * read the same tables, so they can never disagree about what an event means.
 */

/** A model call (or tool call) is being tried again. */
export const RETRY_STEP_TYPES: ReadonlySet<string> = new Set(["retry", "retrying", "model_retry", "tool_retry"]);

/** The turn moved to another model or provider after the first one failed. */
export const FAILOVER_STEP_TYPES: ReadonlySet<string> = new Set([
  "failover",
  "fallback",
  "model_fallback",
  "model_failover",
  "provider_failover",
]);

/** A reply that stopped early is being carried on by another model pass. */
export const CONTINUATION_STEP_TYPES: ReadonlySet<string> = new Set([
  "continuation",
  "continuing",
  "continue",
  "finish_continuation",
]);

/** A tool call is parked until a person approves or rejects it. */
export const APPROVAL_STEP_TYPES: ReadonlySet<string> = new Set([
  "approval",
  "approval_required",
  "approval_pending",
  "waiting_approval",
  "awaiting_approval",
]);

/** Why the model stopped writing (the provider's finish reason, in core's own vocabulary). */
export const FINISH_STEP_TYPES: ReadonlySet<string> = new Set(["finish", "finish_reason", "reply_finish"]);

/** Every type whose extra fields (reason, attempt, pass...) the console keeps on the normalised step. */
export const LIFECYCLE_DETAIL_TYPES: ReadonlySet<string> = new Set([
  ...RETRY_STEP_TYPES,
  ...FAILOVER_STEP_TYPES,
  ...CONTINUATION_STEP_TYPES,
  ...APPROVAL_STEP_TYPES,
  ...FINISH_STEP_TYPES,
]);

const DETAIL_KEYS = [
  "reason",
  "finishReason",
  "finish",
  "attempt",
  "maxAttempts",
  "pass",
  "passes",
  "maxPasses",
  "limit",
  "count",
  "detail",
  "approvalId",
  "provider",
  "model",
  "fromProvider",
  "fromModel",
  "toProvider",
  "toModel",
] as const;

export type StepDetailValue = string | number | boolean;
export type StepDetails = Partial<Record<(typeof DETAIL_KEYS)[number], StepDetailValue>>;

/**
 * The scalar detail fields of a lifecycle event, or undefined when the type is not a lifecycle type or carries
 * none. Never throws; anything that is not a string, finite number or boolean is dropped.
 */
export function lifecycleDetails(type: string, raw: Record<string, unknown>): StepDetails | undefined {
  if (!LIFECYCLE_DETAIL_TYPES.has(type)) return undefined;
  const out: StepDetails = {};
  for (const key of DETAIL_KEYS) {
    const value = raw[key];
    if (typeof value === "string" && value) out[key] = value;
    else if (typeof value === "number" && Number.isFinite(value)) out[key] = value;
    else if (typeof value === "boolean") out[key] = value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

export type FinishKind = "truncated" | "refused" | "malformedToolCall" | "empty" | "complete";

const FINISH_BY_NAME: Record<string, FinishKind> = {
  length: "truncated",
  max_tokens: "truncated",
  max_output_tokens: "truncated",
  truncated: "truncated",
  cut_off: "truncated",
  output_limit: "truncated",
  context_length_exceeded: "truncated",
  safety: "refused",
  refusal: "refused",
  refused: "refused",
  content_filter: "refused",
  content_filtered: "refused",
  guardrail_intervened: "refused",
  malformed_tool_call: "malformedToolCall",
  malformed_function_call: "malformedToolCall",
  tool_use_failed: "malformedToolCall",
  invalid_tool_call: "malformedToolCall",
  empty: "empty",
  empty_reply: "empty",
  interrupted: "truncated",
  stop: "complete",
  end_turn: "complete",
  complete: "complete",
};

/** One finish reason, in whatever spelling the provider or core used, as a kind the console can act on; null when unknown. */
export function finishKindOf(reason: string | null | undefined): FinishKind | null {
  if (typeof reason !== "string") return null;
  const key = reason.trim().toLowerCase().replace(/[\s-]+/g, "_");
  return FINISH_BY_NAME[key] ?? null;
}

/** "rate_limited" -> "rate limited": a raw machine reason made readable when no better wording is known. */
export function humanizeReason(reason: string | null | undefined): string {
  if (typeof reason !== "string") return "";
  return reason.trim().replace(/[_-]+/g, " ");
}

/**
 * The machine reasons core puts on `retry`, `failover` and `continuation` events (TurnLifecycleStepEvents and
 * TurnLifecycleEvents in core), each with the key of its sentence in the playground's i18n bundle. A reason that is
 * not listed (a newer core) is shown with its underscores read as spaces.
 */
export const LIFECYCLE_REASON_KEYS: Readonly<Record<string, string>> = {
  rate_limited: "reasonRateLimited",
  timeout: "reasonTimeout",
  provider_unavailable: "reasonProviderUnavailable",
  auth_failed: "reasonAuthFailed",
  billing: "reasonBilling",
  model_not_found: "reasonModelNotFound",
  provider_error: "reasonProviderError",
  malformed_tool_call: "reasonMalformedToolCall",
};

/** The `reason` of a `continuation` event that means "carrying on after a person decided a held tool call". */
export const CONTINUATION_REASON_APPROVAL = "approval";
