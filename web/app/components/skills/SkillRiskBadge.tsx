"use client";

// A skill's static-scan risk level as a badge. Presentational on purpose: the caller passes
// the already-translated label, so this can sit in any namespace's component (the agent
// workbench card uses it too) without pulling "skillsPage" into that route's i18n bundle.
//
// "No flags" and "low" are neutral, never green - a scan reduces risk, it does not prove safety.

import { Badge } from "../ui/badge";
import { riskBadgeStyle } from "../../lib/skillSecurity";
import type { SkillRiskLevel } from "../../lib/api/skillSecurity";

export default function SkillRiskBadge({
  level,
  label,
  title,
}: {
  level: SkillRiskLevel;
  label: string;
  title?: string;
}) {
  const style = riskBadgeStyle(level);
  return (
    <Badge variant={style.variant} state={style.state} title={title} data-testid="skill-risk-badge" data-risk={level}>
      {label}
    </Badge>
  );
}
