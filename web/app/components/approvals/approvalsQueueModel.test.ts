import { describe, expect, it } from "vitest";
import {
  EXPIRY_ACTOR,
  REDACTED_MARK,
  bulkEligibility,
  displayStatus,
  filterOptions,
  hasClientFilters,
  isExpiredRequest,
  matchesFilters,
  redactedArguments,
  replayGoal,
  requestAgentId,
  requesterIds,
  serverStatusFor,
  toolRisk,
} from "./approvalsQueueModel";
import { expiryDeadlineMs, formatRemaining } from "./approvalExpiry";
import { parseTtlSeconds } from "../../lib/api/approvalsQueue";
import type { ApprovalRequestItem } from "./approvalsModel";

const NOW = Date.parse("2026-10-23T12:00:00Z");
// The summary core writes is always "Tool call: <name>", matching the action type.
const row = (over: Partial<ApprovalRequestItem> = {}): ApprovalRequestItem => ({
  id: "r1",
  status: "PENDING",
  actionType: "mcp_tool.get_invoice",
  summary: "Tool call: get_invoice",
  requestedBy: "agent:a1:session:s1",
  createdAt: "2026-10-23T11:00:00Z",
  payload: JSON.stringify({ replay: { version: 1, tenantId: "t-secret", goal: "Find the invoice" }, arguments: { id: 7 } }),
  ...over,
  ...(over.actionType && !over.summary ? { summary: `Tool call: ${over.actionType.replace(/^mcp_tool\./, "")}` } : {}),
});

describe("expired vs rejected", () => {
  it("treats a rejection by the expiry job as expired, and a person's rejection as rejected", () => {
    expect(isExpiredRequest({ status: "REJECTED", decidedBy: EXPIRY_ACTOR })).toBe(true);
    expect(isExpiredRequest({ status: "REJECTED", decidedBy: "user-1" })).toBe(false);
    expect(isExpiredRequest({ status: "APPROVED", decidedBy: EXPIRY_ACTOR })).toBe(false);
    expect(displayStatus({ status: "REJECTED", decidedBy: EXPIRY_ACTOR })).toBe("EXPIRED");
    expect(displayStatus({ status: "REJECTED", decidedBy: "user-1" })).toBe("REJECTED");
  });
  it("reads expired rows through the REJECTED server filter", () => {
    expect(serverStatusFor("EXPIRED")).toBe("REJECTED");
    expect(serverStatusFor("PENDING")).toBe("PENDING");
    expect(hasClientFilters({ agent: "", tool: "", age: "" }, "EXPIRED")).toBe(true);
    expect(hasClientFilters({ agent: "", tool: "", age: "" }, "PENDING")).toBe(false);
  });
});

describe("requester parsing", () => {
  it("splits agent and session, and only trusts the exact shape", () => {
    expect(requesterIds("agent:a1:session:s-1")).toEqual({ agentId: "a1", sessionId: "s-1" });
    expect(requesterIds("system:email-poll:inbox")).toBeNull();
    expect(requesterIds(null)).toBeNull();
    expect(requestAgentId("agent:a1")).toBe("a1");
    expect(requestAgentId("user@example.com")).toBeNull();
  });
});

describe("toolRisk (name-based estimate)", () => {
  it.each([
    ["get_invoice", "low"],
    ["searchDocuments", "low"],
    ["list_files", "low"],
    ["send_email", "high"],
    ["deleteContact", "high"],
    ["create_refund", "high"],
    ["run_shell", "high"],
    ["create_ticket", "medium"],
    ["frobnicate", "medium"],
    ["read_and_delete", "high"],
    ["list_messages", "low"],
    ["create_invoice", "high"],
  ])("%s -> %s", (name, risk) => {
    expect(toolRisk(name)).toBe(risk);
  });
});

