// Agent Lab - turns one playground exchange (the user message, the turn's
// step events and the reply) into a left-to-right staged graph. Pure: no
// React, no i18n, no clock - the same input always gives the same output, so
// the canvas can rebuild it on every streamed step.
//
// What core emits limits what can be drawn: there is one event per tool call
// and per plan / guardrail / handoff, and nothing per model call, so a node is
// a STEP, not an LLM invocation.
import type { ActivityStep } from "../AgentActivityTrace";
import { clampText } from "./labTurns";
import type { LabTurn } from "./types";

export type RunGraphNodeKind = "user" | "plan" | "tool" | "guardrail" | "handoff" | "event" | "reply";
export type RunGraphNodeStatus = "ok" | "running" | "error" | "blocked" | "streaming" | "interrupted";

export interface RunGraphNode {
  id: string;
  kind: RunGraphNodeKind;
  status: RunGraphNodeStatus;
  /** One clamped line taken from the step itself. May be empty; the canvas
   * always shows the (translated) kind next to it. */
  title: string;
  detail?: string;
  tool?: string;
  args?: string;
  preview?: string;
  message?: string;
  ms?: number;
  /** A handoff event arrived while this tool call was open. */
  handoff?: boolean;
  onDevice?: boolean;
  stage: number;
  x: number;
  y: number;
}

export interface RunGraphEdge {
  id: string;
  source: string;
  target: string;
  /** The target is still working (running or streaming). */
  live: boolean;
}

export interface RunGraph {
  nodes: RunGraphNode[];
  edges: RunGraphEdge[];
  width: number;
  height: number;
}

export interface RunGraphInput {
  userMessage: string;
  steps: readonly ActivityStep[];
  reply: string;
  pending: boolean;
  onDevice: boolean;
  totalMs?: number;
}

export const RUN_GRAPH_NODE_WIDTH = 200;
export const RUN_GRAPH_NODE_HEIGHT = 64;
const COLUMN_GAP = 72;
const ROW_GAP = 20;
const TITLE_MAX = 80;
/** Appended by core when a reply was cut short (AgentChatService.INTERRUPTED_REPLY_MARKER). */
const INTERRUPTED_MARKER = "[reply interrupted]";

function oneLine(text: string | undefined): string {
  return clampText((text ?? "").replace(/\s+/g, " ").trim(), TITLE_MAX);
}

type Draft = Omit<RunGraphNode, "stage" | "x" | "y">;

/**
 * Node ids are POSITIONAL: "u" (user), "r" (reply) and "s<index in steps>".
 * ActivityStep.id is never a node id - only tool events carry a server id,
 * every other event gets a positional fallback that collides with the tool
 * ids (run_started is 1, and so is the first tool call).
 */
