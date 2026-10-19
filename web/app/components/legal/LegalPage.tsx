import Link from "next/link";
import { Fragment, type ReactNode } from "react";
import BrandName from "../BrandName";
import { filterBillingItems } from "../../lib/billing-flag";
import { ENTITY_FACTS } from "../../lib/platform-facts";

/** The deployment's public support address (NEXT_PUBLIC_CONTACT_EMAIL). */
export const SUPPORT_EMAIL = ENTITY_FACTS.contactEmail;

/**
 * The support address as a mailto link that Cloudflare leaves alone. Cloudflare's "Email Address Obfuscation"
 * rewrites every address in the HTML to "[email protected]" (decoded by JavaScript), which a payment-gateway
 * reviewer's crawler or a no-JS visitor then sees instead of the address. Anything between email_off comments is
 * skipped by that rewrite. The markup is a constant, so dangerouslySetInnerHTML carries no user input.
 */
export function PublicEmail({
  address = SUPPORT_EMAIL,
  className = "text-primary hover:underline",
}: {
  /** A fixed, trusted address constant - never user input. */
  address?: string;
  className?: string;
}) {
  // The address comes from operator configuration (NEXT_PUBLIC_CONTACT_EMAIL):
  // trusted, but still escaped so a stray quote or angle bracket in a config
  // value can never become markup.
  const safeAddress = escapeHtml(address);
  const safeClass = escapeHtml(className);
  return (
    <span
      dangerouslySetInnerHTML={{
        __html: `<!--email_off--><a href="mailto:${safeAddress}" class="${safeClass}">${safeAddress}</a><!--/email_off-->`,
      }}
    />
  );
}

/** A paragraph in which every occurrence of the support address becomes a <PublicEmail/>. */
function withEmail(text: string): ReactNode {
  const parts = text.split(SUPPORT_EMAIL);
  return parts.map((part, i) => (
    <Fragment key={i}>
      {part}
      {i < parts.length - 1 && <PublicEmail />}
    </Fragment>
  ));
}

export interface LegalSection {
  h: string;
  /** Plain paragraphs; each string is one <p>. */
  p: string[];
}

const LINKS: { href: string; label: string }[] = [
  { href: "/pricing", label: "Pricing" },
  { href: "/terms", label: "Terms of Service" },
  { href: "/privacy", label: "Privacy Policy" },
  { href: "/refund-policy", label: "Refund & Cancellation" },
  { href: "/shipping-policy", label: "Shipping & Delivery" },
  { href: "/contact", label: "Contact us" },
];

/**
 * The shared chrome of the public policy pages (refund, shipping, contact): same look as /terms and /privacy, plus
 * the full set of policy links in the footer - payment-gateway reviewers and customers look for all of them together.
 */
export default function LegalPage({
  title,
  updated,
  intro,
  sections,
  children,
}: {
  title: string;
  updated: string;
  intro?: string;
  sections?: LegalSection[];
  children?: ReactNode;
}) {
  return (
    <main className="vd-pub min-h-screen w-full bg-background text-foreground">
      <div className="mx-auto max-w-3xl px-5 py-16 sm:px-8">
        <Link href="/" className="text-lg tracking-tight">
          <BrandName />
        </Link>
        <h1 className="mt-8 text-3xl font-semibold tracking-tight">{title}</h1>
        <p className="mt-2 text-sm text-muted-foreground">Last updated: {updated}</p>
        {intro && <p className="mt-6 text-pretty text-muted-foreground">{intro}</p>}
        <div className="mt-8 space-y-6">
          {sections?.map((s) => (
            <section key={s.h}>
              <h2 className="text-lg font-semibold">{s.h}</h2>
              {s.p.map((para) => (
                <p key={para} className="mt-1.5 text-pretty text-muted-foreground">
                  {withEmail(para)}
                </p>
              ))}
            </section>
          ))}
          {children}
        </div>
        <nav aria-label="Policies" className="mt-12 flex flex-wrap gap-x-3 gap-y-2 border-t border-border pt-6 text-sm">
          {filterBillingItems(LINKS).map((l, i) => (
            <span key={l.href} className="flex items-center gap-3">
              {i > 0 && (
                <span className="text-muted-foreground" aria-hidden="true">
                  ·
                </span>
              )}
              <Link href={l.href} className="text-primary hover:underline">
                {l.label}
              </Link>
            </span>
          ))}
        </nav>
      </div>
    </main>
  );
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
