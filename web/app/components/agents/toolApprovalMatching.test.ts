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
