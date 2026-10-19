"use client";

// The Assistant's empty state: a greeting for the time of day and a few everyday things to try.
// Plain on purpose: this page is for people who just want to get something done.

import { useTranslations } from "../../lib/i18n";
import { Sparkles } from "lucide-react";

const STARTERS = ["starter_write", "starter_plan", "starter_explain", "starter_files", "starter_remind", "starter_build"] as const;

/** "morning" before noon, "afternoon" before 17:00, otherwise "evening" (the person's local clock). */
export function dayPart(hour: number): "morning" | "afternoon" | "evening" {
  return hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
}

export default function AssistantLaunchpad({ onPrompt }: { onPrompt: (text: string) => void }) {
  const t = useTranslations("assistant");
  return (
    <div className="flex flex-col items-center gap-6 py-10 text-center" data-testid="assistant-launchpad">
      <span className="flex size-14 items-center justify-center rounded-2xl asst-mark" aria-hidden="true" data-testid="assistant-mark">
        <Sparkles className="size-7" />
      </span>
      <div>
        <h2 className="text-2xl font-semibold tracking-tight text-foreground">
          {t(`greeting_${dayPart(new Date().getHours())}`)}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">{t("emptyTitle")}</p>
      </div>
      <div className="flex max-w-xl flex-wrap justify-center gap-2">
        {STARTERS.map((key) => (
          <button
            type="button"
            key={key}
            onClick={() => onPrompt(t(`${key}_prompt`))}
            className="asst-chip rounded-full border px-3 py-1.5 text-sm text-foreground/80 hover:text-foreground"
          >
            {t(key)}
          </button>
        ))}
      </div>
      <p className="max-w-md text-xs text-muted-foreground">{t("emptyHint")}</p>
    </div>
  );
}
