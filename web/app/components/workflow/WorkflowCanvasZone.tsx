"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DragEventHandler, KeyboardEvent as ReactKeyboardEvent, ReactNode, Ref } from "react";
import { ReactFlow, Background, MiniMap, Panel, ConnectionLineType } from "@xyflow/react";
import type {
  Connection,
  Edge,
  FitViewOptions,
  Node,
  OnConnectEnd,
  OnEdgesChange,
  OnNodesChange,
  Viewport,
} from "@xyflow/react";
import {
  Scan,
  Plus,
  Minus,
  Command,
  MousePointer2,
  Hand,
  LayoutGrid,
  Grid3x3,
  Keyboard,
  Copy,
  ClipboardPaste,
  CopyPlus,
  History,
  X,
} from "lucide-react";
import WorkflowTour from "./WorkflowTour";
import type { WorkflowTourStep } from "./WorkflowTour";
import WorkflowCommandPalette from "./WorkflowCommandPalette";
import { NODE_DEFS } from "./nodeDefs";
import {
  CONNECTION_LINE_STYLE,
  CONNECTION_RADIUS,
  DEFAULT_EDGE_OPTIONS,
  edgeTypes,
  nodeTypes,
} from "./workflowCanvasHelpers";
import "./workflow-chrome.css";
import { Button } from "../ui/button";
import { IconAction } from "./StudioControls";
import { useTranslations } from "../../lib/i18n";

const SNAP_GRID: [number, number] = [10, 10];
const FIT_OPTIONS: FitViewOptions = { maxZoom: 1, padding: 0.3 };
const MULTI_SELECT_KEYS = ["Meta", "Control"];
const DELETE_KEYS = ["Backspace", "Delete"];
const NODE_COLOR_BY_TYPE = new Map(NODE_DEFS.map((d) => [d.type, d.color]));

export interface WorkflowCanvasZoneProps {
  cmdOpen: boolean;
  displayEdges: Edge[];
  displayNodes: Node[];
  edges: Edge[];
  nodes: Node[];
  exitTour: () => void;
  fitView: (options?: FitViewOptions) => unknown;
  /** Studio layout: per-side padding so "fit" frames the graph between the floating panels. */
  fitPadding?: FitViewOptions["padding"];
  focusTourNode: (nodeId: string) => void;
  handlePalettePick: (type: string) => void;
  onConnect: (connection: Connection) => void;
  onConnectEnd: OnConnectEnd;
  onDragOver: DragEventHandler<HTMLDivElement>;
  onDrop: DragEventHandler<HTMLDivElement>;
  onEdgesChange: OnEdgesChange;
  onNodeDragStart: () => void;
  onNodesChange: OnNodesChange;
  reactFlowWrapper: Ref<HTMLDivElement>;
  runLive: boolean;
  runResult: { id: string; status: string } | null;
  setCmdOpen: (open: boolean) => void;
  setInspectorOpen: (open: boolean) => void;
  setPendingConnection: (connection: null) => void;
  setSelectedNodeId: (id: string | null) => void;
  setZoomPct: (pct: number) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  tourOpen: boolean;
  tourSteps: WorkflowTourStep[];
  zoomIn: () => unknown;
  zoomOut: () => unknown;
  zoomPct: number;
  // Optional chrome hooks: a toolbar button only renders when its handler is passed.
  onAutoLayout?: () => void;
  snapToGrid?: boolean;
  onToggleSnap?: () => void;
  onCopy?: () => void;
  onPaste?: () => void;
  onDuplicate?: () => void;
  showShortcuts?: boolean;
  onToggleShortcuts?: () => void;
  // Observe mode (flight recorder): the graph is a read-only instrument panel.
  readOnly?: boolean;
  /** Replaces the edit-mode node click (config drawer) while observing. */
  onObserveNodeClick?: (nodeId: string) => void;
  onObservePaneClick?: () => void;
  /** "Runs" toolbar toggle; renders only when passed. */
  onToggleRuns?: () => void;
  runsActive?: boolean;
  /** Floating chrome rendered inside the zone (lens bar, step inspector). */
  overlay?: ReactNode;
  /** Small status chip after the zone title (workflow-level eval summary). */
  titleExtra?: ReactNode;
}

const SHORTCUTS: Array<{ label: string; keys: string[] }> = [
  { label: "Add node", keys: ["Ctrl", "K"] },
  { label: "Undo / redo", keys: ["Ctrl", "Z", "/", "Shift", "Z"] },
  { label: "Copy / paste / duplicate", keys: ["Ctrl", "C", "/", "V", "/", "D"] },
  { label: "Tidy layout", keys: ["Shift", "L"] },
  { label: "Fit to screen", keys: ["Shift", "1"] },
  { label: "Delete selection", keys: ["Delete"] },
  { label: "Nudge selection", keys: ["Arrow keys"] },
  { label: "Pan canvas", keys: ["Space", "drag"] },
];

