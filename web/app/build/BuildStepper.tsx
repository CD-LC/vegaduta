"use client";

// The four-stage journey across the top of Create: Describe -> Blueprint -> Build -> Live.
// Done stages show a tick, the current one pulses, and the connecting line fills as
// you go - so where you are and what is next is never a guess.

import { motion } from "motion/react";
import { Check } from "lucide-react";
import { cn } from "../lib/utils";

const STAGES = [
  { id: "describe", label: "Describe", hint: "Say what you need" },
  { id: "blueprint", label: "Blueprint", hint: "Review the plan" },
  { id: "build", label: "Build", hint: "We create it" },
  { id: "live", label: "Live", hint: "Use it" },
] as const;

export default function BuildStepper({ index, failed }: { index: number; failed?: boolean }) {
  return (
    <ol className="relative grid grid-cols-4 gap-2" aria-label="Build journey">
      {/* rail */}
      <div
        className="pointer-events-none absolute start-[12.5%] end-[12.5%] top-4 h-0.5 rounded-full bg-border"
        aria-hidden
      />
      <motion.div
        className="pointer-events-none absolute left-[12.5%] top-4 h-0.5 rounded-full bg-primary"
        aria-hidden
        initial={false}
        animate={{ width: `${(Math.min(index, 3) / 3) * 75}%` }}
        transition={{ type: "spring", stiffness: 140, damping: 22 }}
      />
      {STAGES.map((s, i) => {
        const done = i < index;
        const current = i === index;
        const bad = failed && current;
        return (
          <li
            key={s.id}
            className="relative flex flex-col items-center text-center"
            aria-current={current ? "step" : undefined}
          >
            <span
              className={cn(
                "relative z-10 flex size-8 items-center justify-center rounded-full border-2 text-xs font-semibold transition-colors",
                done && "border-primary bg-primary text-primary-foreground",
                current &&
                  !bad &&
                  "border-primary bg-background text-primary shadow-[0_0_0_4px_color-mix(in_srgb,var(--primary)_18%,transparent)]",
                bad &&
                  "border-destructive bg-background text-destructive shadow-[0_0_0_4px_color-mix(in_srgb,var(--destructive)_18%,transparent)]",
                !done && !current && "border-border bg-background text-muted-foreground"
              )}
            >
              {done ? <Check aria-hidden="true" className="size-4" /> : i + 1}
            </span>
            <span
              className={cn(
                "mt-1.5 text-xs font-semibold",
                current || done ? "text-foreground" : "text-muted-foreground"
              )}
            >
              {s.label}
            </span>
            <span className="hidden text-[11px] text-muted-foreground sm:block">{s.hint}</span>
          </li>
        );
      })}
    </ol>
  );
}
