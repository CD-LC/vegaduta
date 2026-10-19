"use client";

import type { RefObject } from "react";
import { AnimatePresence, motion, type Transition } from "motion/react";
import AnimatedBrandMark from "../../AnimatedBrandMark";
import { Button } from "../../ui/button";
import AdminWelcomeBody from "../AdminWelcomeBody";
import { SHORT_TOUR_STEPS } from "./geometry";
import { CloseButton, IllustrationBand } from "./parts";
import type { Translate, useVariantCopy } from "./copyVariants";

// The tour's welcome hero (split out of TourOverlay): a true modal with the
// tenant-admin variant (AdminWelcomeBody) or the plain hero. TourOverlay owns
// the state, the focus trap and the click blocker; this is the painted modal.

export default function WelcomeModal({
  open,
  isAdminWelcome,
  totalSteps,
  reduced,
  fade,
  cardShell,
  modalRef,
  titleId,
  bodyId,
  t,
  tv,
  audienceVariants,
  onStart,
  onBuild,
  onDismiss,
}: {
  open: boolean;
  isAdminWelcome: boolean;
  totalSteps: number;
  reduced: boolean;
  fade: Transition;
  cardShell: string;
  modalRef: RefObject<HTMLDivElement | null>;
  titleId: string;
  bodyId: string;
  t: Translate;
  tv: ReturnType<typeof useVariantCopy>;
  audienceVariants: string[];
  onStart: () => void;
  onBuild: () => void;
  onDismiss: () => void;
}) {
  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            key="welcome-backdrop"
            // Paint only - see "Click blockers" above for who catches clicks.
            className="pointer-events-none fixed inset-0 z-[1150] bg-black/50"
            initial={reduced ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={fade}
            aria-hidden="true"
          />
          <motion.div
            key="welcome-card"
            className="pointer-events-none fixed inset-0 z-[1152] flex items-center justify-center p-4"
            initial={reduced ? false : { opacity: 0, scale: 0.96, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97 }}
            transition={fade}
          >
            <div
              ref={modalRef}
              role="dialog"
              aria-modal="true"
              aria-labelledby={`${titleId}-welcome`}
              aria-describedby={`${bodyId}-welcome`}
              tabIndex={-1}
              className={`pointer-events-auto relative w-full ${isAdminWelcome ? "max-w-[520px]" : "max-w-[440px]"} rounded-2xl ${cardShell}`}
            >
              <CloseButton label={t("closeLabel")} onClick={onDismiss} />
              {isAdminWelcome ? (
                <AdminWelcomeBody
                  titleId={`${titleId}-welcome`}
                  bodyId={`${bodyId}-welcome`}
                  onStart={onStart}
                  onBuild={onBuild}
                  onSkip={onDismiss}
                />
              ) : (
                <>
                  <IllustrationBand name="welcome" tall />
                  <div className="p-6">
                    <AnimatedBrandMark size={56} />
                    <h2 id={`${titleId}-welcome`} className="mt-3 text-lg font-semibold tracking-tight text-foreground">
                      {tv("welcome", "Title", audienceVariants)}
                    </h2>
                    <p id={`${bodyId}-welcome`} className="mt-2 text-sm leading-relaxed text-muted-foreground">
                      {tv("welcome", "Body", audienceVariants)}
                    </p>
                    {/* Length promise, keyed on the length of THIS user's tour
                    rather than on who they are: "About 2 minutes" describes
                    the full tenant-admin walk, and a short selected tour - a
                    platform-admin's handful of stops, or a SIMPLE-mode
                    member's - takes about a minute. The count is the honest
                    predicate here; the audience is only the usual reason the
                    count is small. Falls back to `duration` if the short key
                    is ever removed. */}
                    <p className="mt-3 text-xs text-muted-foreground">
                      {tv("duration", "", [totalSteps <= SHORT_TOUR_STEPS ? "Short" : null])}
                    </p>
                    <div className="mt-5 flex items-center gap-3">
                      {/* v1's primary also did router.push("/agents"): step 1 used
                      to spotlight the workbench's own create control, so
                      starting the tour on /admin or / left the person reading
                      "click New agent" on a page with no such button. The
                      rebuilt step 1 spotlights the /agents NAV ROW instead,
                      which is present on every console route - so the forced
                      navigation is gone. It was never neutral: it yanked a
                      brand-new admin off the page they had just landed on. */}
                      <Button onClick={onStart} size="sm">
                        {t("welcomePrimary")}
                      </Button>
                      <Button onClick={onDismiss} variant="ghost" size="sm">
                        {t("welcomeSkip")}
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
