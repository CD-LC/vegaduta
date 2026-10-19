"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Ban, Check, Download, Loader2, RotateCcw, X } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import {
  allowedActions,
  formatElapsed,
  isTerminalStatus,
  statusTone,
  type CodingTask,
  type CodingTaskDiff,
} from "../../lib/codingTasks";
import ConfirmDelete from "../dataTables/ConfirmDelete";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import DiffViewer from "./DiffViewer";

/**
 * One coding task: live progress (checkpoints, test runs, review verdict), the diff, and the human gate. The
 * Approve button sends the hash of the diff that is ON SCREEN, so the server only accepts a decision about exactly
 * what the person looked at (409 if the change moved). Approve is never enabled until that diff has loaded.
 */
export default function CodingTaskDetail({
  task,
  onChange,
}: {
  task: CodingTask;
  onChange: (task: CodingTask) => void;
}) {
  const t = useTranslations("codingTasks");
  const [diff, setDiff] = useState<CodingTaskDiff | null>(null);
  const [diffFailed, setDiffFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const actions = allowedActions(task);

  // Load (or reload) the diff whenever a different one is on offer. Cleared when there is none.
  useEffect(() => {
    if (!task.diffAvailable || !task.diffHash) {
      setDiff(null);
      setDiffFailed(false);
      return undefined;
    }
    if (diff?.diffHash === task.diffHash) return undefined;
    let live = true;
    setDiffFailed(false);
    api
      .getCodingTaskDiff(task.taskId)
      .then((d: CodingTaskDiff) => {
        if (live) setDiff(d);
      })
      .catch(() => {
        if (live) setDiffFailed(true);
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `diff` is read only to skip a reload of the same hash
  }, [task.taskId, task.diffAvailable, task.diffHash]);

  const run = useCallback(
    async (name: string, call: () => Promise<CodingTask | void>, okKey?: string) => {
      if (busy) return;
      setBusy(name);
      try {
        const updated = await call();
        if (updated && typeof updated === "object" && "taskId" in updated) onChange(updated as CodingTask);
        if (okKey) toast.success(t(okKey));
      } catch (e: any) {
        // 404/409 arrive quietly with the server's sentence; show it, then refresh what the task now is.
        if (e?.status === 409 || e?.status === 404) {
          toast.info(String(e?.message ?? ""));
          try {
            onChange(await api.getCodingTask(task.taskId));
          } catch {
            /* the next poll will catch up */
          }
        }
      } finally {
        setBusy(null);
      }
    },
    [busy, onChange, t, task.taskId]
  );

  const inSync = !!diff && diff.diffHash === task.diffHash;

  const approve = () => {
    if (!diff || !inSync) return;
    void run("approve", () => api.approveCodingTask(task.taskId, diff.diffHash), "approveSent");
  };

  const downloadPatch = () => {
    if (!diff) return;
    const blob = new Blob([diff.diff], { type: "text/x-diff" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `coding-task-${task.taskId.slice(0, 8)}.patch`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const live = !isTerminalStatus(task.status);

  return (
    <Card data-testid="coding-task-detail">
      <CardContent className="flex flex-col gap-4 pt-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="state" state={`var(--state-${statusTone(task.status)})`}>
                {t(`status_${task.status}`)}
              </Badge>
              {live && task.status !== "AWAITING_APPROVAL" && (
                <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                  <Loader2 size={12} className="animate-spin" aria-hidden="true" /> {t(`phase_${task.phase}`)}
                </span>
              )}
              <span className="text-xs text-muted-foreground">
                {t("usage", {
                  steps: task.usage.steps,
                  maxSteps: task.budget.maxSteps,
                  time: formatElapsed(task.usage.elapsedMs),
                })}
              </span>
            </div>
            <p className="mt-2 whitespace-pre-wrap text-sm text-foreground">{task.instruction}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {task.sourceKind === "GIT" ? task.repoUrl : t("sourceArchiveShort")} - <code>{task.testCommand}</code>
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            {actions.cancel && (
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={busy !== null || task.cancelRequested}
                onClick={() =>
                  void run("cancel", () => api.cancelCodingTask(task.taskId).then((): void => undefined), "cancelSent")
                }
              >
                <Ban size={14} aria-hidden="true" /> {t(task.cancelRequested ? "cancelling" : "cancel")}
              </Button>
            )}
            {actions.rollback && (
              <ConfirmDelete
                trigger={
                  <Button type="button" size="sm" variant="outline" disabled={busy !== null}>
                    <RotateCcw size={14} aria-hidden="true" /> {t("rollback")}
                  </Button>
                }
                title={t("rollbackTitle")}
                body={t("rollbackBody")}
                confirmLabel={t("rollbackConfirm")}
                cancelLabel={t("cancelDialog")}
                onConfirm={() => run("rollback", () => api.rollbackCodingTask(task.taskId), "rolledBack")}
              />
            )}
          </div>
        </div>

        {task.message && (
          <Alert variant={task.status === "FAILED" ? "destructive" : "default"}>
            {task.status === "FAILED" && <AlertTriangle aria-hidden="true" />}
            <AlertDescription>{task.message}</AlertDescription>
          </Alert>
        )}

        {task.status === "AWAITING_APPROVAL" && (
          <Alert variant="warning" data-testid="coding-approval-note">
            <AlertTriangle aria-hidden="true" />
            <AlertDescription>{t("approvalNote")}</AlertDescription>
          </Alert>
        )}
        {task.testFilesTouched && task.diffAvailable && (
          <Alert variant="warning" data-testid="coding-test-files-note">
            <AlertTriangle aria-hidden="true" />
            <AlertDescription>{t("testFilesNote")}</AlertDescription>
          </Alert>
        )}

        {task.review && (
          <p className="text-sm text-foreground" data-testid="coding-review">
            <span className="font-medium">{t(task.review.approved ? "reviewApproved" : "reviewRejected")}:</span>{" "}
            {task.review.notes}
          </p>
        )}

        {task.diffAvailable && (
          <section className="flex flex-col gap-3" aria-label={t("diffTitle")}>
            <h3 className="text-sm font-semibold text-foreground">
              {t("diffTitle")}{" "}
              <span className="font-normal text-muted-foreground">
                ({t("filesChanged", { count: task.diffFiles })})
              </span>
            </h3>
            {diff ? (
              <DiffViewer diff={diff.diff} />
            ) : diffFailed ? (
              <p className="text-sm text-destructive">{t("diffFailed")}</p>
            ) : (
              <p className="text-sm text-muted-foreground">{t("loading")}</p>
            )}
            <div className="flex flex-wrap gap-2">
              {actions.approve && (
                <Button
                  type="button"
                  size="sm"
                  disabled={!inSync || busy !== null}
                  onClick={approve}
                  data-testid="coding-approve"
                >
                  {busy === "approve" ? (
                    <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Check size={14} aria-hidden="true" />
                  )}{" "}
                  {t("approve")}
                </Button>
              )}
              {actions.reject && (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  data-testid="coding-reject"
                  onClick={() => void run("reject", () => api.rejectCodingTask(task.taskId), "rejectSent")}
                >
                  <X size={14} aria-hidden="true" /> {t("reject")}
                </Button>
              )}
              {actions.patch && diff && (
                <Button type="button" size="sm" variant="ghost" onClick={downloadPatch}>
                  <Download size={14} aria-hidden="true" /> {t("downloadPatch")}
                </Button>
              )}
            </div>
          </section>
        )}

        {task.tests.length > 0 && (
          <section className="flex flex-col gap-2" aria-label={t("testsTitle")}>
            <h3 className="text-sm font-semibold text-foreground">{t("testsTitle")}</h3>
            <ul className="flex flex-col gap-1">
              {task.tests.map((r, i) => (
                <li key={`${r.iteration}-${i}`}>
                  <details>
                    <summary className="cursor-pointer text-sm text-foreground">
                      {t(r.exitCode === 0 ? "testPassed" : "testFailed", { run: r.iteration, code: r.exitCode })}
                    </summary>
                    <pre className="mt-1 max-h-60 overflow-auto rounded-md border border-border bg-card p-2 font-mono text-xs">
                      {r.outputTail}
                    </pre>
                  </details>
                </li>
              ))}
            </ul>
          </section>
        )}

        {task.events.length > 0 && (
          <section className="flex flex-col gap-2" aria-label={t("progressTitle")}>
            <h3 className="text-sm font-semibold text-foreground">{t("progressTitle")}</h3>
            <ol className="flex flex-col gap-1 text-xs text-muted-foreground" data-testid="coding-events">
              {task.events.slice(-30).map((e, i) => (
                <li key={`${e.atMs}-${i}`}>
                  <span className="tabular-nums">{new Date(e.atMs).toLocaleTimeString()}</span> - {e.text}
                </li>
              ))}
            </ol>
          </section>
        )}
      </CardContent>
    </Card>
  );
}
