"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { AlertTriangle, AlertOctagon, CheckCircle2, Info, X } from "lucide-react";
import { onToast, type Toast } from "../lib/toastBus";
import { useTranslations } from "../lib/i18n";
import { TOAST_AVOID_ATTR, toastTopOffset } from "../lib/toastPlacement";

const ICONS = { error: AlertOctagon, warning: AlertTriangle, success: CheckCircle2, info: Info };

// Belt-and-suspenders on top of toastBus.js's own de-dupe window: even
// with identical messages collapsed, a genuinely down backend can still
// raise several DIFFERENT real errors at once (e.g. Dashboard's four
// independent stat fetches). Capping the visible stack means a burst never
// covers the whole screen - the oldest simply rolls off once a newer one
// arrives past this count, same idea as a toast library's own maxVisible.
const MAX_VISIBLE = 4;

type TimerEntry = { start: number; remaining: number; timeoutId: number | null };

/**
 * HUD alert stack - mounted once in layout.js (same "survives client-side
 * navigation" reasoning as GlobalCursorFX/GlobalBackgroundFX), fed entirely
 * by toastBus.js. Every failed api.js call already pushes here (see
 * toJson()); anything else in the console can call toast.warn()/success()/
 * info() from lib/toastBus.js to add its own.
 *
 * Visual language matches the HUD dashboard rebuild - mono labels, a
 * left accent rim colored per kind, corner-bracket-style top border rather
 * than a generic rounded rectangle - rendered via CSS (see globals.css's
 * .hud-toast-* rules) so it inherits the light/dark HUD tokens automatically
 * instead of hardcoding colors here.
 *
 * Dismissal (UI audit follow-up): error toasts persist until the person
 * explicitly closes them - a server error message is worth reading, not
 * racing a fixed timer - and every other kind's countdown pauses while the
 * toast is hovered or keyboard-focused, resuming from where it left off.
 */
export default function ToastHost() {
  const t = useTranslations("toastHost");
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [pausedIds, setPausedIds] = useState<Set<string>>(new Set());
  const timers = useRef<Map<string, TimerEntry>>(new Map());

  const dismiss = useCallback((id: string) => {
    const entry = timers.current.get(id);
    if (entry?.timeoutId != null) window.clearTimeout(entry.timeoutId);
    timers.current.delete(id);
    setToasts((list) => list.filter((item) => item.id !== id));
  }, []);

  const scheduleDismiss = useCallback(
    (toast: Toast) => {
      // Errors persist until dismissed by hand - see file header. Everything
      // else gets its normal timer, restartable via pause/resume below.
      if (toast.kind === "error" || !(toast.duration > 0)) return;
      const timeoutId = window.setTimeout(() => dismiss(toast.id), toast.duration);
      timers.current.set(toast.id, { start: Date.now(), remaining: toast.duration, timeoutId });
    },
    [dismiss]
  );

  const pauseDismiss = useCallback((id: string) => {
    const entry = timers.current.get(id);
    if (!entry || entry.timeoutId == null) return;
    window.clearTimeout(entry.timeoutId);
    entry.remaining -= Date.now() - entry.start;
    entry.timeoutId = null;
    setPausedIds((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
  }, []);

  const resumeDismiss = useCallback(
    (id: string) => {
      const entry = timers.current.get(id);
      setPausedIds((prev) => {
        if (!prev.has(id)) return prev;
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      if (!entry || entry.timeoutId != null || entry.remaining <= 0) return;
      entry.start = Date.now();
      entry.timeoutId = window.setTimeout(() => dismiss(id), entry.remaining);
    },
    [dismiss]
  );

  useEffect(() => {
    return onToast((toast) => {
      setToasts((list) => {
        const next = [...list, toast];
        return next.length > MAX_VISIBLE ? next.slice(next.length - MAX_VISIBLE) : next;
      });
      scheduleDismiss(toast);
    });
  }, [scheduleDismiss]);

  // Never sit on a toolbar: while anything is showing, keep the stack just
  // below the lowest [data-toast-avoid] bar it would overlap (the app header,
  // the workflow canvas action row). See lib/toastPlacement.ts.
  const stackRef = useRef<HTMLDivElement | null>(null);
  const [top, setTop] = useState<number | null>(null);
  const visible = toasts.length > 0;
  useEffect(() => {
    if (!visible) return undefined;
    let frame = 0;
    const measure = () => {
      frame = 0;
      const rects = Array.from(document.querySelectorAll(`[${TOAST_AVOID_ATTR}]`)).map((el) =>
        el.getBoundingClientRect()
      );
      const width = stackRef.current?.getBoundingClientRect().width || 0;
      setTop(toastTopOffset(rects, { width: window.innerWidth, height: window.innerHeight }, width));
    };
    const schedule = () => {
      if (!frame) frame = window.requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener("resize", schedule);
    window.addEventListener("scroll", schedule, { capture: true, passive: true });
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      window.removeEventListener("resize", schedule);
      window.removeEventListener("scroll", schedule, { capture: true });
    };
  }, [visible]);

  return (
    <div
      ref={stackRef}
      className="hud-toast-stack"
      style={top != null ? { top } : undefined}
      aria-live="polite"
      aria-atomic="false"
    >
      <AnimatePresence>
        {toasts.map((toast) => {
          const Icon = ICONS[toast.kind] || Info;
          const persistent = toast.kind === "error" || !(toast.duration > 0);
          const paused = pausedIds.has(toast.id);
          return (
            <motion.div
              key={toast.id}
              layout
              initial={{ opacity: 0, x: 40, scale: 0.96 }}
              animate={{ opacity: 1, x: 0, scale: 1 }}
              exit={{ opacity: 0, scale: 0.94, transition: { duration: 0.15 } }}
              transition={{ duration: 0.28, ease: [0.16, 1, 0.3, 1] }}
              className={`hud-toast hud-toast-${toast.kind}`}
              role={toast.kind === "error" ? "alert" : "status"}
              onMouseEnter={() => pauseDismiss(toast.id)}
              onMouseLeave={() => resumeDismiss(toast.id)}
              onFocus={() => pauseDismiss(toast.id)}
              onBlur={() => resumeDismiss(toast.id)}
            >
              <span className="hud-toast-icon">
                <Icon size={16} strokeWidth={2.2} aria-hidden="true" />
              </span>
              <span className="hud-toast-body">
                <span className="hud-toast-kind">{toast.kind}</span>
                <span className="hud-toast-message">{toast.message}</span>
              </span>
              <button
                type="button"
                className="hud-toast-close"
                onClick={() => dismiss(toast.id)}
                aria-label={t("dismiss")}
              >
                <X size={13} aria-hidden="true" />
              </button>
              {!persistent && (
                <span
                  className="hud-toast-timer"
                  style={{
                    animationDuration: `${toast.duration}ms`,
                    animationPlayState: paused ? "paused" : "running",
                  }}
                />
              )}
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
