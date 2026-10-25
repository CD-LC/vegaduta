// Pure helpers for the approvals queue experience: expired-vs-rejected, requester parsing, a name-based risk
// estimate, argument redaction, queue filters and bulk-decision eligibility. No React, no I/O.
//
// Facts this file relies on (all verified against core when it was written):
//  - A request that expired undecided is stored as REJECTED by the actor "system:approval-expiry"
//    (core ApprovalRequest.EXPIRY_ACTOR / isExpired()). There is no EXPIRED status on the wire.
//  - A gated tool call is an approval whose actionType is "mcp_tool.<name>" and whose requestedBy is
//    "agent:<agentId>:session:<sessionId>" (GuardedToolCallback).
//  - The server has NO read-only/destructive taxonomy for tools (AgentAutonomyTier javadoc says so). The risk
//    below is an ESTIMATE from the tool name, labelled as such everywhere it is shown, and it only ever decides
//    what the UI offers (bulk approve), never what the server allows.
import {
  ARGS_PREVIEW_MAX_CHARS,
  TOOL_CALL_ACTION_PREFIX,
  toolNameOf,
  truncateText,
  type ToolApprovalRow,
} from "../agents/toolApprovalMatching";
import type { ApprovalRequestItem } from "./approvalsModel";

/** Must stay in sync with core ApprovalRequest.EXPIRY_ACTOR. */
export const EXPIRY_ACTOR = "system:approval-expiry";

/** The UI-only "Expired" filter: the server has no such status, it is REJECTED rows decided by EXPIRY_ACTOR. */
export const EXPIRED_FILTER = "EXPIRED";

/** True for a request that closed because nobody decided in time (not one a person rejected). */
export function isExpiredRequest(item: Pick<ApprovalRequestItem, "status" | "decidedBy">): boolean {
  return item.status === "REJECTED" && item.decidedBy === EXPIRY_ACTOR;
}

/** The status to show: EXPIRED for an expired request, the stored status otherwise. */
export function displayStatus(item: Pick<ApprovalRequestItem, "status" | "decidedBy">): string {
  return isExpiredRequest(item) ? EXPIRED_FILTER : item.status;
}

/** The status the list endpoint can filter on for a UI filter value (EXPIRED reads REJECTED and filters locally). */
export function serverStatusFor(filter: string): string {
  return filter === EXPIRED_FILTER ? "REJECTED" : filter;
}

export function isToolCallRequest(item: Pick<ApprovalRequestItem, "actionType">): boolean {
  return typeof item.actionType === "string" && item.actionType.startsWith(TOOL_CALL_ACTION_PREFIX);
}

/** The tool a request is about ("send_email"), or the raw action type for anything that is not a tool call. */
export function requestToolName(item: ApprovalRequestItem): string {
  return isToolCallRequest(item) ? toolNameOf(item as ToolApprovalRow) : item.actionType;
}

/** "agent:<agentId>:session:<sessionId>" -> ids; null for any other requester (a person, a system job). */
export function requesterIds(requestedBy: string | null | undefined): { agentId: string; sessionId: string } | null {
  if (typeof requestedBy !== "string") return null;
  const match = /^agent:([^:\s]+):session:(.+)$/.exec(requestedBy);
  return match ? { agentId: match[1], sessionId: match[2] } : null;
}

/** The agent id of a request, whether it came from a conversation ("agent:<id>:session:<sid>") or just "agent:<id>". */
export function requestAgentId(requestedBy: string | null | undefined): string | null {
  if (typeof requestedBy !== "string") return null;
  const match = /^agent:([^:\s]+)/.exec(requestedBy);
  return match ? match[1] : null;
}

// ---------------------------------------------------------------------------------------------------------------
// Risk estimate
// ---------------------------------------------------------------------------------------------------------------

export type ToolRisk = "low" | "medium" | "high";

// Verbs that act on the world. Mirrors the server's own default-sensitive action patterns (ApprovalPolicyService:
// send, delete, payment, refund, charge, transfer, code run/apply).
const ACTION_VERBS = new Set([
  "send",
  "post",
  "publish",
  "reply",
  "tweet",
  "invite",
  "broadcast",
  "notify",
  "delete",
  "remove",
  "drop",
  "destroy",
  "purge",
  "wipe",
  "erase",
  "cancel",
  "revoke",
  "terminate",
  "pay",
  "refund",
  "charge",
  "transfer",
  "purchase",
  "buy",
  "withdraw",
  "exec",
  "execute",
  "run",
  "deploy",
  "apply",
  "commit",
  "push",
  "merge",
  "install",
  "write",
  "overwrite",
  "upload",
  "grant",
  "share",
  "sudo",
]);
// Nouns that make a tool sensitive on their own (create_invoice, payment_link, run via shell) - but NOT when the
// name leads with a read verb ("get_invoice", "list_messages" are lookups).
const SENSITIVE_NOUNS = new Set([
  "payment",
  "payments",
  "invoice",
  "billing",
  "order",
  "email",
  "sms",
  "message",
  "shell",
  "bash",
  "command",
]);
const MEDIUM_VERBS = new Set([
  "create",
  "update",
  "set",
  "edit",
  "add",
  "insert",
  "save",
  "put",
  "patch",
  "move",
  "rename",
  "schedule",
  "book",
  "assign",
  "tag",
  "label",
  "archive",
  "enable",
  "disable",
]);
const READ_VERBS = new Set([
  "get",
  "list",
  "read",
  "search",
  "find",
  "fetch",
  "lookup",
  "query",
  "view",
  "describe",
  "show",
  "count",
  "check",
  "status",
  "summarize",
  "summarise",
  "preview",
  "inspect",
  "retrieve",
  "browse",
  "download",
  "extract",
]);

