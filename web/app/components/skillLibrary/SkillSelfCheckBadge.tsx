"use client";

import { useTranslations } from "../../lib/i18n";
import type { SkillSelfCheck } from "../../lib/api/skillLibrary";
import { Badge } from "../ui/badge";

/**
 * "Checked: passed / weak" on a drafted skill: the learning loop replayed the draft against the task
 * it came from before showing it. The model's one-line reason is the tooltip. Nothing for a skill
 * that was not checked, so an install with the check off looks exactly as before.
 */
export default function SkillSelfCheckBadge({ check }: { check?: SkillSelfCheck }) {
  const t = useTranslations("skillLibrary");
  if (!check) return null;
  return (
    <Badge
      variant={check.verdict === "PASS" ? "accent" : "outline"}
      title={check.reason || t("selfCheckHint")}
      data-testid={`self-check-${check.verdict.toLowerCase()}`}
    >
      {t(`selfCheck_${check.verdict}`)}
    </Badge>
  );
}
