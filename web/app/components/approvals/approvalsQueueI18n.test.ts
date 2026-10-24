// Every string the approvals experience shows comes from messages/<locale>.json "approvalsQueue": no key used
// by the components is missing, none is dead, and every locale carries the same keys (English text stands in
// until a translator replaces it).
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";

const NS = "approvalsQueue";
const root = path.resolve(__dirname, "../..");
const FILES = [
  "components/approvals/ApprovalContinuationPanel.tsx",
  "components/approvals/ApprovalDetail.tsx",
  "components/approvals/ApprovalFilters.tsx",
  "components/approvals/ApprovalNotifications.tsx",
  "components/approvals/ApprovalStatusBadge.tsx",
  "components/approvals/ApprovalsTable.tsx",
  "components/approvals/AutonomyTierExplainer.tsx",
  "components/approvals/BulkDecisionBar.tsx",
  "components/approvals/ExpiryCountdown.tsx",
  "components/approvals/RiskBadge.tsx",
  "components/approvals/ToolApprovalRules.tsx",
  "components/approvals/ToolCallPreview.tsx",
  "components/approvals/approvalExpiry.ts",
  "components/agents/AgentApprovalModeCard.tsx",
  "components/agents/AgentToolPoliciesCard.tsx",
  "components/agents/ApprovalContinuationStatus.tsx",
  "components/agents/PendingToolApprovals.tsx",
  "components/ApprovalsPanel.tsx",
];

// Keys chosen at run time (a ternary or a template inside the call), which the literal scan cannot see.
const DYNAMIC = [
  ...["PENDING", "APPROVED", "REJECTED", "EXPIRED", "EXECUTION_FAILED", "DEAD_LETTERED"].map((s) => `status_${s}`),
  ...["newer1h", "older1h", "older24h", "older7d"].map((a) => `age_${a}`),
  "filterAnyAge", "durationDaysHours", "durationHoursMinutes", "durationMinutes", "durationSeconds",
  "riskLow", "riskMedium", "riskHigh",
  "rulesSavedAsk", "rulesSavedNever", "rulesAskName", "rulesNeverName", "rulesAskHint", "rulesNeverHint",
  "rulesNeverConfirmBody", "rulesNeverConfirmBodyLow",
  "bulkMixedTools", "bulkNotLowRisk", "bulkNotToolCall", "bulkNotPending",
  "tierSupervisedName", "tierSupervisedWhat", "tierSupervisedConsequence",
  "tierSemiName", "tierSemiWhat", "tierSemiConsequence",
  "tierFullName", "tierFullWhat", "tierFullConsequence",
  "agentModeSupervised", "agentModeSemi", "agentModeFull",
];

const messages = en as unknown as Record<string, Record<string, string>>;

function literalKeys(): Set<string> {
  const keys = new Set<string>();
  for (const file of FILES) {
    const src = readFileSync(path.join(root, file), "utf8");
    const names = [...src.matchAll(/const (\w+) = useTranslations\("approvalsQueue"\)/g)].map((m) => m[1]);
    for (const name of names) for (const m of src.matchAll(new RegExp(`\\b${name}\\(\\s*"([A-Za-z0-9_]+)"`, "g"))) keys.add(m[1]);
  }
  return keys;
}

describe("approvalsQueue i18n", () => {
  it("has every key the components use, and no key nothing uses", () => {
    const ns = messages[NS];
    expect(ns).toBeTypeOf("object");
    const used = new Set([...literalKeys(), ...DYNAMIC]);
    expect([...used].filter((k) => !(k in ns)).sort()).toEqual([]);
    expect(Object.keys(ns).filter((k) => !used.has(k)).sort()).toEqual([]);
  });

  it("is present, with the same keys, in every locale file", () => {
    const dir = path.resolve(root, "../messages");
    const want = Object.keys(messages[NS]).sort();
    const locales = readdirSync(dir).filter((f) => f.endsWith(".json") && !f.includes("baseline"));
    expect(locales.length).toBeGreaterThan(1);
    for (const file of locales) {
      const json = JSON.parse(readFileSync(path.join(dir, file), "utf8")) as Record<string, Record<string, string>>;
      expect(Object.keys(json[NS] ?? {}).sort(), file).toEqual(want);
    }
  });

  it("keeps the placeholders of every message in the locales", () => {
    const dir = path.resolve(root, "../messages");
    const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(",");
    for (const file of readdirSync(dir).filter((f) => f.endsWith(".json") && !f.includes("baseline"))) {
      const json = JSON.parse(readFileSync(path.join(dir, file), "utf8")) as Record<string, Record<string, string>>;
      for (const [key, value] of Object.entries(json[NS] ?? {})) {
        expect(placeholders(value), `${file}:${key}`).toBe(placeholders(messages[NS][key]));
      }
    }
  });
});
