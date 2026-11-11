"use client";

import { X } from "lucide-react";
import { Button } from "../../ui/button";
import { optionalTourCopy } from "../consumerGuide";
import type { Translate } from "./copyVariants";

// "You can reopen this" note, split out of TourOverlay.
// Shown for a few seconds after a skip or a dismissed welcome (the
// provider owns the timer). Until 2026-09-19 a visitor who bailed out
// half-way was never told the tour could come back, or from where.
// Deliberately NOT a dialog and NOT a scrim: role="status", no focus
// move, and pointer-events only on the pill itself, so it can never
// stand between a logged-in user and the app - the failure TourOverlay's
// scrim comments exist to prevent. It sits at the bottom centre, clear
// of the topbar it is describing.
export default function ReopenHint({ t, onDismiss }: { t: Translate; onDismiss: () => void }) {
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-[1152] flex justify-center px-4">
      <div
        role="status"
        data-testid="tour-reopen-hint"
        className="pointer-events-auto flex max-w-[28rem] items-center gap-2 rounded-full border border-border bg-card py-1.5 ps-4 pe-1.5 text-[13px] text-card-foreground shadow-[var(--shadow-modal)]"
      >
        <span>{optionalTourCopy(t, "reopenHint")}</span>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("closeLabel")}
          onClick={onDismiss}
          className="size-7 shrink-0 text-muted-foreground hover:text-foreground"
        >
          <X className="size-4" aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
