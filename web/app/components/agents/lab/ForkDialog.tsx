"use client";

// Agent Lab - the confirm step of a fork: rewind the server session to just
// before one user message and send that message (or an edit of it) again.
//
// The order on confirm is the safety property and must not be rearranged:
//   1. ask the server how many entries the session holds (non-destructive),
//   2. planFork - refuse unless the transcript maps onto the store exactly,
//   3. save the snapshot of what is about to be removed,
//   4. rewind,
//   5. hand the re-send to the playground.
// No rewind is ever sent without a successful plan, and the snapshot exists
// before the rewind does.
//
// The snapshot is only ever deleted again when the session is KNOWN to be
// uncut. A rewind whose answer never arrived may still have cut it, so the
// session is asked again before anything is concluded from the failure.
import { useEffect, useId, useRef, useState, type ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Textarea } from "../../ui/textarea";
import { planFork } from "./forkMath";
import { tabbableWithin } from "./focusables";
import { deleteSnapshot, saveSnapshot, type ForkSnapshot } from "./forkSnapshots";
import { LAB_MAX_MESSAGE_CHARS, labApi } from "./labApi";
import type { LabAgent, LabTurn } from "./types";

export interface ForkDialogProps {
  agent: LabAgent;
  sessionId: string;
  turns: readonly LabTurn[];
  /** Index in `turns` of the user message to fork from. */
  userIndex: number;
  /** A playground turn is in flight. */
  busy: boolean;
  /** Puts focus somewhere sensible on close when the control that opened the dialog is gone or disabled. */
  focusFallback?: () => void;
  onClose: () => void;
  /** The snapshot now exists (stored, or in memory if storage refused). */
  onSnapshotSaved: (snapshot: ForkSnapshot) => void;
  /** The rewind did not happen after all; forget the snapshot. */
  onSnapshotDropped: (id: string) => void;
  onForkRerun: (keepTurnCount: number, message: string) => void;
}

