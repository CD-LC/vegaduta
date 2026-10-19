"use client";

import { Handle, Position, useStore, useUpdateNodeInternals } from "@xyflow/react";
import { Check, Circle, Loader2, Minus, Pause, Pin, X, type LucideIcon } from "lucide-react";
import { bulkEmailMissing, nodeDef, nodeLabelKey, TRIGGER_CHANNEL_LABELS, voiceMenuPorts, type WorkflowGraphNodeData, type WorkflowNodeConfig } from "./nodeDefs";
import { useNodeDefTranslations } from "./useNodeDefTranslations";
import { isDataNodeType, summarizeDataNode, switchPorts, type SwitchPort } from "./dataNodes";
import { aiNodeSummary, classifierPorts, isAiNodeType } from "./aiNodes";
import { isFormTrigger, readFormDef, waitSummary } from "./workflowControl";
import { useTranslations } from "../../lib/i18n";
import { memo, useCallback, useContext, useEffect, type CSSProperties } from "react";
import { lensCssVars, type LensElementStyle } from "./lensStyles";
import NodeLivePanel from "./NodeLivePanel";
import EvalBadge, { type EvalNodeStats } from "./EvalBadge";
import PinBadge from "./PinBadge";
import { dataTableSubtitle } from "./dataTableNode";
import type { PinMeta } from "./pinModel";
import { NodeLiveOpenContext, type NodeLiveSlice } from "./runOverlay";
import "./workflow-canvas.css";
import "./run-overlay.css";
import "./pins.css";

// One node's slice of a run's trace, as WorkflowCanvas merges it into
// data.trace (built in applyRunResult from WorkflowExecutionEngine's real
// per-node trace entries). `status` stays a plain string rather than a
// literal union so a backend-added status falls through to a neutral state
// instead of failing the build here first. The metric fields are optional:
// the engine does not emit them on every build, so chips render only when
// they are present.
export type WorkflowNodeRunTrace = {
  status: string;
  summary?: string;
  durationMs?: number;
  tokens?: number;
  costUsd?: number;
};

// What WorkflowCanvas's displayNodes actually hands this renderer: the
// persisted {label, config} pair plus the three ephemeral, client-only
// overlays (trace/running/tourFocus) it merges in per render.
export type WorkflowCanvasNodeData = WorkflowGraphNodeData & {
  trace?: WorkflowNodeRunTrace | null;
  running?: boolean;
  tourFocus?: boolean;
  /** Visits of this node so far in the run on screen (loops / fan-out). */
  visitCount?: number;
  /** Replay progress mark (see runOverlay.ts ReplayMark). */
  replay?: "ahead" | "unreached";
  /** The step inspector is open on this node. */
  inspected?: boolean;
  /** A failed Validate named this node. */
  invalid?: boolean;
  /** Canvas lens paint for this node (lensStyles.lensNodeStyle). */
  lens?: LensElementStyle;
  /** Observe mode, agent/supervisor: streamed thinking + tool calls (runOverlay NodeLiveSlice). */
  live?: NodeLiveSlice;
  /** This node's row of GET .../eval-summary, when core serves it. */
  evalStats?: EvalNodeStats;
  /** Metadata of this node's data pin (never the pinned text), when it has one. */
  pin?: PinMeta;
};

// The scoped t() function useTranslations() returns (lib/i18n is itself
// untyped `any`, so this local alias is what keeps summarizeConfig honest).
type Translator = (key: string, values?: Record<string, string | number>) => string;

type NodeState = "idle" | "running" | "ok" | "error" | "paused" | "skipped";

const STATE_LABEL: Record<NodeState, string> = {
  idle: "Idle",
  running: "Running",
  ok: "Done",
  error: "Failed",
  paused: "Waiting",
  skipped: "Skipped",
};

const REPLAY_LABEL: Record<"ahead" | "unreached", string> = { ahead: "Upcoming", unreached: "Not run" };

const STATE_ICON: Record<NodeState, LucideIcon> = {
  idle: Circle,
  running: Loader2,
  ok: Check,
  error: X,
  paused: Pause,
  skipped: Minus,
};

// Below this zoom the card collapses to an icon+title pill. The selector
// below returns a boolean, so nodes re-render only when crossing it.
const COMPACT_ZOOM = 0.5;
const selectCompact = (s: { transform: [number, number, number] }) => s.transform[2] < COMPACT_ZOOM;

