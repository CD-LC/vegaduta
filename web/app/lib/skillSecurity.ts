// Pure helpers for the skill supply-chain UI (badges, finding rows, who needs a review,
// when to ask for confirmation). No React, no fetch - unit tested in skillSecurity.test.ts.
//
// Wording rule for everything built on these: a scan reduces risk, it does not prove a
// skill is safe. "No flags" is never presented as "safe".

import type {
  SkillFinding,
  SkillPinStatus,
  SkillRiskLevel,
  SkillSecurityOverview,
  SkillSecuritySummary,
} from "./api/skillSecurity";

const RISK_ORDER: SkillRiskLevel[] = ["none", "low", "medium", "high"];

export function riskRank(level: SkillRiskLevel | null | undefined): number {
  const i = RISK_ORDER.indexOf(level ?? "none");
  return i < 0 ? 0 : i;
}

export function isRiskAtLeast(level: SkillRiskLevel | null | undefined, min: SkillRiskLevel): boolean {
  return riskRank(level) >= riskRank(min);
}

export function worstRisk(levels: (SkillRiskLevel | null | undefined)[]): SkillRiskLevel {
  let worst: SkillRiskLevel = "none";
  for (const level of levels) {
    if (level && riskRank(level) > riskRank(worst)) worst = level;
  }
  return worst;
}

/** i18n key (skillsPage) for a risk level's badge text. */
export function riskLabelKey(level: SkillRiskLevel): string {
  switch (level) {
    case "high":
      return "riskHigh";
    case "medium":
      return "riskMedium";
    case "low":
      return "riskLow";
    default:
      return "riskNone";
  }
}

export type RiskBadgeStyle = {
  variant: "outline" | "secondary" | "state";
  /** `--state` value for variant "state". */
  state?: string;
};

/**
 * "No flags" and "low" stay neutral on purpose - green would read as "safe", which a scan
 * can never promise. Only medium and high borrow the warning / error state hues.
 */
export function riskBadgeStyle(level: SkillRiskLevel): RiskBadgeStyle {
  switch (level) {
    case "high":
      return { variant: "state", state: "var(--state-error)" };
    case "medium":
      return { variant: "state", state: "var(--state-warning)" };
    case "low":
      return { variant: "secondary" };
    default:
      return { variant: "outline" };
  }
}

// Rule ids the server can emit (SkillScanner). Each has a skillsPage key "rule<PascalCase>";
// anything else renders under "ruleUnknown" so a newer server never shows a raw id as copy.
const KNOWN_RULES = new Set<string>([
  "hidden-unicode-tag",
  "hidden-unicode-bidi",
  "hidden-zero-width",
  "hidden-unicode-variation-selectors",
  "control-characters",
  "confusable-letters",
  "homoglyph-mixed-script",
  "obfuscated-text",
  "override-instructions",
  "prompt-delimiter-injection",
  "role-hijack",
  "reveal-system-prompt",
  "covert-action",
  "exfiltrate-data",
  "outbound-transfer",
  "remote-script-pipe",
  "destructive-command",
  "reverse-shell",
  "encoded-powershell",
  "shell-exec",
  "credential-request",
  "sensitive-path",
  "disable-safeguards",
  "description-directive",
  "description-tool-directive",
  "metadata-mismatch",
  "embedded-url",
  "suspicious-endpoint",
  "url-userinfo",
  "embedded-ip-url",
  "url-shortener",
  "punycode-host",
  "webhook-url",
  "markdown-image-exfil",
  "embedded-ip",
  "encoded-instruction",
  "encoded-text",
  "encoded-blob",
  "hex-blob",
  "escaped-bytes",
  "escaped-unicode",
  "percent-encoded",
  "path-traversal-name",
  "disguised-file-name",
  "bundled-executable",
  "bundled-script",
  "sensitive-file-name",
  "scan-truncated",
]);

export function isKnownRule(rule: string): boolean {
  return KNOWN_RULES.has(rule);
}

/** Every rule id this client has copy for (used by the en.json guard test). */
export function knownRuleIds(): string[] {
  return [...KNOWN_RULES];
}

/** "hidden-unicode-tag" -> "ruleHiddenUnicodeTag"; unknown ids -> "ruleUnknown". */
export function findingRuleKey(rule: string): string {
  if (!KNOWN_RULES.has(rule)) return "ruleUnknown";
  return (
    "rule" +
    rule
      .split("-")
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join("")
  );
}

export type WhereKind = "name" | "description" | "body" | "file" | "fileName" | "other";

/** Splits a finding's `where` ("file:docs/a.md") into a kind and a path. */
export function whereParts(where: string): { kind: WhereKind; path: string } {
  if (where === "name" || where === "description" || where === "body") return { kind: where, path: "" };
  if (where.startsWith("file-name:")) return { kind: "fileName", path: where.slice("file-name:".length) };
  if (where.startsWith("file:")) return { kind: "file", path: where.slice("file:".length) };
  return { kind: "other", path: where };
}

