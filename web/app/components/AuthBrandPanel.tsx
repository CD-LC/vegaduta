"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import Logo from "./Logo";
import { useTranslations } from "../lib/i18n";

// Slim top strip shared by the public auth funnel (/login, /signup,
// /signup/complete): brand lockup, one short title, one optional sentence.
//
// Auth-simplify pass (2026-09-24). Until today this was a full-height 50/50
// brand banner (badge + big headline + three-point checklist + ambient
// SplitBrandMark scene) beside a tall form card. The maintainer, with a production
// screenshot of /signup at 1800px: "so much gap in register page, research
// and simplify" and "content width is not working in auth pages" - the
// headline sat at the bottom-left of an otherwise empty half. The current
// standard (Linear, Vercel, Stripe, Notion, Resend) is a single centred
// column: brand mark + short title at the top, then the form, one primary
// CTA, a one-line legal note and a "have an account? sign in" switch. This
// component is now that header; the column layout lives in
// app/auth.css (.vd-auth*).
//
// The brand stays the real emblem (public/logo-mark.png) next to the
// two-colour wordmark, via <Logo /> - never a redrawn mark. Exactly one <h1>
// per page: the caller passes the step's own title, so the page always has a
// level-1 heading at every width (the old md:hidden role="heading" mirror in
// signup/page.tsx is gone with the panel it mirrored).
export default function AuthBrandPanel({
  eyebrow,
  title,
  subtitle,
  logoHref = "/",
  children,
}: {
  /** Small mono label above the title (e.g. "Free account"). */
  eyebrow?: ReactNode;
  /** The page's single <h1>. */
  title?: ReactNode;
  /** One sentence under the title - never a paragraph. */
  subtitle?: ReactNode;
  /** Where the brand lockup links; the public landing by default. */
  logoHref?: string;
  /** Optional extra row under the subtitle (e.g. the sign-in / sign-up switch). */
  children?: ReactNode;
}) {
  const tHC = useTranslations("hcComponentsAuthBrandPanel");
  return (
    <header className="vd-auth__strip">
      <Link href={logoHref} className="vd-auth__brand" aria-label={tHC("s1")}>
        <Logo size={32} />
      </Link>
      {eyebrow && <p className="vd-auth__eyebrow">{eyebrow}</p>}
      {title && <h1 className="vd-auth__title">{title}</h1>}
      {subtitle && <p className="vd-auth__subtitle">{subtitle}</p>}
      {children}
    </header>
  );
}
