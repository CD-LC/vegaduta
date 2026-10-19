import { describe, expect, it } from "vitest";
import type { SkillFinding, SkillSecurityOverview, SkillSecuritySummary } from "./api/skillSecurity";
import {
  confirmBeforeAttach,
  confirmBeforeSave,
  describeApprover,
  findingRuleKey,
  isFlagged,
  isRiskAtLeast,
  needsReview,
  riskBadgeStyle,
  riskLabelKey,
  riskRank,
  skillsNeedingReview,
  sortFindings,
  splitFindings,
  summaryByName,
  whereLabelKey,
  whereParts,
  worstRisk,
} from "./skillSecurity";

const summary = (over: Partial<SkillSecuritySummary>): SkillSecuritySummary => ({
  name: "s",
  source: "CUSTOM",
  status: "UNPINNED",
  riskLevel: "none",
  findingCount: 0,
  blocked: false,
  ...over,
});

const finding = (over: Partial<SkillFinding>): SkillFinding => ({
  rule: "embedded-url",
  severity: "low",
  excerpt: "https://example.com",
  where: "body",
  ...over,
});

describe("risk levels", () => {
  it("orders none < low < medium < high and tolerates missing values", () => {
    expect(riskRank("none")).toBe(0);
    expect(riskRank("high")).toBe(3);
    expect(riskRank(null)).toBe(0);
    expect(riskRank(undefined)).toBe(0);
    expect(isRiskAtLeast("medium", "medium")).toBe(true);
    expect(isRiskAtLeast("low", "medium")).toBe(false);
    expect(worstRisk(["low", "high", "medium"])).toBe("high");
    expect(worstRisk([])).toBe("none");
    expect(worstRisk([null, undefined])).toBe("none");
  });

  it("maps every level to a badge label key", () => {
    expect(riskLabelKey("none")).toBe("riskNone");
    expect(riskLabelKey("low")).toBe("riskLow");
    expect(riskLabelKey("medium")).toBe("riskMedium");
    expect(riskLabelKey("high")).toBe("riskHigh");
  });

  it("never paints a clean or low scan green (a scan cannot promise safety)", () => {
    expect(riskBadgeStyle("none")).toEqual({ variant: "outline" });
    expect(riskBadgeStyle("low")).toEqual({ variant: "secondary" });
    expect(riskBadgeStyle("medium")).toEqual({ variant: "state", state: "var(--state-warning)" });
    expect(riskBadgeStyle("high")).toEqual({ variant: "state", state: "var(--state-error)" });
  });
});

describe("finding rules", () => {
  it("turns a known rule id into its i18n key", () => {
    expect(findingRuleKey("hidden-unicode-tag")).toBe("ruleHiddenUnicodeTag");
    expect(findingRuleKey("override-instructions")).toBe("ruleOverrideInstructions");
    expect(findingRuleKey("metadata-mismatch")).toBe("ruleMetadataMismatch");
  });

  it("never builds a key from an id it does not know", () => {
    expect(findingRuleKey("brand-new-rule")).toBe("ruleUnknown");
    expect(findingRuleKey("")).toBe("ruleUnknown");
  });
});

describe("where a finding is", () => {
  it("splits files and file names from the three metadata places", () => {
    expect(whereParts("description")).toEqual({ kind: "description", path: "" });
    expect(whereParts("body")).toEqual({ kind: "body", path: "" });
    expect(whereParts("name")).toEqual({ kind: "name", path: "" });
    expect(whereParts("file:docs/a.md")).toEqual({ kind: "file", path: "docs/a.md" });
    expect(whereParts("file-name:../x")).toEqual({ kind: "fileName", path: "../x" });
    expect(whereParts("weird")).toEqual({ kind: "other", path: "weird" });
  });

  it("maps each kind to a label key", () => {
    expect(whereLabelKey("description")).toBe("scanWhereDescription");
    expect(whereLabelKey("file")).toBe("scanWhereFile");
    expect(whereLabelKey("fileName")).toBe("scanWhereFileName");
    expect(whereLabelKey("other")).toBe("scanWhereOther");
  });
});

describe("sorting and trimming findings", () => {
  const findings = [
    finding({ rule: "embedded-url", severity: "low" }),
    finding({ rule: "override-instructions", severity: "high", where: "description" }),
    finding({ rule: "credential-request", severity: "medium" }),
    finding({ rule: "exfiltrate-data", severity: "high", where: "body" }),
  ];

  it("puts the most severe first, then by rule, without mutating the input", () => {
    const sorted = sortFindings(findings);
    expect(sorted.map((f) => f.rule)).toEqual([
      "exfiltrate-data",
      "override-instructions",
      "credential-request",
      "embedded-url",
    ]);
    expect(findings[0].rule).toBe("embedded-url");
  });

  it("reports how many findings were left out", () => {
    const { shown, hidden } = splitFindings(findings, 2);
    expect(shown).toHaveLength(2);
    expect(hidden).toBe(2);
    expect(splitFindings(findings, 10)).toEqual({ shown: sortFindings(findings), hidden: 0 });
    expect(splitFindings(findings, -3).shown).toEqual([]);
  });
});

