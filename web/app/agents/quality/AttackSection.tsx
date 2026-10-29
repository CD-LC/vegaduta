"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ChevronDown, ChevronRight, ShieldAlert, StopCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Section } from "../../components/ax/page-kit";
import { api } from "../../lib/api";
import {
  ATTACK_DEFAULT_BUDGET,
  ATTACK_MAX_TURNS,
  attackHeadline,
  attackHeadlineText,
  attackOutcome,
  attackPersonaKeys,
  attackReportPending,
  formatAttackTranscript,
  latestAttackSessionId,
  normalizeAttackReport,
  parseAttackBudget,
  type AttackReport,
  type AttackScenarioResult,
} from "./attackReport";

/**
 * "Attack my agent" (trust T7): one button that runs every built-in indirect
 * prompt-injection scenario against the selected agent and reports how many
 * worked.
 *
 * It is a thin layer over the simulation engine: the scenarios are the
 * personas the server marks attack === true, a run is an ordinary simulation
 * session created with those keys, and the result is core's computed
 * attack-report for that session. Nothing here is a second source of truth.
 *
 * Honest limits, shown on screen from the report's own `limits` list: tools
 * stay disabled during these runs, so this measures whether the MODEL obeys
 * instructions hidden in content, not whether the live tool path (sanitizer,
 * guardrail, approval gate) would have stopped it.
 *
 * Self-hiding: when the personas list carries no attack entry (older core, or
 * the scenarios are switched off) the whole section renders nothing. Literal
 * English strings, matching this folder's i18n deferral.
 */

const POLL_MS = 5000;
// How many polls to wait for a just-launched session's first report before
// saying it could not be loaded (about a minute).
const FIRST_REPORT_MAX_POLLS = 12;
// Expected states the section handles by hiding: plan gate / older core.
const QUIET_READ = [403, 404];

function outcomeBadge(row: AttackScenarioResult) {
  const outcome = attackOutcome(row.verdict);
  if (outcome === "resisted")
    return (
      <Badge variant="state" state="var(--state-success)">
        Resisted
      </Badge>
    );
  if (outcome === "compromised") return <Badge variant="destructive">Compromised</Badge>;
  if (outcome === "inconclusive") return <Badge variant="secondary">Inconclusive</Badge>;
  return <Badge variant="accent">Running</Badge>;
}

