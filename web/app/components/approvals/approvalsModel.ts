// Pure helpers for the approvals inbox (formatting + payload parsing). No React, no I/O.
import { channelOffered } from "../../lib/edition";

export type Translate = (key: string, params?: Record<string, unknown>) => string;

export interface ApprovalRequestItem {
  id: string;
  status: string;
  actionType: string;
  summary?: string | null;
  requestedBy?: string | null;
  createdAt?: string | null;
  decidedAt?: string | null;
  decidedBy?: string | null;
  retryCount?: number;
  nextRetryAt?: string | null;
  rejectionReason?: string | null;
  executionError?: string | null;
  executionResult?: string | null;
  payload?: string | null;
}

export const STATUS_FILTERS = ["PENDING", "", "APPROVED", "REJECTED", "EXECUTION_FAILED", "DEAD_LETTERED"];

// Every value is a real ACTION_TYPE a channel adapter gates through; "channel.*.send" is the documented glob.
// Non-channel action types (mcp_tool.*, workflow.node.*) are free-typed via allowCustom in the picker.
const ALL_CHANNEL_ACTION_TYPES = [
  { value: "channel.*.send", labelKey: "channelWildcardOption" },
  { value: "channel.whatsapp.send", labelKey: "channelWhatsappTwilio" },
  { value: "channel.instagram.send", labelKey: "channelInstagram" },
  { value: "channel.messenger.send", labelKey: "channelMessenger" },
  { value: "channel.slack.send", labelKey: "channelSlack" },
  { value: "channel.discord.send", labelKey: "channelDiscord" },
  { value: "channel.x.send", labelKey: "channelX" },
];

/** The action types for channels this install runs (no Slack/Discord/X policy rows on a personal install). */
export const CHANNEL_ACTION_TYPES = ALL_CHANNEL_ACTION_TYPES.filter((a) => {
  const m = /^channel\.([a-z0-9_-]+)\.send$/.exec(a.value);
  return m === null || channelOffered(m[1]);
});

export function relTime(iso: string | null | undefined, t: Translate) {
  if (!iso) return "—";
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return t("justNow");
  const m = Math.round(s / 60);
  if (m < 60) return t("minutesAgo", { m });
  const h = Math.round(m / 60);
  if (h < 24) return t("hoursAgo", { h });
  return t("daysAgo", { d: Math.round(h / 24) });
}

export function fmtTime(iso: string | null | undefined) {
  return iso ? new Date(iso).toLocaleString() : "—";
}

// Pretty-prints the payloadJson the backend echoes; falls back to the raw string when it is not JSON.
export function prettyPayload(raw: string | null | undefined) {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

// Agentic UPI Payments: a payload with amountPaise + a mandate object is a payment under mandate.
export function paymentContext(payloadJson: string | null | undefined) {
  if (!payloadJson) return null;
  try {
    const p = JSON.parse(payloadJson);
    if (p && typeof p === "object" && typeof p.amountPaise === "number" && p.mandate && typeof p.mandate === "object") {
      return p;
    }
  } catch {
    /* not JSON / not a payment payload */
  }
  return null;
}

// Ambient-agent proposals: an agent.proposal payload carries the goal the parked run wants to execute.
export function proposalContext(payloadJson: string | null | undefined) {
  if (!payloadJson) return null;
  try {
    const p = JSON.parse(payloadJson);
    if (p && typeof p === "object" && typeof p.goal === "string" && p.goal) {
      return p as { goal: string; agentId?: string; sourceRunId?: string };
    }
  } catch {
    /* not JSON / not a proposal payload */
  }
  return null;
}

// Internal action-type glob -> human label. Unknown types pass through raw so none are hidden.
export function actionLabel(actionType: string, t: (key: string) => string) {
  return actionType === "agent.proposal" ? t("actionTypeAgentProposal") : actionType;
}

// requestedBy is a compound principal string:
//   "agent:<uuid>:session:<uuid>" | "system:<job>:<subject>" | a user id/email.
export function actorLabel(requestedBy: string | null | undefined, t: (key: string) => string) {
  if (!requestedBy) return { kind: "", short: t("unknownActor"), full: "" };
  const agent = requestedBy.match(/^agent:([0-9a-fA-F]{8})[0-9a-fA-F-]*/);
  if (agent) return { kind: "agent", short: `${agent[1]}…`, full: requestedBy };
  const sys = requestedBy.match(/^system:([^:]+)(?::(.+))?$/);
  if (sys) return { kind: "system", short: sys[2] ? `${sys[1]} · ${sys[2]}` : sys[1], full: requestedBy };
  return { kind: "actor", short: requestedBy, full: requestedBy };
}

export function inr(paise: number | null | undefined) {
  return paise == null
    ? "—"
    : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(
        paise / 100
      );
}

// The labels a workflow approval node asked the approver to fill in (payload.formFields).
export function requestedFields(payloadJson: string | null | undefined): string[] {
  if (!payloadJson) return [];
  try {
    const fields = JSON.parse(payloadJson)?.formFields;
    return Array.isArray(fields) ? fields.filter((f): f is string => typeof f === "string" && f.trim() !== "") : [];
  } catch {
    return [];
  }
}

// Status -> shared state-pill hue (token, not a raw colour).
export function statusState(status: string): string {
  if (status === "PENDING") return "var(--state-warning)";
  if (status === "APPROVED") return "var(--state-success)";
  if (status === "REJECTED" || status === "EXECUTION_FAILED" || status === "DEAD_LETTERED") return "var(--state-error)";
  return "var(--state-idle, var(--muted-foreground))";
}
