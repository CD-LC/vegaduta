// Pure view model of the run flight recorder: one resolved bar per visit,
// grouped into swim lanes, plus the clock/tick/relative-time formatting the
// dock renders. No React.
import { criticalPathDetail, keyframes } from "./runModel";
import type { AwaitReason, RunEdge, RunModel, RunStatus, TraceVisit, VisitTiming } from "./runModel";

export type RecorderSpeed = 0.5 | 1 | 2 | 4 | "instant";
export const RECORDER_SPEEDS: readonly RecorderSpeed[] = [0.5, 1, 2, 4, "instant"];

export type Tone = "ok" | "error" | "paused" | "running" | "skipped" | "cancelled" | "unknown";

/** Internal, fully-resolved view of one visit. */
export interface Bar {
  key: string;
  nodeId: string;
  visitIndex: number;
  label: string;
  visitCount: number;
  tone: Tone;
  awaiting: AwaitReason | null;
  timing: VisitTiming;
  start: number;
  end: number;
  /** null = unknown (estimated placement), never guessed as 0. */
  durationMs: number | null;
  summary: string;
  tokensIn: number | null;
  tokensOut: number | null;
  costUsd: number | null;
  critical: boolean;
}

export interface Lane {
  nodeId: string;
  label: string;
  bars: Bar[];
}

export interface View {
  total: number;
  lanes: Lane[];
  bars: Bar[];
  boundaries: number[];
  tone: Tone;
  runAwaiting: AwaitReason | null;
  rawStatus: string;
  startedAtMs: number | null;
  live: boolean;
  critCount: number;
}

/* ------------------------------------------------------------ model -> view */

function toneOfVisit(v: TraceVisit): Tone {
  return v.status;
}

function toneOfRun(s: RunStatus): Tone {
  switch (s) {
    case "completed":
      return "ok";
    case "failed":
      return "error";
    case "cancelled":
      return "cancelled";
    case "paused":
    case "awaiting":
      return "paused";
    case "running":
      return "running";
    default:
      return "unknown";
  }
}

const TONE_TEXT: Record<Tone, string> = {
  ok: "Succeeded",
  error: "Failed",
  paused: "Paused",
  running: "Running",
  skipped: "Skipped",
  cancelled: "Cancelled",
  unknown: "Unknown",
};

const AWAIT_TEXT: Record<AwaitReason, string> = {
  approval: "Awaiting approval",
  input: "Awaiting input",
  voice: "Awaiting caller",
  operator: "Paused by operator",
  other: "Waiting",
};

export function statusText(tone: Tone, awaiting: AwaitReason | null = null): string {
  return tone === "paused" && awaiting ? AWAIT_TEXT[awaiting] : TONE_TEXT[tone];
}

export function buildView(model: RunModel, nodeLabel: (id: string) => string, edges: readonly RunEdge[]): View {
  const { t0, totalMs } = model;
  const total = Math.max(1, totalMs);
  const crit = new Set(criticalPathDetail(model, edges).visits.map((v) => v.key));

  const bars: Bar[] = model.visits.map((v) => {
    const start = Math.max(0, v.startMs - t0);
    // An open visit (still running) extends to the live edge of the timeline.
    const end = v.endMs == null ? total : Math.max(start, v.endMs - t0);
    return {
      key: v.key,
      nodeId: v.nodeId,
      visitIndex: v.visitIndex,
      visitCount: v.visitCount,
      label: (nodeLabel(v.nodeId) || "").trim() || v.label.trim() || v.nodeType || v.nodeId,
      tone: toneOfVisit(v),
      awaiting: v.awaiting,
      timing: v.timing,
      start,
      end,
      durationMs: v.durationMs ?? (v.endMs == null ? end - start : null),
      summary: v.summary,
      tokensIn: v.tokensIn,
      tokensOut: v.tokensOut,
      costUsd: v.costUsd,
      critical: crit.has(v.key),
    };
  });

  const laneMap = new Map<string, Lane>();
  for (const b of bars) {
    let lane = laneMap.get(b.nodeId);
    if (!lane) {
      lane = { nodeId: b.nodeId, label: b.label, bars: [] };
      laneMap.set(b.nodeId, lane);
    }
    lane.bars.push(b);
  }

  const pausedVisit = [...model.visits].reverse().find((v) => v.status === "paused");
  return {
    total,
    lanes: [...laneMap.values()],
    bars,
    boundaries: keyframes(model),
    tone: toneOfRun(model.run.status),
    runAwaiting: model.run.status === "awaiting" ? (pausedVisit?.awaiting ?? "other") : null,
    rawStatus: model.run.rawStatus,
    startedAtMs: model.run.startMs ?? (model.visits.length ? t0 : null),
    live: model.live,
    critCount: crit.size,
  };
}

/* -------------------------------------------------------------- formatting */

/** Fixed-width clock for the readout: mm:ss.t */
export function formatClock(ms: number): string {
  const safe = Math.max(0, ms);
  const m = Math.floor(safe / 60_000);
  const s = Math.floor((safe % 60_000) / 1000);
  const t = Math.floor((safe % 1000) / 100);
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}.${t}`;
}

export function formatTick(ms: number, step: number): string {
  if (step < 1000) return ms === 0 ? "0" : `${ms}ms`;
  if (step < 60_000) return `${+(ms / 1000).toFixed(step < 10_000 && step % 1000 ? 1 : 0)}s`;
  return `${+(ms / 60_000).toFixed(step % 60_000 ? 1 : 0)}m`;
}

const rtf = typeof Intl !== "undefined" ? new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }) : null;
export function formatRelative(epochMs: number, now: number): string {
  const diff = (epochMs - now) / 1000;
  const abs = Math.abs(diff);
  if (!rtf) return new Date(epochMs).toLocaleString();
  if (abs < 45) return rtf.format(Math.round(diff), "second");
  if (abs < 2700) return rtf.format(Math.round(diff / 60), "minute");
  if (abs < 64_800) return rtf.format(Math.round(diff / 3600), "hour");
  return rtf.format(Math.round(diff / 86_400), "day");
}

export const intFmt = new Intl.NumberFormat();

const TICK_STEPS = [
  1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000,
  900_000, 1_800_000, 3_600_000,
];

export function chooseTickStep(total: number, widthPx: number): number {
  const maxTicks = Math.max(2, Math.floor(widthPx / 64));
  return TICK_STEPS.find((s) => total / s <= maxTicks) ?? Math.ceil(total / maxTicks);
}

export function speedLabel(s: RecorderSpeed): string {
  return s === "instant" ? "Instant" : `${s}x`;
}

