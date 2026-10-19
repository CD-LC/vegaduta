"use client";

// Renders lib/voice/callPreflight.ts's findings: one plain line per missing
// piece, at most one action each. Renders NOTHING when there is nothing to
// say, so a working call stays as quiet as it was before 2026-09-19.
//
// Copy is plain English from callPreflight.ts, not t(): web/messages/*.json
// has no keys for it, and a missing key renders its raw path to the user
// (same precedent as the rest of the edge call UI in CallPanel).

import { AlertCircle, Download, Info, Loader2, RefreshCw, Volume2 } from "lucide-react";
import { Button } from "../../ui/button";
import type { PreflightActionKind, PreflightIssue } from "../../../lib/voice/callPreflight";

interface CallPreflightProps {
  issues: PreflightIssue[];
  onAction: (kind: PreflightActionKind) => void;
  /** Disable the action buttons (e.g. while a turn is in flight). */
  disabled?: boolean;
}

function actionIcon(kind: PreflightActionKind) {
  switch (kind) {
    case "download-stt":
    case "download-tts":
      return <Download size={12} />;
    case "recheck-server":
      return <RefreshCw size={12} />;
    case "test-mic":
      return <Volume2 size={12} />;
    default:
      return null;
  }
}

export default function CallPreflight({ issues, onAction, disabled }: CallPreflightProps) {
  if (!issues || issues.length === 0) return null;
  return (
    <div className="flex flex-col gap-1.5" role="status" aria-live="polite">
      {issues.map((issue) => {
        const downloading = typeof issue.progressPercent !== "undefined";
        const stateVar =
          issue.severity === "blocking"
            ? "var(--state-error)"
            : issue.severity === "degraded"
              ? "var(--state-warning)"
              : "var(--state-idle)";
        return (
          <div
            key={issue.id}
            className="flex flex-col gap-1.5 rounded-lg border border-border bg-secondary/40 px-2.5 py-2 text-xs text-muted-foreground"
          >
            <div className="flex items-start gap-1.5">
              <span className="mt-0.5 shrink-0" style={{ color: stateVar }} aria-hidden="true">
                {downloading ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : issue.severity === "info" ? (
                  <Info size={14} />
                ) : (
                  <AlertCircle size={14} />
                )}
              </span>
              <span>{issue.line}</span>
            </div>
            {downloading && typeof issue.progressPercent === "number" && (
              <div className="state-meter" style={{ ["--state" as any]: "var(--state-success)" } as any}>
                <span style={{ width: `${issue.progressPercent}%` }} />
              </div>
            )}
            {issue.action && (
              <div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={disabled}
                  onClick={() => onAction(issue.action!.kind)}
                >
                  {actionIcon(issue.action.kind)}
                  {issue.action.label}
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
