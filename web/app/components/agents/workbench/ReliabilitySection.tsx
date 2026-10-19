"use client";

import { Activity, AlertTriangle, CheckCircle2, Clock, Cpu, TrendingDown, TrendingUp } from "lucide-react";
import { getRecentEdgeTurns } from "../../../lib/edge/telemetry";
import { cn } from "../../../lib/utils";
import { Button } from "../../ui/button";
import { SUB_TEXT } from "../AgentWorkbench/styles";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function ReliabilitySection() {
  const { isDraft, t, reliability, failurePage, setFailurePage, selectedAgent } = useWorkbench();
  return (
    <>
      {/* Task #959 ("Agent performance: response time + reliability +
       * failure handling") - real per-agent reliability panel, backed
       * by GET /api/agents/{id}/reliability (AgentReliabilityService -
       * see its javadoc for exactly which existing tables this reads
       * from: llm_call_log for response time/success rate, tool_call_log
       * for tool reliability, and this agent's own #954-956 fallback
       * counters). Existing agents only - a draft has no id to query. */}
      {isDraft ? (
        <WorkbenchSection.SavedOnly
          icon={<Activity size={13} />}
          label={t("reliabilityLabel")}
          cat="var(--cat-analysis)"
          what="reliability metrics appear once the agent has run."
        />
      ) : (
        <WorkbenchSection icon={<Activity size={13} />} label={t("reliabilityLabel")} cat="var(--cat-analysis)">
          {/* Loading is a shaped skeleton of the three tiles it is about
                  to become, rather than a line of grey text — so the panel
                  doesn't jump height and the wait reads as "loading this",
                  not "nothing here". */}
          {(reliability === undefined || reliability === "loading") && (
            <div className="grid grid-cols-3 gap-3" aria-busy="true" aria-label={t("reliabilityLoading")}>
              {[0, 1, 2].map((i) => (
                <div key={i} className="elev-sunken rounded-lg p-3">
                  <div className="h-3 w-2/3 animate-pulse rounded bg-muted" />
                  <div className="mt-2 h-5 w-1/2 animate-pulse rounded bg-muted" />
                </div>
              ))}
            </div>
          )}
          {reliability === "error" && <p className={SUB_TEXT}>{t("reliabilityLoadError")}</p>}
          {reliability && reliability !== "loading" && reliability !== "error" && reliability.totalCalls === 0 && (
            <div className="elev-sunken flex items-center gap-2.5 rounded-lg px-3 py-4">
              <span className="cat-chip cat-chip-sm" style={{ ["--cat" as any]: "var(--cat-analysis)" } as any}>
                <Activity size={12} />
              </span>
              <span className={SUB_TEXT}>{t("reliabilityNoDataYet")}</span>
            </div>
          )}
          {reliability && reliability !== "loading" && reliability !== "error" && reliability.totalCalls > 0 && (
            <>
              {/* Stat tiles. Same three facts as before, but the
                      good/warn/bad ramp is now the shared --state-* scale (so
                      "degraded" here is literally the same hue as a degraded
                      agent in the switcher), the tone is carried by an icon as
                      well as colour, and the success rate gets a .state-meter
                      so the number is also readable as a length — which
                      survives greyscale and colour-vision deficiency. */}
              <div className="grid grid-cols-3 gap-3">
                <div
                  className="elev-sunken rounded-lg p-3"
                  style={{ ["--state" as any]: "var(--state-running)" } as any}
                >
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <Clock size={12} style={{ color: "var(--state)" }} />
                    {t("avgResponseTimeStat")}
                  </div>
                  <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                    {reliability.avgResponseTimeMs != null ? (
                      t("msUnit", { value: reliability.avgResponseTimeMs.toLocaleString() })
                    ) : (
                      <span className="text-sm font-normal text-muted-foreground">{t("noTimingYet")}</span>
                    )}
                  </div>
                </div>
                {(() => {
                  const sr = reliability.successRatePercent;
                  const tone = sr == null ? "muted" : sr >= 90 ? "good" : sr >= 70 ? "warn" : "bad";
                  const stateToken =
                    tone === "good"
                      ? "var(--state-success)"
                      : tone === "warn"
                        ? "var(--state-warning)"
                        : tone === "bad"
                          ? "var(--state-error)"
                          : "var(--state-idle)";
                  const Icon = tone === "bad" ? TrendingDown : TrendingUp;
                  return (
                    <div
                      className="rounded-lg border p-3"
                      style={
                        {
                          ["--state" as any]: stateToken,
                          background: "color-mix(in srgb, var(--state) 8%, transparent)",
                          borderColor: "color-mix(in srgb, var(--state) 26%, transparent)",
                        } as any
                      }
                    >
                      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <Icon size={12} style={{ color: "var(--state)" }} />
                        {t("successRateStat")}
                      </div>
                      <div
                        className="mt-1 text-lg font-semibold tabular-nums"
                        style={{ color: tone === "muted" ? undefined : "var(--state)" }}
                      >
                        {sr != null ? (
                          `${sr}%`
                        ) : (
                          <span className="text-sm font-normal text-muted-foreground">{t("noTimingYet")}</span>
                        )}
                      </div>
                      {sr != null && (
                        <div className="state-meter mt-2">
                          <span style={{ width: `${Math.max(0, Math.min(100, sr))}%` }} />
                        </div>
                      )}
                    </div>
                  );
                })()}
                <div
                  className="elev-sunken rounded-lg p-3"
                  style={{ ["--state" as any]: "var(--state-queued)" } as any}
                >
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <Activity size={12} style={{ color: "var(--state)" }} />
                    {t("totalCallsStat")}
                  </div>
                  <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                    {reliability.totalCalls.toLocaleString()}
                  </div>
                </div>
              </div>

              <p className={cn("mt-3", SUB_TEXT)}>
                {reliability.toolCallCount > 0
                  ? t("toolCallReliabilityLabel", {
                      success: reliability.toolCallCount - reliability.toolCallFailureCount,
                      total: reliability.toolCallCount,
                    })
                  : t("toolCallReliabilityNone")}
              </p>

              {reliability.fallbackTriggeredCount > 0 && (
                <p className={SUB_TEXT}>
                  {t("fallbackUsageLabel", {
                    count: reliability.fallbackTriggeredCount,
                    failed: reliability.fallbackFailedCount,
                  })}
                </p>
              )}

              <div className="mt-4 space-y-2">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("recentFailuresLabel")}
                </span>
                {reliability.recentFailures.length === 0 ? (
                  <p className={SUB_TEXT}>{t("noRecentFailures")}</p>
                ) : (
                  (() => {
                    // Paginate 10 per page, client-side. No pager for <=10.
                    const PER_PAGE = 10;
                    const total = reliability.recentFailures.length;
                    const pageCount = Math.ceil(total / PER_PAGE);
                    const page = Math.min(failurePage, pageCount - 1);
                    const start = page * PER_PAGE;
                    const shown = reliability.recentFailures.slice(start, start + PER_PAGE);
                    return (
                      <>
                        {shown.map((failure, idx) => (
                          <div
                            className="rounded-lg border border-destructive/25 bg-destructive/5 p-2.5"
                            key={`${failure.occurredAt}-${start + idx}`}
                          >
                            <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
                              <span className="flex items-center gap-1 text-destructive">
                                <AlertTriangle size={11} />
                                {failure.occurredAt ? new Date(failure.occurredAt).toLocaleString() : ""}
                              </span>
                              <span>
                                {failure.provider}
                                {failure.model ? ` · ${failure.model}` : ""}
                              </span>
                            </div>
                            <span
                              className="mt-1 block truncate text-xs text-foreground"
                              title={failure.errorMessage || ""}
                            >
                              {failure.errorMessage || failure.exceptionType || ""}
                            </span>
                          </div>
                        ))}
                        {pageCount > 1 && (
                          <div className="flex items-center justify-between gap-2 pt-1">
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              disabled={page <= 0}
                              onClick={() => setFailurePage(page - 1)}
                            >
                              {t("prevPage")}
                            </Button>
                            <span className="text-xs tabular-nums text-muted-foreground">
                              {page + 1} / {pageCount}
                            </span>
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              disabled={page >= pageCount - 1}
                              onClick={() => setFailurePage(page + 1)}
                            >
                              {t("nextPage")}
                            </Button>
                          </div>
                        )}
                      </>
                    );
                  })()
                )}
              </div>

              {/* Edge (on-device) turn details (2026-08-07, live user
               * request: "let it show edge details also"). Source:
               * getRecentEdgeTurns() - a client-side, THIS-BROWSER-
               * SESSION-ONLY ring buffer (telemetry.ts, capped at 50,
               * resets on reload) reportEdgeTurn() already populates
               * after every on-device turn; nothing new to fetch, just
               * surfacing data that already existed but had no UI yet
               * (its own header comment said as much: "just enough to
               * inspect recent behavior live... without one [a debug
               * panel]"). Deliberately NOT merged into the server-backed
               * tiles above - this is a different data source with a
               * different lifetime, and conflating "this session's
               * on-device turns" with "all-time server-recorded calls"
               * would misrepresent both. */}
              {(() => {
                const edgeTurns = getRecentEdgeTurns().filter((turn) => turn.agentId === selectedAgent.id);
                if (edgeTurns.length === 0) return null;
                const successCount = edgeTurns.filter((t) => t.outcome === "success").length;
                const fallbackCount = edgeTurns.filter((t) => t.outcome === "fallback").length;
                const errorCount = edgeTurns.filter((t) => t.outcome === "error").length;
                const latencies = edgeTurns
                  .filter((t) => t.outcome === "success" && typeof t.latencyMs === "number")
                  .map((t) => t.latencyMs as number);
                const avgLatency =
                  latencies.length > 0 ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : null;
                return (
                  <div className="mt-4 space-y-2">
                    <span className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                      <Cpu size={11} />
                      {t("edgeDetailsLabel")}
                    </span>
                    <p className={SUB_TEXT}>{t("edgeDetailsScopeNote")}</p>
                    <div className="grid grid-cols-3 gap-3">
                      <div className="elev-sunken rounded-lg p-3">
                        <div className="text-xs text-muted-foreground">{t("edgeSuccessStat")}</div>
                        <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                          {successCount} / {edgeTurns.length}
                        </div>
                      </div>
                      <div className="elev-sunken rounded-lg p-3">
                        <div className="text-xs text-muted-foreground">{t("edgeFallbackStat")}</div>
                        <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">{fallbackCount}</div>
                      </div>
                      <div className="elev-sunken rounded-lg p-3">
                        <div className="text-xs text-muted-foreground">{t("edgeAvgLatencyStat")}</div>
                        <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                          {avgLatency != null ? (
                            t("msUnit", { value: avgLatency.toLocaleString() })
                          ) : (
                            <span className="text-sm font-normal text-muted-foreground">{t("noTimingYet")}</span>
                          )}
                        </div>
                      </div>
                    </div>
                    {errorCount > 0 && <p className={SUB_TEXT}>{t("edgeErrorCountNote", { count: errorCount })}</p>}
                    <div className="space-y-1">
                      {edgeTurns
                        .slice(-5)
                        .reverse()
                        .map((turn, idx) => (
                          <div
                            key={`${turn.recordedAt}-${idx}`}
                            className="flex items-center justify-between gap-2 rounded-md border border-border/60 px-2 py-1 text-xs text-muted-foreground"
                          >
                            <span className="flex items-center gap-1.5">
                              {turn.outcome === "success" ? (
                                <CheckCircle2 size={11} className="text-[var(--state-success)]" />
                              ) : (
                                <AlertTriangle size={11} className="text-[var(--state-warning)]" />
                              )}
                              {t("edgeTierLabel", { tier: turn.tier })}
                              {turn.modelId ? ` · ${turn.modelId}` : ""}
                            </span>
                            <span>
                              {turn.outcome !== "success" && turn.reason
                                ? turn.reason
                                : new Date(turn.recordedAt).toLocaleTimeString()}
                            </span>
                          </div>
                        ))}
                    </div>
                  </div>
                );
              })()}
            </>
          )}
        </WorkbenchSection>
      )}
    </>
  );
}
