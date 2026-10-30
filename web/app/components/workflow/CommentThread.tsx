"use client";

/*
 * Canvas comment cards. Standalone - CommentLayer mounts them, but any
 * surface that holds the thread list can.
 *
 * <CommentThread> - one thread: root + replies, reply composer, edit/delete
 *   own (canEdit), resolve/reopen (canResolve). Every mutation is optimistic
 *   through `onThreadsChange(updater)` and rolled back on failure with the
 *   reason shown inline (no toast for 400/403/404/409/410).
 *   Props:
 *     workflowId        string
 *     thread            CommentView (a ROOT, replies nested)
 *     number?           1-based thread number shown as "#n"
 *     people?           {id, displayName}[] for the @mention picker; empty hides the picker
 *     currentUserId?    author id for optimistic rows / resolvedBy
 *     currentUserName?  author name for optimistic rows
 *     onThreadsChange   (updater, phase?: "start" | "settle") => void - pass useWorkflowComments().setThreads
 *                       (plain React setThreads also works, minus the refresh-race guard)
 *   Resolve on a thread whose root was deleted is sent through its first live
 *   reply (core 410s a PATCH to a deleted row); with no live reply it is hidden.
 *   Editing leaves `mentions` out of the PATCH unless the author picked or
 *   removed a mention, so a name mismatch can never silently drop one.
 *     onClose()         Escape / close button
 *     nodeLabel?        (nodeId) => string for the header
 *     onFocusNode?      (nodeId) => void; shows a "Show step" button when set
 *     autoFocus?        move focus into the card on mount (only for user-initiated opens)
 *     onFocusFallback?  where focus goes if the card unmounts holding focus and its opener is gone
 *     className/style/anchored  positioning hooks for the host (see comments.css)
 *
 * <NewCommentCard> - composer for a NEW root thread at a node or a free
 *   canvas point (flow coords). Not optimistic (the new pin needs the server
 *   id); keeps the text and shows the reason on failure.
 *
 * <CommentComposer> - textarea + @mention picker; Ctrl/Cmd+Enter sends.
 */
