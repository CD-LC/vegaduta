"use client";

import { useEffect, useRef, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";
import { lintGraph } from "../canvasOps";
import { canSkipDraftSave, nodeIdsForValidationErrors } from "../canvasValidation";
import { validationErrorsOf } from "../canvasHelpers";
import { fromWireGraph, toWireGraph } from "../workflowCanvasHelpers";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * Loading, dirty tracking, autosave and the explicit Save / Validate /
 * Duplicate actions of the workflow on the canvas. The wire graph the server
 * last confirmed is the dirty baseline for both manual saves and autosave.
 */
export function useGraphPersistence(opts: {
  workflowId: any;
  workflow: any;
  setWorkflow: (w: any) => void;
  nodes: any[];
  edges: any[];
  setNodes: Setter;
  setEdges: Setter;
  nodesRef: MutableRefObject<any[]>;
  edgesRef: MutableRefObject<any[]>;
  resetHistory: () => void;
  observing: boolean;
  busy: boolean;
  setBusy: (b: boolean) => void;
  setError: (e: any) => void;
  runLive: boolean;
  setValidationErrors: (e: string[] | null) => void;
  setInvalidNodeIds: (s: ReadonlySet<string>) => void;
  bumpLint: () => void;
  t: (key: string, values?: Record<string, unknown>) => string;
  tRef: MutableRefObject<(key: string, values?: Record<string, unknown>) => string>;
  push: (href: string) => void;
}) {
  const {
    workflowId,
    workflow,
    setWorkflow,
    nodes,
    edges,
    setNodes,
    setEdges,
    nodesRef,
    edgesRef,
    resetHistory,
    observing,
    busy,
    setBusy,
    setError,
    runLive,
    setValidationErrors,
    setInvalidNodeIds,
    bumpLint,
    t,
    tRef,
    push,
  } = opts;

  const lastSavedGraphJsonRef = useRef<string | null>(null);
  const [graphJson, setGraphJson] = useState<string | null>(null);
  const [lintIssues, setLintIssues] = useState<ReturnType<typeof lintGraph>>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .getWorkflow(workflowId)
      .then((wf) => {
        if (cancelled) return;
        setWorkflow(wf);
        const { nodes: n, edges: e } = fromWireGraph(wf.graph);
        setNodes(n);
        setEdges(e);
        // Fresh load = clean baseline for dirty tracking, and an empty
        // undo history (undoing INTO a previous workflow's graph would
        // be a cross-document leak).
        nodesRef.current = n;
        edgesRef.current = e;
        lastSavedGraphJsonRef.current = JSON.stringify(toWireGraph(n, e));
        setGraphJson(lastSavedGraphJsonRef.current);
        setLintIssues(lintGraph(n, e));
        resetHistory();
      })
      .catch((err) => setError(errorMessage(err, tRef.current("loadWorkflowError"))));
    return () => {
      cancelled = true;
    };
  }, [workflowId, tRef, resetHistory, setWorkflow, setNodes, setEdges, nodesRef, edgesRef, setError]);

  // Dirty = the wire graph differs from the last graph the server confirmed.
  // The stringify is debounced: while a node is dragged `nodes` changes every
  // frame, and serialising the whole graph 60x/s was pure waste. The lint
  // pass rides the same debounce.
  useEffect(() => {
    const timer = setTimeout(() => {
      setGraphJson(JSON.stringify(toWireGraph(nodes, edges)));
      setLintIssues(lintGraph(nodes, edges));
    }, 180);
    return () => clearTimeout(timer);
  }, [nodes, edges]);
  const currentGraphJson = graphJson;
  const isDirty =
    lastSavedGraphJsonRef.current !== null &&
    currentGraphJson !== null &&
    currentGraphJson !== lastSavedGraphJsonRef.current;

  // Dirty guard: leaving the tab mid-edit warns. Modern browsers show their
  // own generic text, so preventDefault is the whole contract.
  useEffect(() => {
    if (!isDirty) return undefined;
    function onBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault();
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [isDirty]);

  // Raw persist, no state banners of its own - throws on failure (with
  // `.errors` attached for a governance rejection) so callers that chain off a
  // successful save (validate/run) don't continue against a stale server copy.
  async function persistGraph(status: any) {
    const graph = toWireGraph(nodes, edges);
    const updated = await api.updateWorkflow(workflowId, {
      name: workflow.name,
      description: workflow.description,
      graph,
      status,
    });
    setWorkflow(updated);
    // The server confirmed this graph - it becomes the dirty baseline.
    lastSavedGraphJsonRef.current = JSON.stringify(graph);
    // Core lints expressions against the SAVED graph (labels, edges). A lint that ran before this save (the
    // autosave lands 2.5s after an edit, the lint 1.2s) answered for the old graph, so ask again.
    bumpLint();
    return updated;
  }

  // Autosave (Tier-3): while the canvas is dirty on a DRAFT workflow and no
  // save is in flight, persist quietly after a short idle window. It never
  // activates or re-runs (autosaving an ACTIVE workflow would be a silent publish).
  const [autosaveState, setAutosaveState] = useState<"idle" | "saving" | "saved">("idle");
  useEffect(() => {
    // Never while observing: entering observe clears a timer armed in edit
    // mode, and leaving re-arms it for edits that were made in edit mode.
    if (observing || !isDirty || !workflow || workflow.status !== "DRAFT" || busy || runLive) return undefined;
    setAutosaveState("idle");
    const timer = setTimeout(() => {
      setAutosaveState("saving");
      persistGraph("DRAFT")
        .then(() => setAutosaveState("saved"))
        .catch(() => {
          // Surface a lost save once per dirty-graph change (the effect re-arms
          // on each new change) while dropping back to "idle" so the dirty
          // badge keeps telling the truth.
          setAutosaveState("idle");
          toast.error(t("autosaveFailed"));
        });
    }, 2500);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentGraphJson, isDirty, workflow?.status, observing]);

  // Validate/Run save a DRAFT first; skip the PUT when the canvas equals the last saved DRAFT (SE-81: no identical versions).
  async function persistDraftUnlessClean() {
    const json = JSON.stringify(toWireGraph(nodes, edges));
    if (workflow && canSkipDraftSave(json, lastSavedGraphJsonRef.current, workflow.status)) return workflow;
    return persistGraph("DRAFT");
  }

  // Validation is deliberately NOT enforced for status DRAFT - the backend only
  // validates when status === ACTIVE, so a work-in-progress graph always saves.
  async function save(status: any) {
    setBusy(true);
    setError(null);
    setValidationErrors(null);
    try {
      await persistGraph(status);
      // Server-only checks (agent refs, provider keys) can change with a save.
      bumpLint();
      toast.success(status === "ACTIVE" ? t("workflowActivatedSuccess") : t("draftSavedSuccess"));
    } catch (err) {
      const rejected = validationErrorsOf(err);
      if (rejected) {
        // Only reachable for status === "ACTIVE".
        setValidationErrors(rejected);
      } else {
        toast.error(errorMessage(err, t("saveWorkflowError")));
      }
    } finally {
      setBusy(false);
    }
  }

  // A FREE tenant's only way to change a locked (24h+) workflow: duplicate it
  // into a fresh, editable DRAFT copy, then jump straight to that canvas.
  async function handleDuplicate() {
    setBusy(true);
    setError(null);
    try {
      const copy = await api.duplicateWorkflow(workflowId);
      toast.success(t("duplicateWorkflowSuccess", { name: copy.name }));
      push(`/workflows/${copy.id}`);
    } catch (err) {
      toast.error(errorMessage(err, t("duplicateWorkflowError")));
      setBusy(false);
    }
  }

  // "Validate Workflow": the same OrchestratorService rules Activate enforces,
  // without changing status. Results always render in the validation dialog.
  async function validate() {
    setBusy(true);
    setError(null);
    setValidationErrors(null);
    setInvalidNodeIds(new Set());
    try {
      // Persist first so validation reflects what's on the canvas (on an
      // ACTIVE workflow this also reverts it to DRAFT, disclosed in the tooltip).
      await persistDraftUnlessClean();
      const result = await api.validateWorkflow(workflowId);
      if (result.valid) {
        toast.success(t("validateSuccess"));
      } else {
        const errs = Array.isArray(result.errors) ? result.errors.map(String) : [];
        setInvalidNodeIds(nodeIdsForValidationErrors(errs, nodes));
        if (errs.length > 0) setValidationErrors(errs);
        else toast.error(t("validateError"));
      }
    } catch (err) {
      const rejected = validationErrorsOf(err);
      if (rejected) {
        setInvalidNodeIds(nodeIdsForValidationErrors(rejected, nodes));
        setValidationErrors(rejected);
      } else {
        toast.error(errorMessage(err, t("validateError")));
      }
    } finally {
      setBusy(false);
    }
  }

  return {
    graphJson,
    lintIssues,
    isDirty,
    autosaveState,
    persistDraftUnlessClean,
    save,
    validate,
    handleDuplicate,
  };
}
