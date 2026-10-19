import Link from "next/link";
import Logo from "../Logo";
import HashLink from "./HashLink";
import BrandName from "../BrandName";
import { CIN_NUMBER, LEGAL_ENTITY } from "../../lib/legalEntity";
import { commercialUrl, docsHref, isExternalHref } from "../../lib/siteLinks";
import { COMMUNITY_LINKS, CONTACT_EMAIL, LICENSE_NAME, REPO_URL, resourceLinks } from "./ossSite";

// Header and footer shared by the open-source public pages. Server component.
// Brand mark, wordmark and operator details are the same sources the classic
// pages use (Logo, BrandName, lib/legalEntity.ts). Link targets that depend on
// runtime configuration (the documentation site, a hosted offering) come from
// lib/siteLinks.ts.

/** An in-app link (next/link) or an external one (<a> that opens safely). */
export function SmartLink({
  href,
  className,
  children,
}: {
  href: string;
  className?: string;
  children: React.ReactNode;
}) {
  if (isExternalHref(href)) {
    return (
      <a href={href} className={className} rel="noopener noreferrer">
        {children}
      </a>
    );
  }
  if (href.startsWith("/#")) {
    return (
      <HashLink href={href} className={className}>
        {children}
      </HashLink>
    );
  }
  return (
    <Link href={href} className={className}>
      {children}
    </Link>
  );
}

function navItems(): { label: string; href: string }[] {
  return [
    { label: "Docs", href: docsHref() },
    { label: "Downloads", href: "/downloads" },
    { label: "Dependencies", href: "/#dependencies" },
    { label: "Plugins & apps", href: "/plugins" },
    { label: "Integrations", href: "/integrations" },
    { label: "Changelog", href: "/changelog" },
    { label: "About", href: "/about" },
  ];
}

export function OssHeader() {
  const hosted = commercialUrl();
  return (
    <header className="sticky top-0 z-30 border-b border-border bg-background/85 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-4 px-4">
        <Link href="/" aria-label="VegaDūta home" className="flex items-center">
          <Logo size={30} />
        </Link>
        <nav aria-label="Primary" className="hidden items-center gap-5 text-sm text-muted-foreground md:flex">
          {navItems().map((n) => (
            <SmartLink key={n.label} href={n.href} className="hover:text-foreground">
              {n.label}
            </SmartLink>
          ))}
          <a href={REPO_URL} className="hover:text-foreground" rel="noopener noreferrer">
            GitHub
          </a>
          {hosted && (
            <a href={hosted} className="hover:text-foreground" rel="noopener noreferrer">
              Hosted version
            </a>
          )}
        </nav>
        <div className="flex items-center gap-2 text-sm">
          <Link href="/login" className="rounded-md px-3 py-1.5 text-muted-foreground hover:text-foreground">
            Sign in
          </Link>
          <Link
            href="/signup"
            className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground hover:opacity-90"
          >
            Get started
          </Link>
        </div>
      </div>
    </header>
  );
}

export function OssFooter() {
  const hosted = commercialUrl();
  const links = [
    ...resourceLinks(),
    ...COMMUNITY_LINKS,
    ...(hosted ? [{ label: "Hosted version", href: hosted }] : []),
  ];
  return (
    <footer className="border-t border-border">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-10 md:grid-cols-[1.2fr_2fr]">
        <div className="space-y-3 text-sm text-muted-foreground">
          <Logo size={28} />
          <p className="m-0 max-w-sm">
            <BrandName /> is open-source software released under the {LICENSE_NAME} license.
          </p>
          <p className="m-0">
            Maintained by {LEGAL_ENTITY}
            {CIN_NUMBER ? ` (CIN ${CIN_NUMBER})` : ""}.
          </p>
          {CONTACT_EMAIL && (
            <p className="m-0">
              <a className="text-primary hover:underline" href={`mailto:${CONTACT_EMAIL}`}>
                {CONTACT_EMAIL}
              </a>
            </p>
          )}
        </div>
        <ul className="m-0 grid list-none grid-cols-2 gap-x-6 gap-y-2 p-0 text-sm sm:grid-cols-3">
          {links.map((l) => (
            <li key={l.label}>
              <SmartLink href={l.href} className="text-muted-foreground hover:text-foreground">
                {l.label}
              </SmartLink>
            </li>
          ))}
          <li>
            <Link href="/terms" className="text-muted-foreground hover:text-foreground">
              Terms
            </Link>
          </li>
          <li>
            <Link href="/privacy" className="text-muted-foreground hover:text-foreground">
              Privacy
            </Link>
          </li>
          <li>
            <Link href="/contact" className="text-muted-foreground hover:text-foreground">
              Contact
            </Link>
          </li>
        </ul>
      </div>
      <div className="border-t border-border py-4 text-center text-xs text-muted-foreground">
        © {new Date().getFullYear()} {LEGAL_ENTITY}. <BrandName /> is licensed under {LICENSE_NAME}.
      </div>
    </footer>
  );
}
