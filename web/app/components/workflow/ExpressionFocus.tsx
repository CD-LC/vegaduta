"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

// Coordination between the data picker and the expression fields, scoped to a
// provider (the config drawer mounts one), never global state: each field
// registers an `insert` handler, remembers itself as "last focused", and the
// picker inserts into that one. The last target survives blur on purpose:
// clicking a picker leaf blurs the field.

export type InsertTarget = {
  nodeId: string;
  field: string;
  /** Insert a bare expression (no braces) at the field's caret. */
  insert: (expr: string) => void;
};

type Registry = {
  register: (target: InsertTarget) => () => void;
  markActive: (nodeId: string, field: string) => void;
  /** Field the next picker click lands in, e.g. "url"; null when none was focused yet. */
  activeField: string | null;
  insert: (expr: string) => boolean;
};

const Ctx = createContext<Registry | null>(null);

const keyOf = (nodeId: string, field: string) => `${nodeId}\u0000${field}`;

export function ExpressionFocusProvider({ children }: { children: ReactNode }) {
  const targets = useRef(new Map<string, InsertTarget>());
  const lastKey = useRef<string | null>(null);
  const [activeField, setActiveField] = useState<string | null>(null);

  const register = useCallback((target: InsertTarget) => {
    const key = keyOf(target.nodeId, target.field);
    targets.current.set(key, target);
    return () => {
      if (targets.current.get(key) === target) targets.current.delete(key);
      if (lastKey.current === key && !targets.current.has(key)) {
        lastKey.current = null;
        setActiveField(null);
      }
    };
  }, []);

  const markActive = useCallback((nodeId: string, field: string) => {
    lastKey.current = keyOf(nodeId, field);
    setActiveField(field);
  }, []);

  const insert = useCallback((expr: string) => {
    const t = lastKey.current ? targets.current.get(lastKey.current) : undefined;
    if (!t) return false;
    t.insert(expr);
    return true;
  }, []);

  const value = useMemo(() => ({ register, markActive, activeField, insert }), [register, markActive, activeField, insert]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useExpressionFocus(): Registry | null {
  return useContext(Ctx);
}

// ------------------------------------------------- "jump to field" requests
// The lint panel's "Open settings" opens a node's drawer and should land in
// the offending field. The drawer mounts after the request, so it is kept for
// a few seconds and consumed by the matching field when it appears.

export const FOCUS_FIELD_EVENT = "wf:focus-expression-field";
const PENDING_TTL_MS = 4000;
let pending: { nodeId: string; field: string; at: number } | null = null;

export function requestExpressionFieldFocus(nodeId: string, field: string): void {
  pending = { nodeId, field, at: Date.now() };
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(FOCUS_FIELD_EVENT, { detail: { nodeId, field } }));
}

/** True (once) when a fresh request targets this field. */
export function consumeExpressionFieldFocus(nodeId: string, field: string): boolean {
  if (!pending || pending.nodeId !== nodeId || pending.field !== field) return false;
  const fresh = Date.now() - pending.at < PENDING_TTL_MS;
  pending = null;
  return fresh;
}

/** Calls `focus` when a request for this field arrives, or already did just before mount. */
export function useExpressionFieldFocusRequests(nodeId: string, field: string, focus: () => void): void {
  useEffect(() => {
    if (consumeExpressionFieldFocus(nodeId, field)) focus();
    function onEvent(e: Event) {
      const d = (e as CustomEvent<{ nodeId: string; field: string }>).detail;
      if (d?.nodeId === nodeId && d?.field === field && consumeExpressionFieldFocus(nodeId, field)) focus();
    }
    window.addEventListener(FOCUS_FIELD_EVENT, onEvent);
    return () => window.removeEventListener(FOCUS_FIELD_EVENT, onEvent);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- focus is read at call time via the caller's ref
  }, [nodeId, field]);
}