export default function ForkDialog({
  agent,
  sessionId,
  turns,
  userIndex,
  busy,
  focusFallback,
  onClose,
  onSnapshotSaved,
  onSnapshotDropped,
  onForkRerun,
}: ForkDialogProps): ReactElement {
  const t = useTranslations("agentLab");
  const titleId = useId();
  const messageId = useId();
  const cacheId = useId();
  const cardRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  const userTurn = turns[userIndex];
  const originalMessage = userTurn?.content ?? "";
  const hadAttachment = !!userTurn?.attachment || (userTurn?.frames?.length ?? 0) > 0;
  const caching = !!agent.responseCachingEnabled || !!agent.semanticResponseCachingEnabled;

  const [message, setMessage] = useState(originalMessage);
  const [cacheConfirmed, setCacheConfirmed] = useState(false);
  const [checking, setChecking] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // The browser refused to store the snapshot and the user has been told; the
  // next confirm goes ahead with a snapshot held in memory only.
  const [memoryOnlyAccepted, setMemoryOnlyAccepted] = useState(false);

  const trimmed = message.trim();
  // An unchanged message on a caching agent can come straight back from the
  // cache, so the "branch" would be the original. Make that a decision.
  const needsCacheConfirm = caching && trimmed === originalMessage.trim();
  const canConfirm = !checking && trimmed !== "" && (!needsCacheConfirm || cacheConfirmed);

  // The freshest transcript and callbacks for the async confirm, which
  // outlives the render it started in.
  const live = useRef({ turns, busy, onClose, checking });
  useEffect(() => {
    live.current = { turns, busy, onClose, checking };
  });
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Focus moves into the dialog on open and back to what opened it on close.
  // After a fork that control (the Fork button) is disabled or gone, and
  // focus must not be left on <body> behind the lab.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    textareaRef.current?.focus();
    return () => {
      if (opener && opener.isConnected && !opener.hasAttribute("disabled")) opener.focus();
      else focusFallback?.();
    };
    // Mount/unmount only: the opener is whatever had focus when the dialog appeared.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Escape and Tab belong to this dialog while it is open. Capture phase on
  // the document, so it runs before the lab's own window listener no matter
  // where focus is; preventDefault is how the lab knows to leave Escape alone.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (!live.current.checking) live.current.onClose();
        return;
      }
      if (e.key !== "Tab" || !cardRef.current) return;
      const focusable = tabbableWithin(cardRef.current);
      if (focusable.length === 0) {
        // While the server is being checked every control here is disabled.
        // Tab must still not walk out of the dialog into the lab behind it.
        e.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (!cardRef.current.contains(active)) {
        e.preventDefault();
        first.focus();
      } else if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, []);

  const confirm = async () => {
    if (!canConfirm) return;
    setChecking(true);
    setProblem(null);
    let snapshotId: string | null = null;
    try {
      const serverEntries = await labApi.probeSessionEntryCount(agent.id, sessionId);
      // A turn that started while this dialog was open would be appended to
      // the session after the cut.
      if (live.current.busy) {
        setProblem(t("graphForkBusy"));
        return;
      }
      const current = live.current.turns;
      const plan = planFork(current, userIndex, serverEntries);
      // "=== false", not "!plan.ok": with strictNullChecks off only the
      // explicit comparison narrows the union to its refusal side.
      if (plan.ok === false) {
        if (plan.reason === "on-device") setProblem(t("graphForkOnDevice"));
        else if (plan.reason === "tail-unclean") setProblem(t("graphForkTailUnclean"));
        else if (plan.reason === "prefix-unclean") setProblem(t("graphForkPrefixUnclean"));
        else setProblem(t("graphForkMismatch", { server: plan.server ?? 0, local: plan.local ?? 0 }));
        return;
      }
      const { snapshot, persisted } = saveSnapshot(agent.id, {
        sessionId,
        forkUserIndex: userIndex,
        originalMessage,
        sentMessage: trimmed,
        originalTail: current.slice(userIndex),
      });
      if (!persisted && !memoryOnlyAccepted) {
        // The dialog promises a kept snapshot. One that lives in memory only
        // is gone on reload, so nothing is rewound until the user has been
        // told and presses confirm again.
        deleteSnapshot(agent.id, snapshot.id);
        setMemoryOnlyAccepted(true);
        setProblem(t("graphForkSnapshotNotStored"));
        return;
      }
      snapshotId = snapshot.id;
      onSnapshotSaved(snapshot);
      let keptTurns: number;
      try {
        keptTurns = (await labApi.rewindChatSession(agent.id, sessionId, plan.keepTurns)).keepTurns;
      } catch (err) {
        // No answer is not "nothing happened": the request may have reached
        // the server and cut the session before the connection dropped. Ask
        // what the session holds now.
        let after: number | null = null;
        try {
          after = await labApi.probeSessionEntryCount(agent.id, sessionId);
        } catch {
          after = null;
        }
        // Untouched: fail as before (the outer catch forgets the snapshot).
        if (after === serverEntries) throw err;
        if (after !== plan.keepTurns) {
          // Unknown, or changed in some other way. The snapshot may be the
          // only record of what was there, so it stays.
          snapshotId = null;
          const reason = err instanceof Error && err.message ? err.message : String(err);
          setProblem(t("graphForkUnknown", { reason }));
          return;
        }
        // The rewind was applied; only its answer was lost. Carry on.
        keptTurns = plan.keepTurns;
      }
      if (keptTurns !== plan.keepTurns) {
        // The server kept a different number than planned, which means the
        // session changed between the probe and the rewind and nothing was
        // cut at the planned point. Nothing is re-sent.
        deleteSnapshot(agent.id, snapshot.id);
        onSnapshotDropped(snapshot.id);
        const local = plan.keepTurns + 2 * plan.tailExchanges;
        setProblem(t("graphForkFailed", { reason: t("graphForkMismatch", { server: keptTurns, local }) }));
        return;
      }
      // The session is cut. From here on the snapshot is the only copy of
      // the original, so nothing below may remove it.
      snapshotId = null;
      if (live.current.busy) {
        // Another turn (a camera watch tick, say) started while the rewind
        // was out. Cutting the transcript and sending now would run two
        // streams into the same turn, so the message is not re-sent.
        setProblem(t("graphForkInterleaved"));
        return;
      }
      onForkRerun(userIndex, trimmed);
      live.current.onClose();
    } catch (err) {
      if (snapshotId) {
        deleteSnapshot(agent.id, snapshotId);
        onSnapshotDropped(snapshotId);
      }
      const reason = err instanceof Error && err.message ? err.message : String(err);
      setProblem(t("graphForkFailed", { reason }));
    } finally {
      if (mounted.current) setChecking(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 p-3 sm:p-6">
      {/* role="dialog" on the card, not the scrim: tokens.css paints every
          [role="dialog"] as a solid raised surface. */}
      <div
        ref={cardRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex max-h-full w-full max-w-xl flex-col gap-3 overflow-y-auto rounded-xl border border-border bg-background p-4 shadow-2xl sm:p-5"
      >
        <h3 id={titleId} className="m-0 text-base font-semibold text-foreground">
          {t("graphForkTitle")}
        </h3>
        <p className="m-0 text-sm leading-relaxed text-muted-foreground">{t("graphForkBody")}</p>
        <ul className="m-0 list-disc space-y-1 ps-5 text-xs leading-relaxed text-muted-foreground">
          <li>{t("graphForkSideEffects")}</li>
          {hadAttachment && <li>{t("graphForkAttachmentNote")}</li>}
        </ul>

        <div className="space-y-1.5">
          <label htmlFor={messageId} className="text-xs font-medium text-foreground">
            {t("graphForkMessageLabel")}
          </label>
          <Textarea
            id={messageId}
            ref={textareaRef}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            maxLength={LAB_MAX_MESSAGE_CHARS}
            rows={4}
            disabled={checking}
            className="max-h-60"
          />
        </div>

        {caching && (
          <div className="space-y-2 rounded-lg border border-[var(--warning-border)] bg-[var(--warning-bg)] p-3 text-xs leading-relaxed text-[var(--warning-text)]">
            <p className="m-0">{t("graphForkCacheNote")}</p>
            {needsCacheConfirm && (
              <label htmlFor={cacheId} className="flex cursor-pointer items-start gap-2 font-medium">
                <input
                  id={cacheId}
                  type="checkbox"
                  className="mt-0.5"
                  checked={cacheConfirmed}
                  onChange={(e) => setCacheConfirmed(e.target.checked)}
                  disabled={checking}
                />
                {t("graphForkCacheConfirm")}
              </label>
            )}
          </div>
        )}

        <p className="m-0 text-xs text-muted-foreground">{t("graphForkEstimate")}</p>

        {checking && (
          <p role="status" className="m-0 text-xs text-muted-foreground">
            {t("graphForkChecking")}
          </p>
        )}
        {problem && (
          <p role="alert" className="m-0 text-xs leading-relaxed text-destructive">
            {problem}
          </p>
        )}

        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose} disabled={checking}>
            {t("cancel")}
          </Button>
          <Button type="button" onClick={() => void confirm()} disabled={!canConfirm}>
            {t("graphForkConfirm")}
          </Button>
        </div>
      </div>
    </div>
  );
}
