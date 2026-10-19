"use client";

import { AlertTriangle } from "lucide-react";
import type { ProviderModelInfo } from "../../lib/api";
import { Badge } from "../ui/badge";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";

// "128000 -> 128K", "1000000 -> 1M". Anything missing/non-positive renders no
// context pill at all rather than a made-up figure - the catalog genuinely has
// models without a published window.
function formatContextWindow(contextWindow: number | null | undefined): string | null {
  if (!contextWindow || contextWindow <= 0) return null;
  if (contextWindow >= 1_000_000) {
    const millions = contextWindow / 1_000_000;
    return `${Number.isInteger(millions) ? millions : millions.toFixed(1)}M`;
  }
  if (contextWindow >= 1_000) return `${Math.round(contextWindow / 1_000)}K`;
  return String(contextWindow);
}

// Dropdown option label shared by every model picker (AgentWorkbench x4,
// RlArmsEditor) - the pre-existing `displayName (modelId)` shape plus a
// deprecation affix when the catalog flags the row. `deprecatedLabel` is
// passed in (t("modelDeprecatedLabel")) rather than translated here so this
// stays a plain function usable inside options-array literals.
export function modelOptionLabel(m: ProviderModelInfo, deprecatedLabel: string): string {
  const base = m.displayName && m.displayName !== m.modelId ? `${m.displayName} (${m.modelId})` : m.modelId;
  return m.deprecated === true ? `${base} — ${deprecatedLabel}` : base;
}

// Three-tier "$"/"$$"/"$$$" cost badge, same spirit as Chatbase's per-model
// price comparison - a coarse, at-a-glance signal, not the exact number
// (that's in the badge's title/aria-label tooltip, built in the component
// below from formatUsdPerMillion). Blended
// 3:1 completion:prompt weighting is a rough approximation of typical chat
// turns (most token spend is on the model's own output, not the prompt it
// was given) - good enough for a relative tier, not offered as a per-call
// estimate. Thresholds are anchored to LlmPricingCatalog's actual rate
// spread ($0.05-$0.10/MTok cheapest inputs up to $75/MTok priciest outputs):
// $ covers the small/fast/open-model tier (mini/nano/flash/llama-class, blended
// under $1/MTok), $$$ the frontier-reasoning tier (Opus-class, blended $15+/MTok
// - checked against every LlmPricingCatalog entry as of 2026-09-23 so this
// split actually separates "Opus" from "Sonnet/GPT-4o", not just "cheap" from
// "not cheap"), $$ everything in between (Sonnet/GPT-4o/Gemini-Pro-class).
function blendedCostPerMillion(input: number, output: number): number {
  return input * 0.25 + output * 0.75;
}

type CostTier = "low" | "medium" | "high";

function costTierFor(inputPerMillionUsd: number, outputPerMillionUsd: number): CostTier {
  const blended = blendedCostPerMillion(inputPerMillionUsd, outputPerMillionUsd);
  if (blended < 1) return "low";
  if (blended < 15) return "medium";
  return "high";
}

const COST_TIER_SYMBOL: Record<CostTier, string> = { low: "$", medium: "$$", high: "$$$" };

function formatUsdPerMillion(value: number): string {
  return value < 1 ? `$${value.toFixed(2)}` : `$${value.toFixed(0)}`;
}

/**
 * Compact "what did I just pick?" box rendered under each model dropdown.
 * Surfaces the catalog metadata the API has always returned but no picker
 * showed: the capability blurb, the context window, input modalities, and a
 * deprecation warning. Renders nothing when there's no selected catalog row
 * (free-text/__custom__ path, or nothing chosen yet) and nothing when the row
 * carries no metadata worth showing - it never reserves empty space.
 */
export default function ModelInfoCard({
  model,
  className,
}: {
  model: ProviderModelInfo | undefined;
  className?: string;
}) {
  const t = useTranslations("agentWorkbench");
  if (!model) return null;

  const summary = model.capabilitySummary || model.description || null;
  const contextLabel = formatContextWindow(model.contextWindow);
  const modalities = [
    model.supportsImageInput ? t("modelInputImage") : null,
    model.supportsAudioInput ? t("modelInputAudio") : null,
    model.supportsVideoInput ? t("modelInputVideo") : null,
  ].filter(Boolean) as string[];
  const deprecated = model.deprecated === true;

  // Undefined (pricing fetch never ran/failed) is deliberately distinct from
  // 0 (a genuinely free model) - only render the badge when both rates are
  // real numbers, never a fabricated tier from a half-loaded value.
  const hasCostData = typeof model.inputCostPerMillionUsd === "number" && typeof model.outputCostPerMillionUsd === "number";
  const costTier = hasCostData ? costTierFor(model.inputCostPerMillionUsd as number, model.outputCostPerMillionUsd as number) : null;
  const costTooltip = hasCostData
    ? t("modelCostTierTooltip", {
        input: formatUsdPerMillion(model.inputCostPerMillionUsd as number),
        output: formatUsdPerMillion(model.outputCostPerMillionUsd as number),
      })
    : null;

  if (!summary && !contextLabel && modalities.length === 0 && !deprecated && !costTier) return null;

  return (
    <div
      data-testid="model-info-card"
      className={cn("rounded-lg border border-border bg-secondary/20 px-3 py-2", className)}
    >
      {summary && <p className="m-0 text-xs leading-relaxed text-muted-foreground">{summary}</p>}
      {(contextLabel || modalities.length > 0 || deprecated || costTier) && (
        <div className={cn("flex flex-wrap items-center gap-1.5", summary && "mt-1.5")}>
          {deprecated && (
            <Badge variant="state" state="var(--state-warning)">
              <AlertTriangle size={11} aria-hidden="true" />
              {t("modelDeprecatedBadge")}
            </Badge>
          )}
          {costTier && (
            <Badge
              data-testid="model-cost-tier-badge"
              variant="outline"
              title={costTooltip ?? undefined}
              aria-label={costTooltip ?? undefined}
            >
              {COST_TIER_SYMBOL[costTier]}
            </Badge>
          )}
          {contextLabel && <Badge variant="outline">{t("modelContextWindow", { size: contextLabel })}</Badge>}
          {modalities.map((label) => (
            <Badge key={label} variant="outline">
              {label}
            </Badge>
          ))}
        </div>
      )}
    </div>
  );
}
