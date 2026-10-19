import { describe, expect, it, vi } from "vitest";
import { QUIET_TEMPLATE_CAPABILITIES, enableQuietTemplateCapabilities } from "./templateCapabilities";
import { AGENT_TEMPLATES } from "../agentTemplates";
import { dependsOnDeepResearch, prereqsForConsoleTemplate, requiredConnectorCount } from "./templatePrereqs";
import type { AgentCapabilityEntry } from "../../lib/api/types";

function capability(over: Partial<AgentCapabilityEntry> = {}): AgentCapabilityEntry {
  return {
    key: "deep-research",
    label: "Deep research",
    description: "",
    category: "tools",
    enabled: false,
    availability: "AVAILABLE",
    availabilityHint: null,
    ...over,
  };
}

describe("QUIET_TEMPLATE_CAPABILITIES", () => {
  it("only names templates that exist in the gallery", () => {
    const keys = new Set<string>(AGENT_TEMPLATES.map((t) => t.key));
    for (const templateKey of Object.keys(QUIET_TEMPLATE_CAPABILITIES)) {
      expect(keys.has(templateKey), `unknown template "${templateKey}"`).toBe(true);
    }
  });

  it("the research template requires a search connector unless deep research is confirmed available", () => {
    expect(dependsOnDeepResearch("research-assistant")).toBe(true);
    expect(dependsOnDeepResearch("travel-planner")).toBe(false);

    const byDefault = prereqsForConsoleTemplate("research-assistant", { suggestKnowledgeBase: true });
    expect(byDefault.mcp.map((m) => [m.server, m.necessity])).toEqual([["brave-search", "required"]]);
    expect(requiredConnectorCount(byDefault)).toBe(1);

    const unknown = prereqsForConsoleTemplate("research-assistant", { deepResearchAvailable: false });
    expect(requiredConnectorCount(unknown)).toBe(1);

    const offered = prereqsForConsoleTemplate("research-assistant", { deepResearchAvailable: true });
    expect(offered.mcp.map((m) => [m.server, m.necessity])).toEqual([["brave-search", "optional"]]);
    expect(requiredConnectorCount(offered)).toBe(0);
  });

  it("deep research being available does not relax other templates' connectors", () => {
    const review = prereqsForConsoleTemplate("code-review-assistant", { deepResearchAvailable: true });
    expect(review.mcp.every((m) => m.necessity === "required")).toBe(true);
  });
});

describe("enableQuietTemplateCapabilities", () => {
  it("switches the capability on when it is listed and available", async () => {
    const set = vi.fn().mockResolvedValue({});
    const out = await enableQuietTemplateCapabilities("research-assistant", "a1", {
      list: vi.fn().mockResolvedValue([capability()]),
      set,
    });
    expect(out).toEqual(["deep-research"]);
    expect(set).toHaveBeenCalledWith("a1", "deep-research", true);
  });

  it("does nothing when the capability is unavailable, needs setup, or is absent", async () => {
    for (const listed of [
      [capability({ availability: "UNAVAILABLE" })],
      [capability({ availability: "NEEDS_SETUP" })],
      [capability({ key: "something-else" })],
      [],
    ]) {
      const set = vi.fn();
      const out = await enableQuietTemplateCapabilities("research-assistant", "a1", {
        list: vi.fn().mockResolvedValue(listed),
        set,
      });
      expect(out).toEqual([]);
      expect(set).not.toHaveBeenCalled();
    }
  });

  it("does not call the server for a template with no capabilities, or without an agent", async () => {
    const list = vi.fn();
    const set = vi.fn();
    expect(await enableQuietTemplateCapabilities("homework-helper", "a1", { list, set })).toEqual([]);
    expect(await enableQuietTemplateCapabilities(null, "a1", { list, set })).toEqual([]);
    expect(await enableQuietTemplateCapabilities("research-assistant", null, { list, set })).toEqual([]);
    expect(list).not.toHaveBeenCalled();
  });

  it("leaves an already-enabled capability alone", async () => {
    const set = vi.fn();
    await enableQuietTemplateCapabilities("research-assistant", "a1", {
      list: vi.fn().mockResolvedValue([capability({ enabled: true })]),
      set,
    });
    expect(set).not.toHaveBeenCalled();
  });

  it("never throws when a call fails", async () => {
    await expect(
      enableQuietTemplateCapabilities("research-assistant", "a1", {
        list: vi.fn().mockRejectedValue(new Error("down")),
        set: vi.fn(),
      })
    ).resolves.toEqual([]);
    await expect(
      enableQuietTemplateCapabilities("research-assistant", "a1", {
        list: vi.fn().mockResolvedValue([capability()]),
        set: vi.fn().mockRejectedValue(new Error("refused")),
      })
    ).resolves.toEqual([]);
  });
});
