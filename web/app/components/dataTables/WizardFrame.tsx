"use client";

import type { ReactNode } from "react";
import { Check, X } from "lucide-react";
import { Button } from "../ui/button";
import { Card } from "../ui/card";

/**
 * The shell shared by the two multi-step flows on /data-tables (CSV import, starter templates): a header with an icon,
 * title, intro and close button, a step indicator, a body, and a footer pinned to the bottom so the primary action is
 * never below the fold. It holds no text of its own - every label comes in as a prop - so each flow keeps its own
 * wording and translation keys.
 */
type Props = {
  icon: ReactNode;
  title: string;
  intro: string;
  /** Accessible name of the step list. */
  stepsLabel: string;
  steps: string[];
  /** 1-based current step; earlier steps show a check. */
  step: number;
  closeLabel: string;
  onClose: () => void;
  footer: ReactNode;
  testId?: string;
  children: ReactNode;
};

export default function WizardFrame({
  icon,
  title,
  intro,
  stepsLabel,
  steps,
  step,
  closeLabel,
  onClose,
  footer,
  testId,
  children,
}: Props) {
  return (
    <Card data-testid={testId} className="gap-0 overflow-hidden p-0">
      <div className="flex flex-col gap-3 border-b border-border p-4 sm:p-5">
        <div className="flex items-start gap-3">
          <span
            className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
            aria-hidden="true"
          >
            {icon}
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="text-base font-semibold text-foreground">{title}</h3>
            <p className="text-sm text-muted-foreground">{intro}</p>
          </div>
          <Button type="button" size="icon" variant="ghost" aria-label={closeLabel} onClick={onClose}>
            <X size={16} aria-hidden="true" />
          </Button>
        </div>
        <ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs" aria-label={stepsLabel}>
          {steps.map((label, i) => {
            const n = i + 1;
            const done = n < step;
            const active = n === step;
            return (
              <li key={label} className="flex items-center gap-2" aria-current={active ? "step" : undefined}>
                <span
                  className={`flex size-5 items-center justify-center rounded-full text-[11px] font-semibold ${
                    done
                      ? "bg-primary text-primary-foreground"
                      : active
                        ? "border border-primary text-primary"
                        : "border border-border text-muted-foreground"
                  }`}
                >
                  {done ? <Check size={12} aria-hidden="true" /> : n}
                </span>
                <span className={active ? "font-medium text-foreground" : "text-muted-foreground"}>{label}</span>
                {n < steps.length && <span className="h-px w-6 bg-border" aria-hidden="true" />}
              </li>
            );
          })}
        </ol>
      </div>

      <div className="flex flex-col gap-5 p-4 sm:p-5">{children}</div>

      <div className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-3 border-t border-border bg-card px-4 py-3 sm:px-5">
        {footer}
      </div>
    </Card>
  );
}
