"use client";

// The findings of one skill scan: overall risk, each finding with where it was found and a
// short sanitised excerpt, and an honest disclaimer. Shared by the skills page viewer, the
// scan-before-install dialog and the review banner.

import { useTranslations } from "../../lib/i18n";
import {
  findingRuleKey,
  riskLabelKey,
  splitFindings,
  whereLabelKey,
  whereParts,
} from "../../lib/skillSecurity";
import type { SkillScanReport } from "../../lib/api/skillSecurity";
import { Badge } from "../ui/badge";
import SkillRiskBadge from "./SkillRiskBadge";

const MAX_SHOWN = 12;

export default function SkillScanFindings({
  report,
  showDisclaimer = true,
}: {
  report: SkillScanReport;
  showDisclaimer?: boolean;
}) {
  const t = useTranslations("skillsPage");
  const { shown, hidden } = splitFindings(report.findings, MAX_SHOWN);

  return (
    <div className="space-y-2" data-testid="skill-scan-findings">
      <div className="flex flex-wrap items-center gap-2">
        <SkillRiskBadge level={report.riskLevel} label={t(riskLabelKey(report.riskLevel))} />
        <span className="text-xs text-muted-foreground">
          {report.findings.length === 0 ? t("scanNoFindings") : t("scanFindingsCount", { count: report.findings.length })}
        </span>
      </div>

      {shown.length > 0 && (
        <ul className="space-y-2">
          {shown.map((f, i) => {
            const where = whereParts(f.where);
            return (
              <li key={`${f.rule}:${f.where}:${i}`} className="rounded-md border border-border p-2.5">
                <div className="flex flex-wrap items-center gap-2">
                  <SkillRiskBadge
                    level={f.severity}
                    label={t(f.severity === "high" ? "severityHigh" : f.severity === "medium" ? "severityMedium" : "severityLow")}
                  />
                  <span className="text-sm font-medium text-foreground">{t(findingRuleKey(f.rule))}</span>
                  <Badge variant="outline" className="max-w-full truncate">
                    {t(whereLabelKey(where.kind), { path: where.path })}
                  </Badge>
                </div>
                {f.excerpt && (
                  <code className="mt-1.5 block max-h-24 overflow-auto whitespace-pre-wrap break-words rounded bg-muted/50 px-2 py-1 font-mono text-xs text-foreground">
                    {f.excerpt}
                  </code>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {(hidden > 0 || report.truncated) && (
        <p className="text-xs text-muted-foreground">
          {hidden > 0 ? t("scanMoreFindings", { count: hidden }) : t("scanTruncated")}
        </p>
      )}

      {showDisclaimer && <p className="text-xs leading-relaxed text-muted-foreground">{t("scanDisclaimer")}</p>}
    </div>
  );
}
