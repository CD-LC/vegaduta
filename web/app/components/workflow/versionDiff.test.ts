// Version diff helpers + fetch classification. apiFetch is mocked at the
// seam; toJsonQuietOn is re-implemented minimally (throws {status} on !ok).

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("../../lib/api/core", () => ({
  apiFetch: apiFetchMock,
  toJsonQuietOn: () => async (res: Response) => {
    if (!res.ok) {
      const err = new Error(`status ${res.status}`) as Error & { status: number };
      err.status = res.status;
      throw err;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
}));

import {
  defaultPair,
  diffDecorations,
  edgeKey,
  fetchWorkflowVersionDiff,
  fetchWorkflowVersions,
  groupFields,
  hasRedaction,
  isRedacted,
  normalizeDiff,
  normalizeVersions,
  summarizeDiff,
  wordDiff,
  type WorkflowVersionRow,
} from "./versionDiff";

const RAW_DIFF: Record<string, unknown> = {
  from: { versionNumber: 3, workflowName: "A", status: "DRAFT", createdBy: "u1", createdAt: "2026-09-01T10:00:00Z" },
  to: { versionNumber: 5, workflowName: "B", status: "ACTIVE", createdBy: "u2", createdAt: "2026-09-02T10:00:00Z" },
  nameChanged: true,
  descriptionChanged: false,
  statusChanged: true,
  details: [{ field: "maxStepsPerRun", from: "10", to: "20" }],
  graph: {
    addedNodes: [{ id: "n3", type: "agent", label: "Writer" }],
    removedNodes: [{ id: "n9", type: "http", label: "Old call" }],
    changedNodes: [
      {
        id: "n1",
        type: "agent",
        label: "Planner",
        changes: ["label changed", "config changed"],
        fields: [
          { path: "config.url", before: "https://a", after: "https://b" },
          { path: "label", before: "Plan", after: "Planner" },
          { path: "config.apiKey", before: "[redacted]", after: "[redacted]" },
          { path: "type", before: "llm", after: "agent" },
          { path: "weird", before: null, after: "x" },
        ],
      },
    ],
    addedEdges: [{ id: "e2", source: "n1", target: "n3", sourceHandle: null }, { id: "e5", source: "n1", target: "n3" }],
    removedEdges: [{ id: null, source: "n1", target: "n9", sourceHandle: "true" }, { id: "e5", source: "n1", target: "n9" }],
  },
  meta: { fromVersion: 3, toVersion: 5, createdAt: "2026-09-02T10:00:00Z", author: "u2", fromCreatedAt: "2026-09-01T10:00:00Z", fromAuthor: "u1" },
};

function res(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : typeof body === "string" ? body : JSON.stringify(body), { status });
}

describe("normalizeDiff", () => {
  it("keeps well-formed data", () => {
    const d = normalizeDiff(RAW_DIFF);
    expect(d.meta.fromVersion).toBe(3);
    expect(d.graph.changedNodes[0].fields).toHaveLength(5);
    expect(d.details[0]).toEqual({ field: "maxStepsPerRun", from: "10", to: "20" });
    expect(d.nameChanged).toBe(true);
  });

  it("tolerates junk and missing sections", () => {
    const d = normalizeDiff({ graph: { addedNodes: [null, { type: "x" }, { id: "ok" }] }, from: { versionNumber: 1 } });
    expect(d.graph.addedNodes).toEqual([{ id: "ok", type: null, label: null }]);
    expect(d.graph.changedNodes).toEqual([]);
    expect(d.meta.fromVersion).toBe(1);
    expect(normalizeDiff(null).details).toEqual([]);
  });
});

describe("summarizeDiff", () => {
  it("counts every bucket", () => {
    expect(summarizeDiff(normalizeDiff(RAW_DIFF))).toEqual({
      nodesAdded: 1,
      nodesRemoved: 1,
      nodesChanged: 1,
      edgesAdded: 2,
      edgesRemoved: 2,
      workflowChanges: 3,
      total: 10,
    });
  });
});

describe("diffDecorations", () => {
  it("maps nodes and edges, added wins for a rewired edge", () => {
    const d = diffDecorations(normalizeDiff(RAW_DIFF));
    expect(d.nodes).toEqual({ n1: "changed", n3: "added", n9: "removed" });
    expect(d.edges).toEqual({ e2: "added", e5: "added", "n1->n9:true": "removed" });
  });

  it("edgeKey falls back to endpoints", () => {
    expect(edgeKey({ id: "x" })).toBe("x");
    expect(edgeKey({ source: "a", target: "b" })).toBe("a->b");
  });
});

describe("groupFields", () => {
  it("groups by top-level in fixed order and strips the prefix", () => {
    const groups = groupFields(normalizeDiff(RAW_DIFF).graph.changedNodes[0].fields);
    expect(groups.map((g) => g.key)).toEqual(["type", "label", "config", "other"]);
    expect(groups[2].fields.map((f) => f.display)).toEqual(["url", "apiKey"]);
    expect(groups[0].fields[0].display).toBe("Type");
    expect(groups[3].fields[0].display).toBe("weird");
  });
});

describe("redaction", () => {
  it("detects the mask", () => {
    expect(isRedacted("[redacted]")).toBe(true);
    expect(isRedacted(" [redacted] ")).toBe(true);
    expect(isRedacted("Bearer [redacted]")).toBe(false);
    expect(hasRedaction("Bearer [redacted]")).toBe(true);
    expect(isRedacted(null)).toBe(false);
  });
});

describe("wordDiff", () => {
  it("highlights only the changed words", () => {
    const d = wordDiff("send the weekly report", "send the daily report");
    expect(d).not.toBeNull();
    expect(d!.before.filter((s) => s.changed).map((s) => s.text)).toEqual(["weekly"]);
    expect(d!.after.filter((s) => s.changed).map((s) => s.text)).toEqual(["daily"]);
    expect(d!.before.map((s) => s.text).join("")).toBe("send the weekly report");
    expect(d!.after.map((s) => s.text).join("")).toBe("send the daily report");
  });

  it("skips null, redacted and long values", () => {
    expect(wordDiff(null, "a")).toBeNull();
    expect(wordDiff("[redacted]", "x")).toBeNull();
    expect(wordDiff("a".repeat(401), "b")).toBeNull();
  });

  it("handles non-latin text", () => {
    const d = wordDiff("नमस्ते दुनिया", "नमस्ते भारत");
    expect(d!.after.filter((s) => s.changed).map((s) => s.text)).toEqual(["भारत"]);
  });
});

describe("defaultPair / normalizeVersions", () => {
  const rows: WorkflowVersionRow[] = normalizeVersions([
    { id: "a", versionNumber: 1, workflowName: "W", createdAt: "2026-09-01T00:00:00Z" },
    { id: "c", versionNumber: 3, workflowName: "W" },
    { id: "b", versionNumber: 2, workflowName: "W" },
    { id: "bad" },
  ]);

  it("sorts newest first and drops rows without a number", () => {
    expect(rows.map((r) => r.versionNumber)).toEqual([3, 2, 1]);
  });

  it("defaults to previous vs latest", () => {
    expect(defaultPair(rows)).toEqual({ from: 2, to: 3 });
  });

  it("honours currentVersion when it has a predecessor", () => {
    expect(defaultPair(rows, 2)).toEqual({ from: 1, to: 2 });
    expect(defaultPair(rows, 1)).toEqual({ from: 2, to: 3 });
    expect(defaultPair(rows, 99)).toEqual({ from: 2, to: 3 });
  });

  it("needs two versions", () => {
    expect(defaultPair(rows.slice(0, 1))).toBeNull();
  });
});

describe("fetchers", () => {
  beforeEach(() => apiFetchMock.mockReset());

  it("lists versions", async () => {
    apiFetchMock.mockResolvedValueOnce(res(200, [{ id: "a", versionNumber: 1, workflowName: "W" }]));
    const r = await fetchWorkflowVersions("wf-1");
    expect(r.ok).toBe(true);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf-1/versions");
  });

  it("treats 404/405 on the list as unsupported", async () => {
    apiFetchMock.mockResolvedValueOnce(res(404));
    expect(await fetchWorkflowVersions("wf")).toEqual({ ok: false, unsupported: true, message: null });
    apiFetchMock.mockResolvedValueOnce(res(405));
    expect((await fetchWorkflowVersions("wf")).unsupported).toBe(true);
  });

  it("treats a non-JSON 200 as unsupported", async () => {
    apiFetchMock.mockResolvedValueOnce(res(200, "<html>app shell</html>"));
    expect((await fetchWorkflowVersions("wf")).unsupported).toBe(true);
  });

  it("keeps a 500 as a visible message", async () => {
    apiFetchMock.mockResolvedValueOnce(res(500));
    const r = await fetchWorkflowVersions("wf");
    expect(r.ok).toBe(false);
    expect(r.unsupported).toBe(false);
    expect(r.message).toBeTruthy();
  });

  it("network error is not unsupported", async () => {
    apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const r = await fetchWorkflowVersions("wf");
    expect(r).toEqual({ ok: false, unsupported: false, message: "Failed to fetch" });
  });

  it("fetches a diff and normalises it", async () => {
    apiFetchMock.mockResolvedValueOnce(res(200, RAW_DIFF));
    const r = await fetchWorkflowVersionDiff("wf", 3, 5);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf/versions/diff?from=3&to=5");
    expect(r.ok && r.data.graph.addedNodes[0].id).toBe("n3");
  });

  it("diff 404 is a missing version (message), 405 is unsupported", async () => {
    apiFetchMock.mockResolvedValueOnce(res(404));
    const a = await fetchWorkflowVersionDiff("wf", 1, 9);
    expect(a.ok).toBe(false);
    expect(a.unsupported).toBe(false);
    apiFetchMock.mockResolvedValueOnce(res(405));
    expect((await fetchWorkflowVersionDiff("wf", 1, 2)).unsupported).toBe(true);
  });
});
