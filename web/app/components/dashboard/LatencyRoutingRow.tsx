"use client";

import { useTranslations } from "../../lib/i18n";
import { Card } from "../ui/card";
import { fmtMs } from "./dashboardFormat";

/* eslint-disable @typescript-eslint/no-explicit-any */
/** LLM latency percentiles and the model-routing donut (below the fold, deferred). */
export default function LatencyRoutingRow({
  llm,
  modelArcs,
}: {
  llm: any;
  modelArcs: { model: string; pct: number; offset: number; color: string }[];
}) {
  const t = useTranslations("dashboard");
  return (
    <div className="defer-offscreen grid grid-cols-1 gap-4 sm:grid-cols-2">
      <Card className="gap-4 p-6">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {t("latencyLabel")}
        </span>
        <div className="grid grid-cols-3 gap-4">
          {[
            { k: t("p50"), v: fmtMs(llm?.p50LatencyMs), accent: false },
            { k: t("p95"), v: fmtMs(llm?.p95LatencyMs), accent: false },
            { k: t("p99"), v: fmtMs(llm?.p99LatencyMs), accent: true },
          ].map((row) => (
            <div key={row.k}>
              <div
                className={
                  "text-2xl font-semibold tabular-nums " +
                  (row.accent ? "text-[var(--state-warning)]" : "text-foreground")
                }
              >
                {row.v}
              </div>
              <div className="mt-0.5 font-mono text-xs uppercase tracking-wider text-muted-foreground">{row.k}</div>
            </div>
          ))}
        </div>
      </Card>

      <Card className="flex-row items-center gap-5 p-6">
        <svg viewBox="0 0 36 36" style={{ width: 64, height: 64, transform: "rotate(-90deg)" }} aria-hidden="true">
          <circle cx="18" cy="18" r="15.915" fill="none" stroke="var(--ax-track)" strokeWidth="4" />
          {modelArcs.map((arc) => (
            <circle
              key={arc.model}
              cx="18"
              cy="18"
              r="15.915"
              fill="none"
              stroke={arc.color}
              strokeWidth="4"
              strokeDasharray={`${arc.pct} ${100 - arc.pct}`}
              strokeDashoffset={arc.offset}
            />
          ))}
        </svg>
        <div className="min-w-0">
          <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {t("modelRoutingLabel")}
          </div>
          <div className="mt-2 space-y-1">
            {modelArcs.length === 0 && (
              <span className="text-sm text-muted-foreground">{t("modelRoutingEmpty")}</span>
            )}
            {modelArcs.map((arc) => (
              <div key={arc.model} className="flex items-center gap-2 text-sm">
                <span className="size-2 rounded-full" style={{ background: arc.color }} />
                <span className="truncate text-foreground">{arc.model}</span>
                <span className="ml-auto tabular-nums text-muted-foreground">{arc.pct}%</span>
              </div>
            ))}
          </div>
        </div>
      </Card>
    </div>
  );
}
