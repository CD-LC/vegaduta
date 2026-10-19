"use client";

// Shared status UI for the Universal Request Lifecycle framework
// (app/lib/requestLifecycle.ts) — renders all 9 states consistently so
// every call-site that adopts the lifecycle (chat streaming, on-device
// model calls, camera inference, video-gen polling — none of those wired
// yet, see requestLifecycle.ts's header) gets the same look for "connecting…
// / streaming… / timed out / failed / cancelled" instead of each surface
// inventing its own spinner-or-nothing treatment.
//
// Deliberately plain-English literal strings (no i18n hookup) — this is
// shared reliability chrome, not a public-facing marketing surface, and
// several sibling components in this repo (e.g. studio/GenerateVideoForm.tsx)
// take the same approach for status text. Wire it to app/lib/i18n's
// useTranslations() later if a call-site needs localized copy.

import type { ComponentType } from "react";
import { CheckCircle2, Clock, Loader2, PlugZap, TimerOff, X, XCircle } from "lucide-react";
import { Button } from "../ui/button";
import { cn } from "../../lib/utils";
import type { RequestLifecycleSnapshot, RequestLifecycleState } from "../../lib/requestLifecycle";

export interface RequestStatusIndicatorProps {
  /** null/undefined = no request has started yet (renders nothing, unless
   * `showIdle` is set). */
  snapshot: RequestLifecycleSnapshot | null | undefined;
  /** Wired to controller.cancel() by the caller. Omit to render a
   * read-only indicator with no Cancel button even while cancellable. */
  onCancel?: (reason?: string) => void;
  className?: string;
  /** Icon only, no label text (still shows Cancel + error tooltip-via-title). */
  compact?: boolean;
  /** Render the IDLE placeholder instead of returning null when there's no
   * snapshot yet. Off by default so this component is a safe no-op to drop
   * into a layout before a request ever starts. */
  showIdle?: boolean;
}

interface StateMeta {
  label: string;
  icon: ComponentType<{ size?: number; className?: string; "aria-hidden"?: boolean }>;
  tone: string;
  spinning?: boolean;
}

const STATE_META: Record<RequestLifecycleState, StateMeta> = {
  // Design-system --state-* tokens (globals.css) via Tailwind's arbitrary-
  // value syntax, not raw palette classes - those tokens are already
  // AA-checked per mode and driven by the app's own data-mode switch, unlike
  // a bare `dark:` utility (no @custom-variant is registered for it here, so
  // `dark:` tracks the OS's prefers-color-scheme instead of the in-app
  // theme toggle).
  IDLE: { label: "Idle", icon: Clock, tone: "text-muted-foreground" },
  QUEUED: { label: "Queued…", icon: Clock, tone: "text-muted-foreground" },
  CONNECTING: { label: "Connecting…", icon: PlugZap, tone: "text-[var(--state-running)]", spinning: true },
  PROCESSING: { label: "Processing…", icon: Loader2, tone: "text-[var(--state-running)]", spinning: true },
  STREAMING: { label: "Streaming…", icon: Loader2, tone: "text-[var(--state-running)]", spinning: true },
  COMPLETED: { label: "Completed", icon: CheckCircle2, tone: "text-[var(--state-success)]" },
  TIMED_OUT: { label: "Timed out", icon: TimerOff, tone: "text-[var(--state-warning)]" },
  FAILED: { label: "Failed", icon: XCircle, tone: "text-destructive" },
  CANCELLED: { label: "Cancelled", icon: X, tone: "text-muted-foreground" },
};

const IN_FLIGHT_STATES: RequestLifecycleState[] = ["CONNECTING", "PROCESSING", "STREAMING"];

/**
 * Renders the current RequestLifecycleState with a consistent icon/label/
 * tone, an optional retry-count hint, the failure/timeout message when
 * present, and a real Cancel button whenever the snapshot says it's safe to
 * cancel (`snapshot.cancellable`) and the caller passed `onCancel`.
 */
export function RequestStatusIndicator({
  snapshot,
  onCancel,
  className,
  compact = false,
  showIdle = false,
}: RequestStatusIndicatorProps) {
  if (!snapshot && !showIdle) return null;

  const state = snapshot?.state ?? "IDLE";
  const meta = STATE_META[state];
  const Icon = meta.icon;
  const showCancel = Boolean(onCancel) && Boolean(snapshot?.cancellable);
  const message = snapshot?.error && (state === "FAILED" || state === "TIMED_OUT") ? snapshot.error : null;
  const showRetryHint = Boolean(snapshot) && snapshot!.attempt > 1 && snapshot!.maxAttempts > 1 && IN_FLIGHT_STATES.includes(state);

  return (
    <div className={cn("flex min-w-0 items-center gap-2 text-sm", className)} role="status" aria-live="polite">
      <Icon size={14} className={cn("shrink-0", meta.tone, meta.spinning && "animate-spin")} aria-hidden={true} />
      {!compact && (
        <span className={cn("font-medium whitespace-nowrap", meta.tone)}>
          {meta.label}
          {showRetryHint && (
            <span className="ml-1 text-xs font-normal text-muted-foreground">
              (retry {snapshot!.attempt}/{snapshot!.maxAttempts})
            </span>
          )}
        </span>
      )}
      {message && (
        <span className="min-w-0 truncate text-xs text-muted-foreground" title={message}>
          {message}
        </span>
      )}
      {showCancel && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-6 shrink-0 gap-1 px-2 text-xs"
          onClick={() => onCancel?.("Cancelled by user")}
        >
          <X size={12} aria-hidden={true} />
          Cancel
        </Button>
      )}
    </div>
  );
}
