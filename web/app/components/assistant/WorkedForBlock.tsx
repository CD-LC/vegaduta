"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, ArrowRightLeft, Brain, ChevronRight, ListChecks, Loader2, Shield, Users } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { buildTraceRows, describeMemoryUse, type ActivityStep } from "../agents/AgentActivityTrace";
import { formatDuration, isVisibleStep, liveStatusKey, skippedReason, summarizeWork } from "./workedFor";

/**
 * One collapsed "Working for 2 m 10 s · 6 tool calls, 1 failed" row per assistant turn (the OpenClaw
 * progress language the design doc borrows). Built from the same step events AgentActivityTrace renders
 * on the agent pages, folded the same way (buildTraceRows), but with the surface's own vocabulary:
 * failed calls counted in the heading, `guardrail` events shown as "Skipped: reason", `plan` as the
 * thinking line, `context_compacted` as a quiet "condensed earlier context". Renders nothing for a turn
 * that produced no events and never started a tool, so a plain answer looks like a plain answer.
 */
// Provider reasoning arrives as many small `thinking` deltas while the model
// streams - fold each run of them into one entry so the row shows the full
// reasoning, not one fragment per SSE chunk.
function mergeThinkingSteps(steps: readonly ActivityStep[]): ActivityStep[] {
  const merged: ActivityStep[] = [];
  for (const step of steps) {
    const prev = merged[merged.length - 1];
    if (step.type === "thinking" && prev?.type === "thinking") {
      prev.message = `${prev.message ?? ""}${step.message ?? ""}`;
      continue;
    }
    merged.push({ ...step });
  }
  return merged;
}

// Tool name -> what it's doing, for the live status line. Fallback humanises
// the tool's own snake_case ("read_email" -> "Read email") so a new tool gets
// a sane label before a string lands for it.
const TOOL_STATUS_KEYS: Record<string, string> = {
  web_search: "searchingWeb",
  fetch_web_page: "readingPage",
  web_task: "researching",
  deep_research: "researching",
  generate_document: "creatingDocument",
  load_skill: "loadingSkill",
  local_shell: "runningCommand",
  local_shell_read: "runningCommand",
  remember_fact: "remembering",
  recall_related_facts: "checkingMemory",
};

function liveStatusLabel(
  status: NonNullable<ReturnType<typeof liveStatusKey>>,
  t: (key: string, vars?: Record<string, unknown>) => string
): string {
  switch (status.key) {
    case "starting":
      return t("statusStarting");
    case "guardrails":
      return t("statusGuardrails");
    case "routing":
      return t("statusRouting");
    case "context":
      return t("statusContext");
    case "model":
      return t("statusModel");
    case "analyzing":
      return t("statusAnalyzing");
    case "planning":
      return t("statusPlanning");
    case "afterTool":
      return t("statusComposing");
    case "recovering":
      return t("statusRecovering");
    case "tool": {
      const key = status.tool ? TOOL_STATUS_KEYS[status.tool] : undefined;
      return key
        ? t(key)
        : t("statusToolGeneric", { tool: (status.tool ?? "tool").replace(/[_-]+/g, " ").replace(/^\w/, (c) => c.toUpperCase()) });
    }
    default:
      return t("statusWorking");
  }
}

// The thinking ticker shows the END of the stream (the freshest thought),
// tail-truncated so one line always shows the latest reasoning.
function tailSnippet(text: string, max = 160): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length <= max ? clean : `…${clean.slice(clean.length - max)}`;
}