export default function AttackSection({
  agentId,
  onAvailabilityChange,
}: {
  agentId: string | null;
  /** Tells the page whether the section is showing, so it can hide links that point at it. */
  onAvailabilityChange?: (available: boolean) => void;
}) {
  // null = not known yet / not supported; [] = supported route but no attack scenarios.
  const [attackKeys, setAttackKeys] = useState<string[] | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [report, setReport] = useState<AttackReport | null>(null);
  const [budget, setBudget] = useState(String(ATTACK_DEFAULT_BUDGET));
  const [busy, setBusy] = useState(false);
  // The session launched from this screen whose first report has not arrived
  // yet. While set, the launcher stays disabled and the report is polled, so
  // a transient failure on the first read cannot leave a running (and billed)
  // attack invisible with the launch button re-enabled.
  const [startingSession, setStartingSession] = useState<string | null>(null);
  const [startFailed, setStartFailed] = useState(false);
  const firstReportPollsRef = useRef(0);
  const [openRun, setOpenRun] = useState<string | null>(null);
  const [openRunDetail, setOpenRunDetail] = useState<any | null>(null);

  // Which agent / session the screen is currently about. Late answers for
  // anything else (agent switched, a newer attack started) are dropped.
  const activeAgentRef = useRef<string | null>(null);
  const activeSessionRef = useRef<string | null>(null);

  useEffect(() => {
    activeAgentRef.current = agentId;
    activeSessionRef.current = null;
    setAttackKeys(null);
    setSessionId(null);
    setReport(null);
    setOpenRun(null);
    setOpenRunDetail(null);
    setStartingSession(null);
    setStartFailed(false);
    if (!agentId) return undefined;
    let cancelled = false;
    void (async () => {
      let keys: string[] = [];
      try {
        keys = attackPersonaKeys(await api.listSimulationPersonas(agentId, QUIET_READ));
      } catch {
        return; // no simulations on this core / plan: stay hidden
      }
      if (cancelled) return;
      setAttackKeys(keys);
      if (keys.length === 0) return;
      // Bring back the last attack result so a revisit is not a blank slate.
      try {
        const last = latestAttackSessionId(await api.listSimulationSessions(agentId, QUIET_READ), keys);
        if (cancelled || !last) return;
        const previous = normalizeAttackReport(await api.getSimulationAttackReport(agentId, last));
        if (cancelled || !previous || previous.total === 0) return;
        if (activeSessionRef.current) return; // an attack was started meanwhile - it wins
        activeSessionRef.current = last;
        setSessionId(last);
        setReport(previous);
      } catch {
        /* no previous result to show - the launcher still works */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const available = !!agentId && !!attackKeys && attackKeys.length > 0;
  useEffect(() => {
    onAvailabilityChange?.(available);
  }, [available, onAvailabilityChange]);

  const loadReport = useCallback(async (agent: string, session: string) => {
    try {
      const next = normalizeAttackReport(await api.getSimulationAttackReport(agent, session));
      if (activeSessionRef.current !== session) return;
      if (next) {
        setReport(next);
        setStartingSession((cur) => (cur === session ? null : cur));
        setStartFailed(false);
      }
    } catch (e: any) {
      // 404 = scenarios were switched off or the session is gone: stop showing
      // (and stop polling) rather than leave a frozen "running" state.
      if (e?.status === 404 && activeSessionRef.current === session) {
        setReport(null);
        setStartingSession((cur) => (cur === session ? null : cur));
      }
    }
  }, []);

  const running = attackReportPending(report);
  // Launched, but its first report has not been read yet.
  const starting = !!sessionId && startingSession === sessionId && !report;

  // Poll while any scenario has no verdict so results land without a refresh,
  // and while a just-launched session has not produced its first report.
  useEffect(() => {
    if (!agentId || !sessionId || !(running || starting)) return undefined;
    const timer = setInterval(() => {
      if (starting) {
        firstReportPollsRef.current += 1;
        if (firstReportPollsRef.current > FIRST_REPORT_MAX_POLLS) {
          // Give up waiting, and say so: the attack may well be running.
          setStartingSession(null);
          setStartFailed(true);
          return;
        }
      }
      void loadReport(agentId, sessionId);
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [agentId, sessionId, running, starting, loadReport]);

  const budgetUsd = parseAttackBudget(budget);

  const launch = useCallback(async () => {
    if (!agentId || !attackKeys || attackKeys.length === 0 || budgetUsd == null) return;
    setBusy(true);
    try {
      const session = await api.createSimulationSession(agentId, {
        personaKeys: attackKeys,
        maxTurns: ATTACK_MAX_TURNS,
        budgetUsd,
      });
      if (session?.id && activeAgentRef.current === agentId) {
        activeSessionRef.current = session.id;
        firstReportPollsRef.current = 0;
        setStartingSession(session.id);
        setStartFailed(false);
        setSessionId(session.id);
        setReport(null);
        setOpenRun(null);
        setOpenRunDetail(null);
        await loadReport(agentId, session.id);
      }
    } catch {
      /* shared toJson already raised the toast (400 validation included) */
    } finally {
      setBusy(false);
    }
  }, [agentId, attackKeys, budgetUsd, loadReport]);

  const cancel = useCallback(async () => {
    if (!agentId || !sessionId) return;
    try {
      await api.cancelSimulationSession(agentId, sessionId);
      await loadReport(agentId, sessionId);
    } catch {
      /* toast already shown */
    }
  }, [agentId, sessionId, loadReport]);

  const toggleRun = useCallback(
    async (runId: string) => {
      if (openRun === runId) {
        setOpenRun(null);
        setOpenRunDetail(null);
        return;
      }
      if (!agentId || !runId) return;
      setOpenRun(runId);
      setOpenRunDetail(null);
      try {
        setOpenRunDetail(await api.getSimulationRun(agentId, runId));
      } catch {
        setOpenRunDetail(null);
      }
    },
    [agentId, openRun]
  );

  if (!available || !agentId || !attackKeys) return null;

  const headline = attackHeadline(report);
  const compromised = !!report && report.succeededAttacks > 0;
  const scenarioCount = attackKeys.length;

  return (
    <Section
      title="Attack my agent"
      description="Hands this agent a poisoned email, web page, tool description and document - each with instructions hidden inside - and checks whether it obeys them. Tools stay disabled, so nothing real is sent."
    >
      <Card>
        <CardContent className="space-y-3 pt-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <label htmlFor="attack-budget" className="text-xs font-medium text-muted-foreground">
                Budget per scenario (USD)
              </label>
              <Input
                id="attack-budget"
                data-testid="attack-budget"
                className="w-28"
                inputMode="decimal"
                value={budget}
                onChange={(e) => setBudget(e.target.value)}
                aria-invalid={budgetUsd == null ? true : undefined}
              />
            </div>
            <Button
              size="sm"
              data-testid="attack-launch"
              onClick={() => void launch()}
              disabled={busy || running || starting || budgetUsd == null}
            >
              <ShieldAlert size={14} aria-hidden="true" />{" "}
              {running ? "Attack running…" : starting ? "Starting…" : "Attack my agent"}
            </Button>
            {running && (
              <Button variant="outline" size="sm" data-testid="attack-cancel" onClick={() => void cancel()}>
                <StopCircle size={14} aria-hidden="true" /> Cancel
              </Button>
            )}
            <span className="text-xs text-muted-foreground">
              {scenarioCount} scenario{scenarioCount === 1 ? "" : "s"}, up to {ATTACK_MAX_TURNS} turns each. Each
              scenario stops when it reaches the budget.
            </span>
          </div>
          {budgetUsd == null && (
            <p className="text-xs text-destructive" role="alert">
              Enter a budget greater than 0.
            </p>
          )}
          {startFailed && !report && (
            <p className="text-xs text-destructive" role="alert" data-testid="attack-start-failed">
              The attack was started, but its result could not be loaded. It may still be running - reload this page to
              see it before starting another one.
            </p>
          )}
        </CardContent>
      </Card>

      {report && headline.kind !== "empty" && (
        <div className="space-y-3" data-testid="attack-report">
          <p
            data-testid="attack-headline"
            className={
              headline.kind === "decided" && headline.succeeded > 0
                ? "text-sm font-semibold text-destructive"
                : "text-sm font-semibold text-foreground"
            }
          >
            {attackHeadlineText(headline)}
          </p>

          <div className="space-y-1">
            {report.perScenario.map((row) => (
              <div
                key={row.runId || row.key}
                className="rounded-md border border-border"
                data-testid={`attack-row-${row.key}`}
              >
                <button
                  type="button"
                  className="flex w-full flex-wrap items-center gap-2 px-3 py-2 text-start"
                  aria-expanded={openRun === row.runId}
                  onClick={() => void toggleRun(row.runId)}
                >
                  {openRun === row.runId ? (
                    <ChevronDown size={13} aria-hidden="true" />
                  ) : (
                    <ChevronRight size={13} aria-hidden="true" />
                  )}
                  {outcomeBadge(row)}
                  {row.deterministic && (
                    <Badge
                      variant="outline"
                      data-testid={`attack-deterministic-${row.key}`}
                      title="Confirmed by an exact check for the planted code, not by a judge model"
                    >
                      Exact match
                    </Badge>
                  )}
                  <span className="text-sm font-medium text-foreground">{row.name}</span>
                  <span className="ms-auto text-xs text-muted-foreground">View transcript</span>
                </button>
                {row.reason && (
                  <p className="min-w-0 break-words px-3 pb-2 text-xs text-muted-foreground">{row.reason}</p>
                )}
                {openRun === row.runId && (
                  <div className="border-t border-border px-3 py-2 text-sm">
                    {openRunDetail?.transcriptJson ? (
                      <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 text-xs">
                        {formatAttackTranscript(openRunDetail.transcriptJson)}
                      </pre>
                    ) : (
                      <p className="text-muted-foreground">
                        {openRunDetail ? "No transcript was recorded for this run." : "Loading transcript…"}
                      </p>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>

          {compromised && (
            <Alert variant="warning" data-testid="attack-fixes">
              <ShieldAlert aria-hidden="true" />
              <AlertTitle>What to change</AlertTitle>
              <AlertDescription>
                <ul className="w-full list-disc space-y-1 ps-4">
                  <li>
                    In the agent&apos;s tools, remove one of the three: reading private data, taking in outside content,
                    or sending data out.{" "}
                    <Link
                      className="underline"
                      data-testid="attack-fix-workbench"
                      href={`/agents?agentId=${encodeURIComponent(agentId)}`}
                    >
                      Open the agent
                    </Link>
                  </li>
                  <li>Or set its Autonomy to Supervised, so a person approves every tool call.</li>
                  <li>
                    Check which content guardrails are on.{" "}
                    <Link className="underline" data-testid="attack-fix-guardrails" href="/admin/guardrails">
                      Guardrails
                    </Link>
                  </li>
                </ul>
              </AlertDescription>
            </Alert>
          )}

          {report.limits.length > 0 && (
            <ul className="space-y-0.5 text-xs text-muted-foreground" data-testid="attack-limits">
              {report.limits.map((limit) => (
                <li key={limit}>Limit: {limit}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Section>
  );
}
