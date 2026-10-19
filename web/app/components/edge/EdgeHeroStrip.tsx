"use client";

import type { EdgeCapabilities, PromptApiAvailability } from "../../lib/edge/capabilities";
import type { EdgeInUseState } from "./edgeConsoleBridge";

import {
  Cloud,
  Cpu,
  Globe,
  HardDrive,
  SlidersHorizontal,
  Zap,
} from "lucide-react";
import { cn } from "../../lib/utils";
import Select from "../Select";
import { useTranslations } from "../../lib/i18n";
import {
  setEdgeMode,
  type EdgeMode,
} from "./edgeConsoleBridge";

type ModeOption = { value: EdgeMode; label: string; hint: string; disabled?: boolean };

/** Device verdict, live on-device indicator and the mode master switch. */
export default function EdgeHeroStrip({
  caps,
  verdictLabel,
  declaredGB,
  onDeclaredGB,
  tier0Status,
  inUse,
  nowRunningName,
  mode,
  modeOptions,
}: {
  caps: EdgeCapabilities | null;
  verdictLabel: string;
  declaredGB: number | null;
  onDeclaredGB: (gb: number | null) => void;
  tier0Status: PromptApiAvailability | "checking";
  inUse: EdgeInUseState;
  nowRunningName: string | null;
  mode: EdgeMode;
  modeOptions: ModeOption[];
}) {
  const t = useTranslations("edge");
  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <div className="flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
        {/* Device verdict */}
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center gap-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            <Cpu size={12} aria-hidden="true" />
            {t("hero.deviceTitle")}
          </div>
          <div className="text-sm font-medium text-foreground">{verdictLabel}</div>
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1">
              <Zap size={12} aria-hidden="true" />
              {t("hero.webgpu")}: {caps ? (caps.webgpu.available ? t("hero.yes") : t("hero.no")) : "…"}
            </span>
            <span className="inline-flex items-center gap-1">
              <HardDrive size={11} aria-hidden="true" />
              {t("hero.memory")}:{" "}
              {declaredGB != null
                ? `${declaredGB} GB`
                : caps?.deviceMemoryGB == null
                  ? t("hero.memoryNotReported")
                  : caps.deviceMemoryGB >= 8
                    ? t("hero.memoryCapped", { gb: caps.deviceMemoryGB })
                    : `${caps.deviceMemoryGB} GB`}
              <Select
                aria-label={t("hero.memoryDeclare")}
                value={declaredGB == null ? "" : String(declaredGB)}
                onChange={(e) => onDeclaredGB(e.target.value === "" ? null : Number(e.target.value))}
                options={[
                  { value: "", label: t("hero.memoryAuto") },
                  ...[4, 8, 12, 16, 24, 32, 48, 64, 96].map((gb) => ({ value: String(gb), label: `${gb} GB` })),
                ]}
                className="ml-1 w-24"
              />
            </span>
            <span className="inline-flex items-center gap-1">
              <Globe size={11} aria-hidden="true" />
              {t("hero.builtin")}:{" "}
              {tier0Status === "checking"
                ? "…"
                : tier0Status === "available"
                  ? t("market.builtinReady")
                  : tier0Status === "downloadable" || tier0Status === "downloading"
                    ? t("market.builtinAvailable")
                    : t("hero.no")}
            </span>
          </div>
          {/* Live on-device indicator (in-use observable) */}
          <div
            className={cn(
              "mt-3 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs",
              inUse.active
                ? "border-primary/40 bg-primary/10 text-foreground"
                : "border-border bg-secondary/40 text-muted-foreground"
            )}
          >
            <span
              className={cn("size-2 rounded-full", inUse.active ? "animate-pulse bg-primary" : "bg-border")}
              aria-hidden="true"
            />
            {inUse.active && nowRunningName ? t("hero.nowRunning", { model: nowRunningName }) : t("hero.idle")}
          </div>
        </div>
  
        {/* Mode master toggle */}
        <div className="w-full shrink-0 lg:w-96">
          <div className="mb-1.5 flex items-center gap-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            <SlidersHorizontal size={12} aria-hidden="true" />
            {t("hero.modeTitle")}
          </div>
          <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-3">
            {modeOptions.map((option) => (
              <button
                key={option.value}
                type="button"
                onClick={() => setEdgeMode(option.value)}
                disabled={option.disabled}
                aria-pressed={mode === option.value}
                className={cn(
                  "flex w-full flex-col items-start gap-0.5 rounded-md border px-2.5 py-2 text-left text-xs transition-colors disabled:cursor-not-allowed disabled:opacity-60",
                  mode === option.value
                    ? "border-primary bg-primary/10 text-foreground"
                    : "border-border bg-transparent text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
                )}
              >
                <span className="inline-flex items-center gap-1.5 font-medium text-foreground">
                  {option.value === "hosted-only" ? (
                    <Cloud size={12} aria-hidden="true" />
                  ) : (
                    <Cpu size={12} aria-hidden="true" />
                  )}
                  {option.label}
                </span>
                <span className="text-[11px] leading-snug text-muted-foreground">{option.hint}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
