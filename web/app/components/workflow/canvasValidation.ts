// Small pure helpers for the workflow canvas's Validate / Run / inspector chrome (SE-26, SE-51, SE-81).
// Kept out of WorkflowCanvas.tsx, which is over the file-size ratchet.
import { useEffect, useRef, useState } from "react";

/**
 * Ids of the nodes a validation message names. Core quotes the node label in single quotes
 * ("Switch node 'Route' has ...") so a message matches a node whose label appears in quotes.
 */
export function nodeIdsForValidationErrors(
  errors: readonly string[] | null | undefined,
  nodes: ReadonlyArray<{ id: string; data?: { label?: unknown } }>
): Set<string> {
  const ids = new Set<string>();
  if (!errors || errors.length === 0) return ids;
  for (const node of nodes) {
    const label = typeof node.data?.label === "string" ? node.data.label.trim() : "";
    if (!label) continue;
    const quoted = `'${label}'`;
    if (errors.some((e) => e.includes(quoted))) ids.add(node.id);
  }
  return ids;
}

/**
 * The step counts for the run inspector. A finished branching run executes fewer nodes than the graph has;
 * the nodes on the branches not taken are reported separately instead of reading as "4/5 COMPLETED".
 */
export function runStepProgress(
  executed: number,
  total: number,
  status: string | undefined,
  live: boolean
): { completed: number; total: number; branchesNotTaken: number } {
  const finished = !live && String(status ?? "").toUpperCase() === "COMPLETED";
  const notTaken = finished ? Math.max(0, total - executed) : 0;
  return { completed: executed, total, branchesNotTaken: notTaken };
}

/**
 * True when the canvas graph is byte-identical to what the server last confirmed and the workflow is
 * already a DRAFT, so a Validate / Run need not PUT-save (and mint a version) first.
 */
export function canSkipDraftSave(
  currentGraphJson: string,
  lastSavedGraphJson: string | null,
  status: string | undefined
): boolean {
  return lastSavedGraphJson !== null && currentGraphJson === lastSavedGraphJson && status === "DRAFT";
}

const INSPECTOR_KEY = "wf-inspector-open:";

export function readInspectorOpen(workflowId: string): boolean | null {
  try {
    const v = window.localStorage.getItem(INSPECTOR_KEY + workflowId);
    return v === "1" ? true : v === "0" ? false : null;
  } catch {
    return null;
  }
}

export function writeInspectorOpen(workflowId: string, open: boolean): void {
  try {
    window.localStorage.setItem(INSPECTOR_KEY + workflowId, open ? "1" : "0");
  } catch {
    // storage blocked: the panel simply is not remembered
  }
}

/** Inspector open/closed, remembered per workflow (SE-51): restored after mount, written on change. */
export function useRememberedInspectorOpen(workflowId: string): [boolean, (open: boolean) => void] {
  const [open, setOpen] = useState(false);
  const restored = useRef(false);
  useEffect(() => {
    const saved = readInspectorOpen(workflowId);
    if (saved !== null) setOpen(saved);
    restored.current = true;
  }, [workflowId]);
  useEffect(() => {
    if (restored.current) writeInspectorOpen(workflowId, open);
  }, [workflowId, open]);
  return [open, setOpen];
}
