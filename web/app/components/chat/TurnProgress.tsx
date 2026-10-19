"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { deriveTurnProgress, formatElapsed, thinkingTail, type TurnProgressState } from "../../lib/turnProgress";
import type { ActivityStep } from "../agents/AgentActivityTrace";
import { Button } from "../ui/button";

/**
 * The live "what is it doing" line for one in-flight chat turn, shared by every chat surface:
 * phase label (from core's `phase`/`thinking`/tool step events via lib/turnProgress.ts), a running
 * clock, and - when the model streams reasoning - the freshest line of it. Exists because a local
 * open-weight model can be silent for a minute between "send" and the first token; with this the
 * minute reads as progress ("Model is working · 42 s") instead of a hung page.
 *
 * Renders nothing when `steps` is empty and nothing has streamed (the caller's own
 * connecting indicator covers the first instant).
 */
export default function TurnProgress({
  steps,
  startedAt,
  hasText,
  showThinking = true,
  onCancel,
  compact = false,
  className,
}: {
  steps: readonly ActivityStep[] | null | undefined;
  startedAt?: number | null;
  hasText: boolean;
  /** False hides the reasoning line; the phase and clock still show. */
  showThinking?: boolean;
  onCancel?: () => void;
  /** Inline variant for a turn header: no border, no cancel button. */
  compact?: boolean;
  className?: string;
}) {
  const t = useTranslations("turnProgress");
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const state = deriveTurnProgress(steps, { hasText, startedAt, now });
  if ((steps?.length ?? 0) === 0 && !hasText) return null;
  const elapsed = formatElapsed(state.elapsedMs);
  const thought = showThinking && state.phase === "thinking" ? thinkingTail(state.thinking) : "";

  return (
    <div
      className={cn(
        "min-w-0 text-xs text-muted-foreground",
        !compact && "rounded-md border border-border bg-secondary/30 px-2.5 py-1.5",
        className
      )}
      role="status"
      aria-live="polite"
      data-testid="turn-progress"
      data-phase={state.phase}
    >
      <div className="flex items-center gap-1.5">
        <Loader2 size={12} className="size-3 shrink-0 animate-spin text-[var(--state-running)]" aria-hidden="true" />
        <span className="min-w-0 truncate font-medium text-foreground">{phaseLabel(state, t)}</span>
        {elapsed && <span className="shrink-0 tabular-nums">· {elapsed}</span>}
        {!compact && onCancel && (
          <Button type="button" variant="ghost" size="sm" className="ml-auto h-6 px-2 text-xs" onClick={onCancel}>
            {t("cancel")}
          </Button>
        )}
      </div>
      {thought && (
        <p className="mt-0.5 truncate text-[11px] italic text-muted-foreground/80" title={state.thinking} data-testid="turn-thinking">
          {thought}
        </p>
      )}
    </div>
  );
}

// Tool name -> what it is doing. Unknown tools get their own name, humanised.
const TOOL_KEYS: Record<string, string> = {
  web_search: "toolSearch",
  fetch_web_page: "toolRead",
  web_task: "toolResearch",
  deep_research: "toolResearch",
  generate_document: "toolDocument",
  local_shell: "toolCommand",
  local_shell_read: "toolCommand",
  remember_fact: "toolRemember",
  recall_related_facts: "toolMemory",
};

export function phaseLabel(state: TurnProgressState, t: (key: string, vars?: Record<string, unknown>) => string): string {
  if (state.phase === "tool") {
    const key = state.tool ? TOOL_KEYS[state.tool] : undefined;
    if (key) return t(key);
    const name = (state.tool ?? "tool").replace(/[_-]+/g, " ").replace(/^\w/, (c) => c.toUpperCase());
    return t("toolGeneric", { tool: name });
  }
  return t(state.phase);
}