describe("redactedArguments", () => {
  it("shows the own fields of an action a tool gated itself on, redacted, and never the envelope", () => {
    const payload = JSON.stringify({
      repoId: "billing",
      command: "deploy --token=abc123",
      password: "hunter2",
      replay: { toolName: "run_in_code_workspace", tenantId: "t-secret", goal: "g" },
    });
    const out = redactedArguments(payload);
    expect(out.text).toContain("deploy");
    expect(out.text).not.toContain("hunter2");
    expect(out.text).not.toContain("t-secret");
    expect(out.redactedCount).toBeGreaterThan(0);
    expect(redactedArguments(JSON.stringify({ replay: { toolName: "x" } })).text).toBeNull();
  });

  it("hides credential-looking values, never shows replay plumbing, and counts what it hid", () => {
    const payload = JSON.stringify({
      replay: { version: 1, tenantId: "t-secret", subject: "u-secret" },
      arguments: { to: "x@example.com", apiKey: "sk-live-123", nested: { password: "hunter2", note: "hi" } },
    });
    const out = redactedArguments(payload);
    expect(out.text).toContain("x@example.com");
    expect(out.text).toContain(REDACTED_MARK);
    expect(out.text).not.toContain("sk-live-123");
    expect(out.text).not.toContain("hunter2");
    expect(out.text).not.toContain("t-secret");
    expect(out.redactedCount).toBe(2);
    expect(out.truncated).toBe(false);
  });
  it("masks key=value pairs in unparseable arguments", () => {
    const payload = JSON.stringify({ replay: { version: 1 }, argumentsRaw: "to=bob token=abc123" });
    const out = redactedArguments(payload);
    expect(out.text).toContain("to=bob");
    expect(out.text).not.toContain("abc123");
    expect(out.redactedCount).toBe(1);
  });
  it("reports truncation instead of silently cutting", () => {
    const payload = JSON.stringify({ replay: { version: 1 }, arguments: { body: "x".repeat(500) } });
    const out = redactedArguments(payload, 100);
    expect(out.truncated).toBe(true);
    expect(out.text!.length).toBeLessThanOrEqual(101);
  });
  it("returns nothing for empty arguments", () => {
    expect(redactedArguments(JSON.stringify({ replay: { version: 1 }, arguments: {} })).text).toBeNull();
    expect(redactedArguments(null).text).toBeNull();
  });
  it("reads the task the call was parked for", () => {
    expect(replayGoal(row().payload)).toBe("Find the invoice");
    expect(replayGoal("not json")).toBeNull();
  });
});

describe("queue filters", () => {
  const items = [
    row({ id: "a", requestedBy: "agent:a1:session:s1", actionType: "mcp_tool.get_invoice", createdAt: "2026-10-23T11:30:00Z" }),
    row({ id: "b", requestedBy: "agent:a2:session:s2", actionType: "mcp_tool.send_email", createdAt: "2026-10-23T08:00:00Z" }),
    row({ id: "c", requestedBy: "agent:a2:session:s3", actionType: "mcp_tool.send_email", createdAt: "2026-10-10T08:00:00Z" }),
  ];
  const ids = (f: Parameters<typeof matchesFilters>[1], status = "PENDING") =>
    items.filter((i) => matchesFilters(i, f, status, NOW)).map((i) => i.id);
  it("filters by agent, tool and age together", () => {
    expect(ids({ agent: "a2", tool: "", age: "" })).toEqual(["b", "c"]);
    expect(ids({ agent: "", tool: "send_email", age: "older24h" })).toEqual(["c"]);
    expect(ids({ agent: "", tool: "", age: "newer1h" })).toEqual(["a"]);
    expect(ids({ agent: "", tool: "", age: "older7d" })).toEqual(["c"]);
    expect(ids({ agent: "a1", tool: "send_email", age: "" })).toEqual([]);
  });
  it("EXPIRED keeps only rows the expiry job closed", () => {
    const mix = [
      row({ id: "e", status: "REJECTED", decidedBy: EXPIRY_ACTOR }),
      row({ id: "r", status: "REJECTED", decidedBy: "u1" }),
    ];
    expect(mix.filter((i) => matchesFilters(i, { agent: "", tool: "", age: "" }, "EXPIRED", NOW)).map((i) => i.id)).toEqual(["e"]);
  });
  it("lists the agents and tools present for the pickers", () => {
    expect(filterOptions(items)).toEqual({ agents: ["a1", "a2"], tools: ["get_invoice", "send_email"] });
  });
});

