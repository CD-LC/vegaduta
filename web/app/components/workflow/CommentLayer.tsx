"use client";

/*
 * Canvas-level comment layer. Mount it as an absolutely positioned sibling
 * of the React Flow pane inside the canvas stage (the element that is the
 * offsetParent of ApprovalPauseCard). It fills its parent, lets pointer
 * events through except on its own pins/cards, and clips to the stage.
 *
 * <CommentLayer> props
 *   workflowId        string
 *   threads           CommentView[] (roots) - from useWorkflowComments below
 *   onThreadsChange   (updater) => void - pass the hook's setThreads
 *   openThreadId      string | null - root id of the open thread
 *   onOpenThread      (id | null) => void
 *   pinScreenPos      (target: {x,y} flow anchor | nodeId string) => {x,y} | null
 *                     px relative to the layer box, e.g. flowToScreenPosition()
 *                     minus the stage rect; for a nodeId return the point the
 *                     card should hug (node's top-right). null = not visible.
 *                     Re-render the layer on viewport change (give this
 *                     function a new identity per viewport) so pins follow.
 *   people?           {id, displayName}[] for @mentions (empty hides the picker)
 *   currentUserId? / currentUserName?
 *   mode?             'view' | 'place' - 'place' shows a crosshair capture
 *                     surface; a click calls onPlaced(clientX, clientY)
 *                     (feed straight into reactFlow.screenToFlowPosition),
 *                     Enter places at the centre, Escape calls onCancelPlace
 *   onPlaced? / onCancelPlace?
 *   newThread?        NewThreadTarget | null - show the new-comment card at a
 *                     node ({nodeId}) or flow point ({x,y}). It wins over an
 *                     open thread: the layer calls onOpenThread(null) itself
 *                     when newThread becomes non-null, so the host need not.
 *   onNewThreadDone?  (created | null) => void - MUST clear `newThread` here; on
 *                     create the thread is already inserted and opened
 *   panelOpen? / onPanelOpenChange?  the comments list panel (docked right)
 *   nodeLabel?        (nodeId) => string
 *   onFocusTarget?    (nodeId | {x,y}) => void - pan the canvas to a thread
 *                     picked in the panel / "Show step"
 *   showResolvedPins? default true (resolved pins render dimmed)
 *
 * <CommentCountChip open resolved onClick?> - node footer chip; renders
 *   nothing at 0/0. Carries "nodrag nopan" so clicks don't drag the node.
 *
 * useWorkflowComments(workflowId, {enabled}) - loads threads, feature-detects
 *   (status 'unavailable' on a 404/405 core: render no comment UI at all),
 *   refreshes when the tab regains focus, keeps unconfirmed local rows.
 *   Pass its `setThreads` (not a raw React setter) as onThreadsChange: it
 *   tracks the cards' "start"/"settle" mutation phases and drops any GET that
 *   overlapped a mutation (re-fetching once they settle), so a slow refresh
 *   can't undo a confirmed reply/edit/resolve/delete.
 */

import {
  useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
  type ComponentProps, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type RefObject,
} from "react";
import { Check, MessageSquare, MessageSquarePlus, X } from "lucide-react";
import CommentThread, { NewCommentCard, type MentionPerson, type ThreadsUpdater } from "./CommentThread";
import {
  canvasPins,
  filterThreads,
  insertComment,
  mergePending,
  personLabel,
  relativeTime,
  threadCounts,
  threadNumbers,
  type CommentAnchor,
  type CommentCounts,
  type CommentFilter,
  type CommentView,
  type NewThreadTarget,
} from "./comments";
import { listWorkflowComments } from "../../lib/api/workflows";
import "./comments.css"; import { useTranslations } from "../../lib/i18n";

export type ScreenPoint = { x: number; y: number };
export type PinTarget = CommentAnchor | string;

/* ------------------------------------------------------------- data hook */

export type CommentsStatus = "idle" | "loading" | "ready" | "unavailable" | "error";

