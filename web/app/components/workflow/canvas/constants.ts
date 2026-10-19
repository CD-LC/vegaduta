import type { AwaitingStatus } from "../ApprovalPauseCard";
import type { WorkflowNodeRunTrace } from "../WorkflowNode";
import type { LiveNodeBuffer } from "../liveRunStream";
import type { RawTraceEntry } from "../runModel";
import { switchPrimaryHandle } from "../dataNodes";
import { classifierPorts } from "../aiNodes";

export const NO_RUNNING: ReadonlySet<string> = new Set();
export const NODE_HEIGHT_GUESS = 96;
export const PLACE_GAP_X = 120;
export const PLACE_GAP_Y = 40;

// Live stream module constants, so an idle stream never hands the node memo a
// "new" empty object.
export const NO_LIVE_NODES: Record<string, LiveNodeBuffer> = {};
export const NO_ENTRIES: RawTraceEntry[] = [];
export const AWAITING_STATUSES: ReadonlySet<string> = new Set<AwaitingStatus>([
  "AWAITING_APPROVAL",
  "AWAITING_INPUT",
  "PAUSED",
]);
// A node the stream says is parked but whose trace entry has not landed yet.
export const PARKED_TRACE: Record<AwaitingStatus, WorkflowNodeRunTrace> = {
  AWAITING_APPROVAL: { status: "AWAITING_APPROVAL" },
  AWAITING_INPUT: { status: "AWAITING_INPUT" },
  PAUSED: { status: "PAUSED" },
};

export function toAwaitingStatus(v: unknown): AwaitingStatus | null {
  const s = typeof v === "string" ? v.toUpperCase() : "";
  return AWAITING_STATUSES.has(s) ? (s as AwaitingStatus) : null;
}

// The handle a freshly inserted node should expose as its primary output, so
// the re-linked edge attaches to a port that actually exists on that node.
export function primarySourceHandle(type: string, config: any): string | undefined {
  if (type === "condition") return "true";
  if (type === "supervisor") return config?.routes?.[0]?.handle;
  if (type === "switch") return switchPrimaryHandle(config);
  if (type === "question_classifier") return classifierPorts(config)[0]?.id;
  return undefined;
}
