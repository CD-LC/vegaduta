"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ShieldQuestion, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import {
  approvalArgsPreview,
  approvalArgsTruncated,
  matchSessionApprovals,
  outcomeOf,
  toolApprovalPollDelayMs,
  toolNameOf,
  type ToolApprovalOutcome,
  type ToolApprovalRow,
} from "./toolApprovalMatching";

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
 * Deliberately small in scope: no auto-approve and no "always allow". The
 * server stays the only authority - deciding a tool call needs
 * MANAGE_APPROVALS (ApprovalController#enforceApproverAuthorization), so a
 * member without it gets a 403 and the card turns into a pointer to the
 * people who can decide.
 *
 * Arguments and results are model- or tool-authored text. They are rendered
 * as text nodes inside <pre> only - never as HTML or markdown.
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
 * result it carried is already back in the agent's hands, so the card is
 * only a receipt. Failed and rejected cards stay until the person closes
 * them - that outcome is the only place the error is shown.
 */
export const DONE_AUTO_CLOSE_MS = 6_000;

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
}: {
  agentId: string | null | undefined;
  sessionId: string | null | undefined;
  /** True while a chat turn is streaming; the list is re-read when it ends. */
  turnInFlight?: boolean;
  className?: string;
  /** How long a clean "Done" card stays before closing itself (tests shorten it). */
  autoCloseMs?: number;
  /**
   * Called once a decision made on this card has an outcome (ran, failed, rejected). The
   * Assistant uses it to carry on a long task after the person approves a command.
   */
  onDecided?: (outcome: ToolApprovalOutcome) => void;
}) {
  const t = useTranslations("toolApproval");
  const [cards, setCards] = useState<ToolApprovalRow[]>([]);
  const [outcomes, setOutcomes] = useState<Record<string, LocalOutcome>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
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
        if (outcome) setOutcomes((prev) => (unresolved(prev[card.id]) ? { ...prev, [card.id]: outcome } : prev));
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
    setNeedsAdmin(false);
    setDismissed(new Set());
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
    if (busyId) return;
    const requestScope = scope;
    setBusyId(card.id);
    try {
      let latest: ToolApprovalRow = approve
        ? await api.approveRequest(card.id, undefined, undefined, QUIET_DECIDE)
        : await api.rejectRequest(card.id, undefined, null, QUIET_DECIDE);
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
      if (scopeRef.current !== requestScope) return;
      const outcome: ToolApprovalOutcome =
        outcomeOf(latest) ?? (approve ? { kind: "done", detail: null } : { kind: "rejected", detail: null });
      setOutcomes((prev) => ({ ...prev, [card.id]: outcome }));
      onDecided?.(outcome);
    } catch (err: any) {
      if (scopeRef.current !== requestScope) return;
      if (err?.status === 403) {
        setNeedsAdmin(true);
        return;
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
      if (scopeRef.current !== requestScope) return;
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
        if (resolved.kind !== "error") onDecided?.(resolved);
      }
    } finally {
      setBusyId(null);
    }
  }

  const visibleCards = cards.filter((card) => !dismissed.has(card.id));
  if (!scope || visibleCards.length === 0) return null;

  return (
    <div className={className ?? "flex w-full max-w-[85%] flex-col gap-2"} data-testid="pending-tool-approvals">
      {visibleCards.map((card) => {
        const tool = toolNameOf(card);
        const args = approvalArgsPreview(card.payload);
        // The card shows less than what would run: no Approve from here.
        const argsTruncated = approvalArgsTruncated(card.payload);
        const outcome = outcomes[card.id];
        // A transport error is not a decision: the buttons stay so the person can retry.
        const decided = outcome && outcome.kind !== "error";
        return (
          <div
            key={card.id}
            data-testid={`tool-approval-${card.id}`}
            className="rounded-lg border border-border bg-card px-3 py-2.5 text-sm text-card-foreground"
          >
            <div className="flex items-center gap-2">
              <ShieldQuestion size={14} className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate font-medium text-foreground">
                {decided ? t("decidedTitle", { tool }) : t("title", { tool })}
              </span>
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
            {!decided && <p className="mt-1 text-xs text-muted-foreground">{t("notRunYet")}</p>}
            {args && (
              <pre
                data-testid="tool-approval-args"
                className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-secondary/30 px-2 py-1.5 font-mono text-[11px] text-muted-foreground"
              >
                {args}
              </pre>
            )}
            {!args && !decided && <p className="mt-1 text-xs text-muted-foreground">{t("noArguments")}</p>}
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
                </div>
              ))}
          </div>
        );
      })}
    </div>
  );
}
