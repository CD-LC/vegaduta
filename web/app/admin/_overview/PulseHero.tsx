"use client";

import HudCounter from "../../components/dashboard/HudCounter";
import CognitionCore from "../../components/dashboard/CognitionCore";
import { useTranslations } from "../../lib/i18n";
import type { AdminSystemStatus, ChipTone } from "./model";
import { StatusChip } from "./ui";

/** Workspace pulse hero: the CognitionCore canvas with health verdict and LLM call totals. */
export default function PulseHero({
  llm,
  overall,
}: {
  llm: AdminSystemStatus["llm"] | undefined;
  overall: { tone: ChipTone; icon: React.ReactNode; label: string };
}) {
  const t = useTranslations("adminOverview");
  return (
    <div
      className="relative min-h-[300px] overflow-hidden rounded-2xl border border-border shadow-[var(--shadow-card)]"
      style={{ background: "radial-gradient(90% 90% at 50% 45%, var(--hud-panel-alt), var(--hud-panel))" }}
      data-testid="admin-pulse-hero"
    >
      <CognitionCore />
      <div className="absolute left-5 top-4 z-[2] min-w-0 max-w-[55%]">
        <div
          className="truncate text-xs font-medium uppercase tracking-[0.08em]"
          style={{ color: "var(--hud-text-dim)" }}
        >
          {t("workspacePulseLabel")}
        </div>
        <div className="mt-0.5 truncate text-xs" style={{ color: "var(--hud-text-dim)" }}>
          {t("workspacePulseSub")}
        </div>
      </div>
      <div className="absolute right-5 top-4 z-[2] flex max-w-[45%] flex-col items-end gap-1">
        <div
          className="truncate text-xs font-medium uppercase tracking-[0.08em]"
          style={{ color: "var(--hud-text-dim)" }}
        >
          {t("heroSystemStatusLabel")}
        </div>
        <StatusChip tone={overall.tone} icon={overall.icon}>
          {overall.label}
        </StatusChip>
      </div>
      {/* Soft scrim behind the centre readout: the canvas animates
          nodes straight through the middle of the panel, so the count
          sits on a blurred, panel-tinted pill to stay legible. */}
      <div
        className="pointer-events-none absolute left-1/2 top-1/2 z-[2] -translate-x-1/2 -translate-y-1/2 rounded-2xl px-6 py-3 text-center"
        style={{
          background: "color-mix(in srgb, var(--hud-panel) 78%, transparent)",
          backdropFilter: "blur(5px)",
          WebkitBackdropFilter: "blur(5px)",
        }}
      >
        <div className="font-mono text-5xl font-semibold leading-none tabular-nums text-foreground">
          <HudCounter value={llm ? Number(llm.totalCalls) : null} />
        </div>
        <div className="mt-2 text-xs font-medium uppercase tracking-[0.08em]" style={{ color: "var(--hud-text-dim)" }}>
          {t("heroLlmCallsFull")}
        </div>
      </div>
      <div className="absolute bottom-4 left-5 right-5 z-[2] flex justify-center gap-8">
        <div className="text-center">
          <div className="font-mono text-xl font-semibold tabular-nums text-foreground">
            {llm?.avgLatencyMs != null ? `${Math.round(llm.avgLatencyMs)}ms` : "—"}
          </div>
          <div className="text-xs" style={{ color: "var(--hud-text-dim)" }}>
            {t("heroAvgLatencyLabel")}
          </div>
        </div>
        <div className="text-center">
          <div className="font-mono text-xl font-semibold tabular-nums text-foreground">
            {llm ? llm.errorCount : "—"}
          </div>
          <div className="text-xs" style={{ color: "var(--hud-text-dim)" }}>
            {t("heroLlmErrorsLabel")}
          </div>
        </div>
      </div>
    </div>
  );
}