function nameTokens(name: string): string[] {
  return name
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
}

/**
 * An estimate of how consequential a tool is, from its name alone. "low" means the name LEADS with a read verb
 * (get / list / search ...) and contains no acting verb anywhere - the only class the bulk-approve control will
 * take. Anything that sends, deletes, pays or runs code is "high"; unknown names are "medium" (never "low").
 */
export function toolRisk(toolName: string): ToolRisk {
  const tokens = nameTokens(toolName);
  if (tokens.length === 0) return "medium";
  if (tokens.some((t) => ACTION_VERBS.has(t))) return "high";
  if (READ_VERBS.has(tokens[0])) return "low";
  if (tokens.some((t) => SENSITIVE_NOUNS.has(t))) return "high";
  if (tokens.some((t) => MEDIUM_VERBS.has(t))) return "medium";
  return "medium";
}

/** Risk of a whole request: tool calls by name; every other action type is "high" (sends, payments, proposals...). */
export function requestRisk(item: ApprovalRequestItem): ToolRisk {
  return isToolCallRequest(item) ? toolRisk(requestToolName(item)) : "high";
}

// ---------------------------------------------------------------------------------------------------------------
// Arguments: redacted preview
// ---------------------------------------------------------------------------------------------------------------

const SENSITIVE_KEY =
  /pass(word|wd)?|secret|token|api[-_]?key|authorization|credential|cookie|bearer|private[-_]?key|session[-_]?id|otp|cvv|ssn/i;
export const REDACTED_MARK = "••••••";

function redactValue(value: unknown, counter: { n: number }): unknown {
  if (Array.isArray(value)) return value.map((v) => redactValue(v, counter));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (SENSITIVE_KEY.test(k) && v != null && v !== "") {
        counter.n += 1;
        out[k] = REDACTED_MARK;
      } else {
        out[k] = redactValue(v, counter);
      }
    }
    return out;
  }
  return value;
}

export interface RedactedArguments {
  /** Display text (render inside <pre> as text, never as HTML); null when there is nothing to show. */
  text: string | null;
  /** How many values were hidden because their key looks like a credential. */
  redactedCount: number;
  /** True when the arguments are longer than the cap - the preview shows less than what would run. */
  truncated: boolean;
}

/**
 * The arguments a gated call would run with, as display text with credential-looking values hidden. The replay
 * envelope's plumbing (tenant, subject, ids) is never shown. Truncation is reported so the caller can refuse a
 * one-click approve of something the person could not read in full.
 */
export function redactedArguments(
  payload: string | null | undefined,
  max: number = ARGS_PREVIEW_MAX_CHARS
): RedactedArguments {
  if (typeof payload !== "string" || !payload.trim()) return { text: null, redactedCount: 0, truncated: false };
  const counter = { n: 0 };
  let text: string;
  try {
    const parsed = JSON.parse(payload);
    let args: unknown = parsed;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed) && "replay" in parsed) {
      if ("arguments" in parsed) args = (parsed as { arguments: unknown }).arguments;
      else if (typeof (parsed as { argumentsRaw?: unknown }).argumentsRaw === "string")
        args = (parsed as { argumentsRaw: string }).argumentsRaw;
      else {
        // An action a tool gated itself on (code-workspace run/apply, channel send) keeps its own fields at the
        // top level next to the envelope: they are what would run.
        const { replay: _envelope, ...own } = parsed as Record<string, unknown>;
        if (Object.keys(own).length === 0) return { text: null, redactedCount: 0, truncated: false };
        args = own;
      }
    }
    if (typeof args === "string") {
      // Unparseable arguments the model produced: mask `key: value` / `key=value` pairs that look like credentials.
      text = args.replace(
        new RegExp(`(${SENSITIVE_KEY.source})(["']?\\s*[:=]\\s*)("[^"]*"|'[^']*'|\\S+)`, "gi"),
        (_m, key, sep) => {
          counter.n += 1;
          return `${key}${sep}${REDACTED_MARK}`;
        }
      );
    } else {
      text = JSON.stringify(redactValue(args, counter), null, 2);
    }
  } catch {
    text = payload;
  }
  if (!text || text === "{}" || text === "null") return { text: null, redactedCount: 0, truncated: false };
  return { text: truncateText(text, max), redactedCount: counter.n, truncated: text.length > max };
}

