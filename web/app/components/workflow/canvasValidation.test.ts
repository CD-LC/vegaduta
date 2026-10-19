import { describe, expect, it } from "vitest";
import { canSkipDraftSave, nodeIdsForValidationErrors, runStepProgress } from "./canvasValidation";

describe("nodeIdsForValidationErrors", () => {
  const nodes = [
    { id: "a", data: { label: "Route" } },
    { id: "b", data: { label: "Fetch" } },
    { id: "c", data: {} },
  ];
  it("matches nodes whose quoted label appears in a message", () => {
    const ids = nodeIdsForValidationErrors(["Switch node 'Route' has no cases.", "Graph is empty"], nodes);
    expect([...ids]).toEqual(["a"]);
  });
  it("returns an empty set for no errors", () => {
    expect(nodeIdsForValidationErrors(null, nodes).size).toBe(0);
  });
});

describe("runStepProgress", () => {
  it("reports unexecuted nodes of a finished run as branches not taken", () => {
    expect(runStepProgress(4, 5, "COMPLETED", false)).toEqual({ completed: 4, total: 5, branchesNotTaken: 1 });
  });
  it("reports none while the run is live", () => {
    expect(runStepProgress(2, 5, "RUNNING", true).branchesNotTaken).toBe(0);
  });
});

describe("canSkipDraftSave", () => {
  it("skips only a clean draft", () => {
    expect(canSkipDraftSave("{}", "{}", "DRAFT")).toBe(true);
    expect(canSkipDraftSave("{}", "{}", "ACTIVE")).toBe(false);
    expect(canSkipDraftSave("{1}", "{}", "DRAFT")).toBe(false);
    expect(canSkipDraftSave("{}", null, "DRAFT")).toBe(false);
  });
});
