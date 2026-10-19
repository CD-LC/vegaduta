"use client";

// The rail brand lockup: tenant mark or the VegaDuta emblem + wordmark. Lifted out of AppShell.tsx unchanged.

import Image from "next/image";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { usePlatformBusy } from "../../lib/platformBusy";


export default function RailBrand({
  collapsibleRail,
  hubMode,
  railLabelCls,
  showTenantBrand,
  brandLogoUrl,
  brandWordmark,
  orgInitials,
  onOpenBrand,
}: {
  collapsibleRail: boolean;
  hubMode: boolean;
  railLabelCls: string;
  showTenantBrand: boolean;
  brandLogoUrl: string | null;
  brandWordmark: string;
  orgInitials: string;
  onOpenBrand: () => void;
}) {
  const tNav = useTranslations("nav");
  const busy = usePlatformBusy();
  return (
      <div
        className={`flex h-16 min-w-0 shrink-0 items-center border-b border-[var(--surface-border)] ${
          collapsibleRail
            ? hubMode
              ? // Hub mode: the lane never widens on hover, so the mark
                // stays centered permanently (no hover-restore).
                "gap-2.5 px-4 lg:justify-center lg:gap-0 lg:px-0"
              : // Collapsed (lg, icon-only): center the mark in the 64px lane —
                // no left padding, no gap pulling it right, so it lines up with
                // the nav icons below and never runs into the overflow-x-hidden
                // clip. On hover the row expands back to the left-aligned
                // logo + wordmark lockup.
                "gap-2.5 px-4 lg:justify-center lg:gap-0 lg:px-0 lg:group-hover/rail:justify-start lg:group-hover/rail:gap-2.5 lg:group-hover/rail:px-4"
            : "gap-2.5 px-4"
        }`}
      >
        {showTenantBrand ? (
          // White-labeled tenant: no VegaDūta mark, no brand-identity
          // showcase trigger - just a plain, non-branded home link carrying
          // the TENANT's mark.
          //
          // The uploaded logo when there is one; the org-initials square
          // only as the fallback. Until 2026-09-11 the initials were all
          // this branch could render, because brand_logo_url was only
          // settable by curl-ing an absolute https URL and in practice
          // nobody had one - that placeholder is obsolete the moment a
          // tenant uploads via /admin/branding.
          //
          // Plain <img>, not next/image, matching the agent share page and
          // the embed widget: the logo is served from the API origin, which
          // would otherwise need a next.config remotePatterns entry.
          // Height-fixed with width free and a max, same as those two, so a
          // wide mark scales and a square one cannot blow out the rail.
          <Link
            href="/" prefetch={false}
            aria-label={tNav("homeAriaLabel")}
            className="flex h-9 shrink-0 items-center transition-opacity hover:opacity-80"
          >
            {brandLogoUrl ? (
              <img
                src={brandLogoUrl}
                alt={brandWordmark || ""}
                className="h-8 w-auto max-w-28 shrink-0 object-contain"
                draggable={false}
              />
            ) : (
              <span
                className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/15 text-xs font-bold uppercase text-primary"
                aria-hidden="true"
              >
                {orgInitials !== "?" ? orgInitials : ""}
              </span>
            )}
          </Link>
        ) : (
          <button
            type="button"
            aria-label={tNav("brandIdentityAriaLabel")}
            onClick={() => onOpenBrand()}
            /* border-0 bg-transparent p-0 shadow-none: neutralize the global
               `button {}` base rule (8px 14px padding + border), which made
               this 74px wide in the 63px collapsed rail - centered, it hung
               5px off-screen and the emblem looked cropped (2026-10-09). */
            className="flex h-9 shrink-0 items-center border-0 bg-transparent p-0 shadow-none transition-opacity hover:opacity-80"
          >
            {/* The winged human-in-helmet emblem, ALWAYS (direct instruction,
                2026-07-25: "our brand logo is always this one, not just V" /
                "ensure this human in helmet is properly displayed, next to
                brandname always and everywhere"). Reverses the earlier
                monogram-below-40px rule; the artwork renders in its own
                colors, untinted, height-fixed with width free (it's a wide/
                short emblem — no square tile, no frame). */}
            <span className={`vd-rail-mark${busy ? " vd-rail-busy" : ""}`}>
            <Image
              src="/logo-mark.png"
              alt="VegaDūta"
              height={32}
              width={44}
              // No hover or busy movement of the artwork itself: while the platform works it goes grey and a
              // blue band sweeps across it (.vd-rail-busy in components-brand.css).
              className="vd-rail-img h-8 w-auto shrink-0 object-contain"
              style={{ height: 32, width: "auto" }}
              draggable={false}
              priority
            />
            </span>
          </button>
        )}
        {/* Wordmark next to the mark, ALWAYS (same 2026-07-25 instruction as
            the emblem above) - this Link existed until 958154fc (2026-09-13)
            dropped it while moving the tenant org-name chip to the topbar,
            which left the rail showing the mark with nothing beside it.
            railLabelCls collapses this to zero width in the icon-only rail
            state so it doesn't offset the centered mark; restored on hover
            same as every other rail label.

            `brand-name` (icons-brand audit follow-up) is the class every
            other correct wordmark lockup in the app carries - it sets the
            Yatra One brand face and forces the nested `.text-primary` span
            to the fixed brand-orange (globals.css) instead of the
            tenant's rotating theme accent. This Link was missing it, so it
            rendered in the default sans font with the live accent colour
            instead of the brand's own. Only for the real VegaDūta wordmark
            - a white-label tenant's own free-text name must NOT be
            reskinned into VegaDūta's brand face/colour.

            `min-w-0 truncate` (responsive audit follow-up) replaces the
            old `whitespace-nowrap`: a white-label tenant's name is
            arbitrary free text with no length cap, and the mobile drawer
            is a fixed-width column with no horizontal clip of its own -
            a long name could overflow it edge to edge. */}
        <Link
          href="/" prefetch={false}
          className={`min-w-0 truncate text-lg tracking-tight ${showTenantBrand ? "" : "brand-name"} ${railLabelCls}`}
        >
          {showTenantBrand ? (
            brandWordmark
          ) : (
            <>
              Vega<span className="text-primary">Dūta</span>
            </>
          )}
        </Link>
      </div>
  );
}
