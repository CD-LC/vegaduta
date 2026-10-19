import { describe, expect, it } from "vitest";
import { canEditAgent, canViewAgentConfig } from "./agentAccess";

describe("agentAccess", () => {
  it("treats a missing or null level as editable (older core, owner)", () => {
    expect(canEditAgent({})).toBe(true);
    expect(canEditAgent({ accessLevel: null })).toBe(true);
    expect(canEditAgent(null)).toBe(true);
  });
  it("lets only EDITOR write", () => {
    expect(canEditAgent({ accessLevel: "EDITOR" })).toBe(true);
    expect(canEditAgent({ accessLevel: "VIEWER" })).toBe(false);
    expect(canEditAgent({ accessLevel: "RUNNER" })).toBe(false);
  });
  it("lets RUNNER run but not read the configuration", () => {
    expect(canViewAgentConfig({ accessLevel: "VIEWER" })).toBe(true);
    expect(canViewAgentConfig({ accessLevel: "RUNNER" })).toBe(false);
    expect(canViewAgentConfig({ accessLevel: null })).toBe(true);
  });
});
