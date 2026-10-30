"use client";

import { useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "motion/react";
import { ArrowRight, Check, Circle, X, Trophy } from "lucide-react";
import { TONES } from "./studioTheme";
import { useTranslations } from "../../lib/i18n";

// Conversion, done as progress rather than pressure. The visitor sees how far
// through the journey they are (five things they really did), and the offer
// changes with it: "keep it" the moment there is something worth keeping, and
// "make it permanent" once they have put it on a phone. The account carries
// every agent over (the ?demo=<token> seam) - the bar says exactly that, and
// nothing else.

export type LaunchProgress = {
  built: boolean;
  armed: boolean;
  ran: boolean;
  chained: boolean;
  shipped: boolean;
};

const STEPS: { key: keyof LaunchProgress; label: string }[] = [
  { key: "built", label: "Agent built" },
  { key: "armed", label: "Given hands" },
  { key: "ran", label: "Ran a mission" },
  { key: "chained", label: "Chained + approval" },
  { key: "shipped", label: "On a phone" },
];

export function countDone(p: LaunchProgress): number {
  return STEPS.reduce((n, s) => n + (p[s.key] ? 1 : 0), 0);
}

/** Sidebar checklist: the journey as five ticks. */
export function LaunchChecklist({ progress }: { progress: LaunchProgress }) {
    const tHC = useTranslations("hcComponentsStudioConversion");
  const n = countDone(progress);
  return (
    <div className="rounded-xl border border-border/60 p-3" data-tour="launch-checklist">
      <div className="mb-2 flex items-center justify-between text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        <span>{tHC("s3")}</span>
        <span className="text-foreground">
          {n}/{STEPS.length}
        </span>
      </div>
      <div
        className="mb-2.5 h-1.5 overflow-hidden rounded-full bg-muted"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={STEPS.length}
        aria-valuenow={n}
        aria-label={tHC("s2")}
      >
        <motion.div
          className="h-full rounded-full bg-gradient-to-r from-violet-500 via-emerald-500 to-sky-500"
          initial={false}
          animate={{ width: `${(n / STEPS.length) * 100}%` }}
          transition={{ type: "spring", stiffness: 120, damping: 20 }}
        />
      </div>
      <ul className="space-y-1.5">
        {STEPS.map((s) => {
          const on = progress[s.key];
          return (
            <li
              key={s.key}
              className={`flex items-center gap-2 text-xs ${on ? "text-foreground" : "text-muted-foreground"}`}
            >
              {on ? (
                <Check aria-hidden="true" className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400 shrink-0" />
              ) : (
                <Circle className="h-3.5 w-3.5 opacity-70 shrink-0" aria-hidden="true" />
              )}
              <span>{s.label}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Floating "keep it" bar. Appears once there is an agent, dismissible, hidden while the tour runs. */
export function ConvertBar({
  progress,
  agentName,
  signupHref,
  hidden,
}: {
  progress: LaunchProgress;
  agentName: string | null;
  signupHref: string;
  hidden: boolean;
}) {
    const tHC = useTranslations("hcComponentsStudioConversion");
  const [dismissed, setDismissed] = useState(false);
  const n = countDone(progress);
  const show = n > 0 && !dismissed && !hidden;

  const copy =
    n >= 5
      ? {
          head: "You shipped an agent.",
          sub: "Make it permanent - it, and everything you built, moves into your own workspace.",
          cta: "Keep it - free",
        }
      : n >= 3
        ? {
            head: `${agentName ?? "Your agent"} is working.`,
            sub: "This sandbox is temporary. A free account keeps it and everything you gave it.",
            cta: "Keep it - free",
          }
        : {
            head: `${agentName ?? "Your agent"} exists.`,
            sub: "Your sandbox is temporary - a free account moves the agent into a workspace of its own.",
            cta: "Keep it - free",
          };

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          initial={{ y: 80, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 80, opacity: 0 }}
          transition={{ type: "spring", stiffness: 220, damping: 24 }}
          className="fixed inset-x-3 bottom-3 z-40 mx-auto flex max-w-3xl items-center gap-3 rounded-2xl border border-violet-500/40 bg-background/90 p-3 ps-4 shadow-2xl shadow-violet-500/20 backdrop-blur-xl"
          role="region"
          aria-label={tHC("s1")}
        >
          <span
            className={`hidden h-9 w-9 shrink-0 items-center justify-center rounded-full sm:inline-flex ${TONES.violet.solid}`}
          >
            {n >= 5 ? (
              <Trophy aria-hidden="true" className="h-4 w-4 shrink-0" />
            ) : (
              <span className="text-xs font-semibold">{n}/5</span>
            )}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium">{copy.head}</div>
            <div className="line-clamp-2 text-xs text-muted-foreground">{copy.sub}</div>
          </div>
          <Link
            href={signupHref}
            className={`inline-flex shrink-0 items-center gap-1.5 rounded-xl px-4 py-2 text-sm font-medium ${TONES.violet.cta}`}
          >
            {copy.cta} <ArrowRight aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
          </Link>
          <button
            type="button"
            onClick={() => setDismissed(true)}
            aria-label="Dismiss"
            className="rounded-lg p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <X aria-hidden="true" className="h-4 w-4 shrink-0" />
          </button>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
