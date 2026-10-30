"use client";

// The context-window meter: what is in the model's window on the current step,
// how each slice compares to its cap, and what was pushed out to make room.
//
// It renders the ContextReport that lib/agentkit/context.ts produced WHILE
// assembling the prompt that was actually sent — not a second estimate made for
// display. If the two could drift, the meter would be decoration.
//
// Numbers are labelled as estimates because they are: the browser engines give
// the page no tokenizer (lib/agentkit/tokens.ts).

import { AGENT_COPY } from "../../lib/agentkit/copy";
import type { ContextReport, Eviction } from "../../lib/agentkit/context";
import { useTranslations } from "../../lib/i18n";

function pct(used: number, cap: number): number {
  if (cap <= 0) return used > 0 ? 100 : 0;
  return Math.max(0, Math.min(100, Math.round((used / cap) * 100)));
}

export default function ContextMeter({ report, evictions }: { report: ContextReport | null; evictions: Eviction[] }) {
    const tHC = useTranslations("hcComponentsAgentkitContextMeter");
  return (
    <div className="mt-5 rounded-lg border border-border p-4" data-testid="agentkit-context-meter">
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{AGENT_COPY.meterTitle}</p>
      <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted-foreground">{AGENT_COPY.meterBody}</p>

      {!report ? (
        <p className="mt-3 text-xs text-muted-foreground">{AGENT_COPY.meterEmpty}</p>
      ) : (
        <>
          <div className="mt-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2 text-xs">
              <span className="font-medium text-foreground">
                ~{report.usedTokens.toLocaleString()} of {report.promptBudget.toLocaleString()} {tHC("s2")}
              </span>
              <span className="text-muted-foreground">
                {report.contextWindow.toLocaleString()}{tHC("s1")}{" "}
                {(report.contextWindow - report.promptBudget).toLocaleString()} {AGENT_COPY.meterReserved}
              </span>
            </div>
            <div
              className="mt-1.5 h-2 w-full overflow-hidden rounded-full bg-secondary"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={report.promptBudget}
              aria-valuenow={Math.min(report.usedTokens, report.promptBudget)}
              aria-label={AGENT_COPY.meterTitle}
            >
              <div
                className={`h-full rounded-full ${report.fits ? "bg-primary" : "bg-destructive"}`}
                style={{ width: `${pct(report.usedTokens, report.promptBudget)}%` }}
              />
            </div>
          </div>

          <ul className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-2">
            {report.slices.map((slice) => (
              <li key={slice.name} className="min-w-0">
                <div className="flex items-baseline justify-between gap-2 text-[11px]">
                  <span className="truncate text-foreground">{slice.label}</span>
                  <span className="shrink-0 text-muted-foreground">
                    ~{slice.used.toLocaleString()} / {slice.cap.toLocaleString()}
                  </span>
                </div>
                <div className="mt-1 h-1 w-full overflow-hidden rounded-full bg-secondary">
                  <div
                    className={`h-full rounded-full ${slice.used > slice.cap ? "bg-destructive" : "bg-primary/70"}`}
                    style={{ width: `${pct(slice.used, slice.cap)}%` }}
                  />
                </div>
              </li>
            ))}
          </ul>

          {report.filesInWindow.length > 0 && (
            <div className="mt-3">
              <p className="text-[11px] font-medium text-foreground">{AGENT_COPY.filesInWindow}</p>
              <ul className="mt-1 space-y-0.5">
                {report.filesInWindow.map((file) => (
                  <li key={file.path} className="truncate font-mono text-[11px] text-muted-foreground">
                    {file.path} · lines {file.lines} of {file.totalLines}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="mt-3">
            <p className="text-[11px] font-medium text-foreground">{AGENT_COPY.evictedTitle}</p>
            {evictions.length === 0 ? (
              <p className="mt-1 text-[11px] text-muted-foreground">{AGENT_COPY.evictedNone}</p>
            ) : (
              <ul className="mt-1 space-y-0.5">
                {evictions.map((eviction, index) => (
                  <li key={`${index}-${eviction.what}`} className="text-[11px] text-muted-foreground">
                    <span className="font-mono text-foreground">{eviction.what}</span> — {eviction.why}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <p className="mt-3 text-[11px] text-muted-foreground">{AGENT_COPY.estimateNote}</p>
        </>
      )}
    </div>
  );
}
