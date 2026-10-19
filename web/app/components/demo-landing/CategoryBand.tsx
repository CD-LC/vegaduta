"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { COMPETITORS } from "../../compare/compare-data";
import { useTranslations } from "../../lib/i18n";
import { sendLandingEvent } from "./landingBeacon";

// ---------------------------------------------------------------------------
// CATEGORY BAND — "where this sits next to the tools you already know"
// (2026-09-12, direct instruction: "inform consumers this is something like
// n8n and other platform, it should be clearly communicated").
//
// WHY IT RENDERS FROM compare-data.ts AND AUTHORS NOTHING
// The landing already had a competitor-claim problem waiting to happen: the
// page never named its category, while `/compare` has shipped three fully
// audited comparison pages (n8n, Dify, Botpress) since before this band
// existed — in PUBLIC_ROUTES, in the sitemap, every competitor row carrying
// its own citation. That content was reachable only from the hamburger menu
// and a footer link, so the visitor who most needed it never saw it.
//
// So this band imports `COMPETITORS` and renders AUDITED FIELDS VERBATIM:
//   - `theyDoBetter[0]`  what the competitor is genuinely better at
//   - `whenToPickVega[0]` the case for this product
// Nothing here restates, summarises or sharpens a competitor claim. If a
// comparison changes, it changes in one place and this band follows. The only
// strings this file authors are the eyebrow, the heading and the lead, and
// the lead is lifted from the n8n page's own intro paragraph — deliberately,
// because it is the most precise sentence anyone has written about what this
// product is:
//
//   "Workflows exist inside VegaDūta as a supporting capability for agents,
//    not as the product."
//
// WHY IT LEADS WITH WHAT THE COMPETITOR DOES BETTER
// Not modesty — it is the more persuasive structure and the honest one at the
// same time. A two-sided argument (concede the real strength, then state the
// narrow case where you win) is consistently more credible than a one-sided
// claim, and it is the only version that survives a visitor who already uses
// n8n and can check. It also matches what `/compare` already does: its own
// intro says the page "will not try to talk you out of it".
//
// COPY LIVES IN `landing.cat_*` (web/messages/*.json), 2026-09-17.
// The band shipped with inline English while en.json was under concurrent
// edit; that debt is now paid — every string this file authors is a
// `t("cat_…")` key, hand-translated into the other eight locales. The
// competitor fields (`theyDoBetter`, `whenToPickVega`, `name`) stay in
// compare-data.ts on purpose: they are audited data, not band chrome.
// ---------------------------------------------------------------------------

// Order is deliberate: n8n first because it is the reference point a visitor
// most likely arrives with, and the one the maintainer named.
const ORDER = ["n8n-alternative-india", "dify-alternative-india", "botpress-alternative-india"];

const ORDERED = ORDER.map((slug) => COMPETITORS.find((c) => c.slug === slug)).filter(
  (c): c is (typeof COMPETITORS)[number] => Boolean(c),
);

export default function CategoryBand() {
  const t = useTranslations("landing");
  return (
    <section id="where-we-fit" className="mkt-band mkt-band--ruled">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-head--center mkt-reveal">
          <p className="mkt-eyebrow">{t("cat_eyebrow")}</p>
          <h2 className="mkt-h2">
            {t("cat_h2")} <span className="mkt-gradient-text">{t("cat_h2Em")}</span>
          </h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("cat_sub")}</p>
        </div>

        <div className="mkt-reveal-group mt-12 grid gap-4 md:grid-cols-3">
          {ORDERED.map((c) => (
            <Link
              key={c.slug}
              href={`/compare/${c.slug}`}
              onClick={() => sendLandingEvent("category_compare_click")}
              // Interactive, so it earns the hover halo — this card really is
              // a link to the full audited page.
              className="mkt-card mkt-card--interactive group flex h-full flex-col gap-4 p-6 no-underline"
            >
              <div className="flex items-center justify-between gap-3">
                <span className="mkt-card-title">{t("cat_vs", { name: c.name })}</span>
                <ArrowRight className="size-4 shrink-0 text-[var(--mkt-ink-3)] transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
              </div>

              {/* Audited field, verbatim. The concession comes first. */}
              <div>
                <p className="mkt-badge text-[var(--mkt-positive)]">{t("cat_betterThere")}</p>
                <p className="mkt-small mkt-ink-2 mt-1.5">{c.theyDoBetter[0]}</p>
              </div>

              {/* Audited field, verbatim. */}
              <div>
                <p className="mkt-badge text-[var(--mkt-accent)]">{t("cat_betterHere")}</p>
                <p className="mkt-small mkt-ink-2 mt-1.5">{c.whenToPickVega[0]}</p>
              </div>
            </Link>
          ))}
        </div>

        <div className="mkt-reveal mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/compare"
            onClick={() => sendLandingEvent("category_compare_all_click")}
            className="mkt-btn mkt-btn-secondary no-underline"
          >
            {t("cat_readAll")}
            <ArrowRight className="size-4" />
          </Link>
          <p className="mkt-caption mkt-ink-3">{t("cat_citation")}</p>
        </div>
      </div>
    </section>
  );
}
