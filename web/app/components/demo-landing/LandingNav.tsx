"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import {
  Sparkles,
  ArrowRight,
  GraduationCap,
  Globe,
  Brain,
  Menu,
  X,
  Play,
  Shield,
  Zap,
  Users,
  BookOpen,
  BarChart3,
  LogIn,
  Briefcase,
  LayoutTemplate,
  Mail,
} from "lucide-react";
import { sendLandingEvent } from "./landingBeacon";
import { auth } from "../../lib/auth";
import { academyHref } from "../../lib/academyUrl";
import LanguageSwitcher from "../LanguageSwitcher";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import ThemePicker from "../ThemePicker";
import BrandText from "../BrandText";
import type { useJourney } from "../../lib/journey";

// Shared by both the real inline nav-links row and its off-screen
// measurement twin (see the nav's useLayoutEffect below) so the two never
// drift out of sync - a link added to one without the other would silently
// break either the visible row or the overflow measurement.
//
// Trimmed 7 -> 4 (funnel audit, 2026-09-09): Who it's for / Features /
// Security are in-page anchors a visitor reaches by scrolling anyway, and
// the row was measured at its width cap in English with all seven (see the
// linksFit comment below) — so the three that add the least routing value
// moved to the hamburger sheet (MENU_ANCHOR_ITEMS) and the footer, where
// there's no width constraint. Fewer nav links = the primary CTA is reached
// with less competing for the eye before it.
const NAV_LINK_ITEMS: Array<{ href: string; labelKey: string; internal?: boolean; iconOnly?: boolean }> = [
  { href: "#live-studio", labelKey: "nav_liveDemo" },
  // Repointed from #why-agentic (2026-08-14 content-reduction pass):
  // DayInTheLife (which owned that id) is unmounted now, and "how it
  // works" is SensesShowcase's job — its id stays "senses" (not renamed)
  // because SensesStrip.tsx links to /#senses from elsewhere in the app.
  // 2026-09-17: #senses was unmounted on 2026-09-12, so this anchor scrolled
  // nowhere for five days. "How it works" is OutcomesBand's job now.
  { href: "#outcomes", labelKey: "nav_howItWorks" },
  // Icon-only at this width (2026-08-30, measured overflow fix — see the
  // linksFit comment below): Docs is the most defensible of the row to
  // compact since it's also separately reachable from the footer. Text
  // label survives as a title tooltip + sr-only span, not dropped.
  { href: "/docs", labelKey: "nav_docs", internal: true, iconOnly: true },
];
// Every in-page anchor the hamburger sheet's "How it works" group offers:
// the inline row's anchors PLUS the three demoted out of the sticky nav
// above. Kept as one explicit list (not derived by filtering NAV_LINK_ITEMS)
// so trimming the nav further never silently removes a sheet entry.
const MENU_ANCHOR_ITEMS: Array<{ href: string; labelKey: string }> = [
  { href: "#live-studio", labelKey: "nav_liveDemo" },
  // 2026-09-17: three of these pointed at bands unmounted on 2026-09-12
  // (#senses, #audience-showcase, #features). Repointed at the bands that
  // now answer the same question: outcomes, the case-study industry
  // picker, and the platform ledger.
  { href: "#outcomes", labelKey: "nav_howItWorks" },
  // Use-cases band (2026-09-24) — the "what would I build" anchor.
  { href: "#use-cases", labelKey: "nav_useCases" },
  { href: "#business-case", labelKey: "nav_whoItsFor" },
  { href: "#platform-ledger", labelKey: "nav_features" },
  { href: "#security", labelKey: "nav_security" },
  // 2026-09-12: the contact banner gets a route from the chrome, so a visitor
  // who wants a human in the first five seconds doesn't have to scroll
  // sixteen bands to find one. Deliberately added HERE and not to
  // NAV_LINK_ITEMS: that inline row is width-measured, and if its items stop
  // fitting the whole row collapses into this sheet — so a seventeenth
  // character of nav label could silently cost every inline link at desktop
  // widths. The sheet has no such constraint.
  { href: "#contact", labelKey: "nav_contact" },
];
// px-1.5 (was px-3) — tightened alongside the gap-0.5/text-xs container
// changes below as part of the same overflow fix; a real DOM+CSS
// remeasurement (off-screen harness reproducing this exact row, Geist font,
// 1280px viewport) showed the previous px-3/gap-1/text-sm row needed 615px
// against 398px available (217px short) - tightening padding+gap+font-size
// here plus the Academy-pill and Docs changes below recovers ~234px,
// closing the gap with a real, not eyeballed, margin.
const NAV_LINK_CLS = "whitespace-nowrap rounded-md px-1.5 py-2 transition-colors hover:text-foreground";

