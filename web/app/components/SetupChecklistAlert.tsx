"use client";

import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "motion/react";
import { Check, Circle, Sparkles, X } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { isTourPending, useTour } from "./TourProvider";

/**
 * "Get set up" - previously a large, always-open panel pinned to the top of
 * the Admin Overview page (see admin/page.js's prior revision). Direct
 * feedback: "remove get setup section and put it as an alert - a pop up
 * menu" - converted from a permanent section into a small floating trigger
 * pill + popover, matching the same popover-in pattern already used by
 * LanguageSwitcher/ThemePicker/CredentialHelp elsewhere in this console.
 * Auto-opens once per browser (first visit) so a brand-new tenant-admin
 * still discovers it without hunting, then stays a click-to-open affordance
 * after that - same "don't nag forever" posture as OnboardingTour.
 *
 * Never auto-opens while the product tour is still pending or running
 * (funnel audit, 2026-09): on a cold first login this popover, the tour's
 * welcome modal and AgentsGuide all used to arrive within a second of each
 * other. The tour goes first; because AUTO_OPEN_KEY is only written when
 * the popover actually auto-opens, the checklist simply takes its one
 * auto-open on the next /admin visit instead of being lost.
 *
 * On SUCCESSION (tour-rebuild audit, 2026-09) - checked, and no fix needed
 * here. AgentsGuide had a real hole: TourOverlay's secondary completion CTA
 * does router.push("/agents") and THEN finishTour(), so the guide mounts on the
 * destination route a frame after every tour guard has gone false, and it
 * auto-opened straight out of the tour. That handover cannot reach this
 * component, because this component only ever renders inside
 * app/admin/page.tsx and the tour's completion CTA routes to /agents. The
 * nearest thing to a handover is the channels step's "Show me", which goes
 * to /admin/channels - a different route, and the tour is still active
 * there anyway. Getting back to /admin after the tour is a deliberate
 * navigation by the user, minutes later and by their own choice, which is
 * exactly when this popover SHOULD take its one auto-open. So this file
 * keeps the overlap guards as-is and does not consult
 * tourEndedThisSession(); adding it would suppress a legitimate first-run
 * affordance for the rest of the session for no observed stacking.
 *
 * "completed" is added to the status list below only to close a latent
 * trap: it is the state while the tour's completion card is on screen, and
 * it was missing from the list. It is unreachable today purely because the
 * effect is mount-only - anyone adding a dependency array would ship a
 * popover opening underneath the tour's final modal.
 */
const AUTO_OPEN_KEY = "adminOverview.setupAlert.autoOpened.v1";

interface SetupStep {
  key: string;
  href: string;
  label: string;
  hint: string;
  done: boolean;
}

interface SetupChecklistAlertProps {
  steps: SetupStep[];
  allDone: boolean;
}

