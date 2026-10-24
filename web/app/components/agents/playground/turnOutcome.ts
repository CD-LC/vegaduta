import type { ActivityStep } from "../AgentActivityTrace";
import { FINISH_STEP_TYPES, finishKindOf, humanizeReason, type FinishKind } from "../../../lib/turnLifecycle";

/**
 * How a finished reply ended when it did not end normally, so the transcript can say so in plain language and
 * offer the one action that helps ("Continue" for a reply that was cut off).
 *
 * Source: the `finish` step event core sends once per reply, whose `finishReason` field (stop, length, safety,
 * malformed_tool_call, empty, interrupted) says how the reply ended. When a turn has that event it alone decides:
 * rewording app.chat.finish.* or reading another locale cannot remove "Continue", because nothing here looks at the
 * reply text. Legacy fallback, only for a turn that has no `finish` event (an older core, or a reply stored before
 * the event existed): the markers core writes INTO the reply text when it stops early (the interrupted-reply suffix,
 * and the default cut-off / safety notices of app.chat.finish.*); an operator who rewords those loses that fallback.
 *
 * Pure and never throws.
 */
export type TurnOutcomeKind = Exclude<FinishKind, "complete">;

export interface TurnOutcome {
  kind: TurnOutcomeKind;
  /** The provider's own reason (humanised), when it was reported. */
  reason?: string;
  /** Where the outcome was read from. */
  source: "event" | "text";
}

/** ChatTurn.INTERRUPTED_REPLY_SUFFIX in core: a reply whose stream broke after some text. */
export const INTERRUPTED_REPLY_MARKER = "[reply interrupted]";
/** Start of the default app.chat.finish.truncated-notice. */
const TRUNCATED_NOTICE_START = "This reply was cut off because the model reached its output length limit";
/** The default app.chat.finish.refusal-notice, with the provider's reason captured. */
const REFUSAL_NOTICE = /safety system stopped this reply \(([^)]*)\)/i;

export function deriveTurnOutcome(turn: {
  steps?: readonly ActivityStep[] | null;
  content?: string | null;
}): TurnOutcome | null {
  const steps = turn.steps ?? [];
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i];
    if (!FINISH_STEP_TYPES.has(step.type)) continue;
    const raw = step.details?.finishReason ?? step.details?.finish ?? step.details?.reason ?? step.message;
    const rawText = typeof raw === "string" && raw ? raw : undefined;
    // An event without any reason is not the field this reads; the text fallback below still applies.
    if (!rawText) continue;
    const kind = finishKindOf(rawText);
    if (kind && kind !== "complete") {
      // `detail` is the provider's own word for a refusal (content_filter, SAFETY...); the reason otherwise.
      const detail = typeof step.details?.detail === "string" ? step.details.detail : undefined;
      return { kind, reason: humanizeReason(detail || rawText), source: "event" };
    }
    // A finish event decided the outcome (an answer, or a reason this build does not know): never guess from text.
    return null;
  }

  const content = typeof turn.content === "string" ? turn.content : "";
  if (!content) return null;
  if (content.trimEnd().endsWith(INTERRUPTED_REPLY_MARKER) || content.includes(TRUNCATED_NOTICE_START)) {
    return { kind: "truncated", source: "text" };
  }
  const refusal = REFUSAL_NOTICE.exec(content);
  if (refusal) return { kind: "refused", reason: refusal[1] || undefined, source: "text" };
  return null;
}
