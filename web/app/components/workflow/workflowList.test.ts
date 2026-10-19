import { describe, expect, it } from "vitest";
import { filterWorkflows, sortWorkflows, statusCounts, workflowLink } from "./workflowList";

const w = (name: string, status: string, updatedAt: string, nodes = 0, description = "") => ({
  name,
  status,
  updatedAt,
  description,
  graph: { nodes: new Array(nodes).fill(0) },
});
const items = [
  w("Beta", "DRAFT", "2026-01-01T00:00:00Z", 2),
  w("alpha", "ACTIVE", "2026-03-01T00:00:00Z", 5, "Sends invoices"),
  w("Gamma", "PAUSED", "2026-02-01T00:00:00Z", 9),
  w("Delta", "ACTIVE", "2026-02-15T00:00:00Z", 1),
];
const names = (xs: { name: string }[]) => xs.map((x) => x.name);

describe("workflow list", () => {
  it("counts statuses with ACTIVE first", () => {
    expect(statusCounts(items)).toEqual([
      { status: "ACTIVE", count: 2 },
      { status: "DRAFT", count: 1 },
      { status: "PAUSED", count: 1 },
    ]);
  });
  it("filters by status and by name or description", () => {
    expect(names(filterWorkflows(items, "ACTIVE", ""))).toEqual(["alpha", "Delta"]);
    expect(names(filterWorkflows(items, "", "invoice"))).toEqual(["alpha"]);
    expect(names(filterWorkflows(items, "DRAFT", "invoice"))).toEqual([]);
  });
  it("sorts without mutating", () => {
    expect(names(sortWorkflows(items, "updated"))).toEqual(["alpha", "Delta", "Gamma", "Beta"]);
    expect(names(sortWorkflows(items, "name"))).toEqual(["alpha", "Beta", "Delta", "Gamma"]);
    expect(names(sortWorkflows(items, "largest"))).toEqual(["Gamma", "alpha", "Beta", "Delta"]);
    expect(names(items)[0]).toBe("Beta");
  });
  it("builds a deep link", () => {
    expect(workflowLink("https://vegaduta.ai/", "a b")).toBe("https://vegaduta.ai/workflows/a%20b");
  });
});
