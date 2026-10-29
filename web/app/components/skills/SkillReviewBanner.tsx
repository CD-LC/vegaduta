"use client";

// "Changed since you approved it" - the review gate for a custom skill. Shown in the skills
// page viewer when a skill's content no longer matches the hash a workspace admin approved
// (or when a high-risk skill has never been approved). Admins approve the exact content they
// are looking at, or reject it (which switches the skill off).
//
// Honest about enforcement: with app.skills.supply-chain.enforce off nothing is blocked, and
// the banner says agents still use the skill.

import { useState } from "react";
import { useTranslations } from "../../lib/i18n";
import type { SkillSecurityReport } from "../../lib/api/skillSecurity";
import { describeApprover } from "../../lib/skillSecurity";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";

export default function SkillReviewBanner({
  report,
  canManage,
  busy,
  onApprove,
  onReject,
}: {
  report: SkillSecurityReport;
  canManage: boolean;
  busy: boolean;
  onApprove: (acknowledgeHighRisk: boolean) => void;
  onReject: () => void;
}) {
  const t = useTranslations("skillsPage");
  const [acknowledged, setAcknowledged] = useState(false);
  const changed = report.status === "NEEDS_REVIEW";
  const high = report.scan.riskLevel === "high";
  const approver = describeApprover(report.approvedBy);

  return (
    <Alert variant="warning" data-testid="skill-review-banner">
      <AlertTitle>{changed ? t("reviewBannerTitle") : t("reviewBannerUnapprovedTitle")}</AlertTitle>
      <AlertDescription>
        <div className="space-y-2">
          <p>
            {changed
              ? report.approvedAt
                ? approver.kind === "person"
                  ? t("reviewBannerBody", {
                      date: new Date(report.approvedAt).toLocaleDateString(),
                      by: approver.name || t("reviewUnknownApprover"),
                    })
                  : t("reviewBannerBodyAuto", {
                      date: new Date(report.approvedAt).toLocaleDateString(),
                      how: t(approver.kind === "autoAttach" ? "reviewAutoHowAttach" : "reviewAutoHowEnable"),
                    })
                : t("reviewBannerNoRecord")
              : t("reviewBannerUnapprovedBody")}
          </p>

          {changed && report.changes.length > 0 && (
            <div>
              <p className="font-medium">{t("reviewChangesHeading")}</p>
              <ul className="list-disc ps-5">
                {report.changes.map((c, i) => (
                  <li key={`${i}:${c}`}>{c}</li>
                ))}
              </ul>
            </div>
          )}

          <p className="text-xs">{report.blocked ? t("reviewEnforcedNote") : t("reviewWarnOnlyNote")}</p>

          {canManage ? (
            <div className="space-y-2 pt-1">
              {high && (
                <label className="flex cursor-pointer items-start gap-2 text-xs">
                  <input
                    type="checkbox"
                    className="mt-0.5"
                    checked={acknowledged}
                    disabled={busy}
                    onChange={(e) => setAcknowledged(e.target.checked)}
                  />
                  <span>{t("reviewAckHighRisk")}</span>
                </label>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  disabled={busy || (high && !acknowledged)}
                  onClick={() => onApprove(high && acknowledged)}
                >
                  {t("reviewApprove")}
                </Button>
                <Button size="sm" variant="outline" disabled={busy} onClick={onReject}>
                  {t("reviewReject")}
                </Button>
              </div>
            </div>
          ) : (
            <p className="text-xs">{t("reviewAdminOnly")}</p>
          )}
        </div>
      </AlertDescription>
    </Alert>
  );
}
