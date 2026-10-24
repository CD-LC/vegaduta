// Inline tool-confirmation cards (trust T2) - the pure half. The matcher is
// the security-relevant part: GET /api/approvals returns the whole
// workspace's pending queue, and a card rendered in the wrong conversation
// would invite someone to approve another session's tool call.

import { describe, expect, it } from "vitest";
import {
  approvalArgsPreview,
  approvalArgsText,
  approvalArgsTruncated,
  ARGS_PREVIEW_MAX_CHARS,
  matchSessionApprovals,
  toolApprovalPollDelayMs,
  outcomeOf,
  sessionRequester,
  toolNameOf,
  truncateText,
  type ToolApprovalRow,
} from "./toolApprovalMatching";

const row = (over: Partial<ToolApprovalRow> = {}): ToolApprovalRow => ({
  id: "r1",
  actionType: "mcp_tool.send_email",
  summary: "Tool call: send_email",
  status: "PENDING",
  requestedBy: "agent:a1:session:s1",
  payload: null,
  createdAt: "2026-10-03T10:00:00Z",
  ...over,
});

describe("matchSessionApprovals", () => {
  it("keeps only this agent+session's tool-call rows, oldest first", () => {
    const rows = [
      row({ id: "late", createdAt: "2026-10-03T10:05:00Z" }),
      row({ id: "other-session", requestedBy: "agent:a1:session:s2" }),
      row({ id: "other-agent", requestedBy: "agent:a2:session:s1" }),
      row({ id: "early", createdAt: "2026-10-03T10:01:00Z" }),
      row({ id: "workflow", actionType: "workflow.approval" }),
      row({ id: "human", requestedBy: "user-123" }),
    ];
    expect(matchSessionApprovals(rows, "a1", "s1").map((r) => r.id)).toEqual(["early", "late"]);
  });

  it("matches the requester exactly - a session id that is a prefix of another never matches", () => {
    const rows = [row({ id: "long", requestedBy: "agent:a1:session:s10" })];
    expect(matchSessionApprovals(rows, "a1", "s1")).toEqual([]);
    expect(sessionRequester("a1", "s10")).toBe("agent:a1:session:s10");
  });

  it("returns nothing without an agent, a session, or a list", () => {
    expect(matchSessionApprovals([row()], null, "s1")).toEqual([]);
    expect(matchSessionApprovals([row()], "a1", null)).toEqual([]);
    expect(matchSessionApprovals(null, "a1", "s1")).toEqual([]);
    expect(matchSessionApprovals({} as unknown as ToolApprovalRow[], "a1", "s1")).toEqual([]);
  });
});

describe("toolNameOf", () => {
  it("reads the name from the summary, then the action type, then falls back", () => {
    expect(toolNameOf(row())).toBe("send_email");
    expect(toolNameOf(row({ summary: "something else", actionType: "mcp_tool.jira_create" }))).toBe("jira_create");
    expect(toolNameOf(row({ summary: "Pay vendor", actionType: "payment" }))).toBe("Pay vendor");
    expect(toolNameOf(row({ summary: null, actionType: null }))).toBe("tool");
  });

  it("drops the annotations core appends to a summary, so queued calls to one tool read as that tool", () => {
    expect(toolNameOf(row({ summary: "Tool call: web_search [queued behind: web_search]" }))).toBe("web_search");
    expect(toolNameOf(row({ summary: "Tool call: send_email [flagged: tainted turn]" }))).toBe("send_email");
  });
});