export function buildRunGraph(input: RunGraphInput): RunGraph {
  const { steps, pending, onDevice } = input;
  const stages: Draft[][] = [
    [
      {
        id: "u",
        kind: "user",
        status: "ok",
        title: oneLine(input.userMessage),
        message: input.userMessage,
        ...(onDevice ? { onDevice: true } : {}),
      },
    ],
  ];
  // Tool nodes still waiting for their closing event, oldest first, each
  // with the step that opened it (the closing event is matched on that
  // step's id AND origin, the rule buildTraceRows uses).
  const open: { node: Draft; step: ActivityStep }[] = [];
  let openToolStage: Draft[] | null = null;
  let finishedMs: number | undefined;

  const stepOnDevice = (step: ActivityStep) => (step.origin === "on-device" ? { onDevice: true } : {});
  const pushStage = (node: Draft) => {
    stages.push([node]);
    openToolStage = null;
  };

  steps.forEach((step, index) => {
    const id = `s${index}`;
    switch (step.type) {
      // Liveness beats are filtered before they reach `steps`; second gate.
      case "heartbeat":
      case "run_started":
      // "memory" says what was recalled before the run began; it is context,
      // not a step of the run, so it draws no node.
      case "memory":
        return;
      case "run_finished":
        if (typeof step.ms === "number") finishedMs = step.ms;
        return;
      case "plan":
        pushStage({ id, kind: "plan", status: "ok", title: oneLine(step.message), message: step.message, ...stepOnDevice(step) });
        return;
      case "tool_call": {
        const node: Draft = {
          id,
          kind: "tool",
          status: "running",
          title: step.tool || "tool",
          detail: step.args ? oneLine(step.args) : undefined,
          tool: step.tool,
          args: step.args,
          ...stepOnDevice(step),
        };
        // Parallel calls share a column: join the current stage while it is
        // a tool stage with a call still open, otherwise start a new one.
        const stage = openToolStage;
        if (stage && stages[stages.length - 1] === stage && open.some((o) => stage.includes(o.node))) {
          stage.push(node);
        } else {
          const next = [node];
          stages.push(next);
          openToolStage = next;
        }
        open.push({ node, step });
        return;
      }
      case "tool_result":
      case "error": {
        const at = open.findIndex((o) => o.step.id === step.id && o.step.origin === step.origin);
        if (at < 0) {
          // No open call to close: keep it visible rather than dropping a failure.
          pushStage({
            id,
            kind: "event",
            status: step.type === "error" ? "error" : "ok",
            title: step.type,
            detail: oneLine(step.message || step.preview) || undefined,
            tool: step.tool,
            preview: step.preview,
            message: step.message,
            ms: step.ms,
            ...stepOnDevice(step),
          });
          return;
        }
        const { node } = open[at];
        open.splice(at, 1);
        if (step.type === "error") {
          node.status = "error";
          node.message = step.message;
        } else {
          node.status = "ok";
          node.preview = step.preview;
        }
        if (typeof step.ms === "number") node.ms = step.ms;
        return;
      }
      case "guardrail":
        pushStage({
          id,
          kind: "guardrail",
          status: "blocked",
          title: oneLine(step.message),
          detail: step.tool,
          tool: step.tool,
          message: step.message,
          ...stepOnDevice(step),
        });
        return;
      case "handoff":
        if (open.length > 0) {
          // core drops the agent names (normalizeServerStep), so a handoff
          // inside a tool call is a badge on the most recently opened call.
          open[open.length - 1].node.handoff = true;
          return;
        }
        pushStage({ id, kind: "handoff", status: "ok", title: oneLine(step.message), message: step.message, ...stepOnDevice(step) });
        return;
      default:
        // Open vocabulary (swarm, and whatever a newer core adds).
        pushStage({
          id,
          kind: "event",
          status: "ok",
          title: step.type,
          detail: oneLine(step.message || step.preview) || undefined,
          tool: step.tool,
          args: step.args,
          preview: step.preview,
          message: step.message,
          ms: step.ms,
          ...stepOnDevice(step),
        });
    }
  });

  // A finished turn cannot still be running a tool: the call never closed.
  if (!pending) for (const { node } of open) node.status = "interrupted";

  if (pending || input.reply !== "") {
    const interrupted = input.reply.trimEnd().endsWith(INTERRUPTED_MARKER);
    const ms = finishedMs ?? input.totalMs;
    stages.push([
      {
        id: "r",
        kind: "reply",
        status: pending ? "streaming" : interrupted ? "interrupted" : "ok",
        title: oneLine(input.reply),
        message: input.reply,
        ...(typeof ms === "number" ? { ms } : {}),
        ...(onDevice ? { onDevice: true } : {}),
      },
    ]);
  }

  // Left to right, one column per stage, each column centred vertically.
  const stageHeight = (count: number) => count * RUN_GRAPH_NODE_HEIGHT + (count - 1) * ROW_GAP;
  const height = Math.max(...stages.map((stage) => stageHeight(stage.length)));
  const width = stages.length * RUN_GRAPH_NODE_WIDTH + (stages.length - 1) * COLUMN_GAP;
  const nodes: RunGraphNode[] = [];
  const edges: RunGraphEdge[] = [];
  stages.forEach((stage, stageIndex) => {
    const top = (height - stageHeight(stage.length)) / 2;
    stage.forEach((draft, row) => {
      nodes.push({
        ...draft,
        stage: stageIndex,
        x: stageIndex * (RUN_GRAPH_NODE_WIDTH + COLUMN_GAP),
        y: top + row * (RUN_GRAPH_NODE_HEIGHT + ROW_GAP),
      });
    });
    if (stageIndex === 0) return;
    for (const source of stages[stageIndex - 1]) {
      for (const target of stage) {
        edges.push({
          id: `e:${source.id}:${target.id}`,
          source: source.id,
          target: target.id,
          live: target.status === "running" || target.status === "streaming",
        });
      }
    }
  });
  return { nodes, edges, width, height };
}

/** The graph of one transcript exchange; either side may be missing. */
export function graphOfExchange(user: LabTurn | null, assistant: LabTurn | null): RunGraph {
  return buildRunGraph({
    userMessage: user?.content ?? "",
    steps: assistant?.steps ?? [],
    reply: assistant?.content ?? "",
    pending: assistant?.pending === true,
    onDevice: assistant?.onDevice === true,
    totalMs: assistant?.metrics?.elapsedMs,
  });
}

/** Names of the tools a graph called, in first-call order, without repeats. */
export function toolNamesOf(graph: RunGraph): string[] {
  const names: string[] = [];
  for (const node of graph.nodes) {
    if (node.kind === "tool" && !names.includes(node.title)) names.push(node.title);
  }
  return names;
}
