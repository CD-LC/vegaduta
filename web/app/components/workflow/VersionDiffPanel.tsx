"use client";

/**
 * VersionDiffPanel - "what changed between two saved versions" side panel.
 *
 * Props
 *   workflowId      string                   Workflow whose history is compared.
 *   currentVersion  number | null (optional) Version the canvas shows; default pair is
 *                                            (its predecessor -> it), else (previous -> latest).
 *   onFocusNode     (nodeId) => void         Pan/select a node on the canvas (added/changed rows only;
 *                                            removed nodes are not on the canvas).
 *   onDecorations   (decos | null) => void   Optional. Called with diffDecorations(diff) while
 *                                            "Show on canvas" is on, and null when it turns off, the
 *                                            pair changes to a failed/empty diff, or the panel unmounts.
 *                                            Only offered when `to` is the version the canvas shows
 *                                            (currentVersion, else the latest saved) and canvasDirty is
 *                                            false; otherwise the toggle is disabled with a hint and
 *                                            null is sent, so marks always describe the visible graph.
 *   canvasDirty     boolean (optional)       True while the canvas has unsaved edits.
 *   nodeLabel       (nodeId) => string|undefined  Optional. Labels for unchanged nodes named by
 *                                            connection rows (else a short id).
 *   personLabel     (userId) => string|undefined  Optional. Names for version authors (core stores
 *                                            the Keycloak subject UUID). Unresolved authors are left
 *                                            out of the version pickers and shown as a short id in
 *                                            the meta line.
 *   onClose         () => void               Esc or the close button. Focus returns to the element
 *                                            that had it when the panel mounted.
 *
 * Data: GET /api/workflows/{id}/versions and /versions/diff (versionDiff.ts).
 * A core without these routes renders a quiet "unavailable" note, no toast.
 * Keyboard: Arrow Up/Down/Home/End move between node rows, Enter shows the
 * node on canvas, Right/Left (or Space) expand/collapse a changed node.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { ArrowLeftRight, Eye, EyeOff, GitCompare, Info, Loader2, Minus, Plus, RotateCw, X } from "lucide-react";
import {
  defaultPair,
  diffDecorations,
  fetchWorkflowVersionDiff,
  fetchWorkflowVersions,
  formatWhen,
  prettyStatus,
  summarizeDiff,
  type DiffDecorations,
  type DiffEdgeBrief,
  type WorkflowVersionDiff,
  type WorkflowVersionRow,
} from "./versionDiff";
import { authorLabel, ChangeTable, shortId, ValueCell, versionOptionLabel } from "./VersionDiffCells";
import VersionDiffNodeList, { type NodeRow } from "./VersionDiffNodeList";
import { Select } from "../ui/select";
import "./version-diff.css";
import { useTranslations } from "../../lib/i18n";

export type VersionDiffPanelProps = {
  workflowId: string;
  currentVersion?: number | null;
  onFocusNode: (nodeId: string) => void;
  onDecorations?: (decorations: DiffDecorations | null) => void;
  nodeLabel?: (nodeId: string) => string | undefined;
  personLabel?: (userId: string) => string | undefined;
  canvasDirty?: boolean;
  onClose: () => void;
};

type VersionsState =
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "error"; message: string }
  | { kind: "ready"; versions: WorkflowVersionRow[] };

type DiffState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "error"; message: string }
  | { kind: "ready"; diff: WorkflowVersionDiff };

export default function VersionDiffPanel({
  workflowId, currentVersion, onFocusNode, onDecorations, nodeLabel, personLabel, canvasDirty = false, onClose,
}: VersionDiffPanelProps) {
    const tHC = useTranslations("hcComponentsWorkflowVersionDiffPanel");
  const uid = useId();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [versions, setVersions] = useState<VersionsState>({ kind: "loading" });
  const [from, setFrom] = useState<number | null>(null);
  const [to, setTo] = useState<number | null>(null);
  const [diffState, setDiffState] = useState<DiffState>({ kind: "idle" });
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [showOnCanvas, setShowOnCanvas] = useState(false);
  const [active, setActive] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [diffReloadKey, setDiffReloadKey] = useState(0);

  // ---- focus: land inside on mount, hand back on unmount ----------------
  const returnFocusRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const opener = document.activeElement;
    if (opener instanceof HTMLElement && !rootRef.current?.contains(opener)) returnFocusRef.current = opener;
    rootRef.current?.focus();
    return () => {
      const back = returnFocusRef.current;
      setTimeout(() => {
        const a = document.activeElement;
        if (a && a !== document.body) return; // focus already moved on purpose
        if (back && back.isConnected) back.focus();
      }, 0);
    };
  }, []);

  useEffect(() => {
    function onDocKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !e.defaultPrevented) onClose();
    }
    document.addEventListener("keydown", onDocKey);
    return () => document.removeEventListener("keydown", onDocKey);
  }, [onClose]);

  // ---- versions list ------------------------------------------------------
  const currentRef = useRef(currentVersion);
  useEffect(() => {
    currentRef.current = currentVersion;
  }, [currentVersion]);
  useEffect(() => {
    let cancelled = false;
    setVersions({ kind: "loading" });
    void fetchWorkflowVersions(workflowId).then((r) => {
      if (cancelled) return;
      if (r.ok) {
        setVersions({ kind: "ready", versions: r.data });
        const pair = defaultPair(r.data, currentRef.current);
        setFrom(pair?.from ?? null);
        setTo(pair?.to ?? null);
      } else if (r.unsupported) {
        setVersions({ kind: "unavailable" });
      } else {
        setVersions({ kind: "error", message: r.message ?? "Could not load versions." });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [workflowId, reloadKey]);

  // ---- diff ---------------------------------------------------------------
  useEffect(() => {
    if (from == null || to == null || from === to) {
      setDiffState({ kind: "idle" });
      return undefined;
    }
    let cancelled = false;
    setDiffState({ kind: "loading" });
    void fetchWorkflowVersionDiff(workflowId, from, to).then((r) => {
      if (cancelled) return;
      if (r.ok) setDiffState({ kind: "ready", diff: r.data });
      else if (r.unsupported) setDiffState({ kind: "unavailable" });
      else setDiffState({ kind: "error", message: r.message ?? "Could not compare these versions." });
    });
    setExpanded(new Set());
    setActive(0);
    return () => {
      cancelled = true;
    };
  }, [workflowId, from, to, diffReloadKey]);

  const diff = diffState.kind === "ready" ? diffState.diff : null;

  // ---- canvas decorations -------------------------------------------------
  // The canvas shows one graph: `currentVersion` (else the latest saved).
  // Added/changed marks only mean something when `to` IS that graph and it
  // has no unsaved edits; any other pair would paint the wrong nodes.
  const canvasVersion = useMemo(() => {
    if (currentVersion != null) return currentVersion;
    if (versions.kind !== "ready" || versions.versions.length === 0) return null;
    return Math.max(...versions.versions.map((v) => v.versionNumber));
  }, [currentVersion, versions]);
  const canDecorate = !!diff && !canvasDirty && canvasVersion != null && diff.meta.toVersion === canvasVersion;
  const decorateHint = !diff || canDecorate
    ? null
    : canvasDirty
      ? "Save your changes to highlight on the canvas."
      : `Pick the current version${canvasVersion != null ? ` (v${canvasVersion})` : ""} as To to highlight on the canvas.`;
  const onDecorationsRef = useRef(onDecorations);
  useEffect(() => {
    onDecorationsRef.current = onDecorations;
  }, [onDecorations]);
  const sentRef = useRef(false);
  useEffect(() => {
    const cb = onDecorationsRef.current;
    if (!cb) return;
    if (showOnCanvas && diff && canDecorate) {
      cb(diffDecorations(diff));
      sentRef.current = true;
    } else if (sentRef.current) {
      cb(null);
      sentRef.current = false;
    }
  }, [showOnCanvas, diff, canDecorate]);
  useEffect(
    () => () => {
      if (sentRef.current) onDecorationsRef.current?.(null);
    },
    [],
  );

  // ---- derived ------------------------------------------------------------
  const summary = useMemo(() => (diff ? summarizeDiff(diff) : null), [diff]);
  const rows = useMemo<NodeRow[]>(() => {
    if (!diff) return [];
    return [
      ...diff.graph.changedNodes.map((n): NodeRow => ({ kind: "changed", node: n, changed: n })),
      ...diff.graph.addedNodes.map((n): NodeRow => ({ kind: "added", node: n, changed: null })),
      ...diff.graph.removedNodes.map((n): NodeRow => ({ kind: "removed", node: n, changed: null })),
    ];
  }, [diff]);
  const labelOf = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of rows) m.set(r.node.id, r.node.label || r.node.id);
    return (id: string | null) => (id ? (m.get(id) ?? nodeLabel?.(id) ?? shortId(id)) : "unknown");
  }, [rows, nodeLabel]);
  const activeIdx = Math.min(active, Math.max(0, rows.length - 1));

  const toggle = useCallback((id: string, open?: boolean) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      const want = open ?? !next.has(id);
      if (want) next.add(id);
      else next.delete(id);
      return next;
    });
  }, []);

  function swap() {
    setFrom(to);
    setTo(from);
  }

  const versionList = versions.kind === "ready" ? versions.versions : [];
  const versionOptions = versionList.map((v) => ({ value: String(v.versionNumber), label: versionOptionLabel(v, personLabel) }));
  const fromId = `${uid}-from`;
  const toId = `${uid}-to`;
  const titleId = `${uid}-title`;

  // ---- render -------------------------------------------------------------
  let body: ReactNode = null;
  if (versions.kind === "loading") {
    body = (
      <p className="wf-vdiff-note">
        <Loader2 size={12} className="wf-vdiff-spin" aria-hidden="true" />
        {tHC("hcComponentsWorkflowVersionDiffPanel.s11")}
      </p>
    );
  } else if (versions.kind === "unavailable") {
    body = (
      <p className="wf-vdiff-note">
        <Info size={12} aria-hidden="true" />
        {tHC("hcComponentsWorkflowVersionDiffPanel.s10")}
      </p>
    );
  } else if (versions.kind === "error") {
    body = (
      <p className="wf-vdiff-note" data-tone="error">
        <Info size={12} aria-hidden="true" />
        <span>{versions.message}</span>
        <button type="button" className="wf-vdiff-btn" onClick={() => setReloadKey((k) => k + 1)}>
          <RotateCw size={11} aria-hidden="true" />
          Retry
        </button>
      </p>
    );
  } else if (versionList.length < 2) {
    body = (
      <p className="wf-vdiff-note">
        <Info size={12} aria-hidden="true" />
        {versionList.length === 0 ? "No saved versions yet." : "Only one version so far. Save a change to compare."}
      </p>
    );
  }

  return (
    <div className="wf-vdiff" role="dialog" aria-labelledby={titleId} ref={rootRef} tabIndex={-1}>
      <div className="wf-vdiff-head">
        <div className="wf-vdiff-title">
          <GitCompare size={14} aria-hidden="true" />
          <strong id={titleId}>{tHC("hcComponentsWorkflowVersionDiffPanel.s9")}</strong>
        </div>
        <button type="button" className="wf-vdiff-icon" aria-label={tHC("hcComponentsWorkflowVersionDiffPanel.s8")} onClick={onClose}>
          <X size={14} aria-hidden="true" />
        </button>
      </div>

      {body ?? (
        <>
          <div className="wf-vdiff-pickers">
            <label htmlFor={fromId} className="wf-vdiff-pick">
              <span>From</span>
              <Select
                id={fromId}
                value={from == null ? "" : String(from)}
                onValueChange={(v) => setFrom(Number(v))}
                options={versionOptions}
              />
            </label>
            <button type="button" className="wf-vdiff-icon" aria-label={tHC("hcComponentsWorkflowVersionDiffPanel.s7")} onClick={swap}>
              <ArrowLeftRight size={13} aria-hidden="true" />
            </button>
            <label htmlFor={toId} className="wf-vdiff-pick">
              <span>To</span>
              <Select
                id={toId}
                value={to == null ? "" : String(to)}
                onValueChange={(v) => setTo(Number(v))}
                options={versionOptions}
              />
            </label>
          </div>

          <div className="wf-vdiff-status" aria-live="polite">
            {from != null && from === to && (
              <p className="wf-vdiff-note">
                <Info size={12} aria-hidden="true" />
                {tHC("hcComponentsWorkflowVersionDiffPanel.s6")}
              </p>
            )}
            {diffState.kind === "loading" && (
              <p className="wf-vdiff-note">
                <Loader2 size={12} className="wf-vdiff-spin" aria-hidden="true" />
                {tHC("hcComponentsWorkflowVersionDiffPanel.s5")}
              </p>
            )}
            {diffState.kind === "unavailable" && (
              <p className="wf-vdiff-note">
                <Info size={12} aria-hidden="true" />
                {tHC("hcComponentsWorkflowVersionDiffPanel.s4")}
              </p>
            )}
            {diffState.kind === "error" && (
              <p className="wf-vdiff-note" data-tone="error">
                <Info size={12} aria-hidden="true" />
                <span>{diffState.message}</span>
                <button type="button" className="wf-vdiff-btn" onClick={() => setDiffReloadKey((k) => k + 1)}>
                  <RotateCw size={11} aria-hidden="true" />
                  Retry
                </button>
              </p>
            )}
            {diff && summary && (
              <span className="wf-vdiff-sr">
                {summary.total === 0 ? "No differences." : `${summary.total} change${summary.total === 1 ? "" : "s"} found.`}
              </span>
            )}
          </div>

          {diff && summary && (
            <div className="wf-vdiff-body">
              <p className="wf-vdiff-meta">
                <span>
                  v{diff.meta.fromVersion}
                  {diff.meta.fromAuthor ? ` by ${authorLabel(diff.meta.fromAuthor, personLabel)}` : ""}
                  {diff.meta.fromCreatedAt ? `, ${formatWhen(diff.meta.fromCreatedAt)}` : ""}
                </span>
                <span aria-hidden="true"> → </span>
                <span className="wf-vdiff-sr"> to </span>
                <span>
                  v{diff.meta.toVersion}
                  {diff.meta.author ? ` by ${authorLabel(diff.meta.author, personLabel)}` : ""}
                  {diff.meta.createdAt ? `, ${formatWhen(diff.meta.createdAt)}` : ""}
                </span>
              </p>

              <ul className="wf-vdiff-chips" aria-label="Summary">
                <li className="wf-vdiff-chip" data-kind="added">
                  +{summary.nodesAdded} added
                </li>
                <li className="wf-vdiff-chip" data-kind="removed">
                  −{summary.nodesRemoved} removed
                </li>
                <li className="wf-vdiff-chip" data-kind="changed">
                  ~{summary.nodesChanged} changed
                </li>
                <li className="wf-vdiff-chip">
                  {tHC("hcComponentsWorkflowVersionDiffPanel.s3")}{summary.edgesAdded} / −{summary.edgesRemoved}
                </li>
              </ul>

              {onDecorations && summary.total > 0 && (
                <>
                  <button
                    type="button"
                    className="wf-vdiff-btn wf-vdiff-toggle"
                    aria-pressed={showOnCanvas && canDecorate}
                    aria-describedby={decorateHint ? `${uid}-deco-hint` : undefined}
                    disabled={!canDecorate}
                    onClick={() => setShowOnCanvas((v) => !v)}
                  >
                    {showOnCanvas && canDecorate ? <EyeOff size={12} aria-hidden="true" /> : <Eye size={12} aria-hidden="true" />}
                    {showOnCanvas && canDecorate ? "Hide on canvas" : "Show on canvas"}
                  </button>
                  {decorateHint && (
                    <p id={`${uid}-deco-hint`} className="wf-vdiff-note">
                      <Info size={12} aria-hidden="true" />
                      {decorateHint}
                    </p>
                  )}
                </>
              )}

              {summary.total === 0 && <p className="wf-vdiff-empty">{tHC("hcComponentsWorkflowVersionDiffPanel.s2")}</p>}

              {summary.workflowChanges > 0 && (
                <section className="wf-vdiff-sec" aria-labelledby={`${uid}-wf`}>
                  <h3 className="wf-vdiff-h" id={`${uid}-wf`}>
                    Workflow
                  </h3>
                  <ul className="wf-vdiff-wf">
                    {diff.nameChanged && (
                      <li>
                        <span className="wf-vdiff-k">Name</span>
                        <ValueCell value={diff.from?.workflowName ?? null} segs={null} side="before" />
                        <span aria-hidden="true"> → </span>
                        <span className="wf-vdiff-sr"> {tHC("hcComponentsWorkflowVersionDiffPanel.s1")} </span>
                        <ValueCell value={diff.to?.workflowName ?? null} segs={null} side="after" />
                      </li>
                    )}
                    {diff.statusChanged && (
                      <li>
                        <span className="wf-vdiff-k">Status</span>
                        {prettyStatus(diff.from?.status)}
                        <span aria-hidden="true"> → </span>
                        <span className="wf-vdiff-sr"> {tHC("hcComponentsWorkflowVersionDiffPanel.s1")} </span>
                        {prettyStatus(diff.to?.status)}
                      </li>
                    )}
                    {diff.descriptionChanged && (
                      <li>
                        <span className="wf-vdiff-k">Description</span>
                        edited
                      </li>
                    )}
                  </ul>
                  {diff.details.length > 0 && (
                    <ChangeTable
                      caption="Workflow settings changes"
                      rows={diff.details.map((d) => ({ key: d.field, label: d.field, before: d.from, after: d.to }))}
                    />
                  )}
                </section>
              )}

              {rows.length > 0 && (
                <section className="wf-vdiff-sec" aria-labelledby={`${uid}-nodes`}>
                  <h3 className="wf-vdiff-h" id={`${uid}-nodes`}>
                    Nodes
                    <span className="wf-vdiff-count">{rows.length}</span>
                  </h3>
                  <VersionDiffNodeList
                    uid={uid}
                    rows={rows}
                    activeIdx={activeIdx}
                    setActive={setActive}
                    expanded={expanded}
                    toggle={toggle}
                    onFocusNode={onFocusNode}
                  />
                </section>
              )}

              {(diff.graph.addedEdges.length > 0 || diff.graph.removedEdges.length > 0) && (
                <section className="wf-vdiff-sec" aria-labelledby={`${uid}-edges`}>
                  <h3 className="wf-vdiff-h" id={`${uid}-edges`}>
                    Connections
                  </h3>
                  <ul className="wf-vdiff-edges">
                    {(
                      [
                        ...diff.graph.addedEdges.map((e) => ["added", e] as const),
                        ...diff.graph.removedEdges.map((e) => ["removed", e] as const),
                      ] as (readonly ["added" | "removed", DiffEdgeBrief])[]
                    ).map(([kind, e], i) => (
                      <li key={`${kind}-${e.id ?? i}-${i}`} data-kind={kind}>
                        <span className="wf-vdiff-badge" data-kind={kind}>
                          {kind === "added" ? <Plus size={10} aria-hidden="true" /> : <Minus size={10} aria-hidden="true" />}
                          {kind === "added" ? "Added" : "Removed"}
                        </span>
                        <span className="wf-vdiff-edge">
                          {labelOf(e.source)}
                          {e.sourceHandle ? ` (${e.sourceHandle})` : ""}
                          <span aria-hidden="true"> → </span>
                          <span className="wf-vdiff-sr"> to </span>
                          {labelOf(e.target)}
                        </span>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
