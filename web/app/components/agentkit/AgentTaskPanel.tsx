"use client";

// The on-device coding agent, surfaced on /code: a task box, the live plan, the
// approval queue and the context-window meter. All behaviour lives in
// lib/agentkit; this file is state wiring and nothing else.
//
// Three rules this component exists to hold (2026-09-19):
//
//  1. NOTHING IS WRITTEN WITHOUT A CLICK ON A DIFF. The loop calls `approve()`
//     and waits; this component renders the request and resolves it only from a
//     button. The write-permission prompt is requested INSIDE that click
//     (ensureWritePermission) — the loop's own write happens several awaits
//     later, by which point the browser's user activation may have lapsed and
//     the permission request would be refused without ever showing a dialog.
//
//  2. NO DEAD ENDS. Every state a run can end in carries a sentence and a next
//     action (continue, new task, open /edge). A refused permission keeps the
//     proposal on screen so Apply can be tried again. Stop always works, even
//     while an approval is pending (the pending request is resolved as "stop",
//     otherwise the loop would wait on it forever).
//
//  3. NO PRETEND TERMINAL. The panel says up front that commands cannot be run
//     from a browser tab and where they can be (the VS Code extension and the
//     JetBrains plugin, which do run them). The command-approval card below is
//     real and tested, but no host reachable from this page has a terminal, so
//     it cannot appear here today.
//
// Privacy: same as the rest of /code — the task, the prompt and the file
// contents never leave the device. This component is not request-free, though:
// its model probe (resolveEdgeAgentModel → getTier1RunModel) fetches the public
// edge model manifest (/api/edge/manifest), exactly as /code's Ask card does,
// and an already-downloaded engine loads its weights through its own loader.
// Neither carries anything the person typed or opened. The run state saved for
// "continue after reload" goes to localStorage on this device
// (lib/agentkit/persist.ts), minus anything that looks like a secret.

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  CheckCircle2,
  Circle,
  CircleDot,
  KeyRound,
  ListChecks,
  Loader2,
  Play,
  RotateCcw,
  StopCircle,
  TerminalSquare,
} from "lucide-react";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import LocalCodeDiffView, { type ApplyOutcome } from "../localcode/LocalCodeDiffView";
import { ensureWritePermission } from "../../lib/localcode/fsAccess";
import type { LocalRepoHandle } from "../../lib/localcode/types";
import { createBrowserHost } from "../../lib/agentkit/browserHost";
import { AGENT_COPY, statusLine } from "../../lib/agentkit/copy";
import { renderCall, type ContextReport, type Eviction } from "../../lib/agentkit/context";
import { resolveEdgeAgentModel } from "../../lib/agentkit/edgeModel";
import { isResumable, newRunState, runAgent } from "../../lib/agentkit/loop";
import { clearRun, loadRun, saveRun } from "../../lib/agentkit/persist";
import type { AgentRunState, ApprovalDecision, ApprovalRequest } from "../../lib/agentkit/types";
import ContextMeter from "./ContextMeter";
import { useTranslations } from "../../lib/i18n";

type ModelGate =
  | { kind: "checking" }
  | { kind: "ready"; modelId: string; contextWindow: number }
  | { kind: "unavailable"; detail: string; reason: string };

const MAX_EVICTIONS_SHOWN = 14;