function resolveState(trace: WorkflowNodeRunTrace | null | undefined, isRunning: boolean): NodeState {
  if (trace) {
    switch (trace.status) {
      case "OK":
      case "PINNED": return "ok"; // success-like; the card still says "Pinned" (see pinnedRun)
      case "ERROR": return "error";
      case "SKIPPED": return "skipped";
      case "AWAITING_APPROVAL":
      case "AWAITING_INPUT":
      case "AWAITING_WAIT":
      case "PAUSED": return "paused";
    }
  }
  return isRunning ? "running" : "idle";
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
}

function formatTokens(n: number) {
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k tok` : `${n} tok`;
}

const USD = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const MIN_SHOWN_USD = 0.01;

function formatCost(usd: number) {
  return usd > 0 && usd < MIN_SHOWN_USD ? `<${USD.format(MIN_SHOWN_USD)}` : USD.format(usd);
}

function finiteNumber(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

// Ports at fixed positions never change; hoisted so render allocates nothing.
const TRUE_PORT_STYLE: CSSProperties = { top: "38%" };
const FALSE_PORT_STYLE: CSSProperties = { top: "68%" };

/**
 * Custom @xyflow/react node renderer shared by every node type. `data.trace`
 * (merged in client-side by WorkflowCanvas, never persisted in the saved
 * graph) drives the run overlay: state chip, duration and, for LLM nodes,
 * tokens + cost when the trace carries them.
 *
 * Typed inline rather than via @xyflow/react's NodeProps generic: React Flow
 * passes many more props than these four, and NodeProps' data constraint
 * (Record<string, unknown>) would force re-narrowing every field.
 */
function WorkflowNode({ id, data, selected, type }: { id: string; data: WorkflowCanvasNodeData; selected?: boolean; type: string }) {
  const t = useTranslations("workflowNode");
  const tHttpNode = useTranslations("httpNode");
  const tData = useTranslations("workflowNodesData");
  const tAi = useTranslations("workflowNodesAi");
  const tForms = useTranslations("workflowForms");
  const tPin = useTranslations("workflowCanvas");
  const tNodeDefs = useNodeDefTranslations();
  const compact = useStore(selectCompact);
  const def = nodeDef(type);
  const defLabel = tNodeDefs(nodeLabelKey(def.type));
  const Icon = def.icon;
  const trace = data.trace;
  // "Currently executing" (the run frontier computed in WorkflowCanvas) is
  // distinct from a post-hoc trace status, hence only when no trace exists.
  const isRunning = !!data.running && !trace;
  const state = resolveState(trace, isRunning);
  // This run answered for the node from its pin instead of executing it.
  const pinnedRun = trace?.status === "PINNED";
  const StateIcon = pinnedRun ? Pin : STATE_ICON[state];
  const isLlm = type === "agent" || type === "supervisor";

  // React Flow anchors edges to each handle's measured rect, so re-measure
  // when the handle set changes (supervisor routes / voice ports) or when
  // semantic zoom changes the node's geometry.
  const updateNodeInternals = useUpdateNodeInternals();
  const voicePorts = type === "voice_menu" ? voiceMenuPorts(data.config, t) : [];
  // Switch: one port per usable case + the fixed default (dataNodes.switchPorts); the id is the edge sourceHandle.
  const switchPortList: SwitchPort[] = type === "switch" ? switchPorts(data.config, tData) : [];
  const switchPortsKey = switchPortList.map((p) => p.id).join("|");
  const classifierPortList = type === "question_classifier" ? classifierPorts(data.config) : [];
  const routeCount = type === "supervisor" ? (data.config?.routes || []).length : type === "question_classifier" ? classifierPortList.length : voicePorts.length;
  useEffect(() => {
    updateNodeInternals(id);
  }, [id, type, routeCount, switchPortsKey, compact, updateNodeInternals]);

  const title = data.label && data.label !== defLabel ? data.label : defLabel;
  const durationMs = finiteNumber(trace?.durationMs);
  const tokens = isLlm ? finiteNumber(trace?.tokens) : undefined;
  const costUsd = isLlm ? finiteNumber(trace?.costUsd) : undefined;
  // Replay marks override "Idle" so an unvisited step is named, not just faded.
  const stateText = pinnedRun
    ? tPin("pinStatePinned")
    : state === "idle" && data.replay
      ? REPLAY_LABEL[data.replay]
      : STATE_LABEL[state];
  const lens = data.lens;
  const visitCount = data.visitCount && data.visitCount > 1 ? data.visitCount : 0;
  const live = isLlm ? data.live : undefined;
  const openLive = useContext(NodeLiveOpenContext);
  const onOpenLive = useCallback(() => openLive?.(id), [openLive, id]);
  const evalStats = data.evalStats;

  return (
    <div
      className={lens?.className ? `wfx-node ${lens.className}` : "wfx-node"}
      data-state={state}
      data-selected={selected ? "true" : undefined}
      data-tour={data.tourFocus ? "true" : undefined}
      data-compact={compact ? "true" : undefined}
      data-replay={state === "idle" ? data.replay : undefined}
      data-inspected={data.inspected ? "true" : undefined}
      data-invalid={data.invalid ? "true" : undefined}
      style={{ "--wfx-node-color": def.color, ...(lens?.className ? lensCssVars(lens) : null) } as CSSProperties}
      title={lens?.description ? `${title} - ${lens.description}` : title}
    >
      {lens?.badge && (
        <span className="wfx-lens-badge" aria-label={lens.description ?? lens.badge}>
          {lens.badge}
        </span>
      )}
      {type !== "trigger" && <Handle type="target" position={Position.Left} className="wfx-port wfx-port-flow" />}

      <div className="wfx-head">
        <div className="wfx-icon" aria-hidden="true">
          <Icon size={compact ? 15 : 13} color="currentColor" strokeWidth={2.25} />
        </div>
        <div className="wfx-titles">
          <div className="wfx-title">{title}</div>
          {!compact && <div className="wfx-kind">{defLabel}</div>}
        </div>
        {data.pin && <PinBadge pin={data.pin} size={compact ? 14 : 12} />}
        {!compact && (
          <span className="wfx-glyph" data-state={state} role="img" aria-label={stateText}>
            <StateIcon size={14} strokeWidth={2.5} />
          </span>
        )}
      </div>

      {!compact && (
        <>
          <div className="wfx-body">
            {isAiNodeType(type)
              ? aiNodeSummary(type, data.config, tAi)
              : summarizeConfig(type, data.config, t, tHttpNode, tData, tForms)}
          </div>
          <div className="wfx-foot">
            <span className="wfx-chip" data-state={state} data-pinned={pinnedRun ? "true" : undefined}>
              <StateIcon size={10} strokeWidth={2.5} aria-hidden="true" />
              {stateText}
            </span>
            {visitCount > 0 && (
              <span className="wfx-chip wfx-chip-visits" aria-label={`${visitCount} visits`} title={`Ran ${visitCount} times in this run`}>
                ×{visitCount}
              </span>
            )}
            {durationMs !== undefined && <span className="wfx-chip">{formatDuration(durationMs)}</span>}
            {tokens !== undefined && <span className="wfx-chip">{formatTokens(tokens)}</span>}
            {costUsd !== undefined && <span className="wfx-chip">{formatCost(costUsd)}</span>}
            {evalStats && (
              <span style={{ display: "inline-flex", minWidth: 0, marginLeft: "auto" }}>
                <EvalBadge stats={evalStats} compact />
              </span>
            )}
          </div>
        </>
      )}

      {live && (
        <NodeLivePanel
          text={live.text}
          streaming={live.streaming}
          capped={live.capped}
          toolCalls={live.toolCalls}
          compact={compact}
          onOpen={openLive ? onOpenLive : undefined}
        />
      )}

      {type === "condition" ? (
        <>
          <Handle type="source" position={Position.Right} id="true" className="wfx-port wfx-port-true" style={TRUE_PORT_STYLE} title={t("trueBranchTitle")} />
          <Handle type="source" position={Position.Right} id="false" className="wfx-port wfx-port-false" style={FALSE_PORT_STYLE} title={t("falseBranchTitle")} />
        </>
      ) : type === "supervisor" ? (
        // One output port per config-declared route; the Handle id is the
        // route's own `handle`, which is the edge's persisted sourceHandle.
        <>
          {(data.config?.routes || []).map((route, i, arr) => (
            <Handle
              key={route.handle}
              type="source"
              position={Position.Right}
              id={route.handle}
              className="wfx-port wfx-port-route"
              style={{ top: `${arr.length <= 1 ? 50 : 15 + i * (70 / (arr.length - 1))}%` }}
              title={route.label || route.handle}
            />
          ))}
        </>
      ) : type === "switch" ? (
        // One port per case (id = case name = sourceHandle, matched case-insensitively by core's nextNodeId, exactly
        // like a supervisor route handle) plus the fixed "default" port for the no-match leg.
        <>
          {switchPortList.map((port, i, arr) => (
            <Handle
              key={port.id}
              type="source"
              position={Position.Right}
              id={port.id}
              className="wfx-port wfx-port-route"
              style={{ top: `${arr.length <= 1 ? 50 : 12 + i * (76 / (arr.length - 1))}%` }}
              title={port.title}
            />
          ))}
        </>
      ) : type === "question_classifier" ? (
        // One port per usable class (see classifierPorts for id/dedupe rules); id = the class handle = the edge's
        // persisted sourceHandle, which the server routes on exactly as for Condition / Supervisor.
        <>
          {classifierPortList.map((port, i, arr) => (
            <Handle
              key={port.id}
              type="source"
              position={Position.Right}
              id={port.id}
              className="wfx-port wfx-port-route"
              style={{ top: `${arr.length <= 1 ? 50 : 12 + i * (76 / (arr.length - 1))}%` }}
              title={port.title}
            />
          ))}
        </>
      ) : type === "voice_menu" ? (
        // One port per configured option plus the implicit no_match/timeout
        // pair (see voiceMenuPorts for id/dedupe rules); id = sourceHandle.
        <>
          {voicePorts.map((port, i, arr) => (
            <Handle
              key={port.id}
              type="source"
              position={Position.Right}
              id={port.id}
              className="wfx-port wfx-port-route"
              style={{ top: `${arr.length <= 1 ? 50 : 12 + i * (76 / (arr.length - 1))}%` }}
              title={port.title}
            />
          ))}
        </>
      ) : (
        <Handle type="source" position={Position.Right} className="wfx-port wfx-port-flow" />
      )}

      {/* Per-node error routing: an edge off this port persists
       * sourceHandle === "error", which WorkflowExecutionEngine#errorEdgeTarget
       * matches once a node's retries are exhausted. */}
      {type !== "trigger" && (
        <Handle type="source" position={Position.Bottom} id="error" className="wfx-port wfx-port-error" title={t("errorPortTitle")} />
      )}
    </div>
  );
}

export default memo(WorkflowNode);

function summarizeConfig(
  type: string,
  config: WorkflowNodeConfig | undefined,
  t: Translator,
  tHttpNode: Translator,
  tData: Translator,
  tForms: Translator
) {
  if (!config) return t("notConfigured");
  switch (type) {
    case "http":
      // HTTP Request subtitle strings live in the "httpNode" namespace (the
      // feature's own self-contained en.json block), hence the second translator.
      return config.url
        ? tHttpNode("summary", { method: (config.method || "GET").toUpperCase(), url: config.url.replace(/^https?:\/\//, "").slice(0, 28) })
        : tHttpNode("summaryNoUrl");
    case "agent":
      return config.agentId ? t("agentIdPrefix", { id: config.agentId.slice(0, 6) }) : t("noAgentSelected");
    case "knowledge":
      return config.collectionId ? t("collectionIdPrefix", { id: config.collectionId.slice(0, 6) }) : t("searchesEveryCollection");
    case "condition":
      return config.contains ? t("containsText", { text: config.contains }) : t("noConditionSet");
    case "output":
      return config.channel === "event" ? t("publishesEvent") : t("logsResult");
    case "trigger":
      // Multi-trigger graphs - each Trigger node now carries its own bound
      // channel (set in NodeConfigDrawer.tsx, defaults to WEBHOOK/null - see
      // nodeDefs.ts's defaultConfig comment), so this subtitle doubles as
      // the "small read-only badge on each trigger-type node showing its
      // bound channel" this feature asked for, with no separate fetch or
      // node-renderer change needed - it's the same real config data every
      // other case below already reads.
      if (isFormTrigger(config)) {
        // Hosted-form trigger (wfcontrol): the subtitle says so and counts the form's fields.
        return tForms("triggerFormSummary", { count: readFormDef(config.form).fields.length });
      }
      return config.channel && config.channel !== "WEBHOOK"
        ? t("triggerChannelPrefix", { channel: TRIGGER_CHANNEL_LABELS[config.channel] || config.channel })
        : t("manualOrApiTriggered");
    case "wait": {
      // Wait node subtitle (wfcontrol): what it waits for, from the config alone.
      const w = waitSummary(config as Record<string, unknown>);
      if (w.kind === "duration") return tForms("waitSummaryDuration", { duration: w.duration });
      if (w.kind === "until") return tForms("waitSummaryUntil", { time: w.time });
      if (w.kind === "webhook") return tForms("waitSummaryWebhook");
      return tForms("waitSummaryUnset");
    }
    case "approval":
      return t("pausesForSignOff");
    case "supervisor":
      return config.agentId
        ? t("supervisorRouteCount", { count: (config.routes || []).length })
        : t("noAgentSelected");
    case "data_table": {
      // "<operation> · <table>" (workflowNode dataTable_* keys); a nudge while no table is chosen.
      const sub = dataTableSubtitle(config);
      return t(sub.key, sub.values);
    }
    case "crm_lead":
      return config.action === "stage" ? t("crmLeadSummaryStage", { stage: config.stage || "?" }) : config.action === "activity" ? t("crmLeadSummaryActivity") : t("crmLeadSummaryUpsert");
    case "voice_menu":
      // Subtitle counts the configured options (the fixed no_match/timeout
      // ports aren't options an author adds) or nudges when there's no prompt
      // yet - the prompt is the one field validate() requires.
      return config.prompt?.trim()
        ? t("voiceMenuOptionCount", { count: (config.branches || []).length })
        : t("voiceMenuNoPrompt");
    case "bulk_email": {
      // Subtitle nudges toward whatever validate() would reject first
      // (subject, body, then the recipient source), else names the source.
      const missing = bulkEmailMissing(config);
      if (missing === "subject") return t("bulkEmailNoSubject");
      if (missing === "body") return t("bulkEmailNoBody");
      if (missing === "group" || missing === "static") return t("bulkEmailNoRecipients");
      return t(`bulkEmailSource_${config.recipientsSource || "items"}`);
    }
    case "parallel":
      return t("parallelFanOutHint");
    case "device_control":
      return config.commandType ? t("deviceCommandPrefix", { command: config.commandType }) : t("noDeviceCommandSet");
    case "tool":
      return config.toolName ? t("toolNamePrefix", { name: config.toolName }) : t("noToolSelected");
    case "code":
      return config.operation ? t("transformOperationPrefix", { operation: config.operation }) : t("noTransformSelected");
    case "loop":
      return config.itemsField ? t("loopOverField", { field: config.itemsField }) : t("loopOverWholeInput");
    case "fan_out":
      // Same itemsField contract as Loop (deliberately - see nodeDefs.ts's
      // fan_out entry), just concurrent, so the subtitle mirrors Loop's shape.
      return config.itemsField ? t("fanOutOverField", { field: config.itemsField }) : t("fanOutOverWholeInput");
    case "human_input":
      // Subtitle shows the collected-field count (or the pure-pause case) -
      // the field list itself is the drawer's job to edit.
      return (config.fields || []).length
        ? t("humanInputFieldCount", { count: (config.fields || []).length })
        : t("humanInputPauseOnly");
    case "sub_flow": {
      // `body` is string|graph on the shared config type (see nodeDefs.ts)
      // - only the object shape can ever arrive on a sub_flow node.
      const bodyGraph = typeof config.body === "object" && config.body !== null ? config.body : null;
      const bodyNodeCount = (bodyGraph?.nodes || []).length;
      return bodyNodeCount
        ? t("subFlowNodeCount", { count: bodyNodeCount })
        : t("subFlowEmptyBody");
    }
    case "workflow":
      // Only the target workflow's id is available on this node's own config
      // (see WorkflowNodeExecutor#executeWorkflowTriggerNode) - resolving it
      // to a real name would need the full workflow list threaded down into
      // this component, which nothing else here needs today, so this stays a
      // truncated id rather than over-fetching for one subtitle line.
      return config.targetWorkflowId
        ? t("triggersWorkflowPrefix", { id: config.targetWorkflowId.slice(0, 6) })
        : t("noTargetWorkflowSelected");
    default:
      // The eight data nodes (set, filter, ...) summarise themselves from their own config (dataNodes.ts).
      return isDataNodeType(type) ? summarizeDataNode(type, config, tData) : t("notYetSupported");
  }
}
