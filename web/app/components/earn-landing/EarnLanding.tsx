"use client";

import { motion } from "motion/react";
import Image from "next/image";
import Link from "next/link";
import {
  ArrowRight,
  GraduationCap,
  Wand2,
  KeyRound,
  Search,
  ShieldCheck,
  Percent,
  MessageCircle,
  Users,
  Wrench,
  ExternalLink,
  TrendingUp,
  Store,
  BadgeCheck,
  Handshake,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { sendLandingEvent } from "../demo-landing/landingBeacon";
import { academyHref } from "../../lib/academyUrl";
import PublicAuthCtas from "../PublicAuthCtas";
import { Button } from "../ui/button";
import SensesStrip from "../marketing/SensesStrip";
import { ENTITY_FACTS } from "../../lib/platform-facts";
import { isBillingEnabled } from "../../lib/billing-flag";
import BrandText from "../BrandText";

// /earn — the automation-entrepreneur playbook (2026-08-03). Public route,
// standalone chrome (no AppShell/useAuth — PUBLIC_ROUTES mount with no
// AuthContext; an AppShell here would be an SSR 500, the /docs pattern).
//
// Editorial rules this page lives by (same as the workforce band):
//   * Every stat card links to a FIRST-PARTY source (Upwork IR, Salesforce
//     News, Fin.ai's own pricing page, Grand View Research). Vendor-blog
//     "agencies charge $X" ranges and "$10K/month" guru claims were
//     researched and deliberately EXCLUDED — they don't survive the "every
//     number links to its source" rule.
//   * The ROI example is arithmetic, clearly labeled illustrative — never a
//     fabricated customer story.
//   * The partner/commission program is honestly framed as NOT LIVE YET.
const STATS = [
  {
    icon: TrendingUp,
    value: "+109%",
    labelKey: "earnPage_stat1Label",
    source: "Upwork In-Demand Skills · 2026",
    href: "https://investors.upwork.com/news-releases/news-release-details/upworks-demand-skills-2026-demand-top-ai-skills-more-doubles-ai",
  },
  {
    icon: Store,
    value: "91%",
    labelKey: "earnPage_stat2Label",
    source: "Salesforce SMB AI Trends · 2025",
    href: "https://www.salesforce.com/news/stories/smbs-ai-trends-2025/",
  },
  {
    icon: Percent,
    value: "$0.99",
    labelKey: "earnPage_stat3Label",
    source: "Fin.ai official pricing",
    href: "https://fin.ai/pricing",
  },
  {
    icon: BadgeCheck,
    value: "49.6%",
    labelKey: "earnPage_stat4Label",
    source: "Grand View Research · 2026",
    href: "https://www.grandviewresearch.com/industry-analysis/ai-agents-market-report",
  },
] as const;

const PLAYBOOK = [
  { icon: GraduationCap, titleKey: "earnPage_s1Title", bodyKey: "earnPage_s1Body", ctaKey: "earnPage_s1Cta", external: true, hrefFn: () => academyHref(), event: "earn_academy_click" as const },
  { icon: KeyRound, titleKey: "earnPage_s2Title", bodyKey: "earnPage_s2Body", ctaKey: "earnPage_s2Cta", href: "/billing" },
  { icon: Search, titleKey: "earnPage_s3Title", bodyKey: "earnPage_s3Body", ctaKey: "earnPage_s3Cta", href: "/help/workflow-recipes" },
  { icon: Wand2, titleKey: "earnPage_s4Title", bodyKey: "earnPage_s4Body", ctaKey: "earnPage_s4Cta", href: "/try" },
  { icon: Percent, titleKey: "earnPage_s5Title", bodyKey: "earnPage_s5Body" },
];

const STACK = [
  { icon: MessageCircle, titleKey: "earnPage_stack1Title", bodyKey: "earnPage_stack1Body" },
  { icon: KeyRound, titleKey: "earnPage_stack2Title", bodyKey: "earnPage_stack2Body" },
  { icon: ShieldCheck, titleKey: "earnPage_stack3Title", bodyKey: "earnPage_stack3Body" },
  { icon: Wrench, titleKey: "earnPage_stack4Title", bodyKey: "earnPage_stack4Body" },
];

export default function EarnLanding() {
    const tHC = useTranslations("hcComponentsEarnlandingEarnLanding");
  const t = useTranslations("landing");

  return (
    <main className="vd-pub marketing-root relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      {/* Ambient backdrop — same register as the root landing */}
      <div aria-hidden="true" className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 left-1/2 h-[42rem] w-[42rem] -translate-x-1/2 rounded-full bg-primary/15 blur-[140px]" />
        <div className="absolute top-1/2 -right-40 h-[30rem] w-[30rem] rounded-full bg-primary/10 blur-[130px]" />
      </div>

      {/* Lean standalone nav (no AppShell on public routes) */}
      <header className="sticky top-0 z-50 border-b border-border/60 bg-background/70 backdrop-blur-xl">
        <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5 sm:px-8">
          <Link href="/" className="flex shrink-0 items-center gap-2">
            <Image
              src="/logo-mark.png"
              alt=""
              height={28}
              width={38}
              className="h-7 w-auto shrink-0 object-contain"
              style={{ height: 28, width: "auto" }}
              priority
            />
            <span className="brand-name text-lg font-semibold tracking-tight">
              Vega<span className="text-primary">Dūta</span>
            </span>
          </Link>
          <div className="flex items-center gap-2">
            <Button asChild variant="outline" size="sm">
              <a href={academyHref()} target="_blank" rel="noopener noreferrer">
                <GraduationCap className="size-4" />
                <span className="hidden sm:inline">{t("nav_academyPrefix")} </span>
                {t("nav_academyFree")}
              </a>
            </Button>
            {/* vd_authed hint swaps "Get started" for "Open console". */}
            <PublicAuthCtas>
              <Button asChild size="sm" onClick={() => sendLandingEvent("earn_signup_click")}>
                <Link href="/signup">
                  <span className="sm:hidden">{t("nav_getStartedShort")}</span>
                  <span className="hidden sm:inline">{t("nav_getStarted")}</span>
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
            </PublicAuthCtas>
          </div>
        </nav>
      </header>

      {/* Hero */}
      <section className="mx-auto max-w-4xl px-5 pb-16 pt-16 text-center sm:px-8 sm:pt-24">
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }}
        >
          <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-primary/30 bg-primary/10 px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.16em] text-primary">
            <Handshake className="size-3.5" />
            {t("earnPage_badge")}
          </div>
          <h1 className="text-balance text-4xl font-black leading-[1.08] tracking-tight sm:text-6xl">
            {t("earnPage_h1_a")}
            <span
              className="bg-clip-text text-transparent"
              style={{ backgroundImage: "var(--accent-gradient)" }}
            >
              {t("earnPage_h1_em")}
            </span>
          </h1>
          <p className="mx-auto mt-6 max-w-2xl text-pretty text-lg leading-relaxed text-foreground/80">
            {t("earnPage_lede")}
          </p>
          <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button asChild size="lg" onClick={() => sendLandingEvent("earn_academy_click")}>
              <a href={academyHref()} target="_blank" rel="noopener noreferrer">
                <GraduationCap className="size-4" />
                {t("earnPage_ctaAcademy")}
              </a>
            </Button>
            <Button asChild variant="outline" size="lg">
              <Link href="/try">{t("earnPage_ctaTry")}</Link>
            </Button>
          </div>
        </motion.div>
      </section>

      {/* The senses, compact — what the product you'd resell can actually
          do; links back to the landing's full senses band. */}
      <SensesStrip />

      {/* Why now — sourced stat cards */}
      <section className="border-y border-border/50 bg-secondary/[0.12] px-5 py-16 sm:px-8">
        <div className="mx-auto max-w-6xl">
          <div className="mx-auto max-w-3xl text-center">
            <h2 className="text-balance text-3xl font-bold tracking-tight">{t("earnPage_whyNowTitle")}</h2>
            <p className="mt-2 text-sm uppercase tracking-[0.16em] text-muted-foreground/70">
              {t("earnPage_whyNowSub")}
            </p>
            <p className="mt-6 text-pretty text-xl font-semibold text-foreground/90">
              “{t("earnPage_whyNowPull")}”
            </p>
          </div>
          <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {STATS.map(({ icon: Icon, value, labelKey, source, href }, i) => (
              <motion.a
                key={labelKey}
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className="group flex h-full flex-col rounded-2xl border border-border/70 bg-card/70 p-6 transition-colors hover:border-primary/40"
                initial={{ opacity: 0, y: 20 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true, amount: 0.3 }}
                transition={{ duration: 0.45, delay: i * 0.07 }}
              >
                <Icon className="size-5 text-primary" />
                <p className="mt-3 text-3xl font-black tracking-tight">{value}</p>
                <p className="mt-2 flex-1 text-sm leading-snug text-foreground/75">{t(labelKey)}</p>
                <p className="mt-4 inline-flex items-center gap-1.5 text-[11px] font-medium text-muted-foreground transition-colors group-hover:text-foreground">
                  {source}
                  <ExternalLink className="size-3" />
                </p>
              </motion.a>
            ))}
          </div>
        </div>
      </section>

      {/* The playbook */}
      <section className="mx-auto max-w-4xl px-5 py-16 sm:px-8 sm:py-24">
        <div className="text-center">
          <h2 className="text-balance text-3xl font-bold tracking-tight sm:text-4xl">
            {t("earnPage_playbookTitle")}
          </h2>
          <p className="mt-3 text-muted-foreground">{t("earnPage_playbookSub")}</p>
        </div>
        <div className="mt-12 space-y-6">
          {PLAYBOOK.map(({ icon: Icon, titleKey, bodyKey, ctaKey, href, hrefFn, external, event }, i) => (
            <motion.div
              key={titleKey}
              className="flex gap-5 rounded-2xl border border-border/70 bg-card/60 p-6 sm:p-7"
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, amount: 0.3 }}
              transition={{ duration: 0.45, delay: i * 0.05 }}
            >
              <div className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary/15 text-primary">
                <Icon className="size-5" />
              </div>
              <div>
                <h3 className="text-lg font-bold tracking-tight">{t(titleKey)}</h3>
                <p className="mt-2 text-sm leading-relaxed text-foreground/75">{t(bodyKey)}</p>
                {/* Billing off (lib/billing-flag.ts): no "See plans & pricing" CTA. */}
                {ctaKey && (isBillingEnabled() || href !== "/billing") && (
                  <div className="mt-4">
                    {external ? (
                      <Button
                        asChild
                        variant="outline"
                        size="sm"
                        onClick={event ? () => sendLandingEvent(event) : undefined}
                      >
                        <a href={hrefFn ? hrefFn() : href} target="_blank" rel="noopener noreferrer">
                          {t(ctaKey)}
                          <ArrowRight className="size-4" />
                        </a>
                      </Button>
                    ) : (
                      <Button asChild variant="outline" size="sm">
                        <Link href={href!}>
                          {t(ctaKey)}
                          <ArrowRight className="size-4" />
                        </Link>
                      </Button>
                    )}
                  </div>
                )}
              </div>
            </motion.div>
          ))}
        </div>
      </section>

      {/* ROI math — arithmetic, not testimonials */}
      <section className="border-y border-border/50 bg-secondary/[0.12] px-5 py-16 sm:px-8">
        <div className="mx-auto max-w-4xl">
          <div className="text-center">
            <h2 className="text-balance text-3xl font-bold tracking-tight">{t("earnPage_mathTitle")}</h2>
            <p className="mt-3 text-muted-foreground">{t("earnPage_mathSub")}</p>
          </div>
          <div className="mt-10 space-y-4">
            {["earnPage_mathStep1", "earnPage_mathStep2", "earnPage_mathStep3"].map((k, i) => (
              <div key={k} className="flex items-start gap-4 rounded-xl border border-border/60 bg-card/60 px-5 py-4">
                <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/15 text-sm font-bold text-primary">
                  {i + 1}
                </span>
                <p className="text-sm leading-relaxed text-foreground/80">{t(k)}</p>
              </div>
            ))}
          </div>
          <p className="mt-6 text-center text-xs leading-relaxed text-muted-foreground">
            {t("earnPage_mathNote")}
          </p>
        </div>
      </section>

      {/* The stack you build with */}
      <section className="mx-auto max-w-6xl px-5 py-16 sm:px-8 sm:py-24">
        <div className="mx-auto max-w-3xl text-center">
          <h2 className="text-balance text-3xl font-bold tracking-tight">{t("earnPage_stackTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("earnPage_stackSub")}</p>
        </div>
        <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {STACK.map(({ icon: Icon, titleKey, bodyKey }) => (
            <div key={titleKey} className="rounded-2xl border border-border/70 bg-card/60 p-6">
              <Icon className="size-5 text-primary" />
              <h3 className="mt-3 font-semibold">{t(titleKey)}</h3>
              <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{t(bodyKey)}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Students */}
      <section className="border-y border-border/50 bg-primary/[0.05] px-5 py-16 sm:px-8">
        <div className="mx-auto max-w-3xl text-center">
          <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-primary/30 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
            <Users className="size-3.5" />
            {t("earnPage_studentTitle")}
          </div>
          <p className="text-pretty text-lg leading-relaxed text-foreground/85">
            {t("earnPage_studentBody")}
          </p>
          <p className="mt-5 text-sm leading-relaxed text-muted-foreground">
            {t("earnPage_studentStat")}{" "}
            <a
              href="https://online.fit.edu/degrees/graduate/business/mba/gen-z-ai-side-hustle/"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium underline-offset-2 hover:underline"
            >
              {tHC("s1")} <ExternalLink className="size-3" />
            </a>
          </p>
        </div>
      </section>

      {/* Partner program — honestly not live yet */}
      <section className="mx-auto max-w-3xl px-5 py-16 text-center sm:px-8">
        <h2 className="text-balance text-2xl font-bold tracking-tight">{t("earnPage_partnerTitle")}</h2>
        <p className="mx-auto mt-4 max-w-2xl text-pretty text-sm leading-relaxed text-foreground/75">
          <BrandText text={t("earnPage_partnerBody")} />
        </p>
        <div className="mt-6">
          <Button asChild variant="outline">
            <a href={`mailto:${ENTITY_FACTS.contactEmail}?subject=Partner%20program%20—%20register%20interest`}>
              <Handshake className="size-4" />
              {t("earnPage_partnerCta")}
            </a>
          </Button>
        </div>
      </section>

      {/* Closing */}
      <section className="border-t border-border/60 px-5 py-20 text-center sm:px-8">
        <div className="mx-auto max-w-2xl">
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
            {t("earnPage_closingTitle")}
          </h2>
          <p className="mx-auto mt-3 max-w-xl text-pretty text-muted-foreground">
            {t("earnPage_closingSub")}
          </p>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button asChild size="lg" onClick={() => sendLandingEvent("earn_academy_click")}>
              <a href={academyHref()} target="_blank" rel="noopener noreferrer">
                {t("earnPage_ctaAcademy")}
                <ArrowRight className="size-4" />
              </a>
            </Button>
            <PublicAuthCtas size="lg" variant="outline">
              <Button asChild variant="outline" size="lg" onClick={() => sendLandingEvent("earn_signup_click")}>
                <Link href="/signup">{t("nav_getStarted")}</Link>
              </Button>
            </PublicAuthCtas>
          </div>
          <p className="mt-6 text-[11px] uppercase tracking-[0.16em] text-muted-foreground/70">
            {t("earnPage_sourceNote")}
          </p>
        </div>
      </section>
    </main>
  );
}
