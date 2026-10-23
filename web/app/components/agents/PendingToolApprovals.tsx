"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ShieldQuestion, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { useToolLabel } from "../../lib/toolLabels";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import ExpiryCountdown from "../approvals/ExpiryCountdown";
import RiskBadge from "../approvals/RiskBadge";
import { useApprovalExpiryTtl } from "../approvals/approvalExpiry";
import { redactedArguments, toolRisk } from "../approvals/approvalsQueueModel";
import type { ApprovalRequestItem } from "../approvals/approvalsModel";
import {
  ARGS_PREVIEW_MAX_CHARS,
  approvalArgsTruncated,
  matchSessionApprovals,
  outcomeOf,
  toolApprovalPollDelayMs,
  toolNameOf,
  type ToolApprovalOutcome,
  type ToolApprovalRow,
} from "./toolApprovalMatching";
import ToolApprovalBulkBar, { type BulkProgress, type BulkStop } from "./ToolApprovalBulkBar";

/**
 * Inline tool-confirmation cards for a chat surface (ChatPanel and
 * AgentPlayground mount the same component).
 *
 * When the confirmation gate parks a tool call, the model only gets a tool
 * result saying "requires human approval" and the request lands in
 * /admin/approvals - a page the person chatting may never have opened. This
 * shows the parked call where it was asked for: what tool, with what
 * arguments, Approve / Reject, and then the replayed result.
 *
 * A card also shows an estimated risk, a countdown when the workspace expires waiting requests, and an optional
 * reason for rejecting that the agent reads as the result of the refused call.
 *
 * When several calls wait, "Approve all N" / "Reject all" (ToolApprovalBulkBar) decides them one after another
 * with the same per-request calls, stopping at the first problem; a call whose arguments are too long to show is
 * never approved that way (decideAll).
 *
 * Deliberately small in scope: no auto-approve and no "always allow". The
 * server stays the only authority - deciding a tool call needs
 * MANAGE_APPROVALS (ApprovalController#enforceApproverAuthorization), so a
 * member without it gets a 403 and the card turns into a pointer to the
 * people who can decide.
 *
 * Arguments and results are model- or tool-authored text. They are rendered
 * as text nodes inside <pre> only - never as HTML or markdown.
 *
 * Deciding a card does not hand anything back to the agent from the browser:
 * the turn that asked is long over, and core itself carries the task on once
 * the decision has an outcome (it starts a turn that restates the request and
 * delivers the tool's result or the denial). The card reports each decision
 * through onDecided(outcome, requestId) so the surface can follow that turn
 * (useApprovalContinuation) - show it carrying on, hold the input back, append
 * the agent's reply.
 */

/**
 * First poll interval while something is waiting - an admin may decide it
 * from the queue. Later polls back off (toolApprovalPollDelayMs) and are
 * skipped while the tab is hidden.
 */
export const TOOL_APPROVAL_POLL_MS = 10_000;

// A background poll must never raise the global error toast: a blip on a
// 10-second timer would otherwise pop an alarm over a chat that is fine.
const QUIET_READ = [401, 403, 404, 429, 500, 502, 503, 504];
// 403 = caller may not decide tool calls; 404/409 = someone else already did.
// The rest are quiet because the card reports every failure inline, next to
// the call it is about: a 5xx on approve usually means the decision WAS
// recorded and the replayed tool then failed (core rethrows after recording),
// which the card resolves by re-reading the request - a global "request
// failed" toast on top of that would say the wrong thing.
const QUIET_DECIDE = [400, 403, 404, 409, 423, 429, 500, 502, 503, 504];

type LocalOutcome = ToolApprovalOutcome | { kind: "error"; detail: string | null };

/**
 * A card whose call ran cleanly ("Done") closes itself after this long; the
 * result it carried has been handed to the agent by core, which continues the
 * task (the surface shows that, not this card), so the card is only a receipt.
 * Failed and rejected cards stay until the person closes them - that outcome
 * is the only place the error is shown.
 */
export const DONE_AUTO_CLOSE_MS = 6_000;