describe("approvalArgsPreview", () => {
  it("shows only the arguments of a replay envelope, never the replay block", () => {
    const payload = JSON.stringify({
      replay: { version: 1, toolName: "send_email", tenantId: "t-secret", subject: "user-1" },
      arguments: { to: "x@example.com", body: "hi" },
    });
    const preview = approvalArgsPreview(payload)!;
    expect(preview).toContain('"to": "x@example.com"');
    expect(preview).not.toContain("t-secret");
    expect(preview).not.toContain("replay");
  });

  it("falls back to argumentsRaw and to a legacy raw payload", () => {
    expect(approvalArgsPreview(JSON.stringify({ replay: { version: 1 }, argumentsRaw: "not json {" }))).toBe("not json {");
    expect(approvalArgsPreview('{"q":"x"}')).toBe('{\n  "q": "x"\n}');
    expect(approvalArgsPreview("plain text")).toBe("plain text");
  });

  it("returns null for empty payloads and truncates long ones", () => {
    expect(approvalArgsPreview(null)).toBeNull();
    expect(approvalArgsPreview("  ")).toBeNull();
    expect(approvalArgsPreview(JSON.stringify({ replay: {}, arguments: {} }))).toBeNull();
    const long = approvalArgsPreview(JSON.stringify({ text: "y".repeat(5000) }), 50)!;
    expect(long.length).toBe(51);
    expect(long.endsWith("…")).toBe(true);
  });

  it("shows a long argument whole by default, so nothing can hide past a short cut", () => {
    const tail = "bcc: attacker@evil.example";
    const payload = JSON.stringify({ replay: { version: 1 }, arguments: { body: "y".repeat(5000), extra: tail } });
    const shown = approvalArgsPreview(payload)!;
    expect(shown).toContain(tail);
    expect(shown.endsWith("…")).toBe(false);
    expect(approvalArgsTruncated(payload)).toBe(false);
    expect(approvalArgsText(payload)).toBe(shown);
  });

  it("flags arguments that exceed the cap instead of cutting them silently", () => {
    const payload = JSON.stringify({ text: "y".repeat(ARGS_PREVIEW_MAX_CHARS + 10) });
    expect(approvalArgsTruncated(payload)).toBe(true);
    expect(approvalArgsPreview(payload)!.length).toBe(ARGS_PREVIEW_MAX_CHARS + 1);
    expect(approvalArgsTruncated(null)).toBe(false);
    expect(approvalArgsTruncated(JSON.stringify({ replay: {}, arguments: {} }))).toBe(false);
  });

  it("keeps markup as inert text", () => {
    expect(approvalArgsPreview(JSON.stringify({ html: "<img src=x onerror=alert(1)>" }))).toContain("<img src=x onerror=alert(1)>");
  });
});

describe("outcomeOf / truncateText", () => {
  it("is null while pending and maps each decided state", () => {
    expect(outcomeOf(row())).toBeNull();
    expect(outcomeOf(null)).toBeNull();
    expect(outcomeOf(row({ status: "APPROVED", executionResult: "sent" }))).toEqual({ kind: "done", detail: "sent" });
    expect(outcomeOf(row({ status: "APPROVED" }))).toEqual({ kind: "done", detail: null });
    expect(outcomeOf(row({ status: "APPROVED", executionError: "smtp down" }))).toEqual({ kind: "failed", detail: "smtp down" });
    expect(outcomeOf(row({ status: "EXECUTION_FAILED" }))).toEqual({ kind: "failed", detail: null });
    expect(outcomeOf(row({ status: "REJECTED", rejectionReason: "no" }))).toEqual({ kind: "rejected", detail: "no" });
  });

  it("truncates with an ellipsis only past the limit", () => {
    expect(truncateText("abc", 3)).toBe("abc");
    expect(truncateText("abcd", 3)).toBe("abc…");
  });
});

describe("toolApprovalPollDelayMs", () => {
  it("backs off the longer a card has been waiting, then stops", () => {
    expect(toolApprovalPollDelayMs(0)).toBe(10_000);
    expect(toolApprovalPollDelayMs(119_000)).toBe(10_000);
    expect(toolApprovalPollDelayMs(120_000)).toBe(30_000);
    expect(toolApprovalPollDelayMs(10 * 60_000)).toBe(60_000);
    expect(toolApprovalPollDelayMs(30 * 60_000)).toBeNull();
    expect(toolApprovalPollDelayMs(Number.NaN)).toBe(10_000);
  });
});

