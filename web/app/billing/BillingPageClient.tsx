"use client";

import { useState } from "react";
import Link from "next/link";
import { Check, ArrowLeft, Coffee, IndianRupee } from "lucide-react";
import { auth } from "../lib/auth";
import Logo from "../components/Logo";
import PublicAuthCtas from "../components/PublicAuthCtas";
import { FREE_MODE, COFFEE_URL, HAS_COFFEE_LINK } from "../lib/freeMode";
import { useTranslations } from "../lib/i18n";
import { useRegion } from "../lib/useRegion";
import { useCurrency } from "../lib/payments/useCurrency";
import CurrencySwitch from "../components/billing/CurrencySwitch";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import BrandText from "../components/BrandText";

// Billing / pricing page — rebuilt on canonical shadcn/ui primitives (Card,
// Button, Badge, Table) + the shadcn dark token layer, so the whole public
// funnel (landing → pricing → login) now shares one dark ground, one violet
// accent and one set of primitives. Retires the old --surface-*/--accent-*
// inline-token markup for this screen; the page is now a deliberately dark
// marketing surface like the landing it's linked from.
//
// Every real number here is pulled straight from the GTM strategy doc's tier
// matrix (matches UpgradePrompt's PLAN_COPY) — no invented figures. TenantPlan
// only has FREE / PREMIUM / UNLIMITED. Deliberately a PUBLIC route (see
// AuthProvider PUBLIC_ROUTES): the audience is a visitor from the landing's
// "Pricing" link before they have any Keycloak session, so no useAuth/AppShell.
// Monthly + annual price points per tier (pricing audit 2026-07-31 §5.1):
// annual = 10× monthly, framed as "2 months free". The ₹/$ pairs are NOT an
// FX conversion of each other - they are deliberate regional pricing (India
// PPP), and the cards now SAY so explicitly instead of presenting ₹399 and
// $19 as if they were the same number (audit §2.5-2: honest framing turns
// that finding from a bug into a strategy).
const TIERS = [
  {
    plan: "FREE",
    nameKey: "tierFreeName",
    priceUsd: "$0",
    priceInr: "₹0",
    priceUsdAnnual: "$0",
    priceInrAnnual: "₹0",
    taglineKey: "tierFreeTagline",
    featureKeys: [
      "tierFreeFeature1",
      "tierFreeFeature2",
      "tierFreeFeature3",
      "tierFreeFeature4",
      "tierFreeFeature5",
      "tierFreeFeature6",
    ],
    noteKey: "tierFreeNote",
    ctaKey: "tierFreeCta",
    highlighted: false,
  },
  {
    plan: "PREMIUM",
    nameKey: "tierPremiumName",
    priceUsd: "$19",
    priceInr: "₹399",
    priceUsdAnnual: "$190",
    priceInrAnnual: "₹3,999",
    taglineKey: "tierPremiumTagline",
    featureKeys: [
      "tierPremiumFeature1",
      "tierPremiumFeature2",
      "tierPremiumFeature3",
      "tierPremiumFeature4",
      "tierPremiumFeature5",
      "tierPremiumFeature6",
      "tierPremiumFeature7",
    ],
    noteKey: null as string | null,
    ctaKey: "tierPremiumCta",
    highlighted: true,
  },
  {
    plan: "UNLIMITED",
    nameKey: "tierUnlimitedName",
    priceUsd: "$79",
    priceInr: "₹1,599",
    priceUsdAnnual: "$790",
    priceInrAnnual: "₹15,999",
    taglineKey: "tierUnlimitedTagline",
    featureKeys: [
      "tierUnlimitedFeature1",
      "tierUnlimitedFeature2",
      "tierUnlimitedFeature3",
      "tierUnlimitedFeature4",
      "tierUnlimitedFeature5",
      "tierUnlimitedFeature6",
      "tierUnlimitedFeature7",
    ],
    noteKey: null as string | null,
    ctaKey: "tierUnlimitedCta",
    highlighted: false,
  },
] as const;

const COMPARISON_ROWS = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({
  labelKey: `compareRow${n}Label`,
  freeKey: `compareRow${n}Free`,
  premiumKey: `compareRow${n}Premium`,
  unlimitedKey: `compareRow${n}Unlimited`,
}));

