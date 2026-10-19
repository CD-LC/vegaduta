"use client";

import Image from "next/image";
import Link from "next/link";
import {
  MessageCircle,
  Phone,
  Send,
  ShieldCheck,
  BookOpen,
  Coffee,
  Boxes,
  Library,
  Scale,
  ExternalLink,
  type LucideIcon,
} from "lucide-react";
import { academyUrl, hasAcademy } from "../../lib/academyUrl";
import LanguageLinks from "../LanguageLinks";
import { useTranslations, useLocale } from "../../lib/i18n";
import { LEGAL_ENTITY, CIN_NUMBER, DIN_NUMBER } from "../../lib/legalEntity";
import { COFFEE_URL, HAS_COFFEE_LINK } from "../../lib/freeMode";
import { filterBillingItems } from "../../lib/billing-flag";
import { REPO_URL, docPageHref, docsHref, hasDocsSite } from "../../lib/siteLinks";

/**
 * The landing page's footer - link columns (with billing-only links filtered
 * out when billing is off), language links and the registered legal entity.
 * Split out of DemoLanding.tsx to keep that file under the file-size ratchet.
 */
export default function SiteFooter() {
  const t = useTranslations("landing");
  const locale = useLocale();
  const year = new Date().getFullYear();
  // Simplified, corporate footer (direct request 2026-07-25: "simplified and
  // corporate" — remove marketing-column clutter, close with the registered
  // legal entity rather than the product name).
  return (
    <footer className="border-t border-border/60 bg-card/20 px-5 py-14 sm:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="grid gap-10 lg:grid-cols-12">
          {/* Brand column */}
          <div className="lg:col-span-5">
            <div className="flex items-center gap-2.5">
              <Image
                src="/logo-mark.png"
                alt=""
                height={32}
                width={44}
                className="h-8 w-auto shrink-0 object-contain"
                style={{ height: 32, width: "auto" }}
                loading="lazy"
              />
              <span className="brand-name text-xl font-semibold tracking-tight">
                Vega<span className="text-primary">Dūta</span>
              </span>
            </div>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-muted-foreground">{t("footer_blurb")}</p>
            <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <MessageCircle className="size-3.5" aria-hidden="true" /> {t("footer_chipWhatsapp")}
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Phone className="size-3.5" /> {t("footer_chipCalls")}
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Send className="size-3.5" /> {t("footer_chipTelegram")}
              </span>
            </div>
            {/* Free-for-everyone note (freeMode.ts): the coffee link renders
                only when NEXT_PUBLIC_COFFEE_URL was baked in - never to an
                invented URL. */}
            {HAS_COFFEE_LINK && (
              <a
                href={COFFEE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-3 inline-flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                <Coffee className="size-3.5" /> {t("footer_buyCoffee")}
              </a>
            )}
          </div>
          {/* Link columns */}
          <div className="grid grid-cols-2 gap-8 sm:grid-cols-4 lg:col-span-7">
            <FooterCol
              title={t("footer_colProduct")}
              icon={Boxes}
              links={[
                { label: t("nav_liveDemo"), href: "#live-studio" },
                // Use-cases band (2026-09-24) — mounted between channels and
                // the process theater; the footer and the hamburger sheet
                // carry the anchor (the inline nav row is width-capped).
                { label: t("nav_useCases"), href: "#use-cases" },
                // Who it's for / Features anchors: demoted out of the sticky
                // nav (2026-09-09, see NAV_LINK_ITEMS) — the footer and the
                // hamburger sheet are their homes now, alongside Security.
                { label: t("nav_whoItsFor"), href: "#business-case" },
                { label: t("nav_features"), href: "#platform-ledger" },
                { label: t("nav_security"), href: "#security" },
                { label: t("nav_whyVegaduta"), href: "/why-vegaduta" },
                // Public community gallery (2026-07-31).
                { label: t("menu_communityTemplates"), href: "/templates" },
                // /hire dossier page (2026-07-31).
                { label: t("menu_hireStaff"), href: "/hire" },
              ]}
            />
            <FooterCol
              title={t("footer_colResources")}
              icon={Library}
              links={[
                ...(hasAcademy() ? [{ label: t("footer_academy"), href: academyUrl(), external: true }] : []),
                { label: t("footer_helpDocs"), href: docsHref(), external: hasDocsSite() },
                { label: t("footer_glossary"), href: "/glossary" },
                { label: t("nav_compare"), href: "/compare" },
                { label: t("footer_allFeatures"), href: "/features" },
                { label: t("footer_whatsappCalc"), href: "/whatsapp-cost-calculator" },
                // 2026-09-12: "Contact" also lives in the Legal column below,
                // next to Terms and Privacy — where it reads as a notice address
                // rather than an invitation. A second entry here, pointing at
                // the contact BAND rather than a bare mailto, is the one a
                // visitor looking for help will actually find.
                { label: t("nav_contact"), href: "/#contact" },
              ]}
            />
            {/* Documentation hubs (2026-08-17). The four SEO/AEO hubs shipped
                2026-08-14 already crawlable and in sitemap.xml, but nothing in
                the site's own chrome linked to them — no internal links from
                the highest-authority page on the domain, and no way for a
                human to find them except by search. Keys live in en.json only
                for now: I18nProvider resolves `messages ?? enMessages`, so an
                untranslated locale renders the English string, and the dotted
                key path only ever surfaces when a key is missing from English
                too. The translation pipeline (scripts/i18n) backfills the rest
                on its next run. */}
            <FooterCol
              title={t("footer_colDocs")}
              icon={BookOpen}
              links={[
                { label: t("footer_docsIntegrations"), href: "/integrations" },
                { label: t("footer_docsAi"), href: "/ai" },
                { label: t("footer_docsPlatform"), href: "/platform" },
                { label: t("footer_docsTutorials"), href: "/tutorials" },
                // Developers (2026-10-12): the dependency, plugin and source links the OSS pages
                // carry, so the commercial pages and the open-source pages cross-link. Inline
                // English like "About" below: reference links, not marketing copy.
                { label: "Plugins & apps", href: "/plugins" },
                { label: "Java Agent SDK", href: docPageHref("javaSdk"), external: true },
                { label: "Open source", href: REPO_URL, external: true },
                { label: "Self-host guide", href: docPageHref("installation"), external: true },
              ]}
            />
            <FooterCol
              title={t("footer_colLegal")}
              icon={Scale}
              links={[
                // Founder/team transparency page — deliberately not run through
                // t() since it's a single English-language legal/trust page,
                // not marketing copy (see web/app/about/page.tsx).
                { label: "About", href: "/about" },
                { label: t("footer_terms"), href: "/terms" },
                { label: t("footer_privacy"), href: "/privacy" },
                // Payment-gateway reviewers (and customers) look for these together; inline English like "About"
                // above - single-language legal/trust pages, not marketing copy.
                { label: "Pricing", href: "/pricing" },
                { label: "Refund & Cancellation", href: "/refund-policy" },
                { label: "Shipping & Delivery", href: "/shipping-policy" },
                { label: t("footer_contact"), href: "/contact" },
              ]}
            />
          </div>
        </div>

        {/* Language versions as real links (multilingual SEO, 2026-09-19) —
            the header switcher is a cookie-writing button a crawler cannot
            follow; see LanguageLinks for the rule on which languages appear. */}
        <LanguageLinks pathname="/" current={locale} className="mt-12 border-t border-border/60 pt-6" />

        {/* Legal-entity bottom bar — the registered company, not the product
            name. Registration values come from lib/legalEntity.ts
            (deployment-configured); CIN/DIN render only when set. */}
        <div className="mt-12 border-t border-border/60 pt-6 text-xs text-muted-foreground">
          <div className="flex flex-col items-center justify-between gap-3 sm:flex-row">
            <span>
              © {year} {LEGAL_ENTITY}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <ShieldCheck className="size-3.5" /> {t("footer_secureLine")}
            </span>
          </div>
          {CIN_NUMBER || DIN_NUMBER ? (
            <div className="mt-2 text-center sm:text-left">
              <span>
                {[CIN_NUMBER && `CIN: ${CIN_NUMBER}`, DIN_NUMBER && `DIN: ${DIN_NUMBER}`].filter(Boolean).join(" · ")}
              </span>
            </div>
          ) : null}
          {/* 2026-07-29 category redesign: dropped the "…may result in legal
              action" closing threat — none of the reference-class homepages
              end on a warning to the reader. The ownership statement stays. */}
          <p className="mx-auto mt-4 max-w-3xl text-center leading-relaxed text-muted-foreground/80">
            {t("footer_rights", { company: LEGAL_ENTITY })}
          </p>
        </div>
      </div>
    </footer>
  );
}

type FooterLink = { label: string; href?: string; onClick?: () => void; external?: boolean };
function FooterCol({ title, icon: Icon, links }: { title: string; icon: LucideIcon; links: FooterLink[] }) {
  return (
    <div>
      <h3 className="inline-flex items-center gap-1.5 text-sm font-semibold">
        <Icon className="size-4 text-primary" aria-hidden="true" />
        {title}
      </h3>
      <ul className="mt-3 space-y-2 text-sm text-muted-foreground">
        {filterBillingItems(links).map((l) => (
          <li key={l.label}>
            {l.onClick ? (
              <button type="button" onClick={l.onClick} className="text-left transition-colors hover:text-foreground">
                {l.label}
              </button>
            ) : l.external ? (
              <a href={l.href} className="inline-flex items-center gap-1 transition-colors hover:text-foreground">
                {l.label}
                <ExternalLink className="size-3 opacity-70" aria-hidden="true" />
              </a>
            ) : l.href!.startsWith("#") ? (
              <a href={l.href} className="transition-colors hover:text-foreground">
                {l.label}
              </a>
            ) : (
              <Link href={l.href!} className="transition-colors hover:text-foreground">
                {l.label}
              </Link>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
