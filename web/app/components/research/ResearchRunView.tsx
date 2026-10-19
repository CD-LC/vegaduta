"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Ban, Loader2, Radio, RotateCcw, ScrollText } from "lucide-react";
import { api } from "../../lib/api";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";
import { formatDuration, formatWhen, jobStatusTone } from "../jobs/jobStatus";
import ResearchReport from "./ResearchReport";
import { failureLabelKey, parseResearchResult, questionFromGoal, statusLabelKey } from "./researchRun";
import { nextPollDelay, shouldGiveUp } from "./pollSchedule";
import { setResearchPrefill } from "./prefill";
import { RS } from "./researchStrings";

/** Progress notes shown while a run is live; older ones are dropped from the list, not from the server. */
const MAX_NOTES_SHOWN = 40;

function formatUsd(value: unknown): string | null {
  const n = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  if (!Number.isFinite(n)) return null;
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency: "USD", maximumFractionDigits: 4 }).format(n);
  } catch {
    return String(n);
  }
}

type RefreshResult = { ok: boolean; data: any; gone: boolean };

/**
 * One research run, live. The same two data paths as the generic job screen
 * (components/jobs/JobDetail.tsx), for the same reasons:
 *
 *  - the run ROW is polled until the server says `terminal` - this component
 *    never keeps its own list of finished statuses. Polling backs off after
 *    failures, idles while the tab is hidden, slows down while a run is parked
 *    on an approval, and stops for good after repeated failures (the user can
 *    ask again) or a 404;
 *  - the TRACE streams while the run is live and is fetched once when the page
 *    opens on a finished run. A closed stream only triggers a row refresh; it
 *    is never read as "finished".
 *
 * On top of that the progress notes are re-read on every row poll: during a
 * research pass the server writes "Searching 4 angles", "Read 3 of 3 pages
 * (0 failed)", "Used 4/12 searches, 3/12 page reads", which is what tells the
 * user the wait is real work.
 *
 * The page mounts this with key={jobId}, so moving between runs starts from a
 * clean slate (no row, notes, trace or error carried over from the last run);
 * every async result is also dropped if its effect has already been torn down.
 */