export function useWorkflowComments(workflowId: string | null | undefined, opts: { enabled?: boolean; refreshOnFocus?: boolean } = {}) {
  const { enabled = true, refreshOnFocus = true } = opts;
  const [threads, setThreads] = useState<CommentView[]>([]);
  const [status, setStatus] = useState<CommentsStatus>("idle");
  const statusRef = useRef<CommentsStatus>("idle");
  useEffect(() => {
    statusRef.current = status;
  }, [status]);
  const seq = useRef(0);
  // Mutation guard: `epoch` bumps on every local change, `inflight` counts
  // optimistic "start"s not yet "settle"d. A GET that overlapped either is
  // older than what the user sees, so it is dropped and re-run once idle.
  const epoch = useRef(0);
  const inflight = useRef(0);
  const staleReload = useRef(false);
  const reloadRef = useRef<() => Promise<void>>(async () => {});

  const reload = useCallback(async () => {
    if (!workflowId || !enabled || statusRef.current === "unavailable") return;
    const mine = ++seq.current;
    const startEpoch = epoch.current;
    setStatus((s) => (s === "ready" ? s : "loading"));
    const r = await listWorkflowComments(workflowId);
    if (mine !== seq.current) return;
    if (r.ok) {
      if (epoch.current !== startEpoch || inflight.current > 0) {
        staleReload.current = true;
        if (inflight.current === 0) void reloadRef.current();
        return;
      }
      staleReload.current = false;
      setThreads((local) => mergePending(r.data, local));
      setStatus("ready");
    } else {
      setStatus((s) => (r.unsupported ? "unavailable" : s === "ready" ? s : "error"));
    }
  }, [workflowId, enabled]);
  useEffect(() => {
    reloadRef.current = reload;
  }, [reload]);

  useEffect(() => {
    setThreads([]);
    setStatus("idle");
    statusRef.current = "idle";
    epoch.current++;
    inflight.current = 0;
    staleReload.current = false;
  }, [workflowId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (!refreshOnFocus) return undefined;
    const onVis = () => {
      if (document.visibilityState === "visible") void reload();
    };
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, [reload, refreshOnFocus]);

  const counts = useMemo(() => threadCounts(threads), [threads]);
  const setAll = useCallback<ThreadsUpdater>((update, phase) => {
    epoch.current++;
    if (phase === "start") inflight.current++;
    else if (phase === "settle") inflight.current = Math.max(0, inflight.current - 1);
    setThreads(update);
    if (phase === "settle" && inflight.current === 0 && staleReload.current) {
      staleReload.current = false;
      void reloadRef.current();
    }
  }, []);
  return { threads, setThreads: setAll, status, reload, counts };
}

/* ------------------------------------------------------------- count chip */

export interface CommentCountChipProps extends CommentCounts {
  onClick?: () => void;
  className?: string;
}

export function CommentCountChip({ open, resolved, onClick, className }: CommentCountChipProps) {
  if (open + resolved === 0) return null;
  const label = `${open} open comment${open === 1 ? "" : "s"}${resolved ? `, ${resolved} resolved` : ""}`;
  const onlyResolved = open === 0;
  const content = (
    <>
      {onlyResolved ? <Check size={10} aria-hidden="true" /> : <MessageSquare size={10} aria-hidden="true" />}
      <span aria-hidden="true">{onlyResolved ? resolved : open}</span>
    </>
  );
  const cls = `wfx-chip wfc-count nodrag nopan${className ? ` ${className}` : ""}`;
  if (!onClick) {
    return (
      <span className={cls} data-resolved={onlyResolved || undefined} role="img" aria-label={label} title={label}>
        {content}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={cls}
      data-resolved={onlyResolved || undefined}
      aria-label={`${label}. Open comments`}
      title={label}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
    >
      {content}
    </button>
  );
}

/* -------------------------------------------------------------- placement */

type CardPos = { left: number; top: number };

/** Hug `point` (right, then left), clamped inside the layer. Null = dock. */
function useCardPlacement(point: ScreenPoint | null) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<CardPos | null>(null);
  const px = point?.x;
  const py = point?.y;

  const place = useCallback(() => {
    const el = ref.current;
    const parent = el?.offsetParent;
    if (!el || !(parent instanceof HTMLElement) || px === undefined || py === undefined
      || window.matchMedia("(max-width: 639.98px)").matches) {
      setPos((p) => (p === null ? p : null));
      return;
    }
    const pw = parent.clientWidth;
    const ph = parent.clientHeight;
    const cw = el.offsetWidth;
    const ch = el.offsetHeight;
    const gap = 14;
    let left = px + gap;
    if (left + cw > pw - 8) left = px - cw - gap >= 8 ? px - cw - gap : Math.max(8, pw - cw - 8);
    const top = Math.round(Math.min(Math.max(8, py - 12), Math.max(8, ph - ch - 8)));
    left = Math.round(left);
    setPos((p) => (p && p.left === left && p.top === top ? p : { left, top }));
  }, [px, py]);

  useLayoutEffect(() => {
    place();
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return undefined;
    const ro = new ResizeObserver(() => place());
    ro.observe(el);
    if (el.offsetParent instanceof HTMLElement) ro.observe(el.offsetParent);
    return () => ro.disconnect();
  }, [place]);

  const style: CSSProperties | undefined = pos
    ? ({ "--wfc-left": `${pos.left}px`, "--wfc-top": `${pos.top}px` } as CSSProperties)
    : undefined;
  return { ref, style, anchored: pos !== null };
}

/* ------------------------------------------------------------------ layer */

export interface CommentLayerProps {
  workflowId: string;
  threads: CommentView[];
  onThreadsChange: ThreadsUpdater;
  openThreadId: string | null;
  onOpenThread: (id: string | null) => void;
  pinScreenPos: (target: PinTarget) => ScreenPoint | null;
  people?: MentionPerson[];
  currentUserId?: string;
  currentUserName?: string;
  mode?: "view" | "place";
  onPlaced?: (clientX: number, clientY: number) => void;
  onCancelPlace?: () => void;
  newThread?: NewThreadTarget | null;
  onNewThreadDone?: (created: CommentView | null) => void;
  panelOpen?: boolean;
  onPanelOpenChange?: (open: boolean) => void;
  nodeLabel?: (nodeId: string) => string;
  onFocusTarget?: (target: PinTarget) => void;
  showResolvedPins?: boolean;
}

const FILTERS: { id: CommentFilter; label: string }[] = [
  { id: "open", label: "Open" },
  { id: "resolved", label: "Resolved" },
  { id: "all", label: "All" },
];

function targetOf(t: CommentView): PinTarget | null {
  return t.nodeId ?? t.anchor ?? null;
}

export default function CommentLayer({
  workflowId, threads, onThreadsChange, openThreadId, onOpenThread, pinScreenPos, people = [],
  currentUserId, currentUserName, mode = "view", onPlaced, onCancelPlace, newThread = null, onNewThreadDone,
  panelOpen = false, onPanelOpenChange, nodeLabel, onFocusTarget, showResolvedPins = true,
}: CommentLayerProps) {
    const tHC = useTranslations("hcComponentsWorkflowCommentLayer");
  const layerRef = useRef<HTMLDivElement>(null);
  const numbers = useMemo(() => threadNumbers(threads), [threads]);
  const pins = useMemo(() => canvasPins(threads), [threads]);
  const openThread = openThreadId ? threads.find((t) => t.id === openThreadId) ?? null : null;
  // Focus the card only when the user opened it here (pin/panel), never on a
  // background refresh or a deep link that lands while they type elsewhere.
  const [focusOpen, setFocusOpen] = useState(false);

  // A thread that vanished (deleted, or a refresh dropped it) closes its card.
  const seenOpen = useRef(false);
  useEffect(() => {
    if (openThread) seenOpen.current = true;
    else if (openThreadId && seenOpen.current) {
      seenOpen.current = false;
      onOpenThread(null);
    }
    if (!openThreadId) seenOpen.current = false;
  }, [openThread, openThreadId, onOpenThread]);

  // A new-comment request wins over an open thread card: close the thread so
  // the composer shows (the host does not have to do it).
  // Keyed on newThread only (via refs): a thread opened right after create
  // must not be closed again.
  const openIdRef = useRef(openThreadId);
  const onOpenRef = useRef(onOpenThread);
  useEffect(() => {
    openIdRef.current = openThreadId;
    onOpenRef.current = onOpenThread;
  }, [openThreadId, onOpenThread]);
  useEffect(() => {
    if (newThread && openIdRef.current) {
      setFocusOpen(false);
      onOpenRef.current(null);
    }
  }, [newThread]);

  const openFromUser = useCallback((id: string) => {
    setFocusOpen(true);
    onOpenThread(id);
  }, [onOpenThread]);

  const openPoint = openThread ? (() => {
    const t = targetOf(openThread);
    return t && !openThread.orphaned ? pinScreenPos(t) : null;
  })() : null;
  const newPoint = newThread ? pinScreenPos("nodeId" in newThread ? newThread.nodeId : newThread) : null;

  const focusLayerFallback = useCallback(() => layerRef.current?.focus(), []);

  return (
    <div
      ref={layerRef}
      className="wfc-layer"
      data-panel-open={panelOpen || undefined}
      data-mode={mode}
      tabIndex={-1}
      aria-label={tHC("hcComponentsWorkflowCommentLayer.s6")}
    >
      {pins.map(({ thread, number, anchor }) => {
        if (thread.resolved && !showResolvedPins && thread.id !== openThreadId) return null;
        const p = pinScreenPos(anchor);
        if (!p) return null;
        const isOpen = thread.id === openThreadId;
        const count = thread.replies.length;
        const snippet = (thread.body ?? "").slice(0, 80);
        return (
          <button
            key={thread.id}
            type="button"
            className="wfc-pin"
            data-resolved={thread.resolved || undefined}
            data-open={isOpen || undefined}
            style={{ "--wfc-x": `${Math.round(p.x)}px`, "--wfc-y": `${Math.round(p.y)}px` } as CSSProperties}
            aria-expanded={isOpen}
            aria-label={`Comment ${number} by ${personLabel(thread.author)}${thread.resolved ? ", resolved" : ""}${count ? `, ${count} ${count === 1 ? "reply" : "replies"}` : ""}${snippet ? `: ${snippet}` : ""}`}
            onClick={() => (isOpen ? onOpenThread(null) : openFromUser(thread.id))}
          >
            {thread.resolved ? <Check size={11} aria-hidden="true" /> : <span aria-hidden="true">{number}</span>}
          </button>
        );
      })}

      {newThread && "x" in newThread && newPoint && (
        <span
          className="wfc-pin wfc-pin-ghost"
          aria-hidden="true"
          style={{ "--wfc-x": `${Math.round(newPoint.x)}px`, "--wfc-y": `${Math.round(newPoint.y)}px` } as CSSProperties}
        >
          <MessageSquarePlus size={11} />
        </span>
      )}

      {mode === "place" && (
        <PlaceSurface layerRef={layerRef} onPlaced={onPlaced} onCancel={onCancelPlace} />
      )}

      {panelOpen && (
        <CommentsPanel
          threads={threads}
          numbers={numbers}
          openThreadId={openThreadId}
          nodeLabel={nodeLabel}
          onPick={(t) => {
            const target = targetOf(t);
            if (target && !t.orphaned) onFocusTarget?.(target);
            openFromUser(t.id);
          }}
          onClose={() => onPanelOpenChange?.(false)}
        />
      )}

      {openThread && !newThread && (
        <AnchoredThread
          key={openThread.id}
          point={openPoint}
          workflowId={workflowId}
          thread={openThread}
          number={numbers.get(openThread.id)}
          people={people}
          currentUserId={currentUserId}
          currentUserName={currentUserName}
          onThreadsChange={onThreadsChange}
          onClose={() => {
            setFocusOpen(false);
            onOpenThread(null);
          }}
          nodeLabel={nodeLabel}
          onFocusNode={onFocusTarget ? (id) => onFocusTarget(id) : undefined}
          autoFocus={focusOpen}
          onFocusFallback={focusLayerFallback}
        />
      )}

      {newThread && (
        <AnchoredNew
          key={"nodeId" in newThread ? `n:${newThread.nodeId}` : `p:${newThread.x},${newThread.y}`}
          point={newPoint}
          workflowId={workflowId}
          target={newThread}
          people={people}
          nodeLabel={nodeLabel}
          onFocusFallback={focusLayerFallback}
          onCreated={(view) => {
            onThreadsChange((prev) => insertComment(prev, { ...view, replies: [] }));
            setFocusOpen(true);
            onOpenThread(view.id);
            onNewThreadDone?.(view);
          }}
          onCancel={() => onNewThreadDone?.(null)}
        />
      )}
    </div>
  );
}

/* ------------------------------------------------ anchored card wrappers */

type AnchoredThreadProps = Omit<ComponentProps<typeof CommentThread>, "style" | "anchored"> & { point: ScreenPoint | null };

function AnchoredThread({ point, ...rest }: AnchoredThreadProps) {
  const { ref, style, anchored } = useCardPlacement(point);
  return (
    <div ref={ref} className="wfc-anchor" style={style} data-anchored={anchored || undefined}>
      <CommentThread {...rest} />
    </div>
  );
}

type AnchoredNewProps = Omit<ComponentProps<typeof NewCommentCard>, "style" | "anchored"> & { point: ScreenPoint | null };

function AnchoredNew({ point, ...rest }: AnchoredNewProps) {
  const { ref, style, anchored } = useCardPlacement(point);
  return (
    <div ref={ref} className="wfc-anchor" style={style} data-anchored={anchored || undefined}>
      <NewCommentCard {...rest} />
    </div>
  );
}

/* ------------------------------------------------------- place surface */

function PlaceSurface({
  layerRef, onPlaced, onCancel,
}: { layerRef: RefObject<HTMLDivElement | null>; onPlaced?: (x: number, y: number) => void; onCancel?: () => void }) {
    const tHC = useTranslations("hcComponentsWorkflowCommentLayer");
  const ref = useRef<HTMLDivElement>(null);
  const hintId = useId();
  useEffect(() => {
    ref.current?.focus();
  }, []);

  function placeCentre() {
    const r = layerRef.current?.getBoundingClientRect();
    if (r) onPlaced?.(r.left + r.width / 2, r.top + r.height / 2);
  }

  return (
    <div
      ref={ref}
      className="wfc-place"
      role="button"
      tabIndex={0}
      aria-label={tHC("hcComponentsWorkflowCommentLayer.s5")}
      aria-describedby={hintId}
      onClick={(e) => onPlaced?.(e.clientX, e.clientY)}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          e.stopPropagation();
          onCancel?.();
        } else if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          placeCentre();
        }
      }}
    >
      <span id={hintId} className="wfc-place-hint">
        {tHC("hcComponentsWorkflowCommentLayer.s4")}
      </span>
    </div>
  );
}

