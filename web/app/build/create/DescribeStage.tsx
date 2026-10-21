"use client";

import { ArrowRight, Bot, Check, Cpu, Dices, GitBranch, Loader2, Rocket, Wand2 } from "lucide-react";
import { Fragment } from "react";
import { motion } from "motion/react";
import { cn } from "../../lib/utils";
import { RECIPES } from "../../lib/capabilities";
import { Button } from "../../components/ui/button";
import { Textarea } from "../../components/ui/textarea";
import { pickSurprise } from "../surprisePrompts";
import { analysePrompt, applyHint } from "../promptHints";
import { radioGroupKeyDown } from "../radioNav";
import { FLIGHT_PATH, RECIPE_ICON } from "./createShared";
import type { OutputKind } from "../../lib/build/blueprint";


/** Stage 1: describe what to build. */
export default function DescribeStage({
  goal,
  setGoal,
  output,
  switchOutput,
  drafting,
  edgeModelName,
  onDraft,
  onPickRecipe,
}: {
  goal: string;
  setGoal: (update: string | ((g: string) => string)) => void;
  output: OutputKind;
  switchOutput: (out: OutputKind) => void;
  drafting: boolean;
  /** Set only when on-device drafting is ready. */
  edgeModelName: string | null;
  onDraft: (text: string, out: OutputKind) => void;
  onPickRecipe: (recipe: (typeof RECIPES)[number]) => void;
}) {
  const strength = analysePrompt(goal);
  const edgeReady = edgeModelName !== null;
  return (
    <motion.div key="describe" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }} transition={{ duration: 0.2 }} className="space-y-5">
      <div className="relative overflow-hidden rounded-2xl border border-border bg-card p-5 sm:p-6">
        <div className="pointer-events-none absolute -right-16 -top-16 size-56 rounded-full bg-primary/10 blur-3xl" aria-hidden />
        <div className="relative space-y-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 className="flex items-center gap-2 text-2xl font-semibold tracking-tight text-foreground">
                <Bot aria-hidden="true" className="size-6 text-primary" /> Build the agent behind what comes next
              </h2>
              <p className="mt-1 text-sm text-muted-foreground">
                Start with a custom agent - the intelligence at the center. Tune how it thinks and acts, connect a workflow, then use it to power apps, channels and customer experiences.
              </p>
            </div>
            {/* l16-9 teaches what this surface produces: a brief applied
                as an envoy on the trust ladder. */}
          </div>

          <div
            data-testid="agent-first-flight-path"
            className="grid gap-2 sm:grid-cols-[1fr_auto_1fr_auto_1fr] sm:items-stretch"
          >
            {FLIGHT_PATH.map(({ step, title, text, Icon, primary }, i) => (
              <Fragment key={step}>
                <div
                  className={cn(
                    "flex h-full flex-col gap-1.5 rounded-xl border p-3",
                    primary ? "border-primary bg-primary/10" : "border-border bg-muted/30"
                  )}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className={cn("text-[10px] font-semibold uppercase tracking-wider", primary ? "text-primary" : "text-muted-foreground")}>
                      {step}
                    </span>
                    <span className={cn("flex size-7 items-center justify-center rounded-lg", primary ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground")}>
                      <Icon className="size-4" />
                    </span>
                  </div>
                  <div className="text-sm font-semibold text-foreground">{title}</div>
                  <div className="text-xs leading-relaxed text-muted-foreground">{text}</div>
                </div>
                {i < FLIGHT_PATH.length - 1 && (
                  <div className="flex items-center justify-center text-muted-foreground" aria-hidden>
                    <ArrowRight aria-hidden="true" className="size-4 rotate-90 sm:rotate-0" />
                  </div>
                )}
              </Fragment>
            ))}
          </div>

          {/* eslint-disable-next-line jsx-a11y/interactive-supports-focus -- roving tabindex lives on the radios; arrow keys bubble to the group */}
          <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="What to create" onKeyDown={radioGroupKeyDown}>
            {(
              [
                { id: "agent", label: "A custom AI agent", hint: "The starting point - answers, researches, writes, follows up and can safely use tools.", Icon: Bot },
                { id: "app", label: "An app or website", hint: "A Git-backed project you own. For apps powered by agents and workflows, use the AI app studio tab.", Icon: GitBranch },
              ] as const
            ).map(({ id, label, hint, Icon }) => (
              <button
                key={id}
                type="button"
                role="radio"
                aria-checked={output === id}
                tabIndex={output === id ? 0 : -1}
                onClick={() => switchOutput(id)}
                className={cn(
                  "flex items-start gap-3 rounded-xl border p-3 text-left transition-all",
                  output === id
                    ? "border-primary bg-primary/10 shadow-[0_0_0_3px_color-mix(in_srgb,var(--primary)_14%,transparent)]"
                    : "border-border hover:border-primary/50 hover:bg-accent/40"
                )}
              >
                <span className={cn("flex size-9 shrink-0 items-center justify-center rounded-lg", output === id ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground")}>
                  <Icon className="size-5" />
                </span>
                <span className="min-w-0">
                  <span className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
                    {label} {output === id && <Check aria-hidden="true" className="size-4 text-primary" />}
                  </span>
                  <span className="mt-0.5 block text-xs leading-relaxed text-muted-foreground">{hint}</span>
                </span>
              </button>
            ))}
          </div>

          <Textarea
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent?.isComposing) {
                e.preventDefault();
                onDraft(goal, output);
              }
            }}
            placeholder={
              output === "agent"
                ? "e.g. Answer customer questions about our pricing in a friendly tone, and pass refunds to me"
                : "e.g. A landing page for my bakery with a photo grid and an order form"
            }
            className="min-h-28 text-base"
            aria-label="Describe what you want to build"
            aria-describedby="goal-strength"
          />

          {/* Live prompt-strength hint: pure, instant, no model call. */}
          <div id="goal-strength" className="space-y-1.5" aria-live="polite" data-testid="prompt-strength">
            {strength.strength === "empty" ? null : strength.hints.length === 0 ? (
              <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Check className="size-3.5 text-[var(--state-success)]" aria-hidden />
                {strength.strength === "strong" ? "Strong prompt: it says who it is for and what it should do." : "Good start."}
              </p>
            ) : (
              <>
                <p className="text-xs text-muted-foreground">
                  <span className="font-medium text-foreground">Prompt strength: {strength.strength === "weak" ? "needs a little more" : "almost there"}.</span>{" "}
                  {strength.hints[0].message}
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {strength.hints.map((h) => (
                    <button
                      key={h.id}
                      type="button"
                      onClick={() => setGoal((g) => applyHint(g, h))}
                      title={h.addition.trim()}
                      className="inline-flex min-h-8 items-center gap-1 rounded-full border border-border px-2.5 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                    >
                      + {h.chipLabel}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button size="lg" onClick={() => onDraft(goal, output)} disabled={!goal.trim() || drafting}>
              {drafting ? <Loader2 aria-hidden="true" className="size-4 animate-spin" /> : <Wand2 aria-hidden="true" className="size-4" />}
              {drafting ? "Drafting your blueprint…" : "Draft my blueprint"}
              {!drafting && <ArrowRight aria-hidden="true" className="size-4" />}
            </Button>
            <Button type="button" size="lg" variant="outline" disabled={drafting} onClick={() => setGoal((g) => pickSurprise(g))}>
              <Dices aria-hidden="true" className="size-4" /> Surprise me
            </Button>
            {!goal.trim() && <span className="text-xs text-muted-foreground">Type a sentence above, or pick an idea below.</span>}
            <span className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground">
              <Cpu aria-hidden="true" className="size-3.5" />
              {edgeReady && output === "agent"
                ? `Private on-device drafting ready (${edgeModelName})`
                : "AI reads your description and writes the blueprint"}
            </span>
          </div>
        </div>
      </div>

      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Not sure? Start from an idea</p>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {RECIPES.slice(0, 6).map((r) => {
            const Icon = RECIPE_ICON[r.id] ?? Rocket;
            return (
              <button
                key={r.id}
                type="button"
                disabled={drafting}
                onClick={() => {
 if (drafting) return;
 onPickRecipe(r);
 }}
                className="group flex items-center gap-3 rounded-xl border border-border bg-card p-3 text-left transition-all hover:-translate-y-0.5 hover:border-primary/60 hover:shadow-md disabled:pointer-events-none disabled:opacity-50"
              >
                <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                  <Icon className="size-[18px]" />
                </span>
                <span className="min-w-0 flex-1 text-sm font-medium text-foreground">{r.title}</span>
                <ArrowRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
              </button>
            );
          })}
        </div>
      </div>

      <details className="rounded-xl border border-border bg-card px-4 py-3 text-sm">
        <summary className="cursor-pointer font-medium text-foreground">New here? What is an agent, and what happens next?</summary>
        <div className="mt-2 space-y-2 text-xs leading-relaxed text-muted-foreground">
          <p>
            <span className="font-semibold text-foreground">An agent</span> is an AI worker with a job description. Unlike a chatbot it can look things up, use tools and finish a task - and it stops to ask you before anything risky.
          </p>
          <p>
            <span className="font-semibold text-foreground">Nothing is created until you press Build.</span> First you get a blueprint - every setting written out in plain words, all editable. New agents start &ldquo;under watch&rdquo; and earn more freedom as they prove themselves.
          </p>
        </div>
      </details>
    </motion.div>
  );
}