// Actions a tool gates ITSELF on (a code-workspace run or apply, a channel send) are recorded by core with the
// conversation envelope next to the action's own fields; requestedBy is the person, not the agent/session label.
// The card must find them in the conversation they belong to - and only there - so the person can decide them
// inline and core's continuation (the same one a guarded tool call gets) is followed by the surface.
describe("self-gated actions (code workspace, channel send)", () => {
  const envelope = (over: Record<string, unknown> = {}) => ({
    version: 1,
    toolName: "run_in_code_workspace",
    agentId: "a1",
    requestedAgentId: "a1",
    sessionId: "s1",
    memorySessionId: "c1:abc",
    goal: "Run the tests and fix what fails",
    tenantId: "t1",
    subject: "user-123",
    ...over,
  });
  const runRow = (over: Partial<ToolApprovalRow> = {}, replay: Record<string, unknown> = {}): ToolApprovalRow =>
    row({
      id: "run1",
      actionType: "codeintel.workspace.run",
      summary: "Run in code workspace ws-1 (repo billing): mvn -q test",
      requestedBy: "user-123",
      payload: JSON.stringify({
        repoId: "billing",
        workspaceId: "ws-1",
        command: "mvn -q test",
        attempts: 1,
        adminOnly: true,
        replay: envelope(replay),
      }),
      ...over,
    });

  it("matches a run/apply/send recorded for this agent and session", () => {
    const rows = [
      runRow(),
      runRow({ id: "apply1", actionType: "codeintel.workspace.apply" }, { toolName: "apply_code_changes" }),
      runRow({ id: "send1", actionType: "channel.telegram.send" }, { toolName: "channel_send_message" }),
    ];
    expect(matchSessionApprovals(rows, "a1", "s1").map((r) => r.id)).toEqual(["run1", "apply1", "send1"]);
  });

  it("matches on the agent the conversation was requested under as well as the routed one", () => {
    const routed = runRow({}, { agentId: "a-clone", requestedAgentId: "a1" });
    expect(matchSessionApprovals([routed], "a1", "s1")).toHaveLength(1);
    expect(matchSessionApprovals([routed], "a-clone", "s1")).toHaveLength(1);
  });

  it("never shows another session's, another agent's or a prefix-colliding session's action", () => {
    const rows = [
      runRow({ id: "other-session" }, { sessionId: "s2" }),
      runRow({ id: "prefix" }, { sessionId: "s10" }),
      runRow({ id: "other-agent" }, { agentId: "a2", requestedAgentId: "a2" }),
    ];
    expect(matchSessionApprovals(rows, "a1", "s1")).toEqual([]);
  });

  it("ignores an envelope that names no goal (core cannot continue it) and a payload that is not JSON", () => {
    expect(matchSessionApprovals([runRow({}, { goal: undefined })], "a1", "s1")).toEqual([]);
    expect(matchSessionApprovals([runRow({ payload: "not json" })], "a1", "s1")).toEqual([]);
    expect(matchSessionApprovals([runRow({ payload: null })], "a1", "s1")).toEqual([]);
  });

  it("does not let an mcp_tool row's model-authored payload place it in another conversation", () => {
    const forged = row({
      id: "forged",
      requestedBy: "agent:a9:session:s9",
      payload: JSON.stringify({ replay: envelope(), arguments: {} }),
    });
    expect(matchSessionApprovals([forged], "a1", "s1")).toEqual([]);
  });

  it("names the card after the tool the agent called, not the action type", () => {
    expect(toolNameOf(runRow())).toBe("run_in_code_workspace");
    expect(toolNameOf(runRow({}, { toolName: "channel_send_message" }))).toBe("channel_send_message");
  });

  it("shows the action's own fields, never the envelope", () => {
    const text = approvalArgsText(runRow().payload);
    expect(text).toContain("mvn -q test");
    expect(text).toContain("billing");
    expect(text).not.toContain("replay");
    expect(text).not.toContain("memorySessionId");
    expect(approvalArgsPreview(JSON.stringify({ replay: envelope() }))).toBeNull();
  });
});
