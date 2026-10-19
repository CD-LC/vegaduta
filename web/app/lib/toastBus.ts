"use client";

// Global error/warning/success popup bus - same tiny pub/sub shape as
// pulseBus.js (GlobalBackgroundFX's reactive-background trigger), reused
// here for a different consumer (ToastHost.js) rather than inventing a
// second pattern for "fire an event, some mounted component reacts to it."
//
// Direct response to "all errors should be popup and also any warnings" -
// every api.ts call already throws a real Error with a real server-provided
// message (see toJson() in lib/api.ts) that individual panels catch and
// show inline (error-box divs). This bus doesn't replace that - it's wired
// into toJson() itself so EVERY failed API call across the whole console
// also raises a HUD popup with zero changes needed in any individual panel,
// while warn()/success()/info() stay available for anything that wants to
// push one deliberately (e.g. a degraded system-health reading).

export type ToastKind = "error" | "warning" | "success" | "info";

export interface Toast {
  id: string;
  kind: ToastKind;
  message: string;
  duration: number;
}

export interface ToastOptions {
  allowDuplicate?: boolean;
  duration?: number;
}

export type ToastListener = (toast: Toast) => void;

const LISTENERS = new Set<ToastListener>();
let seq = 0;

// De-dupe window: if a core-API outage fails N independent calls on the same
// page (e.g. Dashboard's refresh() makes 4 separate try/catches, Admin
// Overview's makes 6), every one of them used to raise its own popup -
// "so many error popups" for what is really one underlying problem. The fix
// isn't to stop toasting real failures, it's to collapse repeats of the
// EXACT SAME message into a single toast within a short window, same idea
// as a log aggregator collapsing a burst of identical lines.
const RECENT_WINDOW_MS = 4000;
const recentMessages = new Map<string, number>(); // message -> timestamp last shown

// Errors and warnings no longer pop up over the page: they land in this log and the
// notification bell shows them. Success/info toasts still pop up.
export interface ProblemEntry {
  id: string;
  kind: "error" | "warning";
  message: string;
  createdAt: number;
  read: boolean;
}

const MAX_PROBLEMS = 30;
let problems: ProblemEntry[] = [];
const PROBLEM_LISTENERS = new Set<() => void>();

function emitProblems() {
  PROBLEM_LISTENERS.forEach((fn) => fn());
}

export function subscribeProblems(fn: () => void): () => void {
  PROBLEM_LISTENERS.add(fn);
  return () => {
    PROBLEM_LISTENERS.delete(fn);
  };
}

export function getProblems(): ProblemEntry[] {
  return problems;
}

export function markAllProblemsRead() {
  if (!problems.some((p) => !p.read)) return;
  problems = problems.map((p) => (p.read ? p : { ...p, read: true }));
  emitProblems();
}

export function markProblemRead(id: string) {
  problems = problems.map((p) => (p.id === id && !p.read ? { ...p, read: true } : p));
  emitProblems();
}

export function onToast(fn: ToastListener): () => void {
  LISTENERS.add(fn);
  return () => {
    LISTENERS.delete(fn);
  };
}

function push(kind: ToastKind, message: unknown, opts: ToastOptions = {}): Toast | null {
  if (!message) return null;
  const text = String(message);

  if (!opts.allowDuplicate) {
    const key = `${kind}:${text}`;
    const now = Date.now();
    const last = recentMessages.get(key);
    if (last && now - last < RECENT_WINDOW_MS) return null; // same problem, already showing - skip the repeat
    recentMessages.set(key, now);
    // Keep the map from growing unbounded over a long session.
    if (recentMessages.size > 50) {
      const oldestKey = recentMessages.keys().next().value;
      if (oldestKey !== undefined) recentMessages.delete(oldestKey);
    }
  }

  const toast: Toast = {
    id: `toast-${Date.now()}-${seq++}`,
    kind, // "error" | "warning" | "success" | "info"
    message: text,
    // Errors/warnings linger longer than a success confirmation - a person
    // reacting to a problem needs more than a couple seconds to read it.
    duration: opts.duration ?? (kind === "error" || kind === "warning" ? 7000 : 4000),
  };
  if (kind === "error" || kind === "warning") {
    problems = [{ id: toast.id, kind, message: text, createdAt: Date.now(), read: false }, ...problems].slice(
      0,
      MAX_PROBLEMS
    );
    emitProblems();
    return toast;
  }
  LISTENERS.forEach((fn) => fn(toast));
  return toast;
}

export const toast = {
  error: (message: unknown, opts?: ToastOptions) => push("error", message, opts),
  warn: (message: unknown, opts?: ToastOptions) => push("warning", message, opts),
  success: (message: unknown, opts?: ToastOptions) => push("success", message, opts),
  info: (message: unknown, opts?: ToastOptions) => push("info", message, opts),
};
