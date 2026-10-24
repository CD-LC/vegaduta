import { describe, expect, it } from "vitest";
import { agentFromParam, registrySearch, resolveRequestedAgent } from "./registryDeepLink";

describe("registry deep link", () => {
  it("accepts an id-shaped ?agent= and rejects junk", () => {
    expect(agentFromParam("7b0e9d4c-1c2f-4e7a-9a43-0d3b6f1b2a10")).toBe("7b0e9d4c-1c2f-4e7a-9a43-0d3b6f1b2a10");
    expect(agentFromParam("  ag1 ")).toBe("ag1");
    for (const bad of [null, undefined, "", "   ", "a b", "../x", "<script>", "x".repeat(65)]) {
      expect(agentFromParam(bad)).toBeNull();
    }
  });

  it("opens at agent scope only for an agent the person can see", () => {
    expect(resolveRequestedAgent("a2", ["a1", "a2"])).toEqual({ kind: "agent", agentId: "a2" });
    expect(resolveRequestedAgent("gone", ["a1"])).toEqual({ kind: "tenant", missing: true });
    expect(resolveRequestedAgent("a1", [])).toEqual({ kind: "tenant", missing: true });
    expect(resolveRequestedAgent(null, ["a1"])).toEqual({ kind: "tenant", missing: false });
  });

  it("sets ?agent= at agent scope and removes it elsewhere, leaving other parameters alone", () => {
    expect(registrySearch("", "agent", "a1")).toBe("?agent=a1");
    expect(registrySearch("?x=1", "agent", "a1")).toBe("?x=1&agent=a1");
    expect(registrySearch("?agent=a1&x=1", "agent", "a2")).toBe("?agent=a2&x=1");
    expect(registrySearch("?agent=a1", "tenant", "a1")).toBe("");
    expect(registrySearch("?agent=a1&x=1", "platform", null)).toBe("?x=1");
    // Agent scope with nobody chosen yet is not shareable, so the parameter goes.
    expect(registrySearch("?agent=a1", "agent", null)).toBe("");
  });
});