export default function ResearchRunView({ jobId }: { jobId: string }) {
  const t = useTranslations("researchPage");
  const [job, setJob] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [stalled, setStalled] = useState(false);
  const [pollNonce, setPollNonce] = useState(0);
  const [notes, setNotes] = useState<any[]>([]);
  const [entries, setEntries] = useState<any[]>([]);
  // True once the stored trace of a finished run was fetched (even when it was empty), so "no trace" is never
  // guessed from a fetch that has not come back yet or one that failed.
  const [traceChecked, setTraceChecked] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const seen = useRef<Set<string>>(new Set());
  const parked = useRef(false);

  const pushEntries = useCallback((list: any[]) => {
    const fresh = (Array.isArray(list) ? list : []).filter((e) => seen.current.add(`${e.at}|${e.type}|${e.label}`));
    if (fresh.length) setEntries((prev) => [...prev, ...fresh]);
  }, []);

  const refresh = useCallback(
    async (isStale: () => boolean): Promise<RefreshResult> => {
      try {
        const data = await api.getResearchRun(jobId);
        if (isStale()) return { ok: true, data: null, gone: false };
        parked.current = data?.status === "AWAITING_APPROVAL";
        setJob(data);
        setError(null);
        if (data?.agentId) {
          api
            .getResearchRunProgress(data.agentId, jobId)
            .then((list: any) => {
              if (!isStale() && Array.isArray(list)) setNotes(list);
            })
            .catch(() => {
              // Notes are supplementary - the row's own progressNote still shows.
            });
        }
        return { ok: true, data, gone: false };
      } catch (err: any) {
        if (isStale()) return { ok: false, data: null, gone: false };
        const gone = err?.status === 404;
        // A 404 will not fix itself: the page stops asking.
        if (gone) setNotFound(true);
        setError(err?.message || t("loadRunFailed"));
        return { ok: false, data: null, gone };
      }
    },
    [jobId, t]
  );

  useEffect(() => {
    let stop = false;
    let inFlight = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let haveRow = false;
    const isStale = () => stop;
    setStalled(false);

    const tick = async () => {
      if (stop || inFlight) return;
      // Hidden tab with a row already on screen: nothing to keep fresh.
      if (haveRow && document.hidden) {
        timer = setTimeout(() => void tick(), nextPollDelay({ failures: 0, hidden: true }));
        return;
      }
      inFlight = true;
      const r = await refresh(isStale);
      inFlight = false;
      if (stop || r.gone) return;
      if (r.ok) {
        failures = 0;
        if (r.data) haveRow = true;
        if (r.data?.terminal) return;
      } else {
        failures += 1;
        if (shouldGiveUp(failures)) {
          setStalled(true);
          return;
        }
      }
      timer = setTimeout(
        () => void tick(),
        nextPollDelay({ failures, parked: parked.current, hidden: typeof document !== "undefined" && document.hidden })
      );
    };

    const onVisible = () => {
      if (document.hidden || stop) return;
      clearTimeout(timer);
      void tick();
    };
    document.addEventListener("visibilitychange", onVisible);
    void tick();
    return () => {
      stop = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refresh, pollNonce]);

  // The trace effects below depend on these two VALUES, never on the `job`
  // object: every row poll stores a new object, and an effect keyed on it
  // would run its cleanup (aborting the stream) four seconds after it opened.
  const agentId: string | null = job?.agentId ?? null;
  const terminal: boolean | null = job ? Boolean(job.terminal) : null;
  // Whether the run was already finished when this page first saw it; decided
  // once and then left alone, so the stream effect is not re-run when the run
  // later finishes.
  const [initialTerminal, setInitialTerminal] = useState<boolean | null>(null);
  useEffect(() => {
    if (terminal === null) return;
    setInitialTerminal((prev) => (prev === null ? terminal : prev));
  }, [terminal]);

  // Live trace: one stream for the life of the page, closed only on unmount
  // or when the run/agent changes.
  useEffect(() => {
    if (!agentId || initialTerminal !== false) return undefined;
    let closed = false;
    setStreaming(true);
    const stream = api.streamAgentJobTrace(agentId, jobId, (entry: any) => pushEntries([entry]));
    stream.done
      .catch(() => {
        // Transient stream failure: the row poll keeps the page truthful.
      })
      .finally(() => {
        if (closed) return;
        setStreaming(false);
        void refresh(() => closed);
      });
    return () => {
      closed = true;
      setStreaming(false);
      stream.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, jobId, initialTerminal]);

  // Stored trace: fetched once the run is finished - on opening a finished
  // run, and again at the moment a watched run finishes, so a stream that
  // dropped part-way can never leave the trace short (pushEntries dedupes).
  useEffect(() => {
    if (!agentId || terminal !== true) return undefined;
    let cancelled = false;
    api
      .getAgentJobTrace(agentId, jobId)
      .then((list: any) => {
        if (!cancelled) {
          pushEntries(list);
          setTraceChecked(true);
        }
      })
      .catch(() => {
        // The trace is supplementary.
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, jobId, terminal, pushEntries]);

  async function cancel() {
    setCancelling(true);
    try {
      setJob(await api.cancelResearchRun(jobId));
      setError(null);
    } catch (err: any) {
      setError(err?.message || t("cancelFailed"));
    } finally {
      setCancelling(false);
    }
  }

  const back = (
    <Button asChild variant="ghost" size="sm">
      <Link href="/research">
        <ArrowLeft className="size-4" aria-hidden="true" /> {t("backToResearch")}
      </Link>
    </Button>
  );

  if (!job) {
    return (
      <div className="space-y-4">
        {back}
        <div
          className="flex flex-wrap items-center gap-2 py-12 text-sm text-muted-foreground"
          data-testid="research-run-loading"
          role="status"
        >
          {notFound ? (
            t("runNotFound")
          ) : error ? (
            <>
              {error}
              {stalled && (
                <Button size="sm" variant="outline" onClick={() => setPollNonce((n) => n + 1)}>
                  {RS.pollingRetry}
                </Button>
              )}
            </>
          ) : (
            <>
              <Loader2 className="size-4 animate-spin" aria-hidden="true" /> {t("loadingRun")}
            </>
          )}
        </div>
      </div>
    );
  }

  const tone = jobStatusTone(job.status);
  const statusKey = statusLabelKey(job.status);
  const statusText = statusKey ? t(statusKey) : tone.label;
  const duration = formatDuration(job.startedAt, job.finishedAt);
  // Self-hosted/personal installs price nothing - the cost column reads "—"
  // rather than a meaningless estimate against a local model.
  const cost = isBillingEnabled() ? formatUsd(job.costUsd) : null;
  const isParked = job.status === "AWAITING_APPROVAL";
  const question = questionFromGoal(job.goal);
  // The server's cumulative allowance line, e.g. "Used 4/12 searches, 3/12 page reads".
  const usageNote = [...notes].reverse().find((n) => typeof n?.note === "string" && /^Used \d+\/\d+ /.test(n.note));
  const shownNotes = notes.slice(-MAX_NOTES_SHOWN);

  return (
    <div className="space-y-5" data-testid="research-run">
      {/* Announces status changes (queued -> researching -> done) to screen readers. */}
      <p className="sr-only" role="status" aria-live="polite">
        {RS.statusAnnouncement(statusText)}
      </p>
      <div className="flex flex-wrap items-center gap-3">
        {back}
        <Badge variant="outline" className={tone.className} data-testid="research-run-status">
          {statusText}
        </Badge>
        {streaming && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Radio className="size-3.5 text-primary" aria-hidden="true" /> {t("live")}
          </span>
        )}
        {job.terminal && question && (
          <Button asChild size="sm" variant="outline" className="ml-auto">
            <Link href="/research" onClick={() => setResearchPrefill(question)} title={RS.runAgainHint}>
              <RotateCcw className="size-4" aria-hidden="true" /> {RS.runAgain}
            </Link>
          </Button>
        )}
        {!job.terminal && (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto text-destructive"
            onClick={cancel}
            disabled={cancelling || job.cancelRequested}
          >
            <Ban className="size-4" aria-hidden="true" />
            {job.cancelRequested ? t("cancelRequested") : cancelling ? t("cancelling") : t("cancel")}
          </Button>
        )}
      </div>

      {error && (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          <span>{error}</span>
          {stalled && (
            <>
              <span className="text-xs">{RS.pollingStalled}</span>
              <Button size="sm" variant="outline" onClick={() => setPollNonce((n) => n + 1)}>
                {RS.pollingRetry}
              </Button>
            </>
          )}
        </div>
      )}

      <Card className="gap-3 p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("questionHeading")}</p>
        <p className="whitespace-pre-wrap text-sm text-foreground [overflow-wrap:anywhere]">{question}</p>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 border-t border-border pt-3 text-xs sm:grid-cols-4">
          <Meta label={t("metaStarted")} value={formatWhen(job.startedAt || job.createdAt)} />
          <Meta label={t("metaDuration")} value={duration ?? "—"} />
          <Meta label={t("metaCost")} value={cost ?? "—"} />
          <Meta label={t("metaAllowance")} value={usageNote?.note ?? "—"} />
        </dl>
      </Card>

      {isParked && (
        <Card className="gap-1 p-4">
          <p className="text-sm font-medium text-foreground">{t("parkedTitle")}</p>
          <p className="text-xs text-muted-foreground">
            {t("parkedBody")}{" "}
            <Link className="underline" href="/admin/maintenance">
              {t("parkedLink")}
            </Link>
          </p>
        </Card>
      )}

      {!job.terminal && (
        <Card className="gap-3 p-5" data-testid="research-progress">
          <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />{" "}
            {t("progressHeading")}
          </p>
          <p className="text-sm text-foreground" aria-live="polite">
            {job.progressNote || (job.status === "QUEUED" ? t("progressQueued") : t("progressWorking"))}
          </p>
          {shownNotes.length > 0 && (
            <ol className="space-y-1">
              {shownNotes.map((n, i) => (
                <li key={n.id || i} className="flex gap-3 text-xs text-muted-foreground">
                  <span className="w-20 shrink-0 tabular-nums">
                    {n.createdAt ? new Date(n.createdAt).toLocaleTimeString() : ""}
                  </span>
                  <span className="min-w-0 [overflow-wrap:anywhere]">{n.note}</span>
                </li>
              ))}
            </ol>
          )}
          <p className="text-xs text-muted-foreground">{t("progressLeaveNote")}</p>
        </Card>
      )}

      {job.status === "FAILED" && (
        <Card className="gap-1 border-destructive/40 p-4" data-testid="research-run-failed">
          <p className="text-sm font-medium text-destructive">{t("failedTitle")}</p>
          {/* The server's errorCode is an internal word and its errorMessage can carry internals: only our own sentences are shown. */}
          <p className="text-sm text-muted-foreground">{t(failureLabelKey(job.errorCode))}</p>
        </Card>
      )}

      {job.status === "CANCELLED" && (
        <Card className="gap-1 p-4">
          <p className="text-sm text-muted-foreground">{t("cancelledNote")}</p>
        </Card>
      )}

      {job.terminal && <ResearchReport job={job} noToolCalls={traceChecked && entries.length === 0} />}

      {/* "Succeeded" with no readable report (empty or malformed resultJson, no plain text) must say so, not render a blank page. */}
      {job.terminal &&
        job.status === "SUCCEEDED" &&
        !parseResearchResult(job.resultJson) &&
        !job.resultText?.trim() && (
          <Card className="gap-1 p-4">
            <p className="text-sm text-muted-foreground">{t("emptyResult")}</p>
          </Card>
        )}

      <details className="rounded-xl border border-border bg-card p-4">
        <summary className="flex cursor-pointer items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <ScrollText className="size-4" aria-hidden="true" /> {t("traceHeading")}
        </summary>
        {entries.length === 0 ? (
          <p className="mt-3 text-sm text-muted-foreground">{job.terminal ? t("traceEmpty") : t("traceWaiting")}</p>
        ) : (
          <ol className="mt-3 space-y-2">
            {entries.map((e, i) => (
              <li key={`${e.at}|${e.type}|${e.label}|${i}`} className="flex gap-3 text-sm">
                <span className="w-20 shrink-0 tabular-nums text-xs text-muted-foreground">
                  {e.at ? new Date(e.at).toLocaleTimeString() : ""}
                </span>
                <div className="min-w-0">
                  <span className={e.type === "TOOL_CALL" ? "font-mono text-xs text-primary" : "text-foreground"}>
                    {e.label}
                  </span>
                  {e.detail && <span className="ml-2 break-all text-xs text-muted-foreground">{e.detail}</span>}
                  {e.errorMessage && <span className="ml-2 text-xs text-destructive">{e.errorMessage}</span>}
                </div>
              </li>
            ))}
          </ol>
        )}
      </details>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground [overflow-wrap:anywhere]">{value}</dd>
    </div>
  );
}
