"use client";

import { Bot, Plug, Network, ShoppingBag, Workflow, Laptop } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

// PLATFORM EDGE — the six advantages a visitor can't get elsewhere in one
// place, stated plainly. Runs after CapabilityAtlas (which says what the
// product IS); this band says why running it beats stitching it together.

const CARDS = [
  { icon: Laptop, k: "local" },
  { icon: Bot, k: "ollama" },
  { icon: Plug, k: "mcp" },
  { icon: Network, k: "a2a" },
  { icon: Workflow, k: "workflow" },
  { icon: ShoppingBag, k: "commerce" },
] as const;

export default function PlatformEdgeBand() {
  const t = useTranslations("landing");
  return (
    <section id="platform-edge" className="mkt-band mkt-band--ruled">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-head--center mkt-reveal">
          <p className="mkt-eyebrow">{t("edge_eyebrow")}</p>
          <h2 className="mkt-h2">
            {t("edge_h2")} <span className="mkt-gradient-text">{t("edge_h2Em")}</span>
          </h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("edge_sub")}</p>
        </div>
        <div className="mkt-reveal-group mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {CARDS.map(({ icon: Icon, k }) => (
            <div key={k} className="mkt-card mkt-card--interactive group flex h-full flex-col gap-3 p-6">
              <span className="flex size-10 items-center justify-center rounded-xl bg-[var(--mkt-accent-soft)] text-[var(--mkt-accent)]">
                <Icon className="size-5" aria-hidden="true" />
              </span>
              <h3 className="mkt-card-title">{t(`edge_${k}_title`)}</h3>
              <p className="m-0 text-sm text-[var(--mkt-ink-2)]">{t(`edge_${k}_body`)}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
