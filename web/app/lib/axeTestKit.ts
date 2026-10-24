// Accessibility checks for component tests: runs axe-core over rendered DOM in jsdom and returns readable problems.
// Not imported by production code.
//
// jsdom has no layout engine, so the rules that need one are off here: colour contrast (the themes' tokens are
// verified separately by the design-system contract) and the page-level landmark rules, which only make sense for a
// whole document. Everything else (names, roles, ARIA validity, labels, duplicate ids, headings order, ...) runs.
import axe from "axe-core";

const DISABLED_IN_JSDOM = ["color-contrast", "region", "landmark-one-main", "page-has-heading-one", "scrollable-region-focusable"];

export interface AxeOptions {
  /** Extra rule ids to switch off for one check, each with the reason in the test that passes it. */
  disable?: string[];
}

export async function axeViolations(root: Element | Document = document.body, opts: AxeOptions = {}): Promise<string[]> {
  const rules: Record<string, { enabled: boolean }> = {};
  for (const id of [...DISABLED_IN_JSDOM, ...(opts.disable ?? [])]) rules[id] = { enabled: false };
  const result = await axe.run(root, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"] },
    rules,
  });
  return result.violations.map(
    (v) => `${v.id} (${v.impact}): ${v.help} -> ${v.nodes.slice(0, 3).map((n) => n.target.join(" ")).join(" | ")}`
  );
}
