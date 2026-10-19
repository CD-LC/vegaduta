import { describe, expect, it } from "vitest";
import { canEditWorkflow } from "./workflowAccess";
import { normalizeLabel, normalizeVersions } from "./versionDiff";

describe("canEditWorkflow", () => {
  it("allows editors and an older core without the field", () => {
    expect(canEditWorkflow({ accessLevel: "EDITOR" })).toBe(true);
    expect(canEditWorkflow({ accessLevel: null })).toBe(true);
    expect(canEditWorkflow({})).toBe(true);
    expect(canEditWorkflow(null)).toBe(true);
  });
  it("hides write actions for runners and viewers", () => {
    expect(canEditWorkflow({ accessLevel: "RUNNER" })).toBe(false);
    expect(canEditWorkflow({ accessLevel: "VIEWER" })).toBe(false);
  });
});

describe("version labels", () => {
  it("normalizes the label column", () => {
    expect(normalizeLabel("production")).toBe("PRODUCTION");
    expect(normalizeLabel("STAGING")).toBe("STAGING");
    expect(normalizeLabel("x")).toBeNull();
    expect(normalizeLabel(undefined)).toBeNull();
  });
  it("carries label on rows", () => {
    const rows = normalizeVersions([{ versionNumber: 2, label: "STAGING" }, { versionNumber: 1 }]);
    expect(rows.map((r) => r.label)).toEqual(["STAGING", null]);
  });
});
