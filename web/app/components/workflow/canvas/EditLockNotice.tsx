"use client";

import { api } from "../../../lib/api";
import TrialOfferButton from "../../TrialOfferButton";
import { UpgradeLock } from "../../ui/upgrade-lock";

/**
 * Shown when the server says the workflow is locked for editing (a FREE
 * tenant's 24h+ workflow): leads with what Premium buys (pricing audit
 * 5.3/B5), then offers the event-triggered 7-day trial (audit 5.4), which
 * renders only when /api/tenant/trial/status reports eligible. On grant the
 * workflow is re-read so the server-computed `editable` flag unlocks the
 * canvas without losing unsaved graph edits.
 */
export default function EditLockNotice({
  workflowId,
  setWorkflow,
  t,
}: {
  workflowId: any;
  setWorkflow: (w: any) => void;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  return (
    <div className="mx-1 mb-2 w-full">
      <UpgradeLock reason={t("editingLockedUpgradeReason")} label={t("upgradePlan")} className="w-full">
        <div className="w-full rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-600 dark:text-amber-400">
          <span className="font-semibold">{t("editLockPremiumSell")}</span> {t("editingLockedBannerText")}{" "}
          {t("editingLockedBannerSuffix")}
        </div>
      </UpgradeLock>
      <div className="mt-2">
        <TrialOfferButton
          trigger="edit-lock"
          onGranted={() => {
            api
              .getWorkflow(workflowId)
              .then((wf) => setWorkflow(wf))
              .catch(() => {});
          }}
        />
      </div>
    </div>
  );
}
