// @vitest-environment node
// Node env: jsdom's crypto shim lacks subtle.digest, and parity with the Java
// server's MessageDigest is exactly what these tests pin.
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import type { LintIssue } from "./canvasOps";
import {
  dropSatisfiedErrorRoutes,
  issueId,
  issueIdInput,
  mergeLint,
  parseLintResponse,
  sha256,
  type ServerLintIssue,
} from "./lintMerge";

const nodeHash = (s: string) => createHash("sha256").update(s, "utf8").digest("hex").slice(0, 16);
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");

describe("issueId", () => {
  it("builds the server pre-image with empty strings for nulls", () => {
    expect(issueIdInput("no-trigger", null, null)).toBe("no-trigger||");
    expect(issueIdInput("dangling-edge", null, "e1")).toBe("dangling-edge||e1");
    expect(issueIdInput("disconnected-node", "n1", null, "ignored")).toBe("disconnected-node|n1|");
    expect(issueIdInput("invalid-config", "n1", null, "bad url")).toBe("invalid-config|n1||bad url");
  });

  it("matches node:crypto SHA-256 (first 16 hex) for known vectors", async () => {
    const cases: [string, string | null, string | null, string?][] = [
      ["no-trigger", null, null],
      ["missing-error-route", "agent_1", null],
      ["dangling-edge", null, "e-a-b"],
      ["invalid-config", "http_2", null, "URL must start with http(s)"],
      ["disconnected-node", "nœud-ü", null], // non-ASCII must hash as UTF-8
    ];
    for (const [code, nodeId, edgeId, msg] of cases) {
      const expected = nodeHash(issueIdInput(code, nodeId, edgeId, msg));
      expect(await issueId(code, nodeId, edgeId, msg)).toBe(expected);
    }
    // Literal vector so a change to both sides at once still fails.
    expect(await issueId("no-trigger")).toBe(nodeHash("no-trigger||"));
    expect(nodeHash("abc")).toBe("ba7816bf8f01cfea");
  });

  it("pure fallback SHA-256 agrees with node:crypto", () => {
    const enc = new TextEncoder();
    for (const s of ["", "abc", "x".repeat(55), "y".repeat(56), "z".repeat(64), "loop-no-max-iterations|loop_9|"]) {
      expect(hex(sha256(enc.encode(s)))).toBe(createHash("sha256").update(s, "utf8").digest("hex"));
    }
  });
});

describe("dropSatisfiedErrorRoutes", () => {
  it("treats config.onErrorNodeId as an error route", () => {
    const issues: LintIssue[] = [
      { nodeId: "a", severity: "warn", code: "missing-error-route", message: "a" },
      { nodeId: "b", severity: "warn", code: "missing-error-route", message: "b" },
      { nodeId: "a", severity: "warn", code: "disconnected-node", message: "a2" },
    ];
    const nodes = [
      { id: "a", data: { config: { onErrorNodeId: "fallback" } } },
      { id: "b", data: { config: { onErrorNodeId: "  " } } },
    ];
    expect(dropSatisfiedErrorRoutes(issues, nodes).map((i) => `${i.code}:${i.nodeId}`)).toEqual([
      "missing-error-route:b",
      "disconnected-node:a",
    ]);
  });
});

describe("mergeLint", () => {
  const client: LintIssue[] = [
    { nodeId: "loop1", severity: "warn", code: "loop-no-max-iterations", message: "client loop" },
    { severity: "error", code: "no-trigger", message: "client trigger" },
    { nodeId: "x", severity: "warn", code: "disconnected-node", message: "client x" },
  ];

  it("dedupes by id, server wins on message and fix, sorts error>warning>info", async () => {
    const server: ServerLintIssue[] = [
      {
        id: await issueId("loop-no-max-iterations", "loop1", null),
        code: "loop-no-max-iterations",
        severity: "warning",
        nodeId: "loop1",
        edgeId: null,
        message: "server loop",
        fix: { kind: "set-config", nodeId: "loop1", path: "config.maxIterations", value: 100 },
      },
      {
        id: await issueId("cost-hint", "loop1", null),
        code: "cost-hint",
        severity: "info",
        nodeId: "loop1",
        edgeId: null,
        message: "pricey",
        fix: null,
      },
      {
        id: await issueId("provider-key-missing", "agent", null),
        code: "provider-key-missing",
        severity: "error",
        nodeId: "agent",
        edgeId: null,
        message: "no key",
        fix: { kind: "open-url", url: "/settings/keys" },
      },
    ];
    const merged = await mergeLint(client, server);
    expect(merged.map((m) => [m.code, m.source])).toEqual([
      ["no-trigger", "client"],
      ["provider-key-missing", "server"],
      ["disconnected-node", "client"],
      ["loop-no-max-iterations", "both"],
      ["cost-hint", "server"],
    ]);
    const loop = merged.find((m) => m.code === "loop-no-max-iterations")!;
    expect(loop.message).toBe("server loop");
    expect(loop.fix).toEqual({ kind: "set-config", nodeId: "loop1", path: "config.maxIterations", value: 100 });
    expect(merged[0].fix).toEqual({ kind: "add-node", type: "trigger" });
  });

  it("drops stale server issues when the canvas moved on", async () => {
    const server: ServerLintIssue[] = [
      { id: await issueId("disconnected-node", "gone", null), code: "disconnected-node", severity: "warning", nodeId: "gone", edgeId: null, message: "gone", fix: null },
      { id: await issueId("disconnected-node", "y", null), code: "disconnected-node", severity: "warning", nodeId: "y", edgeId: null, message: "y", fix: null },
      { id: await issueId("http-insecure", "y", null), code: "http-insecure", severity: "warning", nodeId: "y", edgeId: null, message: "http", fix: null },
    ];
    const merged = await mergeLint(client, server, {
      nodeIds: new Set(["loop1", "x", "y"]),
      clientAuthoritativeParity: true,
    });
    expect(merged.map((m) => `${m.code}:${m.nodeId}`)).toEqual([
      "no-trigger:null",
      "disconnected-node:x",
      "http-insecure:y",
      "loop-no-max-iterations:loop1",
    ]);
  });
});

