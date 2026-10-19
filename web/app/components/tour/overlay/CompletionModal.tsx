"use client";

import type { RefObject } from "react";
import { AnimatePresence, motion, type Transition } from "motion/react";
import { Button } from "../../ui/button";
import { optionalTourCopy } from "../consumerGuide";
import { CloseButton, IllustrationBand } from "./parts";
import type { Translate, useVariantCopy } from "./copyVariants";

// The tour's completion modal (split out of TourOverlay). The audience decides
// the copy variant and whether the Build Studio / agent doors are offered.

export default function CompletionModal({
  open,
  showCreateAgentCta,
  completionVariants,
  reduced,
  fade,
  cardShell,
  modalRef,
  titleId,
  bodyId,
  t,
  tv,
  audienceVariants,
  onOpenBuildStudio,
  onOpenAgents,
  onFinish,
}: {
  open: boolean;
  showCreateAgentCta: boolean;
  completionVariants: string[];
  reduced: boolean;
  fade: Transition;
  cardShell: string;
  modalRef: RefObject<HTMLDivElement | null>;
  titleId: string;
  bodyId: string;
  t: Translate;
  tv: ReturnType<typeof useVariantCopy>;
  audienceVariants: string[];
  onOpenBuildStudio: () => void;
  onOpenAgents: () => void;
  onFinish: () => void;
}) {
  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            key="done-backdrop"
            // Paint only - see "Click blockers" above for who catches clicks.
            className="pointer-events-none fixed inset-0 z-[1150] bg-black/50"
            initial={reduced ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={fade}
            aria-hidden="true"
          />
          <motion.div
            key="done-card"
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
              aria-labelledby={`${titleId}-done`}
              aria-describedby={`${bodyId}-done`}
              tabIndex={-1}
              className={`pointer-events-auto relative w-full max-w-[420px] rounded-2xl ${cardShell}`}
            >
              <CloseButton label={t("closeLabel")} onClick={onFinish} />
              <IllustrationBand name="complete" tall />
              <div className="p-6 text-center">
                <h2 id={`${titleId}-done`} className="text-lg font-semibold tracking-tight text-foreground">
                  {tv("completed", "Title", completionVariants)}
                </h2>
                <p id={`${bodyId}-done`} className="mt-2 text-sm leading-relaxed text-muted-foreground">
                  {tv("completed", "Body", completionVariants)}
                </p>
                <div className="mt-5 flex flex-col gap-2">
                  {showCreateAgentCta && (
                    <Button onClick={onOpenBuildStudio} size="sm" className="w-full">
                      {tv("completed", "Cta", completionVariants)}
                    </Button>
                  )}
                  {/* The second door on the launch path: the agent behind the
                      experience. Same audience gate as the CTA above - a
                      platform-admin may have no tenant to tune one in. */}
                  {showCreateAgentCta && (
                    <Button onClick={onOpenAgents} variant="outline" size="sm" className="w-full">
                      {optionalTourCopy(t, "completedAgentCta")}
                    </Button>
                  )}
                  <Button
                    onClick={onFinish}
                    variant={showCreateAgentCta ? "ghost" : "default"}
                    size="sm"
                    className="w-full"
                  >
                    {tv("completed", "Secondary", completionVariants)}
                  </Button>
                </div>
                <p className="mt-4 text-xs text-muted-foreground">{tv("replayHint", "", audienceVariants)}</p>
                {/* replayHint names Help, which is two clicks away. The "?"
                    button is on every console screen, and nothing ever
                    pointed at it. */}
                <p className="mt-1 text-xs text-muted-foreground">{optionalTourCopy(t, "reopenHint")}</p>
              </div>
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
