"use client";

import Link from "next/link";
import { motion } from "motion/react";
import {
  ArrowRight,
  Check,
  CalendarClock,
  Languages,
  Mail,
  MessagesSquare,
  Plug,
  Scale,
  ShieldCheck,
  Workflow,
  Cpu,
  Mic,
  KeyRound,
  Network,
  Minus,
  RefreshCw,
} from "lucide-react";
import { Button } from "../ui/button";
import { PLATFORM_FACTS, ENTITY_FACTS } from "../../lib/platform-facts";
import { TONES } from "./studioTheme";

// Below the studio: the two things a sceptical visitor needs before they
// commit. First, exactly what was real and what was not (the ledger - the
// fastest way to earn trust is to say the limits first). Second, what a free
// workspace adds that the sandbox cannot. Numbers are PLATFORM_FACTS only; the
// do-not-claim list in that file (no capacity, latency, uptime or ROI numbers)
// applies here unchanged.

const REAL = [
  "Every agent runs on a real platform model with real tools - web search, page reading, API calls, code execution.",
  "Documents are searched, and answers name the passage they came from.",
  "The approval pause is a real pause: the run waits for you.",
  "The share link and QR open a real chat on a real phone.",
  "Signing up copies every agent you built into your own workspace.",
];

const NOT_YET = [
  "Runs use the platform's lowest-cost model under fair-use limits - judge the mechanics, not the ceiling.",
  "The sandbox is temporary and resets; a workspace is permanent.",
  "Documents here are text; PDF, Word and scans (with OCR) come with a workspace.",
  "MCP servers are searchable here and connectable in a workspace.",
  "Schedules, triggers and sending on your own channels are workspace features.",
];

const ADDS = [
  {
    icon: MessagesSquare,
    title: `${PLATFORM_FACTS.channels} channels`,
    body: `${PLATFORM_FACTS.freeChannels.join(", ")} out of the box; Slack, Discord, Instagram, Messenger, X and voice with your own credentials.`,
  },
  {
    icon: CalendarClock,
    title: "Schedules and triggers",
    body: "Run every morning at a time you choose, or when a message arrives - without you opening anything.",
  },
  {
    icon: Workflow,
    title: `${PLATFORM_FACTS.workflowNodeTypes}-node visual builder`,
    body: "Branches, loops, human approvals and agents, drawn instead of coded - your sandbox chains are the same shape.",
  },
  {
    icon: Plug,
    title: "MCP in both directions",
    body: `Connect MCP servers to your agents, and call your agents as MCP tools (${PLATFORM_FACTS.mcpServerTools} tools exposed).`,
  },
  {
    icon: Cpu,
    title: `${PLATFORM_FACTS.byokProviders} model providers`,
    body: "Use the platform's hosted models, or bring your own key for any of them. You pick per agent.",
  },
  {
    icon: ShieldCheck,
    title: "Approvals, audit, budgets",
    body: "Every consequential action can wait for a human, is logged, and runs under per-user spending limits.",
  },
  {
    icon: Languages,
    title: `${PLATFORM_FACTS.interfaceLocales} interface languages`,
    body: `Including all ${PLATFORM_FACTS.indianLanguages} scheduled Indian languages. Interface languages - not a promise of speech in each.`,
  },
  {
    icon: Network,
    title: "Agent-to-agent",
    body: "A2A and AG-UI support: agents that call other agents, across teams.",
  },
  { icon: Mic, title: "Voice channel", body: "Talk to the agent and hear it answer, alongside the text channels." },
  {
    icon: KeyRound,
    title: "Isolated by tenant",
    body: "Schema-per-tenant data isolation on PostgreSQL - your documents are yours alone.",
  },
];

