"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { BookOpen, HelpCircle, MessageSquare, Rocket, SlidersHorizontal, Sparkles, Wrench, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { isTourPending, tourEndedThisSession, useTour } from "../TourProvider";
import { Button } from "../ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import BrandText from "../BrandText";

// First-run "how agents work" guide for the Agents screen — direct feedback:
// "add a proper guide to a consumer, which shows how Agents works on first
// time". Auto-opens once per browser (localStorage-gated) so a brand-new user
// gets oriented, and stays reopenable any time via the "How it works" button
// this component also renders. Deliberately self-contained (its own trigger +
// modal + seen-flag) so the parent workbench only has to drop <AgentsGuide />
// into its action bar.
//
// Never auto-opens while the product tour is pending or running (funnel
// audit, 2026-09): the tour's welcome modal sends a new tenant straight to
// /agents with step 1 spotlighting the create control, and this guide's
// z-[120] modal used to land on top of that z-[110] spotlight the moment the
// route mounted. The tour goes first; SEEN_KEY is only written when the guide
// actually auto-opens, so it still auto-opens once on a later visit. The
// "How it works" trigger is unaffected.
//
// SEEN_KEY is written at OPEN time, not close time (repeat-popup fix,
// 2026-09-23): the modal is a portal over the whole app, and the most common
// way people leave it is NOT the X/backdrop/Escape/CTA - it's clicking a nav
// link, which unmounts AgentWorkbench without ever running close(). That left
// the flag unset, so the guide auto-opened again on EVERY /agents visit.
// Rendering the modal IS the seeing of it, so open time is the correct mark;
// same posture SetupChecklistAlert and PageGuideButton already take.
//
// SUCCESSION, not just overlap (tour-rebuild audit, 2026-09). The guards
// above only ever stopped the two surfaces being on screen at the SAME
// instant. They do nothing about the handover, and the tour's own completion
// CTA creates one: TourOverlay's secondary completion CTA calls
// router.push("/agents") and THEN finishTour(). By the time this component
// mounts on /agents for the first time, vd_tour_status is already written, so
// isTourPending() is false
// and tour.status is back to "idle" — every guard passes and the six-step
// "How agents work" modal opens on the frame after the tour that just spent
// step 1 explaining agents. The real first-run sequence was: language
// prompt, welcome hero, tour step 1, seven more cards, completion modal,
// this guide. Seven surfaces.
//
// So the auto-open is additionally suppressed for the rest of the browser
// SESSION once the tour ends, via tourEndedThisSession() (sessionStorage
// "vd_tour_ended", written by both finishTour and skipTour — skip counts
// too: someone who just dismissed the tour is not asking for another modal).
// Session-scoped on purpose: the next time this user opens the console in a
// new tab or after a browser restart they are no longer mid-handover, and
// the guide takes its one auto-open then. SEEN_KEY is deliberately NOT
// written here — suppressing an auto-open must not consume the one first-run
// showing of a modal the user never actually saw.
//
// "completed" is in the status list below as well. It is the state while the
// tour's own completion card is on screen, and it was missing; today that is
// harmless only because this effect is mount-only, which makes it a trap for
// whoever first gives the effect a dependency array.
const SEEN_KEY = "vd_agents_guide_seen_v1";

const STEP_ICONS = [MessageSquare, SlidersHorizontal, BookOpen, Wrench, Sparkles, Rocket];

