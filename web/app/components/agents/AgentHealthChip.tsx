"use client";

import { useEffect, useState } from "react";
import { HeartPulse } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { displayScore, healthTone, type AgentHealthScore } from "../../lib/agentInsights";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";

// Small health chip for the workbench identity row. Data comes from the hourly
// health sweep (GET /api/agents/{id}/improvements/health). It is decoration:
// loading and error render nothing, so it can never get in the way of editing.

const TONE_VAR: Record<string, string | undefined> = {
  success: "var(--state-success)",
  warning: "var(--state-warning)",
  danger: "var(--state-error)",
  neutral: undefined,
};

export default function AgentHealthChip({ agentId }: { agentId: string }) {
  const t = useTranslations("agentWorkbench");
  // undefined = loading, null = no score yet, false = failed to load
  const [health, setHealth] = useState<AgentHealthScore | null | false | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    setHealth(undefined);
    api
      .getAgentHealthScore(agentId)
      .then((h) => {
        if (!cancelled) setHealth(h);
      })
      .catch(() => {
        if (!cancelled) setHealth(false);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  if (health === undefined || health === false) return null;

  if (health === null) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            className="state-pill"
            data-testid="agent-health-chip"
            // Keyboard-reachable tooltip trigger (the explanation is not hover-only).
            // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
            tabIndex={0}
          >
            <HeartPulse size={11} aria-hidden="true" />
            {t("healthChipNotScored")}
          </span>
        </TooltipTrigger>
        <TooltipContent>{t("healthChipNotScoredTitle")}</TooltipContent>
      </Tooltip>
    );
  }

  const score = displayScore(health.score);
  const tone = healthTone(health.status);
  const statusLabel =
    health.status === "HEALTHY"
      ? t("healthChipHealthy")
      : health.status === "AT_RISK"
        ? t("healthChipAtRisk")
        : health.status === "DEGRADED"
          ? t("healthChipDegraded")
          : String(health.status ?? "");
  const when = health.lastSweptAt ? new Date(health.lastSweptAt).toLocaleString() : t("healthChipNever");
  const title = t("healthChipTitle", {
    score: score ?? "-",
    issues: health.openIssueCount ?? 0,
    suggestions: health.pendingSuggestionCount ?? 0,
    when,
  });

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span
          className="state-pill"
          data-testid="agent-health-chip"
          // Keyboard-reachable tooltip trigger (the explanation is not hover-only).
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          tabIndex={0}
          style={TONE_VAR[tone] ? ({ ["--state" as string]: TONE_VAR[tone] } as React.CSSProperties) : undefined}
        >
          <HeartPulse size={11} aria-hidden="true" />
          {statusLabel}
          {score !== null ? ` · ${score}` : ""}
        </span>
      </TooltipTrigger>
      <TooltipContent>{title}</TooltipContent>
    </Tooltip>
  );
}
