"use client";

// Process Theater: big, long-running processes played as an animated run.
//
// Pick a process; a playhead walks its real node types left to right, the run
// clock jumps from seconds to days, and "waiting" steps visibly park (that is
// the point of agentic workflows: the run survives the wait). Under the track,
// a terminal-style log says what happens technically at each step.
//
// It is an ILLUSTRATION and says so. The live part is "Design this for my
// business", which hands the scenario's prompt to the sandbox agent.

import { useEffect, useState } from "react";
import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
  Bot,
  Braces,
  CheckCircle2,
  Flag,
  GitBranch,
  GitFork,
  Globe,
  Hourglass,
  Library,
  Network,
  Play,
  Repeat,
  ShieldCheck,
  Split,
  Workflow,
  Wrench,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { NODE_LABEL, PROCESS_SCENARIOS, type ProcessNode, type ProcessScenario } from "../../lib/processScenarios";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";

// Exported: /try's ScenarioBuild reuses the same icon-per-node-type map so
// the build theatre and the theater here speak one visual language.
export const NODE_ICON: Record<ProcessNode, LucideIcon> = {
  trigger: Zap,
  agent: Bot,
  supervisor: Network,
  parallel: Split,
  fan_out: GitFork,
  loop: Repeat,
  condition: GitBranch,
  approval: ShieldCheck,
  tool: Wrench,
  http: Globe,
  code: Braces,
  knowledge: Library,
  workflow: Workflow,
  output: Flag,
};

type Props = {
  /** Hand the scenario to the sandbox agent. Omit on surfaces with no sandbox. */
  onDesign?: (scenario: ProcessScenario) => void;
  busy?: boolean;
  /** Surfaces with no sandbox (the landing): where "try it live" goes. */
  ctaHref?: string;
};

