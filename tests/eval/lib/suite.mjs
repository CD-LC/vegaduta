// Task-suite loading and validation. Pure apart from the file read in loadSuite.
import { readFileSync } from "node:fs";

export const CATEGORIES = ["qa", "tool-use", "knowledge", "approval", "workflow"];
const CHECK_TYPES = new Set(["contains_all", "contains_any", "not_contains", "regex", "number", "refusal"]);

/** Returns a list of problems (empty when the suite is valid). */
export function validateSuite(suite) {
  const errs = [];
  if (!suite || typeof suite !== "object") return ["suite is not an object"];
  if (!suite.name) errs.push("missing name");
  if (!/^\d+\.\d+\.\d+$/.test(suite.version ?? "")) errs.push("version must be semver");
  if (!Array.isArray(suite.tasks) || suite.tasks.length === 0) return [...errs, "tasks must be a non-empty array"];
  const seen = new Set();
  for (const t of suite.tasks) {
    const id = t.id ?? "(no id)";
    if (!t.id) errs.push("task without id");
    if (seen.has(t.id)) errs.push(`duplicate task id ${id}`);
    seen.add(t.id);
    if (!CATEGORIES.includes(t.category)) errs.push(`${id}: bad category ${t.category}`);
    if (typeof t.input !== "string" || !t.input) errs.push(`${id}: input must be a non-empty string`);
    if (!t.role) errs.push(`${id}: missing role (the provisioned agent/workflow this task targets)`);
    if (!Array.isArray(t.checks) && !t.tools && !t.guardrail) errs.push(`${id}: no checks, tools or guardrail: nothing to score`);
    for (const c of t.checks ?? []) {
      if (!CHECK_TYPES.has(c.type)) errs.push(`${id}: unknown check type ${c.type}`);
      if (["contains_all", "contains_any", "not_contains"].includes(c.type) && !Array.isArray(c.values)) errs.push(`${id}: ${c.type} needs values[]`);
      if (c.type === "regex") {
        try {
          new RegExp(c.pattern, c.flags ?? "i");
        } catch {
          errs.push(`${id}: invalid regex`);
        }
      }
      if (c.type === "number" && typeof c.value !== "number") errs.push(`${id}: number check needs numeric value`);
    }
    if (t.tools && !Array.isArray(t.tools.calls)) errs.push(`${id}: tools.calls must be an array`);
  }
  return errs;
}

export function loadSuite(path) {
  const suite = JSON.parse(readFileSync(path, "utf8"));
  const errs = validateSuite(suite);
  if (errs.length) throw new Error(`invalid suite ${path}:\n  ${errs.join("\n  ")}`);
  return suite;
}