/* ------------------------------------------------------------ list panel */

function CommentsPanel({
  threads, numbers, openThreadId, nodeLabel, onPick, onClose,
}: {
  threads: CommentView[];
  numbers: Map<string, number>;
  openThreadId: string | null;
  nodeLabel?: (nodeId: string) => string;
  onPick: (t: CommentView) => void;
  onClose: () => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowCommentLayer");
  const uid = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [filter, setFilter] = useState<CommentFilter>("open");
  const [active, setActive] = useState(0);
  const [now] = useState(() => Date.now());
  const shown = useMemo(() => filterThreads(threads, filter), [threads, filter]);
  const openCount = threads.filter((t) => !t.resolved).length;
  const activeIdx = Math.min(active, Math.max(0, shown.length - 1));

  useEffect(() => {
    rootRef.current?.focus();
  }, []);

  function onKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
    if (e.key === "Escape" && !e.defaultPrevented) {
      e.preventDefault();
      e.stopPropagation();
      onClose();
    }
  }

  function onRowKey(e: ReactKeyboardEvent<HTMLButtonElement>, i: number) {
    let next = -1;
    if (e.key === "ArrowDown") next = Math.min(shown.length - 1, i + 1);
    else if (e.key === "ArrowUp") next = Math.max(0, i - 1);
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = shown.length - 1;
    if (next < 0) return;
    e.preventDefault();
    setActive(next);
    rowRefs.current[next]?.focus();
  }

  function onFilterKey(e: ReactKeyboardEvent<HTMLButtonElement>, i: number) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    e.preventDefault();
    const n = (i + (e.key === "ArrowRight" ? 1 : -1) + FILTERS.length) % FILTERS.length;
    setFilter(FILTERS[n].id);
    setActive(0);
    (e.currentTarget.parentElement?.children[n] as HTMLElement | undefined)?.focus();
  }

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog keyboard handling (Escape/arrows) bubbles from focusable children
    <div
      ref={rootRef}
      className="wfc-panel"
      role="dialog"
      aria-labelledby={`${uid}-t`}
      tabIndex={-1}
      onKeyDown={onKeyDown}
      onPointerDown={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
    >
      <div className="wfc-head">
        <div className="wfc-title">
          <MessageSquare size={13} aria-hidden="true" />
          <strong id={`${uid}-t`}>Comments</strong>
          <span className="wfc-sub">{openCount} open</span>
        </div>
        <button type="button" className="wfc-icon" aria-label={tHC("hcComponentsWorkflowCommentLayer.s3")} onClick={onClose}>
          <X size={14} aria-hidden="true" />
        </button>
      </div>
      <div className="wfc-filters" role="radiogroup" aria-label="Show">
        {FILTERS.map((f, i) => (
          <button
            key={f.id}
            type="button"
            role="radio"
            aria-checked={filter === f.id}
            tabIndex={filter === f.id ? 0 : -1}
            className="wfc-filter"
            onClick={() => {
              setFilter(f.id);
              setActive(0);
            }}
            onKeyDown={(e) => onFilterKey(e, i)}
          >
            {f.label}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="wfc-empty">
          {threads.length === 0
            ? "No comments yet. Add one from a step or the comment tool."
            : filter === "open" ? "Nothing open - every thread is resolved." : "No threads here."}
        </p>
      ) : (
        <ul className="wfc-rows" aria-label={`${shown.length} thread${shown.length === 1 ? "" : "s"}`}>
          {shown.map((t, i) => {
            const n = numbers.get(t.id);
            const where = t.nodeId ? (nodeLabel?.(t.nodeId) || "Step") : "Canvas";
            const replies = t.replies.length;
            const last = replies ? t.replies[replies - 1] : t;
            return (
              <li key={t.id}>
                <button
                  ref={(el) => {
                    rowRefs.current[i] = el;
                  }}
                  type="button"
                  className="wfc-row"
                  data-resolved={t.resolved || undefined}
                  aria-current={t.id === openThreadId ? "true" : undefined}
                  tabIndex={i === activeIdx ? 0 : -1}
                  onFocus={() => setActive(i)}
                  onKeyDown={(e) => onRowKey(e, i)}
                  onClick={() => onPick(t)}
                >
                  <span className="wfc-row-num" aria-hidden="true">
                    {t.resolved ? <Check size={11} /> : n}
                  </span>
                  <span className="wfc-row-main">
                    <span className="wfc-row-top">
                      <span className="wfc-row-where">
                        <span className="wfc-sr">Thread {n}{t.resolved ? ", resolved" : ""}{t.orphaned ? ", step removed" : ""}, on </span>
                        {where}
                      </span>
                      <span className="wfc-row-time">{relativeTime(last.createdAt, now)}</span>
                    </span>
                    <span className="wfc-row-body">
                      {t.deleted ? <em>{tHC("hcComponentsWorkflowCommentLayer.s2")}</em> : <><strong>{personLabel(t.author)}:</strong> {t.body}</>}
                    </span>
                    <span className="wfc-row-meta">
                      {replies > 0 && `${replies} ${replies === 1 ? "reply" : "replies"}`}
                      {t.orphaned && <span className="wfc-row-tag">{tHC("hcComponentsWorkflowCommentLayer.s1")}</span>}
                      {t.resolved && <span className="wfc-row-tag" data-tone="ok">Resolved</span>}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
