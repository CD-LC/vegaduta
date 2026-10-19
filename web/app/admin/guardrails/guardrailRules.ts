// Guardrail rule types, request bounds and the in-browser pattern check for
// the workspace guardrails console (page.tsx).

import { errorMessage } from "../../lib/errors";

type RuleAction = "BLOCK" | "REDACT";
type RuleType = "DEFAULT_TEMPLATE" | "CUSTOM";

/** Mirrors GuardrailRuleResponse (core/guardrails/dto). */
export interface GuardrailRule {
  id: string;
  tenantId: string | null;
  ruleType: RuleType;
  name: string;
  description: string | null;
  pattern: string;
  action: RuleAction;
  enabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface RuleDraft {
  name: string;
  description: string;
  pattern: string;
  action: RuleAction;
  enabled: boolean;
}

export const EMPTY_DRAFT: RuleDraft = { name: "", description: "", pattern: "", action: "BLOCK", enabled: true };

/** Mirrors the request DTO's @Size bounds, so an over-long value fails here instead of as a 400. */
export const MAX_NAME = 255;
export const MAX_DESCRIPTION = 2000;
export const MAX_PATTERN = 1000;

/**
 * Compiles the pattern the way the browser can, for instant feedback while
 * typing. Deliberately advertised as approximate in the UI: the server compiles
 * with java.util.regex, which agrees with JavaScript on everything most rules
 * use (literals, classes, quantifiers, anchors) and differs on exotica like
 * possessive quantifiers and some named-group syntax. The server re-validates
 * and is the authority — GuardrailRuleService rejects an un-compilable pattern
 * with a 400 carrying the regex engine's own explanation.
 */
export function compileLocally(pattern: string): { regex: RegExp | null; error: string | null } {
  if (!pattern.trim()) return { regex: null, error: null };
  try {
    return { regex: new RegExp(pattern, "gi"), error: null };
  } catch (e) {
    return { regex: null, error: errorMessage(e, "That is not a valid regular expression.") };
  }
}
