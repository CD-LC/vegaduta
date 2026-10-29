"use client";

import { useTranslations } from "../../lib/i18n";
import { useToolLabel } from "../../lib/toolLabels";
import { Button } from "../ui/button";
import { redactedArguments } from "../approvals/approvalsQueueModel";
import { toolNameOf, type ToolApprovalRow } from "./toolApprovalMatching";

/** Which call an "Approve all" / "Reject all" run is deciding. */
export type BulkProgress = { approve: boolean; position: number; total: number };
/** Why the last bulk run stopped before the end. */
export type BulkStop = "truncated" | "error";

const LIST_PREVIEW_CHARS = 160;

/**
 * The "Approve all N" / "Reject all" row under the current approval card (PendingToolApprovals), shown when more
 * than one call is waiting. It only starts the run; PendingToolApprovals decides the calls one after another with
 * the same requests a single card uses. A short list of what is waiting sits behind a disclosure, so approving all
 * of them is never approving calls the person had no way to see.
 */
export default function ToolApprovalBulkBar({
  waiting,
  canApproveAll,
  busy,
  progress,
  stopped,
  onApproveAll,
  onRejectAll,
}: {
  /** The waiting calls, oldest first (the current card is the first). */
  waiting: ToolApprovalRow[];
  /** False when the current call cannot be approved from here (arguments too long to show). */
  canApproveAll: boolean;
  busy: boolean;
  progress: BulkProgress | null;
  stopped: BulkStop | null;
  onApproveAll: () => void;
  onRejectAll: () => void;
}) {
  const t = useTranslations("toolApproval");
  const labelOf = useToolLabel();
  return (
    <div className="border-t border-border bg-secondary/20 px-3 py-2 text-xs" data-testid="tool-approval-bulk">
      {progress ? (
        <p role="status" className="font-medium text-foreground" data-testid="tool-approval-bulk-progress">
          {t(progress.approve ? "approvingProgress" : "rejectingProgress", {
            position: progress.position,
            total: progress.total,
          })}
        </p>
      ) : waiting.length > 1 ? (
        <div className="flex flex-wrap items-center gap-2">
          {canApproveAll && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              data-testid="tool-approval-approve-all"
              disabled={busy}
              onClick={onApproveAll}
            >
              {t("approveAll", { count: waiting.length })}
            </Button>
          )}
          <Button
            type="button"
            size="sm"
            variant="ghost"
            data-testid="tool-approval-reject-all"
            disabled={busy}
            onClick={onRejectAll}
          >
            {t("rejectAll")}
          </Button>
          <span className="text-muted-foreground">{t("bulkHint")}</span>
        </div>
      ) : null}
      {stopped && !progress && (
        <p className="mt-1.5 text-muted-foreground" data-testid="tool-approval-bulk-stopped">
          {t(stopped === "truncated" ? "bulkStoppedTruncated" : "bulkStoppedError")}
        </p>
      )}
      <details className="mt-1.5" data-testid="tool-approval-waiting-list">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
          {t("waitingList")} ({waiting.length})
        </summary>
        <ol className="mt-1 list-decimal space-y-1 ps-5">
          {waiting.map((card) => {
            const named = labelOf(toolNameOf(card));
            const args = redactedArguments(card.payload, LIST_PREVIEW_CHARS).text;
            return (
              <li key={card.id}>
                <span className="font-medium text-foreground" title={named.raw}>
                  {named.label}
                </span>
                {args && <span className="ms-1 break-all font-mono text-[11px] text-muted-foreground">{args}</span>}
              </li>
            );
          })}
        </ol>
      </details>
    </div>
  );
}
