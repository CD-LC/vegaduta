"use client";

// The envoy as a graph: every artifact the Brief creates, kind-typed, with the
// dependencies between them and - the point of the screen - an approval shield
// on every edge that is guarded at the current trust rung. Hovering a shield
// says what disappears at the next rung. Simple shows only who does the work,
// Guided adds what they lean on and lets a workflow open inline, Expert is the
// raw manifest.

import { createContext, useContext, useEffect, useMemo, useState } from "react";
import {
  BaseEdge,
  EdgeLabelRenderer,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  getSmoothStepPath,
  type EdgeProps,
  type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  Bot,
  ChevronDown,
  ChevronRight,
  Lock,
  ListOrdered,
  MessageSquare,
  Plug,
  Shield,
  ShieldHalf,
  Wrench,
  Workflow,
  BookOpen,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { nodeDef } from "../workflow/nodeDefs";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import {
  READINESS_STATE_TOKEN,
  buildEnvoyGraph,
  guardTotals,
  type ArtifactKind,
  type EnvoyNodeData,
  type Guard,
  type GraphMode,
} from "./logic";
import type { Brief, ReadinessRow, Rung } from "./types";

const KIND_ICON: Record<ArtifactKind, LucideIcon> = {
  agent: Bot,
  workflow: Workflow,
  tool: Wrench,
  mcp: Plug,
  knowledge: BookOpen,
  procedure: ListOrdered,
  secret: Lock,
  channel: MessageSquare,
};

// Category hue per kind (design-system contract: hue + icon chip, never a bare dot).
const KIND_CAT: Record<ArtifactKind, string | undefined> = {
  agent: "var(--cat-assistant)",
  workflow: "var(--cat-automation)",
  tool: "var(--cat-analysis)",
  mcp: "var(--cat-analysis)",
  knowledge: "var(--cat-data)",
  procedure: "var(--cat-data)",
  secret: "var(--cat-creative)",
  channel: "var(--cat-comms)",
};

function stepCat(nodeType?: string): string {
  if (nodeType === "agent" || nodeType === "supervisor") return "var(--cat-assistant)";
  if (nodeType === "output" || nodeType === "bulk_email") return "var(--cat-comms)";
  if (nodeType === "knowledge") return "var(--cat-data)";
  if (nodeType === "http" || nodeType === "tool" || nodeType === "code") return "var(--cat-analysis)";
  return "var(--cat-automation)";
}

interface GraphActions {
  toggleExpand: (ref: string) => void;
}
const GraphActionsContext = createContext<GraphActions>({ toggleExpand: () => {} });

const handleClass = "!size-1.5 !border-0 !bg-muted-foreground/60";

function GuardText({ guard }: { guard: Guard }) {
  const t = useTranslations("brief");
  const next = guard.next ? t(`rung.${guard.next}`) : "";
  if (guard.kind === "human") return <>{t("shield.human")}</>;
  if (!guard.disappearsAtNext)
    return <>{t(guard.irreversible ? "shield.staysIrreversible" : "shield.stays", { next })}</>;
  return <>{t("shield.disappears", { next })}</>;
}

function ShieldEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps) {
  const t = useTranslations("brief");
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 10,
  });
  const guard = (data as { guard?: Guard } | undefined)?.guard ?? null;
  // Radix Tooltip opens on hover/focus only, not on a touch tap — so a
  // touch/tablet visitor could never see what a shield explains. Making it a
  // real toggle (controlled `open`) means a tap opens the same content a
  // pointer visitor gets on hover, without losing the hover behavior itself.
  const [open, setOpen] = useState(false);
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      {guard && (
        <EdgeLabelRenderer>
          <div
            className="nodrag nopan pointer-events-auto absolute"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            <Tooltip open={open} onOpenChange={setOpen}>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  onClick={() => setOpen((o) => !o)}
                  className="flex size-6 items-center justify-center rounded-full border border-border bg-card text-foreground shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
                  aria-label={t("shield.aria")}
                  data-guard={guard.kind}
                >
                  {guard.kind === "human" ? (
                    <ShieldHalf className="size-3.5" aria-hidden="true" />
                  ) : (
                    <Shield className="size-3.5" aria-hidden="true" />
                  )}
                </button>
              </TooltipTrigger>
              <TooltipContent>
                <GuardText guard={guard} />
              </TooltipContent>
            </Tooltip>
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

function EnvoyNodeView({ data, selected }: NodeProps) {
  const t = useTranslations("brief");
  const { toggleExpand } = useContext(GraphActionsContext);
  const d = data as EnvoyNodeData;
  const kind = d.kind as ArtifactKind;
  const Icon = KIND_ICON[kind];
  return (
    <div
      className={cn(
        "elev-base flex items-center gap-2.5 rounded-xl border border-border px-3 py-2 shadow-sm",
        selected && "ring-2 ring-[var(--ring)]"
      )}
      style={{ width: 196, minHeight: 64 }}
      data-kind={kind}
    >
      <Handle type="target" position={Position.Left} className={handleClass} />
      <span
        className="cat-chip cat-chip-sm shrink-0"
        style={{ ["--cat" as string]: KIND_CAT[kind] }}
        aria-hidden="true"
      >
        <Icon className="size-3.5" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t(`kind.${kind}`)}</div>
        <div className="truncate text-sm font-medium text-foreground" title={d.label}>
          {d.label}
        </div>
      </div>
      {d.state && (
        <span
          className="state-dot shrink-0"
          style={{ ["--state" as string]: READINESS_STATE_TOKEN[d.state] }}
          role="img"
          aria-label={t(`state.${d.state}`)}
          title={t(`state.${d.state}`)}
        />
      )}
      {kind === "workflow" && !!d.guardCount && (
        <span
          className="inline-flex items-center gap-0.5 text-xs text-muted-foreground"
          title={t("shield.count", { count: d.guardCount })}
        >
          <Shield className="size-3" aria-hidden="true" />
          {d.guardCount}
        </span>
      )}
      {d.expandable && (
        <button
          type="button"
          className="nodrag rounded border-0 bg-transparent p-1.5 text-muted-foreground shadow-none hover:text-foreground"
          onClick={(e) => {
            e.stopPropagation();
            toggleExpand(d.ref);
          }}
          aria-expanded={!!d.expanded}
          aria-label={t(d.expanded ? "graph.collapse" : "graph.expand")}
        >
          {d.expanded ? (
            <ChevronDown className="size-4" aria-hidden="true" />
          ) : (
            <ChevronRight className="size-4" aria-hidden="true" />
          )}
        </button>
      )}
      <Handle type="source" position={Position.Right} className={handleClass} />
    </div>
  );
}

// An expanded workflow: a labelled frame that holds its own steps.
function FrameNodeView({ data, selected }: NodeProps) {
  const t = useTranslations("brief");
  const { toggleExpand } = useContext(GraphActionsContext);
  const d = data as EnvoyNodeData;
  return (
    <div
      className={cn(
        "elev-sunken h-full w-full rounded-xl border border-dashed border-border",
        selected && "ring-2 ring-[var(--ring)]"
      )}
      data-kind="workflow"
    >
      <Handle type="target" position={Position.Left} className={handleClass} />
      <div className="flex items-center gap-2 px-3 py-2">
        <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: KIND_CAT.workflow }} aria-hidden="true">
          <Workflow className="size-3.5" aria-hidden="true" />
        </span>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{d.label}</span>
        <button
          type="button"
          className="nodrag rounded border-0 bg-transparent p-1.5 text-muted-foreground shadow-none hover:text-foreground"
          onClick={(e) => {
            e.stopPropagation();
            toggleExpand(d.ref);
          }}
          aria-expanded="true"
          aria-label={t("graph.collapse")}
        >
          <ChevronDown className="size-4" aria-hidden="true" />
        </button>
      </div>
      <Handle type="source" position={Position.Right} className={handleClass} />
    </div>
  );
}

