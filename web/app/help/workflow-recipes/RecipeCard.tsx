"use client";

// One recipe card for the workflow recipes page: Flow / Build-spec tabs and
// the copy-prompt button. Split out of WorkflowRecipesClient.tsx.

import { useState } from "react";
import { Zap, Wrench, ShieldCheck, Send, Copy, Check, Bot, Network, Coins, Cpu, GitBranch } from "lucide-react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import { NODE_META, type Recipe, type RecipeBuild } from "./recipes-types";
import { useTranslations } from "../../lib/i18n";

function TriggerRow({ label }: { label: string }) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/20 bg-primary/5 px-3 py-2">
      <span className="inline-flex items-center gap-1.5 font-mono text-xs font-semibold uppercase tracking-[0.14em] text-primary">
        <Zap className="size-3" aria-hidden="true" />
        Trigger
      </span>
      <span className="text-sm text-foreground">{label}</span>
    </div>
  );
}

// A tiny label used across the Build spec block — uppercase micro-label matching
// the page's existing type scale.
function SpecLabel({ children }: { children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
      {children}
    </span>
  );
}

// Icon-only copy-to-clipboard button for the system prompt. Uses the shared
// ghost Button; the p-0/border-0/bg-transparent trio keeps the global
// `button {}` padding rule from clipping the icon.
function CopyPromptButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      aria-label={copied ? "Prompt copied" : "Copy system prompt"}
      className="h-7 gap-1.5 border-0 bg-transparent px-2 text-muted-foreground hover:text-foreground"
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(
          () => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1500);
          },
          () => {
            /* clipboard blocked (e.g. insecure context) — no-op */
          }
        );
      }}
    >
      {copied ? (
        <Check className="size-3 text-primary" aria-hidden="true" />
      ) : (
        <Copy className="size-3" aria-hidden="true" />
      )}
      {copied ? "Copied" : "Copy"}
    </Button>
  );
}

