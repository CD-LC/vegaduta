import { describe, expect, it } from "vitest";
import { edgeStateClass } from "./WorkflowEdge";

describe("edgeStateClass", () => {
  it("maps every run state to its class", () => {
    expect(edgeStateClass("done")).toBe("wf-edge-done");
    expect(edgeStateClass("flowing")).toBe("wf-edge-flowing");
    expect(edgeStateClass("pending")).toBe("wf-edge-pending");
    expect(edgeStateClass("failed")).toBe("wf-edge-failed");
    expect(edgeStateClass("skipped")).toBe("wf-edge-skipped");
    expect(edgeStateClass("awaiting-approval")).toBe("wf-edge-paused");
  });
  it("falls back to the default look with no state", () => {
    expect(edgeStateClass(undefined)).toBe("wf-edge-flowing");
    expect(edgeStateClass(null)).toBe("wf-edge-flowing");
  });
});
