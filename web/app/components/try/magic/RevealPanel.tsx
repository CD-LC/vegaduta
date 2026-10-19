"use client";

import { Bot, Check, Copy, Cpu, ExternalLink, Wrench } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { MagicPlan } from "../magicClient";

export type RevealTab = "diagram" | "agent" | "tools" | "mermaid";

/** "How it was built": the plan's pipeline, agent, registry tools and mermaid source, as tabs. */
export default function RevealPanel({
  plan,
  revealTab,
  onRevealTabChange,
  copied,
  copyText,
}: {
  plan: MagicPlan;
  revealTab: RevealTab;
  onRevealTabChange: (tab: RevealTab) => void;
  copied: "link" | "mermaid" | null;
  copyText: (text: string, which: "link" | "mermaid") => void;
}) {
  const t = useTranslations("landing");
  return (
    <div className="rounded-2xl border border-border/60 bg-card/70 p-5 backdrop-blur">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground">
          {t("magic_howBuilt")}
        </h3>
        <div className="flex gap-1 rounded-lg border border-border/50 bg-background/50 p-0.5 text-xs">
          {(
            [
              ["diagram", "magic_tabDiagram"],
              ["agent", "magic_tabAgent"],
              ["tools", "magic_tabTools"],
              ["mermaid", "magic_tabMermaid"],
            ] as const
          ).map(([key, labelKey]) => (
            <button
              key={key}
              type="button"
              onClick={() => onRevealTabChange(key)}
              className={`rounded-md border-0 px-2.5 py-1 transition ${
                revealTab === key
                  ? "bg-primary text-primary-foreground"
                  : "bg-transparent text-muted-foreground hover:text-foreground"
              }`}
            >
              {t(labelKey)}
            </button>
          ))}
        </div>
      </div>

      {revealTab === "diagram" ? (
        <ol className="space-y-3">
          {plan.steps.map((step, i) => (
            <li key={step.id} className="flex gap-3">
              <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
                {i + 1}
              </span>
              <div>
                <p className="text-sm font-medium">{step.label}</p>
                <p className="text-xs text-muted-foreground">{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
      ) : null}

      {revealTab === "agent" ? (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-3 py-1 font-medium text-primary">
              <Bot className="h-3.5 w-3.5 shrink-0" aria-hidden />
              {plan.agentName}
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-full border border-border/60 px-3 py-1 text-xs text-muted-foreground">
              <Cpu className="h-3.5 w-3.5 shrink-0" aria-hidden />
              {plan.model.available ? `${plan.model.label} · ${plan.model.model}` : t("magic_poolWarming")}
            </span>
          </div>
          {plan.model.fallbacks && plan.model.fallbacks.length > 0 ? (
            <p className="text-xs text-muted-foreground">
              {t("magic_failoverChain", { chain: plan.model.fallbacks.join(" → ") })}
            </p>
          ) : null}
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-xl border border-border/50 bg-background/60 p-3 text-xs leading-relaxed text-muted-foreground">
            {plan.agentSystemPrompt}
          </pre>
        </div>
      ) : null}

      {revealTab === "tools" ? (
        <div className="space-y-2">
          {plan.registryUnavailable ? (
            <p className="text-sm text-muted-foreground">{t("magic_registryUnavailable")}</p>
          ) : plan.tools.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("magic_noTools")}</p>
          ) : (
            plan.tools.map((tool) => (
              <div
                key={tool.name}
                className="flex items-start justify-between gap-3 rounded-xl border border-border/50 bg-background/50 px-3 py-2.5"
              >
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    <Wrench className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="truncate">{tool.name}</span>
                    {tool.free ? (
                      <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-emerald-500">
                        {t("magic_freeBadge")}
                      </span>
                    ) : (
                      <span className="rounded-full bg-amber-500/15 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-amber-500">
                        {t("magic_needsKeysBadge")}
                      </span>
                    )}
                  </p>
                  {tool.description ? (
                    <p className="mt-0.5 text-xs text-muted-foreground">{tool.description}</p>
                  ) : null}
                </div>
                {tool.repositoryUrl ? (
                  <a
                    href={tool.repositoryUrl}
                    target="_blank"
                    rel="noreferrer"
                    aria-label={t("magic_repoLinkAria", { name: tool.name })}
                    className="mt-1 text-muted-foreground transition hover:text-foreground"
                  >
                    <ExternalLink className="h-3.5 w-3.5 shrink-0" aria-hidden />
                  </a>
                ) : null}
              </div>
            ))
          )}
          <p className="pt-1 text-xs text-muted-foreground">{t("magic_registryNote")}</p>
        </div>
      ) : null}

      {revealTab === "mermaid" ? (
        <div>
          <pre className="max-h-64 overflow-auto rounded-xl border border-border/50 bg-background/60 p-3 text-xs leading-relaxed text-muted-foreground">
            {plan.mermaid}
          </pre>
          <button
            type="button"
            onClick={() => copyText(plan.mermaid, "mermaid")}
            className="mt-2 inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground transition hover:text-foreground"
          >
            {copied === "mermaid" ? (
              <Check className="h-3.5 w-3.5 shrink-0" aria-hidden />
            ) : (
              <Copy className="h-3.5 w-3.5 shrink-0" aria-hidden />
            )}
            {copied === "mermaid" ? t("magic_copied") : t("magic_copyMermaid")}
          </button>
        </div>
      ) : null}

      {plan.honestyNote ? (
        <p className="mt-4 rounded-xl border border-amber-500/25 bg-amber-500/5 px-3 py-2 text-xs text-muted-foreground">
          {plan.honestyNote}
        </p>
      ) : null}
    </div>
  );
}
