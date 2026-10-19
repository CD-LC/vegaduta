// The pure data nodes can be run on their own from the step-test panel: they are deterministic and have no side
// effects, so stepTest's allow-list must include them (it mirrors WorkflowNodeTestService#refusalReason, which
// only refuses side-effecting, multi-node and human-gated types).

import { describe, expect, it, vi } from "vitest";

vi.mock("../../lib/api/core", () => ({
  apiFetch: vi.fn(),
  jsonBody: (body: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
}));

import { TESTABLE_TYPES, untestableReason } from "./stepTest";
import { DATA_NODE_TYPES } from "./dataNodes";

describe("data nodes in the step tester", () => {
  it("every data node type is testable on its own", () => {
    for (const type of DATA_NODE_TYPES) {
      expect(TESTABLE_TYPES.has(type), type).toBe(true);
      expect(untestableReason(type, {}), type).toBeNull();
    }
  });
});
