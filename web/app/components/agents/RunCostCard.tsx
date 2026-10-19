"use client";

import { useEffect, useRef, useState } from "react";
import { Coins } from "lucide-react";
import { api } from "../../lib/api";
import type { RunCostStep, RunCostSummary } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";
import { cn } from "../../lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import BrandText from "../BrandText";

/**
 * "Run cost" disclosure for a chat surface: what this conversation has cost
 * so far, read from GET /api/agents/{id}/sessions/{sessionId}/llm-usage/summary
 * (RunCostTimeline in core/chat - built 2026-10-02 with no console consumer).
 *
 * Honesty rules, same as the playground's per-turn metrics row:
 *  - the figure is an ESTIMATE from the platform's price table, labelled so;
 *  - calls with no known price are counted and called out, because the total
 *    is then a floor rather than the real spend;
 *  - nothing is ever fabricated: no session, no calls, an older core (404) or
 *    a failed read all render nothing at all.
 *
 * The endpoint is PREMIUM-gated. On a FREE plan (403) the card renders
 * nothing, except where the host opts into a single muted line via
 * `showPlanNote` (the workbench playground does; the main chat does not).
 */

function toNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * Same tiers as AgentPlayground's per-turn figure: a single turn is usually a
 * fraction of a cent, so one fixed precision would round it to a misleading
 * zero. Null/unknown renders as an em dash, never as a zero amount.
 */
export function formatRunCost(value: unknown): string {
  const usd = toNumber(value);
  if (usd === null) return "—";
  if (usd >= 1) return `$${usd.toFixed(2)}`;
  if (usd >= 0.01) return `$${usd.toFixed(3)}`;
  if (usd === 0) return `$${usd}`;
  return `$${usd.toFixed(5)}`;
}

/** The last `limit` steps, oldest first - a long session's popover stays short. */
export function recentSteps(steps: readonly RunCostStep[] | null | undefined, limit: number): RunCostStep[] {
  if (!Array.isArray(steps) || limit <= 0) return [];
  return steps.slice(-limit);
}

const MAX_STEPS_SHOWN = 12;

type Loaded =
  | { scope: string; state: "ok"; summary: RunCostSummary }
  | { scope: string; state: "plan" }
  | { scope: string; state: "none" };