// Free mode (lib/freeMode.ts, NEXT_PUBLIC_FREE_MODE=1): the paid tier grid
// and comparison table stay exactly as written below - the whole paid
// surface is just not rendered while the platform runs free. One card says
// so honestly, including the one limit that genuinely still exists (the
// hosted-pool fair-use cap - real provider spend, not a paywall).
const FREE_MODE_FEATURE_KEYS = [
  "freeModeFeature1",
  "freeModeFeature2",
  "freeModeFeature3",
  "freeModeFeature4",
  "freeModeFeature5",
] as const;

// Split out of page.tsx (structured-data audit, 2026-07-30): this is the
// client island (i18n via useTranslations, useRegion, auth.login onClick) —
// page.tsx is now a server component wrapping this, carrying `metadata` +
// Product/Offer JSON-LD for the three tiers, which a "use client" file
// cannot export. Unchanged otherwise — TIERS stays the single source of
// truth both the rendered cards and the server-side JSON-LD read from.
export default function BillingPageClient({ forcePaid = false }: { forcePaid?: boolean } = {}) {
    const tHC = useTranslations("hcBillingBillingPageClient");
  const t = useTranslations("billing");
  const { isIndia } = useRegion();
  // The currency prices are shown in: detected from the region, overridable with the switch below (locale
  // detection is a guess), and limited to currencies the deployment can actually bill.
  const { currency, detected, choices, choose } = useCurrency();
  // Monthly/Annual display toggle - purely presentational on this public
  // page (the actual charge period is picked again inside the signed-in
  // upgrade flow, UpgradePrompt, which sends `period` in its payload).
  const [annual, setAnnual] = useState(false);

  // /pricing passes forcePaid: the plans and prices must be visible to customers and to payment-gateway reviewers
  // even while the platform runs in free mode (where /billing shows the free-for-everyone card instead).
  if (FREE_MODE && !forcePaid) {
    return (
      <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
        <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
          <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
        </div>

        <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
          <Link href="/">
            <Logo size={32} />
          </Link>
          <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
            <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
              <Link href="/">
                <ArrowLeft className="size-3.5 shrink-0" aria-hidden="true" /> {t("navBackHome")}
              </Link>
            </Button>
            {/* vd_authed hint swaps this pair for "Open console". */}
            <PublicAuthCtas>
              <Button
                variant="ghost"
                size="sm"
                className="text-muted-foreground hover:text-foreground"
                onClick={() => auth.login({ redirectUri: `${window.location.origin}/` })}
                data-testid="login-landing-button"
              >
                {t("navSignIn")}
              </Button>
              <Button asChild variant="outline" size="sm">
                <Link href="/signup">{t("navStartFree")}</Link>
              </Button>
            </PublicAuthCtas>
          </div>
        </nav>

        <header className="mx-auto max-w-3xl px-6 pb-10 pt-10 text-center">
          <span className="font-mono text-xs uppercase tracking-[0.18em] text-primary">{t("freeModeEyebrow")}</span>
          <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("freeModeTitle")}</h1>
          <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">
            <BrandText text={t("freeModeSubtitle")} />
          </p>
        </header>

        <section className="mx-auto max-w-xl px-6 pb-20">
          <Card className="border-primary/60 shadow-[0_0_0_1px_var(--primary)] shadow-2xl">
            <CardContent className="flex flex-col py-8">
              <ul className="flex list-none flex-col gap-3 p-0">
                {FREE_MODE_FEATURE_KEYS.map((key) => (
                  <li key={key} className="flex items-start gap-2.5 text-sm leading-snug">
                    <Check
                      size={15}
                      strokeWidth={2.5}
                      className="mt-0.5 flex-shrink-0 text-primary"
                      aria-hidden="true"
                    />
                    <span>{t(key)}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-5 border-t border-border pt-4 text-xs leading-snug text-muted-foreground/80">
                {t("freeModeFairUse")}
              </p>
              <div className="mt-6 flex flex-col gap-3">
                <Button asChild className="w-full">
                  <Link href="/signup">{t("freeModeCta")}</Link>
                </Button>
                {HAS_COFFEE_LINK && (
                  <Button asChild variant="outline" className="w-full">
                    <a href={COFFEE_URL} target="_blank" rel="noopener noreferrer">
                      <Coffee className="size-4 shrink-0" aria-hidden="true" /> {t("freeModeCoffee")}
                    </a>
                  </Button>
                )}
              </div>
              <p className="mt-4 text-center text-xs leading-snug text-muted-foreground/80">
                <BrandText text={t("freeModeCoffeeNote")} />
              </p>
            </CardContent>
          </Card>
        </section>
      </div>
    );
  }

  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10">
        <div className="absolute -top-40 left-1/2 h-[38rem] w-[38rem] -translate-x-1/2 rounded-full bg-primary/16 blur-[140px]" />
      </div>

      {/* --------------------------------------------------------------- Nav */}
      <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-y-3 px-5 py-5 sm:justify-between sm:px-8">
        <Link href="/">
          {/* The human-in-helmet winged emblem, in its own colors, always
              next to the brand name (direct instruction, 2026-07-25). */}
          <Logo size={32} />
        </Link>
        <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground">
            {/* NAV-AUDIT FIX (2026-07-27): was /explore - same fix as
                web/app/docs/page.tsx, see that file's comment. */}
            <Link href="/">
              <ArrowLeft className="size-3.5 shrink-0" aria-hidden="true" /> {t("navBackHome")}
            </Link>
          </Button>
          {/* vd_authed hint swaps this pair for "Open console". */}
          <PublicAuthCtas>
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground hover:text-foreground"
              onClick={() => auth.login({ redirectUri: `${window.location.origin}/` })}
              data-testid="login-landing-button"
            >
              {t("navSignIn")}
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/signup">{t("navStartFree")}</Link>
            </Button>
          </PublicAuthCtas>
        </div>
      </nav>

      {/* ------------------------------------------------------------- Header */}
      <header className="mx-auto max-w-3xl px-6 pb-12 pt-10 text-center">
        <span className="font-mono text-xs uppercase tracking-[0.18em] text-primary">{t("eyebrow")}</span>
        <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("heroTitle")}</h1>
        <p className="mx-auto mt-4 max-w-xl leading-relaxed text-muted-foreground">{t("heroSubtitle")}</p>
        {isIndia && (
          <div className="mx-auto mt-6 flex max-w-full flex-wrap items-center justify-center gap-2 rounded-2xl border border-primary/25 bg-primary/[0.06] px-4 py-1.5 text-center text-xs font-medium text-primary">
            <IndianRupee className="size-3.5 flex-shrink-0" aria-hidden="true" />
            <span>
              {tHC("s1")}
            </span>
          </div>
        )}
      </header>

      {/* ------------------------------------------- Monthly/Annual toggle */}
      <div className="mx-auto mb-8 flex max-w-6xl flex-wrap items-center justify-center gap-2 px-6">
        <div
          className="inline-flex items-center rounded-full border border-border bg-card/60 p-1"
          role="group"
          aria-label={t("billingPeriodToggleAria")}
        >
          <button
            type="button"
            aria-pressed={!annual}
            onClick={() => setAnnual(false)}
            className={`rounded-full border-0 px-4 py-1.5 text-sm font-medium transition ${
              !annual
                ? "bg-primary text-primary-foreground"
                : "bg-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {t("billingPeriodMonthly")}
          </button>
          <button
            type="button"
            aria-pressed={annual}
            onClick={() => setAnnual(true)}
            className={`rounded-full border-0 px-4 py-1.5 text-sm font-medium transition ${
              annual
                ? "bg-primary text-primary-foreground"
                : "bg-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {t("billingPeriodAnnual")}
          </button>
        </div>
        <CurrencySwitch currency={currency} detected={detected} choices={choices} onChange={choose} />
        <Badge variant="outline" className="border-primary/40 text-primary">
          {t("annualTwoMonthsFree")}
        </Badge>
      </div>

      {/* -------------------------------------------------------- Tier cards */}
      <section className="mx-auto grid max-w-6xl gap-6 px-6 pb-6 md:grid-cols-3">
        {TIERS.map((tier) => (
          <Card
            key={tier.plan}
            className={`relative gap-0 py-6 ${
              tier.highlighted ? "border-primary/60 shadow-[0_0_0_1px_var(--primary)] shadow-2xl" : "border-border/80"
            }`}
          >
            {tier.highlighted && (
              <Badge className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 py-1">{t("mostPopular")}</Badge>
            )}
            <CardContent className="flex h-full flex-col">
              <div className="text-base font-semibold">{t(tier.nameKey)}</div>
              <p className="mt-1 text-sm leading-snug text-muted-foreground">{t(tier.taglineKey)}</p>
              <div className="mt-5 flex items-baseline gap-1.5">
                <span className="font-mono text-4xl font-semibold tracking-tight tabular-nums">
                  {annual
                    ? currency === "INR"
                      ? tier.priceInrAnnual
                      : tier.priceUsdAnnual
                    : currency === "INR"
                      ? tier.priceInr
                      : tier.priceUsd}
                </span>
                <span className="text-sm text-muted-foreground">
                  {annual ? t("perYearSuffix") : t("perMonthSuffix")}
                </span>
              </div>
              {/* Honest regional-pricing line (audit §2.5-2): ₹ and $ are two
                  deliberate regional price points, not one price at two FX
                  quotes - say so, for paid tiers, in both regions. */}
              {tier.plan === "FREE" ? (
                <p className="mt-1 text-xs text-muted-foreground">{t("freeBothRegionsNote")}</p>
              ) : (
                <p className="mt-1 text-xs text-muted-foreground">
                  {t("regionalPricingNote", {
                    inr:
                      (annual ? tier.priceInrAnnual : tier.priceInr) +
                      (annual ? t("perYearSuffix") : t("perMonthSuffix")),
                    usd:
                      (annual ? tier.priceUsdAnnual : tier.priceUsd) +
                      (annual ? t("perYearSuffix") : t("perMonthSuffix")),
                  })}
                </p>
              )}
              {annual && tier.plan !== "FREE" && (
                <p className="mt-1 text-xs font-medium text-primary">{t("annualTwoMonthsFree")}</p>
              )}

              <ul className="mt-6 flex list-none flex-col gap-3 border-t border-border p-0 pt-5">
                {tier.featureKeys.map((key) => (
                  <li key={key} className="flex items-start gap-2.5 text-sm leading-snug">
                    <Check
                      size={15}
                      strokeWidth={2.5}
                      className="mt-0.5 flex-shrink-0 text-primary"
                      aria-hidden="true"
                    />
                    <span>{t(key)}</span>
                  </li>
                ))}
              </ul>

              {tier.noteKey && <p className="mt-4 text-xs leading-snug text-muted-foreground/80">{t(tier.noteKey)}</p>}

              <div className="mt-auto pt-6">
                <Button asChild variant={tier.highlighted ? "default" : "outline"} className="w-full">
                  <Link href="/signup">{t(tier.ctaKey)}</Link>
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      <p className="mx-auto max-w-3xl px-6 pb-8 pt-4 text-center text-xs text-muted-foreground">
        {t("footerNotePrefix")}{" "}
        <button
          type="button"
          className="font-medium text-primary hover:underline"
          onClick={() => auth.login({ redirectUri: `${window.location.origin}/` })}
        >
          {t("footerSignIn")}
        </button>{" "}
        {t("footerNoteSuffix")}
      </p>

      {/* --------------------------------------------------- Comparison table */}
      <section className="mx-auto max-w-4xl px-6 pb-20 pt-6">
        <h2 className="mb-5 text-center text-xl font-semibold tracking-tight">{t("compareTitle")}</h2>
        <div className="rounded-xl border border-border bg-card/60">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t("tableFeature")}</TableHead>
                <TableHead>{t("tableFree")}</TableHead>
                <TableHead className="bg-primary/10 text-primary">{t("tablePremium")}</TableHead>
                <TableHead>{t("tableUnlimited")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {COMPARISON_ROWS.map((row) => (
                <TableRow key={row.labelKey}>
                  <TableCell className="font-medium">{t(row.labelKey)}</TableCell>
                  <TableCell className="text-muted-foreground">{t(row.freeKey)}</TableCell>
                  <TableCell className="bg-primary/[0.06] font-medium">{t(row.premiumKey)}</TableCell>
                  <TableCell className="text-muted-foreground">{t(row.unlimitedKey)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>
    </div>
  );
}
