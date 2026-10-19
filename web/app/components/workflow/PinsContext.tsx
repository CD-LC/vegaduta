"use client";

// Canvas-wide pin state. WorkflowCanvas runs usePinsController once and provides
// the result here; the node card, config drawer banner, step-test panel and run inspectors read it through
// usePinsContext() and render nothing when there is no provider (or the core
// has no pins route), so no host needs new props.

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import type { PinFailureKind, PinSource, WorkflowPin } from "../../lib/api/workflowPins";
import { usePins } from "./usePins";
import { pinFailureText, type PinMap, type PinRequest } from "./pinModel";
import PinEditor from "./PinEditor";

export type PinEditorRequest = {
  nodeId: string;
  /** Text to start from when the node has no pin yet (e.g. a trace payload). */
  initialText?: string;
  source?: PinSource;
  sourceRunId?: string | null;
  /** Open read-only even for editors (the banner's View). */
  viewOnly?: boolean;
};

export type PinsContextValue = {
  workflowId: string;
  pins: PinMap;
  loaded: boolean;
  /** May create/edit/remove pins (false = show metadata only, hide every write control). */
  canEdit: boolean;
  /** Save a pin; failures are toasted with the contract's messages. Resolves true on success. */
  pinOutput: (req: PinRequest) => Promise<boolean>;
  unpin: (nodeId: string) => Promise<boolean>;
  openEditor: (req: PinEditorRequest) => void;
  labelOf: (nodeId: string) => string;
};

export const PinsContext = createContext<PinsContextValue | null>(null);

export function usePinsContext(): PinsContextValue | null {
  return useContext(PinsContext);
}

type Translator = (key: string, values?: Record<string, string | number>) => string;

/**
 * Owns the pin store, the toasts and the manual editor for one canvas. The canvas
 * calls this (it needs the pins itself to paint node badges), then provides
 * `ctx` through PinsContext and renders `editor` once. `ctx` is null when the
 * core has no pins route, which hides every pin control.
 */
export function usePinsController(
  workflowId: string,
  labelOf: (nodeId: string) => string,
): { ctx: PinsContextValue | null; editor: ReactNode } {
  const t = useTranslations("workflowCanvas") as Translator;
  const store = usePins(workflowId);
  const [editor, setEditor] = useState<PinEditorRequest | null>(null);
  const { pin, unpin: unpinStore } = store;

  // `pin` already rolled the optimistic write back on failure; here we only tell the user why.
  const fail = useCallback(
    (r: { kind?: PinFailureKind; message?: string }) => {
      toast.error(pinFailureText(t, r.kind ?? "error", r.message ?? ""));
    },
    [t],
  );

  // No toast: the editor shows the failure inline next to the text that caused it.
  const savePin = useCallback(
    async (req: PinRequest) => {
      const r = await pin(req.nodeId, {
        data: req.data,
        source: req.source,
        sourceRunId: req.sourceRunId ?? undefined,
      });
      if (r.ok) toast.success(t("pinSaved"));
      return r;
    },
    [pin, t],
  );

  const pinOutput = useCallback(
    async (req: PinRequest) => {
      const r = await savePin(req);
      if (r.ok) return true;
      fail(r);
      return false;
    },
    [savePin, fail],
  );

  const unpin = useCallback(
    async (nodeId: string) => {
      const r = await unpinStore(nodeId);
      if (r.ok) {
        toast.success(t("pinRemoved"));
        return true;
      }
      fail(r);
      return false;
    },
    [unpinStore, fail, t],
  );

  const openEditor = useCallback((req: PinEditorRequest) => setEditor(req), []);

  const value = useMemo<PinsContextValue | null>(
    () =>
      store.unsupported
        ? null
        : {
            workflowId,
            pins: store.pins,
            loaded: store.loaded,
            canEdit: store.canEdit,
            pinOutput,
            unpin,
            openEditor,
            labelOf,
          },
    [store.unsupported, store.pins, store.loaded, store.canEdit, workflowId, pinOutput, unpin, openEditor, labelOf],
  );

  const editorPin: WorkflowPin | null = editor ? (store.pins[editor.nodeId] ?? null) : null;

  const editorEl =
    value && editor ? (
      <PinEditor
        nodeId={editor.nodeId}
        nodeLabel={labelOf(editor.nodeId) || editor.nodeId}
        pin={editorPin}
        initialText={editor.initialText}
        initialSource={editor.source}
        sourceRunId={editor.sourceRunId ?? undefined}
        canEdit={store.canEdit && !editor.viewOnly}
        onSave={savePin}
        onClose={() => setEditor(null)}
      />
    ) : null;

  return { ctx: value, editor: editorEl };
}
