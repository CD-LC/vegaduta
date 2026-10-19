"use client";

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import Link from "next/link";
import {
  Check,
  ChevronRight,
  ExternalLink,
  Hand,
  Loader2,
  MessageSquareText,
  PauseCircle,
  Play,
  X,
  type LucideIcon,
} from "lucide-react";
import { api } from "../../lib/api";
import InputRequestFields from "./ApprovalInputFields";
import {
  errMessage,
  errStatus,
  isRecord,
  payloadMatches,
  prettyPayload,
  QUIET_ACTION,
  QUIET_LOOKUP,
  toApproval,
  toInput,
  type ApprovalInfo,
  type InputInfo,
} from "./approvalPauseModel";
import { useCardFocusReturn, useCardPlacement } from "./useApprovalCardLayout";
import "./approval-card.css";

// Node-anchored decision card for a run parked on a person (the SSE
// `run.awaiting` frame, or the polled run status when the stream is absent).
// Only wraps actions that already exist: /api/approvals/{id}/approve|reject
// (ApprovalsPanel), /api/workflows/input-requests/{id}/submit
// (InputRequestsPanel) and /api/workflows/{id}/runs/{runId}/resume. Every
// lookup is optional - a 403/404/network miss hides that section and the card
// falls back to "Open in Approvals", so it never breaks the canvas.

/* ------------------------------------------------------------------ types */

export type AwaitingStatus = "AWAITING_APPROVAL" | "AWAITING_INPUT" | "PAUSED";

/** Shape of the `run.awaiting` SSE frame's data. */
export interface RunAwaiting {
  status: AwaitingStatus;
  nodeId: string;
  label?: string | null;
  summary?: string | null;
  /** Approval request id; core only sets it when the node summary names one. */
  requestId?: string | null;
}

/** Node rect in px relative to the card's offsetParent (same contract as RunStepInspector). */
export interface ApprovalCardAnchor {
  x: number;
  y: number;
  width?: number;
  height?: number;
}

export interface ApprovalPauseCardProps {
  awaiting: RunAwaiting;
  workflowId: string;
  runId: string;
  /** RUNNER-or-better. False renders the card read-only with the Approvals link. */
  canDecide: boolean;
  /** Called once the decision/submission/resume was accepted by core. */
  onResolved: () => void;
  onClose: () => void;
  /** Omit to dock top-right of the offsetParent. Ignored below 640px (bottom sheet). */
  anchor?: ApprovalCardAnchor | null;
  /** Move focus into the card on mount (or when this turns true). Off by default: a run parking must not steal focus mid-edit. */
  autoFocus?: boolean;
  /** Where focus goes when the card unmounts holding focus and the element it came from is gone. */
  onFocusFallback?: () => void;
}

type Phase = "loading" | "ready" | "unavailable";

const APPROVALS_HREF = "/admin/approvals";
const INPUT_REQUESTS_HREF = "/admin/input-requests";

const META: Record<AwaitingStatus, { title: string; pill: string; Icon: LucideIcon }> = {
  AWAITING_APPROVAL: { title: "Waiting for approval", pill: "Approval", Icon: Hand },
  AWAITING_INPUT: { title: "Waiting for input", pill: "Input", Icon: MessageSquareText },
  PAUSED: { title: "Run paused", pill: "Paused", Icon: PauseCircle },
};

/* -------------------------------------------------------------- component */

