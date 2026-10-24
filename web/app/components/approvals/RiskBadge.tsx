"use client";

import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import type { ToolRisk } from "./approvalsQueueModel";

const STATE: Record<ToolRisk, string> = {
  low: "var(--state-success)",
  medium: "var(--state-warning)",
  high: "var(--state-error)",
};

/** "Low / Medium / High risk" pill. The words carry the meaning; the colour only echoes them. */
export default function RiskBadge({ risk, estimated = true }: { risk: ToolRisk; estimated?: boolean }) {
  const t = useTranslations("approvalsQueue");
  return (
    <Badge
      variant="state"
      state={STATE[risk]}
      data-risk={risk}
      title={estimated ? t("riskEstimateNote") : undefined}
    >
      {t(risk === "low" ? "riskLow" : risk === "medium" ? "riskMedium" : "riskHigh")}
    </Badge>
  );
}
