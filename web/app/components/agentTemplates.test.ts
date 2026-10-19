import { describe, it, expect } from "vitest";
import {
  AGENT_TEMPLATES,
  templateNameKey,
  templateTaglineKey,
  templateKnowledgeHintKey,
} from "./agentTemplates";
import en from "../../messages/en.json";

const strings = (en as Record<string, any>).agentTemplates as Record<string, string> | undefined;

describe("agentTemplates i18n key helpers", () => {
  it("has templates and an agentTemplates i18n namespace to resolve against", () => {
    // Guards every per-template assertion below from passing vacuously.
    expect(AGENT_TEMPLATES.length).toBeGreaterThan(0);
    expect(strings, "en.json is missing the agentTemplates namespace").toBeTruthy();
  });

  it("derives camelCase i18n keys from kebab template keys", () => {
    expect(templateNameKey("homework-helper")).toBe("homeworkHelperName");
    expect(templateTaglineKey("exam-prep-coach")).toBe("examPrepCoachTagline");
    expect(templateKnowledgeHintKey("homework-helper")).toBe("homeworkHelperKnowledgeHint");
  });

  // Regression guard for the AgentsPanel "Using the <empty> template" bug:
  // templates carry only i18n *keys*, never a literal .name, so the displayed
  // name must come from tTemplates(templateNameKey(key)) and that key MUST
  // exist in en.json.
  it("every template's name key resolves to a real English string", () => {
    for (const t of AGENT_TEMPLATES) {
      const key = templateNameKey(t.key);
      expect(strings?.[key], `missing agentTemplates.${key} for template "${t.key}"`).toBeTruthy();
      expect(typeof strings?.[key]).toBe("string");
    }
  });

  it("templates expose NO literal `name` field (name is i18n-only)", () => {
    for (const t of AGENT_TEMPLATES) {
      expect((t as Record<string, unknown>).name).toBeUndefined();
    }
  });

  // knowledgeHint is a boolean FLAG; the hint text is a separate i18n key.
  // (The old bug rendered the boolean directly as the string "true".)
  it("every knowledgeHint-flagged template has a resolvable hint string", () => {
    for (const t of AGENT_TEMPLATES) {
      if ((t as Record<string, unknown>).knowledgeHint) {
        const key = templateKnowledgeHintKey(t.key);
        expect(strings?.[key], `missing agentTemplates.${key} for template "${t.key}"`).toBeTruthy();
      }
    }
  });
});
