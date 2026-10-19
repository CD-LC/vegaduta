import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  apiFetchOnce: vi.fn(),
  friendlyStatusFallback: vi.fn(),
  toJson: (r: unknown) => r,
  toJsonQuietOn: () => (r: unknown) => r,
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { workflowsApi } from "./workflows";

beforeEach(() => apiFetchMock.mockReset().mockResolvedValue({}));

const sentBody = () => JSON.parse(apiFetchMock.mock.calls[0][1].body as string) as Record<string, unknown>;

describe("runWorkflow run mode", () => {
  it("sends mode draft when asked to", async () => {
    await workflowsApi.runWorkflow("wf1", "in", undefined, "c1", true, "draft");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf1/run");
    expect(sentBody()).toMatchObject({ mode: "draft", background: true, clientRunId: "c1" });
  });

  it("omits mode for every other caller so the run stays production", async () => {
    await workflowsApi.runWorkflow("wf1", "in", undefined, "c1");
    expect(sentBody()).not.toHaveProperty("mode");
    expect(sentBody()).not.toHaveProperty("simulateSends");
  });

  it("sends simulateSends only together with mode draft", async () => {
    await workflowsApi.runWorkflow("wf1", "in", undefined, "c1", true, "draft", true);
    expect(sentBody()).toMatchObject({ mode: "draft", simulateSends: true });
  });

  it("never asks for simulated sends on a production run", async () => {
    await workflowsApi.runWorkflow("wf1", "in", undefined, "c1", true, undefined, true);
    expect(sentBody()).not.toHaveProperty("simulateSends");
  });
});

describe("generateWorkflowGraph", () => {
  const graph = { nodes: [{ id: "t" }, { id: "a" }], edges: [{ id: "e" }] };

  it("sends only the instruction for a fresh draft", async () => {
    await workflowsApi.generateWorkflowGraph("wf1", "do it");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf1/generate-graph");
    expect(sentBody()).toEqual({ instruction: "do it" });
  });

  it("sends the current graph when changing an existing workflow", async () => {
    await workflowsApi.generateWorkflowGraph("wf1", "add a step", graph);
    expect(sentBody()).toEqual({ instruction: "add a step", currentGraph: graph });
  });

  it("drops an empty current graph", async () => {
    await workflowsApi.generateWorkflowGraph("wf1", "do it", { nodes: [], edges: [] });
    expect(sentBody()).not.toHaveProperty("currentGraph");
  });
});
