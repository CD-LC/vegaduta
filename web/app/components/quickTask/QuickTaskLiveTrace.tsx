"use client";

import { CheckCircle2, Terminal, XCircle } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Card } from "../ui/card";
import PoweredByChip, { inferSource } from "../PoweredByChip";
import TaskIdChip from "./TaskIdChip";
import type { RunPower } from "./model";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Quick Task's live trace: one row per real ToolCallLog entry the run's SSE
 * stream reported, in firing order, with the run's engine and task id.
 */
export default function QuickTaskLiveTrace({
  busy,
  trace,
  runPower,
  taskId,
}: {
  busy: boolean;
  trace: any[];
  runPower: RunPower | null;
  taskId: string | null;
}) {
  const t = useTranslations("simpleTaskRunner");
  return (
    <Card className="gap-3 p-5">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          <Terminal aria-hidden="true" size={13} /> {t("liveTraceLabel")}
        </span>
        <div className="flex items-center gap-2">
          {(runPower?.providerName || runPower?.model) && (
            <PoweredByChip
              source={inferSource(runPower?.providerName)}
              provider={runPower?.providerName}
              model={runPower?.model}
            />
          )}
          {taskId && <TaskIdChip taskId={taskId} />}
          {busy && (
            <span className="flex items-center gap-1.5 text-xs text-emerald-500">
              <span className="size-1.5 animate-pulse rounded-full bg-emerald-500" /> {t("runningStatus")}
            </span>
          )}
        </div>
      </div>
      {trace.length === 0 ? (
        <p className="text-sm text-muted-foreground">{busy ? t("thinkingNoToolsYet") : t("noToolsNeeded")}</p>
      ) : (
        <div className="space-y-2">
          {trace.map((entry, i) => (
            <div
              key={i}
              className="flex items-start gap-2.5 rounded-lg border border-border bg-secondary/30 px-3 py-2"
            >
              <span className="mt-0.5 shrink-0">
                {entry.errorMessage ? (
                  <XCircle aria-hidden="true" size={13} className="text-destructive" />
                ) : (
                  <CheckCircle2 aria-hidden="true" size={13} className="text-emerald-500" />
                )}
              </span>
              <div className="min-w-0 flex-1 space-y-0.5">
                <div className="text-sm font-medium text-foreground">{entry.toolName}</div>
                {entry.toolInput && (
                  <div className="break-words text-xs text-muted-foreground">
                    {t("traceIn", { input: entry.toolInput })}
                  </div>
                )}
                {entry.errorMessage ? (
                  <div className="break-words text-xs text-destructive">
                    {t("traceError", { message: entry.errorMessage })}
                  </div>
                ) : (
                  entry.toolOutput && (
                    <div className="break-words text-xs text-muted-foreground">
                      {t("traceOut", { output: entry.toolOutput })}
                    </div>
                  )
                )}
              </div>
              <span className="shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
                {t("traceMs", { ms: entry.durationMs })}
              </span>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
