"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, Landmark, RotateCcw } from "lucide-react";
import { useTour } from "../TourProvider";
import { useTranslations } from "../../lib/i18n";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { nextActionsFor, optionalTourCopy } from "./consumerGuide";

/**
 * The permanent "?" affordance in the topbar.
 *
 * Replaces OnboardingTour.tsx (deleted). That component rendered this exact
 * button and used it to reopen a SECOND, separate 5-slide "Welcome aboard"
 * HUD briefing, which said different (and by 2026-09 partly stale) things
 * than the guided product tour - two onboarding narratives competing for the
 * same user. There is now one tour, and this button is its way back in.
 *
 * WHAT A CLICK DOES (changed 2026-09-19). It used to call replayTour()
 * directly. It now opens a small panel - "What should I do next?" - whose
 * rows are the good first moves for THIS visitor's audience and whose last row
 * replays the tour. Founder feedback was that the tour "is not explaining to
 * consumers completely"; the audit found the tour's content thin at exactly one
 * point - the moment after it ends, or after it is skipped, when the person is
 * alone on a dashboard with forty nav rows and the only re-entry is a 7-step
 * walk they may already have abandoned. A tour answers "what is all this";
 * this panel answers "what do I press", and that question does not stop being
 * asked after first login. Replay is one click further away than it was, which
 * is the right trade: /help keeps its own one-click Replay entry.
 *
 * Fail-proof notes:
 *  - The rows are plain in-app links; nothing here fetches.
 *  - replayTour() reports whether a tour actually started. When it did not
 *    (no step is tagged for this audience - not reachable today, but the
 *    provider is allowed to say so), the panel STAYS OPEN and says where the
 *    written guide is, instead of closing on a button that visibly did
 *    nothing.
 *  - Radix Popover portals its content to document.body, so the topbar's
 *    `backdrop-filter` (which re-parents `position: fixed` descendants - the
 *    bug that squashed the old briefing into the 60px strip) cannot confine
 *    it, and Radix's own collision handling keeps it on screen at phone width.
 *    NotificationBell uses the same primitive in the same topbar.
 *
 * Styling notes:
 *  - .hud-tour-reopen is defined in app/globals.css (base rule ~:6398, hover
 *    ~:8254) entirely in design tokens (--hud-line, --surface-card,
 *    --hud-cyan, --accent-glow-cyan), so it tracks light/dark on its own.
 *    Those rules are NOT scoped to the old component and survive its
 *    deletion; the class also out-specifies the global `button {}` rule in
 *    globals.css, which is why no border-0/bg-transparent reset is needed.
 *  - TOUCH TARGET: .hud-tour-reopen hardcodes width/height: 1.9rem (30px),
 *    under the 44px touch minimum. The min-size is applied here as an INLINE
 *    style, not a Tailwind `min-h-11 min-w-11`, on purpose: globals.css's
 *    rules are unlayered while Tailwind v4 utilities live in @layer
 *    utilities, so an unlayered `.hud-tour-reopen { width }` out-cascades any
 *    utility class regardless of specificity (globals.css records this same
 *    trap around :16246). min-width/min-height beat the fixed width/height
 *    without !important. This belongs in globals.css - ideally as a coarse-
 *    pointer-only bump so the desktop button can stay 30px - but this
 *    component does not own that file.
 *  - The panel's rows reset border/background/shadow explicitly for the same
 *    global `button {}` reason the progress rail in TourOverlay does.
 *
 * Copy: the button's own title/label are the tour namespace's reopenTitle /
 * reopenLabel. The panel's strings are key-first with an English fallback from
 * ./consumerGuide.ts (see OPTIONAL_TOUR_COPY there) because this session did
 * not own en.json; add `tour.nextTitle`, `tour.replayLabel` and
 * `tour.replayUnavailable` and the translated strings take over with no code
 * change. The action rows themselves are English-only until they get keys.
 */
export default function TourReopenButton() {
  const t = useTranslations("tour");
  const tour = useTour();
  const [open, setOpen] = useState(false);
  const [replayFailed, setReplayFailed] = useState(false);

  const actions = nextActionsFor(tour.audience, { edgeAvailable: edgeEnabled() });

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (!next) setReplayFailed(false);
  };

  const replay = () => {
    const started = tour.replayTour();
    if (started) {
      onOpenChange(false);
      return;
    }
    setReplayFailed(true);
  };

  // The Court map: same door the Build Center offers, reachable from anywhere.
  const walkCourt = () => {
    onOpenChange(false);
    tour.openCourt();
  };

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          title={t("reopenTitle")}
          aria-label={t("reopenLabel")}
          className="hud-tour-reopen topbar-collapsible"
          style={{ minWidth: "2.75rem", minHeight: "2.75rem" }}
        >
          ?
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-[20rem] p-2" data-testid="tour-next-panel">
        <p className="px-2 pb-1 pt-1.5 text-[11px] font-medium uppercase tracking-[0.14em] text-muted-foreground">
          {optionalTourCopy(t, "nextTitle")}
        </p>
        <ul className="m-0 list-none p-0">
          {actions.map((action) => (
            <li key={action.id}>
              <Link
                href={action.href}
                onClick={() => onOpenChange(false)}
                className="group flex items-start gap-2 rounded-md px-2 py-2 no-underline outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-foreground">{action.label}</span>
                  <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">{action.hint}</span>
                </span>
                <ArrowRight
                  className="mt-0.5 size-3.5 shrink-0 text-muted-foreground group-hover:text-foreground"
                  aria-hidden="true"
                />
              </Link>
            </li>
          ))}
        </ul>
        <div className="mt-1 border-t border-border pt-1">
          <button
            type="button"
            onClick={walkCourt}
            data-testid="tour-open-court"
            className="flex w-full cursor-pointer items-center gap-2 rounded-md border-0 bg-transparent px-2 py-2 text-start text-sm font-medium text-foreground shadow-none outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <Landmark className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            {t("court.walk")}
          </button>
          <button
            type="button"
            onClick={replay}
            className="flex w-full cursor-pointer items-center gap-2 rounded-md border-0 bg-transparent px-2 py-2 text-start text-sm font-medium text-foreground shadow-none outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <RotateCcw className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            {optionalTourCopy(t, "replayLabel")}
          </button>
          {replayFailed && (
            <p role="status" className="px-2 pb-1.5 pt-0.5 text-xs leading-snug text-muted-foreground">
              {optionalTourCopy(t, "replayUnavailable")}
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
