import type { ActivityStep } from "../AgentActivityTrace";
import { deriveTurnProgress } from "../../../lib/turnProgress";
import {
  APPROVAL_STEP_TYPES,
  CONTINUATION_STEP_TYPES,
  CONTINUATION_REASON_APPROVAL,
  FAILOVER_STEP_TYPES,
  LIFECYCLE_REASON_KEYS,
  RETRY_STEP_TYPES,
  humanizeReason,
} from "../../../lib/turnLifecycle";

/**
 * What the agent is doing right now, as the short status chips above the transcript.
 *
 * One chip per concern, in the order a person cares about: a decision they owe (waiting for approval), the work
 * (preparing / model call / thinking / tool / writing), the agent carrying on (continuing), and the platform
 * recovering (retrying, switched to a backup model). Derived from the pending turn's step events, the approval
 * queue and the request lifecycle; pure and never throws.
 */
export type LiveChipKind =
  | "waitingApproval"
  | "preparing"
  | "modelCall"
  | "thinking"
  | "tool"
  | "writing"
  | "continuing"
  | "retrying"
  | "failover"
  | "toolsOff"
  | "failoverBlocked";

/**
 * Core reports two recoveries as `guardrail` events whose `tool` is a fixed subject, not a tool name
 * (DegradedRetrySignal): "tools" - the turn is being retried with its tools withheld - and "failover" - a backup
 * model was NOT used because the turn had already run tools and a second run would repeat their effects.
 */
export const GUARDRAIL_SUBJECT_TOOLS_OFF = "tools";
export const GUARDRAIL_SUBJECT_FAILOVER_BLOCKED = "failover";

export interface LiveChip {
  /** Stable key for React and tests. */
  id: string;
  kind: LiveChipKind;
  /** Warnings (recovering from a problem) read differently from plain progress. */
  tone: "progress" | "attention";
  tool?: string;
  /** The reason as plain words (underscores read as spaces): what is shown for a code this build has no sentence for. */
  reason?: string;
  /** The machine code core sent (rate_limited, timeout...), when it is one with a sentence in the i18n bundle. */
  reasonCode?: string;
  attempt?: number;
  maxAttempts?: number;
  pass?: number;
  maxPasses?: number;
  count?: number;
  model?: string;
  /** The continuation is the agent carrying on after a decided approval, not a model pass on a cut-off reply. */
  afterApproval?: boolean;
}

export interface LiveStatusInput {
  /** The pending turn's step events (undefined when none yet). */
  steps?: readonly ActivityStep[] | null;
  /** A turn is in flight. */
  sending: boolean;
  /** Some reply text has already streamed. */
  hasText: boolean;
  /** Tool calls parked for a person to decide, in this conversation. */
  waitingApprovals: number;
  /** The platform is carrying the task on after a decided approval. */
  approvalContinuing: boolean;
  /** The request lifecycle's own retry counter (1 = first try). */
  attempt?: number | null;
  maxAttempts?: number | null;
}

function lastOfType(steps: readonly ActivityStep[], types: ReadonlySet<string>): ActivityStep | null {
  for (let i = steps.length - 1; i >= 0; i--) if (types.has(steps[i].type)) return steps[i];
  return null;
}

function lastGuardrail(steps: readonly ActivityStep[], subject: string): ActivityStep | null {
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].type === "guardrail" && steps[i].tool === subject) return steps[i];
  }
  return null;
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined;
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** `{ reason, reasonCode }` of a retry or failover event: the plain-words fallback, and the code when it is a known one. */
function reasonOf(step: ActivityStep): { reason?: string; reasonCode?: string } {
  const code = text(step.details?.reason);
  if (!code) {
    const message = text(step.message);
    return message ? { reason: message } : {};
  }
  const known = Object.prototype.hasOwnProperty.call(LIFECYCLE_REASON_KEYS, code);
  return { reason: humanizeReason(code), ...(known ? { reasonCode: code } : {}) };
}