function StepNodeView({ data, selected }: NodeProps) {
  const t = useTranslations("brief");
  const d = data as EnvoyNodeData;
  const Icon = nodeDef(d.nodeType ?? "").icon ?? Workflow;
  return (
    <div
      className={cn(
        "elev-base flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5",
        selected && "ring-2 ring-[var(--ring)]"
      )}
      style={{ width: 168, height: 52 }}
      data-kind="step"
      data-node-type={d.nodeType}
    >
      <Handle type="target" position={Position.Left} className={handleClass} />
      <span
        className="cat-chip cat-chip-sm shrink-0"
        style={{ ["--cat" as string]: stepCat(d.nodeType) }}
        aria-hidden="true"
      >
        <Icon className="size-3.5" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1 truncate text-xs font-medium text-foreground" title={d.label}>
        {d.label}
      </span>
      {d.guard && (
        <span title={t("shield.aria")} className="text-muted-foreground">
          {d.guard.kind === "human" ? (
            <ShieldHalf className="size-3.5" aria-hidden="true" />
          ) : (
            <Shield className="size-3.5" aria-hidden="true" />
          )}
        </span>
      )}
      <Handle type="source" position={Position.Right} className={handleClass} />
    </div>
  );
}

const nodeTypes = { envoy: EnvoyNodeView, frame: FrameNodeView, step: StepNodeView };
const edgeTypes = { shield: ShieldEdge };