export default function ApprovalPauseCard({
  awaiting,
  workflowId,
  runId,
  canDecide,
  onResolved,
  onClose,
  anchor,
  autoFocus = false,
  onFocusFallback,
}: ApprovalPauseCardProps) {
  const { status, nodeId, requestId } = awaiting;
  const meta = META[status] ?? META.PAUSED;
  const titleId = useId();
  const descId = useId();
  const noteId = useId();
  const cardRef = useRef<HTMLDivElement>(null);

  const [phase, setPhase] = useState<Phase>(status === "PAUSED" ? "ready" : "loading");
  const [approval, setApproval] = useState<ApprovalInfo | null>(null);
  const [input, setInput] = useState<InputInfo | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [confirmReject, setConfirmReject] = useState(false);
  const [busy, setBusy] = useState<null | "approve" | "reject" | "submit" | "resume">(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");

  // Resolve the request behind the pause. Keyed on the pause identity so a
  // new awaiting frame for another node starts clean.
  useEffect(() => {
    let cancelled = false;
    setApproval(null);
    setInput(null);
    setValues({});
    setNote("");
    setConfirmReject(false);
    setError(null);
    setDone(null);

    async function load() {
      if (status === "PAUSED") {
        setPhase("ready");
        return;
      }
      setPhase("loading");
      try {
        if (status === "AWAITING_APPROVAL") {
          let found: ApprovalInfo | null = null;
          if (requestId) {
            try {
              found = toApproval(await api.getApproval(requestId, QUIET_LOOKUP));
            } catch {
              // Detail read failed but the id is real - still decidable, just no preview.
              found = { id: requestId, status: "PENDING", summary: null, payload: null, formFields: [] };
            }
          } else {
            const list: unknown = await api.listApprovals("PENDING", QUIET_LOOKUP);
            const hit = Array.isArray(list) ? list.find((it) => payloadMatches(it, runId, nodeId)) : undefined;
            found = toApproval(hit);
          }
          if (cancelled) return;
          setApproval(found);
          setPhase(found ? "ready" : "unavailable");
        } else {
          const list: unknown = await api.listInputRequests("PENDING", runId, QUIET_LOOKUP);
          const items = Array.isArray(list) ? list : [];
          const hit =
            items.find((it) => isRecord(it) && it.nodeId === nodeId) ?? (items.length === 1 ? items[0] : undefined);
          const found = toInput(hit);
          if (cancelled) return;
          setInput(found);
          setPhase(found ? "ready" : "unavailable");
        }
      } catch {
        // Older core / no permission / offline: hide the decision UI, keep the link.
        if (!cancelled) setPhase("unavailable");
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [status, nodeId, requestId, runId]);

  useEffect(() => {
    setAnnounce(`Run paused at ${awaiting.label || nodeId}. ${meta.title}.`);
  }, [awaiting.label, nodeId, meta.title]);

  useEffect(() => {
    if (autoFocus) cardRef.current?.focus();
  }, [autoFocus]);
  const { onFocusCapture, onBlurCapture } = useCardFocusReturn(cardRef, done, onFocusFallback);

  /* ---- anchoring: beside the node, flipped/clamped inside the stage ---- */
  const pos = useCardPlacement(cardRef, anchor);

  /* ---------------------------------------------------------- actions ---- */
  const finish = useCallback(
    (message: string) => {
      setDone(message);
      setAnnounce(message);
      onResolved();
    },
    [onResolved]
  );

  // 409 = someone else already decided / the run moved on: treat as resolved
  // so the canvas refreshes rather than leaving a stale card up.
  const fail = useCallback(
    (err: unknown, fallback: string) => {
      if (errStatus(err) === 409) {
        finish("Already handled elsewhere - refreshing the run.");
        return;
      }
      const message = errMessage(err, fallback);
      setError(message);
      setAnnounce(message);
    },
    [finish]
  );

  const approve = useCallback(async () => {
    if (!approval || busy || done || !canDecide) return;
    setBusy("approve");
    setError(null);
    try {
      const formValues: Record<string, string> = {};
      for (const f of approval.formFields) {
        const v = (values[f] ?? "").trim();
        if (v) formValues[f] = v;
      }
      await api.approveRequest(approval.id, undefined, formValues, QUIET_ACTION);
      finish("Approved - the run is continuing.");
    } catch (err) {
      fail(err, "Couldn't approve this step.");
    } finally {
      setBusy(null);
    }
  }, [approval, busy, done, canDecide, values, finish, fail]);

  const reject = useCallback(async () => {
    if (!approval || busy || done || !canDecide) return;
    setBusy("reject");
    setError(null);
    try {
      await api.rejectRequest(approval.id, undefined, note.trim() || null, QUIET_ACTION);
      setConfirmReject(false);
      finish("Rejected - the run will stop at this step.");
    } catch (err) {
      fail(err, "Couldn't reject this step.");
    } finally {
      setBusy(null);
    }
  }, [approval, busy, done, canDecide, note, finish, fail]);

  const submit = useCallback(async () => {
    if (!input || busy || done || !canDecide) return;
    const missingRequired = input.fields.filter((f) => f.required && !(values[f.key] ?? "").trim());
    if (missingRequired.length) {
      const message = `Required: ${missingRequired.map((f) => f.label).join(", ")}`;
      setError(message);
      setAnnounce(message);
      return;
    }
    setBusy("submit");
    setError(null);
    try {
      const out: Record<string, string> = {};
      for (const f of input.fields) {
        const v = values[f.key] ?? "";
        if (v !== "") out[f.key] = v;
      }
      await api.submitInputRequest(input.id, out, QUIET_ACTION);
      finish("Submitted - the run is continuing.");
    } catch (err) {
      fail(err, "Couldn't submit this input.");
    } finally {
      setBusy(null);
    }
  }, [input, busy, done, canDecide, values, finish, fail]);

  const resume = useCallback(async () => {
    if (busy || done || !canDecide) return;
    setBusy("resume");
    setError(null);
    try {
      await api.resumeWorkflowRun(workflowId, runId, QUIET_ACTION);
      finish("Resumed.");
    } catch (err) {
      fail(err, "Couldn't resume this run.");
    } finally {
      setBusy(null);
    }
  }, [busy, done, canDecide, workflowId, runId, finish, fail]);

  const primary = status === "AWAITING_APPROVAL" ? approve : status === "AWAITING_INPUT" ? submit : resume;

  function onKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
    if (e.key === "Escape") {
      e.stopPropagation();
      e.preventDefault();
      if (confirmReject) setConfirmReject(false);
      else onClose();
      return;
    }
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
      e.stopPropagation();
      e.preventDefault();
      if (confirmReject) return;
      // The note is the Reject note: Ctrl+Enter there asks to reject, never approves.
      if (e.target instanceof HTMLElement && e.target.id === noteId) {
        if (decidable) setConfirmReject(true);
        return;
      }
      void primary();
    }
  }

  const summary = approval?.summary || awaiting.summary || null;
  const payload = approval ? prettyPayload(approval.payload) : null;
  const decidable = canDecide && phase === "ready" && !done;
  const linkHref = status === "AWAITING_INPUT" ? INPUT_REQUESTS_HREF : APPROVALS_HREF;
  const linkText = status === "AWAITING_INPUT" ? "Open in Input requests" : "Open in Approvals";
  const { Icon } = meta;

  const style: CSSProperties | undefined = pos
    ? ({ "--apc-left": `${pos.left}px`, "--apc-top": `${pos.top}px` } as CSSProperties)
    : undefined;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- non-modal dialog: Escape/Tab handling bubbles from its focusable children
    <div
      ref={cardRef}
      className="wfx-apc"
      role="dialog"
      aria-modal="false"
      aria-labelledby={titleId}
      aria-describedby={descId}
      tabIndex={-1}
      data-status={status === "PAUSED" ? "paused" : "waiting"}
      data-anchored={pos ? "" : undefined}
      data-side={pos?.side}
      data-done={done ? "" : undefined}
      style={style}
      onKeyDown={onKeyDown}
      onFocusCapture={onFocusCapture}
      onBlurCapture={onBlurCapture}
    >
      <span className="apc-notch" aria-hidden="true" />
      <div className="apc-grip" aria-hidden="true" />
      <span className="apc-sr" role="status" aria-live="polite">
        {announce}
      </span>

      <header className="apc-head">
        <span className="apc-beacon" aria-hidden="true">
          <span className="apc-ring" />
          <Icon size={15} strokeWidth={2.2} aria-hidden="true" />
        </span>
        <div className="apc-titles">
          <h3 id={titleId} className="apc-title">
            {meta.title}
          </h3>
          <p className="apc-node" title={awaiting.label || nodeId}>
            {awaiting.label || nodeId}
          </p>
        </div>
        <span className="apc-pill">{done ? "Done" : meta.pill}</span>
        <button type="button" className="apc-iconbtn" onClick={onClose} aria-label="Close decision card">
          <X size={15} aria-hidden="true" />
        </button>
      </header>

      <div className="apc-body" id={descId}>
        {summary && <p className="apc-summary">{summary}</p>}
        {!summary && status === "PAUSED" && (
          <p className="apc-summary apc-muted">The run is parked on this step and will continue from here.</p>
        )}

        {phase === "loading" && (
          <p className="apc-muted apc-loading">
            <Loader2 size={13} className="apc-spin" aria-hidden="true" /> Loading request...
          </p>
        )}

        {phase === "unavailable" && (
          <p className="apc-muted">This request can&apos;t be decided from the canvas. Use the queue instead.</p>
        )}

        {payload && (
          <details className="apc-details">
            <summary>
              <ChevronRight size={13} className="apc-chev" aria-hidden="true" /> What&apos;s being approved
            </summary>
            {/* Scrollable region: focusable so keyboard users can scroll a long payload. */}
            {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
            <pre className="apc-payload" tabIndex={0} aria-label="Approval payload">
              {payload}
            </pre>
          </details>
        )}

        {input?.message && <p className="apc-summary">{input.message}</p>}

        {!canDecide && phase !== "loading" && (
          <p className="apc-muted">You can view this pause, but deciding needs Runner access.</p>
        )}

        {decidable && status === "AWAITING_APPROVAL" && approval && approval.formFields.length > 0 && (
          <fieldset className="apc-fields">
            <legend>The workflow needs this from you</legend>
            {approval.formFields.map((f, i) => (
              <label key={f} className="apc-field">
                <span>{f}</span>
                <input
                  className="apc-input"
                  value={values[f] ?? ""}
                  maxLength={2000}
                  onChange={(e) => setValues((p) => ({ ...p, [f]: e.target.value }))}
                  // Opt-in via the autoFocus prop: the card is opened specifically to answer this prompt.
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus={autoFocus && i === 0}
                />
              </label>
            ))}
          </fieldset>
        )}

        {decidable && status === "AWAITING_APPROVAL" && approval && (
          <div className="apc-field">
            <label htmlFor={noteId}>
              Note <span className="apc-muted">(optional, sent with Reject)</span>
            </label>
            <textarea
              id={noteId}
              className="apc-input apc-textarea"
              rows={2}
              maxLength={2000}
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>
        )}

        {decidable &&
          status === "AWAITING_INPUT" &&
          input &&
          (input.fields.length === 0 ? (
            <p className="apc-muted">No fields requested - submit to continue the run.</p>
          ) : (
            <InputRequestFields fields={input.fields} idPrefix={noteId} values={values} setValues={setValues} />
          ))}

        {error && (
          <p className="apc-error" role="alert">
            {error}
          </p>
        )}
        {done && (
          <p className="apc-done">
            <Check size={14} aria-hidden="true" /> {done}
          </p>
        )}
      </div>

      <footer className="apc-foot">
        <Link href={linkHref} className="apc-link" target="_blank" rel="noopener">
          {linkText} <ExternalLink size={12} aria-hidden="true" />
        </Link>
        <div className="apc-actions">
          {decidable &&
            status === "AWAITING_APPROVAL" &&
            approval &&
            (confirmReject ? (
              <>
                <button
                  type="button"
                  className="apc-btn"
                  onClick={() => setConfirmReject(false)}
                  disabled={busy !== null}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="apc-btn apc-btn-danger"
                  onClick={() => void reject()}
                  disabled={busy !== null}
                  aria-busy={busy === "reject"}
                >
                  {busy === "reject" && <Loader2 size={13} className="apc-spin" aria-hidden="true" />}
                  Confirm reject
                </button>
              </>
            ) : (
              <>
                <button
                  type="button"
                  className="apc-btn apc-btn-ghost-danger"
                  onClick={() => setConfirmReject(true)}
                  disabled={busy !== null}
                >
                  Reject...
                </button>
                <button
                  type="button"
                  className="apc-btn apc-btn-primary"
                  onClick={() => void approve()}
                  disabled={busy !== null}
                  aria-busy={busy === "approve"}
                  aria-keyshortcuts="Control+Enter"
                  title="Approve (Ctrl+Enter)"
                >
                  {busy === "approve" ? (
                    <Loader2 size={13} className="apc-spin" aria-hidden="true" />
                  ) : (
                    <Check size={13} aria-hidden="true" />
                  )}
                  Approve
                </button>
              </>
            ))}
          {decidable && status === "AWAITING_INPUT" && input && (
            <button
              type="button"
              className="apc-btn apc-btn-primary"
              onClick={() => void submit()}
              disabled={busy !== null}
              aria-busy={busy === "submit"}
              aria-keyshortcuts="Control+Enter"
              title="Submit (Ctrl+Enter)"
            >
              {busy === "submit" ? (
                <Loader2 size={13} className="apc-spin" aria-hidden="true" />
              ) : (
                <Check size={13} aria-hidden="true" />
              )}
              {input.fields.length === 0 ? "Continue" : "Submit"}
            </button>
          )}
          {decidable && status === "PAUSED" && (
            <button
              type="button"
              className="apc-btn apc-btn-primary"
              onClick={() => void resume()}
              disabled={busy !== null}
              aria-busy={busy === "resume"}
              aria-keyshortcuts="Control+Enter"
              title="Resume (Ctrl+Enter)"
            >
              {busy === "resume" ? (
                <Loader2 size={13} className="apc-spin" aria-hidden="true" />
              ) : (
                <Play size={13} aria-hidden="true" />
              )}
              Resume
            </button>
          )}
        </div>
      </footer>
      {decidable && (
        <p className="apc-keys" aria-hidden="true">
          <kbd>Ctrl</kbd>+<kbd>Enter</kbd>{" "}
          {status === "AWAITING_APPROVAL" ? "approve" : status === "AWAITING_INPUT" ? "submit" : "resume"} ·{" "}
          <kbd>Esc</kbd> close
        </p>
      )}
    </div>
  );
}
