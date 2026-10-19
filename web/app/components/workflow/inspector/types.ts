// Types for the step inspector card (see ../RunStepInspector.tsx).

export interface InspectorVisit {
  nodeId: string;
  nodeType?: string | null;
  label?: string | null;
  status?: string | null;
  summary?: string | null;
  logLines?: readonly string[] | null;
  startMs?: number | null;
  endMs?: number | null;
  durationMs?: number | null;
  visitIndex: number;
  // Future core fields - read defensively, render only when present.
  inputJson?: unknown;
  outputJson?: unknown;
  inputTruncated?: boolean | null;
  outputTruncated?: boolean | null;
  tokensIn?: number | null;
  tokensOut?: number | null;
  costUsd?: number | null;
  /** NodeTraceEntry.toolCalls - input/output are plain text (JSON when it parses). */
  toolCalls?: readonly InspectorToolCall[] | null;
}

export interface InspectorToolCall {
  tool: string;
  input?: string | null;
  output?: string | null;
  error?: string | null;
  startedAt?: string | null;
  durationMs?: number | null;
}

/** Screen rect of the selected node, in px relative to the card's offsetParent. */
export interface InspectorAnchor {
  x: number;
  y: number;
  width?: number;
  height?: number;
}

export interface RunStepInspectorProps {
  visit: InspectorVisit;
  visitCount: number;
  onPrevVisit: () => void;
  onNextVisit: () => void;
  onClose: () => void;
  onOpenFullRun?: () => void;
  /** Omit to dock top-right of the offsetParent. Ignored below 640px (bottom sheet). */
  anchor?: InspectorAnchor | null;
  /** Whole-run window, enables the "where in the run" flight strip. */
  runSpan?: { startMs: number; endMs?: number | null } | null;
  /** Run this visit belongs to; recorded as the source of a pin made from its output. */
  pinRunId?: string | null;
  /** With pinRunId, enables the "Sent" tab (the exact model request of agent nodes). */
  workflowId?: string | null;
}

export type TabKey = "summary" | "logs" | "tools" | "data" | "cost" | "sent";
export type LogLevel = "error" | "warn" | "info";
export type LevelFilter = "all" | "error" | "warn";
export type StatusKey = "ok" | "pinned" | "error" | "skipped" | "approval" | "cancelled" | "paused" | "running" | "unknown";
