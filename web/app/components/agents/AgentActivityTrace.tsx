"use client";

import { useState } from "react";
import {
  AlertTriangle,
  ArrowRightLeft,
  Brain,
  ChevronRight,
  CircleDot,
  Flag,
  ListChecks,
  Play,
  Shield,
} from "lucide-react";
import type { AgentStepEvent } from "../../lib/api";
import type { Tier1ToolCallEvent } from "../../lib/edge/tier1";
import { Button } from "../ui/button";
import { cn } from "../../lib/utils";
import McpAppFrame from "../McpAppFrame";
import { MCP_APPS_ENABLED, parseAppPointer, type McpAppPointer } from "../../lib/mcpApps/mcpApps";

/**
 * AG-UI-style live activity trace, shared by ChatPanel (/playground) and
 * AgentPlayground (the workbench's embedded pane).
 *
 * Extracted from ChatPanel.tsx's inline ActivityTrace (2026-08-17) so both
 * chat surfaces render one trace from one vocabulary. Two producers feed it:
 *
 *  - the SERVER side channel: api.streamChat's `steps.onEvent` hands over
 *    each `@@AGENT_STEP_EVENT_4d81ba@@` chunk parsed off the SSE stream
 *    (AgentStepEventEmitter in core/agents - tool_call/tool_result/error
 *    today, plus whatever lifecycle events core adds next: run_started,
 *    run_finished, plan, guardrail, handoff). Fed through
 *    normalizeServerStep().
 *  - the ON-DEVICE side: tierRouter's `onToolCall` fires
 *    calling/done/error while a tier-1 local model's tool round is in
 *    flight (Tier1ToolCallEvent). Fed through appendEdgeToolEvent(), which
 *    maps that phase vocabulary onto the same tool_call/tool_result/error
 *    rows and tags them origin:"on-device" so the trace says plainly which
 *    half of the turn ran where.
 *
 * Unknown event types are the rule, not the exception: core ships new
 * types before the console knows them, so anything we don't recognise
 * renders as a generic labelled row rather than crashing the turn. The
 * normalisers never throw for the same reason (same fail-open posture as
 * the backend emitter, whose javadoc spells out why a trace must never be
 * able to fail a turn that would otherwise have answered).
 *
 * Deliberately understated, as the original was: collapsed by default, one
 * muted chevron-prefixed line under the reply, the panel's existing
 * Button/border/secondary tokens and no new styling. Renders nothing at all
 * for a turn with no events, so a turn without tools looks exactly as it
 * always did.
 */

/** Event types this component renders with a dedicated icon/label. Anything
 * else is still rendered - see renderGenericRow - just without one. */
export type KnownActivityStepType =
  "tool_call" | "tool_result" | "error" | "run_started" | "run_finished" | "plan" | "thinking" | "guardrail" | "handoff" | "memory";

/** What a `memory` event says was placed in the turn's context: counts and
 * note labels only - core never sends the remembered text itself. */
export interface MemoryStepInfo {
  /** Labels of the notes that were included, e.g. "persona", "project:acme". */
  blockLabels: string[];
  /** Number of notes included (blockLabels.length when labels were sent). */
  blocks: number;
  privateFacts: number;
  sharedFacts: number;
}

/** The normalised shape both producers are reduced to. `id` pairs a
 * tool_call with whichever tool_result/error closed it (tools can run
 * concurrently server-side, so array order alone is not enough). Server ids
 * are the emitter's per-turn integers; on-device ids are "edge-N" strings so
 * the two sequences can never collide inside one turn. */
export interface ActivityStep {
  /** Open vocabulary on purpose - unknown types render generically. */
  type: KnownActivityStepType | (string & {});
  id: number | string;
  tool?: string;
  args?: string;
  preview?: string;
  message?: string;
  ms?: number;
  /** Only set on rows derived from a Tier1ToolCallEvent. */
  phase?: Tier1ToolCallEvent["phase"];
  /** Only set on server `phase` rows: which pre-token stage began (guardrails, routing, context, model). */
  stage?: string;
  origin?: "server" | "on-device";
  /** Only set on `memory` rows. */
  memory?: MemoryStepInfo;
  /** Only set on a `tool_result` whose tool declared an MCP App (core: app.mcp.apps.enabled). A handle, not the HTML. */
  app?: McpAppPointer;
}

const CLOSING_TYPES = new Set<string>(["tool_result", "error"]);