describe("bulkEligibility", () => {
  const lookup = (id: string) => row({ id, actionType: "mcp_tool.get_invoice" });
  const send = (id: string) => row({ id, actionType: "mcp_tool.send_email" });
  it("allows approve and deny for several pending low-risk calls to one tool", () => {
    expect(bulkEligibility([lookup("1"), lookup("2")], "approve")).toMatchObject({ ok: true, tool: "get_invoice", risk: "low", count: 2 });
    expect(bulkEligibility([lookup("1"), lookup("2")], "deny")).toMatchObject({ ok: true });
  });
  it("refuses approve for a risky tool but still allows deny", () => {
    expect(bulkEligibility([send("1"), send("2")], "approve")).toEqual({ ok: false, reason: "notLowRisk" });
    expect(bulkEligibility([send("1"), send("2")], "deny")).toMatchObject({ ok: true, tool: "send_email" });
  });
  it("refuses mixed tools, non-tool requests, decided requests and nothing", () => {
    expect(bulkEligibility([lookup("1"), send("2")], "deny")).toEqual({ ok: false, reason: "mixedTools" });
    expect(bulkEligibility([row({ actionType: "channel.slack.send" })], "deny")).toEqual({ ok: false, reason: "notToolCall" });
    expect(bulkEligibility([row({ status: "APPROVED" })], "deny")).toEqual({ ok: false, reason: "notPending" });
    expect(bulkEligibility([], "approve")).toEqual({ ok: false, reason: "none" });
  });
});

describe("expiry maths", () => {
  it("parses the TTL formats the settings catalog may send", () => {
    expect(parseTtlSeconds("PT24H")).toBe(86_400);
    expect(parseTtlSeconds("P3D")).toBe(259_200);
    expect(parseTtlSeconds("PT90M")).toBe(5_400);
    expect(parseTtlSeconds(3600)).toBe(3600);
    expect(parseTtlSeconds("3600")).toBe(3600);
    expect(parseTtlSeconds("24h")).toBe(86_400);
    expect(parseTtlSeconds("PT0S")).toBe(0);
    expect(parseTtlSeconds("soon")).toBeNull();
    expect(parseTtlSeconds(undefined)).toBeNull();
  });
  it("computes the deadline only for pending requests with a TTL", () => {
    const created = "2026-10-23T11:00:00Z";
    expect(expiryDeadlineMs({ status: "PENDING", createdAt: created }, 3600)).toBe(Date.parse("2026-10-23T12:00:00Z"));
    expect(expiryDeadlineMs({ status: "PENDING", createdAt: created }, 0)).toBeNull();
    expect(expiryDeadlineMs({ status: "PENDING", createdAt: created }, null)).toBeNull();
    expect(expiryDeadlineMs({ status: "APPROVED", createdAt: created }, 3600)).toBeNull();
    expect(expiryDeadlineMs({ status: "PENDING", createdAt: created, expiresAt: "2026-10-24T00:00:00Z" }, 3600)).toBe(
      Date.parse("2026-10-24T00:00:00Z")
    );
  });
  it("formats the time left in at most two units", () => {
    const t = (k: string, v?: Record<string, unknown>) => `${k}:${Object.values(v ?? {}).join(",")}`;
    expect(formatRemaining(90_000, t)).toBe("durationMinutes:1");
    expect(formatRemaining(3 * 3_600_000 + 5 * 60_000, t)).toBe("durationHoursMinutes:3,5");
    expect(formatRemaining(2 * 86_400_000 + 4 * 3_600_000, t)).toBe("durationDaysHours:2,4");
    expect(formatRemaining(40_000, t)).toBe("durationSeconds:40");
  });
});
