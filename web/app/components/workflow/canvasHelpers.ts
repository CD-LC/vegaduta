// Pure module-level helpers moved out of WorkflowCanvas.tsx, which is over the file-size ratchet.
import { errorField } from "../../lib/errors";
import type { WorkflowNodeRunTrace } from "./WorkflowNode";

/** The governance-rejection list lib/api attaches as `err.errors`, or null. */
export function validationErrorsOf(err: unknown): string[] | null {
  const errors = errorField(err, "errors");
  return Array.isArray(errors) ? errors.map(String) : null;
}

export function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  const tag = el?.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || !!el?.isContentEditable;
}

export function sameTrace(a: WorkflowNodeRunTrace | undefined, b: WorkflowNodeRunTrace): boolean {
  return (
    !!a &&
    a.status === b.status &&
    a.summary === b.summary &&
    a.durationMs === b.durationMs &&
    a.tokens === b.tokens &&
    a.costUsd === b.costUsd
  );
}

export function finiteNumber(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}
