"use client";

// The Assistant's empty state: a greeting for the time of day and a few everyday things to try.
// Plain on purpose: this page is for people who just want to get something done.

import { BellRing, CalendarDays, FileText, Hammer, Lightbulb, PenLine, Sparkles } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

const STARTERS = [
  { key: "starter_write", icon: PenLine },
  { key: "starter_plan", icon: CalendarDays },
  { key: "starter_explain", icon: Lightbulb },
  { key: "starter_files", icon: FileText },
  { key: "starter_remind", icon: BellRing },
  { key: "starter_build", icon: Hammer },
] as const;

/** "morning" before noon, "afternoon" before 17:00, otherwise "evening" (the person's local clock). */
export function dayPart(hour: number): "morning" | "afternoon" | "evening" {
  return hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
}

/** The composer's text box (AssistantComposer marks it), so a starter can hand focus straight to it. */
export const COMPOSER_BOX_SELECTOR = "[data-assistant-box]";

export default function AssistantLaunchpad({ onPrompt }: { onPrompt: (text: string) => void }) {
  const t = useTranslations("assistant");
  return (
    <div className="flex flex-col items-center gap-7 pb-6 pt-[8vh] text-center" data-testid="assistant-launchpad">
      <span
        className="flex size-12 items-center justify-center rounded-2xl asst-mark"
        aria-hidden="true"
        data-testid="assistant-mark"
      >
        <Sparkles className="size-6" />
      </span>
      <div>
        <h2 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
          {t(`greeting_${dayPart(new Date().getHours())}`)}
        </h2>
        <p className="mt-1.5 text-[0.9375rem] text-muted-foreground">{t("emptyTitle")}</p>
      </div>
      <ul className="grid w-full max-w-xl grid-cols-2 gap-2 sm:grid-cols-3">
        {STARTERS.map(({ key, icon: Icon }) => (
          <li key={key}>
            <button
              type="button"
              onClick={() => {
                onPrompt(t(`${key}_prompt`));
                // The prompt lands in the box; the cursor follows so it can be finished and sent.
                requestAnimationFrame(() =>
                  document.querySelector<HTMLTextAreaElement>(COMPOSER_BOX_SELECTOR)?.focus()
                );
              }}
              className="asst-chip flex h-full w-full items-center gap-2 rounded-xl border px-3 py-2.5 text-start text-sm text-foreground/85 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            >
              <Icon size={15} className="size-4 shrink-0 text-primary" aria-hidden="true" />
              <span className="min-w-0">{t(key)}</span>
            </button>
          </li>
        ))}
      </ul>
      <p className="max-w-md text-xs leading-relaxed text-muted-foreground">{t("emptyHint")}</p>
    </div>
  );
}
