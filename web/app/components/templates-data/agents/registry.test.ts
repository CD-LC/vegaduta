import { describe, it, expect } from "vitest";
import { AGENT_TEMPLATES, AGENT_TEMPLATES_BY_CATEGORY } from "./registry";
import { AGENT_TEMPLATE_CATEGORIES, CATEGORY_AUDIENCE } from "./categories";
import { MCP_CATALOG_KEYS } from "./mcpGuide";

describe("agent template registry", () => {
  it("has one data file per category and no empty category", () => {
    expect(Object.keys(AGENT_TEMPLATES_BY_CATEGORY).sort()).toEqual([...AGENT_TEMPLATE_CATEGORIES].sort());
    for (const c of AGENT_TEMPLATE_CATEGORIES) {
      expect(AGENT_TEMPLATES_BY_CATEGORY[c].length, c).toBeGreaterThan(0);
    }
  });

  it("registry equals the concatenation of the category files, categories in declared order", () => {
    const flat = AGENT_TEMPLATE_CATEGORIES.flatMap((c) => AGENT_TEMPLATES_BY_CATEGORY[c]);
    expect(AGENT_TEMPLATES).toEqual(flat);
    expect(AGENT_TEMPLATES.length).toBe(97);
  });

  it("every entry is schema-valid", () => {
    const keys = new Set<string>();
    const mcp = new Set<string>(MCP_CATALOG_KEYS);
    for (const [category, list] of Object.entries(AGENT_TEMPLATES_BY_CATEGORY)) {
      for (const t of list) {
        expect(t.key, "kebab-case key").toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
        expect(keys.has(t.key), `duplicate key ${t.key}`).toBe(false);
        keys.add(t.key);
        expect(t.category, `${t.key} filed under the wrong category file`).toBe(category);
        expect(CATEGORY_AUDIENCE[t.category]).toBeTruthy();
        expect(typeof t.icon === "function" || typeof t.icon === "object", `${t.key} icon`).toBe(true);
        expect(t.icon).toBeTruthy();
        expect(typeof t.suggestKnowledgeBase).toBe("boolean");
        expect(t.systemPrompt.trim().length, `${t.key} prompt`).toBeGreaterThan(50);
        if (t.knowledgeHint !== undefined) expect(typeof t.knowledgeHint).toBe("boolean");
        for (const s of t.mcp ?? []) {
          expect(["required", "optional"]).toContain(s.requirement);
          expect(s.servers.length).toBeGreaterThan(0);
          for (const server of s.servers) expect(mcp.has(server), `${t.key} -> ${server}`).toBe(true);
        }
      }
    }
  });
});