/** i18n key (skillsPage) for where a finding was found. */
export function whereLabelKey(kind: WhereKind): string {
  switch (kind) {
    case "name":
      return "scanWhereName";
    case "description":
      return "scanWhereDescription";
    case "body":
      return "scanWhereBody";
    case "file":
      return "scanWhereFile";
    case "fileName":
      return "scanWhereFileName";
    default:
      return "scanWhereOther";
  }
}

const SEVERITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 };

/** Most severe first, then rule, then place - stable and independent of server order. */
export function sortFindings(findings: SkillFinding[]): SkillFinding[] {
  return [...findings].sort((a, b) => {
    const s = (SEVERITY_ORDER[a.severity] ?? 3) - (SEVERITY_ORDER[b.severity] ?? 3);
    if (s !== 0) return s;
    if (a.rule !== b.rule) return a.rule < b.rule ? -1 : 1;
    if (a.where !== b.where) return a.where < b.where ? -1 : 1;
    return a.excerpt < b.excerpt ? -1 : a.excerpt > b.excerpt ? 1 : 0;
  });
}

export function splitFindings(findings: SkillFinding[], limit: number): { shown: SkillFinding[]; hidden: number } {
  const sorted = sortFindings(findings);
  const max = Math.max(0, limit);
  return { shown: sorted.slice(0, max), hidden: Math.max(0, sorted.length - max) };
}

/** Review queue test: changed since approval, or high risk and nobody has approved it yet. */
export function needsReview(summary: { status: SkillPinStatus; riskLevel: SkillRiskLevel; source: string } | null | undefined): boolean {
  if (!summary || summary.source === "BUILT_IN") return false;
  if (summary.status === "NEEDS_REVIEW") return true;
  return summary.status === "UNPINNED" && summary.riskLevel === "high";
}

export function summaryByName(overview: SkillSecurityOverview | null | undefined): Map<string, SkillSecuritySummary> {
  const map = new Map<string, SkillSecuritySummary>();
  for (const s of overview?.skills ?? []) map.set(s.name, s);
  return map;
}

/**
 * `isEnabled` leaves out skills that are switched off: a rejected skill (reject = turn off) cannot be
 * loaded by any agent, so it is not waiting on anyone. Unknown names count as enabled.
 */
export function skillsNeedingReview(
  overview: SkillSecurityOverview | null | undefined,
  isEnabled?: (name: string) => boolean
): SkillSecuritySummary[] {
  return (overview?.skills ?? []).filter((s) => needsReview(s) && (!isEnabled || isEnabled(s.name)));
}

export type ApproverKind = "person" | "autoAttach" | "autoEnable";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The server records WHO approved a skill as a plain string: an admin's id for an explicit approval,
 * or "attach:<id>" / "enable:<id>" when the first attach or switch-on pinned it automatically
 * (trust on first use). People must never see those internals, so this turns the string into either
 * "an automatic pin and how it happened" or a name that is safe to show (null = say "an admin").
 */
export function describeApprover(approvedBy: string | null | undefined): { kind: ApproverKind; name: string | null } {
  const raw = (approvedBy ?? "").trim();
  if (raw.startsWith("attach:")) return { kind: "autoAttach", name: null };
  if (raw.startsWith("enable:")) return { kind: "autoEnable", name: null };
  const name = raw.startsWith("apply:") ? "" : raw;
  return { kind: "person", name: name && name !== "unknown" && !UUID_RE.test(name) ? name : null };
}

/** Editor saves only interrupt for something a human should look at; low is shown in the result, not gated. */
export function confirmBeforeSave(level: SkillRiskLevel): boolean {
  return isRiskAtLeast(level, "medium");
}

/**
 * Attaching a skill to an agent: show the scan result first when it is flagged, changed since
 * approval, or has never been approved (UNPINNED) - the first attach is what pins its content, so
 * that is the moment the person should see what they are accepting. A custom skill that is already
 * approved at its current content and scans clean attaches without a prompt.
 */
export function confirmBeforeAttach(summary: SkillSecuritySummary | null | undefined): boolean {
  if (!summary || summary.source === "BUILT_IN") return false;
  return needsReview(summary) || summary.status === "UNPINNED" || isRiskAtLeast(summary.riskLevel, "medium");
}

/** True when a skill is flagged enough to deserve a visible marker in a list. */
export function isFlagged(summary: SkillSecuritySummary | null | undefined): boolean {
  if (!summary) return false;
  return summary.status === "NEEDS_REVIEW" || isRiskAtLeast(summary.riskLevel, "medium");
}
