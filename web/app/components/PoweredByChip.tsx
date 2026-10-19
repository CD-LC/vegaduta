"use client";

import { Cloud, Cpu, KeyRound, ScrollText } from "lucide-react";
import { cn } from "../lib/utils";
import { poolLabel } from "../lib/personalInstall";

/**
 * Shared "what powered this" disclosure for every AI-generation surface
 * (build agent, build app, custom apps, playground, quick task). One
 * component + one source vocabulary so a customer never has to wonder
 * whether a result came from their own device, the included platform
 * pool, or their own provider key - and so internal labels like
 * PLATFORM_HOSTED never reach the screen raw.
 *
 * Rendered as a small muted inline chip; the `title` tooltip carries the
 * one-line privacy/routing explanation. Deliberately copy-inline (no
 * i18n keys) - the messages catalog is concurrently edited and this is
 * established precedent (see build/page.tsx's inline-copy comment).
 */

export type PoweredBySource = "platform" | "byok" | "device" | "recipes";

export interface PoweredBy {
  source: PoweredBySource;
  /** Raw provider id (e.g. "ANTHROPIC", "PLATFORM_HOSTED") or a free-form
   * label for source "device"/"recipes" - normalized by providerDisplayName. */
  provider?: string | null;
  /** Model id when honestly known; null is rendered as the provider's pick,
   * never fabricated. */
  model?: string | null;
}

/** Customer-facing provider label. PLATFORM_HOSTED is an internal
 * accounting label for calls the included platform pool paid for - the
 * tenant-facing name matches billing/LlmSpendSection's convention. */
export function providerDisplayName(name: string | null | undefined): string | null {
  if (!name) return null;
  if (name === "PLATFORM_HOSTED") return poolLabel("VegaDūta (included)");
  const pretty: Record<string, string> = {
    ANTHROPIC: "Anthropic",
    OPENAI: "OpenAI",
    AZURE_OPENAI: "Azure OpenAI",
    GOOGLE_VERTEX: "Vertex AI",
    GOOGLE_GEMINI: "Gemini",
    GEMINI: "Gemini",
    AWS_BEDROCK: "AWS Bedrock",
    OLLAMA: "Ollama",
    OPENROUTER: "OpenRouter",
    GROK: "xAI Grok",
    SARVAM: "Sarvam",
    MISTRAL: "Mistral",
    DEEPSEEK: "DeepSeek",
    GROQ: "Groq",
    META: "Meta",
    LOCAL_OPENAI: "Local server",
  };
  return pretty[name] ?? name.charAt(0) + name.slice(1).toLowerCase().replace(/_/g, " ");
}

/** Infer the source from a raw provider name: PLATFORM_HOSTED = the
 * included pool, anything else present = the tenant's own key. */
export function inferSource(provider: string | null | undefined): PoweredBySource {
  return provider === "PLATFORM_HOSTED" ? "platform" : provider ? "byok" : "platform";
}

const SOURCE_META: Record<PoweredBySource, { icon: typeof Cloud; title: string }> = {
  platform: {
    icon: Cloud,
    title: "Runs on VegaDūta's hosted model pool - included with your workspace, billed to the platform.",
  },
  byok: {
    icon: KeyRound,
    title: "Runs on a provider key configured by your workspace - usage bills to that account.",
  },
  device: {
    icon: Cpu,
    title: "Runs on this device - your input never leaves the browser. Free on every plan.",
  },
  recipes: {
    icon: ScrollText,
    title: "Built from a deterministic recipe, not an AI model - always available, always free.",
  },
};

export default function PoweredByChip({
  source,
  provider,
  model,
  prefix = "Powered by",
  title,
  className,
}: PoweredBy & {
  /** Verb phrase before the provider, e.g. "Powered by" vs "Will run on" -
   * pick "will"-phrasing for pre-run disclosures so a plan is never
   * mistaken for what actually ran. */
  prefix?: string;
  /** Tooltip override; defaults to the per-source explanation. */
  title?: string;
  className?: string;
}) {
  const meta = SOURCE_META[source];
  const Icon = meta.icon;
  const label = providerDisplayName(provider) ?? (source === "device" ? "on-device" : source === "recipes" ? "recipes" : null);
  return (
    <span
      className={cn(
        "inline-flex max-w-full items-center gap-1 rounded-full border border-border/60 bg-muted/40 px-2 py-0.5 text-[10px] font-medium text-muted-foreground",
        className
      )}
      title={title ?? meta.title}
    >
      <Icon size={10} aria-hidden="true" className="shrink-0" />
      <span className="truncate">
        {prefix} {label}
        {model ? ` · ${model}` : ""}
      </span>
    </span>
  );
}
