"use client";

// Shared plumbing of the canvas comment cards (CommentThread, NewCommentCard,
// CommentComposer): the threads-updater contract, a ticking clock for relative
// times, focus return on unmount and small label helpers.
import { useCallback, useEffect, useRef, useState, type FocusEvent as ReactFocusEvent, type RefObject } from "react";
import type { CommentView } from "./comments";

/**
 * "start" = optimistic change for a request now in flight; "settle" = that
 * request finished (confirm or rollback). Every "start" is paired with
 * exactly one "settle". useWorkflowComments uses the pair to drop GETs that
 * raced a mutation; React's plain setState ignores the second argument.
 */
export type MutationPhase = "start" | "settle";
export type ThreadsUpdater = (update: (prev: CommentView[]) => CommentView[], phase?: MutationPhase) => void;
export type MentionPerson = { id: string; displayName: string | null };

/** Re-render every `ms` so relative times stay fresh. */
export function useNow(ms = 60_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/**
 * ApprovalPauseCard's focus bookkeeping: remember where focus entered from,
 * and when the card unmounts holding focus, put it back (or on the fallback).
 */
export function useReturnFocus(ref: RefObject<HTMLElement | null>, autoFocus: boolean, onFocusFallback?: () => void) {
  const returnRef = useRef<HTMLElement | null>(null);
  const hasFocusRef = useRef(false);
  const fallbackRef = useRef(onFocusFallback);
  useEffect(() => {
    fallbackRef.current = onFocusFallback;
  }, [onFocusFallback]);

  useEffect(() => {
    if (!autoFocus) return;
    const a = document.activeElement;
    if (a instanceof HTMLElement && !ref.current?.contains(a)) returnRef.current = a;
    ref.current?.focus();
  }, [autoFocus, ref]);

  useEffect(() => {
    return () => {
      if (!hasFocusRef.current) return;
      const back = returnRef.current;
      const fallback = fallbackRef.current;
      setTimeout(() => {
        const active = document.activeElement;
        if (active && active !== document.body) return;
        if (back && back.isConnected) back.focus();
        else fallback?.();
      }, 0);
    };
  }, []);

  const onFocusCapture = useCallback((e: ReactFocusEvent<HTMLElement>) => {
    hasFocusRef.current = true;
    const from = e.relatedTarget;
    if (from instanceof HTMLElement && !ref.current?.contains(from)) returnRef.current = from;
  }, [ref]);

  const onBlurCapture = useCallback((e: ReactFocusEvent<HTMLElement>) => {
    const to = e.relatedTarget;
    if (to instanceof Node && ref.current?.contains(to)) return;
    if (to) hasFocusRef.current = false;
  }, [ref]);

  return { onFocusCapture, onBlurCapture, hasFocusRef };
}

export function initials(label: string): string {
  const parts = label.replace(/^user\s+/, "").split(/\s+/).filter(Boolean);
  const s = parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : (parts[0] ?? "?").slice(0, 2);
  return s.toUpperCase();
}

export function fullTime(iso: string | null): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  return Number.isFinite(t) ? new Date(t).toLocaleString() : "";
}

