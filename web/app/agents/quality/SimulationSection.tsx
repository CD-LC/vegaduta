"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, ChevronRight, Play, RefreshCw, StopCircle, Trash2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../components/ui/alert-dialog";
import { Badge } from "../../components/ui/badge";
import { Button, buttonVariants } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Section } from "../../components/ax/page-kit";
import { api } from "../../lib/api";

/**
 * Simulation testing (SimulationController, V266 + V344): launch a bounded
 * multi-turn conversation between built-in/custom adversarial personas and
 * the agent — with tools deliberately disabled server-side so no run can
 * produce real side effects. Each persona gets an isolated run; a separate
 * judge model scores the transcript (SAFE / REGRESSED style verdicts).
 *
 * V344 controls surfaced here: per-run budget cap (default USD 10), session
 * cancel (cooperative — runs end CANCELLED), the per-run `termination`
 * reason (GOAL_MET / MAX_TURNS / NO_PROGRESS / ...), and custom-persona
 * delete. Literal English strings, matching this folder's i18n deferral.
 */

interface Persona {
  key: string;
  name: string;
  description: string;
  objective: string;
  builtIn: boolean;
}

interface SimSession {
  id: string;
  agentId: string;
  personaKeys: string[];
  maxTurns: number;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "CANCELLED";
  budgetUsd: number | null;
  cancelRequested: boolean;
  createdBy: string | null;
  createdAt: string;
  completedAt: string | null;
}

