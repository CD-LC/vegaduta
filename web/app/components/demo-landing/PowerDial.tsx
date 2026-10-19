"use client";

import { useEffect, useState } from "react";
import { ArrowRight, Cpu, KeyRound, Users } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

// The hero's answer to "what do I run this on, and who pays?". Three power sources, in the order a builder cares about
// them, as one selector: pick a source and the line beneath redraws the route from YOU to YOUR AGENT through it. It
// turns a run-on tagline into something you can touch, and every line is a claim the product actually backs.
// Cycles on its own until the visitor touches it (or prefers reduced motion), then stays where they put it.

const SOURCES = [
  { id: "byok", icon: KeyRound },
  { id: "edge", icon: Cpu },
  { id: "pool", icon: Users },
] as const;

const CYCLE_MS = 3800;

export default function PowerDial() {
  const t = useTranslations("landing");
  const [i, setI] = useState(0);
  const [auto, setAuto] = useState(true);

  useEffect(() => {
    if (!auto) return undefined;
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches) return undefined;
    const id = window.setInterval(() => setI((n) => (n + 1) % SOURCES.length), CYCLE_MS);
    return () => window.clearInterval(id);
  }, [auto]);

  const active = SOURCES[i];

  return (
    <div data-testid="hero-power-dial" onMouseEnter={() => setAuto(false)} onFocusCapture={() => setAuto(false)}>
      <div role="group" aria-label={t("heroPower_label")} className="flex flex-wrap gap-2">
        {SOURCES.map(({ id, icon: Icon }, idx) => {
          const on = idx === i;
          return (
            <button
              key={id}
              type="button"
              aria-pressed={on}
              onClick={() => {
                setI(idx);
                setAuto(false);
              }}
              className="mkt-pill inline-flex items-center gap-1.5 px-3 py-1.5 shadow-none transition-colors"
              style={
                on
                  ? { background: "var(--mkt-accent)", color: "var(--accent-fg, #fff)", border: "1px solid var(--mkt-accent)" }
                  : { background: "var(--mkt-surface)", color: "var(--mkt-ink-2)", border: "1px solid var(--mkt-line)" }
              }
            >
              <Icon className="size-3.5" aria-hidden="true" />
              {t(`heroPower_${id}`)}
            </button>
          );
        })}
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs mkt-ink-3" aria-hidden="true">
        <span>{t("heroPower_you")}</span>
        <ArrowRight className="size-3" />
        <span className="font-semibold" style={{ color: "var(--mkt-accent)" }}>
          {t(`heroPower_${active.id}`)} · {t(`heroPower_${active.id}_tag`)}
        </span>
        <ArrowRight className="size-3" />
        <span>{t("heroPower_agent")}</span>
      </div>
      <p className="mkt-ink-2 mt-1.5 max-w-xl text-sm" aria-live={auto ? "off" : "polite"}>
        {t(`heroPower_${active.id}_line`)}
      </p>
    </div>
  );
}
