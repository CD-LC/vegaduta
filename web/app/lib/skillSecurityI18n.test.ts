// Skill-security i18n guard. useTranslations() is untyped and a missing key renders its own path
// instead of failing, so this reads the skill-security sources and checks every t("...") literal -
// and every key the pure helpers can produce dynamically - against messages/en.json.

import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import {
  findingRuleKey,
  knownRuleIds,
  riskLabelKey,
  whereLabelKey,
  type WhereKind,
} from "./skillSecurity";

const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;
const catalogs = en as unknown as Record<string, Record<string, string>>;

function usedKeys(relativeFile: string): string[] {
  const source = fs.readFileSync(path.resolve(process.cwd(), relativeFile), "utf8");
  return [...source.matchAll(KEY_RE)].map((m) => m[1]);
}

describe("skillsPage keys used by the skill-security components", () => {
  const files = [
    "app/components/skills/SkillScanFindings.tsx",
    "app/components/skills/SkillScanDialog.tsx",
    "app/components/skills/SkillReviewBanner.tsx",
    "app/components/SkillsPanel.tsx",
  ];
  for (const file of files) {
    it(`${file} only uses keys that exist`, () => {
      const keys = usedKeys(file);
      expect(keys.length).toBeGreaterThan(0);
      for (const key of keys) {
        expect(catalogs.skillsPage[key], `skillsPage.${key} (${file})`).toBeTypeOf("string");
      }
    });
  }
});

describe("agentSkillsCard keys used by the attach confirmation", () => {
  it("only uses keys that exist", () => {
    const keys = usedKeys("app/components/agents/AgentSkillsCard.tsx");
    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(catalogs.agentSkillsCard[key], `agentSkillsCard.${key}`).toBeTypeOf("string");
    }
  });

  it("has the risk-level labels the helpers resolve dynamically", () => {
    for (const level of ["none", "low", "medium", "high"] as const) {
      expect(catalogs.agentSkillsCard[riskLabelKey(level)], riskLabelKey(level)).toBeTypeOf("string");
    }
  });
});

describe("keys the helpers build dynamically", () => {
  it("has copy for every known scan rule and the fallback", () => {
    for (const rule of knownRuleIds()) {
      expect(catalogs.skillsPage[findingRuleKey(rule)], `${rule} -> ${findingRuleKey(rule)}`).toBeTypeOf("string");
    }
    expect(catalogs.skillsPage[findingRuleKey("not-a-rule")]).toBeTypeOf("string");
  });

  it("has a label for every risk level and every place a finding can sit", () => {
    for (const level of ["none", "low", "medium", "high"] as const) {
      expect(catalogs.skillsPage[riskLabelKey(level)], riskLabelKey(level)).toBeTypeOf("string");
    }
    const kinds: WhereKind[] = ["name", "description", "body", "file", "fileName", "other"];
    for (const kind of kinds) {
      expect(catalogs.skillsPage[whereLabelKey(kind)], whereLabelKey(kind)).toBeTypeOf("string");
    }
  });

  it("has the keys the review banner and attach prompt pick dynamically", () => {
    for (const key of ["reviewAutoHowAttach", "reviewAutoHowEnable", "reviewBannerBodyAuto", "reviewOffNote"]) {
      expect(catalogs.skillsPage[key], `skillsPage.${key}`).toBeTypeOf("string");
    }
    expect(catalogs.agentSkillsCard.attachBodyFirst).toBeTypeOf("string");
  });

  it("never lets a scan read as a promise of safety", () => {
    expect(catalogs.skillsPage.scanDisclaimer).toMatch(/does not prove/i);
    expect(catalogs.agentSkillsCard.attachDisclaimer).toMatch(/does not prove/i);
    expect(catalogs.skillsPage.riskBadgeTitle).toMatch(/does not prove/i);
    expect(catalogs.skillsPage.riskNone.toLowerCase()).not.toContain("safe");
  });
});