interface SimRun {
  id: string;
  sessionId: string;
  personaKey: string;
  personaName: string;
  status: "QUEUED" | "RUNNING" | "PASSED" | "FAILED" | "ERROR" | "CANCELLED";
  termination: string | null;
  verdict: string | null;
  verdictReason: string | null;
  turnCount: number;
  costUsd: number | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

const LIVE = ["QUEUED", "RUNNING"];
const POLL_MS = 5000;

function verdictBadge(verdict: string | null) {
  if (!verdict) return null;
  if (verdict === "PASSED")
    return (
      <Badge variant="state" state="var(--state-success)">
        Passed
      </Badge>
    );
  if (verdict === "FAILED") return <Badge variant="destructive">Failed</Badge>;
  return <Badge variant="secondary">{verdict}</Badge>;
}

function statusBadge(status: string) {
  if (status === "RUNNING") return <Badge variant="accent">Running</Badge>;
  if (status === "QUEUED") return <Badge variant="secondary">Queued</Badge>;
  if (status === "CANCELLED") return <Badge variant="secondary">Cancelled</Badge>;
  if (status === "COMPLETED" || status === "PASSED")
    return <Badge variant="outline">{status === "COMPLETED" ? "Completed" : "Passed"}</Badge>;
  if (status === "FAILED") return <Badge variant="destructive">Failed</Badge>;
  return <Badge variant="destructive">Error</Badge>;
}

const TERMINATION_LABEL: Record<string, string> = {
  GOAL_MET: "persona finished its objective",
  GOAL_FAILED: "persona gave up on its objective",
  MAX_TURNS: "turn limit reached",
  AGENT_ERROR: "agent reply failed",
  SIMULATOR_ERROR: "persona produced nothing usable",
  SAFETY_TRIP: "safety trip",
  NO_PROGRESS: "no progress - conversation looping",
};

export default function SimulationSection({ agentId }: { agentId: string | null }) {
  const [personas, setPersonas] = useState<Persona[] | null>(null);
  const [sessions, setSessions] = useState<SimSession[]>([]);
  const [loadFailed, setLoadFailed] = useState(false);
  const [unsupported, setUnsupported] = useState(false); // older core without /simulations

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [maxTurns, setMaxTurns] = useState("6");
  const [budget, setBudget] = useState("10");
  const [busy, setBusy] = useState(false);

  const [expandedSession, setExpandedSession] = useState<string | null>(null);
  const [sessionRuns, setSessionRuns] = useState<Record<string, SimRun[]>>({});
  const [openRun, setOpenRun] = useState<string | null>(null);
  const [openRunDetail, setOpenRunDetail] = useState<any | null>(null);

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = useCallback(async () => {
    if (!agentId) return;
    try {
      const [p, s] = await Promise.all([api.listSimulationPersonas(agentId), api.listSimulationSessions(agentId)]);
      setPersonas(p || []);
      setSessions(s || []);
      setLoadFailed(false);
    } catch (e: any) {
      if (e?.status === 404) setUnsupported(true);
      else if (e?.status === 403)
        setUnsupported(true); // plan gate - hide whole surface
      else setLoadFailed(true);
    }
  }, [agentId]);

  useEffect(() => {
    setPersonas(null);
    setSessions([]);
    setExpandedSession(null);
    setSessionRuns({});
    setOpenRun(null);
    setOpenRunDetail(null);
    void refresh();
  }, [agentId, refresh]);

  const loadRuns = useCallback(
    async (sessionId: string) => {
      if (!agentId) return;
      try {
        const runs = await api.listSimulationRuns(agentId, sessionId);
        setSessionRuns((cur) => ({ ...cur, [sessionId]: runs || [] }));
      } catch {
        /* toast already shown */
      }
    },
    [agentId]
  );

  // Poll while anything is live so verdicts land without manual refresh.
  useEffect(() => {
    const liveSessions = sessions.filter((s) => LIVE.includes(s.status));
    const liveRuns = Object.values(sessionRuns)
      .flat()
      .some((r) => LIVE.includes(r.status));
    const shouldPoll = liveSessions.length > 0 || liveRuns;
    if (shouldPoll && !pollRef.current) {
      pollRef.current = setInterval(() => {
        void refresh();
        if (expandedSession) void loadRuns(expandedSession);
      }, POLL_MS);
    }
    if (!shouldPoll && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [sessions, sessionRuns, expandedSession, refresh, loadRuns]);

  const togglePersona = (key: string) => {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const launch = useCallback(async () => {
    if (!agentId || selected.size === 0) return;
    setBusy(true);
    try {
      const session = await api.createSimulationSession(agentId, {
        personaKeys: Array.from(selected),
        maxTurns: Math.max(1, Math.min(50, parseInt(maxTurns, 10) || 6)),
        ...(budget.trim() ? { budgetUsd: parseFloat(budget) } : {}),
      });
      setSelected(new Set());
      await refresh();
      if (session?.id) {
        setExpandedSession(session.id);
        await loadRuns(session.id);
      }
    } catch {
      /* shared toJson already raised the toast (400 validation included) */
    } finally {
      setBusy(false);
    }
  }, [agentId, selected, maxTurns, budget, refresh, loadRuns]);

  const cancelSession = useCallback(
    async (sessionId: string) => {
      if (!agentId) return;
      try {
        await api.cancelSimulationSession(agentId, sessionId);
        await refresh();
        await loadRuns(sessionId);
      } catch {
        /* toast already shown */
      }
    },
    [agentId, refresh, loadRuns]
  );

  const deletePersona = useCallback(
    async (key: string) => {
      if (!agentId) return;
      try {
        await api.deleteSimulationPersona(agentId, key);
        await refresh();
      } catch {
        /* toast already shown */
      }
    },
    [agentId, refresh]
  );

  const toggleSession = useCallback(
    async (sessionId: string) => {
      if (expandedSession === sessionId) {
        setExpandedSession(null);
        return;
      }
      setExpandedSession(sessionId);
      setOpenRun(null);
      setOpenRunDetail(null);
      await loadRuns(sessionId);
    },
    [expandedSession, loadRuns]
  );

  const toggleRun = useCallback(
    async (runId: string) => {
      if (openRun === runId) {
        setOpenRun(null);
        setOpenRunDetail(null);
        return;
      }
      if (!agentId) return;
      setOpenRun(runId);
      setOpenRunDetail(null);
      try {
        const detail = await api.getSimulationRun(agentId, runId);
        setOpenRunDetail(detail);
      } catch {
        setOpenRunDetail(null);
      }
    },
    [agentId, openRun]
  );

  if (!agentId || unsupported) return null; // core without /simulations, or FREE plan

  return (
    <Section
      title="Simulation testing"
      description="Adversarial personas hold a bounded conversation with this agent — tools disabled, so no real side effects — and a judge scores each transcript."
      actions={
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={refresh}>
            <RefreshCw size={14} aria-hidden="true" /> Refresh
          </Button>
        </div>
      }
    >
      {loadFailed && (
        <Alert variant="destructive">
          <AlertTitle>Couldn&apos;t load simulations</AlertTitle>
          <AlertDescription className="flex items-center gap-3">
            This is a load failure, not an empty list.
            <Button variant="outline" size="sm" onClick={refresh}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {/* Launcher */}
      <Card>
        <CardContent className="space-y-3 pt-4">
          {personas == null ? (
            <p className="text-sm text-muted-foreground">Loading personas…</p>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {personas.map((p) => (
                <label
                  key={p.key}
                  className="flex items-start gap-2 rounded-md border border-border p-2 text-sm cursor-pointer hover:bg-accent/40"
                  title={p.objective}
                >
                  <input
                    type="checkbox"
                    className="mt-1"
                    aria-label={p.name}
                    checked={selected.has(p.key)}
                    onChange={() => togglePersona(p.key)}
                  />
                  <span className="min-w-0">
                    <span className="font-medium text-foreground">{p.name}</span>
                    {p.builtIn && (
                      <Badge variant="secondary" className="ms-2">
                        built-in
                      </Badge>
                    )}
                    <span className="block truncate text-xs text-muted-foreground">{p.description}</span>
                  </span>
                  {!p.builtIn && (
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <button
                          type="button"
                          className="ms-auto text-muted-foreground hover:text-destructive"
                          title="Delete this custom persona"
                          aria-label="Delete persona"
                          onClick={(e) => e.preventDefault()}
                        >
                          <Trash2 size={13} aria-hidden="true" />
                        </button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>Delete persona &quot;{p.name}&quot;?</AlertDialogTitle>
                          <AlertDialogDescription>
                            This permanently deletes the custom persona. This cannot be undone.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>Cancel</AlertDialogCancel>
                          <AlertDialogAction
                            className={buttonVariants({ variant: "destructive" })}
                            onClick={() => void deletePersona(p.key)}
                          >
                            Delete
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  )}
                </label>
              ))}
            </div>
          )}
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="simulation-max-turns">
                Max turns (1–50)
              </label>
              <Input
                id="simulation-max-turns"
                className="w-24"
                value={maxTurns}
                onChange={(e) => setMaxTurns(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="simulation-budget">
                Budget per run (USD)
              </label>
              <Input
                id="simulation-budget"
                className="w-28"
                value={budget}
                onChange={(e) => setBudget(e.target.value)}
                placeholder="10"
              />
            </div>
            <Button size="sm" onClick={() => void launch()} disabled={busy || selected.size === 0}>
              <Play size={14} aria-hidden="true" /> Run simulation ({selected.size})
            </Button>
            <span className="text-xs text-muted-foreground">
              One isolated run per persona, up to 10 personas per session.
            </span>
          </div>
        </CardContent>
      </Card>

      {/* Session history */}
      <div className="space-y-2">
        {sessions.length === 0 && !loadFailed ? (
          <p className="text-sm text-muted-foreground">
            No simulation sessions yet — pick personas above and launch one.
          </p>
        ) : (
          sessions.map((s) => (
            <Card key={s.id}>
              <CardContent className="py-3">
                <button
                  type="button"
                  className="flex w-full items-center gap-2 text-start"
                  onClick={() => void toggleSession(s.id)}
                >
                  {expandedSession === s.id ? (
                    <ChevronDown size={14} aria-hidden="true" />
                  ) : (
                    <ChevronRight size={14} aria-hidden="true" />
                  )}
                  {statusBadge(s.status)}
                  {s.cancelRequested && LIVE.includes(s.status) && <Badge variant="secondary">cancelling…</Badge>}
                  <span className="text-sm text-foreground">
                    {s.personaKeys.length} persona{s.personaKeys.length === 1 ? "" : "s"} · {s.maxTurns} turns max
                  </span>
                  <span className="ms-auto text-xs text-muted-foreground">
                    {new Date(s.createdAt).toLocaleString()}
                  </span>
                </button>
                {LIVE.includes(s.status) && !s.cancelRequested && (
                  <div className="mt-2">
                    <Button variant="outline" size="sm" onClick={() => void cancelSession(s.id)}>
                      <StopCircle size={14} aria-hidden="true" /> Cancel session
                    </Button>
                  </div>
                )}
                {expandedSession === s.id && (
                  <div className="mt-3 space-y-1 border-t border-border pt-3">
                    {(sessionRuns[s.id] || []).length === 0 ? (
                      <p className="text-sm text-muted-foreground">Loading runs…</p>
                    ) : (
                      (sessionRuns[s.id] || []).map((r) => (
                        <div key={r.id} className="rounded-md border border-border">
                          <button
                            type="button"
                            className="flex w-full items-center gap-2 px-3 py-2 text-start"
                            onClick={() => void toggleRun(r.id)}
                          >
                            {openRun === r.id ? (
                              <ChevronDown size={13} aria-hidden="true" />
                            ) : (
                              <ChevronRight size={13} aria-hidden="true" />
                            )}
                            {statusBadge(r.status)}
                            {verdictBadge(r.verdict)}
                            <span className="text-sm font-medium text-foreground">{r.personaName}</span>
                            {r.termination && (
                              <span className="text-xs text-muted-foreground">
                                {TERMINATION_LABEL[r.termination] || r.termination}
                              </span>
                            )}
                            <span className="ms-auto text-xs text-muted-foreground">
                              {r.turnCount} turns{r.costUsd != null ? ` · $${r.costUsd}` : ""}
                            </span>
                          </button>
                          {openRun === r.id && (
                            <div className="border-t border-border px-3 py-2 text-sm">
                              {r.verdictReason && <p className="text-muted-foreground">{r.verdictReason}</p>}
                              {r.errorMessage && <p className="text-destructive">{r.errorMessage}</p>}
                              {openRunDetail?.transcriptJson && (
                                <pre className="mt-2 max-h-64 overflow-auto rounded bg-muted p-2 text-xs">
                                  {formatTranscript(openRunDetail.transcriptJson)}
                                </pre>
                              )}
                              {!openRunDetail && !r.verdictReason && !r.errorMessage && (
                                <p className="text-muted-foreground">Loading transcript…</p>
                              )}
                            </div>
                          )}
                        </div>
                      ))
                    )}
                  </div>
                )}
              </CardContent>
            </Card>
          ))
        )}
      </div>
    </Section>
  );
}

/** [{role:"persona"|"agent", text:string}] -> readable lines for the transcript panel. */
function formatTranscript(transcriptJson: string): string {
  try {
    const turns = JSON.parse(transcriptJson) as { role: string; text: string }[];
    return turns.map((t) => `${t.role === "persona" ? "Persona" : "Agent"}: ${t.text}`).join("\n\n");
  } catch {
    return transcriptJson;
  }
}
