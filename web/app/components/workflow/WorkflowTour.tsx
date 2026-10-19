"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Play, Pause, ChevronLeft, ChevronRight, X as CloseIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

/**
 * Guided-tour overlay for the workflow canvas.
 *
 * Rendered by WorkflowCanvas ONLY while a tour is active - the user starts it
 * explicitly with the toolbar "Tour" button; it never auto-plays on page load
 * (a returning author dropped straight into a moving canvas would be jarring).
 *
 * Visually modeled on the AXON "Workflows Pro" reference's timeline HUD
 * ([data-hud-tl] - a row of step segments with progress bars) plus its
 * narration bubble ([data-narrate] - a pill caption over the canvas). Unlike
 * the reference, which walks a canned 8-step fake example, this walks the REAL
 * currently-loaded workflow: `steps` is a topological order of the actual
 * nodes (computed by the parent from the real edges), and every label /
 * caption is real node data (author label, node-type label, per-type
 * description) - nothing is fabricated.
 *
 * Ownership split: this component owns the ephemeral tour UI state (which step
 * is active, whether it's auto-advancing). The parent owns the canvas effects
 * (panning to a node, drawing its focus ring) via the `onFocus(nodeId)`
 * callback, and owns teardown via `onExit`.
 */
// One narrated stop on the tour - built by WorkflowCanvas's tourSteps memo
// from the real graph (id is the actual node id onFocus pans to; the labels
// are the same author-label/type-label/description strings the node cards
// themselves render, never fabricated copy - see that memo's comment).
export type WorkflowTourStep = {
  id: string;
  shortLabel: string;
  title: string;
  subtitle: string;
  description?: string;
};

export default function WorkflowTour({
  steps,
  onFocus,
  onExit,
}: {
  steps: WorkflowTourStep[];
  onFocus: (nodeId: string) => void;
  onExit: () => void;
}) {
  const t = useTranslations("workflowCanvas");
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(true);

  // onFocus identity can change between renders (the parent rebuilds it when
  // `nodes` changes); keep the effect below keyed only on the step so a node
  // being dragged mid-tour doesn't spuriously re-pan the viewport.
  const onFocusRef = useRef(onFocus);
  onFocusRef.current = onFocus;

  const total = steps.length;
  const clampedIndex = Math.min(index, Math.max(0, total - 1));
  const step = steps[clampedIndex];

  // Focus (pan/zoom + highlight) the current node whenever the step changes,
  // including on first mount.
  const stepId = step ? step.id : null;
  useEffect(() => {
    if (stepId != null) onFocusRef.current(stepId);
  }, [stepId]);

  // Auto-advance while playing; stop (and flip to paused) once the last step is
  // reached so the tour settles on the final node instead of looping.
  useEffect(() => {
    if (!playing) return undefined;
    if (clampedIndex >= total - 1) {
      setPlaying(false);
      return undefined;
    }
    const timer = setTimeout(() => setIndex((i) => Math.min(i + 1, total - 1)), 2600);
    return () => clearTimeout(timer);
  }, [playing, clampedIndex, total]);

  // Manual navigation always pauses auto-advance - the user has taken control.
  const goPrev = useCallback(() => {
    setPlaying(false);
    setIndex((i) => Math.max(0, i - 1));
  }, []);
  const goNext = useCallback(() => {
    setPlaying(false);
    setIndex((i) => Math.min(total - 1, i + 1));
  }, [total]);

  // Keyboard affordances: arrows step, Escape exits.
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === "ArrowRight") goNext();
      else if (ev.key === "ArrowLeft") goPrev();
      else if (ev.key === "Escape") onExit();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [goNext, goPrev, onExit]);

  if (total === 0 || !step) return null;

  return (
    <>
      {/* Timeline HUD (reference [data-hud-tl]) - one clickable segment per
       * real node, its bar filling as the tour passes it. */}
      <div className="ax-wf-tour-hud" role="group" aria-label={t("tourTimelineAriaLabel")}>
        <span className="ax-wf-tour-hud-run">{t("tourRunLabel")}</span>
        {steps.map((s, i) => (
          <button
            key={s.id}
            type="button"
            className={`ax-wf-tour-seg${i === clampedIndex ? " ax-wf-tour-seg-active" : ""}${
              i < clampedIndex ? " ax-wf-tour-seg-done" : ""
            }`}
            onClick={() => {
              setPlaying(false);
              setIndex(i);
            }}
            title={s.title}
          >
            <span className="ax-wf-tour-seg-bar" />
            <span className="ax-wf-tour-seg-label">{s.shortLabel}</span>
          </button>
        ))}
      </div>

      {/* Narration bubble (reference [data-narrate]) - real node copy only. */}
      <div className="ax-wf-tour-narrate" aria-live="polite">
        <span className="ax-wf-tour-narrate-title">{step.title}</span>
        <span className="ax-wf-tour-narrate-kind">{step.subtitle}</span>
        {step.description ? <span className="ax-wf-tour-narrate-desc">{step.description}</span> : null}
      </div>

      {/* Transport controls: prev / play-pause / next / exit + a step counter. */}
      <div className="ax-wf-tour-controls">
        <span className="ax-wf-tour-count">{t("tourStepCount", { current: clampedIndex + 1, total })}</span>
        <button
          type="button"
          className="ax-wf-tour-ctrl"
          onClick={goPrev}
          disabled={clampedIndex === 0}
          title={t("tourPrev")}
          aria-label={t("tourPrev")}
        >
          <ChevronLeft size={15} />
        </button>
        <button
          type="button"
          className="ax-wf-tour-ctrl"
          onClick={() => setPlaying((p) => !p)}
          title={playing ? t("tourPause") : t("tourPlay")}
          aria-label={playing ? t("tourPause") : t("tourPlay")}
        >
          {playing ? <Pause size={15} /> : <Play size={15} />}
        </button>
        <button
          type="button"
          className="ax-wf-tour-ctrl"
          onClick={goNext}
          disabled={clampedIndex >= total - 1}
          title={t("tourNext")}
          aria-label={t("tourNext")}
        >
          <ChevronRight size={15} />
        </button>
        <button
          type="button"
          className="ax-wf-tour-ctrl ax-wf-tour-exit"
          onClick={onExit}
          title={t("tourExit")}
          aria-label={t("tourExit")}
        >
          <CloseIcon size={15} />
        </button>
      </div>
    </>
  );
}
