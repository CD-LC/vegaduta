import { describe, it, expect } from "vitest";
import { WORKFLOW_TEMPLATES, WORKFLOW_TEMPLATES_BY_CATEGORY } from "./registry";
import { WORKFLOW_TEMPLATE_CATEGORIES } from "./types";
import { buildWorkflowGraph } from "../../workflowTemplates";
import { nodeDef } from "../../workflow/nodeDefs";

describe("workflow template registry", () => {
  it("has one data file per category and no empty category", () => {
    expect(Object.keys(WORKFLOW_TEMPLATES_BY_CATEGORY).sort()).toEqual([...WORKFLOW_TEMPLATE_CATEGORIES].sort());
    for (const c of WORKFLOW_TEMPLATE_CATEGORIES) expect(WORKFLOW_TEMPLATES_BY_CATEGORY[c].length, c).toBeGreaterThan(0);
  });

  it("registry equals the concatenation of the category files", () => {
    expect(WORKFLOW_TEMPLATES).toEqual(WORKFLOW_TEMPLATE_CATEGORIES.flatMap((c) => WORKFLOW_TEMPLATES_BY_CATEGORY[c]));
    expect(WORKFLOW_TEMPLATES.length).toBe(40);
  });

  it("every entry is schema-valid and builds a connected graph", () => {
    const keys = new Set<string>();
    for (const [category, list] of Object.entries(WORKFLOW_TEMPLATES_BY_CATEGORY)) {
      for (const t of list) {
        expect(t.key).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
        expect(keys.has(t.key), `duplicate ${t.key}`).toBe(false);
        keys.add(t.key);
        expect(t.category, t.key).toBe(category);
        expect(t.icon).toBeTruthy();
        expect(t.description.trim().length).toBeGreaterThan(10);
        expect(t.triggerChannel).toBeTruthy();
        expect(Boolean(t.steps) !== Boolean(t.buildGraph), `${t.key}: exactly one of steps/buildGraph`).toBe(true);
        const g = buildWorkflowGraph(t);
        expect(g.nodes.filter((n) => n.type === "trigger"), t.key).toHaveLength(1);
        const ids = new Set(g.nodes.map((n) => n.id));
        expect(ids.size).toBe(g.nodes.length);
        for (const n of g.nodes) expect(() => nodeDef(n.type), `${t.key}: node type ${n.type}`).not.toThrow();
        for (const e of g.edges) {
          expect(ids.has(e.source), `${t.key} edge source`).toBe(true);
          expect(ids.has(e.target), `${t.key} edge target`).toBe(true);
        }
      }
    }
  });
});
