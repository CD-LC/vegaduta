"use client";

// <NewCommentCard> - composer for a NEW root thread at a node or a free
// canvas point (flow coords). Not optimistic (the new pin needs the server
// id); keeps the text and shows the reason on failure.
// Re-exported from CommentThread.tsx.
import { useId, useRef, useState, type CSSProperties } from "react";
import { MessageSquare, X } from "lucide-react";
import type { CommentView, NewThreadTarget } from "./comments";
import { createWorkflowComment } from "../../lib/api/workflows";
import { useReturnFocus, type MentionPerson } from "./commentCardShared";
import { CommentComposer } from "./CommentComposer";
import { useTranslations } from "../../lib/i18n";

export interface NewCommentCardProps {
  workflowId: string;
  target: NewThreadTarget;
  people?: MentionPerson[];
  nodeLabel?: (nodeId: string) => string;
  onCreated: (view: CommentView) => void;
  onCancel: () => void;
  autoFocus?: boolean;
  onFocusFallback?: () => void;
  className?: string;
  style?: CSSProperties;
  anchored?: boolean;
}

export function NewCommentCard({
  workflowId, target, people = [], nodeLabel, onCreated, onCancel, autoFocus = true, onFocusFallback,
  className, style, anchored = false,
}: NewCommentCardProps) {
    const tHC = useTranslations("hcComponentsWorkflowNewCommentCard");
  const uid = useId();
  const cardRef = useRef<HTMLDivElement>(null);
  const focus = useReturnFocus(cardRef, false, onFocusFallback);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const where = "nodeId" in target ? (nodeLabel?.(target.nodeId) || "Step") : "Canvas";

  async function create(body: string, mentionIds: string[]): Promise<boolean> {
    setBusy(true);
    setError(null);
    const anchor = "nodeId" in target ? { nodeId: target.nodeId } : { x: target.x, y: target.y };
    const r = await createWorkflowComment(workflowId, { body, mentions: mentionIds, ...anchor });
    setBusy(false);
    if (r.ok) {
      onCreated(r.data);
      return true;
    }
    setError(r.unsupported ? "Comments aren't available on this workspace yet." : r.message);
    return false;
  }

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog keyboard handling (Escape) bubbles from focusable children
    <div
      ref={cardRef}
      className={`wfc-card wfc-card-new${className ? ` ${className}` : ""}`}
      style={style}
      data-anchored={anchored || undefined}
      role="dialog"
      aria-labelledby={`${uid}-t`}
      tabIndex={-1}
      onFocusCapture={focus.onFocusCapture}
      onBlurCapture={focus.onBlurCapture}
      onPointerDown={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        if (e.key === "Escape" && !e.defaultPrevented) {
          e.preventDefault();
          e.stopPropagation();
          onCancel();
        }
      }}
    >
      <div className="wfc-grip" aria-hidden="true" />
      <div className="wfc-head">
        <div className="wfc-title" id={`${uid}-t`}>
          <MessageSquare size={13} aria-hidden="true" />
          <strong>
            {tHC("hcComponentsWorkflowNewCommentCard.s2")} <span className="wfc-sub">on {where}</span>
          </strong>
        </div>
        <button type="button" className="wfc-icon" aria-label={tHC("hcComponentsWorkflowNewCommentCard.s1")} onClick={onCancel}>
          <X size={14} aria-hidden="true" />
        </button>
      </div>
      <div className="wfc-foot">
        <CommentComposer
          label={`New comment on ${where}`}
          people={people}
          submitLabel="Comment"
          busy={busy}
          autoFocus={autoFocus}
          keepOnSubmit
          onSubmit={(body, ids) => create(body, ids)}
        />
      </div>
      {error && (
        <p className="wfc-err wfc-err-card" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
