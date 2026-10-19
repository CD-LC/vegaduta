"use client";

import type { PendingSkillImprovement } from "../../lib/api";
import type { SkillSecurityReport } from "../../lib/api/skillSecurity";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import SkillScanFindings from "./SkillScanFindings";

/**
 * The staged agent-authored rewrite of the open skill (V243/V395), with the
 * scan of the staged text when there is one. The live skill is untouched
 * until Apply.
 */
export default function SkillImprovementReview({
  improvement,
  stagedScan,
  busy,
  onApply,
  onDiscard,
}: {
  improvement: PendingSkillImprovement;
  stagedScan: SkillSecurityReport["stagedScan"] | null | undefined;
  busy: boolean;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const t = useTranslations("skillsPage");
  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <p className="text-sm font-medium text-foreground">{t("improvementHeading")}</p>
      <p className="text-xs text-muted-foreground">
        {t("improvementMeta", { by: improvement.proposedBy })}
        {improvement.rationale ? ` — ${improvement.rationale}` : ""}
      </p>
      <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border/60 bg-muted/40 p-3 font-mono text-xs leading-relaxed text-foreground">
        {improvement.body}
      </pre>
      {stagedScan && (
        <div className="space-y-1">
          <p className="text-xs font-medium text-foreground">{t("improvementScanHeading")}</p>
          <SkillScanFindings report={stagedScan} showDisclaimer={false} />
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={busy} onClick={onApply}>
          {t("improvementApply")}
        </Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={onDiscard}>
          {t("improvementDiscard")}
        </Button>
      </div>
      <p className="text-[11px] text-muted-foreground">{t("improvementHint")}</p>
    </div>
  );
}
