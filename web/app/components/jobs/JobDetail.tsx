"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Ban, Loader2, Radio, ScrollText } from "lucide-react";
import { api } from "../../lib/api";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Separator } from "../ui/separator";
import { jobStatusTone, jobStatusText, formatDuration, formatWhen } from "./jobStatus";
import RichText from "../RichText";
import ResearchReportLink from "../research/ResearchReportLink";
import { useTranslations } from "../../lib/i18n";
import { isPersonalInstall } from "../../lib/personalInstall";

const DETAIL_POLL_MS = 4000;

// One job, live. Two data paths on purpose:
//
//  - the job ROW (status, progress note, result, error) is polled while the
//    server says `terminal: false` - the response projects that boolean
//    explicitly so this component never keeps its own list of finished
//    statuses (see jobStatus.ts for the AWAITING_APPROVAL history);
//  - the TRACE arrives over the SSE stream while live (the server closes the
//    stream itself after a final catch-up poll once the job finishes), and is
//    fetched once, statically, when the page opens on an already-finished job.
//
// The stream deliberately does NOT drive the job row: its entries are
// progress notes and tool calls, not status transitions, and inferring
// "finished" from "the stream closed" would misread a dropped connection as
// completion. Stream closure merely triggers one immediate row refresh.
export default function JobDetail({ agentId, jobId }: { agentId: string; jobId: string }) {
  const t = useTranslations("jobsPage");
  const [job, setJob] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [entries, setEntries] = useState<any[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  // Identity-dedupe mirrors the server's own trace-stream dedupe key
  // (at|type|label) so a static refetch after a stream can't double-print.
  const seen = useRef<Set<string>>(new Set());

  const pushEntries = useCallback((list: any[]) => {
    const fresh = list.filter((e) => seen.current.add(`${e.at}|${e.type}|${e.label}`));
    if (fresh.length) setEntries((prev) => [...prev, ...fresh]);
  }, []);

  const refreshJob = useCallback(async () => {
    try {
      const data = await api.getAgentJob(agentId, jobId);
      setJob(data);
      setError(null);
      return data;
    } catch (err: any) {
      setError(err?.message || t("detailLoadFailed"));
      return null;
    }
  }, [agentId, jobId, t]);

  // Row poll: runs until the server says terminal.
  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      const data = await refreshJob();
      if (stop) return;
      if (!data || !data.terminal)
        timer = setTimeout(() => {
          void tick();
        }, DETAIL_POLL_MS);
    };
    void tick();
    return () => {
      stop = true;
      clearTimeout(timer);
    };
  }, [refreshJob]);

  // Trace: stream while live, one static fetch when already finished. Keyed
  // only on the job ids plus a one-shot "which mode" decision - re-running on
  // every job update would tear the stream down each poll.
  const modeDecided = useRef(false);
  useEffect(() => {
    if (!job || modeDecided.current) return undefined;
    modeDecided.current = true;
    if (job.terminal) {
      api
        .getAgentJobTrace(agentId, jobId)
        .then(pushEntries)
        .catch(() => {
          // The trace is supplementary - the row's result/error still renders.
        });
      return undefined;
    }
    setStreaming(true);
    const stream = api.streamAgentJobTrace(agentId, jobId, (entry: any) => pushEntries([entry]));
    stream.done
      .catch(() => {
        // Transient stream failure: the row poll keeps the page truthful.
      })
      .finally(() => {
        setStreaming(false);
        void refreshJob();
      });
    return () => stream.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job, agentId, jobId]);

  async function cancel() {
    setCancelling(true);
    try {
      // Idempotent server-side - a job that finished between render and click
      // just returns its current row, so no pre-check is needed.
      setJob(await api.cancelAgentJob(agentId, jobId));
    } catch (err: any) {
      setError(err?.message || t("cancelFailed"));
    } finally {
      setCancelling(false);
    }
  }

  if (!job) {
    return (
      <div className="flex items-center gap-2 py-16 text-sm text-muted-foreground">
        {error ? (
          error
        ) : (
          <>
            <Loader2 className="size-4 animate-spin" aria-hidden="true" /> {t("detailLoading")}
          </>
        )}
      </div>
    );
  }

  const tone = jobStatusTone(job.status);
  const duration = formatDuration(job.startedAt, job.finishedAt);
  const parked = job.status === "AWAITING_APPROVAL";

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-3">
        <Button asChild variant="ghost" size="sm">
          <Link href={isPersonalInstall() ? "/jobs/all" : "/jobs"}>
            <ArrowLeft className="size-4" aria-hidden="true" /> {t("allJobs")}
          </Link>
        </Button>
        <Badge variant="outline" className={tone.className}>
          {jobStatusText(tone, t)}
        </Badge>
        {streaming && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Radio className="size-3.5 text-primary" aria-hidden="true" /> {t("live")}
          </span>
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
          className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      <Card className="gap-3 p-5">
        <p className="whitespace-pre-wrap text-sm text-foreground">{job.goal}</p>
        <Separator />
        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs sm:grid-cols-4">
          <Meta label={t("metaCreated")} value={formatWhen(job.createdAt)} />
          <Meta label={t("metaStarted")} value={formatWhen(job.startedAt)} />
          <Meta
            label={job.terminal ? t("metaFinished") : t("metaDeadline")}
            value={formatWhen(job.terminal ? job.finishedAt : job.deadlineAt)}
          />
          <Meta label={t("metaDuration")} value={duration ?? "—"} />
          <Meta label={t("metaTurns")} value={`${job.turnsUsed}/${job.maxTurns}`} />
          <Meta label={t("metaCost")} value={job.costUsd != null ? `$${job.costUsd}` : "—"} />
          <Meta
            label={t("metaOrigin")}
            value={job.origin === "SCHEDULE" ? t("originSchedule") : job.origin === "EVENT" ? t("originEvent") : t("originManual")}
          />
          <Meta label={t("metaRequestedBy")} value={job.requestedBy || "—"} />
        </dl>
      </Card>

      {/* A parked job is WAITING ON A HUMAN, not stuck - the server exposes
          parkedAt/approvalDeadlineAt precisely so this page can say which. */}
      {parked && (
        <Card className="gap-1 border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] p-4">
          <p className="text-sm font-medium text-foreground">{t("awaitingTitle")}</p>
          <p className="text-xs text-muted-foreground">
            {t("parkedAt", { when: formatWhen(job.parkedAt) })}
            {job.approvalDeadlineAt ? ` · ${t("expiresAt", { when: formatWhen(job.approvalDeadlineAt) })}` : ""} —{" "}
            {t("approveUnder")}{" "}
            <Link className="underline" href="/admin/maintenance">
              {t("approvalsLink")}
            </Link>
            .
          </p>
        </Card>
      )}

      {!job.terminal && job.progressNote && (
        <Card className="gap-1 p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("latestProgress")}</p>
          <p className="text-sm text-foreground">{job.progressNote}</p>
          <p className="text-xs text-muted-foreground">{formatWhen(job.progressUpdatedAt)}</p>
        </Card>
      )}

      {job.status === "FAILED" && (
        <Card className="gap-1 border-destructive/40 p-4">
          <p className="text-sm font-medium text-destructive">{job.errorCode || t("status_FAILED")}</p>
          {job.errorMessage && <p className="text-sm text-muted-foreground">{job.errorMessage}</p>}
        </Card>
      )}

      {job.terminal && (job.resultText || job.resultJson) && (
        <Card className="gap-3 p-5">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("result")}</p>
          {/* RichText, not a pre-wrapped <p>: links in a result are clickable
              (http/https only) and markdown reads as written. */}
          {job.resultText && <RichText text={job.resultText} className="text-foreground" />}
          <ResearchReportLink jobId={job.id} resultJson={job.resultJson} />
          {job.resultJson && (
            <pre className="overflow-x-auto rounded-md bg-muted/50 p-3 text-xs text-foreground">
              {JSON.stringify(job.resultJson, null, 2)}
            </pre>
          )}
        </Card>
      )}

      <Card className="gap-3 p-5">
        <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <ScrollText className="size-4" aria-hidden="true" /> {t("trace")}
        </p>
        {entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {job.terminal ? t("traceNone") : t("traceWaiting")}
          </p>
        ) : (
          <ol className="space-y-2">
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
                  {e.durationMs != null && (
                    <span className="ml-2 tabular-nums text-xs text-muted-foreground">{e.durationMs}ms</span>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
      </Card>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{value}</dd>
    </div>
  );
}
