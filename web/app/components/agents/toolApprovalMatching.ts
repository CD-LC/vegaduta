// Pure helpers for the inline tool-confirmation cards (trust T2). No React,
// no API imports - everything here is testable without a DOM.
//
// Background: when the confirmation gate parks a tool call, core records an
// approval_request row whose requestedBy is the literal
// "agent:<agentId>:session:<sessionId>" (GuardedToolCallback
// #recordPendingApprovalAndExplain), whose actionType is "mcp_tool.<name>" and
// whose summary is "Tool call: <name>". GET /api/approvals returns every
// pending row in the workspace, so the chat surface has to pick out the rows
// that belong to the conversation on screen - that is matchSessionApprovals.

/** The subset of ApprovalRequestResponse these helpers read. */
export interface ToolApprovalRow {
  id: string;
  actionType?: string | null;
  summary?: string | null;
  status?: string | null;
  requestedBy?: string | null;
  payload?: string | null;
  executionResult?: string | null;
  executionError?: string | null;
  rejectionReason?: string | null;
  createdAt?: string | null;
}

/** Must stay in sync with GuardedToolCallback.ACTION_TYPE_PREFIX. */
export const TOOL_CALL_ACTION_PREFIX = "mcp_tool.";
const SUMMARY_PREFIX = "Tool call: ";

/**
 * Cap for the arguments shown on a card. Deliberately large: the card is
 * where a person authorises the call, so they must see what they approve - a
 * short preview would let an injected call hide the harmful part (an extra
 * recipient, a URL at the end of a long body) past the cut. The cap only
 * protects the page from a pathological payload; a card whose arguments
 * exceed it says so and does not offer Approve (see approvalArgsTruncated).
 */
export const ARGS_PREVIEW_MAX_CHARS = 20_000;
/** Cap for an execution result/error echoed back into the conversation. */
export const RESULT_PREVIEW_MAX_CHARS = 800;

export function sessionRequester(agentId: string, sessionId: string): string {
  return `agent:${agentId}:session:${sessionId}`;
}

/**
 * The gated tool calls of ONE conversation, oldest first. Exact string match
 * on requestedBy - never a prefix/contains match, so session "abc" can never
 * pick up session "abcd"'s requests. Non-tool-call approvals (workflow
 * approval nodes, payments) are excluded even if a requester string collides.
 */
export function matchSessionApprovals<T extends ToolApprovalRow>(
  rows: readonly T[] | null | undefined,
  agentId: string | null | undefined,
  sessionId: string | null | undefined
): T[] {
  if (!Array.isArray(rows) || !agentId || !sessionId) return [];
  const requester = sessionRequester(agentId, sessionId);
  return rows
    .filter(
      (row) =>
        !!row &&
        typeof row.id === "string" &&
        typeof row.actionType === "string" &&
        ((row.requestedBy === requester && row.actionType.startsWith(TOOL_CALL_ACTION_PREFIX)) ||
          selfGatedInConversation(row, agentId, sessionId))
    )
    .sort((a, b) => String(a.createdAt ?? "").localeCompare(String(b.createdAt ?? "")));
}

/**
 * The conversation envelope core records on an action a tool gated ITSELF on (a code-workspace run or apply, a
 * channel send): `payload.replay` with the tool, the agent and session, and the user's goal. Null for anything
 * else, including a legacy tool-call payload (whose `replay` key, if any, is model-authored) - only an envelope
 * that names a tool AND carries a goal counts, which core writes and the model cannot.
 */
function conversationEnvelope(row: ToolApprovalRow): { toolName: string; agentIds: string[]; sessionId: string } | null {
  if (typeof row.payload !== "string" || !row.payload.includes('"replay"')) return null;
  try {
    const parsed = JSON.parse(row.payload);
    const replay = parsed && typeof parsed === "object" ? parsed.replay : null;
    if (!replay || typeof replay !== "object") return null;
    if (typeof replay.toolName !== "string" || !replay.toolName) return null;
    if (typeof replay.goal !== "string" || !replay.goal) return null;
    if (typeof replay.sessionId !== "string" || !replay.sessionId) return null;
    const agentIds = [replay.agentId, replay.requestedAgentId].filter((id): id is string => typeof id === "string" && !!id);
    return { toolName: replay.toolName, agentIds, sessionId: replay.sessionId };
  } catch {
    return null;
  }
}

/**
 * A self-gated action (not an `mcp_tool.*` call) that belongs to this conversation. Its requestedBy is the person,
 * not the agent/session label a guarded call has, so the envelope is what places it. Exact match on the session and
 * on the agent the conversation was requested under or the routed one - never a prefix match.
 */
function selfGatedInConversation(row: ToolApprovalRow, agentId: string, sessionId: string): boolean {
  if (typeof row.actionType !== "string" || row.actionType.startsWith(TOOL_CALL_ACTION_PREFIX)) return false;
  const envelope = conversationEnvelope(row);
  return !!envelope && envelope.sessionId === sessionId && envelope.agentIds.includes(agentId);
}