export default function WorkedForBlock({
  steps,
  startedAt,
  endedAt,
  className,
  showThinking = false,
}: {
  steps: readonly ActivityStep[] | null | undefined;
  startedAt?: number | null;
  endedAt?: number | null;
  className?: string;
  /** False hides the model's raw reasoning (the live ticker line and the
   * `thinking` rows in the expanded trace); the working summary and other
   * step types are unaffected. */
  showThinking?: boolean;
}) {
  const t = useTranslations("assistant");
  const live = endedAt == null;
  const [open, setOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  // A ticking clock only while the turn is live; a settled row never re-renders on its own.
  useEffect(() => {
    if (!live) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [live]);

  const visible = mergeThinkingSteps((steps ?? []).filter(isVisibleStep)).filter(
    (s) => showThinking || s.type !== "thinking"
  );
  // A live turn with only phase events (nothing to list yet) still gets the heading and the
  // status line: that is the whole point of the pre-token phases. A settled turn with no rows
  // renders nothing, so a plain answer looks like a plain answer.
  if (visible.length === 0 && !(live && (steps?.length ?? 0) > 0)) return null;
  const summary = summarizeWork(steps, startedAt, endedAt, now);
  const rows = buildTraceRows(visible);
  const duration = formatDuration(summary.durationMs);
  const status = live ? liveStatusKey(steps) : null;
  const parts: string[] = [];
  if (summary.toolCalls > 0)
    parts.push(summary.toolCalls === 1 ? t("oneToolCall") : t("toolCalls", { n: summary.toolCalls }));
  if (summary.failed > 0) parts.push(t("failedCalls", { n: summary.failed }));
  if (summary.skipped > 0) parts.push(t("skippedCalls", { n: summary.skipped }));
  const heading = live ? t("workingFor", { duration }) : t("workedFor", { duration });

  return (
    <div className={cn("w-full max-w-[85%]", className)} data-testid="worked-for">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="h-auto gap-1 px-1.5 py-0.5 text-xs font-normal text-muted-foreground"
      >
        <ChevronRight size={12} className={cn("size-3 transition-transform", open && "rotate-90")} aria-hidden="true" />
        {live && <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />}
        <span className="font-medium text-foreground">{heading}</span>
        {parts.length > 0 && <span>· {parts.join(", ")}</span>}
      </Button>
      {live && status && (
        <div className="mt-1 flex items-center gap-1.5 px-1.5 text-xs text-muted-foreground" data-testid="live-status">
          <Loader2 size={12} className="animate-spin text-primary" aria-hidden="true" />
          <span className="animate-pulse font-medium">{liveStatusLabel(status, t)}</span>
        </div>
      )}
      {live && showThinking && status?.thinking && (
        <p
          className="mt-0.5 max-w-full truncate px-1.5 text-xs italic text-muted-foreground/80"
          title={status.thinking}
          data-testid="live-thinking"
        >
          {tailSnippet(status.thinking)}
        </p>
      )}
      {open && rows.length > 0 && (
        <ol className="mt-1 space-y-1.5 rounded-lg border border-border bg-secondary/30 px-3 py-2">
          {rows.map((row, index) => {
            if (row.kind === "tool") {
              const { call, closing } = row;
              const failed = closing?.type === "error";
              return (
                <li key={`call-${call.id}-${index}`} className="min-w-0 space-y-0.5 text-xs">
                  <div className="flex items-center gap-1.5">
                    {failed && <AlertTriangle size={12} className="size-3 shrink-0 text-destructive" aria-hidden="true" />}
                    <span className={cn("truncate font-mono font-medium", failed ? "text-destructive" : "text-foreground")}>{call.tool}</span>
                    {closing?.ms != null && <span className="shrink-0 text-muted-foreground">{closing.ms} ms</span>}
                    {!closing && live && <span className="shrink-0 text-muted-foreground">{t("stepRunning")}</span>}
                  </div>
                  {call.args && (
                    <p className="truncate font-mono text-xs text-muted-foreground" title={call.args}>
                      {call.args}
                    </p>
                  )}
                  {closing && (closing.preview || closing.message) && (
                    <p
                      className={cn("truncate text-xs", failed ? "text-destructive" : "text-muted-foreground")}
                      title={closing.preview || closing.message || undefined}
                    >
                      {closing.preview || closing.message}
                    </p>
                  )}
                </li>
              );
            }
            return <EventRow key={`event-${row.step.type}-${row.step.id}-${index}`} step={row.step} />;
          })}
        </ol>
      )}
    </div>
  );
}

function EventRow({ step }: { step: ActivityStep }) {
  const t = useTranslations("assistant");
  const base = "size-3 shrink-0 text-muted-foreground";
  let icon = <ListChecks size={12} className={base} aria-hidden="true" />;
  let label: string;
  let text: string | null = null;
  let tone = "text-foreground";
  switch (step.type) {
    case "guardrail": {
      icon = <Shield size={12} className={base} aria-hidden="true" />;
      const reason = skippedReason(step);
      label = reason ? t("skippedWithReason", { reason }) : t("skipped");
      text = step.tool ?? null;
      tone = "text-foreground";
      break;
    }
    case "plan":
      label = t("planLabel");
      text = step.message ?? null;
      break;
    case "thinking":
      icon = <Brain size={12} className={base} aria-hidden="true" />;
      label = t("thinkingLabel");
      text = step.message ?? null;
      break;
    case "memory":
      icon = <Brain size={12} className={base} aria-hidden="true" />;
      label = describeMemoryUse(step.memory);
      break;
    case "context_compacted":
      label = t("condensedContext");
      break;
    case "handoff":
      icon = <ArrowRightLeft size={12} className={base} aria-hidden="true" />;
      label = t("handoffLabel");
      text = step.message ?? null;
      break;
    case "swarm":
      icon = <Users size={12} className={base} aria-hidden="true" />;
      label = t("swarmLabel");
      text = step.message ?? null;
      break;
    case "error":
      icon = <AlertTriangle size={12} className="size-3 shrink-0 text-destructive" aria-hidden="true" />;
      label = step.tool ? t("errorIn", { tool: step.tool }) : t("errorLabel");
      text = step.message ?? step.preview ?? null;
      tone = "text-destructive";
      break;
    default:
      label = step.type.replace(/[_-]+/g, " ").replace(/^\w/, (c) => c.toUpperCase());
      text = step.message ?? step.preview ?? step.tool ?? null;
  }
  return (
    <li className="min-w-0 space-y-0.5 text-xs">
      <div className="flex items-center gap-1.5">
        {icon}
        <span className={cn("truncate font-medium", tone)}>{label}</span>
      </div>
      {text && (
        <p
          className={cn(
            "whitespace-pre-wrap text-xs text-muted-foreground",
            step.type === "thinking" && "italic"
          )}
          title={text}
        >
          {text}
        </p>
      )}
    </li>
  );
}
