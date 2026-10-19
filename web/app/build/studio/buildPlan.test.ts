import { describe, expect, it } from "vitest";
import { MAX_PLAN_STEPS, findDuplicateDeclarations, integrationPrompt, needsPlan, parsePlan, stepPrompt } from "./buildPlan";

describe("parsePlan", () => {
  it("reads numbered and bulleted lines and ignores prose", () => {
    const reply = "Here is the plan:\n1. App shell with tab bar\n2) **Leads** table with search\n- Pipeline board\nthanks";
    expect(parsePlan(reply)).toEqual(["App shell with tab bar", "Leads table with search", "Pipeline board"]);
  });
  it("caps the number of steps", () => {
    const reply = Array.from({ length: 12 }, (_, i) => `${i + 1}. step number ${i + 1}`).join("\n");
    expect(parsePlan(reply)).toHaveLength(MAX_PLAN_STEPS);
  });
  it("returns nothing for a reply with no list", () => {
    expect(parsePlan("I cannot help with that.")).toEqual([]);
  });
});

describe("needsPlan", () => {
  it("plans multi-screen ideas", () => {
    expect(needsPlan("A CRM with a dashboard, leads table, pipeline board and a workflow page")).toBe(true);
  });
  it("skips simple ideas", () => {
    expect(needsPlan("A tip calculator")).toBe(false);
  });
});

describe("stepPrompt", () => {
  it("asks for the shell first and a single step each time", () => {
    const steps = ["shell", "leads"];
    expect(stepPrompt("crm", steps, 0)).toContain("bottom tab bar");
    expect(stepPrompt("crm", steps, 1)).toContain("Do ONLY step 2");
  });
});

describe("findDuplicateDeclarations", () => {
  it("flags names redeclared at the top level of the script", () => {
    const html = "<body><script>\nconst esc = 1;\nconst screens = {};\nfunction render() {}\nconst screens = [];\nfunction render() {}\n</script></body>";
    expect(findDuplicateDeclarations(html)).toEqual(["screens", "render"]);
  });
  it("ignores indented declarations and clean scripts", () => {
    const html = "<script>\nconst a = 1;\nfunction f() {\n  const a = 2;\n}\n</script>";
    expect(findDuplicateDeclarations(html)).toEqual([]);
  });
});

describe("integrationPrompt", () => {
  it("asks the router to show every real screen", () => {
    const p = integrationPrompt("crm", ["shell", "leads"]);
    expect(p).toContain("FINAL STEP");
    expect(p).toContain("router");
    expect(p).toContain("2. leads");
  });
});
