"use client";

import { useId, useState } from "react";
import { AlertTriangle, Brain, Check, FileText, Globe, Loader2, Search, Terminal, Wrench } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import type { buildTraceRows } from "../agents/AgentActivityTrace";
import { humanizeTool, siteOf, toolTarget } from "./workedFor";

type TraceRow = ReturnType<typeof buildTraceRows>[number];

/** At most this many chips in a row; the rest are counted ("+3") and live in the full trace. */
export const MAX_CHIPS = 8;

function iconFor(tool: string | undefined) {
  const name = (tool ?? "").toLowerCase();
  if (/search|research/.test(name)) return Search;
  if (/fetch|page|browse|web|url/.test(name)) return Globe;
  if (/shell|command|exec|terminal/.test(name)) return Terminal;
  if (/file|document|read|write/.test(name)) return FileText;
  if (/memory|remember|recall|fact/.test(name)) return Brain;
  return Wrench;
}

/**
 * One compact chip per tool call of a turn (icon, what it did, how it ended), the step timeline the leading
 * assistants show. A chip opens its details underneath (arguments, the result excerpt, how long it took); one
 * chip is open at a time. Live: a running call spins, and the chips appear as the calls do.
 */
export default function ToolStepChips({ rows, live }: { rows: readonly TraceRow[]; live: boolean }) {
  const t = useTranslations("assistant");
  const panelId = useId();
  const [openKey, setOpenKey] = useState<string | null>(null);
  const calls = rows.filter((r): r is Extract<TraceRow, { kind: "tool" }> => r.kind === "tool");
  if (calls.length === 0) return null;
  const shown = calls.slice(0, MAX_CHIPS);
  const more = calls.length - shown.length;
  const open = shown.find((r, i) => `${r.call.id}-${i}` === openKey) ?? null;

  return (
    <div className="mt-1.5" data-testid="tool-chips">
      <ul className="flex flex-wrap gap-1.5" aria-label={t("toolStepsLabel")}>
        {shown.map((row, i) => {
          const key = `${row.call.id}-${i}`;
          const failed = row.closing?.type === "error";
          const running = !row.closing && live;
          const target = toolTarget(row.call.args, 40);
          const Icon = iconFor(row.call.tool);
          const label = target
            ? `${humanizeTool(row.call.tool)} · ${/^https?:/.test(target) ? siteOf(target) : target}`
            : humanizeTool(row.call.tool);
          const expanded = openKey === key;
          return (
            <li key={key}>
              <button
                type="button"
                aria-expanded={expanded}
                aria-controls={expanded ? panelId : undefined}
                onClick={() => setOpenKey(expanded ? null : key)}
                data-state={failed ? "failed" : running ? "running" : "done"}
                title={label}
                className={cn(
                  "asst-step-chip inline-flex max-w-[16rem] items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs",
                  "transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
                  failed
                    ? "border-destructive/40 text-destructive"
                    : running
                      ? "border-primary/40 bg-primary/5 text-foreground"
                      : "border-border text-muted-foreground hover:text-foreground",
                  expanded && "bg-secondary text-foreground"
                )}
              >
                <Icon size={12} className="size-3 shrink-0" aria-hidden="true" />
                <span className="min-w-0 truncate">{label}</span>
                {running && <Loader2 size={11} className="size-3 shrink-0 motion-safe:animate-spin" aria-hidden="true" />}
                {failed && <AlertTriangle size={11} className="size-3 shrink-0" aria-hidden="true" />}
                {!running && !failed && row.closing && (
                  <Check size={11} className="size-3 shrink-0 text-primary" aria-hidden="true" />
                )}
                <span className="sr-only">
                  {failed ? t("chipFailed") : running ? t("stepRunning") : row.closing ? t("chipDone") : ""}
                </span>
              </button>
            </li>
          );
        })}
        {more > 0 && (
          <li className="inline-flex items-center px-1 text-xs text-muted-foreground">{t("moreSteps", { n: more })}</li>
        )}
      </ul>
      {open && (
        <div
          id={panelId}
          className="mt-1.5 space-y-1 rounded-lg border border-border bg-secondary/30 px-3 py-2 text-xs"
          data-testid="tool-chip-details"
        >
          <div className="flex items-center gap-2">
            <span className="font-mono font-medium text-foreground">{open.call.tool}</span>
            {open.closing?.ms != null && <span className="text-muted-foreground">{open.closing.ms} ms</span>}
          </div>
          {open.call.args && (
            <p className="whitespace-pre-wrap break-words font-mono text-muted-foreground">{open.call.args}</p>
          )}
          {open.closing && (open.closing.preview || open.closing.message) && (
            <p
              className={cn(
                "whitespace-pre-wrap break-words",
                open.closing.type === "error" ? "text-destructive" : "text-muted-foreground"
              )}
            >
              {open.closing.preview || open.closing.message}
            </p>
          )}
          {!open.closing && <p className="text-muted-foreground">{t("stepRunning")}</p>}
        </div>
      )}
    </div>
  );
}
