"use client";

import Link from "next/link";
import { ShieldCheck, Clock, ArrowRight, GraduationCap, Sparkles, Zap } from "lucide-react";
import { sendLandingEvent } from "./landingBeacon";
import { academyHref } from "../../lib/academyUrl";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";
import SectionReach from "./SectionReach";
import type { useJourney } from "../../lib/journey";

// Closing CTA band + link-chip row. Extracted verbatim from DemoLanding.
export default function ClosingBand({
  journey,
  tried,
  isIndia,
}: {
  journey: ReturnType<typeof useJourney>;
  tried: boolean;
  isIndia: boolean;
}) {
  const t = useTranslations("landing");
  return (
    <>
      {/* Closing CTA */}
      <SectionReach section="closing">
        {/* Migrated to the Aurora system (2026-09-12). The hand-rolled glow
            here was a 420px circle on `blur-3xl` — a 64px gaussian over a
            quarter-million pixels, recomputed on every paint, for an effect
            `.mkt-spot` renders as a radial-gradient stop at zero cost. Same
            look, one class, and one fewer reason for this page's screenshots
            to time out. The h2 was also the page's fourth distinct
            section-heading spec (`text-3xl font-semibold sm:text-4xl`, where
            its neighbours used 600/700 at four different sizes); it is now the
            shared `.mkt-h2` like every other band. */}
        <section className="mkt-band mkt-band--lg mkt-band--ruled mkt-spot overflow-hidden">
          {/* Was a framer-motion whileInView fade/rise; now `.mkt-reveal` —
              same motion, zero JS. See the import-block note at the top of
              this file for the full argument. */}
          <div className="mkt-reveal relative mx-auto max-w-2xl text-center">
            <div className="mkt-pill mb-4">
              <Sparkles className="size-3.5 text-primary" />
              {t("closing_badge")}
            </div>
            <h2 className="mkt-h2">{t("closing_title")}</h2>
            <p className="mkt-section-sub mkt-ink-2 mx-auto mt-3 max-w-xl text-pretty">
              {isIndia ? t("closing_subIN") : t("closing_sub")}
            </p>
            {/* Primary = /signup, secondary = /try (2026-09-12).
                HISTORY, because this has now flipped twice and the next
                session deserves the reasoning rather than a third flip:
                  - before 2026-09-09 this band led with /signup while the
                    hero led with /try, so the page gave two different answers
                    to "where do I start?";
                  - the 2026-09-09 funnel audit resolved that by making /try
                    primary EVERYWHERE, on the principle of one entry point;
                  - 2026-09-12, maintainer decision: the entry point
                    should be the account ("users should get registered ...
                    give a clear direction on signup to use the dashboard").
                So the one-entry-point principle is kept and the entry point
                changed. /try is NOT removed — it stays co-equal secondary in
                every band, and the ?demo=<token> carry into signup is
                untouched. Event names are unchanged on purpose, so the
                funnel read-out can compare the two postures across the
                flip. */}
            <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
              {/* /try — the real no-signup sandbox (a genuine isolated workspace
                  with a working agent; hands-on for 24h, viewable 5 days).
                  Journey-aware (2026-09-10): once this browser HAS tried, the
                  solid button becomes "keep my agent" → signup with the demo
                  token, and /try drops to the outline. Event names unchanged. */}
              {/* Migrated off ShadCN <Button> to `.mkt-btn` (2026-09-12,
                  persuasion pass). Ten bands on this page — PathBand,
                  PlatformLedger, ProofBand, ProviderWall, BusinessCaseBand,
                  ContactBand, PricingBand, SensesShowcase, CategoryBand,
                  IdePluginsSection — already render their CTAs with
                  `.mkt-btn-primary` / `.mkt-btn-secondary`. This band and the
                  hero were the two holdouts, so the page's FINAL ask was
                  shaped, sized and lit differently from every ask that led up
                  to it. A CTA that changes appearance on its last repetition
                  reads as a different offer. Destinations, labels, journey
                  branching and beacon names are untouched. */}
              {tried ? (
                <>
                  <Link
                    href={journey.signupHref}
                    onClick={() => sendLandingEvent("closing_signup_click")}
                    className="mkt-btn mkt-btn-primary mkt-btn-lg w-full sm:w-auto"
                  >
                    {t("hero_ctaKeepAgent")}
                    <ArrowRight className="size-4" aria-hidden="true" />
                  </Link>
                  <Link
                    href={journey.tryHref}
                    onClick={() => sendLandingEvent("closing_try_click")}
                    className="mkt-btn mkt-btn-secondary mkt-btn-lg w-full sm:w-auto"
                  >
                    {t("hero_ctaReopenSandbox")}
                  </Link>
                </>
              ) : (
                <>
                  <Link
                    href={journey.signupHref}
                    onClick={() => sendLandingEvent("closing_signup_click")}
                    className="mkt-btn mkt-btn-primary mkt-btn-lg w-full sm:w-auto"
                  >
                    {t("closing_ctaCreate")}
                    <ArrowRight className="size-4" aria-hidden="true" />
                  </Link>
                  <Link
                    href={journey.tryHref}
                    onClick={() => sendLandingEvent("closing_try_click")}
                    className="mkt-btn mkt-btn-secondary mkt-btn-lg w-full sm:w-auto"
                  >
                    {t("closing_ctaTry")}
                  </Link>
                </>
              )}
            </div>

            {/* THE GUARANTEE PANEL (persuasion-architecture pass, 2026-09-12).
                Two existing strings, re-sited — no new copy, no key touched.

                WHY IT MOVED HERE. This page's single strongest offer is
                `heroUniversal_sparkBadge` ("everything free for 3 months · no
                card · then a free plan that never expires"), and until now it
                appeared exactly once: as a solid violet `animate-pulse` pill
                under the hero h1, ~16 bands above the place the decision is
                actually made. Two problems with that, in order of cost:
                  1. A blinking solid badge is read as an advert, not as terms.
                     It is the same shape as every promo strip a visitor
                     dismisses without parsing, so the page's best argument was
                     paying banner-blindness rates.
                  2. Risk reversal has to be adjacent to the ask. A guarantee a
                     visitor read a full scroll-session ago is not in working
                     memory when they are deciding whether to hand over an
                     email address.
                So it is restated at the ask, in the register of terms rather
                than promotion: a sunken card, a hairline, a token-coloured
                check, no pulse, no fill. `.mkt-card--sunken` is deliberately
                the QUIETEST surface in the system — the credibility of a
                guarantee is inversely proportional to how hard it is shouting.

                Paired with `hero_ctaNote` ("about a minute · no card · your
                dashboard opens immediately"), which was already here but
                rendered as loose 12px muted text trailing the buttons, where
                it read as a disclaimer rather than as the answer to "what am I
                actually about to do?". Offer above, cost-in-time below, one
                object, directly under the button that charges it.

                Left-aligned inside a centred band on purpose: these two lines
                wrap to 2-3 lines on a phone, and centred ragged multi-line
                body copy is the exact readability regression the hero's own
                sub-lead comment records being told to fix. */}
            <div className="mkt-card mkt-card--sunken mx-auto mt-6 max-w-md px-4 py-3.5 text-start">
              <p className="mkt-small flex items-start gap-2.5 font-medium">
                <ShieldCheck
                  className="mt-0.5 size-4 shrink-0"
                  style={{ color: "var(--mkt-positive)" }}
                  aria-hidden="true"
                />
                <span>{t(FREE_MODE ? "sparkBadgeFreeMode" : "heroUniversal_sparkBadge")}</span>
              </p>
              <p className="mkt-caption mkt-ink-2 mt-2 flex items-start gap-2.5">
                <Clock className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>{t("hero_ctaNote")}</span>
              </p>
            </div>

            {/* Content-reduction pass (2026-08-14, judged workflow): AcademyCTA,
                TemplatesShowcase, IdePluginsSection and EntrepreneurSection each
                used to be a full standalone band whose entire job was "here's a
                shipped surface, go use it" — that's exactly what a link chip
                does, at a fraction of the scroll length. Real destinations,
                nothing invented: /templates, /plugins and /earn already exist;
                Academy is academyHref()'s separate deployable. */}
            {/* Pushed further down and put behind the guarantee panel
                (2026-09-12). Five link chips sitting immediately under the
                page's final CTA is five alternative destinations offered at
                the moment of the ask — the classic way a closing band leaks
                the decision it just set up. They still belong on the page (a
                visitor who has decided NOT to sign up should be handed the
                other shipped surfaces rather than the back button), but they
                belong AFTER the ask has closed, which is what the guarantee
                panel now does. Hairline moved onto the system's own
                `--mkt-line` so the separator matches every other rule on the
                page instead of being the one `border-border/50` left. */}
            <div
              className="mt-10 flex flex-wrap items-center justify-center gap-2 border-t pt-6"
              style={{ borderColor: "var(--mkt-line)" }}
            >
              <a
                href={academyHref()}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card/50 px-3.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                <GraduationCap className="size-3.5" /> {t("closing_linkAcademy")}
              </a>
              <Link
                href="/templates"
                className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card/50 px-3.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                {t("closing_linkTemplates")}
              </Link>
              <Link
                href="/plugins"
                onClick={() => sendLandingEvent("plugins_click")}
                className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card/50 px-3.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                {t("closing_linkPlugins")}
              </Link>
              <Link
                href="/earn"
                className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card/50 px-3.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                {t("closing_linkEarn")}
              </Link>
              {/* /hire — was a full-width strip above the nav (see the comment
                  where it used to mount); same destination, same testid, now
                  one chip among the other shipped surfaces. Beaconed so the
                  demotion can be checked against data, not argued. */}
              <Link
                href="/hire"
                onClick={() => sendLandingEvent("hire_banner_click")}
                data-testid="hire-banner"
                title={`${t("hire_bannerLabel")} ${t("hire_bannerBody")}`}
                className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card/50 px-3.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                <Zap className="size-3.5" /> {t("menu_hireStaff")}
              </Link>
            </div>
          </div>
        </section>
      </SectionReach>
    </>
  );
}