describe("who needs a review", () => {
  it("flags a changed skill and an unapproved high-risk skill, never a built-in", () => {
    expect(needsReview(summary({ status: "NEEDS_REVIEW" }))).toBe(true);
    expect(needsReview(summary({ status: "UNPINNED", riskLevel: "high" }))).toBe(true);
    expect(needsReview(summary({ status: "APPROVED", riskLevel: "high" }))).toBe(false);
    expect(needsReview(summary({ status: "UNPINNED", riskLevel: "medium" }))).toBe(false);
    expect(needsReview(summary({ source: "BUILT_IN", status: "BUILT_IN", riskLevel: "high" }))).toBe(false);
    expect(needsReview(null)).toBe(false);
  });

  it("builds a lookup and the review list from the overview", () => {
    const overview: SkillSecurityOverview = {
      enabled: true,
      enforcing: false,
      skills: [
        summary({ name: "a", status: "APPROVED" }),
        summary({ name: "b", status: "NEEDS_REVIEW", riskLevel: "low" }),
        summary({ name: "pdf", source: "BUILT_IN", status: "BUILT_IN" }),
      ],
    };
    expect(summaryByName(overview).get("b")?.status).toBe("NEEDS_REVIEW");
    expect(summaryByName(null).size).toBe(0);
    expect(skillsNeedingReview(overview).map((s) => s.name)).toEqual(["b"]);
    expect(skillsNeedingReview(undefined)).toEqual([]);
  });
});

describe("when to ask for confirmation", () => {
  it("interrupts an editor save only for medium and high", () => {
    expect(confirmBeforeSave("none")).toBe(false);
    expect(confirmBeforeSave("low")).toBe(false);
    expect(confirmBeforeSave("medium")).toBe(true);
    expect(confirmBeforeSave("high")).toBe(true);
  });

  it("asks before attaching a flagged, changed or not-yet-approved custom skill, never a built-in or an unknown one", () => {
    expect(confirmBeforeAttach(summary({ riskLevel: "medium" }))).toBe(true);
    expect(confirmBeforeAttach(summary({ status: "NEEDS_REVIEW" }))).toBe(true);
    // The first attach pins the content, so an unapproved skill shows its scan result even when clean.
    expect(confirmBeforeAttach(summary({ status: "UNPINNED", riskLevel: "none" }))).toBe(true);
    expect(confirmBeforeAttach(summary({ status: "UNPINNED", riskLevel: "low" }))).toBe(true);
    expect(confirmBeforeAttach(summary({ status: "APPROVED", riskLevel: "low" }))).toBe(false);
    expect(confirmBeforeAttach(summary({ status: "APPROVED", riskLevel: "none" }))).toBe(false);
    expect(confirmBeforeAttach(summary({ source: "BUILT_IN", status: "BUILT_IN", riskLevel: "high" }))).toBe(false);
    expect(confirmBeforeAttach(undefined)).toBe(false);
  });

  it("leaves switched-off skills out of the review queue", () => {
    const overview: SkillSecurityOverview = {
      enabled: true,
      enforcing: false,
      skills: [
        summary({ name: "rejected", status: "NEEDS_REVIEW" }),
        summary({ name: "waiting", status: "NEEDS_REVIEW" }),
      ],
    };
    expect(skillsNeedingReview(overview).map((s) => s.name)).toEqual(["rejected", "waiting"]);
    expect(skillsNeedingReview(overview, (name) => name !== "rejected").map((s) => s.name)).toEqual(["waiting"]);
  });

  it("never shows an internal approver string as a person's name", () => {
    expect(describeApprover("attach:3f2a9c1e-0000-4000-8000-123456789abc")).toEqual({ kind: "autoAttach", name: null });
    expect(describeApprover("enable:alice")).toEqual({ kind: "autoEnable", name: null });
    expect(describeApprover("apply:alice")).toEqual({ kind: "person", name: null });
    expect(describeApprover("3f2a9c1e-0000-4000-8000-123456789abc")).toEqual({ kind: "person", name: null });
    expect(describeApprover("unknown")).toEqual({ kind: "person", name: null });
    expect(describeApprover(null)).toEqual({ kind: "person", name: null });
    expect(describeApprover("carol")).toEqual({ kind: "person", name: "carol" });
  });

  it("marks only medium+ or changed skills as flagged in a list", () => {
    expect(isFlagged(summary({ riskLevel: "low" }))).toBe(false);
    expect(isFlagged(summary({ riskLevel: "medium" }))).toBe(true);
    expect(isFlagged(summary({ status: "NEEDS_REVIEW" }))).toBe(true);
    expect(isFlagged(undefined)).toBe(false);
  });
});