function asString(value: unknown): string | undefined {
  if (value == null) return undefined;
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return undefined;
  }
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function asCount(value: unknown): number {
  const n = asNumber(value);
  return n != null && n > 0 ? Math.floor(n) : 0;
}

function memoryInfo(raw: Record<string, unknown>): MemoryStepInfo {
  const blockLabels = Array.isArray(raw.blockLabels)
    ? raw.blockLabels.filter((l): l is string => typeof l === "string" && l.length > 0)
    : [];
  return {
    blockLabels,
    blocks: blockLabels.length || asCount(raw.blocks),
    privateFacts: asCount(raw.privateFacts),
    sharedFacts: asCount(raw.sharedFacts),
  };
}

function noteName(label: string): string {
  if (label === "persona") return "persona note";
  if (label === "user_profile") return "profile note";
  if (label === "project_notes") return "current-work note";
  if (label.startsWith("project:")) return `${label.slice("project:".length)} project note`;
  return `${label.replace(/[_-]+/g, " ")} note`;
}

/**
 * One sentence for a `memory` event, e.g. "Used your profile note and 2 saved
 * memories". Pure and never throws; an event with nothing countable still
 * reads as a plain "Used your memory".
 */
export function describeMemoryUse(info: MemoryStepInfo | null | undefined): string {
  const parts: string[] = [];
  const labels = info?.blockLabels ?? [];
  const blocks = info?.blocks ?? 0;
  if (labels.length > 0) parts.push(...labels.map(noteName));
  else if (blocks > 0) parts.push(blocks === 1 ? "1 note" : `${blocks} notes`);
  const hasNotes = parts.length > 0;
  const facts = (info?.privateFacts ?? 0) + (info?.sharedFacts ?? 0);
  if (facts > 0) parts.push(facts === 1 ? "1 saved memory" : `${facts} saved memories`);
  if (parts.length === 0) return "Used your memory";
  const list = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(", ")} and ${parts[parts.length - 1]}`;
  return `Used ${hasNotes ? "your " : ""}${list}`;
}

/**
 * Normalises one server step event. Accepts the typed AgentStepEvent
 * api.ts declares AND an arbitrary object, because the wire payload is
 * whatever core emitted - a newer core sends types this build's type union
 * does not name yet. Never throws: a payload that is not even an object
 * becomes an "unknown" row with its stringified value as the message, so a
 * garbled event shows up as one odd line instead of a blank trace.
 */
export function normalizeServerStep(
  event: AgentStepEvent | Record<string, unknown> | unknown,
  fallbackId: number
): ActivityStep {
  if (!event || typeof event !== "object") {
    return { type: "unknown", id: fallbackId, message: asString(event), origin: "server" };
  }
  const raw = event as Record<string, unknown>;
  const type = typeof raw.type === "string" && raw.type ? raw.type : "unknown";
  const id: number | string =
    typeof raw.id === "number" || (typeof raw.id === "string" && raw.id) ? (raw.id as number | string) : fallbackId;
  return {
    type,
    id,
    tool: asString(raw.tool ?? raw.name),
    args: asString(raw.args),
    preview: asString(raw.preview),
    // Lifecycle/guardrail/handoff events from core carry their human text
    // under a few plausible keys; take the first present rather than
    // hard-coding one and rendering an empty row for the others.
    message: asString(raw.message ?? raw.text ?? raw.reason ?? raw.detail ?? raw.summary),
    ms: asNumber(raw.ms ?? raw.elapsedMs),
    origin: "server",
    ...(type === "phase" ? { stage: asString(raw.phase) } : {}),
    ...(type === "memory" ? { memory: memoryInfo(raw) } : {}),
    ...(type === "tool_result" ? appField(raw.app) : {}),
  };
}

/** `{ app }` only for a valid pointer, so a step without one is key-for-key what it was before MCP Apps. */
function appField(raw: unknown): { app?: McpAppPointer } {
  const app = parseAppPointer(raw);
  return app ? { app } : {};
}

/**
 * Folds one on-device Tier1ToolCallEvent into a step list, returning a new
 * array (never mutating the input - callers hand the result straight to
 * setState). "calling" opens a new tool_call row; "done"/"error" close the
 * most recent still-open row for that tool name. Tier 1 runs its tool
 * rounds sequentially, so "most recent open row with this name" is exact,
 * but the lookup is by name rather than by position so an out-of-order
 * event (or a "done" whose "calling" was never seen) degrades to a lone
 * closing row instead of closing the wrong call.
 */
export function appendEdgeToolEvent(steps: readonly ActivityStep[], event: Tier1ToolCallEvent): ActivityStep[] {
  const name = event?.name || "tool";
  if (event?.phase === "calling") {
    const id = `edge-${steps.filter((s) => s.origin === "on-device" && s.type === "tool_call").length + 1}`;
    return [...steps, { type: "tool_call", id, tool: name, phase: "calling", origin: "on-device" }];
  }
  const closedIds = new Set(
    steps.filter((s) => s.origin === "on-device" && CLOSING_TYPES.has(s.type)).map((s) => s.id)
  );
  const open = [...steps]
    .reverse()
    .find((s) => s.origin === "on-device" && s.type === "tool_call" && s.tool === name && !closedIds.has(s.id));
  const id = open?.id ?? `edge-orphan-${steps.length + 1}`;
  const failed = event?.phase === "error";
  return [
    ...steps,
    {
      type: failed ? "error" : "tool_result",
      id,
      tool: name,
      phase: event?.phase,
      origin: "on-device",
      ...(failed ? { message: "Tool call failed" } : {}),
    },
  ];
}

/** One rendered row: either a tool_call folded with its closing event, or
 * any other event standing alone. */
type TraceRow = { kind: "tool"; call: ActivityStep; closing?: ActivityStep } | { kind: "event"; step: ActivityStep };

export function buildTraceRows(steps: readonly ActivityStep[]): TraceRow[] {
  const rows: TraceRow[] = [];
  const openCalls = new Set<number | string>();
  for (const step of steps) {
    // Heartbeats are liveness beats, not activity (2026-09-25) - they exist
    // to keep the SSE wire alive through silent phases, not to be rendered.
    // Callers already filter them out of `turnSteps`; this is the defensive
    // second gate so a future producer can't leak one into a row.
    if (step.type === "heartbeat") continue;
    // Phase events feed the live progress line (lib/turnProgress.ts), not the trace: "guardrails,
    // routing, context, model" is where the turn WAS, which is noise once it has answered.
    if (step.type === "phase") continue;
    if (step.type === "tool_call") {
      const closing = steps.find((s) => s.id === step.id && s.origin === step.origin && CLOSING_TYPES.has(s.type));
      rows.push({ kind: "tool", call: step, closing });
      openCalls.add(step.id);
      continue;
    }
    if (CLOSING_TYPES.has(step.type)) {
      // Already folded into its tool_call row above; an orphan closing
      // event (no matching call ever arrived) still gets its own row so a
      // failure is never silently dropped from the trace.
      const hasCall = steps.some((s) => s.type === "tool_call" && s.id === step.id && s.origin === step.origin);
      if (!hasCall) rows.push({ kind: "event", step });
      continue;
    }
    rows.push({ kind: "event", step });
  }
  return rows;
}

function OnDeviceBadge() {
  return (
    <span
      className="shrink-0 rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground"
      title="This tool call was proposed by the on-device model and executed through the edge tool bridge"
    >
      on-device
    </span>
  );
}

function eventIcon(type: string) {
  const cls = "size-3 shrink-0 text-muted-foreground";
  switch (type) {
    case "run_started":
      return <Play size={12} className={cls} aria-hidden="true" />;
    case "run_finished":
      return <Flag size={12} className={cls} aria-hidden="true" />;
    case "plan":
      return <ListChecks size={12} className={cls} aria-hidden="true" />;
    case "thinking":
      return <Brain size={12} className={cls} aria-hidden="true" />;
    case "guardrail":
      return <Shield size={12} className={cls} aria-hidden="true" />;
    case "handoff":
      return <ArrowRightLeft size={12} className={cls} aria-hidden="true" />;
    case "memory":
      return <Brain size={12} className={cls} aria-hidden="true" />;
    case "error":
      return <AlertTriangle size={12} className="size-3 shrink-0 text-destructive" aria-hidden="true" />;
    default:
      return <CircleDot size={12} className={cls} aria-hidden="true" />;
  }
}

function eventLabel(type: string): string {
  switch (type) {
    case "run_started":
      return "Run started";
    case "run_finished":
      return "Run finished";
    case "plan":
      return "Plan";
    case "thinking":
      return "Thinking";
    case "guardrail":
      return "Guardrail";
    case "handoff":
      return "Handoff";
    case "memory":
      return "Memory";
    case "tool_result":
      return "Tool result";
    case "error":
      return "Error";
    default:
      // Unknown type from a newer core - humanise the identifier rather
      // than hiding the event or showing raw snake_case.
      return type.replace(/[_-]+/g, " ").replace(/^\w/, (c) => c.toUpperCase());
  }
}

/**
 * Why a run was stopped, when the reason is one this console can explain.
 * Core reports stops as terse machine text: the run-budget stop is the literal
 * token "run_budget_exceeded" (RunBudget.STOP_REASON, emitted as a guardrail
 * step by RunBudgetToolCallback / DelegateToTeamToolCallback), and the
 * tool-call ceiling is ToolStepLimitExceededException's sentence ("exceeded
 * the maximum of N tool calls for a single turn"). Anything else is null and
 * renders exactly as before.
 */
export type StopReasonKind = "run_budget" | "step_limit";

export function stopReasonKind(message: string | null | undefined): StopReasonKind | null {
  if (typeof message !== "string" || !message) return null;
  if (message.includes("run_budget_exceeded")) return "run_budget";
  if (/maximum of \d+ tool calls/i.test(message) || /tool[ _-]?step[ _-]?limit/i.test(message)) return "step_limit";
  return null;
}

/** Plain-language sentence for a known stop reason, or null for anything else. */
export function stopReasonText(message: string | null | undefined): string | null {
  switch (stopReasonKind(message)) {
    case "run_budget":
      return "Stopped: this run reached its spending cap. Ask for a smaller task, or have the cap raised.";
    case "step_limit":
      return "Stopped: too many tool calls in one turn. Break the task into smaller steps and try again.";
    default:
      return null;
  }
}

/**
 * The stop reason of a whole turn, read off its guardrail/error steps - what
 * the chat surfaces use to show a notice OUTSIDE the collapsed trace.
 */
export function turnStopKind(steps: readonly ActivityStep[] | null | undefined): StopReasonKind | null {
  if (!steps) return null;
  for (const step of steps) {
    if (step.type !== "guardrail" && step.type !== "error") continue;
    const kind = stopReasonKind(step.message) ?? stopReasonKind(step.preview);
    if (kind) return kind;
  }
  return null;
}

function eventText(step: ActivityStep): string | undefined {
  if (step.type === "memory") return describeMemoryUse(step.memory);
  const text = step.message || step.preview || step.args || step.tool;
  if (step.type === "guardrail" || step.type === "error") return stopReasonText(text) ?? text;
  return text;
}

export default function AgentActivityTrace({
  steps,
  defaultOpen = false,
  className,
  appHost,
}: {
  steps: readonly ActivityStep[] | null | undefined;
  /** Start expanded - AgentPlayground passes true for a still-pending turn
   * so a slow multi-tool turn is visibly working, then the person can fold
   * it away. Read once at mount, like any uncontrolled default. */
  defaultOpen?: boolean;
  className?: string;
  /** Enables MCP App cards (NEXT_PUBLIC_MCP_APPS): the agent whose turn this is, and where a user-confirmed app
   * message goes (the composer draft). Without it - or with the flag off - no card is ever mounted. */
  appHost?: { agentId: string; onAddMessage?: (text: string) => void };
}) {
  const [open, setOpen] = useState(defaultOpen);
  if (!steps || steps.length === 0) return null;

  const rows = buildTraceRows(steps);
  if (rows.length === 0) return null;
  const running = rows.some((r) => r.kind === "tool" && !r.closing);
  // "Memory used" chip: shown beside the toggle (not only inside the folded
  // list) so a reply that leaned on something remembered says so at a glance.
  const memoryStep = steps.find((s) => s.type === "memory");
  const memorySentence = memoryStep ? describeMemoryUse(memoryStep.memory) : "";
  // The chip already says it, so the memory row (still listed when expanded)
  // is not counted as a step.
  const stepCount = rows.filter((r) => !(r.kind === "event" && r.step.type === "memory")).length || rows.length;
  // MCP Apps (flag-gated): one collapsed, labelled card per tool result that declared an app. Rendered outside the
  // folded step list so it is findable, but nothing loads or runs until the person opens it.
  const appPointers: McpAppPointer[] = [];
  if (MCP_APPS_ENABLED && appHost?.agentId) {
    for (const row of rows) {
      if (row.kind === "tool" && row.closing?.app && appPointers.length < 6) appPointers.push(row.closing.app);
    }
  }

  return (
    <div className={cn("w-full max-w-[85%]", className)}>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="h-auto gap-1 px-1.5 py-0.5 text-xs font-normal text-muted-foreground"
      >
        <ChevronRight size={12} className={cn("size-3 transition-transform", open && "rotate-90")} aria-hidden="true" />
        {stepCount === 1 ? "1 step" : `${stepCount} steps`}
        {running && (
          <span className="ml-1 inline-flex items-center gap-1">
            <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
            running
          </span>
        )}
      </Button>
      {memoryStep && (
        // Opens in a new tab so the conversation in this one is not lost.
        <a
          href="/memory"
          target="_blank"
          rel="noopener noreferrer"
          data-testid="memory-used-chip"
          title="See and edit what your assistants remember"
          aria-label={`${memorySentence} - opens Memory in a new tab`}
          className="ml-1 inline-flex max-w-full items-center gap-1 rounded-full border border-border px-2 py-0.5 align-middle text-[11px] text-muted-foreground hover:text-foreground"
        >
          <Brain size={12} className="size-3 shrink-0" aria-hidden="true" />
          <span className="truncate">{memorySentence}</span>
        </a>
      )}
      {appPointers.map((pointer) => (
        <McpAppFrame
          key={pointer.appId}
          pointer={pointer}
          agentId={appHost!.agentId}
          onAddMessage={appHost!.onAddMessage}
        />
      ))}
      {open && (
        <ol className="mt-1 space-y-1.5 rounded-lg border border-border bg-secondary/30 px-3 py-2">
          {rows.map((row, index) => {
            if (row.kind === "event") {
              const { step } = row;
              const text = eventText(step);
              const failed = step.type === "error";
              return (
                <li
                  key={`${step.origin ?? "server"}-${step.type}-${step.id}-${index}`}
                  className="min-w-0 space-y-0.5 text-xs"
                >
                  <div className="flex items-center gap-1.5">
                    {eventIcon(step.type)}
                    <span className={cn("truncate font-medium", failed ? "text-destructive" : "text-foreground")}>
                      {eventLabel(step.type)}
                      {step.tool && step.type !== "tool_result" && step.type !== "error" ? ` · ${step.tool}` : ""}
                    </span>
                    {step.ms != null && <span className="shrink-0 text-muted-foreground">{step.ms} ms</span>}
                    {step.origin === "on-device" && <OnDeviceBadge />}
                  </div>
                  {text && (
                    <p
                      className={cn(
                        // A translated stop reason is a full sentence the person
                        // needs to read - never clip it to one line.
                        stopReasonKind(step.message) ? "whitespace-normal" : "truncate",
                        "text-[11px]",
                        failed ? "text-destructive" : "text-muted-foreground"
                      )}
                      title={text}
                    >
                      {text}
                    </p>
                  )}
                </li>
              );
            }
            const { call, closing } = row;
            const failed = closing?.type === "error";
            return (
              <li key={`${call.origin ?? "server"}-call-${call.id}`} className="min-w-0 space-y-0.5 text-xs">
                <div className="flex items-center gap-1.5">
                  {failed && (
                    <AlertTriangle size={12} className="size-3 shrink-0 text-destructive" aria-hidden="true" />
                  )}
                  <span className="truncate font-medium text-foreground">{call.tool}</span>
                  {closing?.ms != null && <span className="shrink-0 text-muted-foreground">{closing.ms} ms</span>}
                  {/* Still running - the tool_call arrived but nothing has
                      closed it yet. This is the live half of the trace. */}
                  {!closing && <span className="shrink-0 text-muted-foreground">running…</span>}
                  {call.origin === "on-device" && <OnDeviceBadge />}
                </div>
                {/* Server-redacted and length-capped before it ever left the
                    backend (AgentStepEventRedactor) - secret-named argument
                    fields and credential-shaped values are already [redacted],
                    and this is a short preview, not the full payload. */}
                {call.args && (
                  <p className="truncate font-mono text-[11px] text-muted-foreground" title={call.args}>
                    {call.args}
                  </p>
                )}
                {closing && (closing.preview || closing.message) && (
                  <p
                    className={cn("truncate text-[11px]", failed ? "text-destructive" : "text-muted-foreground")}
                    title={closing.preview || closing.message || undefined}
                  >
                    {closing.preview || closing.message}
                  </p>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}