export default function AgentTaskPanel({ repo }: { repo: LocalRepoHandle | null }) {
    const tHC = useTranslations("hcComponentsAgentkitAgentTaskPanel");
  const [gate, setGate] = useState<ModelGate>({ kind: "checking" });
  const [task, setTask] = useState("");
  const [run, setRun] = useState<AgentRunState | null>(null);
  const [saved, setSaved] = useState<AgentRunState | null>(null);
  const [running, setRunning] = useState(false);
  const [activity, setActivity] = useState("");
  const [report, setReport] = useState<ContextReport | null>(null);
  const [evictions, setEvictions] = useState<Eviction[]>([]);
  const [pending, setPending] = useState<ApprovalRequest | null>(null);
  const [writeOutcome, setWriteOutcome] = useState<ApplyOutcome>({ kind: "none" });
  const [granting, setGranting] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const resolveRef = useRef<((decision: ApprovalDecision) => void) | null>(null);

  // Read-only probe: answers only for weights already on this device and
  // starts no download (edgeModel.ts). It takes no AbortSignal, so a sequence
  // number decides which answer counts: only the newest probe may set the gate,
  // and none may after unmount.
  const probeSeq = useRef(0);
  const probe = useCallback((showChecking: boolean) => {
    const seq = (probeSeq.current += 1);
    if (showChecking) setGate({ kind: "checking" });
    resolveEdgeAgentModel()
      .then((result) => {
        if (probeSeq.current !== seq) return;
        if (result.ok === true) {
          setGate({ kind: "ready", modelId: result.model.id, contextWindow: result.model.contextWindow });
        } else {
          const miss = result as { detail: string; reason: string };
          setGate({ kind: "unavailable", detail: miss.detail, reason: miss.reason });
        }
      })
      .catch(() => {
        if (probeSeq.current === seq) {
          setGate({
            kind: "unavailable",
            detail: "This device could not be checked for an on-device model.",
            reason: "probe-failed",
          });
        }
      });
  }, []);

  useEffect(() => {
    probe(false);
    return () => {
      // Disown any probe still in flight.
      probeSeq.current += 1;
    };
  }, [probe]);

  // Start and Continue are disabled until the gate is "ready", and the usual
  // way to get there is to download a model on /edge in ANOTHER tab and come
  // back. Without this the gate would stay "unavailable" until a manual reload.
  // So while it is not ready (and nothing is running), coming back to the tab
  // probes again — quietly, without flashing "checking". "Check again" below
  // is the same thing on a button, for when focus events do not fire.
  // A layout effect, so the listener is gone in the same commit that shows
  // "ready" (or starts a run): a passive effect's cleanup runs later, and a
  // focus event landing in between would probe again.
  const gateReady = gate.kind === "ready";
  useLayoutEffect(() => {
    if (gateReady || running) return undefined;
    const recheck = () => {
      if (document.visibilityState === "visible") probe(false);
    };
    window.addEventListener("focus", recheck);
    document.addEventListener("visibilitychange", recheck);
    return () => {
      window.removeEventListener("focus", recheck);
      document.removeEventListener("visibilitychange", recheck);
    };
  }, [gateReady, running, probe]);

  const settlePending = useCallback((decision: ApprovalDecision) => {
    const resolve = resolveRef.current;
    resolveRef.current = null;
    setPending(null);
    setWriteOutcome({ kind: "none" });
    if (resolve) resolve(decision);
  }, []);

  // A different folder is a different run. Stop whatever is going, and look
  // for a run saved against THIS folder.
  useEffect(() => {
    // Abort AND disown the controller: drive() compares against abortRef before
    // every state write, so a run that belonged to the previous folder can no
    // longer paint its result over this one.
    abortRef.current?.abort();
    abortRef.current = null;
    setRunning(false);
    settlePending("stop");
    setRun(null);
    setReport(null);
    setEvictions([]);
    setActivity("");
    const found = repo ? loadRun(repo.id) : null;
    setSaved(found && isResumable(found) ? found : null);
  }, [repo, settlePending]);

  // The run dies with the page: an agent still generating after the person
  // navigated away is their GPU working for nobody. A pending approval is
  // released as "stop" so the loop's promise does not dangle.
  useEffect(
    () => () => {
      abortRef.current?.abort();
      const resolve = resolveRef.current;
      resolveRef.current = null;
      if (resolve) resolve("stop");
    },
    []
  );

  const drive = useCallback(
    async (initial: AgentRunState) => {
      if (!repo || running) return;
      setRunning(true);
      setSaved(null);
      setRun(initial);
      setActivity("Checking the on-device model…");
      const controller = new AbortController();
      abortRef.current = controller;
      try {
        // Re-resolved per run, not reused from the gate: the run needs the
        // model OBJECT, and the set of downloaded models can change while this
        // tab is open (a model deleted on /edge since the gate went "ready").
        // The opposite case — a model downloaded since — is the gate's job:
        // see the focus re-probe above, because this line is only reachable
        // once the gate already says ready.
        probeSeq.current += 1; // an older in-flight probe must not overwrite this answer
        const resolved = await resolveEdgeAgentModel();
        if (abortRef.current !== controller) return;
        if (resolved.ok !== true) {
          const miss = resolved as { detail: string; reason: string };
          setGate({ kind: "unavailable", detail: miss.detail, reason: miss.reason });
          setRun({ ...initial, status: "failed", answer: miss.detail });
          return;
        }
        setGate({ kind: "ready", modelId: resolved.model.id, contextWindow: resolved.model.contextWindow });

        const final = await runAgent({
          host: createBrowserHost(repo),
          model: resolved.model,
          state: initial,
          signal: controller.signal,
          persist: (state) => {
            saveRun(repo.id, state);
          },
          approve: (request) =>
            new Promise<ApprovalDecision>((resolve) => {
              // The loop is sequential, so there is never more than one open
              // request; a stale resolver here would mean a bug, and refusing
              // it is the safe reading.
              resolveRef.current?.("reject");
              resolveRef.current = resolve;
              setWriteOutcome({ kind: "none" });
              setPending(request);
            }),
          onEvent: (event) => {
            if (abortRef.current !== controller) return;
            if (event.type === "state") setRun(event.state);
            else if (event.type === "model-start") setActivity(`Step ${event.step}: the model is thinking…`);
            else if (event.type === "tool-start")
              setActivity(`Step ${event.step}: ${renderCall(event.call).slice(0, 140)}`);
            else if (event.type === "context") {
              setReport(event.report);
              if (event.report.evicted.length > 0) {
                setEvictions((prev) => {
                  // The same truncation is reported on every step it applies;
                  // show each distinct one once, newest first.
                  const next = [...event.report.evicted].reverse().concat(prev);
                  const seen = new Set<string>();
                  return next
                    .filter((e) => {
                      const key = `${e.what}|${e.why}`;
                      if (seen.has(key)) return false;
                      seen.add(key);
                      return true;
                    })
                    .slice(0, MAX_EVICTIONS_SHOWN);
                });
              }
            }
          },
        });
        if (abortRef.current !== controller) return;
        setRun(final);
        // A finished or stalled run has nothing to resume — do not offer it
        // again on the next visit.
        if (!isResumable(final)) clearRun(repo.id);
      } catch {
        // runAgent never throws; this is for the wiring above it.
        setRun((prev) =>
          prev
            ? { ...prev, status: "failed", answer: "The run stopped unexpectedly. Nothing further was changed." }
            : prev
        );
      } finally {
        if (abortRef.current === controller) {
          abortRef.current = null;
          setRunning(false);
          setActivity("");
          settlePending("stop");
        }
      }
    },
    [repo, running, settlePending]
  );

  const start = useCallback(() => {
    const text = task.trim();
    if (!text || !repo) return;
    setEvictions([]);
    setReport(null);
    void drive(newRunState(text));
  }, [task, repo, drive]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    settlePending("stop");
  }, [settlePending]);

  const reset = useCallback(() => {
    if (repo) clearRun(repo.id);
    setRun(null);
    setSaved(null);
    setReport(null);
    setEvictions([]);
  }, [repo]);

  const approveWrite = useCallback(async () => {
    if (!repo || granting) return;
    setGranting(true);
    try {
      // Inside the click: see rule 1 in the header.
      const granted = await ensureWritePermission(repo);
      if (!granted) {
        setWriteOutcome({ kind: "denied" });
        return;
      }
      settlePending("approve");
    } catch {
      setWriteOutcome({ kind: "denied" });
    } finally {
      setGranting(false);
    }
  }, [repo, granting, settlePending]);

  const pendingEdit = useMemo(
    () =>
      pending && pending.kind === "write"
        ? {
            path: pending.path,
            original: pending.original ?? "",
            updated: pending.updated,
            diff: pending.diff,
            coarse: pending.coarse,
          }
        : null,
    [pending]
  );

  const modelReady = gate.kind === "ready";
  const canStart = !!repo && modelReady && !running && task.trim().length > 0;
  const showResult = run !== null && !running && run.status !== "idle";

  return (
    <section className="rounded-xl border border-border bg-card p-5" data-testid="agentkit-panel">
      <div className="flex items-start gap-3">
        <ListChecks size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
        <div className="min-w-0">
          <h2 className="text-base font-semibold text-foreground">{AGENT_COPY.title}</h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{AGENT_COPY.body}</p>
        </div>
      </div>

      <div className="mt-4 rounded-lg border border-border bg-secondary/30 p-3">
        <div className="flex items-start gap-2">
          <TerminalSquare size={14} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="min-w-0">
            <p className="text-xs font-semibold text-foreground">{AGENT_COPY.terminalTitle}</p>
            <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted-foreground">{AGENT_COPY.terminalBody}</p>
          </div>
        </div>
      </div>

      {saved && !running && !run && (
        <div className="mt-4 rounded-lg border border-border bg-secondary/30 p-3">
          <p className="text-sm font-semibold text-foreground">{AGENT_COPY.savedRunTitle}</p>
          <p className="mt-1 text-xs text-muted-foreground">“{saved.task.slice(0, 200)}”</p>
          <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted-foreground">{AGENT_COPY.savedRunBody}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button size="sm" onClick={() => void drive(saved)} disabled={!modelReady}>
              <Play size={13} aria-hidden="true" />
              {AGENT_COPY.continueRun}
            </Button>
            <Button size="sm" variant="secondary" onClick={reset}>
              {AGENT_COPY.discardRun}
            </Button>
          </div>
        </div>
      )}

      <Textarea
        value={task}
        onChange={(event) => setTask(event.target.value)}
        placeholder={AGENT_COPY.taskPlaceholder}
        rows={3}
        disabled={running || !repo}
        className="mt-4"
        aria-label={AGENT_COPY.title}
      />
      <div className="mt-3 flex flex-wrap items-center gap-3">
        {/* Start stays where it is (disabled) while a run is going and Stop is a
            SEPARATE button after it. They used to swap in one slot, and React
            flushes a click synchronously — so the second click of a double-click
            on Start landed on Stop and aborted the run it had just begun. */}
        <Button size="sm" onClick={start} disabled={!canStart}>
          {running ? (
            <Loader2 size={13} className="animate-spin" aria-hidden="true" />
          ) : (
            <Play size={13} aria-hidden="true" />
          )}
          {running ? AGENT_COPY.runningLabel : AGENT_COPY.start}
        </Button>
        {running && (
          <Button size="sm" variant="secondary" onClick={stop}>
            <StopCircle size={13} aria-hidden="true" />
            {AGENT_COPY.stop}
          </Button>
        )}
        {!repo && <span className="text-xs text-muted-foreground">{AGENT_COPY.needFolder}</span>}
        {repo && gate.kind === "checking" && (
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" />
            {AGENT_COPY.checkingModel}
          </span>
        )}
        {repo && gate.kind === "unavailable" && (
          <span className="text-xs text-muted-foreground">
            {gate.detail}{" "}
            {/* /edge is gated on the same flag, so it is only a useful link when
                the reason is a missing model rather than the feature being off. */}
            {gate.reason === "no-model" && (
              <Link href="/edge" className="underline underline-offset-2">
                {tHC("s4")}
              </Link>
            )}{" "}
            {/* No use re-checking a deployment-level switch. */}
            {gate.reason !== "edge-off" && (
              <button
                type="button"
                onClick={() => probe(true)}
                className="underline underline-offset-2"
                data-testid="agentkit-recheck"
              >
                {AGENT_COPY.recheckModel}
              </button>
            )}
          </span>
        )}
        {repo && gate.kind === "ready" && !running && (
          <span className="text-xs text-muted-foreground">
            {tHC("s3")} {gate.modelId} · {gate.contextWindow.toLocaleString()}{tHC("s2")}
          </span>
        )}
        {running && (
          <span className="inline-flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground" aria-live="polite">
            <Loader2 size={11} className="shrink-0 animate-spin" aria-hidden="true" />
            <span className="truncate">{activity || statusLine(run?.status ?? "running", run?.stepsUsed ?? 0)}</span>
          </span>
        )}
      </div>

      {/* ---------------- Approval queue ---------------- */}
      {pending && (
        <div
          className="mt-5 rounded-lg border border-primary/40 bg-primary/5 p-4"
          role="group"
          aria-label={AGENT_COPY.approvalTitle}
        >
          <p className="text-sm font-semibold text-foreground">{AGENT_COPY.approvalTitle}</p>
          {pending.kind === "write" && pendingEdit && (
            <div className="mt-2">
              <p className="text-xs leading-relaxed text-muted-foreground">
                {pending.original === null ? `${AGENT_COPY.newFile} ` : ""}
                {AGENT_COPY.approveWriteHint}
              </p>
              <div className="mt-3">
                <LocalCodeDiffView
                  edit={pendingEdit}
                  outcome={writeOutcome}
                  applying={granting}
                  onApply={() => void approveWrite()}
                  onDiscard={() => settlePending("reject")}
                />
              </div>
              {/* The diff view renders the refused-permission notice itself and
                  keeps Apply live, so a refusal is a retry, not a dead end. */}
              {pending.coarse && <p className="mt-2 text-xs text-muted-foreground">{AGENT_COPY.coarseDiff}</p>}
            </div>
          )}
          {pending.kind === "secret-read" && (
            <div className="mt-2">
              <p className="flex items-center gap-2 text-sm text-foreground">
                <KeyRound size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="break-all font-mono text-xs">{pending.path}</span>
              </p>
              <p className="mt-1 text-xs font-medium text-foreground">{AGENT_COPY.secretTitle}</p>
              <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted-foreground">{AGENT_COPY.secretBody}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" onClick={() => settlePending("reject")}>
                  {AGENT_COPY.deny}
                </Button>
                <Button size="sm" variant="outline" onClick={() => settlePending("approve")}>
                  {AGENT_COPY.allowOnce}
                </Button>
              </div>
            </div>
          )}
          {pending.kind === "command" && (
            <div className="mt-2">
              <p className="text-xs font-medium text-foreground">{AGENT_COPY.commandTitle}</p>
              <pre className="mt-2 overflow-x-auto rounded-md border border-border bg-background p-2 font-mono text-xs text-foreground">
                {pending.command}
              </pre>
              <p className="mt-1 text-xs text-muted-foreground">in {pending.cwd}</p>
              {pending.danger && (
                <p className="mt-2 flex items-start gap-1.5 text-xs text-destructive">
                  <AlertTriangle size={12} className="mt-0.5 shrink-0" aria-hidden="true" />
                  <span>
                    {AGENT_COPY.commandFlagged} {pending.danger}.
                  </span>
                </p>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" onClick={() => settlePending("reject")}>
                  {AGENT_COPY.dontRun}
                </Button>
                <Button size="sm" variant="outline" onClick={() => settlePending("approve")}>
                  {AGENT_COPY.runCommand}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ---------------- Plan + steps ---------------- */}
      {run && (
        <div className="mt-5 grid gap-4 lg:grid-cols-2">
          <div className="min-w-0 rounded-lg border border-border p-3">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {AGENT_COPY.planTitle}
            </p>
            {run.plan.length === 0 ? (
              <p className="mt-2 text-xs text-muted-foreground">{AGENT_COPY.planEmpty}</p>
            ) : (
              <ol className="mt-2 space-y-1.5">
                {run.plan.map((item, index) => (
                  <li key={`${index}-${item.text}`} className="flex items-start gap-2 text-sm">
                    {item.status === "done" ? (
                      <CheckCircle2 size={14} className="mt-0.5 shrink-0 text-primary" aria-label="done" />
                    ) : item.status === "doing" ? (
                      <CircleDot size={14} className="mt-0.5 shrink-0 text-primary" aria-label={tHC("s1")} />
                    ) : (
                      <Circle size={14} className="mt-0.5 shrink-0 text-muted-foreground" aria-label="to do" />
                    )}
                    <span className={item.status === "done" ? "text-muted-foreground line-through" : "text-foreground"}>
                      {item.text}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
          <div className="min-w-0 rounded-lg border border-border p-3">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {AGENT_COPY.stepsTitle} · {statusLine(run.status, run.stepsUsed)}
            </p>
            <ul className="mt-2 max-h-56 space-y-1 overflow-y-auto">
              {run.summary.map((line, index) => (
                <li key={`s-${index}`} className="truncate font-mono text-[11px] text-muted-foreground" title={line}>
                  {line}
                </li>
              ))}
              {run.turns.map((turn) => (
                <li
                  key={`t-${turn.step}`}
                  className={`truncate font-mono text-[11px] ${turn.failed ? "text-destructive" : "text-foreground"}`}
                  title={turn.observation.slice(0, 600)}
                >
                  {turn.step}. {turn.call ? renderCall(turn.call) : "(unreadable reply)"}
                  {turn.failed ? " — failed" : ""}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* ---------------- Result ---------------- */}
      {showResult && run && (
        <div className="mt-5 rounded-lg border border-border p-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {AGENT_COPY.answerTitle} · {statusLine(run.status, run.stepsUsed)}
          </p>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-relaxed text-foreground">{run.answer}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {isResumable(run) && run.status !== "failed" && (
              <Button size="sm" onClick={() => void drive(run)} disabled={!modelReady}>
                <Play size={13} aria-hidden="true" />
                {AGENT_COPY.continueRun}
              </Button>
            )}
            {run.status === "failed" && gate.kind === "ready" && (
              <Button size="sm" variant="secondary" onClick={() => void drive(run)}>
                <RotateCcw size={13} aria-hidden="true" />
                {AGENT_COPY.continueRun}
              </Button>
            )}
            <Button size="sm" variant="secondary" onClick={reset}>
              {AGENT_COPY.newTask}
            </Button>
          </div>
        </div>
      )}

      <ContextMeter report={report} evictions={evictions} />
    </section>
  );
}