export default function AfterSandbox({
  signupHref,
  onRestart,
  hasAgent,
}: {
  signupHref: string;
  onRestart: () => void;
  hasAgent: boolean;
}) {
  return (
    <div className="mt-28 space-y-24">
      {/* The ledger */}
      <section aria-labelledby="ledger-title">
        <div className="max-w-2xl">
          <div className="mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-amber-700 dark:text-amber-300">
            <Scale aria-hidden="true" className="h-4 w-4 shrink-0" /> The honest ledger
          </div>
          <h2 id="ledger-title" className="text-3xl font-semibold tracking-tight sm:text-4xl">
            What was real here - and what was not.
          </h2>
          <p className="mt-3 text-muted-foreground">
            We would rather you know the limits from us than find them later. Nothing on this page is staged: what you
            ran is what you get.
          </p>
        </div>
        <div className="mt-8 grid gap-4 md:grid-cols-2">
          <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/5 p-5">
            <div className={`mb-3 text-sm font-semibold ${TONES.emerald.text}`}>Real, in this page</div>
            <ul className="space-y-2.5">
              {REAL.map((r) => (
                <li key={r} className="flex items-start gap-2.5 text-sm">
                  <Check
                    aria-hidden="true"
                    className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400"
                  />{" "}
                  {r}
                </li>
              ))}
            </ul>
          </div>
          <div className="rounded-2xl border border-amber-500/30 bg-amber-500/5 p-5">
            <div className={`mb-3 text-sm font-semibold ${TONES.amber.text}`}>Not here - and where it lives</div>
            <ul className="space-y-2.5">
              {NOT_YET.map((r) => (
                <li key={r} className="flex items-start gap-2.5 text-sm">
                  <Minus aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />{" "}
                  {r}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {/* What a workspace adds */}
      <section aria-labelledby="adds-title">
        <div className="max-w-2xl">
          <div className="mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-violet-700 dark:text-violet-300">
            <Mail aria-hidden="true" className="h-4 w-4 shrink-0" /> Past the sandbox
          </div>
          <h2 id="adds-title" className="text-3xl font-semibold tracking-tight sm:text-4xl">
            The same agent - with the doors unlocked.
          </h2>
          <p className="mt-3 text-muted-foreground">
            What you built moves in with you. Here is what the workspace adds around it.
          </p>
        </div>
        <div className="mt-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {ADDS.map((a, i) => (
            <motion.div
              key={a.title}
              initial={{ opacity: 0, y: 14 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-30px" }}
              transition={{ delay: (i % 5) * 0.06 }}
              className="rounded-2xl border border-border/60 bg-background/60 p-4 backdrop-blur"
            >
              <span className={`mb-2 inline-flex h-8 w-8 items-center justify-center rounded-lg ${TONES.violet.soft}`}>
                <a.icon className="h-4 w-4" />
              </span>
              <div className="text-sm font-semibold">{a.title}</div>
              <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{a.body}</p>
            </motion.div>
          ))}
        </div>
      </section>

      {/* The close */}
      <section
        aria-labelledby="close-title"
        className="relative overflow-hidden rounded-3xl border border-violet-500/30 bg-gradient-to-br from-violet-600/15 via-background to-sky-500/15 p-8 text-center sm:p-14"
      >
        <div
          aria-hidden
          className="pointer-events-none absolute -top-24 left-1/2 h-64 w-[560px] -translate-x-1/2 rounded-full bg-violet-500/20 blur-3xl"
        />
        <h2 id="close-title" className="relative mx-auto max-w-2xl text-3xl font-semibold tracking-tight sm:text-5xl">
          {hasAgent
            ? "Your agent is built. Do not let it evaporate."
            : "The fastest way to understand agents is to build one."}
        </h2>
        <p className="relative mx-auto mt-4 max-w-xl text-muted-foreground">
          {hasAgent
            ? "Create a free account and every agent, mission and rule from this sandbox is copied into your workspace on the spot."
            : "Two minutes, an empty page and one job you actually care about. No signup, no card, nothing pre-built."}
        </p>
        <div className="relative mt-8 flex flex-wrap items-center justify-center gap-3">
          <Button size="lg" asChild className={TONES.violet.cta}>
            <Link href={signupHref}>
              {hasAgent ? "Create my workspace" : "Create a free account"}{" "}
              <ArrowRight aria-hidden="true" className="ms-2 h-4 w-4 shrink-0" />
            </Link>
          </Button>
          <Button size="lg" variant="outline" onClick={onRestart}>
            <RefreshCw aria-hidden="true" className="me-2 h-4 w-4 shrink-0" />{" "}
            {hasAgent ? "Try a different idea" : "Start building"}
          </Button>
        </div>
        <p className="relative mt-5 text-xs text-muted-foreground">
          Questions first? Write to{" "}
          <a
            className="underline underline-offset-2 hover:text-foreground"
            href={`mailto:${ENTITY_FACTS.contactEmail}`}
          >
            {ENTITY_FACTS.contactEmail}
          </a>
          .
        </p>
      </section>
    </div>
  );
}
