import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  AGENT_STORAGE_KEY,
  assistantHref,
  initialAgentId,
  persistAgentSelection,
  readStoredAgentId,
  resolveAgentId,
  withAgentParam,
} from "./agentSelection";

const AGENTS = [
  { id: "a-first", name: "Comparison Agent" },
  { id: "a-second", name: "e2e-agent" },
  { id: "a-assistant", name: "Assistant" },
];

describe("assistant agent selection - pure rules", () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.history.replaceState(null, "", "/"));

  it("initial id: the URL wins, then the last agent used in this browser", () => {
    expect(initialAgentId("a-url")).toBe("a-url");
    window.localStorage.setItem(AGENT_STORAGE_KEY, "a-stored");
    expect(initialAgentId(null)).toBe("a-stored");
    expect(readStoredAgentId()).toBe("a-stored");
  });

  it("resolve: URL agent, then the current selection, then the Assistant, then the first agent", () => {
    expect(resolveAgentId(AGENTS, "a-second", "a-first", "a-assistant")).toBe("a-second");
    expect(resolveAgentId(AGENTS, null, "a-first", "a-assistant")).toBe("a-first");
    expect(resolveAgentId(AGENTS, null, null, "a-assistant")).toBe("a-assistant");
    expect(resolveAgentId(AGENTS, null, null, null)).toBe("a-first");
  });

  it("resolve: never keeps an id that is not in the list", () => {
    expect(resolveAgentId(AGENTS, "gone", "also-gone", "a-assistant")).toBe("a-assistant");
    expect(resolveAgentId([], "gone", "also-gone", "a-assistant")).toBe("");
  });

  it("withAgentParam keeps other params and the hash", () => {
    expect(withAgentParam("/assistant?mode=task#x", "a-1")).toBe("/assistant?mode=task&agent=a-1#x");
    expect(withAgentParam("/assistant?agent=a-1&mode=task", "")).toBe("/assistant?mode=task");
  });

  it("persist writes localStorage and the address bar", () => {
    window.history.replaceState(null, "", "/assistant?mode=task");
    persistAgentSelection("a-second");
    expect(window.localStorage.getItem(AGENT_STORAGE_KEY)).toBe("a-second");
    expect(window.location.search).toBe("?mode=task&agent=a-second");
  });

  it("assistantHref carries agent, mode and a capped prompt", () => {
    expect(assistantHref({})).toBe("/assistant");
    expect(assistantHref({ agentId: "a-1", mode: "background" })).toBe("/assistant?agent=a-1&mode=background");
    expect(assistantHref({ prompt: "x".repeat(700) })).toBe(`/assistant?prompt=${"x".repeat(600)}`);
  });
});