export default function ProcessTheater({ onDesign, busy = false, ctaHref }: Props) {
    const tHC = useTranslations("hcComponentsCapabilitiesProcessTheater");
  const reduce = useReducedMotion();
  const [scenarioId, setScenarioId] = useState(PROCESS_SCENARIOS[0].id);
  const [at, setAt] = useState(0);
  const [playing, setPlaying] = useState(true);
  const scenario = PROCESS_SCENARIOS.find((s) => s.id === scenarioId) ?? PROCESS_SCENARIOS[0];
  const last = scenario.steps.length - 1;

  useEffect(() => {
    if (reduce || !playing) return undefined;
    // A waiting step holds longer: the pause is the thing being shown.
    const hold = at === last ? 3200 : scenario.steps[at].waits ? 2600 : 1300;
    const timer = window.setTimeout(() => setAt((i) => (i >= last ? 0 : i + 1)), hold);
    return () => window.clearTimeout(timer);
  }, [at, last, playing, reduce, scenario]);

  const pick = (id: string) => {
    setScenarioId(id);
    setAt(0);
    setPlaying(true);
  };

  const step = scenario.steps[at];
  const shown = reduce ? scenario.steps : scenario.steps.slice(0, at + 1);

  return (
    <section
      aria-labelledby="process-theater-heading"
      className="overflow-hidden rounded-xl border border-border bg-card"
    >
      <div className="p-5 sm:p-6">
        <p className="text-xs font-semibold uppercase tracking-wide text-primary">{tHC("s8")}</p>
        <h2
          id="process-theater-heading"
          className="mt-1 text-xl font-semibold leading-snug text-foreground sm:text-2xl"
        >
          {tHC("s7")}
        </h2>
        <p className="mt-2 max-w-3xl text-sm text-muted-foreground">
          {tHC("s6")}
        </p>

        <div role="group" aria-label="Process" className="mt-4 flex flex-wrap gap-1.5">
          {PROCESS_SCENARIOS.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-pressed={s.id === scenario.id}
              onClick={() => pick(s.id)}
              className={cn(
                "rounded-full border px-3 py-1.5 text-xs font-medium shadow-none transition-colors",
                s.id === scenario.id
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border bg-transparent text-foreground hover:bg-accent"
              )}
            >
              <span className={s.id === scenario.id ? "text-primary-foreground" : "text-muted-foreground"}>
                {s.audience}
              </span>{" "}
              · {s.title}
            </button>
          ))}
        </div>
      </div>

      <div className="border-t border-border bg-background/60 p-5 sm:p-6">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <p className="min-w-0 text-sm text-foreground">{scenario.pitch}</p>
          <span className="shrink-0 rounded-full border border-border px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
            {scenario.span}
          </span>
        </div>

        {/* The track */}
        <ol className="mt-5 flex items-stretch gap-0 overflow-x-auto pb-2">
          {scenario.steps.map((s, i) => {
            const Icon = NODE_ICON[s.node];
            const state = reduce ? "done" : i < at ? "done" : i === at ? "active" : "todo";
            const parked = state === "active" && s.waits;
            return (
              <li key={`${scenario.id}-${i}`} className="flex min-w-[7.5rem] flex-1 items-start">
                <button
                  type="button"
                  onClick={() => {
                    setAt(i);
                    setPlaying(false);
                  }}
                  aria-current={state === "active" ? "step" : undefined}
                  className="group flex w-full flex-col items-center gap-1.5 border-0 bg-transparent p-0 text-center shadow-none"
                >
                  <span className="relative flex w-full items-center">
                    <span
                      className={cn(
                        "h-0.5 flex-1",
                        i === 0 ? "bg-transparent" : state === "todo" ? "bg-border" : "bg-primary"
                      )}
                    />
                    <motion.span
                      animate={reduce ? undefined : { scale: state === "active" ? 1.15 : 1 }}
                      transition={{ type: "spring", stiffness: 300, damping: 18 }}
                      className={cn(
                        "relative flex size-10 shrink-0 items-center justify-center rounded-xl border",
                        state === "active"
                          ? parked
                            ? "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
                            : "border-primary bg-primary text-primary-foreground"
                          : state === "done"
                            ? "border-primary/50 bg-primary/10 text-foreground"
                            : "border-border bg-card text-muted-foreground"
                      )}
                    >
                      {parked ? <Hourglass size={16} aria-hidden="true" /> : <Icon size={16} />}
                      {state === "active" && !reduce && (
                        <motion.span
                          className={cn(
                            "absolute inset-0 rounded-xl border",
                            parked ? "border-[var(--warning-border)]" : "border-primary"
                          )}
                          animate={{ scale: [1, 1.6], opacity: [0.7, 0] }}
                          transition={{ duration: parked ? 1.8 : 1, repeat: Infinity, ease: "easeOut" }}
                        />
                      )}
                    </motion.span>
                    <span
                      className={cn(
                        "h-0.5 flex-1",
                        i === last ? "bg-transparent" : i < at || reduce ? "bg-primary" : "bg-border"
                      )}
                    />
                  </span>
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    {NODE_LABEL[s.node]}
                  </span>
                  <span
                    className={cn(
                      "px-1 text-xs leading-tight",
                      state === "todo" ? "text-muted-foreground" : "text-foreground"
                    )}
                  >
                    {s.label}
                  </span>
                </button>
              </li>
            );
          })}
        </ol>

        {/* Run log */}
        <div className="mt-4 rounded-lg border border-border bg-card font-mono text-xs leading-relaxed">
          <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-1.5">
            <span className="text-muted-foreground">{tHC("s5")}</span>
            <span
              className={cn(
                "inline-flex items-center gap-1",
                step.waits ? "text-[var(--warning-text)]" : "text-primary"
              )}
            >
              {step.waits ? (
                <Hourglass size={11} aria-hidden="true" />
              ) : at === last ? (
                <CheckCircle2 size={11} aria-hidden="true" />
              ) : (
                <Play size={11} aria-hidden="true" />
              )}
              {step.waits ? "parked, nothing running" : at === last ? "complete" : "running"} · {step.clock}
            </span>
          </div>
          <ul className="max-h-44 space-y-0.5 overflow-y-auto px-3 py-2">
            <AnimatePresence initial={false}>
              {shown.map((s, i) => (
                <motion.li
                  key={`${scenario.id}-log-${i}`}
                  initial={reduce ? false : { opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  className="flex gap-2"
                >
                  <span className="w-20 shrink-0 text-muted-foreground">{s.clock}</span>
                  <span className="w-24 shrink-0 text-primary">{NODE_LABEL[s.node].toLowerCase()}</span>
                  <span className="min-w-0 text-foreground">{s.tech}</span>
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <p className="min-w-0 text-xs text-muted-foreground">
            <span className="font-medium text-foreground">{tHC("s4")}</span> {scenario.needs}{tHC("s3")}
          </p>
          <div className="flex shrink-0 gap-2">
            {!reduce && (
              <button
                type="button"
                onClick={() => setPlaying((p) => !p)}
                className="rounded-md border border-border bg-transparent px-3 py-1.5 text-xs font-medium text-foreground shadow-none hover:bg-accent"
              >
                {playing ? "Pause" : "Play"}
              </button>
            )}
            {!onDesign && ctaHref && (
              <Link
                href={ctaHref}
                className="inline-flex items-center gap-1.5 rounded-md border border-primary bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90"
              >
                <Bot size={13} aria-hidden="true" /> {tHC("s2")}
              </Link>
            )}
            {onDesign && (
              <button
                type="button"
                disabled={busy}
                onClick={() => onDesign(scenario)}
                className="inline-flex items-center gap-1.5 rounded-md border border-primary bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground shadow-none hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Bot size={13} aria-hidden="true" /> {tHC("s1")}
              </button>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