import {
  useCallback,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import { Check, CircleDot, Crosshair, MessageSquare, RotateCcw, X } from "lucide-react";
import {
  editMentionIds,
  insertComment,
  mentionLabels,
  optimisticComment,
  patchComment,
  personLabel,
  relativeTime,
  removeComment,
  replaceComment,
  resolveTargetId,
  restoreThread,
  setThreadResolved,
  type CommentPerson,
  type CommentView,
} from "./comments";
import { createWorkflowComment, deleteWorkflowComment, updateWorkflowComment } from "../../lib/api/workflows";
import { useNow, useReturnFocus, type MentionPerson, type ThreadsUpdater } from "./commentCardShared";
import { CommentComposer } from "./CommentComposer";
import CommentItem from "./CommentItem";
import "./comments.css";
import { useTranslations } from "../../lib/i18n";

export type { MentionPerson, MutationPhase, ThreadsUpdater } from "./commentCardShared";
export { CommentComposer, type CommentComposerProps } from "./CommentComposer";
export { NewCommentCard, type NewCommentCardProps } from "./NewCommentCard";

/* ------------------------------------------------------------ thread card */

export interface CommentThreadProps {
  workflowId: string;
  thread: CommentView;
  number?: number;
  people?: MentionPerson[];
  currentUserId?: string;
  currentUserName?: string;
  onThreadsChange: ThreadsUpdater;
  onClose: () => void;
  nodeLabel?: (nodeId: string) => string;
  onFocusNode?: (nodeId: string) => void;
  autoFocus?: boolean;
  onFocusFallback?: () => void;
  className?: string;
  style?: CSSProperties;
  /** True when the host positioned the card via style (--wfc-left/--wfc-top); false docks it. */
  anchored?: boolean;
}

export default function CommentThread({
  workflowId,
  thread,
  number,
  people = [],
  currentUserId,
  currentUserName,
  onThreadsChange,
  onClose,
  nodeLabel,
  onFocusNode,
  autoFocus = false,
  onFocusFallback,
  className,
  style,
  anchored = false,
}: CommentThreadProps) {
  const tHC = useTranslations("hcComponentsWorkflowCommentThread");
  const uid = useId();
  const titleId = `${uid}-title`;
  const cardRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLOListElement>(null);
  const now = useNow();
  const focus = useReturnFocus(cardRef, autoFocus, onFocusFallback);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");

  const peopleById = useMemo(() => new Map(people.map((p) => [p.id, p])), [people]);
  const me = useMemo<CommentPerson>(
    () => ({
      id: currentUserId ?? "me",
      displayName: currentUserName || (currentUserId && peopleById.get(currentUserId)?.displayName) || "You",
    }),
    [currentUserId, currentUserName, peopleById]
  );
  const where = thread.nodeId ? nodeLabel?.(thread.nodeId) || "Step" : "Canvas";
  const replyCount = thread.replies.length;

  const fail = useCallback((message: string) => {
    setError(message);
    setAnnounce(message);
  }, []);

  /* ---- reply (optimistic) ---- */
  const sendReply = useCallback(
    async (body: string, mentionIds: string[], mentioned: CommentPerson[]): Promise<boolean> => {
      setError(null);
      const temp = optimisticComment({ body, mentions: mentioned, author: me, root: thread });
      onThreadsChange((prev) => insertComment(prev, temp), "start");
      requestAnimationFrame(() => listRef.current?.lastElementChild?.scrollIntoView({ block: "nearest" }));
      const r = await createWorkflowComment(workflowId, { body, parentId: thread.id, mentions: mentionIds });
      if (r.ok) {
        onThreadsChange(
          (prev) => replaceComment(prev, temp.id, { ...r.data, parentId: r.data.parentId ?? thread.id }),
          "settle"
        );
        setAnnounce("Reply posted.");
        return true;
      }
      onThreadsChange((prev) => removeComment(prev, temp.id), "settle");
      fail(r.status === 404 ? "This thread no longer exists." : r.message);
      return false;
    },
    [workflowId, thread, onThreadsChange, fail, me]
  );

  /* ---- edit own ---- */
  const saveEdit = useCallback(
    async (
      target: CommentView,
      body: string,
      pickedIds: string[],
      mentioned: CommentPerson[],
      meta: { pickedDuringEdit: boolean }
    ): Promise<boolean> => {
      setError(null);
      const before = (target.body ?? "").trim();
      // null = leave `mentions` out so core keeps its list (it replaces the
      // whole list when sent, and a label mismatch must not drop anyone).
      const ids = editMentionIds({
        before,
        after: body,
        original: target.mentions.map((m) => ({
          id: m.id,
          labels: mentionLabels(m.id, [peopleById.get(m.id)?.displayName, m.displayName]),
        })),
        pickedIds,
        pickedDuringEdit: meta.pickedDuringEdit,
      });
      const sameIds = ids === null || ids.join() === target.mentions.map((m) => m.id).join();
      if (body === before && sameIds) {
        setEditingId(null);
        return true;
      }
      const nextMentions =
        ids === null
          ? target.mentions
          : ids.map(
              (id) =>
                mentioned.find((p) => p.id === id) ??
                target.mentions.find((p) => p.id === id) ?? { id, displayName: null }
            );
      setBusyId(target.id);
      const prev = target;
      onThreadsChange(
        (p) => patchComment(p, target.id, { body, mentions: nextMentions, updatedAt: new Date().toISOString() }),
        "start"
      );
      setEditingId(null);
      const r = await updateWorkflowComment(workflowId, target.id, ids === null ? { body } : { body, mentions: ids });
      setBusyId(null);
      if (r.ok) {
        onThreadsChange((p) => replaceComment(p, target.id, r.data), "settle");
        setAnnounce("Comment updated.");
        return true;
      }
      onThreadsChange((p) => replaceComment(p, target.id, prev), "settle");
      fail(r.status === 410 || r.status === 404 ? "This comment was deleted." : r.message);
      return false;
    },
    [workflowId, onThreadsChange, fail, peopleById]
  );

  /* ---- delete own ---- */
  const remove = useCallback(
    async (target: CommentView) => {
      setError(null);
      setBusyId(target.id);
      let snapshot: CommentView | null = null;
      let index = 0;
      onThreadsChange((p) => {
        const i = p.findIndex((t) => t.id === thread.id);
        if (i >= 0) {
          snapshot = p[i];
          index = i;
        }
        return removeComment(p, target.id);
      }, "start");
      const r = await deleteWorkflowComment(workflowId, target.id);
      setBusyId(null);
      // 404: already gone - the optimistic removal is the truth.
      if (r.ok || r.status === 404) {
        // Re-apply (idempotent) so a GET that raced the delete can't have
        // brought the row back; also closes the mutation for the loader.
        onThreadsChange((p) => removeComment(p, target.id), "settle");
        setAnnounce("Comment deleted.");
        return;
      }
      const snap = snapshot as CommentView | null;
      onThreadsChange((p) => (snap ? restoreThread(p, snap, index) : p), "settle");
      fail(r.message);
    },
    [workflowId, thread.id, onThreadsChange, fail]
  );

  /* ---- resolve / reopen ---- */
  // A blanked root answers 410 to PATCH; core resolves the root through any
  // reply, so route through a live one (null hides the button).
  const resolveVia = resolveTargetId(thread);
  const toggleResolved = useCallback(async () => {
    if (resolving || !resolveVia) return;
    setError(null);
    setResolving(true);
    const next = !thread.resolved;
    const before = { resolved: thread.resolved, by: thread.resolvedBy, at: thread.resolvedAt };
    onThreadsChange(
      (p) => setThreadResolved(p, thread.id, next, currentUserId ?? null, new Date().toISOString()),
      "start"
    );
    const r = await updateWorkflowComment(workflowId, resolveVia, { resolved: next });
    setResolving(false);
    if (r.ok) {
      // The response is the row we PATCHed (root or the carrying reply) with
      // the root's resolution mirrored onto it.
      onThreadsChange(
        (p) =>
          setThreadResolved(
            replaceComment(p, resolveVia, r.data),
            thread.id,
            r.data.resolved,
            r.data.resolvedBy,
            r.data.resolvedAt
          ),
        "settle"
      );
      setAnnounce(next ? "Thread resolved." : "Thread reopened.");
      return;
    }
    onThreadsChange((p) => setThreadResolved(p, thread.id, before.resolved, before.by, before.at), "settle");
    fail(r.status === 410 || r.status === 404 ? "This thread was deleted." : r.message);
  }, [resolving, resolveVia, thread, currentUserId, workflowId, onThreadsChange, fail]);

  function onKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
    if (e.key !== "Escape" || e.defaultPrevented) return;
    e.preventDefault();
    e.stopPropagation();
    if (editingId) setEditingId(null);
    else onClose();
  }

  const resolvedByLabel = thread.resolvedBy
    ? thread.resolvedBy === currentUserId
      ? "you"
      : personLabel({ id: thread.resolvedBy, displayName: peopleById.get(thread.resolvedBy)?.displayName ?? null })
    : null;

  const all = [thread, ...thread.replies];
  const rootGone = thread.deleted && thread.replies.length === 0;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- card keyboard handling (Escape) bubbles from focusable children
    <div
      ref={cardRef}
      className={`wfc-card${className ? ` ${className}` : ""}`}
      style={style}
      data-anchored={anchored || undefined}
      data-resolved={thread.resolved || undefined}
      role="dialog"
      aria-labelledby={titleId}
      tabIndex={-1}
      onKeyDown={onKeyDown}
      onFocusCapture={focus.onFocusCapture}
      onBlurCapture={focus.onBlurCapture}
      // Clicks inside must not reach the canvas (deselect / pane click).
      onPointerDown={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
    >
      <div className="wfc-grip" aria-hidden="true" />
      <div className="wfc-head">
        <div className="wfc-title" id={titleId}>
          <MessageSquare size={13} aria-hidden="true" />
          <strong>
            {number ? `#${number} ` : ""}
            <span className="wfc-where">{where}</span>
          </strong>
          <span className="wfc-sub">
            {replyCount === 0 ? "No replies" : `${replyCount} ${replyCount === 1 ? "reply" : "replies"}`}
          </span>
        </div>
        <div className="wfc-head-actions">
          {thread.nodeId && onFocusNode && !thread.orphaned && (
            <button
              type="button"
              className="wfc-icon"
              aria-label={`Show ${where} on canvas`}
              onClick={() => onFocusNode(thread.nodeId as string)}
            >
              <Crosshair size={13} aria-hidden="true" />
            </button>
          )}
          {thread.canResolve && resolveVia && (
            <button
              type="button"
              className="wfc-btn"
              data-primary={!thread.resolved || undefined}
              aria-pressed={thread.resolved}
              onClick={() => void toggleResolved()}
              disabled={resolving}
            >
              {thread.resolved ? <RotateCcw size={12} aria-hidden="true" /> : <Check size={12} aria-hidden="true" />}
              {thread.resolved ? "Reopen" : "Resolve"}
            </button>
          )}
          <button type="button" className="wfc-icon" aria-label={tHC("s3")} onClick={onClose}>
            <X size={14} aria-hidden="true" />
          </button>
        </div>
      </div>

      {thread.orphaned && (
        <p className="wfc-banner" data-tone="warn">
          <CircleDot size={12} aria-hidden="true" />
          {tHC("s2")}
        </p>
      )}
      {thread.resolved && (
        <p className="wfc-banner" data-tone="ok">
          <Check size={12} aria-hidden="true" />
          Resolved{resolvedByLabel ? ` by ${resolvedByLabel}` : ""}
          {thread.resolvedAt ? ` · ${relativeTime(thread.resolvedAt, now)}` : ""}
        </p>
      )}

      <div className="wfc-scroll">
        {rootGone ? (
          <p className="wfc-deleted">{tHC("s1")}</p>
        ) : (
          <ol className="wfc-list" ref={listRef}>
            {all.map((c) => (
              <CommentItem
                key={c.id}
                comment={c}
                isRoot={c.id === thread.id}
                now={now}
                people={people}
                peopleById={peopleById}
                editing={editingId === c.id}
                busy={busyId === c.id}
                onEdit={() => {
                  setError(null);
                  setEditingId(c.id);
                }}
                onCancelEdit={() => setEditingId(null)}
                onSaveEdit={(body, ids, mentioned, meta) => saveEdit(c, body, ids, mentioned, meta)}
                onDelete={() => void remove(c)}
              />
            ))}
          </ol>
        )}
      </div>

      {error && (
        <p className="wfc-err wfc-err-card" role="alert">
          {error}
        </p>
      )}

      {!rootGone && (
        <div className="wfc-foot">
          <CommentComposer
            label={`Reply to thread ${number ? `#${number}` : ""} on ${where}`}
            people={people}
            placeholder={thread.resolved ? "Reply (thread stays resolved)…" : "Reply…"}
            submitLabel="Reply"
            onSubmit={sendReply}
          />
        </div>
      )}
      <span className="wfc-sr" aria-live="polite">
        {announce}
      </span>
    </div>
  );
}