/** "Tool call: send_email" -> "send_email"; falls back to the action type, then the raw summary. */
export function toolNameOf(row: ToolApprovalRow): string {
  const envelope = typeof row.actionType === "string" && !row.actionType.startsWith(TOOL_CALL_ACTION_PREFIX) ? conversationEnvelope(row) : null;
  if (envelope) return envelope.toolName;
  const summary = typeof row.summary === "string" ? row.summary : "";
  if (summary.startsWith(SUMMARY_PREFIX) && summary.length > SUMMARY_PREFIX.length) {
    return summary.slice(SUMMARY_PREFIX.length).trim();
  }
  const actionType = typeof row.actionType === "string" ? row.actionType : "";
  if (actionType.startsWith(TOOL_CALL_ACTION_PREFIX) && actionType.length > TOOL_CALL_ACTION_PREFIX.length) {
    return actionType.slice(TOOL_CALL_ACTION_PREFIX.length);
  }
  return summary || actionType || "tool";
}

export function truncateText(text: string, max: number): string {
  if (max <= 0) return "";
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/**
 * The arguments the tool would run with, as display TEXT (callers render it
 * inside <pre>, never as HTML). Understands both payload shapes core writes:
 * the replay envelope {replay:{...}, arguments:{...}} / {replay, argumentsRaw}
 * and a legacy raw-arguments payload. The envelope's `replay` block (tenant,
 * subject, ids) is plumbing and deliberately not shown. Returns null when
 * there is nothing to show.
 */
export function approvalArgsPreview(payload: string | null | undefined, max: number = ARGS_PREVIEW_MAX_CHARS): string | null {
  const text = approvalArgsText(payload);
  return text == null ? null : truncateText(text, max);
}

/** True when approvalArgsPreview would cut the arguments - the card then shows less than what would run. */
export function approvalArgsTruncated(payload: string | null | undefined, max: number = ARGS_PREVIEW_MAX_CHARS): boolean {
  const text = approvalArgsText(payload);
  return text != null && text.length > max;
}

/** The complete arguments text, uncut; null when there is nothing to show. */
export function approvalArgsText(payload: string | null | undefined): string | null {
  if (typeof payload !== "string" || !payload.trim()) return null;
  let text: string;
  try {
    const parsed = JSON.parse(payload);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed) && "replay" in parsed) {
      if ("arguments" in parsed) {
        text = JSON.stringify(parsed.arguments, null, 2);
      } else if (typeof parsed.argumentsRaw === "string") {
        text = parsed.argumentsRaw;
      } else {
        // An action a tool gated itself on keeps its own fields (repo, command, files, recipient...) at the top
        // level next to the envelope: they are what would run, so they are what the card shows.
        const { replay: _envelope, ...own } = parsed as Record<string, unknown>;
        if (Object.keys(own).length === 0) return null;
        text = JSON.stringify(own, null, 2);
      }
    } else {
      text = JSON.stringify(parsed, null, 2);
    }
  } catch {
    text = payload;
  }
  if (!text || text === "{}" || text === "null") return null;
  return text;
}

/**
 * How long to wait before the next background poll of a waiting card, given
 * how long it has been waiting; null = stop polling on a timer. Backs off so
 * a chat left open on a parked call (a member who cannot decide it may wait
 * for an admin indefinitely) does not re-download the workspace's pending
 * queue every 10 seconds forever.
 */
export function toolApprovalPollDelayMs(waitedMs: number): number | null {
  if (!(waitedMs >= 0)) return 10_000;
  if (waitedMs < 2 * 60_000) return 10_000;
  if (waitedMs < 10 * 60_000) return 30_000;
  if (waitedMs < 30 * 60_000) return 60_000;
  return null;
}

export type ToolApprovalOutcome =
  | { kind: "done"; detail: string | null }
  | { kind: "failed"; detail: string | null }
  | { kind: "rejected"; detail: string | null };

/**
 * What a DECIDED row means for the conversation, or null while it is still
 * pending. executionError wins over the status: an APPROVED row whose replay
 * failed is a failure to the person who approved it.
 */
export function outcomeOf(row: ToolApprovalRow | null | undefined): ToolApprovalOutcome | null {
  if (!row) return null;
  const status = typeof row.status === "string" ? row.status : "";
  if (status === "PENDING" || status === "") return null;
  if (status === "REJECTED") {
    return { kind: "rejected", detail: row.rejectionReason ? truncateText(row.rejectionReason, RESULT_PREVIEW_MAX_CHARS) : null };
  }
  if (row.executionError || status === "EXECUTION_FAILED" || status === "DEAD_LETTERED") {
    return { kind: "failed", detail: row.executionError ? truncateText(row.executionError, RESULT_PREVIEW_MAX_CHARS) : null };
  }
  return { kind: "done", detail: row.executionResult ? truncateText(row.executionResult, RESULT_PREVIEW_MAX_CHARS) : null };
}
