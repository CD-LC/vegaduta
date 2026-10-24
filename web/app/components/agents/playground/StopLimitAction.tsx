"use client";

import Link from "next/link";
import { useTranslations } from "../../../lib/i18n";
import type { StopReasonKind } from "../AgentActivityTrace";
import { canEditAgent } from "../agentAccess";
import { FOCUS_MAX_TOOL_STEPS, FOCUS_RUN_BUDGET, agentBehaviorHref } from "./runSettingsModel";
import type { PlaygroundAgent } from "./types";

/**
 * What to do after a run was stopped by a limit (too many tool calls, the spending cap): someone who can edit
 * the agent gets a link straight to that setting on the Behavior tab; anyone else is told who to ask. Renders
 * nothing without an agent.
 */
export function StopLimitAction({ agent, kind }: { agent: PlaygroundAgent | null; kind: StopReasonKind }) {
  const t = useTranslations("agentPlayground");
  if (!agent) return null;
  if (!canEditAgent(agent)) {
    return (
      <span data-testid="stop-limit-ask" className="text-muted-foreground">
        {t("stopAskEditor")}
      </span>
    );
  }
  return (
    <Link
      href={agentBehaviorHref(agent.id, kind === "run_budget" ? FOCUS_RUN_BUDGET : FOCUS_MAX_TOOL_STEPS)}
      data-testid="stop-limit-raise"
      className="font-medium text-primary underline underline-offset-2 hover:no-underline"
    >
      {kind === "run_budget" ? t("stopRaiseBudget") : t("stopRaiseSteps")}
    </Link>
  );
}