// Sticky landing nav: inline link row (only when measured to fit), hamburger
// sheet, condense-on-scroll sentinel. Extracted verbatim from DemoLanding.
export default function LandingNav({ journey, tried }: { journey: ReturnType<typeof useJourney>; tried: boolean }) {
  const t = useTranslations("landing");
  // Separate namespace: "languageSwitcher.label" is the one already-translated
  // "Language" string present in all 30 locale files, so the sheet's heading
  // below needs no new key (a key missing from any locale renders its dotted
  // path rather than English - see lib/i18n.tsx).
  const tLang = useTranslations("languageSwitcher");
  const signIn = useCallback(() => {
    // `void`: resolves only by navigating away to Keycloak — nothing to
    // await or handle here (no-floating-promises lint).
    void auth.login({ redirectUri: `${window.location.origin}/` });
  }, []);

  // Mobile/tablet nav sheet — see the nav's useLayoutEffect below for why
  // its visibility is measured, not a fixed breakpoint. Anchors close the
  // sheet so the jump is immediately visible.
  const [menuOpen, setMenuOpen] = useState(false);
  const [isCompactViewport, setIsCompactViewport] = useState(false);

  useEffect(() => {
    const updateCompactState = () => setIsCompactViewport(window.innerWidth <= 480);
    updateCompactState();
    window.addEventListener("resize", updateCompactState);
    return () => window.removeEventListener("resize", updateCompactState);
  }, []);

  // Nav-links overflow measurement (direct feedback, 2026-08-03): a fixed
  // Tailwind breakpoint (previously xl:flex) was calibrated against ENGLISH
  // label widths - Kannada and other Indic-script locales render the same
  // seven labels + Academy pill + Sign in + Get started noticeably wider,
  // overflowing the nav AND the viewport (no scrollbar - silently clipped)
  // at viewport widths where English fit fine, making the Get started CTA
  // and hamburger unreachable. Since this app ships 22+ languages and their
  // relative widths aren't ours to predict, no single static breakpoint can
  // be correct for all of them - so this measures the REAL rendered width
  // of the current locale's links (via an off-screen twin - navLinksMeasureRef
  // - that always renders at natural width regardless of whether the real
  // row is shown) against the space actually free in the nav, and only
  // shows the inline row when it verifiably fits. Anything that doesn't fit
  // falls back to the hamburger sheet, which carries every same destination.
  const navRowRef = useRef<HTMLElement>(null);
  const navLogoRef = useRef<HTMLAnchorElement>(null);
  const navLinksMeasureRef = useRef<HTMLDivElement>(null);
  const navActionsRef = useRef<HTMLDivElement>(null);
  const [linksFit, setLinksFit] = useState(false);
  const showInlineLinks = linksFit && !isCompactViewport;
  const showMobileMenu = !showInlineLinks;

  useLayoutEffect(() => {
    const nav = navRowRef.current;
    const logo = navLogoRef.current;
    const measure = navLinksMeasureRef.current;
    const actions = navActionsRef.current;
    if (!nav || !logo || !measure || !actions) return undefined;

    // Small fixed allowance for the row's own gap-4 (16px) between the three
    // groups (logo/links/actions) plus a little breathing room - the exact
    // value only needs to be conservative, not exact, since a false "doesn't
    // fit" just means an unnecessary hamburger, never an overflow.
    const GAP_ALLOWANCE = 64;

    function recompute() {
      const available = nav.clientWidth - logo.offsetWidth - actions.offsetWidth - GAP_ALLOWANCE;
      setLinksFit(measure.scrollWidth <= available);
    }

    recompute();
    const observer = new ResizeObserver(recompute);
    observer.observe(nav);
    observer.observe(actions);
    return () => observer.disconnect();
    // Re-measure whenever the active locale changes label text/width - t
    // itself isn't a stable dependency, so key off the one thing that
    // actually changes per locale switch: the rendered link labels.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t("nav_liveDemo"), t("nav_howItWorks")]);

  // Nav condense-on-scroll. A 1px sentinel sits immediately above the sticky
  // header; the moment it leaves the viewport the header has stuck, and
  // `.is-stuck` raises its background opacity, draws its bottom hairline and
  // lifts it onto a shadow (marketing-system.css §9). Deliberately an
  // IntersectionObserver rather than a scroll listener: a scroll handler on a
  // page this long fires hundreds of times a second and every one of them
  // reads layout. This fires exactly twice per direction change.
  const navSentinelRef = useRef<HTMLDivElement>(null);
  const [navStuck, setNavStuck] = useState(false);

  useEffect(() => {
    const el = navSentinelRef.current;
    if (!el || typeof IntersectionObserver === "undefined") return undefined;
    const observer = new IntersectionObserver(([entry]) => setNavStuck(!entry.isIntersecting));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <>
      {/* Lean sticky nav. `.mkt-nav` owns sticky/backdrop/transition;
          `.is-stuck` is the condensed state (see the sentinel effect above).
          At the very top of the page the header is nearly transparent so the
          hero's aurora reads as one continuous surface behind it — the header
          only materialises once there is content scrolling under it. */}
      <div ref={navSentinelRef} aria-hidden="true" className="h-px w-full" />
      <header className={`mkt-nav${navStuck ? " is-stuck" : ""}`}>
        <nav ref={navRowRef} className="relative mx-auto flex h-16 max-w-6xl items-center gap-4 px-5 sm:px-8">
          {/* shrink-0: the brand must never be the flex item that gives way —
              without it, when the row got tight the anchor shrank below its
              content and the first nav link rendered THROUGH the wordmark
              ("VegaDūtaLive demo", user screenshot 2026-07-28). */}
          <a href="#top" ref={navLogoRef} className="flex shrink-0 items-center gap-2" aria-label={t("nav_homeAria")}>
            {/* The human-in-helmet winged emblem, always (direct instruction,
                2026-07-25) — height-fixed, width free, own colors, no tile. */}
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
          </a>
          {/* Shown only once measured to actually fit (see the linksFit
              useLayoutEffect above) - a fixed Tailwind breakpoint here was
              calibrated against English label widths and overflowed for
              longer-rendering locales like Kannada. The off-screen
              navLinksMeasureRef twin just below always renders at natural
              width so that measurement stays correct regardless of which
              locale is active, without ever showing overflowing content. */}
          {showInlineLinks && (
            <div className="ms-2 flex items-center gap-0.5 whitespace-nowrap text-xs text-muted-foreground">
              {NAV_LINK_ITEMS.map((item) => {
                const cls = item.iconOnly ? `${NAV_LINK_CLS} inline-flex items-center justify-center` : NAV_LINK_CLS;
                const content = item.iconOnly ? (
                  <>
                    <BookOpen className="size-4" aria-hidden="true" />
                    <span className="sr-only">{t(item.labelKey)}</span>
                  </>
                ) : (
                  t(item.labelKey)
                );
                const title = item.iconOnly ? t(item.labelKey) : undefined;
                return item.internal ? (
                  <Link key={item.href} href={item.href} className={cls} title={title}>
                    {content}
                  </Link>
                ) : (
                  <a key={item.href} href={item.href} className={cls} title={title}>
                    {content}
                  </a>
                );
              })}
              {/* /compare, /why-vegaduta deliberately NOT added here (content-
                  onpage SEO audit, 2026-07-30): this row is capped at
                  max-w-6xl regardless of viewport, and even the trimmed
                  4-link row + Academy pill + language + Sign in + Create
                  free account is measured, not assumed, to fit - untested
                  further additions risk the row no longer fitting in
                  longer-rendering locales. Added to the hamburger sheet and
                  the footer instead, where there's no such constraint. */}
            </div>
          )}
          {/* Off-screen measurement twin: identical links, always mounted at
              natural (unwrapped) width regardless of whether the real row
              above is shown - this is what the layout effect measures
              against available nav space to decide linksFit. invisible +
              absolute so it never affects visible layout or a11y (aria-hidden). */}
          <div
            ref={navLinksMeasureRef}
            aria-hidden="true"
            className="pointer-events-none invisible absolute left-0 top-0 flex items-center gap-0.5 whitespace-nowrap text-xs"
            style={{ width: "max-content" }}
          >
            {NAV_LINK_ITEMS.map((item) => (
              <span
                key={item.href}
                className={item.iconOnly ? `${NAV_LINK_CLS} inline-flex items-center justify-center` : NAV_LINK_CLS}
              >
                {item.iconOnly ? <BookOpen className="size-4" aria-hidden="true" /> : t(item.labelKey)}
              </span>
            ))}
          </div>
          <div ref={navActionsRef} className="ms-auto flex min-w-0 shrink-0 items-center gap-1.5 sm:gap-3">
            {/* Academy pill — always visible (incl. mobile). Was "Academy ·
                learn free" spelled out at >=640px (verbose enough to be
                part of the 217px nav-links overflow at 1280px, measured
                2026-08-30); now always the same compact "Academy" text
                mobile already used at every width - one rendering, not a
                breakpoint-gated pair. */}
            {/* 2026-09-12: "always visible (incl. mobile)" no longer holds at
                <=480px, and the reason is measured, not preference. Making the
                signup button visible on phones (see below) put five controls in
                a 375px row: the actions block ran to x=468 and the HAMBURGER
                was clipped off-screen, taking every nav anchor, Pricing, Docs,
                /hire, /templates and the language switcher with it. Something
                had to leave. Academy is a separate learning deployable, still
                reachable from the hamburger's Products grid (added in the same
                change), the closing chips and the footer; the free account is
                what this page exists to open, so the account stays. */}
            {!isCompactViewport && (
              <a
                href={academyHref()}
                className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-primary/40 bg-primary/10 px-2.5 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/20 sm:px-3 sm:text-xs"
              >
                <GraduationCap className="size-3.5" />
                <span>Academy</span>
              </a>
            )}
            {/* At <=480px the nav row has no room for this (Academy + Sign in
                + hamburger already fill it), so the compact rendering lives in
                the sheet below instead - see the Language section there.
                Previously it was gated off here and NOT re-added anywhere,
                leaving phones with no way at all to change language on the
                landing page (mobile audit, 2026-09-11). */}
            {!isCompactViewport && <LanguageSwitcher />}
            {/* Theme picker — allows visitors to toggle accent/light/dark on
                public pages. Was a `fixed right-4 top-4 z-50` overlay that sat
                directly under this sticky header (same z-index, painted after
                it in DOM order) and silently ate every click — moved inline
                into the nav row like every other control here instead. */}
            {!isCompactViewport && <ThemePicker />}
            {/* data-testid is the e2e suite's login contract (e2e/fixtures.js
                loginViaKeycloak) — keep it on whatever control starts the
                Keycloak redirect. */}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                // Beaconed (funnel audit, 2026-09-09): "Sign in" was the one
                // nav action with no event, so returning users were
                // indistinguishable from bounces in the read-out.
                sendLandingEvent("nav_signin_click");
                signIn();
              }}
              data-testid="login-landing-button"
              className="inline-flex shrink-0 rounded-md px-2.5 py-1.5 text-xs font-medium sm:text-xs"
            >
              <LogIn className="size-4" aria-hidden="true" />
              {t("nav_signIn")}
            </Button>
            {/* Still /signup, but labelled "Create free account" (funnel
                audit, 2026-09-09) so it reads as the ACCOUNT step rather
                than a second "start here" competing with the hero's /try —
                one page, one primary entry point. */}
            {/* 2026-09-12: this used to be `isCompactViewport ? "hidden"`,
                which meant a phone visitor's chrome offered Academy, Sign in
                and a hamburger — and NO way to create an account — while the
                sheet's own "Sign in" was `sm:block hidden` on top of that. On
                the narrowest widths the page's whole purpose was unreachable
                from the header. It now always renders; at <=480px it drops
                the label to a short one and the arrow so it still fits beside
                Sign in and the hamburger. */}
            <Button asChild size="sm" onClick={() => sendLandingEvent("signup_click")} className="inline-flex">
              <Link href={journey.signupHref}>
                {isCompactViewport
                  ? tried
                    ? t("nav_keepAgentShort")
                    : t("nav_getStartedShort")
                  : tried
                    ? t("hero_ctaKeepAgent")
                    : t("nav_getStarted")}
                {!isCompactViewport && <ArrowRight className="size-4" />}
              </Link>
            </Button>
            {/* Hamburger for whenever the inline links don't fit - measured,
                not a fixed breakpoint, see linksFit above. Before the
                original xl-breakpoint version of this, phones and tablets
                had NO route to the section anchors, Pricing or Docs
                (full-app audit, mobile pass). */}
            {showMobileMenu && (
              <button
                type="button"
                aria-label={t("nav_menuAria")}
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen((o) => !o)}
                className="inline-flex size-9 items-center justify-center rounded-md border border-border/60 bg-background/80 p-0 text-muted-foreground shadow-sm transition-colors hover:text-foreground"
              >
                {menuOpen ? <X className="size-5" /> : <Menu className="size-5" />}
              </button>
            )}
          </div>
        </nav>

        {/* Premium menu sheet — organized horizontally with category headers */}
        {menuOpen && showMobileMenu && (
          <div className="border-t border-border/60 bg-gradient-to-b from-background to-background/50 backdrop-blur-xl">
            <div className="mx-auto max-w-6xl px-5 py-4 sm:px-8 space-y-3">
              {/* How it works section */}
              <div>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-widest text-muted-foreground/70">
                  How it works
                </h3>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-5">
                  {MENU_ANCHOR_ITEMS.map((l) => {
                    const iconMap: Record<string, React.ReactNode> = {
                      nav_liveDemo: <Play className="size-4" />,
                      nav_howItWorks: <Zap className="size-4" />,
                      nav_whoItsFor: <Users className="size-4" />,
                      nav_features: <Sparkles className="size-4" />,
                      nav_security: <Shield className="size-4" />,
                      nav_contact: <Mail className="size-4" />,
                    };
                    return (
                      <a
                        key={l.href}
                        href={l.href}
                        onClick={() => setMenuOpen(false)}
                        className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                      >
                        <span className="text-primary">{iconMap[l.labelKey] || <Globe className="size-4" />}</span>
                        <span className="line-clamp-2 text-xs">{t(l.labelKey)}</span>
                      </a>
                    );
                  })}
                </div>
              </div>

              {/* Products section */}
              <div className="border-t border-border/30 pt-3">
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-widest text-muted-foreground/70">
                  Products
                </h3>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Link
                    href="/hire"
                    onClick={() => setMenuOpen(false)}
                    className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                  >
                    <Briefcase className="size-4 text-primary" aria-hidden="true" />
                    <span className="line-clamp-2 text-xs">{t("menu_hireStaff")}</span>
                  </Link>
                  <Link
                    href="/templates"
                    onClick={() => setMenuOpen(false)}
                    className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                  >
                    <LayoutTemplate className="size-4 text-primary" aria-hidden="true" />
                    <span className="line-clamp-2 text-xs">{t("menu_communityTemplates")}</span>
                  </Link>
                  {/* Academy moves HERE at compact widths (2026-09-12). The
                      header pill is hidden below 480px so the account CTA can
                      have the space instead — measured: with Academy (96px),
                      Sign in, "Sign up free" and the hamburger all in the
                      header at 375px, the row runs to 468px and the hamburger
                      is clipped off-screen entirely. Academy is a separate
                      learning deployable and is also reachable from the
                      closing chips and the footer; the account is what this
                      page exists to open. Rendered only when the pill is
                      hidden, so wider viewports don't get it twice. */}
                  {isCompactViewport && (
                    <a
                      href={academyHref()}
                      onClick={() => setMenuOpen(false)}
                      className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/20 hover:text-foreground"
                    >
                      <GraduationCap className="size-4 text-primary" aria-hidden="true" />
                      <span className="line-clamp-2 text-xs">{t("footer_academy")}</span>
                    </a>
                  )}
                </div>
              </div>

              {/* Resources section */}
              <div className="border-t border-border/30 pt-3">
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-widest text-muted-foreground/70">
                  Resources
                </h3>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Link
                    href="/compare"
                    onClick={() => setMenuOpen(false)}
                    className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                  >
                    <BarChart3 className="size-4 text-primary" aria-hidden="true" />
                    <span className="line-clamp-2 text-xs">{t("nav_compare")}</span>
                  </Link>
                  <Link
                    href="/why-vegaduta"
                    onClick={() => setMenuOpen(false)}
                    className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                  >
                    <Brain className="size-4 text-primary" aria-hidden="true" />
                    <span className="line-clamp-2 text-xs">
                      <BrandText text={t("nav_whyVegaduta")} />
                    </span>
                  </Link>
                  <Link
                    href="/docs"
                    onClick={() => setMenuOpen(false)}
                    className="group flex flex-col items-center gap-1.5 rounded-lg px-2 py-2.5 text-center text-xs font-medium text-muted-foreground transition-all bg-primary/10 hover:bg-primary/15 hover:text-foreground"
                  >
                    <BookOpen className="size-4 text-primary" aria-hidden="true" />
                    <span className="line-clamp-2 text-xs">{t("nav_docs")}</span>
                  </Link>
                </div>
              </div>

              {/* Language - rendered only when the inline switcher in the nav
                  row is gated off, so the two renderings are mutually
                  exclusive and never both on screen (same isCompactViewport
                  test, inverted). */}
              {isCompactViewport && (
                <div className="border-t border-border/30 pt-3">
                  <h3 className="mb-2 text-xs font-semibold uppercase tracking-widest text-muted-foreground/70">
                    {tLang("label")}
                  </h3>
                  <LanguageSwitcher />
                </div>
              )}

              {/* CTA section.

                  Re-ordered 2026-09-12 (persuasion-architecture pass). What
                  was here put PRICING first, rendered in the accent tint at
                  font-semibold with a motion arrow — i.e. the visually
                  strongest control in the sheet — with Sign in beside it and
                  the account CTA trailing underneath. On a phone, where this
                  sheet is the ONLY chrome a visitor gets, that made "what does
                  it cost?" the loudest answer to "what do I do here?", which
                  is the opposite of the page's stated one-entry-point posture
                  (/signup primary, see the closing band's history comment).

                  Now: the account is first and full width, the friction note
                  sits directly under it, and Sign in + Pricing share one
                  quiet, equal-weight row below. Nothing moved destination,
                  label or beacon — only order and weight. */}
              <div className="border-t border-border/30 pt-3">
                {/* Returning visitors sign in; new ones need the account step
                    to be the most reachable thing in the sheet. */}
                <Link
                  href={journey.signupHref}
                  onClick={() => {
                    sendLandingEvent("signup_click");
                    setMenuOpen(false);
                  }}
                  className="group flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground transition-opacity hover:opacity-90"
                >
                  {tried ? t("hero_ctaKeepAgent") : t("nav_getStarted")}
                  <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
                </Link>
                {/* Friction honesty at the chrome's decision point — the same
                    line the hero and the closing band carry. On a phone this
                    button is the most-used account entry on the page and it
                    was the only one of the three that never said what the
                    account costs in time or what it opens. */}
                <p className="mkt-caption mt-2 text-center text-muted-foreground">{t("hero_ctaNote")}</p>
                <div className="mt-3 grid grid-cols-1 gap-2">
                  {/* 2026-09-12: this was `sm:block hidden` — hidden on exactly
                      the narrow viewports that are the only ones guaranteed to
                      SEE this sheet. Together with the header's signup button
                      being hidden at the same widths, a phone visitor had no
                      account action anywhere in the chrome. Both are fixed. */}
                  <button
                    type="button"
                    onClick={() => {
                      sendLandingEvent("nav_signin_click"); // same nav action, sheet form
                      signIn();
                    }}
                    className="flex items-center justify-center gap-2 rounded-lg border border-border/70 px-4 py-2.5 text-sm font-medium text-foreground transition-all hover:border-primary/60 hover:bg-primary/5"
                  >
                    <LogIn className="size-4" aria-hidden="true" />
                    {t("nav_signIn")}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </header>
    </>
  );
}