/**
 * Strips the console's `<app_context>...</app_context>` preamble from a recorded goal. Rows parked before the
 * server-side strip (core now records the person's words via ChatThreadService.withoutAppContext) still carry
 * the platform block, so the card reads it off too rather than showing an old row the plumbing.
 */
export function stripAppContext(goal: string): string {
  const m = /^\s*<app_context>[\s\S]*?<\/app_context>/i.exec(goal);
  const stripped = (m ? goal.slice(m[0].length) : goal).trim();
  return stripped || goal.trim();
}

/** The task the person had asked for when the call was parked (replay.goal), if core recorded it. */
export function replayGoal(payload: string | null | undefined): string | null {
  if (typeof payload !== "string" || !payload.trim()) return null;
  try {
    const goal = JSON.parse(payload)?.replay?.goal;
    return typeof goal === "string" && goal.trim() ? stripAppContext(goal) : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Queue filters
// ---------------------------------------------------------------------------------------------------------------

/** Age buckets: "" = any; newer/older than a threshold. */
export const AGE_FILTERS = ["", "newer1h", "older1h", "older24h", "older7d"] as const;
export type AgeFilter = (typeof AGE_FILTERS)[number];

const HOUR = 3_600_000;
const AGE_LIMITS: Record<Exclude<AgeFilter, "">, { older: boolean; ms: number }> = {
  newer1h: { older: false, ms: HOUR },
  older1h: { older: true, ms: HOUR },
  older24h: { older: true, ms: 24 * HOUR },
  older7d: { older: true, ms: 7 * 24 * HOUR },
};

export interface QueueFilters {
  /** Agent id, "" = any. */
  agent: string;
  /** Tool name (or raw action type), "" = any. */
  tool: string;
  age: AgeFilter;
}

export const NO_FILTERS: QueueFilters = { agent: "", tool: "", age: "" };

/** True when a filter the server cannot apply is active (the list is then read in one larger window). */
export function hasClientFilters(filters: QueueFilters, statusFilter: string): boolean {
  return !!(filters.agent || filters.tool || filters.age) || statusFilter === EXPIRED_FILTER;
}

export function matchesFilters(
  item: ApprovalRequestItem,
  filters: QueueFilters,
  statusFilter: string,
  now: number
): boolean {
  if (statusFilter === EXPIRED_FILTER && !isExpiredRequest(item)) return false;
  if (filters.agent && requestAgentId(item.requestedBy) !== filters.agent) return false;
  if (filters.tool && requestToolName(item) !== filters.tool) return false;
  if (filters.age) {
    const created = item.createdAt ? new Date(item.createdAt).getTime() : NaN;
    if (!Number.isFinite(created)) return false;
    const rule = AGE_LIMITS[filters.age];
    const age = now - created;
    if (rule.older ? age < rule.ms : age >= rule.ms) return false;
  }
  return true;
}

/** Distinct agent ids and tool names present in the rows, for the filter pickers (sorted, no blanks). */
export function filterOptions(items: ApprovalRequestItem[]): { agents: string[]; tools: string[] } {
  const agents = new Set<string>();
  const tools = new Set<string>();
  for (const item of items) {
    const agent = requestAgentId(item.requestedBy);
    if (agent) agents.add(agent);
    const tool = requestToolName(item);
    if (tool) tools.add(tool);
  }
  return { agents: [...agents].sort(), tools: [...tools].sort() };
}

// ---------------------------------------------------------------------------------------------------------------
// Bulk decisions
// ---------------------------------------------------------------------------------------------------------------

export type BulkEligibility =
  | { ok: true; tool: string; risk: ToolRisk; count: number }
  | { ok: false; reason: "none" | "notPending" | "mixedTools" | "notToolCall" | "notLowRisk" };

/**
 * Whether a selection can be decided in one go. Both directions need every selected request to be a pending call
 * to the SAME tool, so one confirmation honestly describes all of them. Approving also needs the tool to read as
 * low-risk; denying never does (saying no is the safe direction).
 */
export function bulkEligibility(selected: ApprovalRequestItem[], direction: "approve" | "deny"): BulkEligibility {
  if (selected.length === 0) return { ok: false, reason: "none" };
  if (selected.some((i) => i.status !== "PENDING")) return { ok: false, reason: "notPending" };
  if (selected.some((i) => !isToolCallRequest(i))) return { ok: false, reason: "notToolCall" };
  const tool = requestToolName(selected[0]);
  if (selected.some((i) => requestToolName(i) !== tool)) return { ok: false, reason: "mixedTools" };
  const risk = toolRisk(tool);
  if (direction === "approve" && risk !== "low") return { ok: false, reason: "notLowRisk" };
  return { ok: true, tool, risk, count: selected.length };
}
