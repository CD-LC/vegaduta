"use client";

// Agent Lab - the run graph drawn with React Flow, read-only: a picture of a
// turn, not an editor (same recipe as brief/EnvoyGraph). Selection is by
// pointer (onNodeClick); RunGraphInspector's Previous/Next buttons are the
// keyboard path, so the nodes themselves are not tab stops.
import { useEffect, useMemo, useState, type ReactElement } from "react";
import {
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  ReactFlowProvider,
  getViewportForBounds,
  useNodesInitialized,
  useReactFlow,
  useStore,
  type Edge,
  type Node,
  type NodeHandle,
  type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import "./run-graph.css";
import { ArrowRightLeft, Smartphone } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { cn } from "../../../lib/utils";
import { StepKindIcon, StepStatusIcon, statusTone, useStepLabels } from "./RunGraphInspector";
import { RUN_GRAPH_NODE_HEIGHT, RUN_GRAPH_NODE_WIDTH, type RunGraph, type RunGraphNode } from "./runGraphModel";

type LabStepData = { step: RunGraphNode };

/** The node surface is a <div>, never a <button>: the global button rule adds
 * a press transform, and a transformed node detaches its edges. */
function LabStepNode({ data, selected }: NodeProps): ReactElement {
  const t = useTranslations("agentLab");
  const { kindLabel, statusLabel } = useStepLabels();
  const { step } = data as LabStepData;
  return (
    <div
      className="alab-node flex flex-col justify-center gap-1 px-3"
      data-kind={step.kind}
      data-status={step.status}
      data-selected={selected ? "true" : "false"}
    >
      {step.kind !== "user" && <Handle type="target" position={Position.Left} className="alab-handle" isConnectable={false} />}
      <div className="flex min-w-0 items-center gap-1.5 text-[11px]">
        <StepKindIcon kind={step.kind} className="text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate font-medium text-muted-foreground">{kindLabel(step.kind)}</span>
        {step.handoff && (
          <span className="inline-flex shrink-0 text-muted-foreground" title={t("graphHandoffBadge")}>
            <ArrowRightLeft className="size-3" aria-hidden="true" />
            <span className="sr-only">{t("graphHandoffBadge")}</span>
          </span>
        )}
        {step.onDevice && (
          <span className="inline-flex shrink-0 text-muted-foreground" title={t("graphOnDeviceBadge")}>
            <Smartphone className="size-3" aria-hidden="true" />
            <span className="sr-only">{t("graphOnDeviceBadge")}</span>
          </span>
        )}
        {/* Icon AND word: status is never colour alone. */}
        {step.kind !== "user" && (
          <span className={cn("inline-flex shrink-0 items-center gap-1", statusTone(step.status))}>
            <StepStatusIcon status={step.status} />
            {statusLabel(step.status)}
          </span>
        )}
      </div>
      <div className="min-w-0 truncate text-xs font-medium text-foreground" title={step.title || undefined}>
        {step.title || " "}
      </div>
      {step.kind !== "reply" && <Handle type="source" position={Position.Right} className="alab-handle" isConnectable={false} />}
    </div>
  );
}

// Module-level so React Flow sees one stable object. Custom type names only:
// "input", "default", "output" and "group" are React Flow's own.
const nodeTypes = { labStep: LabStepNode };
const MIN_ZOOM = 0.3;
const FIT_MAX_ZOOM = 1;
const FIT_PADDING = 0.15;
// Every node is exactly this size (the model lays them out on that basis), so
// React Flow is told up front instead of measuring each one. This matters
// here: the flow is controlled with no onNodesChange, so a measurement React
// Flow takes itself is lost every time a node object is replaced, the nodes
// then never count as initialised, and the re-fit below would never run.
const MEASURED = { width: RUN_GRAPH_NODE_WIDTH, height: RUN_GRAPH_NODE_HEIGHT };
// Same for the two handles (6px dots centred on the left and right edge, see
// .alab-handle): with their bounds declared, edges are drawn in the same
// pass as the nodes instead of waiting for a measurement.
const HANDLE = { width: 6, height: 6, y: RUN_GRAPH_NODE_HEIGHT / 2 - 3 };
const TARGET_HANDLE: NodeHandle = { ...HANDLE, type: "target", position: Position.Left, x: -3 };
const SOURCE_HANDLE: NodeHandle = { ...HANDLE, type: "source", position: Position.Right, x: RUN_GRAPH_NODE_WIDTH - 3 };

type CachedNode = { step: RunGraphNode; selected: boolean; node: Node };

function sameStep(a: RunGraphNode, b: RunGraphNode): boolean {
  const ka = Object.keys(a) as (keyof RunGraphNode)[];
  return ka.length === Object.keys(b).length && ka.every((key) => a[key] === b[key]);
}

function Flow({ graph, selectedId, onSelect }: Pick<RunGraphCanvasProps, "graph" | "selectedId" | "onSelect">) {
  const { setViewport } = useReactFlow();

  // Coarse pointer (touch): a page swipe over the graph must scroll the lab,
  // not pan the diagram. Pinch-zoom stays on either way.
  const [coarsePointer, setCoarsePointer] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return undefined;
    const mql = window.matchMedia("(pointer: coarse)");
    setCoarsePointer(mql.matches);
    const onChange = () => setCoarsePointer(mql.matches);
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, []);

  // The graph is rebuilt on every streamed token, but most steps come out
  // identical. Handing React Flow the SAME node object for an unchanged step
  // keeps it from re-adopting and re-measuring the whole graph per token.
  const [cache] = useState(() => new Map<string, CachedNode>());
  const nodes = useMemo<Node[]>(() => {
    const next = graph.nodes.map((step) => {
      const selected = step.id === selectedId;
      const hit = cache.get(step.id);
      if (hit && hit.selected === selected && sameStep(hit.step, step)) return hit.node;
      const node: Node = {
        id: step.id,
        type: "labStep",
        position: { x: step.x, y: step.y },
        width: RUN_GRAPH_NODE_WIDTH,
        height: RUN_GRAPH_NODE_HEIGHT,
        measured: MEASURED,
        // Mirrors which <Handle>s LabStepNode renders for this kind.
        handles: step.kind === "user" ? [SOURCE_HANDLE] : step.kind === "reply" ? [TARGET_HANDLE] : [TARGET_HANDLE, SOURCE_HANDLE],
        data: { step } satisfies LabStepData,
        selected,
        draggable: false,
        connectable: false,
      };
      cache.set(step.id, { step, selected, node });
      return node;
    });
    for (const id of [...cache.keys()]) {
      if (!graph.nodes.some((step) => step.id === id)) cache.delete(id);
    }
    return next;
  }, [graph.nodes, selectedId, cache]);
  const edges = useMemo<Edge[]>(
    () =>
      graph.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        animated: edge.live,
        selectable: false,
        markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
        style: { stroke: "var(--muted-foreground)", opacity: 0.75 },
      })),
    [graph.edges]
  );

  // The graph grows while a turn streams. Re-frame it whenever the node
  // count, the extent of the layout or the size of the pane changes, once
  // React Flow has adopted the nodes. The viewport is computed here from the
  // model's own bounds rather than through fitView(): React Flow's mount-time
  // fit can run before its pan/zoom exists and is then dropped, and a later
  // fitView() call is resolved on an animation frame, which a background tab
  // never gets. This way the first frame is already framed.
  const nodesMeasured = useNodesInitialized();
  const nodeCount = graph.nodes.length;
  const { width, height } = graph;
  const paneWidth = useStore((s) => s.width);
  const paneHeight = useStore((s) => s.height);
  useEffect(() => {
    if (!nodesMeasured || nodeCount === 0 || !paneWidth || !paneHeight) return;
    const viewport = getViewportForBounds({ x: 0, y: 0, width, height }, paneWidth, paneHeight, MIN_ZOOM, FIT_MAX_ZOOM, FIT_PADDING);
    void setViewport(viewport, { duration: 0 });
  }, [nodesMeasured, nodeCount, width, height, paneWidth, paneHeight, setViewport]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      nodesFocusable={false}
      edgesFocusable={false}
      elementsSelectable
      onNodeClick={(_, node) => onSelect(node.id)}
      proOptions={{ hideAttribution: true }}
      minZoom={MIN_ZOOM}
      panOnDrag={!coarsePointer}
      zoomOnScroll={!coarsePointer}
    />
  );
}

export interface RunGraphCanvasProps {
  graph: RunGraph;
  selectedId: string | null;
  onSelect: (id: string) => void;
  ariaLabel: string;
}

/**
 * Fills its parent, which must have an explicit height. Carries its own
 * ReactFlowProvider because the compare view shows two canvases at once.
 */
export default function RunGraphCanvas({ graph, selectedId, onSelect, ariaLabel }: RunGraphCanvasProps): ReactElement {
  return (
    <div role="group" aria-label={ariaLabel} className="alab-canvas h-full w-full">
      <ReactFlowProvider>
        <Flow graph={graph} selectedId={selectedId} onSelect={onSelect} />
      </ReactFlowProvider>
    </div>
  );
}