export default function EnvoyGraph({
  brief,
  rung,
  readiness = [],
  selectedRef,
  onSelect,
  className,
}: {
  brief: Brief;
  rung?: Rung;
  readiness?: readonly ReadinessRow[];
  selectedRef?: string | null;
  onSelect?: (ref: string) => void;
  className?: string;
}) {
  const t = useTranslations("brief");
  const [mode, setMode] = useState<GraphMode>("guided");
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const activeRung = rung ?? brief.trust.rung;

  // Coarse pointer (touch/tablet): a plain page-swipe over the graph must
  // scroll the page past it, not pan/zoom the diagram, so pan-on-drag and
  // zoom-on-scroll are switched off there; pinch-to-zoom stays on either way.
  const [coarsePointer, setCoarsePointer] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return undefined;
    const mql = window.matchMedia("(pointer: coarse)");
    setCoarsePointer(mql.matches);
    const onChange = () => setCoarsePointer(mql.matches);
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, []);

  const readinessByRef = useMemo(() => Object.fromEntries(readiness.map((r) => [r.ref, r.state])), [readiness]);
  const graph = useMemo(
    () => buildEnvoyGraph(brief, { rung: activeRung, mode, expanded, readinessByRef }),
    [brief, activeRung, mode, expanded, readinessByRef]
  );
  const totals = useMemo(() => guardTotals(brief, activeRung), [brief, activeRung]);

  const nodes = useMemo(
    () =>
      graph.nodes.map((n) => ({
        ...n,
        selected: n.id === selectedRef,
        // frames stay draggable=false too; the graph is a picture of the Brief, not an editor
        draggable: false,
      })),
    [graph.nodes, selectedRef]
  );
  const edges = useMemo(
    () =>
      graph.edges.map((e) => ({
        ...e,
        markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
        style: {
          stroke: "var(--muted-foreground)",
          opacity: e.type === "shield" ? 0.9 : 0.75,
          strokeDasharray: e.type === "shield" ? undefined : "4 3",
        },
      })),
    [graph.edges]
  );

  const actions = useMemo<GraphActions>(
    () => ({
      toggleExpand: (ref) =>
        setExpanded((prev) => {
          const next = new Set(prev);
          if (next.has(ref)) next.delete(ref);
          else next.add(ref);
          return next;
        }),
    }),
    []
  );

  const modes: GraphMode[] = ["simple", "guided", "expert"];

  return (
    <div className={cn("space-y-3", className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground" aria-live="polite">
          {totals.guarded === 0
            ? t("graph.noShields")
            : totals.next
              ? t("graph.shieldSummary", {
                  count: totals.guarded,
                  gone: totals.disappearAtNext,
                  next: t(`rung.${totals.next}`),
                })
              : t("graph.shieldSummaryTop", { count: totals.guarded })}
        </p>
        <div
          role="group"
          aria-label={t("graph.modeLabel")}
          className="inline-flex rounded-full border border-border bg-card p-0.5"
        >
          {modes.map((m) => (
            <button
              key={m}
              type="button"
              aria-pressed={mode === m}
              onClick={() => setMode(m)}
              className={cn(
                "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                mode === m ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
              )}
            >
              {t(`graph.mode.${m}`)}
            </button>
          ))}
        </div>
      </div>

      {mode === "expert" ? (
        <pre
          className="elev-sunken max-h-[32rem] overflow-auto rounded-xl border border-border p-4 text-xs leading-relaxed text-foreground"
          aria-label={t("graph.rawLabel")}
        >
          {JSON.stringify(brief, null, 2)}
        </pre>
      ) : (
        <GraphActionsContext.Provider value={actions}>
          <div
            className="elev-sunken h-[70vh] max-h-[30rem] overflow-hidden rounded-xl border border-border"
            data-testid="envoy-graph"
          >
            <ReactFlow
              // Re-fit when the shape of the graph changes (mode, expand, rung).
              key={`${mode}:${activeRung}:${[...expanded].sort().join(",")}`}
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              fitView
              fitViewOptions={{ padding: 0.15 }}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable
              onNodeClick={(_, node) => onSelect?.((node.data as EnvoyNodeData).ref)}
              proOptions={{ hideAttribution: true }}
              minZoom={0.3}
              // Coarse pointers (touch) get a normal page swipe instead of the
              // diagram capturing the gesture; pinch-zoom (zoomOnPinch, default
              // on) still works for anyone who wants to zoom the graph itself.
              panOnDrag={!coarsePointer}
              zoomOnScroll={!coarsePointer}
            />
          </div>
        </GraphActionsContext.Provider>
      )}
    </div>
  );
}
