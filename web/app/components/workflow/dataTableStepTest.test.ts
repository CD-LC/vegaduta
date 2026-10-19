// "Run this step" for the Data Table node: reads and counts can be tested, writes are refused with a reason
// (mirrors WorkflowNodeTestService#refusalReason). apiFetch is mocked at the seam - no network.

import { describe, expect, it, vi } from "vitest";

vi.mock("../../lib/api/core", () => ({
  apiFetch: vi.fn(),
  jsonBody: (body: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
}));

import { TESTABLE_TYPES, isTestable, untestableReason } from "./stepTest";

describe("data_table step test", () => {
  it("is a testable type", () => {
    expect(TESTABLE_TYPES.has("data_table")).toBe(true);
  });

  it("allows reading and counting, including the blank default", () => {
    expect(isTestable("data_table", { table: "orders" })).toBe(true);
    expect(isTestable("data_table", { table: "orders", operation: "get_rows" })).toBe(true);
    expect(isTestable("data_table", { table: "orders", operation: "count" })).toBe(true);
  });

  it("refuses every write with a reason", () => {
    for (const operation of ["insert_row", "update_rows", "upsert_row", "delete_rows", "truncate"]) {
      expect(isTestable("data_table", { operation }), operation).toBe(false);
      expect(untestableReason("data_table", { operation })).toMatch(/writes to a table/);
    }
  });
});
