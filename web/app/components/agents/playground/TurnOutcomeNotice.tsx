"use client";

import Link from "next/link";
import { OctagonAlert, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { canEditAgent } from "../agentAccess";
import { FOCUS_MAX_TOKENS, agentBehaviorHref } from "./runSettingsModel";
import type { TurnOutcome } from "./turnOutcome";
import type { PlaygroundAgent } from "./types";

/**
 * One always-visible line under a reply that did not end normally. A reply cut off by the output limit offers
 * "Continue", which sends a continuation message into the same conversation; a reply the provider's safety
 * system stopped, an unusable tool call and an empty reply say so in plain language instead of leaving a
 * silent or odd-looking answer.
 */
export function TurnOutcomeNotice({
  outcome,
  agent,
  canContinue,
  onContinue,
}: {
  outcome: TurnOutcome;
  agent: PlaygroundAgent | null;
  /** False while a turn is already running: Continue would race it. */
  canContinue: boolean;
  onContinue: () => void;
}) {
  const t = useTranslations("agentPlayground");
  const Icon = outcome.kind === "truncated" ? TriangleAlert : OctagonAlert;
  const editable = !!agent && canEditAgent(agent);
  return (
    <div
      role="status"
      data-testid={`turn-outcome-${outcome.kind}`}
      className="flex w-full max-w-[85%] flex-wrap items-start gap-x-2 gap-y-1.5 rounded-md border border-border bg-secondary/40 px-2.5 py-1.5 text-xs text-foreground"
    >
      <Icon size={12} className="mt-0.5 size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        {outcome.kind === "truncated" && t("outcomeTruncated")}
        {outcome.kind === "refused" &&
          (outcome.reason ? t("outcomeRefusedReason", { reason: outcome.reason }) : t("outcomeRefused"))}
        {outcome.kind === "malformedToolCall" && t("outcomeMalformedToolCall")}
        {outcome.kind === "empty" && t("outcomeEmpty")}
      </span>
      {outcome.kind === "truncated" && (
        <span className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-6 px-2 text-xs"
            disabled={!canContinue}
            onClick={onContinue}
            data-testid="outcome-continue"
          >
            {t("outcomeContinue")}
          </Button>
          {editable && agent && (
            <Link
              href={agentBehaviorHref(agent.id, FOCUS_MAX_TOKENS)}
              className="font-medium text-primary underline underline-offset-2 hover:no-underline"
            >
              {t("outcomeLongerReplies")}
            </Link>
          )}
        </span>
      )}
    </div>
  );
}
