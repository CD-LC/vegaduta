// Every t("...") the Coding tasks screens read must exist in messages/en.json's "codingTasks" namespace: t() returns
// the dotted path for a missing key (it never falls back), so a typo would render "codingTasks.xyz" on screen. Keys
// built at runtime (status, phase, problem names) are listed explicitly below.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";
import { CODING_TASK_STATUSES } from "../../lib/codingTasks";

const ns = (en as any).codingTasks as Record<string, string>;

const FILES = [
  "../../coding/page.tsx",
  "./CodingTasksPanel.tsx",
  "./CodingTaskForm.tsx",
  "./CodingTaskDetail.tsx",
  "./DiffViewer.tsx",
];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  // t(cond ? "a" : "b") and t(x ? "a" : "b", ...) - both branches are literal keys.
  for (const m of source.matchAll(/\bt\(\s*[^"()]*\?\s*"([A-Za-z0-9_]+)"\s*:\s*"([A-Za-z0-9_]+)"/g)) {
    keys.add(m[1]);
    keys.add(m[2]);
  }
  return [...keys];
}

describe("codingTasks i18n", () => {
  it("has the namespace and the sidebar label", () => {
    expect(ns).toBeTruthy();
    expect((en as any).nav.railCodingTasks).toBeTruthy();
  });

  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      expect(keys.filter((k) => !(k in ns))).toEqual([]);
    });
  }

  it("defines every dynamically built key", () => {
    const dynamic = [
      ...CODING_TASK_STATUSES.map((s) => `status_${s}`),
      ...["QUEUED", "SEEDING", "EDITING", "TESTING", "REVIEWING", ...CODING_TASK_STATUSES].map((p) => `phase_${p}`),
      ...[
        "agentRequired",
        "instructionRequired",
        "instructionTooLong",
        "repoUrl",
        "archiveRequired",
        "archiveTooLarge",
        "testRunner",
        "testArgs",
        "minutes",
      ].map((p) => `problem_${p}`),
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    expect(ns.archiveHint).toContain("{mb}");
    expect(ns.minutesHint).toContain("{max}");
    expect(ns.usage).toContain("{steps}");
    expect(ns.usage).toContain("{maxSteps}");
    expect(ns.usage).toContain("{time}");
    expect(ns.filesChanged).toContain("{count}");
    expect(ns.diffStats).toContain("{added}");
    expect(ns.diffStats).toContain("{removed}");
    expect(ns.testPassed).toContain("{run}");
    expect(ns.testFailed).toContain("{run}");
    expect(ns.testFailed).toContain("{code}");
  });
});
