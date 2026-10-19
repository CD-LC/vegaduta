import { useEffect } from "react";
import type { useTour } from "../../TourProvider";
import { higherEscapeLayerOpen } from "../../../lib/overlayGate";

// Keyboard handling for the tour overlay (split out of TourOverlay).

type TourApi = ReturnType<typeof useTour>;

export function useTourKeyboard(
  tour: TourApi,
  state: { isWelcome: boolean; isCompleted: boolean; isActive: boolean }
): void {
  const { isWelcome, isCompleted, isActive } = state;
  // The welcome hero and the completion screen are true modals, so Escape
  // closes them the way every other dialog in this app does.
  useEffect(() => {
    if (!isWelcome && !isCompleted) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key !== "Escape" || higherEscapeLayerOpen()) return;
      e.preventDefault();
      if (isWelcome) tour.dismissWelcome();
      else tour.finishTour();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isWelcome, isCompleted, tour]);

  // Keyboard control for the walkthrough. Enter is deliberately ignored when
  // focus is already on a control - the browser's own click would fire too
  // and the tour would jump two steps.
  useEffect(() => {
    if (!isActive) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        if (higherEscapeLayerOpen()) return;
        e.preventDefault();
        tour.skipTour();
        return;
      }
      if (e.key === "ArrowRight") {
        e.preventDefault();
        tour.nextStep();
        return;
      }
      if (e.key === "ArrowLeft") {
        e.preventDefault();
        tour.prevStep();
        return;
      }
      if (e.key === "Enter") {
        const el = e.target as HTMLElement | null;
        if (el?.closest?.("button, a, input, textarea, select, [contenteditable]")) return;
        e.preventDefault();
        tour.nextStep();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isActive, tour]);
}
