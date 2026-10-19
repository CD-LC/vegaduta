"use client";

import { Lock, ShieldCheck, Building2, DatabaseZap } from "lucide-react";
import BrandText from "../BrandText";
import { useTranslations } from "../../lib/i18n";

// Platform security — every item below maps to a real capability in the
// codebase (AES key/secret encryption, Keycloak OIDC+PKCE, schema-per-tenant
// isolation, mandatory email verification, tenant data export/erasure, HTTPS
// via Cloudflare). No fabricated compliance badges.
export default function SecuritySection() {
  const t = useTranslations("landing");
  // Content-reduction pass (2026-08-14, judged workflow): trimmed 6 cards to
  // the 4 highest-stakes claims for a credibility-gap product (research:
  // security is exactly the objection PAS says to keep visible, not export
  // off-page). Verified accounts + transport encryption stay true and
  // shipped, just no longer need their own cards — HTTPS-everywhere and
  // required email verification are baseline expectations, not differentiators.
  const items = [
    { icon: Lock, titleKey: "security_rest_title", bodyKey: "security_rest_body" },
    { icon: ShieldCheck, titleKey: "security_auth_title", bodyKey: "security_auth_body" },
    { icon: Building2, titleKey: "security_tenant_title", bodyKey: "security_tenant_body" },
    { icon: DatabaseZap, titleKey: "security_data_title", bodyKey: "security_data_body" },
  ];
  return (
    // 2026-07-29 design audit: this section carries the platform's actual
    // trust claims (encryption, OIDC, tenant isolation) but was styled
    // identically to every other card-grid section on the page, under-selling
    // it exactly where a nervous first-time visitor needs a different visual
    // register than "one more feature list." A subtly tinted band + a
    // distinct card treatment (solid surface, emerald icon accent instead of
    // the page's one all-purpose violet) sets it apart without a redesign.
    // Migrated to the Aurora system (2026-09-12, persuasion pass). This was
    // the last band on the page still hand-rolling its own chrome, and the
    // cost was not only inconsistency — it was a LIGHT-MODE legibility bug in
    // the band that exists to carry credibility:
    //
    //   * `text-emerald-400` (#34d399) was the colour of both the badge text
    //     and every card icon. On the dark canvas that is fine; on the light
    //     one it is a pale mint sitting on `bg-emerald-500/10` and on `bg-card`
    //     — nowhere near 4.5:1 at 12px semibold. `--mkt-positive` is the token
    //     that already solved exactly this (it was darkened to #04724f on
    //     2026-09-12 for the identical failure in BusinessCaseBand) and it
    //     carries a separate dark-mode value, so one token replaces the
    //     hardcoded hue in both themes.
    //   * `text-3xl font-semibold tracking-tight` made this the page's last
    //     surviving fourth section-heading spec; it is `.mkt-h2` now, like
    //     every other band.
    //   * `px-5 py-16 sm:px-8 sm:py-24` was its own vertical rhythm; `.mkt-band`
    //     owns that, and `--ruled` replaces the full-bleed `border-t` with the
    //     fading hairline the rest of the page separates on.
    //
    // The band keeps its distinct visual REGISTER — a trust section styled
    // identically to a feature grid under-sells itself to exactly the nervous
    // first-time visitor it is for — but the register now rides on the
    // `--mkt-positive` token rather than on a raw Tailwind palette step, so it
    // tracks the theme instead of fighting it. Same id, same four claims, same
    // heading levels.
    <section
      id="security"
      className="mkt-band mkt-band--ruled scroll-mt-16"
      style={{ backgroundColor: "color-mix(in oklab, var(--mkt-positive) 5%, transparent)" }}
    >
      <div className="mkt-band__inner">
        {/* `.mkt-reveal` (CSS view-timeline), not <Reveal> (framer-motion).
            This is the trust band: it MUST be readable with motion off and
            with no scroll-driven-animation support, and the CSS class is the
            version that actually guarantees that — it only ever animates
            inside `prefers-reduced-motion: no-preference` + `@supports
            (animation-timeline: view())`, and is `animation: none; opacity: 1`
            under print/forced-colors. The JS version could leave it at
            opacity 0 if the bundle failed. */}
        <div className="mkt-reveal mkt-head mkt-head--center">
          <div
            className="mkt-pill"
            style={{
              borderColor: "color-mix(in oklab, var(--mkt-positive) 38%, transparent)",
              background: "color-mix(in oklab, var(--mkt-positive) 10%, transparent)",
              color: "var(--mkt-positive)",
            }}
          >
            <ShieldCheck className="size-3.5" aria-hidden="true" /> {t("security_badge")}
          </div>
          <h2 className="mkt-h2">{t("security_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">
            <BrandText text={t("security_sub")} />
          </p>
        </div>
        {/* Not `.mkt-card--interactive`: these are claims, not destinations.
            A hover lift here would promise a detail page that does not exist. */}
        <div className="mkt-reveal-group mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map(({ icon: Icon, titleKey, bodyKey }) => (
            <div key={titleKey} className="mkt-card h-full p-5">
              <Icon className="size-5" style={{ color: "var(--mkt-positive)" }} aria-hidden="true" />
              <h3 className="mkt-card-title mt-3">{t(titleKey)}</h3>
              <p className="mkt-small mkt-ink-2 mt-2">{t(bodyKey)}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
