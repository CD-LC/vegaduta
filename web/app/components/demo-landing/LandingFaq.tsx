"use client";

import { useState } from "react";
import { ChevronDown } from "lucide-react";
import Reveal from "../marketing/Reveal";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";
import JsonLd from "../JsonLd";

// ---------------------------------------------------------------------------
// LandingFaq — the last-objection handler before the closing CTA, plus the
// homepage's first page-level structured data (FAQPage JSON-LD — the sitewide
// @graph in layout.tsx deliberately carries no page-specific nodes).
//
// Every answer states only verified product facts, same posture as the rest
// of the page: Free-plan mechanics from BillingPageClient/plan_limits,
// WhatsApp entitlement from the Premium tier, approvals/audit from
// core/approval + core/audit, languages from the 30 shipped locales (8
// international + all 22 Eighth Schedule languages).
// ---------------------------------------------------------------------------

// Content-reduction pass (2026-08-14, judged workflow): trimmed from 6 to
// the 4 that rebut a real objection (PAS: "FAQ stays only if it's
// rebutting real objections"). Dropped faq_q4 (languages — informational,
// not an objection) and faq_q6 (where do I start — onboarding, not an
// objection); both answers stay in en.json for a future onboarding surface.
const FAQ_KEYS = [
  { qKey: "faq_q1", aKey: "faq_a1" },
  { qKey: "faq_q2", aKey: "faq_a2" },
  { qKey: "faq_q3", aKey: "faq_a3" },
  { qKey: "faq_q5", aKey: "faq_a5" },
] as const;

export default function LandingFaq() {
  const [open, setOpen] = useState<number | null>(0);
  const t = useTranslations("landing");
  // Free mode (lib/freeMode.ts): the pricing answer's 3-month-grant
  // mechanics are false under it, so faq_a1 swaps to the free-mode text.
  // The FAQPage JSON-LD reads the same resolved strings, so markup and
  // structured data stay in agreement either way.
  const FAQS = FAQ_KEYS.map(({ qKey, aKey }) => ({
    q: t(qKey),
    a: t(FREE_MODE && aKey === "faq_a1" ? "faqA1FreeMode" : aKey),
  }));

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    mainEntity: FAQS.map(({ q, a }) => ({
      "@type": "Question",
      name: q,
      acceptedAnswer: { "@type": "Answer", text: a },
    })),
  };

  return (
    <section id="faq" className="mkt-band mkt-band--ruled">
      <JsonLd data={jsonLd} />
      <div className="mkt-band__inner mkt-band__inner--narrow">
        <Reveal className="mkt-head">
          <p className="mkt-eyebrow">{t("faq_eyebrow")}</p>
          <h2 className="mkt-h2">{t("faq_title")}</h2>
        </Reveal>

        {/* Not .mkt-card--interactive: the card is a container, only the
            individual rows are clickable, so a card-wide hover lift would
            claim an affordance the card does not have. */}
        <div className="mkt-card mkt-reveal-group mt-8 divide-y divide-[color:var(--mkt-line)]">
          {FAQS.map(({ q, a }, i) => {
            const isOpen = open === i;
            return (
              <div key={q}>
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? null : i)}
                  aria-expanded={isOpen}
                  aria-controls={`landing-faq-a${i}`}
                  className="flex w-full items-center justify-between gap-4 border-0 bg-transparent px-5 py-4 text-start"
                >
                  <span className="mkt-body font-medium text-foreground">{q}</span>
                  <ChevronDown
                    className={`mkt-ink-3 size-4 shrink-0 transition-transform ${isOpen ? "rotate-180" : ""}`}
                    aria-hidden="true"
                  />
                </button>
                {/* Always in the DOM, collapsed with `hidden` (GEO pass,
                    2026-09-19). This was `{isOpen && …}`, so only the first
                    answer existed in the served HTML while the FAQPage JSON-LD
                    above declared all four — markup claiming more than the
                    page shows, which is what Google's FAQ policy calls out,
                    and AI crawlers (none of which run JavaScript) could read
                    one answer in four. */}
                <p id={`landing-faq-a${i}`} hidden={!isOpen} className="mkt-body mkt-ink-2 px-5 pb-5">
                  {a}
                </p>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
