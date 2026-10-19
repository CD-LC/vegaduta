"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import type { BriefClarification } from "./types";

// What the planner is doing right now, as it streams. Thinking lines are model
// output shown as written; only the surrounding labels go through t().
export function ThinkingLines({ lines, running }: { lines: readonly string[]; running: boolean }) {
  const t = useTranslations("brief");
  return (
    <div className="space-y-1.5" aria-live="polite" aria-label={t("plan.thinkingLabel")}>
      {lines.map((line, i) => {
        const last = i === lines.length - 1;
        return (
          <div key={i} className={cn("flex items-center gap-2 text-sm", last && running ? "text-foreground" : "text-muted-foreground")}>
            {last && running ? <Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> : <span className="state-dot" aria-hidden="true" />}
            <span>{line}</span>
          </div>
        );
      })}
      {lines.length === 0 && running && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {t("plan.starting")}
        </div>
      )}
    </div>
  );
}

/**
 * Up to three questions the planner needs answered before it proposes. Each is
 * a row of answer chips (plus a free-text escape); once every question has an
 * answer, or the person skips, the parent re-plans.
 */
export function Clarifications({
  items,
  answers,
  onAnswer,
  onSkip,
}: {
  items: readonly BriefClarification[];
  answers: Readonly<Record<number, string>>;
  onAnswer: (index: number, answer: string) => void;
  onSkip: () => void;
}) {
  const t = useTranslations("brief");
  const [free, setFree] = useState<Record<number, string>>({});
  const shown = items.slice(0, 3);
  return (
    <section aria-label={t("plan.clarifyTitle")} className="space-y-4 rounded-xl border border-border bg-card/60 p-4">
      <h3 className="text-sm font-semibold text-foreground">{t("plan.clarifyTitle")}</h3>
      {shown.map((c, i) => (
        <div key={i} className="space-y-2">
          <p className="text-sm text-foreground">{c.question}</p>
          <div className="flex flex-wrap items-center gap-2">
            {(c.options ?? []).slice(0, 4).map((opt) => (
              <button
                key={opt}
                type="button"
                aria-pressed={answers[i] === opt}
                onClick={() => onAnswer(i, opt)}
                className={cn(
                  "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                  answers[i] === opt ? "border-primary bg-primary text-primary-foreground" : "border-border bg-card text-foreground hover:bg-accent"
                )}
              >
                {opt}
              </button>
            ))}
            <form
              className="flex items-center gap-1.5"
              onSubmit={(e) => {
                e.preventDefault();
                const v = (free[i] ?? "").trim();
                if (v) onAnswer(i, v);
              }}
            >
              <Input
                value={free[i] ?? ""}
                onChange={(e) => setFree((f) => ({ ...f, [i]: e.target.value }))}
                placeholder={t("plan.otherAnswer")}
                aria-label={t("plan.otherAnswer")}
                className="h-8 w-40 text-xs"
              />
            </form>
          </div>
        </div>
      ))}
      <Button variant="ghost" size="sm" onClick={onSkip}>
        {t("plan.skipQuestions")}
      </Button>
    </section>
  );
}
