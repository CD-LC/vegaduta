"use client";

import { Clock, Sparkles, Wrench } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { isBillingEnabled } from "../../../lib/billing-flag";
import { Badge } from "../../ui/badge";
import { Button } from "../../ui/button";
import { Separator } from "../../ui/separator";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";
import PoweredByChip, { inferSource, providerDisplayName } from "../../PoweredByChip";
import { edgeFallbackLabel, edgeFallbackShortLabel } from "../../../lib/edge/capabilities";
import { formatUsageCost } from "./format";
import type { PlaygroundTurn } from "./types";

/**
 * The per-reply metadata strip: latency, tool-call count, real token/cost
 * usage, which engine answered (on-device tier or the hosted provider/model),
 * the fallback/downgrade provenance badges, and the explicit "get a smarter
 * answer" escalation for on-device replies. Only what has a real source is
 * shown (never a fabricated number).
 */
export function TurnMetricsRow({
  turn,
  index,
  turns,
  sending,
  onResendHosted,
}: {
  turn: PlaygroundTurn;
  index: number;
  turns: PlaygroundTurn[];
  sending: boolean;
  /** Re-ask the exact same user message through the hosted path. */
  onResendHosted: (userMessage: string) => void;
}) {
    const tHC = useTranslations("hcComponentsAgentsPlaygroundTurnMetricsRow");
  const t = useTranslations("agentPlayground");
  const i = index;
  return (
    <>
                    {turn.metrics && (
                      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                        {/* Quiet metadata pair (latency + tool calls) - detail
                          lives in the tooltip so the row itself stays a calm,
                          single-height strip instead of the cramped glyph soup
                          this used to be (user report 2026-08-07). */}
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="inline-flex cursor-default items-center gap-1">
                              <Clock size={12} aria-hidden="true" />
                              <span className="tabular-nums">{turn.metrics.elapsedMs}ms</span>
                            </span>
                          </TooltipTrigger>
                          <TooltipContent>
                            {tHC("hcComponentsAgentsPlaygroundTurnMetricsRow.s5")}
                          </TooltipContent>
                        </Tooltip>
                        <Separator orientation="vertical" className="data-[orientation=vertical]:h-3" />
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="inline-flex cursor-default items-center gap-1">
                              <Wrench size={12} aria-hidden="true" />
                              <span className="tabular-nums">{turn.metrics.toolCalls}</span>
                              <span>{turn.metrics.toolCalls === 1 ? t("toolCallSingular") : t("toolCallPlural")}</span>
                            </span>
                          </TooltipTrigger>
                          <TooltipContent>
                            {tHC("hcComponentsAgentsPlaygroundTurnMetricsRow.s4")}
                          </TooltipContent>
                        </Tooltip>
                        {/* Per-turn LLM usage (real rows off the session's
                          llm_call_log via AgentSessionLlmUsageController) -
                          only rendered when the read succeeded AND the turn
                          recorded calls; a multi-call reasoning depth sums
                          all of them, so a RIGOROUS turn honestly shows the
                          several calls it made. */}
                        {turn.metrics.totalTokens != null && (
                          <>
                            <Separator orientation="vertical" className="data-[orientation=vertical]:h-3" />
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <span className="inline-flex cursor-default items-center gap-1">
                                  <Sparkles size={12} aria-hidden="true" />
                                  <span className="tabular-nums">
                                    {turn.metrics.totalTokens.toLocaleString()}{" "}
                                    {turn.metrics.totalTokens === 1 ? t("tokenSingular") : t("tokenPlural")}
                                    {isBillingEnabled() &&
                                    turn.metrics.estimatedCostUsd != null &&
                                    turn.metrics.estimatedCostUsd > 0
                                      ? ` · ${formatUsageCost(turn.metrics.estimatedCostUsd)}`
                                      : ""}
                                  </span>
                                </span>
                              </TooltipTrigger>
                              <TooltipContent>{t("usageTooltip")}</TooltipContent>
                            </Tooltip>
                          </>
                        )}
                        {/* Honest provenance marker on turns the
                          local model answered (edgeTier is only ever set by
                          sendViaEdge). 2026-08-05 ("clearly communicate to
                          consumers - tier 0 or tier 1, and what it can do"):
                          tier 0 (Chrome's built-in model) and tier 1 (a
                          downloaded WebLLM model) have a real capability
                          difference - tier 0 is chat-only and can NEVER use
                          tools, tier 1 can - so the badge names which one
                          answered and its title states the capability
                          difference plainly, rather than one generic
                          "on-device" label that hides it. */}
                        {turn.metrics.edgeTier != null && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge
                                variant="secondary"
                                className="cursor-default px-2 py-0 text-[11px] uppercase tracking-wide text-muted-foreground"
                              >
                                {turn.metrics.edgeTier === 0 ? t("edgeTier0Badge") : t("edgeTier1Badge")}
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>
                              {turn.metrics.edgeTier === 0 ? t("edgeTier0BadgeTitle") : t("edgeTier1BadgeTitle")}
                              {turn.metrics.edgeModel ? ` — ${turn.metrics.edgeModel}` : ""}
                            </TooltipContent>
                          </Tooltip>
                        )}
                        {/* Live user report (2026-08-07): the on-device model
                          picker showed "most capable" as ready/running, but
                          this exact turn's badge above still read "Chrome
                          built-in" with zero explanation - a silent in-tier
                          downgrade (tier 1 failed, tier 0 answered instead)
                          that tierRouter.ts's RouteTurnResult had no field
                          for until now. This surfaces WHY, right next to the
                          badge that was confusingly wrong on its own. */}
                        {turn.metrics.edgeTier === 0 && turn.metrics.edgeDowngradeDetail && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="state" state="var(--state-warning)" className="cursor-default">
                                {tHC("hcComponentsAgentsPlaygroundTurnMetricsRow.s3")}
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>
                              {tHC("hcComponentsAgentsPlaygroundTurnMetricsRow.s2")} {turn.metrics.edgeDowngradeDetail}
                            </TooltipContent>
                          </Tooltip>
                        )}
                        {/* Direct fix for "even after selecting edge still uses
                          platform llm" - previously a hosted fallback while
                          on-device was selected gave zero indication why. */}
                        {/* "customers should know what is powering" - for a
                          hosted turn (including one that fell back from
                          edge), name the provider/model the llm_call_log
                          actually recorded this turn burning, straight from
                          the per-turn usage delta. Edge turns carry their
                          own badge + edgeModel instead. */}
                        {turn.metrics.edgeTier == null &&
                          turn.metrics.poweredBy &&
                          turn.metrics.poweredBy.length > 0 && (
                            <PoweredByChip
                              source={inferSource(turn.metrics.poweredBy[0].providerName)}
                              provider={turn.metrics.poweredBy[0].providerName}
                              model={turn.metrics.poweredBy[0].model}
                              title={
                                turn.metrics.poweredBy.length > 1
                                  ? `This turn called ${turn.metrics.poweredBy.length} engines: ` +
                                    turn.metrics.poweredBy
                                      .map((e) =>
                                        [providerDisplayName(e.providerName), e.model].filter(Boolean).join(" · ")
                                      )
                                      .join(" + ")
                                  : undefined
                              }
                            />
                          )}
                        {turn.metrics.edgeTier == null && turn.metrics.edgeFallbackReason && (
                          /* Short reason stays always-visible on the badge itself
                           (edgeFallbackShortLabel); the full sentence
                           (edgeFallbackLabel) moves from a raw title= to the
                           styled tooltip. */
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="state" state="var(--state-warning)" className="cursor-default">
                                {tHC("hcComponentsAgentsPlaygroundTurnMetricsRow.s1")} {edgeFallbackShortLabel(turn.metrics.edgeFallbackReason)}
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>{edgeFallbackLabel(turn.metrics.edgeFallbackReason)}</TooltipContent>
                          </Tooltip>
                        )}
                        {/* On-device replies come from a much smaller model than
                          the hosted pool - it can genuinely fumble (refuse, go
                          blank, misread intent) where a real provider wouldn't.
                          Every on-device reply gets this one-click, EXPLICIT
                          escalation (never automatic - that would silently
                          break the "stays on your device" promise this mode is
                          built on) that re-asks the exact same message through
                          the unchanged hosted path, byte-for-byte the same
                          sendServerTurn() the visionFallback offer above uses. */}
                        {turn.metrics.edgeTier != null && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                disabled={sending}
                                onClick={() => {
                                  const priorUserTurn = turns[i - 1];
                                  const userMessage =
                                    priorUserTurn && priorUserTurn.role === "user" ? priorUserTurn.content : null;
                                  if (userMessage) onResendHosted(userMessage);
                                }}
                                className="h-6 gap-1 px-2 text-xs font-medium text-primary hover:text-primary"
                              >
                                <Sparkles size={12} aria-hidden="true" className="size-3" />
                                {t("getSmarterAnswer")}
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>{t("getSmarterAnswerTitle")}</TooltipContent>
                          </Tooltip>
                        )}
                      </div>
                    )}
    </>
  );
}