export default function AgentsGuide() {
  const t = useTranslations("agentsGuide");
  const tour = useTour();
  const [open, setOpen] = useState(false);
  const [mounted, setMounted] = useState(false);

  // Portal target only exists after mount; also gate the auto-open on the
  // client-only localStorage read to avoid any hydration mismatch.
  useEffect(() => {
    setMounted(true);
    // Tour first - see the header comment. Read at mount only, on purpose:
    // the guide must not pop open mid-page the instant the tour finishes.
    // tourEndedThisSession() is the succession guard: the completion CTA
    // routes here and finishes the tour, so the overlap checks below have
    // already gone false by this mount. It does NOT write SEEN_KEY, so the
    // auto-open is only deferred, never spent.
    if (
      isTourPending() ||
      tourEndedThisSession() ||
      tour.status === "welcome" ||
      tour.status === "active" ||
      tour.status === "completed"
    ) {
      return;
    }
    try {
      if (localStorage.getItem(SEEN_KEY)) return;
      // Mark seen BEFORE opening: a navigation that unmounts this modal is
      // the common exit path, and close() would never get to write this.
      // If the write itself throws (private mode / blocked site data) we
      // deliberately don't open at all - an un-persistable auto-open would
      // re-fire on every single visit, which is worse than never opening
      // (same "no storage -> don't nag" posture as LanguageChoicePrompt).
      localStorage.setItem(SEEN_KEY, "1");
      setOpen(true);
    } catch {
      // Storage unavailable - see the comment above.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function markSeen() {
    try {
      localStorage.setItem(SEEN_KEY, "1");
    } catch {
      // Non-fatal — the guide simply auto-opens again next visit.
    }
  }

  function close() {
    markSeen();
    setOpen(false);
  }

  // Close on Escape while open. Inlines the close logic (rather than depending
  // on the identity-unstable `close` callback) so the effect only re-runs when
  // `open` actually flips.
  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key !== "Escape") return;
      try {
        localStorage.setItem(SEEN_KEY, "1");
      } catch {
        // Non-fatal — guide simply auto-opens again next visit.
      }
      setOpen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  // Focus in, Tab trap and focus back to the trigger on close (FE-041). Escape stays with the effect above.
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, null, { enabled: mounted && open });

  const steps = [0, 1, 2, 3, 4, 5].map((i) => ({
    title: t(`step${i + 1}Title`),
    body: t(`step${i + 1}Body`),
    Icon: STEP_ICONS[i],
  }));

  return (
    <>
      <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(true)}>
        <HelpCircle size={14} />
        {t("triggerLabel")}
      </Button>

      {mounted &&
        open &&
        createPortal(
          <div
            ref={dialogRef}
            className="fixed inset-0 z-[120] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
            role="presentation"
            onClick={(e) => { if (e.target === e.currentTarget) close(); }}
          >
            <div
              role="dialog"
              aria-modal="true"
              aria-labelledby="agents-guide-title"
              className="relative flex max-h-[85vh] w-full max-w-lg flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl"
            >
              <button
                type="button"
                onClick={close}
                aria-label={t("close")}
                className="absolute right-3 top-3 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
              >
                <X size={16} />
              </button>

              <div className="border-b border-border px-6 py-5">
                <div className="flex items-center gap-2 text-primary">
                  <Sparkles size={16} />
                  <span className="text-xs font-semibold uppercase tracking-wider">{t("eyebrow")}</span>
                </div>
                <h2 id="agents-guide-title" className="mt-1.5 text-xl font-semibold tracking-tight text-foreground">
                  <BrandText text={t("title")} />
                </h2>
                <p className="mt-1 text-sm text-muted-foreground"><BrandText text={t("subtitle")} /></p>
              </div>

              <ol className="flex-1 space-y-1 overflow-y-auto px-6 py-5">
                {steps.map((step, i) => (
                  <li key={i} className="flex gap-3.5 rounded-lg p-2 transition-colors hover:bg-accent/50">
                    <div className="relative flex flex-col items-center">
                      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                        <step.Icon size={17} />
                      </span>
                      {i < steps.length - 1 && <span className="mt-1 w-px flex-1 bg-border" aria-hidden="true" />}
                    </div>
                    <div className="min-w-0 pb-2">
                      <div className="flex items-center gap-2">
                        <span className="text-[11px] font-semibold tabular-nums text-muted-foreground">
                          {String(i + 1).padStart(2, "0")}
                        </span>
                        <span className="text-sm font-semibold text-foreground">{step.title}</span>
                      </div>
                      <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
                    </div>
                  </li>
                ))}
              </ol>

              <div className="flex items-center justify-between gap-3 border-t border-border px-6 py-4">
                <span className="text-xs text-muted-foreground">{t("footerHint")}</span>
                <Button type="button" onClick={close}>
                  {t("cta")}
                </Button>
              </div>
            </div>
          </div>,
          document.body
        )}
    </>
  );
}