// The practical "how to actually build it" layer, rendered as the Build-spec
// tab of a recipe. Visually distinct from the flow: a tinted inner panel so it
// reads as a separate "spec" block.
function BuildSpec({ build }: { build: RecipeBuild }) {
  const tHC = useTranslations("hcHelpWorkflowrecipesRecipeCard");
  return (
    <div className="space-y-4 rounded-xl border border-border bg-secondary/20 p-4">
      {/* Header + agent count */}
      <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
        <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-primary">
          {tHC("s1")}
        </span>
        <Badge variant="secondary" className="gap-1">
          <Bot className="size-3" aria-hidden="true" />
          {build.agents} {build.agents === 1 ? "agent" : "agents"}
        </Badge>
        {build.pattern && (
          <Badge variant="outline" className="gap-1">
            <Network className="size-3" aria-hidden="true" />
            {build.pattern}
          </Badge>
        )}
        {build.agentsNote && <span className="text-xs leading-relaxed text-muted-foreground">{build.agentsNote}</span>}
      </div>

      {/* System prompt (the supervisor/primary agent for multi-agent recipes) */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <SpecLabel>
            {build.agentRoles && build.agentRoles.length > 0 ? "Primary / supervisor prompt" : "System prompt"}
          </SpecLabel>
          <CopyPromptButton text={build.prompt} />
        </div>
        <pre className="max-h-64 overflow-x-auto overflow-y-auto whitespace-pre-wrap break-words rounded-lg border border-border bg-secondary/40 p-3 text-xs font-mono text-foreground">
          {build.prompt}
        </pre>
      </div>

      {/* Agent roster — per-agent role + model tier + prompt (multi-agent recipes) */}
      {build.agentRoles && build.agentRoles.length > 0 && (
        <div className="space-y-2">
          <SpecLabel>
            <Bot className="size-3" aria-hidden="true" />
            {tHC("s5")}
            {build.agentRoles.length})
          </SpecLabel>
          <div className="space-y-2">
            {build.agentRoles.map((a) => (
              <div key={a.role} className="rounded-lg border border-border bg-secondary/30 p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-foreground">{a.role}</span>
                  <Badge variant="outline" className="gap-1 text-[10px]">
                    <Cpu className="size-3" aria-hidden="true" />
                    {a.model}
                  </Badge>
                </div>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{a.prompt}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* MCP connectors + Tools */}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <SpecLabel>
            <Wrench className="size-3" aria-hidden="true" />
            {tHC("s4")}
          </SpecLabel>
          <div className="flex flex-wrap gap-1.5">
            {build.mcp.map((m) => (
              <Badge key={m} variant="secondary">
                {m}
              </Badge>
            ))}
          </div>
        </div>
        <div className="space-y-2">
          <SpecLabel>Tools</SpecLabel>
          <div className="flex flex-wrap gap-1.5">
            {build.tools.map((t) => (
              <Badge key={t} variant="outline">
                {t}
              </Badge>
            ))}
          </div>
        </div>
      </div>

      {/* Guardrails — the safety rails, made to read as important */}
      <div className="space-y-2">
        <SpecLabel>
          <ShieldCheck className="size-3 text-primary" aria-hidden="true" />
          Guardrails
        </SpecLabel>
        <ul className="space-y-1.5">
          {build.guardrails.map((g) => (
            <li key={g} className="flex gap-2 text-sm leading-relaxed text-foreground">
              <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              <span>{g}</span>
            </li>
          ))}
        </ul>
      </div>

      {/* Cost-effectiveness — model tiering, budgets, cost routing */}
      {build.cost && (
        <div className="space-y-1.5 rounded-lg border border-amber-500/25 bg-amber-500/5 p-3">
          <SpecLabel>
            <Coins className="size-3 text-amber-500" aria-hidden="true" />
            Cost-effectiveness
          </SpecLabel>
          <p className="text-sm leading-relaxed text-foreground/90">{build.cost}</p>
        </div>
      )}

      {/* Output */}
      <div className="space-y-1.5">
        <SpecLabel>
          <Send className="size-3" aria-hidden="true" />
          Output
        </SpecLabel>
        <p className="text-sm leading-relaxed text-muted-foreground">{build.output}</p>
      </div>
    </div>
  );
}

// The flow tab: trigger, the numbered vertical stepper, connectors, and the
// outcome/impact pair.
function RecipeFlow({ recipe }: { recipe: Recipe }) {
  const tHC = useTranslations("hcHelpWorkflowrecipesRecipeCard");
  return (
    <div className="space-y-5">
      {/* Trigger */}
      <TriggerRow label={recipe.triggerLabel} />

      {/* The flow — a numbered vertical stepper */}
      <div className="space-y-2.5">
        <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {tHC("s3")}
        </span>
        <ol className="space-y-0">
          {recipe.steps.map((step, i) => {
            const meta = NODE_META[step.kind];
            const Icon = meta.icon;
            const isLast = i === recipe.steps.length - 1;
            return (
              <li key={`${step.kind}-${i}`} className="relative flex gap-4">
                {/* Icon tile + connecting line */}
                <div className="flex flex-col items-center">
                  <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary ring-1 ring-inset ring-primary/20">
                    <Icon className="size-[18px]" aria-hidden="true" />
                  </div>
                  {!isLast && <div className="my-1 w-px flex-1 bg-border" aria-hidden="true" />}
                </div>
                {/* Content */}
                <div className={`min-w-0 flex-1 ${isLast ? "pb-0" : "pb-6"}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-[11px] font-semibold tabular-nums text-muted-foreground">
                      {String(i + 1).padStart(2, "0")}
                    </span>
                    <Badge variant="outline" className="gap-1 px-2 py-0 text-[10px] font-medium">
                      <Icon className="size-3 text-primary" aria-hidden="true" />
                      {meta.label}
                    </Badge>
                  </div>
                  <div className="mt-1.5 text-sm font-medium text-foreground">{step.title}</div>
                  <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">{step.detail}</p>
                </div>
              </li>
            );
          })}
        </ol>
      </div>

      {/* Connectors */}
      <div className="space-y-2">
        <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {tHC("s2")}
        </span>
        <div className="flex flex-wrap gap-1.5">
          {recipe.connectors.map((c) => (
            <Badge key={c} variant="secondary">
              {c}
            </Badge>
          ))}
        </div>
      </div>

      {/* Outcome + impact */}
      <div className="grid gap-3 border-t border-border pt-4 sm:grid-cols-2">
        <div className="space-y-1">
          <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-primary">Outcome</span>
          <p className="text-sm leading-relaxed text-foreground">{recipe.outcome}</p>
        </div>
        <div className="space-y-1">
          <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
            Impact
          </span>
          <p className="text-sm leading-relaxed text-muted-foreground">{recipe.impact}</p>
        </div>
      </div>
    </div>
  );
}

export function RecipeCard({ recipe }: { recipe: Recipe }) {
  const tHC = useTranslations("hcHelpWorkflowrecipesRecipeCard");
  // Split the reading surface into Flow vs Build spec tabs so an open recipe is
  // a scannable two-view panel instead of one long scroll. Flow-only recipes
  // (no `build`) render the flow directly, no tab chrome.
  const [tab, setTab] = useState("flow");
  const hasBuild = !!recipe.build;

  return (
    <Card className="gap-0 overflow-hidden py-0">
      <CardContent className="space-y-5 p-5 sm:p-6">
        {/* Title + scenario */}
        <div className="space-y-1.5">
          <h4 className="text-base font-semibold tracking-tight text-foreground">{recipe.title}</h4>
          <p className="flex flex-col gap-0.5 text-sm leading-relaxed text-muted-foreground">
            <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
              Scenario
            </span>
            {recipe.scenario}
          </p>
        </div>

        {hasBuild ? (
          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className="w-full max-w-sm">
              <TabsTrigger value="flow">
                <GitBranch className="size-3.5" aria-hidden="true" />
                Flow
              </TabsTrigger>
              <TabsTrigger value="build">
                <Wrench className="size-3.5" aria-hidden="true" />
                {tHC("s1")}
              </TabsTrigger>
            </TabsList>
            <TabsContent value="flow" className="mt-1">
              <RecipeFlow recipe={recipe} />
            </TabsContent>
            <TabsContent value="build" className="mt-1">
              {recipe.build && <BuildSpec build={recipe.build} />}
            </TabsContent>
          </Tabs>
        ) : (
          <RecipeFlow recipe={recipe} />
        )}
      </CardContent>
    </Card>
  );
}
