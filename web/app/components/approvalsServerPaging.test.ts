import { describe, expect, it } from "vitest";
import { approvalsPageUrl } from "./approvalsServerPaging";

describe("approvalsPageUrl", () => {
  it("sends status, limit and offset", () => {
    expect(approvalsPageUrl("PENDING", 25, 50)).toBe("/api/approvals?status=PENDING&limit=25&offset=50");
  });
  it("omits an empty status (All)", () => {
    expect(approvalsPageUrl("", 10, 0)).toBe("/api/approvals?limit=10&offset=0");
  });
});
