"use client";

// Run-level pin indicators: "N nodes pinned" in a run's summary and the
// persistent "Draft run - pinned data used" chip, so a green draft run is
// never mistaken for a real end-to-end run. Also the recorder's "pin the
// selected step's output" action.

import { Pin } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { TraceVisit } from "./runModel";
import PinOutputButton from "./PinOutputButton";
import "./pins.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** "3 nodes pinned" (plain text, for run summaries and the recorder header). */
export function PinnedCountMeta({ count, className }: { count: number; className?: string }) {
  if (count <= 0) return null;
  return <PinnedCountText count={count} className={className} />;
}

function PinnedCountText({ count, className }: { count: number; className?: string }) {
  const t = useTranslations("workflowCanvas") as Translator;
  return (
    <span className={className ?? "wfx-pin-count"} data-testid="pin-count">
      <Pin size={11} fill="currentColor" aria-hidden="true" />
      {count === 1 ? t("pinRunCountOne") : t("pinRunCount", { count })}
    </span>
  );
}

/** Persistent warning chip on a run that used pins. Renders nothing when no node was pinned. */
export function DraftRunChip({ count }: { count: number }) {
  if (count <= 0) return null;
  return <DraftRunChipText count={count} />;
}

function DraftRunChipText({ count }: { count: number }) {
  const t = useTranslations("workflowCanvas") as Translator;
  return (
    <span className="wfx-pin-draftchip" role="note" title={t("pinDraftChipHint")} data-testid="draft-run-chip">
      <Pin size={12} fill="currentColor" aria-hidden="true" />
      {t("pinDraftChip")}
      <span className="wfx-pin-draftchip-count">{count === 1 ? t("pinRunCountOne") : t("pinRunCount", { count })}</span>
    </span>
  );
}

/** Flight recorder: pin the output of the step currently selected on the timeline. */
export function PinSelectedOutput({ visit, runId }: { visit: TraceVisit | undefined; runId: string | null | undefined }) {
  if (!visit || visit.status === "running" || visit.output == null) return null;
  return (
    <PinOutputButton
      nodeId={visit.nodeId}
      data={visit.output}
      source="run"
      sourceRunId={runId || null}
      truncated={visit.outputTruncated}
      label="this"
      className="wfx-pin-out-float"
    />
  );
}
