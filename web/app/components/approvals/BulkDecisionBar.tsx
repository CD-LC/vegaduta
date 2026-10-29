"use client";

import { useEffect, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import RiskBadge from "./RiskBadge";
import type { ApprovalRequestItem } from "./approvalsModel";
import { bulkEligibility, redactedArguments } from "./approvalsQueueModel";

export type BulkMode = "approve" | "deny";
export interface BulkResult {
  done: number;
  failed: Array<{ id: string; message: string }>;
}

/** One-line view of a call's arguments for the confirmation list. */
function argsLine(item: ApprovalRequestItem): string {
  const text = redactedArguments(item.payload, 400).text;
  return text ? text.replace(/\s+/g, " ").trim() : "";
}

/**
 * Bulk approve / deny for ticked pending tool calls. Both need every ticked request to be a call to the SAME
 * tool, and approving also needs that tool to read as low-risk (name-based estimate). The confirmation names the
 * tool, the count and every call's arguments, and says what will happen, before anything runs.
 */
export default function BulkDecisionBar({
  selected,
  onRun,
  onClear,
}: {
  selected: ApprovalRequestItem[];
  /** Decide each request one after another; report progress through onProgress(done). */
  onRun: (mode: BulkMode, reason: string | null, onProgress: (done: number) => void) => Promise<BulkResult>;
  onClear: () => void;
}) {
  const t = useTranslations("approvalsQueue");
  const [mode, setMode] = useState<BulkMode | null>(null);
  const [reason, setReason] = useState("");
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<BulkResult | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  // The selection changed under an open dialog: close it rather than describe a stale set.
  const ids = selected.map((s) => s.id).join(",");
  useEffect(() => {
    if (!running) setMode(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids]);

  if (selected.length === 0 && !result) return null;

  const approveCheck = bulkEligibility(selected, "approve");
  const denyCheck = bulkEligibility(selected, "deny");
  const reasonKey = (check: ReturnType<typeof bulkEligibility>): string | null => {
    if (!("reason" in check)) return null;
    switch (check.reason) {
      case "mixedTools":
        return "bulkMixedTools";
      case "notLowRisk":
        return "bulkNotLowRisk";
      case "notToolCall":
        return "bulkNotToolCall";
      case "notPending":
        return "bulkNotPending";
      default:
        return null;
    }
  };
  const approveWhy = reasonKey(approveCheck);
  const denyWhy = reasonKey(denyCheck);
  const tool = "tool" in denyCheck ? denyCheck.tool : "tool" in approveCheck ? approveCheck.tool : "";

  const open = (next: BulkMode, e: React.MouseEvent<HTMLButtonElement>) => {
    openerRef.current = e.currentTarget;
    setReason("");
    setResult(null);
    setProgress(0);
    setMode(next);
  };

  const runConfirmed = async () => {
    if (!mode) return;
    setRunning(true);
    setProgress(0);
    try {
      const res = await onRun(mode, mode === "deny" ? reason.trim() || null : null, setProgress);
      setResult(res);
      setMode(null);
    } finally {
      setRunning(false);
    }
  };

  const check = mode === "approve" ? approveCheck : denyCheck;
  const shown = selected.slice(0, 8);

  return (
    <div className="border-b border-border bg-muted/30 px-4 py-3" data-testid="bulk-bar">
      {selected.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium" aria-live="polite">
            {t("bulkSelected", { count: selected.length })}
          </span>
          <Button
            type="button"
            size="sm"
            variant="destructive"
            disabled={!denyCheck.ok || running}
            onClick={(e) => open("deny", e)}
            data-testid="bulk-deny"
          >
            {t("bulkDeny")}
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={!approveCheck.ok || running}
            onClick={(e) => open("approve", e)}
            data-testid="bulk-approve"
          >
            {t("bulkApprove")}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onClear} disabled={running}>
            {t("bulkClear")}
          </Button>
        </div>
      )}
      {selected.length > 0 && (approveWhy || denyWhy) && (
        <p className="mt-1.5 text-xs text-muted-foreground" data-testid="bulk-hint">
          {approveWhy && denyWhy ? t(denyWhy) : approveWhy ? t(approveWhy) : ""}
        </p>
      )}
      {result && (
        <div className="mt-1" role="status" data-testid="bulk-result">
          <p className="text-sm">{t("bulkResult", { done: result.done, failed: result.failed.length })}</p>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => setResult(null)}
            data-testid="bulk-result-dismiss"
          >
            {t("bulkDismiss")}
          </Button>
          {result.failed.length > 0 && (
            <ul className="mt-1 list-disc ps-5 text-xs text-destructive" role="alert">
              {result.failed.map((f) => (
                <li key={f.id}>
                  <code className="font-mono">{f.id.slice(0, 8)}</code>: {f.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <AlertDialog
        open={mode !== null}
        onOpenChange={(o) => {
          if (!running && !o) setMode(null);
        }}
      >
        <AlertDialogContent
          onCloseAutoFocus={(e) => {
            // Return focus to the control that opened the dialog.
            if (openerRef.current) {
              e.preventDefault();
              openerRef.current.focus();
            }
          }}
        >
          <AlertDialogHeader>
            <AlertDialogTitle>
              {mode === "deny"
                ? t("bulkConfirmDenyTitle", { count: selected.length, tool })
                : t("bulkConfirmApproveTitle", { count: selected.length, tool })}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {mode === "deny" ? t("bulkConfirmDenyBody", { tool }) : t("bulkConfirmApproveBody", { tool })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="grid gap-2">
            {"tool" in check && (
              <div className="flex items-center gap-2 text-xs">
                <code className="font-mono">{check.tool}</code>
                <RiskBadge risk={check.risk} />
              </div>
            )}
            <ul className="grid max-h-40 gap-1 overflow-auto text-xs" data-testid="bulk-confirm-list">
              {shown.map((s) => (
                <li key={s.id} className="truncate font-mono text-muted-foreground" title={argsLine(s)}>
                  {argsLine(s) || t("previewNoArguments")}
                </li>
              ))}
              {selected.length > shown.length && (
                <li className="text-muted-foreground">{t("bulkMore", { count: selected.length - shown.length })}</li>
              )}
            </ul>
            {mode === "deny" && (
              <>
                <label htmlFor="bulk-deny-reason" className="text-xs font-medium">
                  {t("denyReasonLabel")}
                </label>
                <Textarea
                  id="bulk-deny-reason"
                  value={reason}
                  maxLength={2000}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder={t("denyReasonPlaceholder")}
                  aria-describedby="bulk-deny-reason-help"
                />
                <p id="bulk-deny-reason-help" className="text-xs text-muted-foreground">
                  {t("denyReasonHelp")}
                </p>
              </>
            )}
            {running && (
              <p
                role="status"
                className="flex items-center gap-2 text-xs text-muted-foreground"
                data-testid="bulk-progress"
              >
                <Loader2 size={12} className="animate-spin" aria-hidden="true" />
                {t("bulkProgress", { done: progress, count: selected.length })}
              </p>
            )}
          </div>
          <AlertDialogFooter>
            <Button type="button" variant="outline" disabled={running} onClick={() => setMode(null)}>
              {t("bulkCancel")}
            </Button>
            <Button
              type="button"
              variant={mode === "deny" ? "destructive" : "default"}
              disabled={running || !("tool" in check)}
              onClick={() => void runConfirmed()}
              data-testid="bulk-confirm"
            >
              {mode === "deny"
                ? t("bulkConfirmDeny", { count: selected.length })
                : t("bulkConfirmApprove", { count: selected.length })}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
