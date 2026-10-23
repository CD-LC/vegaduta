"use client";

// Plain-language explainer of the three autonomy tiers and what each one means for approvals.
// Mirrors core AgentAutonomyTier / ApprovalGateMode (change the copy only together with that code):
//   SUPERVISED       -> ALWAYS_GATE   every tool call waits for a person, whatever the workspace rules say;
//                                     the per-run tool-step ceiling is tighter
//   SEMI_AUTONOMOUS  -> POLICY_DEFAULT the workspace's per-tool approval rule decides (the default tier)
//   FULLY_AUTONOMOUS -> NEVER_GATE    no tool call ever waits; per-tool rules are ignored; higher step ceiling
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../ui/badge";
import { cn } from "../../lib/utils";

export const AUTONOMY_TIERS = ["SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"] as const;
export type AutonomyTierName = (typeof AUTONOMY_TIERS)[number];

const KEYS: Record<AutonomyTierName, { name: string; what: string; consequence: string }> = {
  SUPERVISED: { name: "tierSupervisedName", what: "tierSupervisedWhat", consequence: "tierSupervisedConsequence" },
  SEMI_AUTONOMOUS: { name: "tierSemiName", what: "tierSemiWhat", consequence: "tierSemiConsequence" },
  FULLY_AUTONOMOUS: { name: "tierFullName", what: "tierFullWhat", consequence: "tierFullConsequence" },
};

export function isAutonomyTier(value: unknown): value is AutonomyTierName {
  return typeof value === "string" && (AUTONOMY_TIERS as readonly string[]).includes(value);
}

export default function AutonomyTierExplainer({
  current,
  className,
}: {
  /** Highlight the tier this agent uses; omit on the workspace-wide queue. */
  current?: string | null;
  className?: string;
}) {
  const t = useTranslations("approvalsQueue");
  return (
    <section
      id="approval-tiers"
      aria-labelledby="approval-tiers-title"
      data-testid="autonomy-tier-explainer"
      className={cn("grid gap-3 rounded-lg border border-border bg-card p-4", className)}
    >
      <div className="grid gap-1">
        <h3 id="approval-tiers-title" className="text-sm font-semibold">
          {t("tiersTitle")}
        </h3>
        <p className="text-xs text-muted-foreground">{t("tiersIntro")}</p>
      </div>
      <ul className="grid gap-2 sm:grid-cols-3">
        {AUTONOMY_TIERS.map((tier) => {
          const active = current === tier;
          return (
            <li
              key={tier}
              data-testid={`tier-card-${tier.toLowerCase()}`}
              aria-current={active ? "true" : undefined}
              className={cn("grid content-start gap-1.5 rounded-lg border p-3", active ? "border-primary bg-primary/5" : "border-border")}
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium">{t(KEYS[tier].name)}</span>
                {active && <Badge variant="accent">{t("tierCurrent")}</Badge>}
              </div>
              <p className="text-xs text-muted-foreground">{t(KEYS[tier].what)}</p>
              <p className="text-xs">
                <strong>{t("tierConsequenceLabel")}</strong> {t(KEYS[tier].consequence)}
              </p>
            </li>
          );
        })}
      </ul>
      <p className="text-xs text-muted-foreground">{t("tiersRuleNote")}</p>
    </section>
  );
}
