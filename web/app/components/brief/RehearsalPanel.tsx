"use client";

import { useEffect, useRef, useState } from "react";
import { Drama, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import type { Brief, RehearsalRunView, RehearsalSession } from "./types";

// Rehearsal: persona-driven conversations against the applied agent before
// real traffic. The transcripts are simulations - tools are offered, never
// executed - and every one carries a visible pill saying so.

interface Turn {
  role: string;
  text: string;
}
const TERMINAL = new Set(["PASSED", "FAILED", "ERROR"]);
const POLL_MS = 3000;
const MAX_POLLS = 40;

export function parseTranscript(json: string | null | undefined): Turn[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v.filter((x) => x && typeof x.text === "string").map((x) => ({ role: String(x.role), text: x.text })) : [];
  } catch {
    return [];
  }
}

export function RehearsalPill() {
  const t = useTranslations("brief");
  return (
    <span className="state-pill" style={{ ["--state" as string]: "var(--state-queued)" }}>
      <span className="state-dot" aria-hidden="true" />
      {t("rehearsal.pill")}
    </span>
  );
}

function Transcript({ agentId, run }: { agentId: string; run: RehearsalRunView }) {
  const t = useTranslations("brief");
  const [turns, setTurns] = useState<Turn[] | null>(null);
  const [failed, setFailed] = useState(false);
  const terminal = TERMINAL.has(run.status);

  useEffect(() => {
    if (!terminal) return undefined;
    let alive = true;
    api
      .getSimulationRun(agentId, run.runId)
      .then((d: { transcriptJson?: string | null }) => alive && setTurns(parseTranscript(d.transcriptJson)))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [agentId, run.runId, terminal]);

  return (
    <li className="space-y-2 rounded-lg border border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-semibold text-foreground">{run.persona}</h4>
        <RehearsalPill />
        {!terminal && (
          <span className="flex items-center gap-1 text-xs text-muted-foreground">
            <Loader2 className="size-3 animate-spin" aria-hidden="true" />
            {t("rehearsal.running")}
          </span>
        )}
        {run.verdict && <span className="text-xs text-muted-foreground">{t(`rehearsal.verdict.${run.verdict}`)}</span>}
      </div>
      {run.verdictReason && <p className="text-sm text-muted-foreground">{run.verdictReason}</p>}
      {run.status === "ERROR" && <p className="text-sm text-muted-foreground">{t("rehearsal.runError")}</p>}
      {failed && <p className="text-sm text-muted-foreground">{t("rehearsal.transcriptFailed")}</p>}
      {turns && turns.length > 0 && (
        <details>
          <summary className="cursor-pointer text-xs text-muted-foreground">{t("rehearsal.showTranscript", { count: turns.length })}</summary>
          <p className="mt-2 text-xs text-muted-foreground">{t("rehearsal.noTools")}</p>
          <ol className="mt-2 space-y-2">
            {turns.map((turn, i) => (
              <li key={i} className="text-sm">
                <span className="font-medium text-foreground">{turn.role === "agent" ? t("rehearsal.agent") : run.persona}</span>
                <span className="text-muted-foreground">{": "}</span>
                <span className="whitespace-pre-wrap break-words text-foreground">{turn.text}</span>
              </li>
            ))}
          </ol>
        </details>
      )}
    </li>
  );
}

export default function RehearsalPanel({
  briefId,
  brief,
  agentId,
  canRun,
}: {
  briefId: string;
  brief: Brief;
  /** Entity id of the agent the sessions run against; null until the envoy is applied. */
  agentId: string | null;
  canRun: boolean;
}) {
  const t = useTranslations("brief");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState(0);
  const [runs, setRuns] = useState<RehearsalRunView[]>([]);
  const [runAgentId, setRunAgentId] = useState<string | null>(agentId);
  const alive = useRef(true);
  const personas = brief.rehearsal?.personas ?? [];

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  async function start() {
    setBusy(true);
    setError(null);
    setRuns([]);
    try {
      const started = await api.rehearseBrief(briefId);
      if (!alive.current) return;
      setStarted(started.sessionIds.length);
      setRunAgentId(started.agentId);
      const mine = new Set(started.sessionIds);
      for (let n = 0; n < MAX_POLLS; n++) {
        // Every session core lists here is labelled "rehearsal"; only this start's sessions are followed.
        const all: RehearsalRunView[] = (await api.listRehearsals(briefId)).filter((x: RehearsalSession) => mine.has(x.sessionId)).flatMap((x: RehearsalSession) => x.runs);
        if (!alive.current) return;
        setRuns(all);
        if (all.length > 0 && all.every((r) => TERMINAL.has(r.status))) break;
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (!alive.current) return;
      }
    } catch (e: any) {
      if (alive.current) setError(e?.message || t("rehearsal.failed"));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  return (
    <section className="space-y-4 rounded-xl border border-border bg-card p-5" aria-label={t("rehearsal.title")}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="flex items-center gap-2 text-base font-semibold text-foreground">
            <Drama className="size-4" aria-hidden="true" />
            {t("rehearsal.title")}
          </h3>
          <p className="text-sm text-muted-foreground">{t("rehearsal.subtitle")}</p>
        </div>
        <Button size="sm" onClick={start} disabled={busy || !canRun}>
          {busy && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
          {t("rehearsal.run")}
        </Button>
      </div>

      {!canRun && <p className="text-xs text-muted-foreground">{t("rehearsal.needsPermission")}</p>}
      {personas.length > 0 && (
        <p className="text-sm text-muted-foreground">{t("rehearsal.personas", { names: personas.map((p) => p.name).join(", ") })}</p>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {started > 0 && runs.length === 0 && <p className="text-sm text-muted-foreground">{t("rehearsal.startedNoAgent", { count: started })}</p>}
      {runAgentId && runs.length > 0 && (
        <ul className="space-y-3">
          {runs.map((r) => (
            <Transcript key={r.runId} agentId={runAgentId} run={r} />
          ))}
        </ul>
      )}
    </section>
  );
}