export default function RunCostCard({
  agentId,
  sessionId,
  turnInFlight = false,
  showPlanNote = false,
  className,
}: {
  agentId: string | null | undefined;
  sessionId: string | null | undefined;
  /** True while a chat turn is streaming; the summary is re-read when it ends. */
  turnInFlight?: boolean;
  /** Show one muted "Premium feature" line on a FREE plan instead of nothing. */
  showPlanNote?: boolean;
  className?: string;
}) {
  const t = useTranslations("runCost");
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  // Bumped on the falling edge of turnInFlight so the effect below re-reads.
  const [turnTick, setTurnTick] = useState(0);
  const wasInFlightRef = useRef(turnInFlight);
  useEffect(() => {
    const was = wasInFlightRef.current;
    wasInFlightRef.current = turnInFlight;
    if (was && !turnInFlight) setTurnTick((n) => n + 1);
  }, [turnInFlight]);

  const scope = agentId && sessionId ? `${agentId}::${sessionId}` : "";

  useEffect(() => {
    if (!agentId || !sessionId) {
      setLoaded(null);
      return undefined;
    }
    let cancelled = false;
    const requestScope = `${agentId}::${sessionId}`;
    api
      .getSessionLlmUsageSummary(agentId, sessionId)
      .then((summary: RunCostSummary) => {
        if (cancelled) return;
        setLoaded(
          summary && typeof summary === "object" && toNumber(summary.callCount)
            ? { scope: requestScope, state: "ok", summary }
            : { scope: requestScope, state: "none" }
        );
      })
      .catch((err: any) => {
        if (cancelled) return;
        // 403 = FREE plan (an entitlement state, not a failure). Anything
        // else - 404 on an older core, a transient error - is simply hidden.
        setLoaded({ scope: requestScope, state: err?.status === 403 ? "plan" : "none" });
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, sessionId, turnTick]);

  // Never show one conversation's numbers under another.
  const current = loaded && loaded.scope === scope ? loaded : null;
  if (!scope || !current || current.state === "none") return null;

  if (current.state === "plan") {
    if (!showPlanNote) return null;
    return (
      <span className={cn("truncate text-[11px] text-muted-foreground", className)} data-testid="run-cost-plan-note">
        {t("premiumNote")}
      </span>
    );
  }

  const { summary } = current;
  // Self-hosted/personal installs have no price table that means anything
  // (billing off => NEXT_PUBLIC_BILLING_ENABLED unset): the card stays, but
  // every $ figure becomes the token count it was computed from.
  const billing = isBillingEnabled();
  const steps = recentSteps(summary.steps, MAX_STEPS_SHOWN);
  const hiddenSteps = (Array.isArray(summary.steps) ? summary.steps.length : 0) - steps.length;
  const unpriced = toNumber(summary.unpricedCalls) ?? 0;
  const failed = toNumber(summary.failedCalls) ?? 0;
  const total = formatRunCost(summary.totalCostUsd);
  const totalTokens = (toNumber(summary.totalTokens) ?? 0).toLocaleString();
  const stepCell = (step: RunCostStep): string =>
    billing
      ? formatRunCost(step.cumulativeCostUsd)
      : `${((toNumber(step.promptTokens) ?? 0) + (toNumber(step.completionTokens) ?? 0)).toLocaleString()} tok`;

  return (
    <Popover>
      <PopoverTrigger
        data-testid="run-cost-trigger"
        className={cn(
          "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground",
          className
        )}
        aria-label={billing ? t("triggerLabel", { cost: total }) : t("triggerTokensLabel", { tokens: totalTokens })}
      >
        <Coins size={12} className="size-3 shrink-0" aria-hidden="true" />
        <span>{billing ? t("trigger", { cost: total }) : t("triggerTokens", { tokens: totalTokens })}</span>
      </PopoverTrigger>
      <PopoverContent className="p-3 text-sm" data-testid="run-cost-panel">
        <div className="flex items-baseline justify-between gap-2">
          <span className="font-medium text-foreground"><BrandText text={billing ? t("title") : t("titleUsage")} /></span>
          <span className="font-mono text-foreground" data-testid="run-cost-total">
            {billing ? total : `${totalTokens} tok`}
          </span>
        </div>
        {billing && <p className="mt-0.5 text-xs text-muted-foreground">{t("estimateNote")}</p>}

        <dl className="mt-2 grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-0.5 text-xs">
          <dt className="text-muted-foreground">{t("calls")}</dt>
          <dd className="text-right font-mono text-foreground" data-testid="run-cost-calls">
            {(toNumber(summary.callCount) ?? 0).toLocaleString()}
          </dd>
          <dt className="text-muted-foreground">{t("tokens")}</dt>
          <dd className="text-right font-mono text-foreground" data-testid="run-cost-tokens">
            {(toNumber(summary.totalTokens) ?? 0).toLocaleString()}
          </dd>
          {(toNumber(summary.reasoningTokens) ?? 0) > 0 && (
            <>
              <dt className="text-muted-foreground" title={t("reasoningTokensHint")}>
                {t("reasoningTokens")}
              </dt>
              <dd className="text-right font-mono text-foreground" data-testid="run-cost-reasoning">
                {(toNumber(summary.reasoningTokens) ?? 0).toLocaleString()}
              </dd>
            </>
          )}
          {failed > 0 && (
            <>
              <dt className="text-muted-foreground">{t("failedCalls")}</dt>
              <dd className="text-right font-mono text-foreground">{failed.toLocaleString()}</dd>
            </>
          )}
        </dl>

        {billing && unpriced > 0 && (
          <p className="mt-2 text-xs text-muted-foreground" data-testid="run-cost-unpriced">
            {unpriced === 1 ? t("unpricedOne") : t("unpricedMany", { count: unpriced })}
          </p>
        )}

        {steps.length > 0 && (
          <div className="mt-2 border-t border-border pt-2">
            <div className="mb-1 text-xs text-muted-foreground">{t("stepsTitle")}</div>
            {hiddenSteps > 0 && (
              <div className="mb-1 text-[11px] text-muted-foreground">{t("earlierSteps", { count: hiddenSteps })}</div>
            )}
            <ol className="max-h-48 space-y-0.5 overflow-auto">
              {steps.map((step) => (
                <li
                  key={step.id ?? step.index}
                  data-testid="run-cost-step"
                  className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-baseline gap-x-2 text-[11px]"
                >
                  <span className="font-mono text-muted-foreground">{(toNumber(step.index) ?? 0) + 1}</span>
                  <span className="truncate text-muted-foreground" title={step.model || undefined}>
                    {step.model || t("unknownModel")}
                    {step.success === false ? ` · ${t("stepFailed")}` : ""}
                  </span>
                  <span className="font-mono text-foreground">{stepCell(step)}</span>
                </li>
              ))}
            </ol>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}