export default function SetupChecklistAlert({ steps, allDone }: SetupChecklistAlertProps) {
  const t = useTranslations("setupChecklistAlert");
  const tour = useTour();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerButtonRef = useRef<HTMLButtonElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const [popoverStyle, setPopoverStyle] = useState<CSSProperties>({});
  const doneCount = steps.filter((s: SetupStep) => s.done).length;
  const pct = Math.round((doneCount / steps.length) * 100);

  // Position the popover from the trigger's own bounding rect rather than
  // `absolute right-0` (which anchors to the trigger, not the viewport): at
  // phone width, with topbar siblings to the trigger's right, `right-0`
  // pushed a large chunk of the panel off the left edge of the screen with
  // no way to reach it. Recomputed each time the popover opens.
  useEffect(() => {
    if (!open) return undefined;
    const trigger = triggerButtonRef.current;
    if (!trigger) return undefined;
    const rect = trigger.getBoundingClientRect();
    const panelWidth = Math.min(320, window.innerWidth - 32);
    const left = Math.max(16, Math.min(rect.right - panelWidth, window.innerWidth - panelWidth - 16));
    setPopoverStyle({ position: "fixed", top: rect.bottom + 8, left, width: panelWidth });
  }, [open]);

  useEffect(() => {
    if (allDone) return;
    // Tour first - see the header comment. isTourPending() catches the
    // cold-load race (the welcome is armed on a timer, so tour.status still
    // reads "idle" here); the status check covers a replayed/running tour.
    if (isTourPending() || tour.status === "welcome" || tour.status === "active" || tour.status === "completed") {
      return;
    }
    try {
      if (!window.localStorage.getItem(AUTO_OPEN_KEY)) {
        setOpen(true);
        window.localStorage.setItem(AUTO_OPEN_KEY, "1");
      }
    } catch {
      // Private browsing / storage disabled - just stays closed until clicked.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    function onDocClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const popoverEl = popoverRef.current;
    if (!popoverEl) return undefined;
    // The trigger is always mounted; capture it so focus returns to it on close.
    const trigger = triggerButtonRef.current;

    const getFocusable = () => Array.from(popoverEl.querySelectorAll<HTMLElement>("a[href], button:not([disabled])"));

    const focusables = getFocusable();
    if (focusables.length) focusables[0].focus();

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        setOpen(false);
        return;
      }
      if (e.key !== "Tab") return;
      const items = getFocusable();
      if (!items.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      trigger?.focus();
    };
  }, [open]);

  if (allDone) return null;

  return (
    <div ref={rootRef} className="relative mb-5 inline-block shrink-0">
      <button
        type="button"
        ref={triggerButtonRef}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        // One line at every width (prod re-verification 2026-10-05, R6): in the 375px topbar the pill was squeezed
        // and wrapped to three lines, pushing the workspace name to "RU...". It never wraps or shrinks; below the sm
        // breakpoint the words give way to the icon and the 2/4 count, and the accessible name keeps the words.
        aria-label={`${t("getSetup")} ${doneCount}/${steps.length}`}
        className="inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-full border border-border bg-card px-3.5 py-1.5 text-sm font-medium text-foreground shadow-sm transition-colors hover:bg-accent"
      >
        <Sparkles aria-hidden="true" size={14} className="text-primary" />
        <span className="hidden sm:inline">{t("getSetup")}</span>
        <span className="ms-0.5 rounded-full bg-primary/10 px-1.5 py-0.5 text-[length:var(--fs-label)] font-semibold tabular-nums text-primary">
          {doneCount}/{steps.length}
        </span>
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            ref={popoverRef}
            style={popoverStyle}
            className="z-50 rounded-xl border border-border bg-card p-4 text-card-foreground shadow-xl"
            initial={{ opacity: 0, y: -6, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.97 }}
            transition={{ duration: 0.16, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="space-y-0.5">
                <h3 className="text-sm font-semibold tracking-tight text-foreground">{t("getSetup")}</h3>
                <span className="text-xs text-muted-foreground">
                  {t("doneCount", { done: doneCount, total: steps.length })}
                </span>
              </div>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label={t("closeAriaLabel")}
                className="flex size-7 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
              >
                <X aria-hidden="true" size={14} />
              </button>
            </div>
            <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-secondary">
              <motion.div
                className="h-full rounded-full bg-primary"
                initial={false}
                animate={{ width: `${pct}%` }}
                transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
              />
            </div>
            <p className="mt-2.5 text-xs leading-relaxed text-muted-foreground">{t("hintText")}</p>
            <div className="mt-3 space-y-1">
              {steps.map((step: SetupStep) => (
                <Link
                  key={step.key}
                  href={step.href}
                  className="flex items-start gap-2.5 rounded-lg px-2 py-2 transition-colors hover:bg-accent"
                  onClick={() => setOpen(false)}
                >
                  <span className="mt-0.5 shrink-0">
                    {step.done ? (
                      <Check size={16} className="text-primary" aria-hidden="true" />
                    ) : (
                      <Circle size={16} className="text-muted-foreground" aria-hidden="true" />
                    )}
                    <span className="sr-only">{step.done ? t("stepDone") : t("stepTodo")}</span>
                  </span>
                  <span className="min-w-0">
                    <strong
                      className={`block text-sm font-medium ${step.done ? "text-muted-foreground line-through" : "text-foreground"}`}
                    >
                      {step.label}
                    </strong>
                    <span className="block text-xs text-muted-foreground">{step.hint}</span>
                  </span>
                </Link>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
