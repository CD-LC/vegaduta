"use client";

import { ShieldAlert, ShieldCheck, ShieldQuestion } from "lucide-react";
import { Badge } from "../../components/ui/badge";
import { useTranslations } from "../../lib/i18n";
import { formatKappa, type JudgeCalibrationInfo } from "./judgeCalibration";

/**
 * Judge-trust badge on an eval run (wave E): whether the LLM judge that scored the run was ever calibrated
 * against human labels. Informational only - it never changes pass/fail. Renders nothing when the run carries
 * no `judgeCalibration` (calibration disabled on this core, or the run used no judge), so an older core or a
 * flag-off tenant sees exactly what they saw before.
 */
export default function JudgeTrustBadge({ info }: { info: JudgeCalibrationInfo | null | undefined }) {
  const t = useTranslations("judgeCalibration");
  if (!info) return null;

  if (info.status === "OK") {
    return (
      <Badge
        variant="state"
        state="var(--state-success)"
        title={t("badgeOkHint", { kappa: formatKappa(info.kappa), n: info.n ?? 0 })}
      >
        <ShieldCheck aria-hidden="true" /> {t("badgeOk", { kappa: formatKappa(info.kappa) })}
      </Badge>
    );
  }
  if (info.status === "LOW") {
    return (
      <Badge
        variant="state"
        state="var(--state-warning)"
        title={t("badgeLowHint", { kappa: formatKappa(info.kappa), n: info.n ?? 0 })}
      >
        <ShieldAlert aria-hidden="true" /> {t("badgeLow", { kappa: formatKappa(info.kappa) })}
      </Badge>
    );
  }
  return (
    <Badge variant="secondary" title={t("badgeUncalibratedHint")}>
      <ShieldQuestion aria-hidden="true" /> {t("badgeUncalibrated")}
    </Badge>
  );
}
