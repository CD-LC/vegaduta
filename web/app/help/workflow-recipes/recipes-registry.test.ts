import { describe, expect, it } from "vitest";
import { INDUSTRY_INDEX } from "./recipes-index";
import { PACK_LOADERS, loadPack } from "./recipes-registry";
import { INDUSTRIES_CORE } from "./recipes-core-patterns";
import { NODE_META } from "./recipes-types";

describe("workflow recipe registry", () => {
  it("every index entry points at a registered pack", () => {
    for (const meta of INDUSTRY_INDEX) {
      expect(PACK_LOADERS[meta.pack], `${meta.id} -> ${meta.pack}`).toBeTypeOf("function");
    }
  });

  it("industry ids are unique", () => {
    const ids = INDUSTRY_INDEX.map((i) => i.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("core patterns keep their order and shape", () => {
    expect(INDUSTRIES_CORE).toHaveLength(19);
    expect(INDUSTRIES_CORE[0].id).toBe("multi-agent-patterns");
    expect(INDUSTRIES_CORE[18].id).toBe("agritech");
    const coreIndex = INDUSTRY_INDEX.filter((i) => i.pack === "recipes-core-patterns");
    expect(coreIndex.map((i) => i.id)).toEqual(INDUSTRIES_CORE.map((i) => i.id));
  });

  it("core recipes satisfy the recipe schema and match the index", async () => {
    const packed = await loadPack("recipes-core-patterns");
    for (const ind of packed) {
      const meta = INDUSTRY_INDEX.find((m) => m.id === ind.id);
      expect(meta, ind.id).toBeDefined();
      expect(meta!.recipeCount).toBe(ind.recipes.length);
      expect(meta!.recipeTitles).toEqual(ind.recipes.map((r) => r.title));
      const recipeIds = new Set<string>();
      for (const r of ind.recipes) {
        expect(recipeIds.has(r.id), `dup recipe ${r.id}`).toBe(false);
        recipeIds.add(r.id);
        expect(r.title && r.scenario && r.triggerLabel && r.outcome && r.impact).toBeTruthy();
        expect(r.steps.length).toBeGreaterThan(0);
        for (const step of r.steps) {
          expect(NODE_META[step.kind], `${r.id} step kind ${step.kind}`).toBeDefined();
          expect(step.title && step.detail).toBeTruthy();
        }
        if (r.build) {
          expect(r.build.agents).toBeGreaterThan(0);
          expect(r.build.prompt).toBeTruthy();
          expect(r.build.guardrails.length).toBeGreaterThan(0);
        }
      }
    }
  });
});