function ShortcutsSheet({ onClose }: { onClose: () => void }) {
  const tHC = useTranslations("hcComponentsWorkflowWorkflowCanvasZone");
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>("button")?.focus();
    return () => prev?.focus?.();
  }, []);
  function onKeyDown(e: ReactKeyboardEvent) {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      onClose();
    } else if (e.key === "Tab") {
      // The close button is the only focusable control, so keep focus on it.
      e.preventDefault();
    }
  }
  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- click-outside backdrop; Escape closes via the dialog's key handler
    <div className="wf-shortcuts-backdrop" onClick={onClose}>
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog: Escape/Tab trap on the dialog element */}
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={tHC("s1")}
        className="wf-shortcuts"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <div className="wf-shortcuts-head">
          <span>{tHC("s1")}</span>
          <button type="button" className="wf-tb-btn" aria-label={tHC("s15")} onClick={onClose}>
            <X size={14} />
          </button>
        </div>
        <dl>
          {SHORTCUTS.map((s) => (
            <div key={s.label} style={{ display: "contents" }}>
              <dt>{s.label}</dt>
              <dd>
                {s.keys.map((k, i) =>
                  k === "/" ? (
                    <span key={i} aria-hidden="true">
                      /
                    </span>
                  ) : (
                    <kbd key={i} className="wf-kbd">
                      {k}
                    </kbd>
                  )
                )}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

function minimapNodeColor(node: Node): string {
  const data = node.data as { color?: unknown } | undefined;
  if (data && typeof data.color === "string") return data.color;
  return NODE_COLOR_BY_TYPE.get(node.type ?? "") ?? "var(--ax-accent2, var(--ax-cyan))";
}

function ToolbarButton({
  label,
  onClick,
  pressed,
  children,
}: {
  label: string;
  onClick: () => void;
  pressed?: boolean;
  children: ReactNode;
}) {
  // The console's icon-button contract (StudioControls.IconAction): ghost
  // shadcn Button + tooltip. "Label (Shortcut)" labels split into a tooltip
  // with a key hint; aria-label keeps the full original text.
  const m = /^(.*?)\s*\(([^)]+)\)$/.exec(label);
  return (
    <IconAction
      label={label}
      shortcut={m && /(ctrl|shift|alt|⌘|\+)/i.test(m[2]) ? m[2] : undefined}
      pressed={pressed}
      side="top"
      onClick={onClick}
    >
      {children}
    </IconAction>
  );
}

export default function WorkflowCanvasZone({
  cmdOpen,
  displayEdges,
  displayNodes,
  edges,
  exitTour,
  fitView,
  fitPadding,
  focusTourNode,
  handlePalettePick,
  nodes,
  onConnect,
  onConnectEnd,
  onDragOver,
  onDrop,
  onEdgesChange,
  onNodeDragStart,
  onNodesChange,
  reactFlowWrapper,
  runLive,
  runResult,
  setCmdOpen,
  setInspectorOpen,
  setPendingConnection,
  setSelectedNodeId,
  setZoomPct,
  t,
  tourOpen,
  tourSteps,
  zoomIn,
  zoomOut,
  zoomPct,
  onAutoLayout,
  snapToGrid = false,
  onToggleSnap,
  onCopy,
  onPaste,
  onDuplicate,
  showShortcuts = false,
  onToggleShortcuts,
  readOnly = false,
  onObserveNodeClick,
  onObservePaneClick,
  onToggleRuns,
  runsActive = false,
  overlay,
  titleExtra,
}: WorkflowCanvasZoneProps) {
  const tHC = useTranslations("hcComponentsWorkflowWorkflowCanvasZone");
  // "select": drag draws a marquee, pan via middle/right button or Space.
  // "pan": plain drag pans the canvas.
  const [mode, setMode] = useState<"select" | "pan">("select");
  const onMove = useCallback(
    (_: unknown, viewport: Viewport) => setZoomPct(Math.round(viewport.zoom * 100)),
    [setZoomPct]
  );
  const panMode = mode === "pan";
  const hasEdit = !readOnly && Boolean(onCopy || onPaste || onDuplicate);
  const fitOptions = useMemo<FitViewOptions>(
    () => (fitPadding ? { maxZoom: FIT_OPTIONS.maxZoom, padding: fitPadding } : FIT_OPTIONS),
    [fitPadding]
  );

  return (
    <>
      <div ref={reactFlowWrapper} onDrop={onDrop} onDragOver={onDragOver} className="ax-wf-canvas-zone">
        <div className="ax-wf-canvas-title">
          {t("flowGraphTitle")}
          {runResult
            ? ` · RUN #${runResult.id.slice(0, 8)} ${runLive ? t("runStatusInProgress") : runResult.status}`
            : ""}
          {titleExtra}
        </div>

        <ReactFlow
          aria-label={tHC("s14")}
          nodes={displayNodes}
          edges={displayEdges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onConnectEnd={onConnectEnd}
          onNodeDragStart={onNodeDragStart}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          defaultEdgeOptions={DEFAULT_EDGE_OPTIONS}
          connectionLineStyle={CONNECTION_LINE_STYLE}
          connectionLineType={ConnectionLineType.SmoothStep}
          connectionRadius={CONNECTION_RADIUS}
          nodesConnectable={!readOnly}
          nodesDraggable={!readOnly}
          edgesReconnectable={!readOnly}
          snapToGrid={snapToGrid}
          snapGrid={SNAP_GRID}
          selectionOnDrag={!panMode && !readOnly}
          panOnDrag={panMode || readOnly ? true : [1, 2]}
          panActivationKeyCode="Space"
          multiSelectionKeyCode={MULTI_SELECT_KEYS}
          deleteKeyCode={readOnly ? null : DELETE_KEYS}
          minZoom={0.1}
          maxZoom={2}
          onNodeClick={(_, node) => {
            if (readOnly) {
              onObserveNodeClick?.(node.id);
              return;
            }
            setSelectedNodeId(node.id);
            // Mobile: the inspector is an off-canvas drawer below 900px
            // (see the paletteOpen/inspectorOpen comment above) - opening
            // it here is a no-op on desktop widths (the CSS class it adds
            // only does anything inside that media query).
            setInspectorOpen(true);
          }}
          onPaneClick={() => (readOnly ? onObservePaneClick?.() : setSelectedNodeId(null))}
          onMove={onMove}
          fitView
          fitViewOptions={fitOptions}
        >
          <Background gap={26} size={1} color="var(--ax-border)" />
          {/* Navigation minimap - pan/zoom the whole graph at a glance.
              Bottom-right is clear; picks up the existing
              .react-flow__minimap theme styles in globals.css. */}
          <MiniMap
            pannable
            zoomable
            position="bottom-right"
            ariaLabel="Workflow minimap"
            className="overflow-hidden rounded-lg border border-[var(--ax-border)] shadow-lg"
            style={{ width: 168, height: 108 }}
            maskColor="color-mix(in srgb, var(--ax-mid, currentColor) 16%, transparent)"
            nodeColor={minimapNodeColor}
            nodeStrokeColor="transparent"
            nodeBorderRadius={3}
          />
          {!readOnly && nodes.length <= 1 && (
            <Panel position="top-center">
              {/* Glassy, interactive first-build hint that surfaces the fast
                  ⌘K path right where a new builder is looking. */}
              <div className="flex items-center gap-2.5 rounded-lg border border-primary/25 bg-popover/85 px-4 py-2 text-xs font-medium text-muted-foreground shadow-xl shadow-black/10 backdrop-blur-md">
                <span className="flex size-5 shrink-0 items-center justify-center rounded-md bg-primary/15 text-primary">
                  <Plus size={12} />
                </span>
                {t("emptyCanvasHint")}
                <span className="text-border">·</span>
                <button
                  type="button"
                  onClick={() => setCmdOpen(true)}
                  title={tHC("s13")}
                  aria-label={tHC("s11")}
                  className="inline-flex items-center gap-1 rounded-md border-0 bg-transparent p-0 font-semibold text-foreground shadow-none transition-colors hover:text-primary"
                >
                  <kbd className="inline-flex items-center gap-0.5 rounded border border-border bg-secondary/60 px-1.5 py-0.5 font-mono text-[10px]">
                    <Command size={9} />K
                  </kbd>
                </button>
              </div>
            </Panel>
          )}
          {/* Taught at the point of need, once there's actually something to
           * connect, rather than in a separate help doc no one reads first -
           * the exact moment "how do I connect these" comes up. */}
          {!readOnly && nodes.length > 1 && edges.length === 0 && (
            <Panel position="top-center" className="wf-empty-hint">
              {t("connectNodesHint")}
            </Panel>
          )}
          {/* Floating "+ Add node" - opens the ⌘K command palette. The fast,
              modern way to drop a node without hunting the left rail. */}
          {!readOnly && (
            <Panel position="bottom-left">
              <button
                type="button"
                onClick={() => setCmdOpen(true)}
                title={tHC("s12")}
                aria-label={tHC("s11")}
                className="flex items-center gap-2 rounded-lg border border-border bg-popover/90 px-3 py-2 text-xs font-medium text-foreground shadow-lg backdrop-blur-md transition-colors hover:border-primary/50 hover:bg-accent"
              >
                <Plus size={15} className="text-primary" />
                {tHC("s10")}
                <kbd className="ms-0.5 hidden items-center gap-0.5 rounded border border-border bg-secondary/60 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground sm:inline-flex">
                  <Command size={9} />K
                </kbd>
              </button>
            </Panel>
          )}
        </ReactFlow>

        {/* Floating bottom-centre toolbar. Zoom -/%/+ and fit are driven by
         * @xyflow/react's own zoomIn/zoomOut/fitView; the percentage comes
         * from the canvas's onMove viewport event, never a fabricated number.
         * Optional actions render only when the parent supplies a handler. */}
        <div className="wf-toolbar" role="toolbar" aria-label={tHC("s9")}>
          {onToggleRuns && (
            <>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className={`wf-tb-runs text-muted-foreground hover:text-foreground${runsActive ? " bg-accent text-foreground" : ""}`}
                data-runs-toggle=""
                aria-pressed={runsActive}
                title={runsActive ? "Back to editing" : "Runs: replay and inspect past runs"}
                onClick={onToggleRuns}
              >
                <History size={14} aria-hidden="true" />
                <span>Runs</span>
              </Button>
              <span className="wf-toolbar-sep" aria-hidden="true" />
            </>
          )}
          {!readOnly && (
            <>
              <ToolbarButton label={tHC("s8")} pressed={!panMode} onClick={() => setMode("select")}>
                <MousePointer2 size={15} />
              </ToolbarButton>
              <ToolbarButton label={tHC("s7")} pressed={panMode} onClick={() => setMode("pan")}>
                <Hand size={15} />
              </ToolbarButton>
            </>
          )}
          {!readOnly && (onAutoLayout || onToggleSnap) && <span className="wf-toolbar-sep" aria-hidden="true" />}
          {!readOnly && onAutoLayout && (
            <ToolbarButton label={tHC("s6")} onClick={onAutoLayout}>
              <LayoutGrid size={15} />
            </ToolbarButton>
          )}
          {!readOnly && onToggleSnap && (
            <ToolbarButton
              label={`Snap to grid: ${snapToGrid ? "on" : "off"}`}
              pressed={snapToGrid}
              onClick={onToggleSnap}
            >
              <Grid3x3 size={15} />
            </ToolbarButton>
          )}
          {hasEdit && <span className="wf-toolbar-sep" aria-hidden="true" />}
          {hasEdit && onCopy && (
            <ToolbarButton label={tHC("s5")} onClick={onCopy}>
              <Copy size={15} />
            </ToolbarButton>
          )}
          {hasEdit && onPaste && (
            <ToolbarButton label={tHC("s4")} onClick={onPaste}>
              <ClipboardPaste size={15} />
            </ToolbarButton>
          )}
          {hasEdit && onDuplicate && (
            <ToolbarButton label={tHC("s3")} onClick={onDuplicate}>
              <CopyPlus size={15} />
            </ToolbarButton>
          )}
          {!readOnly && <span className="wf-toolbar-sep" aria-hidden="true" />}
          <ToolbarButton label={t("zoomOutTooltip")} onClick={() => zoomOut()}>
            <Minus size={15} />
          </ToolbarButton>
          <span className="wf-tb-readout" role="status" aria-label={tHC("s2")}>
            {zoomPct}%
          </span>
          <ToolbarButton label={t("zoomInTooltip")} onClick={() => zoomIn()}>
            <Plus size={15} />
          </ToolbarButton>
          <ToolbarButton label={`${t("fitToScreenTooltip")} (Shift+1)`} onClick={() => fitView(fitOptions)}>
            <Scan size={15} />
          </ToolbarButton>
          {onToggleShortcuts && (
            <>
              <span className="wf-toolbar-sep" aria-hidden="true" />
              <ToolbarButton label={tHC("s1")} pressed={showShortcuts} onClick={onToggleShortcuts}>
                <Keyboard size={15} />
              </ToolbarButton>
            </>
          )}
        </div>

        {showShortcuts && onToggleShortcuts && <ShortcutsSheet onClose={onToggleShortcuts} />}

        {/* Guided-tour overlay (timeline HUD + narration bubble + controls).
         * Positioned inside .ax-wf-canvas-zone (position:relative) so it
         * floats over the graph like the AXON reference's
         * [data-hud-tl]/[data-narrate]. Only mounted while a tour is active;
         * it manages its own step index/play state and calls focusTourNode
         * to pan+highlight each real node. */}
        {overlay}

        {tourOpen && tourSteps.length > 0 && (
          <WorkflowTour steps={tourSteps} onFocus={focusTourNode} onExit={exitTour} />
        )}

        {/* ⌘K command palette for adding nodes - also the picker for
            drop-to-create (onConnectEnd on empty pane). */}
        <WorkflowCommandPalette
          open={cmdOpen}
          onClose={() => {
            setCmdOpen(false);
            setPendingConnection(null);
          }}
          onPick={handlePalettePick}
        />
      </div>
    </>
  );
}