/** Moves focus into the reason field when it opens (the person just asked for it). */
function focusOnMount(el: HTMLInputElement | null): void {
  el?.focus();
}

/** A transport error is not a decision: such a card is still waiting. */
function unresolved(outcome: LocalOutcome | undefined): boolean {
  return !outcome || outcome.kind === "error";
}

export default function PendingToolApprovals({
  agentId,
  sessionId,
  turnInFlight = false,
  className,
  autoCloseMs,
  onDecided,
  onWaitingChange,
}: {
  agentId: string | null | undefined;
  sessionId: string | null | undefined;
  /** True while a chat turn is streaming; the list is re-read when it ends. */
  turnInFlight?: boolean;
  className?: string;
  /** How long a clean "Done" card stays before closing itself (tests shorten it). */
  autoCloseMs?: number;
  /**
   * Called once per request when its decision has an outcome (ran, failed, rejected), whether the person
   * decided it on this card or it was decided elsewhere (the approvals queue). Core continues the task by
   * itself after every outcome; the surface uses this to follow that continuation, not to start one.
   */
  onDecided?: (outcome: ToolApprovalOutcome, requestId: string) => void;
  /** Called with the number of tool calls waiting for a decision, whenever it changes (0 when none). */
  onWaitingChange?: (waiting: number) => void;
}) {
  const t = useTranslations("toolApproval");
  const q = useTranslations("approvalsQueue");
  const labelOf = useToolLabel();
  const ttlSeconds = useApprovalExpiryTtl();
  // Optional reason per request for a rejection, and which cards have the reason field open.
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [reasonOpen, setReasonOpen] = useState<Set<string>>(() => new Set());
  const [cards, setCards] = useState<ToolApprovalRow[]>([]);
  const [outcomes, setOutcomes] = useState<Record<string, LocalOutcome>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  // An "Approve all" / "Reject all" run in progress (which call it is on), and why the last one stopped early.
  const [bulk, setBulk] = useState<BulkProgress | null>(null);
  const [bulkStop, setBulkStop] = useState<BulkStop | null>(null);
  const [needsAdmin, setNeedsAdmin] = useState(false);
  // Cards the person closed (or that closed themselves). Per conversation.
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set());
  // The conversation the state above belongs to. A reply to a request made
  // for an earlier conversation is dropped instead of landing in this one.
  const scopeRef = useRef("");
  const scope = agentId && sessionId ? `${agentId}::${sessionId}` : "";
  const cardsRef = useRef<ToolApprovalRow[]>([]);
  cardsRef.current = cards;
  const outcomesRef = useRef<Record<string, LocalOutcome>>({});
  outcomesRef.current = outcomes;
  const onDecidedRef = useRef(onDecided);
  onDecidedRef.current = onDecided;

  const refresh = useCallback(async () => {
    if (!agentId || !sessionId) return;
    const requestScope = `${agentId}::${sessionId}`;
    let pending: ToolApprovalRow[];
    try {
      const rows = await api.listApprovals("PENDING", QUIET_READ, true);
      pending = matchSessionApprovals<ToolApprovalRow>(Array.isArray(rows) ? rows : [], agentId, sessionId);
    } catch {
      return; // quiet by design - see QUIET_READ
    }
    if (scopeRef.current !== requestScope) return;

    const pendingIds = new Set(pending.map((r) => r.id));
    // Cards we were showing as waiting that are no longer in the pending
    // queue were decided somewhere else (the admin queue). Fetch each once
    // so the result still shows up in the conversation it belongs to.
    const decidedElsewhere = cardsRef.current.filter(
      (c) => !pendingIds.has(c.id) && unresolved(outcomesRef.current[c.id])
    );
    setCards((prev) => {
      const known = new Set(prev.map((c) => c.id));
      const added = pending.filter((r) => !known.has(r.id));
      return added.length > 0 ? [...prev, ...added] : prev;
    });
    for (const card of decidedElsewhere) {
      try {
        const latest = await api.getApproval(card.id, QUIET_READ, true);
        if (scopeRef.current !== requestScope) return;
        const outcome = outcomeOf(latest);
        if (outcome) {
          const wasWaiting = unresolved(outcomesRef.current[card.id]);
          setOutcomes((prev) => (unresolved(prev[card.id]) ? { ...prev, [card.id]: outcome } : prev));
          if (wasWaiting) onDecidedRef.current?.(outcome, card.id);
        }
      } catch {
        /* still unknown - the next poll tries again */
      }
    }
  }, [agentId, sessionId]);

  // New conversation: drop everything from the previous one, then read.
  useEffect(() => {
    scopeRef.current = scope;
    setCards([]);
    setOutcomes({});
    setBusyId(null);
    setBulk(null);
    setBulkStop(null);
    setNeedsAdmin(false);
    setDismissed(new Set());
    setReasons({});
    setReasonOpen(new Set());
    if (scope) void refresh();
  }, [scope, refresh]);

  const dismiss = useCallback((id: string) => {
    setDismissed((prev) => {
      if (prev.has(id)) return prev;
      const next = new Set(prev);
      next.add(id);
      return next;
    });
  }, []);

  // A clean "Done" closes itself after a moment (see DONE_AUTO_CLOSE_MS).
  const autoCloseDelay = autoCloseMs ?? DONE_AUTO_CLOSE_MS;
  const autoCloseScheduledRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    const scheduled = autoCloseScheduledRef.current;
    const started: Array<[string, ReturnType<typeof setTimeout>]> = [];
    for (const [id, outcome] of Object.entries(outcomes)) {
      if (outcome.kind !== "done" || scheduled.has(id)) continue;
      scheduled.add(id);
      started.push([id, setTimeout(() => dismiss(id), autoCloseDelay)]);
    }
    return () => {
      // Only timers created by THIS run are cancelled (and re-armed by the
      // next run); an unmount or a scope change drops the component state.
      for (const [id, timer] of started) {
        clearTimeout(timer);
        scheduled.delete(id);
      }
    };
  }, [outcomes, autoCloseDelay, dismiss]);

  // A turn just finished - the gate may have parked a call during it.
  // Falling edge only, so mounting (already covered above) does not read twice.
  const wasInFlightRef = useRef(turnInFlight);
  useEffect(() => {
    const was = wasInFlightRef.current;
    wasInFlightRef.current = turnInFlight;
    if (was && !turnInFlight && scopeRef.current) void refresh();
  }, [turnInFlight, refresh]);

  const waiting = cards.some((c) => unresolved(outcomes[c.id]));
  const waitingCount = cards.filter((c) => unresolved(outcomes[c.id])).length;
  const onWaitingChangeRef = useRef(onWaitingChange);
  onWaitingChangeRef.current = onWaitingChange;
  useEffect(() => {
    onWaitingChangeRef.current?.(waitingCount);
  }, [waitingCount]);
  useEffect(() => {
    if (!waiting || !scope) return undefined;
    const startedAt = Date.now();
    let timer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;
    const isHidden = () => typeof document !== "undefined" && document.hidden;
    const schedule = () => {
      if (stopped) return;
      const delay = toolApprovalPollDelayMs(Date.now() - startedAt);
      if (delay == null) return; // waited long enough - only a return to the tab reads again
      timer = setTimeout(() => {
        // A hidden tab does not poll; coming back to it reads once (below).
        if (!isHidden()) void refresh();
        schedule();
      }, delay);
    };
    schedule();
    const onVisibility = () => {
      if (!isHidden()) void refresh();
    };
    if (typeof document !== "undefined") document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      if (typeof document !== "undefined") document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [waiting, scope, refresh]);

  async function decide(card: ToolApprovalRow, approve: boolean) {
    if (busyId || bulk) return;
    setBulkStop(null);
    setBusyId(card.id);
    try {
      await decideOne(card, approve);
    } finally {
      setBusyId(null);
    }
  }

  /**
   * "Approve all N" / "Reject all": the waiting calls, oldest first, one request at a time through the same
   * approve/reject calls a single card uses. Stops at the first result that is not the expected one (an error, a
   * failed run, a 403, a call someone else decided differently) and - approving only - at a call whose arguments
   * are too long to show here: that one is never approved in bulk and is left for the person to decide.
   */
  async function decideAll(approve: boolean, queue: ToolApprovalRow[]) {
    if (busyId || bulk || queue.length === 0) return;
    const requestScope = scope;
    setBulkStop(null);
    try {
      for (let i = 0; i < queue.length; i++) {
        const card = queue[i];
        if (scopeRef.current !== requestScope) return;
        if (approve && approvalArgsTruncated(card.payload)) {
          setBulkStop("truncated");
          return;
        }
        setBulk({ approve, position: i + 1, total: queue.length });
        setBusyId(card.id);
        const result = await decideOne(card, approve);
        if (result === null || result === "needsAdmin") return;
        if (result.kind !== (approve ? "done" : "rejected")) {
          setBulkStop("error");
          return;
        }
      }
    } finally {
      if (scopeRef.current === requestScope) {
        setBusyId(null);
        setBulk(null);
      }
    }
  }

  /**
   * Decides one call and records its outcome. Returns what happened so a bulk run can stop on anything but a
   * clean result: the outcome, "needsAdmin" for a 403, or null when the conversation changed underneath (or the
   * call was decided elsewhere and its state could not be read yet).
   */
  async function decideOne(card: ToolApprovalRow, approve: boolean): Promise<LocalOutcome | "needsAdmin" | null> {
    const requestScope = scope;
    try {
      let latest: ToolApprovalRow = approve
        ? await api.approveRequest(card.id, undefined, undefined, QUIET_DECIDE)
        : await api.rejectRequest(card.id, undefined, reasons[card.id]?.trim() || null, QUIET_DECIDE);
      // The approve response can be serialised before the replayed tool's
      // result is attached; one follow-up read picks it up.
      if (approve && latest && !latest.executionResult && !latest.executionError) {
        try {
          const reread = await api.getApproval(card.id, QUIET_READ, true);
          if (reread) latest = reread;
        } catch {
          /* keep the approve response */
        }
      }
      if (scopeRef.current !== requestScope) return null;
      const outcome: ToolApprovalOutcome =
        outcomeOf(latest) ?? (approve ? { kind: "done", detail: null } : { kind: "rejected", detail: null });
      setOutcomes((prev) => ({ ...prev, [card.id]: outcome }));
      onDecided?.(outcome, card.id);
      return outcome;
    } catch (err: any) {
      if (scopeRef.current !== requestScope) return null;
      if (err?.status === 403) {
        setNeedsAdmin(true);
        return "needsAdmin";
      }
      // Any other failure: ask the server what state the request is really in
      // before saying anything. 404/409 mean someone else already decided it.
      // A 5xx on approve usually means the decision WAS recorded and the
      // replayed tool then failed (core records the failure and rethrows) -
      // offering "Approve and run" again there would invite a second run.
      const alreadyDecided = err?.status === 404 || err?.status === 409;
      let latest: ToolApprovalRow | null = null;
      try {
        latest = await api.getApproval(card.id, QUIET_READ, true);
      } catch {
        /* state unknown - handled below */
      }
      if (scopeRef.current !== requestScope) return null;
      let outcome: LocalOutcome | null = outcomeOf(latest);
      if (outcome && outcome.kind === "done" && approve && !alreadyDecided && !latest?.executionResult) {
        // Recorded as approved, but this call failed and no result is
        // attached: never present that as a clean "Done".
        outcome = { kind: "failed", detail: err?.message || null };
      }
      if (!outcome && !alreadyDecided) {
        // Still pending (or the re-read failed): nothing was decided, retry is safe.
        outcome = { kind: "error", detail: err?.message || null };
      }
      // alreadyDecided with an unreadable row: leave the card - the poll catches up.
      if (outcome) {
        const resolved: LocalOutcome = outcome;
        setOutcomes((prev) => ({ ...prev, [card.id]: resolved }));
        if (resolved.kind !== "error") onDecided?.(resolved, card.id);
      }
      return outcome;
    }
  }

  const visibleCards = cards.filter((card) => !dismissed.has(card.id));
  if (!scope || visibleCards.length === 0) return null;
  // One decision at a time, in the order the agent asked: only the oldest waiting call is shown with its
  // buttons; the rest wait behind it and the panel says how many. A decided call stays below as a receipt
  // (a clean Done closes itself). Several calls parked in one turn no longer arrive as a stack of cards.
  const waitingCards = visibleCards.filter((card) => unresolved(outcomes[card.id]));
  const current = waitingCards[0] ?? null;
  const receipts = visibleCards.filter((card) => !unresolved(outcomes[card.id]));

  const renderCard = (card: ToolApprovalRow) => {
    const tool = toolNameOf(card);
    // What the person reads ("Search the web"); the wire name stays one click away for experts.
    const named = labelOf(tool);
    const shownArgs = redactedArguments(card.payload, ARGS_PREVIEW_MAX_CHARS);
    const args = shownArgs.text;
    // The card shows less than what would run: no Approve from here.
    const argsTruncated = approvalArgsTruncated(card.payload);
    const outcome = outcomes[card.id];
    // A transport error is not a decision: the buttons stay so the person can retry.
    const decided = outcome && outcome.kind !== "error";
    return (
      <div key={card.id} data-testid={`tool-approval-${card.id}`} className="px-3 py-2.5 text-sm text-card-foreground">
        <div className="flex items-center gap-2">
          <ShieldQuestion size={14} className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate font-medium text-foreground" title={named.raw}>
            {decided ? t("decidedTitle", { tool: named.label }) : t("title", { tool: named.label })}
          </span>
          {!decided && <RiskBadge risk={toolRisk(tool)} />}
          {decided && (
            <button
              type="button"
              aria-label={t("close")}
              title={t("close")}
              data-testid={`tool-approval-close-${card.id}`}
              onClick={() => dismiss(card.id)}
              className="-mr-1 shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              <X size={14} className="size-3.5" aria-hidden="true" />
            </button>
          )}
        </div>
        {!decided && named.description && (
          <p className="mt-1 text-xs text-foreground" data-testid="tool-approval-does">
            {named.description}
          </p>
        )}
        {!decided && <p className="mt-1 text-xs text-muted-foreground">{t("notRunYet")}</p>}
        {!decided && named.label !== named.raw && (
          <details className="mt-1 text-xs text-muted-foreground" data-testid="tool-approval-raw-name">
            <summary className="cursor-pointer hover:text-foreground">{t("rawName")}</summary>
            <code className="font-mono text-[11px]">{named.raw}</code>
          </details>
        )}
        {!decided && (
          <ExpiryCountdown
            className="mt-1"
            ttlSeconds={ttlSeconds}
            item={
              { ...card, status: card.status ?? "PENDING", actionType: card.actionType ?? "" } as ApprovalRequestItem
            }
          />
        )}
        {args && (
          <pre
            data-testid="tool-approval-args"
            className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/30 px-2 py-1.5 font-mono text-[11px] text-muted-foreground"
          >
            {args}
          </pre>
        )}
        {!args && !decided && <p className="mt-1 text-xs text-muted-foreground">{t("noArguments")}</p>}
        {shownArgs.redactedCount > 0 && !decided && (
          <p className="mt-1 text-xs text-muted-foreground" data-testid="tool-approval-redacted">
            {q("previewRedacted", { count: shownArgs.redactedCount })}
          </p>
        )}
        {argsTruncated && !decided && (
          <p className="mt-1 text-xs text-muted-foreground" data-testid="tool-approval-args-truncated">
            {t("argsTruncated")}{" "}
            <Link href="/admin/approvals" className="font-medium text-foreground underline underline-offset-2">
              {t("openQueue")}
            </Link>
          </p>
        )}

        {outcome && (
          <div className="mt-2 space-y-1" data-testid={`tool-approval-outcome-${outcome.kind}`}>
            <p
              className={
                outcome.kind === "failed" || outcome.kind === "error"
                  ? "text-xs font-medium text-destructive"
                  : "text-xs font-medium text-foreground"
              }
            >
              {outcome.kind === "done" && t("done")}
              {outcome.kind === "failed" && t("failed")}
              {outcome.kind === "rejected" && t("rejected")}
              {outcome.kind === "error" && t("decideError")}
            </p>
            {outcome.detail && (
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/30 px-2 py-1.5 font-mono text-[11px] text-muted-foreground">
                {outcome.detail}
              </pre>
            )}
          </div>
        )}

        {!decided &&
          (needsAdmin ? (
            <p className="mt-2 text-xs text-muted-foreground" data-testid="tool-approval-needs-admin">
              {t("needsAdmin")}{" "}
              <Link href="/admin/approvals" className="font-medium text-foreground underline underline-offset-2">
                {t("openQueue")}
              </Link>
            </p>
          ) : (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              {!argsTruncated && (
                <Button
                  type="button"
                  size="sm"
                  data-testid="tool-approval-approve"
                  disabled={busyId != null}
                  onClick={() => void decide(card, true)}
                >
                  {busyId === card.id ? t("working") : t("approve")}
                </Button>
              )}
              <Button
                type="button"
                size="sm"
                variant="outline"
                data-testid="tool-approval-reject"
                disabled={busyId != null}
                onClick={() => void decide(card, false)}
              >
                {t("reject")}
              </Button>
              <button
                type="button"
                data-testid="tool-approval-reason-toggle"
                aria-expanded={reasonOpen.has(card.id)}
                aria-controls={`tool-approval-reason-${card.id}`}
                onClick={() =>
                  setReasonOpen((prev) => {
                    const next = new Set(prev);
                    if (next.has(card.id)) next.delete(card.id);
                    else next.add(card.id);
                    return next;
                  })
                }
                className="text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground"
              >
                {q("chatReasonToggle")}
              </button>
              <Link
                href={`/admin/approvals?id=${encodeURIComponent(card.id)}`}
                data-testid="tool-approval-queue-link"
                className="text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground"
              >
                {q("chatReviewInQueue")}
              </Link>
            </div>
          ))}
        {!decided && !needsAdmin && reasonOpen.has(card.id) && (
          <div className="mt-2 grid gap-1" id={`tool-approval-reason-${card.id}`}>
            <label htmlFor={`tool-approval-reason-input-${card.id}`} className="text-xs font-medium">
              {q("denyReasonLabel")}
            </label>
            <Input
              ref={focusOnMount}
              id={`tool-approval-reason-input-${card.id}`}
              data-testid="tool-approval-reason"
              value={reasons[card.id] ?? ""}
              maxLength={2000}
              disabled={busyId != null}
              aria-describedby={`tool-approval-reason-help-${card.id}`}
              placeholder={q("denyReasonPlaceholder")}
              onChange={(e) => setReasons((prev) => ({ ...prev, [card.id]: e.target.value }))}
            />
            <p id={`tool-approval-reason-help-${card.id}`} className="text-xs text-muted-foreground">
              {q("denyReasonHelp")}
            </p>
          </div>
        )}
      </div>
    );
  };

  return (
    <section
      aria-label={t("panelLabel")}
      className={
        className ??
        "flex w-full max-w-[85%] flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card"
      }
      data-testid="pending-tool-approvals"
    >
      {current && (
        <div aria-live="polite" data-testid="tool-approval-current">
          {waitingCards.length > 1 && (
            <p
              className="flex items-center justify-between gap-2 bg-secondary/40 px-3 py-1.5 text-xs text-muted-foreground"
              data-testid="tool-approval-queue-status"
            >
              <span className="font-medium text-foreground">
                {t("queuePosition", { position: 1, total: waitingCards.length })}
              </span>
              <span>{t("queueMore", { count: waitingCards.length - 1 })}</span>
            </p>
          )}
          {renderCard(current)}
          {!needsAdmin && (waitingCards.length > 1 || bulk || bulkStop) && (
            <ToolApprovalBulkBar
              waiting={waitingCards}
              canApproveAll={!approvalArgsTruncated(current.payload)}
              busy={busyId != null}
              progress={bulk}
              stopped={bulkStop}
              onApproveAll={() => void decideAll(true, waitingCards)}
              onRejectAll={() => void decideAll(false, waitingCards)}
            />
          )}
        </div>
      )}
      {receipts.map(renderCard)}
    </section>
  );
}