export function deriveLiveChips(input: LiveStatusInput): LiveChip[] {
  const chips: LiveChip[] = [];
  const steps = input.steps ?? [];

  if (input.waitingApprovals > 0) {
    chips.push({ id: "waitingApproval", kind: "waitingApproval", tone: "attention", count: input.waitingApprovals });
  } else if (input.sending) {
    // An approval event the queue has not reported yet still means the turn is parked on a person.
    const approval = lastOfType(steps, APPROVAL_STEP_TYPES);
    const closedAfter = approval ? steps.slice(steps.indexOf(approval) + 1).some((s) => s.type === "tool_result") : true;
    if (approval && !closedAfter) {
      chips.push({ id: "waitingApproval", kind: "waitingApproval", tone: "attention", tool: approval.tool });
    }
  }

  if (input.approvalContinuing) {
    chips.push({ id: "continuing-approval", kind: "continuing", tone: "progress", afterApproval: true });
  }

  if (input.sending) {
    const progress = deriveTurnProgress(steps, { hasText: input.hasText, now: 0 });
    switch (progress.phase) {
      case "tool":
        chips.push({ id: "tool", kind: "tool", tone: "progress", tool: progress.tool });
        break;
      case "writing":
        chips.push({ id: "writing", kind: "writing", tone: "progress" });
        break;
      case "thinking":
      case "planning":
        chips.push({ id: "thinking", kind: "thinking", tone: "progress" });
        break;
      case "starting":
      case "guardrails":
      case "routing":
      case "context":
        chips.push({ id: "preparing", kind: "preparing", tone: "progress" });
        break;
      default:
        chips.push({ id: "modelCall", kind: "modelCall", tone: "progress" });
    }

    const continuation = lastOfType(steps, CONTINUATION_STEP_TYPES);
    if (continuation) {
      const d = continuation.details;
      if (d?.reason === CONTINUATION_REASON_APPROVAL) {
        // The agent carrying on after a person decided a held tool call (the same chip the approval queue drives).
        if (!input.approvalContinuing) {
          chips.push({ id: "continuing-approval", kind: "continuing", tone: "progress", afterApproval: true });
        }
      } else {
        chips.push({
          id: "continuing-pass",
          kind: "continuing",
          tone: "progress",
          pass: num(d?.pass) ?? num(d?.passes),
          maxPasses: num(d?.maxPasses) ?? num(d?.limit),
        });
      }
    }

    const retry = lastOfType(steps, RETRY_STEP_TYPES);
    const lifecycleAttempt = input.attempt ?? 0;
    const lifecycleMax = input.maxAttempts ?? 0;
    if (retry) {
      const d = retry.details;
      chips.push({
        id: "retrying",
        kind: "retrying",
        tone: "attention",
        ...reasonOf(retry),
        attempt: num(d?.attempt),
        maxAttempts: num(d?.maxAttempts) ?? num(d?.limit),
      });
    } else if (lifecycleAttempt > 1 && lifecycleMax > 1) {
      chips.push({
        id: "retrying",
        kind: "retrying",
        tone: "attention",
        attempt: lifecycleAttempt,
        maxAttempts: lifecycleMax,
      });
    }

    const failover = lastOfType(steps, FAILOVER_STEP_TYPES);
    if (failover) {
      const d = failover.details;
      chips.push({
        id: "failover",
        kind: "failover",
        tone: "attention",
        ...reasonOf(failover),
        model: text(d?.toModel) ?? text(d?.model),
      });
    }

    if (lastGuardrail(steps, GUARDRAIL_SUBJECT_TOOLS_OFF)) {
      chips.push({ id: "tools-off", kind: "toolsOff", tone: "attention" });
    }
    if (lastGuardrail(steps, GUARDRAIL_SUBJECT_FAILOVER_BLOCKED)) {
      chips.push({ id: "failover-blocked", kind: "failoverBlocked", tone: "attention" });
    }
  }

  return chips;
}

type Translate = (key: string, vars?: Record<string, string | number>) => string;

/** The reason inside a chip's sentence: the bundle's wording for a code it knows, else the plain-words fallback. */
function reasonText(t: Translate, chip: LiveChip): string | undefined {
  const key = chip.reasonCode ? LIFECYCLE_REASON_KEYS[chip.reasonCode] : undefined;
  return key ? t(key) : chip.reason;
}

/** The chip's sentence, from the playground's i18n bundle. */
export function chipText(t: Translate, chip: LiveChip): string {
  const reason = reasonText(t, chip);
  switch (chip.kind) {
    case "waitingApproval":
      return t("chipWaitingApproval", { count: chip.count ?? 1 });
    case "preparing":
      return t("chipPreparing");
    case "modelCall":
      return t("chipModelCall");
    case "thinking":
      return t("chipThinking");
    case "writing":
      return t("chipWriting");
    case "tool":
      return chip.tool ? t("chipTool", { tool: chip.tool }) : t("chipToolUnnamed");
    case "continuing":
      if (chip.afterApproval) return t("chipContinuingApproval");
      return chip.pass != null && chip.maxPasses != null
        ? t("chipContinuingPass", { pass: chip.pass, max: chip.maxPasses })
        : t("chipContinuing");
    case "retrying": {
      const attempt = chip.attempt != null && chip.maxAttempts != null;
      if (attempt && reason) {
        return t("chipRetryingAttemptReason", { attempt: chip.attempt as number, max: chip.maxAttempts as number, reason });
      }
      if (attempt) return t("chipRetryingAttempt", { attempt: chip.attempt as number, max: chip.maxAttempts as number });
      return reason ? t("chipRetryingReason", { reason }) : t("chipRetrying");
    }
    case "toolsOff":
      return t("chipToolsOff");
    case "failoverBlocked":
      return t("chipFailoverBlocked");
    case "failover":
      if (chip.model && reason) return t("chipFailoverToReason", { model: chip.model, reason });
      if (chip.model) return t("chipFailoverTo", { model: chip.model });
      return reason ? t("chipFailoverReason", { reason }) : t("chipFailover");
  }
}
