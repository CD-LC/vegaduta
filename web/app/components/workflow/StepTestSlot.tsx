"use client";

// "Run this step" entry point inside NodeConfigDrawer. The drawer is mounted
// by WorkflowInspector with a fixed prop list, so the canvas hands the graph,
// dirty flag and last-run prefills down through this context instead of
// threading five new props through two components. No provider (or core
// without the test endpoint) = the slot renders nothing.

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { FlaskConical } from "lucide-react";
import StepTestPanel, { type StepTestUpstream } from "./StepTestPanel";
import { upstreamIds } from "./stepTest";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";

type SlotNode = { id: string; type?: string; data?: { label?: string; config?: object | null } };
type SlotEdge = { source: string; target: string };

export type StepTestContextValue = {
  workflowId: string;
  /** Canvas has unsaved edits (the test runs the SAVED graph). */
  dirty: boolean;
  nodes: readonly SlotNode[];
  edges: readonly SlotEdge[];
  /** Per node, the input/output text of its latest visit in the canvas's last run. */
  lastIO: Readonly<Record<string, { input?: string; output?: string }>>;
  labelOf: (nodeId: string) => string;
  /** False once the endpoint answered as missing; the slot hides for the session. */
  available: boolean;
  onUnavailable: () => void;
};

export const StepTestContext = createContext<StepTestContextValue | null>(null);

export default function StepTestSlot({ nodeId }: { nodeId: string }) {
    const tHC = useTranslations("hcComponentsWorkflowStepTestSlot");
  const ctx = useContext(StepTestContext);
  const [open, setOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement | null>(null);
  // Collapse when the drawer switches to another node.
  const [forNode, setForNode] = useState(nodeId);
  if (forNode !== nodeId) {
    setForNode(nodeId);
    setOpen(false);
  }

  const node = ctx?.nodes.find((n) => n.id === nodeId) ?? null;
  const edges = ctx?.edges;
  const lastIO = ctx?.lastIO;
  const labelOf = ctx?.labelOf;
  const upstream: StepTestUpstream[] = useMemo(
    () =>
      edges && labelOf
        ? upstreamIds(nodeId, edges).map((id) => ({ id, label: labelOf(id) || id, lastOutput: lastIO?.[id]?.output }))
        : [],
    [nodeId, edges, labelOf, lastIO],
  );
  const panelRef = useRef<HTMLDivElement | null>(null);
  // Set by the user's open/close, not by a node switch: only those move focus.
  const focusIntentRef = useRef(false);
  const close = useCallback(() => {
    focusIntentRef.current = true;
    setOpen(false);
  }, []);
  // The toggle and the panel swap places, so focus moves after the swap commits.
  useEffect(() => {
    if (!focusIntentRef.current) return;
    focusIntentRef.current = false;
    if (open) {
      const root = panelRef.current;
      const first = root?.querySelector<HTMLElement>("textarea, input, select");
      (first ?? root)?.focus();
    } else {
      toggleRef.current?.focus();
    }
  }, [open]);

  if (!ctx || !ctx.available || !node) return null;

  return (
    <div className="field" style={{ marginTop: "0.75rem" }}>
      {open ? (
        <div ref={panelRef} tabIndex={-1} style={{ outline: "none" }}>
        <StepTestPanel
          workflowId={ctx.workflowId}
          node={{
            id: node.id,
            type: node.type ?? "",
            label: node.data?.label ?? null,
            config: (node.data?.config as Record<string, unknown> | null | undefined) ?? null,
          }}
          upstream={upstream}
          dirty={ctx.dirty}
          lastInput={lastIO?.[nodeId]?.input}
          onUnavailable={ctx.onUnavailable}
          onClose={close}
        />
        </div>
      ) : (
        // An opener, not a disclosure toggle: it is replaced by the panel,
        // which has its own close button, so no aria-expanded.
        <Button
          ref={toggleRef}
          type="button"
          variant="outline"
          onClick={() => {
            focusIntentRef.current = true;
            setOpen(true);
          }}
        >
          <FlaskConical size={14} aria-hidden="true" />
          {tHC("hcComponentsWorkflowStepTestSlot.s1")}
        </Button>
      )}
    </div>
  );
}
