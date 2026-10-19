// Every t("...") the Scheduled tasks screens read must exist in messages/en.json's "scheduledTasks" namespace: t()
// returns the dotted path for a missing key (it never falls back), so a typo would render "scheduledTasks.xyz" on
// screen. Keys built at runtime (status, delivery, weekday, problem names...) are listed explicitly below.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";
import { DELIVERY_CHANNELS, SCHEDULE_KINDS, WEEKDAY_CODES } from "../../lib/scheduledTasks";

const ns = (en as any).scheduledTasks as Record<string, string>;

const FILES = [
  "../../scheduled/page.tsx",
  "./ScheduledTasksPanel.tsx",
  "./ScheduledTaskForm.tsx",
  "./ScheduledTaskRuns.tsx",
  "./ScheduledTasksSummary.tsx",
  "./ScheduledTaskExplainer.tsx",
];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("scheduledTasks i18n", () => {
  it("has the namespace and the sidebar label", () => {
    expect(ns).toBeTruthy();
    expect((en as any).nav.railScheduledTasks).toBeTruthy();
  });

  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      const missing = keys.filter((k) => !(k in ns));
      expect(missing).toEqual([]);
    });
  }

  it("defines every dynamically built key", () => {
    const dynamic = [
      ...SCHEDULE_KINDS.map((k) => `freq_${k}`),
      ...DELIVERY_CHANNELS.map((c) => `delivery_${c}`),
      ...DELIVERY_CHANNELS.filter((c) => c !== "in_app").map((c) => `toPlaceholder_${c}`),
      ...DELIVERY_CHANNELS.filter((c) => c !== "in_app").map((c) => `problem_to_${c}`),
      ...WEEKDAY_CODES.map((d) => `day_${d}`),
      ...["describeDaily", "describeWeekdays", "describeWeekly", "describeMonthly", "describeMonthlyLast", "describeCustom"],
      ...[
        "timeRequired",
        "timeInvalid",
        "dayRequired",
        "dayInvalid",
        "cronRequired",
        "cronFields",
        "cronSeconds",
        "cronChars",
        "cronTooFrequent",
        "timezoneInvalid",
        "nameRequired",
        "agentRequired",
        "promptRequired",
        "promptTooLong",
      ].map((p) => `problem_${p}`),
      ...["ENQUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED"].map((s) => `runStatus_${s}`),
      ...["SCHEDULE", "MANUAL", "RETRY"].map((x) => `trigger_${x}`),
      ...["IN_APP", "DELIVERED", "PENDING_APPROVAL", "FAILED"].map((x) => `runDelivery_${x}`),
      ...["all", "active", "failing", "paused"].map((x) => `filter_${x}`),
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    expect(ns.taskLimit).toContain("{count}");
    expect(ns.taskLimit).toContain("{max}");
    expect(ns.promptCount).toContain("{count}");
    expect(ns.promptCount).toContain("{max}");
    expect(ns.problem_promptTooLong).toContain("{max}");
    expect(ns.problem_cronTooFrequent).toContain("{min}");
    expect(ns.cronHint).toContain("{min}");
    expect(ns.runsOfMax).toContain("{count}");
    expect(ns.runsOfMax).toContain("{max}");
    expect(ns.endsOn).toContain("{date}");
    expect(ns.failuresInARow).toContain("{count}");
    expect(ns.deleteTitle).toContain("{name}");
    for (const key of ["describeDaily", "describeWeekdays", "describeWeekly", "describeMonthly", "describeMonthlyLast"]) {
      expect(ns[key]).toContain("{time}");
      expect(ns[key]).toContain("{timezone}");
    }
    expect(ns.describeWeekly).toContain("{day}");
    expect(ns.describeMonthly).toContain("{day}");
    expect(ns.describeCustom).toContain("{cron}");
    expect(ns.describeCustom).toContain("{timezone}");
  });

  it("keeps the placeholders of the summary, the run strip and the explainer", () => {
    for (const key of ["summarySentence", "summarySentenceOne"]) {
      for (const slot of ["{active}", "{paused}", "{failing}"]) expect(ns[key]).toContain(slot);
    }
    expect(ns.summarySentence).toContain("{total}");
    for (const key of ["upcomingSentence", "upcomingMore", "upcomingPartial", "runStripLabel", "lifetimeRuns"]) {
      expect(ns[key]).toContain("{count}");
    }
    for (const slot of ["{count}", "{succeeded}", "{failed}", "{other}"]) expect(ns.runStripSummary).toContain(slot);
    expect(ns.runSampleCost).toContain("{cost}");
    for (const slot of ["{cost}", "{costed}", "{count}"]) expect(ns.runSampleCostPartial).toContain(slot);
  });

  it("never presents the fetched runs as a lifetime figure, and calls cost an estimate", () => {
    expect(ns.runStripLabel.toLowerCase()).toContain("last");
    expect(ns.runSampleCost.toLowerCase()).toContain("estimated");
    expect(ns.runSampleCost.toLowerCase()).toContain("runs shown");
    expect(ns.runSampleCostPartial.toLowerCase()).toContain("estimated");
  });

  it("adds no em dash in the new strings", () => {
    for (const [key, value] of Object.entries(ns)) {
      if (/^(summary|upcoming|runStrip|runSample|lifetime|explain)/.test(key)) expect(value, key).not.toMatch(/—/);
    }
  });
});