describe("parseLintResponse", () => {
  it("rejects unknown shapes and unsafe fix urls", () => {
    expect(parseLintResponse(null)).toBeNull();
    expect(parseLintResponse({ nope: 1 })).toBeNull();
    const r = parseLintResponse({
      issues: [
        { id: "a", code: "c", severity: "error", nodeId: null, edgeId: "e", message: "m", fix: { kind: "open-url", url: "javascript:alert(1)" } },
        { id: "b", code: "c", severity: "weird", nodeId: "n", edgeId: null, message: "m", fix: { kind: "delete-edge", edgeId: "e" } },
        { id: "c", code: "c", severity: "info", nodeId: "n", edgeId: null, message: "m", fix: { kind: "future-kind" } },
        { code: "missing-id" },
      ],
    })!;
    expect(r.issues).toHaveLength(3);
    expect(r.issues[0].fix).toBeNull();
    expect(r.issues[1]).toMatchObject({ severity: "warning", fix: { kind: "delete-edge", edgeId: "e" } });
    expect(r.issues[2].fix).toBeNull();
    expect(r.summary).toEqual({ errors: 1, warnings: 1, infos: 1 });
  });
});

describe("enable-approval-policy fix (core 0f3ed3b4)", () => {
  const issue = (fix: unknown) => ({
    summary: { errors: 0, warnings: 1, infos: 0 },
    issues: [{ id: "a1b2c3d4e5f60718", code: "approval-auto-approved", severity: "warning", nodeId: "n1", edgeId: null as string | null, message: "Never pauses", fix }],
  });

  it("keeps only the action type from the server fix", async () => {
    const { parseLintResponse } = await import("./lintMerge");
    const parsed = parseLintResponse(
      issue({ kind: "enable-approval-policy", actionType: "workflow.node.approval", method: "PUT", url: "/api/approvals/policy/workflow.node.approval", body: { requiresApproval: true } }),
    );
    expect(parsed?.issues[0].fix).toEqual({ kind: "enable-approval-policy", actionType: "workflow.node.approval" });
  });

  it("drops the button for an action type outside workflow.node.*", async () => {
    const { parseLintResponse } = await import("./lintMerge");
    const parsed = parseLintResponse(issue({ kind: "enable-approval-policy", actionType: "billing.refund/../x" }));
    expect(parsed?.issues[0].fix).toBeNull();
  });
});

describe("insert-approval fix (send-without-approval)", () => {
  const issue = (fix: unknown) => ({
    summary: { errors: 0, warnings: 1, infos: 0 },
    issues: [{ id: "0011223344556677", code: "send-without-approval", severity: "warning", nodeId: "o1", edgeId: null as string | null, message: "Sends without approval", fix }],
  });

  it("keeps the node ids to gate", async () => {
    const { parseLintResponse } = await import("./lintMerge");
    const parsed = parseLintResponse(issue({ kind: "insert-approval", nodeId: "o1", insertBeforeNodeIds: ["loop1", 7, ""] }));
    expect(parsed?.issues[0].fix).toEqual({ kind: "insert-approval", nodeId: "o1", insertBeforeNodeIds: ["loop1"] });
  });

  it("gates the send itself when the server names nothing else", async () => {
    const { parseLintResponse } = await import("./lintMerge");
    const parsed = parseLintResponse(issue({ kind: "insert-approval", nodeId: "o1" }));
    expect(parsed?.issues[0].fix).toEqual({ kind: "insert-approval", nodeId: "o1", insertBeforeNodeIds: ["o1"] });
  });

  it("drops the button without a node id", async () => {
    const { parseLintResponse } = await import("./lintMerge");
    expect(parseLintResponse(issue({ kind: "insert-approval", insertBeforeNodeIds: ["o1"] }))?.issues[0].fix).toBeNull();
  });
});
