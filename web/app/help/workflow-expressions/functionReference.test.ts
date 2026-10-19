import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { FUNCTION_DOCS, functionsByCategory } from "./functionReference";

// Drift guard: the docs table must list exactly the functions the Java engine
// registers (BuiltIns.java and its *BuiltIns.java category files are the single source of truth).
const EXPRESSION_DIR = path.resolve(
  __dirname,
  "../../../../core/workflow-expression/src/main/java/com/agenticai/core/workflow/expression",
);
const builtInSources = () =>
  readdirSync(EXPRESSION_DIR)
    .filter((f) => /BuiltIns\.java$/.test(f))
    .map((f) => readFileSync(path.join(EXPRESSION_DIR, f), "utf8"))
    .join("\n");

describe("function reference", () => {
  it("lists exactly the functions registered in BuiltIns.java", () => {
    const src = builtInSources();
    const registered = [...src.matchAll(/\b(?:def|lazy)\(\s*m,\s*"([A-Za-z0-9]+)"/g)].map((m) => m[1]);
    expect(registered.length).toBeGreaterThan(40);
    expect([...FUNCTION_DOCS.map((f) => f.name)].sort()).toEqual([...registered].sort());
  });

  it("has a signature, description, example and result for every function", () => {
    for (const f of FUNCTION_DOCS) {
      expect(f.signature, f.name).toContain(f.name);
      expect(f.doc.length, f.name).toBeGreaterThan(5);
      expect(f.example, f.name).not.toBe("");
      expect(f.result, f.name).not.toBe("");
    }
  });

  it("groups into the seven registry categories", () => {
    expect(functionsByCategory().map((g) => g.category)).toEqual([
      "String",
      "Number",
      "Array",
      "Object",
      "Date",
      "Type",
      "Logic",
    ]);
  });
});
